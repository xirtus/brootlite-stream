import Foundation
import SwiftUI

/// Mirrors `StreamCandidate::telemetry_line` in brootlite-core: seeds, size, resolution, codecs and
/// the Torznab provider on one line that the stream rows never truncate.
extension StreamCandidate {
    private var telemetryHaystack: String {
        "\(title) \(subtitle) \(filename) \(bingeGroup)".lowercased()
    }

    public var codecTags: [String] {
        let s = telemetryHaystack
        var out: [String] = []
        if s.contains("av1") || s.contains("av01") { out.append("AV1") }
        if s.contains("hevc") || s.contains("h265") || s.contains("h.265") || s.contains("x265") {
            out.append("HEVC/H.265")
        } else if s.contains("h264") || s.contains("h.264") || s.contains("x264") || s.contains("avc") {
            out.append("H.264")
        } else if s.contains("vp9") {
            out.append("VP9")
        }
        if s.contains("10bit") || s.contains("10-bit") || s.contains("hi10p") { out.append("10-bit") }
        if s.contains("dolby vision") || s.contains(".dv.") || s.contains("dovi") {
            out.append("DV")
        } else if s.contains("hdr10+") {
            out.append("HDR10+")
        } else if s.contains("hdr") {
            out.append("HDR")
        }
        return out
    }

    public var hasPreferredModernCodec: Bool {
        codecTags.contains("AV1") || codecTags.contains("HEVC/H.265")
    }

    /// `⚙️ Torznab` -> "Torznab"
    public var extractedIndexer: String? {
        let combined = "\(title)\n\(subtitle)"
        for marker in ["⚙️", "⚙"] {
            guard let r = combined.range(of: marker) else { continue }
            let after = combined[r.upperBound...].drop(while: { $0 == " " || $0 == "\u{FE0F}" })
            let stop: Set<Character> = ["\n", "•", "|", "👤", "💾"]
            let name = String(after.prefix(while: { !stop.contains($0) })).trimmingCharacters(in: .whitespaces)
            if !name.isEmpty && name.caseInsensitiveCompare(addonName) != .orderedSame { return name }
        }
        return nil
    }

    public var extractedSizeLabel: String? {
        if let sz = formattedSize { return sz }
        let combined = "\(title) \(subtitle)"
        guard let r = combined.range(of: "💾") else { return nil }
        let tokens = combined[r.upperBound...].split(separator: " ", omittingEmptySubsequences: true).prefix(2)
        guard tokens.count == 2 else { return nil }
        let unit = tokens.last!.filter(\.isLetter).uppercased()
        return ["GB", "GIB", "MB", "MIB", "KB", "KIB"].contains(unit) ? "\(tokens.first!) \(unit)" : nil
    }

    public var telemetryLine: String {
        var parts: [String] = []
        if isP2P {
            parts.append(seeders == 1 ? "▲ 1 seed" : (seeders == 0 ? "▽ 0 seeds" : "▲ \(seeders) seeds"))
        } else if isDirectHTTP {
            parts.append("⚡ direct")
        }
        if let sz = extractedSizeLabel { parts.append("💾 \(sz)") }
        switch resolutionScore {
        case 2160...: parts.append("4K")
        case 1440..<2160: parts.append("1440p")
        case 1080..<1440: parts.append("1080p")
        case 720..<1080: parts.append("720p")
        case 480..<720: parts.append("480p")
        default: break
        }
        parts.append(contentsOf: codecTags)
        if let idx = extractedIndexer { parts.append("⚙ \(idx)") }
        return parts.joined(separator: " · ")
    }

    @MainActor public var telemetryColor: Color {
        if isP2P && seeders == 0 { return XirtusTheme.textMuted }
        if hasPreferredModernCodec { return XirtusTheme.cyanAccent }
        return XirtusTheme.emerald
    }
}
