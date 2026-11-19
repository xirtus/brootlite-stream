'use strict';

var path = require('path');
var fs = require('fs');
var torrentStream = require('torrent-stream');
var httpClient = require('./http_client');
var storeMod = require('./store');

var VIDEO_EXTENSIONS = ['.mkv', '.mp4', '.webm', '.avi', '.m4v', '.mov', '.ts'];

function isVideoFile(name) {
  var low = String(name || '').toLowerCase();
  for (var i = 0; i < VIDEO_EXTENSIONS.length; i++) {
    var ext = VIDEO_EXTENSIONS[i];
    if (low.slice(-ext.length) === ext) return true;
  }
  return false;
}

function guessVideoMime(name) {
  var low = String(name || '').toLowerCase();
  if (low.slice(-4) === '.mp4' || low.slice(-4) === '.m4v') return 'video/mp4';
  if (low.slice(-5) === '.webm') return 'video/webm';
  if (low.slice(-4) === '.mkv') return 'video/x-matroska';
  if (low.slice(-4) === '.avi') return 'video/x-msvideo';
  if (low.slice(-4) === '.mov') return 'video/quicktime';
  if (low.slice(-3) === '.ts') return 'video/mp2t';
  return 'video/mp4';
}

function base32ToHex(b32) {
  var alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  var clean = String(b32 || '').toUpperCase().replace(/=+$/g, '');
  var bits = 0;
  var value = 0;
  var hex = '';
  for (var i = 0; i < clean.length; i++) {
    var idx = alphabet.indexOf(clean.charAt(i));
    if (idx === -1) return null;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      var byte = (value >>> bits) & 0xff;
      hex += (byte < 16 ? '0' : '') + byte.toString(16);
    }
  }
  return hex.length === 40 ? hex : null;
}

function extractInfoHash(magnetOrHash) {
  var raw = String(magnetOrHash || '').trim();
  if (/^[a-f0-9]{40}$/i.test(raw)) return raw.toLowerCase();
  if (/^[a-z2-7]{32}$/i.test(raw)) return base32ToHex(raw);
  var m = raw.match(/xt=urn:btih:([a-f0-9]{40}|[a-z2-7]{32})/i);
  if (m && m[1]) {
    if (m[1].length === 40) return m[1].toLowerCase();
    return base32ToHex(m[1]);
  }
  return null;
}

function extractTrackersFromMagnet(magnet) {
  var trackers = [];
  var re = /[?&]tr=([^&]+)/gi;
  var m;
  while ((m = re.exec(String(magnet || ''))) !== null) {
    try {
      var decoded = decodeURIComponent(m[1]).trim();
      if (decoded) trackers.push(decoded);
    } catch (e) {}
  }
  return trackers;
}

function parseHttpByteRange(header, totalLen) {
  if (!totalLen || totalLen <= 0) return null;
  var raw = String(header || '').trim();
  if (raw.toLowerCase().indexOf('bytes=') !== 0) return null;
  var spec = raw.slice(6).split(',')[0].trim();
  var dash = spec.indexOf('-');
  if (dash === -1) return null;
  var startStr = spec.slice(0, dash).trim();
  var endStr = spec.slice(dash + 1).trim();

  if (!startStr) {
    var suffix = parseInt(endStr, 10);
    if (isNaN(suffix) || suffix <= 0) return null;
    var s = Math.max(0, totalLen - suffix);
    return { start: s, end: totalLen - 1 };
  }
  var start = parseInt(startStr, 10);
  if (isNaN(start) || start < 0 || start >= totalLen) return null;
  if (!endStr) return { start: start, end: totalLen - 1 };
  var end = parseInt(endStr, 10);
  if (isNaN(end) || end < start) return null;
  return { start: start, end: Math.min(end, totalLen - 1) };
}

