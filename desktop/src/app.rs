//! Core `BrootLiteApp` state machine wiring `iced 0.13` UI, `brootlite-core`, and dynamic `libmpv`.

use crate::mpv_player::{DynamicMpvApi, LoadMediaRequest, MpvPlayer, MpvRenderMode};
use crate::poster_cache::{download_poster_bytes, PosterLruCache};
use crate::state::{
    apply_discover_sort, make_qr_data, ActiveOverlay, ActivePlayerState, BingeNextCardState,
    BrootLiteEngineWrapper, CatalogRow, ContinueWatchingAction, ContinueWatchingCard,
    CreatorSubTab, DetailStreamFilter, DetailViewState, DiscoverCatalogChoice,
    IsolatedJellyfinServerState, JellyfinAddServerWizard, JellyfinSubMode, KeyboardAction,
    LibraryFilter, Message, NavTab, PlaybackSourceKind, SearchCatalogFilter, StreamingPlatform,
    SwarmSourceTab, TwitchTab, YouTubeTab,
};
use crate::theme::{root_canvas_style, xirtus_theme};
use crate::views;
use brootlite_core::binge::StreamSimilarityMatcher;
use brootlite_core::jellyfin::JellyfinIds;
use brootlite_core::kodi::parse_kodi_stream_url;
use brootlite_core::models::{
    now_epoch_ms, CreatorCategory, CreatorChannel, CreatorFollowEntry, CreatorVideoItem,
    DeviceCodeSession, DiscoverSortMode, FavoriteEntry, InstalledAddon, JellyfinServerProfile,
    KodiAddonInfo, KodiDirectoryItem, MetaItem, MultipassProfile, SkipSegmentKind,
    StreamCandidate, SwarmFilter, TorrentEngineConfig, TwitchAuthProfile, WatchHistoryEntry,
    YouTubeAccountProfile,
};
use brootlite_core::multipass::{
    MultipassPairingPollOutcome, MultipassPairingSession, MultipassSyncReport,
};
use brootlite_core::stremio::StremioLinkSession;
use brootlite_core::BrootLiteEngine;
use iced::keyboard::{self, Key};
use iced::widget::{column, container, qr_code};
use iced::{Element, Length, Subscription, Task, Theme};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;

pub struct BrootLiteApp {
    engine: Option<Arc<BrootLiteEngine>>,
    mpv_api: Option<Arc<DynamicMpvApi>>,
    mpv_player: Option<MpvPlayer>,
    mpv_render_mode: MpvRenderMode,
    mpv_status_line: String,

    posters: PosterLruCache,
    active_tab: NavTab,
    overlay: ActiveOverlay,
    toast: Option<String>,

    // 1. Board
    board_search_input: String,
    board_rows: Vec<CatalogRow>,
    continue_watching: Vec<ContinueWatchingCard>,
    board_loading: bool,

    // 2. Discover
    discover_media_types: Vec<String>,
    discover_active_type: String,
    discover_catalogs: Vec<DiscoverCatalogChoice>,
    discover_catalog_idx: usize,
    discover_genre: String,
    discover_year: String,
    discover_sort: DiscoverSortMode,
    discover_skip: u32,
    discover_raw_items: Vec<MetaItem>,
    discover_items: Vec<MetaItem>,
    discover_loading: bool,

    // 3. Jellyfin (Strictly Isolated Multi-Server)
    jf_servers: Vec<JellyfinServerProfile>,
    jf_active_server_id: Option<String>,
    jf_sub_mode: JellyfinSubMode,
    jf_isolated: IsolatedJellyfinServerState,
    jf_wizard: JellyfinAddServerWizard,

    // 4. Streaming (YouTube · Twitch · Odysee · Rumble)
    streaming_platform: StreamingPlatform,
    yt_accounts: Vec<YouTubeAccountProfile>,
    yt_active_account_id: String,
    yt_device_session: Option<DeviceCodeSession>,
    yt_qr_data: Option<qr_code::Data>,
    yt_tab: YouTubeTab,
    yt_playlist_input: String,
    yt_search_query: String,
    yt_suggestions: Vec<String>,
    yt_feed: Vec<CreatorVideoItem>,
    yt_continuation: Option<String>,
    yt_channel: Option<CreatorChannel>,
    sponsorblock_enabled: bool,
    ryd_enabled: bool,
    dearrow_enabled: bool,
    dearrow_titles: HashMap<String, String>,

    twitch_profile: Option<TwitchAuthProfile>,
    twitch_device_session: Option<DeviceCodeSession>,
    twitch_qr_data: Option<qr_code::Data>,
    twitch_tab: TwitchTab,
    twitch_items: Vec<CreatorVideoItem>,
    twitch_categories: Vec<CreatorCategory>,
    twitch_channel: Option<CreatorChannel>,
    twitch_search_query: String,

    odysee_tab: CreatorSubTab,
    odysee_items: Vec<CreatorVideoItem>,
    odysee_search_query: String,

    rumble_tab: CreatorSubTab,
    rumble_items: Vec<CreatorVideoItem>,
    rumble_search_query: String,

    creator_follows: Vec<CreatorFollowEntry>,
    streaming_loading: bool,

    // 5. Search
    search_query: String,
    search_catalog_filter: SearchCatalogFilter,
    search_catalog_results: Vec<MetaItem>,
    search_catalog_loading: bool,
    search_swarm_tab: SwarmSourceTab,
    search_swarm_results: Vec<StreamCandidate>,
    search_swarm_featured: Vec<StreamCandidate>,
    search_swarm_loading: bool,

    // 6. Library & 7. Kodi
    library_filter: LibraryFilter,
    favorites: Vec<FavoriteEntry>,
    watch_history: Vec<WatchHistoryEntry>,
    kodi_addons: Vec<KodiAddonInfo>,
    kodi_active_addon: Option<String>,
    kodi_breadcrumbs: Vec<(String, String)>,
    kodi_items: Vec<KodiDirectoryItem>,
    kodi_loading: bool,
    kodi_error: Option<String>,

    // 8. Passport ↗ (Leeloo Multipass v2 + Stremio + Engine Tuning)
    mp_profile: MultipassProfile,
    mp_endpoint_input: String,
    mp_device_name_input: String,
    mp_passphrase_input: String,
    mp_pairing_session: Option<MultipassPairingSession>,
    mp_pairing_qr: Option<qr_code::Data>,
    mp_status_text: String,
    mp_last_report: Option<MultipassSyncReport>,
    mp_syncing: bool,

    stremio_linked: bool,
    stremio_email: String,
    stremio_qr_session: Option<StremioLinkSession>,
    stremio_qr_data: Option<qr_code::Data>,
    addon_url_input: String,
    installed_addons: Vec<InstalledAddon>,

    swarm_filter: SwarmFilter,
    max_cache_input: String,
    window_ahead_input: String,
    prebuffer_input: String,
    max_conns_input: String,
    torznab_url_input: String,
    torznab_key_input: String,
    torznab_has_key: bool,

    // Detail & Active Player
    detail: Option<DetailViewState>,
    player: Option<ActivePlayerState>,
}

impl BrootLiteApp {
    pub fn boot() -> (Self, Task<Message>) {
        let (mpv_api, mpv_status_line) = match DynamicMpvApi::load() {
            Ok(api) => {
                let maj = api.api_version >> 16;
                let min = api.api_version & 0xFFFF;
                let msg = format!(
                    "libmpv v{maj}.{min} dynamically linked ({}) · default hwdec={}",
                    api.loaded_path,
                    DynamicMpvApi::default_hwdec()
                );
                (Some(api), msg)
            }
            Err(err) => (None, err),
        };

        let app = Self {
            engine: None,
            mpv_api,
            mpv_player: None,
            mpv_render_mode: MpvRenderMode::GpuWindow,
            mpv_status_line,
            posters: PosterLruCache::default(),
            active_tab: NavTab::Board,
            overlay: ActiveOverlay::None,
            toast: None,

            board_search_input: String::new(),
            board_rows: Vec::new(),
            continue_watching: Vec::new(),
            board_loading: true,

            discover_media_types: vec!["movie".into(), "series".into()],
            discover_active_type: "movie".into(),
            discover_catalogs: Vec::new(),
            discover_catalog_idx: 0,
            discover_genre: "All".into(),
            discover_year: "All".into(),
            discover_sort: DiscoverSortMode::Popular,
            discover_skip: 0,
            discover_raw_items: Vec::new(),
            discover_items: Vec::new(),
            discover_loading: false,

            jf_servers: Vec::new(),
            jf_active_server_id: None,
            jf_sub_mode: JellyfinSubMode::Overview,
            jf_isolated: IsolatedJellyfinServerState::default(),
            jf_wizard: JellyfinAddServerWizard::default(),

            streaming_platform: StreamingPlatform::YouTube,
            yt_accounts: Vec::new(),
            yt_active_account_id: String::new(),
            yt_device_session: None,
            yt_qr_data: None,
            yt_tab: YouTubeTab::Home,
            yt_playlist_input: "WL".into(),
            yt_search_query: String::new(),
            yt_suggestions: Vec::new(),
            yt_feed: Vec::new(),
            yt_continuation: None,
            yt_channel: None,
            sponsorblock_enabled: true,
            ryd_enabled: true,
            dearrow_enabled: false,
            dearrow_titles: HashMap::new(),

            twitch_profile: None,
            twitch_device_session: None,
            twitch_qr_data: None,
            twitch_tab: TwitchTab::TopLive,
            twitch_items: Vec::new(),
            twitch_categories: Vec::new(),
            twitch_channel: None,
            twitch_search_query: String::new(),

            odysee_tab: CreatorSubTab::Featured,
            odysee_items: Vec::new(),
            odysee_search_query: String::new(),

            rumble_tab: CreatorSubTab::Featured,
            rumble_items: Vec::new(),
            rumble_search_query: String::new(),

            creator_follows: Vec::new(),
            streaming_loading: false,

            search_query: String::new(),
            search_catalog_filter: SearchCatalogFilter::All,
            search_catalog_results: Vec::new(),
            search_catalog_loading: false,
            search_swarm_tab: SwarmSourceTab::AllSwarm,
            search_swarm_results: Vec::new(),
            search_swarm_featured: Vec::new(),
            search_swarm_loading: false,

            library_filter: LibraryFilter::All,
            favorites: Vec::new(),
            watch_history: Vec::new(),
            kodi_addons: Vec::new(),
            kodi_active_addon: None,
            kodi_breadcrumbs: Vec::new(),
            kodi_items: Vec::new(),
            kodi_loading: false,
            kodi_error: None,

            mp_profile: MultipassProfile::default(),
            mp_endpoint_input: String::new(),
            mp_device_name_input: "BrootLite Desktop".into(),
            mp_passphrase_input: String::new(),
            mp_pairing_session: None,
            mp_pairing_qr: None,
            mp_status_text: String::new(),
            mp_last_report: None,
            mp_syncing: false,

            stremio_linked: false,
            stremio_email: String::new(),
            stremio_qr_session: None,
            stremio_qr_data: None,
            addon_url_input: String::new(),
            installed_addons: Vec::new(),

            swarm_filter: SwarmFilter::SafeOnly,
            max_cache_input: "512".into(),
            window_ahead_input: "48".into(),
            prebuffer_input: "12".into(),
            max_conns_input: "120".into(),
            torznab_url_input: String::new(),
            torznab_key_input: String::new(),
            torznab_has_key: false,

            detail: None,
            player: None,
        };

        let init_task = Task::perform(
            async {
                BrootLiteEngine::new(None)
                    .await
                    .map(|e| Arc::new(BrootLiteEngineWrapper(e)))
                    .map_err(|e| e.to_string())
            },
            Message::EngineBooted,
        );

        (app, init_task)
    }

    pub fn title(&self) -> String {
        if let Some(p) = &self.player {
            format!("BrootLite — {}", p.title)
        } else {
            format!("BrootLite v2.1.1 — {}", self.active_tab.label())
        }
    }

    pub fn theme(&self) -> Theme {
        xirtus_theme()
    }

    pub fn subscription(&self) -> Subscription<Message> {
        let mut subs = Vec::new();

        // 1. Hardware/SW Player Telemetry Loop (active ONLY while playing media — 0% idle CPU!)
        if self.player.is_some() {
            let interval_ms = if self.mpv_render_mode == MpvRenderMode::InlineSoftware {
                33
            } else {
                120
            };
            subs.push(
                iced::time::every(Duration::from_millis(interval_ms))
                    .map(|_| Message::FastPlayerTick),
            );
        }

        // 2. OAuth / QR / QuickConnect Poll Loop (active ONLY while a pairing/QR session is open!)
        let any_poll_active = self.mp_pairing_session.is_some()
            || self.stremio_qr_session.is_some()
            || self.yt_device_session.is_some()
            || self.twitch_device_session.is_some()
            || self.jf_wizard.quick_connect.is_some();
        if any_poll_active {
            subs.push(
                iced::time::every(Duration::from_secs(3)).map(|_| Message::SlowBackgroundTick),
            );
        }

        // 3. Keyboard Shortcuts (registered ONLY when Detail or Player overlay is open so text inputs are never intercepted!)
        if self.overlay != ActiveOverlay::None {
            subs.push(keyboard::on_key_press(handle_overlay_key_press));
        }

        Subscription::batch(subs)
    }

    pub fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::NoOp => Task::none(),
            Message::DismissToast => {
                self.toast = None;
                Task::none()
            }
            Message::EngineBooted(res) => match res {
                Ok(wrapper) => {
                    self.engine = Some(wrapper.0.clone());
                    self.hydrate_from_config();
                    Task::batch([
                        self.spawn_board_refresh(),
                        self.spawn_swarm_featured("archive_movies".into()),
                    ])
                }
                Err(err) => {
                    self.toast = Some(format!("Core engine init error: {err}"));
                    Task::none()
                }
            },

            Message::PosterLoaded(url, res) => {
                match res {
                    Ok(bytes) => {
                        let handle = iced::widget::image::Handle::from_bytes(bytes);
                        self.posters.insert(url, handle);
                    }
                    Err(_) => {
                        self.posters.mark_failed(&url);
                    }
                }
                Task::none()
            }

            Message::KeyboardShortcut(act) => match act {
                KeyboardAction::Escape => {
                    if self.overlay == ActiveOverlay::Player
                        && self.player.as_ref().is_some_and(|p| p.is_scrubbing)
                    {
                        self.update(Message::PlayerCancelScrub)
                    } else {
                        self.update(Message::CloseOverlay)
                    }
                }
                KeyboardAction::TogglePause if self.overlay == ActiveOverlay::Player => {
                    self.update(Message::PlayerTogglePause)
                }
                KeyboardAction::SeekBack10 if self.overlay == ActiveOverlay::Player => {
                    self.update(Message::PlayerStepKeyboardScrub(-1.0))
                }
                KeyboardAction::SeekForward10 if self.overlay == ActiveOverlay::Player => {
                    self.update(Message::PlayerStepKeyboardScrub(1.0))
                }
                KeyboardAction::CommitScrub if self.overlay == ActiveOverlay::Player => {
                    if self.player.as_ref().is_some_and(|p| p.is_scrubbing) {
                        self.update(Message::PlayerScrubCommit)
                    } else {
                        Task::none()
                    }
                }
                KeyboardAction::ToggleFullscreen if self.overlay == ActiveOverlay::Player => {
                    self.update(Message::PlayerToggleFullscreen)
                }
                KeyboardAction::ToggleMute if self.overlay == ActiveOverlay::Player => {
                    self.update(Message::PlayerToggleMute)
                }
                _ => Task::none(),
            },

            // ── Navigation & Overlays ─────────────────────────────────────────
            Message::SelectTab(tab) => {
                self.active_tab = tab;
                self.overlay = ActiveOverlay::None;
                match tab {
                    NavTab::Board => {
                        if self.board_rows.is_empty() {
                            return self.spawn_board_refresh();
                        }
                    }
                    NavTab::Discover => {
                        if self.discover_items.is_empty() {
                            return self.spawn_discover_fetch();
                        }
                    }
                    NavTab::Jellyfin => {
                        if let Some(sid) = self.jf_active_server_id.clone() {
                            if self.jf_isolated.server_id != sid
                                || (self.jf_isolated.views.is_empty()
                                    && self.jf_isolated.resume_items.is_empty())
                            {
                                return self.switch_and_load_jellyfin_server(&sid);
                            }
                        }
                    }
                    NavTab::Streaming => {
                        return self.ensure_streaming_loaded();
                    }
                    NavTab::Search => {
                        if self.search_swarm_featured.is_empty() {
                            return self.spawn_swarm_featured("archive_movies".into());
                        }
                    }
                    NavTab::Library => {
                        self.refresh_library_lists();
                    }
                    NavTab::Kodi => {
                        if let Some(eng) = &self.engine {
                            self.kodi_addons = eng.kodi.scan_installed_video_addons();
                        }
                    }
                    NavTab::Passport => {
                        self.hydrate_from_config();
                    }
                }
                Task::none()
            }
            Message::CloseOverlay => {
                self.overlay = ActiveOverlay::None;
                Task::none()
            }
            Message::ReturnToPlayer => {
                if self.player.is_some() {
                    self.overlay = ActiveOverlay::Player;
                }
                Task::none()
            }

