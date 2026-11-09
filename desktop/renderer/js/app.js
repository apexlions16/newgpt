// G-Flix PC arayüzü (Netflix masaüstü düzeni; logo, ad ve yazı tipi G-Flix'in kendi).
import { h, svg, clear, avatar, debounce, AVATAR_COLORS } from "./dom.js";
import { Store, fraction } from "./store.js";
import * as L from "./library.js";
import { allTrailers, pickTrailer, trailerSource, trailerKey, attach } from "./media.js";
import { openPlayer } from "./player.js";

const app = document.getElementById("app");
let catalog = null;
let titles = [];          // görünür ve profile göre yerelleştirilmiş
let config = {};
const trailerAudio = { muted: true };

// ------------------------------------------------------------------ başlangıç

async function boot() {
  config = await (await fetch("/api/config")).json();
  if (!config.owner || !config.repo) return setupScreen("İlk kurulum: GitHub deposunu ve erişim anahtarını gir.");
  const r = await fetch("/api/catalog");
  if (r.status === 401) return setupScreen("Erişim anahtarı geçersiz ya da süresi dolmuş. Yenisini gir.");
  catalog = await r.json();
  if (catalog.error) return setupScreen(catalog.error);
  if (!Store.profile) return profileGate();
  enter();
}

function refreshTitles() {
  titles = L.visibleTitles(catalog, Store.profile).map((t) => L.localize(t, Store.profile));
}

function setupScreen(message) {
  clear(app);
  const owner = h("input", { value: config.owner || "", placeholder: "kullanıcı (ör. thedrowned925)" });
  const repo = h("input", { value: config.repo || "g-flix", placeholder: "depo (ör. g-flix)" });
  const token = h("input", { type: "password", placeholder: "github_pat_… (salt-okunur yeterli)" });
  const msg = h("p.setup-msg", message);
  app.append(h("div.setup",
    h("div.logo.big", "G-FLIX"),
    h("div.setup-card",
      h("h2", "Bağlantı"), msg,
      h("label", "GitHub kullanıcı adı", owner), h("label", "Depo", repo), h("label", "Erişim anahtarı (token)", token),
      h("button.btn.red", { onclick: async () => {
        await fetch("/api/config", { method: "POST", body: JSON.stringify({ owner: owner.value, repo: repo.value, token: token.value || undefined }) });
        boot();
      } }, "Bağlan"),
      h("p.hint", "Anahtar sadece bu bilgisayarda saklanır. Android uygulamasındaki okuma anahtarının aynısı olur."))));
}

// ------------------------------------------------------------------ profiller

function profileGate(manage = false) {
  clear(app);
  document.body.className = "gate";
  const ps = Store.state.profiles;
  const grid = h("div.gate-grid",
    ps.map((p) => h("button.gate-item", { onclick: () => (manage ? editProfile(p) : (Store.setActive(p.id), enter())) },
      h("div.gate-av", avatar(p.avatar, 140), manage ? h("div.gate-edit", svg("pencil")) : null),
      h("span", p.name))),
    ps.length < 5 ? h("button.gate-item.add", { onclick: () => editProfile(null) }, h("div.gate-av.plus", svg("plus")), h("span", "Profil Ekle")) : null);
  app.append(h("div.gate-wrap", h("div.logo.corner", "G-FLIX"),
    h("div.gate", h("h1", manage ? "Profilleri Yönet:" : "Kim izliyor?"), grid,
      ps.length ? h("button.gate-manage" + (manage ? ".done" : ""), { onclick: () => profileGate(!manage) }, manage ? "Bitti" : "Profilleri Yönet") : null)));
  if (!ps.length) editProfile(null);
}

