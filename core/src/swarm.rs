use crate::config::ConfigStore;
use crate::error::CoreResult;
use crate::models::{StreamCandidate, SwarmFilter, DEFAULT_PUBLIC_TRACKERS};
use crate::stremio::{
    dedupe_and_sort_streams, extract_btih_from_magnet, futures_join_all, parse_resolution_score,
};
use quick_xml::events::Event;
use quick_xml::Reader;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;

pub struct SwarmSearchClient {
    http: reqwest::Client,
    config: Arc<ConfigStore>,
}

impl SwarmSearchClient {
    pub fn new(http: reqwest::Client, config: Arc<ConfigStore>) -> Self {
        Self { http, config }
    }

    /// Concurrently searches Internet Archive Public Domain video collections and any
    /// user-configured Torznab indexers.
    pub async fn search_all(
        &self,
        query: &str,
        imdb_id: Option<&str>,
        _filter_override: Option<SwarmFilter>,
    ) -> CoreResult<Vec<StreamCandidate>> {
        let q = query.trim();
        let imdb = imdb_id.unwrap_or("").trim();
        if q.is_empty() && imdb.is_empty() {
            return Ok(Vec::new());
        }

        let effective_query = if !q.is_empty() { q } else { imdb };
        let mut tasks: Vec<
            std::pin::Pin<Box<dyn std::future::Future<Output = Vec<StreamCandidate>> + Send>>,
        > = Vec::new();

        {
            let http = self.http.clone();
            let qs = format!(
                "({effective_query}) AND mediatype:movies AND (collection:feature_films OR collection:moviesandfilms OR collection:classic_tv)"
            );
            tasks.push(Box::pin(async move {
                search_internet_archive(&http, &qs, "InternetArchive").await
            }));
        }

        let (torznab_url, torznab_key) = self.config.torznab_config();
        if !torznab_url.is_empty() {
            let http = self.http.clone();
            let qs = effective_query.to_string();
            tasks.push(Box::pin(async move {
                search_torznab(&http, &torznab_url, &torznab_key, &qs).await
            }));
        }

        let mut all = Vec::new();
        for batch in futures_join_all(tasks).await {
            all.extend(batch);
        }
        Ok(dedupe_and_sort_streams(all))
    }

    /// Fetches curated Public Domain / user-configured Torznab Swarm feeds.
    pub async fn fetch_featured_feed(
        &self,
        feed_key: &str,
    ) -> CoreResult<Vec<StreamCandidate>> {
        let out = match feed_key {
            "archive_scifi" => {
                search_internet_archive(
                    &self.http,
                    "collection:scifi_horror AND mediatype:movies",
                    "InternetArchive",
                )
                .await
            }
            "archive_animation" => {
                search_internet_archive(
                    &self.http,
                    "collection:classic_cartoons AND mediatype:movies",
                    "InternetArchive",
                )
                .await
            }
            "torznab" => {
                let (torznab_url, torznab_key) = self.config.torznab_config();
                if torznab_url.is_empty() {
                    Vec::new()
                } else {
                    search_torznab(&self.http, &torznab_url, &torznab_key, "").await
                }
            }
            _ => {
                search_internet_archive(
                    &self.http,
                    "collection:feature_films AND mediatype:movies",
                    "InternetArchive",
                )
                .await
            }
        };
        Ok(dedupe_and_sort_streams(out))
    }
}

async fn search_internet_archive(
    http: &reqwest::Client,
    ia_query: &str,
    label: &str,
) -> Vec<StreamCandidate> {
    let url = format!(
        "https://archive.org/advancedsearch.php?q={}&fl[]=identifier&fl[]=title&fl[]=item_size&fl[]=downloads&sort[]=downloads+desc&rows=35&page=1&output=json",
        urlencoding::encode(ia_query)
    );
    let Ok(resp) = http.get(&url).send().await else {
        return Vec::new();
    };
    let Ok(root) = resp.json::<Value>().await else {
        return Vec::new();
    };
    let Some(docs) = root
        .get("response")
        .and_then(|r| r.get("docs"))
        .and_then(|d| d.as_array())
    else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for doc in docs {
        let Some(identifier) = doc
            .get("identifier")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
        else {
            continue;
        };
        let title = doc
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or(identifier)
            .trim();
        let size_bytes = doc
            .get("item_size")
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .unwrap_or(0);
        let downloads = doc
            .get("downloads")
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .unwrap_or(10);
        let seed_est = (downloads / 500).clamp(2, 150) as u32;
        let res = parse_resolution_score(title);
        let gb = (size_bytes as f64) / (1024.0 * 1024.0 * 1024.0);
        let size_label = if size_bytes > 0 {
            format!("{gb:.2} GB")
        } else {
            "Public Domain".to_string()
        };
        let torrent_url =
            format!("https://archive.org/download/{identifier}/{identifier}_archive.torrent");
        out.push(StreamCandidate {
            title: title.to_string(),
            subtitle: format!("{label} • {size_label} • 👤 {seed_est}"),
            addon_name: label.to_string(),
            direct_url: Some(torrent_url),
            info_hash: None,
            file_idx: None,
            sources: default_tracker_sources(),
            request_headers: HashMap::new(),
            external_url: Some(format!("https://archive.org/details/{identifier}")),
            binge_group: format!("archive-{res}"),
            filename: format!("{identifier}.mp4"),
            video_size_bytes: size_bytes,
            seeders: seed_est,
            resolution_score: res,
        });
    }
    out
}

