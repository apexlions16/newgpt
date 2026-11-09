"""
YouTube linkinden anlık yayın (fragmanlar ve YouTube'daki içerikler). Hiçbir şey diske kaydedilmez.

Bilgisayar yt-dlp ile o anki yayın adreslerini alır:
  • YouTube HLS veriyorsa (görüntü + ses tek listede): yayin.py'deki HLS aracısı aynen kullanılır.
  • Vermiyorsa en iyi görüntü + en iyi ses ffmpeg ile kopyalanarak (yeniden kodlamadan) HLS'e çevrilir; parçalar
    sadece bellekte (/dev/shm) durur ve izleme bitince silinir.
Site korumasını aşan bir şey yapılmaz: yt-dlp YouTube'un normal oynatıcı yanıtlarını okur. YouTube "bot değilim"
kontrolü isterse hata verilir (isteğe bağlı GFLIX_YT_COOKIES ile kullanıcının kendi tarayıcı çerezleri verilebilir).
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

_ID = re.compile(r"(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/|live/|v/)|youtu\.be/|youtube-nocookie\.com/embed/)([A-Za-z0-9_-]{11})")


def video_id(s: str | None) -> str | None:
    s = (s or "").strip()
    if re.fullmatch(r"[A-Za-z0-9_-]{11}", s):
        return s
    m = _ID.search(s)
    return m.group(1) if m else None


def is_youtube(url: str | None) -> bool:
    return bool(url and re.search(r"(youtube\.com|youtu\.be|youtube-nocookie\.com)/", url) and video_id(url))


def probe(vid: str, log=print, timeout: int = 90) -> dict:
    """yt-dlp -J: biçimler ve adresler (adresler birkaç saat geçerli)."""
    cmd = [sys.executable, "-m", "yt_dlp", "-J", "--no-playlist", "--no-warnings"]
    cookies = os.environ.get("GFLIX_YT_COOKIES", "").strip()
    if cookies and Path(cookies).expanduser().is_file():
        cmd += ["--cookies", str(Path(cookies).expanduser())]
    cmd.append(f"https://www.youtube.com/watch?v={vid}")
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        err = (r.stderr.strip().splitlines() or ["?"])[-1]
        if "not a bot" in err or "Sign in" in err:
            err = "YouTube 'bot değilim' kontrolü istedi. Bilgisayarda ~/.config/gflix/yayin.env içine GFLIX_YT_COOKIES=<çerez dosyası> verilebilir."
        raise RuntimeError(f"YouTube videosu açılamadı: {err[:300]}")
    log(f"  ▶ YouTube çözüldü ({time.time() - t0:.1f} sn)")
    return json.loads(r.stdout)


def _has(f: dict, k: str) -> bool:
    v = f.get(k)
    return bool(v) and v != "none"


def is_playlist(url: str | None) -> bool:
    """youtube.com/playlist?list=… (izleme sayfasındaki &list= tek video sayılır)."""
    return bool(url and re.search(r"youtube\.com/playlist\?(?:.*&)?list=[A-Za-z0-9_-]+", url))


def playlist(url: str, timeout: int = 120) -> list[tuple[str, str]]:
    """Oynatma listesindeki videolar (sırasıyla): [(kimlik, başlık)]. Videolar açılmaz, sadece liste okunur."""
    cmd = [sys.executable, "-m", "yt_dlp", "-J", "--flat-playlist", "--no-warnings"]
    cookies = os.environ.get("GFLIX_YT_COOKIES", "").strip()
    if cookies and Path(cookies).expanduser().is_file():
        cmd += ["--cookies", str(Path(cookies).expanduser())]
    r = subprocess.run(cmd + [url], capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"Oynatma listesi açılamadı: {(r.stderr.strip().splitlines() or ['?'])[-1][:200]}")
    d = json.loads(r.stdout)
    return [(e["id"], e.get("title") or "") for e in d.get("entries") or [] if e and video_id(e.get("id"))]


def search(query: str, n: int = 8, timeout: int = 60) -> list[dict]:
    """YouTube araması (videolar açılmaz): [{id, title, duration}]"""
    cmd = [sys.executable, "-m", "yt_dlp", "-J", "--flat-playlist", "--no-warnings"]
    cookies = os.environ.get("GFLIX_YT_COOKIES", "").strip()
    if cookies and Path(cookies).expanduser().is_file():
        cmd += ["--cookies", str(Path(cookies).expanduser())]
    r = subprocess.run(cmd + [f"ytsearch{n}:{query}"], capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"YouTube araması yapılamadı: {(r.stderr.strip().splitlines() or ['?'])[-1][:200]}")
    d = json.loads(r.stdout)
    return [{"id": e["id"], "title": e.get("title") or "", "duration": e.get("duration") or 0}
            for e in d.get("entries") or [] if e and video_id(e.get("id"))]


def audio_only(info: dict) -> dict:
    """Sadece ses (tema müziği için): en iyi m4a/AAC; yoksa herhangi bir ses biçimi. {url, headers, ext}"""
    fmts = [f for f in info.get("formats") or [] if str(f.get("protocol") or "") in ("https", "http")
            and _has(f, "acodec") and not _has(f, "vcodec") and f.get("url")]
    if not fmts:
        raise RuntimeError("YouTube videosunda ayrı ses bulunamadı")
    f = max(fmts, key=lambda f: (str(f.get("acodec") or "").startswith("mp4a") or f.get("ext") == "m4a", lang_rank(f), f.get("abr") or f.get("tbr") or 0))
    return {"url": f["url"], "headers": f.get("http_headers") or {}, "ext": f.get("ext") or "m4a"}


def latest(url: str, n: int = 20, timeout: int = 120) -> list[dict]:
    """Kanalın (ya da oynatma listesinin) son videoları, videolar açılmadan: [{id, title, duration}].
    Kanal ana adresi verilirse 'Videolar' sekmesi okunur."""
    u = url.strip()
    if re.search(r"youtube\.com/(@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+)/?$", u):
        u = u.rstrip("/") + "/videos"
    cmd = [sys.executable, "-m", "yt_dlp", "-J", "--flat-playlist", "--no-warnings", "--playlist-end", str(n)]
    cookies = os.environ.get("GFLIX_YT_COOKIES", "").strip()
    if cookies and Path(cookies).expanduser().is_file():
        cmd += ["--cookies", str(Path(cookies).expanduser())]
    r = subprocess.run(cmd + [u], capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"Kanal/liste okunamadı: {(r.stderr.strip().splitlines() or ['?'])[-1][:200]}")
    d = json.loads(r.stdout)
    return [{"id": e["id"], "title": e.get("title") or "", "duration": e.get("duration") or 0}
            for e in d.get("entries") or [] if e and video_id(e.get("id"))]


def lang_rank(f: dict) -> int:
    """Videonun kendi (orijinal) sesi önce: YouTube otomatik dublaj kanalları (Almanca, İspanyolca …) ekleyebiliyor.
    yt-dlp orijinal sese language_preference=10, varsayılana 5, dublajlara -1 verir; 'original' notu da bakılır."""
    note = str(f.get("format_note") or "").lower()
    if "original" in note:
        return 20
    lp = f.get("language_preference")
    if lp is None:
        return 0 if not f.get("language") else -1
    return int(lp)


def choose(info: dict) -> dict:
    """{"kind": "hls", url, headers, height, variant} ya da {"kind": "mux", video, audio, headers, height}.
    En yüksek çözünürlük (TV/telefon uyumu için H.264 öncelikli, 1080p üstü sadece H.264 ise)."""
    fmts = info.get("formats") or []

    def vkey(f):
        avc = str(f.get("vcodec") or "").startswith(("avc1", "h264"))
        h = f.get("height") or 0
        return (lang_rank(f), avc or h <= 1080, h if avc or h <= 1080 else 0, avc, f.get("tbr") or 0)
    hls = [f for f in fmts if str(f.get("protocol") or "").startswith("m3u8") and _has(f, "vcodec") and _has(f, "acodec")]
    if hls:
        f = max(hls, key=vkey)
        v = {"BANDWIDTH": str(int((f.get("tbr") or 3000) * 1000))}
        if f.get("width") and f.get("height"):
            v["RESOLUTION"] = f"{f['width']}x{f['height']}"
        return {"kind": "hls", "url": f["url"], "headers": f.get("http_headers") or {}, "height": f.get("height") or 0, "variant": v}
    vids = [f for f in fmts if str(f.get("protocol") or "") in ("https", "http") and _has(f, "vcodec") and not _has(f, "acodec")
            and str(f.get("vcodec") or "").startswith(("avc1", "h264"))]
    auds = [f for f in fmts if str(f.get("protocol") or "") in ("https", "http") and _has(f, "acodec") and not _has(f, "vcodec")
            and (str(f.get("acodec") or "").startswith("mp4a") or f.get("ext") == "m4a")]
    if vids and auds:
        # Ayrı görüntü + ses (DASH): dizinlerinden tam uzunlukta HLS üretilir (ileri/geri sarılabilir); olmazsa birleştirme
        v = max(vids, key=lambda f: (f.get("height") or 0, f.get("tbr") or 0))
        a = max(auds, key=lambda f: (lang_rank(f), f.get("abr") or f.get("tbr") or 0))
        return {"kind": "dash", "video": v["url"], "audio": a["url"], "headers": v.get("http_headers") or {}, "height": v.get("height") or 0,
                "width": v.get("width") or 0, "vcodec": v.get("vcodec") or "", "acodec": a.get("acodec") or "",
                "tbr": (v.get("tbr") or 0) + (a.get("abr") or a.get("tbr") or 0), "lang": a.get("language") or "und"}
    both = [f for f in fmts if str(f.get("protocol") or "") in ("https", "http") and _has(f, "vcodec") and _has(f, "acodec")]
    if both:
        f = max(both, key=lambda f: (lang_rank(f), f.get("height") or 0))
        return {"kind": "mux", "video": f["url"], "audio": None, "headers": f.get("http_headers") or {}, "height": f.get("height") or 0}
    raise RuntimeError("YouTube videosunda oynatılabilir biçim bulunamadı")


def parse_sidx(data: bytes) -> tuple[int, list[tuple[int, int, float]]] | None:
    """Parçalı MP4'ün başından: (init uzunluğu = ftyp+moov, [(parça başı, boyut, süre sn)…]). Dizin (sidx) yoksa None."""
    import struct
    off = 0
    init_end = None
    while off + 8 <= len(data):
        size, typ = struct.unpack(">I4s", data[off:off + 8])
        hdr = 8
        if size == 1 and off + 16 <= len(data):
            size = struct.unpack(">Q", data[off + 8:off + 16])[0]
            hdr = 16
        if size < hdr:
            return None
        if typ == b"sidx":
            if init_end is None:
                init_end = off
            b = data[off + hdr:off + size]
            if len(b) < off + size - off - hdr or len(b) < 12:
                return None
            ver = b[0]
            timescale = struct.unpack(">I", b[8:12])[0]
            if ver == 0:
                first = struct.unpack(">I", b[16:20])[0]
                pos = 20
            else:
                first = struct.unpack(">Q", b[20:28])[0]
                pos = 28
            count = struct.unpack(">H", b[pos + 2:pos + 4])[0]
            pos += 4
            at = off + size + first
            segs = []
            for _ in range(count):
                if pos + 12 > len(b):
                    return None
                ref, dur = struct.unpack(">II", b[pos:pos + 8])
                pos += 12
                if ref & 0x80000000:          # başka bir dizine işaret (iç içe): desteklenmiyor
                    return None
                n = ref & 0x7FFFFFFF
                segs.append((at, n, dur / timescale if timescale else 0.0))
                at += n
            return (init_end, segs) if segs else None
        if typ in (b"moof", b"mdat"):
            return None                       # dizinden önce veri başladı: dizin yok
        if typ == b"moov":
            init_end = off + size
        off += size
    return None


