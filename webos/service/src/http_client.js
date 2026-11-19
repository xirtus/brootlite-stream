'use strict';

var http = require('http');
var https = require('https');
var urlMod = require('url');
var zlib = require('zlib');
var crypto = require('crypto');
var querystring = require('querystring');

var DEFAULT_UA = 'Mozilla/5.0 (SMART-TV; Linux; WebOS) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
var DESKTOP_UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36';

function b64UrlEncode(input) {
  var buf = Buffer.isBuffer(input) ? input : Buffer.from(String(input || ''), 'utf8');
  return buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function b64UrlDecodeToBuffer(str) {
  var s = String(str || '').trim().replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4 !== 0) s += '=';
  return Buffer.from(s, 'base64');
}

function b64UrlDecodeToString(str) {
  return b64UrlDecodeToBuffer(str).toString('utf8');
}

function uuidV4() {
  var bytes = crypto.randomBytes(16);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  var hex = bytes.toString('hex');
  return (
    hex.slice(0, 8) + '-' +
    hex.slice(8, 12) + '-' +
    hex.slice(12, 16) + '-' +
    hex.slice(16, 20) + '-' +
    hex.slice(20, 32)
  );
}

function parseUrl(raw, parseQuery) {
  var s = String(raw || '');
  if (typeof urlMod.URL === 'function') {
    var u = new urlMod.URL(s, 'http://localhost');
    var query = {};
    if (parseQuery && u.searchParams) {
      u.searchParams.forEach(function (val, key) {
        query[key] = val;
      });
    }
    return {
      protocol: u.protocol,
      hostname: u.hostname,
      host: u.host,
      port: u.port,
      pathname: u.pathname,
      search: u.search,
      query: query
    };
  }
  return urlMod.parse(s, Boolean(parseQuery));
}

function resolveUrl(baseUrl, relativeUrl) {
  var rel = String(relativeUrl || '').trim();
  if (!rel) return '';
  if (/^https?:\/\//i.test(rel)) return rel;
  if (!baseUrl) return rel;
  try {
    if (typeof urlMod.URL === 'function') {
      return new urlMod.URL(rel, baseUrl).toString();
    }
    return urlMod.resolve(baseUrl, rel);
  } catch (e) {
    return rel;
  }
}

function decodeXmlEntities(str) {
  if (!str) return '';
  var s = String(str)
    .replace(/^<!\[CDATA\[([\s\S]*?)\]\]>$/, '$1')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1');
  return s
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, function (_, dec) {
      var code = parseInt(dec, 10);
      return isNaN(code) ? _ : String.fromCharCode(code);
    })
    .replace(/&#x([0-9a-fA-F]+);/g, function (_, hex) {
      var code = parseInt(hex, 16);
      return isNaN(code) ? _ : String.fromCharCode(code);
    });
}

function normalizeHeaders(hdrs) {
  var out = {};
  if (!hdrs || typeof hdrs !== 'object') return out;
  var keys = Object.keys(hdrs);
  for (var i = 0; i < keys.length; i++) {
    var k = keys[i];
    if (hdrs[k] !== undefined && hdrs[k] !== null) {
      out[k] = String(hdrs[k]);
    }
  }
  return out;
}

function hasHeaderCaseInsensitive(headers, name) {
  var target = name.toLowerCase();
  var keys = Object.keys(headers);
  for (var i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === target) return true;
  }
  return false;
}

function getHeaderCaseInsensitive(headers, name) {
  var target = name.toLowerCase();
  var keys = Object.keys(headers);
  for (var i = 0; i < keys.length; i++) {
    if (keys[i].toLowerCase() === target) return headers[keys[i]];
  }
  return '';
}

