#!/usr/bin/env python3
"""
Linkten toplu ekleme (GitHub Actions'ta çalışır).

Uygulama (telefon / Windows) tarifi `linkjob-…` adlı bir Release'e `recipe.txt` olarak koyup yayınlar.
Bu betik:
  1. Tarifi okur, TMDB'den adı/yılı alır.
  2. Bütün dosyaları aynı anda, hız sınırı olmadan indirir (aria2c 16 bağlantı / gdown / yt-dlp).
     .txt gibi yanlış uzantılar içerikten tanınıp düzeltilir.
  3. Her film/bölüm için MKVToolNix ile tek MKV yapar: "Film Adı (Yıl).mkv" (diller, varsayılan, zorunlu).
  4. G-Flix'e yükler (tools/gflix/uploader.py), dosyaları hemen siler, kataloğu günceller.
  5. Fragmanı yt-dlp ile indirip ayrıca yükler.
  6. Her şeyi Release gövdesine canlı yazar; uygulamalar buradan takip eder.

GFLIX_OUTPUT=mkv (Linux bilgisayardaki tools/gflix_worker.py): MKVToolNix'in yaptığı MKV olduğu gibi (512 MB'lık parçalar
halinde) yüklenir, HLS'e çevrilmez; oynatıcı MKV'yi doğrudan çalar (sesler tek dosyada, kayma olmaz). Yükleme GitHub'dan
tek tek doğrulanmadan (boyut + sha256) kaynak dosyalar ve MKV silinmez. GFLIX_CATALOG=github: katalog GitHub'da
(catalog.yml, TMDB) güncellenir, bilgisayarda TMDB/git gerekmez.

Fragmanlar: "fragman tr: …", "fragman en: …" (dil başına bir tane). YouTube'dan iki sürüm iner, dönüştürülmez:
1080p'ye kadar H.264 MP4 (her telefonda oynar) ve varsa 4K VP9 (destekleyen telefonda Wi-Fi'de oynar).

"mod: hızlı" tariflerinde birleştirme/dönüştürme yoktur: video ve ses dosyaları olduğu gibi (gerekirse 512 MB'lık
parçalar halinde) "media-…" Release'ine konur, altyazılar VTT'ye çevrilir, kataloğa "direct" kaydı yazılır.
Telefonun doğrudan media-… Release'ine yüklediği dosyalar (asset:media-…/f0) hiç indirilmez.

Ortam: GITHUB_TOKEN, GFLIX_REPO, GFLIX_BRANCH, GFLIX_TAG, TMDB_API_KEY, GFLIX_RUN_URL, GFLIX_WORKDIR
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import shutil
import subprocess
import sys
import threading
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from gflix import catalog as catalog_mod  # noqa: E402
from gflix.fetch import Download, Downloader, method_for, probe_size  # noqa: E402
from gflix.github import GitHub, GitHubError  # noqa: E402
from gflix.recipe import FileSpec, Item, Recipe, parse  # noqa: E402
from gflix.hls import mux_aligned  # noqa: E402
from gflix.tmdb import TMDB  # noqa: E402
from gflix import sync as av_sync  # noqa: E402
from gflix.subs import to_webvtt  # noqa: E402
from gflix.uploader import ITEMS_DIR, STAGES, Reporter, UploadJob, run_job, save_stream, save_trailer  # noqa: E402
from gflix.util import LANG_LABEL, Cancelled, item_id_for, norm_lang  # noqa: E402

STATUS_MARK = "<!--GFLIX_STATUS"
RESERVE = 3 * 1024 ** 3
UNKNOWN_SIZE = {"video": 3 * 1024 ** 3, "audio": 300 * 1024 ** 2, "subtitle": 1024 ** 2}
WEIGHTS = {k: w for k, _, w in STAGES}
DIRECT_PART = 512 * 1024 ** 2   # doğrudan oynatılan dosyaların parça boyutu (GitHub sınırı 2 GB)
PLAYABLE_AUDIO = ("A_AAC", "A_OPUS", "A_MPEG/L3", "A_MPEG/L2", "A_VORBIS", "A_FLAC")
TRAILER_FMT = "bv*[height<=1080][vcodec^=avc1]+ba[ext=m4a]/b[height<=1080][ext=mp4]/bv*[height<=1080][ext=mp4]+ba/bv*[height<=1080]+ba/b"
TRAILER_UHD_FMT = "bv*[height>1080][vcodec^=vp09]+ba[acodec=opus]/bv*[height>1080][vcodec^=vp9]+ba[acodec=opus]/bv*[height>1080][vcodec^=vp09]+ba/bv*[height>1080][vcodec^=vp9]+ba"


def output_mode() -> str:
    return os.environ.get("GFLIX_OUTPUT", "hls").lower()


def catalog_on_github() -> bool:
    return os.environ.get("GFLIX_CATALOG", "").lower() == "github"


def sha256_slice(path: Path, offset: int, length: int) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        f.seek(offset)
        left = length
        while left > 0:
            b = f.read(min(8 * 1024 * 1024, left))
            if not b:
                break
            h.update(b)
            left -= len(b)
    return h.hexdigest()


class TrailerGroup:
    """Bütün fragman indirmeleri (her dil için normal + varsa 4K) tek bir "fragman" satırı gibi görünür."""

    def __init__(self):
        self.items: list[tuple[str, str, Download]] = []   # (dil, "main"|"uhd", indirme)

    def mains(self):
        return [d for _, v, d in self.items if v == "main"]

    @property
    def state(self) -> str:
        if any(d.state in ("waiting", "active") for _, _, d in self.items):
            return "active"
        return "done" if any(d.state == "done" for d in self.mains()) else "error"

    @property
    def error(self) -> str:
        return "; ".join(d.error for d in self.mains() if d.error)[:600]

    @property
    def done(self) -> int:
        return sum(d.done for _, _, d in self.items)

    @property
    def speed(self) -> float:
        return sum(d.speed for _, _, d in self.items)

    def as_dict(self) -> dict:
        total = sum(d.total for _, _, d in self.items)
        return {"label": "Fragman" + (f" ({', '.join(sorted({l for l, _, _ in self.items}))})" if self.items else ""),
                "state": self.state, "done": self.done, "total": total, "speed": round(self.speed), "error": self.error[:300]}


def human_time(sec: float) -> str:
    sec = int(sec)
    return f"{sec // 3600} sa {sec % 3600 // 60} dk {sec % 60} sn" if sec >= 3600 else f"{sec // 60} dk {sec % 60} sn"


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024
    return f"{n:.1f} GB"


# ------------------------------------------------------------------ durum (Release gövdesi)

ACTIVE_STATUSES: set = set()     # süren işlerin durumları (istek hakkı koruması mesajı kartlarına yazılır)


def _quota_notify(msg: str) -> None:
    for s in list(ACTIVE_STATUSES):
        try:
            s.log(msg.strip())
            s.push(force=True)
        except Exception:  # noqa: BLE001
            pass


__import__("gflix.github", fromlist=["QUOTA"]).QUOTA["notify"] = _quota_notify


class Status:
    def __init__(self, gh: GitHub, rel: dict):
        self.gh, self.rel = gh, rel
        self.data: dict = {"v": 1, "state": "running", "stage": "Başlıyor", "title": "", "kind": "", "started": int(time.time()),
                           "updated": int(time.time()), "run_url": os.environ.get("GFLIX_RUN_URL", ""), "items": [], "trailer": None,
                           "log": [], "warnings": [],
                           "worker": os.environ.get("GFLIX_WORKER_NAME", "github")}
        self.lock = threading.Lock()
        self._last = 0.0
        self.full: list[str] = []      # ayrıntılı kayıt (log.txt): her şey, zaman damgalı
        self.t0 = time.time()
        self.data["has_log"] = False
        # Süre istatistiği (unix sn): start, site (siteden indirme sırası alındı), dl_start, dl_end, end; onay bekleme sn; inen bayt
        self.data["timing"] = {"start": int(self.t0)}

    def mark(self, key: str, value: float | None = None, only_first: bool = True) -> None:
        with self.lock:
            t = self.data.setdefault("timing", {})
            if only_first and key in t:
                return
            t[key] = int(value if value is not None else time.time())

    def add_time(self, key: str, value: float) -> None:
        with self.lock:
            t = self.data.setdefault("timing", {})
            t[key] = int(t.get(key, 0) + value)

    def _stamp(self, msg: str) -> str:
        el = int(time.time() - self.t0)
        return f"{time.strftime('%H:%M:%S')} +{el // 60:02d}:{el % 60:02d}  {msg}"

    def log(self, msg: str) -> None:
        print(msg, flush=True)
        with self.lock:
            self.data["log"].append(msg[:400])
            del self.data["log"][:-40]
            self.full.append(self._stamp(msg))

    def detail(self, msg: str) -> None:
        """Sadece ayrıntılı kayda (log.txt) giren satırlar: komutlar, iz listeleri, ölçümler."""
        print(msg, flush=True)
        with self.lock:
            for line in str(msg).splitlines() or [""]:
                self.full.append(self._stamp("    " + line))

    def save_log(self) -> None:
        """Ayrıntılı kaydı Release'e log.txt olarak koyar (uygulamada "Ayrıntılı kayıt"), bilgisayarda da dosyaya yazar."""
        with self.lock:
            text = "\n".join(self.full) + "\n"
        log_dir = os.environ.get("GFLIX_LOG_DIR")
        if log_dir:
            try:
                Path(log_dir).mkdir(parents=True, exist_ok=True)
                (Path(log_dir) / f"{self.rel['tag_name']}.log").write_text(text, encoding="utf-8")
            except OSError as e:
                print(f"  kayıt dosyası yazılamadı: {e}", flush=True)
        try:
            for a in self.gh.release_assets(self.rel["id"]):
                if a["name"] == "log.txt":
                    self.gh.delete_asset(a["id"])
            tmp = Path(os.environ.get("RUNNER_TEMP") or "/tmp") / f"gflix-{self.rel['id']}-log.txt"
            tmp.write_text(text, encoding="utf-8")
            self.gh.upload_asset(self.rel, tmp, "log.txt", "text/plain; charset=utf-8")
            tmp.unlink(missing_ok=True)
            with self.lock:
                self.data["has_log"] = True
        except Exception as e:  # noqa: BLE001
            print(f"  ayrıntılı kayıt yüklenemedi: {e}", flush=True)

    def push(self, force: bool = False) -> None:
        now = time.time()
        # Durum her 15 sn'de bir yazılır (önceden 4 sn: iş başına saatte ~900 istek, TV'nin payını bitiriyordu);
        # saatlik hak 1000'in altına inince 60 sn. Önemli anlar (force) her zaman hemen yazılır.
        from gflix.github import rate_low
        if not force and now - self._last < (60 if rate_low() else 15):
            return
        self._last = now
        with self.lock:
            self.data["updated"] = int(now)
            body = self._summary() + f"\n\n{STATUS_MARK}\n" + json.dumps(self.data, ensure_ascii=False) + "\n-->"
        try:
            self.gh.req("PATCH", f"/releases/{self.rel['id']}", json={"body": body[:120000]})
        except Exception as e:  # noqa: BLE001
            print(f"  durum yazılamadı: {e}", flush=True)

    def _summary(self) -> str:
        d = self.data
        icon = {"running": "⚙️", "done": "✅", "partial": "⚠️", "error": "❌", "cancelled": "🛑"}.get(d["state"], "⚙️")
        lines = [f"{icon} {d.get('title') or 'Linkten ekleme'} — {d['stage']}"]
        for it in d["items"]:
            lines.append(f"- {it['label']}: {it['text']} (%{int(it['progress'] * 100)})")
        if d.get("trailer"):
            lines.append(f"- Fragman: {d['trailer']['text']}")
        if d.get("run_url"):
            lines.append(f"\nCanlı kayıt: {d['run_url']}")
        return "\n".join(lines)


@dataclass
class ItemRun:
    item: Item
    index: int
    state: str = "waiting"        # waiting | downloading | ready | processing | done | error | skipped
    progress: float = 0.0
    text: str = "Sırada"
    error: str = ""
    downloads: list[Download] = field(default_factory=list)
    reserved: int = 0

    def as_dict(self) -> dict:
        return {"label": self.item.label, "state": self.state, "progress": round(self.progress, 4), "text": self.text,
                "error": self.error[:600], "files": [d.as_dict() for d in self.downloads]}


