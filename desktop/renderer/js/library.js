// Katalog mantığı — Android uygulamasındaki Library/Presentation ile aynı kurallar.
import { isFinished } from "./store.js";

export const progressKey = (id, s, e) => (s == null || e == null ? id : `${id}/${s}/${e}`);
export const hasPlayable = (t) => !!t.stream || (t.seasons || []).some((s) => (s.episodes || []).some((e) => e.stream));

export function isKidsFriendly(t) {
  if (t.maturity != null) return t.maturity <= 7;
  return (t.genres || []).some((g) => /Aile|Animasyon|Çocuk/.test(g));
}

export function visibleTitles(catalog, profile) {
  const all = (catalog?.titles || []).filter((t) => hasPlayable(t) && !isHidden(t));
  return profile?.kids ? all.filter(isKidsFriendly) : all;
}

export const isSeries = (t) => t.type === "series";
export const lastChange = (t) => t.updated || t.added || 0;

export function genres(titles) {
  const c = new Map();
  titles.forEach((t) => (t.genres || []).forEach((g) => c.set(g, (c.get(g) || 0) + 1)));
  return [...c.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0], "tr")).map((x) => x[0]);
}

export function episodes(t) {
  return [...(t.seasons || [])].sort((a, b) => a.number - b.number)
    .flatMap((s) => (s.episodes || []).filter((e) => e.stream).sort((a, b) => a.number - b.number).map((e) => ({ season: s, episode: e })));
}

export const findEpisode = (t, s, e) => episodes(t).find((r) => r.season.number === s && r.episode.number === e) || null;
export function nextEpisode(t, s, e) {
  const all = episodes(t);
  const i = all.findIndex((r) => r.season.number === s && r.episode.number === e);
  return i >= 0 ? all[i + 1] || null : null;
}

export function latestProgress(id, data) {
  return Object.values(data.progress || {}).filter((p) => p.titleId === id).sort((a, b) => b.updatedAt - a.updatedAt)[0] || null;
}

/** "Oynat"a basınca nereden başlanacağı. */
export function playTarget(t, data) {
  if (!isSeries(t)) {
    if (!t.stream) return null;
    const p = data.progress[t.id];
    return { season: null, episode: null, positionMs: p && !isFinished(p) ? p.positionMs : 0 };
  }
  const eps = episodes(t);
  if (!eps.length) return null;
  const latest = latestProgress(t.id, data);
  if (latest && latest.season != null) {
    const cur = findEpisode(t, latest.season, latest.episode);
    if (cur && !isFinished(latest)) return { season: latest.season, episode: latest.episode, positionMs: latest.positionMs };
    const nx = nextEpisode(t, latest.season, latest.episode);
    if (nx) return { season: nx.season.number, episode: nx.episode.number, positionMs: 0 };
  }
  return { season: eps[0].season.number, episode: eps[0].episode.number, positionMs: 0 };
}

export function continueWatching(titles, data) {
  const byId = new Map(titles.map((t) => [t.id, t]));
  const groups = new Map();
  Object.values(data.progress || {}).forEach((p) => groups.set(p.titleId, [...(groups.get(p.titleId) || []), p]));
  const out = [];
  for (const [id, list] of groups) {
    const title = byId.get(id);
    if (!title) continue;
    const latest = list.sort((a, b) => b.updatedAt - a.updatedAt)[0];
    if (!isFinished(latest)) out.push({ title, progress: latest, season: latest.season, episode: latest.episode, at: latest.updatedAt });
    else if (isSeries(title)) {
      const nx = nextEpisode(title, latest.season, latest.episode);
      if (nx) out.push({ title, progress: null, season: nx.season.number, episode: nx.episode.number, at: latest.updatedAt });
    }
  }
  return out.sort((a, b) => b.at - a.at);
}