function editProfile(p) {
  const isNew = !p;
  p = p || { name: "", avatar: Store.state.profiles.length, kids: false, titleLang: "tr", artMode: "rotate", autoplayTrailers: true };
  let av = p.avatar;
  clear(app);
  const name = h("input.pe-name", { value: p.name, placeholder: "Ad", maxlength: "20" });
  const avWrap = h("div.pe-av");
  const drawAv = () => clear(avWrap).append(avatar(av, 120));
  drawAv();
  const kids = h("input", { type: "checkbox", checked: p.kids ? "checked" : null });
  const lang = select([["tr", "Türkçe"], ["en", "İngilizce"], ["original", "Orijinal"], ["mixed", "Karışık"]], p.titleLang);
  const art = select([["rotate", "Her gün değişsin"], ["fixed", "Sabit"]], p.artMode);
  const auto = h("input", { type: "checkbox", checked: p.autoplayTrailers !== false ? "checked" : null });
  app.append(h("div.pe-wrap", h("div.logo.corner", "G-FLIX"), h("div.pe",
    h("h1", isNew ? "Profil Ekle" : "Profili Düzenle"),
    h("div.pe-body",
      h("div", avWrap, h("div.pe-colors", AVATAR_COLORS.map((_, i) => h("button.pe-color" + (i === av % AVATAR_COLORS.length ? ".on" : ""), { onclick: (e) => { av = i + AVATAR_COLORS.length * Math.floor(Math.random() * 4); drawAv(); [...e.target.parentNode.children].forEach((c) => c.classList.remove("on")); e.currentTarget.classList.add("on"); } }, avatar(i, 34))))),
      h("div.pe-form", name,
        h("label.pe-check", kids, " Çocuk profili (sadece aileye uygun yapımlar)"),
        h("label", "Başlık dili", lang), h("label", "Kapaklar", art),
        h("label.pe-check", auto, " Fragmanlar kendiliğinden oynasın"))),
    h("div.pe-actions",
      h("button.btn.white", { onclick: () => {
        const patch = { name: name.value.trim() || "Profil", avatar: av, kids: kids.checked, titleLang: lang.value, artMode: art.value, autoplayTrailers: auto.checked };
        if (isNew) { const np = Store.addProfile(patch); if (Store.state.profiles.length === 1) { Store.setActive(np.id); return enter(); } }
        else Store.updateProfile(p.id, patch);
        profileGate(!isNew);
      } }, "Kaydet"),
      Store.state.profiles.length ? h("button.btn.outline", { onclick: () => profileGate(!isNew) }, "İptal") : null,
      !isNew ? h("button.btn.outline", { onclick: () => { if (confirm(`"${p.name}" profili silinsin mi?`)) { Store.deleteProfile(p.id); profileGate(true); } } }, "Profili Sil") : null))));
}

function select(opts, value) {
  return h("select", opts.map(([v, l]) => h("option", { value: v, selected: v === value ? "selected" : null }, l)));
}

// ------------------------------------------------------------------ kabuk

let main, nav, modalHost, searchInput;

function enter() {
  refreshTitles();
  document.body.className = "";
  clear(app);
  nav = buildNav();
  main = h("main#main");
  modalHost = h("div#modal-host");
  app.append(nav, main, modalHost);
  window.removeEventListener("hashchange", route);
  window.addEventListener("hashchange", route);
  window.addEventListener("scroll", () => nav.classList.toggle("solid", window.scrollY > 10), { passive: true });
  route();
}

function buildNav() {
  const links = [["#/", "Ana Sayfa"], ["#/series", "Diziler"], ["#/movies", "Filmler"], ["#/tr-series", "Türk Dizileri"], ["#/tr-movies", "Türk Filmleri"], ["#/new", "Yeni ve Popüler"], ["#/list", "Listem"]];
  searchInput = h("input.search-in", { placeholder: "Yapımlar, kişiler, türler", oninput: debounce((e) => {
    const q = e.target.value.trim();
    location.hash = q ? "#/search?q=" + encodeURIComponent(q) : "#/";
  }, 250) });
  const searchBox = h("div.search", h("button.search-btn", { onclick: () => { searchBox.classList.add("open"); searchInput.focus(); } }, svg("search")), searchInput);
  searchInput.addEventListener("blur", () => { if (!searchInput.value) searchBox.classList.remove("open"); });
  const p = Store.profile;
  const menu = h("div.pmenu",
    Store.state.profiles.filter((x) => x.id !== p.id).map((x) => h("button.pmenu-item", { onclick: () => { Store.setActive(x.id); enter(); } }, avatar(x.avatar, 32), x.name)),
    h("button.pmenu-item", { onclick: () => profileGate(true) }, svg("pencil"), "Profilleri Yönet"),
    h("button.pmenu-item", { onclick: () => settingsModal() }, svg("gear"), "Ayarlar"),
    h("hr"),
    h("button.pmenu-item.center", { onclick: () => { Store.setActive(null); profileGate(); } }, "Profil Değiştir"));
  return h("nav.nav",
    h("a.logo", { href: "#/" }, "G-FLIX"),
    h("div.nav-links", links.map(([href, label]) => h("a.nav-link", { href, "data-href": href }, label))),
    h("div.nav-right", searchBox, h("div.profile", avatar(p.avatar, 32), svg("caret", "ic caret"), menu)));
}

