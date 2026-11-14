import AppKit
import CBrootLiteBridge
import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Navigation & UI View State Types
// ─────────────────────────────────────────────────────────────────────────────

public enum NavSection: String, CaseIterable, Identifiable, Sendable {
    case board = "Board"
    case discover = "Discover"
    case jellyfin = "Jellyfin"
    case streaming = "Streaming"
    case search = "Search"
    case library = "Library"
    case kodi = "Kodi"
    case passport = "Passport ↗"

    public var id: String { rawValue }

    public var icon: String {
        switch self {
        case .board: return "sparkles.tv"
        case .discover: return "safari"
        case .jellyfin: return "server.rack"
        case .streaming: return "play.tv"
        case .search: return "magnifyingglass"
        case .library: return "bookmark.fill"
        case .kodi: return "shippingbox"
        case .passport: return "qrcode.viewfinder"
        }
    }
}

public enum StreamingPlatformTab: String, CaseIterable, Identifiable, Sendable {
    case youtube = "YouTube"
    case twitch = "Twitch"
    case odysee = "Odysee"
    case rumble = "Rumble"

    public var id: String { rawValue }

    public var accentColor: Color {
        switch self {
        case .youtube: return Color(red: 0.98, green: 0.26, blue: 0.32)
        case .twitch: return Color(red: 0.66, green: 0.44, blue: 0.99)
        case .odysee: return Color(red: 0.96, green: 0.52, blue: 0.22)
        case .rumble: return Color(red: 0.42, green: 0.84, blue: 0.34)
        }
    }
}

public enum YouTubeSectionTab: String, CaseIterable, Identifiable, Sendable {
    case home = "Home"
    case subscriptions = "Subscriptions"
    case history = "History"
    case playlists = "Playlists"
    case search = "Search"

    public var id: String { rawValue }
}

public enum TwitchSectionTab: String, CaseIterable, Identifiable, Sendable {
    case live = "Live"
    case following = "Following"
    case categories = "Categories"
    case search = "Search"

    public var id: String { rawValue }
}

public enum CreatorSimpleTab: String, CaseIterable, Identifiable, Sendable {
    case featured = "Featured"
    case live = "Live"
    case search = "Search"

    public var id: String { rawValue }
}

public enum SwarmSourceTab: String, CaseIterable, Identifiable, Sendable {
    case all = "All Swarm"
    case publicDomain = "Public Domain"
    case customRss = "Custom RSS"
    case torznab = "Torznab"

    public var id: String { rawValue }
}

public enum StremioSearchFilter: String, CaseIterable, Identifiable, Sendable {
    case all = "All"
    case movies = "Movies"
    case series = "Series"
    case anime = "Anime"

    public var id: String { rawValue }
}

public enum LibrarySourceFilter: String, CaseIterable, Identifiable, Sendable {
    case all = "All"
    case stremio = "Stremio"
    case jellyfin = "Jellyfin"
    case streaming = "Streaming"
    case kodi = "Kodi"
    case torrents = "Torrents"

    public var id: String { rawValue }
}

public enum StreamModeFilter: String, CaseIterable, Identifiable, Sendable {
    case all = "All"
    case direct = "Direct / Mirrors"
    case p2p = "P2P Swarm"

    public var id: String { rawValue }
}

public struct CatalogRail: Identifiable, Sendable {
    public let id: String
    public let title: String
    public let subtitle: String
    public let items: [MetaItem]
}

public struct AddonCatalogOption: Identifiable, Hashable, Sendable {
    public let id: String
    public let addonId: String
    public let addonName: String
    public let transportUrl: String
    public let mediaType: String
    public let catalogId: String
    public let catalogName: String
    public let genres: [String]
    public let supportsGenre: Bool
    public let supportsSkip: Bool
    public let isByYearSynthetic: Bool

    public var displayLabel: String {
        if isByYearSynthetic {
            return "By Year (\(addonName))"
        }
        if addonName.lowercased() == "cinemeta" {
            return catalogName
        }
        return "\(catalogName) · \(addonName)"
    }
}

public struct UnifiedResumeItem: Identifiable, Sendable {
    public enum SourceKind: Sendable {
        case watchHistory(WatchHistoryEntry)
        case jellyfinMeta(MetaItem, serverName: String)
        case creatorVideo(CreatorVideoItem)
    }

    public let id: String
    public let title: String
    public let subtitle: String
    public let artworkUrl: String
    public let badge: String
    public let progressRatio: Double
    public let updatedAtMs: UInt64
    public let source: SourceKind
}

public struct KodiBreadcrumb: Identifiable, Hashable, Sendable {
    public let id = UUID()
    public let title: String
    public let pluginUrl: String?
}

