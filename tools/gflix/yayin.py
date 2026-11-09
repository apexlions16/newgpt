"""
Linkten anlık yayın: bilgisayar (Hasan) aracıdır.

Telefonda ya da TV'de bir film/bölüme basılınca istek buraya gelir. Bilgisayar yapımın sitedeki sayfasını açıp o anki
yayın linkini çözer (resolve.capture_page → choose_stream → pick_audios / pick_subs) ve HLS'i kendi üzerinden aktarır:
listeleri yeniden yazar (adresler bilgisayarı gösterir), parçaları sitenin istediği Referer/UA/çerezle çekip anında
cihaza akıtır. Diske video yazılmaz. GitHub'a hiçbir şey gitmez.

Uç noktalar (/api/ping, /hls/, /subs/ dışındakiler X-GFlix-Key başlığı ya da ?k= ister):
  GET  /api/ping                         → {"gflix", "yayin", "host", "auth"}
  GET  /api/catalog[?tv=1]               → katalog; kaynağı bilinen film/bölüm "stream": {"live": true}. tv=1: zamanı
                                           gelmemiş yayınlar gizlenir (desktop/server.js applySchedule ile aynı)
  POST /api/play {id, season?, episode?, capture?}
                                         → {"session", "hls", "subs": [...], "audio": [...], "height", "duration"}
  GET  /hls/<oturum>/master.m3u8         → bilgisayarın ürettiği ana liste (görüntü + Türkçe/orijinal ses)
  GET  /hls/<oturum>/<iz>.m3u8           → sitenin listesi, adresleri bilgisayara çevrilmiş
  GET  /hls/<oturum>/<iz>/<n>.<uzantı>   → parça (siteden anında aktarılır; Range desteklenir)
  GET  /subs/<oturum>/<n>.vtt            → altyazı (SRT ise VTT'ye çevrilir)
  POST /api/tv/open {id, season?, episode?, position?, session?}   → TV'ye "aç" komutu
  GET  /api/tv/next?after=N&wait=25      → TV bekler (uzun bekleme); komut gelince döner
  GET  /api/sources, POST /api/source    → sayfa adresleri (kaynaklar.json, sadece bu bilgisayarda)
  GET  /log?after=N                      → canlı kayıt

Kurallar: siteden aynı anda tek çözme (görünmez tarayıcı) yapılır; parça istekleri hls.HostLimit ile sınırlanır,
429'da beklenir. Link süresi dolarsa (403/404/410) oturum sessizce yeniden çözülür, oynatma aynı adreslerle sürer.
Site korumasını aşan kod yok: sayfa çözülemezse telefondaki Sayfadan Yakala'nın yakaladığı (capture) kullanılır.
"""
from __future__ import annotations

import hmac
import ipaddress
import json
import os
import re
import secrets
import socket
import tempfile
import threading
import time
from collections import deque
from datetime import datetime, timedelta, timezone
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urljoin, urlparse

import requests

from . import hls, resolve, youtube
from .util import LANG_LABEL

DEFAULT_PORT = 8766
SESSION_TTL = 6 * 3600          # izlenmeyen oturum bu kadar sonra unutulur
REUSE_SEC = 10 * 60             # aynı film/bölüm bu süre içinde yeniden istenirse aynı oturum (TV'de aç hızlı olsun)
SEG_TIMEOUT = (10, 30)
HOST_PARALLEL = 6               # siteye aynı anda en fazla bu kadar parça isteği (izleme başına değil, site başına)
EXPIRED = (401, 403, 404, 410)
_SEG_EXT = (".ts", ".m4s", ".mp4", ".aac", ".m4a", ".mp3", ".ac3", ".ec3", ".webvtt", ".vtt", ".cmfv", ".cmfa")
_KEY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"     # karışan harfler (0/O, 1/I) yok: TV kumandasıyla kolay yazılır


def log(msg: str) -> None:
    print(msg, flush=True)


# ------------------------------------------------------------------ anahtar

def new_key() -> str:
    raw = "".join(secrets.choice(_KEY_ALPHABET) for _ in range(12))
    return f"{raw[:4]}-{raw[4:8]}-{raw[8:]}"


def load_key(path: Path) -> str:
    """Bilgisayarda bir kez üretilen paylaşılan anahtar (telefon QR ile ya da elle, TV elle girer)."""
    try:
        k = path.read_text().strip()
        if k:
            return k
    except OSError:
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    k = new_key()
    path.write_text(k + "\n")
    os.chmod(path, 0o600)
    return k


def norm_key(k: str) -> str:
    return re.sub(r"[^A-Z0-9]", "", (k or "").upper())


