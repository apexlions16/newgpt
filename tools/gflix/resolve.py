"""
Tek linkten tam iş: "linki ver, gerisini bilgisayar yapsın".

Link iki türlü olabilir:
  • Yayın linki (1DM'de "bağlantıyı kopyala" ile alınan ana m3u8): liste okunur.
  • Sayfa linki (dizinin/filmin izleme sayfası): sayfa görünmez bir Chromium'da açılır, video başlatılır, bütün ağ
    istekleri (gömülü oynatıcılar dahil) izlenir; yayın listesi, altyazılar, sitenin istediği çerez/Referer ve sayfa
    başlığı yakalanır.

Sonra: başlıktan/adresten/altyazı adlarından yapım adı + sezon/bölüm çıkarılır, TMDB'de eşleştirilir; en iyi görüntü,
Türkçe ses ve orijinal ses (dili TMDB'deki orijinal dil), Türkçe ve İngilizce altyazı seçilir (dili adından ve metninden).
Ortaya normal bir tarif çıkar; indirme, MKV, yükleme, doğrulama, katalog ve temizlik her zamanki akışla yapılır.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote, urljoin, urlparse

import requests

from .hls import attrs, is_playlist, parse_media

CHROME_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


class _Resp:
    def __init__(self, url, status_code, headers, content):
        self.url, self.status_code, self.headers, self.content = url, status_code, headers, content
        self.ok = 200 <= status_code < 400

    def raise_for_status(self) -> None:
        if not self.ok:
            raise requests.HTTPError(f"HTTP {self.status_code}: {self.url[:120]}")

    @property
    def text(self) -> str:
        return self.content.decode("utf-8", "replace")


def http_get(url: str, headers: dict | None = None, timeout: float = 20, limit: int = 16 * 1024 * 1024) -> _Resp:
    """requests.get gibi, ama asla takılmaz: toplam süre sınırı (timeout sn) ve boyut sınırı (limit bayt; aşılırsa
    o kadarı döner). Sunucu damla damla cevap verse ya da liste yerine koca bir dosya gönderse bile bekleme biter."""
    import threading
    from .hls import PRELOADED
    if url in PRELOADED:      # telefonun yakalarken indirdiği liste: siteye tekrar sorma
        return _Resp(url, 200, {}, PRELOADED[url].encode("utf-8")[:limit])
    deadline = time.time() + timeout
    r = requests.get(url, headers=headers, timeout=(min(10, timeout), min(10, timeout)), stream=True)
    late = threading.Event()

    def cut():           # süre doldu: bağlantıyı zorla kapat (okuma nerede beklerse beklesin hemen düşer)
        late.set()
        try:
            r.raw._fp.fp.raw._sock.shutdown(2)      # noqa: SLF001
        except Exception:  # noqa: BLE001
            pass
        try:
            r.close()
        except Exception:  # noqa: BLE001
            pass
    timer = threading.Timer(max(0.1, deadline - time.time()), cut)
    timer.daemon = True
    timer.start()
    buf = bytearray()
    try:
        for chunk in r.iter_content(16 * 1024):
            buf += chunk
            if len(buf) >= limit:
                break
    except Exception:  # noqa: BLE001
        if not late.is_set():
            raise
    finally:
        timer.cancel()
        r.close()
    if late.is_set() and len(buf) < limit:
        raise requests.Timeout(f"{timeout:.0f} sn içinde gelmedi: {url[:120]}")
    return _Resp(r.url, r.status_code, r.headers, bytes(buf[:limit]))


# ------------------------------------------------------------------ dil

LANG_WORDS = {
    "tr": ("tr", "tur", "turkish", "turkce", "türkçe", "dublaj"),
    "en": ("en", "eng", "english", "ingilizce"),
}


def lang_from_name(text: str | None) -> str | None:
    """'Turkish', 'tur', '…-tr-…', 'Original Audio' → tr / en / orig / diğer kod ya da None."""
    if not text:
        return None
    t = unquote(text).lower()
    if re.search(r"orig|orijinal|original", t):
        return "orig"
    tokens = re.split(r"[^a-zçğıöşü]+", t)
    for lang, words in LANG_WORDS.items():
        if any(w in tokens for w in words):
            return lang
    for code in ("fr", "es", "pt", "de", "it", "ar", "ru", "ja", "ko", "pl", "nl", "fre", "fra", "spa", "por", "ger", "deu", "ita", "ara", "rus"):
        if code in tokens:
            return code[:2]
    return None


# Altyazı metninden dil: her dilin en sık kelimeleri (başka dillerle karışmayanlar) + o dile özgü harfler
_LANG_TEXT_WORDS = {
    "tr": {"bir", "bu", "ve", "değil", "için", "çok", "ben", "sen", "bana", "sana", "şey", "evet", "hayır", "tamam", "var", "yok",
           "gibi", "daha", "neden", "nasıl", "şimdi", "burada", "seni", "beni", "musun", "misin", "lütfen", "olarak", "ama", "kadar"},
    "en": {"the", "you", "and", "what", "is", "that", "of", "this", "we", "don't", "i'm", "your", "have", "yes", "okay", "are",
           "my", "it's", "was", "with", "just", "know", "can", "he", "she", "they", "be", "for", "not", "right"},
    "es": {"que", "qué", "el", "los", "las", "está", "por", "para", "pero", "con", "una", "eso", "esto", "yo", "tú", "sí", "aquí",
           "ahora", "bien", "muy", "cómo", "también", "puedo", "tengo", "hay", "estoy", "eres", "nada", "algo", "porque"},
    "fr": {"le", "les", "des", "est", "je", "tu", "vous", "nous", "pas", "qui", "c'est", "ce", "une", "et", "il", "elle", "oui",
           "mais", "pour", "avec", "ça", "suis", "être", "j'ai", "n'est", "moi", "toi", "bien", "dans", "sur"},
    "pt": {"não", "você", "um", "uma", "eu", "isso", "com", "os", "do", "da", "em", "mas", "sim", "aqui", "agora", "muito",
           "também", "estou", "tem", "está", "vou", "meu", "minha", "obrigado", "então", "ele", "ela", "foi", "isto", "nós"},
    "de": {"der", "die", "das", "und", "ist", "ich", "nicht", "du", "sie", "ein", "eine", "zu", "was", "wir", "ja", "mit", "den",
           "auf", "mich", "dich", "sind", "haben", "habe", "auch", "noch", "kann", "mir", "dir", "wie", "nein"},
    "it": {"che", "non", "il", "di", "un", "sono", "ho", "per", "mi", "ti", "ma", "cosa", "questo", "sei", "bene", "perché", "lo",
           "gli", "della", "del", "anche", "sì", "come", "qui", "tutto", "molto", "grazie", "fatto", "voglio", "ci"},
    "nl": {"de", "het", "een", "en", "ik", "je", "niet", "dat", "wat", "is", "zijn", "met", "voor", "maar", "hij", "zij", "wij",
           "ze", "dit", "naar", "ook", "nog", "heb", "kan", "mij", "jij", "geen", "goed", "hier", "waarom"},
    "pl": {"nie", "się", "jest", "to", "że", "jak", "co", "mnie", "tak", "ale", "już", "jestem", "tylko", "dobrze", "może", "był",
           "tu", "ja", "ty", "czy", "dla", "wiem", "teraz", "jego", "jej", "tego", "mam", "masz", "proszę", "dlaczego"},
    "ro": {"și", "nu", "este", "că", "ce", "pe", "cu", "mai", "să", "în", "am", "eu", "tu", "asta", "dar", "pentru", "un", "o",
           "ești", "sunt", "acum", "aici", "bine", "foarte", "știu", "vreau", "poate", "doar", "mulțumesc", "unde"},
}
_LANG_CHARS = {"tr": "ğış", "es": "ñ¿¡", "fr": "èêàù", "pt": "ãõ", "de": "ßä", "pl": "łąęźż", "ro": "țșăâ"}
_SCRIPT = [("ru", r"[а-яё]"), ("ar", r"[\u0600-\u06ff]"), ("el", r"[α-ω]"), ("he", r"[\u0590-\u05ff]"), ("ja", r"[\u3040-\u30ff]"),
           ("ko", r"[\uac00-\ud7af]"), ("zh", r"[\u4e00-\u9fff]"), ("fa", r"[پچژگ]"), ("hi", r"[\u0900-\u097f]"), ("th", r"[\u0e00-\u0e7f]")]


def text_lang_scores(text: str) -> dict[str, float]:
    """Altyazı metni için dil puanları (kelime başına isabet oranı)."""
    body = re.sub(r"<[^>]+>|\{[^}]*\}|\d\d:\d\d[:.,\d\s\->]+|WEBVTT.*|^\d+\s*$", " ", text[:80000], flags=re.M).lower()
    letters = re.findall(r"[^\W\d_]", body)
    if len(letters) < 200:
        return {}
    scores: dict[str, float] = {}
    for code, rx in _SCRIPT:            # Latin dışı yazılar: harflerin çoğu o yazıdaysa kesin
        n = len(re.findall(rx, body))
        if n > len(letters) * 0.3:
            scores[code] = 1.0
    if scores:
        return scores
    words = re.findall(r"[^\W\d_]+(?:'[^\W\d_]+)?", body)
    if not words:
        return {}
    for code, vocab in _LANG_TEXT_WORDS.items():
        hits = sum(w in vocab for w in words)
        chars = sum(body.count(c) for c in _LANG_CHARS.get(code, ""))
        scores[code] = (hits + 2 * chars) / len(words)
    return scores


def lang_from_text(text: str) -> str | None:
    """Altyazı metninden dil (TR, EN, ES, FR, PT, DE, IT, NL, PL, RO; Kiril/Arap/… yazılar): açık ara öndeyse."""
    scores = text_lang_scores(text)
    if not scores:
        return None
    ranked = sorted(scores.items(), key=lambda kv: -kv[1])
    top, val = ranked[0]
    second = ranked[1][1] if len(ranked) > 1 else 0.0
    if val >= 0.08 and val >= second * 1.6:
        return top
    return None


# ------------------------------------------------------------------ başlıktan yapım / bölüm

_EP_PATTERNS = [
    re.compile(r"[Ss](\d{1,2})[ ._-]*[Ee](\d{1,3})"),
    re.compile(r"(?<!\d)(\d{1,2})[xX×](\d{1,3})(?!\d)"),
    re.compile(r"(\d{1,2})\s*\.?\s*sezon[\s._-]*(\d{1,3})\s*\.?\s*b[öo]l[üu]m", re.I),
    re.compile(r"sezon[\s._-]*(\d{1,2})[\s._-]*b[öo]l[üu]m[\s._-]*(\d{1,3})", re.I),
    re.compile(r"season[\s._-]*(\d{1,2})[\s._-]*(?:episode|ep)[\s._-]*(\d{1,3})", re.I),
]
_JUNK = re.compile(
    r"\b(izle|full|hd|1080p|720p|4k|t[üu]rk[çc]e|dublaj|altyaz[ıi]l[ıi]|altyaz[ıi]|online|tek\s*par[çc]a|bedava|[üu]cretsiz|"
    r"watch|online|free|stream|dizi|film|filmi|web-?dl|webrip|bluray|x26[45]|dual|tr|en)\b", re.I)


@dataclass
class Guess:
    query: str
    season: int | None = None
    episode: int | None = None
    year: int | None = None
    abs_episode: int | None = None     # sezonsuz bölüm no ("Örnek Dizi 150. Bölüm"): TMDB sezonlarına çevrilir


_EP_ONLY = [
    re.compile(r"(?<!\d)(\d{1,4})\s*\.\s*b[öo]l[üu]m", re.I),
    re.compile(r"b[öo]l[üu]m[\s._-]*(\d{1,4})(?!\d)", re.I),
    re.compile(r"(?:episode|ep)[\s._-]*(\d{1,4})(?!\d)", re.I),
]


_GENERIC = re.compile(r"^(ana ?sayfa|home|anasayfa|giri[şs]|index|player|video|watch|embed)$", re.I)


def _one_guess(raw: str) -> Guess | None:
    t = unquote(raw)
    if t.startswith("http"):
        t = " ".join(p for p in urlparse(t).path.split("/") if p)
    for sep in (" | ", " – ", " — ", " - "):     # "Yapım … izle | SiteAdı" (tireler boşluğa çevrilmeden önce)
        if sep in t:
            parts = t.split(sep)
            # bölüm kodu taşıyan > "izle/watch" geçen (yapım adı; site adı değil) > en uzun parça
            t = max(parts, key=lambda p: (bool(any(x.search(p) for x in _EP_PATTERNS + _EP_ONLY)),
                                          bool(re.search(r"\b(izle|watch)\b", p, re.I)) and not re.fullmatch(r"\s*(hd\s*)?(film|dizi)\s+izle\s*", p, re.I),
                                          len(p)))
    t = re.sub(r"[_\-+]+", " ", t)
    t = re.sub(r"\.(html?|php|aspx?|vtt|srt|m3u8)$", "", t, flags=re.I)
    m = next((x.search(t) for x in _EP_PATTERNS if x.search(t)), None)
    season = episode = absn = None
    name = t
    if m:
        season, episode = int(m.group(1)), int(m.group(2))
        name = t[: m.start()]
    else:
        m2 = next((x.search(t) for x in _EP_ONLY if x.search(t)), None)
        if m2:
            absn = int(m2.group(1))
            name = t[: m2.start()]
    y = re.search(r"(?<!\d)(19[3-9]\d|20[0-4]\d)(?!\d)", name)
    year = int(y.group(1)) if y else None
    if y:
        name = name[: y.start()]
    name = re.sub(r"\b(?=\w*\d)(?=\w*[A-Za-z])\w{7,}\b", " ", name)   # rastgele kimlikler (rNd793OCua7)
    name = _JUNK.sub(" ", name)
    name = re.sub(r"[()\[\]{}:.,!]+", " ", name)
    name = re.sub(r"\s+", " ", name).strip()
    if len(name) < 2 or _GENERIC.match(name):
        name = ""
    return Guess(name, season, episode, year, absn) if (name or season is not None or absn) else None


def guess_title(texts: list[str]) -> Guess | None:
    """Sayfa başlığı, og:title, adres ve altyazı adlarından yapım adı + bölüm. Bölüm kodu taşıyan aday öncelikli;
    adayın adı yoksa (ör. "Ana Sayfa") ad başka adaydan, bölüm kodu herhangi birinden alınır."""
    gs = [g for g in (_one_guess(t) for t in texts if t) if g]
    # İlk metin (sayfa başlığı) bir yapımı bölüm kodu olmadan adlandırıyorsa, başka bir yapımın bölüm kodunu taşıyan
    # adayları (ör. telefonda önceki sayfanın adresi: "/dizi/ornek-dizi/sezon-1/bolum-22") yok say
    first = _one_guess(texts[0]) if texts and texts[0] else None
    if first and first.query and first.season is None and not first.abs_episode:
        ft = _norm(first.query)

        def same_title(g: Guess) -> bool:
            n = _norm(g.query)
            return not n or n in ft or ft in n
        dropped = [g for g in gs if (g.season is not None or g.abs_episode) and not same_title(g)]
        gs = [g for g in gs if g not in dropped]
    named = [g for g in gs if g.query]
    if not named:
        return None
    best = next((g for g in named if g.season is not None), named[0])
    if best.season is None:
        coded = next((g for g in gs if g.season is not None), None)
        if coded:
            best.season, best.episode = coded.season, coded.episode
        elif best.abs_episode is None:
            best.abs_episode = next((g.abs_episode for g in gs if g.abs_episode), None)
    if best.year is None:
        best.year = next((g.year for g in named if g.year), None)
    return best


def _norm(s: str) -> str:
    s = s.lower().replace("ı", "i").replace("İ", "i")
    return re.sub(r"[^a-z0-9çğöşü]+", "", s)


# Katalogda zaten olan diziler (iş başında doldurulur): [{"title", "names": [...], "tmdbId", "year"}]
KNOWN_SERIES: list[dict] = []


def _known_series(g: Guess) -> dict | None:
    """Bölüm sayfası ve katalogda adı tutan bir dizi varsa o (ör. 'Örnek' → kullanıcının hazırladığı 'Örnek Dizi 1900-1950')."""
    q = _norm(g.query)
    if len(q) < 3:
        return None
    exact, prefix = [], []
    for s in KNOWN_SERIES:
        for n in s.get("names") or []:
            nn = _norm(n)
            if not nn or not s.get("tmdbId"):
                continue
            if nn == q:
                exact.append(s)
            elif nn.startswith(q) or q.startswith(nn):
                prefix.append(s)
    pick = exact or prefix
    return pick[0] if len(pick) >= 1 and len({p["tmdbId"] for p in pick}) == 1 else None


def match_tmdb(tmdb, g: Guess, log=print) -> tuple[str, dict] | None:
    """('tv'|'movie', sonuç) — bölüm bilgisi varsa önce dizi, yoksa önce film aranır; tam ad eşleşmesi tercih edilir."""
    episodic = g.season is not None or bool(g.abs_episode)
    if episodic:
        k = _known_series(g)
        if k:
            log(f"  📚 Katalogdaki dizi seçildi: {k['title']} (TMDB #{k['tmdbId']})")
            return "tv", {"id": k["tmdbId"], "title": k["title"], "original_title": k["title"], "year": k.get("year")}
    # Bölüm sayfası (sezon/bölüm var) asla filmle eşleşmez; bölüm yoksa önce film
    kinds = ["tv"] if episodic else ["movie", "tv"]
    # Devam filmi numarası sitede adın ortasında olabilir ("Kahraman 3 Son Savaş" = "Kahraman: Son Savaş"): numarasız da dene
    queries = [g.query]
    bare = re.sub(r"\s+", " ", re.sub(r"(?<!\S)\d{1,2}(?!\S)", " ", g.query)).strip()
    if bare and bare != g.query and len(bare) >= 3:
        queries.append(bare)
    first: dict[str, tuple[str, dict]] = {}
    for q in queries:
        for kind in kinds:
            for lang in ("tr-TR", "en-US"):
                try:
                    res = tmdb.search(kind, q, g.year, language=lang)
                except Exception as e:  # noqa: BLE001
                    log(f"  TMDB araması başarısız: {e}")
                    res = []
                for r in res[:8]:
                    if _norm(q) in (_norm(r["title"]), _norm(r["original_title"])):
                        if q != g.query:
                            log(f"  🔎 '{g.query}' yerine '{q}' ile bulundu: {r['title']} ({r.get('year') or '?'})")
                        return kind, r
                if res and kind not in first:
                    first[kind] = (kind, res[0])
    # Tam eşleşme yoksa en yakın sonuç: bölüm bilgisi yoksa sadece film (yanlışlıkla bir diziye bölüm olarak eklenmesin)
    pick = first.get("tv") or first.get("movie") if episodic else first.get("movie")
    if pick:
        log(f"  ⚠ Tam ad eşleşmesi yok, en yakın sonuç seçildi: {pick[1]['title']} ({pick[1].get('year') or '?'})")
    elif first.get("tv"):
        log(f"  ⚠ '{g.query}' film olarak bulunamadı; dizi sonucu ({first['tv'][1]['title']}) bölüm bilgisi olmadığı için seçilmedi")
    return pick


# ------------------------------------------------------------------ ana liste

@dataclass
class Rendition:
    kind: str           # audio | subtitle
    url: str
    lang: str | None
    name: str
    default: bool = False


@dataclass
class Master:
    video: str | None
    height: int = 0
    audios: list[Rendition] = field(default_factory=list)
    subs: list[Rendition] = field(default_factory=list)
    muxed_audio: bool = False     # ses görüntünün içinde (ayrı ses grubu yok)


_MASTERS: dict[str, tuple[str, str]] = {}     # görüntü listesi → (ana liste linki, metni): okunan ana listeler hatırlanır


def master_heights(text: str) -> list[int]:
    """Ana listedeki görüntü çözünürlükleri (p), büyükten küçüğe."""
    hs = set()
    for line in text.splitlines():
        if line.startswith("#EXT-X-STREAM-INF:"):
            res = attrs(line).get("RESOLUTION", "0x0").split("x")
            if res[-1].isdigit() and int(res[-1]) > 0:
                hs.add(int(res[-1]))
    return sorted(hs, reverse=True)


def read_master(text: str, base: str, max_height: int = 0) -> Master:
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            nxt = next((x.strip() for x in lines[i + 1:] if x.strip() and not x.startswith("#")), None)
            if nxt:
                _MASTERS.setdefault(urljoin(base, nxt), (base, text))
    best, best_score, group, sub_group, height = None, -10**9, None, None, 0
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            a = attrs(line)
            res = a.get("RESOLUTION", "0x0").split("x")
            h = int(res[-1]) if res[-1].isdigit() else 0
            score = h * 10**10 + int(a.get("BANDWIDTH", "0") or 0)
            if max_height and h > max_height:
                score = -h                     # istenen kaliteden yüksek: ancak hiç uygun yoksa (en küçüğü)
            nxt = next((x.strip() for x in lines[i + 1:] if x.strip() and not x.startswith("#")), None)
            if nxt and score > best_score:
                best, best_score, group, sub_group, height = urljoin(base, nxt), score, a.get("AUDIO"), a.get("SUBTITLES"), h
    m = Master(best, height)
    for line in lines:
        if not line.startswith("#EXT-X-MEDIA:"):
            continue
        a = attrs(line)
        if not a.get("URI"):
            continue
        r = Rendition("audio" if a.get("TYPE") == "AUDIO" else "subtitle", urljoin(base, a["URI"]),
                      lang_from_name(a.get("LANGUAGE")) or lang_from_name(a.get("NAME")), a.get("NAME") or a.get("LANGUAGE") or "",
                      a.get("DEFAULT") == "YES")
        # "Original Audio" adı dil kodundan önce gelir (LANGUAGE="und" olabilir)
        if lang_from_name(a.get("NAME")) == "orig":
            r.lang = "orig"
        if a.get("TYPE") == "AUDIO" and (group is None or a.get("GROUP-ID") == group):
            m.audios.append(r)
        elif a.get("TYPE") == "SUBTITLES" and (sub_group is None or a.get("GROUP-ID") == sub_group):
            m.subs.append(r)
    m.muxed_audio = not m.audios
    return m


def pick_audios(audios: list[Rendition], original: str) -> list[tuple[str, Rendition]]:
    """Türkçe ses + orijinal ses (dili TMDB'den); başka diller atlanır."""
    out: list[tuple[str, Rendition]] = []
    tr = next((a for a in audios if a.lang == "tr"), None)
    if tr:
        out.append(("tr", tr))
    orig = next((a for a in audios if a.lang == "orig"), None) or next((a for a in audios if a.lang == original and a is not tr), None)
    if orig is None and original != "tr":
        orig = next((a for a in audios if a.lang in (None, "und") and a is not tr), None)
    if orig and original != "tr":
        out.append((original, orig))
    if not out and audios:
        a = next((x for x in audios if x.default), audios[0])
        out.append((a.lang if a.lang not in (None, "orig") else original, a))
    return out


# ------------------------------------------------------------------ sayfayı görünmez tarayıcıda aç

_PLAY_JS = """() => {
  for (const v of document.querySelectorAll('video')) { try { v.muted = true; v.play().catch(() => {}); } catch (e) {} }
  const sel = ['.vjs-big-play-button', '.jw-icon-display', '.jw-display-icon-container', '.plyr__control--overlaid',
    '.fp-play', '.play-button', '.playbtn', '#play', '.play', 'button[aria-label*="Play" i]', 'button[title*="Oynat" i]', '[class*="play-btn"]',
    '[class*="playbutton"]', '[class*="play_button"]', '[id*="play"]'];
  for (const s of sel) { for (const b of document.querySelectorAll(s)) { try { b.click(); } catch (e) {} } }
  return document.querySelectorAll('video').length;
}"""
# Reklam: "Reklamı geç", "Skip Ad", "Atla", "Kapat", "×" gibi görünür küçük düğmelere bas; reklam videosunu sona sar
_SKIP_JS = """() => {
  const re = /^(reklam[ıi]?\\s*(ge[çc]|atla)|reklam[ıi]?\\s*kapat|skip(\\s*ad(s)?)?|atla|ge[çc]|kapat|close|×|✕|x)$/i;
  let n = 0;
  for (const e of document.querySelectorAll('button,a,div,span')) {
    const t = (e.innerText || e.getAttribute('aria-label') || '').trim();
    if (!t || t.length > 24 || !re.test(t)) continue;
    const r = e.getBoundingClientRect();
    if (r.width < 8 || r.height < 8 || r.width > 400) continue;
    try { e.click(); n++; } catch (x) {}
  }
  for (const s of ['.ytp-ad-skip-button', '.videoAdUiSkipButton', '[class*="skip"]', '[id*="skip"]', '[class*="close-ad"]', '[class*="ad-close"]']) {
    for (const b of document.querySelectorAll(s)) { try { b.click(); n++; } catch (x) {} }
  }
  // reklam olarak işaretli kısa videoları sona sar
  for (const v of document.querySelectorAll('video')) {
    const ad = /\\bad(s|vert)?\\b|reklam|preroll|ima/i.test((v.className || '') + ' ' + (v.id || '') + ' ' + (v.parentElement ? v.parentElement.className : ''));
    if (ad && isFinite(v.duration) && v.duration < 180) { try { v.currentTime = v.duration; } catch (x) {} }
  }
  return n;
}"""
# Oynatıcı alanı (çerçeve/video ya da henüz çerçeve yüklenmemişse kapak resmi): ekranın ortasına kaydır, merkezini ver.
# Oynatıcı sayfanın altında kalınca ekran dışına dokunuluyordu (telefonda kullanıcı zaten kaydırıp dokunuyor).
_PLAYER_BOX_JS = """() => {
  let best = null, area = 0;
  const q = 'iframe,video,[class*="player" i],[id*="player" i],[class*="poster" i],[class*="video" i]';
  for (const e of document.querySelectorAll(q)) {
    const r = e.getBoundingClientRect();
    if (r.width < 200 || r.height < 120) continue;
    if (r.width * r.height > area) { area = r.width * r.height; best = e; }
  }
  if (!best) return null;
  best.scrollIntoView({block: 'center', inline: 'center'});
  const r = best.getBoundingClientRect();
  const x = Math.min(Math.max(r.x + r.width / 2, 1), innerWidth - 1), y = Math.min(Math.max(r.y + r.height / 2, 1), innerHeight - 1);
  return {x, y};
}"""
_SUB_IN_TEXT = re.compile(r"https?:(?:\\?/){2}[^\"'\s<>]+?\.(?:vtt|srt)(?:\?[^\"'\s<>]*)?", re.I)
LONG_SEC = 10 * 60      # bundan kısa yayınlar reklam/fragman sayılır (TMDB süresi biliniyorsa onun yarısı)


def ensure_playwright(log=print) -> None:
    try:
        import playwright  # noqa: F401
        return
    except ImportError:
        pass
    log("  🧩 Sayfa linkleri için görünmez tarayıcı ilk kez kuruluyor (bir kerelik, ~150 MB)…")
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "playwright"], check=True)
    subprocess.run([sys.executable, "-m", "playwright", "install", "chromium"], check=True)