// ─────────────────────────────────────────────────────────────────────────────
// Active Playback Session Models
// ─────────────────────────────────────────────────────────────────────────────

public enum PlaybackOrigin: Sendable {
    case stremio(meta: MetaItem, episode: VideoEpisode?, context: StreamSelectionContext)
    case jellyfin(
        meta: MetaItem,
        episode: VideoEpisode?,
        serverId: String,
        playbackItemId: String,
        mediaSourceId: String,
        playSessionId: String,
        playMethod: String
    )
    case youtube(video: CreatorVideoItem, stream: ResolvedMediaStream)
    case creator(platform: String, video: CreatorVideoItem, stream: ResolvedMediaStream)
    case swarm(candidate: StreamCandidate)
    case kodi(label: String, url: String)
}

public struct NextEpisodeProposal: Sendable {
    public let meta: MetaItem
    public let nextEpisode: VideoEpisode
    public let bestCandidate: StreamCandidate?
    public let matchedReasons: [String]
    public let isJellyfin: Bool
    public let jellyfinServerId: String?
}

public struct ActivePlaybackSession: Sendable {
    public var title: String
    public var subtitle: String
    public var posterUrl: String
    public var playUrl: String
    public var headers: [String: String]
    public var origin: PlaybackOrigin
    public var torrentSessionId: String?
    public var skipSegments: [SkipSegment]
    public var externalSubtitles: [ExternalSubtitle]
    public var startedAtSec: Double
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Application ViewModel (`@MainActor @Observable`)
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
@Observable
public final class AppViewModel {
    public let core = BrootLiteCoreClient.shared
    public let player = MpvPlayerController()

    // Navigation & Global Chrome
    public var selectedNav: NavSection = .board
    public var searchFocusCounter: Int = 0
    public var toastMessage: String?
    public var status: EngineStatus?

    // Live Memory Footprint (`mach_task_basic_info` / `phys_footprint`)
    public var residentMemoryMB: Double = 0
    public var footprintMemoryMB: Double = 0

    // Installed Addons, Watch History & Saved Favorites
    public var addons: [InstalledAddon] = []
    public var watchHistory: [WatchHistoryEntry] = []
    public var favorites: [FavoriteEntry] = []
    public var creatorFollows: [CreatorFollowEntry] = []

    // 1. Board State
    public var boardSearchQuery: String = ""
    public var isLoadingBoard: Bool = false
    public var continueWatchingItems: [UnifiedResumeItem] = []
    public var boardRails: [CatalogRail] = []

    // 2. Discover State
    public var discoverType: String = "movie"
    public var selectedCatalogOptionId: String = ""
    public var discoverGenreOrYear: String = "All"
    public var discoverSort: DiscoverSortMode = .popular
    public var discoverInlineQuery: String = ""
    public var discoverItems: [MetaItem] = []
    public var discoverSkip: Int = 0
    public var discoverCanLoadMore: Bool = true
    public var isLoadingDiscover: Bool = false

    // 3. Jellyfin Strictly-Isolated Multi-Server State
    public var jellyfinServers: [JellyfinServerProfile] = []
    public var selectedJellyfinServerId: String = ""
    public var jellyfinMode: String = "overview" // "overview", "view:<id>", "search", "settings"
    public var jellyfinViews: [JellyfinView] = []
    public var jellyfinResumeItems: [MetaItem] = []
    public var jellyfinNextUpItems: [MetaItem] = []
    public var jellyfinLatestByView: [String: [MetaItem]] = [:]
    public var jellyfinLibraryItems: [MetaItem] = []
    public var jellyfinGenres: [String] = []
    public var jellyfinYears: [String] = []
    public var jellyfinSelectedGenre: String = "All"
    public var jellyfinSelectedYear: String = "All"
    public var jellyfinSortBy: String = "SortName"
    public var jellyfinSearchQuery: String = ""
    public var jellyfinSearchResults: [MetaItem] = []
    public var isLoadingJellyfin: Bool = false

    // Jellyfin Add-Server Sheet State
    public var showAddJellyfinSheet: Bool = false
    public var lanDiscoveredServers: [DiscoveredJellyfinServer] = []
    public var isScanningLAN: Bool = false
    public var jfInputLanUrl: String = ""
    public var jfInputRemoteUrl: String = ""
    public var jfInputUsername: String = ""
    public var jfInputPassword: String = ""
    public var jfQuickConnectState: JellyfinQuickConnectState?
    public var jfAuthError: String?

