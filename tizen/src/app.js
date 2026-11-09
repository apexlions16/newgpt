// G-Flix TV (Samsung Tizen): Netflix TV düzeni, kumandayla gezinme.
import { svg, avatar } from "../../desktop/renderer/js/dom.js";
import { Store, isFinished, fraction } from "../../desktop/renderer/js/store.js";
import * as L from "../../desktop/renderer/js/library.js";
import { partsUrl, hlsUrl, pickTrailer, allTrailers } from "../../desktop/renderer/js/media.js";
import { setupKeys, setupPointer, exitApp, Net, isTizen } from "./core.js";
import { Screens, stage, h, clear, clamp, toast, confirmBox, choiceBox, keyboard, preload, tmdb } from "./ui.js";
import { openPlayer } from "./player.js";
import { canStart, queueOverlay } from "./queue.js";

let catalog = null;
let titles = [];
let soon = [];
let soonSeries = [];   // Yakında Gelen Diziler: izlenebilir bölümü olan, devamı beklenen diziler
const CAT_KEY = "gflix.tv.catalog";

// ---------------------------------------------------------------- açılış
async function boot() {
  if (!isTizen && window.innerWidth && window.innerWidth !== 1920) document.documentElement.style.zoom = String(window.innerWidth / 1920);
  setupKeys((k, rep) => Screens.key(k, rep));
  setupPointer(() => Screens.top && Screens.top.el, (k) => Screens.key(k, false));
  if (!isTizen) toast("Tarayıcı sürümü: imleçle tıklayabilirsin. Asıl uygulamada kumanda okları doğrudan çalışır.", 5000);
  document.addEventListener("visibilitychange", () => { const t = Screens.top; if (t && t.visibility) t.visibility(!document.hidden); });
  const splash = h("div.screen.splash", h("div.splash-logo", "G-FLIX".split("").map((c, i) => h("span", { style: { animationDelay: `${i * 70}ms` } }, c))), h("div.splash-status"));
  Screens.push({ el: splash, key() {} });
  const status = splash.querySelector(".splash-status");
  const minDelay = new Promise((ok) => setTimeout(ok, 1900));
  status.textContent = "Bilgisayardaki G-Flix sunucusu aranıyor…";
  const base = await Net.find((p) => { status.textContent = `Ev ağı taranıyor… %${Math.round(p * 100)}`; });
  await minDelay;
  if (!base) return connectScreen("Sunucu bulunamadı.");
  await loadCatalog();
}

async function loadCatalog(refresh) {
  try {
    catalog = await Net.json("/api/catalog?tv=1" + (refresh ? "&refresh=1" : ""));
    try { localStorage.setItem(CAT_KEY, JSON.stringify(catalog)); } catch (_) {}
  } catch (e) {
    if (e.status === 401) return keyScreen(Net.key ? "Anahtar yanlış. Bilgisayarda: gflix-yayin anahtar" : "");
    try { catalog = JSON.parse(localStorage.getItem(CAT_KEY)); } catch (_) {}
    if (!catalog) return connectScreen(`Katalog alınamadı: ${e.message}`);
    toast("Bilgisayara ulaşılamadı; son kaydedilen katalog gösteriliyor.");
  }
  profilesScreen();
  tvListener();
}

// ---------------------------------------------------------------- telefondan "📺 TV'de aç"
// Bilgisayar komutu tutar; TV uzun bekleme (long-poll) ile dinler, gelince yapımı kendiliğinden açar.
let listening = false;
async function tvListener() {
  if (listening) return;
  listening = true;
  let seq = 0, fails = 0;
  for (;;) {
    let r = null;
    try { r = await Net.json(`/api/tv/next?after=${seq}&wait=25`); fails = 0; } catch (_) { fails++; }
    if (!r) { await new Promise((ok) => setTimeout(ok, Math.min(30000, 2000 * fails))); continue; }
    seq = Math.max(seq, r.seq || 0);
    const cmd = (r.commands || []).slice(-1)[0];
    if (cmd) openFromPhone(cmd);
  }
}
function openFromPhone(cmd) {
  const t = titles.find((x) => x.id === cmd.id) || ((catalog && catalog.titles) || []).find((x) => x.id === cmd.id);
  if (!t) return toast("Telefondan gelen yapım katalogda yok; kataloğu yenile.");
  while (Screens.top && Screens.top.player) Screens.top.close();      // açık oynatıcı kapanır (ilerleme kaydedilir)
  toast(`📱 Telefondan: ${t.title}`);
  playFrom(t, cmd.season == null ? null : cmd.season, cmd.episode == null ? null : cmd.episode, Math.max(0, Math.floor((cmd.position || 0) * 1000)));
}

// ---------------------------------------------------------------- anahtar (gflix-yayin)
function keyScreen(reason) {
  let val = Net.key || "";
  const field = h("div.connect-field", val || " ");
  const msg = h("div.connect-msg", reason || "");
  let onBtn = false;
  const btn = h("button.btn.primary", "Bağlan");
  const kb = keyboard({ value: val, onChange: (v) => { val = v; field.textContent = v || " "; } });
  const el = h("div.screen.connect",
    h("div.logo", "G-FLIX"),
    h("h1", "Bilgisayarın anahtarını gir"),
    h("p.connect-help", "Bilgisayarda ", h("code", "gflix-yayin anahtar"), " yaz; çıkan anahtarı (ör. ABCD-EFGH-JK23) buraya gir. Tireler gerekmez."),
    h("div.connect-body", kb.el, h("div.connect-side", field, btn, msg)));
  kb.focus();
  const paint = () => { btn.classList.toggle("focus", onBtn); onBtn ? kb.blur() : kb.focus(); };
  async function go() {
    Net.saveKey(val);
    msg.textContent = "Bağlanılıyor…";
    const p = await Net.ping(Net.base, 4000);
    if (!p) { msg.textContent = "Bilgisayara ulaşılamadı."; return; }
    if (p.yayin && !p.auth) { msg.textContent = "Anahtar yanlış."; return; }
    await loadCatalog(true);
  }
  Screens.replaceAll({ el, key(k) {
    if (k === "back") return connectScreen("");
    if (onBtn) { if (k === "left") { onBtn = false; paint(); } else if (k === "ok") go(); return; }
    const out = kb.key(k);
    if (out === "right" || out === "down") { onBtn = true; paint(); }
  } });
}

