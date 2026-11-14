import AppKit
import Foundation
import SwiftUI

extension AppViewModel {

    // MARK: - 4. Streaming (`YouTube · Twitch · Odysee · Rumble`)

    public func loadCurrentStreamingTab() async {
        switch streamingPlatform {
        case .youtube:
            await loadYouTubeFeed(reset: true)
        case .twitch:
            await loadTwitchTab()
        case .odysee:
            await loadOdyseeTab()
        case .rumble:
            await loadRumbleTab()
        }
    }

    public func loadYouTubeFeed(reset: Bool = true) async {
        isLoadingStreaming = true
        defer { isLoadingStreaming = false }

        if reset {
            ytContinuation = nil
            ytActiveChannel = nil
        }
        do {
            let page: CreatorFeedPage
            switch ytTab {
            case .home:
                page = try await core.youtubeHome(continuation: reset ? nil : ytContinuation)
            case .subscriptions:
                page = try await core.youtubeSubscriptions(continuation: reset ? nil : ytContinuation)
            case .history:
                page = try await core.youtubeHistory(continuation: reset ? nil : ytContinuation)
            case .playlists:
                let pid = ytPlaylistId.trimmingCharacters(in: .whitespacesAndNewlines)
                page = try await core.youtubePlaylist(
                    playlistId: pid.isEmpty ? "WL" : pid,
                    continuation: reset ? nil : ytContinuation
                )
            case .search:
                let q = ytSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !q.isEmpty else {
                    if reset { ytFeedItems = [] }
                    return
                }
                page = try await core.youtubeSearch(query: q, continuation: reset ? nil : ytContinuation)
            }
            if reset {
                ytFeedItems = page.items
            } else {
                let existing = Set(ytFeedItems.map(\.id))
                ytFeedItems.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            }
            ytContinuation = page.continuationToken

            if deArrowEnabled {
                prefetchDeArrowTitles(for: Array(page.items.prefix(12)))
            }
        } catch {
            if reset { ytFeedItems = [] }
            showToast(error.localizedDescription)
        }
    }

