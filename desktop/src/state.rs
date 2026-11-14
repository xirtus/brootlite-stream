//! Domain state & message types for all 8 BrootLite Desktop views, Detail, Player, Binge, & Credits.

use crate::mpv_player::{MpvRenderMode, MpvTelemetrySnapshot};
use brootlite_core::models::{
    CreatorCategory, CreatorChannel, CreatorVideoItem, DeviceCodeSession, DiscoverSortMode,
    DiscoveredJellyfinServer, ExternalSubtitle, InstalledAddon, JellyfinPlaybackSession,
    JellyfinQuickConnectState, JellyfinServerProfile, JellyfinView, KodiBridgeResponse, MetaItem,
    RankedStreamCandidate, ResolvedMediaStream, ReturnYouTubeDislikeVotes, SkipSegment,
    StreamCandidate, StreamSelectionContext, SwarmFilter, TwitchAuthProfile, VideoEpisode,
    WatchHistoryEntry, YouTubeAccountProfile,
};
use brootlite_core::multipass::{
    MultipassPairingPollOutcome, MultipassPairingSession, MultipassSyncReport,
};
use brootlite_core::stremio::{StremioLinkSession, StremioSyncSummary};
use brootlite_core::torrent::{SwarmStatsSnapshot, TorrentStreamSession};
use bytes::Bytes;
use iced::widget::qr_code;
use std::collections::HashMap;
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NavTab {
    Board,
    Discover,
    Jellyfin,
    Streaming,
    Search,
    Library,
    Kodi,
    Passport,
}

impl NavTab {
    pub const ALL: [NavTab; 8] = [
        NavTab::Board,
        NavTab::Discover,
        NavTab::Jellyfin,
        NavTab::Streaming,
        NavTab::Search,
        NavTab::Library,
        NavTab::Kodi,
        NavTab::Passport,
    ];

