from __future__ import annotations

import re
import unicodedata


class Cancelled(Exception):
    """Kullanıcı işlemi iptal etti."""


LANG_ALIASES = {
    "tr": "tr", "tur": "tr", "turkish": "tr", "türkçe": "tr", "turkce": "tr",
    "en": "en", "eng": "en", "english": "en", "ingilizce": "en",
    "de": "de", "ger": "de", "deu": "de", "german": "de",
    "fr": "fr", "fre": "fr", "fra": "fr", "french": "fr",
    "es": "es", "spa": "es", "spanish": "es",
    "it": "it", "ita": "it", "ja": "ja", "jpn": "ja", "ko": "ko", "kor": "ko",
    "ru": "ru", "rus": "ru", "ar": "ar", "ara": "ar", "und": "und", "": "und",
}
LANG3 = {"tr": "tur", "en": "eng", "de": "ger", "fr": "fre", "es": "spa", "it": "ita",
         "ja": "jpn", "ko": "kor", "ru": "rus", "ar": "ara", "und": "und"}
LANG_LABEL = {"tr": "Türkçe", "en": "İngilizce", "de": "Almanca", "fr": "Fransızca", "es": "İspanyolca",
              "it": "İtalyanca", "ja": "Japonca", "ko": "Korece", "ru": "Rusça", "ar": "Arapça", "und": "Belirsiz"}
LANG_ORDER = ["tr", "en"]


def norm_lang(value: str | None) -> str:
    if not value:
        return "und"
    v = value.strip().lower()
    return LANG_ALIASES.get(v, v[:2] if len(v) >= 2 else "und")


def slugify(text: str) -> str:
    t = text.replace("ı", "i").replace("İ", "i")
    t = unicodedata.normalize("NFKD", t).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "-", t).strip("-") or "icerik"


def item_id_for(kind: str, title: str) -> str:
    return f"{'movie' if kind == 'movie' else 'series'}-{slugify(title)}"


def release_tag(item_id: str, kind: str, season: int | None = None, episode: int | None = None) -> str:
    if kind == "episode":
        return f"{item_id}-s{season:02d}e{episode:02d}"
    if kind == "trailer":
        return f"{item_id}-trailer"
    return item_id


_EP = re.compile(r"[Ss](\d{1,2})[ ._-]*[Ee](\d{1,3})|(?<!\d)(\d{1,2})x(\d{2,3})(?!\d)")
_YEAR = re.compile(r"(?<!\d)(19[3-9]\d|20[0-4]\d)(?!\d)")
_JUNK = re.compile(
    r"\b(2160p|1080p|720p|480p|4k|uhd|hdr|hdr10|dv|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|web|hdtv|dvdrip|x264|x265|h\.?264|h\.?265|"
    r"hevc|avc|aac|ac3|dts|ddp?5\.1|5\.1|7\.1|atmos|remux|proper|repack|extended|multi|dual|tr|eng|turkce|dublaj|altyazili|"
    r"yify|rarbg|amzn|nf|dsnp)\b", re.I)


def parse_filename(name: str) -> dict:
    """'Dark.Road.S01E02.1080p.mkv' -> {kind: episode, title: 'Dark Road', season: 1, episode: 2}"""
    stem = re.sub(r"\.[A-Za-z0-9]{2,4}$", "", name)
    low = stem.lower()
    if re.search(r"\b(trailer|fragman|teaser)\b", low):
        kind = "trailer"
    else:
        kind = "movie"
    result: dict = {"kind": kind, "season": None, "episode": None, "year": None}
    m = _EP.search(stem)
    cut = len(stem)
    if m and kind != "trailer":
        result["kind"] = "episode"
        result["season"] = int(m.group(1) or m.group(3))
        result["episode"] = int(m.group(2) or m.group(4))
        cut = m.start()
    y = _YEAR.search(stem[:cut] if m else stem)
    if y and y.start() > 0:
        result["year"] = int(y.group(1))
        if not m:
            cut = y.start()
    title = re.sub(r"[\(\[\s._-]+$", "", stem[:cut])
    title = re.sub(r"[\[\(].*?[\]\)]", " ", title)
    title = re.sub(r"[._]+", " ", title)
    title = _JUNK.sub(" ", title)
    title = re.sub(r"\b(trailer|fragman|teaser)\b", " ", title, flags=re.I)
    title = re.sub(r"[-–]+\s*$", "", re.sub(r"\s+", " ", title)).strip(" -")
    result["title"] = title
    return result