# ------------------------------------------------------------------ MKVToolNix

def mkv_identify(path: Path) -> dict:
    out = subprocess.run(["mkvmerge", "-J", str(path)], capture_output=True, text=True, encoding="utf-8")
    if out.returncode not in (0, 1):
        raise RuntimeError(f"mkvmerge dosyayı tanımadı: {path.name}\n{out.stdout[-500:]}{out.stderr[-500:]}")
    data = json.loads(out.stdout)
    if not data.get("container", {}).get("recognized", True):
        raise RuntimeError(f"Desteklenmeyen dosya: {path.name}")
    return data


def _lang(props: dict) -> str:
    return norm_lang(props.get("language_ietf") or props.get("language") or "und")


def _is_utf8(path: Path) -> bool:
    try:
        path.read_bytes().decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def describe_media(path: Path, info: dict | None = None) -> str:
    """Ayrıntılı kayıt için: dosyanın kabı, süresi ve bütün izleri (mkvmerge -J)."""
    try:
        info = info or mkv_identify(path)
    except Exception as e:  # noqa: BLE001
        return f"{path.name}: tanınmadı ({e})"
    c = info.get("container", {})
    cp = c.get("properties", {}) or {}
    dur = cp.get("duration")
    lines = [f"{path.name}  [{c.get('type', '?')}, {human(path.stat().st_size) if path.exists() else '?'}"
             + (f", {dur / 1e9:.3f} sn" if dur else "") + "]"]
    for t in info.get("tracks", []):
        pr = t.get("properties", {}) or {}
        bits = [f"#{t['id']} {t['type']}", t.get("codec", "?"), f"dil={_lang(pr)}"]
        if pr.get("pixel_dimensions"):
            bits.append(pr["pixel_dimensions"])
        if pr.get("default_duration"):
            bits.append(f"{1e9 / pr['default_duration']:.3f} fps" if t["type"] == "video" else "")
        if pr.get("audio_channels"):
            bits.append(f"{pr['audio_channels']} kanal {pr.get('audio_sampling_frequency', '')} Hz")
        if pr.get("track_name"):
            bits.append(f"ad=\"{pr['track_name']}\"")
        if pr.get("default_track"):
            bits.append("varsayılan")
        if pr.get("forced_track"):
            bits.append("zorunlu")
        lines.append("   " + "  ".join(b for b in bits if b))
    return "\n".join(lines)


def mkvmerge(item: Item, files: dict[int, Path], out: Path, title: str, on_progress, sidecar: set[int] | None = None,
             notes: list[str] | None = None, detail=None) -> None:
    """sidecar: MKV'ye konmayıp ayrı VTT olarak yüklenecek altyazıların id(spec)'leri (videodaki aynı dildekiler yine çıkarılır).
    notes: senkron düzeltmeleri buraya yazılır (gflix/sync.py)."""
    sidecar = sidecar or set()
    notes = notes if notes is not None else []
    detail = detail or (lambda m: None)
    video_spec = item.video
    video = files[id(video_spec)]
    info = mkv_identify(video)
    detail("── Girdi dosyaları ──")
    detail("VIDEO: " + describe_media(video, info))
    for f in item.files:
        if f is not video_spec and id(f) in files:
            role = {"audio": "SES", "subtitle": "ALTYAZI"}.get(f.role, f.role)
            detail(f"{role} ({f.lang}{', zorunlu' if f.forced else ''}{', varsayılan' if f.default else ''}): "
                   + describe_media(Path(files[id(f)])))
    tracks = info.get("tracks", [])
    v_audio = [t for t in tracks if t["type"] == "audio"]
    v_subs = [t for t in tracks if t["type"] == "subtitles"]
    ext_audio = [f for f in item.files if f.role == "audio"]
    ext_subs = [f for f in item.files if f.role == "subtitle"]
    if not v_audio and not ext_audio:
        raise RuntimeError(f"{item.label}: videoda ses yok ve hiç 'ses tr:' / 'ses en:' linki verilmemiş. Film sessiz kalırdı, işlenmedi.")

    cmd = ["mkvmerge", "--gui-mode", "-o", str(out), "--title", title]
    eff_audio = {}
    for i, t in enumerate(v_audio):
        lang = item.video_audio[i] if i < len(item.video_audio) else _lang(t.get("properties", {}))
        eff_audio[t["id"]] = lang
        if i < len(item.video_audio):
            cmd += ["--language", f"{t['id']}:{lang}"]
    eff_subs = {}
    for i, t in enumerate(v_subs):
        props = t.get("properties", {})
        lang = item.video_subs[i] if i < len(item.video_subs) else _lang(props)
        eff_subs[t["id"]] = (lang, bool(props.get("forced_track")))
        if i < len(item.video_subs):
            cmd += ["--language", f"{t['id']}:{lang}"]
    # Harici ses/altyazı, videodaki aynı dildekinin yerine geçer
    drop_a = [tid for tid, lang in eff_audio.items() if lang in {f.lang for f in ext_audio}]
    drop_s = [tid for tid, key in eff_subs.items() if key in {(f.lang, f.forced) for f in ext_subs}]
    if drop_a:
        cmd += ["--no-audio"] if len(drop_a) == len(v_audio) else ["--audio-tracks", "!" + ",".join(map(str, drop_a))]
    if drop_s:
        cmd += ["--no-subtitles"] if len(drop_s) == len(v_subs) else ["--subtitle-tracks", "!" + ",".join(map(str, drop_s))]
    if any(f.default for f in ext_audio):
        for tid in eff_audio:
            if tid not in drop_a:
                cmd += ["--default-track-flag", f"{tid}:no"]
    cmd.append(str(video))

    # Ayrı ses dosyalarının senkronu. Varsayılan: KAPALI — dosyalar MKVToolNix ile olduğu gibi birleştirilir.
    # Sadece tarifte elle "| gecikme: ms" yazılmışsa o uygulanır. GFLIX_AUTO_SYNC=1 ile otomatik ölçüm açılabilir (gflix/sync.py).
    sync_ms: dict[int, int] = {}
    v_start = v_dur = None
    auto_sync = os.environ.get("GFLIX_AUTO_SYNC", "0") == "1"
    if not auto_sync:
        detail("Otomatik senkron kapalı: dosyalar olduğu gibi birleştiriliyor")
        for f in ext_audio:
            if f.delay_ms:
                sync_ms[id(f)] = f.delay_ms
                notes.append(f"{LANG_LABEL.get(f.lang, f.lang)} ses: elle {f.delay_ms:+d} ms gecikme uygulandı")
    for f in (ext_audio if auto_sync else []):
        label = f"{LANG_LABEL.get(f.lang, f.lang)} ses"
        try:
            if v_start is None:
                v_start, v_dur = av_sync.stream_start(video, "video"), av_sync.duration(video, "video")
            pl = av_sync.plan(video, files[id(f)], label, f.delay_ms, v_start, v_dur)
            a_start, a_dur = av_sync.stream_start(Path(files[id(f)]), "audio"), av_sync.duration(Path(files[id(f)]), "audio")
            detail(f"Senkron ölçümü — {label}: video başlangıç {v_start:.3f} sn, süre {v_dur:.3f} sn | "
                   f"ses başlangıç {a_start:.3f} sn, süre {a_dur:.3f} sn | oran {a_dur / v_dur if v_dur else 0:.5f} | "
                   f"uygulanan kaydırma {pl.offset_ms:+d} ms" + (f", hız düzeltme atempo={pl.tempo:.5f}" if pl.tempo else ", hız düzeltme yok"))
            if pl.tempo:
                fixed = out.parent / (Path(files[id(f)]).stem + ".hiz.m4a")
                av_sync.retime(files[id(f)], pl.tempo, fixed)
                files[id(f)] = fixed
            sync_ms[id(f)] = pl.offset_ms
            notes.extend(pl.notes)
        except Exception as e:  # noqa: BLE001  ölçülemezse olduğu gibi birleştir
            sync_ms[id(f)] = f.delay_ms
            notes.append(f"{label}: senkron ölçülemedi ({str(e)[:120]})")

    for f in ext_audio + ext_subs:
        if id(f) in sidecar:
            continue
        path = files[id(f)]
        ident = mkv_identify(path)
        want = "audio" if f.role == "audio" else "subtitles"
        cand = [t for t in ident.get("tracks", []) if t["type"] == want]
        if not cand:
            raise RuntimeError(f"{f.line}. satırdaki dosyada {'ses' if f.role == 'audio' else 'altyazı'} bulunamadı ({path.name})")
        tid = cand[0]["id"]
        opts = ["--no-video", "--no-attachments", "--no-chapters", "--no-global-tags"]
        opts += ["--no-subtitles", "--audio-tracks", str(tid)] if f.role == "audio" else ["--no-audio", "--subtitle-tracks", str(tid)]
        opts += ["--language", f"{tid}:{f.lang}"]
        if f.role == "audio" and sync_ms.get(id(f)):
            opts += ["--sync", f"{tid}:{sync_ms[id(f)]}"]
        if f.name:
            opts += ["--track-name", f"{tid}:{f.name}"]
        opts += ["--default-track-flag", f"{tid}:{'yes' if f.default else 'no'}"]
        if f.role == "subtitle":
            if f.forced:
                opts += ["--forced-display-flag", f"{tid}:yes"]
            if path.suffix.lower() in (".srt", ".ass", ".ssa") and not _is_utf8(path):
                opts += ["--sub-charset", f"{tid}:cp1254"]  # Türkçe altyazılar çoğunlukla Windows-1254
        cmd += opts + [str(path)]

    import shlex
    detail("── MKVToolNix komutu ──")
    detail(" ".join(shlex.quote(c) for c in cmd))
    t0 = time.time()
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    tail = []
    for line in p.stdout:
        m = re.search(r"#GUI#progress\s+(\d+)%", line)
        if m:
            on_progress(int(m.group(1)) / 100)
        else:
            tail.append(line.strip())
            del tail[:-20]
            if line.strip():
                detail("mkvmerge: " + line.strip().replace("#GUI#", ""))
    p.wait()
    detail(f"mkvmerge çıkış kodu {p.returncode} ({'uyarılarla tamam' if p.returncode == 1 else 'tamam' if p.returncode == 0 else 'HATA'}), "
           f"süre {human_time(time.time() - t0)}")
    if out.exists():
        detail("── Çıkan MKV ──")
        detail(describe_media(out))
    if p.returncode == 2 or not out.exists():
        raise RuntimeError("MKVToolNix birleştiremedi:\n" + "\n".join(t for t in tail if t and not t.startswith("#GUI#"))[-1500:])


# ------------------------------------------------------------------ katalog

# Siteden indirme sırası: aynı anda yalnız bir iş siteye istek atar (hazırlık + boyut + indirme). İndirmesi biten iş
# sırayı bırakır; o GitHub'a yüklerken sıradaki iş siteden inmeye başlar. Siteye giden yük tek işle aynı kalır.
class _SiteSlots:
    """Siteden aynı anda en fazla [limit] iş indirir (Stüdyo'dan ayarlanır; varsayılan 1)."""

    def __init__(self, limit: int = 1):
        self.limit, self.used = limit, 0
        self.cv = threading.Condition()

    def acquire(self, timeout: float | None = None) -> bool:
        end = None if timeout is None else time.time() + timeout
        with self.cv:
            while self.used >= self.limit:
                left = None if end is None else end - time.time()
                if left is not None and left <= 0:
                    return False
                self.cv.wait(left)
            self.used += 1
            return True

    def release(self) -> None:
        with self.cv:
            self.used = max(0, self.used - 1)
            self.cv.notify_all()

    def locked(self) -> bool:
        with self.cv:
            return self.used >= self.limit

    def set_limit(self, n: int) -> None:
        with self.cv:
            self.limit = max(1, n)
            self.cv.notify_all()


SITE_SLOT = _SiteSlots()


# Biten işlerin süre kayıtları (etiket → kayıt); gflix_worker alıp kalıcı istatistiğe ekler
FINISHED: dict[str, dict] = {}

# Telefondan "Tüm işleri iptal et": işçi bu etiketleri buraya ekler; iş GitHub'a sormadan hemen durur
KILLED: set[str] = set()
ACTIVE_JOBS: dict[str, "LinkJob"] = {}     # şu an indirme/yükleme aşamasındaki işler (etiket → iş)


