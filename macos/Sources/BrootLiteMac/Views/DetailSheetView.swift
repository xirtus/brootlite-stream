import SwiftUI

public struct DetailSheetView: View {
    @Bindable public var vm: AppViewModel
    public let item: MetaItem

    public init(vm: AppViewModel, item: MetaItem) {
        self.vm = vm
        self.item = item
    }

    public var body: some View {
        ZStack(alignment: .topTrailing) {
            XirtusTheme.bgObsidian.ignoresSafeArea()

            // Ambient Backdrop Header
            VStack(spacing: 0) {
                ZStack(alignment: .bottomLeading) {
                    RemoteArtwork(
                        urlString: !item.background.isEmpty ? item.background : item.poster,
                        contentMode: .fill
                    )
                    .frame(height: 250)
                    .clipped()
                    .opacity(0.32)

                    LinearGradient(
                        colors: [Color.clear, XirtusTheme.bgObsidian],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 250)
                }
                Spacer()
            }
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    // Top Hero Metadata Row
                    HStack(alignment: .top, spacing: 24) {
                        RemoteArtwork(urlString: item.poster)
                            .frame(width: 172, height: 254)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1)
                            )
                            .shadow(color: .black.opacity(0.5), radius: 14, x: 0, y: 8)

                        VStack(alignment: .leading, spacing: 10) {
                            HStack(spacing: 10) {
                                Text(item.isJellyfin ? "JELLYFIN" : item.mediaType.uppercased())
                                    .font(XirtusTheme.mono(10.5, weight: .bold))
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .background(item.isJellyfin ? XirtusTheme.indigoAccent : XirtusTheme.cyanAccent)
                                    .clipShape(Capsule())

                                if !item.imdbRating.isEmpty {
                                    HStack(spacing: 4) {
                                        Image(systemName: "star.fill")
                                            .foregroundStyle(XirtusTheme.amber)
                                        Text("IMDb \(item.imdbRating)")
                                            .font(XirtusTheme.mono(12, weight: .semibold))
                                            .foregroundStyle(XirtusTheme.textPrimary)
                                    }
                                }

                                if !item.releaseInfo.isEmpty {
                                    Text(item.releaseInfo)
                                        .font(XirtusTheme.mono(12))
                                        .foregroundStyle(XirtusTheme.textSecondary)
                                }
                                if !item.runtime.isEmpty {
                                    Text(item.runtime)
                                        .font(XirtusTheme.mono(12))
                                        .foregroundStyle(XirtusTheme.textSecondary)
                                }
                            }

                            Text(item.name)
                                .font(XirtusTheme.font(28, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)

                            if !item.genres.isEmpty {
                                HStack(spacing: 6) {
                                    ForEach(item.genres.prefix(6), id: \.self) { g in
                                        Text(g)
                                            .font(XirtusTheme.font(11.5, weight: .medium))
                                            .foregroundStyle(XirtusTheme.textSecondary)
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 4)
                                            .background(XirtusTheme.surfaceSlate)
                                            .clipShape(Capsule())
                                            .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
                                    }
                                }
                            }

                            if !item.description.isEmpty {
                                Text(item.description)
                                    .font(XirtusTheme.font(13.5))
                                    .foregroundStyle(XirtusTheme.textSecondary)
                                    .lineSpacing(3)
                                    .lineLimit(5)
                            }

                            HStack(spacing: 12) {
                                if let firstStream = vm.filteredDetailStreams.first {
                                    Button {
                                        Task {
                                            await vm.playMetaWithCandidate(
                                                meta: item,
                                                episode: vm.detailSelectedEpisode,
                                                candidate: firstStream
                                            )
                                        }
                                    } label: {
                                        HStack(spacing: 8) {
                                            Image(systemName: "play.fill")
                                            Text(item.isJellyfin ? "Play on Jellyfin" : "Play Best Stream")
                                                .font(XirtusTheme.font(13.5, weight: .bold))
                                        }
                                        .foregroundStyle(.black)
                                        .padding(.horizontal, 20)
                                        .padding(.vertical, 10)
                                        .background(XirtusTheme.cyanAccent)
                                        .clipShape(Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }

                                let saved = vm.isFavorite(itemId: item.id)
                                XirtusChip(
                                    title: saved ? "Saved in Library" : "+ Save to Library",
                                    icon: saved ? "bookmark.fill" : "bookmark",
                                    isSelected: saved
                                ) {
                                    Task { await vm.toggleFavoriteMeta(item) }
                                }
                            }
                            .padding(.top, 4)
                        }
                    }

                    // Season & Episode Selector (for Series / Anime)
                    if !item.videos.isEmpty {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack(spacing: 8) {
                                Text("Episodes")
                                    .font(XirtusTheme.font(17.5, weight: .bold))
                                    .foregroundStyle(XirtusTheme.textPrimary)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 6) {
                                        ForEach(vm.detailSeasons, id: \.self) { season in
                                            XirtusChip(
                                                title: season == 0 ? "Specials" : "Season \(season)",
                                                isSelected: vm.detailSelectedSeason == season
                                            ) {
                                                vm.detailSelectedSeason = season
                                                if let firstEp = vm.detailEpisodesForSeason.first {
                                                    vm.selectDetailEpisode(firstEp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            ScrollView(.horizontal, showsIndicators: false) {
                                LazyHStack(spacing: 12) {
                                    ForEach(vm.detailEpisodesForSeason) { ep in
                                        let isSelected = vm.detailSelectedEpisode?.id == ep.id
                                        Button {
                                            vm.selectDetailEpisode(ep)
                                        } label: {
                                            VStack(alignment: .leading, spacing: 6) {
                                                RemoteArtwork(
                                                    urlString: !ep.thumbnail.isEmpty ? ep.thumbnail : item.poster,
                                                    placeholderIcon: "tv"
                                                )
                                                .frame(width: 196, height: 110)
                                                .clipShape(RoundedRectangle(cornerRadius: 9))
                                                .overlay(
                                                    RoundedRectangle(cornerRadius: 9)
                                                        .strokeBorder(
                                                            isSelected ? XirtusTheme.cyanAccent : XirtusTheme.hairlineBorder,
                                                            lineWidth: isSelected ? 2 : 1
                                                        )
                                                )

                                                Text("\(ep.seasonEpisodeCode) · \(ep.title)")
                                                    .font(XirtusTheme.font(12, weight: .semibold))
                                                    .foregroundStyle(isSelected ? XirtusTheme.cyanAccent : XirtusTheme.textPrimary)
                                                    .lineLimit(1)
                                                    .frame(width: 196, alignment: .leading)
                                            }
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }

                    // Progressive Stream List (`All · Direct / Mirrors · P2P Swarm`)
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 10) {
                            Text("Available Streams")
                                .font(XirtusTheme.font(17.5, weight: .bold))
                                .foregroundStyle(XirtusTheme.textPrimary)

                            ForEach(StreamModeFilter.allCases) { mode in
                                XirtusChip(
                                    title: mode.rawValue,
                                    isSelected: vm.detailStreamFilter == mode
                                ) {
                                    vm.detailStreamFilter = mode
                                }
                            }

                            Spacer()

                            if vm.isLoadingDetailStreams {
                                HStack(spacing: 6) {
                                    ProgressView().controlSize(.small)
                                    Text("Resolving addons & swarm...")
                                        .font(XirtusTheme.mono(11))
                                        .foregroundStyle(XirtusTheme.textSecondary)
                                }
                            } else {
                                Text("\(vm.filteredDetailStreams.count) streams")
                                    .font(XirtusTheme.mono(11.5))
                                    .foregroundStyle(XirtusTheme.textMuted)
                            }
                        }

                        if !vm.isLoadingDetailStreams && vm.filteredDetailStreams.isEmpty {
                            GlassPanel(cornerRadius: 12, padding: 18) {
                                Text("No streams matched this filter. Try switching to All or installing a stream addon in Passport ↗.")
                                    .font(XirtusTheme.font(13))
                                    .foregroundStyle(XirtusTheme.textSecondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        } else {
                            LazyVStack(spacing: 9) {
                                ForEach(vm.filteredDetailStreams.prefix(250)) { stream in
                                    SwarmCandidateRow(candidate: stream) {
                                        Task {
                                            await vm.playMetaWithCandidate(
                                                meta: item,
                                                episode: vm.detailSelectedEpisode,
                                                candidate: stream
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(28)
            }

            // Close Button (`Esc`)
            Button {
                vm.detailItem = nil
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                    .frame(width: 34, height: 34)
                    .background(XirtusTheme.surfaceSlate.opacity(0.92))
                    .clipShape(Circle())
                    .overlay(Circle().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(20)
        }
        .frame(minWidth: 900, minHeight: 620)
    }
}