@dataclass
class Capture:
    page_url: str
    title: str = ""
    og_title: str = ""
    masters: list[tuple[str, str, dict]] = field(default_factory=list)    # (url, metin, istek başlıkları)
    media: list[tuple[str, str, dict]] = field(default_factory=list)      # ana liste olmayan tekil listeler
    subs: list[str] = field(default_factory=list)
    headers: dict = field(default_factory=dict)


def playlist_duration(text: str) -> float:
    return sum(float(m) for m in re.findall(r"#EXTINF:\s*([\d.]+)", text))


def _req_headers(resp) -> dict:
    try:
        h = resp.request.all_headers()
    except Exception:  # noqa: BLE001
        h = resp.request.headers
    return {k: v for k, v in {"Referer": h.get("referer"), "Origin": h.get("origin"), "Cookie": h.get("cookie"),
                              "User-Agent": h.get("user-agent")}.items() if v}


def _master_duration(url: str, text: str, headers: dict) -> float:
    m = read_master(text, url)
    if not m.video:
        return 0.0
    try:
        r = http_get(m.video, headers={"User-Agent": CHROME_UA, **headers}, timeout=20)
        return playlist_duration(r.content.decode("utf-8", "replace")) if r.ok else 0.0
    except Exception:  # noqa: BLE001
        return 0.0


