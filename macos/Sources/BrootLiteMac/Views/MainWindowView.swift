import AppKit
import SwiftUI

public struct MainWindowView: View {
    @Bindable public var vm: AppViewModel
    @State private var keyMonitor: Any?

    public init(vm: AppViewModel) {
        self.vm = vm
    }

    public var body: some View {
        ZStack {
            XirtusTheme.bgObsidian.ignoresSafeArea()

            VStack(spacing: 0) {
                topNavigationBar

                Divider()
                    .overlay(XirtusTheme.hairlineBorder)

                mainSectionContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            // Detail Modal Overlay
            if let item = vm.detailItem {
                ZStack {
                    Color.black.opacity(0.68)
                        .ignoresSafeArea()
                        .onTapGesture {
                            vm.detailItem = nil
                        }

                    DetailSheetView(vm: vm, item: item)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1)
                        )
                        .shadow(color: .black.opacity(0.65), radius: 30, x: 0, y: 12)
                        .padding(32)
                }
                .transition(.opacity)
            }

            // Resolving Stream Spinner Overlay
            if vm.isResolvingPlayback {
                ZStack {
                    Color.black.opacity(0.45).ignoresSafeArea()
                    GlassPanel(cornerRadius: 16, padding: 22, highlightBorder: true) {
                        HStack(spacing: 12) {
                            ProgressView().tint(XirtusTheme.cyanAccent)
                            Text("Resolving hardware stream...")
                                .font(XirtusTheme.font(14, weight: .semibold))
                                .foregroundStyle(XirtusTheme.textPrimary)
                        }
                    }
                }
            }

            // Fullscreen-Capable Hardware `libmpv` (`VideoToolbox`) Cinema Player Overlay
            if let session = vm.activeSession {
                CinemaPlayerOverlayView(vm: vm, session: session)
                    .transition(.opacity)
                    .zIndex(20)
            }

            // Non-Intrusive Toast Banner
            if let toast = vm.toastMessage {
                VStack {
                    Spacer()
                    Text(toast)
                        .font(XirtusTheme.font(13, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(XirtusTheme.surfaceElevated.opacity(0.95))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(XirtusTheme.cyanAccent.opacity(0.55), lineWidth: 1))
                        .shadow(color: .black.opacity(0.45), radius: 12, x: 0, y: 5)
                        .padding(.bottom, 22)
                }
                .zIndex(30)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .preferredColorScheme(.dark)
        .task {
            await vm.bootstrapIfNeeded()
        }
        .onAppear {
            installKeyboardShortcuts()
        }
        .onDisappear {
            if let keyMonitor {
                NSEvent.removeMonitor(keyMonitor)
                self.keyMonitor = nil
            }
        }
    }

    // MARK: - Top Navigation Chrome (`Board · Discover · Jellyfin · Streaming · Search · Library · Kodi · Passport ↗`)

    private var topNavigationBar: some View {
        HStack(spacing: 10) {
            // Brand Mark
            HStack(spacing: 9) {
                if let url = Bundle.main.url(forResource: "BrandMark", withExtension: "png"),
                   let mark = NSImage(contentsOf: url) {
                    // brootlite "bL" mark from branding/ (copied in by build-app.sh)
                    Image(nsImage: mark)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: 30, height: 30)
                } else {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .fill(XirtusTheme.accentGradient)
                        .frame(width: 28, height: 28)
                        .overlay(
                            Image(systemName: "play.fill")
                                .font(.system(size: 12, weight: .black))
                                .foregroundStyle(.black)
                        )
                }
                Text("BrootLite")
                    .font(XirtusTheme.font(16, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
            }
            .padding(.trailing, 8)

            // Primary Navigation Tabs
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(NavSection.allCases) { nav in
                        let selected = (vm.selectedNav == nav)
                        Button {
                            vm.selectedNav = nav
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: nav.icon)
                                    .font(.system(size: 11.5, weight: .semibold))
                                Text(nav.rawValue)
                                    .font(XirtusTheme.font(13, weight: selected ? .bold : .medium))
                            }
                            .foregroundStyle(selected ? .black : XirtusTheme.textSecondary)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 7)
                            .background(
                                Group {
                                    if selected {
                                        XirtusTheme.cyanAccent
                                    } else {
                                        Color.clear
                                    }
                                }
                            )
                            .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Spacer()

            // Quick `⌘K` Search Pill + Memory Footprint Pill
            Button {
                vm.selectedNav = .search
                vm.focusGlobalStremioSearch()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    Text("⌘K")
                        .font(XirtusTheme.mono(11, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textSecondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(XirtusTheme.surfaceSlate)
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)

            Button {
                vm.selectedNav = .passport
            } label: {
                HStack(spacing: 5) {
                    Circle()
                        .fill(vm.status?.multipassPaired == true ? XirtusTheme.emerald : XirtusTheme.indigoAccent)
                        .frame(width: 6.5, height: 6.5)
                    Text(String(format: "%.0f MB", vm.footprintMemoryMB))
                        .font(XirtusTheme.mono(11, weight: .medium))
                        .foregroundStyle(XirtusTheme.textSecondary)
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(XirtusTheme.surfaceSlate)
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 11)
        .background(
            ZStack {
                FrostedVisualEffectView(material: .headerView, blendingMode: .withinWindow)
                    .opacity(0.55)
                XirtusTheme.bgObsidian.opacity(0.88)
            }
        )
    }

    @ViewBuilder
    private var mainSectionContent: some View {
        switch vm.selectedNav {
        case .board:
            BoardView(vm: vm)
        case .discover:
            DiscoverView(vm: vm)
        case .jellyfin:
            JellyfinScreenView(vm: vm)
        case .streaming:
            StreamingView(vm: vm)
        case .search:
            SearchView(vm: vm)
        case .library:
            LibraryView(vm: vm)
        case .kodi:
            KodiView(vm: vm)
        case .passport:
            PassportView(vm: vm)
        }
    }

    // MARK: - Native macOS Keyboard Shortcuts (`⌘K`/`⌘F`, `Space`, `←`/`→`, `F`, `Z`/`⌫`, `Esc`)

    private func installKeyboardShortcuts() {
        guard keyMonitor == nil else { return }
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            let chars = event.charactersIgnoringModifiers?.lowercased() ?? ""

            // `⌘K` or `⌘F` -> Focus Stremio Search
            if flags == .command && (chars == "k" || chars == "f") {
                if vm.activeSession != nil {
                    vm.closePlayer()
                }
                vm.detailItem = nil
                vm.focusGlobalStremioSearch()
                return nil
            }

            // `Esc` (keyCode 53) -> Close Cinema Player or Detail Overlay
            if event.keyCode == 53 {
                if vm.activeSession != nil {
                    if vm.player.isKeyScrubbing {
                        vm.player.cancelKeyboardScrub()
                        return nil
                    }
                    vm.closePlayer()
                    return nil
                }
                if vm.detailItem != nil {
                    vm.detailItem = nil
                    return nil
                }
                return event
            }

            // Single-key Cinema Player shortcuts (only when player is open and no text field is active)
            let isEditingText = (NSApplication.shared.keyWindow?.firstResponder is NSTextView)
            if vm.activeSession != nil && !isEditingText && flags.isEmpty {
                switch event.keyCode {
                case 49: // Space -> Play/Pause (commits any pending scrub first)
                    if vm.player.isKeyScrubbing {
                        vm.player.commitKeyboardScrubNow()
                    }
                    vm.player.togglePlayPause()
                    return nil
                case 36, 76: // Return / Enter -> Immediately commit active keyboard scrub
                    if vm.player.isKeyScrubbing {
                        vm.player.commitKeyboardScrubNow()
                        return nil
                    }
                case 123: // Left Arrow -> Accelerating debounced scrub backward
                    vm.player.stepKeyboardScrub(direction: -1, isRepeat: event.isARepeat)
                    return nil
                case 124: // Right Arrow -> Accelerating debounced scrub forward
                    vm.player.stepKeyboardScrub(direction: 1, isRepeat: event.isARepeat)
                    return nil
                case 51: // Backspace -> Undo SponsorBlock skip
                    vm.undoLastSponsorSkip()
                    return nil
                default:
                    if chars == "j" {
                        vm.player.stepKeyboardScrub(direction: -1, isRepeat: event.isARepeat)
                        return nil
                    } else if chars == "l" {
                        vm.player.stepKeyboardScrub(direction: 1, isRepeat: event.isARepeat)
                        return nil
                    } else if chars == "k" {
                        if vm.player.isKeyScrubbing {
                            vm.player.commitKeyboardScrubNow()
                        }
                        vm.player.togglePlayPause()
                        return nil
                    } else if chars == "f" {
                        vm.player.toggleWindowFullscreen()
                        return nil
                    } else if chars == "z" {
                        vm.undoLastSponsorSkip()
                        return nil
                    }
                }
            }

            return event
        }
    }
}
