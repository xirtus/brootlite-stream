use crate::config::ConfigStore;
use crate::error::{CoreError, CoreResult};
use crate::models::{
    now_epoch_ms, CreatorChannel, CreatorFeedPage, CreatorVideoItem, DeviceCodeSession,
    ExternalSubtitle, ResolvedMediaStream, ReturnYouTubeDislikeVotes, SkipSegment, SkipSegmentKind,
    YouTubeAccountProfile,
};
use crate::stremio::futures_join_all;
use crate::torrent::LocalMediaServer;
use boa_engine::{Context as JsContext, Source as JsSource};
use quick_xml::events::Event;
use quick_xml::Reader;
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, OnceLock};
use tokio::sync::Mutex as AsyncMutex;

const YT_OAUTH_SCOPE: &str =
    "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content";
const INNERTUBE_BASE: &str = "https://www.youtube.com/youtubei/v1";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DashRepresentationFormat {
    pub itag: u32,
    pub mime_type: String,
    pub codecs: String,
    pub base_url: String,
    pub bitrate: u64,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub fps: Option<u32>,
    pub audio_sample_rate: Option<u32>,
    pub init_range: (u64, u64),
    pub index_range: (u64, u64),
    pub content_length: Option<u64>,
}

struct InnerTubeClientSpec {
    name: &'static str,
    client_name: &'static str,
    client_version: &'static str,
    client_id_header: &'static str,
    user_agent: &'static str,
    extra_ctx: Option<Value>,
}

const STREAM_CLIENTS: &[InnerTubeClientSpec] = &[
    InnerTubeClientSpec {
        name: "ANDROID_VR",
        client_name: "ANDROID_VR",
        client_version: "1.61.48",
        client_id_header: "28",
        user_agent: "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        extra_ctx: None,
    },
    InnerTubeClientSpec {
        name: "TV_SIMPLY",
        client_name: "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        client_version: "2.0",
        client_id_header: "85",
        user_agent: "Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Safari/605.1.15",
        extra_ctx: None,
    },
    InnerTubeClientSpec {
        name: "IOS",
        client_name: "IOS",
        client_version: "20.10.4",
        client_id_header: "5",
        user_agent: "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extra_ctx: None,
    },
    InnerTubeClientSpec {
        name: "WEB_SAFARI",
        client_name: "WEB",
        client_version: "2.20250312.04.00",
        client_id_header: "1",
        user_agent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
        extra_ctx: None,
    },
];

pub struct YouTubeClient {
    http: reqwest::Client,
    config: Arc<ConfigStore>,
    media_server: Arc<LocalMediaServer>,
    refresh_mutex: AsyncMutex<()>,
    sig_cache: Mutex<HashMap<String, String>>,
}

