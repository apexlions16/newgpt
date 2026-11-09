#!/usr/bin/env python3
"""
Telefondan yüklenen içerikleri işler (GitHub Actions'ta çalışır).

Telefon, videoyu işlemeden parçalar halinde `staging-…` adlı bir Release'e yükler,
yanına seçimleri içeren `job.json` koyar ve Release'i yayınlar. Bu betik:
  1. Sırada bekleyen (gövdesi "⏳" ile başlayan) staging Release'lerini bulur,
  2. parçaları indirip birleştirir,
  3. telefondaki ses/altyazı seçimlerini uygular,
  4. Windows uygulamasıyla aynı yolla hazırlayıp yükler (tools/gflix/uploader.py),
  5. başarılıysa staging Release'ini siler; hata olursa gövdesine hatayı yazar.

Ortam: GITHUB_TOKEN, GFLIX_REPO (owner/repo), GFLIX_WORKDIR
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
import time
import traceback
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gflix import media  # noqa: E402
from gflix.github import GitHub  # noqa: E402
from gflix.media import EncodeOptions, SubTrack  # noqa: E402
from gflix.uploader import Reporter, UploadJob, run_job  # noqa: E402
from gflix.util import norm_lang  # noqa: E402

QUEUED = "⏳"


class LogReporter(Reporter):
    def __init__(self):
        self.lines: list[str] = []
        self._last = 0.0

    def log(self, msg: str) -> None:
        self.lines.append(msg)
        print(msg, flush=True)

    def stage(self, name: str, fraction: float, text: str = "") -> None:
        now = time.time()
        if text and (now - self._last > 15 or fraction >= 1):
            self._last = now
            print(f"  [{name}] {text}", flush=True)


def set_body(gh: GitHub, rel: dict, text: str) -> None:
    gh.req("PATCH", f"/releases/{rel['id']}", json={"body": text[:120000]})


def _open_asset(gh: GitHub, asset: dict):
    """Release dosyasını akış olarak açar. İmzalı depolama adresine yetki başlığı gönderilmez."""
    r = gh.s.get(asset["url"], headers={"Accept": "application/octet-stream"}, allow_redirects=False, timeout=(30, 600))
    if r.status_code in (301, 302, 303, 307, 308):
        loc = r.headers["Location"]
        r.close()
        return requests.get(loc, stream=True, timeout=(30, 600))
    r.raise_for_status()
    return r


def download(gh: GitHub, asset: dict, out, append: bool = False) -> int:
    """Release dosyasını indirir. out: Path ya da açık dosya."""
    for attempt in range(5):
        try:
            with _open_asset(gh, asset) as r:
                r.raise_for_status()
                n = 0
                target = out if hasattr(out, "write") else open(out, "ab" if append else "wb")
                start = target.tell()
                try:
                    for chunk in r.iter_content(4 * 1024 * 1024):
                        target.write(chunk)
                        n += len(chunk)
                finally:
                    if target is not out:
                        target.close()
                if n != asset["size"]:
                    raise IOError(f"{asset['name']}: {n}/{asset['size']} bayt indirildi")
                return n
        except Exception as e:  # noqa: BLE001
            print(f"  indirme hatası ({attempt + 1}/5): {e}", flush=True)
            if hasattr(out, "write"):
                out.seek(start)
                out.truncate()
            time.sleep(5 * (attempt + 1))
    raise IOError(f"{asset['name']} indirilemedi")


def apply_selection(info: media.MediaInfo, job: dict, rep: LogReporter) -> None:
    audio = job.get("audio")
    if audio:
        wanted = {int(a["index"]): norm_lang(a.get("lang")) for a in audio}
        matched = 0
        for a in info.audio:
            if a.stream_index in wanted:
                a.include, a.lang = True, wanted[a.stream_index]
                matched += 1
            else:
                a.include = False
        if matched == 0:
            rep.log("  ⚠ Telefondaki ses seçimi dosyayla eşleşmedi; otomatik seçim kullanılıyor.")
            media.auto_select(info)
    else:
        media.auto_select(info)
    subs = job.get("subs")
    if subs is not None:
        wanted_s = {int(s["index"]): s for s in subs}
        for s in info.subs:
            sel = wanted_s.get(s.stream_index)
            s.include = sel is not None and not s.bitmap
            if sel:
                s.lang = norm_lang(sel.get("lang"))
                s.forced = bool(sel.get("forced"))


def process(gh: GitHub, rel: dict, workdir: Path, rep: LogReporter) -> None:
    assets = {a["name"]: a for a in gh.release_assets(rel["id"])}
    if "job.json" not in assets:
        raise ValueError("job.json bulunamadı (telefondaki yükleme tamamlanmamış olabilir)")
    with _open_asset(gh, assets["job.json"]) as raw:
        raw.raise_for_status()
        job = json.loads(raw.content.decode("utf-8"))
    rep.log(f"▶ {job.get('title')} ({job.get('kind')}) — {job.get('fileName')}, {job.get('size', 0) / 1024**3:.2f} GB")

    src_dir = workdir / "src"
    shutil.rmtree(src_dir, ignore_errors=True)
    src_dir.mkdir(parents=True)
    ext = Path(job.get("fileName") or "video.mkv").suffix.lower() or ".mkv"
    src = src_dir / f"source{ext}"
    with open(src, "wb") as out:
        for i, name in enumerate(job["parts"]):
            if name not in assets:
                raise ValueError(f"Eksik parça: {name}")
            download(gh, assets[name], out)
            print(f"  ↓ {name} ({i + 1}/{len(job['parts'])})", flush=True)
    size = src.stat().st_size
    if job.get("size") and size != int(job["size"]):
        raise ValueError(f"Dosya boyutu tutmuyor: {size} != {job['size']}")

    info = media.analyze(src)
    apply_selection(info, job, rep)
    for sf in job.get("subFiles") or []:
        if sf["asset"] not in assets:
            continue
        p = src_dir / re.sub(r"[^\w.\-]", "_", sf["asset"])
        download(gh, assets[sf["asset"]], p)
        lang, forced = norm_lang(sf.get("lang")), bool(sf.get("forced"))
        for s in info.subs:  # harici altyazı aynı dildeki gömülüyü ezer
            if s.lang == lang and s.forced == forced:
                s.include = False
        info.subs.append(SubTrack(lang=lang, forced=forced, file=str(p)))

    uj = UploadJob(
        kind=job["kind"], input=str(src), title=job["title"], item_id=job.get("itemId"), tmdb=job.get("tmdb"),
        year=job.get("year"), season=job.get("season"), episode=job.get("episode"), info=info,
        options=EncodeOptions(), parallel=4,
    )
    run_job(uj, gh, workdir / "work", rep)
    shutil.rmtree(src_dir, ignore_errors=True)


def main() -> int:
    repo = os.environ["GFLIX_REPO"]
    gh = GitHub(repo, os.environ["GITHUB_TOKEN"])
    workdir = Path(os.environ.get("GFLIX_WORKDIR", "gflix-work"))
    workdir.mkdir(parents=True, exist_ok=True)
    failures = 0
    # Aynı anda birden fazla yükleme gelirse hepsi tek çalıştırmada sırayla işlenir
    for _round in range(20):
        queued = sorted(
            (r for r in gh.list_releases()
             if r["tag_name"].startswith("staging-") and not r.get("draft") and (r.get("body") or "").startswith(QUEUED)),
            key=lambda r: r.get("created_at") or "",
        )
        if not queued:
            break
        rel = queued[0]
        rep = LogReporter()
        print(f"\n===== {rel['tag_name']} =====", flush=True)
        set_body(gh, rel, "⚙️ GitHub'da işleniyor…")
        try:
            process(gh, rel, workdir, rep)
            gh.delete_release(rel["tag_name"])
            print(f"✓ {rel['tag_name']} tamamlandı", flush=True)
        except Exception as e:  # noqa: BLE001
            failures += 1
            traceback.print_exc()
            tail = "\n".join(rep.lines[-25:])
            set_body(gh, rel, f"❌ Hata: {str(e).strip()[:600]}\n\n```\n{tail[-3000:]}\n```")
        shutil.rmtree(workdir / "src", ignore_errors=True)
    print(f"Bitti. Hatalı iş: {failures}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
