use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{
    now_epoch_ms, CreatorCategory, CreatorChannel, CreatorVideoItem, DeviceCodeSession,
    ResolvedMediaStream, TwitchAuthProfile,
};
use crate::torrent::LocalMediaServer;
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine as _};
use regex::Regex;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, OnceLock};
use tokio::sync::Mutex as AsyncMutex;

// ─────────────────────────────────────────────────────────────────────────────
// 1. Twitch Client + Ad-Free HLS Playlist Rewriter / Ad Stripper
// ─────────────────────────────────────────────────────────────────────────────

const TWITCH_GQL_URL: &str = "https://gql.twitch.tv/gql";
const TWITCH_WEB_CLIENT_ID: &str = "kimne78kx3ncx6brgo4mv6wki5h1ko";
const TWITCH_TV_CLIENT_ID: &str = "ue6666qo983tsx6so1t0vnawi233wa";

pub struct TwitchClient {
    http: reqwest::Client,
    config: Arc<ConfigStore>,
    media_server: Arc<LocalMediaServer>,
    refresh_mutex: AsyncMutex<()>,
}

impl TwitchClient {
    pub fn new(
        http: reqwest::Client,
        config: Arc<ConfigStore>,
        media_server: Arc<LocalMediaServer>,
    ) -> Self {
        Self {
            http,
            config,
            media_server,
            refresh_mutex: AsyncMutex::new(()),
        }
    }

