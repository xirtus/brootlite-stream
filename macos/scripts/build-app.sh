#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MACOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
ROOT_DIR="$(cd "${MACOS_DIR}/.." && pwd)"

CORE_STATICLIB="${ROOT_DIR}/core/target/release/libbrootlite_core.a"
if [[ ! -f "${CORE_STATICLIB}" ]]; then
    echo "==> Building brootlite-core release staticlib..."
    cargo build --release --manifest-path "${ROOT_DIR}/core/Cargo.toml"
fi

echo "==> Compiling BrootLiteMac (Swift 6 Release)..."
cd "${MACOS_DIR}"
swift build -c release

BIN_PATH="$(swift build -c release --show-bin-path)/BrootLiteMac"
if [[ ! -x "${BIN_PATH}" ]]; then
    echo "Error: compiled binary not found at ${BIN_PATH}" >&2
    exit 1
fi

APP_BUNDLE="${MACOS_DIR}/dist/BrootLite.app"
CONTENTS_DIR="${APP_BUNDLE}/Contents"
MACOS_BIN_DIR="${CONTENTS_DIR}/MacOS"
RESOURCES_DIR="${CONTENTS_DIR}/Resources"
FRAMEWORKS_DIR="${CONTENTS_DIR}/Frameworks"

echo "==> Packaging ${APP_BUNDLE}..."
rm -rf "${APP_BUNDLE}"
mkdir -p "${MACOS_BIN_DIR}" "${RESOURCES_DIR}" "${FRAMEWORKS_DIR}"

cp "${BIN_PATH}" "${MACOS_BIN_DIR}/BrootLiteMac"
chmod +x "${MACOS_BIN_DIR}/BrootLiteMac"

# Bundle Geist & Geist Mono fonts
FONT_SRC_DIR="${ROOT_DIR}/app/src/main/res/font"
if [[ -d "${FONT_SRC_DIR}" ]]; then
    cp "${FONT_SRC_DIR}"/geist*.ttf "${RESOURCES_DIR}/"
fi

# In-app brand mark (top bar)
cp "${ROOT_DIR}/branding/mark-512.png" "${RESOURCES_DIR}/BrandMark.png"

# Generate macOS AppIcon.icns from launcher icon
ICON_PNG="${ROOT_DIR}/branding/icon-macos-1024.png"
if [[ -f "${ICON_PNG}" ]]; then
    cp "${ICON_PNG}" "${RESOURCES_DIR}/AppIcon.png"
    TMP_ICONSET="$(mktemp -d)/AppIcon.iconset"
    mkdir -p "${TMP_ICONSET}"
    for sz in 16 32 128 256 512; do
        sips -z "${sz}" "${sz}" "${ICON_PNG}" --out "${TMP_ICONSET}/icon_${sz}x${sz}.png" >/dev/null 2>&1 || true
        sz2=$((sz * 2))
        sips -z "${sz2}" "${sz2}" "${ICON_PNG}" --out "${TMP_ICONSET}/icon_${sz}x${sz}@2x.png" >/dev/null 2>&1 || true
    done
    iconutil -c icns "${TMP_ICONSET}" -o "${RESOURCES_DIR}/AppIcon.icns" >/dev/null 2>&1 || true
    rm -rf "$(dirname "${TMP_ICONSET}")"
fi

cat > "${CONTENTS_DIR}/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key>
    <string>en</string>
    <key>CFBundleDisplayName</key>
    <string>BrootLite</string>
    <key>CFBundleExecutable</key>
    <string>BrootLiteMac</string>
    <key>CFBundleIconFile</key>
    <string>AppIcon</string>
    <key>CFBundleIdentifier</key>
    <string>app.brootlite.macos</string>
    <key>CFBundleInfoDictionaryVersion</key>
    <string>6.0</string>
    <key>CFBundleName</key>
    <string>BrootLite</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleShortVersionString</key>
    <string>0.2.0</string>
    <key>CFBundleVersion</key>
    <string>2</string>
    <key>LSMinimumSystemVersion</key>
    <string>14.0</string>
    <key>NSHighResolutionCapable</key>
    <true/>
    <key>NSPrincipalClass</key>
    <string>NSApplication</string>
    <key>ATSApplicationFontsPath</key>
    <string>.</string>
    <key>NSLocalNetworkUsageDescription</key>
    <string>BrootLite uses your local network to auto-discover Jellyfin media servers (UDP 7359) and stream DirectPlay media.</string>
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsArbitraryLoads</key>
        <true/>
    </dict>
</dict>
</plist>
PLIST

codesign --force --deep --sign - "${APP_BUNDLE}" >/dev/null 2>&1 || true

echo "==> Done! Native macOS bundle ready at: ${APP_BUNDLE}"
