"""TMDB (The Movie Database) istemcisi."""
from __future__ import annotations

import time

import requests

IMG = "https://image.tmdb.org/t/p/"


def img(path: str | None, size: str = "w500") -> str | None:
    return f"{IMG}{size}{path}" if path else None


class TMDB:
    def __init__(self, key: str):
        key = key.strip()
        self.s = requests.Session()
        self.params: dict = {}
        if key.startswith("eyJ"):
            self.s.headers["Authorization"] = f"Bearer {key}"
        else:
            self.params["api_key"] = key

    def get(self, path: str, **params):
        for attempt in range(5):
            r = self.s.get(f"https://api.themoviedb.org/3{path}", params={**self.params, **params}, timeout=30)
            if r.status_code == 429:
                time.sleep(2 + attempt * 2)
                continue
            if r.status_code == 404:
                return None
            if r.status_code == 401:
                raise RuntimeError("TMDB anahtarı geçersiz.")
            r.raise_for_status()
            return r.json()
        raise RuntimeError(f"TMDB isteği başarısız: {path}")

    def search(self, kind: str, query: str, year: int | None = None, language: str = "tr-TR") -> list[dict]:
        """kind: 'movie' | 'tv'. Sonuçlar: id, title, original_title, year, poster, overview"""
        params = {"query": query, "language": language}
        if year:
            params["first_air_date_year" if kind == "tv" else "year"] = year
        res = (self.get(f"/search/{kind}", **params) or {}).get("results") or []
        if not res and year:
            return self.search(kind, query, None, language)
        out = []
        for r in res[:20]:
            date = r.get("release_date") or r.get("first_air_date") or ""
            out.append({
                "id": r["id"],
                "title": r.get("title") or r.get("name") or "",
                "original_title": r.get("original_title") or r.get("original_name") or "",
                "year": int(date[:4]) if date[:4].isdigit() else None,
                "poster": img(r.get("poster_path"), "w185"),
                "overview": r.get("overview") or "",
            })
        return out

    def images(self, kind: str, tmdb_id: int) -> dict:
        """Tüm posterler, arka planlar ve logolar (tr, en, yazısız ve diğer diller)."""
        return self.get(f"/{kind}/{tmdb_id}/images") or {}
