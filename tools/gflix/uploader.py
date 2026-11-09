"""Bir yükleme işini baştan sona yürütür: analiz -> HLS -> bölme -> altyazı -> GitHub -> katalog kaydı."""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import media
from .github import GitHub
from .media import AudioTrack, EncodeOptions, MediaInfo, SubTrack
from .util import Cancelled, LANG_LABEL, item_id_for, release_tag

ITEMS_DIR = "catalog/items"


@dataclass
class UploadJob:
    kind: str                 # movie | episode | trailer
    input: str
    title: str
    item_id: str | None = None
    tmdb: int | None = None
    year: int | None = None
    season: int | None = None
    episode: int | None = None
    info: MediaInfo | None = None   # None ise analiz edilip otomatik seçilir
    options: EncodeOptions = field(default_factory=EncodeOptions)
    parallel: int = 4               # aynı anda yüklenecek parça sayısı
    delete_source: bool = False     # hazırlandıktan sonra kaynak videoyu sil (disk tasarrufu)

    @property
    def resolved_id(self) -> str:
        return self.item_id or item_id_for("episode" if self.kind == "episode" else "movie", self.title)

    @property
    def tag(self) -> str:
        return release_tag(self.resolved_id, self.kind, self.season, self.episode)

    @property
    def display(self) -> str:
        if self.kind == "episode":
            return f"{self.title} S{self.season:02d}E{self.episode:02d}"
        if self.kind == "trailer":
            return f"{self.title} (Fragman)"
        return self.title


class Reporter:
    """Aşama ve ilerleme bildirimi. Arayüz bunu alt sınıflayabilir."""

    def log(self, msg: str) -> None:
        print(msg, flush=True)

    def stage(self, name: str, fraction: float, text: str = "") -> None:
        pass


STAGES = [("analyze", "Analiz", 0.02), ("package", "Hazırlanıyor", 0.38), ("split", "Bölünüyor", 0.05),
          ("subs", "Altyazılar", 0.02), ("upload", "Yükleniyor", 0.50), ("catalog", "Kataloğa ekleniyor", 0.03)]


def _signature(job: UploadJob, info: MediaInfo) -> str:
    p = Path(job.input)
    st = p.stat()
    sel = [(a.stream_index, a.lang, a.file) for a in info.audio if a.include]
    subs = [(s.stream_index, s.file, s.attachment, s.lang, s.forced) for s in info.subs if s.include]
    raw = json.dumps([str(p.resolve()), st.st_size, int(st.st_mtime), sel, subs, job.kind, vars(job.options), media.PART_LIMIT], default=str)
    return hashlib.sha1(raw.encode()).hexdigest()