    pub async fn start_device_code_auth(&self) -> CoreResult<DeviceCodeSession> {
        let resp = self
            .http
            .post("https://id.twitch.tv/oauth2/device")
            .form(&[
                ("client_id", TWITCH_TV_CLIENT_ID),
                ("scopes", "user:read:follows"),
            ])
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "Twitch device auth failed ({})",
                resp.status()
            )));
        }
        let val: Value = resp.json().await?;
        Ok(DeviceCodeSession {
            device_code: val
                .get("device_code")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            user_code: val
                .get("user_code")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            verification_url: val
                .get("verification_uri")
                .and_then(|v| v.as_str())
                .unwrap_or("https://www.twitch.tv/activate")
                .to_string(),
            expires_in_sec: val.get("expires_in").and_then(|v| v.as_u64()).unwrap_or(1800),
            interval_sec: val.get("interval").and_then(|v| v.as_u64()).unwrap_or(5),
            client_id: TWITCH_TV_CLIENT_ID.into(),
            client_secret: String::new(),
        })
    }

    pub async fn poll_device_token(
        &self,
        session: &DeviceCodeSession,
    ) -> CoreResult<Option<TwitchAuthProfile>> {
        let resp = self
            .http
            .post("https://id.twitch.tv/oauth2/token")
            .form(&[
                ("client_id", TWITCH_TV_CLIENT_ID),
                ("scopes", "user:read:follows"),
                ("device_code", &session.device_code),
                (
                    "grant_type",
                    "urn:ietf:params:oauth:grant-type:device_code",
                ),
            ])
            .send()
            .await?;
        let val: Value = resp.json().await?;
        if let Some(msg) = val.get("message").and_then(|v| v.as_str()) {
            if msg == "authorization_pending" || msg == "slow_down" {
                return Ok(None);
            }
            return Err(CoreError::Auth(format!("Twitch auth error: {msg}")));
        }

        let access_token = val
            .get("access_token")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| CoreError::Auth("Twitch missing access_token".into()))?
            .to_string();
        let refresh_token = val
            .get("refresh_token")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        // Validate to get user_id & login
        let val_resp: Value = self
            .http
            .get("https://id.twitch.tv/oauth2/validate")
            .header("Authorization", format!("OAuth {access_token}"))
            .send()
            .await?
            .json()
            .await?;
        let login = val_resp
            .get("login")
            .and_then(|v| v.as_str())
            .unwrap_or("twitch_user")
            .to_string();
        let user_id = val_resp
            .get("user_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();

        let profile = TwitchAuthProfile {
            user_id,
            display_name: login.clone(),
            login,
            access_token,
            refresh_token,
            updated_at_ms: now_epoch_ms(),
        };
        self.config.set_twitch_profile(Some(profile.clone()))?;
        Ok(Some(profile))
    }

    async fn refresh_token_single_flight(&self) -> Option<String> {
        let _guard = self.refresh_mutex.lock().await;
        let prof = self.config.twitch_profile()?;
        if prof.refresh_token.is_empty() {
            return None;
        }
        let resp = self
            .http
            .post("https://id.twitch.tv/oauth2/token")
            .form(&[
                ("client_id", TWITCH_TV_CLIENT_ID),
                ("grant_type", "refresh_token"),
                ("refresh_token", &prof.refresh_token),
            ])
            .send()
            .await
            .ok()?;
        if !resp.status().is_success() {
            return None;
        }
        let val: Value = resp.json().await.ok()?;
        let new_access = val.get("access_token")?.as_str()?.to_string();
        let new_refresh = val
            .get("refresh_token")
            .and_then(|v| v.as_str())
            .unwrap_or(&prof.refresh_token)
            .to_string();
        let mut updated = prof;
        updated.access_token = new_access.clone();
        updated.refresh_token = new_refresh;
        updated.updated_at_ms = now_epoch_ms();
        let _ = self.config.set_twitch_profile(Some(updated));
        Some(new_access)
    }

    async fn post_gql(&self, query: &str, use_auth: bool) -> CoreResult<Value> {
        let mut req = self.http.post(TWITCH_GQL_URL);
        if use_auth {
            if let Some(prof) = self.config.twitch_profile() {
                req = req
                    .header("Client-ID", TWITCH_TV_CLIENT_ID)
                    .header("Authorization", format!("OAuth {}", prof.access_token));
            } else {
                req = req.header("Client-ID", TWITCH_WEB_CLIENT_ID);
            }
        } else {
            req = req.header("Client-ID", TWITCH_WEB_CLIENT_ID);
        }
        let resp = req.json(&json!({ "query": query })).send().await?;
        if resp.status().as_u16() == 401 && use_auth {
            if let Some(refreshed) = self.refresh_token_single_flight().await {
                let retry = self
                    .http
                    .post(TWITCH_GQL_URL)
                    .header("Client-ID", TWITCH_TV_CLIENT_ID)
                    .header("Authorization", format!("OAuth {refreshed}"))
                    .json(&json!({ "query": query }))
                    .send()
                    .await?;
                return Ok(retry.json().await?);
            }
        }
        Ok(resp.json().await?)
    }

    pub async fn get_top_live_streams(&self, limit: u32) -> CoreResult<Vec<CreatorVideoItem>> {
        let q = format!(
            "query {{ streams(first: {}) {{ edges {{ node {{ id title viewersCount previewImageURL(width: 640, height: 360) broadcaster {{ id login displayName profileImageURL(width: 150) }} game {{ displayName }} }} }} }} }}",
            limit.clamp(1, 60)
        );
        let root = self.post_gql(&q, false).await?;
        Ok(parse_twitch_stream_edges(
            root.pointer("/data/streams/edges"),
        ))
    }

    pub async fn get_top_categories(&self, limit: u32) -> CoreResult<Vec<CreatorCategory>> {
        let q = format!(
            "query {{ games(first: {}) {{ edges {{ node {{ id name displayName viewersCount boxArtURL(width: 285, height: 380) }} }} }} }}",
            limit.clamp(1, 60)
        );
        let root = self.post_gql(&q, false).await?;
        let mut out = Vec::new();
        if let Some(edges) = root
            .pointer("/data/games/edges")
            .and_then(|v| v.as_array())
        {
            for edge in edges {
                let Some(n) = edge.get("node") else {
                    continue;
                };
                let name = n
                    .get("name")
                    .or_else(|| n.get("displayName"))
                    .and_then(|v| v.as_str())
                    .unwrap_or("");
                if name.is_empty() {
                    continue;
                }
                let viewers = n.get("viewersCount").and_then(|v| v.as_u64()).unwrap_or(0);
                out.push(CreatorCategory {
                    platform: "twitch".into(),
                    id: n
                        .get("id")
                        .and_then(|v| v.as_str())
                        .unwrap_or(name)
                        .to_string(),
                    name: name.to_string(),
                    box_art_url: n
                        .get("boxArtURL")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    viewers_text: format!("{viewers} watching"),
                });
            }
        }
        Ok(out)
    }

    pub async fn get_category_streams(
        &self,
        game_name: &str,
        limit: u32,
    ) -> CoreResult<Vec<CreatorVideoItem>> {
        let safe_name = game_name.replace('"', "\\\"");
        let q = format!(
            "query {{ game(name: \"{safe_name}\") {{ streams(first: {}) {{ edges {{ node {{ id title viewersCount previewImageURL(width: 640, height: 360) broadcaster {{ id login displayName profileImageURL(width: 150) }} game {{ displayName }} }} }} }} }} }}",
            limit.clamp(1, 60)
        );
        let root = self.post_gql(&q, false).await?;
        Ok(parse_twitch_stream_edges(
            root.pointer("/data/game/streams/edges"),
        ))
    }

    pub async fn get_followed_live_streams(&self) -> CoreResult<Vec<CreatorVideoItem>> {
        if self.config.twitch_profile().is_some() {
            let q = "query { currentUser { followedLiveUsers(first: 40) { edges { node { id login displayName profileImageURL(width: 150) stream { id title viewersCount previewImageURL(width: 640, height: 360) game { displayName } } } } } } }";
            let root = self.post_gql(q, true).await?;
            let mut out = Vec::new();
            if let Some(edges) = root
                .pointer("/data/currentUser/followedLiveUsers/edges")
                .and_then(|v| v.as_array())
            {
                for edge in edges {
                    let Some(u) = edge.get("node") else {
                        continue;
                    };
                    let Some(s) = u.get("stream").filter(|x| !x.is_null()) else {
                        continue;
                    };
                    let login = u.get("login").and_then(|v| v.as_str()).unwrap_or("");
                    let viewers = s.get("viewersCount").and_then(|v| v.as_u64()).unwrap_or(0);
                    out.push(CreatorVideoItem {
                        platform: "twitch".into(),
                        video_id: format!("live:{login}"),
                        title: s
                            .get("title")
                            .and_then(|v| v.as_str())
                            .unwrap_or(login)
                            .to_string(),
                        channel_name: u
                            .get("displayName")
                            .and_then(|v| v.as_str())
                            .unwrap_or(login)
                            .to_string(),
                        channel_id: login.to_string(),
                        channel_avatar: u
                            .get("profileImageURL")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        thumbnail_url: s
                            .get("previewImageURL")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        duration_text: "LIVE".into(),
                        view_count_text: format!("{viewers} viewers"),
                        is_live: true,
                        category_name: s
                            .pointer("/game/displayName")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        ..Default::default()
                    });
                }
            }
            return Ok(out);
        }

        // Anonymous local follow check
        let follows = self.config.active_creator_follows(Some("twitch"));
        let mut live = Vec::new();
        for f in follows {
            if let Ok(ch) = self.get_channel_detail(&f.channel_id).await {
                if let Some(ls) = ch.live_stream {
                    live.push(ls);
                }
            }
        }
        Ok(live)
    }

    pub async fn get_channel_detail(&self, login: &str) -> CoreResult<CreatorChannel> {
        let safe = login
            .trim()
            .trim_start_matches("live:")
            .replace('"', "");
        let q = format!(
            "query {{ user(login: \"{safe}\") {{ id login displayName description profileImageURL(width: 300) followers {{ totalCount }} stream {{ id title viewersCount previewImageURL(width: 640, height: 360) game {{ displayName }} }} videos(first: 24, type: ARCHIVE) {{ edges {{ node {{ id title lengthSeconds viewCount publishedAt previewThumbnailURL(width: 640, height: 360) }} }} }} }} }}"
        );
        let root = self.post_gql(&q, false).await?;
        let u = root
            .pointer("/data/user")
            .filter(|x| !x.is_null())
            .ok_or_else(|| CoreError::NotFound(format!("Twitch channel {safe} not found")))?;

        let disp = u
            .get("displayName")
            .and_then(|v| v.as_str())
            .unwrap_or(&safe)
            .to_string();
        let avatar = u
            .get("profileImageURL")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let live_stream = u.get("stream").filter(|s| !s.is_null()).map(|s| {
            let viewers = s.get("viewersCount").and_then(|v| v.as_u64()).unwrap_or(0);
            CreatorVideoItem {
                platform: "twitch".into(),
                video_id: format!("live:{safe}"),
                title: s
                    .get("title")
                    .and_then(|v| v.as_str())
                    .unwrap_or(&disp)
                    .to_string(),
                channel_name: disp.clone(),
                channel_id: safe.clone(),
                channel_avatar: avatar.clone(),
                thumbnail_url: s
                    .get("previewImageURL")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string(),
                duration_text: "LIVE".into(),
                view_count_text: format!("{viewers} viewers"),
                is_live: true,
                ..Default::default()
            }
        });

        let mut videos = Vec::new();
        if let Some(edges) = u
            .pointer("/videos/edges")
            .and_then(|v| v.as_array())
        {
            for edge in edges {
                let Some(vn) = edge.get("node") else {
                    continue;
                };
                let Some(vid) = vn.get("id").and_then(|v| v.as_str()) else {
                    continue;
                };
                let secs = vn
                    .get("lengthSeconds")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0);
                videos.push(CreatorVideoItem {
                    platform: "twitch".into(),
                    video_id: format!("vod:{vid}"),
                    title: vn
                        .get("title")
                        .and_then(|v| v.as_str())
                        .unwrap_or("VOD")
                        .to_string(),
                    channel_name: disp.clone(),
                    channel_id: safe.clone(),
                    channel_avatar: avatar.clone(),
                    thumbnail_url: vn
                        .get("previewThumbnailURL")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    duration_sec: secs,
                    duration_text: format!("{}:{:02}", secs / 60, secs % 60),
                    view_count_text: format!(
                        "{} views",
                        vn.get("viewCount").and_then(|v| v.as_u64()).unwrap_or(0)
                    ),
                    ..Default::default()
                });
            }
        }

        let followers = u
            .pointer("/followers/totalCount")
            .and_then(|v| v.as_u64())
            .unwrap_or(0);

        Ok(CreatorChannel {
            platform: "twitch".into(),
            channel_id: safe.clone(),
            name: disp,
            handle: format!("@{safe}"),
            avatar_url: avatar,
            banner_url: String::new(),
            subscriber_text: format!("{followers} followers"),
            description: u
                .get("description")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            is_subscribed: self
                .config
                .active_creator_follows(Some("twitch"))
                .iter()
                .any(|f| f.channel_id.eq_ignore_ascii_case(&safe)),
            live_stream,
            videos,
        })
    }

    pub async fn search_twitch(&self, query: &str) -> CoreResult<Vec<CreatorVideoItem>> {
        let safe = query.trim().replace('"', "\\\"");
        let q = format!(
            "query {{ searchFor(userQuery: \"{safe}\", platform: \"web\") {{ channels {{ edges {{ item {{ id login displayName profileImageURL(width: 150) stream {{ id title viewersCount previewImageURL(width: 640, height: 360) game {{ displayName }} }} }} }} }} videos {{ edges {{ item {{ id title lengthSeconds viewCount previewThumbnailURL(width: 640, height: 360) owner {{ login displayName }} }} }} }} }} }}"
        );
        let root = self.post_gql(&q, false).await?;
        let mut out = Vec::new();
        if let Some(ch_edges) = root
            .pointer("/data/searchFor/channels/edges")
            .and_then(|v| v.as_array())
        {
            for edge in ch_edges {
                let Some(it) = edge.get("item") else {
                    continue;
                };
                let login = it.get("login").and_then(|v| v.as_str()).unwrap_or("");
                if let Some(s) = it.get("stream").filter(|x| !x.is_null()) {
                    let viewers = s.get("viewersCount").and_then(|v| v.as_u64()).unwrap_or(0);
                    out.push(CreatorVideoItem {
                        platform: "twitch".into(),
                        video_id: format!("live:{login}"),
                        title: s
                            .get("title")
                            .and_then(|v| v.as_str())
                            .unwrap_or(login)
                            .to_string(),
                        channel_name: it
                            .get("displayName")
                            .and_then(|v| v.as_str())
                            .unwrap_or(login)
                            .to_string(),
                        channel_id: login.to_string(),
                        thumbnail_url: s
                            .get("previewImageURL")
                            .and_then(|v| v.as_str())
                            .unwrap_or("")
                            .to_string(),
                        duration_text: "LIVE".into(),
                        view_count_text: format!("{viewers} viewers"),
                        is_live: true,
                        ..Default::default()
                    });
                }
            }
        }
        if let Some(v_edges) = root
            .pointer("/data/searchFor/videos/edges")
            .and_then(|v| v.as_array())
        {
            for edge in v_edges {
                let Some(it) = edge.get("item") else {
                    continue;
                };
                let Some(vid) = it.get("id").and_then(|v| v.as_str()) else {
                    continue;
                };
                let secs = it
                    .get("lengthSeconds")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0);
                out.push(CreatorVideoItem {
                    platform: "twitch".into(),
                    video_id: format!("vod:{vid}"),
                    title: it
                        .get("title")
                        .and_then(|v| v.as_str())
                        .unwrap_or("VOD")
                        .to_string(),
                    channel_name: it
                        .pointer("/owner/displayName")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    channel_id: it
                        .pointer("/owner/login")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    thumbnail_url: it
                        .get("previewThumbnailURL")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string(),
                    duration_sec: secs,
                    duration_text: format!("{}:{:02}", secs / 60, secs % 60),
                    ..Default::default()
                });
            }
        }
        Ok(out)
    }

    /// Resolves an ad-free Twitch Live or VOD HLS stream routed through the local loopback
    /// M3U8 ad-stripper proxy (`http://127.0.0.1:<port>/twitch/{session}/master.m3u8`).
    pub async fn resolve_stream(&self, video_or_login: &str) -> CoreResult<ResolvedMediaStream> {
        let is_vod = video_or_login.starts_with("vod:");
        let target = video_or_login
            .trim_start_matches("live:")
            .trim_start_matches("vod:")
            .trim();

        for player_type in ["embed", "thunderdome", "site"] {
            let gql_body = json!({
                "operationName": "PlaybackAccessToken",
                "extensions": {
                    "persistedQuery": {
                        "version": 1,
                        "sha256Hash": "0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712"
                    }
                },
                "variables": {
                    "isLive": !is_vod,
                    "login": if is_vod { "" } else { target },
                    "isVod": is_vod,
                    "vodID": if is_vod { target } else { "" },
                    "playerType": player_type
                }
            });

            let Ok(resp) = self
                .http
                .post(TWITCH_GQL_URL)
                .header("Client-ID", TWITCH_WEB_CLIENT_ID)
                .json(&gql_body)
                .send()
                .await
            else {
                continue;
            };
            let Ok(root) = resp.json::<Value>().await else {
                continue;
            };
            let tok_obj = if is_vod {
                root.pointer("/data/videoPlaybackAccessToken")
            } else {
                root.pointer("/data/streamPlaybackAccessToken")
            };
            let Some(sig) = tok_obj
                .and_then(|t| t.get("signature"))
                .and_then(|v| v.as_str())
            else {
                continue;
            };
            let Some(val_str) = tok_obj
                .and_then(|t| t.get("value"))
                .and_then(|v| v.as_str())
            else {
                continue;
            };

            let usher_url = if is_vod {
                format!(
                    "https://usher.ttvnw.net/vod/{target}.m3u8?allow_source=true&allow_audio_only=true&player=twitchweb&sig={}&token={}",
                    urlencoding::encode(sig),
                    urlencoding::encode(val_str)
                )
            } else {
                format!(
                    "https://usher.ttvnw.net/api/channel/hls/{target}.m3u8?allow_source=true&allow_audio_only=true&fast_bread=true&player=twitchweb&sig={}&token={}",
                    urlencoding::encode(sig),
                    urlencoding::encode(val_str)
                )
            };

            let proxied_url = self
                .media_server
                .register_twitch_hls_session(usher_url, HashMap::new());
            return Ok(ResolvedMediaStream {
                play_url: proxied_url,
                protocol: "HLS".into(),
                title: target.to_string(),
                author: target.to_string(),
                duration_sec: 0,
                is_live: !is_vod,
                quality_label: format!("Ad-Free Source HLS ({player_type})"),
                request_headers: HashMap::new(),
                subtitles: Vec::new(),
                skip_segments: Vec::new(),
                playback_tracking_url: String::new(),
                watchtime_tracking_url: String::new(),
            });
        }
        Err(CoreError::NotFound(format!(
            "Failed to obtain Twitch PlaybackAccessToken for {target}"
        )))
    }
}

