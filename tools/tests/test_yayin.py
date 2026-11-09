"""Yayın sunucusu taklit site testi (gerçek siteye istek yok).  python3 tools/tests/test_yayin.py
(ffmpeg gerekir; görünmez tarayıcı adımı için playwright, GFLIX_CHROMIUM ile tarayıcı yolu verilebilir)

Taklit site:
  /m/master.m3u8         ana liste: 1080p (en iyi) + 360p (düşük), TR + EN ses, altyazı grubu yok
  /m/<liste>.m3u8?t=G    alt listeler; parça adresleri ?t=G (nesil) taşır
  /m/<parça>?t=G         parça: Referer yoksa 403; nesil eskiyse 403 (süresi dolmuş link); ilk v2 isteği 429
  /m/sub.tr.vtt, /m/sub.en.srt   altyazılar
  /expire                nesli artırır (eski linkler ölür)
Video parçaları .png uzantılı ve başında sahte PNG baytları var (bazı siteler böyle yapar).
"""
import json
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
from gflix import yayin, resolve  # noqa: E402

MOCK = Path(tempfile.mkdtemp(prefix="gflix-mock-"))


def make_media() -> None:
    """11 dk'lık gerçek HLS: görüntü + TR/EN ses (ffmpeg, birkaç saniye)."""
    run = lambda *a: subprocess.run(["ffmpeg", "-loglevel", "error", *a], check=True, cwd=MOCK)  # noqa: E731
    run("-f", "lavfi", "-i", "testsrc=size=320x180:rate=10", "-t", "660", "-c:v", "libx264", "-preset", "ultrafast", "-g", "100",
        "-f", "hls", "-hls_time", "30", "-hls_segment_filename", "v%d.ts", "-hls_list_size", "0", "v.m3u8")
    for lang, hz in (("tr", 440), ("en", 880)):
        run("-f", "lavfi", "-i", f"sine=frequency={hz}:duration=660", "-c:a", "aac", "-b:a", "32k", "-f", "hls", "-hls_time", "30",
            "-hls_segment_filename", f"a{lang}%d.ts", "-hls_list_size", "0", f"a{lang}.m3u8")


make_media()
STATE = {"gen": 1, "hits": {}, "429": set(), "resolves": 0}
FAKE_PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 500


def scaled(name: str, gen: int, seg_ext: str = "") -> str:
    text = (MOCK / name).read_text()
    durs = [float(x.split(":")[1].split(",")[0]) for x in text.splitlines() if x.startswith("#EXTINF")]
    k = 1.0
    out = []
    for line in text.splitlines():
        if line.startswith("#EXTINF"):
            out.append(f"#EXTINF:{float(line.split(':')[1].split(',')[0]) * k:.3f},")
        elif line.startswith("#EXT-X-TARGETDURATION"):
            out.append(f"#EXT-X-TARGETDURATION:{int(2 * k) + 1}")
        elif line and not line.startswith("#"):
            out.append(f"{line.replace('.ts', seg_ext or '.ts')}?t={gen}")
        else:
            out.append(line)
    return "\n".join(out) + "\n"