function route() {
  closeModal(true);
  hidePreview();
  stopAllTrailers();
  const hash = location.hash || "#/";
  const [path, qs] = hash.slice(1).split("?");
  const params = new URLSearchParams(qs || "");
  nav.querySelectorAll(".nav-link").forEach((a) => a.classList.toggle("active", a.dataset.href === "#" + path || (path === "/" && a.dataset.href === "#/")));
  clear(main);
  window.scrollTo(0, 0);
  if (path === "/search") return searchPage(params.get("q") || "");
  if (searchInput && searchInput.value) { searchInput.value = ""; searchInput.parentNode.classList.remove("open"); }
  if (path === "/series") return browse("series", params.get("genre"));
  if (path === "/movies") return browse("movies", params.get("genre"));
  if (path === "/tr-series") return browse("tr-series", params.get("genre"));
  if (path === "/tr-movies") return browse("tr-movies", params.get("genre"));
  if (path === "/new") return newPage();
  if (path === "/list") return listPage();
  if (params.get("title")) { browse("all"); return openModal(params.get("title")); }
  browse("all");
}

// ------------------------------------------------------------------ sayfalar

function browse(kind, genre) {
  const filter = genre ? "genre:" + genre : kind;
  let pool = L.applyFilter(titles, kind === "all" ? "all" : kind);
  if (genre) pool = pool.filter((t) => (t.genres || []).includes(genre));
  if (!titles.length) {
    main.append(h("div.empty", h("h2", "Henüz içerik yok"), h("p", "G-Flix Stüdyo, telefon ya da bilgisayar işçisiyle film/dizi ekleyince burada görünür.")));
    return;
  }
  const hero = pickHero(pool);
  if (hero) main.append(billboard(hero, kind !== "all" ? pageHeader(kind, genre) : null));
  const rows = h("div.rows");
  const cw = L.continueWatching(pool, Store.data);
  if (cw.length) rows.append(row({ key: "cw", title: `${Store.profile.name} için İzlemeye Devam Et`, items: cw.map((c) => c.title), cw }));
  L.homeRows(pool, Store.data, filter).forEach((r) => rows.append(row(r)));
  main.append(rows);
}

function pageHeader(kind, genre) {
  const gs = L.genres(L.applyFilter(titles, kind));
  const sel = h("select.genre-sel", { onchange: (e) => { location.hash = `#/${kind}` + (e.target.value ? "?genre=" + encodeURIComponent(e.target.value) : ""); } },
    h("option", { value: "" }, "Türler"), gs.map((g) => h("option", { value: g, selected: g === genre ? "selected" : null }, g)));
  return h("div.page-head", h("h1", { series: "Diziler", movies: "Filmler", "tr-series": "Türk Dizileri", "tr-movies": "Türk Filmleri" }[kind] || "Filmler"), sel);
}

function pickHero(pool) {
  if (!pool.length) return null;
  const withArt = pool.filter((t) => t.backdrop);
  const list = withArt.length ? withArt : pool;
  const withTrailer = list.filter((t) => allTrailers(t).length);
  const fresh = (withTrailer.length ? withTrailer : list).sort((a, b) => L.lastChange(b) - L.lastChange(a)).slice(0, 5);
  return fresh[L.today() % fresh.length];
}

function newPage() {
  main.append(h("div.page-top", h("h1", "Yeni ve Popüler")));
  const rows = h("div.rows.notop");
  const byNew = [...titles].sort((a, b) => L.lastChange(b) - L.lastChange(a));
  rows.append(row({ key: "new", title: "G-Flix'te Yeni", items: byNew.slice(0, 20) }));
  const s = L.top10(titles.filter(L.isSeries));
  const m = L.top10(titles.filter((t) => !L.isSeries(t)));
  if (s.length >= 3) rows.append(row({ key: "t10s", title: "Bugün İlk 10 Dizi", items: s, style: "top10" }));
  if (m.length >= 3) rows.append(row({ key: "t10m", title: "Bugün İlk 10 Film", items: m, style: "top10" }));
  const withTrailer = byNew.filter((t) => allTrailers(t).length);
  if (withTrailer.length) rows.append(row({ key: "tr", title: "Fragmanı Olanlar", items: withTrailer }));
  main.append(rows);
}

function listPage() {
  const byId = new Map(titles.map((t) => [t.id, t]));
  const items = Store.data.myList.map((id) => byId.get(id)).filter(Boolean);
  main.append(h("div.page-top", h("h1", "Listem")));
  main.append(items.length ? grid(items) : h("div.empty.small", h("p", "Listende henüz bir şey yok. Bir yapımın üzerindeki + düğmesiyle ekle.")));
}

function searchPage(q) {
  const res = L.search(titles, q);
  main.append(h("div.page-top.search-top", res.length ? h("p.muted", `"${q}" için sonuçlar`) : null));
  main.append(res.length ? grid(res) : h("div.empty.small", h("p", `"${q}" aramanız için eşleşme bulunamadı.`), h("p.muted", "Öneriler: farklı bir kelime dene, film ya da dizi adı, oyuncu, tür…")));
}

