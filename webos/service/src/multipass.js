'use strict';

var crypto = require('crypto');
var urlMod = require('url');
var httpClient = require('./http_client');

var DEFAULT_PBKDF2_ITERATIONS = 210000;
var PAIR_WRAP_ALGO = 'ECDH-P256-HKDF-SHA256-AES-256-GCM';
var PAIR_WRAP_HKDF_INFO = Buffer.from('leeloo-multipass:pair-wrap:v2', 'utf8');
var KEYSLOT_AAD = 'leeloo-multipass:keyslot:v2';
var MEDIA_SYNC_V2_INFO = Buffer.from('leeloo-multipass:media-sync:v2', 'utf8');
var MEDIA_SYNC_V2_AAD = 'leeloo-multipass:media-sync:v2';
var MEDIA_SYNC_V1_AAD = 'leeloo-multipass:media-sync:v1';

function normalizeUserCode(raw) {
  var cleaned = String(raw || '').replace(/[\s-]+/g, '').toUpperCase();
  if (cleaned.length !== 8 || !/^[A-Z0-9]{8}$/.test(cleaned)) {
    throw new Error('Pairing userCode must be 8 alphanumeric characters (XXXX-XXXX)');
  }
  return cleaned.slice(0, 4) + '-' + cleaned.slice(4, 8);
}

function normalizeOrigin(rawUrl) {
  var trimmed = String(rawUrl || '').trim().replace(/\/+$/, '');
  if (!trimmed) throw new Error('Multipass endpoint URL is empty');
  var withScheme = /^https?:\/\//i.test(trimmed) ? trimmed : ('https://' + trimmed);
  var parsed = httpClient.parseUrl(withScheme);
  if (!parsed || !parsed.hostname) throw new Error('Multipass URL missing host');
  var origin = parsed.protocol + '//' + parsed.hostname;
  if (parsed.port) origin += ':' + parsed.port;
  return origin;
}

function hkdfSha256(ikm, salt, info, length) {
  var actualSalt = (salt && salt.length > 0) ? salt : Buffer.alloc(32, 0);
  var prk = crypto.createHmac('sha256', actualSalt).update(ikm).digest();
  var okm = [];
  var prev = Buffer.alloc(0);
  var totalLen = 0;
  var counter = 1;
  while (totalLen < length) {
    var hmac = crypto.createHmac('sha256', prk);
    hmac.update(prev);
    if (info && info.length > 0) hmac.update(info);
    hmac.update(Buffer.from([counter]));
    prev = hmac.digest();
    okm.push(prev);
    totalLen += prev.length;
    counter++;
  }
  return Buffer.concat(okm).slice(0, length);
}

function aes256GcmEncrypt(key32, iv12, plaintextBuf, aadBuf) {
  var cipher = crypto.createCipheriv('aes-256-gcm', key32, iv12);
  if (aadBuf && aadBuf.length > 0) cipher.setAAD(aadBuf);
  var enc = Buffer.concat([cipher.update(plaintextBuf), cipher.final()]);
  var tag = cipher.getAuthTag();
  return Buffer.concat([enc, tag]);
}

function aes256GcmDecrypt(key32, iv12, ctWithTagBuf, aadBuf) {
  if (!Buffer.isBuffer(ctWithTagBuf) || ctWithTagBuf.length < 16) {
    throw new Error('Ciphertext too short for AES-256-GCM tag');
  }
  var ct = ctWithTagBuf.slice(0, ctWithTagBuf.length - 16);
  var tag = ctWithTagBuf.slice(ctWithTagBuf.length - 16);
  var decipher = crypto.createDecipheriv('aes-256-gcm', key32, iv12);
  if (aadBuf && aadBuf.length > 0) decipher.setAAD(aadBuf);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ct), decipher.final()]);
}

function generateEphemeralP256KeyPair() {
  var ecdh = crypto.createECDH('prime256v1');
  ecdh.generateKeys();
  var pubBytes = ecdh.getPublicKey(null, 'uncompressed');
  if (pubBytes.length !== 65 || pubBytes[0] !== 0x04) {
    throw new Error('Unexpected P-256 public key format');
  }
  return {
    ecdh: ecdh,
    publicKeyB64Url: httpClient.b64UrlEncode(pubBytes)
  };
}

function wrapPairedVaultMasterKey(vmkBuf, peerPubB64Url, userCode, keySlots) {
  if (!Buffer.isBuffer(vmkBuf) || vmkBuf.length !== 32) {
    throw new Error('VMK must be 32 bytes');
  }
  var normalizedCode = normalizeUserCode(userCode);
  var peerBytes = httpClient.b64UrlDecodeToBuffer(peerPubB64Url);
  if (peerBytes.length !== 65 || peerBytes[0] !== 0x04) {
    throw new Error('Peer P-256 public key must be 65-byte uncompressed SEC1');
  }
  var eph = generateEphemeralP256KeyPair();
  var shared = eph.ecdh.computeSecret(peerBytes);
  var kek = hkdfSha256(shared, Buffer.from(normalizedCode, 'utf8'), PAIR_WRAP_HKDF_INFO, 32);
  var iv = crypto.randomBytes(12);
  var aad = Buffer.from('pair:' + normalizedCode, 'utf8');
  var ct = aes256GcmEncrypt(kek, iv, vmkBuf, aad);
  var out = {
    algorithm: PAIR_WRAP_ALGO,
    ephemeralPublicKey: eph.publicKeyB64Url,
    iv: httpClient.b64UrlEncode(iv),
    ciphertext: httpClient.b64UrlEncode(ct)
  };
  if (keySlots) out.keySlots = keySlots;
  return out;
}

