// Main Entry Point for BrootLite LG webOS 10-Foot TV Application
import { rpc, rpcBridge, resolveMediaUrl } from "./rpc.js";
import { AudioDspEngine } from "./audio_dsp.js";
import { SpatialNavigator } from "./spatial_nav.js";
import { CinemaPlayer } from "./player.js";
import {
  renderDetailOverlay,
  bindDetailEvents,
  loadDetailData,
  fetchDetailStreamsData,
} from "./detail.js";
import {
  renderBoardView,
  bindBoardEvents,
  renderDiscoverView,
  bindDiscoverEvents,
  renderSearchView,
  bindSearchEvents,
  renderLibraryView,
  bindLibraryEvents,
} from "./views_primary.js";
import {
  renderJellyfinView,
  bindJellyfinEvents,
  renderStreamingView,
  bindStreamingEvents,
  renderSourcesView,
  bindSourcesEvents,
  renderPassportView,
  bindPassportEvents,
} from "./views_integrations.js";
import { esc, normalizeItem } from "./utils.js";

const TABS = [
  { key: "board", label: "Board" },
  { key: "discover", label: "Discover" },
  { key: "jellyfin", label: "Jellyfin" },
  { key: "streaming", label: "Streaming" },
  { key: "search", label: "Search" },
  { key: "sources", label: "Sources" },
  { key: "library", label: "Library" },
  { key: "passport", label: "Passport ↗" },
];

const state = {
  activeTab: "board",
  boot: { edition: "webOS", ramMb: "", connected: false },
  pack: {},
  favorites: [],
  history: [],
  youtubeOptions: {
    sponsorBlock: true,
    watchHistory: true,
    dislikes: true,
    deArrow: false,
  },
  board: {
    loading: true,
    continueWatching: [],
    rails: [],
  },
  discover: {
    loading: false,
    loaded: false,
    type: "movie",
    catalogId: "",
    genre: "",
    page: 1,
    hasMore: false,
    catalogs: [],
    genres: [],
    items: [],
  },
  jellyfin: {
    loading: false,
    loaded: false,
    servers: [],
    activeServerId: "",
    view: "overview",
    sort: "DateCreated",
    genre: "",
    query: "",
    genres: [],
    items: [],
    addModalOpen: false,
    scanningLan: false,
    lanServers: [],
    formUrl: "",
    formName: "",
    formUser: "",
    formPass: "",
    quickConnect: null,
  },
  streaming: {
    loading: false,
    loaded: false,
    platform: "youtube",
    section: "home",
    query: "",
    authenticated: false,
    user: "",
    items: [],
    authModal: null,
  },
  search: {
    query: "",
    category: "all",
    feedKey: "",
    loadingCatalog: false,
    loadingSwarm: false,
    catalogMatches: [],
    feeds: [],
    swarmResults: [],
  },
  sources: {
    loading: false,
    loaded: false,
    providerId: "",
    section: "trending",
    query: "",
    page: 1,
    hasMore: false,
    providers: [],
    sections: [],
    items: [],
  },
  library: {
    filter: "all",
  },
  multipass: {
    paired: false,
    qrUrl: "",
    sessionId: "",
  },
  stremio: {
    authenticated: false,
    email: "",
    qrLink: "",
    code: "",
  },
  passportPackInput: "",
  detail: { open: false },
  toast: "",
};

const audioDsp = new AudioDspEngine();
let toastTimer = null;
let stremioPollTimer = null;
let multipassPollTimer = null;
let jfQcPollTimer = null;
let streamAuthPollTimer = null;

function showToast(msg) {
  state.toast = msg;
  renderToastOnly();
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    state.toast = "";
    renderToastOnly();
  }, 3400);
}

function renderToastOnly() {
  let el = document.getElementById("broot-toast");
  if (!state.toast) {
    if (el && el.parentNode) el.parentNode.removeChild(el);
    return;
  }
  if (!el) {
    el = document.createElement("div");
    el.id = "broot-toast";
    el.className = "toast-banner";
    document.body.appendChild(el);
  }
  el.textContent = state.toast;
}

const nav = new SpatialNavigator({
  onBack: () => handleBackHierarchy(),
  onMediaKey: (action) => player.handleMediaKey(action),
  onUserActivity: () => player.onUserActivity(),
});

const player = new CinemaPlayer({
  audioDsp: audioDsp,
  nav: nav,
  getAppState: () => state,
  onExit: () => {
    actions.refreshState().then(() => render());
  },
  onOpenItem: (item) => actions.openItem(item),
  onToast: (msg) => showToast(msg),
});

