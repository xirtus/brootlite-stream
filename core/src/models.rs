use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// ─────────────────────────────────────────────────────────────────────────────
// Stremio & Shared Catalog / Stream / Playback Models
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AddonCatalogDef {
    #[serde(rename = "type")]
    pub media_type: String,
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub supports_search: bool,
    #[serde(default)]
    pub supports_genre: bool,
    #[serde(default)]
    pub supports_skip: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct AddonResourceSpec {
    pub name: String,
    #[serde(default)]
    pub types: Vec<String>,
    #[serde(default)]
    pub id_prefixes: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct InstalledAddon {
    pub id: String,
    pub name: String,
    pub version: String,
    #[serde(default)]
    pub description: String,
    /// Full URL ending in `/manifest.json`
    pub transport_url: String,
    /// Base URL without `/manifest.json`
    pub base_url: String,
    #[serde(default)]
    pub types: Vec<String>,
    #[serde(default)]
    pub resources: Vec<String>,
    #[serde(default)]
    pub resource_specs: Vec<AddonResourceSpec>,
    #[serde(default)]
    pub id_prefixes: Vec<String>,
    #[serde(default)]
    pub catalogs: Vec<AddonCatalogDef>,
}

impl InstalledAddon {
    pub fn supports_resource(&self, resource_name: &str, media_type: &str, item_id: &str) -> bool {
        let matching_specs: Vec<&AddonResourceSpec> = self
            .resource_specs
            .iter()
            .filter(|s| s.name == resource_name)
            .collect();

        if !matching_specs.is_empty() {
            return matching_specs.iter().any(|spec| {
                let effective_types = if spec.types.is_empty() {
                    &self.types
                } else {
                    &spec.types
                };
                let type_ok = effective_types.is_empty()
                    || effective_types.iter().any(|t| t == media_type);
                let effective_prefixes = if spec.id_prefixes.is_empty() {
                    &self.id_prefixes
                } else {
                    &spec.id_prefixes
                };
                let prefix_ok = effective_prefixes.is_empty()
                    || item_id.is_empty()
                    || effective_prefixes.iter().any(|p| {
                        let norm = p.trim_end_matches(':');
                        !norm.is_empty() && (item_id.starts_with(p) || item_id.starts_with(norm))
                    });
                type_ok && prefix_ok
            });
        }

        if !self.resources.iter().any(|r| r == resource_name) {
            return false;
        }
        let type_ok = self.types.is_empty() || self.types.iter().any(|t| t == media_type);
        let prefix_ok = self.id_prefixes.is_empty()
            || item_id.is_empty()
            || self.id_prefixes.iter().any(|p| {
                let norm = p.trim_end_matches(':');
                !norm.is_empty() && (item_id.starts_with(p) || item_id.starts_with(norm))
            });
        type_ok && prefix_ok
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct VideoEpisode {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub season: i32,
    #[serde(default)]
    pub episode: i32,
    #[serde(default)]
    pub overview: String,
    #[serde(default)]
    pub released: String,
    #[serde(default)]
    pub thumbnail: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct MetaItem {
    pub id: String,
    #[serde(rename = "type")]
    pub media_type: String,
    pub name: String,
    #[serde(default)]
    pub poster: String,
    #[serde(default)]
    pub background: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub release_info: String,
    #[serde(default)]
    pub imdb_rating: String,
    #[serde(default)]
    pub runtime: String,
    #[serde(default)]
    pub genres: Vec<String>,
    #[serde(default)]
    pub videos: Vec<VideoEpisode>,
    #[serde(default)]
    pub addon_source: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct StreamCandidate {
    pub title: String,
    #[serde(default)]
    pub subtitle: String,
    #[serde(default)]
    pub addon_name: String,
    #[serde(default)]
    pub direct_url: Option<String>,
    #[serde(default)]
    pub info_hash: Option<String>,
    #[serde(default)]
    pub file_idx: Option<usize>,
    #[serde(default)]
    pub sources: Vec<String>,
    #[serde(default)]
    pub request_headers: HashMap<String, String>,
    #[serde(default)]
    pub external_url: Option<String>,
    #[serde(default)]
    pub binge_group: String,
    #[serde(default)]
    pub filename: String,
    #[serde(default)]
    pub video_size_bytes: u64,
    #[serde(default)]
    pub seeders: u32,
    #[serde(default)]
    pub resolution_score: i32,
}

impl StreamCandidate {
    pub fn is_p2p(&self) -> bool {
        self.direct_url.as_deref().unwrap_or("").is_empty()
            && !self.info_hash.as_deref().unwrap_or("").is_empty()
    }

    pub fn is_direct_http(&self) -> bool {
        !self.direct_url.as_deref().unwrap_or("").is_empty()
    }

    pub fn to_magnet_uri(&self) -> Option<String> {
        let hash = self.info_hash.as_deref()?.trim().to_ascii_lowercase();
        if hash.is_empty() {
            return None;
        }
        let mut trackers: Vec<String> = self
            .sources
            .iter()
            .filter_map(|s| {
                let t = s.strip_prefix("tracker:").unwrap_or(s).trim();
                if t.starts_with("udp://") || t.starts_with("http://") || t.starts_with("https://") {
                    Some(t.to_string())
                } else {
                    None
                }
            })
            .collect();
        for fallback in DEFAULT_PUBLIC_TRACKERS {
            if !trackers.iter().any(|t| t == fallback) {
                trackers.push((*fallback).to_string());
            }
        }
        let dn = urlencoding::encode(&self.title);
        let tr_params: String = trackers
            .iter()
            .map(|t| format!("&tr={}", urlencoding::encode(t)))
            .collect();
        Some(format!("magnet:?xt=urn:btih:{hash}&dn={dn}{tr_params}"))
    }

    /// Scans `title`, `subtitle`, `filename`, and `binge_group` so codec tags like `HEVC/H.265`
    /// or `AV1` hidden inside `behaviorHints.filename` are never missed.
    pub fn codec_tags(&self) -> Vec<&'static str> {
        let lower = format!(
            "{} {} {} {}",
            self.title, self.subtitle, self.filename, self.binge_group
        )
        .to_ascii_lowercase();
        let mut out = Vec::new();
        if lower.contains("av1") || lower.contains("av01") {
            out.push("AV1");
        }
        if lower.contains("hevc")
            || lower.contains("h265")
            || lower.contains("h.265")
            || lower.contains("x265")
        {
            out.push("HEVC/H.265");
        } else if lower.contains("h264")
            || lower.contains("h.264")
            || lower.contains("x264")
            || lower.contains("avc")
        {
            out.push("H.264");
        } else if lower.contains("vp9") {
            out.push("VP9");
        }
        if lower.contains("10bit") || lower.contains("10-bit") || lower.contains("hi10p") {
            out.push("10-bit");
        }
        if lower.contains("dolby vision") || lower.contains(".dv.") || lower.contains("dovi") {
            out.push("DV");
        } else if lower.contains("hdr10+") {
            out.push("HDR10+");
        } else if lower.contains("hdr") {
            out.push("HDR");
        }
        out
    }

    pub fn has_preferred_modern_codec(&self) -> bool {
        let tags = self.codec_tags();
        tags.contains(&"AV1") || tags.contains(&"HEVC/H.265")
    }

    /// Extracts upstream indexer name (`⚙️ ArchiveOrg`, `OpenTracker`, etc.) from `subtitle`/`title`.
    pub fn extracted_indexer(&self) -> Option<String> {
        let combined = format!("{}\n{}", self.title, self.subtitle);
        for marker in ["⚙️", "⚙"] {
            if let Some(idx) = combined.find(marker) {
                let after = combined[idx + marker.len()..].trim_start();
                let end = after
                    .find(|c: char| c == '\n' || c == '•' || c == '|' || c == '👤' || c == '💾')
                    .unwrap_or(after.len());
                let name = after[..end].trim();
                if !name.is_empty() && !name.eq_ignore_ascii_case(&self.addon_name) {
                    return Some(name.to_string());
                }
            }
        }
        None
    }

    /// Returns human-readable file size from `video_size_bytes` OR parsed from `💾 229.7 MB` in text.
    pub fn extracted_size_label(&self) -> Option<String> {
        if self.video_size_bytes > 0 {
            let gb = (self.video_size_bytes as f64) / (1024.0 * 1024.0 * 1024.0);
            if gb >= 1.0 {
                return Some(format!("{gb:.2} GB"));
            }
            let mb = (self.video_size_bytes as f64) / (1024.0 * 1024.0);
            return Some(format!("{mb:.1} MB"));
        }
        let combined = format!("{} {}", self.title, self.subtitle);
        if let Some(idx) = combined.find("💾") {
            let after = combined[idx + "💾".len()..].trim_start();
            let tokens: Vec<&str> = after.split_whitespace().take(2).collect();
            if tokens.len() == 2 {
                let unit = tokens[1].trim_matches(|c: char| !c.is_ascii_alphabetic()).to_ascii_uppercase();
                if matches!(unit.as_str(), "GB" | "GIB" | "MB" | "MIB" | "KB" | "KIB") {
                    return Some(format!("{} {unit}", tokens[0]));
                }
            }
        }
        None
    }

    /// One line that is never truncated: seeds, size, resolution, codecs and the upstream indexer,
    /// e.g. `▲ 207 seeds · 💾 1.4 GB · 1080p · HEVC/H.265 · 10-bit · ⚙ ArchiveOrg`.
    pub fn telemetry_line(&self) -> String {
        let mut parts: Vec<String> = Vec::new();
        if self.is_p2p() {
            parts.push(match self.seeders {
                0 => "▽ 0 seeds".to_string(),
                1 => "▲ 1 seed".to_string(),
                n => format!("▲ {n} seeds"),
            });
        } else if self.is_direct_http() {
            parts.push("⚡ direct".to_string());
        }
        if let Some(sz) = self.extracted_size_label() {
            parts.push(format!("💾 {sz}"));
        }
        let res = match self.resolution_score {
            2160.. => "4K",
            1440..=2159 => "1440p",
            1080..=1439 => "1080p",
            720..=1079 => "720p",
            480..=719 => "480p",
            _ => "",
        };
        if !res.is_empty() {
            parts.push(res.to_string());
        }
        parts.extend(self.codec_tags().into_iter().map(String::from));
        if let Some(idx) = self.extracted_indexer() {
            parts.push(format!("⚙ {idx}"));
        }
        parts.join(" · ")
    }

    /// Returns `Some(filename)` when `behaviorHints.filename` carries extra metadata not already in `title` or `subtitle`.
    pub fn untruncated_filename_note(&self) -> Option<&str> {
        let f = self.filename.trim();
        if f.is_empty() {
            return None;
        }
        let t_low = self.title.to_ascii_lowercase();
        let s_low = self.subtitle.to_ascii_lowercase();
        let f_low = f.to_ascii_lowercase();
        if t_low.contains(&f_low) || s_low.contains(&f_low) {
            None
        } else {
            Some(f)
        }
    }
}

pub const DEFAULT_PUBLIC_TRACKERS: &[&str] = &[
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://open.demonii.com:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://p4p.arenabg.com:1337/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker.moeking.me:6969/announce",
    "https://tracker.tamersunion.org:443/announce",
];

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ExternalSubtitle {
    pub id: String,
    pub url: String,
    pub lang: String,
    pub addon_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct WatchHistoryEntry {
    pub item_id: String,
    #[serde(rename = "type")]
    pub media_type: String,
    pub title: String,
    #[serde(default)]
    pub poster: String,
    #[serde(default)]
    pub video_id: String,
    #[serde(default)]
    pub episode_title: String,
    #[serde(default)]
    pub position_ms: u64,
    #[serde(default)]
    pub duration_ms: u64,
    #[serde(default)]
    pub updated_at_ms: u64,
    /// Optional binge-matching context persisted from the last played stream
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub last_stream_context: Option<StreamSelectionContext>,
}

impl WatchHistoryEntry {
    pub fn progress_ratio(&self) -> f32 {
        if self.duration_ms > 0 {
            ((self.position_ms as f64) / (self.duration_ms as f64)).clamp(0.0, 1.0) as f32
        } else {
            0.0
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FavoriteEntry {
    pub item_id: String,
    #[serde(rename = "type")]
    pub media_type: String,
    pub title: String,
    #[serde(default)]
    pub poster: String,
    #[serde(default)]
    pub imdb_rating: String,
    #[serde(default)]
    pub release_info: String,
    #[serde(default)]
    pub added_at_ms: u64,
}

// ─────────────────────────────────────────────────────────────────────────────
// Swarm, Torrent, & Discover Preferences
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SwarmFilter {
    #[default]
    SafeOnly,
    All,
    AdultOnly,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiscoverSortMode {
    #[default]
    Popular,
    TopRated,
    YearDesc,
    TitleAsc,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TorrentEngineConfig {
    pub max_cache_mb: u32,
    pub window_ahead_mb: u32,
    pub prebuffer_mb: u32,
    pub max_connections: u32,
}

impl Default for TorrentEngineConfig {
    fn default() -> Self {
        Self {
            max_cache_mb: 512,
            window_ahead_mb: 48,
            prebuffer_mb: 12,
            max_connections: 120,
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Binge & Stream Similarity Context
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StreamSelectionContext {
    #[serde(default)]
    pub binge_group: String,
    #[serde(default)]
    pub addon_name: String,
    #[serde(default)]
    pub title: String,
    #[serde(default)]
    pub filename: String,
    #[serde(default)]
    pub was_direct_http: bool,
    #[serde(default)]
    pub resolution_score: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RankedStreamCandidate {
    pub candidate: StreamCandidate,
    pub similarity_score: f64,
    pub matched_reasons: Vec<String>,
}

// ─────────────────────────────────────────────────────────────────────────────
// Jellyfin Multi-Server Models
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct JellyfinServerProfile {
    pub server_id: String,
    pub server_name: String,
    pub lan_url: String,
    #[serde(default)]
    pub remote_url: String,
    pub user_id: String,
    #[serde(default)]
    pub username: String,
    /// Unsealed in memory when loaded from ConfigStore; sealed on disk (`blseal2:...`).
    #[serde(default)]
    pub access_token: String,
    /// Optional Multipass zero-password bootstrap token (sealed on disk).
    #[serde(default)]
    pub bootstrap_token: String,
    #[serde(default)]
    pub last_verified_ms: u64,
    #[serde(default)]
    pub updated_at_ms: u64,
}

impl JellyfinServerProfile {
    pub fn primary_url(&self) -> &str {
        if !self.lan_url.trim().is_empty() {
            self.lan_url.trim_end_matches('/')
        } else {
            self.remote_url.trim_end_matches('/')
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredJellyfinServer {
    pub server_id: String,
    pub name: String,
    pub address: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct JellyfinQuickConnectState {
    pub secret: String,
    pub code: String,
    pub authenticated: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct JellyfinView {
    pub id: String,
    pub name: String,
    /// `"movies"`, `"tvshows"`, `"boxsets"`, `"music"`, `"homevideos"`, etc.
    pub collection_type: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct JellyfinPlaybackSession {
    pub play_url: String,
    pub request_headers: HashMap<String, String>,
    /// `"DirectPlay"`, `"DirectStream"`, or `"Transcode (HLS)"`
    pub play_method: String,
    pub play_session_id: String,
    pub media_source_id: String,
    pub external_subtitles: Vec<ExternalSubtitle>,
    pub runtime_ms: u64,
    pub resume_position_ms: u64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SkipSegmentKind {
    Intro,
    Outro,
    Sponsor,
    SelfPromo,
    Interaction,
    Preview,
    Recap,
    Filler,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct SkipSegment {
    pub start_ms: u64,
    pub end_ms: u64,
    pub kind: SkipSegmentKind,
    pub label: String,
}

// ─────────────────────────────────────────────────────────────────────────────
// YouTube & Creators (Twitch, Odysee, Rumble) Models
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct YouTubeAccountProfile {
    pub account_id: String,
    pub account_name: String,
    #[serde(default)]
    pub channel_handle: String,
    #[serde(default)]
    pub avatar_url: String,
    #[serde(default)]
    pub page_id: String,
    #[serde(default)]
    pub refresh_token: String,
    #[serde(default)]
    pub access_token: String,
    #[serde(default)]
    pub expires_at_ms: u64,
    #[serde(default)]
    pub oauth_client_id: String,
    #[serde(default)]
    pub oauth_client_secret: String,
    #[serde(default)]
    pub updated_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DeviceCodeSession {
    pub device_code: String,
    pub user_code: String,
    pub verification_url: String,
    pub expires_in_sec: u64,
    pub interval_sec: u64,
    #[serde(default)]
    pub client_id: String,
    #[serde(default)]
    pub client_secret: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreatorVideoItem {
    /// `"youtube"`, `"twitch"`, `"odysee"`, `"rumble"`
    pub platform: String,
    pub video_id: String,
    pub title: String,
    #[serde(default)]
    pub channel_name: String,
    #[serde(default)]
    pub channel_id: String,
    #[serde(default)]
    pub channel_avatar: String,
    #[serde(default)]
    pub thumbnail_url: String,
    #[serde(default)]
    pub duration_sec: i64,
    #[serde(default)]
    pub duration_text: String,
    #[serde(default)]
    pub view_count_text: String,
    #[serde(default)]
    pub published_text: String,
    #[serde(default)]
    pub is_live: bool,
    #[serde(default)]
    pub is_short: bool,
    #[serde(default)]
    pub resume_progress_pct: i32,
    #[serde(default)]
    pub category_name: String,
    #[serde(default)]
    pub direct_url_hint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreatorChannel {
    pub platform: String,
    pub channel_id: String,
    pub name: String,
    #[serde(default)]
    pub handle: String,
    #[serde(default)]
    pub avatar_url: String,
    #[serde(default)]
    pub banner_url: String,
    #[serde(default)]
    pub subscriber_text: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub is_subscribed: bool,
    #[serde(default)]
    pub live_stream: Option<CreatorVideoItem>,
    #[serde(default)]
    pub videos: Vec<CreatorVideoItem>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreatorFeedPage {
    pub items: Vec<CreatorVideoItem>,
    #[serde(default)]
    pub continuation_token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct CreatorCategory {
    pub platform: String,
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub box_art_url: String,
    #[serde(default)]
    pub viewers_text: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CreatorFollowEntry {
    pub platform: String,
    pub channel_id: String,
    pub name: String,
    #[serde(default)]
    pub handle: String,
    #[serde(default)]
    pub avatar_url: String,
    #[serde(default)]
    pub added_at_ms: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub removed_at_ms: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TwitchAuthProfile {
    pub user_id: String,
    pub login: String,
    pub display_name: String,
    #[serde(default)]
    pub access_token: String,
    #[serde(default)]
    pub refresh_token: String,
    #[serde(default)]
    pub updated_at_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedMediaStream {
    pub play_url: String,
    /// `"DASH"`, `"HLS"`, or `"PROGRESSIVE"`
    pub protocol: String,
    pub title: String,
    pub author: String,
    pub duration_sec: u64,
    pub is_live: bool,
    pub quality_label: String,
    #[serde(default)]
    pub request_headers: HashMap<String, String>,
    #[serde(default)]
    pub subtitles: Vec<ExternalSubtitle>,
    #[serde(default)]
    pub skip_segments: Vec<SkipSegment>,
    #[serde(default)]
    pub playback_tracking_url: String,
    #[serde(default)]
    pub watchtime_tracking_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ReturnYouTubeDislikeVotes {
    pub likes: u64,
    pub dislikes: u64,
    pub view_count: u64,
    pub rating: f64,
}

// ─────────────────────────────────────────────────────────────────────────────
// Multipass Profile & Kodi Models
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct MultipassProfile {
    #[serde(default)]
    pub endpoint_url: String,
    /// Unsealed in memory (`mpd_...`); sealed on disk (`blseal2:...`).
    #[serde(default)]
    pub pair_token: String,
    #[serde(default)]
    pub vault_unlocked: bool,
    #[serde(default)]
    pub device_id: String,
    #[serde(default)]
    pub device_name: String,
    #[serde(default)]
    pub handle: String,
    #[serde(default)]
    pub last_synced_ms: u64,
    #[serde(default)]
    pub last_synced_revision: u64,
}

impl MultipassProfile {
    pub fn is_paired(&self) -> bool {
        !self.endpoint_url.trim().is_empty() && !self.pair_token.trim().is_empty()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct KodiAddonInfo {
    pub id: String,
    pub name: String,
    pub version: String,
    pub summary: String,
    pub icon_path: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct KodiDirectoryItem {
    pub label: String,
    pub url: String,
    #[serde(default)]
    pub is_folder: bool,
    #[serde(default)]
    pub is_playable: bool,
    #[serde(default)]
    pub thumb: String,
    #[serde(default)]
    pub fanart: String,
    #[serde(default)]
    pub plot: String,
    #[serde(default)]
    pub year: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct KodiBridgeResponse {
    pub ok: bool,
    #[serde(default)]
    pub error: String,
    #[serde(default)]
    pub resolved_url: String,
    #[serde(default)]
    pub items: Vec<KodiDirectoryItem>,
}

pub fn now_epoch_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
