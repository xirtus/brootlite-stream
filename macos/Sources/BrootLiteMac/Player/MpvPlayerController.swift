import AVFoundation
import AVKit
import AppKit
import CBrootLiteBridge
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Player Track & Chapter Models
// ─────────────────────────────────────────────────────────────────────────────

public struct MediaTrackOption: Identifiable, Hashable, Sendable {
    public let id: Int64
    public let trackType: String // "audio", "sub", "video"
    public let title: String
    public let lang: String
    public let codec: String
    public let width: Int
    public let height: Int
    public let isExternal: Bool
    public let selected: Bool

    public var displayLabel: String {
        var parts: [String] = []
        if !title.isEmpty {
            parts.append(title)
        }
        if !lang.isEmpty && !title.localizedCaseInsensitiveContains(lang) {
            parts.append(lang.uppercased())
        }
        if trackType == "video" && height > 0 {
            parts.append("\(height)p")
        }
        if !codec.isEmpty {
            parts.append(codec.uppercased())
        }
        if isExternal {
            parts.append("EXT")
        }
        if parts.isEmpty {
            return "Track #\(id)"
        }
        return parts.joined(separator: " · ")
    }
}

public struct PlayerChapter: Identifiable, Hashable, Sendable {
    public let index: Int
    public let title: String
    public let timeSec: Double

    public var id: Int { index }
}

private struct RawMpvTrack: Decodable {
    let id: Int64?
    let type: String?
    let title: String?
    let lang: String?
    let codec: String?
    let external: Bool?
    let selected: Bool?
    let `demux-w`: Int?
    let `demux-h`: Int?
}

private struct RawMpvChapter: Decodable {
    let title: String?
    let time: Double?
}

// ─────────────────────────────────────────────────────────────────────────────
// Thread-Safe Callback Bridge for `libmpv` Event Wakeup
// ─────────────────────────────────────────────────────────────────────────────

private final class MpvWakeupBridge: @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable () -> Void)?

    init(handler: @escaping @Sendable () -> Void) {
        self.handler = handler
    }

    func invalidate() {
        lock.lock()
        handler = nil
        lock.unlock()
    }

    func fire() {
        lock.lock()
        let cb = handler
        lock.unlock()
        cb?()
    }
}

private func mpvWakeupTrampoline(_ userdata: UnsafeMutableRawPointer?) {
    guard let userdata else { return }
    let bridge = Unmanaged<MpvWakeupBridge>.fromOpaque(userdata).takeUnretainedValue()
    bridge.fire()
}

// ─────────────────────────────────────────────────────────────────────────────
// SwiftUI Video Surface (`libmpv` OpenGL + Automatic `AVPlayer` Fallback)
// ─────────────────────────────────────────────────────────────────────────────

public struct HardwareVideoSurface: NSViewRepresentable {
    public let controller: MpvPlayerController

    public init(controller: MpvPlayerController) {
        self.controller = controller
    }

    public func makeNSView(context: Context) -> NSView {
        if controller.usingLibmpv,
           let handle = controller.rawMpvHandle,
           let rawViewPtr = bl_mpv_create_gl_host_view(handle) {
            return Unmanaged<NSView>.fromOpaque(rawViewPtr).takeRetainedValue()
        } else {
            let avView = AVPlayerView()
            avView.controlsStyle = .none
            avView.videoGravity = .resizeAspect
            avView.player = controller.fallbackAVPlayer
            return avView
        }
    }

