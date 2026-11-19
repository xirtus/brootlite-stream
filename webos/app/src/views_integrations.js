// Integration Views: Jellyfin, Streaming, Sources (Addonless Direct Video), and Passport
import { renderPosterCard, renderVideoCard } from "./components.js";
import { generateQrSvg } from "./qr.js";
import { esc } from "./utils.js";

// ─────────────────────────────────────────────────────────────────────────────
// 3. JELLYFIN
// ─────────────────────────────────────────────────────────────────────────────

export function renderJellyfinView(ctx) {
  const { state } = ctx;
  const jf = state.jellyfin;
  const servers = jf.servers || [];
  const activeServer = servers.find((s) => s.id === jf.activeServerId) || servers[0] || null;

  // Strictly isolated per-server pills + [+ Add Server]
  let html = '<div class="pill-strip">';
  for (let i = 0; i < servers.length; i++) {
    const srv = servers[i];
    const isActive = activeServer && srv.id === activeServer.id;
    html +=
      '<button class="pill focusable' + (isActive ? " is-active" : "") + '" tabindex="0" data-jf-server="' + esc(srv.id) + '">' +
      '  <span class="status-dot" style="width:7px;height:7px;"></span> ' +
      esc(srv.name || srv.url || "Server " + (i + 1)) +
      "</button>";
  }
  html +=
    '<button id="jf-add-server-btn" class="pill focusable" tabindex="0" style="border-color:rgba(56,189,248,0.45);color:var(--cyan);">+ Add Server</button>';
  if (activeServer) {
    html +=
      '<button id="jf-remove-server-btn" class="pill focusable" tabindex="0" style="margin-left:auto;color:var(--rose);">Remove Server</button>';
  }
  html += "</div>";

  if (!activeServer) {
    return (
      html +
      '<div class="empty-state">' +
      "No Jellyfin servers connected yet. Click <b>+ Add Server</b> to scan your LAN (UDP 7359), pair via 6-Digit QuickConnect, or sign in with Username & Password." +
      "</div>" +
      renderJellyfinModal(jf)
    );
  }

  // Server Search + Views + Sort
  const views = [
    { key: "overview", label: "Overview" },
    { key: "movies", label: "Movies" },
    { key: "series", label: "TV Shows" },
  ];
  const sorts = [
    { key: "DateCreated", label: "Recently Added" },
    { key: "SortName", label: "A–Z" },
    { key: "CommunityRating", label: "Top Rated" },
    { key: "PremiereDate", label: "Release Date" },
  ];

  html +=
    '<div class="toolbar-row">' +
    '  <div class="search-box">' +
    '    <span style="color:var(--cyan);font-size:20px;">⌕</span>' +
    '    <input id="jf-search-input" class="search-input focusable" tabindex="0" type="text" ' +
    '      placeholder="Search ' + esc(activeServer.name || "server") + '..." value="' + esc(jf.query) + '" data-enter-target="jf-search-go" />' +
    '    <button id="jf-search-go" class="btn btn-primary btn-sm focusable" tabindex="0">Search</button>' +
    "  </div>" +
    "</div>";

  html += '<div class="pill-strip">';
  for (let i = 0; i < views.length; i++) {
    const v = views[i];
    html +=
      '<button class="pill focusable' + (jf.view === v.key ? " is-active" : "") + '" tabindex="0" data-jf-view="' + v.key + '">' +
      esc(v.label) +
      "</button>";
  }
  for (let i = 0; i < sorts.length; i++) {
    const s = sorts[i];
    html +=
      '<button class="pill focusable' + (jf.sort === s.key ? " is-active" : "") + '" tabindex="0" data-jf-sort="' + s.key + '">' +
      esc(s.label) +
      "</button>";
  }
  html += "</div>";

  if (jf.genres && jf.genres.length > 0) {
    html += '<div class="pill-strip">';
    html +=
      '<button class="pill focusable' + (!jf.genre ? " is-active" : "") + '" tabindex="0" data-jf-genre="">All Genres</button>';
    for (let i = 0; i < jf.genres.length; i++) {
      const g = jf.genres[i];
      html +=
        '<button class="pill focusable' + (jf.genre === g ? " is-active" : "") + '" tabindex="0" data-jf-genre="' + esc(g) + '">' +
        esc(g) +
        "</button>";
    }
    html += "</div>";
  }

  if (jf.loading) {
    html += '<div class="empty-state">Browsing Jellyfin library...</div>';
  } else if (!jf.items || jf.items.length === 0) {
    html += '<div class="empty-state">No items found in this Jellyfin view.</div>';
  } else {
    html += '<div class="poster-grid">';
    for (let i = 0; i < jf.items.length; i++) {
      html += renderPosterCard(jf.items[i], i, "data-jf-item");
    }
    html += "</div>";
  }

  html += renderJellyfinModal(jf);
  return html;
}

