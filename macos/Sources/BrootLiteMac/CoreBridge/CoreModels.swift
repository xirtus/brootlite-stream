import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// JSON-RPC Envelope
// ─────────────────────────────────────────────────────────────────────────────

public struct CoreRPCEnvelope<ResultType: Decodable & Sendable>: Decodable, Sendable {
    public let ok: Bool
    public let result: ResultType?
    public let error: String?
}

public enum CoreBridgeError: LocalizedError, Sendable {
    case engineUnavailable
    case invalidUTF8
    case rpcFailed(method: String, message: String)
    case decodingFailed(method: String, detail: String)

    public var errorDescription: String? {
        switch self {
        case .engineUnavailable:
            return "BrootLite Core Engine failed to initialize."
        case .invalidUTF8:
            return "Invalid UTF-8 payload across C-ABI boundary."
        case let .rpcFailed(method, message):
            return "\(method): \(message)"
        case let .decodingFailed(method, detail):
            return "Failed decoding \(method): \(detail)"
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Engine Status & Config Models
// ─────────────────────────────────────────────────────────────────────────────

public enum SwarmFilter: String, Codable, CaseIterable, Sendable, Identifiable {
    case safeOnly = "SAFE_ONLY"
    case all = "ALL"
    case adultOnly = "ADULT_ONLY"

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .safeOnly: return "Safe Only"
        case .all: return "All (Safe + 18+)"
        case .adultOnly: return "18+ Only"
        }
    }
}

public enum DiscoverSortMode: String, Codable, CaseIterable, Sendable, Identifiable {
    case popular = "POPULAR"
    case topRated = "TOP_RATED"
    case yearDesc = "YEAR_DESC"
    case titleAsc = "TITLE_ASC"

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .popular: return "Popular"
        case .topRated: return "IMDb Rating"
        case .yearDesc: return "Newest Year"
        case .titleAsc: return "Title A–Z"
        }
    }
}

public struct TorrentEngineConfig: Codable, Equatable, Sendable {
    public var maxCacheMb: UInt32
    public var windowAheadMb: UInt32
    public var prebufferMb: UInt32
    public var maxConnections: UInt32

    public init(
        maxCacheMb: UInt32 = 512,
        windowAheadMb: UInt32 = 48,
        prebufferMb: UInt32 = 12,
        maxConnections: UInt32 = 120
    ) {
        self.maxCacheMb = maxCacheMb
        self.windowAheadMb = windowAheadMb
        self.prebufferMb = prebufferMb
        self.maxConnections = maxConnections
    }
}

public struct MultipassProfile: Codable, Equatable, Sendable {
    public var endpointUrl: String
    public var pairToken: String
    public var vaultUnlocked: Bool
    public var deviceId: String
    public var deviceName: String
    public var handle: String
    public var lastSyncedMs: UInt64
    public var lastSyncedRevision: UInt64

    public init(
        endpointUrl: String = "",
        pairToken: String = "",
        vaultUnlocked: Bool = false,
        deviceId: String = "",
        deviceName: String = "",
        handle: String = "",
        lastSyncedMs: UInt64 = 0,
        lastSyncedRevision: UInt64 = 0
    ) {
        self.endpointUrl = endpointUrl
        self.pairToken = pairToken
        self.vaultUnlocked = vaultUnlocked
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.handle = handle
        self.lastSyncedMs = lastSyncedMs
        self.lastSyncedRevision = lastSyncedRevision
    }
}

public struct EngineStatus: Codable, Equatable, Sendable {
    public var version: String
    public var dataDir: String
    public var mediaServerBaseUrl: String
    public var mediaServerPort: UInt16
    public var stremioLinked: Bool
    public var stremioEmail: String
    public var multipassPaired: Bool
    public var multipassProfile: MultipassProfile
    public var jellyfinCount: Int
    public var youtubeCount: Int
    public var twitchLoggedIn: Bool
    public var swarmFilter: SwarmFilter
    public var discoverSortMode: DiscoverSortMode
    public var torrentConfig: TorrentEngineConfig
}

// ─────────────────────────────────────────────────────────────────────────────
// Stremio & Shared Catalog / Stream / Playback Models
// ─────────────────────────────────────────────────────────────────────────────