    public func updateNSView(_ nsView: NSView, context: Context) {
        if let avView = nsView as? AVPlayerView {
            if avView.player !== controller.fallbackAVPlayer {
                avView.player = controller.fallbackAVPlayer
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// `MpvPlayerController` — Unified Cinema Playback Engine
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
@Observable
public final class MpvPlayerController {
    public private(set) var usingLibmpv: Bool = false
    public private(set) var backendBadge: String = "libmpv · VideoToolbox"
    public private(set) var hwdecCurrent: String = "videotoolbox"

    public private(set) var isPlaying: Bool = false
    public private(set) var isBuffering: Bool = false
    public private(set) var eofReached: Bool = false
    public private(set) var positionSec: Double = 0
    public private(set) var durationSec: Double = 0
    public private(set) var volume: Double = 100
    public private(set) var isMuted: Bool = false
    public private(set) var playbackError: String?

    public private(set) var audioTracks: [MediaTrackOption] = []
    public private(set) var subtitleTracks: [MediaTrackOption] = []
    public private(set) var videoTracks: [MediaTrackOption] = []
    public private(set) var chapters: [PlayerChapter] = []

    public private(set) var selectedAudioId: Int64 = 0
    public private(set) var selectedSubtitleId: Int64 = 0
    public private(set) var selectedVideoId: Int64 = 0

    // Keyboard Scrubbing State Machine & Post-Seek Settle Guard
    public private(set) var isKeyScrubbing: Bool = false
    public private(set) var keyScrubTargetSec: Double = 0
    public private(set) var keyScrubOriginSec: Double = 0

    @ObservationIgnored
    public private(set) var rawMpvHandle: OpaquePointer?
    @ObservationIgnored
    public let fallbackAVPlayer = AVPlayer()

    @ObservationIgnored
    private var wakeupBridge: Unmanaged<MpvWakeupBridge>?
    @ObservationIgnored
    private var avTimeObserver: Any?
    @ObservationIgnored
    private var pendingStartSeekSec: Double = 0
    @ObservationIgnored
    private var pendingExternalSubtitles: [ExternalSubtitle] = []
    @ObservationIgnored
    private var keyScrubRepeatCount: Int = 0
    @ObservationIgnored
    private var lastKeyScrubDate: Date = .distantPast
    @ObservationIgnored
    private var keyScrubCommitTask: Task<Void, Never>?
    @ObservationIgnored
    private var pendingSeekTargetSec: Double?
    @ObservationIgnored
    private var pendingSeekUntil: Date = .distantPast

    public init() {
        setupBackend()
    }

    private func setupBackend() {
        if bl_mpv_is_available(), let handle = bl_mpv_create_initialized() {
            self.rawMpvHandle = handle
            self.usingLibmpv = true
            self.backendBadge = "libmpv · VideoToolbox"

            let bridge = MpvWakeupBridge { [weak self] in
                DispatchQueue.main.async {
                    self?.drainMpvEvents()
                }
            }
            let retained = Unmanaged.passRetained(bridge)
            self.wakeupBridge = retained
            bl_mpv_set_wakeup_callback(handle, mpvWakeupTrampoline, retained.toOpaque())
        } else {
            self.usingLibmpv = false
            self.backendBadge = "AVPlayer · VideoToolbox"
            setupAVPlayerObservers()
        }
    }

    private func setupAVPlayerObservers() {
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        avTimeObserver = fallbackAVPlayer.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self, !self.usingLibmpv else { return }
                let sec = CMTimeGetSeconds(time)
                if sec.isFinite && sec >= 0 {
                    if let target = self.pendingSeekTargetSec {
                        if Date() >= self.pendingSeekUntil || abs(sec - target) <= 2.5 {
                            self.pendingSeekTargetSec = nil
                            self.positionSec = sec
                        }
                    } else if !self.isKeyScrubbing {
                        self.positionSec = sec
                    }
                }
                if let item = self.fallbackAVPlayer.currentItem {
                    let dur = CMTimeGetSeconds(item.duration)
                    if dur.isFinite && dur > 0 {
                        self.durationSec = dur
                    }
                    self.isPlaying = self.fallbackAVPlayer.timeControlStatus == .playing
                    self.isBuffering = self.fallbackAVPlayer.timeControlStatus == .waitingToPlayAtSpecifiedRate
                    if dur > 1.0 && sec >= dur - 0.4 && self.pendingSeekTargetSec == nil {
                        self.eofReached = true
                    }
                }
            }
        }
    }

    // MARK: - Load & Control Media

    public func loadMedia(
        url: String,
        headers: [String: String] = [:],
        startPositionSec: Double = 0,
        externalSubtitles: [ExternalSubtitle] = []
    ) {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        isKeyScrubbing = false
        pendingSeekTargetSec = nil
        playbackError = nil
        eofReached = false
        isBuffering = true
        positionSec = max(0, startPositionSec)
        durationSec = 0
        audioTracks = []
        subtitleTracks = []
        videoTracks = []
        chapters = []
        pendingStartSeekSec = max(0, startPositionSec)
        pendingExternalSubtitles = externalSubtitles

        if usingLibmpv, let mpv = rawMpvHandle {
            // Configure HTTP headers safely via `change-list` so values containing commas (e.g. Jellyfin MediaBrowser auth) stay intact!
            _ = bl_mpv_command_4(mpv, "change-list", "http-header-fields", "clr", "")
            var customUA: String?
            for (key, value) in headers {
                if key.caseInsensitiveCompare("User-Agent") == .orderedSame {
                    customUA = value
                } else {
                    _ = bl_mpv_command_4(
                        mpv,
                        "change-list",
                        "http-header-fields",
                        "append",
                        "\(key): \(value)"
                    )
                }
            }
            if let ua = customUA, !ua.isEmpty {
                _ = bl_mpv_set_property_string(mpv, "user-agent", ua)
            } else {
                _ = bl_mpv_set_property_string(mpv, "user-agent", "BrootLite/0.2.0 (macOS; libmpv)")
            }

            if startPositionSec > 1.5 {
                _ = bl_mpv_set_property_string(mpv, "start", String(format: "%.1f", startPositionSec))
            } else {
                _ = bl_mpv_set_property_string(mpv, "start", "none")
            }

            _ = bl_mpv_set_property_flag(mpv, "pause", 0)
            _ = bl_mpv_command_3(mpv, "loadfile", url, "replace")
            isPlaying = true
        } else {
            guard let parsedURL = URL(string: url) else {
                playbackError = "Invalid playback URL"
                isBuffering = false
                return
            }
            var assetOpts: [String: Any] = [:]
            if !headers.isEmpty {
                assetOpts["AVURLAssetHTTPHeaderFieldsKey"] = headers
            }
            let asset = AVURLAsset(url: parsedURL, options: assetOpts)
            let item = AVPlayerItem(asset: asset)
            fallbackAVPlayer.replaceCurrentItem(with: item)
            if startPositionSec > 1.5 {
                let target = CMTime(seconds: startPositionSec, preferredTimescale: 600)
                fallbackAVPlayer.seek(to: target)
            }
            fallbackAVPlayer.play()
            isPlaying = true
        }
    }

    public func togglePlayPause() {
        if usingLibmpv, let mpv = rawMpvHandle {
            let nextPause: Int32 = isPlaying ? 1 : 0
            if eofReached && nextPause == 0 {
                seekAbsolute(0)
                eofReached = false
            }
            _ = bl_mpv_set_property_flag(mpv, "pause", nextPause)
            isPlaying = (nextPause == 0)
        } else {
            if isPlaying {
                fallbackAVPlayer.pause()
                isPlaying = false
            } else {
                if eofReached {
                    fallbackAVPlayer.seek(to: .zero)
                    eofReached = false
                }
                fallbackAVPlayer.play()
                isPlaying = true
            }
        }
    }

    public func setPaused(_ paused: Bool) {
        if usingLibmpv, let mpv = rawMpvHandle {
            _ = bl_mpv_set_property_flag(mpv, "pause", paused ? 1 : 0)
            isPlaying = !paused
        } else {
            if paused {
                fallbackAVPlayer.pause()
            } else {
                fallbackAVPlayer.play()
            }
            isPlaying = !paused
        }
    }

    public func seekRelative(_ deltaSec: Double) {
        let base = isKeyScrubbing ? keyScrubTargetSec : positionSec
        let target = max(0, base + deltaSec)
        seekAbsolute(target)
    }

    public func seekAbsolute(_ seconds: Double) {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        isKeyScrubbing = false
        keyScrubRepeatCount = 0

        let clamped = durationSec > 0 ? min(durationSec, max(0, seconds)) : max(0, seconds)
        positionSec = clamped
        pendingSeekTargetSec = clamped
        pendingSeekUntil = Date().addingTimeInterval(2.2)

        if eofReached && (durationSec == 0 || clamped < durationSec - 1.0) {
            eofReached = false
        }
        if usingLibmpv, let mpv = rawMpvHandle {
            _ = bl_mpv_command_3(mpv, "seek", String(format: "%.2f", clamped), "absolute")
        } else {
            fallbackAVPlayer.seek(to: CMTime(seconds: clamped, preferredTimescale: 600))
        }
    }

    public func stepKeyboardScrub(direction: Int, isRepeat: Bool) {
        let now = Date()
        if isRepeat || now.timeIntervalSince(lastKeyScrubDate) <= 0.65 {
            keyScrubRepeatCount += 1
        } else {
            keyScrubRepeatCount = 0
        }
        lastKeyScrubDate = now

        let dur = durationSec
        let baseStep: Double = switch keyScrubRepeatCount {
        case 0...1: (dur > 0 && dur <= 90) ? 5.0 : 10.0
        case 2...5: 30.0
        case 6...12: 60.0
        case 13...24: 120.0
        default: 300.0
        }
        let step = (dur > 20 ? min(baseStep, max(5.0, dur / 6.0)) : baseStep) * Double(direction >= 0 ? 1 : -1)

        if !isKeyScrubbing {
            isKeyScrubbing = true
            keyScrubOriginSec = positionSec
            keyScrubTargetSec = positionSec
        }
        let rawNext = keyScrubTargetSec + step
        keyScrubTargetSec = dur > 0 ? min(dur, max(0, rawNext)) : max(0, rawNext)

        let delayMs = isRepeat ? 550 : 350
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(delayMs))
            guard let self, !Task.isCancelled, self.isKeyScrubbing else { return }
            self.commitKeyboardScrubNow()
        }
    }

