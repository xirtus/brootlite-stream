// Detail Overlay: Poster, Metadata, Season/Episode Selector, Favorite Toggle & Non-Truncated Stream List
import { rpc } from "./rpc.js";
import { esc, normalizeItem } from "./utils.js";

export function renderDetailOverlay(ctx) {
  const { state, actions } = ctx;
  const d = state.detail;
  if (!d || !d.open) return "";

  const meta = d.meta || d.item || {};
  const title = meta.title || meta.name || "Untitled";
  const poster = meta.poster || "";
  const backdrop = meta.backdrop || meta.background || poster;
  const year = meta.year || meta.releaseInfo || "";
  const rating = meta.imdbRating || meta.rating || "";
  const genres = Array.isArray(meta.genres) ? meta.genres : [];
  const overview = meta.overview || meta.description || "";
  const isSeries = (meta.type || d.item.type) === "series";
  const seasons = d.seasons || [];
  const episodes = d.episodes || [];
  const streams = d.streams || [];
  const isFav = actions.isFavorite(meta.id || d.item.id);

  let html =
    '<div id="detail-overlay" class="overlay-backdrop">' +
    '  <div class="detail-hero">' +
    (backdrop ? '<img class="detail-backdrop-img" src="' + esc(backdrop) + '" alt="" />' : "") +
    '    <div class="detail-hero-inner">' +
    (poster
      ? '<img class="detail-poster" src="' + esc(poster) + '" alt="" />'
      : '<div class="detail-poster poster-placeholder">' + esc(title) + "</div>") +
    '      <div class="detail-info">' +
    '        <div style="display:flex;align-items:flex-start;justify-content:space-between;gap:16px;">' +
    '          <div class="detail-title">' + esc(title) + "</div>" +
    '          <div class="form-row" style="flex-shrink:0;">' +
    '            <button id="detail-fav-btn" class="btn focusable' + (isFav ? " btn-emerald" : "") + '" tabindex="0">' +
    (isFav ? "★ Saved" : "☆ Favorite") +
    "</button>" +
    '            <button id="detail-close-btn" class="btn focusable" tabindex="0">✕ Back</button>' +
    "          </div>" +
    "        </div>" +
    '        <div class="detail-meta-row">';
  if (year) html += '<span class="badge-chip">' + esc(year) + "</span>";
  if (rating) html += '<span class="badge-chip badge-amber">★ ' + esc(rating) + "</span>";
  if (isSeries) html += '<span class="badge-chip badge-indigo">Series</span>';
  for (let i = 0; i < genres.length && i < 5; i++) {
    html += '<span class="badge-chip">' + esc(genres[i]) + "</span>";
  }
  html +=
    "        </div>" +
    (overview ? '<div class="detail-overview">' + esc(overview) + "</div>" : "") +
    "      </div>" +
    "    </div>" +
    "  </div>" +
    '  <div class="detail-body">';

  if (d.loadingMeta) {
    html += '<div class="empty-state">Loading metadata...</div>';
  }

  // Season & Episode Selector for Series
  if (isSeries && seasons.length > 0) {
    html +=
      '<div>' +
      '  <div class="section-header" style="margin-top:0;"><div class="section-title">Seasons & Episodes</div></div>' +
      '  <div class="pill-strip">';
    for (let i = 0; i < seasons.length; i++) {
      const s = seasons[i];
      html +=
        '<button class="pill focusable' + (d.selectedSeason === s ? " is-active" : "") + '" tabindex="0" data-detail-season="' + esc(s) + '">Season ' + esc(s) + "</button>";
    }
    html += "  </div>";
    if (episodes.length > 0) {
      html += '<div class="episode-rail">';
      for (let i = 0; i < episodes.length; i++) {
        const ep = episodes[i];
        const epNum = ep.episode !== undefined ? ep.episode : ep.number !== undefined ? ep.number : i + 1;
        const active = d.selectedEpisode === epNum;
        html +=
          '<button class="episode-card focusable' + (active ? " is-active" : "") + '" tabindex="0" data-detail-ep="' + esc(epNum) + '">' +
          '  <div class="mono" style="font-size:13px;color:var(--cyan);">S' + String(d.selectedSeason).padStart(2, "0") + "E" + String(epNum).padStart(2, "0") + "</div>" +
          '  <div style="font-size:17px;font-weight:600;">' + esc(ep.title || ep.name || "Episode " + epNum) + "</div>" +
          (ep.overview ? '<div class="muted" style="font-size:14px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;">' + esc(ep.overview) + "</div>" : "") +
          "</button>";
      }
      html += "</div>";
    }
    html += "</div>";
  }

  // Jellyfin Direct Play or Non-Truncated Stream List
  if (d.item && d.item.source === "jellyfin" && !isSeries) {
    html +=
      '<div class="form-row">' +
      '  <button id="detail-jf-play" class="btn btn-primary focusable" tabindex="0" data-autofocus="1">▶ Play from Jellyfin Server</button>' +
      "</div>";
  }

  html +=
    '<div>' +
    '  <div class="section-header" style="margin-top:0;">' +
    '    <div class="section-title">Available Streams' +
    (isSeries ? ' <span class="badge-chip badge-cyan">S' + String(d.selectedSeason || 1).padStart(2, "0") + "E" + String(d.selectedEpisode || 1).padStart(2, "0") + "</span>" : "") +
    "    </div>" +
    '    <button id="detail-refresh-streams" class="btn btn-sm focusable" tabindex="0">↻ Refresh Streams</button>' +
    "  </div>";

  if (d.loadingStreams) {
    html += '<div class="empty-state">Resolving streams across active providers...</div>';
  } else if (streams.length === 0) {
    html += '<div class="empty-state">No streams found for this title. Try Refresh Streams or check Passport provider pack.</div>';
  } else {
    html += '<div class="stream-list">';
    for (let i = 0; i < streams.length; i++) {
      const st = streams[i];
      const telemetry =
        st.telemetryHeader ||
        [st.quality, st.provider, st.size].filter(Boolean).join(" · ") ||
        "Stream #" + (i + 1);
      const releaseTitle =
        st.fullReleaseTitle || st.title || st.name || st.filename || st.url || "Direct Stream";
      const isCached = !!(st.cached || /⚡|cached/i.test(telemetry));
      html +=
        '<button class="stream-row focusable-subtle focusable" tabindex="0" data-stream-idx="' + i + '"' + (i === 0 ? ' data-autofocus="1"' : "") + ">" +
        '  <div class="stream-telemetry' + (isCached ? " is-cached" : "") + '">' + esc(telemetry) + "</div>" +
        '  <div class="stream-release-title">' + esc(releaseTitle) + "</div>" +
        "</button>";
    }
    html += "</div>";
  }

  html += "</div></div></div>";
  return html;
}