MASTER = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="tr",NAME="Türkçe",DEFAULT=YES,URI="atr.m3u8?t={g}"
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="en",NAME="Original Audio",URI="aen.m3u8?t={g}"
#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
low.m3u8?t={g}
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
v.m3u8?t={g}
"""
VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nMerhaba dünya, nasılsın? Bu bir deneme.\n\n00:10:00.000 --> 00:05:02.000\nGörüşürüz.\n"
SRT = "1\n00:00:01,000 --> 00:00:03,000\nHello world, how are you? This is a test.\n\n2\n00:10:00,000 --> 00:05:02,000\nSee you.\n"


class Site(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/octet-stream", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path, _, q = self.path.partition("?")
        gen = int(q.split("t=")[1]) if "t=" in q else None
        if path == "/izle":
            html = ("<html><head><title>Örnek Film izle | Taklit Site</title></head><body><video id=v></video><script>"
                    "fetch('/m/master.m3u8').then(r=>r.text()).then(t=>fetch('/m/v.m3u8?t=" + str(STATE["gen"]) + "'));"
                    "fetch('/m/sub.tr.vtt');</script></body></html>")
            return self._send(200, html.encode(), "text/html; charset=utf-8")
        if path == "/olu":
            return self._send(404, b"yok")
        if path == "/expire":
            STATE["gen"] += 1
            return self._send(200, b"ok")
        if path == "/m/master.m3u8":
            STATE["resolves"] += 1
            return self._send(200, MASTER.format(g=STATE["gen"]).encode(), "application/vnd.apple.mpegurl")
        if path == "/m/sub.tr.vtt":
            return self._send(200, VTT.encode(), "text/vtt")
        if path == "/m/sub.en.srt":
            return self._send(200, SRT.encode(), "text/plain")
        name = path.rsplit("/", 1)[-1]
        if gen is not None and gen != STATE["gen"]:
            return self._send(403, b"expired")
        if name.endswith(".m3u8"):
            src = "v.m3u8" if name == "low.m3u8" else name
            return self._send(200, scaled(src, STATE["gen"], ".png" if src == "v.m3u8" else "").encode(), "application/vnd.apple.mpegurl")
        if self.headers.get("Referer") not in ("http://site.test/izle", f"http://127.0.0.1:{SITE_PORT}/izle"):
            return self._send(403, b"no referer")
        if name == "v2.png" and "v2" not in STATE["429"]:
            STATE["429"].add("v2")
            return self._send(429, b"slow down", extra={"Retry-After": "1"})
        real = MOCK / name.replace(".png", ".ts")
        if not real.exists():
            return self._send(404, b"yok")
        STATE["hits"][name] = STATE["hits"].get(name, 0) + 1
        data = real.read_bytes()
        rng = self.headers.get("Range")
        if rng and name.startswith("ytd"):
            a, _, b = rng.split("=")[1].partition("-")
            a, b = int(a), min(int(b) if b else len(data) - 1, len(data) - 1)
            return self._send(206, data[a:b + 1], "video/mp4", {"Content-Range": f"bytes {a}-{b}/{len(data)}", "Accept-Ranges": "bytes"})
        if name.endswith(".png"):
            return self._send(200, FAKE_PNG + data, "image/png")
        return self._send(200, data, "video/mp2t")


def fake_resolver(page, kind, tmdb_id, season, episode, tmdb_key, capture=None, log=print):
    """Gerçek resolve_play'in sayfa açma kısmı yerine: taklit 'telefon yakalaması' (Referer başlıklı) verilir;
    choose_stream / pick_audios / pick_subs gerçek kodla çalışır."""
    base = f"http://127.0.0.1:{SITE_PORT}/m/"
    master = urllib.request.urlopen(base + "master.m3u8").read().decode()
    cap = {"page_url": page, "title": "Deneme izle",
           "streams": [{"url": base + "master.m3u8", "text": master, "headers": {"Referer": "http://site.test/izle"}}],
           "subs": [{"url": base + "sub.tr.vtt"}, {"url": base + "sub.en.srt"}]}
    return yayin.resolve_play(page, kind, tmdb_id, season, episode, "", capture=cap, log=log)


def get(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def post(url, obj, key):
    req = urllib.request.Request(url, data=json.dumps(obj).encode(), headers={"X-GFlix-Key": key, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


def check(cond, msg):
    print(("  ✓ " if cond else "  ✗ ") + msg)
    if not cond:
        FAILS.append(msg)


FAILS = []
site = ThreadingHTTPServer(("127.0.0.1", 0), Site)
site.daemon_threads = True
SITE_PORT = site.server_address[1]
threading.Thread(target=site.serve_forever, daemon=True).start()

work = Path(tempfile.mkdtemp())
key = yayin.new_key()
# Uydurma katalog (gerçek yapım adı yok; gerçek katalog sadece evdeki bilgisayarda)
CAT = work / "katalog" / "catalog.json"
CAT.parent.mkdir(parents=True)
CAT.write_text(json.dumps({"version": 2, "titles": [
    {"id": "movie-ornek-film", "type": "movie", "title": "Örnek Film", "names": {"tr": "Örnek Film"}, "tmdbId": 2002, "stream": None,
     "tmdbVideos": [{"key": "AAAAAAAAAA1", "lang": "en", "type": "Teaser", "official": True, "size": 1080},
                    {"key": "AAAAAAAAAA2", "lang": "en", "type": "Trailer", "official": True, "size": 1080},
                    {"key": "AAAAAAAAAA3", "lang": "tr", "type": "Trailer", "official": False, "size": 720}]},
    {"id": "movie-ornek-film-2", "type": "movie", "title": "Örnek Film 2", "names": {"tr": "Örnek Film 2"}, "tmdbId": 2003, "stream": None},
    {"id": "series-ornek-dizi", "type": "series", "title": "Örnek Dizi", "names": {"tr": "Örnek Dizi", "en": "Sample Show"}, "tmdbId": 1001,
     "tmdbSeasons": [{"n": 1, "c": 10}, {"n": 2, "c": 23}, {"n": 3, "c": 23}],
     "releasePlan": [{"season": 3, "from": 1, "to": 23, "at": 0, "whenComplete": True, "complete": False}],
     "seasons": [{"number": 1, "name": "1. Sezon", "episodes": [{"number": 1, "title": "Başlangıç", "stream": None}]}]},
]}, ensure_ascii=False))
srv = yayin.Server(work, key, CAT, "", port=0, resolver=fake_resolver)
srv.port = 0
srv.start()
PORT = srv.httpd.server_address[1]
srv.port = PORT
B = f"http://127.0.0.1:{PORT}"

print("1) anahtar ve izinler")
check(get(B + "/api/ping")[0] == 200, "ping anahtarsız açık")
check(get(B + "/api/catalog")[0] == 401, "katalog anahtarsız 401")
check(get(B + "/api/catalog", {"X-GFlix-Key": key.lower().replace("-", "")})[0] == 200, "anahtar küçük harf/tiresiz de kabul")
check(yayin.allowed_client("100.101.5.7") and yayin.allowed_client("192.168.1.20") and not yayin.allowed_client("8.8.8.8"),
      "sadece ev ağı + Tailscale")

print("2) kaynak yokken oynat")
code, body = post(B + "/api/play", {"id": "movie-ornek-film"}, key)
check(code == 404 and body.get("needsSource"), f"kaynak yok → 404 needsSource ({body.get('error', '')[:60]})")

print("3) kaynak kaydı + katalog")
code, body = post(B + "/api/source", {"id": "movie-ornek-film", "url": "https://site.test/izle/ornek-film"}, key)
check(code == 200 and body["id"] == "movie-ornek-film", "film kaynağı kaydedildi")
post(B + "/api/source", {"id": "series-ornek-dizi", "pattern": "https://site.test/dizi/ornek-dizi/sezon-{s}/bolum-{e2}"}, key)
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
t = next(x for x in cat["titles"] if x["id"] == "movie-ornek-film")
check(t.get("stream", {}).get("live") is True, "kaynağı olan film oynatılabilir (live)")
check(sum(1 for x in cat["titles"] if x["type"] == "movie" and x.get("stream")) == 1, "kaynağı olmayan filmler oynatılamaz")
fl = next(x for x in cat["titles"] if x["id"] == "series-ornek-dizi")
eps = [(s["number"], e["number"]) for s in fl["seasons"] for e in s["episodes"] if e.get("stream")]
check(len(eps) == 56 and (1, 1) in eps, f"bölüm kalıbıyla TMDB'deki bölümler açıldı ({len(eps)} bölüm)")
check((3, 1) in eps and fl["releasePlan"][0]["complete"], "kalıp varken 'hepsi hazır olunca aç' kapısı açık (bütün bölümlerin kaynağı var)")
check("site.test" not in json.dumps(cat), "kaynak adresleri katalogda yok")
check(srv.sources.url_for("series-ornek-dizi", 2, 5) == "https://site.test/dizi/ornek-dizi/sezon-2/bolum-05", "kalıp → bölüm adresi")
check((work / "kaynaklar.json").stat().st_mode & 0o777 == 0o600, "kaynaklar.json sadece kullanıcıya açık (600)")

print("4) oynat: taze link + ana liste")
t0 = time.time()
code, play = post(B + "/api/play", {"id": "movie-ornek-film"}, key)
check(code == 200, f"play 200 ({time.time() - t0:.2f} sn)")
check(play["height"] == 1080, "en iyi kalite (1080p) seçildi")
check([a["lang"] for a in play["audio"]] == ["tr", "en"], f"sesler TR + orijinal: {play['audio']}")
check(sorted(s["lang"] for s in play["subs"]) == ["en", "tr"], f"bütün altyazılar: {[s['lang'] for s in play['subs']]}")
code, m, _ = get(play["hls"])
m = m.decode()
check(code == 200 and 'URI="a0.m3u8"' in m and "RESOLUTION=1920x1080" in m and 'CODECS="avc1.640028,mp4a.40.2"' in m, "ana liste bilgisayarı gösteriyor")
check("127.0.0.1:%d" % SITE_PORT not in m, "ana listede site adresi yok")

print("5) alt liste ve parçalar (Referer, 429, sahte PNG öneki)")
base = play["hls"].rsplit("/", 1)[0]
code, v, _ = get(base + "/v.m3u8")
v = v.decode()
check(code == 200 and "v/0.ts" in v and "site" not in v and "127.0.0.1" not in v, "görüntü listesi yeniden yazıldı")
code, s0, h = get(base + "/v/0.ts")
check(code == 200 and s0[:1] == b"\x47" and s0 == (MOCK / "v0.ts").read_bytes(), "parça: Referer'le çekildi, sahte PNG öneki atıldı")
check(h.get("Content-Type") == "video/mp2t", f"içerik türü video/mp2t ({h.get('Content-Type')})")
t0 = time.time()
code, s2, _ = get(base + "/v/2.ts")
check(code == 200 and s2 == (MOCK / "v2.ts").read_bytes() and time.time() - t0 >= 0.9, f"429 sonrası beklenip alındı ({time.time() - t0:.1f} sn)")
code, a0, _ = get(base + "/a1.m3u8")
check(code == 200 and "a1/0.ts" in a0.decode(), "ikinci ses listesi")
code, rng, h = get(base + "/a1/1.ts", {"Range": "bytes=0-99"})
check(code in (200, 206), f"Range isteği geçer ({code})")

print("6) link süresi doldu → sessizce yeniden çözülür, aynı adresler")
before = STATE["resolves"]
get(f"http://127.0.0.1:{SITE_PORT}/expire")
code, s3, _ = get(base + "/v/3.ts")
check(code == 200 and s3 == (MOCK / "v3.ts").read_bytes(), "süresi dolan link sonrası parça geldi")
check(STATE["resolves"] == before + 1, "bir kez yeniden çözüldü")
code, s4, _ = get(base + "/v/4.ts")
check(code == 200 and STATE["resolves"] == before + 1, "sonraki parçalar yeni linkle (tekrar çözme yok)")

print("7) altyazılar")
for sub in play["subs"]:
    code, body, h = get(sub["url"])
    check(code == 200 and body.startswith(b"WEBVTT") and "-->" in body.decode(), f"{sub['lang']} VTT ({h.get('Content-Type')})")

print("8) ffmpeg oynatıcı gibi baştan sona okur")
r = subprocess.run(["ffmpeg", "-v", "error", "-i", play["hls"], "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"],
                   capture_output=True, text=True, timeout=120)
check(r.returncode == 0, "ffmpeg hatasız okudu" + (f": {r.stderr[:300]}" if r.returncode else ""))

print("9) aynı yapım tekrar → aynı oturum (TV'de aç hızlı)")
code, play2 = post(B + "/api/play", {"id": "movie-ornek-film"}, key)
check(play2["session"] == play["session"], "10 dk içinde aynı oturum")

print("10) TV komutu (uzun bekleme)")
res = {}
th = threading.Thread(target=lambda: res.update(get(B + "/api/tv/next?after=0&wait=10", {"X-GFlix-Key": key})[1:2] and
                                                 {"r": json.loads(get(B + "/api/tv/next?after=0&wait=0", {"X-GFlix-Key": key})[1])}))
t0 = time.time()
th.start()
time.sleep(0.5)
post(B + "/api/tv/open", {"id": "movie-ornek-film", "position": 42, "session": play["session"]}, key)
th.join(15)
cmds = res.get("r", {}).get("commands", [])
check(cmds and cmds[0]["id"] == "movie-ornek-film" and cmds[0]["position"] == 42, f"TV komutu geldi ({time.time() - t0:.1f} sn)")
code, nxt = 0, None
t0 = time.time()
r2 = json.loads(get(B + f"/api/tv/next?after={cmds[0]['seq'] if cmds else 0}&wait=1", {"X-GFlix-Key": key})[1])
check(not r2["commands"] and time.time() - t0 >= 0.9, "yeni komut yokken bekleyip boş döner")

print("11) Sayfadan Yakala eşleştirmesi (TMDB'siz, katalog adıyla)")
code, body = post(B + "/api/source", {"url": "https://site.test/dizi/ornek-dizi/sezon-2/bolum-7", "title": "Örnek Dizi 2. Sezon 7. Bölüm izle | Site"}, key)
check(code == 200 and body.get("pending") and body["title"] == "Örnek Dizi S02E07", f"sayfa → onay kuyruğu, tahmin: {body.get('title')}")
code, ar = post(B + "/api/pending/approve", {"pids": [body["pid"]]}, key)
check(ar["results"][0]["ok"] and srv.sources.url_for("series-ornek-dizi", 2, 7), "tahminle onaylandı → yayında")

print("12) GitHub'a medya yükleme kapalı")
from gflix.github import GitHub, GitHubError  # noqa: E402
gh = GitHub.__new__(GitHub)
gh.log = print
p = MOCK / "v0.ts"
try:
    gh.upload_asset({"upload_url": "http://127.0.0.1:1/x{?name}", "id": 1}, p, "film.p000", "application/octet-stream")
    check(False, "yükleme engellenmedi!")
except GitHubError as e:
    check("kapalı" in str(e), "parça yüklemesi engellendi")

import importlib.util  # noqa: E402
HAS_PW = importlib.util.find_spec("playwright") is not None
print("13) gerçek yol: bilgisayar sayfayı görünmez tarayıcıda açıp linki kendisi bulur")
import os  # noqa: E402
srv.resolver = yayin.resolve_play
if not HAS_PW:
    print("  (playwright yok, atlandı)")
post(B + "/api/source", {"id": "movie-ornek-film-2", "url": f"http://127.0.0.1:{SITE_PORT}/izle"}, key)
t0 = time.time()
code, p3 = post(B + "/api/play", {"id": "movie-ornek-film-2"}, key) if HAS_PW else (0, {})
HAS_PW and check(code == 200 and p3.get("height") == 1080, f"sayfadan çözüldü: {p3.get('height')}p, {[a['lang'] for a in p3.get('audio', [])]}, altyazı {[x['lang'] for x in p3.get('subs', [])]} ({time.time() - t0:.1f} sn)")
if code == 200:
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", p3["hls"], "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"],
                       capture_output=True, text=True, timeout=120)
    check(r.returncode == 0, "sayfadan çözülen yayın baştan sona okundu" + (f": {r.stderr[:200]}" if r.returncode else ""))

print("14) fragmanlar ve YouTube (taklit yt-dlp; gerçek YouTube'a istek yok)")
from gflix import youtube  # noqa: E402
subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=10", "-t", "20", "-c:v", "libx264",
                "-preset", "ultrafast", "-movflags", "+faststart", str(MOCK / "ytv.mp4")], check=True)
subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=500:duration=20", "-c:a", "aac", str(MOCK / "yta.m4a")], check=True)
SB = f"http://127.0.0.1:{SITE_PORT}/m/"
FAKE = {
    "HLSVID00001": {"title": "x", "duration": 660, "formats": [
        {"format_id": "95", "protocol": "m3u8_native", "url": SB + "v.m3u8?t=" + str(STATE["gen"]), "vcodec": "avc1.4d401f", "acodec": "mp4a.40.2",
         "height": 1080, "width": 1920, "tbr": 4000, "http_headers": {"Referer": "http://site.test/izle"}}]},
    "MUXVID00001": {"title": "y", "duration": 20, "formats": [
        {"format_id": "137", "protocol": "https", "url": SB + "ytv.mp4", "vcodec": "avc1.640028", "acodec": "none", "height": 1080, "tbr": 3000,
         "http_headers": {"Referer": "http://site.test/izle"}},
        {"format_id": "140", "protocol": "https", "url": SB + "yta.m4a", "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a", "abr": 128,
         "http_headers": {"Referer": "http://site.test/izle"}}]},
}
calls = []
youtube.probe = lambda vid, log=print, timeout=90: (calls.append(vid), FAKE[vid])[1]
check(youtube.video_id("https://youtu.be/HLSVID00001?t=3") == "HLSVID00001" and youtube.video_id("https://www.youtube.com/watch?x=1&v=MUXVID00001") == "MUXVID00001",
      "YouTube linkinden kimlik")
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
t = next(x for x in cat["titles"] if x["id"] == "movie-ornek-film")
check([(x["lang"], x["youtube"]) for x in t["trailers"]] == [("tr", "AAAAAAAAAA3"), ("en", "AAAAAAAAAA2")] and t["trailer"]["live"],
      "fragmanlar TMDB'deki YouTube'dan (dil başına en iyisi, TR önce)")
post(B + "/api/source", {"id": "movie-ornek-film-2", "url": "https://www.youtube.com/watch?v=HLSVID00001"}, key)
srv.sources.set_trailer("movie-ornek-film", "https://youtu.be/MUXVID00001", "tr")
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
t = next(x for x in cat["titles"] if x["id"] == "movie-ornek-film")
check(t["trailer"]["youtube"] == "MUXVID00001", "elle verilen fragman TMDB'ninkinin yerine geçti")
code, py = post(B + "/api/play", {"id": "movie-ornek-film-2"}, key)
check(code == 200 and "/yt/HLSVID00001/master.m3u8" in py["hls"], f"kaynağı YouTube olan yapım → /yt/ ({code})")
r = subprocess.run(["ffmpeg", "-v", "error", "-i", py["hls"], "-map", "0:v:0", "-t", "60", "-f", "null", "-"], capture_output=True, text=True, timeout=120)
check(r.returncode == 0, "YouTube HLS aracı üzerinden okundu" + (f": {r.stderr[:200]}" if r.returncode else ""))
t0 = time.time()
code, mtext, _ = get(B + "/yt/MUXVID00001/master.m3u8?k=" + key)
check(code == 200 and "/ytf/" in mtext.decode() and "#EXTINF" in mtext.decode(), f"HLS'siz YouTube: bellekte birleştirildi ({time.time() - t0:.1f} sn)")
r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type", "-of", "csv=p=0", B + "/yt/MUXVID00001/master.m3u8?k=" + key],
                   capture_output=True, text=True, timeout=60)
check("video" in r.stdout and "audio" in r.stdout, f"birleşik yayında görüntü + ses var ({r.stdout.split()})")
m = srv.muxes.get("MUXVID00001")
check(m is not None and str(m.dir).startswith("/dev/shm"), f"parçalar bellekte: {m.dir if m else '-'}")
n = len(calls)
get(B + "/yt/MUXVID00001/master.m3u8?k=" + key)
check(len(calls) == n, "aynı fragman tekrar → yeniden çözme yok")
check(get(B + "/yt/MUXVID00001/master.m3u8")[0] == 401, "/yt/ anahtarsız açılmaz")
mdir = m.dir
m.used = 0
srv._gc()
check(not mdir.exists(), "izlenmeyen fragmanın bellekteki parçaları silindi")

print("14b) YouTube ayrı görüntü+ses (DASH): tam uzunlukta, ileri/geri sarılabilir")
fl_ = ["+frag_keyframe+empty_moov+default_base_moof+global_sidx"]
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=10", "-t", "60", "-c:v", "libx264",
                "-preset", "ultrafast", "-g", "50", "-an", "-movflags", fl_[0], "-frag_duration", "5000000", str(MOCK / "ytdv.mp4")], check=True)
subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=500:duration=60", "-c:a", "aac", "-b:a", "64k",
                "-movflags", fl_[0], "-frag_duration", "5000000", str(MOCK / "ytda.m4a")], check=True)
FAKE["DASHVID0001"] = {"title": "z", "duration": 60, "formats": [
    {"format_id": "137", "protocol": "https", "url": SB + "ytdv.mp4", "vcodec": "avc1.64000d", "acodec": "none", "height": 180, "width": 320, "tbr": 300,
     "http_headers": {"Referer": "http://site.test/izle"}},
    {"format_id": "140", "protocol": "https", "url": SB + "ytda.m4a", "vcodec": "none", "acodec": "mp4a.40.2", "ext": "m4a", "abr": 64,
     "language": "tr", "language_preference": 10, "http_headers": {"Referer": "http://site.test/izle"}}]}
before_mux = len(srv.muxes)
code, mt, _ = get(B + "/yt/DASHVID0001/master.m3u8?k=" + key)
mt = mt.decode()
check(code == 200 and 'CODECS="avc1.64000d,mp4a.40.2"' in mt and "TYPE=AUDIO" in mt and len(srv.muxes) == before_mux,
      "DASH: birleştirme yok, görüntü + ses izleri olan ana liste")
vurl = next(l for l in mt.splitlines() if l and not l.startswith("#"))
code, vl, _ = get(vurl)
vl = vl.decode()
check("#EXT-X-ENDLIST" in vl and "#EXT-X-BYTERANGE" in vl and abs(sum(float(x.split(":")[1].rstrip(",")) for x in vl.splitlines() if x.startswith("#EXTINF")) - 60) < 1,
      "liste en baştan tam uzunlukta (60 sn, sonu belli)")
r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", B + "/yt/DASHVID0001/master.m3u8?k=" + key],
                   capture_output=True, text=True, timeout=60)
check(abs(float(r.stdout.strip() or 0) - 60) < 2, f"oynatıcı süreyi baştan görüyor: {r.stdout.strip()} sn")
r = subprocess.run(["ffmpeg", "-v", "error", "-ss", "45", "-i", B + "/yt/DASHVID0001/master.m3u8?k=" + key, "-t", "5", "-f", "null", "-"],
                   capture_output=True, text=True, timeout=60)
check(r.returncode == 0, "45. saniyeye atlayıp oynattı" + (f": {r.stderr[:200]}" if r.returncode else ""))
r = subprocess.run(["ffmpeg", "-v", "error", "-i", B + "/yt/DASHVID0001/master.m3u8?k=" + key, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"],
                   capture_output=True, text=True, timeout=120)
check(r.returncode == 0, "baştan sona görüntü + ses okundu" + (f": {r.stderr[:200]}" if r.returncode else ""))

print("15) telefon güncellemesi bilgisayardan")
check(get(B + "/api/app", {"X-GFlix-Key": key})[1] == b'{"code": 0}', "güncelleme yokken code 0")
ad = srv.app_dir(); ad.mkdir(parents=True, exist_ok=True)
(ad / "app-10005.apk").write_bytes(b"PK-sahte-apk")
(ad / "app.json").write_text(json.dumps({"code": 10005, "name": "2.0.5", "file": "app-10005.apk"}))
info = json.loads(get(B + "/api/app", {"X-GFlix-Key": key})[1])
check(info["code"] == 10005 and info["url"] == "/app.apk", f"güncelleme bilgisi {info}")
code, apk, h = get(B + "/app.apk")
check(code == 200 and apk == b"PK-sahte-apk" and "android.package" in h.get("Content-Type", ""), "APK ev ağından indirilir")

print("16) site bilgisayara yayın vermiyor → telefonun yakaladığı kullanılır")
srv.resolver = yayin.resolve_play
_orig_cp = resolve.capture_page
resolve.capture_page = lambda url, log=print, timeout=120, **kw: resolve.Capture(url)      # insan kontrolü: hiçbir yayın yok
base_m = f"http://127.0.0.1:{SITE_PORT}/m/"
phone_cap = {"page_url": "https://site.test/izle/korumali", "title": "Örnek Film izle | Site",
             "streams": [{"url": base_m + "master.m3u8", "text": urllib.request.urlopen(base_m + "master.m3u8").read().decode(),
                          "headers": {"Referer": "http://site.test/izle"}, "duration": 660}],
             "subs": [{"url": base_m + "sub.tr.vtt", "text": VTT}], "playlists": []}
code, body = post(B + "/api/source", {"url": "https://site.test/izle/korumali", "title": "Örnek Film izle | Site", "capture": phone_cap}, key)
check(code == 200 and body.get("pending"), "yakalama onay kuyruğunda (yayında değil)")
post(B + "/api/pending/approve", {"pids": [body["pid"]], "tmdb": "movie/2002"}, key)
f = work / "yakalamalar" / "movie-ornek-film.json"
check(code == 200 and f.exists() and (f.stat().st_mode & 0o777) == 0o600, "telefonun yakalaması bilgisayarda saklandı (600)")
t0 = time.time()
code, pc = post(B + "/api/play", {"id": "movie-ornek-film", "fresh": True}, key)
check(code == 200 and pc.get("height") == 1080, f"sayfa açılamasa da telefonun yakaladığıyla oynadı ({time.time() - t0:.1f} sn)")
if code == 200:
    r = subprocess.run(["ffmpeg", "-v", "error", "-i", pc["hls"], "-map", "0:v:0", "-t", "40", "-f", "null", "-"], capture_output=True, text=True, timeout=120)
    check(r.returncode == 0, "yakalanan yayın aracı üzerinden okundu")
get(f"http://127.0.0.1:{SITE_PORT}/expire")
code, body = post(B + "/api/play", {"id": "movie-ornek-film", "fresh": True}, key)
check(code == 502 and "Sayfadan Yakala" in body.get("error", ""), f"yakalama eskiyince açık hata: {body.get('error', '')[:70]}")
resolve.capture_page = _orig_cp

print("17) Stüdyo özeti ve fragman kontrolü")
st = json.loads(get(B + "/api/stats", {"X-GFlix-Key": key})[1])
check(st["movies"] == 2 and st["series"] == 1 and st["playsTotal"] >= 3 and st["bytesTotal"] > 1_000_000,
      f"özet: {st['movies']} film, {st['series']} dizi, {st['playsTotal']} izleme, {st['bytesTotal'] / 1e6:.0f} MB geçti")
check(get(B + "/api/stats")[0] == 401, "özet anahtarsız açılmaz")
FAKE["AAAAAAAAAA2"] = {"title": "Başka Bir Yapım | Official Trailer", "formats": []}
FAKE["AAAAAAAAAA1"] = {"title": "Örnek Film - Teaser", "formats": []}
FAKE["AAAAAAAAAA3"] = {"title": "ÖRNEK FİLM Türkçe Altyazılı Fragman", "formats": []}
srv.sources.mark_bad_trailer("movie-ornek-film", "MUXVID00001")     # elle verileni kaldır: TMDB adayları sınansın
while srv.check_next_trailer():
    pass
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
t = next(x for x in cat["titles"] if x["id"] == "movie-ornek-film")
langs = {x["lang"]: x["youtube"] for x in t["trailers"]}
check(langs.get("en") == "AAAAAAAAAA1" and langs.get("tr") == "AAAAAAAAAA3",
      f"başlığı tutmayan fragman atlandı, sıradaki seçildi: {langs}")
code, r = post(B + "/api/trailer", {"id": "movie-ornek-film", "bad": "AAAAAAAAAA1"}, key)
check(code == 200 and "en" not in {x["lang"] for x in r["trailers"]}, "telefondan 'yanlış fragman' → o video bir daha seçilmez")
code, r = post(B + "/api/trailer", {"id": "movie-ornek-film", "url": "https://youtu.be/HLSVID00001", "lang": "en"}, key)
check(code == 200 and {x["lang"]: x["youtube"] for x in r["trailers"]}.get("en") == "HLSVID00001", "telefondan YouTube linki verildi")
srv.usage.flush(); srv.tchecks.flush()
check((work / "istatistik.json").exists() and (work / "fragman-kontrol.json").exists(), "sayaç ve kontrol dosyaları yazıldı")

print("18) ölü link kontrolü ve bildirim, YouTube linkinden ekleme")
_probe = youtube.probe
def probe2(vid, log=print, timeout=90):
    if vid == "DEADVID0001":
        raise RuntimeError("YouTube videosu açılamadı: ERROR: [youtube] DEADVID0001: Video unavailable")
    return FAKE.get(vid) or {"title": "?", "formats": []}
youtube.probe = probe2
srv.sources.set("movie-ornek-film", f"http://127.0.0.1:{SITE_PORT}/izle")
srv.sources.set("series-ornek-dizi", f"http://127.0.0.1:{SITE_PORT}/olu", 1, 1)
srv.sources.set("series-ornek-dizi", "http://127.0.0.1:1/kapali", 1, 2)
srv.sources.set("movie-ornek-film-2", "https://www.youtube.com/watch?v=DEADVID0001")
n = 0
while srv.check_next_link() and n < 50:
    n += 1
al = json.loads(get(B + "/api/alerts", {"X-GFlix-Key": key})[1])
whats = sorted(f"{x['title']} {x['what']}" for x in al["items"])
check(len(al["items"]) == 4 and any("S01E01" in w for w in whats) and any("S01E02" in w for w in whats) and any("Örnek Film 2" in w for w in whats),
      f"ölü linkler bildirildi ({al['checked']}/{al['targets']} kontrol): {whats}")
check(not any(x["title"] == "Örnek Film" and x["what"] == "sayfası" for x in al["items"]), "çalışan sayfa ölü sayılmadı")
k = next(x["key"] for x in al["items"] if "S01E01" in x["what"])
post(B + "/api/alerts/ignore", {"key": k}, key)
al2 = json.loads(get(B + "/api/alerts", {"X-GFlix-Key": key})[1])
check(len(al2["items"]) == 3, "gizlenen bildirim listeden çıktı")
check(not srv.check_next_link(), "günde bir: hemen tekrar kontrol yok")
st = json.loads(get(B + "/api/stats", {"X-GFlix-Key": key})[1])
check(st["deadLinks"] == 3, "özette ölü link sayısı")
FAKE["LINKVID0001"] = {"title": "Sample Show S02E03 | Full Episode", "formats": []}
code, body = post(B + "/api/source", {"url": "https://youtu.be/LINKVID0001"}, key)
check(code == 200 and body.get("pending") and body["title"] == "Örnek Dizi S02E03", f"YouTube linkinden ekleme → kuyruk: {body.get('title')}")
post(B + "/api/pending/approve", {"pids": [body["pid"]]}, key)
check(srv.sources.url_for("series-ornek-dizi", 2, 3) == "https://youtu.be/LINKVID0001", "YouTube linki bölüm kaynağı oldu")
youtube.probe = _probe

print("19) onay kuyruğu: TMDB'de olmayan bölüm, elle eşleştirme, toplu eşleştirme")
class FakeTM:
    def get(self, path, **kw):
        eps = {"/tv/1001/season/2/episode/8": {"name": "x"}, "/tv/1001/season/2/episode/9": {"name": "y"}}
        if path in eps:
            return eps[path]
        if path == "/tv/1001":
            return {"name": "Örnek Dizi", "seasons": [{"season_number": 0, "episode_count": 3}, {"season_number": 1, "episode_count": 10},
                                                      {"season_number": 2, "episode_count": 9}]}
        if path == "/movie/5005":
            return {"title": "Örnek Hikaye", "release_date": "2025-12-01"}
        return None
    def search(self, kind, q, year=None, language="tr-TR"):
        return [{"id": 5005, "title": "Örnek Hikaye", "year": 2025, "poster": None}] if kind == "movie" and "hikaye" in q.lower() else []
srv.tmdb_factory = FakeTM
srv.items_dir = work / "katalog" / "items"
srv.items_dir.mkdir(parents=True, exist_ok=True)
srv.rebuild = lambda *a, **k: None          # uydurma kataloğu yeniden üretme
code, b1 = post(B + "/api/source", {"url": "https://site.test/dizi/ornek-dizi/sezon-1/bolum-9", "title": "Örnek Dizi 1. Sezon 9. Bölüm izle"}, key)
check(b1.get("pending") and not b1["guessOk"] and "yok" in (b1.get("reason") or ""), f"TMDB'de olmayan bölüm işaretlendi: {b1.get('reason')}")
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
fl = next(x for x in cat["titles"] if x["id"] == "series-ornek-dizi")
check("1:9" not in (srv.sources.entry("series-ornek-dizi").get("episodes") or {}) and not srv.sources.url_for("movie-ornek-hikaye"),
      "onaylanmadan kaynak olarak yazılmadı")
code, r = post(B + "/api/pending/approve", {"pids": [b1["pid"]]}, key)
check(not r["results"][0]["ok"], "yanlış tahmin kendiliğinden onaylanamaz")
code, sr = post(B + "/api/tmdb/search", {"q": "Örnek Hikaye"}, key)
check(code == 200 and sr["results"][0]["tmdb"] == "movie/5005", "TMDB'de adla arama")
code, ss = post(B + "/api/tmdb/seasons", {"tmdb": "tv/1001"}, key)
check(code == 200 and ss["seasons"] == [{"season": 1, "episodes": 10}, {"season": 2, "episodes": 9}], f"İstek listesi: sezonlar ({ss})")
code, bh = post(B + "/api/source", {"url": "https://site.test/x/istek-1", "title": "alakasız sayfa adı",
                                    "hint": {"tmdb": "tv/1001", "season": 2, "episode": 8, "title": "Örnek Dizi"}}, key)
check(bh.get("pending") and bh["guessOk"] and "S02E08" in bh["title"], f"İstek listesi ipucu tahminin yerine geçti ({bh.get('title')})")
code, r = post(B + "/api/pending/approve", {"pids": [bh["pid"]]}, key)
check(r["results"][0]["ok"] and srv.sources.url_for(r["results"][0]["id"], 2, 8) == "https://site.test/x/istek-1", f"ipuçlu link tek dokunuşla onaylandı ({r})")
code, r = post(B + "/api/pending/approve", {"pids": [b1["pid"]], "tmdb": "https://www.themoviedb.org/movie/5005-ornek-hikaye"}, key)
new_id = r["results"][0].get("id")
check(r["results"][0]["ok"] and new_id == "movie-ornek-hikaye" and srv.sources.url_for(new_id) == "https://site.test/dizi/ornek-dizi/sezon-1/bolum-9"
      and (srv.items_dir / f"{new_id}.json").exists(), f"elle eşleştirildi: ayrı film kaydı açıldı ({new_id})")
pids = []
for ep_ in (8, 9):
    code, bx = post(B + "/api/source", {"url": f"https://site.test/d/s2-b{ep_}", "title": f"Başka Ad 2. Sezon {ep_}. Bölüm izle"}, key)
    pids.append(bx["pid"])
code, r = post(B + "/api/pending/approve", {"pids": pids, "tmdb": "tv/1001"}, key)
check(all(x["ok"] for x in r["results"]) and srv.sources.url_for("series-ornek-dizi", 2, 8) == "https://site.test/d/s2-b8"
      and srv.sources.url_for("series-ornek-dizi", 2, 9) == "https://site.test/d/s2-b9", f"toplu eşleştirme: her link kendi bölümüne ({[x.get('label') for x in r['results']]})")
cat = json.loads(get(B + "/api/catalog", {"X-GFlix-Key": key})[1])
fl = next(x for x in cat["titles"] if x["id"] == "series-ornek-dizi")
check(any(s_["number"] == 2 and e_["number"] == 9 and e_.get("stream") for s_ in fl["seasons"] for e_ in s_["episodes"]), "onaylanan bölüm katalogda oynatılabilir")
code, b3 = post(B + "/api/source", {"url": "https://site.test/cop", "title": "Alakasız Şey izle"}, key)
post(B + "/api/pending/reject", {"pids": [b3["pid"]]}, key)
pl = json.loads(get(B + "/api/pending", {"X-GFlix-Key": key})[1])["items"]
check(pl == [], f"reddedilen silindi, kuyruk boş ({len(pl)})")
al = json.loads(get(B + "/api/alerts", {"X-GFlix-Key": key})[1])
check("pending" in al, "bildirim ucu bekleyen sayısını veriyor")

print("19b) YouTube oynatma listesi → kuyruk, sırayla numaralayıp toplu eşleştirme")
check(youtube.is_playlist("https://www.youtube.com/playlist?list=PLabc123") and not youtube.is_playlist("https://www.youtube.com/watch?v=PLVIDEO0001&list=PLabc"),
      "oynatma listesi linki tanındı (izleme sayfası tek video)")
_pl = youtube.playlist
youtube.playlist = lambda url, timeout=120: [("PLVIDEO0001", "Giriş"), ("PLVIDEO0002", "Devamı"), ("PLVIDEO0003", "Son")]
code, r = post(B + "/api/source", {"url": "https://www.youtube.com/playlist?list=PLabc123"}, key)
pend = json.loads(get(B + "/api/pending", {"X-GFlix-Key": key})[1])["items"]
check(code == 200 and r.get("count") == 3 and len(pend) == 3, f"listeden 3 video kuyruğa: {r.get('title')}")
code, r = post(B + "/api/pending/approve", {"pids": [x["pid"] for x in reversed(pend)], "tmdb": "tv/1001", "season": 3, "start": 1}, key)
got = [srv.sources.url_for("series-ornek-dizi", 3, n) for n in (1, 2, 3)] if all(x["ok"] for x in r["results"]) else r["results"]
check(got == [f"https://www.youtube.com/watch?v=PLVIDEO000{i}" for i in (1, 2, 3)], f"liste sırasıyla S03E01-03: {got}")
youtube.playlist = _pl

print("19c) Sayfadan Yakala listesi: yayında / onayda işaretleri")
srv.sources.set("series-ornek-dizi", "https://www.site.test/dizi/x/sezon-1/bolum-1/", 1, 1)
code, bq = post(B + "/api/source", {"url": "https://site.test/dizi/x/sezon-1/bolum-2", "title": "Örnek Dizi 1. Sezon 2. Bölüm izle"}, key)
code, stt = post(B + "/api/sources/status", {"urls": ["https://site.test/dizi/x/sezon-1/bolum-1", "https://site.test/dizi/x/sezon-1/bolum-2/",
                                                      "https://site.test/dizi/x/sezon-1/bolum-3"]}, key)
check(stt["status"] == {"https://site.test/dizi/x/sezon-1/bolum-1": "live", "https://site.test/dizi/x/sezon-1/bolum-2/": "pending"},
      f"durumlar (www/eğik çizgi farkı tolere): {stt['status']}")
post(B + "/api/pending/reject", {"pids": [bq["pid"]]}, key)

print("20) 'ilk N bölüm hazır olunca birlikte aç' ve telefondan kayıt düzenleme")
src2 = yayin.Sources(work / "kaynaklar-plan.json")
def plan_cat(n_sources):
    for i in range(1, n_sources + 1):
        src2.set("series-plan", f"https://site.test/p/{i}", 1, i)
    c = {"titles": [{"id": "series-plan", "type": "series", "title": "Plan Dizisi", "tmdbSeasons": [{"n": 1, "c": 5}],
                     "releasePlan": [{"season": 1, "from": 1, "to": 3, "at": 0, "whenComplete": True}],
                     "seasons": [{"number": 1, "episodes": [{"number": i, "title": f"B{i}", "stream": None} for i in range(1, 6)]}]}]}
    return yayin.live_catalog(c, src2)["titles"][0]
t2 = plan_cat(2)
vis = [e["number"] for s_ in t2["seasons"] for e in s_["episodes"]]
check(1 not in vis and 2 not in vis and t2["releasePlan"][0]["have"] == 2 and not t2["releasePlan"][0]["complete"],
      f"3 bölümlük açılışta 2'si hazırken gizli (görünen: {vis})")
t3 = plan_cat(3)
vis3 = [e["number"] for s_ in t3["seasons"] for e in s_["episodes"] if e.get("stream")]
check(vis3 == [1, 2, 3] and t3["releasePlan"][0]["complete"], f"3'ü hazır olunca birlikte açıldı ({vis3})")
code, r = post(B + "/api/item", {"item": {"id": "series-yeni-dizi", "type": "series", "title": "Yeni Dizi",
                                          "releasePlan": [{"season": 1, "from": 1, "to": 4, "at": 0, "whenComplete": True}]}}, key)
code2, it = get(B + "/api/item?id=series-yeni-dizi", {"X-GFlix-Key": key})[:2]
check(code == 200 and code2 == 200 and json.loads(it)["releasePlan"][0]["to"] == 4, "telefondan açılış planı kaydedildi (bilgisayarda)")
check(post(B + "/api/item", {"item": {"id": "../kotu", "type": "movie"}}, key)[0] == 400, "yanlış kimlik reddedildi")
srv.sources.set("series-yeni-dizi", "https://site.test/y/1", 1, 1)
srv.sources.set("series-yeni-dizi", "https://site.test/y/2", 1, 2)
post(B + "/api/item", {"item": {"id": "series-yeni-dizi", "type": "series", "title": "Yeni Dizi",
                                "episodes": [{"season": 1, "episode": 1}, {"season": 1, "episode": 2}]}}, key)
post(B + "/api/item", {"item": {"id": "series-yeni-dizi", "type": "series", "title": "Yeni Dizi", "episodes": [{"season": 1, "episode": 1}]}}, key)
check(srv.sources.url_for("series-yeni-dizi", 1, 2) is None and srv.sources.url_for("series-yeni-dizi", 1, 1), "telefondan silinen bölümün kaynağı da unutuldu")
code, r = post(B + "/api/item/delete", {"id": "series-yeni-dizi"}, key)
moved = Path(r.get("moved", "/yok"))
check(code == 200 and r["ok"] and not (srv.items_dir / "series-yeni-dizi.json").exists() and moved.exists()
      and json.loads(moved.read_text())["_kaynaklar"]["episodes"] == {"1:1": "https://site.test/y/1"} and not srv.sources.entry("series-yeni-dizi"),
      "yapım silindi: silinenler/ klasörüne taşındı (kaynaklarıyla, geri alınabilir)")

print("21) içeriğin kesin boyutu: kimse izlemiyorken parça parça ölçülür")
srv.sizes.data.clear()
srv.sources.set("movie-ornek-film", "https://site.test/izle/korumali")     # kaynak: telefonun yakalaması (14. adım)
srv.captures.save("movie-ornek-film", None, None, phone_cap)
get(f"http://127.0.0.1:{SITE_PORT}/expire")                                # taze linkler: yakalamayı yenile
phone_cap["streams"][0]["text"] = urllib.request.urlopen(base_m + "master.m3u8").read().decode()
srv.captures.save("movie-ornek-film", None, None, phone_cap)
with srv.slock:
    srv.sessions.clear()
for k in [x for x in list(srv.sources.all()) if x != "movie-ornek-film"]:
    srv.sources.data["items"].pop(k, None)
srv.sources._save()
youtube.probe = probe2
did = srv.measure_next()
v = srv.sizes.data.get("movie-ornek-film") or {}
expect = sum(f.stat().st_size + (len(FAKE_PNG) if f.name.startswith("v") else 0)
             for f in MOCK.glob("*.ts") if f.name[:1] == "v" or f.name.startswith(("atr", "aen")))
check(did and v.get("exact") and v.get("bytes") == expect, f"kesin ölçüm: {v.get('bytes')} bayt (beklenen {expect}) {v.get('err', '')}")
st = json.loads(get(B + "/api/stats", {"X-GFlix-Key": key})[1])
check(st["contentExact"] >= 1 and st["contentBytes"] >= expect, f"özet: {st['contentBytes'] / 1e6:.0f} MB, {st['contentExact']}/{st['contentItems']} kesin")
srv.note_size("movie-ornek-film", None, None, 1_000_000, 60)
check(srv.sizes.data["movie-ornek-film"]["bytes"] == expect, "kesin ölçüm tahminle ezilmedi")
s_busy = srv.play("movie-ornek-film", None, None, count=False)
s_busy.used = time.time()
with srv.sizes.lock:
    srv.sizes.data.clear()
check(srv.measure_next() is False and not srv.sizes.data, "biri izlerken ölçüm yapılmaz")
youtube.probe = _probe

# ---- tema müziği: YouTube'da aranır, sadece ses aktarılır (dosya yok)
print("-" * 40)
srv.yt_search = lambda q: [{"id": "AAAAAAAAAA1", "title": "Örnek Film Official Trailer", "duration": 120},
                           {"id": "AAAAAAAAAA2", "title": "Örnek Film Full Soundtrack", "duration": 3600},
                           {"id": "AAAAAAAAAA3", "title": "Örnek Film Main Theme", "duration": 150},
                           {"id": "AAAAAAAAAA4", "title": "Örnek Film Suite", "duration": 200}]
srv.yt_probe = lambda vid, log=print: {"formats": [
    {"protocol": "https", "acodec": "opus", "vcodec": "none", "ext": "webm", "abr": 160, "url": f"http://127.0.0.1:{SITE_PORT}/m/v1.ts"},
    {"protocol": "https", "acodec": "mp4a.40.2", "vcodec": "none", "ext": "m4a", "abr": 128, "url": f"http://127.0.0.1:{SITE_PORT}/m/v0.ts",
     "http_headers": {"Referer": "http://site.test/izle"}}]}
code, th = post(B + "/api/theme", {"id": "movie-ornek-film"}, key)
check(code == 200 and th.get("youtube") == "AAAAAAAAAA3", f"tema: fragman/uzun video atlandı, ana tema seçildi ({th})")
code, th = post(B + "/api/theme", {"id": "movie-ornek-film", "bad": "AAAAAAAAAA3"}, key)
check(th.get("youtube") == "AAAAAAAAAA4", f"tema: 'yanlış' denince başkası ({th})")
code, th = post(B + "/api/theme", {"id": "movie-ornek-film", "url": "https://youtu.be/BBBBBBBBBB1"}, key)
check(th.get("youtube") == "BBBBBBBBBB1" and srv.theme_for("movie-ornek-film")["youtube"] == "BBBBBBBBBB1", "tema: elle verilen link kalıcı")
want = (MOCK / "v0.ts").read_bytes()
code, a, hd = get(B + f"/yta/BBBBBBBBBB1?k={key}")
check(code == 200 and a == want and hd.get("Content-Type") == "audio/mp4", f"tema sesi aktarıldı (m4a seçildi, {len(a)} bayt)")
code, _, _ = get(B + "/yta/BBBBBBBBBB1")
check(code == 401, "tema sesi anahtarsız verilmez")
check(not any(p.suffix in (".m4a", ".ts") for p in work.rglob("*")), "tema için diske dosya yazılmadı")

# ---- duyurular: yayındaki dizide TMDB'ye yeni sezon/bölüm girince Stüdyo'ya düşer
print("-" * 40)
_cat = srv.catalog
def fake_cat(tv=False, eps2=0, nx=None):
    se = [{"number": 1, "episodes": [{"number": i, "stream": {"x": 1}} for i in range(1, 9)]}]
    return {"titles": [{"id": "series-d", "type": "series", "title": "D", "tmdbId": 77, "year": 2020, "originalTitle": "D o",
                        "tmdbSeasons": [{"n": 1, "c": 8}] + ([{"n": 2, "c": eps2}] if eps2 else []), "nextEpisode": nx, "seasons": se}]}
srv.catalog = lambda tv=False: fake_cat()
check(srv.note_news() == 0 and not srv.news.data["items"], "duyuru: değişiklik yokken boş")
srv.catalog = lambda tv=False: fake_cat(nx={"s": 2, "e": 1, "at": None})
check(srv.note_news() == 1 and srv.news.data["items"][0]["text"] == "2. sezon duyuruldu", f"duyuru: yeni sezon ({srv.news.data['items']})")
check(srv.note_news() == 0, "duyuru: aynısı tekrar gelmez")
srv.catalog = lambda tv=False: fake_cat(eps2=10)
check(srv.note_news() == 1 and len(srv.news.data["items"]) == 1 and "10 bölüm" in srv.news.data["items"][0]["text"], "duyuru: bölümler girince aynı sezonun duyurusu güncellendi")
code, nw, _ = get(B + "/api/news", {"X-GFlix-Key": key})
nw = json.loads(nw)
check(code == 200 and nw["items"][0]["tmdb"] == "tv/77" and nw["items"][0]["season"] == 2, "duyuru: telefona gidiyor (TMDB + sezon)")
post(B + "/api/news/done", {"key": nw["items"][0]["key"]}, key)
check(not srv.news.data["items"], "duyuru: 'tamam' deyince kalktı")
srv.catalog = _cat

# ---- YouTube takibi: kanala yeni TAM bölüm gelince onay kuyruğuna (eşleşmiş)
print("-" * 40)
VIDS = [{"id": "OLDOLDOLD01", "title": "Dizi 39. Bölüm", "duration": 7800}]
srv.yt_latest = lambda url, n=20: list(VIDS)
code, fr = post(B + "/api/follows/add", {"url": "https://www.youtube.com/@dizi", "tmdb": "tv/1001", "title": "Örnek Dizi", "season": 2, "first": 40, "min": 30}, key)
check(code == 200 and fr.get("key") and fr.get("seen") is None, f"takip eklendi ({code})")
before = len(srv.pending_list())
check(srv.follow_check(srv.follows.data["items"][0]) == 0, "takip: eski videolar atlandı")
VIDS[:0] = [{"id": "NEWNEWNEW01", "title": "Dizi 41. Bölüm | Tam Bölüm", "duration": 8100},
            {"id": "TRAILER0001", "title": "Dizi 42. Bölüm Fragmanı", "duration": 120},
            {"id": "SHORTCLIP01", "title": "Dizi 41. Bölüm - En komik sahne", "duration": 400}]
check(srv.follow_check(srv.follows.data["items"][0]) == 1, "takip: sadece tam bölüm alındı (fragman/kesit atlandı)")
newp = [x for x in srv.pending_list() if x["url"].endswith("NEWNEWNEW01")]
g = newp[0]["guess"] if newp else {}
check(len(srv.pending_list()) == before + 1 and g.get("ok") and g.get("season") == 2 and g.get("episode") == 2,
      f"takip: onay kuyruğunda 2. sezon 2. bölüm olarak eşleşti ({g.get('label')})")
check(srv.follow_check(srv.follows.data["items"][0]) == 0, "takip: aynı bölüm tekrar eklenmez")
code, fl, _ = get(B + "/api/follows", {"X-GFlix-Key": key})
fl = json.loads(fl)
check(code == 200 and fl["items"][0]["found"] == 1 and "seen" not in fl["items"][0], "takip listesi telefona gidiyor")
# ---- bilgisayarda yakalama (İstek listesinin bir kısmı)
from gflix import resolve as _rs
def fake_cap(url, log=print, timeout=120, visible=False, gentle=False, ghost=False, stay=8):
    c = _rs.Capture(url, title="Deneme Film izle")
    if "siyah" in url:
        return c
    base = f"http://127.0.0.1:{SITE_PORT}/m/"
    c.masters.append((base + "master.m3u8", urllib.request.urlopen(base + "master.m3u8").read().decode(), {"Referer": "http://site.test/izle"}))
    c.durations = {base + "master.m3u8": 5400.0}
    return c
srv.capture_fn = fake_cap
code, j1 = post(B + "/api/pccap/add", {"url": "https://site.test/film/deneme", "hint": {"tmdb": "movie/5005", "title": "Örnek Hikaye"}}, key)
code, j2 = post(B + "/api/pccap/add", {"url": "https://site.test/film/siyah"}, key)
check(srv.pccap_next() and srv.pccap_next() and not srv.pccap_next(), "bilgisayar işleri tek tek yapıldı")
code, st = post(B + "/api/pccap/status", {"jids": [j1["jid"], j2["jid"]]}, key)
a, b_ = st["jobs"][j1["jid"]], st["jobs"][j2["jid"]]
pp = next((x for x in srv.pending_list() if x["url"] == "https://site.test/film/deneme"), None)
check(a["state"] == "ok" and pp and pp["hasCapture"] and pp["guess"].get("tmdbId") == 5005, f"bilgisayarda yakalandı → onaya eşleşmiş ({a})")
check(b_["state"] == "black" and "siyah" in b_["note"], f"video gelmezse siyah ekran ({b_['note']})")
# ---- tazeleme: süresi dolmuş link → telefon yeniden yakalar, onaya gitmeden kaydedilir
srv.refresh_req["movie-ornek-film|None|None"] = {"id": "movie-ornek-film", "season": None, "episode": None, "page": "https://site.test/izle/ornek-film", "title": "Örnek Film", "at": 1}
code, rf, _ = get(B + "/api/refresh", {"X-GFlix-Key": key})
check(code == 200 and json.loads(rf)["items"][0]["id"] == "movie-ornek-film", "varsayılan: tazeleme doğrudan telefona (bilgisayarın tarayıcısı bu sitede açamıyor)")
# telefonun uzun sorgusu: iş gelir gelmez döner; izleyenin beklemesi: link gelir gelmez biter
_saved_rf = dict(srv.refresh_req)
srv.refresh_req.clear()
import threading as _th
_got = {}
def _lp():
    t0 = time.time(); c, body, _ = get(B + "/api/refresh?wait=20", {"X-GFlix-Key": key}); _got.update(dt=time.time() - t0, items=json.loads(body)["items"])
_t = _th.Thread(target=_lp); _t.start(); time.sleep(1.0)
srv.ask_refresh("movie-ornek-film|None|None", {"id": "movie-ornek-film", "season": None, "episode": None, "page": "https://site.test/izle/ornek-film", "title": "Örnek Film"})
_t.join(10)
check(_got.get("items") and _got["dt"] < 5, f"telefonun uzun sorgusu iş gelince hemen döndü ({_got.get('dt', 0):.1f} sn)")
_w = {}
_t = _th.Thread(target=lambda: _w.update(ok=srv.wait_refresh("movie-ornek-film|None|None", 20), at=time.time())); _t.start(); time.sleep(0.5)
_t0 = time.time()
with srv.refresh_cv:
    srv.refresh_req.pop("movie-ornek-film|None|None", None); srv.refresh_cv.notify_all()
_t.join(5)
check(_w.get("ok") and _w["at"] - _t0 < 1, "izleyenin beklemesi link gelir gelmez bitti")
srv.refresh_req.clear(); srv.refresh_req.update(_saved_rf)
os.environ["GFLIX_PC_REFRESH"] = "1"
code, rf, _ = get(B + "/api/refresh", {"X-GFlix-Key": key})
check(code == 200 and not json.loads(rf)["items"], "bilgisayar tazelemesi açıksa önce o dener (telefona henüz verilmez)")
srv.refresh_req["movie-ornek-film|None|None"]["pcWork"] = 1
code, rf, _ = get(B + "/api/refresh", {"X-GFlix-Key": key})
check(not json.loads(rf)["items"], "bilgisayar uğraşırken telefona verilmez")
srv.refresh_req["movie-ornek-film|None|None"].pop("pcWork")
srv.refresh_req["movie-ornek-film|None|None"]["pcTried"] = 1
code, rf, _ = get(B + "/api/refresh", {"X-GFlix-Key": key})
check(code == 200 and json.loads(rf)["items"][0]["id"] == "movie-ornek-film" and "pcTried" not in json.loads(rf)["items"][0],
      "bilgisayar açamadı → tazeleme telefona gidiyor")
npend = len(srv.pending_list())
base_ = f"http://127.0.0.1:{SITE_PORT}/m/"
capx = {"page_url": "https://site.test/izle/ornek-film", "title": "x", "streams": [{"url": base_ + "master.m3u8", "text": urllib.request.urlopen(base_ + "master.m3u8").read().decode(), "headers": {"Referer": "http://site.test/izle"}}]}
code, rr = post(B + "/api/source", {"url": "https://site.test/izle/ornek-film", "title": "x", "capture": capx,
                                    "hint": {"refresh": {"id": "movie-ornek-film", "season": None, "episode": None}}}, key)
check(code == 200 and rr.get("id") == "movie-ornek-film" and not srv.refresh_req and len(srv.pending_list()) == npend and srv.captures.get("movie-ornek-film", None, None),
      f"tazeleme onaya gitmeden kaydedildi ({rr})")
# telefonun linki eskimişse bilgisayar kendi tarayıcısıyla beklemez: hemen telefona sorar, izleyene "süresi dolmuş" döner
with srv.slock:
    srv.sessions.clear()
_alive, _res, calls = srv.alive, srv.resolver, []
srv.alive = lambda s: False
srv.resolver = lambda *a, **kw: (calls.append(kw.get("capture") is not None), _res(*a, **kw))[1]
try:
    srv.play("movie-ornek-film", None, None, count=False)
    msg = ""
except Exception as e:  # noqa: BLE001
    msg = str(e)
srv.alive, srv.resolver = _alive, _res
check("süresi dolmuş" in msg and calls == [True] and "movie-ornek-film|None|None" in srv.refresh_req,
      f"eskimiş telefon linki: bilgisayar tarayıcı açmadan telefona sordu ({calls}, {msg[:60]})")
srv.refresh_req.clear()
# bilgisayar görünmez tazeler (telefon gerekmez); açamazsa telefona kalır
srv.pc_fail_streak = 0
srv.ask_refresh("movie-ornek-film|None|None", {"id": "movie-ornek-film", "season": None, "episode": None,
                                                "page": "https://site.test/film/deneme", "title": "Örnek Film"})
srv.captures.data.clear() if hasattr(srv.captures, "data") else None
kw_seen = {}
_fc = srv.capture_fn
srv.capture_fn = lambda url, log=print, **kw: (kw_seen.update(kw), fake_cap(url, log))[1]
check(srv.pc_refresh_next() and not srv.refresh_req and srv.captures.get("movie-ornek-film", None, None) and kw_seen.get("ghost") is True,
      f"bilgisayar görünmez tazeledi ({kw_seen})")
srv.ask_refresh("movie-ornek-film|None|None", {"id": "movie-ornek-film", "season": None, "episode": None,
                                                "page": "https://site.test/film/siyah", "title": "Örnek Film"})
check(srv.pc_refresh_next() and srv.refresh_req["movie-ornek-film|None|None"].get("pcTried") and not srv.pc_refresh_next()
      and srv.refresh_for_phone(), "bilgisayar açamadı → telefona kaldı, bir daha denemez")
srv.capture_fn = _fc
srv.refresh_req.clear()
srv.pc_fail_streak = 0
os.environ.pop("GFLIX_PC_REFRESH", None)

# ---- yeni bölüm: yabancı dizide TMDB'de yayınlanmış ama katalogda olmayan bölüm → telefona (günde bir)
_cp = srv.catalog_path
newcat = work / "yeni-bolum-katalog.json"
import datetime as _dt
_today = _dt.date.today()
def _d(days): return (_today - _dt.timedelta(days=days)).isoformat()
_st = {"master": "x"}
newcat.write_text(json.dumps({"titles": [
    {"id": "series-yabanci", "type": "series", "tmdbId": 77, "title": "Yabancı", "originalLanguage": "en",
     "seasons": [{"number": 2, "episodes": [{"number": 1, "stream": _st}, {"number": 2, "stream": _st}]}],
     "lastAired": {"s": 2, "e": 4, "date": _d(1)}},
    {"id": "series-yerli", "type": "series", "tmdbId": 78, "title": "Yerli", "originalLanguage": "tr",
     "seasons": [{"number": 1, "episodes": [{"number": 1, "stream": _st}]}], "lastAired": {"s": 1, "e": 2, "date": _d(1)}},
    {"id": "series-duyuru", "type": "series", "tmdbId": 79, "title": "Duyuru", "originalLanguage": "en",
     "seasons": [{"number": 1, "episodes": [{"number": 1, "stream": _st}]}], "lastAired": {"s": 1, "e": 1, "date": _d(3)}},
    {"id": "series-eski", "type": "series", "tmdbId": 80, "title": "Eski", "originalLanguage": "en",
     "seasons": [{"number": 1, "episodes": [{"number": 1, "stream": _st}]}], "lastAired": {"s": 5, "e": 8, "date": _d(2)}},
    {"id": "series-gelecek", "type": "series", "tmdbId": 81, "title": "Gelecek", "originalLanguage": "en",
     "seasons": [{"number": 1, "episodes": [{"number": 1, "stream": _st}]}], "lastAired": {"s": 1, "e": 2, "date": _d(-2)}},
]}))
srv.catalog_path = newcat
srv.newep_checks.data["next"] = {}
code, ne, _ = get(B + "/api/neweps", {"X-GFlix-Key": key})
ne = json.loads(ne)["items"]
check(code == 200 and [(x["id"], x["season"], x["episodes"]) for x in ne] == [("series-yabanci", 2, [3, 4])],
      f"yeni bölüm: sadece yabancı, yayınlanmış, takip edilen sezon ({ne})")
code, ne2, _ = get(B + "/api/neweps", {"X-GFlix-Key": key})
check(not json.loads(ne2)["items"], "yeni bölüm: aynı gün bir daha istenmez (siteye boşuna istek yok)")
check(srv.new_episodes(now=time.time() + 25 * 3600)[0]["id"] == "series-yabanci", "yeni bölüm: ertesi gün yine bakılır")
srv.catalog_path = _cp
# yeni bölüm yakalanınca onay beklemeden yayına
_ap, seen_ap = srv.approve, []
srv.approve = lambda pids, *a, **k: (seen_ap.append(pids), [{"ok": True}])[1]
r_auto = srv.queue_link("https://site.test/dizi/yabanci-2-3", "x", {"streams": [{"url": "u", "text": "#EXTM3U"}]},
                        hint={"tmdb": "tv/77", "title": "Yabancı", "season": 2, "episode": 3, "auto": True})
r_man = srv.queue_link("https://site.test/dizi/yabanci-2-4", "x", {"streams": [{"url": "u", "text": "#EXTM3U"}]},
                       hint={"tmdb": "tv/77", "title": "Yabancı", "season": 2, "episode": 4})
srv.approve = _ap
check(seen_ap == [[r_auto["pid"]]] and r_auto.get("approved") and not r_man.get("approved"), "yeni bölüm onaysız yayına; normal istek onaya")
with srv.pending.lock:
    srv.pending.data["items"] = [x for x in srv.pending.data["items"] if x["pid"] not in (r_auto["pid"], r_man["pid"])]

# ---- İstek listesinin tahmini tarihleri → Yakında ("Tahmini 7 Kasım"); listeden çıkınca işaret kalkar (kayıt silinmez)
_rb = srv.rebuild
srv.rebuild = lambda *a, **k: None
(srv.items_dir / "movie-plan-film.json").write_text(json.dumps({"id": "movie-plan-film", "type": "movie", "title": "Plan Film", "tmdbId": 9091}))
_eta = int(time.time()) + 3 * 86400
code, sp = post(B + "/api/soon/plan", {"items": [{"tmdb": "movie/9091", "eta": _eta}]}, key)
d_ = json.loads((srv.items_dir / "movie-plan-film.json").read_text())
check(code == 200 and d_.get("comingSoon") and d_.get("autoSoon") and d_.get("releaseNote", "").startswith("Tahmini "),
      f"Yakında planı: tahmini tarihle Yakında'da ({d_.get('releaseNote')})")
code, sp = post(B + "/api/soon/plan", {"items": []}, key)
d_ = json.loads((srv.items_dir / "movie-plan-film.json").read_text())
check(not d_.get("comingSoon") and "releaseNote" not in d_ and (srv.items_dir / "movie-plan-film.json").exists(),
      "Yakında planı: listeden çıkınca Yakında'dan kalktı, kayıt duruyor")
srv.rebuild = _rb

# ---- İstek listesi uzaktan: TV kutusu durum yazar, telefon okur ve komut bırakır
code, wv, _ = get(B + "/api/wish", {"X-GFlix-Key": key})
check(code == 200 and json.loads(wv).get("age") is None, "uzaktan liste: henüz cihaz yok")
code, r = post(B + "/api/wish/cmd", {"cmd": {"op": "remove", "id": "abc"}}, key)
code, rep = post(B + "/api/wish/report", {"device": "TV kutusu", "state": {"items": [{"id": "x"}], "started": True}}, key)
check(code == 200 and rep["cmds"][0]["op"] == "remove", "uzaktan liste: telefonun komutu cihaza geçti")
code, rep2 = post(B + "/api/wish/report", {"device": "TV kutusu", "state": {"items": []}}, key)
code, wv, _ = get(B + "/api/wish", {"X-GFlix-Key": key})
wv = json.loads(wv)
check(not rep2["cmds"] and wv["device"] == "TV kutusu" and wv["age"] is not None and wv["age"] < 5, "uzaktan liste: komut bir kez verildi, durum okunuyor")

# ---- 🌙 gece önceden yenileme: izlenen dizinin sıradaki bölümleri, ölü olanlar yakalama cihazına
_cp2 = srv.catalog_path
pfcat = work / "onceden-katalog.json"
_st2 = {"master": "x"}
pfcat.write_text(json.dumps({"titles": [
    {"id": "series-izlenen", "type": "series", "title": "İzlenen", "added": 1,
     "seasons": [{"number": 1, "episodes": [{"number": n, "stream": _st2} for n in range(1, 6)]}]},
    {"id": "movie-eski", "type": "movie", "title": "Eski", "added": 1, "stream": _st2}]}))
srv.catalog_path = pfcat
for n in range(1, 6):
    srv.sources.set("series-izlenen", f"https://site.test/izlenen/1-{n}", 1, n)
    srv.captures.save("series-izlenen", 1, n, {"page_url": "x", "streams": [{"url": "u", "text": "#EXTM3U"}]})
srv.playlog.data["items"] = [{"id": "series-izlenen", "s": 1, "e": 2, "at": int(time.time()) - 3600}]
cands = srv.prefetch_candidates()
check(cands[:3] == [("series-izlenen", 1, 2), ("series-izlenen", 1, 3), ("series-izlenen", 1, 4)],
      f"önceden yenileme adayları: izlenen bölüm + sıradaki 2 ({cands})")
_sa = srv.stored_alive
srv.stored_alive = lambda i, s_, e_: e_ == 2                      # S1E2 canlı, S1E3 ölü
srv.prefetch_cfg.data.update(on=True, **{"from": "00:00", "to": "23:59"}, max=25, done={})
srv.refresh_req.clear()
k1 = srv.prefetch_step()
check(k1 == "series-izlenen|1|3" and "series-izlenen|1|3" in srv.refresh_req and srv.refresh_req[k1].get("prefetch"),
      f"önceden yenileme: canlı olan atlandı, ölü olan cihaza verildi ({k1})")
check(srv.prefetch_step() is None, "önceden yenileme: biri sürerken yenisi verilmez")
srv.refresh_req.clear()
srv.prefetch_cfg.data["max"] = 1
check(srv.prefetch_step() is None, "önceden yenileme: gecelik sınıra uyuldu")
srv.prefetch_cfg.data.update(max=25, **{"from": "03:00", "to": "03:01"})
lt = time.localtime()
check(lt.tm_hour == 3 and lt.tm_min == 0 or srv.prefetch_step() is None, "önceden yenileme: saat aralığı dışında yapılmaz")
code, pc, _ = get(B + "/api/prefetch", {"X-GFlix-Key": key})
check(code == 200 and json.loads(pc)["tonight"] == 1, f"önceden yenileme: telefona durum ({pc[:80]})")
code, _ = post(B + "/api/prefetch", {"on": False, "max": 10}, key)
check(srv.prefetch_cfg.data["on"] is False and srv.prefetch_cfg.data["max"] == 10, "önceden yenileme: ayarlar telefondan değişir")
srv.stored_alive = _sa
srv.catalog_path = _cp2
srv.refresh_req.clear()

# ---- indirme kalitesi: ana listedeki çözünürlükler, istenen kaliteye en yakın (altı) seçilir
from gflix import resolve as _rq
_mt = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nv1080.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nv720.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=854x480\nv480.m3u8\n"
check(_rq.master_heights(_mt) == [1080, 720, 480], "kalite: ana listedeki çözünürlükler")
check(_rq.read_master(_mt, "http://x/m.m3u8").height == 1080 and _rq.read_master(_mt, "http://x/m.m3u8", 720).height == 720
      and _rq.read_master(_mt, "http://x/m.m3u8", 600).height == 480 and _rq.read_master(_mt, "http://x/m.m3u8", 360).height == 480,
      "kalite: istenen kaliteye göre görüntü seçildi (yoksa en küçüğü)")
srv.captures.save("movie-kalite", None, None, {"page_url": "x", "streams": [{"url": "http://x/m.m3u8", "text": _mt, "duration": 5400}]})
code, q = post(B + "/api/qualities", {"id": "movie-kalite"}, key)
check(code == 200 and q["heights"] == [1080, 720, 480], f"kalite: telefona seçenekler ({q})")

# ---- doğrudan indirme: bilgisayar sadece ana liste adresini ve başlıkları verir (video telefona siteden iner)
base_d = f"http://127.0.0.1:{SITE_PORT}/m/"
srv.captures.save("movie-dogrudan", None, None, {"page_url": "x", "streams": [
    {"url": base_d + "master.m3u8", "text": urllib.request.urlopen(base_d + "master.m3u8").read().decode(), "headers": {"Referer": "http://site.test/izle", "X-Diger": "1"}}]})
di = srv.direct_info("movie-dogrudan", None, None)
check(di["master"] == base_d + "master.m3u8" and di["headers"] == {"Referer": "http://site.test/izle"} and di["heights"],
      f"doğrudan indirme: ana liste, başlıklar ve kaliteler ({di})")
check(srv.direct_info("yok-boyle", None, None) == {"master": ""}, "doğrudan indirme: kaydı yoksa bilgisayar üzerinden")

# ---- 🎞 indirme için bilgisayarda küçültme: iş → hazır dosya (parça parça indirilir) → telefon bitirince silinir
import types as _types
_pl, _tc = srv.play, srv.transcode
srv.play = lambda *a, **k: _types.SimpleNamespace(sid="x", res=_types.SimpleNamespace(duration=100), item={"title": "Deneme"})
srv.transcode = lambda src, out, h, d, prog: (prog(50), out.write_bytes(bytes(range(256)) * 40))
code, j = post(B + "/api/transcode/add", {"id": "movie-x", "height": 480}, key)
code, st0 = post(B + "/api/transcode/status", {"job": j["job"]}, key)
check(st0["state"] == "wait" and srv.tc_next() and not srv.tc_next(), "küçültme: iş sıraya girdi ve yapıldı")
code, st1 = post(B + "/api/transcode/status", {"job": j["job"]}, key)
check(st1["state"] == "ready" and st1["size"] == 10240, f"küçültme: hazır ({st1})")
req = urllib.request.Request(B + f"/dl/{j['job']}.mp4", headers={"Range": "bytes=100-199"})
with urllib.request.urlopen(req) as rr:
    part = rr.read(); cr = rr.headers.get("Content-Range")
check(part == (bytes(range(256)) * 40)[100:200] and cr == "bytes 100-199/10240", f"küçültme: dosya parça parça iniyor ({cr})")
code, _ = post(B + "/api/transcode/done", {"job": j["job"]}, key)
check(not (srv.tc_dir / f"{j['job']}.mp4").exists(), "küçültme: telefon indirince dosya silindi")
srv.play, srv.transcode = _pl, _tc

# cihazın uzun sorgusu: panelin komutu gelir gelmez cihaza
import threading as _th2
_cg = {}
def _cl():
    t0 = time.time(); c, body, _ = get(B + "/api/wish/cmds?wait=20", {"X-GFlix-Key": key}); _cg.update(dt=time.time() - t0, c=json.loads(body)["cmds"])
_t2 = _th2.Thread(target=_cl); _t2.start(); time.sleep(0.8)
post(B + "/api/wish/cmd", {"cmd": {"op": "clear"}}, key)
_t2.join(10)
check(_cg.get("c") and _cg["c"][0]["op"] == "clear" and _cg["dt"] < 4, f"uzaktan liste: komut cihaza anında geçti ({_cg.get('dt', 0):.1f} sn)")

# ---- istek özelliği kaldırıldı
code, _ = post(B + "/api/requests/add", {"tmdb": "tv/1001", "title": "Örnek Dizi"}, key)
check(code == 404 and not hasattr(srv, "requests"), "istek özelliği yok (kaldırıldı)")
check(yayin.Server.episode_no("Kızılcık Şerbeti 85. Bölüm @showtv") == 85 and yayin.Server.episode_no("Bölüm 7 | Full") == 7, "bölüm no başlıktan")

srv.stop()
site.shutdown()
import shutil  # noqa: E402
shutil.rmtree(MOCK, ignore_errors=True)
print("\nSONUÇ:", "HEPSİ GEÇTİ" if not FAILS else f"{len(FAILS)} HATA: {FAILS}")
sys.exit(1 if FAILS else 0)