def allowed_client(ip: str) -> bool:
    """Sadece ev ağı, bu bilgisayar ve Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48). İnternetten gelen her şey reddedilir."""
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    if a.version == 6 and a.ipv4_mapped:
        a = a.ipv4_mapped
    if a.is_loopback or a.is_link_local:
        return True
    if a.version == 4:
        return any(a in ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10"))
    return a in ipaddress.ip_network("fd7a:115c:a1e0::/48") or a in ipaddress.ip_network("fc00::/7")


def local_ips() -> list[str]:
    ips: list[str] = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            if info[4][0] not in ips and not info[4][0].startswith("127."):
                ips.append(info[4][0])
    except OSError:
        pass
    return ips


# ------------------------------------------------------------------ kaynaklar (sitedeki sayfa adresleri; sadece bu bilgisayarda)

class Sources:
    """<iş klasörü>/kaynaklar.json:
    {"items": {"movie-x": {"url": "…"}, "series-y": {"episodes": {"1:2": "…"}, "pattern": "…/sezon-{s}/bolum-{e}"}}}
    Kalıpta {s} {e} (sayı) ve {s2} {e2} (iki haneli: 01, 02) kullanılır."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.Lock()
        self.data = {"items": {}}
        self.mtime = 0.0
        self._load()

    def _load(self) -> None:
        try:
            m = self.path.stat().st_mtime
        except OSError:
            return
        if m == self.mtime:
            return
        try:
            d = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(d.get("items"), dict):
                self.data, self.mtime = d, m
        except (OSError, ValueError) as e:
            log(f"⚠ kaynaklar.json okunamadı: {e}")

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp")
        tmp.write_text(json.dumps(self.data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)
        self.mtime = self.path.stat().st_mtime

    def entry(self, item_id: str) -> dict:
        with self.lock:
            self._load()
            return dict(self.data["items"].get(item_id) or {})

    def all(self) -> dict:
        with self.lock:
            self._load()
            return json.loads(json.dumps(self.data["items"]))

    def url_for(self, item_id: str, season: int | None = None, episode: int | None = None) -> str | None:
        e = self.entry(item_id)
        if season is None:
            return e.get("url")
        u = (e.get("episodes") or {}).get(f"{season}:{episode}")
        if u:
            return u
        pat = e.get("pattern")
        if pat:
            return pat.replace("{s2}", f"{season:02d}").replace("{e2}", f"{episode:02d}").replace("{s}", str(season)).replace("{e}", str(episode))
        return None

    def set(self, item_id: str, url: str | None = None, season: int | None = None, episode: int | None = None,
            pattern: str | None = None) -> None:
        for u in (url, pattern):
            if u and not re.match(r"^https?://", u):
                raise ValueError("adres http:// ya da https:// ile başlamalı")
        with self.lock:
            self._load()
            e = self.data["items"].setdefault(item_id, {})
            if pattern is not None:
                e["pattern"] = pattern
            if url is not None:
                if season is None:
                    e["url"] = url
                else:
                    e.setdefault("episodes", {})[f"{season}:{episode}"] = url
            e["updated"] = int(time.time())
            self._save()

    def set_trailer(self, item_id: str, url: str, lang: str = "tr") -> None:
        """Yapımın fragmanı: YouTube linki (dil başına bir tane). TMDB'deki fragmanın yerine geçer."""
        if not youtube.video_id(url):
            raise ValueError("YouTube linki anlaşılamadı")
        with self.lock:
            self._load()
            e = self.data["items"].setdefault(item_id, {})
            e.setdefault("trailers", {})[lang] = url
            e["updated"] = int(time.time())
            self._save()

    def mark_bad_trailer(self, item_id: str, vid: str) -> None:
        """Telefondan 'Yanlış fragman': bu video bu yapım için bir daha seçilmez (sıradaki aday gelir)."""
        with self.lock:
            self._load()
            e = self.data["items"].setdefault(item_id, {})
            bad = e.setdefault("bad_trailers", [])
            if vid not in bad:
                bad.append(vid)
            for lang, url in list((e.get("trailers") or {}).items()):
                if youtube.video_id(url) == vid:
                    e["trailers"].pop(lang)
            e["updated"] = int(time.time())
            self._save()

    def remove(self, item_id: str, season: int | None = None, episode: int | None = None, pattern: bool = False) -> bool:
        """Sadece kaynak adresini unutur (video/kayıt yok). Kullanıcının kendi komutuyla çağrılır."""
        with self.lock:
            self._load()
            e = self.data["items"].get(item_id)
            if not e:
                return False
            if pattern:
                hit = e.pop("pattern", None) is not None
            elif season is None:
                hit = e.pop("url", None) is not None
            else:
                hit = (e.get("episodes") or {}).pop(f"{season}:{episode}", None) is not None
            if not any(e.get(k) for k in ("url", "episodes", "pattern", "trailers", "bad_trailers")):
                self.data["items"].pop(item_id, None)
            self._save()
            return hit


class JsonStore:
    """Küçük JSON dosyası (bellekte tutulur, ara ara yazılır)."""

    def __init__(self, path: Path, default: dict):
        self.path = path
        self.lock = threading.Lock()
        self.dirty = False
        try:
            self.data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.data = default

    def flush(self) -> None:
        with self.lock:
            if not self.dirty:
                return
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_name(self.path.name + ".tmp")
            tmp.write_text(json.dumps(self.data, ensure_ascii=False), encoding="utf-8")
            tmp.replace(self.path)
            self.dirty = False


class Captures:
    """Telefonun Sayfadan Yakala ile yakaladığı yayın (linkler, başlıklar, altyazılar): <iş klasörü>/yakalamalar/*.json (600).
    Bilgisayar sayfayı kendisi açamadığında (site insan kontrolü istiyorsa) bunu kullanır. Sadece bu bilgisayarda durur."""
    MAX_AGE = 12 * 3600

    def __init__(self, folder: Path):
        self.dir = folder

    def _file(self, item_id: str, season: int | None, episode: int | None) -> Path:
        name = re.sub(r"[^a-z0-9-]", "", item_id.lower())[:120] or "x"
        if season is not None:
            name += f"_S{int(season)}E{int(episode)}"
        return self.dir / f"{name}.json"

    def save(self, item_id: str, season: int | None, episode: int | None, capture: dict) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        os.chmod(self.dir, 0o700)
        f = self._file(item_id, season, episode)
        tmp = f.with_name(f.name + ".tmp")
        tmp.write_text(json.dumps({"saved": int(time.time()), "capture": capture}, ensure_ascii=False), encoding="utf-8")
        os.chmod(tmp, 0o600)
        tmp.replace(f)

    def get(self, item_id: str, season: int | None, episode: int | None) -> dict | None:
        try:
            d = json.loads(self._file(item_id, season, episode).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None
        # Yaş sınırı yok: linkin hâlâ çalışıp çalışmadığına oynatırken bakılır (alive). Çalışıyorsa anında açılır.
        cap = d.get("capture") or None
        if cap is not None:
            cap = {**cap, "_saved": d.get("saved") or 0}
        return cap


# ------------------------------------------------------------------ katalog

def apply_schedule(c: dict, now: float | None = None) -> dict:
    """desktop/server.js applySchedule'ın aynısı (TV): zamanı gelmemiş yapım/bölümler gizlenir, nextReleaseAt ve
    episodePlan eklenir, süresi dolan "gizle" notlu yapımlar çıkarılır."""
    now = time.time() if now is None else now
    c["titles"] = [t for t in c.get("titles") or [] if not any(n.get("hide") and n.get("until") and n["until"] <= now for n in t.get("notes") or [])]
    for t in c["titles"]:
        eps = [e.get("releaseAt") or 0 for s in t.get("seasons") or [] for e in s.get("episodes") or []]
        eps = [x for x in eps if x > now]
        plan = [x.get("at") or 0 for x in t.get("releasePlan") or [] if not (x.get("whenComplete") and not x.get("complete"))]
        plan = [x for x in plan if x > now]
        nxt = t["releaseAt"] if (t.get("releaseAt") or 0) > now else (min(eps + plan) if eps or plan else 0)
        if nxt:
            t["nextReleaseAt"] = nxt
        fut = sorted(((s.get("number"), e.get("number"), e.get("releaseAt")) for s in t.get("seasons") or []
                      for e in s.get("episodes") or [] if (e.get("releaseAt") or 0) > now), key=lambda x: (x[2], x[0], x[1]))
        plan_rows: list[dict] = []
        for season, n, at in fut:
            last = plan_rows[-1] if plan_rows else None
            if last and last["season"] == season and last["at"] == at and last["to"] == n - 1:
                last["to"] = n
            else:
                plan_rows.append({"season": season, "from": n, "to": n, "at": at})
        if plan_rows:
            t["episodePlan"] = plan_rows
        if (t.get("releaseAt") or 0) > now:
            t.pop("stream", None)
            t["seasons"] = []
            continue
        if isinstance(t.get("seasons"), list):
            t["seasons"] = [{**s, "episodes": [e for e in s.get("episodes") or [] if (e.get("releaseAt") or 0) <= now]} for s in t["seasons"]]
            t["seasons"] = [s for s in t["seasons"] if s["episodes"]]
    return c


def _held(t: dict, season: int, ep: int, now: float) -> bool:
    """Yayın planı bu bölümü tutuyor mu (zamanı gelmemiş ya da 'hepsi hazır olunca' bekleyen aralık)."""
    for p in t.get("releasePlan") or []:
        if p.get("season") == season and (p.get("from") or 1) <= ep <= (p.get("to") or 10_000):
            if (p.get("at") or 0) > now or (p.get("whenComplete") and not p.get("complete")):
                return True
    return False


def live_catalog(c: dict, src: Sources, now: float | None = None, checks: dict | None = None) -> dict:
    """Kaynağı bilinen film/bölümlere "stream": {"live": true}. Dizide sayfa kalıbı (pattern) varsa TMDB'deki bütün
    bölümler (yayın planının tuttukları hariç) listeye eklenir. Kaynak adresleri katalogla gönderilmez."""
    now = time.time() if now is None else now
    items = src.all()
    for t in c.get("titles") or []:
        e = items.get(t.get("id")) or {}
        live_trailers(t, e, checks)
        if t.get("type") != "series":
            old = t.get("stream") or {}
            t["stream"] = old if old.get("direct") else ({"live": True, **{k: old[k] for k in ("audio", "duration") if old.get(k)}} if e.get("url") else None)
            continue
        # "İlk N bölüm hazır olunca birlikte aç": yeni düzende 'hazır' = kaynağı onaylanmış (ya da bölüm kalıbı var)
        for pl in t.get("releasePlan") or []:
            if pl.get("whenComplete") and pl.get("to"):
                rng = range(int(pl.get("from") or 1), int(pl["to"]) + 1)
                have = len(rng) if e.get("pattern") else sum(1 for n in rng if f"{pl.get('season')}:{n}" in (e.get("episodes") or {}))
                pl.update(have=have, total=len(rng), complete=have == len(rng))
        seasons = {s.get("number"): s for s in t.get("seasons") or []}
        if e.get("pattern"):
            for ts in t.get("tmdbSeasons") or []:
                s = seasons.setdefault(ts["n"], {"number": ts["n"], "name": f"{ts['n']}. Sezon", "episodes": []})
                have = {x.get("number") for x in s["episodes"]}
                for n in range(1, (ts.get("c") or 0) + 1):
                    if n not in have and not _held(t, ts["n"], n, now):
                        s["episodes"].append({"number": n, "title": f"Bölüm {n}", "stream": None, "releaseAt": None})
                s["episodes"].sort(key=lambda x: x.get("number") or 0)
        for se in (e.get("episodes") or {}):          # onaylanmış ama katalog henüz yenilenmemiş bölüm
            try:
                sn, en = (int(x) for x in se.split(":"))
            except ValueError:
                continue
            ss = seasons.setdefault(sn, {"number": sn, "name": f"{sn}. Sezon", "episodes": []})
            if not any(x.get("number") == en for x in ss["episodes"]):
                ss["episodes"].append({"number": en, "title": f"Bölüm {en}", "stream": None, "releaseAt": None})
                ss["episodes"].sort(key=lambda x: x.get("number") or 0)
        for s in seasons.values():
            for ep in s.get("episodes") or []:
                ok = bool((e.get("episodes") or {}).get(f"{s.get('number')}:{ep.get('number')}") or e.get("pattern"))
                old = ep.get("stream") or {}
                ep["stream"] = old if old.get("direct") else ({"live": True, **{k: old[k] for k in ("audio", "duration") if old.get(k)}} if ok else None)
        for s_ in seasons.values():           # tamamlanmamış "birlikte aç" aralığındaki bölümler gizli
            s_["episodes"] = [x for x in s_.get("episodes") or [] if not _held(t, s_.get("number"), x.get("number") or 0, now)]
        t["seasons"] = [seasons[k] for k in sorted(seasons, key=lambda x: x or 0) if seasons[k].get("episodes")]
    return c


def fold(s: str) -> str:
    s = (s or "").replace("İ", "i").replace("I", "ı").lower()
    s = s.translate(str.maketrans("ışçğöüâîû", "iscgouaiu"))
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


_STOP = {"the", "and", "of", "a", "an", "ve", "bir", "ile", "da", "de", "official", "trailer", "teaser", "fragman", "film", "movie"}


def title_matches(names: list[str], video_title: str) -> bool:
    """YouTube videosunun başlığı yapımın adlarından birini taşıyor mu (yanlış yapımın fragmanını elemek için)."""
    vt = fold(video_title)
    vc = vt.replace(" ", "")
    vw = set(vt.split())
    judged = False
    for n in names:
        f = fold(n)
        if not f:
            continue
        c = f.replace(" ", "")
        if len(c) >= 3:
            judged = True
            if c in vc:
                return True
        words = [w for w in f.split() if len(w) >= 3 and w not in _STOP]
        if len(words) >= 2 and sum(w in vw for w in words) >= max(2, round(len(words) * 0.6)):
            return True
    return not judged       # çok kısa adlar (ör. iki harf) güvenle karşılaştırılamaz: elenmez


def title_names(t: dict) -> list[str]:
    return [x for x in [t.get("title"), t.get("originalTitle")] + list((t.get("names") or {}).values()) if x]


def trailer_candidates(t: dict, e: dict, checks: dict | None = None) -> dict[str, list[str]]:
    """Dil → aday YouTube kimlikleri (sırayla). Elle verilen link önce; TMDB'dekilerden 'yanlış' işaretlenenler ve
    başlığı yapımın adını taşımayanlar (bilgisayarın kontrolü) atlanır."""
    checks = checks or {}
    bad = set(e.get("bad_trailers") or [])
    out: dict[str, list[str]] = {}
    for lang, url in (e.get("trailers") or {}).items():
        vid = youtube.video_id(url)
        if vid and vid not in bad:
            out.setdefault(lang, []).append(vid)
    rank = {"Trailer": 0, "Teaser": 1}
    for v in sorted(t.get("tmdbVideos") or [], key=lambda v: (not v.get("official"), rank.get(v.get("type"), 2), -(v.get("size") or 0))):
        vid = v.get("key")
        if not vid or not youtube.video_id(vid) or vid in bad or (checks.get(vid) or {}).get("ok") is False:
            continue
        lst = out.setdefault(v.get("lang") or "und", [])
        if vid not in lst:
            lst.append(vid)
    return out


def live_trailers(t: dict, e: dict, checks: dict | None = None) -> None:
    """Fragmanlar da anlık yayın: elle verilen YouTube linki, yoksa TMDB'deki YouTube fragmanları (dil başına en iyisi).
    Oynatıcı /yt/<kimlik>/master.m3u8 ister; bilgisayar o an çözer. Hiçbir fragman dosyası tutulmaz."""
    out = {lang: vids[0] for lang, vids in trailer_candidates(t, e, checks).items() if vids}
    order = sorted(out, key=lambda l: (l != "tr", l != "en", l))
    lst = [{"live": True, "youtube": out[l], "lang": l, "master": ""} for l in order]
    t["trailers"] = lst or None
    t["trailer"] = lst[0] if lst else None


# ------------------------------------------------------------------ çözme

@dataclass
class Resolved:
    video: str
    headers: dict
    audios: list[tuple[str, str]] = field(default_factory=list)      # (dil, liste adresi)
    subs: list[dict] = field(default_factory=list)                   # {lang, label, forced, text}
    height: int = 0
    duration: float = 0.0
    variant: dict = field(default_factory=dict)                      # BANDWIDTH, RESOLUTION, CODECS …
    page: str = ""


def _variant_attrs(master_text: str, base: str, video: str) -> dict:
    lines = master_text.splitlines()
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-STREAM-INF:"):
            nxt = next((x.strip() for x in lines[i + 1:] if x.strip() and not x.startswith("#")), None)
            if nxt and urljoin(base, nxt) == video:
                return hls.attrs(line)
    return {}


def _tmdb_info(tmdb_key: str, kind: str, tmdb_id: int | None, season: int | None, episode: int | None) -> tuple[float | None, str]:
    """(beklenen süre sn, orijinal dil) — reklam/fragmanı ayırmak ve orijinal sesi seçmek için."""
    if not tmdb_key or not tmdb_id:
        return None, "en"
    from .tmdb import TMDB
    tm = TMDB(tmdb_key)
    expected, original = None, "en"
    try:
        d = tm.get(f"/{'tv' if kind == 'series' else 'movie'}/{tmdb_id}") or {}
        original = d.get("original_language") or "en"
        if kind == "series":
            ep = tm.get(f"/tv/{tmdb_id}/season/{season}/episode/{episode}") or {}
            rt = ep.get("runtime") or (d.get("episode_run_time") or [None])[0]
        else:
            rt = d.get("runtime")
        expected = float(rt) * 60 if rt else None
    except Exception:  # noqa: BLE001
        pass
    return expected, original


def resolve_play(page: str, kind: str, tmdb_id: int | None, season: int | None, episode: int | None, tmdb_key: str,
                 capture: dict | None = None, log=log, max_height: int = 0) -> Resolved:
    """Sayfadan (ya da telefonun yakaladığından) o anki yayın: en iyi görüntü (ya da indirme için istenen kalite),
    Türkçe + orijinal ses, bütün altyazılar."""
    t0 = time.time()
    if not capture and youtube.is_youtube(page):
        return youtube_resolved(youtube.video_id(page), log)
    if capture:
        cap = resolve.capture_from_json(capture)
        log(f"  📱 telefonun yakaladığı kullanılıyor: {len(cap.masters)} ana liste, {len(cap.media)} tekil liste")
    else:
        cap = None
        if ".m3u8" in urlparse(page).path.lower():
            r = resolve.http_get(page, headers={"User-Agent": resolve.CHROME_UA}, timeout=20)
            if r.ok and hls.is_playlist(r.content):
                text = r.content.decode("utf-8", "replace")
                cap = resolve.Capture(page, masters=[(page, text, {})]) if "#EXT-X-STREAM-INF" in text else resolve.Capture(page, media=[(page, text, {})])
        if cap is None:
            # Masaüstü varsa görünür pencere + nazik mod (telefondaki sade mod gibi: betik yok, aralıklı tıklama)
            cap = resolve.capture_page(page, log, timeout=120, visible=_has_display(), gentle=True, ghost=True, stay=3)
        if not cap.masters and not cap.media:
            raise RuntimeError("Sayfada video yayını bulunamadı (site insan kontrolü istiyor olabilir). "
                               "Telefonda Stüdyo → 🌐 Sayfadan Yakala ile bu sayfayı bir kez aç.")
    expected, original = _tmdb_info(tmdb_key, kind, tmdb_id, season, episode)
    m, h, vdur = resolve.choose_stream(cap, expected, log, max_height) if max_height else resolve.choose_stream(cap, expected, log)
    if not m.video:
        raise RuntimeError("Yayın listesinde görüntü yok")
    headers = {"User-Agent": resolve.CHROME_UA, **(h or getattr(cap, "headers", {}) or {})}
    master_url, master_text = next(((u, t) for u, t, _ in cap.masters if read_has(t, u, m.video)), ("", ""))
    variant = _variant_attrs(master_text, master_url, m.video) if master_text else {}
    audios = [(lang, r.url) for lang, r in resolve.pick_audios(m.audios, original)]
    sub_cands = [(r.name + " " + (r.lang or ""), r.url) for r in m.subs] + [(Path(urlparse(u).path).name, u) for u in cap.subs]
    subs: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="gflix-yayin-") as tmp:     # altyazılar küçük; okunur okunmaz silinir
        for lang, path, forced in resolve.pick_subs(sub_cands, headers, Path(tmp), log, video_dur=vdur,
                                                    texts=getattr(cap, "sub_texts", None)):
            from .subs import to_webvtt
            subs.append({"lang": lang, "label": LANG_LABEL.get(lang, lang) + (" (zorunlu)" if forced else ""),
                         "forced": forced, "text": to_webvtt(path.read_bytes())})
    log(f"  ✓ çözüldü ({time.time() - t0:.1f} sn): {m.height or '?'}p, {round(vdur / 60)} dk · ses: "
        + (", ".join(l for l, _ in audios) or "görüntünün içinde") + " · altyazı: " + (", ".join(s["lang"] for s in subs) or "yok"))
    return Resolved(m.video, headers, audios, subs, m.height, vdur, variant, page)


class NeedsMux(RuntimeError):
    """YouTube ne HLS ne dizinli DASH verdi: /yt/ yolu (bellekte birleştirme) kullanılmalı."""

    def __init__(self, choice: dict):
        super().__init__("YouTube: birleştirme gerekli")
        self.choice = choice


def youtube_resolved(vid: str, log=log) -> Resolved:
    """YouTube → tam uzunlukta, sarılabilir yayın. HLS varsa o; yoksa ayrı görüntü + ses dosyalarının dizinlerinden
    üretilen HLS (görüntü ve ses ayrı izler). İkisi de olmazsa NeedsMux."""
    info = youtube.probe(vid, log)
    c = youtube.choose(info)
    page = f"https://www.youtube.com/watch?v={vid}"
    ua = {"User-Agent": resolve.CHROME_UA, **(c.get("headers") or {})}
    dur = float(info.get("duration") or 0)
    if c["kind"] == "hls":
        return Resolved(c["url"], ua, [], [], c["height"], dur, c["variant"], page)
    if c["kind"] == "dash":
        vt = youtube.dash_playlist(c["video"], ua)
        at = youtube.dash_playlist(c["audio"], ua) if vt else None
        if vt and at:
            kv, ka = f"ytdash://{vid}/v.m3u8", f"ytdash://{vid}/a.m3u8"
            hls.PRELOADED[kv], hls.PRELOADED[ka] = vt, at
            variant = {"BANDWIDTH": str(int((c.get("tbr") or 3000) * 1000))}
            if c.get("width") and c.get("height"):
                variant["RESOLUTION"] = f"{c['width']}x{c['height']}"
            codecs = ",".join(x for x in (c.get("vcodec"), c.get("acodec")) if x and x != "none")
            if codecs:
                variant["CODECS"] = codecs
            lang = (c.get("lang") or "und").split("-")[0]
            log(f"  🎞 YouTube: {c['height']}p, tam uzunlukta (sarılabilir)")
            return Resolved(kv, ua, [(lang, ka)], [], c["height"], dur, variant, page)
    raise NeedsMux(c)


def read_has(text: str, base: str, video: str) -> bool:
    return any(urljoin(base, ln.strip()) == video for ln in text.splitlines() if ln.strip() and not ln.startswith("#"))


# ------------------------------------------------------------------ oturum ve proxy

class Track:
    def __init__(self, name: str, url: str):
        self.name, self.url = name, url
        self.text: str | None = None          # yeniden yazılmış liste
        self.segs: list[str] = []
        self.keys: list[str] = []
        self.maps: list[str] = []
        self.ext = ".ts"


class Session:
    def __init__(self, sid: str, key: tuple, item: dict, page: str, res: Resolved, capture: dict | None):
        self.sid, self.key, self.item, self.page, self.capture = sid, key, item, page, capture
        self.created = self.used = time.time()
        self.lock = threading.Lock()
        self.gen = 0
        self.http = requests.Session()
        self._apply(res)

    def _apply(self, res: Resolved) -> None:
        self.res = res
        self.tracks = {"v": Track("v", res.video)}
        for i, (_lang, url) in enumerate(res.audios):
            self.tracks[f"a{i}"] = Track(f"a{i}", url)
        self.gen += 1


class Server:
    def __init__(self, workdir: Path, key: str, catalog_path: Path, tmdb_key: str = "", port: int = DEFAULT_PORT,
                 host_name: str = "", resolver=resolve_play, items_dir: Path | None = None,
                 archive_cfg: dict | None = None):
        self.workdir = workdir
        # Yapım kayıtları sadece bu bilgisayarda (adlar GitHub'a gitmez); katalog TMDB'den burada üretilir
        self.items_dir = items_dir
        self.build_lock = threading.Lock()
        self.last_build = 0.0
        self.key = norm_key(key)
        self.catalog_path = catalog_path
        self.tmdb_key = tmdb_key
        self.port = port
        self.host_name = host_name or socket.gethostname()
        self.sources = Sources(workdir / "kaynaklar.json")
        self.captures = Captures(workdir / "yakalamalar")
        self.tchecks = JsonStore(workdir / "fragman-kontrol.json", {})          # YouTube kimliği → {ok, title, at}
        self.usage = JsonStore(workdir / "istatistik.json", {"bytes": {}, "plays": {}})
        self.links = JsonStore(workdir / "link-kontrol.json", {})
        self.pending = JsonStore(workdir / "bekleyenler.json", {"items": []})   # onay bekleyen linkler
        self.sizes = JsonStore(workdir / "boyutlar.json", {})                    # "id" / "id|S:E" → {bytes, dur, bps}
        self.news = JsonStore(workdir / "duyurular.json", {"seen": {}, "items": []})  # TMDB'de yeni sezon/bölüm duyuruları
        self.refresh_req: dict[str, dict] = {}         # süresi dolmuş yayın: telefon yeniden yakalasın (id|S|E → {page, title})
        self.refresh_lock = threading.RLock()
        self.refresh_cv = threading.Condition(self.refresh_lock)   # tazeleme isteği geldi / bitti: bekleyenler uyansın
        self.pc_fail_streak = 0                    # bilgisayarın kendi tarayıcısıyla üst üste başarısız açma
        self.pc_fail_at = 0.0
        self.pccap: dict[str, dict] = {}            # bilgisayarda yakalama işleri (İstek listesinden): jid → {url, hint, state, note, at}
        self.pccap_lock = threading.Lock()
        self.capture_fn = resolve.capture_page       # testte sahtesi verilir
        self.playlog = JsonStore(workdir / "izlemeler.json", {"items": []})     # ne izlendi (yapım/bölüm/zaman): gece önceden yenileme için
        self.prefetch_cfg = JsonStore(workdir / "on-yenileme.json", {"on": True, "from": "03:00", "to": "07:00", "max": 25, "done": {}})
        self.tc_jobs: dict[str, dict] = {}                 # 🎞 bilgisayarda küçültme (indirme için): iş → durum
        self.tc_lock = threading.Lock()
        self.tc_dir = workdir / "donusum"
        self.wish = JsonStore(workdir / "istek-listesi.json", {"state": None, "at": 0, "device": ""})  # yakalama cihazının (TV kutusu) son durumu
        self.wish_cmds: list[dict] = []                          # telefondaki yönetim panelinden gelen komutlar (cihaz alır)
        self.wish_lock = threading.Lock()
        self.wish_cv = threading.Condition(self.wish_lock)        # komut gelince bekleyen cihaz hemen uyansın
        self.newep_checks = JsonStore(workdir / "yeni-bolum.json", {"next": {}})  # yeni bölüm: ne zaman yine bakılsın (günde bir)
        self.follows = JsonStore(workdir / "takip.json", {"items": []})         # YouTube kanal/liste takibi (dizi yeni bölümleri)
        self.yt_latest = youtube.latest           # testte sahtesi verilir
        self.themes = JsonStore(workdir / "tema.json", {})                       # yapım → {vid, title, at, bad: [...]}
        self.yt_search = youtube.search          # testte sahtesi verilir
        self.yt_probe = youtube.probe
        self.audio_cache: dict[str, tuple[float, dict]] = {}                      # video → (zaman, {url, headers, ext})
        self.tmdb_factory = None        # testte sahte TMDB verilebilir               # anahtar → {state, detail, at, since, ...}
        self.sessions: dict[str, Session] = {}
        self.slock = threading.Lock()
        self.site_slot = threading.Lock()          # siteden aynı anda tek çözme (kullanıcı kararı: aynı anda 1 iş)
        self.resolver = resolver
        self.yt_lock = threading.Lock()
        self.muxes: dict[str, "youtube.Muxer"] = {}       # video kimliği → bellekte birleştirme
        self.tv_cmds: deque = deque(maxlen=50)
        self.tv_seq = 0
        self.tv_cv = threading.Condition()
        self.httpd: ThreadingHTTPServer | None = None
        app = Path(__file__).resolve().parents[2] / "tizen" / "app"
        self.tv_dir: Path | None = app if app.is_dir() else None
        from .archive_download import ArchiveQueue
        cfg = archive_cfg or {}
        self.archive = ArchiveQueue(workdir, enabled=bool(cfg.get("enabled")), access=cfg.get("access", ""),
                                    secret=cfg.get("secret", ""), prefix=cfg.get("prefix", ""),
                                    resolve_media=self._archive_resolve, completed=self._archive_completed, log=log)

    def _archive_resolve(self, job: dict) -> Resolved:
        t = self.find_title(job["item_id"]) or {}
        cap = self.captures.get(job["item_id"], job.get("season"), job.get("episode"))
        return self.resolver(job["url"], "series" if job.get("season") is not None else "movie",
                             t.get("tmdbId"), job.get("season"), job.get("episode"), self.tmdb_key,
                             capture=cap, log=lambda *_: None)

    def _archive_completed(self, job: dict, result: dict, archive) -> None:
        if not self.items_dir:
            raise RuntimeError("Yerel katalog dizini yok")
        item_id, se, ep = job["item_id"], job.get("season"), job.get("episode")
        path = self.items_dir / f"{item_id}.json"
        item = json.loads(path.read_text(encoding="utf-8"))
        video = {"parts": [result["video_url"]], "sizes": [result["video_size"]]}
        stream = {"direct": {"video": video, "audio": [], "videoAudio": []},
                  "subtitles": result["subs"], "duration": result.get("duration")}
        if se is None:
            item["stream"] = stream
        else:
            episodes = item.setdefault("episodes", [])
            row = next((x for x in episodes if x.get("season") == se and x.get("episode") == ep), None)
            if row is None:
                row = {"season": se, "episode": ep}
                episodes.append(row)
            row["stream"] = stream
            row["added"] = int(time.time())
        item["updated"] = int(time.time())
        temp = path.with_suffix(".tmp")
        temp.write_text(json.dumps(item, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temp.replace(path)
        self.rebuild(wait=True)
        if not self.catalog_path.is_file():
            raise RuntimeError("Katalog oluşturulamadı")
        archive.upload_file(f"{archive.prefix}-katalog", "catalog.json", self.catalog_path,
                            title="G-Flix içerik kataloğu", kind="data")

    def pccap_status(self, job: dict) -> dict:
        out = {k: v for k, v in job.items() if k != "hint"}
        if job.get("archiveJob"):
            state = self.archive.status(job["archiveJob"])
            out["state"] = "ok" if state.get("state") == "done" else "black" if state.get("state") == "failed" else "work"
            out["note"] = state.get("note", "")
        return out

    # ---- katalog (bilgisayarda üretilir)
    def needs_build(self) -> bool:
        if not self.items_dir or not self.items_dir.is_dir():
            return False
        try:
            built = self.catalog_path.stat().st_mtime
        except OSError:
            return True
        if not self.tmdb_key:
            return False        # TMDB anahtarı yokken var olan zengin kataloğu bilgisiz hâliyle ezme
        newest = max((f.stat().st_mtime for f in self.items_dir.glob("*.json")), default=0.0)
        return newest > built or time.time() - built > 20 * 3600       # yeni kayıt ya da günlük TMDB yenilemesi

    def rebuild(self, wait: bool = False) -> None:
        """Kataloğu kayıtlardan + TMDB'den üret (arka planda; aynı anda bir kez)."""
        if not self.items_dir:
            return

        def run():
            if not self.build_lock.acquire(blocking=False):
                return
            try:
                from .catalog import build
                import contextlib
                import io
                log("📚 katalog üretiliyor (TMDB)…")
                with contextlib.redirect_stdout(io.StringIO()):
                    c = build(self.items_dir.parent, self.tmdb_key, self.items_dir, self.catalog_path)
                self.last_build = time.time()
                log(f"📚 katalog hazır: {len(c.get('titles') or [])} yapım")
                try:
                    self.note_news()
                except Exception as e:  # noqa: BLE001
                    log(f"⚠ duyurular okunamadı: {str(e)[:160]}")
            except Exception as e:  # noqa: BLE001
                log(f"⚠ katalog üretilemedi: {str(e)[:200]}")
            finally:
                self.build_lock.release()
        if wait:
            run()
        else:
            threading.Thread(target=run, daemon=True).start()

    def add_item(self, kind: str, tmdb_id: int, title: str, year: int | None) -> str:
        """Sayfadan Yakala'da tanınan ama kayıtlarda olmayan yapım: kayıt oluşturulur, katalog yenilenir."""
        from .util import item_id_for
        if not self.items_dir:
            raise ValueError(f"\"{title}\" katalogda yok.")
        item_id = item_id_for("series" if kind == "tv" else "movie", title)
        f = self.items_dir / f"{item_id}.json"
        if not f.exists():
            now = int(time.time())
            d = {"id": item_id, "type": "series" if kind == "tv" else "movie", "title": title, "tmdbId": tmdb_id,
                 "year": year, "added": now, "updated": now}
            if kind == "tv":
                d["episodes"] = []
            self.items_dir.mkdir(parents=True, exist_ok=True)
            f.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            log(f"➕ yeni yapım kaydı: {title}")
            self.rebuild()
        return item_id

    # ---- içerik boyutu (dosya olsaydı kaç bayt): yayının bit hızı × süresi
    def note_size(self, item_id: str, season, episode, bps: float, dur: float) -> None:
        if not item_id or bps <= 0 or dur <= 0:
            return
        k = item_id if season is None else f"{item_id}|{season}:{episode}"
        with self.sizes.lock:
            if (self.sizes.data.get(k) or {}).get("exact"):
                return                     # kesin ölçülmüşü tahminle ezme
            self.sizes.data[k] = {"bytes": int(bps * dur / 8), "dur": int(dur), "bps": int(bps), "at": int(time.time())}
            self.sizes.dirty = True

    def note_session_size(self, s: "Session") -> None:
        try:
            bps = float(s.res.variant.get("BANDWIDTH") or 0)
        except (TypeError, ValueError):
            bps = 0
        if s.key[0] != "yt":
            self.note_size(s.key[0], s.key[1], s.key[2], bps, s.res.duration)

    # ---- kesin ölçüm: her parçanın boyutu siteye sorulup toplanır (kimse izlemiyorken, yavaş, tek tek)
    def _busy_except(self, sid: str | None = None) -> bool:
        now = time.time()
        with self.slock:
            busy = any(x.sid != sid and now - x.used < 180 for x in self.sessions.values())
        return busy or self.site_slot.locked()

    def _seg_size(self, s: "Session", url: str) -> int | None:
        r = self.fetch(s, url, {"Range": "bytes=0-0"}, stream=True)
        try:
            if r.status_code == 206:
                m = re.search(r"/(\d+)", r.headers.get("Content-Range") or "")
                return int(m.group(1)) if m else None
            if r.status_code == 200 and r.headers.get("Content-Length"):
                return int(r.headers["Content-Length"])
            return None
        finally:
            r.close()

    def measure_next(self) -> bool:
        """Kesin boyutu bilinmeyen bir yayındaki yapımı ölçer. Biri izlemeye başlarsa bırakır (sonra yeniden).
        İş yoksa ya da şu an uygun değilse False."""
        if self._busy_except():
            return False
        try:
            c = self.catalog(tv=False)
        except (OSError, ValueError):
            return False
        now = time.time()
        targets = []
        for t in c.get("titles") or []:
            if t.get("type") != "series":
                if t.get("stream"):
                    targets.append((t["id"], None, None))
            else:
                for sn in t.get("seasons") or []:
                    for e in sn.get("episodes") or []:
                        if e.get("stream"):
                            targets.append((t["id"], sn.get("number"), e.get("number")))
        with self.sizes.lock:
            data = dict(self.sizes.data)
        todo = None
        for k in targets:
            key = k[0] if k[1] is None else f"{k[0]}|{k[1]}:{k[2]}"
            v = data.get(key) or {}
            if not v.get("exact") and now - (v.get("fail_at") or 0) > 6 * 3600:
                todo = (k, key)
                break
        if not todo:
            return False
        (iid, se, ep), key = todo
        try:
            s = self.play(iid, se, ep, fresh=False, count=False)
            total = 0
            for name in list(s.tracks):
                self.media(s, name)
                tr = s.tracks[name]
                raw = hls.PRELOADED.get(tr.url) or ""
                ranges = re.findall(r"#EXT-X-BYTERANGE:(\d+)", raw)
                if ranges:                    # YouTube vb.: boyutlar listede yazılı
                    total += sum(int(x) for x in ranges)
                    m = re.search(r'BYTERANGE="(\d+)@', raw)
                    total += int(m.group(1)) if m else 0
                    continue
                for i, u in enumerate(tr.maps + tr.segs):
                    if i % 25 == 0 and self._busy_except(s.sid):
                        raise InterruptedError("izleyen var")
                    n = self._seg_size(s, u)
                    if n is None:
                        raise RuntimeError("parça boyutu alınamadı")
                    total += n
                    time.sleep(0.25)
            with self.sizes.lock:
                self.sizes.data[key] = {"bytes": total, "dur": int(s.res.duration), "exact": True, "at": int(time.time()),
                                        "bps": int(total * 8 / s.res.duration) if s.res.duration else 0}
                self.sizes.dirty = True
            t = self.find_title(iid) or {}
            log(f"📏 ölçüldü: {t.get('title') or iid}" + (f" S{se:02d}E{ep:02d}" if se is not None else "") + f" = {total / 1e9:.2f} GB")
        except InterruptedError:
            return False
        except Exception as e:  # noqa: BLE001
            with self.sizes.lock:
                self.sizes.data[key] = {**(self.sizes.data.get(key) or {}), "fail_at": int(time.time()), "err": str(e)[:150]}
                self.sizes.dirty = True
        return True

    @staticmethod
    def capture_size(cap: dict) -> tuple[float, float]:
        """Telefonun yakaladığından (bps, süre sn): ana listedeki en yüksek BANDWIDTH ve uzun yayının süresi."""
        bps = dur = 0.0
        for st in cap.get("streams") or []:
            t = st.get("text") or ""
            for line in t.splitlines():
                if line.startswith("#EXT-X-STREAM-INF"):
                    try:
                        bps = max(bps, float(hls.attrs(line).get("BANDWIDTH") or 0))
                    except ValueError:
                        pass
            dur = max(dur, float(st.get("duration") or 0))
        return bps, dur

    # ---- izleme sayacı (bu bilgisayardan geçen veri = internet kullanımı; diske video yazılmaz)
    def count(self, nbytes: int = 0, play: bool = False) -> None:
        day = time.strftime("%Y-%m-%d")
        with self.usage.lock:
            if nbytes:
                b = self.usage.data.setdefault("bytes", {})
                b[day] = b.get(day, 0) + nbytes
            if play:
                pl = self.usage.data.setdefault("plays", {})
                pl[day] = pl.get(day, 0) + 1
            self.usage.dirty = True

    def stats(self) -> dict:
        c = self.catalog(tv=False)
        titles = c.get("titles") or []
        movies = [t for t in titles if t.get("type") != "series"]
        series = [t for t in titles if t.get("type") == "series"]
        eps = [e for t in series for s in t.get("seasons") or [] for e in s.get("episodes") or []]
        today, month = time.strftime("%Y-%m-%d"), time.strftime("%Y-%m")
        with self.usage.lock:
            b = dict(self.usage.data.get("bytes") or {})
            pl = dict(self.usage.data.get("plays") or {})

        def tot(d, pre=""):
            return sum(v for k, v in d.items() if k.startswith(pre))
        own = 0
        for name in ("katalog", "yakalamalar", "uygulama", "kaynaklar.json", "fragman-kontrol.json", "istatistik.json", "link-kontrol.json", "bekleyenler.json", "boyutlar.json"):
            q = self.workdir / name
            if q.is_file():
                own += q.stat().st_size
            elif q.is_dir():
                own += sum(f.stat().st_size for f in q.rglob("*") if f.is_file())
        ram = sum(f.stat().st_size for m in list(self.muxes.values()) for f in m.dir.glob("*") if f.is_file())
        recent = sorted(titles, key=lambda t: t.get("added") or 0, reverse=True)[:5]
        # Yayındaki içeriğin toplam boyutu (dosya olsaydı): ölçülen bit hızı × süre; ölçülmemişlere ortalama bit hızı × TMDB süresi
        with self.sizes.lock:
            sizes = dict(self.sizes.data)
        measured = [v for v in sizes.values() if v.get("bps") and v.get("dur") and v.get("bytes")]
        avg_bps = (sum(v["bytes"] for v in measured) * 8 / max(1, sum(v["dur"] for v in measured))) if measured else 4_500_000
        content = n_meas = n_est = n_exact = minutes = 0
        for t in movies:
            if t.get("stream"):
                v = sizes.get(t["id"]) if (sizes.get(t["id"]) or {}).get("bytes") else None
                n_exact += 1 if (v or {}).get("exact") else 0
                mins = (v or {}).get("dur", 0) / 60 or (t.get("runtime") or 110)
                content += v["bytes"] if v else int(avg_bps * mins * 60 / 8)
                n_meas, n_est = (n_meas + 1, n_est) if v else (n_meas, n_est + 1)
                minutes += mins
        for t in series:
            for sn in t.get("seasons") or []:
                for e in sn.get("episodes") or []:
                    if not e.get("stream"):
                        continue
                    v = sizes.get(f"{t['id']}|{sn.get('number')}:{e.get('number')}")
                    v = v if (v or {}).get("bytes") else None
                    n_exact += 1 if (v or {}).get("exact") else 0
                    mins = (v or {}).get("dur", 0) / 60 or (e.get("runtime") or t.get("runtime") or 45)
                    content += v["bytes"] if v else int(avg_bps * mins * 60 / 8)
                    n_meas, n_est = (n_meas + 1, n_est) if v else (n_meas, n_est + 1)
                    minutes += mins
        return {
            "movies": len(movies), "series": len(series),
            "seasons": sum(len(t.get("seasons") or []) for t in series),
            "episodes": len(eps),
            "playableMovies": sum(1 for t in movies if t.get("stream")),
            "playableEpisodes": sum(1 for e in eps if e.get("stream")),
            "trailers": sum(1 for t in titles if t.get("trailer")),
            "trailersChecked": sum(1 for v in self.tchecks.data.values() if v.get("ok") is not None),
            "trailersWrong": sum(1 for v in self.tchecks.data.values() if v.get("ok") is False),
            "bytesToday": tot(b, today), "bytesMonth": tot(b, month), "bytesTotal": tot(b),
            "playsToday": tot(pl, today), "playsMonth": tot(pl, month), "playsTotal": tot(pl),
            "ownBytes": own, "ramBytes": ram, "deadLinks": len(self.alerts()["items"]), "pending": len(self.pending_list()),
            "contentBytes": content, "contentMeasured": n_meas, "contentEstimated": n_est, "contentMinutes": int(minutes),
            "contentExact": n_exact, "contentItems": n_meas + n_est,
            # linkler kaç saatte eskiyor (son 50 eskiyen linkin ortancası)
            "linkLifeHours": sorted(self.usage.data.get("linkLife") or [0])[len(self.usage.data.get("linkLife") or [0]) // 2] or None,
            "recent": [{"id": t.get("id"), "title": t.get("title"), "type": t.get("type"), "added": t.get("added")} for t in recent],
        }

    # ---- fragman kontrolü: seçilen YouTube videosunun başlığı yapımın adını taşıyor mu
    def check_next_trailer(self) -> bool:
        """Kontrol edilmemiş bir seçili fragmanı YouTube'dan kontrol eder (çağrı başına en fazla bir video). İş yoksa False."""
        try:
            c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        items = self.sources.all()
        now = time.time()
        for t in c.get("titles") or []:
            e = items.get(t.get("id")) or {}
            manual = {youtube.video_id(u) for u in (e.get("trailers") or {}).values()}
            for lang, vids in trailer_candidates(t, e, self.tchecks.data).items():
                vid = vids[0] if vids else None
                if not vid or vid in manual:
                    continue
                st = self.tchecks.data.get(vid) or {}
                if st.get("ok") is not None or now - (st.get("at") or 0) < 6 * 3600:
                    continue
                try:
                    info = youtube.probe(vid, log=lambda *_: None, timeout=60)
                    ok = title_matches(title_names(t), info.get("title") or "")
                    rec = {"ok": ok, "title": (info.get("title") or "")[:200], "at": int(now)}
                    if not ok:
                        log(f"🎞 yanlış fragman atlandı: {t.get('title')} [{lang}] ← \"{rec['title'][:80]}\"")
                except Exception as ex:  # noqa: BLE001
                    dead = bool(DEAD_YT.search(str(ex)))
                    rec = {"ok": False if dead else None, "err": str(ex)[:150], "at": int(now)}
                    if dead:
                        log(f"🎞 kaldırılmış fragman atlandı: {t.get('title')} [{lang}]")
                with self.tchecks.lock:
                    self.tchecks.data[vid] = rec
                    self.tchecks.dirty = True
                return True
        return False

    def tm(self):
        if self.tmdb_factory:
            return self.tmdb_factory()
        if not self.tmdb_key:
            return None
        from .tmdb import TMDB
        return TMDB(self.tmdb_key)

    # ---- onay kuyruğu: yakalanan/eklenen linkler önce buraya; kullanıcı TMDB ile eşleştirip onaylayınca yayına girer
    def queue_link(self, url: str, title: str, capture: dict | None, order: float | None = None, hint: dict | None = None) -> dict:
        guess = self.hint_guess(hint) if hint else None
        if not guess:
            guess = guess_link(self, url, title)
        pid = secrets.token_hex(6)
        if capture:
            self.captures.save(f"bekleyen-{pid}", None, None, capture)
        rec = {"pid": pid, "url": url, "page": title, "guess": guess, "created": int(time.time()), "hasCapture": bool(capture),
               "order": order if order is not None else time.time()}
        with self.pending.lock:
            # aynı sayfa zaten bekliyorsa yenisiyle değiştir (taze yakalama)
            self.pending.data["items"] = [x for x in self.pending.data.get("items") or [] if x.get("url") != url] + [rec]
            self.pending.dirty = True
        self.pending.flush()
        log(f"📥 onay bekliyor: {guess.get('label') or title or url[:60]}" + ("" if guess.get("ok") else f" — {guess.get('reason')}"))
        if hint and hint.get("auto") and guess.get("ok") and capture:
            # Yeni bölüm (kataloğdaki yabancı dizi, TMDB'den bölümü belli): onay beklemeden yayına
            r = self.approve([pid])
            if r and r[0].get("ok"):
                rec["approved"] = True
                rec["archiveJob"] = r[0].get("archiveJob")
        return rec

    def hint_guess(self, hint: dict) -> dict | None:
        """Telefonun İstek listesi: kullanıcı yapımı TMDB'den kendisi seçti; tahmin yerine o geçer (onay yine sorulur)."""
        target = parse_tmdb(str(hint.get("tmdb") or ""))
        if not target:
            return None
        kind, tid = target
        se = int(hint["season"]) if hint.get("season") is not None else None
        ep = int(hint["episode"]) if hint.get("episode") is not None else None
        if kind == "tv" and (se is None or ep is None):
            return None
        name = str(hint.get("title") or f"{kind}/{tid}")
        return {"ok": True, "kind": "series" if kind == "tv" else "movie", "tmdbId": tid, "season": se if kind == "tv" else None,
                "episode": ep if kind == "tv" else None, "title": name,
                "label": name + (f" S{se:02d}E{ep:02d}" if kind == "tv" else ""), "reason": "İstek listesinden"}

    def new_episodes(self, now: float | None = None) -> list[dict]:
        """Yabancı dizilerde TMDB'de YAYINLANMIŞ (duyuru değil) ama katalogda olmayan yeni bölümler → telefonun
        İstek listesi sitede arayıp alır. Aynı bölüm için en çok günde bir kez verilir (sitede yoksa boşuna istek
        gitmesin); yayından 30 gün sonra bırakılır. Sadece takip edilen sezon (o sezondan ya da bir öncekinden
        bölümü olan) — eski sezonların eksikleri kendiliğinden toplanmaz."""
        now = now or time.time()
        try:
            c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        today = datetime.fromtimestamp(now, timezone(timedelta(hours=3))).date()
        out = []
        with self.newep_checks.lock:
            nxt = self.newep_checks.data.setdefault("next", {})
            for t in c.get("titles") or []:
                la = t.get("lastAired") or {}
                if t.get("type") != "series" or not t.get("tmdbId") or not la.get("s"):
                    continue
                if (t.get("originalLanguage") or "tr") == "tr":
                    continue                                   # sadece yabancı diziler
                try:
                    aired = datetime.strptime(la["date"], "%Y-%m-%d").date()
                except (KeyError, ValueError):
                    continue
                if aired > today or (today - aired).days > 30:
                    continue
                S, E = int(la["s"]), int(la["e"])
                def have(n):
                    sea = next((x for x in t.get("seasons") or [] if x.get("number") == n), None) or {}
                    return {ep.get("number") for ep in sea.get("episodes") or [] if ep.get("stream") or ep.get("live")}
                cur, prev = have(S), have(S - 1)
                if not cur and not prev:
                    continue                                   # bu sezonu takip etmiyoruz
                want = [e for e in range((max(cur) if cur else 0) + 1, E + 1)]
                if not want:
                    continue
                key = f"{t['id']}|{S}|{E}"
                if nxt.get(key, 0) > now:
                    continue
                nxt[key] = now + 24 * 3600
                out.append({"id": t["id"], "tmdb": f"tv/{t['tmdbId']}", "title": t.get("title") or t["id"],
                            "original": t.get("originalTitle") or "", "year": t.get("year"), "season": S, "episodes": want})
            for k in [k for k, v in nxt.items() if v < now - 40 * 86400]:
                nxt.pop(k, None)
            self.newep_checks.dirty = True
        self.newep_checks.flush()
        if out:
            log("🆕 yeni bölüm aranacak: " + ", ".join(f"{x['title']} S{x['season']:02d}E{x['episodes'][-1]:02d}" for x in out))
        return out

    def soon_plan(self, plan: list[dict]) -> dict:
        """İstek listesindeki (TMDB'si seçili) yapımlar Yakında'da tahmini tarihle görünsün. Katalogda olmayan yapım
        TMDB'den kayıt olarak açılır (videosu yok → Yakında). Listeden çıkan/alınanın işareti kalkar (kayıt silinmez)."""
        if not self.items_dir:
            return {"ok": False}
        months = ["Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"]
        wanted: dict[str, str] = {}
        for x in plan[:300]:
            t = parse_tmdb(str(x.get("tmdb") or ""))
            eta = int(x.get("eta") or 0)
            if not t or eta <= 0:
                continue
            try:
                item_id = self.item_for_tmdb(*t)
            except Exception as e:  # noqa: BLE001
                log(f"  (Yakında planı: {x.get('tmdb')}: {str(e)[:80]})")
                continue
            d = datetime.fromtimestamp(eta, timezone(timedelta(hours=3)))
            wanted[item_id] = f"Tahmini {d.day} {months[d.month - 1]}"
        changed = 0
        for f in self.items_dir.glob("*.json"):
            try:
                d = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                continue
            iid = d.get("id") or f.stem
            playable = bool(d.get("stream") or d.get("episodes") or self.sources.entry(iid))
            if iid in wanted and not playable:
                if d.get("releaseNote") == wanted[iid] and d.get("comingSoon") and d.get("autoSoon"):
                    continue
                d.update(comingSoon=True, autoSoon=True, releaseNote=wanted[iid])
            elif d.get("autoSoon"):
                d.pop("autoSoon", None)
                d.pop("releaseNote", None)
                d["comingSoon"] = False
            else:
                continue
            f.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            changed += 1
        if changed:
            log(f"🗓 Yakında planı güncellendi ({len(wanted)} yapım, {changed} değişti)")
            self.rebuild()
        return {"ok": True, "soon": len(wanted), "changed": changed}

    # ---- İstek listesi uzaktan: işi tek bir yakalama cihazı (TV kutusu) yapar, durumunu buraya yazar;
    # telefondaki panel durumu buradan okur, komutlarını buraya bırakır (cihaz bir sonraki bildiriminde alır)
    def wish_report(self, device: str, state: dict) -> list[dict]:
        with self.wish.lock:
            self.wish.data.update(state=state, at=time.time(), device=device[:60])
            self.wish.dirty = True
        with self.wish_lock:
            cmds, self.wish_cmds = self.wish_cmds, []
        return cmds

    def wish_view(self) -> dict:
        with self.wish.lock:
            d = dict(self.wish.data)
        d["age"] = round(time.time() - (d.get("at") or 0)) if d.get("at") else None
        with self.wish_lock:
            d["queued"] = len(self.wish_cmds)
        return d

    def wish_cmd(self, cmd: dict) -> dict:
        if not isinstance(cmd, dict) or not cmd.get("op"):
            raise ValueError("komut gerekli")
        with self.wish_cv:
            self.wish_cmds.append({**cmd, "at": int(time.time())})
            del self.wish_cmds[:-500]
            self.wish_cv.notify_all()
        return {"ok": True}

    def wish_take(self, wait: float) -> list[dict]:
        """Cihazın uzun sorgusu: komut yoksa bekler, gelir gelmez verir (panelde yapılan anında uygulanır)."""
        with self.wish_cv:
            if wait > 0:
                self.wish_cv.wait_for(lambda: bool(self.wish_cmds), wait)
            cmds, self.wish_cmds = self.wish_cmds, []
        return cmds

    def pending_list(self) -> list[dict]:
        return list(self.pending.data.get("items") or [])

    def ensure_episode(self, item_id: str, season: int, episode: int) -> None:
        """Dizi kaydına bölümü ekle (TMDB'den adı/görseli katalog üretiminde gelir)."""
        if not self.items_dir:
            return
        f = self.items_dir / f"{item_id}.json"
        try:
            d = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        eps = d.setdefault("episodes", [])
        if not any(x.get("season") == season and x.get("episode") == episode for x in eps):
            now = int(time.time())
            eps.append({"season": season, "episode": episode, "added": now})
            eps.sort(key=lambda x: (x.get("season") or 0, x.get("episode") or 0))
            d["updated"] = now
            f.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def item_for_tmdb(self, kind: str, tmdb_id: int) -> str:
        """Katalogda bu TMDB yapımı varsa kimliği; yoksa TMDB'den adıyla yeni kayıt."""
        try:
            c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            c = {}
        want = "series" if kind == "tv" else "movie"
        t = next((x for x in c.get("titles") or [] if x.get("tmdbId") == tmdb_id and x.get("type") == want), None)
        if t:
            return t["id"]
        if self.items_dir:
            for f in self.items_dir.glob("*.json"):
                try:
                    d = json.loads(f.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                if d.get("tmdbId") == tmdb_id and d.get("type") == want:
                    return d["id"]
        tm = self.tm()
        info = (tm.get(f"/{kind}/{tmdb_id}", language="tr-TR") if tm else None) or {}
        if tm and not info:
            raise ValueError(f"TMDB'de böyle bir {'dizi' if kind == 'tv' else 'film'} yok (#{tmdb_id})")
        name = info.get("title") or info.get("name") or f"{want}-{tmdb_id}"
        date = info.get("release_date") or info.get("first_air_date") or ""
        return self.add_item(kind, tmdb_id, name, int(date[:4]) if date[:4].isdigit() else None)

    def approve(self, pids: list[str], tmdb: str | None = None, season: int | None = None, episode: int | None = None,
                start: int | None = None) -> list[dict]:
        """Seçilen bekleyen linkleri yayına al. tmdb verilirse hepsi o yapıma bağlanır (dizide her link kendi bölüm
        numarasıyla; tek link seçiliyse sezon/bölüm elle verilebilir). [start] verilirse (sırayla numarala) linkler
        sıralarına göre (oynatma listesi sırası / eklenme sırası) [season]. sezonun start, start+1 … bölümleri olur.
        Verilmezse bilgisayarın tahmini kullanılır."""
        target = parse_tmdb(tmdb) if tmdb else None
        if tmdb and not target:
            raise ValueError("TMDB linki ya da numarası anlaşılamadı (ör. https://www.themoviedb.org/movie/12345 ya da tv/12345)")
        items = {x["pid"]: x for x in self.pending_list()}
        numbered: dict[str, int] = {}
        if start is not None and season is not None:
            ordered = sorted([p for p in pids if p in items], key=lambda p: (items[p].get("order") or 0, items[p].get("created") or 0))
            numbered = {p: start + i for i, p in enumerate(ordered)}
        results = []
        for pid in pids:
            p = items.get(pid)
            if not p:
                results.append({"pid": pid, "ok": False, "error": "artık yok"})
                continue
            g = p.get("guess") or {}
            try:
                if target:
                    kind, tid = target
                else:
                    if not g.get("ok") or not g.get("tmdbId"):
                        raise ValueError(g.get("reason") or "tahmin yok; TMDB ile eşleştir")
                    kind, tid = ("tv" if g.get("kind") == "series" else "movie"), int(g["tmdbId"])
                if pid in numbered:
                    s_, e_ = season, numbered[pid]
                elif season is not None and len(pids) == 1:
                    s_, e_ = season, episode
                else:
                    s_, e_ = g.get("season"), g.get("episode")
                if kind == "tv" and (s_ is None or e_ is None):
                    raise ValueError("dizi için sezon ve bölüm gerekli")
                if kind == "movie":
                    s_ = e_ = None
                item_id = self.item_for_tmdb(kind, tid)
                self.sources.set(item_id, p["url"], s_, e_)
                if kind == "tv":
                    self.ensure_episode(item_id, int(s_), int(e_))
                cap = self.captures.get(f"bekleyen-{pid}", None, None)
                if cap:
                    self.captures.save(item_id, s_, e_, cap)
                    self.note_size(item_id, s_, e_, *self.capture_size(cap))
                with self.slock:
                    for sid in [k for k, x in self.sessions.items() if x.key == (item_id, s_, e_)]:
                        self.sessions.pop(sid, None)
                with self.pending.lock:
                    self.pending.data["items"] = [x for x in self.pending.data.get("items") or [] if x.get("pid") != pid]
                    self.pending.dirty = True
                t = self.find_title(item_id) or {}
                label = (t.get("title") or item_id) + (f" S{int(s_):02d}E{int(e_):02d}" if s_ is not None else "")
                archive_job = self.archive.enqueue(item_id, p["url"], t.get("title") or label, s_, e_, tid) if self.archive.enabled else ""
                log(f"✅ kaynağa bağlandı: {label}" + (" — archive.org'a indirilecek" if archive_job else ""))
                results.append({"pid": pid, "ok": True, "id": item_id, "label": label, "archiveJob": archive_job})
            except Exception as ex:  # noqa: BLE001
                results.append({"pid": pid, "ok": False, "error": str(ex)[:200]})
        self.pending.flush()
        if any(r["ok"] for r in results):
            self.rebuild()
        return results

    def delete_item(self, item_id: str) -> dict:
        """Telefondan 'Yapımı Sil': kayıt silinmez, katalog/silinenler/ klasörüne taşınır (kaynak adresleriyle birlikte);
        geri almak için dosyayı katalog/items'a geri koymak yeter. Yapım uygulamalardan kalkar."""
        f = self.items_dir / f"{item_id}.json"
        if not f.exists():
            return {"ok": False, "error": "kayıt yok"}
        d = json.loads(f.read_text(encoding="utf-8"))
        d["_kaynaklar"] = self.sources.entry(item_id)
        trash = self.items_dir.parent / "silinenler"
        trash.mkdir(parents=True, exist_ok=True)
        dst = trash / f"{item_id}-{time.strftime('%Y%m%d-%H%M%S')}.json"
        dst.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        f.unlink()
        with self.sources.lock:
            self.sources._load()
            self.sources.data["items"].pop(item_id, None)
            self.sources._save()
        with self.slock:
            for sid in [k for k, x in self.sessions.items() if x.key[0] == item_id]:
                self.sessions.pop(sid, None)
        log(f"🗑 yapım kaldırıldı (geri alınabilir: {dst})")
        self.rebuild()
        return {"ok": True, "moved": str(dst)}

    def url_status(self, urls: list[str]) -> dict[str, str]:
        """Sayfadan Yakala'nın bölüm listesi için: sayfa linki yayında mı (kaynak), onayda mı (kuyruk)?"""
        def n(u: str) -> str:
            u = (u or "").split("#")[0].strip()
            p = urlparse(u)
            return f"{p.netloc.lower().removeprefix('www.')}{p.path.rstrip('/')}" + (f"?{p.query}" if p.query else "")
        live = set()
        for e in self.sources.all().values():
            if e.get("url"):
                live.add(n(e["url"]))
            live.update(n(u) for u in (e.get("episodes") or {}).values())
        pend = {n(x.get("url") or "") for x in self.pending_list()}
        out = {}
        for u in urls[:2000]:
            k = n(u)
            if k in live:
                out[u] = "live"
            elif k in pend:
                out[u] = "pending"
        return out

    def reject(self, pids: list[str]) -> int:
        with self.pending.lock:
            before = len(self.pending.data.get("items") or [])
            self.pending.data["items"] = [x for x in self.pending.data.get("items") or [] if x.get("pid") not in set(pids)]
            self.pending.dirty = True
            n = before - len(self.pending.data["items"])
        self.pending.flush()
        return n

    # ---- ölü link kontrolü (günde bir, tek tek): kayıtlı sayfalar, YouTube kaynakları, seçili fragmanlar
    def link_targets(self) -> list[dict]:
        out = []
        for item_id, e in self.sources.all().items():
            if e.get("url"):
                out.append({"key": f"{item_id}|film", "id": item_id, "what": "sayfası", "url": e["url"],
                            "kind": "youtube" if youtube.is_youtube(e["url"]) else "page"})
            for se, u in (e.get("episodes") or {}).items():
                sn, ep = se.split(":")
                out.append({"key": f"{item_id}|{se}", "id": item_id, "what": f"S{int(sn):02d}E{int(ep):02d} sayfası", "url": u,
                            "kind": "youtube" if youtube.is_youtube(u) else "page"})
        try:
            c = live_catalog(json.loads(self.catalog_path.read_text(encoding="utf-8")), self.sources, checks=self.tchecks.data)
        except (OSError, ValueError):
            c = {}
        for t in c.get("titles") or []:
            for tr in t.get("trailers") or []:
                out.append({"key": f"{t.get('id')}|fragman-{tr['lang']}", "id": t.get("id"), "what": f"{tr['lang'].upper()} fragmanı",
                            "url": f"https://www.youtube.com/watch?v={tr['youtube']}", "kind": "youtube", "trailer": tr["youtube"]})
        return out

    def check_next_link(self, max_age: float = 24 * 3600) -> bool:
        now = time.time()
        due = [x for x in self.link_targets() if now - ((self.links.data.get(x["key"]) or {}).get("at") or 0) > max_age]
        if not due:
            return False
        x = min(due, key=lambda x: (self.links.data.get(x["key"]) or {}).get("at") or 0)
        state, detail = check_link(x["kind"], x["url"])
        old = self.links.data.get(x["key"]) or {}
        rec = {"state": state, "detail": detail, "at": int(now), "id": x["id"], "what": x["what"], "kind": x["kind"],
               "since": old.get("since") if old.get("state") == state else int(now)}
        if state == "dead" and old.get("state") != "dead":
            t = self.find_title(x["id"]) or {}
            log(f"⚠ ölü link: {t.get('title') or x['id']} {x['what']} — {detail[:120]}")
            if x.get("trailer"):          # ölü fragman: bir daha seçilmez, sıradaki gelir
                with self.tchecks.lock:
                    self.tchecks.data[x["trailer"]] = {"ok": False, "title": "(video kaldırılmış)", "at": int(now)}
                    self.tchecks.dirty = True
        with self.links.lock:
            self.links.data[x["key"]] = rec
            self.links.dirty = True
        return True

    def alerts(self) -> dict:
        items = []
        valid = {x["key"] for x in self.link_targets()}
        for k, v in self.links.data.items():
            if v.get("state") == "dead" and not v.get("ignored") and (k in valid or "|fragman-" in k):
                t = self.find_title(v.get("id") or "") or {}
                items.append({"key": k, "title": t.get("title") or v.get("id"), "what": v.get("what"), "detail": v.get("detail"),
                              "since": v.get("since"), "at": v.get("at")})
        items.sort(key=lambda x: -(x.get("since") or 0))
        return {"items": items, "checked": sum(1 for v in self.links.data.values() if v.get("at")), "targets": len(valid)}

    def catalog(self, tv: bool) -> dict:
        c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        c = live_catalog(c, self.sources, checks=self.tchecks.data)
        return apply_schedule(c) if tv else c

    # ---- duyurular: yayındaki dizide TMDB'ye yeni sezon/bölüm girdi → Stüdyo'ya düşer (İstek listesine tek dokunuşla)
    def news_keys(self) -> list[dict]:
        out = []
        for t in self.catalog(tv=False).get("titles") or []:
            if t.get("type") != "series" or not t.get("tmdbId"):
                continue
            have = {}
            for se in t.get("seasons") or []:
                have[se.get("number")] = max([e.get("number") or 0 for e in se.get("episodes") or [] if e.get("stream")] or [0])
            if not any(have.values()):
                continue          # hiç yayında bölümü yok: duyuru değil, Yakında'daki yapım
            ts = t.get("tmdbSeasons") or []
            top = max([x["n"] for x in ts] or [0])
            nx = t.get("nextEpisode") or {}
            base = {"id": t["id"], "title": t.get("title") or t["id"], "original": t.get("originalTitle") or "",
                    "year": t.get("year"), "tmdb": f"tv/{t['tmdbId']}"}
            if nx.get("s") and nx["s"] > top:
                out.append({**base, "key": f"{t['id']}|S{nx['s']}", "season": nx["s"], "text": f"{nx['s']}. sezon duyuruldu"})
            for x in ts:
                n, c_ = x["n"], x["c"]
                if c_ > have.get(n, 0) and (have.get(n, 0) > 0 or n > max(k for k, v in have.items() if v)):
                    out.append({**base, "key": f"{t['id']}|S{n}E{c_}", "season": n,
                                "text": f"{n}. sezon: TMDB'de {c_} bölüm, yayında {have.get(n, 0)}"})
        return out

    def note_news(self) -> int:
        """Yeni duyuruları kaydet. İlk çalışmada eski eksik bölümler sessizce 'görüldü' sayılır (sel olmasın)."""
        keys = self.news_keys()
        now = int(time.time())
        added = 0
        fresh: list[dict] = []
        with self.news.lock:
            first = not self.news.data.get("init")
            seen = self.news.data.setdefault("seen", {})
            items = self.news.data.setdefault("items", [])
            for k in keys:
                if k["key"] in seen:
                    continue
                seen[k["key"]] = now
                if first and "E" in k["key"].rsplit("|", 1)[-1]:
                    continue
                # aynı sezonun eski duyurusu yenisiyle değişir
                items[:] = [x for x in items if not (x["id"] == k["id"] and x["season"] == k["season"])]
                items.append({**k, "at": now})
                fresh.append(k)
                added += 1
            self.news.data["init"] = True
            self.news.dirty = True
        self.news.flush()
        for k in fresh:
            log(f"🆕 duyuru: {k['title']} — {k['text']}")
        return added

    # ---- YouTube takibi: dizinin kanalına/listesine yeni TAM bölüm gelince onay kuyruğuna (eşleşmiş olarak)
    FOLLOW_SKIP = ("fragman", "tanıtım", "tanitim", "teaser", "trailer", "özet", "ozet", "kamera arkası", "sahne", "klip", "#shorts",
                   "final sahnesi", "promo", "kesit")

    @staticmethod
    def episode_no(title: str) -> int | None:
        t = title.lower()
        for pat in (r"(\d{1,4})\s*\.?\s*b[öo]l[üu]m", r"b[öo]l[üu]m\s*[:#-]?\s*(\d{1,4})", r"\bep(?:isode)?\s*\.?\s*(\d{1,4})"):
            m = re.search(pat, t)
            if m:
                return int(m.group(1))
        return None

    def follow_add(self, url: str, tmdb: str, title: str, season: int, first: int = 1, min_minutes: int = 30) -> dict:
        target = parse_tmdb(tmdb)
        if not target or target[0] != "tv":
            raise ValueError("TMDB dizisi seçilmeli")
        vids = self.yt_latest(url, 40)
        rec = {"key": secrets.token_hex(5), "url": url.strip(), "tmdb": f"tv/{target[1]}", "title": title, "season": int(season),
               "first": max(1, int(first)), "min": max(5, int(min_minutes)), "seen": [v["id"] for v in vids], "added": int(time.time()),
               "checked": int(time.time()), "found": 0}
        with self.follows.lock:
            self.follows.data.setdefault("items", []).append(rec)
            self.follows.dirty = True
        self.follows.flush()
        log(f"📡 takip başladı: {title} ({len(vids)} mevcut video atlandı, yenileri gelecek)")
        return rec

    def follow_remove(self, key: str) -> None:
        with self.follows.lock:
            self.follows.data["items"] = [x for x in self.follows.data.get("items") or [] if x.get("key") != key]
            self.follows.dirty = True
        self.follows.flush()

    def follow_check(self, rec: dict) -> int:
        """Bir takibin yeni tam bölümleri → onay kuyruğu (TMDB + sezon/bölüm eşleşmiş). Döner: eklenen sayısı."""
        vids = self.yt_latest(rec["url"], 20)
        seen = set(rec.get("seen") or [])
        added = 0
        for v in reversed(vids):          # eskiden yeniye
            if v["id"] in seen:
                continue
            seen.add(v["id"])
            t = v["title"]
            low = t.lower()
            if (v.get("duration") or 0) < rec["min"] * 60 or any(w in low for w in self.FOLLOW_SKIP):
                continue                  # tam bölüm değil (fragman, kesit, kısa)
            n = self.episode_no(t)
            hint = {"tmdb": rec["tmdb"], "title": rec["title"]}
            if n is not None:
                hint.update({"season": rec["season"], "episode": n - rec["first"] + 1})
            url = f"https://www.youtube.com/watch?v={v['id']}"
            if self.url_status([url]).get(url):
                continue
            self.queue_link(url, t, None, hint=hint if n is not None else None)
            added += 1
            log(f"📡 yeni bölüm: {rec['title']} — {t[:70]}")
        with self.follows.lock:
            for x in self.follows.data.get("items") or []:
                if x.get("key") == rec["key"]:
                    x["seen"] = list(seen)[-300:]
                    x["checked"] = int(time.time())
                    x["found"] = (x.get("found") or 0) + added
            self.follows.dirty = True
        self.follows.flush()
        return added

    def follow_next(self) -> bool:
        """Sırası gelen tek takibi kontrol et (her takip 30 dk'da bir). True: bir şey kontrol edildi."""
        items = list(self.follows.data.get("items") or [])
        due = [x for x in items if time.time() - (x.get("checked") or 0) > 30 * 60]
        if not due:
            return False
        rec = min(due, key=lambda x: x.get("checked") or 0)
        try:
            self.follow_check(rec)
        except Exception as e:  # noqa: BLE001
            log(f"⚠ takip okunamadı ({rec.get('title')}): {str(e)[:150]}")
            with self.follows.lock:
                for x in self.follows.data.get("items") or []:
                    if x.get("key") == rec["key"]:
                        x["checked"] = int(time.time())
                self.follows.dirty = True
        return True

    # ---- bilgisayarda yakalama (İstek listesinin bir kısmı): görünür tarayıcı penceresiyle sayfayı açar, yayını yakalar
    def pccap_add(self, url: str, hint: dict | None) -> str:
        jid = secrets.token_hex(5)
        with self.pccap_lock:
            # bitmişlerin eskileri unutulsun
            for k in [k for k, x in self.pccap.items() if x["state"] in ("ok", "black") and time.time() - x["at"] > 6 * 3600]:
                self.pccap.pop(k, None)
            self.pccap[jid] = {"url": url, "hint": hint, "state": "wait", "note": "", "at": time.time()}
        return jid

    def pccap_visible(self) -> bool:
        """Masaüstü var mı (görünür tarayıcı penceresi açılabilir mi). Servis masaüstü oturumunun ortamını almadıysa
        açık X ekranı bulunur (/tmp/.X11-unix/X0 → DISPLAY=:0, XAUTHORITY de bulunur)."""
        if os.environ.get("DISPLAY"):
            return True
        import glob
        socks = sorted(glob.glob("/tmp/.X11-unix/X*"))
        if not socks:
            return False
        os.environ["DISPLAY"] = ":" + socks[0].rsplit("X", 1)[-1]
        uid = os.getuid()
        for cand in [os.path.expanduser("~/.Xauthority"), f"/run/user/{uid}/gdm/Xauthority", *glob.glob(f"/run/user/{uid}/.mutter-Xwaylandauth.*"),
                     *glob.glob(f"/run/user/{uid}/xauth_*")]:
            if os.path.isfile(cand):
                os.environ.setdefault("XAUTHORITY", cand)
                break
        return True

    def pccap_next(self) -> bool:
        """Sıradaki bilgisayar yakalama işi (aynı anda tek; siteye tek iş kuralı). True: bir iş yapıldı."""
        with self.pccap_lock:
            job = next(((k, x) for k, x in self.pccap.items() if x["state"] == "wait"), None)
            if not job:
                return False
            jid, x = job
            x["state"], x["at"] = "work", time.time()
        url = x["url"]
        log(f"🖥️ indirme için kaynak bulunuyor: {url[:70]}")
        try:
            if not self.archive.enabled:
                raise RuntimeError("archive.org anahtarları ve otomatik yükleme onayı bilgisayarda ayarlanmamış")
            with self.site_slot:
                cap = self.capture_fn(url, log, timeout=180, visible=self.pccap_visible(), gentle=True, ghost=True, stay=10)
            longest = max((getattr(cap, "durations", {}) or {}).values(), default=0.0)
            if not (cap.masters or cap.media) or longest < 5 * 60:
                d = getattr(cap, "diag", None) or {}
                why = ("video gelmedi (siyah ekran)" if not (cap.masters or cap.media) else "sadece kısa yayın (reklam?)")
                if d:
                    why += f" · {d.get('responses', 0)} istek görüldü" + (f" · oynatıcı reddedildi: {', '.join(d['frame_errors'][:2])}" if d.get("frame_errors") else "")
                raise RuntimeError(why)
            hint = x.get("hint") or {}
            if not hint.get("tmdb"):
                raise RuntimeError("İstek listesinde TMDB eşleştirmesi gerekli")
            rec = self.queue_link(url, cap.title or cap.og_title or url, resolve.capture_to_json(cap), hint=hint)
            if rec.get("approved"):
                archive_job = rec.get("archiveJob")
            else:
                result = self.approve([rec["pid"]], hint["tmdb"], hint.get("season"), hint.get("episode"))
                if not result or not result[0].get("ok"):
                    raise RuntimeError((result[0].get("error") if result else None) or "TMDB eşleştirmesi başarısız")
                archive_job = result[0].get("archiveJob")
            if not archive_job:
                raise RuntimeError("archive.org indirme işi başlatılamadı")
            with self.pccap_lock:
                x.update(state="work", note="video indiriliyor ve archive.org'a aktarılıyor",
                         archiveJob=archive_job, pid=rec["pid"], at=time.time())
        except Exception as e:  # noqa: BLE001
            with self.pccap_lock:
                x.update(state="black", note=str(e)[:160], at=time.time())
            log(f"🖥️ yakalanamadı: {str(e)[:120]}")
        return True


    def news_done(self, key: str) -> None:
        with self.news.lock:
            self.news.data["items"] = [x for x in self.news.data.get("items") or [] if x.get("key") != key]
            self.news.dirty = True
        self.news.flush()

    def find_title(self, item_id: str) -> dict | None:
        try:
            c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            c = {}
        t = next((t for t in c.get("titles") or [] if t.get("id") == item_id), None)
        if t is None and self.items_dir and re.fullmatch(r"[a-z0-9-]+", item_id or ""):
            try:      # katalog henüz yenilenmediyse kayıttan
                d = json.loads((self.items_dir / f"{item_id}.json").read_text(encoding="utf-8"))
                t = {k: d.get(k) for k in ("id", "type", "title", "tmdbId", "year")}
            except (OSError, ValueError):
                pass
        return t

    # ---- oturumlar
    def _gc(self) -> None:
        now = time.time()
        with self.slock:
            for sid in [s for s, x in self.sessions.items() if now - x.used > SESSION_TTL]:
                self.sessions.pop(sid, None)
        with self.yt_lock:
            for vid in [v for v, m in self.muxes.items() if now - m.used > 30 * 60]:
                self.muxes.pop(vid).close()

    # ---- tema müziği: sadece telefonda, YouTube'dan anlık (dosya tutulmaz, GitHub'a hiçbir şey gitmez)
    THEME_BAD_WORDS = ("trailer", "fragman", "teaser", "reaction", "review", "inceleme", "tepki", "explained", "ending", "full movie", "spoiler")

    def theme_for(self, item_id: str, bad: str | None = None, url: str | None = None) -> dict:
        """{youtube, title} ya da {} (bulunamadı). bad: yanlış denen video (bir daha seçilmez), url: elle verilen link."""
        t = self.find_title(item_id)
        if not t:
            raise LookupError("yapım yok")
        with self.themes.lock:
            cur = dict(self.themes.data.get(item_id) or {})
        if url:
            vid = youtube.video_id(url)
            if not vid:
                raise ValueError("YouTube linki anlaşılamadı")
            cur.update({"vid": vid, "title": "elle verildi", "at": int(time.time()), "manual": True})
        elif bad:
            cur["bad"] = sorted(set(cur.get("bad") or []) | {bad})
            if cur.get("vid") == bad:
                cur.pop("vid", None); cur.pop("manual", None)
        fresh = cur.get("vid") and (cur.get("manual") or time.time() - (cur.get("at") or 0) < 30 * 86400)
        if not fresh and time.time() - (cur.get("tried") or 0) > (0 if bad else 6 * 3600):
            cur["tried"] = int(time.time())
            name = t.get("originalTitle") or t.get("title") or ""
            series = t.get("type") == "series"
            queries = [f"{name} {'opening theme' if series else 'main theme'} soundtrack", f"{name} theme song"]
            badset = set(cur.get("bad") or [])
            for qq in queries:
                try:
                    res = self.yt_search(qq)
                except Exception as e:  # noqa: BLE001
                    log(f"🎵 tema araması olmadı ({t.get('title')}): {str(e)[:120]}")
                    break
                hit = next((r for r in res if r["id"] not in badset and 40 <= (r.get("duration") or 0) <= 480
                            and not any(w in r["title"].lower() for w in self.THEME_BAD_WORDS)), None)
                if hit:
                    cur.update({"vid": hit["id"], "title": hit["title"], "at": int(time.time())})
                    log(f"🎵 tema müziği: {t.get('title')} → {hit['title'][:70]}")
                    break
        with self.themes.lock:
            self.themes.data[item_id] = cur
            self.themes.dirty = True
        self.themes.flush()
        return {"youtube": cur["vid"], "title": cur.get("title") or ""} if cur.get("vid") else {}

    def theme_audio(self, vid: str) -> dict:
        hit = self.audio_cache.get(vid)
        if hit and time.time() - hit[0] < 3600:
            return hit[1]
        a = youtube.audio_only(self.yt_probe(vid, log))
        self.audio_cache[vid] = (time.time(), a)
        return a

    def youtube(self, vid: str):
        """("hls", Session) ya da ("mux", Muxer). Aynı video 1 saat içinde tekrar istenirse aynısı (adresler saatlerce geçerli)."""
        self._gc()
        with self.yt_lock:
            with self.slock:
                hit = next((x for x in self.sessions.values() if x.key == ("yt", vid, None) and time.time() - x.created < 3600), None)
            if hit:
                hit.used = time.time()
                return "hls", hit
            m = self.muxes.get(vid)
            if m and time.time() - m.created < 3600 and (m.proc.poll() is None or m.proc.returncode == 0):
                m.used = time.time()
                return "mux", m
            if m:
                self.muxes.pop(vid).close()
            log(f"▶ YouTube {vid}: yayın çözülüyor…")
            sid = secrets.token_urlsafe(18)
            try:
                res = youtube_resolved(vid, log)
                s = Session(sid, ("yt", vid, None), {"title": vid, "type": "movie"}, res.page, res, None)
                with self.slock:
                    self.sessions[sid] = s
                return "hls", s
            except NeedsMux as nm:
                c = nm.choice
            m = youtube.Muxer(sid, c, log)
            self.muxes[vid] = m
            return "mux", m

    def app_dir(self) -> Path:
        return self.workdir / "uygulama"

    def app_info(self) -> dict | None:
        """Telefon güncellemesi bilgisayardan: <iş klasörü>/uygulama/app.json + APK (GitHub'a telefon hiç bağlanmaz)."""
        try:
            d = json.loads((self.app_dir() / "app.json").read_text())
            f = self.app_dir() / d["file"]
            if f.is_file():
                return {"code": int(d["code"]), "name": d.get("name") or "", "size": f.stat().st_size, "url": "/app.apk"}
        except (OSError, ValueError, KeyError):
            pass
        return None

    def mux_by_sid(self, sid: str):
        with self.yt_lock:
            return next((m for m in self.muxes.values() if m.sid == sid), None)

    def play(self, item_id: str, season: int | None, episode: int | None, capture: dict | None = None,
             fresh: bool = False, count: bool = True, max_height: int = 0) -> Session:
        """max_height: indirme için istenen en yüksek kalite (p); 0 = en iyisi (izleme)."""
        self._gc()
        key = (item_id, season, episode)
        kq = {"max_height": max_height} if max_height else {}
        if not fresh and not capture:
            with self.slock:
                hit = next((s for s in self.sessions.values() if s.key == key and time.time() - s.created < REUSE_SEC
                            and getattr(s, "max_height", 0) == max_height), None)
            if hit:
                hit.used = time.time()
                return hit
        t = self.find_title(item_id) or {}
        page = (capture or {}).get("page_url") or self.sources.url_for(item_id, season, episode)
        if not page and not capture:
            raise LookupError("Bu yapımın sitedeki sayfası bilgisayarda kayıtlı değil. Telefonda 🌐 Sayfadan Yakala ile "
                              "sayfayı aç (bilgisayara gider) ya da bilgisayarda: gflix-yayin kaynak …")
        what = t.get("title") or item_id
        if season is not None:
            what += f" S{season:02d}E{episode:02d}"
        log(f"▶ {what}: taze link çözülüyor…")
        kind = t.get("type") or ("series" if season is not None else "movie")
        s = None
        stored = None if capture else self.captures.get(item_id, season, episode)
        if stored:
            # Telefonun yakaladığı yayın (birkaç sn; tarayıcı açılmaz). Linkleri hâlâ geçerliyse bu kullanılır.
            try:
                log("  📱 telefonun yakaladığı yayın deneniyor")
                res = self.resolver(page or "", kind, t.get("tmdbId"), season, episode, self.tmdb_key, capture=stored, log=log, **kq)
                cand = Session(secrets.token_urlsafe(18), key, t, page or "", res, stored)
                cand.max_height = max_height
                if self.alive(cand):
                    s = cand
                else:
                    age = (time.time() - (stored.get("_saved") or time.time())) / 3600
                    log(f"  ⌛ telefonun yakaladığı linklerin süresi dolmuş ({age:.1f} saat önce yakalanmıştı)")
                    self.note_link_life(age)
                    # Bu yapımın linkini telefon yakalamıştı: yenisini de telefon alsın. Bilgisayarın tarayıcısı
                    # bu sitede açamıyor; izleyeni 2 dk bekletmesin (pcFailedAt → aşağıda hemen "süresi dolmuş" döner).
                    if page and not youtube.is_youtube(page):
                        self.ask_refresh(f"{item_id}|{season}|{episode}", {"id": item_id, "season": season, "episode": episode,
                                                                            "page": page, "title": what, "pcFailedAt": int(time.time())})
            except Exception as e:  # noqa: BLE001
                log(f"  ⚠ telefonun yakaladığı kullanılamadı: {str(e)[:150]}")
        if s is None:
            rkey = f"{item_id}|{season}|{episode}"
            with self.refresh_lock:
                pend = self.refresh_req.get(rkey)
            pc_gave_up = self.pc_fail_streak >= 3 and time.time() - self.pc_fail_at < 6 * 3600
            if not capture and page and not youtube.is_youtube(page) and pc_gave_up:
                # Bilgisayarın tarayıcısı bu siteden üst üste video alamadı: 6 sa boyunca denemez, işi telefon yapar
                self.ask_refresh(rkey, {"id": item_id, "season": season, "episode": episode, "page": page, "title": what,
                                        "pcFailedAt": int(time.time())})
                raise RuntimeError("Kayıtlı linklerin süresi dolmuş; telefon yeniden yakalıyor (Sayfadan Yakala / İstek listesi). "
                                   "Birazdan kendiliğinden açılacak.")
            if pend and time.time() - (pend.get("pcFailedAt") or 0) < 10 * 60 and not capture:
                # bilgisayar az önce denedi ve açamadı; telefon yakalıyor: boşuna 2 dk bekletme, hemen söyle
                raise RuntimeError("Kayıtlı linklerin süresi dolmuş; telefon yeniden yakalıyor (Sayfadan Yakala / İstek listesi). "
                                   "Birazdan kendiliğinden açılacak.")
            try:
                with self.site_slot:
                    res = self.resolver(page or "", kind, t.get("tmdbId"), season, episode, self.tmdb_key, capture=capture, log=log, **kq)
            except Exception as e:  # noqa: BLE001
                if not capture and page and not youtube.is_youtube(page):
                    self.pc_fail_streak += 1
                    self.pc_fail_at = time.time()
                    # Telefon kendiliğinden yeniden yakalasın (İstek listesi 2 dk'da bir bakar)
                    self.ask_refresh(f"{item_id}|{season}|{episode}", {"id": item_id, "season": season, "episode": episode,
                                                                        "page": page, "title": what, "pcFailedAt": int(time.time()),
                                                                        "pcTried": int(time.time())})
                    log(f"🔄 tazeleme istendi (telefon yeniden yakalayacak): {what}")
                    raise RuntimeError(
                        "Kayıtlı linklerin süresi dolmuş, bilgisayar da sayfayı açamadı. Telefon bu sayfayı kendiliğinden "
                        "yeniden yakalayacak (Sayfadan Yakala / İstek listesi, birkaç dakika); sonra tekrar dene. "
                        f"({str(e)[:100]})") from e
                raise
            s = Session(secrets.token_urlsafe(18), key, t, page or "", res, capture)
            s.max_height = max_height
            if not capture:
                self.pc_fail_streak = 0
        with self.slock:
            self.sessions[s.sid] = s
        if count:
            self.count(play=True)
        self.note_session_size(s)
        return s

    def ask_refresh(self, rkey: str, rec: dict) -> None:
        """Tazeleme isteği (telefon ya da bilgisayar yeniden yakalasın). Var olanın durumu (bilgisayar deniyor/denedi) korunur."""
        with self.refresh_lock:
            old = self.refresh_req.get(rkey) or {}
            self.refresh_req[rkey] = {**old, **rec, "at": old.get("at") or rec.get("at") or int(time.time())}
            self.refresh_cv.notify_all()

    def pc_refresh_next(self) -> bool:
        """Tazelemeyi önce bilgisayar dener (görünmez masaüstünde gerçek Chrome; telefon gerekmez). Açamazsa iş
        telefona kalır (/api/refresh ancak o zaman telefona verir). True: bir iş yapıldı."""
        if os.environ.get("GFLIX_PC_REFRESH", "0") != "1":
            return False
        if self.pc_fail_streak >= 3 and time.time() - self.pc_fail_at < 6 * 3600:
            return False                       # bilgisayar bu siteden üst üste alamadı: 6 sa telefon yapar
        with self.refresh_lock:
            job = next(((k, x) for k, x in self.refresh_req.items() if not x.get("pcTried") and not x.get("pcWork")), None)
            if not job:
                return False
            rkey, x = job
            x["pcWork"] = int(time.time())
        page, what = x.get("page") or "", x.get("title") or rkey
        item_id, season, episode = x.get("id"), x.get("season"), x.get("episode")
        log(f"🖥️👻 tazeleniyor (görünmez): {what}")
        try:
            with self.site_slot:
                cap = self.capture_fn(page, log, timeout=150, visible=self.pccap_visible(), gentle=True, ghost=True, stay=3)
            longest = max((getattr(cap, "durations", {}) or {}).values(), default=0.0)
            if not (cap.masters or cap.media) or longest < 5 * 60:
                d = getattr(cap, "diag", None) or {}
                raise RuntimeError(("video gelmedi (siyah ekran)" if not (cap.masters or cap.media) else "sadece kısa yayın")
                                   + (f" · oynatıcı reddedildi: {', '.join(d['frame_errors'][:2])}" if d.get("frame_errors") else ""))
            self.captures.save(item_id, season, episode, resolve.capture_to_json(cap))
            with self.slock:
                for sid in [k for k, s in self.sessions.items() if s.key == (item_id, season, episode)]:
                    self.sessions.pop(sid, None)
            with self.refresh_lock:
                self.refresh_req.pop(rkey, None)
                self.refresh_cv.notify_all()
            self.pc_fail_streak = 0
            log(f"🖥️👻 tazelendi: {what} ({round(longest / 60)} dk)")
        except Exception as e:  # noqa: BLE001
            self.pc_fail_streak += 1
            self.pc_fail_at = time.time()
            with self.refresh_lock:
                if rkey in self.refresh_req:
                    self.refresh_req[rkey].pop("pcWork", None)
                    self.refresh_req[rkey]["pcTried"] = int(time.time())
                    self.refresh_cv.notify_all()
            log(f"🖥️👻 tazelenemedi, telefona kaldı: {what} ({str(e)[:120]})")
        return True

    def wait_refresh(self, rkey: str, timeout: float) -> bool:
        """İzleyen beklerken: telefon yeni linki getirene kadar bekle (gelir gelmez True, video hemen başlar)."""
        with self.refresh_cv:
            return self.refresh_cv.wait_for(lambda: rkey not in self.refresh_req, timeout)

    def wait_phone_work(self, timeout: float) -> list[dict]:
        """Telefonun uzun sorgusu: iş yoksa bekler, iş gelir gelmez döner (5 sn'lik sorgu gecikmesi olmaz)."""
        with self.refresh_cv:
            self.refresh_cv.wait_for(lambda: bool(self.refresh_for_phone()), timeout)
            return self.refresh_for_phone()

    def refresh_for_phone(self) -> list[dict]:
        """Telefonun yapacağı tazelemeler: bilgisayarın denemediği/açamadığı. Bilgisayar o an uğraşıyorsa telefona verilmez."""
        pc_on = os.environ.get("GFLIX_PC_REFRESH", "0") == "1" and not (self.pc_fail_streak >= 3 and time.time() - self.pc_fail_at < 6 * 3600)
        with self.refresh_lock:
            return [{k: v for k, v in x.items() if k not in ("pcWork", "pcTried")} for x in self.refresh_req.values()
                    if not x.get("pcWork") and (x.get("pcTried") or not pc_on)]

    # ---- 🌙 gece önceden yenileme: izlenmesi muhtemel yapımların ölü linkleri gece yakalama cihazına yeniletilir
    # ---- 🎞 indirme için küçültme: sitede istenen kalite yoksa bilgisayar ffmpeg ile küçültür, telefon hazır dosyayı
    # indirir, bitince dosya silinir (24 saatte alınmayan da silinir)
    def tc_add(self, item_id: str, season: int | None, episode: int | None, height: int) -> str:
        jid = secrets.token_urlsafe(12)
        with self.tc_lock:
            self.tc_jobs[jid] = {"id": item_id, "season": season, "episode": episode, "height": int(height), "state": "wait",
                                 "pct": 0, "size": 0, "err": "", "at": time.time()}
        return jid

    def tc_status(self, jid: str) -> dict:
        with self.tc_lock:
            x = dict(self.tc_jobs.get(jid) or {"state": "gone"})
        return {k: v for k, v in x.items() if k in ("state", "pct", "size", "err", "height", "duration")}

    def tc_done(self, jid: str) -> None:
        with self.tc_lock:
            x = self.tc_jobs.pop(jid, None)
        f = self.tc_dir / f"{jid}.mp4"
        f.unlink(missing_ok=True)
        if x:
            log(f"🎞 küçültülen dosya telefona indi, silindi ({jid[:6]})")

    def tc_file(self, jid: str) -> Path | None:
        with self.tc_lock:
            ok = (self.tc_jobs.get(jid) or {}).get("state") == "ready"
        f = self.tc_dir / f"{jid}.mp4"
        return f if ok and f.is_file() else None

    def tc_next(self) -> bool:
        """Sıradaki küçültme işi (aynı anda tek). True: bir iş yapıldı."""
        now = time.time()
        with self.tc_lock:
            for k in [k for k, x in self.tc_jobs.items() if now - x["at"] > 24 * 3600]:
                self.tc_jobs.pop(k, None)
                (self.tc_dir / f"{k}.mp4").unlink(missing_ok=True)
            job = next(((k, x) for k, x in self.tc_jobs.items() if x["state"] == "wait"), None)
            if not job:
                return False
            jid, x = job
            x["state"] = "work"
        try:
            self.tc_dir.mkdir(parents=True, exist_ok=True)
            sess = self.play(x["id"], x["season"], x["episode"], fresh=True, count=False)
            x["duration"] = round(sess.res.duration or 0)
            src = f"http://127.0.0.1:{self.port}/hls/{sess.sid}/master.m3u8"
            out = self.tc_dir / f"{jid}.mp4"
            log(f"🎞 küçültülüyor ({x['height']}p): {(sess.item.get('title') or x['id'])}")
            self.transcode(src, out, x["height"], float(sess.res.duration or 0), lambda p: x.update(pct=p))
            with self.tc_lock:
                x.update(state="ready", pct=100, size=out.stat().st_size, at=time.time())
            log(f"🎞 hazır: {out.stat().st_size / 1e6:.0f} MB")
        except Exception as e:  # noqa: BLE001
            with self.tc_lock:
                x.update(state="error", err=str(e)[:200])
            (self.tc_dir / f"{jid}.mp4").unlink(missing_ok=True)
            log(f"🎞 küçültülemedi: {str(e)[:150]}")
        return True

    def transcode(self, src: str, out: Path, height: int, duration: float, progress) -> None:
        """ffmpeg: görüntü istenen yüksekliğe (H.264), bütün sesler AAC, telefonun hemen oynatabileceği MP4."""
        import subprocess
        tmp = out.with_name(out.name + ".part.mp4")
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", src,
               "-map", "0:v:0", "-map", "0:a?", "-vf", f"scale=-2:{int(height)}",
               "-c:v", "libx264", "-preset", "veryfast", "-crf", "24", "-c:a", "aac", "-b:a", "128k", "-ac", "2",
               "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", str(tmp)]
        pr = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        for line in pr.stdout:
            if line.startswith("out_time_us=") and duration > 0:
                try:
                    progress(min(99, int(int(line.split("=")[1]) / 1e6 / duration * 100)))
                except ValueError:
                    pass
        err = pr.stderr.read()
        if pr.wait() != 0 or not tmp.is_file():
            tmp.unlink(missing_ok=True)
            raise RuntimeError("ffmpeg: " + (err.strip().splitlines() or ["hata"])[-1][:150])
        tmp.replace(out)

    def direct_info(self, item_id: str, season: int | None, episode: int | None) -> dict:
        """Telefon doğrudan siteden indirsin (bilgisayardan geçmeden): yakalanan yayının ana liste adresi, istek başlıkları,
        kaliteler. Ana liste yoksa boş (telefon bilgisayar üzerinden indirir)."""
        stored = self.captures.get(item_id, season, episode)
        if not stored:
            return {"master": ""}
        cap = resolve.capture_from_json(stored)
        t = self.find_title(item_id) or {}
        expected, _orig = _tmdb_info(self.tmdb_key, t.get("type") or ("series" if season is not None else "movie"), t.get("tmdbId"), season, episode)
        try:
            m, h, _d = resolve.choose_stream(cap, expected, lambda *a, **k: None)
        except Exception:  # noqa: BLE001
            return {"master": ""}
        base_text = resolve._MASTERS.get(m.video) if m.video else None
        if not base_text:
            return {"master": ""}
        base, text = base_text
        hdr = {k: v for k, v in (h or {}).items() if k.lower() in ("referer", "origin", "user-agent", "cookie")}
        return {"master": base, "headers": hdr, "heights": resolve.master_heights(text)}

    def qualities(self, item_id: str, season: int | None, episode: int | None) -> list[int]:
        """İndirme için seçilebilecek kaliteler: kayıtlı yayının ana listelerindeki çözünürlükler (uzun olanlar)."""
        stored = self.captures.get(item_id, season, episode)
        if not stored:
            return []
        cap = resolve.capture_from_json(stored)
        hs: set[int] = set()
        for u, t, h in cap.masters:
            d = (getattr(cap, "durations", {}) or {}).get(u) or resolve.playlist_duration(t)
            if d and d < 5 * 60 and "#EXT-X-STREAM-INF" not in t:
                continue
            hs.update(resolve.master_heights(t))
        return sorted(hs, reverse=True)

    def note_play(self, item_id: str, season: int | None, episode: int | None) -> None:
        if not item_id:
            return
        with self.playlog.lock:
            lst = self.playlog.data.setdefault("items", [])
            lst.append({"id": item_id, "s": season, "e": episode, "at": int(time.time())})
            del lst[:-400]
            self.playlog.dirty = True

    def prefetch_candidates(self, now: float | None = None) -> list[tuple[str, int | None, int | None]]:
        """Sırayla: son 14 günde izlenen dizilerin sıradaki 2 bölümü (ve yarım kalmış olabilecek son bölüm), son 7 günde
        açılan filmler, son 3 günde eklenenler. Sadece sayfası bilinen ve telefonun yakaladığı linki olanlar."""
        now = now or time.time()
        try:
            c = json.loads(self.catalog_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return []
        titles = {t["id"]: t for t in c.get("titles") or [] if t.get("id")}

        def eps(t):
            return [(se.get("number"), ep.get("number")) for se in sorted(t.get("seasons") or [], key=lambda x: x.get("number") or 0)
                    for ep in sorted(se.get("episodes") or [], key=lambda x: x.get("number") or 0) if ep.get("stream")]
        out: list[tuple[str, int | None, int | None]] = []
        with self.playlog.lock:
            plays = list(self.playlog.data.get("items") or [])
        last: dict[str, dict] = {}
        for p in plays:
            if now - (p.get("at") or 0) <= 14 * 86400:
                last[p["id"]] = p                                  # her yapımın en son izleneni
        for p in sorted(last.values(), key=lambda x: -(x.get("at") or 0)):
            t = titles.get(p["id"])
            if not t:
                continue
            if t.get("type") == "series" and p.get("s") is not None:
                el = eps(t)
                try:
                    i = el.index((p["s"], p["e"]))
                except ValueError:
                    continue
                out += [(t["id"], s_, e_) for s_, e_ in el[i:i + 3]]
            elif t.get("type") != "series" and now - (p.get("at") or 0) <= 7 * 86400:
                out.append((t["id"], None, None))
        for t in sorted(titles.values(), key=lambda x: -(x.get("added") or 0)):
            if now - (t.get("added") or 0) > 3 * 86400:
                break
            if t.get("type") == "series":
                out += [(t["id"], s_, e_) for s_, e_ in eps(t)[-2:]]
            elif t.get("stream"):
                out.append((t["id"], None, None))
        seen, res = set(), []
        for k in out:
            if k in seen:
                continue
            seen.add(k)
            page = self.sources.url_for(*k)
            if page and not youtube.is_youtube(page) and self.captures.get(*k):
                res.append(k)
        return res

    def stored_alive(self, item_id: str, season: int | None, episode: int | None) -> bool:
        """Kayıtlı (telefonun/kutunun yakaladığı) link hâlâ çalışıyor mu: sayfa açılmaz, videonun ilk parçasına bakılır."""
        stored = self.captures.get(item_id, season, episode)
        if not stored:
            return False
        t = self.find_title(item_id) or {}
        page = self.sources.url_for(item_id, season, episode) or ""
        try:
            res = self.resolver(page, t.get("type") or ("series" if season is not None else "movie"), t.get("tmdbId"), season, episode,
                                self.tmdb_key, capture=stored, log=lambda *a, **k: None)
            return self.alive(Session(secrets.token_urlsafe(18), (item_id, season, episode), t, page, res, stored))
        except Exception:  # noqa: BLE001
            return False

    def prefetch_step(self, now: float | None = None) -> str | None:
        """Gece aralığındaysa sıradaki ölü linki yeniletir (aynı anda tek, gecede en çok 'max'). Yeniletilenin anahtarı döner."""
        now = now or time.time()
        with self.prefetch_cfg.lock:
            cfg = dict(self.prefetch_cfg.data)
        if not cfg.get("on"):
            return None
        lt = time.localtime(now)
        cur = lt.tm_hour * 60 + lt.tm_min

        def hm(v):
            try:
                h, m = str(v).split(":")
                return int(h) * 60 + int(m)
            except ValueError:
                return None
        a, b = hm(cfg.get("from")), hm(cfg.get("to"))
        if a is None or b is None or not (a <= cur < b if a <= b else cur >= a or cur < b):
            return None
        # gece yarısını aşan aralıkta (23:00–05:00) gece, başladığı günün tarihiyle anılır
        night = time.strftime("%Y-%m-%d", time.localtime(now - (b * 60 if a > b and cur < b else 0)))
        rec = (cfg.get("done") or {}).get(night) or {}
        done, refreshed = rec.get("checked", []), rec.get("refreshed", [])
        if len(refreshed) >= int(cfg.get("max") or 25):
            return None
        with self.refresh_lock:
            if self.refresh_req:
                return None                                       # önce izleyenlerin tazelemesi / sıradaki önceden yenileme bitsin
        for k in self.prefetch_candidates(now):
            key = f"{k[0]}|{k[1]}|{k[2]}"
            if key in done:
                continue
            alive = self.stored_alive(*k)
            with self.prefetch_cfg.lock:
                d = self.prefetch_cfg.data.setdefault("done", {})
                r = d.setdefault(night, {"checked": [], "refreshed": []})
                r["checked"].append(key)
                if not alive:
                    r["refreshed"].append(key)
                for old in sorted(x for x in d if x != night)[:-3]:
                    d.pop(old, None)
                self.prefetch_cfg.dirty = True
            if alive:
                continue                                          # link hâlâ çalışıyor: siteye gitmeye gerek yok
            t = self.find_title(k[0]) or {}
            what = (t.get("title") or k[0]) + (f" S{k[1]:02d}E{k[2]:02d}" if k[1] is not None else "")
            self.ask_refresh(key, {"id": k[0], "season": k[1], "episode": k[2], "page": self.sources.url_for(*k), "title": what,
                                   "prefetch": True})
            log(f"🌙 önceden yenileniyor: {what}")
            return key
        return None

    def note_link_life(self, hours: float) -> None:
        """Linklerin kaç saatte eskidiği (istatistik): Stüdyo'da 'linkler ~N saat yaşıyor' için."""
        with self.usage.lock:
            ll = self.usage.data.setdefault("linkLife", [])
            ll.append(round(hours, 2))
            del ll[:-50]
            self.usage.dirty = True

    def alive(self, s: Session) -> bool:
        """Görüntünün ilk parçası siteden alınabiliyor mu (yakalanan linkler eskimiş olabilir)?"""
        try:
            tr = s.tracks["v"]
            text = hls.PRELOADED.get(tr.url)
            if text is None:
                r = self.fetch(s, tr.url)
                if not r.ok:
                    return False
                text = r.content.decode("utf-8", "replace")
            self._rewrite(tr, text)
            if not tr.segs:
                return False
            r = self.fetch(s, tr.segs[0], {"Range": "bytes=0-2047"}, stream=True)
            ok = r.status_code in (200, 206)
            r.close()
            return ok
        except Exception:  # noqa: BLE001
            return False

    def refresh(self, s: Session, gen: int) -> bool:
        """Link süresi doldu: aynı sayfadan yeniden çöz. Başka bir istek zaten yenilediyse tekrar çözmez."""
        with s.lock:
            if s.gen != gen:
                return True
            if not s.page or s.capture and not s.page.startswith("http"):
                return False
            log(f"🔄 link süresi dolmuş, yeniden çözülüyor: {s.item.get('title') or s.key[0]}")
            try:
                with self.site_slot:
                    mh = getattr(s, "max_height", 0)
                    res = self.resolver(s.page, s.item.get("type") or "movie", s.item.get("tmdbId"), s.key[1], s.key[2],
                                        self.tmdb_key, capture=None, log=log, **({"max_height": mh} if mh else {}))
            except Exception as e:  # noqa: BLE001
                log(f"  ✗ yeniden çözülemedi: {str(e)[:200]}")
                return False
            subs = s.res.subs
            s._apply(res)
            if not res.subs:          # altyazı numaraları oynatıcıda açık; eskisi korunur
                s.res.subs = subs
            return True

    # ---- listeler
    def master(self, s: Session) -> str:
        r = s.res
        out = ["#EXTM3U", "#EXT-X-VERSION:4", "#EXT-X-INDEPENDENT-SEGMENTS"]
        group = ""
        if r.audios:
            group = ',AUDIO="aud"'
            for i, (lang, _u) in enumerate(r.audios):
                name = LANG_LABEL.get(lang, lang.upper())
                out.append(f'#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",LANGUAGE="{lang}",NAME="{name}",'
                           f'DEFAULT={"YES" if i == 0 else "NO"},AUTOSELECT=YES,URI="a{i}.m3u8"')
        v = r.variant
        attrs = [f"BANDWIDTH={v.get('BANDWIDTH') or 5_000_000}"]
        if v.get("AVERAGE-BANDWIDTH"):
            attrs.append(f"AVERAGE-BANDWIDTH={v['AVERAGE-BANDWIDTH']}")
        if v.get("RESOLUTION"):
            attrs.append(f"RESOLUTION={v['RESOLUTION']}")
        if v.get("CODECS"):
            attrs.append(f'CODECS="{v["CODECS"]}"')
        if v.get("FRAME-RATE"):
            attrs.append(f"FRAME-RATE={v['FRAME-RATE']}")
        out.append("#EXT-X-STREAM-INF:" + ",".join(attrs) + group)
        out.append("v.m3u8")
        return "\n".join(out) + "\n"

    def fetch(self, s: Session, url: str, extra: dict | None = None, stream: bool = False) -> requests.Response:
        """Siteden istek: sitenin istediği başlıklarla; site başına eş zamanlı sınır, 429'da bekleme (en fazla ~2 dk)."""
        lim = hls.host_limit(url, HOST_PARALLEL, scope="yayin")
        deadline = time.time() + 120
        delay = 5
        while True:
            lim.acquire()
            throttled, wait = False, 0.0
            try:
                r = s.http.get(url, headers={**s.res.headers, **(extra or {})}, timeout=SEG_TIMEOUT, stream=stream)
            except requests.RequestException:
                lim.release(False)
                raise
            if r.status_code in (429, 503):
                throttled = True
                ra = r.headers.get("Retry-After", "")
                wait = float(ra) if ra.isdigit() and int(ra) <= 60 else delay
                delay = min(60, delay * 2)
                r.close()
            lim.release(throttled, wait)
            if not throttled:
                return r
            if time.time() + wait > deadline:
                raise RuntimeError("site çok yoğun (429), sonra tekrar dene")
            log(f"  ⏳ site 'bekle' dedi (429), {wait:.0f} sn")
            time.sleep(wait)

    def media(self, s: Session, name: str) -> str:
        """Sitenin listesini al, parça/anahtar/init adreslerini bilgisayara çevir. Süresi dolduysa yeniden çöz."""
        for attempt in range(2):
            tr = s.tracks.get(name)
            if tr is None:
                raise KeyError(name)
            if tr.text is not None:
                return tr.text
            gen = s.gen
            if tr.url in hls.PRELOADED:
                text = hls.PRELOADED[tr.url]
            else:
                r = self.fetch(s, tr.url)
                if r.status_code in EXPIRED and attempt == 0 and self.refresh(s, gen):
                    continue
                if not r.ok:
                    raise RuntimeError(f"site listeyi vermedi (HTTP {r.status_code})")
                text = r.content.decode("utf-8", "replace")
            self._rewrite(tr, text)
            return tr.text
        raise RuntimeError("liste alınamadı")

    @staticmethod
    def _rewrite(tr: Track, text: str) -> None:
        out, segs, keys, maps = [], [], [], []
        has_map = "#EXT-X-MAP" in text
        for raw in text.splitlines():
            line = raw.strip()
            if not line:
                continue
            if line.startswith("#EXT-X-KEY:") or line.startswith("#EXT-X-MAP:"):
                m = re.search(r'URI="([^"]+)"', line)
                if m:
                    tgt = keys if line.startswith("#EXT-X-KEY") else maps
                    tgt.append(urljoin(tr.url, m.group(1)))
                    kind = "key" if tgt is keys else "init"
                    line = line[:m.start(1)] + f"{tr.name}/{kind}{len(tgt) - 1}" + line[m.end(1):]
                out.append(line)
            elif line.startswith("#"):
                out.append(line)
            else:
                u = urljoin(tr.url, line)
                ext = Path(urlparse(u).path).suffix.lower()
                if ext not in _SEG_EXT:
                    ext = ".m4s" if has_map else ".ts"
                out.append(f"{tr.name}/{len(segs)}{ext}")
                segs.append(u)
        tr.segs, tr.keys, tr.maps = segs, keys, maps
        tr.text = "\n".join(out) + "\n"

    def piece(self, s: Session, name: str, kind: str, idx: int, rng: str | None):
        """(yanıt, atılacak önek baytı) — süresi dolmuşsa oturumu yenileyip aynı sıradaki parçayı ister."""
        for attempt in range(2):
            tr = s.tracks[name]
            if tr.text is None:
                self.media(s, name)
                tr = s.tracks[name]
            lst = {"seg": tr.segs, "key": tr.keys, "init": tr.maps}[kind]
            if idx >= len(lst):
                raise KeyError(idx)
            gen = s.gen
            r = self.fetch(s, lst[idx], {"Range": rng} if rng else None, stream=True)
            if r.status_code in EXPIRED and attempt == 0:
                r.close()
                if self.refresh(s, gen):
                    self.media(s, name)
                    continue
            return r
        raise RuntimeError("parça alınamadı")

    # ---- TV komutları
    def tv_open(self, cmd: dict) -> int:
        with self.tv_cv:
            self.tv_seq += 1
            self.tv_cmds.append({**cmd, "seq": self.tv_seq, "at": int(time.time())})
            self.tv_cv.notify_all()
            return self.tv_seq

    def tv_next(self, after: int, wait: float) -> dict:
        end = time.time() + max(0.0, min(wait, 55.0))
        with self.tv_cv:
            while True:
                now = time.time()
                fresh = [c for c in self.tv_cmds if c["seq"] > after and now - c["at"] < 120]
                if fresh or now >= end:
                    return {"seq": self.tv_seq, "commands": fresh}
                self.tv_cv.wait(end - now)

    # ---- HTTP
    def start(self) -> None:
        self.archive.start()
        self.httpd = _Http(("0.0.0.0", self.port), _handler(self))
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self) -> None:
        self.archive.stop()
        if self.httpd:
            self.httpd.shutdown()
            self.httpd.server_close()


class _Http(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        import sys as _sys
        e = _sys.exc_info()[1]
        if isinstance(e, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError, TimeoutError)):
            return
        super().handle_error(request, client_address)


def _strip_fake_header(head: bytes) -> int:
    """Bazı siteler TS parçasının başına sahte resim baytları koyar: ilk gerçek TS paketine kadar olan önek uzunluğu."""
    if head[:1] == b"\x47":
        return 0
    if not (head[:4] == b"\x89PNG" or head[:3] == b"\xff\xd8\xff" or head[:3] == b"GIF" or head[:2] == b"BM"):
        return 0
    for i in range(min(len(head) - 188 * 3, 64 * 1024)):
        if head[i] == 0x47 and head[i + 188] == 0x47 and head[i + 376] == 0x47:
            return i
    return 0


def _handler(srv: Server):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "GFlix"

        def log_message(self, fmt, *a):
            pass

        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Range, Content-Type, X-GFlix-Key")
            self.send_header("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")

        def _send(self, code: int, body: bytes, ctype: str, extra: dict | None = None):
            self.send_response(code)
            self._cors()
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _file(self, f):
            """Küçültülen dosyayı parça parça gönder (Range: kaldığı yerden devam)"""
            if f is None:
                return self._j(404, {"error": "dosya yok"})
            size = f.stat().st_size
            start, end = 0, size - 1
            rng = self.headers.get("Range") or ""
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m and (m.group(1) or m.group(2)):
                if m.group(1):
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else size - 1
                else:
                    start = max(0, size - int(m.group(2)))
            end = min(end, size - 1)
            if start > end:
                self.send_response(416); self.send_header("Content-Range", f"bytes */{size}"); self.end_headers()
                return
            self.send_response(206 if m else 200)
            self._cors()
            self.send_header("Content-Type", "video/mp4")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if m:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if self.command == "HEAD":
                return
            with open(f, "rb") as fh:
                fh.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = fh.read(min(1 << 20, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)

        def _j(self, code: int, obj) -> None:
            self._send(code, json.dumps(obj, ensure_ascii=False).encode(), "application/json; charset=utf-8")

        def _authed(self, q: dict) -> bool:
            k = norm_key(self.headers.get("X-GFlix-Key") or (q.get("k") or [""])[0])
            return bool(k) and hmac.compare_digest(k, srv.key)

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 8 * 1024 * 1024:
                raise ValueError("gövde çok büyük")
            raw = self.rfile.read(n) if n else b""
            return json.loads(raw or b"{}")

        def do_OPTIONS(self):
            self.send_response(204)
            self._cors()
            self.send_header("Content-Length", "0")
            self.end_headers()

        def _gate(self):
            if not allowed_client(self.client_address[0]):
                self._j(403, {"error": "sadece ev ağı ve Tailscale"})
                return None
            u = urlparse(self.path)
            return u, [unquote(x) for x in u.path.strip("/").split("/") if x], parse_qs(u.query)

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            g = self._gate()
            if not g:
                return
            u, parts, q = g
            try:
                if parts == ["api", "ping"]:
                    return self._j(200, {"gflix": True, "yayin": True, "host": srv.host_name, "auth": self._authed(q)})
                if parts[:1] == ["hls"] and len(parts) >= 3:
                    return self._hls(parts)
                if parts[:1] == ["subs"] and len(parts) == 3:
                    return self._sub(parts[1], parts[2])
                if parts[:1] == ["dl"] and len(parts) == 2 and parts[1].endswith(".mp4"):
                    return self._file(srv.tc_file(parts[1][:-4]))
                if parts == ["app.apk"]:          # ev ağından/Tailscale'den indirme (ilk kurulum: telefonun tarayıcısıyla)
                    info = srv.app_info()
                    if not info:
                        return self._j(404, {"error": "bilgisayarda henüz uygulama yok"})
                    f = srv.app_dir() / json.loads((srv.app_dir() / "app.json").read_text())["file"]
                    return self._send(200, f.read_bytes(), "application/vnd.android.package-archive",
                                      {"Content-Disposition": f'attachment; filename="app-{info["name"]}.apk"'})
                if parts[:1] == ["ytf"] and len(parts) == 3:       # bellekteki YouTube parçaları (oturum kimliğiyle)
                    m = srv.mux_by_sid(parts[1])
                    f = m.file(parts[2]) if m else None
                    if not f:
                        return self._j(404, {"error": "yok"})
                    m.used = time.time()
                    data = f.read_bytes()
                    srv.count(len(data))
                    return self._send(200, data, "video/mp2t" if f.suffix == ".ts" else "application/vnd.apple.mpegurl")
                if parts[:1] == ["tv"] and srv.tv_dir:      # TV'nin internet tarayıcısı için uygulamanın tarayıcı sürümü
                    return self._static(srv.tv_dir, parts[1:] or ["index.html"])
                if not self._authed(q):
                    return self._j(401, {"error": "anahtar yanlış ya da yok"})
                if len(parts) == 3 and parts[0] == "yt" and parts[2] == "master.m3u8":
                    return self._yt(parts[1])
                if len(parts) == 2 and parts[0] == "yta":      # tema müziği: YouTube'dan sadece ses, aktarılır (diske yazılmaz)
                    return self._yta(parts[1])
                if parts == ["api", "app"]:
                    return self._j(200, srv.app_info() or {"code": 0})
                if parts == ["api", "catalog"]:
                    return self._j(200, srv.catalog(tv=(q.get("tv") or ["0"])[0] == "1"))
                if parts == ["api", "alerts"]:
                    return self._j(200, {**srv.alerts(), "pending": len(srv.pending_list()), "news": len(srv.news.data.get("items") or [])})
                if parts == ["api", "pending"]:
                    return self._j(200, {"items": srv.pending_list()})
                if parts == ["api", "archive", "jobs"]:
                    with srv.archive.lock:
                        return self._j(200, {"enabled": srv.archive.enabled,
                                             "jobs": [{"key": k, **v} for k, v in srv.archive.jobs.items()]})
                if parts == ["api", "wish"]:
                    return self._j(200, srv.wish_view())
                if parts == ["api", "wish", "cmds"]:
                    return self._j(200, {"cmds": srv.wish_take(min(float((q.get("wait") or ["0"])[0] or 0), 30))})
                if parts == ["api", "prefetch"]:
                    with srv.prefetch_cfg.lock:
                        d = {k: v for k, v in srv.prefetch_cfg.data.items() if k != "done"}
                        dn = srv.prefetch_cfg.data.get("done") or {}
                        lastn = dn[max(dn)] if dn else {}
                        d["tonight"] = len(lastn.get("refreshed", [])) if isinstance(lastn, dict) else 0
                        d["checked"] = len(lastn.get("checked", [])) if isinstance(lastn, dict) else 0
                    return self._j(200, d)
                if parts == ["api", "neweps"]:
                    return self._j(200, {"items": srv.new_episodes()})
                if parts == ["api", "refresh"]:
                    w_ = min(float((q.get("wait") or ["0"])[0] or 0), 30)
                    return self._j(200, {"items": srv.wait_phone_work(w_) if w_ > 0 else srv.refresh_for_phone()})
                if parts == ["api", "follows"]:
                    return self._j(200, {"items": [{k: v for k, v in x.items() if k != "seen"} for x in srv.follows.data.get("items") or []]})
                if parts == ["api", "news"]:
                    return self._j(200, {"items": list(srv.news.data.get("items") or [])})
                if parts == ["api", "item"]:
                    iid = (q.get("id") or [""])[0]
                    if not re.fullmatch(r"(movie|series)-[a-z0-9-]+", iid) or not srv.items_dir:
                        return self._j(400, {"error": "kimlik yanlış"})
                    f = srv.items_dir / f"{iid}.json"
                    if not f.exists():
                        return self._j(404, {"error": "kayıt yok"})
                    return self._j(200, json.loads(f.read_text(encoding="utf-8")))
                if parts == ["api", "stats"]:
                    return self._j(200, srv.stats())
                if parts == ["api", "sources"]:
                    return self._j(200, {"items": srv.sources.all()})
                if parts == ["api", "tv", "next"]:
                    after = int((q.get("after") or ["0"])[0] or 0)
                    wait = float((q.get("wait") or ["25"])[0] or 25)
                    return self._j(200, srv.tv_next(after, wait))
                if parts == ["log"]:
                    from . import livelog
                    return self._j(200, livelog.LOG.since(int((q.get("after") or ["0"])[0] or 0)))
                if parts == ["api", "sessions"]:
                    with srv.slock:
                        return self._j(200, {"sessions": [{"id": s.sid, "item": s.key[0], "season": s.key[1], "episode": s.key[2],
                                                            "created": int(s.created), "used": int(s.used)} for s in srv.sessions.values()]})
                return self._j(404, {"error": "yok"})
            except (BrokenPipeError, ConnectionResetError):
                raise
            except Exception as e:  # noqa: BLE001
                log(f"⚠ {self.path[:80]}: {str(e)[:300]}")
                if not getattr(self, "_started", False):
                    return self._j(502, {"error": str(e)[:300]})

        def do_POST(self):
            g = self._gate()
            if not g:
                return
            u, parts, q = g
            if not self._authed(q):
                return self._j(401, {"error": "anahtar yanlış ya da yok"})
            try:
                b = self._body()
            except ValueError as e:
                return self._j(400, {"error": str(e)})
            try:
                if parts == ["api", "play"]:
                    season = int(b["season"]) if b.get("season") is not None else None
                    episode = int(b["episode"]) if b.get("episode") is not None else None
                    # YouTube: fragman ya da kaynağı YouTube linki olan yapım → /yt/ yolu (oynatıcı doğrudan açar)
                    ylink = b.get("youtube") or (None if b.get("capture") else srv.sources.url_for(str(b.get("id") or ""), season, episode))
                    if ylink and youtube.is_youtube(ylink) or (b.get("youtube") and youtube.video_id(b["youtube"])):
                        vid = youtube.video_id(ylink)
                        host = self.headers.get("Host") or f"127.0.0.1:{srv.port}"
                        return self._j(200, {"session": "", "hls": f"http://{host}/yt/{vid}/master.m3u8?k={srv.key}",
                                             "audio": [], "subs": [], "height": 0, "duration": 0, "youtube": vid})
                    pid_ = str(b.get("id") or "")
                    mh_ = int(b.get("maxHeight") or 0)
                    if not mh_:
                        srv.note_play(pid_, season, episode)
                    try:
                        s = srv.play(pid_, season, episode, capture=b.get("capture"), fresh=bool(b.get("fresh")), max_height=mh_)
                    except RuntimeError as ex:
                        # Link eskimiş, telefon yeniden yakalıyor: izleyeni geri çevirme, gelene kadar bekle → gelir gelmez başlar
                        wait_s = min(float(b.get("waitSec") or 90), 120)
                        if "süresi dolmuş" not in str(ex) or wait_s <= 0 or not srv.wait_refresh(f"{pid_}|{season}|{episode}", wait_s):
                            raise
                        s = srv.play(pid_, season, episode, fresh=bool(b.get("fresh")), max_height=mh_)
                    return self._j(200, self._play_info(s))
                if parts == ["api", "tv", "open"]:
                    cmd = {k: b.get(k) for k in ("id", "season", "episode", "position", "session", "title") if b.get(k) is not None}
                    if not cmd.get("id"):
                        return self._j(400, {"error": "id gerekli"})
                    seq = srv.tv_open(cmd)
                    log(f"📺 TV'de aç: {cmd.get('title') or cmd['id']}")
                    return self._j(200, {"ok": True, "seq": seq})
                if parts == ["api", "source"]:
                    return self._j(200, self._source(b))
                if parts == ["api", "item"]:
                    # Telefon Stüdyo'sunun düzenleme ekranları (bilgi, yayın planı, açılış, yakında, notlar): kayıt sadece burada
                    it = b.get("item") if isinstance(b.get("item"), dict) else None
                    iid = str((it or {}).get("id") or "")
                    if not it or not re.fullmatch(r"(movie|series)-[a-z0-9-]+", iid) or it.get("type") not in ("movie", "series") or not srv.items_dir:
                        return self._j(400, {"error": "kayıt yanlış"})
                    srv.items_dir.mkdir(parents=True, exist_ok=True)
                    f = srv.items_dir / f"{iid}.json"
                    # telefondan bölüm silindiyse o bölümün kaynağı da unutulur (yoksa kaynaktan geri gelir)
                    try:
                        old_eps = {(x.get("season"), x.get("episode")) for x in json.loads(f.read_text(encoding="utf-8")).get("episodes") or []}
                    except (OSError, ValueError):
                        old_eps = set()
                    new_eps = {(x.get("season"), x.get("episode")) for x in it.get("episodes") or []}
                    for se, ep in old_eps - new_eps:
                        if se is not None and ep is not None:
                            srv.sources.remove(iid, int(se), int(ep))
                    tmp = f.with_name(f.name + ".tmp")
                    tmp.write_text(json.dumps(it, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                    tmp.replace(f)
                    log(f"✏️ kayıt düzenlendi (telefon): {it.get('title') or iid}" + (f" — {b.get('message')}" if b.get("message") else ""))
                    srv.rebuild()
                    return self._j(200, {"ok": True})
                if parts == ["api", "sources", "status"]:
                    return self._j(200, {"status": srv.url_status([str(x) for x in b.get("urls") or []])})
                if parts == ["api", "client-log"]:
                    # Telefonun Sayfadan Yakala tanısı (oynatıcı hataları, pencere/izin istekleri): Canlı Kayıt'a
                    for line in (b.get("lines") or [])[:60]:
                        log(f"📱 {str(line)[:220]}")
                    return self._j(200, {"ok": True})
                if parts == ["api", "item", "delete"]:
                    iid = str(b.get("id") or "")
                    if not re.fullmatch(r"(movie|series)-[a-z0-9-]+", iid) or not srv.items_dir:
                        return self._j(400, {"error": "kimlik yanlış"})
                    return self._j(200, srv.delete_item(iid))
                if parts == ["api", "pending", "approve"]:
                    pids = [str(x) for x in b.get("pids") or []]
                    se = int(b["season"]) if b.get("season") is not None else None
                    ep = int(b["episode"]) if b.get("episode") is not None else None
                    st_ = int(b["start"]) if b.get("start") is not None else None
                    return self._j(200, {"results": srv.approve(pids, b.get("tmdb") or None, se, ep, st_)})
                if parts == ["api", "pending", "reject"]:
                    return self._j(200, {"removed": srv.reject([str(x) for x in b.get("pids") or []])})
                if parts == ["api", "tmdb", "search"]:
                    tm = srv.tm()
                    if not tm:
                        return self._j(400, {"error": "bilgisayarda TMDB anahtarı yok"})
                    q = str(b.get("q") or "").strip()
                    out = []
                    for kind in ("movie", "tv"):
                        for r in tm.search(kind, q)[:8]:
                            out.append({"kind": kind, "id": r["id"], "title": r["title"], "original": r.get("original_title") or "", "year": r.get("year"), "poster": r.get("poster"),
                                        "tmdb": f"{kind}/{r['id']}"})
                    return self._j(200, {"results": out})
                if parts == ["api", "tmdb", "seasons"]:
                    # İstek listesi: dizinin sezonları ve bölüm sayıları (0. sezon = özel bölümler, dahil edilmez)
                    tm = srv.tm()
                    if not tm:
                        return self._j(400, {"error": "bilgisayarda TMDB anahtarı yok"})
                    t_ = parse_tmdb(str(b.get("tmdb") or ""))
                    if not t_ or t_[0] != "tv":
                        return self._j(400, {"error": "dizi değil"})
                    d = tm.get(f"/tv/{t_[1]}", language="tr-TR") or {}
                    out = [{"season": x["season_number"], "episodes": x.get("episode_count") or 0}
                           for x in d.get("seasons") or [] if (x.get("season_number") or 0) > 0]
                    return self._j(200, {"seasons": out})
                if parts == ["api", "alerts", "recheck"]:
                    k = str(b.get("key") or "")
                    x = next((x for x in srv.link_targets() if x["key"] == k), None)
                    if not x:
                        with srv.links.lock:
                            srv.links.data.pop(k, None)
                            srv.links.dirty = True
                        return self._j(200, {"ok": True, "state": "gone"})
                    state, detail = check_link(x["kind"], x["url"])
                    with srv.links.lock:
                        srv.links.data[k] = {**(srv.links.data.get(k) or {}), "state": state, "detail": detail, "at": int(time.time()),
                                             "id": x["id"], "what": x["what"], "kind": x["kind"], "ignored": False}
                        srv.links.dirty = True
                    return self._j(200, {"ok": True, "state": state, "detail": detail})
                if parts == ["api", "alerts", "ignore"]:
                    k = str(b.get("key") or "")
                    with srv.links.lock:
                        if k in srv.links.data:
                            srv.links.data[k]["ignored"] = True
                            srv.links.dirty = True
                    return self._j(200, {"ok": True})
                if parts == ["api", "prefetch"]:
                    with srv.prefetch_cfg.lock:
                        for k in ("on", "from", "to", "max"):
                            if k in b:
                                srv.prefetch_cfg.data[k] = b[k]
                        srv.prefetch_cfg.dirty = True
                    srv.prefetch_cfg.flush()
                    return self._j(200, {"ok": True})
                if parts == ["api", "direct"]:
                    se_ = int(b["season"]) if b.get("season") is not None else None
                    ep_ = int(b["episode"]) if b.get("episode") is not None else None
                    iid = str(b.get("id") or "")
                    # önce linkin canlı olduğundan emin ol (ölüyse yakalama cihazı yeniler; gelene kadar beklenir)
                    try:
                        sess = srv.play(iid, se_, ep_, fresh=True, count=False)
                    except RuntimeError as ex:
                        if "süresi dolmuş" not in str(ex) or not srv.wait_refresh(f"{iid}|{se_}|{ep_}", 90):
                            raise
                        sess = srv.play(iid, se_, ep_, fresh=True, count=False)
                    info = srv.direct_info(iid, se_, ep_)
                    host = self.headers.get("Host") or f"127.0.0.1:{srv.port}"
                    info["subs"] = [{"lang": x["lang"], "label": x["label"], "forced": x["forced"], "url": f"http://{host}/subs/{sess.sid}/{i}.vtt"}
                                    for i, x in enumerate(sess.res.subs)]
                    return self._j(200, info)
                if parts == ["api", "transcode", "add"]:
                    se_ = int(b["season"]) if b.get("season") is not None else None
                    ep_ = int(b["episode"]) if b.get("episode") is not None else None
                    return self._j(200, {"job": srv.tc_add(str(b.get("id") or ""), se_, ep_, int(b.get("height") or 480))})
                if parts == ["api", "transcode", "status"]:
                    st = srv.tc_status(str(b.get("job") or ""))
                    if st.get("state") == "ready":
                        st["url"] = f"http://{self.headers.get('Host') or f'127.0.0.1:{srv.port}'}/dl/{b.get('job')}.mp4"
                    return self._j(200, st)
                if parts == ["api", "transcode", "done"]:
                    srv.tc_done(str(b.get("job") or ""))
                    return self._j(200, {"ok": True})
                if parts == ["api", "qualities"]:
                    se_ = int(b["season"]) if b.get("season") is not None else None
                    ep_ = int(b["episode"]) if b.get("episode") is not None else None
                    return self._j(200, {"heights": srv.qualities(str(b.get("id") or ""), se_, ep_)})
                if parts == ["api", "wish", "report"]:
                    st = b.get("state") if isinstance(b.get("state"), dict) else {}
                    return self._j(200, {"cmds": srv.wish_report(str(b.get("device") or ""), st)})
                if parts == ["api", "wish", "cmd"]:
                    return self._j(200, srv.wish_cmd(b.get("cmd") or {}))
                if parts == ["api", "soon", "plan"]:
                    return self._j(200, srv.soon_plan([x for x in b.get("items") or [] if isinstance(x, dict)]))
                if parts == ["api", "pccap", "add"]:
                    return self._j(200, {"jid": srv.pccap_add(str(b.get("url") or ""), b.get("hint") if isinstance(b.get("hint"), dict) else None),
                                         "visible": srv.pccap_visible()})
                if parts == ["api", "pccap", "status"]:
                    with srv.pccap_lock:
                        return self._j(200, {"jobs": {j: srv.pccap_status(x) for j, x in srv.pccap.items()
                                                      if j in set(b.get("jids") or [])}, "visible": srv.pccap_visible(),
                                             "busy": any(x["state"] in ("wait", "work") for x in srv.pccap.values())})
                if parts == ["api", "follows", "add"]:
                    return self._j(200, srv.follow_add(str(b.get("url") or ""), str(b.get("tmdb") or ""), str(b.get("title") or ""),
                                                       int(b.get("season") or 1), int(b.get("first") or 1), int(b.get("min") or 30)) | {"seen": None})
                if parts == ["api", "follows", "remove"]:
                    srv.follow_remove(str(b.get("key") or ""))
                    return self._j(200, {"ok": True})
                if parts == ["api", "follows", "check"]:
                    rec = next((x for x in srv.follows.data.get("items") or [] if x.get("key") == b.get("key")), None)
                    if not rec:
                        return self._j(404, {"error": "takip yok"})
                    return self._j(200, {"added": srv.follow_check(rec)})
                if parts == ["api", "news", "done"]:
                    srv.news_done(str(b.get("key") or ""))
                    return self._j(200, {"ok": True})
                if parts == ["api", "theme"]:
                    return self._j(200, srv.theme_for(str(b.get("id") or ""), str(b["bad"]) if b.get("bad") else None,
                                                      str(b["url"]) if b.get("url") else None))
                if parts == ["api", "trailer"]:
                    tid = str(b.get("id") or "")
                    if not srv.find_title(tid):
                        return self._j(404, {"error": "yapım yok"})
                    if b.get("bad"):
                        srv.sources.mark_bad_trailer(tid, str(b["bad"]))
                        log(f"🎞 telefondan 'yanlış fragman': {tid} ← {b['bad']}")
                    if b.get("url"):
                        srv.sources.set_trailer(tid, str(b["url"]), str(b.get("lang") or "tr"))
                        log(f"🎞 fragman linki verildi: {tid} [{b.get('lang') or 'tr'}]")
                    t = next((x for x in srv.catalog(tv=False).get("titles") or [] if x.get("id") == tid), {})
                    return self._j(200, {"ok": True, "trailers": t.get("trailers") or []})
                if parts == ["api", "source", "delete"]:
                    ok = srv.sources.remove(str(b.get("id") or ""), b.get("season"), b.get("episode"), bool(b.get("pattern")))
                    return self._j(200, {"ok": ok})
                return self._j(404, {"error": "yok"})
            except LookupError as e:
                return self._j(404, {"error": str(e), "needsSource": True})
            except ValueError as e:
                return self._j(400, {"error": str(e)})
            except Exception as e:  # noqa: BLE001
                log(f"✗ {'/'.join(parts)}: {str(e)[:300]}")
                return self._j(502, {"error": str(e)[:400]})

        def _yta(self, vid: str):
            if not youtube.video_id(vid):
                return self._j(400, {"error": "YouTube kimliği yanlış"})
            try:
                a = srv.theme_audio(vid)
            except Exception as e:  # noqa: BLE001
                return self._j(502, {"error": str(e)[:300]})
            h = {"User-Agent": resolve.CHROME_UA, **(a.get("headers") or {})}
            if self.headers.get("Range"):
                h["Range"] = self.headers["Range"]
            r = requests.get(a["url"], headers=h, stream=True, timeout=30)
            try:
                if r.status_code in (403, 410):
                    srv.audio_cache.pop(vid, None)
                if not r.ok:
                    return self._j(502, {"error": f"YouTube sesi vermedi (HTTP {r.status_code})"})
                self._started = True
                self.send_response(r.status_code)
                self._cors()
                self.send_header("Content-Type", "audio/mp4" if a.get("ext") in ("m4a", "mp4") else "audio/webm")
                self.send_header("Accept-Ranges", "bytes")
                for k in ("Content-Length", "Content-Range"):
                    if r.headers.get(k):
                        self.send_header(k, r.headers[k])
                self.end_headers()
                if self.command == "HEAD":
                    return
                for chunk in r.iter_content(64 * 1024):
                    srv.count(len(chunk))
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                r.close()

        def _yt(self, vid: str):
            if not youtube.video_id(vid):
                return self._j(400, {"error": "YouTube kimliği yanlış"})
            kind, obj = srv.youtube(vid)
            base = f"http://{self.headers.get('Host') or f'127.0.0.1:{srv.port}'}"
            if kind == "hls":
                pre = f"{base}/hls/{obj.sid}/"
                text = srv.master(obj)
            else:
                obj.used = time.time()
                pre = f"{base}/ytf/{obj.sid}/"
                text = obj.playlist()
            out = []
            for line in text.splitlines():
                if line and not line.startswith("#"):
                    line = pre + line
                elif 'URI="' in line:
                    line = re.sub(r'URI="([^"]+)"', lambda m: f'URI="{pre}{m.group(1)}"', line)
                out.append(line)
            return self._send(200, ("\n".join(out) + "\n").encode(), "application/vnd.apple.mpegurl")

        def _static(self, root: Path, rel: list[str]):
            f = (root / "/".join(rel)).resolve()
            if not str(f).startswith(str(root.resolve())) or not f.is_file():
                return self._j(404, {"error": "yok"})
            types = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
                     ".png": "image/png", ".svg": "image/svg+xml", ".ttf": "font/ttf", ".json": "application/json"}
            return self._send(200, f.read_bytes(), types.get(f.suffix, "application/octet-stream"))

        def _source(self, b: dict) -> dict:
            url, pattern = b.get("url"), b.get("pattern")
            item_id = b.get("id")
            season = int(b["season"]) if b.get("season") is not None else None
            episode = int(b["episode"]) if b.get("episode") is not None else None
            rf = (b.get("hint") or {}).get("refresh") if isinstance(b.get("hint"), dict) else None
            if not item_id and isinstance(rf, dict) and rf.get("id"):
                # Tazeleme: zaten yayında olan yapımın süresi dolmuş linki; onaya gitmez, doğrudan kaydedilir
                item_id = str(rf["id"])
                season = int(rf["season"]) if rf.get("season") is not None else None
                episode = int(rf["episode"]) if rf.get("episode") is not None else None
            if item_id:
                with srv.refresh_lock:
                    srv.refresh_req.pop(f"{item_id}|{season}|{episode}", None)
                    srv.refresh_cv.notify_all()
            if not item_id:
                # Telefonun Sayfadan Yakala'sı / Link ekle: doğrudan yayınlanmaz, önce onay kuyruğuna (bildirim gider);
                # kullanıcı TMDB ile eşleştirip onaylayınca yayına girer
                if not url:
                    raise ValueError("link gerekli")
                if youtube.is_playlist(url):
                    # Oynatma listesi: her video sırasıyla kuyruğa (toplu eşleştirmede 'sırayla numarala' bu sırayı kullanır)
                    vids = youtube.playlist(url)
                    if not vids:
                        raise ValueError("oynatma listesi boş")
                    base = time.time()
                    for i, (vid, vtitle) in enumerate(vids):
                        srv.queue_link(f"https://www.youtube.com/watch?v={vid}", vtitle, None, order=base + i / 1000)
                    return {"ok": True, "pending": True, "title": f"oynatma listesinden {len(vids)} video", "guessOk": True, "count": len(vids)}
                title = b.get("title") or ""
                if not title:
                    title = link_title(url)
                cap = b.get("capture") if isinstance(b.get("capture"), dict) and (b["capture"].get("streams") or []) else None
                rec = srv.queue_link(url, title, cap, hint=b.get("hint") if isinstance(b.get("hint"), dict) else None)
                g = rec["guess"]
                return {"ok": True, "pending": True, "pid": rec["pid"], "title": g.get("label") or title,
                        "guessOk": bool(g.get("ok")), "reason": g.get("reason")}
            srv.sources.set(item_id, url, season, episode, pattern)
            if isinstance(b.get("capture"), dict) and (b["capture"].get("streams") or []):
                srv.captures.save(item_id, season, episode, b["capture"])
                # aynı yapımın eski oturumu (eski linkler) yeniden kullanılmasın
                with srv.slock:
                    for sid in [k for k, x in srv.sessions.items() if x.key == (item_id, season, episode)]:
                        srv.sessions.pop(sid, None)
            t = srv.find_title(item_id) or {}
            what = (t.get("title") or item_id) + (f" S{season:02d}E{episode:02d}" if season is not None else "")
            log(f"🔗 kaynak kaydedildi: {what}" + (" (bölüm kalıbı)" if pattern else ""))
            return {"ok": True, "id": item_id, "season": season, "episode": episode, "title": t.get("title")}

        def _play_info(self, s: Session) -> dict:
            host = self.headers.get("Host") or f"127.0.0.1:{srv.port}"
            base = f"http://{host}"
            r = s.res
            return {
                "session": s.sid,
                "hls": f"{base}/hls/{s.sid}/master.m3u8",
                "audio": [{"lang": l, "name": LANG_LABEL.get(l, l)} for l, _ in r.audios],
                "subs": [{"lang": x["lang"], "label": x["label"], "forced": x["forced"], "url": f"{base}/subs/{s.sid}/{i}.vtt"}
                         for i, x in enumerate(r.subs)],
                "height": r.height, "duration": round(r.duration), "title": s.item.get("title"),
            }

        def _session(self, sid: str) -> Session | None:
            with srv.slock:
                s = srv.sessions.get(sid)
            if s:
                s.used = time.time()
            return s

        def _sub(self, sid: str, name: str):
            s = self._session(sid)
            m = re.fullmatch(r"(\d+)\.vtt", name)
            if not s or not m or int(m.group(1)) >= len(s.res.subs):
                return self._j(404, {"error": "yok"})
            return self._send(200, s.res.subs[int(m.group(1))]["text"].encode("utf-8"), "text/vtt; charset=utf-8")

        def _hls(self, parts: list[str]):
            s = self._session(parts[1])
            if not s:
                return self._j(404, {"error": "oturum yok ya da süresi doldu; yeniden oynat"})
            rest = parts[2:]
            if rest == ["master.m3u8"]:
                return self._send(200, srv.master(s).encode(), "application/vnd.apple.mpegurl")
            m = re.fullmatch(r"(v|a\d+)\.m3u8", rest[0]) if len(rest) == 1 else None
            if m:
                try:
                    text = srv.media(s, m.group(1))
                except KeyError:
                    return self._j(404, {"error": "yok"})
                return self._send(200, text.encode(), "application/vnd.apple.mpegurl")
            if len(rest) == 2 and re.fullmatch(r"v|a\d+", rest[0]) and rest[0] in s.tracks:
                m = re.fullmatch(r"(key|init)?(\d+)(\.[a-z0-9]+)?", rest[1])
                if not m:
                    return self._j(404, {"error": "yok"})
                kind = m.group(1) or "seg"
                try:
                    return self._pipe(s, rest[0], kind, int(m.group(2)), m.group(3) or "")
                except KeyError:
                    return self._j(404, {"error": "yok"})
            return self._j(404, {"error": "yok"})

        def _pipe(self, s: Session, track: str, kind: str, idx: int, ext: str):
            rng = self.headers.get("Range")
            if rng and re.fullmatch(r"\s*bytes=0-\s*", rng):
                rng = None       # "baştan sona" = tam parça (ffmpeg, AVPlay böyle ister); 200 ile tamamı gönderilir
            r = srv.piece(s, track, kind, idx, rng)
            try:
                if not r.ok:
                    return self._j(502, {"error": f"site parçayı vermedi (HTTP {r.status_code})"})
                it = r.iter_content(64 * 1024)
                first = b""
                skip = 0
                if kind == "seg" and not rng and ext == ".ts":
                    for chunk in it:          # sahte resim öneki var mı diye ilk baytlara bak
                        first += chunk
                        if len(first) >= 128 * 1024:
                            break
                    skip = _strip_fake_header(first)
                    first = first[skip:]
                ctype = r.headers.get("Content-Type") or "application/octet-stream"
                if kind == "seg" and (ctype.startswith(("image/", "text/", "application/octet")) or skip):
                    ctype = {".ts": "video/mp2t", ".m4s": "video/iso.segment", ".mp4": "video/mp4", ".aac": "audio/aac"}.get(ext, "video/mp2t")
                length = r.headers.get("Content-Length")
                enc = (r.headers.get("Content-Encoding") or "").lower()
                self._started = True
                self.send_response(206 if r.status_code == 206 else 200)
                self._cors()
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                if r.headers.get("Content-Range"):
                    self.send_header("Content-Range", r.headers["Content-Range"])
                chunked = not length or enc not in ("", "identity")
                if chunked:
                    self.send_header("Transfer-Encoding", "chunked")
                else:
                    self.send_header("Content-Length", str(int(length) - skip))
                self.end_headers()
                if self.command == "HEAD":
                    return

                def out(b: bytes):
                    if not b:
                        return
                    srv.count(len(b))
                    if chunked:
                        self.wfile.write(f"{len(b):x}\r\n".encode() + b + b"\r\n")
                    else:
                        self.wfile.write(b)
                out(first)
                for chunk in it:
                    out(chunk)
                if chunked:
                    self.wfile.write(b"0\r\n\r\n")
            finally:
                r.close()

    return H


def link_title(url: str) -> str:
    """Link ekle (telefon): YouTube videosunun başlığı ya da sayfanın <title>/og:title'ı."""
    if youtube.is_youtube(url):
        return youtube.probe(youtube.video_id(url), log=lambda *_: None).get("title") or ""
    try:
        r = resolve.http_get(url, headers={"User-Agent": resolve.CHROME_UA}, timeout=20, limit=512 * 1024)
    except Exception as e:  # noqa: BLE001
        raise ValueError(f"Sayfa açılamadı ({str(e)[:80]}). Telefonda Sayfadan Yakala ile aç.") from e
    html = r.content.decode("utf-8", "replace")
    m = re.search(r'<meta[^>]+property=["\']og:title["\'][^>]+content=["\']([^"\']+)', html, re.I) or re.search(r"<title[^>]*>([^<]+)</title>", html, re.I)
    if not r.ok or not m:
        raise ValueError("Sayfanın başlığı okunamadı (site koruma istiyor olabilir). Telefonda Sayfadan Yakala ile aç.")
    import html as _h
    return _h.unescape(m.group(1)).strip()


# ------------------------------------------------------------------ link kontrolü

DEAD_YT = re.compile(r"unavailable|private video|removed|terminated|not available|does not exist|kullanılamıyor", re.I)


def check_link(kind: str, url: str) -> tuple[str, str]:
    """('ok' | 'dead' | 'unknown', açıklama). Ölü: sayfa 404/410, site adı çözülmüyor/bağlanılamıyor, YouTube videosu
    kaldırılmış/gizli. Bilinmiyor: site koruması (403/429/503), zaman aşımı — bunlar bildirilmez."""
    if kind == "youtube":
        try:
            youtube.probe(youtube.video_id(url), log=lambda *_: None, timeout=60)
            return "ok", ""
        except Exception as e:  # noqa: BLE001
            return ("dead" if DEAD_YT.search(str(e)) else "unknown"), str(e)[:160]
    try:
        r = resolve.http_get(url, headers={"User-Agent": resolve.CHROME_UA}, timeout=25, limit=64 * 1024)
    except requests.ConnectionError as e:
        return "dead", f"siteye ulaşılamıyor (site kapanmış ya da adresi değişmiş olabilir): {str(e)[:100]}"
    except Exception as e:  # noqa: BLE001
        return "unknown", str(e)[:160]
    if r.status_code in (404, 410):
        return "dead", f"sayfa yok (HTTP {r.status_code})"
    if r.ok:
        return "ok", ""
    return "unknown", f"HTTP {r.status_code} (koruma olabilir)"


# ------------------------------------------------------------------ sayfa → katalogdaki yapım (Sayfadan Yakala eşleştirmesi)

def _has_display() -> bool:
    """Masaüstü var mı; servis masaüstü ortamını almadıysa açık X ekranını bul (DISPLAY/XAUTHORITY)."""
    if os.environ.get("DISPLAY"):
        return True
    import glob
    socks = sorted(glob.glob("/tmp/.X11-unix/X*"))
    if not socks:
        return False
    os.environ["DISPLAY"] = ":" + socks[0].rsplit("X", 1)[-1]
    uid = os.getuid()
    for cand in [os.path.expanduser("~/.Xauthority"), f"/run/user/{uid}/gdm/Xauthority", *glob.glob(f"/run/user/{uid}/.mutter-Xwaylandauth.*"),
                 *glob.glob(f"/run/user/{uid}/xauth_*")]:
        if os.path.isfile(cand):
            os.environ.setdefault("XAUTHORITY", cand)
            break
    return True


def parse_tmdb(s: str | None) -> tuple[str, int] | None:
    """'https://www.themoviedb.org/tv/1234-ad', 'movie/567', 'tv 1234' → ('tv'|'movie', no)."""
    m = re.search(r"\b(movie|tv|film|dizi)\s*[/ :#-]\s*(\d+)", (s or "").strip(), re.I)
    if not m:
        return None
    k = m.group(1).lower()
    return ("tv" if k in ("tv", "dizi") else "movie"), int(m.group(2))


def guess_link(srv: Server, url: str, title: str) -> dict:
    """Onay kuyruğu için tahmin (yan etkisiz): yapım, TMDB no, sezon/bölüm; bölüm TMDB'de var mı.
    {ok, label, kind, tmdbId, id?, season?, episode?, reason?}"""
    out: dict = {"ok": False}
    g = resolve.guess_title([x for x in (title, "" if youtube.is_youtube(url) else url) if x])
    if not g:
        return {**out, "reason": "başlıktan yapım adı çıkarılamadı"}
    try:
        c = json.loads(srv.catalog_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        c = {"titles": []}
    resolve.KNOWN_SERIES[:] = [{"title": t.get("title"), "tmdbId": t.get("tmdbId"), "year": t.get("year"),
                                "names": [t.get("title") or ""] + [n for n in (t.get("names") or {}).values() if n]}
                               for t in c.get("titles") or [] if t.get("type") == "series" and t.get("tmdbId")]
    tm = srv.tm()
    kind = tid = name = None
    found = None
    if tm:
        try:
            found = resolve.match_tmdb(tm, g, lambda *_: None)
        except Exception as e:  # noqa: BLE001
            out["reason"] = f"TMDB'ye sorulamadı: {str(e)[:80]}"
    if found:
        k, r = found
        kind, tid, name = ("series" if k == "tv" else "movie"), r["id"], r.get("title")
    else:
        want_series = g.season is not None or g.abs_episode is not None
        q = resolve._norm(g.query)
        t = next((x for x in c.get("titles") or [] if (x.get("type") == "series") == want_series and
                  q in {resolve._norm(n) for n in [x.get("title") or ""] + list((x.get("names") or {}).values()) if n}), None)
        if not t and want_series:
            k2 = resolve._known_series(g)
            t = next((x for x in c.get("titles") or [] if k2 and x.get("tmdbId") == k2["tmdbId"]), None)
        if t:
            kind, tid, name = t.get("type"), t.get("tmdbId"), t.get("title")
    if not tid:
        return {**out, "reason": out.get("reason") or f"\"{g.query}\" TMDB'de bulunamadı", "query": g.query,
                "season": g.season, "episode": g.episode}
    cat = next((x for x in c.get("titles") or [] if x.get("tmdbId") == tid and x.get("type") == kind), None)
    season, episode = g.season, g.episode
    if kind == "series" and season is None and g.abs_episode and tm:
        se = resolve.abs_to_season(tm, tid, g.abs_episode)
        if se:
            season, episode = se
    res = {"kind": kind, "tmdbId": tid, "title": (cat or {}).get("title") or name, "id": (cat or {}).get("id"),
           "season": season if kind == "series" else None, "episode": episode if kind == "series" else None}
    res["label"] = res["title"] + (f" S{season:02d}E{episode:02d}" if kind == "series" and season is not None else "")
    if kind == "series" and season is None:
        return {**res, "ok": False, "reason": "dizi bulundu ama sezon/bölüm anlaşılamadı"}
    if kind == "series" and tm:
        try:
            if not tm.get(f"/tv/{tid}/season/{season}/episode/{episode}"):
                return {**res, "ok": False, "reason": f"TMDB'de bu dizinin S{season:02d}E{episode:02d} bölümü yok — başka bir yapıma ait olabilir"}
        except Exception:  # noqa: BLE001
            pass
    return {**res, "ok": True}


def match_page(srv: Server, url: str, title: str) -> tuple[str, int | None, int | None]:
    """Sayfa başlığı/adresinden yapım + bölüm (resolve.guess_title + TMDB), katalogdaki kaydın kimliği."""
    g = resolve.guess_title([x for x in (title, url) if x])
    if not g:
        raise ValueError("Sayfa başlığından yapım adı çıkarılamadı; kimliği (id) elle ver.")
    try:
        c = json.loads(srv.catalog_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        c = {"titles": []}
    want_series = g.season is not None or g.abs_episode is not None
    resolve.KNOWN_SERIES[:] = [{"title": t.get("title"), "tmdbId": t.get("tmdbId"), "year": t.get("year"),
                                "names": [t.get("title") or ""] + [n for n in (t.get("names") or {}).values() if n]}
                               for t in c.get("titles") or [] if t.get("type") == "series" and t.get("tmdbId")]
    found = None
    if srv.tmdb_key:
        from .tmdb import TMDB
        found = resolve.match_tmdb(TMDB(srv.tmdb_key), g, log)
    if found:
        kind, r = found
        t = next((x for x in c.get("titles") or [] if x.get("tmdbId") == r["id"]), None)
        if not t:
            new_id = srv.add_item(kind, r["id"], r.get("title") or g.query, r.get("year"))
            t = srv.find_title(new_id) or {"id": new_id, "type": "series" if kind == "tv" else "movie", "tmdbId": r["id"]}
    else:
        q = resolve._norm(g.query)
        t = next((x for x in c.get("titles") or [] if (x.get("type") == "series") == want_series and
                  q in {resolve._norm(n) for n in [x.get("title") or ""] + list((x.get("names") or {}).values()) if n}), None)
        if not t and want_series:       # katalogdaki dizi adıyla önek eşleşmesi (tek aday varsa)
            k = resolve._known_series(g)
            t = next((x for x in c.get("titles") or [] if k and x.get("tmdbId") == k["tmdbId"]), None)
        if not t:
            raise ValueError(f"\"{g.query}\" katalogda bulunamadı; kimliği (id) elle ver.")
    season, episode = g.season, g.episode
    if t.get("type") == "series":
        if season is None and g.abs_episode and srv.tmdb_key and t.get("tmdbId"):
            from .tmdb import TMDB
            se = resolve.abs_to_season(TMDB(srv.tmdb_key), t["tmdbId"], g.abs_episode)
            if se:
                season, episode = se
        if season is None:
            raise ValueError("Dizi bulundu ama sezon/bölüm anlaşılamadı; sezon ve bölümü elle ver.")
    else:
        season = episode = None
    return t["id"], season, episode
