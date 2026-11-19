#!/usr/bin/env bash
set -euo pipefail

TV_TARGET="${1:-}"
IPK_PATH="${2:-}"

if [[ -z "${TV_TARGET}" ]]; then
  echo "Usage: $0 <tv-device-name-or-ip> [path/to/app.brootlite.tv_*.ipk]" >&2
  echo "Example:" >&2
  echo "  $0 living-room-lg" >&2
  echo "  $0 192.168.1.100 ../dist/app.brootlite.tv_2.1.5_all.ipk" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Find IPK if not specified
if [[ -z "${IPK_PATH}" ]]; then
  IPK_PATH="$(find "${WEBOS_DIR}/dist" -name "*.ipk" | head -n 1)"
  if [[ -z "${IPK_PATH}" ]]; then
    echo "Error: No .ipk found in ${WEBOS_DIR}/dist. Run ./webos/scripts/build-ipk.sh first." >&2
    exit 1
  fi
fi

if [[ ! -f "${IPK_PATH}" ]]; then
  echo "Error: IPK file not found at ${IPK_PATH}" >&2
  exit 1
fi

echo "==> Configuring TV device target: ${TV_TARGET}..."
# Check if device is already registered in ares CLI
if ! npx ares-setup-device -l 2>/dev/null | grep -q "${TV_TARGET}"; then
  echo "==> Registering device ${TV_TARGET} (Enter Developer Mode passcode when prompted)..."
  npx ares-setup-device -a "${TV_TARGET}" -i "${TV_TARGET}" -p 9922 -u prisoner || true
fi

echo "==> Fetching novacom private key / pairing with ${TV_TARGET}..."
npx ares-novacom --device "${TV_TARGET}" --getkey || true

echo "==> Installing IPK: ${IPK_PATH} on ${TV_TARGET}..."
npx ares-install --device "${TV_TARGET}" "${IPK_PATH}"

echo "==> Launching BrootLite on ${TV_TARGET}..."
npx ares-launch --device "${TV_TARGET}" app.brootlite.tv

echo "==> Successfully installed and launched BrootLite on LG webOS TV!"