// ---------------------------------------------------------------- sunucu adresi (bulunamazsa)
function connectScreen(reason) {
  let ip = (Net.saved().match(/\/\/([\d.]+)/) || [])[1] || "192.168.1.";
  const field = h("div.connect-field", ip);
  const msg = h("div.connect-msg", reason || "");
  let onBtn = false;
  const btn = h("button.btn.primary", "Bağlan");
  const kb = keyboard({ value: ip, digits: true, cols: 3, onChange: (v) => { ip = v; field.textContent = v || " "; } });
  const el = h("div.screen.connect",
    h("div.logo", "G-FLIX"),
    h("h1", "Bilgisayardaki G-Flix sunucusuna bağlan"),
    h("p.connect-help", "Linux bilgisayarda bir kere ", h("code", "bash linux/yayin-kur.sh"), " çalıştır; sonra bilgisayarın IP adresini buraya yaz (kurulumun sonunda yazar)."),
    h("div.connect-body", kb.el, h("div.connect-side", field, btn, msg)));
  kb.focus();
  const paint = () => { btn.classList.toggle("focus", onBtn); onBtn ? kb.blur() : kb.focus(); };
  async function go() {
    const base = `http://${ip.trim()}:8766`;
    msg.textContent = "Bağlanılıyor…";
    const p = await Net.ping(base, 4000);
    if (!p) { msg.textContent = "Bu adreste G-Flix sunucusu yok. Bilgisayar açık mı, servis kurulu mu?"; return; }
    Net.save(base);
    if (p.yayin && !p.auth) return keyScreen("");
    await loadCatalog(true);
  }
  Screens.replaceAll({ el, key(k) {
    if (k === "back") return exitApp();
    if (onBtn) { if (k === "left") { onBtn = false; paint(); } else if (k === "ok") go(); return; }
    const out = kb.key(k);
    if (out === "right" || out === "down") { onBtn = true; paint(); }
  } });
}

// ---------------------------------------------------------------- profiller ("Kim izliyor?")
function profilesScreen(manage = false) {
  const profs = Store.state.profiles;
  const items = profs.map((p) => ({ el: h("div.prof", h("div.prof-av", avatar(p.avatar, 220)), h("div.prof-name", p.name), p.kids ? h("div.prof-kids", "Çocuk") : null), act: () => manage ? editProfile(p) : enterProfile(p) }));
  items.push({ el: h("div.prof.add", h("div.prof-av.add-av", svg("plus")), h("div.prof-name", "Profil Ekle")), act: () => editProfile(null) });
  const manageBtn = h("button.btn.outline", manage ? "Bitti" : "Profilleri Yönet");
  let i = Math.max(0, profs.findIndex((p) => p.id === Store.state.active)), onBtn = false;
  const el = h("div.screen.profiles" + (manage ? ".managing" : ""), h("div.logo.corner", "G-FLIX"), h("h1", manage ? "Profilleri Yönet:" : "Kim izliyor?"), h("div.prof-list", items.map((x) => x.el)), manageBtn);
  const paint = () => { items.forEach((x, j) => x.el.classList.toggle("focus", !onBtn && i === j)); manageBtn.classList.toggle("focus", onBtn); };
  paint();
  Screens.replaceAll({ el, key(k) {
    if (k === "left" && !onBtn) i = clamp(i - 1, 0, items.length - 1);
    else if (k === "right" && !onBtn) i = clamp(i + 1, 0, items.length - 1);
    else if (k === "down") onBtn = true;
    else if (k === "up") onBtn = false;
    else if (k === "ok") return onBtn ? profilesScreen(!manage) : items[i].act();
    else if (k === "back") return manage ? profilesScreen(false) : confirmBox("G-Flix'ten çıkılsın mı?", exitApp, { yesLabel: "Çık" });
    paint();
  } });
}

function editProfile(p) {
  const draft = Object.assign({ name: "", avatar: Store.state.profiles.length % 8, kids: false, titleLang: "tr", autoplayTrailers: true }, p || {});
  const LANGS = [["tr", "Türkçe"], ["en", "İngilizce"], ["original", "Orijinal"], ["mixed", "Karışık"]];
  const nameEl = h("div.ed-name");
  const av = h("div.ed-av");
  const rows = [
    { label: () => "Avatar", value: () => "◀ ▶ ile değiştir", left: () => (draft.avatar = (draft.avatar + 7) % 8), right: () => (draft.avatar = (draft.avatar + 1) % 8) },
    { label: () => "Çocuk profili", value: () => (draft.kids ? "Açık" : "Kapalı"), ok: () => (draft.kids = !draft.kids) },
    { label: () => "Başlık dili", value: () => LANGS.find((x) => x[0] === draft.titleLang)[1], ok: () => { const j = LANGS.findIndex((x) => x[0] === draft.titleLang); draft.titleLang = LANGS[(j + 1) % LANGS.length][0]; } },
    { label: () => "Fragmanları otomatik oynat", value: () => (draft.autoplayTrailers ? "Açık" : "Kapalı"), ok: () => (draft.autoplayTrailers = !draft.autoplayTrailers) },
    { label: () => "Kaydet", button: "primary", ok: save },
  ];
  if (p) rows.push({ label: () => "Profili Sil", button: "outline", ok: () => confirmBox(`"${p.name}" profili silinsin mi? İzleme geçmişi de silinir.`, () => { Store.deleteProfile(p.id); profilesScreen(true); }, { yesLabel: "Sil" }) });
  const rowEls = rows.map(() => h("div.ed-row"));
  const kb = keyboard({ value: draft.name, onChange: (v) => { draft.name = v; paint(); } });
  let area = "kb", i = 0;
  const el = h("div.screen.edit", h("h1", p ? "Profili Düzenle" : "Profil Ekle"),
    h("div.ed-body", h("div.ed-left", av, nameEl, kb.el), h("div.ed-right", rowEls)));
  function save() {
    if (!draft.name.trim()) { toast("Profil adı yaz."); area = "kb"; return paint(); }
    draft.name = draft.name.trim().replace(/^./, (c) => c.toLocaleUpperCase("tr"));
    if (p) Store.updateProfile(p.id, draft); else Store.addProfile(draft);
    profilesScreen(false);
  }
  function paint() {
    clear(av).append(avatar(draft.avatar, 200));
    nameEl.textContent = draft.name || "Ad";
    nameEl.classList.toggle("empty", !draft.name);
    area === "kb" ? kb.focus() : kb.blur();
    rows.forEach((r, j) => {
      const e = clear(rowEls[j]);
      e.className = "ed-row" + (r.button ? " btnrow " + r.button : "") + (area === "rows" && i === j ? " focus" : "");
      e.appendChild(h("span", r.label())); if (r.value) e.appendChild(h("b", r.value()));
    });
  }
  paint();
  Screens.push({ el, key(k) {
    if (k === "back") return Screens.pop();
    if (area === "kb") { if (kb.key(k) === "right") area = "rows"; return paint(); }
    const r = rows[i];
    if (k === "up") i = clamp(i - 1, 0, rows.length - 1);
    else if (k === "down") i = clamp(i + 1, 0, rows.length - 1);
    else if (k === "left") { if (r.left) r.left(); else area = "kb"; }
    else if (k === "right") { if (r.right) r.right(); }
    else if (k === "ok") { if (r.ok) r.ok(); else if (r.right) r.right(); }
    paint();
  } });
}

