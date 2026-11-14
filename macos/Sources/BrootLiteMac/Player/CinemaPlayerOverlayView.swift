import AppKit
import SwiftUI

public struct CinemaPlayerOverlayView: View {
    @Bindable public var vm: AppViewModel
    public let session: ActivePlaybackSession

    @State private var showOSD: Bool = true
    @State private var isScrubbing: Bool = false
    @State private var scrubOriginSec: Double = 0
    @State private var scrubPreviewSec: Double = 0
    @State private var isHoveringTimeline: Bool = false
    @State private var hoverPreviewSec: Double = 0
    @State private var hideTimerTask: Task<Void, Never>?
    @State private var showAddSubtitlePopover: Bool = false
    @State private var customSubtitleUrl: String = ""

    public init(vm: AppViewModel, session: ActivePlaybackSession) {
        self.vm = vm
        self.session = session
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            HardwareVideoSurface(controller: vm.player)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture(count: 2) {
                    vm.player.toggleWindowFullscreen()
                }
                .onTapGesture(count: 1) {
                    bumpOSDVisibility()
                    vm.player.togglePlayPause()
                }
                .onContinuousHover { phase in
                    if case .active = phase {
                        bumpOSDVisibility()
                    }
                }

            // Buffering Spinner
            if vm.player.isBuffering {
                VStack(spacing: 10) {
                    ProgressView()
                        .controlSize(.large)
                        .tint(XirtusTheme.cyanAccent)
                    if let swarm = vm.liveSwarmStats {
                        Text(swarm.formattedOverlay.isEmpty ? "Prebuffering P2P Swarm (\(swarm.livePeers) peers)..." : swarm.formattedOverlay)
                            .font(XirtusTheme.mono(12, weight: .medium))
                            .foregroundStyle(XirtusTheme.textPrimary)
                    } else {
                        Text("Buffering stream...")
                            .font(XirtusTheme.font(13, weight: .medium))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 16)
                .background(XirtusTheme.surfaceSlate.opacity(0.88))
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1)
                )
            }

            // Playback Error Banner
            if let err = vm.player.playbackError {
                GlassPanel(cornerRadius: 14, padding: 18, highlightBorder: true) {
                    VStack(spacing: 10) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(XirtusTheme.rose)
                        Text(err)
                            .font(XirtusTheme.font(13.5, weight: .medium))
                            .foregroundStyle(XirtusTheme.textPrimary)
                            .multilineTextAlignment(.center)
                        Button("Close Player") {
                            vm.closePlayer()
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(XirtusTheme.cyanAccent)
                    }
                    .frame(maxWidth: 380)
                }
            }

            // SponsorBlock Auto-Skip Toast (Top-Right)
            if let sbToast = vm.sponsorSkipToast {
                VStack {
                    HStack {
                        Spacer()
                        HStack(spacing: 12) {
                            Image(systemName: "forward.end.alt.fill")
                                .foregroundStyle(XirtusTheme.emerald)
                            Text(sbToast)
                                .font(XirtusTheme.font(12.5, weight: .semibold))
                                .foregroundStyle(XirtusTheme.textPrimary)
                            Button {
                                vm.undoLastSponsorSkip()
                            } label: {
                                Text("Undo [Z]")
                                    .font(XirtusTheme.mono(11, weight: .semibold))
                                    .foregroundStyle(.black)
                                    .padding(.horizontal, 9)
                                    .padding(.vertical, 4)
                                    .background(XirtusTheme.cyanAccent)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(XirtusTheme.surfaceSlate.opacity(0.94))
                        .clipShape(Capsule())
                        .overlay(Capsule().strokeBorder(XirtusTheme.emerald.opacity(0.55), lineWidth: 1))
                        .padding(.top, 76)
                        .padding(.trailing, 24)
                    }
                    Spacer()
                }
                .transition(.move(edge: .top).combined(with: .opacity))
            }

            // Floating `Skip Intro ⏭` Pill (Bottom-Right above timeline)
            if let introSeg = vm.activeIntroSkipSegment {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        Button {
                            vm.skipActiveIntroSegment()
                        } label: {
                            HStack(spacing: 8) {
                                Text(introSeg.label.isEmpty ? "Skip Intro" : "Skip \(introSeg.label)")
                                    .font(XirtusTheme.font(13.5, weight: .bold))
                                Image(systemName: "forward.end.fill")
                                    .font(.system(size: 12, weight: .bold))
                            }
                            .foregroundStyle(.black)
                            .padding(.horizontal, 18)
                            .padding(.vertical, 10)
                            .background(XirtusTheme.cyanAccent)
                            .clipShape(Capsule())
                            .shadow(color: XirtusTheme.cyanAccent.opacity(0.4), radius: 12, x: 0, y: 4)
                        }
                        .buttonStyle(.plain)
                        .padding(.trailing, 28)
                        .padding(.bottom, 118)
                    }
                }
                .transition(.opacity)
            }

