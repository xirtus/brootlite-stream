'use strict';

var fs = require('fs');
var http = require('http');
var urlMod = require('url');
var httpClient = require('./http_client');
var storeMod = require('./store');
var resolvers = require('./resolvers');
var p2pMod = require('./p2p');
var protocols = require('./protocols');
var multipassMod = require('./multipass');

var DEFAULT_PORT = 19477;
var SERVICE_VERSION = '2.1.5';

function sendCorsHeaders(res, extra) {
  var hdrs = Object.assign({
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, HEAD, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Range, If-Range, Authorization',
    'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges'
  }, extra || {});
  return hdrs;
}

function sendJson(res, statusCode, obj) {
  var body = Buffer.from(JSON.stringify(obj), 'utf8');
  var hdrs = sendCorsHeaders(res, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': String(body.length),
    'Cache-Control': 'no-store'
  });
  res.writeHead(statusCode, hdrs);
  res.end(body);
}

function readJsonBody(req, maxBytes) {
  var limit = maxBytes || 4 * 1024 * 1024;
  return new Promise(function (resolve, reject) {
    var chunks = [];
    var total = 0;
    req.on('data', function (chunk) {
      total += chunk.length;
      if (total > limit) {
        reject(new Error('Request body too large'));
        try { req.destroy(); } catch (e) {}
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', function () {
      if (chunks.length === 0) return resolve({});
      try {
        var text = Buffer.concat(chunks).toString('utf8');
        resolve(text.trim() ? JSON.parse(text) : {});
      } catch (e) {
        reject(new Error('Invalid JSON body: ' + e.message));
      }
    });
    req.on('error', reject);
  });
}

function makeProxyUrl(mediaBaseUrl, rawUrl, headers, isHls) {
  var uB64 = httpClient.b64UrlEncode(rawUrl);
  var hasHeaders = headers && typeof headers === 'object' && Object.keys(headers).length > 0;
  var hB64 = hasHeaders ? httpClient.b64UrlEncode(JSON.stringify(headers)) : '';
  var route = isHls ? '/proxy/hls.m3u8' : '/proxy/stream';
  return mediaBaseUrl + route + '?u=' + uB64 + (hB64 ? '&h=' + hB64 : '');
}

function sortMetaItems(items, sortMode) {
  var arr = items.slice();
  if (sortMode === 'TOP_RATED') {
    arr.sort(function (a, b) {
      return (parseFloat(b.imdbRating) || 0) - (parseFloat(a.imdbRating) || 0);
    });
  } else if (sortMode === 'YEAR_DESC') {
    arr.sort(function (a, b) {
      var ya = parseInt(String(a.releaseInfo || '').slice(0, 4), 10) || 0;
      var yb = parseInt(String(b.releaseInfo || '').slice(0, 4), 10) || 0;
      return yb - ya;
    });
  } else if (sortMode === 'TITLE_ASC') {
    arr.sort(function (a, b) {
      return String(a.name || '').localeCompare(String(b.name || ''));
    });
  }
  return arr;
}

function createServiceEngine(opts) {
  opts = opts || {};
  var store = storeMod.createStore(opts.dataDir);
  var dashManifests = {};
  var currentPort = 0;
  var hasLunaService = false;
  var lunaActivity = null;
  var keepAliveSubs = {};
  var keepAliveInterval = null;
  var lunaService = null;

  function getMediaBaseUrl() {
    return 'http://127.0.0.1:' + (currentPort || DEFAULT_PORT);
  }

  function updateKeepAliveState() {
    var hasSubs = Object.keys(keepAliveSubs).length > 0;
    var hasTorrent = p2p && p2p.hasActiveSession();
    var shouldStayAlive = hasSubs || hasTorrent;

    if (shouldStayAlive && !keepAliveInterval) {
      keepAliveInterval = setInterval(function () {
        var keys = Object.keys(keepAliveSubs);
        for (var i = 0; i < keys.length; i++) {
          var sub = keepAliveSubs[keys[i]];
          try {
            sub.respond({
              returnValue: true,
              alive: true,
              mediaBaseUrl: getMediaBaseUrl(),
              activeTorrent: p2p.hasActiveSession()
            });
          } catch (e) {
            delete keepAliveSubs[keys[i]];
          }
        }
      }, 15000);
    } else if (!shouldStayAlive && keepAliveInterval) {
      clearInterval(keepAliveInterval);
      keepAliveInterval = null;
    }

    if (lunaService && lunaService.activityManager) {
      try {
        if (shouldStayAlive && !lunaActivity && typeof lunaService.activityManager.create === 'function') {
          lunaService.activityManager.create('brootliteKeepAlive', function (act) {
            lunaActivity = act;
          });
        } else if (!shouldStayAlive && lunaActivity && typeof lunaService.activityManager.complete === 'function') {
          var actRef = lunaActivity;
          lunaActivity = null;
          lunaService.activityManager.complete(actRef, true, function () {});
        }
      } catch (e) {}
    }
  }

  var p2p = p2pMod.createP2PEngine(store, {
    onActivityChange: function () {
      updateKeepAliveState();
    }
  });

  var stremio = protocols.createStremioClient(store);
  var swarm = protocols.createSwarmClient(store);
  var jellyfin = protocols.createJellyfinClient(store);
  var youtube = protocols.createYouTubeClient(store, dashManifests);
  var twitch = protocols.createTwitchClient(store);
  var odysee = protocols.createOdyseeClient();
  var rumble = protocols.createRumbleClient();
  var multipass = multipassMod.createMultipassClient(store, stremio, jellyfin);

  function getDirectSourcesList() {
    var pack = store.getActivePack();
    var filter = store.getStateRaw().swarmFilter || 'SAFE_ONLY';
    var list = Array.isArray(pack.directSources) ? pack.directSources : [];
    return list.filter(function (s) {
      if (!s || !s.id) return false;
      if (filter === 'SAFE_ONLY' && s.adult) return false;
      if (filter === 'ADULT_ONLY' && !s.adult) return false;
      return true;
    });
  }

  function findDirectSource(sourceId) {
    var pack = store.getActivePack();
    var list = Array.isArray(pack.directSources) ? pack.directSources : [];
    for (var i = 0; i < list.length; i++) {
      if (list[i] && list[i].id === sourceId) return list[i];
    }
    return null;
  }

  function handleRpc(method, params) {
    params = params || {};
    var m = String(method || '').trim();
    if (!m) return Promise.reject(new Error('Missing RPC method'));

    // ── System, State, History, Favorites, & Pack Management ────────────────
    if (m === 'system.boot') {
      var packSum = store.getPackSummary();
      return Promise.resolve({
        version: SERVICE_VERSION,
        platform: hasLunaService ? 'webos' : 'node',
        hasLunaService: hasLunaService,
        mediaBaseUrl: getMediaBaseUrl(),
        httpPort: currentPort,
        dataDir: store.dataDir,
        pack: packSum,
        state: store.getPublicState()
      });
    }

    if (m === 'state.get') {
      return Promise.resolve(store.getPublicState());
    }

    if (m === 'state.patch') {
      if (params.installAddonUrl) {
        return stremio.installAddon(params.installAddonUrl).then(function () {
          return store.patchState(params);
        });
      }
      return Promise.resolve(store.patchState(params));
    }

    if (m === 'history.upsert') {
      var saved = store.upsertWatchHistory(params);
      if (params.syncCloud !== false) {
        stremio.pushWatchProgress(saved).catch(function () {});
      }
      return Promise.resolve({ saved: true, entry: saved, watchHistory: store.getWatchHistory() });
    }

    if (m === 'favorites.toggle') {
      return Promise.resolve(store.toggleFavorite(params.item || params.entry || params));
    }

    if (m === 'pack.get') {
      return Promise.resolve(store.getPackSummary());
    }

    if (m === 'pack.import') {
      var srcStr = typeof params.source === 'string' ? params.source.trim() : '';
      var urlCandidate =
        params.url ||
        (/^https?:\/\//i.test(srcStr) ? srcStr : '') ||
        (typeof params.data === 'string' && /^https?:\/\//i.test(params.data.trim()) ? params.data.trim() : '');
      if (urlCandidate) {
        return httpClient.getJson(urlCandidate, { desktopUa: true, timeoutMs: 15000 }).then(function (jsonObj) {
          var summary = store.importPack(jsonObj);
          return { imported: true, pack: summary };
        });
      }
      var fileCandidate = params.filePath || (srcStr && srcStr.charAt(0) !== '{' ? srcStr : '');
      if (fileCandidate && fs.existsSync(String(fileCandidate))) {
        var fileRaw = fs.readFileSync(String(fileCandidate), 'utf8');
        var fileObj = JSON.parse(fileRaw);
        return Promise.resolve({ imported: true, pack: store.importPack(fileObj) });
      }
      var rawObj = (srcStr && srcStr.charAt(0) === '{' ? srcStr : null) || params.pack || params.json || params.data || params;
      if (typeof rawObj === 'string') {
        rawObj = JSON.parse(rawObj);
      }
      return Promise.resolve({ imported: true, pack: store.importPack(rawObj) });
    }

    if (m === 'pack.clear') {
      return Promise.resolve({ cleared: true, pack: store.clearPack() });
    }

    // ── Stremio ─────────────────────────────────────────────────────────────
    if (m === 'stremio.startQrAuth') {
      return stremio.startQrAuth();
    }

    if (m === 'stremio.pollQrAuth') {
      if (!params.code) return Promise.reject(new Error('Missing code'));
      return stremio.pollQrAuth(params.code);
    }

    if (m === 'stremio.syncAddons') {
      if (params.url) {
        return stremio.installAddon(params.url).then(function (addon) {
          return { installedAddon: addon, addons: store.getAddons() };
        });
      }
      return stremio.syncAccountEverything(params.authKey || null);
    }

    if (m === 'stremio.logout') {
      store.setStremioAuth(null, '', Date.now());
      return Promise.resolve({ loggedOut: true });
    }

    // ── Catalog & Detail ────────────────────────────────────────────────────
    if (m === 'catalog.board') {
      var addons = store.getAddons();
      var boardRowsDef = [];
      for (var i = 0; i < addons.length; i++) {
        var ad = addons[i];
        var cats = Array.isArray(ad.catalogs) ? ad.catalogs : [];
        for (var j = 0; j < cats.length; j++) {
          var cat = cats[j];
          if (ad.id === 'com.linvo.cinemeta' || boardRowsDef.length < 6) {
            boardRowsDef.push({ addon: ad, catalog: cat });
          }
        }
      }
      boardRowsDef = boardRowsDef.slice(0, 6);

      var rowTasks = boardRowsDef.map(function (def) {
        return stremio.fetchCatalog(def.addon, def.catalog.type, def.catalog.id, { skip: 0 }).then(function (items) {
          return {
            id: def.addon.id + ':' + def.catalog.type + ':' + def.catalog.id,
            title: def.catalog.name + ' ' + (def.catalog.type === 'series' ? 'Series' : 'Movies'),
            type: def.catalog.type,
            addonId: def.addon.id,
            addonName: def.addon.name || '',
            catalogId: def.catalog.id,
            items: items.slice(0, 20)
          };
        }).catch(function () { return null; });
      });

      var jfServers = store.getJellyfinServers();
      var jfPromise = jfServers.length > 0
        ? jellyfin.browse({ serverId: jfServers[0].serverId, action: 'home' }).catch(function () { return null; })
        : Promise.resolve(null);

      return Promise.all([Promise.all(rowTasks), jfPromise]).then(function (res) {
        var rows = (res[0] || []).filter(function (r) { return r && r.items && r.items.length > 0; });
        var jfHome = res[1];
        return {
          continueWatching: store.getWatchHistory(),
          favorites: store.getFavorites(),
          rows: rows,
          rails: rows,
          directSources: getDirectSourcesList().map(function (s) {
            return { id: s.id, name: s.name, kind: s.kind, adult: Boolean(s.adult) };
          }),
          jellyfin: {
            servers: store.getPublicState().jellyfinServers,
            home: jfHome
          },
          pack: store.getPackSummary()
        };
      });
    }

    if (m === 'catalog.discover') {
      var query = String(params.query || params.search || '').trim();
      var reqType = params.type || 'movie';
      var sortMode = params.sortMode || params.sort || store.getStateRaw().discoverSortMode || 'POPULAR';

      if (query && !params.catalogId && !params.addonId) {
        return stremio.searchCatalogs(query, reqType).then(function (items) {
          return {
            items: sortMetaItems(items, sortMode),
            query: query,
            type: reqType,
            genres: [],
            catalogs: []
          };
        });
      }

      var allAddons = store.getAddons();
      var availableCatalogs = [];
      var targetAddon = null;
      var targetCat = null;
      for (var ai = 0; ai < allAddons.length; ai++) {
        var candAddon = allAddons[ai];
        var acats = Array.isArray(candAddon.catalogs) ? candAddon.catalogs : [];
        for (var ci = 0; ci < acats.length; ci++) {
          var cc = acats[ci];
          if (reqType && reqType !== 'all' && cc.type !== reqType) continue;
          availableCatalogs.push({
            id: cc.id,
            name: (candAddon.name && candAddon.id !== 'com.linvo.cinemeta' ? candAddon.name + ' · ' : '') + (cc.name || cc.id),
            type: cc.type,
            addonId: candAddon.id,
            genres: cc.genres || []
          });
          if (!targetCat) {
            if (params.addonId && candAddon.id !== params.addonId) continue;
            if (params.transportUrl && candAddon.transportUrl !== params.transportUrl) continue;
            if (params.catalogId && cc.id !== params.catalogId) continue;
            targetAddon = candAddon;
            targetCat = cc;
          }
        }
      }

      if (!targetAddon || !targetCat) {
        return Promise.resolve({ items: [], genres: [], catalogs: availableCatalogs, type: reqType });
      }

      return stremio.fetchCatalog(targetAddon, targetCat.type, targetCat.id, {
        query: query || null,
        genre: params.genre || null,
        year: params.year || null,
        skip: params.skip || 0
      }).then(function (items) {
        return {
          items: sortMetaItems(items, sortMode),
          genres: targetCat.genres || [],
          catalogs: availableCatalogs,
          catalog: targetCat,
          addonId: targetAddon.id
        };
      });
    }

    if (m === 'catalog.detail') {
      var itemId = String(params.itemId || params.id || '').trim();
      if (!itemId) return Promise.reject(new Error('Missing itemId'));
      var mediaType = params.type || 'movie';
      var historyEntry = store.getWatchHistory().filter(function (h) { return h.itemId === itemId; })[0] || null;
      var favFlag = store.isFavorite(itemId);

      if (itemId.indexOf('jf:') === 0) {
        return jellyfin.browse({ action: 'detail', itemId: itemId, serverId: params.serverId }).then(function (res) {
          return { meta: res.meta, isFavorite: favFlag, historyEntry: historyEntry };
        });
      }
      return stremio.fetchMetaDetail(mediaType, itemId).then(function (meta) {
        return { meta: meta, isFavorite: favFlag, historyEntry: historyEntry };
      });
    }

    // ── Streams & Torrents ──────────────────────────────────────────────────
    if (m === 'streams.fetch') {
      var sType = params.type || 'movie';
      var rawBaseId = String(params.videoId || params.id || params.itemId || '').trim();
      if (!rawBaseId) return Promise.reject(new Error('Missing videoId'));
      var videoId =
        sType === 'series' && params.season && params.episode && rawBaseId.indexOf(':') === -1
          ? rawBaseId + ':' + params.season + ':' + params.episode
          : rawBaseId;
      var rootItemId = params.itemId || params.id || videoId.split(':')[0];
      var prevCtx = params.prevStreamContext || store.getLastStreamContext(rootItemId);

      return Promise.all([
        stremio.fetchStreamsFromAddons(sType, videoId),
        swarm.fetchImdbFallback(videoId),
        stremio.fetchSubtitles(sType, videoId, getMediaBaseUrl())
      ]).then(function (res) {
        var combined = (res[0] || []).concat(res[1] || []);
        var ranked = protocols.dedupeAndRankStreams(combined, prevCtx);
        return {
          streams: ranked,
          subtitles: res[2] || []
        };
      });
    }

    if (m === 'streams.resolvePlayable') {
      var cand = params.candidate || params.stream || params;
      var metaCtx = params.meta || {};
      var directUrl = cand.directUrl || (cand.url && !/^magnet:/i.test(cand.url) ? cand.url : '');
      var reqHeaders = cand.requestHeaders || cand.headers || {};
      var recId = params.recordItemId || metaCtx.id || cand.itemId;

      if (recId) {
        store.recordStreamSelection(recId, {
          bingeGroup: cand.bingeGroup || '',
          addonName: cand.addonName || cand.providerName || '',
          title: cand.title || '',
          filename: cand.filename || '',
          wasDirectHttp: Boolean(directUrl),
          resolutionScore: cand.resolutionScore || 0
        });
      }

      var lookupSubVideoId =
        params.videoId ||
        cand.videoId ||
        (metaCtx.id && metaCtx.season && metaCtx.episode
          ? metaCtx.id + ':' + metaCtx.season + ':' + metaCtx.episode
          : metaCtx.id || '');
      var subLookupType = params.type || metaCtx.type || 'movie';
      var subsTask =
        lookupSubVideoId && /^tt\d+/i.test(lookupSubVideoId)
          ? stremio.fetchSubtitles(subLookupType, lookupSubVideoId, getMediaBaseUrl()).catch(function () { return []; })
          : Promise.resolve([]);

      if (directUrl) {
        var resolveChain = params.resolveWeb
          ? resolvers.resolveWebVideoUrl(directUrl, reqHeaders)
          : Promise.resolve({
              streamUrl: directUrl,
              protocol: /\.m3u8(\?|$)/i.test(directUrl) ? 'HLS' : 'PROGRESSIVE',
              headers: reqHeaders,
              needsProxy: Object.keys(reqHeaders).length > 0
            });

        return Promise.all([resolveChain, subsTask]).then(function (pair) {
          var r = pair[0];
          var subs = pair[1] || [];
          var isHls = r.protocol === 'HLS' || /\.m3u8(\?|$)/i.test(r.streamUrl);
          var needsProxy = r.needsProxy || Object.keys(r.headers || {}).length > 0;
          var playUrl = needsProxy ? makeProxyUrl(getMediaBaseUrl(), r.streamUrl, r.headers, isHls) : r.streamUrl;
          return {
            url: playUrl,
            streamUrl: playUrl,
            playUrl: playUrl,
            rawStreamUrl: r.streamUrl,
            isTorrent: false,
            protocol: r.protocol,
            requestHeaders: r.headers || {},
            qualityChoices: r.qualityChoices || [],
            subtitles: subs
          };
        });
      }

      var magnetOrHash = cand.magnetOrUrl || cand.magnetUri || cand.infoHash || cand.url;
      if (!magnetOrHash) {
        return Promise.reject(new Error('Stream candidate has neither directUrl nor infoHash/magnet'));
      }

      var epHint =
        params.episodeHint ||
        (metaCtx.season && metaCtx.episode ? { season: metaCtx.season, episode: metaCtx.episode } : null);

      return Promise.all([
        p2p.startTorrentStream({
          magnetOrUrl: magnetOrHash,
          infoHash: cand.infoHash,
          fileIdx: typeof cand.fileIdx === 'number' ? cand.fileIdx : undefined,
          sources: cand.sources || cand.trackers || [],
          title: cand.title || cand.fullReleaseTitle || '',
          videoId: lookupSubVideoId,
          episodeHint: epHint
        }, getMediaBaseUrl()),
        subsTask
      ]).then(function (pair) {
        var tRes = pair[0] || {};
        var subs = pair[1] || [];
        var finalUrl = tRes.playUrl || tRes.streamUrl || tRes.url || '';
        return Object.assign({}, tRes, {
          url: finalUrl,
          streamUrl: finalUrl,
          playUrl: finalUrl,
          subtitles: (tRes.subtitles || []).concat(subs)
        });
      });
    }

    if (m === 'torrent.stats') {
      return Promise.resolve(p2p.getStats(params.sessionId || params.infoHash || null));
    }

    if (m === 'torrent.stop') {
      var targetStop = params.sessionId || params.infoHash || null;
      var stopPromise = targetStop ? p2p.stopSession(targetStop) : p2p.stopAll();
      return stopPromise.then(function () {
        return { stopped: true };
      });
    }

    // ── Swarm & Addonless Direct Sources ────────────────────────────────────
    if (m === 'swarm.search') {
      var packFeeds = ((store.getActivePack() && store.getActivePack().featuredFeeds) || []).map(function (f) {
        return { key: f.key, label: f.label, adult: Boolean(f.adult) };
      });
      if (params.feedKey && !params.query) {
        return swarm.fetchFeaturedFeed(params.feedKey).then(function (streams) {
          return { items: streams, streams: streams, feeds: packFeeds };
        });
      }
      return swarm.searchAll({
        query: params.query || '',
        imdbId: params.imdbId || '',
        filter: params.filter || 'ALL',
        tabGroup: params.tabGroup || (params.category && params.category !== 'all' ? params.category : null)
      }).then(function (streams) {
        return { items: streams, streams: streams, feeds: packFeeds };
      });
    }

    if (m === 'sources.catalog') {
      var available = getDirectSourcesList();
      var providerList = available.map(function (s) {
        return { id: s.id, name: s.name, kind: s.kind, adult: Boolean(s.adult), collection: s.collection || '' };
      });
      var chosenId = params.providerId || params.sourceId || (available[0] && available[0].id) || '';
      if (!chosenId) {
        return Promise.resolve({ providers: providerList, sources: providerList, items: [] });
      }
      var src = findDirectSource(chosenId);
      if (!src) return Promise.reject(new Error('Unknown direct source: ' + chosenId));
      return resolvers.catalogDirectSource(src, params).then(function (catRes) {
        return Object.assign({}, catRes, {
          providerId: chosenId,
          providers: providerList,
          sources: providerList,
          items: (catRes && catRes.items) || []
        });
      });
    }

    if (m === 'sources.resolve') {
      var rawItemId = String(params.itemId || params.id || '');
      var sourceId = params.providerId || params.sourceId || '';
      var itemUrl = params.itemUrl || params.url || '';
      var rawId = rawItemId;
      if (rawItemId.indexOf('src:') === 0) {
        var parts = rawItemId.split(':');
        if (parts.length >= 3) {
          sourceId = sourceId || parts[1];
          rawId = parts.slice(2).join(':');
        }
      }

      var resolvePromise;
      if (sourceId && rawId) {
        var dSrc = findDirectSource(sourceId);
        if (!dSrc) return Promise.reject(new Error('Unknown direct source: ' + sourceId));
        resolvePromise = resolvers.resolveDirectSource(dSrc, rawId, Object.assign({ url: itemUrl }, params.item || params));
      } else if (itemUrl) {
        resolvePromise = resolvers.resolveWebVideoUrl(itemUrl, params.headers || {});
      } else {
        return Promise.reject(new Error('sources.resolve requires providerId+itemId or itemUrl'));
      }

      return resolvePromise.then(function (res) {
        var isHls = res.protocol === 'HLS' || /\.m3u8(\?|$)/i.test(res.streamUrl);
        var playUrl = res.needsProxy
          ? makeProxyUrl(getMediaBaseUrl(), res.streamUrl, res.headers, isHls)
          : res.streamUrl;
        var subs = (res.subtitles || []).map(function (sb) {
          return Object.assign({}, sb, {
            vttUrl: getMediaBaseUrl() + '/sub.vtt?u=' + httpClient.b64UrlEncode(sb.url)
          });
        });
        return Object.assign({}, res, {
          url: playUrl,
          streamUrl: playUrl,
          playUrl: playUrl,
          rawStreamUrl: res.streamUrl,
          subtitles: subs
        });
      });
    }

    // ── Jellyfin Multi-Server ───────────────────────────────────────────────
    if (m === 'jellyfin.discoverLan') {
      return jellyfin.discoverLanServers(params.timeoutMs).then(function (servers) {
        return { servers: servers };
      });
    }

    if (m === 'jellyfin.addServerQuickConnect') {
      var qcUrl = params.serverUrl || params.lanUrl || params.baseUrl || params.url;
      return jellyfin.initiateQuickConnect(qcUrl);
    }

    if (m === 'jellyfin.pollQuickConnect') {
      return jellyfin.pollQuickConnect(
        params.serverUrl || params.lanUrl || params.baseUrl || params.url,
        params.remoteUrl,
        params.secret
      );
    }

    if (m === 'jellyfin.loginPassword') {
      return jellyfin.loginPassword(
        params.serverUrl || params.lanUrl || params.baseUrl || params.url,
        params.remoteUrl,
        params.username,
        params.password || ''
      ).then(function (server) {
        return { server: server };
      });
    }

    if (m === 'jellyfin.removeServer') {
      if (!params.serverId) return Promise.reject(new Error('Missing serverId'));
      store.removeJellyfinServer(params.serverId);
      return Promise.resolve({ removed: true });
    }

    if (m === 'jellyfin.browse') {
      return jellyfin.browse(params);
    }

    if (m === 'jellyfin.resolvePlay') {
      return jellyfin.resolvePlay(params, getMediaBaseUrl()).then(function (r) {
        var u = (r && (r.playUrl || r.streamUrl || r.url)) || '';
        return Object.assign({}, r, { url: u, streamUrl: u, playUrl: u });
      });
    }

    if (m === 'jellyfin.report') {
      return jellyfin.report(params);
    }

    // ── Streaming (YouTube · Twitch · Odysee · Rumble) ──────────────────────
    if (m === 'streaming.feed') {
      var plat = String(params.platform || 'youtube').toLowerCase();
      if (plat === 'youtube') return youtube.feed(params);
      if (plat === 'twitch') return twitch.feed(params);
      if (plat === 'odysee') return odysee.feed(params);
      if (plat === 'rumble') return rumble.feed(params);
      return Promise.reject(new Error('Unsupported streaming platform: ' + plat));
    }

    if (m === 'streaming.resolve') {
      var rPlat = String(params.platform || 'youtube').toLowerCase();
      var rTarget = params.videoId || params.id || params.target || params.claim || params.url;
      var sPromise;
      if (rPlat === 'youtube') sPromise = youtube.resolveStream(rTarget, getMediaBaseUrl());
      else if (rPlat === 'twitch') sPromise = twitch.resolveStream(rTarget, getMediaBaseUrl());
      else if (rPlat === 'odysee') sPromise = odysee.resolveStream(rTarget);
      else if (rPlat === 'rumble') sPromise = rumble.resolveStream(rTarget);
      else return Promise.reject(new Error('Unsupported streaming platform: ' + rPlat));
      return sPromise.then(function (r) {
        var u = (r && (r.playUrl || r.streamUrl || r.url)) || '';
        return Object.assign({}, r, {
          url: u,
          streamUrl: u,
          playUrl: u,
          formats: r.qualityChoices || r.formats || []
        });
      });
    }

    if (m === 'streaming.authStart') {
      var aPlat = String(params.platform || 'youtube').toLowerCase();
      if (aPlat === 'youtube') return youtube.startAuth();
      if (aPlat === 'twitch') return twitch.startAuth();
      return Promise.reject(new Error('Device OAuth not supported for platform: ' + aPlat));
    }

    if (m === 'streaming.authPoll') {
      var pPlat = String((params.session && params.session.platform) || params.platform || 'youtube').toLowerCase();
      var sess = params.session || params;
      if (pPlat === 'youtube') return youtube.pollAuth(sess);
      if (pPlat === 'twitch') return twitch.pollAuth(sess);
      return Promise.reject(new Error('Device OAuth not supported for platform: ' + pPlat));
    }

    if (m === 'streaming.authLogout') {
      var lPlat = String(params.platform || 'youtube').toLowerCase();
      if (lPlat === 'youtube') {
        store.removeYoutubeAccount(params.accountId || null);
        return Promise.resolve({ loggedOut: true });
      }
      if (lPlat === 'twitch') {
        store.setTwitchProfile(null);
        return Promise.resolve({ loggedOut: true });
      }
      return Promise.reject(new Error('Unsupported platform for authLogout: ' + lPlat));
    }

    // ── Binge & Recommendations ─────────────────────────────────────────────
    if (m === 'binge.nextEpisode') {
      var curVid = String(
        params.currentVideoId ||
        params.videoId ||
        (params.itemId && params.season && params.episode ? params.itemId + ':' + params.season + ':' + params.episode : '')
      );
      if (!curVid) return Promise.reject(new Error('Missing currentVideoId'));
      var rootId = params.itemId || curVid.split(':')[0];
      var epsPromise = Array.isArray(params.episodes) && params.episodes.length > 0
        ? Promise.resolve(params.episodes)
        : (rootId.indexOf('jf:') === 0
            ? jellyfin.browse({ action: 'detail', itemId: rootId }).then(function (r) { return (r.meta && r.meta.videos) || []; })
            : stremio.fetchMetaDetail('series', rootId).then(function (meta) { return (meta && meta.videos) || []; }));

      return epsPromise.then(function (episodes) {
        var nextEp = protocols.findNextEpisode(episodes, curVid);
        if (!nextEp) return { nextEpisode: null, rankedStreams: [] };
        if (params.includeStreams === false || rootId.indexOf('jf:') === 0) {
          return { nextEpisode: nextEp, rankedStreams: [] };
        }
        var prevCtx = params.prevStreamContext || store.getLastStreamContext(rootId);
        return Promise.all([
          stremio.fetchStreamsFromAddons('series', nextEp.id),
          swarm.fetchImdbFallback(nextEp.id)
        ]).then(function (res) {
          var ranked = protocols.dedupeAndRankStreams((res[0] || []).concat(res[1] || []), prevCtx);
          return { nextEpisode: nextEp, rankedStreams: ranked };
        });
      });
    }

    if (m === 'binge.recommendations') {
      var limit = Math.max(1, Math.min(24, parseInt(params.limit || 14, 10) || 14));
      var seedPromise = params.seed
        ? Promise.resolve(params.seed)
        : (params.itemId
            ? handleRpc('catalog.detail', { itemId: params.itemId, type: params.type || 'movie' }).then(function (d) { return d.meta; })
            : Promise.resolve(null));

      return seedPromise.then(function (seed) {
        if (!seed) return { items: [] };
        if (String(seed.id).indexOf('jf:') === 0) {
          return jellyfin.browse({ action: 'similar', itemId: seed.id, limit: limit }).then(function (res) {
            return { items: res.items || [] };
          });
        }
        var primaryGenre = Array.isArray(seed.genres) && seed.genres[0] ? seed.genres[0] : null;
        var allAddons = store.getAddons();
        var tasks = [];
        for (var i = 0; i < allAddons.length && tasks.length < 3; i++) {
          var ad = allAddons[i];
          var cats = Array.isArray(ad.catalogs) ? ad.catalogs : [];
          for (var j = 0; j < cats.length && tasks.length < 3; j++) {
            var cat = cats[j];
            if (cat.type === (seed.type || 'movie') && (cat.supportsGenre || !primaryGenre)) {
              tasks.push(stremio.fetchCatalog(ad, cat.type, cat.id, { genre: primaryGenre }));
            }
          }
        }
        return Promise.all(tasks).then(function (batches) {
          var pool = [];
          for (var i = 0; i < batches.length; i++) pool = pool.concat(batches[i]);
          return { items: protocols.rankCatalogRecommendations(seed, pool, limit) };
        });
      });
    }

    // ── Multipass v2 E2EE ───────────────────────────────────────────────────
    if (m === 'multipass.pairStart') {
      return multipass.pairStart(params);
    }

    if (m === 'multipass.pairPoll') {
      return multipass.pairPoll(params);
    }

    if (m === 'multipass.sync') {
      return multipass.sync(params);
    }

    return Promise.reject(new Error('Unknown BrootLite RPC method: ' + m));
  }

  // ── HTTP Server (RPC + P2P Range + Header/HLS Proxy + Subtitles + DASH) ───
  function handleHttpRequest(req, res) {
    var reqMethod = String(req.method || 'GET').toUpperCase();
    if (reqMethod === 'OPTIONS') {
      res.writeHead(204, sendCorsHeaders(res, { 'Content-Length': '0' }));
      res.end();
      return;
    }

    var parsedUrl = httpClient.parseUrl(req.url || '/', true);
    var pathname = parsedUrl.pathname || '/';
    var query = parsedUrl.query || {};

    if (pathname === '/health' && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      var packSum = store.getPackSummary();
      sendJson(res, 200, {
        ok: true,
        version: SERVICE_VERSION,
        mediaBaseUrl: getMediaBaseUrl(),
        port: currentPort,
        edition: packSum.edition,
        packSource: packSum.activeSource,
        hasLunaService: hasLunaService,
        activeTorrent: p2p.hasActiveSession()
      });
      return;
    }

    if (pathname === '/rpc' && reqMethod === 'POST') {
      readJsonBody(req).then(function (body) {
        return handleRpc(body && body.method, (body && body.params) || {}).then(function (data) {
          sendJson(res, 200, { ok: true, data: data });
        });
      }).catch(function (err) {
        sendJson(res, 200, { ok: false, error: String((err && err.message) || err) });
      });
      return;
    }

    // Torrent stream: /torrent/:sessionId/stream (or /torrent/:sessionId)
    if (pathname.indexOf('/torrent/') === 0 && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      var rest = pathname.slice('/torrent/'.length);
      var sessionId = rest.split('/')[0];
      p2p.handleTorrentStreamRequest(req, res, sessionId);
      return;
    }

    // Subtitle on-the-fly WebVTT converter: /sub.vtt?u=<b64url>&h=<b64url_headers>
    if (pathname === '/sub.vtt' && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      if (!query.u) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Missing subtitle URL parameter u');
        return;
      }
      var subUrl = '';
      var subHeaders = {};
      try {
        subUrl = httpClient.b64UrlDecodeToString(query.u);
        if (query.h) subHeaders = JSON.parse(httpClient.b64UrlDecodeToString(query.h));
      } catch (e) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid base64url parameter');
        return;
      }
      if (!/^https?:\/\//i.test(subUrl)) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid subtitle scheme');
        return;
      }
      httpClient.getText(subUrl, { headers: subHeaders, desktopUa: true, timeoutMs: 12000 }).then(function (r) {
        var vtt = resolvers.convertSubtitleToVtt(r.text || '');
        var buf = Buffer.from(vtt, 'utf8');
        res.writeHead(200, sendCorsHeaders(res, {
          'Content-Type': 'text/vtt; charset=utf-8',
          'Content-Length': String(buf.length),
          'Cache-Control': 'no-store'
        }));
        if (reqMethod === 'HEAD') res.end();
        else res.end(buf);
      }).catch(function (err) {
        res.writeHead(502, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Subtitle fetch failed: ' + err.message);
      });
      return;
    }

    // Synthetic DASH MPD endpoint
    if (pathname.indexOf('/dash/') === 0 && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      var dashKey = pathname.slice('/dash/'.length).replace(/\.mpd$/i, '');
      var mpd = dashManifests[dashKey];
      if (!mpd) {
        res.writeHead(404, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Not Found');
        return;
      }
      var mpdBuf = Buffer.from(mpd, 'utf8');
      res.writeHead(200, sendCorsHeaders(res, {
        'Content-Type': 'application/dash+xml; charset=utf-8',
        'Content-Length': String(mpdBuf.length),
        'Cache-Control': 'no-store'
      }));
      if (reqMethod === 'HEAD') res.end();
      else res.end(mpdBuf);
      return;
    }

    // HLS Playlist Proxy + Ad Stripper: /proxy/hls.m3u8?u=<b64url>&h=<b64url_headers>
    if (pathname === '/proxy/hls.m3u8' && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      if (!query.u) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Missing parameter u');
        return;
      }
      var hlsUrl = '';
      var hlsHeaders = {};
      try {
        hlsUrl = httpClient.b64UrlDecodeToString(query.u);
        if (query.h) hlsHeaders = JSON.parse(httpClient.b64UrlDecodeToString(query.h));
      } catch (e) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid base64url parameter');
        return;
      }
      if (!/^https?:\/\//i.test(hlsUrl)) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid URL scheme');
        return;
      }

      httpClient.getText(hlsUrl, { headers: hlsHeaders, desktopUa: true, timeoutMs: 12000 }).then(function (r) {
        var hB64 = (query.h && String(query.h)) || '';
        var rewritten = resolvers.rewriteHlsPlaylistForProxy(r.text || '', r.url || hlsUrl, getMediaBaseUrl(), hB64);
        var buf = Buffer.from(rewritten, 'utf8');
        res.writeHead(200, sendCorsHeaders(res, {
          'Content-Type': 'application/vnd.apple.mpegurl; charset=utf-8',
          'Content-Length': String(buf.length),
          'Cache-Control': 'no-store'
        }));
        if (reqMethod === 'HEAD') res.end();
        else res.end(buf);
      }).catch(function (err) {
        res.writeHead(502, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Upstream HLS failed: ' + err.message);
      });
      return;
    }

    // Header-Injecting Stream Reverse Proxy: /proxy/stream?u=<b64url>&h=<b64url_headers>
    if (pathname === '/proxy/stream' && (reqMethod === 'GET' || reqMethod === 'HEAD')) {
      if (!query.u) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Missing parameter u');
        return;
      }
      var targetUrl = '';
      var injHeaders = {};
      try {
        targetUrl = httpClient.b64UrlDecodeToString(query.u);
        if (query.h) injHeaders = JSON.parse(httpClient.b64UrlDecodeToString(query.h));
      } catch (e) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid base64url parameter');
        return;
      }
      if (!/^https?:\/\//i.test(targetUrl)) {
        res.writeHead(400, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        res.end('Invalid URL scheme');
        return;
      }

      var fwdHeaders = Object.assign({}, injHeaders);
      if (req.headers && req.headers.range) fwdHeaders.Range = req.headers.range;
      if (req.headers && req.headers['if-range']) fwdHeaders['If-Range'] = req.headers['if-range'];

      httpClient.request(targetUrl, {
        method: reqMethod,
        headers: fwdHeaders,
        desktopUa: true,
        responseType: 'stream',
        timeoutMs: 20000
      }).then(function (up) {
        var outHdrs = sendCorsHeaders(res, {
          'Content-Type': up.headers['content-type'] || 'application/octet-stream',
          'Cache-Control': 'no-store'
        });
        if (up.headers['content-length']) outHdrs['Content-Length'] = up.headers['content-length'];
        if (up.headers['content-range']) outHdrs['Content-Range'] = up.headers['content-range'];
        if (up.headers['accept-ranges']) outHdrs['Accept-Ranges'] = up.headers['accept-ranges'];
        res.writeHead(up.status || 200, outHdrs);
        if (reqMethod === 'HEAD') {
          up.abort();
          res.end();
          return;
        }
        req.on('close', function () { up.abort(); });
        up.stream.on('error', function () {
          try { if (!res.finished) res.end(); } catch (e) {}
        });
        up.stream.pipe(res);
      }).catch(function (err) {
        if (!res.headersSent) {
          res.writeHead(502, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
        }
        res.end('Upstream proxy failed: ' + err.message);
      });
      return;
    }

    res.writeHead(404, sendCorsHeaders(res, { 'Content-Type': 'text/plain' }));
    res.end('Not Found');
  }

  var httpServer = http.createServer(handleHttpRequest);

  function registerWebosService() {
    try {
      // Dynamic require keeps bundler from failing and works cleanly on desktop Node
      var Service = require('webos-service');
      lunaService = new Service('app.brootlite.tv.service');
      hasLunaService = true;

      lunaService.register('rpc', function (message) {
        var payload = (message && message.payload) || {};
        var method = payload.method;
        var params = payload.params || {};
        handleRpc(method, params).then(function (data) {
          message.respond({ returnValue: true, ok: true, data: data });
        }).catch(function (err) {
          message.respond({
            returnValue: true,
            ok: false,
            error: String((err && err.message) || err)
          });
        });
      });

      lunaService.register('keepAlive', function (message) {
        var isSub = Boolean(message && message.isSubscription);
        if (isSub && message.uniqueToken) {
          keepAliveSubs[message.uniqueToken] = message;
          updateKeepAliveState();
        }
        message.respond({
          returnValue: true,
          alive: true,
          subscribed: isSub,
          mediaBaseUrl: getMediaBaseUrl(),
          activeTorrent: p2p.hasActiveSession()
        });
      }, function (cancelMessage) {
        if (cancelMessage && cancelMessage.uniqueToken) {
          delete keepAliveSubs[cancelMessage.uniqueToken];
          updateKeepAliveState();
        }
      });
    } catch (e) {
      hasLunaService = false;
    }
  }

  function start(startOpts) {
    startOpts = startOpts || {};
    var requestedPort = typeof startOpts.port === 'number' ? startOpts.port : DEFAULT_PORT;

    if (!startOpts.skipLuna) {
      registerWebosService();
    }

    return new Promise(function (resolve, reject) {
      function listenOn(port) {
        function onErr(err) {
          httpServer.removeListener('listening', onListen);
          if (port !== 0 && err && (err.code === 'EADDRINUSE' || err.code === 'EACCES')) {
            listenOn(0);
            return;
          }
          reject(err);
        }
        function onListen() {
          httpServer.removeListener('error', onErr);
          var addr = httpServer.address();
          currentPort = addr && typeof addr === 'object' ? addr.port : port;
          resolve({
            port: currentPort,
            mediaBaseUrl: getMediaBaseUrl(),
            hasLunaService: hasLunaService
          });
        }
        httpServer.once('error', onErr);
        httpServer.once('listening', onListen);
        httpServer.listen(port, '127.0.0.1');
      }
      listenOn(requestedPort);
    });
  }

  function shutdown() {
    if (keepAliveInterval) {
      clearInterval(keepAliveInterval);
      keepAliveInterval = null;
    }
    keepAliveSubs = {};
    if (lunaService && lunaService.activityManager && lunaActivity) {
      try {
        lunaService.activityManager.complete(lunaActivity, true, function () {});
      } catch (e) {}
      lunaActivity = null;
    }
    return p2p.stopAll().then(function () {
      return new Promise(function (resolve) {
        try {
          httpServer.close(function () { resolve(); });
        } catch (e) {
          resolve();
        }
      });
    });
  }

  return {
    start: start,
    shutdown: shutdown,
    handleRpc: handleRpc,
    getMediaBaseUrl: getMediaBaseUrl,
    store: store
  };
}

var defaultInstance = null;

if (!process.env.BROOTLITE_LIBRARY_MODE) {
  defaultInstance = createServiceEngine();
  defaultInstance.start({
    port: process.env.BROOTLITE_PORT ? parseInt(process.env.BROOTLITE_PORT, 10) : DEFAULT_PORT
  }).then(function (info) {
    console.log('[brootlite-service] Listening on ' + info.mediaBaseUrl + ' (luna=' + info.hasLunaService + ')');
  }).catch(function (err) {
    console.error('[brootlite-service] Failed to start:', err);
  });

  process.on('SIGTERM', function () {
    if (defaultInstance) defaultInstance.shutdown().then(function () { process.exit(0); });
  });
  process.on('SIGINT', function () {
    if (defaultInstance) defaultInstance.shutdown().then(function () { process.exit(0); });
  });
}

module.exports = {
  createServiceEngine: createServiceEngine,
  resolvers: resolvers,
  protocols: protocols,
  multipass: multipassMod,
  p2p: p2pMod,
  httpClient: httpClient,
  store: storeMod
};