def run_job(job: UploadJob, gh: GitHub | None, workdir: Path, rep: Reporter | None = None,
            cancel: Callable[[], bool] = lambda: False, keep: bool = False) -> dict:
    rep = rep or Reporter()
    log = rep.log

    def check():
        if cancel():
            raise Cancelled()

    if job.kind == "episode" and (job.season is None or job.episode is None):
        raise ValueError("Dizi bölümü için sezon ve bölüm numarası gerekli.")

    # 1) Analiz
    rep.stage("analyze", 0, "Video inceleniyor")
    info = job.info
    if info is None:
        info = media.analyze(Path(job.input))
        media.auto_select(info)
    if job.kind == "trailer":
        # Fragmanda tek ses (tercihen Türkçe), altyazı yok
        chosen = [a for a in info.audio if a.include] or info.audio[:1]
        chosen.sort(key=lambda a: 0 if a.lang == "tr" else 1)
        for a in info.audio:
            a.include = a is chosen[0]
        for s in info.subs:
            s.include = False
        job.options.max_height = min(job.options.max_height, 1080)
    audios = [a for a in info.audio if a.include]
    subs = [s for s in info.subs if s.include and not s.bitmap]
    log(f"▶ {job.display} [{job.tag}]")
    log("  Görüntü: " + info.video_desc)
    log("  Ses: " + ", ".join(LANG_LABEL.get(a.lang, a.lang) for a in audios))
    log("  Altyazı: " + (", ".join(media.sub_label(s) for s in subs) or "yok"))
    rep.stage("analyze", 1)
    check()

    work = workdir / job.tag
    state_file = work / "state.json"
    sig = _signature(job, info)
    state = None
    if state_file.exists():
        try:
            state = json.loads(state_file.read_text(encoding="utf-8"))
            if state.get("sig") != sig:
                state = None
        except Exception:
            state = None

    if state is None:
        if work.exists():
            shutil.rmtree(work)
        hls_dir = work / "hls"
        # 2) HLS paketleme
        rep.stage("package", 0, "Video hazırlanıyor")
        master = media.package_hls(info, audios, hls_dir, job.options, log,
                                   progress=lambda f: rep.stage("package", f, f"Video hazırlanıyor %{f * 100:.0f}"),
                                   cancel=cancel)
        check()
        # 3) Bölme
        rep.stage("split", 0, "Parçalara bölünüyor")
        master_lines, names = media.parse_master(master)
        variants = {}
        for i, n in enumerate(names):
            variants[n] = media.split_variant(Path(n).stem, hls_dir / n, log)
            rep.stage("split", (i + 1) / len(names))
            check()
        # 4) Altyazılar
        sub_files = []
        used_names: set[str] = set()
        for i, s in enumerate(subs):
            base = f"sub_{s.lang}{'_forced' if s.forced else ''}"
            name, n = base, 2
            while name in used_names:
                name, n = f"{base}_{n}", n + 1
            used_names.add(name)
            out = work / f"{name}.vtt"
            try:
                media.convert_subtitle(s, info.path, out, log)
                if not out.exists() or out.stat().st_size < 10:
                    raise RuntimeError("altyazı boş çıktı")
            except Exception as ex:  # bir altyazı bozuksa bütün yükleme durmasın
                log(f"  ⚠ Altyazı atlandı ({s.describe()}): {str(ex).splitlines()[-1] if str(ex) else ex}")
                continue
            sub_files.append({"lang": s.lang, "forced": s.forced, "path": str(out), "label": media.sub_label(s)})
            rep.stage("subs", (i + 1) / max(len(subs), 1))
        state = {
            "sig": sig, "master_lines": master_lines,
            "variants": {n: vars(v) for n, v in variants.items()},
            "subs": sub_files, "audio": [a.lang for a in audios], "duration": info.duration,
        }
        state_file.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
        if job.delete_source:
            Path(job.input).unlink(missing_ok=True)
            log("  Kaynak dosya silindi (yer açmak için).")
    else:
        log("  Daha önce hazırlanmış dosyalar bulundu, hazırlık adımı atlanıyor.")
        for s in ("package", "split", "subs"):
            rep.stage(s, 1)

    variants = {n: media.Variant(**v) for n, v in state["variants"].items()}
    total = sum(Path(p).stat().st_size for v in variants.values() for p in v.parts)
    log(f"✓ Hazır: {sum(len(v.parts) for v in variants.values())} parça, toplam {total / 1024**3:.2f} GB")
    if gh is None:
        log(f"(deneme) Dosyalar: {work}")
        return {}

    # 5) GitHub'a yükleme
    rep.stage("upload", 0, "GitHub'a yükleniyor")
    rel = gh.ensure_release(job.tag, job.display, "G-Flix içerik dosyaları. G-Flix Stüdyo / tools/gflix_upload.py tarafından yönetilir.")
    existing = {a["name"]: a for a in gh.release_assets(rel["id"])}
    done = [0]
    lock = threading.Lock()
    samples: list[tuple[float, int]] = [(time.time(), 0)]
    last_emit = [0.0]

    def on_bytes(n: int):
        with lock:
            done[0] += n
            now = time.time()
            if now - last_emit[0] < 0.3 and done[0] < total:
                return
            last_emit[0] = now
            samples.append((now, done[0]))
            while len(samples) > 2 and now - samples[0][0] > 15:  # son 15 sn'nin hızı
                samples.pop(0)
            t_a, d_a = samples[0]
            speed = (done[0] - d_a) / max(now - t_a, 0.001)
            rem = (total - done[0]) / speed if speed > 0 else 0
            text = (f"Yükleniyor {done[0] / 1024**2:,.0f}/{total / 1024**2:,.0f} MB · {speed / 1024**2:.1f} MB/sn"
                    f" · {parallel} bağlantı · kalan {int(rem // 60)} dk {int(rem % 60)} sn")
        rep.stage("upload", min(done[0] / max(total, 1), 1.0), text)

    used: set[str] = set()

    def up(path: Path, name: str, ctype: str, count=True, client: GitHub | None = None) -> str:
        used.add(name)
        return (client or gh).upload_asset(rel, path, name, ctype, existing, on_bytes if count else (lambda n: None), cancel)

    # Video/ses parçaları aynı anda birden fazla bağlantıyla yüklenir (uzak sunucuda tek bağlantı yavaş kalır)
    parts = [Path(p) for v in variants.values() for p in v.parts]
    parts.sort(key=lambda p: p.stat().st_size, reverse=True)
    parallel = max(1, min(job.parallel, len(parts)))
    part_url: dict[str, str] = {}
    failed = threading.Event()
    local = threading.local()

    def client() -> GitHub:
        if not hasattr(local, "gh"):
            local.gh = gh.clone() if hasattr(gh, "clone") else gh
        return local.gh

    def up_part(p: Path) -> None:
        if failed.is_set() or cancel():
            raise Cancelled()
        part_url[p.name] = up(p, p.name, "video/mp2t", client=client())

    with ThreadPoolExecutor(max_workers=parallel) as pool:
        futures = [pool.submit(up_part, p) for p in parts]
        error: BaseException | None = None
        for fut in as_completed(futures):
            try:
                fut.result()
            except BaseException as e:  # biri başarısız olursa diğerlerini durdur
                if error is None or isinstance(error, Cancelled):
                    error = e
                failed.set()
        if error is not None:
            raise error

    playlist_urls: dict[str, str] = {}
    for fname, var in variants.items():
        part_urls = [part_url[Path(p).name] for p in var.parts]
        text = "\n".join(re.sub(r"\{\{PART:(\d+)\}\}", lambda m: part_urls[int(m.group(1))], l) for l in var.lines) + "\n"
        pl_path = Path(var.playlist).with_name(var.name + "_final.m3u8")
        pl_path.write_text(text)
        playlist_urls[fname] = up(pl_path, f"{var.name}.m3u8", "application/vnd.apple.mpegurl", False)

    def repl(line: str) -> str:
        line = re.sub(r'URI="([^"]+)"', lambda m: f'URI="{playlist_urls.get(m.group(1), m.group(1))}"', line)
        return playlist_urls.get(line, line)

    final_master = work / "master_final.m3u8"
    final_master.write_text("\n".join(repl(l) for l in state["master_lines"]) + "\n")
    master_url = up(final_master, "master.m3u8", "application/vnd.apple.mpegurl", False)

    subtitles = []
    for s in state["subs"]:
        url = up(Path(s["path"]), Path(s["path"]).name, "text/vtt", False)
        subtitles.append({"lang": s["lang"], "label": s["label"], "url": url, "forced": s["forced"]})
    for name, a in existing.items():
        if name not in used:
            gh.delete_asset(a["id"])
    rep.stage("upload", 1)

    stream = {"master": master_url, "audio": state["audio"], "subtitles": subtitles,
              "duration": round(state["duration"], 1), "release": job.tag}

    # 6) Katalog kaydı
    rep.stage("catalog", 0, "Kataloğa ekleniyor")
    msg = save_stream(gh, job, stream)
    log(f"✓ {msg}")
    log("  GitHub Actions birkaç dakika içinde TMDB'den Türkçe bilgileri çekip kataloğu güncelleyecek.")
    rep.stage("catalog", 1, "Tamamlandı")
    if not keep:
        shutil.rmtree(work, ignore_errors=True)
    return stream