function handleBackHierarchy() {
  if (player.isOpen()) {
    return player.handleBack();
  }
  if (state.jellyfin.addModalOpen) {
    state.jellyfin.addModalOpen = false;
    actions.stopJellyfinQuickConnectPoll();
    render();
    return true;
  }
  if (state.streaming.authModal && state.streaming.authModal.open) {
    actions.closeStreamingAuth();
    return true;
  }
  if (state.detail && state.detail.open) {
    actions.closeDetail();
    return true;
  }
  if (state.activeTab !== "board") {
    actions.switchTab("board");
    return true;
  }
  return false;
}

function captureFocusDescriptor() {
  const active = document.activeElement;
  if (!active || !active.classList || !active.classList.contains("focusable")) return null;
  if (active.id) return { type: "id", value: active.id };
  const attrs = active.attributes;
  for (let i = 0; i < attrs.length; i++) {
    if (attrs[i].name.indexOf("data-") === 0 && attrs[i].name !== "data-autofocus") {
      return { type: "attr", name: attrs[i].name, value: attrs[i].value };
    }
  }
  return null;
}

function restoreFocusDescriptor(desc) {
  if (!desc) {
    nav.ensureFocus();
    return;
  }
  let target = null;
  if (desc.type === "id") {
    target = document.getElementById(desc.value);
  } else if (desc.type === "attr") {
    target = document.querySelector("[" + desc.name + '="' + desc.value + '"]');
  }
  if (target && target.offsetWidth > 0 && target.offsetHeight > 0) {
    nav.focusElement(target, false);
  } else {
    nav.ensureFocus();
  }
}

function render() {
  const root = document.getElementById("app");
  if (!root) return;

  const viewportEl = root.querySelector(".main-viewport");
  const prevScrollTop = viewportEl ? viewportEl.scrollTop : 0;
  const focusDesc = captureFocusDescriptor();

  const serviceStatus = state.boot.connected
    ? (state.boot.edition || "webOS") + (state.boot.ramMb ? " · " + state.boot.ramMb + " MB" : "")
    : "Connecting...";

  let navHtml =
    '<header class="top-nav">' +
    '  <div class="brand-cluster">' +
    '    <div class="brand-mark-wrap">' +
    '      <img class="brand-mark-img" src="assets/BrandMark.png" alt="BrootLite" onerror="this.style.display=\'none\';this.nextElementSibling.style.display=\'block\';" />' +
    '      <span class="brand-mark-fallback">B</span>' +
    "    </div>" +
    '    <div class="brand-title-col">' +
    '      <div class="brand-title">BrootLite</div>' +
    '      <div class="brand-subtitle">Xirtus 10-Foot TV</div>' +
    "    </div>" +
    "  </div>" +
    '  <nav class="nav-tabs">';

  for (let i = 0; i < TABS.length; i++) {
    const t = TABS[i];
    navHtml +=
      '<button class="nav-tab focusable' + (state.activeTab === t.key ? " is-active" : "") + '" tabindex="0" data-nav-tab="' + t.key + '">' +
      esc(t.label) +
      "</button>";
  }

  navHtml +=
    "  </nav>" +
    '  <div class="nav-status-cluster">' +
    '    <div class="status-pill">' +
    '      <span class="status-dot' + (state.boot.connected ? "" : " is-offline") + '"></span>' +
    '      <span>' + esc(serviceStatus) + "</span>" +
    "    </div>" +
    "  </div>" +
    "</header>";

  const ctx = { state: state, actions: actions, nav: nav };
  let bodyHtml = "";
  switch (state.activeTab) {
    case "board":
      bodyHtml = renderBoardView(ctx);
      break;
    case "discover":
      bodyHtml = renderDiscoverView(ctx);
      break;
    case "jellyfin":
      bodyHtml = renderJellyfinView(ctx);
      break;
    case "streaming":
      bodyHtml = renderStreamingView(ctx);
      break;
    case "search":
      bodyHtml = renderSearchView(ctx);
      break;
    case "sources":
      bodyHtml = renderSourcesView(ctx);
      break;
    case "library":
      bodyHtml = renderLibraryView(ctx);
      break;
    case "passport":
      bodyHtml = renderPassportView(ctx);
      break;
    default:
      bodyHtml = renderBoardView(ctx);
  }

  const detailHtml = renderDetailOverlay(ctx);

  root.innerHTML =
    navHtml +
    '<main class="main-viewport">' +
    bodyHtml +
    "</main>" +
    detailHtml;

  const newViewport = root.querySelector(".main-viewport");
  if (newViewport) newViewport.scrollTop = prevScrollTop;

  // Bind Top Nav Tabs
  const tabBtns = root.querySelectorAll("[data-nav-tab]");
  for (let i = 0; i < tabBtns.length; i++) {
    tabBtns[i].onclick = () => {
      const key = tabBtns[i].getAttribute("data-nav-tab");
      actions.switchTab(key);
    };
  }

  // Bind Active View Events
  switch (state.activeTab) {
    case "board":
      bindBoardEvents(root, ctx);
      break;
    case "discover":
      bindDiscoverEvents(root, ctx);
      break;
    case "jellyfin":
      bindJellyfinEvents(root, ctx);
      break;
    case "streaming":
      bindStreamingEvents(root, ctx);
      break;
    case "search":
      bindSearchEvents(root, ctx);
      break;
    case "sources":
      bindSourcesEvents(root, ctx);
      break;
    case "library":
      bindLibraryEvents(root, ctx);
      break;
    case "passport":
      bindPassportEvents(root, ctx);
      break;
  }

  if (state.detail && state.detail.open) {
    bindDetailEvents(root, ctx);
  }

  if (!player.isOpen()) {
    restoreFocusDescriptor(focusDesc);
  }
}

