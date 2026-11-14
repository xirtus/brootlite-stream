use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{
    AddonResourceSpec, InstalledAddon, StreamCandidate, SwarmFilter, DEFAULT_PUBLIC_TRACKERS,
};
use crate::stremio::{futures_join_all, parse_resolution_score};
use crate::swarm::parse_rss_torrent_xml;
use quick_xml::events::Event;
use quick_xml::Reader;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::Path;
use std::process::Stdio;

#[cfg(has_seed_pack)]
const EMBEDDED_SEED_PACK_JSON: Option<&str> = Some(include_str!("../seed_pack.json"));
#[cfg(not(has_seed_pack))]
const EMBEDDED_SEED_PACK_JSON: Option<&str> = None;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PackStremioAddon {
    pub id: String,
    pub name: String,
    pub transport_url: String,
    #[serde(default = "default_ver")]
    pub version: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_types")]
    pub types: Vec<String>,
    #[serde(default = "default_resources")]
    pub resources: Vec<String>,
    #[serde(default = "default_prefixes")]
    pub id_prefixes: Vec<String>,
}

fn default_ver() -> String {
    "1.0.0".into()
}
fn default_types() -> Vec<String> {
    vec!["movie".into(), "series".into(), "anime".into()]
}
fn default_resources() -> Vec<String> {
    vec!["stream".into()]
}
fn default_prefixes() -> Vec<String> {
    vec!["tt".into(), "kitsu".into()]
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PackSwarmIndexer {
    pub id: String,
    pub name: String,
    pub kind: String, // "rss" | "json_array_btih" | "json_movies_torrents"
    #[serde(default)]
    pub search_url_template: String,
    #[serde(default)]
    pub imdb_url_template: String,
    #[serde(default)]
    pub base_urls: Vec<String>,
    #[serde(default)]
    pub path_template: String,
    #[serde(default)]
    pub default_category: String,
    #[serde(default)]
    pub tab_group: String,
    #[serde(default)]
    pub adult: bool,
    #[serde(default)]
    pub imdb_fallback: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PackFeaturedFeed {
    pub key: String,
    pub label: String,
    pub kind: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub base_urls: Vec<String>,
    #[serde(default)]
    pub path: String,
    #[serde(default)]
    pub adult: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PackKodiRepo {
    pub id: String,
    pub name: String,
    pub zip_url: String,
    #[serde(default)]
    pub install_addons: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProviderPack {
    #[serde(default = "default_schema_v1")]
    pub schema_version: u32,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub trackers: Vec<String>,
    #[serde(default)]
    pub stremio_addons: Vec<PackStremioAddon>,
    #[serde(default)]
    pub swarm_indexers: Vec<PackSwarmIndexer>,
    #[serde(default)]
    pub featured_feeds: Vec<PackFeaturedFeed>,
    #[serde(default)]
    pub kodi_repositories: Vec<PackKodiRepo>,
}

fn default_schema_v1() -> u32 {
    1
}

impl ProviderPack {
    pub fn is_non_empty(&self) -> bool {
        !self.stremio_addons.is_empty()
            || !self.swarm_indexers.is_empty()
            || !self.kodi_repositories.is_empty()
            || !self.featured_feeds.is_empty()
    }

    pub fn merged_tracker_sources(&self) -> Vec<String> {
        let mut set = Vec::new();
        for t in DEFAULT_PUBLIC_TRACKERS {
            set.push(format!("tracker:{t}"));
        }
        for t in &self.trackers {
            let trimmed = t.trim();
            if !trimmed.is_empty() {
                let entry = if trimmed.starts_with("tracker:") {
                    trimmed.to_string()
                } else {
                    format!("tracker:{trimmed}")
                };
                if !set.contains(&entry) {
                    set.push(entry);
                }
            }
        }
        set
    }
}

/// Discovers optional runtime `provider_pack.json` in `data_dir`, adjacent bundle `seed_pack.json`,
/// or compile-time `EMBEDDED_SEED_PACK_JSON`.
pub fn load_startup_provider_pack(data_dir: &Path) -> Option<ProviderPack> {
    let runtime_file = data_dir.join("provider_pack.json");
    if let Ok(raw) = std::fs::read_to_string(&runtime_file) {
        if let Ok(pack) = serde_json::from_str::<ProviderPack>(&raw) {
            if pack.is_non_empty() {
                return Some(pack);
            }
        }
    }

    if let Ok(exe) = std::env::current_exe() {
        if let Some(exe_dir) = exe.parent() {
            let candidates = [
                exe_dir.join("seed_pack.json"),
                exe_dir.join("../Resources/seed_pack.json"),
            ];
            for cand in candidates {
                if let Ok(raw) = std::fs::read_to_string(&cand) {
                    if let Ok(pack) = serde_json::from_str::<ProviderPack>(&raw) {
                        if pack.is_non_empty() {
                            return Some(pack);
                        }
                    }
                }
            }
        }
    }

    if let Some(raw) = EMBEDDED_SEED_PACK_JSON {
        if let Ok(pack) = serde_json::from_str::<ProviderPack>(raw) {
            if pack.is_non_empty() {
                return Some(pack);
            }
        }
    }
    None
}

pub fn apply_pack_stremio_addons(pack: &ProviderPack, addons: &mut Vec<InstalledAddon>) {
    for pa in &pack.stremio_addons {
        let exists = addons.iter().any(|a| {
            a.id.eq_ignore_ascii_case(&pa.id)
                || a.transport_url.eq_ignore_ascii_case(&pa.transport_url)
        });
        if !exists {
            let base = pa
                .transport_url
                .strip_suffix("/manifest.json")
                .unwrap_or(&pa.transport_url)
                .trim_end_matches('/')
                .to_string();
            addons.push(InstalledAddon {
                id: pa.id.clone(),
                name: pa.name.clone(),
                version: pa.version.clone(),
                description: pa.description.clone(),
                transport_url: pa.transport_url.clone(),
                base_url: base,
                types: pa.types.clone(),
                resources: pa.resources.clone(),
                resource_specs: pa
                    .resources
                    .iter()
                    .map(|r| AddonResourceSpec {
                        name: r.clone(),
                        types: pa.types.clone(),
                        id_prefixes: pa.id_prefixes.clone(),
                    })
                    .collect(),
                id_prefixes: pa.id_prefixes.clone(),
                catalogs: Vec::new(),
            });
        }
    }
}

pub async fn import_provider_pack_from_source(
    http: &reqwest::Client,
    config: &ConfigStore,
    url_or_raw: &str,
) -> CoreResult<ProviderPack> {
    let trimmed = url_or_raw.trim();
    let raw_json = if trimmed.starts_with('{') {
        trimmed.to_string()
    } else if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        http.get(trimmed).send().await?.text().await?
    } else if let Some(rest) = trimmed.strip_prefix("file://") {
        std::fs::read_to_string(rest)?
    } else {
        std::fs::read_to_string(trimmed)?
    };

    let pack: ProviderPack = serde_json::from_str(&raw_json)
        .map_err(|e| CoreError::Protocol(format!("Invalid ProviderPack JSON: {e}")))?;
    if !pack.is_non_empty() {
        return Err(CoreError::Protocol(
            "ProviderPack contains no addons, indexers, or repositories".into(),
        ));
    }

    std::fs::write(config.data_dir().join("provider_pack.json"), &raw_json)?;
    config.set_provider_pack(Some(pack.clone()))?;

    let mut addons = config.addons();
    apply_pack_stremio_addons(&pack, &mut addons);
    config.set_addons(addons)?;

    if !pack.kodi_repositories.is_empty() {
        let http_bg = http.clone();
        let kodi_dir = config.kodi_env_dir().join("addons");
        let repos = pack.kodi_repositories.clone();
        tokio::spawn(async move {
            install_kodi_repositories_async(&http_bg, &kodi_dir, &repos).await;
        });
    }

    Ok(pack)
}

// ── Generic Declarative Swarm Search & IMDb Fallbacks ────────────────────────

pub async fn search_pack_indexers(
    http: &reqwest::Client,
    pack: &ProviderPack,
    query: &str,
    filter: SwarmFilter,
) -> Vec<StreamCandidate> {
    let trackers = pack.merged_tracker_sources();
    let q = query.trim();
    if q.is_empty() {
        return Vec::new();
    }

    let mut tasks: Vec<
        std::pin::Pin<Box<dyn std::future::Future<Output = Vec<StreamCandidate>> + Send>>,
    > = Vec::new();

    for idx in &pack.swarm_indexers {
        if filter == SwarmFilter::SafeOnly && idx.adult {
            continue;
        }
        if filter == SwarmFilter::AdultOnly && !idx.adult {
            continue;
        }
        let client = http.clone();
        let spec = idx.clone();
        let qs = q.to_string();
        let tr = trackers.clone();
        tasks.push(Box::pin(async move {
            run_single_indexer(&client, &spec, &qs, &tr).await
        }));
    }

    let mut out = Vec::new();
    for batch in futures_join_all(tasks).await {
        out.extend(batch);
    }
    out
}

pub async fn fetch_pack_featured_feed(
    http: &reqwest::Client,
    pack: &ProviderPack,
    feed_key: &str,
    filter: SwarmFilter,
) -> Option<Vec<StreamCandidate>> {
    let trackers = pack.merged_tracker_sources();
    let chosen = pack
        .featured_feeds
        .iter()
        .find(|f| f.key == feed_key)
        .or_else(|| {
            if feed_key == "archive_movies" || feed_key.is_empty() {
                pack.featured_feeds
                    .iter()
                    .find(|f| filter != SwarmFilter::SafeOnly || !f.adult)
            } else {
                None
            }
        })?;

    let list = match chosen.kind.as_str() {
        "rss" => fetch_rss_url(http, &chosen.url, &chosen.label, &trackers).await,
        "json_array_btih" => {
            fetch_json_array_btih(http, &chosen.url, &chosen.label, &trackers, None, None, false)
                .await
        }
        "json_movies_torrents" => {
            fetch_json_movies_torrents(
                http,
                &chosen.base_urls,
                &chosen.path,
                "",
                &chosen.label,
                &trackers,
            )
            .await
        }
        _ => Vec::new(),
    };
    Some(list)
}

pub async fn search_pack_imdb_fallbacks(
    http: &reqwest::Client,
    pack: &ProviderPack,
    video_id: &str,
) -> Vec<StreamCandidate> {
    let (root_tt, season, episode) = parse_imdb_video_id(video_id);
    if !root_tt.starts_with("tt") {
        return Vec::new();
    }
    let trackers = pack.merged_tracker_sources();
    let mut tasks: Vec<
        std::pin::Pin<Box<dyn std::future::Future<Output = Vec<StreamCandidate>> + Send>>,
    > = Vec::new();

    for idx in pack.swarm_indexers.iter().filter(|i| i.imdb_fallback) {
        let client = http.clone();
        let spec = idx.clone();
        let tt = root_tt.clone();
        let tr = trackers.clone();
        tasks.push(Box::pin(async move {
            match spec.kind.as_str() {
                "json_array_btih" => {
                    let tpl = if spec.imdb_url_template.is_empty() {
                        &spec.search_url_template
                    } else {
                        &spec.imdb_url_template
                    };
                    let url = tpl
                        .replace("{imdb}", &urlencoding::encode(&tt))
                        .replace("{query}", &urlencoding::encode(&tt))
                        .replace("{cat}", &spec.default_category);
                    fetch_json_array_btih(&client, &url, &spec.name, &tr, season, episode, true)
                        .await
                }
                "json_movies_torrents" if season.is_none() && episode.is_none() => {
                    fetch_json_movies_torrents(
                        &client,
                        &spec.base_urls,
                        &spec.path_template,
                        &tt,
                        &spec.name,
                        &tr,
                    )
                    .await
                }
                _ => Vec::new(),
            }
        }));
    }

    let mut out = Vec::new();
    for batch in futures_join_all(tasks).await {
        out.extend(batch);
    }
    out
}

fn parse_imdb_video_id(raw: &str) -> (String, Option<u32>, Option<u32>) {
    let parts: Vec<&str> = raw.trim().split(':').collect();
    let tt = parts.first().copied().unwrap_or("").to_string();
    let s = parts.get(1).and_then(|v| v.parse::<u32>().ok());
    let e = parts.get(2).and_then(|v| v.parse::<u32>().ok());
    (tt, s, e)
}

async fn run_single_indexer(
    http: &reqwest::Client,
    idx: &PackSwarmIndexer,
    query: &str,
    trackers: &[String],
) -> Vec<StreamCandidate> {
    let enc_q = urlencoding::encode(query);
    match idx.kind.as_str() {
        "rss" => {
            let url = idx
                .search_url_template
                .replace("{query}", &enc_q)
                .replace("{cat}", &idx.default_category);
            fetch_rss_url(http, &url, &idx.name, trackers).await
        }
        "json_array_btih" => {
            let url = idx
                .search_url_template
                .replace("{query}", &enc_q)
                .replace("{cat}", &idx.default_category);
            fetch_json_array_btih(http, &url, &idx.name, trackers, None, None, false).await
        }
        "json_movies_torrents" => {
            fetch_json_movies_torrents(
                http,
                &idx.base_urls,
                &idx.path_template,
                query,
                &idx.name,
                trackers,
            )
            .await
        }
        _ => Vec::new(),
    }
}

async fn fetch_rss_url(
    http: &reqwest::Client,
    url: &str,
    label: &str,
    trackers: &[String],
) -> Vec<StreamCandidate> {
    let Ok(resp) = http.get(url).send().await else {
        return Vec::new();
    };
    let Ok(xml) = resp.text().await else {
        return Vec::new();
    };
    let mut list = parse_rss_torrent_xml(&xml, label);
    for item in &mut list {
        item.sources = trackers.to_vec();
    }
    list
}

async fn fetch_json_array_btih(
    http: &reqwest::Client,
    url: &str,
    label: &str,
    trackers: &[String],
    season: Option<u32>,
    episode: Option<u32>,
    filter_episode: bool,
) -> Vec<StreamCandidate> {
    let Ok(resp) = http.get(url).send().await else {
        return Vec::new();
    };
    let Ok(arr_val) = resp.json::<Value>().await else {
        return Vec::new();
    };
    let Some(arr) = arr_val.as_array() else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for item in arr {
        let Some(name) = item
            .get("name")
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|s| !s.is_empty())
        else {
            continue;
        };
        let Some(hash) = item
            .get("info_hash")
            .and_then(|v| v.as_str())
            .map(|s| s.trim().to_ascii_lowercase())
            .filter(|h| h.len() == 40 && !h.chars().all(|c| c == '0'))
        else {
            continue;
        };
        let seeders = item
            .get("seeders")
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .unwrap_or(0) as u32;
        let size_bytes = item
            .get("size")
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .unwrap_or(0);
        let num_files = item
            .get("num_files")
            .and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()))
            .unwrap_or(1) as u32;

        if filter_episode {
            if let Some(s_num) = season {
                if !matches_season_episode(name, s_num, episode, num_files) {
                    continue;
                }
            }
        }

        let res = parse_resolution_score(name);
        let gb = (size_bytes as f64) / (1024.0 * 1024.0 * 1024.0);
        let size_str = if gb >= 1.0 {
            format!("{gb:.2} GB")
        } else {
            format!("{:.1} MB", (size_bytes as f64) / (1024.0 * 1024.0))
        };
        out.push(StreamCandidate {
            title: name.to_string(),
            subtitle: format!("{label} • 💾 {size_str} • 👤 {seeders} • ⚙️ {label}"),
            addon_name: label.to_string(),
            direct_url: None,
            info_hash: Some(hash),
            file_idx: None,
            sources: trackers.to_vec(),
            request_headers: HashMap::new(),
            external_url: None,
            binge_group: format!("pack-{}-{res}", label.to_ascii_lowercase()),
            filename: name.to_string(),
            video_size_bytes: size_bytes,
            seeders,
            resolution_score: res,
        });
    }
    out
}