def capture_to_json(cap: Capture) -> dict:
    """Capture → telefonun capture.json biçimi (onay kuyruğuna/kaynağa aynı yoldan gider)."""
    durs = getattr(cap, "durations", {})
    return {"page_url": cap.page_url, "title": cap.title or cap.og_title,
            "streams": [{"url": u, "text": t, "headers": h, "duration": durs.get(u) or 0} for u, t, h in cap.masters + cap.media],
            "subs": [{"url": u} for u in cap.subs], "playlists": []}


_GHOST: dict = {}
_GHOST_LOCK = threading.Lock()


def ghost_display(log=print) -> str | None:
    """Ekranı olmayan sanal masaüstü (Xvfb): tarayıcı normal bir pencerede çalışır ama kullanıcının ekranında
    hiçbir şey görünmez. Bir kez açılır, sunucu kapanana kadar kalır. Xvfb kurulu değilse None."""
    with _GHOST_LOCK:
        pr = _GHOST.get("proc")
        if pr is not None and pr.poll() is None:
            return _GHOST["display"]
        xvfb = shutil.which("Xvfb")
        if not xvfb:
            return None
        for n in range(97, 120):
            if os.path.exists(f"/tmp/.X11-unix/X{n}") or os.path.exists(f"/tmp/.X{n}-lock"):
                continue
            pr = subprocess.Popen([xvfb, f":{n}", "-screen", "0", "1366x800x24", "-nolisten", "tcp"],
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for _ in range(30):
                if os.path.exists(f"/tmp/.X11-unix/X{n}") or pr.poll() is not None:
                    break
                time.sleep(0.1)
            if pr.poll() is None:
                _GHOST.update(proc=pr, display=f":{n}")
                log(f"  👻 görünmez masaüstü açıldı (:{n})")
                return f":{n}"
        return None


def real_chrome() -> str | None:
    """Bilgisayarda kurulu gerçek Google Chrome (video çözücüleri tam; Playwright'ın Chromium'unda H.264/AAC yok)."""
    for name in ("google-chrome-stable", "google-chrome", "chrome"):
        p = shutil.which(name)
        if p:
            return p
    for p in ("/opt/google/chrome/chrome", "/opt/google/chrome/google-chrome"):
        if os.path.isfile(p):
            return p
    return None


def capture_page(url: str, log=print, timeout: int = 120, visible: bool = False, gentle: bool = False,
                 ghost: bool = False, stay: int = 8) -> Capture:
    """Sayfayı tarayıcıda açar; reklamları geçer, oynatıcıyı başlatır, uzun (asıl) yayın görünene kadar
    bütün yayın listelerini, altyazıları ve sitenin istediği başlıkları toplar.
    ghost: sanal masaüstünde (Xvfb) gerçek pencere: kullanıcı hiçbir şey görmez, ses yok. stay: bulduktan sonra videoda kalma (sn)."""
    ensure_playwright(log)
    from playwright.sync_api import sync_playwright

    cap = Capture(url)
    seen: set[str] = set()
    durations: dict[str, float] = {}
    with sync_playwright() as p:
        chrome = None if os.environ.get("GFLIX_CHROMIUM") else real_chrome()
        exe = os.environ.get("GFLIX_CHROMIUM") or chrome
        headless, env = not visible, None
        if ghost:
            disp = ghost_display(log)
            if disp:
                env = {k: v for k, v in os.environ.items() if k not in ("WAYLAND_DISPLAY", "XAUTHORITY")}
                env["DISPLAY"] = disp
                headless = False
        # visible: bilgisayarın masaüstünde gerçek (görünür) tarayıcı penceresi; bazı oynatıcılar görünmez tarayıcıya video vermez
        browser = p.chromium.launch(headless=headless, executable_path=exe, env=env,
                                    args=["--autoplay-policy=no-user-gesture-required", "--mute-audio", "--disable-blink-features=AutomationControlled"])
        # gerçek Chrome kendi kimliğini kullanır; Playwright'ın Chromium'una masaüstü Chrome kimliği verilir (eskisi gibi)
        ctx = browser.new_context(viewport={"width": 1280, "height": 720}, locale="tr-TR",
                                  **({} if chrome else {"user_agent": CHROME_UA}))
        ctx.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});")

        def on_response(resp):
            u = resp.url
            if u in seen or not u.startswith("http"):
                return
            seen.add(u)
            try:
                ct = (resp.headers.get("content-type") or "").lower()
                size = int(resp.headers.get("content-length") or 0)
                path = urlparse(u).path.lower()
                if re.search(r"\.(vtt|srt)$", path) or "text/vtt" in ct:
                    cap.subs.append(u)
                    return
                if "video/mp2t" in ct or "video/mp4" in ct or size > 3_000_000:
                    return
                rtype = resp.request.resource_type
                maybe = "mpegurl" in ct or ".m3u8" in path or rtype in ("xhr", "fetch", "media", "other")
                if not maybe:
                    return
                body = resp.body()
                if is_playlist(body):
                    text = body.decode("utf-8", "replace")
                    h = _req_headers(resp)
                    if "#EXT-X-STREAM-INF" in text:
                        cap.masters.append((u, text, h))
                    elif "#EXTINF" in text:
                        cap.media.append((u, text, h))
                        durations[u] = playlist_duration(text)
                elif b"WEBVTT" in body[:20]:
                    cap.subs.append(u)
                elif rtype in ("xhr", "fetch") and len(body) < 2_000_000:
                    for s in _SUB_IN_TEXT.findall(body.decode("utf-8", "replace")):
                        cap.subs.append(s.replace("\\/", "/"))
            except Exception:  # noqa: BLE001
                pass

        cap.diag = {"responses": 0, "frame_errors": []}

        def on_any(resp):
            cap.diag["responses"] += 1
            try:
                if resp.request.resource_type == "document" and resp.status >= 400 and resp.frame != resp.frame.page.main_frame:
                    cap.diag["frame_errors"].append(f"{urlparse(resp.url).hostname} HTTP {resp.status}")
            except Exception:  # noqa: BLE001
                pass

        def on_failed(req):
            try:
                if req.resource_type == "document":
                    cap.diag["frame_errors"].append(f"{urlparse(req.url).hostname} {req.failure or 'hata'}")
            except Exception:  # noqa: BLE001
                pass
        ctx.on("response", on_any)
        ctx.on("requestfailed", on_failed)
        ctx.on("response", on_response)
        page = ctx.new_page()
        ctx.on("page", lambda pg: pg.close() if pg != page else None)    # reklam açılır pencereleri
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=45000)
        except Exception as e:  # noqa: BLE001
            log(f"  sayfa tam açılmadı ({str(e)[:80]}), yine de deneniyor")
        t0 = time.time()
        found_at = None
        checked: set[str] = set()
        skipped_ads = 0
        clicks = 0
        if gentle:
            # Telefondaki sade mod gibi: sayfaya/çerçevelere betik çalıştırılmaz (bazı oynatıcılar bozuluyor);
            # sadece kullanıcının yapacağı gibi 'Close' sekmesine ve oynatıcıya aralıklı tıklanır
            try:
                page.get_by_text("Close", exact=True).first.click(timeout=4000)
            except Exception:  # noqa: BLE001
                pass
        next_tap = 1.0
        while time.time() - t0 < timeout:
            if gentle:
                el = time.time() - t0
                # Telefondaki gibi: oynatıcı görünür görünmez dokun, 2 sn sonra bir daha (ilki çoğu zaman kaplamayı geçer),
                # sonra 10 sn'de bir
                if found_at is None and clicks < 12 and el >= next_tap:
                    try:
                        box = page.evaluate(_PLAYER_BOX_JS)
                        if box and box["x"] > 0:
                            page.mouse.click(box["x"], box["y"])
                            clicks += 1
                            next_tap = el + (2 if clicks == 1 else 10)
                        else:
                            next_tap = el + 1
                    except Exception:  # noqa: BLE001
                        next_tap = el + 1
                for mu, mt, mh in list(cap.masters):
                    if mu not in checked:
                        checked.add(mu)
                        durations[mu] = _master_duration(mu, mt, mh)
                if max(durations.values(), default=0.0) >= LONG_SEC and found_at is None:
                    found_at = time.time()
                    log(f"  ▶ uzun yayın bulundu ({round(max(durations.values()) / 60)} dk)")
                if found_at and time.time() - found_at > stay:
                    break                     # videoda biraz kalındı (hemen kaçmak tuhaf görünmesin), altyazılar da geldi
                if not cap.masters and not cap.media and cap.diag["frame_errors"] and el > 45:
                    break                     # oynatıcı çerçevesi reddedildi: boşuna bekleme
                page.wait_for_timeout(1000)
                continue
            for fr in list(page.frames):
                for js in (_SKIP_JS, _PLAY_JS):
                    try:
                        n = fr.evaluate(js)
                        if js is _SKIP_JS and n:
                            skipped_ads += n
                    except Exception:  # noqa: BLE001
                        pass
            try:   # en büyük oynatıcı alanının ortasına tıkla (bazı oynatıcılar ancak tıklanınca başlar)
                box = page.evaluate("""() => { let b=null, a=0; for (const e of document.querySelectorAll('iframe,video')) {
                    const r=e.getBoundingClientRect(); if (r.width*r.height>a) { a=r.width*r.height; b={x:r.x+r.width/2,y:r.y+r.height/2}; } } return b; }""")
                if box and found_at is None:
                    page.mouse.click(box["x"], box["y"])
            except Exception:  # noqa: BLE001
                pass
            # Ana listelerin süresine bak: uzun yayın görünene kadar devam (önce reklam oynayabilir)
            for mu, mt, mh in list(cap.masters):
                if mu not in checked:
                    checked.add(mu)
                    durations[mu] = _master_duration(mu, mt, mh)
            longest = max(durations.values(), default=0.0)
            if longest >= LONG_SEC and found_at is None:
                found_at = time.time()
                log(f"  ▶ uzun yayın bulundu ({round(longest / 60)} dk)" + (f" · {skipped_ads} reklam düğmesine basıldı" if skipped_ads else ""))
            if found_at and time.time() - found_at > 8:      # altyazı istekleri için biraz daha bekle
                break
            page.wait_for_timeout(2500)
        if found_at is None and (cap.masters or cap.media):
            log(f"  ⚠ {timeout} sn içinde {LONG_SEC // 60} dk'dan uzun yayın görülmedi; bulunanlar arasından seçilecek")
        if found_at is None:
            # Görünmez denemede ne olduğunu kullanıcı görebilsin: son ekranın resmi (sadece bu bilgisayarda kalır)
            try:
                shot = Path.home() / ".cache" / "gflix" / "son-deneme.png"
                shot.parent.mkdir(parents=True, exist_ok=True)
                page.screenshot(path=str(shot))
                log(f"  📸 son ekran: {shot} · {cap.diag['responses']} istek, {clicks} dokunuş"
                    + (f" · reddedilen çerçeve: {', '.join(cap.diag['frame_errors'][:2])}" if cap.diag["frame_errors"] else ""))
            except Exception:  # noqa: BLE001
                pass
        try:
            cap.title = page.title()
            cap.og_title = page.evaluate("() => (document.querySelector('meta[property=\"og:title\"]')||{}).content || ''")
        except Exception:  # noqa: BLE001
            pass
        browser.close()
    cap.subs = list(dict.fromkeys(cap.subs))
    cap.durations = durations
    return cap