public struct AddonCatalogDef: Codable, Hashable, Sendable, Identifiable {
    public var mediaType: String
    public var id: String
    public var name: String
    public var genres: [String]
    public var supportsSearch: Bool
    public var supportsGenre: Bool
    public var supportsSkip: Bool

    enum CodingKeys: String, CodingKey {
        case mediaType = "type"
        case id, name, genres, supportsSearch, supportsGenre, supportsSkip
    }
}

public struct AddonResourceSpec: Codable, Hashable, Sendable {
    public var name: String
    public var types: [String]
    public var idPrefixes: [String]
}

public struct InstalledAddon: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: String
    public var description: String
    public var transportUrl: String
    public var baseUrl: String
    public var types: [String]
    public var resources: [String]
    public var resourceSpecs: [AddonResourceSpec]
    public var idPrefixes: [String]
    public var catalogs: [AddonCatalogDef]
}

public struct VideoEpisode: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var season: Int
    public var episode: Int
    public var overview: String
    public var released: String
    public var thumbnail: String

    public init(
        id: String,
        title: String,
        season: Int = 0,
        episode: Int = 0,
        overview: String = "",
        released: String = "",
        thumbnail: String = ""
    ) {
        self.id = id
        self.title = title
        self.season = season
        self.episode = episode
        self.overview = overview
        self.released = released
        self.thumbnail = thumbnail
    }

    public var seasonEpisodeCode: String {
        if season > 0 || episode > 0 {
            return String(format: "S%02dE%02d", max(0, season), max(0, episode))
        }
        return "EP"
    }
}

public struct MetaItem: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var mediaType: String
    public var name: String
    public var poster: String
    public var background: String
    public var description: String
    public var releaseInfo: String
    public var imdbRating: String
    public var runtime: String
    public var genres: [String]
    public var videos: [VideoEpisode]
    public var addonSource: String

    enum CodingKeys: String, CodingKey {
        case id
        case mediaType = "type"
        case name, poster, background, description, releaseInfo, imdbRating, runtime, genres, videos, addonSource
    }

    public init(
        id: String,
        mediaType: String,
        name: String,
        poster: String = "",
        background: String = "",
        description: String = "",
        releaseInfo: String = "",
        imdbRating: String = "",
        runtime: String = "",
        genres: [String] = [],
        videos: [VideoEpisode] = [],
        addonSource: String = ""
    ) {
        self.id = id
        self.mediaType = mediaType
        self.name = name
        self.poster = poster
        self.background = background
        self.description = description
        self.releaseInfo = releaseInfo
        self.imdbRating = imdbRating
        self.runtime = runtime
        self.genres = genres
        self.videos = videos
        self.addonSource = addonSource
    }

    public var isJellyfin: Bool {
        id.hasPrefix("jf:")
    }

    public var jellyfinServerId: String? {
        guard id.hasPrefix("jf:") else { return nil }
        let parts = id.dropFirst(3).split(separator: ":", maxSplits: 1)
        guard parts.count == 2 else { return nil }
        return String(parts[0])
    }

    public var isSeriesLike: Bool {
        let lower = mediaType.lowercased()
        return lower == "series" || lower == "tv" || lower == "anime" || !videos.isEmpty
    }
}

public struct StreamCandidate: Codable, Hashable, Sendable, Identifiable {
    public var title: String
    public var subtitle: String
    public var addonName: String
    public var directUrl: String?
    public var infoHash: String?
    public var fileIdx: Int?
    public var sources: [String]
    public var requestHeaders: [String: String]
    public var externalUrl: String?
    public var bingeGroup: String
    public var filename: String
    public var videoSizeBytes: UInt64
    public var seeders: UInt32
    public var resolutionScore: Int32

    public var id: String {
        let u = directUrl ?? ""
        let h = infoHash ?? ""
        let f = fileIdx.map(String.init) ?? ""
        return "\(addonName)|\(title)|\(u)|\(h)|\(f)"
    }