function enterProfile(p) {
  Store.setActive(p.id);
  refreshTitles();
  browseScreen("home");
}
function refreshTitles() {
  titles = L.visibleTitles(catalog, Store.profile).map((t) => L.localize(t, Store.profile));
  soon = L.comingSoonTitles(catalog, Store.profile).map((t) => L.localize(t, Store.profile));
  soonSeries = L.comingSoonSeries(catalog, Store.profile).map((t) => L.localize(t, Store.profile));
}


// ---------------------------------------------------------------- fragman (kahraman alanı ve detay)
function trailerUrl(t) {
  const s = pickTrailer(t, Store.data.audioLang || "tr");
  if (!s) return null;
  // Anlık yayın: bilgisayar YouTube'dan o an çözer (fragman dosyası tutulmaz)
  if (s.youtube) return { url: Net.url(`/yt/${s.youtube}/master.m3u8?k=${encodeURIComponent(Net.key)}`), hls: true };
  if (s.direct && s.direct.video) return { url: Net.url(partsUrl(s.direct.video, "mp4")), hls: false };
  if (s.master) return { url: Net.url(hlsUrl(s.master)), hls: true };
  return null;
}
/** Arka plan görseli + gecikmeli fragman. Fragman başlayınca görsel solar; bitince geri gelir. */
function backdropPlayer(container, { delay = 2500, muted = false, onStart, onEnd } = {}) {
  const imgA = h("div.bg-img"), imgB = h("div.bg-img");
  const video = h("video.bg-video", { playsinline: true, preload: "none" });
  let front = imgA, timer = null, hls = null, cur = null, token = 0;
  container.append(imgA, imgB, video);
  video.muted = muted;
  video.addEventListener("playing", () => { container.classList.add("trailer-on"); onStart && onStart(); });
  video.addEventListener("ended", () => stop());
  video.addEventListener("error", () => stop());
  function stop() {
    clearTimeout(timer);
    if (hls) { hls.destroy(); hls = null; }
    if (video.getAttribute("src") || !video.paused) { video.pause(); video.removeAttribute("src"); try { video.load(); } catch (_) {} }
    if (container.classList.contains("trailer-on")) { container.classList.remove("trailer-on"); onEnd && onEnd(); }
  }
  function start(t) {
    const tr = trailerUrl(t);
    if (!tr) return;
    if (tr.hls && window.Hls && Hls.isSupported() && !video.canPlayType("application/vnd.apple.mpegurl")) { hls = new Hls({ maxBufferLength: 20 }); hls.loadSource(tr.url); hls.attachMedia(video); }
    else video.src = tr.url;
    video.play().catch(() => {});
  }
  return {
    video,
    async set(t, { autoplay = true } = {}) {
      const my = ++token;
      stop();
      cur = t;
      const url = tmdb(t.backdrop, "w1280");
      await preload(url);
      if (my !== token) return;
      const back = front === imgA ? imgB : imgA;
      back.style.backgroundImage = url ? `url("${url}")` : "none";
      back.classList.add("show"); front.classList.remove("show");
      front = back;
      if (autoplay && Store.profile && Store.profile.autoplayTrailers !== false && allTrailers(t).length) timer = setTimeout(() => { if (my === token) start(t); }, delay);
    },
    replay() { if (cur) { stop(); start(cur); } },
    get playing() { return container.classList.contains("trailer-on"); },
    set muted(m) { video.muted = m; },
    get muted() { return video.muted; },
    stop() { token++; stop(); },
    pause() { token++; stop(); },
  };
}

/** Tema müziği kaldırıldı (GitHub istek hakkı): eski çağrılar bozulmasın diye boş oynatıcı. */
function themePlayer() {
  return { fadeIn() {}, fadeOut() {}, stop() {} };
}

