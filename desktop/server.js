// G-Flix PC: yerel sunucu.
//  - Arayüz dosyalarını sunar (renderer/)
//  - Katalogu GitHub'dan okur (catalog/catalog.json), çevrimdışı için önbellekte tutar
//  - Release dosyalarını (videolar, altyazılar) GitHub'ın imzalı adresine çevirip Range ile aktarır
//  - Parçalı dosyaları (2 GB sınırı yüzünden f.p000, f.p001 …) tek dosya gibi sunar: /parts?u=…&s=…
//  - HLS listelerindeki GitHub adreslerini yerel adreslere çevirir: /hls/<id>
// Oynatıcı (<video>, hls.js) her şeyi aynı kaynaktan (127.0.0.1) alır; böylece CORS ve yetki sorunu olmaz.
"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");
const { Readable } = require("stream");

const API = process.env.GFLIX_API || "https://api.github.com";
const RENDERER = path.join(__dirname, "renderer");
const MIME = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
  ".ttf": "font/ttf", ".png": "image/png", ".svg": "image/svg+xml", ".ico": "image/x-icon", ".json": "application/json",
};
const SIGNED_TTL = 4 * 60 * 1000;

/**
 * Zamanlı yayın: yayın saati (releaseAt) gelmemiş yapım ve bölümlerin videoları gizlenir (TV ve PC uygulaması
 * güncelleme gerektirmeden doğru davranır; saati gelince bir sonraki katalog isteğinde görünür).
 */
function applySchedule(body) {
  try {
    const c = JSON.parse(Buffer.isBuffer(body) ? body.toString("utf8") : body);
    const now = Date.now() / 1000;
    // "Bitince yapımı kaldır" notunun zamanı geldiyse yapım TV/PC'ye hiç gönderilmez (dosyalar silinmez)
    c.titles = (c.titles || []).filter((t) => !(t.notes || []).some((n) => n.hide && n.until && n.until <= now));
    for (const t of c.titles || []) {
      // gizlemeden önce sıradaki yayın anını yaz (TV/PC "Yakında"da tarih gösterir)
      const eps = (t.seasons || []).flatMap((s) => s.episodes || []).map((e) => e.releaseAt || 0).filter((x) => x > now);
      const plan = (t.releasePlan || []).filter((x) => !(x.whenComplete && !x.complete)).map((x) => x.at || 0).filter((x) => x > now);
      const next = (t.releaseAt || 0) > now ? t.releaseAt : eps.length || plan.length ? Math.min(...eps, ...plan) : 0;
      if (next) t.nextReleaseAt = next;
      // gizlenecek bölümlerden plan: aynı sezonda aynı saatte açılan ardışık bölümler tek satır ("1–15. bölümler · tarih")
      const fut = (t.seasons || []).flatMap((s) => (s.episodes || []).filter((e) => (e.releaseAt || 0) > now).map((e) => [s.number, e.number, e.releaseAt]))
        .sort((a, b) => a[2] - b[2] || a[0] - b[0] || a[1] - b[1]);
      const ep = [];
      for (const [season, n, at] of fut) {
        const last = ep[ep.length - 1];
        if (last && last.season === season && last.at === at && last.to === n - 1) last.to = n;
        else ep.push({ season, from: n, to: n, at });
      }
      if (ep.length) t.episodePlan = ep;
      if ((t.releaseAt || 0) > now) { delete t.stream; t.seasons = []; continue; }
      if (Array.isArray(t.seasons)) {
        t.seasons = t.seasons.map((s) => ({ ...s, episodes: (s.episodes || []).filter((e) => (e.releaseAt || 0) <= now) })).filter((s) => s.episodes.length);
      }
    }
    return JSON.stringify(c);
  } catch (_) { return body; }
}

