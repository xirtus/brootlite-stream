use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{
    now_epoch_ms, AddonCatalogDef, AddonResourceSpec, ExternalSubtitle, InstalledAddon, MetaItem,
    StreamCandidate, VideoEpisode, WatchHistoryEntry,
};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, OnceLock};

pub const STREMIO_API_BASE: &str = "https://api.strem.io/api";
pub const STREMIO_LINK_BASE: &str = "https://link.stremio.com/api/v2";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StremioLinkSession {
    pub code: String,
    pub link: String,
    pub qrcode_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct StremioSyncSummary {
    pub email: String,
    pub synced_addons_count: usize,
    pub synced_history_count: usize,
}

pub fn default_addons() -> Vec<InstalledAddon> {
    let movie_genres = vec![
        "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller", "War",
    ]
    .into_iter()
    .map(String::from)
    .collect::<Vec<_>>();

    let streaming_catalog_url = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibyxwbXAsamhzfHx8fHx8MTc3MTM2NTQyNTMwOQ%3D%3D/manifest.json";
    let streaming_base = streaming_catalog_url
        .strip_suffix("/manifest.json")
        .unwrap_or(streaming_catalog_url)
        .to_string();

    let mut streaming_catalogs = Vec::new();
    for (id, label) in [
        ("nfx", "Netflix"),
        ("dnp", "Disney+"),
        ("amp", "Prime Video"),
        ("atp", "Apple TV+"),
        ("hbo", "Max"),
        ("pmp", "Paramount+"),
    ] {
        streaming_catalogs.push(AddonCatalogDef {
            media_type: "movie".into(),
            id: id.into(),
            name: label.into(),
            genres: Vec::new(),
            supports_search: false,
            supports_genre: false,
            supports_skip: true,
        });
        streaming_catalogs.push(AddonCatalogDef {
            media_type: "series".into(),
            id: id.into(),
            name: label.into(),
            genres: Vec::new(),
            supports_search: false,
            supports_genre: false,
            supports_skip: true,
        });
    }

    vec![
        InstalledAddon {
            id: "com.linvo.cinemeta".into(),
            name: "Cinemeta".into(),
            version: "3.0.13".into(),
            description: "Official movie & series metadata and catalogs".into(),
            transport_url: "https://v3-cinemeta.strem.io/manifest.json".into(),
            base_url: "https://v3-cinemeta.strem.io".into(),
            types: vec!["movie".into(), "series".into()],
            resources: vec!["catalog".into(), "meta".into()],
            resource_specs: vec![
                AddonResourceSpec {
                    name: "catalog".into(),
                    types: vec!["movie".into(), "series".into()],
                    id_prefixes: Vec::new(),
                },
                AddonResourceSpec {
                    name: "meta".into(),
                    types: vec!["movie".into(), "series".into()],
                    id_prefixes: vec!["tt".into()],
                },
            ],
            id_prefixes: vec!["tt".into()],
            catalogs: vec![
                AddonCatalogDef {
                    media_type: "movie".into(),
                    id: "top".into(),
                    name: "Popular".into(),
                    genres: movie_genres.clone(),
                    supports_search: true,
                    supports_genre: true,
                    supports_skip: true,
                },
                AddonCatalogDef {
                    media_type: "series".into(),
                    id: "top".into(),
                    name: "Popular".into(),
                    genres: movie_genres.clone(),
                    supports_search: true,
                    supports_genre: true,
                    supports_skip: true,
                },
                AddonCatalogDef {
                    media_type: "movie".into(),
                    id: "imdbRating".into(),
                    name: "Featured".into(),
                    genres: movie_genres.clone(),
                    supports_search: false,
                    supports_genre: true,
                    supports_skip: true,
                },
                AddonCatalogDef {
                    media_type: "series".into(),
                    id: "imdbRating".into(),
                    name: "Featured".into(),
                    genres: movie_genres,
                    supports_search: false,
                    supports_genre: true,
                    supports_skip: true,
                },
            ],
        },
        InstalledAddon {
            id: "pw.ers.netflix-catalog".into(),
            name: "Streaming Catalogs".into(),
            version: "1.0.0".into(),
            description: "Netflix, Disney+, Prime Video, Apple TV+, Max & Paramount+ rows".into(),
            transport_url: streaming_catalog_url.into(),
            base_url: streaming_base,
            types: vec!["movie".into(), "series".into()],
            resources: vec!["catalog".into()],
            resource_specs: vec![AddonResourceSpec {
                name: "catalog".into(),
                types: vec!["movie".into(), "series".into()],
                id_prefixes: Vec::new(),
            }],
            id_prefixes: vec!["tt".into()],
            catalogs: streaming_catalogs,
        },
        InstalledAddon {
            id: "org.stremio.opensubtitlesv3".into(),
            name: "OpenSubtitles v3".into(),
            version: "1.0.0".into(),
            description: "Official multi-language external subtitles".into(),
            transport_url: "https://opensubtitles-v3.strem.io/manifest.json".into(),
            base_url: "https://opensubtitles-v3.strem.io".into(),
            types: vec!["movie".into(), "series".into()],
            resources: vec!["subtitles".into()],
            resource_specs: vec![AddonResourceSpec {
                name: "subtitles".into(),
                types: vec!["movie".into(), "series".into()],
                id_prefixes: vec!["tt".into()],
            }],
            id_prefixes: vec!["tt".into()],
            catalogs: Vec::new(),
        },
        InstalledAddon {
            id: "org.stremio.cinemeta-catalogs-halloweenpopular".into(),
            name: "Halloween Movies".into(),
            version: "1.0.0".into(),
            description: "Cinemeta's Halloween movies catalog".into(),
            transport_url: "https://cinemeta-catalogs.strem.io/halloweenpopular/manifest.json".into(),
            base_url: "https://cinemeta-catalogs.strem.io/halloweenpopular".into(),
            types: vec!["movie".into()],
            resources: vec!["catalog".into()],
            resource_specs: vec![AddonResourceSpec {
                name: "catalog".into(),
                types: vec!["movie".into()],
                id_prefixes: vec!["tt".into()],
            }],
            id_prefixes: vec!["tt".into()],
            catalogs: vec![AddonCatalogDef {
                media_type: "movie".into(),
                id: "halloweenpopular".into(),
                name: "Halloween Movies".into(),
                genres: Vec::new(),
                supports_search: false,
                supports_genre: false,
                supports_skip: true,
            }],
        },
    ]
}

pub struct StremioClient {
    http: reqwest::Client,
    config: Arc<ConfigStore>,
}

impl StremioClient {
    pub fn new(http: reqwest::Client, config: Arc<ConfigStore>) -> Self {
        Self { http, config }
    }

    // ── Stremio QR Link Auth & Two-Way Account Sync ──────────────────────────

    pub async fn create_qr_link_session(&self) -> CoreResult<StremioLinkSession> {
        let url = format!("{STREMIO_LINK_BASE}/create?type=Create");
        let root: Value = self.http.get(&url).send().await?.json().await?;
        let res = root
            .get("result")
            .ok_or_else(|| CoreError::Protocol("link.stremio.com missing result".into()))?;
        let code = res
            .get("code")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| CoreError::Protocol("empty link code".into()))?
            .to_string();
        let default_link = format!("https://link.stremio.com/?code={code}");
        let link = res
            .get("link")
            .and_then(|v| v.as_str())
            .unwrap_or(&default_link)
            .to_string();
        let qrcode_url = res
            .get("qrcode")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        Ok(StremioLinkSession {
            code,
            link,
            qrcode_url,
        })
    }

    /// Polls `link.stremio.com/api/v2/read?type=Read&code=...`. Returns `Ok(Some(auth_key))`
    /// once approved on the user's phone/browser, or `Ok(None)` while still waiting.
    pub async fn poll_qr_link_session(&self, code: &str) -> CoreResult<Option<String>> {
        let url = format!(
            "{STREMIO_LINK_BASE}/read?type=Read&code={}",
            urlencoding::encode(code)
        );
        let Ok(resp) = self.http.get(&url).send().await else {
            return Ok(None);
        };
        let Ok(root) = resp.json::<Value>().await else {
            return Ok(None);
        };
        if let Some(key) = root
            .get("result")
            .and_then(|r| r.get("authKey"))
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
        {
            return Ok(Some(key.to_string()));
        }
        Ok(None)
    }

    /// Syncs profile email (`getUser`), installed addons (`addonCollectionGet`), and two-way
    /// Continue Watching (`datastoreGet`) for a Stremio `authKey`.
    pub async fn sync_account_everything(&self, auth_key: &str) -> CoreResult<StremioSyncSummary> {
        // 1. getUser
        let user_resp: Value = self
            .http
            .post(format!("{STREMIO_API_BASE}/getUser"))
            .json(&json!({ "type": "GetUser", "authKey": auth_key }))
            .send()
            .await?
            .json()
            .await?;
        if let Some(err_msg) = user_resp
            .get("error")
            .and_then(|e| e.get("message"))
            .and_then(|v| v.as_str())
        {
            return Err(CoreError::Auth(format!("Stremio getUser failed: {err_msg}")));
        }
        let email = user_resp
            .get("result")
            .and_then(|r| r.get("email"))
            .and_then(|v| v.as_str())
            .unwrap_or("Stremio User")
            .to_string();
        self.config
            .set_stremio_auth(Some(auth_key), &email, Some(now_epoch_ms()))?;

        // 2. addonCollectionGet
        let mut synced_addons = Vec::new();
        if let Ok(col_resp) = self
            .http
            .post(format!("{STREMIO_API_BASE}/addonCollectionGet"))
            .json(&json!({
                "type": "AddonCollectionGet",
                "authKey": auth_key,
                "update": true
            }))
            .send()
            .await
        {
            if let Ok(col_json) = col_resp.json::<Value>().await {
                if let Some(arr) = col_json
                    .get("result")
                    .and_then(|r| r.get("addons"))
                    .and_then(|v| v.as_array())
                {
                    for entry in arr {
                        let transport_url = entry
                            .get("transportUrl")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .trim();
                        if transport_url.is_empty() {
                            continue;
                        }
                        if let Some(manifest) = entry.get("manifest") {
                            if let Ok(parsed) = parse_manifest_value(transport_url, manifest) {
                                synced_addons.push(parsed);
                            }
                        }
                    }
                }
            }
        }
        if !synced_addons.is_empty() {
            self.config.set_addons(synced_addons.clone())?;
        }

        // 3. datastoreGet (Continue Watching libraryitems)
        let mut synced_history_count = 0usize;
        if let Ok(ds_resp) = self
            .http
            .post(format!("{STREMIO_API_BASE}/datastoreGet"))
            .json(&json!({
                "authKey": auth_key,
                "collection": "libraryItem",
                "all": true
            }))
            .send()
            .await
        {
            if let Ok(ds_json) = ds_resp.json::<Value>().await {
                if let Some(items) = ds_json.get("result").and_then(|v| v.as_array()) {
                    let mut merged_history = self.config.watch_history();
                    for item in items {
                        let removed = item
                            .get("removed")
                            .and_then(|v| v.as_bool())
                            .unwrap_or(false);
                        let temp = item.get("temp").and_then(|v| v.as_bool()).unwrap_or(false);
                        if removed && temp {
                            continue;
                        }
                        let item_id = item
                            .get("_id")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        if item_id.is_empty() {
                            continue;
                        }
                        let Some(state) = item.get("state") else {
                            continue;
                        };
                        let time_offset = state
                            .get("timeOffset")
                            .and_then(|v| v.as_u64())
                            .unwrap_or(0);
                        let duration = state.get("duration").and_then(|v| v.as_u64()).unwrap_or(0);
                        if time_offset < 15_000 {
                            continue;
                        }
                        if duration > 0 && (time_offset as f64 / duration as f64) >= 0.94 {
                            continue;
                        }
                        let media_type = item
                            .get("type")
                            .and_then(|v| v.as_str())
                            .unwrap_or("movie")
                            .to_string();
                        let name = item
                            .get("name")
                            .and_then(|v| v.as_str())
                            .unwrap_or(&item_id)
                            .to_string();
                        let poster = item
                            .get("poster")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string();
                        let video_id = state
                            .get("video_id")
                            .and_then(|v| v.as_str())
                            .unwrap_or(&item_id)
                            .to_string();
                        let mtime_ms = item
                            .get("_mtime")
                            .and_then(|v| v.as_str())
                            .and_then(parse_iso_approx_epoch_ms)
                            .unwrap_or_else(now_epoch_ms);

                        match merged_history.iter_mut().find(|h| h.item_id == item_id) {
                            Some(existing) if existing.updated_at_ms >= mtime_ms => {}
                            Some(existing) => {
                                existing.position_ms = time_offset;
                                existing.duration_ms = duration;
                                existing.video_id = video_id;
                                existing.updated_at_ms = mtime_ms;
                                synced_history_count += 1;
                            }
                            None => {
                                merged_history.push(WatchHistoryEntry {
                                    item_id,
                                    media_type,
                                    title: name,
                                    poster,
                                    video_id,
                                    episode_title: String::new(),
                                    position_ms: time_offset,
                                    duration_ms: duration,
                                    updated_at_ms: mtime_ms,
                                    last_stream_context: None,
                                });
                                synced_history_count += 1;
                            }
                        }
                    }
                    self.config.replace_watch_history(merged_history)?;
                }
            }
        }

        Ok(StremioSyncSummary {
            email,
            synced_addons_count: synced_addons.len(),
            synced_history_count,
        })
    }

    /// Pushes watch progress back to Stremio's cloud `libraryItem` datastore (`datastorePut`).
    pub async fn push_watch_progress(&self, entry: &WatchHistoryEntry) -> CoreResult<bool> {
        let (Some(auth_key), _, _, _) = self.config.stremio_auth() else {
            return Ok(false);
        };
        if !entry.item_id.starts_with("tt") {
            return Ok(false);
        }
        let now_iso = epoch_ms_to_iso8601(entry.updated_at_ms.max(now_epoch_ms()));
        let change = json!({
            "_id": entry.item_id,
            "name": entry.title,
            "type": entry.media_type,
            "poster": entry.poster,
            "removed": false,
            "temp": true,
            "_ctime": now_iso,
            "_mtime": now_iso,
            "state": {
                "lastWatched": now_iso,
                "timeWatched": entry.position_ms,
                "timeOffset": entry.position_ms,
                "overallTimeWatched": entry.position_ms,
                "timesWatched": if entry.progress_ratio() >= 0.90 { 1 } else { 0 },
                "flaggedWatched": if entry.progress_ratio() >= 0.90 { 1 } else { 0 },
                "duration": entry.duration_ms,
                "video_id": if entry.video_id.is_empty() { &entry.item_id } else { &entry.video_id },
                "noNotif": true
            }
        });
        let resp = self
            .http
            .post(format!("{STREMIO_API_BASE}/datastorePut"))
            .json(&json!({
                "authKey": auth_key,
                "collection": "libraryItem",
                "changes": [change]
            }))
            .send()
            .await?;
        Ok(resp.status().is_success())
    }

    // ── Addon Manifest Installation ──────────────────────────────────────────

    pub async fn install_addon_from_url(&self, raw_url: &str) -> CoreResult<InstalledAddon> {
        let normalized = normalize_manifest_url(raw_url)?;
        let resp = self.http.get(&normalized).send().await?;
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "Manifest fetch failed ({}) for {normalized}",
                resp.status()
            )));
        }
        let val: Value = resp.json().await?;
        let addon = parse_manifest_value(&normalized, &val)?;
        self.config.upsert_addon(addon.clone())?;
        Ok(addon)
    }

    // ── Catalog Browsing, Search, Meta Detail, Streams, Subtitles ────────────

    pub async fn fetch_catalog(
        &self,
        addon: &InstalledAddon,
        media_type: &str,
        catalog_id: &str,
        search_query: Option<&str>,
        genre: Option<&str>,
        year: Option<&str>,
        skip: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        fetch_catalog_http(
            &self.http,
            addon,
            media_type,
            catalog_id,
            search_query,
            genre,
            year,
            skip,
        )
        .await
    }

    pub async fn search_catalogs(&self, query: &str) -> CoreResult<Vec<MetaItem>> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return Ok(Vec::new());
        }
        let addons = self.config.addons();
        let mut futs = Vec::new();
        for addon in addons {
            for cat in addon.catalogs.clone() {
                if cat.supports_search {
                    let http = self.http.clone();
                    let addon_clone = addon.clone();
                    let q = trimmed.to_string();
                    futs.push(async move {
                        fetch_catalog_http(
                            &http,
                            &addon_clone,
                            &cat.media_type,
                            &cat.id,
                            Some(&q),
                            None,
                            None,
                            0,
                        )
                        .await
                        .unwrap_or_default()
                    });
                }
            }
        }

        let mut seen = HashSet::new();
        let mut combined = Vec::new();
        for batch in futures_join_all(futs).await {
            for item in batch {
                if seen.insert(item.id.clone()) {
                    combined.push(item);
                }
            }
        }
        Ok(combined)
    }

    pub async fn fetch_meta_detail(
        &self,
        media_type: &str,
        item_id: &str,
    ) -> CoreResult<Option<MetaItem>> {
        let addons = self.config.addons();
        for addon in &addons {
            let Some(effective_type) = resolve_resource_type(addon, "meta", media_type, item_id)
            else {
                continue;
            };
            let url = format!(
                "{}/meta/{}/{}.json",
                addon.base_url.trim_end_matches('/'),
                encode_stremio_path_val(&effective_type),
                encode_stremio_path_val(item_id)
            );
            let Ok(resp) = self.http.get(&url).send().await else {
                continue;
            };
            if !resp.status().is_success() {
                continue;
            }
            let Ok(root) = resp.json::<Value>().await else {
                continue;
            };
            if let Some(meta_obj) = root.get("meta") {
                if let Some(parsed) = parse_meta_item(meta_obj, &effective_type, &addon.name) {
                    return Ok(Some(parsed));
                }
            }
        }
        Ok(None)
    }

    /// Concurrently queries all installed stream addons (including cross-type `tt...` IMDb
    /// matching for anime/custom catalogs), then deduplicates and sorts streams with live seeders prioritized.
    pub async fn fetch_streams(
        &self,
        media_type: &str,
        video_id: &str,
    ) -> CoreResult<Vec<StreamCandidate>> {
        let addons = self.config.addons();
        let mut tasks: Vec<
            std::pin::Pin<Box<dyn std::future::Future<Output = Vec<StreamCandidate>> + Send>>,
        > = Vec::new();
        for addon in addons {
            if let Some(effective_type) =
                resolve_resource_type(&addon, "stream", media_type, video_id)
            {
                let http = self.http.clone();
                let vid = video_id.to_string();
                tasks.push(Box::pin(async move {
                    fetch_streams_from_single_addon(&http, &addon, &effective_type, &vid)
                        .await
                        .unwrap_or_default()
                }));
            }
        }
        let mut flat = Vec::new();
        for batch in futures_join_all(tasks).await {
            flat.extend(batch);
        }
        Ok(dedupe_and_sort_streams(flat))
    }

    pub async fn fetch_subtitles(
        &self,
        media_type: &str,
        video_id: &str,
    ) -> CoreResult<Vec<ExternalSubtitle>> {
        let addons = self.config.addons();
        let mut tasks = Vec::new();
        for addon in addons {
            if let Some(effective_type) =
                resolve_resource_type(&addon, "subtitles", media_type, video_id)
            {
                let http = self.http.clone();
                let vid = video_id.to_string();
                tasks.push(async move {
                    let url = format!(
                        "{}/subtitles/{}/{}.json",
                        addon.base_url.trim_end_matches('/'),
                        encode_stremio_path_val(&effective_type),
                        encode_stremio_path_val(&vid)
                    );
                    let Ok(resp) = http.get(&url).send().await else {
                        return Vec::new();
                    };
                    let Ok(root) = resp.json::<Value>().await else {
                        return Vec::new();
                    };
                    let mut subs = Vec::new();
                    if let Some(arr) = root.get("subtitles").and_then(|v| v.as_array()) {
                        for s in arr {
                            let sub_url = s
                                .get("url")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .trim();
                            if sub_url.is_empty() {
                                continue;
                            }
                            subs.push(ExternalSubtitle {
                                id: s
                                    .get("id")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or(sub_url)
                                    .to_string(),
                                url: sub_url.to_string(),
                                lang: s
                                    .get("lang")
                                    .and_then(|v| v.as_str())
                                    .unwrap_or("und")
                                    .to_string(),
                                addon_name: addon.name.clone(),
                            });
                        }
                    }
                    subs
                });
            }
        }
        let mut out = Vec::new();
        let mut seen = HashSet::new();
        for batch in futures_join_all(tasks).await {
            for s in batch {
                if seen.insert(s.url.clone()) {
                    out.push(s);
                }
            }
        }
        Ok(out)
    }
}