fn matches_season_episode(name: &str, season: u32, episode: Option<u32>, num_files: u32) -> bool {
    let lower = name.to_ascii_lowercase();
    if let Some(ep) = episode {
        let s2 = format!("s{season:02}e{ep:02}");
        let x2 = format!("{season}x{ep:02}");
        if lower.contains(&s2) || lower.contains(&x2) {
            return true;
        }
    }
    if num_files > 1 {
        let s_tag = format!("s{season:02}");
        let s_word = format!("season {season}");
        let s_word0 = format!("season {season:02}");
        if lower.contains(&s_tag)
            || lower.contains(&s_word)
            || lower.contains(&s_word0)
            || lower.contains("complete")
        {
            return true;
        }
    }
    false
}

async fn fetch_json_movies_torrents(
    http: &reqwest::Client,
    base_urls: &[String],
    path_template: &str,
    query: &str,
    label: &str,
    trackers: &[String],
) -> Vec<StreamCandidate> {
    let enc_q = urlencoding::encode(query);
    let rel = path_template.replace("{query}", &enc_q);
    for base in base_urls {
        let url = format!("{}/{}", base.trim_end_matches('/'), rel.trim_start_matches('/'));
        let Ok(resp) = http.get(&url).send().await else {
            continue;
        };
        let Ok(root) = resp.json::<Value>().await else {
            continue;
        };
        let Some(movies) = root
            .get("data")
            .and_then(|d| d.get("movies"))
            .and_then(|m| m.as_array())
        else {
            continue;
        };

        let mut out = Vec::new();
        for mv in movies {
            let mv_title = mv
                .get("title_long")
                .or_else(|| mv.get("title"))
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let Some(torrents) = mv.get("torrents").and_then(|t| t.as_array()) else {
                continue;
            };
            for t in torrents {
                let Some(hash) = t
                    .get("hash")
                    .and_then(|v| v.as_str())
                    .map(|s| s.trim().to_ascii_lowercase())
                    .filter(|h| h.len() == 40)
                else {
                    continue;
                };
                let quality = t.get("quality").and_then(|v| v.as_str()).unwrap_or("");
                let codec = t.get("video_codec").and_then(|v| v.as_str()).unwrap_or("");
                let seeds = t.get("seeds").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
                let size_bytes = t.get("size_bytes").and_then(|v| v.as_u64()).unwrap_or(0);
                let size_str = t
                    .get("size")
                    .and_then(|v| v.as_str())
                    .map(String::from)
                    .unwrap_or_else(|| {
                        format!("{:.2} GB", (size_bytes as f64) / (1024.0 * 1024.0 * 1024.0))
                    });
                let full_title = format!("{mv_title} [{quality}] [{codec}]");
                let res = parse_resolution_score(&full_title);
                out.push(StreamCandidate {
                    title: full_title.clone(),
                    subtitle: format!("{label} • 💾 {size_str} • 👤 {seeds} • ⚙️ {label}"),
                    addon_name: label.to_string(),
                    direct_url: None,
                    info_hash: Some(hash),
                    file_idx: None,
                    sources: trackers.to_vec(),
                    request_headers: HashMap::new(),
                    external_url: None,
                    binge_group: format!("{}-{quality}-{codec}", label.to_ascii_lowercase()),
                    filename: full_title,
                    video_size_bytes: size_bytes,
                    seeders: seeds,
                    resolution_score: res,
                });
            }
        }
        if !out.is_empty() {
            return out;
        }
    }
    Vec::new()
}

