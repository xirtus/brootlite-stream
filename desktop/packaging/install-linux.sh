#!/usr/bin/env bash
set -euo pipefail

# BrootLite Desktop v2.1.0 — User-local Linux Installer (Wayland / X11)
# Installs binary to ~/.local/bin/brootlite-desktop, icon, and Freedesktop entry.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_CRATE_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
BIN_SRC="${DESKTOP_CRATE_DIR}/target/release/brootlite-desktop"

PREFIX_BIN="${HOME}/.local/bin"
PREFIX_APPS="${HOME}/.local/share/applications"
PREFIX_ICON_SVG="${HOME}/.local/share/icons/hicolor/scalable/apps"
PREFIX_ICON_PNG="${HOME}/.local/share/icons/hicolor/256x256/apps"

echo "==> BrootLite Desktop v2.1.0 Installer"

if [[ ! -x "${BIN_SRC}" || "${1:-}" == "--build" ]]; then
    echo "==> Building release binary with cargo..."
    (cd "${DESKTOP_CRATE_DIR}" && cargo build --release)
fi

mkdir -p "${PREFIX_BIN}" "${PREFIX_APPS}" "${PREFIX_ICON_SVG}" "${PREFIX_ICON_PNG}"

echo "==> Installing binary -> ${PREFIX_BIN}/brootlite-desktop"
install -m 0755 "${BIN_SRC}" "${PREFIX_BIN}/brootlite-desktop"

echo "==> Installing Freedesktop launcher -> ${PREFIX_APPS}/app.brootlite.Desktop.desktop"
sed "s|^Exec=brootlite-desktop|Exec=${PREFIX_BIN}/brootlite-desktop|" \
    "${SCRIPT_DIR}/app.brootlite.Desktop.desktop" > "${PREFIX_APPS}/app.brootlite.Desktop.desktop"
chmod 0644 "${PREFIX_APPS}/app.brootlite.Desktop.desktop"

echo "==> Installing icons -> ~/.local/share/icons/hicolor/"
install -m 0644 "${SCRIPT_DIR}/app.brootlite.Desktop.svg" "${PREFIX_ICON_SVG}/app.brootlite.Desktop.svg"
if [[ -f "${DESKTOP_CRATE_DIR}/../branding/icon-256.png" ]]; then
    install -m 0644 "${DESKTOP_CRATE_DIR}/../branding/icon-256.png" \
        "${PREFIX_ICON_PNG}/app.brootlite.Desktop.png"
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "${PREFIX_APPS}" || true
fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then
    gtk-update-icon-cache -f -t "${HOME}/.local/share/icons/hicolor" || true
fi

echo "==> Installed! Ensure ${PREFIX_BIN} is on your PATH, then launch 'BrootLite Desktop' or run:"
echo "    ${PREFIX_BIN}/brootlite-desktop"
