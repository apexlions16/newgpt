#!/usr/bin/env python3
"""
G-Flix Linux bilgisayar işçisi.

Uygulamalarda "Kim indirsin? → 🖥️ Bilgisayarım" seçilince tarif "pcjob-…" adlı bir Release'e konur. Bu program
(kendi Linux bilgisayarında, systemd ile sürekli) o işleri alır ve linkten ekleme hattının tamamını yerelde çalıştırır:

  aria2c / yt-dlp / gdown ile indir → MKVToolNix ile tek MKV → (gerekirse sesi AAC'ye çevir)
  → MKV'yi parça parça GitHub'a yükle → her parçayı GitHub'dan doğrula (boyut + sha256)
  → kayda (catalog/items/…json) yaz → kaynakları ve MKV'yi sil.

Katalog (TMDB bilgileri, posterler) GitHub'daki "Katalog Güncelle" iş akışında güncellenir.
Fragmanlar yt-dlp ile bu bilgisayarda iner: 1080p H.264 MP4 + varsa 4K VP9.

Kurulum: linux/install.sh. Ayarlar: ~/.config/gflix/worker.env
  GITHUB_TOKEN=…        (yazma yetkili token: Contents Read and write)
  GFLIX_REPO=kullanici/g-flix
  TMDB_API_KEY=…        (isteğe bağlı: tarifte "ad:" yoksa adı TMDB'den almak için)
  GFLIX_WORKDIR=~/gflix-work
  GFLIX_YT_COOKIES_FILE=~/.config/gflix/cookies.txt  (isteğe bağlı)
"""
from __future__ import annotations

import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

os.environ.setdefault("GFLIX_OUTPUT", "mkv")       # MKV'yi olduğu gibi yükle (HLS'e çevirme)
os.environ.setdefault("GFLIX_CATALOG", "github")   # katalog GitHub'da güncellensin
os.environ.setdefault("GFLIX_WORKER_NAME", socket.gethostname())
os.environ.setdefault("GFLIX_RUN_URL", "")
os.environ.setdefault("GFLIX_LOG_DIR", os.path.join(os.path.expanduser(os.environ.get("GFLIX_WORKDIR", "~/gflix-work")), "kayitlar"))

import link_ingest as li  # noqa: E402
from gflix import lan as lan_mod  # noqa: E402
from gflix.github import GitHub  # noqa: E402
from gflix import autotrailers as autotrailers_mod  # noqa: E402

JOB_PREFIX = "pcjob-"
HEARTBEAT_TAG = "pcworker"
POLL = int(os.environ.get("GFLIX_POLL", "20"))
LOG_TAG = "pclog"      # canlı kayıt: telefon izlerken son satırlar bu Release'in gövdesine yazılır
LOG_WATCH_WINDOW = 120 # telefon "izliyorum" dedikten sonra bu kadar sn yazmaya devam
CONFIG_TAG = "pcconfig"   # Stüdyo'dan ayarlar: başlama gecikmesi, gece modu, istek hakkı koruması
CMD_PREFIX = "pccmd-"     # telefondan komut Release'i (gövde "cancel_all"): işlenince silinir
# Aynı siteden aynı anda HER ZAMAN tek iş (kullanıcı kararı, 3 Kasım 2026). Stüdyo'daki 1–5 seçimi kaldırıldı;
# eski pcconfig'deki "site_jobs" yok sayılır. Önceki iş GitHub'a yüklenirken sıradakinin indirmesi yine başlar.
SITE_JOBS_MAX = 1
BACKOFF_SEC = 3600        # site zorlanırsa (bağlantı/429 hatasıyla düşen iş) bu kadar süre aynı anda 1 iş
STATS_KEPT = 1000      # bilgisayardaki dosyada tutulan iş sayısı
STATS_SHOWN = 150      # telefona gönderilen (son) iş sayısı
# Aynı anda en fazla kaç iş: 2 = biri GitHub'a yüklerken sıradaki siteden iner. Siteden aynı anda yine tek iş indirir
# (link_ingest.SITE_SLOT); ikinci iş ancak öncekinin indirmesi bitince başlar, siteye giden yük tek işle aynıdır.
MAX_JOBS = max(1, min(2, int(os.environ.get("GFLIX_JOBS", "2") or 2)))
# İkinci/üçüncü işe başlamak için gereken en az boş alan (GB)
MIN_FREE_GB = float(os.environ.get("GFLIX_MIN_FREE_GB", "30"))
stop = False