def _probe_kind(url: str, text: str, headers: dict) -> str:
    """Tekil listenin türü: ilk parçaya bakılır → 'video' | 'audio' | '?'."""
    segs, init = parse_media(text, url)
    if not segs:
        return "?"
    h = {"User-Agent": CHROME_UA, **headers}
    try:
        data = (http_get(init, headers=h, timeout=20).content if init else b"") + http_get(segs[0].url, headers=h, timeout=30, limit=2_000_000).content
    except Exception:  # noqa: BLE001
        return "?"
    if data[:3] == b"ID3" or data[:2] in (b"\xff\xf1", b"\xff\xf9"):
        return "audio"
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", "-"],
                       input=data, capture_output=True)
    kinds = r.stdout.decode().split()
    return "video" if "video" in kinds else ("audio" if "audio" in kinds else "?")


def _hms(sec: float) -> str:
    return f"{round(sec)} sn" if sec < 120 else f"{round(sec / 60)} dk"


def choose_stream(cap: Capture, expected: float | None, log=print, max_height: int = 0) -> tuple[Master, dict, float]:
    """
    Yakalananlar arasından asıl içerik: TMDB süresine en yakın (bilinmiyorsa en uzun) yayın. Kısa yayınlar (reklam,
    fragman) atılır; görüntüyle süresi tutmayan sesler de atılır. Ana liste yoksa tekil görüntü/ses listeleri
    süreleriyle eşleştirilir. → (yayın, istek başlıkları, görüntü süresi sn)
    """
    min_len = max(5 * 60, (expected or 0) * 0.5)
    durs = getattr(cap, "durations", {})
    cands = []
    for u, t, h in cap.masters:
        d = durs.get(u) or _master_duration(u, t, h)
        if d < min_len:
            log(f"  ✂ kısa yayın atlandı ({_hms(d)} — reklam/fragman): {Path(urlparse(u).path).name[:40]}")
            continue
        cands.append((u, t, h, d))
    if cands:
        best = min(cands, key=lambda c: abs(c[3] - expected) if expected else -c[3])
        # Aynı uzunlukta birden çok kaynak (ör. 480p ve 1080p oynatıcı): Türkçe + orijinal sesi olan, sonra en yüksek çözünürlük
        same = [c for c in cands if abs(c[3] - best[3]) <= max(60.0, best[3] * 0.03)]

        def rank(c):
            mm = read_master(c[1], c[0])
            langs = {a.lang for a in mm.audios}
            bw = max((int(attrs(x).get("BANDWIDTH", "0") or 0) for x in c[1].splitlines() if x.startswith("#EXT-X-STREAM-INF")), default=0)
            return ("tr" in langs) + bool(langs - {"tr", None}), mm.height, bw
        u, t, h, d = max(same, key=rank) if len(same) > 1 else best
        if len(same) > 1:
            log(f"  🔝 {len(same)} kaynak aynı uzunlukta; en iyisi seçildi: {read_master(t, u).height or '?'}p")
        m = read_master(t, u, max_height)
        if max_height:
            log(f"  📏 istenen kalite ≤{max_height}p → {m.height or '?'}p")
    else:
        # Ana liste yok: tekil listeleri türüne ve süresine göre eşleştir
        longs = [(u, t, h, playlist_duration(t)) for u, t, h in cap.media]
        for u, t, h, d in longs:
            if d < min_len:
                log(f"  ✂ kısa liste atlandı ({_hms(d)} — reklam?): {Path(urlparse(u).path).name[:40]}")
        longs = [x for x in longs if x[3] >= min_len]
        if not longs:
            raise RuntimeError("Sayfada asıl videonun yayını bulunamadı (sadece kısa reklam/fragman yayınları yakalandı). "
                               "Video bir tıklamayla başlıyor olabilir; 1DM Experimental linklerini ver.")
        kinds = {x[0]: _probe_kind(x[0], x[1], x[2]) for x in longs}
        vids = [x for x in longs if kinds[x[0]] == "video"]
        if not vids:
            raise RuntimeError("Yakalanan uzun yayınlarda görüntü bulunamadı.")
        u, t, h, d = max(vids, key=lambda x: (x[1].count("#EXTINF") > 0, x[3]))
        if expected:
            u, t, h, d = min(vids, key=lambda x: abs(x[3] - expected))
        m = Master(u, 0)
        for au, at, ah, ad in longs:
            if kinds[au] == "audio":
                m.audios.append(Rendition("audio", au, lang_from_name(au), Path(urlparse(au).path).name))
        m.muxed_audio = not m.audios
        log(f"  ℹ️ ana liste yok: {len(vids)} görüntü, {len(m.audios)} ses listesi süresine göre eşleştirildi")
    # Görüntüyle süresi tutmayan sesler (reklam/kısa ses) atılır
    vdur = d
    keep = []
    for a in m.audios:
        try:
            ad = playlist_duration(http_get(a.url, headers={"User-Agent": CHROME_UA, **h}, timeout=20).content.decode("utf-8", "replace"))
        except Exception:  # noqa: BLE001
            ad = 0.0
        if ad and abs(ad - vdur) > max(60.0, vdur * 0.05):
            log(f"  ✂ ses atlandı: {a.name or a.lang or '?'} ({_hms(ad)}, görüntü {_hms(vdur)} — süre tutmuyor, reklam?)")
            continue
        keep.append(a)
    m.audios = keep
    return m, h, vdur