def dash_playlist(url: str, headers: dict, timeout: int = 20) -> str | None:
    """Ayrı görüntü/ses dosyası (YouTube DASH) → tam uzunlukta, bayt aralıklı HLS listesi (fMP4). Dosya inmez: sadece
    başındaki dizin okunur; oynatıcı parçaları bilgisayar üzerinden aralıklarla ister."""
    import math
    import requests
    try:
        r = requests.get(url, headers={**headers, "Range": "bytes=0-262143"}, timeout=timeout)
    except requests.RequestException:
        return None
    if r.status_code not in (200, 206):
        return None
    parsed = parse_sidx(r.content[:262144])
    if not parsed:
        return None
    init_len, segs = parsed
    target = max(1, math.ceil(max(d for _, _, d in segs)))
    out = ["#EXTM3U", "#EXT-X-VERSION:7", f"#EXT-X-TARGETDURATION:{target}", "#EXT-X-MEDIA-SEQUENCE:0",
           "#EXT-X-PLAYLIST-TYPE:VOD", "#EXT-X-INDEPENDENT-SEGMENTS", f'#EXT-X-MAP:URI="{url}",BYTERANGE="{init_len}@0"']
    for at, n, d in segs:
        out += [f"#EXTINF:{d:.3f},", f"#EXT-X-BYTERANGE:{n}@{at}", url]
    out.append("#EXT-X-ENDLIST")
    return "\n".join(out) + "\n"