function createServer(opts) {
  const dataDir = opts.dataDir;
  fs.mkdirSync(dataDir, { recursive: true });
  const confPath = path.join(dataDir, "config.json");
  const catalogCache = path.join(dataDir, "catalog.json");
  const builtIn = opts.buildConfig || {};
  let conf = {};
  try { conf = JSON.parse(fs.readFileSync(confPath, "utf8")); } catch (_) { /* ilk açılış */ }
  const owner = () => conf.owner || builtIn.owner || "";
  const repo = () => conf.repo || builtIn.repo || "";
  const branch = () => conf.branch || builtIn.branch || "";
  const token = () => conf.token || builtIn.token || "";
  const saveConf = () => fs.writeFileSync(confPath, JSON.stringify(conf, null, 2));

  const signed = new Map(); // asset id -> {url, exp}
  // GitHub istek hakkı: biterse o ana kadar istek atılmaz; TV "Sıradasın" gösterir (/api/ping → blockedUntil)
  const rate = { remaining: null, resetAt: 0, blockedUntil: 0 };
  function trackRate(r) {
    const rem = Number(r.headers.get("x-ratelimit-remaining"));
    const reset = Number(r.headers.get("x-ratelimit-reset")) * 1000;
    const retry = Number(r.headers.get("retry-after")) * 1000;
    if (!Number.isNaN(rem) && r.headers.get("x-ratelimit-remaining") != null) rate.remaining = rem;
    if (reset) rate.resetAt = reset;
    if ((r.status === 403 || r.status === 429) && (rem === 0 || retry)) {
      rate.blockedUntil = Math.max(rate.blockedUntil, (retry ? Date.now() + retry : reset || Date.now() + 60000) + 3000);
      throw Object.assign(new Error("Şu an çok yoğun, sıradasın"), { status: 503, retryAt: rate.blockedUntil });
    }
  }
  const RESERVE = 250;
  const canStart = () => rate.blockedUntil <= Date.now() && (rate.remaining == null || rate.remaining >= RESERVE || (rate.resetAt && rate.resetAt <= Date.now()));
  const waitMs = () => Math.max(0, Math.max(rate.blockedUntil, rate.remaining != null && rate.remaining < RESERVE ? rate.resetAt : 0) - Date.now());
  const pending = new Map();

  function ghHeaders(extra) {
    const h = { "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "G-Flix-PC", ...extra };
    if (token()) h.Authorization = `Bearer ${token()}`;
    return h;
  }

  /** Release dosyasının kısa ömürlü imzalı indirme adresi (önbellekli). */
  async function resolveAsset(id, force) {
    const hit = signed.get(id);
    if (!force && hit && hit.exp > Date.now()) return hit.url;
    if (!force && pending.has(id)) return pending.get(id);
    const p = (async () => {
      if (rate.blockedUntil > Date.now()) throw Object.assign(new Error("Şu an çok yoğun, sıradasın"), { status: 503, retryAt: rate.blockedUntil });
      const r = await fetch(`${API}/repos/${owner()}/${repo()}/releases/assets/${id}`, {
        headers: ghHeaders({ Accept: "application/octet-stream" }), redirect: "manual",
      });
      trackRate(r);
      if (r.status >= 300 && r.status < 400 && r.headers.get("location")) {
        const url = r.headers.get("location");
        signed.set(id, { url, exp: Date.now() + SIGNED_TTL });
        return url;
      }
      if (r.ok) { // yönlendirme yoksa doğrudan API adresi kullanılır
        signed.set(id, { url: null, exp: Date.now() + SIGNED_TTL });
        return null;
      }
      throw Object.assign(new Error(`GitHub dosyası açılamadı (HTTP ${r.status})`), { status: r.status });
    })();
    pending.set(id, p);
    try { return await p; } finally { pending.delete(id); }
  }

  /** Bir dosyanın [start, end] (dahil) aralığını getirir; imzalı adresin süresi dolduysa yeniler. */
  async function fetchRange(id, start, end, signal) {
    const range = end == null ? `bytes=${start}-` : `bytes=${start}-${end}`;
    for (let attempt = 0; attempt < 2; attempt++) {
      const url = await resolveAsset(id, attempt > 0);
      const r = url
        ? await fetch(url, { headers: { Range: range }, signal })
        : await fetch(`${API}/repos/${owner()}/${repo()}/releases/assets/${id}`, { headers: ghHeaders({ Accept: "application/octet-stream", Range: range }), signal });
      if ((r.status === 401 || r.status === 403 || r.status === 404) && attempt === 0) { try { await r.body?.cancel(); } catch (_) {} continue; }
      return r;
    }
    throw new Error("GitHub dosyası alınamadı");
  }

  const assetId = (u) => { const m = String(u).match(/\/releases\/assets\/(\d+)/); return m ? m[1] : null; };

  function parseRange(header, total) {
    const m = /bytes=(\d*)-(\d*)/.exec(header || "");
    if (!m) return null;
    let start = m[1] === "" ? null : Number(m[1]);
    let end = m[2] === "" ? null : Number(m[2]);
    if (start == null) { start = Math.max(0, total - end); end = total - 1; }
    if (end == null || end >= total) end = total - 1;
    if (start > end || start >= total) return "bad";
    return { start, end };
  }

  // ---- uç noktalar

  async function serveAsset(req, res, id) {
    const ctrl = new AbortController();
    res.on("close", () => ctrl.abort());
    const r = await fetchRange(id, ...(rangeOf(req.headers.range)), ctrl.signal);
    const headers = { "Accept-Ranges": "bytes", "Content-Type": guessType(req.url) };
    for (const k of ["content-length", "content-range"]) if (r.headers.get(k)) headers[k] = r.headers.get(k);
    if (!req.headers.range) delete headers["content-range"];
    res.writeHead(req.headers.range ? (r.status === 206 ? 206 : r.status) : (r.status === 206 ? 200 : r.status), headers);
    if (r.body) Readable.fromWeb(r.body).on("error", () => res.destroy()).pipe(res);
    else res.end();
  }

  function rangeOf(h) {
    const m = /bytes=(\d+)-(\d*)/.exec(h || "");
    if (!m) return [0, null];
    return [Number(m[1]), m[2] === "" ? null : Number(m[2])];
  }

  function guessType(u) {
    if (/\.vtt|sub=1/.test(u)) return "text/vtt; charset=utf-8";
    if (/type=mp4/.test(u)) return "video/mp4";
    if (/type=webm/.test(u)) return "video/webm";
    if (/type=ts/.test(u)) return "video/mp2t";
    return "video/x-matroska";
  }

  /** Parçalı dosya: /parts?u=<url>&s=<boyut>&u=…  → tek dosya gibi, Range destekli. */
  async function serveParts(req, res, q) {
    const ids = q.getAll("u").map(assetId);
    const sizes = q.getAll("s").map(Number);
    if (!ids.length || ids.some((x) => !x) || ids.length !== sizes.length) { res.writeHead(400); return res.end("geçersiz parça listesi"); }
    const total = sizes.reduce((a, b) => a + b, 0);
    const rg = req.headers.range ? parseRange(req.headers.range, total) : { start: 0, end: total - 1 };
    if (rg === "bad" || rg == null) { res.writeHead(416, { "Content-Range": `bytes */${total}` }); return res.end(); }
    const headers = { "Accept-Ranges": "bytes", "Content-Type": q.get("type") ? guessType("type=" + q.get("type")) : "video/x-matroska",
      "Content-Length": String(rg.end - rg.start + 1) };
    if (req.headers.range) headers["Content-Range"] = `bytes ${rg.start}-${rg.end}/${total}`;
    res.writeHead(req.headers.range ? 206 : 200, headers);
    if (req.method === "HEAD") return res.end();
    const ctrl = new AbortController();
    let closed = false;
    res.on("close", () => { closed = true; ctrl.abort(); });
    let pos = rg.start;
    let partStart = 0;
    try {
      for (let i = 0; i < ids.length && pos <= rg.end && !closed; i++) {
        const partEnd = partStart + sizes[i] - 1;
        if (pos > partEnd) { partStart += sizes[i]; continue; }
        const from = pos - partStart;
        const to = Math.min(partEnd, rg.end) - partStart;
        const r = await fetchRange(ids[i], from, to, ctrl.signal);
        if (!r.ok) throw new Error(`parça ${i} HTTP ${r.status}`);
        for await (const chunk of Readable.fromWeb(r.body)) {
          if (closed) break;
          if (!res.write(chunk)) await new Promise((ok) => res.once("drain", ok));
        }
        pos = partStart + to + 1;
        partStart += sizes[i];
      }
    } catch (e) {
      if (!closed) console.error("parça aktarımı:", e.message);
    }
    res.end();
  }

  /** HLS listesi: GitHub adreslerini yerel adreslere çevirir (ana liste → /hls/, bölüm dosyaları → /asset/). */
  async function serveHls(req, res, id) {
    const r = await fetchRange(id, 0, null);
    const text = await r.text();
    const master = /#EXT-X-STREAM-INF|#EXT-X-MEDIA:/.test(text);
    const local = (u) => { const a = assetId(u); return a ? (master ? `/hls/${a}` : `/asset/${a}?type=ts`) : u; };
    const out = text.split(/\r?\n/).map((line) => {
      if (!line) return line;
      if (line.startsWith("#")) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${local(u)}"`);
      return local(line);
    }).join("\n");
    res.writeHead(200, { "Content-Type": "application/vnd.apple.mpegurl", "Cache-Control": "no-cache" });
    res.end(out);
  }

  async function serveCatalog(req, res, refresh) {
    const send = (body, from) => { res.writeHead(200, { "Content-Type": "application/json; charset=utf-8", "X-From": from }); res.end(applySchedule(body)); };
    if (!refresh && fs.existsSync(catalogCache) && Date.now() - fs.statSync(catalogCache).mtimeMs < 60_000) return send(fs.readFileSync(catalogCache), "cache");
    if (!owner() || !repo()) return send(fs.existsSync(catalogCache) ? fs.readFileSync(catalogCache) : '{"titles":[]}', "none");
    try {
      const ref = branch() ? `?ref=${encodeURIComponent(branch())}` : "";
      const r = await fetch(`${API}/repos/${owner()}/${repo()}/contents/catalog/catalog.json${ref}`, { headers: ghHeaders({ Accept: "application/vnd.github.raw+json" }) });
      trackRate(r);
      if (!r.ok) throw Object.assign(new Error(r.status === 401 ? "Erişim anahtarı geçersiz ya da süresi dolmuş." : r.status === 404 ? "Katalog bulunamadı (depo adı ya da anahtar yanlış)." : `GitHub hatası (HTTP ${r.status})`), { status: r.status });
      const body = Buffer.from(await r.arrayBuffer());
      JSON.parse(body.toString("utf8"));
      fs.writeFileSync(catalogCache, body);
      return send(body, "github");
    } catch (e) {
      if (fs.existsSync(catalogCache)) return send(fs.readFileSync(catalogCache), "offline");
      res.writeHead(e.status === 401 ? 401 : 502, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: e.message }));
    }
  }

  function readBody(req) {
    return new Promise((ok) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => ok(b)); });
  }

  function serveStatic(req, res, p) {
    const file = path.normalize(path.join(RENDERER, p === "/" ? "index.html" : p));
    if (!file.startsWith(RENDERER) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); return res.end("yok"); }
    res.writeHead(200, { "Content-Type": MIME[path.extname(file)] || "application/octet-stream" });
    fs.createReadStream(file).pipe(res);
  }

  const server = http.createServer(async (req, res) => {
    const u = new URL(req.url, "http://127.0.0.1");
    if (opts.cors) { // TV uygulaması (Tizen) başka kaynaktan istek atar
      res.setHeader("Access-Control-Allow-Origin", "*");
      res.setHeader("Access-Control-Allow-Headers", "Range, Content-Type");
      res.setHeader("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges");
      if (req.method === "OPTIONS") { res.writeHead(204); return res.end(); }
    }
    try {
      if (u.pathname === "/api/ping") { res.writeHead(200, { "Content-Type": "application/json", "Cache-Control": "no-cache" }); return res.end(JSON.stringify({ gflix: true, owner: owner(), repo: repo(), hasToken: !!token(), canStart: canStart(), waitMs: waitMs() })); }
      for (const [prefix, dir] of Object.entries(opts.mounts || {})) {
        if (!u.pathname.startsWith(prefix)) continue;
        const f = path.normalize(path.join(dir, u.pathname.slice(prefix.length) || "index.html"));
        if (!f.startsWith(dir) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); return res.end("yok"); }
        res.writeHead(200, { "Content-Type": MIME[path.extname(f)] || "application/octet-stream", "Cache-Control": "no-cache" });
        return fs.createReadStream(f).pipe(res);
      }
      if (u.pathname === "/api/catalog") return await serveCatalog(req, res, u.searchParams.has("refresh"));
      if (u.pathname === "/api/config" && req.method === "GET") {
        res.writeHead(200, { "Content-Type": "application/json" });
        return res.end(JSON.stringify({ owner: owner(), repo: repo(), hasToken: !!token(), version: opts.version || "dev" }));
      }
      if (u.pathname === "/api/config" && req.method === "POST") {
        const b = JSON.parse((await readBody(req)) || "{}");
        for (const k of ["token", "owner", "repo", "branch"]) if (typeof b[k] === "string") conf[k] = b[k].trim();
        saveConf();
        signed.clear();
        res.writeHead(200, { "Content-Type": "application/json" });
        return res.end('{"ok":true}');
      }
      let m;
      if ((m = u.pathname.match(/^\/asset\/(\d+)$/))) return await serveAsset(req, res, m[1]);
      if ((m = u.pathname.match(/^\/hls\/(\d+)$/))) return await serveHls(req, res, m[1]);
      if (u.pathname === "/parts") return await serveParts(req, res, u.searchParams);
      if (u.pathname.startsWith("/vendor/hls.js")) return serveFile(res, require.resolve("hls.js/dist/hls.min.js"), MIME[".js"]);
      if (u.pathname.startsWith("/fonts/")) {
        const f = path.join(__dirname, "fonts", path.basename(u.pathname));
        if (fs.existsSync(f)) return serveFile(res, f, MIME[".ttf"]);
      }
      return serveStatic(req, res, u.pathname);
    } catch (e) {
      console.error(req.url, e.message);
      if (!res.headersSent) { res.writeHead(e.status || 502, { "Content-Type": "text/plain; charset=utf-8", ...(e.retryAt ? { "Retry-After": String(Math.ceil((e.retryAt - Date.now()) / 1000)) } : {}) }); }
      res.end(e.message);
    }
  });

  function serveFile(res, file, type) {
    res.writeHead(200, { "Content-Type": type, "Cache-Control": "max-age=86400" });
    fs.createReadStream(file).pipe(res);
  }

  return {
    listen: (port = 0, host = opts.host || "127.0.0.1") => new Promise((ok, no) => { server.once("error", no); server.listen(port, host, () => ok(server.address().port)); }),
    close: () => server.close(),
  };
}

module.exports = { createServer, applySchedule };

// Doğrudan çalıştırma (tarayıcıyla geliştirme/test): node server.js [port]
if (require.main === module) {
  let buildConfig = {};
  try { buildConfig = require("./build-config.json"); } catch (_) {}
  const s = createServer({ dataDir: process.env.GFLIX_DATA || path.join(__dirname, ".data"), buildConfig, version: "dev" });
  s.listen(Number(process.argv[2] || 0)).then((p) => console.log(`G-Flix: http://127.0.0.1:${p}/`));
}
