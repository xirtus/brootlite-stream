'use strict';

var dgram = require('dgram');
var urlMod = require('url');
var httpClient = require('./http_client');
var p2pMod = require('./p2p');

var TICKS_PER_MS = 10000;

// ─────────────────────────────────────────────────────────────────────────────
// 1. Stream Telemetry, LG webOS Audio Compatibility & Binge Ranking
// ─────────────────────────────────────────────────────────────────────────────

var IGNORED_RELEASE_TAGS = {
  '2160p': 1, '1440p': 1, '1080p': 1, '720p': 1, '480p': 1, '4k': 1, 'uhd': 1, 'fhd': 1, 'hd': 1,
  'hevc': 1, 'x265': 1, 'h265': 1, 'x264': 1, 'h264': 1, 'avc': 1, 'av1': 1, '10bit': 1, '10-bit': 1,
  'hdr': 1, 'hdr10': 1, 'dv': 1, 'web-dl': 1, 'webrip': 1, 'bluray': 1, 'bdrip': 1, 'remux': 1,
  'aac': 1, 'eac3': 1, 'ac3': 1, 'atmos': 1, 'dts': 1, 'truehd': 1, 'opus': 1, 'multi': 1, 'dual-audio': 1
};

function parseResolutionScore(text) {
  var low = String(text || '').toLowerCase();
  if (low.indexOf('2160p') !== -1 || low.indexOf('4k') !== -1 || low.indexOf('uhd') !== -1) return 2160;
  if (low.indexOf('1440p') !== -1 || low.indexOf('2k') !== -1) return 1440;
  if (low.indexOf('1080p') !== -1 || low.indexOf('fhd') !== -1) return 1080;
  if (low.indexOf('720p') !== -1 || /\bhd\b/.test(low)) return 720;
  if (low.indexOf('480p') !== -1 || /\bsd\b/.test(low)) return 480;
  return 0;
}

function resolutionLabel(score) {
  if (score >= 2160) return '4K 2160p';
  if (score >= 1440) return '1440p';
  if (score >= 1080) return '1080p';
  if (score >= 720) return '720p';
  if (score >= 480) return '480p';
  return '';
}

function parseSeedersFromText(text) {
  var m1 = String(text || '').match(/👤\s*(\d+)/);
  if (m1) return parseInt(m1[1], 10) || 0;
  var m2 = String(text || '').match(/(?:seeders?|seeds?|s:)\s*(\d+)/i);
  if (m2) return parseInt(m2[1], 10) || 0;
  return 0;
}

function parseHumanSizeBytes(raw) {
  var m = String(raw || '').match(/(\d+(?:\.\d+)?)\s*(TiB|TB|GiB|GB|MiB|MB|KiB|KB)\b/i);
  if (!m) return 0;
  var num = parseFloat(m[1]);
  if (isNaN(num)) return 0;
  var unit = m[2].toUpperCase();
  if (unit === 'TIB' || unit === 'TB') return Math.round(num * 1024 * 1024 * 1024 * 1024);
  if (unit === 'GIB' || unit === 'GB') return Math.round(num * 1024 * 1024 * 1024);
  if (unit === 'MIB' || unit === 'MB') return Math.round(num * 1024 * 1024);
  if (unit === 'KIB' || unit === 'KB') return Math.round(num * 1024);
  return Math.round(num);
}

function formatHumanBytes(bytes) {
  var n = Number(bytes || 0);
  if (!n || n <= 0) return '';
  var gb = n / (1024 * 1024 * 1024);
  if (gb >= 1.0) return gb.toFixed(2) + ' GB';
  var mb = n / (1024 * 1024);
  return mb.toFixed(1) + ' MB';
}

function extractCodecTags(text) {
  var low = String(text || '').toLowerCase();
  var out = [];
  if (low.indexOf('av1') !== -1 || low.indexOf('av01') !== -1) out.push('AV1');
  if (low.indexOf('hevc') !== -1 || low.indexOf('h265') !== -1 || low.indexOf('h.265') !== -1 || low.indexOf('x265') !== -1) {
    out.push('HEVC');
  } else if (low.indexOf('h264') !== -1 || low.indexOf('h.264') !== -1 || low.indexOf('x264') !== -1 || low.indexOf('avc') !== -1) {
    out.push('H.264');
  } else if (low.indexOf('vp9') !== -1) {
    out.push('VP9');
  }
  if (low.indexOf('10bit') !== -1 || low.indexOf('10-bit') !== -1 || low.indexOf('hi10p') !== -1) {
    out.push('10-bit');
  }
  if (/\bdv\b/.test(low) || /dolby[\s._-]?vision/.test(low) || low.indexOf('.dv.') !== -1 || low.indexOf('dovi') !== -1) {
    out.push('DV');
  } else if (low.indexOf('hdr10+') !== -1) {
    out.push('HDR10+');
  } else if (low.indexOf('hdr') !== -1) {
    out.push('HDR');
  }
  return out;
}

function extractAudioTags(text) {
  var low = String(text || '').toLowerCase();
  var tags = [];
  if (/(?:^|[^a-z0-9])(e-?ac-?3|ddp(?=[0-9.\s_-]|$)|dd\+|dolby[\s._-]*digital[\s._-]*plus)/.test(low)) tags.push('EAC3');
  if (/(?:^|[^a-z0-9])(ac-?3(?=[0-9.\s_-]|$)|dd(?=[257][.\s]?[01])|dolby[\s._-]*digital(?![\s._-]*plus))/.test(low)) tags.push('AC3');
  if (/(?:^|[^a-z0-9])aac(?=[0-9.\s_-]|$)/.test(low)) tags.push('AAC');
  if (/(?:^|[^a-z0-9])opus(?=[0-9.\s_-]|$)/.test(low)) tags.push('Opus');
  if (/(?:^|[^a-z0-9])atmos(?=[0-9.\s_-]|$)/.test(low)) tags.push('Atmos');
  if (/(?:^|[^a-z0-9])flac(?=[0-9.\s_-]|$)/.test(low)) tags.push('FLAC');
  if (/(?:^|[^a-z0-9])mp3(?=[0-9.\s_-]|$)/.test(low)) tags.push('MP3');
  if (/(?:^|[^a-z0-9])(dts(?:[\s._-]?(?:hd|ma|x))?|dca)(?=[0-9.\s_:-]|$)/.test(low)) tags.push('DTS');
  if (/(?:^|[^a-z0-9])(true[\s._-]?hd|mlp)(?=[0-9.\s_-]|$)/.test(low)) tags.push('TrueHD');
  return tags;
}

function extractIndexerName(text, addonName) {
  var combined = String(text || '');
  var markers = ['⚙️', '⚙'];
  for (var i = 0; i < markers.length; i++) {
    var mk = markers[i];
    var idx = combined.indexOf(mk);
    if (idx !== -1) {
      var after = combined.slice(idx + mk.length).trim();
      var endMatch = after.match(/[\n•|👤💾]/);
      var rawName = (endMatch ? after.slice(0, endMatch.index) : after).trim();
      if (rawName) return rawName;
    }
  }
  return addonName || '';
}

function extractReleaseGroup(text) {
  var str = String(text || '');
  var bRe = /\[([A-Za-z0-9._ -]{2,28})\]/g;
  var m;
  while ((m = bRe.exec(str)) !== null) {
    var tag = m[1].trim();
    var low = tag.toLowerCase();
    if (tag && !IGNORED_RELEASE_TAGS[low] && !/^\d+$/.test(low)) {
      return low;
    }
  }
  var stripped = str.trim().replace(/\.(mkv|mp4|avi|webm)$/i, '');
  var sMatch = stripped.match(/-([A-Za-z0-9]{2,18})(?:\s*\[.*\])?$/);
  if (sMatch && sMatch[1]) {
    var grp = sMatch[1].toLowerCase();
    if (!IGNORED_RELEASE_TAGS[grp]) return grp;
  }
  return null;
}

function scoreBingeSimilarity(prevCtx, cand) {
  if (!prevCtx) return { score: 0, reasons: [] };
  var score = 0;
  var reasons = [];
  var prevBlob = (prevCtx.title || '') + ' ' + (prevCtx.filename || '');
  var candBlob = (cand.title || '') + ' ' + (cand.subtitle || '') + ' ' + (cand.filename || '');

  if (prevCtx.bingeGroup && cand.bingeGroup && prevCtx.bingeGroup.toLowerCase() === cand.bingeGroup.toLowerCase()) {
    score += 120;
    reasons.push('bingeGroup');
  }
  if (prevCtx.addonName && cand.addonName && prevCtx.addonName.toLowerCase() === cand.addonName.toLowerCase()) {
    score += 35;
    reasons.push('addon');
  }
  var pg = extractReleaseGroup(prevBlob);
  var cg = extractReleaseGroup(candBlob);
  if (pg && cg && pg === cg) {
    score += 65;
    reasons.push('group:' + cg);
  }
  var candHttp = Boolean(cand.directUrl);
  if (Boolean(prevCtx.wasDirectHttp) === candHttp) {
    score += 40;
    reasons.push(candHttp ? 'transport:mirror' : 'transport:p2p');
  }
  if (prevCtx.resolutionScore > 0 && cand.resolutionScore > 0) {
    if (prevCtx.resolutionScore === cand.resolutionScore) {
      score += 30;
      reasons.push(cand.resolutionScore + 'p');
    } else {
      score -= (Math.abs(prevCtx.resolutionScore - cand.resolutionScore) / 360) * 6;
    }
  }
  return { score: score, reasons: reasons };
}

function enrichStreamCandidate(cand) {
  var blob = [
    cand.title || '',
    cand.subtitle || '',
    cand.filename || '',
    cand.bingeGroup || '',
    cand.fullReleaseTitle || ''
  ].join('\n');

  var resScore = cand.resolutionScore || parseResolutionScore(blob);
  var seeders = typeof cand.seeders === 'number' ? cand.seeders : parseSeedersFromText(blob);
  var sizeBytes = cand.videoSizeBytes || parseHumanSizeBytes(blob);
  var sizeLabel = formatHumanBytes(sizeBytes);
  var codecTags = extractCodecTags(blob);
  var audioTags = extractAudioTags(blob);
  var indexer = cand.indexer || extractIndexerName(blob, cand.addonName);

  var isDirect = Boolean(cand.directUrl);
  var isP2P = !isDirect && Boolean(cand.infoHash);

  var hasSafeAudio = (
    audioTags.indexOf('EAC3') !== -1 ||
    audioTags.indexOf('AC3') !== -1 ||
    audioTags.indexOf('AAC') !== -1 ||
    audioTags.indexOf('Opus') !== -1 ||
    audioTags.indexOf('MP3') !== -1
  );
  var hasIncompatibleAudio = audioTags.indexOf('DTS') !== -1 || audioTags.indexOf('TrueHD') !== -1;
  var audioCompatible = !(hasIncompatibleAudio && !hasSafeAudio);

  var fullReleaseTitle = String(cand.fullReleaseTitle || cand.filename || cand.title || '').replace(/\s+/g, ' ').trim();
  if (!fullReleaseTitle) fullReleaseTitle = cand.addonName || 'Stream';

  var telemetryParts = [];
  if (isDirect) {
    telemetryParts.push('⚡ Direct');
  } else if (isP2P) {
    telemetryParts.push('👤 ' + seeders + (seeders === 1 ? ' seed' : ' seeds'));
  }
  var resLbl = resolutionLabel(resScore);
  if (resLbl) telemetryParts.push(resLbl);
  for (var i = 0; i < codecTags.length; i++) telemetryParts.push(codecTags[i]);
  for (var j = 0; j < audioTags.length; j++) telemetryParts.push(audioTags[j]);
  if (sizeLabel) telemetryParts.push('💾 ' + sizeLabel);
  if (indexer) telemetryParts.push('⚙️ ' + indexer);

  var telemetryHeader = telemetryParts.join(' · ');

  return Object.assign({}, cand, {
    resolutionScore: resScore,
    resolutionLabel: resLbl,
    seeders: seeders,
    videoSizeBytes: sizeBytes,
    sizeLabel: sizeLabel,
    codecTags: codecTags,
    audioTags: audioTags,
    indexer: indexer,
    hasSafeAudio: hasSafeAudio,
    hasIncompatibleAudioOnly: hasIncompatibleAudio && !hasSafeAudio,
    audioCompatible: audioCompatible,
    audioWarning: !audioCompatible ? 'DTS/TrueHD only — may lack audio on 2020–2022 LG OLEDs' : '',
    telemetryHeader: telemetryHeader,
    fullReleaseTitle: fullReleaseTitle,
    subtitle: cand.subtitle || telemetryHeader
  });
}

function dedupeAndRankStreams(rawStreams, prevStreamContext) {
  var unique = [];
  var urlSeen = {};
  var hashIndex = {};

  for (var i = 0; i < rawStreams.length; i++) {
    var s = enrichStreamCandidate(rawStreams[i]);
    if (s.directUrl) {
      var uKey = 'url:' + s.directUrl;
      if (!urlSeen[uKey]) {
        urlSeen[uKey] = true;
        unique.push(s);
      }
    } else if (s.infoHash) {
      var h = String(s.infoHash).trim().toLowerCase();
      if (Object.prototype.hasOwnProperty.call(hashIndex, h)) {
        var idx = hashIndex[h];
        var existing = unique[idx];
        if (existing.fileIdx === s.fileIdx || existing.fileIdx === null || existing.fileIdx === undefined || s.fileIdx === null || s.fileIdx === undefined) {
          if ((existing.fileIdx === null || existing.fileIdx === undefined) && typeof s.fileIdx === 'number') {
            unique[idx] = s;
          } else if (s.seeders > existing.seeders) {
            unique[idx].seeders = s.seeders;
            unique[idx] = enrichStreamCandidate(unique[idx]);
          }
          continue;
        }
        var subKey = h + ':' + s.fileIdx;
        if (!Object.prototype.hasOwnProperty.call(hashIndex, subKey)) {
          hashIndex[subKey] = unique.length;
          unique.push(s);
        }
      } else {
        hashIndex[h] = unique.length;
        unique.push(s);
      }
    } else if (s.externalUrl) {
      var extKey = 'ext:' + s.externalUrl;
      if (!urlSeen[extKey]) {
        urlSeen[extKey] = true;
        unique.push(s);
      }
    }
  }

  var hasSafeAudioAlternative = unique.some(function (c) { return c.hasSafeAudio; });

  for (var j = 0; j < unique.length; j++) {
    var c = unique[j];
    var isDirect = Boolean(c.directUrl);
    var viabilityTier = isDirect ? 3 : (c.seeders >= 3 ? 3 : (c.seeders >= 1 ? 2 : (c.externalUrl ? 1 : 0)));

    var resBase = 10;
    if (c.resolutionScore >= 2160) resBase = 120;
    else if (c.resolutionScore >= 1440) resBase = 100;
    else if (c.resolutionScore >= 1080) resBase = 90;
    else if (c.resolutionScore >= 720) resBase = 60;
    else if (c.resolutionScore >= 480) resBase = 30;

    var codecBonus = 0;
    if (c.codecTags.indexOf('AV1') !== -1) codecBonus = 25;
    else if (c.codecTags.indexOf('HEVC') !== -1) codecBonus = 20;
    else if (c.codecTags.indexOf('H.264') !== -1) codecBonus = 5;

    var lgAudioBonus = 0;
    if (c.hasSafeAudio) {
      lgAudioBonus = 30;
    } else if (c.hasIncompatibleAudioOnly) {
      lgAudioBonus = hasSafeAudioAlternative ? -35 : -15;
    }

    var seedBonus = c.seeders > 0 ? Math.min(25, Math.log(1 + c.seeders) * 4) : 0;
    var bingeRes = scoreBingeSimilarity(prevStreamContext, c);

    c.viabilityTier = viabilityTier;
    c.lgAudioBonus = lgAudioBonus;
    c.rankScore = Number((resBase + codecBonus + lgAudioBonus + seedBonus + bingeRes.score).toFixed(2));
    c.matchedReasons = bingeRes.reasons;
  }

  unique.sort(function (a, b) {
    if (b.viabilityTier !== a.viabilityTier) return b.viabilityTier - a.viabilityTier;
    if (Boolean(b.directUrl) !== Boolean(a.directUrl)) return b.directUrl ? 1 : -1;
    if (b.rankScore !== a.rankScore) return b.rankScore - a.rankScore;
    return (b.seeders || 0) - (a.seeders || 0);
  });

  return unique;
}

// ─────────────────────────────────────────────────────────────────────────────
// 2. Stremio v3 Protocol Client
// ─────────────────────────────────────────────────────────────────────────────

var STREMIO_API_BASE = 'https://api.strem.io/api';
var STREMIO_LINK_BASE = 'https://link.strem.io/api/v2';

function splitPipeHeaders(rawUrl) {
  var idx = String(rawUrl || '').indexOf('|');
  if (idx === -1) return { url: String(rawUrl || ''), headers: {} };
  var base = rawUrl.slice(0, idx).trim();
  var hdrPart = rawUrl.slice(idx + 1);
  var headers = {};
  var pairs = hdrPart.split('&');
  for (var i = 0; i < pairs.length; i++) {
    var eq = pairs[i].indexOf('=');
    if (eq === -1) continue;
    try {
      var k = decodeURIComponent(pairs[i].slice(0, eq)).trim();
      var v = decodeURIComponent(pairs[i].slice(eq + 1)).trim();
      if (k) headers[k] = v;
    } catch (e) {}
  }
  return { url: base, headers: headers };
}