def log(msg: str) -> None:
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def _hhmm(v, default: str) -> str:
    m = __import__("re").fullmatch(r"\s*(\d{1,2})[:.](\d{2})\s*", str(v or ""))
    return f"{int(m.group(1)) % 24:02d}:{int(m.group(2)) % 60:02d}" if m else default


def _mins(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def git_head() -> str:
    r = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip()


def self_update() -> bool:
    """Depodaki son sürümü çeker; kod değiştiyse True (program kendini yeniden başlatır)."""
    before = git_head()
    r = subprocess.run(["git", "pull", "-q", "--ff-only"], cwd=ROOT, capture_output=True, text=True)
    if r.returncode != 0:
        log(f"güncelleme çekilemedi: {r.stderr.strip()[:200]}")
        return False
    return git_head() != before


class Worker:
    def __init__(self):
        self.gh = GitHub(os.environ["GFLIX_REPO"], os.environ["GITHUB_TOKEN"], log=log)
        self.host = os.environ["GFLIX_WORKER_NAME"]
        self.workdir = Path(os.path.expanduser(os.environ.get("GFLIX_WORKDIR", "~/gflix-work")))
        self.busy: str | None = None
        self.active: dict[str, threading.Thread] = {}      # şu an işlenen işler (etiket → iş parçacığı)
        # Süre istatistiği: biten her işin aşama süreleri (kalıcı dosya; son kayıtlar kalp atışıyla telefona gider)
        self.site_jobs = 1            # Stüdyo'da seçilen: aynı siteden aynı anda kaç iş
        self.start_delay = 0          # Stüdyo: iş gönderildikten kaç dakika sonra başlansın
        self.night = {"on": False, "start": "04:00", "end": "10:00"}   # Stüdyo: işler sadece bu saatlerde başlar
        self.night_marked: set[str] = set()   # "🌙 … başlayacak" yazılan bekleyen işler
        self.quota_paused_logged = False      # istek hakkı koruması: "yeni işler ertelendi" bir kez yazılır
        self.backoff_until = 0.0      # site zorlandıysa bu zamana kadar 1 iş
        self.last_config = 0.0
        self.stats_path = self.workdir / "sure-istatistigi.json"
        self.stats_lock = threading.Lock()
        try:
            self.stats: list[dict] = json.loads(self.stats_path.read_text(encoding="utf-8"))
            if not isinstance(self.stats, list):
                self.stats = []
        except (OSError, ValueError):
            self.stats = []
        self.last_idle_log = 0.0
        self.lan = None
        if os.environ.get("GFLIX_LAN", "1") != "0":
            try:
                self.lan = lan_mod.LanServer(self.workdir / "telefondan", os.environ["GITHUB_TOKEN"],
                                             int(os.environ.get("GFLIX_LAN_PORT", lan_mod.DEFAULT_PORT)), log=log, host_name=self.host)
            except Exception as e:  # noqa: BLE001
                log(f"ev ağı alıcısı kurulamadı: {e}")
        self.last_beat = 0.0
        self.hb_rel: dict | None = None

    def site_jobs_now(self) -> int:
        return 1 if time.time() < self.backoff_until else self.site_jobs

    def max_jobs(self) -> int:
        """Aynı anda en fazla iş: siteden inen iş sayısı + 1 (o sırada GitHub'a yüklenen). GFLIX_JOBS=1 ise tek iş."""
        return 1 if MAX_JOBS <= 1 else self.site_jobs_now() + 1

    def apply_site_jobs(self) -> None:
        n = self.site_jobs_now()
        li.SITE_SLOT.set_limit(n)
        from gflix import hls as hls_mod
        hls_mod.PER_JOB = n > 1       # birden çok iş: her işin kendi hız sınırı (1DM'deki gibi)

    def read_config(self) -> None:
        """Stüdyo'daki 'aynı siteden aynı anda kaç iş' seçimi ("pcconfig" Release gövdesi)."""
        if time.time() - self.last_config < 60:
            return
        self.last_config = time.time()
        try:
            rel = self.gh.get_release(CONFIG_TAG)
            cfg = json.loads((rel or {}).get("body") or "{}")
            n = 1     # sabit: aynı anda tek iş siteden iner (cfg["site_jobs"] yok sayılır)
            delay = max(0, min(60, int(cfg.get("start_delay_min") or 0)))
            nt = cfg.get("night") or {}
            night = {"on": bool(nt.get("on")), "start": _hhmm(nt.get("start"), "04:00"), "end": _hhmm(nt.get("end"), "10:00")}
        except Exception:  # noqa: BLE001
            return
        qg = cfg.get("quota_guard") or {}
        from gflix.github import QUOTA
        q_on = bool(qg.get("on", True))
        q_min = max(100, min(4900, int(qg.get("min") or 900)))
        if (q_on, q_min) != (QUOTA.get("on"), QUOTA.get("min")):
            log("⚙️ Ayar: " + (f"🛡️ istek hakkı koruması açık — kalan hak {q_min}'in altına inince yeni işler ve yüklemeler yenilenmeye kadar bekler"
                               if q_on else "istek hakkı koruması kapalı"))
            QUOTA.update(on=q_on, min=q_min)
        if delay != self.start_delay:
            log(f"⚙️ Ayar: işler gönderildikten {delay} dk sonra başlar")
            self.start_delay = delay
        if night != self.night:
            log("⚙️ Ayar: " + (f"🌙 gece modu açık — işler {night['start']}–{night['end']} arasında başlar" if night["on"] else "gece modu kapalı"))
            self.night = night
            self.night_marked.clear()
        if n != self.site_jobs:
            log(f"⚙️ Ayar: aynı siteden aynı anda {n} iş" + (" (site zorlandığı için şimdilik 1)" if time.time() < self.backoff_until else ""))
            self.site_jobs = n
        self.apply_site_jobs()

    def in_window(self, now: float | None = None) -> bool:
        """Gece modu kapalıysa her zaman; açıksa sadece seçilen saat aralığında (bilgisayarın saatiyle) yeni iş başlar."""
        if not self.night.get("on"):
            return True
        t = time.localtime(now or time.time())
        cur = t.tm_hour * 60 + t.tm_min
        s, e = _mins(self.night["start"]), _mins(self.night["end"])
        return s <= cur < e if s < e else (cur >= s or cur < e)    # 22:00–06:00 gibi gece yarısını aşan aralık da olur

    @staticmethod
    def urgent(r: dict) -> bool:
        return any(a.get("name") == "urgent" for a in r.get("assets") or [])

    def eligible(self, r: dict) -> bool:
        """Bekleyen iş şimdi başlayabilir mi: acil (⚡) her zaman; değilse başlama gecikmesi dolmuş ve gece modu aralığında."""
        if not (r.get("body") or "").startswith("⏳") or self.urgent(r):
            return True       # yarıda kalan iş ya da acil
        if self.start_delay:
            import calendar
            ts = r.get("published_at") or r.get("created_at") or ""
            try:
                pub = calendar.timegm(time.strptime(ts, "%Y-%m-%dT%H:%M:%SZ"))
            except ValueError:
                pub = 0
            if time.time() - pub < self.start_delay * 60:
                return False
        if not self.in_window():
            if r["tag_name"] not in self.night_marked:     # bekleyen işin kartına bir kez yaz
                self.night_marked.add(r["tag_name"])
                try:
                    self.gh.req("PATCH", f"/releases/{r['id']}", json={"body":
                        f"⏳ 🌙 Gece modu: {self.night['start']}'te başlayacak — acilse telefonda ⚡ Hemen başlat"})
                except Exception:  # noqa: BLE001
                    pass
            return False
        return True

    def cancel_all(self, before: float | None = None, rels: list[dict] | None = None) -> dict:
        """
        Telefondan "Tüm işleri iptal et": süren işler hemen durur (link_ingest.kill), sıradaki/yarım kalan işler
        "İptal edildi" diye kapatılır. `before` verilirse sadece o andan önce gönderilmiş işler (komuttan sonra
        gönderilen yeni iş iptal olmasın). Bilgisayar ardından yeni iş beklemeye hazırdır.
        """
        before = before or time.time()
        import calendar
        stopped = []
        for tag in list(self.active):
            li.kill(tag)
            stopped.append(tag)
        if rels is None:
            try:
                rels = self.gh.recent_releases(100)
            except Exception as e:  # noqa: BLE001
                log(f"🛑 İş listesi okunamadı: {e}")
                rels = []
        closed = []
        for r in rels:
            tag = r.get("tag_name") or ""
            if not tag.startswith(JOB_PREFIX) or r.get("draft") or tag in self.active:
                continue
            try:
                created = calendar.timegm(time.strptime(r.get("created_at") or "", "%Y-%m-%dT%H:%M:%SZ"))
            except ValueError:
                created = 0
            if created > before:
                continue
            body = r.get("body") or ""
            d = None
            if li.STATUS_MARK in body:
                try:
                    d = json.loads(body.split(li.STATUS_MARK, 1)[1].rsplit("-->", 1)[0])
                except ValueError:
                    d = None
            waiting = body.startswith("⏳") or body.startswith("⚙️") or body.startswith("Hazırlanıyor")
            if not waiting and not (d and d.get("state") == "running"):
                continue
            d = d or {"title": (r.get("name") or "").removeprefix("🌐 ").removeprefix("🔗 "), "items": [], "log": []}
            d.update(state="cancelled", stage="İptal edildi", updated=int(time.time()))
            d.setdefault("log", []).append("🛑 Telefondan: tüm işler iptal edildi")
            try:
                self.gh.req("PATCH", f"/releases/{r['id']}", json={"body":
                    f"🛑 Linkten ekleme — İptal edildi\n\n{li.STATUS_MARK}\n{json.dumps(d, ensure_ascii=False)}\n-->"})
                closed.append(tag)
            except Exception as e:  # noqa: BLE001
                log(f"🛑 {tag} kapatılamadı: {e}")
        self.night_marked.clear()
        log(f"🛑 Telefondan TÜM İŞLER İPTAL: {len(stopped)} süren iş durduruluyor, {len(closed)} bekleyen iş kapatıldı — yeni iş bekleniyor")
        return {"cancelled": len(stopped) + len(closed), "running": stopped, "queued": closed}

    def handle_commands(self, rels: list[dict]) -> None:
        """Telefonun açtığı komut Release'leri (pccmd-…): işle, sonra sil (sadece komut; iş/dosya değil)."""
        import calendar
        for r in rels:
            if not (r.get("tag_name") or "").startswith(CMD_PREFIX):
                continue
            cmd = (r.get("body") or "").strip().split()[0:1]
            if cmd == ["cancel_all"]:
                try:
                    at = calendar.timegm(time.strptime(r.get("created_at") or "", "%Y-%m-%dT%H:%M:%SZ"))
                except ValueError:
                    at = time.time()
                self.cancel_all(before=at + 2, rels=rels)
            try:
                self.gh.req("DELETE", f"/releases/{r['id']}")
                self.gh.req("DELETE", f"/git/refs/tags/{r['tag_name']}")
            except Exception:  # noqa: BLE001
                pass

    def heartbeat(self, force: bool = False) -> None:
        """Uygulama "Bilgisayar çevrimiçi" bilgisini buradan okur."""
        if not force and time.time() - self.last_beat < 60:
            return
        self.last_beat = time.time()
        self.read_config()
        try:
            if self.hb_rel is None:
                self.hb_rel = self.gh.ensure_release(HEARTBEAT_TAG, "G-Flix bilgisayar", "")
            free = 0
            try:
                self.workdir.mkdir(parents=True, exist_ok=True)
                import shutil
                free = shutil.disk_usage(self.workdir).free
            except OSError:
                pass
            info = {"host": self.host, "seen": int(time.time()), "busy": self.busy, "version": git_head(), "free": free,
                    "max_jobs": self.max_jobs(), "jobs": len(self.active),
                    "site_jobs": self.site_jobs, "site_jobs_now": self.site_jobs_now(), "backoff_until": int(self.backoff_until),
                    "start_delay_min": self.start_delay, "night": self.night, "in_window": self.in_window()}
            from gflix.github import QUOTA, quota_paused
            rem, reset = self.gh.quota()
            info["quota_guard"] = {"on": QUOTA.get("on"), "min": QUOTA.get("min"), "remaining": rem, "reset": reset,
                                   "paused": quota_paused(rem)}
            with self.stats_lock:
                info["stats"] = self.stats[-STATS_SHOWN:]
            if self.lan is not None:
                # Telefon aynı Wi-Fi'deyse dosyaları buraya doğrudan gönderir (GitHub'a uğramadan)
                info["lan"] = [f"{ip}:{self.lan.port}" for ip in lan_mod.local_ips()]
            body = json.dumps(info)
            self.gh.req("PATCH", f"/releases/{self.hb_rel['id']}", json={"body": body})
        except Exception as e:  # noqa: BLE001
            log(f"durum yazılamadı: {e}")
            self.hb_rel = None

    def publish_log_forever(self) -> None:
        """
        Telefondaki "Canlı Kayıt" paneli ev ağı dışındayken: panel açıkken telefon "pclog" Release'inin adına
        "watch:<zaman>" yazar; işçi bunu görünce 5 sn'de bir son 300 satırı gövdeye yazar. Kimse izlemiyorsa yazmaz
        (20 sn'de bir sadece bakar).
        """
        from gflix import livelog
        import re as _re
        rel = None
        watched_until = 0.0
        last_check = 0.0
        last_pushed = -1
        while not stop:
            try:
                now = time.time()
                if rel is None:
                    rel = self.gh.ensure_release(LOG_TAG, "G-Flix canlı kayıt", "")
                if now - last_check > (20 if now < watched_until else 60):   # kimse izlemiyorken dakikada bir bak
                    last_check = now
                    name = (self.gh.req("GET", f"/releases/{rel['id']}").json().get("name") or "")
                    m = _re.match(r"watch:(\d+)", name)
                    if m:
                        watched_until = max(watched_until, min(int(m.group(1)), now) + LOG_WATCH_WINDOW)
                if now < watched_until and livelog.LOG.seq != last_pushed:
                    d = livelog.LOG.since(0, limit=300)
                    d["at"] = int(now)
                    d["host"] = self.host
                    body = json.dumps(d, ensure_ascii=False)
                    while len(body) > 120_000 and d["lines"]:
                        d["lines"] = d["lines"][len(d["lines"]) // 4 + 1:]
                        body = json.dumps(d, ensure_ascii=False)
                    self.gh.req("PATCH", f"/releases/{rel['id']}", json={"body": body})
                    last_pushed = d["seq"]
            except Exception:  # noqa: BLE001  (hata yazdırılmaz: yazdırmak kayda satır ekleyip döngü yaratır)
                rel = None
                time.sleep(25)
            time.sleep(5)

    def beat_forever(self) -> None:
        while not stop:
            self.heartbeat()
            time.sleep(5)

    def queued(self) -> list[dict]:
        rels = self.gh.recent_releases(100)
        if any((r.get("tag_name") or "").startswith(CMD_PREFIX) for r in rels):
            self.handle_commands(rels)
            rels = self.gh.recent_releases(100)
        jobs = []
        for r in rels:
            if not r["tag_name"].startswith(JOB_PREFIX) or r.get("draft"):
                continue
            body = r.get("body") or ""
            if body.startswith("⏳"):
                jobs.append(r)
            elif li.STATUS_MARK in body:
                # Bu bilgisayar işlerken kapandıysa (elektrik, yeniden başlatma) işi baştan al
                try:
                    d = json.loads(body.split(li.STATUS_MARK, 1)[1].rsplit("-->", 1)[0])
                except ValueError:
                    continue
                if d.get("state") == "running" and d.get("worker") == self.host and not self.active and self.busy is None:
                    if any(a.get("name") == "cancel" for a in r.get("assets") or []):
                        # Yarıda kalan iş iptal edilmiş: yeniden başlatma, iptal edildi olarak kapat
                        d.update(state="cancelled", stage="İptal edildi", updated=int(time.time()))
                        d.setdefault("log", []).append("🛑 İptal edildi")
                        try:
                            self.gh.req("PATCH", f"/releases/{r['id']}", json={"body": f"🛑 Linkten ekleme — İptal edildi\n\n{li.STATUS_MARK}\n{json.dumps(d, ensure_ascii=False)}\n-->"})
                            log(f"🛑 İptal edilmiş iş kapatıldı: {r['tag_name']}")
                        except Exception as e:  # noqa: BLE001
                            log(f"iptal yazılamadı: {e}")
                        continue
                    jobs.append(r)
        return sorted(jobs, key=lambda r: r.get("created_at") or "")

    def record_stat(self, tag: str) -> None:
        rec = li.FINISHED.pop(tag, None)
        if not rec:
            return
        if rec.get("netTrouble") and self.site_jobs > 1 and time.time() >= self.backoff_until:
            self.backoff_until = time.time() + BACKOFF_SEC
            self.apply_site_jobs()
            log(f"⚠ Site zorlandı (bağlantı/429 hatasıyla düşen iş): 1 saat boyunca aynı anda 1 iş; sonra yine {self.site_jobs}")
        with self.stats_lock:
            self.stats = [s for s in self.stats if s.get("tag") != tag] + [rec]
            self.stats = self.stats[-STATS_KEPT:]
            try:
                self.workdir.mkdir(parents=True, exist_ok=True)
                tmp = self.stats_path.with_suffix(".tmp")
                tmp.write_text(json.dumps(self.stats, ensure_ascii=False), encoding="utf-8")
                tmp.replace(self.stats_path)
            except OSError as e:
                log(f"süre istatistiği yazılamadı: {e}")
        t = rec
        if t.get("start") and t.get("end"):
            log(f"⏱ {rec.get('title') or tag}: toplam {li.human_time(t['end'] - t['start'])}"
                + (f", indirme {li.human_time(t['dl_end'] - t['dl_start'])}" if t.get("dl_start") and t.get("dl_end") else ""))

    def _set_busy(self) -> None:
        tags = list(self.active)
        self.busy = (tags[0] if len(tags) == 1 else f"{len(tags)} iş: " + ", ".join(tags)) if tags else None

    def start_job(self, rel: dict) -> None:
        """İşi ayrı iş parçacığında başlat (aynı anda en fazla MAX_JOBS)."""
        tag = rel["tag_name"]
        t = threading.Thread(target=self.run_job, args=(rel,), name=tag, daemon=True)
        self.active[tag] = t
        self._set_busy()
        t.start()

    def run_job(self, rel: dict) -> None:
        tag = rel["tag_name"]
        self.active.setdefault(tag, threading.current_thread())
        self._set_busy()
        self.heartbeat(force=True)
        log("═" * 70)
        log(f"▶ İş başladı: {tag} ({rel.get('name')})")
        # Başka bir bilgisayar aynı anda almasın: hemen "çalışıyor" yaz
        try:
            self.gh.req("PATCH", f"/releases/{rel['id']}", json={"body": f"⚙️ {self.host} aldı, başlıyor…"})
        except Exception:  # noqa: BLE001
            pass
        try:
            state = li.run_tag(self.gh, tag, self.workdir / f"job-{tag}")   # her işin kendi klasörü
            log(f"■ İş bitti: {tag} → {state}")
            self.record_stat(tag)
            if self.lan is not None:   # telefondan gelen dosyalar silindiyse boş kalan klasörü kaldır
                d = self.lan.root / tag
                try:
                    if d.is_dir() and not any(p for p in d.iterdir() if not p.name.endswith(".parcalar.json")):
                        for p in d.iterdir():
                            p.unlink()
                        d.rmdir()
                except OSError:
                    pass
            log("═" * 70)
            self.last_idle_log = time.time()
        except Exception:  # noqa: BLE001
            traceback.print_exc()
        finally:
            self.active.pop(tag, None)
            self._set_busy()
            self.heartbeat(force=True)

    def loop(self) -> None:
        log(f"G-Flix bilgisayar işçisi çalışıyor: {self.host}, depo {os.environ['GFLIX_REPO']}, sürüm {git_head()}")
        log(f"İş klasörü: {self.workdir}")
        last_update = time.time()
        last_trailers = 0.0
        if self.lan is not None:
            try:
                self.lan.start()
                log(f"📶 Telefondan doğrudan alım açık: {', '.join(lan_mod.local_ips()) or '?'} port {self.lan.port} → {self.lan.root}")
            except OSError as e:
                log(f"⚠ Telefondan doğrudan alım açılamadı (port {self.lan.port}): {e} — dosyalar GitHub üzerinden gelir")
                self.lan = None
        if self.lan is not None:
            self.lan.on_command = lambda c: self.cancel_all() if c == "cancel_all" else {}
        threading.Thread(target=self.beat_forever, daemon=True).start()
        threading.Thread(target=self.publish_log_forever, daemon=True).start()
        while not stop:
            try:
                jobs = self.queued()
            except Exception as e:  # noqa: BLE001
                log(f"işler okunamadı: {e}")
                jobs = []
            for tag in [t for t, th in self.active.items() if not th.is_alive()]:
                self.active.pop(tag, None)
            jobs = [j for j in jobs if j["tag_name"] not in self.active]
            # Başlama gecikmesi / gece modu; acil (⚡) işler sıranın başına
            jobs = sorted((j for j in jobs if self.eligible(j)), key=lambda j: (not self.urgent(j), j.get("created_at") or ""))
            # İstek hakkı koruması: hak sınırın altındaysa yeni iş başlatma (TV'ye pay kalsın); yarım kalan işler hariç
            if jobs:
                from gflix.github import QUOTA, quota_paused
                rem, reset = self.gh.quota()
                if quota_paused(rem):
                    held = [j for j in jobs if (j.get("body") or "").startswith("⏳")]
                    if held and not self.quota_paused_logged:
                        self.quota_paused_logged = True
                        log(f"⏸ GitHub istek hakkı {rem} (sınır {QUOTA.get('min')}): {len(held)} yeni iş "
                            f"{time.strftime('%H:%M', time.localtime(reset))}'e (yenilenme) ertelendi — TV etkilenmez")
                    jobs = [j for j in jobs if j not in held]
                elif self.quota_paused_logged:
                    self.quota_paused_logged = False
                    log(f"▶ GitHub istek hakkı yenilendi ({rem}): bekleyen işler başlıyor")
            # Önceki iş hâlâ siteden indiriyorsa yeni işe başlama (sadece yükleme/birleştirme sürerken başlar)
            if jobs and len(self.active) < self.max_jobs() and (not self.active or not li.site_busy()):
                import shutil as _sh
                try:
                    free_gb = _sh.disk_usage(self.workdir).free / 1e9
                except OSError:
                    free_gb = 0
                if self.active and free_gb < MIN_FREE_GB:
                    pass    # yer azaldı: ikinci işe başlama, öncekiler bitsin
                else:
                    if not self.active and self_update():      # sürüm sadece hiç iş yokken güncellenir
                        log("Yeni sürüm indirildi, yeniden başlatılıyor…")
                        os.execv(sys.executable, [sys.executable] + sys.argv)
                    if self.active:
                        log("⏩ Önceki işin indirmesi bitti, o yüklenirken sıradaki iş başlıyor")
                    self.start_job(jobs[0])
                    time.sleep(3)
                    continue
            if self.active:
                was_busy = li.site_busy()
                for _ in range(POLL):
                    if stop:
                        break
                    if was_busy and not li.site_busy() and len(self.active) < self.max_jobs():
                        break       # önceki işin indirmesi şimdi bitti: sıradaki işi beklemeden kontrol et
                    time.sleep(1)
                continue
            if time.time() - self.last_idle_log > 300:
                self.last_idle_log = time.time()
                import shutil as _sh
                try:
                    free = _sh.disk_usage(self.workdir).free / 1e9
                except OSError:
                    free = 0
                log(f"💤 Boşta — iş bekleniyor ({free:.0f} GB boş" + (f", telefondan alım açık: {', '.join(lan_mod.local_ips())}" if self.lan else "") + ")")
            # Boştayken: fragmanı olmayan yapımlara otomatik fragman (yarım saatte bir, en fazla 3 yapım; yeni iş gelirse durur).
            # Tema müziği bulma kaldırıldı (kullanıcı kararı: GitHub istek hakkını boşuna harcıyordu).
            if os.environ.get("GFLIX_AUTO_TRAILERS", "1") != "0" and time.time() - last_trailers > 1800:
                last_trailers = time.time()
                try:
                    self.busy = "otomatik fragmanlar"
                    n = autotrailers_mod.run_pending(self.gh, self.workdir / "fragmanlar", log=log, limit=3, stop=lambda: stop)
                    if n:
                        log(f"🎬 {n} yapımın fragmanı otomatik işlendi")
                except Exception as e:  # noqa: BLE001
                    log(f"otomatik fragmanlar: {e}")
                finally:
                    self.busy = None
            if time.time() - last_update > 3600:
                last_update = time.time()
                if self_update():
                    log("Yeni sürüm indirildi, yeniden başlatılıyor…")
                    os.execv(sys.executable, [sys.executable] + sys.argv)
            for _ in range(POLL):
                if stop:
                    break
                time.sleep(1)


def check_tools() -> list[str]:
    import shutil
    missing = [t for t in ("aria2c", "mkvmerge", "ffmpeg", "ffprobe", "yt-dlp") if not shutil.which(t)]
    return missing


def main() -> int:
    global stop
    from gflix import livelog
    livelog.install()      # ekrana yazılan her şeyin kopyası: telefondaki Canlı Kayıt paneli

    def on_signal(*_):
        global stop
        stop = True
        log("Durduruluyor (iş varsa bitince)…")
    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)
    for k in ("GITHUB_TOKEN", "GFLIX_REPO"):
        if not os.environ.get(k):
            log(f"❌ {k} ayarlanmamış (~/.config/gflix/worker.env)")
            return 2
    missing = check_tools()
    if missing:
        log(f"❌ Eksik araçlar: {', '.join(missing)} — linux/install.sh'i çalıştır")
        return 2
    if "--check" in sys.argv:
        w = Worker()
        info = w.gh.repo_info()
        log(f"✓ Depoya erişim var: {info['full_name']} (yazma: {info.get('permissions', {}).get('push')})")
        w.heartbeat(force=True)
        log("✓ Uygulamada 'Bilgisayar çevrimiçi' görünmeli")
        return 0
    Worker().loop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