    public func commitKeyboardScrubNow() {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        guard isKeyScrubbing else { return }
        let target = keyScrubTargetSec
        isKeyScrubbing = false
        keyScrubRepeatCount = 0
        seekAbsolute(target)
    }

    public func cancelKeyboardScrub() {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        isKeyScrubbing = false
        keyScrubRepeatCount = 0
    }

    public func setVolume(_ newVolume: Double) {
        let clamped = min(130, max(0, newVolume))
        volume = clamped
        if usingLibmpv, let mpv = rawMpvHandle {
            _ = bl_mpv_set_property_double(mpv, "volume", clamped)
        } else {
            fallbackAVPlayer.volume = Float(min(1.0, clamped / 100.0))
        }
    }

    public func toggleMute() {
        isMuted.toggle()
        if usingLibmpv, let mpv = rawMpvHandle {
            _ = bl_mpv_set_property_flag(mpv, "mute", isMuted ? 1 : 0)
        } else {
            fallbackAVPlayer.isMuted = isMuted
        }
    }

    public func selectAudioTrack(_ trackId: Int64) {
        selectedAudioId = trackId
        guard usingLibmpv, let mpv = rawMpvHandle else { return }
        if trackId <= 0 {
            _ = bl_mpv_set_property_string(mpv, "aid", "no")
        } else {
            _ = bl_mpv_set_property_int64(mpv, "aid", trackId)
        }
        refreshTrackList()
    }

