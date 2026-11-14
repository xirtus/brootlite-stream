use crate::config::ConfigStore;
use crate::creators::{rewrite_master_playlist_for_proxy, strip_stitched_ads_from_media_playlist};
use crate::error::{CoreError, CoreResult};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use librqbit::{
    api::TorrentIdOrHash, AddTorrent, AddTorrentOptions, AddTorrentResponse, ManagedTorrent,
    Session, SessionOptions,
};
type ManagedTorrentHandle = Arc<ManagedTorrent>;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::SeekFrom;
use std::path::PathBuf;
use std::sync::{Arc, RwLock};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Mutex as AsyncMutex;
use uuid::Uuid;

const VIDEO_EXTENSIONS: &[&str] = &[".mkv", ".mp4", ".webm", ".avi", ".ts", ".m4v", ".mov"];
const STREAM_CHUNK_BYTES: usize = 128 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TorrentStreamSession {
    pub session_id: String,
    pub play_url: String,
    pub file_name: String,
    pub file_size_bytes: u64,
    pub file_idx: usize,
    pub info_hash: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct SwarmStatsSnapshot {
    pub session_id: String,
    pub state: String,
    pub download_speed_mbps: f64,
    pub progress_bytes: u64,
    pub total_bytes: u64,
    pub live_peers: u32,
    pub formatted_overlay: String,
}

enum RangeBacking {
    Memory {
        bytes: Arc<Vec<u8>>,
        content_type: String,
    },
    File {
        path: PathBuf,
        content_type: String,
    },
    Torrent {
        torrent_id: usize,
        handle: ManagedTorrentHandle,
        file_idx: usize,
        file_len: u64,
        content_type: String,
        cache_subdir: PathBuf,
    },
}

#[derive(Clone)]
struct TwitchProxyEntry {
    master_url: String,
    headers: HashMap<String, String>,
}

struct ServerSharedState {
    port: u16,
    http: reqwest::Client,
    dash_manifests: RwLock<HashMap<String, String>>,
    twitch_sessions: RwLock<HashMap<String, TwitchProxyEntry>>,
    range_sources: RwLock<HashMap<String, Arc<RangeBacking>>>,
}

/// Embedded loopback (`127.0.0.1:<port>`) HTTP/1.1 media server + `librqbit` v8 P2P engine:
/// 1. Serves RFC-9110 HTTP `206 Partial Content` (`Accept-Ranges: bytes`, `Range: bytes=start-end`)
///    for P2P torrent streams (with sliding-window piece prioritization & cache purge on stop)
///    and local/memory Range sources.
/// 2. Serves synthetic YouTube 1080p60 / 4K MPEG-DASH `.mpd` manifests (`/dash/{id}.mpd`).
/// 3. Serves proxied ad-free Twitch HLS playlists (`/twitch/{id}/master.m3u8` & `/media.m3u8`).
pub struct LocalMediaServer {
    config: Arc<ConfigStore>,
    shared: Arc<ServerSharedState>,
    rqbit_session: AsyncMutex<Option<Arc<Session>>>,
    shutdown_tx: tokio::sync::watch::Sender<bool>,
}

impl LocalMediaServer {
    pub async fn start(http: reqwest::Client, config: Arc<ConfigStore>) -> CoreResult<Arc<Self>> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let port = listener.local_addr()?.port();
        let (shutdown_tx, mut shutdown_rx) = tokio::sync::watch::channel(false);

        let shared = Arc::new(ServerSharedState {
            port,
            http,
            dash_manifests: RwLock::new(HashMap::new()),
            twitch_sessions: RwLock::new(HashMap::new()),
            range_sources: RwLock::new(HashMap::new()),
        });

        let accept_shared = shared.clone();
        tokio::spawn(async move {
            loop {
                tokio::select! {
                    changed = shutdown_rx.changed() => {
                        if changed.is_err() || *shutdown_rx.borrow() {
                            break;
                        }
                    }
                    accepted = listener.accept() => {
                        if let Ok((stream, _peer)) = accepted {
                            let st = accept_shared.clone();
                            tokio::spawn(async move {
                                let _ = handle_http_connection(stream, st).await;
                            });
                        }
                    }
                }
            }
        });

        Ok(Arc::new(Self {
            config,
            shared,
            rqbit_session: AsyncMutex::new(None),
            shutdown_tx,
        }))
    }

    pub fn port(&self) -> u16 {
        self.shared.port
    }

    pub fn base_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.shared.port)
    }

    // ── 1. Synthetic YouTube DASH `.mpd` Hosting ─────────────────────────────

    pub fn register_dash_manifest(&self, video_id: &str, mpd_xml: String) -> String {
        let safe_id: String = video_id
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '-' || *c == '_')
            .collect();
        let key = if safe_id.is_empty() {
            Uuid::new_v4().to_string()
        } else {
            format!("{safe_id}-{}", &Uuid::new_v4().to_string()[..8])
        };
        let mut map = self.shared.dash_manifests.write().unwrap();
        if map.len() >= 32 {
            if let Some(first_k) = map.keys().next().cloned() {
                map.remove(&first_k);
            }
        }
        map.insert(key.clone(), mpd_xml);
        format!("{}/dash/{key}.mpd", self.base_url())
    }

    // ── 2. Twitch Ad-Free HLS Loopback Proxy ─────────────────────────────────

    pub fn register_twitch_hls_session(
        &self,
        master_url: String,
        headers: HashMap<String, String>,
    ) -> String {
        let token = Uuid::new_v4().simple().to_string();
        let mut map = self.shared.twitch_sessions.write().unwrap();
        if map.len() >= 24 {
            if let Some(first_k) = map.keys().next().cloned() {
                map.remove(&first_k);
            }
        }
        map.insert(
            token.clone(),
            TwitchProxyEntry {
                master_url,
                headers,
            },
        );
        format!("{}/twitch/{token}/master.m3u8", self.base_url())
    }

    // ── 3. Memory / File Byte-Range Registration ─────────────────────────────

    pub fn register_memory_range_source(
        &self,
        bytes: Vec<u8>,
        content_type: &str,
    ) -> (String, String) {
        let id = Uuid::new_v4().simple().to_string();
        self.shared.range_sources.write().unwrap().insert(
            id.clone(),
            Arc::new(RangeBacking::Memory {
                bytes: Arc::new(bytes),
                content_type: content_type.to_string(),
            }),
        );
        let url = format!("{}/range/{id}", self.base_url());
        (id, url)
    }

    pub fn register_file_range_source(
        &self,
        path: PathBuf,
        content_type: &str,
    ) -> (String, String) {
        let id = Uuid::new_v4().simple().to_string();
        self.shared.range_sources.write().unwrap().insert(
            id.clone(),
            Arc::new(RangeBacking::File {
                path,
                content_type: content_type.to_string(),
            }),
        );
        let url = format!("{}/range/{id}", self.base_url());
        (id, url)
    }

    // ── 4. P2P BitTorrent Streaming (`librqbit` v8 + Sliding Window + Purge) ─

    async fn ensure_rqbit_session(&self) -> CoreResult<Arc<Session>> {
        let mut guard = self.rqbit_session.lock().await;
        if let Some(existing) = guard.as_ref() {
            return Ok(existing.clone());
        }
        let torrents_root = self.config.cache_dir().join("torrents");
        tokio::fs::create_dir_all(&torrents_root).await?;

        let opts = SessionOptions {
            disable_dht: false,
            disable_dht_persistence: true,
            fastresume: false,
            persistence: None,
            enable_upnp_port_forwarding: true,
            ..Default::default()
        };
        let session = Session::new_with_opts(torrents_root, opts)
            .await
            .map_err(|e| CoreError::Torrent(format!("librqbit session init failed: {e:#}")))?;
        *guard = Some(session.clone());
        Ok(session)
    }

    /// Starts streaming a magnet URI or `.torrent` URL, selecting either `requested_file_idx`
    /// or the largest video file, and exposes an HTTP `206 Partial Content` URL for `libmpv`.
    pub async fn start_torrent_stream(
        &self,
        magnet_or_url: &str,
        requested_file_idx: Option<usize>,
    ) -> CoreResult<TorrentStreamSession> {
        // Stop any previous active torrent sessions first so cache stays strictly bounded!
        self.stop_all_torrent_streams().await?;

        let session = self.ensure_rqbit_session().await?;
        let session_key = Uuid::new_v4().simple().to_string();
        let sub_dir_name = format!("sess_{session_key}");
        let cache_subdir = self
            .config
            .cache_dir()
            .join("torrents")
            .join(&sub_dir_name);
        tokio::fs::create_dir_all(&cache_subdir).await?;

        let add_opts = AddTorrentOptions {
            overwrite: true,
            output_folder: Some(cache_subdir.to_string_lossy().into_owned()),
            only_files: requested_file_idx.map(|i| vec![i]),
            ..Default::default()
        };

        let resp = session
            .add_torrent(AddTorrent::from_url(magnet_or_url), Some(add_opts))
            .await
            .map_err(|e| CoreError::Torrent(format!("failed to add torrent: {e:#}")))?;

        let (torrent_id, handle) = match resp {
            AddTorrentResponse::Added(id, h) | AddTorrentResponse::AlreadyManaged(id, h) => (id, h),
            AddTorrentResponse::ListOnly(_) => {
                return Err(CoreError::Torrent("unexpected list_only response".into()));
            }
        };

        handle
            .wait_until_initialized()
            .await
            .map_err(|e| CoreError::Torrent(format!("metadata resolution failed: {e:#}")))?;

        // Choose requested file index, or auto-pick largest video file
        let (chosen_idx, file_name, file_len) = select_best_video_file(&handle, requested_file_idx)?;
        if requested_file_idx.is_none() {
            let _ = session
                .update_only_files(&handle, &[chosen_idx].into_iter().collect())
                .await;
        }

        let content_type = guess_video_mime(&file_name).to_string();
        let info_hash = handle.info_hash().as_string();

        self.shared.range_sources.write().unwrap().insert(
            session_key.clone(),
            Arc::new(RangeBacking::Torrent {
                torrent_id,
                handle,
                file_idx: chosen_idx,
                file_len,
                content_type,
                cache_subdir,
            }),
        );

        Ok(TorrentStreamSession {
            play_url: format!("{}/torrent/{session_key}", self.base_url()),
            session_id: session_key,
            file_name,
            file_size_bytes: file_len,
            file_idx: chosen_idx,
            info_hash,
        })
    }

    pub fn get_torrent_stats(&self, session_id: &str) -> Option<SwarmStatsSnapshot> {
        let backing = self
            .shared
            .range_sources
            .read()
            .unwrap()
            .get(session_id)?
            .clone();
        let RangeBacking::Torrent { handle, .. } = backing.as_ref() else {
            return None;
        };
        let stats = handle.stats();
        let (mbps, peers) = stats
            .live
            .as_ref()
            .map(|l| (l.download_speed.mbps, l.snapshot.peer_stats.live as u32))
            .unwrap_or((0.0, 0));
        let pct = if stats.total_bytes > 0 {
            ((stats.progress_bytes as f64 / stats.total_bytes as f64) * 100.0).min(100.0)
        } else {
            0.0
        };
        Some(SwarmStatsSnapshot {
            session_id: session_id.to_string(),
            state: format!("{}", stats.state),
            download_speed_mbps: mbps,
            progress_bytes: stats.progress_bytes,
            total_bytes: stats.total_bytes,
            live_peers: peers,
            formatted_overlay: format!("↓ {mbps:.2} MB/s • {peers} Peers • {pct:.0}%"),
        })
    }

    /// Stops an active torrent stream and immediately purges its downloaded cache files.
    pub async fn stop_torrent_stream(&self, session_id: &str) -> CoreResult<()> {
        let removed = self
            .shared
            .range_sources
            .write()
            .unwrap()
            .remove(session_id);
        if let Some(backing) = removed {
            if let RangeBacking::Torrent {
                torrent_id,
                cache_subdir,
                ..
            } = backing.as_ref()
            {
                if let Some(sess) = self.rqbit_session.lock().await.clone() {
                    let _ = sess.delete(TorrentIdOrHash::Id(*torrent_id), true).await;
                }
                let _ = tokio::fs::remove_dir_all(cache_subdir).await;
            }
        }
        Ok(())
    }

    pub async fn stop_all_torrent_streams(&self) -> CoreResult<()> {
        let keys: Vec<String> = self
            .shared
            .range_sources
            .read()
            .unwrap()
            .iter()
            .filter_map(|(k, v)| match v.as_ref() {
                RangeBacking::Torrent { .. } => Some(k.clone()),
                _ => None,
            })
            .collect();
        for k in keys {
            let _ = self.stop_torrent_stream(&k).await;
        }
        Ok(())
    }

    pub fn shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
    }
}

