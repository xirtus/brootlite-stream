# BrootLite
<img width="1280" height="720" alt="image" src="https://github.com/user-attachments/assets/4a898cd5-4675-464e-be6d-67494d82efdc" />

**Ultra-lightweight native media & creator streaming suite across Android TV (`armeabi-v7a`), macOS (`Swift 6 + libmpv VideoToolbox`), and Linux / Windows (`Rust iced GPU + libmpv`).**

BrootLite replaces heavy WebView/Electron/JavaScript media shells (`550+ MB` PSS) and monolithic media centers (`380+ MB` Kodi runtime) with native platform shells running inside a **`~35–85 MB`** footprint — sharing the exact same **Leeloo Multipass `v2` Zero-Knowledge E2EE** Media Pouch (`P-256` `#pk` optical QR lockbox + `AES-256-GCM`) across your Android TV projector, Mac, Linux workstation, and Windows PC.

---

## Platform Targets & Memory Profile

| Target | UI & Playback Stack | Idle / Browsing RAM | Active 1080p/4K Stream RAM |
| :--- | :--- | :--- | :--- |
| **Android TV (`app/`)** | Kotlin Views + `Media3 ExoPlayer` + `Jellyfin FFmpeg` + `libtorrent4j` (`32-bit armeabi-v7a`) | **`~38–55 MB` PSS** (`:pyaddon` & `:potoken` `0 MB`) | **`~60–110 MB` PSS** |
| **macOS (`macos/`)** | **Swift 6** + AppKit/SwiftUI (`NSVisualEffectView`) + **`libmpv` (`VideoToolbox` / `CAOpenGLLayer`)** + `brootlite-core` | **`~35–52 MB`** | **`~55–95 MB`** |
| **Linux & Windows (`desktop/`)** | Pure-Rust **`iced` (`wgpu`)** + dynamic **`libmpv` (`VAAPI` / `NVDEC` / `D3D11VA`)** + `brootlite-core` | **`~35–55 MB`** | **`~55–95 MB`** |
| *Legacy WebView / Kodi Stack* | *Chromium / Electron / Node.js / Full Kodi C+++Python* | *`380–540 MB`* | *`395–650+ MB`* |

---

## Architecture Highlights

### 1. Xirtus Dark-Glass Native Shells (`0 MB` Electron / WebView Overhead)
- **Android TV (`app/`):** Pure Android `View` + `RecyclerView` (`TvUiKit`, `TvAdapters`) with shared `RecycledViewPool`s and `RGB_565` Coil `8%`-heap cap.
- **macOS (`macos/`):** Native Swift 6 (`BrootLite.app`) with `NSVisualEffectView` dark glass, CoreText-registered `Geist` & `Geist Mono` fonts, zero-copy `CAOpenGLLayer` `libmpv` (`hwdec=videotoolbox`) + automatic `AVPlayer` fallback, and keyboard shortcuts (`⌘1..8`, `⌘K`, `Space`, `←/→`, `F`, `Z`).
- **Linux & Windows (`desktop/`):** Pure-Rust `iced 0.13` (`wgpu` Vulkan / Metal / DX12 / GL) with a `64`-entry LRU poster cache and runtime `libloading` `libmpv` bridge (`libmpv.so.2` / `libmpv-2.dll`) supporting both zero-copy hardware GPU rendering (`VAAPI`/`NVDEC`/`D3D11VA`) and inline SIMD-aligned frame rendering.

### 2. Stremio v3 Protocol, Inline Board Search & Smart Cinema Binging
- **Stremio v3 Sync & Browse:** Full `catalog`, `meta`, `stream`, `subtitles`, `link.stremio.com` QR pairing, `addonCollectionGet`, and two-way `datastoreGet`/`datastorePut` **Continue Watching** sync, with **inline Stremio Search + Voice** directly on `Board` and `Discover`.
- **Smart Series Binge Autoplay (`StreamSimilarityMatcher`):** Pre-resolves `SxxE(y+1)` during closing credits and scores streams by **live seeders / direct mirror + `bingeGroup` + release group (`[SubsPlease]`, `-NTb`, etc.) + resolution/codec/audio/sub similarity**, displaying a bottom-right `"Keep Watching · Stream Next ▶"` card with live countdown.
- **Movie / Finale Credits `"Next Recommended"` Bottom Gallery (`RecommendationEngine`):** Slides up a horizontal poster rail over rolling credits (sourced from Jellyfin `/Similar`, Stremio genre/catalog similarity, or YouTube related videos) for 1-click movie-to-movie binging.

### 3. Native Multi-Server Jellyfin Engine
- **Strictly Isolated Multi-Server Profiles:** Connect multiple independent Jellyfin servers (e.g., Local LAN + Cloud HTTPS) via UDP port `7359` LAN auto-discovery, 6-digit `QuickConnect`, username/password, or Multipass bootstrap tokens, with `1.2s` LAN-first health routing.
- **95%+ Direct Play + IntroSkipper:** Direct-Plays `H.264` / `HEVC` / `VP9` `MKV`/`MP4`/`TS` containers with `EAC3`/`AC3`/`DTS`/`TrueHD`/`FLAC` audio, automatic HLS transcode fallback, **`Skip Intro ⏭` / `Skip Credits ⏭`** (`MediaSegments` + `IntroTimestamps`), chapters, and two-way session reporting.

