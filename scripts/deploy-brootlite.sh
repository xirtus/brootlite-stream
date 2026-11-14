#!/usr/bin/env bash
# Deploy BrootLite APK, optionally import local Kodi Python addons/userdata, and AOT-compile (-m speed)
set -euo pipefail

ADB_BIN="${ADB_BIN:-adb}"
HOST="${1:?Usage: ./scripts/deploy-brootlite.sh <device-ip:5555>}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
APK="${APK:-$DIR/app/build/outputs/apk/release/app-release.apk}"

A() { "$ADB_BIN" -s "$HOST" "$@"; }
S() { "$ADB_BIN" -s "$HOST" shell "$@"; }

echo "== [1/4] Installing BrootLite (${APK##*/}) to $HOST =="
A install -r "$APK"

echo "== [2/4] Syncing optional local Kodi Python addons & userdata on device =="
S "mkdir -p /sdcard/Android/data/app.brootlite.tv/files/kodi_env/addons /sdcard/Android/data/app.brootlite.tv/files/kodi_env/userdata /sdcard/Android/data/app.brootlite.tv/files/kodi_env/temp"

# Copy installed Python video addons & dependency modules locally on device storage (zero Wi-Fi transfer)
S '
SRC="/sdcard/Android/data/org.xbmc.kodi/files/.kodi"
DST="/sdcard/Android/data/app.brootlite.tv/files/kodi_env"
if [ -d "$SRC/addons" ]; then
  for d in "$SRC"/addons/plugin.video.* "$SRC"/addons/context.* "$SRC"/addons/script.*; do
    if [ -d "$d" ]; then
      bn=$(basename "$d")
      # Skip binary-heavy C++ torrent daemons when native BrootLite sliding-window engine handles P2P
      case "$bn" in
        plugin.video.elementum|script.elementum.*) continue ;;
      esac
      echo "  syncing addon: $bn"
      rm -rf "$DST/addons/$bn"
      cp -r "$d" "$DST/addons/$bn"
    fi
  done
fi
if [ -d "$SRC/userdata" ]; then
  cp -f "$SRC/userdata/favourites.xml" "$DST/userdata/" 2>/dev/null || true
  if [ -d "$SRC/userdata/addon_data" ]; then
    mkdir -p "$DST/userdata/addon_data"
    for ad_path in "$SRC"/userdata/addon_data/*; do
      if [ -d "$ad_path" ]; then
        ad=$(basename "$ad_path")
        echo "  syncing userdata: $ad"
        rm -rf "$DST/userdata/addon_data/$ad"
        cp -r "$ad_path" "$DST/userdata/addon_data/$ad"
      fi
    done
  fi
fi
'

echo "== [3/4] Granting permissions & compiling BrootLite to 100% native ARM machine code (-m speed) =="
S "pm grant app.brootlite.tv android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true"
S "pm grant app.brootlite.tv android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true"
S "pm grant app.brootlite.tv android.permission.RECORD_AUDIO 2>/dev/null || true"
S "cmd appops set app.brootlite.tv MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true"
S "cmd package compile -m speed -f app.brootlite.tv"

echo "== [4/4] Deployment complete! =="
