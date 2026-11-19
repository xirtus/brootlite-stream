'use strict';

var urlMod = require('url');
var httpClient = require('./http_client');

var roomHintCache = {};

function unescapeJsString(raw) {
  if (!raw) return '';
  return String(raw)
    .replace(/\\u002[fF]/g, '/')
    .replace(/\\u002[dD]/g, '-')
    .replace(/\\u0026/g, '&')
    .replace(/\\u003[dD]/g, '=')
    .replace(/\\u003[aA]/g, ':')
    .replace(/\\u003[fF]/g, '?')
    .replace(/\\\//g, '/')
    .replace(/&amp;/g, '&')
    .trim();
}

function encodeBaseN(num, radix) {
  var chars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  if (radix <= 36) {
    return num.toString(radix);
  }
  var prefix = num < radix ? '' : encodeBaseN(Math.floor(num / radix), radix);
  var rem = num % radix;
  var ch = rem > 35 ? String.fromCharCode(rem + 29) : chars.charAt(rem);
  return prefix + ch;
}

function unpackDeanEdwards(source) {
  if (!source || source.indexOf('eval(function(p,a,c,k,e,') === -1) {
    return source || '';
  }

  var current = String(source);
  for (var pass = 0; pass < 4; pass++) {
    var re = /eval\(function\(p,a,c,k,e,[dr]\)\{[\s\S]*?return p\}\('((?:\\.|[^'\\])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'\\])*)'\.split\(['"]\|['"]\)/g;
    var changed = false;
    current = current.replace(re, function (_, pRaw, aStr, cStr, kRaw) {
      try {
        var p = pRaw.replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/\\\\/g, '\\');
        var a = parseInt(aStr, 10);
        var c = parseInt(cStr, 10);
        var k = kRaw.replace(/\\'/g, "'").split('|');
        if (isNaN(a) || isNaN(c) || !k.length) return _;

        var dict = {};
        while (c--) {
          var key = encodeBaseN(c, a);
          dict[key] = k[c] || key;
        }
        var unpacked = p.replace(/\b\w+\b/g, function (word) {
          return Object.prototype.hasOwnProperty.call(dict, word) ? dict[word] : word;
        });
        changed = true;
        return unpacked;
      } catch (e) {
        return _;
      }
    });
    if (!changed) break;
  }
  return current;
}

function extractAtobStrings(corpus) {
  var out = [];
  var re = /atob\(\s*['"]([A-Za-z0-9+/=]{12,})['"]\s*\)/g;
  var m;
  while ((m = re.exec(corpus)) !== null) {
    try {
      var decoded = Buffer.from(m[1], 'base64').toString('utf8');
      if (decoded && /https?:\/\/|\.m3u8|\.mp4/i.test(decoded)) {
        out.push(decoded);
      }
    } catch (e) {}
  }
  return out.join('\n');
}

function parseQualityRank(label, url) {
  var hay = (String(label || '') + ' ' + String(url || '')).toLowerCase();
  if (hay.indexOf('2160') !== -1 || hay.indexOf('4k') !== -1 || hay.indexOf('uhd') !== -1) return 2160;
  if (hay.indexOf('1440') !== -1 || hay.indexOf('2k') !== -1) return 1440;
  if (hay.indexOf('1080') !== -1 || hay.indexOf('fhd') !== -1) return 1080;
  if (hay.indexOf('720') !== -1 || hay.indexOf('hd') !== -1) return 720;
  if (hay.indexOf('480') !== -1) return 480;
  if (hay.indexOf('360') !== -1) return 360;
  if (hay.indexOf('240') !== -1) return 240;
  if (/\.m3u8(\?|$)/i.test(url)) return 1800;
  return 500;
}

function isLikelyPreviewUrl(url) {
  var low = String(url || '').toLowerCase();
  return (
    low.indexOf('preview') !== -1 ||
    low.indexOf('thumb') !== -1 ||
    low.indexOf('trailer') !== -1 ||
    low.indexOf('sprite') !== -1 ||
    low.indexOf('_t.') !== -1
  );
}

function collectVideoCandidatesFromCorpus(corpus, baseUrl) {
  var candidates = [];
  var seen = {};

  function addCandidate(rawUrl, label, priorityBoost) {
    var clean = unescapeJsString(rawUrl);
    if (!clean || clean.length < 8) return;
    if (clean.indexOf('//') === 0) clean = 'https:' + clean;
    if (!/^https?:\/\//i.test(clean)) {
      clean = httpClient.resolveUrl(baseUrl, clean);
    }
    if (!/^https?:\/\//i.test(clean)) return;
    if (/\.js(\?|$)|\.css(\?|$)|\.png(\?|$)|\.jpe?g(\?|$)|\.gif(\?|$)|\.webp(\?|$)|\.svg(\?|$)/i.test(clean)) {
      return;
    }
    if (seen[clean]) return;
    seen[clean] = true;

    var isHls = /\.m3u8(\?|#|$)/i.test(clean) || (label && String(label).toLowerCase().indexOf('hls') !== -1);
    var rank = parseQualityRank(label, clean) + (priorityBoost || 0);
    if (isLikelyPreviewUrl(clean)) rank -= 2000;

    candidates.push({
      url: clean,
      label: label ? String(label) : (isHls ? 'HLS Auto' : (parseQualityRank('', clean) >= 720 ? parseQualityRank('', clean) + 'p' : 'Direct MP4')),
      protocol: isHls ? 'HLS' : 'PROGRESSIVE',
      rank: rank
    });
  }

  var m;
  // 1. Named manifest / HLS properties
  var hlsPropRe = /(?:hlsManifestUrl|hls_source|hlsUrl|manifestUrl|streamUrl|playlistUrl)\s*['"]?\s*[:=]\s*['"]([^'"]+\.m3u8[^'"]*)['"]/gi;
  while ((m = hlsPropRe.exec(corpus)) !== null) {
    addCandidate(m[1], 'HLS Live/Adaptive', 400);
  }

  // 2. JSON/JS sources arrays with file/src/url + label/res/quality
  var srcObjRe = /\{[^{}]{0,260}?(?:file|src|url)\s*[:=]\s*['"]([^'"]+(?:\.m3u8|\.mp4|\.webm)[^'"]*)['"][^{}]{0,260}?\}/gi;
  while ((m = srcObjRe.exec(corpus)) !== null) {
    var block = m[0];
    var lblMatch = block.match(/(?:label|res|quality|title)\s*[:=]\s*['"]([^'"]{1,32})['"]/i);
    addCandidate(m[1], lblMatch ? lblMatch[1] : '', 300);
  }

  // 3. Generic file/src assignments
  var fileAssignRe = /(?:file|src|source|video_url|videoUrl|mp4Url|hdUrl|sdUrl)\s*['"]?\s*[:=]\s*['"]((?:https?:|\/\/)[^'"]+(?:\.m3u8|\.mp4|\.webm)[^'"]*)['"]/gi;
  while ((m = fileAssignRe.exec(corpus)) !== null) {
    addCandidate(m[1], '', 250);
  }

  // 4. HTML <source src="..."> and <video src="...">
  var htmlTagRe = /<(?:source|video)[^>]+src\s*=\s*['"]([^'"]+)['"][^>]*>/gi;
  while ((m = htmlTagRe.exec(corpus)) !== null) {
    var tag = m[0];
    var qm = tag.match(/(?:label|data-quality|data-res|title)\s*=\s*['"]([^'"]+)['"]/i);
    addCandidate(m[1], qm ? qm[1] : '', 220);
  }

  // 5. Direct download / quality anchor links (<a href="...mp4...">1080p</a>)
  var dlRe = /<a[^>]+href\s*=\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"][^>]*>([^<]{0,60})/gi;
  while ((m = dlRe.exec(corpus)) !== null) {
    addCandidate(m[1], (m[2] || '').trim(), 150);
  }

  // 6. Standalone m3u8 / mp4 URLs anywhere in unpacked JS/HTML
  var bareRe = /https?:\/\/[^\s"'<>\\`]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\`]*)?/gi;
  while ((m = bareRe.exec(corpus)) !== null) {
    addCandidate(m[0], '', 0);
  }

  candidates.sort(function (a, b) {
    return b.rank - a.rank;
  });
  return candidates;
}

function resolveEmbedPlayerXhr(html, baseUrl, headers) {
  var vidMatch = html.match(/\bvid\s*[:=]\s*['"]([a-zA-Z0-9_-]{4,32})['"]/);
  var hashMatch = html.match(/\bhash\s*[:=]\s*['"]([a-fA-F0-9]{16,64})['"]/);
  if (!vidMatch || !hashMatch) return Promise.resolve([]);

  var parsed;
  try {
    parsed = httpClient.parseUrl(baseUrl);
  } catch (e) {
    return Promise.resolve([]);
  }
  if (!parsed || !parsed.host) return Promise.resolve([]);
  var origin = (parsed.protocol || 'https:') + '//' + parsed.host;
  var xhrUrl = origin + '/xhr/video/' + encodeURIComponent(vidMatch[1]) + '/' + encodeURIComponent(hashMatch[1]);
  var reqHeaders = Object.assign({
    Referer: baseUrl,
    'X-Requested-With': 'XMLHttpRequest'
  }, headers || {});

  return httpClient.getJson(xhrUrl, {
    headers: reqHeaders,
    desktopUa: true,
    timeoutMs: 10000
  }).then(function (json) {
    var out = [];
    if (json && json.sources && typeof json.sources === 'object') {
      var kinds = Object.keys(json.sources);
      for (var i = 0; i < kinds.length; i++) {
        var bucket = json.sources[kinds[i]];
        if (bucket && typeof bucket === 'object') {
          var qKeys = Object.keys(bucket);
          for (var j = 0; j < qKeys.length; j++) {
            var qInfo = bucket[qKeys[j]];
            if (qInfo && qInfo.src) {
              out.push({
                url: qInfo.src,
                label: qInfo.label || qKeys[j],
                protocol: /\.m3u8/i.test(qInfo.src) ? 'HLS' : 'PROGRESSIVE',
                rank: parseQualityRank(qInfo.label || qKeys[j], qInfo.src) + 500
              });
            }
          }
        }
      }
    }
    if (out.length === 0 && json) {
      out = collectVideoCandidatesFromCorpus(JSON.stringify(json), baseUrl);
    }
    return out;
  }).catch(function () {
    return [];
  });
}

function resolveWebVideoUrl(targetUrl, customHeaders, depth) {
  depth = depth || 0;
  var cleanTarget = String(targetUrl || '').trim();
  if (!cleanTarget) return Promise.reject(new Error('Empty target URL'));
  var headers = Object.assign({}, customHeaders || {});

  if (/\.(mp4|m3u8|webm|mkv|mpd)(\?|$)/i.test(cleanTarget)) {
    var proto = /\.m3u8/i.test(cleanTarget) ? 'HLS' : (/\.mpd/i.test(cleanTarget) ? 'DASH' : 'PROGRESSIVE');
    return Promise.resolve({
      streamUrl: cleanTarget,
      protocol: proto,
      headers: headers,
      needsProxy: Object.keys(headers).length > 0 || proto === 'HLS',
      qualityChoices: [{ label: proto, url: cleanTarget, protocol: proto }]
    });
  }

  return httpClient.getText(cleanTarget, {
    headers: headers,
    desktopUa: true,
    timeoutMs: 15000
  }).then(function (resp) {
    var finalUrl = resp.url || cleanTarget;
    var html = resp.text || '';
    var unpacked = unpackDeanEdwards(html);
    var decodedAtob = extractAtobStrings(html + '\n' + unpacked);
    var combined = html + '\n' + unpacked + '\n' + decodedAtob;

    var parsedFinal;
    var outHeaders = Object.assign({}, headers);
    try {
      parsedFinal = httpClient.parseUrl(finalUrl);
      if (parsedFinal && parsedFinal.host && !outHeaders.Referer) {
        outHeaders.Referer = (parsedFinal.protocol || 'https:') + '//' + parsedFinal.host + '/';
      }
    } catch (e) {}

    return resolveEmbedPlayerXhr(combined, finalUrl, outHeaders).then(function (xhrChoices) {
      var candidates = xhrChoices.concat(collectVideoCandidatesFromCorpus(combined, finalUrl));
      if (candidates.length > 0) {
        candidates.sort(function (a, b) { return b.rank - a.rank; });
        var best = candidates[0];
        return {
          streamUrl: best.url,
          protocol: best.protocol,
          headers: outHeaders,
          needsProxy: Object.keys(outHeaders).length > 0 || best.protocol === 'HLS',
          qualityChoices: candidates.slice(0, 8).map(function (c) {
            return { label: c.label, url: c.url, protocol: c.protocol };
          })
        };
      }

      if (depth < 1) {
        var iframeMatch = combined.match(/<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]/i);
        if (iframeMatch && iframeMatch[1]) {
          var iframeUrl = httpClient.resolveUrl(finalUrl, iframeMatch[1]);
          if (iframeUrl && iframeUrl !== finalUrl && /^https?:\/\//i.test(iframeUrl)) {
            return resolveWebVideoUrl(iframeUrl, outHeaders, depth + 1);
          }
        }
      }

      throw new Error('Could not extract playable video stream from ' + finalUrl);
    });
  });
}

// ─────────────────────────────────────────────────────────────────────────────
// Direct Sources Engine (Schema-Driven, Zero Hardcoded Private Domains)
// ─────────────────────────────────────────────────────────────────────────────

function formatSeconds(sec) {
  var n = parseInt(sec, 10);
  if (isNaN(n) || n <= 0) return '';
  var h = Math.floor(n / 3600);
  var m = Math.floor((n % 3600) / 60);
  var s = n % 60;
  var mm = m < 10 && h > 0 ? '0' + m : String(m);
  var ss = s < 10 ? '0' + s : String(s);
  return h > 0 ? h + ':' + mm + ':' + ss : mm + ':' + ss;
}

function catalogInternetArchive(source, opts) {
  var col = source.collection || 'feature_films';
  var qParts = ['(collection:' + col + ' OR subject:' + col + ') AND mediatype:movies'];
  var userQ = String((opts && opts.query) || '').trim();
  if (userQ) {
    qParts.push('(' + userQ.replace(/[()"]/g, ' ') + ')');
  }
  var page = Math.max(1, parseInt((opts && opts.page) || 1, 10) || 1);
  var apiUrl =
    'https://archive.org/advancedsearch.php?q=' + encodeURIComponent(qParts.join(' AND ')) +
    '&fl[]=identifier&fl[]=title&fl[]=description&fl[]=year&fl[]=downloads&fl[]=avg_rating&fl[]=runtime&fl[]=format' +
    '&sort[]=downloads+desc&rows=36&page=' + page + '&output=json';

  return httpClient.getJson(apiUrl, { timeoutMs: 15000 }).then(function (root) {
    var docs = (root && root.response && Array.isArray(root.response.docs)) ? root.response.docs : [];
    var items = [];
    for (var i = 0; i < docs.length; i++) {
      var d = docs[i];
      if (!d || !d.identifier) continue;
      if (Array.isArray(d.format) && d.format.length > 0) {
        var hasVideoFmt = d.format.some(function (fmt) {
          var fLow = String(fmt || '').toLowerCase();
          return (
            fLow.indexOf('mpeg4') !== -1 ||
            fLow.indexOf('h.264') !== -1 ||
            fLow.indexOf('mp4') !== -1 ||
            fLow.indexOf('matroska') !== -1 ||
            fLow.indexOf('webm') !== -1 ||
            fLow.indexOf('ogg video') !== -1
          );
        });
        if (!hasVideoFmt) continue;
      }
      var id = String(d.identifier);
      var title = Array.isArray(d.title) ? d.title[0] : (d.title || id);
      var desc = Array.isArray(d.description) ? d.description[0] : (d.description || '');
      items.push({
        id: 'src:' + source.id + ':' + id,
        rawId: id,
        sourceId: source.id,
        sourceName: source.name,
        title: String(title),
        name: String(title),
        description: String(desc).replace(/<[^>]+>/g, ' ').trim().slice(0, 400),
        poster: 'https://archive.org/services/img/' + encodeURIComponent(id),
        thumbnail: 'https://archive.org/services/img/' + encodeURIComponent(id),
        releaseInfo: d.year ? String(d.year) : '',
        imdbRating: d.avg_rating ? String(d.avg_rating) : '',
        runtime: d.runtime ? String(d.runtime) : '',
        viewsText: d.downloads ? Number(d.downloads).toLocaleString() + ' views' : '',
        isLive: false,
        adult: Boolean(source.adult),
        type: 'movie'
      });
    }
    return {
      source: { id: source.id, name: source.name, kind: source.kind, adult: Boolean(source.adult) },
      items: items,
      page: page,
      hasMore: docs.length >= 30
    };
  });
}

function resolveInternetArchive(source, rawId) {
  var cleanId = String(rawId || '').trim();
  var metaUrl = 'https://archive.org/metadata/' + encodeURIComponent(cleanId);
  return httpClient.getJson(metaUrl, { timeoutMs: 15000 }).then(function (meta) {
    var files = Array.isArray(meta && meta.files) ? meta.files : [];
    var videoChoices = [];
    var subtitles = [];
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (!f || !f.name) continue;
      var low = String(f.name).toLowerCase();
      var downloadUrl = 'https://archive.org/download/' + encodeURIComponent(cleanId) + '/' + encodeURIComponent(f.name).replace(/%2F/gi, '/');
      if (/\.(mp4|m4v|webm|mkv|ogv)$/.test(low) && low.indexOf('sample') === -1) {
        var size = parseInt(f.size || 0, 10) || 0;
        var h = parseInt(f.height || 0, 10) || 0;
        var isMp4 = /\.mp4$/.test(low);
        var score = (isMp4 ? 100000 : 0) + (h * 100) + Math.min(50000, Math.floor(size / (1024 * 1024)));
        videoChoices.push({
          label: (h > 0 ? h + 'p ' : '') + (isMp4 ? 'MP4' : low.split('.').pop().toUpperCase()),
          url: downloadUrl,
          protocol: 'PROGRESSIVE',
          score: score
        });
      } else if (/\.(vtt|srt)$/.test(low)) {
        subtitles.push({
          id: 'ia-sub-' + i,
          url: downloadUrl,
          lang: 'en',
          addonName: 'Internet Archive'
        });
      }
    }
    videoChoices.sort(function (a, b) { return b.score - a.score; });
    if (videoChoices.length === 0) {
      throw new Error('No playable video file found in Archive.org item ' + cleanId);
    }
    var md = (meta && meta.metadata) || {};
    return {
      streamUrl: videoChoices[0].url,
      protocol: 'PROGRESSIVE',
      title: Array.isArray(md.title) ? md.title[0] : (md.title || cleanId),
      author: Array.isArray(md.creator) ? md.creator[0] : (md.creator || 'Internet Archive'),
      headers: {},
      needsProxy: false,
      qualityChoices: videoChoices.slice(0, 6).map(function (c) {
        return { label: c.label, url: c.url, protocol: c.protocol };
      }),
      subtitles: subtitles
    };
  });
}

function catalogV2VideoJsonApi(source, opts) {
  var baseApi = String(source.apiUrl || '').trim();
  if (!baseApi) return Promise.reject(new Error('Direct source missing apiUrl'));
  var page = Math.max(1, parseInt((opts && opts.page) || 1, 10) || 1);
  var query = String((opts && opts.query) || '').trim();
  var order = String((opts && opts.order) || 'top-weekly').trim();
  var sep = baseApi.indexOf('?') !== -1 ? '&' : '?';
  var url =
    baseApi + sep +
    'query=' + encodeURIComponent(query) +
    '&per_page=30&page=' + page +
    '&thumbsize=big&order=' + encodeURIComponent(order) + '&format=json';

  return httpClient.getJson(url, { desktopUa: true, timeoutMs: 15000 }).then(function (root) {
    var list = Array.isArray(root && root.videos) ? root.videos : [];
    var items = [];
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (!v || !v.id) continue;
      var id = String(v.id);
      var thumb = (v.default_thumb && v.default_thumb.src) || v.thumb || '';
      if (!thumb && Array.isArray(v.thumbs) && v.thumbs[0]) {
        thumb = v.thumbs[0].src || '';
      }
      var dur = v.length_min || formatSeconds(v.length_sec);
      items.push({
        id: 'src:' + source.id + ':' + id,
        rawId: id,
        sourceId: source.id,
        sourceName: source.name,
        title: String(v.title || id),
        name: String(v.title || id),
        poster: thumb,
        thumbnail: thumb,
        runtime: dur ? String(dur) : '',
        durationText: dur ? String(dur) : '',
        imdbRating: v.rate ? String(v.rate) : '',
        viewsText: v.views ? Number(v.views).toLocaleString() + ' views' : '',
        pageUrl: v.url || '',
        embedUrl: v.embed || '',
        isLive: false,
        adult: Boolean(source.adult),
        type: 'movie'
      });
    }
    return {
      source: { id: source.id, name: source.name, kind: source.kind, adult: Boolean(source.adult) },
      items: items,
      page: page,
      hasMore: list.length >= 20
    };
  });
}

function resolveV2VideoJsonApi(source, rawId, itemMeta) {
  var baseApi = String(source.apiUrl || '').trim();
  var detailBase = source.detailApiUrl || baseApi.replace(/\/video\/search\/?$/i, '/video/id/');
  var sep = detailBase.indexOf('?') !== -1 ? '&' : '?';
  var detailUrl = detailBase + sep + 'id=' + encodeURIComponent(rawId) + '&thumbsize=big&format=json';

  return httpClient.getJson(detailUrl, { desktopUa: true, timeoutMs: 12000 }).catch(function () {
    return null;
  }).then(function (detail) {
    var embedUrl = (detail && detail.embed) || (itemMeta && itemMeta.embedUrl) || '';
    var pageUrl = (detail && detail.url) || (itemMeta && itemMeta.pageUrl) || '';
    if (!embedUrl && baseApi) {
      try {
        var parsed = httpClient.parseUrl(baseApi);
        embedUrl = (parsed.protocol || 'https:') + '//' + parsed.host + '/embed/' + encodeURIComponent(rawId) + '/';
      } catch (e) {}
    }

    var target = embedUrl || pageUrl;
    if (!target) throw new Error('No embed or page URL available for item ' + rawId);

    return resolveWebVideoUrl(target, {}).catch(function (err) {
      if (pageUrl && pageUrl !== target) {
        return resolveWebVideoUrl(pageUrl, {});
      }
      throw err;
    }).then(function (resolved) {
      return Object.assign({}, resolved, {
        title: (detail && detail.title) || (itemMeta && itemMeta.title) || rawId,
        author: source.name,
        subtitles: []
      });
    });
  });
}

function catalogLiveRoomsHls(source, opts) {
  var apiUrl = String(source.apiUrl || '').trim();
  if (!apiUrl) return Promise.reject(new Error('Live HLS source missing apiUrl'));
  var query = String((opts && opts.query) || '').trim().toLowerCase();

  return httpClient.getJson(apiUrl, { desktopUa: true, timeoutMs: 15000 }).then(function (root) {
    var rawList = Array.isArray(root) ? root : (Array.isArray(root && root.results) ? root.results : []);
    var items = [];
    for (var i = 0; i < rawList.length; i++) {
      var r = rawList[i];
      if (!r) continue;
      var uname = String(r.username || r.slug || r.id || '');
      if (!uname) continue;
      var subj = String(r.room_subject || r.title || uname);
      var tags = Array.isArray(r.tags) ? r.tags.join(' ') : '';
      if (query) {
        var hay = (uname + ' ' + subj + ' ' + tags).toLowerCase();
        if (hay.indexOf(query) === -1) continue;
      }
      var roomUrl = r.chat_room_url || (source.roomBaseUrl ? source.roomBaseUrl + encodeURIComponent(uname) + '/' : '');
      var fullId = 'src:' + source.id + ':' + uname;
      roomHintCache[fullId] = {
        hlsHint: r.hls_source || '',
        roomUrl: roomUrl,
        title: subj,
        username: uname
      };
      items.push({
        id: fullId,
        rawId: uname,
        sourceId: source.id,
        sourceName: source.name,
        title: subj,
        name: uname + (subj && subj !== uname ? ' — ' + subj : ''),
        channelName: uname,
        poster: r.image_url_360x270 || r.image_url || r.thumb || '',
        thumbnail: r.image_url_360x270 || r.image_url || r.thumb || '',
        viewsText: (r.num_users || r.viewers || 0) + ' watching',
        pageUrl: roomUrl,
        hlsHint: r.hls_source || '',
        isLive: true,
        adult: Boolean(source.adult),
        type: 'channel'
      });
    }
    return {
      source: { id: source.id, name: source.name, kind: source.kind, adult: Boolean(source.adult) },
      items: items,
      page: 1,
      hasMore: false
    };
  });
}

function resolveLiveRoomsHls(source, rawId, itemMeta) {
  var fullId = 'src:' + source.id + ':' + rawId;
  var cached = roomHintCache[fullId] || {};
  var hlsHint = (itemMeta && itemMeta.hlsHint) || cached.hlsHint || '';
  if (hlsHint && /\.m3u8/i.test(hlsHint)) {
    return Promise.resolve({
      streamUrl: hlsHint,
      protocol: 'HLS',
      title: (itemMeta && itemMeta.title) || cached.title || rawId,
      author: rawId,
      isLive: true,
      headers: {},
      needsProxy: true,
      qualityChoices: [{ label: 'Live HLS', url: hlsHint, protocol: 'HLS' }],
      subtitles: []
    });
  }

  var roomUrl = (itemMeta && itemMeta.pageUrl) || cached.roomUrl || '';
  if (!roomUrl && source.roomBaseUrl) {
    roomUrl = source.roomBaseUrl.replace(/\/+$/, '') + '/' + encodeURIComponent(rawId) + '/';
  }
  if (!roomUrl && source.apiUrl) {
    try {
      var parsed = httpClient.parseUrl(source.apiUrl);
      roomUrl = (parsed.protocol || 'https:') + '//' + parsed.host + '/' + encodeURIComponent(rawId) + '/';
    } catch (e) {}
  }
  if (!roomUrl) return Promise.reject(new Error('No room URL for live stream ' + rawId));

  return resolveWebVideoUrl(roomUrl, {}).then(function (resolved) {
    return Object.assign({}, resolved, {
      title: (itemMeta && itemMeta.title) || cached.title || rawId,
      author: rawId,
      isLive: true,
      needsProxy: true,
      subtitles: []
    });
  });
}

function catalogHtmlScraper(source, opts) {
  var query = String((opts && opts.query) || '').trim();
  var page = Math.max(1, parseInt((opts && opts.page) || 1, 10) || 1);
  var tpl = (query && source.searchUrlTemplate) ? source.searchUrlTemplate : (source.catalogUrl || source.apiUrl || '');
  if (!tpl) return Promise.reject(new Error('HTML scraper source missing catalogUrl/searchUrlTemplate'));
  var targetUrl = tpl
    .replace(/\{query\}/g, encodeURIComponent(query))
    .replace(/\{page\}/g, String(page));

  return httpClient.getText(targetUrl, {
    headers: source.headers || {},
    desktopUa: true,
    timeoutMs: 15000
  }).then(function (resp) {
    var html = resp.text || '';
    var finalBase = resp.url || targetUrl;
    var items = [];
    var seen = {};
    var cardRe = /<a[^>]+href\s*=\s*['"]([^'"]+)['"][^>]*>([\s\S]{0,400}?)<\/a>/gi;
    var m;
    while ((m = cardRe.exec(html)) !== null && items.length < 40) {
      var href = httpClient.resolveUrl(finalBase, m[1]);
      var inner = m[2] || '';
      var imgM = inner.match(/<img[^>]+(?:data-src|src)\s*=\s*['"]([^'"]+)['"]/i);
      if (!imgM) continue;
      var thumb = httpClient.resolveUrl(finalBase, imgM[1]);
      var titleM = inner.match(/alt\s*=\s*['"]([^'"]{3,140})['"]/i) || m[0].match(/title\s*=\s*['"]([^'"]{3,140})['"]/i);
      var title = titleM ? httpClient.decodeXmlEntities(titleM[1]).trim() : '';
      if (!title) {
        title = inner.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
      }
      if (!title || title.length < 2 || seen[href]) continue;
      seen[href] = true;
      var encodedHref = httpClient.b64UrlEncode(href);
      items.push({
        id: 'src:' + source.id + ':' + encodedHref,
        rawId: encodedHref,
        sourceId: source.id,
        sourceName: source.name,
        title: title,
        name: title,
        poster: thumb,
        thumbnail: thumb,
        pageUrl: href,
        adult: Boolean(source.adult),
        type: 'movie'
      });
    }
    return {
      source: { id: source.id, name: source.name, kind: source.kind, adult: Boolean(source.adult) },
      items: items,
      page: page,
      hasMore: items.length >= 20
    };
  });
}

function catalogDirectSource(source, opts) {
  if (!source) return Promise.reject(new Error('Unknown direct source'));
  var kind = String(source.kind || '').toLowerCase();
  if (kind === 'internet_archive') {
    return catalogInternetArchive(source, opts);
  }
  if (kind.slice(-9) === '_json_api' || source.schema === 'v2_video_json') {
    return catalogV2VideoJsonApi(source, opts);
  }
  if ((kind.slice(-4) === '_hls' && kind !== 'html_hls_scraper') || source.schema === 'live_rooms_hls') {
    return catalogLiveRoomsHls(source, opts);
  }
  if (kind === 'html_hls_scraper') {
    return catalogHtmlScraper(source, opts);
  }
  return Promise.reject(new Error('Unsupported direct source kind: ' + source.kind));
}

function resolveDirectSource(source, rawId, itemMeta) {
  if (!source) return Promise.reject(new Error('Unknown direct source'));
  var kind = String(source.kind || '').toLowerCase();
  if (kind === 'internet_archive') {
    return resolveInternetArchive(source, rawId);
  }
  if (kind.slice(-9) === '_json_api' || source.schema === 'v2_video_json') {
    return resolveV2VideoJsonApi(source, rawId, itemMeta);
  }
  if ((kind.slice(-4) === '_hls' && kind !== 'html_hls_scraper') || source.schema === 'live_rooms_hls') {
    return resolveLiveRoomsHls(source, rawId, itemMeta);
  }
  if (kind === 'html_hls_scraper') {
    var pageUrl = (itemMeta && itemMeta.pageUrl) || '';
    if (!pageUrl && rawId) {
      try { pageUrl = httpClient.b64UrlDecodeToString(rawId); } catch (e) {}
    }
    return resolveWebVideoUrl(pageUrl, source.headers || {});
  }
  return Promise.reject(new Error('Unsupported direct source kind: ' + source.kind));
}

// ─────────────────────────────────────────────────────────────────────────────
// Subtitle (.srt / .ass / .ssa -> .vtt) On-The-Fly Converter
// ─────────────────────────────────────────────────────────────────────────────

function formatAssTimestampToVtt(ts) {
  // ASS format: H:MM:SS.cc (centiseconds) -> HH:MM:SS.cc0
  var m = String(ts || '').trim().match(/^(\d+):(\d{1,2}):(\d{1,2})[.,](\d{1,3})$/);
  if (!m) return '00:00:00.000';
  var hh = m[1].length < 2 ? '0' + m[1] : m[1];
  var mm = m[2].length < 2 ? '0' + m[2] : m[2];
  var ss = m[3].length < 2 ? '0' + m[3] : m[3];
  var frac = m[4];
  while (frac.length < 3) frac += '0';
  if (frac.length > 3) frac = frac.slice(0, 3);
  return hh + ':' + mm + ':' + ss + '.' + frac;
}

function convertSubtitleToVtt(rawText) {
  var text = String(rawText || '').replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  var trimmed = text.trim();
  if (!trimmed) return 'WEBVTT\n\n';
  if (/^WEBVTT(?:\s|$)/i.test(trimmed)) {
    return trimmed + '\n';
  }

  // Detect ASS/SSA subtitle
  if (/\[Events\]/i.test(trimmed) || /^Dialogue:/im.test(trimmed)) {
    var lines = trimmed.split('\n');
    var startIdx = 1;
    var endIdx = 2;
    var textIdx = 9;
    var cues = [];
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (/^Format:/i.test(line)) {
        var cols = line.slice(7).split(',').map(function (c) { return c.trim().toLowerCase(); });
        var si = cols.indexOf('start');
        var ei = cols.indexOf('end');
        var ti = cols.indexOf('text');
        if (si !== -1) startIdx = si;
        if (ei !== -1) endIdx = ei;
        if (ti !== -1) textIdx = ti;
      } else if (/^Dialogue:/i.test(line)) {
        var body = line.slice(9).trim();
        var parts = body.split(',');
        if (parts.length > textIdx) {
          var startT = formatAssTimestampToVtt(parts[startIdx]);
          var endT = formatAssTimestampToVtt(parts[endIdx]);
          var cueRaw = parts.slice(textIdx).join(',');
          var cueClean = cueRaw
            .replace(/\{[^}]*\}/g, '')
            .replace(/\\N/g, '\n')
            .replace(/\\n/g, '\n')
            .replace(/\\h/g, ' ')
            .trim();
          if (cueClean) {
            cues.push(startT + ' --> ' + endT + '\n' + cueClean);
          }
        }
      }
    }
    return 'WEBVTT\n\n' + cues.join('\n\n') + '\n';
  }

  // SRT -> WebVTT
  var srtBlocks = trimmed.split(/\n{2,}/);
  var vttBlocks = [];
  for (var b = 0; b < srtBlocks.length; b++) {
    var blkLines = srtBlocks[b].split('\n').map(function (l) { return l.trimRight(); });
    if (blkLines.length === 0) continue;
    if (/^\d+$/.test(blkLines[0].trim()) && blkLines.length > 1) {
      blkLines.shift();
    }
    if (blkLines.length === 0) continue;
    blkLines[0] = blkLines[0].replace(
      /(\d{1,2}:\d{2}:\d{2}),(\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}),(\d{1,3})/g,
      function (_, t1, ms1, t2, ms2) {
        return formatAssTimestampToVtt(t1 + '.' + ms1) + ' --> ' + formatAssTimestampToVtt(t2 + '.' + ms2);
      }
    );
    vttBlocks.push(blkLines.join('\n'));
  }
  return 'WEBVTT\n\n' + vttBlocks.join('\n\n') + '\n';
}

// ─────────────────────────────────────────────────────────────────────────────
// HLS Ad-Stripper & Reverse-Proxy Playlist Rewriter
// ─────────────────────────────────────────────────────────────────────────────

function rewriteUriAttr(line, baseUrl, proxyPrefix, headersB64) {
  var idx = line.indexOf('URI="');
  if (idx === -1) return line;
  var valStart = idx + 5;
  var valEnd = line.indexOf('"', valStart);
  if (valEnd === -1) return line;
  var rawUri = line.slice(valStart, valEnd);
  var abs = httpClient.resolveUrl(baseUrl, rawUri);
  var encoded = httpClient.b64UrlEncode(abs);
  var nextUri = proxyPrefix + encoded + (headersB64 ? '&h=' + headersB64 : '');
  return line.slice(0, valStart) + nextUri + line.slice(valEnd);
}

function rewriteHlsPlaylistForProxy(rawPlaylist, playlistUrl, mediaBaseUrl, headersB64) {
  var text = String(rawPlaylist || '');
  var isMaster = text.indexOf('#EXT-X-STREAM-INF') !== -1 || text.indexOf('#EXT-X-I-FRAME-STREAM-INF') !== -1;
  var hlsPrefix = mediaBaseUrl + '/proxy/hls.m3u8?u=';
  var segPrefix = mediaBaseUrl + '/proxy/stream?u=';

  if (isMaster) {
    var mLines = text.split(/\r?\n/);
    var mOut = [];
    for (var i = 0; i < mLines.length; i++) {
      var trimmed = mLines[i].trim();
      if (!trimmed) continue;
      if (trimmed.charAt(0) !== '#') {
        var absVar = httpClient.resolveUrl(playlistUrl, trimmed);
        mOut.push(hlsPrefix + httpClient.b64UrlEncode(absVar) + (headersB64 ? '&h=' + headersB64 : ''));
      } else if (trimmed.indexOf('URI="') !== -1) {
        var targetPfx = trimmed.indexOf('#EXT-X-MEDIA:') === 0 ? hlsPrefix : segPrefix;
        mOut.push(rewriteUriAttr(trimmed, playlistUrl, targetPfx, headersB64));
      } else {
        mOut.push(trimmed);
      }
    }
    return mOut.join('\n') + '\n';
  }

  // Media playlist: strip stitched ads (Twitch / SCTE-35 / Amazon) + rewrite segment/key URLs
  var rawLines = text.split(/\r?\n/);
  var headerLines = [];
  var mediaSeq = null;
  var bodyLines = [];
  var pendingExtinf = null;
  var pendingSegMeta = [];
  var seenCleanSeg = false;
  var leadingStripped = 0;
  var strippedSinceClean = false;

  for (var j = 0; j < rawLines.length; j++) {
    var line = rawLines[j].trim();
    if (!line) continue;
    var lower = line.toLowerCase();

    if (
      (line.indexOf('#EXT-X-DATERANGE:') === 0 &&
        (lower.indexOf('stitched-ad') !== -1 || lower.indexOf('twitch-ad') !== -1)) ||
      line.indexOf('#EXT-X-SCTE35-') === 0
    ) {
      continue;
    }

    if (line.indexOf('#EXT-X-MEDIA-SEQUENCE:') === 0) {
      var seqVal = parseInt(line.slice('#EXT-X-MEDIA-SEQUENCE:'.length).trim(), 10);
      if (!isNaN(seqVal)) {
        mediaSeq = { idx: headerLines.length, val: seqVal };
      }
      headerLines.push(line);
      continue;
    }

    if (line.indexOf('#EXTINF:') === 0) {
      pendingExtinf = line;
      continue;
    }

    if (line.charAt(0) === '#') {
      var processedTag = line.indexOf('URI="') !== -1
        ? rewriteUriAttr(line, playlistUrl, segPrefix, headersB64)
        : line;
      if (
        line.indexOf('#EXT-X-PROGRAM-DATE-TIME:') === 0 ||
        line.indexOf('#EXT-X-BITRATE:') === 0 ||
        line.indexOf('#EXT-X-KEY:') === 0 ||
        line.indexOf('#EXT-X-MAP:') === 0 ||
        line === '#EXT-X-DISCONTINUITY'
      ) {
        if (seenCleanSeg || pendingExtinf) {
          pendingSegMeta.push(processedTag);
        } else {
          headerLines.push(processedTag);
        }
      } else if (line === '#EXT-X-ENDLIST') {
        bodyLines.push(processedTag);
      } else if (!seenCleanSeg && !pendingExtinf) {
        headerLines.push(processedTag);
      } else {
        bodyLines.push(processedTag);
      }
      continue;
    }

    var extinf = pendingExtinf || '#EXTINF:2.000,';
    pendingExtinf = null;
    var extLower = extinf.toLowerCase();
    var isAdSeg =
      extinf.indexOf(',Amazon') !== -1 ||
      extLower.indexOf('stitched-ad') !== -1 ||
      extLower.indexOf('twitch-ad') !== -1 ||
      lower.indexOf('stitched-ad') !== -1;

    if (isAdSeg) {
      pendingSegMeta = [];
      if (!seenCleanSeg) leadingStripped++;
      else strippedSinceClean = true;
      continue;
    }

    if (strippedSinceClean) {
      bodyLines.push('#EXT-X-DISCONTINUITY');
      strippedSinceClean = false;
    }
    seenCleanSeg = true;
    for (var mIdx = 0; mIdx < pendingSegMeta.length; mIdx++) {
      bodyLines.push(pendingSegMeta[mIdx]);
    }
    pendingSegMeta = [];
    bodyLines.push(extinf);

    var absSeg = httpClient.resolveUrl(playlistUrl, line);
    if (headersB64) {
      bodyLines.push(segPrefix + httpClient.b64UrlEncode(absSeg) + '&h=' + headersB64);
    } else {
      bodyLines.push(absSeg);
    }
  }

  if (!seenCleanSeg && leadingStripped > 0) {
    // Fallback if an entire pre-roll window was ads: rewrite segments directly without stalling
    return rewriteFallbackPlaylist(rawLines, playlistUrl, segPrefix, headersB64);
  }

  if (mediaSeq && leadingStripped > 0) {
    headerLines[mediaSeq.idx] = '#EXT-X-MEDIA-SEQUENCE:' + (mediaSeq.val + leadingStripped);
  }

  return headerLines.concat(bodyLines).join('\n') + '\n';
}

function rewriteFallbackPlaylist(lines, playlistUrl, segPrefix, headersB64) {
  var out = [];
  for (var i = 0; i < lines.length; i++) {
    var line = lines[i].trim();
    if (!line) continue;
    if (line.charAt(0) === '#') {
      out.push(line.indexOf('URI="') !== -1 ? rewriteUriAttr(line, playlistUrl, segPrefix, headersB64) : line);
    } else {
      var absSeg = httpClient.resolveUrl(playlistUrl, line);
      out.push(headersB64 ? segPrefix + httpClient.b64UrlEncode(absSeg) + '&h=' + headersB64 : absSeg);
    }
  }
  return out.join('\n') + '\n';
}

module.exports = {
  unpackDeanEdwards: unpackDeanEdwards,
  resolveWebVideoUrl: resolveWebVideoUrl,
  catalogDirectSource: catalogDirectSource,
  resolveDirectSource: resolveDirectSource,
  convertSubtitleToVtt: convertSubtitleToVtt,
  rewriteHlsPlaylistForProxy: rewriteHlsPlaylistForProxy
};