    public func selectSubtitleTrack(_ trackId: Int64) {
        selectedSubtitleId = trackId
        guard usingLibmpv, let mpv = rawMpvHandle else { return }
        if trackId <= 0 {
            _ = bl_mpv_set_property_string(mpv, "sid", "no")
        } else {
            _ = bl_mpv_set_property_int64(mpv, "sid", trackId)
        }
        refreshTrackList()
    }

    public func selectVideoTrack(_ trackId: Int64) {
        selectedVideoId = trackId
        guard usingLibmpv, let mpv = rawMpvHandle else { return }
        if trackId <= 0 {
            _ = bl_mpv_set_property_string(mpv, "vid", "auto")
        } else {
            _ = bl_mpv_set_property_int64(mpv, "vid", trackId)
        }
        refreshTrackList()
    }

    public func addExternalSubtitle(url: String, label: String, select: Bool = false) {
        guard usingLibmpv, let mpv = rawMpvHandle, !url.isEmpty else { return }
        let mode = select ? "select" : "auto"
        _ = bl_mpv_command_4(mpv, "sub-add", url, mode, label)
    }

    public func stop() {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        isKeyScrubbing = false
        pendingSeekTargetSec = nil
        if usingLibmpv, let mpv = rawMpvHandle {
            _ = bl_mpv_command_1(mpv, "stop")
        } else {
            fallbackAVPlayer.pause()
            fallbackAVPlayer.replaceCurrentItem(with: nil)
        }
        isPlaying = false
        isBuffering = false
        eofReached = false
    }