def kill(tag: str) -> None:
    """İşi bu bilgisayarda hemen iptal et (GitHub'daki 'cancel' varlığını beklemeden)."""
    KILLED.add(tag)
    job = ACTIVE_JOBS.get(tag)
    if job is not None:
        job.cancelled = True
        for r in job.runs:
            for d in getattr(r, "downloads", []) or []:
                try:
                    job.dl.cancel(d)
                except Exception:  # noqa: BLE001
                    pass


def site_busy() -> bool:
    return SITE_SLOT.locked()


class SiteTurn:
    """Bir işin site sırası: acquire() sıra gelene kadar bekler (iptal edilirse Cancelled), release() iki kez çağrılsa da güvenli."""

    def __init__(self, status: "Status", cancelled):
        self.status, self.cancelled, self.held = status, cancelled, False
        self._lock = threading.Lock()

    def acquire(self) -> None:
        if self.held:
            return
        shown, last_check = False, time.time()
        while not SITE_SLOT.acquire(timeout=2):
            if not shown:
                shown = True
                self.status.log("⏳ Sırada: önceki iş siteden indiriyor, bitince başlanacak")
                with self.status.lock:
                    self.status.data["stage"] = "Sırada — önceki işin indirmesi bitince başlar"
                self.status.push(force=True)
            if time.time() - last_check > 10:
                last_check = time.time()
                if self.cancelled():
                    raise Cancelled()
        with self._lock:
            self.held = True
        try:
            self.status.mark("site")
        except Exception:  # noqa: BLE001
            pass

    def release(self) -> None:
        with self._lock:
            if self.held:
                self.held = False
                SITE_SLOT.release()


_CATALOG_LOCK = threading.Lock()   # aynı anda birden çok iş: katalog gönderimi sırayla (git reset/commit çakışmasın)


def commit_catalog(message: str, status: Status) -> None:
    with _CATALOG_LOCK:
        _commit_catalog(message, status)


def _commit_catalog(message: str, status: Status) -> None:
    # Yeni düzen: katalog (yapım adları) GitHub'a gönderilmez; bilgisayarda gflix-yayin üretir.
    status.log("  ℹ️ Katalog GitHub'a gönderilmedi (yeni düzende katalog bilgisayarda)")
    return
    if catalog_on_github():
        status.log("  ✓ Kayıt GitHub'a yazıldı; katalog GitHub'da TMDB'den güncellenecek (1-2 dk)")
        return
    branch = os.environ.get("GFLIX_BRANCH", "main")
    key = os.environ.get("TMDB_API_KEY", "").strip()
    for attempt in range(6):
        try:
            subprocess.run(["git", "fetch", "-q", "origin", branch], cwd=ROOT, check=True)
            subprocess.run(["git", "reset", "-q", "--hard", f"origin/{branch}"], cwd=ROOT, check=True)
            catalog_mod.build(ROOT, key)
            subprocess.run(["git", "add", "catalog"], cwd=ROOT, check=True)
            if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0:
                return
            subprocess.run(["git", "commit", "-q", "-m", message], cwd=ROOT, check=True)
            if subprocess.run(["git", "push", "-q", "origin", f"HEAD:{branch}"], cwd=ROOT).returncode == 0:
                status.log("  ✓ Katalog güncellendi")
                return
        except Exception as e:  # noqa: BLE001
            status.log(f"  katalog denemesi {attempt + 1}: {e}")
        time.sleep(4 + attempt * 3)
    status.log("  ⚠ Katalog güncellenemedi; bir sonraki işte güncellenecek.")


# ------------------------------------------------------------------ ana akış

class ItemReporter(Reporter):
    def __init__(self, run: ItemRun, status: Status, base: float):
        self.run, self.status, self.base = run, status, base
        self.done: dict[str, float] = {}

    def log(self, msg: str) -> None:
        self.status.log("  " + msg)

    def stage(self, name: str, fraction: float, text: str = "") -> None:
        self.done[name] = fraction
        frac = sum(WEIGHTS[k] * self.done.get(k, 0) for k in WEIGHTS)
        self.run.progress = self.base + (1 - self.base) * frac
        if text:
            self.run.text = text
        self.status.push()


def safe_name(s: str) -> str:
    return re.sub(r'[\\/:*?"<>|]+', " ", s).strip() or "video"