function metaLine(t, big) {
  const bits = [];
  const m = L.matchPercent(t); if (m) bits.push(h("span.match", `%${m} Eşleşme`));
  if (t.year) bits.push(h("span", String(t.year)));
  if (t.certification || t.maturity != null) bits.push(h("span.cert", t.certification || `${t.maturity}+`));
  const extra = L.seasonsText(t) || L.formatRuntime(t.runtime); if (extra) bits.push(h("span", extra));
  L.qualityBadges(t).forEach((b) => bits.push(h(b.startsWith("Dolby") ? "span.dolby" : "span.hd", b)));
  return h("div.meta" + (big ? ".big" : ""), bits);
}
function langLine(t) {
  const l = L.languageBadges(t);
  return l.length ? h("div.lang-line", l.map((x) => h("span", (x.includes("Dublaj") ? "🔊 " : "💬 ") + x))) : null;
}
function soonLine(t) {
  const at = L.nextRelease(t);
  const notes = L.activeNotes(t).slice(0, 2).map((n) => h("div.note-line", n.text));
  // Devamı gelen dizi: "🆕 Yeni bölümler: 1–15. bölümler 7 Kasım Cuma 20:00" ya da "Yeni bölümler yakında"
  if (L.isContinuingSeries(t)) {
    const line = L.seriesSoonText(t);
    return line || notes.length ? h("div", line ? h("div.soon-line", line) : null, notes) : null;
  }
  const missing = !at && L.hasPlayable(t) ? L.newEpisodesText(t) : null;
  if (L.hasPlayable(t) && !at && !missing) return notes.length ? h("div", notes) : null;
  const plan = L.planText(t);
  return h("div", h("div.soon-line", missing || L.releaseText(at, L.hasPlayable(t))), plan ? h("div.plan-line", "🗓️ " + plan) : null, notes);
}
/** Profil dilinde logo yoksa İngilizce (ya da yazısız) logo; o da yoksa ad. */
function logoOf(t) {
  if (t.logo) return t.logo;
  const ls = t.logos || [];
  const l = ls.find((x) => x.lang === "en") || ls.find((x) => !x.lang) || ls.find((x) => x.lang === t.originalLanguage);
  return l ? l.url : null;
}
/** Logo resmi; inmezse (ağ, TMDB) yerine ad yazılır. */
function logoImg(t, cls, size) {
  const url = logoOf(t);
  const text = () => h(cls.text, t.title);
  if (!url) return text();
  const img = h(cls.img, { src: tmdb(url, size), alt: t.title });
  img.onerror = () => { if (img.parentNode) img.parentNode.replaceChild(text(), img); };
  return img;
}
function titleArt(t, cls = "") {
  return logoImg(t, { img: "img.title-logo" + cls, text: "div.title-text" + cls }, "w500");
}
const tileLogo = (t) => logoImg(t, { img: "img.tile-logo", text: "div.tile-name" }, "w300");

// ---------------------------------------------------------------- ana gezinme (sol menü + kahraman + satırlar)
const NAV = [
  { key: "search", label: "Ara", icon: "search" },
  { key: "home", label: "Ana Sayfa", icon: "home" },
  { key: "series", label: "Diziler", icon: "tv" },
  { key: "movies", label: "Filmler", icon: "film" },
  { key: "tr-series", label: "Türk Dizileri", icon: "trtv" },
  { key: "tr-movies", label: "Türk Filmleri", icon: "trfilm" },
  { key: "soon", label: "Yakında", icon: "calendar" },
  { key: "soon-series", label: "Yakında Gelen Diziler", icon: "tv" },
  { key: "mylist", label: "Listem", icon: "plus" },
  { key: "settings", label: "Ayarlar", icon: "gear" },
];
const EXTRA_ICONS = {
  home: '<path d="M3 11l9-7 9 7v9h-6v-6H9v6H3z" fill="currentColor"/>',
  tv: '<rect x="2.5" y="5" width="19" height="13" rx="1.5" stroke="currentColor" stroke-width="2" fill="none"/><path d="M8 21h8" stroke="currentColor" stroke-width="2"/>',
  trtv: '<rect x="2.5" y="5" width="19" height="13" rx="1.5" stroke="currentColor" stroke-width="2" fill="none"/><text x="12" y="14.6" text-anchor="middle" font-size="7.5" font-weight="900" fill="currentColor" font-family="Inter,sans-serif">TR</text><path d="M8 21h8" stroke="currentColor" stroke-width="2"/>',
  trfilm: '<rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2" fill="none"/><text x="12" y="15" text-anchor="middle" font-size="8" font-weight="900" fill="currentColor" font-family="Inter,sans-serif">TR</text>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2" stroke="currentColor" stroke-width="2" fill="none"/><path d="M3 10h18M8 3v4M16 3v4" stroke="currentColor" stroke-width="2"/>',
  film: '<rect x="3" y="3" width="18" height="18" rx="2" stroke="currentColor" stroke-width="2" fill="none"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4" stroke="currentColor" stroke-width="2"/>',
};
function icon(name) {
  if (!EXTRA_ICONS[name]) return svg(name);
  const s = h("span.ic"); s.innerHTML = `<svg viewBox="0 0 24 24">${EXTRA_ICONS[name]}</svg>`; return s;
}