function grid(items) {
  return h("div.grid", items.map((t) => card(t, {})));
}

// ------------------------------------------------------------------ vitrin (billboard)

function billboard(t, header) {
  const img = h("div.bb-img", { style: { backgroundImage: t.backdrop ? `url("${t.backdrop}")` : "" } });
  const videoBox = h("div.bb-video");
  const info = h("div.bb-info",
    t.logo ? h("img.bb-logo", { src: t.logo, alt: t.title }) : h("h1.bb-title", t.title),
    h("p.bb-overview", t.overview || ""),
    h("div.bb-buttons",
      h("button.btn.white.lg", { onclick: () => play(t) }, svg("play"), "Oynat"),
      h("button.btn.gray.lg", { onclick: () => openModal(t.id) }, svg("info"), "Daha Fazla Bilgi")));
  const sideBtn = h("button.round.bb-mute", { title: "Ses" });
  const side = h("div.bb-side", sideBtn, t.certification ? h("span.bb-age", t.certification) : null);
  const el = h("section.billboard", img, videoBox, h("div.bb-vignette"), h("div.bb-fade"), header, info, side);
  const setSide = (mode) => { clear(sideBtn).append(svg(mode)); sideBtn.dataset.mode = mode; };
  setSide("replay");
  sideBtn.style.visibility = "hidden";
  const tr = pickTrailer(t, preferredTrailerLang());
  if (tr && Store.profile.autoplayTrailers !== false) {
    const player = trailerPlayer(videoBox, tr, {
      onStart: () => { el.classList.add("trailer-on"); sideBtn.style.visibility = ""; setSide(trailerAudio.muted ? "mute" : "volume"); },
      onEnd: () => { el.classList.remove("trailer-on"); setSide("replay"); },
    });
    sideBtn.onclick = () => {
      if (sideBtn.dataset.mode === "replay") { player.replay(); return; }
      trailerAudio.muted = !trailerAudio.muted; player.setMuted(trailerAudio.muted); setSide(trailerAudio.muted ? "mute" : "volume");
    };
    setTimeout(() => player.start(), 2500);
    el._trailer = player;
  }
  return el;
}

function preferredTrailerLang() {
  const d = Store.data, p = Store.profile;
  return d.audioLang || (p.titleLang === "en" ? "en" : "tr");
}

/** Sessiz başlayan, bitince kapağa dönen fragman. */
const liveTrailers = new Set();
function trailerPlayer(box, stream, { onStart, onEnd } = {}) {
  const v = h("video.tr-video", { playsinline: true, preload: "none" });
  v.muted = trailerAudio.muted;
  let att = null, started = false, dead = false;
  const api = {
    async start() {
      if (dead || started || document.body.classList.contains("playing")) return;
      started = true;
      const src = await trailerSource(stream, window.innerWidth >= 1600);
      if (dead) return;
      box.append(v);
      att = attach(v, src);
      v.addEventListener("playing", () => { v.classList.add("on"); onStart && onStart(); }, { once: true });
      v.play().catch(() => {});
    },
    replay() { v.currentTime = 0; v.classList.add("on"); v.play().catch(() => {}); onStart && onStart(); },
    setMuted(m) { v.muted = m; },
    pause() { v.pause(); },
    destroy() { dead = true; if (att) att.destroy(); v.removeAttribute("src"); v.load(); v.remove(); liveTrailers.delete(api); },
    get video() { return v; },
  };
  v.addEventListener("ended", () => { v.classList.remove("on"); onEnd && onEnd(); });
  liveTrailers.add(api);
  return api;
}

function stopAllTrailers() { [...liveTrailers].forEach((t) => t.destroy()); }

// ------------------------------------------------------------------ satırlar ve kartlar

function row(r) {
  const track = h("div.row-track");
  r.items.forEach((t, i) => track.append(r.style === "top10" ? top10Card(t, i + 1) : card(t, r.cw ? { cw: r.cw[i] } : {})));
  const left = h("button.row-arrow.left", { onclick: () => slide(-1) }, svg("left"));
  const right = h("button.row-arrow.right", { onclick: () => slide(1) }, svg("right"));
  const viewport = h("div.row-viewport", track);
  const el = h("section.row" + (r.style === "top10" ? ".top10" : ""), h("h2.row-title", r.title), h("div.row-body", left, viewport, right));
  function slide(dir) {
    viewport.scrollBy({ left: dir * viewport.clientWidth * 0.9, behavior: "smooth" });
    el.classList.add("moved");
  }
  const upd = () => {
    left.classList.toggle("hide", viewport.scrollLeft < 5);
    right.classList.toggle("hide", viewport.scrollLeft + viewport.clientWidth >= viewport.scrollWidth - 5);
  };
  viewport.addEventListener("scroll", debounce(upd, 50));
  requestAnimationFrame(upd);
  return el;
}