    public var isDirectHTTP: Bool {
        guard let u = directUrl else { return false }
        return !u.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public var isP2P: Bool {
        !isDirectHTTP && !(infoHash ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    public var resolutionBadge: String {
        if resolutionScore >= 2160 { return "4K UHD" }
        if resolutionScore >= 1440 { return "1440p" }
        if resolutionScore >= 1080 { return "1080p" }
        if resolutionScore >= 720 { return "720p" }
        if resolutionScore > 0 { return "\(resolutionScore)p" }
        return isDirectHTTP ? "DIRECT" : "SWARM"
    }

    public var formattedSize: String? {
        guard videoSizeBytes > 0 else { return nil }
        let gb = Double(videoSizeBytes) / (1024.0 * 1024.0 * 1024.0)
        if gb >= 1.0 {
            return String(format: "%.2f GB", gb)
        }
        let mb = Double(videoSizeBytes) / (1024.0 * 1024.0)
        return String(format: "%.0f MB", mb)
    }

    public var magnetURI: String? {
        guard let rawHash = infoHash?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !rawHash.isEmpty else {
            return nil
        }
        var trackers: [String] = sources.compactMap { src in
            if src.hasPrefix("tracker:") {
                let t = String(src.dropFirst("tracker:".count))
                return t.isEmpty ? nil : t
            }
            return nil
        }
        let defaults = [
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://open.demonii.com:1337/announce"
        ]
        for fb in defaults where !trackers.contains(fb) {
            trackers.append(fb)
        }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-._~"))
        let dn = title.addingPercentEncoding(withAllowedCharacters: allowed) ?? "video"
        let trParams = trackers.map { tr in
            let enc = tr.addingPercentEncoding(withAllowedCharacters: allowed) ?? tr
            return "&tr=\(enc)"
        }.joined()
        return "magnet:?xt=urn:btih:\(rawHash)&dn=\(dn)\(trParams)"
    }

    public func toSelectionContext() -> StreamSelectionContext {
        StreamSelectionContext(
            bingeGroup: bingeGroup,
            addonName: addonName,
            title: title,
            filename: filename,
            wasDirectHttp: isDirectHTTP,
            resolutionScore: resolutionScore
        )
    }
}

public struct ExternalSubtitle: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var url: String
    public var lang: String
    public var addonName: String
}

public struct StreamSelectionContext: Codable, Hashable, Sendable {
    public var bingeGroup: String
    public var addonName: String
    public var title: String
    public var filename: String
    public var wasDirectHttp: Bool
    public var resolutionScore: Int32

    public init(
        bingeGroup: String = "",
        addonName: String = "",
        title: String = "",
        filename: String = "",
        wasDirectHttp: Bool = false,
        resolutionScore: Int32 = 0
    ) {
        self.bingeGroup = bingeGroup
        self.addonName = addonName
        self.title = title
        self.filename = filename
        self.wasDirectHttp = wasDirectHttp
        self.resolutionScore = resolutionScore
    }
}

public struct RankedStreamCandidate: Codable, Hashable, Sendable {
    public var candidate: StreamCandidate
    public var similarityScore: Double
    public var matchedReasons: [String]
}

public struct WatchHistoryEntry: Codable, Hashable, Sendable, Identifiable {
    public var itemId: String
    public var mediaType: String
    public var title: String
    public var poster: String
    public var videoId: String
    public var episodeTitle: String
    public var positionMs: UInt64
    public var durationMs: UInt64
    public var updatedAtMs: UInt64
    public var lastStreamContext: StreamSelectionContext?

    public var id: String { "\(itemId)|\(videoId)" }

    enum CodingKeys: String, CodingKey {
        case itemId
        case mediaType = "type"
        case title, poster, videoId, episodeTitle, positionMs, durationMs, updatedAtMs, lastStreamContext
    }

    public init(
        itemId: String,
        mediaType: String,
        title: String,
        poster: String = "",
        videoId: String = "",
        episodeTitle: String = "",
        positionMs: UInt64 = 0,
        durationMs: UInt64 = 0,
        updatedAtMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000),
        lastStreamContext: StreamSelectionContext? = nil
    ) {
        self.itemId = itemId
        self.mediaType = mediaType
        self.title = title
        self.poster = poster
        self.videoId = videoId
        self.episodeTitle = episodeTitle
        self.positionMs = positionMs
        self.durationMs = durationMs
        self.updatedAtMs = updatedAtMs
        self.lastStreamContext = lastStreamContext
    }

    public var progressRatio: Double {
        guard durationMs > 0 else { return 0 }
        return min(1.0, max(0.0, Double(positionMs) / Double(durationMs)))
    }
}

public struct FavoriteEntry: Codable, Hashable, Sendable, Identifiable {
    public var itemId: String
    public var mediaType: String
    public var title: String
    public var poster: String
    public var imdbRating: String
    public var releaseInfo: String
    public var addedAtMs: UInt64