function browseScreen(section) {
  const prof = Store.profile;
  const navItems = [{ key: "profile", label: prof.name, el: null }, ...NAV].map((n) => {
    n = { ...n };
    n.el = h("div.nav-item", n.key === "profile" ? h("span.nav-av", avatar(prof.avatar, 56)) : icon(n.icon), h("span.nav-label", n.key === "profile" ? "Profil Değiştir" : n.label));
    return n;
  });
  const nav = h("div.nav", h("div.nav-items", navItems.map((n) => n.el)));
  const heroBg = h("div.hero-bg");
  const heroInfo = h("div.hero-info");
  const rowsWrap = h("div.rows");
  const el = h("div.screen.browse", heroBg, h("div.hero-shade"), heroInfo, h("div.rows-clip", rowsWrap), nav);
  const bp = backdropPlayer(heroBg, { delay: 2600, onStart: () => el.classList.add("trailer"), onEnd: () => el.classList.remove("trailer") });

  let area = "rows", navIdx = 1, rowIdx = 0, rows = [], heroTimer = null, heroTitle = null;
  const TILE_W = 392, GAP = 16;

  function build() {
    const data = Store.data;
    const pool = ["series", "movies", "tr-series", "tr-movies"].includes(section) ? L.applyFilter(titles, section) : titles;
    let defs;
    if (section === "soon") {
      defs = soon.length ? [{ key: "soon", title: "Yakında", items: soon }] : [];
    } else if (section === "soon-series") {
      defs = soonSeries.length ? [{ key: "soon-series", title: "Yakında Gelen Diziler", items: soonSeries }] : [];
    } else if (section === "mylist") {
      const byId = new Map(titles.map((t) => [t.id, t]));
      const mine = (data.myList || []).map((id) => byId.get(id)).filter(Boolean);
      defs = mine.length ? [{ key: "mylist", title: "Listem", items: mine }] : [];
    } else {
      defs = L.homeRows(pool, data, section === "home" ? "all" : section);
      const cw = L.continueWatching(pool, data);
      if (cw.length) defs.splice(defs.findIndex((r) => r.key === "mylist") + 1, 0, { key: "continue", title: `${prof.name} için İzlemeye Devam Et`, items: cw.map((c) => c.title), cw });
      const sn = L.applyFilter(soon, section === "home" ? "all" : section);
      const ss = L.applyFilter(soonSeries, section === "home" ? "all" : section);
      const extra = [...(sn.length ? [{ key: "soon", title: "Yakında", items: sn }] : []),
        ...(ss.length ? [{ key: "soon-series", title: "Yakında Gelen Diziler", items: ss }] : [])];
      if (extra.length) { const at = defs.findIndex((r) => r.key === "new"); defs.splice(at < 0 ? 0 : at + 1, 0, ...extra); }
    }
    clear(rowsWrap);
    rows = defs.map((d) => {
      const tiles = d.items.map((t, j) => tileFor(t, d, j));
      const track = h("div.row-track", tiles.map((x) => x.el));
      const rowEl = h("div.row" + (d.style === "top10" ? ".top10" : ""), h("div.row-title", d.title), track);
      rowsWrap.appendChild(rowEl);
      return { def: d, tiles, track, el: rowEl, col: 0 };
    });
    if (!rows.length) {
      rowsWrap.appendChild(h("div.empty-row", section === "mylist" ? "Listen boş. Bir yapımın sayfasında “Listem”e ekleyebilirsin." : section === "soon" ? "Yakında gelecek bir şey yok." : section === "soon-series" ? "Devamı beklenen dizi yok." : "Burada henüz bir şey yok."));
      clear(heroInfo);
    }
    rowIdx = clamp(rowIdx, 0, Math.max(0, rows.length - 1));
    layout(true);
  }

  function tileFor(t, d, j) {
    const cw = d.cw && d.cw[j];
    let el;
    if (d.style === "top10") el = h("div.tile.rank", h("div.rank-num", String(j + 1)), h("div.rank-poster", { style: { backgroundImage: `url("${tmdb(t.poster, "w342")}")` } },
      !t.posterHasText && t.logo ? h("div.poster-logo", tileLogo(t)) : null));
    else {
      const prog = cw && cw.progress ? fraction(cw.progress) : null;
      el = h("div.tile", { style: { backgroundImage: `url("${tmdb(t.backdrop, "w780")}")` } },
        h("div.tile-shade"),
        tileLogo(t),
        cw && cw.season != null ? h("div.tile-ep", `S${cw.season}:B${cw.episode}`) : null,
        prog != null ? h("div.tile-prog", h("i", { style: { width: `${Math.round(prog * 100)}%` } })) : null,
        d.key === "soon" || d.key === "soon-series" ? h("div.tile-badge.soon", L.nextRelease(t) ? L.shortDate(L.nextRelease(t)) : d.key === "soon-series" ? "Yeni bölüm" : "Yakında")
          : isNew(t) ? h("div.tile-badge", L.isSeries(t) && t.updated && t.updated !== t.added ? "Yeni Bölüm" : "Yeni Eklendi") : null);
    }
    return { el, title: t, cw };
  }
  const isNew = (t) => Date.now() / 1000 - L.lastChange(t) < 14 * 86400;

  function layout(instant) {
    el.classList.toggle("nav-open", area === "nav");
    navItems.forEach((n, j) => n.el.classList.toggle("focus", area === "nav" && j === navIdx));
    navItems.forEach((n) => n.el.classList.toggle("current", n.key === section));
    rows.forEach((r, ri) => {
      const off = ri - rowIdx;
      r.el.classList.toggle("active", ri === rowIdx);
      r.el.classList.toggle("above", off < 0);
      r.el.style.transform = `translateY(${off < 0 ? -340 : rowY(off)}px)`;
      const w = r.def.style === "top10" ? 330 + GAP : TILE_W + GAP;
      r.track.style.transform = `translateX(${-r.col * w}px)`;
      r.tiles.forEach((tl, ci) => tl.el.classList.toggle("focus", area === "rows" && ri === rowIdx && ci === r.col));
      if (instant) { r.el.style.transition = "none"; r.track.style.transition = "none"; r.el.offsetHeight; r.el.style.transition = ""; r.track.style.transition = ""; }
    });
    scheduleHero();
  }
  // odaktaki satır sabit yerde durur; altındakiler sırayla dizilir
  function rowY(off) {
    let y = 0;
    for (let k = 0; k < off; k++) y += rows[rowIdx + k].def.style === "top10" ? 420 : 300;
    return y;
  }
  const focused = () => (rows[rowIdx] && rows[rowIdx].tiles[rows[rowIdx].col]) || null;

  function scheduleHero() {
    const f = focused();
    if (!f || f.title === heroTitle) return;
    clearTimeout(heroTimer);
    bp.stop();
    heroInfo.classList.add("fade");
    heroTimer = setTimeout(() => showHero(f.title), 320);
  }
  function showHero(t) {
    heroTitle = t;
    clear(heroInfo).append(titleArt(t), metaLine(t), soonLine(t), h("div.hero-overview", t.overview || ""), h("div.hero-genres", (t.genres || []).slice(0, 3).join(" • ")), langLine(t));
    heroInfo.classList.remove("fade");
    bp.set(t);
  }

  function navAct(n) {
    if (n.key === "profile") return profilesScreen(false);
    if (n.key === "search") return searchScreen();
    if (n.key === "settings") return settingsScreen();
    if (n.key !== section) { section = n.key; rowIdx = 0; heroTitle = null; area = "rows"; build(); }
    else { area = "rows"; layout(); }
  }

  build();
  Screens.replaceAll({
    el,
    show() { const keep = heroTitle; heroTitle = null; build(); if (!keep) return; },
    hide() { bp.stop(); clearTimeout(heroTimer); },
    destroy() { bp.stop(); clearTimeout(heroTimer); },
    visibility(v) { if (!v) bp.stop(); },
    key(k) {
      if (area === "nav") {
        if (k === "up") navIdx = clamp(navIdx - 1, 0, navItems.length - 1);
        else if (k === "down") navIdx = clamp(navIdx + 1, 0, navItems.length - 1);
        else if (k === "right" && rows.length) area = "rows";
        else if (k === "ok") return navAct(navItems[navIdx]);
        else if (k === "back") return confirmBox("G-Flix'ten çıkılsın mı?", exitApp, { yesLabel: "Çık" });
        return layout();
      }
      const r = rows[rowIdx];
      if (!r) { if (k === "left" || k === "back") { area = "nav"; layout(); } return; }
      if (k === "left") { if (r.col === 0) { area = "nav"; navIdx = Math.max(1, navItems.findIndex((n) => n.key === section)); } else r.col--; }
      else if (k === "right") r.col = clamp(r.col + 1, 0, r.tiles.length - 1);
      else if (k === "up") { if (rowIdx > 0) rowIdx--; }
      else if (k === "down") rowIdx = clamp(rowIdx + 1, 0, rows.length - 1);
      else if (k === "chup") rowIdx = clamp(rowIdx - 3, 0, rows.length - 1);
      else if (k === "chdown") rowIdx = clamp(rowIdx + 3, 0, rows.length - 1);
      else if (k === "ok") { const f = focused(); if (f) return f.cw ? playFrom(f.title, f.cw.season, f.cw.episode, f.cw.progress ? f.cw.progress.positionMs : 0) : detailScreen(f.title); }
      else if (k === "play" || k === "playpause") { const f = focused(); if (f) return L.hasPlayable(f.title) ? playTitle(f.title) : detailScreen(f.title); }
      else if (k === "back") { if (r.col > 0) r.col = 0; else if (rowIdx > 0) rowIdx = 0; else { area = "nav"; navIdx = Math.max(1, navItems.findIndex((n) => n.key === section)); } }
      layout();
    },
  });
}