    // 4. Streaming (`YouTube · Twitch · Odysee · Rumble`) State
    public var streamingPlatform: StreamingPlatformTab = .youtube
    public var ytTab: YouTubeSectionTab = .home
    public var ytAccounts: [YouTubeAccountProfile] = []
    public var ytActiveAccountId: String = ""
    public var ytDeviceSession: DeviceCodeSession?
    public var ytFeedItems: [CreatorVideoItem] = []
    public var ytContinuation: String?
    public var ytPlaylistId: String = "WL"
    public var ytSearchQuery: String = ""
    public var ytSuggestions: [String] = []
    public var ytActiveChannel: CreatorChannel?
    public var deArrowTitles: [String: String] = [:]
    public var isLoadingStreaming: Bool = false

    // YouTube Enhancement Toggles
    public var sponsorBlockEnabled: Bool = UserDefaults.standard.object(forKey: "bl.sponsorBlock") as? Bool ?? true {
        didSet { UserDefaults.standard.set(sponsorBlockEnabled, forKey: "bl.sponsorBlock") }
    }
    public var deArrowEnabled: Bool = UserDefaults.standard.object(forKey: "bl.deArrow") as? Bool ?? false {
        didSet { UserDefaults.standard.set(deArrowEnabled, forKey: "bl.deArrow") }
    }
    public var rydEnabled: Bool = UserDefaults.standard.object(forKey: "bl.ryd") as? Bool ?? true {
        didSet { UserDefaults.standard.set(rydEnabled, forKey: "bl.ryd") }
    }

    // Twitch, Odysee & Rumble State
    public var twitchTab: TwitchSectionTab = .live
    public var twitchProfile: TwitchAuthProfile?
    public var twitchDeviceSession: DeviceCodeSession?
    public var twitchItems: [CreatorVideoItem] = []
    public var twitchCategories: [CreatorCategory] = []
    public var twitchSelectedCategory: String?
    public var twitchActiveChannel: CreatorChannel?
    public var twitchSearchQuery: String = ""

    public var odyseeTab: CreatorSimpleTab = .featured
    public var odyseeItems: [CreatorVideoItem] = []
    public var odyseeSearchQuery: String = ""

    public var rumbleTab: CreatorSimpleTab = .featured
    public var rumbleItems: [CreatorVideoItem] = []
    public var rumbleSearchQuery: String = ""

    // 5. Search State (`Stremio Catalog Search` + `Direct P2P Swarm`)
    public var unifiedSearchQuery: String = ""
    public var stremioSearchFilter: StremioSearchFilter = .all
    public var stremioSearchResults: [MetaItem] = []
    public var isSearchingStremio: Bool = false

    public var swarmSourceTab: SwarmSourceTab = .all
    public var swarmFilter: SwarmFilter = .safeOnly
    public var swarmResults: [StreamCandidate] = []
    public var swarmFeaturedKey: String = "archive_movies"
    public var isSearchingSwarm: Bool = false

    // 6. Library & Kodi State
    public var libraryFilter: LibrarySourceFilter = .all
    public var kodiAddons: [KodiAddonInfo] = []
    public var selectedKodiAddon: KodiAddonInfo?
    public var kodiBreadcrumbs: [KodiBreadcrumb] = []
    public var kodiItems: [KodiDirectoryItem] = []
    public var isLoadingKodi: Bool = false
    public var kodiError: String?

    // 7. Passport (`Leeloo Multipass v2`) State
    public var multipassInputEndpoint: String = "https://multipass.leeloo.dev"
    public var multipassDeviceName: String = Host.current().localizedName ?? "BrootLite Mac"
    public var multipassPairingSession: MultipassPairingSession?
    public var multipassPassphrase: String = ""
    public var multipassLastReport: MultipassSyncReport?
    public var isSyncingVault: Bool = false
    public var keychainHasUnlockedMarker: Bool = false

    public var stremioQrSession: StremioLinkSession?
    public var addonInstallUrl: String = ""
    public var torrentDraftConfig: TorrentEngineConfig = TorrentEngineConfig()
    public var torznabUrl: String = ""
    public var torznabApiKey: String = ""
    public var torznabHasSavedKey: Bool = false

    // 8. Detail Sheet State
    public var detailItem: MetaItem?
    public var isLoadingDetailMeta: Bool = false
    public var detailSelectedSeason: Int = 1
    public var detailSelectedEpisode: VideoEpisode?
    public var detailStreamFilter: StreamModeFilter = .all
    public var detailStreams: [StreamCandidate] = []
    public var isLoadingDetailStreams: Bool = false

