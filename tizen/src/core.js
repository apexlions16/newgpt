// Kumanda tuşları, sunucu bağlantısı, oynatma motoru (Samsung AVPlay / HTML5) ve altyazılar.
export const isTizen = !!(window.tizen && window.webapis);

// ---------------------------------------------------------------- kumanda
const CODES = {
  37: "left", 38: "up", 39: "right", 40: "down", 13: "ok", 29443: "ok",
  10009: "back", 27: "back", 8: "back", 10182: "exit",
  10252: "playpause", 32: "playpause", 415: "play", 19: "pause", 413: "stop",
  417: "ff", 412: "rw", 427: "chup", 428: "chdown", 33: "chup", 34: "chdown",
  403: "red", 404: "green", 405: "yellow", 406: "blue",
};
export function setupKeys(handler) {
  if (isTizen) {
    try {
      tizen.tvinputdevice.registerKeyBatch(["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward", "MediaRewind",
        "ChannelUp", "ChannelDown", "ColorF0Red", "ColorF1Green", "ColorF2Yellow", "ColorF3Blue"]);
    } catch (_) { /* eski model */ }
  }
  document.addEventListener("keydown", (e) => {
    const t = e.target;
    if (t && t.tagName === "INPUT" && ![13, 37, 38, 39, 40, 10009, 27].includes(e.keyCode)) return;
    const k = CODES[e.keyCode];
    if (!k) return;
    e.preventDefault();
    handler(k, e.repeat);
  });
}
export function exitApp() {
  try { tizen.application.getCurrentApplication().exit(); } catch (_) { window.close(); }
}
export function screenSaver(on) {
  try { webapis.appcommon.setScreenSaver(on ? webapis.appcommon.AppCommonScreenSaverState.SCREEN_SAVER_ON : webapis.appcommon.AppCommonScreenSaverState.SCREEN_SAVER_OFF); } catch (_) {}
}

// ---------------------------------------------------------------- sunucu
const BASE_KEY = "gflix.tv.server";
const KEY_KEY = "gflix.tv.key";
// Bilgisayardaki yayın sunucusu (gflix-yayin) bir anahtar ister: bilgisayarda "gflix-yayin anahtar" yazar, TV'ye bir kez girilir.
export const Net = {
  base: "",
  key: (() => { try { return localStorage.getItem(KEY_KEY) || ""; } catch (_) { return ""; } })(),
  saved() { try { return localStorage.getItem(BASE_KEY) || ""; } catch (_) { return ""; } },
  save(b) { Net.base = b; try { localStorage.setItem(BASE_KEY, b); } catch (_) {} },
  saveKey(k) { Net.key = (k || "").trim(); try { localStorage.setItem(KEY_KEY, Net.key); } catch (_) {} },
  headers(extra) { return Net.key ? { "X-GFlix-Key": Net.key, ...(extra || {}) } : { ...(extra || {}) }; },
  url(p) { return Net.base + p; },
  async ping(base, ms = 1500) {
    try {
      const r = await timeoutFetch(base + "/api/ping", ms, { headers: Net.headers() });
      const j = await r.json();
      return j && j.gflix ? j : null;
    } catch (_) { return null; }
  },
  /** Tarayıcı sürümü sunucudan açıldıysa adres bellidir; değilse kayıtlı adres, o da yoksa ev ağı taranır. */
  async find(onProgress) {
    if (/^https?:/.test(location.protocol) && location.pathname.startsWith("/tv/")) { Net.base = location.origin; return Net.base; }
    const saved = Net.saved();
    if (saved && (await Net.ping(saved, 2500))) { Net.base = saved; return saved; }
    const ip = localIp();
    if (!ip) return null;
    const prefix = ip.split(".").slice(0, 3).join(".");
    const order = [];
    for (let i = 1; i < 255; i++) order.push(i);
    let found = null, done = 0;
    const workers = Array.from({ length: 24 }, async () => {
      while (order.length && !found) {
        const n = order.shift();
        const b = `http://${prefix}.${n}:8766`;
        if (await Net.ping(b, 900)) found = found || b;
        onProgress && onProgress(++done / 254);
      }
    });
    await Promise.all(workers);
    if (found) Net.save(found);
    return found;
  },
  async json(p) {
    const r = await fetch(Net.url(p), { headers: Net.headers() });
    if (!r.ok) { let m = `HTTP ${r.status}`; try { m = (await r.json()).error || m; } catch (_) {} throw Object.assign(new Error(m), { status: r.status }); }
    return r.json();
  },
  async post(p, body) {
    const r = await fetch(Net.url(p), { method: "POST", headers: Net.headers({ "Content-Type": "application/json" }), body: JSON.stringify(body || {}) });
    let j = {};
    try { j = await r.json(); } catch (_) {}
    if (!r.ok) throw Object.assign(new Error(j.error || `HTTP ${r.status}`), { status: r.status, needsSource: !!j.needsSource });
    return j;
  },
};
function localIp() {
  try { return webapis.network.getIp(); } catch (_) { return null; }
}
function timeoutFetch(url, ms, opts) {
  return new Promise((ok, no) => {
    const t = setTimeout(() => no(new Error("zaman aşımı")), ms);
    fetch(url, opts).then((r) => { clearTimeout(t); ok(r); }, (e) => { clearTimeout(t); no(e); });
  });
}