# ------------------------------------------------------------------ altyazılar

def fetch_subtitle(url: str, headers: dict) -> str:
    r = http_get(url, headers=headers, timeout=60)
    r.raise_for_status()
    text = r.content.decode("utf-8-sig", "replace")
    if is_playlist(text):   # HLS altyazı listesi: VTT parçalarını birleştir
        parts = []
        for line in text.splitlines():
            if line.strip() and not line.startswith("#"):
                seg = http_get(urljoin(url, line.strip()), headers=headers, timeout=60).content.decode("utf-8-sig", "replace")
                parts.append(seg if not parts else re.sub(r"^WEBVTT[^\n]*\n(X-TIMESTAMP[^\n]*\n)?", "", seg))
        text = "\n".join(parts)
    return text


def _last_cue(text: str) -> float:
    ts = re.findall(r"(\d+):(\d\d):(\d\d)[.,](\d{3})\s*-->", text)
    if not ts:
        return 0.0
    h, mi, se, ms = ts[-1]
    return int(h) * 3600 + int(mi) * 60 + int(se) + int(ms) / 1000


def pick_subs(cands: list[tuple[str, str]], headers: dict, workdir: Path, log=print, video_dur: float = 0.0,
              texts: dict | None = None) -> list[tuple[str, Path, bool]]:
    """[(ad/etiket, url)] → [(dil, dosya, zorunlu)]: bütün diller (TR, EN, DE, FR …); dil adından, TR/EN ayrıca metninden.
    Aynı dilde birden çok varsa en uzunu; adında "forced/zorunlu" geçen ya da çok kısa olan "zorunlu" sayılır."""
    best: dict[str, tuple[int, Path, bool]] = {}
    for i, (label, url) in enumerate(cands):
        fname = Path(urlparse(url).path).name
        named = lang_from_name(label) or lang_from_name(fname)
        if named == "orig":
            named = None
        tagged_forced = bool(re.search(r"forced|zorunlu", f"{label} {fname}", re.I))
        try:
            text = (texts or {}).get(url) or fetch_subtitle(url, headers)
        except Exception as e:  # noqa: BLE001
            log(f"  altyazı inmedi ({Path(urlparse(url).path).name[:40]}): {e}")
            continue
        # Reklam/fragman altyazısı: asıl videonun başında biter (zorunlu altyazı hariç)
        if video_dur and not tagged_forced and text.count("-->") > 30 and _last_cue(text) < video_dur * 0.25:
            log(f"  ✂ altyazı atlandı (videonun %25'inden önce bitiyor — reklam/fragman?): {fname[:40]}")
            continue
        found = lang_from_text(text)
        if named and found and named != found:
            # Ad ile metin uyuşmuyor: ancak metin açıkça başka dildeyse ve adındaki dil metinde neredeyse hiç yoksa düzelt
            sc = text_lang_scores(text)
            if sc.get(named, 0.0) < sc.get(found, 0.0) * 0.25:
                log(f"  ℹ️ '{fname[:40]}' adında {named} yazıyor ama metni açıkça {found} — {found} sayıldı")
            else:
                found = named
        lang = found or named
        if not lang:
            log(f"  altyazının dili anlaşılamadı, atlandı: {fname[:50]}")
            continue
        cues = text.count("-->")
        forced = tagged_forced   # ayrıca aşağıda: diğerlerine göre çok kısa olan da "zorunlu" sayılır
        ext = ".srt" if re.match(r"\s*\d+\s*\n\d\d:", text) else ".vtt"
        path = workdir / f"altyazi.{i}.{lang}{ext}"
        path.write_text(text, encoding="utf-8")
        key = lang + (".zorunlu" if forced else "")
        if key not in best or cues > best[key][0]:
            best[key] = (cues, path, forced)
    # Zorunlu altyazı (sadece yabancı konuşmalar): aynı yayındaki en uzun altyazının %30'undan kısa olan
    longest = max((v[0] for v in best.values()), default=0)
    out: dict[str, tuple[int, Path, bool]] = {}
    for k, (cues, path, tagged) in best.items():
        lang = k.split(".")[0]
        forced = tagged or (longest >= 100 and cues < longest * 0.3)
        key = lang + (".zorunlu" if forced else "")
        if key not in out or cues > out[key][0]:
            out[key] = (cues, path, forced)
    return [(k.split(".")[0], v[1], v[2]) for k, v in out.items()]


