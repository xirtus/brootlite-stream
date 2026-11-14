import SwiftUI

public struct SearchView: View {
    @Bindable public var vm: AppViewModel
    @FocusState private var queryFocused: Bool

    private let quickSearchPills = [
        "Severance", "Dune", "Cyberpunk Edgerunners", "Frieren", "Succession", "Interstellar", "Solo Leveling"
    ]

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                // 1. Prominent Stremio Catalog Search Header
                GlassPanel(cornerRadius: 16, padding: 16, highlightBorder: queryFocused) {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(XirtusTheme.cyanAccent)

                            TextField(
                                "Search Stremio Catalogs (Movies, Series, Anime) & Direct P2P Swarm...",
                                text: $vm.unifiedSearchQuery
                            )
                            .textFieldStyle(.plain)
                            .font(XirtusTheme.font(15, weight: .medium))
                            .foregroundStyle(XirtusTheme.textPrimary)
                            .focused($queryFocused)
                            .onSubmit {
                                Task { await vm.executeUnifiedSearch() }
                            }

                            if !vm.unifiedSearchQuery.isEmpty {
                                Button {
                                    vm.unifiedSearchQuery = ""
                                    vm.stremioSearchResults = []
                                    Task { await vm.loadFeaturedSwarmFeed() }
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .foregroundStyle(XirtusTheme.textMuted)
                                }
                                .buttonStyle(.plain)
                            }

                            Button {
                                Task { await vm.executeUnifiedSearch() }
                            } label: {
                                Text("Search All")
                                    .font(XirtusTheme.font(13, weight: .bold))
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 7)
                                    .background(XirtusTheme.cyanAccent)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }

                        // Filter Chips (`All · Movies · Series · Anime`) + Quick Search Pills
                        HStack(spacing: 8) {
                            ForEach(StremioSearchFilter.allCases) { filt in
                                XirtusChip(
                                    title: filt.rawValue,
                                    isSelected: vm.stremioSearchFilter == filt
                                ) {
                                    vm.stremioSearchFilter = filt
                                }
                            }

                            Spacer()

                            Menu {
                                ForEach(SwarmFilter.allCases) { sf in
                                    Button(sf.label) {
                                        vm.updateSwarmFilter(sf)
                                    }
                                }
                            } label: {
                                XirtusChip(
                                    title: "Swarm: \(vm.swarmFilter.label)",
                                    icon: "shield.lefthalf.filled",
                                    action: {}
                                )
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                        }

                        if vm.unifiedSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    Text("Quick Search:")
                                        .font(XirtusTheme.mono(11))
                                        .foregroundStyle(XirtusTheme.textMuted)
                                    ForEach(quickSearchPills, id: \.self) { pill in
                                        XirtusChip(title: pill, icon: "sparkle") {
                                            vm.unifiedSearchQuery = pill
                                            Task { await vm.executeUnifiedSearch() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Stremio Catalog Results Section
                if vm.isSearchingStremio {
                    HStack(spacing: 10) {
                        ProgressView().tint(XirtusTheme.cyanAccent)
                        Text("Searching Stremio catalogs...")
                            .font(XirtusTheme.font(13.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                } else if !vm.filteredStremioSearchResults.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text("Stremio Catalog Matches")
                                .font(XirtusTheme.font(18, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Text("\(vm.filteredStremioSearchResults.count) titles")
                                .font(XirtusTheme.mono(11.5))
                                .foregroundStyle(XirtusTheme.cyanAccent)
                            Spacer()
                        }

                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 16) {
                                ForEach(vm.filteredStremioSearchResults) { item in
                                    PosterCardView(item: item, width: 148) {
                                        vm.openDetail(for: item)
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }

                // 3. Direct P2P Swarm Section (`All Swarm · Public Domain · Custom RSS · Torznab`)
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 10) {
                        Text("Direct P2P Swarm")
                            .font(XirtusTheme.font(18, weight: .bold))
                            .foregroundStyle(XirtusTheme.textPrimary)

                        ForEach(SwarmSourceTab.allCases) { tab in
                            XirtusChip(
                                title: tab.rawValue,
                                isSelected: vm.swarmSourceTab == tab
                            ) {
                                vm.swarmSourceTab = tab
                            }
                        }

                        Spacer()

                        if vm.unifiedSearchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Menu {
                                Button("Public Domain Feature Films") { Task { await vm.loadFeaturedSwarmFeed("archive_movies") } }
                                Button("Classic Sci-Fi") { Task { await vm.loadFeaturedSwarmFeed("archive_scifi") } }
                                Button("Classic Animation") { Task { await vm.loadFeaturedSwarmFeed("archive_animation") } }
                                Button("Configured Torznab") { Task { await vm.loadFeaturedSwarmFeed("torznab") } }
                            } label: {
                                XirtusChip(title: "Live Feed: \(vm.swarmFeaturedKey)", icon: "bolt.horizontal.fill", action: {})
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                        }
                    }

                    if vm.isSearchingSwarm {
                        HStack(spacing: 10) {
                            ProgressView().tint(XirtusTheme.cyanAccent)
                            Text("Querying P2P Swarm indexers...")
                                .font(XirtusTheme.font(13))
                                .foregroundStyle(XirtusTheme.textSecondary)
                        }
                        .padding(.vertical, 12)
                    } else if vm.filteredSwarmResults.isEmpty {
                        GlassPanel(cornerRadius: 12, padding: 20) {
                            Text("No swarm torrents found for the selected tab.")
                                .font(XirtusTheme.font(13))
                                .foregroundStyle(XirtusTheme.textMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    } else {
                        LazyVStack(spacing: 9) {
                            ForEach(vm.filteredSwarmResults.prefix(150)) { cand in
                                SwarmCandidateRow(candidate: cand) {
                                    Task { await vm.playSwarmCandidateDirectly(cand) }
                                }
                            }
                        }
                    }
                }
            }
            .padding(24)
        }
        .task {
            if vm.swarmResults.isEmpty && vm.unifiedSearchQuery.isEmpty {
                await vm.loadFeaturedSwarmFeed()
            }
        }
        .onChange(of: vm.searchFocusCounter) { _, _ in
            if vm.selectedNav == .search {
                queryFocused = true
            }
        }
    }
}

public struct SwarmCandidateRow: View {
    public let candidate: StreamCandidate
    public var onPlay: () -> Void

    @State private var isHovered = false

    public var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 14) {
                Text(candidate.resolutionBadge)
                    .font(XirtusTheme.mono(11, weight: .bold))
                    .foregroundStyle(.black)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(candidate.isDirectHTTP ? XirtusTheme.emerald : XirtusTheme.cyanAccent)
                    .clipShape(RoundedRectangle(cornerRadius: 6))

                VStack(alignment: .leading, spacing: 3) {
                    // Seeds, size, resolution, codec and provider: never truncated.
                    Text(candidate.telemetryLine)
                        .font(XirtusTheme.mono(11.5, weight: .semibold))
                        .foregroundStyle(candidate.telemetryColor)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(candidate.title)
                        .font(XirtusTheme.font(13, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(alignment: .top, spacing: 10) {
                        Text(candidate.addonName.isEmpty ? "Swarm" : candidate.addonName)
                            .font(XirtusTheme.mono(11, weight: .medium))
                            .foregroundStyle(XirtusTheme.indigoAccent)
                        if !candidate.subtitle.isEmpty {
                            Text(candidate.subtitle)
                                .font(XirtusTheme.mono(11))
                                .foregroundStyle(XirtusTheme.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                Spacer()

                if candidate.seeders > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(XirtusTheme.emerald)
                        Text("\(candidate.seeders)")
                            .font(XirtusTheme.mono(11.5, weight: .semibold))
                            .foregroundStyle(XirtusTheme.emerald)
                    }
                }

                Image(systemName: "play.fill")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(isHovered ? .black : XirtusTheme.cyanAccent)
                    .frame(width: 30, height: 30)
                    .background(isHovered ? XirtusTheme.cyanAccent : XirtusTheme.surfaceElevated)
                    .clipShape(Circle())
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(isHovered ? XirtusTheme.surfaceElevated : XirtusTheme.surfaceSlate.opacity(0.88))
            .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 11, style: .continuous)
                    .strokeBorder(
                        isHovered ? XirtusTheme.cyanAccent.opacity(0.6) : XirtusTheme.hairlineBorder,
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}