function parseEpisodeHint(opts) {
  if (!opts) return null;
  if (opts.episodeHint && opts.episodeHint.season) {
    return {
      season: Number(opts.episodeHint.season),
      episode: opts.episodeHint.episode ? Number(opts.episodeHint.episode) : null
    };
  }
  var vid = String(opts.videoId || '');
  var parts = vid.split(':');
  if (parts.length >= 3 && /^\d+$/.test(parts[1]) && /^\d+$/.test(parts[2])) {
    return { season: parseInt(parts[1], 10), episode: parseInt(parts[2], 10) };
  }
  return null;
}

function selectTargetFile(files, requestedFileIdx, episodeHint) {
  if (!Array.isArray(files) || files.length === 0) {
    throw new Error('Torrent contains 0 files');
  }

  if (typeof requestedFileIdx === 'number' && requestedFileIdx >= 0 && requestedFileIdx < files.length) {
    var reqFile = files[requestedFileIdx];
    if (reqFile && (isVideoFile(reqFile.name) || files.length === 1 || reqFile.length > 5 * 1024 * 1024)) {
      return { idx: requestedFileIdx, file: reqFile };
    }
  }

  if (episodeHint && episodeHint.season) {
    var sNum = episodeHint.season;
    var eNum = episodeHint.episode;
    var pad = function (n) { return n < 10 ? '0' + n : String(n); };
    var exactPatterns = [];
    if (eNum !== null && eNum !== undefined) {
      exactPatterns.push(new RegExp('\\bs0?' + sNum + 'e0?' + eNum + '\\b', 'i'));
      exactPatterns.push(new RegExp('\\b' + sNum + 'x0?' + eNum + '\\b', 'i'));
      exactPatterns.push(new RegExp('s' + pad(sNum) + '[^a-z0-9]?e' + pad(eNum), 'i'));
    }
    var epMatches = [];
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (!isVideoFile(f.name)) continue;
      if (/\bsample\b/i.test(f.name)) continue;
      for (var p = 0; p < exactPatterns.length; p++) {
        if (exactPatterns[p].test(f.name)) {
          epMatches.push({ idx: i, file: f });
          break;
        }
      }
    }
    if (epMatches.length > 0) {
      epMatches.sort(function (a, b) { return b.file.length - a.file.length; });
      return epMatches[0];
    }
  }

  var best = null;
  for (var j = 0; j < files.length; j++) {
    var candidate = files[j];
    var vid = isVideoFile(candidate.name);
    var sample = /\bsample\b/i.test(candidate.name);
    if (!best) {
      best = { idx: j, file: candidate, vid: vid, sample: sample };
      continue;
    }
    if (vid && !best.vid) {
      best = { idx: j, file: candidate, vid: vid, sample: sample };
    } else if (vid === best.vid) {
      if (!sample && best.sample) {
        best = { idx: j, file: candidate, vid: vid, sample: sample };
      } else if (sample === best.sample && candidate.length > best.file.length) {
        best = { idx: j, file: candidate, vid: vid, sample: sample };
      }
    }
  }
  return { idx: best.idx, file: best.file };
}

function fetchFastTorrentBuffer(infoHash, directTorrentUrl) {
  var candidates = [];
  if (directTorrentUrl && /^https?:\/\//i.test(directTorrentUrl) && directTorrentUrl.indexOf('magnet:') !== 0) {
    candidates.push(directTorrentUrl);
  }
  if (infoHash && /^[a-f0-9]{40}$/i.test(infoHash)) {
    candidates.push('https://itorrents.org/torrent/' + infoHash.toUpperCase() + '.torrent');
  }
  if (candidates.length === 0) return Promise.resolve(null);

  function tryIdx(idx) {
    if (idx >= candidates.length) return Promise.resolve(null);
    return httpClient.getBuffer(candidates[idx], {
      desktopUa: true,
      timeoutMs: 2500,
      headers: { Accept: 'application/x-bittorrent, application/octet-stream, */*' }
    }).then(function (resp) {
      if (resp && resp.ok && Buffer.isBuffer(resp.buffer) && resp.buffer.length > 24 && resp.buffer[0] === 0x64) {
        return resp.buffer;
      }
      return tryIdx(idx + 1);
    }).catch(function () {
      return tryIdx(idx + 1);
    });
  }

  return tryIdx(0);
}

