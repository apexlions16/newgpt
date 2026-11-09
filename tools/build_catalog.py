#!/usr/bin/env python3
"""
G-Flix katalog oluşturucu: catalog/items/*.json + TMDB (Türkçe) -> catalog/catalog.json

GitHub Actions bunu otomatik çalıştırır. Elle:  TMDB_API_KEY=xxxx python tools/build_catalog.py
TMDB_API_KEY: v3 "API Anahtarı" ya da v4 "API Okuma Erişim Jetonu" (eyJ...).
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gflix.catalog import build  # noqa: E402

if __name__ == "__main__":
    key = os.environ.get("TMDB_API_KEY", "").strip()
    if not key and os.environ.get("GITHUB_ACTIONS"):
        # GitHub'da TMDB anahtarı (secret) yoksa katalog TMDB bilgileri olmadan üretilip mevcut iyi kataloğun üzerine
        # yazılmasın: hiçbir şeye dokunmadan çık. (Settings → Secrets and variables → Actions → TMDB_API_KEY)
        print("! TMDB_API_KEY secret'ı yok: katalog değiştirilmedi.")
        sys.exit(0)
    build(Path(__file__).resolve().parent.parent, key)
