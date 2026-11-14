import CBrootLiteBridge
import Foundation
import Security

// ─────────────────────────────────────────────────────────────────────────────
// Thread-Safe FFI Handle & Continuation Box
// ─────────────────────────────────────────────────────────────────────────────

private final class EngineHandleBox: @unchecked Sendable {
    private let lock = NSLock()
    private var rawHandle: OpaquePointer?

    init(dataDir: String? = nil) {
        if let dir = dataDir, !dir.isEmpty {
            self.rawHandle = dir.withCString { cStr in
                brootlite_engine_new(cStr)
            }
        } else {
            self.rawHandle = brootlite_engine_new(nil)
        }
    }

    var isValid: Bool {
        lock.lock()
        defer { lock.unlock() }
        return rawHandle != nil
    }

    func callAsync(
        method: String,
        paramsJSON: String,
        continuationBox: Unmanaged<AsyncJSONContinuationBox>
    ) -> Bool {
        lock.lock()
        guard let handle = rawHandle else {
            lock.unlock()
            return false
        }
        method.withCString { methodPtr in
            paramsJSON.withCString { paramsPtr in
                brootlite_call_json_async(
                    handle,
                    methodPtr,
                    paramsPtr,
                    continuationBox.toOpaque(),
                    brootliteAsyncTrampoline
                )
            }
        }
        lock.unlock()
        return true
    }

    func shutdownAndFree() {
        lock.lock()
        let handle = rawHandle
        rawHandle = nil
        lock.unlock()
        if let handle {
            brootlite_engine_free(handle)
        }
    }

    deinit {
        shutdownAndFree()
    }
}

private final class AsyncJSONContinuationBox: @unchecked Sendable {
    private let continuation: CheckedContinuation<Data, Error>

    init(_ continuation: CheckedContinuation<Data, Error>) {
        self.continuation = continuation
    }

    func resumeWithJSON(_ utf8Ptr: UnsafePointer<CChar>?) {
        guard let utf8Ptr else {
            continuation.resume(throwing: CoreBridgeError.invalidUTF8)
            return
        }
        let len = strlen(utf8Ptr)
        let data = Data(bytes: utf8Ptr, count: len)
        continuation.resume(returning: data)
    }

    func resumeWithUnavailable() {
        continuation.resume(throwing: CoreBridgeError.engineUnavailable)
    }
}

private func brootliteAsyncTrampoline(
    userCtx: UnsafeMutableRawPointer?,
    responseUtf8: UnsafePointer<CChar>?
) {
    guard let userCtx else { return }
    let box = Unmanaged<AsyncJSONContinuationBox>
        .fromOpaque(userCtx)
        .takeRetainedValue()
    box.resumeWithJSON(responseUtf8)
}

// ─────────────────────────────────────────────────────────────────────────────
// macOS Keychain Vault Metadata Store (Zero-Secret Logging)
// ─────────────────────────────────────────────────────────────────────────────

public enum KeychainVaultStore {
    private static let service = "app.brootlite.macos.multipass"