function decompressStream(res) {
  var enc = String((res.headers && res.headers['content-encoding']) || '').toLowerCase().trim();
  if (enc === 'gzip' || enc === 'x-gzip') {
    return res.pipe(zlib.createGunzip());
  }
  if (enc === 'deflate') {
    return res.pipe(zlib.createInflate());
  }
  if (enc === 'br' && typeof zlib.createBrotliDecompress === 'function') {
    return res.pipe(zlib.createBrotliDecompress());
  }
  return res;
}

function request(targetUrl, opts) {
  opts = opts || {};
  var maxRedirects = typeof opts.maxRedirects === 'number' ? opts.maxRedirects : 5;
  var timeoutMs = typeof opts.timeoutMs === 'number' ? opts.timeoutMs : 15000;
  var method = String(opts.method || 'GET').toUpperCase();
  var responseType = opts.responseType || 'text';

  var payloadBuf = null;
  var headers = normalizeHeaders(opts.headers);

  if (!hasHeaderCaseInsensitive(headers, 'user-agent')) {
    headers['User-Agent'] = opts.desktopUa ? DESKTOP_UA : DEFAULT_UA;
  }
  if (!hasHeaderCaseInsensitive(headers, 'accept-encoding') && responseType !== 'stream') {
    headers['Accept-Encoding'] = 'gzip, deflate';
  }

  if (opts.form) {
    var formStr = typeof opts.form === 'string' ? opts.form : querystring.stringify(opts.form);
    payloadBuf = Buffer.from(formStr, 'utf8');
    if (!hasHeaderCaseInsensitive(headers, 'content-type')) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded';
    }
  } else if (opts.json !== undefined) {
    payloadBuf = Buffer.from(JSON.stringify(opts.json), 'utf8');
    if (!hasHeaderCaseInsensitive(headers, 'content-type')) {
      headers['Content-Type'] = 'application/json';
    }
  } else if (opts.body !== undefined && opts.body !== null) {
    if (Buffer.isBuffer(opts.body)) {
      payloadBuf = opts.body;
    } else if (typeof opts.body === 'object') {
      payloadBuf = Buffer.from(JSON.stringify(opts.body), 'utf8');
      if (!hasHeaderCaseInsensitive(headers, 'content-type')) {
        headers['Content-Type'] = 'application/json';
      }
    } else {
      payloadBuf = Buffer.from(String(opts.body), 'utf8');
    }
  }

  if (payloadBuf && !hasHeaderCaseInsensitive(headers, 'content-length')) {
    headers['Content-Length'] = String(payloadBuf.length);
  }

  function doHop(currentUrl, redirectsLeft, currentMethod, currentBody) {
    return new Promise(function (resolve, reject) {
      var parsed;
      try {
        parsed = parseUrl(currentUrl);
      } catch (err) {
        return reject(err);
      }
      if (!parsed || !parsed.hostname) {
        return reject(new Error('Invalid URL: ' + currentUrl));
      }
      var isHttps = parsed.protocol === 'https:';
      var lib = isHttps ? https : http;
      var reqOpts = {
        protocol: parsed.protocol,
        hostname: parsed.hostname,
        port: parsed.port || (isHttps ? 443 : 80),
        path: (parsed.pathname || '/') + (parsed.search || ''),
        method: currentMethod,
        headers: headers
      };
      if (isHttps && opts.insecureTls) {
        reqOpts.rejectUnauthorized = false;
      }

      var settled = false;
      var req = lib.request(reqOpts, function (res) {
        var status = res.statusCode || 0;
        if (
          (status === 301 || status === 302 || status === 303 || status === 307 || status === 308) &&
          res.headers &&
          res.headers.location &&
          redirectsLeft > 0
        ) {
          res.resume();
          var nextUrl = resolveUrl(currentUrl, res.headers.location);
          var nextMethod = status === 303 || ((status === 301 || status === 302) && currentMethod === 'POST')
            ? 'GET'
            : currentMethod;
          var nextBody = nextMethod === 'GET' ? null : currentBody;
          if (nextMethod === 'GET') {
            delete headers['Content-Length'];
            delete headers['content-length'];
          }
          settled = true;
          return doHop(nextUrl, redirectsLeft - 1, nextMethod, nextBody).then(resolve, reject);
        }

        if (responseType === 'stream') {
          settled = true;
          return resolve({
            status: status,
            ok: status >= 200 && status < 300,
            headers: res.headers || {},
            url: currentUrl,
            stream: res,
            abort: function () {
              try { req.abort(); } catch (e) {}
            }
          });
        }

        var stream = decompressStream(res);
        var chunks = [];
        stream.on('data', function (chunk) {
          chunks.push(chunk);
        });
        stream.on('error', function (err) {
          if (!settled) {
            settled = true;
            reject(err);
          }
        });
        stream.on('end', function () {
          if (settled) return;
          settled = true;
          var buf = Buffer.concat(chunks);
          var text = responseType === 'buffer' ? null : buf.toString('utf8');
          var data = buf;
          if (responseType === 'text') {
            data = text;
          } else if (responseType === 'json') {
            try {
              data = text ? JSON.parse(text) : null;
            } catch (e) {
              return reject(new Error('Invalid JSON from ' + currentUrl + ': ' + e.message));
            }
          }
          resolve({
            status: status,
            ok: status >= 200 && status < 300,
            headers: res.headers || {},
            url: currentUrl,
            buffer: buf,
            text: text,
            data: data
          });
        });
      });

      req.setTimeout(timeoutMs, function () {
        if (!settled) {
          settled = true;
          try { req.abort(); } catch (e) {}
          reject(new Error('HTTP timeout (' + timeoutMs + 'ms) for ' + currentUrl));
        }
      });

      req.on('error', function (err) {
        if (!settled) {
          settled = true;
          reject(err);
        }
      });

      if (currentBody && currentBody.length > 0 && currentMethod !== 'GET' && currentMethod !== 'HEAD') {
        req.write(currentBody);
      }
      req.end();
    });
  }

  return doHop(targetUrl, maxRedirects, method, payloadBuf);
}

