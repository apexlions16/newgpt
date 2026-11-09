"""
HLS (m3u8) indirici: sitelerin oynatıcılarının kullandığı parça listeleri.

- Liste ve parçalar .png / .jpg / .mp4 kılığında olabilir (içerikten tanınır).
- Ana liste (master) verilirse en yüksek çözünürlüklü görüntü seçilir; ayrı ses grubu varsa varsayılan ses de indirilip eklenir.
- Parçalar 16 paralel bağlantıyla iner, kopan parça yeniden denenir; AES-128 şifreli listeler çözülür.
- Çıktı: parçalar sırayla birleştirilmiş .ts (ya da fMP4 ise .mp4) — orijinal zaman damgaları korunur, böylece aynı yayının
  görüntüsü ve dil sesleri sonradan kaymadan birleştirilir.
"""
from __future__ import annotations

import re
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urljoin

import requests

ATTR = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')


def attrs(line: str) -> dict:
    return {k: v.strip('"') for k, v in ATTR.findall(line.split(":", 1)[1] if ":" in line else "")}


def is_playlist(text: str | bytes) -> bool:
    head = text[:64] if isinstance(text, bytes) else text[:64].encode("utf-8", "ignore")
    return head.lstrip(b"\xef\xbb\xbf \r\n\t").startswith(b"#EXTM3U")


class Segment:
    __slots__ = ("url", "key_url", "iv", "seq", "dur")

    def __init__(self, url, key_url, iv, seq, dur):
        self.url, self.key_url, self.iv, self.seq, self.dur = url, key_url, iv, seq, dur


def parse_media(text: str, base: str) -> tuple[list[Segment], str | None]:
    """Parça listesi ve (varsa) fMP4 başlangıç parçası (EXT-X-MAP)."""
    segs: list[Segment] = []
    key_url = iv = init = None
    seq = 0
    dur = 0.0
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            seq = int(line.split(":", 1)[1] or 0)
        elif line.startswith("#EXT-X-KEY:"):
            a = attrs(line)
            method = a.get("METHOD", "NONE").upper()
            if method == "NONE":
                key_url = iv = None
            elif method == "AES-128":
                key_url = urljoin(base, a["URI"])
                iv = a.get("IV")
            else:
                raise RuntimeError(f"Bu yayın {method} ile korunuyor (DRM) — indirilemez.")
        elif line.startswith("#EXT-X-MAP:"):
            init = urljoin(base, attrs(line)["URI"])
        elif line.startswith("#EXTINF:"):
            try:
                dur = float(line.split(":", 1)[1].split(",")[0])
            except ValueError:
                dur = 0.0
        elif line and not line.startswith("#"):
            segs.append(Segment(urljoin(base, line), key_url, iv, seq, dur))
            seq += 1
    return segs, init


def parse_master(text: str, base: str) -> tuple[str | None, str | None]:
    """(en iyi görüntü listesi, varsayılan ses listesi) — ses ayrı grupta değilse ikincisi None."""
    best, best_bw, group = None, -1, None
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            a = attrs(line)
            bw = int(a.get("BANDWIDTH", "0") or 0)
            res = a.get("RESOLUTION", "0x0").split("x")
            score = int(res[-1]) * 10**9 + bw if res[-1].isdigit() else bw
            nxt = next((l.strip() for l in lines[i + 1:] if l.strip() and not l.startswith("#")), None)
            if nxt and score > best_bw:
                best, best_bw, group = urljoin(base, nxt), score, a.get("AUDIO")
    audio = None
    if group:
        rends = [attrs(l) for l in lines if l.startswith("#EXT-X-MEDIA:") and "TYPE=AUDIO" in l]
        rends = [r for r in rends if r.get("GROUP-ID") == group and r.get("URI")]
        pick = next((r for r in rends if r.get("DEFAULT") == "YES"), rends[0] if rends else None)
        audio = urljoin(base, pick["URI"]) if pick else None
    return best, audio