            // Smart Series Binge Autoplay Card (`"KEEP WATCHING · SxxEyy"`)
            if let proposal = vm.nextEpisodeProposal,
               let countdown = vm.bingeCountdownRemaining,
               !vm.watchCreditsDismissedBinge {
                VStack {
                    Spacer()
                    HStack {
                        Spacer()
                        smartBingeCard(proposal: proposal, countdown: countdown)
                            .padding(.trailing, 28)
                            .padding(.bottom, showOSD ? 118 : 34)
                    }
                }
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }

            // Movie / Finale Credits `"NEXT RECOMMENDED"` Bottom Glass Gallery
            if vm.shouldShowCreditsRecommendationsOverlay {
                VStack {
                    Spacer()
                    creditsRecommendationsGallery
                        .padding(.horizontal, 24)
                        .padding(.bottom, showOSD ? 112 : 24)
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            // Top + Bottom Cinema OSD Chrome
            if showOSD || isScrubbing || vm.player.isKeyScrubbing || !vm.player.isPlaying || vm.player.eofReached {
                VStack {
                    topHeaderBar
                    Spacer()
                    bottomTransportBar
                }
                .transition(.opacity)
            }
        }
        .onChange(of: vm.player.keyScrubTargetSec) { _, _ in
            if vm.player.isKeyScrubbing {
                bumpOSDVisibility()
            }
        }
        .onAppear {
            bumpOSDVisibility()
        }
        .onDisappear {
            hideTimerTask?.cancel()
        }
    }

    // MARK: - Top Header Bar