function addonSupportsResource(addon, resourceName, mediaType, itemId) {
  var specs = Array.isArray(addon.resourceSpecs) ? addon.resourceSpecs : [];
  var matching = specs.filter(function (s) { return s && s.name === resourceName; });

  function matchesPrefix(prefixes) {
    if (!prefixes || prefixes.length === 0 || !itemId) return true;
    for (var i = 0; i < prefixes.length; i++) {
      var p = String(prefixes[i] || '');
      var norm = p.replace(/:+$/, '');
      if (norm && (itemId.indexOf(p) === 0 || itemId.indexOf(norm) === 0)) return true;
    }
    return false;
  }

  if (matching.length > 0) {
    return matching.some(function (spec) {
      var effTypes = (spec.types && spec.types.length) ? spec.types : (addon.types || []);
      var typeOk = effTypes.length === 0 || effTypes.indexOf(mediaType) !== -1;
      var effPrefixes = (spec.idPrefixes && spec.idPrefixes.length) ? spec.idPrefixes : (addon.idPrefixes || []);
      return typeOk && matchesPrefix(effPrefixes);
    });
  }

  if (!Array.isArray(addon.resources) || addon.resources.indexOf(resourceName) === -1) {
    return false;
  }
  var tOk = !addon.types || addon.types.length === 0 || addon.types.indexOf(mediaType) !== -1;
  return tOk && matchesPrefix(addon.idPrefixes || []);
}

function resolveResourceType(addon, resourceName, requestedType, itemId) {
  if (addonSupportsResource(addon, resourceName, requestedType, itemId)) {
    return requestedType;
  }
  if (itemId && (itemId.indexOf('tt') === 0 || itemId.indexOf('kitsu:') === 0)) {
    var colonCount = (itemId.match(/:/g) || []).length;
    var inferred = colonCount >= 2 ? 'series' : 'movie';
    if (addonSupportsResource(addon, resourceName, inferred, itemId)) return inferred;
    var alts = ['movie', 'series', 'anime'];
    for (var i = 0; i < alts.length; i++) {
      if (addonSupportsResource(addon, resourceName, alts[i], itemId)) return alts[i];
    }
  }
  return null;
}

function normalizeManifestUrl(rawUrl) {
  var u = String(rawUrl || '').trim();
  if (!u) throw new Error('Addon URL is empty');
  if (u.indexOf('stremio://') === 0) u = 'https://' + u.slice('stremio://'.length);
  else if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
  if (u.indexOf('/manifest.json') === -1) {
    u = u.replace(/\/+$/, '') + '/manifest.json';
  }
  return u;
}

function parseManifestValue(transportUrl, val) {
  if (!val || !val.id) throw new Error('Addon manifest missing id');
  var types = Array.isArray(val.types) ? val.types.slice() : [];
  var idPrefixes = Array.isArray(val.idPrefixes) ? val.idPrefixes.slice() : [];
  var resources = [];
  var resourceSpecs = [];
  if (Array.isArray(val.resources)) {
    for (var i = 0; i < val.resources.length; i++) {
      var r = val.resources[i];
      if (typeof r === 'string') {
        resources.push(r);
        resourceSpecs.push({ name: r, types: types, idPrefixes: idPrefixes });
      } else if (r && typeof r === 'object' && r.name) {
        resources.push(r.name);
        resourceSpecs.push({
          name: r.name,
          types: Array.isArray(r.types) ? r.types : types,
          idPrefixes: Array.isArray(r.idPrefixes) ? r.idPrefixes : idPrefixes
        });
      }
    }
  }
  var catalogs = [];
  if (Array.isArray(val.catalogs)) {
    for (var j = 0; j < val.catalogs.length; j++) {
      var c = val.catalogs[j];
      if (!c || !c.type || !c.id) continue;
      var genres = Array.isArray(c.genres) ? c.genres.slice() : [];
      var supportsSearch = false;
      var supportsGenre = genres.length > 0;
      var supportsSkip = false;
      if (Array.isArray(c.extra)) {
        for (var ex = 0; ex < c.extra.length; ex++) {
          var extra = c.extra[ex];
          if (!extra) continue;
          if (extra.name === 'search') supportsSearch = true;
          if (extra.name === 'skip') supportsSkip = true;
          if (extra.name === 'genre') {
            supportsGenre = true;
            if (genres.length === 0 && Array.isArray(extra.options)) genres = extra.options.slice();
          }
        }
      }
      if (Array.isArray(c.extraSupported)) {
        if (c.extraSupported.indexOf('search') !== -1) supportsSearch = true;
        if (c.extraSupported.indexOf('skip') !== -1) supportsSkip = true;
        if (c.extraSupported.indexOf('genre') !== -1) supportsGenre = true;
      }
      catalogs.push({
        type: c.type,
        id: c.id,
        name: c.name || c.id,
        genres: genres,
        supportsSearch: supportsSearch,
        supportsGenre: supportsGenre,
        supportsSkip: supportsSkip
      });
    }
  }
  var baseUrl = transportUrl.split('/manifest.json')[0].replace(/\/+$/, '');
  return {
    id: String(val.id),
    name: String(val.name || val.id),
    version: String(val.version || '1.0.0'),
    description: String(val.description || ''),
    transportUrl: transportUrl,
    baseUrl: baseUrl,
    types: types,
    resources: resources,
    resourceSpecs: resourceSpecs,
    idPrefixes: idPrefixes,
    catalogs: catalogs
  };
}

function parseMetaItem(m, defaultType, addonSource) {
  if (!m || !m.id) return null;
  var id = String(m.id).trim();
  if (!id) return null;
  var videos = [];
  if (Array.isArray(m.videos)) {
    for (var i = 0; i < m.videos.length; i++) {
      var v = m.videos[i];
      if (!v || !v.id) continue;
      var season = typeof v.season === 'number' ? v.season : 0;
      var episode = typeof v.episode === 'number' ? v.episode : (typeof v.number === 'number' ? v.number : 0);
      var epTitle = v.title || v.name || ('Episode ' + episode);
      videos.push({
        id: String(v.id),
        title: String(epTitle),
        season: season,
        episode: episode,
        overview: String(v.overview || v.description || ''),
        released: String(v.released || ''),
        thumbnail: String(v.thumbnail || '')
      });
    }
    videos.sort(function (a, b) {
      return a.season !== b.season ? a.season - b.season : a.episode - b.episode;
    });
  }
  return {
    id: id,
    type: String(m.type || defaultType || 'movie'),
    name: String(m.name || id),
    poster: String(m.poster || ''),
    background: String(m.background || ''),
    description: String(m.description || ''),
    releaseInfo: m.releaseInfo !== undefined ? String(m.releaseInfo) : (m.year !== undefined ? String(m.year) : ''),
    imdbRating: m.imdbRating !== undefined ? String(m.imdbRating) : '',
    runtime: String(m.runtime || ''),
    genres: Array.isArray(m.genres) ? m.genres.map(String) : [],
    videos: videos,
    addonSource: String(addonSource || '')
  };
}

function parseStremioStreamCandidate(s, addonName) {
  if (!s || typeof s !== 'object') return null;
  var rawUrl = typeof s.url === 'string' ? s.url.trim() : '';
  var infoHash = typeof s.infoHash === 'string' ? s.infoHash.trim().toLowerCase() : '';
  var externalUrl = typeof s.externalUrl === 'string' ? s.externalUrl.trim() : '';

  var directUrl = null;
  var requestHeaders = {};
  var sources = Array.isArray(s.sources) ? s.sources.map(String) : [];

  if (rawUrl) {
    if (rawUrl.indexOf('magnet:') === 0) {
      var h = p2pMod.extractInfoHash(rawUrl);
      if (h) infoHash = h;
      var trRe = /[?&]tr=([^&]+)/gi;
      var tm;
      while ((tm = trRe.exec(rawUrl)) !== null) {
        try { sources.push('tracker:' + decodeURIComponent(tm[1])); } catch (e) {}
      }
    } else {
      var split = splitPipeHeaders(rawUrl);
      directUrl = split.url;
      requestHeaders = split.headers;
    }
  }

  if (!directUrl && !infoHash && !externalUrl) return null;

  var bh = s.behaviorHints || {};
  if (bh.proxyHeaders && bh.proxyHeaders.request && typeof bh.proxyHeaders.request === 'object') {
    var rKeys = Object.keys(bh.proxyHeaders.request);
    for (var i = 0; i < rKeys.length; i++) {
      requestHeaders[rKeys[i]] = String(bh.proxyHeaders.request[rKeys[i]]);
    }
  }

  var bingeGroup = String(bh.bingeGroup || '');
  var filename = String(bh.filename || '');
  var videoSizeBytes = typeof bh.videoSize === 'number' ? bh.videoSize : 0;

  var nameStr = String(s.name || addonName || '');
  var rawTitle = String(s.title || '').trim();
  var rawDesc = String(s.description || '').trim();
  var titleBlock = rawTitle && rawDesc && rawTitle !== rawDesc
    ? rawTitle + '\n' + rawDesc
    : (rawTitle || rawDesc);
  var combinedText = nameStr + '\n' + titleBlock + '\n' + filename;
  var lines = titleBlock ? titleBlock.split(/\r?\n/).map(function (l) { return l.trim(); }).filter(Boolean) : [];
  var displayTitle = lines[0] || filename || nameStr.replace(/\n/g, ' ');
  var subtitleParts = lines.slice(1);
  if (filename) {
    var fLow = filename.toLowerCase();
    var already = displayTitle.toLowerCase().indexOf(fLow) !== -1 ||
      subtitleParts.some(function (p) { return p.toLowerCase().indexOf(fLow) !== -1; });
    if (!already) subtitleParts.push('File: ' + filename);
  }

  return {
    title: displayTitle,
    subtitle: subtitleParts.join(' • '),
    fullReleaseTitle: filename || (titleBlock ? titleBlock.replace(/\n/g, ' ') : displayTitle),
    addonName: String(addonName || ''),
    directUrl: directUrl,
    infoHash: infoHash || null,
    fileIdx: typeof s.fileIdx === 'number' ? s.fileIdx : null,
    sources: sources,
    requestHeaders: requestHeaders,
    externalUrl: externalUrl || null,
    bingeGroup: bingeGroup,
    filename: filename,
    videoSizeBytes: videoSizeBytes || parseHumanSizeBytes(combinedText),
    seeders: parseSeedersFromText(combinedText),
    resolutionScore: parseResolutionScore(combinedText)
  };
}

