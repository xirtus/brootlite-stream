use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{
    now_epoch_ms, DiscoveredJellyfinServer, ExternalSubtitle, JellyfinPlaybackSession,
    JellyfinQuickConnectState, JellyfinServerProfile, JellyfinView, MetaItem, SkipSegment,
    SkipSegmentKind, VideoEpisode,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, RwLock};
use std::time::Duration;
use tokio::net::UdpSocket;

const TICKS_PER_MS: u64 = 10_000;
const ROUTE_TTL_MS: u64 = 5 * 60 * 1000;
const LAN_PROBE_TIMEOUT_MS: u64 = 1_200;

/// Composite ID helper (`jf:{serverId}:{itemId}`) ensuring strict multi-server isolation.
pub struct JellyfinIds;

impl JellyfinIds {
    pub fn make_id(server_id: &str, item_id: &str) -> String {
        format!("jf:{server_id}:{item_id}")
    }

    pub fn is_jellyfin_id(raw: &str) -> bool {
        raw.starts_with("jf:")
    }

    pub fn parse_id(raw: &str) -> Option<(String, String)> {
        let rest = raw.strip_prefix("jf:")?;
        let (srv, item) = rest.split_once(':')?;
        if srv.is_empty() || item.is_empty() {
            return None;
        }
        Some((srv.to_string(), item.to_string()))
    }
}

pub struct JellyfinClient {
    http: reqwest::Client,
    lan_probe_http: reqwest::Client,
    config: Arc<ConfigStore>,
    route_cache: RwLock<HashMap<String, (String, u64)>>,
}

impl JellyfinClient {
    pub fn new(http: reqwest::Client, config: Arc<ConfigStore>) -> Self {
        let lan_probe_http = reqwest::Client::builder()
            .timeout(Duration::from_millis(LAN_PROBE_TIMEOUT_MS))
            .build()
            .unwrap_or_else(|_| http.clone());
        Self {
            http,
            lan_probe_http,
            config,
            route_cache: RwLock::new(HashMap::new()),
        }
    }

    fn auth_header(&self, token: Option<&str>) -> String {
        let dev_id = self.config.jellyfin_device_id();
        let ver = env!("CARGO_PKG_VERSION");
        match token.filter(|t| !t.is_empty()) {
            Some(tok) => format!(
                "MediaBrowser Client=\"BrootLite\", Device=\"BrootLite Desktop\", DeviceId=\"{dev_id}\", Version=\"{ver}\", Token=\"{tok}\""
            ),
            None => format!(
                "MediaBrowser Client=\"BrootLite\", Device=\"BrootLite Desktop\", DeviceId=\"{dev_id}\", Version=\"{ver}\""
            ),
        }
    }

    // ── LAN Discovery & 1.2s LAN-First / Remote-Fallback Endpoint Router ──────