def save_stream(gh: GitHub, job: UploadJob, stream: dict) -> str:
    item_id = job.resolved_id
    path = f"{ITEMS_DIR}/{item_id}.json"
    for attempt in range(3):
        item, sha = gh.get_json(path)
        now = int(time.time())
        if job.kind == "trailer":
            if not item:
                raise ValueError(f"Fragman eklenecek yapım bulunamadı: {item_id}")
            item["trailer"] = stream
            msg = f"Fragman eklendi: {item.get('title', item_id)}"
        else:
            item = item or {"id": item_id, "type": "movie" if job.kind == "movie" else "series", "title": job.title, "added": now}
            item["title"] = job.title
            item["updated"] = now
            if job.tmdb:
                item["tmdbId"] = job.tmdb
            if job.year:
                item["year"] = job.year
            if job.kind == "movie":
                item["stream"] = stream
                msg = f"İçerik eklendi: {job.title}"
            else:
                eps = [e for e in item.get("episodes", []) if not (e["season"] == job.season and e["episode"] == job.episode)]
                eps.append({"season": job.season, "episode": job.episode, "added": now, "stream": stream})
                item["episodes"] = sorted(eps, key=lambda e: (e["season"], e["episode"]))
                msg = f"Bölüm eklendi: {job.title} S{job.season:02d}E{job.episode:02d}"
        try:
            gh.put_json(path, item, msg, sha)
            return msg
        except Exception as e:  # aynı anda başka bir kayıt yazıldıysa (sha çakışması) tekrar dene
            if attempt == 2:
                raise
            time.sleep(2)
            print(f"  tekrar deneniyor: {e}")
    return ""


def save_trailer(gh: GitHub, item_id: str, stream: dict) -> str:
    """Dile göre fragman kaydı: item["trailers"] listesinde aynı dildekinin yerine geçer; item["trailer"] = tercih edilen
    (Türkçe > dilsiz > ilk). Eski uygulamalar sadece "trailer"ı okur."""
    path = f"{ITEMS_DIR}/{item_id}.json"
    lang = stream.get("lang") or "und"
    for attempt in range(3):
        item, sha = gh.get_json(path)
        if not item:
            raise ValueError(f"Fragman eklenecek yapım bulunamadı: {item_id}")
        trailers = [t for t in item.get("trailers", []) if (t.get("lang") or "und") != lang]
        old = item.get("trailer")
        if old and not item.get("trailers") and (old.get("lang") or "und") != lang:
            trailers.append(old)        # dilsiz eski fragmanı listede tut
        trailers.append(stream)
        order = {"tr": 0, "und": 1}
        trailers.sort(key=lambda t: order.get(t.get("lang") or "und", 2))
        item["trailers"] = trailers
        item["trailer"] = trailers[0]
        msg = f"Fragman eklendi: {item.get('title', item_id)}" + ("" if lang == "und" else f" ({lang})")
        try:
            gh.put_json(path, item, msg, sha)
            return msg
        except Exception as e:  # noqa: BLE001
            if attempt == 2:
                raise
            time.sleep(2)
            print(f"  tekrar deneniyor: {e}")
    return ""
