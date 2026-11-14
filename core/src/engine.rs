use crate::binge::{RecommendationEngine, StreamSimilarityMatcher};
use crate::config::ConfigStore;
use crate::creators::{OdyseeClient, RumbleClient, TwitchClient};
use crate::error::{CoreError, CoreResult};
use crate::jellyfin::{JellyfinClient, JellyfinIds};
use crate::kodi::{parse_kodi_stream_url, KodiRunner};
use crate::models::{
    CreatorFollowEntry, DeviceCodeSession, DiscoverSortMode, FavoriteEntry, MetaItem,
    StreamCandidate, StreamSelectionContext, SwarmFilter, TorrentEngineConfig, VideoEpisode,
    WatchHistoryEntry,
};
use crate::multipass::MultipassClient;
use crate::stremio::StremioClient;
use crate::swarm::SwarmSearchClient;
use crate::torrent::LocalMediaServer;
use crate::youtube::YouTubeClient;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

/// High-level shared BrootLite Core Engine (`rlib` for Rust `desktop/` and `ffi` backing for Swift 6 `macos/`).
pub struct BrootLiteEngine {
    pub http: reqwest::Client,
    pub config: Arc<ConfigStore>,
    pub media_server: Arc<LocalMediaServer>,
    pub multipass: Arc<MultipassClient>,
    pub stremio: Arc<StremioClient>,
    pub jellyfin: Arc<JellyfinClient>,
    pub youtube: Arc<YouTubeClient>,
    pub twitch: Arc<TwitchClient>,
    pub odysee: Arc<OdyseeClient>,
    pub rumble: Arc<RumbleClient>,
    pub swarm: Arc<SwarmSearchClient>,
    pub kodi: Arc<KodiRunner>,
    pub recommendations: Arc<RecommendationEngine>,
}

impl BrootLiteEngine {
    pub async fn new(explicit_data_dir: Option<PathBuf>) -> CoreResult<Arc<Self>> {
        let config = Arc::new(ConfigStore::open(explicit_data_dir)?);
        let http = reqwest::Client::builder()
            .user_agent(format!("BrootLite/{} (Desktop)", env!("CARGO_PKG_VERSION")))
            .timeout(Duration::from_secs(18))
            .connect_timeout(Duration::from_secs(8))
            .build()?;

        let media_server = LocalMediaServer::start(http.clone(), config.clone()).await?;
        let multipass = Arc::new(MultipassClient::new(http.clone(), config.clone()));
        let stremio = Arc::new(StremioClient::new(http.clone(), config.clone()));
        let jellyfin = Arc::new(JellyfinClient::new(http.clone(), config.clone()));
        let youtube = Arc::new(YouTubeClient::new(
            http.clone(),
            config.clone(),
            media_server.clone(),
        ));
        let twitch = Arc::new(TwitchClient::new(
            http.clone(),
            config.clone(),
            media_server.clone(),
        ));
        let odysee = Arc::new(OdyseeClient::new(http.clone()));
        let rumble = Arc::new(RumbleClient::new(http.clone()));
        let swarm = Arc::new(SwarmSearchClient::new(http.clone(), config.clone()));
        let kodi = Arc::new(KodiRunner::new(config.clone()));
        let recommendations = Arc::new(RecommendationEngine::new(
            config.clone(),
            stremio.clone(),
            jellyfin.clone(),
            youtube.clone(),
        ));

        Ok(Arc::new(Self {
            http,
            config,
            media_server,
            multipass,
            stremio,
            jellyfin,
            youtube,
            twitch,
            odysee,
            rumble,
            swarm,
            kodi,
            recommendations,
        }))
    }

    pub async fn shutdown(&self) {
        let _ = self.media_server.stop_all_torrent_streams().await;
        self.media_server.shutdown();
    }

    /// Executes any engine method via uniform JSON-RPC envelope:
    /// `{"ok": true, "result": ...}` or `{"ok": false, "error": "..."}`.
    pub async fn call_json_envelope(&self, method: &str, params: Value) -> Value {
        match self.dispatch_inner(method, params).await {
            Ok(res) => json!({ "ok": true, "result": res }),
            Err(err) => json!({ "ok": false, "error": err.to_string() }),
        }
    }