async fn fetch_catalog_http(
    http: &reqwest::Client,
    addon: &InstalledAddon,
    media_type: &str,
    catalog_id: &str,
    search_query: Option<&str>,
    genre: Option<&str>,
    year: Option<&str>,
    skip: u32,
) -> CoreResult<Vec<MetaItem>> {
    let mut extra_parts = Vec::new();
    if let Some(q) = search_query.map(str::trim).filter(|s| !s.is_empty()) {
        extra_parts.push(format!("search={}", encode_stremio_path_val(q)));
    }
    if let Some(g) = genre
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("All"))
    {
        extra_parts.push(format!("genre={}", encode_stremio_path_val(g)));
    }
    if let Some(y) = year
        .map(str::trim)
        .filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("All"))
    {
        extra_parts.push(format!("year={}", encode_stremio_path_val(y)));
    }
    if skip > 0 {
        extra_parts.push(format!("skip={skip}"));
    }

    let extra_segment = if extra_parts.is_empty() {
        String::new()
    } else {
        format!("/{}", extra_parts.join("&"))
    };
    let url = format!(
        "{}/catalog/{}/{}{}.json",
        addon.base_url.trim_end_matches('/'),
        encode_stremio_path_val(media_type),
        encode_stremio_path_val(catalog_id),
        extra_segment
    );

    let resp = http.get(&url).send().await?;
    if !resp.status().is_success() {
        return Ok(Vec::new());
    }
    let root: Value = resp.json().await?;
    let mut out = Vec::new();
    if let Some(metas) = root.get("metas").and_then(|v| v.as_array()) {
        for m in metas {
            if let Some(item) = parse_meta_item(m, media_type, &addon.name) {
                out.push(item);
            }
        }
    }
    Ok(out)
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure Parsing & Sorting Helpers
// ─────────────────────────────────────────────────────────────────────────────

