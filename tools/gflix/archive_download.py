"""Authorized media ingest: download -> verify -> Internet Archive -> catalog.

Never bypasses DRM, paywalls, account restrictions or website protections.
This worker only runs when the operator explicitly enables public archiving
and supplies their own archive.org S3 keys and a unique item prefix.
"""
from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import re
import shutil
import subprocess
import threading
import time
from pathlib import Path
from urllib.parse import quote, urlparse

import requests


def require_public_https(url: str) -> str:
    """Avoid accidental local-network downloads / SSRF via captured URLs."""
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("Yalnızca HTTPS medya bağlantıları kabul edilir")
    host = parsed.hostname.lower()
    if host == "localhost" or host.endswith((".local", ".internal", ".localhost")):
        raise ValueError("Yerel adresler indirilemez")
    try:
        ip = ipaddress.ip_address(host)
        if not ip.is_global:
            raise ValueError("Özel ağ adresleri indirilemez")
    except ValueError as exc:
        if "Özel ağ" in str(exc):
            raise
    return url


def archive_item(prefix: str, item_id: str, season: int | None) -> str:
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,39}", prefix):
        raise ValueError("archive.org öneki geçersiz")
    if not re.fullmatch(r"(movie|series)-[a-z0-9-]+", item_id):
        raise ValueError("Yapım kimliği geçersiz")
    base = (prefix + "-" + item_id[:70]).rstrip("-")
    return base + (f"-s{season:02d}" if season is not None else "")


def file_base(item_id: str, season: int | None, episode: int | None) -> str:
    return f"s{season:02d}e{episode:02d}" if season is not None and episode is not None else item_id.removeprefix("movie-")[:60]


def md5_file(path: Path) -> str:
    h = hashlib.md5()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


