import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreText
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Xirtus Dark-Glass Palette & Geist Typography
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public enum XirtusTheme {
    // Deep Obsidian `#090B10`
    public static let bgObsidian = Color(red: 0x09 / 255.0, green: 0x0B / 255.0, blue: 0x10 / 255.0)
    // Frosted Slate `#121722`
    public static let surfaceSlate = Color(red: 0x12 / 255.0, green: 0x17 / 255.0, blue: 0x22 / 255.0)
    // Elevated Glass `#192030`
    public static let surfaceElevated = Color(red: 0x19 / 255.0, green: 0x20 / 255.0, blue: 0x30 / 255.0)
    // Subtle `1px` `#24FFFFFF` hairline border (~14% white)
    public static let hairlineBorder = Color.white.opacity(0.141)
    public static let subtleBorder = Color.white.opacity(0.08)

    // Accents
    public static let cyanAccent = Color(red: 0x38 / 255.0, green: 0xBD / 255.0, blue: 0xF8 / 255.0)   // #38BDF8
    public static let indigoAccent = Color(red: 0x81 / 255.0, green: 0x8C / 255.0, blue: 0xF8 / 255.0) // #818CF8
    public static let emerald = Color(red: 0x34 / 255.0, green: 0xD3 / 255.0, blue: 0x99 / 255.0)
    public static let amber = Color(red: 0xFB / 255.0, green: 0xBF / 255.0, blue: 0x24 / 255.0)
    public static let rose = Color(red: 0xFB / 255.0, green: 0x71 / 255.0, blue: 0x85 / 255.0)

    // Text hierarchy
    public static let textPrimary = Color(red: 0xF8 / 255.0, green: 0xFA / 255.0, blue: 0xFC / 255.0)
    public static let textSecondary = Color(red: 0x94 / 255.0, green: 0xA3 / 255.0, blue: 0xB8 / 255.0)
    public static let textMuted = Color(red: 0x64 / 255.0, green: 0x74 / 255.0, blue: 0x8B / 255.0)

    public static let accentGradient = LinearGradient(
        colors: [cyanAccent, indigoAccent],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    private static var registeredSansFamily: String = "Geist"
    private static var registeredMonoFamily: String = "Geist Mono"
    private static var didRegisterFonts = false

    public static func registerBundledFonts() {
        guard !didRegisterFonts else { return }
        didRegisterFonts = true

        let fontFiles = [
            "geist_regular.ttf",
            "geist_medium.ttf",
            "geist_semibold.ttf",
            "geist_bold.ttf",
            "geist_mono_regular.ttf",
            "geist_mono_medium.ttf"
        ]

        var searchDirs: [URL] = []
        if let resURL = Bundle.main.resourceURL {
            searchDirs.append(resURL)
            searchDirs.append(resURL.appendingPathComponent("Fonts"))
        }
        let execDir = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        searchDirs.append(execDir.appendingPathComponent("../Resources"))
        let cwd = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        searchDirs.append(cwd.appendingPathComponent("app/src/main/res/font"))
        searchDirs.append(cwd.appendingPathComponent("../app/src/main/res/font"))

        for file in fontFiles {
            for dir in searchDirs {
                let candidate = dir.appendingPathComponent(file)
                if FileManager.default.fileExists(atPath: candidate.path) {
                    CTFontManagerRegisterFontsForURL(candidate as CFURL, .process, nil)
                    if let descriptors = CTFontManagerCreateFontDescriptorsFromURL(candidate as CFURL) as? [CTFontDescriptor],
                       let first = descriptors.first,
                       let family = CTFontDescriptorCopyAttribute(first, kCTFontFamilyNameAttribute) as? String {
                        if file.contains("mono") {
                            registeredMonoFamily = family
                        } else {
                            registeredSansFamily = family
                        }
                    }
                    break
                }
            }
        }
    }

    public static func font(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        if NSFont(name: registeredSansFamily, size: size) != nil {
            return .custom(registeredSansFamily, size: size).weight(weight)
        }
        return .system(size: size, weight: weight, design: .default)
    }

    public static func mono(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        if NSFont(name: registeredMonoFamily, size: size) != nil {
            return .custom(registeredMonoFamily, size: size).weight(weight)
        }
        return .system(size: size, weight: weight, design: .monospaced)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AppKit `NSVisualEffectView` Frosted Glass Backdrop
// ─────────────────────────────────────────────────────────────────────────────

public struct FrostedVisualEffectView: NSViewRepresentable {
    public var material: NSVisualEffectView.Material
    public var blendingMode: NSVisualEffectView.BlendingMode
    public var state: NSVisualEffectView.State

    public init(
        material: NSVisualEffectView.Material = .hudWindow,
        blendingMode: NSVisualEffectView.BlendingMode = .withinWindow,
        state: NSVisualEffectView.State = .active
    ) {
        self.material = material
        self.blendingMode = blendingMode
        self.state = state
    }

    public func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = state
        view.appearance = NSAppearance(named: .darkAqua)
        return view
    }

    public func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
        nsView.state = state
    }
}

public struct GlassPanel<Content: View>: View {
    public var cornerRadius: CGFloat
    public var padding: CGFloat
    public var highlightBorder: Bool
    @ViewBuilder public var content: () -> Content

    public init(
        cornerRadius: CGFloat = 14,
        padding: CGFloat = 16,
        highlightBorder: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.cornerRadius = cornerRadius
        self.padding = padding
        self.highlightBorder = highlightBorder
        self.content = content
    }

    public var body: some View {
        content()
            .padding(padding)
            .background(
                ZStack {
                    FrostedVisualEffectView(material: .hudWindow, blendingMode: .withinWindow)
                        .opacity(0.42)
                    XirtusTheme.surfaceSlate.opacity(0.86)
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(
                        highlightBorder ? XirtusTheme.cyanAccent.opacity(0.65) : XirtusTheme.hairlineBorder,
                        lineWidth: 1
                    )
            )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Crisp `CIQRCodeGenerator` QR Code View
// ─────────────────────────────────────────────────────────────────────────────

public struct QRCodeView: View {
    public let payload: String
    public var size: CGFloat = 188

    public init(payload: String, size: CGFloat = 188) {
        self.payload = payload
        self.size = size
    }

    public var body: some View {
        Group {
            if let img = Self.generateQRImage(from: payload, dimension: size * 2) {
                Image(nsImage: img)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.white)
                    .overlay(
                        Image(systemName: "qrcode")
                            .font(.system(size: 48))
                            .foregroundStyle(.black)
                    )
            }
        }
        .frame(width: size, height: size)
        .padding(12)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: XirtusTheme.cyanAccent.opacity(0.18), radius: 14, x: 0, y: 4)
    }

    private static func generateQRImage(from text: String, dimension: CGFloat) -> NSImage? {
        guard !text.isEmpty else { return nil }
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"

        guard let output = filter.outputImage else { return nil }
        let scaleX = max(1, floor(dimension / output.extent.size.width))
        let scaleY = max(1, floor(dimension / output.extent.size.height))
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scaleX, y: scaleY))

        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Fast In-Memory Cached Remote Artwork Loader
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
private enum ArtworkMemoryCache {
    static let cache: NSCache<NSString, NSImage> = {
        let c = NSCache<NSString, NSImage>()
        c.countLimit = 450
        return c
    }()
}

public struct RemoteArtwork: View {
    public let urlString: String
    public var contentMode: ContentMode = .fill
    public var placeholderIcon: String = "film"

    @State private var loadedImage: NSImage?
    @State private var failed = false

    public init(
        urlString: String,
        contentMode: ContentMode = .fill,
        placeholderIcon: String = "film"
    ) {
        self.urlString = urlString
        self.contentMode = contentMode
        self.placeholderIcon = placeholderIcon
    }

    public var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.09, green: 0.12, blue: 0.18),
                    Color(red: 0.05, green: 0.07, blue: 0.11)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            if let img = loadedImage {
                Image(nsImage: img)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                Image(systemName: failed ? "photo" : placeholderIcon)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(XirtusTheme.textMuted.opacity(0.6))
            }
        }
        .task(id: urlString) {
            await loadArtwork()
        }
    }

    private func loadArtwork() async {
        failed = false
        let trimmed = urlString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            loadedImage = nil
            return
        }
        if FileManager.default.fileExists(atPath: trimmed),
           let localImg = NSImage(contentsOfFile: trimmed) {
            loadedImage = localImg
            return
        }
        let cacheKey = trimmed as NSString
        if let cached = ArtworkMemoryCache.cache.object(forKey: cacheKey) {
            loadedImage = cached
            return
        }
        guard let url = URL(string: trimmed) else {
            failed = true
            return
        }
        do {
            var req = URLRequest(url: url)
            req.timeoutInterval = 12
            let (data, resp) = try await URLSession.shared.data(for: req)
            if let http = resp as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                failed = true
                return
            }
            if let img = NSImage(data: data) {
                ArtworkMemoryCache.cache.setObject(img, forKey: cacheKey)
                loadedImage = img
            } else {
                failed = true
            }
        } catch {
            if !Task.isCancelled {
                failed = true
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Interactive Xirtus Pill Chip, Poster Card & Wide 16:9 Card
// ─────────────────────────────────────────────────────────────────────────────

public struct XirtusChip: View {
    public let title: String
    public var icon: String? = nil
    public var isSelected: Bool = false
    public var badge: String? = nil
    public var action: () -> Void

    @State private var isHovered = false

    public var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 11, weight: .semibold))
                }
                Text(title)
                    .font(XirtusTheme.font(12.5, weight: isSelected ? .semibold : .medium))
                    .lineLimit(1)
                if let badge, !badge.isEmpty {
                    Text(badge)
                        .font(XirtusTheme.mono(10.5, weight: .medium))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color.black.opacity(0.28))
                        .clipShape(Capsule())
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6.5)
            .foregroundStyle(isSelected ? Color.black : XirtusTheme.textPrimary)
            .background(
                Group {
                    if isSelected {
                        XirtusTheme.cyanAccent
                    } else if isHovered {
                        XirtusTheme.surfaceElevated
                    } else {
                        XirtusTheme.surfaceSlate.opacity(0.9)
                    }
                }
            )
            .clipShape(Capsule())
            .overlay(
                Capsule()
                    .strokeBorder(
                        isSelected
                            ? XirtusTheme.cyanAccent
                            : (isHovered ? XirtusTheme.cyanAccent.opacity(0.55) : XirtusTheme.hairlineBorder),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
    }
}

