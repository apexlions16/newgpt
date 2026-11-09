"""catalog/items/*.json + TMDB -> catalog/catalog.json

Her yapım için:
  - names:     {"tr": ..., "en": ..., "original": ...}  (profil tercihine göre gösterilir, aramada hepsi geçerli)
  - posters / backdrops / logos: [{url, lang, pinned?}]  (uygulama profile göre / güne göre seçer)
  - trailer:   kullanıcının yüklediği fragman (HLS)
Kayıttaki "art" alanı kapakları sabitlemek/gizlemek, "overrides" alanı bilgileri elle değiştirmek içindir.
"""
from __future__ import annotations

import json
import os
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from .tmdb import TMDB, img

LANG = "tr-TR"
LIMITS = {
    "posters": {"tr": 6, "en": 6, None: 4, "*": 2},
    "backdrops": {None: 8, "tr": 3, "en": 3, "*": 0},
    "logos": {"tr": 3, "en": 3, "*": 2},
}
SIZES = {"posters": "w500", "backdrops": "w1280", "logos": "w500"}

CERT_MAP = {
    "G": 0, "TV-Y": 0, "TV-G": 0, "PG": 7, "TV-Y7": 7, "TV-PG": 7, "PG-13": 13, "TV-14": 13,
    "R": 18, "NC-17": 18, "TV-MA": 18,
    "GENEL İZLEYICI": 0, "GENEL IZLEYICI": 0, "GENEL İZLEYİCİ": 0, "6A": 7, "A": 0,
}


def maturity(cert: str | None) -> tuple[int | None, str | None]:
    if not cert:
        return None, None
    c = cert.strip().upper()
    if c in CERT_MAP:
        age = CERT_MAP[c]
    else:
        digits = "".join(ch for ch in c if ch.isdigit())
        if not digits:
            return None, None
        age = int(digits)
        age = 7 if age <= 7 else 13 if age <= 13 else 16 if age <= 16 else 18
    return age, ("Genel İzleyici" if age == 0 else f"{age}+")


def _cert(d: dict, tv: bool) -> str | None:
    if tv:
        for country in ("TR", "US"):
            for r in (d.get("content_ratings") or {}).get("results", []):
                if r.get("iso_3166_1") == country and r.get("rating"):
                    return r["rating"]
        return None
    for country in ("TR", "US"):
        for r in (d.get("release_dates") or {}).get("results", []):
            if r.get("iso_3166_1") == country:
                for rd in r.get("release_dates", []):
                    if rd.get("certification"):
                        return rd["certification"]
    return None


def _year(date: str | None) -> int | None:
    return int(date[:4]) if date and date[:4].isdigit() else None


def select_art(images: list[dict], kind: str, prefs: dict | None, original_lang: str | None) -> list[dict]:
    """TMDB görsellerinden dil gruplarına göre en iyileri seçer, kullanıcı tercihlerini uygular."""
    prefs = prefs or {}
    hidden = set(prefs.get("hidden") or [])
    pin = prefs.get("pin")
    limits = dict(LIMITS[kind])
    if original_lang and original_lang not in ("tr", "en"):
        limits[original_lang] = 2
    imgs = [i for i in images if i.get("file_path") and i["file_path"] not in hidden]
    imgs.sort(key=lambda i: (i.get("vote_average") or 0, i.get("vote_count") or 0, i.get("width") or 0), reverse=True)
    out, counts = [], {}
    for i in imgs:
        lang = i.get("iso_639_1")
        if i["file_path"] == pin:
            continue
        key = lang if lang in limits else "*"
        if counts.get(key, 0) >= limits.get(key, 0):
            continue
        counts[key] = counts.get(key, 0) + 1
        out.append({"url": img(i["file_path"], SIZES[kind]), "lang": lang})
    if pin:
        pinned = next((i for i in images if i.get("file_path") == pin), None)
        out.insert(0, {"url": img(pin, SIZES[kind]), "lang": pinned.get("iso_639_1") if pinned else None, "pinned": True})
    return out


def _first(art: list[dict], langs) -> str | None:
    for a in art:
        if a.get("pinned"):
            return a["url"]
    for lang in langs:
        for a in art:
            if a.get("lang") == lang:
                return a["url"]
    return art[0]["url"] if art else None