    public var id: String { itemId }

    enum CodingKeys: String, CodingKey {
        case itemId
        case mediaType = "type"
        case title, poster, imdbRating, releaseInfo, addedAtMs
    }

    public init(
        itemId: String,
        mediaType: String,
        title: String,
        poster: String = "",
        imdbRating: String = "",
        releaseInfo: String = "",
        addedAtMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.itemId = itemId
        self.mediaType = mediaType
        self.title = title
        self.poster = poster
        self.imdbRating = imdbRating
        self.releaseInfo = releaseInfo
        self.addedAtMs = addedAtMs
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stremio Auth / Sync Models
// ─────────────────────────────────────────────────────────────────────────────

public struct StremioLinkSession: Codable, Hashable, Sendable {
    public var code: String
    public var link: String
    public var qrcodeUrl: String
}

public struct StremioSyncSummary: Codable, Hashable, Sendable {
    public var email: String
    public var syncedAddonsCount: Int
    public var syncedHistoryCount: Int
}

public struct StremioPollQrResponse: Codable, Hashable, Sendable {
    public var approved: Bool
    public var summary: StremioSyncSummary?
}

// ─────────────────────────────────────────────────────────────────────────────
// Jellyfin Multi-Server Models
// ─────────────────────────────────────────────────────────────────────────────

public struct JellyfinServerProfile: Codable, Hashable, Sendable, Identifiable {
    public var serverId: String
    public var serverName: String
    public var lanUrl: String
    public var remoteUrl: String
    public var userId: String
    public var username: String
    public var lastVerifiedMs: UInt64
    public var updatedAtMs: UInt64

    public var id: String { serverId }

    public var displayUrl: String {
        !lanUrl.isEmpty ? lanUrl : remoteUrl
    }
}

public struct DiscoveredJellyfinServer: Codable, Hashable, Sendable, Identifiable {
    public var serverId: String
    public var name: String
    public var address: String

    public var id: String { serverId }
}

public struct JellyfinPublicInfo: Codable, Hashable, Sendable {
    public var serverId: String
    public var serverName: String
    public var quickConnectEnabled: Bool
}

public struct JellyfinQuickConnectState: Codable, Hashable, Sendable {
    public var secret: String
    public var code: String
    public var authenticated: Bool
}

public struct JellyfinView: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var collectionType: String
}

public struct JellyfinPlaybackSession: Codable, Hashable, Sendable {
    public var playUrl: String
    public var requestHeaders: [String: String]
    public var playMethod: String
    public var playSessionId: String
    public var mediaSourceId: String
    public var externalSubtitles: [ExternalSubtitle]
    public var runtimeMs: UInt64
    public var resumePositionMs: UInt64
}

public enum SkipSegmentKind: String, Codable, Hashable, Sendable {
    case intro = "INTRO"
    case outro = "OUTRO"
    case sponsor = "SPONSOR"
    case selfPromo = "SELF_PROMO"
    case interaction = "INTERACTION"
    case preview = "PREVIEW"
    case recap = "RECAP"
    case filler = "FILLER"
}

public struct SkipSegment: Codable, Hashable, Sendable, Identifiable {
    public var startMs: UInt64
    public var endMs: UInt64
    public var kind: SkipSegmentKind
    public var label: String

    public var id: String { "\(kind.rawValue)-\(startMs)-\(endMs)" }
}

// ─────────────────────────────────────────────────────────────────────────────
// YouTube & Creators (Twitch, Odysee, Rumble) Models
// ─────────────────────────────────────────────────────────────────────────────

public struct YouTubeAccountProfile: Codable, Hashable, Sendable, Identifiable {
    public var accountId: String
    public var accountName: String
    public var channelHandle: String
    public var avatarUrl: String
    public var pageId: String
    public var expiresAtMs: UInt64
    public var updatedAtMs: UInt64

