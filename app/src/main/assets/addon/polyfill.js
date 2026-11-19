// StreamBD add-on runtime (API v1). Native bridges: nativeLog, nativeFetch, nativeSleep,
// nativeCrypto, nativeHtml, nativeCookieGet, nativeCookieSet, nativeStorageGet/Set/Remove, nativeSettings.
(function (g) {
  'use strict';
  var ERR = '\u0000ERR:';
  function unwrap(s) {
    if (typeof s === 'string' && s.indexOf(ERR) === 0) throw new Error(s.substring(ERR.length));
    return s;
  }
  function fmt(args) {
    var out = [];
    for (var i = 0; i < args.length; i++) {
      var m = args[i];
      if (m instanceof Error) out.push(m.stack || String(m));
      else if (typeof m === 'object' && m !== null) { try { out.push(JSON.stringify(m)); } catch (e) { out.push(String(m)); } }
      else out.push(String(m));
    }
    return out.join(' ');
  }
  g.console = {
    log: function () { nativeLog('I', fmt(arguments)); },
    info: function () { nativeLog('I', fmt(arguments)); },
    debug: function () { nativeLog('D', fmt(arguments)); },
    warn: function () { nativeLog('W', fmt(arguments)); },
    error: function () { nativeLog('E', fmt(arguments)); }
  };

  // ---------- base64 (latin1) ----------
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  g.btoa = function (s) {
    s = String(s); var o = '';
    for (var i = 0; i < s.length; i += 3) {
      var a = s.charCodeAt(i), b = s.charCodeAt(i + 1), c = s.charCodeAt(i + 2);
      if (a > 255 || b > 255 || c > 255) throw new Error('btoa: invalid character');
      var n = (a << 16) | ((b || 0) << 8) | (c || 0);
      o += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
        (i + 1 < s.length ? B64[(n >> 6) & 63] : '=') + (i + 2 < s.length ? B64[n & 63] : '=');
    }
    return o;
  };
  g.atob = function (s) {
    s = String(s).replace(/[\s=]+/g, '').replace(/-/g, '+').replace(/_/g, '/');
    var o = '', bits = 0, val = 0;
    for (var i = 0; i < s.length; i++) {
      var v = B64.indexOf(s[i]);
      if (v < 0) throw new Error('atob: invalid character');
      val = (val << 6) | v; bits += 6;
      if (bits >= 8) { bits -= 8; o += String.fromCharCode((val >> bits) & 255); }
    }
    return o;
  };

  // ---------- TextEncoder / TextDecoder (UTF-8) ----------
  function TextEncoder() { this.encoding = 'utf-8'; }
  TextEncoder.prototype.encode = function (str) {
    str = String(str === undefined ? '' : str);
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c >= 0xD800 && c <= 0xDBFF && i + 1 < str.length) {
        var d = str.charCodeAt(i + 1);
        if (d >= 0xDC00 && d <= 0xDFFF) { c = 0x10000 + ((c - 0xD800) << 10) + (d - 0xDC00); i++; }
      }
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 63));
      else if (c < 0x10000) out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      else out.push(0xF0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return new Uint8Array(out);
  };
  function TextDecoder() { this.encoding = 'utf-8'; }
  TextDecoder.prototype.decode = function (buf) {
    if (!buf) return '';
    var b = buf instanceof Uint8Array ? buf : new Uint8Array(buf.buffer ? buf.buffer : buf);
    var s = '', i = 0;
    while (i < b.length) {
      var c = b[i++], cp;
      if (c < 0x80) cp = c;
      else if (c >= 0xC0 && c < 0xE0) cp = ((c & 31) << 6) | (b[i++] & 63);
      else if (c >= 0xE0 && c < 0xF0) { cp = ((c & 15) << 12) | ((b[i] & 63) << 6) | (b[i + 1] & 63); i += 2; }
      else { cp = ((c & 7) << 18) | ((b[i] & 63) << 12) | ((b[i + 1] & 63) << 6) | (b[i + 2] & 63); i += 3; }
      if (cp > 0xFFFF) { cp -= 0x10000; s += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 1023)); }
      else s += String.fromCharCode(cp);
    }
    return s;
  };
  g.TextEncoder = TextEncoder; g.TextDecoder = TextDecoder;

  // ---------- URLSearchParams / URL ----------
  function enc(s) { return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); }); }
  function dec(s) { try { return decodeURIComponent(String(s).replace(/\+/g, ' ')); } catch (e) { return String(s); } }
  function URLSearchParams(init) {
    this._l = [];
    if (init === undefined || init === null) return;
    if (typeof init === 'string') {
      if (init.charAt(0) === '?') init = init.substring(1);
      if (init.length) {
        var parts = init.split('&');
        for (var i = 0; i < parts.length; i++) {
          if (!parts[i]) continue;
          var e = parts[i].indexOf('=');
          this._l.push(e < 0 ? [dec(parts[i]), ''] : [dec(parts[i].substring(0, e)), dec(parts[i].substring(e + 1))]);
        }
      }
    } else if (init instanceof URLSearchParams) {
      this._l = init._l.map(function (p) { return [p[0], p[1]]; });
    } else if (Array.isArray(init)) {
      for (var j = 0; j < init.length; j++) this._l.push([String(init[j][0]), String(init[j][1])]);
    } else if (typeof init === 'object') {
      for (var k in init) if (Object.prototype.hasOwnProperty.call(init, k)) this._l.push([k, String(init[k])]);
    }
  }
  URLSearchParams.prototype.append = function (k, v) { this._l.push([String(k), String(v)]); };
  URLSearchParams.prototype['delete'] = function (k) { k = String(k); this._l = this._l.filter(function (p) { return p[0] !== k; }); };
  URLSearchParams.prototype.get = function (k) { k = String(k); for (var i = 0; i < this._l.length; i++) if (this._l[i][0] === k) return this._l[i][1]; return null; };
  URLSearchParams.prototype.getAll = function (k) { k = String(k); return this._l.filter(function (p) { return p[0] === k; }).map(function (p) { return p[1]; }); };
  URLSearchParams.prototype.has = function (k) { return this.get(k) !== null; };
  URLSearchParams.prototype.set = function (k, v) {
    k = String(k); v = String(v); var found = false, out = [];
    for (var i = 0; i < this._l.length; i++) {
      if (this._l[i][0] === k) { if (!found) { out.push([k, v]); found = true; } } else out.push(this._l[i]);
    }
    if (!found) out.push([k, v]);
    this._l = out;
  };
  URLSearchParams.prototype.sort = function () { this._l.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; }); };
  URLSearchParams.prototype.forEach = function (fn, t) { for (var i = 0; i < this._l.length; i++) fn.call(t, this._l[i][1], this._l[i][0], this); };
  URLSearchParams.prototype.keys = function () { return this._l.map(function (p) { return p[0]; })[Symbol.iterator](); };
  URLSearchParams.prototype.values = function () { return this._l.map(function (p) { return p[1]; })[Symbol.iterator](); };
  URLSearchParams.prototype.entries = function () { return this._l.map(function (p) { return [p[0], p[1]]; })[Symbol.iterator](); };
  URLSearchParams.prototype[Symbol.iterator] = URLSearchParams.prototype.entries;
  URLSearchParams.prototype.toString = function () { return this._l.map(function (p) { return enc(p[0]) + '=' + enc(p[1]); }).join('&'); };
  g.URLSearchParams = URLSearchParams;

  var URL_RE = /^([a-zA-Z][a-zA-Z0-9+.\-]*:)(?:\/\/(?:([^:@\/?#]*)(?::([^@\/?#]*))?@)?(\[[^\]]*\]|[^:\/?#]*)(?::(\d*))?)?([^?#]*)(\?[^#]*)?(#.*)?$/;
  var DEFAULT_PORT = { 'http:': '80', 'https:': '443', 'ftp:': '21', 'ws:': '80', 'wss:': '443' };
  function normPath(p) {
    var segs = p.split('/'), out = [];
    for (var i = 0; i < segs.length; i++) {
      var s = segs[i];
      if (s === '..') { if (out.length > 1) out.pop(); if (i === segs.length - 1) out.push(''); }
      else if (s === '.') { if (i === segs.length - 1) out.push(''); }
      else out.push(s);
    }
    var r = out.join('/');
    return r.charAt(0) === '/' ? r : '/' + r;
  }
  function URL(url, base) {
    url = String(url);
    var m = URL_RE.exec(url);
    if (!m) {
      if (base === undefined) throw new TypeError('Invalid URL: ' + url);
      var b = base instanceof URL ? base : new URL(base);
      var rel = /^([^?#]*)(\?[^#]*)?(#.*)?$/.exec(url);
      var path = rel[1], search = rel[2] || '', hash = rel[3] || '';
      if (url.indexOf('//') === 0) return new URL(b.protocol + url);
      if (path === '') { path = b.pathname; if (!rel[2]) search = b.search; }
      else if (path.charAt(0) !== '/') path = b.pathname.substring(0, b.pathname.lastIndexOf('/') + 1) + path;
      return new URL(b.protocol + '//' + (b.username ? b.username + (b.password ? ':' + b.password : '') + '@' : '') + b.host + normPath(path) + search + hash);
    }
    this.protocol = m[1].toLowerCase();
    this.username = m[2] || ''; this.password = m[3] || '';
    this.hostname = (m[4] || '').toLowerCase();
    this.port = (m[5] && m[5] !== DEFAULT_PORT[this.protocol]) ? m[5] : '';
    var p = m[6] || '';
    this.pathname = this.hostname || p.charAt(0) === '/' ? normPath(p || '/') : p;
    this.search = m[7] && m[7] !== '?' ? m[7] : '';
    this.hash = m[8] && m[8] !== '#' ? m[8] : '';
    this.searchParams = new URLSearchParams(this.search);
    this._sp0 = this.searchParams.toString();
  }
  Object.defineProperty(URL.prototype, 'host', { get: function () { return this.hostname + (this.port ? ':' + this.port : ''); } });
  Object.defineProperty(URL.prototype, 'origin', { get: function () { return this.hostname ? this.protocol + '//' + this.host : 'null'; } });
  Object.defineProperty(URL.prototype, 'href', {
    get: function () {
      var q = this.searchParams.toString();
      var search = q === this._sp0 ? this.search : (q ? '?' + q : '');
      var auth = this.username ? this.username + (this.password ? ':' + this.password : '') + '@' : '';
      return this.protocol + (this.hostname ? '//' + auth + this.host : '') + this.pathname + search + this.hash;
    }
  });
  URL.prototype.toString = function () { return this.href; };
  URL.prototype.toJSON = function () { return this.href; };
  g.URL = URL;

  // ---------- timers ----------
  var timers = {}, tid = 1;
  g.sleep = function (ms) { return nativeSleep(Math.max(0, +ms || 0)); };
  g.setTimeout = function (fn, ms) {
    var id = tid++, args = Array.prototype.slice.call(arguments, 2);
    timers[id] = true;
    (async function () {
      await nativeSleep(Math.max(0, +ms || 0));
      if (timers[id]) { delete timers[id]; try { fn.apply(null, args); } catch (e) { console.error(e); } }
    })();
    return id;
  };
  g.clearTimeout = function (id) { delete timers[id]; };
  g.setInterval = function (fn, ms) {
    var id = tid++, args = Array.prototype.slice.call(arguments, 2);
    timers[id] = true;
    (async function () {
      while (timers[id]) {
        await nativeSleep(Math.max(1, +ms || 1));
        if (!timers[id]) break;
        try { fn.apply(null, args); } catch (e) { console.error(e); }
      }
    })();
    return id;
  };
  g.clearInterval = g.clearTimeout;

  // ---------- fetch ----------
  function bytesToB64(u8) {
    var s = '';
    for (var i = 0; i < u8.length; i += 0x2000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x2000));
    return g.btoa(s);
  }
  function b64ToBytes(b64) {
    var s = g.atob(b64), u = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
    return u;
  }
  function toHex(u8) { var h = ''; for (var i = 0; i < u8.length; i++) h += (u8[i] < 16 ? '0' : '') + u8[i].toString(16); return h; }
  g.fetch = async function (url, options) {
    options = options || {};
    var headers = {}, h = options.headers || {};
    for (var k in h) if (Object.prototype.hasOwnProperty.call(h, k)) headers[k] = String(h[k]);
    var body = options.body, bodyB64 = null;
    if (body instanceof URLSearchParams) { body = body.toString(); if (!hasHeader(headers, 'content-type')) headers['Content-Type'] = 'application/x-www-form-urlencoded'; }
    else if (body instanceof Uint8Array) { bodyB64 = bytesToB64(body); body = null; }
    else if (body !== undefined && body !== null && typeof body === 'object') { body = JSON.stringify(body); if (!hasHeader(headers, 'content-type')) headers['Content-Type'] = 'application/json'; }
    var req = {
      method: (options.method || 'GET').toUpperCase(), headers: headers,
      body: body === undefined || body === null ? null : String(body), bodyB64: bodyB64,
      redirect: options.redirect === 'manual' ? 'manual' : 'follow',
      timeout: options.timeout || 0, binary: !!options.binary
    };
    var res = JSON.parse(await nativeFetch(String(url), JSON.stringify(req)));
    if (res.error) throw new Error(res.error);
    var text = res.body, b64 = res.bodyB64;
    var hdrs = res.headers || {};
    return {
      ok: res.ok, status: res.status, url: res.url, redirected: res.url !== String(url),
      headers: { get: function (n) { var v = hdrs[String(n).toLowerCase()]; return v === undefined ? null : v; }, has: function (n) { return hdrs[String(n).toLowerCase()] !== undefined; }, raw: hdrs },
      text: async function () { return text !== undefined && text !== null ? text : new TextDecoder().decode(b64ToBytes(b64 || '')); },
      json: async function () { return JSON.parse(text !== undefined && text !== null ? text : new TextDecoder().decode(b64ToBytes(b64 || ''))); },
      base64: async function () { return b64 !== undefined && b64 !== null ? b64 : bytesToB64(new TextEncoder().encode(text || '')); },
      bytes: async function () { return b64 !== undefined && b64 !== null ? b64ToBytes(b64) : new TextEncoder().encode(text || ''); }
    };
  };
  function hasHeader(h, name) { for (var k in h) if (k.toLowerCase() === name) return true; return false; }

  // ---------- crypto ----------
  function dataArg(v, enc) {
    if (v instanceof Uint8Array) return { d: toHex(v), e: 'hex' };
    if (Array.isArray(v)) return { d: toHex(new Uint8Array(v)), e: 'hex' };
    return { d: String(v === undefined || v === null ? '' : v), e: enc || 'utf8' };
  }
  function call(o) { return unwrap(nativeCrypto(JSON.stringify(o))); }
  var crypto = {
    hash: function (alg, data, o) {
      o = o || {}; var d = dataArg(data, o.inputEncoding);
      return call({ op: 'hash', alg: alg, data: d.d, dataEnc: d.e, out: o.output || 'hex' });
    },
    hmac: function (alg, key, data, o) {
      o = o || {}; var d = dataArg(data, o.inputEncoding), k = dataArg(key, o.keyEncoding);
      return call({ op: 'hmac', alg: alg, key: k.d, keyEnc: k.e, data: d.d, dataEnc: d.e, out: o.output || 'hex' });
    },
    aes: {
      encrypt: function (data, key, o) {
        o = o || {}; var d = dataArg(data, o.inputEncoding), k = dataArg(key, o.keyEncoding), iv = dataArg(o.iv || '', o.ivEncoding);
        return call({ op: 'aes', encrypt: true, mode: o.mode || 'cbc', padding: o.padding || 'pkcs7', data: d.d, dataEnc: d.e, key: k.d, keyEnc: k.e, iv: iv.d, ivEnc: iv.e, aad: o.aad || '', tagBits: o.tagBits || 128, out: o.output || 'base64' });
      },
      decrypt: function (data, key, o) {
        o = o || {}; var d = dataArg(data, o.inputEncoding || 'base64'), k = dataArg(key, o.keyEncoding), iv = dataArg(o.iv || '', o.ivEncoding);
        return call({ op: 'aes', encrypt: false, mode: o.mode || 'cbc', padding: o.padding || 'pkcs7', data: d.d, dataEnc: d.e, key: k.d, keyEnc: k.e, iv: iv.d, ivEnc: iv.e, aad: o.aad || '', tagBits: o.tagBits || 128, out: o.output || 'utf8' });
      }
    },
    randomHex: function (n) { return call({ op: 'random', n: n || 16 }); },
    randomUUID: function () {
      var h = call({ op: 'random', n: 16 });
      var v = h.substring(0, 12) + '4' + h.substring(13, 16) + '89ab'[parseInt(h.charAt(16 % 32), 16) & 3] + h.substring(17, 32);
      return v.substring(0, 8) + '-' + v.substring(8, 12) + '-' + v.substring(12, 16) + '-' + v.substring(16, 20) + '-' + v.substring(20, 32);
    },
    convert: function (data, from, to) { var d = dataArg(data, from); return call({ op: 'convert', data: d.d, dataEnc: d.e, out: to }); }
  };
  ['md5', 'sha1', 'sha256', 'sha384', 'sha512'].forEach(function (a) { crypto[a] = function (data, o) { return crypto.hash(a, data, o); }; });
  g.crypto = crypto;

  // ---------- html (Jsoup) ----------
  g.html = {
    select: function (src, selector, o) { return JSON.parse(unwrap(nativeHtml('select', String(src), String(selector), JSON.stringify(o || {})))); },
    first: function (src, selector, o) { var r = g.html.select(src, selector, o); return r.length ? r[0] : null; },
    text: function (src) { return unwrap(nativeHtml('text', String(src), '', '{}')); }
  };

  // ---------- cookies / storage / settings ----------
  g.cookies = {
    get: function (url) { return nativeCookieGet(String(url)); },
    set: function (url, cookie) { nativeCookieSet(String(url), String(cookie)); }
  };
  g.storage = {
    get: function (k) { var v = nativeStorageGet(String(k)); return v === undefined ? null : v; },
    set: function (k, v) { unwrap(nativeStorageSet(String(k), String(v))); },
    remove: function (k) { nativeStorageRemove(String(k)); },
    getJSON: function (k, def) { var v = g.storage.get(k); if (v === null) return def === undefined ? null : def; try { return JSON.parse(v); } catch (e) { return def === undefined ? null : def; } },
    setJSON: function (k, v) { g.storage.set(k, JSON.stringify(v)); }
  };
  var S = {};
  try { S = JSON.parse(nativeSettings() || '{}'); } catch (e) { S = {}; }
  g.settings = { get: function (k, def) { return S[k] === undefined || S[k] === '' ? def : S[k]; }, all: S };

  // ---------- m3u builder ----------
  function q(s) { return String(s === undefined || s === null ? '' : s).replace(/[\r\n]+/g, ' ').replace(/"/g, "'"); }
  g.m3u = {
    build: function (items, o) {
      o = o || {};
      var aid = (typeof addon !== 'undefined' && addon && addon.id) ? String(addon.id) : '';
      var out = ['#EXTM3U' + (o.epg ? ' x-tvg-url="' + q(Array.isArray(o.epg) ? o.epg.join(',') : o.epg) + '"' : '')];
      for (var i = 0; i < items.length; i++) {
        var c = items[i];
        if (!c || !c.url || !c.name) continue;
        var a = '#EXTINF:-1';
        if (c.tvgId) a += ' tvg-id="' + q(c.tvgId) + '"';
        if (c.tvgName) a += ' tvg-name="' + q(c.tvgName) + '"';
        if (c.logo) a += ' tvg-logo="' + q(c.logo) + '"';
        // সাব-ক্যাটাগরি: subgroup দিলে "group › subgroup" (অ্যাপে ফোল্ডারের ভেতরে ফোল্ডার, সর্বোচ্চ ২ স্তর)
        var gt = c.group ? String(c.group) : '';
        if (c.subgroup) gt = (gt || 'Others') + ' \u203A ' + String(c.subgroup).replace(/\u203A/g, '-');
        if (gt) a += ' group-title="' + q(gt) + '"';
        if (c.chno) a += ' tvg-chno="' + q(c.chno) + '"';
        if (c.type) a += ' tvg-type="' + q(c.type) + '"';
        // Movie/Series: সিরিজের নাম + সিজন/এপিসোড নম্বর (নাম থেকে আন্দাজের চেয়ে নির্ভরযোগ্য) ও সারির নিচের লেখা
        if (c.seriesName) a += ' serie-title="' + q(c.seriesName) + '"';
        if (c.season !== undefined && c.season !== null && c.season !== '') a += ' tvg-season="' + q(c.season) + '"';
        if (c.episode !== undefined && c.episode !== null && c.episode !== '') a += ' tvg-episode="' + q(c.episode) + '"';
        if (c.subtitle) a += ' tvg-subtitle="' + q(c.subtitle) + '"';
        out.push(a + ',' + q(c.name));
        if (c.userAgent) out.push('#EXTVLCOPT:http-user-agent=' + q(c.userAgent));
        if (c.referrer) out.push('#EXTVLCOPT:http-referrer=' + q(c.referrer));
        if (c.origin) out.push('#EXTVLCOPT:http-origin=' + q(c.origin));
        if (c.headers && typeof c.headers === 'object') out.push('#EXTHTTP:' + JSON.stringify(c.headers));
        if (c.drmScheme && c.drmLicense) {
          out.push('#KODIPROP:inputstream.adaptive.license_type=' + q(c.drmScheme));
          out.push('#KODIPROP:inputstream.adaptive.license_key=' + q(c.drmLicense));
        }
        var u = String(c.url).replace(/[\r\n]+/g, '');
        // resolve:true → URL প্লে করার মুহূর্তে resolveStream() দিয়ে বের হবে
        if (c.resolve) u = 'addon-resolve://' + aid + '/' + encodeURIComponent(u);
        out.push(u);
      }
      return out.join('\n') + '\n';
    }
  };

  // প্লে করার মুহূর্তে চলে: resolveStream(info) → { url, userAgent, referrer, origin, headers, drmScheme, drmLicense } বা শুধু url স্ট্রিং
  g.__resolveAddon = async function (infoJson) {
    if (typeof g.resolveStream !== 'function') throw new Error('resolveStream() is not defined');
    var r = await g.resolveStream(JSON.parse(infoJson));
    if (typeof r === 'string') r = { url: r };
    if (!r || typeof r !== 'object' || !r.url) throw new Error('resolveStream() must return a URL or an object with url');
    var o = { url: String(r.url) };
    ['userAgent', 'referrer', 'origin', 'drmScheme', 'drmLicense'].forEach(function (k) { if (r[k]) o[k] = String(r[k]); });
    if (r.headers && typeof r.headers === 'object') {
      o.headers = {};
      for (var k in r.headers) if (Object.prototype.hasOwnProperty.call(r.headers, k)) o.headers[k] = String(r.headers[k]);
    }
    return JSON.stringify(o);
  };

  g.__runAddon = async function () {
    if (typeof g.generatePlaylist !== 'function') throw new Error('generatePlaylist() is not defined');
    var r = await g.generatePlaylist();
    if (r && typeof r === 'object' && !Array.isArray(r) && Array.isArray(r.channels)) r = g.m3u.build(r.channels, { epg: r.epg });
    else if (Array.isArray(r)) r = g.m3u.build(r);
    return r === undefined || r === null ? '' : String(r);
  };
})(globalThis);