// ── Standard Kodi `xbmc.addon.repository` Zip & Dependency Installer ─────────

pub async fn install_kodi_repositories_async(
    http: &reqwest::Client,
    addons_root: &Path,
    repos: &[PackKodiRepo],
) {
    let _ = std::fs::create_dir_all(addons_root);
    for repo in repos {
        if !addons_root.join(&repo.id).is_dir() && !repo.zip_url.is_empty() {
            let _ = download_and_extract_zip(http, &repo.zip_url, addons_root).await;
        }
        let repo_xml_path = addons_root.join(&repo.id).join("addon.xml");
        let Ok(repo_xml) = std::fs::read_to_string(&repo_xml_path) else {
            continue;
        };
        if repo.install_addons.is_empty() {
            continue;
        }
        let dirs = parse_repo_dirs(&repo_xml);
        if dirs.is_empty() {
            continue;
        }
        let catalog = fetch_kodi_catalog(http, &dirs).await;
        let mut visited = HashSet::new();
        let mut queue: Vec<String> = repo.install_addons.clone();
        while let Some(addon_id) = queue.pop() {
            if !visited.insert(addon_id.clone()) || addon_id.starts_with("xbmc.") {
                continue;
            }
            if !addons_root.join(&addon_id).is_dir() {
                if let Some((ver, datadir, _)) = catalog.get(&addon_id) {
                    let zip_url = format!(
                        "{}/{addon_id}/{addon_id}-{ver}.zip",
                        datadir.trim_end_matches('/')
                    );
                    let _ = download_and_extract_zip(http, &zip_url, addons_root).await;
                }
            }
            let local_xml_path = addons_root.join(&addon_id).join("addon.xml");
            let deps = if let Ok(local_xml) = std::fs::read_to_string(&local_xml_path) {
                parse_addon_imports(&local_xml)
            } else {
                catalog
                    .get(&addon_id)
                    .map(|(_, _, reqs)| reqs.clone())
                    .unwrap_or_default()
            };
            for d in deps {
                queue.push(d);
            }
        }
    }
}