function createStremioClient(store) {
  function startQrAuth() {
    return httpClient.getJson(STREMIO_LINK_BASE + '/create?type=Create').then(function (root) {
      var res = root && root.result;
      if (!res || !res.code) throw new Error('Stremio link service returned no code');
      return {
        code: String(res.code),
        link: String(res.link || ('https://link.stremio.com/?code=' + res.code)),
        qrcodeUrl: String(res.qrcode || '')
      };
    });
  }

  function pollQrAuth(code) {
    return httpClient.getJson(STREMIO_LINK_BASE + '/read?type=Read&code=' + encodeURIComponent(code)).then(function (root) {
      var authKey = root && root.result && root.result.authKey;
      if (!authKey) return { approved: false };
      return syncAccountEverything(authKey).then(function (summary) {
        return { approved: true, summary: summary };
      });
    }).catch(function () {
      return { approved: false };
    });
  }

  function syncAccountEverything(explicitAuthKey) {
    var authKey = explicitAuthKey || store.getStremioAuth().authKey;
    if (!authKey) return Promise.reject(new Error('Not signed in to Stremio'));

    return httpClient.postJson(STREMIO_API_BASE + '/getUser', {
      type: 'GetUser',
      authKey: authKey
    }).then(function (r) {
      var userResp = r.data || {};
      if (userResp.error && userResp.error.message) {
        throw new Error('Stremio getUser failed: ' + userResp.error.message);
      }
      var email = (userResp.result && userResp.result.email) || 'Stremio User';
      store.setStremioAuth(authKey, email, Date.now());

      var addonsPromise = httpClient.postJson(STREMIO_API_BASE + '/addonCollectionGet', {
        type: 'AddonCollectionGet',
        authKey: authKey,
        update: true
      }).then(function (colR) {
        var list = (colR.data && colR.data.result && colR.data.result.addons) || [];
        var parsed = [];
        for (var i = 0; i < list.length; i++) {
          var entry = list[i];
          if (!entry || !entry.transportUrl || !entry.manifest) continue;
          try {
            parsed.push(parseManifestValue(entry.transportUrl, entry.manifest));
          } catch (e) {}
        }
        if (parsed.length > 0) {
          store.setAddons(parsed);
        }
        return parsed.length;
      }).catch(function () { return 0; });

      var historyPromise = httpClient.postJson(STREMIO_API_BASE + '/datastoreGet', {
        authKey: authKey,
        collection: 'libraryItem',
        all: true
      }).then(function (dsR) {
        var items = (dsR.data && dsR.data.result) || [];
        var syncedCount = 0;
        var merged = store.getWatchHistory();
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          if (!item || !item._id || (item.removed && item.temp) || !item.state) continue;
          var timeOffset = Number(item.state.timeOffset || 0);
          var duration = Number(item.state.duration || 0);
          if (timeOffset < 15000) continue;
          if (duration > 0 && timeOffset / duration >= 0.94) continue;
          var mtime = item._mtime ? Date.parse(item._mtime) : Date.now();
          if (isNaN(mtime)) mtime = Date.now();
          var existingIdx = -1;
          for (var j = 0; j < merged.length; j++) {
            if (merged[j].itemId === item._id) { existingIdx = j; break; }
          }
          if (existingIdx >= 0 && (merged[existingIdx].updatedAtMs || 0) >= mtime) continue;
          var entry = {
            itemId: String(item._id),
            type: String(item.type || 'movie'),
            title: String(item.name || item._id),
            poster: String(item.poster || ''),
            videoId: String(item.state.video_id || item._id),
            episodeTitle: '',
            positionMs: timeOffset,
            durationMs: duration,
            updatedAtMs: mtime,
            lastStreamContext: existingIdx >= 0 ? merged[existingIdx].lastStreamContext : null
          };
          if (existingIdx >= 0) merged[existingIdx] = entry;
          else merged.push(entry);
          syncedCount++;
        }
        store.replaceWatchHistory(merged);
        return syncedCount;
      }).catch(function () { return 0; });

      return Promise.all([addonsPromise, historyPromise]).then(function (counts) {
        return {
          email: email,
          syncedAddonsCount: counts[0],
          syncedHistoryCount: counts[1],
          addons: store.getAddons()
        };
      });
    });
  }

  function installAddon(rawUrl) {
    var normalized = normalizeManifestUrl(rawUrl);
    return httpClient.getJson(normalized).then(function (val) {
      var addon = parseManifestValue(normalized, val);
      store.upsertAddon(addon);
      return addon;
    });
  }

  function pushWatchProgress(entry) {
    var auth = store.getStremioAuth();
    if (!auth.authKey || !entry || !entry.itemId || entry.itemId.indexOf('tt') !== 0) {
      return Promise.resolve(false);
    }
    var nowIso = new Date(Math.max(entry.updatedAtMs || 0, Date.now())).toISOString();
    var ratio = entry.durationMs > 0 ? entry.positionMs / entry.durationMs : 0;
    return httpClient.postJson(STREMIO_API_BASE + '/datastorePut', {
      authKey: auth.authKey,
      collection: 'libraryItem',
      changes: [{
        _id: entry.itemId,
        name: entry.title,
        type: entry.type || 'movie',
        poster: entry.poster || '',
        removed: false,
        temp: true,
        _ctime: nowIso,
        _mtime: nowIso,
        state: {
          lastWatched: nowIso,
          timeWatched: entry.positionMs,
          timeOffset: entry.positionMs,
          overallTimeWatched: entry.positionMs,
          timesWatched: ratio >= 0.9 ? 1 : 0,
          flaggedWatched: ratio >= 0.9 ? 1 : 0,
          duration: entry.durationMs,
          video_id: entry.videoId || entry.itemId,
          noNotif: true
        }
      }]
    }).then(function (r) { return Boolean(r && r.ok); }).catch(function () { return false; });
  }

  function fetchCatalog(addon, mediaType, catalogId, opts) {
    opts = opts || {};
    var extras = [];
    if (opts.query && String(opts.query).trim()) {
      extras.push('search=' + encodeURIComponent(String(opts.query).trim()));
    }
    if (opts.genre && String(opts.genre).trim() && String(opts.genre).toLowerCase() !== 'all') {
      extras.push('genre=' + encodeURIComponent(String(opts.genre).trim()));
    }
    if (opts.year && String(opts.year).trim() && String(opts.year).toLowerCase() !== 'all') {
      extras.push('year=' + encodeURIComponent(String(opts.year).trim()));
    }
    if (opts.skip && Number(opts.skip) > 0) {
      extras.push('skip=' + Number(opts.skip));
    }
    var extraSeg = extras.length > 0 ? '/' + extras.join('&') : '';
    var url =
      addon.baseUrl.replace(/\/+$/, '') +
      '/catalog/' + encodeURIComponent(mediaType) + '/' + encodeURIComponent(catalogId) + extraSeg + '.json';

    return httpClient.getJson(url, { timeoutMs: 12000 }).then(function (root) {
      var metas = Array.isArray(root && root.metas) ? root.metas : [];
      var out = [];
      for (var i = 0; i < metas.length; i++) {
        var parsed = parseMetaItem(metas[i], mediaType, addon.name);
        if (parsed) out.push(parsed);
      }
      return out;
    }).catch(function () { return []; });
  }

  function searchCatalogs(query, typeFilter) {
    var trimmed = String(query || '').trim();
    if (!trimmed) return Promise.resolve([]);
    var addons = store.getAddons();
    var tasks = [];
    for (var i = 0; i < addons.length; i++) {
      var addon = addons[i];
      var cats = Array.isArray(addon.catalogs) ? addon.catalogs : [];
      for (var j = 0; j < cats.length; j++) {
        var c = cats[j];
        if (!c.supportsSearch) continue;
        if (typeFilter && typeFilter !== 'all' && c.type !== typeFilter) continue;
        tasks.push(fetchCatalog(addon, c.type, c.id, { query: trimmed }));
      }
    }
    return Promise.all(tasks).then(function (batches) {
      var seen = {};
      var out = [];
      for (var i = 0; i < batches.length; i++) {
        for (var j = 0; j < batches[i].length; j++) {
          var item = batches[i][j];
          if (!seen[item.id]) {
            seen[item.id] = true;
            out.push(item);
          }
        }
      }
      return out;
    });
  }

  function fetchMetaDetail(mediaType, itemId) {
    var addons = store.getAddons();
    function tryIdx(i) {
      if (i >= addons.length) return Promise.resolve(null);
      var addon = addons[i];
      var effType = resolveResourceType(addon, 'meta', mediaType || 'movie', itemId);
      if (!effType) return tryIdx(i + 1);
      var url =
        addon.baseUrl.replace(/\/+$/, '') +
        '/meta/' + encodeURIComponent(effType) + '/' + encodeURIComponent(itemId) + '.json';
      return httpClient.getJson(url, { timeoutMs: 12000 }).then(function (root) {
        if (root && root.meta) {
          var parsed = parseMetaItem(root.meta, effType, addon.name);
          if (parsed) return parsed;
        }
        return tryIdx(i + 1);
      }).catch(function () {
        return tryIdx(i + 1);
      });
    }
    return tryIdx(0);
  }

  function fetchSubtitles(mediaType, videoId, mediaBaseUrl) {
    var addons = store.getAddons();
    var tasks = [];
    for (var i = 0; i < addons.length; i++) {
      (function (addon) {
        var effType = resolveResourceType(addon, 'subtitles', mediaType || 'movie', videoId);
        if (!effType) return;
        var url =
          addon.baseUrl.replace(/\/+$/, '') +
          '/subtitles/' + encodeURIComponent(effType) + '/' + encodeURIComponent(videoId) + '.json';
        tasks.push(httpClient.getJson(url, { timeoutMs: 10000 }).then(function (root) {
          var arr = Array.isArray(root && root.subtitles) ? root.subtitles : [];
          return arr.map(function (s) {
            var subUrl = String((s && s.url) || '').trim();
            if (!subUrl) return null;
            return {
              id: String(s.id || subUrl),
              url: subUrl,
              vttUrl: mediaBaseUrl ? (mediaBaseUrl + '/sub.vtt?u=' + httpClient.b64UrlEncode(subUrl)) : subUrl,
              lang: String(s.lang || 'und'),
              addonName: addon.name
            };
          }).filter(Boolean);
        }).catch(function () { return []; }));
      })(addons[i]);
    }
    return Promise.all(tasks).then(function (batches) {
      var seen = {};
      var out = [];
      for (var i = 0; i < batches.length; i++) {
        for (var j = 0; j < batches[i].length; j++) {
          var sub = batches[i][j];
          if (!seen[sub.url]) {
            seen[sub.url] = true;
            out.push(sub);
          }
        }
      }
      return out;
    });
  }

  function fetchStreamsFromAddons(mediaType, videoId) {
    var addons = store.getAddons();
    var tasks = [];
    for (var i = 0; i < addons.length; i++) {
      (function (addon) {
        var effType = resolveResourceType(addon, 'stream', mediaType || 'movie', videoId);
        if (!effType) return;
        var url =
          addon.baseUrl.replace(/\/+$/, '') +
          '/stream/' + encodeURIComponent(effType) + '/' + encodeURIComponent(videoId) + '.json';
        tasks.push(
          httpClient.getJson(url, { desktopUa: true, timeoutMs: 12000 }).catch(function () {
            return httpClient.getJson(url, {
              desktopUa: true,
              timeoutMs: 12000,
              headers: { Accept: 'application/json, text/plain, */*' }
            }).catch(function () { return null; });
          }).then(function (root) {
            var arr = Array.isArray(root && root.streams) ? root.streams : [];
            var out = [];
            for (var k = 0; k < arr.length; k++) {
              var cand = parseStremioStreamCandidate(arr[k], addon.name);
              if (cand) out.push(cand);
            }
            return out;
          })
        );
      })(addons[i]);
    }
    return Promise.all(tasks).then(function (batches) {
      var flat = [];
      for (var i = 0; i < batches.length; i++) {
        flat = flat.concat(batches[i]);
      }
      return flat;
    });
  }

  return {
    startQrAuth: startQrAuth,
    pollQrAuth: pollQrAuth,
    syncAccountEverything: syncAccountEverything,
    installAddon: installAddon,
    pushWatchProgress: pushWatchProgress,
    fetchCatalog: fetchCatalog,
    searchCatalogs: searchCatalogs,
    fetchMetaDetail: fetchMetaDetail,
    fetchSubtitles: fetchSubtitles,
    fetchStreamsFromAddons: fetchStreamsFromAddons
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// 3. Schema-Driven Swarm Client (Zero Hardcoded Domains — Reads Active Pack)
// ─────────────────────────────────────────────────────────────────────────────

function parseStremioImdbVideoId(videoId) {
  var parts = String(videoId || '').trim().split(':');
  return {
    root: parts[0] || '',
    season: parts[1] && /^\d+$/.test(parts[1]) ? parseInt(parts[1], 10) : null,
    episode: parts[2] && /^\d+$/.test(parts[2]) ? parseInt(parts[2], 10) : null
  };
}

function matchesEpisodeReleaseName(name, season, episode, numFiles) {
  if (!season) return true;
  var low = String(name || '').toLowerCase();
  var seMatch = low.match(/\bs(\d{1,2})e(\d{1,3})\b/i);
  if (seMatch) {
    var s = parseInt(seMatch[1], 10);
    var e = parseInt(seMatch[2], 10);
    return s === season && (episode === null || episode === undefined || e === episode);
  }
  var sxMatch = low.match(/\b(\d{1,2})x(\d{1,3})\b/i);
  if (sxMatch) {
    var sx = parseInt(sxMatch[1], 10);
    var ex = parseInt(sxMatch[2], 10);
    return sx === season && (episode === null || episode === undefined || ex === episode);
  }
  var rangeMatch = low.match(/\b(?:seasons?|s)\s*(\d{1,2})\s*[-–to]+\s*(?:s)?(\d{1,2})\b/i);
  if (rangeMatch) {
    var s1 = parseInt(rangeMatch[1], 10);
    var s2 = parseInt(rangeMatch[2], 10);
    return season >= s1 && season <= s2;
  }
  if (low.indexOf('complete') !== -1 || low.indexOf('all seasons') !== -1 || low.indexOf('full series') !== -1) {
    return true;
  }
  var sRe = /\b(?:season\s*(\d{1,2})|s(\d{1,2}))\b/gi;
  var mentioned = [];
  var sm;
  while ((sm = sRe.exec(low)) !== null) {
    var sn = parseInt(sm[1] || sm[2], 10);
    if (!isNaN(sn)) mentioned.push(sn);
  }
  if (mentioned.length > 0) return mentioned.indexOf(season) !== -1;
  return (numFiles || 1) > 1;
}

function extractXmlTagText(block, localTagName) {
  var re = new RegExp('<(?:[a-z0-9_-]+:)?' + localTagName + '(?:\\s[^>]*)?>([\\s\\S]*?)<\\/(?:[a-z0-9_-]+:)?' + localTagName + '>', 'i');
  var m = block.match(re);
  return m ? httpClient.decodeXmlEntities(m[1]).trim() : '';
}

function parseGenericTorrentRssXml(xml, sourceLabel, defaultTrackers) {
  var items = [];
  var itemRe = /<item\b[^>]*>([\s\S]*?)<\/item>/gi;
  var m;
  var trackerSources = (defaultTrackers || []).map(function (t) { return 'tracker:' + t; });

  while ((m = itemRe.exec(String(xml || ''))) !== null) {
    var blk = m[1];
    var title = extractXmlTagText(blk, 'title');
    var link = extractXmlTagText(blk, 'link');
    var infoHash = extractXmlTagText(blk, 'infoHash').toLowerCase();
    var seedStr = extractXmlTagText(blk, 'seeders');
    var sizeStr = extractXmlTagText(blk, 'size');
    var category = extractXmlTagText(blk, 'category');

    var attrRe = /<[a-z0-9_-]+:attr\b[^>]*\bname=['"]([^'"]+)['"][^>]*\bvalue=['"]([^'"]*)['"]/gi;
    var am;
    var sizeBytes = 0;
    while ((am = attrRe.exec(blk)) !== null) {
      var aname = am[1].toLowerCase();
      var aval = am[2];
      if (aname === 'infohash' && !infoHash) infoHash = aval.trim().toLowerCase();
      else if (aname === 'seeders' && !seedStr) seedStr = aval.trim();
      else if (aname === 'size') sizeBytes = parseInt(aval, 10) || sizeBytes;
    }

    if (!infoHash && link.indexOf('magnet:') === 0) {
      infoHash = p2pMod.extractInfoHash(link) || '';
    }
    if (!infoHash && !/^[a-f0-9]{40}$/i.test(infoHash)) continue;
    if (!/^[a-f0-9]{40}$/i.test(infoHash)) continue;

    var seeders = parseInt(seedStr, 10) || 0;
    if (!sizeBytes && sizeStr) {
      sizeBytes = /^\d+$/.test(sizeStr) ? parseInt(sizeStr, 10) : parseHumanSizeBytes(sizeStr);
    }
    var sizeLabel = sizeStr && !/^\d+$/.test(sizeStr) ? sizeStr : formatHumanBytes(sizeBytes);
    var resScore = parseResolutionScore(title);

    items.push({
      title: title,
      subtitle: [sourceLabel, sizeLabel ? ('💾 ' + sizeLabel) : '', '👤 ' + seeders, category].filter(Boolean).join(' • '),
      fullReleaseTitle: title,
      addonName: sourceLabel,
      directUrl: null,
      infoHash: infoHash,
      fileIdx: null,
      sources: trackerSources.slice(),
      requestHeaders: {},
      externalUrl: null,
      bingeGroup: 'swarm-' + sourceLabel + '-' + resScore,
      filename: title,
      videoSizeBytes: sizeBytes,
      seeders: seeders,
      resolutionScore: resScore
    });
  }
  return items;
}

function parseJsonArrayBtih(arr, label, filterMode, defaultTrackers, season, episode) {
  if (!Array.isArray(arr)) return [];
  var out = [];
  var trackerSources = (defaultTrackers || []).map(function (t) { return 'tracker:' + t; });

  for (var i = 0; i < arr.length; i++) {
    var item = arr[i];
    if (!item) continue;
    var name = String(item.name || '').trim();
    var hash = String(item.info_hash || '').trim().toLowerCase();
    if (!name || name === 'No results returned' || hash.length !== 40 || /^0+$/.test(hash)) continue;

    var catNum = parseInt(item.category || 200, 10) || 200;
    if (filterMode === 'SAFE_ONLY' && catNum >= 500 && catNum < 600) continue;
    if (filterMode === 'ADULT_ONLY' && !(catNum >= 500 && catNum < 600)) continue;

    var numFiles = parseInt(item.num_files || 1, 10) || 1;
    if (season !== null && season !== undefined) {
      if (!matchesEpisodeReleaseName(name, season, episode, numFiles)) continue;
    }

    var seeders = parseInt(item.seeders || 0, 10) || 0;
    var leechers = parseInt(item.leechers || 0, 10) || 0;
    var sizeBytes = parseInt(item.size || 0, 10) || 0;
    var resScore = parseResolutionScore(name);
    var sizeLabel = formatHumanBytes(sizeBytes);
    var packNote = numFiles > 1 ? ' • 📁 ' + numFiles + ' files (auto-selects episode)' : '';

    out.push({
      title: name,
      subtitle: '👤 ' + seeders + ' (↓' + leechers + ') • 💾 ' + sizeLabel + packNote + ' • ⚙️ ' + label,
      fullReleaseTitle: name,
      addonName: label,
      directUrl: null,
      infoHash: hash,
      fileIdx: null,
      sources: trackerSources.slice(),
      requestHeaders: {},
      externalUrl: null,
      bingeGroup: 'btih-' + resScore,
      filename: name,
      videoSizeBytes: sizeBytes,
      seeders: seeders,
      resolutionScore: resScore
    });
  }
  return out;
}

function parseJsonMoviesTorrents(root, label, defaultTrackers) {
  var movies = root && root.data && Array.isArray(root.data.movies) ? root.data.movies : [];
  var out = [];
  var trackerSources = (defaultTrackers || []).map(function (t) { return 'tracker:' + t; });

  for (var i = 0; i < movies.length; i++) {
    var mv = movies[i];
    if (!mv || !Array.isArray(mv.torrents)) continue;
    var mvTitle = mv.title_long || mv.title || 'Movie';
    for (var j = 0; j < mv.torrents.length; j++) {
      var t = mv.torrents[j];
      var hash = String((t && t.hash) || '').trim().toLowerCase();
      if (hash.length !== 40) continue;
      var quality = t.quality || '1080p';
      var codec = t.video_codec || 'x264';
      var sizeStr = t.size || '';
      var sizeBytes = Number(t.size_bytes || 0);
      var seeds = Number(t.seeds || 0);
      var fullTitle = mvTitle + ' [' + quality + '] [' + codec + '] -' + label;
      out.push({
        title: fullTitle,
        subtitle: label + ' • 💾 ' + sizeStr + ' • 👤 ' + seeds,
        fullReleaseTitle: fullTitle,
        addonName: label,
        directUrl: null,
        infoHash: hash,
        fileIdx: null,
        sources: trackerSources.slice(),
        requestHeaders: {},
        externalUrl: null,
        bingeGroup: 'mv-' + quality + '-' + codec,
        filename: fullTitle,
        videoSizeBytes: sizeBytes,
        seeders: seeds,
        resolutionScore: parseResolutionScore(quality)
      });
    }
  }
  return out;
}

function createSwarmClient(store) {
  function runIndexer(idx, opts, trackers) {
    opts = opts || {};
    var query = String(opts.query || '').trim();
    var imdbId = String(opts.imdbId || '').trim();
    var filterMode = opts.filter || store.getStateRaw().swarmFilter || 'SAFE_ONLY';
    var cat = opts.category || idx.defaultCategory || '0_0';

    if (idx.kind === 'rss') {
      if (!idx.searchUrlTemplate || (!query && !opts.overrideUrl)) return Promise.resolve([]);
      var rssUrl = opts.overrideUrl || idx.searchUrlTemplate
        .replace(/\{cat\}/g, encodeURIComponent(cat))
        .replace(/\{query\}/g, encodeURIComponent(query));
      return httpClient.getText(rssUrl, { desktopUa: true, timeoutMs: 12000 }).then(function (r) {
        return parseGenericTorrentRssXml(r.text, idx.name, trackers);
      }).catch(function () { return []; });
    }

    if (idx.kind === 'json_array_btih') {
      var parsedImdb = imdbId ? parseStremioImdbVideoId(imdbId) : null;
      if (parsedImdb && parsedImdb.root && parsedImdb.root.indexOf('tt') === 0 && idx.imdbUrlTemplate) {
        var iUrl = idx.imdbUrlTemplate.replace(/\{imdb\}/g, encodeURIComponent(parsedImdb.root));
        return httpClient.getJson(iUrl, { desktopUa: true, timeoutMs: 12000 }).then(function (arr) {
          return parseJsonArrayBtih(arr, idx.name, filterMode, trackers, parsedImdb.season, parsedImdb.episode);
        }).catch(function () { return []; });
      }
      if (!idx.searchUrlTemplate || (!query && !opts.overrideUrl)) return Promise.resolve([]);
      var catParam = filterMode === 'SAFE_ONLY' ? (idx.defaultCategory || '200') : (filterMode === 'ADULT_ONLY' ? '500' : '200,500');
      var qUrl = opts.overrideUrl || idx.searchUrlTemplate
        .replace(/\{cat\}/g, encodeURIComponent(catParam))
        .replace(/\{query\}/g, encodeURIComponent(query));
      return httpClient.getJson(qUrl, { desktopUa: true, timeoutMs: 12000 }).then(function (arr) {
        var out = parseJsonArrayBtih(arr, idx.name, filterMode, trackers, null, null);
        if (out.length === 0 && /\bS\d{1,2}E\d{1,3}\b/i.test(query)) {
          var stripped = query.replace(/\bS\d{1,2}E\d{1,3}\b/ig, '').trim();
          if (stripped && stripped !== query) {
            var retryUrl = idx.searchUrlTemplate
              .replace(/\{cat\}/g, encodeURIComponent(catParam))
              .replace(/\{query\}/g, encodeURIComponent(stripped));
            return httpClient.getJson(retryUrl, { desktopUa: true, timeoutMs: 12000 }).then(function (arr2) {
              return parseJsonArrayBtih(arr2, idx.name, filterMode, trackers, null, null);
            }).catch(function () { return []; });
          }
        }
        return out;
      }).catch(function () { return []; });
    }

    if (idx.kind === 'json_movies_torrents') {
      var bases = Array.isArray(idx.baseUrls) ? idx.baseUrls : [];
      var pathTpl = opts.overridePath || idx.pathTemplate || '';
      var term = (imdbId && imdbId.indexOf('tt') === 0) ? parseStremioImdbVideoId(imdbId).root : query;
      if (!bases.length || !pathTpl) return Promise.resolve([]);
      var relPath = pathTpl.replace(/\{query\}/g, encodeURIComponent(term || ''));

      function tryBase(bIdx) {
        if (bIdx >= bases.length) return Promise.resolve([]);
        var fullUrl = bases[bIdx].replace(/\/+$/, '') + relPath;
        return httpClient.getJson(fullUrl, { desktopUa: true, timeoutMs: 10000 }).then(function (root) {
          return parseJsonMoviesTorrents(root, idx.name, trackers);
        }).catch(function () {
          return tryBase(bIdx + 1);
        });
      }
      return tryBase(0);
    }

    return Promise.resolve([]);
  }

  function searchAll(opts) {
    opts = opts || {};
    var query = String(opts.query || '').trim();
    var imdbId = String(opts.imdbId || '').trim();
    var filterMode = opts.filter || store.getStateRaw().swarmFilter || 'SAFE_ONLY';
    var pack = store.getActivePack();
    var indexers = Array.isArray(pack.swarmIndexers) ? pack.swarmIndexers : [];
    var trackers = pack.trackers || [];

    var tasks = [];
    for (var i = 0; i < indexers.length; i++) {
      var idx = indexers[i];
      if (!idx) continue;
      if (filterMode === 'SAFE_ONLY' && idx.adult) continue;
      if (filterMode === 'ADULT_ONLY' && !idx.adult) continue;
      if (opts.tabGroup && idx.tabGroup && idx.tabGroup !== opts.tabGroup) continue;
      tasks.push(runIndexer(idx, { query: query, imdbId: imdbId, filter: filterMode }, trackers));
    }

    var stateRaw = store.getStateRaw();
    if (stateRaw.torznabUrl && query) {
      var sep = stateRaw.torznabUrl.indexOf('?') !== -1 ? '&' : '?';
      var tUrl = stateRaw.torznabUrl + sep + 't=search&q=' + encodeURIComponent(query) +
        (stateRaw.torznabApiKey ? '&apikey=' + encodeURIComponent(stateRaw.torznabApiKey) : '');
      tasks.push(httpClient.getText(tUrl, { timeoutMs: 12000 }).then(function (r) {
        return parseGenericTorrentRssXml(r.text, 'Torznab', trackers);
      }).catch(function () { return []; }));
    }

    return Promise.all(tasks).then(function (batches) {
      var flat = [];
      for (var i = 0; i < batches.length; i++) flat = flat.concat(batches[i]);
      return dedupeAndRankStreams(flat, opts.prevStreamContext || null);
    });
  }

  function fetchImdbFallback(videoId) {
    var parsed = parseStremioImdbVideoId(videoId);
    if (!parsed.root || parsed.root.indexOf('tt') !== 0) return Promise.resolve([]);
    var pack = store.getActivePack();
    var indexers = Array.isArray(pack.swarmIndexers) ? pack.swarmIndexers : [];
    var trackers = pack.trackers || [];
    var tasks = [];
    for (var i = 0; i < indexers.length; i++) {
      var idx = indexers[i];
      if (!idx || !idx.imdbFallback) continue;
      if (idx.kind === 'json_movies_torrents' && parsed.season !== null) continue;
      tasks.push(runIndexer(idx, { imdbId: videoId, filter: 'SAFE_ONLY' }, trackers));
    }
    return Promise.all(tasks).then(function (batches) {
      var flat = [];
      for (var i = 0; i < batches.length; i++) flat = flat.concat(batches[i]);
      return flat;
    });
  }

  function fetchFeaturedFeed(feedKey) {
    var pack = store.getActivePack();
    var feeds = Array.isArray(pack.featuredFeeds) ? pack.featuredFeeds : [];
    var filterMode = store.getStateRaw().swarmFilter || 'SAFE_ONLY';
    var chosen = null;
    for (var i = 0; i < feeds.length; i++) {
      if (feeds[i] && feeds[i].key === feedKey) {
        chosen = feeds[i];
        break;
      }
    }
    if (!chosen && feeds.length > 0) {
      chosen = feeds.find(function (f) { return filterMode === 'SAFE_ONLY' ? !f.adult : true; }) || feeds[0];
    }
    if (!chosen) return Promise.resolve([]);
    if (filterMode === 'SAFE_ONLY' && chosen.adult) return Promise.resolve([]);

    return runIndexer({
      name: chosen.label || chosen.key,
      kind: chosen.kind,
      baseUrls: chosen.baseUrls
    }, {
      overrideUrl: chosen.url,
      overridePath: chosen.path,
      filter: chosen.adult ? 'ADULT_ONLY' : 'SAFE_ONLY'
    }, pack.trackers || []).then(function (list) {
      return dedupeAndRankStreams(list, null);
    });
  }

  return {
    searchAll: searchAll,
    fetchImdbFallback: fetchImdbFallback,
    fetchFeaturedFeed: fetchFeaturedFeed
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// 4. Multi-Server Jellyfin Client (LAN UDP + QuickConnect + webOS DeviceProfile)
// ─────────────────────────────────────────────────────────────────────────────

function createJellyfinClient(store) {
  var routeCache = {};

  function normalizeBaseUrl(raw) {
    var trimmed = String(raw || '').trim().replace(/\/+$/, '');
    if (!trimmed) throw new Error('Jellyfin server URL is empty');
    if (!/^https?:\/\//i.test(trimmed)) trimmed = 'http://' + trimmed;
    return trimmed;
  }

  function stripComposite(rawId) {
    var s = String(rawId || '');
    if (s.indexOf('jf:') === 0) {
      var rest = s.slice(3);
      var colon = rest.indexOf(':');
      if (colon !== -1) return rest.slice(colon + 1);
    }
    return s;
  }

  function parseComposite(rawId) {
    var s = String(rawId || '');
    if (s.indexOf('jf:') !== 0) return null;
    var rest = s.slice(3);
    var colon = rest.indexOf(':');
    if (colon <= 0) return null;
    return { serverId: rest.slice(0, colon), itemId: rest.slice(colon + 1) };
  }

  function authHeader(token) {
    var devId = store.getStateRaw().jellyfinDeviceId;
    var base = 'MediaBrowser Client="BrootLite", Device="LG webOS TV", DeviceId="' + devId + '", Version="2.1.5"';
    return token ? (base + ', Token="' + token + '"') : base;
  }

  function discoverLanServers(timeoutMs) {
    var wait = Math.max(300, Math.min(4500, Number(timeoutMs) || 1500));
    return new Promise(function (resolve) {
      var sock = dgram.createSocket('udp4');
      var seen = {};
      var found = [];
      var closed = false;

      function finish() {
        if (closed) return;
        closed = true;
        try { sock.close(); } catch (e) {}
        resolve(found);
      }

      var timer = setTimeout(finish, wait);

      sock.on('error', function () {
        clearTimeout(timer);
        finish();
      });

      sock.on('message', function (msg) {
        try {
          var v = JSON.parse(msg.toString('utf8'));
          if (v && v.Id && v.Address && !seen[v.Id]) {
            seen[v.Id] = true;
            found.push({
              serverId: String(v.Id),
              name: String(v.Name || 'Jellyfin Server'),
              address: String(v.Address).replace(/\/+$/, '')
            });
          }
        } catch (e) {}
      });

      try {
        sock.bind(0, function () {
          try {
            sock.setBroadcast(true);
            var probe = Buffer.from('Who is JellyfinServer?', 'utf8');
            sock.send(probe, 0, probe.length, 7359, '255.255.255.255');
          } catch (e) {
            clearTimeout(timer);
            finish();
          }
        });
      } catch (e) {
        clearTimeout(timer);
        finish();
      }
    });
  }

  function verifyIdentity(baseUrl, expectedId, timeoutMs) {
    return httpClient.getJson(baseUrl.replace(/\/+$/, '') + '/System/Info/Public', {
      timeoutMs: timeoutMs || 1200,
      insecureTls: true
    }).then(function (val) {
      var actual = (val && val.Id) || '';
      return !expectedId || actual.toLowerCase() === expectedId.toLowerCase();
    }).catch(function () { return false; });
  }

  function resolveActiveBaseUrl(profile) {
    var cached = routeCache[profile.serverId];
    var now = Date.now();
    if (cached && (now - cached.ts) < 5 * 60 * 1000) {
      return Promise.resolve(cached.url);
    }
    var lan = String(profile.lanUrl || '').trim().replace(/\/+$/, '');
    var rem = String(profile.remoteUrl || '').trim().replace(/\/+$/, '');
    if (lan && !rem) return Promise.resolve(lan);
    if (!lan && rem) return Promise.resolve(rem);
    if (!lan && !rem) return Promise.reject(new Error('Jellyfin profile has neither LAN nor Remote URL'));

    return verifyIdentity(lan, profile.serverId, 1200).then(function (lanOk) {
      if (lanOk) {
        routeCache[profile.serverId] = { url: lan, ts: now };
        return lan;
      }
      return verifyIdentity(rem, profile.serverId, 5000).then(function (remOk) {
        if (remOk) {
          routeCache[profile.serverId] = { url: rem, ts: now };
          return rem;
        }
        return lan;
      });
    });
  }

  function authedGet(url, token) {
    return httpClient.getJson(url, {
      headers: { Authorization: authHeader(token) },
      insecureTls: true,
      timeoutMs: 12000
    });
  }

  function fetchPublicInfo(rawUrl) {
    var clean = normalizeBaseUrl(rawUrl);
    return httpClient.getJson(clean + '/System/Info/Public', {
      headers: { Authorization: authHeader(null) },
      insecureTls: true,
      timeoutMs: 8000
    }).then(function (val) {
      if (!val || !val.Id) throw new Error('Jellyfin /System/Info/Public missing Id');
      return { serverId: String(val.Id), serverName: String(val.ServerName || 'Jellyfin'), baseUrl: clean };
    });
  }

  function checkQuickConnectEnabled(cleanUrl) {
    return httpClient.getJson(cleanUrl + '/QuickConnect/Enabled', {
      headers: { Authorization: authHeader(null) },
      insecureTls: true,
      timeoutMs: 5000
    }).then(function (val) { return val === true; }).catch(function () { return false; });
  }

  function initiateQuickConnect(rawUrl) {
    var clean = normalizeBaseUrl(rawUrl);
    return Promise.all([
      fetchPublicInfo(clean),
      checkQuickConnectEnabled(clean),
      httpClient.request(clean + '/QuickConnect/Initiate', {
        method: 'POST',
        headers: { Authorization: authHeader(null), 'Content-Length': '0' },
        responseType: 'json',
        insecureTls: true
      })
    ]).then(function (res) {
      var info = res[0];
      var qcEnabled = res[1];
      var initResp = res[2];
      if (!initResp.ok || !initResp.data) {
        throw new Error('QuickConnect/Initiate failed (' + initResp.status + ')');
      }
      return {
        serverId: info.serverId,
        serverName: info.serverName,
        lanUrl: clean,
        quickConnectEnabled: qcEnabled,
        secret: String(initResp.data.Secret || ''),
        code: String(initResp.data.Code || ''),
        authenticated: Boolean(initResp.data.Authenticated)
      };
    });
  }

  function profileFromAuthResult(lanUrl, remoteUrl, authVal) {
    var token = authVal && authVal.AccessToken;
    if (!token) throw new Error('Jellyfin auth response missing AccessToken');
    var user = authVal.User || {};
    var serverId = authVal.ServerId || '';
    return fetchPublicInfo(lanUrl).catch(function () {
      return { serverId: serverId, serverName: 'Jellyfin' };
    }).then(function (pub) {
      var now = Date.now();
      return {
        serverId: serverId || pub.serverId,
        serverName: pub.serverName || 'Jellyfin',
        lanUrl: lanUrl,
        remoteUrl: String(remoteUrl || '').trim().replace(/\/+$/, ''),
        userId: String(user.Id || ''),
        username: String(user.Name || 'User'),
        accessToken: token,
        bootstrapToken: token,
        lastVerifiedMs: now,
        updatedAtMs: now
      };
    });
  }

  function sanitizeProfile(p) {
    if (!p) return null;
    return {
      serverId: p.serverId,
      serverName: p.serverName,
      lanUrl: p.lanUrl,
      remoteUrl: p.remoteUrl,
      userId: p.userId,
      username: p.username,
      lastVerifiedMs: p.lastVerifiedMs,
      updatedAtMs: p.updatedAtMs
    };
  }

  function pollQuickConnect(lanUrl, remoteUrl, secret) {
    var clean = normalizeBaseUrl(lanUrl);
    return httpClient.getJson(clean + '/QuickConnect/Connect?secret=' + encodeURIComponent(secret), {
      headers: { Authorization: authHeader(null) },
      insecureTls: true
    }).then(function (state) {
      if (!state || !state.Authenticated) return { authenticated: false };
      return httpClient.postJson(clean + '/Users/AuthenticateWithQuickConnect', { Secret: secret }, {
        headers: { Authorization: authHeader(null) },
        insecureTls: true
      }).then(function (authResp) {
        if (!authResp.ok || !authResp.data) {
          throw new Error('AuthenticateWithQuickConnect failed (' + authResp.status + ')');
        }
        return profileFromAuthResult(clean, remoteUrl, authResp.data).then(function (profile) {
          store.upsertJellyfinServer(profile);
          return { authenticated: true, server: sanitizeProfile(profile) };
        });
      });
    }).catch(function (err) {
      if (err && /AuthenticateWithQuickConnect/.test(err.message)) throw err;
      return { authenticated: false };
    });
  }

  function loginPassword(lanUrl, remoteUrl, username, password) {
    var clean = normalizeBaseUrl(lanUrl);
    return httpClient.postJson(clean + '/Users/AuthenticateByName', {
      Username: String(username || ''),
      Pw: String(password || '')
    }, {
      headers: { Authorization: authHeader(null) },
      insecureTls: true
    }).then(function (resp) {
      if (!resp.ok || !resp.data) {
        throw new Error('Jellyfin login failed (' + resp.status + ')');
      }
      return profileFromAuthResult(clean, remoteUrl, resp.data).then(function (profile) {
        store.upsertJellyfinServer(profile);
        return sanitizeProfile(profile);
      });
    });
  }

  function mintDeviceTokenViaBootstrap(baseUrl, bootstrapToken) {
    var clean = normalizeBaseUrl(baseUrl);
    return initiateQuickConnect(clean).then(function (qc) {
      if (!qc.code || !qc.secret) throw new Error('QuickConnect returned empty code/secret');
      return httpClient.request(clean + '/QuickConnect/Authorize?code=' + encodeURIComponent(qc.code), {
        method: 'POST',
        headers: { 'X-Emby-Token': bootstrapToken, 'Content-Length': '0' },
        insecureTls: true
      }).then(function (authzResp) {
        if (!authzResp.ok) throw new Error('QuickConnect/Authorize failed (' + authzResp.status + ')');
        return httpClient.postJson(clean + '/Users/AuthenticateWithQuickConnect', { Secret: qc.secret }, {
          headers: { Authorization: authHeader(null) },
          insecureTls: true
        }).then(function (exResp) {
          if (!exResp.ok || !exResp.data) throw new Error('Bootstrap exchange failed');
          return profileFromAuthResult(clean, '', exResp.data);
        });
      });
    });
  }

  function parseMetaFromJf(item, baseUrl, profile) {
    if (!item || !item.Id) return null;
    var rawId = String(item.Id);
    var jfType = String(item.Type || 'Movie');
    var mediaType = (jfType === 'Series' || jfType === 'Season' || jfType === 'Episode') ? 'series' : 'movie';
    var targetId = jfType === 'Episode' ? (item.SeriesId || rawId) : rawId;
    var name = jfType === 'Episode' && item.SeriesName
      ? item.SeriesName + ' — ' + (item.Name || '')
      : (item.Name || rawId);
    var rating = typeof item.CommunityRating === 'number' ? item.CommunityRating.toFixed(1) : '';
    var year = item.ProductionYear ? String(item.ProductionYear) : '';
    var mins = item.RunTimeTicks ? Math.floor((item.RunTimeTicks / TICKS_PER_MS) / 60000) : 0;
    return {
      id: 'jf:' + profile.serverId + ':' + targetId,
      rawId: rawId,
      playItemId: 'jf:' + profile.serverId + ':' + rawId,
      type: mediaType,
      name: String(name),
      poster: baseUrl + '/Items/' + targetId + '/Images/Primary?maxWidth=440&quality=88',
      background: baseUrl + '/Items/' + targetId + '/Images/Backdrop/0?maxWidth=1920&quality=85',
      description: String(item.Overview || ''),
      releaseInfo: year,
      imdbRating: rating,
      runtime: mins > 0 ? mins + ' min' : '',
      genres: Array.isArray(item.Genres) ? item.Genres.map(String) : [],
      videos: [],
      addonSource: 'Jellyfin • ' + profile.serverName
    };
  }

  function resolveProfile(params) {
    var sid = params && params.serverId;
    if (!sid && params && (params.itemId || params.seriesId)) {
      var parsed = parseComposite(params.itemId || params.seriesId);
      if (parsed) sid = parsed.serverId;
    }
    var prof = store.getJellyfinServer(sid);
    if (!prof) throw new Error('No Jellyfin server configured');
    return prof;
  }

  function browse(params) {
    params = params || {};
    var prof = resolveProfile(params);
    var action = String(params.action || 'home');
    var limit = Math.max(1, Math.min(100, parseInt(params.limit || 24, 10) || 24));

    return resolveActiveBaseUrl(prof).then(function (base) {
      if (action === 'views') {
        return authedGet(base + '/Users/' + prof.userId + '/Views', prof.accessToken).then(function (root) {
          var views = (root && root.Items || []).map(function (v) {
            return {
              id: String(v.Id),
              name: String(v.Name || 'Library'),
              collectionType: String(v.CollectionType || 'movies').toLowerCase()
            };
          });
          return { action: 'views', serverId: prof.serverId, views: views };
        });
      }

      if (action === 'resume') {
        return authedGet(
          base + '/Users/' + prof.userId + '/Items/Resume?Limit=' + limit + '&Recursive=true&Fields=Overview,Genres,PrimaryImageAspectRatio,UserData&MediaTypes=Video',
          prof.accessToken
        ).then(function (root) {
          return {
            action: 'resume',
            serverId: prof.serverId,
            items: (root && root.Items || []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      if (action === 'nextUp') {
        return authedGet(
          base + '/Shows/NextUp?UserId=' + prof.userId + '&Limit=' + limit + '&Fields=Overview,Genres,PrimaryImageAspectRatio,UserData',
          prof.accessToken
        ).then(function (root) {
          return {
            action: 'nextUp',
            serverId: prof.serverId,
            items: (root && root.Items || []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      if (action === 'latest') {
        var pid = params.parentId || '';
        return authedGet(
          base + '/Users/' + prof.userId + '/Items/Latest?ParentId=' + encodeURIComponent(pid) + '&Limit=' + limit + '&Fields=Overview,Genres,CommunityRating',
          prof.accessToken
        ).then(function (arr) {
          return {
            action: 'latest',
            serverId: prof.serverId,
            items: (Array.isArray(arr) ? arr : []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      if (action === 'items') {
        var u = base + '/Users/' + prof.userId + '/Items?Recursive=true&StartIndex=' + (Number(params.startIndex) || 0) +
          '&Limit=' + limit +
          '&SortBy=' + encodeURIComponent(params.sortBy || 'SortName') +
          '&SortOrder=' + encodeURIComponent(params.sortOrder || 'Ascending') +
          '&Fields=Overview,Genres,CommunityRating,ProductionYear';
        if (params.parentId) u += '&ParentId=' + encodeURIComponent(params.parentId);
        if (params.includeItemTypes) u += '&IncludeItemTypes=' + encodeURIComponent(params.includeItemTypes);
        if (params.genre && params.genre.toLowerCase() !== 'all') u += '&Genres=' + encodeURIComponent(params.genre);
        if (params.year && params.year.toLowerCase() !== 'all') u += '&Years=' + encodeURIComponent(params.year);
        return authedGet(u, prof.accessToken).then(function (root) {
          return {
            action: 'items',
            serverId: prof.serverId,
            items: (root && root.Items || []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      if (action === 'genres' || action === 'years') {
        var ep = action === 'genres'
          ? base + '/Genres?UserId=' + prof.userId + '&SortBy=SortName'
          : base + '/Years?UserId=' + prof.userId + '&SortBy=SortName&SortOrder=Descending';
        if (params.parentId) ep += '&ParentId=' + encodeURIComponent(params.parentId);
        return authedGet(ep, prof.accessToken).then(function (root) {
          var names = (root && root.Items || []).map(function (x) { return x && x.Name; }).filter(Boolean);
          var out = { action: action, serverId: prof.serverId };
          out[action] = names;
          return out;
        });
      }

      if (action === 'episodes') {
        var cleanSeries = stripComposite(params.seriesId || params.itemId || '');
        var epUrl = base + '/Shows/' + encodeURIComponent(cleanSeries) + '/Episodes?UserId=' + prof.userId + '&Fields=Overview';
        if (params.seasonId) epUrl += '&SeasonId=' + encodeURIComponent(params.seasonId);
        return authedGet(epUrl, prof.accessToken).then(function (root) {
          var eps = (root && root.Items || []).map(function (ep) {
            return {
              id: 'jf:' + prof.serverId + ':' + ep.Id,
              title: String(ep.Name || 'Episode'),
              season: typeof ep.ParentIndexNumber === 'number' ? ep.ParentIndexNumber : 1,
              episode: typeof ep.IndexNumber === 'number' ? ep.IndexNumber : 1,
              overview: String(ep.Overview || ''),
              released: String(ep.PremiereDate || ''),
              thumbnail: base + '/Items/' + ep.Id + '/Images/Primary?maxWidth=480'
            };
          });
          return { action: 'episodes', serverId: prof.serverId, episodes: eps };
        });
      }

      if (action === 'detail') {
        var cleanId = stripComposite(params.itemId || '');
        return authedGet(base + '/Users/' + prof.userId + '/Items/' + encodeURIComponent(cleanId), prof.accessToken)
          .then(function (val) {
            var meta = parseMetaFromJf(val, base, prof);
            if (!meta) throw new Error('Jellyfin item not found');
            if (meta.type === 'series') {
              return browse({ serverId: prof.serverId, action: 'episodes', seriesId: cleanId }).then(function (epRes) {
                meta.videos = epRes.episodes || [];
                return { action: 'detail', serverId: prof.serverId, meta: meta };
              });
            }
            return { action: 'detail', serverId: prof.serverId, meta: meta };
          });
      }

      if (action === 'similar') {
        var simId = stripComposite(params.itemId || '');
        return authedGet(
          base + '/Items/' + encodeURIComponent(simId) + '/Similar?UserId=' + prof.userId + '&Limit=' + limit + '&Fields=Overview,Genres,CommunityRating',
          prof.accessToken
        ).then(function (root) {
          return {
            action: 'similar',
            serverId: prof.serverId,
            items: (root && root.Items || []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      if (action === 'search') {
        return authedGet(
          base + '/Users/' + prof.userId + '/Items?Recursive=true&SearchTerm=' + encodeURIComponent(String(params.query || '').trim()) + '&IncludeItemTypes=Movie,Series&Limit=' + limit + '&Fields=Overview,Genres,CommunityRating',
          prof.accessToken
        ).then(function (root) {
          return {
            action: 'search',
            serverId: prof.serverId,
            items: (root && root.Items || []).map(function (x) { return parseMetaFromJf(x, base, prof); }).filter(Boolean)
          };
        });
      }

      // Default 'home' aggregate
      return Promise.all([
        browse({ serverId: prof.serverId, action: 'views' }).catch(function () { return { views: [] }; }),
        browse({ serverId: prof.serverId, action: 'resume', limit: 16 }).catch(function () { return { items: [] }; }),
        browse({ serverId: prof.serverId, action: 'nextUp', limit: 16 }).catch(function () { return { items: [] }; })
      ]).then(function (res) {
        return {
          action: 'home',
          serverId: prof.serverId,
          serverName: prof.serverName,
          views: res[0].views || [],
          resume: res[1].items || [],
          nextUp: res[2].items || []
        };
      });
    });
  }

  function fetchSkipSegments(base, prof, itemId) {
    return authedGet(base + '/MediaSegments/' + encodeURIComponent(itemId), prof.accessToken).then(function (msJson) {
      var list = Array.isArray(msJson && msJson.Items) ? msJson.Items : [];
      var segs = [];
      for (var i = 0; i < list.length; i++) {
        var s = list[i];
        var st = Number(s.StartTicks || 0);
        var et = Number(s.EndTicks || 0);
        if (et <= st) continue;
        var t = String(s.Type || 'Intro').toLowerCase();
        var kind = 'INTRO';
        var label = 'Skip Intro';
        if (t === 'outro' || t === 'credits') { kind = 'OUTRO'; label = 'Skip Credits'; }
        else if (t === 'recap') { kind = 'RECAP'; label = 'Skip Recap'; }
        else if (t === 'preview') { kind = 'PREVIEW'; label = 'Skip Preview'; }
        else if (t === 'commercial') { kind = 'SPONSOR'; label = 'Skip Commercial'; }
        segs.push({
          startMs: Math.floor(st / TICKS_PER_MS),
          endMs: Math.floor(et / TICKS_PER_MS),
          kind: kind,
          label: label
        });
      }
      return segs;
    }).catch(function () { return []; }).then(function (segs) {
      if (segs.length > 0) return segs;
      return authedGet(base + '/Episode/' + encodeURIComponent(itemId) + '/IntroTimestamps', prof.accessToken).then(function (ij) {
        var s = Number((ij && ij.IntroStart) || 0);
        var e = Number((ij && ij.IntroEnd) || 0);
        if (e > s) {
          return [{
            startMs: Math.floor(s * 1000),
            endMs: Math.floor(e * 1000),
            kind: 'INTRO',
            label: 'Skip Intro'
          }];
        }
        return [];
      }).catch(function () { return []; });
    });
  }

  function resolvePlay(params, mediaBaseUrl) {
    var prof = resolveProfile(params);
    var itemId = stripComposite(params.itemId || '');
    if (!itemId) return Promise.reject(new Error('Missing Jellyfin itemId'));

    var deviceProfile = {
      DeviceProfile: {
        Name: 'BrootLite LG webOS DirectPlay/DirectStream',
        MaxStreamingBitrate: 120000000,
        DirectPlayProfiles: [
          {
            Type: 'Video',
            Container: 'mp4,m4v,mkv,webm,ts,mov',
            VideoCodec: 'h264,hevc,vp9,av1',
            AudioCodec: 'aac,ac3,eac3,opus,flac,mp3'
          },
          {
            Type: 'Audio',
            Container: 'aac,mp3,flac,opus,ogg,wav'
          }
        ],
        TranscodingProfiles: [
          {
            Type: 'Video',
            Container: 'ts',
            Protocol: 'hls',
            VideoCodec: 'h264,hevc',
            AudioCodec: 'eac3,aac,ac3,mp3',
            Context: 'Streaming',
            CopyTimestamps: true
          }
        ],
        SubtitleProfiles: [
          { Format: 'vtt', Method: 'External' },
          { Format: 'srt', Method: 'External' },
          { Format: 'ass', Method: 'External' },
          { Format: 'ssa', Method: 'External' }
        ]
      }
    };

    return resolveActiveBaseUrl(prof).then(function (base) {
      var pbUrl = base + '/Items/' + encodeURIComponent(itemId) + '/PlaybackInfo?UserId=' + prof.userId;
      return Promise.all([
        httpClient.postJson(pbUrl, deviceProfile, {
          headers: { Authorization: authHeader(prof.accessToken) },
          insecureTls: true
        }),
        authedGet(base + '/Users/' + prof.userId + '/Items/' + encodeURIComponent(itemId), prof.accessToken).catch(function () { return null; }),
        fetchSkipSegments(base, prof, itemId)
      ]).then(function (results) {
        var pbResp = results[0];
        if (!pbResp.ok || !pbResp.data) throw new Error('Jellyfin PlaybackInfo failed (' + pbResp.status + ')');
        var info = pbResp.data;
        var source = Array.isArray(info.MediaSources) ? info.MediaSources[0] : null;
        if (!source) throw new Error('PlaybackInfo returned no MediaSources');

        var playSessionId = String(info.PlaySessionId || '');
        var mediaSourceId = String(source.Id || itemId);
        var container = String(source.Container || 'mkv');
        var supportsDirectPlay = source.SupportsDirectPlay !== false;
        var supportsDirectStream = source.SupportsDirectStream !== false;

        var playUrl;
        var playMethod;
        if (supportsDirectPlay) {
          playUrl = base + '/Videos/' + itemId + '/stream.' + container +
            '?Static=true&mediaSourceId=' + encodeURIComponent(mediaSourceId) +
            '&api_key=' + encodeURIComponent(prof.accessToken);
          playMethod = 'DirectPlay';
        } else if (source.TranscodingUrl) {
          playUrl = source.TranscodingUrl.indexOf('http') === 0
            ? source.TranscodingUrl
            : (base + source.TranscodingUrl);
          playMethod = supportsDirectStream ? 'DirectStream' : 'Transcode (HLS)';
        } else {
          playUrl = base + '/Videos/' + itemId + '/stream?Static=true&mediaSourceId=' +
            encodeURIComponent(mediaSourceId) + '&api_key=' + encodeURIComponent(prof.accessToken);
          playMethod = 'DirectStream';
        }

        var externalSubtitles = [];
        var streams = Array.isArray(source.MediaStreams) ? source.MediaStreams : [];
        for (var i = 0; i < streams.length; i++) {
          var ms = streams[i];
          if (!ms || ms.Type !== 'Subtitle' || !ms.IsExternal || !ms.DeliveryUrl) continue;
          var rawSubUrl = ms.DeliveryUrl.indexOf('http') === 0 ? ms.DeliveryUrl : (base + ms.DeliveryUrl);
          externalSubtitles.push({
            id: 'jfsub:' + (ms.Index || i),
            url: rawSubUrl,
            vttUrl: mediaBaseUrl ? (mediaBaseUrl + '/sub.vtt?u=' + httpClient.b64UrlEncode(rawSubUrl)) : rawSubUrl,
            lang: String(ms.Language || 'und'),
            addonName: prof.serverName
          });
        }

        var itemDetail = results[1];
        var runtimeMs = source.RunTimeTicks ? Math.floor(source.RunTimeTicks / TICKS_PER_MS) : 0;
        var resumePositionMs = (itemDetail && itemDetail.UserData && itemDetail.UserData.PlaybackPositionTicks)
          ? Math.floor(itemDetail.UserData.PlaybackPositionTicks / TICKS_PER_MS)
          : 0;

        return {
          playUrl: playUrl,
          requestHeaders: { Authorization: authHeader(prof.accessToken) },
          playMethod: playMethod,
          playSessionId: playSessionId,
          mediaSourceId: mediaSourceId,
          externalSubtitles: externalSubtitles,
          skipSegments: results[2] || [],
          runtimeMs: runtimeMs,
          resumePositionMs: resumePositionMs
        };
      });
    });
  }

  function report(params) {
    var prof = resolveProfile(params);
    var itemId = stripComposite(params.itemId || '');
    var action = String(params.action || 'progress').toLowerCase();
    var msid = String(params.mediaSourceId || '');
    var psid = String(params.playSessionId || '');
    var posTicks = Math.max(0, Math.floor(Number(params.positionMs || 0))) * TICKS_PER_MS;

    return resolveActiveBaseUrl(prof).then(function (base) {
      var headers = { Authorization: authHeader(prof.accessToken) };
      if (action === 'start') {
        return httpClient.postJson(base + '/Sessions/Playing', {
          ItemId: itemId,
          MediaSourceId: msid,
          PlaySessionId: psid,
          PositionTicks: posTicks,
          CanSeek: true,
          IsPaused: false
        }, { headers: headers, insecureTls: true }).catch(function () {});
      }
      if (action === 'stopped' || action === 'stop') {
        return httpClient.postJson(base + '/Sessions/Playing/Stopped', {
          ItemId: itemId,
          MediaSourceId: msid,
          PlaySessionId: psid,
          PositionTicks: posTicks
        }, { headers: headers, insecureTls: true }).catch(function () {}).then(function () {
          if (psid) {
            var devId = store.getStateRaw().jellyfinDeviceId;
            return httpClient.request(
              base + '/Videos/ActiveEncodings?DeviceId=' + encodeURIComponent(devId) + '&PlaySessionId=' + encodeURIComponent(psid),
              { method: 'DELETE', headers: headers, insecureTls: true }
            ).catch(function () {});
          }
        });
      }
      return httpClient.postJson(base + '/Sessions/Playing/Progress', {
        ItemId: itemId,
        MediaSourceId: msid,
        PlaySessionId: psid,
        PositionTicks: posTicks,
        IsPaused: Boolean(params.isPaused),
        CanSeek: true
      }, { headers: headers, insecureTls: true }).catch(function () {});
    }).then(function () { return { reported: true }; });
  }

  return {
    discoverLanServers: discoverLanServers,
    initiateQuickConnect: initiateQuickConnect,
    pollQuickConnect: pollQuickConnect,
    loginPassword: loginPassword,
    mintDeviceTokenViaBootstrap: mintDeviceTokenViaBootstrap,
    browse: browse,
    resolvePlay: resolvePlay,
    report: report,
    parseComposite: parseComposite
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// 5. Streaming Clients (YouTube · Twitch · Odysee · Rumble)
// ─────────────────────────────────────────────────────────────────────────────

var cachedYtTvCreds = null;
var YT_OAUTH_SCOPE = 'http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content';
var INNERTUBE_BASE = 'https://www.youtube.com/youtubei/v1';

function resolveYtTvClientCredentials() {
  if (cachedYtTvCreds) return Promise.resolve(cachedYtTvCreds);
  return httpClient.getText('https://www.youtube.com/tv', {
    headers: {
      'User-Agent': 'Mozilla/5.0 (SMART-TV; Linux; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/5.0 TV Safari/537.36'
    },
    timeoutMs: 10000
  }).then(function (page) {
    var html = (page && page.text) || '';
    var scriptMatch = html.match(/src="(\/s\/player\/[^"]+\/tv-player-ias\.vflset\/[^"]+\.js|[^"]*tv-player[^"]*\.js)"/i);
    var inspectPromise = scriptMatch
      ? httpClient.getText(scriptMatch[1].indexOf('http') === 0 ? scriptMatch[1] : ('https://www.youtube.com' + scriptMatch[1]), { timeoutMs: 10000 }).then(function (js) {
          return html + '\n' + ((js && js.text) || '');
        }).catch(function () { return html; })
      : Promise.resolve(html);
    return inspectPromise.then(function (combined) {
      var idM = combined.match(/clientId\s*:\s*["']([0-9]+-[a-z0-9_]+\.apps\.googleusercontent\.com)["']/i) ||
        combined.match(/["']([0-9]{10,}-[a-z0-9_]+\.apps\.googleusercontent\.com)["']/i);
      var secM = combined.match(/clientSecret\s*:\s*["']([A-Za-z0-9_-]{16,48})["']/i);
      if (!idM || !secM) {
        throw new Error('Unable to discover YouTube TV OAuth client credentials');
      }
      cachedYtTvCreds = { clientId: idM[1], clientSecret: secM[1] };
      return cachedYtTvCreds;
    });
  });
}

var YT_STREAM_CLIENTS = [
  {
    name: 'IOS',
    clientName: 'IOS',
    clientVersion: '20.10.4',
    clientIdHeader: '5',
    userAgent: 'com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)'
  },
  {
    name: 'ANDROID_VR',
    clientName: 'ANDROID_VR',
    clientVersion: '1.61.48',
    clientIdHeader: '28',
    userAgent: 'com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip'
  },
  {
    name: 'TV_SIMPLY',
    clientName: 'TVHTML5_SIMPLY_EMBEDDED_PLAYER',
    clientVersion: '2.0',
    clientIdHeader: '85',
    userAgent: 'Mozilla/5.0 (PlayStation; PlayStation 5/2.26) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0 Safari/605.1.15'
  }
];

function extractYtText(v) {
  if (!v) return '';
  if (typeof v.simpleText === 'string') return v.simpleText;
  if (Array.isArray(v.runs)) {
    return v.runs.map(function (r) { return (r && r.text) || ''; }).join('');
  }
  return '';
}

function extractYtThumb(v) {
  var arr = v && (Array.isArray(v.thumbnails) ? v.thumbnails : (Array.isArray(v) ? v : null));
  if (!arr || arr.length === 0) return '';
  var last = arr[arr.length - 1];
  return (last && last.url) || '';
}

function parseColonDurationSec(txt) {
  var parts = String(txt || '').trim().split(':').map(function (p) { return parseInt(p, 10); });
  if (parts.some(isNaN)) return 0;
  if (parts.length === 2) return parts[0] * 60 + parts[1];
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
  return 0;
}

function parseInnerTubeFeedPage(root) {
  var items = [];
  var seen = {};
  var continuationToken = null;

  function parseRenderer(r) {
    if (!r || typeof r !== 'object') return null;
    var vid = r.videoId || (r.onSelectCommand && r.onSelectCommand.watchEndpoint && r.onSelectCommand.watchEndpoint.videoId);
    if (!vid) return null;
    vid = String(vid).trim();
    var title = extractYtText(r.title) || extractYtText(r.metadata && r.metadata.tileMetadataRenderer && r.metadata.tileMetadataRenderer.title);
    if (!title) return null;
    var channelName = extractYtText(r.ownerText) || extractYtText(r.longBylineText) || extractYtText(r.shortBylineText);
    var channelId = '';
    if (r.ownerText && r.ownerText.runs && r.ownerText.runs[0] && r.ownerText.runs[0].navigationEndpoint && r.ownerText.runs[0].navigationEndpoint.browseEndpoint) {
      channelId = r.ownerText.runs[0].navigationEndpoint.browseEndpoint.browseId || '';
    } else if (r.longBylineText && r.longBylineText.runs && r.longBylineText.runs[0] && r.longBylineText.runs[0].navigationEndpoint && r.longBylineText.runs[0].navigationEndpoint.browseEndpoint) {
      channelId = r.longBylineText.runs[0].navigationEndpoint.browseEndpoint.browseId || '';
    }
    var durText = extractYtText(r.lengthText);
    var thumb = extractYtThumb(r.thumbnail) || ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');
    var isLive = !durText && JSON.stringify(r.badges || []).indexOf('LIVE') !== -1;

    return {
      platform: 'youtube',
      videoId: vid,
      title: title,
      channelName: channelName,
      channelId: channelId,
      channelAvatar: '',
      thumbnailUrl: thumb,
      durationSec: parseColonDurationSec(durText),
      durationText: durText,
      viewCountText: extractYtText(r.shortViewCountText),
      publishedText: extractYtText(r.publishedTimeText),
      isLive: isLive,
      isShort: false,
      resumeProgressPct: 0,
      categoryName: '',
      directUrlHint: ''
    };
  }

  function walk(node) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) walk(node[i]);
      return;
    }
    var keys = ['videoRenderer', 'compactVideoRenderer', 'gridVideoRenderer', 'playlistVideoRenderer', 'tileRenderer'];
    for (var k = 0; k < keys.length; k++) {
      if (node[keys[k]]) {
        var parsed = parseRenderer(node[keys[k]]);
        if (parsed && !seen[parsed.videoId]) {
          seen[parsed.videoId] = true;
          items.push(parsed);
        }
      }
    }
    if (!continuationToken) {
      var tok = (
        node.continuationItemRenderer &&
        node.continuationItemRenderer.continuationEndpoint &&
        node.continuationItemRenderer.continuationEndpoint.continuationCommand &&
        node.continuationItemRenderer.continuationEndpoint.continuationCommand.token
      ) || (node.nextContinuationData && node.nextContinuationData.continuation);
      if (tok) continuationToken = String(tok);
    }
    var objKeys = Object.keys(node);
    for (var j = 0; j < objKeys.length; j++) {
      walk(node[objKeys[j]]);
    }
  }

  walk(root);
  return { items: items, continuationToken: continuationToken };
}

function escapeXmlAttr(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function buildDashMpdXml(durationSec, reps) {
  var groups = {};
  for (var i = 0; i < reps.length; i++) {
    var r = reps[i];
    if (!groups[r.mimeType]) groups[r.mimeType] = [];
    groups[r.mimeType].push(r);
  }
  var mimeKeys = Object.keys(groups).sort();
  var xml = '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT' + Math.max(1, durationSec) + 'S">\n' +
    '  <Period start="PT0S">\n';
  for (var a = 0; a < mimeKeys.length; a++) {
    var mime = mimeKeys[a];
    var isVideo = mime.indexOf('video/') === 0;
    xml += '    <AdaptationSet id="' + a + '" mimeType="' + escapeXmlAttr(mime) + '" contentType="' + (isVideo ? 'video' : 'audio') + '" subsegmentAlignment="true">\n';
    var list = groups[mime];
    for (var j = 0; j < list.length; j++) {
      var rep = list[j];
      var attrs = 'id="' + rep.itag + '" codecs="' + escapeXmlAttr(rep.codecs) + '" bandwidth="' + rep.bitrate + '"';
      if (rep.width && rep.height) attrs += ' width="' + rep.width + '" height="' + rep.height + '"';
      if (rep.fps) attrs += ' frameRate="' + rep.fps + '"';
      if (rep.audioSampleRate) attrs += ' audioSamplingRate="' + rep.audioSampleRate + '"';
      xml += '      <Representation ' + attrs + '>\n';
      if (!isVideo) {
        xml += '        <AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>\n';
      }
      xml += '        <BaseURL>' + escapeXmlAttr(rep.baseUrl) + '</BaseURL>\n';
      xml += '        <SegmentBase indexRange="' + rep.indexRange[0] + '-' + rep.indexRange[1] + '">\n';
      xml += '          <Initialization range="' + rep.initRange[0] + '-' + rep.initRange[1] + '"/>\n';
      xml += '        </SegmentBase>\n';
      xml += '      </Representation>\n';
    }
    xml += '    </AdaptationSet>\n';
  }
  xml += '  </Period>\n</MPD>\n';
  return xml;
}

function createYouTubeClient(store, dashManifestStore) {
  function validAuthContext() {
    var acc = store.getActiveYoutubeAccount();
    if (!acc) return Promise.resolve(null);
    var now = Date.now();
    if (acc.accessToken && (acc.expiresAtMs || 0) > now + 60000) {
      return Promise.resolve({ token: acc.accessToken, pageId: acc.pageId || '' });
    }
    if (!acc.refreshToken) return Promise.resolve(null);
    var credsPromise = (acc.oauthClientId && acc.oauthClientSecret)
      ? Promise.resolve({ clientId: acc.oauthClientId, clientSecret: acc.oauthClientSecret })
      : resolveYtTvClientCredentials();
    return credsPromise.then(function (creds) {
      return httpClient.postForm('https://www.youtube.com/o/oauth2/token', {
        client_id: creds.clientId,
        client_secret: creds.clientSecret,
        refresh_token: acc.refreshToken,
        grant_type: 'refresh_token'
      });
    }).then(function (r) {
      if (!r.ok || !r.data || !r.data.access_token) return null;
      var updated = Object.assign({}, acc, {
        accessToken: r.data.access_token,
        expiresAtMs: Date.now() + (Number(r.data.expires_in || 3600) * 1000)
      });
      store.upsertYoutubeAccount(updated);
      return { token: updated.accessToken, pageId: updated.pageId || '' };
    }).catch(function () { return null; });
  }

  function postInnerTube(pathSeg, payload, useAuth) {
    return (useAuth ? validAuthContext() : Promise.resolve(null)).then(function (auth) {
      var headers = {};
      if (auth) {
        headers.Authorization = 'Bearer ' + auth.token;
        if (auth.pageId) headers['X-Goog-Pageid'] = auth.pageId;
      }
      return httpClient.postJson(INNERTUBE_BASE + '/' + pathSeg + '?prettyPrint=false', payload, {
        headers: headers,
        timeoutMs: 12000
      }).then(function (r) {
        if (!r.ok) throw new Error('InnerTube ' + pathSeg + ' HTTP ' + r.status);
        return r.data;
      });
    });
  }

  function webContext(pageId) {
    var ctx = {
      context: {
        client: { clientName: 'WEB', clientVersion: '2.20250312.04.00', hl: 'en', gl: 'US' }
      }
    };
    if (pageId) ctx.context.user = { onBehalfOfUser: pageId };
    return ctx;
  }

  function tvContext(pageId) {
    var ctx = {
      context: {
        client: { clientName: 'TVHTML5', clientVersion: '7.20250312.16.00', hl: 'en', gl: 'US' }
      }
    };
    if (pageId) ctx.context.user = { onBehalfOfUser: pageId };
    return ctx;
  }

  function browseFeed(browseId, continuation, useAuth) {
    return (useAuth ? validAuthContext() : Promise.resolve(null)).then(function (auth) {
      var payload = auth ? tvContext(auth.pageId) : webContext(null);
      if (continuation) payload.continuation = continuation;
      else payload.browseId = browseId;
      return postInnerTube('browse', payload, useAuth).then(parseInnerTubeFeedPage);
    });
  }

  function fetchAtomRss(channelId) {
    var url = 'https://www.youtube.com/feeds/videos.xml?channel_id=' + encodeURIComponent(channelId);
    return httpClient.getText(url, { timeoutMs: 10000 }).then(function (r) {
      var xml = r.text || '';
      var out = [];
      var entryRe = /<entry\b[^>]*>([\s\S]*?)<\/entry>/gi;
      var m;
      var author = extractXmlTagText(xml, 'name') || 'YouTube';
      while ((m = entryRe.exec(xml)) !== null) {
        var blk = m[1];
        var vid = extractXmlTagText(blk, 'videoId');
        var title = extractXmlTagText(blk, 'title');
        if (!vid || !title) continue;
        out.push({
          platform: 'youtube',
          videoId: vid,
          title: title,
          channelName: author,
          channelId: channelId,
          thumbnailUrl: 'https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg',
          publishedText: extractXmlTagText(blk, 'published').slice(0, 10),
          isLive: false
        });
      }
      return out;
    }).catch(function () { return []; });
  }

  function startAuth() {
    var devId = store.getStateRaw().installationId;
    return resolveYtTvClientCredentials().then(function (creds) {
      return httpClient.postForm('https://www.youtube.com/o/oauth2/device/code', {
        client_id: creds.clientId,
        scope: YT_OAUTH_SCOPE,
        device_id: devId,
        device_model: 'LG webOS TV'
      }).then(function (r) {
        if (!r.ok || !r.data || !r.data.device_code) {
          throw new Error('YouTube device/code failed (' + r.status + ')');
        }
        return {
          platform: 'youtube',
          deviceCode: r.data.device_code,
          userCode: r.data.user_code || '',
          verificationUrl: r.data.verification_url || 'https://www.youtube.com/activate',
          expiresInSec: r.data.expires_in || 1800,
          intervalSec: r.data.interval || 5,
          clientId: creds.clientId,
          clientSecret: creds.clientSecret
        };
      });
    });
  }

  function listBrandAccounts(accessToken, refreshToken, expiresAtMs, cid, csec) {
    return httpClient.postJson(INNERTUBE_BASE + '/account/account_list?prettyPrint=false', tvContext(null), {
      headers: { Authorization: 'Bearer ' + accessToken }
    }).then(function (r) {
      var root = r.data || {};
      var accounts = [];
      function walk(node) {
        if (!node || typeof node !== 'object') return;
        if (Array.isArray(node)) {
          for (var i = 0; i < node.length; i++) walk(node[i]);
          return;
        }
        if (node.accountItem && typeof node.accountItem === 'object') {
          var ai = node.accountItem;
          var name = extractYtText(ai.accountName);
          var handle = extractYtText(ai.channelHandle);
          var avatar = extractYtThumb(ai.accountPhoto);
          var tokens = ai.serviceEndpoint && ai.serviceEndpoint.signInEndpoint && ai.serviceEndpoint.signInEndpoint.directSigninIdentity;
          var pageId = (tokens && tokens.pageId) || '';
          var gaia = (tokens && tokens.effectiveObfuscatedGaiaId) || '';
          var accId = pageId ? ('yt-brand-' + pageId) : (gaia ? ('yt-gaia-' + gaia) : (handle ? ('yt-handle-' + handle) : ('yt-' + (accounts.length + 1))));
          if (name) {
            accounts.push({
              accountId: accId,
              accountName: name,
              channelHandle: handle,
              avatarUrl: avatar,
              pageId: pageId,
              refreshToken: refreshToken,
              accessToken: accessToken,
              expiresAtMs: expiresAtMs,
              oauthClientId: cid,
              oauthClientSecret: csec,
              updatedAtMs: Date.now()
            });
          }
          return;
        }
        var ks = Object.keys(node);
        for (var j = 0; j < ks.length; j++) walk(node[ks[j]]);
      }
      walk(root);
      return accounts;
    }).catch(function () { return []; });
  }

  function pollAuth(session) {
    var credsPromise = (session && session.clientId && session.clientSecret)
      ? Promise.resolve({ clientId: session.clientId, clientSecret: session.clientSecret })
      : resolveYtTvClientCredentials();
    return credsPromise.then(function (creds) {
      var cid = creds.clientId;
      var csec = creds.clientSecret;
      return httpClient.postForm('https://www.youtube.com/o/oauth2/token', {
        client_id: cid,
        client_secret: csec,
        code: session.deviceCode,
        grant_type: 'http://oauth.net/grant_type/device/1.0'
      }).then(validateYtTokenPoll(cid, csec));
    });
  }

  function validateYtTokenPoll(cid, csec) {
    return function (r) {
      var val = r.data || {};
      if (val.error === 'authorization_pending' || val.error === 'slow_down') {
        return { authenticated: false };
      }
      if (val.error) throw new Error('YouTube OAuth error: ' + val.error);
      if (!val.access_token) return { authenticated: false };

      var expMs = Date.now() + (Number(val.expires_in || 3600) * 1000);
      return listBrandAccounts(val.access_token, val.refresh_token || '', expMs, cid, csec).then(function (accs) {
        if (accs.length === 0) {
          accs.push({
            accountId: 'yt-' + httpClient.uuidV4().slice(0, 8),
            accountName: 'YouTube Account',
            channelHandle: '',
            avatarUrl: '',
            pageId: '',
            refreshToken: val.refresh_token || '',
            accessToken: val.access_token,
            expiresAtMs: expMs,
            oauthClientId: cid,
            oauthClientSecret: csec,
            updatedAtMs: Date.now()
          });
        }
        for (var i = 0; i < accs.length; i++) store.upsertYoutubeAccount(accs[i]);
        return {
          authenticated: true,
          accounts: accs.map(function (a) {
            return {
              accountId: a.accountId,
              accountName: a.accountName,
              channelHandle: a.channelHandle,
              avatarUrl: a.avatarUrl,
              pageId: a.pageId
            };
          })
        };
      });
    };
  }

  function fetchSponsorBlock(videoId) {
    if (store.getStateRaw().sponsorBlockEnabled === false) return Promise.resolve([]);
    var cats = encodeURIComponent('["sponsor","selfpromo","interaction","intro","outro","preview","filler"]');
    var url = 'https://sponsor.ajay.app/api/skipSegments?videoID=' + encodeURIComponent(videoId) + '&categories=' + cats;
    return httpClient.getJson(url, { timeoutMs: 4000 }).then(function (arr) {
      if (!Array.isArray(arr)) return [];
      var out = [];
      for (var i = 0; i < arr.length; i++) {
        var it = arr[i];
        if (!it || !Array.isArray(it.segment) || it.segment.length < 2) continue;
        var s = Number(it.segment[0] || 0);
        var e = Number(it.segment[1] || 0);
        if (e <= s) continue;
        var cat = String(it.category || 'sponsor');
        out.push({
          startMs: Math.floor(s * 1000),
          endMs: Math.floor(e * 1000),
          kind: cat.toUpperCase(),
          label: 'Skip ' + cat.charAt(0).toUpperCase() + cat.slice(1)
        });
      }
      return out;
    }).catch(function () { return []; });
  }

  function fetchDislikeVotes(videoId) {
    return httpClient.getJson('https://returnyoutubedislikeapi.com/votes?videoId=' + encodeURIComponent(videoId), { timeoutMs: 4000 })
      .catch(function () { return null; });
  }

  function fetchDeArrowTitle(videoId) {
    if (store.getStateRaw().deArrowEnabled === false) return Promise.resolve(null);
    return httpClient.getJson('https://dearrow-thumb.ajay.app/api/v1/branding?videoID=' + encodeURIComponent(videoId), { timeoutMs: 3500 })
      .then(function (val) {
        var t = val && Array.isArray(val.titles) && val.titles[0] && val.titles[0].title;
        return t ? String(t).replace(/>/g, '') : null;
      }).catch(function () { return null; });
  }

  function resolveStream(videoId, mediaBaseUrl) {
    var cleanId = String(videoId || '').trim();
    if (!cleanId) return Promise.reject(new Error('YouTube videoId is empty'));

    var sbPromise = fetchSponsorBlock(cleanId);
    var rydPromise = fetchDislikeVotes(cleanId);
    var daPromise = fetchDeArrowTitle(cleanId);

    function tryClient(idx) {
      if (idx >= YT_STREAM_CLIENTS.length) {
        return Promise.reject(new Error('Unable to resolve playable YouTube stream for ' + cleanId));
      }
      var spec = YT_STREAM_CLIENTS[idx];
      return httpClient.postJson(INNERTUBE_BASE + '/player?prettyPrint=false', {
        videoId: cleanId,
        contentCheckOk: true,
        racyCheckOk: true,
        context: {
          client: {
            clientName: spec.clientName,
            clientVersion: spec.clientVersion,
            hl: 'en',
            gl: 'US'
          }
        }
      }, {
        headers: {
          'User-Agent': spec.userAgent,
          'X-YouTube-Client-Name': spec.clientIdHeader,
          'X-YouTube-Client-Version': spec.clientVersion
        },
        timeoutMs: 10000
      }).then(function (resp) {
        var root = resp.data;
        if (!root || !root.playabilityStatus || root.playabilityStatus.status !== 'OK') {
          return tryClient(idx + 1);
        }
        var details = root.videoDetails || {};
        var streaming = root.streamingData || {};
        var durationSec = parseInt(details.lengthSeconds || 0, 10) || 0;
        var isLive = Boolean(details.isLiveContent);

        var captions = [];
        var capTracks = root.captions && root.captions.playerCaptionsTracklistRenderer && root.captions.playerCaptionsTracklistRenderer.captionTracks;
        if (Array.isArray(capTracks)) {
          for (var i = 0; i < capTracks.length; i++) {
            var tr = capTracks[i];
            if (!tr || !tr.baseUrl) continue;
            var vttUrl = tr.baseUrl.indexOf('fmt=') !== -1 ? tr.baseUrl : (tr.baseUrl + '&fmt=vtt');
            captions.push({
              id: 'ytcap:' + i + ':' + (tr.languageCode || 'en'),
              url: vttUrl,
              vttUrl: mediaBaseUrl ? (mediaBaseUrl + '/sub.vtt?u=' + httpClient.b64UrlEncode(vttUrl)) : vttUrl,
              lang: String(tr.languageCode || 'en'),
              addonName: 'YouTube Captions'
            });
          }
        }

        var hlsUrl = streaming.hlsManifestUrl || '';
        var progFormats = Array.isArray(streaming.formats) ? streaming.formats.filter(function (f) { return f && f.url; }) : [];
        progFormats.sort(function (a, b) { return (b.height || 0) - (a.height || 0); });

        var dashReps = [];
        var maxHeight = 0;
        var adaptive = Array.isArray(streaming.adaptiveFormats) ? streaming.adaptiveFormats : [];
        for (var j = 0; j < adaptive.length; j++) {
          var fmt = adaptive[j];
          if (!fmt || !fmt.url || !fmt.initRange || !fmt.indexRange || !fmt.mimeType) continue;
          var parts = String(fmt.mimeType).split(';');
          var codecsMatch = (parts[1] || '').match(/codecs="([^"]+)"/i);
          if (fmt.height && fmt.height > maxHeight) maxHeight = fmt.height;
          dashReps.push({
            itag: fmt.itag || 0,
            mimeType: parts[0].trim(),
            codecs: codecsMatch ? codecsMatch[1] : '',
            baseUrl: fmt.url,
            bitrate: fmt.averageBitrate || fmt.bitrate || 128000,
            width: fmt.width || null,
            height: fmt.height || null,
            fps: fmt.fps || null,
            audioSampleRate: fmt.audioSampleRate ? parseInt(fmt.audioSampleRate, 10) : null,
            initRange: [Number(fmt.initRange.start || 0), Number(fmt.initRange.end || 0)],
            indexRange: [Number(fmt.indexRange.start || 0), Number(fmt.indexRange.end || 0)]
          });
        }

        var hasVideo = dashReps.some(function (r) { return r.mimeType.indexOf('video/') === 0; });
        var hasAudio = dashReps.some(function (r) { return r.mimeType.indexOf('audio/') === 0; });
        var dashMpdUrl = '';
        if (hasVideo && hasAudio && durationSec > 0 && dashManifestStore) {
          var dashKey = cleanId.replace(/[^a-zA-Z0-9_-]/g, '') + '-' + httpClient.uuidV4().slice(0, 6);
          dashManifestStore[dashKey] = buildDashMpdXml(durationSec, dashReps);
          dashMpdUrl = mediaBaseUrl + '/dash/' + dashKey + '.mpd';
        }

        var playUrl = '';
        var protocol = '';
        var qualityLabel = '';
        if (hlsUrl) {
          playUrl = hlsUrl;
          protocol = 'HLS';
          qualityLabel = isLive ? 'LIVE HLS' : ('Adaptive HLS (' + spec.name + ')');
        } else if (progFormats.length > 0) {
          playUrl = progFormats[0].url;
          protocol = 'PROGRESSIVE';
          qualityLabel = (progFormats[0].height || 720) + 'p MP4 (' + spec.name + ')';
        } else if (dashMpdUrl) {
          playUrl = dashMpdUrl;
          protocol = 'DASH';
          qualityLabel = (maxHeight || 1080) + 'p DASH (' + spec.name + ')';
        } else {
          return tryClient(idx + 1);
        }

        return Promise.all([sbPromise, rydPromise, daPromise]).then(function (extras) {
          var title = extras[2] || String(details.title || cleanId);
          return {
            playUrl: playUrl,
            protocol: protocol,
            dashMpdUrl: dashMpdUrl || undefined,
            progressiveUrl: progFormats[0] ? progFormats[0].url : undefined,
            title: title,
            originalTitle: String(details.title || cleanId),
            dearrowTitle: extras[2] || null,
            author: String(details.author || 'YouTube'),
            durationSec: durationSec,
            isLive: isLive,
            qualityLabel: qualityLabel,
            requestHeaders: { 'User-Agent': spec.userAgent },
            subtitles: captions,
            skipSegments: extras[0] || [],
            votes: extras[1] || null
          };
        });
      }).catch(function () {
        return tryClient(idx + 1);
      });
    }

    return tryClient(0);
  }

  function feed(params) {
    params = params || {};
    var tab = String(params.tab || 'home');
    var cont = params.continuation || null;
    if (tab === 'subscriptions') {
      if (store.getActiveYoutubeAccount()) {
        return browseFeed('FEsubscriptions', cont, true);
      }
      var follows = store.getActiveCreatorFollows('youtube');
      if (!follows.length) return Promise.resolve({ items: [], continuationToken: null });
      return Promise.all(follows.filter(function (f) { return f.channelId.indexOf('UC') === 0; }).map(function (f) {
        return fetchAtomRss(f.channelId);
      })).then(function (batches) {
        var flat = [];
        for (var i = 0; i < batches.length; i++) flat = flat.concat(batches[i]);
        return { items: flat, continuationToken: null };
      });
    }
    if (tab === 'history') return browseFeed('FEhistory', cont, true);
    if (tab === 'playlist') {
      var pid = String(params.playlistId || '');
      var bid = (pid.indexOf('VL') === 0 || pid.indexOf('FE') === 0) ? pid : ('VL' + pid);
      return browseFeed(bid, cont, true);
    }
    if (tab === 'search') {
      var payload = webContext(null);
      if (cont) payload.continuation = cont;
      else payload.query = String(params.query || '').trim();
      return postInnerTube('search', payload, false).then(parseInnerTubeFeedPage);
    }
    if (tab === 'suggestions') {
      var q = String(params.query || '').trim();
      if (!q) return Promise.resolve({ suggestions: [] });
      return httpClient.getText('https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&ds=yt&q=' + encodeURIComponent(q))
        .then(function (r) {
          var s = r.text || '';
          var a = s.indexOf('[');
          var b = s.lastIndexOf(']');
          if (a === -1 || b === -1) return { suggestions: [] };
          var parsed = JSON.parse(s.slice(a, b + 1));
          var list = Array.isArray(parsed[1]) ? parsed[1].map(function (x) { return x && x[0]; }).filter(Boolean) : [];
          return { suggestions: list };
        }).catch(function () { return { suggestions: [] }; });
    }
    if (tab === 'related') {
      var relPayload = webContext(null);
      relPayload.videoId = String(params.videoId || '');
      return postInnerTube('next', relPayload, false).then(parseInnerTubeFeedPage);
    }
    if (tab === 'channel') {
      var chPayload = webContext(null);
      chPayload.browseId = String(params.channelId || '');
      chPayload.params = 'EgZ2aWRlb3PyBgQKAjoA';
      return postInnerTube('browse', chPayload, true).then(function (root) {
        var page = parseInnerTubeFeedPage(root);
        var meta = root && root.metadata && root.metadata.channelMetadataRenderer;
        return {
          channel: {
            platform: 'youtube',
            channelId: params.channelId,
            name: (meta && meta.title) || params.channelId,
            avatarUrl: extractYtThumb(meta && meta.avatar),
            description: (meta && meta.description) || '',
            videos: page.items
          },
          items: page.items,
          continuationToken: page.continuationToken
        };
      });
    }
    return browseFeed('FEwhat_to_watch', cont, true);
  }

  return {
    startAuth: startAuth,
    pollAuth: pollAuth,
    feed: feed,
    resolveStream: resolveStream
  };
}

// ── Twitch ────────────────────────────────────────────────────────────────────

var TWITCH_GQL_URL = 'https://gql.twitch.tv/gql';
var TWITCH_WEB_CLIENT_ID = 'kimne78kx3ncx6brgo4mv6wki5h1ko';
var TWITCH_TV_CLIENT_ID = 'ue6666qo983tsx6so1t0vnawi233wa';

function createTwitchClient(store) {
  function refreshToken() {
    var prof = store.getTwitchProfile();
    if (!prof || !prof.refreshToken) return Promise.resolve(null);
    return httpClient.postForm('https://id.twitch.tv/oauth2/token', {
      client_id: TWITCH_TV_CLIENT_ID,
      grant_type: 'refresh_token',
      refresh_token: prof.refreshToken
    }).then(function (r) {
      if (!r.ok || !r.data || !r.data.access_token) return null;
      var updated = Object.assign({}, prof, {
        accessToken: r.data.access_token,
        refreshToken: r.data.refresh_token || prof.refreshToken,
        updatedAtMs: Date.now()
      });
      store.setTwitchProfile(updated);
      return updated.accessToken;
    }).catch(function () { return null; });
  }

  function postGql(query, useAuth) {
    var headers = { 'Client-ID': TWITCH_WEB_CLIENT_ID };
    var prof = useAuth ? store.getTwitchProfile() : null;
    if (prof && prof.accessToken) {
      headers = {
        'Client-ID': TWITCH_TV_CLIENT_ID,
        Authorization: 'OAuth ' + prof.accessToken
      };
    }
    return httpClient.postJson(TWITCH_GQL_URL, { query: query }, { headers: headers, timeoutMs: 12000 }).then(function (r) {
      if (r.status === 401 && useAuth) {
        return refreshToken().then(function (tok) {
          if (!tok) return r.data;
          return httpClient.postJson(TWITCH_GQL_URL, { query: query }, {
            headers: { 'Client-ID': TWITCH_TV_CLIENT_ID, Authorization: 'OAuth ' + tok }
          }).then(function (r2) { return r2.data; });
        });
      }
      return r.data;
    });
  }

  function parseStreamEdges(edges) {
    if (!Array.isArray(edges)) return [];
    var out = [];
    for (var i = 0; i < edges.length; i++) {
      var n = edges[i] && edges[i].node;
      var b = n && n.broadcaster;
      if (!n || !b || !b.login) continue;
      out.push({
        platform: 'twitch',
        videoId: 'live:' + b.login,
        title: String(n.title || b.login),
        channelName: String(b.displayName || b.login),
        channelId: String(b.login),
        channelAvatar: String(b.profileImageURL || ''),
        thumbnailUrl: String(n.previewImageURL || ''),
        durationText: 'LIVE',
        viewCountText: (n.viewersCount || 0) + ' viewers',
        isLive: true,
        categoryName: String((n.game && n.game.displayName) || '')
      });
    }
    return out;
  }

  function startAuth() {
    return httpClient.postForm('https://id.twitch.tv/oauth2/device', {
      client_id: TWITCH_TV_CLIENT_ID,
      scopes: 'user:read:follows'
    }).then(function (r) {
      if (!r.ok || !r.data) throw new Error('Twitch device auth failed (' + r.status + ')');
      return {
        platform: 'twitch',
        deviceCode: r.data.device_code || '',
        userCode: r.data.user_code || '',
        verificationUrl: r.data.verification_uri || 'https://www.twitch.tv/activate',
        expiresInSec: r.data.expires_in || 1800,
        intervalSec: r.data.interval || 5,
        clientId: TWITCH_TV_CLIENT_ID,
        clientSecret: ''
      };
    });
  }

  function pollAuth(session) {
    return httpClient.postForm('https://id.twitch.tv/oauth2/token', {
      client_id: TWITCH_TV_CLIENT_ID,
      scopes: 'user:read:follows',
      device_code: session.deviceCode,
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code'
    }).then(function (r) {
      var val = r.data || {};
      if (val.message === 'authorization_pending' || val.message === 'slow_down') {
        return { authenticated: false };
      }
      if (val.message) throw new Error('Twitch auth error: ' + val.message);
      if (!val.access_token) return { authenticated: false };

      return httpClient.getJson('https://id.twitch.tv/oauth2/validate', {
        headers: { Authorization: 'OAuth ' + val.access_token }
      }).then(function (v) {
        var profile = {
          userId: String((v && v.user_id) || ''),
          login: String((v && v.login) || 'twitch_user'),
          displayName: String((v && v.login) || 'twitch_user'),
          accessToken: val.access_token,
          refreshToken: val.refresh_token || '',
          updatedAtMs: Date.now()
        };
        store.setTwitchProfile(profile);
        return {
          authenticated: true,
          profile: { userId: profile.userId, login: profile.login, displayName: profile.displayName }
        };
      });
    });
  }

  function feed(params) {
    params = params || {};
    var tab = String(params.tab || 'top');
    var limit = Math.max(1, Math.min(60, parseInt(params.limit || 30, 10) || 30));

    if (tab === 'categories') {
      var qCat = 'query { games(first: ' + limit + ') { edges { node { id name displayName viewersCount boxArtURL(width: 285, height: 380) } } } }';
      return postGql(qCat, false).then(function (root) {
        var edges = (root && root.data && root.data.games && root.data.games.edges) || [];
        var categories = edges.map(function (e) {
          var n = e && e.node;
          if (!n || !n.name) return null;
          return {
            platform: 'twitch',
            id: String(n.id || n.name),
            name: String(n.name || n.displayName),
            boxArtUrl: String(n.boxArtURL || ''),
            viewersText: (n.viewersCount || 0) + ' watching'
          };
        }).filter(Boolean);
        return { categories: categories, items: [] };
      });
    }

    if (tab === 'category' && params.category) {
      var safeName = String(params.category).replace(/"/g, '\\"');
      var qGame = 'query { game(name: "' + safeName + '") { streams(first: ' + limit + ') { edges { node { id title viewersCount previewImageURL(width: 640, height: 360) broadcaster { id login displayName profileImageURL(width: 150) } game { displayName } } } } } }';
      return postGql(qGame, false).then(function (root) {
        return { items: parseStreamEdges(root && root.data && root.data.game && root.data.game.streams && root.data.game.streams.edges) };
      });
    }

    if (tab === 'followed') {
      if (store.getTwitchProfile()) {
        var qFol = 'query { currentUser { followedLiveUsers(first: 40) { edges { node { id login displayName profileImageURL(width: 150) stream { id title viewersCount previewImageURL(width: 640, height: 360) game { displayName } } } } } } }';
        return postGql(qFol, true).then(function (root) {
          var edges = (root && root.data && root.data.currentUser && root.data.currentUser.followedLiveUsers && root.data.currentUser.followedLiveUsers.edges) || [];
          var items = [];
          for (var i = 0; i < edges.length; i++) {
            var u = edges[i] && edges[i].node;
            var s = u && u.stream;
            if (!u || !s) continue;
            items.push({
              platform: 'twitch',
              videoId: 'live:' + u.login,
              title: String(s.title || u.login),
              channelName: String(u.displayName || u.login),
              channelId: String(u.login),
              channelAvatar: String(u.profileImageURL || ''),
              thumbnailUrl: String(s.previewImageURL || ''),
              durationText: 'LIVE',
              viewCountText: (s.viewersCount || 0) + ' viewers',
              isLive: true,
              categoryName: String((s.game && s.game.displayName) || '')
            });
          }
          return { items: items };
        });
      }
      return Promise.resolve({ items: [] });
    }

    if (tab === 'search' && params.query) {
      var safeQ = String(params.query).trim().replace(/"/g, '\\"');
      var qSearch = 'query { searchFor(userQuery: "' + safeQ + '", platform: "web") { channels { edges { item { id login displayName profileImageURL(width: 150) stream { id title viewersCount previewImageURL(width: 640, height: 360) game { displayName } } } } } videos { edges { item { id title lengthSeconds viewCount previewThumbnailURL(width: 640, height: 360) owner { login displayName } } } } } }';
      return postGql(qSearch, false).then(function (root) {
        var data = root && root.data && root.data.searchFor;
        var items = [];
        var chEdges = (data && data.channels && data.channels.edges) || [];
        for (var i = 0; i < chEdges.length; i++) {
          var it = chEdges[i] && chEdges[i].item;
          if (!it || !it.stream) continue;
          items.push({
            platform: 'twitch',
            videoId: 'live:' + it.login,
            title: String(it.stream.title || it.login),
            channelName: String(it.displayName || it.login),
            channelId: String(it.login),
            thumbnailUrl: String(it.stream.previewImageURL || ''),
            durationText: 'LIVE',
            viewCountText: (it.stream.viewersCount || 0) + ' viewers',
            isLive: true
          });
        }
        var vEdges = (data && data.videos && data.videos.edges) || [];
        for (var j = 0; j < vEdges.length; j++) {
          var vit = vEdges[j] && vEdges[j].item;
          if (!vit || !vit.id) continue;
          var secs = vit.lengthSeconds || 0;
          items.push({
            platform: 'twitch',
            videoId: 'vod:' + vit.id,
            title: String(vit.title || 'VOD'),
            channelName: String((vit.owner && vit.owner.displayName) || ''),
            channelId: String((vit.owner && vit.owner.login) || ''),
            thumbnailUrl: String(vit.previewThumbnailURL || ''),
            durationSec: secs,
            durationText: Math.floor(secs / 60) + ':' + ((secs % 60) < 10 ? '0' : '') + (secs % 60),
            isLive: false
          });
        }
        return { items: items };
      });
    }

    var qTop = 'query { streams(first: ' + limit + ') { edges { node { id title viewersCount previewImageURL(width: 640, height: 360) broadcaster { id login displayName profileImageURL(width: 150) } game { displayName } } } } }';
    return postGql(qTop, false).then(function (root) {
      return { items: parseStreamEdges(root && root.data && root.data.streams && root.data.streams.edges) };
    });
  }

  function resolveStream(videoOrLogin, mediaBaseUrl) {
    var raw = String(videoOrLogin || '').trim();
    var isVod = raw.indexOf('vod:') === 0;
    var target = raw.replace(/^live:/, '').replace(/^vod:/, '').trim();
    var playerTypes = ['embed', 'thunderdome', 'site'];

    function tryIdx(i) {
      if (i >= playerTypes.length) {
        return Promise.reject(new Error('Failed to obtain Twitch PlaybackAccessToken for ' + target));
      }
      var playerType = playerTypes[i];
      var gqlBody = {
        operationName: 'PlaybackAccessToken',
        extensions: {
          persistedQuery: {
            version: 1,
            sha256Hash: '0828119ded1c13477966434e15800ff57ddacf13ba1911c129dc2200705b0712'
          }
        },
        variables: {
          isLive: !isVod,
          login: isVod ? '' : target,
          isVod: isVod,
          vodID: isVod ? target : '',
          playerType: playerType
        }
      };

      return httpClient.postJson(TWITCH_GQL_URL, gqlBody, {
        headers: { 'Client-ID': TWITCH_WEB_CLIENT_ID },
        timeoutMs: 10000
      }).then(function (r) {
        var root = r.data || {};
        var tokObj = isVod
          ? (root.data && root.data.videoPlaybackAccessToken)
          : (root.data && root.data.streamPlaybackAccessToken);
        if (!tokObj || !tokObj.signature || !tokObj.value) return tryIdx(i + 1);

        var usherUrl = isVod
          ? ('https://usher.ttvnw.net/vod/' + encodeURIComponent(target) + '.m3u8?allow_source=true&allow_audio_only=true&player=twitchweb&sig=' + encodeURIComponent(tokObj.signature) + '&token=' + encodeURIComponent(tokObj.value))
          : ('https://usher.ttvnw.net/api/channel/hls/' + encodeURIComponent(target) + '.m3u8?allow_source=true&allow_audio_only=true&fast_bread=true&player=twitchweb&sig=' + encodeURIComponent(tokObj.signature) + '&token=' + encodeURIComponent(tokObj.value));

        var proxiedUrl = mediaBaseUrl + '/proxy/hls.m3u8?u=' + httpClient.b64UrlEncode(usherUrl);
        return {
          playUrl: proxiedUrl,
          rawUrl: usherUrl,
          protocol: 'HLS',
          title: target,
          author: target,
          durationSec: 0,
          isLive: !isVod,
          qualityLabel: 'Ad-Free Source HLS (' + playerType + ')',
          requestHeaders: {},
          subtitles: [],
          skipSegments: []
        };
      }).catch(function () {
        return tryIdx(i + 1);
      });
    }

    return tryIdx(0);
  }

  return {
    startAuth: startAuth,
    pollAuth: pollAuth,
    feed: feed,
    resolveStream: resolveStream
  };
}

// ── Odysee ────────────────────────────────────────────────────────────────────

var ODYSEE_RPC_URL = 'https://api.na-backend.odysee.com/api/v1/proxy';

function createOdyseeClient() {
  function claimSearch(params) {
    return httpClient.postJson(ODYSEE_RPC_URL, {
      jsonrpc: '2.0',
      id: 1,
      method: 'claim_search',
      params: params
    }, { timeoutMs: 12000 }).then(function (r) {
      var list = (r.data && r.data.result && r.data.result.items) || [];
      return list.map(function (item) {
        if (!item || !item.claim_id) return null;
        var slug = item.name || 'video';
        var val = item.value || {};
        var dur = (val.video && val.video.duration) || 0;
        var signing = item.signing_channel || {};
        var chName = (signing.value && signing.value.title) || signing.name || 'Odysee';
        return {
          platform: 'odysee',
          videoId: slug + '#' + item.claim_id,
          title: String(val.title || slug),
          channelName: String(chName),
          channelId: String(signing.claim_id || ''),
          thumbnailUrl: String((val.thumbnail && val.thumbnail.url) || ''),
          durationSec: dur,
          durationText: dur > 0 ? Math.floor(dur / 60) + ':' + ((dur % 60) < 10 ? '0' : '') + (dur % 60) : '',
          isLive: false
        };
      }).filter(Boolean);
    });
  }

  function feed(params) {
    params = params || {};
    var tab = String(params.tab || 'featured');
    if (tab === 'live') {
      return httpClient.getJson('https://api.odysee.live/livestream/all', { timeoutMs: 12000 }).then(function (resp) {
        var arr = Array.isArray(resp && resp.data) ? resp.data : [];
        var claimIds = [];
        var liveMeta = {};
        for (var i = 0; i < arr.length && claimIds.length < 25; i++) {
          var it = arr[i];
          var cid = it && it.Live && it.ActiveClaim && it.ActiveClaim.ClaimID;
          if (!cid) continue;
          claimIds.push(cid);
          liveMeta[cid] = { viewers: it.ViewerCount || 0, thumb: it.ThumbnailURL || '' };
        }
        if (claimIds.length === 0) return { items: [] };
        return claimSearch({ claim_ids: claimIds, page_size: 25, no_totals: true }).then(function (items) {
          for (var j = 0; j < items.length; j++) {
            items[j].isLive = true;
            items[j].durationText = 'LIVE';
            var rawCid = items[j].videoId.split('#')[1] || '';
            if (liveMeta[rawCid]) {
              items[j].viewCountText = liveMeta[rawCid].viewers + ' watching';
              if (!items[j].thumbnailUrl && liveMeta[rawCid].thumb) items[j].thumbnailUrl = liveMeta[rawCid].thumb;
            }
          }
          return { items: items };
        });
      });
    }
    if (tab === 'search' && params.query) {
      return httpClient.getJson('https://lighthouse.odysee.tv/search?s=' + encodeURIComponent(String(params.query).trim()) + '&size=25&from=0&claimType=file&mediaType=video&nsfw=false')
        .then(function (hits) {
          var ids = Array.isArray(hits) ? hits.map(function (h) { return h && h.claimId; }).filter(Boolean) : [];
          if (!ids.length) return { items: [] };
          return claimSearch({ claim_ids: ids, page_size: 25, no_totals: true }).then(function (items) {
            return { items: items };
          });
        });
    }
    return claimSearch({
      page_size: 30,
      page: Math.max(1, parseInt(params.page || 1, 10) || 1),
      claim_type: ['stream'],
      stream_types: ['video'],
      no_totals: true,
      not_tags: ['porn', 'nsfw', 'xxx', 'mature'],
      order_by: ['trending_group', 'trending_mixed']
    }).then(function (items) { return { items: items }; });
  }

  function resolveStream(claimSpec) {
    var parts = String(claimSpec || '').split('#');
    var nameOpt = parts.length > 1 ? parts[0] : null;
    var claimId = parts.length > 1 ? parts[1] : parts[0];
    var uri = nameOpt ? ('lbry://' + nameOpt + '#' + claimId) : claimId;

    return httpClient.postJson(ODYSEE_RPC_URL, {
      jsonrpc: '2.0',
      id: 1,
      method: 'get',
      params: { uri: uri }
    }).then(function (r) {
      var streamingUrl = r.data && r.data.result && r.data.result.streaming_url;
      var cdnHls = claimId ? ('https://player.odycdn.com/v6/streams/' + claimId + '/' + (nameOpt || 'stream') + '/master.m3u8') : '';
      var playUrl = streamingUrl || cdnHls;
      if (!playUrl) throw new Error('Odysee stream URL unavailable');
      return {
        playUrl: playUrl,
        protocol: playUrl.indexOf('.m3u8') !== -1 ? 'HLS' : 'PROGRESSIVE',
        title: nameOpt || claimId,
        author: 'Odysee',
        durationSec: 0,
        isLive: false,
        qualityLabel: 'Odysee Direct',
        requestHeaders: {},
        subtitles: [],
        skipSegments: []
      };
    });
  }

  return { feed: feed, resolveStream: resolveStream };
}

// ── Rumble ────────────────────────────────────────────────────────────────────

function createRumbleClient() {
  function scrapeCards(url) {
    return httpClient.getText(url, { desktopUa: true, timeoutMs: 12000 }).then(function (r) {
      var html = r.text || '';
      var re = /href="(\/(v[a-z0-9]+-[^"?#]+\.html))"[^>]*>([^<]{3,160})</gi;
      var m;
      var seen = {};
      var out = [];
      while ((m = re.exec(html)) !== null) {
        var relPath = m[1].trim();
        var title = httpClient.decodeXmlEntities(m[3]).trim();
        if (!title || seen[relPath]) continue;
        seen[relPath] = true;
        out.push({
          platform: 'rumble',
          videoId: relPath,
          title: title,
          channelName: 'Rumble',
          isLive: url.indexOf('/live') !== -1
        });
      }
      return { items: out };
    });
  }

  function feed(params) {
    params = params || {};
    var tab = String(params.tab || 'featured');
    if (tab === 'live') return scrapeCards('https://rumble.com/live');
    if (tab === 'search' && params.query) {
      return scrapeCards('https://rumble.com/search/video?q=' + encodeURIComponent(String(params.query).trim()));
    }
    return scrapeCards('https://rumble.com/editor-picks');
  }

  function resolveStream(pathOrEmbed) {
    var s = String(pathOrEmbed || '').trim();
    var embedPromise;
    if (s.charAt(0) === 'v' && s.indexOf('/') === -1) {
      embedPromise = Promise.resolve(s);
    } else {
      var fullPage = /^https?:\/\//i.test(s) ? s : ('https://rumble.com/' + s.replace(/^\/+/, ''));
      embedPromise = httpClient.getJson(
        'https://wn0.rumble.com/api/Media/oembed.json?url=' + encodeURIComponent(fullPage),
        { desktopUa: true }
      ).then(function (oe) {
        var m = String((oe && oe.html) || '').match(/\/embed\/(v[a-z0-9]+)/i);
        if (!m) throw new Error('Rumble oembed missing embed ID');
        return m[1];
      });
    }

    return embedPromise.then(function (embedId) {
      return httpClient.getJson(
        'https://wn0.rumble.com/embedJS/u3/?request=video&ver=2&v=' + encodeURIComponent(embedId),
        { desktopUa: true }
      ).then(function (root) {
        var bestUrl = '';
        var bestH = 0;
        var protocol = 'HLS';
        if (root && root.ua) {
          if (root.ua.hls && root.ua.hls.auto && root.ua.hls.auto.url) {
            bestUrl = root.ua.hls.auto.url;
            bestH = 1080;
          } else if (root.ua.mp4 && typeof root.ua.mp4 === 'object') {
            var keys = Object.keys(root.ua.mp4);
            for (var i = 0; i < keys.length; i++) {
              var h = parseInt(keys[i], 10) || 360;
              var entry = root.ua.mp4[keys[i]];
              if (entry && entry.url && h >= bestH) {
                bestH = h;
                bestUrl = entry.url;
                protocol = 'PROGRESSIVE';
              }
            }
          }
        }
        if (!bestUrl && root && root.u && root.u.hls && root.u.hls.url) {
          bestUrl = root.u.hls.url;
          protocol = 'HLS';
        }
        if (!bestUrl) throw new Error('Rumble embedJS returned no media URLs');
        return {
          playUrl: bestUrl,
          protocol: protocol,
          title: String((root && root.title) || embedId),
          author: String((root && root.author && root.author.name) || 'Rumble'),
          durationSec: Number((root && root.duration) || 0),
          isLive: Number((root && root.live) || 0) > 0,
          qualityLabel: 'Rumble ' + Math.max(720, bestH) + 'p (' + protocol + ')',
          requestHeaders: {},
          subtitles: [],
          skipSegments: []
        };
      });
    });
  }

  return { feed: feed, resolveStream: resolveStream };
}

// ─────────────────────────────────────────────────────────────────────────────
// 6. Binge Next-Episode & Recommendations Engine
// ─────────────────────────────────────────────────────────────────────────────

function findNextEpisode(episodes, currentVideoId) {
  if (!Array.isArray(episodes) || episodes.length === 0) return null;
  var sorted = episodes.slice().sort(function (a, b) {
    return a.season !== b.season ? a.season - b.season : a.episode - b.episode;
  });
  var pos = -1;
  for (var i = 0; i < sorted.length; i++) {
    if (sorted[i].id === currentVideoId) {
      pos = i;
      break;
    }
  }
  if (pos === -1) return null;
  var curSeason = sorted[pos].season;
  for (var j = pos + 1; j < sorted.length; j++) {
    if (curSeason === 0 || sorted[j].season > 0) return sorted[j];
  }
  return null;
}

function rankCatalogRecommendations(seed, pool, limit) {
  var seedGenres = {};
  if (seed && Array.isArray(seed.genres)) {
    for (var i = 0; i < seed.genres.length; i++) {
      var g = String(seed.genres[i] || '').trim().toLowerCase();
      if (g) seedGenres[g] = true;
    }
  }
  var seen = {};
  if (seed && seed.id) seen[seed.id] = true;
  var scored = [];
  for (var j = 0; j < pool.length; j++) {
    var item = pool[j];
    if (!item || !item.id || seen[item.id]) continue;
    seen[item.id] = true;
    var overlap = 0;
    if (Array.isArray(item.genres)) {
      for (var k = 0; k < item.genres.length; k++) {
        if (seedGenres[String(item.genres[k] || '').trim().toLowerCase()]) overlap++;
      }
    }
    var rating = parseFloat(item.imdbRating);
    if (isNaN(rating)) rating = 6.0;
    scored.push({ score: overlap * 12 + rating, item: item });
  }
  scored.sort(function (a, b) { return b.score - a.score; });
  return scored.slice(0, limit || 14).map(function (s) { return s.item; });
}

module.exports = {
  parseResolutionScore: parseResolutionScore,
  parseSeedersFromText: parseSeedersFromText,
  parseHumanSizeBytes: parseHumanSizeBytes,
  formatHumanBytes: formatHumanBytes,
  extractCodecTags: extractCodecTags,
  extractAudioTags: extractAudioTags,
  enrichStreamCandidate: enrichStreamCandidate,
  dedupeAndRankStreams: dedupeAndRankStreams,
  scoreBingeSimilarity: scoreBingeSimilarity,
  parseStremioImdbVideoId: parseStremioImdbVideoId,
  matchesEpisodeReleaseName: matchesEpisodeReleaseName,
  parseGenericTorrentRssXml: parseGenericTorrentRssXml,
  parseManifestValue: parseManifestValue,
  splitPipeHeaders: splitPipeHeaders,
  createStremioClient: createStremioClient,
  createSwarmClient: createSwarmClient,
  createJellyfinClient: createJellyfinClient,
  createYouTubeClient: createYouTubeClient,
  createTwitchClient: createTwitchClient,
  createOdyseeClient: createOdyseeClient,
  createRumbleClient: createRumbleClient,
  findNextEpisode: findNextEpisode,
  rankCatalogRecommendations: rankCatalogRecommendations
};