    public var id: String { accountId }
}

public struct YouTubeAccountsResponse: Codable, Hashable, Sendable {
    public var activeAccountId: String
    public var accounts: [YouTubeAccountProfile]
}

public struct DeviceCodeSession: Codable, Hashable, Sendable {
    public var deviceCode: String
    public var userCode: String
    public var verificationUrl: String
    public var expiresInSec: UInt64
    public var intervalSec: UInt64
    public var clientId: String
    public var clientSecret: String
}

public struct CreatorVideoItem: Codable, Hashable, Sendable, Identifiable {
    public var platform: String
    public var videoId: String
    public var title: String
    public var channelName: String
    public var channelId: String
    public var channelAvatar: String
    public var thumbnailUrl: String
    public var durationSec: Int64
    public var durationText: String
    public var viewCountText: String
    public var publishedText: String
    public var isLive: Bool
    public var isShort: Bool
    public var resumeProgressPct: Int32
    public var categoryName: String
    public var directUrlHint: String

    public var id: String { "\(platform):\(videoId)" }
}

public struct CreatorChannel: Codable, Hashable, Sendable, Identifiable {
    public var platform: String
    public var channelId: String
    public var name: String
    public var handle: String
    public var avatarUrl: String
    public var bannerUrl: String
    public var subscriberText: String
    public var description: String
    public var isSubscribed: Bool
    public var liveStream: CreatorVideoItem?
    public var videos: [CreatorVideoItem]

    public var id: String { "\(platform):\(channelId)" }
}

public struct CreatorFeedPage: Codable, Hashable, Sendable {
    public var items: [CreatorVideoItem]
    public var continuationToken: String?
}

public struct CreatorCategory: Codable, Hashable, Sendable, Identifiable {
    public var platform: String
    public var id: String
    public var name: String
    public var boxArtUrl: String
    public var viewersText: String
}

public struct CreatorFollowEntry: Codable, Hashable, Sendable, Identifiable {
    public var platform: String
    public var channelId: String
    public var name: String
    public var handle: String
    public var avatarUrl: String
    public var addedAtMs: UInt64
    public var removedAtMs: UInt64?

    public var id: String { "\(platform):\(channelId)" }

    public init(
        platform: String,
        channelId: String,
        name: String,
        handle: String = "",
        avatarUrl: String = "",
        addedAtMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000),
        removedAtMs: UInt64? = nil
    ) {
        self.platform = platform
        self.channelId = channelId
        self.name = name
        self.handle = handle
        self.avatarUrl = avatarUrl
        self.addedAtMs = addedAtMs
        self.removedAtMs = removedAtMs
    }
}

public struct TwitchAuthProfile: Codable, Hashable, Sendable {
    public var userId: String
    public var login: String
    public var displayName: String
    public var updatedAtMs: UInt64
}

public struct ResolvedMediaStream: Codable, Hashable, Sendable {
    public var playUrl: String
    public var protocolType: String
    public var title: String
    public var author: String
    public var durationSec: UInt64
    public var isLive: Bool
    public var qualityLabel: String
    public var requestHeaders: [String: String]
    public var subtitles: [ExternalSubtitle]
    public var skipSegments: [SkipSegment]
    public var playbackTrackingUrl: String
    public var watchtimeTrackingUrl: String

    enum CodingKeys: String, CodingKey {
        case playUrl
        case protocolType = "protocol"
        case title, author, durationSec, isLive, qualityLabel, requestHeaders, subtitles, skipSegments
        case playbackTrackingUrl, watchtimeTrackingUrl
    }
}

public struct ReturnYouTubeDislikeVotes: Codable, Hashable, Sendable {
    public var likes: UInt64
    public var dislikes: UInt64
    public var viewCount: UInt64
    public var rating: Double
}

public struct DeArrowTitleResponse: Codable, Hashable, Sendable {
    public var title: String?
}

// ─────────────────────────────────────────────────────────────────────────────
// Multipass v2 E2EE Pairing & Sync Models
// ─────────────────────────────────────────────────────────────────────────────

public struct MultipassProfileEnvelope: Codable, Hashable, Sendable {
    public var paired: Bool
    public var profile: MultipassProfile

    public static func == (lhs: MultipassProfileEnvelope, rhs: MultipassProfileEnvelope) -> Bool {
        lhs.paired == rhs.paired && lhs.profile == rhs.profile
    }