class ArchiveQueue:
    """Persistent, single-worker queue. A job is done only after upload + catalog."""

    def __init__(self, workdir: Path, *, enabled: bool, access: str, secret: str,
                 prefix: str, resolve_media, completed, log=print):
        self.workdir = Path(workdir)
        self.access, self.secret, self.prefix = access, secret, prefix
        self.enabled = bool(enabled and access and secret and prefix)
        self.resolve_media = resolve_media
        self.completed = completed
        self.log = log
        self.path = self.workdir / "archive-jobs.json"
        self.root = self.workdir / "archive-downloads"
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.jobs = {}
        try:
            self.jobs = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            pass
        for job in self.jobs.values():
            if job.get("state") in ("downloading", "uploading", "verifying", "catalog"):
                job["state"] = "queued"

    @staticmethod
    def key(item_id: str, season: int | None, episode: int | None) -> str:
        return f"{item_id}|{season if season is not None else '-'}|{episode if episode is not None else '-'}"

    def _save(self):
        self.workdir.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.jobs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        tmp.replace(self.path)

    def enqueue(self, item_id: str, url: str, title: str, season: int | None = None,
                episode: int | None = None, tmdb_id: int | None = None) -> str:
        if not self.enabled:
            raise RuntimeError("archive.org otomatik yüklemesi kapalı; önek, S3 anahtarları ve açık onay gerekli")
        require_public_https(url)
        archive_item(self.prefix, item_id, season)
        if (season is None) != (episode is None):
            raise ValueError("Sezon ve bölüm birlikte belirtilmeli")
        key = self.key(item_id, season, episode)
        with self.lock:
            old = self.jobs.get(key)
            if old and old.get("state") in ("done", "queued", "downloading", "uploading", "verifying", "catalog"):
                return key
            self.jobs[key] = {
                "item_id": item_id, "url": url, "title": title, "season": season, "episode": episode,
                "tmdb_id": tmdb_id, "state": "queued", "tries": 0, "next_at": 0,
                "note": "indirme sırasında", "created": int(time.time()),
            }
            self._save()
        return key

    def status(self, key: str) -> dict:
        with self.lock:
            return dict(self.jobs.get(key) or {"state": "missing", "note": "iş yok"})

    def _status(self, key: str, state: str, note: str = ""):
        with self.lock:
            job = self.jobs[key]
            job["state"], job["note"], job["updated"] = state, note[:180], int(time.time())
            self._save()
        self.log(f"📦 {job['item_id']}: {state} {note[:100]}")

    def start(self):
        if not self.enabled or self.thread is not None:
            return
        self.thread = threading.Thread(target=self._loop, name="gflix-archive", daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread is not None:
            self.thread.join(timeout=5)

    def _loop(self):
        while not self.stop_event.is_set():
            with self.lock:
                now = time.time()
                candidate = next((k for k, v in self.jobs.items()
                                  if v["state"] in ("queued", "retry") and v.get("next_at", 0) <= now), None)
                job = dict(self.jobs[candidate]) if candidate else None
            if job:
                try:
                    self._process(candidate, job)
                    self._status(candidate, "done", "archive.org doğrulandı; katalog yayınlandı")
                except Exception as exc:
                    with self.lock:
                        current = self.jobs[candidate]
                        n = current["tries"] = current.get("tries", 0) + 1
                        current["next_at"] = int(time.time() + min(3600, 60 * 2 ** min(n - 1, 6)))
                        self._save()
                    self._status(candidate, "failed" if n >= 8 else "retry", f"{type(exc).__name__}: {str(exc)[:120]}")
            else:
                self.stop_event.wait(3)

    def _download(self, job: dict, dest: Path) -> tuple[float | None, list[dict]]:
        """Use existing capture/resolver first so alternate audio tracks survive."""
        ffmpeg = shutil.which("ffmpeg")
        if not ffmpeg:
            raise RuntimeError("ffmpeg kurulu değil")
        subs = []
        duration = None
        try:
            resolved = self.resolve_media(job)
        except Exception as exc:
            self.log(f"  Çözümleme mümkün olmadı; standart indirici denenecek ({type(exc).__name__})")
            resolved = None
        if resolved:
            video = require_public_https(resolved.video)
            inputs = [video] + [require_public_https(url) for _, url in resolved.audios]
            headers = resolved.headers or {}
            allowed = {"User-Agent", "Referer", "Origin", "Cookie"}
            custom = "".join(f"{k}: {v}\r\n" for k, v in headers.items()
                             if k in allowed and "\n" not in str(v) and "\r" not in str(v))
            args = [ffmpeg, "-hide_banner", "-nostdin", "-loglevel", "error", "-y"]
            for url in inputs:
                if custom:
                    args += ["-headers", custom]
                args += ["-i", url]
            args += ["-map", "0:v:0", "-map", "0:a?"]
            for i in range(1, len(inputs)):
                args += ["-map", f"{i}:a:0"]
            args += ["-c", "copy", "-max_muxing_queue_size", "2048", str(dest)]
            proc = subprocess.run(args, capture_output=True, timeout=8 * 3600)
            if proc.returncode:
                raise RuntimeError("ffmpeg indirme başarısız: " + proc.stderr.decode("utf-8", "replace")[-250:])
            duration = resolved.duration
            for i, entry in enumerate(resolved.subs):
                lang = re.sub(r"[^a-z0-9-]", "", str(entry.get("lang") or "und").lower())[:12] or "und"
                name = f"altyazi-{i:02d}-{lang}.vtt"
                text = entry.get("text") or ""
                if text:
                    p = dest.parent / name
                    p.write_text(text, encoding="utf-8")
                    subs.append({"path": p, "lang": lang, "forced": bool(entry.get("forced"))})
        else:
            # Standard downloader for accessible, non-DRM, operator-authorized sources.
            binary = shutil.which("yt-dlp")
            if not binary:
                raise RuntimeError("yt-dlp kurulu değil")
            url = require_public_https(job["url"])
            args = [binary, "--no-playlist", "--no-progress", "--no-part", "--no-overwrites",
                    "--merge-output-format", "mkv", "--remux-video", "mkv",
                    "--format", "bv*+ba/b", "--write-subs", "--sub-langs", "all",
                    "-o", str(dest), url]
            proc = subprocess.run(args, capture_output=True, timeout=8 * 3600)
            if proc.returncode:
                raise RuntimeError("yt-dlp indirme başarısız: " + proc.stderr.decode("utf-8", "replace")[-250:])
            if not dest.is_file():
                files = sorted(dest.parent.glob(dest.stem + "*"), key=lambda p: p.stat().st_size, reverse=True)
                source = next((p for p in files if p.suffix.lower() in (".mkv", ".mp4", ".webm")), None)
                if source:
                    source.rename(dest)
            for p in dest.parent.glob(dest.stem + "*.vtt"):
                subs.append({"path": p, "lang": p.stem.split(".")[-1][:12], "forced": False})
        if not dest.is_file() or dest.stat().st_size < 1024:
            raise RuntimeError("İndirilen video bulunamadı veya boş")
        return duration, subs

    def _remote_files(self, item: str) -> dict:
        resp = requests.get(f"https://archive.org/metadata/{item}/files", timeout=(20, 60))
        if not resp.ok:
            return {}
        return {f.get("name"): f for f in (resp.json().get("result") or []) if f.get("name")}

    def upload_file(self, item: str, remote: str, file: Path, *, title: str, kind: str = "movies"):
        """IAS3 single PUT; idempotent by size + checksum, verifies archive visibility."""
        encoded = quote(remote, safe="")
        existing = self._remote_files(item).get(remote)
        local_md5 = md5_file(file)
        if not (existing and int(existing.get("size") or -1) == file.stat().st_size
                and (not existing.get("md5") or existing["md5"] == local_md5)):
            headers = {
                "Authorization": f"LOW {self.access}:{self.secret}",
                "x-archive-auto-make-bucket": "1",
                "x-archive-queue-derive": "0",
                "x-archive-keep-old-version": "0",
                "x-archive-meta-mediatype": kind,
                "x-archive-meta-collection": "opensource_movies" if kind == "movies" else "opensource",
                "x-archive-meta-title": title,
                "x-archive-meta-subject": "gflix",
            }
            for attempt in range(5):
                with file.open("rb") as stream:
                    try:
                        resp = requests.put(f"https://s3.us.archive.org/{item}/{encoded}",
                                            data=stream, headers=headers, timeout=(30, 1800), allow_redirects=False)
                    except requests.RequestException:
                        if attempt == 4:
                            raise
                        time.sleep(min(60, 2 ** (attempt + 1)))
                        continue
                if resp.ok:
                    break
                if resp.status_code not in (429, 500, 502, 503, 504) or attempt == 4:
                    raise RuntimeError(f"archive.org yüklemeyi reddetti: HTTP {resp.status_code}")
                time.sleep(min(60, 2 ** (attempt + 1)))
        for wait in range(16):
            remote_meta = self._remote_files(item).get(remote)
            if remote_meta and int(remote_meta.get("size") or -1) == file.stat().st_size:
                if remote_meta.get("md5") and remote_meta["md5"] != local_md5:
                    raise RuntimeError("archive.org checksum uyuşmuyor")
                return f"https://archive.org/download/{item}/{encoded}"
            self.stop_event.wait(min(60, 5 + wait * 4))
        raise TimeoutError("archive.org dosyası doğrulanamadı (daha sonra yeniden denenecek)")

    def _process(self, key: str, job: dict):
        item_id, season, episode = job["item_id"], job["season"], job["episode"]
        item = archive_item(self.prefix, item_id, season)
        base = file_base(item_id, season, episode)
        folder = self.root / hashlib.sha256(key.encode()).hexdigest()[:24]
        folder.mkdir(parents=True, exist_ok=True)
        video = folder / (base + ".mkv")
        duration = None
        subs = []
        if not video.is_file() or video.stat().st_size < 1024:
            self._status(key, "downloading", "video, sesler ve altyazılar indiriliyor")
            duration, subs = self._download(job, video)
        else:
            for p in folder.glob("*.vtt"):
                subs.append({"path": p, "lang": p.stem.split("-")[-1], "forced": False})
        self._status(key, "uploading", "archive.org'a dosyalar gönderiliyor")
        video_url = self.upload_file(item, video.name, video, title=job["title"])
        subtitle_entries = []
        for sub in subs:
            path = sub["path"]
            url = self.upload_file(item, path.name, path, title=job["title"])
            subtitle_entries.append({"lang": sub["lang"], "url": url, "forced": sub["forced"]})
        result = {
            "video_url": video_url, "video_size": video.stat().st_size,
            "subs": subtitle_entries, "duration": duration, "item": item,
        }
        self._status(key, "catalog", "katalog güncelleniyor ve yayımlanıyor")
        self.completed(job, result, self)
        shutil.rmtree(folder, ignore_errors=True)
