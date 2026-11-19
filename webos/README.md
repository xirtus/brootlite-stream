# BrootLite for LG webOS (`brootlite/webos`)

High-performance native BitTorrent sliding-window streaming app and background service for LG webOS Smart TVs (webOS 5.0 through webOS 24+ OLED/QNED).

---

## 1. Prerequisites & Developer Mode Setup

1. **Install Developer Mode App**: On your LG webOS TV, go to the **LG Content Store**, search for **Developer Mode**, and install it.
2. **Enable Account & Dev Mode**: Open the Developer Mode app, sign in with your free LG developer account, flip **Developer Mode** to **ON**, and enable **Key Server**.
3. **Note your Passcode & IP**: The app displays an 6-character passcode and your TV's local IP address.

---

## 2. Building & Sideloading

Run from the root or inside `brootlite/webos`:

```bash
# Build the .ipk package
./webos/scripts/build-ipk.sh 2.1.5

# Sideload and launch on your TV (registers device & installs automatically)
./webos/scripts/install-tv.sh 192.168.1.150
```

---

## 3. Auto-Renewing the 999-Hour Dev Mode Timer

LG Developer Mode automatically expires every 999 hours. You can renew it instantly without touching the TV remote by hitting the official LG reset endpoint:

```bash
curl -s "https://developer.lge.com/secure/ResetDevModeSession.dev?sessionToken=YOUR_SESSION_TOKEN"
```
*(Token can be found in your LG Developer account dashboard).*

---

## 4. Architecture & Highlights

- **Luna Node.js Background Service (`app.brootlite.tv.service`)**: Runs persistently in the background handling TCP/UDP/DHT BitTorrent sliding-window streaming.
- **Header-Injecting HLS/MP4 Reverse Proxy**: Serves transcoded/direct streams locally to the web player with zero latency.
- **On-the-Fly Subtitles**: Converts `SRT` -> `WebVTT` in real-time.
- **Audio Optimization**: `EAC3`/`AC3` LG OLED audio track ranking and `WebAudio` 50%–300% Kodi Amplifier + 3-Band EQ + Dialogue DRC.