function createP2PEngine(store, options) {
  options = options || {};
  var onActivityChange = typeof options.onActivityChange === 'function' ? options.onActivityChange : function () {};
  var activeSession = null;

  function sweepStaleCache() {
    try {
      if (!fs.existsSync(store.torrentCacheDir)) return;
      var entries = fs.readdirSync(store.torrentCacheDir);
      for (var i = 0; i < entries.length; i++) {
        storeMod.rimrafSync(path.join(store.torrentCacheDir, entries[i]));
      }
    } catch (e) {}
  }

  sweepStaleCache();

  function destroySessionSync(sess) {
    if (!sess) return Promise.resolve();
    return new Promise(function (resolve) {
      var engine = sess.engine;
      var done = false;
      function finish() {
        if (done) return;
        done = true;
        try {
          storeMod.rimrafSync(sess.sessionDir);
        } catch (e) {}
        resolve();
      }
      var timer = setTimeout(finish, 2000);
      try {
        if (engine) {
          engine.destroy(function () {
            clearTimeout(timer);
            finish();
          });
        } else {
          clearTimeout(timer);
          finish();
        }
      } catch (err) {
        clearTimeout(timer);
        finish();
      }
    });
  }

  function stopSession(sessionId) {
    if (!activeSession) return Promise.resolve(true);
    if (sessionId && activeSession.sessionId !== sessionId) return Promise.resolve(true);
    var old = activeSession;
    activeSession = null;
    onActivityChange(false);
    return destroySessionSync(old).then(function () { return true; });
  }

  function stopAll() {
    if (!activeSession) {
      sweepStaleCache();
      onActivityChange(false);
      return Promise.resolve(true);
    }
    var old = activeSession;
    activeSession = null;
    onActivityChange(false);
    return destroySessionSync(old).then(function () {
      sweepStaleCache();
      return true;
    });
  }

  function updateSlidingWindow(sess, byteOffset) {
    if (!sess || !sess.engine || !sess.engine.torrent || !sess.file) return;
    var engine = sess.engine;
    var file = sess.file;
    var pieceLength = engine.torrent.pieceLength;
    if (!pieceLength || pieceLength <= 0) return;

    var cfg = store.getStateRaw().torrentConfig || { windowAheadMb: 48 };
    var aheadBytes = (cfg.windowAheadMb || 48) * 1024 * 1024;
    var windowPieces = Math.max(4, Math.ceil(aheadBytes / pieceLength));

    var fileStartPiece = Math.floor(file.offset / pieceLength);
    var fileEndPiece = Math.floor((file.offset + Math.max(1, file.length) - 1) / pieceLength);
    var clampedOffset = Math.max(0, Math.min(file.length - 1, byteOffset || 0));
    var curPiece = Math.floor((file.offset + clampedOffset) / pieceLength);
    var endPiece = Math.min(fileEndPiece, curPiece + windowPieces);

    if (sess.slidingWindow) {
      if (sess.slidingWindow.from === curPiece && sess.slidingWindow.to === endPiece) {
        return;
      }
      try {
        engine.deselect(sess.slidingWindow.from, sess.slidingWindow.to, true);
      } catch (e) {}
    }

    sess.slidingWindow = { from: curPiece, to: endPiece };
    sess.lastReadOffset = clampedOffset;
    try {
      engine.select(curPiece, endPiece, true);
      engine.critical(curPiece, Math.min(3, Math.max(1, endPiece - curPiece + 1)));
    } catch (e2) {}

    // Ensure header pieces remain prioritized on seek near start
    if (curPiece <= fileStartPiece + 4) {
      var headPieces = Math.max(2, Math.ceil((2 * 1024 * 1024) / pieceLength));
      var headEnd = Math.min(fileEndPiece, fileStartPiece + headPieces);
      try {
        engine.critical(fileStartPiece, Math.min(4, headEnd - fileStartPiece + 1));
      } catch (e3) {}
    }
  }

  function startTorrentStream(opts, mediaBaseUrl) {
    opts = opts || {};
    var rawInput = String(opts.magnetOrUrl || opts.infoHash || '').trim();
    if (!rawInput) return Promise.reject(new Error('Missing magnetOrUrl or infoHash'));

    var infoHash = extractInfoHash(rawInput);
    var pack = store.getActivePack();
    var mergedTrackers = [];
    var trackerSeen = {};

    function addTracker(t) {
      var clean = String(t || '').replace(/^tracker:/, '').trim();
      if (!clean || trackerSeen[clean]) return;
      if (/^(udp|https?|wss?):\/\//i.test(clean)) {
        trackerSeen[clean] = true;
        mergedTrackers.push(clean);
      }
    }

    if (Array.isArray(opts.sources)) {
      for (var i = 0; i < opts.sources.length; i++) addTracker(opts.sources[i]);
    }
    var magTrackers = extractTrackersFromMagnet(rawInput);
    for (var j = 0; j < magTrackers.length; j++) addTracker(magTrackers[j]);
    if (Array.isArray(pack.trackers)) {
      for (var k = 0; k < pack.trackers.length; k++) addTracker(pack.trackers[k]);
    }

    var magnetUri = rawInput.indexOf('magnet:') === 0
      ? rawInput
      : (infoHash
          ? 'magnet:?xt=urn:btih:' + infoHash +
            (opts.title ? '&dn=' + encodeURIComponent(opts.title) : '') +
            mergedTrackers.map(function (tr) { return '&tr=' + encodeURIComponent(tr); }).join('')
          : rawInput);

    // Cleanly stop any prior session first so TV flash & RAM stay bounded
    return stopAll().then(function () {
      return fetchFastTorrentBuffer(infoHash, rawInput.indexOf('magnet:') !== 0 && /^https?:\/\//i.test(rawInput) ? rawInput : null);
    }).then(function (fastTorrentBuf) {
      var sessionId = httpClient.uuidV4().replace(/-/g, '').slice(0, 16);
      var sessionDir = path.join(store.torrentCacheDir, 'sess_' + sessionId);
      store.mkdirpSync(sessionDir);

      var cfg = store.getStateRaw().torrentConfig || { maxConnections: 80 };
      var engineOpts = {
        connections: Math.max(25, Math.min(180, cfg.maxConnections || 80)),
        uploads: 4,
        tmp: store.torrentCacheDir,
        path: sessionDir,
        name: 'sess_' + sessionId,
        dht: true,
        tracker: true,
        verify: false,
        trackers: mergedTrackers
      };

      var sourceInput = fastTorrentBuf || magnetUri;
      var engine;
      try {
        engine = torrentStream(sourceInput, engineOpts);
      } catch (err) {
        if (fastTorrentBuf) {
          engine = torrentStream(magnetUri, engineOpts);
        } else {
          storeMod.rimrafSync(sessionDir);
          throw err;
        }
      }

      var sess = {
        sessionId: sessionId,
        sessionDir: sessionDir,
        engine: engine,
        infoHash: infoHash || engine.infoHash || '',
        file: null,
        fileIdx: 0,
        contentType: 'video/mp4',
        startedAtMs: Date.now(),
        lastReadOffset: 0,
        slidingWindow: null,
        metadataSource: fastTorrentBuf ? 'itorrents-cache' : 'dht-metadata'
      };
      activeSession = sess;
      onActivityChange(true);

      return new Promise(function (resolve, reject) {
        var settled = false;
        var readyTimer = setTimeout(function () {
          if (settled) return;
          settled = true;
          stopSession(sessionId);
          reject(new Error('Timed out resolving torrent metadata (25s)'));
        }, 25000);

        engine.on('error', function (err) {
          if (!settled) {
            settled = true;
            clearTimeout(readyTimer);
            stopSession(sessionId);
            reject(err);
          }
        });

        engine.ready(function () {
          if (settled) return;
          settled = true;
          clearTimeout(readyTimer);

          try {
            var epHint = parseEpisodeHint(opts);
            var reqIdx = typeof opts.fileIdx === 'number' ? opts.fileIdx : null;
            var picked = selectTargetFile(engine.files, reqIdx, epHint);

            sess.file = picked.file;
            sess.fileIdx = picked.idx;
            sess.infoHash = engine.infoHash || sess.infoHash;
            sess.contentType = guessVideoMime(picked.file.name);

            // Prioritize first 2 MB header & last 2 MB footer (MKV Cues / MP4 moov)
            var pieceLength = engine.torrent.pieceLength;
            var fileStartPiece = Math.floor(picked.file.offset / pieceLength);
            var fileEndPiece = Math.floor((picked.file.offset + Math.max(1, picked.file.length) - 1) / pieceLength);
            var edgePieces = Math.max(2, Math.ceil((2 * 1024 * 1024) / pieceLength));

            var headEnd = Math.min(fileEndPiece, fileStartPiece + edgePieces);
            var footStart = Math.max(fileStartPiece, fileEndPiece - edgePieces);

            engine.select(fileStartPiece, headEnd, true);
            engine.critical(fileStartPiece, Math.min(4, headEnd - fileStartPiece + 1));
            if (footStart > headEnd) {
              engine.select(footStart, fileEndPiece, true);
            }
            updateSlidingWindow(sess, 0);

            resolve({
              sessionId: sessionId,
              playUrl: mediaBaseUrl + '/torrent/' + sessionId + '/stream',
              fileName: picked.file.name,
              fileSizeBytes: picked.file.length,
              fileIdx: picked.idx,
              infoHash: sess.infoHash,
              metadataSource: sess.metadataSource,
              isTorrent: true,
              protocol: 'TORRENT_HTTP'
            });
          } catch (pickErr) {
            stopSession(sessionId);
            reject(pickErr);
          }
        });
      });
    });
  }

  function getStats(sessionId) {
    if (!activeSession || (sessionId && activeSession.sessionId !== sessionId)) {
      return {
        active: false,
        sessionId: sessionId || '',
        state: 'idle',
        downloadSpeedMbps: 0,
        peers: 0,
        livePeers: 0,
        windowPct: 0,
        progressBytes: 0,
        totalBytes: 0,
        formattedOverlay: 'Idle'
      };
    }

    var sess = activeSession;
    var engine = sess.engine;
    var file = sess.file;
    var wires = (engine && engine.swarm && Array.isArray(engine.swarm.wires)) ? engine.swarm.wires : [];
    var speedBytes = (engine && engine.swarm && typeof engine.swarm.downloadSpeed === 'function')
      ? engine.swarm.downloadSpeed()
      : 0;
    var mbps = speedBytes / (1024 * 1024);

    var windowPct = 0;
    var progressBytes = 0;
    var totalBytes = file ? file.length : 0;

    if (engine && engine.torrent && engine.bitfield && file) {
      var pieceLen = engine.torrent.pieceLength;
      var fStart = Math.floor(file.offset / pieceLen);
      var fEnd = Math.floor((file.offset + Math.max(1, file.length) - 1) / pieceLen);
      var haveCount = 0;
      for (var p = fStart; p <= fEnd; p++) {
        if (engine.bitfield.get(p)) haveCount++;
      }
      progressBytes = Math.min(totalBytes, haveCount * pieceLen);

      var winFrom = sess.slidingWindow ? sess.slidingWindow.from : fStart;
      var winTo = sess.slidingWindow ? sess.slidingWindow.to : Math.min(fEnd, fStart + 8);
      var winTotal = Math.max(1, winTo - winFrom + 1);
      var winHave = 0;
      for (var w = winFrom; w <= winTo; w++) {
        if (engine.bitfield.get(w)) winHave++;
      }
      windowPct = Math.min(100, Math.round((winHave / winTotal) * 100));
    }

    var stateLabel = !file ? 'resolving' : (windowPct >= 100 || progressBytes > 1024 * 1024 ? 'streaming' : 'buffering');
    return {
      active: true,
      sessionId: sess.sessionId,
      state: stateLabel,
      downloadSpeedBytes: speedBytes,
      downloadSpeedMbps: Number(mbps.toFixed(2)),
      peers: wires.length,
      livePeers: wires.length,
      windowPct: windowPct,
      progressBytes: progressBytes,
      totalBytes: totalBytes,
      formattedOverlay: '↓ ' + mbps.toFixed(2) + ' MB/s • ' + wires.length + ' Peers • ' + windowPct + '%'
    };
  }

  function handleTorrentStreamRequest(req, res, sessionId) {
    if (!activeSession || activeSession.sessionId !== sessionId || !activeSession.file) {
      res.writeHead(404, {
        'Content-Type': 'text/plain',
        'Access-Control-Allow-Origin': '*'
      });
      res.end('Torrent session not found');
      return;
    }

    var sess = activeSession;
    var file = sess.file;
    var totalLen = file.length;
    if (!totalLen || totalLen <= 0) {
      res.writeHead(200, {
        'Content-Type': sess.contentType,
        'Content-Length': '0',
        'Access-Control-Allow-Origin': '*'
      });
      res.end();
      return;
    }

    var rangeHeader = req.headers && req.headers.range;
    var isPartial = false;
    var start = 0;
    var end = totalLen - 1;

    if (rangeHeader) {
      var parsed = parseHttpByteRange(rangeHeader, totalLen);
      if (!parsed) {
        res.writeHead(416, {
          'Content-Range': 'bytes */' + totalLen,
          'Content-Length': '0',
          'Access-Control-Allow-Origin': '*'
        });
        res.end();
        return;
      }
      isPartial = true;
      start = parsed.start;
      end = parsed.end;
    }

    var contentLen = end - start + 1;
    var headers = {
      'Content-Type': sess.contentType,
      'Accept-Ranges': 'bytes',
      'Content-Length': String(contentLen),
      'Cache-Control': 'no-store',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Expose-Headers': 'Content-Length, Content-Range, Accept-Ranges'
    };
    if (isPartial) {
      headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + totalLen;
    }

    res.writeHead(isPartial ? 206 : 200, headers);
    if (String(req.method || 'GET').toUpperCase() === 'HEAD') {
      res.end();
      return;
    }

    updateSlidingWindow(sess, start);
    var stream = file.createReadStream({ start: start, end: end });

    var cleanedUp = false;
    function cleanup() {
      if (cleanedUp) return;
      cleanedUp = true;
      try { stream.destroy(); } catch (e) {}
    }

    req.on('close', cleanup);
    res.on('close', cleanup);
    stream.on('error', function () {
      cleanup();
      try { if (!res.finished) res.end(); } catch (e) {}
    });
    stream.pipe(res);
  }

  return {
    startTorrentStream: startTorrentStream,
    stopSession: stopSession,
    stopAll: stopAll,
    getStats: getStats,
    hasActiveSession: function () { return Boolean(activeSession); },
    handleTorrentStreamRequest: handleTorrentStreamRequest,
    extractInfoHash: extractInfoHash
  };
}

module.exports = {
  createP2PEngine: createP2PEngine,
  parseHttpByteRange: parseHttpByteRange,
  extractInfoHash: extractInfoHash,
  selectTargetFile: selectTargetFile
};