// ---------------------------------------------------------------- oynatma kısayolları
function playTitle(t) {
  const target = L.playTarget(t, Store.data);
  if (!target) return toast("Bu yapımın videosu henüz yüklenmemiş.");
  playFrom(t, target.season, target.episode, target.positionMs);
}
async function playFrom(t, season, episode, positionMs) {
  const go = () => openPlayer(t, season, episode, positionMs || 0, { titles });
  const r = await canStart();
  if (r.ok) return go();
  // Sıradasın: ayrı bir ekran; Geri ile vazgeçilir
  const el = h("div.screen.queue-screen");
  let remove = null;
  Screens.push({ el, key(k) { if (k === "back") { remove && remove(); Screens.pop(); } }, destroy() { remove && remove(); } });
  remove = queueOverlay(el, t.title, false, () => { Screens.pop(); go(); });
}

// ---------------------------------------------------------------- detay sayfası
function detailScreen(t) {
  const bg = h("div.hero-bg");
  const info = h("div.detail-info");
  const btnWrap = h("div.detail-btns");
  const simRow = h("div.detail-similar");
  const el = h("div.screen.detail", bg, h("div.detail-shade"), info, simRow);
  let muted = false;
  // Tema müziği (Plex/Emby gibi): fragman çalmıyorken ya da sesi kapalıyken
  const theme = themePlayer(t);
  const bp = backdropPlayer(bg, { delay: 1400,
    onStart: () => { el.classList.add("trailer"); if (!bp.muted) theme.fadeOut(); },
    onEnd: () => { el.classList.remove("trailer", "immersive"); paint(); theme.fadeIn(); } });
  let area = "btns", i = 0, si = 0, buttons = [], sims = L.similar(t, titles, 12);

  function render() {
    const data = Store.data;
    const series = L.isSeries(t);
    const target = L.playTarget(t, data);
    const latest = series ? L.latestProgress(t.id, data) : data.progress[t.id];
    const resumable = latest && !isFinished(latest) && latest.positionMs > 15000;
    const inList = (data.myList || []).includes(t.id);
    const liked = (data.liked || []).includes(t.id);
    const epLabel = target && series ? `S${target.season}:B${target.episode} ` : "";
    buttons = [];
    if (target) buttons.push({ icon: "play", label: resumable ? `${epLabel}Devam Et` : series ? `${epLabel}Oynat` : "Oynat", primary: true, act: () => playFrom(t, target.season, target.episode, target.positionMs) });
    if (target && (resumable || (series && (target.season !== L.episodes(t)[0].season.number || target.episode !== L.episodes(t)[0].episode.number))))
      buttons.push({ icon: "replay", label: series ? "Bölüme Baştan Başla" : "Baştan Başlat", act: () => playFrom(t, target.season, target.episode, 0) });
    if (series && L.episodes(t).length) buttons.push({ icon: "episodes", label: "Bölümler ve Daha Fazlası", act: () => episodesScreen(t) });
    if (series && target && (target.season !== L.episodes(t)[0].season.number || target.episode !== L.episodes(t)[0].episode.number))
      buttons.push({ icon: "replay", label: "Diziye En Baştan Başla", act: () => { const f = L.episodes(t)[0]; playFrom(t, f.season.number, f.episode.number, 0); } });
    if (allTrailers(t).length) buttons.push({ icon: "play", label: "Fragmanı İzle", act: () => { el.classList.add("immersive"); bp.muted = false; muted = false; bp.replay(); } });
    buttons.push({ icon: inList ? "check" : "plus", label: inList ? "Listemde" : "Listeme Ekle", act: () => { Store.toggleList(t.id); render(); } });
    buttons.push({ icon: liked ? "liked" : "like", label: liked ? "Beğendin" : "Beğen", act: () => { Store.toggleLike(t.id); render(); } });
    i = clamp(i, 0, buttons.length - 1);

    const progressBit = resumable && latest.durationMs ? h("div.detail-progress", h("div.bar", h("i", { style: { width: `${Math.round(fraction(latest) * 100)}%` } })),
      h("span", `${L.formatClock(Math.max(0, latest.durationMs - latest.positionMs))} kaldı`)) : null;
    clear(info).append(
      titleArt(t, ".big"),
      metaLine(t, true),
      soonLine(t),
      langLine(t),
      progressBit,
      t.tagline ? h("div.detail-tagline", t.tagline) : null,
      h("div.detail-overview", t.overview || ""),
      (t.cast || []).length ? h("div.detail-credits", h("span", "Oyuncular: "), t.cast.slice(0, 4).join(", ")) : null,
      (t.genres || []).length ? h("div.detail-credits", h("span", "Türler: "), t.genres.join(", ")) : null,
      clear(btnWrap).appendChild(h("div", buttons.map((b) => (b.el = h("div.dbtn" + (b.primary ? ".primary" : ""), svg(b.icon), h("span", b.label)))))).parentNode);
    clear(simRow);
    if (sims.length) simRow.append(h("div.row-title", "Benzerleri"), h("div.row-track", sims.map((s) => h("div.tile.small", { style: { backgroundImage: `url("${tmdb(s.backdrop, "w780")}")` } }, h("div.tile-shade"), tileLogo(s)))));
    paint();
  }
  function paint() {
    buttons.forEach((b, j) => b.el.classList.toggle("focus", area === "btns" && j === i));
    el.classList.toggle("sim-open", area === "sims");
    const tiles = simRow.querySelectorAll(".tile");
    tiles.forEach((x, j) => x.classList.toggle("focus", area === "sims" && j === si));
    const track = simRow.querySelector(".row-track");
    if (track) track.style.transform = `translateX(${-si * 316}px)`;
  }

  render();
  bp.set(t);
  theme.fadeIn();
  Screens.push({
    el,
    show() { render(); if (!bp.playing) bp.set(t); theme.fadeIn(); },
    hide() { bp.stop(); theme.stop(); },
    destroy() { bp.stop(); theme.stop(); },
    visibility(v) { if (!v) { bp.stop(); theme.stop(); } },
    key(k) {
      if (el.classList.contains("immersive")) { el.classList.remove("immersive"); if (k === "back") return; return paint(); }
      if (k === "back") return Screens.pop();
      if (k === "red" || k === "yellow") { muted = !muted; bp.muted = muted; if (muted || !bp.playing) theme.fadeIn(); else theme.fadeOut(); return toast(muted ? "Fragman sesi kapalı" : "Fragman sesi açık", 1200); }
      if (k === "play" || k === "playpause") return playTitle(t);
      if (area === "btns") {
        if (k === "up") i = clamp(i - 1, 0, buttons.length - 1);
        else if (k === "down") { if (i < buttons.length - 1) i++; else if (sims.length) area = "sims"; }
        else if (k === "ok") return buttons[i].act();
      } else {
        if (k === "up") area = "btns";
        else if (k === "left") si = clamp(si - 1, 0, sims.length - 1);
        else if (k === "right") si = clamp(si + 1, 0, sims.length - 1);
        else if (k === "ok") { Screens.pop(); return detailScreen(sims[si]); }
      }
      paint();
    },
  });
}