fn parse_repo_dirs(xml: &str) -> Vec<(String, String)> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);
    let mut buf = Vec::new();
    let mut in_dir = false;
    let mut cur = String::new();
    let mut info = String::new();
    let mut datadir = String::new();
    let mut out = Vec::new();
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let n = String::from_utf8_lossy(e.name().as_ref()).to_ascii_lowercase();
                if n == "dir" {
                    in_dir = true;
                    info.clear();
                    datadir.clear();
                } else if in_dir {
                    cur = n;
                }
            }
            Ok(Event::Text(t)) if in_dir => {
                let txt = t.unescape().map(|c| c.into_owned()).unwrap_or_default();
                if cur == "info" && txt.starts_with("http") {
                    info = txt;
                } else if cur == "datadir" && txt.starts_with("http") {
                    datadir = txt;
                }
            }
            Ok(Event::End(e)) => {
                let n = String::from_utf8_lossy(e.name().as_ref()).to_ascii_lowercase();
                if n == "dir" && in_dir {
                    in_dir = false;
                    if !info.is_empty() && !datadir.is_empty() {
                        out.push((info.clone(), datadir.clone()));
                    }
                }
                cur.clear();
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
    out
}

async fn fetch_kodi_catalog(
    http: &reqwest::Client,
    dirs: &[(String, String)],
) -> HashMap<String, (String, String, Vec<String>)> {
    let mut map = HashMap::new();
    for (info_url, datadir) in dirs {
        let Ok(resp) = http.get(info_url).send().await else {
            continue;
        };
        let Ok(xml) = resp.text().await else {
            continue;
        };
        let mut reader = Reader::from_str(&xml);
        reader.config_mut().trim_text(true);
        let mut buf = Vec::new();
        let mut cur_id = String::new();
        let mut cur_ver = String::new();
        let mut reqs = Vec::new();
        loop {
            match reader.read_event_into(&mut buf) {
                Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                    let n = String::from_utf8_lossy(e.name().as_ref()).to_ascii_lowercase();
                    if n == "addon" {
                        cur_id.clear();
                        cur_ver.clear();
                        reqs.clear();
                        for attr in e.attributes().flatten() {
                            if attr.key.as_ref() == b"id" {
                                cur_id = String::from_utf8_lossy(&attr.value).to_string();
                            } else if attr.key.as_ref() == b"version" {
                                cur_ver = String::from_utf8_lossy(&attr.value).to_string();
                            }
                        }
                    } else if n == "import" && !cur_id.is_empty() {
                        let mut imp = String::new();
                        let mut optional = false;
                        for attr in e.attributes().flatten() {
                            if attr.key.as_ref() == b"addon" {
                                imp = String::from_utf8_lossy(&attr.value).to_string();
                            } else if attr.key.as_ref() == b"optional" {
                                optional =
                                    String::from_utf8_lossy(&attr.value).eq_ignore_ascii_case("true");
                            }
                        }
                        if !imp.is_empty() && !optional {
                            reqs.push(imp);
                        }
                    }
                }
                Ok(Event::End(e)) => {
                    if e.name().as_ref().eq_ignore_ascii_case(b"addon")
                        && !cur_id.is_empty()
                        && !cur_ver.is_empty()
                    {
                        map.entry(cur_id.clone()).or_insert((
                            cur_ver.clone(),
                            datadir.clone(),
                            reqs.clone(),
                        ));
                        cur_id.clear();
                    }
                }
                Ok(Event::Eof) | Err(_) => break,
                _ => {}
            }
            buf.clear();
        }
    }
    map
}