export function similar(t, titles, limit = 12) {
  return titles.filter((x) => x.id !== t.id)
    .map((x) => [x, (x.genres || []).filter((g) => (t.genres || []).includes(g)).length + (x.type === t.type ? 1 : 0)])
    .filter((x) => x[1] > 0)
    .sort((a, b) => b[1] - a[1] || (b[0].rating || 0) - (a[0].rating || 0))
    .slice(0, limit).map((x) => x[0]);
}

// Oturum boyunca sabit karıştırma (satırlar her yenilemede zıplamasın)
const SESSION_SEED = Math.floor(Math.random() * 1e9);
function shuffled(list, key) {
  return [...list].map((x) => [x, hash(`${SESSION_SEED}|${key}|${x.id}`)]).sort((a, b) => a[1] - b[1]).map((x) => x[0]);
}

/** Bugünün İlk 10'u: TMDB'nin günlük popülerliğine göre (gerçek hayattaki ilgi); eşitlikte/eksikte puan. */
export function top10(pool) {
  return [...pool].sort((a, b) => (b.popularity || 0) - (a.popularity || 0) || (b.rating || 0) - (a.rating || 0)).slice(0, 10);
}

export function homeRows(pool, data, filter) {
  if (!pool.length) return [];
  const rows = [];
  const byId = new Map(pool.map((t) => [t.id, t]));
  const mine = (data.myList || []).map((id) => byId.get(id)).filter(Boolean);
  if (mine.length) rows.push({ key: "mylist", title: "Listem", items: mine });
  rows.push({ key: "new", title: "Yeni Eklenenler", items: [...pool].sort((a, b) => lastChange(b) - lastChange(a)).slice(0, 20) });
  if (pool.length >= 4) {
    const label = { series: "G-Flix'te Bugünün İlk 10 Dizisi", movies: "G-Flix'te Bugünün İlk 10 Filmi", "tr-series": "İlk 10 Türk Dizisi", "tr-movies": "İlk 10 Türk Filmi" }[filter] || "G-Flix'te Bugünün İlk 10 Yapımı";
    rows.push({ key: "top10", title: label, items: top10(pool), style: "top10" });
  }
  if (filter === "all") {
    const s = pool.filter(isSeries); if (s.length) rows.push({ key: "series", title: "Diziler", items: shuffled(s, "s") });
    const m = pool.filter((t) => !isSeries(t)); if (m.length) rows.push({ key: "movies", title: "Filmler", items: shuffled(m, "m") });
    const ts = pool.filter((t) => isSeries(t) && isTurkish(t)); if (ts.length) rows.push({ key: "tr-series", title: "Türk Dizileri", items: [...ts].sort((a, b) => lastChange(b) - lastChange(a)) });
    const tm = pool.filter((t) => !isSeries(t) && isTurkish(t)); if (tm.length) rows.push({ key: "tr-movies", title: "Türk Filmleri", items: [...tm].sort((a, b) => lastChange(b) - lastChange(a)) });
  }
  const liked = (data.liked || []).map((id) => byId.get(id)).filter(Boolean);
  if (liked.length >= 2) rows.push({ key: "liked", title: "Beğendiklerin", items: liked });
  const g0 = filter.startsWith("genre:") ? filter.slice(6) : null;
  genres(pool).filter((g) => g !== g0).forEach((g) => {
    const items = pool.filter((t) => (t.genres || []).includes(g)).sort((a, b) => (b.rating || 0) - (a.rating || 0));
    if (items.length) rows.push({ key: "genre:" + g, title: g, items });
  });
  return rows;
}

/** Türk yapımı: orijinal dili Türkçe */
export const isTurkish = (t) => sameLanguage(t.originalLanguage, "tr");

export function applyFilter(titles, filter) {
  if (filter === "series") return titles.filter(isSeries);
  if (filter === "movies") return titles.filter((t) => !isSeries(t));
  if (filter === "tr-series") return titles.filter((t) => isSeries(t) && isTurkish(t));
  if (filter === "tr-movies") return titles.filter((t) => !isSeries(t) && isTurkish(t));
  if (filter.startsWith("genre:")) { const g = filter.slice(6); return titles.filter((t) => (t.genres || []).includes(g)); }
  return titles;
}