    async fn dispatch_inner(&self, method: &str, p: Value) -> CoreResult<Value> {
        match method {
            // ── Engine Status & Local Config ──────────────────────────────────
            "status" | "engine.status" => {
                let (stremio_key, stremio_email, _, _) = self.config.stremio_auth();
                let mut mp = self.config.multipass_profile();
                // Never expose raw `mpd_...` token over general UI status snapshots!
                let mp_paired = mp.is_paired();
                mp.pair_token = String::new();
                Ok(json!({
                    "version": env!("CARGO_PKG_VERSION"),
                    "dataDir": self.config.data_dir().to_string_lossy(),
                    "mediaServerBaseUrl": self.media_server.base_url(),
                    "mediaServerPort": self.media_server.port(),
                    "stremioLinked": stremio_key.is_some(),
                    "stremioEmail": stremio_email,
                    "multipassPaired": mp_paired,
                    "multipassProfile": mp,
                    "jellyfinCount": self.config.jellyfin_servers().len(),
                    "youtubeCount": self.config.youtube_accounts().len(),
                    "twitchLoggedIn": self.config.twitch_profile().is_some(),
                    "swarmFilter": self.config.swarm_filter(),
                    "discoverSortMode": self.config.discover_sort_mode(),
                    "torrentConfig": self.config.torrent_config(),
                }))
            }
            "config.get_addons" => Ok(serde_json::to_value(self.config.addons())?),
            "config.remove_addon" => {
                let id = req_str(&p, "addonId")?;
                self.config.remove_addon(&id)?;
                Ok(json!({ "removed": true }))
            }
            "config.get_watch_history" => Ok(serde_json::to_value(self.config.watch_history())?),
            "config.upsert_watch_history" => {
                let entry: WatchHistoryEntry = serde_json::from_value(p)?;
                self.config.upsert_watch_history(entry)?;
                Ok(json!({ "saved": true }))
            }
            "config.record_stream_selection" => {
                let item_id = req_str(&p, "itemId")?;
                let ctx: StreamSelectionContext =
                    serde_json::from_value(p.get("context").cloned().unwrap_or_else(|| json!({})))?;
                self.config.record_stream_selection(&item_id, ctx)?;
                Ok(json!({ "saved": true }))
            }
            "config.get_last_stream_context" => {
                let item_id = req_str(&p, "itemId")?;
                Ok(serde_json::to_value(self.config.last_stream_context(&item_id))?)
            }
            "config.get_favorites" => Ok(serde_json::to_value(self.config.favorites())?),
            "config.toggle_favorite" => {
                let fav: FavoriteEntry = serde_json::from_value(p)?;
                let added = self.config.toggle_favorite(fav)?;
                Ok(json!({ "isFavorite": added }))
            }
            "config.set_swarm_filter" => {
                let filter: SwarmFilter =
                    serde_json::from_value(p.get("filter").cloned().unwrap_or(Value::Null))?;
                self.config.set_swarm_filter(filter)?;
                Ok(json!({ "swarmFilter": filter }))
            }
            "config.set_discover_sort_mode" => {
                let mode: DiscoverSortMode =
                    serde_json::from_value(p.get("mode").cloned().unwrap_or(Value::Null))?;
                self.config.set_discover_sort_mode(mode)?;
                Ok(json!({ "discoverSortMode": mode }))
            }
            "config.set_torrent_config" => {
                let cfg: TorrentEngineConfig = serde_json::from_value(p)?;
                self.config.set_torrent_config(cfg)?;
                Ok(serde_json::to_value(self.config.torrent_config())?)
            }
            "config.get_torznab" => {
                let (url, has_key) = {
                    let (u, k) = self.config.torznab_config();
                    (u, !k.is_empty())
                };
                Ok(json!({ "url": url, "hasApiKey": has_key }))
            }
            "config.set_torznab" => {
                let url = opt_str(&p, "url").unwrap_or_default();
                let key = opt_str(&p, "apiKey").unwrap_or_default();
                self.config.set_torznab_config(&url, &key)?;
                Ok(json!({ "saved": true }))
            }
            "config.get_creator_follows" => {
                let platform = opt_str(&p, "platform");
                Ok(serde_json::to_value(
                    self.config.active_creator_follows(platform.as_deref()),
                )?)
            }
            "config.toggle_creator_follow" => {
                let entry: CreatorFollowEntry = serde_json::from_value(p)?;
                let following = self.config.toggle_creator_follow(entry)?;
                Ok(json!({ "following": following }))
            }

            // ── Multipass v2 E2EE ─────────────────────────────────────────────
            "multipass.get_profile" => {
                let mut prof = self.config.multipass_profile();
                let paired = prof.is_paired();
                prof.pair_token = String::new();
                Ok(json!({ "paired": paired, "profile": prof }))
            }
            "multipass.disconnect" => {
                self.config.clear_multipass_profile()?;
                Ok(json!({ "disconnected": true }))
            }
            "multipass.start_pairing" => {
                let endpoint = req_str(&p, "endpointUrl")?;
                let dev_name = opt_str(&p, "deviceName")
                    .unwrap_or_else(|| "BrootLite Desktop".to_string());
                let sess = self
                    .multipass
                    .start_device_pairing(&endpoint, &dev_name)
                    .await?;
                Ok(serde_json::to_value(sess)?)
            }
            "multipass.poll_pairing" => {
                let origin = req_str(&p, "originUrl")?;
                let pid = req_str(&p, "pairingId")?;
                let sec = req_str(&p, "pollSecret")?;
                let code = opt_str(&p, "userCode").unwrap_or_default();
                let out = self
                    .multipass
                    .poll_device_pairing(&origin, &pid, &sec, &code)
                    .await?;
                Ok(serde_json::to_value(out)?)
            }
            "multipass.sync_pull" => {
                let pass = opt_str(&p, "passphrase");
                let rep = self
                    .multipass
                    .sync_from_endpoint(pass.as_deref(), &self.stremio, &self.jellyfin)
                    .await?;
                Ok(serde_json::to_value(rep)?)
            }
            "multipass.sync_push" => {
                let pass = opt_str(&p, "passphrase");
                let rev = self
                    .multipass
                    .push_current_setup_to_vault(pass.as_deref())
                    .await?;
                Ok(json!({ "revision": rev }))
            }
            "multipass.export_snapshot" => {
                Ok(serde_json::to_value(self.multipass.export_local_vault_snapshot())?)
            }

            // ── Stremio ───────────────────────────────────────────────────────
            "stremio.create_qr_session" => {
                Ok(serde_json::to_value(self.stremio.create_qr_link_session().await?)?)
            }
            "stremio.poll_qr_session" => {
                let code = req_str(&p, "code")?;
                let maybe_key = self.stremio.poll_qr_link_session(&code).await?;
                if let Some(auth_key) = maybe_key {
                    let summary = self.stremio.sync_account_everything(&auth_key).await?;
                    Ok(json!({ "approved": true, "summary": summary }))
                } else {
                    Ok(json!({ "approved": false }))
                }
            }
            "stremio.sync_account" => {
                let auth_key = match opt_str(&p, "authKey") {
                    Some(k) if !k.is_empty() => k,
                    _ => self
                        .config
                        .stremio_auth()
                        .0
                        .ok_or_else(|| CoreError::Auth("Not signed in to Stremio".into()))?,
                };
                Ok(serde_json::to_value(
                    self.stremio.sync_account_everything(&auth_key).await?,
                )?)
            }
            "stremio.logout" => {
                self.config.set_stremio_auth(None, "", None)?;
                Ok(json!({ "loggedOut": true }))
            }
            "stremio.push_watch_progress" => {
                let entry: WatchHistoryEntry = serde_json::from_value(p)?;
                let ok = self.stremio.push_watch_progress(&entry).await?;
                Ok(json!({ "pushed": ok }))
            }
            "stremio.install_addon" => {
                let url = req_str(&p, "url")?;
                Ok(serde_json::to_value(self.stremio.install_addon_from_url(&url).await?)?)
            }
            "stremio.fetch_catalog" => {
                let mtype = opt_str(&p, "type").unwrap_or_else(|| "movie".into());
                let cat_id = opt_str(&p, "catalogId").unwrap_or_else(|| "top".into());
                let addon_id = opt_str(&p, "addonId");
                let transport_url = opt_str(&p, "transportUrl");
                let addons = self.config.addons();
                let addon = addons
                    .into_iter()
                    .find(|a| {
                        addon_id.as_deref().map(|id| a.id == id).unwrap_or(false)
                            || transport_url
                                .as_deref()
                                .map(|u| a.transport_url == u)
                                .unwrap_or(false)
                    })
                    .or_else(|| self.config.addons().into_iter().next())
                    .ok_or_else(|| CoreError::NotFound("No catalog addon found".into()))?;
                let search = opt_str(&p, "search");
                let genre = opt_str(&p, "genre");
                let year = opt_str(&p, "year");
                let skip = p.get("skip").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
                let items = self
                    .stremio
                    .fetch_catalog(
                        &addon,
                        &mtype,
                        &cat_id,
                        search.as_deref(),
                        genre.as_deref(),
                        year.as_deref(),
                        skip,
                    )
                    .await?;
                Ok(serde_json::to_value(items)?)
            }
            "stremio.search" => {
                let q = req_str(&p, "query")?;
                Ok(serde_json::to_value(self.stremio.search_catalogs(&q).await?)?)
            }
            "stremio.fetch_meta" => {
                let mtype = opt_str(&p, "type").unwrap_or_else(|| "movie".into());
                let item_id = req_str(&p, "itemId")?;
                Ok(serde_json::to_value(
                    self.stremio.fetch_meta_detail(&mtype, &item_id).await?,
                )?)
            }
            "stremio.fetch_streams" => {
                let mtype = opt_str(&p, "type").unwrap_or_else(|| "movie".into());
                let vid = req_str(&p, "videoId")?;
                Ok(serde_json::to_value(
                    self.stremio.fetch_streams(&mtype, &vid).await?,
                )?)
            }
            "stremio.fetch_subtitles" => {
                let mtype = opt_str(&p, "type").unwrap_or_else(|| "movie".into());
                let vid = req_str(&p, "videoId")?;
                Ok(serde_json::to_value(
                    self.stremio.fetch_subtitles(&mtype, &vid).await?,
                )?)
            }

            // ── Jellyfin ──────────────────────────────────────────────────────
            "jellyfin.list_servers" => {
                let mut list = self.config.jellyfin_servers();
                for s in &mut list {
                    s.access_token.clear();
                    s.bootstrap_token.clear();
                }
                Ok(serde_json::to_value(list)?)
            }
            "jellyfin.remove_server" => {
                let sid = req_str(&p, "serverId")?;
                self.config.remove_jellyfin_server(&sid)?;
                Ok(json!({ "removed": true }))
            }
            "jellyfin.discover_lan" => {
                let timeout = p.get("timeoutMs").and_then(|v| v.as_u64()).unwrap_or(1500);
                Ok(serde_json::to_value(
                    self.jellyfin.discover_lan_servers(timeout).await?,
                )?)
            }
            "jellyfin.public_info" => {
                let url = req_str(&p, "baseUrl")?;
                let (id, name) = self.jellyfin.fetch_public_server_info(&url).await?;
                let qc = self.jellyfin.check_quick_connect_enabled(&url).await;
                Ok(json!({ "serverId": id, "serverName": name, "quickConnectEnabled": qc }))
            }
            "jellyfin.quick_connect_initiate" => {
                let url = req_str(&p, "baseUrl")?;
                Ok(serde_json::to_value(
                    self.jellyfin.initiate_quick_connect(&url).await?,
                )?)
            }
            "jellyfin.quick_connect_poll" => {
                let lan = req_str(&p, "lanUrl")?;
                let rem = opt_str(&p, "remoteUrl");
                let sec = req_str(&p, "secret")?;
                let mut prof_opt = self
                    .jellyfin
                    .poll_quick_connect(&lan, rem.as_deref(), &sec)
                    .await?;
                if let Some(ref mut pr) = prof_opt {
                    pr.access_token.clear();
                    pr.bootstrap_token.clear();
                }
                Ok(serde_json::to_value(prof_opt)?)
            }
            "jellyfin.login_password" => {
                let lan = req_str(&p, "lanUrl")?;
                let rem = opt_str(&p, "remoteUrl");
                let user = req_str(&p, "username")?;
                let pass = opt_str(&p, "password").unwrap_or_default();
                let mut prof = self
                    .jellyfin
                    .authenticate_by_name(&lan, rem.as_deref(), &user, &pass)
                    .await?;
                prof.access_token.clear();
                prof.bootstrap_token.clear();
                Ok(serde_json::to_value(prof)?)
            }
            "jellyfin.views" => {
                let prof = self.resolve_jf_profile(&p)?;
                Ok(serde_json::to_value(self.jellyfin.get_views(&prof).await?)?)
            }
            "jellyfin.resume" => {
                let prof = self.resolve_jf_profile(&p)?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(20) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin.get_resume_items(&prof, lim).await?,
                )?)
            }
            "jellyfin.next_up" => {
                let prof = self.resolve_jf_profile(&p)?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(20) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin.get_next_up(&prof, lim).await?,
                )?)
            }
            "jellyfin.latest" => {
                let prof = self.resolve_jf_profile(&p)?;
                let pid = req_str(&p, "parentId")?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(24) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin.get_latest_in_view(&prof, &pid, lim).await?,
                )?)
            }
            "jellyfin.items" => {
                let prof = self.resolve_jf_profile(&p)?;
                let parent = opt_str(&p, "parentId");
                let types = opt_str(&p, "includeItemTypes");
                let genre = opt_str(&p, "genre");
                let year = opt_str(&p, "year");
                let sort_by = opt_str(&p, "sortBy").unwrap_or_else(|| "SortName".into());
                let sort_order = opt_str(&p, "sortOrder").unwrap_or_else(|| "Ascending".into());
                let start = p.get("startIndex").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
                let limit = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(48) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin
                        .get_library_items(
                            &prof,
                            parent.as_deref(),
                            types.as_deref(),
                            genre.as_deref(),
                            year.as_deref(),
                            &sort_by,
                            &sort_order,
                            start,
                            limit,
                        )
                        .await?,
                )?)
            }
            "jellyfin.genres" => {
                let prof = self.resolve_jf_profile(&p)?;
                let parent = opt_str(&p, "parentId");
                Ok(serde_json::to_value(
                    self.jellyfin.get_genres(&prof, parent.as_deref()).await?,
                )?)
            }
            "jellyfin.years" => {
                let prof = self.resolve_jf_profile(&p)?;
                let parent = opt_str(&p, "parentId");
                Ok(serde_json::to_value(
                    self.jellyfin.get_years(&prof, parent.as_deref()).await?,
                )?)
            }
            "jellyfin.item_detail" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                Ok(serde_json::to_value(
                    self.jellyfin.get_item_detail(&prof, &item_id).await?,
                )?)
            }
            "jellyfin.episodes" => {
                let prof = self.resolve_jf_profile(&p)?;
                let series_id = req_str(&p, "seriesId")?;
                let season_id = opt_str(&p, "seasonId");
                Ok(serde_json::to_value(
                    self.jellyfin
                        .get_episodes(&prof, &series_id, season_id.as_deref())
                        .await?,
                )?)
            }
            "jellyfin.similar" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(16) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin.get_similar_items(&prof, &item_id, lim).await?,
                )?)
            }
            "jellyfin.search" => {
                let prof = self.resolve_jf_profile(&p)?;
                let q = req_str(&p, "query")?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(24) as u32;
                Ok(serde_json::to_value(
                    self.jellyfin.search_server(&prof, &q, lim).await?,
                )?)
            }
            "jellyfin.resolve_playback" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                Ok(serde_json::to_value(
                    self.jellyfin
                        .resolve_playback_session(&prof, &item_id)
                        .await?,
                )?)
            }
            "jellyfin.skip_segments" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                Ok(serde_json::to_value(
                    self.jellyfin.fetch_skip_segments(&prof, &item_id).await?,
                )?)
            }
            "jellyfin.report_start" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                let msid = opt_str(&p, "mediaSourceId").unwrap_or_default();
                let psid = opt_str(&p, "playSessionId").unwrap_or_default();
                let pos = p.get("positionMs").and_then(|v| v.as_u64()).unwrap_or(0);
                self.jellyfin
                    .report_playback_start(&prof, &item_id, &msid, &psid, pos)
                    .await?;
                Ok(json!({ "reported": true }))
            }
            "jellyfin.report_progress" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                let msid = opt_str(&p, "mediaSourceId").unwrap_or_default();
                let psid = opt_str(&p, "playSessionId").unwrap_or_default();
                let pos = p.get("positionMs").and_then(|v| v.as_u64()).unwrap_or(0);
                let paused = p.get("isPaused").and_then(|v| v.as_bool()).unwrap_or(false);
                self.jellyfin
                    .report_playback_progress(&prof, &item_id, &msid, &psid, pos, paused)
                    .await?;
                Ok(json!({ "reported": true }))
            }
            "jellyfin.report_stopped" => {
                let prof = self.resolve_jf_profile(&p)?;
                let item_id = req_str(&p, "itemId")?;
                let msid = opt_str(&p, "mediaSourceId").unwrap_or_default();
                let psid = opt_str(&p, "playSessionId").unwrap_or_default();
                let pos = p.get("positionMs").and_then(|v| v.as_u64()).unwrap_or(0);
                self.jellyfin
                    .report_playback_stopped(&prof, &item_id, &msid, &psid, pos)
                    .await?;
                Ok(json!({ "reported": true }))
            }

            // ── YouTube ───────────────────────────────────────────────────────
            "youtube.list_accounts" => {
                let active_id = self
                    .config
                    .active_youtube_account()
                    .map(|a| a.account_id)
                    .unwrap_or_default();
                let mut list = self.config.youtube_accounts();
                for a in &mut list {
                    a.refresh_token.clear();
                    a.access_token.clear();
                    a.oauth_client_secret.clear();
                }
                Ok(json!({ "activeAccountId": active_id, "accounts": list }))
            }
            "youtube.set_active_account" => {
                let id = req_str(&p, "accountId")?;
                self.config.set_active_youtube_account_id(&id)?;
                Ok(json!({ "activeAccountId": id }))
            }
            "youtube.remove_account" => {
                let id = req_str(&p, "accountId")?;
                self.config.remove_youtube_account(&id)?;
                Ok(json!({ "removed": true }))
            }
            "youtube.start_device_auth" => {
                Ok(serde_json::to_value(
                    self.youtube.start_device_code_auth().await?,
                )?)
            }
            "youtube.poll_device_auth" => {
                let sess: DeviceCodeSession = serde_json::from_value(p)?;
                let mut res = self.youtube.poll_device_token(&sess).await?;
                if let Some(ref mut list) = res {
                    for a in list {
                        a.refresh_token.clear();
                        a.access_token.clear();
                        a.oauth_client_secret.clear();
                    }
                }
                Ok(serde_json::to_value(res)?)
            }
            "youtube.home" => {
                let cont = opt_str(&p, "continuation");
                Ok(serde_json::to_value(
                    self.youtube.get_home_feed(cont.as_deref()).await?,
                )?)
            }
            "youtube.subscriptions" => {
                let cont = opt_str(&p, "continuation");
                Ok(serde_json::to_value(
                    self.youtube
                        .get_subscriptions_feed(cont.as_deref())
                        .await?,
                )?)
            }
            "youtube.history" => {
                let cont = opt_str(&p, "continuation");
                Ok(serde_json::to_value(
                    self.youtube.get_history_feed(cont.as_deref()).await?,
                )?)
            }
            "youtube.playlist" => {
                let pid = req_str(&p, "playlistId")?;
                let cont = opt_str(&p, "continuation");
                Ok(serde_json::to_value(
                    self.youtube
                        .get_playlist_videos(&pid, cont.as_deref())
                        .await?,
                )?)
            }
            "youtube.search" => {
                let q = req_str(&p, "query")?;
                let cont = opt_str(&p, "continuation");
                Ok(serde_json::to_value(
                    self.youtube.search_videos(&q, cont.as_deref()).await?,
                )?)
            }
            "youtube.suggestions" => {
                let q = req_str(&p, "query")?;
                Ok(serde_json::to_value(
                    self.youtube.get_search_suggestions(&q).await?,
                )?)
            }
            "youtube.channel" => {
                let cid = req_str(&p, "channelId")?;
                Ok(serde_json::to_value(
                    self.youtube.get_channel_feed(&cid).await?,
                )?)
            }
            "youtube.set_subscription" => {
                let cid = req_str(&p, "channelId")?;
                let sub = p.get("subscribe").and_then(|v| v.as_bool()).unwrap_or(true);
                let ok = self.youtube.set_channel_subscription(&cid, sub).await?;
                Ok(json!({ "updated": ok }))
            }
            "youtube.related" => {
                let vid = req_str(&p, "videoId")?;
                Ok(serde_json::to_value(
                    self.youtube.get_related_videos(&vid).await?,
                )?)
            }
            "youtube.resolve_stream" => {
                let vid = req_str(&p, "videoId")?;
                Ok(serde_json::to_value(
                    self.youtube.resolve_stream(&vid).await?,
                )?)
            }
            "youtube.dislike_votes" => {
                let vid = req_str(&p, "videoId")?;
                Ok(serde_json::to_value(
                    self.youtube.fetch_return_youtube_dislike(&vid).await?,
                )?)
            }
            "youtube.dearrow_title" => {
                let vid = req_str(&p, "videoId")?;
                Ok(json!({
                    "title": self.youtube.fetch_dearrow_title(&vid).await?
                }))
            }
            "youtube.report_watch_time" => {
                let p_url = opt_str(&p, "playbackTrackingUrl").unwrap_or_default();
                let w_url = opt_str(&p, "watchtimeTrackingUrl").unwrap_or_default();
                let pos = p.get("positionSec").and_then(|v| v.as_f64()).unwrap_or(0.0);
                let dur = p.get("durationSec").and_then(|v| v.as_f64()).unwrap_or(0.0);
                let ok = self
                    .youtube
                    .report_watch_time(&p_url, &w_url, pos, dur)
                    .await?;
                Ok(json!({ "reported": ok }))
            }

            // ── Creators: Twitch, Odysee, & Rumble ───────────────────────────
            "twitch.profile" => {
                let mut prof = self.config.twitch_profile();
                if let Some(ref mut tp) = prof {
                    tp.access_token.clear();
                    tp.refresh_token.clear();
                }
                Ok(serde_json::to_value(prof)?)
            }
            "twitch.logout" => {
                self.config.set_twitch_profile(None)?;
                Ok(json!({ "loggedOut": true }))
            }
            "twitch.start_device_auth" => {
                Ok(serde_json::to_value(
                    self.twitch.start_device_code_auth().await?,
                )?)
            }
            "twitch.poll_device_auth" => {
                let sess: DeviceCodeSession = serde_json::from_value(p)?;
                let mut prof = self.twitch.poll_device_token(&sess).await?;
                if let Some(ref mut tp) = prof {
                    tp.access_token.clear();
                    tp.refresh_token.clear();
                }
                Ok(serde_json::to_value(prof)?)
            }
            "twitch.top_live" => {
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(30) as u32;
                Ok(serde_json::to_value(
                    self.twitch.get_top_live_streams(lim).await?,
                )?)
            }
            "twitch.top_categories" => {
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(30) as u32;
                Ok(serde_json::to_value(
                    self.twitch.get_top_categories(lim).await?,
                )?)
            }
            "twitch.category_streams" => {
                let game = req_str(&p, "gameName")?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(30) as u32;
                Ok(serde_json::to_value(
                    self.twitch.get_category_streams(&game, lim).await?,
                )?)
            }
            "twitch.followed_live" => Ok(serde_json::to_value(
                self.twitch.get_followed_live_streams().await?,
            )?),
            "twitch.channel" => {
                let login = req_str(&p, "login")?;
                Ok(serde_json::to_value(
                    self.twitch.get_channel_detail(&login).await?,
                )?)
            }
            "twitch.search" => {
                let q = req_str(&p, "query")?;
                Ok(serde_json::to_value(self.twitch.search_twitch(&q).await?)?)
            }
            "twitch.resolve_stream" => {
                let target = req_str(&p, "target")?;
                Ok(serde_json::to_value(
                    self.twitch.resolve_stream(&target).await?,
                )?)
            }
            "odysee.featured" => {
                let page = p.get("page").and_then(|v| v.as_u64()).unwrap_or(1) as u32;
                Ok(serde_json::to_value(
                    self.odysee.get_featured_videos(page).await?,
                )?)
            }
            "odysee.live" => {
                Ok(serde_json::to_value(self.odysee.get_live_streams().await?)?)
            }
            "odysee.search" => {
                let q = req_str(&p, "query")?;
                Ok(serde_json::to_value(self.odysee.search_videos(&q).await?)?)
            }
            "odysee.resolve_stream" => {
                let claim = req_str(&p, "claim")?;
                Ok(serde_json::to_value(
                    self.odysee.resolve_stream(&claim).await?,
                )?)
            }
            "rumble.featured" => Ok(serde_json::to_value(
                self.rumble.get_featured_videos().await?,
            )?),
            "rumble.live" => {
                Ok(serde_json::to_value(self.rumble.get_live_streams().await?)?)
            }
            "rumble.search" => {
                let q = req_str(&p, "query")?;
                Ok(serde_json::to_value(self.rumble.search_videos(&q).await?)?)
            }
            "rumble.resolve_stream" => {
                let target = req_str(&p, "target")?;
                Ok(serde_json::to_value(
                    self.rumble.resolve_stream(&target).await?,
                )?)
            }

            // ── Native Swarm Search & P2P Torrent Loopback Streaming ─────────
            "swarm.search" => {
                let q = opt_str(&p, "query").unwrap_or_default();
                let imdb = opt_str(&p, "imdbId");
                let filt: Option<SwarmFilter> = p
                    .get("filter")
                    .and_then(|v| serde_json::from_value(v.clone()).ok());
                Ok(serde_json::to_value(
                    self.swarm.search_all(&q, imdb.as_deref(), filt).await?,
                )?)
            }
            "swarm.featured" => {
                let key = opt_str(&p, "feedKey").unwrap_or_else(|| "archive_movies".into());
                Ok(serde_json::to_value(
                    self.swarm.fetch_featured_feed(&key).await?,
                )?)
            }
            "torrent.start_stream" => {
                let magnet = req_str(&p, "magnetOrUrl")?;
                let fidx = p
                    .get("fileIdx")
                    .and_then(|v| v.as_u64())
                    .map(|n| n as usize);
                Ok(serde_json::to_value(
                    self.media_server.start_torrent_stream(&magnet, fidx).await?,
                )?)
            }
            "torrent.stats" => {
                let sid = req_str(&p, "sessionId")?;
                Ok(serde_json::to_value(
                    self.media_server.get_torrent_stats(&sid),
                )?)
            }
            "torrent.stop_stream" => {
                let sid = req_str(&p, "sessionId")?;
                self.media_server.stop_torrent_stream(&sid).await?;
                Ok(json!({ "stopped": true }))
            }
            "torrent.stop_all" => {
                self.media_server.stop_all_torrent_streams().await?;
                Ok(json!({ "stopped": true }))
            }

            // ── Binge Stream Matcher & Movie Credits Recommendations ─────────
            "binge.rank_streams" => {
                let ctx: StreamSelectionContext =
                    serde_json::from_value(p.get("context").cloned().unwrap_or_else(|| json!({})))?;
                let cands: Vec<StreamCandidate> = serde_json::from_value(
                    p.get("candidates").cloned().unwrap_or_else(|| json!([])),
                )?;
                Ok(serde_json::to_value(StreamSimilarityMatcher::rank_streams(
                    &ctx, cands,
                ))?)
            }
            "binge.next_episode" => {
                let eps: Vec<VideoEpisode> = serde_json::from_value(
                    p.get("episodes").cloned().unwrap_or_else(|| json!([])),
                )?;
                let cur = req_str(&p, "currentVideoId")?;
                Ok(serde_json::to_value(
                    StreamSimilarityMatcher::find_next_episode(&eps, &cur),
                )?)
            }
            "binge.recommendations" => {
                let seed: MetaItem =
                    serde_json::from_value(p.get("seed").cloned().unwrap_or(Value::Null))?;
                let lim = p.get("limit").and_then(|v| v.as_u64()).unwrap_or(14) as usize;
                Ok(serde_json::to_value(
                    self.recommendations
                        .get_recommendations_for_meta(&seed, lim)
                        .await?,
                )?)
            }

            // ── Kodi Addon Bridge ─────────────────────────────────────────────
            "kodi.list_addons" => Ok(serde_json::to_value(
                self.kodi.scan_installed_video_addons(),
            )?),
            "kodi.execute" => {
                let aid = req_str(&p, "addonId")?;
                let purl = opt_str(&p, "pluginUrl");
                let timeout = p.get("timeoutSec").and_then(|v| v.as_u64()).unwrap_or(20);
                Ok(serde_json::to_value(
                    self.kodi
                        .execute_addon(&aid, purl.as_deref(), timeout)
                        .await?,
                )?)
            }
            "kodi.parse_stream_url" => {
                let raw = req_str(&p, "url")?;
                let (clean, headers) = parse_kodi_stream_url(&raw);
                Ok(json!({ "url": clean, "headers": headers }))
            }

            unknown => Err(CoreError::InvalidInput(format!(
                "Unknown BrootLite RPC method: {unknown}"
            ))),
        }
    }

    fn resolve_jf_profile(
        &self,
        p: &Value,
    ) -> CoreResult<crate::models::JellyfinServerProfile> {
        let mut sid = opt_str(p, "serverId").unwrap_or_default();
        if sid.is_empty() {
            if let Some(item_id) = opt_str(p, "itemId").or_else(|| opt_str(p, "seriesId")) {
                if let Some((parsed_srv, _)) = JellyfinIds::parse_id(&item_id) {
                    sid = parsed_srv;
                }
            }
        }
        if !sid.is_empty() {
            return self.config.jellyfin_server(&sid).ok_or_else(|| {
                CoreError::NotFound(format!("Jellyfin server {sid} not configured"))
            });
        }
        self.config
            .jellyfin_servers()
            .into_iter()
            .next()
            .ok_or_else(|| CoreError::NotFound("No Jellyfin server configured".into()))
    }
}

fn req_str(p: &Value, key: &str) -> CoreResult<String> {
    p.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .ok_or_else(|| CoreError::InvalidInput(format!("Missing required parameter `{key}`")))
}

fn opt_str(p: &Value, key: &str) -> Option<String> {
    p.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
}