// ---------------------------------------------------------------- bölümler
function episodesScreen(t, opts = {}) {
  const seasons = [...(t.seasons || [])].filter((s) => (s.episodes || []).some((e) => e.stream)).sort((a, b) => a.number - b.number);
  const data = Store.data;
  const target = L.playTarget(t, data);
  let si = Math.max(0, seasons.findIndex((s) => s.number === (opts.season != null ? opts.season : target && target.season)));
  let ei = 0, area = "eps";
  const seasonEls = seasons.map((s) => h("div.season", s.name && !/^Sezon \d+$/.test(s.name) ? s.name : `${s.number}. Sezon`, h("small", `${s.episodes.filter((e) => e.stream).length} bölüm`)));
  const list = h("div.ep-list");
  const listClip = h("div.ep-clip", list);
  const el = h("div.screen.episodes", { style: { backgroundImage: `url("${tmdb(t.backdrop, "w1280")}")` } }, h("div.ep-shade"),
    h("div.ep-head", titleArt(t), h("div.ep-head-title", "Bölümler")),
    h("div.ep-body", h("div.seasons", seasonEls), listClip));
  let eps = [];
  function renderList(keepEp) {
    const s = seasons[si];
    eps = s.episodes.filter((e) => e.stream).sort((a, b) => a.number - b.number);
    clear(list);
    eps.forEach((e) => {
      const p = data.progress[L.progressKey(t.id, s.number, e.number)];
      e._el = h("div.ep",
        h("div.ep-num", String(e.number)),
        h("div.ep-thumb", { style: { backgroundImage: `url("${tmdb(e.still || t.backdrop, "w500")}")` } }, h("div.ep-play", svg("play")), p ? h("div.ep-prog", h("i", { style: { width: `${Math.round(fraction(p) * 100)}%` } })) : null),
        h("div.ep-text", h("div.ep-title", h("b", e.title || `Bölüm ${e.number}`), h("span", L.formatRuntime(e.runtime) || "")), h("div.ep-ov", e.overview || "")));
      list.appendChild(e._el);
    });
    if (!keepEp) {
      ei = 0;
      if (target && target.season === s.number) ei = Math.max(0, eps.findIndex((e) => e.number === target.episode));
    }
    paint();
  }
  function paint() {
    seasonEls.forEach((x, j) => { x.classList.toggle("current", j === si); x.classList.toggle("focus", area === "seasons" && j === si); });
    eps.forEach((e, j) => e._el.classList.toggle("focus", area === "eps" && j === ei));
    el.classList.toggle("seasons-focus", area === "seasons");
    list.style.transform = `translateY(${-Math.max(0, ei - 1) * 236}px)`;
  }
  renderList();
  Screens.push({
    el,
    show() { renderList(true); },
    key(k) {
      if (k === "back") return Screens.pop();
      if (area === "seasons") {
        if (k === "up" && si > 0) { si--; renderList(); return; }
        if (k === "down" && si < seasons.length - 1) { si++; renderList(); return; }
        if (k === "right" || k === "ok") area = "eps";
      } else {
        if (k === "up") ei = clamp(ei - 1, 0, eps.length - 1);
        else if (k === "down") ei = clamp(ei + 1, 0, eps.length - 1);
        else if (k === "left" && seasons.length > 1) area = "seasons";
        else if (k === "left") return Screens.pop();
        else if (k === "ok" || k === "play" || k === "playpause") {
          const s = seasons[si], e = eps[ei];
          const p = data.progress[L.progressKey(t.id, s.number, e.number)];
          const go = () => playFrom(t, s.number, e.number, p && !isFinished(p) ? p.positionMs : 0);
          if (p && !isFinished(p) && p.positionMs > 15000) return choiceBox(`B${e.number}: ${e.title || ""}`, [
            { label: `Kaldığın yerden devam et (${L.formatClock(p.positionMs)})`, on: true, pick: go },
            { label: "Baştan başlat", pick: () => playFrom(t, s.number, e.number, 0) }]);
          return go();
        }
      }
      paint();
    },
  });
}