// ---------------------------------------------------------------- oynatma motoru
/**
 * Ortak arayüz: open(url, startMs) · play() · pause() · seek(ms) · time() · duration() · paused
 * audioTracks() → [{id, lang, label, on}] · selectAudio(id) · destroy()
 * Olaylar: on(ev, fn) — ready, time, buffering(bool), ended, error(msg), playing, paused
 */
export function createEngine(videoEl) {
  return isTizen && webapis.avplay ? avplayEngine() : html5Engine(videoEl);
}

function emitter() {
  const m = {};
  return { on(e, f) { (m[e] = m[e] || []).push(f); }, emit(e, a) { (m[e] || []).forEach((f) => { try { f(a); } catch (x) { console.error(x); } }); } };
}

function avplayEngine() {
  const av = webapis.avplay;
  const ev = emitter();
  let cur = 0, dur = 0, paused = true, alive = true;
  const obj = document.getElementById("avplayer");
  function close() { try { av.stop(); } catch (_) {} try { av.close(); } catch (_) {} }
  const api = {
    on: ev.on,
    get paused() { return paused; },
    open(url, startMs) {
      close();
      cur = startMs || 0; dur = 0; paused = true;
      obj.classList.add("on");
      av.open(url);
      av.setDisplayRect(0, 0, window.innerWidth || 1920, window.innerHeight || 1080);
      try { av.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX"); } catch (_) {}
      try { av.setTimeoutForBuffering(20); } catch (_) {}
      av.setListener({
        onbufferingstart: () => ev.emit("buffering", true),
        onbufferingcomplete: () => ev.emit("buffering", false),
        oncurrentplaytime: (ms) => { cur = ms; ev.emit("time"); },
        onstreamcompleted: () => { paused = true; ev.emit("ended"); },
        onerror: (e) => ev.emit("error", String(e)),
        onevent: () => {},
      });
      ev.emit("buffering", true);
      av.prepareAsync(() => {
        if (!alive) return;
        dur = av.getDuration() || 0;
        const go = () => { av.play(); paused = false; ev.emit("buffering", false); ev.emit("ready"); ev.emit("playing"); };
        if (startMs > 0) av.seekTo(startMs, go, go); else go();
      }, (e) => ev.emit("error", String(e && e.name || e)));
    },
    play() { try { av.play(); paused = false; ev.emit("playing"); } catch (_) {} },
    pause() { try { av.pause(); paused = true; ev.emit("paused"); } catch (_) {} },
    seek(ms) {
      ms = Math.max(0, Math.min(ms, (dur || ms + 1) - 1500));
      cur = ms; ev.emit("buffering", true);
      try { av.seekTo(ms, () => ev.emit("buffering", false), () => ev.emit("buffering", false)); } catch (_) {}
    },
    time: () => cur,
    duration: () => dur || (() => { try { return av.getDuration(); } catch (_) { return 0; } })(),
    audioTracks() {
      let list = [], curIdx = -1;
      try { list = av.getTotalTrackInfo().filter((t) => t.type === "AUDIO"); } catch (_) {}
      try { const c = av.getCurrentStreamInfo().find((t) => t.type === "AUDIO"); if (c) curIdx = c.index; } catch (_) {}
      return list.map((t, i) => {
        let info = {}; try { info = JSON.parse(t.extra_info || "{}"); } catch (_) {}
        return { id: t.index, lang: info.language || info.lang || "", label: info.language || `Ses ${i + 1}`, on: t.index === curIdx };
      });
    },
    selectAudio(id) { try { av.setSelectTrack("AUDIO", id); } catch (_) {} },
    destroy() { alive = false; close(); obj.classList.remove("on"); },
  };
  return api;
}

function html5Engine(video) {
  const ev = emitter();
  let hls = null;
  const on = (n, f) => video.addEventListener(n, f);
  on("waiting", () => ev.emit("buffering", true));
  on("playing", () => { ev.emit("buffering", false); ev.emit("playing"); });
  on("pause", () => ev.emit("paused"));
  on("timeupdate", () => ev.emit("time"));
  on("ended", () => ev.emit("ended"));
  on("error", () => ev.emit("error", video.error ? `kod ${video.error.code}` : "bilinmeyen"));
  return {
    on: ev.on,
    get paused() { return video.paused; },
    open(url, startMs) {
      if (hls) { hls.destroy(); hls = null; }
      video.classList.add("on");
      ev.emit("buffering", true);
      const isHls = /\/hls\/|\.m3u8/.test(url);
      // hls.js varsa hep o: yeni Chromium "maybe" der ama HLS'i kendisi oynatamaz
      if (isHls && window.Hls && Hls.isSupported()) {
        hls = new Hls({ maxBufferLength: 60 });
        hls.loadSource(url); hls.attachMedia(video);
        hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, () => ev.emit("tracks"));
      } else video.src = url;
      video.addEventListener("loadedmetadata", function once() {
        video.removeEventListener("loadedmetadata", once);
        if (startMs > 0) video.currentTime = startMs / 1000;
        video.play().catch(() => {});
        ev.emit("ready");
      });
    },
    play() { video.play().catch(() => {}); },
    pause() { video.pause(); },
    seek(ms) { video.currentTime = Math.max(0, ms / 1000); },
    time: () => video.currentTime * 1000,
    duration: () => (isFinite(video.duration) ? video.duration * 1000 : 0),
    audioTracks() {
      if (hls) return hls.audioTracks.map((t, i) => ({ id: i, lang: t.lang || "", label: t.name || t.lang, on: hls.audioTrack === i }));
      const at = video.audioTracks;
      if (!at) return [];
      const out = [];
      for (let i = 0; i < at.length; i++) out.push({ id: i, lang: at[i].language, label: at[i].label || at[i].language, on: at[i].enabled });
      return out;
    },
    selectAudio(id) {
      if (hls) { hls.audioTrack = id; return; }
      const at = video.audioTracks; if (!at) return;
      for (let i = 0; i < at.length; i++) at[i].enabled = i === id;
    },
    destroy() { if (hls) { hls.destroy(); hls = null; } video.pause(); video.removeAttribute("src"); video.load(); video.classList.remove("on"); },
  };
}