            // ── 1. Board ──────────────────────────────────────────────────────
            Message::BoardSearchInputChanged(val) => {
                self.board_search_input = val;
                Task::none()
            }
            Message::BoardSearchSubmit => {
                let q = self.board_search_input.trim().to_string();
                self.active_tab = NavTab::Search;
                self.overlay = ActiveOverlay::None;
                if !q.is_empty() {
                    self.search_query = q;
                    return self.spawn_unified_search();
                }
                Task::none()
            }
            Message::RefreshBoard => self.spawn_board_refresh(),
            Message::BoardCatalogsLoaded(rows, cw) => {
                self.board_loading = false;
                let mut urls: Vec<String> = cw.iter().map(|c| c.poster_url.clone()).collect();
                for r in &rows {
                    for m in r.items.iter().take(12) {
                        urls.push(m.poster.clone());
                    }
                }
                self.board_rows = rows;
                self.continue_watching = cw;
                self.queue_poster_fetches(urls)
            }
            Message::TriggerContinueWatching(idx) => {
                let Some(card) = self.continue_watching.get(idx).cloned() else {
                    return Task::none();
                };
                match card.action {
                    ContinueWatchingAction::Stremio { entry } => {
                        let meta = MetaItem {
                            id: entry.item_id.clone(),
                            media_type: entry.media_type.clone(),
                            name: entry.title.clone(),
                            poster: entry.poster.clone(),
                            ..Default::default()
                        };
                        self.open_meta_detail_with_resume(
                            meta,
                            Some(entry.video_id),
                            entry.episode_title,
                            entry.position_ms,
                        )
                    }
                    ContinueWatchingAction::Jellyfin {
                        server_id,
                        meta,
                        resume_ms,
                    } => self.update(Message::PlayJellyfinItem {
                        server_id,
                        video_id: meta.id.clone(),
                        episode_title: String::new(),
                        meta,
                        resume_ms,
                    }),
                    ContinueWatchingAction::Creator { item } => {
                        self.update(Message::PlayCreatorItem(item))
                    }
                }
            }

