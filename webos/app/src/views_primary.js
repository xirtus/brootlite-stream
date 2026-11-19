// Primary Views: Board, Discover, Search (Catalog + Dynamic Swarm), and Library
import { renderPosterCard } from "./components.js";
import { esc, formatBytes } from "./utils.js";

export function renderBoardView(ctx) {
  const { state } = ctx;
  const b = state.board;

  let html =
    '<div class="toolbar-row">' +
    '  <div class="search-box">' +
    '    <span style="color:var(--cyan);font-size:20px;">⌕</span>' +
    '    <input id="board-search-input" class="search-input focusable" tabindex="0" type="text" ' +
    '      placeholder="Search Stremio catalogs & Direct P2P Swarm..." value="' + esc(state.search.query) + '" data-enter-target="board-search-go" />' +
    '    <button id="board-search-go" class="btn btn-primary btn-sm focusable" tabindex="0">Search</button>' +
    "  </div>" +
    '  <button id="board-refresh-btn" class="btn focusable" tabindex="0">↻ Refresh Board</button>' +
    "</div>";

  if (b.loading) {
    return html + '<div class="empty-state">Loading Board catalogs...</div>';
  }

  // Continue Watching Rail
  if (b.continueWatching && b.continueWatching.length > 0) {
    html +=
      '<div class="section-header">' +
      '  <div class="section-title">Continue Watching <span class="pill-badge">' + b.continueWatching.length + "</span></div>" +
      "</div>" +
      '<div class="poster-rail">';
    for (let i = 0; i < b.continueWatching.length; i++) {
      html += renderPosterCard(b.continueWatching[i], i, "data-board-cw");
    }
    html += "</div>";
  }

  // Synced Stremio Catalog Poster Rails
  if (b.rails && b.rails.length > 0) {
    for (let r = 0; r < b.rails.length; r++) {
      const rail = b.rails[r];
      const items = rail.items || [];
      if (items.length === 0) continue;
      html +=
        '<div class="section-header">' +
        '  <div class="section-title">' + esc(rail.title || "Catalog") + "</div>" +
        '  <div class="section-meta">' + esc(rail.addonName || rail.type || "") + "</div>" +
        "</div>" +
        '<div class="poster-rail">';
      for (let i = 0; i < items.length; i++) {
        html += renderPosterCard(items[i], i, 'data-board-rail="' + r + '" data-board-item');
      }
      html += "</div>";
    }
  } else if (!b.continueWatching || b.continueWatching.length === 0) {
    html +=
      '<div class="empty-state">' +
      "No catalog rails loaded yet. Link your Stremio account or install a Provider Pack in <b>Passport ↗</b>." +
      "</div>";
  }

  return html;
}

export function bindBoardEvents(root, ctx) {
  const { state, actions } = ctx;
  const searchInput = root.querySelector("#board-search-input");
  const searchGo = root.querySelector("#board-search-go");
  if (searchGo) {
    searchGo.onclick = () => {
      const q = searchInput ? searchInput.value.trim() : "";
      if (q) {
        actions.runGlobalSearch(q);
      } else {
        actions.switchTab("search");
      }
    };
  }

  const refreshBtn = root.querySelector("#board-refresh-btn");
  if (refreshBtn) refreshBtn.onclick = () => actions.loadBoard(true);

  const cwCards = root.querySelectorAll("[data-board-cw]");
  for (let i = 0; i < cwCards.length; i++) {
    cwCards[i].onclick = () => {
      const idx = Number(cwCards[i].getAttribute("data-board-cw"));
      const item = state.board.continueWatching[idx];
      if (item) actions.openItem(item);
    };
  }

  const railCards = root.querySelectorAll("[data-board-rail]");
  for (let i = 0; i < railCards.length; i++) {
    railCards[i].onclick = () => {
      const r = Number(railCards[i].getAttribute("data-board-rail"));
      const idx = Number(railCards[i].getAttribute("data-board-item"));
      const rail = state.board.rails[r];
      if (rail && rail.items && rail.items[idx]) {
        actions.openItem(rail.items[idx]);
      }
    };
  }
}

