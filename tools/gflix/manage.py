"""Depodaki yapımları yönetme: listeleme, düzenleme, silme."""
from __future__ import annotations

from .github import GitHub
from .uploader import ITEMS_DIR


def load_catalog(gh: GitHub) -> dict:
    data, _ = gh.get_json("catalog/catalog.json")
    return data or {"titles": []}


def list_item_ids(gh: GitHub) -> list[str]:
    return [f["name"][:-5] for f in gh.list_dir(ITEMS_DIR) if f.get("name", "").endswith(".json")]


def get_item(gh: GitHub, item_id: str) -> tuple[dict | None, str | None]:
    return gh.get_json(f"{ITEMS_DIR}/{item_id}.json")


def save_item(gh: GitHub, item: dict, sha: str | None, message: str) -> str:
    return gh.put_json(f"{ITEMS_DIR}/{item['id']}.json", item, message, sha)


def _tags_for(item: dict) -> list[str]:
    tags = []
    if item.get("stream", {}).get("release"):
        tags.append(item["stream"]["release"])
    for ep in item.get("episodes", []):
        if ep.get("stream", {}).get("release"):
            tags.append(ep["stream"]["release"])
    for t in [item.get("trailer") or {}] + list(item.get("trailers") or []):
        if t.get("release") and t["release"] not in tags:
            tags.append(t["release"])
    return tags


def delete_title(gh: GitHub, item_id: str, log=print) -> None:
    item, sha = get_item(gh, item_id)
    tags = set(_tags_for(item or {}))
    prefix = item_id + "-"
    for rel in gh.list_releases():
        t = rel["tag_name"]
        if t == item_id or (t.startswith(prefix) and (t[len(prefix):].startswith("s") or t.endswith("-trailer"))):
            tags.add(t)
    for t in sorted(tags):
        log(f"  Release siliniyor: {t}")
        gh.delete_release(t)
    if sha:
        gh.delete_file(f"{ITEMS_DIR}/{item_id}.json", f"Yapım silindi: {(item or {}).get('title', item_id)}", sha)
    log(f"✓ {item_id} silindi")


def delete_episode(gh: GitHub, item_id: str, season: int, episode: int, log=print) -> None:
    item, sha = get_item(gh, item_id)
    if not item:
        raise ValueError("Yapım bulunamadı")
    keep, removed = [], None
    for ep in item.get("episodes", []):
        if ep["season"] == season and ep["episode"] == episode:
            removed = ep
        else:
            keep.append(ep)
    if removed and removed.get("stream", {}).get("release"):
        gh.delete_release(removed["stream"]["release"])
    item["episodes"] = keep
    save_item(gh, item, sha, f"Bölüm silindi: {item.get('title')} S{season:02d}E{episode:02d}")
    log("✓ Bölüm silindi")


def remove_trailer(gh: GitHub, item_id: str, log=print) -> None:
    item, sha = get_item(gh, item_id)
    if not item or ("trailer" not in item and not item.get("trailers")):
        return
    tags = {t.get("release") or f"{item_id}-trailer" for t in [item.get("trailer") or {}] + list(item.get("trailers") or []) if t}
    for tag in tags:
        gh.delete_release(tag)
    item.pop("trailer", None)
    item.pop("trailers", None)
    save_item(gh, item, sha, f"Fragman kaldırıldı: {item.get('title')}")
    log("✓ Fragman kaldırıldı")


def rebuild_catalog(gh: GitHub) -> None:
    gh.dispatch_workflow("catalog.yml")