class LinkJob:
    def __init__(self, gh: GitHub, rel: dict, recipe: Recipe, workdir: Path, status: Status | None = None):
        self.gh, self.rel, self.recipe, self.workdir = gh, rel, recipe, workdir
        self.status = status or Status(gh, rel)
        self.dl = Downloader(self.status.log, asset_fetcher=self.fetch_asset)
        self.runs = [ItemRun(it, i) for i, it in enumerate(recipe.items)]
        self.trailer: TrailerGroup | None = None
        self.trailer_state = {"state": "waiting", "text": "Sırada", "files": []}
        self.cancelled = False
        self.title = recipe.title
        self.year = recipe.year
        self.item_id = ""
        self.first_ok = False

    # ---- yardımcılar
    def sync_status(self, stage: str | None = None, force: bool = False):
        with self.status.lock:
            if stage:
                self.status.data["stage"] = stage
            self.status.data["items"] = [r.as_dict() for r in self.runs]
            if self.trailer or self.recipe.trailer:
                t = dict(self.trailer_state)
                t["files"] = [self.trailer.as_dict()] if self.trailer else []
                self.status.data["trailer"] = t
        self.status.push(force)

    def check_cancel(self) -> bool:
        if self.rel.get("tag_name") in KILLED:
            self.cancelled = True
            return True
        try:
            names = {a["name"] for a in self.gh.release_assets(self.rel["id"])}
            if "cancel" in names:
                self.cancelled = True
        except GitHubError as e:
            if e.status == 404:     # iş telefondan "Kaldır"ıldı: kaydı yok, arka planda boşuna sürmesin
                self.status.log("🛑 İşin kaydı silinmiş (telefonda Kaldır): iş durduruldu")
                self.cancelled = True
        except Exception:  # noqa: BLE001
            pass
        return self.cancelled

    def fetch_asset(self, name: str, dest: Path, progress, d: Download) -> None:
        """Telefonun parça parça yüklediği dosyayı (NAME.p000, NAME.p001 …) birleştirir."""
        parts = sorted((a for a in self.gh.release_assets(self.rel["id"]) if a["name"].startswith(name + ".p")), key=lambda a: a["name"])
        if not parts:
            raise RuntimeError(f"Telefondan aktarılan dosya bulunamadı: {name}")
        d.total = sum(a["size"] for a in parts)
        with open(dest, "wb") as out:
            for a in parts:
                r = self.gh.s.get(a["url"], headers={"Accept": "application/octet-stream"}, allow_redirects=False, timeout=(30, 600))
                if r.status_code in (301, 302, 303, 307, 308):
                    r = requests.get(r.headers["Location"], stream=True, timeout=(30, 600))
                r.raise_for_status()
                for chunk in r.iter_content(4 * 1024 * 1024):
                    out.write(chunk)
                    progress(len(chunk))

    def delete_phone_assets(self, item: Item) -> None:
        prefixes = [f.url.split(":", 1)[1] + ".p" for f in item.files if f.url.lower().startswith("asset:")]
        if not prefixes:
            return
        try:
            for a in self.gh.release_assets(self.rel["id"]):
                if any(a["name"].startswith(p) for p in prefixes):
                    self.gh.delete_asset(a["id"])
        except Exception as e:  # noqa: BLE001
            self.status.log(f"  aktarılan dosyalar silinemedi: {e}")

    def resolve(self):
        r = self.recipe
        key = os.environ.get("TMDB_API_KEY", "").strip()
        if r.tmdb_id and key:
            kind = "tv" if r.kind == "series" else "movie"
            d = TMDB(key).get(f"/{kind}/{r.tmdb_id}", language="tr-TR")
            if not d:
                raise RuntimeError(f"TMDB'de bulunamadı: {kind}/{r.tmdb_id}")
            self.title = self.title or d.get("title") or d.get("name") or d.get("original_title") or d.get("original_name")
            date = d.get("release_date") or d.get("first_air_date") or ""
            self.year = self.year or (int(date[:4]) if date[:4].isdigit() else None)
        if not self.title:
            raise RuntimeError("Ad bulunamadı: TMDB_API_KEY secret'ı yok. Tarife 'ad: Film Adı' satırı ekle.")
        cat = ROOT / "catalog" / "catalog.json"
        existing = None
        if cat.exists() and r.tmdb_id:
            try:
                for t in json.loads(cat.read_text(encoding="utf-8")).get("titles", []):
                    if t.get("tmdbId") == r.tmdb_id and (t.get("type") == "series") == (r.kind == "series"):
                        existing = t["id"]
                        break
            except Exception:  # noqa: BLE001
                pass
        self.item_id = existing or item_id_for("episode" if r.kind == "series" else "movie", self.title)
        with self.status.lock:
            self.status.data.update({"title": self.title + (f" ({self.year})" if self.year else ""), "kind": r.kind})
        self.status.log(f"▶ {self.title} ({self.year or '?'}) — {r.kind}, kimlik: {self.item_id}, {len(r.items)} öğe")
        if r.release_at:
            self.apply_release(r.release_at)

    def apply_release(self, at: int):
        """'yayın:' satırı: dosyalar yüklenmeden önce kayda yazılır; yükleme bitse de o saate kadar gizli kalır."""
        path = f"{ITEMS_DIR}/{self.item_id}.json"
        for attempt in range(3):
            item, sha = self.gh.get_json(path)
            now = int(time.time())
            item = item or {"id": self.item_id, "type": "series" if self.recipe.kind == "series" else "movie",
                            "title": self.title, "added": now}
            if self.recipe.tmdb_id:
                item.setdefault("tmdbId", self.recipe.tmdb_id)
            if self.year:
                item.setdefault("year", self.year)
            item["releaseAt"] = at
            if (item.get("added") or 0) < at:
                item["added"] = at
            try:
                self.gh.put_json(path, item, f"Yayın zamanı: {self.title}", sha)
                self.status.log(f"🗓️ Yayın zamanı: {time.strftime('%d.%m.%Y %H:%M', time.localtime(at))} — o zamana kadar gizli")
                return
            except Exception as e:  # noqa: BLE001
                if attempt == 2:
                    raise
                time.sleep(2)
                self.status.log(f"  yayın zamanı tekrar yazılıyor: {e}")

    def wait_approval(self) -> bool:
        """
        İndirmeden önce: her bölümün tahmini boyutu, toplam, işlerken gereken en fazla boş alan ve bilgisayardaki boş alan
        telefona gösterilir; "Devam et" (approve) gelene kadar beklenir. Telefondan aktarılan dosyalarda sorulmaz.
        Kapatmak: GFLIX_APPROVAL=0.
        """
        if os.environ.get("GFLIX_APPROVAL", "1") == "0":
            return True
        files = [f for r in self.runs for f in r.item.files]
        if not files or all(f.url.lower().startswith(("asset:", "file://")) for f in files if f.role != "subtitle"):
            return True
        from gflix import estimate as est_mod
        self.sync_status("İndirilecek boyut hesaplanıyor", force=True)
        items, total, peak = [], 0, 0
        cache: dict[str, int] = {}
        for r in self.runs:
            size = 0
            for f in r.item.files:
                if f.role == "subtitle":
                    continue
                key = f.url
                if key not in cache:
                    if self.check_cancel():
                        self.status.log("🛑 İptal edildi")
                        return False
                    cache[key] = est_mod.file_size(f.url, f.headers, f.is_hls)
                size += cache[key]
            videos = max(1, len(r.item.videos)) if not self.recipe.separate else max(1, sum(1 for f in r.item.files if f.role == "audio"))
            items.append({"label": r.item.label, "bytes": size})
            total += size
            # işlerken: indirilen + MKV çıktıları (dil başına video ise görüntü her dil için ayrı dosya)
            peak = max(peak, int(size * (1 + videos) * 1.1))
        free = shutil.disk_usage(self.workdir).free
        unknown = sum(1 for it in items if it["bytes"] == 0)
        with self.status.lock:
            self.status.data["approval"] = {"total": total, "peak": peak, "free": free, "items": items, "unknown": unknown}
        msg = f"⏸ Onay bekleniyor — toplam ~{human(total)} inecek ({len(items)} öğe, sırayla)"
        self.status.log(f"📦 Tahmini toplam {human(total)} · işlerken en fazla ~{human(peak)} boş alan gerekir · bilgisayarda {human(free)} boş"
                        + (f" · {unknown} öğenin boyutu bilinemedi" if unknown else ""))
        if free < peak:
            self.status.log(f"  ⚠ Boş alan yetmeyebilir: {human(peak - free)} daha gerekli")
        elif self.recipe.auto_approve:
            self.status.log("▶ Yer yetiyor, onay beklenmeden başlanıyor")
            with self.status.lock:
                self.status.data.pop("approval", None)
            return True
        self.sync_status(msg, force=True)
        # Onay beklerken siteyi meşgul tutma: sıradaki iş bu arada inebilir; onay gelince sıra yeniden alınır
        t = getattr(self, "turn", None)
        if t is not None:
            t.release()
        t0 = time.time()
        try:
            return self._wait_user_approval(t, t0, msg)
        finally:
            self.status.add_time("approval", time.time() - t0)

    def _wait_user_approval(self, t, t0: float, msg: str) -> bool:
        while time.time() - t0 < 24 * 3600:
            try:
                names = {a["name"] for a in self.gh.release_assets(self.rel["id"])}
            except Exception:  # noqa: BLE001
                names = set()
            if "cancel" in names:
                self.status.log("🛑 Onay verilmedi, iptal edildi")
                with self.status.lock:
                    self.status.data.pop("approval", None)
                return False
            if "approve" in names:
                self.status.log("▶ Onaylandı, indirme başlıyor")
                with self.status.lock:
                    self.status.data.pop("approval", None)
                if t is not None:
                    t.acquire()
                return True
            self.sync_status(msg, force=True)
            time.sleep(10)
        self.status.log("⌛ 24 saat onay gelmedi, iş kapatıldı")
        return False

    def estimate(self, run: ItemRun) -> int:
        return sum(probe_size(f.url) or UNKNOWN_SIZE[f.role] for f in run.item.files)

    def start_downloads(self, run: ItemRun):
        run.state, run.text = "downloading", "İndiriliyor"
        base = self.workdir / f"item{run.index:03d}"
        stem = safe_name(self.title) + ("" if run.item.season is None else f" - {run.item.label}")
        for k, f in enumerate(run.item.files):
            suffix = {"video": "", "audio": f".ses.{f.lang}", "subtitle": f".altyazi.{f.lang}" + (".zorunlu" if f.forced else "")}[f.role]
            label = {"video": "Video", "audio": f"Ses ({f.lang})", "subtitle": f"Altyazı ({f.lang}{', zorunlu' if f.forced else ''})"}[f.role]
            d = Download(url=f.url, dest_dir=base, name=f"{stem}{suffix}.{k}", label=label, headers=dict(f.headers),
                         optional=f.role == "subtitle", hls=f.is_hls and f.role != "subtitle")
            if f.role == "video":
                d.ytdlp_format = "bv*+ba/b"     # YouTube vb.: sınırsız en iyi kalite (görüntü + ses, MKV'de birleşik)
            run.downloads.append(d)
            try:
                self.dl.add(d)
            except Exception as e:  # noqa: BLE001
                d.state, d.error = "error", str(e)

    def _owned(self, p: Path) -> bool:
        """Silinebilir mi: bu işin indirdiği ya da telefondan gelen dosya (iş klasörünün içinde). Bilgisayardaki asıl
        dosyalara (sürüklenenler) dokunulmaz."""
        roots = [self.workdir.resolve()]
        if os.environ.get("GFLIX_WORKDIR"):
            roots.append(Path(os.path.expanduser(os.environ["GFLIX_WORKDIR"])).resolve())
        try:
            rp = p.resolve()
            return any(rp.is_relative_to(r) for r in roots)
        except OSError:
            return False

    def media_tag(self, index: int) -> str:
        """Bir film/bölümün dosyalarının kalıcı olarak durduğu Release (iş silinse de kalır)."""
        return "media-" + self.rel["tag_name"].split("-", 1)[-1] + f"-{index}"

    def verify_parts(self, rel: dict, path: Path, base: str, sizes: list[int]) -> list[int]:
        """GitHub'daki parçaları tek tek denetler (durum, boyut, varsa sha256). Hatalı parça sıralarını döner."""
        assets = {a["name"]: a for a in self.gh.release_assets(rel["id"])}
        bad, off = [], 0
        for i, n in enumerate(sizes):
            a = assets.get(f"{base}.p{i:03d}")
            if not a or a.get("state") != "uploaded" or a.get("size") != n:
                bad.append(i)
            elif a.get("digest") and a["digest"] != "sha256:" + sha256_slice(path, off, n):
                bad.append(i)
            off += n
        return bad

    def upload_parts(self, rel: dict, path: Path, base: str, assets: dict, run: ItemRun | None, label: str,
                     on_text=None) -> tuple[list[str], list[int]]:
        """Dosyayı 512 MB'lık parçalar halinde (4 bağlantı) yükler, sonra GitHub'dan doğrular; hatalı parçayı yeniden yükler."""
        size = path.stat().st_size
        count = max(1, -(-size // DIRECT_PART))
        sent = [0]
        lock = threading.Lock()
        t0 = time.time()

        def prog(n: int):
            with lock:
                sent[0] += n
            speed = sent[0] / max(time.time() - t0, 1)
            text = f"{label} yükleniyor {human(sent[0])} / {human(size)} · {human(speed)}/sn"
            if run is not None:
                run.text = text
                run.progress = max(run.progress, 0.55 + 0.4 * min(sent[0] / max(size, 1), 1.0))
            if on_text:
                on_text(text)
            self.sync_status()

        def one(i: int) -> tuple[str, int]:
            off = i * DIRECT_PART
            ln = min(DIRECT_PART, size - off)
            url = self.gh.upload_asset(rel, path, f"{base}.p{i:03d}", "application/octet-stream", existing=assets,
                                       progress=prog, cancel=lambda: self.cancelled, offset=off, length=ln)
            return url, ln

        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(max_workers=4) as ex:
            res = list(ex.map(one, range(count)))
        sizes = [n for _, n in res]
        urls = [u for u, _ in res]
        for attempt in range(3):
            if run is not None:
                run.text = f"{label} GitHub'da doğrulanıyor"
                self.sync_status()
            bad = self.verify_parts(rel, path, base, sizes)
            self.status.detail(f"Doğrulama ({label}, {attempt + 1}. tur): {len(sizes)} parça, hatalı: {bad or 'yok'}; "
                               f"yükleme hızı ~{human(size / max(time.time() - t0, 1))}/sn, süre {human_time(time.time() - t0)}")
            if not bad:
                self.status.log(f"  ✓ {label}: {count} parça yüklendi ve doğrulandı ({human(size)})")
                return urls, sizes
            self.status.log(f"  ⚠ {label}: {len(bad)} parça hatalı, yeniden yükleniyor ({attempt + 1}/3)")
            fresh = {a["name"]: a for a in self.gh.release_assets(rel["id"])}
            for i in bad:
                a = fresh.get(f"{base}.p{i:03d}")
                if a:
                    self.gh.delete_asset(a["id"])
                urls[i], _ = one(i)
        raise RuntimeError(f"{label}: yükleme doğrulanamadı; dosyalar silinmedi, iş tekrar denenebilir")

    def replace_old_release(self, kind: str, season, episode, new_tag: str) -> str | None:
        """Aynı film/bölüm daha önce yüklenmişse eski dosyaların Release etiketi."""
        old, _ = self.gh.get_json(f"{ITEMS_DIR}/{self.item_id}.json")
        if not old:
            return None
        if kind == "movie":
            st = old.get("stream") or {}
        else:
            st = next(((ep.get("stream") or {}) for ep in old.get("episodes", [])
                       if ep.get("season") == season and ep.get("episode") == episode), {})
        t = st.get("release")
        self._old_duration = float(st.get("duration") or 0)
        return t if t and t != new_tag else None

    def delete_old(self, old_tag: str | None, new_duration: float | None) -> None:
        """Aynı film/bölüm yeniden yüklendiyse eski dosyaları sil — ama süreler belirgin farklıysa SİLME
        (yanlış eşleşmede başka bir filmin dosyaları silinmesin; ör. 119 dk'lık film 161 dk'lık filmle 'değiştirildi')."""
        if not old_tag:
            return
        od, nd = getattr(self, "_old_duration", 0.0) or 0.0, float(new_duration or 0)
        if od and nd and abs(od - nd) > max(120.0, 0.1 * max(od, nd)):
            self.status.log(f"  ⚠ eski dosyalar SİLİNMEDİ: süreler çok farklı (eski {od / 60:.0f} dk, yeni {nd / 60:.0f} dk) — "
                            f"yanlış eşleşme olabilir; eski dosyalar '{old_tag}' Release'inde duruyor")
            return
        self.status.log(f"  eski dosyalar siliniyor: {old_tag}")
        try:
            self.gh.delete_release(old_tag)
        except Exception as e:  # noqa: BLE001
            self.status.log(f"  eski dosyalar silinemedi: {e}")

    def ensure_playable_audio(self, out: Path, run: ItemRun) -> None:
        """Telefonların çoğunun çözemediği sesleri (AC3, E-AC3, DTS, TrueHD…) AAC'ye çevirir; video ve diğerleri kopyalanır."""
        info = mkv_identify(out)
        audio = [t for t in info.get("tracks", []) if t["type"] == "audio"]
        bad = [(n, t) for n, t in enumerate(audio) if not str(t.get("properties", {}).get("codec_id", "")).startswith(PLAYABLE_AUDIO)]
        if not bad:
            return
        names = ", ".join(f"{t.get('codec')} ({_lang(t.get('properties', {}))})" for _, t in bad)
        self.status.log(f"  {names} telefonlarda çalmayabilir → AAC'ye çevriliyor")
        run.text = "Ses AAC'ye çevriliyor (" + names + ")"
        self.sync_status()
        from gflix.media import FFMPEG
        fixed = out.with_suffix(".aac.mkv")
        cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(out), "-map", "0", "-c", "copy"]
        for n, t in bad:
            ch = int(t.get("properties", {}).get("audio_channels") or 2)
            cmd += [f"-c:a:{n}", "aac", f"-b:a:{n}", "192k" if ch <= 2 else "384k"]
        cmd.append(str(fixed))
        import shlex
        self.status.detail("── Ses AAC'ye çevriliyor ──\n" + " ".join(shlex.quote(c) for c in cmd))
        t0 = time.time()
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
        self.status.detail(f"ffmpeg çıkış kodu {r.returncode}, süre {human_time(time.time() - t0)}" + (f"\n{r.stderr[-1500:]}" if r.stderr.strip() else ""))
        if r.returncode != 0 or not fixed.exists():
            fixed.unlink(missing_ok=True)
            w = f"{run.item.label}: ses AAC'ye çevrilemedi, olduğu gibi yüklenecek ({r.stderr[-300:]})"
            self.status.log("  ⚠ " + w)
            with self.status.lock:
                self.status.data.setdefault("warnings", []).append(w)
            return
        fixed.replace(out)

    def deliver_mkv(self, run: ItemRun, out: Path, sidecars: list[tuple[FileSpec, Path]]) -> None:
        """MKV'yi olduğu gibi yükler (doğrudan oynatılır), altyazıları ayrı VTT olarak ekler, kayda yazar."""
        it = run.item
        kind = "episode" if it.season is not None else "movie"
        tag = self.media_tag(run.index)
        rel = self.gh.ensure_release(tag, f"G-Flix · {self.title} {it.label}", "G-Flix içerik dosyaları (bilgisayardan, MKV).")
        # Yarım kalmış eski bir denemenin dosyaları: MKV her birleştirmede biraz farklı olur, baştan yükle
        for a in self.gh.release_assets(rel["id"]):
            self.gh.delete_asset(a["id"])
        info = mkv_identify(out)
        langs = []
        for t in info.get("tracks", []):
            if t["type"] == "audio":
                lang = _lang(t.get("properties", {}))
                if lang != "und" and lang not in langs:
                    langs.append(lang)
        dur_ns = (info.get("container", {}).get("properties", {}) or {}).get("duration")
        urls, sizes = self.upload_parts(rel, out, "v", {}, run, "MKV")
        subs = []
        for spec, vpath in sidecars:
            name = f"sub.{spec.lang}{'.zorunlu' if spec.forced else ''}.{len(subs)}.vtt"
            url = self.gh.upload_asset(rel, vpath, name, "text/vtt")
            subs.append({"lang": spec.lang, "label": spec.name or (LANG_LABEL.get(spec.lang, spec.lang) + (" (Zorunlu)" if spec.forced else "")),
                         "url": url, "forced": spec.forced})
        if subs:
            check = {a["name"]: a for a in self.gh.release_assets(rel["id"])}
            missing = [s["url"] for s in subs if not any(a["url"] == s["url"] and a.get("state") == "uploaded" for a in check.values())]
            if missing:
                raise RuntimeError("altyazı yüklemesi doğrulanamadı")
        stream = {"master": "", "audio": langs, "subtitles": subs, "release": tag,
                  "direct": {"video": {"parts": urls, "sizes": sizes}, "audio": [], "videoAudio": []}}
        if dur_ns:
            stream["duration"] = round(dur_ns / 1e9, 1)
        run.text = "Kataloğa ekleniyor"
        self.sync_status()
        old_tag = self.replace_old_release(kind, it.season, it.episode, tag)
        job = UploadJob(kind=kind, input="", title=self.title, item_id=self.item_id, tmdb=self.recipe.tmdb_id, year=self.year,
                        season=it.season, episode=it.episode)
        save_stream(self.gh, job, stream)
        self.delete_old(old_tag, stream.get("duration"))

    def deliver_versions(self, run: ItemRun, files: dict, ok_specs: list[FileSpec], title: str) -> None:
        """Dil başına ayrı video (Türkçe dublajlı + İngilizce): birleştirme yok. Her video kendi içinde MKV'ye aktarılır
        (kayıpsız, birkaç saniye; sarma dizini ve ses dili için), olduğu gibi yüklenir. Oynatıcı ses menüsünde dil
        değişince aynı saniyeden öbür videoya geçer; altyazılar ortak."""
        import shlex
        it = run.item
        kind = "episode" if it.season is not None else "movie"
        base = self.workdir / f"item{run.index:03d}"
        base.mkdir(parents=True, exist_ok=True)
        vids = [s for s in ok_specs if s.role == "video"]
        subs_specs = [s for s in ok_specs if s.role == "subtitle"]
        # Sessiz video yüklenmesin (1DM Experimental'ın sadece görüntü linki verilmişse olur); eski yükleme silinmeden önce bakılır
        for v in vids:
            if not any(t["type"] == "audio" for t in mkv_identify(Path(files[id(v)])).get("tracks", [])):
                raise RuntimeError(f"{it.label}: {LANG_LABEL.get(v.lang, v.lang)} videoda ses yok — sessiz video yüklenmedi. "
                                   "1DM Experimental'da görüntü ve ses ayrı link: satırı 'video tr: <görüntü linki> | ses: <ses linki>' "
                                   "diye yaz, ya da ana m3u8 linkini / izleme sayfasının linkini ver.")
        tag = self.media_tag(run.index)
        rel = self.gh.ensure_release(tag, f"G-Flix · {self.title} {it.label}", "G-Flix içerik dosyaları (dil başına ayrı video).")
        for a in self.gh.release_assets(rel["id"]):
            self.gh.delete_asset(a["id"])
        self.status.detail(f"═══ {it.label}: dil başına ayrı video ({', '.join(v.lang for v in vids)}) — birleştirme yok ═══")
        outs, versions, duration = [], [], None
        for n, v in enumerate(vids):
            src = Path(files[id(v)])
            info = mkv_identify(src)
            self.status.detail(f"VIDEO ({v.lang}): " + describe_media(src, info))
            out = base / f"{safe_name(title)}.{v.lang}.mkv"
            cmd = ["mkvmerge", "--gui-mode", "-o", str(out), "--title", f"{title} ({LANG_LABEL.get(v.lang, v.lang)})"]
            for t in info.get("tracks", []):
                if t["type"] == "audio" and _lang(t.get("properties", {})) == "und":
                    cmd += ["--language", f"{t['id']}:{v.lang}"]   # etiketsiz sese videonun dili yazılır
            cmd.append(str(src))
            run.text, run.progress = f"{LANG_LABEL.get(v.lang, v.lang)} video hazırlanıyor (MKV'ye aktarma)", 0.45 + 0.05 * n
            self.sync_status()
            self.status.detail("mkvmerge (sadece kap değişimi): " + " ".join(shlex.quote(c) for c in cmd))
            t0 = time.time()
            p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
            for line in p.stdout.splitlines():
                if line.strip() and "#GUI#progress" not in line:
                    self.status.detail("mkvmerge: " + line.strip().replace("#GUI#", ""))
            if p.returncode == 2 or not out.exists():
                raise RuntimeError(f"{v.lang} videosu MKV'ye aktarılamadı:\n{p.stdout[-800:]}")
            self.status.detail(f"mkvmerge çıkış kodu {p.returncode}, süre {human_time(time.time() - t0)}")
            self.ensure_playable_audio(out, run)
            oinfo = mkv_identify(out)
            self.status.detail("Çıkan: " + describe_media(out, oinfo))
            dur = ((oinfo.get("container", {}).get("properties", {}) or {}).get("duration"))
            duration = duration or (round(dur / 1e9, 1) if dur else None)
            urls, sizes = self.upload_parts(rel, out, f"v_{v.lang}", {}, run, f"{LANG_LABEL.get(v.lang, v.lang)} video")
            versions.append({"lang": v.lang, "label": v.name or None, "video": {"parts": urls, "sizes": sizes}})
            outs.append(out)
        subs = []
        for spec in subs_specs:
            try:
                vtt = to_webvtt(Path(files[id(spec)]).read_bytes())
            except Exception as e:  # noqa: BLE001
                w = f"{it.label}: {LANG_LABEL.get(spec.lang, spec.lang)} altyazı eklenemedi ({e})"
                self.status.log("  ⚠ " + w)
                with self.status.lock:
                    self.status.data.setdefault("warnings", []).append(w)
                continue
            vpath = base / f"sub{len(subs)}.{spec.lang}.vtt"
            vpath.write_text(vtt, encoding="utf-8")
            url = self.gh.upload_asset(rel, vpath, f"sub.{spec.lang}{'.zorunlu' if spec.forced else ''}.{len(subs)}.vtt", "text/vtt")
            subs.append({"lang": spec.lang, "label": spec.name or (LANG_LABEL.get(spec.lang, spec.lang) + (" (Zorunlu)" if spec.forced else "")),
                         "url": url, "forced": spec.forced})
        stream = {"master": "", "audio": [v["lang"] for v in versions], "subtitles": subs, "release": tag, "versions": versions}
        if duration:
            stream["duration"] = duration
        run.text = "Kataloğa ekleniyor"
        self.sync_status()
        old_tag = self.replace_old_release(kind, it.season, it.episode, tag)
        save_stream(self.gh, UploadJob(kind=kind, input="", title=self.title, item_id=self.item_id, tmdb=self.recipe.tmdb_id,
                                       year=self.year, season=it.season, episode=it.episode), stream)
        self.delete_old(old_tag, stream.get("duration"))
        # Hepsi GitHub'da doğrulandı: kaynakları ve MKV'leri sil (bilgisayardaki asıl dosyalar sadece "| sil" ile)
        keep = {str(files[id(s)]) for s in ok_specs if s.url.lower().startswith("file://") and not s.delete}
        for p_ in {str(x) for x in files.values()} | {str(o) for o in outs}:
            if p_ not in keep:
                Path(p_).unlink(missing_ok=True)
        self.status.log(f"  🧹 {it.label}: kaynak dosyalar ve MKV'ler silindi")

    def premux_hls(self, run: ItemRun, item: Item, files: dict, hls_ids: set) -> None:
        """
        Aynı HLS yayınından gelen görüntü + dil sesleri (1DM'deki "Experimental"): orijinal zaman damgaları korunarak
        (-copyts) tek MKV'de birleştirilir; böylece sesler kaymaz. Sonra normal akış bunu "sesli video" olarak işler.
        """
        v = item.video
        hls_audio = [f for f in item.files if f.role == "audio" and id(f) in hls_ids and id(f) in files]
        if len(item.videos) > 1:
            # Dil başına: her dilin kendi görüntüsü + kendi sesi → tek parça video (1DM Experimental'ın aynısı)
            used = []
            for vv in item.videos:
                a = next((f for f in hls_audio if f.lang == vv.lang and f not in used), None)
                if a is None or id(vv) not in files:
                    continue
                out = Path(files[id(vv)]).with_name(Path(files[id(vv)]).stem + f".{vv.lang}.mkv")
                mux_aligned(Path(files[id(vv)]), [(Path(files[id(a)]), a.lang)], out, self.status.log)
                files[id(vv)] = out
                used.append(a)
            if used:
                self.status.log("  🎬 HLS: " + " + ".join(f"{a.lang.upper()} görüntü + {a.lang.upper()} ses" for a in used) + " ayrı ayrı birleştirildi")
                item.files = [f for f in item.files if f not in used]
            return
        if not v or id(v) not in hls_ids or not hls_audio:
            return
        if self.recipe.separate:
            # 1DM "Experimental" gibi: her dil sesi görüntüyle ayrı bir video olur (görüntü yine bir kez indi)
            vids = []
            for f in hls_audio:
                out = Path(files[id(v)]).with_name(Path(files[id(v)]).stem + f".{f.lang}.mkv")
                mux_aligned(Path(files[id(v)]), [(Path(files[id(f)]), f.lang)], out, self.status.log)
                spec = FileSpec(url=v.url, role="video", lang=f.lang, line=v.line)
                files[id(spec)] = out
                vids.append(spec)
            self.status.log("  🎬 HLS: " + " + ".join(f"{s.lang.upper()} sesli video" for s in vids) + " ayrı ayrı hazırlandı (görüntü bir kez indi)")
            item.files = vids + [f for f in item.files if f.role == "subtitle"]
            item.video_audio = []
            return
        out = Path(files[id(v)]).with_name(Path(files[id(v)]).stem + ".hls.mkv")
        run.text = "Görüntü ve sesler aynı zaman çizelgesinde birleştiriliyor"
        self.sync_status()
        mux_aligned(Path(files[id(v)]), [(Path(files[id(f)]), f.lang) for f in hls_audio], out, self.status.log)
        self.status.log(f"  🎬 HLS: görüntü + {len(hls_audio)} ses ({', '.join(f.lang for f in hls_audio)}) kaymadan birleştirildi")
        files[id(v)] = out
        item.files = [f for f in item.files if f not in hls_audio]
        item.video_audio = [f.lang for f in hls_audio]

    def process(self, run: ItemRun):
        run.state = "processing"
        mkv_mode = output_mode() == "mkv"
        try:
            files = {}
            ok_specs = []
            for spec, d in zip(run.item.files, run.downloads):
                if d.state == "done" and d.path:
                    files[id(spec)] = d.path
                    ok_specs.append(spec)
            item = Item(run.item.season, run.item.episode, ok_specs, list(run.item.video_audio), list(run.item.video_subs))
            self.premux_hls(run, item, files, {id(s) for s, d in zip(run.item.files, run.downloads) if d.hls})
            if item.is_versions:
                ok_specs = list(item.files)    # HLS "ayrı video": görüntü + her dil sesi ayrı videolara dönüştü
            if item.is_versions:
                vt = f"{self.title} ({self.year})" if self.year else self.title
                if run.item.season is not None:
                    vt = f"{self.title} - {run.item.label}"
                self.deliver_versions(run, files, ok_specs, vt)
                run.state, run.progress, run.text = "done", 1.0, "✓ Yüklendi (" + " + ".join(v.lang.upper() for v in item.videos) + " ayrı video)"
                self.delete_phone_assets(run.item)
                self.first_ok = True
                self.sync_status(force=True)
                commit_catalog(f"Katalog güncellendi ({self.title} {run.item.label})", self.status)
                return
            # Tek video "video tr:" gibi dil yazılmışsa ve videonun etiketsiz sesi varsa o dil yazılsın
            if item.video and item.video.lang != "und" and not item.video_audio:
                item.video_audio = [item.video.lang]
            title = f"{self.title} ({self.year})" if self.year else self.title
            if run.item.season is not None:
                title = f"{self.title} - {run.item.label}"
            out = self.workdir / f"item{run.index:03d}" / f"{safe_name(title)}.mkv"
            # MKV modunda dış altyazılar ayrı VTT olur (oynatıcıda en sorunsuz yol); çevrilemeyen (ASS) MKV'ye girer
            sidecars: list[tuple[FileSpec, Path]] = []
            if mkv_mode:
                for spec in ok_specs:
                    if spec.role != "subtitle":
                        continue
                    try:
                        vtt = to_webvtt(Path(files[id(spec)]).read_bytes())
                        vpath = out.parent / f"sidecar{len(sidecars)}.{spec.lang}.vtt"
                        vpath.write_text(vtt, encoding="utf-8")
                        sidecars.append((spec, vpath))
                    except Exception as e:  # noqa: BLE001
                        self.status.log(f"  altyazı VTT'ye çevrilemedi, MKV'nin içine konacak: {e}")
            run.text, run.progress = "MKVToolNix ile birleştiriliyor", 0.45
            self.sync_status()

            def mprog(frac):
                run.progress = 0.45 + 0.1 * frac
                run.text = f"Birleştiriliyor %{int(frac * 100)}"
                self.sync_status()
            sync_notes: list[str] = []
            originals = dict(files)   # mkvmerge hızı düzeltilen sesi yeni dosyayla değiştirebilir
            self.status.detail(f"═══ {run.item.label}: birleştirme ═══")
            mkvmerge(item, files, out, title, mprog, sidecar={id(s) for s, _ in sidecars}, notes=sync_notes, detail=self.status.detail)
            for n in sync_notes:
                self.status.log("  🎚️ " + n)
                with self.status.lock:
                    self.status.data.setdefault("warnings", []).append(f"{run.item.label}: {n}")
            self.status.log(f"  ✓ {out.name} oluşturuldu ({human(out.stat().st_size)})")
            if mkv_mode:
                self.ensure_playable_audio(out, run)
                self.deliver_mkv(run, out, sidecars)
                # Hepsi GitHub'da doğrulandı: şimdi kaynakları ve MKV'yi sil.
                # Bilgisayardaki asıl dosyalar (file://) sadece "| sil" denmişse silinir.
                keep = {str(originals[id(s)]) for s in ok_specs if s.url.lower().startswith("file://") and not s.delete}
                for p in set(map(str, files.values())) | set(map(str, originals.values())) | {str(out)} | {str(v) for _, v in sidecars}:
                    if str(p) not in keep:
                        Path(p).unlink(missing_ok=True)
                self.status.log(f"  🧹 {run.item.label}: kaynak dosyalar ve MKV silindi")
            else:
                keep = {str(originals[id(s)]) for s in ok_specs if s.url.lower().startswith("file://") and not s.delete}
                for p in set(map(str, files.values())) | set(map(str, originals.values())):  # ham dosyaları hemen sil
                    if p and p != out and str(p) not in keep:
                        Path(p).unlink(missing_ok=True)
                job = UploadJob(
                    kind="episode" if run.item.season is not None else "movie", input=str(out), title=self.title,
                    item_id=self.item_id, tmdb=self.recipe.tmdb_id, year=self.year, season=run.item.season, episode=run.item.episode,
                    parallel=6, delete_source=True,
                )
                run_job(job, self.gh, self.workdir / "work", ItemReporter(run, self.status, 0.55), cancel=lambda: self.cancelled)
            run.state, run.progress, run.text = "done", 1.0, "✓ Yüklendi" + (" (bazı altyazılar inemedi)" if len(ok_specs) < len(run.item.files) else "")
            self.delete_phone_assets(run.item)
            self.first_ok = True
            self.sync_status(force=True)
            commit_catalog(f"Katalog güncellendi ({self.title} {run.item.label})", self.status)
        except Cancelled:
            run.state, run.text = "error", "İptal edildi"
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            run.state, run.error, run.text = "error", str(e), "Hata"
            self.status.log(f"  ❌ {run.item.label}: {e}")
        finally:
            # MKV modunda başarısızlıkta dosyalar kalır mı? İş klasörü her işte baştan kurulur; disk dolmasın diye temizlenir.
            shutil.rmtree(self.workdir / f"item{run.index:03d}", ignore_errors=True)
            shutil.rmtree(self.workdir / "work", ignore_errors=True)
            self.sync_status(force=True)

    def process_trailer(self):
        """Her dil için fragmanı (normal + varsa 4K) dönüştürmeden yükler ve kayda yazar."""
        st = self.trailer_state
        group = self.trailer
        added, failed = [], []
        try:
            if group is None:
                raise RuntimeError("fragman yok")
            langs = []
            for lang, _, _ in group.items:
                if lang not in langs:
                    langs.append(lang)
            for lang in langs:
                main = next((d for l, v, d in group.items if l == lang and v == "main"), None)
                uhd = next((d for l, v, d in group.items if l == lang and v == "uhd"), None)
                label = "Fragman" + ("" if lang == "und" else f" ({lang})")
                try:
                    if main is None or main.state != "done" or not main.path:
                        raise RuntimeError(main.error if main else "indirilemedi")
                    st.update(state="processing", text=f"{label} yükleniyor")
                    self.sync_status()
                    tag = f"{self.item_id}-trailer" if lang == "und" else f"{self.item_id}-{lang}-trailer"
                    rel = self.gh.ensure_release(tag, f"{self.title} ({label})", "G-Flix fragman dosyaları.")
                    for a in self.gh.release_assets(rel["id"]):
                        self.gh.delete_asset(a["id"])
                    on_text = lambda t: st.update(text=t)  # noqa: E731
                    urls, sizes = self.upload_parts(rel, Path(main.path), "v", {}, None, label, on_text)
                    stream = {"master": "", "lang": lang, "release": tag, "audio": [] if lang == "und" else [lang], "subtitles": [],
                              "direct": {"video": {"parts": urls, "sizes": sizes}, "audio": [], "videoAudio": []}}
                    if uhd is not None and uhd.state == "done" and uhd.path:
                        u_urls, u_sizes = self.upload_parts(rel, Path(uhd.path), "uhd", {}, None, label + " 4K", on_text)
                        stream["uhd"] = {"parts": u_urls, "sizes": u_sizes}
                    elif uhd is not None:
                        self.status.log(f"  {label}: 4K VP9 sürümü yok, normal kalitede eklendi")
                    save_trailer(self.gh, self.item_id, stream)
                    added.append(((lang if lang != "und" else "") + (" 4K" if "uhd" in stream else "")).strip())
                    for d in (main, uhd):
                        if d is not None and d.path and self._owned(Path(d.path)):
                            Path(d.path).unlink(missing_ok=True)
                    url = next((u for l, u in self.recipe.trailers if l == lang), "")
                    if url.lower().startswith("asset:"):
                        self.delete_phone_assets(Item(files=[FileSpec(url=url, role="video")]))
                except Exception as e:  # noqa: BLE001
                    traceback.print_exc()
                    failed.append(f"{label}: {str(e)[:200]}")
                    self.status.log(f"  ⚠ {label}: {e}")
            if added:
                names = ", ".join(x for x in added if x)
                st.update(state="done", text="✓ Fragman yüklendi" + (f" ({names})" if names else "") + (" — " + "; ".join(failed) if failed else ""))
                commit_catalog(f"Katalog güncellendi ({self.title} fragman)", self.status)
            else:
                st.update(state="error", text="Fragman eklenemedi: " + "; ".join(failed)[:400])
        except Exception as e:  # noqa: BLE001
            st.update(state="error", text=f"Fragman eklenemedi: {str(e)[:300]}")
            self.status.log(f"  ⚠ Fragman: {e}")
        finally:
            shutil.rmtree(self.workdir / "trailer", ignore_errors=True)
            shutil.rmtree(self.workdir / "work-trailer", ignore_errors=True)
            self.sync_status(force=True)

    def start_trailer(self):
        if not self.recipe.trailers:
            return
        group = TrailerGroup()
        for lang, url in self.recipe.trailers:
            is_asset = url.lower().startswith("asset:")
            method = "asset" if is_asset else method_for(url)
            suffix = "" if lang == "und" else f" ({lang})"
            main = Download(url=url, dest_dir=self.workdir / "trailer", name=f"fragman_{lang}",
                            label=("Fragman (telefondan)" if is_asset else "Fragman") + suffix, method=method)
            if method == "ytdlp":
                main.ytdlp_format, main.ytdlp_merge = TRAILER_FMT, "mp4"
            group.items.append((lang, "main", main))
            if method == "ytdlp":
                uhd = Download(url=url, dest_dir=self.workdir / "trailer", name=f"fragman_{lang}_4k", label="Fragman 4K" + suffix,
                               method="ytdlp", optional=True)
                uhd.ytdlp_format, uhd.ytdlp_merge = TRAILER_UHD_FMT, "mkv"
                group.items.append((lang, "uhd", uhd))
        for _, _, d in group.items:
            try:
                self.dl.add(d)
            except Exception as e:  # noqa: BLE001
                d.state, d.error = "error", str(e)
        self.trailer = group
        self.trailer_state.update(state="downloading", text="İndiriliyor")

    def finish(self) -> str:
        self.dl.close()
        self.status.mark("end")
        with self.status.lock:
            self.status.data["timing"]["items"] = len(self.runs)
            self.status.data["timing"]["done_items"] = sum(1 for r in self.runs if r.state == "done")
        self.status.detail(f"İş süresi: {human_time(time.time() - self.status.t0)}")
        self.status.save_log()
        ok = sum(1 for r in self.runs if r.state == "done")
        state = "cancelled" if self.cancelled else ("done" if ok == len(self.runs) else ("partial" if ok else "error"))
        with self.status.lock:
            self.status.data["state"] = state
        stage = {"done": "Tamamlandı", "partial": f"{ok}/{len(self.runs)} tamamlandı", "error": "Başarısız", "cancelled": "İptal edildi"}[state]
        self.sync_status(stage, force=True)
        return state

    # ---- hızlı mod (birleştirmeden)
    def media_release(self, run: ItemRun) -> tuple[dict, bool]:
        """Öğenin dosyalarının kalıcı olarak duracağı Release. (release, sunucu mu oluşturdu)"""
        for f in run.item.files:
            ref = f.url.split(":", 1)[1] if f.url.lower().startswith("asset:") else ""
            if "/" in ref:
                tag = ref.split("/", 1)[0]
                rel = self.gh.get_release(tag)
                if not rel:
                    raise RuntimeError(f"Telefondan yüklenen dosyalar bulunamadı ({tag} silinmiş olabilir)")
                return rel, False
        tag = "media-" + self.rel["tag_name"].removeprefix("linkjob-") + f"-{run.index}"
        existed = self.gh.get_release(tag)
        rel = existed or self.gh.ensure_release(tag, f"G-Flix · {self.title} {run.item.label}", "G-Flix içerik dosyaları (hızlı yükleme).")
        return rel, existed is None

    def _asset_bytes(self, a: dict) -> bytes:
        r = self.gh.s.get(a["url"], headers={"Accept": "application/octet-stream"}, allow_redirects=False, timeout=(30, 300))
        if r.status_code in (301, 302, 303, 307, 308):
            r = requests.get(r.headers["Location"], timeout=(30, 300))  # imzalı adres: yetki başlığı gönderilmez
        r.raise_for_status()
        return r.content

    def _ref_assets(self, ref: str, rel: dict, assets: dict) -> tuple[list[dict], dict, dict]:
        """asset:media-x/f0 -> (parçalar, o Release'in dosyaları, Release)"""
        tag, base = ref.split("/", 1)
        if tag != rel["tag_name"]:
            rel = self.gh.get_release(tag) or {}
            assets = {a["name"]: a for a in self.gh.release_assets(rel["id"])} if rel else {}
        parts = sorted((a for n, a in assets.items() if n.startswith(base + ".p")), key=lambda a: a["name"])
        if not parts:
            raise RuntimeError(f"Telefondan yüklenen dosya bulunamadı: {ref}")
        bad = [a["name"] for a in parts if a.get("state") != "uploaded"]
        if bad:
            raise RuntimeError(f"Telefondan yükleme yarım kalmış: {', '.join(bad)}")
        return parts, assets, rel

    def wait_download(self, d: Download, run: ItemRun) -> Path:
        last_check = time.time()
        while d.state not in ("done", "error"):
            self.dl.poll()
            run.text = f"{d.label} indiriliyor {human(d.done)} / {human(d.total or 0)} · {human(d.speed)}/sn"
            self.sync_status()
            if self.cancelled or time.time() - last_check > 15:
                last_check = time.time()
                if self.cancelled or self.check_cancel():
                    self.dl.cancel(d)
                    raise Cancelled()
            time.sleep(2)
        if d.state == "error" or not d.path:
            raise RuntimeError(f"{d.label}: {d.error or 'indirilemedi'}")
        return Path(d.path)

    def process_fast(self, run: ItemRun):
        it = run.item
        run.state, run.text, run.progress = "processing", "Hazırlanıyor", 0.05
        self.sync_status(force=True)
        rel, created = None, False
        base_dir = self.workdir / f"item{run.index:03d}"
        try:
            rel, created = self.media_release(run)
            tag = rel["tag_name"]
            assets = {a["name"]: a for a in self.gh.release_assets(rel["id"])}
            # Telefonda olmayan (link) video/ses dosyalarını hepsini aynı anda indirmeye başla
            pending: dict[int, Download] = {}
            for k, f in enumerate(it.files):
                ref = f.url.split(":", 1)[1] if f.url.lower().startswith("asset:") else ""
                if f.role in ("video", "audio") and "/" not in ref:
                    label = "Video" if f.role == "video" else f"Ses ({f.lang})"
                    d = Download(url=f.url, dest_dir=base_dir, name=f"f{k}", label=label, headers=dict(f.headers))
                    self.dl.add(d)
                    run.downloads.append(d)
                    pending[k] = d
            video, audios, subs, warnings = None, [], [], []
            n_files = max(len(it.files), 1)
            for k, f in enumerate(it.files):
                if self.cancelled:
                    raise Cancelled()
                ref = f.url.split(":", 1)[1] if f.url.lower().startswith("asset:") else ""
                if f.role == "subtitle":
                    label = f"Altyazı ({f.lang}{', zorunlu' if f.forced else ''})"
                    try:
                        src_parts = []
                        if "/" in ref:
                            src_parts, _, _ = self._ref_assets(ref, rel, assets)
                            data = b"".join(self._asset_bytes(a) for a in src_parts)
                        elif ref:
                            tmp = base_dir / f"sub{k}"
                            base_dir.mkdir(parents=True, exist_ok=True)
                            self.fetch_asset(ref, tmp, lambda n: None, Download(url=f.url, dest_dir=base_dir, name=f"sub{k}"))
                            data = tmp.read_bytes()
                        else:
                            from gflix.fetch import browser_headers
                            r = requests.get(f.url, headers=browser_headers(f.url, f.headers), timeout=(30, 120))
                            r.raise_for_status()
                            data = r.content
                        vtt = to_webvtt(data)
                        name = f"sub{k}.{f.lang}{'.zorunlu' if f.forced else ''}.vtt"
                        vpath = base_dir / name
                        base_dir.mkdir(parents=True, exist_ok=True)
                        vpath.write_text(vtt, encoding="utf-8")
                        url = self.gh.upload_asset(rel, vpath, name, "text/vtt", existing=assets)
                        subs.append({"lang": f.lang, "label": f.name or (LANG_LABEL.get(f.lang, f.lang) + (" (Zorunlu)" if f.forced else "")),
                                     "url": url, "forced": f.forced})
                        for a in src_parts:          # ham altyazı artık gereksiz
                            if a["name"] in assets:
                                self.gh.delete_asset(a["id"])
                    except Exception as e:  # noqa: BLE001  altyazı olmadan da devam
                        w = f"{it.label}: {label} eklenemedi ({str(e)[:200]})"
                        warnings.append(w)
                        self.status.log("  ⚠ " + w)
                        with self.status.lock:
                            self.status.data.setdefault("warnings", []).append(w)
                    continue
                label = "Video" if f.role == "video" else f"Ses ({f.lang})"
                if "/" in ref:
                    parts, _, _ = self._ref_assets(ref, rel, assets)
                    urls, sizes = [a["url"] for a in parts], [a["size"] for a in parts]
                    self.status.log(f"  ✓ {it.label} {label}: telefondan yüklenmiş ({human(sum(sizes))}, {len(parts)} parça)")
                else:
                    path = self.wait_download(pending[k], run)
                    urls, sizes = self.upload_parts(rel, path, f"f{k}", assets, run, label)
                    path.unlink(missing_ok=True)
                    self.status.log(f"  ✓ {it.label} {label}: yüklendi ({human(sum(sizes))}, {len(urls)} parça)")
                entry = {"parts": urls, "sizes": sizes}
                if f.role == "video":
                    video = entry
                else:
                    audios.append({"lang": f.lang, "label": f.name or None, "default": f.default, **entry})
                run.progress = 0.1 + 0.8 * (k + 1) / n_files
                self.sync_status()
            if not video:
                raise RuntimeError("video dosyası yok")
            langs: list[str] = []
            for lang in list(it.video_audio) + [a["lang"] for a in audios]:
                if lang and lang != "und" and lang not in langs:
                    langs.append(lang)
            stream = {"master": "", "audio": langs, "subtitles": subs, "release": tag,
                      "direct": {"video": video, "audio": audios, "videoAudio": list(it.video_audio)}}
            run.text = "Kataloğa ekleniyor"
            self.sync_status()
            kind = "episode" if it.season is not None else "movie"
            old, _ = self.gh.get_json(f"{ITEMS_DIR}/{self.item_id}.json")
            old_tag = None
            self._old_duration = 0.0
            if old:
                if kind == "movie":
                    old_st = old.get("stream") or {}
                else:
                    old_st = next(((ep.get("stream") or {}) for ep in old.get("episodes", [])
                                   if ep.get("season") == it.season and ep.get("episode") == it.episode), {})
                old_tag = old_st.get("release")
                self._old_duration = float(old_st.get("duration") or 0)
            job = UploadJob(kind=kind, input="", title=self.title, item_id=self.item_id, tmdb=self.recipe.tmdb_id, year=self.year,
                            season=it.season, episode=it.episode)
            save_stream(self.gh, job, stream)
            if old_tag and old_tag != tag:   # aynı film/bölüm daha önce yüklenmişse eski dosyaları sil (süreler tutuyorsa)
                self.delete_old(old_tag, stream.get("duration"))
            run.state, run.progress = "done", 1.0
            run.text = "✓ Eklendi (birleştirmeden)" + (" — bazı altyazılar eklenemedi" if warnings else "")
            self.first_ok = True
            self.sync_status(force=True)
            commit_catalog(f"Katalog güncellendi ({self.title} {it.label})", self.status)
        except Cancelled:
            run.state, run.text = "error", "İptal edildi"
            if created and rel:
                self.gh.delete_release(rel["tag_name"])
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            run.state, run.error, run.text = "error", str(e), "Hata"
            self.status.log(f"  ❌ {it.label}: {e}")
            if created and rel:     # sunucunun açtığı yarım Release'i bırakma
                try:
                    self.gh.delete_release(rel["tag_name"])
                except Exception:  # noqa: BLE001
                    pass
        finally:
            shutil.rmtree(base_dir, ignore_errors=True)
            self.sync_status(force=True)

    def run_fast(self) -> str:
        self.sync_status("TMDB bilgileri alınıyor", force=True)
        self.resolve()
        self.start_trailer()
        self.sync_status("Ekleniyor (birleştirmeden)", force=True)
        for run in self.runs:
            if self.cancelled or self.check_cancel():
                run.state, run.text = "skipped", "İptal edildi"
                continue
            self.process_fast(run)
        if self.trailer:
            last_check = time.time()
            while self.trailer.state not in ("done", "error") and not self.cancelled:
                self.dl.poll()
                self.trailer_state["text"] = f"İndiriliyor {human(self.trailer.done)} · {human(self.trailer.speed)}/sn"
                self.sync_status("Fragman indiriliyor")
                if time.time() - last_check > 15:
                    last_check = time.time()
                    self.check_cancel()
                time.sleep(2)
            if self.trailer.state == "done" and self.first_ok and not self.cancelled:
                self.trailer_state.update(state="ready", text="İndirildi")
                self.process_trailer()
            elif self.trailer.state == "error":
                self.trailer_state.update(state="error", text=f"İndirilemedi: {self.trailer.error[:300]}")
            else:
                self.trailer_state.update(state="error", text="Film/bölüm eklenemediği için fragman eklenmedi")
        return self.finish()

    # ---- zamanlayıcı
    def _release_site(self) -> None:
        t = getattr(self, "turn", None)
        if t is not None and t.held:
            t.release()
            self.status.log("  ⏩ Siteden indirme bitti; bu iş yüklenirken sıradaki iş inmeye başlayabilir")

    def run(self):
        if self.recipe.fast:
            t = getattr(self, "turn", None)
            if t is not None:
                t.release()     # telefondan gelen dosyalar: siteye istek yok
            return self.run_fast()
        self.sync_status("TMDB bilgileri alınıyor", force=True)
        self.resolve()
        if not self.wait_approval():
            for r in self.runs:
                r.state, r.text = "skipped", "İptal edildi"
            self.cancelled = True
            return self.finish()
        self.status.mark("dl_start")
        self.sync_status("İndiriliyor", force=True)
        self.start_trailer()

        pending = list(self.runs)
        worker: threading.Thread | None = None
        next_proc = 0
        trailer_done = not self.recipe.trailer
        last_cancel_check = 0.0
        cancel_seen = False
        while True:
            # Boş alana göre yeni öğelerin indirmesini başlat (hepsi aynı anda, sınırsız)
            free = shutil.disk_usage(self.workdir).free - RESERVE
            reserved = sum(r.reserved for r in self.runs if r.state in ("downloading", "ready", "processing"))
            while pending and not self.cancelled:
                # Sırayla: siteden aynı anda tek bölüm iner. Önceki bölüm birleştirilip yüklenirken (processing)
                # sıradaki bölüm inmeye başlar; indirilmiş ama işlenmeyi bekleyen bölüm varken yenisi başlamaz.
                busy_states = ("downloading", "ready") if os.environ.get("GFLIX_PIPELINE", "1") != "0" \
                    else ("downloading", "ready", "processing")
                if os.environ.get("GFLIX_SEQUENTIAL", "1") != "0" and any(r.state in busy_states for r in self.runs):
                    break
                est = self.estimate(pending[0]) if pending[0].reserved == 0 else pending[0].reserved
                need = int(est * 2.2)
                if reserved > 0 and reserved + need > free:
                    break
                run = pending.pop(0)
                run.reserved = need
                reserved += need
                self.start_downloads(run)
            self.dl.poll()
            for run in self.runs:
                if run.state != "downloading":
                    continue
                done = sum(d.done for d in run.downloads)
                total = sum(d.total or UNKNOWN_SIZE["video"] for d in run.downloads)
                speed = sum(d.speed for d in run.downloads)
                run.progress = 0.45 * min(done / max(total, 1), 1.0)
                run.text = f"İndiriliyor {human(done)} / {human(total)} · {human(speed)}/sn"
                fatal = [d for d in run.downloads if d.state == "error" and not d.optional]
                soft = [d for d in run.downloads if d.state == "error" and d.optional]
                if fatal:
                    run.state, run.text = "error", "İndirme hatası"
                    run.error = "; ".join(f"{d.label}: {d.error}" for d in fatal)[:900]
                    self.status.log(f"  ❌ {run.item.label} indirilemedi: {run.error}")
                    for d in run.downloads:
                        self.dl.cancel(d)
                    shutil.rmtree(self.workdir / f"item{run.index:03d}", ignore_errors=True)
                elif all(d.state in ("done", "error") for d in run.downloads):
                    run.state, run.text = "ready", "İndirildi, sırada"
                    self.status.add_time("bytes", done)
                    self.status.log(f"  ✓ {run.item.label} indirildi ({human(done)})")
                    for d in soft:
                        w = f"{run.item.label}: {d.label} inemedi, onsuz devam ediliyor ({d.error[:200]})"
                        self.status.log("  ⚠ " + w)
                        with self.status.lock:
                            self.status.data.setdefault("warnings", []).append(w)
            # Bütün indirmeler bitti: süreyi yaz, site sırasını bırak (bu iş yüklenirken sıradaki iş inebilir)
            if not pending and not any(r.state == "downloading" for r in self.runs):
                if "dl_start" in self.status.data.get("timing", {}):
                    self.status.mark("dl_end")
                self._release_site()
            if self.trailer and self.trailer_state["state"] == "downloading":
                if self.trailer.state == "done":
                    self.trailer_state.update(state="ready", text="İndirildi, film yüklenince eklenecek")
                elif self.trailer.state == "error":
                    self.trailer_state.update(state="error", text=f"İndirilemedi: {self.trailer.error[:300]}")
                    trailer_done = True
                else:
                    self.trailer_state["text"] = f"İndiriliyor {human(self.trailer.done)} · {human(self.trailer.speed)}/sn"

            # Sırayla işle (birleştir + yükle); indirmeler arka planda sürer
            if worker is None or not worker.is_alive():
                worker = None
                while next_proc < len(self.runs) and self.runs[next_proc].state in ("done", "error", "skipped"):
                    next_proc += 1
                if not trailer_done and self.first_ok and self.trailer_state["state"] in ("ready", "error"):
                    trailer_done = True
                    if self.trailer_state["state"] == "ready":
                        worker = threading.Thread(target=self.process_trailer, daemon=True)
                        worker.start()
                if worker is None and next_proc < len(self.runs) and self.runs[next_proc].state == "ready":
                    worker = threading.Thread(target=self.process, args=(self.runs[next_proc],), daemon=True)
                    worker.start()

            all_items_done = all(r.state in ("done", "error", "skipped") for r in self.runs)
            if not trailer_done and all_items_done and not self.first_ok:
                trailer_done = True
                self.trailer_state.update(state="error", text="Film/bölüm yüklenemediği için fragman eklenmedi")
            if all_items_done and trailer_done and (worker is None or not worker.is_alive()):
                break
            killed = self.rel.get("tag_name") in KILLED and not cancel_seen
            if killed or time.time() - last_cancel_check > (60 if __import__("gflix.github", fromlist=["rate_low"]).rate_low() else 15):
                last_cancel_check = time.time()
                if (killed or not cancel_seen) and self.check_cancel():
                    cancel_seen = True
                    self.status.log("🛑 İptal istendi")
                    for r in self.runs:
                        if r.state in ("waiting", "downloading", "ready"):
                            r.state, r.text = "skipped", "İptal edildi"
                    pending.clear()
                    trailer_done = True
            busy = sum(1 for r in self.runs if r.state == "downloading")
            self.sync_status(f"{busy} öğe indiriliyor" if busy else "İşleniyor")
            time.sleep(2)

        return self.finish()


def read_recipe(gh: GitHub, rel: dict, name: str = "recipe.txt") -> str:
    assets = {a["name"]: a for a in gh.release_assets(rel["id"])}
    if name not in assets:
        raise RuntimeError(f"{name} bulunamadı")
    r = gh.s.get(assets[name]["url"], headers={"Accept": "application/octet-stream"}, allow_redirects=False, timeout=60)
    if r.status_code in (301, 302, 303, 307, 308):
        r = requests.get(r.headers["Location"], timeout=60)  # imzalı adres: yetki başlığı gönderilmez
    r.raise_for_status()
    return r.content.decode("utf-8-sig")


def needs_tools(recipe: Recipe) -> bool:
    """Hızlı modda bütün dosyalar telefondan media-… Release'ine yüklendiyse ve fragman yoksa hiçbir araç gerekmez."""
    if not recipe.ok or not recipe.fast or recipe.trailer:
        return True
    for it in recipe.items:
        for f in it.files:
            ref = f.url.split(":", 1)[1] if f.url.lower().startswith("asset:") else ""
            if f.role in ("video", "audio") and "/" not in ref:
                return True
            if f.role == "subtitle" and not ref:
                return True
    return False


def print_needs() -> int:
    """İş akışı için: araç kurulumu (aria2, MKVToolNix, ffmpeg…) gerekiyor mu? GITHUB_OUTPUT'a full=true/false yazar."""
    full = True
    try:
        tag = os.environ.get("GFLIX_TAG", "")
        if tag:
            gh = GitHub(os.environ["GFLIX_REPO"], os.environ["GITHUB_TOKEN"])
            rel = gh.get_release(tag)
            if rel:
                full = needs_tools(parse(read_recipe(gh, rel)))
    except Exception as e:  # noqa: BLE001
        print(f"tarif okunamadı, bütün araçlar kurulacak: {e}")
    print(f"full={'true' if full else 'false'}")
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a", encoding="utf-8") as fh:
            fh.write(f"full={'true' if full else 'false'}\n")
    return 0


def main() -> int:
    if "--needs" in sys.argv:
        return print_needs()
    gh = GitHub(os.environ["GFLIX_REPO"], os.environ["GITHUB_TOKEN"])
    os.environ.setdefault("GFLIX_BRANCH", gh.repo_info()["default_branch"])
    subprocess.run(["git", "config", "user.name", "G-Flix Bot"], cwd=ROOT)
    subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=ROOT)
    cookies = os.environ.get("YT_COOKIES", "").strip()
    if cookies:
        p = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "yt_cookies.txt"
        p.write_text(cookies + "\n", encoding="utf-8")
        os.environ["GFLIX_YT_COOKIES_FILE"] = str(p)
    workdir = Path(os.environ.get("GFLIX_WORKDIR", "gflix-links"))
    tags = [os.environ["GFLIX_TAG"]] if os.environ.get("GFLIX_TAG") else [
        r["tag_name"] for r in gh.list_releases()
        if r["tag_name"].startswith("linkjob-") and not r.get("draft") and (r.get("body") or "").startswith("⏳")]
    for tag in tags:
        run_tag(gh, tag, workdir)
    return 0