function card(t, { cw }) {
  const img = t.backdrop || t.poster;
  const el = h("div.card", { tabindex: "0" },
    h("div.card-img", { style: { backgroundImage: img ? `url("${img}")` : "" } }, h("span.gmark", "G")),
    t.logo ? h("img.card-logo", { src: t.logo, alt: "", loading: "lazy" }) : h("div.card-name", t.title),
    cw?.progress ? h("div.card-bar", h("i", { style: { width: fraction(cw.progress) * 100 + "%" } })) : null);
  el.addEventListener("click", () => openModal(t.id));
  el.addEventListener("keydown", (e) => { if (e.key === "Enter") openModal(t.id); });
  hoverPreview(el, t, cw);
  return el;
}

function top10Card(t, n) {
  const el = h("div.card.t10", { tabindex: "0" },
    h("span.t10-num", String(n)),
    h("div.t10-poster", { style: { backgroundImage: t.poster ? `url("${t.poster}")` : "" } }, h("span.gmark", "G")));
  el.addEventListener("click", () => openModal(t.id));
  hoverPreview(el, t, null);
  return el;
}

// Kartın üstünde durunca büyüyen önizleme (Netflix "mini modal")
let preview = null, previewTimer = null;
function hoverPreview(el, t, cw) {
  el.addEventListener("mouseenter", () => {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(() => showPreview(el, t, cw), 450);
  });
  el.addEventListener("mouseleave", (e) => {
    clearTimeout(previewTimer);
    if (preview && !preview.contains(e.relatedTarget)) hidePreview();
  });
}

function showPreview(anchor, t, cw) {
  hidePreview();
  const r = anchor.getBoundingClientRect();
  const w = Math.max(r.width * 1.45, 320);
  const left = Math.min(Math.max(r.left + r.width / 2 - w / 2, 16), window.innerWidth - w - 16);
  const top = r.top + window.scrollY - (w * 0.5625 - r.height) / 2 - 10;
  const inList = Store.data.myList.includes(t.id), liked = Store.data.liked.includes(t.id);
  const media = h("div.pv-media", { style: { backgroundImage: `url("${t.backdrop || t.poster || ""}")` } },
    t.logo ? h("img.pv-logo", { src: t.logo, alt: "" }) : h("div.pv-name", t.title));
  const listBtn = h("button.round", { title: inList ? "Listemden kaldır" : "Listeme ekle", onclick: (e) => { e.stopPropagation(); Store.toggleList(t.id); clear(listBtn).append(svg(Store.data.myList.includes(t.id) ? "check" : "plus")); } }, svg(inList ? "check" : "plus"));
  const likeBtn = h("button.round", { title: "Beğen", onclick: (e) => { e.stopPropagation(); Store.toggleLike(t.id); clear(likeBtn).append(svg(Store.data.liked.includes(t.id) ? "liked" : "like")); } }, svg(liked ? "liked" : "like"));
  const sub = cw && cw.season != null ? `B${cw.episode}` : null;
  const p = h("div.preview", { style: { left: left + "px", top: top + "px", width: w + "px" } },
    media,
    h("div.pv-body",
      h("div.pv-buttons",
        h("button.round.white", { title: "Oynat", onclick: (e) => { e.stopPropagation(); play(t, cw); } }, svg("play")),
        listBtn, likeBtn, h("span.grow"),
        h("button.round", { title: "Bölümler ve bilgiler", onclick: (e) => { e.stopPropagation(); openModal(t.id); } }, svg("down"))),
      cw?.progress ? h("div.pv-progress", h("div.card-bar.inline", h("i", { style: { width: fraction(cw.progress) * 100 + "%" } })), h("span", `${sub ? sub + " · " : ""}${L.formatClock(cw.progress.positionMs)} / ${L.formatClock(cw.progress.durationMs)}`)) : null,
      metaLine(t),
      h("div.pv-genres", (t.genres || []).slice(0, 3).join("  •  "))));
  p.addEventListener("mouseleave", () => hidePreview());
  p.addEventListener("click", () => openModal(t.id));
  document.body.append(p);
  preview = p;
  requestAnimationFrame(() => p.classList.add("in"));
  const tr = pickTrailer(t, preferredTrailerLang());
  if (tr && Store.profile.autoplayTrailers !== false) {
    const box = h("div.pv-video");
    media.append(box);
    const tp = trailerPlayer(box, tr, { onStart: () => media.classList.add("trailer-on"), onEnd: () => media.classList.remove("trailer-on") });
    p._trailer = tp;
    setTimeout(() => tp.start(), 900);
  }
}

