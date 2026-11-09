"""
Otomatik fragman: fragmanı olmayan yapımlar için TMDB'deki resmi YouTube fragmanını indirip yükler.

Katalog üretilirken her yapımın TMDB fragman listesi "tmdbVideos" olarak kaydedilir (bkz. catalog._videos).
İşçi (Linux PC) boştayken: Türkçe fragman varsa onu, İngilizce fragman varsa onu indirir (1080p H.264 MP4 + varsa 4K VP9),
"<id>-<dil>-trailer" Release'ine yükler ve item["trailers"]'a yazar (elle yüklenen fragmanlarla aynı biçim).
Elle fragman yüklenmiş yapıma dokunmaz. Bulunamayan/inmeyen 7 gün sonra tekrar denenir (item["trailerAutoTried"]).
Yapımda kapatmak: item["trailerAutoOff"] = true.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import time
from pathlib import Path

from .github import GitHub
from .uploader import save_trailer

ITEMS_DIR = "catalog/items"
RETRY_AFTER = 7 * 86400
MAX_PART = 1_900_000_000
FMT = "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080][ext=mp4]+ba/bv*[height<=1080]+ba/b"
UHD_FMT = "bv*[height>1080][vcodec^=vp09]+ba[acodec=opus]/bv*[height>1080][vcodec^=vp9]+ba[acodec=opus]/bv*[height>1080][vcodec^=vp09]+ba/bv*[height>1080][vcodec^=vp9]+ba"


def _yt(url: str, fmt: str, merge: str, out: Path) -> Path | None:
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = ["yt-dlp", "--no-playlist", "--no-warnings", "-q", "-f", fmt, "--merge-output-format", merge, "-o", str(out) + ".%(ext)s", url]
    cookies = os.path.expanduser(os.environ.get("GFLIX_YT_COOKIES_FILE", "~/.config/gflix/cookies.txt"))
    if os.path.isfile(cookies):
        cmd[1:1] = ["--cookies", cookies]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    got = next(iter(sorted(out.parent.glob(out.name + ".*"))), None)
    if r.returncode != 0 or got is None:
        if r.returncode != 0 and fmt is FMT:
            raise RuntimeError((r.stderr or r.stdout or "yt-dlp başarısız").strip().splitlines()[-1][:300])
        return None
    return got


def _upload(gh: GitHub, rel: dict, path: Path, prefix: str, ctype: str) -> tuple[list[str], list[int]]:
    size = path.stat().st_size
    urls, sizes = [], []
    n = max(1, -(-size // MAX_PART))
    for i in range(n):
        off = i * MAX_PART
        ln = min(MAX_PART, size - off)
        urls.append(gh.upload_asset(rel, path, f"{prefix}.p{i:03d}{path.suffix}", ctype, None, offset=off, length=ln))
        sizes.append(ln)
    return urls, sizes


def pick(videos: list[dict]) -> list[tuple[str, str]]:
    """(dil, youtube adresi): Türkçe ve İngilizce için en iyisi (resmi fragman > teaser)."""
    out = []
    for lang in ("tr", "en"):
        v = next((x for x in videos if x.get("lang") == lang), None)
        if v:
            out.append((lang, f"https://www.youtube.com/watch?v={v['key']}"))
    if not out and videos:
        v = videos[0]
        out.append((v.get("lang") or "und", f"https://www.youtube.com/watch?v={v['key']}"))
    return out


def needs(item: dict, entry: dict | None, now: int) -> bool:
    if item.get("trailerAutoOff") or item.get("trailer") or item.get("trailers"):
        return False
    if not (entry or {}).get("tmdbVideos"):
        return False
    return now - int(item.get("trailerAutoTried") or 0) > RETRY_AFTER


def process_item(gh: GitHub, item_id: str, entry: dict, work: Path, log=print) -> str:
    title = entry.get("title") or item_id
    added, errors = [], []
    for lang, url in pick(entry.get("tmdbVideos") or []):
        d = work / item_id / lang
        try:
            log(f"  🎬 {title}: {lang} fragman → {url}")
            main = _yt(url, FMT, "mp4", d / "main")
            uhd = _yt(url, UHD_FMT, "mkv", d / "uhd")
            tag = f"{item_id}-trailer" if lang == "und" else f"{item_id}-{lang}-trailer"
            rel = gh.ensure_release(tag, f"{title} (Fragman {lang})", "G-Flix fragman dosyaları (otomatik, TMDB/YouTube).")
            for a in gh.release_assets(rel["id"]):
                gh.delete_asset(a["id"])
            urls, sizes = _upload(gh, rel, main, "v", "video/mp4")
            stream = {"master": "", "lang": lang, "release": tag, "audio": [] if lang == "und" else [lang], "subtitles": [],
                      "direct": {"video": {"parts": urls, "sizes": sizes}, "audio": [], "videoAudio": []}, "auto": True}
            if uhd is not None:
                u_urls, u_sizes = _upload(gh, rel, uhd, "uhd", "video/x-matroska")
                stream["uhd"] = {"parts": u_urls, "sizes": u_sizes}
            save_trailer(gh, item_id, stream)
            added.append(lang + (" 4K" if uhd else ""))
        except Exception as e:  # noqa: BLE001
            errors.append(f"{lang}: {e}")
            log(f"    ✗ {e}")
        finally:
            shutil.rmtree(d, ignore_errors=True)
    if added:
        return "✓ " + ", ".join(added)
    path = f"{ITEMS_DIR}/{item_id}.json"
    item, sha = gh.get_json(path)
    if item:
        item["trailerAutoTried"] = int(time.time())
        item["trailerAutoError"] = "; ".join(errors)[:400] or "uygun fragman yok"
        try:
            gh.put_json(path, item, f"Otomatik fragman bulunamadı: {title}", sha)
        except Exception:  # noqa: BLE001
            pass
    return "bulunamadı"


def run_pending(gh: GitHub, work: Path, log=print, limit: int = 3, stop=lambda: False) -> int:
    if not shutil.which("yt-dlp"):
        return 0
    catalog, _ = gh.get_json("catalog/catalog.json")
    entries = {t["id"]: t for t in (catalog or {}).get("titles", [])}
    now = int(time.time())
    done = 0
    for item_id, entry in entries.items():
        if done >= limit or stop():
            break
        # Katalogda fragmanı olanları ve TMDB fragmanı olmayanları baştan ele (gereksiz istek olmasın)
        if entry.get("trailer") or entry.get("trailers") or not entry.get("tmdbVideos"):
            continue
        item, _ = gh.get_json(f"{ITEMS_DIR}/{item_id}.json")
        if not item or not needs(item, entry, now):
            continue
        res = process_item(gh, item_id, entry, work, log)
        log(f"  🎬 {entry.get('title', item_id)}: {res}")
        done += 1
    return done
