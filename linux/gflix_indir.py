#!/usr/bin/env python3
"""
gflix-indir: linkleri bu bilgisayara 1DM gibi indirir (aria2, dosya başına 16 bağlantı).
Site izin vermezse tarayıcının (Firefox/Chrome…) o siteye ait çerezleriyle tekrar dener. .txt gibi yanlış
uzantıları içerikten tanıyıp düzeltir.

  gflix-indir LINK [LINK …]            ./ klasörüne indirir
  gflix-indir -o ~/Filmler LINK        başka klasöre
  gflix-indir --curl                   tarayıcıdan "cURL olarak kopyala" ile alınan komutu yapıştır (Enter, sonra Ctrl+D)
  gflix-indir -b firefox LINK          çerezleri sadece Firefox'tan al (varsayılan: hepsini dene)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from gflix.fetch import Downloader, fetch_link, parse_curl  # noqa: E402


def human(n: float) -> str:
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return f"{n:.1f} {u}"
        n /= 1024
    return f"{n:.1f} GB"


def main() -> int:
    ap = argparse.ArgumentParser(description="Linkleri 1DM gibi indirir.")
    ap.add_argument("links", nargs="*")
    ap.add_argument("-o", "--out", default=".", help="indirme klasörü")
    ap.add_argument("-b", "--browser", default="auto", help="çerezler: auto | firefox | chrome | chromium | brave | off")
    ap.add_argument("--curl", action="store_true", help="cURL komutunu standart girdiden oku")
    a = ap.parse_args()
    jobs = [(u, {}) for u in a.links]
    if a.curl:
        print("cURL komutunu yapıştır, sonra Enter ve Ctrl+D:", file=sys.stderr)
        jobs.append(parse_curl(sys.stdin.read()))
    if not jobs:
        ap.print_help()
        return 1
    out = Path(a.out).expanduser()
    dl = Downloader(log=print)
    ok = 0
    try:
        for n, (url, headers) in enumerate(jobs, 1):
            name = Path(url.split("?")[0]).stem or f"dosya{n}"

            def prog(d):
                tot = f" / {human(d.total)}" if d.total else ""
                print(f"\r  {name}: {human(d.done)}{tot}  {human(d.speed)}/sn      ", end="", flush=True)
            try:
                p = fetch_link(dl, url, out, name, label=name, headers=headers, browser=a.browser, on_progress=prog)
                print(f"\n✓ {p}")
                ok += 1
            except Exception as e:  # noqa: BLE001
                print(f"\n✗ {url}\n  {e}")
    finally:
        dl.close()
    return 0 if ok == len(jobs) else 2


if __name__ == "__main__":
    sys.exit(main())
