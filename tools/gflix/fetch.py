"""Paralel indirme (aria2c / gdown / yt-dlp) ve dosya türünü içerikten tanıma."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

import requests

# ------------------------------------------------------------------ tür tanıma


def detect_ext(path: Path) -> str | None:
    """Uzantıya bakmadan içerikten gerçek türü bulur (.txt gelen dosyalar için)."""
    with open(path, "rb") as f:
        head = f.read(4096)
    if not head:
        return None
    if head[:4] == b"\x1a\x45\xdf\xa3":
        return ".mka" if b"webm" not in head[:64].lower() and _matroska_audio_only(path) else (".webm" if b"webm" in head[:64].lower() else ".mkv")
    if head[4:8] == b"ftyp":
        brand = head[8:12]
        return ".m4a" if brand in (b"M4A ", b"M4B ") else ".mp4"
    if head[:4] == b"RIFF" and head[8:12] == b"AVI ":
        return ".avi"
    if head[:4] == b"RIFF" and head[8:12] == b"WAVE":
        return ".wav"
    if head[0] == 0x47 and len(head) > 376 and head[188] == 0x47 and head[376] == 0x47:
        return ".ts"
    if head[:4] == b"fLaC":
        return ".flac"
    if head[:4] == b"OggS":
        return ".opus" if b"OpusHead" in head[:100] else ".ogg"
    if head[:2] == b"\x0b\x77":
        return ".ac3"   # E-AC3 de aynı imzayla başlar; mkvmerge ikisini de tanır
    if head[:4] == b"\x7f\xfe\x80\x01":
        return ".dts"
    if head[:3] == b"ID3" or (head[0] == 0xFF and head[1] & 0xE0 == 0xE0 and head[1] & 0x06 != 0):
        return ".mp3"
    if head[0] == 0xFF and head[1] & 0xF6 == 0xF0:
        return ".aac"
    text = head.decode("utf-8", "ignore").lstrip("﻿ \r\n\t")
    low = text[:300].lower()
    if low.startswith("<!doctype html") or low.startswith("<html") or "<head>" in low[:200]:
        return ".html"
    if text.startswith("WEBVTT"):
        return ".vtt"
    if text.startswith("[Script Info]") or "[script info]" in low[:50]:
        return ".ass"
    try:
        text_any = head.decode("cp1254", "ignore").lstrip("﻿ \r\n\t")
    except Exception:  # noqa: BLE001
        text_any = text
    if re.match(r"^\d+\s*\r?\n\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->", text_any):
        return ".srt"
    return _ffprobe_ext(path)


def _matroska_audio_only(path: Path) -> bool:
    try:
        from . import mkv
        types = {t.type for t in mkv.read_tracks(path)}
        return bool(types) and 1 not in types and 2 in types
    except Exception:  # noqa: BLE001
        return False


def _ffprobe_ext(path: Path) -> str | None:
    try:
        from .media import FFPROBE
        out = subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=format_name", "-of", "csv=p=0", str(path)],
                             capture_output=True, text=True, timeout=60).stdout.strip()
    except Exception:  # noqa: BLE001
        return None
    for key, ext in (("matroska", ".mkv"), ("mov", ".mp4"), ("mp4", ".mp4"), ("avi", ".avi"), ("mpegts", ".ts"), ("ac3", ".ac3"),
                     ("eac3", ".eac3"), ("dts", ".dts"), ("aac", ".aac"), ("mp3", ".mp3"), ("flac", ".flac"), ("srt", ".srt"),
                     ("webvtt", ".vtt"), ("ass", ".ass"), ("wav", ".wav")):
        if out.startswith(key) or f",{key}" in out:
            return ext
    return None


def _is_playlist(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            return f.read(64).lstrip(b"\xef\xbb\xbf \r\n\t").startswith(b"#EXTM3U")
    except OSError:
        return False


def fix_extension(path: Path) -> Path:
    """Dosyanın uzantısını gerçek türüne göre değiştirir (sadece ad değişir)."""
    with open(path, "rb") as f:
        head = f.read(64).lstrip(b"\xef\xbb\xbf \r\n\t")
    if head.startswith(b"#EXTM3U"):
        raise RuntimeError("Bu link bir video/ses dosyası değil, oynatma listesi (m3u8/HLS) — sadece parçaların listesini içeriyor. "
                           "Oynatma listeleri desteklenmiyor; dosyanın kendisini gönder (Dosyalardan Ekle ya da G-Flix Masaüstü).")
    ext = detect_ext(path)
    if ext == ".html":
        raise RuntimeError("Link bir dosya yerine web sayfası döndürdü (indirme linki doğrudan dosyayı göstermeli).")
    if not ext or path.suffix.lower() == ext:
        return path
    target = path.with_suffix(ext)
    path.rename(target)
    return target


# ------------------------------------------------------------------ indirme


@dataclass
class Download:
    url: str
    dest_dir: Path
    name: str                       # uzantısız hedef ad
    label: str = ""
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:8])
    method: str = "aria2"           # aria2 | gdrive | ytdlp | asset | http
    state: str = "waiting"          # waiting | active | done | error
    done: int = 0
    total: int = 0
    speed: float = 0.0
    error: str = ""
    path: Path | None = None
    gid: str | None = None
    ytdlp_format: str | None = None
    ytdlp_merge: str = "mkv"        # yt-dlp birleştirme kabı (fragmanın normal sürümü mp4)
    headers: dict = field(default_factory=dict)
    retried: bool = False
    optional: bool = False          # altyazı gibi: inmezse iş durmaz
    hls: bool = False               # HLS oynatma listesi: parçalar 16 paralel iner, zaman damgaları korunur (gflix/hls.py)

    def as_dict(self) -> dict:
        return {"label": self.label or self.name, "state": self.state, "done": self.done, "total": self.total,
                "speed": round(self.speed), "error": self.error[:300]}


def _tr_error(msg: str) -> str:
    low = msg.lower()
    table = [("not found", "Dosya bulunamadı (404) — link yanlış ya da süresi dolmuş"),
             ("403", "Erişim reddedildi (403) — link süresi dolmuş ya da izin gerekiyor"),
             ("401", "Yetki gerekiyor (401)"), ("timeout", "Bağlantı zaman aşımına uğradı"),
             ("name resolution", "Alan adı bulunamadı — link yanlış"), ("ssl", "Güvenli bağlantı kurulamadı (SSL)")]
    for k, tr in table:
        if k in low:
            return f"{tr} [{msg[:120]}]"
    return msg


BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"


def browser_headers(url: str, extra: dict | None = None) -> dict:
    """1DM gibi indiricilerin gönderdiği tarayıcı başlıkları."""
    u = urlparse(url)
    h = {
        "User-Agent": BROWSER_UA,
        "Accept": "*/*",
        "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer": f"{u.scheme}://{u.netloc}/",
        "Connection": "keep-alive",
    }
    h.update(extra or {})
    return h


def method_for(url: str) -> str:
    if url.lower().startswith("asset:"):
        return "asset"
    if url.lower().startswith("file://"):
        return "local"
    host = (urlparse(url).hostname or "").lower()
    if host.endswith("drive.google.com") or host.endswith("docs.google.com") or host.endswith("drive.usercontent.google.com"):
        return "gdrive"
    if any(host.endswith(h) for h in ("youtube.com", "youtu.be", "vimeo.com", "dailymotion.com")):
        return "ytdlp"
    return "aria2"


def local_path(url: str) -> Path:
    """file:///home/x/film.mkv -> Path (yüzde kodlaması çözülür)."""
    from urllib.parse import unquote
    return Path(unquote(urlparse(url).path))