    public func hash(into hasher: inout Hasher) {
        hasher.combine(paired)
        hasher.combine(profile.endpointUrl)
        hasher.combine(profile.deviceId)
    }
}

public struct MultipassPairingSession: Codable, Hashable, Sendable {
    public var originUrl: String
    public var pairingId: String
    public var pollSecret: String
    public var userCode: String
    public var verificationUrl: String
    public var qrVerificationUrl: String
    public var tvPublicKeyB64Url: String
    public var expiresInSec: UInt64
    public var intervalSec: UInt64
}

public enum MultipassPairingPollOutcome: Decodable, Equatable, Sendable {
    case pending
    case approved(deviceId: String, deviceName: String, scope: String, e2eeUnlocked: Bool)
    case denied
    case expired

    private enum CodingKeys: String, CodingKey {
        case status
        case deviceId
        case device_id
        case deviceName
        case device_name
        case scope
        case e2eeUnlocked
        case e2ee_unlocked
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let status = (try? c.decode(String.self, forKey: .status))?.lowercased() ?? "pending"
        switch status {
        case "approved":
            let devId = (try? c.decode(String.self, forKey: .device_id))
                ?? (try? c.decode(String.self, forKey: .deviceId))
                ?? ""
            let devName = (try? c.decode(String.self, forKey: .device_name))
                ?? (try? c.decode(String.self, forKey: .deviceName))
                ?? "BrootLite Mac"
            let scope = (try? c.decode(String.self, forKey: .scope)) ?? "media:sync"
            let unlocked = (try? c.decode(Bool.self, forKey: .e2ee_unlocked))
                ?? (try? c.decode(Bool.self, forKey: .e2eeUnlocked))
                ?? false
            self = .approved(
                deviceId: devId,
                deviceName: devName,
                scope: scope,
                e2eeUnlocked: unlocked
            )
        case "denied":
            self = .denied
        case "expired":
            self = .expired
        default:
            self = .pending
        }
    }
}

public struct MultipassSyncReport: Codable, Hashable, Sendable {
    public var stremioLinked: Bool
    public var addonsImported: Int
    public var jellyfinServersSynced: Int
    public var youtubeAccountsSynced: Int
    public var twitchSynced: Bool
    public var creatorFollowsSynced: Int
    public var watchHistoryMerged: Int
    public var favoritesMerged: Int
    public var revision: UInt64
    public var vaultLockedNeedsPassphrase: Bool
}

public struct MultipassPushResponse: Codable, Hashable, Sendable {
    public var revision: UInt64
}

// ─────────────────────────────────────────────────────────────────────────────
// Swarm, Torznab & Kodi Bridge Models
// ─────────────────────────────────────────────────────────────────────────────

public struct TorrentStreamSession: Codable, Hashable, Sendable {
    public var sessionId: String
    public var playUrl: String
    public var fileName: String
    public var fileSizeBytes: UInt64
    public var fileIdx: Int
    public var infoHash: String
}

public struct SwarmStatsSnapshot: Codable, Hashable, Sendable {
    public var sessionId: String
    public var state: String
    public var downloadSpeedMbps: Double
    public var progressBytes: UInt64
    public var totalBytes: UInt64
    public var livePeers: UInt32
    public var formattedOverlay: String
}

public struct TorznabConfigSnapshot: Codable, Hashable, Sendable {
    public var url: String
    public var hasApiKey: Bool
}

public struct KodiAddonInfo: Codable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var version: String
    public var summary: String
    public var iconPath: String
}

public struct KodiDirectoryItem: Codable, Hashable, Sendable, Identifiable {
    public var label: String
    public var url: String
    public var isFolder: Bool
    public var isPlayable: Bool
    public var thumb: String
    public var fanart: String
    public var plot: String
    public var year: String

    public var id: String { "\(label)|\(url)" }
}

public struct KodiBridgeResponse: Codable, Hashable, Sendable {
    public var ok: Bool
    public var error: String
    public var resolvedUrl: String
    public var items: [KodiDirectoryItem]
}

public struct KodiParsedStream: Codable, Hashable, Sendable {
    public var url: String
    public var headers: [String: String]
}

// Small Ack helper structs
public struct ToggleFavoriteResult: Codable, Sendable { public var isFavorite: Bool }
public struct ToggleFollowResult: Codable, Sendable { public var following: Bool }
public struct GenericAckResult: Codable, Sendable {
    public var saved: Bool?
    public var removed: Bool?
    public var stopped: Bool?
    public var reported: Bool?
    public var updated: Bool?
    public var pushed: Bool?
    public var loggedOut: Bool?
    public var disconnected: Bool?
}
