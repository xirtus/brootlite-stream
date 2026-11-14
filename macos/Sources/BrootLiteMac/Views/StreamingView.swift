import SwiftUI

public struct StreamingView: View {
    @Bindable public var vm: AppViewModel

    private let wideColumns = [
        GridItem(.adaptive(minimum: 248, maximum: 292), spacing: 18)
    ]
    private let categoryColumns = [
        GridItem(.adaptive(minimum: 152, maximum: 184), spacing: 16)
    ]

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Top Platform & Sub-Tab Chrome
            GlassPanel(cornerRadius: 14, padding: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        ForEach(StreamingPlatformTab.allCases) { plat in
                            XirtusChip(
                                title: plat.rawValue,
                                icon: "play.rectangle.fill",
                                isSelected: vm.streamingPlatform == plat
                            ) {
                                vm.streamingPlatform = plat
                                Task { await vm.loadCurrentStreamingTab() }
                            }
                        }

                        Spacer()

                        if vm.streamingPlatform == .youtube {
                            youtubeAccountControls
                        } else if vm.streamingPlatform == .twitch {
                            twitchAccountControls
                        }
                    }

                    platformSubHeader
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    // Device OAuth QR Card (YouTube or Twitch)
                    if vm.streamingPlatform == .youtube, let sess = vm.ytDeviceSession {
                        deviceAuthQRCard(
                            title: "YouTube TV Device OAuth",
                            sess: sess,
                            onCancel: {
                                vm.ytDevicePollTask?.cancel()
                                vm.ytDeviceSession = nil
                            }
                        )
                    } else if vm.streamingPlatform == .twitch, let sess = vm.twitchDeviceSession {
                        deviceAuthQRCard(
                            title: "Twitch Activate Device OAuth",
                            sess: sess,
                            onCancel: {
                                vm.twitchDevicePollTask?.cancel()
                                vm.twitchDeviceSession = nil
                            }
                        )
                    }

