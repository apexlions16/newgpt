"""
İndirmeden önce boyut tahmini (onay ekranı için): "toplam kaç GB inecek, işlerken en fazla ne kadar boş alan gerekir".

  • HLS: parça listesindeki birkaç parçanın boyutu (Range isteğiyle) × parça sayısı
  • YouTube: yt-dlp'nin bildirdiği en iyi görüntü + ses boyutu
  • Düz link: sunucunun bildirdiği boyut
"""
from __future__ import annotations

import json
import subprocess
from urllib.parse import urljoin

import requests

from .hls import is_playlist, parse_media
from .resolve import http_get

UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"


def is_youtube(url: str) -> bool:
    from urllib.parse import urlparse
    h = (urlparse(url).hostname or "").lower()
    return h.endswith("youtube.com") or h.endswith("youtu.be")


def _size_of(url: str, headers: dict) -> int:
    try:
        r = requests.get(url, headers={**headers, "Range": "bytes=0-0"}, timeout=20, stream=True)
        cr = r.headers.get("Content-Range", "")
        r.close()
        if "/" in cr and cr.rsplit("/", 1)[1].isdigit():
            return int(cr.rsplit("/", 1)[1])
        return int(r.headers.get("Content-Length") or 0) if r.status_code == 200 else 0
    except Exception:  # noqa: BLE001
        return 0


def _master_bandwidth(url: str) -> int:
    """Bu görüntü listesinin, daha önce okunmuş ana listedeki BANDWIDTH değeri (bilinmiyorsa 0)."""
    try:
        from urllib.parse import urljoin
        from .hls import attrs
        from .resolve import _MASTERS
        m = _MASTERS.get(url)
        if not m:
            return 0
        base, text = m
        lines = text.splitlines()
        for i, line in enumerate(lines):
            if line.startswith("#EXT-X-STREAM-INF:"):
                nxt = next((x.strip() for x in lines[i + 1:] if x.strip() and not x.startswith("#")), None)
                if nxt and urljoin(base, nxt) == url:
                    return int(attrs(line).get("BANDWIDTH", "0") or 0)
    except Exception:  # noqa: BLE001
        pass
    return 0


def hls_size(url: str, headers: dict) -> int:
    h = {"User-Agent": UA, **headers}
    text = http_get(url, headers=h, timeout=30).content.decode("utf-8", "replace")
    if not is_playlist(text):
        return 0
    if "#EXT-X-STREAM-INF" in text:     # ana liste: en iyi görüntü
        from .hls import parse_master
        best, audio = parse_master(text, url)
        return (hls_size(best, headers) if best else 0) + (hls_size(audio, headers) if audio else 0)
    segs, _ = parse_media(text, url)
    if not segs:
        return 0
    bw = _master_bandwidth(url)
    if bw > 0:      # yakalamada okunan ana listede bit hızı yazılı: siteye ek istek atmadan bit hızı × süre
        return int(bw / 8 * sum(sg.dur or 0 for sg in segs))
    picks = sorted({0, len(segs) // 3, len(segs) // 2, (2 * len(segs)) // 3, len(segs) - 1})
    sizes = [s for s in (_size_of(segs[i].url, h) for i in picks) if s > 0]
    return int(sum(sizes) / len(sizes) * len(segs)) if sizes else 0


def youtube_size(url: str) -> int:
    r = subprocess.run(["yt-dlp", "-J", "--no-playlist", "-f", "bv*+ba/b", url], capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        return 0
    d = json.loads(r.stdout)
    fmts = d.get("requested_formats") or [d]
    return int(sum((f.get("filesize") or f.get("filesize_approx") or 0) for f in fmts))


def file_size(url: str, headers: dict, hls: bool = False) -> int:
    """Bir satırın tahmini boyutu (bayt); bilinemezse 0."""
    try:
        if url.lower().startswith(("asset:", "file://")):
            return 0
        if is_youtube(url):
            return youtube_size(url)
        if hls or ".m3u8" in url.lower().split("?")[0]:
            return hls_size(url, headers)
        n = _size_of(url, {"User-Agent": UA, **headers})
        if 0 < n < 64 * 1024:      # küçük yanıt: belki kılıklı HLS listesi
            try:
                body = http_get(url, headers={"User-Agent": UA, **headers}, timeout=20, limit=64 * 1024).content
                if is_playlist(body):
                    return hls_size(url, headers)
            except Exception:  # noqa: BLE001
                pass
        return n
    except Exception:  # noqa: BLE001
        return 0