# ------------------------------------------------------------------ "video tr: <ana liste>" → tek parça Türkçe video

def _audio_for(audios: list[Rendition], lang: str) -> Rendition | None:
    """Satırdaki dile uyan ses: tr → Türkçe; başka dil → o dil, yoksa "Original Audio", yoksa Türkçe olmayan ilk ses."""
    if lang == "tr":
        return next((a for a in audios if a.lang == "tr"), None)
    return (next((a for a in audios if a.lang == lang), None) or next((a for a in audios if a.lang == "orig"), None)
            or next((a for a in audios if a.lang in (None, "und")), None) or next((a for a in audios if a.lang != "tr"), None))


def find_master(media_url: str, headers: dict) -> tuple[str, str] | None:
    """Sadece görüntü listesi verildiyse (1DM Experimental'ın görüntü linki) yanındaki ana listeyi bul: aynı klasörde
    master/index/playlist… dene; içinde bu görüntü listesi geçen ana liste bulunursa (url, metin)."""
    from urllib.parse import urlsplit, urlunsplit
    if media_url in _MASTERS:          # sayfadan yakalanırken zaten okundu: tekrar aramaya gerek yok
        return _MASTERS[media_url]
    parts = urlsplit(media_url)
    folder = parts.path.rsplit("/", 1)[0]
    name = parts.path.rsplit("/", 1)[-1]
    ext = "." + name.rsplit(".", 1)[-1] if "." in name else ".m3u8"
    tried = set()
    budget = time.time() + 60          # bütün deneme en fazla 1 dk
    for cand in ("master", "index", "playlist", "main", "manifest", "master_playlist"):
        if time.time() > budget:
            break
        for e in dict.fromkeys((ext, ".m3u8")):
            u = urlunsplit((parts.scheme, parts.netloc, f"{folder}/{cand}{e}", parts.query, ""))
            if u in tried or u == media_url or time.time() > budget:
                continue
            tried.add(u)
            try:
                r = http_get(u, headers={"User-Agent": CHROME_UA, **headers}, timeout=15)
            except Exception:  # noqa: BLE001
                continue
            if r.ok and is_playlist(r.content) and b"#EXT-X-STREAM-INF" in r.content and name.encode() in r.content:
                return u, r.content.decode("utf-8", "replace")
    return None