            // ── 2. Discover ───────────────────────────────────────────────────
            Message::DiscoverSelectMediaType(mt) => {
                self.discover_active_type = mt;
                self.rebuild_discover_catalogs();
                self.discover_catalog_idx = 0;
                self.discover_genre = "All".into();
                self.discover_skip = 0;
                self.spawn_discover_fetch()
            }
            Message::DiscoverSelectCatalog(idx) => {
                self.discover_catalog_idx = idx;
                self.discover_genre = "All".into();
                self.discover_skip = 0;
                self.spawn_discover_fetch()
            }
            Message::DiscoverSelectGenre(g) => {
                self.discover_genre = g;
                self.discover_skip = 0;
                self.spawn_discover_fetch()
            }
            Message::DiscoverSelectYear(yr) => {
                self.discover_year = yr;
                self.discover_skip = 0;
                self.spawn_discover_fetch()
            }
            Message::DiscoverSelectSort(mode) => {
                self.discover_sort = mode;
                if let Some(eng) = &self.engine {
                    let _ = eng.config.set_discover_sort_mode(mode);
                }
                let mut sorted = self.discover_raw_items.clone();
                apply_discover_sort(&mut sorted, mode);
                self.discover_items = sorted;
                Task::none()
            }
            Message::DiscoverPageDelta(delta) => {
                self.discover_skip = (self.discover_skip as i32 + delta).max(0) as u32;
                self.spawn_discover_fetch()
            }
            Message::DiscoverJumpToSearch => {
                self.active_tab = NavTab::Search;
                self.overlay = ActiveOverlay::None;
                Task::none()
            }
            Message::DiscoverLoaded(res) => {
                self.discover_loading = false;
                match res {
                    Ok(items) => {
                        self.discover_raw_items = items.clone();
                        let mut sorted = items;
                        apply_discover_sort(&mut sorted, self.discover_sort);
                        let urls: Vec<String> =
                            sorted.iter().take(36).map(|m| m.poster.clone()).collect();
                        self.discover_items = sorted;
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("Discover fetch failed: {e}"));
                        Task::none()
                    }
                }
            }

            // ── 3. Jellyfin (Strictly Isolated Multi-Server) ──────────────────
            Message::JellyfinSwitchServer(sid) => {
                self.jf_sub_mode = JellyfinSubMode::Overview;
                self.switch_and_load_jellyfin_server(&sid)
            }
            Message::JellyfinSetSubMode(mode) => {
                self.jf_sub_mode = mode.clone();
                if let JellyfinSubMode::LibraryView(vid) = mode {
                    self.jf_isolated.page_start = 0;
                    self.jf_isolated.selected_genre = "All".into();
                    self.jf_isolated.selected_year = "All".into();
                    return self.spawn_jellyfin_library_fetch(Some(vid), true);
                }
                Task::none()
            }
            Message::JellyfinOverviewLoaded(sid, res) => {
                if self.jf_active_server_id.as_deref() != Some(sid.as_str()) {
                    return Task::none();
                }
                self.jf_isolated.loading = false;
                match res {
                    Ok((views, resume, next_up, latest)) => {
                        let mut urls = Vec::new();
                        for m in resume.iter().chain(next_up.iter()) {
                            urls.push(m.poster.clone());
                        }
                        for (_, list) in &latest {
                            for m in list.iter().take(12) {
                                urls.push(m.poster.clone());
                            }
                        }
                        self.jf_isolated.views = views;
                        self.jf_isolated.resume_items = resume;
                        self.jf_isolated.next_up_items = next_up;
                        self.jf_isolated.latest_by_view = latest;
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("Jellyfin server error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::JellyfinViewFilterLoaded(sid, genres, years) => {
                if self.jf_active_server_id.as_deref() == Some(sid.as_str()) {
                    self.jf_isolated.genres = genres;
                    self.jf_isolated.years = years;
                }
                Task::none()
            }
            Message::JellyfinItemsLoaded(sid, res) => {
                if self.jf_active_server_id.as_deref() != Some(sid.as_str()) {
                    return Task::none();
                }
                self.jf_isolated.loading = false;
                match res {
                    Ok(items) => {
                        let urls: Vec<String> = items.iter().map(|m| m.poster.clone()).collect();
                        self.jf_isolated.library_items = items;
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("Jellyfin items error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::JellyfinSelectGenre(g) => {
                self.jf_isolated.selected_genre = g;
                self.jf_isolated.page_start = 0;
                let vid = match &self.jf_sub_mode {
                    JellyfinSubMode::LibraryView(v) => Some(v.clone()),
                    _ => None,
                };
                self.spawn_jellyfin_library_fetch(vid, false)
            }
            Message::JellyfinSelectYear(y) => {
                self.jf_isolated.selected_year = y;
                self.jf_isolated.page_start = 0;
                let vid = match &self.jf_sub_mode {
                    JellyfinSubMode::LibraryView(v) => Some(v.clone()),
                    _ => None,
                };
                self.spawn_jellyfin_library_fetch(vid, false)
            }
            Message::JellyfinSelectSort(sb, so) => {
                self.jf_isolated.sort_by = sb;
                self.jf_isolated.sort_order = so;
                self.jf_isolated.page_start = 0;
                let vid = match &self.jf_sub_mode {
                    JellyfinSubMode::LibraryView(v) => Some(v.clone()),
                    _ => None,
                };
                self.spawn_jellyfin_library_fetch(vid, false)
            }
            Message::JellyfinPageDelta(delta) => {
                self.jf_isolated.page_start =
                    (self.jf_isolated.page_start as i32 + delta).max(0) as u32;
                let vid = match &self.jf_sub_mode {
                    JellyfinSubMode::LibraryView(v) => Some(v.clone()),
                    _ => None,
                };
                self.spawn_jellyfin_library_fetch(vid, false)
            }
            Message::JellyfinSearchChanged(q) => {
                self.jf_isolated.search_query = q;
                Task::none()
            }
            Message::JellyfinSearchSubmit => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let Some(sid) = self.jf_active_server_id.clone() else {
                    return Task::none();
                };
                let Some(prof) = eng.config.jellyfin_server(&sid) else {
                    return Task::none();
                };
                let q = self.jf_isolated.search_query.trim().to_string();
                if q.is_empty() {
                    return Task::none();
                }
                Task::perform(
                    async move {
                        let res = eng
                            .jellyfin
                            .search_server(&prof, &q, 36)
                            .await
                            .map_err(|e| e.to_string());
                        (sid, res)
                    },
                    |(sid, res)| Message::JellyfinSearchLoaded(sid, res),
                )
            }
            Message::JellyfinSearchLoaded(sid, res) => {
                if self.jf_active_server_id.as_deref() != Some(sid.as_str()) {
                    return Task::none();
                }
                match res {
                    Ok(items) => {
                        let urls: Vec<String> = items.iter().map(|m| m.poster.clone()).collect();
                        self.jf_isolated.search_results = items;
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("Jellyfin search error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::JellyfinRemoveServer(sid) => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.remove_jellyfin_server(&sid);
                    self.jf_servers = eng.config.jellyfin_servers();
                    self.jf_active_server_id =
                        self.jf_servers.first().map(|s| s.server_id.clone());
                    if let Some(next_id) = self.jf_active_server_id.clone() {
                        self.jf_sub_mode = JellyfinSubMode::Overview;
                        return self.switch_and_load_jellyfin_server(&next_id);
                    } else {
                        self.jf_isolated = IsolatedJellyfinServerState::default();
                        self.jf_sub_mode = JellyfinSubMode::AddServer;
                    }
                }
                Task::none()
            }

            // Jellyfin Add Server Wizard
            Message::JfWizardLanUrlChanged(v) => {
                self.jf_wizard.lan_url_input = v;
                Task::none()
            }
            Message::JfWizardRemoteUrlChanged(v) => {
                self.jf_wizard.remote_url_input = v;
                Task::none()
            }
            Message::JfWizardUsernameChanged(v) => {
                self.jf_wizard.username_input = v;
                Task::none()
            }
            Message::JfWizardPasswordChanged(v) => {
                self.jf_wizard.password_input = v;
                Task::none()
            }
            Message::JfWizardScanLan => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                self.jf_wizard.scanning_lan = true;
                self.jf_wizard.status_msg = "Broadcasting UDP 7359 discovery...".into();
                Task::perform(
                    async move {
                        eng.jellyfin
                            .discover_lan_servers(1600)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::JfWizardScanLanDone,
                )
            }
            Message::JfWizardScanLanDone(res) => {
                self.jf_wizard.scanning_lan = false;
                match res {
                    Ok(found) => {
                        if let Some(first) = found.first() {
                            if self.jf_wizard.lan_url_input.trim().is_empty() {
                                self.jf_wizard.lan_url_input = first.address.clone();
                            }
                        }
                        self.jf_wizard.status_msg =
                            format!("Discovered {} LAN Jellyfin server(s).", found.len());
                        self.jf_wizard.discovered_lan = found;
                    }
                    Err(e) => {
                        self.jf_wizard.status_msg = format!("LAN scan error: {e}");
                    }
                }
                Task::none()
            }
            Message::JfWizardPickDiscovered(d) => {
                self.jf_wizard.lan_url_input = d.address;
                self.jf_wizard.status_msg = format!("Selected {} — choose QuickConnect or Username Sign-In.", d.name);
                Task::none()
            }
            Message::JfWizardStartQuickConnect => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let url = if !self.jf_wizard.lan_url_input.trim().is_empty() {
                    self.jf_wizard.lan_url_input.trim().to_string()
                } else {
                    self.jf_wizard.remote_url_input.trim().to_string()
                };
                if url.is_empty() {
                    self.jf_wizard.status_msg = "Enter a Primary/LAN or Remote URL first.".into();
                    return Task::none();
                }
                Task::perform(
                    async move {
                        eng.jellyfin
                            .initiate_quick_connect(&url)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::JfWizardQuickConnectStarted,
                )
            }
            Message::JfWizardQuickConnectStarted(res) => {
                match res {
                    Ok(qc) => {
                        self.jf_wizard.status_msg =
                            format!("QuickConnect ready — approve code {} on your phone/web.", qc.code);
                        self.jf_wizard.quick_connect = Some(qc);
                    }
                    Err(e) => {
                        self.jf_wizard.status_msg = format!("QuickConnect error: {e}");
                    }
                }
                Task::none()
            }
            Message::JfWizardQuickConnectPolled(res) => match res {
                Ok(Some(prof)) => {
                    self.jf_wizard.quick_connect = None;
                    self.jf_wizard.status_msg.clear();
                    return self.on_jellyfin_server_authenticated(prof);
                }
                Ok(None) => Task::none(),
                Err(e) => {
                    self.jf_wizard.quick_connect = None;
                    self.jf_wizard.status_msg = format!("QuickConnect poll failed: {e}");
                    Task::none()
                }
            },
            Message::JfWizardLoginPassword => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let lan = self.jf_wizard.lan_url_input.trim().to_string();
                let rem = self.jf_wizard.remote_url_input.trim().to_string();
                let primary = if !lan.is_empty() { lan } else { rem.clone() };
                let rem_opt = if rem.is_empty() { None } else { Some(rem) };
                let user = self.jf_wizard.username_input.trim().to_string();
                let pass = self.jf_wizard.password_input.clone();
                if primary.is_empty() || user.is_empty() {
                    self.jf_wizard.status_msg = "Enter both Server URL and Username.".into();
                    return Task::none();
                }
                self.jf_wizard.status_msg = "Authenticating with Jellyfin...".into();
                Task::perform(
                    async move {
                        eng.jellyfin
                            .authenticate_by_name(&primary, rem_opt.as_deref(), &user, &pass)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::JfWizardLoginDone,
                )
            }
            Message::JfWizardLoginDone(res) => match res {
                Ok(prof) => {
                    self.jf_wizard.password_input.clear();
                    self.jf_wizard.status_msg.clear();
                    self.on_jellyfin_server_authenticated(prof)
                }
                Err(e) => {
                    self.jf_wizard.status_msg = format!("Login failed: {e}");
                    Task::none()
                }
            },

            // ── 4. Streaming (YouTube · Twitch · Odysee · Rumble) ────────────
            Message::StreamingSelectPlatform(plat) => {
                self.streaming_platform = plat;
                self.ensure_streaming_loaded()
            }
            Message::YtSelectTab(tab) => {
                self.yt_tab = tab;
                match tab {
                    YouTubeTab::Home | YouTubeTab::Subscriptions | YouTubeTab::History => {
                        return self.spawn_youtube_feed(false);
                    }
                    YouTubeTab::Playlists => {
                        return self.update(Message::YtLoadPlaylist);
                    }
                    _ => {}
                }
                Task::none()
            }
            Message::YtSwitchAccount(acc_id) => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.set_active_youtube_account_id(&acc_id);
                    self.yt_active_account_id = acc_id;
                    return self.spawn_youtube_feed(false);
                }
                Task::none()
            }
            Message::YtRemoveAccount(acc_id) => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.remove_youtube_account(&acc_id);
                    self.yt_accounts = eng.config.youtube_accounts();
                    self.yt_active_account_id = eng
                        .config
                        .active_youtube_account()
                        .map(|a| a.account_id)
                        .unwrap_or_default();
                    return self.spawn_youtube_feed(false);
                }
                Task::none()
            }
            Message::YtStartDeviceAuth => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                Task::perform(
                    async move {
                        eng.youtube
                            .start_device_code_auth()
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::YtDeviceAuthStarted,
                )
            }
            Message::YtDeviceAuthStarted(res) => {
                match res {
                    Ok(sess) => {
                        self.yt_qr_data = make_qr_data(&sess.verification_url);
                        self.yt_device_session = Some(sess);
                    }
                    Err(e) => {
                        self.toast = Some(format!("YouTube device auth error: {e}"));
                    }
                }
                Task::none()
            }
            Message::YtDeviceAuthPolled(res) => match res {
                Ok(Some(accounts)) => {
                    self.yt_device_session = None;
                    self.yt_qr_data = None;
                    self.hydrate_from_config();
                    self.toast = Some(format!(
                        "YouTube signed in ({} Primary/Brand account(s) discovered)!",
                        accounts.len()
                    ));
                    self.spawn_youtube_feed(false)
                }
                Ok(None) => Task::none(),
                Err(e) => {
                    self.yt_device_session = None;
                    self.yt_qr_data = None;
                    self.toast = Some(format!("YouTube OAuth failed: {e}"));
                    Task::none()
                }
            },
            Message::YtSearchQueryChanged(q) => {
                self.yt_search_query = q.clone();
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                if q.trim().len() >= 2 {
                    return Task::perform(
                        async move {
                            eng.youtube
                                .get_search_suggestions(&q)
                                .await
                                .unwrap_or_default()
                        },
                        Message::YtSuggestionsLoaded,
                    );
                } else {
                    self.yt_suggestions.clear();
                }
                Task::none()
            }
            Message::YtSuggestionsLoaded(list) => {
                self.yt_suggestions = list;
                Task::none()
            }
            Message::YtPickSuggestion(s) => {
                self.yt_search_query = s;
                self.yt_suggestions.clear();
                self.update(Message::YtSearchSubmit)
            }
            Message::YtSearchSubmit => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let q = self.yt_search_query.trim().to_string();
                if q.is_empty() {
                    return Task::none();
                }
                self.streaming_loading = true;
                Task::perform(
                    async move {
                        eng.youtube
                            .search_videos(&q, None)
                            .await
                            .map(|p| (p.items, p.continuation_token))
                            .map_err(|e| e.to_string())
                    },
                    |res| Message::YtFeedLoaded(false, res),
                )
            }
            Message::YtPlaylistIdChanged(pid) => {
                let should_auto_load = pid == "WL" || pid == "LL";
                self.yt_playlist_input = pid;
                if should_auto_load {
                    return self.update(Message::YtLoadPlaylist);
                }
                Task::none()
            }
            Message::YtLoadPlaylist => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let pid = self.yt_playlist_input.trim().to_string();
                if pid.is_empty() {
                    return Task::none();
                }
                self.streaming_loading = true;
                Task::perform(
                    async move {
                        eng.youtube
                            .get_playlist_videos(&pid, None)
                            .await
                            .map(|p| (p.items, p.continuation_token))
                            .map_err(|e| e.to_string())
                    },
                    |res| Message::YtFeedLoaded(false, res),
                )
            }
            Message::YtLoadMoreFeed => self.spawn_youtube_feed(true),
            Message::YtFeedLoaded(append, res) => {
                self.streaming_loading = false;
                match res {
                    Ok((items, cont)) => {
                        let urls: Vec<String> =
                            items.iter().take(24).map(|i| i.thumbnail_url.clone()).collect();
                        let vids: Vec<String> = items
                            .iter()
                            .take(12)
                            .map(|i| i.video_id.clone())
                            .collect();
                        if append {
                            self.yt_feed.extend(items);
                        } else {
                            self.yt_feed = items;
                        }
                        self.yt_continuation = cont;
                        let poster_task = self.queue_poster_fetches(urls);
                        if self.dearrow_enabled {
                            return Task::batch([poster_task, self.spawn_dearrow_batch(vids)]);
                        }
                        poster_task
                    }
                    Err(e) => {
                        self.toast = Some(format!("YouTube feed error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::YtOpenChannel(cid) => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                self.yt_tab = YouTubeTab::Channel;
                self.streaming_loading = true;
                Task::perform(
                    async move { eng.youtube.get_channel_feed(&cid).await.map_err(|e| e.to_string()) },
                    Message::YtChannelLoaded,
                )
            }
            Message::YtChannelLoaded(res) => {
                self.streaming_loading = false;
                match res {
                    Ok(ch) => {
                        let urls: Vec<String> = ch
                            .videos
                            .iter()
                            .take(20)
                            .map(|v| v.thumbnail_url.clone())
                            .collect();
                        self.yt_channel = Some(ch);
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("YouTube channel error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::YtToggleSubscribe(cid, sub) => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                if let Some(ch) = &mut self.yt_channel {
                    if ch.channel_id == cid {
                        ch.is_subscribed = sub;
                    }
                }
                Task::perform(
                    async move {
                        eng.youtube
                            .set_channel_subscription(&cid, sub)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::YtSubscribedDone,
                )
            }
            Message::YtSubscribedDone(_) => Task::none(),
            Message::YtDeArrowBatchLoaded(pairs) => {
                for (vid, title) in pairs {
                    self.dearrow_titles.insert(vid, title);
                }
                Task::none()
            }
            Message::YtToggleSponsorBlock => {
                self.sponsorblock_enabled = !self.sponsorblock_enabled;
                Task::none()
            }
            Message::YtToggleRyd => {
                self.ryd_enabled = !self.ryd_enabled;
                Task::none()
            }
            Message::YtToggleDeArrow => {
                self.dearrow_enabled = !self.dearrow_enabled;
                if self.dearrow_enabled {
                    let vids: Vec<String> = self
                        .yt_feed
                        .iter()
                        .take(12)
                        .map(|v| v.video_id.clone())
                        .collect();
                    return self.spawn_dearrow_batch(vids);
                }
                Task::none()
            }

            // Twitch
            Message::TwitchSelectTab(tab) => {
                self.twitch_tab = tab.clone();
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                self.streaming_loading = true;
                match tab {
                    TwitchTab::TopLive => Task::perform(
                        async move {
                            eng.twitch
                                .get_top_live_streams(28)
                                .await
                                .map_err(|e| e.to_string())
                        },
                        Message::TwitchItemsLoaded,
                    ),
                    TwitchTab::FollowedLive => Task::perform(
                        async move {
                            eng.twitch
                                .get_followed_live_streams()
                                .await
                                .map_err(|e| e.to_string())
                        },
                        Message::TwitchItemsLoaded,
                    ),
                    TwitchTab::Categories => Task::perform(
                        async move {
                            eng.twitch
                                .get_top_categories(30)
                                .await
                                .map_err(|e| e.to_string())
                        },
                        Message::TwitchCategoriesLoaded,
                    ),
                    TwitchTab::CategoryStreams(game) => Task::perform(
                        async move {
                            eng.twitch
                                .get_category_streams(&game, 28)
                                .await
                                .map_err(|e| e.to_string())
                        },
                        Message::TwitchItemsLoaded,
                    ),
                    TwitchTab::Channel | TwitchTab::Search => {
                        self.streaming_loading = false;
                        Task::none()
                    }
                }
            }
            Message::TwitchStartDeviceAuth => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                Task::perform(
                    async move {
                        eng.twitch
                            .start_device_code_auth()
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::TwitchDeviceAuthStarted,
                )
            }
            Message::TwitchDeviceAuthStarted(res) => {
                match res {
                    Ok(sess) => {
                        self.twitch_qr_data = make_qr_data(&sess.verification_url);
                        self.twitch_device_session = Some(sess);
                    }
                    Err(e) => {
                        self.toast = Some(format!("Twitch device auth error: {e}"));
                    }
                }
                Task::none()
            }
            Message::TwitchDeviceAuthPolled(res) => match res {
                Ok(Some(prof)) => {
                    self.twitch_device_session = None;
                    self.twitch_qr_data = None;
                    self.toast = Some(format!("Signed in to Twitch as @{}!", prof.display_name));
                    self.twitch_profile = Some(prof);
                    self.update(Message::TwitchSelectTab(TwitchTab::FollowedLive))
                }
                Ok(None) => Task::none(),
                Err(e) => {
                    self.twitch_device_session = None;
                    self.twitch_qr_data = None;
                    self.toast = Some(format!("Twitch auth error: {e}"));
                    Task::none()
                }
            },
            Message::TwitchLogout => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.set_twitch_profile(None);
                }
                self.twitch_profile = None;
                Task::none()
            }
            Message::TwitchSearchChanged(q) => {
                self.twitch_search_query = q;
                Task::none()
            }
            Message::TwitchSearchSubmit => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let q = self.twitch_search_query.trim().to_string();
                if q.is_empty() {
                    return Task::none();
                }
                self.streaming_loading = true;
                Task::perform(
                    async move { eng.twitch.search_twitch(&q).await.map_err(|e| e.to_string()) },
                    Message::TwitchItemsLoaded,
                )
            }
            Message::TwitchItemsLoaded(res) => {
                self.streaming_loading = false;
                match res {
                    Ok(items) => {
                        let urls: Vec<String> =
                            items.iter().take(24).map(|i| i.thumbnail_url.clone()).collect();
                        self.twitch_items = items;
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("Twitch error: {e}"));
                        Task::none()
                    }
                }
            }
            Message::TwitchCategoriesLoaded(res) => {
                self.streaming_loading = false;
                if let Ok(cats) = res {
                    self.twitch_categories = cats;
                }
                Task::none()
            }
            Message::TwitchOpenChannel(login) => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                self.twitch_tab = TwitchTab::Channel;
                self.streaming_loading = true;
                Task::perform(
                    async move {
                        eng.twitch
                            .get_channel_detail(&login)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::TwitchChannelLoaded,
                )
            }
            Message::TwitchChannelLoaded(res) => {
                self.streaming_loading = false;
                if let Ok(ch) = res {
                    let urls: Vec<String> = ch
                        .videos
                        .iter()
                        .take(16)
                        .map(|v| v.thumbnail_url.clone())
                        .collect();
                    self.twitch_channel = Some(ch);
                    return self.queue_poster_fetches(urls);
                }
                Task::none()
            }

            // Odysee & Rumble
            Message::CreatorSelectSubTab(plat, sub) => {
                match plat {
                    StreamingPlatform::Odysee => self.odysee_tab = sub,
                    StreamingPlatform::Rumble => self.rumble_tab = sub,
                    _ => {}
                }
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                if matches!(sub, CreatorSubTab::Featured | CreatorSubTab::Live) {
                    self.streaming_loading = true;
                    return Task::perform(
                        async move {
                            let res = match (plat, sub) {
                                (StreamingPlatform::Odysee, CreatorSubTab::Featured) => {
                                    eng.odysee.get_featured_videos(1).await
                                }
                                (StreamingPlatform::Odysee, CreatorSubTab::Live) => {
                                    eng.odysee.get_live_streams().await
                                }
                                (StreamingPlatform::Rumble, CreatorSubTab::Featured) => {
                                    eng.rumble.get_featured_videos().await
                                }
                                (StreamingPlatform::Rumble, CreatorSubTab::Live) => {
                                    eng.rumble.get_live_streams().await
                                }
                                _ => Ok(Vec::new()),
                            };
                            (plat, res.map_err(|e| e.to_string()))
                        },
                        |(p, r)| Message::CreatorItemsLoaded(p, r),
                    );
                }
                Task::none()
            }
            Message::CreatorSearchChanged(plat, q) => {
                match plat {
                    StreamingPlatform::Odysee => self.odysee_search_query = q,
                    StreamingPlatform::Rumble => self.rumble_search_query = q,
                    _ => {}
                }
                Task::none()
            }
            Message::CreatorSearchSubmit(plat) => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let q = match plat {
                    StreamingPlatform::Odysee => self.odysee_search_query.trim().to_string(),
                    StreamingPlatform::Rumble => self.rumble_search_query.trim().to_string(),
                    _ => String::new(),
                };
                if q.is_empty() {
                    return Task::none();
                }
                self.streaming_loading = true;
                Task::perform(
                    async move {
                        let res = match plat {
                            StreamingPlatform::Odysee => eng.odysee.search_videos(&q).await,
                            StreamingPlatform::Rumble => eng.rumble.search_videos(&q).await,
                            _ => Ok(Vec::new()),
                        };
                        (plat, res.map_err(|e| e.to_string()))
                    },
                    |(p, r)| Message::CreatorItemsLoaded(p, r),
                )
            }
            Message::CreatorItemsLoaded(plat, res) => {
                self.streaming_loading = false;
                match res {
                    Ok(items) => {
                        let urls: Vec<String> =
                            items.iter().take(24).map(|i| i.thumbnail_url.clone()).collect();
                        match plat {
                            StreamingPlatform::Odysee => self.odysee_items = items,
                            StreamingPlatform::Rumble => self.rumble_items = items,
                            _ => {}
                        }
                        self.queue_poster_fetches(urls)
                    }
                    Err(e) => {
                        self.toast = Some(format!("{} error: {e}", plat.label()));
                        Task::none()
                    }
                }
            }
            Message::ToggleCreatorFollow {
                platform,
                channel_id,
                name,
                handle,
                avatar_url,
            } => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.toggle_creator_follow(CreatorFollowEntry {
                        platform,
                        channel_id,
                        name,
                        handle,
                        avatar_url,
                        added_at_ms: now_epoch_ms(),
                        removed_at_ms: None,
                    });
                    self.creator_follows = eng.config.active_creator_follows(None);
                }
                Task::none()
            }
            Message::PlayCreatorItem(item) => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                self.toast = Some(format!("Resolving ad-free stream for {}...", item.title));
                Task::perform(
                    async move {
                        let res = match item.platform.as_str() {
                            "youtube" => eng.youtube.resolve_stream(&item.video_id).await,
                            "twitch" => {
                                let target = if !item.direct_url_hint.is_empty() {
                                    &item.direct_url_hint
                                } else {
                                    &item.video_id
                                };
                                eng.twitch.resolve_stream(target).await
                            }
                            "odysee" => {
                                let claim = if !item.direct_url_hint.is_empty() {
                                    &item.direct_url_hint
                                } else {
                                    &item.video_id
                                };
                                eng.odysee.resolve_stream(claim).await
                            }
                            "rumble" => {
                                let target = if !item.direct_url_hint.is_empty() {
                                    &item.direct_url_hint
                                } else {
                                    &item.video_id
                                };
                                eng.rumble.resolve_stream(target).await
                            }
                            _ => eng.youtube.resolve_stream(&item.video_id).await,
                        };
                        (item, res.map_err(|e| e.to_string()))
                    },
                    |(it, r)| Message::CreatorStreamResolved(it, r),
                )
            }
            Message::CreatorStreamResolved(item, res) => match res {
                Ok(resolved) => {
                    self.toast = None;
                    let vid = item.video_id.clone();
                    let is_yt = item.platform == "youtube";
                    let source = if is_yt {
                        PlaybackSourceKind::YouTube {
                            video: item.clone(),
                            playback_tracking_url: resolved.playback_tracking_url.clone(),
                            watchtime_tracking_url: resolved.watchtime_tracking_url.clone(),
                        }
                    } else {
                        PlaybackSourceKind::Creator {
                            video: item.clone(),
                        }
                    };

                    let ext_subs: Vec<(String, String)> = resolved
                        .subtitles
                        .iter()
                        .map(|s| (s.url.clone(), s.lang.clone()))
                        .collect();

                    self.start_mpv_playback(
                        resolved.title.clone(),
                        format!(
                            "{} · {} · {}",
                            item.platform.to_uppercase(),
                            resolved.author,
                            resolved.quality_label
                        ),
                        item.thumbnail_url.clone(),
                        resolved.play_url,
                        resolved.request_headers,
                        ext_subs,
                        0.0,
                        source,
                        None,
                        resolved.skip_segments,
                    );

                    if is_yt {
                        if let Some(eng) = self.engine.clone() {
                            let mut extra_tasks = Vec::new();
                            if self.ryd_enabled {
                                let e1 = eng.clone();
                                let v1 = vid.clone();
                                extra_tasks.push(Task::perform(
                                    async move {
                                        let r = e1
                                            .youtube
                                            .fetch_return_youtube_dislike(&v1)
                                            .await
                                            .map_err(|e| e.to_string());
                                        (v1, r)
                                    },
                                    |(v, r)| Message::PlayerRydLoaded(v, r),
                                ));
                            }
                            if self.dearrow_enabled {
                                let e2 = eng;
                                let v2 = vid;
                                extra_tasks.push(Task::perform(
                                    async move {
                                        let t = e2
                                            .youtube
                                            .fetch_dearrow_title(&v2)
                                            .await
                                            .ok()
                                            .flatten();
                                        (v2, t)
                                    },
                                    |(v, t)| Message::PlayerDeArrowLoaded(v, t),
                                ));
                            }
                            return Task::batch(extra_tasks);
                        }
                    }
                    Task::none()
                }
                Err(e) => {
                    self.toast = Some(format!("Stream resolution failed: {e}"));
                    Task::none()
                }
            },

            // ── 5. Search ─────────────────────────────────────────────────────
            Message::SearchQueryChanged(q) => {
                self.search_query = q;
                Task::none()
            }
            Message::SearchSubmit => self.spawn_unified_search(),
            Message::SearchQuickChip(chip) => {
                self.search_query = chip;
                self.spawn_unified_search()
            }
            Message::SearchSelectCatalogFilter(f) => {
                self.search_catalog_filter = f;
                Task::none()
            }
            Message::SearchSelectSwarmTab(t) => {
                self.search_swarm_tab = t;
                Task::none()
            }
            Message::SearchLoadSwarmFeatured(key) => {
                self.search_query.clear();
                self.search_swarm_results.clear();
                self.spawn_swarm_featured(key)
            }
            Message::SearchCatalogDone(res) => {
                self.search_catalog_loading = false;
                if let Ok(items) = res {
                    let urls: Vec<String> =
                        items.iter().take(24).map(|m| m.poster.clone()).collect();
                    self.search_catalog_results = items;
                    return self.queue_poster_fetches(urls);
                }
                Task::none()
            }
            Message::SearchSwarmDone(is_featured, res) => {
                self.search_swarm_loading = false;
                if let Ok(cands) = res {
                    if is_featured {
                        self.search_swarm_featured = cands;
                    } else {
                        self.search_swarm_results = cands;
                    }
                }
                Task::none()
            }

            // ── 6. Library & 7. Kodi ──────────────────────────────────────────
            Message::LibrarySelectFilter(f) => {
                self.library_filter = f;
                self.refresh_library_lists();
                Task::none()
            }
            Message::ToggleFavoriteMeta(meta) => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.toggle_favorite(FavoriteEntry {
                        item_id: meta.id,
                        media_type: meta.media_type,
                        title: meta.name,
                        poster: meta.poster,
                        imdb_rating: meta.imdb_rating,
                        release_info: meta.release_info,
                        added_at_ms: now_epoch_ms(),
                    });
                    self.favorites = eng.config.favorites();
                }
                Task::none()
            }
            Message::KodiRefreshAddons => {
                if let Some(eng) = &self.engine {
                    self.kodi_addons = eng.kodi.scan_installed_video_addons();
                }
                Task::none()
            }
            Message::KodiOpenAddon(addon_id) => {
                let root_url = format!("plugin://{addon_id}/");
                self.kodi_active_addon = Some(addon_id.clone());
                self.kodi_breadcrumbs = vec![(addon_id.clone(), root_url.clone())];
                self.spawn_kodi_exec(addon_id, "Root".into(), root_url, false)
            }
            Message::KodiOpenDirectory {
                addon_id,
                label,
                url,
            } => {
                self.kodi_breadcrumbs.push((label.clone(), url.clone()));
                self.spawn_kodi_exec(addon_id, label, url, false)
            }
            Message::KodiNavigateUp => {
                if self.kodi_breadcrumbs.len() > 1 {
                    self.kodi_breadcrumbs.pop();
                    if let (Some(aid), Some((lbl, url))) = (
                        self.kodi_active_addon.clone(),
                        self.kodi_breadcrumbs.last().cloned(),
                    ) {
                        return self.spawn_kodi_exec(aid, lbl, url, false);
                    }
                }
                Task::none()
            }
            Message::KodiPlayItem {
                addon_id,
                label,
                url,
            } => {
                if url.starts_with("http://") || url.starts_with("https://") {
                    let (clean, headers) = parse_kodi_stream_url(&url);
                    self.start_mpv_playback(
                        label.clone(),
                        format!("Kodi Direct · {addon_id}"),
                        String::new(),
                        clean.clone(),
                        headers,
                        Vec::new(),
                        0.0,
                        PlaybackSourceKind::Kodi {
                            addon_id,
                            label,
                            url: clean,
                        },
                        None,
                        Vec::new(),
                    );
                    return Task::none();
                }
                self.spawn_kodi_exec(addon_id, label, url, true)
            }
            Message::KodiExecuted {
                addon_id,
                label,
                url: _,
                is_play,
                result,
            } => {
                self.kodi_loading = false;
                match result {
                    Ok(resp) => {
                        if !resp.ok && !resp.error.is_empty() {
                            self.kodi_error = Some(resp.error);
                            return Task::none();
                        }
                        self.kodi_error = None;
                        if is_play && !resp.resolved_url.is_empty() {
                            let (clean, headers) = parse_kodi_stream_url(&resp.resolved_url);
                            self.start_mpv_playback(
                                label.clone(),
                                format!("Kodi Resolved · {addon_id}"),
                                String::new(),
                                clean.clone(),
                                headers,
                                Vec::new(),
                                0.0,
                                PlaybackSourceKind::Kodi {
                                    addon_id,
                                    label,
                                    url: clean,
                                },
                                None,
                                Vec::new(),
                            );
                        } else {
                            self.kodi_items = resp.items;
                        }
                    }
                    Err(e) => {
                        self.kodi_error = Some(e);
                    }
                }
                Task::none()
            }

            // ── 8. Passport ↗ (Leeloo Multipass v2 + Stremio + Tuning) ───────
            Message::MpEndpointChanged(v) => {
                self.mp_endpoint_input = v;
                Task::none()
            }
            Message::MpDeviceNameChanged(v) => {
                self.mp_device_name_input = v;
                Task::none()
            }
            Message::MpPassphraseChanged(v) => {
                self.mp_passphrase_input = v;
                Task::none()
            }
            Message::MpStartPairing => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let ep = self.mp_endpoint_input.trim().to_string();
                let dev = self.mp_device_name_input.trim().to_string();
                if ep.is_empty() {
                    self.mp_status_text = "Enter your Leeloo Multipass origin URL first.".into();
                    return Task::none();
                }
                self.mp_status_text = "Generating ephemeral P-256 keypair & starting pairing...".into();
                Task::perform(
                    async move {
                        eng.multipass
                            .start_device_pairing(&ep, &dev)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::MpPairingStarted,
                )
            }
            Message::MpPairingStarted(res) => {
                match res {
                    Ok(sess) => {
                        self.mp_pairing_qr = make_qr_data(&sess.qr_verification_url);
                        self.mp_status_text =
                            "Scan the optical #pk QR code on your phone to unwrap & seal the 32-byte VMK!".into();
                        self.mp_pairing_session = Some(sess);
                    }
                    Err(e) => {
                        self.mp_status_text = format!("Pairing start failed: {e}");
                    }
                }
                Task::none()
            }
            Message::MpPairingPolled(res) => match res {
                Ok(MultipassPairingPollOutcome::Pending) => Task::none(),
                Ok(MultipassPairingPollOutcome::Approved {
                    device_name,
                    e2ee_unlocked,
                    ..
                }) => {
                    self.mp_pairing_session = None;
                    self.mp_pairing_qr = None;
                    self.hydrate_from_config();
                    self.mp_status_text = format!(
                        "Paired `{device_name}`! E2EE VMK sealed in Keychain: {e2ee_unlocked}. Syncing vault..."
                    );
                    self.update(Message::MpSyncVault)
                }
                Ok(MultipassPairingPollOutcome::Denied) => {
                    self.mp_pairing_session = None;
                    self.mp_pairing_qr = None;
                    self.mp_status_text = "Pairing request was denied.".into();
                    Task::none()
                }
                Ok(MultipassPairingPollOutcome::Expired) => {
                    self.mp_pairing_session = None;
                    self.mp_pairing_qr = None;
                    self.mp_status_text = "Pairing session expired. Click Pair to generate a fresh QR.".into();
                    Task::none()
                }
                Err(e) => {
                    self.mp_status_text = format!("Pairing poll error: {e}");
                    Task::none()
                }
            },
            Message::MpSyncVault => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let pass = if self.mp_passphrase_input.trim().is_empty() {
                    None
                } else {
                    Some(self.mp_passphrase_input.trim().to_string())
                };
                self.mp_syncing = true;
                self.mp_status_text = "Running E2EE CAS Pull -> CRDT Merge -> Push...".into();
                Task::perform(
                    async move {
                        let pull_rep = eng
                            .multipass
                            .sync_from_endpoint(pass.as_deref(), &eng.stremio, &eng.jellyfin)
                            .await
                            .map_err(|e| e.to_string())?;
                        if pull_rep.vault_locked_needs_passphrase {
                            return Ok((pull_rep, 0));
                        }
                        let pushed_rev = eng
                            .multipass
                            .push_current_setup_to_vault(pass.as_deref())
                            .await
                            .unwrap_or(pull_rep.revision);
                        Ok((pull_rep, pushed_rev))
                    },
                    Message::MpSyncDone,
                )
            }
            Message::MpSyncDone(res) => {
                self.mp_syncing = false;
                match res {
                    Ok((mut rep, pushed_rev)) => {
                        if pushed_rev > 0 {
                            rep.revision = pushed_rev;
                        }
                        if rep.vault_locked_needs_passphrase {
                            self.mp_status_text =
                                "Vault is locked without paired VMK — enter Recovery Passphrase above and click Sync Vault.".into();
                        } else {
                            self.mp_status_text =
                                format!("Vault synced cleanly at revision {}!", rep.revision);
                        }
                        self.mp_last_report = Some(rep);
                        self.hydrate_from_config();
                        return self.spawn_board_refresh();
                    }
                    Err(e) => {
                        self.mp_status_text = format!("Vault sync error: {e}");
                    }
                }
                Task::none()
            }
            Message::MpDisconnect => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.clear_multipass_profile();
                    self.hydrate_from_config();
                    self.mp_status_text = "Disconnected Multipass profile & cleared local VMK.".into();
                }
                Task::none()
            }

            // Stremio QR & Addons
            Message::StremioStartQr => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                Task::perform(
                    async move {
                        eng.stremio
                            .create_qr_link_session()
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::StremioQrStarted,
                )
            }
            Message::StremioQrStarted(res) => {
                match res {
                    Ok(sess) => {
                        self.stremio_qr_data = make_qr_data(&sess.link);
                        self.stremio_qr_session = Some(sess);
                    }
                    Err(e) => {
                        self.toast = Some(format!("Stremio QR error: {e}"));
                    }
                }
                Task::none()
            }
            Message::StremioQrPolled(res) => match res {
                Ok(Some(summary)) => {
                    self.stremio_qr_session = None;
                    self.stremio_qr_data = None;
                    self.hydrate_from_config();
                    self.toast = Some(format!(
                        "Stremio linked ({}) · {} addons & {} history items synced!",
                        summary.email, summary.synced_addons_count, summary.synced_history_count
                    ));
                    self.spawn_board_refresh()
                }
                Ok(None) => Task::none(),
                Err(e) => {
                    self.stremio_qr_session = None;
                    self.stremio_qr_data = None;
                    self.toast = Some(format!("Stremio link error: {e}"));
                    Task::none()
                }
            },
            Message::StremioSyncNow => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let (Some(auth_key), _, _, _) = eng.config.stremio_auth() else {
                    return Task::none();
                };
                Task::perform(
                    async move {
                        eng.stremio
                            .sync_account_everything(&auth_key)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::StremioSyncDone,
                )
            }
            Message::StremioSyncDone(res) => {
                match res {
                    Ok(s) => {
                        self.hydrate_from_config();
                        self.toast = Some(format!(
                            "Synced Stremio ({}) · {} addons · {} history items.",
                            s.email, s.synced_addons_count, s.synced_history_count
                        ));
                        return self.spawn_board_refresh();
                    }
                    Err(e) => {
                        self.toast = Some(format!("Stremio sync failed: {e}"));
                    }
                }
                Task::none()
            }
            Message::StremioLogout => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.set_stremio_auth(None, "", None);
                    self.hydrate_from_config();
                }
                Task::none()
            }
            Message::AddonUrlInputChanged(v) => {
                self.addon_url_input = v;
                Task::none()
            }
            Message::InstallAddonSubmit => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let url = self.addon_url_input.trim().to_string();
                if url.is_empty() {
                    return Task::none();
                }
                Task::perform(
                    async move {
                        eng.stremio
                            .install_addon_from_url(&url)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::AddonInstalled,
                )
            }
            Message::AddonInstalled(res) => {
                match res {
                    Ok(addon) => {
                        self.addon_url_input.clear();
                        self.hydrate_from_config();
                        self.toast = Some(format!("Installed addon: {} v{}", addon.name, addon.version));
                        return self.spawn_board_refresh();
                    }
                    Err(e) => {
                        self.toast = Some(format!("Addon install failed: {e}"));
                    }
                }
                Task::none()
            }
            Message::RemoveAddon(id) => {
                if let Some(eng) = &self.engine {
                    let _ = eng.config.remove_addon(&id);
                    self.hydrate_from_config();
                }
                Task::none()
            }
            Message::SetSwarmFilter(f) => {
                self.swarm_filter = f;
                if let Some(eng) = &self.engine {
                    let _ = eng.config.set_swarm_filter(f);
                }
                Task::none()
            }
            Message::TorrentMaxCacheChanged(v) => {
                self.max_cache_input = v;
                Task::none()
            }
            Message::TorrentWindowAheadChanged(v) => {
                self.window_ahead_input = v;
                Task::none()
            }
            Message::TorrentPrebufferChanged(v) => {
                self.prebuffer_input = v;
                Task::none()
            }
            Message::TorrentMaxConnsChanged(v) => {
                self.max_conns_input = v;
                Task::none()
            }
            Message::SaveTorrentConfig => {
                if let Some(eng) = &self.engine {
                    let cfg = TorrentEngineConfig {
                        max_cache_mb: self.max_cache_input.trim().parse().unwrap_or(512),
                        window_ahead_mb: self.window_ahead_input.trim().parse().unwrap_or(48),
                        prebuffer_mb: self.prebuffer_input.trim().parse().unwrap_or(12),
                        max_connections: self.max_conns_input.trim().parse().unwrap_or(120),
                    };
                    let _ = eng.config.set_torrent_config(cfg);
                    self.hydrate_from_config();
                    self.toast = Some("Saved P2P Torrent Engine settings.".into());
                }
                Task::none()
            }
            Message::TorznabUrlChanged(v) => {
                self.torznab_url_input = v;
                Task::none()
            }
            Message::TorznabKeyChanged(v) => {
                self.torznab_key_input = v;
                Task::none()
            }
            Message::SaveTorznabConfig => {
                if let Some(eng) = &self.engine {
                    let existing_key = eng.config.torznab_config().1;
                    let key_to_save = if self.torznab_key_input.trim().is_empty() {
                        existing_key
                    } else {
                        self.torznab_key_input.trim().to_string()
                    };
                    let _ = eng
                        .config
                        .set_torznab_config(&self.torznab_url_input, &key_to_save);
                    self.torznab_key_input.clear();
                    self.hydrate_from_config();
                    self.toast = Some("Saved Torznab indexer configuration.".into());
                }
                Task::none()
            }

            // ── Detail & Stream Resolution ────────────────────────────────────
            Message::OpenMetaDetail(meta) => {
                let saved = self
                    .engine
                    .as_ref()
                    .and_then(|e| {
                        e.config
                            .watch_history()
                            .into_iter()
                            .find(|h| h.item_id == meta.id)
                    });
                let vid = saved.as_ref().map(|h| h.video_id.clone());
                let ep_title = saved
                    .as_ref()
                    .map(|h| h.episode_title.clone())
                    .unwrap_or_default();
                let pos_ms = saved.as_ref().map(|h| h.position_ms).unwrap_or(0);
                self.open_meta_detail_with_resume(meta, vid, ep_title, pos_ms)
            }
            Message::DetailMetaLoaded(res) => {
                if let Ok((mut full_meta, jf_eps)) = res {
                    if !jf_eps.is_empty() {
                        full_meta.videos = jf_eps;
                    }
                    let poster = full_meta.poster.clone();
                    let mut fetch_ep_streams: Option<(String, String)> = None;

                    if let Some(det) = &mut self.detail {
                        if det.meta.id == full_meta.id {
                            det.loading_meta = false;
                            if !full_meta.videos.is_empty()
                                && (det.selected_video_id.is_empty()
                                    || det.selected_video_id == full_meta.id)
                            {
                                let first_ep = full_meta
                                    .videos
                                    .iter()
                                    .find(|e| e.season > 0)
                                    .or_else(|| full_meta.videos.first())
                                    .cloned();
                                if let Some(ep) = first_ep {
                                    det.selected_season = ep.season;
                                    det.selected_video_id = ep.id.clone();
                                    det.selected_episode_title =
                                        format!("S{:02}E{:02} · {}", ep.season, ep.episode, ep.title);
                                    if !JellyfinIds::is_jellyfin_id(&full_meta.id) {
                                        fetch_ep_streams =
                                            Some((full_meta.media_type.clone(), ep.id));
                                    }
                                }
                            } else if let Some(cur_ep) = full_meta
                                .videos
                                .iter()
                                .find(|e| e.id == det.selected_video_id)
                            {
                                det.selected_season = cur_ep.season;
                                if det.selected_episode_title.is_empty() {
                                    det.selected_episode_title = format!(
                                        "S{:02}E{:02} · {}",
                                        cur_ep.season, cur_ep.episode, cur_ep.title
                                    );
                                }
                            }
                            det.meta = full_meta;
                        }
                    }

                    let mut tasks = vec![self.queue_poster_fetches(vec![poster])];
                    if let Some((mtype, vid)) = fetch_ep_streams {
                        tasks.push(self.spawn_detail_streams_fetch(mtype, vid));
                    }
                    return Task::batch(tasks);
                }
                Task::none()
            }
            Message::DetailSelectSeason(s) => {
                if let Some(det) = &mut self.detail {
                    det.selected_season = s;
                }
                Task::none()
            }
            Message::DetailSelectEpisode(ep) => {
                let mut stream_req = None;
                if let Some(det) = &mut self.detail {
                    det.selected_season = ep.season;
                    det.selected_video_id = ep.id.clone();
                    det.selected_episode_title =
                        format!("S{:02}E{:02} · {}", ep.season, ep.episode, ep.title);
                    det.resume_position_ms = 0;
                    if !JellyfinIds::is_jellyfin_id(&det.meta.id) {
                        det.loading_streams = true;
                        stream_req = Some((det.meta.media_type.clone(), ep.id));
                    }
                }
                if let Some((mtype, vid)) = stream_req {
                    return self.spawn_detail_streams_fetch(mtype, vid);
                }
                Task::none()
            }
            Message::DetailSelectStreamFilter(f) => {
                if let Some(det) = &mut self.detail {
                    det.stream_filter = f;
                }
                Task::none()
            }
            Message::DetailStreamsLoaded(res) => {
                if let (Some(det), Some(eng)) = (&mut self.detail, &self.engine) {
                    det.loading_streams = false;
                    if let Ok((streams, subs)) = res {
                        if let Some(prev_ctx) = eng.config.last_stream_context(&det.meta.id) {
                            det.ranked_streams =
                                StreamSimilarityMatcher::rank_streams(&prev_ctx, streams.clone());
                        } else {
                            det.ranked_streams.clear();
                        }
                        det.streams = streams;
                        det.subtitles = subs;
                    }
                }
                Task::none()
            }
            Message::DetailQuickPlayBest => {
                let Some(det) = self.detail.clone() else {
                    return Task::none();
                };
                let chosen = det
                    .ranked_streams
                    .first()
                    .map(|r| r.candidate.clone())
                    .or_else(|| det.streams.first().cloned());
                let Some(cand) = chosen else {
                    return Task::none();
                };
                let vid = if det.selected_video_id.is_empty() {
                    det.meta.id.clone()
                } else {
                    det.selected_video_id.clone()
                };
                self.update(Message::PlayStreamCandidate {
                    candidate: cand,
                    meta: Some(det.meta),
                    video_id: Some(vid),
                    episode_title: Some(det.selected_episode_title),
                    resume_ms: det.resume_position_ms,
                })
            }
            Message::PlayStreamCandidate {
                candidate,
                meta,
                video_id,
                episode_title,
                resume_ms,
            } => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };

                // Persist stream selection context for Series Binge Autoplay!
                let stream_ctx = StreamSimilarityMatcher::build_context_from_stream(&candidate);
                if let Some(m) = &meta {
                    let _ = eng.config.record_stream_selection(&m.id, stream_ctx.clone());
                }

                let subs = self
                    .detail
                    .as_ref()
                    .map(|d| d.subtitles.clone())
                    .unwrap_or_default();

                if let Some(direct_url) = candidate
                    .direct_url
                    .clone()
                    .filter(|s| !s.trim().is_empty())
                {
                    let (title, sub_line, poster, source) = build_stream_source_descriptor(
                        &candidate,
                        meta,
                        video_id,
                        episode_title,
                        stream_ctx,
                    );
                    let ext_subs = subs.into_iter().map(|s| (s.url, s.lang)).collect();
                    self.start_mpv_playback(
                        title,
                        sub_line,
                        poster,
                        direct_url,
                        candidate.request_headers.clone(),
                        ext_subs,
                        (resume_ms as f64) / 1000.0,
                        source,
                        None,
                        Vec::new(),
                    );
                    return Task::none();
                }

                // P2P Torrent Loopback Streaming via `LocalMediaServer` (`librqbit` v8)
                let Some(magnet) = candidate.to_magnet_uri() else {
                    self.toast = Some("Stream candidate has neither direct URL nor infoHash.".into());
                    return Task::none();
                };
                let fidx = candidate.file_idx;
                self.toast = Some(format!(
                    "Starting P2P BitTorrent loopback session for {}...",
                    candidate.title
                ));
                Task::perform(
                    async move {
                        let res = eng
                            .media_server
                            .start_torrent_stream(&magnet, fidx)
                            .await
                            .map_err(|e| e.to_string());
                        (candidate, meta, video_id, episode_title, subs, resume_ms, res)
                    },
                    |(candidate, meta, video_id, episode_title, subtitles, resume_ms, result)| {
                        Message::TorrentSessionStarted {
                            candidate,
                            meta,
                            video_id,
                            episode_title,
                            subtitles,
                            resume_ms,
                            result,
                        }
                    },
                )
            }
            Message::TorrentSessionStarted {
                candidate,
                meta,
                video_id,
                episode_title,
                subtitles,
                resume_ms,
                result,
            } => match result {
                Ok(sess) => {
                    self.toast = None;
                    let stream_ctx = StreamSimilarityMatcher::build_context_from_stream(&candidate);
                    let (title, sub_line, poster, source) = build_stream_source_descriptor(
                        &candidate,
                        meta,
                        video_id,
                        episode_title,
                        stream_ctx,
                    );
                    let ext_subs = subtitles.into_iter().map(|s| (s.url, s.lang)).collect();
                    self.start_mpv_playback(
                        title,
                        format!("{sub_line} · {}", sess.file_name),
                        poster,
                        sess.play_url.clone(),
                        HashMap::new(),
                        ext_subs,
                        (resume_ms as f64) / 1000.0,
                        source,
                        Some(sess),
                        Vec::new(),
                    );
                    Task::none()
                }
                Err(e) => {
                    self.toast = Some(format!("Torrent stream init error: {e}"));
                    Task::none()
                }
            },
            Message::PlayJellyfinItem {
                server_id,
                meta,
                video_id,
                episode_title,
                resume_ms,
            } => {
                let Some(eng) = self.engine.clone() else {
                    return Task::none();
                };
                let Some(prof) = eng.config.jellyfin_server(&server_id) else {
                    self.toast = Some("Jellyfin server profile not found.".into());
                    return Task::none();
                };
                self.toast = Some(format!("Resolving Jellyfin playback for {}...", meta.name));
                Task::perform(
                    async move {
                        let sess_res = eng.jellyfin.resolve_playback_session(&prof, &video_id).await;
                        let skips = eng
                            .jellyfin
                            .fetch_skip_segments(&prof, &video_id)
                            .await
                            .unwrap_or_default();
                        let res = sess_res.map(|s| (s, skips)).map_err(|e| e.to_string());
                        (server_id, meta, video_id, episode_title, resume_ms, res)
                    },
                    |(server_id, meta, video_id, episode_title, resume_ms, result)| {
                        Message::JellyfinPlaybackResolved {
                            server_id,
                            meta,
                            video_id,
                            episode_title,
                            resume_ms,
                            result,
                        }
                    },
                )
            }
            Message::JellyfinPlaybackResolved {
                server_id,
                meta,
                video_id,
                episode_title,
                resume_ms,
                result,
            } => match result {
                Ok((sess, skips)) => {
                    self.toast = None;
                    let start_ms = if resume_ms > 0 {
                        resume_ms
                    } else {
                        sess.resume_position_ms
                    };
                    let ext_subs: Vec<(String, String)> = sess
                        .external_subtitles
                        .iter()
                        .map(|s| (s.url.clone(), s.lang.clone()))
                        .collect();
                    let sub_line = if episode_title.is_empty() {
                        format!("Jellyfin {}", sess.play_method)
                    } else {
                        format!("{} · Jellyfin {}", episode_title, sess.play_method)
                    };

                    if let Some(eng) = self.engine.clone() {
                        if let Some(prof) = eng.config.jellyfin_server(&server_id) {
                            let vid_c = video_id.clone();
                            let msid = sess.media_source_id.clone();
                            let psid = sess.play_session_id.clone();
                            tokio::spawn(async move {
                                let _ = eng
                                    .jellyfin
                                    .report_playback_start(&prof, &vid_c, &msid, &psid, start_ms)
                                    .await;
                            });
                        }
                    }

                    self.start_mpv_playback(
                        meta.name.clone(),
                        sub_line,
                        meta.poster.clone(),
                        sess.play_url.clone(),
                        sess.request_headers.clone(),
                        ext_subs,
                        (start_ms as f64) / 1000.0,
                        PlaybackSourceKind::Jellyfin {
                            server_id,
                            meta,
                            video_id,
                            episode_title,
                            session: sess,
                        },
                        None,
                        skips,
                    );
                    Task::none()
                }
                Err(e) => {
                    self.toast = Some(format!("Jellyfin playback error: {e}"));
                    Task::none()
                }
            },

            // ── Active Player Controls, Binge Autoplay, & Credits Gallery ─────
            Message::FastPlayerTick => self.handle_fast_player_tick(),
            Message::SlowBackgroundTick => self.handle_slow_poll_tick(),

            Message::PlayerTogglePause => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.toggle_pause();
                }
                Task::none()
            }
            Message::PlayerScrubPreview(sec) => {
                if let Some(p) = &mut self.player {
                    let cur = p.effective_display_position_sec();
                    if !p.is_scrubbing {
                        p.scrub_origin_sec = cur;
                    }
                    let max_dur = if p.telemetry.duration_sec > 0.5 {
                        p.telemetry.duration_sec
                    } else {
                        (sec as f64).max(1.0)
                    };
                    p.is_scrubbing = true;
                    p.scrub_via_keyboard = false;
                    p.scrub_target_sec = (sec as f64).clamp(0.0, max_dur);
                    p.last_scrub_input_ms = now_epoch_ms();
                }
                Task::none()
            }
            Message::PlayerScrubCommit => {
                if let Some(target) = self
                    .player
                    .as_ref()
                    .filter(|p| p.is_scrubbing)
                    .map(|p| p.scrub_target_sec)
                {
                    self.commit_active_player_seek(target);
                }
                Task::none()
            }
            Message::PlayerStepKeyboardScrub(direction) => {
                if let Some(p) = &mut self.player {
                    let now_ms = now_epoch_ms();
                    let cur = p.effective_display_position_sec();
                    if !p.is_scrubbing {
                        p.is_scrubbing = true;
                        p.scrub_origin_sec = cur;
                        p.scrub_target_sec = cur;
                        p.scrub_repeat_count = 0;
                    } else if now_ms.saturating_sub(p.last_scrub_input_ms) < 550 {
                        p.scrub_repeat_count = p.scrub_repeat_count.saturating_add(1);
                    } else {
                        p.scrub_repeat_count = 0;
                    }
                    p.scrub_via_keyboard = true;
                    p.last_scrub_input_ms = now_ms;

                    // Accelerating step curve: ±10s -> ±30s -> ±60s -> ±120s -> ±300s
                    let dur = p.telemetry.duration_sec;
                    let step_sec = match p.scrub_repeat_count {
                        0..=2 => {
                            if dur > 0.0 && dur < 300.0 {
                                5.0
                            } else {
                                10.0
                            }
                        }
                        3..=6 => 30.0,
                        7..=12 => 60.0,
                        13..=20 => 120.0,
                        _ => 300.0,
                    };
                    let sign = if direction >= 0.0 { 1.0 } else { -1.0 };
                    let next = p.scrub_target_sec + sign * step_sec;
                    p.scrub_target_sec = if dur > 0.5 {
                        next.clamp(0.0, dur)
                    } else {
                        next.max(0.0)
                    };
                }
                Task::none()
            }
            Message::PlayerCancelScrub => {
                if let Some(p) = &mut self.player {
                    p.is_scrubbing = false;
                    p.scrub_via_keyboard = false;
                    p.scrub_repeat_count = 0;
                }
                Task::none()
            }
            Message::PlayerSeekAbsolute(sec) => {
                self.commit_active_player_seek(sec as f64);
                Task::none()
            }
            Message::PlayerSeekRelative(delta) => {
                if let Some(cur) = self
                    .player
                    .as_ref()
                    .map(|p| p.effective_display_position_sec())
                {
                    self.commit_active_player_seek((cur + delta).max(0.0));
                } else if let Some(mpv) = &self.mpv_player {
                    mpv.seek_relative(delta);
                }
                Task::none()
            }
            Message::PlayerVolumeChanged(vol) => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.set_volume(vol as f64);
                }
                Task::none()
            }
            Message::PlayerToggleMute => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.toggle_mute();
                }
                Task::none()
            }
            Message::PlayerSetSpeed(spd) => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.set_speed(spd);
                }
                Task::none()
            }
            Message::PlayerToggleFullscreen => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.toggle_fullscreen();
                }
                Task::none()
            }
            Message::PlayerSwitchRenderMode(mode) => {
                self.mpv_render_mode = mode;
                if let Some(mpv) = &mut self.mpv_player {
                    if let Err(e) = mpv.switch_mode(mode) {
                        self.toast = Some(format!("Render mode switch error: {e}"));
                    }
                }
                Task::none()
            }
            Message::PlayerSelectAudio(id) => {
                if let Some(mpv) = &mut self.mpv_player {
                    mpv.select_audio_track(id);
                }
                Task::none()
            }
            Message::PlayerSelectSubtitle(id_opt) => {
                if let Some(mpv) = &mut self.mpv_player {
                    mpv.select_subtitle_track(id_opt);
                }
                Task::none()
            }
            Message::PlayerSelectVideo(id) => {
                if let Some(mpv) = &mut self.mpv_player {
                    mpv.select_video_track(id);
                }
                Task::none()
            }
            Message::PlayerSelectChapter(idx) => {
                if let Some(mpv) = &self.mpv_player {
                    mpv.select_chapter(idx);
                }
                if let Some(p) = &mut self.player {
                    p.is_scrubbing = false;
                    p.pending_seek_target_sec = None;
                }
                Task::none()
            }
            Message::PlayerStopAndClose => {
                self.stop_active_player(true);
                Task::none()
            }
            Message::PlayerConfirmSkipSegment(seg) => {
                let target_sec = (seg.end_ms as f64) / 1000.0;
                if let Some(p) = &mut self.player {
                    p.active_skip_prompt = None;
                }
                self.commit_active_player_seek(target_sec);
                Task::none()
            }
            Message::PlayerUndoSponsorSkip => {
                let mut seek_back = None;
                if let Some(p) = &mut self.player {
                    if let Some((_, prev_sec)) = p.undo_skip_target.take() {
                        seek_back = Some(prev_sec);
                    }
                }
                if let Some(sec) = seek_back {
                    self.commit_active_player_seek(sec);
                }
                Task::none()
            }
            Message::PlayerRydLoaded(vid, res) => {
                if let (Some(p), Ok(votes)) = (&mut self.player, res) {
                    if matches!(&p.source, PlaybackSourceKind::YouTube { video, .. } if video.video_id == vid)
                    {
                        p.ryd_votes = Some(votes);
                    }
                }
                Task::none()
            }
            Message::PlayerDeArrowLoaded(vid, title_opt) => {
                if let (Some(p), Some(clean_t)) = (&mut self.player, title_opt) {
                    if matches!(&p.source, PlaybackSourceKind::YouTube { video, .. } if video.video_id == vid)
                    {
                        p.dearrow_title = Some(clean_t);
                    }
                }
                Task::none()
            }
            Message::BingeNextStreamsResolved(ep_id, res) => {
                if let Some(p) = &mut self.player {
                    if let Some(binge) = &mut p.binge_card {
                        if binge.next_episode.id == ep_id {
                            binge.resolving = false;
                            if let Ok(matched) = res {
                                binge.matched_stream = matched;
                            }
                        }
                    }
                }
                Task::none()
            }
            Message::BingePlayNextNow => {
                let Some(p) = self.player.as_ref() else {
                    return Task::none();
                };
                let Some(binge) = p.binge_card.clone() else {
                    return Task::none();
                };
                let next_ep = binge.next_episode;
                let ep_title = format!(
                    "S{:02}E{:02} · {}",
                    next_ep.season, next_ep.episode, next_ep.title
                );

                match &p.source {
                    PlaybackSourceKind::Stremio { meta, .. } => {
                        if let Some(rk) = binge.matched_stream {
                            let m_clone = meta.clone();
                            return self.update(Message::PlayStreamCandidate {
                                candidate: rk.candidate,
                                meta: Some(m_clone),
                                video_id: Some(next_ep.id),
                                episode_title: Some(ep_title),
                                resume_ms: 0,
                            });
                        }
                    }
                    PlaybackSourceKind::Jellyfin {
                        server_id, meta, ..
                    } => {
                        let sid = server_id.clone();
                        let m_clone = meta.clone();
                        return self.update(Message::PlayJellyfinItem {
                            server_id: sid,
                            meta: m_clone,
                            video_id: next_ep.id,
                            episode_title: ep_title,
                            resume_ms: 0,
                        });
                    }
                    _ => {}
                }
                Task::none()
            }
            Message::BingeDismissWatchCredits => {
                if let Some(p) = &mut self.player {
                    if let Some(b) = &mut p.binge_card {
                        b.dismissed = true;
                        b.countdown_active = false;
                    }
                }
                Task::none()
            }
            Message::PlayerToggleCreditsDrawer => {
                let mut req_task = Task::none();
                if let Some(p) = &mut self.player {
                    p.show_credits_drawer = !p.show_credits_drawer;
                    if p.show_credits_drawer && !p.credits_requested {
                        p.credits_requested = true;
                        req_task = self.spawn_credits_recommendations();
                    }
                }
                req_task
            }
            Message::CreditsRecommendationsLoaded(res) => {
                if let Ok(items) = res {
                    let urls: Vec<String> = items.iter().map(|m| m.poster.clone()).collect();
                    if let Some(p) = &mut self.player {
                        p.credits_recommendations = items;
                    }
                    return self.queue_poster_fetches(urls);
                }
                Task::none()
            }
            Message::CreditsYtRecommendationsLoaded(res) => {
                if let Ok(items) = res {
                    let urls: Vec<String> =
                        items.iter().map(|i| i.thumbnail_url.clone()).collect();
                    if let Some(p) = &mut self.player {
                        p.yt_recommendations = items;
                    }
                    return self.queue_poster_fetches(urls);
                }
                Task::none()
            }
        }
    }

    pub fn view(&self) -> Element<'_, Message> {
        let nav = views::top_navigation_bar(
            self.active_tab,
            self.overlay,
            self.player.as_ref().map(|p| p.title.as_str()),
            self.mp_profile.is_paired(),
        );

        let main_view: Element<'_, Message> = match self.overlay {
            ActiveOverlay::Player => {
                if let Some(p) = &self.player {
                    views::detail_player::view_player(
                        p,
                        self.mpv_render_mode,
                        self.mpv_api.is_some(),
                        &self.posters,
                        self.dearrow_enabled,
                        &self.dearrow_titles,
                    )
                } else {
                    self.render_active_tab()
                }
            }
            ActiveOverlay::Detail => {
                if let Some(det) = &self.detail {
                    let is_fav = self.favorites.iter().any(|f| f.item_id == det.meta.id);
                    views::detail_player::view_detail(det, is_fav, &self.posters)
                } else {
                    self.render_active_tab()
                }
            }
            ActiveOverlay::None => self.render_active_tab(),
        };

        let mut root_col = column![nav];
        if let Some(msg) = &self.toast {
            root_col = root_col.push(views::toast_banner(msg));
        }
        root_col = root_col.push(main_view);

        container(root_col)
            .width(Length::Fill)
            .height(Length::Fill)
            .style(root_canvas_style)
            .into()
    }

    fn render_active_tab(&self) -> Element<'_, Message> {
        match self.active_tab {
            NavTab::Board => views::board::view_board(
                &self.board_search_input,
                &self.continue_watching,
                &self.board_rows,
                self.board_loading,
                self.stremio_linked,
                &self.stremio_email,
                self.jf_servers.len(),
                self.yt_accounts.len(),
                &self.posters,
            ),
            NavTab::Discover => views::discover::view_discover(
                &self.discover_media_types,
                &self.discover_active_type,
                &self.discover_catalogs,
                self.discover_catalog_idx,
                &self.discover_genre,
                &self.discover_year,
                self.discover_sort,
                self.discover_skip,
                &self.discover_items,
                self.discover_loading,
                &self.posters,
            ),
            NavTab::Jellyfin => views::jellyfin::view_jellyfin(
                &self.jf_servers,
                self.jf_active_server_id.as_deref(),
                &self.jf_sub_mode,
                &self.jf_isolated,
                &self.jf_wizard,
                &self.posters,
            ),
            NavTab::Streaming => views::streaming::view_streaming(
                self.streaming_platform,
                &self.yt_accounts,
                &self.yt_active_account_id,
                self.yt_device_session.as_ref(),
                self.yt_qr_data.as_ref(),
                self.yt_tab,
                &self.yt_playlist_input,
                &self.yt_search_query,
                &self.yt_suggestions,
                &self.yt_feed,
                self.yt_continuation.is_some(),
                self.yt_channel.as_ref(),
                self.sponsorblock_enabled,
                self.ryd_enabled,
                self.dearrow_enabled,
                &self.dearrow_titles,
                self.twitch_profile.as_ref(),
                self.twitch_device_session.as_ref(),
                self.twitch_qr_data.as_ref(),
                &self.twitch_tab,
                &self.twitch_items,
                &self.twitch_categories,
                self.twitch_channel.as_ref(),
                &self.twitch_search_query,
                self.odysee_tab,
                &self.odysee_items,
                &self.odysee_search_query,
                self.rumble_tab,
                &self.rumble_items,
                &self.rumble_search_query,
                &self.creator_follows,
                self.streaming_loading,
                &self.posters,
            ),
            NavTab::Search => views::search::view_search(
                &self.search_query,
                self.search_catalog_filter,
                &self.search_catalog_results,
                self.search_catalog_loading,
                self.search_swarm_tab,
                &self.search_swarm_results,
                &self.search_swarm_featured,
                self.search_swarm_loading,
                &self.posters,
            ),
            NavTab::Library => views::library_kodi::view_library(
                self.library_filter,
                &self.favorites,
                &self.watch_history,
                &self.posters,
            ),
            NavTab::Kodi => {
                let kodi_dir = self
                    .engine
                    .as_ref()
                    .map(|e| {
                        e.config
                            .kodi_env_dir()
                            .join("addons")
                            .to_string_lossy()
                            .into_owned()
                    })
                    .unwrap_or_else(|| "~/.local/share/brootlite/kodi_env/addons".into());
                views::library_kodi::view_kodi(
                    &self.kodi_addons,
                    self.kodi_active_addon.as_deref(),
                    &self.kodi_breadcrumbs,
                    &self.kodi_items,
                    self.kodi_loading,
                    self.kodi_error.as_deref(),
                    &kodi_dir,
                )
            }
            NavTab::Passport => {
                let data_dir = self
                    .engine
                    .as_ref()
                    .map(|e| e.config.data_dir().to_string_lossy().into_owned())
                    .unwrap_or_default();
                let media_url = self
                    .engine
                    .as_ref()
                    .map(|e| e.media_server.base_url())
                    .unwrap_or_default();
                views::passport::view_passport(
                    &self.mp_profile,
                    &self.mp_endpoint_input,
                    &self.mp_device_name_input,
                    &self.mp_passphrase_input,
                    self.mp_pairing_session.as_ref(),
                    self.mp_pairing_qr.as_ref(),
                    &self.mp_status_text,
                    self.mp_last_report.as_ref(),
                    self.mp_syncing,
                    self.stremio_linked,
                    &self.stremio_email,
                    self.stremio_qr_session.as_ref(),
                    self.stremio_qr_data.as_ref(),
                    &self.addon_url_input,
                    &self.installed_addons,
                    self.sponsorblock_enabled,
                    self.ryd_enabled,
                    self.dearrow_enabled,
                    self.swarm_filter,
                    &self.max_cache_input,
                    &self.window_ahead_input,
                    &self.prebuffer_input,
                    &self.max_conns_input,
                    &self.torznab_url_input,
                    &self.torznab_key_input,
                    self.torznab_has_key,
                    &data_dir,
                    &media_url,
                    &self.mpv_status_line,
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal Helpers & Async Task Spawners
    // ─────────────────────────────────────────────────────────────────────────

    fn hydrate_from_config(&mut self) {
        let Some(eng) = &self.engine else {
            return;
        };
        let (st_key, st_email, _, _) = eng.config.stremio_auth();
        self.stremio_linked = st_key.is_some();
        self.stremio_email = st_email;
        self.installed_addons = eng.config.addons();
        self.favorites = eng.config.favorites();
        self.watch_history = eng.config.watch_history();

        self.jf_servers = eng.config.jellyfin_servers();
        if self.jf_active_server_id.is_none() {
            self.jf_active_server_id = self.jf_servers.first().map(|s| s.server_id.clone());
        }

        self.yt_accounts = eng.config.youtube_accounts();
        self.yt_active_account_id = eng
            .config
            .active_youtube_account()
            .map(|a| a.account_id)
            .unwrap_or_default();
        self.twitch_profile = eng.config.twitch_profile();
        self.creator_follows = eng.config.active_creator_follows(None);

        self.mp_profile = eng.config.multipass_profile();
        if self.mp_endpoint_input.is_empty() && !self.mp_profile.endpoint_url.is_empty() {
            self.mp_endpoint_input = self.mp_profile.endpoint_url.clone();
        }
        if !self.mp_profile.device_name.is_empty() {
            self.mp_device_name_input = self.mp_profile.device_name.clone();
        }

        self.swarm_filter = eng.config.swarm_filter();
        self.discover_sort = eng.config.discover_sort_mode();
        let tcfg = eng.config.torrent_config();
        self.max_cache_input = tcfg.max_cache_mb.to_string();
        self.window_ahead_input = tcfg.window_ahead_mb.to_string();
        self.prebuffer_input = tcfg.prebuffer_mb.to_string();
        self.max_conns_input = tcfg.max_connections.to_string();

        let (tz_url, tz_key) = eng.config.torznab_config();
        self.torznab_url_input = tz_url;
        self.torznab_has_key = !tz_key.is_empty();

        self.kodi_addons = eng.kodi.scan_installed_video_addons();
        self.rebuild_discover_types_and_catalogs();
    }

    fn rebuild_discover_types_and_catalogs(&mut self) {
        let mut types = vec!["movie".to_string(), "series".to_string()];
        for addon in &self.installed_addons {
            for cat in &addon.catalogs {
                if !types.iter().any(|t| t == &cat.media_type) {
                    types.push(cat.media_type.clone());
                }
            }
        }
        self.discover_media_types = types;
        self.rebuild_discover_catalogs();
    }

    fn rebuild_discover_catalogs(&mut self) {
        let mut choices = Vec::new();
        for addon in &self.installed_addons {
            for cat in &addon.catalogs {
                if cat.media_type == self.discover_active_type {
                    let label = if addon.id == "com.linvo.cinemeta"
                        || addon.id == "pw.ers.netflix-catalog"
                    {
                        cat.name.clone()
                    } else {
                        format!("{} · {}", addon.name, cat.name)
                    };
                    choices.push(DiscoverCatalogChoice {
                        label,
                        addon: addon.clone(),
                        media_type: cat.media_type.clone(),
                        catalog_id: cat.id.clone(),
                        genres: cat.genres.clone(),
                    });
                }
            }
        }
        self.discover_catalogs = choices;
    }

    fn refresh_library_lists(&mut self) {
        if let Some(eng) = &self.engine {
            self.favorites = eng.config.favorites();
            self.watch_history = eng.config.watch_history();
        }
    }

    fn queue_poster_fetches(&mut self, urls: Vec<String>) -> Task<Message> {
        let Some(eng) = &self.engine else {
            return Task::none();
        };
        let mut tasks = Vec::new();
        for u in urls {
            if self.posters.claim_fetch(&u) {
                let http = eng.http.clone();
                let u_clone = u.clone();
                tasks.push(Task::perform(
                    async move {
                        let res = download_poster_bytes(http, u_clone.clone()).await;
                        (u_clone, res)
                    },
                    |(url, res)| Message::PosterLoaded(url, res),
                ));
            }
        }
        Task::batch(tasks)
    }

    fn spawn_board_refresh(&mut self) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        self.board_loading = true;
        Task::perform(
            async move {
                // 1. Merge Continue Watching from Stremio/Local + Multi-Server Jellyfin + YouTube History
                let mut cw_cards = Vec::new();
                for h in eng.config.watch_history().into_iter().take(12) {
                    if h.duration_ms > 0 && h.progress_ratio() >= 0.95 {
                        continue;
                    }
                    let subtitle = if !h.episode_title.is_empty() {
                        h.episode_title.clone()
                    } else {
                        h.media_type.to_uppercase()
                    };
                    let badge = if JellyfinIds::is_jellyfin_id(&h.item_id) {
                        "JELLYFIN".to_string()
                    } else {
                        "STREMIO".to_string()
                    };
                    cw_cards.push(ContinueWatchingCard {
                        id: h.item_id.clone(),
                        title: h.title.clone(),
                        subtitle,
                        badge,
                        poster_url: h.poster.clone(),
                        progress_ratio: h.progress_ratio().max(0.05),
                        updated_at_ms: h.updated_at_ms,
                        action: ContinueWatchingAction::Stremio { entry: h },
                    });
                }

                for srv in eng.config.jellyfin_servers() {
                    if let Ok(jf_resumes) = eng.jellyfin.get_resume_items(&srv, 6).await {
                        for item in jf_resumes {
                            if cw_cards.iter().any(|c| c.id == item.id) {
                                continue;
                            }
                            cw_cards.push(ContinueWatchingCard {
                                id: item.id.clone(),
                                title: item.name.clone(),
                                subtitle: format!("Server: {}", srv.server_name),
                                badge: "JELLYFIN".into(),
                                poster_url: item.poster.clone(),
                                progress_ratio: 0.45,
                                updated_at_ms: now_epoch_ms().saturating_sub(5_000),
                                action: ContinueWatchingAction::Jellyfin {
                                    server_id: srv.server_id.clone(),
                                    meta: item,
                                    resume_ms: 0,
                                },
                            });
                        }
                    }
                }

                if eng.config.active_youtube_account().is_some() {
                    if let Ok(yt_hist) = eng.youtube.get_history_feed(None).await {
                        for vid in yt_hist
                            .items
                            .into_iter()
                            .filter(|v| v.resume_progress_pct > 3 && v.resume_progress_pct < 95)
                            .take(6)
                        {
                            cw_cards.push(ContinueWatchingCard {
                                id: format!("yt:{}", vid.video_id),
                                title: vid.title.clone(),
                                subtitle: vid.channel_name.clone(),
                                badge: "YOUTUBE".into(),
                                poster_url: vid.thumbnail_url.clone(),
                                progress_ratio: (vid.resume_progress_pct as f32) / 100.0,
                                updated_at_ms: now_epoch_ms().saturating_sub(15_000),
                                action: ContinueWatchingAction::Creator { item: vid },
                            });
                        }
                    }
                }

                cw_cards.sort_by(|a, b| b.updated_at_ms.cmp(&a.updated_at_ms));

                // 2. Fetch synced Stremio catalog rows concurrently (up to 10 curated/user rows)
                let addons = eng.config.addons();
                let mut specs = Vec::new();
                for addon in &addons {
                    for cat in &addon.catalogs {
                        if specs.len() >= 10 {
                            break;
                        }
                        let row_title = if addon.id == "com.linvo.cinemeta" {
                            format!(
                                "{} {}",
                                cat.name,
                                if cat.media_type == "movie" {
                                    "Movies"
                                } else {
                                    "Series"
                                }
                            )
                        } else if addon.id == "pw.ers.netflix-catalog" {
                            if cat.media_type == "movie" && matches!(cat.id.as_str(), "nfx" | "dnp" | "amp" | "hbo") {
                                format!("{} — Movies", cat.name)
                            } else if cat.media_type == "series" && matches!(cat.id.as_str(), "nfx" | "atp" | "hbo") {
                                format!("{} — Series", cat.name)
                            } else {
                                continue;
                            }
                        } else {
                            format!("{} · {}", addon.name, cat.name)
                        };
                        specs.push((
                            format!("{}:{}:{}", addon.id, cat.media_type, cat.id),
                            row_title,
                            addon.name.clone(),
                            addon.clone(),
                            cat.media_type.clone(),
                            cat.id.clone(),
                        ));
                    }
                }

                let mut rows = Vec::new();
                for (key, title, subtitle, addon, mtype, cid) in specs {
                    if let Ok(items) = eng
                        .stremio
                        .fetch_catalog(&addon, &mtype, &cid, None, None, None, 0)
                        .await
                    {
                        if !items.is_empty() {
                            rows.push(CatalogRow {
                                key,
                                title,
                                subtitle,
                                items,
                            });
                        }
                    }
                }
                (rows, cw_cards)
            },
            |(rows, cw)| Message::BoardCatalogsLoaded(rows, cw),
        )
    }

    fn spawn_discover_fetch(&mut self) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        if self.discover_catalogs.is_empty() {
            self.rebuild_discover_catalogs();
        }
        let Some(choice) = self
            .discover_catalogs
            .get(self.discover_catalog_idx)
            .cloned()
        else {
            return Task::none();
        };

        let genre = if self.discover_genre == "All" {
            None
        } else {
            Some(self.discover_genre.clone())
        };
        let year = if self.discover_year == "All" {
            None
        } else {
            Some(self.discover_year.clone())
        };
        let skip = self.discover_skip;
        self.discover_loading = true;

        Task::perform(
            async move {
                eng.stremio
                    .fetch_catalog(
                        &choice.addon,
                        &choice.media_type,
                        &choice.catalog_id,
                        None,
                        genre.as_deref(),
                        year.as_deref(),
                        skip,
                    )
                    .await
                    .map_err(|e| e.to_string())
            },
            Message::DiscoverLoaded,
        )
    }

    fn switch_and_load_jellyfin_server(&mut self, server_id: &str) -> Task<Message> {
        // Strictly clear previous server's items immediately so zero cross-server leakage occurs!
        self.jf_active_server_id = Some(server_id.to_string());
        self.jf_isolated = IsolatedJellyfinServerState::fresh_for(server_id);

        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let Some(prof) = eng.config.jellyfin_server(server_id) else {
            return Task::none();
        };
        let sid = server_id.to_string();

        Task::perform(
            async move {
                let res = async {
                    let views = eng
                        .jellyfin
                        .get_views(&prof)
                        .await
                        .map_err(|e| e.to_string())?;
                    let resume = eng
                        .jellyfin
                        .get_resume_items(&prof, 16)
                        .await
                        .unwrap_or_default();
                    let next_up = eng
                        .jellyfin
                        .get_next_up(&prof, 16)
                        .await
                        .unwrap_or_default();
                    let mut latest = Vec::new();
                    for v in views.iter().take(5) {
                        if let Ok(items) = eng.jellyfin.get_latest_in_view(&prof, &v.id, 16).await {
                            if !items.is_empty() {
                                latest.push((v.name.clone(), items));
                            }
                        }
                    }
                    Ok((views, resume, next_up, latest))
                }
                .await;
                (sid, res)
            },
            |(sid, res)| Message::JellyfinOverviewLoaded(sid, res),
        )
    }

    fn on_jellyfin_server_authenticated(&mut self, prof: JellyfinServerProfile) -> Task<Message> {
        let sid = prof.server_id.clone();
        if let Some(eng) = &self.engine {
            self.jf_servers = eng.config.jellyfin_servers();
        }
        self.jf_sub_mode = JellyfinSubMode::Overview;
        self.toast = Some(format!("Connected Jellyfin server: {}", prof.server_name));
        self.switch_and_load_jellyfin_server(&sid)
    }

    fn spawn_jellyfin_library_fetch(
        &mut self,
        view_id: Option<String>,
        fetch_filters: bool,
    ) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let Some(sid) = self.jf_active_server_id.clone() else {
            return Task::none();
        };
        let Some(prof) = eng.config.jellyfin_server(&sid) else {
            return Task::none();
        };

        self.jf_isolated.loading = true;
        let genre = if self.jf_isolated.selected_genre == "All" {
            None
        } else {
            Some(self.jf_isolated.selected_genre.clone())
        };
        let year = if self.jf_isolated.selected_year == "All" {
            None
        } else {
            Some(self.jf_isolated.selected_year.clone())
        };
        let sort_by = self.jf_isolated.sort_by.clone();
        let sort_order = self.jf_isolated.sort_order.clone();
        let start = self.jf_isolated.page_start;

        let mut tasks = Vec::new();
        if fetch_filters {
            let e_f = eng.clone();
            let p_f = prof.clone();
            let v_f = view_id.clone();
            let sid_f = sid.clone();
            tasks.push(Task::perform(
                async move {
                    let g = e_f
                        .jellyfin
                        .get_genres(&p_f, v_f.as_deref())
                        .await
                        .unwrap_or_default();
                    let y = e_f
                        .jellyfin
                        .get_years(&p_f, v_f.as_deref())
                        .await
                        .unwrap_or_default();
                    (sid_f, g, y)
                },
                |(s, g, y)| Message::JellyfinViewFilterLoaded(s, g, y),
            ));
        }

        tasks.push(Task::perform(
            async move {
                let res = eng
                    .jellyfin
                    .get_library_items(
                        &prof,
                        view_id.as_deref(),
                        None,
                        genre.as_deref(),
                        year.as_deref(),
                        &sort_by,
                        &sort_order,
                        start,
                        48,
                    )
                    .await
                    .map_err(|e| e.to_string());
                (sid, res)
            },
            |(s, r)| Message::JellyfinItemsLoaded(s, r),
        ));

        Task::batch(tasks)
    }

    fn ensure_streaming_loaded(&mut self) -> Task<Message> {
        match self.streaming_platform {
            StreamingPlatform::YouTube => {
                if self.yt_feed.is_empty() {
                    return self.spawn_youtube_feed(false);
                }
            }
            StreamingPlatform::Twitch => {
                if self.twitch_items.is_empty() {
                    return self.update(Message::TwitchSelectTab(TwitchTab::TopLive));
                }
            }
            StreamingPlatform::Odysee => {
                if self.odysee_items.is_empty() {
                    return self.update(Message::CreatorSelectSubTab(
                        StreamingPlatform::Odysee,
                        CreatorSubTab::Featured,
                    ));
                }
            }
            StreamingPlatform::Rumble => {
                if self.rumble_items.is_empty() {
                    return self.update(Message::CreatorSelectSubTab(
                        StreamingPlatform::Rumble,
                        CreatorSubTab::Featured,
                    ));
                }
            }
        }
        Task::none()
    }

    fn spawn_youtube_feed(&mut self, append: bool) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let cont = if append {
            self.yt_continuation.clone()
        } else {
            None
        };
        if append && cont.is_none() {
            return Task::none();
        }
        let tab = self.yt_tab;
        let q = self.yt_search_query.trim().to_string();
        let pid = self.yt_playlist_input.trim().to_string();
        self.streaming_loading = true;

        Task::perform(
            async move {
                let page_res = match tab {
                    YouTubeTab::Home => eng.youtube.get_home_feed(cont.as_deref()).await,
                    YouTubeTab::Subscriptions => {
                        eng.youtube.get_subscriptions_feed(cont.as_deref()).await
                    }
                    YouTubeTab::History => eng.youtube.get_history_feed(cont.as_deref()).await,
                    YouTubeTab::Playlists => {
                        eng.youtube.get_playlist_videos(&pid, cont.as_deref()).await
                    }
                    YouTubeTab::Search => eng.youtube.search_videos(&q, cont.as_deref()).await,
                    YouTubeTab::Channel => return Ok((Vec::new(), None)),
                };
                page_res
                    .map(|p| (p.items, p.continuation_token))
                    .map_err(|e| e.to_string())
            },
            move |res| Message::YtFeedLoaded(append, res),
        )
    }

    fn spawn_dearrow_batch(&self, video_ids: Vec<String>) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let uncached: Vec<String> = video_ids
            .into_iter()
            .filter(|id| !id.is_empty() && !self.dearrow_titles.contains_key(id))
            .take(8)
            .collect();
        if uncached.is_empty() {
            return Task::none();
        }
        Task::perform(
            async move {
                let mut out = Vec::new();
                for vid in uncached {
                    if let Ok(Some(clean)) = eng.youtube.fetch_dearrow_title(&vid).await {
                        out.push((vid, clean));
                    }
                }
                out
            },
            Message::YtDeArrowBatchLoaded,
        )
    }

    fn spawn_unified_search(&mut self) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let q = self.search_query.trim().to_string();
        if q.is_empty() {
            return Task::none();
        }
        self.search_catalog_loading = true;
        self.search_swarm_loading = true;

        let e1 = eng.clone();
        let q1 = q.clone();
        let cat_task = Task::perform(
            async move { e1.stremio.search_catalogs(&q1).await.map_err(|e| e.to_string()) },
            Message::SearchCatalogDone,
        );

        let e2 = eng;
        let q2 = q;
        let swarm_task = Task::perform(
            async move { e2.swarm.search_all(&q2, None, None).await.map_err(|e| e.to_string()) },
            |res| Message::SearchSwarmDone(false, res),
        );

        Task::batch([cat_task, swarm_task])
    }

    fn spawn_swarm_featured(&mut self, feed_key: String) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        self.search_swarm_loading = true;
        Task::perform(
            async move {
                eng.swarm
                    .fetch_featured_feed(&feed_key)
                    .await
                    .map_err(|e| e.to_string())
            },
            |res| Message::SearchSwarmDone(true, res),
        )
    }

    fn spawn_kodi_exec(
        &mut self,
        addon_id: String,
        label: String,
        url: String,
        is_play: bool,
    ) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        self.kodi_loading = true;
        Task::perform(
            async move {
                let res = eng
                    .kodi
                    .execute_addon(&addon_id, Some(&url), 25)
                    .await
                    .map_err(|e| e.to_string());
                (addon_id, label, url, is_play, res)
            },
            |(addon_id, label, url, is_play, result)| Message::KodiExecuted {
                addon_id,
                label,
                url,
                is_play,
                result,
            },
        )
    }

    fn open_meta_detail_with_resume(
        &mut self,
        meta: MetaItem,
        video_id_override: Option<String>,
        ep_title: String,
        resume_ms: u64,
    ) -> Task<Message> {
        let is_jf = JellyfinIds::is_jellyfin_id(&meta.id);
        let init_vid = video_id_override
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| meta.id.clone());

        self.detail = Some(DetailViewState {
            selected_season: 1,
            selected_video_id: init_vid.clone(),
            selected_episode_title: ep_title,
            stream_filter: DetailStreamFilter::All,
            streams: Vec::new(),
            ranked_streams: Vec::new(),
            subtitles: Vec::new(),
            loading_meta: true,
            loading_streams: !is_jf && meta.media_type == "movie",
            resume_position_ms: resume_ms,
            meta: meta.clone(),
        });
        self.overlay = ActiveOverlay::Detail;

        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };

        let mut tasks = vec![self.queue_poster_fetches(vec![meta.poster.clone()])];

        if is_jf {
            if let Some((srv_id, _)) = JellyfinIds::parse_id(&meta.id) {
                if let Some(prof) = eng.config.jellyfin_server(&srv_id) {
                    let mid = meta.id.clone();
                    tasks.push(Task::perform(
                        async move {
                            let full = eng
                                .jellyfin
                                .get_item_detail(&prof, &mid)
                                .await
                                .map_err(|e| e.to_string())?;
                            let eps = if full.media_type == "series" {
                                eng.jellyfin
                                    .get_episodes(&prof, &mid, None)
                                    .await
                                    .unwrap_or_default()
                            } else {
                                Vec::new()
                            };
                            Ok((full, eps))
                        },
                        Message::DetailMetaLoaded,
                    ));
                }
            }
        } else {
            let e1 = eng.clone();
            let mtype = meta.media_type.clone();
            let mid = meta.id.clone();
            let fallback_meta = meta.clone();
            tasks.push(Task::perform(
                async move {
                    let fetched = e1
                        .stremio
                        .fetch_meta_detail(&mtype, &mid)
                        .await
                        .map_err(|e| e.to_string())?;
                    Ok((fetched.unwrap_or(fallback_meta), Vec::new()))
                },
                Message::DetailMetaLoaded,
            ));

            if meta.media_type == "movie" || init_vid.contains(':') {
                tasks.push(self.spawn_detail_streams_fetch(meta.media_type, init_vid));
            }
        }

        Task::batch(tasks)
    }

    fn spawn_detail_streams_fetch(&self, media_type: String, video_id: String) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        Task::perform(
            async move {
                let mut streams = eng
                    .stremio
                    .fetch_streams(&media_type, &video_id)
                    .await
                    .unwrap_or_default();
                if streams.is_empty() && video_id.starts_with("tt") {
                    let imdb_base = video_id.split(':').next().unwrap_or(&video_id);
                    if let Ok(sw) = eng.swarm.search_all("", Some(imdb_base), None).await {
                        streams.extend(sw);
                    }
                }
                let subs = eng
                    .stremio
                    .fetch_subtitles(&media_type, &video_id)
                    .await
                    .unwrap_or_default();
                Ok((streams, subs))
            },
            Message::DetailStreamsLoaded,
        )
    }

    #[allow(clippy::too_many_arguments)]
    fn start_mpv_playback(
        &mut self,
        title: String,
        subtitle: String,
        poster_url: String,
        play_url: String,
        headers: HashMap<String, String>,
        external_subtitles: Vec<(String, String)>,
        start_position_sec: f64,
        source: PlaybackSourceKind,
        torrent_session: Option<brootlite_core::torrent::TorrentStreamSession>,
        skip_segments: Vec<brootlite_core::models::SkipSegment>,
    ) {
        // Stop any previous torrent stream cleanly before starting the next one
        if let (Some(prev), Some(eng)) = (&self.player, self.engine.clone()) {
            if let Some(old_ts) = &prev.torrent_session {
                let old_id = old_ts.session_id.clone();
                tokio::spawn(async move {
                    let _ = eng.media_server.stop_torrent_stream(&old_id).await;
                });
            }
        }

        if self.mpv_player.is_none() {
            if let Some(api) = &self.mpv_api {
                match MpvPlayer::new(api.clone(), self.mpv_render_mode) {
                    Ok(p) => self.mpv_player = Some(p),
                    Err(e) => {
                        self.toast = Some(format!("libmpv init failed: {e}"));
                    }
                }
            }
        }

        if let Some(mpv) = &mut self.mpv_player {
            let _ = mpv.load_media(LoadMediaRequest {
                url: play_url.clone(),
                title: title.clone(),
                headers,
                external_subtitles,
                start_position_sec,
            });
        }

        // Initialize Series Binge Next Episode card if playing a Series episode!
        let binge_card = match &source {
            PlaybackSourceKind::Stremio {
                meta, video_id, ..
            } => StreamSimilarityMatcher::find_next_episode(&meta.videos, video_id)
                .cloned()
                .map(|next_ep| BingeNextCardState {
                    next_episode: next_ep,
                    matched_stream: None,
                    jellyfin_server_id: None,
                    resolving: false,
                    countdown_active: false,
                    countdown_remaining_sec: 15.0,
                    dismissed: false,
                }),
            PlaybackSourceKind::Jellyfin {
                server_id,
                meta,
                video_id,
                ..
            } => StreamSimilarityMatcher::find_next_episode(&meta.videos, video_id)
                .cloned()
                .map(|next_ep| BingeNextCardState {
                    next_episode: next_ep,
                    matched_stream: None,
                    jellyfin_server_id: Some(server_id.clone()),
                    resolving: false,
                    countdown_active: false,
                    countdown_remaining_sec: 15.0,
                    dismissed: false,
                }),
            _ => None,
        };

        self.player = Some(ActivePlayerState {
            title,
            subtitle,
            poster_url,
            play_url,
            source,
            torrent_session,
            swarm_stats: None,
            telemetry: Default::default(),
            inline_frame: None,
            skip_segments,
            active_skip_prompt: None,
            auto_skipped_ranges: Vec::new(),
            undo_skip_target: None,
            ryd_votes: None,
            dearrow_title: None,
            last_progress_saved_sec: start_position_sec,
            binge_card,
            credits_recommendations: Vec::new(),
            yt_recommendations: Vec::new(),
            credits_requested: false,
            show_credits_drawer: false,
            is_scrubbing: false,
            scrub_via_keyboard: false,
            scrub_origin_sec: start_position_sec,
            scrub_target_sec: start_position_sec,
            scrub_repeat_count: 0,
            last_scrub_input_ms: 0,
            pending_seek_target_sec: if start_position_sec > 0.5 {
                Some((start_position_sec, now_epoch_ms() + 2_000))
            } else {
                None
            },
        });
        self.overlay = ActiveOverlay::Player;
    }

    fn commit_active_player_seek(&mut self, target_sec: f64) {
        let clamped = if let Some(p) = &mut self.player {
            let max_dur = p.telemetry.duration_sec;
            let c = if max_dur > 0.5 {
                target_sec.clamp(0.0, max_dur)
            } else {
                target_sec.max(0.0)
            };
            p.is_scrubbing = false;
            p.scrub_via_keyboard = false;
            p.scrub_repeat_count = 0;
            p.scrub_target_sec = c;
            p.pending_seek_target_sec = Some((c, now_epoch_ms() + 1_800));
            c
        } else {
            target_sec.max(0.0)
        };
        if let Some(mpv) = &self.mpv_player {
            mpv.seek_absolute(clamped);
        }
    }

    fn handle_fast_player_tick(&mut self) -> Task<Message> {
        let dt_sec = if self.mpv_render_mode == MpvRenderMode::InlineSoftware {
            0.033
        } else {
            0.120
        };

        // Auto-commit keyboard scrub after 350ms idle pause so holding/tapping arrows never thrashes HTTP/P2P!
        let now_ms = now_epoch_ms();
        let kb_auto_commit_target = self.player.as_ref().and_then(|p| {
            if p.is_scrubbing
                && p.scrub_via_keyboard
                && now_ms.saturating_sub(p.last_scrub_input_ms) >= 350
            {
                Some(p.scrub_target_sec)
            } else {
                None
            }
        });
        if let Some(target) = kb_auto_commit_target {
            self.commit_active_player_seek(target);
        }

        let (tel_opt, frame_opt) = if let Some(mpv) = &mut self.mpv_player {
            let t = mpv.poll_telemetry();
            let f = mpv.poll_inline_frame();
            (Some(t), f)
        } else {
            (None, None)
        };

        if let Some(tel) = &tel_opt {
            if tel.window_closed_by_user {
                self.stop_active_player(true);
                return Task::none();
            }
        }

        let mut follow_up_tasks = Vec::new();
        let mut trigger_binge_play_now = false;
        let mut need_credits_fetch = false;
        let mut pre_resolve_binge: Option<(String, String, brootlite_core::models::StreamSelectionContext)> = None;

        if let Some(p) = &mut self.player {
            if let Some(f) = frame_opt {
                p.inline_frame = Some(f);
            }
            if let Some(tel) = tel_opt {
                // Settle guard: clear pending_seek_target_sec once mpv reaches target or timeout expires
                if let Some((target_sec, expire_ms)) = p.pending_seek_target_sec {
                    if now_ms >= expire_ms || (tel.position_sec - target_sec).abs() <= 1.5 {
                        p.pending_seek_target_sec = None;
                    }
                }

                let pos_ms = (tel.position_sec * 1000.0) as u64;

                // 1. Live P2P Swarm Stats
                if let (Some(ts), Some(eng)) = (&p.torrent_session, &self.engine) {
                    p.swarm_stats = eng.media_server.get_torrent_stats(&ts.session_id);
                }

                // 2. SponsorBlock Auto-Skip (with Undo) & Jellyfin Skip Intro Prompt
                // Suppress auto-skip while user is actively scrubbing or settling after a seek!
                let is_yt = matches!(p.source, PlaybackSourceKind::YouTube { .. });
                let mut found_prompt = None;
                if !p.is_scrubbing && p.pending_seek_target_sec.is_none() {
                    for seg in &p.skip_segments {
                        if pos_ms >= seg.start_ms && pos_ms + 800 < seg.end_ms {
                            let is_sponsor_kind = matches!(
                                seg.kind,
                                SkipSegmentKind::Sponsor
                                    | SkipSegmentKind::SelfPromo
                                    | SkipSegmentKind::Interaction
                            );
                            if is_yt
                                && self.sponsorblock_enabled
                                && is_sponsor_kind
                                && !p.auto_skipped_ranges.contains(&(seg.start_ms, seg.end_ms))
                            {
                                p.auto_skipped_ranges.push((seg.start_ms, seg.end_ms));
                                p.undo_skip_target = Some((seg.label.clone(), tel.position_sec));
                                let end_sec = (seg.end_ms as f64) / 1000.0;
                                p.pending_seek_target_sec = Some((end_sec, now_ms + 1_800));
                                if let Some(mpv) = &self.mpv_player {
                                    mpv.seek_absolute(end_sec);
                                }
                            } else {
                                found_prompt = Some(seg.clone());
                            }
                            break;
                        }
                    }
                }
                p.active_skip_prompt = found_prompt;

                // 3. Series Binge Pre-Resolution & 15s Countdown
                let near_end_pre_resolve = tel.eof_reached
                    || (tel.duration_sec > 60.0
                        && (tel.position_sec / tel.duration_sec >= 0.72
                            || (tel.duration_sec - tel.position_sec) <= 120.0));
                let in_final_binge_window = tel.eof_reached
                    || (tel.duration_sec > 60.0
                        && (tel.position_sec / tel.duration_sec >= 0.92
                            || (tel.duration_sec - tel.position_sec) <= 45.0));

                if let Some(binge) = &mut p.binge_card {
                    if near_end_pre_resolve
                        && !binge.resolving
                        && binge.matched_stream.is_none()
                        && binge.jellyfin_server_id.is_none()
                    {
                        if let PlaybackSourceKind::Stremio {
                            meta, stream_ctx, ..
                        } = &p.source
                        {
                            binge.resolving = true;
                            pre_resolve_binge = Some((
                                meta.media_type.clone(),
                                binge.next_episode.id.clone(),
                                stream_ctx.clone(),
                            ));
                        }
                    }

                    if in_final_binge_window && !binge.dismissed {
                        binge.countdown_active = true;
                        if !tel.paused || tel.eof_reached {
                            binge.countdown_remaining_sec =
                                (binge.countdown_remaining_sec - dt_sec).max(0.0);
                            if binge.countdown_remaining_sec <= 0.0
                                && (binge.matched_stream.is_some()
                                    || binge.jellyfin_server_id.is_some())
                            {
                                trigger_binge_play_now = true;
                            }
                        }
                    }
                }

                // 4. Movie / YouTube Credits Recommendations Pre-Fetch
                if near_end_pre_resolve && !p.credits_requested {
                    p.credits_requested = true;
                    need_credits_fetch = true;
                }

                // 5. Periodic Two-Way Watch Progress Sync (every 12s)
                if !p.is_scrubbing
                    && (tel.position_sec - p.last_progress_saved_sec).abs() >= 12.0
                    && tel.position_sec > 5.0
                {
                    p.last_progress_saved_sec = tel.position_sec;
                    if let Some(eng) = self.engine.clone() {
                        persist_and_sync_progress(eng, &p.source, &p.poster_url, &tel);
                    }
                }

                p.telemetry = tel;
            }
        }

        if let (Some((mtype, next_vid, ctx)), Some(eng)) = (pre_resolve_binge, self.engine.clone())
        {
            follow_up_tasks.push(Task::perform(
                async move {
                    let res = eng
                        .stremio
                        .fetch_streams(&mtype, &next_vid)
                        .await
                        .map(|cands| {
                            StreamSimilarityMatcher::pick_best_next_episode_stream(&ctx, cands)
                        })
                        .map_err(|e| e.to_string());
                    (next_vid, res)
                },
                |(vid, res)| Message::BingeNextStreamsResolved(vid, res),
            ));
        }

        if need_credits_fetch {
            follow_up_tasks.push(self.spawn_credits_recommendations());
        }

        if trigger_binge_play_now {
            follow_up_tasks.push(self.update(Message::BingePlayNextNow));
        }

        Task::batch(follow_up_tasks)
    }

    fn spawn_credits_recommendations(&self) -> Task<Message> {
        let (Some(p), Some(eng)) = (&self.player, self.engine.clone()) else {
            return Task::none();
        };
        match &p.source {
            PlaybackSourceKind::Stremio { meta, .. }
            | PlaybackSourceKind::Jellyfin { meta, .. } => {
                let seed = meta.clone();
                Task::perform(
                    async move {
                        eng.recommendations
                            .get_recommendations_for_meta(&seed, 14)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::CreditsRecommendationsLoaded,
                )
            }
            PlaybackSourceKind::YouTube { video, .. } => {
                let vid = video.video_id.clone();
                Task::perform(
                    async move {
                        eng.recommendations
                            .get_youtube_recommendations(&vid, 10)
                            .await
                            .map_err(|e| e.to_string())
                    },
                    Message::CreditsYtRecommendationsLoaded,
                )
            }
            _ => Task::none(),
        }
    }

    fn handle_slow_poll_tick(&mut self) -> Task<Message> {
        let Some(eng) = self.engine.clone() else {
            return Task::none();
        };
        let mut tasks = Vec::new();

        if let Some(sess) = self.mp_pairing_session.as_ref() {
            let e = eng.clone();
            let origin = sess.origin_url.clone();
            let pid = sess.pairing_id.clone();
            let sec = sess.poll_secret.clone();
            let code = sess.user_code.clone();
            tasks.push(Task::perform(
                async move {
                    e.multipass
                        .poll_device_pairing(&origin, &pid, &sec, &code)
                        .await
                        .map_err(|err| err.to_string())
                },
                Message::MpPairingPolled,
            ));
        }

        if let Some(sess) = self.stremio_qr_session.as_ref() {
            let e = eng.clone();
            let code = sess.code.clone();
            tasks.push(Task::perform(
                async move {
                    match e
                        .stremio
                        .poll_qr_link_session(&code)
                        .await
                        .map_err(|err| err.to_string())?
                    {
                        Some(auth_key) => e
                            .stremio
                            .sync_account_everything(&auth_key)
                            .await
                            .map(Some)
                            .map_err(|err| err.to_string()),
                        None => Ok(None),
                    }
                },
                Message::StremioQrPolled,
            ));
        }

        if let Some(sess) = self.yt_device_session.clone() {
            let e = eng.clone();
            tasks.push(Task::perform(
                async move {
                    e.youtube
                        .poll_device_token(&sess)
                        .await
                        .map_err(|err| err.to_string())
                },
                Message::YtDeviceAuthPolled,
            ));
        }

        if let Some(sess) = self.twitch_device_session.clone() {
            let e = eng.clone();
            tasks.push(Task::perform(
                async move {
                    e.twitch
                        .poll_device_token(&sess)
                        .await
                        .map_err(|err| err.to_string())
                },
                Message::TwitchDeviceAuthPolled,
            ));
        }

        if let Some(qc) = self.jf_wizard.quick_connect.clone() {
            let e = eng;
            let lan = if !self.jf_wizard.lan_url_input.trim().is_empty() {
                self.jf_wizard.lan_url_input.trim().to_string()
            } else {
                self.jf_wizard.remote_url_input.trim().to_string()
            };
            let rem = if self.jf_wizard.remote_url_input.trim().is_empty() {
                None
            } else {
                Some(self.jf_wizard.remote_url_input.trim().to_string())
            };
            if !lan.is_empty() {
                tasks.push(Task::perform(
                    async move {
                        e.jellyfin
                            .poll_quick_connect(&lan, rem.as_deref(), &qc.secret)
                            .await
                            .map_err(|err| err.to_string())
                    },
                    Message::JfWizardQuickConnectPolled,
                ));
            }
        }

        Task::batch(tasks)
    }

    fn stop_active_player(&mut self, destroy_mpv_window: bool) {
        if let Some(p) = self.player.take() {
            if let Some(eng) = self.engine.clone() {
                persist_and_sync_progress(eng.clone(), &p.source, &p.poster_url, &p.telemetry);
                if let PlaybackSourceKind::Jellyfin {
                    server_id,
                    video_id,
                    session,
                    ..
                } = &p.source
                {
                    if let Some(prof) = eng.config.jellyfin_server(server_id) {
                        let eng_jf = eng.clone();
                        let vid = video_id.clone();
                        let msid = session.media_source_id.clone();
                        let psid = session.play_session_id.clone();
                        let pos_ms = (p.telemetry.position_sec * 1000.0) as u64;
                        tokio::spawn(async move {
                            let _ = eng_jf
                                .jellyfin
                                .report_playback_stopped(&prof, &vid, &msid, &psid, pos_ms)
                                .await;
                        });
                    }
                }
                if let Some(ts) = p.torrent_session {
                    let eng_ts = eng.clone();
                    tokio::spawn(async move {
                        let _ = eng_ts.media_server.stop_torrent_stream(&ts.session_id).await;
                    });
                }
                self.watch_history = eng.config.watch_history();
            }
        }

        if destroy_mpv_window {
            self.mpv_player = None;
        } else if let Some(mpv) = &mut self.mpv_player {
            mpv.stop();
        }
        if self.overlay == ActiveOverlay::Player {
            self.overlay = ActiveOverlay::None;
        }
    }
}