fn parse_addon_imports(xml: &str) -> Vec<String> {
    let mut reader = Reader::from_str(xml);
    let mut buf = Vec::new();
    let mut reqs = Vec::new();
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) | Ok(Event::Empty(e)) => {
                if e.name().as_ref().eq_ignore_ascii_case(b"import") {
                    let mut imp = String::new();
                    let mut optional = false;
                    for attr in e.attributes().flatten() {
                        if attr.key.as_ref() == b"addon" {
                            imp = String::from_utf8_lossy(&attr.value).to_string();
                        } else if attr.key.as_ref() == b"optional" {
                            optional =
                                String::from_utf8_lossy(&attr.value).eq_ignore_ascii_case("true");
                        }
                    }
                    if !imp.is_empty() && !optional {
                        reqs.push(imp);
                    }
                }
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
    reqs
}

async fn download_and_extract_zip(
    http: &reqwest::Client,
    zip_url: &str,
    dest_dir: &Path,
) -> CoreResult<()> {
    let bytes = http.get(zip_url).send().await?.bytes().await?;
    if bytes.is_empty() || bytes.len() > 60 * 1024 * 1024 {
        return Err(CoreError::Protocol("Invalid zip size".into()));
    }
    let tmp_zip = dest_dir.join(format!(".tmp-{}.zip", uuid::Uuid::new_v4()));
    std::fs::write(&tmp_zip, &bytes)?;

    let py_script = r#"
import os, sys, zipfile
zip_path, dest_root = sys.argv[1], os.path.realpath(sys.argv[2])
with zipfile.ZipFile(zip_path, 'r') as zf:
    for member in zf.infolist():
        target = os.path.realpath(os.path.join(dest_root, member.filename))
        if not (target == dest_root or target.startswith(dest_root + os.sep)):
            raise RuntimeError("Blocked zip-slip path")
        if member.is_dir():
            os.makedirs(target, exist_ok=True)
        else:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with zf.open(member) as src, open(target, 'wb') as dst:
                dst.write(src.read())
"#;

    let res = tokio::process::Command::new("python3")
        .arg("-I")
        .arg("-c")
        .arg(py_script)
        .arg(&tmp_zip)
        .arg(dest_dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
    let _ = std::fs::remove_file(&tmp_zip);
    match res {
        Ok(st) if st.success() => Ok(()),
        _ => Err(CoreError::Subprocess("Zip extraction failed".into())),
    }
}