# ---------------------------------------------------------------- linkten anlık yayın (3 Kasım 2026)
# Eski hesabın Release'lerindeki videolar artık yok: kayıtlardaki stream.release / versions[].video.parts / trailer
# dosya adresleri ölü. Uygulamalara "oynatılabilir" diye gönderilmez. Bir film/bölüm ancak kaynağı (sitedeki sayfası)
# bilindiğinde oynatılır; kaynak adresleri gizli kalsın diye kataloğa hiç yazılmaz, sadece "live: true" işareti gider.
# Kaynaklar normalde bilgisayarda (<iş klasörü>/kaynaklar.json) durur ve bilgisayarın sunucusu işareti kendisi ekler.

def _github_asset(st: dict | None) -> bool:
    return bool(st) and ("api.github.com/repos/" in json.dumps(st) or bool(st.get("release")))


def live_stream(st: dict | None) -> dict | None:
    """Kayıttaki stream → uygulamaya giden stream. Kaynağı olan: {"live": true, ...}; eski GitHub dosyalı: None."""
    if not st:
        return None
    # Verified archive files take precedence over old live stream references.
    if st.get("direct") or st.get("master") or st.get("versions"):
        return {k: v for k, v in st.items() if k not in ("source", "live")}
    if not (st.get("source") or st.get("live")):
        return None
    out = {"live": True}
    for k in ("audio", "duration"):
        if st.get(k):
            out[k] = st[k]
    return out


def live_trailers(item: dict) -> tuple[dict | None, list | None]:
    """GitHub Release'indeki fragman dosyaları ölü: atılır. Sadece dış (YouTube vb.) fragmanlar kalır."""
    tr = [x for x in (item.get("trailers") or []) if x and not _github_asset(x)]
    one = item.get("trailer")
    one = one if one and not _github_asset(one) else (tr[0] if tr else None)
    return one, (tr or None)


def base_entry(item: dict) -> dict:
    return {
        "id": item["id"], "type": item["type"], "tmdbId": item.get("tmdbId"),
        "title": item.get("title", item["id"]), "names": {"tr": item.get("title", item["id"])},
        "year": item.get("year"), "overview": "", "genres": [],
        # zamanlı yayında "eklenme" yayın anıdır: yayına girince "Yeni Eklenenler"in başına gelir
        "added": max(item.get("added") or 0, item.get("releaseAt") or 0) or None,
        "updated": max(item.get("updated") or item.get("added") or 0, item.get("releaseAt") or 0) or None,
        "trailer": live_trailers(item)[0],
        "trailers": live_trailers(item)[1],
        # Yakında / zamanlanmış yayın: uygulamalar bu saate kadar videoyu gizler, "Yakında"da gösterir
        "releaseAt": item.get("releaseAt"),
        "comingSoon": True if item.get("comingSoon") else None,
        # Tema müziği kaldırıldı (3 Kasım 2026): her yapım sayfasında GitHub'dan dosya istiyordu (istek hakkı).
        # Kayıtlardaki "theme" alanı ve yüklü dosyalar silinmedi; sadece uygulamalara gönderilmiyor.
        # Yöneticinin işaretlediği kalite (HD, HD+, Full HD, 4K, … HDR) ve Dolby Vision / Atmos
        "badges": item.get("badges") or None,
        # Yayın planı (dizi): [{"season": 2, "from": 1, "to": 5, "at": epoch}] ve isteğe bağlı bilgilendirme metni
        "releasePlan": item.get("releasePlan") or None,
        "releaseNote": (item.get("releaseNote") or "").strip() or None,
        # Stüdyo notları (📢 Duyuru …): süresi dolan not düşer; "hide" notu dolunca yapım uygulamalarda gizlenir (silinmez)
        "notes": _notes(item.get("notes")),
    }


