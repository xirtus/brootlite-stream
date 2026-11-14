import SwiftUI

public struct JellyfinScreenView: View {
    @Bindable public var vm: AppViewModel

    private let gridColumns = [
        GridItem(.adaptive(minimum: 148, maximum: 176), spacing: 18)
    ]

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Top Multi-Server Switcher + Sub-Navigation
            GlassPanel(cornerRadius: 14, padding: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 10) {
                        ForEach(Array(vm.jellyfinServers.enumerated()), id: \.element.id) { idx, srv in
                            XirtusChip(
                                title: srv.serverName.isEmpty ? "Server \(idx + 1)" : srv.serverName,
                                icon: "server.rack",
                                isSelected: vm.selectedJellyfinServerId == srv.serverId,
                                badge: srv.username
                            ) {
                                vm.selectJellyfinServer(srv.serverId)
                            }
                        }

                        if vm.activeJellyfinServer != nil {
                            XirtusChip(
                                title: "⚙ Server Settings",
                                isSelected: vm.jellyfinMode == "settings"
                            ) {
                                vm.jellyfinMode = "settings"
                            }
                        }

                        XirtusChip(title: "+ Add Server", icon: "plus") {
                            vm.jfAuthError = nil
                            vm.jfQuickConnectState = nil
                            vm.showAddJellyfinSheet = true
                            Task { await vm.scanJellyfinLAN() }
                        }

                        Spacer()

                        if vm.activeJellyfinServer != nil {
                            HStack(spacing: 7) {
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 12))
                                    .foregroundStyle(XirtusTheme.cyanAccent)
                                TextField("Search selected server...", text: $vm.jellyfinSearchQuery)
                                    .textFieldStyle(.plain)
                                    .font(XirtusTheme.font(12.5))
                                    .foregroundStyle(XirtusTheme.textPrimary)
                                    .frame(width: 185)
                                    .onSubmit {
                                        Task { await vm.executeJellyfinSearch() }
                                    }
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(XirtusTheme.surfaceElevated)
                            .clipShape(Capsule())
                            .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
                        }
                    }

                    // Isolated Per-Server Library Views Bar
                    if vm.activeJellyfinServer != nil {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                XirtusChip(
                                    title: "Overview",
                                    icon: "sparkles.rectangle.stack",
                                    isSelected: vm.jellyfinMode == "overview"
                                ) {
                                    vm.jellyfinMode = "overview"
                                }

                                ForEach(vm.jellyfinViews) { view in
                                    XirtusChip(
                                        title: view.name,
                                        icon: view.collectionType == "tvshows" ? "tv" : "film",
                                        isSelected: vm.jellyfinMode == "view:\(view.id)"
                                    ) {
                                        vm.jellyfinSelectedGenre = "All"
                                        vm.jellyfinSelectedYear = "All"
                                        Task { await vm.loadJellyfinLibraryView(viewId: view.id) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)

            // Main Content Area (Strictly Isolated to `selectedJellyfinServerId`)
            ScrollView {
                if vm.jellyfinServers.isEmpty {
                    emptyJellyfinState
                } else if vm.jellyfinMode == "settings", let srv = vm.activeJellyfinServer {
                    serverSettingsCard(srv)
                } else if vm.jellyfinMode == "search" {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Search Results on \(vm.activeJellyfinServer?.serverName ?? "Server")")
                            .font(XirtusTheme.font(17, weight: .bold))
                            .foregroundStyle(XirtusTheme.textPrimary)

                        LazyVGrid(columns: gridColumns, spacing: 20) {
                            ForEach(vm.jellyfinSearchResults) { item in
                                PosterCardView(item: item, width: 150) {
                                    vm.openDetail(for: item)
                                }
                            }
                        }
                    }
                    .padding(24)
                } else if vm.jellyfinMode.hasPrefix("view:") {
                    let viewId = String(vm.jellyfinMode.dropFirst(5))
                    libraryViewGrid(viewId: viewId)
                } else {
                    serverOverviewContent
                }
            }
        }
        .sheet(isPresented: $vm.showAddJellyfinSheet) {
            addJellyfinServerSheet
        }
        .task {
            if !vm.selectedJellyfinServerId.isEmpty && vm.jellyfinViews.isEmpty {
                await vm.loadActiveJellyfinServerOverview()
            }
        }
    }

    private var emptyJellyfinState: some View {
        GlassPanel(cornerRadius: 18, padding: 32) {
            VStack(spacing: 14) {
                Image(systemName: "server.rack")
                    .font(.system(size: 42))
                    .foregroundStyle(XirtusTheme.indigoAccent)
                Text("Connect Your Jellyfin Media Servers")
                    .font(XirtusTheme.font(20, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                Text("Supports multiple strictly isolated servers, 1-click UDP 7359 LAN Auto-Discovery, 6-digit QuickConnect, DirectPlay + HLS fallback, Intro Skipper, and Leeloo Multipass zero-password E2EE sync.")
                    .font(XirtusTheme.font(13.5))
                    .foregroundStyle(XirtusTheme.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 520)

                Button {
                    vm.showAddJellyfinSheet = true
                    Task { await vm.scanJellyfinLAN() }
                } label: {
                    Text("+ Add Jellyfin Server")
                        .font(XirtusTheme.font(13.5, weight: .bold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 10)
                        .background(XirtusTheme.cyanAccent)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(32)
    }

    private var serverOverviewContent: some View {
        VStack(alignment: .leading, spacing: 26) {
            if vm.isLoadingJellyfin && vm.jellyfinResumeItems.isEmpty && vm.jellyfinLatestByView.isEmpty {
                HStack(spacing: 10) {
                    ProgressView().tint(XirtusTheme.cyanAccent)
                    Text("Connecting to \(vm.activeJellyfinServer?.serverName ?? "Jellyfin")...")
                        .font(XirtusTheme.font(13.5))
                        .foregroundStyle(XirtusTheme.textSecondary)
                }
            }

            if !vm.jellyfinResumeItems.isEmpty {
                jfHorizontalRail(title: "Continue Watching", items: vm.jellyfinResumeItems)
            }
            if !vm.jellyfinNextUpItems.isEmpty {
                jfHorizontalRail(title: "Next Up", items: vm.jellyfinNextUpItems)
            }
            ForEach(vm.jellyfinViews) { view in
                if let latest = vm.jellyfinLatestByView[view.id], !latest.isEmpty {
                    jfHorizontalRail(title: "Latest in \(view.name)", items: latest)
                }
            }
        }
        .padding(24)
    }

    private func jfHorizontalRail(title: String, items: [MetaItem]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(XirtusTheme.font(17.5, weight: .bold))
                .foregroundStyle(XirtusTheme.textPrimary)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 15) {
                    ForEach(items) { item in
                        PosterCardView(item: item, width: 146) {
                            vm.openDetail(for: item)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private func libraryViewGrid(viewId: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(spacing: 12) {
                Menu {
                    Button("All Genres") {
                        vm.jellyfinSelectedGenre = "All"
                        Task { await vm.loadJellyfinLibraryView(viewId: viewId) }
                    }
                    ForEach(vm.jellyfinGenres, id: \.self) { g in
                        Button(g) {
                            vm.jellyfinSelectedGenre = g
                            Task { await vm.loadJellyfinLibraryView(viewId: viewId) }
                        }
                    }
                } label: {
                    XirtusChip(title: "Genre: \(vm.jellyfinSelectedGenre)", icon: "tag", action: {})
                }
                .menuStyle(.borderlessButton)
                .fixedSize()

                Menu {
                    Button("All Years") {
                        vm.jellyfinSelectedYear = "All"
                        Task { await vm.loadJellyfinLibraryView(viewId: viewId) }
                    }
                    ForEach(vm.jellyfinYears, id: \.self) { y in
                        Button(y) {
                            vm.jellyfinSelectedYear = y
                            Task { await vm.loadJellyfinLibraryView(viewId: viewId) }
                        }
                    }
                } label: {
                    XirtusChip(title: "Year: \(vm.jellyfinSelectedYear)", icon: "calendar", action: {})
                }
                .menuStyle(.borderlessButton)
                .fixedSize()

                Menu {
                    ForEach(
                        [
                            ("SortName", "Title A–Z"),
                            ("DateCreated", "Recently Added"),
                            ("CommunityRating", "Community Rating"),
                            ("PremiereDate", "Release Date")
                        ],
                        id: \.0
                    ) { key, label in
                        Button(label) {
                            vm.jellyfinSortBy = key
                            Task { await vm.loadJellyfinLibraryView(viewId: viewId) }
                        }
                    }
                } label: {
                    XirtusChip(title: "Sort: \(vm.jellyfinSortBy)", icon: "arrow.up.arrow.down", action: {})
                }
                .menuStyle(.borderlessButton)
                .fixedSize()

                Spacer()
            }

            LazyVGrid(columns: gridColumns, spacing: 20) {
                ForEach(vm.jellyfinLibraryItems) { item in
                    PosterCardView(item: item, width: 150) {
                        vm.openDetail(for: item)
                    }
                }
            }
        }
        .padding(24)
    }

    private func serverSettingsCard(_ srv: JellyfinServerProfile) -> some View {
        GlassPanel(cornerRadius: 16, padding: 22) {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(srv.serverName) · Server Settings")
                    .font(XirtusTheme.font(18, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                Text("Signed in as \(srv.username) · LAN: \(srv.lanUrl.isEmpty ? "—" : srv.lanUrl) · Remote: \(srv.remoteUrl.isEmpty ? "—" : srv.remoteUrl)")
                    .font(XirtusTheme.mono(12))
                    .foregroundStyle(XirtusTheme.textSecondary)

                HStack(spacing: 12) {
                    Button("Refresh Libraries") {
                        vm.jellyfinMode = "overview"
                        Task { await vm.loadActiveJellyfinServerOverview() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(XirtusTheme.cyanAccent)

                    Button("Remove Server") {
                        Task { await vm.removeJellyfinServer(srv.serverId) }
                    }
                    .buttonStyle(.bordered)
                    .foregroundStyle(XirtusTheme.rose)
                }
                .padding(.top, 6)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(24)
    }

    private var addJellyfinServerSheet: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Add Jellyfin Server")
                    .font(XirtusTheme.font(18, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                Spacer()
                Button("Close") {
                    vm.jfQuickConnectPollTask?.cancel()
                    vm.showAddJellyfinSheet = false
                }
                .buttonStyle(.plain)
                .foregroundStyle(XirtusTheme.textSecondary)
            }

            // 1. UDP 7359 LAN Auto-Discovery
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("UDP 7359 LAN Auto-Discovery")
                        .font(XirtusTheme.font(13, weight: .semibold))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    Spacer()
                    Button(vm.isScanningLAN ? "Scanning..." : "Scan LAN") {
                        Task { await vm.scanJellyfinLAN() }
                    }
                    .disabled(vm.isScanningLAN)
                }
                if vm.lanDiscoveredServers.isEmpty {
                    Text(vm.isScanningLAN ? "Broadcasting UDP 7359 discovery probe..." : "No LAN servers responded yet — enter URL manually or click Scan LAN.")
                        .font(XirtusTheme.font(12))
                        .foregroundStyle(XirtusTheme.textMuted)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 8) {
                            ForEach(vm.lanDiscoveredServers) { disc in
                                XirtusChip(
                                    title: "\(disc.name) (\(disc.address))",
                                    icon: "wifi",
                                    isSelected: vm.jfInputLanUrl == disc.address
                                ) {
                                    vm.jfInputLanUrl = disc.address
                                }
                            }
                        }
                    }
                }
            }

            Divider()

            TextField("Primary / LAN Server URL (e.g. http://192.168.1.50:8096)", text: $vm.jfInputLanUrl)
                .textFieldStyle(.roundedBorder)
            TextField("Optional Tailscale / HTTPS Remote Fallback URL", text: $vm.jfInputRemoteUrl)
                .textFieldStyle(.roundedBorder)

            // 2. 6-Digit QuickConnect
            HStack(spacing: 14) {
                Button("6-Digit QuickConnect") {
                    Task { await vm.startJellyfinQuickConnect() }
                }
                .buttonStyle(.borderedProminent)
                .tint(XirtusTheme.indigoAccent)

                if let qc = vm.jfQuickConnectState {
                    HStack(spacing: 8) {
                        Text("Enter Code in Jellyfin:")
                            .font(XirtusTheme.font(12.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                        Text(qc.code)
                            .font(XirtusTheme.mono(20, weight: .bold))
                            .foregroundStyle(XirtusTheme.cyanAccent)
                    }
                }
            }

            Divider()

            // 3. Username & Password
            Text("Or Sign In with Username & Password")
                .font(XirtusTheme.font(13, weight: .semibold))
                .foregroundStyle(XirtusTheme.textSecondary)
            HStack(spacing: 10) {
                TextField("Username", text: $vm.jfInputUsername)
                    .textFieldStyle(.roundedBorder)
                SecureField("Password", text: $vm.jfInputPassword)
                    .textFieldStyle(.roundedBorder)
                Button("Sign In") {
                    Task { await vm.loginJellyfinWithPassword() }
                }
                .buttonStyle(.borderedProminent)
                .tint(XirtusTheme.cyanAccent)
            }

            if let err = vm.jfAuthError {
                Text(err)
                    .font(XirtusTheme.font(12))
                    .foregroundStyle(XirtusTheme.rose)
            }
        }
        .padding(24)
        .frame(width: 560)
        .background(XirtusTheme.bgObsidian)
    }
}
