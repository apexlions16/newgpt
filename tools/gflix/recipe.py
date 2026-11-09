"""Linkten toplu ekleme tarifi.

Örnek (film):
    tmdb: https://www.themoviedb.org/movie/3003-ornek-film
    fragman: https://www.youtube.com/watch?v=YoHD9XEInc0      (ya da dillere göre: fragman tr: … / fragman en: …)
    video: https://example.com/inception.txt
    ses tr varsayılan: https://example.com/dublaj.txt | ad: Türkçe Dublaj
    altyazı tr: https://example.com/tr.txt
    altyazı tr zorunlu: https://example.com/tr_forced.txt
    altyazı en: https://example.com/en.txt

Dizi:
    tmdb: https://www.themoviedb.org/tv/1001-ornek-dizi
    bölüm S01E01:
    video: https://...
    altyazı tr: https://...
    bölüm S01E02:
    video: https://...

Dublajlı sürümler (birleştirme yok, dil seçince oynatıcı videoyu değiştirir):
    video tr: https://…/film_turkce_dublaj.mkv
    video en: https://…/film_ingilizce.mkv
    altyazı tr: https://…

Diğer satırlar:
    mod: hızlı                  -> birleştirmeden: MP4 video + AAC sesler + VTT altyazılar olduğu gibi oynatılır
    ad: Başlangıç               -> TMDB'deki adın yerine
    video sesleri: tr en        -> videonun içindeki seslerin dilleri (sırayla), etiketsizse
    video altyazıları: tr en    -> videonun içindeki altyazıların dilleri
    # ile başlayan satırlar yok sayılır
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .util import LANG_LABEL, norm_lang

ROLE_KEYS = {
    "video": "video", "film": "video",
    "ses": "audio", "audio": "audio", "dublaj": "audio",
    "altyazı": "subtitle", "altyazi": "subtitle", "sub": "subtitle", "subtitle": "subtitle", "altyazılar": "subtitle",
}
META_KEYS = {
    "tmdb": "tmdb", "fragman": "trailer", "trailer": "trailer",
    "ad": "title", "isim": "title", "title": "title", "yıl": "year", "yil": "year",
    "mod": "mode", "yöntem": "mode", "yontem": "mode",
    "yayın": "release", "yayin": "release", "yayın zamanı": "release",
    "kaynak": "source", "link": "source", "sayfa": "source",
    "yakalama": "capture", "onay": "approve",
    "video sesleri": "video_audio", "video sesi": "video_audio", "video altyazıları": "video_subs", "video altyazilari": "video_subs",
}
FORCED = {"zorunlu", "forced"}
SEPARATE_WORDS = {"ayrı", "ayri", "ayrı video", "ayri video", "ayrı videolar", "experimental", "deneysel"}
FAST_WORDS = {"hızlı", "hizli", "fast", "direkt", "birleştirmeden", "birlestirmeden"}
MERGE_WORDS = {"birleştir", "birlestir", "merge", "mkv", "hls"}
DEFAULT = {"varsayılan", "varsayilan", "default"}
EP_HEADER = re.compile(r"^(?:b[öo]l[üu]m|episode)?\s*[:\-]?\s*(?:s(\d{1,2})\s*e(\d{1,3})|(\d{1,2})x(\d{1,3}))\s*:?\s*$", re.I)
TMDB_URL = re.compile(r"(movie|tv)/(\d+)", re.I)


@dataclass
class FileSpec:
    url: str
    role: str                 # video | audio | subtitle
    lang: str = "und"
    default: bool = False
    forced: bool = False
    name: str = ""
    line: int = 0
    headers: dict = field(default_factory=dict)   # | referer: … | cookie: … | ua: … | header: Ad: değer
    delete: bool = False      # "| sil": bilgisayardaki dosya (file://) yükleme doğrulanınca silinsin
    delay_ms: int = 0         # "| gecikme: -300": sesi elle kaydır (ms; + = daha geç)
    audio_url: str = ""       # "video tr: <görüntü> | ses: <ses linki>": 1DM Experimental'ın iki linki tek satırda
    hls: bool = False         # "| hls": link bir HLS oynatma listesi (m3u8; .png/.mp4 kılığında da olabilir)

    @property
    def is_hls(self) -> bool:
        return self.hls or ".m3u8" in self.url.lower().split("?")[0]


@dataclass
class Item:
    season: int | None = None
    episode: int | None = None
    files: list[FileSpec] = field(default_factory=list)
    video_audio: list[str] = field(default_factory=list)
    video_subs: list[str] = field(default_factory=list)

    @property
    def video(self) -> FileSpec | None:
        return next((f for f in self.files if f.role == "video"), None)

    @property
    def videos(self) -> list[FileSpec]:
        return [f for f in self.files if f.role == "video"]

    @property
    def is_versions(self) -> bool:
        """Dil başına ayrı video ("video tr:" + "video en:"): birleştirilmez, oynatıcı dil değişince videoyu değiştirir."""
        return len(self.videos) > 1

    @property
    def label(self) -> str:
        return f"S{self.season:02d}E{self.episode:02d}" if self.season is not None else "Film"


@dataclass
class Recipe:
    kind: str = ""            # movie | series
    tmdb_id: int | None = None
    title: str = ""
    year: int | None = None
    trailer: str = ""         # ilk fragman (eski uyumluluk)
    trailers: list[tuple[str, str]] = field(default_factory=list)   # (dil, link): "fragman tr: …", "fragman en: …"
    fast: bool = False        # "mod: hızlı" — birleştirmeden, dosyalar olduğu gibi oynatılır
    release_at: int | None = None  # "yayın: <unix saniye>" — film o zamana kadar gizli kalır
    separate: bool = False    # "mod: ayrı video": aynı HLS yayınından her dil sesi kendi videosuyla ayrı (1DM "Experimental")
    capture: str = ""         # "yakalama: capture.json": telefondaki tarayıcının yakaladıkları (Stüdyo → Sayfadan Yakala)
    auto_approve: bool = False  # "onay: otomatik": boş alan yetiyorsa indirmeden önce sorma
    source: str = ""          # tek başına link (sayfa ya da ana m3u8): bilgisayar kendisi çözer (gflix/resolve.py)
    items: list[Item] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors


def _split_key(raw: str) -> tuple[str, list[str]]:
    """'altyazı tr zorunlu' -> ('altyazı', ['tr', 'zorunlu']); 'video sesleri' tek anahtar."""
    low = re.sub(r"\s+", " ", raw.strip().lower())
    for k in sorted(META_KEYS, key=len, reverse=True):
        if low == k:
            return k, []
    parts = low.split(" ")
    return parts[0], parts[1:]


def _langs(value: str) -> list[str]:
    return [norm_lang(x) for x in re.split(r"[\s,;/]+", value.strip()) if x]


def parse(text: str) -> Recipe:
    r = Recipe()
    current: Item | None = None
    top = Item()
    for no, raw_line in enumerate(text.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        m = EP_HEADER.match(line)
        if m:
            season = int(m.group(1) or m.group(3))
            episode = int(m.group(2) or m.group(4))
            current = Item(season, episode, video_audio=list(top.video_audio), video_subs=list(top.video_subs))
            r.items.append(current)
            continue
        if line.lower().startswith(("http://", "https://")) and " " not in line and not r.source:
            r.source = line     # sadece link verilmiş: bilgisayar sayfayı/yayını kendisi çözer
            continue
        if ":" not in line or line.lower().startswith(("http://", "https://")):
            r.errors.append(f"{no}. satır anlaşılamadı: {line[:80]}")
            continue
        key_raw, rest = line.split(":", 1)
        if key_raw.lower() in ("http", "https"):
            r.errors.append(f"{no}. satır anlaşılamadı: {line[:80]}")
            continue
        value, *opts = [x.strip() for x in rest.split("|")]
        key, mods = _split_key(key_raw)
        if not value:  # şablonda doldurulmamış satır: yok say
            continue
        target = current or top

        if key in META_KEYS:
            what = META_KEYS[key]
            if what == "tmdb":
                tm = TMDB_URL.search(value)
                bare = re.fullmatch(r"(film|movie|dizi|tv)?\s*/?\s*(\d+)", value.strip(), re.I)
                if tm:
                    r.kind = "series" if tm.group(1).lower() == "tv" else "movie"
                    r.tmdb_id = int(tm.group(2))
                elif bare and bare.group(1):
                    r.kind = "series" if bare.group(1).lower() in ("dizi", "tv") else "movie"
                    r.tmdb_id = int(bare.group(2))
                else:
                    r.errors.append(f"{no}. satır: TMDB adresi anlaşılamadı (ör. https://www.themoviedb.org/movie/3003)")
            elif what == "trailer":
                lang = norm_lang(mods[0]) if mods else "und"
                if any(t[0] == lang for t in r.trailers):
                    r.errors.append(f"{no}. satır: '{lang}' dilinde iki fragman var")
                r.trailers.append((lang, value))
                r.trailer = r.trailer or value
            elif what == "title":
                r.title = value
            elif what == "source":
                r.source = value
            elif what == "capture":
                r.capture = value
            elif what == "approve":
                r.auto_approve = value.lower().startswith(("otomatik", "auto", "hayır", "hayir", "yok"))
            elif what == "release":
                if value.isdigit():
                    r.release_at = int(value)
                else:
                    r.errors.append(f"{no}. satır: yayın zamanı anlaşılamadı")
            elif what == "year":
                r.year = int(value) if value.isdigit() else None
            elif what == "mode":
                low = value.lower()
                if low in SEPARATE_WORDS:
                    r.separate = True
                elif low in FAST_WORDS:
                    r.fast = True
                elif low in MERGE_WORDS:
                    r.fast = False
                else:
                    r.warnings.append(f"{no}. satır: mod '{value}' anlaşılamadı (hızlı / birleştir)")
            elif what == "video_audio":
                target.video_audio = _langs(value)
            elif what == "video_subs":
                target.video_subs = _langs(value)
            continue

        if key not in ROLE_KEYS:
            r.errors.append(f"{no}. satır: '{key_raw.strip()}' bilinmiyor (video / ses / altyazı / tmdb / fragman / ad)")
            continue
        if not re.match(r"^(https?://|asset:|file://)", value, re.I):
            r.errors.append(f"{no}. satır: link http:// ya da https:// ile başlamalı")
            continue
        spec = FileSpec(url=value, role=ROLE_KEYS[key], line=no)
        for mtok in mods:
            if mtok in FORCED:
                spec.forced = True
            elif mtok in DEFAULT:
                spec.default = True
            else:
                lang = norm_lang(mtok)
                if lang in LANG_LABEL or len(mtok) in (2, 3):
                    spec.lang = lang
                else:
                    r.warnings.append(f"{no}. satır: '{mtok}' anlaşılamadı, yok sayıldı")
        for o in opts:
            if o.strip().lower() in ("sil", "delete"):
                spec.delete = True
            elif o.strip().lower() in ("hls", "m3u8"):
                spec.hls = True
            elif ":" in o:
                ok, ov = [x.strip() for x in o.split(":", 1)]
                kl = ok.lower()
                if kl in ("ad", "isim", "name"):
                    spec.name = ov
                elif kl in ("ses", "audio") and ov.lower().startswith(("http://", "https://")):
                    spec.audio_url = ov
                elif kl in ("gecikme", "delay", "kaydir", "kaydır"):
                    try:
                        spec.delay_ms = int(float(ov.lower().replace("ms", "").strip()))
                    except ValueError:
                        r.warnings.append(f"{no}. satır: gecikme '{ov}' anlaşılamadı (ör. gecikme: -300)")
                elif kl == "referer":
                    spec.headers["Referer"] = ov
                elif kl in ("cookie", "çerez", "cerez"):
                    spec.headers["Cookie"] = ov
                elif kl in ("ua", "user-agent"):
                    spec.headers["User-Agent"] = ov
                elif kl in ("header", "başlık", "baslik") and ":" in ov:
                    hk, hv = ov.split(":", 1)
                    spec.headers[hk.strip()] = hv.strip()
        if spec.role in ("audio", "subtitle") and spec.lang == "und":
            r.warnings.append(f"{no}. satır: dil belirtilmedi (ör. 'ses tr:'), 'Belirsiz' olarak eklenecek")
        target.files.append(spec)

    if top.files:
        if r.items:
            r.errors.append("Bölüm satırlarından önce dosya linki var. Dizide her dosya bir 'bölüm S01E01:' satırının altında olmalı.")
        else:
            r.items.append(top)
    if (r.source or r.capture) and not any(f for it in r.items for f in it.files) and not top.files:
        return r      # kaynak linki: tarif işçide tamamlanır
    if r.tmdb_id is None and not r.title:
        r.errors.append("'tmdb:' satırı gerekli (ya da en azından 'ad:').")
    if not r.kind:
        r.kind = "series" if any(i.season is not None for i in r.items) else "movie"
    if r.kind == "series" and any(i.season is None for i in r.items):
        r.errors.append("Dizi için dosyalar 'bölüm S01E01:' satırlarının altında olmalı.")
    if r.kind == "movie" and len(r.items) > 1:
        r.errors.append("Filmde bölüm satırı olmamalı (TMDB adresi /tv/ ise dizi sayılır).")
    if not r.items:
        r.errors.append("Hiç dosya linki yok ('video: https://…').")
    for it in r.items:
        vids = [f for f in it.files if f.role == "video"]
        if not vids:
            r.errors.append(f"{it.label}: 'video:' satırı yok.")
        elif len(vids) > 1:
            # Dublajlı sürümler: her videonun dili belli ve farklı olmalı ("video tr:", "video en:")
            langs = [f.lang for f in vids]
            if "und" in langs:
                r.errors.append(f"{it.label}: birden fazla video varsa her birinin dili yazılmalı (ör. 'video tr:' ve 'video en:').")
            elif len(set(langs)) != len(langs):
                r.errors.append(f"{it.label}: aynı dilde iki video var ({', '.join(langs)}).")
            if any(f.role == "audio" for f in it.files):
                r.warnings.append(f"{it.label}: dil başına ayrı video verildiği için ayrı ses satırları yok sayılır.")
    seen = set()
    for it in r.items:
        if it.label in seen and it.season is not None:
            r.errors.append(f"{it.label} iki kez yazılmış.")
        seen.add(it.label)
    return r


TEMPLATE_MOVIE = """tmdb: 
fragman tr: 
fragman en: 
video: 
ses tr varsayılan: 
ses en: 
altyazı tr: 
altyazı en: 
"""

TEMPLATE_SERIES = """tmdb: 
fragman tr: 
fragman en: 
bölüm S01E01:
video: 
ses tr varsayılan: 
ses en: 
altyazı tr: 
bölüm S01E02:
video: 
ses tr varsayılan: 
ses en: 
altyazı tr: 
"""