fn parse_twitch_stream_edges(edges_val: Option<&Value>) -> Vec<CreatorVideoItem> {
    let Some(edges) = edges_val.and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for edge in edges {
        let Some(n) = edge.get("node").filter(|x| !x.is_null()) else {
            continue;
        };
        let Some(b) = n.get("broadcaster") else {
            continue;
        };
        let login = b.get("login").and_then(|v| v.as_str()).unwrap_or("");
        if login.is_empty() {
            continue;
        }
        let viewers = n.get("viewersCount").and_then(|v| v.as_u64()).unwrap_or(0);
        out.push(CreatorVideoItem {
            platform: "twitch".into(),
            video_id: format!("live:{login}"),
            title: n
                .get("title")
                .and_then(|v| v.as_str())
                .unwrap_or(login)
                .to_string(),
            channel_name: b
                .get("displayName")
                .and_then(|v| v.as_str())
                .unwrap_or(login)
                .to_string(),
            channel_id: login.to_string(),
            channel_avatar: b
                .get("profileImageURL")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            thumbnail_url: n
                .get("previewImageURL")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            duration_text: "LIVE".into(),
            view_count_text: format!("{viewers} viewers"),
            is_live: true,
            category_name: n
                .pointer("/game/displayName")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            ..Default::default()
        });
    }
    out
}