function hidePreview() {
  if (!preview) return;
  if (preview._trailer) preview._trailer.destroy();
  const p = preview;
  preview = null;
  p.classList.remove("in");
  setTimeout(() => p.remove(), 180);
}

function metaLine(t) {
  const match = L.matchPercent(t);
  return h("div.meta",
    match ? h("span.match", `%${match} Eşleşme`) : null,
    t.certification ? h("span.age", t.certification) : null,
    L.isSeries(t) ? h("span", L.seasonsText(t) || "") : t.runtime ? h("span", L.formatRuntime(t.runtime)) : null,
    t.year ? h("span", String(t.year)) : null,
    h("span.hd", "HD"));
}

// ------------------------------------------------------------------ detay penceresi

let modal = null;
function openModal(id) {
  const t = titles.find((x) => x.id === id);
  if (!t) return;
  hidePreview();
  closeModal(true);
  liveTrailers.forEach((x) => x.pause());
  const inList = () => Store.data.myList.includes(t.id);
  const listBtn = h("button.round.lg", { title: "Listem", onclick: () => { Store.toggleList(t.id); clear(listBtn).append(svg(inList() ? "check" : "plus")); } }, svg(inList() ? "check" : "plus"));
  const likeBtn = h("button.round.lg", { title: "Beğen", onclick: () => { Store.toggleLike(t.id); clear(likeBtn).append(svg(Store.data.liked.includes(t.id) ? "liked" : "like")); } }, svg(Store.data.liked.includes(t.id) ? "liked" : "like"));
  const target = L.playTarget(t, Store.data);
  const resumeLabel = target && target.positionMs > 0 ? "Devam Et" : "Oynat";
  const trailers = allTrailers(t);
  let trLang = preferredTrailerLang();
  const heroImg = h("div.md-img", { style: { backgroundImage: `url("${t.backdrop || t.poster || ""}")` } });
  const heroVideo = h("div.md-video");
  const sideBtn = h("button.round.lg.md-mute", { title: "Ses" });
  const langBtn = h("button.md-lang");
  const hero = h("div.md-hero", heroImg, heroVideo, h("div.md-fade"),
    h("button.md-close", { title: "Kapat (Esc)", onclick: () => closeModal() }, svg("close")),
    h("div.md-hero-info",
      t.logo ? h("img.md-logo", { src: t.logo, alt: t.title }) : h("h1.md-title", t.title),
      h("div.md-buttons", h("button.btn.white.lg", { onclick: () => play(t) }, svg("play"), resumeLabel), listBtn, likeBtn, h("span.grow"), langBtn, sideBtn)));
  let trailer = null;
  const setSide = (m) => { clear(sideBtn).append(svg(m)); sideBtn.dataset.mode = m; };
  function startTrailer() {
    if (trailer) trailer.destroy();
    clear(heroVideo);
    hero.classList.remove("trailer-on");
    const tr = pickTrailer(t, trLang);
    if (!tr) { sideBtn.style.display = "none"; langBtn.style.display = "none"; return; }
    langBtn.style.display = trailers.length > 1 ? "" : "none";
    clear(langBtn).append((tr.lang && tr.lang !== "und" ? tr.lang.toUpperCase() : "—"));
    langBtn.title = "Fragman dili";
    setSide("replay"); sideBtn.style.visibility = "hidden";
    trailer = trailerPlayer(heroVideo, tr, {
      onStart: () => { hero.classList.add("trailer-on"); sideBtn.style.visibility = ""; setSide(trailerAudio.muted ? "mute" : "volume"); },
      onEnd: () => { hero.classList.remove("trailer-on"); setSide("replay"); },
    });
    if (Store.profile.autoplayTrailers !== false) setTimeout(() => trailer && trailer.start(), 800);
    else { sideBtn.style.visibility = ""; }
  }
  sideBtn.onclick = () => {
    if (!trailer) return;
    if (sideBtn.dataset.mode === "replay") { if (trailer.video.src) trailer.replay(); else trailer.start(); return; }
    trailerAudio.muted = !trailerAudio.muted; trailer.setMuted(trailerAudio.muted); setSide(trailerAudio.muted ? "mute" : "volume");
  };
  langBtn.onclick = () => {
    const i = trailers.findIndex((x) => trailerKey(x) === trailerKey(pickTrailer(t, trLang)));
    trLang = trailers[(i + 1) % trailers.length].lang || "und";
    startTrailer();
    setTimeout(() => trailer && trailer.start(), 50);
  };

  const extra = [L.newEpisodesText(t), ...L.activeNotes(t).map((n) => n.text)].filter(Boolean)
    .map((x) => h("div", { style: { display: "inline-block", background: "rgba(229,9,20,.28)", borderRadius: "4px", padding: "6px 12px", margin: "8px 8px 0 0", fontWeight: "600" } }, x));
  const left = h("div.md-left", metaLine(t), ...extra, t.tagline ? h("p.md-tagline", t.tagline) : null, h("p.md-overview", t.overview || ""));
  const right = h("div.md-right",
    t.cast?.length ? h("p", h("span.muted", "Oyuncular: "), t.cast.slice(0, 4).join(", ") + (t.cast.length > 4 ? ", daha fazlası" : "")) : null,
    t.genres?.length ? h("p", h("span.muted", "Türler: "), t.genres.join(", ")) : null,
    t.creators?.length ? h("p", h("span.muted", L.isSeries(t) ? "Yaratıcılar: " : "Yönetmen: "), t.creators.join(", ")) : null);
  const body = h("div.md-body", h("div.md-cols", left, right));
  if (L.isSeries(t)) body.append(episodesSection(t, target));
  const sim = L.similar(t, titles);
  if (sim.length) body.append(h("h3.md-h", "Benzerleri"), h("div.md-sim", sim.map((s) => simCard(s))));
  body.append(h("h3.md-h", h("span", t.title), h("span.muted", " Hakkında")),
    h("div.md-about",
      t.creators?.length ? h("p", h("span.muted", L.isSeries(t) ? "Yaratıcılar: " : "Yönetmen: "), t.creators.join(", ")) : null,
      t.cast?.length ? h("p", h("span.muted", "Oyuncular: "), t.cast.join(", ")) : null,
      t.genres?.length ? h("p", h("span.muted", "Türler: "), t.genres.join(", ")) : null,
      t.originalTitle && t.originalTitle !== t.title ? h("p", h("span.muted", "Orijinal adı: "), t.originalTitle) : null,
      t.certification ? h("p", h("span.muted", "Yaş sınırı: "), h("span.age", t.certification)) : null));
  const box = h("div.modal", hero, body);
  const overlay = h("div.modal-overlay", { onclick: (e) => { if (e.target === overlay) closeModal(); } }, box);
  clear(modalHost).append(overlay);
  document.body.classList.add("modal-open");
  modal = { overlay, destroy: () => trailer && trailer.destroy(), id };
  requestAnimationFrame(() => overlay.classList.add("in"));
  startTrailer();
}