export function renderDiscoverView(ctx) {
  const { state } = ctx;
  const d = state.discover;
  const types = [
    { key: "movie", label: "Movies" },
    { key: "series", label: "Series" },
    { key: "anime", label: "Anime" },
  ];

  let html = '<div class="pill-strip">';
  for (let i = 0; i < types.length; i++) {
    const t = types[i];
    html +=
      '<button class="pill focusable' + (d.type === t.key ? " is-active" : "") + '" tabindex="0" data-disc-type="' + t.key + '">' +
      esc(t.label) +
      "</button>";
  }
  html += "</div>";

  // Catalog selector pills
  if (d.catalogs && d.catalogs.length > 0) {
    html += '<div class="pill-strip">';
    for (let i = 0; i < d.catalogs.length; i++) {
      const cat = d.catalogs[i];
      const cid = cat.id || cat.catalogId || String(i);
      const label = cat.name || cat.label || cid;
      html +=
        '<button class="pill focusable' + (d.catalogId === cid ? " is-active" : "") + '" tabindex="0" data-disc-cat="' + esc(cid) + '">' +
        esc(label) +
        "</button>";
    }
    html += "</div>";
  }

  // Genre filter pills
  if (d.genres && d.genres.length > 0) {
    html += '<div class="pill-strip">';
    html +=
      '<button class="pill focusable' + (!d.genre ? " is-active" : "") + '" tabindex="0" data-disc-genre="">All Genres</button>';
    for (let i = 0; i < d.genres.length; i++) {
      const g = d.genres[i];
      html +=
        '<button class="pill focusable' + (d.genre === g ? " is-active" : "") + '" tabindex="0" data-disc-genre="' + esc(g) + '">' +
        esc(g) +
        "</button>";
    }
    html += "</div>";
  }

  if (d.loading) {
    html += '<div class="empty-state">Loading Discover catalog...</div>';
  } else if (!d.items || d.items.length === 0) {
    html += '<div class="empty-state">No titles found for this catalog / genre.</div>';
  } else {
    html += '<div class="poster-grid">';
    for (let i = 0; i < d.items.length; i++) {
      html += renderPosterCard(d.items[i], i, "data-disc-item");
    }
    html += "</div>";
  }

  html +=
    '<div class="toolbar-row" style="justify-content:center;margin-top:16px;">' +
    '  <button id="disc-prev" class="btn focusable" tabindex="0"' + (d.page <= 1 ? " disabled" : "") + ">← Prev Page</button>" +
    '  <span class="status-pill">Page ' + esc(d.page) + "</span>" +
    '  <button id="disc-next" class="btn focusable" tabindex="0"' + (!d.hasMore ? " disabled" : "") + ">Next Page →</button>" +
    "</div>";

  return html;
}

export function bindDiscoverEvents(root, ctx) {
  const { state, actions } = ctx;
  const typeBtns = root.querySelectorAll("[data-disc-type]");
  for (let i = 0; i < typeBtns.length; i++) {
    typeBtns[i].onclick = () => {
      state.discover.type = typeBtns[i].getAttribute("data-disc-type");
      state.discover.catalogId = "";
      state.discover.genre = "";
      state.discover.page = 1;
      actions.loadDiscover();
    };
  }

  const catBtns = root.querySelectorAll("[data-disc-cat]");
  for (let i = 0; i < catBtns.length; i++) {
    catBtns[i].onclick = () => {
      state.discover.catalogId = catBtns[i].getAttribute("data-disc-cat");
      state.discover.page = 1;
      actions.loadDiscover();
    };
  }

  const genreBtns = root.querySelectorAll("[data-disc-genre]");
  for (let i = 0; i < genreBtns.length; i++) {
    genreBtns[i].onclick = () => {
      state.discover.genre = genreBtns[i].getAttribute("data-disc-genre");
      state.discover.page = 1;
      actions.loadDiscover();
    };
  }

  const itemCards = root.querySelectorAll("[data-disc-item]");
  for (let i = 0; i < itemCards.length; i++) {
    itemCards[i].onclick = () => {
      const idx = Number(itemCards[i].getAttribute("data-disc-item"));
      const item = state.discover.items[idx];
      if (item) actions.openItem(item);
    };
  }

  const prev = root.querySelector("#disc-prev");
  if (prev) {
    prev.onclick = () => {
      if (state.discover.page > 1) {
        state.discover.page--;
        actions.loadDiscover();
      }
    };
  }

  const next = root.querySelector("#disc-next");
  if (next) {
    next.onclick = () => {
      state.discover.page++;
      actions.loadDiscover();
    };
  }
}