/// Rewrites a Twitch Usher master `.m3u8` so every variant playlist URL routes through the
/// local loopback ad-free proxy (`/twitch/{session}/media.m3u8?u=<b64url>`).
pub fn rewrite_master_playlist_for_proxy(
    master_m3u8: &str,
    master_url: &str,
    proxy_media_prefix: &str,
) -> String {
    let base = url::Url::parse(master_url).ok();
    let mut out = Vec::new();
    for line in master_m3u8.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if !trimmed.starts_with('#') {
            let abs = resolve_relative_hls_url(base.as_ref(), trimmed);
            let encoded = URL_SAFE_NO_PAD.encode(abs.as_bytes());
            out.push(format!("{proxy_media_prefix}{encoded}"));
        } else if trimmed.starts_with("#EXT-X-MEDIA:") && trimmed.contains("URI=\"") {
            let rewritten = rewrite_uri_attribute(trimmed, base.as_ref(), Some(proxy_media_prefix));
            out.push(rewritten);
        } else {
            out.push(trimmed.to_string());
        }
    }
    let mut joined = out.join("\n");
    joined.push('\n');
    joined
}

/// Strips server-side stitched Twitch ads (`twitch-stitched-ad`, `stitched-ad`, `#EXT-X-SCTE35-`,
/// and `,Amazon` `#EXTINF` segments), increments `#EXT-X-MEDIA-SEQUENCE` by the count of stripped
/// leading ad segments, inserts `#EXT-X-DISCONTINUITY` across mid-roll ad gaps, and resolves
/// relative segment URLs against `playlist_url`.
///
/// Exact parity with `TwitchAdFreeHlsParserFactory.stripStitchedAdsFromMediaPlaylist`.
pub fn strip_stitched_ads_from_media_playlist(
    raw_playlist: &str,
    playlist_url: Option<&str>,
) -> String {
    let lower_probe = raw_playlist.to_ascii_lowercase();
    let has_ads = lower_probe.contains("stitched-ad")
        || lower_probe.contains("scte35")
        || raw_playlist.contains(",Amazon");

    let base_url = playlist_url.and_then(|u| url::Url::parse(u).ok());
    if !has_ads && base_url.is_none() {
        return raw_playlist.to_string();
    }

    let mut header_lines: Vec<String> = Vec::new();
    let mut media_sequence: Option<(usize, u64)> = None;
    let mut body_lines: Vec<String> = Vec::new();

    let mut pending_extinf: Option<String> = None;
    let mut pending_seg_meta: Vec<String> = Vec::new();
    let mut seen_clean_segment = false;
    let mut leading_stripped_count = 0u64;
    let mut stripped_since_last_clean = false;

    for raw_line in raw_playlist.lines() {
        let line = raw_line.trim();
        if line.is_empty() {
            continue;
        }
        let lower = line.to_ascii_lowercase();

        // Drop ad DATERANGE and SCTE-35 ad splice markers immediately
        if (line.starts_with("#EXT-X-DATERANGE:")
            && (lower.contains("stitched-ad") || lower.contains("twitch-ad")))
            || line.starts_with("#EXT-X-SCTE35-")
        {
            continue;
        }

        if let Some(seq_str) = line.strip_prefix("#EXT-X-MEDIA-SEQUENCE:") {
            if let Ok(seq) = seq_str.trim().parse::<u64>() {
                media_sequence = Some((header_lines.len(), seq));
            }
            header_lines.push(line.to_string());
            continue;
        }

        if line.starts_with("#EXTINF:") {
            pending_extinf = Some(line.to_string());
            continue;
        }

        if line.starts_with('#') {
            if line.starts_with("#EXT-X-MAP:") {
                let rewritten = rewrite_uri_attribute(line, base_url.as_ref(), None);
                if seen_clean_segment || pending_extinf.is_some() {
                    pending_seg_meta.push(rewritten);
                } else {
                    header_lines.push(rewritten);
                }
            } else if line.starts_with("#EXT-X-PROGRAM-DATE-TIME:")
                || line.starts_with("#EXT-X-BITRATE:")
                || line == "#EXT-X-DISCONTINUITY"
            {
                pending_seg_meta.push(line.to_string());
            } else if line == "#EXT-X-ENDLIST" {
                body_lines.push(line.to_string());
            } else if !seen_clean_segment && pending_extinf.is_none() {
                header_lines.push(line.to_string());
            } else {
                body_lines.push(line.to_string());
            }
            continue;
        }

        // Media segment URI line!
        let extinf = pending_extinf.take().unwrap_or_else(|| "#EXTINF:2.000,".into());
        let extinf_lower = extinf.to_ascii_lowercase();
        let is_ad_segment = extinf.contains(",Amazon")
            || extinf_lower.contains("stitched-ad")
            || extinf_lower.contains("twitch-ad")
            || lower.contains("stitched-ad");

        if is_ad_segment {
            pending_seg_meta.clear();
            if !seen_clean_segment {
                leading_stripped_count += 1;
            } else {
                stripped_since_last_clean = true;
            }
            continue;
        }

        if stripped_since_last_clean {
            body_lines.push("#EXT-X-DISCONTINUITY".to_string());
            stripped_since_last_clean = false;
        }
        seen_clean_segment = true;
        for m in pending_seg_meta.drain(..) {
            body_lines.push(m);
        }
        body_lines.push(extinf);
        let resolved_seg = resolve_relative_hls_url(base_url.as_ref(), line);
        body_lines.push(resolved_seg);
    }

    // If every single segment in a pre-roll window was ads, preserve raw playlist rather than empty stall
    if !seen_clean_segment && leading_stripped_count > 0 {
        return raw_playlist.to_string();
    }

    if let Some((idx, orig_seq)) = media_sequence {
        if leading_stripped_count > 0 {
            header_lines[idx] = format!(
                "#EXT-X-MEDIA-SEQUENCE:{}",
                orig_seq + leading_stripped_count
            );
        }
    }

    let mut combined = Vec::with_capacity(header_lines.len() + body_lines.len());
    combined.extend(header_lines);
    combined.extend(body_lines);
    let mut res = combined.join("\n");
    res.push('\n');
    res
}