def probe_size(url: str) -> int:
    if method_for(url) == "local":
        p = local_path(url)
        return p.stat().st_size if p.is_file() else 0
    if method_for(url) != "aria2":
        return 0
    try:
        r = requests.head(url, allow_redirects=True, timeout=20)
        return int(r.headers.get("Content-Length") or 0)
    except Exception:  # noqa: BLE001
        return 0


import itertools as _it
_PORTS = _it.count()


class Downloader:
    """Tüm indirmeleri aynı anda, hız sınırı olmadan yürütür."""

    def __init__(self, log=print, asset_fetcher=None):
        self.log = log
        # asset_fetcher(ad, hedef_yol, ilerleme(n)) — telefonun GitHub'a yüklediği dosyaları birleştirir
        self.asset_fetcher = asset_fetcher
        self.items: dict[str, Download] = {}
        # Aynı işlemde birden çok iş (aynı anda 2-3 iş) ayrı aria2 portu kullanır
        self.port = 6800 + (os.getpid() % 400) + next(_PORTS) % 90
        self.secret = uuid.uuid4().hex
        self.proc: subprocess.Popen | None = None
        self.lock = threading.Lock()
        self.poll_lock = threading.Lock()

    # ---- aria2 RPC
    def _start_aria2(self):
        if self.proc is not None:
            return
        if not shutil.which("aria2c"):
            raise RuntimeError("aria2c kurulu değil")
        self.proc = subprocess.Popen([
            "aria2c", "--enable-rpc", f"--rpc-listen-port={self.port}", f"--rpc-secret={self.secret}",
            "--max-concurrent-downloads=1000", "--max-connection-per-server=16", "--split=16", "--min-split-size=1M",
            "--max-overall-download-limit=0", "--max-download-limit=0", "--continue=true", "--file-allocation=none",
            "--max-tries=20", "--retry-wait=3", "--timeout=60", "--connect-timeout=30", "--auto-file-renaming=false",
            "--allow-overwrite=true", "--console-log-level=warn", "--summary-interval=0", "--check-certificate=true",
            "--user-agent=Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36",
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                self._rpc("aria2.getVersion")
                return
            except Exception:  # noqa: BLE001
                time.sleep(0.2)
        raise RuntimeError("aria2c başlatılamadı")

    def _rpc(self, method: str, *params):
        payload = {"jsonrpc": "2.0", "id": "g", "method": method, "params": [f"token:{self.secret}", *params]}
        r = requests.post(f"http://127.0.0.1:{self.port}/jsonrpc", data=json.dumps(payload), timeout=10,
                          proxies={"http": None, "https": None})
        data = r.json()
        if "error" in data:
            raise RuntimeError(data["error"].get("message"))
        return data["result"]

    # ---- dış arayüz
    def add(self, d: Download) -> Download:
        d.method = method_for(d.url) if d.method == "aria2" else d.method
        if d.hls or (d.method == "aria2" and ".m3u8" in d.url.lower().split("?")[0]):
            d.hls, d.method = True, "hls"
        d.dest_dir.mkdir(parents=True, exist_ok=True)
        with self.lock:
            self.items[d.id] = d
        d.state = "active"
        if d.method == "local":
            # Bilgisayardaki dosya (G-Flix Masaüstü'ne sürüklenen): kopyalanmaz, yerinde kullanılır
            p = local_path(d.url)
            if not p.is_file():
                d.state, d.error = "error", f"Dosya bulunamadı: {p}"
            else:
                d.path, d.done, d.total, d.state = p, p.stat().st_size, p.stat().st_size, "done"
            return d
        if d.method == "aria2":
            self._start_aria2()
            h = browser_headers(d.url, d.headers)
            opts = {"dir": str(d.dest_dir), "out": d.name + ".part", "user-agent": h.pop("User-Agent"),
                    "header": [f"{k}: {v}" for k, v in h.items()]}
            d.gid = self._rpc("aria2.addUri", [d.url], opts)
        else:
            threading.Thread(target=self._run_external, args=(d,), daemon=True).start()
        return d

    def poll(self) -> None:
        with self.poll_lock:   # birden fazla iş parçacığı aynı anda çağırabilir (G-Flix Masaüstü)
            self._poll()

    def _poll(self) -> None:
        for d in list(self.items.values()):
            if d.method != "aria2" or d.state != "active" or not d.gid:
                continue
            try:
                st = self._rpc("aria2.tellStatus", d.gid, ["status", "totalLength", "completedLength", "downloadSpeed", "errorMessage", "files"])
            except Exception as e:  # noqa: BLE001
                d.error = str(e)
                continue
            d.total = int(st.get("totalLength") or 0)
            d.done = int(st.get("completedLength") or 0)
            d.speed = float(st.get("downloadSpeed") or 0)
            if st["status"] == "complete":
                self._finish(d, d.dest_dir / (d.name + ".part"))
            elif st["status"] in ("error", "removed"):
                msg = st.get("errorMessage") or "indirme başarısız"
                if not d.retried and d.state == "active" and st["status"] == "error":
                    # Farklı yöntemle bir kez daha dene (tek bağlantı, tam tarayıcı başlıkları)
                    d.retried, d.method, d.done, d.speed = True, "http", 0, 0
                    self.log(f"  ↻ {d.label}: {msg[:80]} — farklı yöntemle yeniden deneniyor")
                    threading.Thread(target=self._run_external, args=(d,), daemon=True).start()
                    continue
                d.state, d.error = "error", _tr_error(msg)
                if "403" in msg or "401" in msg:
                    d.error += " — Link sadece kendi cihazından inebiliyor olabilir (IP'ye bağlı/süreli). 'Telefon indirsin' modunu dene."

    def _finish(self, d: Download, file: Path) -> None:
        try:
            # Link bir HLS oynatma listesiymiş (uzantısı .png/.mp4 olsa bile): parçalarıyla birlikte yeniden indir
            if d.method not in ("asset", "local") and not d.hls and _is_playlist(file):
                file.unlink(missing_ok=True)
                self.log(f"  ↻ {d.label or d.name}: link bir HLS oynatma listesi, parçalarıyla indiriliyor")
                d.hls, d.method, d.done, d.total, d.state = True, "hls", 0, 0, "active"
                threading.Thread(target=self._run_external, args=(d,), daemon=True).start()
                return
            final = file.with_name(d.name + ".bin")
            file.rename(final)
            d.path = fix_extension(final)
            d.done = d.total = d.path.stat().st_size
            d.speed = 0
            d.state = "done"
        except Exception as e:  # noqa: BLE001
            d.state, d.error = "error", str(e)

    def cancel(self, d: Download) -> None:
        if d.state in ("active", "waiting"):
            d.state, d.error = "error", d.error or "iptal edildi"
            if d.gid and self.proc:
                try:
                    self._rpc("aria2.forceRemove", d.gid)
                except Exception:  # noqa: BLE001
                    pass

    def _run_external(self, d: Download) -> None:
        tmp = d.dest_dir / (d.name + ".dl")
        try:
            if d.method == "asset":
                if not self.asset_fetcher:
                    raise RuntimeError("Telefondan aktarılan dosya bulunamadı")
                self.asset_fetcher(d.url.split(":", 1)[1], tmp, lambda n: setattr(d, "done", d.done + n), d)
                self._finish(d, tmp)
            elif d.method == "http":
                self._http(d, tmp)
            elif d.method == "gdrive":
                import gdown
                watcher = threading.Thread(target=self._watch_size, args=(d, tmp), daemon=True)
                watcher.start()
                out = gdown.download(d.url, str(tmp), quiet=True, fuzzy=True)
                if not out or not tmp.exists():
                    raise RuntimeError("Google Drive dosyası indirilemedi (paylaşım 'linke sahip herkes' olmalı)")
                self._finish(d, tmp)
            elif d.method == "hls":
                self._hls(d)
            else:
                self._ytdlp(d)
        except Exception as e:  # noqa: BLE001
            d.state, d.error = "error", str(e)[:500]

    def _hls(self, d: Download) -> None:
        from . import hls as hls_mod
        from .hls import HlsDownload
        t0, win = time.time(), [0]

        def on_bytes(n, total):
            d.done += n
            d.total = max(total, d.done)
            win[0] += n
            el = time.time() - t0
            d.speed = d.done / el if el > 0 else 0
        h = HlsDownload(d.url, {"User-Agent": DESKTOP_UA, **d.headers}, d.dest_dir / d.name, log=self.log, on_bytes=on_bytes,
                        cancel=lambda: d.state == "error", scope=self.secret if hls_mod.PER_JOB else "")    # aynı anda çok iş: her işin kendi sınırı
        d.path = h.run()
        d.done = d.total = d.path.stat().st_size
        d.speed = 0
        d.state = "done"

    def _http(self, d: Download, tmp: Path) -> None:
        part = d.dest_dir / (d.name + ".part")
        part.unlink(missing_ok=True)
        t0, last_t, last_n = time.time(), time.time(), 0
        with requests.get(d.url, headers=browser_headers(d.url, d.headers), stream=True, timeout=(30, 300), allow_redirects=True) as r:
            if r.status_code >= 400:
                raise RuntimeError(_tr_error(f"status={r.status_code}") + (
                    " — Link sadece kendi cihazından inebiliyor olabilir (IP'ye bağlı/süreli). 'Telefon indirsin' modunu dene."
                    if r.status_code in (401, 403) else ""))
            d.total = int(r.headers.get("Content-Length") or 0)
            with open(tmp, "wb") as f:
                for chunk in r.iter_content(1024 * 1024):
                    f.write(chunk)
                    d.done += len(chunk)
                    now = time.time()
                    if now - last_t >= 1:
                        d.speed = (d.done - last_n) / (now - last_t)
                        last_t, last_n = now, d.done
        self._finish(d, tmp)

    def _watch_size(self, d: Download, path: Path) -> None:
        last, t0 = 0, time.time()
        while d.state == "active":
            time.sleep(1)
            cands = list(path.parent.glob(path.name + "*"))
            size = sum(p.stat().st_size for p in cands if p.exists())
            d.done = size
            d.speed = max(size - last, 0)
            last = size
            if time.time() - t0 > 6 * 3600:
                return

    def _ytdlp(self, d: Download) -> None:
        cookies = os.environ.get("GFLIX_YT_COOKIES_FILE")
        fmt = d.ytdlp_format or "bv*[height<=1080][ext=mp4]+ba[ext=m4a]/bv*[height<=1080]+ba/b[height<=1080]/b"
        prog = ["--progress-template",
                "download:PROG %(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s"]
        cmd = ["yt-dlp", "--no-playlist", "--newline", "--no-part", "-N", "16", "-f", fmt, "--merge-output-format", d.ytdlp_merge,
               "-o", str(d.dest_dir / (d.name + ".%(ext)s")), *prog]
        if cookies and Path(cookies).exists():
            cmd += ["--cookies", cookies]
        cmd.append(d.url)
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
        tail = []
        for line in p.stdout:
            if line.startswith("PROG "):
                parts = line.split()
                num = lambda x: float(x) if x not in ("NA", "None") else 0.0  # noqa: E731
                d.done = int(num(parts[1]))
                d.total = int(num(parts[2]) or num(parts[3]))
                d.speed = num(parts[4]) if len(parts) > 4 else 0
            else:
                tail.append(line.strip())
                del tail[:-15]
        p.wait()
        files = [f for f in d.dest_dir.glob(d.name + ".*") if f.suffix not in (".part", ".ytdl") and f.is_file()]
        if p.returncode != 0 or not files:
            errors = [t for t in tail if t.startswith("ERROR")]
            msg = "\n".join(errors[-3:] or [t for t in tail if not t.startswith("WARNING")][-4:])
            if "Sign in to confirm" in msg or "bot" in msg.lower():
                msg = "YouTube bu sunucuyu bot sandı. YouTube çerezlerini (cookies.txt) ekle ya da fragmanı elle yükle.\n" + msg
            raise RuntimeError(msg or "yt-dlp başarısız")
        d.path = max(files, key=lambda f: f.stat().st_size)
        d.done = d.total = d.path.stat().st_size
        d.state = "done"

    def close(self):
        if self.proc:
            try:
                self._rpc("aria2.shutdown")
            except Exception:  # noqa: BLE001
                pass
            self.proc.terminate()
            self.proc = None


# ------------------------------------------------------------------ bilgisayardan indirme (G-Flix Masaüstü)

DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64; rv:128.0) Gecko/20100101 Firefox/128.0"
BROWSERS = ("firefox", "chrome", "chromium", "brave", "edge", "opera", "vivaldi")


def browser_cookie_header(url: str, browser: str | None = None, log=print) -> tuple[str, str]:
    """Tarayıcının (Firefox/Chrome…) o siteye ait çerezlerini okur -> ("ad=değer; …", tarayıcı).
    1DM'in yaptığı gibi: sitede tarayıcıyla oturum açıksan, aynı oturumla indirilir. Çerezler bu bilgisayardan çıkmaz."""
    from yt_dlp.cookies import extract_cookies_from_browser
    host = (urlparse(url).hostname or "").lower()
    for b in ([browser] if browser else BROWSERS):
        try:
            jar = extract_cookies_from_browser(b, logger=_QuietLogger())
        except Exception:  # noqa: BLE001  tarayıcı kurulu değil
            continue
        pairs = []
        for c in jar:
            dom = (c.domain or "").lstrip(".").lower()
            if dom and (host == dom or host.endswith("." + dom)):
                pairs.append(f"{c.name}={c.value}")
        if pairs:
            return "; ".join(pairs), b
    return "", ""


class _QuietLogger:
    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg, only_once=False): pass
    def error(self, msg): pass