export function renderSearchView(ctx) {
  const { state } = ctx;
  const s = state.search;
  const categories = ["all", "movie", "series", "anime"];

  let html =
    '<div class="toolbar-row">' +
    '  <div class="search-box">' +
    '    <span style="color:var(--cyan);font-size:20px;">⌕</span>' +
    '    <input id="search-query-input" class="search-input focusable" tabindex="0" type="text" ' +
    '      placeholder="Search titles, releases, or magnets..." value="' + esc(s.query) + '" data-enter-target="search-submit-btn" />' +
    '    <button id="search-submit-btn" class="btn btn-primary btn-sm focusable" tabindex="0">Search</button>' +
    "  </div>" +
    "</div>";

  // Top: Stremio Catalog Matches
  html +=
    '<div class="section-header">' +
    '  <div class="section-title">Stremio Catalog Matches <span class="pill-badge">' + s.catalogMatches.length + "</span></div>" +
    "</div>";

  if (s.loadingCatalog) {
    html += '<div class="empty-state">Searching Stremio catalogs...</div>';
  } else if (s.catalogMatches.length === 0) {
    html += '<div class="empty-state">' + (s.query ? "No Stremio catalog matches." : "Enter a query above to search catalogs and Direct P2P Swarm.") + "</div>";
  } else {
    html += '<div class="poster-rail">';
    for (let i = 0; i < s.catalogMatches.length; i++) {
      html += renderPosterCard(s.catalogMatches[i], i, "data-search-cat-item");
    }
    html += "</div>";
  }

  // Bottom: Direct P2P Swarm (100% Dynamic Feeds from Service!)
  html +=
    '<div class="section-header">' +
    '  <div class="section-title">Direct P2P Swarm <span class="pill-badge">' + s.swarmResults.length + "</span></div>" +
    "</div>";

  html += '<div class="pill-strip">';
  for (let i = 0; i < categories.length; i++) {
    const c = categories[i];
    html +=
      '<button class="pill focusable' + (s.category === c ? " is-active" : "") + '" tabindex="0" data-swarm-cat="' + c + '">' +
      esc(c.charAt(0).toUpperCase() + c.slice(1)) +
      "</button>";
  }
  html += "</div>";

  // Dynamic feed pills returned by swarm.search
  if (s.feeds && s.feeds.length > 0) {
    html += '<div class="pill-strip">';
    for (let i = 0; i < s.feeds.length; i++) {
      const f = s.feeds[i];
      const fKey = f.key || f.id || f.feedKey || String(i);
      const fLabel = f.label || f.name || fKey;
      const active = s.feedKey === fKey || (!s.feedKey && i === 0);
      html +=
        '<button class="pill focusable' + (active ? " is-active" : "") + '" tabindex="0" data-swarm-feed="' + esc(fKey) + '">' +
        esc(fLabel) +
        (f.count !== undefined ? ' <span class="pill-badge">' + esc(f.count) + "</span>" : "") +
        "</button>";
    }
    html += "</div>";
  }

  if (s.loadingSwarm) {
    html += '<div class="empty-state">Querying active P2P Swarm feeds...</div>';
  } else if (s.swarmResults.length === 0) {
    html += '<div class="empty-state">' + (s.query ? "No swarm results found for this feed." : "Search above or select a feed to browse P2P swarm releases.") + "</div>";
  } else {
    html += '<div class="swarm-list">';
    for (let i = 0; i < s.swarmResults.length; i++) {
      const r = s.swarmResults[i];
      const sizeStr = r.sizeText || (r.sizeBytes ? formatBytes(r.sizeBytes) : r.size || "");
      const seeds = r.seeders !== undefined ? r.seeders : r.seeds !== undefined ? r.seeds : 0;
      const peers = r.leechers !== undefined ? r.leechers : r.peers !== undefined ? r.peers : 0;
      const telemetry =
        r.telemetryHeader ||
        [
          "👤 " + seeds + " seeds",
          peers ? peers + " leech" : "",
          sizeStr ? "💾 " + sizeStr : "",
          r.feedLabel || r.feedKey || r.category || "",
        ]
          .filter(Boolean)
          .join(" · ");
      const fullTitle = r.fullReleaseTitle || r.title || r.name || "Untitled Release";
      html +=
        '<button class="swarm-row focusable-subtle focusable" tabindex="0" data-swarm-idx="' + i + '">' +
        '  <div class="swarm-row-main">' +
        '    <div class="stream-telemetry">' + esc(telemetry) + "</div>" +
        '    <div class="stream-release-title">' + esc(fullTitle) + "</div>" +
        "  </div>" +
        '  <span class="btn btn-primary btn-sm">▶ Play</span>' +
        "</button>";
    }
    html += "</div>";
  }

  return html;
}