function closeModal(silent) {
  if (!modal) return;
  modal.destroy();
  const o = modal.overlay;
  modal = null;
  document.body.classList.remove("modal-open");
  if (silent) o.remove(); else { o.classList.remove("in"); setTimeout(() => o.remove(), 200); }
}

function episodesSection(t, target) {
  const eps = L.episodes(t);
  const seasons = [...new Set(eps.map((r) => r.season.number))];
  let shown = target?.season ?? seasons[0];
  const list = h("div.ep-list");
  const fill = () => {
    clear(list);
    eps.filter((r) => r.season.number === shown).forEach((r) => {
      const p = Store.data.progress[`${t.id}/${r.season.number}/${r.episode.number}`];
      const cur = target && target.season === r.season.number && target.episode === r.episode.number;
      list.append(h("div.ep" + (cur ? ".cur" : ""), { onclick: () => play(t, { season: r.season.number, episode: r.episode.number }, p && p.positionMs < p.durationMs * 0.95 ? p.positionMs : 0) },
        h("div.ep-n", String(r.episode.number)),
        h("div.ep-img", { style: { backgroundImage: `url("${r.episode.still || t.backdrop || ""}")` } }, h("div.ep-play", svg("play")),
          p ? h("div.card-bar", h("i", { style: { width: fraction(p) * 100 + "%" } })) : null),
        h("div.ep-text",
          h("div.ep-top", h("b", r.episode.title || `Bölüm ${r.episode.number}`), h("span", L.formatRuntime(r.episode.runtime) || "")),
          h("p", r.episode.overview || ""))));
    });
  };
  const sel = h("select.season-sel", { onchange: (e) => { shown = Number(e.target.value); fill(); } },
    seasons.map((s) => h("option", { value: String(s), selected: s === shown ? "selected" : null }, `${s}. Sezon`)));
  fill();
  return h("div.md-episodes", h("div.ep-head", h("h3.md-h", "Bölümler"), seasons.length > 1 ? sel : h("span.muted", `${shown}. Sezon`)), list);
}