    private var topHeaderBar: some View {
        HStack(spacing: 14) {
            Button {
                vm.closePlayer()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 13, weight: .bold))
                    Text("Back")
                        .font(XirtusTheme.font(13, weight: .semibold))
                }
                .foregroundStyle(XirtusTheme.textPrimary)
                .padding(.horizontal, 13)
                .padding(.vertical, 8)
                .background(XirtusTheme.surfaceSlate.opacity(0.88))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                Text(session.title)
                    .font(XirtusTheme.font(15.5, weight: .bold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                    .lineLimit(1)
                Text(session.subtitle)
                    .font(XirtusTheme.font(12))
                    .foregroundStyle(XirtusTheme.textSecondary)
                    .lineLimit(1)
            }

            Spacer()

            // ReturnYouTubeDislike (`👍 / 👎`) Pill
            if let votes = vm.activeRYDVotes {
                HStack(spacing: 10) {
                    HStack(spacing: 4) {
                        Text("👍")
                        Text(Self.compactCount(votes.likes))
                            .font(XirtusTheme.mono(11.5, weight: .semibold))
                            .foregroundStyle(XirtusTheme.emerald)
                    }
                    Divider().frame(height: 12)
                    HStack(spacing: 4) {
                        Text("👎")
                        Text(Self.compactCount(votes.dislikes))
                            .font(XirtusTheme.mono(11.5, weight: .medium))
                            .foregroundStyle(XirtusTheme.rose)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(XirtusTheme.surfaceSlate.opacity(0.9))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
            }

            // Live P2P `librqbit` Swarm Telemetry Badge
            if let swarm = vm.liveSwarmStats {
                HStack(spacing: 7) {
                    Circle()
                        .fill(XirtusTheme.emerald)
                        .frame(width: 7, height: 7)
                    Text(
                        String(
                            format: "↓ %.1f MB/s · %u Peers",
                            swarm.downloadSpeedMbps,
                            swarm.livePeers
                        )
                    )
                    .font(XirtusTheme.mono(11.5, weight: .medium))
                    .foregroundStyle(XirtusTheme.cyanAccent)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(XirtusTheme.surfaceSlate.opacity(0.92))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.cyanAccent.opacity(0.45), lineWidth: 1))
            }

            // Hardware Decoder Badge (`libmpv · videotoolbox`)
            Text(vm.player.backendBadge)
                .font(XirtusTheme.mono(11, weight: .medium))
                .foregroundStyle(XirtusTheme.indigoAccent)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(XirtusTheme.surfaceSlate.opacity(0.88))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 26)
        .background(
            LinearGradient(
                colors: [Color.black.opacity(0.82), Color.clear],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    // MARK: - Bottom Transport & Track Controls

    private var bottomTransportBar: some View {
        VStack(spacing: 10) {
            // Scrubbable Timeline with Skip Segment Highlights
            timelineScrubber

            HStack(spacing: 16) {
                // Play / Pause
                Button {
                    vm.player.togglePlayPause()
                } label: {
                    Image(systemName: vm.player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 17, weight: .bold))
                        .foregroundStyle(.black)
                        .frame(width: 38, height: 38)
                        .background(XirtusTheme.cyanAccent)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)

                // -10s / +30s
                Button {
                    vm.player.seekRelative(-10)
                } label: {
                    Label("10s", systemImage: "gobackward.10")
                        .labelStyle(.iconOnly)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                }
                .buttonStyle(.plain)

                Button {
                    vm.player.seekRelative(30)
                } label: {
                    Label("30s", systemImage: "goforward.30")
                        .labelStyle(.iconOnly)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                }
                .buttonStyle(.plain)

                // Time Readout
                let displayPos = isScrubbing ? scrubPreviewSec : (vm.player.isKeyScrubbing ? vm.player.keyScrubTargetSec : vm.player.positionSec)
                Text("\(Self.formatSeconds(displayPos)) / \(Self.formatSeconds(vm.player.durationSec))")
                    .font(XirtusTheme.mono(12, weight: .medium))
                    .foregroundStyle((isScrubbing || vm.player.isKeyScrubbing) ? XirtusTheme.cyanAccent : XirtusTheme.textSecondary)

                // Volume
                HStack(spacing: 6) {
                    Button {
                        vm.player.toggleMute()
                    } label: {
                        Image(systemName: vm.player.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .font(.system(size: 13.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    .buttonStyle(.plain)

                    Slider(
                        value: Binding(
                            get: { vm.player.isMuted ? 0 : vm.player.volume },
                            set: { vm.player.setVolume($0) }
                        ),
                        in: 0...100
                    )
                    .frame(width: 84)
                    .tint(XirtusTheme.cyanAccent)
                }

                Spacer()

                // Chapters Menu (Jellyfin / MKV)
                if !vm.player.chapters.isEmpty {
                    Menu {
                        ForEach(vm.player.chapters) { ch in
                            Button("\(ch.title) (\(Self.formatSeconds(ch.timeSec)))") {
                                vm.player.seekAbsolute(ch.timeSec)
                            }
                        }
                    } label: {
                        osdMenuPill(icon: "list.bullet.indent", label: "Chapters")
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }

                // Audio Track Switcher (`aid`)
                if !vm.player.audioTracks.isEmpty {
                    Menu {
                        ForEach(vm.player.audioTracks) { track in
                            Button {
                                vm.player.selectAudioTrack(track.id)
                            } label: {
                                HStack {
                                    if track.id == vm.player.selectedAudioId {
                                        Image(systemName: "checkmark")
                                    }
                                    Text(track.displayLabel)
                                }
                            }
                        }
                    } label: {
                        osdMenuPill(icon: "waveform", label: "Audio")
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }

                // Subtitle Switcher (`sid` + External `sub-add`)
                Menu {
                    Button {
                        vm.player.selectSubtitleTrack(0)
                    } label: {
                        HStack {
                            if vm.player.selectedSubtitleId == 0 {
                                Image(systemName: "checkmark")
                            }
                            Text("Subtitles Off")
                        }
                    }
                    Divider()
                    ForEach(vm.player.subtitleTracks) { sub in
                        Button {
                            vm.player.selectSubtitleTrack(sub.id)
                        } label: {
                            HStack {
                                if sub.id == vm.player.selectedSubtitleId {
                                    Image(systemName: "checkmark")
                                }
                                Text(sub.displayLabel)
                            }
                        }
                    }
                    Divider()
                    Button("Load External Subtitle File / URL...") {
                        showAddSubtitlePopover = true
                    }
                } label: {
                    osdMenuPill(icon: "captions.bubble", label: "Subtitles")
                }
                .menuStyle(.borderlessButton)
                .fixedSize()
                .popover(isPresented: $showAddSubtitlePopover) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Load External Subtitle (.srt / .ass / URL)")
                            .font(XirtusTheme.font(13, weight: .semibold))
                        TextField("https://... or /path/to/sub.srt", text: $customSubtitleUrl)
                            .textFieldStyle(.roundedBorder)
                            .frame(width: 280)
                        HStack {
                            Button("Choose Local File...") {
                                let panel = NSOpenPanel()
                                panel.canChooseFiles = true
                                panel.allowsMultipleSelection = false
                                if panel.runModal() == .OK, let path = panel.url?.path {
                                    vm.player.addExternalSubtitle(url: path, label: "Local Sub", select: true)
                                    showAddSubtitlePopover = false
                                }
                            }
                            Spacer()
                            Button("Load") {
                                let clean = customSubtitleUrl.trimmingCharacters(in: .whitespacesAndNewlines)
                                if !clean.isEmpty {
                                    vm.player.addExternalSubtitle(url: clean, label: "Custom Sub", select: true)
                                    customSubtitleUrl = ""
                                    showAddSubtitlePopover = false
                                }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(14)
                }

                // Video Quality Switcher (`vid`)
                if vm.player.videoTracks.count > 1 {
                    Menu {
                        Button("Auto Best Quality") {
                            vm.player.selectVideoTrack(0)
                        }
                        Divider()
                        ForEach(vm.player.videoTracks) { vtrack in
                            Button {
                                vm.player.selectVideoTrack(vtrack.id)
                            } label: {
                                HStack {
                                    if vtrack.id == vm.player.selectedVideoId {
                                        Image(systemName: "checkmark")
                                    }
                                    Text(vtrack.displayLabel)
                                }
                            }
                        }
                    } label: {
                        osdMenuPill(icon: "sparkles.tv", label: "Quality")
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                }

                // Fullscreen (`F`)
                Button {
                    vm.player.toggleWindowFullscreen()
                } label: {
                    osdMenuPill(icon: "arrow.up.left.and.arrow.down.right", label: "Fullscreen")
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 20)
        .padding(.bottom, 20)
        .background(
            LinearGradient(
                colors: [Color.clear, Color.black.opacity(0.88)],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }

    private var timelineScrubber: some View {
        let dur = max(1.0, vm.player.durationSec)
        let activeDragOrKey = isScrubbing || vm.player.isKeyScrubbing
        let current = isScrubbing ? scrubPreviewSec : (vm.player.isKeyScrubbing ? vm.player.keyScrubTargetSec : vm.player.positionSec)
        let ratio = min(1.0, max(0.0, current / dur))
        let trackH: CGFloat = (activeDragOrKey || isHoveringTimeline) ? 10 : 6
        let thumbD: CGFloat = (activeDragOrKey || isHoveringTimeline) ? 16 : 13

        let previewActive = activeDragOrKey || isHoveringTimeline
        let previewSec = isScrubbing ? scrubPreviewSec : (vm.player.isKeyScrubbing ? vm.player.keyScrubTargetSec : hoverPreviewSec)
        let originSec = isScrubbing ? scrubOriginSec : (vm.player.isKeyScrubbing ? vm.player.keyScrubOriginSec : vm.player.positionSec)
        let deltaSec = previewSec - originSec
        let matchedChapter = vm.player.chapters.last(where: { previewSec >= $0.timeSec })?.title

        return VStack(alignment: .leading, spacing: 6) {
            if previewActive && vm.player.durationSec > 0 {
                HStack(spacing: 8) {
                    Text("\(deltaSec >= 0 ? "▶▶" : "◀◀") \(Self.formatSignedDelta(deltaSec))")
                        .font(XirtusTheme.mono(11.5, weight: .bold))
                        .foregroundStyle(XirtusTheme.cyanAccent)
                    Text("· \(Self.formatSeconds(previewSec)) / \(Self.formatSeconds(dur))")
                        .font(XirtusTheme.mono(11.5, weight: .medium))
                        .foregroundStyle(XirtusTheme.textPrimary)
                    if let ch = matchedChapter, !ch.isEmpty {
                        Text("· \(ch)")
                            .font(XirtusTheme.font(11.5, weight: .semibold))
                            .foregroundStyle(XirtusTheme.amber)
                            .lineLimit(1)
                    }
                    if vm.player.isKeyScrubbing {
                        Text("[Enter Jump · Esc Cancel]")
                            .font(XirtusTheme.mono(10.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 5)
                .background(XirtusTheme.surfaceSlate.opacity(0.95))
                .clipShape(Capsule())
                .overlay(Capsule().strokeBorder(XirtusTheme.cyanAccent.opacity(0.6), lineWidth: 1))
            }

            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(Color.white.opacity(0.20))
                        .frame(height: trackH)

                    // SponsorBlock & Jellyfin Intro/Outro markers
                    ForEach(session.skipSegments) { seg in
                        let startFrac = min(1.0, Double(seg.startMs) / (dur * 1000.0))
                        let endFrac = min(1.0, Double(seg.endMs) / (dur * 1000.0))
                        let segWidth = max(3.0, geo.size.width * max(0, endFrac - startFrac))
                        Rectangle()
                            .fill(seg.kind == .intro ? XirtusTheme.amber : XirtusTheme.emerald)
                            .frame(width: segWidth, height: trackH)
                            .offset(x: geo.size.width * startFrac)
                    }

                    Capsule()
                        .fill(XirtusTheme.accentGradient)
                        .frame(width: geo.size.width * ratio, height: trackH)

                    Circle()
                        .fill(activeDragOrKey ? XirtusTheme.cyanAccent : Color.white)
                        .frame(width: thumbD, height: thumbD)
                        .shadow(color: XirtusTheme.cyanAccent.opacity(activeDragOrKey ? 0.55 : 0.0), radius: 6)
                        .shadow(radius: 3)
                        .offset(x: max(0, geo.size.width * ratio - thumbD / 2))
                }
                .frame(height: 32)
                .contentShape(Rectangle())
                .onContinuousHover { phase in
                    switch phase {
                    case .active(let loc):
                        isHoveringTimeline = true
                        let frac = min(1.0, max(0.0, loc.x / max(1, geo.size.width)))
                        hoverPreviewSec = frac * dur
                    case .ended:
                        isHoveringTimeline = false
                    }
                }
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { val in
                            bumpOSDVisibility()
                            if !isScrubbing {
                                isScrubbing = true
                                scrubOriginSec = vm.player.positionSec
                            }
                            let frac = min(1.0, max(0.0, val.location.x / max(1, geo.size.width)))
                            scrubPreviewSec = frac * dur
                        }
                        .onEnded { val in
                            let frac = min(1.0, max(0.0, val.location.x / max(1, geo.size.width)))
                            let target = frac * dur
                            isScrubbing = false
                            vm.player.seekAbsolute(target)
                        }
                )
            }
            .frame(height: 32)
        }
    }

    // MARK: - Smart Series Binge Card (`"KEEP WATCHING · SxxEyy"`)

    private func smartBingeCard(proposal: NextEpisodeProposal, countdown: Int) -> some View {
        GlassPanel(cornerRadius: 16, padding: 15, highlightBorder: true) {
            HStack(spacing: 14) {
                RemoteArtwork(
                    urlString: !proposal.nextEpisode.thumbnail.isEmpty ? proposal.nextEpisode.thumbnail : proposal.meta.poster
                )
                .frame(width: 112, height: 68)
                .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text("KEEP WATCHING · \(proposal.nextEpisode.seasonEpisodeCode)")
                            .font(XirtusTheme.mono(11, weight: .semibold))
                            .foregroundStyle(XirtusTheme.cyanAccent)
                        Text("in \(countdown)s")
                            .font(XirtusTheme.mono(11, weight: .semibold))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(XirtusTheme.amber)
                            .clipShape(Capsule())
                    }

                    Text(proposal.nextEpisode.title)
                        .font(XirtusTheme.font(14, weight: .bold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .lineLimit(1)

                    if !proposal.matchedReasons.isEmpty {
                        Text(proposal.matchedReasons.prefix(3).joined(separator: " · "))
                            .font(XirtusTheme.mono(10.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                            .lineLimit(1)
                    }

                    HStack(spacing: 10) {
                        Button {
                            vm.streamNextEpisodeNow()
                        } label: {
                            Text("Stream Next ▶")
                                .font(XirtusTheme.font(12.5, weight: .bold))
                                .foregroundStyle(.black)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 6)
                                .background(XirtusTheme.cyanAccent)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)

                        Button {
                            vm.dismissBingeWatchCredits()
                        } label: {
                            Text("Watch Credits")
                                .font(XirtusTheme.font(12, weight: .medium))
                                .foregroundStyle(XirtusTheme.textSecondary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(XirtusTheme.surfaceElevated)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.top, 2)
                }
            }
            .frame(maxWidth: 440)
        }
    }

    // MARK: - Movie / Finale Credits `"NEXT RECOMMENDED"` Bottom Glass Gallery

    private var creditsRecommendationsGallery: some View {
        GlassPanel(cornerRadius: 16, padding: 14, highlightBorder: true) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    HStack(spacing: 8) {
                        Image(systemName: "sparkles")
                            .foregroundStyle(XirtusTheme.cyanAccent)
                        Text("NEXT RECOMMENDED")
                            .font(XirtusTheme.mono(11.5, weight: .semibold))
                            .foregroundStyle(XirtusTheme.cyanAccent)
                        Text("Click any title to resolve best stream & play immediately")
                            .font(XirtusTheme.font(11.5))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    Spacer()
                    Button {
                        vm.dismissCreditsRecommendations = true
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 16))
                            .foregroundStyle(XirtusTheme.textSecondary)
                    }
                    .buttonStyle(.plain)
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 14) {
                        ForEach(vm.creditsRecommendations) { item in
                            PosterCardView(item: item, width: 112) {
                                vm.playRecommendedMovieImmediately(item)
                            }
                        }
                    }
                }
            }
        }
    }

    private func osdMenuPill(icon: String, label: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.system(size: 11.5, weight: .semibold))
            Text(label)
                .font(XirtusTheme.font(12, weight: .medium))
        }
        .foregroundStyle(XirtusTheme.textPrimary)
        .padding(.horizontal, 11)
        .padding(.vertical, 6)
        .background(XirtusTheme.surfaceElevated.opacity(0.9))
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(XirtusTheme.hairlineBorder, lineWidth: 1))
    }

    private func bumpOSDVisibility() {
        showOSD = true
        hideTimerTask?.cancel()
        hideTimerTask = Task { @MainActor in
            // Hide 5 s after the last input. While a scrub or popover is still active, keep checking
            // instead of giving up, so the overlay never stays stuck on screen after a scrub.
            try? await Task.sleep(for: .seconds(5))
            while !Task.isCancelled {
                if vm.player.isPlaying && !isScrubbing && !vm.player.isKeyScrubbing && !showAddSubtitlePopover {
                    withAnimation(.easeOut(duration: 0.2)) {
                        showOSD = false
                    }
                    return
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    private static func formatSignedDelta(_ deltaSec: Double) -> String {
        let sign = deltaSec >= 0 ? "+" : "−"
        let absVal = abs(deltaSec)
        let total = Int(absVal.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 {
            return String(format: "%@%d:%02d:%02d", sign, h, m, s)
        }
        return String(format: "%@%02d:%02d", sign, m, s)
    }

    private static func formatSeconds(_ sec: Double) -> String {
        guard sec.isFinite && sec > 0 else { return "0:00" }
        let total = Int(sec.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%d:%02d", m, s)
    }

    private static func compactCount(_ val: UInt64) -> String {
        if val >= 1_000_000 {
            return String(format: "%.1fM", Double(val) / 1_000_000.0)
        }
        if val >= 1_000 {
            return String(format: "%.1fK", Double(val) / 1_000.0)
        }
        return "\(val)"
    }
}
