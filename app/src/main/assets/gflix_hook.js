/* G-Flix medya yakalayıcı (TurboDM tabanlı): her çerçeveye (iframe'ler dahil) sayfa betiklerinden ÖNCE enjekte edilir. */
(function () {
  if (window.__gfxHooked) return;
  window.__gfxHooked = true;
  var seen = {};
  var MAX_TEXT = 4000000;
  var MEDIA_RE = /https?:\/\/[^"'\s<>\\{}|^`]+?\.(?:m3u8|mpd|mp4|m4v|webm|mkv|mov|mp3|m4a|flv|vtt|srt)(?:\?[^"'\s<>\\{}|^`]*)?/gi;

  function post(o) {
    try {
      var s = JSON.stringify(o);
      if (window.GFXBridge && window.GFXBridge.postMessage) window.GFXBridge.postMessage(s);
      else if (window.GFX && window.GFX.report) window.GFX.report(s);
    } catch (e) {}
  }
  function abs(u) {
    try { return new URL(String(u), location.href).href; } catch (e) { return null; }
  }
  function report(u, kind, extra) {
    u = abs(u);
    if (!u || u.indexOf('http') !== 0) return;
    var key = kind + '|' + u;
    if (seen[key]) return;
    seen[key] = 1;
    post({ u: u, k: kind, p: location.href, t: document.title || '', x: extra || null });
  }
  function byExt(u) {
    var p = String(u).split('#')[0].split('?')[0].toLowerCase();
    if (/\.m3u8$/.test(p)) return 'hls';
    if (/\.mpd$/.test(p)) return 'dash';
    return null;
  }
  function unescapeText(t) {
    return t.replace(/\\\//g, '/').replace(/\\u0026/gi, '&').replace(/\\u002f/gi, '/')
            .replace(/\\u003d/gi, '=').replace(/&amp;/g, '&');
  }
  function sniffBytes(u, bytes) {
    try {
      var n = Math.min(bytes.length, 64), s = '';
      for (var i = 0; i < n; i++) s += String.fromCharCode(bytes[i]);
      s = s.replace(/^﻿/, '').replace(/^\s+/, '');
      if (s.indexOf('#EXTM3U') === 0) report(u, 'hls');
      else if (/^(<\?xml[^>]*>\s*)?<MPD/.test(s)) report(u, 'dash');
    } catch (e) {}
  }
  function scanText(txt, src) {
    if (!txt || typeof txt !== 'string') return;
    var head = txt.slice(0, 300).replace(/^﻿/, '').replace(/^\s+/, '');
    if (src && head.indexOf('#EXTM3U') === 0) { report(src, 'hls'); return; }
    if (src && /<MPD[\s>]/.test(txt.slice(0, 3000))) { report(src, 'dash'); return; }
    if (txt.length > MAX_TEXT) txt = txt.slice(0, MAX_TEXT);
    var t = unescapeText(txt), m, n = 0;
    MEDIA_RE.lastIndex = 0;
    while ((m = MEDIA_RE.exec(t)) && n++ < 300) report(m[0], 'url');
  }
  function checkType(u, ct) {
    ct = (ct || '').toLowerCase();
    if (!u) return false;
    if (ct.indexOf('mpegurl') >= 0) { report(u, 'hls'); return true; }
    if (ct.indexOf('dash+xml') >= 0) { report(u, 'dash'); return true; }
    if (ct.indexOf('video/') === 0) { if (ct.indexOf('mp2t') < 0 && ct.indexOf('iso.segment') < 0) report(u, 'media', ct); return true; }
    if (ct.indexOf('audio/') === 0) { report(u, 'media', ct); return true; }
    return false;
  }
  function texty(ct) {
    ct = (ct || '').toLowerCase();
    return ct === '' || /json|text|javascript|xml/.test(ct);
  }

  // ---- fetch
  try {
    if (window.fetch) {
      var origFetch = window.fetch;
      window.fetch = function (input, init) {
        var reqUrl = typeof input === 'string' ? input : (input && input.url) || String(input);
        var bx = byExt(reqUrl); if (bx) report(reqUrl, bx);
        var p = origFetch.apply(this, arguments);
        try {
          p.then(function (r) {
            try {
              var u = r.url || abs(reqUrl);
              var ct = r.headers.get('content-type');
              if (checkType(u, ct)) return;
              var len = parseInt(r.headers.get('content-length') || '0', 10) || 0;
              if (texty(ct)) {
                if (len < MAX_TEXT) r.clone().text().then(function (t) { scanText(t, u); })['catch'](function () {});
              } else if (len > 0 && len < 400000) {
                r.clone().arrayBuffer().then(function (b) { sniffBytes(u, new Uint8Array(b)); })['catch'](function () {});
              }
            } catch (e) {}
          })['catch'](function () {});
        } catch (e) {}
        return p;
      };
    }
  } catch (e) {}

  // ---- XMLHttpRequest
  try {
    var XP = XMLHttpRequest.prototype, xOpen = XP.open, xSend = XP.send;
    XP.open = function (m, u) { try { this.__gfxU = u; } catch (e) {} return xOpen.apply(this, arguments); };
    XP.send = function () {
      var x = this;
      try {
        var bx = byExt(x.__gfxU); if (bx) report(x.__gfxU, bx);
        x.addEventListener('load', function () {
          try {
            var u = x.responseURL || abs(x.__gfxU);
            if (checkType(u, x.getResponseHeader('content-type'))) return;
            var rt = x.responseType;
            if (rt === '' || rt === 'text') scanText(x.responseText, u);
            else if (rt === 'json') scanText(JSON.stringify(x.response), u);
            else if (rt === 'arraybuffer' && x.response && x.response.byteLength < 400000)
              sniffBytes(u, new Uint8Array(x.response, 0, Math.min(64, x.response.byteLength)));
          } catch (e) {}
        });
      } catch (e) {}
      return xSend.apply(this, arguments);
    };
  } catch (e) {}

  // ---- <video>/<audio>
  function mediaSrc(el) {
    try {
      var s = el.currentSrc || el.src || (el.getAttribute && el.getAttribute('src'));
      if (!s) return;
      if (s.indexOf('blob:') === 0) post({ k: 'mse', p: location.href, t: document.title || '' });
      else if (s.indexOf('data:') !== 0) report(s, 'el');
    } catch (e) {}
  }
  try {
    var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
    if (desc && desc.set) {
      Object.defineProperty(HTMLMediaElement.prototype, 'src', {
        configurable: true, enumerable: desc.enumerable,
        get: function () { return desc.get.call(this); },
        set: function (v) { try { if (v && String(v).indexOf('blob:') !== 0) report(String(v), 'el'); } catch (e) {} return desc.set.call(this, v); }
      });
    }
  } catch (e) {}
  function tracksOf(el) {
    try {
      var ts = el.querySelectorAll ? el.querySelectorAll('track') : [];
      for (var i = 0; i < ts.length; i++) {
        var t = ts[i], src = t.src || t.getAttribute('src');
        if (src && src.indexOf('blob:') !== 0 && src.indexOf('data:') !== 0)
          report(src, 'sub', (t.label || t.getAttribute('label') || '') + '|' + (t.srclang || t.getAttribute('srclang') || ''));
      }
    } catch (e) {}
  }
  var played = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
  ['play', 'playing', 'loadedmetadata', 'loadstart'].forEach(function (ev) {
    try {
      document.addEventListener(ev, function (e) {
        var el = e.target;
        if (!el || !el.tagName) return;
        mediaSrc(el); tracksOf(el);
        // Video oynamaya başladı: uygulamaya haber ver (bir kez)
        if (ev === 'playing' && el.tagName === 'VIDEO' && played && !played.has(el)) {
          played.add(el);
          post({ k: 'play', p: location.href, t: document.title || '', x: String(Math.round(el.duration || 0)) });
        }
      }, true);
    } catch (x) {}
  });
  function scanDom(deep) {
    try {
      var els = document.querySelectorAll('video,audio,video source,audio source');
      for (var i = 0; i < els.length; i++) mediaSrc(els[i]);
      tracksOf(document);
      if (deep && document.documentElement) {
        var html = document.documentElement.innerHTML;
        if (html && html.length < MAX_TEXT) scanText(html, null);
      }
    } catch (e) {}
  }
  var ticks = 0;
  function loop() { scanDom(ticks % 2 === 0); if (++ticks < 20) setTimeout(loop, 3000); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', function () { loop(); });
  else loop();
  try {
    var pending = false;
    new MutationObserver(function () {
      if (pending) return; pending = true;
      setTimeout(function () { pending = false; scanDom(false); }, 800);
    }).observe(document, { childList: true, subtree: true });
  } catch (e) {}
  window.__gfxRescan = function () { scanDom(true); };

  // ---- G-Flix: sayfadaki oynatıcıyı başlat, reklam düğmelerine bas (kullanıcının açtığı sayfada, ilk 3 dakika)
  var SKIP_RE = /^(reklam[ıi]?\s*(ge[çc]|atla)|reklam[ıi]?\s*kapat|skip(\s*ad(s)?)?|atla|ge[çc]|reklam[ıi] ge[çc])$/i;
  var PLAY_SEL = ['.vjs-big-play-button', '.jw-icon-display', '.jw-display-icon-container', '.plyr__control--overlaid',
    '.fp-play', '.play-button', '.playbtn', '#play', 'button[aria-label*="Play" i]', 'button[title*="Oynat" i]', '[class*="play-btn"]',
    '[class*="playbutton"]', '[class*="play_button"]'];
  function autoplay() {
    try {
      var all = document.querySelectorAll('button,a,div,span');
      for (var i = 0; i < all.length; i++) {
        var e = all[i], t = (e.innerText || e.getAttribute('aria-label') || '').trim();
        if (!t || t.length > 24 || !SKIP_RE.test(t)) continue;
        var r = e.getBoundingClientRect();
        if (r.width < 8 || r.height < 8 || r.width > 400) continue;
        try { e.click(); post({ k: 'skip', p: location.href, t: document.title || '', x: t }); } catch (x) {}
      }
      ['.ytp-ad-skip-button', '.videoAdUiSkipButton', '[class*="skip-ad"]', '[class*="skipAd"]'].forEach(function (s) {
        var bs = document.querySelectorAll(s); for (var j = 0; j < bs.length; j++) { try { bs[j].click(); } catch (x) {} }
      });
      var vs = document.querySelectorAll('video'), playing = false;
      for (var k = 0; k < vs.length; k++) {
        var v = vs[k];
        var ad = /\bad(s|vert)?\b|reklam|preroll|\bima\b/i.test((v.className || '') + ' ' + (v.id || '') + ' ' + (v.parentElement ? v.parentElement.className : ''));
        if (ad && isFinite(v.duration) && v.duration < 180) { try { v.currentTime = v.duration; } catch (x) {} continue; }
        if (!v.paused) { playing = true; continue; }
        try { v.muted = true; var pr = v.play(); if (pr && pr['catch']) pr['catch'](function () {}); } catch (x) {}
      }
      if (!playing) for (var m = 0; m < PLAY_SEL.length; m++) {
        var b = document.querySelector(PLAY_SEL[m]); if (b) { try { b.click(); } catch (x) {} }
      }
    } catch (e) {}
  }
  var autoTicks = 0;
  (function autoLoop() { autoplay(); if (++autoTicks < 72) setTimeout(autoLoop, 2500); })();
  window.__gfxAutoplay = autoplay;
})();
