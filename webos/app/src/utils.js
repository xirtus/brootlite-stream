// Pure formatting, HTML escaping, and payload normalization helpers

export function esc(val) {
  if (val === null || val === undefined) return "";
  return String(val)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export function formatTime(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  const pad = (n) => (n < 10 ? "0" + n : String(n));
  if (h > 0) return h + ":" + pad(m) + ":" + pad(r);
  return pad(m) + ":" + pad(r);
}

export function formatCount(n) {
  const num = Number(n);
  if (!isFinite(num) || num < 0) return "";
  if (num >= 1000000) return (num / 1000000).toFixed(1).replace(/\.0$/, "") + "M";
  if (num >= 1000) return (num / 1000).toFixed(1).replace(/\.0$/, "") + "K";
  return String(Math.floor(num));
}

export function formatSpeed(bytesPerSec) {
  const b = Number(bytesPerSec) || 0;
  if (b >= 1024 * 1024) return (b / (1024 * 1024)).toFixed(2) + " MB/s";
  if (b >= 1024) return (b / 1024).toFixed(0) + " KB/s";
  return b.toFixed(0) + " B/s";
}

export function formatBytes(bytes) {
  const b = Number(bytes) || 0;
  if (b <= 0) return "";
  if (b >= 1024 * 1024 * 1024) return (b / (1024 * 1024 * 1024)).toFixed(2) + " GB";
  if (b >= 1024 * 1024) return (b / (1024 * 1024)).toFixed(1) + " MB";
  return (b / 1024).toFixed(0) + " KB";
}

export function isLocalOrProxyUrl(url) {
  if (!url || typeof url !== "string") return false;
  if (url.charAt(0) === "/") return true;
  return /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?\//i.test(url);
}

export function normalizeItem(raw, fallbackType = "movie") {
  if (!raw || typeof raw !== "object") return null;
  return {
    id: raw.id || raw.imdb_id || raw.itemId || raw.videoId || raw.url || raw.itemUrl || "",
    type: raw.type || fallbackType,
    title: raw.title || raw.name || raw.label || "Untitled",
    poster: raw.poster || raw.posterUrl || raw.image || raw.thumbnail || "",
    backdrop: raw.backdrop || raw.background || raw.banner || raw.fanart || raw.poster || "",
    thumbnail: raw.thumbnail || raw.poster || raw.image || "",
    year: raw.year || raw.releaseInfo || "",
    rating: raw.imdbRating || raw.rating || raw.communityRating || "",
    duration: raw.duration || raw.durationText || "",
    durationSec: Number(raw.durationSec || raw.durationSeconds || 0) || 0,
    positionSec: Number(raw.positionSec || raw.resumeSec || 0) || 0,
    overview: raw.description || raw.overview || raw.plot || "",
    genres: Array.isArray(raw.genres) ? raw.genres : [],
    source: raw.source || raw.kind || "",
    providerId: raw.providerId || "",
    serverId: raw.serverId || "",
    platform: raw.platform || "",
    itemUrl: raw.itemUrl || raw.url || "",
    season: raw.season !== undefined ? Number(raw.season) : undefined,
    episode: raw.episode !== undefined ? Number(raw.episode) : undefined,
    episodeTitle: raw.episodeTitle || "",
    channel: raw.channel || raw.author || raw.uploader || "",
    views: raw.views || raw.viewCount || "",
    likes: raw.likes,
    dislikes: raw.dislikes,
    isLive: !!raw.isLive,
    raw: raw,
  };
}

export function itemProgressPct(item) {
  if (!item || !item.durationSec || item.durationSec <= 0 || !item.positionSec) return 0;
  return Math.max(0, Math.min(100, Math.round((item.positionSec / item.durationSec) * 100)));
}
