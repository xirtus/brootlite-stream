# BrootLite Desktop (`v2.1.0`) — Native Linux & Windows Rust GPU Media Center

`brootlite-desktop` is the ultra-lean, zero-Electron, zero-WebView native **Linux (Wayland + X11)** and **Windows (`x86_64` / `aarch64`)** desktop application powered directly by [`brootlite-core`](../core), [`iced 0.13`](https://iced.rs) (`wgpu` Vulkan / DX12 / Metal / GL hardware acceleration), and a zero-C-header dynamic `libmpv` runtime bridge (`libloading`).

## Architecture & Memory Budget

| Layer | Implementation | Footprint |
| --- | --- | --- |
| **Core Engine** | Direct Rust crate link to `brootlite-core` (`BrootLiteEngine`) — zero IPC, zero FFI serialization overhead | ~8–12 MB |
| **GPU UI Shell** | `iced 0.13` (`wgpu` backend) + bundled `Geist` / `Geist Mono` typography + Xirtus Dark-Glass (`#090B10`) theme | ~18–26 MB |
| **Poster Cache** | Strict `LRU(64)` `iced::widget::image::Handle` memory cache (`2 MiB` max payload guard, 18 max concurrent fetches) | Bounded |
| **Video Playback** | Dynamic runtime `libmpv` (`libmpv.so.2` / `libmpv-2.dll`) with live switchable **GPU Hardware Window (`VAAPI` / `NVDEC` / `D3D11VA`)** and **Inline Canvas (`MPV_RENDER_API_TYPE_SW` `960x540` SIMD)** modes; falls back cleanly to system `mpv` / `vlc` / `ffplay` if `libmpv` is absent | Hardware zero-copy / ~4 MB inline |
| **Idle / Browsing Total** | Complete 8-view desktop hub | **~35–55 MB RAM** |

---

## Feature Parity Across All 8 Views

1. **`Board`**: Prominent inline Stremio search bar at top, merged multi-source **Continue Watching** (Stremio + all configured Jellyfin servers + YouTube + local history), and synced Stremio addon catalog rows.
2. **`Discover`**: Media type (`Movies` / `Series` / `Anime`), catalog/network selector, dynamic genre & **By Year** filters, sort modes (`Trending`, `Top Rated`, `Newest`), quick jump to Search, and paginated 6-column `2:3` poster grid.
3. **`Jellyfin`**: Strictly isolated multi-server tabs (`Server 1`, `Server 2`, `⚙ Settings`, `+ Add Server`) with UDP `7359` LAN auto-discovery, 6-digit **QuickConnect**, Username/Password login, `Overview`, `Resume`, `Next Up`, Library Views, Genre/Year/Sort filters, and isolated per-server search.
4. **`Streaming`**: Ad-free **YouTube · Twitch · Odysee · Rumble** hub with native `iced::widget::qr_code` Google TV OAuth device flow, Brand Account / channel switcher, DASH `.mpd` 1080p60/4K HDR manifest generation, **SponsorBlock** auto-skip + `↩ Undo Skip`, **ReturnYouTubeDislike**, **DeArrow**, Twitch GQL ad-free HLS proxy, and direct Odysee/Rumble 1080p streams.
5. **`Search`**: Dual-tier layout — **Stremio Catalog Search** prominently on top (`All · Movies · Series · Anime` + quick preset chips) and **Direct P2P Swarm** below (`All Swarm · Public Domain Internet Archive · Custom RSS · Torznab` + curated one-click feeds) streaming directly via `librqbit`.
6. **`Library`**: Filterable local Favorites & Watch History (`All · Favorites · History · Movies · Series`) with instant resume and 1-click favorite toggling.
7. **`Kodi`**: Ephemeral, sandboxed Python 3 Kodi video addon explorer (`plugin.video.*`) with breadcrumb navigation and direct stream playback.
8. **`Passport ↗` (Leeloo Multipass v2)**: Optical `#pk` P-256 ECDH + AES-256-GCM E2EE QR pairing (`iced::widget::qr_code`), 1-click **Sync Vault** (CAS pull + per-item LWW CRDT merge + push), Stremio QR link & Addon manager, YouTube/SponsorBlock/DeArrow toggles, P2P Torrent buffer & DHT tuning, and Torznab indexer configuration.
9. **Active Player & Binge Engine**:
   - Dual `libmpv` playback modes (`GPU Hardware Window` & `Inline Canvas`), keyboard shortcuts (`Space` Play/Pause, `←`/`→` ±10s, `↑`/`↓` ±60s, `M` Mute, `F` Fullscreen, `Esc` Back), audio/subtitle Cycling, and live `librqbit` P2P swarm telemetry.
   - **Series Binge Autoplay**: Automatic next-episode stream pre-resolution ~45s before episode end + non-intrusive bottom-right **`Keep Watching · Stream Next ▶`** card during the final 35s with a 15s countdown and **`Watch Credits`** dismiss button.
   - **Movie Credits Gallery**: Non-intrusive bottom **`Up Next · Recommended Movies`** poster strip during the final 3 minutes of movies.

---

## Linux Setup & Installation (Wayland & X11)

### 1. Install Runtime `libmpv` & Hardware Decode Drivers

- **Ubuntu / Debian / Pop!_OS / Mint**:
  ```bash
  sudo apt update && sudo apt install -y libmpv2 va-driver-all mesa-va-drivers
  ```
- **Fedora / Nobara / RHEL**:
  ```bash
  sudo dnf install -y mpv-libs libva-utils
  ```
- **Arch Linux / EndeavourOS / CachyOS / SteamOS**:
  ```bash
  sudo pacman -S --needed mpv libva-mesa-driver
  ```

*(No `-dev` / `-devel` C headers or `pkg-config` are required to compile `brootlite-desktop` — `libmpv.so.2` is loaded dynamically at runtime via `libloading`.)*

### 2. Build & Install User-Locally

```bash
cd brootlite/desktop
./packaging/install-linux.sh --build
```

This compiles `target/release/brootlite-desktop` with LTO (`strip = "symbols"`, `codegen-units = 1`) and installs:
- `~/.local/bin/brootlite-desktop`
- `~/.local/share/applications/app.brootlite.Desktop.desktop`
- `~/.local/share/icons/hicolor/scalable/apps/app.brootlite.Desktop.svg`

### Environment Variables

| Variable | Description |
| --- | --- |
| `BROOTLITE_LIBMPV_PATH` | Optional explicit path to `libmpv.so.2` / `libmpv-2.dll` / `libmpv.2.dylib` |
| `BROOTLITE_DATA_DIR` | Override persistent config & encrypted Multipass vault directory (default `~/.local/share/brootlite-desktop` on Linux, `%APPDATA%\BrootLite` on Windows) |
| `WGPU_BACKEND` | Optional `wgpu` override (`vulkan`, `gl`, `dx12`, `metal`) |

---

## Windows (`x86_64` / `aarch64`) Setup & Build

1. Install the [Rust MSVC toolchain](https://rustup.rs).
2. Build the native release executable (zero C/C++ build dependencies):
   ```powershell
   cd brootlite\desktop
   cargo build --release
   ```
3. Download `libmpv-2.dll` (e.g., from [`shinchiro/mpv-winbuild-cmake`](https://github.com/shinchiro/mpv-winbuild-cmake/releases)) and place `libmpv-2.dll` either alongside `brootlite-desktop.exe` or anywhere in `PATH` (`C:\Program Files\mpv\libmpv-2.dll` is also auto-detected).
4. Run `target\release\brootlite-desktop.exe` — hardware decoding automatically selects `D3D11VA` (`hwdec=d3d11va-copy` / `auto-safe`) and `wgpu` renders via DirectX 12 / Vulkan.