    /// Broadcasts `"Who is JellyfinServer?"` over UDP port `7359` and collects LAN servers.
    pub async fn discover_lan_servers(
        &self,
        timeout_ms: u64,
    ) -> CoreResult<Vec<DiscoveredJellyfinServer>> {
        let sock = UdpSocket::bind("0.0.0.0:0").await?;
        sock.set_broadcast(true)?;
        let _ = sock
            .send_to(b"Who is JellyfinServer?", "255.255.255.255:7359")
            .await;

        let deadline = tokio::time::Instant::now() + Duration::from_millis(timeout_ms.clamp(250, 5000));
        let mut seen = HashSet::new();
        let mut found = Vec::new();
        let mut buf = [0u8; 2048];

        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                break;
            }
            match tokio::time::timeout(remaining, sock.recv_from(&mut buf)).await {
                Ok(Ok((n, _addr))) => {
                    if let Ok(v) = serde_json::from_slice::<Value>(&buf[..n]) {
                        let id = v
                            .get("Id")
                            .and_then(|x| x.as_str())
                            .unwrap_or("")
                            .to_string();
                        let name = v
                            .get("Name")
                            .and_then(|x| x.as_str())
                            .unwrap_or("Jellyfin Server")
                            .to_string();
                        let address = v
                            .get("Address")
                            .and_then(|x| x.as_str())
                            .unwrap_or("")
                            .trim_end_matches('/')
                            .to_string();
                        if !id.is_empty() && !address.is_empty() && seen.insert(id.clone()) {
                            found.push(DiscoveredJellyfinServer {
                                server_id: id,
                                name,
                                address,
                            });
                        }
                    }
                }
                _ => break,
            }
        }
        Ok(found)
    }

    /// Resolves the fastest healthy endpoint for `profile` (LAN first within `1.2s` verifying
    /// `/System/Info/Public` `Id == profile.server_id`, seamlessly falling back to `remote_url`).
    pub async fn resolve_active_base_url(
        &self,
        profile: &JellyfinServerProfile,
    ) -> CoreResult<String> {
        let now = now_epoch_ms();
        if let Some((cached, ts)) = self
            .route_cache
            .read()
            .unwrap()
            .get(&profile.server_id)
            .cloned()
        {
            if now.saturating_sub(ts) < ROUTE_TTL_MS {
                return Ok(cached);
            }
        }

        let lan = profile.lan_url.trim().trim_end_matches('/');
        let rem = profile.remote_url.trim().trim_end_matches('/');

        if !lan.is_empty() && rem.is_empty() {
            return Ok(lan.to_string());
        }
        if lan.is_empty() && !rem.is_empty() {
            return Ok(rem.to_string());
        }
        if lan.is_empty() && rem.is_empty() {
            return Err(CoreError::InvalidInput(
                "Jellyfin server profile has neither LAN nor Remote URL".into(),
            ));
        }

        if self
            .verify_server_identity(&self.lan_probe_http, lan, &profile.server_id)
            .await
        {
            self.route_cache
                .write()
                .unwrap()
                .insert(profile.server_id.clone(), (lan.to_string(), now));
            return Ok(lan.to_string());
        }

        if self
            .verify_server_identity(&self.http, rem, &profile.server_id)
            .await
        {
            self.route_cache
                .write()
                .unwrap()
                .insert(profile.server_id.clone(), (rem.to_string(), now));
            return Ok(rem.to_string());
        }

        Ok(lan.to_string())
    }

    async fn verify_server_identity(
        &self,
        client: &reqwest::Client,
        base_url: &str,
        expected_id: &str,
    ) -> bool {
        let url = format!("{}/System/Info/Public", base_url.trim_end_matches('/'));
        let Ok(resp) = client.get(&url).send().await else {
            return false;
        };
        if !resp.status().is_success() {
            return false;
        }
        let Ok(val) = resp.json::<Value>().await else {
            return false;
        };
        let actual_id = val.get("Id").and_then(|v| v.as_str()).unwrap_or("");
        expected_id.is_empty() || actual_id.eq_ignore_ascii_case(expected_id)
    }

    // ── QuickConnect, Username Auth, & Multipass Bootstrap Minting ────────────

    pub async fn fetch_public_server_info(&self, base_url: &str) -> CoreResult<(String, String)> {
        let clean = normalize_jellyfin_url(base_url)?;
        let resp = self
            .http
            .get(format!("{clean}/System/Info/Public"))
            .header("Authorization", self.auth_header(None))
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "Jellyfin /System/Info/Public HTTP {}",
                resp.status()
            )));
        }
        let val: Value = resp.json().await?;
        let id = val
            .get("Id")
            .and_then(|v| v.as_str())
            .ok_or_else(|| CoreError::Protocol("Jellyfin public info missing Id".into()))?
            .to_string();
        let name = val
            .get("ServerName")
            .and_then(|v| v.as_str())
            .unwrap_or("Jellyfin")
            .to_string();
        Ok((id, name))
    }

    pub async fn check_quick_connect_enabled(&self, base_url: &str) -> bool {
        let Ok(clean) = normalize_jellyfin_url(base_url) else {
            return false;
        };
        let Ok(resp) = self
            .http
            .get(format!("{clean}/QuickConnect/Enabled"))
            .header("Authorization", self.auth_header(None))
            .send()
            .await
        else {
            return false;
        };
        resp.json::<bool>().await.unwrap_or(false)
    }

    pub async fn initiate_quick_connect(
        &self,
        base_url: &str,
    ) -> CoreResult<JellyfinQuickConnectState> {
        let clean = normalize_jellyfin_url(base_url)?;
        let resp = self
            .http
            .post(format!("{clean}/QuickConnect/Initiate"))
            .header("Authorization", self.auth_header(None))
            .header("Content-Length", "0")
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "QuickConnect/Initiate failed: {}",
                resp.status()
            )));
        }
        let val: Value = resp.json().await?;
        Ok(JellyfinQuickConnectState {
            secret: val
                .get("Secret")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            code: val
                .get("Code")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            authenticated: val
                .get("Authenticated")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
        })
    }

    /// Polls QuickConnect and exchanges `secret` for an isolated device token once approved.
    pub async fn poll_quick_connect(
        &self,
        lan_url: &str,
        remote_url: Option<&str>,
        secret: &str,
    ) -> CoreResult<Option<JellyfinServerProfile>> {
        let clean = normalize_jellyfin_url(lan_url)?;
        let connect_resp = self
            .http
            .get(format!(
                "{clean}/QuickConnect/Connect?secret={}",
                urlencoding::encode(secret)
            ))
            .header("Authorization", self.auth_header(None))
            .send()
            .await?;
        if !connect_resp.status().is_success() {
            return Ok(None);
        }
        let state: Value = connect_resp.json().await?;
        if !state
            .get("Authenticated")
            .and_then(|v| v.as_bool())
            .unwrap_or(false)
        {
            return Ok(None);
        }

        let auth_resp = self
            .http
            .post(format!("{clean}/Users/AuthenticateWithQuickConnect"))
            .header("Authorization", self.auth_header(None))
            .json(&json!({ "Secret": secret }))
            .send()
            .await?;
        if !auth_resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "AuthenticateWithQuickConnect failed: {}",
                auth_resp.status()
            )));
        }
        let auth_val: Value = auth_resp.json().await?;
        let profile = self
            .profile_from_auth_result(&clean, remote_url.unwrap_or(""), &auth_val)
            .await?;
        self.config.upsert_jellyfin_server(profile.clone())?;
        Ok(Some(profile))
    }

    pub async fn authenticate_by_name(
        &self,
        lan_url: &str,
        remote_url: Option<&str>,
        username: &str,
        password: &str,
    ) -> CoreResult<JellyfinServerProfile> {
        let clean = normalize_jellyfin_url(lan_url)?;
        let resp = self
            .http
            .post(format!("{clean}/Users/AuthenticateByName"))
            .header("Authorization", self.auth_header(None))
            .json(&json!({
                "Username": username,
                "Pw": password
            }))
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "Jellyfin login failed ({})",
                resp.status()
            )));
        }
        let auth_val: Value = resp.json().await?;
        let profile = self
            .profile_from_auth_result(&clean, remote_url.unwrap_or(""), &auth_val)
            .await?;
        self.config.upsert_jellyfin_server(profile.clone())?;
        Ok(profile)
    }

    /// Zero-password Multipass per-device token minting: initiates QuickConnect for this device's
    /// `DeviceId`, authorizes it immediately using the vault's `bootstrapToken`, and exchanges it!
    pub async fn mint_device_token_via_bootstrap(
        &self,
        base_url: &str,
        bootstrap_token: &str,
    ) -> CoreResult<JellyfinServerProfile> {
        let clean = normalize_jellyfin_url(base_url)?;
        let qc = self.initiate_quick_connect(&clean).await?;
        if qc.code.is_empty() || qc.secret.is_empty() {
            return Err(CoreError::Auth("QuickConnect returned empty code/secret".into()));
        }

        let authz_resp = self
            .http
            .post(format!(
                "{clean}/QuickConnect/Authorize?code={}",
                urlencoding::encode(&qc.code)
            ))
            .header("X-Emby-Token", bootstrap_token)
            .header("Content-Length", "0")
            .send()
            .await?;
        if !authz_resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "QuickConnect/Authorize via bootstrapToken failed: {}",
                authz_resp.status()
            )));
        }

        self.poll_quick_connect(&clean, None, &qc.secret)
            .await?
            .ok_or_else(|| CoreError::Auth("QuickConnect bootstrap exchange did not complete".into()))
    }

    async fn profile_from_auth_result(
        &self,
        lan_url: &str,
        remote_url: &str,
        auth_val: &Value,
    ) -> CoreResult<JellyfinServerProfile> {
        let access_token = auth_val
            .get("AccessToken")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| CoreError::Auth("Jellyfin auth missing AccessToken".into()))?
            .to_string();
        let user_obj = auth_val.get("User");
        let user_id = user_obj
            .and_then(|u| u.get("Id"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let username = user_obj
            .and_then(|u| u.get("Name"))
            .and_then(|v| v.as_str())
            .unwrap_or("User")
            .to_string();
        let mut server_id = auth_val
            .get("ServerId")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let (pub_id, server_name) = self
            .fetch_public_server_info(lan_url)
            .await
            .unwrap_or_else(|_| (server_id.clone(), "Jellyfin".to_string()));
        if server_id.is_empty() {
            server_id = pub_id;
        }
        let now = now_epoch_ms();

        Ok(JellyfinServerProfile {
            server_id,
            server_name,
            lan_url: lan_url.to_string(),
            remote_url: remote_url.trim().trim_end_matches('/').to_string(),
            user_id,
            username,
            access_token: access_token.clone(),
            bootstrap_token: access_token,
            last_verified_ms: now,
            updated_at_ms: now,
        })
    }

    // ── Views, Resume, NextUp, Latest, Items, Genres, Years, Similar, Search ──

    pub async fn get_views(&self, profile: &JellyfinServerProfile) -> CoreResult<Vec<JellyfinView>> {
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!("{base}/Users/{}/Views", profile.user_id);
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        let mut views = Vec::new();
        if let Some(arr) = root.get("Items").and_then(|v| v.as_array()) {
            for item in arr {
                let id = item.get("Id").and_then(|v| v.as_str()).unwrap_or("");
                if id.is_empty() {
                    continue;
                }
                views.push(JellyfinView {
                    id: id.to_string(),
                    name: item
                        .get("Name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Library")
                        .to_string(),
                    collection_type: item
                        .get("CollectionType")
                        .and_then(|v| v.as_str())
                        .unwrap_or("movies")
                        .to_ascii_lowercase(),
                });
            }
        }
        Ok(views)
    }

    pub async fn get_resume_items(
        &self,
        profile: &JellyfinServerProfile,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Users/{}/Items/Resume?Limit={limit}&Recursive=true&Fields=Overview,Genres,PrimaryImageAspectRatio,UserData&MediaTypes=Video",
            profile.user_id
        );
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(self.parse_items_array(&root, &base, profile))
    }

    pub async fn get_next_up(
        &self,
        profile: &JellyfinServerProfile,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Shows/NextUp?UserId={}&Limit={limit}&Fields=Overview,Genres,PrimaryImageAspectRatio,UserData",
            profile.user_id
        );
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(self.parse_items_array(&root, &base, profile))
    }

    pub async fn get_latest_in_view(
        &self,
        profile: &JellyfinServerProfile,
        parent_id: &str,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Users/{}/Items/Latest?ParentId={}&Limit={limit}&Fields=Overview,Genres,CommunityRating",
            profile.user_id,
            urlencoding::encode(parent_id)
        );
        let val = self.authed_get_json(&url, &profile.access_token).await?;
        let mut out = Vec::new();
        if let Some(arr) = val.as_array() {
            for item in arr {
                if let Some(m) = parse_jellyfin_meta_item(item, &base, profile) {
                    out.push(m);
                }
            }
        }
        Ok(out)
    }

    #[allow(clippy::too_many_arguments)]
    pub async fn get_library_items(
        &self,
        profile: &JellyfinServerProfile,
        parent_id: Option<&str>,
        include_item_types: Option<&str>,
        genre: Option<&str>,
        year: Option<&str>,
        sort_by: &str,
        sort_order: &str,
        start_index: u32,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let base = self.resolve_active_base_url(profile).await?;
        let mut url = format!(
            "{base}/Users/{}/Items?Recursive=true&StartIndex={start_index}&Limit={limit}&SortBy={}&SortOrder={}&Fields=Overview,Genres,CommunityRating,ProductionYear",
            profile.user_id,
            urlencoding::encode(sort_by),
            urlencoding::encode(sort_order)
        );
        if let Some(pid) = parent_id.filter(|s| !s.is_empty()) {
            url.push_str(&format!("&ParentId={}", urlencoding::encode(pid)));
        }
        if let Some(it) = include_item_types.filter(|s| !s.is_empty()) {
            url.push_str(&format!("&IncludeItemTypes={}", urlencoding::encode(it)));
        }
        if let Some(g) = genre.filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("All")) {
            url.push_str(&format!("&Genres={}", urlencoding::encode(g)));
        }
        if let Some(y) = year.filter(|s| !s.is_empty() && !s.eq_ignore_ascii_case("All")) {
            url.push_str(&format!("&Years={}", urlencoding::encode(y)));
        }
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(self.parse_items_array(&root, &base, profile))
    }

    pub async fn get_genres(
        &self,
        profile: &JellyfinServerProfile,
        parent_id: Option<&str>,
    ) -> CoreResult<Vec<String>> {
        let base = self.resolve_active_base_url(profile).await?;
        let mut url = format!("{base}/Genres?UserId={}&SortBy=SortName", profile.user_id);
        if let Some(pid) = parent_id.filter(|s| !s.is_empty()) {
            url.push_str(&format!("&ParentId={}", urlencoding::encode(pid)));
        }
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(root
            .get("Items")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|x| x.get("Name").and_then(|n| n.as_str()).map(String::from))
                    .collect()
            })
            .unwrap_or_default())
    }

    pub async fn get_years(
        &self,
        profile: &JellyfinServerProfile,
        parent_id: Option<&str>,
    ) -> CoreResult<Vec<String>> {
        let base = self.resolve_active_base_url(profile).await?;
        let mut url = format!(
            "{base}/Years?UserId={}&SortBy=SortName&SortOrder=Descending",
            profile.user_id
        );
        if let Some(pid) = parent_id.filter(|s| !s.is_empty()) {
            url.push_str(&format!("&ParentId={}", urlencoding::encode(pid)));
        }
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(root
            .get("Items")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|x| x.get("Name").and_then(|n| n.as_str()).map(String::from))
                    .collect()
            })
            .unwrap_or_default())
    }

    pub async fn get_item_detail(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
    ) -> CoreResult<MetaItem> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Users/{}/Items/{}",
            profile.user_id,
            urlencoding::encode(item_id)
        );
        let val = self.authed_get_json(&url, &profile.access_token).await?;
        let mut meta = parse_jellyfin_meta_item(&val, &base, profile)
            .ok_or_else(|| CoreError::NotFound(format!("Jellyfin item {item_id} not found")))?;

        if meta.media_type == "series" {
            meta.videos = self.get_episodes(profile, item_id, None).await?;
        }
        Ok(meta)
    }

    pub async fn get_episodes(
        &self,
        profile: &JellyfinServerProfile,
        series_id: &str,
        season_id: Option<&str>,
    ) -> CoreResult<Vec<VideoEpisode>> {
        let clean_series = strip_composite_prefix(series_id);
        let base = self.resolve_active_base_url(profile).await?;
        let mut url = format!(
            "{base}/Shows/{}/Episodes?UserId={}&Fields=Overview",
            urlencoding::encode(clean_series),
            profile.user_id
        );
        if let Some(sid) = season_id.filter(|s| !s.is_empty()) {
            url.push_str(&format!("&SeasonId={}", urlencoding::encode(sid)));
        }
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        let mut out = Vec::new();
        if let Some(arr) = root.get("Items").and_then(|v| v.as_array()) {
            for ep in arr {
                let Some(ep_id) = ep.get("Id").and_then(|v| v.as_str()) else {
                    continue;
                };
                out.push(VideoEpisode {
                    id: JellyfinIds::make_id(&profile.server_id, ep_id),
                    title: ep
                        .get("Name")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Episode")
                        .to_string(),
                    season: ep
                        .get("ParentIndexNumber")
                        .and_then(|v| v.as_i64())
                        .unwrap_or(1) as i32,
                    episode: ep.get("IndexNumber").and_then(|v| v.as_i64()).unwrap_or(1) as i32,
                    overview: ep
                        .get("Overview")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    released: ep
                        .get("PremiereDate")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    thumbnail: format!("{base}/Items/{ep_id}/Images/Primary?maxWidth=480"),
                });
            }
        }
        Ok(out)
    }

    pub async fn get_similar_items(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Items/{}/Similar?UserId={}&Limit={limit}&Fields=Overview,Genres,CommunityRating",
            urlencoding::encode(item_id),
            profile.user_id
        );
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(self.parse_items_array(&root, &base, profile))
    }

    pub async fn search_server(
        &self,
        profile: &JellyfinServerProfile,
        query: &str,
        limit: u32,
    ) -> CoreResult<Vec<MetaItem>> {
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Users/{}/Items?Recursive=true&SearchTerm={}&IncludeItemTypes=Movie,Series&Limit={limit}&Fields=Overview,Genres,CommunityRating",
            profile.user_id,
            urlencoding::encode(query.trim())
        );
        let root = self.authed_get_json(&url, &profile.access_token).await?;
        Ok(self.parse_items_array(&root, &base, profile))
    }

    // ── PlaybackInfo (DirectPlay / HLS), Skip Segments, & Session Reporting ──

    pub async fn resolve_playback_session(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
    ) -> CoreResult<JellyfinPlaybackSession> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let url = format!(
            "{base}/Items/{}/PlaybackInfo?UserId={}",
            urlencoding::encode(item_id),
            profile.user_id
        );

        // Desktop libmpv plays virtually every container & codec natively via DirectPlay!
        let device_profile = json!({
            "DeviceProfile": {
                "Name": "BrootLite Desktop DirectPlay",
                "MaxStreamingBitrate": 160_000_000,
                "DirectPlayProfiles": [
                    { "Type": "Video", "Container": "mkv,mp4,m4v,webm,mov,avi,ts,m2ts" },
                    { "Type": "Audio" }
                ],
                "TranscodingProfiles": [
                    {
                        "Type": "Video",
                        "Container": "ts",
                        "Protocol": "hls",
                        "VideoCodec": "h264,hevc",
                        "AudioCodec": "aac,ac3,eac3,opus,mp3",
                        "Context": "Streaming"
                    }
                ],
                "SubtitleProfiles": [
                    { "Format": "srt", "Method": "External" },
                    { "Format": "vtt", "Method": "External" },
                    { "Format": "ass", "Method": "Embed" },
                    { "Format": "ssa", "Method": "Embed" },
                    { "Format": "subrip", "Method": "Embed" }
                ]
            }
        });

        let resp = self
            .http
            .post(&url)
            .header("Authorization", self.auth_header(Some(&profile.access_token)))
            .json(&device_profile)
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "Jellyfin PlaybackInfo failed: {}",
                resp.status()
            )));
        }
        let info: Value = resp.json().await?;
        let play_session_id = info
            .get("PlaySessionId")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let source = info
            .get("MediaSources")
            .and_then(|v| v.as_array())
            .and_then(|a| a.first())
            .ok_or_else(|| CoreError::NotFound("PlaybackInfo returned no MediaSources".into()))?;

        let media_source_id = source
            .get("Id")
            .and_then(|v| v.as_str())
            .unwrap_or(item_id)
            .to_string();
        let supports_direct_play = source
            .get("SupportsDirectPlay")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        let supports_direct_stream = source
            .get("SupportsDirectStream")
            .and_then(|v| v.as_bool())
            .unwrap_or(true);
        let container = source
            .get("Container")
            .and_then(|v| v.as_str())
            .unwrap_or("mkv");

        let (play_url, play_method) = if supports_direct_play || supports_direct_stream {
            (
                format!(
                    "{base}/Videos/{item_id}/stream.{container}?Static=true&mediaSourceId={media_source_id}&api_key={}",
                    urlencoding::encode(&profile.access_token)
                ),
                if supports_direct_play {
                    "DirectPlay".to_string()
                } else {
                    "DirectStream".to_string()
                },
            )
        } else if let Some(trans_url) = source.get("TranscodingUrl").and_then(|v| v.as_str()) {
            let full = if trans_url.starts_with("http") {
                trans_url.to_string()
            } else {
                format!("{base}{trans_url}")
            };
            (full, "Transcode (HLS)".to_string())
        } else {
            (
                format!(
                    "{base}/Videos/{item_id}/stream?Static=true&mediaSourceId={media_source_id}&api_key={}",
                    urlencoding::encode(&profile.access_token)
                ),
                "DirectPlay".to_string(),
            )
        };

        let mut external_subtitles = Vec::new();
        if let Some(streams) = source.get("MediaStreams").and_then(|v| v.as_array()) {
            for ms in streams {
                if ms.get("Type").and_then(|v| v.as_str()) != Some("Subtitle") {
                    continue;
                }
                let is_ext = ms
                    .get("IsExternal")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let delivery = ms.get("DeliveryUrl").and_then(|v| v.as_str()).unwrap_or("");
                if is_ext && !delivery.is_empty() {
                    let sub_url = if delivery.starts_with("http") {
                        delivery.to_string()
                    } else {
                        format!("{base}{delivery}")
                    };
                    let idx = ms.get("Index").and_then(|v| v.as_i64()).unwrap_or(0);
                    let lang = ms
                        .get("Language")
                        .and_then(|v| v.as_str())
                        .unwrap_or("und")
                        .to_string();
                    external_subtitles.push(ExternalSubtitle {
                        id: format!("jfsub:{idx}"),
                        url: sub_url,
                        lang,
                        addon_name: profile.server_name.clone(),
                    });
                }
            }
        }

        let runtime_ms = source
            .get("RunTimeTicks")
            .and_then(|v| v.as_u64())
            .map(|t| t / TICKS_PER_MS)
            .unwrap_or(0);

        // Fetch UserData playback position if available
        let item_detail_val = self
            .authed_get_json(
                &format!(
                    "{base}/Users/{}/Items/{}",
                    profile.user_id,
                    urlencoding::encode(item_id)
                ),
                &profile.access_token,
            )
            .await
            .ok();
        let resume_position_ms = item_detail_val
            .as_ref()
            .and_then(|v| v.get("UserData"))
            .and_then(|u| u.get("PlaybackPositionTicks"))
            .and_then(|v| v.as_u64())
            .map(|t| t / TICKS_PER_MS)
            .unwrap_or(0);

        let mut headers = HashMap::new();
        headers.insert(
            "Authorization".into(),
            self.auth_header(Some(&profile.access_token)),
        );

        Ok(JellyfinPlaybackSession {
            play_url,
            request_headers: headers,
            play_method,
            play_session_id,
            media_source_id,
            external_subtitles,
            runtime_ms,
            resume_position_ms,
        })
    }

    /// Queries Jellyfin 10.10+ `/MediaSegments/{id}` AND Intro-Skipper `/Episode/{id}/IntroTimestamps`.
    pub async fn fetch_skip_segments(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
    ) -> CoreResult<Vec<SkipSegment>> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let mut segments = Vec::new();

        // 1. Native Jellyfin 10.10+ MediaSegments API
        if let Ok(ms_json) = self
            .authed_get_json(
                &format!("{base}/MediaSegments/{}", urlencoding::encode(item_id)),
                &profile.access_token,
            )
            .await
        {
            if let Some(arr) = ms_json.get("Items").and_then(|v| v.as_array()) {
                for seg in arr {
                    let s_ticks = seg.get("StartTicks").and_then(|v| v.as_u64()).unwrap_or(0);
                    let e_ticks = seg.get("EndTicks").and_then(|v| v.as_u64()).unwrap_or(0);
                    if e_ticks <= s_ticks {
                        continue;
                    }
                    let seg_type = seg
                        .get("Type")
                        .and_then(|v| v.as_str())
                        .unwrap_or("Intro")
                        .to_ascii_lowercase();
                    let (kind, label) = match seg_type.as_str() {
                        "outro" | "credits" => (SkipSegmentKind::Outro, "Skip Credits"),
                        "recap" => (SkipSegmentKind::Recap, "Skip Recap"),
                        "preview" => (SkipSegmentKind::Preview, "Skip Preview"),
                        "commercial" => (SkipSegmentKind::Sponsor, "Skip Commercial"),
                        _ => (SkipSegmentKind::Intro, "Skip Intro"),
                    };
                    segments.push(SkipSegment {
                        start_ms: s_ticks / TICKS_PER_MS,
                        end_ms: e_ticks / TICKS_PER_MS,
                        kind,
                        label: label.to_string(),
                    });
                }
            }
        }

        // 2. Intro-Skipper plugin fallback
        if segments.is_empty() {
            if let Ok(intro_json) = self
                .authed_get_json(
                    &format!(
                        "{base}/Episode/{}/IntroTimestamps",
                        urlencoding::encode(item_id)
                    ),
                    &profile.access_token,
                )
                .await
            {
                let start_s = intro_json
                    .get("IntroStart")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(0.0);
                let end_s = intro_json
                    .get("IntroEnd")
                    .and_then(|v| v.as_f64())
                    .unwrap_or(0.0);
                if end_s > start_s {
                    segments.push(SkipSegment {
                        start_ms: (start_s * 1000.0) as u64,
                        end_ms: (end_s * 1000.0) as u64,
                        kind: SkipSegmentKind::Intro,
                        label: "Skip Intro".to_string(),
                    });
                }
            }
        }
        Ok(segments)
    }

    pub async fn report_playback_start(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
        media_source_id: &str,
        play_session_id: &str,
        position_ms: u64,
    ) -> CoreResult<()> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let _ = self
            .http
            .post(format!("{base}/Sessions/Playing"))
            .header("Authorization", self.auth_header(Some(&profile.access_token)))
            .json(&json!({
                "ItemId": item_id,
                "MediaSourceId": media_source_id,
                "PlaySessionId": play_session_id,
                "PositionTicks": position_ms * TICKS_PER_MS,
                "CanSeek": true,
                "IsPaused": false
            }))
            .send()
            .await;
        Ok(())
    }

    pub async fn report_playback_progress(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
        media_source_id: &str,
        play_session_id: &str,
        position_ms: u64,
        is_paused: bool,
    ) -> CoreResult<()> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let _ = self
            .http
            .post(format!("{base}/Sessions/Playing/Progress"))
            .header("Authorization", self.auth_header(Some(&profile.access_token)))
            .json(&json!({
                "ItemId": item_id,
                "MediaSourceId": media_source_id,
                "PlaySessionId": play_session_id,
                "PositionTicks": position_ms * TICKS_PER_MS,
                "IsPaused": is_paused,
                "CanSeek": true
            }))
            .send()
            .await;
        Ok(())
    }

    pub async fn report_playback_stopped(
        &self,
        profile: &JellyfinServerProfile,
        raw_or_composite_id: &str,
        media_source_id: &str,
        play_session_id: &str,
        position_ms: u64,
    ) -> CoreResult<()> {
        let item_id = strip_composite_prefix(raw_or_composite_id);
        let base = self.resolve_active_base_url(profile).await?;
        let _ = self
            .http
            .post(format!("{base}/Sessions/Playing/Stopped"))
            .header("Authorization", self.auth_header(Some(&profile.access_token)))
            .json(&json!({
                "ItemId": item_id,
                "MediaSourceId": media_source_id,
                "PlaySessionId": play_session_id,
                "PositionTicks": position_ms * TICKS_PER_MS
            }))
            .send()
            .await;

        if !play_session_id.is_empty() {
            let dev_id = self.config.jellyfin_device_id();
            let _ = self
                .http
                .delete(format!(
                    "{base}/Videos/ActiveEncodings?DeviceId={}&PlaySessionId={}",
                    urlencoding::encode(&dev_id),
                    urlencoding::encode(play_session_id)
                ))
                .header("Authorization", self.auth_header(Some(&profile.access_token)))
                .send()
                .await;
        }
        Ok(())
    }

    async fn authed_get_json(&self, url: &str, token: &str) -> CoreResult<Value> {
        let resp = self
            .http
            .get(url)
            .header("Authorization", self.auth_header(Some(token)))
            .send()
            .await?;
        if resp.status().as_u16() == 401 || resp.status().as_u16() == 403 {
            return Err(CoreError::Auth("Jellyfin token unauthorized".into()));
        }
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "Jellyfin GET failed ({}) for {url}",
                resp.status()
            )));
        }
        Ok(resp.json().await?)
    }

    fn parse_items_array(
        &self,
        root: &Value,
        base_url: &str,
        profile: &JellyfinServerProfile,
    ) -> Vec<MetaItem> {
        root.get("Items")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|x| parse_jellyfin_meta_item(x, base_url, profile))
                    .collect()
            })
            .unwrap_or_default()
    }
}