// ---------------------------------------------------------------- altyazı (VTT/SRT, kendi çizimimiz: iki motorda da aynı görünür)
export async function loadSubtitles(url) {
  const text = await (await fetch(url)).text();
  const cues = [];
  const toMs = (s) => { const p = s.trim().replace(",", ".").split(":"); const sec = parseFloat(p.pop()); const min = +(p.pop() || 0); const hr = +(p.pop() || 0); return Math.round(((hr * 60 + min) * 60 + sec) * 1000); };
  for (const block of text.replace(/\r/g, "").split(/\n\n+/)) {
    const lines = block.split("\n");
    const i = lines.findIndex((l) => l.includes("-->"));
    if (i < 0) continue;
    const [a, b] = lines[i].split("-->");
    const body = lines.slice(i + 1).join("\n").replace(/<(?!\/?[ib]>)[^>]+>/g, "").replace(/\{\\[^}]*\}/g, "");
    if (body.trim()) cues.push({ start: toMs(a), end: toMs(b.trim().split(/\s+/)[0]), text: body });
  }
  return cues;
}
export function cueAt(cues, ms) {
  let lo = 0, hi = cues.length - 1, out = [];
  while (lo <= hi) { const m = (lo + hi) >> 1; if (cues[m].start <= ms) lo = m + 1; else hi = m - 1; }
  for (let i = Math.max(0, hi - 3); i <= hi; i++) if (cues[i] && cues[i].start <= ms && cues[i].end > ms) out.push(cues[i].text);
  return out.join("\n");
}

