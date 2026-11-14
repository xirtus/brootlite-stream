import SwiftUI

public struct DiscoverView: View {
    @Bindable public var vm: AppViewModel

    private let columns = [
        GridItem(.adaptive(minimum: 148, maximum: 176), spacing: 18)
    ]

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Top Frosted Filter Bar
            GlassPanel(cornerRadius: 14, padding: 14) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 12) {
                        // 1. Type Selector
                        Menu {
                            ForEach(vm.discoverAvailableTypes, id: \.self) { mtype in
                                Button(mtype.capitalized) {
                                    vm.discoverType = mtype
                                    vm.selectedCatalogOptionId = ""
                                    vm.discoverGenreOrYear = "All"
                                    Task { await vm.reloadDiscover(reset: true) }
                                }
                            }
                        } label: {
                            selectorPill(label: "Type", value: vm.discoverType.capitalized)
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()

                        // 2. Catalog / Network / By Year Selector
                        Menu {
                            ForEach(vm.discoverCatalogOptions) { opt in
                                Button(opt.displayLabel) {
                                    vm.selectedCatalogOptionId = opt.id
                                    vm.discoverGenreOrYear = opt.isByYearSynthetic ? "2025" : "All"
                                    Task { await vm.reloadDiscover(reset: true) }
                                }
                            }
                        } label: {
                            selectorPill(
                                label: "Catalog",
                                value: vm.activeDiscoverCatalog?.displayLabel ?? "Popular"
                            )
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()

                        // 3. Genre / Year / Extra Selector
                        if let cat = vm.activeDiscoverCatalog, !cat.genres.isEmpty {
                            Menu {
                                if !cat.isByYearSynthetic {
                                    Button("All") {
                                        vm.discoverGenreOrYear = "All"
                                        Task { await vm.reloadDiscover(reset: true) }
                                    }
                                }
                                ForEach(cat.genres, id: \.self) { g in
                                    Button(g) {
                                        vm.discoverGenreOrYear = g
                                        Task { await vm.reloadDiscover(reset: true) }
                                    }
                                }
                            } label: {
                                selectorPill(
                                    label: cat.isByYearSynthetic ? "Year" : "Genre",
                                    value: vm.discoverGenreOrYear
                                )
                            }
                            .menuStyle(.borderlessButton)
                            .fixedSize()
                        }

                        // 4. Sort Selector
                        Menu {
                            ForEach(DiscoverSortMode.allCases) { mode in
                                Button(mode.label) {
                                    vm.setDiscoverSort(mode)
                                }
                            }
                        } label: {
                            selectorPill(label: "Sort", value: vm.discoverSort.label)
                        }
                        .menuStyle(.borderlessButton)
                        .fixedSize()

                        Spacer()

                        // 5. Inline Search Pill
                        HStack(spacing: 7) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(XirtusTheme.cyanAccent)
                            TextField("Filter or search catalog...", text: $vm.discoverInlineQuery)
                                .textFieldStyle(.plain)
                                .font(XirtusTheme.font(12.5))
                                .foregroundStyle(XirtusTheme.textPrimary)
                                .frame(width: 190)
                                .onSubmit {
                                    Task { await vm.reloadDiscover(reset: true) }
                                }
                            if !vm.discoverInlineQuery.isEmpty {
                                Button {
                                    vm.discoverInlineQuery = ""
                                    Task { await vm.reloadDiscover(reset: true) }
                                } label: {
                                    Image(systemName: "xmark.circle.fill")
                                        .font(.system(size: 12))
                                        .foregroundStyle(XirtusTheme.textMuted)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(XirtusTheme.surfaceElevated)
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            .padding(.bottom, 12)

            // Responsive Poster Grid with Infinite Scroll
            ScrollView {
                if vm.isLoadingDiscover && vm.discoverItems.isEmpty {
                    HStack(spacing: 10) {
                        ProgressView().tint(XirtusTheme.cyanAccent)
                        Text("Fetching catalog items...")
                            .font(XirtusTheme.font(13.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    .padding(44)
                } else if vm.discoverItems.isEmpty {
                    VStack(spacing: 10) {
                        Image(systemName: "film.stack")
                            .font(.system(size: 36))
                            .foregroundStyle(XirtusTheme.textMuted)
                        Text("No catalog items matched the current filters.")
                            .font(XirtusTheme.font(14, weight: .medium))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(60)
                } else {
                    LazyVGrid(columns: columns, spacing: 22) {
                        ForEach(vm.discoverItems) { item in
                            PosterCardView(item: item, width: 150) {
                                vm.openDetail(for: item)
                            }
                            .onAppear {
                                vm.loadMoreDiscoverIfNeeded(currentItem: item)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 14)

                    if vm.isLoadingDiscover {
                        ProgressView()
                            .tint(XirtusTheme.cyanAccent)
                            .padding(.vertical, 20)
                    }
                }
            }
        }
        .task {
            if vm.discoverItems.isEmpty {
                await vm.reloadDiscover(reset: true)
            }
        }
    }

    private func selectorPill(label: String, value: String) -> some View {
        HStack(spacing: 6) {
            Text(label.uppercased())
                .font(XirtusTheme.mono(10, weight: .medium))
                .foregroundStyle(XirtusTheme.textMuted)
            Text(value)
                .font(XirtusTheme.font(12.5, weight: .semibold))
                .foregroundStyle(XirtusTheme.textPrimary)
                .lineLimit(1)
            Image(systemName: "chevron.down")
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(XirtusTheme.cyanAccent)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(XirtusTheme.surfaceElevated)
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
    }
}
