#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-2.1.5}"
OUT_IPK="${2:-}"

# LG appinfo.json version MUST be pure MAJOR.MINOR.PATCH numeric digits without leading "v"
CLEAN_VER="${VERSION#v}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBOS_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd "${WEBOS_DIR}/.." && pwd)"
WORKSPACE_DIR="$(cd "${REPO_ROOT}/.." && pwd)"

echo "==> Building BrootLite LG webOS v${CLEAN_VER} IPK..."

# 1. Ensure node_modules exists
if [[ ! -d "${WEBOS_DIR}/node_modules" ]]; then
  if [[ -d "${WORKSPACE_DIR}/brootlite/webos/node_modules" && "${WORKSPACE_DIR}/brootlite/webos" != "${WEBOS_DIR}" ]]; then
    echo "==> Symlinking node_modules from brootlite/webos..."
    ln -s "${WORKSPACE_DIR}/brootlite/webos/node_modules" "${WEBOS_DIR}/node_modules"
  else
    echo "==> Installing npm dependencies in ${WEBOS_DIR}..."
    (cd "${WEBOS_DIR}" && npm install --no-audit --no-fund)
  fi
fi

# 2. Bundle service
echo "==> Bundling Background Luna Service (esbuild)..."
mkdir -p "${WEBOS_DIR}/service/dist"
"${WEBOS_DIR}/node_modules/.bin/esbuild" "${WEBOS_DIR}/service/src/index.js" \
  --bundle \
  --platform=node \
  --target=es2017 \
  --external:webos-service \
  --outfile="${WEBOS_DIR}/service/dist/service.js"

# 3. Bundle app
echo "==> Bundling Web UI App (esbuild)..."
mkdir -p "${WEBOS_DIR}/app/dist"
"${WEBOS_DIR}/node_modules/.bin/esbuild" "${WEBOS_DIR}/app/src/main.js" \
  --bundle \
  --platform=browser \
  --target=es2017 \
  --outfile="${WEBOS_DIR}/app/dist/app.js"

# 4. Stage clean temporary directory
STAGE_DIR="${WEBOS_DIR}/stage"
echo "==> Staging package artifacts in ${STAGE_DIR}..."
rm -rf "${STAGE_DIR}"
mkdir -p "${STAGE_DIR}/app/assets" "${STAGE_DIR}/app/dist" "${STAGE_DIR}/service/dist"

# Copy App files
cp "${WEBOS_DIR}/app/index.html" "${STAGE_DIR}/app/"
cp "${WEBOS_DIR}/app/styles.css" "${STAGE_DIR}/app/"
cp "${WEBOS_DIR}/app/dist/app.js" "${STAGE_DIR}/app/dist/"

# Update appinfo.json version dynamically
python3 -c "
import json
path = '${WEBOS_DIR}/app/appinfo.json'
with open(path, 'r') as f:
    data = json.load(f)
data['version'] = '${CLEAN_VER}'
with open('${STAGE_DIR}/app/appinfo.json', 'w') as f:
    json.dump(data, f, indent=2)
"

# Generate/copy LG webOS required icons using macOS sips (or fallback)
BRANDING_DIR="${REPO_ROOT}/branding"
if [[ -f "${BRANDING_DIR}/icon-256.png" ]]; then
  sips -z 80 80 "${BRANDING_DIR}/icon-256.png" --out "${STAGE_DIR}/app/icon.png" >/dev/null 2>&1 || cp "${BRANDING_DIR}/icon-256.png" "${STAGE_DIR}/app/icon.png"
  sips -z 130 130 "${BRANDING_DIR}/icon-256.png" --out "${STAGE_DIR}/app/largeIcon.png" >/dev/null 2>&1 || cp "${BRANDING_DIR}/icon-256.png" "${STAGE_DIR}/app/largeIcon.png"
elif [[ -f "${BRANDING_DIR}/icon-1024.png" ]]; then
  sips -z 80 80 "${BRANDING_DIR}/icon-1024.png" --out "${STAGE_DIR}/app/icon.png" >/dev/null 2>&1
  sips -z 130 130 "${BRANDING_DIR}/icon-1024.png" --out "${STAGE_DIR}/app/largeIcon.png" >/dev/null 2>&1
else
  touch "${STAGE_DIR}/app/icon.png" "${STAGE_DIR}/app/largeIcon.png"
fi

if [[ -f "${BRANDING_DIR}/mark-512.png" ]]; then
  cp "${BRANDING_DIR}/mark-512.png" "${STAGE_DIR}/app/assets/BrandMark.png"
else
  touch "${STAGE_DIR}/app/assets/BrandMark.png"
fi

# Copy Geist fonts if present
FONT_SRC="${REPO_ROOT}/app/src/main/res/font"
if [[ -d "${FONT_SRC}" ]]; then
  cp "${FONT_SRC}"/geist*.ttf "${STAGE_DIR}/app/assets/" 2>/dev/null || true
fi

# Copy Service files
cp "${WEBOS_DIR}/service/services.json" "${STAGE_DIR}/service/"
cp "${WEBOS_DIR}/service/package.json" "${STAGE_DIR}/service/"
cp "${WEBOS_DIR}/service/dist/service.js" "${STAGE_DIR}/service/dist/service.js"

# If seed_pack.json exists, copy into both service/ and service/dist/ so __dirname finds it
if [[ -f "${WEBOS_DIR}/seed_pack.json" ]]; then
  cp "${WEBOS_DIR}/seed_pack.json" "${STAGE_DIR}/service/seed_pack.json"
  cp "${WEBOS_DIR}/seed_pack.json" "${STAGE_DIR}/service/dist/seed_pack.json"
  echo "==> Embedded seed_pack.json into service stage."
fi

# 5. Run official ares-package
mkdir -p "${WEBOS_DIR}/dist"
echo "==> Packaging IPK with ares-package..."
"${WEBOS_DIR}/node_modules/.bin/ares-package" --no-minify "${STAGE_DIR}/app" "${STAGE_DIR}/service" -o "${WEBOS_DIR}/dist"

PRODUCED_IPK="${WEBOS_DIR}/dist/app.brootlite.tv_${CLEAN_VER}_all.ipk"
if [[ ! -f "${PRODUCED_IPK}" ]]; then
  # Find any generated .ipk in dist
  PRODUCED_IPK="$(find "${WEBOS_DIR}/dist" -name "*.ipk" | head -n 1)"
fi

if [[ -n "${OUT_IPK}" && -f "${PRODUCED_IPK}" ]]; then
  mkdir -p "$(dirname "${OUT_IPK}")"
  cp "${PRODUCED_IPK}" "${OUT_IPK}"
  echo "==> Copied IPK to requested output: ${OUT_IPK}"
fi

# 6. Cleanup stage
rm -rf "${STAGE_DIR}"
echo "==> Build complete! Output IPK: ${PRODUCED_IPK}"