fn normalize_jellyfin_url(raw: &str) -> CoreResult<String> {
    let trimmed = raw.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(CoreError::InvalidInput("Jellyfin server URL is empty".into()));
    }
    let with_scheme = if trimmed.starts_with("http://") || trimmed.starts_with("https://") {
        trimmed.to_string()
    } else {
        format!("http://{trimmed}")
    };
    let _ = url::Url::parse(&with_scheme)?;
    Ok(with_scheme)
}

fn strip_composite_prefix(id: &str) -> &str {
    if let Some(rest) = id.strip_prefix("jf:") {
        if let Some((_, raw)) = rest.split_once(':') {
            return raw;
        }
    }
    id
}

fn parse_jellyfin_meta_item(
    item: &Value,
    base_url: &str,
    profile: &JellyfinServerProfile,
) -> Option<MetaItem> {
    let raw_id = item.get("Id").and_then(|v| v.as_str())?;
    let jf_type = item.get("Type").and_then(|v| v.as_str()).unwrap_or("Movie");
    let media_type = match jf_type {
        "Series" | "Season" | "Episode" => "series",
        _ => "movie",
    };

    let target_id = if jf_type == "Episode" {
        item.get("SeriesId")
            .and_then(|v| v.as_str())
            .unwrap_or(raw_id)
    } else {
        raw_id
    };
    let poster_id = target_id;

    let name = if jf_type == "Episode" {
        let s_name = item
            .get("SeriesName")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let ep_name = item.get("Name").and_then(|v| v.as_str()).unwrap_or("");
        if !s_name.is_empty() {
            format!("{s_name} — {ep_name}")
        } else {
            ep_name.to_string()
        }
    } else {
        item.get("Name")
            .and_then(|v| v.as_str())
            .unwrap_or(raw_id)
            .to_string()
    };

    let rating = item
        .get("CommunityRating")
        .and_then(|v| v.as_f64())
        .map(|r| format!("{r:.1}"))
        .unwrap_or_default();
    let year = item
        .get("ProductionYear")
        .and_then(|v| v.as_i64())
        .map(|y| y.to_string())
        .unwrap_or_default();
    let runtime_mins = item
        .get("RunTimeTicks")
        .and_then(|v| v.as_u64())
        .map(|t| (t / TICKS_PER_MS) / 60_000)
        .filter(|m| *m > 0)
        .map(|m| format!("{m} min"))
        .unwrap_or_default();
    let genres = item
        .get("Genres")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(String::from))
                .collect()
        })
        .unwrap_or_default();

    Some(MetaItem {
        id: JellyfinIds::make_id(&profile.server_id, target_id),
        media_type: media_type.to_string(),
        name,
        poster: format!("{base_url}/Items/{poster_id}/Images/Primary?maxWidth=440&quality=88"),
        background: format!("{base_url}/Items/{poster_id}/Images/Backdrop/0?maxWidth=1920&quality=85"),
        description: item
            .get("Overview")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        release_info: year,
        imdb_rating: rating,
        runtime: runtime_mins,
        genres,
        videos: Vec::new(),
        addon_source: format!("Jellyfin • {}", profile.server_name),
    })
}