function unwrapPairedVaultMasterKey(tvKeyPair, wrapped, userCode) {
  if (!wrapped || wrapped.algorithm !== PAIR_WRAP_ALGO) {
    throw new Error('Unsupported wrappedVaultKey algorithm: ' + (wrapped && wrapped.algorithm));
  }
  var normalizedCode = normalizeUserCode(userCode);
  var peerBytes = httpClient.b64UrlDecodeToBuffer(wrapped.ephemeralPublicKey);
  if (peerBytes.length !== 65 || peerBytes[0] !== 0x04) {
    throw new Error('ephemeralPublicKey must be 65-byte uncompressed SEC1');
  }
  var iv = httpClient.b64UrlDecodeToBuffer(wrapped.iv);
  if (iv.length !== 12) throw new Error('wrapped iv must be 12 bytes');
  var ct = httpClient.b64UrlDecodeToBuffer(wrapped.ciphertext);
  if (ct.length !== 48) throw new Error('wrapped ciphertext must be 48 bytes');

  var shared = tvKeyPair.ecdh.computeSecret(peerBytes);
  var kek = hkdfSha256(shared, Buffer.from(normalizedCode, 'utf8'), PAIR_WRAP_HKDF_INFO, 32);
  var aad = Buffer.from(wrapped.aad || ('pair:' + normalizedCode), 'utf8');
  var pt = aes256GcmDecrypt(kek, iv, ct, aad);
  if (pt.length !== 32) throw new Error('Unwrapped VMK is not 32 bytes');
  return pt;
}

function createPassphraseKeySlot(vmkBuf, passphrase, slotId, iterations) {
  if (!Buffer.isBuffer(vmkBuf) || vmkBuf.length !== 32) {
    throw new Error('VMK must be 32 bytes');
  }
  if (!passphrase || passphrase.length < 8) {
    throw new Error('Vault recovery passphrase must be at least 8 characters');
  }
  var iters = Math.max(100000, Math.min(1000000, Number(iterations) || DEFAULT_PBKDF2_ITERATIONS));
  var salt = crypto.randomBytes(16);
  var iv = crypto.randomBytes(12);
  var kek = crypto.pbkdf2Sync(Buffer.from(passphrase, 'utf8'), salt, iters, 32, 'sha256');
  var wrappedVmk = aes256GcmEncrypt(kek, iv, vmkBuf, Buffer.from(KEYSLOT_AAD, 'utf8'));
  return {
    slotId: String(slotId || 'primary-passphrase'),
    type: 'pbkdf2-sha256',
    kdf: {
      algorithm: 'PBKDF2-SHA256',
      iterations: iters,
      salt: httpClient.b64UrlEncode(salt)
    },
    iv: httpClient.b64UrlEncode(iv),
    aad: KEYSLOT_AAD,
    wrappedVmk: httpClient.b64UrlEncode(wrappedVmk)
  };
}

function unwrapVmkFromKeySlots(keySlots, passphrase) {
  if (!Array.isArray(keySlots) || keySlots.length === 0) {
    throw new Error('No keySlots present on v2 document');
  }
  for (var i = 0; i < keySlots.length; i++) {
    var slot = keySlots[i];
    if (!slot || slot.type !== 'pbkdf2-sha256' || !slot.kdf || slot.kdf.algorithm !== 'PBKDF2-SHA256') continue;
    var iters = Number(slot.kdf.iterations);
    if (isNaN(iters) || iters < 100000 || iters > 1000000) continue;
    try {
      var salt = httpClient.b64UrlDecodeToBuffer(slot.kdf.salt);
      var iv = httpClient.b64UrlDecodeToBuffer(slot.iv);
      var ct = httpClient.b64UrlDecodeToBuffer(slot.wrappedVmk);
      if (salt.length < 16 || iv.length !== 12 || ct.length !== 48) continue;
      var kek = crypto.pbkdf2Sync(Buffer.from(passphrase, 'utf8'), salt, iters, 32, 'sha256');
      var pt = aes256GcmDecrypt(kek, iv, ct, Buffer.from(slot.aad || KEYSLOT_AAD, 'utf8'));
      if (pt.length === 32) return pt;
    } catch (e) {}
  }
  throw new Error('Invalid recovery passphrase for all keySlots');
}