function renderJellyfinModal(jf) {
  if (!jf.addModalOpen) return "";
  const lanList = jf.lanServers || [];
  const qc = jf.quickConnect;

  let html =
    '<div class="modal-backdrop">' +
    '  <div class="modal-card">' +
    '    <div class="panel-head">' +
    '      <div class="panel-title">Connect Jellyfin Server</div>' +
    '      <button id="jf-modal-close" class="btn btn-sm focusable" tabindex="0">✕</button>' +
    "    </div>" +
    '    <div class="form-row" style="justify-content:space-between;">' +
    '      <span class="muted">1-Click UDP 7359 Local Network Discovery</span>' +
    '      <button id="jf-lan-scan-btn" class="btn btn-primary btn-sm focusable" tabindex="0" data-autofocus="1">' +
    (jf.scanningLan ? "Scanning LAN..." : "📡 Scan LAN (UDP 7359)") +
    "</button>" +
    "    </div>";

  if (lanList.length > 0) {
    html += '<div class="pill-strip" style="margin-bottom:4px;">';
    for (let i = 0; i < lanList.length; i++) {
      const srv = lanList[i];
      html +=
        '<button class="pill focusable" tabindex="0" data-jf-lan-pick="' + i + '">' +
        esc(srv.name || "Jellyfin") +
        ' <span class="pill-badge">' + esc(srv.address || srv.url) + "</span>" +
        "</button>";
    }
    html += "</div>";
  }

  html +=
    '    <div class="form-row">' +
    '      <input id="jf-url-input" class="text-input focusable" tabindex="0" type="text" placeholder="http://192.168.1.50:8096" value="' + esc(jf.formUrl) + '" />' +
    '      <input id="jf-name-input" class="text-input focusable" tabindex="0" type="text" placeholder="Server Label (optional)" value="' + esc(jf.formName) + '" style="max-width:240px;" />' +
    "    </div>";

  // 6-Digit QuickConnect Box
  html +=
    '    <div class="glass-panel" style="padding:16px 18px;">' +
    '      <div class="panel-head">' +
    '        <div style="font-size:18px;font-weight:600;">6-Digit QuickConnect</div>' +
    '        <button id="jf-qc-start-btn" class="btn btn-emerald btn-sm focusable" tabindex="0">' +
    (qc && qc.code ? "↻ Regenerate Code" : "⚡ Generate QuickConnect Code") +
    "</button>" +
    "      </div>";
  if (qc && qc.code) {
    html +=
      '      <div style="display:flex;align-items:center;justify-content:space-between;gap:16px;">' +
      '        <div>' +
      '          <div class="muted" style="font-size:15px;">Enter this code in Jellyfin Dashboard → Quick Connect:</div>' +
      '          <div class="qr-code-big">' + esc(qc.code) + "</div>" +
      "        </div>" +
      '        <span class="badge-chip badge-cyan">Waiting for approval...</span>' +
      "      </div>";
  }
  html += "    </div>";

  // Username / Password Login
  html +=
    '    <div class="glass-panel" style="padding:16px 18px;">' +
    '      <div style="font-size:18px;font-weight:600;">Username & Password</div>' +
    '      <div class="form-row">' +
    '        <input id="jf-user-input" class="text-input focusable" tabindex="0" type="text" placeholder="Username" value="' + esc(jf.formUser) + '" />' +
    '        <input id="jf-pass-input" class="text-input focusable" tabindex="0" type="password" placeholder="Password" value="' + esc(jf.formPass) + '" data-enter-target="jf-login-btn" />' +
    '        <button id="jf-login-btn" class="btn btn-primary focusable" tabindex="0">Sign In</button>' +
    "      </div>" +
    "    </div>" +
    "  </div>" +
    "</div>";

  return html;
}