### 4. Ad-Free Logged-In `Streaming` Hub (`YouTube` · `Twitch` · `Odysee` · `Rumble`)
- **Logged-In Ad-Free YouTube:** Official TV OAuth2 Device Code QR sign-in (`youtube.com/activate`) + Brand Account switching, Home/Subscriptions/History/Playlists/Channels/Search, synthetic **1080p60 / 4K DASH (`.mpd`)** playback, lightweight JS `sig`/`nsig` cipher solvers (`QuickJS` on Android, `boa_engine` in Rust — `0 MB` idle), **SponsorBlock** auto-skip (with undo), **two-way Google Watch History**, **ReturnYouTubeDislike**, and optional **DeArrow**.
- **Twitch, Odysee, & Rumble:** Twitch GraphQL live/follows + `twitch.tv/activate` login + real-time stitched-ad HLS stripping; Odysee LBRY JSON-RPC + Lighthouse; Rumble `oembed` + `embedJS` direct 1080p streams.

### 5. RAM-Capped Sliding-Window P2P Engine & Native Swarm Search
- **Android TV:** `libtorrent4j` (`2.1.0-39`, `armeabi-v7a`) pinning header/footer pieces + `28 MB` lookahead piece-deadline window directly into Media3 `TorrentDataSource`.
- **Desktop (`core/`):** Pure-Rust `librqbit` v8 local loopback `127.0.0.1` HTTP `206 Partial Content` byte-range server (`LocalMediaServer`) feeding `libmpv` with instant seek reprioritization and automatic cache purge on stop, paired with concurrent Swarm search (`Public Domain Internet Archive`, `Custom RSS`, `Torznab`).

### 6. Disposable Kodi Python Bridge (`0 MB` Idle & Playback Footprint)
- Executes Kodi 21 Python video plugins (`kodi_bridge.py` synthetic `xbmc*` + `kodi_six` runtime) inside a disposable helper process (`:pyaddon` on Android TV; child `python3` process on macOS/Linux/Windows) that immediately terminates before video playback begins.

### 7. Leeloo Multipass `media_sync` v2 Zero-Knowledge QR-Lockbox E2EE
- **Zero Bundled Secrets:** No private credentials, tokens, or third-party Kodi `.zip` repositories exist anywhere in this repo.
- **Optical `#pk` `P-256` ECDH Lockbox + OS Keystores:** Scanning the pairing QR (`#pk=<65B_P256_pubkey>`, kept strictly in the browser URL fragment) wraps the `32`-byte Vault Master Key end-to-end into `AndroidKeyStore` / macOS Keychain / Linux Secret Service (`AES-256-GCM`, `schemaVersion: 2`), keeping the Multipass server 100% blind with deterministic multi-device CRDT/LWW + CAS merging.

---

## Monorepo Structure

```text
brootlite/
├── app/                     # Android TV native client (Kotlin Views + Media3 + libtorrent4j, armeabi-v7a)
├── core/                    # Shared cross-platform Rust engine (brootlite-core: rlib + staticlib + C ABI)
│   ├── include/             # brootlite_core.h C FFI header for Swift 6
│   └── src/                 # stremio, jellyfin, youtube, creators, torrent, swarm, binge, kodi, multipass
├── macos/                   # Native macOS Swift 6 app (AppKit/SwiftUI + libmpv VideoToolbox -> BrootLite.app)
│   ├── Package.swift
│   ├── Sources/
│   └── scripts/build-app.sh
└── desktop/                 # Native Linux & Windows GPU app (Rust iced wgpu + dynamic libmpv)
    ├── Cargo.toml
    ├── packaging/           # .desktop entry, SVG icon, and install-linux.sh
    └── src/
```

---

## Build & Run

### 1. Android TV (`armeabi-v7a` Release APK)
```bash
./gradlew assembleRelease
adb connect <PROJECTOR_IP>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f app.brootlite.tv
```

### 2. Shared Rust Core (`core/`)
```bash
cargo build --release --manifest-path core/Cargo.toml
cargo test --release --manifest-path core/Cargo.toml
```

### 3. Native macOS App (`Swift 6` + `libmpv VideoToolbox`)
Requires Xcode / Swift 6+ and `brew install mpv` (for zero-copy `libmpv` `VideoToolbox`; falls back automatically to `AVPlayer` if `libmpv` is absent):
```bash
cargo build --release --manifest-path core/Cargo.toml
./macos/scripts/build-app.sh
open macos/dist/BrootLite.app
```

### 4. Native Linux (& Optional Windows) Desktop App (`Rust iced` + `libmpv`)
On Linux, ensure `libmpv` (`libmpv2` / `mpv-libs`) is installed for `VAAPI`/`NVDEC` hardware decoding (on Windows, place `libmpv-2.dll` on `PATH` or alongside `brootlite-desktop.exe`):
```bash
cargo build --release --manifest-path desktop/Cargo.toml
./desktop/packaging/install-linux.sh   # Installs binary + .desktop launcher to ~/.local/
```