// Eski TV tarayıcıları \p{…} bilmez: düzenli ifade çalışma anında kurulur
const MARKS = (() => { try { return new RegExp("\\p{M}+", "gu"); } catch (_) { return /[\u0300-\u036f]+/g; } })();
export function normalize(s) {
  return String(s || "").toLocaleLowerCase("tr").replace(/ı/g, "i").normalize("NFD").replace(MARKS, "");
}

export function search(titles, query) {
  const q = normalize(query.trim());
  if (!q) return [];
  return titles.map((t) => {
    const n = normalize(t.title);
    const score = n.startsWith(q) ? 4 : n.includes(q) ? 3
      : normalize(t.originalTitle).includes(q) || Object.values(t.names || {}).some((x) => normalize(x).includes(q)) ? 3
      : (t.genres || []).some((g) => normalize(g).includes(q)) ? 2
      : [...(t.cast || []), ...(t.creators || [])].some((c) => normalize(c).includes(q)) ? 1 : 0;
    return [t, score];
  }).filter((x) => x[1] > 0).sort((a, b) => b[1] - a[1]).map((x) => x[0]);
}

// ---- biçimlendirme

export function formatRuntime(min) {
  if (!min || min <= 0) return null;
  const h = Math.floor(min / 60), m = min % 60;
  return h === 0 ? `${m} dk` : m === 0 ? `${h} sa` : `${h} sa ${m} dk`;
}

export function formatClock(ms) {
  const t = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}

export function seasonsText(t) {
  const n = (t.seasons || []).filter((s) => (s.episodes || []).some((e) => e.stream)).length;
  if (!isSeries(t) || !n) return null;
  return n === 1 ? `${episodes(t).length} Bölüm` : `${n} Sezon`;
}

/** Netflix'teki "%97 Eşleşme" (TMDB puanından) */
export function matchPercent(t) {
  if (!t.rating) return null;
  return Math.max(55, Math.min(99, Math.round(t.rating * 10 + 8)));
}

const LANGS = { tr: "Türkçe", tur: "Türkçe", en: "İngilizce", eng: "İngilizce", de: "Almanca", ger: "Almanca", deu: "Almanca",
  fr: "Fransızca", fre: "Fransızca", fra: "Fransızca", es: "İspanyolca", spa: "İspanyolca", it: "İtalyanca", ita: "İtalyanca",
  ja: "Japonca", jpn: "Japonca", ko: "Korece", kor: "Korece", ru: "Rusça", rus: "Rusça", ar: "Arapça", ara: "Arapça", und: "Bilinmiyor" };
export const languageName = (c) => LANGS[String(c || "").toLowerCase().split("-")[0]] || (c ? c.toUpperCase() : "Bilinmiyor");
export function sameLanguage(a, b) {
  if (!a || !b) return false;
  const n = (x) => { x = x.toLowerCase().split("-")[0]; return { tur: "tr", eng: "en", ger: "de", deu: "de", fre: "fr", fra: "fr" }[x] || x; };
  return n(a) === n(b);
}

// ---- profil diline göre ad ve görseller (Presentation)