def parse_curl(text: str) -> tuple[str, dict]:
    """Tarayıcı geliştirici araçlarındaki "cURL olarak kopyala" komutundan link ve başlıklar."""
    import shlex
    text = text.replace("\\\n", " ").replace("^\n", " ").replace("^", "")   # bash / Windows satır devamları
    tokens = shlex.split(text)
    url, headers = "", {}
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if t in ("-H", "--header") and i + 1 < len(tokens):
            k, _, v = tokens[i + 1].partition(":")
            if k.strip().lower() not in ("range", "accept-encoding", "content-length", "host"):
                headers[k.strip()] = v.strip()
            i += 2
            continue
        if t in ("-b", "--cookie") and i + 1 < len(tokens):
            headers["Cookie"] = tokens[i + 1]
            i += 2
            continue
        if t in ("-A", "--user-agent") and i + 1 < len(tokens):
            headers["User-Agent"] = tokens[i + 1]
            i += 2
            continue
        if t in ("-e", "--referer") and i + 1 < len(tokens):
            headers["Referer"] = tokens[i + 1]
            i += 2
            continue
        if t.startswith("http://") or t.startswith("https://"):
            url = t
        elif t == "--url" and i + 1 < len(tokens):
            url = tokens[i + 1]
            i += 1
        i += 1
    if not url:
        raise ValueError("cURL komutunda link bulunamadı")
    # header adlarını düzgün yaz (user-agent -> User-Agent)
    fixed = {"-".join(p.capitalize() for p in k.split("-")): v for k, v in headers.items()}
    return url, fixed


