'use strict';

var fs = require('fs');
var os = require('os');
var path = require('path');
var httpClient = require('./http_client');
var defaultProviders = require('./default_providers');

function mkdirpSync(dir) {
  if (!dir) return;
  if (fs.existsSync(dir)) return;
  var parent = path.dirname(dir);
  if (parent && parent !== dir) {
    mkdirpSync(parent);
  }
  try {
    fs.mkdirSync(dir);
  } catch (err) {
    if (err && err.code !== 'EEXIST') throw err;
  }
}

function rimrafSync(targetPath) {
  if (!targetPath || !fs.existsSync(targetPath)) return;
  var stat;
  try {
    stat = fs.lstatSync(targetPath);
  } catch (e) {
    return;
  }
  if (stat.isDirectory() && !stat.isSymbolicLink()) {
    var entries = fs.readdirSync(targetPath);
    for (var i = 0; i < entries.length; i++) {
      rimrafSync(path.join(targetPath, entries[i]));
    }
    try {
      fs.rmdirSync(targetPath);
    } catch (e) {}
  } else {
    try {
      fs.unlinkSync(targetPath);
    } catch (e) {}
  }
}

function resolveDataDir() {
  if (process.env.BROOTLITE_DATA_DIR && process.env.BROOTLITE_DATA_DIR.trim()) {
    var envDir = process.env.BROOTLITE_DATA_DIR.trim();
    mkdirpSync(envDir);
    return envDir;
  }

  var webosBase = '/media/developer/temp';
  var webosParent = '/media/developer';
  try {
    if (fs.existsSync(webosBase) || fs.existsSync(webosParent)) {
      var candidate = path.join(webosBase, 'brootlite');
      mkdirpSync(candidate);
      var testFile = path.join(candidate, '.rw_probe');
      fs.writeFileSync(testFile, 'ok');
      fs.unlinkSync(testFile);
      return candidate;
    }
  } catch (e) {}

  try {
    var home = os.homedir ? os.homedir() : '';
    if (home) {
      var homeDir = path.join(home, '.brootlite');
      mkdirpSync(homeDir);
      return homeDir;
    }
  } catch (e2) {}

  var tmpDir = path.join(os.tmpdir ? os.tmpdir() : '/tmp', 'brootlite');
  mkdirpSync(tmpDir);
  return tmpDir;
}

function getPublicBaseAddons() {
  var movieGenres = [
    'Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Family',
    'Fantasy', 'History', 'Horror', 'Mystery', 'Romance', 'Sci-Fi', 'Thriller', 'War'
  ];
  var streamingBase = 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibyxwbXAsamhzfHx8fHx8MTc3MTM2NTQyNTMwOQ%3D%3D';
  var streamingServices = [
    ['nfx', 'Netflix'],
    ['dnp', 'Disney+'],
    ['amp', 'Prime Video'],
    ['atp', 'Apple TV+'],
    ['hbo', 'Max'],
    ['pmp', 'Paramount+']
  ];
  var streamingCatalogs = [];
  for (var i = 0; i < streamingServices.length; i++) {
    var sid = streamingServices[i][0];
    var sname = streamingServices[i][1];
    streamingCatalogs.push({
      type: 'movie',
      id: sid,
      name: sname,
      genres: [],
      supportsSearch: false,
      supportsGenre: false,
      supportsSkip: true
    });
    streamingCatalogs.push({
      type: 'series',
      id: sid,
      name: sname,
      genres: [],
      supportsSearch: false,
      supportsGenre: false,
      supportsSkip: true
    });
  }

  return [
    {
      id: 'com.linvo.cinemeta',
      name: 'Cinemeta',
      version: '3.0.13',
      description: 'Official movie & series metadata and catalogs',
      transportUrl: 'https://v3-cinemeta.strem.io/manifest.json',
      baseUrl: 'https://v3-cinemeta.strem.io',
      types: ['movie', 'series'],
      resources: ['catalog', 'meta'],
      resourceSpecs: [
        { name: 'catalog', types: ['movie', 'series'], idPrefixes: [] },
        { name: 'meta', types: ['movie', 'series'], idPrefixes: ['tt'] }
      ],
      idPrefixes: ['tt'],
      catalogs: [
        {
          type: 'movie',
          id: 'top',
          name: 'Popular',
          genres: movieGenres.slice(),
          supportsSearch: true,
          supportsGenre: true,
          supportsSkip: true
        },
        {
          type: 'series',
          id: 'top',
          name: 'Popular',
          genres: movieGenres.slice(),
          supportsSearch: true,
          supportsGenre: true,
          supportsSkip: true
        },
        {
          type: 'movie',
          id: 'imdbRating',
          name: 'Featured',
          genres: movieGenres.slice(),
          supportsSearch: false,
          supportsGenre: true,
          supportsSkip: true
        },
        {
          type: 'series',
          id: 'imdbRating',
          name: 'Featured',
          genres: movieGenres.slice(),
          supportsSearch: false,
          supportsGenre: true,
          supportsSkip: true
        }
      ]
    },
    {
      id: 'pw.ers.netflix-catalog',
      name: 'Streaming Catalogs',
      version: '1.0.0',
      description: 'Netflix, Disney+, Prime Video, Apple TV+, Max & Paramount+ rows',
      transportUrl: streamingBase + '/manifest.json',
      baseUrl: streamingBase,
      types: ['movie', 'series'],
      resources: ['catalog'],
      resourceSpecs: [
        { name: 'catalog', types: ['movie', 'series'], idPrefixes: [] }
      ],
      idPrefixes: ['tt'],
      catalogs: streamingCatalogs
    },
    {
      id: 'org.stremio.opensubtitlesv3',
      name: 'OpenSubtitles v3',
      version: '1.0.0',
      description: 'Official multi-language external subtitles',
      transportUrl: 'https://opensubtitles-v3.strem.io/manifest.json',
      baseUrl: 'https://opensubtitles-v3.strem.io',
      types: ['movie', 'series'],
      resources: ['subtitles'],
      resourceSpecs: [
        { name: 'subtitles', types: ['movie', 'series'], idPrefixes: ['tt'] }
      ],
      idPrefixes: ['tt'],
      catalogs: []
    }
  ];
}