export function bindDetailEvents(root, ctx) {
  const { state, actions, nav } = ctx;
  const overlay = root.querySelector("#detail-overlay");
  if (!overlay || !state.detail || !state.detail.open) return;

  const closeBtn = overlay.querySelector("#detail-close-btn");
  if (closeBtn) closeBtn.onclick = () => actions.closeDetail();

  const favBtn = overlay.querySelector("#detail-fav-btn");
  if (favBtn) {
    favBtn.onclick = () => {
      const item = normalizeItem(state.detail.meta || state.detail.item);
      if (item) actions.toggleFavorite(item);
    };
  }

  const jfPlay = overlay.querySelector("#detail-jf-play");
  if (jfPlay) {
    jfPlay.onclick = () => actions.playJellyfinItem(state.detail.item);
  }

  const refreshBtn = overlay.querySelector("#detail-refresh-streams");
  if (refreshBtn) {
    refreshBtn.onclick = () => actions.fetchDetailStreams();
  }

  const seasonBtns = overlay.querySelectorAll("[data-detail-season]");
  for (let i = 0; i < seasonBtns.length; i++) {
    seasonBtns[i].onclick = () => {
      const s = Number(seasonBtns[i].getAttribute("data-detail-season"));
      actions.selectDetailSeason(s);
    };
  }

  const epBtns = overlay.querySelectorAll("[data-detail-ep]");
  for (let i = 0; i < epBtns.length; i++) {
    epBtns[i].onclick = () => {
      const ep = Number(epBtns[i].getAttribute("data-detail-ep"));
      actions.selectDetailEpisode(ep);
    };
  }

  const streamBtns = overlay.querySelectorAll("[data-stream-idx]");
  for (let i = 0; i < streamBtns.length; i++) {
    streamBtns[i].onclick = () => {
      const idx = Number(streamBtns[i].getAttribute("data-stream-idx"));
      actions.playDetailStream(idx);
    };
  }

  nav.ensureFocus();
}