def check_link(url: str, headers: dict | None = None) -> tuple[int, int, str]:
    """Linki dener (ilk bayt): (HTTP kodu, boyut, içerik türü)."""
    h = browser_headers(url, {"User-Agent": DESKTOP_UA, **(headers or {})})
    h["Range"] = "bytes=0-0"
    with requests.get(url, headers=h, stream=True, timeout=(20, 30), allow_redirects=True) as r:
        size = 0
        cr = r.headers.get("Content-Range", "")
        if "/" in cr:
            size = int(cr.rsplit("/", 1)[1] or 0) if cr.rsplit("/", 1)[1].isdigit() else 0
        size = size or int(r.headers.get("Content-Length") or 0)
        return r.status_code, size, r.headers.get("Content-Type", "")


def fetch_link(dl: "Downloader", url: str, dest_dir: Path, name: str, label: str = "", headers: dict | None = None,
               browser: str = "auto", log=print, on_progress=None, cancel=lambda: False) -> Path:
    """Bir linki bu bilgisayara indirir (aria2, 16 bağlantı). Site izin vermezse (403/401) tarayıcının o siteye ait
    çerezleriyle (1DM gibi) bir kez daha dener. browser: auto | firefox | chrome | … | off"""
    base = {"User-Agent": DESKTOP_UA, **(headers or {})}
    tried_cookies = "Cookie" in base or browser == "off" or method_for(url) not in ("aria2", "http")
    attempt = 0
    while True:
        attempt += 1
        d = Download(url=url, dest_dir=dest_dir, name=name if attempt == 1 else f"{name}.{attempt}", label=label or name, headers=dict(base))
        dl.add(d)
        while d.state in ("waiting", "active"):
            if cancel():
                dl.cancel(d)
                raise RuntimeError("iptal edildi")
            dl.poll()
            if on_progress:
                on_progress(d)
            time.sleep(0.5)
        if on_progress:
            on_progress(d)
        if d.state == "done" and d.path:
            return Path(d.path)
        if not tried_cookies:
            tried_cookies = True
            cookie, b = browser_cookie_header(url, None if browser == "auto" else browser, log)
            if cookie:
                log(f"  ↻ {label}: site izin vermedi, {b} çerezleriyle (tarayıcıdaki oturumunla) yeniden deneniyor")
                base["Cookie"] = cookie
                continue
            log(f"  {label}: tarayıcılarda bu siteye ait çerez bulunamadı")
        raise RuntimeError((d.error or "indirilemedi") + "\nÇözüm: siteyi bu bilgisayardaki tarayıcıda açıp oturum aç (gerekirse), sonra tekrar dene. "
                           "Olmazsa tarayıcıda F12 → Ağ (Network) → indirme isteğine sağ tık → 'cURL olarak kopyala' ve link kutusuna yapıştır. "
                           "Ya da dosyayı tarayıcıyla indirip buraya sürükle.")