    pub fn label(self) -> &'static str {
        match self {
            NavTab::Board => "Board",
            NavTab::Discover => "Discover",
            NavTab::Jellyfin => "Jellyfin",
            NavTab::Streaming => "Streaming",
            NavTab::Search => "Search",
            NavTab::Library => "Library",
            NavTab::Kodi => "Kodi",
            NavTab::Passport => "Passport ↗",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActiveOverlay {
    None,
    Detail,
    Player,
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. Board Models
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub enum ContinueWatchingAction {
    Stremio {
        entry: WatchHistoryEntry,
    },
    Jellyfin {
        server_id: String,
        meta: MetaItem,
        resume_ms: u64,
    },
    Creator {
        item: CreatorVideoItem,
    },
}

#[derive(Debug, Clone)]
pub struct ContinueWatchingCard {
    pub id: String,
    pub title: String,
    pub subtitle: String,
    pub badge: String,
    pub poster_url: String,
    pub progress_ratio: f32,
    pub updated_at_ms: u64,
    pub action: ContinueWatchingAction,
}

#[derive(Debug, Clone)]
pub struct CatalogRow {
    pub key: String,
    pub title: String,
    pub subtitle: String,
    pub items: Vec<MetaItem>,
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Discover Catalog Descriptor
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub struct DiscoverCatalogChoice {
    pub label: String,
    pub addon: InstalledAddon,
    pub media_type: String,
    pub catalog_id: String,
    pub genres: Vec<String>,
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Jellyfin Multi-Server Strictly Isolated State
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JellyfinSubMode {
    Overview,
    Resume,
    NextUp,
    LibraryView(String),
    Search,
    Settings,
    AddServer,
}

#[derive(Debug, Clone, Default)]
pub struct IsolatedJellyfinServerState {
    pub server_id: String,
    pub views: Vec<JellyfinView>,
    pub resume_items: Vec<MetaItem>,
    pub next_up_items: Vec<MetaItem>,
    pub latest_by_view: Vec<(String, Vec<MetaItem>)>,
    pub library_items: Vec<MetaItem>,
    pub genres: Vec<String>,
    pub selected_genre: String,
    pub years: Vec<String>,
    pub selected_year: String,
    pub sort_by: String,
    pub sort_order: String,
    pub page_start: u32,
    pub search_query: String,
    pub search_results: Vec<MetaItem>,
    pub loading: bool,
}

impl IsolatedJellyfinServerState {
    pub fn fresh_for(server_id: &str) -> Self {
        Self {
            server_id: server_id.to_string(),
            selected_genre: "All".into(),
            selected_year: "All".into(),
            sort_by: "SortName".into(),
            sort_order: "Ascending".into(),
            loading: true,
            ..Default::default()
        }
    }
}

#[derive(Debug, Default)]
pub struct JellyfinAddServerWizard {
    pub lan_url_input: String,
    pub remote_url_input: String,
    pub username_input: String,
    pub password_input: String,
    pub discovered_lan: Vec<DiscoveredJellyfinServer>,
    pub scanning_lan: bool,
    pub quick_connect: Option<JellyfinQuickConnectState>,
    pub status_msg: String,
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Streaming (YouTube · Twitch · Odysee · Rumble) State
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StreamingPlatform {
    YouTube,
    Twitch,
    Odysee,
    Rumble,
}

impl StreamingPlatform {
    pub const ALL: [StreamingPlatform; 4] = [
        StreamingPlatform::YouTube,
        StreamingPlatform::Twitch,
        StreamingPlatform::Odysee,
        StreamingPlatform::Rumble,
    ];

    pub fn label(self) -> &'static str {
        match self {
            StreamingPlatform::YouTube => "YouTube",
            StreamingPlatform::Twitch => "Twitch",
            StreamingPlatform::Odysee => "Odysee",
            StreamingPlatform::Rumble => "Rumble",
        }
    }

    pub fn slug(self) -> &'static str {
        match self {
            StreamingPlatform::YouTube => "youtube",
            StreamingPlatform::Twitch => "twitch",
            StreamingPlatform::Odysee => "odysee",
            StreamingPlatform::Rumble => "rumble",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum YouTubeTab {
    Home,
    Subscriptions,
    History,
    Playlists,
    Channel,
    Search,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TwitchTab {
    TopLive,
    FollowedLive,
    Categories,
    CategoryStreams(String),
    Channel,
    Search,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CreatorSubTab {
    Featured,
    Live,
    Following,
    Search,
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Search & 6. Library Filters
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchCatalogFilter {
    All,
    Movies,
    Series,
    Anime,
}

impl SearchCatalogFilter {
    pub fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Movies => "Movies",
            Self::Series => "Series",
            Self::Anime => "Anime",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SwarmSourceTab {
    AllSwarm,
    PublicDomain,
    CustomRss,
    Torznab,
}

impl SwarmSourceTab {
    pub fn label(self) -> &'static str {
        match self {
            Self::AllSwarm => "All Swarm",
            Self::PublicDomain => "Public Domain",
            Self::CustomRss => "Custom RSS",
            Self::Torznab => "Torznab",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LibraryFilter {
    All,
    Stremio,
    Jellyfin,
    Streaming,
    Kodi,
    Torrents,
}

impl LibraryFilter {
    pub const ALL: [LibraryFilter; 6] = [
        Self::All,
        Self::Stremio,
        Self::Jellyfin,
        Self::Streaming,
        Self::Kodi,
        Self::Torrents,
    ];

    pub fn label(self) -> &'static str {
        match self {
            Self::All => "All",
            Self::Stremio => "Stremio",
            Self::Jellyfin => "Jellyfin",
            Self::Streaming => "Streaming",
            Self::Kodi => "Kodi",
            Self::Torrents => "Torrents",
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. Detail, Active Player, Series Binge Autoplay, & Movie Credits Gallery
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DetailStreamFilter {
    All,
    DirectMirrors,
    P2PSwarm,
}

#[derive(Debug, Clone)]
pub struct DetailViewState {
    pub meta: MetaItem,
    pub selected_season: i32,
    pub selected_video_id: String,
    pub selected_episode_title: String,
    pub stream_filter: DetailStreamFilter,
    pub streams: Vec<StreamCandidate>,
    pub ranked_streams: Vec<RankedStreamCandidate>,
    pub subtitles: Vec<ExternalSubtitle>,
    pub loading_meta: bool,
    pub loading_streams: bool,
    pub resume_position_ms: u64,
}

#[derive(Debug, Clone)]
pub enum PlaybackSourceKind {
    Stremio {
        meta: MetaItem,
        video_id: String,
        episode_title: String,
        stream_ctx: StreamSelectionContext,
    },
    Jellyfin {
        server_id: String,
        meta: MetaItem,
        video_id: String,
        episode_title: String,
        session: JellyfinPlaybackSession,
    },
    YouTube {
        video: CreatorVideoItem,
        playback_tracking_url: String,
        watchtime_tracking_url: String,
    },
    Creator {
        video: CreatorVideoItem,
    },
    DirectSwarm {
        candidate: StreamCandidate,
    },
    Kodi {
        addon_id: String,
        label: String,
        url: String,
    },
}

#[derive(Debug, Clone)]
pub struct BingeNextCardState {
    pub next_episode: VideoEpisode,
    pub matched_stream: Option<RankedStreamCandidate>,
    pub jellyfin_server_id: Option<String>,
    pub resolving: bool,
    pub countdown_active: bool,
    pub countdown_remaining_sec: f64,
    pub dismissed: bool,
}

pub struct ActivePlayerState {
    pub title: String,
    pub subtitle: String,
    pub poster_url: String,
    pub play_url: String,
    pub source: PlaybackSourceKind,
    pub torrent_session: Option<TorrentStreamSession>,
    pub swarm_stats: Option<SwarmStatsSnapshot>,
    pub telemetry: MpvTelemetrySnapshot,
    pub inline_frame: Option<iced::widget::image::Handle>,
    pub skip_segments: Vec<SkipSegment>,
    pub active_skip_prompt: Option<SkipSegment>,
    pub auto_skipped_ranges: Vec<(u64, u64)>,
    pub undo_skip_target: Option<(String, f64)>,
    pub ryd_votes: Option<ReturnYouTubeDislikeVotes>,
    pub dearrow_title: Option<String>,
    pub last_progress_saved_sec: f64,
    pub binge_card: Option<BingeNextCardState>,
    pub credits_recommendations: Vec<MetaItem>,
    pub yt_recommendations: Vec<CreatorVideoItem>,
    pub credits_requested: bool,
    pub show_credits_drawer: bool,
    // Timeline Scrubbing & Post-Seek Settle Guard (prevents UI snap-back & HTTP/P2P thrashing)
    pub is_scrubbing: bool,
    pub scrub_via_keyboard: bool,
    pub scrub_origin_sec: f64,
    pub scrub_target_sec: f64,
    pub scrub_repeat_count: u32,
    pub last_scrub_input_ms: u64,
    pub pending_seek_target_sec: Option<(f64, u64)>,
}

impl ActivePlayerState {
    pub fn effective_display_position_sec(&self) -> f64 {
        if self.is_scrubbing {
            return self.scrub_target_sec;
        }
        if let Some((target_sec, expire_ms)) = self.pending_seek_target_sec {
            if brootlite_core::models::now_epoch_ms() < expire_ms {
                return target_sec;
            }
        }
        self.telemetry.position_sec
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Unified Async `Message` Bus
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub enum Message {
    // Boot & Periodic Timers
    EngineBooted(Result<Arc<BrootLiteEngineWrapper>, String>),
    FastPlayerTick,
    SlowBackgroundTick,
    PosterLoaded(String, Result<Bytes, String>),
    KeyboardShortcut(KeyboardAction),
    DismissToast,

    // Navigation & Overlays
    SelectTab(NavTab),
    CloseOverlay,
    ReturnToPlayer,

    // 1. Board
    BoardSearchInputChanged(String),
    BoardSearchSubmit,
    RefreshBoard,
    BoardCatalogsLoaded(Vec<CatalogRow>, Vec<ContinueWatchingCard>),
    TriggerContinueWatching(usize),

    // 2. Discover
    DiscoverSelectMediaType(String),
    DiscoverSelectCatalog(usize),
    DiscoverSelectGenre(String),
    DiscoverSelectYear(String),
    DiscoverSelectSort(DiscoverSortMode),
    DiscoverPageDelta(i32),
    DiscoverJumpToSearch,
    DiscoverLoaded(Result<Vec<MetaItem>, String>),

    // 3. Jellyfin (Strictly Isolated Multi-Server)
    JellyfinSwitchServer(String),
    JellyfinSetSubMode(JellyfinSubMode),
    JellyfinOverviewLoaded(
        String,
        Result<
            (
                Vec<JellyfinView>,
                Vec<MetaItem>,
                Vec<MetaItem>,
                Vec<(String, Vec<MetaItem>)>,
            ),
            String,
        >,
    ),
    JellyfinViewFilterLoaded(String, Vec<String>, Vec<String>),
    JellyfinItemsLoaded(String, Result<Vec<MetaItem>, String>),
    JellyfinSelectGenre(String),
    JellyfinSelectYear(String),
    JellyfinSelectSort(String, String),
    JellyfinPageDelta(i32),
    JellyfinSearchChanged(String),
    JellyfinSearchSubmit,
    JellyfinSearchLoaded(String, Result<Vec<MetaItem>, String>),
    JellyfinRemoveServer(String),
    // Add Server Wizard
    JfWizardLanUrlChanged(String),
    JfWizardRemoteUrlChanged(String),
    JfWizardUsernameChanged(String),
    JfWizardPasswordChanged(String),
    JfWizardScanLan,
    JfWizardScanLanDone(Result<Vec<DiscoveredJellyfinServer>, String>),
    JfWizardPickDiscovered(DiscoveredJellyfinServer),
    JfWizardStartQuickConnect,
    JfWizardQuickConnectStarted(Result<JellyfinQuickConnectState, String>),
    JfWizardQuickConnectPolled(Result<Option<JellyfinServerProfile>, String>),
    JfWizardLoginPassword,
    JfWizardLoginDone(Result<JellyfinServerProfile, String>),

    // 4. Streaming (YouTube · Twitch · Odysee · Rumble)
    StreamingSelectPlatform(StreamingPlatform),
    // YouTube
    YtSelectTab(YouTubeTab),
    YtSwitchAccount(String),
    YtRemoveAccount(String),
    YtStartDeviceAuth,
    YtDeviceAuthStarted(Result<DeviceCodeSession, String>),
    YtDeviceAuthPolled(Result<Option<Vec<YouTubeAccountProfile>>, String>),
    YtSearchQueryChanged(String),
    YtSuggestionsLoaded(Vec<String>),
    YtPickSuggestion(String),
    YtSearchSubmit,
    YtPlaylistIdChanged(String),
    YtLoadPlaylist,
    YtLoadMoreFeed,
    YtFeedLoaded(bool, Result<(Vec<CreatorVideoItem>, Option<String>), String>),
    YtOpenChannel(String),
    YtChannelLoaded(Result<CreatorChannel, String>),
    YtToggleSubscribe(String, bool),
    YtSubscribedDone(Result<bool, String>),
    YtDeArrowBatchLoaded(Vec<(String, String)>),
    YtToggleSponsorBlock,
    YtToggleRyd,
    YtToggleDeArrow,
    // Twitch
    TwitchSelectTab(TwitchTab),
    TwitchStartDeviceAuth,
    TwitchDeviceAuthStarted(Result<DeviceCodeSession, String>),
    TwitchDeviceAuthPolled(Result<Option<TwitchAuthProfile>, String>),
    TwitchLogout,
    TwitchSearchChanged(String),
    TwitchSearchSubmit,
    TwitchItemsLoaded(Result<Vec<CreatorVideoItem>, String>),
    TwitchCategoriesLoaded(Result<Vec<CreatorCategory>, String>),
    TwitchOpenChannel(String),
    TwitchChannelLoaded(Result<CreatorChannel, String>),
    // Odysee & Rumble
    CreatorSelectSubTab(StreamingPlatform, CreatorSubTab),
    CreatorSearchChanged(StreamingPlatform, String),
    CreatorSearchSubmit(StreamingPlatform),
    CreatorItemsLoaded(StreamingPlatform, Result<Vec<CreatorVideoItem>, String>),
    ToggleCreatorFollow {
        platform: String,
        channel_id: String,
        name: String,
        handle: String,
        avatar_url: String,
    },
    PlayCreatorItem(CreatorVideoItem),
    CreatorStreamResolved(CreatorVideoItem, Result<ResolvedMediaStream, String>),

    // 5. Search
    SearchQueryChanged(String),
    SearchSubmit,
    SearchQuickChip(String),
    SearchSelectCatalogFilter(SearchCatalogFilter),
    SearchSelectSwarmTab(SwarmSourceTab),
    SearchLoadSwarmFeatured(String),
    SearchCatalogDone(Result<Vec<MetaItem>, String>),
    SearchSwarmDone(bool, Result<Vec<StreamCandidate>, String>),

    // 6. Library & 7. Kodi
    LibrarySelectFilter(LibraryFilter),
    ToggleFavoriteMeta(MetaItem),
    KodiRefreshAddons,
    KodiOpenAddon(String),
    KodiOpenDirectory {
        addon_id: String,
        label: String,
        url: String,
    },
    KodiNavigateUp,
    KodiPlayItem {
        addon_id: String,
        label: String,
        url: String,
    },
    KodiExecuted {
        addon_id: String,
        label: String,
        url: String,
        is_play: bool,
        result: Result<KodiBridgeResponse, String>,
    },

    // 8. Passport ↗ (Leeloo Multipass v2 + Stremio + Engine Settings)
    MpEndpointChanged(String),
    MpDeviceNameChanged(String),
    MpPassphraseChanged(String),
    MpStartPairing,
    MpPairingStarted(Result<MultipassPairingSession, String>),
    MpPairingPolled(Result<MultipassPairingPollOutcome, String>),
    MpSyncVault,
    MpSyncDone(Result<(MultipassSyncReport, u64), String>),
    MpDisconnect,
    // Stremio QR & Addons
    StremioStartQr,
    StremioQrStarted(Result<StremioLinkSession, String>),
    StremioQrPolled(Result<Option<StremioSyncSummary>, String>),
    StremioSyncNow,
    StremioSyncDone(Result<StremioSyncSummary, String>),
    StremioLogout,
    AddonUrlInputChanged(String),
    InstallAddonSubmit,
    AddonInstalled(Result<InstalledAddon, String>),
    RemoveAddon(String),
    // Swarm & Torrent Config
    SetSwarmFilter(SwarmFilter),
    TorrentMaxCacheChanged(String),
    TorrentWindowAheadChanged(String),
    TorrentPrebufferChanged(String),
    TorrentMaxConnsChanged(String),
    SaveTorrentConfig,
    TorznabUrlChanged(String),
    TorznabKeyChanged(String),
    SaveTorznabConfig,

    // Detail & Stream Resolution
    OpenMetaDetail(MetaItem),
    DetailMetaLoaded(Result<(MetaItem, Vec<VideoEpisode>), String>),
    DetailSelectSeason(i32),
    DetailSelectEpisode(VideoEpisode),
    DetailSelectStreamFilter(DetailStreamFilter),
    DetailStreamsLoaded(Result<(Vec<StreamCandidate>, Vec<ExternalSubtitle>), String>),
    DetailQuickPlayBest,
    PlayStreamCandidate {
        candidate: StreamCandidate,
        meta: Option<MetaItem>,
        video_id: Option<String>,
        episode_title: Option<String>,
        resume_ms: u64,
    },
    TorrentSessionStarted {
        candidate: StreamCandidate,
        meta: Option<MetaItem>,
        video_id: Option<String>,
        episode_title: Option<String>,
        subtitles: Vec<ExternalSubtitle>,
        resume_ms: u64,
        result: Result<TorrentStreamSession, String>,
    },
    PlayJellyfinItem {
        server_id: String,
        meta: MetaItem,
        video_id: String,
        episode_title: String,
        resume_ms: u64,
    },
    JellyfinPlaybackResolved {
        server_id: String,
        meta: MetaItem,
        video_id: String,
        episode_title: String,
        resume_ms: u64,
        result: Result<(JellyfinPlaybackSession, Vec<SkipSegment>), String>,
    },

    // Active Player Controls, Binge Autoplay, & Credits Recommendations
    PlayerTogglePause,
    PlayerScrubPreview(f32),
    PlayerScrubCommit,
    PlayerStepKeyboardScrub(f64),
    PlayerCancelScrub,
    PlayerSeekAbsolute(f32),
    PlayerSeekRelative(f64),
    PlayerVolumeChanged(f32),
    PlayerToggleMute,
    PlayerSetSpeed(f64),
    PlayerToggleFullscreen,
    PlayerSwitchRenderMode(MpvRenderMode),
    PlayerSelectAudio(i64),
    PlayerSelectSubtitle(Option<i64>),
    PlayerSelectVideo(i64),
    PlayerSelectChapter(usize),
    PlayerStopAndClose,
    PlayerConfirmSkipSegment(SkipSegment),
    PlayerUndoSponsorSkip,
    PlayerRydLoaded(String, Result<ReturnYouTubeDislikeVotes, String>),
    PlayerDeArrowLoaded(String, Option<String>),
    // Series Binge Autoplay
    BingeNextStreamsResolved(String, Result<Option<RankedStreamCandidate>, String>),
    BingePlayNextNow,
    BingeDismissWatchCredits,
    // Movie Credits Gallery
    PlayerToggleCreditsDrawer,
    CreditsRecommendationsLoaded(Result<Vec<MetaItem>, String>),
    CreditsYtRecommendationsLoaded(Result<Vec<CreatorVideoItem>, String>),

    // Background No-Op Ack
    NoOp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyboardAction {
    TogglePause,
    SeekBack10,
    SeekForward10,
    CommitScrub,
    ToggleFullscreen,
    ToggleMute,
    Escape,
}

/// Wrapper implementing `Debug` around `brootlite_core::BrootLiteEngine` so `Message` derives `Debug`.
pub struct BrootLiteEngineWrapper(pub Arc<brootlite_core::BrootLiteEngine>);

impl std::fmt::Debug for BrootLiteEngineWrapper {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("BrootLiteEngineWrapper").finish()
    }
}

pub fn make_qr_data(raw: &str) -> Option<qr_code::Data> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    qr_code::Data::new(trimmed).ok()
}

pub fn format_seconds_hms(sec: f64) -> String {
    let total = sec.max(0.0).round() as u64;
    let h = total / 3600;
    let m = (total % 3600) / 60;
    let s = total % 60;
    if h > 0 {
        format!("{h}:{m:02}:{s:02}")
    } else {
        format!("{m:02}:{s:02}")
    }
}

pub fn format_signed_delta_sec(delta_sec: f64) -> String {
    let sign = if delta_sec >= 0.0 { "+" } else { "-" };
    format!("{sign}{}", format_seconds_hms(delta_sec.abs()))
}

pub fn format_bytes_human(bytes: u64) -> String {
    if bytes == 0 {
        return String::new();
    }
    let gb = (bytes as f64) / (1024.0 * 1024.0 * 1024.0);
    if gb >= 1.0 {
        format!("{gb:.2} GB")
    } else {
        let mb = (bytes as f64) / (1024.0 * 1024.0);
        format!("{mb:.0} MB")
    }
}

pub fn apply_discover_sort(items: &mut [MetaItem], mode: DiscoverSortMode) {
    match mode {
        DiscoverSortMode::Popular => {}
        DiscoverSortMode::TopRated => {
            items.sort_by(|a, b| {
                let ra = a.imdb_rating.trim().parse::<f64>().unwrap_or(0.0);
                let rb = b.imdb_rating.trim().parse::<f64>().unwrap_or(0.0);
                rb.partial_cmp(&ra).unwrap_or(std::cmp::Ordering::Equal)
            });
        }
        DiscoverSortMode::YearDesc => {
            items.sort_by(|a, b| b.release_info.cmp(&a.release_info));
        }
        DiscoverSortMode::TitleAsc => {
            items.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
        }
    }
}

pub fn extract_display_title<'a>(
    item: &'a CreatorVideoItem,
    dearrow_enabled: bool,
    dearrow_map: &'a HashMap<String, String>,
) -> &'a str {
    if dearrow_enabled && item.platform == "youtube" {
        if let Some(clean) = dearrow_map.get(&item.video_id) {
            if !clean.trim().is_empty() {
                return clean.as_str();
            }
        }
    }
    &item.title
}