                    // Active Channel Drill-Down View
                    if vm.streamingPlatform == .youtube, let ch = vm.ytActiveChannel {
                        creatorChannelDetail(ch, onClose: { vm.ytActiveChannel = nil })
                    } else if vm.streamingPlatform == .twitch, let ch = vm.twitchActiveChannel {
                        creatorChannelDetail(ch, onClose: { vm.twitchActiveChannel = nil })
                    } else {
                        mainPlatformGrid
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
            }
        }
        .task {
            if vm.ytFeedItems.isEmpty && vm.streamingPlatform == .youtube {
                await vm.loadYouTubeFeed(reset: true)
            }
        }
    }

    // MARK: - Account Controls & Sub-Headers

    private var youtubeAccountControls: some View {
        HStack(spacing: 10) {
            Toggle("SponsorBlock", isOn: $vm.sponsorBlockEnabled)
                .toggleStyle(.checkbox)
                .font(XirtusTheme.font(11.5, weight: .medium))
                .foregroundStyle(XirtusTheme.textSecondary)

            Toggle("DeArrow", isOn: $vm.deArrowEnabled)
                .toggleStyle(.checkbox)
                .font(XirtusTheme.font(11.5, weight: .medium))
                .foregroundStyle(XirtusTheme.textSecondary)

            if !vm.ytAccounts.isEmpty {
                Menu {
                    ForEach(vm.ytAccounts) { acc in
                        Button {
                            Task { await vm.switchYouTubeBrandAccount(acc.accountId) }
                        } label: {
                            HStack {
                                if acc.accountId == vm.ytActiveAccountId {
                                    Image(systemName: "checkmark")
                                }
                                Text("\(acc.accountName) \(acc.channelHandle)")
                            }
                        }
                    }
                    Divider()
                    Button("+ Link Another Account / Brand Channel...") {
                        Task { await vm.startYouTubeDeviceOAuth() }
                    }
                    if !vm.ytActiveAccountId.isEmpty {
                        Button("Sign Out Current Account", role: .destructive) {
                            Task { await vm.removeYouTubeAccount(vm.ytActiveAccountId) }
                        }
                    }
                } label: {
                    let current = vm.ytAccounts.first(where: { $0.accountId == vm.ytActiveAccountId }) ?? vm.ytAccounts.first
                    XirtusChip(
                        title: current?.accountName ?? "YouTube Account",
                        icon: "person.crop.circle.fill",
                        action: {}
                    )
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
            } else {
                XirtusChip(title: "Sign In QR", icon: "qrcode") {
                    Task { await vm.startYouTubeDeviceOAuth() }
                }
            }
        }
    }

    private var twitchAccountControls: some View {
        HStack(spacing: 8) {
            if let prof = vm.twitchProfile {
                XirtusChip(title: prof.displayName, icon: "person.fill", badge: "Sign Out") {
                    Task { await vm.logoutTwitch() }
                }
            } else {
                XirtusChip(title: "Twitch QR Login", icon: "qrcode") {
                    Task { await vm.startTwitchDeviceOAuth() }
                }
            }
        }
    }

    @ViewBuilder
    private var platformSubHeader: some View {
        switch vm.streamingPlatform {
        case .youtube:
            HStack(spacing: 8) {
                ForEach(YouTubeSectionTab.allCases) { tab in
                    XirtusChip(title: tab.rawValue, isSelected: vm.ytTab == tab) {
                        vm.ytTab = tab
                        vm.ytActiveChannel = nil
                        if tab != .search {
                            Task { await vm.loadYouTubeFeed(reset: true) }
                        }
                    }
                }

                if vm.ytTab == .playlists {
                    Menu {
                        Button("Watch Later (WL)") {
                            vm.ytPlaylistId = "WL"
                            Task { await vm.loadYouTubeFeed(reset: true) }
                        }
                        Button("Liked Videos (LL)") {
                            vm.ytPlaylistId = "LL"
                            Task { await vm.loadYouTubeFeed(reset: true) }
                        }
                    } label: {
                        XirtusChip(title: "Playlist: \(vm.ytPlaylistId)", icon: "music.note.list", action: {})
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }

                Spacer()

                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    TextField("Search YouTube (1080p60 / 4K Ad-Free DASH)...", text: $vm.ytSearchQuery)
                        .textFieldStyle(.plain)
                        .font(XirtusTheme.font(12.5))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .frame(width: 245)
                        .onChange(of: vm.ytSearchQuery) { _, newVal in
                            vm.updateYouTubeSuggestions(newVal)
                        }
                        .onSubmit {
                            vm.ytTab = .search
                            vm.ytSuggestions = []
                            Task { await vm.loadYouTubeFeed(reset: true) }
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(XirtusTheme.surfaceElevated)
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }

            if !vm.ytSuggestions.isEmpty && !vm.ytSearchQuery.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(vm.ytSuggestions, id: \.self) { sug in
                            XirtusChip(title: sug, icon: "sparkle.magnifyingglass") {
                                vm.ytSearchQuery = sug
                                vm.ytTab = .search
                                vm.ytSuggestions = []
                                Task { await vm.loadYouTubeFeed(reset: true) }
                            }
                        }
                    }
                }
            }

        case .twitch:
            HStack(spacing: 8) {
                ForEach(TwitchSectionTab.allCases) { tab in
                    XirtusChip(title: tab.rawValue, isSelected: vm.twitchTab == tab) {
                        vm.twitchTab = tab
                        vm.twitchSelectedCategory = nil
                        Task { await vm.loadTwitchTab() }
                    }
                }
                if let cat = vm.twitchSelectedCategory {
                    XirtusChip(title: "← \(cat)", isSelected: true) {
                        vm.twitchSelectedCategory = nil
                        Task { await vm.loadTwitchTab() }
                    }
                }
                Spacer()
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    TextField("Search Twitch live & VODs...", text: $vm.twitchSearchQuery)
                        .textFieldStyle(.plain)
                        .font(XirtusTheme.font(12.5))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .frame(width: 210)
                        .onSubmit {
                            vm.twitchTab = .search
                            Task { await vm.loadTwitchTab() }
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(XirtusTheme.surfaceElevated)
                .clipShape(Capsule())
            }

        case .odysee:
            HStack(spacing: 8) {
                ForEach(CreatorSimpleTab.allCases) { tab in
                    XirtusChip(title: tab.rawValue, isSelected: vm.odyseeTab == tab) {
                        vm.odyseeTab = tab
                        Task { await vm.loadOdyseeTab() }
                    }
                }
                Spacer()
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    TextField("Search Odysee...", text: $vm.odyseeSearchQuery)
                        .textFieldStyle(.plain)
                        .font(XirtusTheme.font(12.5))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .frame(width: 200)
                        .onSubmit {
                            vm.odyseeTab = .search
                            Task { await vm.loadOdyseeTab() }
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(XirtusTheme.surfaceElevated)
                .clipShape(Capsule())
            }

        case .rumble:
            HStack(spacing: 8) {
                ForEach(CreatorSimpleTab.allCases) { tab in
                    XirtusChip(title: tab.rawValue, isSelected: vm.rumbleTab == tab) {
                        vm.rumbleTab = tab
                        Task { await vm.loadRumbleTab() }
                    }
                }
                Spacer()
                HStack(spacing: 7) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 12))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    TextField("Search Rumble...", text: $vm.rumbleSearchQuery)
                        .textFieldStyle(.plain)
                        .font(XirtusTheme.font(12.5))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .frame(width: 200)
                        .onSubmit {
                            vm.rumbleTab = .search
                            Task { await vm.loadRumbleTab() }
                        }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(XirtusTheme.surfaceElevated)
                .clipShape(Capsule())
            }
        }
    }

    // MARK: - Grids & QR / Channel Cards

    @ViewBuilder
    private var mainPlatformGrid: some View {
        if vm.isLoadingStreaming {
            HStack(spacing: 10) {
                ProgressView().tint(XirtusTheme.cyanAccent)
                Text("Loading \(vm.streamingPlatform.rawValue) feed...")
                    .font(XirtusTheme.font(13.5))
                    .foregroundStyle(XirtusTheme.textSecondary)
            }
            .padding(.vertical, 24)
        }

        switch vm.streamingPlatform {
        case .youtube:
            creatorVideoGrid(vm.ytFeedItems, allowChannelDrilldown: true)
            if vm.ytContinuation != nil && !vm.ytFeedItems.isEmpty {
                HStack {
                    Spacer()
                    XirtusChip(title: "Load More Videos", icon: "arrow.down.circle") {
                        Task { await vm.loadYouTubeFeed(reset: false) }
                    }
                    Spacer()
                }
                .padding(.vertical, 12)
            }

        case .twitch:
            if vm.twitchTab == .categories && vm.twitchSelectedCategory == nil {
                LazyVGrid(columns: categoryColumns, spacing: 18) {
                    ForEach(vm.twitchCategories) { cat in
                        Button {
                            vm.twitchSelectedCategory = cat.name
                            Task { await vm.loadTwitchTab() }
                        } label: {
                            VStack(alignment: .leading, spacing: 6) {
                                RemoteArtwork(urlString: cat.boxArtUrl, placeholderIcon: "gamecontroller")
                                    .frame(width: 156, height: 208)
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1)
                                    )
                                Text(cat.name)
                                    .font(XirtusTheme.font(12.5, weight: .semibold))
                                    .foregroundStyle(XirtusTheme.textPrimary)
                                    .lineLimit(1)
                                Text(cat.viewersText)
                                    .font(XirtusTheme.mono(10.5))
                                    .foregroundStyle(XirtusTheme.textSecondary)
                                    .lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            } else {
                creatorVideoGrid(vm.twitchItems, allowChannelDrilldown: true)
            }

        case .odysee:
            creatorVideoGrid(vm.odyseeItems, allowChannelDrilldown: false)

        case .rumble:
            creatorVideoGrid(vm.rumbleItems, allowChannelDrilldown: false)
        }
    }

    private func creatorVideoGrid(_ items: [CreatorVideoItem], allowChannelDrilldown: Bool) -> some View {
        LazyVGrid(columns: wideColumns, spacing: 22) {
            ForEach(items) { vid in
                let subParts = [vid.channelName, vid.viewCountText, vid.publishedText]
                    .filter { !$0.isEmpty }
                    .joined(separator: " · ")

                WideMediaCardView(
                    title: vm.displayTitle(for: vid),
                    subtitle: subParts,
                    artworkUrl: vid.thumbnailUrl,
                    badgeText: vid.durationText,
                    isLive: vid.isLive,
                    progressRatio: Double(vid.resumeProgressPct) / 100.0,
                    width: 260,
                    onPrimaryTap: {
                        Task { await vm.playCreatorItem(vid) }
                    },
                    onChannelTap: (allowChannelDrilldown && !vid.channelId.isEmpty) ? {
                        Task { await vm.openCreatorChannel(platform: vid.platform, channelId: vid.channelId) }
                    } : nil
                )
            }
        }
    }

    private func deviceAuthQRCard(
        title: String,
        sess: DeviceCodeSession,
        onCancel: @escaping () -> Void
    ) -> some View {
        let fullQRUrl = sess.verificationUrl.contains("?")
            ? sess.verificationUrl
            : "\(sess.verificationUrl)?user_code=\(sess.userCode)"

        return GlassPanel(cornerRadius: 16, padding: 20, highlightBorder: true) {
            HStack(spacing: 24) {
                QRCodeView(payload: fullQRUrl, size: 152)

                VStack(alignment: .leading, spacing: 8) {
                    Text(title)
                        .font(XirtusTheme.font(17, weight: .bold))
                        .foregroundStyle(XirtusTheme.textPrimary)

                    Text("Scan the QR code on your phone or visit:")
                        .font(XirtusTheme.font(13))
                        .foregroundStyle(XirtusTheme.textSecondary)

                    Text(sess.verificationUrl)
                        .font(XirtusTheme.mono(13.5, weight: .semibold))
                        .foregroundStyle(XirtusTheme.cyanAccent)

                    HStack(spacing: 10) {
                        Text("Activation Code:")
                            .font(XirtusTheme.font(13))
                            .foregroundStyle(XirtusTheme.textSecondary)
                        Text(sess.userCode)
                            .font(XirtusTheme.mono(22, weight: .bold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 5)
                            .background(XirtusTheme.cyanAccent)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                    }
                    .padding(.top, 4)

                    HStack(spacing: 12) {
                        ProgressView().controlSize(.small)
                        Text("Waiting for approval...")
                            .font(XirtusTheme.font(12))
                            .foregroundStyle(XirtusTheme.textMuted)
                        Spacer()
                        Button("Cancel", action: onCancel)
                            .buttonStyle(.bordered)
                    }
                    .padding(.top, 4)
                }
            }
        }
    }

    private func creatorChannelDetail(_ channel: CreatorChannel, onClose: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            GlassPanel(cornerRadius: 16, padding: 18) {
                HStack(spacing: 16) {
                    Button(action: onClose) {
                        Image(systemName: "chevron.left")
                            .font(.system(size: 14, weight: .bold))
                            .padding(10)
                            .background(XirtusTheme.surfaceElevated)
                            .clipShape(Circle())
                    }
                    .buttonStyle(.plain)

                    RemoteArtwork(urlString: channel.avatarUrl, placeholderIcon: "person.crop.circle")
                        .frame(width: 56, height: 56)
                        .clipShape(Circle())

                    VStack(alignment: .leading, spacing: 3) {
                        Text(channel.name)
                            .font(XirtusTheme.font(18, weight: .bold))
                            .foregroundStyle(XirtusTheme.textPrimary)
                        Text("\(channel.handle) · \(channel.subscriberText)")
                            .font(XirtusTheme.mono(12))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }

                    Spacer()

                    if let live = channel.liveStream {
                        XirtusChip(title: "● Watch Live Now", isSelected: true) {
                            Task { await vm.playCreatorItem(live) }
                        }
                    }

                    XirtusChip(
                        title: channel.isSubscribed ? "Subscribed ✓" : "+ Subscribe",
                        isSelected: !channel.isSubscribed
                    ) {
                        Task { await vm.toggleChannelSubscription(channel) }
                    }
                }
            }

            creatorVideoGrid(channel.videos, allowChannelDrilldown: false)
        }
    }
}