function encryptV2WithVmk(payload, vmkBuf, keySlots) {
  if (!Buffer.isBuffer(vmkBuf) || vmkBuf.length !== 32) throw new Error('VMK must be 32 bytes');
  if (!Array.isArray(keySlots) || keySlots.length === 0 || keySlots.length > 5) {
    throw new Error('v2 vault document requires 1..5 keySlots');
  }
  var plaintext = Buffer.from(JSON.stringify(payload), 'utf8');
  var hkdfSalt = crypto.randomBytes(16);
  var iv = crypto.randomBytes(12);
  var kEnc = hkdfSha256(vmkBuf, hkdfSalt, MEDIA_SYNC_V2_INFO, 32);
  var ct = aes256GcmEncrypt(kEnc, iv, plaintext, Buffer.from(MEDIA_SYNC_V2_AAD, 'utf8'));
  return {
    schemaVersion: 2,
    mode: 'e2ee-aes-256-gcm',
    keySlots: keySlots,
    envelope: {
      hkdfSalt: httpClient.b64UrlEncode(hkdfSalt),
      cipher: 'AES-256-GCM',
      iv: httpClient.b64UrlEncode(iv),
      aad: MEDIA_SYNC_V2_AAD,
      ciphertext: httpClient.b64UrlEncode(ct)
    }
  };
}

function decryptV2WithVmk(doc, vmkBuf) {
  if (!Buffer.isBuffer(vmkBuf) || vmkBuf.length !== 32) throw new Error('VMK must be 32 bytes');
  if (!doc || doc.schemaVersion !== 2 || doc.mode !== 'e2ee-aes-256-gcm' || !doc.envelope) {
    throw new Error('Not a schemaVersion 2 E2EE document');
  }
  var hkdfSalt = httpClient.b64UrlDecodeToBuffer(doc.envelope.hkdfSalt);
  var iv = httpClient.b64UrlDecodeToBuffer(doc.envelope.iv);
  var ct = httpClient.b64UrlDecodeToBuffer(doc.envelope.ciphertext);
  if (hkdfSalt.length < 16 || iv.length !== 12 || ct.length <= 16) {
    throw new Error('Malformed v2 envelope lengths');
  }
  var kEnc = hkdfSha256(vmkBuf, hkdfSalt, MEDIA_SYNC_V2_INFO, 32);
  var pt = aes256GcmDecrypt(kEnc, iv, ct, Buffer.from(doc.envelope.aad || MEDIA_SYNC_V2_AAD, 'utf8'));
  return JSON.parse(pt.toString('utf8'));
}

function decryptAnyDocument(docVal, vmkBuf, passphrase) {
  var schemaVersion = Number((docVal && docVal.schemaVersion) || 1);
  if (schemaVersion === 2) {
    if (vmkBuf) {
      try {
        var payload = decryptV2WithVmk(docVal, vmkBuf);
        return { payload: payload, vmk: vmkBuf, keySlots: docVal.keySlots || [] };
      } catch (e) {}
    }
    if (passphrase && passphrase.length > 0) {
      var unwrapped = unwrapVmkFromKeySlots(docVal.keySlots || [], passphrase);
      var payload2 = decryptV2WithVmk(docVal, unwrapped);
      return { payload: payload2, vmk: unwrapped, keySlots: docVal.keySlots || [] };
    }
    var err = new Error('VAULT_LOCKED: Multipass v2 vault requires paired VMK or recovery passphrase');
    err.vaultLocked = true;
    throw err;
  }

  var mode = String((docVal && docVal.mode) || 'server-at-rest');
  if (mode === 'server-at-rest') {
    return { payload: (docVal && docVal.payload) || {}, vmk: null, keySlots: [] };
  }
  if (mode === 'e2ee-aes-256-gcm') {
    if (!passphrase) {
      var err2 = new Error('VAULT_LOCKED: Legacy v1 E2EE vault requires passphrase');
      err2.vaultLocked = true;
      throw err2;
    }
    var kdf = docVal.kdf || {};
    var iters = Number(kdf.iterations || DEFAULT_PBKDF2_ITERATIONS);
    var salt = httpClient.b64UrlDecodeToBuffer(kdf.salt);
    var iv = httpClient.b64UrlDecodeToBuffer(docVal.iv);
    var ct = httpClient.b64UrlDecodeToBuffer(docVal.ciphertext);
    var aad = Buffer.from(docVal.aad || MEDIA_SYNC_V1_AAD, 'utf8');
    var key = crypto.pbkdf2Sync(Buffer.from(passphrase, 'utf8'), salt, iters, 32, 'sha256');
    var pt = aes256GcmDecrypt(key, iv, ct, aad);
    return { payload: JSON.parse(pt.toString('utf8')), vmk: null, keySlots: [] };
  }
  throw new Error('Unknown Multipass vault mode: ' + mode);
}

// ─────────────────────────────────────────────────────────────────────────────
// Deterministic LWW / CRDT Merge
// ─────────────────────────────────────────────────────────────────────────────