def expand_video_lines(recipe, log=print) -> None:
    """
    1DM "Experimental" gibi: "video tr: <ana m3u8>" → görüntü + Türkçe ses tek parça Türkçe video; "video en: <aynı link>" →
    görüntü + orijinal ses. "video tr: <görüntü> | ses: <ses>" de olur. Aynı bölümdeki satırlar aynı görüntüyü paylaşırsa
    görüntü bir kez iner; dil başına ayrı video olarak yüklenir (mod: ayrı video). Ana liste değilse satıra dokunulmaz.
    """
    from .recipe import FileSpec
    for it in recipe.items:
        vids = [f for f in it.files if f.role == "video"]
        if not vids:
            continue
        plan: list[tuple[object, str, FileSpec]] = []      # (eski satır, görüntü linki, ses satırı)
        for v in vids:
            if v.audio_url:
                # 1DM Experimental'ın iki linki: görüntü her zaman en iyi kalitede (ana liste bulunursa en yüksek çözünürlük)
                video_url = v.url
                if v.url not in _MASTERS:
                    log(f"  🔎 {it.label} video {v.lang}: en iyi kalite için ana liste aranıyor…")
                found = find_master(v.url, v.headers)
                if found:
                    m = read_master(found[1], found[0])
                    if m.video and m.video != v.url:
                        log(f"  🔝 {it.label} video {v.lang}: en iyi kalite seçildi ({m.height or '?'}p)")
                        video_url = m.video
                plan.append((v, video_url, FileSpec(url=v.audio_url, role="audio", lang=v.lang, headers=dict(v.headers), line=v.line, hls=True)))
                continue
            try:
                r = http_get(v.url, headers={"User-Agent": CHROME_UA, **v.headers}, timeout=30)
            except Exception:  # noqa: BLE001
                break
            if not (r.ok and is_playlist(r.content)):
                break
            if b"#EXT-X-STREAM-INF" in r.content:
                m = read_master(r.content.decode("utf-8", "replace"), v.url)
            else:
                # Sadece görüntü listesi verilmiş: ana listeyi bulup bu dilin sesini oradan al
                found = find_master(v.url, v.headers) if b"#EXTINF" in r.content else None
                if not found:
                    log(f"  ⚠ {it.label} video {v.lang}: bu link sadece bir parça listesi; içinde ses yoksa sessiz kalır. "
                        f"Ses linkini '| ses: …' ile ekle ya da ana m3u8 / sayfa linkini ver.")
                    break
                log(f"  🔎 {it.label} video {v.lang}: ana liste bulundu, ses oradan alınacak")
                m = read_master(found[1], found[0])
            if m.muxed_audio or not m.video:
                break       # ses görüntünün içinde: olduğu gibi indirilir
            want = v.lang if v.lang != "und" else "tr"
            a = _audio_for(m.audios, want)
            if a is None:
                raise RuntimeError(f"{it.label}: yayında {'Türkçe' if want == 'tr' else want} ses yok (sesler: "
                                   + ", ".join(x.name or x.lang or '?' for x in m.audios) + ")")
            plan.append((v, m.video, FileSpec(url=a.url, role="audio", lang=want, headers=dict(v.headers), line=v.line, hls=True)))
        if len(plan) != len(vids):
            continue
        # Her dil kendi görüntüsüyle ayrı iner ve sadece kendi sesiyle birleşir (1DM Experimental'ın aynısı):
        # Türkçe görüntü + Türkçe ses, İngilizce görüntü + İngilizce ses
        new: list = []
        for v, video_url, audio in plan:
            new.append(FileSpec(url=video_url, role="video", lang=audio.lang, headers=dict(v.headers), line=v.line, hls=True))
            new.append(audio)
        it.files = new + [f for f in it.files if f.role != "video"]
        if len(plan) > 1:
            recipe.separate = True
        log(f"  🎬 {it.label}: " + " + ".join(f"{a.lang.upper()} görüntü + {a.lang.upper()} ses" for _, _, a in plan) + " (her dil ayrı iner, tek parça)")


# ------------------------------------------------------------------ tarif üret

def abs_to_season(tm, tmdb_id: int, n: int) -> tuple[int, int] | None:
    """Sezonsuz bölüm no → (sezon, bölüm): TMDB'deki sezonların bölüm sayılarına göre (Özel sezon hariç)."""
    d = tm.get(f"/tv/{tmdb_id}") or {}
    left = n
    for s in sorted((x for x in d.get("seasons") or [] if (x.get("season_number") or 0) >= 1), key=lambda x: x["season_number"]):
        c = s.get("episode_count") or 0
        if left <= c:
            return s["season_number"], left
        left -= c
    return None