    public static func saveVaultRecord(account: String, payload: Data) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: payload,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        _ = SecItemAdd(addQuery as CFDictionary, nil)
    }

    public static func loadVaultRecord(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var out: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &out)
        guard status == errSecSuccess else { return nil }
        return out as? Data
    }

    public static func deleteVaultRecord(account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// BrootLiteCoreClient Actor — Non-Blocking Async Swift 6 RPC Client
// ─────────────────────────────────────────────────────────────────────────────

public actor BrootLiteCoreClient {
    public static let shared = BrootLiteCoreClient()

    private let handleBox: EngineHandleBox
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(dataDir: String? = nil) {
        self.handleBox = EngineHandleBox(dataDir: dataDir)
    }

    public func shutdown() {
        handleBox.shutdownAndFree()
    }

    // MARK: - Core Generic JSON-RPC Dispatcher

    public func call<Response: Decodable & Sendable>(
        _ method: String,
        paramsJSON: String = "{}"
    ) async throws -> Response {
        let rawData: Data = try await withCheckedThrowingContinuation { continuation in
            let box = AsyncJSONContinuationBox(continuation)
            let retained = Unmanaged.passRetained(box)
            if !handleBox.callAsync(method: method, paramsJSON: paramsJSON, continuationBox: retained) {
                let reclaimed = retained.takeRetainedValue()
                reclaimed.resumeWithUnavailable()
            }
        }

        let envelope: CoreRPCEnvelope<Response>
        do {
            envelope = try decoder.decode(CoreRPCEnvelope<Response>.self, from: rawData)
        } catch {
            if let fallbackErr = try? JSONSerialization.jsonObject(with: rawData) as? [String: Any],
               let ok = fallbackErr["ok"] as? Bool, !ok,
               let msg = fallbackErr["error"] as? String {
                throw CoreBridgeError.rpcFailed(method: method, message: msg)
            }
            throw CoreBridgeError.decodingFailed(method: method, detail: error.localizedDescription)
        }

        if !envelope.ok {
            throw CoreBridgeError.rpcFailed(
                method: method,
                message: envelope.error ?? "Unknown core error"
            )
        }
        if let res = envelope.result {
            return res
        }
        // Handle Optional<T> when `"result": null`
        let nullData = Data("null".utf8)
        if let nullable = try? decoder.decode(Response.self, from: nullData) {
            return nullable
        }
        throw CoreBridgeError.decodingFailed(method: method, detail: "Missing result field")
    }

    public func callEncodable<Params: Encodable & Sendable, Response: Decodable & Sendable>(
        _ method: String,
        params: Params
    ) async throws -> Response {
        let jsonData = try encoder.encode(params)
        let jsonString = String(data: jsonData, encoding: .utf8) ?? "{}"
        return try await call(method, paramsJSON: jsonString)
    }

    private func callDict<Response: Decodable & Sendable>(
        _ method: String,
        _ dict: [String: Any?]
    ) async throws -> Response {
        var clean: [String: Any] = [:]
        for (k, v) in dict {
            if let unwrapped = v {
                clean[k] = unwrapped
            }
        }
        let data = try JSONSerialization.data(withJSONObject: clean)
        let jsonStr = String(data: data, encoding: .utf8) ?? "{}"
        return try await call(method, paramsJSON: jsonStr)
    }

    // MARK: - Engine Status & Config

    public func engineStatus() async throws -> EngineStatus {
        try await call("engine.status")
    }

    public func getAddons() async throws -> [InstalledAddon] {
        try await call("config.get_addons")
    }

    public func removeAddon(id: String) async throws {
        let _: GenericAckResult = try await callDict("config.remove_addon", ["addonId": id])
    }

    public func getWatchHistory() async throws -> [WatchHistoryEntry] {
        try await call("config.get_watch_history")
    }

    public func upsertWatchHistory(_ entry: WatchHistoryEntry) async throws {
        let _: GenericAckResult = try await callEncodable("config.upsert_watch_history", params: entry)
    }

    public func recordStreamSelection(itemId: String, context: StreamSelectionContext) async throws {
        struct Payload: Encodable, Sendable {
            let itemId: String
            let context: StreamSelectionContext
        }
        let _: GenericAckResult = try await callEncodable(
            "config.record_stream_selection",
            params: Payload(itemId: itemId, context: context)
        )
    }

    public func getLastStreamContext(itemId: String) async throws -> StreamSelectionContext? {
        try await callDict("config.get_last_stream_context", ["itemId": itemId])
    }

    public func getFavorites() async throws -> [FavoriteEntry] {
        try await call("config.get_favorites")
    }

    public func toggleFavorite(_ entry: FavoriteEntry) async throws -> Bool {
        let res: ToggleFavoriteResult = try await callEncodable("config.toggle_favorite", params: entry)
        return res.isFavorite
    }

    public func setSwarmFilter(_ filter: SwarmFilter) async throws {
        struct Res: Decodable, Sendable { let swarmFilter: SwarmFilter }
        let _: Res = try await callDict("config.set_swarm_filter", ["filter": filter.rawValue])
    }

    public func setDiscoverSortMode(_ mode: DiscoverSortMode) async throws {
        struct Res: Decodable, Sendable { let discoverSortMode: DiscoverSortMode }
        let _: Res = try await callDict("config.set_discover_sort_mode", ["mode": mode.rawValue])
    }

    public func setTorrentConfig(_ cfg: TorrentEngineConfig) async throws -> TorrentEngineConfig {
        try await callEncodable("config.set_torrent_config", params: cfg)
    }

    public func getTorznab() async throws -> TorznabConfigSnapshot {
        try await call("config.get_torznab")
    }

    public func setTorznab(url: String, apiKey: String) async throws {
        let _: GenericAckResult = try await callDict(
            "config.set_torznab",
            ["url": url, "apiKey": apiKey]
        )
    }

    public func getCreatorFollows(platform: String? = nil) async throws -> [CreatorFollowEntry] {
        try await callDict("config.get_creator_follows", ["platform": platform])
    }

    public func toggleCreatorFollow(_ entry: CreatorFollowEntry) async throws -> Bool {
        let res: ToggleFollowResult = try await callEncodable("config.toggle_creator_follow", params: entry)
        return res.following
    }

    // MARK: - Multipass v2 E2EE

    public func multipassProfile() async throws -> MultipassProfileEnvelope {
        try await call("multipass.get_profile")
    }

    public func multipassDisconnect() async throws {
        let _: GenericAckResult = try await call("multipass.disconnect")
        KeychainVaultStore.deleteVaultRecord(account: "paired_device")
    }

    public func multipassStartPairing(endpointUrl: String, deviceName: String) async throws -> MultipassPairingSession {
        try await callDict(
            "multipass.start_pairing",
            ["endpointUrl": endpointUrl, "deviceName": deviceName]
        )
    }

    public func multipassPollPairing(_ session: MultipassPairingSession) async throws -> MultipassPairingPollOutcome {
        let outcome: MultipassPairingPollOutcome = try await callDict(
            "multipass.poll_pairing",
            [
                "originUrl": session.originUrl,
                "pairingId": session.pairingId,
                "pollSecret": session.pollSecret,
                "userCode": session.userCode
            ]
        )
        if case let .approved(deviceId, deviceName, _, unlocked) = outcome {
            let meta = try? JSONSerialization.data(withJSONObject: [
                "deviceId": deviceId,
                "deviceName": deviceName,
                "originUrl": session.originUrl,
                "e2eeUnlocked": unlocked,
                "pairedAtMs": Int64(Date().timeIntervalSince1970 * 1000)
            ])
            if let meta {
                KeychainVaultStore.saveVaultRecord(account: "paired_device", payload: meta)
            }
        }
        return outcome
    }

    public func multipassSyncPull(passphrase: String? = nil) async throws -> MultipassSyncReport {
        let cleanPass = (passphrase?.isEmpty == false) ? passphrase : nil
        return try await callDict("multipass.sync_pull", ["passphrase": cleanPass])
    }

    public func multipassSyncPush(passphrase: String? = nil) async throws -> UInt64 {
        let cleanPass = (passphrase?.isEmpty == false) ? passphrase : nil
        let res: MultipassPushResponse = try await callDict("multipass.sync_push", ["passphrase": cleanPass])
        return res.revision
    }

    // MARK: - Stremio

    public func stremioCreateQrSession() async throws -> StremioLinkSession {
        try await call("stremio.create_qr_session")
    }

    public func stremioPollQrSession(code: String) async throws -> StremioPollQrResponse {
        try await callDict("stremio.poll_qr_session", ["code": code])
    }

    public func stremioSyncAccount(authKey: String? = nil) async throws -> StremioSyncSummary {
        try await callDict("stremio.sync_account", ["authKey": authKey])
    }

    public func stremioLogout() async throws {
        let _: GenericAckResult = try await call("stremio.logout")
    }

    public func stremioPushWatchProgress(_ entry: WatchHistoryEntry) async throws {
        let _: GenericAckResult = try await callEncodable("stremio.push_watch_progress", params: entry)
    }

    public func stremioInstallAddon(url: String) async throws -> InstalledAddon {
        try await callDict("stremio.install_addon", ["url": url])
    }

    public func stremioFetchCatalog(
        type: String,
        catalogId: String,
        addonId: String? = nil,
        transportUrl: String? = nil,
        search: String? = nil,
        genre: String? = nil,
        year: String? = nil,
        skip: Int = 0
    ) async throws -> [MetaItem] {
        try await callDict(
            "stremio.fetch_catalog",
            [
                "type": type,
                "catalogId": catalogId,
                "addonId": addonId,
                "transportUrl": transportUrl,
                "search": search,
                "genre": genre,
                "year": year,
                "skip": skip
            ]
        )
    }

    public func stremioSearch(query: String) async throws -> [MetaItem] {
        try await callDict("stremio.search", ["query": query])
    }

    public func stremioFetchMeta(type: String, itemId: String) async throws -> MetaItem {
        try await callDict("stremio.fetch_meta", ["type": type, "itemId": itemId])
    }

    public func stremioFetchStreams(type: String, videoId: String) async throws -> [StreamCandidate] {
        try await callDict("stremio.fetch_streams", ["type": type, "videoId": videoId])
    }

    public func stremioFetchSubtitles(type: String, videoId: String) async throws -> [ExternalSubtitle] {
        try await callDict("stremio.fetch_subtitles", ["type": type, "videoId": videoId])
    }

    // MARK: - Jellyfin (Strictly Server-Isolated via `serverId`)

    public func jellyfinListServers() async throws -> [JellyfinServerProfile] {
        try await call("jellyfin.list_servers")
    }

    public func jellyfinRemoveServer(serverId: String) async throws {
        let _: GenericAckResult = try await callDict("jellyfin.remove_server", ["serverId": serverId])
    }

    public func jellyfinDiscoverLAN(timeoutMs: Int = 1600) async throws -> [DiscoveredJellyfinServer] {
        try await callDict("jellyfin.discover_lan", ["timeoutMs": timeoutMs])
    }

    public func jellyfinPublicInfo(baseUrl: String) async throws -> JellyfinPublicInfo {
        try await callDict("jellyfin.public_info", ["baseUrl": baseUrl])
    }

    public func jellyfinInitiateQuickConnect(baseUrl: String) async throws -> JellyfinQuickConnectState {
        try await callDict("jellyfin.quick_connect_initiate", ["baseUrl": baseUrl])
    }

    public func jellyfinPollQuickConnect(
        lanUrl: String,
        remoteUrl: String? = nil,
        secret: String
    ) async throws -> JellyfinServerProfile? {
        try await callDict(
            "jellyfin.quick_connect_poll",
            ["lanUrl": lanUrl, "remoteUrl": remoteUrl, "secret": secret]
        )
    }

    public func jellyfinLoginPassword(
        lanUrl: String,
        remoteUrl: String? = nil,
        username: String,
        password: String
    ) async throws -> JellyfinServerProfile {
        try await callDict(
            "jellyfin.login_password",
            [
                "lanUrl": lanUrl,
                "remoteUrl": remoteUrl,
                "username": username,
                "password": password
            ]
        )
    }

    public func jellyfinViews(serverId: String) async throws -> [JellyfinView] {
        try await callDict("jellyfin.views", ["serverId": serverId])
    }

    public func jellyfinResume(serverId: String, limit: Int = 20) async throws -> [MetaItem] {
        try await callDict("jellyfin.resume", ["serverId": serverId, "limit": limit])
    }

    public func jellyfinNextUp(serverId: String, limit: Int = 20) async throws -> [MetaItem] {
        try await callDict("jellyfin.next_up", ["serverId": serverId, "limit": limit])
    }

    public func jellyfinLatest(serverId: String, parentId: String, limit: Int = 24) async throws -> [MetaItem] {
        try await callDict(
            "jellyfin.latest",
            ["serverId": serverId, "parentId": parentId, "limit": limit]
        )
    }

    public func jellyfinItems(
        serverId: String,
        parentId: String? = nil,
        includeItemTypes: String? = nil,
        genre: String? = nil,
        year: String? = nil,
        sortBy: String = "SortName",
        sortOrder: String = "Ascending",
        startIndex: Int = 0,
        limit: Int = 48
    ) async throws -> [MetaItem] {
        try await callDict(
            "jellyfin.items",
            [
                "serverId": serverId,
                "parentId": parentId,
                "includeItemTypes": includeItemTypes,
                "genre": genre,
                "year": year,
                "sortBy": sortBy,
                "sortOrder": sortOrder,
                "startIndex": startIndex,
                "limit": limit
            ]
        )
    }

    public func jellyfinGenres(serverId: String, parentId: String? = nil) async throws -> [String] {
        try await callDict("jellyfin.genres", ["serverId": serverId, "parentId": parentId])
    }

    public func jellyfinYears(serverId: String, parentId: String? = nil) async throws -> [String] {
        try await callDict("jellyfin.years", ["serverId": serverId, "parentId": parentId])
    }

    public func jellyfinItemDetail(serverId: String, itemId: String) async throws -> MetaItem {
        try await callDict("jellyfin.item_detail", ["serverId": serverId, "itemId": itemId])
    }

    public func jellyfinEpisodes(serverId: String, seriesId: String, seasonId: String? = nil) async throws -> [VideoEpisode] {
        try await callDict(
            "jellyfin.episodes",
            ["serverId": serverId, "seriesId": seriesId, "seasonId": seasonId]
        )
    }

    public func jellyfinSimilar(serverId: String, itemId: String, limit: Int = 16) async throws -> [MetaItem] {
        try await callDict("jellyfin.similar", ["serverId": serverId, "itemId": itemId, "limit": limit])
    }

    public func jellyfinSearch(serverId: String, query: String, limit: Int = 28) async throws -> [MetaItem] {
        try await callDict("jellyfin.search", ["serverId": serverId, "query": query, "limit": limit])
    }

    public func jellyfinResolvePlayback(serverId: String, itemId: String) async throws -> JellyfinPlaybackSession {
        try await callDict("jellyfin.resolve_playback", ["serverId": serverId, "itemId": itemId])
    }

    public func jellyfinSkipSegments(serverId: String, itemId: String) async throws -> [SkipSegment] {
        try await callDict("jellyfin.skip_segments", ["serverId": serverId, "itemId": itemId])
    }

    public func jellyfinReportStart(
        serverId: String,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
        positionMs: UInt64
    ) async throws {
        let _: GenericAckResult = try await callDict(
            "jellyfin.report_start",
            [
                "serverId": serverId,
                "itemId": itemId,
                "mediaSourceId": mediaSourceId,
                "playSessionId": playSessionId,
                "positionMs": positionMs
            ]
        )
    }

    public func jellyfinReportProgress(
        serverId: String,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
        positionMs: UInt64,
        isPaused: Bool
    ) async throws {
        let _: GenericAckResult = try await callDict(
            "jellyfin.report_progress",
            [
                "serverId": serverId,
                "itemId": itemId,
                "mediaSourceId": mediaSourceId,
                "playSessionId": playSessionId,
                "positionMs": positionMs,
                "isPaused": isPaused
            ]
        )
    }

    public func jellyfinReportStopped(
        serverId: String,
        itemId: String,
        mediaSourceId: String,
        playSessionId: String,
        positionMs: UInt64
    ) async throws {
        let _: GenericAckResult = try await callDict(
            "jellyfin.report_stopped",
            [
                "serverId": serverId,
                "itemId": itemId,
                "mediaSourceId": mediaSourceId,
                "playSessionId": playSessionId,
                "positionMs": positionMs
            ]
        )
    }

    // MARK: - YouTube

    public func youtubeListAccounts() async throws -> YouTubeAccountsResponse {
        try await call("youtube.list_accounts")
    }

    public func youtubeSetActiveAccount(accountId: String) async throws {
        struct Res: Decodable, Sendable { let activeAccountId: String }
        let _: Res = try await callDict("youtube.set_active_account", ["accountId": accountId])
    }

    public func youtubeRemoveAccount(accountId: String) async throws {
        let _: GenericAckResult = try await callDict("youtube.remove_account", ["accountId": accountId])
    }

    public func youtubeStartDeviceAuth() async throws -> DeviceCodeSession {
        try await call("youtube.start_device_auth")
    }

    public func youtubePollDeviceAuth(_ session: DeviceCodeSession) async throws -> [YouTubeAccountProfile]? {
        try await callEncodable("youtube.poll_device_auth", params: session)
    }

    public func youtubeHome(continuation: String? = nil) async throws -> CreatorFeedPage {
        try await callDict("youtube.home", ["continuation": continuation])
    }

    public func youtubeSubscriptions(continuation: String? = nil) async throws -> CreatorFeedPage {
        try await callDict("youtube.subscriptions", ["continuation": continuation])
    }

    public func youtubeHistory(continuation: String? = nil) async throws -> CreatorFeedPage {
        try await callDict("youtube.history", ["continuation": continuation])
    }

    public func youtubePlaylist(playlistId: String, continuation: String? = nil) async throws -> CreatorFeedPage {
        try await callDict("youtube.playlist", ["playlistId": playlistId, "continuation": continuation])
    }

    public func youtubeSearch(query: String, continuation: String? = nil) async throws -> CreatorFeedPage {
        try await callDict("youtube.search", ["query": query, "continuation": continuation])
    }

    public func youtubeSuggestions(query: String) async throws -> [String] {
        try await callDict("youtube.suggestions", ["query": query])
    }

    public func youtubeChannel(channelId: String) async throws -> CreatorChannel {
        try await callDict("youtube.channel", ["channelId": channelId])
    }

    public func youtubeSetSubscription(channelId: String, subscribe: Bool) async throws -> Bool {
        let res: GenericAckResult = try await callDict(
            "youtube.set_subscription",
            ["channelId": channelId, "subscribe": subscribe]
        )
        return res.updated ?? false
    }

    public func youtubeRelated(videoId: String) async throws -> [CreatorVideoItem] {
        try await callDict("youtube.related", ["videoId": videoId])
    }

    public func youtubeResolveStream(videoId: String) async throws -> ResolvedMediaStream {
        try await callDict("youtube.resolve_stream", ["videoId": videoId])
    }

    public func youtubeDislikeVotes(videoId: String) async throws -> ReturnYouTubeDislikeVotes {
        try await callDict("youtube.dislike_votes", ["videoId": videoId])
    }

    public func youtubeDeArrowTitle(videoId: String) async throws -> String? {
        let res: DeArrowTitleResponse = try await callDict("youtube.dearrow_title", ["videoId": videoId])
        return res.title
    }

    public func youtubeReportWatchTime(
        playbackTrackingUrl: String,
        watchtimeTrackingUrl: String,
        positionSec: Double,
        durationSec: Double
    ) async throws {
        let _: GenericAckResult = try await callDict(
            "youtube.report_watch_time",
            [
                "playbackTrackingUrl": playbackTrackingUrl,
                "watchtimeTrackingUrl": watchtimeTrackingUrl,
                "positionSec": positionSec,
                "durationSec": durationSec
            ]
        )
    }

    // MARK: - Twitch, Odysee, Rumble

    public func twitchProfile() async throws -> TwitchAuthProfile? {
        try await call("twitch.profile")
    }

    public func twitchLogout() async throws {
        let _: GenericAckResult = try await call("twitch.logout")
    }

    public func twitchStartDeviceAuth() async throws -> DeviceCodeSession {
        try await call("twitch.start_device_auth")
    }

    public func twitchPollDeviceAuth(_ session: DeviceCodeSession) async throws -> TwitchAuthProfile? {
        try await callEncodable("twitch.poll_device_auth", params: session)
    }

    public func twitchTopLive(limit: Int = 30) async throws -> [CreatorVideoItem] {
        try await callDict("twitch.top_live", ["limit": limit])
    }

    public func twitchTopCategories(limit: Int = 30) async throws -> [CreatorCategory] {
        try await callDict("twitch.top_categories", ["limit": limit])
    }

    public func twitchCategoryStreams(gameName: String, limit: Int = 30) async throws -> [CreatorVideoItem] {
        try await callDict("twitch.category_streams", ["gameName": gameName, "limit": limit])
    }

    public func twitchFollowedLive() async throws -> [CreatorVideoItem] {
        try await call("twitch.followed_live")
    }

    public func twitchChannel(login: String) async throws -> CreatorChannel {
        try await callDict("twitch.channel", ["login": login])
    }

    public func twitchSearch(query: String) async throws -> [CreatorVideoItem] {
        try await callDict("twitch.search", ["query": query])
    }

    public func twitchResolveStream(target: String) async throws -> ResolvedMediaStream {
        try await callDict("twitch.resolve_stream", ["target": target])
    }

    public func odyseeFeatured(page: Int = 1) async throws -> [CreatorVideoItem] {
        try await callDict("odysee.featured", ["page": page])
    }

    public func odyseeLive() async throws -> [CreatorVideoItem] {
        try await call("odysee.live")
    }

    public func odyseeSearch(query: String) async throws -> [CreatorVideoItem] {
        try await callDict("odysee.search", ["query": query])
    }

    public func odyseeResolveStream(claim: String) async throws -> ResolvedMediaStream {
        try await callDict("odysee.resolve_stream", ["claim": claim])
    }

    public func rumbleFeatured() async throws -> [CreatorVideoItem] {
        try await call("rumble.featured")
    }

    public func rumbleLive() async throws -> [CreatorVideoItem] {
        try await call("rumble.live")
    }

    public func rumbleSearch(query: String) async throws -> [CreatorVideoItem] {
        try await callDict("rumble.search", ["query": query])
    }

    public func rumbleResolveStream(target: String) async throws -> ResolvedMediaStream {
        try await callDict("rumble.resolve_stream", ["target": target])
    }

    // MARK: - Swarm Search & P2P Loopback Streaming

    public func swarmSearch(
        query: String,
        imdbId: String? = nil,
        filter: SwarmFilter? = nil
    ) async throws -> [StreamCandidate] {
        try await callDict(
            "swarm.search",
            [
                "query": query,
                "imdbId": imdbId,
                "filter": filter?.rawValue
            ]
        )
    }

    public func swarmFeatured(feedKey: String) async throws -> [StreamCandidate] {
        try await callDict("swarm.featured", ["feedKey": feedKey])
    }

    public func torrentStartStream(magnetOrUrl: String, fileIdx: Int? = nil) async throws -> TorrentStreamSession {
        try await callDict(
            "torrent.start_stream",
            ["magnetOrUrl": magnetOrUrl, "fileIdx": fileIdx]
        )
    }

    public func torrentStats(sessionId: String) async throws -> SwarmStatsSnapshot? {
        try await callDict("torrent.stats", ["sessionId": sessionId])
    }

    public func torrentStopStream(sessionId: String) async throws {
        let _: GenericAckResult = try await callDict("torrent.stop_stream", ["sessionId": sessionId])
    }

    public func torrentStopAll() async throws {
        let _: GenericAckResult = try await call("torrent.stop_all")
    }

    // MARK: - Smart Binge & Movie Credits Recommendations

    public func bingeRankStreams(
        context: StreamSelectionContext,
        candidates: [StreamCandidate]
    ) async throws -> [RankedStreamCandidate] {
        struct Payload: Encodable, Sendable {
            let context: StreamSelectionContext
            let candidates: [StreamCandidate]
        }
        return try await callEncodable(
            "binge.rank_streams",
            params: Payload(context: context, candidates: candidates)
        )
    }

    public func bingeNextEpisode(
        episodes: [VideoEpisode],
        currentVideoId: String
    ) async throws -> VideoEpisode? {
        struct Payload: Encodable, Sendable {
            let episodes: [VideoEpisode]
            let currentVideoId: String
        }
        return try await callEncodable(
            "binge.next_episode",
            params: Payload(episodes: episodes, currentVideoId: currentVideoId)
        )
    }

    public func bingeRecommendations(seed: MetaItem, limit: Int = 14) async throws -> [MetaItem] {
        struct Payload: Encodable, Sendable {
            let seed: MetaItem
            let limit: Int
        }
        return try await callEncodable(
            "binge.recommendations",
            params: Payload(seed: seed, limit: limit)
        )
    }

    // MARK: - Kodi Addon Bridge

    public func kodiListAddons() async throws -> [KodiAddonInfo] {
        try await call("kodi.list_addons")
    }

    public func kodiExecute(
        addonId: String,
        pluginUrl: String? = nil,
        timeoutSec: Int = 20
    ) async throws -> KodiBridgeResponse {
        try await callDict(
            "kodi.execute",
            [
                "addonId": addonId,
                "pluginUrl": pluginUrl,
                "timeoutSec": timeoutSec
            ]
        )
    }

    public func kodiParseStreamUrl(_ url: String) async throws -> KodiParsedStream {
        try await callDict("kodi.parse_stream_url", ["url": url])
    }
}
