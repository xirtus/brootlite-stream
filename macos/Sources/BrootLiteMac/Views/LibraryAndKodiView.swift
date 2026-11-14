import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Library View (`All · Stremio · Jellyfin · Streaming · Kodi · Torrents`)
// ─────────────────────────────────────────────────────────────────────────────

public struct LibraryView: View {
    @Bindable public var vm: AppViewModel

    private let columns = [
        GridItem(.adaptive(minimum: 148, maximum: 176), spacing: 18)
    ]

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            GlassPanel(cornerRadius: 14, padding: 14) {
                HStack(spacing: 8) {
                    Text("Saved Library")
                        .font(XirtusTheme.font(16, weight: .bold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .padding(.trailing, 8)

                    ForEach(LibrarySourceFilter.allCases) { filt in
                        XirtusChip(
                            title: filt.rawValue,
                            isSelected: vm.libraryFilter == filt
                        ) {
                            vm.libraryFilter = filt
                        }
                    }
                    Spacer()
                    Text("\(vm.filteredFavorites.count) saved")
                        .font(XirtusTheme.mono(11.5))
                        .foregroundStyle(XirtusTheme.textMuted)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)

            ScrollView {
                if vm.filteredFavorites.isEmpty {
                    GlassPanel(cornerRadius: 16, padding: 32) {
                        VStack(spacing: 10) {
                            Image(systemName: "bookmark")
                                .font(.system(size: 34))
                                .foregroundStyle(XirtusTheme.textMuted)
                            Text("No saved favorites in \(vm.libraryFilter.rawValue)")
                                .font(XirtusTheme.font(15, weight: .semibold))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Text("Bookmark any movie, series, anime, or Jellyfin title to keep it synced across your devices via Leeloo Multipass.")
                                .font(XirtusTheme.font(12.5))
                                .foregroundStyle(XirtusTheme.textSecondary)
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: 420)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .padding(28)
                } else {
                    LazyVGrid(columns: columns, spacing: 22) {
                        ForEach(vm.filteredFavorites) { fav in
                            let meta = MetaItem(
                                id: fav.itemId,
                                mediaType: fav.mediaType,
                                name: fav.title,
                                poster: fav.poster,
                                releaseInfo: fav.releaseInfo,
                                imdbRating: fav.imdbRating
                            )
                            PosterCardView(item: meta, width: 150) {
                                vm.openDetail(for: meta)
                            }
                        }
                    }
                    .padding(24)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Disposable Python 3 Kodi Video Addon Browser View
// ─────────────────────────────────────────────────────────────────────────────

public struct KodiView: View {
    @Bindable public var vm: AppViewModel

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            GlassPanel(cornerRadius: 14, padding: 14) {
                HStack(spacing: 10) {
                    Image(systemName: "shippingbox.fill")
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    Text("Kodi Video Addon Bridge")
                        .font(XirtusTheme.font(16, weight: .bold))
                        .foregroundStyle(XirtusTheme.textPrimary)

                    Text("Disposable Python 3 runner · Zero background RAM during playback")
                        .font(XirtusTheme.mono(11))
                        .foregroundStyle(XirtusTheme.textMuted)

                    Spacer()

                    if vm.selectedKodiAddon != nil {
                        XirtusChip(title: "← All Addons") {
                            vm.selectedKodiAddon = nil
                            vm.kodiItems = []
                            vm.kodiBreadcrumbs = []
                        }
                    }

                    XirtusChip(title: "Rescan Addons", icon: "arrow.clockwise") {
                        Task { await vm.scanKodiAddons() }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let err = vm.kodiError {
                        GlassPanel(cornerRadius: 12, padding: 14, highlightBorder: true) {
                            Text(err)
                                .font(XirtusTheme.font(12.5))
                                .foregroundStyle(XirtusTheme.rose)
                        }
                    }

                    if vm.isLoadingKodi {
                        HStack(spacing: 10) {
                            ProgressView().tint(XirtusTheme.cyanAccent)
                            Text("Running disposable Python 3 Kodi bridge...")
                                .font(XirtusTheme.font(13.5))
                                .foregroundStyle(XirtusTheme.textSecondary)
                        }
                    }

                    if vm.selectedKodiAddon == nil {
                        if vm.kodiAddons.isEmpty && !vm.isLoadingKodi {
                            GlassPanel(cornerRadius: 16, padding: 28) {
                                VStack(spacing: 10) {
                                    Image(systemName: "shippingbox")
                                        .font(.system(size: 36))
                                        .foregroundStyle(XirtusTheme.indigoAccent)
                                    Text("No Installed Kodi Video Addons Found")
                                        .font(XirtusTheme.font(16, weight: .bold))
                                        .foregroundStyle(XirtusTheme.textPrimary)
                                    Text("Place unpacked `plugin.video.*` addons in `~/Library/Application Support/Kodi/addons` or BrootLite's `kodi_addons/` folder, then click Rescan Addons.")
                                        .font(XirtusTheme.font(12.5))
                                        .foregroundStyle(XirtusTheme.textSecondary)
                                        .multilineTextAlignment(.center)
                                        .frame(maxWidth: 480)
                                }
                                .frame(maxWidth: .infinity)
                            }
                        } else {
                            ForEach(vm.kodiAddons) { addon in
                                Button {
                                    Task { await vm.openKodiAddon(addon) }
                                } label: {
                                    GlassPanel(cornerRadius: 12, padding: 14) {
                                        HStack(spacing: 14) {
                                            RemoteArtwork(urlString: addon.iconPath, placeholderIcon: "puzzlepiece.extension")
                                                .frame(width: 48, height: 48)
                                                .clipShape(RoundedRectangle(cornerRadius: 10))
                                            VStack(alignment: .leading, spacing: 3) {
                                                Text("\(addon.name) · v\(addon.version)")
                                                    .font(XirtusTheme.font(14, weight: .bold))
                                                    .foregroundStyle(XirtusTheme.textPrimary)
                                                Text(addon.summary.isEmpty ? addon.id : addon.summary)
                                                    .font(XirtusTheme.font(12))
                                                    .foregroundStyle(XirtusTheme.textSecondary)
                                                    .lineLimit(1)
                                            }
                                            Spacer()
                                            Image(systemName: "chevron.right")
                                                .foregroundStyle(XirtusTheme.cyanAccent)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    } else {
                        // Breadcrumb Bar
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 6) {
                                ForEach(vm.kodiBreadcrumbs) { crumb in
                                    XirtusChip(title: crumb.title, icon: "folder") {
                                        Task { await vm.jumpToKodiBreadcrumb(crumb) }
                                    }
                                }
                            }
                        }

                        LazyVStack(spacing: 8) {
                            ForEach(vm.kodiItems) { item in
                                Button {
                                    Task { await vm.navigateKodiItem(item) }
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: item.isFolder ? "folder.fill" : "play.circle.fill")
                                            .font(.system(size: 16))
                                            .foregroundStyle(item.isFolder ? XirtusTheme.amber : XirtusTheme.cyanAccent)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(item.label)
                                                .font(XirtusTheme.font(13.5, weight: .semibold))
                                                .foregroundStyle(XirtusTheme.textPrimary)
                                                .lineLimit(1)
                                            if !item.plot.isEmpty {
                                                Text(item.plot)
                                                    .font(XirtusTheme.font(11.5))
                                                    .foregroundStyle(XirtusTheme.textSecondary)
                                                    .lineLimit(1)
                                            }
                                        }
                                        Spacer()
                                        Image(systemName: item.isFolder ? "chevron.right" : "play.fill")
                                            .font(.system(size: 12))
                                            .foregroundStyle(XirtusTheme.textMuted)
                                    }
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 11)
                                    .background(XirtusTheme.surfaceSlate.opacity(0.9))
                                    .clipShape(RoundedRectangle(cornerRadius: 10))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 10)
                                            .strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                }
                .padding(24)
            }
        }
        .task {
            if vm.kodiAddons.isEmpty {
                await vm.scanKodiAddons()
            }
        }
    }
}