    // 9. Active Cinema Player Overlay State
    public var activeSession: ActivePlaybackSession?
    public var isResolvingPlayback: Bool = false
    public var liveSwarmStats: SwarmStatsSnapshot?
    public var activeRYDVotes: ReturnYouTubeDislikeVotes?
    public var sponsorSkipToast: String?
    public var lastSponsorSkippedFromSec: Double?
    public var suppressedSponsorSegmentIds: Set<String> = []

    // Smart Series Binge Autoplay State
    public var nextEpisodeProposal: NextEpisodeProposal?
    public var didStartPreResolvingNextEp: Bool = false
    public var watchCreditsDismissedBinge: Bool = false
    public var bingeCountdownRemaining: Int?

    // Movie / Finale Credits `"NEXT RECOMMENDED"` Bottom Glass Gallery State
    public var creditsRecommendations: [MetaItem] = []
    public var didFetchCreditsRecommendations: Bool = false
    public var dismissCreditsRecommendations: Bool = false

    @ObservationIgnored
    var backgroundTimersStarted = false
    @ObservationIgnored
    var multipassPollTask: Task<Void, Never>?
    @ObservationIgnored
    var stremioQrPollTask: Task<Void, Never>?
    @ObservationIgnored
    var ytDevicePollTask: Task<Void, Never>?
    @ObservationIgnored
    var twitchDevicePollTask: Task<Void, Never>?
    @ObservationIgnored
    var jfQuickConnectPollTask: Task<Void, Never>?
    @ObservationIgnored
    var playerTelemetryTask: Task<Void, Never>?

    public init() {}

    // MARK: - Bootstrap & Memory Monitor