impl YouTubeClient {
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
            sig_cache: Mutex::new(HashMap::new()),
        }
    }

    // ── 1. TV OAuth2 Device Code & Brand Accounts (`pageId`) ─────────────────

    pub async fn start_device_code_auth(&self) -> CoreResult<DeviceCodeSession> {
        let (cid, csec) = self
            .scrape_tv_client_credentials()
            .await
            .ok_or_else(|| CoreError::Auth("Unable to discover YouTube TV client credentials".into()))?;
        let resp = self.post_device_code(&cid).await?;
        if !resp.status().is_success() {
            return Err(CoreError::Auth(format!(
                "YouTube device/code failed ({})",
                resp.status()
            )));
        }
        let val: Value = resp.json().await?;
        Ok(DeviceCodeSession {
            device_code: val
                .get("device_code")
                .and_then(|v| v.as_str())
                .ok_or_else(|| CoreError::Protocol("missing device_code".into()))?
                .to_string(),
            user_code: val
                .get("user_code")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string(),
            verification_url: val
                .get("verification_url")
                .and_then(|v| v.as_str())
                .unwrap_or("https://www.youtube.com/activate")
                .to_string(),
            expires_in_sec: val.get("expires_in").and_then(|v| v.as_u64()).unwrap_or(1800),
            interval_sec: val.get("interval").and_then(|v| v.as_u64()).unwrap_or(5),
            client_id: cid,
            client_secret: csec,
        })
    }

    async fn post_device_code(&self, client_id: &str) -> CoreResult<reqwest::Response> {
        Ok(self
            .http
            .post("https://www.youtube.com/o/oauth2/device/code")
            .form(&[
                ("client_id", client_id),
                ("scope", YT_OAUTH_SCOPE),
                ("device_id", &self.config.installation_id()),
                ("device_model", "BrootLite Desktop"),
            ])
            .send()
            .await?)
    }

    async fn scrape_tv_client_credentials(&self) -> Option<(String, String)> {
        let tv_html = self
            .http
            .get("https://www.youtube.com/tv")
            .header(
                "User-Agent",
                "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36",
            )
            .send()
            .await
            .ok()?
            .text()
            .await
            .ok()?;
        let script_re = Regex::new(r#"src="(/[^"]*tv-player[^"]*\.js)""#).ok()?;
        let rel = script_re.captures(&tv_html)?.get(1)?.as_str();
        let js = self
            .http
            .get(format!("https://www.youtube.com{rel}"))
            .send()
            .await
            .ok()?
            .text()
            .await
            .ok()?;
        let cred_re = Regex::new(
            r#"clientId\s*:\s*"([0-9]+-[a-z0-9_]+\.apps\.googleusercontent\.com)"\s*,\s*[a-zA-Z0-9_$]+\s*:\s*"([A-Za-z0-9_-]{16,48})""#,
        )
        .ok()?;
        let caps = cred_re.captures(&js)?;
        Some((caps[1].to_string(), caps[2].to_string()))
    }

    /// Polls `https://www.youtube.com/o/oauth2/token`. Returns `Ok(Some(accounts))` once the user
    /// approves at `youtube.com/activate`, automatically discovering Primary + Brand Accounts!
    pub async fn poll_device_token(
        &self,
        session: &DeviceCodeSession,
    ) -> CoreResult<Option<Vec<YouTubeAccountProfile>>> {
        let (cid, csec) = if session.client_id.is_empty() || session.client_secret.is_empty() {
            self.scrape_tv_client_credentials()
                .await
                .ok_or_else(|| CoreError::Auth("Unable to discover YouTube TV client credentials".into()))?
        } else {
            (session.client_id.clone(), session.client_secret.clone())
        };

        let resp = self
            .http
            .post("https://www.youtube.com/o/oauth2/token")
            .form(&[
                ("client_id", cid.as_str()),
                ("client_secret", csec.as_str()),
                ("code", session.device_code.as_str()),
                ("grant_type", "http://oauth.net/grant_type/device/1.0"),
            ])
            .send()
            .await?;

        let val: Value = resp.json().await?;
        if let Some(err) = val.get("error").and_then(|v| v.as_str()) {
            match err {
                "authorization_pending" | "slow_down" => return Ok(None),
                other => return Err(CoreError::Auth(format!("YouTube OAuth error: {other}"))),
            }
        }

        let access_token = val
            .get("access_token")
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| CoreError::Auth("missing access_token".into()))?
            .to_string();
        let refresh_token = val
            .get("refresh_token")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let expires_in = val.get("expires_in").and_then(|v| v.as_u64()).unwrap_or(3600);
        let expires_at_ms = now_epoch_ms() + expires_in * 1000;

        let mut discovered = self
            .list_brand_accounts(&access_token, &refresh_token, expires_at_ms, &cid, &csec)
            .await
            .unwrap_or_default();

        if discovered.is_empty() {
            discovered.push(YouTubeAccountProfile {
                account_id: format!("yt-{}", &uuid::Uuid::new_v4().to_string()[..8]),
                account_name: "YouTube Account".into(),
                channel_handle: String::new(),
                avatar_url: String::new(),
                page_id: String::new(),
                refresh_token,
                access_token,
                expires_at_ms,
                oauth_client_id: cid.to_string(),
                oauth_client_secret: csec.to_string(),
                updated_at_ms: now_epoch_ms(),
            });
        }

        for acc in &discovered {
            self.config.upsert_youtube_account(acc.clone())?;
        }
        Ok(Some(discovered))
    }

    async fn list_brand_accounts(
        &self,
        access_token: &str,
        refresh_token: &str,
        expires_at_ms: u64,
        client_id: &str,
        client_secret: &str,
    ) -> CoreResult<Vec<YouTubeAccountProfile>> {
        let body = json!({
            "context": {
                "client": {
                    "clientName": "TVHTML5",
                    "clientVersion": "7.20250312.16.00",
                    "hl": "en",
                    "gl": "US"
                }
            }
        });
        let resp = self
            .http
            .post(format!("{INNERTUBE_BASE}/account/account_list?prettyPrint=false"))
            .bearer_auth(access_token)
            .json(&body)
            .send()
            .await?;
        let root: Value = resp.json().await?;

        let mut accounts = Vec::new();
        let mut stack = vec![&root];
        while let Some(node) = stack.pop() {
            match node {
                Value::Object(map) => {
                    if let Some(acc_item) = map.get("accountItem").and_then(|v| v.as_object()) {
                        let name = extract_yt_text(acc_item.get("accountName"));
                        let handle = extract_yt_text(acc_item.get("channelHandle"));
                        let avatar = extract_yt_thumb(acc_item.get("accountPhoto"));
                        let mut page_id = String::new();
                        let mut obfuscated_id = String::new();
                        if let Some(tokens) = acc_item
                            .get("serviceEndpoint")
                            .and_then(|s| s.get("signInEndpoint"))
                            .and_then(|s| s.get("directSigninIdentity"))
                        {
                            page_id = tokens
                                .get("pageId")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string();
                            obfuscated_id = tokens
                                .get("effectiveObfuscatedGaiaId")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .to_string();
                        }
                        let account_id = if !page_id.is_empty() {
                            format!("yt-brand-{page_id}")
                        } else if !obfuscated_id.is_empty() {
                            format!("yt-gaia-{obfuscated_id}")
                        } else if !handle.is_empty() {
                            format!("yt-handle-{handle}")
                        } else {
                            format!("yt-{}", accounts.len() + 1)
                        };
                        if !name.is_empty() {
                            accounts.push(YouTubeAccountProfile {
                                account_id,
                                account_name: name,
                                channel_handle: handle,
                                avatar_url: avatar,
                                page_id,
                                refresh_token: refresh_token.to_string(),
                                access_token: access_token.to_string(),
                                expires_at_ms,
                                oauth_client_id: client_id.to_string(),
                                oauth_client_secret: client_secret.to_string(),
                                updated_at_ms: now_epoch_ms(),
                            });
                        }
                    } else {
                        for v in map.values() {
                            stack.push(v);
                        }
                    }
                }
                Value::Array(arr) => {
                    for v in arr {
                        stack.push(v);
                    }
                }
                _ => {}
            }
        }
        Ok(accounts)
    }

    async fn valid_auth_context(&self) -> Option<(String, String)> {
        let acc = self.config.active_youtube_account()?;
        let now = now_epoch_ms();
        if !acc.access_token.is_empty() && acc.expires_at_ms > now + 60_000 {
            return Some((acc.access_token, acc.page_id));
        }
        if acc.refresh_token.is_empty() {
            return None;
        }
        let _guard = self.refresh_mutex.lock().await;
        // Re-check after lock
        let acc = self.config.active_youtube_account()?;
        if !acc.access_token.is_empty() && acc.expires_at_ms > now_epoch_ms() + 60_000 {
            return Some((acc.access_token, acc.page_id));
        }

        let (cid, csec) = if acc.oauth_client_id.is_empty() || acc.oauth_client_secret.is_empty() {
            self.scrape_tv_client_credentials().await?
        } else {
            (acc.oauth_client_id.clone(), acc.oauth_client_secret.clone())
        };

        let resp = self
            .http
            .post("https://www.youtube.com/o/oauth2/token")
            .form(&[
                ("client_id", cid.as_str()),
                ("client_secret", csec.as_str()),
                ("refresh_token", acc.refresh_token.as_str()),
                ("grant_type", "refresh_token"),
            ])
            .send()
            .await
            .ok()?;
        let val: Value = resp.json().await.ok()?;
        let new_access = val.get("access_token")?.as_str()?.to_string();
        let exp = val.get("expires_in").and_then(|v| v.as_u64()).unwrap_or(3600);
        let mut updated = acc.clone();
        updated.access_token = new_access.clone();
        updated.expires_at_ms = now_epoch_ms() + exp * 1000;
        let _ = self.config.upsert_youtube_account(updated);
        Some((new_access, acc.page_id))
    }

    // ── 2. InnerTube Feeds, Subscriptions (+ Atom RSS), Search, Channels ─────

    pub async fn get_home_feed(&self, continuation: Option<&str>) -> CoreResult<CreatorFeedPage> {
        self.browse_feed("FEwhat_to_watch", continuation, true).await
    }

    pub async fn get_subscriptions_feed(
        &self,
        continuation: Option<&str>,
    ) -> CoreResult<CreatorFeedPage> {
        if self.config.active_youtube_account().is_some() {
            return self.browse_feed("FEsubscriptions", continuation, true).await;
        }
        // Anonymous local follow fallback via YouTube Atom RSS!
        let follows = self.config.active_creator_follows(Some("youtube"));
        if follows.is_empty() || continuation.is_some() {
            return Ok(CreatorFeedPage::default());
        }
        let mut tasks = Vec::new();
        for f in follows {
            if f.channel_id.starts_with("UC") {
                let http = self.http.clone();
                let cid = f.channel_id.clone();
                tasks.push(async move { fetch_youtube_atom_rss(&http, &cid).await });
            }
        }
        let mut items = Vec::new();
        for batch in futures_join_all(tasks).await {
            items.extend(batch);
        }
        Ok(CreatorFeedPage {
            items,
            continuation_token: None,
        })
    }

    pub async fn get_history_feed(
        &self,
        continuation: Option<&str>,
    ) -> CoreResult<CreatorFeedPage> {
        self.browse_feed("FEhistory", continuation, true).await
    }

    pub async fn get_playlist_videos(
        &self,
        playlist_id: &str,
        continuation: Option<&str>,
    ) -> CoreResult<CreatorFeedPage> {
        let browse_id = if playlist_id.starts_with("VL") || playlist_id.starts_with("FE") {
            playlist_id.to_string()
        } else {
            format!("VL{playlist_id}")
        };
        self.browse_feed(&browse_id, continuation, true).await
    }

    pub async fn search_videos(
        &self,
        query: &str,
        continuation: Option<&str>,
    ) -> CoreResult<CreatorFeedPage> {
        let mut payload = self.web_context_json(None);
        if let Some(tok) = continuation.filter(|s| !s.is_empty()) {
            payload["continuation"] = Value::String(tok.to_string());
        } else {
            payload["query"] = Value::String(query.trim().to_string());
        }
        let root = self
            .post_innertube("search", &payload, false)
            .await?;
        Ok(parse_innertube_feed_page(&root))
    }

    pub async fn get_search_suggestions(&self, query: &str) -> CoreResult<Vec<String>> {
        let q = query.trim();
        if q.is_empty() {
            return Ok(Vec::new());
        }
        let url = format!(
            "https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q={}",
            urlencoding::encode(q)
        );
        let text = self.http.get(&url).send().await?.text().await?;
        let Some(start) = text.find('[') else {
            return Ok(Vec::new());
        };
        let Some(end) = text.rfind(']') else {
            return Ok(Vec::new());
        };
        let Ok(arr) = serde_json::from_str::<Value>(&text[start..=end]) else {
            return Ok(Vec::new());
        };
        Ok(arr
            .get(1)
            .and_then(|v| v.as_array())
            .map(|list| {
                list.iter()
                    .filter_map(|item| item.get(0).and_then(|s| s.as_str()).map(String::from))
                    .collect()
            })
            .unwrap_or_default())
    }

    pub async fn get_channel_feed(&self, channel_id: &str) -> CoreResult<CreatorChannel> {
        let mut payload = self.web_context_json(None);
        payload["browseId"] = Value::String(channel_id.to_string());
        // Standard Videos tab param (`EgZ2aWRlb3PyBgQKAjoA`)
        payload["params"] = Value::String("EgZ2aWRlb3PyBgQKAjoA".into());

        let root = self.post_innertube("browse", &payload, true).await?;
        let page = parse_innertube_feed_page(&root);
        let header = root.get("header");
        let meta = root
            .get("metadata")
            .and_then(|m| m.get("channelMetadataRenderer"));
        let name = meta
            .and_then(|m| m.get("title"))
            .and_then(|v| v.as_str())
            .map(String::from)
            .unwrap_or_else(|| extract_yt_text(header.and_then(|h| h.pointer("/c4TabbedHeaderRenderer/title"))));
        let description = meta
            .and_then(|m| m.get("description"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        let avatar_url = extract_yt_thumb(meta.and_then(|m| m.get("avatar")));

        Ok(CreatorChannel {
            platform: "youtube".into(),
            channel_id: channel_id.to_string(),
            name,
            handle: String::new(),
            avatar_url,
            banner_url: String::new(),
            subscriber_text: String::new(),
            description,
            is_subscribed: self
                .config
                .active_creator_follows(Some("youtube"))
                .iter()
                .any(|f| f.channel_id == channel_id),
            live_stream: page.items.iter().find(|i| i.is_live).cloned(),
            videos: page.items,
        })
    }

    pub async fn set_channel_subscription(
        &self,
        channel_id: &str,
        subscribe: bool,
    ) -> CoreResult<bool> {
        let Some((token, page_id)) = self.valid_auth_context().await else {
            return Err(CoreError::Auth(
                "Sign in to YouTube or use local Creator Follow".into(),
            ));
        };
        let mut payload = self.web_context_json(Some(&page_id));
        payload["channelIds"] = json!([channel_id]);
        let endpoint = if subscribe {
            "subscription/subscribe"
        } else {
            "subscription/unsubscribe"
        };
        let mut req = self
            .http
            .post(format!("{INNERTUBE_BASE}/{endpoint}?prettyPrint=false"))
            .bearer_auth(token);
        if !page_id.is_empty() {
            req = req.header("X-Goog-Pageid", page_id);
        }
        let resp = req.json(&payload).send().await?;
        Ok(resp.status().is_success())
    }

    pub async fn get_related_videos(&self, video_id: &str) -> CoreResult<Vec<CreatorVideoItem>> {
        let mut payload = self.web_context_json(None);
        payload["videoId"] = Value::String(video_id.to_string());
        let root = self.post_innertube("next", &payload, false).await?;
        Ok(parse_innertube_feed_page(&root).items)
    }

    async fn browse_feed(
        &self,
        browse_id: &str,
        continuation: Option<&str>,
        use_auth: bool,
    ) -> CoreResult<CreatorFeedPage> {
        let auth_ctx = if use_auth {
            self.valid_auth_context().await
        } else {
            None
        };
        let page_id = auth_ctx.as_ref().map(|(_, p)| p.as_str());
        let mut payload = if auth_ctx.is_some() {
            self.tv_context_json(page_id)
        } else {
            self.web_context_json(None)
        };
        if let Some(tok) = continuation.filter(|s| !s.is_empty()) {
            payload["continuation"] = Value::String(tok.to_string());
        } else {
            payload["browseId"] = Value::String(browse_id.to_string());
        }
        let root = self.post_innertube("browse", &payload, use_auth).await?;
        Ok(parse_innertube_feed_page(&root))
    }

    async fn post_innertube(
        &self,
        path: &str,
        payload: &Value,
        use_auth: bool,
    ) -> CoreResult<Value> {
        let url = format!("{INNERTUBE_BASE}/{path}?prettyPrint=false");
        let mut req = self.http.post(&url);
        if use_auth {
            if let Some((token, page_id)) = self.valid_auth_context().await {
                req = req.bearer_auth(token);
                if !page_id.is_empty() {
                    req = req.header("X-Goog-Pageid", page_id);
                }
            }
        }
        let resp = req.json(payload).send().await?;
        if !resp.status().is_success() {
            return Err(CoreError::Protocol(format!(
                "InnerTube {path} HTTP {}",
                resp.status()
            )));
        }
        Ok(resp.json().await?)
    }

    fn web_context_json(&self, page_id: Option<&str>) -> Value {
        let mut ctx = json!({
            "context": {
                "client": {
                    "clientName": "WEB",
                    "clientVersion": "2.20250312.04.00",
                    "hl": "en",
                    "gl": "US"
                }
            }
        });
        if let Some(pid) = page_id.filter(|s| !s.is_empty()) {
            ctx["context"]["user"] = json!({ "onBehalfOfUser": pid });
        }
        ctx
    }

    fn tv_context_json(&self, page_id: Option<&str>) -> Value {
        let mut ctx = json!({
            "context": {
                "client": {
                    "clientName": "TVHTML5",
                    "clientVersion": "7.20250312.16.00",
                    "hl": "en",
                    "gl": "US"
                }
            }
        });
        if let Some(pid) = page_id.filter(|s| !s.is_empty()) {
            ctx["context"]["user"] = json!({ "onBehalfOfUser": pid });
        }
        ctx
    }

    // ── 3. Multi-Client Ad-Free Stream Resolver + Synthetic 4K60 DASH `.mpd` ─

    pub async fn resolve_stream(&self, video_id: &str) -> CoreResult<ResolvedMediaStream> {
        let clean_id = video_id.trim();
        if clean_id.is_empty() {
            return Err(CoreError::InvalidInput("videoId is empty".into()));
        }

        let sponsor_fut = {
            let http = self.http.clone();
            let vid = clean_id.to_string();
            async move { fetch_sponsor_block_segments(&http, &vid).await }
        };
        let sponsor_handle = tokio::spawn(sponsor_fut);

        let mut player_js_code: Option<String> = None;

        for spec in STREAM_CLIENTS {
            let mut body = json!({
                "videoId": clean_id,
                "contentCheckOk": true,
                "racyCheckOk": true,
                "context": {
                    "client": {
                        "clientName": spec.client_name,
                        "clientVersion": spec.client_version,
                        "hl": "en",
                        "gl": "US"
                    }
                }
            });
            if let Some(extra) = &spec.extra_ctx {
                body["context"]["thirdParty"] = extra.clone();
            }

            let Ok(resp) = self
                .http
                .post(format!("{INNERTUBE_BASE}/player?prettyPrint=false"))
                .header("User-Agent", spec.user_agent)
                .header("X-YouTube-Client-Name", spec.client_id_header)
                .header("X-YouTube-Client-Version", spec.client_version)
                .json(&body)
                .send()
                .await
            else {
                continue;
            };
            let Ok(root) = resp.json::<Value>().await else {
                continue;
            };

            let status = root
                .pointer("/playabilityStatus/status")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if status != "OK" {
                continue;
            }

            let details = root.get("videoDetails");
            let title = details
                .and_then(|d| d.get("title"))
                .and_then(|v| v.as_str())
                .unwrap_or(clean_id)
                .to_string();
            let author = details
                .and_then(|d| d.get("author"))
                .and_then(|v| v.as_str())
                .unwrap_or("YouTube")
                .to_string();
            let duration_sec: u64 = details
                .and_then(|d| d.get("lengthSeconds"))
                .and_then(|v| v.as_str())
                .and_then(|s| s.parse().ok())
                .unwrap_or(0);
            let is_live = details
                .and_then(|d| d.get("isLiveContent"))
                .and_then(|v| v.as_bool())
                .unwrap_or(false);

            let streaming = root.get("streamingData");
            let subtitles = parse_yt_caption_tracks(&root);
            let playback_tracking_url = root
                .pointer("/playbackTracking/videostatsPlaybackUrl/baseUrl")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let watchtime_tracking_url = root
                .pointer("/playbackTracking/videostatsWatchtimeUrl/baseUrl")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();

            let mut headers = HashMap::new();
            headers.insert("User-Agent".into(), spec.user_agent.to_string());

            // Live HLS manifest
            if let Some(hls_url) = streaming
                .and_then(|s| s.get("hlsManifestUrl"))
                .and_then(|v| v.as_str())
                .filter(|u| !u.is_empty())
            {
                if is_live {
                    let skip_segments = sponsor_handle.await.unwrap_or_default();
                    return Ok(ResolvedMediaStream {
                        play_url: hls_url.to_string(),
                        protocol: "HLS".into(),
                        title,
                        author,
                        duration_sec,
                        is_live: true,
                        quality_label: "LIVE HLS".into(),
                        request_headers: headers,
                        subtitles,
                        skip_segments,
                        playback_tracking_url,
                        watchtime_tracking_url,
                    });
                }
            }

            // Parse adaptiveFormats -> build synthetic 1080p60 / 4K DASH `.mpd`!
            let mut dash_reps = Vec::new();
            let mut max_height = 0u32;
            let mut max_fps = 30u32;
            if let Some(adaptive) = streaming
                .and_then(|s| s.get("adaptiveFormats"))
                .and_then(|v| v.as_array())
            {
                for fmt in adaptive {
                    if let Some(rep) = self
                        .extract_dash_representation(fmt, clean_id, &mut player_js_code)
                        .await
                    {
                        if let Some(h) = rep.height {
                            max_height = max_height.max(h);
                        }
                        if let Some(f) = rep.fps {
                            max_fps = max_fps.max(f);
                        }
                        dash_reps.push(rep);
                    }
                }
            }

            let has_video = dash_reps.iter().any(|r| r.mime_type.starts_with("video/"));
            let has_audio = dash_reps.iter().any(|r| r.mime_type.starts_with("audio/"));
            if has_video && has_audio && duration_sec > 0 {
                let mpd_xml = build_dash_mpd_xml(duration_sec, &dash_reps);
                let play_url = self.media_server.register_dash_manifest(clean_id, mpd_xml);
                let skip_segments = sponsor_handle.await.unwrap_or_default();
                let quality_label = if max_fps > 30 {
                    format!("{max_height}p{max_fps} DASH ({})", spec.name)
                } else {
                    format!("{max_height}p DASH ({})", spec.name)
                };
                return Ok(ResolvedMediaStream {
                    play_url,
                    protocol: "DASH".into(),
                    title,
                    author,
                    duration_sec,
                    is_live: false,
                    quality_label,
                    request_headers: headers,
                    subtitles,
                    skip_segments,
                    playback_tracking_url,
                    watchtime_tracking_url,
                });
            }

            // Progressive MP4 fallback (`formats`)
            if let Some(prog) = streaming
                .and_then(|s| s.get("formats"))
                .and_then(|v| v.as_array())
            {
                let mut best_prog: Option<(u32, String)> = None;
                for fmt in prog {
                    if let Some(u) = self
                        .resolve_format_url(fmt, clean_id, &mut player_js_code)
                        .await
                    {
                        let h = fmt.get("height").and_then(|v| v.as_u64()).unwrap_or(360) as u32;
                        if best_prog.as_ref().map(|(bh, _)| h > *bh).unwrap_or(true) {
                            best_prog = Some((h, u));
                        }
                    }
                }
                if let Some((h, play_url)) = best_prog {
                    let skip_segments = sponsor_handle.await.unwrap_or_default();
                    return Ok(ResolvedMediaStream {
                        play_url,
                        protocol: "PROGRESSIVE".into(),
                        title,
                        author,
                        duration_sec,
                        is_live,
                        quality_label: format!("{h}p MP4 ({})", spec.name),
                        request_headers: headers,
                        subtitles,
                        skip_segments,
                        playback_tracking_url,
                        watchtime_tracking_url,
                    });
                }
            }
        }

        Err(CoreError::NotFound(format!(
            "Unable to resolve playable YouTube stream for {clean_id}"
        )))
    }

    async fn extract_dash_representation(
        &self,
        fmt: &Value,
        video_id: &str,
        player_js_cache: &mut Option<String>,
    ) -> Option<DashRepresentationFormat> {
        let init_start: u64 = fmt.pointer("/initRange/start")?.as_str()?.parse().ok()?;
        let init_end: u64 = fmt.pointer("/initRange/end")?.as_str()?.parse().ok()?;
        let idx_start: u64 = fmt.pointer("/indexRange/start")?.as_str()?.parse().ok()?;
        let idx_end: u64 = fmt.pointer("/indexRange/end")?.as_str()?.parse().ok()?;

        let raw_mime = fmt.get("mimeType")?.as_str()?;
        let (mime_part, rest) = raw_mime.split_once(';').unwrap_or((raw_mime, ""));
        let codecs = rest
            .split("codecs=\"")
            .nth(1)
            .and_then(|s| s.split('"').next())
            .unwrap_or("")
            .to_string();

        let base_url = self.resolve_format_url(fmt, video_id, player_js_cache).await?;
        let itag = fmt.get("itag").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
        let bitrate = fmt
            .get("averageBitrate")
            .or_else(|| fmt.get("bitrate"))
            .and_then(|v| v.as_u64())
            .unwrap_or(128_000);

        Some(DashRepresentationFormat {
            itag,
            mime_type: mime_part.trim().to_string(),
            codecs,
            base_url,
            bitrate,
            width: fmt.get("width").and_then(|v| v.as_u64()).map(|n| n as u32),
            height: fmt.get("height").and_then(|v| v.as_u64()).map(|n| n as u32),
            fps: fmt.get("fps").and_then(|v| v.as_u64()).map(|n| n as u32),
            audio_sample_rate: fmt
                .get("audioSampleRate")
                .and_then(|v| v.as_str())
                .and_then(|s| s.parse().ok()),
            init_range: (init_start, init_end),
            index_range: (idx_start, idx_end),
            content_length: fmt
                .get("contentLength")
                .and_then(|v| v.as_str())
                .and_then(|s| s.parse().ok()),
        })
    }

    async fn resolve_format_url(
        &self,
        fmt: &Value,
        video_id: &str,
        player_js_cache: &mut Option<String>,
    ) -> Option<String> {
        let mut resolved = if let Some(u) = fmt.get("url").and_then(|v| v.as_str()) {
            u.to_string()
        } else if let Some(cipher) = fmt
            .get("signatureCipher")
            .or_else(|| fmt.get("cipher"))
            .and_then(|v| v.as_str())
        {
            let params: HashMap<String, String> = url::form_urlencoded::parse(cipher.as_bytes())
                .into_owned()
                .collect();
            let base = params.get("url")?.clone();
            let enc_s = params.get("s")?;
            let sp = params.get("sp").map(|s| s.as_str()).unwrap_or("sig");

            let js = self.ensure_player_js(video_id, player_js_cache).await?;
            let deciphered = self.decipher_sig_cached(&js, enc_s)?;
            let sep = if base.contains('?') { "&" } else { "?" };
            format!("{base}{sep}{sp}={}", urlencoding::encode(&deciphered))
        } else {
            return None;
        };

        // Transform `&n=` throttling parameter if player JS is loaded
        if let Ok(mut parsed) = url::Url::parse(&resolved) {
            let n_val = parsed
                .query_pairs()
                .find(|(k, _)| k == "n")
                .map(|(_, v)| v.into_owned());
            if let Some(raw_n) = n_val {
                if let Some(js) = player_js_cache.as_ref() {
                    if let Some(new_n) = self.transform_nsig_cached(js, &raw_n) {
                        let pairs: Vec<(String, String)> = parsed
                            .query_pairs()
                            .map(|(k, v)| {
                                if k == "n" {
                                    (k.into_owned(), new_n.clone())
                                } else {
                                    (k.into_owned(), v.into_owned())
                                }
                            })
                            .collect();
                        parsed.query_pairs_mut().clear().extend_pairs(pairs);
                        resolved = parsed.to_string();
                    }
                }
            }
        }
        Some(resolved)
    }

    async fn ensure_player_js(&self, video_id: &str, slot: &mut Option<String>) -> Option<String> {
        if let Some(existing) = slot {
            return Some(existing.clone());
        }
        let embed_html = self
            .http
            .get(format!("https://www.youtube.com/embed/{video_id}"))
            .send()
            .await
            .ok()?
            .text()
            .await
            .ok()?;
        let re = Regex::new(r#"(/s/player/[a-zA-Z0-9_-]+/[^"]*base\.js)"#).ok()?;
        let rel_path = re.captures(&embed_html)?.get(1)?.as_str();
        let hash_key = rel_path.replace('/', "_");
        let cache_path = self.config.cache_dir().join(format!("yt_{hash_key}"));

        if let Ok(cached) = tokio::fs::read_to_string(&cache_path).await {
            if cached.len() > 10_000 {
                *slot = Some(cached.clone());
                return Some(cached);
            }
        }

        let js = self
            .http
            .get(format!("https://www.youtube.com{rel_path}"))
            .send()
            .await
            .ok()?
            .text()
            .await
            .ok()?;
        let _ = tokio::fs::write(&cache_path, &js).await;
        *slot = Some(js.clone());
        Some(js)
    }

    fn decipher_sig_cached(&self, player_js: &str, enc_sig: &str) -> Option<String> {
        let cache_key = format!("s:{enc_sig}");
        if let Some(hit) = self.sig_cache.lock().unwrap().get(&cache_key).cloned() {
            return Some(hit);
        }
        let out = eval_youtube_sig_decipher(player_js, enc_sig)?;
        let mut map = self.sig_cache.lock().unwrap();
        if map.len() > 256 {
            map.clear();
        }
        map.insert(cache_key, out.clone());
        Some(out)
    }

    fn transform_nsig_cached(&self, player_js: &str, raw_n: &str) -> Option<String> {
        let cache_key = format!("n:{raw_n}");
        if let Some(hit) = self.sig_cache.lock().unwrap().get(&cache_key).cloned() {
            return Some(hit);
        }
        let out = eval_youtube_nsig_transform(player_js, raw_n)?;
        self.sig_cache.lock().unwrap().insert(cache_key, out.clone());
        Some(out)
    }

    // ── 4. SponsorBlock, ReturnYouTubeDislike, DeArrow, & 2-Way WatchTime ───

    pub async fn fetch_return_youtube_dislike(
        &self,
        video_id: &str,
    ) -> CoreResult<ReturnYouTubeDislikeVotes> {
        let url = format!(
            "https://returnyoutubedislikeapi.com/votes?videoId={}",
            urlencoding::encode(video_id)
        );
        let val: Value = self.http.get(&url).send().await?.json().await?;
        Ok(ReturnYouTubeDislikeVotes {
            likes: val.get("likes").and_then(|v| v.as_u64()).unwrap_or(0),
            dislikes: val.get("dislikes").and_then(|v| v.as_u64()).unwrap_or(0),
            view_count: val.get("viewCount").and_then(|v| v.as_u64()).unwrap_or(0),
            rating: val.get("rating").and_then(|v| v.as_f64()).unwrap_or(0.0),
        })
    }

    pub async fn fetch_dearrow_title(&self, video_id: &str) -> CoreResult<Option<String>> {
        let url = format!(
            "https://dearrow-thumb.ajay.app/api/v1/branding?videoID={}",
            urlencoding::encode(video_id)
        );
        let resp = self.http.get(&url).send().await?;
        if !resp.status().is_success() {
            return Ok(None);
        }
        let val: Value = resp.json().await?;
        Ok(val
            .get("titles")
            .and_then(|v| v.as_array())
            .and_then(|a| a.first())
            .and_then(|t| t.get("title"))
            .and_then(|v| v.as_str())
            .filter(|s| !s.is_empty())
            .map(|s| s.replace('>', "")))
    }

    pub async fn report_watch_time(
        &self,
        playback_tracking_url: &str,
        watchtime_tracking_url: &str,
        position_sec: f64,
        duration_sec: f64,
    ) -> CoreResult<bool> {
        let Some((token, page_id)) = self.valid_auth_context().await else {
            return Ok(false);
        };
        let target_base = if position_sec <= 2.0 && !playback_tracking_url.is_empty() {
            playback_tracking_url
        } else if !watchtime_tracking_url.is_empty() {
            watchtime_tracking_url
        } else {
            return Ok(false);
        };

        let sep = if target_base.contains('?') { "&" } else { "?" };
        let ping_url = format!(
            "{target_base}{sep}ver=2&cmt={position_sec:.2}&st={:.2}&et={position_sec:.2}&len={duration_sec:.2}",
            (position_sec - 10.0).max(0.0)
        );
        let mut req = self.http.get(&ping_url).bearer_auth(token);
        if !page_id.is_empty() {
            req = req.header("X-Goog-Pageid", page_id);
        }
        let resp = req.send().await?;
        Ok(resp.status().is_success())
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-Rust Synthetic 1080p60 / 4K MPEG-DASH `.mpd` XML Generator
// ─────────────────────────────────────────────────────────────────────────────

pub fn build_dash_mpd_xml(duration_sec: u64, reps: &[DashRepresentationFormat]) -> String {
    let mut groups: HashMap<String, Vec<&DashRepresentationFormat>> = HashMap::new();
    for r in reps {
        groups.entry(r.mime_type.clone()).or_default().push(r);
    }
    let mut mime_keys: Vec<String> = groups.keys().cloned().collect();
    mime_keys.sort();

    let mut xml = String::with_capacity(4096);
    xml.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.push_str(&format!(
        "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" minBufferTime=\"PT1.5S\" mediaPresentationDuration=\"PT{}S\">\n",
        duration_sec.max(1)
    ));
    xml.push_str("  <Period start=\"PT0S\">\n");

    for (as_id, mime) in mime_keys.iter().enumerate() {
        let list = &groups[mime];
        let is_video = mime.starts_with("video/");
        let content_type = if is_video { "video" } else { "audio" };
        xml.push_str(&format!(
            "    <AdaptationSet id=\"{as_id}\" mimeType=\"{}\" contentType=\"{content_type}\" subsegmentAlignment=\"true\">\n",
            escape_xml_attr(mime)
        ));

        for rep in list {
            let mut attrs = format!(
                "id=\"{}\" codecs=\"{}\" bandwidth=\"{}\"",
                rep.itag,
                escape_xml_attr(&rep.codecs),
                rep.bitrate
            );
            if let (Some(w), Some(h)) = (rep.width, rep.height) {
                attrs.push_str(&format!(" width=\"{w}\" height=\"{h}\""));
            }
            if let Some(fps) = rep.fps {
                attrs.push_str(&format!(" frameRate=\"{fps}\""));
            }
            if let Some(sr) = rep.audio_sample_rate {
                attrs.push_str(&format!(" audioSamplingRate=\"{sr}\""));
            }

            xml.push_str(&format!("      <Representation {attrs}>\n"));
            if !is_video {
                xml.push_str(
                    "        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\"/>\n",
                );
            }
            xml.push_str(&format!(
                "        <BaseURL>{}</BaseURL>\n",
                escape_xml_attr(&rep.base_url)
            ));
            xml.push_str(&format!(
                "        <SegmentBase indexRange=\"{}-{}\">\n",
                rep.index_range.0, rep.index_range.1
            ));
            xml.push_str(&format!(
                "          <Initialization range=\"{}-{}\"/>\n",
                rep.init_range.0, rep.init_range.1
            ));
            xml.push_str("        </SegmentBase>\n");
            xml.push_str("      </Representation>\n");
        }
        xml.push_str("    </AdaptationSet>\n");
    }

    xml.push_str("  </Period>\n</MPD>\n");
    xml
}

fn escape_xml_attr(raw: &str) -> String {
    raw.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure-Rust `boa_engine` Disposable JS Decipher Evaluator (`0 MB` Idle RAM)
// ─────────────────────────────────────────────────────────────────────────────

pub fn eval_youtube_sig_decipher(player_js: &str, encrypted_sig: &str) -> Option<String> {
    static FN_RE: OnceLock<Vec<Regex>> = OnceLock::new();
    let patterns = FN_RE.get_or_init(|| {
        vec![
            Regex::new(r#"\b[cs]\s*&&\s*[adf]\.set\([^,]+\s*,\s*encodeURIComponent\s*\(\s*([a-zA-Z0-9$]+)\("#).unwrap(),
            Regex::new(r#"([a-zA-Z0-9$]+)\s*=\s*function\(\s*a\s*\)\s*\{\s*a\s*=\s*a\.split\(\s*""\s*\);\s*([a-zA-Z0-9$]{2,})\."#).unwrap(),
        ]
    });

    for re in patterns {
        if let Some(caps) = re.captures(player_js) {
            let fn_name = &caps[1];
            let body_re = Regex::new(&format!(
                r#"(?:function\s+{}\s*\(\s*a\s*\)|{}\s*=\s*function\s*\(\s*a\s*\))\s*\{{([^}}]+)\}}"#,
                regex::escape(fn_name),
                regex::escape(fn_name)
            ))
            .ok()?;
            let Some(fn_caps) = body_re.captures(player_js) else {
                continue;
            };
            let fn_body = &fn_caps[1];

            let helper_re =
                Regex::new(r#"([a-zA-Z0-9$]+)\.[a-zA-Z0-9$]+\(\s*a\s*,\s*\d+\s*\)"#).ok()?;
            let helper_name = helper_re
                .captures(fn_body)
                .map(|c| c[1].to_string())
                .unwrap_or_default();

            let mut helper_decl = String::new();
            if !helper_name.is_empty() {
                let start_re = Regex::new(&format!(
                    r#"(?:var|let|const)\s+{}\s*=\s*\{{|\b{}\s*=\s*\{{"#,
                    regex::escape(&helper_name),
                    regex::escape(&helper_name)
                ))
                .ok()?;
                if let Some(m) = start_re.find(player_js) {
                    let slice = &player_js[m.start()..];
                    let mut depth = 0i32;
                    let mut seen_open = false;
                    let mut end_idx = None;
                    for (i, ch) in slice.char_indices() {
                        if ch == '{' {
                            depth += 1;
                            seen_open = true;
                        } else if ch == '}' {
                            depth -= 1;
                            if seen_open && depth == 0 {
                                end_idx = Some(i + 1);
                                break;
                            }
                        }
                    }
                    if let Some(end) = end_idx {
                        let raw_decl = slice[..end].trim_start();
                        if raw_decl.starts_with("var ")
                            || raw_decl.starts_with("let ")
                            || raw_decl.starts_with("const ")
                        {
                            helper_decl = format!("{raw_decl};");
                        } else {
                            helper_decl = format!("var {raw_decl};");
                        }
                    }
                }
            }

            let safe_arg = serde_json::to_string(encrypted_sig).ok()?;
            let snippet = format!(
                "{helper_decl}\nvar __dec = function(a) {{ {fn_body} }};\n__dec({safe_arg});"
            );
            let mut ctx = JsContext::default();
            if let Ok(val) = ctx.eval(JsSource::from_bytes(snippet.as_bytes())) {
                if let Ok(s) = val.to_string(&mut ctx) {
                    return Some(s.to_std_string_escaped());
                }
            }
        }
    }
    None
}

pub fn eval_youtube_nsig_transform(player_js: &str, raw_n: &str) -> Option<String> {
    let re = Regex::new(
        r#"\.get\(\s*"n"\s*\)\s*\)\s*&&\s*\(\s*b\s*=\s*([a-zA-Z0-9$]+)(?:\[(\d+)\])?\(\s*b\s*\)"#,
    )
    .ok()?;
    let caps = re.captures(player_js)?;
    let var_name = &caps[1];
    let fn_name = if let Some(idx_m) = caps.get(2) {
        let idx: usize = idx_m.as_str().parse().ok()?;
        let arr_re = Regex::new(&format!(
            r#"var\s+{}\s*=\s*\[([^\]]+)\]"#,
            regex::escape(var_name)
        ))
        .ok()?;
        let items = arr_re.captures(player_js)?.get(1)?.as_str();
        items.split(',').nth(idx)?.trim().to_string()
    } else {
        var_name.to_string()
    };

    let fn_hdr_re = Regex::new(&format!(
        r#"{}\s*=\s*function\s*\(\s*a\s*\)"#,
        regex::escape(&fn_name)
    ))
    .ok()?;
    let fn_start = fn_hdr_re.find(player_js)?.start();
    let slice = &player_js[fn_start..];
    let mut depth = 0i32;
    let mut end_idx = None;
    let mut seen_open = false;
    for (i, ch) in slice.char_indices() {
        if ch == '{' {
            depth += 1;
            seen_open = true;
        } else if ch == '}' {
            depth -= 1;
            if seen_open && depth == 0 {
                end_idx = Some(i + 1);
                break;
            }
        }
    }
    let fn_expr = &slice[..end_idx?];
    let safe_arg = serde_json::to_string(raw_n).ok()?;
    let script = format!("var {fn_expr}; {fn_name}({safe_arg});");
    let mut ctx = JsContext::default();
    let res = ctx.eval(JsSource::from_bytes(script.as_bytes())).ok()?;
    Some(res.to_string(&mut ctx).ok()?.to_std_string_escaped())
}

// ─────────────────────────────────────────────────────────────────────────────
// SponsorBlock, InnerTube Recursive Parser, & Anonymous Atom RSS
// ─────────────────────────────────────────────────────────────────────────────

async fn fetch_sponsor_block_segments(http: &reqwest::Client, video_id: &str) -> Vec<SkipSegment> {
    let url = format!(
        "https://sponsor.ajay.app/api/skipSegments?videoID={}&categories=[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\",\"preview\",\"filler\"]",
        urlencoding::encode(video_id)
    );
    let Ok(resp) = http.get(&url).send().await else {
        return Vec::new();
    };
    if !resp.status().is_success() {
        return Vec::new();
    }
    let Ok(arr) = resp.json::<Value>().await else {
        return Vec::new();
    };
    let mut out = Vec::new();
    if let Some(list) = arr.as_array() {
        for item in list {
            let Some(seg) = item.get("segment").and_then(|v| v.as_array()) else {
                continue;
            };
            if seg.len() < 2 {
                continue;
            }
            let s = seg[0].as_f64().unwrap_or(0.0);
            let e = seg[1].as_f64().unwrap_or(0.0);
            if e <= s {
                continue;
            }
            let cat = item
                .get("category")
                .and_then(|v| v.as_str())
                .unwrap_or("sponsor");
            let (kind, label) = match cat {
                "intro" => (SkipSegmentKind::Intro, "Skip Intro"),
                "outro" => (SkipSegmentKind::Outro, "Skip Outro"),
                "selfpromo" => (SkipSegmentKind::SelfPromo, "Skip Self-Promo"),
                "interaction" => (SkipSegmentKind::Interaction, "Skip Reminder"),
                "preview" => (SkipSegmentKind::Preview, "Skip Preview"),
                "filler" => (SkipSegmentKind::Filler, "Skip Filler"),
                _ => (SkipSegmentKind::Sponsor, "Skip Sponsor"),
            };
            out.push(SkipSegment {
                start_ms: (s * 1000.0) as u64,
                end_ms: (e * 1000.0) as u64,
                kind,
                label: label.to_string(),
            });
        }
    }
    out
}

fn parse_yt_caption_tracks(player_root: &Value) -> Vec<ExternalSubtitle> {
    let mut subs = Vec::new();
    if let Some(tracks) = player_root
        .pointer("/captions/playerCaptionsTracklistRenderer/captionTracks")
        .and_then(|v| v.as_array())
    {
        for (i, t) in tracks.iter().enumerate() {
            let base = t.get("baseUrl").and_then(|v| v.as_str()).unwrap_or("");
            if base.is_empty() {
                continue;
            }
            let lang = t
                .get("languageCode")
                .and_then(|v| v.as_str())
                .unwrap_or("en")
                .to_string();
            let vtt_url = if base.contains("fmt=") {
                base.to_string()
            } else {
                format!("{base}&fmt=vtt")
            };
            subs.push(ExternalSubtitle {
                id: format!("ytsub:{i}:{lang}"),
                url: vtt_url,
                lang,
                addon_name: "YouTube Captions".into(),
            });
        }
    }
    subs
}

pub fn parse_innertube_feed_page(root: &Value) -> CreatorFeedPage {
    let mut items = Vec::new();
    let mut seen = HashSet::new();
    let mut continuation_token: Option<String> = None;

    walk_innertube_json(root, &mut items, &mut seen, &mut continuation_token);
    CreatorFeedPage {
        items,
        continuation_token,
    }
}

fn walk_innertube_json(
    node: &Value,
    items: &mut Vec<CreatorVideoItem>,
    seen: &mut HashSet<String>,
    continuation: &mut Option<String>,
) {
    match node {
        Value::Object(map) => {
            for renderer_key in [
                "videoRenderer",
                "compactVideoRenderer",
                "gridVideoRenderer",
                "playlistVideoRenderer",
                "tileRenderer",
            ] {
                if let Some(r) = map.get(renderer_key) {
                    if let Some(item) = parse_video_renderer(r) {
                        if seen.insert(item.video_id.clone()) {
                            items.push(item);
                        }
                    }
                }
            }
            if continuation.is_none() {
                if let Some(tok) = node
                    .pointer("/continuationItemRenderer/continuationEndpoint/continuationCommand/token")
                    .or_else(|| node.pointer("/nextContinuationData/continuation"))
                    .and_then(|v| v.as_str())
                    .filter(|s| !s.is_empty())
                {
                    *continuation = Some(tok.to_string());
                }
            }
            for v in map.values() {
                walk_innertube_json(v, items, seen, continuation);
            }
        }
        Value::Array(arr) => {
            for v in arr {
                walk_innertube_json(v, items, seen, continuation);
            }
        }
        _ => {}
    }
}

fn parse_video_renderer(r: &Value) -> Option<CreatorVideoItem> {
    let video_id = r
        .get("videoId")
        .or_else(|| r.pointer("/onSelectCommand/watchEndpoint/videoId"))
        .and_then(|v| v.as_str())?
        .trim()
        .to_string();
    if video_id.is_empty() {
        return None;
    }

    let mut title = extract_yt_text(r.get("title"));
    if title.is_empty() {
        title = extract_yt_text(r.pointer("/metadata/tileMetadataRenderer/title"));
    }
    if title.is_empty() {
        return None;
    }

    let mut channel_name = extract_yt_text(r.get("ownerText"));
    if channel_name.is_empty() {
        channel_name = extract_yt_text(r.get("longBylineText"));
    }
    if channel_name.is_empty() {
        channel_name = extract_yt_text(r.get("shortBylineText"));
    }

    let channel_id = r
        .pointer("/ownerText/runs/0/navigationEndpoint/browseEndpoint/browseId")
        .or_else(|| r.pointer("/longBylineText/runs/0/navigationEndpoint/browseEndpoint/browseId"))
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    let duration_text = extract_yt_text(r.get("lengthText"));
    let duration_sec = parse_colon_duration_sec(&duration_text);
    let view_count_text = extract_yt_text(r.get("shortViewCountText"));
    let published_text = extract_yt_text(r.get("publishedTimeText"));
    let mut thumb = extract_yt_thumb(r.get("thumbnail"));
    if thumb.is_empty() {
        thumb = format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg");
    }
    let is_live = duration_text.is_empty()
        && r.get("badges")
            .map(|b| b.to_string().contains("LIVE"))
            .unwrap_or(false);

    Some(CreatorVideoItem {
        platform: "youtube".into(),
        video_id,
        title,
        channel_name,
        channel_id,
        channel_avatar: String::new(),
        thumbnail_url: thumb,
        duration_sec,
        duration_text,
        view_count_text,
        published_text,
        is_live,
        is_short: false,
        resume_progress_pct: 0,
        category_name: String::new(),
        direct_url_hint: String::new(),
    })
}

fn extract_yt_text(v: Option<&Value>) -> String {
    let Some(val) = v else {
        return String::new();
    };
    if let Some(s) = val.get("simpleText").and_then(|x| x.as_str()) {
        return s.to_string();
    }
    if let Some(runs) = val.get("runs").and_then(|x| x.as_array()) {
        return runs
            .iter()
            .filter_map(|r| r.get("text").and_then(|t| t.as_str()))
            .collect::<Vec<_>>()
            .join("");
    }
    String::new()
}

fn extract_yt_thumb(v: Option<&Value>) -> String {
    v.and_then(|x| x.get("thumbnails").or(Some(x)))
        .and_then(|t| t.as_array())
        .and_then(|a| a.last())
        .and_then(|item| item.get("url"))
        .and_then(|u| u.as_str())
        .map(|u| {
            if u.starts_with("//") {
                format!("https:{u}")
            } else {
                u.to_string()
            }
        })
        .unwrap_or_default()
}

fn parse_colon_duration_sec(txt: &str) -> i64 {
    let parts: Vec<i64> = txt
        .trim()
        .split(':')
        .filter_map(|p| p.parse::<i64>().ok())
        .collect();
    match parts.as_slice() {
        [m, s] => m * 60 + s,
        [h, m, s] => h * 3600 + m * 60 + s,
        _ => 0,
    }
}

async fn fetch_youtube_atom_rss(http: &reqwest::Client, channel_id: &str) -> Vec<CreatorVideoItem> {
    let url = format!(
        "https://www.youtube.com/feeds/videos.xml?channel_id={}",
        urlencoding::encode(channel_id)
    );
    let Ok(resp) = http.get(&url).send().await else {
        return Vec::new();
    };
    let Ok(xml) = resp.text().await else {
        return Vec::new();
    };

    let mut reader = Reader::from_str(&xml);
    reader.config_mut().trim_text(true);
    let mut items = Vec::new();
    let mut in_entry = false;
    let mut tag = String::new();
    let mut vid = String::new();
    let mut title = String::new();
    let mut author = String::new();
    let mut published = String::new();
    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(e)) => {
                let n = String::from_utf8_lossy(e.name().as_ref()).to_string();
                if n == "entry" {
                    in_entry = true;
                    vid.clear();
                    title.clear();
                    author.clear();
                    published.clear();
                } else if in_entry {
                    tag = n;
                }
            }
            Ok(Event::Text(t)) if in_entry => {
                let txt = t.unescape().map(|c| c.into_owned()).unwrap_or_default();
                match tag.as_str() {
                    "yt:videoId" => vid.push_str(&txt),
                    "title" if title.is_empty() => title.push_str(&txt),
                    "name" if author.is_empty() => author.push_str(&txt),
                    "published" if published.is_empty() => published.push_str(&txt),
                    _ => {}
                }
            }
            Ok(Event::End(e)) => {
                if e.name().as_ref() == b"entry" {
                    in_entry = false;
                    if !vid.is_empty() {
                        items.push(CreatorVideoItem {
                            platform: "youtube".into(),
                            thumbnail_url: format!("https://i.ytimg.com/vi/{vid}/hqdefault.jpg"),
                            video_id: vid.clone(),
                            title: title.clone(),
                            channel_name: author.clone(),
                            channel_id: channel_id.to_string(),
                            published_text: published.chars().take(10).collect(),
                            ..Default::default()
                        });
                    }
                }
                tag.clear();
            }
            Ok(Event::Eof) | Err(_) => break,
            _ => {}
        }
        buf.clear();
    }
    items
}