function simCard(t) {
  const inList = () => Store.data.myList.includes(t.id);
  const btn = h("button.round", { title: "Listem", onclick: (e) => { e.stopPropagation(); Store.toggleList(t.id); clear(btn).append(svg(inList() ? "check" : "plus")); } }, svg(inList() ? "check" : "plus"));
  return h("div.sim", { onclick: () => openModal(t.id) },
    h("div.sim-img", { style: { backgroundImage: `url("${t.backdrop || t.poster || ""}")` } }, h("span.gmark", "G"),
      L.isSeries(t) ? null : t.runtime ? h("span.sim-dur", L.formatRuntime(t.runtime)) : null),
    h("div.sim-body", h("div.sim-row", h("div", metaLine(t)), btn), h("b.sim-name", t.title), h("p", (t.overview || "").slice(0, 180))));
}

// ------------------------------------------------------------------ oynatma

function play(t, cw, pos) {
  hidePreview();
  closeModal(true);
  stopAllTrailers();
  let season = cw?.season ?? null, episode = cw?.episode ?? null, positionMs = pos ?? null;
  if (positionMs == null) {
    if (cw && cw.season != null) {
      const p = Store.data.progress[`${t.id}/${cw.season}/${cw.episode}`];
      positionMs = p && p.positionMs < p.durationMs * 0.95 ? p.positionMs : 0;
    } else {
      const tg = L.playTarget(t, Store.data);
      if (!tg) return alert("Bu içeriğin videosu henüz yüklenmemiş.");
      season = tg.season; episode = tg.episode; positionMs = tg.positionMs;
    }
  }
  openPlayer(t, season, episode, positionMs, { onClose: () => { refreshTitles(); route(); } });
}

// ------------------------------------------------------------------ ayarlar

function settingsModal() {
  const p = Store.profile;
  const lang = select([["tr", "Türkçe"], ["en", "İngilizce"], ["original", "Orijinal"], ["mixed", "Karışık"]], p.titleLang);
  const art = select([["rotate", "Her gün değişsin"], ["fixed", "Sabit"]], p.artMode);
  const auto = h("input", { type: "checkbox", checked: p.autoplayTrailers !== false ? "checked" : null });
  const token = h("input", { type: "password", placeholder: config.hasToken ? "•••••• (kayıtlı — değiştirmek için yaz)" : "github_pat_…" });
  const msg = h("p.muted");
  const overlay = h("div.modal-overlay.in", { onclick: (e) => { if (e.target === overlay) overlay.remove(); } },
    h("div.settings",
      h("h2", "Ayarlar"),
      h("h4", `Profil: ${p.name}`),
      h("label", "Başlık dili", lang), h("label", "Kapaklar", art), h("label.pe-check", auto, " Fragmanlar kendiliğinden oynasın"),
      h("h4", "Bağlantı"),
      h("p.muted", `Depo: ${config.owner}/${config.repo} · Sürüm ${config.version}`),
      h("label", "Erişim anahtarı", token),
      msg,
      h("div.pe-actions",
        h("button.btn.white", { onclick: async () => {
          Store.updateProfile(p.id, { titleLang: lang.value, artMode: art.value, autoplayTrailers: auto.checked });
          if (token.value.trim()) await fetch("/api/config", { method: "POST", body: JSON.stringify({ token: token.value.trim() }) });
          const r = await fetch("/api/catalog?refresh=1");
          if (r.ok) { catalog = await r.json(); overlay.remove(); enter(); } else { msg.textContent = (await r.json()).error || "Katalog alınamadı"; }
        } }, "Kaydet"),
        h("button.btn.outline", { onclick: async () => { const r = await fetch("/api/catalog?refresh=1"); if (r.ok) { catalog = await r.json(); overlay.remove(); enter(); } } }, "Kataloğu Yenile"),
        h("button.btn.outline", { onclick: () => overlay.remove() }, "Kapat"))));
  document.body.append(overlay);
}

// Esc: pencereyi kapat
document.addEventListener("keydown", (e) => { if (e.key === "Escape" && modal && !document.body.classList.contains("playing")) closeModal(); });
// Katalogu arka planda yenile (yeni eklenenler görünsün)
setInterval(async () => {
  if (document.body.classList.contains("playing") || !Store.profile) return;
  try { const r = await fetch("/api/catalog"); if (r.ok) { const c = await r.json(); if (c.generatedAt !== catalog?.generatedAt) { catalog = c; refreshTitles(); } } } catch (_) {}
}, 5 * 60 * 1000);

boot();