// ---------------------------------------------------------------- arama
function searchScreen() {
  const field = h("div.search-field");
  const results = h("div.search-grid");
  const label = h("div.row-title");
  let found = [], ri = 0, area = "kb";
  const COLS = 3;
  const kb = keyboard({ onChange: (v) => run(v) });
  const el = h("div.screen.search", h("div.search-left", h("div.search-q", svg("search"), field), kb.el), h("div.search-right", label, h("div.search-clip", results)));
  function run(q) {
    field.textContent = q || "Film, dizi, tür, oyuncu";
    field.classList.toggle("empty", !q);
    found = q.trim() ? L.search(titles, q) : [...titles].sort((a, b) => (b.rating || 0) - (a.rating || 0));
    label.textContent = q.trim() ? (found.length ? "Sonuçlar" : `“${q}” için sonuç yok`) : "Önerilenler";
    clear(results);
    found.forEach((t) => results.appendChild(h("div.tile.small", { style: { backgroundImage: `url("${tmdb(t.backdrop, "w780")}")` } }, h("div.tile-shade"), tileLogo(t))));
    ri = 0; paint();
  }
  function paint() {
    area === "kb" ? kb.focus() : kb.blur();
    [...results.children].forEach((x, j) => x.classList.toggle("focus", area === "res" && j === ri));
    results.style.transform = `translateY(${-Math.max(0, Math.floor(ri / COLS) - 1) * 220}px)`;
  }
  run("");
  Screens.push({ el, key(k) {
    if (k === "back") return Screens.pop();
    if (area === "kb") { if (kb.key(k) === "right" && found.length) area = "res"; else if (k === "left") {} return paint(); }
    if (k === "left") { if (ri % COLS === 0) area = "kb"; else ri--; }
    else if (k === "right") ri = clamp(ri + 1, 0, found.length - 1);
    else if (k === "up") ri = ri - COLS >= 0 ? ri - COLS : ri;
    else if (k === "down") ri = clamp(ri + COLS, 0, found.length - 1);
    else if (k === "ok") return detailScreen(found[ri]);
    paint();
  } });
}

// ---------------------------------------------------------------- ayarlar
function settingsScreen() {
  const p = Store.profile;
  const items = [
    { label: "Profili düzenle", act: () => editProfile(p) },
    { label: () => "Altyazı boyutu: " + ({ s: "Küçük", m: "Orta", l: "Büyük", xl: "Çok Büyük" }[localStorage.getItem("gflix.tv.subsize") || "m"]),
      act: () => { const o = ["s", "m", "l", "xl"]; const c = localStorage.getItem("gflix.tv.subsize") || "m"; localStorage.setItem("gflix.tv.subsize", o[(o.indexOf(c) + 1) % o.length]); paint(); } },
    { label: "Kataloğu yenile", act: async () => { toast("Katalog yenileniyor…"); try { catalog = await Net.json("/api/catalog?tv=1&refresh=1"); refreshTitles(); toast("Katalog güncel."); } catch (e) { toast("Yenilenemedi: " + e.message); } } },
    { label: `Sunucu: ${Net.base.replace(/^https?:\/\//, "")}`, act: () => connectScreen("") },
    { label: "Profil değiştir", act: () => profilesScreen(false) },
    { label: "Çıkış", act: () => exitApp() },
  ];
  let i = 0;
  const els = items.map(() => h("div.set-item"));
  const el = h("div.screen.settings", h("h1", "Ayarlar"), h("div.set-list", els), h("div.set-foot", `G-Flix TV · ${isTizen ? "Samsung Tizen" : "Tarayıcı"} · ${titles.length} yapım` + (window.GFLIX_REMOTE ? " · arayüz bilgisayardan (güncel)" : "")));
  const paint = () => els.forEach((x, j) => { x.textContent = typeof items[j].label === "function" ? items[j].label() : items[j].label; x.classList.toggle("focus", j === i); });
  paint();
  Screens.push({ el, key(k) {
    if (k === "back") return Screens.pop();
    if (k === "up") i = clamp(i - 1, 0, items.length - 1);
    else if (k === "down") i = clamp(i + 1, 0, items.length - 1);
    else if (k === "ok") return items[i].act();
    paint();
  } });
}

boot();