fn resolve_relative_hls_url(base: Option<&url::Url>, raw: &str) -> String {
    if raw.starts_with("http://") || raw.starts_with("https://") {
        return raw.to_string();
    }
    if let Some(b) = base {
        if let Ok(joined) = b.join(raw) {
            return joined.to_string();
        }
    }
    raw.to_string()
}

fn rewrite_uri_attribute(
    line: &str,
    base: Option<&url::Url>,
    proxy_prefix: Option<&str>,
) -> String {
    let Some(start) = line.find("URI=\"") else {
        return line.to_string();
    };
    let val_start = start + 5;
    let Some(rel_end) = line[val_start..].find('"') else {
        return line.to_string();
    };
    let raw_uri = &line[val_start..val_start + rel_end];
    let abs = resolve_relative_hls_url(base, raw_uri);
    let replacement = match proxy_prefix {
        Some(pfx) => format!("{pfx}{}", URL_SAFE_NO_PAD.encode(abs.as_bytes())),
        None => abs,
    };
    format!(
        "{}{}{}",
        &line[..val_start],
        replacement,
        &line[val_start + rel_end..]
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Odysee Client (LBRY JSON-RPC + Lighthouse Search + Direct HLS/MP4)
// ─────────────────────────────────────────────────────────────────────────────

const ODYSEE_RPC_URL: &str = "https://api.na-backend.odysee.com/api/v1/proxy";

pub struct OdyseeClient {
    http: reqwest::Client,
}

impl OdyseeClient {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http }
    }

    pub async fn get_featured_videos(&self, page: u32) -> CoreResult<Vec<CreatorVideoItem>> {
        self.claim_search(json!({
            "page_size": 30,
            "page": page.max(1),
            "claim_type": ["stream"],
            "stream_types": ["video"],
            "no_totals": true,
            "not_tags": ["porn", "nsfw", "xxx", "mature"],
            "order_by": ["trending_group", "trending_mixed"]
        }))
        .await
    }

    pub async fn get_live_streams(&self) -> CoreResult<Vec<CreatorVideoItem>> {
        let resp: Value = self
            .http
            .get("https://api.odysee.live/livestream/all")
            .send()
            .await?
            .json()
            .await?;
        let mut claim_ids = Vec::new();
        let mut live_meta: HashMap<String, (u64, String)> = HashMap::new();
        if let Some(arr) = resp.get("data").and_then(|v| v.as_array()) {
            for item in arr.iter().take(25) {
                if !item.get("Live").and_then(|v| v.as_bool()).unwrap_or(false) {
                    continue;
                }
                if let Some(cid) = item
                    .pointer("/ActiveClaim/ClaimID")
                    .and_then(|v| v.as_str())
                    .filter(|s| !s.is_empty())
                {
                    let viewers = item.get("ViewerCount").and_then(|v| v.as_u64()).unwrap_or(0);
                    let thumb = item
                        .get("ThumbnailURL")
                        .and_then(|v| v.as_str())
                        .unwrap_or("")
                        .to_string();
                    claim_ids.push(cid.to_string());
                    live_meta.insert(cid.to_string(), (viewers, thumb));
                }
            }
        }
        if claim_ids.is_empty() {
            return Ok(Vec::new());
        }
        let mut resolved = self
            .claim_search(json!({
                "claim_ids": claim_ids,
                "page_size": 25,
                "no_totals": true
            }))
            .await?;
        for item in &mut resolved {
            item.is_live = true;
            item.duration_text = "LIVE".into();
            let raw_cid = item.video_id.split('#').nth(1).unwrap_or("");
            if let Some((viewers, thumb)) = live_meta.get(raw_cid) {
                item.view_count_text = format!("{viewers} watching");
                if item.thumbnail_url.is_empty() && !thumb.is_empty() {
                    item.thumbnail_url = thumb.clone();
                }
            }
        }
        Ok(resolved)
    }

    pub async fn search_videos(&self, query: &str) -> CoreResult<Vec<CreatorVideoItem>> {
        let lh_url = format!(
            "https://lighthouse.odysee.tv/search?s={}&size=25&from=0&claimType=file&mediaType=video&nsfw=false",
            urlencoding::encode(query.trim())
        );
        let hits: Value = self.http.get(&lh_url).send().await?.json().await?;
        let claim_ids: Vec<String> = hits
            .as_array()
            .map(|arr| {
                arr.iter()
                    .filter_map(|x| x.get("claimId").and_then(|v| v.as_str()).map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        if claim_ids.is_empty() {
            return Ok(Vec::new());
        }
        self.claim_search(json!({
            "claim_ids": claim_ids,
            "page_size": 25,
            "no_totals": true
        }))
        .await
    }

    pub async fn resolve_stream(&self, claim_spec: &str) -> CoreResult<ResolvedMediaStream> {
        // Accept `name#claim_id` or raw `claim_id`
        let (name_opt, claim_id) = match claim_spec.split_once('#') {
            Some((n, c)) => (Some(n), c),
            None => (None, claim_spec),
        };

        let resp: Value = self
            .http
            .post(ODYSEE_RPC_URL)
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "get",
                "params": {
                    "uri": if let Some(n) = name_opt { format!("lbry://{n}#{claim_id}") } else { claim_id.to_string() }
                }
            }))
            .send()
            .await?
            .json()
            .await?;

        let streaming_url = resp
            .pointer("/result/streaming_url")
            .and_then(|v| v.as_str())
            .unwrap_or("");
        let cdns_hls = if !claim_id.is_empty() {
            let slug = name_opt.unwrap_or("stream");
            format!("https://player.odycdn.com/v6/streams/{claim_id}/{slug}/master.m3u8")
        } else {
            String::new()
        };

        let (play_url, protocol) = if !streaming_url.is_empty() {
            let proto = if streaming_url.contains(".m3u8") {
                "HLS"
            } else {
                "PROGRESSIVE"
            };
            (streaming_url.to_string(), proto)
        } else if !cdns_hls.is_empty() {
            (cdns_hls, "HLS")
        } else {
            return Err(CoreError::NotFound("Odysee stream URL unavailable".into()));
        };

        Ok(ResolvedMediaStream {
            play_url,
            protocol: protocol.into(),
            title: name_opt.unwrap_or(claim_id).to_string(),
            author: "Odysee".into(),
            duration_sec: 0,
            is_live: false,
            quality_label: "Odysee Direct".into(),
            request_headers: HashMap::new(),
            subtitles: Vec::new(),
            skip_segments: Vec::new(),
            playback_tracking_url: String::new(),
            watchtime_tracking_url: String::new(),
        })
    }

    async fn claim_search(&self, params: Value) -> CoreResult<Vec<CreatorVideoItem>> {
        let resp: Value = self
            .http
            .post(ODYSEE_RPC_URL)
            .json(&json!({
                "jsonrpc": "2.0",
                "id": 1,
                "method": "claim_search",
                "params": params
            }))
            .send()
            .await?
            .json()
            .await?;
        let mut out = Vec::new();
        if let Some(items) = resp.pointer("/result/items").and_then(|v| v.as_array()) {
            for item in items {
                let Some(claim_id) = item.get("claim_id").and_then(|v| v.as_str()) else {
                    continue;
                };
                let slug = item.get("name").and_then(|v| v.as_str()).unwrap_or("video");
                let val = item.get("value");
                let title = val
                    .and_then(|v| v.get("title"))
                    .and_then(|x| x.as_str())
                    .unwrap_or(slug)
                    .to_string();
                let thumb = val
                    .and_then(|v| v.pointer("/thumbnail/url"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string();
                let dur = val
                    .and_then(|v| v.pointer("/video/duration"))
                    .and_then(|x| x.as_i64())
                    .unwrap_or(0);
                let signing = item.get("signing_channel");
                let ch_name = signing
                    .and_then(|s| s.pointer("/value/title").or_else(|| s.get("name")))
                    .and_then(|x| x.as_str())
                    .unwrap_or("Odysee")
                    .to_string();
                let ch_id = signing
                    .and_then(|s| s.get("claim_id"))
                    .and_then(|x| x.as_str())
                    .unwrap_or("")
                    .to_string();

                out.push(CreatorVideoItem {
                    platform: "odysee".into(),
                    video_id: format!("{slug}#{claim_id}"),
                    title,
                    channel_name: ch_name,
                    channel_id: ch_id,
                    thumbnail_url: thumb,
                    duration_sec: dur,
                    duration_text: if dur > 0 {
                        format!("{}:{:02}", dur / 60, dur % 60)
                    } else {
                        String::new()
                    },
                    ..Default::default()
                });
            }
        }
        Ok(out)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Rumble Client (`oembed.json` + `embedJS/u3` Direct Ad-Free 1080p HLS/MP4)
// ─────────────────────────────────────────────────────────────────────────────

const DESKTOP_UA: &str =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36";

pub struct RumbleClient {
    http: reqwest::Client,
}

impl RumbleClient {
    pub fn new(http: reqwest::Client) -> Self {
        Self { http }
    }

    pub async fn get_featured_videos(&self) -> CoreResult<Vec<CreatorVideoItem>> {
        self.scrape_rumble_page("https://rumble.com/editor-picks")
            .await
    }

    pub async fn get_live_streams(&self) -> CoreResult<Vec<CreatorVideoItem>> {
        self.scrape_rumble_page("https://rumble.com/live").await
    }

    pub async fn search_videos(&self, query: &str) -> CoreResult<Vec<CreatorVideoItem>> {
        let url = format!(
            "https://rumble.com/search/video?q={}",
            urlencoding::encode(query.trim())
        );
        if let Ok(items) = self.scrape_rumble_page(&url).await {
            if !items.is_empty() {
                return Ok(items);
            }
        }
        Ok(Vec::new())
    }

    async fn scrape_rumble_page(&self, url: &str) -> CoreResult<Vec<CreatorVideoItem>> {
        let html = self
            .http
            .get(url)
            .header("User-Agent", DESKTOP_UA)
            .send()
            .await?
            .text()
            .await?;
        Ok(parse_rumble_html_cards(&html))
    }

    pub async fn resolve_stream(&self, path_or_embed_id: &str) -> CoreResult<ResolvedMediaStream> {
        let embed_id = if path_or_embed_id.starts_with('v') && !path_or_embed_id.contains('/') {
            path_or_embed_id.to_string()
        } else {
            let full_page = if path_or_embed_id.starts_with("http") {
                path_or_embed_id.to_string()
            } else {
                format!("https://rumble.com/{}", path_or_embed_id.trim_start_matches('/'))
            };
            let oembed_url = format!(
                "https://wn0.rumble.com/api/Media/oembed.json?url={}",
                urlencoding::encode(&full_page)
            );
            let oembed: Value = self
                .http
                .get(&oembed_url)
                .header("User-Agent", DESKTOP_UA)
                .send()
                .await?
                .json()
                .await?;
            let html = oembed.get("html").and_then(|v| v.as_str()).unwrap_or("");
            static EMBED_RE: OnceLock<Regex> = OnceLock::new();
            let re = EMBED_RE.get_or_init(|| Regex::new(r#"/embed/(v[a-z0-9]+)"#).unwrap());
            re.captures(html)
                .and_then(|c| c.get(1))
                .map(|m| m.as_str().to_string())
                .ok_or_else(|| CoreError::NotFound("Rumble oembed missing embed ID".into()))?
        };

        let api_url =
            format!("https://wn0.rumble.com/embedJS/u3/?request=video&ver=2&v={embed_id}");
        let root: Value = self
            .http
            .get(&api_url)
            .header("User-Agent", DESKTOP_UA)
            .send()
            .await?
            .json()
            .await?;

        let title = root
            .get("title")
            .and_then(|v| v.as_str())
            .unwrap_or(&embed_id)
            .to_string();
        let author = root
            .pointer("/author/name")
            .and_then(|v| v.as_str())
            .unwrap_or("Rumble")
            .to_string();
        let duration_sec = root.get("duration").and_then(|v| v.as_u64()).unwrap_or(0);
        let is_live = root.get("live").and_then(|v| v.as_i64()).unwrap_or(0) > 0;

        let mut best_url = String::new();
        let mut best_height = 0u32;
        let mut protocol = "HLS";

        if let Some(ua) = root.get("ua") {
            if let Some(hls_auto) = ua.pointer("/hls/auto/url").and_then(|v| v.as_str()) {
                best_url = hls_auto.to_string();
                best_height = 1080;
            } else if let Some(mp4_obj) = ua.get("mp4").and_then(|v| v.as_object()) {
                for (res_key, entry) in mp4_obj {
                    let h: u32 = res_key.parse().unwrap_or(360);
                    if let Some(u) = entry.get("url").and_then(|v| v.as_str()) {
                        if h >= best_height {
                            best_height = h;
                            best_url = u.to_string();
                            protocol = "PROGRESSIVE";
                        }
                    }
                }
            }
        }
        if best_url.is_empty() {
            if let Some(u_hls) = root.pointer("/u/hls/url").and_then(|v| v.as_str()) {
                best_url = u_hls.to_string();
                protocol = "HLS";
            }
        }
        if best_url.is_empty() {
            return Err(CoreError::NotFound("Rumble embedJS returned no media URLs".into()));
        }

        Ok(ResolvedMediaStream {
            play_url: best_url,
            protocol: protocol.into(),
            title,
            author,
            duration_sec,
            is_live,
            quality_label: format!("Rumble {}p ({protocol})", best_height.max(720)),
            request_headers: HashMap::new(),
            subtitles: Vec::new(),
            skip_segments: Vec::new(),
            playback_tracking_url: String::new(),
            watchtime_tracking_url: String::new(),
        })
    }
}

fn parse_rumble_html_cards(html: &str) -> Vec<CreatorVideoItem> {
    static LINK_RE: OnceLock<Regex> = OnceLock::new();
    let re = LINK_RE.get_or_init(|| {
        Regex::new(r#"href="(/(v[a-z0-9]+-[^"?#]+\.html))"[^>]*>([^<]{3,160})<"#).unwrap()
    });
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for caps in re.captures_iter(html) {
        let rel_path = caps[1].trim().to_string();
        let title = caps[3].trim().to_string();
        if title.is_empty() || !seen.insert(rel_path.clone()) {
            continue;
        }
        out.push(CreatorVideoItem {
            platform: "rumble".into(),
            video_id: rel_path,
            title,
            channel_name: "Rumble".into(),
            ..Default::default()
        });
    }
    out
}