function mergeMediaSyncPayloads(remote, local) {
  remote = remote || {};
  local = local || {};

  // 1. Stremio Auth
  var stremioAuth = null;
  if (remote.stremioAuth && local.stremioAuth) {
    var rTs = Math.max(remote.stremioAuth.updatedAtMs || 0, remote.stremioAuth.revokedAtMs || 0);
    var lTs = Math.max(local.stremioAuth.updatedAtMs || 0, local.stremioAuth.revokedAtMs || 0);
    stremioAuth = lTs >= rTs ? local.stremioAuth : remote.stremioAuth;
  } else {
    stremioAuth = remote.stremioAuth || local.stremioAuth || null;
  }

  // 2. Addons union
  var addonsMap = {};
  var allAddons = (remote.addons || []).concat(local.addons || []);
  for (var i = 0; i < allAddons.length; i++) {
    var a = allAddons[i];
    if (!a || !a.transportUrl) continue;
    var aKey = String(a.transportUrl).trim().replace(/\/+$/, '').toLowerCase();
    if (!addonsMap[aKey] || (a.addedAtMs || 0) >= (addonsMap[aKey].addedAtMs || 0)) {
      addonsMap[aKey] = a;
    }
  }
  var addons = Object.keys(addonsMap).sort().map(function (k) { return addonsMap[k]; });

  // 3. Watch Progress LWW
  var progMap = {};
  var allProg = (remote.watchProgress || []).concat(local.watchProgress || []);
  for (var j = 0; j < allProg.length; j++) {
    var w = allProg[j];
    if (!w || !w.itemId) continue;
    if (!progMap[w.itemId] || (w.updatedAtMs || 0) >= (progMap[w.itemId].updatedAtMs || 0)) {
      progMap[w.itemId] = w;
    }
  }
  var watchProgress = Object.keys(progMap).map(function (k) { return progMap[k]; });
  watchProgress.sort(function (a, b) { return (b.updatedAtMs || 0) - (a.updatedAtMs || 0); });
  watchProgress = watchProgress.slice(0, 60);

  // 4. Favorites union
  var favMap = {};
  var allFav = (remote.favorites || []).concat(local.favorites || []);
  for (var f = 0; f < allFav.length; f++) {
    var fv = allFav[f];
    if (!fv || !fv.itemId) continue;
    if (!favMap[fv.itemId] || (fv.addedAtMs || 0) >= (favMap[fv.itemId].addedAtMs || 0)) {
      favMap[fv.itemId] = fv;
    }
  }
  var favorites = Object.keys(favMap).map(function (k) { return favMap[k]; });
  favorites.sort(function (a, b) { return (b.addedAtMs || 0) - (a.addedAtMs || 0); });

  // 5. Jellyfin Servers + deviceTokens union
  var jfMap = {};
  var allJf = (remote.jellyfinServers || []).concat(local.jellyfinServers || []);
  for (var s = 0; s < allJf.length; s++) {
    var srv = allJf[s];
    if (!srv || !srv.serverId) continue;
    var prev = jfMap[srv.serverId];
    if (!prev) {
      jfMap[srv.serverId] = Object.assign({}, srv, {
        deviceTokens: Object.assign({}, srv.deviceTokens || {})
      });
    } else {
      var prevTs = Math.max(prev.updatedAtMs || 0, prev.removedAtMs || 0);
      var curTs = Math.max(srv.updatedAtMs || 0, srv.removedAtMs || 0);
      var mergedSrv = Object.assign({}, curTs >= prevTs ? srv : prev);
      mergedSrv.deviceTokens = Object.assign({}, prev.deviceTokens || {}, srv.deviceTokens || {});
      if (!mergedSrv.bootstrapToken) mergedSrv.bootstrapToken = srv.bootstrapToken || prev.bootstrapToken || '';
      jfMap[srv.serverId] = mergedSrv;
    }
  }
  var jellyfinServers = Object.keys(jfMap).sort().map(function (k) { return jfMap[k]; });

  // 6. YouTube Accounts LWW
  var ytMap = {};
  var allYt = (remote.youtubeAccounts || []).concat(local.youtubeAccounts || []);
  for (var y = 0; y < allYt.length; y++) {
    var ya = allYt[y];
    if (!ya || !ya.accountId) continue;
    var pYa = ytMap[ya.accountId];
    var pYtTs = pYa ? Math.max(pYa.updatedAtMs || 0, pYa.removedAtMs || 0) : -1;
    var cYtTs = Math.max(ya.updatedAtMs || 0, ya.removedAtMs || 0);
    if (cYtTs >= pYtTs) ytMap[ya.accountId] = ya;
  }
  var youtubeAccounts = Object.keys(ytMap).sort().map(function (k) { return ytMap[k]; });

  // 7. Twitch per-device map
  var twitchByDevice = Object.assign({}, remote.twitchByDevice || {});
  var locTw = local.twitchByDevice || {};
  var locTwKeys = Object.keys(locTw);
  for (var t = 0; t < locTwKeys.length; t++) {
    var devId = locTwKeys[t];
    if (!twitchByDevice[devId] || (locTw[devId].updatedAtMs || 0) >= (twitchByDevice[devId].updatedAtMs || 0)) {
      twitchByDevice[devId] = locTw[devId];
    }
  }

  // 8. Creator Follows LWW
  var folMap = {};
  var allFol = (remote.creatorFollows || []).concat(local.creatorFollows || []);
  for (var c = 0; c < allFol.length; c++) {
    var fl = allFol[c];
    if (!fl || !fl.platform || !fl.channelId) continue;
    var fKey = fl.platform + ':' + fl.channelId;
    var pf = folMap[fKey];
    var pfTs = pf ? Math.max(pf.addedAtMs || 0, pf.removedAtMs || 0) : -1;
    var cfTs = Math.max(fl.addedAtMs || 0, fl.removedAtMs || 0);
    if (cfTs >= pfTs) folMap[fKey] = fl;
  }
  var creatorFollows = Object.keys(folMap).sort().map(function (k) { return folMap[k]; });

  // 9. Streaming Preferences LWW
  var streamingPreferences = null;
  if (remote.streamingPreferences && local.streamingPreferences) {
    streamingPreferences = (local.streamingPreferences.updatedAtMs || 0) >= (remote.streamingPreferences.updatedAtMs || 0)
      ? local.streamingPreferences
      : remote.streamingPreferences;
  } else {
    streamingPreferences = remote.streamingPreferences || local.streamingPreferences || null;
  }

  return {
    version: 2,
    exportedAt: Date.now(),
    source: local.source || 'brootlite-webos',
    stremioAuth: stremioAuth,
    addons: addons,
    watchProgress: watchProgress,
    favorites: favorites,
    jellyfinServers: jellyfinServers,
    youtubeAccounts: youtubeAccounts,
    twitchByDevice: twitchByDevice,
    creatorFollows: creatorFollows,
    streamingPreferences: streamingPreferences
  };
}

