import SwiftUI

public struct BoardView: View {
    @Bindable public var vm: AppViewModel
    @FocusState private var searchBarFocused: Bool

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                // 1. Prominent Top Frosted-Glass Stremio Search Bar (`⌘K`)
                prominentStremioSearchBar

                // 2. Unified Continue Watching Rail (Stremio + Per-Server Jellyfin + YouTube/Creators + Local)
                if !vm.continueWatchingItems.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        sectionHeader(
                            title: "Continue Watching",
                            subtitle: "Stremio · Jellyfin · YouTube · Local Progress",
                            icon: "play.circle.fill"
                        )
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 16) {
                                ForEach(vm.continueWatchingItems) { item in
                                    WideMediaCardView(
                                        title: item.title,
                                        subtitle: item.subtitle,
                                        artworkUrl: item.artworkUrl,
                                        badgeText: item.badge,
                                        progressRatio: item.progressRatio,
                                        width: 264
                                    ) {
                                        vm.openResumeItem(item)
                                    }
                                }
                            }
                            .padding(.horizontal, 2)
                            .padding(.vertical, 4)
                        }
                    }
                }

                // 3. Dynamic Horizontal Poster Rails for All Synced Stremio Catalogs
                if vm.isLoadingBoard && vm.boardRails.isEmpty {
                    HStack(spacing: 12) {
                        ProgressView().tint(XirtusTheme.cyanAccent)
                        Text("Loading synced Stremio catalogs...")
                            .font(XirtusTheme.font(13.5, weight: .medium))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    .padding(.vertical, 36)
                } else {
                    ForEach(vm.boardRails) { rail in
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(rail.title)
                                    .font(XirtusTheme.font(17.5, weight: .bold))
                                    .foregroundStyle(XirtusTheme.textPrimary)
                                Text(rail.subtitle)
                                    .font(XirtusTheme.mono(11))
                                    .foregroundStyle(XirtusTheme.textMuted)
                                Spacer()
                                Button {
                                    vm.selectedNav = .discover
                                } label: {
                                    Text("Explore →")
                                        .font(XirtusTheme.font(12, weight: .semibold))
                                        .foregroundStyle(XirtusTheme.cyanAccent)
                                }
                                .buttonStyle(.plain)
                            }

                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 15) {
                                    ForEach(rail.items) { item in
                                        PosterCardView(item: item, width: 146) {
                                            vm.openDetail(for: item)
                                        }
                                    }
                                }
                                .padding(.horizontal, 2)
                                .padding(.vertical, 4)
                            }
                        }
                    }
                }
            }
            .padding(26)
        }
        .onChange(of: vm.searchFocusCounter) { _, _ in
            if vm.selectedNav == .board {
                searchBarFocused = true
            }
        }
    }

    private var prominentStremioSearchBar: some View {
        GlassPanel(cornerRadius: 16, padding: 14, highlightBorder: searchBarFocused) {
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(searchBarFocused ? XirtusTheme.cyanAccent : XirtusTheme.textSecondary)

                TextField(
                    "Search Stremio Movies, Series, Anime & Addons...",
                    text: $vm.boardSearchQuery
                )
                .textFieldStyle(.plain)
                .font(XirtusTheme.font(15, weight: .medium))
                .foregroundStyle(XirtusTheme.textPrimary)
                .focused($searchBarFocused)
                .onSubmit {
                    vm.submitBoardStremioSearch()
                }

                if !vm.boardSearchQuery.isEmpty {
                    Button {
                        vm.boardSearchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(XirtusTheme.textMuted)
                    }
                    .buttonStyle(.plain)
                }

                Text("⌘K")
                    .font(XirtusTheme.mono(11, weight: .semibold))
                    .foregroundStyle(XirtusTheme.textSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(XirtusTheme.surfaceElevated)
                    .clipShape(RoundedRectangle(cornerRadius: 6))

                Button {
                    vm.submitBoardStremioSearch()
                } label: {
                    Text("Search")
                        .font(XirtusTheme.font(13, weight: .bold))
                        .foregroundStyle(.black)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 7)
                        .background(XirtusTheme.cyanAccent)
                        .clipShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func sectionHeader(title: String, subtitle: String, icon: String) -> some View {
        HStack(spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(XirtusTheme.cyanAccent)
            Text(title)
                .font(XirtusTheme.font(18, weight: .bold))
                .foregroundStyle(XirtusTheme.textPrimary)
            Text(subtitle)
                .font(XirtusTheme.mono(11))
                .foregroundStyle(XirtusTheme.textMuted)
            Spacer()
        }
    }
}