def run_tag(gh: GitHub, tag: str, workdir: Path) -> str:
    """Bir işi baştan sona yürütür; sonuç durumunu döner (done | partial | error | cancelled)."""
    rel = gh.get_release(tag)
    if not rel:
        print(f"{tag} bulunamadı")
        return "error"
    status = Status(gh, rel)
    ACTIVE_STATUSES.add(status)

    def cancel_requested() -> bool:
        if tag in KILLED:
            return True
        try:
            return any(a["name"] == "cancel" for a in gh.release_assets(rel["id"]))
        except GitHubError as e:
            return e.status == 404      # kaydı silinmiş iş: durdur
        except Exception:  # noqa: BLE001
            return False

    def guarded(fn):
        """Uzun hazırlık adımlarını (yakalama/link çözme) ayrı iş parçacığında yürütür; arada iptal gelirse beklemeden bırakır."""
        box: dict = {}

        def run():
            try:
                box["v"] = fn()
            except BaseException as ex:  # noqa: BLE001
                box["e"] = ex
        t = threading.Thread(target=run, daemon=True, name=f"{tag}-hazirlik")
        t.start()
        last = 0.0
        while t.is_alive():
            t.join(2)
            if t.is_alive() and time.time() - last > 10:
                last = time.time()
                if cancel_requested():
                    raise Cancelled()
        if "e" in box:
            raise box["e"]
        return box.get("v")

    turn = SiteTurn(status, cancel_requested)
    try:
        if cancel_requested():
            raise Cancelled()
        turn.acquire()
        recipe = parse(read_recipe(gh, rel))
        if not recipe.ok:
            raise RuntimeError("Tarifte hata var:\n" + "\n".join(recipe.errors))
        shutil.rmtree(workdir, ignore_errors=True)
        workdir.mkdir(parents=True)
        try:    # katalogda hazır diziler: bölüm sayfası önce bunlarla eşleşir
            from gflix import resolve as _rm
            cat, _ = gh.get_json("catalog/catalog.json")
            _rm.KNOWN_SERIES = [
                {"title": t.get("title") or "", "tmdbId": t.get("tmdbId"), "year": t.get("year"),
                 "names": [t.get("title") or ""] + [v for v in (t.get("names") or {}).values() if isinstance(v, str)]}
                for t in (cat or {}).get("titles", []) if t.get("type") == "series"]
        except Exception:  # noqa: BLE001
            pass
        if recipe.capture:
            # Stüdyo → Sayfadan Yakala: telefondaki tarayıcının yakaladıkları (sayfayı kullanıcı açtı)
            from gflix import resolve as resolve_mod
            status.data.update(stage="Telefonda yakalananlar inceleniyor")
            status.push(force=True)
            cap = resolve_mod.capture_from_json(json.loads(read_recipe(gh, rel, recipe.capture)))
            _r = recipe
            text = guarded(lambda: resolve_mod.expand(cap.page_url, _r, workdir / "kaynak", os.environ.get("TMDB_API_KEY", "").strip(), status.log, cap=cap))
            status.detail("── Yakalamadan üretilen tarif ──\n" + re.sub(r"(cookie: )[^|\n]+", r"\1…", text))
            auto = recipe.auto_approve
            recipe = parse(text)
            recipe.auto_approve = auto
            if not recipe.ok:
                raise RuntimeError("Yakalamadan üretilen tarifte hata:\n" + "\n".join(recipe.errors))
            _r = recipe
            guarded(lambda: resolve_mod.expand_video_lines(_r, status.log))
        elif not recipe.source:
            # "video tr: <ana m3u8>" / "video tr: … | ses: …": görüntü + o dilin sesi tek parça video
            from gflix import resolve as resolve_mod
            _r = recipe
            guarded(lambda: resolve_mod.expand_video_lines(_r, status.log))
        if recipe.source:
            # Sadece link verildi: sayfa/yayın çözülür, yapım TMDB'de bulunur, tam tarif üretilir
            from gflix import resolve as resolve_mod
            status.data.update(stage="Link çözülüyor")
            status.push(force=True)
            _r = recipe
            text = guarded(lambda: resolve_mod.expand(_r.source, _r, workdir / "kaynak", os.environ.get("TMDB_API_KEY", "").strip(), status.log))
            status.detail("── Linkten üretilen tarif ──\n" + re.sub(r"(cookie: )[^|\n]+", r"\1…", text))
            auto = recipe.auto_approve
            recipe = parse(text)
            recipe.auto_approve = auto
            if not recipe.ok:
                raise RuntimeError("Linkten üretilen tarifte hata:\n" + "\n".join(recipe.errors))
            _r = recipe
            guarded(lambda: resolve_mod.expand_video_lines(_r, status.log))
        job = LinkJob(gh, rel, recipe, workdir, status=status)
        job.turn = turn
        ACTIVE_JOBS[tag] = job
        if tag in KILLED:
            raise Cancelled()
        job.status.data["warnings"] = recipe.warnings
        return job.run()
    except Cancelled:
        status.mark("end")
        status.data.update(state="cancelled", stage="İptal edildi")
        status.log("🛑 İptal edildi")
        status.save_log()
        status.push(force=True)
        return "cancelled"
    except Exception as e:  # noqa: BLE001
        traceback.print_exc()
        status.mark("end")
        status.data.update(state="error", stage=f"Başarısız: {str(e)[:500]}")
        status.log(f"❌ {e}")
        status.detail(traceback.format_exc())
        status.save_log()
        status.push(force=True)
        return "error"
    finally:
        turn.release()
        ACTIVE_JOBS.pop(tag, None)
        KILLED.discard(tag)
        ACTIVE_STATUSES.discard(status)
        try:    # süre istatistiği: işçi bunu alıp kalıcı dosyaya yazar
            tm = dict(status.data.get("timing") or {})
            tm.setdefault("end", int(time.time()))
            text = " ".join(status.data.get("log") or []) + " " + " ".join(it.get("error", "") for it in status.data.get("items") or [])
            trouble = status.data.get("state") in ("error", "partial") and any(
                k in text for k in ("Parça inmedi", "429", "bağlantı kabul", "ConnectTimeout", "ConnectionError", "Max retries"))
            FINISHED[tag] = {"tag": tag, "title": status.data.get("title") or (rel.get("name") or "").removeprefix("🌐 ")[:80],
                             "kind": status.data.get("kind", ""), "state": status.data.get("state", ""), **tm,
                             **({"netTrouble": True} if trouble else {})}
        except Exception:  # noqa: BLE001
            pass
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