function normalizeAddonEntry(raw) {
  if (!raw || typeof raw !== 'object') return null;
  var transportUrl = String(raw.transportUrl || '').trim();
  if (!transportUrl) return null;
  var baseUrl = raw.baseUrl
    ? String(raw.baseUrl).replace(/\/+$/, '')
    : transportUrl.split('/manifest.json')[0].replace(/\/+$/, '');
  var types = Array.isArray(raw.types) ? raw.types.slice() : ['movie', 'series'];
  var resources = Array.isArray(raw.resources) ? raw.resources.slice() : ['stream'];
  var idPrefixes = Array.isArray(raw.idPrefixes) ? raw.idPrefixes.slice() : [];
  var resourceSpecs = Array.isArray(raw.resourceSpecs) && raw.resourceSpecs.length
    ? raw.resourceSpecs
    : resources.map(function (r) {
        if (typeof r === 'string') {
          return { name: r, types: types, idPrefixes: idPrefixes };
        }
        return {
          name: String((r && r.name) || 'stream'),
          types: Array.isArray(r && r.types) ? r.types : types,
          idPrefixes: Array.isArray(r && r.idPrefixes) ? r.idPrefixes : idPrefixes
        };
      });

  return {
    id: String(raw.id || transportUrl),
    name: String(raw.name || raw.id || 'Addon'),
    version: String(raw.version || '1.0.0'),
    description: String(raw.description || ''),
    transportUrl: transportUrl,
    baseUrl: baseUrl,
    types: types,
    resources: resources.map(function (r) { return typeof r === 'string' ? r : (r && r.name) || ''; }).filter(Boolean),
    resourceSpecs: resourceSpecs,
    idPrefixes: idPrefixes,
    catalogs: Array.isArray(raw.catalogs) ? raw.catalogs : []
  };
}

function normalizePack(rawPack, sourceTag) {
  var p = rawPack && typeof rawPack === 'object' ? rawPack : {};
  var normalizedAddons = [];
  if (Array.isArray(p.stremioAddons)) {
    for (var i = 0; i < p.stremioAddons.length; i++) {
      var na = normalizeAddonEntry(p.stremioAddons[i]);
      if (na) normalizedAddons.push(na);
    }
  }
  return {
    schemaVersion: p.schemaVersion || 1,
    activeSource: sourceTag || 'default',
    edition: p.edition || (sourceTag === 'default' ? (defaultProviders.edition || 'private') : 'pack'),
    name: String(p.name || 'BrootLite Provider Pack'),
    trackers: Array.isArray(p.trackers) ? p.trackers.filter(Boolean) : [],
    stremioAddons: normalizedAddons,
    swarmIndexers: Array.isArray(p.swarmIndexers) ? p.swarmIndexers : [],
    featuredFeeds: Array.isArray(p.featuredFeeds) ? p.featuredFeeds : [],
    directSources: Array.isArray(p.directSources) ? p.directSources : [],
    kodiRepositories: Array.isArray(p.kodiRepositories) ? p.kodiRepositories : []
  };
}