pub fn normalize_manifest_url(raw: &str) -> CoreResult<String> {
    let mut u = raw.trim().to_string();
    if u.is_empty() {
        return Err(CoreError::InvalidInput("Addon URL is empty".into()));
    }
    if let Some(rest) = u.strip_prefix("stremio://") {
        u = format!("https://{rest}");
    } else if !u.starts_with("http://") && !u.starts_with("https://") {
        u = format!("https://{u}");
    }
    // Strip query string before checking /manifest.json if needed
    if !u.contains("/manifest.json") {
        u = format!("{}/manifest.json", u.trim_end_matches('/'));
    }
    let _ = url::Url::parse(&u)?;
    Ok(u)
}

pub fn parse_manifest_value(transport_url: &str, val: &Value) -> CoreResult<InstalledAddon> {
    let id = val
        .get("id")
        .and_then(|v| v.as_str())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| CoreError::Protocol("Addon manifest missing id".into()))?
        .to_string();
    let name = val
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or(&id)
        .to_string();
    let version = val
        .get("version")
        .and_then(|v| v.as_str())
        .unwrap_or("1.0.0")
        .to_string();
    let description = val
        .get("description")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    let types = json_string_vec(val.get("types"));
    let id_prefixes = json_string_vec(val.get("idPrefixes"));

    let mut resources = Vec::new();
    let mut resource_specs = Vec::new();
    if let Some(res_arr) = val.get("resources").and_then(|v| v.as_array()) {
        for r in res_arr {
            if let Some(s) = r.as_str() {
                resources.push(s.to_string());
                resource_specs.push(AddonResourceSpec {
                    name: s.to_string(),
                    types: types.clone(),
                    id_prefixes: id_prefixes.clone(),
                });
            } else if let Some(obj) = r.as_object() {
                if let Some(r_name) = obj.get("name").and_then(|v| v.as_str()) {
                    resources.push(r_name.to_string());
                    resource_specs.push(AddonResourceSpec {
                        name: r_name.to_string(),
                        types: json_string_vec(obj.get("types")),
                        id_prefixes: json_string_vec(obj.get("idPrefixes")),
                    });
                }
            }
        }
    }

    let mut catalogs = Vec::new();
    if let Some(cat_arr) = val.get("catalogs").and_then(|v| v.as_array()) {
        for c in cat_arr {
            let Some(c_type) = c.get("type").and_then(|v| v.as_str()) else {
                continue;
            };
            let Some(c_id) = c.get("id").and_then(|v| v.as_str()) else {
                continue;
            };
            let c_name = c.get("name").and_then(|v| v.as_str()).unwrap_or(c_id);
            let mut genres = json_string_vec(c.get("genres"));
            let mut supports_search = false;
            let mut supports_genre = !genres.is_empty();
            let mut supports_skip = false;

            if let Some(extra_arr) = c.get("extra").and_then(|v| v.as_array()) {
                for ex in extra_arr {
                    let ex_name = ex.get("name").and_then(|v| v.as_str()).unwrap_or("");
                    match ex_name {
                        "search" => supports_search = true,
                        "skip" => supports_skip = true,
                        "genre" => {
                            supports_genre = true;
                            if genres.is_empty() {
                                genres = json_string_vec(ex.get("options"));
                            }
                        }
                        _ => {}
                    }
                }
            }
            if let Some(extra_sup) = c.get("extraSupported").and_then(|v| v.as_array()) {
                for es in extra_sup.iter().filter_map(|v| v.as_str()) {
                    match es {
                        "search" => supports_search = true,
                        "skip" => supports_skip = true,
                        "genre" => supports_genre = true,
                        _ => {}
                    }
                }
            }

            catalogs.push(AddonCatalogDef {
                media_type: c_type.to_string(),
                id: c_id.to_string(),
                name: c_name.to_string(),
                genres,
                supports_search,
                supports_genre,
                supports_skip,
            });
        }
    }

    let base_url = transport_url
        .split_once("/manifest.json")
        .map(|(b, _)| b.trim_end_matches('/').to_string())
        .unwrap_or_else(|| transport_url.trim_end_matches('/').to_string());

    Ok(InstalledAddon {
        id,
        name,
        version,
        description,
        transport_url: transport_url.to_string(),
        base_url,
        types,
        resources,
        resource_specs,
        id_prefixes,
        catalogs,
    })
}