// ---------------------------------------------------------------- imleç (Samsung'un internet tarayıcısında oklar imleci gezdirir)
// Tıklanan öğeye ok tuşlarıyla adım adım gidip OK basılır; böylece her ekran kendi odak düzeniyle çalışmaya devam eder.
const FOCUSABLE = ".tile,.dbtn,.prof,.kb-key,.nav-item,.ep,.season,.set-item,.dlg-btn,.choice,.pl-opt,.pl-choice,.pl-ep,.pl-season,.btn,.ed-row,.pl-bar";
export function setupPointer(getTopEl, send) {
  if (isTizen) return;
  const center = (el) => { const r = el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };
  /** Odağı ok tuşlarıyla hedefe götürür; ok=true ise sonunda OK basar. */
  function go(top, target, ok) {
    const seen = new Map();
    for (let step = 0; step < 80; step++) {
      if (target.classList.contains("focus")) { if (ok) send("ok"); return; }
      const cur = top.querySelector(".focus");
      if (!cur) { send("right"); continue; }
      const [tx, ty] = center(target), [cx, cy] = center(cur);
      const dx = tx - cx, dy = ty - cy;
      const h = dx > 0 ? "right" : "left", v = dy > 0 ? "down" : "up";
      let order = Math.abs(dx) > Math.abs(dy) ? [h, v] : [v, h];
      const visits = (seen.get(cur) || 0) + 1; seen.set(cur, visits);
      if (visits > 1) order = order.reverse(); // gidip gelmeyi kır
      if (visits > 3) return;
      let moved = false;
      for (const k of order) {
        send(k);
        const now = top.querySelector(".focus");
        if (now !== cur || (now && center(now)[0] !== cx)) { moved = true; break; }
      }
      if (!moved) return;
    }
  }
  const find = (e) => { const top = getTopEl(); const t = top && e.target.closest && e.target.closest(FOCUSABLE); return t && top.contains(t) ? [top, t] : null; };
  document.addEventListener("click", (e) => {
    const f = find(e);
    if (!f) { send("ok"); return; }
    go(f[0], f[1], true);
  });
  // imleç bir kartın üzerinde durunca odak oraya gelir (ana sayfada fragman başlar); yalnız kartlar ve menü
  let hoverTimer = null;
  // mousemove: kart kayınca imlecin altına başka kart gelir ama imleç oynamadıkça yeniden odaklanmaz
  document.addEventListener("mousemove", (e) => {
    clearTimeout(hoverTimer);
    const f = find(e);
    if (!f || f[1].classList.contains("focus") || !f[1].matches(".tile,.nav-item,.dbtn,.prof,.ep,.set-item,.pl-ep")) return;
    const row = f[1].closest(".row");
    if (row && !row.classList.contains("active")) return; // başka satır: kaydırma zincirlemesin, tıklayınca gider
    hoverTimer = setTimeout(() => go(f[0], f[1], false), 250);
  });
  // tarayıcının "geri"si sayfadan çıkmasın: uygulama içinde geri
  try {
    history.pushState({ g: 1 }, "");
    window.addEventListener("popstate", () => { history.pushState({ g: 1 }, ""); send("back"); });
  } catch (_) {}
  // tekerlek: yukarı/aşağı
  let wheelAt = 0;
  document.addEventListener("wheel", (e) => { const n = Date.now(); if (n - wheelAt < 250) return; wheelAt = n; send(e.deltaY > 0 ? "down" : "up"); }, { passive: true });
}