/// Parses standard BitTorrent RSS `<item>` feeds (`<title>`, `<link>`/`<enclosure>`, `<infoHash>`, `<seeders>`, `<size>`).
pub fn parse_rss_torrent_xml(xml: &str, label: &str) -> Vec<StreamCandidate> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut out = Vec::new();
    let mut in_item = false;
    let mut cur_tag = String::new();
    let mut title = String::new();
    let mut link = String::new();
    let mut info_hash = String::new();
    let mut seeders = 0u32;
    let mut size_str = String::new();
    let mut size_bytes = 0u64;

    let mut buf = Vec::new();
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local = raw.rsplit(':').next().unwrap_or(&raw).to_ascii_lowercase();
                if local == "item" {
                    in_item = true;
                    title.clear();
                    link.clear();
                    info_hash.clear();
                    seeders = 0;
                    size_str.clear();
                    size_bytes = 0;
                } else if in_item {
                    cur_tag = local;
                }
            }
            Ok(Event::Empty(e)) if in_item => {
                let raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local = raw.rsplit(':').next().unwrap_or(&raw).to_ascii_lowercase();
                if local == "enclosure" {
                    for attr in e.attributes().flatten() {
                        if attr.key.as_ref() == b"url" && link.is_empty() {
                            link = String::from_utf8_lossy(&attr.value).to_string();
                        } else if attr.key.as_ref() == b"length" && size_bytes == 0 {
                            size_bytes = String::from_utf8_lossy(&attr.value)
                                .parse()
                                .unwrap_or(0);
                        }
                    }
                }
            }
            Ok(Event::Text(t)) if in_item => {
                let text = t.unescape().map(|c| c.into_owned()).unwrap_or_default();
                match cur_tag.as_str() {
                    "title" => title.push_str(&text),
                    "link" => link.push_str(&text),
                    "infohash" => info_hash.push_str(text.trim()),
                    "seeders" => seeders = text.trim().parse().unwrap_or(seeders),
                    "size" => size_str.push_str(text.trim()),
                    _ => {}
                }
            }
            Ok(Event::End(e)) => {
                let raw = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local = raw.rsplit(':').next().unwrap_or(&raw).to_ascii_lowercase();
                if local == "item" {
                    in_item = false;
                    if info_hash.is_empty() && link.starts_with("magnet:") {
                        if let Some(h) = extract_btih_from_magnet(&link) {
                            info_hash = h;
                        }
                    }
                    if size_bytes == 0 && !size_str.is_empty() {
                        size_bytes = parse_human_size_bytes(&size_str);
                    }
                    if !info_hash.is_empty() || link.starts_with("http") {
                        let res = parse_resolution_score(&title);
                        let disp_size = if !size_str.is_empty() {
                            size_str.clone()
                        } else if size_bytes > 0 {
                            format!("{:.2} GB", (size_bytes as f64) / (1024.0 * 1024.0 * 1024.0))
                        } else {
                            "RSS".to_string()
                        };
                        out.push(StreamCandidate {
                            title: title.clone(),
                            subtitle: format!("{label} • {disp_size} • 👤 {seeders}"),
                            addon_name: label.to_string(),
                            direct_url: if info_hash.is_empty() && link.starts_with("http") {
                                Some(link.clone())
                            } else {
                                None
                            },
                            info_hash: (!info_hash.is_empty())
                                .then(|| info_hash.to_ascii_lowercase()),
                            file_idx: None,
                            sources: default_tracker_sources(),
                            request_headers: HashMap::new(),
                            external_url: None,
                            binge_group: format!("rss-{}-{res}", label.to_ascii_lowercase()),
                            filename: title.clone(),
                            video_size_bytes: size_bytes,
                            seeders,
                            resolution_score: res,
                        });
                    }
                }
                cur_tag.clear();
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
    out
}

