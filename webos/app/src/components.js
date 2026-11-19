// Shared 10-foot UI card components (PosterCard & 16:9 VideoCard)
import { esc, formatCount, itemProgressPct } from "./utils.js";

export function renderPosterCard(item, idx, dataAttrPrefix) {
  const pct = itemProgressPct(item);
  const subParts = [];
  if (item.season !== undefined && item.episode !== undefined) {
    subParts.push("S" + String(item.season).padStart(2, "0") + "E" + String(item.episode).padStart(2, "0"));
  }
  if (item.year) subParts.push(String(item.year));
  if (item.source && item.source !== "stremio") subParts.push(item.source);

  return (
    '<button class="poster-card focusable" tabindex="0" ' +
    dataAttrPrefix +
    '="' +
    idx +
    '">' +
    '  <div class="poster-media">' +
    (item.poster
      ? '<img src="' + esc(item.poster) + '" alt="" loading="lazy" />'
      : '<div class="poster-placeholder">' + esc(item.title) + "</div>") +
    '    <div class="poster-badges">' +
    (item.rating ? '<span class="badge-chip badge-amber">★ ' + esc(item.rating) + "</span>" : "<span></span>") +
    (item.type === "series" ? '<span class="badge-chip badge-indigo">Series</span>' : "") +
    "    </div>" +
    (pct > 0
      ? '<div class="poster-progress-track"><div class="poster-progress-fill" style="width:' + pct + '%"></div></div>'
      : "") +
    "  </div>" +
    '  <div class="poster-body">' +
    '    <div class="poster-title">' + esc(item.title) + "</div>" +
    (subParts.length > 0 ? '<div class="poster-sub">' + esc(subParts.join(" · ")) + "</div>" : "") +
    "  </div>" +
    "</button>"
  );
}

export function renderVideoCard(item, idx, dataAttrPrefix) {
  const thumb = item.thumbnail || item.poster || item.backdrop || "";
  const likesStr = item.likes !== undefined && item.likes !== null ? formatCount(item.likes) : "";
  const dislikesStr = item.dislikes !== undefined && item.dislikes !== null ? formatCount(item.dislikes) : "";

  return (
    '<button class="video-card focusable" tabindex="0" ' +
    dataAttrPrefix +
    '="' +
    idx +
    '">' +
    '  <div class="video-thumb">' +
    (thumb
      ? '<img src="' + esc(thumb) + '" alt="" loading="lazy" />'
      : '<div class="poster-placeholder">' + esc(item.title) + "</div>") +
    '    <div class="poster-badges">' +
    (item.isLive ? '<span class="badge-chip badge-rose">● LIVE</span>' : "<span></span>") +
    (item.quality ? '<span class="badge-chip badge-cyan">' + esc(item.quality) + "</span>" : "") +
    "    </div>" +
    (item.duration ? '<span class="video-duration">' + esc(item.duration) + "</span>" : "") +
    "  </div>" +
    '  <div class="video-body">' +
    '    <div class="video-title">' + esc(item.title) + "</div>" +
    '    <div class="video-meta">' +
    '      <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + esc(item.channel || item.providerId || item.platform || "") + "</span>" +
    (item.views ? '<span class="mono">' + esc(typeof item.views === "number" ? formatCount(item.views) : item.views) + "</span>" : "") +
    "    </div>" +
    (likesStr || dislikesStr
      ? '<div class="ryd-row">' +
        (likesStr ? '<span style="color:var(--emerald);">👍 ' + esc(likesStr) + "</span>" : "") +
        (dislikesStr ? '<span style="color:var(--rose);">👎 ' + esc(dislikesStr) + "</span>" : "") +
        "</div>"
      : "") +
    "  </div>" +
    "</button>"
  );
}