function createStore(explicitDataDir) {
  var dataDir = explicitDataDir || resolveDataDir();
  mkdirpSync(dataDir);
  var cacheDir = path.join(dataDir, 'cache');
  var torrentCacheDir = path.join(dataDir, 'torrent_cache');
  mkdirpSync(cacheDir);
  mkdirpSync(torrentCacheDir);

  var stateFile = path.join(dataDir, 'state.json');
  var packFile = path.join(dataDir, 'provider_pack.json');

  function defaultState() {
    return {
      installationId: 'bl-webos-' + httpClient.uuidV4(),
      jellyfinDeviceId: 'brootlite-webos-' + httpClient.uuidV4(),
      stremioAuthKey: '',
      stremioEmail: '',
      stremioAuthUpdatedAtMs: 0,
      stremioAuthRevokedAtMs: null,
      addons: getPublicBaseAddons(),
      packAddonIds: [],
      watchHistory: [],
      favorites: [],
      jellyfinServers: [],
      jellyfinTombstones: {},
      youtubeAccounts: [],
      activeYoutubeAccountId: '',
      youtubeTombstones: {},
      twitchProfile: null,
      creatorFollows: [],
      multipass: {
        endpointUrl: '',
        pairToken: '',
        vaultUnlocked: false,
        deviceId: '',
        deviceName: 'BrootLite LG webOS TV',
        handle: '',
        lastSyncedMs: 0,
        lastSyncedRevision: 0
      },
      multipassVmkB64: '',
      swarmFilter: 'SAFE_ONLY',
      discoverSortMode: 'POPULAR',
      torrentConfig: {
        maxCacheMb: 512,
        windowAheadMb: 48,
        prebufferMb: 12,
        maxConnections: 80
      },
      torznabUrl: '',
      torznabApiKey: '',
      deArrowEnabled: true,
      sponsorBlockEnabled: true,
      streamingPrefsUpdatedAtMs: 0
    };
  }

  function loadDiskState() {
    var base = defaultState();
    try {
      if (fs.existsSync(stateFile)) {
        var raw = fs.readFileSync(stateFile, 'utf8');
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') {
          var keys = Object.keys(parsed);
          for (var i = 0; i < keys.length; i++) {
            base[keys[i]] = parsed[keys[i]];
          }
        }
      }
    } catch (e) {}

    if (!base.installationId) base.installationId = 'bl-webos-' + httpClient.uuidV4();
    if (!base.jellyfinDeviceId) base.jellyfinDeviceId = 'brootlite-webos-' + httpClient.uuidV4();
    if (!Array.isArray(base.addons) || base.addons.length === 0) {
      base.addons = getPublicBaseAddons();
    }
    if (!Array.isArray(base.packAddonIds)) base.packAddonIds = [];
    if (!Array.isArray(base.watchHistory)) base.watchHistory = [];
    if (!Array.isArray(base.favorites)) base.favorites = [];
    if (!Array.isArray(base.jellyfinServers)) base.jellyfinServers = [];
    if (!base.jellyfinTombstones || typeof base.jellyfinTombstones !== 'object') base.jellyfinTombstones = {};
    if (!Array.isArray(base.youtubeAccounts)) base.youtubeAccounts = [];
    if (!base.youtubeTombstones || typeof base.youtubeTombstones !== 'object') base.youtubeTombstones = {};
    if (!Array.isArray(base.creatorFollows)) base.creatorFollows = [];
    if (!base.multipass || typeof base.multipass !== 'object') base.multipass = defaultState().multipass;
    if (!base.torrentConfig || typeof base.torrentConfig !== 'object') base.torrentConfig = defaultState().torrentConfig;
    return base;
  }

  var state = loadDiskState();
  var activePack = null;

  function saveState() {
    var tmp = stateFile + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(state, null, 2), 'utf8');
    fs.renameSync(tmp, stateFile);
  }

  function mergePackAddons(pack) {
    var baseAddons = getPublicBaseAddons();
    var merged = state.addons.slice();

    // Ensure Cinemeta and OpenSubtitles remain available
    for (var i = 0; i < baseAddons.length; i++) {
      var ba = baseAddons[i];
      var foundBase = false;
      for (var j = 0; j < merged.length; j++) {
        if (merged[j].id === ba.id) {
          foundBase = true;
          break;
        }
      }
      if (!foundBase) {
        merged.unshift(ba);
      }
    }

    var newPackIds = [];
    for (var k = 0; k < pack.stremioAddons.length; k++) {
      var pa = pack.stremioAddons[k];
      newPackIds.push(pa.id);
      var idx = -1;
      for (var m = 0; m < merged.length; m++) {
        if (merged[m].id === pa.id || merged[m].transportUrl === pa.transportUrl) {
          idx = m;
          break;
        }
      }
      if (idx >= 0) {
        merged[idx] = pa;
      } else {
        merged.push(pa);
      }
    }
    state.addons = merged;
    state.packAddonIds = newPackIds;
  }

  function loadActivePack() {
    // 1. Saved runtime pack in <dataDir>/provider_pack.json
    try {
      if (fs.existsSync(packFile)) {
        var raw = fs.readFileSync(packFile, 'utf8');
        var parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object') {
          activePack = normalizePack(parsed, 'custom');
          mergePackAddons(activePack);
          saveState();
          return activePack;
        }
      }
    } catch (e) {}

    // 2. Adjacent bundled seed_pack.json (dist/seed_pack.json or service/seed_pack.json)
    var seedCandidates = [
      path.join(__dirname, 'seed_pack.json'),
      path.join(__dirname, '..', 'seed_pack.json')
    ];
    for (var i = 0; i < seedCandidates.length; i++) {
      try {
        if (fs.existsSync(seedCandidates[i])) {
          var seedRaw = fs.readFileSync(seedCandidates[i], 'utf8');
          var seedParsed = JSON.parse(seedRaw);
          if (seedParsed && typeof seedParsed === 'object') {
            activePack = normalizePack(seedParsed, 'seed');
            mergePackAddons(activePack);
            saveState();
            return activePack;
          }
        }
      } catch (e2) {}
    }

    // 3. Fallback to isolated default_providers.js
    activePack = normalizePack(defaultProviders, 'default');
    mergePackAddons(activePack);
    saveState();
    return activePack;
  }

  loadActivePack();

  function getActivePack() {
    return activePack || loadActivePack();
  }

  function getPackSummary() {
    var p = getActivePack();
    return {
      activeSource: p.activeSource,
      edition: p.edition,
      name: p.name,
      schemaVersion: p.schemaVersion,
      hasCustomPack: fs.existsSync(packFile),
      counts: {
        trackers: p.trackers.length,
        stremioAddons: p.stremioAddons.length,
        swarmIndexers: p.swarmIndexers.length,
        featuredFeeds: p.featuredFeeds.length,
        directSources: p.directSources.length
      },
      swarmIndexers: p.swarmIndexers,
      featuredFeeds: p.featuredFeeds,
      directSources: p.directSources.map(function (s) {
        return {
          id: s.id,
          name: s.name,
          kind: s.kind,
          adult: Boolean(s.adult),
          collection: s.collection || ''
        };
      }),
      stremioAddons: p.stremioAddons
    };
  }

  function importPack(packData) {
    if (!packData || typeof packData !== 'object' || Array.isArray(packData)) {
      throw new Error('Invalid provider pack JSON object');
    }
    var hasAnySection = (
      Array.isArray(packData.trackers) ||
      Array.isArray(packData.stremioAddons) ||
      Array.isArray(packData.swarmIndexers) ||
      Array.isArray(packData.featuredFeeds) ||
      Array.isArray(packData.directSources)
    );
    if (!hasAnySection) {
      throw new Error('Provider pack must define at least one of trackers, stremioAddons, swarmIndexers, featuredFeeds, or directSources');
    }

    // Remove previously pack-managed addon IDs before applying new pack
    var prevPackIds = state.packAddonIds || [];
    if (prevPackIds.length > 0) {
      state.addons = state.addons.filter(function (a) {
        return prevPackIds.indexOf(a.id) === -1;
      });
      state.packAddonIds = [];
    }

    var tmp = packFile + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(packData, null, 2), 'utf8');
    fs.renameSync(tmp, packFile);
    loadActivePack();
    return getPackSummary();
  }

  function clearPack() {
    var prevPackIds = state.packAddonIds || [];
    if (prevPackIds.length > 0) {
      state.addons = state.addons.filter(function (a) {
        return prevPackIds.indexOf(a.id) === -1;
      });
      state.packAddonIds = [];
    }
    if (fs.existsSync(packFile)) {
      fs.unlinkSync(packFile);
    }
    loadActivePack();
    return getPackSummary();
  }

  function getPublicState() {
    var mp = Object.assign({}, state.multipass);
    var mpPaired = Boolean(mp.endpointUrl && mp.pairToken);
    mp.pairToken = '';
    mp.vaultUnlocked = Boolean(state.multipassVmkB64);

    return {
      installationId: state.installationId,
      jellyfinDeviceId: state.jellyfinDeviceId,
      stremioLinked: Boolean(state.stremioAuthKey),
      stremioEmail: state.stremioEmail || '',
      addons: state.addons,
      watchHistory: state.watchHistory,
      favorites: state.favorites,
      jellyfinServers: state.jellyfinServers.map(function (s) {
        return {
          serverId: s.serverId,
          serverName: s.serverName,
          lanUrl: s.lanUrl || '',
          remoteUrl: s.remoteUrl || '',
          userId: s.userId || '',
          username: s.username || '',
          lastVerifiedMs: s.lastVerifiedMs || 0,
          updatedAtMs: s.updatedAtMs || 0
        };
      }),
      youtubeAccounts: state.youtubeAccounts.map(function (a) {
        return {
          accountId: a.accountId,
          accountName: a.accountName,
          channelHandle: a.channelHandle || '',
          avatarUrl: a.avatarUrl || '',
          pageId: a.pageId || '',
          updatedAtMs: a.updatedAtMs || 0
        };
      }),
      activeYoutubeAccountId: state.activeYoutubeAccountId || '',
      twitchLoggedIn: Boolean(state.twitchProfile && state.twitchProfile.accessToken),
      twitchProfile: state.twitchProfile
        ? {
            userId: state.twitchProfile.userId || '',
            login: state.twitchProfile.login || '',
            displayName: state.twitchProfile.displayName || ''
          }
        : null,
      creatorFollows: getActiveCreatorFollows(null),
      multipassPaired: mpPaired,
      multipass: mp,
      swarmFilter: state.swarmFilter || 'SAFE_ONLY',
      adultUnlocked: state.swarmFilter !== 'SAFE_ONLY',
      discoverSortMode: state.discoverSortMode || 'POPULAR',
      torrentConfig: state.torrentConfig,
      torznabUrl: state.torznabUrl || '',
      hasTorznabApiKey: Boolean(state.torznabApiKey),
      deArrowEnabled: state.deArrowEnabled !== false,
      sponsorBlockEnabled: state.sponsorBlockEnabled !== false,
      pack: getPackSummary()
    };
  }

  function patchState(patch) {
    if (!patch || typeof patch !== 'object') return getPublicState();
    var touchedPrefs = false;

    if (typeof patch.adultUnlocked === 'boolean') {
      state.swarmFilter = patch.adultUnlocked ? 'ALL' : 'SAFE_ONLY';
      touchedPrefs = true;
    }
    if (patch.swarmFilter && ['SAFE_ONLY', 'ALL', 'ADULT_ONLY'].indexOf(patch.swarmFilter) !== -1) {
      state.swarmFilter = patch.swarmFilter;
      touchedPrefs = true;
    }
    if (patch.discoverSortMode && ['POPULAR', 'TOP_RATED', 'YEAR_DESC', 'TITLE_ASC'].indexOf(patch.discoverSortMode) !== -1) {
      state.discoverSortMode = patch.discoverSortMode;
    }
    if (patch.torrentConfig && typeof patch.torrentConfig === 'object') {
      var tc = patch.torrentConfig;
      state.torrentConfig = {
        maxCacheMb: Math.max(128, Math.min(4096, Number(tc.maxCacheMb) || state.torrentConfig.maxCacheMb || 512)),
        windowAheadMb: Math.max(8, Math.min(128, Number(tc.windowAheadMb) || state.torrentConfig.windowAheadMb || 48)),
        prebufferMb: Math.max(4, Math.min(64, Number(tc.prebufferMb) || state.torrentConfig.prebufferMb || 12)),
        maxConnections: Math.max(20, Math.min(250, Number(tc.maxConnections) || state.torrentConfig.maxConnections || 80))
      };
      touchedPrefs = true;
    }
    if (typeof patch.torznabUrl === 'string') {
      state.torznabUrl = patch.torznabUrl.trim();
      touchedPrefs = true;
    }
    if (typeof patch.torznabApiKey === 'string') {
      state.torznabApiKey = patch.torznabApiKey.trim();
      touchedPrefs = true;
    }
    if (typeof patch.deArrowEnabled === 'boolean') {
      state.deArrowEnabled = patch.deArrowEnabled;
    }
    if (typeof patch.sponsorBlockEnabled === 'boolean') {
      state.sponsorBlockEnabled = patch.sponsorBlockEnabled;
    }
    if (typeof patch.activeYoutubeAccountId === 'string') {
      state.activeYoutubeAccountId = patch.activeYoutubeAccountId.trim();
    }
    if (typeof patch.removeAddonId === 'string' && patch.removeAddonId.trim()) {
      removeAddon(patch.removeAddonId.trim());
    }
    if (typeof patch.removeWatchHistoryItemId === 'string' && patch.removeWatchHistoryItemId.trim()) {
      state.watchHistory = state.watchHistory.filter(function (h) {
        return h.itemId !== patch.removeWatchHistoryItemId.trim();
      });
    }
    if (patch.toggleCreatorFollow && typeof patch.toggleCreatorFollow === 'object') {
      toggleCreatorFollow(patch.toggleCreatorFollow);
    }
    if (touchedPrefs) {
      state.streamingPrefsUpdatedAtMs = Date.now();
    }
    saveState();
    return getPublicState();
  }

  function setStremioAuth(authKey, email, updatedAtMs) {
    var now = updatedAtMs || Date.now();
    var cleanKey = authKey ? String(authKey).trim() : '';
    if (cleanKey) {
      state.stremioAuthKey = cleanKey;
      state.stremioEmail = String(email || '').trim();
      state.stremioAuthUpdatedAtMs = now;
      state.stremioAuthRevokedAtMs = null;
    } else {
      state.stremioAuthKey = '';
      state.stremioEmail = '';
      state.stremioAuthUpdatedAtMs = now;
      state.stremioAuthRevokedAtMs = now;
    }
    saveState();
  }

  function getStremioAuth() {
    return {
      authKey: state.stremioAuthKey || '',
      email: state.stremioEmail || '',
      updatedAtMs: state.stremioAuthUpdatedAtMs || 0,
      revokedAtMs: state.stremioAuthRevokedAtMs || null
    };
  }

  function setAddons(newAddons) {
    var normalized = [];
    for (var i = 0; i < newAddons.length; i++) {
      var n = normalizeAddonEntry(newAddons[i]);
      if (n) normalized.push(n);
    }
    state.addons = normalized;
    mergePackAddons(getActivePack());
    saveState();
    return state.addons;
  }

  function upsertAddon(addon) {
    var n = normalizeAddonEntry(addon);
    if (!n) return null;
    var list = state.addons.slice();
    var idx = -1;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === n.id || list[i].transportUrl === n.transportUrl) {
        idx = i;
        break;
      }
    }
    if (idx >= 0) list[idx] = n;
    else list.push(n);
    state.addons = list;
    saveState();
    return n;
  }

  function removeAddon(addonId) {
    state.addons = state.addons.filter(function (a) { return a.id !== addonId; });
    saveState();
  }

  function upsertWatchHistory(entry) {
    if (!entry || !entry.itemId) throw new Error('watchHistory entry missing itemId');
    var now = entry.updatedAtMs || Date.now();
    var prev = null;
    for (var i = 0; i < state.watchHistory.length; i++) {
      if (state.watchHistory[i].itemId === entry.itemId) {
        prev = state.watchHistory[i];
        break;
      }
    }
    var item = {
      itemId: String(entry.itemId),
      type: String(entry.type || entry.mediaType || (prev && prev.type) || 'movie'),
      title: String(entry.title || (prev && prev.title) || entry.itemId),
      poster: String(entry.poster || (prev && prev.poster) || ''),
      videoId: String(entry.videoId || (prev && prev.videoId) || entry.itemId),
      episodeTitle: String(entry.episodeTitle || (prev && prev.episodeTitle) || ''),
      positionMs: Number(entry.positionMs || 0),
      durationMs: Number(entry.durationMs || 0),
      updatedAtMs: now,
      lastStreamContext: entry.lastStreamContext || (prev && prev.lastStreamContext) || null
    };

    var filtered = state.watchHistory.filter(function (h) { return h.itemId !== item.itemId; });
    var ratio = item.durationMs > 0 ? item.positionMs / item.durationMs : 0;
    if (ratio < 0.94 || item.type === 'series') {
      filtered.unshift(item);
    }
    filtered.sort(function (a, b) { return (b.updatedAtMs || 0) - (a.updatedAtMs || 0); });
    state.watchHistory = filtered.slice(0, 60);
    saveState();
    return item;
  }

  function recordStreamSelection(itemId, context) {
    if (!itemId || !context) return;
    var found = false;
    for (var i = 0; i < state.watchHistory.length; i++) {
      if (state.watchHistory[i].itemId === itemId) {
        state.watchHistory[i].lastStreamContext = context;
        found = true;
        break;
      }
    }
    if (!found) {
      state.watchHistory.unshift({
        itemId: String(itemId),
        type: 'series',
        title: String(itemId),
        poster: '',
        videoId: String(itemId),
        episodeTitle: '',
        positionMs: 0,
        durationMs: 0,
        updatedAtMs: Date.now(),
        lastStreamContext: context
      });
    }
    saveState();
  }

  function getLastStreamContext(itemId) {
    for (var i = 0; i < state.watchHistory.length; i++) {
      if (state.watchHistory[i].itemId === itemId) {
        return state.watchHistory[i].lastStreamContext || null;
      }
    }
    return null;
  }

  function replaceWatchHistory(entries) {
    var list = Array.isArray(entries) ? entries.slice() : [];
    list.sort(function (a, b) { return (b.updatedAtMs || 0) - (a.updatedAtMs || 0); });
    state.watchHistory = list.slice(0, 60);
    saveState();
  }

  function toggleFavorite(fav) {
    if (!fav || !fav.itemId) throw new Error('favorite entry missing itemId');
    var idx = -1;
    for (var i = 0; i < state.favorites.length; i++) {
      if (state.favorites[i].itemId === fav.itemId) {
        idx = i;
        break;
      }
    }
    if (idx >= 0) {
      state.favorites.splice(idx, 1);
      saveState();
      return { isFavorite: false, favorites: state.favorites };
    }
    var item = {
      itemId: String(fav.itemId),
      type: String(fav.type || fav.mediaType || 'movie'),
      title: String(fav.title || fav.name || fav.itemId),
      poster: String(fav.poster || ''),
      imdbRating: String(fav.imdbRating || ''),
      releaseInfo: String(fav.releaseInfo || ''),
      addedAtMs: fav.addedAtMs || Date.now()
    };
    state.favorites.unshift(item);
    saveState();
    return { isFavorite: true, favorites: state.favorites };
  }

  function replaceFavorites(entries) {
    var list = Array.isArray(entries) ? entries.slice() : [];
    list.sort(function (a, b) { return (b.addedAtMs || 0) - (a.addedAtMs || 0); });
    state.favorites = list;
    saveState();
  }

  function isFavorite(itemId) {
    for (var i = 0; i < state.favorites.length; i++) {
      if (state.favorites[i].itemId === itemId) return true;
    }
    return false;
  }

  function upsertJellyfinServer(profile) {
    if (!profile || !profile.serverId) throw new Error('Jellyfin profile missing serverId');
    var p = Object.assign({}, profile, {
      updatedAtMs: profile.updatedAtMs || Date.now()
    });
    delete state.jellyfinTombstones[p.serverId];
    var idx = -1;
    for (var i = 0; i < state.jellyfinServers.length; i++) {
      if (state.jellyfinServers[i].serverId === p.serverId) {
        idx = i;
        break;
      }
    }
    if (idx >= 0) state.jellyfinServers[idx] = p;
    else state.jellyfinServers.push(p);
    saveState();
    return p;
  }

  function removeJellyfinServer(serverId) {
    state.jellyfinServers = state.jellyfinServers.filter(function (s) { return s.serverId !== serverId; });
    state.jellyfinTombstones[serverId] = Date.now();
    saveState();
  }

  function getJellyfinServer(serverId) {
    if (!serverId) return state.jellyfinServers[0] || null;
    for (var i = 0; i < state.jellyfinServers.length; i++) {
      if (state.jellyfinServers[i].serverId === serverId) return state.jellyfinServers[i];
    }
    return null;
  }

  function upsertYoutubeAccount(acc) {
    if (!acc || !acc.accountId) return null;
    var a = Object.assign({}, acc, { updatedAtMs: acc.updatedAtMs || Date.now() });
    delete state.youtubeTombstones[a.accountId];
    var idx = -1;
    for (var i = 0; i < state.youtubeAccounts.length; i++) {
      if (state.youtubeAccounts[i].accountId === a.accountId) {
        idx = i;
        break;
      }
    }
    if (idx >= 0) state.youtubeAccounts[idx] = a;
    else state.youtubeAccounts.push(a);
    if (!state.activeYoutubeAccountId) {
      state.activeYoutubeAccountId = a.accountId;
    }
    saveState();
    return a;
  }

  function removeYoutubeAccount(accountId) {
    if (!accountId) {
      state.youtubeAccounts = [];
      state.activeYoutubeAccountId = '';
      saveState();
      return;
    }
    state.youtubeAccounts = state.youtubeAccounts.filter(function (a) { return a.accountId !== accountId; });
    state.youtubeTombstones[accountId] = Date.now();
    if (state.activeYoutubeAccountId === accountId) {
      state.activeYoutubeAccountId = state.youtubeAccounts[0] ? state.youtubeAccounts[0].accountId : '';
    }
    saveState();
  }

  function getActiveYoutubeAccount() {
    if (state.activeYoutubeAccountId) {
      for (var i = 0; i < state.youtubeAccounts.length; i++) {
        if (state.youtubeAccounts[i].accountId === state.activeYoutubeAccountId) {
          return state.youtubeAccounts[i];
        }
      }
    }
    return state.youtubeAccounts[0] || null;
  }

  function setTwitchProfile(profile) {
    state.twitchProfile = profile ? Object.assign({}, profile, { updatedAtMs: profile.updatedAtMs || Date.now() }) : null;
    saveState();
  }

  function getActiveCreatorFollows(platform) {
    return state.creatorFollows.filter(function (f) {
      var active = (f.removedAtMs || 0) <= (f.addedAtMs || 0);
      if (!active) return false;
      if (platform && f.platform !== platform) return false;
      return true;
    });
  }

  function toggleCreatorFollow(entry) {
    var now = Date.now();
    for (var i = 0; i < state.creatorFollows.length; i++) {
      var f = state.creatorFollows[i];
      if (f.platform === entry.platform && f.channelId === entry.channelId) {
        var active = (f.removedAtMs || 0) <= (f.addedAtMs || 0);
        if (active) {
          f.removedAtMs = now;
          saveState();
          return false;
        } else {
          f.addedAtMs = now;
          f.removedAtMs = null;
          f.name = entry.name || f.name;
          f.handle = entry.handle || f.handle || '';
          f.avatarUrl = entry.avatarUrl || f.avatarUrl || '';
          saveState();
          return true;
        }
      }
    }
    state.creatorFollows.push({
      platform: String(entry.platform || ''),
      channelId: String(entry.channelId || ''),
      name: String(entry.name || entry.channelId || ''),
      handle: String(entry.handle || ''),
      avatarUrl: String(entry.avatarUrl || ''),
      addedAtMs: now,
      removedAtMs: null
    });
    saveState();
    return true;
  }

  function replaceCreatorFollows(list) {
    state.creatorFollows = Array.isArray(list) ? list : [];
    saveState();
  }

  function getMultipassProfile() {
    return Object.assign({}, state.multipass, {
      vaultUnlocked: Boolean(state.multipassVmkB64)
    });
  }

  function saveMultipassProfile(mp) {
    state.multipass = Object.assign({}, state.multipass, mp, {
      vaultUnlocked: Boolean(state.multipassVmkB64)
    });
    saveState();
  }

  function clearMultipassProfile() {
    state.multipass = defaultState().multipass;
    state.multipassVmkB64 = '';
    saveState();
  }

  function sealMultipassVmk(vmkBuf) {
    if (!Buffer.isBuffer(vmkBuf) || vmkBuf.length !== 32) {
      throw new Error('VMK must be 32 bytes');
    }
    state.multipassVmkB64 = vmkBuf.toString('base64');
    state.multipass.vaultUnlocked = true;
    saveState();
  }

  function unsealMultipassVmk() {
    if (!state.multipassVmkB64) return null;
    try {
      var b = Buffer.from(state.multipassVmkB64, 'base64');
      return b.length === 32 ? b : null;
    } catch (e) {
      return null;
    }
  }

  return {
    dataDir: dataDir,
    cacheDir: cacheDir,
    torrentCacheDir: torrentCacheDir,
    mkdirpSync: mkdirpSync,
    rimrafSync: rimrafSync,
    getStateRaw: function () { return state; },
    saveState: saveState,
    getPublicState: getPublicState,
    patchState: patchState,
    loadActivePack: loadActivePack,
    getActivePack: getActivePack,
    getPackSummary: getPackSummary,
    importPack: importPack,
    clearPack: clearPack,
    getStremioAuth: getStremioAuth,
    setStremioAuth: setStremioAuth,
    getAddons: function () { return state.addons.slice(); },
    setAddons: setAddons,
    upsertAddon: upsertAddon,
    removeAddon: removeAddon,
    getWatchHistory: function () { return state.watchHistory.slice(); },
    upsertWatchHistory: upsertWatchHistory,
    recordStreamSelection: recordStreamSelection,
    getLastStreamContext: getLastStreamContext,
    replaceWatchHistory: replaceWatchHistory,
    getFavorites: function () { return state.favorites.slice(); },
    toggleFavorite: toggleFavorite,
    replaceFavorites: replaceFavorites,
    isFavorite: isFavorite,
    getJellyfinServers: function () { return state.jellyfinServers.slice(); },
    getJellyfinTombstones: function () { return Object.assign({}, state.jellyfinTombstones); },
    upsertJellyfinServer: upsertJellyfinServer,
    removeJellyfinServer: removeJellyfinServer,
    getJellyfinServer: getJellyfinServer,
    getYoutubeAccounts: function () { return state.youtubeAccounts.slice(); },
    getYoutubeTombstones: function () { return Object.assign({}, state.youtubeTombstones); },
    getActiveYoutubeAccount: getActiveYoutubeAccount,
    upsertYoutubeAccount: upsertYoutubeAccount,
    removeYoutubeAccount: removeYoutubeAccount,
    getTwitchProfile: function () { return state.twitchProfile; },
    setTwitchProfile: setTwitchProfile,
    getCreatorFollows: function () { return state.creatorFollows.slice(); },
    getActiveCreatorFollows: getActiveCreatorFollows,
    toggleCreatorFollow: toggleCreatorFollow,
    replaceCreatorFollows: replaceCreatorFollows,
    getMultipassProfile: getMultipassProfile,
    saveMultipassProfile: saveMultipassProfile,
    clearMultipassProfile: clearMultipassProfile,
    sealMultipassVmk: sealMultipassVmk,
    unsealMultipassVmk: unsealMultipassVmk
  };
}

module.exports = {
  createStore: createStore,
  getPublicBaseAddons: getPublicBaseAddons,
  normalizeAddonEntry: normalizeAddonEntry,
  mkdirpSync: mkdirpSync,
  rimrafSync: rimrafSync
};