pub fn parse_torznab_xml(xml: &str) -> Vec<StreamCandidate> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut out = Vec::new();
    let mut in_item = false;
    let mut cur_tag = String::new();
    let mut title = String::new();
    let mut link = String::new();
    let mut info_hash = String::new();
    let mut seeders = 0u32;
    let mut size_bytes = 0u64;

    let mut buf = Vec::new();
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                if name == "item" {
                    in_item = true;
                    title.clear();
                    link.clear();
                    info_hash.clear();
                    seeders = 0;
                    size_bytes = 0;
                } else if in_item {
                    if name.ends_with("attr") {
                        parse_torznab_attr(&e, &mut info_hash, &mut seeders, &mut size_bytes);
                    }
                    cur_tag = name;
                }
            }
            Ok(Event::Empty(e)) if in_item => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                if name.ends_with("attr") {
                    parse_torznab_attr(&e, &mut info_hash, &mut seeders, &mut size_bytes);
                } else if name == "enclosure" {
                    for attr in e.attributes().flatten() {
                        if attr.key.as_ref() == b"url" && link.is_empty() {
                            link = String::from_utf8_lossy(&attr.value).to_string();
                        } else if attr.key.as_ref() == b"length" && size_bytes == 0 {
                            size_bytes = String::from_utf8_lossy(&attr.value)
                                .parse()
                                .unwrap_or(0);
                        }
                    }
                }
            }
            Ok(Event::Text(t)) if in_item => {
                let txt = t.unescape().map(|c| c.into_owned()).unwrap_or_default();
                match cur_tag.as_str() {
                    "title" => title.push_str(&txt),
                    "link" => link.push_str(&txt),
                    "size" => size_bytes = txt.trim().parse().unwrap_or(size_bytes),
                    _ => {}
                }
            }
            Ok(Event::End(e)) => {
                if e.name().as_ref() == b"item" {
                    in_item = false;
                    if info_hash.is_empty() && link.starts_with("magnet:") {
                        if let Some(h) = extract_btih_from_magnet(&link) {
                            info_hash = h;
                        }
                    }
                    if !info_hash.is_empty() {
                        let res = parse_resolution_score(&title);
                        let gb = (size_bytes as f64) / (1024.0 * 1024.0 * 1024.0);
                        out.push(StreamCandidate {
                            title: title.clone(),
                            subtitle: format!("Torznab • {gb:.2} GB • 👤 {seeders}"),
                            addon_name: "Torznab".to_string(),
                            direct_url: None,
                            info_hash: Some(info_hash.to_ascii_lowercase()),
                            file_idx: None,
                            sources: default_tracker_sources(),
                            request_headers: HashMap::new(),
                            external_url: None,
                            binge_group: format!("torznab-{res}"),
                            filename: title.clone(),
                            video_size_bytes: size_bytes,
                            seeders,
                            resolution_score: res,
                        });
                    }
                }
                cur_tag.clear();
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
    out
}

fn parse_torznab_attr(
    e: &quick_xml::events::BytesStart<'_>,
    info_hash: &mut String,
    seeders: &mut u32,
    size_bytes: &mut u64,
) {
    let mut k = String::new();
    let mut v = String::new();
    for attr in e.attributes().flatten() {
        if attr.key.as_ref() == b"name" {
            k = String::from_utf8_lossy(&attr.value).to_ascii_lowercase();
        } else if attr.key.as_ref() == b"value" {
            v = String::from_utf8_lossy(&attr.value).to_string();
        }
    }
    match k.as_str() {
        "infohash" => *info_hash = v.trim().to_ascii_lowercase(),
        "seeders" => *seeders = v.trim().parse().unwrap_or(*seeders),
        "size" => *size_bytes = v.trim().parse().unwrap_or(*size_bytes),
        _ => {}
    }
}

async fn search_torznab(
    http: &reqwest::Client,
    base_url: &str,
    api_key: &str,
    query: &str,
) -> Vec<StreamCandidate> {
    let sep = if base_url.contains('?') { "&" } else { "?" };
    let mut url = format!("{base_url}{sep}t=search&q={}", urlencoding::encode(query));
    if !api_key.is_empty() {
        url.push_str(&format!("&apikey={}", urlencoding::encode(api_key)));
    }
    let Ok(resp) = http.get(&url).send().await else {
        return Vec::new();
    };
    let Ok(xml) = resp.text().await else {
        return Vec::new();
    };
    parse_torznab_xml(&xml)
}

fn default_tracker_sources() -> Vec<String> {
    DEFAULT_PUBLIC_TRACKERS
        .iter()
        .map(|t| format!("tracker:{t}"))
        .collect()
}

fn parse_human_size_bytes(raw: &str) -> u64 {
    let parts: Vec<&str> = raw.split_whitespace().collect();
    if parts.len() < 2 {
        return 0;
    }
    let Ok(num) = parts[0].parse::<f64>() else {
        return 0;
    };
    let mult = match parts[1].to_ascii_uppercase().as_str() {
        "GIB" | "GB" => 1024.0 * 1024.0 * 1024.0,
        "MIB" | "MB" => 1024.0 * 1024.0,
        "KIB" | "KB" => 1024.0,
        _ => 1.0,
    };
    (num * mult) as u64
}