export function bindSearchEvents(root, ctx) {
  const { state, actions } = ctx;
  const input = root.querySelector("#search-query-input");
  const submit = root.querySelector("#search-submit-btn");
  if (submit) {
    submit.onclick = () => {
      const q = input ? input.value.trim() : "";
      actions.runGlobalSearch(q);
    };
  }

  const catCards = root.querySelectorAll("[data-search-cat-item]");
  for (let i = 0; i < catCards.length; i++) {
    catCards[i].onclick = () => {
      const idx = Number(catCards[i].getAttribute("data-search-cat-item"));
      const item = state.search.catalogMatches[idx];
      if (item) actions.openItem(item);
    };
  }

  const catPills = root.querySelectorAll("[data-swarm-cat]");
  for (let i = 0; i < catPills.length; i++) {
    catPills[i].onclick = () => {
      state.search.category = catPills[i].getAttribute("data-swarm-cat");
      actions.runSwarmSearch();
    };
  }

  const feedPills = root.querySelectorAll("[data-swarm-feed]");
  for (let i = 0; i < feedPills.length; i++) {
    feedPills[i].onclick = () => {
      state.search.feedKey = feedPills[i].getAttribute("data-swarm-feed");
      actions.runSwarmSearch();
    };
  }

  const swarmRows = root.querySelectorAll("[data-swarm-idx]");
  for (let i = 0; i < swarmRows.length; i++) {
    swarmRows[i].onclick = () => {
      const idx = Number(swarmRows[i].getAttribute("data-swarm-idx"));
      const row = state.search.swarmResults[idx];
      if (row) actions.playSwarmResult(row);
    };
  }
}

export function renderLibraryView(ctx) {
  const { state } = ctx;
  const lib = state.library;
  const filters = [
    { key: "all", label: "All" },
    { key: "movie", label: "Movies" },
    { key: "series", label: "Series" },
    { key: "jellyfin", label: "Jellyfin" },
    { key: "streaming", label: "Streaming" },
    { key: "sources", label: "Sources" },
  ];

  const matchesFilter = (item) => {
    if (lib.filter === "all") return true;
    if (lib.filter === "movie" || lib.filter === "series") return item.type === lib.filter;
    return item.source === lib.filter;
  };

  const favs = (state.favorites || []).filter(matchesFilter);
  const hist = (state.history || []).filter(matchesFilter);

  let html = '<div class="pill-strip">';
  for (let i = 0; i < filters.length; i++) {
    const f = filters[i];
    html +=
      '<button class="pill focusable' + (lib.filter === f.key ? " is-active" : "") + '" tabindex="0" data-lib-filter="' + f.key + '">' +
      esc(f.label) +
      "</button>";
  }
  html += "</div>";

  html +=
    '<div class="section-header">' +
    '  <div class="section-title">Saved Favorites <span class="pill-badge">' + favs.length + "</span></div>" +
    "</div>";
  if (favs.length === 0) {
    html += '<div class="empty-state">No saved favorites match this filter yet.</div>';
  } else {
    html += '<div class="poster-grid">';
    for (let i = 0; i < favs.length; i++) {
      html += renderPosterCard(favs[i], i, "data-lib-fav");
    }
    html += "</div>";
  }

  html +=
    '<div class="section-header">' +
    '  <div class="section-title">Watch History <span class="pill-badge">' + hist.length + "</span></div>" +
    "</div>";
  if (hist.length === 0) {
    html += '<div class="empty-state">No watch history recorded for this filter yet.</div>';
  } else {
    html += '<div class="poster-grid">';
    for (let i = 0; i < hist.length; i++) {
      html += renderPosterCard(hist[i], i, "data-lib-hist");
    }
    html += "</div>";
  }

  return html;
}

export function bindLibraryEvents(root, ctx) {
  const { state, actions } = ctx;
  const filterBtns = root.querySelectorAll("[data-lib-filter]");
  for (let i = 0; i < filterBtns.length; i++) {
    filterBtns[i].onclick = () => {
      state.library.filter = filterBtns[i].getAttribute("data-lib-filter");
      actions.render();
    };
  }

  const matchesFilter = (item) => {
    if (state.library.filter === "all") return true;
    if (state.library.filter === "movie" || state.library.filter === "series") {
      return item.type === state.library.filter;
    }
    return item.source === state.library.filter;
  };

  const favs = (state.favorites || []).filter(matchesFilter);
  const hist = (state.history || []).filter(matchesFilter);

  const favCards = root.querySelectorAll("[data-lib-fav]");
  for (let i = 0; i < favCards.length; i++) {
    favCards[i].onclick = () => {
      const idx = Number(favCards[i].getAttribute("data-lib-fav"));
      if (favs[idx]) actions.openItem(favs[idx]);
    };
  }

  const histCards = root.querySelectorAll("[data-lib-hist]");
  for (let i = 0; i < histCards.length; i++) {
    histCards[i].onclick = () => {
      const idx = Number(histCards[i].getAttribute("data-lib-hist"));
      if (hist[idx]) actions.openItem(hist[idx]);
    };
  }
}