impl Drop for LocalMediaServer {
    fn drop(&mut self) {
        let _ = self.shutdown_tx.send(true);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Lightweight HTTP/1.1 Connection & RFC-9110 Byte-Range Handler
// ─────────────────────────────────────────────────────────────────────────────

async fn handle_http_connection(
    mut sock: TcpStream,
    shared: Arc<ServerSharedState>,
) -> CoreResult<()> {
    let mut header_buf = Vec::with_capacity(2048);
    let mut byte = [0u8; 1024];
    loop {
        let n = sock.read(&mut byte).await?;
        if n == 0 {
            return Ok(());
        }
        header_buf.extend_from_slice(&byte[..n]);
        if header_buf.windows(4).any(|w| w == b"\r\n\r\n") || header_buf.len() > 16_384 {
            break;
        }
    }

    let req_text = String::from_utf8_lossy(&header_buf);
    let mut lines = req_text.lines();
    let Some(req_line) = lines.next() else {
        return Ok(());
    };
    let mut parts = req_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_ascii_uppercase();
    let raw_target = parts.next().unwrap_or("/");

    let mut range_header: Option<String> = None;
    for line in lines {
        if line.is_empty() {
            break;
        }
        if let Some((k, v)) = line.split_once(':') {
            if k.trim().eq_ignore_ascii_case("range") {
                range_header = Some(v.trim().to_string());
            }
        }
    }

    let (path, query) = raw_target
        .split_once('?')
        .map(|(p, q)| (p, Some(q)))
        .unwrap_or((raw_target, None));

    // Route 1: `/dash/{id}.mpd`
    if let Some(rest) = path.strip_prefix("/dash/") {
        let key = rest.strip_suffix(".mpd").unwrap_or(rest);
        let xml_opt = shared.dash_manifests.read().unwrap().get(key).cloned();
        match xml_opt {
            Some(xml) => {
                write_simple_response(
                    &mut sock,
                    "200 OK",
                    "application/dash+xml; charset=utf-8",
                    xml.as_bytes(),
                    method == "HEAD",
                )
                .await?;
            }
            None => {
                write_simple_response(&mut sock, "404 Not Found", "text/plain", b"Not Found", false)
                    .await?;
            }
        }
        return Ok(());
    }

    // Route 2: `/twitch/{session}/master.m3u8` and `/twitch/{session}/media.m3u8?u=...`
    if let Some(rest) = path.strip_prefix("/twitch/") {
        if let Some((token, tail)) = rest.split_once('/') {
            let entry_opt = shared.twitch_sessions.read().unwrap().get(token).cloned();
            let Some(entry) = entry_opt else {
                write_simple_response(
                    &mut sock,
                    "404 Not Found",
                    "text/plain",
                    b"Unknown Twitch Session",
                    false,
                )
                .await?;
                return Ok(());
            };

            if tail == "master.m3u8" {
                let mut req = shared.http.get(&entry.master_url);
                for (hk, hv) in &entry.headers {
                    req = req.header(hk, hv);
                }
                match req.send().await {
                    Ok(r) if r.status().is_success() => {
                        let raw_m3u8 = r.text().await.unwrap_or_default();
                        let proxy_prefix =
                            format!("http://127.0.0.1:{}/twitch/{token}/media.m3u8?u=", shared.port);
                        let rewritten = rewrite_master_playlist_for_proxy(
                            &raw_m3u8,
                            &entry.master_url,
                            &proxy_prefix,
                        );
                        write_simple_response(
                            &mut sock,
                            "200 OK",
                            "application/vnd.apple.mpegurl",
                            rewritten.as_bytes(),
                            method == "HEAD",
                        )
                        .await?;
                    }
                    _ => {
                        write_simple_response(
                            &mut sock,
                            "502 Bad Gateway",
                            "text/plain",
                            b"Upstream Twitch master playlist failed",
                            false,
                        )
                        .await?;
                    }
                }
                return Ok(());
            } else if tail == "media.m3u8" {
                let target_b64 = query
                    .unwrap_or("")
                    .split('&')
                    .find_map(|kv| kv.strip_prefix("u="))
                    .unwrap_or("");
                let Ok(decoded_bytes) = URL_SAFE_NO_PAD.decode(target_b64) else {
                    write_simple_response(
                        &mut sock,
                        "400 Bad Request",
                        "text/plain",
                        b"Bad media URL",
                        false,
                    )
                    .await?;
                    return Ok(());
                };
                let upstream_url = String::from_utf8_lossy(&decoded_bytes).into_owned();
                let mut req = shared.http.get(&upstream_url);
                for (hk, hv) in &entry.headers {
                    req = req.header(hk, hv);
                }
                match req.send().await {
                    Ok(r) if r.status().is_success() => {
                        let raw_media = r.text().await.unwrap_or_default();
                        let stripped =
                            strip_stitched_ads_from_media_playlist(&raw_media, Some(&upstream_url));
                        write_simple_response(
                            &mut sock,
                            "200 OK",
                            "application/vnd.apple.mpegurl",
                            stripped.as_bytes(),
                            method == "HEAD",
                        )
                        .await?;
                    }
                    _ => {
                        write_simple_response(
                            &mut sock,
                            "502 Bad Gateway",
                            "text/plain",
                            b"Upstream Twitch media playlist failed",
                            false,
                        )
                        .await?;
                    }
                }
                return Ok(());
            }
        }
    }

    // Route 3: `/range/{id}` or `/torrent/{id}` -> HTTP 206 Partial Content!
    let range_key = path
        .strip_prefix("/range/")
        .or_else(|| path.strip_prefix("/torrent/"));
    if let Some(id) = range_key {
        let backing_opt = shared.range_sources.read().unwrap().get(id).cloned();
        let Some(backing) = backing_opt else {
            write_simple_response(&mut sock, "404 Not Found", "text/plain", b"Not Found", false)
                .await?;
            return Ok(());
        };
        serve_range_backing(&mut sock, backing, range_header.as_deref(), method == "HEAD").await?;
        return Ok(());
    }

    write_simple_response(&mut sock, "404 Not Found", "text/plain", b"Not Found", false).await
}

async fn serve_range_backing(
    sock: &mut TcpStream,
    backing: Arc<RangeBacking>,
    range_header: Option<&str>,
    head_only: bool,
) -> CoreResult<()> {
    let (total_len, content_type) = match backing.as_ref() {
        RangeBacking::Memory {
            bytes,
            content_type,
        } => (bytes.len() as u64, content_type.as_str()),
        RangeBacking::File { path, content_type } => {
            let meta = tokio::fs::metadata(path).await?;
            (meta.len(), content_type.as_str())
        }
        RangeBacking::Torrent {
            file_len,
            content_type,
            ..
        } => (*file_len, content_type.as_str()),
    };

    if total_len == 0 {
        write_simple_response(sock, "200 OK", content_type, &[], true).await?;
        return Ok(());
    }

    let (is_partial, start, end) = match range_header {
        Some(hdr) => match parse_http_byte_range(hdr, total_len) {
            Some((s, e)) => (true, s, e),
            None => {
                let hdr_resp = format!(
                    "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */{total_len}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                );
                sock.write_all(hdr_resp.as_bytes()).await?;
                return Ok(());
            }
        },
        None => (false, 0, total_len - 1),
    };

    let content_len = end - start + 1;
    let status_line = if is_partial {
        "HTTP/1.1 206 Partial Content"
    } else {
        "HTTP/1.1 200 OK"
    };
    let content_range_line = if is_partial {
        format!("Content-Range: bytes {start}-{end}/{total_len}\r\n")
    } else {
        String::new()
    };

    let resp_headers = format!(
        "{status_line}\r\nContent-Type: {content_type}\r\nAccept-Ranges: bytes\r\n{content_range_line}Content-Length: {content_len}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
    );
    sock.write_all(resp_headers.as_bytes()).await?;
    if head_only {
        sock.flush().await?;
        return Ok(());
    }

    match backing.as_ref() {
        RangeBacking::Memory { bytes, .. } => {
            sock.write_all(&bytes[start as usize..=end as usize]).await?;
        }
        RangeBacking::File { path, .. } => {
            let mut f = tokio::fs::File::open(path).await?;
            f.seek(SeekFrom::Start(start)).await?;
            pump_exact_bytes(&mut f, sock, content_len).await?;
        }
        RangeBacking::Torrent {
            handle, file_idx, ..
        } => {
            let mut stream = handle
                .clone()
                .stream(*file_idx)
                .map_err(|e| CoreError::Torrent(format!("torrent stream open: {e:#}")))?;
            stream.seek(SeekFrom::Start(start)).await?;
            pump_exact_bytes(&mut stream, sock, content_len).await?;
        }
    }
    let _ = sock.flush().await;
    Ok(())
}

async fn pump_exact_bytes<R: tokio::io::AsyncRead + Unpin>(
    reader: &mut R,
    writer: &mut TcpStream,
    mut remaining: u64,
) -> CoreResult<()> {
    let mut buf = vec![0u8; STREAM_CHUNK_BYTES];
    while remaining > 0 {
        let to_read = (buf.len() as u64).min(remaining) as usize;
        let n = reader.read(&mut buf[..to_read]).await?;
        if n == 0 {
            break;
        }
        if writer.write_all(&buf[..n]).await.is_err() {
            // Client (e.g. libmpv seeking to a new Range) closed this Range socket cleanly
            break;
        }
        remaining -= n as u64;
    }
    Ok(())
}

async fn write_simple_response(
    sock: &mut TcpStream,
    status: &str,
    content_type: &str,
    body: &[u8],
    head_only: bool,
) -> CoreResult<()> {
    let hdr = format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nAccept-Ranges: bytes\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
        body.len()
    );
    sock.write_all(hdr.as_bytes()).await?;
    if !head_only && !body.is_empty() {
        sock.write_all(body).await?;
    }
    sock.flush().await?;
    Ok(())
}

/// Parses RFC-9110 `Range: bytes=start-end`, `bytes=start-`, and `bytes=-suffix`.
pub fn parse_http_byte_range(header: &str, total_len: u64) -> Option<(u64, u64)> {
    if total_len == 0 {
        return None;
    }
    let spec = header.trim().strip_prefix("bytes=")?;
    let first_range = spec.split(',').next()?.trim();
    let (start_s, end_s) = first_range.split_once('-')?;
    let start_s = start_s.trim();
    let end_s = end_s.trim();

    if start_s.is_empty() {
        // Suffix range `bytes=-N`
        let suffix: u64 = end_s.parse().ok()?;
        if suffix == 0 {
            return None;
        }
        let start = total_len.saturating_sub(suffix);
        Some((start, total_len - 1))
    } else {
        let start: u64 = start_s.parse().ok()?;
        if start >= total_len {
            return None;
        }
        let end = if end_s.is_empty() {
            total_len - 1
        } else {
            let parsed_end: u64 = end_s.parse().ok()?;
            if parsed_end < start {
                return None;
            }
            parsed_end.min(total_len - 1)
        };
        Some((start, end))
    }
}

fn select_best_video_file(
    handle: &ManagedTorrentHandle,
    requested_idx: Option<usize>,
) -> CoreResult<(usize, String, u64)> {
    handle
        .with_metadata(|meta| {
            if meta.file_infos.is_empty() {
                return Err(CoreError::Torrent("torrent contains 0 files".into()));
            }
            if let Some(idx) = requested_idx {
                if let Some(fi) = meta.file_infos.get(idx) {
                    return Ok((
                        idx,
                        fi.relative_filename.to_string_lossy().into_owned(),
                        fi.len,
                    ));
                }
            }
            let mut best: Option<(usize, String, u64, bool)> = None;
            for (idx, fi) in meta.file_infos.iter().enumerate() {
                let name = fi.relative_filename.to_string_lossy().into_owned();
                let lower = name.to_ascii_lowercase();
                let is_video = VIDEO_EXTENSIONS.iter().any(|ext| lower.ends_with(ext));
                match &best {
                    None => best = Some((idx, name, fi.len, is_video)),
                    Some((_, _, best_len, best_is_video)) => {
                        if (is_video && !*best_is_video)
                            || (is_video == *best_is_video && fi.len > *best_len)
                        {
                            best = Some((idx, name, fi.len, is_video));
                        }
                    }
                }
            }
            best.map(|(i, n, l, _)| (i, n, l))
                .ok_or_else(|| CoreError::Torrent("no playable file found".into()))
        })
        .map_err(|e| CoreError::Torrent(format!("torrent metadata unavailable: {e:#}")))?
}

fn guess_video_mime(filename: &str) -> &'static str {
    let lower = filename.to_ascii_lowercase();
    if lower.ends_with(".mp4") || lower.ends_with(".m4v") {
        "video/mp4"
    } else if lower.ends_with(".webm") {
        "video/webm"
    } else if lower.ends_with(".ts") {
        "video/mp2t"
    } else if lower.ends_with(".mov") {
        "video/quicktime"
    } else {
        "video/x-matroska"
    }
}