impl Drop for BrootLiteApp {
    fn drop(&mut self) {
        self.mpv_player = None;
        if let Some(eng) = &self.engine {
            eng.media_server.shutdown();
        }
    }
}

fn handle_overlay_key_press(key: Key, modifiers: keyboard::Modifiers) -> Option<Message> {
    if modifiers.command() || modifiers.control() || modifiers.alt() {
        return None;
    }
    match key.as_ref() {
        Key::Named(keyboard::key::Named::Escape) => {
            Some(Message::KeyboardShortcut(KeyboardAction::Escape))
        }
        Key::Named(keyboard::key::Named::Enter) => {
            Some(Message::KeyboardShortcut(KeyboardAction::CommitScrub))
        }
        Key::Named(keyboard::key::Named::Space) | Key::Character("k") | Key::Character("K") => {
            Some(Message::KeyboardShortcut(KeyboardAction::TogglePause))
        }
        Key::Named(keyboard::key::Named::ArrowLeft) | Key::Character("j") | Key::Character("J") => {
            Some(Message::KeyboardShortcut(KeyboardAction::SeekBack10))
        }
        Key::Named(keyboard::key::Named::ArrowRight)
        | Key::Character("l")
        | Key::Character("L") => Some(Message::KeyboardShortcut(KeyboardAction::SeekForward10)),
        Key::Character("f") | Key::Character("F") => {
            Some(Message::KeyboardShortcut(KeyboardAction::ToggleFullscreen))
        }
        Key::Character("m") | Key::Character("M") => {
            Some(Message::KeyboardShortcut(KeyboardAction::ToggleMute))
        }
        _ => None,
    }
}