pub fn resolve_resource_type(
    addon: &InstalledAddon,
    resource_name: &str,
    requested_type: &str,
    item_id: &str,
) -> Option<String> {
    if addon.supports_resource(resource_name, requested_type, item_id) {
        return Some(requested_type.to_string());
    }
    // Cross-type fallback for IMDb (`tt...`) or Kitsu (`kitsu:...`) items from custom catalogs
    if item_id.starts_with("tt") || item_id.starts_with("kitsu:") {
        let inferred = if item_id.matches(':').count() >= 2 {
            "series"
        } else {
            "movie"
        };
        if addon.supports_resource(resource_name, inferred, item_id) {
            return Some(inferred.to_string());
        }
        for alt in ["movie", "series", "anime"] {
            if addon.supports_resource(resource_name, alt, item_id) {
                return Some(alt.to_string());
            }
        }
    }
    None
}

async fn fetch_streams_from_single_addon(
    http: &reqwest::Client,
    addon: &InstalledAddon,
    effective_type: &str,
    video_id: &str,
) -> CoreResult<Vec<StreamCandidate>> {
    let url = format!(
        "{}/stream/{}/{}.json",
        addon.base_url.trim_end_matches('/'),
        encode_stremio_path_val(effective_type),
        encode_stremio_path_val(video_id)
    );
    let mut resp = http.get(&url).send().await?;
    if matches!(resp.status().as_u16(), 401 | 403 | 429 | 503) {
        // Retry with desktop Firefox User-Agent when Cloudflare challenges default client headers
        resp = http
            .get(&url)
            .header(
                reqwest::header::USER_AGENT,
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            )
            .header(reqwest::header::ACCEPT, "application/json, text/plain, */*")
            .send()
            .await?;
    }
    if !resp.status().is_success() {
        return Ok(Vec::new());
    }
    let root: Value = resp.json().await?;
    let mut out = Vec::new();
    if let Some(arr) = root.get("streams").and_then(|v| v.as_array()) {
        for s in arr {
            if let Some(cand) = parse_stream_candidate(s, &addon.name) {
                out.push(cand);
            }
        }
    }
    Ok(out)
}