def resolve_id(tmdb: TMDB, item: dict, path: Path | None) -> int | None:
    if item.get("tmdbId"):
        return item["tmdbId"]
    kind = "tv" if item["type"] == "series" else "movie"
    res = tmdb.search(kind, item["title"], item.get("year"))
    if not res:
        print(f"  ! TMDB'de bulunamadı: {item['title']}")
        return None
    print(f"  TMDB eşleşmesi: {item['title']} -> {res[0]['title']} ({res[0]['id']})")
    item["tmdbId"] = res[0]["id"]
    if path:
        path.write_text(json.dumps(item, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return item["tmdbId"]


def _videos(tmdb: TMDB, kind: str, tid: int) -> list[dict] | None:
    """TMDB'deki YouTube fragmanları (Türkçe ve İngilizce, resmi olanlar önce): otomatik fragman için."""
    out = []
    for lang in ("tr-TR", "en-US"):
        res = (tmdb.get(f"/{kind}/{tid}/videos", language=lang) or {}).get("results", [])
        for v in res:
            if v.get("site") != "YouTube" or v.get("type") not in ("Trailer", "Teaser") or not v.get("key"):
                continue
            if any(x["key"] == v["key"] for x in out):
                continue
            out.append({"key": v["key"], "lang": (v.get("iso_639_1") or lang[:2]).lower(), "type": v["type"],
                        "official": bool(v.get("official")), "name": v.get("name") or "", "size": v.get("size") or 0})
    rank = {"Trailer": 0, "Teaser": 1}
    out.sort(key=lambda x: (rank.get(x["type"], 2), not x["official"], -x["size"]))
    return out or None


def _details(tmdb: TMDB, item: dict, path: Path | None, e: dict) -> dict | None:
    tv = item["type"] == "series"
    kind = "tv" if tv else "movie"
    tid = resolve_id(tmdb, item, path)
    if not tid:
        return None
    d = tmdb.get(f"/{kind}/{tid}", language=LANG,
                 append_to_response="credits,content_ratings" if tv else "credits,release_dates")
    if not d:
        return None
    en = tmdb.get(f"/{kind}/{tid}", language="en-US") or {}
    images = tmdb.images(kind, tid)
    original_lang = d.get("original_language")
    art = item.get("art") or {}
    posters = select_art(images.get("posters", []), "posters", art.get("posters"), original_lang)
    backdrops = select_art(images.get("backdrops", []), "backdrops", art.get("backdrops"), original_lang)
    logos = select_art(images.get("logos", []), "logos", art.get("logos"), original_lang)
    tr_name = d.get("name" if tv else "title") or e["title"]
    en_name = en.get("name" if tv else "title") or tr_name
    orig_name = d.get("original_name" if tv else "original_title") or en_name
    age, label = maturity(_cert(d, tv))
    run_times = d.get("episode_run_time") or []
    credits = d.get("credits") or {}
    e["tmdbVideos"] = _videos(tmdb, kind, tid)
    e.update({
        "tmdbId": tid,
        "title": tr_name,
        "names": {"tr": tr_name, "en": en_name, "original": orig_name},
        "originalTitle": orig_name,
        "overview": d.get("overview") or en.get("overview") or "",
        "tagline": d.get("tagline") or None,
        "year": _year(d.get("first_air_date" if tv else "release_date")) or e["year"],
        "runtime": (run_times[0] if run_times else None) if tv else (d.get("runtime") or None),
        "rating": round(d.get("vote_average") or 0, 1) or None,
        # TMDB'nin günlük popülerlik puanı (izlenme, arama, oy ve konuşulma); "Bugünün İlk 10'u" buna göre sıralanır
        "popularity": round(d.get("popularity") or 0, 2) or None,
        "maturity": age,
        "certification": label,
        "genres": [g["name"] for g in d.get("genres", [])],
        "cast": [c["name"] for c in credits.get("cast", [])[:10]],
        "creators": ([c["name"] for c in d.get("created_by", [])] if tv
                     else [c["name"] for c in credits.get("crew", []) if c.get("job") == "Director"])[:3],
        "poster": _first(posters, ("tr", None, "en")) or img(d.get("poster_path"), "w500"),
        # kapak/arka planda yazısız görsel (logo ayrıca gösterilir); TMDB'nin varsayılan arka planı genelde yazısızdır
        "backdrop": next((a["url"] for a in backdrops if a.get("pinned") or a.get("lang") is None), None)
                    or img(d.get("backdrop_path"), "w1280") or _first(backdrops, ("tr", "en")),
        "logo": _first(logos, ("tr", "en")),
        "posters": posters,
        "backdrops": backdrops,
        "logos": logos,
        "originalLanguage": original_lang,
    })
    if tv:
        e["tmdbSeasons"], e["nextEpisode"] = _tmdb_seasons(d)
        la = d.get("last_episode_to_air") or {}
        # Yayınlanmış son bölüm (yeni bölüm kendiliğinden alınsın diye; duyuru değil, gerçekten yayınlanmış olan)
        if la.get("season_number") and la.get("episode_number") and len(la.get("air_date") or "") == 10:
            e["lastAired"] = {"s": la["season_number"], "e": la["episode_number"], "date": la["air_date"]}
    return d


def build_movie(tmdb: TMDB | None, item: dict, path: Path | None) -> dict:
    e = base_entry(item)
    e["stream"] = live_stream(item.get("stream"))
    if tmdb:
        _details(tmdb, item, path, e)
    return e


def _plan_at(item: dict, season: int, episode: int) -> int | None:
    """Yayın planındaki (sezon, bölüm aralığı) zaman; plan yoksa None."""
    for p in item.get("releasePlan") or []:
        if p.get("season") == season and (p.get("from") or 1) <= episode <= (p.get("to") or 10_000):
            return p.get("at")
    return None


def _batch_gates(item: dict) -> list[dict]:
    """
    "Hepsi yüklenince birlikte aç" planları: aralıktaki bölümlerin hepsi yüklenene kadar hiçbiri kataloğa girmez
    (eski uygulama sürümleri de göremez); hepsi gelince aynı anda açılır (en erken zaman verildiyse o saatte).
    Planın kopyasına yüklenen sayısı (have), toplam (total) ve tamamlandı mı (complete) yazılır.
    """
    have_by_season: dict[int, set[int]] = {}
    for ep in item.get("episodes", []):
        if ep.get("stream"):
            have_by_season.setdefault(ep["season"], set()).add(ep["episode"])
    out = []
    for p in item.get("releasePlan") or []:
        if not p.get("whenComplete") or not p.get("to"):
            continue
        rng = range(int(p.get("from") or 1), int(p["to"]) + 1)
        have = sum(1 for n in rng if n in have_by_season.get(p["season"], set()))
        out.append({**p, "have": have, "total": len(rng), "complete": have == len(rng)})
    return out


def _held(gates: list[dict], season: int, episode: int) -> bool:
    return any(not g["complete"] and g["season"] == season and (g.get("from") or 1) <= episode <= g["to"] for g in gates)


def build_series(tmdb: TMDB | None, item: dict, path: Path | None) -> dict:
    e = base_entry(item)
    gates = _batch_gates(item)
    if gates:     # planı yüklenen sayısıyla birlikte yaz (uygulama "hepsi hazır olunca birlikte açılacak" der)
        e["releasePlan"] = [next((g for g in gates if g["season"] == p.get("season") and g.get("from") == p.get("from") and g.get("to") == p.get("to")), p)
                            for p in item.get("releasePlan") or []]
    # tamamlanmamış "birlikte aç" aralığındaki bölümler kataloğa hiç girmez
    # Yeni düzende 'hepsi hazır olunca aç' kapısını bilgisayarın yayın sunucusu kaynaklara göre uygular (yayin.live_catalog);
    # burada bölümler (TMDB adları/görselleriyle) eksiksiz üretilir
    episodes = list(item.get("episodes", []))
    seasons: dict[int, dict] = {}
    for ep in episodes:
        s = seasons.setdefault(ep["season"], {"number": ep["season"], "name": f"{ep['season']}. Sezon", "episodes": []})
        rel = ep.get("releaseAt") or (item.get("episodeRelease") or {}).get(f"{ep['season']}:{ep['episode']}") or _plan_at(item, ep["season"], ep["episode"])
        s["episodes"].append({"number": ep["episode"], "added": max(ep.get("added") or 0, rel or 0) or None, "stream": live_stream(ep.get("stream")), "releaseAt": rel})
    if episodes:
        now = int(time.time())
        # zamanı gelmemiş bölüm "son güncellenme"yi ileri atmasın (yayına girince katalog yeniden üretilir)
        e["updated"] = max([e.get("updated") or 0] + [x["added"] or 0 for se in seasons.values() for x in se["episodes"] if (x.get("releaseAt") or 0) <= now])
    d = _details(tmdb, item, path, e) if tmdb else None
    if d:
        tid = e["tmdbId"]
        for number, season in seasons.items():
            sd = tmdb.get(f"/tv/{tid}/season/{number}", language=LANG) or {}
            se = tmdb.get(f"/tv/{tid}/season/{number}", language="en-US") or {}
            tr_eps = {x["episode_number"]: x for x in sd.get("episodes", [])}
            en_eps = {x["episode_number"]: x for x in se.get("episodes", [])}
            season["overview"] = sd.get("overview") or None
            season["poster"] = img(sd.get("poster_path"), "w342")
            for ep in season["episodes"]:
                x = tr_eps.get(ep["number"], {})
                y = en_eps.get(ep["number"], {})
                name = x.get("name") or ""
                generic = not name or (name.lower().startswith(("episode", "bölüm")) and name.split()[-1].isdigit())
                ep.update({
                    "title": name if not generic else f"Bölüm {ep['number']}",
                    "titleEn": y.get("name") or None,
                    "overview": x.get("overview") or y.get("overview") or None,
                    "still": img(x.get("still_path") or y.get("still_path"), "w500"),
                    "runtime": x.get("runtime") or None,
                })
    for s in seasons.values():
        s["episodes"].sort(key=lambda x: x["number"])
    e["seasons"] = [seasons[k] for k in sorted(seasons)]
    return e


def build_entry(tmdb: TMDB | None, item: dict, path: Path | None = None) -> dict:
    try:
        entry = build_series(tmdb, item, path) if item.get("type") == "series" else build_movie(tmdb, item, path)
    except Exception as ex:  # tek bir kayıt tüm kataloğu bozmasın
        print(f"  ! Hata: {ex} — temel bilgilerle eklendi")
        entry = build_series(None, item, path) if item.get("type") == "series" else build_movie(None, item, path)
    overrides = dict(item.get("overrides") or {})
    names_override = overrides.pop("names", None)
    entry.update(overrides)
    names = entry.setdefault("names", {})
    if isinstance(names_override, dict):
        names.update(names_override)
    if "title" in overrides:
        names["tr"] = overrides["title"]
    return _clean(entry)


def _notes(raw) -> list | None:
    now = int(time.time())
    out = []
    for n in raw or []:
        text = (n.get("text") or "").strip()
        until = n.get("until")
        if not text and not n.get("hide"):
            continue
        if until and until <= now and not n.get("hide"):
            continue
        out.append({"text": text, "until": until, "hide": bool(n.get("hide")) or None})
    return out or None


def _tmdb_seasons(d: dict) -> tuple[list | None, dict | None]:
    """TMDB'deki sezonlar (n: sezon, c: bölüm sayısı; "Özel" sezon 0 hariç) ve sıradaki bölümün yayın zamanı (20:00 TR)."""
    seasons = [{"n": s["season_number"], "c": s.get("episode_count") or 0}
               for s in d.get("seasons") or [] if (s.get("season_number") or 0) >= 1 and (s.get("episode_count") or 0) > 0]
    nxt = d.get("next_episode_to_air") or None
    nxt_out = None
    if nxt and nxt.get("season_number") and nxt.get("episode_number"):
        at = None
        date = nxt.get("air_date") or ""
        if len(date) == 10:
            try:
                at = int(datetime.strptime(date + " 20:00", "%Y-%m-%d %H:%M").replace(tzinfo=timezone(timedelta(hours=3))).timestamp())
            except ValueError:
                at = None
        nxt_out = {"s": nxt["season_number"], "e": nxt["episode_number"], "at": at}
    if not nxt_out:
        # Yeni sezon duyuruldu ama bölümleri henüz yok (TMDB'de bölüm sayısı 0 ya da yayın tarihi ileride):
        # "Yakında Gelen Diziler"e kendiliğinden girsin (yönetici elle eklemesin)
        aired = max([x["n"] for x in seasons] or [0])
        today = datetime.now(timezone(timedelta(hours=3))).strftime("%Y-%m-%d")
        for s_ in sorted(d.get("seasons") or [], key=lambda x: x.get("season_number") or 0):
            n = s_.get("season_number") or 0
            date = s_.get("air_date") or ""
            if n < 1 or not (n > aired or (len(date) == 10 and date > today)):
                continue
            at = None
            if len(date) == 10 and date > today:
                try:
                    at = int(datetime.strptime(date + " 20:00", "%Y-%m-%d %H:%M").replace(tzinfo=timezone(timedelta(hours=3))).timestamp())
                except ValueError:
                    at = None
            nxt_out = {"s": n, "e": 1, "at": at}
            break
    return (seasons or None), nxt_out


def _clean(v):
    if isinstance(v, dict):
        return {k: _clean(x) for k, x in v.items() if x is not None}
    if isinstance(v, list):
        return [_clean(x) for x in v]
    return v


def build(root: Path, key: str, items_dir: Path | None = None, out: Path | None = None) -> dict:
    """Katalog üretimi. Yeni düzende bilgisayarda çalışır: items_dir=<iş klasörü>/katalog/items, out=…/catalog.json
    (yapım adları GitHub'a hiç gitmez)."""
    items_dir = items_dir or root / "catalog" / "items"
    out = out or root / "catalog" / "catalog.json"
    tmdb = TMDB(key) if key else None
    if not tmdb:
        print("! TMDB_API_KEY yok: sadece temel bilgilerle katalog oluşturulacak.")
    titles = []
    for path in sorted(items_dir.glob("*.json")):
        item = json.loads(path.read_text(encoding="utf-8"))
        print(f"• {item.get('title', path.stem)}")
        titles.append(build_entry(tmdb, item, path))
    titles.sort(key=lambda t: t.get("updated") or t.get("added") or 0, reverse=True)
    stats = compute_stats(titles, release_sizes())
    catalog = {"version": 2, "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "stats": stats, "titles": titles}
    # ISTATISTIK.md artık yazılmıyor (depoda belge dosyası tutulmuyor); istatistik catalog.json içinde
    out.parent.mkdir(parents=True, exist_ok=True)
    part = out.with_name(out.name + ".yaziliyor")
    part.write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    part.replace(out)       # yarım dosya okunmasın
    print(f"✓ {len(titles)} yapım -> {out}")
    return catalog


# ---------------------------------------------------------------- arşiv istatistiği (her katalog üretiminde)

def release_sizes() -> dict[str, int]:
    """GitHub Releases: etiket -> dosyaların toplam boyutu (bayt). Token yoksa boş (parça boyutlarına düşülür)."""
    token, repo = os.environ.get("GITHUB_TOKEN", ""), os.environ.get("GITHUB_REPOSITORY", "")
    if not token or not repo:
        return {}
    import requests
    out: dict[str, int] = {}
    url = f"https://api.github.com/repos/{repo}/releases?per_page=100"
    try:
        while url:
            r = requests.get(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}, timeout=30)
            r.raise_for_status()
            for rel in r.json():
                out[rel["tag_name"]] = sum(a.get("size") or 0 for a in rel.get("assets", []))
            url = r.links.get("next", {}).get("url")
    except Exception as ex:  # istatistik yüzünden katalog bozulmasın
        print(f"  ! Release boyutları alınamadı: {ex}")
        return {}
    return out


def _stream_bytes(st: dict | None, sizes: dict[str, int], seen: set[str]) -> int:
    """Bir videonun kapladığı yer: Release'in gerçek boyutu (aynı Release iki kez sayılmaz), yoksa kayıtlı parça boyutları."""
    if not st:
        return 0
    tag = st.get("release")
    if tag and tag in sizes:
        if tag in seen:
            return 0
        seen.add(tag)
        return sizes[tag]
    total = 0
    for v in st.get("versions") or []:
        total += sum((v.get("video") or {}).get("sizes") or [])
    d = st.get("direct") or {}
    total += sum((d.get("video") or {}).get("sizes") or [])
    for a in d.get("audio") or []:
        total += sum(a.get("sizes") or [])
    return total


def compute_stats(titles: list[dict], sizes: dict[str, int]) -> dict:
    now = int(time.time())
    seen: set[str] = set()
    movies = series = seasons = episodes = trailers = soon = 0
    content_bytes = trailer_bytes = minutes = 0
    for t in titles:
        tr = t.get("trailers") or ([t["trailer"]] if t.get("trailer") else [])
        trailers += len(tr)
        for x in tr:
            trailer_bytes += _stream_bytes(x, sizes, seen) + sum((x.get("uhd") or {}).get("sizes") or []) * (0 if x.get("release") in sizes else 1)
        upcoming = (t.get("releaseAt") or 0) > now
        if t.get("type") == "series":
            eps = [(s, e) for s in t.get("seasons", []) for e in s.get("episodes", []) if e.get("stream")]
            for _, e in eps:
                content_bytes += _stream_bytes(e["stream"], sizes, seen)
                minutes += e.get("runtime") or round((e["stream"].get("duration") or 0) / 60)
            if eps:
                series += 1
                seasons += len({s["number"] for s, _ in eps})
                episodes += len(eps)
        elif t.get("stream"):
            movies += 1
            content_bytes += _stream_bytes(t["stream"], sizes, seen)
            minutes += t.get("runtime") or round((t["stream"].get("duration") or 0) / 60)
        if upcoming or (t.get("comingSoon") and not t.get("stream") and not any(s.get("episodes") for s in t.get("seasons", []))):
            soon += 1
    return {
        "movies": movies, "series": series, "seasons": seasons, "episodes": episodes, "trailers": trailers, "comingSoon": soon,
        "contentBytes": content_bytes, "trailerBytes": trailer_bytes, "totalBytes": content_bytes + trailer_bytes,
        "minutes": minutes, "exactSizes": bool(sizes),
    }


def human_size(n: int) -> str:
    """Ondalık birimler (telefon ve diskler gibi): 1 GB = 1000 MB."""
    for unit, div in (("TB", 1e12), ("GB", 1e9), ("MB", 1e6)):
        if n >= div:
            return f"{n / div:.2f} {unit}".replace(".", ",")
    return f"{n / 1e3:.0f} KB"


def write_stats_page(path: Path, st: dict, titles: list[dict]) -> None:
    hours = st["minutes"] / 60
    lines = [
        "# 📊 G-Flix Arşivi",
        "",
        f"_Her yükleme ve düzenlemede kendiliğinden güncellenir. Son güncelleme: {datetime.now(ZoneInfo('Europe/Istanbul')).strftime('%d.%m.%Y %H:%M')} (Türkiye saati)_",
        "",
        "| | |",
        "|---|---|",
        f"| 🎬 Film | **{st['movies']}** |",
        f"| 📺 Dizi | **{st['series']}** |",
        f"| 🗂️ Sezon | **{st['seasons']}** |",
        f"| ▶️ Bölüm | **{st['episodes']}** |",
        f"| 🎞️ Fragman | **{st['trailers']}** |",
        f"| 🗓️ Yakında | **{st['comingSoon']}** |",
        f"| ⏱️ Toplam süre | **{hours:.0f} saat** |",
        f"| 💾 Filmler + bölümler | **{human_size(st['contentBytes'])}** |",
        f"| 💾 Fragmanlar | **{human_size(st['trailerBytes'])}** |",
        f"| 💾 **Toplam** | **{human_size(st['totalBytes'])}** |",
        "",
    ]
    if not st.get("exactSizes"):
        lines += ["> Boyutlar kayıtlı parça boyutlarından hesaplandı (eski HLS yüklemeleri dahil değil).", ""]
    lines += ["## Yapımlar", "", "| Yapım | Tür | İçerik |", "|---|---|---|"]
    for t in sorted(titles, key=lambda x: x.get("title", "").lower()):
        if t.get("type") == "series":
            eps = [(s["number"], e) for s in t.get("seasons", []) for e in s.get("episodes", []) if e.get("stream")]
            if not eps:
                continue
            info = f"{len({n for n, _ in eps})} sezon, {len(eps)} bölüm"
            kind = "Dizi"
        else:
            if not t.get("stream"):
                continue
            info, kind = (f"{t['runtime']} dk" if t.get("runtime") else "—"), "Film"
        lines.append(f"| {t.get('title', t['id'])} | {kind} | {info} |")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