    public func bootstrapIfNeeded() async {
        guard !backgroundTimersStarted else { return }
        backgroundTimersStarted = true
        refreshMemoryFootprint()
        keychainHasUnlockedMarker = KeychainVaultStore.loadVaultRecord(account: "paired_device") != nil

        Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2.5))
                self?.refreshMemoryFootprint()
            }
        }

        await reloadCoreStateAndBoard()
    }

    public func refreshMemoryFootprint() {
        let snap = bl_get_process_memory()
        residentMemoryMB = snap.resident_mb
        footprintMemoryMB = snap.phys_footprint_mb
    }

    public func showToast(_ message: String) {
        toastMessage = message
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(3.6))
            if self?.toastMessage == message {
                self?.toastMessage = nil
            }
        }
    }

    public func focusGlobalStremioSearch() {
        if selectedNav != .board && selectedNav != .search {
            selectedNav = .search
        }
        searchFocusCounter += 1
    }

    public func reloadCoreStateAndBoard() async {
        do {
            let st = try await core.engineStatus()
            self.status = st
            self.swarmFilter = st.swarmFilter
            self.discoverSort = st.discoverSortMode
            self.torrentDraftConfig = st.torrentConfig
            if !st.multipassProfile.endpointUrl.isEmpty {
                self.multipassInputEndpoint = st.multipassProfile.endpointUrl
            }
        } catch {
            showToast(error.localizedDescription)
        }

        async let addonsTask = try? core.getAddons()
        async let historyTask = try? core.getWatchHistory()
        async let favsTask = try? core.getFavorites()
        async let followsTask = try? core.getCreatorFollows()
        async let jfTask = try? core.jellyfinListServers()
        async let ytTask = try? core.youtubeListAccounts()
        async let twTask = try? core.twitchProfile()
        async let tzTask = try? core.getTorznab()

        self.addons = (await addonsTask) ?? []
        self.watchHistory = (await historyTask) ?? []
        self.favorites = (await favsTask) ?? []
        self.creatorFollows = (await followsTask) ?? []
        self.jellyfinServers = (await jfTask) ?? []
        if selectedJellyfinServerId.isEmpty || !jellyfinServers.contains(where: { $0.serverId == selectedJellyfinServerId }) {
            selectedJellyfinServerId = jellyfinServers.first?.serverId ?? ""
        }
        if let ytResp = await ytTask {
            self.ytAccounts = ytResp.accounts
            self.ytActiveAccountId = ytResp.activeAccountId
        }
        self.twitchProfile = await twTask
        if let tz = await tzTask {
            self.torznabUrl = tz.url
            self.torznabHasSavedKey = tz.hasApiKey
        }

        await loadBoard()
    }

    // MARK: - 1. Board Loading & Unified Continue Watching

    public func submitBoardStremioSearch() {
        let q = boardSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            selectedNav = .search
            searchFocusCounter += 1
            return
        }
        unifiedSearchQuery = q
        selectedNav = .search
        Task {
            await executeUnifiedSearch()
        }
    }

    public func loadBoard() async {
        isLoadingBoard = true
        defer { isLoadingBoard = false }

        await rebuildContinueWatching()

        // Build catalog specs from all installed addons (Popular Movies/Series, Netflix, HBO/Max, Prime, Anime, JAV/Hentai, etc.)
        struct Spec: Sendable {
            let id: String
            let title: String
            let subtitle: String
            let addon: InstalledAddon
            let cat: AddonCatalogDef
        }

        var specs: [Spec] = []
        for addon in addons {
            for cat in addon.catalogs {
                // Skip pure search-only catalogs that return empty without query unless they are standard rails
                let title: String
                if addon.id == "com.linvo.cinemeta" {
                    let typeLabel = cat.mediaType == "series" ? "Series" : "Movies"
                    title = "\(cat.name) \(typeLabel)"
                } else {
                    let typeSuffix = cat.mediaType == "series" ? "Series" : (cat.mediaType == "movie" ? "Movies" : cat.mediaType.capitalized)
                    title = cat.name.localizedCaseInsensitiveContains(typeSuffix)
                        ? cat.name
                        : "\(cat.name) · \(typeSuffix)"
                }
                specs.append(
                    Spec(
                        id: "\(addon.id)|\(cat.mediaType)|\(cat.id)",
                        title: title,
                        subtitle: addon.name,
                        addon: addon,
                        cat: cat
                    )
                )
            }
        }

        let cappedSpecs = Array(specs.prefix(14))
        let client = self.core

        let rails = await withTaskGroup(of: (Int, CatalogRail?).self) { group in
            for (idx, spec) in cappedSpecs.enumerated() {
                group.addTask {
                    guard let items = try? await client.stremioFetchCatalog(
                        type: spec.cat.mediaType,
                        catalogId: spec.cat.id,
                        addonId: spec.addon.id,
                        transportUrl: spec.addon.transportUrl,
                        skip: 0
                    ), !items.isEmpty else {
                        return (idx, nil)
                    }
                    return (
                        idx,
                        CatalogRail(
                            id: spec.id,
                            title: spec.title,
                            subtitle: spec.subtitle,
                            items: Array(items.prefix(24))
                        )
                    )
                }
            }
            var collected: [(Int, CatalogRail)] = []
            for await (idx, maybeRail) in group {
                if let rail = maybeRail {
                    collected.append((idx, rail))
                }
            }
            return collected.sorted { $0.0 < $1.0 }.map(\.1)
        }

        self.boardRails = rails
    }

    public func rebuildContinueWatching() async {
        var merged: [UnifiedResumeItem] = []
        var seenIds: Set<String> = []

        // 1) Local + Stremio + Creator Watch History
        for entry in watchHistory {
            let ratio = entry.progressRatio
            if ratio >= 0.96 && entry.durationMs > 0 { continue }
            seenIds.insert(entry.itemId)
            let sub = !entry.episodeTitle.isEmpty
                ? entry.episodeTitle
                : (entry.itemId.hasPrefix("jf:") ? "Jellyfin Resume" : entry.mediaType.uppercased())
            merged.append(
                UnifiedResumeItem(
                    id: "wh:\(entry.id)",
                    title: entry.title,
                    subtitle: sub,
                    artworkUrl: entry.poster,
                    badge: ratio > 0 ? "\(Int(round(ratio * 100)))%" : "RESUME",
                    progressRatio: max(0.05, ratio),
                    updatedAtMs: entry.updatedAtMs,
                    source: .watchHistory(entry)
                )
            )
        }

        // 2) Per-Server Jellyfin Resume Items
        let servers = jellyfinServers
        let client = core
        for srv in servers {
            if let jfItems = try? await client.jellyfinResume(serverId: srv.serverId, limit: 8) {
                for item in jfItems where !seenIds.contains(item.id) {
                    seenIds.insert(item.id)
                    merged.append(
                        UnifiedResumeItem(
                            id: "jfres:\(item.id)",
                            title: item.name,
                            subtitle: "\(srv.serverName) · \(item.releaseInfo)",
                            artworkUrl: !item.background.isEmpty ? item.background : item.poster,
                            badge: srv.serverName,
                            progressRatio: 0.42,
                            updatedAtMs: srv.updatedAtMs,
                            source: .jellyfinMeta(item, serverName: srv.serverName)
                        )
                    )
                }
            }
        }

        // 3) YouTube Account Watch History (if signed into YouTube)
        if !ytAccounts.isEmpty,
           let ytHist = try? await client.youtubeHistory() {
            for vid in ytHist.items.prefix(6) where vid.resumeProgressPct > 0 && vid.resumeProgressPct < 96 {
                let compId = "yt:\(vid.videoId)"
                guard !seenIds.contains(compId) else { continue }
                seenIds.insert(compId)
                merged.append(
                    UnifiedResumeItem(
                        id: compId,
                        title: vid.title,
                        subtitle: "YouTube · \(vid.channelName)",
                        artworkUrl: vid.thumbnailUrl,
                        badge: vid.durationText,
                        progressRatio: Double(vid.resumeProgressPct) / 100.0,
                        updatedAtMs: 1,
                        source: .creatorVideo(vid)
                    )
                )
            }
        }

        merged.sort { $0.updatedAtMs > $1.updatedAtMs }
        self.continueWatchingItems = Array(merged.prefix(24))
    }

    public func openResumeItem(_ item: UnifiedResumeItem) {
        switch item.source {
        case let .watchHistory(entry):
            if entry.itemId.hasPrefix("yt:") {
                let vidId = String(entry.itemId.dropFirst(3))
                let vid = CreatorVideoItem(
                    platform: "youtube",
                    videoId: vidId,
                    title: entry.title,
                    channelName: entry.episodeTitle,
                    channelId: "",
                    channelAvatar: "",
                    thumbnailUrl: entry.poster,
                    durationSec: Int64(entry.durationMs / 1000),
                    durationText: "",
                    viewCountText: "",
                    publishedText: "",
                    isLive: false,
                    isShort: false,
                    resumeProgressPct: Int32(entry.progressRatio * 100),
                    categoryName: "",
                    directUrlHint: ""
                )
                Task { await playCreatorItem(vid, startPositionSec: Double(entry.positionMs) / 1000.0) }
            } else {
                let meta = MetaItem(
                    id: entry.itemId,
                    mediaType: entry.mediaType,
                    name: entry.title,
                    poster: entry.poster
                )
                openDetail(for: meta, preselectVideoId: entry.videoId)
            }
        case let .jellyfinMeta(meta, _):
            openDetail(for: meta)
        case let .creatorVideo(vid):
            let startSec = vid.durationSec > 0
                ? Double(vid.durationSec) * (Double(vid.resumeProgressPct) / 100.0)
                : 0
            Task { await playCreatorItem(vid, startPositionSec: startSec) }
        }
    }

    // MARK: - 2. Discover Selectors & Infinite Scroll

    public var discoverAvailableTypes: [String] {
        var types = ["movie", "series"]
        for addon in addons {
            for cat in addon.catalogs where !types.contains(cat.mediaType) {
                types.append(cat.mediaType)
            }
        }
        return types
    }

    public var discoverCatalogOptions: [AddonCatalogOption] {
        var opts: [AddonCatalogOption] = []
        for addon in addons {
            for cat in addon.catalogs where cat.mediaType == discoverType {
                opts.append(
                    AddonCatalogOption(
                        id: "\(addon.id)|\(cat.mediaType)|\(cat.id)",
                        addonId: addon.id,
                        addonName: addon.name,
                        transportUrl: addon.transportUrl,
                        mediaType: cat.mediaType,
                        catalogId: cat.id,
                        catalogName: cat.name,
                        genres: cat.genres,
                        supportsGenre: cat.supportsGenre,
                        supportsSkip: cat.supportsSkip,
                        isByYearSynthetic: false
                    )
                )
                if addon.id == "com.linvo.cinemeta" && cat.id == "year" {
                    // Already a By Year catalog
                }
            }
        }
        if let cine = addons.first(where: { $0.id == "com.linvo.cinemeta" }),
           !opts.contains(where: { $0.catalogId == "year" }) {
            opts.append(
                AddonCatalogOption(
                    id: "\(cine.id)|\(discoverType)|by_year_syn",
                    addonId: cine.id,
                    addonName: cine.name,
                    transportUrl: cine.transportUrl,
                    mediaType: discoverType,
                    catalogId: "year",
                    catalogName: "By Year",
                    genres: (1985...2026).reversed().map(String.init),
                    supportsGenre: true,
                    supportsSkip: true,
                    isByYearSynthetic: true
                )
            )
        }
        return opts
    }

    public var activeDiscoverCatalog: AddonCatalogOption? {
        let opts = discoverCatalogOptions
        if let match = opts.first(where: { $0.id == selectedCatalogOptionId }) {
            return match
        }
        return opts.first
    }

    public func reloadDiscover(reset: Bool = true) async {
        guard let cat = activeDiscoverCatalog else { return }
        if selectedCatalogOptionId != cat.id {
            selectedCatalogOptionId = cat.id
        }
        if reset {
            discoverSkip = 0
            discoverCanLoadMore = true
        }
        isLoadingDiscover = true
        defer { isLoadingDiscover = false }

        let extraVal = (discoverGenreOrYear == "All" || discoverGenreOrYear.isEmpty) ? nil : discoverGenreOrYear
        let isYearMode = cat.catalogId == "year" || cat.isByYearSynthetic
        let searchVal = discoverInlineQuery.trimmingCharacters(in: .whitespacesAndNewlines)

        do {
            let batch = try await core.stremioFetchCatalog(
                type: cat.mediaType,
                catalogId: cat.catalogId,
                addonId: cat.addonId,
                transportUrl: cat.transportUrl,
                search: searchVal.isEmpty ? nil : searchVal,
                genre: isYearMode ? nil : extraVal,
                year: isYearMode ? (extraVal ?? "2025") : nil,
                skip: discoverSkip
            )
            let sorted = applyDiscoverSort(batch, mode: discoverSort)
            if reset {
                discoverItems = sorted
            } else {
                let existingIds = Set(discoverItems.map(\.id))
                discoverItems.append(contentsOf: sorted.filter { !existingIds.contains($0.id) })
            }
            discoverCanLoadMore = cat.supportsSkip && batch.count >= 15
        } catch {
            if reset { discoverItems = [] }
            discoverCanLoadMore = false
        }
    }

    public func loadMoreDiscoverIfNeeded(currentItem: MetaItem) {
        guard !isLoadingDiscover, discoverCanLoadMore else { return }
        guard let idx = discoverItems.firstIndex(where: { $0.id == currentItem.id }),
              idx >= discoverItems.count - 8 else {
            return
        }
        discoverSkip += max(20, discoverItems.count)
        Task { await reloadDiscover(reset: false) }
    }

    public func setDiscoverSort(_ mode: DiscoverSortMode) {
        discoverSort = mode
        discoverItems = applyDiscoverSort(discoverItems, mode: mode)
        Task { try? await core.setDiscoverSortMode(mode) }
    }

    private func applyDiscoverSort(_ list: [MetaItem], mode: DiscoverSortMode) -> [MetaItem] {
        switch mode {
        case .popular:
            return list
        case .topRated:
            return list.sorted { (Double($0.imdbRating) ?? 0) > (Double($1.imdbRating) ?? 0) }
        case .yearDesc:
            return list.sorted { $0.releaseInfo > $1.releaseInfo }
        case .titleAsc:
            return list.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
    }

    // MARK: - 3. Jellyfin Strictly-Isolated Multi-Server Operations

    public var activeJellyfinServer: JellyfinServerProfile? {
        jellyfinServers.first(where: { $0.serverId == selectedJellyfinServerId })
    }

    public func selectJellyfinServer(_ serverId: String) {
        guard selectedJellyfinServerId != serverId else { return }
        // Strictly clear previous server's isolated state immediately!
        selectedJellyfinServerId = serverId
        jellyfinMode = "overview"
        jellyfinViews = []
        jellyfinResumeItems = []
        jellyfinNextUpItems = []
        jellyfinLatestByView = [:]
        jellyfinLibraryItems = []
        jellyfinGenres = []
        jellyfinYears = []
        jellyfinSearchResults = []
        jellyfinSelectedGenre = "All"
        jellyfinSelectedYear = "All"

        Task { await loadActiveJellyfinServerOverview() }
    }

    public func loadActiveJellyfinServerOverview() async {
        let targetServerId = selectedJellyfinServerId
        guard !targetServerId.isEmpty else { return }
        isLoadingJellyfin = true
        defer { isLoadingJellyfin = false }

        let client = core
        async let viewsTask = try? client.jellyfinViews(serverId: targetServerId)
        async let resumeTask = try? client.jellyfinResume(serverId: targetServerId, limit: 18)
        async let nextUpTask = try? client.jellyfinNextUp(serverId: targetServerId, limit: 18)

        let views = (await viewsTask) ?? []
        let resume = (await resumeTask) ?? []
        let nextUp = (await nextUpTask) ?? []

        // Guard against race if user switched servers while awaiting!
        guard selectedJellyfinServerId == targetServerId else { return }

        self.jellyfinViews = views
        self.jellyfinResumeItems = resume
        self.jellyfinNextUpItems = nextUp

        var latestMap: [String: [MetaItem]] = [:]
        for view in views.prefix(6) {
            if let latest = try? await client.jellyfinLatest(serverId: targetServerId, parentId: view.id, limit: 18),
               !latest.isEmpty {
                latestMap[view.id] = latest
            }
        }
        guard selectedJellyfinServerId == targetServerId else { return }
        self.jellyfinLatestByView = latestMap
    }

    public func loadJellyfinLibraryView(viewId: String) async {
        let targetServerId = selectedJellyfinServerId
        guard !targetServerId.isEmpty else { return }
        jellyfinMode = "view:\(viewId)"
        isLoadingJellyfin = true
        defer { isLoadingJellyfin = false }

        async let genresTask = try? core.jellyfinGenres(serverId: targetServerId, parentId: viewId)
        async let yearsTask = try? core.jellyfinYears(serverId: targetServerId, parentId: viewId)
        let order = (jellyfinSortBy == "SortName") ? "Ascending" : "Descending"
        async let itemsTask = try? core.jellyfinItems(
            serverId: targetServerId,
            parentId: viewId,
            genre: jellyfinSelectedGenre == "All" ? nil : jellyfinSelectedGenre,
            year: jellyfinSelectedYear == "All" ? nil : jellyfinSelectedYear,
            sortBy: jellyfinSortBy,
            sortOrder: order,
            startIndex: 0,
            limit: 72
        )

        let g = (await genresTask) ?? []
        let y = (await yearsTask) ?? []
        let items = (await itemsTask) ?? []

        guard selectedJellyfinServerId == targetServerId else { return }
        self.jellyfinGenres = g
        self.jellyfinYears = y
        self.jellyfinLibraryItems = items
    }

    public func executeJellyfinSearch() async {
        let targetServerId = selectedJellyfinServerId
        let q = jellyfinSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !targetServerId.isEmpty, !q.isEmpty else { return }
        jellyfinMode = "search"
        isLoadingJellyfin = true
        defer { isLoadingJellyfin = false }

        let res = (try? await core.jellyfinSearch(serverId: targetServerId, query: q, limit: 40)) ?? []
        guard selectedJellyfinServerId == targetServerId else { return }
        jellyfinSearchResults = res
    }

    public func scanJellyfinLAN() async {
        isScanningLAN = true
        jfAuthError = nil
        defer { isScanningLAN = false }
        lanDiscoveredServers = (try? await core.jellyfinDiscoverLAN(timeoutMs: 1600)) ?? []
        if let first = lanDiscoveredServers.first, jfInputLanUrl.isEmpty {
            jfInputLanUrl = first.address
        }
    }

    public func startJellyfinQuickConnect() async {
        let base = jfInputLanUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !base.isEmpty else {
            jfAuthError = "Enter or discover a Jellyfin server URL first."
            return
        }
        jfAuthError = nil
        jfQuickConnectPollTask?.cancel()
        do {
            let qc = try await core.jellyfinInitiateQuickConnect(baseUrl: base)
            jfQuickConnectState = qc
            let rem = jfInputRemoteUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            jfQuickConnectPollTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(2.5))
                    guard let self, let currentQC = self.jfQuickConnectState else { break }
                    if let prof = try? await self.core.jellyfinPollQuickConnect(
                        lanUrl: base,
                        remoteUrl: rem.isEmpty ? nil : rem,
                        secret: currentQC.secret
                    ) {
                        self.jfQuickConnectState = nil
                        self.showAddJellyfinSheet = false
                        await self.reloadJellyfinServersAndSelect(prof.serverId)
                        self.showToast("Connected to Jellyfin · \(prof.serverName)")
                        break
                    }
                }
            }
        } catch {
            jfAuthError = error.localizedDescription
        }
    }

    public func loginJellyfinWithPassword() async {
        let lan = jfInputLanUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let user = jfInputUsername.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !lan.isEmpty, !user.isEmpty else {
            jfAuthError = "Server URL and username are required."
            return
        }
        jfAuthError = nil
        do {
            let rem = jfInputRemoteUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            let prof = try await core.jellyfinLoginPassword(
                lanUrl: lan,
                remoteUrl: rem.isEmpty ? nil : rem,
                username: user,
                password: jfInputPassword
            )
            jfInputPassword = ""
            showAddJellyfinSheet = false
            await reloadJellyfinServersAndSelect(prof.serverId)
            showToast("Signed in to \(prof.serverName) as \(prof.username)")
        } catch {
            jfAuthError = error.localizedDescription
        }
    }

    public func removeJellyfinServer(_ serverId: String) async {
        try? await core.jellyfinRemoveServer(serverId: serverId)
        jellyfinServers = (try? await core.jellyfinListServers()) ?? []
        selectedJellyfinServerId = jellyfinServers.first?.serverId ?? ""
        await loadActiveJellyfinServerOverview()
        await rebuildContinueWatching()
    }

    private func reloadJellyfinServersAndSelect(_ serverId: String) async {
        jellyfinServers = (try? await core.jellyfinListServers()) ?? []
        selectedJellyfinServerId = serverId
        jellyfinMode = "overview"
        await loadActiveJellyfinServerOverview()
        await rebuildContinueWatching()
    }
}