fn build_stream_source_descriptor(
    cand: &StreamCandidate,
    meta: Option<MetaItem>,
    video_id: Option<String>,
    episode_title: Option<String>,
    stream_ctx: brootlite_core::models::StreamSelectionContext,
) -> (String, String, String, PlaybackSourceKind) {
    if let Some(m) = meta {
        let vid = video_id.unwrap_or_else(|| m.id.clone());
        let ep_t = episode_title.unwrap_or_default();
        let sub_line = if ep_t.is_empty() {
            format!("{} · {}", cand.addon_name, cand.title)
        } else {
            format!("{ep_t} · {}", cand.addon_name)
        };
        let poster = m.poster.clone();
        let title = m.name.clone();
        (
            title,
            sub_line,
            poster,
            PlaybackSourceKind::Stremio {
                meta: m,
                video_id: vid,
                episode_title: ep_t,
                stream_ctx,
            },
        )
    } else {
        (
            cand.title.clone(),
            cand.addon_name.clone(),
            String::new(),
            PlaybackSourceKind::DirectSwarm {
                candidate: cand.clone(),
            },
        )
    }
}

fn persist_and_sync_progress(
    eng: Arc<BrootLiteEngine>,
    source: &PlaybackSourceKind,
    poster_url: &str,
    tel: &crate::mpv_player::MpvTelemetrySnapshot,
) {
    let pos_ms = (tel.position_sec * 1000.0) as u64;
    let dur_ms = (tel.duration_sec * 1000.0) as u64;
    if pos_ms < 3_000 {
        return;
    }

    match source {
        PlaybackSourceKind::Stremio {
            meta,
            video_id,
            episode_title,
            stream_ctx,
        } => {
            let entry = WatchHistoryEntry {
                item_id: meta.id.clone(),
                media_type: meta.media_type.clone(),
                title: meta.name.clone(),
                poster: poster_url.to_string(),
                video_id: video_id.clone(),
                episode_title: episode_title.clone(),
                position_ms: pos_ms,
                duration_ms: dur_ms,
                updated_at_ms: now_epoch_ms(),
                last_stream_context: Some(stream_ctx.clone()),
            };
            let _ = eng.config.upsert_watch_history(entry.clone());
            tokio::spawn(async move {
                let _ = eng.stremio.push_watch_progress(&entry).await;
            });
        }
        PlaybackSourceKind::Jellyfin {
            server_id,
            meta,
            video_id,
            episode_title,
            session,
        } => {
            let _ = eng.config.upsert_watch_history(WatchHistoryEntry {
                item_id: meta.id.clone(),
                media_type: meta.media_type.clone(),
                title: meta.name.clone(),
                poster: poster_url.to_string(),
                video_id: video_id.clone(),
                episode_title: episode_title.clone(),
                position_ms: pos_ms,
                duration_ms: dur_ms,
                updated_at_ms: now_epoch_ms(),
                last_stream_context: None,
            });
            if let Some(prof) = eng.config.jellyfin_server(server_id) {
                let vid = video_id.clone();
                let msid = session.media_source_id.clone();
                let psid = session.play_session_id.clone();
                let paused = tel.paused;
                tokio::spawn(async move {
                    let _ = eng
                        .jellyfin
                        .report_playback_progress(&prof, &vid, &msid, &psid, pos_ms, paused)
                        .await;
                });
            }
        }
        PlaybackSourceKind::YouTube {
            video,
            playback_tracking_url,
            watchtime_tracking_url,
        } => {
            let _ = eng.config.upsert_watch_history(WatchHistoryEntry {
                item_id: format!("yt:{}", video.video_id),
                media_type: "youtube".into(),
                title: video.title.clone(),
                poster: video.thumbnail_url.clone(),
                video_id: video.video_id.clone(),
                episode_title: video.channel_name.clone(),
                position_ms: pos_ms,
                duration_ms: dur_ms,
                updated_at_ms: now_epoch_ms(),
                last_stream_context: None,
            });
            let p_url = playback_tracking_url.clone();
            let w_url = watchtime_tracking_url.clone();
            let p_sec = tel.position_sec;
            let d_sec = tel.duration_sec;
            tokio::spawn(async move {
                let _ = eng
                    .youtube
                    .report_watch_time(&p_url, &w_url, p_sec, d_sec)
                    .await;
            });
        }
        _ => {}
    }
}