export function bindJellyfinEvents(root, ctx) {
  const { state, actions } = ctx;
  const jf = state.jellyfin;

  const srvBtns = root.querySelectorAll("[data-jf-server]");
  for (let i = 0; i < srvBtns.length; i++) {
    srvBtns[i].onclick = () => {
      jf.activeServerId = srvBtns[i].getAttribute("data-jf-server");
      actions.loadJellyfinBrowse();
    };
  }

  const addBtn = root.querySelector("#jf-add-server-btn");
  if (addBtn) {
    addBtn.onclick = () => {
      jf.addModalOpen = true;
      actions.render();
    };
  }

  const remBtn = root.querySelector("#jf-remove-server-btn");
  if (remBtn) {
    remBtn.onclick = () => actions.removeActiveJellyfinServer();
  }

  const searchInput = root.querySelector("#jf-search-input");
  const searchGo = root.querySelector("#jf-search-go");
  if (searchGo) {
    searchGo.onclick = () => {
      jf.query = searchInput ? searchInput.value.trim() : "";
      actions.loadJellyfinBrowse();
    };
  }

  const viewBtns = root.querySelectorAll("[data-jf-view]");
  for (let i = 0; i < viewBtns.length; i++) {
    viewBtns[i].onclick = () => {
      jf.view = viewBtns[i].getAttribute("data-jf-view");
      actions.loadJellyfinBrowse();
    };
  }

  const sortBtns = root.querySelectorAll("[data-jf-sort]");
  for (let i = 0; i < sortBtns.length; i++) {
    sortBtns[i].onclick = () => {
      jf.sort = sortBtns[i].getAttribute("data-jf-sort");
      actions.loadJellyfinBrowse();
    };
  }

  const genreBtns = root.querySelectorAll("[data-jf-genre]");
  for (let i = 0; i < genreBtns.length; i++) {
    genreBtns[i].onclick = () => {
      jf.genre = genreBtns[i].getAttribute("data-jf-genre");
      actions.loadJellyfinBrowse();
    };
  }

  const items = root.querySelectorAll("[data-jf-item]");
  for (let i = 0; i < items.length; i++) {
    items[i].onclick = () => {
      const idx = Number(items[i].getAttribute("data-jf-item"));
      const item = jf.items[idx];
      if (item) {
        if (item.type === "series") actions.openItem(item);
        else actions.playJellyfinItem(item);
      }
    };
  }

  // Modal bindings
  if (jf.addModalOpen) {
    const urlInput = root.querySelector("#jf-url-input");
    const nameInput = root.querySelector("#jf-name-input");
    const userInput = root.querySelector("#jf-user-input");
    const passInput = root.querySelector("#jf-pass-input");
    const syncFields = () => {
      if (urlInput) jf.formUrl = urlInput.value.trim();
      if (nameInput) jf.formName = nameInput.value.trim();
      if (userInput) jf.formUser = userInput.value.trim();
      if (passInput) jf.formPass = passInput.value;
    };

    const closeBtn = root.querySelector("#jf-modal-close");
    if (closeBtn) {
      closeBtn.onclick = () => {
        jf.addModalOpen = false;
        actions.stopJellyfinQuickConnectPoll();
        actions.render();
      };
    }

    const scanBtn = root.querySelector("#jf-lan-scan-btn");
    if (scanBtn) {
      scanBtn.onclick = () => {
        syncFields();
        actions.scanJellyfinLan();
      };
    }

    const lanPicks = root.querySelectorAll("[data-jf-lan-pick]");
    for (let i = 0; i < lanPicks.length; i++) {
      lanPicks[i].onclick = () => {
        const idx = Number(lanPicks[i].getAttribute("data-jf-lan-pick"));
        const picked = jf.lanServers[idx];
        if (picked) {
          jf.formUrl = picked.address || picked.url || "";
          jf.formName = picked.name || "";
          actions.render();
        }
      };
    }

    const qcBtn = root.querySelector("#jf-qc-start-btn");
    if (qcBtn) {
      qcBtn.onclick = () => {
        syncFields();
        actions.startJellyfinQuickConnect();
      };
    }

    const loginBtn = root.querySelector("#jf-login-btn");
    if (loginBtn) {
      loginBtn.onclick = () => {
        syncFields();
        actions.loginJellyfinPassword();
      };
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. STREAMING (YouTube · Twitch · Odysee · Rumble)
// ─────────────────────────────────────────────────────────────────────────────

export function renderStreamingView(ctx) {
  const { state } = ctx;
  const st = state.streaming;
  const platforms = [
    { key: "youtube", label: "YouTube" },
    { key: "twitch", label: "Twitch" },
    { key: "odysee", label: "Odysee" },
    { key: "rumble", label: "Rumble" },
  ];
  const sections = [
    { key: "home", label: "Home" },
    { key: "subscriptions", label: "Subscriptions" },
    { key: "trending", label: "Trending" },
    { key: "live", label: "Live" },
    { key: "search", label: "Search" },
  ];

  let html = '<div class="pill-strip">';
  for (let i = 0; i < platforms.length; i++) {
    const p = platforms[i];
    html +=
      '<button class="pill focusable' + (st.platform === p.key ? " is-active" : "") + '" tabindex="0" data-st-plat="' + p.key + '">' +
      esc(p.label) +
      "</button>";
  }
  if (st.platform === "youtube" || st.platform === "twitch") {
    if (st.authenticated) {
      html +=
        '<button id="st-auth-logout" class="pill focusable" tabindex="0" style="margin-left:auto;border-color:rgba(16,185,129,0.45);color:#A7F3D0;">✓ Signed In (' +
        esc(st.user || st.platform) +
        ") · Sign Out</button>";
    } else {
      html +=
        '<button id="st-auth-open" class="pill focusable" tabindex="0" style="margin-left:auto;border-color:rgba(56,189,248,0.45);color:var(--cyan);">📱 TV QR Sign-In</button>';
    }
  }
  html += "</div>";

  html +=
    '<div class="toolbar-row">' +
    '  <div class="pill-strip" style="margin-bottom:0;">';
  for (let i = 0; i < sections.length; i++) {
    const sec = sections[i];
    html +=
      '<button class="pill focusable' + (st.section === sec.key ? " is-active" : "") + '" tabindex="0" data-st-sec="' + sec.key + '">' +
      esc(sec.label) +
      "</button>";
  }
  html +=
    "  </div>" +
    '  <div class="search-box" style="min-width:280px;">' +
    '    <input id="st-search-input" class="search-input focusable" tabindex="0" type="text" ' +
    '      placeholder="Search ' + esc(st.platform) + '..." value="' + esc(st.query) + '" data-enter-target="st-search-go" />' +
    '    <button id="st-search-go" class="btn btn-primary btn-sm focusable" tabindex="0">Go</button>' +
    "  </div>" +
    "</div>";

  if (st.loading) {
    html += '<div class="empty-state">Loading ' + esc(st.platform) + " feed...</div>";
  } else if (!st.items || st.items.length === 0) {
    html += '<div class="empty-state">No videos returned for this feed.</div>';
  } else {
    html += '<div class="video-grid">';
    for (let i = 0; i < st.items.length; i++) {
      html += renderVideoCard(st.items[i], i, "data-st-item");
    }
    html += "</div>";
  }

  // TV QR Auth Modal
  if (st.authModal && st.authModal.open) {
    const a = st.authModal;
    const qrTarget = a.qrPayload || (a.verificationUrl ? a.verificationUrl + (a.userCode ? "?user_code=" + encodeURIComponent(a.userCode) : "") : "");
    html +=
      '<div class="modal-backdrop">' +
      '  <div class="modal-card">' +
      '    <div class="panel-head">' +
      '      <div class="panel-title">' + esc(st.platform.toUpperCase()) + " TV QR Sign-In</div>" +
      '      <button id="st-auth-close" class="btn btn-sm focusable" tabindex="0" data-autofocus="1">✕</button>' +
      "    </div>";
    if (a.loading) {
      html += '<div class="empty-state">Requesting device activation code...</div>';
    } else if (a.userCode || a.verificationUrl) {
      html +=
        '<div class="qr-box">' +
        '  <div class="qr-svg-wrap">' + generateQrSvg(qrTarget, 172) + "</div>" +
        '  <div style="display:flex;flex-direction:column;gap:10px;">' +
        '    <div class="muted">1. Scan QR or visit on your phone/PC:</div>' +
        '    <div class="mono" style="font-size:19px;color:var(--text-primary);word-break:break-all;">' + esc(a.verificationUrl || "") + "</div>" +
        '    <div class="muted">2. Enter activation code:</div>' +
        '    <div class="qr-code-big">' + esc(a.userCode || "") + "</div>" +
        '    <span class="badge-chip badge-cyan" style="align-self:flex-start;">Waiting for confirmation...</span>' +
        "  </div>" +
        "</div>";
    } else {
      html += '<div class="empty-state">Unable to initialize TV sign-in for ' + esc(st.platform) + ".</div>";
    }
    html += "  </div></div>";
  }

  return html;
}

export function bindStreamingEvents(root, ctx) {
  const { state, actions } = ctx;
  const st = state.streaming;

  const platBtns = root.querySelectorAll("[data-st-plat]");
  for (let i = 0; i < platBtns.length; i++) {
    platBtns[i].onclick = () => {
      st.platform = platBtns[i].getAttribute("data-st-plat");
      actions.loadStreamingFeed();
    };
  }

  const secBtns = root.querySelectorAll("[data-st-sec]");
  for (let i = 0; i < secBtns.length; i++) {
    secBtns[i].onclick = () => {
      st.section = secBtns[i].getAttribute("data-st-sec");
      actions.loadStreamingFeed();
    };
  }

  const searchInput = root.querySelector("#st-search-input");
  const searchGo = root.querySelector("#st-search-go");
  if (searchGo) {
    searchGo.onclick = () => {
      st.query = searchInput ? searchInput.value.trim() : "";
      if (st.query) st.section = "search";
      actions.loadStreamingFeed();
    };
  }

  const cards = root.querySelectorAll("[data-st-item]");
  for (let i = 0; i < cards.length; i++) {
    cards[i].onclick = () => {
      const idx = Number(cards[i].getAttribute("data-st-item"));
      if (st.items[idx]) actions.playStreamingItem(st.items[idx]);
    };
  }

  const openAuth = root.querySelector("#st-auth-open");
  if (openAuth) openAuth.onclick = () => actions.startStreamingAuth();

  const logoutAuth = root.querySelector("#st-auth-logout");
  if (logoutAuth) logoutAuth.onclick = () => actions.logoutStreamingAuth();

  const closeAuth = root.querySelector("#st-auth-close");
  if (closeAuth) closeAuth.onclick = () => actions.closeStreamingAuth();
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. SOURCES (Addonless Direct Video Sources — 100% Dynamic from Service!)
// ─────────────────────────────────────────────────────────────────────────────

export function renderSourcesView(ctx) {
  const { state } = ctx;
  const src = state.sources;
  const providers = src.providers || [];
  const defaultSections = [
    { key: "trending", label: "Trending" },
    { key: "top_rated", label: "Top Rated" },
    { key: "hd", label: "HD / 4K" },
    { key: "live", label: "Live" },
    { key: "search", label: "Search" },
  ];
  const sections = src.sections && src.sections.length > 0 ? src.sections : defaultSections;

  // Dynamic Provider Pills from sources.catalog
  let html = '<div class="pill-strip">';
  if (providers.length === 0) {
    html += '<span class="muted">No direct providers loaded yet (check Passport Provider Pack).</span>';
  } else {
    for (let i = 0; i < providers.length; i++) {
      const p = providers[i];
      const pid = p.id || p.providerId || String(i);
      const label = p.label || p.name || pid;
      const active = src.providerId === pid || (!src.providerId && i === 0);
      html +=
        '<button class="pill focusable' + (active ? " is-active" : "") + '" tabindex="0" data-src-provider="' + esc(pid) + '">' +
        esc(label) +
        "</button>";
    }
  }
  html += "</div>";

  // Section Pills + Search
  html +=
    '<div class="toolbar-row">' +
    '  <div class="pill-strip" style="margin-bottom:0;">';
  for (let i = 0; i < sections.length; i++) {
    const sec = sections[i];
    const skey = typeof sec === "string" ? sec : sec.key || sec.id;
    const slabel = typeof sec === "string" ? sec : sec.label || sec.name || skey;
    html +=
      '<button class="pill focusable' + (src.section === skey ? " is-active" : "") + '" tabindex="0" data-src-section="' + esc(skey) + '">' +
      esc(slabel) +
      "</button>";
  }
  html +=
    "  </div>" +
    '  <div class="search-box" style="min-width:280px;">' +
    '    <input id="src-search-input" class="search-input focusable" tabindex="0" type="text" ' +
    '      placeholder="Search selected direct source..." value="' + esc(src.query) + '" data-enter-target="src-search-go" />' +
    '    <button id="src-search-go" class="btn btn-primary btn-sm focusable" tabindex="0">Search</button>' +
    "  </div>" +
    "</div>";

  if (src.loading) {
    html += '<div class="empty-state">Loading direct source catalog...</div>';
  } else if (!src.items || src.items.length === 0) {
    html += '<div class="empty-state">No videos returned from this source.</div>';
  } else {
    html += '<div class="video-grid">';
    for (let i = 0; i < src.items.length; i++) {
      html += renderVideoCard(src.items[i], i, "data-src-item");
    }
    html += "</div>";
  }

  html +=
    '<div class="toolbar-row" style="justify-content:center;margin-top:16px;">' +
    '  <button id="src-prev" class="btn focusable" tabindex="0"' + (src.page <= 1 ? " disabled" : "") + ">← Prev</button>" +
    '  <span class="status-pill">Page ' + esc(src.page) + "</span>" +
    '  <button id="src-next" class="btn focusable" tabindex="0"' + (!src.hasMore ? " disabled" : "") + ">Next →</button>" +
    "</div>";

  return html;
}

export function bindSourcesEvents(root, ctx) {
  const { state, actions } = ctx;
  const src = state.sources;

  const provBtns = root.querySelectorAll("[data-src-provider]");
  for (let i = 0; i < provBtns.length; i++) {
    provBtns[i].onclick = () => {
      src.providerId = provBtns[i].getAttribute("data-src-provider");
      src.page = 1;
      actions.loadSourcesCatalog();
    };
  }

  const secBtns = root.querySelectorAll("[data-src-section]");
  for (let i = 0; i < secBtns.length; i++) {
    secBtns[i].onclick = () => {
      src.section = secBtns[i].getAttribute("data-src-section");
      src.page = 1;
      actions.loadSourcesCatalog();
    };
  }

  const searchInput = root.querySelector("#src-search-input");
  const searchGo = root.querySelector("#src-search-go");
  if (searchGo) {
    searchGo.onclick = () => {
      src.query = searchInput ? searchInput.value.trim() : "";
      if (src.query) src.section = "search";
      src.page = 1;
      actions.loadSourcesCatalog();
    };
  }

  const cards = root.querySelectorAll("[data-src-item]");
  for (let i = 0; i < cards.length; i++) {
    cards[i].onclick = () => {
      const idx = Number(cards[i].getAttribute("data-src-item"));
      if (src.items[idx]) actions.playSourceItem(src.items[idx]);
    };
  }

  const prev = root.querySelector("#src-prev");
  if (prev) {
    prev.onclick = () => {
      if (src.page > 1) {
        src.page--;
        actions.loadSourcesCatalog();
      }
    };
  }

  const next = root.querySelector("#src-next");
  if (next) {
    next.onclick = () => {
      src.page++;
      actions.loadSourcesCatalog();
    };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 8. PASSPORT ↗
// ─────────────────────────────────────────────────────────────────────────────

export function renderPassportView(ctx) {
  const { state } = ctx;
  const pack = state.pack || {};
  const boot = state.boot || {};
  const yt = state.youtubeOptions || {};
  const mp = state.multipass || {};
  const stremio = state.stremio || {};

  const counts = pack.counts || {};
  const countChips = Object.keys(counts)
    .map((k) => k + ": " + counts[k])
    .join(" · ");

  let html = '<div class="glass-grid-2">';

  // 1. Provider Pack Quick-Unlock Card
  html +=
    '<div class="glass-panel">' +
    '  <div class="panel-head">' +
    '    <div class="panel-title">Provider Pack Quick-Unlock</div>' +
    '    <span class="badge-chip ' + (pack.installed ? "badge-emerald" : "badge-indigo") + '">' +
    esc(pack.name || (pack.installed ? "Custom Pack Active" : "Default Public Pack")) +
    "    </span>" +
    "  </div>" +
    '  <div class="panel-desc">' +
    (countChips ? esc(countChips) : "Import a signed or local provider_pack.json to extend Swarm feeds & Direct Sources dynamically.") +
    "  </div>" +
    '  <div class="form-row">' +
    '    <input id="pack-source-input" class="text-input focusable" tabindex="0" type="text" ' +
    '      placeholder="https://... or /media/.../provider_pack.json" value="' + esc(state.passportPackInput || "") + '" data-enter-target="pack-import-btn" />' +
    '    <button id="pack-import-btn" class="btn btn-primary focusable" tabindex="0">+ Install / Import Pack</button>' +
    "  </div>" +
    '  <div class="form-row" style="justify-content:space-between;">' +
    '    <label id="pack-file-drop" class="btn focusable" tabindex="0" style="cursor:pointer;">' +
    '      📁 Pick / Drop .json File' +
    '      <input id="pack-file-input" type="file" accept=".json,application/json" style="display:none;" />' +
    "    </label>" +
    '    <button id="pack-reset-btn" class="btn btn-danger focusable" tabindex="0">Reset Pack</button>' +
    "  </div>" +
    "</div>";

  // 2. Leeloo Multipass v2 Lockbox
  const mpQr = mp.qrUrl || mp.qrPayload || "";
  html +=
    '<div class="glass-panel">' +
    '  <div class="panel-head">' +
    '    <div class="panel-title">Leeloo Multipass v2 Lockbox</div>' +
    '    <span class="badge-chip ' + (mp.paired ? "badge-emerald" : "badge-cyan") + '">' +
    (mp.paired ? "Paired · P-256 ECDH" : "Zero-Knowledge Vault") +
    "    </span>" +
    "  </div>" +
    '  <div class="panel-desc">Pair your mobile or desktop Multipass via #pk P-256 ECDH QR to sync addons, accounts & packs.</div>' +
    (mpQr
      ? '<div class="qr-box">' +
        '  <div class="qr-svg-wrap" style="width:140px;height:140px;min-width:140px;">' + generateQrSvg(mpQr, 118) + "</div>" +
        '  <div style="display:flex;flex-direction:column;gap:8px;min-width:0;">' +
        '    <div class="mono" style="font-size:13px;color:var(--cyan);word-break:break-all;">#pk ECDH Session Active</div>' +
        '    <div class="muted" style="font-size:14px;">Scan with BrootLite Mobile or Desktop to transfer encrypted vault.</div>' +
        "  </div>" +
        "</div>"
      : "") +
    '  <div class="form-row">' +
    '    <button id="mp-pair-btn" class="btn btn-primary focusable" tabindex="0">' + (mpQr ? "↻ Refresh #pk QR" : "🔐 Pair via #pk QR") + "</button>" +
    '    <button id="mp-sync-btn" class="btn btn-emerald focusable" tabindex="0">↻ Sync Vault</button>' +
    "  </div>" +
    "</div>";

  // 3. Stremio QR Link
  const stremioQr = stremio.qrLink || stremio.verificationUrl || "";
  html +=
    '<div class="glass-panel">' +
    '  <div class="panel-head">' +
    '    <div class="panel-title">Stremio Account Link</div>' +
    '    <span class="badge-chip ' + (stremio.authenticated ? "badge-emerald" : "badge-indigo") + '">' +
    (stremio.authenticated ? esc(stremio.email || "Linked") : "Not Linked") +
    "    </span>" +
    "  </div>" +
    '  <div class="panel-desc">Sync your Stremio library, Watchlist, and installed addons via TV QR code.</div>' +
    (stremioQr && !stremio.authenticated
      ? '<div class="qr-box">' +
        '  <div class="qr-svg-wrap" style="width:140px;height:140px;min-width:140px;">' + generateQrSvg(stremioQr, 118) + "</div>" +
        '  <div style="display:flex;flex-direction:column;gap:6px;">' +
        '    <div class="qr-code-big" style="font-size:28px;">' + esc(stremio.code || "") + "</div>" +
        '    <div class="mono muted" style="font-size:13px;word-break:break-all;">' + esc(stremioQr) + "</div>" +
        "  </div>" +
        "</div>"
      : "") +
    '  <div class="form-row">' +
    (stremio.authenticated
      ? '<button id="stremio-sync-btn" class="btn btn-primary focusable" tabindex="0">↻ Sync Addons</button>' +
        '<button id="stremio-logout-btn" class="btn btn-danger focusable" tabindex="0">Unlink Stremio</button>'
      : '<button id="stremio-qr-btn" class="btn btn-primary focusable" tabindex="0">📱 Link via Stremio QR</button>') +
    "  </div>" +
    "</div>";

  // 4. YouTube Enhancements & Live Service Telemetry
  const ytToggles = [
    { key: "sponsorBlock", label: "SponsorBlock Auto-Skip", desc: "Skip sponsor & intro segments with BACK undo" },
    { key: "watchHistory", label: "Watch History Sync", desc: "Track resume timestamps locally & across servers" },
    { key: "dislikes", label: "ReturnYouTubeDislike", desc: "Display 👍 / 👎 community ratios on cards" },
    { key: "deArrow", label: "DeArrow Clean Titles", desc: "Replace clickbait titles & thumbnails" },
  ];

  html +=
    '<div class="glass-panel">' +
    '  <div class="panel-head">' +
    '    <div class="panel-title">YouTube Enhancements & Service</div>' +
    '    <span class="badge-chip badge-cyan">' +
    esc((boot.edition || "webOS") + (boot.ramMb ? " · " + boot.ramMb + " MB RAM" : "")) +
    "    </span>" +
    "  </div>" +
    '  <div style="display:flex;flex-direction:column;gap:10px;">';
  for (let i = 0; i < ytToggles.length; i++) {
    const t = ytToggles[i];
    const isOn = yt[t.key] !== false;
    html +=
      '<button class="toggle-row focusable" tabindex="0" data-yt-toggle="' + t.key + '">' +
      '  <div style="text-align:left;">' +
      '    <div style="font-size:17px;font-weight:600;">' + esc(t.label) + "</div>" +
      '    <div class="muted" style="font-size:14px;">' + esc(t.desc) + "</div>" +
      "  </div>" +
      '  <div class="toggle-switch' + (isOn ? " is-on" : "") + '"><div class="toggle-knob"></div></div>' +
      "</button>";
  }
  html += "  </div></div>";

  html += "</div>";
  return html;
}

export function bindPassportEvents(root, ctx) {
  const { state, actions } = ctx;

  const packInput = root.querySelector("#pack-source-input");
  const packImportBtn = root.querySelector("#pack-import-btn");
  if (packImportBtn) {
    packImportBtn.onclick = () => {
      const val = packInput ? packInput.value.trim() : "";
      state.passportPackInput = val;
      if (val) actions.importPack(val);
    };
  }

  const fileInput = root.querySelector("#pack-file-input");
  if (fileInput) {
    fileInput.onchange = (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => {
        actions.importPack(String(reader.result || ""));
      };
      reader.readAsText(file);
    };
  }

  const dropLabel = root.querySelector("#pack-file-drop");
  if (dropLabel) {
    dropLabel.ondragover = (e) => e.preventDefault();
    dropLabel.ondrop = (e) => {
      e.preventDefault();
      const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (!file) return;
      const reader = new FileReader();
      reader.onload = () => actions.importPack(String(reader.result || ""));
      reader.readAsText(file);
    };
  }

  const resetPackBtn = root.querySelector("#pack-reset-btn");
  if (resetPackBtn) resetPackBtn.onclick = () => actions.clearPack();

  const mpPair = root.querySelector("#mp-pair-btn");
  if (mpPair) mpPair.onclick = () => actions.startMultipassPair();

  const mpSync = root.querySelector("#mp-sync-btn");
  if (mpSync) mpSync.onclick = () => actions.syncMultipass();

  const stremioQr = root.querySelector("#stremio-qr-btn");
  if (stremioQr) stremioQr.onclick = () => actions.startStremioQr();

  const stremioSync = root.querySelector("#stremio-sync-btn");
  if (stremioSync) stremioSync.onclick = () => actions.syncStremioAddons();

  const stremioLogout = root.querySelector("#stremio-logout-btn");
  if (stremioLogout) stremioLogout.onclick = () => actions.logoutStremio();

  const toggles = root.querySelectorAll("[data-yt-toggle]");
  for (let i = 0; i < toggles.length; i++) {
    toggles[i].onclick = () => {
      const key = toggles[i].getAttribute("data-yt-toggle");
      actions.toggleYoutubeOption(key);
    };
  }
}