public struct PosterCardView: View {
    public let item: MetaItem
    public var width: CGFloat = 148
    public var progressRatio: Double? = nil
    public var onSelect: () -> Void

    @State private var isHovered = false
    @FocusState private var isFocused: Bool

    public var body: some View {
        let height = round(width * 1.48)
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 7) {
                ZStack(alignment: .topTrailing) {
                    RemoteArtwork(urlString: item.poster)
                        .frame(width: width, height: height)
                        .clipped()

                    if !item.imdbRating.isEmpty {
                        HStack(spacing: 3) {
                            Image(systemName: "star.fill")
                                .font(.system(size: 9))
                                .foregroundStyle(XirtusTheme.amber)
                            Text(item.imdbRating)
                                .font(XirtusTheme.mono(10.5, weight: .medium))
                                .foregroundStyle(XirtusTheme.textPrimary)
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Color.black.opacity(0.75))
                        .clipShape(Capsule())
                        .padding(7)
                    }

                    if isHovered || isFocused {
                        ZStack {
                            Color.black.opacity(0.32)
                            Circle()
                                .fill(XirtusTheme.cyanAccent.opacity(0.92))
                                .frame(width: 40, height: 40)
                                .overlay(
                                    Image(systemName: "play.fill")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(.black)
                                        .offset(x: 1)
                                )
                        }
                        .frame(width: width, height: height)
                        .transition(.opacity)
                    }

                    if let progressRatio, progressRatio > 0.01 {
                        VStack {
                            Spacer()
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color.black.opacity(0.55))
                                    Rectangle()
                                        .fill(XirtusTheme.cyanAccent)
                                        .frame(width: geo.size.width * min(1.0, max(0.0, progressRatio)))
                                }
                            }
                            .frame(height: 4)
                        }
                        .frame(width: width, height: height)
                    }
                }
                .frame(width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .strokeBorder(
                            (isHovered || isFocused) ? XirtusTheme.cyanAccent : XirtusTheme.hairlineBorder,
                            lineWidth: (isHovered || isFocused) ? 1.6 : 1
                        )
                )
                .shadow(
                    color: (isHovered || isFocused) ? XirtusTheme.cyanAccent.opacity(0.24) : .black.opacity(0.35),
                    radius: (isHovered || isFocused) ? 10 : 5,
                    x: 0,
                    y: 4
                )
                .scaleEffect((isHovered || isFocused) ? 1.025 : 1.0)
                .animation(.easeOut(duration: 0.15), value: isHovered || isFocused)

                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name)
                        .font(XirtusTheme.font(12.5, weight: .semibold))
                        .foregroundStyle(XirtusTheme.textPrimary)
                        .lineLimit(1)

                    HStack(spacing: 5) {
                        if !item.releaseInfo.isEmpty {
                            Text(item.releaseInfo)
                                .font(XirtusTheme.mono(10.5))
                                .foregroundStyle(XirtusTheme.textSecondary)
                        }
                        Text(item.isJellyfin ? "JELLYFIN" : item.mediaType.uppercased())
                            .font(XirtusTheme.mono(9.5, weight: .medium))
                            .foregroundStyle(item.isJellyfin ? XirtusTheme.indigoAccent : XirtusTheme.textMuted)
                    }
                    .lineLimit(1)
                }
                .frame(width: width, alignment: .leading)
            }
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .onHover { isHovered = $0 }
    }
}