    public func toggleWindowFullscreen() {
        if let win = NSApplication.shared.keyWindow ?? NSApplication.shared.windows.first {
            win.toggleFullScreen(nil)
        }
    }

    public func shutdown() {
        keyScrubCommitTask?.cancel()
        keyScrubCommitTask = nil
        if let obs = avTimeObserver {
            fallbackAVPlayer.removeTimeObserver(obs)
            avTimeObserver = nil
        }
        if let bridge = wakeupBridge {
            bridge.takeUnretainedValue().invalidate()
        }
        if let mpv = rawMpvHandle {
            bl_mpv_set_wakeup_callback(mpv, nil, nil)
            bl_mpv_terminate(mpv)
            rawMpvHandle = nil
        }
        if let bridge = wakeupBridge {
            bridge.release()
            wakeupBridge = nil
        }
    }

    // MARK: - `libmpv` Event Loop & Property Sync

    private func drainMpvEvents() {
        guard let mpv = rawMpvHandle else { return }
        var ev = bl_mpv_polled_event()
        var iterations = 0
        while iterations < 64 && bl_mpv_poll_event(mpv, &ev) {
            iterations += 1
            switch Int(ev.event_id) {
            case BL_MPV_EVENT_FILE_LOADED:
                isBuffering = false
                if pendingStartSeekSec > 1.5 {
                    _ = bl_mpv_set_property_string(mpv, "start", "none")
                    pendingStartSeekSec = 0
                }
                for (idx, sub) in pendingExternalSubtitles.prefix(12).enumerated() {
                    let label = sub.lang.isEmpty ? sub.addonName : "\(sub.lang.uppercased()) (\(sub.addonName))"
                    addExternalSubtitle(url: sub.url, label: label, select: idx == 0 && subtitleTracks.isEmpty)
                }
                pendingExternalSubtitles.removeAll()
                syncPlaybackProperties()
                refreshTrackList()
                refreshChapters()

            case BL_MPV_EVENT_PROPERTY_CHANGE:
                let propName = withUnsafePointer(to: &ev.prop_name) {
                    $0.withMemoryRebound(to: CChar.self, capacity: 64) { ptr in
                        String(cString: ptr)
                    }
                }
                handlePropertyChange(propName)

            case BL_MPV_EVENT_END_FILE:
                if Int(ev.end_reason) == BL_MPV_END_REASON_EOF {
                    eofReached = true
                    isPlaying = false
                } else if Int(ev.end_reason) == BL_MPV_END_REASON_ERROR {
                    playbackError = "Playback stream failed to open (mpv code \(ev.end_error))."
                    isBuffering = false
                    isPlaying = false
                }

            case BL_MPV_EVENT_PLAYBACK_RESTART:
                isBuffering = false
                var restartPos: Double = 0
                if bl_mpv_get_property_double(mpv, "time-pos", &restartPos) >= 0, restartPos.isFinite {
                    if let target = pendingSeekTargetSec, abs(restartPos - target) <= 3.5 {
                        pendingSeekTargetSec = nil
                    }
                }
                syncPlaybackProperties()

            default:
                break
            }
        }
    }