function createMultipassClient(store, stremioClient, jellyfinClient) {
  var pendingKeypairs = {};

  function pairStart(params) {
    params = params || {};
    var origin = normalizeOrigin(params.endpointUrl);
    var deviceName = String(params.deviceName || 'BrootLite LG webOS TV').trim() || 'BrootLite LG webOS TV';

    return httpClient.postJson(origin + '/v1/media-sync/pair/start', {
      deviceName: deviceName,
      requestedScope: 'media_sync:readwrite'
    }).then(function (resp) {
      if (!resp.ok || !resp.data) {
        throw new Error('Multipass pair/start failed (' + resp.status + '): ' + (resp.text || ''));
      }
      var data = resp.data;
      if (!data.pairingId || !data.pollSecret || !data.userCode) {
        throw new Error('Multipass pair/start response missing required fields');
      }
      var rawVerify = data.verificationUrl || '/networks#media-sync';
      var absVerify = /^https?:\/\//i.test(rawVerify)
        ? rawVerify
        : (origin + (rawVerify.charAt(0) === '/' ? '' : '/') + rawVerify);
      var baseNoFrag = absVerify.split('#')[0];

      var kp = generateEphemeralP256KeyPair();
      var qrVerificationUrl = baseNoFrag + '#pk=' + kp.publicKeyB64Url;

      pendingKeypairs[data.pairingId] = { keypair: kp, userCode: data.userCode };

      return {
        originUrl: origin,
        pairingId: data.pairingId,
        pollSecret: data.pollSecret,
        userCode: data.userCode,
        verificationUrl: absVerify,
        qrVerificationUrl: qrVerificationUrl,
        tvPublicKeyB64Url: kp.publicKeyB64Url,
        expiresInSec: data.expiresIn || 600,
        intervalSec: data.intervalSeconds || 3
      };
    });
  }

  function pairPoll(params) {
    params = params || {};
    var origin = normalizeOrigin(params.originUrl || params.endpointUrl);
    var pairingId = String(params.pairingId || '');
    var pollSecret = String(params.pollSecret || '');
    var userCodeFallback = String(params.userCode || '');

    return httpClient.postJson(origin + '/v1/media-sync/pair/poll', {
      pairingId: pairingId,
      pollSecret: pollSecret
    }).then(function (resp) {
      if (resp.status === 404 || resp.status === 410) {
        delete pendingKeypairs[pairingId];
        return { status: 'expired' };
      }
      if (!resp.ok || !resp.data) {
        throw new Error('Multipass pair/poll HTTP ' + resp.status);
      }
      var data = resp.data;
      var status = String(data.status || 'pending');
      if (status === 'pending') return { status: 'pending' };
      if (status === 'denied' || status === 'expired') {
        delete pendingKeypairs[pairingId];
        return { status: status };
      }
      if (status === 'approved') {
        if (!data.deviceToken) throw new Error('Multipass approved poll missing deviceToken');
        var e2eeUnlocked = false;
        var pending = pendingKeypairs[pairingId];
        delete pendingKeypairs[pairingId];
        if (pending && data.wrappedVaultKey) {
          var code = pending.userCode || userCodeFallback;
          var vmk = unwrapPairedVaultMasterKey(pending.keypair, data.wrappedVaultKey, code);
          store.sealMultipassVmk(vmk);
          e2eeUnlocked = true;
        }
        store.saveMultipassProfile({
          endpointUrl: origin,
          pairToken: data.deviceToken,
          vaultUnlocked: e2eeUnlocked || Boolean(store.unsealMultipassVmk()),
          deviceId: data.deviceId || '',
          deviceName: data.deviceName || 'BrootLite LG webOS TV',
          handle: '',
          lastSyncedMs: Date.now(),
          lastSyncedRevision: 0
        });
        return {
          status: 'approved',
          deviceId: data.deviceId || '',
          deviceName: data.deviceName || 'BrootLite LG webOS TV',
          scope: data.scope || 'media_sync:readwrite',
          e2eeUnlocked: e2eeUnlocked
        };
      }
      throw new Error('Unexpected Multipass pairing status: ' + status);
    });
  }

  function exportLocalSnapshot() {
    var raw = store.getStateRaw();
    var instId = raw.installationId;
    var st = store.getStremioAuth();
    var stremioAuth = (st.authKey || st.revokedAtMs) ? {
      authKey: st.authKey,
      email: st.email,
      updatedAtMs: st.updatedAtMs,
      revokedAtMs: st.revokedAtMs || undefined
    } : null;

    var addons = store.getAddons().map(function (a) {
      return {
        transportUrl: a.transportUrl,
        name: a.name,
        id: a.id,
        addedAtMs: 1
      };
    });

    var jfServers = store.getJellyfinServers().map(function (s) {
      var devTokens = {};
      if (s.accessToken) devTokens[instId] = s.accessToken;
      return {
        serverId: s.serverId,
        serverName: s.serverName,
        lanUrl: s.lanUrl || '',
        remoteUrl: s.remoteUrl || '',
        userId: s.userId || '',
        username: s.username || '',
        accessToken: s.accessToken || '',
        bootstrapToken: s.bootstrapToken || '',
        deviceTokens: devTokens,
        updatedAtMs: s.updatedAtMs || 0
      };
    });
    var jfTombs = store.getJellyfinTombstones();
    Object.keys(jfTombs).forEach(function (sid) {
      jfServers.push({
        serverId: sid,
        serverName: '',
        updatedAtMs: jfTombs[sid],
        removedAtMs: jfTombs[sid]
      });
    });

    var ytAccs = store.getYoutubeAccounts().map(function (a) {
      return {
        accountId: a.accountId,
        accountName: a.accountName,
        channelHandle: a.channelHandle || '',
        avatarUrl: a.avatarUrl || '',
        pageId: a.pageId || '',
        refreshToken: a.refreshToken || '',
        oauthClientId: a.oauthClientId || '',
        oauthClientSecret: a.oauthClientSecret || '',
        updatedAtMs: a.updatedAtMs || 0
      };
    });
    var ytTombs = store.getYoutubeTombstones();
    Object.keys(ytTombs).forEach(function (aid) {
      ytAccs.push({
        accountId: aid,
        accountName: '',
        updatedAtMs: ytTombs[aid],
        removedAtMs: ytTombs[aid]
      });
    });

    var twitchByDevice = {};
    var tw = store.getTwitchProfile();
    if (tw) twitchByDevice[instId] = tw;

    return {
      version: 2,
      exportedAt: Date.now(),
      source: 'brootlite-webos:' + instId,
      stremioAuth: stremioAuth,
      addons: addons,
      watchProgress: store.getWatchHistory(),
      favorites: store.getFavorites(),
      jellyfinServers: jfServers,
      youtubeAccounts: ytAccs,
      twitchByDevice: twitchByDevice,
      creatorFollows: store.getCreatorFollows(),
      streamingPreferences: {
        swarmFilter: raw.swarmFilter || 'SAFE_ONLY',
        torrentConfig: raw.torrentConfig,
        torznabUrl: raw.torznabUrl || '',
        torznabApiKey: raw.torznabApiKey || '',
        updatedAtMs: raw.streamingPrefsUpdatedAtMs || 0
      }
    };
  }

  function applyMergedPayload(merged, revision) {
    var instId = store.getStateRaw().installationId;
    var report = {
      stremioLinked: false,
      addonsImported: 0,
      jellyfinServersSynced: 0,
      youtubeAccountsSynced: 0,
      twitchSynced: false,
      creatorFollowsSynced: 0,
      watchHistoryMerged: 0,
      favoritesMerged: 0,
      revision: revision || 0,
      vaultLockedNeedsPassphrase: false
    };

    if (merged.stremioAuth) {
      var sa = merged.stremioAuth;
      if ((sa.revokedAtMs || 0) > (sa.updatedAtMs || 0)) {
        store.setStremioAuth(null, '', sa.revokedAtMs);
      } else if (sa.authKey) {
        store.setStremioAuth(sa.authKey, sa.email || '', sa.updatedAtMs);
        report.stremioLinked = true;
      }
    }

    var existingUrls = {};
    store.getAddons().forEach(function (a) {
      existingUrls[String(a.transportUrl || '').replace(/\/+$/, '').toLowerCase()] = true;
    });
    var addonTasks = (merged.addons || []).map(function (ar) {
      var norm = String((ar && ar.transportUrl) || '').trim().replace(/\/+$/, '').toLowerCase();
      if (!norm || existingUrls[norm]) return Promise.resolve();
      return stremioClient.installAddon(ar.transportUrl).then(function () {
        report.addonsImported++;
      }).catch(function () {});
    });

    store.replaceWatchHistory(merged.watchProgress || []);
    report.watchHistoryMerged = (merged.watchProgress || []).length;
    store.replaceFavorites(merged.favorites || []);
    report.favoritesMerged = (merged.favorites || []).length;

    var jfTasks = (merged.jellyfinServers || []).map(function (srv) {
      if ((srv.removedAtMs || 0) > (srv.updatedAtMs || 0)) {
        store.removeJellyfinServer(srv.serverId);
        return Promise.resolve();
      }
      var myToken = (srv.deviceTokens && srv.deviceTokens[instId]) || '';
      var mintPromise = (!myToken && srv.bootstrapToken && (srv.lanUrl || srv.remoteUrl))
        ? jellyfinClient.mintDeviceTokenViaBootstrap(srv.lanUrl || srv.remoteUrl, srv.bootstrapToken)
            .then(function (minted) { return minted.accessToken; })
            .catch(function () { return ''; })
        : Promise.resolve(myToken);

      return mintPromise.then(function (tok) {
        var finalTok = tok || srv.accessToken || '';
        if (!finalTok) return;
        store.upsertJellyfinServer({
          serverId: srv.serverId,
          serverName: srv.serverName || 'Jellyfin',
          lanUrl: srv.lanUrl || '',
          remoteUrl: srv.remoteUrl || '',
          userId: srv.userId || '',
          username: srv.username || '',
          accessToken: finalTok,
          bootstrapToken: srv.bootstrapToken || '',
          lastVerifiedMs: Date.now(),
          updatedAtMs: srv.updatedAtMs || Date.now()
        });
        report.jellyfinServersSynced++;
      });
    });

    (merged.youtubeAccounts || []).forEach(function (yt) {
      if ((yt.removedAtMs || 0) > (yt.updatedAtMs || 0)) {
        store.removeYoutubeAccount(yt.accountId);
        return;
      }
      if (yt.refreshToken) {
        store.upsertYoutubeAccount({
          accountId: yt.accountId,
          accountName: yt.accountName || 'YouTube Account',
          channelHandle: yt.channelHandle || '',
          avatarUrl: yt.avatarUrl || '',
          pageId: yt.pageId || '',
          refreshToken: yt.refreshToken,
          accessToken: '',
          expiresAtMs: 0,
          oauthClientId: yt.oauthClientId || '',
          oauthClientSecret: yt.oauthClientSecret || '',
          updatedAtMs: yt.updatedAtMs || Date.now()
        });
        report.youtubeAccountsSynced++;
      }
    });

    var twMap = merged.twitchByDevice || {};
    if (twMap[instId]) {
      store.setTwitchProfile(twMap[instId]);
      report.twitchSynced = true;
    } else if (!store.getTwitchProfile()) {
      var twKeys = Object.keys(twMap);
      if (twKeys.length > 0) {
        store.setTwitchProfile(twMap[twKeys[0]]);
        report.twitchSynced = true;
      }
    }

    store.replaceCreatorFollows(merged.creatorFollows || []);
    report.creatorFollowsSynced = (merged.creatorFollows || []).length;

    if (merged.streamingPreferences) {
      var prefs = merged.streamingPreferences;
      if ((prefs.updatedAtMs || 0) > (store.getStateRaw().streamingPrefsUpdatedAtMs || 0)) {
        store.patchState({
          swarmFilter: prefs.swarmFilter,
          torrentConfig: prefs.torrentConfig,
          torznabUrl: prefs.torznabUrl,
          torznabApiKey: prefs.torznabApiKey
        });
      }
    }

    return Promise.all(addonTasks.concat(jfTasks)).then(function () {
      return report;
    });
  }

  function sync(params) {
    params = params || {};
    var mode = String(params.mode || 'pull').toLowerCase();
    var passphrase = params.passphrase || params.newPassphrase || null;
    var prof = store.getMultipassProfile();
    if (!prof.endpointUrl || !prof.pairToken) {
      return Promise.reject(new Error('Multipass is not paired yet'));
    }
    var origin = normalizeOrigin(prof.endpointUrl);
    var syncUrl = origin + '/v1/media-sync';

    function doPull() {
      return httpClient.getJson(syncUrl, {
        headers: { Authorization: 'Bearer ' + prof.pairToken },
        timeoutMs: 12000
      }).then(function (root) {
        var revision = Number((root && root.revision) || 0);
        if (root && root.handle) prof.handle = root.handle;
        if (!root || !root.exists || !root.document) {
          prof.lastSyncedMs = Date.now();
          prof.lastSyncedRevision = revision;
          store.saveMultipassProfile(prof);
          return {
            report: { revision: revision, vaultLockedNeedsPassphrase: false },
            revision: revision,
            vaultLockedNeedsPassphrase: false
          };
        }

        var vmk = store.unsealMultipassVmk();
        var dec;
        try {
          dec = decryptAnyDocument(root.document, vmk, passphrase);
        } catch (err) {
          if (err && err.vaultLocked) {
            return {
              report: { revision: revision, vaultLockedNeedsPassphrase: true },
              revision: revision,
              vaultLockedNeedsPassphrase: true
            };
          }
          throw err;
        }
        if (dec.vmk) store.sealMultipassVmk(dec.vmk);

        var local = exportLocalSnapshot();
        var merged = mergeMediaSyncPayloads(dec.payload, local);
        return applyMergedPayload(merged, revision).then(function (report) {
          prof.lastSyncedMs = Date.now();
          prof.lastSyncedRevision = revision;
          store.saveMultipassProfile(prof);
          return { report: report, revision: revision, vaultLockedNeedsPassphrase: false };
        });
      });
    }

    function doPush(attempt) {
      if (attempt >= 3) return Promise.reject(new Error('PUT /v1/media-sync exceeded max 409 CAS retries'));
      return httpClient.getJson(syncUrl, {
        headers: { Authorization: 'Bearer ' + prof.pairToken },
        timeoutMs: 12000
      }).then(function (root) {
        var exists = Boolean(root && root.exists && root.document);
        var expectedRev = exists ? Number(root.revision || 0) : 0;
        var local = exportLocalSnapshot();
        var vmk = store.unsealMultipassVmk();
        var keySlots = [];
        var mergedPayload = local;

        if (exists) {
          var dec = decryptAnyDocument(root.document, vmk, passphrase);
          if (dec.vmk) {
            store.sealMultipassVmk(dec.vmk);
            vmk = dec.vmk;
          }
          keySlots = dec.keySlots || [];
          mergedPayload = mergeMediaSyncPayloads(dec.payload, local);
        }

        if (!vmk) {
          vmk = crypto.randomBytes(32);
          store.sealMultipassVmk(vmk);
        }
        if (keySlots.length === 0) {
          keySlots.push(createPassphraseKeySlot(vmk, passphrase, 'primary-passphrase', DEFAULT_PBKDF2_ITERATIONS));
        }
        var docV2 = encryptV2WithVmk(mergedPayload, vmk, keySlots);
        return httpClient.request(syncUrl, {
          method: 'PUT',
          headers: { Authorization: 'Bearer ' + prof.pairToken },
          json: { expectedRevision: expectedRev, document: docV2 },
          responseType: 'json'
        }).then(function (putResp) {
          if (putResp.status === 409) return doPush(attempt + 1);
          if (!putResp.ok) throw new Error('PUT /v1/media-sync failed (' + putResp.status + ')');
          var newRev = (putResp.data && putResp.data.revision) || (expectedRev + 1);
          prof.lastSyncedMs = Date.now();
          prof.lastSyncedRevision = newRev;
          store.saveMultipassProfile(prof);
          return { revision: newRev };
        });
      });
    }

    if (mode === 'push') {
      return doPush(0);
    }
    if (mode === 'both') {
      return doPull().then(function (pullRes) {
        if (pullRes.vaultLockedNeedsPassphrase) return pullRes;
        return doPush(0).then(function (pushRes) {
          return Object.assign({}, pullRes, { revision: pushRes.revision });
        });
      });
    }
    return doPull();
  }

  return {
    pairStart: pairStart,
    pairPoll: pairPoll,
    sync: sync,
    exportLocalSnapshot: exportLocalSnapshot
  };
}

module.exports = {
  PAIR_WRAP_ALGO: PAIR_WRAP_ALGO,
  DEFAULT_PBKDF2_ITERATIONS: DEFAULT_PBKDF2_ITERATIONS,
  normalizeUserCode: normalizeUserCode,
  normalizeOrigin: normalizeOrigin,
  hkdfSha256: hkdfSha256,
  generateEphemeralP256KeyPair: generateEphemeralP256KeyPair,
  wrapPairedVaultMasterKey: wrapPairedVaultMasterKey,
  unwrapPairedVaultMasterKey: unwrapPairedVaultMasterKey,
  createPassphraseKeySlot: createPassphraseKeySlot,
  unwrapVmkFromKeySlots: unwrapVmkFromKeySlots,
  encryptV2WithVmk: encryptV2WithVmk,
  decryptV2WithVmk: decryptV2WithVmk,
  decryptAnyDocument: decryptAnyDocument,
  mergeMediaSyncPayloads: mergeMediaSyncPayloads,
  createMultipassClient: createMultipassClient
};