export async function loadDetailData(state, item, renderFn) {
  state.detail = {
    open: true,
    item: item,
    meta: Object.assign({}, item),
    seasons: [],
    episodes: [],
    allVideos: [],
    selectedSeason: item.season || 1,
    selectedEpisode: item.episode || 1,
    streams: [],
    loadingMeta: true,
    loadingStreams: false,
  };
  renderFn();

  if (item.source === "jellyfin" && item.serverId && item.type !== "series") {
    state.detail.loadingMeta = false;
    renderFn();
    return;
  }

  try {
    const res = await rpc("catalog.detail", {
      type: item.type || "movie",
      id: item.id,
      serverId: item.serverId,
      source: item.source,
    });
    const meta = (res && (res.meta || res.item || res)) || {};
    state.detail.meta = Object.assign({}, item, meta, {
      title: meta.name || meta.title || item.title,
      overview: meta.description || meta.overview || item.overview,
      backdrop: meta.background || meta.backdrop || item.backdrop,
      poster: meta.poster || item.poster,
    });

    const videos = Array.isArray(meta.videos) ? meta.videos : Array.isArray(meta.episodes) ? meta.episodes : [];
    state.detail.allVideos = videos;

    if ((state.detail.meta.type || item.type) === "series" && videos.length > 0) {
      const seasonSet = {};
      for (let i = 0; i < videos.length; i++) {
        const s = Number(videos[i].season !== undefined ? videos[i].season : 1);
        if (s > 0) seasonSet[s] = true;
      }
      const seasons = Object.keys(seasonSet)
        .map(Number)
        .sort((a, b) => a - b);
      state.detail.seasons = seasons.length > 0 ? seasons : [1];
      if (!seasonSet[state.detail.selectedSeason]) {
        state.detail.selectedSeason = state.detail.seasons[0];
      }
      state.detail.episodes = videos.filter(
        (v) => Number(v.season !== undefined ? v.season : 1) === state.detail.selectedSeason
      );
      if (
        state.detail.episodes.length > 0 &&
        !state.detail.episodes.some((e) => Number(e.episode || e.number) === state.detail.selectedEpisode)
      ) {
        state.detail.selectedEpisode = Number(
          state.detail.episodes[0].episode || state.detail.episodes[0].number || 1
        );
      }
    }
  } catch (_e) {
    // Keep base item metadata if detail lookup fails
  }

  state.detail.loadingMeta = false;
  renderFn();
  await fetchDetailStreamsData(state, renderFn);
}

export async function fetchDetailStreamsData(state, renderFn) {
  if (!state.detail || !state.detail.open) return;
  const d = state.detail;
  const meta = d.meta || d.item;
  d.loadingStreams = true;
  renderFn();

  try {
    const res = await rpc("streams.fetch", {
      type: meta.type || "movie",
      id: meta.id,
      season: (meta.type || d.item.type) === "series" ? d.selectedSeason : undefined,
      episode: (meta.type || d.item.type) === "series" ? d.selectedEpisode : undefined,
      title: meta.title || meta.name,
      year: meta.year,
    });
    d.streams = (res && Array.isArray(res.streams) ? res.streams : []) || [];
  } catch (_e) {
    d.streams = [];
  }

  d.loadingStreams = false;
  renderFn();
}