public struct WideMediaCardView: View {
    public let title: String
    public let subtitle: String
    public let artworkUrl: String
    public var badgeText: String = ""
    public var isLive: Bool = false
    public var progressRatio: Double = 0
    public var width: CGFloat = 256
    public var onPrimaryTap: () -> Void
    public var onChannelTap: (() -> Void)? = nil

    @State private var isHovered = false
    @FocusState private var isFocused: Bool

    public var body: some View {
        let height = round(width * 9.0 / 16.0)
        VStack(alignment: .leading, spacing: 7) {
            Button(action: onPrimaryTap) {
                ZStack(alignment: .bottomTrailing) {
                    RemoteArtwork(urlString: artworkUrl, placeholderIcon: "play.rectangle")
                        .frame(width: width, height: height)
                        .clipped()

                    if isHovered || isFocused {
                        Color.black.opacity(0.28)
                            .overlay(
                                Circle()
                                    .fill(XirtusTheme.cyanAccent.opacity(0.92))
                                    .frame(width: 40, height: 40)
                                    .overlay(
                                        Image(systemName: "play.fill")
                                            .font(.system(size: 14, weight: .bold))
                                            .foregroundStyle(.black)
                                            .offset(x: 1)
                                    )
                            )
                    }

                    if isLive || !badgeText.isEmpty {
                        Text(isLive ? "● LIVE" : badgeText)
                            .font(XirtusTheme.mono(10.5, weight: .semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(isLive ? XirtusTheme.rose.opacity(0.92) : Color.black.opacity(0.78))
                            .clipShape(RoundedRectangle(cornerRadius: 5))
                            .padding(8)
                    }

                    if progressRatio > 0.01 {
                        VStack {
                            Spacer()
                            GeometryReader { geo in
                                ZStack(alignment: .leading) {
                                    Rectangle().fill(Color.black.opacity(0.55))
                                    Rectangle()
                                        .fill(XirtusTheme.cyanAccent)
                                        .frame(width: geo.size.width * min(1.0, max(0.0, progressRatio)))
                                }
                            }
                            .frame(height: 4)
                        }
                        .frame(width: width, height: height)
                    }
                }
                .frame(width: width, height: height)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 11, style: .continuous)
                        .strokeBorder(
                            (isHovered || isFocused) ? XirtusTheme.cyanAccent : XirtusTheme.hairlineBorder,
                            lineWidth: (isHovered || isFocused) ? 1.6 : 1
                        )
                )
                .scaleEffect((isHovered || isFocused) ? 1.02 : 1.0)
                .animation(.easeOut(duration: 0.15), value: isHovered || isFocused)
            }
            .buttonStyle(.plain)
            .focused($isFocused)
            .onHover { isHovered = $0 }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(XirtusTheme.font(12.5, weight: .semibold))
                    .foregroundStyle(XirtusTheme.textPrimary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)

                if let onChannelTap {
                    Button(action: onChannelTap) {
                        Text(subtitle)
                            .font(XirtusTheme.font(11.5, weight: .medium))
                            .foregroundStyle(XirtusTheme.cyanAccent.opacity(0.9))
                            .lineLimit(1)
                    }
                    .buttonStyle(.plain)
                } else {
                    Text(subtitle)
                        .font(XirtusTheme.font(11.5))
                        .foregroundStyle(XirtusTheme.textSecondary)
                        .lineLimit(1)
                }
            }
            .frame(width: width, alignment: .leading)
        }
    }
}
