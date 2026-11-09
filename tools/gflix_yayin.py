#!/usr/bin/env python3
"""
G-Flix yayın sunucusu (Linux bilgisayar "Hasan"): linkten anlık yayın, GitHub'sız.

  gflix_yayin.py                       sunucuyu başlat (systemd servisi: gflix-yayin)
  gflix_yayin.py anahtar               bağlanma anahtarını, adresleri ve QR'ı göster
  gflix_yayin.py kaynak <id> <sayfa>                     film: sitedeki sayfası
  gflix_yayin.py kaynak <id> <sayfa> --sezon 1 --bolum 2 dizi bölümü
  gflix_yayin.py kaynak <id> --kalip 'https://…/sezon-{s}/bolum-{e}'   dizinin bütün bölümleri ({s2} {e2}: 01, 02)
  gflix_yayin.py eslestir <sayfa> ['sayfa başlığı']      yapımı başlıktan/adresten bul ve kaydet
  gflix_yayin.py kaynaklar             kayıtlı sayfaları listele
  gflix_yayin.py unut <id> [--sezon S --bolum E | --kalip]   bir kaynağı unut (sadece adres; video yok)
  gflix_yayin.py dene <id> [--sezon S --bolum E]         çözmeyi dene (oynatmadan), süreyi ölç
  gflix_yayin.py fragman <id> <YouTube linki> [--dil tr]   fragman (yoksa TMDB'deki YouTube fragmanı)
  gflix_yayin.py katalog               kataloğu şimdi üret (TMDB)
  gflix_yayin.py ice-aktar <klasör>    eski depodaki catalog/items kayıtlarını bilgisayara al (var olanın üstüne yazmaz)

Ayarlar ~/.config/gflix/yayin.env: GFLIX_WORKDIR, TMDB_API_KEY, GFLIX_YAYIN_PORT (8766).
Yapım kayıtları <GFLIX_WORKDIR>/katalog/items, üretilen katalog <GFLIX_WORKDIR>/katalog/catalog.json: yapım adları
sadece bu bilgisayarda durur, GitHub'a gitmez.
Anahtar ~/.config/gflix/yayin.key (ilk açılışta üretilir). Sayfa adresleri <GFLIX_WORKDIR>/kaynaklar.json (GitHub'a gitmez).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from gflix import livelog, yayin  # noqa: E402

CONF_DIR = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config") / "gflix"


def read_env(path: Path) -> dict:
    out = {}
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                out[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    return out


def settings() -> dict:
    env = {**read_env(CONF_DIR / "worker.env"), **read_env(CONF_DIR / "yayin.env"), **os.environ}
    # eski işçinin GitHub token'ı burada kullanılmaz (yeni düzende GitHub'a hiçbir şey gitmez)
    workdir = Path(env.get("GFLIX_WORKDIR") or Path.home() / "gflix-work").expanduser()
    return {
        "workdir": workdir,
        "tmdb": env.get("TMDB_API_KEY", "").strip(),
        "port": int(env.get("GFLIX_YAYIN_PORT") or yayin.DEFAULT_PORT),
        "catalog": Path(env.get("GFLIX_CATALOG") or workdir / "katalog" / "catalog.json").expanduser(),
        "items": workdir / "katalog" / "items",
        "name": env.get("GFLIX_WORKER_NAME") or "",
        "keyfile": CONF_DIR / "yayin.key",
        # Telefon güncellemesi: bilgisayar yeni APK'yı GitHub Release'inden çeker (sadece okuma yetkili anahtar)
        "gh_token": env.get("GFLIX_GITHUB_TOKEN", "").strip(),
        "gh_repo": env.get("GFLIX_REPO", "").strip(),
        "archive": {
            "enabled": env.get("GFLIX_ARCHIVE_ENABLED", "0").strip() == "1"
                       and env.get("GFLIX_ARCHIVE_RIGHTS_CONFIRMED", "0").strip() == "1",
            "access": env.get("GFLIX_ARCHIVE_ACCESS_KEY", "").strip(),
            "secret": env.get("GFLIX_ARCHIVE_SECRET_KEY", "").strip(),
            "prefix": env.get("GFLIX_ARCHIVE_PREFIX", "").strip(),
        },
    }


APP_TAG = re.compile(r"^app-v2\.0\.(\d+)$")
APP_CODE_BASE = 10000     # android.yml ile aynı: versionCode = 10000 + derleme no


def app_sync(st: dict, srv) -> None:
    """GitHub'daki en yeni 'app-v2.0.N' APK'sı bilgisayardakinden yeniyse indir; telefon güncellemeyi bilgisayardan alır."""
    if not st["gh_token"] or not st["gh_repo"]:
        return
    import requests
    h = {"Authorization": f"Bearer {st['gh_token']}", "Accept": "application/vnd.github+json"}
    try:
        # GitHub /releases listesi tarihe göre değil etiket adına göre sıralı gelir: bütün sayfalar okunur
        # (yoksa Stüdyo/TV derlemeleri çoğalınca yeni telefon sürümü ilk sayfanın dışında kalıyordu)
        best = None
        for page in range(1, 11):
            r = requests.get(f"https://api.github.com/repos/{st['gh_repo']}/releases", params={"per_page": 100, "page": page},
                             headers=h, timeout=30)
            r.raise_for_status()
            rels = r.json()
            for rel in rels:
                m = APP_TAG.match(rel.get("tag_name") or "")
                apk = next((a for a in rel.get("assets") or [] if a["name"].endswith(".apk")), None)
                if m and apk and not rel.get("draft") and (best is None or int(m.group(1)) > best[0]):
                    best = (int(m.group(1)), apk)
            if len(rels) < 100:
                break
        if not best:
            return
        code = APP_CODE_BASE + best[0]
        cur = srv.app_info()
        if cur and cur["code"] >= code:
            return
        d = srv.app_dir()
        d.mkdir(parents=True, exist_ok=True)
        name = f"app-{code}.apk"
        with requests.get(best[1]["url"], headers={**h, "Accept": "application/octet-stream"}, stream=True, timeout=(30, 300)) as dl:
            dl.raise_for_status()
            with open(d / (name + ".part"), "wb") as f:
                for chunk in dl.iter_content(1 << 20):
                    f.write(chunk)
        (d / (name + ".part")).replace(d / name)
        (d / "app.json").write_text(json.dumps({"code": code, "name": f"2.0.{best[0]}", "file": name}))
        for old in d.glob("app-*.apk"):
            if old.name != name:
                old.unlink(missing_ok=True)
        print(f"📱 telefon güncellemesi hazır: 2.0.{best[0]}")
    except Exception as e:  # noqa: BLE001
        print(f"  (uygulama güncellemesi kontrol edilemedi: {str(e)[:150]})")


def show_key(st: dict) -> None:
    key = yayin.load_key(st["keyfile"])
    ips = yayin.local_ips()
    ts = tailscale_ip()
    print(f"\n  🔑 Anahtar: {key}")
    for ip in ips:
        print(f"  🏠 Ev ağı adresi: {ip}:{st['port']}")
    if ts:
        print(f"  🌍 Tailscale adresi (dışarıdayken): {ts}:{st['port']}")
    host = (ips or ["?"])[0]
    link = f"gflix://pc?h={host}:{st['port']}" + (f"&t={ts}:{st['port']}" if ts else "") + f"&k={key}"
    print(f"  📱 Telefon için bağlantı: {link}")
    if shutil.which("qrencode"):
        print()
        subprocess.run(["qrencode", "-t", "ansiutf8", link])
    else:
        print("  (QR için: sudo apt install qrencode)")
    print()


def tailscale_ip() -> str:
    if not shutil.which("tailscale"):
        return ""
    try:
        r = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=5)
        return (r.stdout.split() or [""])[0]
    except Exception:  # noqa: BLE001
        return ""


def serve(st: dict) -> None:
    livelog.install()
    key = yayin.load_key(st["keyfile"])
    st["workdir"].mkdir(parents=True, exist_ok=True)
    if not st["items"].is_dir() and not st["catalog"].exists():
        print(f"✗ Yapım kayıtları yok: {st['items']}  →  gflix-yayin ice-aktar <eski depo>/catalog/items")
        sys.exit(1)
    if not st["tmdb"]:
        print("⚠ TMDB_API_KEY yok (~/.config/gflix/yayin.env): reklam/asıl video ayrımı süreye göre yapılamaz, en uzun yayın seçilir.")
    srv = yayin.Server(st["workdir"], key, st["catalog"], st["tmdb"], st["port"], st["name"], items_dir=st["items"], archive_cfg=st["archive"])
    if not st["catalog"].exists():
        srv.rebuild(wait=True)
    srv.start()
    print(f"G-Flix yayın sunucusu hazır · port {st['port']} · kaynaklar: {srv.sources.path}")
    for ip in yayin.local_ips():
        print(f"  ev ağı: http://{ip}:{st['port']}")
    ts = tailscale_ip()
    if ts:
        print(f"  Tailscale: http://{ts}:{st['port']}")
    print("  İstek listesi: archive.org otomatik indirme " + ("AÇIK" if srv.archive.enabled else "KAPALI (yayin.env ayarlarını kontrol et)"))
    stop = {"now": False}
    signal.signal(signal.SIGTERM, lambda *_: stop.update(now=True))
    try:
        last_pull = last_check = last_app = last_flush = 0.0
        pending = False

        def trailer_checker():
            # Seçilen YouTube fragmanlarının başlığını yapımın adıyla karşılaştırır; tutmayan atlanır (yavaş, tek tek)
            time.sleep(60)
            while True:
                try:
                    did = srv.check_next_trailer()
                except Exception as e:  # noqa: BLE001
                    print(f"  (fragman kontrolü: {str(e)[:150]})")
                    did = False
                time.sleep(8 if did else 600)
        threading.Thread(target=trailer_checker, daemon=True).start()

        def link_checker():
            # Ölü link kontrolü: her kayıt günde bir, 30 sn arayla tek tek (siteye yük olmasın)
            time.sleep(120)
            while True:
                try:
                    did = srv.check_next_link()
                except Exception as e:  # noqa: BLE001
                    print(f"  (link kontrolü: {str(e)[:150]})")
                    did = False
                time.sleep(30 if did else 900)
        threading.Thread(target=link_checker, daemon=True).start()

        def size_measurer():
            # İçeriğin kesin boyutu: kimse izlemiyorken yayındaki yapımlar tek tek ölçülür (yeniler de bir kez)
            time.sleep(180)
            while True:
                try:
                    did = srv.measure_next()
                except Exception as e:  # noqa: BLE001
                    print(f"  (boyut ölçümü: {str(e)[:150]})")
                    did = False
                time.sleep(20 if did else 300)
        threading.Thread(target=size_measurer, daemon=True).start()

        def follower():
            # YouTube takibi: dizilerin kanallarına/listelerine yeni tam bölüm geldi mi (her takip 30 dk'da bir)
            time.sleep(240)
            while True:
                try:
                    did = srv.follow_next()
                except Exception as e:  # noqa: BLE001
                    print(f"  (takip: {str(e)[:150]})")
                    did = False
                time.sleep(20 if did else 120)
        threading.Thread(target=follower, daemon=True).start()

        def transcoder():
            # 🎞 indirme için küçültme (aynı anda tek)
            for f in srv.tc_dir.glob("*.mp4*") if srv.tc_dir.is_dir() else []:
                f.unlink(missing_ok=True)                  # yeniden başlayınca yarım/eski dosyalar silinir
            while True:
                try:
                    did = srv.tc_next()
                except Exception as e:  # noqa: BLE001
                    print(f"  (küçültme: {str(e)[:150]})")
                    did = False
                time.sleep(1 if did else 3)
        threading.Thread(target=transcoder, daemon=True).start()

        def prefetcher():
            # 🌙 gece önceden yenileme: izlenmesi muhtemel yapımların ölü linkleri (aralıkta, tek tek, gecede sınırlı)
            time.sleep(120)
            while True:
                try:
                    did = srv.prefetch_step()
                except Exception as e:  # noqa: BLE001
                    print(f"  (önceden yenileme: {str(e)[:150]})")
                    did = None
                time.sleep(30 if did is None else 60)
        threading.Thread(target=prefetcher, daemon=True).start()

        def pc_capturer():
            # İstek listesinin bilgisayara verdiği yakalama işleri (görünür tarayıcı penceresiyle)
            while True:
                try:
                    # önce tazeleme (biri izlemeye çalışıyor), sonra İstek listesinin verdiği işler
                    did = srv.pc_refresh_next() or srv.pccap_next()
                except Exception as e:  # noqa: BLE001
                    print(f"  (bilgisayarda yakalama: {str(e)[:150]})")
                    did = False
                time.sleep(3)
        threading.Thread(target=pc_capturer, daemon=True).start()
        while not stop["now"]:
            time.sleep(1)
            if time.time() - last_flush > 60:
                last_flush = time.time()
                srv.usage.flush()
                srv.playlog.flush()
                srv.prefetch_cfg.flush()
                srv.tchecks.flush()
                srv.links.flush()
                srv.sizes.flush()
            if time.time() - last_check > 600:      # yeni kayıt ya da günlük TMDB yenilemesi (İlk 10, yeni bölümler)
                last_check = time.time()
                if srv.needs_build():
                    srv.rebuild()
            if time.time() - last_app > 900:         # telefon güncellemesi: 15 dk'da bir
                last_app = time.time()
                threading.Thread(target=app_sync, args=(st, srv), daemon=True).start()
            if time.time() - last_pull > 900:        # kod güncellemesi: 15 dk'da bir
                last_pull = time.time()
                pending = git_pull() or pending
                if pending:
                    print("⬆ yeni sürüm indirildi; kimse izlemiyorken servis kendini yeniden başlatacak")
            if pending and idle(srv):
                # İzleme yarıda kesilmesin: sadece boştayken. systemd (Restart=always) yeni kodla hemen yeniden açar.
                print("⬆ yeni sürüme geçiliyor (servis yeniden başlıyor)")
                break
    except KeyboardInterrupt:
        pass
    srv.usage.flush()
    srv.playlog.flush()
    srv.prefetch_cfg.flush()
    srv.tchecks.flush()
    srv.links.flush()
    srv.sizes.flush()
    srv.stop()


def git_head() -> str:
    try:
        return subprocess.run(["git", "-C", str(HERE.parent), "rev-parse", "HEAD"], capture_output=True, text=True, timeout=20).stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def git_pull() -> bool:
    """Depodan yeni kodu çek. Kod değiştiyse True (servis boşta kalınca kendini yeniden başlatır)."""
    root = HERE.parent
    if not (root / ".git").exists() or not shutil.which("git"):
        return False
    before = git_head()
    try:
        r = subprocess.run(["git", "-C", str(root), "pull", "--ff-only", "-q"], capture_output=True, text=True, timeout=120)
        if r.returncode != 0 and r.stderr.strip():
            print(f"  (git pull: {r.stderr.strip()[:200]})")
    except Exception:  # noqa: BLE001
        return False
    after = git_head()
    return bool(before and after and before != after)


def idle(srv) -> bool:
    """Kimse izlemiyor mu (son 3 dk'da parça/liste istenmedi, link çözülmüyor)?"""
    now = time.time()
    with srv.slock:
        busy = any(now - s.used < 180 for s in srv.sessions.values())
    with srv.yt_lock:
        busy = busy or any(now - m.used < 180 for m in srv.muxes.values())
    return not busy and not srv.site_slot.locked()


def main() -> None:
    ap = argparse.ArgumentParser(description="G-Flix yayın sunucusu")
    sub = ap.add_subparsers(dest="cmd")
    sub.add_parser("anahtar")
    k = sub.add_parser("kaynak")
    k.add_argument("id")
    k.add_argument("url", nargs="?")
    k.add_argument("--sezon", type=int)
    k.add_argument("--bolum", type=int)
    k.add_argument("--kalip")
    e = sub.add_parser("eslestir")
    e.add_argument("url")
    e.add_argument("baslik", nargs="?", default="")
    sub.add_parser("kaynaklar")
    u = sub.add_parser("unut")
    u.add_argument("id")
    u.add_argument("--sezon", type=int)
    u.add_argument("--bolum", type=int)
    u.add_argument("--kalip", action="store_true")
    fr = sub.add_parser("fragman")
    fr.add_argument("id")
    fr.add_argument("youtube")
    fr.add_argument("--dil", default="tr")
    sub.add_parser("katalog")
    ia = sub.add_parser("ice-aktar")
    ia.add_argument("klasor")
    d = sub.add_parser("dene")
    d.add_argument("id")
    d.add_argument("--sezon", type=int)
    d.add_argument("--bolum", type=int)
    a = ap.parse_args()
    st = settings()
    src = yayin.Sources(st["workdir"] / "kaynaklar.json")

    if a.cmd is None:
        return serve(st)
    if a.cmd == "anahtar":
        return show_key(st)
    if a.cmd == "kaynak":
        if not a.url and not a.kalip:
            sys.exit("sayfa adresi ya da --kalip gerekli")
        if (a.sezon is None) != (a.bolum is None):
            sys.exit("--sezon ve --bolum birlikte verilir")
        src.set(a.id, a.url, a.sezon, a.bolum, a.kalip)
        print("✓ kaydedildi")
        return
    if a.cmd == "eslestir":
        srv = yayin.Server(st["workdir"], "x", st["catalog"], st["tmdb"], items_dir=st["items"])
        item_id, s, ep = yayin.match_page(srv, a.url, a.baslik)
        src.set(item_id, a.url, s, ep)
        print(f"✓ {item_id}" + (f" S{s:02d}E{ep:02d}" if s is not None else "") + " kaydedildi")
        return
    if a.cmd == "kaynaklar":
        items = src.all()
        if not items:
            print("Henüz kayıtlı sayfa yok.")
        for item_id, e in sorted(items.items()):
            parts = []
            if e.get("url"):
                parts.append("film sayfası")
            if e.get("episodes"):
                parts.append(f"{len(e['episodes'])} bölüm")
            if e.get("pattern"):
                parts.append("bölüm kalıbı")
            print(f"  {item_id}: {', '.join(parts)}")
        return
    if a.cmd == "unut":
        ok = src.remove(a.id, a.sezon, a.bolum, a.kalip)
        print("✓ unutuldu" if ok else "kayıt yoktu")
        return
    if a.cmd == "fragman":
        src.set_trailer(a.id, a.youtube, a.dil)
        print("✓ fragman kaydedildi (YouTube'dan anlık yayınlanır)")
        return
    if a.cmd == "katalog":
        srv = yayin.Server(st["workdir"], "x", st["catalog"], st["tmdb"], items_dir=st["items"])
        srv.rebuild(wait=True)
        return
    if a.cmd == "ice-aktar":
        src_dir = Path(a.klasor).expanduser()
        if (src_dir / "catalog" / "items").is_dir():
            src_dir = src_dir / "catalog" / "items"
        files = sorted(src_dir.glob("*.json"))
        if not files:
            sys.exit(f"{src_dir} içinde kayıt (*.json) yok")
        st["items"].mkdir(parents=True, exist_ok=True)
        new = 0
        for f in files:
            if not re.fullmatch(r"(movie|series)-[a-z0-9-]+\.json", f.name):
                continue
            dst = st["items"] / f.name
            if not dst.exists():
                shutil.copy2(f, dst)
                new += 1
        cat = src_dir.parent / "catalog.json"
        if cat.is_file() and not st["catalog"].exists():
            shutil.copy2(cat, st["catalog"])        # TMDB bilgili hazır katalog: anahtar olmasa da hemen kullanılır
            print(f"✓ hazır katalog da alındı → {st['catalog']}")
        print(f"✓ {new} kayıt alındı ({len(files) - new} zaten vardı) → {st['items']}")
        print("  Katalog servis açıkken 10 dk içinde kendiliğinden üretilir; hemen istersen: gflix-yayin katalog")
        return
    if a.cmd == "dene":
        srv = yayin.Server(st["workdir"], "x", st["catalog"], st["tmdb"], items_dir=st["items"])
        t0 = time.time()
        s = srv.play(a.id, a.sezon, a.bolum, fresh=True)
        r = s.res
        print(json.dumps({"sure_sn": round(time.time() - t0, 1), "yukseklik": r.height, "dakika": round(r.duration / 60),
                          "sesler": [x for x, _ in r.audios], "altyazilar": [x["lang"] for x in r.subs]}, ensure_ascii=False))
        return


if __name__ == "__main__":
    main()