pub fn parse_stream_candidate(s: &Value, addon_name: &str) -> Option<StreamCandidate> {
    let raw_url = s
        .get("url")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|u| !u.is_empty());
    let mut info_hash = s
        .get("infoHash")
        .and_then(|v| v.as_str())
        .map(|h| h.trim().to_ascii_lowercase())
        .filter(|h| !h.is_empty());
    let external_url = s
        .get("externalUrl")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|u| !u.is_empty())
        .map(String::from);

    let mut direct_url: Option<String> = None;
    let mut request_headers: HashMap<String, String> = HashMap::new();
    let mut sources = json_string_vec(s.get("sources"));

    if let Some(u) = raw_url {
        if u.starts_with("magnet:") {
            if let Some(extracted) = extract_btih_from_magnet(u) {
                info_hash = Some(extracted);
            }
            for tr in extract_trackers_from_magnet(u) {
                sources.push(format!("tracker:{tr}"));
            }
        } else {
            let (clean_u, pipe_headers) = split_pipe_headers(u);
            direct_url = Some(clean_u);
            request_headers.extend(pipe_headers);
        }
    }

    if direct_url.is_none() && info_hash.is_none() && external_url.is_none() {
        return None;
    }

    let bh = s.get("behaviorHints");
    if let Some(req_hdrs) = bh
        .and_then(|b| b.get("proxyHeaders"))
        .and_then(|p| p.get("request"))
        .and_then(|r| r.as_object())
    {
        for (k, v) in req_hdrs {
            if let Some(vs) = v.as_str() {
                request_headers.insert(k.clone(), vs.to_string());
            }
        }
    }

    let binge_group = bh
        .and_then(|b| b.get("bingeGroup"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let filename = bh
        .and_then(|b| b.get("filename"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    let mut video_size_bytes = bh
        .and_then(|b| b.get("videoSize"))
        .and_then(|v| v.as_u64())
        .unwrap_or(0);

    let name_str = s.get("name").and_then(|v| v.as_str()).unwrap_or(addon_name);
    let raw_title = s.get("title").and_then(|v| v.as_str()).unwrap_or("").trim();
    let raw_desc = s
        .get("description")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    let title_str = match (!raw_title.is_empty(), !raw_desc.is_empty()) {
        (true, true) if raw_title != raw_desc => format!("{raw_title}\n{raw_desc}"),
        (true, _) => raw_title.to_string(),
        (false, true) => raw_desc.to_string(),
        (false, false) => String::new(),
    };

    let combined_text = format!("{name_str}\n{title_str}\n{filename}");
    let seeders = parse_seeders_from_text(&combined_text);
    let resolution_score = parse_resolution_score(&combined_text);
    if video_size_bytes == 0 {
        video_size_bytes = parse_size_bytes_from_text(&combined_text);
    }

    let file_idx = s
        .get("fileIdx")
        .and_then(|v| v.as_u64())
        .map(|n| n as usize);

    let first_line = title_str.lines().next().unwrap_or("").trim();
    let display_title = if first_line.is_empty() {
        if !filename.is_empty() {
            filename.clone()
        } else {
            name_str.replace('\n', " ")
        }
    } else {
        first_line.to_string()
    };

    let mut subtitle_parts: Vec<String> = title_str
        .lines()
        .skip(1)
        .map(str::trim)
        .filter(|l| !l.is_empty())
        .map(String::from)
        .collect();
    if !filename.trim().is_empty() {
        let f_low = filename.trim().to_ascii_lowercase();
        let already_shown = display_title.to_ascii_lowercase().contains(&f_low)
            || subtitle_parts
                .iter()
                .any(|p| p.to_ascii_lowercase().contains(&f_low));
        if !already_shown {
            subtitle_parts.push(format!("File: {}", filename.trim()));
        }
    }
    let subtitle = subtitle_parts.join(" • ");

    Some(StreamCandidate {
        title: display_title,
        subtitle,
        addon_name: addon_name.to_string(),
        direct_url,
        info_hash,
        file_idx,
        sources,
        request_headers,
        external_url,
        binge_group,
        filename,
        video_size_bytes,
        seeders,
        resolution_score,
    })
}

pub fn parse_size_bytes_from_text(text: &str) -> u64 {
    static RE_SIZE: OnceLock<Regex> = OnceLock::new();
    let re = RE_SIZE.get_or_init(|| {
        Regex::new(r"(?i)(?:💾\s*)?(\d+(?:\.\d+)?)\s*(GiB|GB|MiB|MB|KiB|KB)\b").unwrap()
    });
    if let Some(cap) = re.captures(text) {
        if let Ok(num) = cap[1].parse::<f64>() {
            let mult = match cap[2].to_ascii_uppercase().as_str() {
                "GIB" | "GB" => 1024.0 * 1024.0 * 1024.0,
                "MIB" | "MB" => 1024.0 * 1024.0,
                "KIB" | "KB" => 1024.0,
                _ => 1.0,
            };
            return (num * mult) as u64;
        }
    }
    0
}

pub fn dedupe_and_sort_streams(streams: Vec<StreamCandidate>) -> Vec<StreamCandidate> {
    let mut unique: Vec<StreamCandidate> = Vec::new();
    let mut url_seen = HashSet::new();
    let mut hash_index: HashMap<String, usize> = HashMap::new();

    for s in streams {
        if let Some(u) = &s.direct_url {
            if url_seen.insert(format!("url:{u}")) {
                unique.push(s);
            }
        } else if let Some(h) = &s.info_hash {
            let norm_h = h.trim().to_ascii_lowercase();
            // Deduplicate identical infoHash when one source has explicit file_idx
            // and another is a season-pack fallback (`file_idx == None`), keeping the richer entry!
            if let Some(&existing_idx) = hash_index.get(&norm_h) {
                let existing = &unique[existing_idx];
                if existing.file_idx == s.file_idx
                    || existing.file_idx.is_none()
                    || s.file_idx.is_none()
                {
                    if existing.file_idx.is_none() && s.file_idx.is_some() {
                        unique[existing_idx] = s;
                    } else if s.seeders > existing.seeders {
                        unique[existing_idx].seeders = s.seeders;
                    }
                    continue;
                }
                let idx_key = format!("{norm_h}:{}", s.file_idx.unwrap_or(usize::MAX));
                if !hash_index.contains_key(&idx_key) {
                    let pos = unique.len();
                    hash_index.insert(idx_key, pos);
                    unique.push(s);
                }
            } else {
                let pos = unique.len();
                hash_index.insert(norm_h, pos);
                unique.push(s);
            }
        } else if let Some(ext) = &s.external_url {
            if url_seen.insert(format!("ext:{ext}")) {
                unique.push(s);
            }
        }
    }

    // Viability-first sort: dead 0-seed torrents NEVER bury live seeded streams!
    let viability_tier = |c: &StreamCandidate| -> u8 {
        if c.is_direct_http() {
            3
        } else if c.seeders >= 3 {
            3
        } else if c.seeders >= 1 {
            2
        } else if c.external_url.is_some() {
            1
        } else {
            0
        }
    };
    let quality_rank = |c: &StreamCandidate| -> i64 {
        let codec_bonus = if c.has_preferred_modern_codec() { 350 } else { 0 };
        let seed_bonus = (c.seeders.min(200) as i64) * 8;
        (c.resolution_score as i64) + codec_bonus + seed_bonus
    };

    unique.sort_by(|a, b| {
        viability_tier(b)
            .cmp(&viability_tier(a))
            .then_with(|| b.is_direct_http().cmp(&a.is_direct_http()))
            .then_with(|| quality_rank(b).cmp(&quality_rank(a)))
            .then_with(|| b.seeders.cmp(&a.seeders))
    });
    unique
}

pub fn split_pipe_headers(raw_url: &str) -> (String, HashMap<String, String>) {
    let Some((base, hdr_part)) = raw_url.split_once('|') else {
        return (raw_url.to_string(), HashMap::new());
    };
    let mut map = HashMap::new();
    for pair in hdr_part.split('&') {
        if let Some((k, v)) = pair.split_once('=') {
            let key = urlencoding::decode(k)
                .map(|c| c.into_owned())
                .unwrap_or_else(|_| k.to_string());
            let val = urlencoding::decode(v)
                .map(|c| c.into_owned())
                .unwrap_or_else(|_| v.to_string());
            if !key.trim().is_empty() {
                map.insert(key.trim().to_string(), val.trim().to_string());
            }
        }
    }
    (base.trim().to_string(), map)
}

pub fn parse_seeders_from_text(text: &str) -> u32 {
    static RE_EMOJI: OnceLock<Regex> = OnceLock::new();
    static RE_WORD: OnceLock<Regex> = OnceLock::new();
    let re1 = RE_EMOJI.get_or_init(|| Regex::new(r"👤\s*(\d+)").unwrap());
    if let Some(cap) = re1.captures(text) {
        if let Ok(n) = cap[1].parse::<u32>() {
            return n;
        }
    }
    let re2 = RE_WORD.get_or_init(|| Regex::new(r"(?i)(?:seeders?|seeds?|s:)\s*(\d+)").unwrap());
    if let Some(cap) = re2.captures(text) {
        if let Ok(n) = cap[1].parse::<u32>() {
            return n;
        }
    }
    0
}

pub fn parse_resolution_score(text: &str) -> i32 {
    let lower = text.to_ascii_lowercase();
    if lower.contains("2160p") || lower.contains("4k") || lower.contains("uhd") {
        2160
    } else if lower.contains("1440p") || lower.contains("2k") {
        1440
    } else if lower.contains("1080p") || lower.contains("fhd") {
        1080
    } else if lower.contains("720p") || lower.contains("hd") {
        720
    } else if lower.contains("480p") || lower.contains("sd") {
        480
    } else {
        0
    }
}

fn parse_meta_item(m: &Value, default_type: &str, addon_source: &str) -> Option<MetaItem> {
    let id = m.get("id").and_then(|v| v.as_str())?.trim().to_string();
    if id.is_empty() {
        return None;
    }
    let name = m
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or(&id)
        .to_string();
    let media_type = m
        .get("type")
        .and_then(|v| v.as_str())
        .unwrap_or(default_type)
        .to_string();

    let mut videos = Vec::new();
    if let Some(v_arr) = m.get("videos").and_then(|v| v.as_array()) {
        for v in v_arr {
            let Some(v_id) = v.get("id").and_then(|x| x.as_str()) else {
                continue;
            };
            let season = v.get("season").and_then(|x| x.as_i64()).unwrap_or(0) as i32;
            let episode = v
                .get("episode")
                .or_else(|| v.get("number"))
                .and_then(|x| x.as_i64())
                .unwrap_or(0) as i32;
            let ep_title = v
                .get("title")
                .or_else(|| v.get("name"))
                .and_then(|x| x.as_str())
                .unwrap_or("")
                .to_string();
            videos.push(VideoEpisode {
                id: v_id.to_string(),
                title: if ep_title.is_empty() {
                    format!("Episode {episode}")
                } else {
                    ep_title
                },
                season,
                episode,
                overview: v
                    .get("overview")
                    .or_else(|| v.get("description"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string(),
                released: v
                    .get("released")
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string(),
                thumbnail: v
                    .get("thumbnail")
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string(),
            });
        }
    }
    videos.sort_by(|a, b| a.season.cmp(&b.season).then_with(|| a.episode.cmp(&b.episode)));

    let imdb_rating = match m.get("imdbRating") {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };
    let release_info = match m.get("releaseInfo").or_else(|| m.get("year")) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };

    Some(MetaItem {
        id,
        media_type,
        name,
        poster: m
            .get("poster")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        background: m
            .get("background")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        description: m
            .get("description")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        release_info,
        imdb_rating,
        runtime: m
            .get("runtime")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        genres: json_string_vec(m.get("genres")),
        videos,
        addon_source: addon_source.to_string(),
    })
}

fn json_string_vec(val: Option<&Value>) -> Vec<String> {
    val.and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default()
}

fn encode_stremio_path_val(s: &str) -> String {
    urlencoding::encode(s).into_owned()
}

pub fn extract_btih_from_magnet(magnet: &str) -> Option<String> {
    static RE: OnceLock<Regex> = OnceLock::new();
    let re = RE.get_or_init(|| Regex::new(r"(?i)xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})").unwrap());
    re.captures(magnet).map(|c| c[1].to_ascii_lowercase())
}

pub fn extract_trackers_from_magnet(magnet: &str) -> Vec<String> {
    let Ok(parsed) = url::Url::parse(magnet) else {
        return Vec::new();
    };
    parsed
        .query_pairs()
        .filter(|(k, _)| k == "tr")
        .map(|(_, v)| v.into_owned())
        .collect()
}

fn parse_iso_approx_epoch_ms(s: &str) -> Option<u64> {
    // Fast RFC3339 / ISO-8601 `YYYY-MM-DDTHH:MM:SS` parser without heavy chrono formatting needs
    let b = s.trim().as_bytes();
    if b.len() < 19 {
        return None;
    }
    let yr: i64 = std::str::from_utf8(&b[0..4]).ok()?.parse().ok()?;
    let mo: i64 = std::str::from_utf8(&b[5..7]).ok()?.parse().ok()?;
    let dy: i64 = std::str::from_utf8(&b[8..10]).ok()?.parse().ok()?;
    let hr: i64 = std::str::from_utf8(&b[11..13]).ok()?.parse().ok()?;
    let mi: i64 = std::str::from_utf8(&b[14..16]).ok()?.parse().ok()?;
    let se: i64 = std::str::from_utf8(&b[17..19]).ok()?.parse().ok()?;

    // Howard Hinnant civil-from-days formula
    let y = yr - if mo <= 2 { 1 } else { 0 };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m_adj = mo + if mo > 2 { -3 } else { 9 };
    let doy = (153 * m_adj + 2) / 5 + dy - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    let secs = days * 86_400 + hr * 3600 + mi * 60 + se;
    if secs <= 0 {
        None
    } else {
        Some((secs as u64) * 1000)
    }
}

fn epoch_ms_to_iso8601(ms: u64) -> String {
    let total_secs = (ms / 1000) as i64;
    let days = total_secs.div_euclid(86_400);
    let sod = total_secs.rem_euclid(86_400);
    let hr = sod / 3600;
    let mi = (sod % 3600) / 60;
    let se = sod % 60;

    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = mp + if mp < 10 { 3 } else { -9 };
    let yr = y + if m <= 2 { 1 } else { 0 };
    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}.{:03}Z",
        yr,
        m,
        d,
        hr,
        mi,
        se,
        ms % 1000
    )
}

pub(crate) async fn futures_join_all<F, T>(futs: Vec<F>) -> Vec<T>
where
    F: std::future::Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    let mut handles = Vec::with_capacity(futs.len());
    for f in futs {
        handles.push(tokio::spawn(f));
    }
    let mut out = Vec::with_capacity(handles.len());
    for h in handles {
        if let Ok(v) = h.await {
            out.push(v);
        }
    }
    out
}