function getJson(url, opts) {
  return request(url, Object.assign({}, opts || {}, { method: 'GET', responseType: 'json' })).then(function (r) {
    if (!r.ok) {
      var err = new Error('HTTP ' + r.status + ' for ' + url);
      err.status = r.status;
      err.body = r.text;
      throw err;
    }
    return r.data;
  });
}

function getText(url, opts) {
  return request(url, Object.assign({}, opts || {}, { method: 'GET', responseType: 'text' })).then(function (r) {
    if (!r.ok) {
      var err = new Error('HTTP ' + r.status + ' for ' + url);
      err.status = r.status;
      err.body = r.text;
      throw err;
    }
    return r;
  });
}

function getBuffer(url, opts) {
  return request(url, Object.assign({}, opts || {}, { method: 'GET', responseType: 'buffer' }));
}

function postJson(url, body, opts) {
  return request(url, Object.assign({}, opts || {}, { method: 'POST', json: body, responseType: 'json' }));
}

function postForm(url, formObj, opts) {
  return request(url, Object.assign({}, opts || {}, { method: 'POST', form: formObj, responseType: 'json' }));
}

module.exports = {
  DEFAULT_UA: DEFAULT_UA,
  DESKTOP_UA: DESKTOP_UA,
  b64UrlEncode: b64UrlEncode,
  b64UrlDecodeToBuffer: b64UrlDecodeToBuffer,
  b64UrlDecodeToString: b64UrlDecodeToString,
  uuidV4: uuidV4,
  parseUrl: parseUrl,
  resolveUrl: resolveUrl,
  decodeXmlEntities: decodeXmlEntities,
  getHeaderCaseInsensitive: getHeaderCaseInsensitive,
  request: request,
  getJson: getJson,
  getText: getText,
  getBuffer: getBuffer,
  postJson: postJson,
  postForm: postForm
};