const actions = {
  render: render,

  switchTab(tabKey) {
    state.activeTab = tabKey;
    render();
    if (tabKey === "discover" && !state.discover.loaded) actions.loadDiscover();
    if (tabKey === "jellyfin" && !state.jellyfin.loaded) actions.loadJellyfinBrowse();
    if (tabKey === "streaming" && !state.streaming.loaded) actions.loadStreamingFeed();
    if (tabKey === "sources" && !state.sources.loaded) actions.loadSourcesCatalog();
    if (tabKey === "search" && state.search.feeds.length === 0) actions.runSwarmSearch();
    if (tabKey === "passport") actions.refreshPack();
  },

  isFavorite(id) {
    if (!id) return false;
    return (state.favorites || []).some((f) => f.id === id);
  },

  async toggleFavorite(item) {
    try {
      const res = await rpc("favorites.toggle", { item: item });
      if (res && Array.isArray(res.favorites)) {
        state.favorites = res.favorites.map((x) => normalizeItem(x)).filter(Boolean);
      } else {
        const exists = actions.isFavorite(item.id);
        if (exists) {
          state.favorites = state.favorites.filter((f) => f.id !== item.id);
        } else {
          state.favorites = [item].concat(state.favorites);
        }
      }
      showToast(actions.isFavorite(item.id) ? "Saved to Library" : "Removed from Favorites");
      render();
    } catch (err) {
      showToast("Favorite failed: " + (err.message || err));
    }
  },

  async refreshState() {
    try {
      const s = await rpc("state.get", {});
      if (!s) return;
      if (Array.isArray(s.favorites)) {
        state.favorites = s.favorites.map((x) => normalizeItem(x)).filter(Boolean);
      }
      if (Array.isArray(s.history)) {
        state.history = s.history.map((x) => normalizeItem(x)).filter(Boolean);
      }
      if (s.audioDsp) audioDsp.hydrateFromRemoteState(s.audioDsp);
      if (s.youtubeOptions) {
        state.youtubeOptions = Object.assign({}, state.youtubeOptions, s.youtubeOptions);
      }
      if (s.stremio) {
        state.stremio = Object.assign({}, state.stremio, s.stremio);
      }
      if (Array.isArray(s.jellyfinServers)) {
        state.jellyfin.servers = s.jellyfinServers;
        if (!state.jellyfin.activeServerId && s.jellyfinServers.length > 0) {
          state.jellyfin.activeServerId = s.jellyfinServers[0].id;
        }
      }
      if (s.multipass) {
        state.multipass = Object.assign({}, state.multipass, s.multipass);
      }
    } catch (_e) {}
  },

  async refreshPack() {
    try {
      const p = await rpc("pack.get", {});
      if (p) state.pack = p;
      render();
    } catch (_e) {}
  },

  async importPack(source) {
    try {
      showToast("Importing Provider Pack...");
      const res = await rpc("pack.import", { source: source });
      state.pack = (res && (res.pack || res)) || state.pack;
      state.sources.loaded = false;
      state.discover.loaded = false;
      state.search.feeds = [];
      showToast("Provider Pack active!");
      await actions.loadBoard(true);
      render();
    } catch (err) {
      showToast("Pack import error: " + (err.message || err));
    }
  },

  async clearPack() {
    try {
      const res = await rpc("pack.clear", {});
      state.pack = (res && (res.pack || res)) || {};
      state.sources.loaded = false;
      state.search.feeds = [];
      showToast("Provider Pack reset to defaults");
      render();
    } catch (err) {
      showToast("Reset error: " + (err.message || err));
    }
  },

  async loadBoard(force) {
    if (!force && state.board.rails.length > 0) return;
    state.board.loading = true;
    render();
    try {
      const res = await rpc("catalog.board", {});
      const cw = (res && (res.continueWatching || res.history)) || [];
      const rails = (res && (res.rails || res.catalogs)) || [];
      state.board.continueWatching = cw.map((x) => normalizeItem(x)).filter(Boolean);
      state.board.rails = rails.map((r) => ({
        title: r.title || r.name || "Catalog",
        type: r.type || "movie",
        addonName: r.addonName || "",
        items: (r.items || r.metas || []).map((x) => normalizeItem(x, r.type || "movie")).filter(Boolean),
      }));
    } catch (err) {
      showToast("Board error: " + (err.message || err));
    }
    state.board.loading = false;
    render();
  },

  async loadDiscover() {
    const d = state.discover;
    d.loading = true;
    d.loaded = true;
    render();
    try {
      const res = await rpc("catalog.discover", {
        type: d.type,
        catalogId: d.catalogId || undefined,
        genre: d.genre || undefined,
        page: d.page,
        skip: (d.page - 1) * 24,
      });
      if (res) {
        if (Array.isArray(res.catalogs)) d.catalogs = res.catalogs;
        if (!d.catalogId && d.catalogs.length > 0) {
          d.catalogId = d.catalogs[0].id || d.catalogs[0].catalogId || "";
        }
        if (Array.isArray(res.genres)) d.genres = res.genres;
        const rawItems = res.items || res.metas || res.results || [];
        d.items = rawItems.map((x) => normalizeItem(x, d.type)).filter(Boolean);
        d.hasMore = res.hasMore !== undefined ? !!res.hasMore : d.items.length >= 18;
      }
    } catch (err) {
      showToast("Discover error: " + (err.message || err));
    }
    d.loading = false;
    render();
  },

  async runGlobalSearch(query) {
    state.search.query = query;
    state.activeTab = "search";
    state.search.loadingCatalog = true;
    state.search.loadingSwarm = true;
    render();

    const catPromise = rpc("catalog.discover", { query: query, search: query, type: "movie" })
      .then((res) => {
        const raw = (res && (res.items || res.metas || res.results)) || [];
        state.search.catalogMatches = raw.map((x) => normalizeItem(x)).filter(Boolean);
      })
      .catch(() => {
        state.search.catalogMatches = [];
      })
      .finally(() => {
        state.search.loadingCatalog = false;
        render();
      });

    const swarmPromise = actions.runSwarmSearch();
    await Promise.all([catPromise, swarmPromise]);
  },

  async runSwarmSearch() {
    const s = state.search;
    s.loadingSwarm = true;
    render();
    try {
      const res = await rpc("swarm.search", {
        query: s.query,
        category: s.category,
        feedKey: s.feedKey || undefined,
      });
      if (res) {
        if (Array.isArray(res.feeds)) {
          s.feeds = res.feeds;
          if (!s.feedKey && s.feeds.length > 0) {
            s.feedKey = s.feeds[0].key || s.feeds[0].id || "";
          }
        }
        s.swarmResults = res.items || res.results || [];
      }
    } catch (err) {
      showToast("Swarm search failed: " + (err.message || err));
      s.swarmResults = [];
    }
    s.loadingSwarm = false;
    render();
  },

  async playSwarmResult(row) {
    try {
      showToast("Resolving P2P swarm stream...");
      const res = await rpc("streams.resolvePlayable", {
        stream: row,
        meta: { id: row.infoHash || row.id || row.title, type: row.category === "series" ? "series" : "movie", title: row.title },
      });
      const url = res && (res.url || res.streamUrl);
      if (!url) {
        showToast("Unable to resolve swarm stream");
        return;
      }
      player.open({
        id: row.infoHash || row.id || row.title,
        type: row.category === "series" ? "series" : "movie",
        title: row.title || row.fullReleaseTitle || "Swarm Stream",
        subtitle: row.telemetryHeader || row.feedLabel || "Direct P2P Swarm",
        url: url,
        infoHash: (res && res.infoHash) || row.infoHash || "",
        subtitles: (res && res.subtitles) || [],
        source: "swarm",
      });
    } catch (err) {
      showToast("Swarm play failed: " + (err.message || err));
    }
  },

  openItem(item) {
    const norm = normalizeItem(item);
    if (!norm) return;
    if (norm.source === "streaming") {
      actions.playStreamingItem(norm);
      return;
    }
    if (norm.source === "sources") {
      actions.playSourceItem(norm);
      return;
    }
    if (norm.source === "jellyfin" && norm.type !== "series") {
      actions.playJellyfinItem(norm);
      return;
    }
    loadDetailData(state, norm, render);
  },

  closeDetail() {
    state.detail = { open: false };
    render();
  },

  selectDetailSeason(seasonNum) {
    if (!state.detail || !state.detail.open) return;
    const d = state.detail;
    d.selectedSeason = seasonNum;
    d.episodes = (d.allVideos || []).filter(
      (v) => Number(v.season !== undefined ? v.season : 1) === seasonNum
    );
    if (d.episodes.length > 0) {
      d.selectedEpisode = Number(d.episodes[0].episode || d.episodes[0].number || 1);
    }
    render();
    actions.fetchDetailStreams();
  },

  selectDetailEpisode(epNum) {
    if (!state.detail || !state.detail.open) return;
    state.detail.selectedEpisode = epNum;
    render();
    actions.fetchDetailStreams();
  },

  fetchDetailStreams() {
    return fetchDetailStreamsData(state, render);
  },

  async playDetailStream(idx) {
    const d = state.detail;
    if (!d || !d.streams || !d.streams[idx]) return;
    const st = d.streams[idx];
    const meta = d.meta || d.item;
    try {
      showToast("Preparing stream...");
      const res = await rpc("streams.resolvePlayable", {
        stream: st,
        meta: {
          id: meta.id,
          type: meta.type,
          title: meta.title || meta.name,
          season: meta.type === "series" ? d.selectedSeason : undefined,
          episode: meta.type === "series" ? d.selectedEpisode : undefined,
        },
      });
      const playUrl = res && (res.url || res.streamUrl);
      if (!playUrl) {
        showToast("No playable URL resolved for this stream");
        return;
      }
      const epSubtitle =
        meta.type === "series"
          ? "S" + String(d.selectedSeason).padStart(2, "0") + "E" + String(d.selectedEpisode).padStart(2, "0")
          : st.telemetryHeader || "";
      player.open({
        id: meta.id,
        type: meta.type || "movie",
        title: meta.title || meta.name,
        subtitle: epSubtitle,
        poster: meta.poster || "",
        backdrop: meta.backdrop || "",
        season: meta.type === "series" ? d.selectedSeason : undefined,
        episode: meta.type === "series" ? d.selectedEpisode : undefined,
        url: playUrl,
        infoHash: (res && res.infoHash) || st.infoHash || "",
        subtitles: (res && res.subtitles) || [],
        positionSec: d.item && d.item.positionSec ? d.item.positionSec : 0,
        source: meta.source || "stremio",
      });
    } catch (err) {
      showToast("Playback error: " + (err.message || err));
    }
  },

  // ── Jellyfin ──
  async loadJellyfinBrowse() {
    const jf = state.jellyfin;
    jf.loading = true;
    jf.loaded = true;
    render();
    try {
      const res = await rpc("jellyfin.browse", {
        serverId: jf.activeServerId || undefined,
        view: jf.view,
        genre: jf.genre || undefined,
        sort: jf.sort,
        query: jf.query || undefined,
      });
      if (res) {
        if (Array.isArray(res.servers)) {
          jf.servers = res.servers;
          if (!jf.activeServerId && jf.servers.length > 0) jf.activeServerId = jf.servers[0].id;
        }
        if (Array.isArray(res.genres)) jf.genres = res.genres;
        const raw = res.items || [];
        jf.items = raw
          .map((x) => normalizeItem(Object.assign({ source: "jellyfin", serverId: jf.activeServerId }, x)))
          .filter(Boolean);
      }
    } catch (err) {
      showToast("Jellyfin error: " + (err.message || err));
    }
    jf.loading = false;
    render();
  },

  async scanJellyfinLan() {
    const jf = state.jellyfin;
    jf.scanningLan = true;
    render();
    try {
      const res = await rpc("jellyfin.discoverLan", {});
      jf.lanServers = (res && res.servers) || [];
      if (jf.lanServers.length === 0) showToast("No Jellyfin servers found via UDP 7359");
    } catch (err) {
      showToast("LAN Scan error: " + (err.message || err));
    }
    jf.scanningLan = false;
    render();
  },

  async startJellyfinQuickConnect() {
    const jf = state.jellyfin;
    if (!jf.formUrl) {
      showToast("Enter Jellyfin server URL first");
      return;
    }
    actions.stopJellyfinQuickConnectPoll();
    try {
      const res = await rpc("jellyfin.addServerQuickConnect", {
        serverUrl: jf.formUrl,
        name: jf.formName || undefined,
      });
      jf.quickConnect = res;
      render();
      if (res && res.secret) {
        jfQcPollTimer = setInterval(async () => {
          try {
            const p = await rpc("jellyfin.pollQuickConnect", {
              serverId: res.serverId,
              serverUrl: jf.formUrl,
              secret: res.secret,
            });
            if (p && p.authenticated) {
              actions.stopJellyfinQuickConnectPoll();
              jf.addModalOpen = false;
              jf.quickConnect = null;
              if (p.server && p.server.id) jf.activeServerId = p.server.id;
              showToast("Jellyfin QuickConnect paired!");
              await actions.loadJellyfinBrowse();
            }
          } catch (_e) {}
        }, 2500);
      }
    } catch (err) {
      showToast("QuickConnect error: " + (err.message || err));
    }
  },

  stopJellyfinQuickConnectPoll() {
    if (jfQcPollTimer) clearInterval(jfQcPollTimer);
    jfQcPollTimer = null;
  },

  async loginJellyfinPassword() {
    const jf = state.jellyfin;
    if (!jf.formUrl || !jf.formUser) {
      showToast("Enter Server URL and Username");
      return;
    }
    try {
      const res = await rpc("jellyfin.loginPassword", {
        serverUrl: jf.formUrl,
        username: jf.formUser,
        password: jf.formPass,
        name: jf.formName || undefined,
      });
      jf.addModalOpen = false;
      jf.formPass = "";
      if (res && res.server && res.server.id) jf.activeServerId = res.server.id;
      showToast("Connected to Jellyfin!");
      await actions.loadJellyfinBrowse();
    } catch (err) {
      showToast("Login failed: " + (err.message || err));
    }
  },

  async removeActiveJellyfinServer() {
    const jf = state.jellyfin;
    if (!jf.activeServerId) return;
    try {
      await rpc("jellyfin.removeServer", { serverId: jf.activeServerId });
      jf.servers = jf.servers.filter((s) => s.id !== jf.activeServerId);
      jf.activeServerId = jf.servers.length > 0 ? jf.servers[0].id : "";
      jf.items = [];
      showToast("Server removed");
      await actions.loadJellyfinBrowse();
    } catch (err) {
      showToast("Remove failed: " + (err.message || err));
    }
  },

  async playJellyfinItem(item) {
    try {
      showToast("Starting Jellyfin stream...");
      const res = await rpc("jellyfin.resolvePlay", {
        serverId: item.serverId || state.jellyfin.activeServerId,
        itemId: item.id,
        resumePositionTicks: item.positionSec ? item.positionSec * 10000000 : 0,
      });
      const url = res && (res.url || res.streamUrl);
      if (!url) {
        showToast("No Jellyfin stream URL returned");
        return;
      }
      player.open({
        id: item.id,
        type: item.type || "movie",
        title: item.title,
        subtitle: "Jellyfin Direct Play",
        poster: item.poster,
        backdrop: item.backdrop,
        serverId: item.serverId || state.jellyfin.activeServerId,
        mediaSourceId: (res && res.mediaSourceId) || "",
        playSessionId: (res && res.playSessionId) || "",
        url: url,
        subtitles: (res && res.subtitles) || [],
        positionSec: item.positionSec || 0,
        source: "jellyfin",
      });
    } catch (err) {
      showToast("Jellyfin play failed: " + (err.message || err));
    }
  },

  // ── Streaming ──
  async loadStreamingFeed() {
    const st = state.streaming;
    st.loading = true;
    st.loaded = true;
    render();
    try {
      const res = await rpc("streaming.feed", {
        platform: st.platform,
        section: st.section,
        query: st.query || undefined,
      });
      if (res) {
        st.authenticated = !!res.authenticated;
        st.user = res.user || "";
        const raw = res.items || [];
        st.items = raw
          .map((x) => normalizeItem(Object.assign({ source: "streaming", platform: st.platform }, x)))
          .filter(Boolean);
      }
    } catch (err) {
      showToast("Streaming feed error: " + (err.message || err));
      st.items = [];
    }
    st.loading = false;
    render();
  },

  async playStreamingItem(item) {
    try {
      showToast("Resolving ad-free stream...");
      const res = await rpc("streaming.resolve", {
        platform: item.platform || state.streaming.platform,
        videoId: item.id,
        url: item.itemUrl,
        item: item.raw || item,
      });
      const url = res && (res.url || res.streamUrl);
      if (!url) {
        showToast("No playable stream URL returned");
        return;
      }
      const ytOpts = state.youtubeOptions || {};
      const title = ytOpts.deArrow && res.deArrowTitle ? res.deArrowTitle : item.title;
      player.open({
        id: item.id,
        type: "movie",
        title: title,
        subtitle: item.channel || (item.platform || state.streaming.platform).toUpperCase(),
        poster: item.thumbnail || item.poster,
        url: url,
        qualities: (res && res.formats) || [],
        subtitles: (res && res.subtitles) || [],
        sponsorSegments: (res && res.sponsorSegments) || [],
        source: "streaming",
      });
    } catch (err) {
      showToast("Streaming play failed: " + (err.message || err));
    }
  },

  async startStreamingAuth() {
    const st = state.streaming;
    st.authModal = { open: true, loading: true };
    render();
    try {
      const res = await rpc("streaming.authStart", { platform: st.platform });
      st.authModal = Object.assign({ open: true, loading: false }, res || {});
      render();
      if (res && res.deviceCode) {
        if (streamAuthPollTimer) clearInterval(streamAuthPollTimer);
        streamAuthPollTimer = setInterval(async () => {
          try {
            const p = await rpc("streaming.authPoll", {
              platform: st.platform,
              deviceCode: res.deviceCode,
            });
            if (p && p.authenticated) {
              actions.closeStreamingAuth();
              showToast(st.platform + " signed in!");
              actions.loadStreamingFeed();
            }
          } catch (_e) {}
        }, Math.max(2500, (res.interval || 4) * 1000));
      }
    } catch (err) {
      st.authModal = { open: true, loading: false };
      showToast("Auth start failed: " + (err.message || err));
      render();
    }
  },

  closeStreamingAuth() {
    if (streamAuthPollTimer) clearInterval(streamAuthPollTimer);
    streamAuthPollTimer = null;
    state.streaming.authModal = null;
    render();
  },

  async logoutStreamingAuth() {
    try {
      await rpc("streaming.authLogout", { platform: state.streaming.platform });
      state.streaming.authenticated = false;
      state.streaming.user = "";
      showToast("Signed out");
      actions.loadStreamingFeed();
    } catch (err) {
      showToast("Logout error: " + (err.message || err));
    }
  },

  // ── Sources (Addonless Direct Video) ──
  async loadSourcesCatalog() {
    const src = state.sources;
    src.loading = true;
    src.loaded = true;
    render();
    try {
      const res = await rpc("sources.catalog", {
        providerId: src.providerId || undefined,
        section: src.section,
        query: src.query || undefined,
        page: src.page,
      });
      if (res) {
        if (Array.isArray(res.providers)) {
          src.providers = res.providers;
          if (!src.providerId && src.providers.length > 0) {
            src.providerId = src.providers[0].id || src.providers[0].providerId || "";
          }
        }
        if (Array.isArray(res.sections)) src.sections = res.sections;
        const raw = res.items || [];
        src.items = raw
          .map((x) => normalizeItem(Object.assign({ source: "sources", providerId: src.providerId }, x)))
          .filter(Boolean);
        src.hasMore = res.hasMore !== undefined ? !!res.hasMore : src.items.length >= 12;
      }
    } catch (err) {
      showToast("Sources error: " + (err.message || err));
      src.items = [];
    }
    src.loading = false;
    render();
  },

  async playSourceItem(item) {
    try {
      showToast("Extracting direct video stream...");
      const res = await rpc("sources.resolve", {
        providerId: item.providerId || state.sources.providerId,
        itemUrl: item.itemUrl,
        itemId: item.id,
      });
      const qualities = (res && res.streams) || [];
      const url = (res && (res.url || res.streamUrl)) || (qualities[0] && qualities[0].url);
      if (!url) {
        showToast("No direct stream URL extracted");
        return;
      }
      player.open({
        id: item.id || item.itemUrl,
        type: "movie",
        title: (res && res.title) || item.title,
        subtitle: item.providerId || state.sources.providerId || "Direct Source",
        poster: item.thumbnail || item.poster,
        url: url,
        qualities: qualities,
        subtitles: (res && res.subtitles) || [],
        source: "sources",
        providerId: item.providerId || state.sources.providerId,
      });
    } catch (err) {
      showToast("Source resolve failed: " + (err.message || err));
    }
  },

  // ── Passport: Multipass, Stremio, YouTube Options ──
  async startMultipassPair() {
    try {
      const res = await rpc("multipass.pairStart", {});
      state.multipass = Object.assign({}, state.multipass, {
        qrUrl: (res && (res.qrUrl || res.qrPayload)) || "",
        sessionId: (res && res.sessionId) || "",
      });
      render();
      if (multipassPollTimer) clearInterval(multipassPollTimer);
      if (state.multipass.sessionId) {
        multipassPollTimer = setInterval(async () => {
          try {
            const p = await rpc("multipass.pairPoll", { sessionId: state.multipass.sessionId });
            if (p && p.paired) {
              clearInterval(multipassPollTimer);
              multipassPollTimer = null;
              state.multipass.paired = true;
              showToast("Leeloo Multipass v2 paired!");
              await actions.refreshState();
              await actions.refreshPack();
              render();
            }
          } catch (_e) {}
        }, 2500);
      }
    } catch (err) {
      showToast("Multipass pair error: " + (err.message || err));
    }
  },

  async syncMultipass() {
    try {
      showToast("Syncing Multipass vault...");
      await rpc("multipass.sync", {});
      await actions.refreshState();
      await actions.refreshPack();
      showToast("Vault synced!");
      render();
    } catch (err) {
      showToast("Sync error: " + (err.message || err));
    }
  },

  async startStremioQr() {
    try {
      const res = await rpc("stremio.startQrAuth", {});
      state.stremio = Object.assign({}, state.stremio, {
        qrLink: (res && (res.link || res.verificationUrl || res.qrUrl)) || "",
        code: (res && res.code) || "",
      });
      render();
      if (stremioPollTimer) clearInterval(stremioPollTimer);
      if (state.stremio.code) {
        stremioPollTimer = setInterval(async () => {
          try {
            const p = await rpc("stremio.pollQrAuth", { code: state.stremio.code });
            if (p && p.authenticated) {
              clearInterval(stremioPollTimer);
              stremioPollTimer = null;
              state.stremio.authenticated = true;
              state.stremio.email = p.email || (p.user && p.user.email) || "Linked";
              state.stremio.qrLink = "";
              showToast("Stremio account linked!");
              await actions.loadBoard(true);
              render();
            }
          } catch (_e) {}
        }, 2500);
      }
    } catch (err) {
      showToast("Stremio QR error: " + (err.message || err));
    }
  },

  async syncStremioAddons() {
    try {
      showToast("Syncing Stremio addons...");
      await rpc("stremio.syncAddons", {});
      await actions.loadBoard(true);
      showToast("Stremio addons synced!");
    } catch (err) {
      showToast("Sync failed: " + (err.message || err));
    }
  },

  async logoutStremio() {
    try {
      await rpc("stremio.logout", {});
      state.stremio = { authenticated: false, email: "", qrLink: "", code: "" };
      showToast("Stremio unlinked");
      render();
    } catch (err) {
      showToast("Unlink error: " + (err.message || err));
    }
  },

  async toggleYoutubeOption(key) {
    state.youtubeOptions[key] = state.youtubeOptions[key] === false;
    render();
    try {
      await rpc("state.patch", { youtubeOptions: Object.assign({}, state.youtubeOptions) });
    } catch (_e) {}
  },
};

async function bootstrap() {
  nav.mount();
  try {
    const boot = await rpcBridge.boot();
    state.boot = Object.assign({ connected: true }, boot || {});
    if (boot && boot.mediaBaseUrl) {
      // Ensure mediaBaseUrl is ready for relative URLs
      resolveMediaUrl("/");
    }
  } catch (_err) {
    state.boot = { connected: false, edition: "Offline", ramMb: "" };
  }

  await Promise.all([actions.refreshState(), actions.refreshPack()]);
  await actions.loadBoard(true);
  render();
}

bootstrap();