function hash(s) {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (Math.imul(31, h) + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}
export const today = () => Math.floor(Date.now() / 86400000);

export function localize(t, profile, day = today()) {
  if (!profile) return t;
  const lang = profile.titleLang === "en" ? "en" : profile.titleLang === "original" ? "original"
    : profile.titleLang === "mixed" ? (hash(`${profile.id}|${t.id}|${day}|lang`) % 2 === 0 ? "tr" : "en") : "tr";
  const rotate = profile.artMode !== "fixed";
  const artLang = lang === "original" ? (t.originalLanguage || "en") : lang;
  const names = t.names || {};
  const name = (lang === "en" ? names.en : lang === "original" ? names.original : names.tr) || t.title;
  const s = hash(`${profile.id}|${t.id}|${rotate ? day : 0}`);
  const pick = (list, textless) => {
    if (!list || !list.length) return null;
    const pin = list.find((a) => a.pinned); if (pin) return pin.url;
    const m = list.filter((a) => a.lang === artLang), tl = textless ? list.filter((a) => !a.lang) : [];
    if (!rotate) return (m[0] || tl[0] || list.find((a) => a.lang === "en") || list[0]).url;
    const pool = [...m, ...tl].length ? [...m, ...tl] : list.filter((a) => a.lang === "en").length ? list.filter((a) => a.lang === "en") : list;
    return pool[s % pool.length].url;
  };
  // Kapaklarda yazısız (logosuz) görsel: logo ayrıca üstüne konur; yazılı arka plan hiç kullanılmaz
  const textless = (list) => {
    list = list || [];
    const pin = list.find((a) => a.pinned); if (pin) return pin.url;
    const tl = list.filter((a) => !a.lang);
    return tl.length ? (rotate ? tl[s % tl.length].url : tl[0].url) : null;
  };
  const backdrop = textless(t.backdrops) || t.backdrop;
  const cleanPoster = textless(t.posters);
  let logo = null;
  const logos = t.logos || [];
  if (logos.length) {
    // seçilen dil > İngilizce > yazısız > orijinal dil > herhangi biri (Türkçe logo yoksa İngilizcesi gösterilir)
    logo = (logos.find((a) => a.pinned) || logos.find((a) => a.lang === artLang) || logos.find((a) => a.lang === "en")
      || logos.find((a) => !a.lang) || logos.find((a) => a.lang === t.originalLanguage) || logos[0]).url;
  } else logo = t.logo || null;
  const seasons = lang === "tr" ? t.seasons : (t.seasons || []).map((se) => ({ ...se, episodes: (se.episodes || []).map((e) => ({ ...e, title: e.titleEn || e.title })) }));
  return { ...t, title: name, poster: cleanPoster || pick(t.posters, true) || t.poster, posterHasText: !cleanPoster && !!(t.posters || []).length, backdrop, logo, seasons };
}

// ---- Yakında / zamanlı yayın (sunucu saati gelmemiş videoları gizler, sıradaki yayın anını nextReleaseAt'e yazar)

export const nowSec = () => Math.floor(Date.now() / 1000);

/** "Hepsi yüklenince birlikte aç" planı ve henüz eksik bölümü var */
export const waitingPlan = (p) => !!(p && p.whenComplete && !p.complete);

/** Sıradaki yayın anı: yapımın kendisi ya da dizinin sıradaki bölümü */
export function nextRelease(t, now = nowSec()) {
  if (t.nextReleaseAt && t.nextReleaseAt > now) return t.nextReleaseAt;
  if ((t.releaseAt || 0) > now) return t.releaseAt;
  const eps = [...(t.seasons || []).flatMap((s) => s.episodes || []).map((e) => e.releaseAt || 0),
    ...(t.releasePlan || []).filter((p) => !waitingPlan(p)).map((p) => p.at || 0)].filter((x) => x > now);
  return eps.length ? Math.min(...eps) : null;
}

/** Yayın planı bilgisi: yöneticinin metni ya da plandan ("1–5. bölümler 7 Kasım Cuma 20:00 · 2. sezon … yayında") */
export function planText(t, now = nowSec()) {
  if (t.releaseNote && t.releaseNote.trim()) return t.releaseNote.trim();
  // yöneticinin planı (zamanı gelmemiş ya da "hepsi yüklenince" bekleyen) + bölüm saatlerinden çıkan, planla çakışmayan aralıklar
  const admin = (t.releasePlan || []).filter((p) => waitingPlan(p) || p.at > now);
  const covered = (e) => admin.some((a) => a.season === e.season && (a.from || 1) <= (e.to ?? e.from) && e.from <= (a.to ?? 1e9));
  const key = (p) => (waitingPlan(p) && !(p.at > now) ? 9e12 : p.at);
  const future = [...admin, ...episodePlan(t, now).filter((e) => !covered(e))].sort((a, b) => key(a) - key(b));
  if (!future.length) return null;
  const multi = new Set([...future, ...(t.releasePlan || [])].map((p) => p.season)).size > 1 || (t.seasons || []).length > 1;
  const date = (at) => { const d = new Date(at * 1000); const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
    return `${d.getDate()} ${AYLAR[d.getMonth()]} ${GUNLER[d.getDay()]}` + (hm === "00:00" ? "" : ` ${hm}`); };
  return future.map((p) => {
    const from = p.from || 1;
    if (waitingPlan(p)) {
      const eps = from === p.to ? `${from}. bölüm` : `${from}–${p.to}. bölümler`;
      return (multi ? `${p.season}. sezon ` : "") + `${eps} hepsi hazır olunca birlikte açılacak` + (p.at > now ? ` (en erken ${date(p.at)})` : "");
    }
    const eps = from <= 1 && p.to == null ? null : p.to == null ? `${from}. bölümden itibaren` : from === p.to ? `${from}. bölüm` : `${from}–${p.to}. bölümler`;
    if (!eps) return `${p.season}. sezon ${date(p.at)} yayında`;
    return (multi ? `${p.season}. sezon ` : "") + `${eps} ${date(p.at)}`;
  }).join(" · ");
}

/** Stüdyo notları: süresi dolmamış, metni olanlar */
export function activeNotes(t, now = nowSec()) {
  return (t.notes || []).filter((n) => (n.text || "").trim() && (!n.until || n.until > now));
}

/** "Bitince yapımı kaldır" notunun zamanı geldiyse yapım görünmez (dosyalar silinmez) */
export const isHidden = (t, now = nowSec()) => (t.notes || []).some((n) => n.hide && n.until && n.until <= now);

/** TMDB'de olup henüz yüklenmemiş bölüm sayısı */
export function missingEpisodes(t) {
  if (!isSeries(t) || !(t.tmdbSeasons || []).length) return 0;
  return t.tmdbSeasons.reduce((sum, ts) => {
    const s = (t.seasons || []).find((x) => x.number === ts.n);
    const have = s ? (s.episodes || []).filter((e) => e.stream && e.number >= 1 && e.number <= ts.c).length : 0;
    return sum + Math.max(0, ts.c - have);
  }, 0);
}

/** Eksik bölümü olan, yeni bölümü zamanlanmamış dizinin Yakında ibaresi (tarih yalnızca yayın planından gelir) */
export function newEpisodesText(t, now = nowSec()) {
  if (!missingEpisodes(t)) return null;
  return "🆕 Yeni bölümler yakında";
}

/** Yüklenmiş ama saati gelmemiş bölümlerden plan (sunucu gizlediyse onun yazdığı episodePlan) */
export function episodePlan(t, now = nowSec()) {
  const fut = (t.seasons || []).flatMap((s) => (s.episodes || []).filter((e) => (e.releaseAt || 0) > now).map((e) => [s.number, e.number, e.releaseAt]))
    .sort((a, b) => a[2] - b[2] || a[0] - b[0] || a[1] - b[1]);
  const out = [];
  for (const [season, n, at] of fut) {
    const last = out[out.length - 1];
    if (last && last.season === season && last.at === at && last.to === n - 1) last.to = n;
    else out.push({ season, from: n, to: n, at });
  }
  if (out.length) return out;
  return (t.episodePlan || []).filter((p) => p.at > now);
}

/** Devamı gelen dizinin ibaresi: plan varsa "🆕 Yeni bölümler: 1–15. bölümler 7 Kasım Cuma 20:00", yoksa "Yeni bölümler yakında" */
export function seriesSoonText(t, now = nowSec()) {
  const p = planText(t, now);
  if (p) return t.releaseNote && t.releaseNote.trim() ? `🆕 ${p}` : `🆕 Yeni bölümler: ${p}`;
  return newEpisodesText(t, now);
}

/** Yakında listesi: tarihi gelmemişler, yeni bölümü zamanlanmış ya da eksik bölümü olan diziler, videosu olmayıp fragmanı/duyurusu olanlar */
/** Devamı gelen dizi: şu an izlenebilir bölümü var, yeni bölüm/sezon bekleniyor ("Yakında Gelen Diziler") */
export function isContinuingSeries(t, now = nowSec()) {
  return isSeries(t) && !((t.releaseAt || 0) > now) &&
    (t.seasons || []).some((s) => (s.episodes || []).some((e) => e.stream && (e.releaseAt || 0) <= now));
}
/** "Yakında": henüz hiç izlenemeyen yapımlar (devamı gelen diziler ayrı kategoride) */
export function comingSoonTitles(catalog, profile, now = nowSec()) {
  return comingSoon(catalog, profile, now).filter((t) => !isContinuingSeries(t, now));
}
/** "Yakında Gelen Diziler": izlenebilir bölümü olan ve yeni bölümü/sezonu beklenen diziler */
export function comingSoonSeries(catalog, profile, now = nowSec()) {
  return comingSoon(catalog, profile, now).filter((t) => isContinuingSeries(t, now));
}

export function comingSoon(catalog, profile, now = nowSec()) {
  let list = (catalog?.titles || []).filter((t) => !isHidden(t, now) && (nextRelease(t, now) != null || (t.releasePlan || []).some(waitingPlan) ||
    (!hasPlayable(t) && (t.comingSoon || t.trailer || (t.trailers || []).length)) || (hasPlayable(t) && missingEpisodes(t) > 0)));
  if (profile?.kids) list = list.filter(isKidsFriendly);
  const key = (t) => nextRelease(t, now) || (t.nextEpisode?.at > now ? t.nextEpisode.at : 9e12);
  return list.sort((a, b) => key(a) - key(b) || (b.added || 0) - (a.added || 0));
}

const AYLAR = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"];
const GUNLER = ["Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi"];
export function releaseText(at, newEpisode) {
  if (!at) return "Yakında";
  const d = new Date(at * 1000);
  const day = `${d.getDate()} ${AYLAR[d.getMonth()]} ${GUNLER[d.getDay()]}`;
  const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  const when = hm === "00:00" ? day : `${day}, ${hm}`;
  return newEpisode ? `Yeni bölüm: ${when}` : `${when} tarihinde geliyor`;
}
export const shortDate = (at) => { const d = new Date(at * 1000); return `${d.getDate()} ${AYLAR[d.getMonth()].slice(0, 3)}`; };

/** "Türkçe Dublaj", "Türkçe Altyazı" (dosyalardan) */
export function languageBadges(t) {
  const streams = [t.stream, ...(t.seasons || []).flatMap((s) => (s.episodes || []).map((e) => e.stream))].filter(Boolean);
  const audio = streams.flatMap((st) => [...(st.audio || []), ...(st.versions || []).map((v) => v.lang), ...((st.direct || {}).audio || []).map((a) => a.lang), ...((st.direct || {}).videoAudio || [])]);
  const subs = streams.flatMap((st) => (st.subtitles || []).filter((x) => !x.forced).map((x) => x.lang));
  const out = [];
  if (!sameLanguage(t.originalLanguage, "tr") && audio.some((a) => sameLanguage(a, "tr"))) out.push("Türkçe Dublaj");
  if (subs.some((a) => sameLanguage(a, "tr"))) out.push("Türkçe Altyazı");
  return out;
}

/** Kalite etiketi (yöneticinin işareti; yoksa HD) ve Dolby rozetleri */
export function qualityBadges(t) {
  const b = t.badges || {};
  return [b.quality || "HD", b.dolbyVision ? "Dolby Vision" : null, b.dolbyAtmos ? "Dolby Atmos" : null].filter(Boolean);
}