def ram_dir() -> Path:
    """Parçalar için bellek (tmpfs). Yoksa geçici klasör (yine izleme bitince silinir)."""
    shm = Path("/dev/shm")
    base = shm if shm.is_dir() and os.access(shm, os.W_OK) else Path(tempfile.gettempdir())
    d = base / "gflix-yt"
    d.mkdir(exist_ok=True)
    return d


class Muxer:
    """En iyi görüntü + ses → ffmpeg (-c copy) → bellekte HLS (event listesi). İzleme bitince kill + sil."""

    def __init__(self, sid: str, choice: dict, log=print):
        self.sid = sid
        self.dir = Path(tempfile.mkdtemp(prefix=f"{sid[:8]}-", dir=ram_dir()))
        self.created = self.used = time.time()
        hdr = "".join(f"{k}: {v}\r\n" for k, v in (choice.get("headers") or {}).items())
        cmd = ["ffmpeg", "-loglevel", "error", "-nostdin"]
        for u in [choice["video"]] + ([choice["audio"]] if choice.get("audio") else []):
            cmd += (["-headers", hdr] if hdr else []) + ["-i", u]
        cmd += ["-map", "0:v:0", "-map", "1:a:0" if choice.get("audio") else "0:a:0?", "-c", "copy",
                "-f", "hls", "-hls_time", "4", "-hls_list_size", "0", "-hls_playlist_type", "event",
                "-hls_segment_filename", str(self.dir / "s%05d.ts"), str(self.dir / "index.m3u8")]
        if not shutil.which("ffmpeg"):
            raise RuntimeError("ffmpeg yok (sudo apt install ffmpeg)")
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        threading.Thread(target=self._drain, daemon=True).start()
        self.err = ""
        log(f"  🎞 YouTube görüntü+ses birleştiriliyor (bellekte, {choice.get('height') or '?'}p)")

    def _drain(self):
        try:
            self.err = (self.proc.stderr.read() or b"").decode("utf-8", "replace")[-500:]
        except Exception:  # noqa: BLE001
            pass

    def playlist(self, wait: float = 25.0) -> str:
        """İlk parça hazır olana kadar bekle; listenin o anki hâli."""
        end = time.time() + wait
        idx = self.dir / "index.m3u8"
        while time.time() < end:
            if idx.exists():
                t = idx.read_text(errors="replace")
                if "#EXTINF" in t:
                    return t
            if self.proc.poll() is not None and not idx.exists():
                raise RuntimeError(f"ffmpeg durdu: {self.err[-200:]}")
            time.sleep(0.3)
        raise RuntimeError("YouTube yayını zamanında başlamadı")

    def file(self, name: str) -> Path | None:
        if not re.fullmatch(r"s\d{5}\.ts|index\.m3u8", name):
            return None
        p = self.dir / name
        return p if p.is_file() else None

    def close(self) -> None:
        try:
            self.proc.kill()
        except Exception:  # noqa: BLE001
            pass
        shutil.rmtree(self.dir, ignore_errors=True)