    private func handlePropertyChange(_ name: String) {
        guard let mpv = rawMpvHandle else { return }
        switch name {
        case "time-pos":
            var t: Double = 0
            if bl_mpv_get_property_double(mpv, "time-pos", &t) >= 0, t.isFinite {
                let clean = max(0, t)
                if let target = pendingSeekTargetSec {
                    if Date() >= pendingSeekUntil || abs(clean - target) <= 2.5 {
                        pendingSeekTargetSec = nil
                        if !isKeyScrubbing {
                            positionSec = clean
                        }
                    }
                } else if !isKeyScrubbing {
                    positionSec = clean
                }
            }
        case "duration":
            var d: Double = 0
            if bl_mpv_get_property_double(mpv, "duration", &d) >= 0, d.isFinite, d > 0 {
                durationSec = d
            }
        case "pause":
            var flag: Int32 = 0
            if bl_mpv_get_property_flag(mpv, "pause", &flag) >= 0 {
                isPlaying = (flag == 0)
            }
        case "eof-reached":
            var eof: Int32 = 0
            if bl_mpv_get_property_flag(mpv, "eof-reached", &eof) >= 0 {
                eofReached = (eof != 0)
                if eofReached {
                    isPlaying = false
                }
            }
        case "paused-for-cache":
            var buffering: Int32 = 0
            if bl_mpv_get_property_flag(mpv, "paused-for-cache", &buffering) >= 0 {
                isBuffering = (buffering != 0)
            }
        case "hwdec-current":
            if let cStr = bl_mpv_copy_property_string(mpv, "hwdec-current") {
                let val = String(cString: cStr)
                free(cStr)
                if !val.isEmpty && val != "no" {
                    hwdecCurrent = val
                    backendBadge = "libmpv · \(val)"
                }
            }
        case "track-list":
            refreshTrackList()
        case "chapter-list":
            refreshChapters()
        default:
            break
        }
    }

    private func syncPlaybackProperties() {
        handlePropertyChange("time-pos")
        handlePropertyChange("duration")
        handlePropertyChange("pause")
        handlePropertyChange("eof-reached")
        handlePropertyChange("hwdec-current")
    }

    public func refreshTrackList() {
        guard let mpv = rawMpvHandle,
              let cStr = bl_mpv_copy_property_string(mpv, "track-list") else {
            return
        }
        let jsonStr = String(cString: cStr)
        free(cStr)

        guard let data = jsonStr.data(using: .utf8),
              let rawTracks = try? JSONDecoder().decode([RawMpvTrack].self, from: data) else {
            return
        }

        var audios: [MediaTrackOption] = []
        var subs: [MediaTrackOption] = []
        var videos: [MediaTrackOption] = []

        for raw in rawTracks {
            guard let id = raw.id, let t = raw.type else { continue }
            let opt = MediaTrackOption(
                id: id,
                trackType: t,
                title: raw.title ?? "",
                lang: raw.lang ?? "",
                codec: raw.codec ?? "",
                width: raw.`demux-w` ?? 0,
                height: raw.`demux-h` ?? 0,
                isExternal: raw.external ?? false,
                selected: raw.selected ?? false
            )
            switch t {
            case "audio":
                audios.append(opt)
                if opt.selected { selectedAudioId = opt.id }
            case "sub":
                subs.append(opt)
                if opt.selected { selectedSubtitleId = opt.id }
            case "video":
                videos.append(opt)
                if opt.selected { selectedVideoId = opt.id }
            default:
                break
            }
        }
        if !subs.contains(where: \.selected) {
            selectedSubtitleId = 0
        }
        audioTracks = audios
        subtitleTracks = subs
        videoTracks = videos
    }

    public func refreshChapters() {
        guard let mpv = rawMpvHandle,
              let cStr = bl_mpv_copy_property_string(mpv, "chapter-list") else {
            return
        }
        let jsonStr = String(cString: cStr)
        free(cStr)

        guard let data = jsonStr.data(using: .utf8),
              let rawChapters = try? JSONDecoder().decode([RawMpvChapter].self, from: data) else {
            return
        }
        chapters = rawChapters.enumerated().map { idx, ch in
            let title = (ch.title?.isEmpty == false) ? ch.title! : "Chapter \(idx + 1)"
            return PlayerChapter(index: idx, title: title, timeSec: ch.time ?? 0)
        }
    }
}