class HostLimit:
    """
    Siteye göre uyarlanan eş zamanlı istek sınırı (aynı bilgisayardaki bütün HLS indirmeleri paylaşır: görüntü + sesler).
    429/503 gelince sınır yarıya iner ve site "bekle" dediği kadar (Retry-After) herkes bekler; sorunsuz giden
    parçalarla sınır yavaşça yeniden artar. Böylece hız, sitenin izin verdiği en yüksek düzeyde kalır.
    """
    def __init__(self, limit: int):
        self.max = self.limit = limit
        self.active = 0
        self.ok = 0
        self.until = 0.0
        self.last_cut = 0.0
        self.cv = threading.Condition()

    def acquire(self) -> None:
        with self.cv:
            while self.active >= self.limit or time.time() < self.until:
                self.cv.wait(0.5)
            self.active += 1

    def release(self, throttled: bool, wait: float = 0.0) -> int | None:
        """throttled ise yeni sınırı döndürür (kayıt için)."""
        with self.cv:
            self.active -= 1
            changed = None
            if throttled:
                self.ok = 0
                new = max(1, self.limit // 2)
                if new != self.limit and time.time() - self.last_cut > 5:   # aynı anda gelen 429'lar sınırı bir kez düşürsün
                    self.limit = changed = new
                    self.last_cut = time.time()
                self.until = max(self.until, time.time() + wait)
            else:
                self.ok += 1
                if self.ok >= self.limit * 4 and self.limit < self.max:
                    self.limit += 1
                    self.ok = 0
            self.cv.notify_all()
            return changed


_LIMITS: dict[str, HostLimit] = {}
# Telefonun yakalarken indirdiği listeler (adres → metin): bazı siteler liste linkini bir kez ya da sadece o cihaza verir
# (bilgisayar aynı linki isteyince 403); liste metni telefondan gelir, parçalar her zamanki gibi indirilir
PRELOADED: dict[str, str] = {}

# Aynı siteden birden çok iş aynı anda inerken True: her iş kendi sınırını kullanır (işçi ayarlar). False: eski davranış
PER_JOB = False
_LIMITS_LOCK = threading.Lock()


def host_limit(url: str, default: int = 16, scope: str = "") -> HostLimit:
    """Site + iş başına sınır: aynı işin bütün yayınları (görüntü + sesler) paylaşır, ayrı işler ayrı sınır kullanır
    (1DM'deki gibi her indirmenin kendi payı)."""
    from urllib.parse import urlparse
    host = (urlparse(url).hostname or "").lower() + ("|" + scope if scope else "")
    with _LIMITS_LOCK:
        if host not in _LIMITS:
            _LIMITS[host] = HostLimit(default)
        return _LIMITS[host]


class HlsDownload:
    def __init__(self, url: str, headers: dict, out_stem: Path, log=print, on_bytes=None, cancel=lambda: False, threads: int = 16,
                 scope: str = ""):
        self.url, self.headers, self.out_stem, self.log = url, headers, out_stem, log
        self.scope = scope
        self.on_bytes = on_bytes or (lambda n, total: None)
        self.cancel, self.threads = cancel, threads
        self.s = requests.Session()
        self.s.headers.update(headers)
        # Bağlantılar açık tutulur (her küçük parça için yeniden TLS el sıkışması yok): havuz iş parçacığı sayısı kadar
        adapter = requests.adapters.HTTPAdapter(pool_connections=8, pool_maxsize=max(threads, 16) * 2, max_retries=0)
        self.s.mount("https://", adapter)
        self.s.mount("http://", adapter)
        self._throttle_logged = False
        self._long_ra_logged = False
        self._keys: dict[str, bytes] = {}
        self._lock = threading.Lock()

    def _get(self, url: str, tries: int = 6) -> bytes:
        """Parçayı indir. 429/503 (site hız sınırı) kalıcı hata değildir: sınır düşürülür, beklenir, yeniden denenir
        (bir parça için en fazla ~20 dk). Diğer ağ hatalarında 6 deneme."""
        last = None
        errors = 0
        throttle_wait = 0.0
        lim = host_limit(url, self.threads, self.scope)
        while True:
            if self.cancel():
                raise RuntimeError("iptal edildi")
            lim.acquire()
            throttled, wait = False, 0.0
            try:
                r = self.s.get(url, timeout=60)
                if r.status_code in (429, 503):
                    throttled = True
                    ra = r.headers.get("Retry-After", "")
                    backoff = min(60.0, 5.0 * (2 ** min(throttle_wait // 30, 4)))     # 5, 10, 20, 40, 60 sn
                    wait = float(ra) if ra.replace(".", "", 1).isdigit() else min(60.0, 2.0 * (2 ** min(throttle_wait // 30, 5)))
                    if wait > 60:
                        # Bazı siteler "600 sn bekle" der ama çok daha önce yeniden kabul eder (1DM de beklemez):
                        # kısa bekle, bağlantı sayısı zaten yarıya iniyor; site yine reddederse bekleme 60 sn'ye kadar uzar
                        if not self._long_ra_logged:
                            self._long_ra_logged = True
                            self.log(f"  ℹ️ site {wait:.0f} sn beklemeyi istedi; {backoff:.0f} sn sonra daha az bağlantıyla yeniden denenecek")
                        wait = backoff
                    r.close()
                else:
                    if r.status_code in (401, 403, 404, 410):
                        raise RuntimeError(f"HTTP {r.status_code} ({url[:80]}) — link süresi dolmuş ya da çerez/Referer gerekli")
                    r.raise_for_status()
                    return r.content
            except RuntimeError:
                raise
            except Exception as e:  # noqa: BLE001
                last = e
                errors += 1
            finally:
                changed = lim.release(throttled, wait)
                if throttled and (changed or not self._throttle_logged):
                    self._throttle_logged = True
                    self.log(f"  ⏳ site hız sınırı (429): {wait:.0f} sn bekleniyor · eş zamanlı istek sayısı: {lim.limit}")
            if throttled:
                throttle_wait += wait
                if throttle_wait > 20 * 60:
                    raise RuntimeError(f"Site 20 dakikadır 'çok fazla istek' (429) diyor: {url[:80]}")
                continue
            if errors >= tries:
                raise RuntimeError(f"Parça inmedi: {last}")
            time.sleep(min(2 ** errors, 20))

    def _key(self, url: str) -> bytes:
        with self._lock:
            if url not in self._keys:
                self._keys[url] = self._get(url)
            return self._keys[url]

    def _segment(self, seg: Segment) -> bytes:
        data = self._get(seg.url)
        if seg.key_url:
            try:
                from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
            except ImportError:   # eski kurulum: bir kerelik kur
                import subprocess as sp, sys
                sp.run([sys.executable, "-m", "pip", "install", "-q", "cryptography"], check=True)
                from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
            iv = bytes.fromhex(seg.iv[2:] if seg.iv.lower().startswith("0x") else seg.iv) if seg.iv else seg.seq.to_bytes(16, "big")
            dec = Cipher(algorithms.AES(self._key(seg.key_url)), modes.CBC(iv)).decryptor()
            data = dec.update(data) + dec.finalize()
            data = data[: -data[-1]] if data and 1 <= data[-1] <= 16 else data   # PKCS7
        return data

    def media(self, url: str, out_stem: Path) -> Path:
        text = PRELOADED.get(url) or self._get(url).decode("utf-8", "replace")
        if not is_playlist(text):
            raise RuntimeError("Link bir HLS listesi değil")
        segs, init = parse_media(text, url)
        if not segs:
            raise RuntimeError("HLS listesinde parça yok")
        out = Path(str(out_stem) + (".mp4" if init else ".ts"))
        tmp = Path(str(out) + ".part")
        done_bytes = [0]
        est = [0]

        def one(i_seg):
            i, seg = i_seg
            data = self._segment(seg)
            with self._lock:
                done_bytes[0] += len(data)
                n = i + 1
                est[0] = int(done_bytes[0] / max(n, 1) * len(segs)) if n > 3 else 0
            self.on_bytes(len(data), est[0])
            return i, data

        pending: dict[int, bytes] = {}
        nxt = 0
        with open(tmp, "wb") as f, ThreadPoolExecutor(self.threads) as ex:
            if init:
                f.write(self._get(init))
            for i, data in ex.map(one, enumerate(segs)):
                pending[i] = data
                while nxt in pending:
                    f.write(pending.pop(nxt))
                    nxt += 1
        tmp.rename(out)
        return out

    def run(self) -> Path:
        text = PRELOADED.get(self.url) or self._get(self.url).decode("utf-8", "replace")
        if not is_playlist(text):
            raise RuntimeError("Link bir HLS listesi değil")
        if "#EXT-X-STREAM-INF" not in text:
            return self.media(self.url, self.out_stem)
        video, audio = parse_master(text, self.url)
        if not video:
            raise RuntimeError("Ana listede görüntü bulunamadı")
        v = self.media(video, self.out_stem.with_name(self.out_stem.name + ".v"))
        if not audio:
            return v
        a = self.media(audio, self.out_stem.with_name(self.out_stem.name + ".a"))
        out = Path(str(self.out_stem) + ".mkv")
        mux_aligned(v, [(a, "und")], out, self.log)
        v.unlink(missing_ok=True)
        a.unlink(missing_ok=True)
        return out


# ------------------------------------------------------------------ görüntü + dil seslerini hizalayarak birleştir

def _id3_start(path: Path) -> float | None:
    """Çıplak AAC/MP3 HLS parçası (Apple "packed audio"): ilk ID3 etiketindeki transportStreamTimestamp (90 kHz)."""
    with open(path, "rb") as f:
        head = f.read(4096)
    if not head.startswith(b"ID3") or len(head) < 10:
        return None
    size = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9]
    tag = head[10:10 + size]
    owner = b"com.apple.streaming.transportStreamTimestamp\x00"
    i = tag.find(owner)
    if i < 0 or len(tag) < i + len(owner) + 8:
        return None
    pts = int.from_bytes(tag[i + len(owner): i + len(owner) + 8], "big") & ((1 << 33) - 1)
    return pts / 90000.0


def stream_start(path: Path) -> float | None:
    """Akışın yayın saatindeki başlangıcı (sn): TS/MP4'te dosyanın kendi zaman damgası, çıplak AAC'de ID3 etiketi;
    bilinmiyorsa None (o zaman parça listesinin başına hizalanır)."""
    t = _id3_start(path)
    if t is not None:
        return t
    with open(path, "rb") as f:
        if f.read(3) == b"ID3":
            return None            # ID3'lü ama zaman etiketi yok: zaman bilgisi yok
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=start_time,format_name", "-of", "json", str(path)],
                       capture_output=True, text=True)
    try:
        import json
        fmt = json.loads(r.stdout)["format"]
        if fmt.get("format_name") in ("aac", "mp3"):
            return None            # çıplak ses: kendi zaman damgası yok
        return float(fmt["start_time"])
    except Exception:  # noqa: BLE001
        return None


def mux_aligned(video: Path, audios: list[tuple[Path, str]], out: Path, log=print) -> None:
    """
    Aynı HLS yayınının görüntüsü ve dil sesleri tek dosyada: her akış yayın saatindeki gerçek başlangıcına göre hizalanır
    (TS'de kendi zaman damgası, çıplak AAC'de ID3 etiketi). Zaman bilgisi olmayan ses görüntünün başına hizalanır.
    """
    vs = stream_start(video)
    cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error"]
    shifts = []
    for a, _ in audios:
        s = stream_start(a)
        shifts.append(0.0 if (s is None or vs is None) else s - vs)
    lead = max([0.0] + [-d for d in shifts])           # ses görüntüden önce başlıyorsa görüntüyü o kadar ileri al
    cmd += ["-itsoffset", f"{lead:.3f}", "-i", str(video)]
    for (a, _), d in zip(audios, shifts):
        cmd += ["-itsoffset", f"{lead + d:.3f}", "-i", str(a)]
    cmd += ["-map", "0:v:0"]
    for i, (_, lang) in enumerate(audios, start=1):
        cmd += ["-map", f"{i}:a:0", f"-metadata:s:a:{i - 1}", f"language={lang}"]
    cmd += ["-c", "copy", "-max_interleave_delta", "0", str(out)]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0 or not out.exists():
        raise RuntimeError("Görüntü ve ses birleştirilemedi: " + (r.stderr or "")[-600:])
    log("  🎚️ hizalama: " + ", ".join(f"{lang} {d * 1000:+.0f} ms" for (_, lang), d in zip(audios, shifts))
        + (" (ses zaman etiketi yok: parça listesine göre)" if any(stream_start(a) is None for a, _ in audios) else ""))
