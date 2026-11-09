"""
Tema müziği (Plex/Emby'deki gibi): yapım sayfası açılınca arkada çalan kısa müzik.

Kaynak sırası:
  1. Yönetici telefondan YouTube linki verdiyse (item["themeSource"])
  2. ThemerrDB (LizardByte; Plex/Emby tema eklentilerinin kullandığı açık veritabanı): TMDB numarası → YouTube linki
  3. YouTube araması: "<ad> theme song" / "<ad> main theme soundtrack"

İndirilen ses ilk ~75 saniyeye kırpılır (yumuşak giriş/çıkış, ses seviyesi eşitlenir), AAC .m4a olarak "themes"
Release'ine yüklenir ve item["theme"] = {"file": {"parts": [url], "sizes": [n]}, "source", "yt", "at"} yazılır.
Bulunamayan/inmeyen yapım 7 gün sonra tekrar denenir (item["themeTried"]).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import time
from pathlib import Path

import requests

from .github import GitHub

THEME_TAG = "themes"
ITEMS_DIR = "catalog/items"
CLIP_SECONDS = 75
RETRY_AFTER = 7 * 86400
THEMERR = "https://app.lizardbyte.dev/ThemerrDB/{kind}/themoviedb/{id}.json"


def themerr_url(tmdb_id: int | None, series: bool) -> str | None:
    if not tmdb_id:
        return None
    try:
        r = requests.get(THEMERR.format(kind="tv_shows" if series else "movies", id=tmdb_id), timeout=20)
        if r.status_code != 200:
            return None
        return (r.json() or {}).get("youtube_theme_url") or None
    except (requests.RequestException, ValueError):
        return None


def _names(entry: dict | None, item: dict) -> list[str]:
    e = entry or {}
    names = [(e.get("names") or {}).get("en"), e.get("originalTitle"), e.get("title"), item.get("title")]
    out = []
    for n in names:
        if n and n not in out:
            out.append(n)
    return out


def _yt_dlp_base() -> list[str]:
    cmd = ["yt-dlp", "--no-playlist", "--no-warnings", "-q"]
    cookies = os.path.expanduser(os.environ.get("GFLIX_YT_COOKIES_FILE", "~/.config/gflix/cookies.txt"))
    if os.path.isfile(cookies):
        cmd += ["--cookies", cookies]
    return cmd


def download_clip(source: str, work: Path, log=print, progress=lambda t: None) -> Path:
    """source: YouTube linki ya da "ytsearch1:…". Döner: kırpılmış .m4a"""
    work.mkdir(parents=True, exist_ok=True)
    for p in work.glob("raw.*"):
        p.unlink()
    # Arama sonucunda çok uzun videoları (tam albüm, 1 saatlik derleme) atla
    progress("YouTube'dan indiriliyor")
    cmd = _yt_dlp_base() + ["-f", "bestaudio/best", "--match-filter", "duration < 900", "-o", str(work / "raw.%(ext)s"), source]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    raw = next(iter(work.glob("raw.*")), None)
    if r.returncode != 0 or raw is None:
        raise RuntimeError((r.stderr or r.stdout or "yt-dlp başarısız").strip().splitlines()[-1][:300])
    progress("Kırpılıyor ve ses seviyesi eşitleniyor")
    out = work / "theme.m4a"
    fade_out_at = CLIP_SECONDS - 6
    af = f"afade=t=in:d=2,afade=t=out:st={fade_out_at}:d=6,loudnorm=I=-18:TP=-2:LRA=11"
    r = subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", str(raw), "-t", str(CLIP_SECONDS), "-vn", "-af", af,
                        "-c:a", "aac", "-b:a", "128k", "-ac", "2", "-movflags", "+faststart", str(out)],
                       capture_output=True, text=True, timeout=600)
    raw.unlink(missing_ok=True)
    if r.returncode != 0 or not out.exists():
        raise RuntimeError(f"ffmpeg: {r.stderr.strip()[:300]}")
    return out


def candidates(item: dict, entry: dict | None) -> list[tuple[str, str]]:
    """(kaynak türü, yt-dlp adresi) listesi, öncelik sırasıyla."""
    series = item.get("type") == "series"
    out: list[tuple[str, str]] = []
    if item.get("themeSource"):
        out.append(("manual", item["themeSource"]))
        return out   # elle verildiyse sadece o
    t = themerr_url(item.get("tmdbId") or (entry or {}).get("tmdbId"), series)
    if t:
        out.append(("themerrdb", t))
    names = _names(entry, item)
    if names:
        year = (entry or {}).get("year") or item.get("year")
        q = f"{names[0]} {'theme song opening' if series else 'main theme soundtrack'}" + (f" {year}" if year and not series else "")
        out.append(("search", f"ytsearch1:{q}"))
    return out


def needs_theme(item: dict, now: int) -> bool:
    if item.get("themeOff"):
        return False
    th = item.get("theme") or {}
    if th.get("file"):
        # elle yeni link verildiyse yeniden indir
        return bool(item.get("themeSource")) and th.get("yt") != item.get("themeSource")
    return now - int(item.get("themeTried") or 0) > RETRY_AFTER or (item.get("themeSource") and item.get("themeSource") != item.get("themeTriedSource"))


def process_item(gh: GitHub, item_id: str, entry: dict | None, work: Path, log=print, progress=lambda t: None) -> str:
    path = f"{ITEMS_DIR}/{item_id}.json"
    item, sha = gh.get_json(path)
    if not item:
        return "kayıt yok"
    now = int(time.time())
    if not needs_theme(item, now):
        return "zaten var" if (item.get("theme") or {}).get("file") else "gerek yok"
    errors = []
    for kind, src in candidates(item, entry):
        try:
            log(f"  🎵 {item.get('title', item_id)}: {kind} → {src[:90]}")
            progress({"manual": "Verdiğin link", "themerrdb": "ThemerrDB", "search": "YouTube araması"}.get(kind, kind) + " deneniyor")
            clip = download_clip(src, work / item_id, log, progress)
            progress("GitHub'a yükleniyor")
            rel = gh.ensure_release(THEME_TAG, "G-Flix tema müzikleri", "Yapım sayfalarında arkada çalan kısa müzikler (otomatik).")
            existing = {a["name"]: a for a in gh.release_assets(rel["id"])}
            name = f"{item_id}.m4a"
            if name in existing:
                gh.delete_asset(existing[name]["id"])
                existing.pop(name)
            url = gh.upload_asset(rel, clip, name, "audio/mp4", existing)
            size = clip.stat().st_size
            shutil.rmtree(work / item_id, ignore_errors=True)
            # yükleme sürerken kayıt değişmiş olabilir: tazesini al
            for attempt in range(3):
                item, sha = gh.get_json(path)
                if not item:
                    return "kayıt silindi"
                item["theme"] = {"file": {"parts": [url], "sizes": [size]}, "source": kind, "yt": src, "at": now}
                item.pop("themeTried", None); item.pop("themeError", None); item.pop("themeTriedSource", None)
                try:
                    gh.put_json(path, item, f"Tema müziği: {item.get('title', item_id)}", sha)
                    return f"✓ {kind}"
                except Exception:  # noqa: BLE001
                    time.sleep(2)
            return "kaydedilemedi"
        except Exception as e:  # noqa: BLE001
            errors.append(f"{kind}: {e}")
            log(f"    ✗ {e}")
    shutil.rmtree(work / item_id, ignore_errors=True)
    item, sha = gh.get_json(path)
    if item:
        item["themeTried"] = now
        item["themeError"] = "; ".join(errors)[:400] or "kaynak bulunamadı"
        if item.get("themeSource"):
            item["themeTriedSource"] = item["themeSource"]
        try:
            gh.put_json(path, item, f"Tema müziği bulunamadı: {item.get('title', item_id)}", sha)
        except Exception:  # noqa: BLE001
            pass
    return "bulunamadı"


def run_pending(gh: GitHub, work: Path, log=print, limit: int = 5, stop=lambda: False) -> int:
    """Tema müziği eksik yapımları işler (en fazla [limit] tanesi). Döner: işlenen sayısı."""
    if not shutil.which("yt-dlp") or not shutil.which("ffmpeg"):
        return 0
    catalog, _ = gh.get_json("catalog/catalog.json")
    entries = {t["id"]: t for t in (catalog or {}).get("titles", [])}
    now = int(time.time())
    done = 0
    for f in gh.list_dir(ITEMS_DIR):
        if done >= limit or stop():
            break
        if not f["name"].endswith(".json"):
            continue
        item_id = f["name"][:-5]
        item, _ = gh.get_json(f"{ITEMS_DIR}/{item_id}.json")
        if not item or not needs_theme(item, now):
            continue
        res = process_item(gh, item_id, entries.get(item_id), work, log)
        log(f"  🎵 {item.get('title', item_id)}: {res}")
        done += 1
    return done


# ---------------------------------------------------------------- telefondan istek: "theme-queue" Release'i
# Gövde JSON: {"items": {"<id>": {"state": "queued|running|done|error", "text": "...", "title": "...", "at": epoch}}}
# Telefon "queued" yazar; işçi boştaysa hemen alır (film/dizi yükleniyorsa o bitince), adımları buraya yazar.
QUEUE_TAG = "theme-queue"


def _read_queue(gh: GitHub) -> tuple[dict | None, dict]:
    rel = gh.get_release(QUEUE_TAG)
    if not rel:
        return None, {"items": {}}
    try:
        data = json.loads(rel.get("body") or "{}")
    except ValueError:
        data = {}
    data.setdefault("items", {})
    return rel, data


def _write_queue(gh: GitHub, rel: dict, data: dict) -> None:
    # aynı anda telefon yeni istek eklemiş olabilir: tazesini alıp birleştir (işçi sadece kendi alanlarını yazar)
    _, fresh = _read_queue(gh)
    for k, v in fresh["items"].items():
        mine = data["items"].get(k)
        if mine is None or (v.get("state") == "queued" and v.get("at", 0) > mine.get("at", 0)):
            data["items"][k] = v
    gh.req("PATCH", f"/releases/{rel['id']}", json={"body": json.dumps(data, ensure_ascii=False)})


def run_queue(gh: GitHub, work: Path, log=print, stop=lambda: False) -> int:
    """Telefondan gelen tema müziği isteklerini hemen işler. Döner: işlenen sayısı."""
    rel, data = _read_queue(gh)
    if not rel:
        return 0
    pending = [k for k, v in data["items"].items() if v.get("state") == "queued"]
    now = int(time.time())
    # 3 günden eski bitmiş kayıtları temizle
    stale = [k for k, v in data["items"].items() if v.get("state") in ("done", "error") and now - v.get("at", 0) > 3 * 86400]
    if not pending:
        if stale:
            for k in stale:
                data["items"].pop(k, None)
            _write_queue(gh, rel, data)
        return 0
    if not shutil.which("yt-dlp") or not shutil.which("ffmpeg"):
        for k in pending:
            data["items"][k].update(state="error", text="Bilgisayarda yt-dlp/ffmpeg yok", at=now)
        _write_queue(gh, rel, data)
        return 0
    catalog, _ = gh.get_json("catalog/catalog.json")
    entries = {t["id"]: t for t in (catalog or {}).get("titles", [])}
    done = 0
    for item_id in pending:
        if stop():
            break
        last = [0.0]

        def progress(text, item_id=item_id):
            data["items"][item_id].update(state="running", text=str(text), at=int(time.time()))
            if time.time() - last[0] > 3:   # en fazla 3 sn'de bir yaz
                last[0] = time.time()
                try:
                    _write_queue(gh, rel, data)
                except Exception:  # noqa: BLE001
                    pass

        progress("Başladı")
        last[0] = 0
        try:
            res = process_item(gh, item_id, entries.get(item_id), work / "istek", log, progress)
        except Exception as e:  # noqa: BLE001
            res = f"hata: {e}"
        ok = res.startswith("✓") or res == "zaten var"
        err = ""
        if not ok:
            item, _ = gh.get_json(f"{ITEMS_DIR}/{item_id}.json")
            err = (item or {}).get("themeError") or res
        data["items"][item_id].update(
            state="done" if ok else "error",
            text=("✓ Hazır — katalog birkaç dakikada güncellenir" if res.startswith("✓") else "✓ Zaten var") if ok else f"İndirilemedi: {err}"[:300],
            at=int(time.time()),
        )
        _write_queue(gh, rel, data)
        log(f"  🎵 istek {item_id}: {res}")
        done += 1
    return done


if __name__ == "__main__":  # elle: GITHUB_TOKEN=… GFLIX_REPO=… python -m gflix.themes
    import sys
    g = GitHub(os.environ["GFLIX_REPO"], os.environ["GITHUB_TOKEN"])
    n = run_pending(g, Path(os.path.expanduser(os.environ.get("GFLIX_WORKDIR", "~/gflix-work"))) / "temalar", limit=int(sys.argv[1]) if len(sys.argv) > 1 else 100)
    print(json.dumps({"processed": n}))