    public func updateYouTubeSuggestions(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= 2 else {
            ytSuggestions = []
            return
        }
        Task {
            if let list = try? await core.youtubeSuggestions(query: trimmed) {
                if self.ytSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines) == trimmed {
                    self.ytSuggestions = Array(list.prefix(8))
                }
            }
        }
    }

    private func prefetchDeArrowTitles(for items: [CreatorVideoItem]) {
        let client = core
        for item in items where deArrowTitles[item.videoId] == nil {
            let vid = item.videoId
            Task {
                if let clean = try? await client.youtubeDeArrowTitle(videoId: vid),
                   !clean.isEmpty {
                    self.deArrowTitles[vid] = clean
                }
            }
        }
    }

    public func displayTitle(for item: CreatorVideoItem) -> String {
        if item.platform == "youtube", deArrowEnabled, let clean = deArrowTitles[item.videoId] {
            return clean
        }
        return item.title
    }

    public func openCreatorChannel(platform: String, channelId: String) async {
        guard !channelId.isEmpty else { return }
        isLoadingStreaming = true
        defer { isLoadingStreaming = false }

        if platform == "youtube" {
            ytActiveChannel = try? await core.youtubeChannel(channelId: channelId)
        } else if platform == "twitch" {
            twitchActiveChannel = try? await core.twitchChannel(login: channelId)
        }
    }

    public func toggleChannelSubscription(_ channel: CreatorChannel) async {
        if channel.platform == "youtube" {
            let nextState = !channel.isSubscribed
            if (try? await core.youtubeSetSubscription(channelId: channel.channelId, subscribe: nextState)) != nil {
                var copy = channel
                copy.isSubscribed = nextState
                ytActiveChannel = copy
            }
            _ = try? await core.toggleCreatorFollow(
                CreatorFollowEntry(
                    platform: "youtube",
                    channelId: channel.channelId,
                    name: channel.name,
                    handle: channel.handle,
                    avatarUrl: channel.avatarUrl
                )
            )
        } else {
            let following = (try? await core.toggleCreatorFollow(
                CreatorFollowEntry(
                    platform: channel.platform,
                    channelId: channel.channelId,
                    name: channel.name,
                    handle: channel.handle,
                    avatarUrl: channel.avatarUrl
                )
            )) ?? false
            if channel.platform == "twitch" {
                var copy = channel
                copy.isSubscribed = following
                twitchActiveChannel = copy
            }
        }
        creatorFollows = (try? await core.getCreatorFollows()) ?? []
    }

    public func startYouTubeDeviceOAuth() async {
        ytDevicePollTask?.cancel()
        do {
            let sess = try await core.youtubeStartDeviceAuth()
            ytDeviceSession = sess
            let interval = max(3, sess.intervalSec)
            ytDevicePollTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(interval))
                    guard let self, let activeSess = self.ytDeviceSession else { break }
                    if let accounts = try? await self.core.youtubePollDeviceAuth(activeSess),
                       !accounts.isEmpty {
                        self.ytDeviceSession = nil
                        if let resp = try? await self.core.youtubeListAccounts() {
                            self.ytAccounts = resp.accounts
                            self.ytActiveAccountId = resp.activeAccountId
                        }
                        self.status = try? await self.core.engineStatus()
                        self.showToast("Signed in to YouTube (\(accounts.first?.accountName ?? "Account"))")
                        await self.loadYouTubeFeed(reset: true)
                        break
                    }
                }
            }
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func switchYouTubeBrandAccount(_ accountId: String) async {
        try? await core.youtubeSetActiveAccount(accountId: accountId)
        ytActiveAccountId = accountId
        await loadYouTubeFeed(reset: true)
    }

    public func removeYouTubeAccount(_ accountId: String) async {
        try? await core.youtubeRemoveAccount(accountId: accountId)
        if let resp = try? await core.youtubeListAccounts() {
            ytAccounts = resp.accounts
            ytActiveAccountId = resp.activeAccountId
        }
        await loadYouTubeFeed(reset: true)
    }

    // Twitch, Odysee & Rumble
    public func loadTwitchTab() async {
        isLoadingStreaming = true
        defer { isLoadingStreaming = false }
        twitchActiveChannel = nil

        switch twitchTab {
        case .live:
            twitchSelectedCategory = nil
            twitchItems = (try? await core.twitchTopLive(limit: 36)) ?? []
        case .following:
            twitchSelectedCategory = nil
            twitchItems = (try? await core.twitchFollowedLive()) ?? []
        case .categories:
            if let game = twitchSelectedCategory {
                twitchItems = (try? await core.twitchCategoryStreams(gameName: game, limit: 36)) ?? []
            } else {
                twitchCategories = (try? await core.twitchTopCategories(limit: 36)) ?? []
            }
        case .search:
            let q = twitchSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !q.isEmpty else { return }
            twitchItems = (try? await core.twitchSearch(query: q)) ?? []
        }
    }

    public func startTwitchDeviceOAuth() async {
        twitchDevicePollTask?.cancel()
        do {
            let sess = try await core.twitchStartDeviceAuth()
            twitchDeviceSession = sess
            let interval = max(4, sess.intervalSec)
            twitchDevicePollTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(interval))
                    guard let self, let activeSess = self.twitchDeviceSession else { break }
                    if let prof = try? await self.core.twitchPollDeviceAuth(activeSess) {
                        self.twitchDeviceSession = nil
                        self.twitchProfile = prof
                        self.status = try? await self.core.engineStatus()
                        self.showToast("Signed in to Twitch as \(prof.displayName)")
                        await self.loadTwitchTab()
                        break
                    }
                }
            }
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func logoutTwitch() async {
        try? await core.twitchLogout()
        twitchProfile = nil
        status = try? await core.engineStatus()
    }

    public func loadOdyseeTab() async {
        isLoadingStreaming = true
        defer { isLoadingStreaming = false }
        switch odyseeTab {
        case .featured:
            odyseeItems = (try? await core.odyseeFeatured(page: 1)) ?? []
        case .live:
            odyseeItems = (try? await core.odyseeLive()) ?? []
        case .search:
            let q = odyseeSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !q.isEmpty else { return }
            odyseeItems = (try? await core.odyseeSearch(query: q)) ?? []
        }
    }

    public func loadRumbleTab() async {
        isLoadingStreaming = true
        defer { isLoadingStreaming = false }
        switch rumbleTab {
        case .featured:
            rumbleItems = (try? await core.rumbleFeatured()) ?? []
        case .live:
            rumbleItems = (try? await core.rumbleLive()) ?? []
        case .search:
            let q = rumbleSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !q.isEmpty else { return }
            rumbleItems = (try? await core.rumbleSearch(query: q)) ?? []
        }
    }

    // MARK: - 5. Unified Stremio Catalog + Direct P2P Swarm Search

    public var filteredStremioSearchResults: [MetaItem] {
        switch stremioSearchFilter {
        case .all:
            return stremioSearchResults
        case .movies:
            return stremioSearchResults.filter { $0.mediaType.lowercased() == "movie" }
        case .series:
            return stremioSearchResults.filter { $0.mediaType.lowercased() == "series" }
        case .anime:
            return stremioSearchResults.filter { item in
                item.mediaType.lowercased() == "anime"
                    || item.genres.contains(where: { $0.localizedCaseInsensitiveContains("anime") || $0.localizedCaseInsensitiveContains("animation") })
                    || item.addonSource.localizedCaseInsensitiveContains("kitsu")
                    || item.addonSource.localizedCaseInsensitiveContains("anime")
            }
        }
    }

    public var filteredSwarmResults: [StreamCandidate] {
        switch swarmSourceTab {
        case .all:
            return swarmResults
        case .publicDomain:
            return swarmResults.filter { $0.addonName.localizedCaseInsensitiveContains("archive") || $0.addonName.localizedCaseInsensitiveContains("public") }
        case .customRss:
            return swarmResults.filter { $0.addonName.localizedCaseInsensitiveContains("rss") }
        case .torznab:
            return swarmResults.filter { $0.addonName.localizedCaseInsensitiveContains("torznab") }
        }
    }

    public func executeUnifiedSearch() async {
        let q = unifiedSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else {
            await loadFeaturedSwarmFeed()
            return
        }
        isSearchingStremio = true
        isSearchingSwarm = true

        let client = core
        let filt = swarmFilter
        async let stremioTask = try? client.stremioSearch(query: q)
        async let swarmTask = try? client.swarmSearch(query: q, filter: filt)

        stremioSearchResults = (await stremioTask) ?? []
        isSearchingStremio = false

        swarmResults = (await swarmTask) ?? []
        isSearchingSwarm = false
    }

    public func loadFeaturedSwarmFeed(_ feedKey: String? = nil) async {
        if let feedKey {
            swarmFeaturedKey = feedKey
        }
        isSearchingSwarm = true
        defer { isSearchingSwarm = false }
        swarmResults = (try? await core.swarmFeatured(feedKey: swarmFeaturedKey)) ?? []
    }

    public func updateSwarmFilter(_ next: SwarmFilter) {
        swarmFilter = next
        Task {
            try? await core.setSwarmFilter(next)
            if !unifiedSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                await executeUnifiedSearch()
            }
        }
    }

    // MARK: - 6. Library & Kodi Addon Bridge

    public var filteredFavorites: [FavoriteEntry] {
        switch libraryFilter {
        case .all:
            return favorites
        case .stremio:
            return favorites.filter {
                !$0.itemId.hasPrefix("jf:") && !$0.itemId.hasPrefix("yt:") && !$0.itemId.hasPrefix("tw:")
                    && !$0.itemId.hasPrefix("od:") && !$0.itemId.hasPrefix("rm:") && !$0.itemId.hasPrefix("kodi:")
                    && !$0.itemId.hasPrefix("btih:")
            }
        case .jellyfin:
            return favorites.filter { $0.itemId.hasPrefix("jf:") }
        case .streaming:
            return favorites.filter {
                $0.itemId.hasPrefix("yt:") || $0.itemId.hasPrefix("tw:")
                    || $0.itemId.hasPrefix("od:") || $0.itemId.hasPrefix("rm:")
            }
        case .kodi:
            return favorites.filter { $0.itemId.hasPrefix("kodi:") }
        case .torrents:
            return favorites.filter { $0.itemId.hasPrefix("btih:") }
        }
    }

    public func isFavorite(itemId: String) -> Bool {
        favorites.contains(where: { $0.itemId == itemId })
    }

    public func toggleFavoriteMeta(_ meta: MetaItem) async {
        let entry = FavoriteEntry(
            itemId: meta.id,
            mediaType: meta.mediaType,
            title: meta.name,
            poster: meta.poster,
            imdbRating: meta.imdbRating,
            releaseInfo: meta.releaseInfo
        )
        _ = try? await core.toggleFavorite(entry)
        favorites = (try? await core.getFavorites()) ?? []
    }

    public func scanKodiAddons() async {
        isLoadingKodi = true
        kodiError = nil
        defer { isLoadingKodi = false }
        kodiAddons = (try? await core.kodiListAddons()) ?? []
    }

    public func openKodiAddon(_ addon: KodiAddonInfo) async {
        selectedKodiAddon = addon
        kodiBreadcrumbs = [KodiBreadcrumb(title: addon.name, pluginUrl: nil)]
        await executeKodiDirectory(addonId: addon.id, pluginUrl: nil)
    }

    public func navigateKodiItem(_ item: KodiDirectoryItem) async {
        guard let addon = selectedKodiAddon else { return }
        if item.isFolder {
            kodiBreadcrumbs.append(KodiBreadcrumb(title: item.label, pluginUrl: item.url))
            await executeKodiDirectory(addonId: addon.id, pluginUrl: item.url)
        } else {
            // Execute disposable Python 3 bridge to resolve playable URL (Python exits before playback!)
            isLoadingKodi = true
            let resolvedRaw: String
            if item.url.hasPrefix("plugin://") {
                let resp = try? await core.kodiExecute(addonId: addon.id, pluginUrl: item.url, timeoutSec: 25)
                resolvedRaw = (resp?.resolvedUrl.isEmpty == false) ? resp!.resolvedUrl : item.url
            } else {
                resolvedRaw = item.url
            }
            isLoadingKodi = false

            guard !resolvedRaw.isEmpty, !resolvedRaw.hasPrefix("plugin://") else {
                kodiError = "Addon item did not return a playable media stream URL."
                return
            }
            let parsed = (try? await core.kodiParseStreamUrl(resolvedRaw))
                ?? KodiParsedStream(url: resolvedRaw, headers: [:])

            await startPlaybackSession(
                ActivePlaybackSession(
                    title: item.label,
                    subtitle: "Kodi · \(addon.name)",
                    posterUrl: item.thumb,
                    playUrl: parsed.url,
                    headers: parsed.headers,
                    origin: .kodi(label: item.label, url: parsed.url),
                    torrentSessionId: nil,
                    skipSegments: [],
                    externalSubtitles: [],
                    startedAtSec: 0
                )
            )
        }
    }

    public func jumpToKodiBreadcrumb(_ crumb: KodiBreadcrumb) async {
        guard let addon = selectedKodiAddon,
              let idx = kodiBreadcrumbs.firstIndex(of: crumb) else { return }
        kodiBreadcrumbs = Array(kodiBreadcrumbs.prefix(idx + 1))
        await executeKodiDirectory(addonId: addon.id, pluginUrl: crumb.pluginUrl)
    }

    private func executeKodiDirectory(addonId: String, pluginUrl: String?) async {
        isLoadingKodi = true
        kodiError = nil
        defer { isLoadingKodi = false }
        do {
            let resp = try await core.kodiExecute(addonId: addonId, pluginUrl: pluginUrl, timeoutSec: 22)
            if !resp.ok && !resp.error.isEmpty {
                kodiError = resp.error
            }
            kodiItems = resp.items
        } catch {
            kodiError = error.localizedDescription
            kodiItems = []
        }
    }

    // MARK: - 7. Passport (`Leeloo Multipass v2` Optical `#pk` ECDH + Stremio + Config)

    public func startMultipassOpticalPairing() async {
        multipassPollTask?.cancel()
        let endpoint = multipassInputEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !endpoint.isEmpty else {
            showToast("Enter your Leeloo Multipass endpoint URL.")
            return
        }
        do {
            let sess = try await core.multipassStartPairing(
                endpointUrl: endpoint,
                deviceName: multipassDeviceName
            )
            multipassPairingSession = sess
            let interval = max(2, sess.intervalSec)

            multipassPollTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(interval))
                    guard let self, let activeSess = self.multipassPairingSession else { break }
                    if let outcome = try? await self.core.multipassPollPairing(activeSess) {
                        switch outcome {
                        case .pending:
                            continue
                        case let .approved(_, deviceName, _, e2eeUnlocked):
                            self.multipassPairingSession = nil
                            self.keychainHasUnlockedMarker = true
                            self.showToast(
                                e2eeUnlocked
                                    ? "Multipass Paired & E2EE Pouch Unlocked (\(deviceName))!"
                                    : "Multipass Paired (\(deviceName)) — syncing vault..."
                            )
                            await self.syncMultipassVaultNow()
                            return
                        case .denied:
                            self.multipassPairingSession = nil
                            self.showToast("Multipass pairing request was denied.")
                            return
                        case .expired:
                            self.multipassPairingSession = nil
                            self.showToast("Multipass QR code expired — generate a new one.")
                            return
                        }
                    }
                }
            }
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func syncMultipassVaultNow() async {
        isSyncingVault = true
        defer { isSyncingVault = false }
        do {
            let pass = multipassPassphrase.trimmingCharacters(in: .whitespacesAndNewlines)
            let pullReport = try await core.multipassSyncPull(passphrase: pass.isEmpty ? nil : pass)
            multipassLastReport = pullReport
            if !pullReport.vaultLockedNeedsPassphrase {
                _ = try? await core.multipassSyncPush(passphrase: pass.isEmpty ? nil : pass)
                multipassPassphrase = ""
                showToast("Vault synced (rev #\(pullReport.revision)) · +\(pullReport.addonsImported) addons")
            } else {
                showToast("Vault requires passphrase or #pk optical QR unlock.")
            }
            await reloadCoreStateAndBoard()
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func disconnectMultipass() async {
        multipassPollTask?.cancel()
        multipassPairingSession = nil
        try? await core.multipassDisconnect()
        keychainHasUnlockedMarker = false
        status = try? await core.engineStatus()
        showToast("Disconnected from Leeloo Multipass.")
    }

    public func startStremioQrLink() async {
        stremioQrPollTask?.cancel()
        do {
            let sess = try await core.stremioCreateQrSession()
            stremioQrSession = sess
            stremioQrPollTask = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(3))
                    guard let self, let active = self.stremioQrSession else { break }
                    if let res = try? await self.core.stremioPollQrSession(code: active.code),
                       res.approved {
                        self.stremioQrSession = nil
                        let count = res.summary?.syncedAddonsCount ?? 0
                        self.showToast("Stremio Linked (\(count) addons synced)!")
                        await self.reloadCoreStateAndBoard()
                        break
                    }
                }
            }
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func syncStremioNow() async {
        do {
            let sum = try await core.stremioSyncAccount()
            showToast("Synced Stremio (\(sum.syncedAddonsCount) addons, \(sum.syncedHistoryCount) history)")
            await reloadCoreStateAndBoard()
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func logoutStremio() async {
        try? await core.stremioLogout()
        await reloadCoreStateAndBoard()
    }

    public func installAddonFromManifestURL() async {
        let u = addonInstallUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !u.isEmpty else { return }
        do {
            let installed = try await core.stremioInstallAddon(url: u)
            addonInstallUrl = ""
            showToast("Installed addon: \(installed.name)")
            addons = (try? await core.getAddons()) ?? []
            await loadBoard()
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func removeInstalledAddon(_ addonId: String) async {
        try? await core.removeAddon(id: addonId)
        addons = (try? await core.getAddons()) ?? []
        await loadBoard()
    }

    public func saveTorrentAndTorznabSettings() async {
        if let saved = try? await core.setTorrentConfig(torrentDraftConfig) {
            torrentDraftConfig = saved
        }
        if !torznabUrl.isEmpty || !torznabApiKey.isEmpty {
            try? await core.setTorznab(url: torznabUrl, apiKey: torznabApiKey)
            torznabApiKey = ""
            if let tz = try? await core.getTorznab() {
                torznabHasSavedKey = tz.hasApiKey
            }
        }
        showToast("Saved P2P Swarm & Torznab settings.")
    }

    // MARK: - 8. Detail Sheet & Stream Resolution

    public var detailSeasons: [Int] {
        guard let item = detailItem else { return [] }
        let set = Set(item.videos.map(\.season))
        return set.sorted { a, b in
            if a == 0 { return false }
            if b == 0 { return true }
            return a < b
        }
    }

    public var detailEpisodesForSeason: [VideoEpisode] {
        guard let item = detailItem else { return [] }
        let eps = item.videos.filter { $0.season == detailSelectedSeason }
        return eps.sorted { $0.episode < $1.episode }
    }

    public var filteredDetailStreams: [StreamCandidate] {
        switch detailStreamFilter {
        case .all:
            return detailStreams
        case .direct:
            return detailStreams.filter(\.isDirectHTTP)
        case .p2p:
            return detailStreams.filter(\.isP2P)
        }
    }

    public func openDetail(for item: MetaItem, preselectVideoId: String? = nil) {
        detailItem = item
        detailStreams = []
        detailSelectedEpisode = nil
        isLoadingDetailMeta = true

        Task {
            defer { isLoadingDetailMeta = false }
            let fullMeta: MetaItem
            if let srvId = item.jellyfinServerId {
                var detail = (try? await core.jellyfinItemDetail(serverId: srvId, itemId: item.id)) ?? item
                if detail.isSeriesLike && detail.videos.isEmpty {
                    detail.videos = (try? await core.jellyfinEpisodes(serverId: srvId, seriesId: item.id)) ?? []
                }
                fullMeta = detail
            } else {
                fullMeta = (try? await core.stremioFetchMeta(type: item.mediaType, itemId: item.id)) ?? item
            }
            guard self.detailItem?.id == item.id else { return }
            self.detailItem = fullMeta

            if !fullMeta.videos.isEmpty {
                if let preId = preselectVideoId,
                   !preId.isEmpty,
                   let matchedEp = fullMeta.videos.first(where: { $0.id == preId }) {
                    self.detailSelectedSeason = matchedEp.season
                    self.detailSelectedEpisode = matchedEp
                } else {
                    let seasons = self.detailSeasons
                    self.detailSelectedSeason = seasons.first ?? 1
                    self.detailSelectedEpisode = self.detailEpisodesForSeason.first ?? fullMeta.videos.first
                }
                if let ep = self.detailSelectedEpisode {
                    await loadStreamsForDetail(meta: fullMeta, episode: ep)
                }
            } else {
                await loadStreamsForDetail(meta: fullMeta, episode: nil)
            }
        }
    }

    public func selectDetailEpisode(_ ep: VideoEpisode) {
        detailSelectedEpisode = ep
        guard let meta = detailItem else { return }
        Task { await loadStreamsForDetail(meta: meta, episode: ep) }
    }

    public func loadStreamsForDetail(meta: MetaItem, episode: VideoEpisode?) async {
        isLoadingDetailStreams = true
        detailStreams = []
        defer { isLoadingDetailStreams = false }

        if meta.isJellyfin {
            // Jellyfin resolves DirectPlay / HLS session directly
            let playTargetId = episode?.id ?? meta.id
            if let srvId = meta.jellyfinServerId,
               let sess = try? await core.jellyfinResolvePlayback(serverId: srvId, itemId: playTargetId) {
                detailStreams = [
                    StreamCandidate(
                        title: "\(sess.playMethod) · Jellyfin Media Stream",
                        subtitle: episode?.title ?? meta.name,
                        addonName: "Jellyfin",
                        directUrl: sess.playUrl,
                        infoHash: nil,
                        fileIdx: nil,
                        sources: [],
                        requestHeaders: sess.requestHeaders,
                        externalUrl: nil,
                        bingeGroup: "jellyfin-\(srvId)",
                        filename: "",
                        videoSizeBytes: 0,
                        seeders: 0,
                        resolutionScore: 1080
                    )
                ]
            }
            return
        }

        let videoId = episode?.id ?? meta.id
        let swarmQuery: String = {
            if let ep = episode, ep.season > 0 {
                return "\(meta.name) \(ep.seasonEpisodeCode)"
            }
            return "\(meta.name) \(meta.releaseInfo.prefix(4))"
        }()
        let imdbPrefix = meta.id.hasPrefix("tt") ? String(meta.id.split(separator: ":").first ?? "") : nil

        let client = core
        let filt = swarmFilter
        async let addonStreamsTask = try? client.stremioFetchStreams(type: meta.mediaType, videoId: videoId)
        async let swarmStreamsTask = try? client.swarmSearch(query: swarmQuery, imdbId: imdbPrefix, filter: filt)

        var combined = (await addonStreamsTask) ?? []
        let swarmExtra = (await swarmStreamsTask) ?? []
        let seenHashes = Set(combined.compactMap { $0.infoHash?.lowercased() })
        for sw in swarmExtra {
            if let h = sw.infoHash?.lowercased(), seenHashes.contains(h) {
                continue
            }
            combined.append(sw)
        }

        // Rank with saved stream context if user previously watched this series!
        if let prevCtx = try? await core.getLastStreamContext(itemId: meta.id) {
            if let ranked = try? await core.bingeRankStreams(context: prevCtx, candidates: combined) {
                combined = ranked.map(\.candidate)
            }
        }
        detailStreams = combined
    }

    // MARK: - 9. Cinema Playback Launchers (Stremio, P2P Swarm, Jellyfin, YouTube, Creators)

    public func playMetaWithCandidate(
        meta: MetaItem,
        episode: VideoEpisode?,
        candidate: StreamCandidate,
        resumeMsOverride: UInt64? = nil
    ) async {
        if meta.isJellyfin, let srvId = meta.jellyfinServerId {
            await playJellyfinItem(meta: meta, episode: episode, serverId: srvId)
            return
        }

        isResolvingPlayback = true
        defer { isResolvingPlayback = false }

        let videoId = episode?.id ?? meta.id
        let resumeSec: Double = {
            if let overrideMs = resumeMsOverride {
                return Double(overrideMs) / 1000.0
            }
            if let hist = watchHistory.first(where: { $0.itemId == meta.id && ($0.videoId == videoId || $0.videoId.isEmpty) }),
               hist.progressRatio < 0.94 {
                return Double(hist.positionMs) / 1000.0
            }
            return 0
        }()

        let subs = (try? await core.stremioFetchSubtitles(type: meta.mediaType, videoId: videoId)) ?? []
        let ctx = candidate.toSelectionContext()
        try? await core.recordStreamSelection(itemId: meta.id, context: ctx)

        var playUrl = ""
        var activeTorrentSid: String?

        if candidate.isDirectHTTP, let direct = candidate.directUrl {
            playUrl = direct
        } else if let magnet = candidate.magnetURI {
            do {
                let torSession = try await core.torrentStartStream(magnetOrUrl: magnet, fileIdx: candidate.fileIdx)
                playUrl = torSession.playUrl
                activeTorrentSid = torSession.sessionId
            } catch {
                showToast("P2P Swarm error: \(error.localizedDescription)")
                return
            }
        } else if let ext = candidate.externalUrl, let url = URL(string: ext) {
            NSWorkspace.shared.open(url)
            return
        }

        guard !playUrl.isEmpty else {
            showToast("Selected stream has no playable URL or infoHash.")
            return
        }

        let subtitleLine: String = {
            if let ep = episode {
                return "\(ep.seasonEpisodeCode) · \(ep.title) · \(candidate.addonName)"
            }
            return "\(meta.releaseInfo) · \(candidate.addonName)"
        }()

        detailItem = nil
        await startPlaybackSession(
            ActivePlaybackSession(
                title: meta.name,
                subtitle: subtitleLine,
                posterUrl: meta.poster,
                playUrl: playUrl,
                headers: candidate.requestHeaders,
                origin: .stremio(meta: meta, episode: episode, context: ctx),
                torrentSessionId: activeTorrentSid,
                skipSegments: [],
                externalSubtitles: subs,
                startedAtSec: resumeSec
            )
        )
    }

    public func playJellyfinItem(meta: MetaItem, episode: VideoEpisode?, serverId: String) async {
        isResolvingPlayback = true
        defer { isResolvingPlayback = false }

        let targetId = episode?.id ?? meta.id
        do {
            let pb = try await core.jellyfinResolvePlayback(serverId: serverId, itemId: targetId)
            let segs = (try? await core.jellyfinSkipSegments(serverId: serverId, itemId: targetId)) ?? []
            let startSec = Double(pb.resumePositionMs) / 1000.0

            try? await core.jellyfinReportStart(
                serverId: serverId,
                itemId: targetId,
                mediaSourceId: pb.mediaSourceId,
                playSessionId: pb.playSessionId,
                positionMs: pb.resumePositionMs
            )

            detailItem = nil
            let subLine = episode != nil
                ? "\(episode!.seasonEpisodeCode) · \(episode!.title) · \(pb.playMethod)"
                : "Jellyfin · \(pb.playMethod)"

            await startPlaybackSession(
                ActivePlaybackSession(
                    title: meta.name,
                    subtitle: subLine,
                    posterUrl: meta.poster,
                    playUrl: pb.playUrl,
                    headers: pb.requestHeaders,
                    origin: .jellyfin(
                        meta: meta,
                        episode: episode,
                        serverId: serverId,
                        playbackItemId: targetId,
                        mediaSourceId: pb.mediaSourceId,
                        playSessionId: pb.playSessionId,
                        playMethod: pb.playMethod
                    ),
                    torrentSessionId: nil,
                    skipSegments: segs,
                    externalSubtitles: pb.externalSubtitles,
                    startedAtSec: startSec
                )
            )
        } catch {
            showToast(error.localizedDescription)
        }
    }

    public func playSwarmCandidateDirectly(_ candidate: StreamCandidate) async {
        isResolvingPlayback = true
        defer { isResolvingPlayback = false }

        if candidate.isDirectHTTP, let direct = candidate.directUrl {
            await startPlaybackSession(
                ActivePlaybackSession(
                    title: candidate.title,
                    subtitle: candidate.addonName,
                    posterUrl: "",
                    playUrl: direct,
                    headers: candidate.requestHeaders,
                    origin: .swarm(candidate: candidate),
                    torrentSessionId: nil,
                    skipSegments: [],
                    externalSubtitles: [],
                    startedAtSec: 0
                )
            )
            return
        }

        guard let magnet = candidate.magnetURI else {
            showToast("Unable to construct magnet URI for swarm item.")
            return
        }
        do {
            let sess = try await core.torrentStartStream(magnetOrUrl: magnet, fileIdx: candidate.fileIdx)
            await startPlaybackSession(
                ActivePlaybackSession(
                    title: sess.fileName.isEmpty ? candidate.title : sess.fileName,
                    subtitle: "P2P Swarm · \(candidate.addonName)",
                    posterUrl: "",
                    playUrl: sess.playUrl,
                    headers: [:],
                    origin: .swarm(candidate: candidate),
                    torrentSessionId: sess.sessionId,
                    skipSegments: [],
                    externalSubtitles: [],
                    startedAtSec: 0
                )
            )
        } catch {
            showToast("Torrent start failed: \(error.localizedDescription)")
        }
    }

    public func playCreatorItem(_ item: CreatorVideoItem, startPositionSec: Double = 0) async {
        isResolvingPlayback = true
        defer { isResolvingPlayback = false }

        do {
            let resolved: ResolvedMediaStream
            switch item.platform.lowercased() {
            case "youtube":
                resolved = try await core.youtubeResolveStream(videoId: item.videoId)
            case "twitch":
                let target = !item.videoId.isEmpty ? item.videoId : item.channelId
                resolved = try await core.twitchResolveStream(target: target)
            case "odysee":
                let claim = !item.directUrlHint.isEmpty ? item.directUrlHint : item.videoId
                resolved = try await core.odyseeResolveStream(claim: claim)
            case "rumble":
                let target = !item.directUrlHint.isEmpty ? item.directUrlHint : item.videoId
                resolved = try await core.rumbleResolveStream(target: target)
            default:
                resolved = try await core.youtubeResolveStream(videoId: item.videoId)
            }

            let titleToShow = displayTitle(for: item)
            let origin: PlaybackOrigin = item.platform.lowercased() == "youtube"
                ? .youtube(video: item, stream: resolved)
                : .creator(platform: item.platform, video: item, stream: resolved)

            await startPlaybackSession(
                ActivePlaybackSession(
                    title: titleToShow.isEmpty ? resolved.title : titleToShow,
                    subtitle: "\(resolved.author.isEmpty ? item.channelName : resolved.author) · \(resolved.qualityLabel)",
                    posterUrl: item.thumbnailUrl,
                    playUrl: resolved.playUrl,
                    headers: resolved.requestHeaders,
                    origin: origin,
                    torrentSessionId: nil,
                    skipSegments: resolved.skipSegments,
                    externalSubtitles: resolved.subtitles,
                    startedAtSec: startPositionSec
                )
            )

            if item.platform.lowercased() == "youtube" && rydEnabled {
                activeRYDVotes = try? await core.youtubeDislikeVotes(videoId: item.videoId)
            }
        } catch {
            showToast(error.localizedDescription)
        }
    }

    // MARK: - 10. Active Session Lifecycle, SponsorBlock, Smart Binge & Movie Credits Gallery

    public func startPlaybackSession(_ session: ActivePlaybackSession) async {
        // Stop previous P2P torrent stream if switching media
        if let prevSid = activeSession?.torrentSessionId, prevSid != session.torrentSessionId {
            try? await core.torrentStopStream(sessionId: prevSid)
        }
        playerTelemetryTask?.cancel()

        liveSwarmStats = nil
        activeRYDVotes = nil
        sponsorSkipToast = nil
        lastSponsorSkippedFromSec = nil
        suppressedSponsorSegmentIds = []

        nextEpisodeProposal = nil
        didStartPreResolvingNextEp = false
        watchCreditsDismissedBinge = false
        bingeCountdownRemaining = nil

        creditsRecommendations = []
        didFetchCreditsRecommendations = false
        dismissCreditsRecommendations = false

        activeSession = session
        player.loadMedia(
            url: session.playUrl,
            headers: session.headers,
            startPositionSec: session.startedAtSec,
            externalSubtitles: session.externalSubtitles
        )

        startPlayerTelemetryLoop()
    }

    public func closePlayer() {
        playerTelemetryTask?.cancel()
        let closingSession = activeSession
        let finalPosMs = UInt64(max(0, player.positionSec) * 1000)
        let finalDurMs = UInt64(max(0, player.durationSec) * 1000)

        player.stop()
        activeSession = nil
        liveSwarmStats = nil
        bingeCountdownRemaining = nil

        if let closingSession {
            Task {
                await persistProgressAndCleanup(
                    session: closingSession,
                    positionMs: finalPosMs,
                    durationMs: finalDurMs,
                    stopping: true
                )
                await rebuildContinueWatching()
            }
        }
    }

    private func startPlayerTelemetryLoop() {
        playerTelemetryTask = Task { @MainActor [weak self] in
            var tickCount = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, let sess = self.activeSession else { break }
                tickCount += 1

                // 1) Poll live `librqbit` P2P stats every second
                if let torSid = sess.torrentSessionId {
                    self.liveSwarmStats = try? await self.core.torrentStats(sessionId: torSid)
                }

                // 2) SponsorBlock Auto-Skip (YouTube)
                self.evaluateSponsorBlockAutoSkip(session: sess)

                // 3) Smart Series Binge Pre-Resolution (~55s before end) & 15s Credits Countdown (final 35s)
                await self.evaluateSmartBingeAndCredits(session: sess)

                // 4) Persist Watch History & Jellyfin / YouTube 2-way progress every 10s
                if tickCount % 10 == 0 {
                    let posMs = UInt64(max(0, self.player.positionSec) * 1000)
                    let durMs = UInt64(max(0, self.player.durationSec) * 1000)
                    await self.persistProgressAndCleanup(
                        session: sess,
                        positionMs: posMs,
                        durationMs: durMs,
                        stopping: false
                    )
                }
            }
        }
    }

    // Active Jellyfin / Stremio Intro Skip Segment
    public var activeIntroSkipSegment: SkipSegment? {
        guard let sess = activeSession else { return nil }
        let nowMs = UInt64(max(0, player.positionSec) * 1000)
        return sess.skipSegments.first { seg in
            (seg.kind == .intro || seg.kind == .recap || seg.kind == .preview)
                && nowMs >= seg.startMs
                && nowMs + 1000 < seg.endMs
        }
    }

    public func skipActiveIntroSegment() {
        guard let seg = activeIntroSkipSegment else { return }
        player.seekAbsolute(Double(seg.endMs) / 1000.0)
    }

    private func evaluateSponsorBlockAutoSkip(session: ActivePlaybackSession) {
        guard case .youtube = session.origin,
              sponsorBlockEnabled,
              player.isPlaying else {
            return
        }
        let nowMs = UInt64(max(0, player.positionSec) * 1000)
        for seg in session.skipSegments {
            let autoSkippable = (seg.kind == .sponsor || seg.kind == .selfPromo || seg.kind == .interaction)
            guard autoSkippable,
                  !suppressedSponsorSegmentIds.contains(seg.id),
                  nowMs >= seg.startMs,
                  nowMs + 800 < seg.endMs else {
                continue
            }
            lastSponsorSkippedFromSec = player.positionSec
            suppressedSponsorSegmentIds.insert(seg.id)
            player.seekAbsolute(Double(seg.endMs) / 1000.0)

            let toast = "Skipped \(seg.label.isEmpty ? "Sponsor" : seg.label) · Press Z or ⌫ to Undo"
            sponsorSkipToast = toast
            Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(5.5))
                if self?.sponsorSkipToast == toast {
                    self?.sponsorSkipToast = nil
                }
            }
            break
        }
    }

    public func undoLastSponsorSkip() {
        guard let prevSec = lastSponsorSkippedFromSec else { return }
        lastSponsorSkippedFromSec = nil
        sponsorSkipToast = nil
        player.seekAbsolute(prevSec)
        showToast("Rewound before SponsorBlock skip")
    }

    // Smart Binge + Movie Credits Evaluation
    private func evaluateSmartBingeAndCredits(session: ActivePlaybackSession) async {
        let dur = player.durationSec
        let pos = player.positionSec
        let remaining = dur > 0 ? max(0, dur - pos) : Double.infinity
        let nowMs = UInt64(max(0, pos) * 1000)
        let inOutroSegment = session.skipSegments.contains {
            $0.kind == .outro && nowMs >= $0.startMs
        }

        switch session.origin {
        case let .stremio(meta, currentEp, ctx):
            if let currentEp, !meta.videos.isEmpty {
                // Series episode -> Pre-resolve ~55s before end
                if !didStartPreResolvingNextEp && dur > 30 && (remaining <= 55 || inOutroSegment) {
                    didStartPreResolvingNextEp = true
                    await preResolveStremioNextEpisode(meta: meta, currentEpisode: currentEp, context: ctx)
                }
                if nextEpisodeProposal != nil && !watchCreditsDismissedBinge && dur > 30 && (remaining <= 35 || inOutroSegment || player.eofReached) {
                    advanceBingeCountdown()
                } else if nextEpisodeProposal == nil && didStartPreResolvingNextEp {
                    // Season/Series Finale -> fall through to Movie/Finale Credits Recommendations!
                    await maybeFetchCreditsRecommendations(seed: meta, remaining: remaining, dur: dur, inOutro: inOutroSegment)
                }
            } else {
                // Movie -> Pre-fetch recommendations ~180s (3m) before end
                await maybeFetchCreditsRecommendations(seed: meta, remaining: remaining, dur: dur, inOutro: inOutroSegment)
            }

        case let .jellyfin(meta, currentEp, serverId, _, _, _, _):
            if let currentEp, !meta.videos.isEmpty {
                if !didStartPreResolvingNextEp && dur > 30 && (remaining <= 55 || inOutroSegment) {
                    didStartPreResolvingNextEp = true
                    if let nextEp = try? await core.bingeNextEpisode(episodes: meta.videos, currentVideoId: currentEp.id) {
                        self.nextEpisodeProposal = NextEpisodeProposal(
                            meta: meta,
                            nextEpisode: nextEp,
                            bestCandidate: nil,
                            matchedReasons: ["Jellyfin Direct Play"],
                            isJellyfin: true,
                            jellyfinServerId: serverId
                        )
                    }
                }
                if nextEpisodeProposal != nil && !watchCreditsDismissedBinge && dur > 30 && (remaining <= 35 || inOutroSegment || player.eofReached) {
                    advanceBingeCountdown()
                } else if nextEpisodeProposal == nil && didStartPreResolvingNextEp {
                    await maybeFetchCreditsRecommendations(seed: meta, remaining: remaining, dur: dur, inOutro: inOutroSegment)
                }
            } else {
                await maybeFetchCreditsRecommendations(seed: meta, remaining: remaining, dur: dur, inOutro: inOutroSegment)
            }

        default:
            break
        }
    }

    private func preResolveStremioNextEpisode(
        meta: MetaItem,
        currentEpisode: VideoEpisode,
        context: StreamSelectionContext
    ) async {
        guard let nextEp = try? await core.bingeNextEpisode(
            episodes: meta.videos,
            currentVideoId: currentEpisode.id
        ) else {
            return
        }
        var candidates = (try? await core.stremioFetchStreams(type: meta.mediaType, videoId: nextEp.id)) ?? []
        if candidates.isEmpty {
            let q = "\(meta.name) \(nextEp.seasonEpisodeCode)"
            candidates = (try? await core.swarmSearch(query: q, filter: swarmFilter)) ?? []
        }
        guard !candidates.isEmpty else { return }

        if let ranked = try? await core.bingeRankStreams(context: context, candidates: candidates),
           let top = ranked.first {
            self.nextEpisodeProposal = NextEpisodeProposal(
                meta: meta,
                nextEpisode: nextEp,
                bestCandidate: top.candidate,
                matchedReasons: top.matchedReasons,
                isJellyfin: false,
                jellyfinServerId: nil
            )
        } else if let first = candidates.first {
            self.nextEpisodeProposal = NextEpisodeProposal(
                meta: meta,
                nextEpisode: nextEp,
                bestCandidate: first,
                matchedReasons: [],
                isJellyfin: false,
                jellyfinServerId: nil
            )
        }
    }

    private func advanceBingeCountdown() {
        if let current = bingeCountdownRemaining {
            if current <= 1 {
                bingeCountdownRemaining = nil
                streamNextEpisodeNow()
            } else {
                bingeCountdownRemaining = current - 1
            }
        } else {
            bingeCountdownRemaining = 15
        }
    }

    public func streamNextEpisodeNow() {
        guard let proposal = nextEpisodeProposal else { return }
        bingeCountdownRemaining = nil
        nextEpisodeProposal = nil
        Task {
            if proposal.isJellyfin, let srvId = proposal.jellyfinServerId {
                await playJellyfinItem(meta: proposal.meta, episode: proposal.nextEpisode, serverId: srvId)
            } else if let cand = proposal.bestCandidate {
                await playMetaWithCandidate(
                    meta: proposal.meta,
                    episode: proposal.nextEpisode,
                    candidate: cand,
                    resumeMsOverride: 0
                )
            }
        }
    }

    public func dismissBingeWatchCredits() {
        watchCreditsDismissedBinge = true
        bingeCountdownRemaining = nil
    }

    private func maybeFetchCreditsRecommendations(
        seed: MetaItem,
        remaining: Double,
        dur: Double,
        inOutro: Bool
    ) async {
        guard !didFetchCreditsRecommendations,
              dur > 45,
              (remaining <= 180 || inOutro || player.eofReached) else {
            return
        }
        didFetchCreditsRecommendations = true
        if let recs = try? await core.bingeRecommendations(seed: seed, limit: 14) {
            self.creditsRecommendations = recs
        }
    }

    public var shouldShowCreditsRecommendationsOverlay: Bool {
        guard !creditsRecommendations.isEmpty,
              !dismissCreditsRecommendations,
              nextEpisodeProposal == nil || watchCreditsDismissedBinge else {
            return false
        }
        if player.eofReached { return true }
        let dur = player.durationSec
        let rem = dur > 0 ? (dur - player.positionSec) : Double.infinity
        let nowMs = UInt64(max(0, player.positionSec) * 1000)
        let inOutro = activeSession?.skipSegments.contains(where: { $0.kind == .outro && nowMs >= $0.startMs }) ?? false
        return (dur > 45 && rem <= 150) || inOutro
    }

    public func playRecommendedMovieImmediately(_ rec: MetaItem) {
        Task {
            if let srvId = rec.jellyfinServerId {
                await playJellyfinItem(meta: rec, episode: nil, serverId: srvId)
                return
            }
            isResolvingPlayback = true
            let fullMeta = (try? await core.stremioFetchMeta(type: rec.mediaType, itemId: rec.id)) ?? rec
            let firstEp = fullMeta.videos.first
            let vidId = firstEp?.id ?? fullMeta.id

            var streams = (try? await core.stremioFetchStreams(type: fullMeta.mediaType, videoId: vidId)) ?? []
            if streams.isEmpty {
                let imdb = fullMeta.id.hasPrefix("tt") ? fullMeta.id : nil
                streams = (try? await core.swarmSearch(query: fullMeta.name, imdbId: imdb, filter: swarmFilter)) ?? []
            }
            isResolvingPlayback = false

            // Pick best direct mirror first, or highest-seeded 1080p/4K swarm stream
            let best = streams.first(where: \.isDirectHTTP)
                ?? streams.max(by: { a, b in a.seeders < b.seeders })
                ?? streams.first

            if let best {
                await playMetaWithCandidate(
                    meta: fullMeta,
                    episode: firstEp,
                    candidate: best,
                    resumeMsOverride: 0
                )
            } else {
                closePlayer()
                openDetail(for: fullMeta)
            }
        }
    }

    private func persistProgressAndCleanup(
        session: ActivePlaybackSession,
        positionMs: UInt64,
        durationMs: UInt64,
        stopping: Bool
    ) async {
        switch session.origin {
        case let .stremio(meta, ep, ctx):
            guard durationMs > 0 || positionMs > 3000 else { break }
            let entry = WatchHistoryEntry(
                itemId: meta.id,
                mediaType: meta.mediaType,
                title: meta.name,
                poster: meta.poster,
                videoId: ep?.id ?? meta.id,
                episodeTitle: ep.map { "\($0.seasonEpisodeCode) · \($0.title)" } ?? "",
                positionMs: positionMs,
                durationMs: durationMs,
                lastStreamContext: ctx
            )
            try? await core.upsertWatchHistory(entry)
            if stopping {
                try? await core.stremioPushWatchProgress(entry)
            }
            watchHistory = (try? await core.getWatchHistory()) ?? watchHistory

        case let .jellyfin(meta, ep, serverId, pbItemId, mediaSourceId, playSessionId, _):
            if stopping {
                try? await core.jellyfinReportStopped(
                    serverId: serverId,
                    itemId: pbItemId,
                    mediaSourceId: mediaSourceId,
                    playSessionId: playSessionId,
                    positionMs: positionMs
                )
            } else {
                try? await core.jellyfinReportProgress(
                    serverId: serverId,
                    itemId: pbItemId,
                    mediaSourceId: mediaSourceId,
                    playSessionId: playSessionId,
                    positionMs: positionMs,
                    isPaused: !player.isPlaying
                )
            }
            let entry = WatchHistoryEntry(
                itemId: meta.id,
                mediaType: meta.mediaType,
                title: meta.name,
                poster: meta.poster,
                videoId: ep?.id ?? pbItemId,
                episodeTitle: ep.map { "\($0.seasonEpisodeCode) · \($0.title)" } ?? "Jellyfin",
                positionMs: positionMs,
                durationMs: durationMs
            )
            try? await core.upsertWatchHistory(entry)
            watchHistory = (try? await core.getWatchHistory()) ?? watchHistory

        case let .youtube(video, stream):
            let posSec = Double(positionMs) / 1000.0
            let durSec = Double(durationMs > 0 ? durationMs / 1000 : stream.durationSec)
            if !stream.playbackTrackingUrl.isEmpty || !stream.watchtimeTrackingUrl.isEmpty {
                try? await core.youtubeReportWatchTime(
                    playbackTrackingUrl: stream.playbackTrackingUrl,
                    watchtimeTrackingUrl: stream.watchtimeTrackingUrl,
                    positionSec: posSec,
                    durationSec: durSec
                )
            }
            let entry = WatchHistoryEntry(
                itemId: "yt:\(video.videoId)",
                mediaType: "youtube",
                title: video.title,
                poster: video.thumbnailUrl,
                videoId: video.videoId,
                episodeTitle: video.channelName,
                positionMs: positionMs,
                durationMs: UInt64(durSec * 1000)
            )
            try? await core.upsertWatchHistory(entry)
            watchHistory = (try? await core.getWatchHistory()) ?? watchHistory

        default:
            break
        }

        if stopping, let torSid = session.torrentSessionId {
            try? await core.torrentStopStream(sessionId: torSid)
        }
    }
}