def expand_youtube(source: str, recipe, tm, log=print) -> str:
    """YouTube linki (çoğu Türk dizisi/filmi): başlıktan yapım + bölüm, en iyi kalite yt-dlp ile; dil Türkçe dizide tr."""
    r = subprocess.run(["yt-dlp", "-J", "--no-playlist", source], capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError("YouTube videosu okunamadı: " + (r.stderr.strip().splitlines() or ["?"])[-1][:300])
    info = json.loads(r.stdout)
    yt_title = info.get("title") or ""
    log(f"▶️ YouTube: {yt_title[:100]} · {round((info.get('duration') or 0) / 60)} dk · en iyi kalite: {info.get('height') or '?'}p")
    tmdb_id, kind = recipe.tmdb_id, recipe.kind or None
    season = episode = None
    if recipe.items and recipe.items[0].season is not None:
        season, episode = recipe.items[0].season, recipe.items[0].episode
    g = guess_title([yt_title, info.get("alt_title") or "", source])
    if tmdb_id is None:
        if not tm:
            raise RuntimeError("Yapımı bulmak için TMDB anahtarı gerekli (ya da linkle birlikte 'tmdb:' satırı ver).")
        if not g:
            raise RuntimeError(f"Video başlığından yapım adı çıkarılamadı: \"{yt_title}\". Linkten Ekle'de 🔍 ile yapımı seçip gönder.")
        found = match_tmdb(tm, g, log)
        if not found:
            raise RuntimeError(f"TMDB'de bulunamadı: \"{g.query}\". Linkten Ekle'de 🔍 ile yapımı seçip gönder.")
        k, res = found
        tmdb_id, kind = res["id"], ("series" if k == "tv" else "movie")
        log(f"🎯 {res['title']} ({res.get('year') or '?'}) — TMDB {'dizi' if kind == 'series' else 'film'} #{tmdb_id}")
    original = "tr"
    if tm:
        try:
            original = (tm.get(f"/{'tv' if kind == 'series' else 'movie'}/{tmdb_id}") or {}).get("original_language") or "tr"
        except Exception:  # noqa: BLE001
            pass
    if kind == "series" and season is None and g:
        if g.season is not None:
            season, episode = g.season, g.episode
        elif g.abs_episode and tm:
            se = abs_to_season(tm, tmdb_id, g.abs_episode)
            if se:
                season, episode = se
                log(f"  {g.abs_episode}. bölüm → {season}. sezon {episode}. bölüm (TMDB sezonlarına göre)")
    if kind == "series" and season is None:
        raise RuntimeError(f"Dizi bulundu ama bölüm anlaşılamadı (\"{yt_title}\"). Linkle birlikte 'bölüm S01E05:' satırı ver.")
    lang = "tr" if (original == "tr" or re.search(r"t[üu]rk[çc]e\s*dublaj|tr\s*dublaj", yt_title, re.I)) else original
    lines = [f"tmdb: https://www.themoviedb.org/{'tv' if kind == 'series' else 'movie'}/{tmdb_id}"]
    if recipe.release_at:
        lines.append(f"yayın: {recipe.release_at}")
    if kind == "series":
        lines.append(f"bölüm S{season:02d}E{episode:02d}:")
    lines.append(f"video {lang}: {source}")
    return "\n".join(lines) + "\n"


def capture_from_json(d: dict) -> Capture:
    """Telefondaki tarayıcının yakaladıkları (capture.json) → Capture: listeler metinleriyle, istek başlıkları, altyazı metinleri."""
    cap = Capture(d.get("page_url") or "", title=d.get("title") or "", og_title=d.get("og_title") or "")
    durs = {}
    for e in d.get("streams") or []:
        h = {k: v for k, v in (e.get("headers") or {}).items() if v}
        t = e.get("text") or ""
        if not t:
            try:
                t = http_get(e["url"], headers={"User-Agent": CHROME_UA, **h}, timeout=20).content.decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                continue
        if "#EXT-X-STREAM-INF" in t:
            cap.masters.append((e["url"], t, h))
        elif "#EXTINF" in t:
            cap.media.append((e["url"], t, h))
        if e.get("duration"):
            durs[e["url"]] = float(e["duration"])
    # Telefonun indirdiği alt listeler (en iyi görüntü, sesler): bilgisayar bunları siteden yeniden istemez
    from .hls import PRELOADED
    for p in d.get("playlists") or []:
        if p.get("url") and "#EXTINF" in (p.get("text") or ""):
            PRELOADED[p["url"]] = p["text"]
    for u, t, _h in cap.masters + cap.media:
        PRELOADED.setdefault(u, t)
    cap.subs = [s["url"] for s in d.get("subs") or []]
    cap.sub_texts = {s["url"]: s.get("text") or "" for s in d.get("subs") or [] if s.get("text")}
    cap.headers = cap.masters[0][2] if cap.masters else (cap.media[0][2] if cap.media else {})
    cap.durations = durs
    return cap


def expand(source: str, recipe, workdir: Path, tmdb_key: str, log=print, cap: "Capture | None" = None) -> str:
    """Kaynak linki tam tarif metnine çevirir. recipe: kullanıcının verdiği (tmdb/bölüm satırları varsa onlar geçerli).
    cap verilirse (telefonda yakalandı) sayfa açılmaz, yakalananlar kullanılır."""
    from .tmdb import TMDB
    from .estimate import is_youtube
    workdir.mkdir(parents=True, exist_ok=True)
    given = cap
    if given is None and is_youtube(source):
        return expand_youtube(source, recipe, TMDB(tmdb_key) if tmdb_key else None, log)
    headers = {"User-Agent": CHROME_UA}
    texts: list[str] = []
    sub_cands: list[tuple[str, str]] = []
    if given is not None:
        cap = given
        log(f"📱 Telefonda yakalandı: {cap.title[:90] or '(başlıksız)'} · {len(cap.masters)} ana liste, {len(cap.media)} tekil liste, {len(cap.subs)} altyazı")
        if not cap.masters and not cap.media:
            raise RuntimeError("Telefondaki sayfada video yayını yakalanmadı.")
        texts += [cap.title, cap.og_title, cap.page_url]
        sub_cands += [(Path(urlparse(u).path).name, u) for u in cap.subs]
        texts += [Path(urlparse(u).path).name for u in cap.subs]
    else:
        try:
            first = http_get(source, headers=headers, timeout=30)
        except Exception:  # noqa: BLE001   sayfa doğrudan açılmadı (bot koruması vb.): tarayıcıyla denenecek
            first = requests.Response()
            first.status_code = 0
            first._content = b""
        if not first.ok and ".m3u8" in urlparse(source).path.lower():
            raise RuntimeError(f"Yayın linki açılmadı (HTTP {first.status_code}): site Referer/çerez istiyor ya da linkin süresi doldu. "
                               "Bunun yerine izleme sayfasının linkini ver; bilgisayar sayfayı açıp gerekenleri kendisi alır.")
        cap = None
        if first.ok and is_playlist(first.content or b""):
            log("🔗 Yayın linki (m3u8) verildi")
            cap = Capture(source, masters=[(source, first.content.decode("utf-8", "replace"), {})]) \
                if b"#EXT-X-STREAM-INF" in first.content else Capture(source, media=[(source, first.content.decode("utf-8", "replace"), {})])
        else:
            log("🌐 Sayfa görünmez tarayıcıda açılıyor; reklamlar geçiliyor, video başlatılıyor…")
            cap = capture_page(source, log)
            cap.from_browser = True
            log(f"  Sayfa: {cap.title[:90] or '(başlıksız)'} · {len(cap.masters)} ana liste, {len(cap.media)} tekil liste, {len(cap.subs)} altyazı yakalandı")
            if not cap.masters and not cap.media:
                raise RuntimeError("Sayfada video yayını yakalanamadı. Site görünmez tarayıcıyı engelliyor olabilir ya da video "
                                   "bir tıklamayla başlıyordur. Stüdyo → 🌐 Sayfadan Yakala ile sayfayı telefonda aç ya da 1DM Experimental linklerini ver.")
            texts += [cap.title, cap.og_title, source]
            sub_cands += [(Path(urlparse(u).path).name, u) for u in cap.subs]
            texts += [Path(urlparse(u).path).name for u in cap.subs]

    # ---- yapım
    tmdb_id, kind = recipe.tmdb_id, recipe.kind or None
    season = episode = None
    if recipe.items and recipe.items[0].season is not None:
        season, episode = recipe.items[0].season, recipe.items[0].episode
    original = "en"
    tm = TMDB(tmdb_key) if tmdb_key else None
    g = guess_title(texts) if texts else None
    if g and season is None and g.season is not None:
        season, episode = g.season, g.episode
    if tmdb_id is None:
        if not tm:
            raise RuntimeError("Yapımı bulmak için TMDB anahtarı gerekli (ya da linkle birlikte 'tmdb:' satırı ver).")
        if not g:
            raise RuntimeError("Sayfa başlığından yapım adı çıkarılamadı. Linkten Ekle'de 🔍 ile yapımı seçip linki öyle gönder.")
        found = match_tmdb(tm, g, log)
        if not found:
            raise RuntimeError(f"TMDB'de bulunamadı: \"{g.query}\". Linkten Ekle'de 🔍 ile yapımı seçip linki öyle gönder.")
        kind, r = found
        tmdb_id = r["id"]
        log(f"🎯 {r['title']} ({r.get('year') or '?'}) — TMDB {'dizi' if kind == 'tv' else 'film'} #{tmdb_id}")
        kind = "series" if kind == "tv" else "movie"
    if tm:
        try:
            d = tm.get(f"/{'tv' if kind == 'series' else 'movie'}/{tmdb_id}") or {}
            original = d.get("original_language") or "en"
        except Exception:  # noqa: BLE001
            pass
    if kind == "series" and season is None and g and g.abs_episode and tm:
        se = abs_to_season(tm, tmdb_id, g.abs_episode)
        if se:
            season, episode = se
            log(f"  {g.abs_episode}. bölüm → {season}. sezon {episode}. bölüm (TMDB sezonlarına göre)")
    if kind == "series" and season is None:
        raise RuntimeError("Dizi bulundu ama sezon/bölüm anlaşılamadı. Linkle birlikte 'bölüm S01E06:' satırı ver.")

    # ---- asıl yayın: TMDB süresine en yakın uzun yayın (reklam/fragman/kısa sesler atılır)
    expected = None
    if tm:
        try:
            if kind == "series":
                ep = tm.get(f"/tv/{tmdb_id}/season/{season}/episode/{episode}") or {}
                rt = ep.get("runtime") or ((tm.get(f"/tv/{tmdb_id}") or {}).get("episode_run_time") or [None])[0]
            else:
                rt = (tm.get(f"/movie/{tmdb_id}") or {}).get("runtime")
            expected = float(rt) * 60 if rt else None
        except Exception:  # noqa: BLE001
            expected = None
    m, sh, vdur = choose_stream(cap, expected, log)
    headers.update(sh or cap.headers)
    if not m.video:
        raise RuntimeError("Yayın listesinde görüntü yok")
    log(f"  🎯 asıl yayın: {round(vdur / 60)} dk" + (f" (TMDB: {round(expected / 60)} dk)" if expected else ""))
    sub_cands = [(r.name + " " + (r.lang or ""), r.url) for r in m.subs] + sub_cands

    # ---- sesler, altyazılar
    audios = pick_audios(m.audios, original)
    subs = pick_subs(sub_cands, headers, workdir, log, video_dur=vdur, texts=getattr(cap, "sub_texts", None))
    log(f"🎬 Görüntü {m.height or '?'}p · ses: " + (", ".join(f"{l} ({r.name or r.lang})" for l, r in audios) or "görüntünün içinde")
        + " · altyazı: " + (", ".join(l + (" zorunlu" if f else "") for l, _, f in subs) or "yok"))
    if audios and not any(l == "tr" for l, _ in audios):
        log("  ⚠ Türkçe ses bulunamadı, sadece orijinal ses eklenecek")

    def opts() -> str:
        o = " | hls"
        for k, key in (("Referer", "referer"), ("Cookie", "cookie"), ("User-Agent", "ua")):
            if headers.get(k):
                o += f" | {key}: {headers[k]}"
        if headers.get("Origin"):
            o += f" | header: Origin: {headers['Origin']}"
        return o

    lines = [f"tmdb: https://www.themoviedb.org/{'tv' if kind == 'series' else 'movie'}/{tmdb_id}"]
    if recipe.title:
        lines.append(f"ad: {recipe.title}")
    for lang, url in recipe.trailers:
        lines.append(f"fragman {lang}: {url}" if lang != "und" else f"fragman: {url}")
    if recipe.release_at:
        lines.append(f"yayın: {recipe.release_at}")
    if kind == "series":
        lines.append(f"bölüm S{season:02d}E{episode:02d}:")
    if audios:
        for lang, r in audios:     # her dil: kendi görüntüsü + kendi sesi (tek parça video)
            lines.append(f"video {lang}: {m.video} | ses: {r.url}{opts()}")
    else:
        lines.append(f"video: {m.video}{opts()}")
    for lang, path, forced in subs:
        lines.append(f"altyazı {lang}{' zorunlu' if forced else ''}: file://{path} | sil")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":   # elle deneme: python -m gflix.resolve <link>
    print(json.dumps(guess_title(sys.argv[1:]).__dict__ if guess_title(sys.argv[1:]) else None, ensure_ascii=False))
