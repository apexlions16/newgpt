"""GitHub API: Release dosyaları, içerik dosyaları, iş akışları."""
from __future__ import annotations

import base64
import json
import time
from pathlib import Path
from typing import Callable

import requests

from .util import Cancelled

API = "https://api.github.com"


# Son görülen saatlik istek hakkı (X-RateLimit-Remaining): az kalınca işçi durum yazmayı seyreltir, TV'ye pay kalır
RATE: dict = {"remaining": None}


# İstek hakkı koruması (Stüdyo → Bilgisayar ayarları): kalan hak [min]'in altındayken yeni iş başlamaz, yüklemeler bekler;
# TV oynatması etkilenmez (ona pay kalsın diye). İşçi pcconfig'ten doldurur.
QUOTA: dict = {"on": False, "min": 900}
_QUOTA_CACHE: dict = {"at": 0.0, "remaining": None, "reset": 0}
_QUOTA_LOCK = __import__("threading").Lock()


_GQL_WARN = {"at": 0.0}


# GitHub'a medya yükleme KAPALI (3 Kasım 2026, eski hesap askıya alındıktan sonra): video, ses, altyazı, fragman, tema
# hiçbir yoldan Release'e yüklenmez. Sadece küçük metin dosyalarına (iş kaydı vb.) izin var. Kapıyı açan ayar yok.
UPLOAD_MAX_BYTES = 256 * 1024
_UPLOAD_TYPES = ("text/plain", "application/json")
_MEDIA_EXT = (".mkv", ".mp4", ".m4a", ".m4s", ".ts", ".webm", ".mp3", ".aac", ".ac3", ".eac3", ".opus", ".vtt", ".srt",
              ".ass", ".m3u8", ".mov", ".avi", ".flac", ".wav")


def upload_allowed(name: str, ctype: str, size: int) -> bool:
    import re as _re
    low = name.lower()
    return (size <= UPLOAD_MAX_BYTES and ctype.split(";")[0].strip().lower() in _UPLOAD_TYPES
            and not low.endswith(_MEDIA_EXT) and not _re.search(r"\.p\d{3}", low))


def rate_low(threshold: int = 1000) -> bool:
    r = RATE.get("remaining")
    if QUOTA.get("on"):
        threshold = max(threshold, int(QUOTA.get("min") or 0))
    return r is not None and r < threshold


def quota_paused(rem: int | None) -> bool:
    return bool(QUOTA.get("on")) and rem is not None and rem < int(QUOTA.get("min") or 0)


class GitHubError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


class GitHub:
    def __init__(self, repo: str, token: str, log: Callable[[str], None] = print):
        self.repo = repo.strip().strip("/")
        self.log = log
        self._token = token
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": f"Bearer {token.strip()}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "gflix",
        })

    def clone(self) -> "GitHub":
        """Paralel yükleme için ayrı bağlantı havuzlu kopya."""
        return GitHub(self.repo, self._token, self.log)

    # ------------------------------------------------------------ temel

    def req(self, method: str, url: str, ok=(200, 201, 204), **kw) -> requests.Response:
        if method.upper() in ("POST", "PUT", "PATCH", "DELETE") and "/graphql" not in url:
            # Yeni düzen: GitHub'a hiçbir şey yazılmaz (yapım adlı Release'ler, catalog/items kayıtları, iş kuyruğu).
            # Her şey evdeki bilgisayarda; GitHub'da sadece kod durur.
            raise GitHubError(0, f"GitHub'a yazma kapalı ({method} {url.split('?')[0][-60:]}). Yeni düzende her şey bilgisayarda.")
        if not url.startswith("http"):
            url = f"{API}/repos/{self.repo}{url}"
        timeout = kw.pop("timeout", 60)
        for attempt in range(5):
            try:
                r = self.s.request(method, url, timeout=timeout, **kw)
            except requests.RequestException as e:
                if attempt == 4:
                    raise GitHubError(0, f"Bağlantı hatası: {e}") from e
                time.sleep(2 ** attempt)
                continue
            rem = r.headers.get("X-RateLimit-Remaining")
            if rem is not None and rem.isdigit():
                RATE["remaining"] = int(rem)       # saatlik istek hakkından kalan (TV ve telefon da aynı hakkı kullanır)
            if r.status_code in ok:
                return r
            if r.status_code >= 500 or r.status_code == 429:
                time.sleep(2 ** attempt)
                continue
            msg = {401: "GitHub token geçersiz.", 403: "GitHub token'ın bu işlem için yetkisi yok.",
                   404: "Bulunamadı (depo adı ya da token yetkisi yanlış olabilir)."}.get(r.status_code, "")
            raise GitHubError(r.status_code, f"{msg} [{r.status_code} {method} {url}] {r.text[:300]}".strip())
        raise GitHubError(0, f"GitHub isteği başarısız: {method} {url}")

    def repo_info(self) -> dict:
        return self.req("GET", f"{API}/repos/{self.repo}").json()

    # ------------------------------------------------------------ içerik dosyaları

    def get_text(self, path: str) -> tuple[str | None, str | None]:
        r = self.req("GET", f"/contents/{path}", ok=(200, 404))
        if r.status_code == 404:
            return None, None
        meta = r.json()
        if isinstance(meta, list):
            raise GitHubError(400, f"{path} bir klasör")
        raw = self.req("GET", f"/contents/{path}", headers={"Accept": "application/vnd.github.raw+json"}).text
        return raw, meta["sha"]

    def get_json(self, path: str) -> tuple[dict | None, str | None]:
        raw, sha = self.get_text(path)
        return (json.loads(raw) if raw else None), sha

    def put_json(self, path: str, data: dict, message: str, sha: str | None) -> str:
        body = {"message": message, "content": base64.b64encode(
            (json.dumps(data, ensure_ascii=False, indent=2) + "\n").encode()).decode()}
        if sha:
            body["sha"] = sha
        return self.req("PUT", f"/contents/{path}", json=body).json()["content"]["sha"]

    def delete_file(self, path: str, message: str, sha: str) -> None:
        self.req("DELETE", f"/contents/{path}", json={"message": message, "sha": sha})

    def list_dir(self, path: str) -> list[dict]:
        r = self.req("GET", f"/contents/{path}", ok=(200, 404))
        return r.json() if r.status_code == 200 else []

    # ------------------------------------------------------------ release'ler

    def list_releases(self) -> list[dict]:
        out, page = [], 1
        while True:
            batch = self.req("GET", "/releases", params={"per_page": 100, "page": page}).json()
            out += batch
            if len(batch) < 100:
                return out
            page += 1

    def recent_releases(self, n: int = 100) -> list[dict]:  # noqa: C901
        """
        En yeni [n] Release, OLUŞTURULMA zamanına göre (yeniden eskiye). REST'in /releases listesi tarihe göre değil
        etiket adına göre sıralı geliyor (pcjob-f… önce, pcjob-0… sonra): rastgele adlı yeni iş ilk 100'ün dışında
        kalıp hiç görülmüyordu. GraphQL tarihe göre sıralar ve REST istek hakkından düşmez (ayrı havuz).
        Dönen sözlükler REST biçiminde: id, tag_name, name, draft, body, created_at, published_at, assets[{name}].
        GraphQL olmazsa REST'e döner (sırası güvenilmez).
        """
        owner, name = self.repo.split("/", 1)
        q = ("query($o:String!,$n:String!,$k:Int!){repository(owner:$o,name:$n){releases(first:$k,"
             "orderBy:{field:CREATED_AT,direction:DESC}){nodes{databaseId tagName name isDraft description createdAt "
             "publishedAt releaseAssets(first:30){nodes{name}}}}}}")
        try:
            r = self.req("POST", f"{API}/graphql", json={"query": q, "variables": {"o": owner, "n": name, "k": min(n, 100)}})
            d = r.json()
            nodes = (((d.get("data") or {}).get("repository") or {}).get("releases") or {}).get("nodes")
            if nodes is None:
                raise GitHubError(0, str(d.get("errors"))[:300])
            return [{"id": x["databaseId"], "tag_name": x["tagName"], "name": x.get("name") or "", "draft": bool(x.get("isDraft")),
                     "body": x.get("description") or "", "created_at": x.get("createdAt") or "", "published_at": x.get("publishedAt"),
                     "assets": [{"name": a["name"]} for a in ((x.get("releaseAssets") or {}).get("nodes") or [])]}
                    for x in nodes]
        except Exception as e:  # noqa: BLE001
            if time.time() - _GQL_WARN["at"] > 3600:
                _GQL_WARN["at"] = time.time()
                self.log(f"GraphQL ile Release listesi alınamadı ({str(e)[:200]}); bütün liste sayfa sayfa okunuyor")
            # Yedek: hepsini oku, tarihe göre sırala (birkaç istek; doğruluk için)
            rels = self.list_releases()
            rels.sort(key=lambda r: r.get("created_at") or "", reverse=True)
            return rels[:n]

    def get_release(self, tag: str) -> dict | None:
        r = self.req("GET", f"/releases/tags/{tag}", ok=(200, 404))
        return r.json() if r.status_code == 200 else None

    def ensure_release(self, tag: str, name: str, body: str) -> dict:
        rel = self.get_release(tag)
        if rel:
            return rel
        return self.req("POST", "/releases", json={
            "tag_name": tag, "name": name, "body": body, "prerelease": True, "make_latest": "false",
        }).json()

    def release_assets(self, release_id: int) -> list[dict]:
        out, page = [], 1
        while True:
            batch = self.req("GET", f"/releases/{release_id}/assets", params={"per_page": 100, "page": page}).json()
            out += batch
            if len(batch) < 100:
                return out
            page += 1

    def delete_asset(self, asset_id: int) -> None:
        self.req("DELETE", f"/releases/assets/{asset_id}", ok=(204, 404))

    def delete_release(self, tag: str) -> bool:
        rel = self.get_release(tag)
        if rel:
            self.req("DELETE", f"/releases/{rel['id']}", ok=(204, 404))
        self.req("DELETE", f"/git/refs/tags/{tag}", ok=(204, 404, 422))
        return rel is not None

    def quota(self, max_age: float = 30) -> tuple[int | None, int]:
        """(kalan hak, yenilenme epoch sn) — /rate_limit hakkın kendisinden düşmez; 30 sn önbellek."""
        with _QUOTA_LOCK:
            if time.time() - _QUOTA_CACHE["at"] < max_age:
                return _QUOTA_CACHE["remaining"], _QUOTA_CACHE["reset"]
            try:
                c = self.s.get("https://api.github.com/rate_limit", timeout=20).json()["resources"]["core"]
                _QUOTA_CACHE.update(at=time.time(), remaining=int(c["remaining"]), reset=int(c["reset"]))
                RATE["remaining"] = int(c["remaining"])
            except Exception:  # noqa: BLE001
                _QUOTA_CACHE["at"] = time.time()
            return _QUOTA_CACHE["remaining"], _QUOTA_CACHE["reset"]

    def quota_wait(self, cancel: Callable[[], bool] = lambda: False, what: str = "yükleme") -> None:
        """İstek hakkı koruması açıksa ve kalan hak sınırın altındaysa yenilenene kadar bekle (dakikada bir bakar)."""
        logged = False
        while QUOTA.get("on"):
            rem, reset = self.quota()
            if not quota_paused(rem):
                if logged:
                    self.log(f"  ▶ GitHub istek hakkı yenilendi ({rem}): {what} devam ediyor")
                return
            if not logged:
                logged = True
                msg = (f"  ⏸ GitHub istek hakkı {rem} (sınır {QUOTA.get('min')}): {what} "
                       f"{time.strftime('%H:%M', time.localtime(reset))}'de devam edecek (TV etkilenmez)")
                self.log(msg)
                notify = QUOTA.get("notify")
                if notify:
                    try:
                        notify(msg)       # süren işlerin kartına da yaz
                    except Exception:  # noqa: BLE001
                        pass
            for _ in range(60):
                if cancel():
                    raise Cancelled()
                time.sleep(1)

    def upload_asset(
        self, rel: dict, path: Path, name: str, ctype: str,
        existing: dict[str, dict] | None = None,
        progress: Callable[[int], None] = lambda n: None,
        cancel: Callable[[], bool] = lambda: False,
        offset: int = 0,
        length: int | None = None,
    ) -> str:
        """Dosyayı (ya da offset/length ile bir dilimini) yükler; aynı adla ve aynı boyutta zaten yüklenmişse atlar."""
        size = length if length is not None else path.stat().st_size - offset
        if not upload_allowed(name, ctype, size):
            raise GitHubError(0, f"GitHub'a medya yükleme kapalı: {name} ({size / 1e6:.1f} MB, {ctype}) yüklenmedi. "
                                 "Yeni düzende videolar GitHub'a gitmez; bilgisayar linkten anlık yayın yapar.")
        self.quota_wait(cancel)      # istek hakkı koruması: sınırın altındaysa yüklemeyi yenilenmeye kadar beklet
        if existing is not None and name in existing:
            a = existing[name]
            if a.get("state") == "uploaded" and a.get("size") == size:
                self.log(f"  ✓ {name} zaten yüklü, atlanıyor")
                progress(size)
                return a["url"]
            self.delete_asset(a["id"])
        url = rel["upload_url"].split("{")[0]
        for attempt in range(5):
            # GitHub "chunked" gönderimi kabul etmez: boyutu bilinen dosya nesnesi veriyoruz
            with open(path, "rb") as f:
                f.seek(offset)
                body = _UploadBody(f, size, progress, cancel)
                try:
                    r = self.s.post(url, params={"name": name}, data=body,
                                    headers={"Content-Type": ctype}, timeout=(30, 600))
                except Cancelled:
                    raise
                except requests.RequestException as e:
                    r = None
                    self.log(f"  yükleme hatası ({attempt + 1}/5): {e}")
                sent = [body.sent]
            if r is not None and r.status_code == 201:
                return r.json()["url"]
            if r is not None:
                self.log(f"  yükleme başarısız ({r.status_code}): {r.text[:200]}")
            progress(-sent[0])
            for a in self.release_assets(rel["id"]):
                if a["name"] == name:
                    self.delete_asset(a["id"])
            time.sleep(5 * (attempt + 1))
        raise GitHubError(0, f"{name} yüklenemedi. İnternet bağlantını kontrol edip 'Tekrar dene'ye bas; yüklenen parçalar atlanır.")

    # ------------------------------------------------------------ iş akışları

    def dispatch_workflow(self, workflow_file: str, ref: str | None = None) -> None:
        ref = ref or self.repo_info()["default_branch"]
        self.req("POST", f"/actions/workflows/{workflow_file}/dispatches", json={"ref": ref})


class _UploadBody:
    """requests'in Content-Length ile akıtarak göndermesi için boyutu bilinen, ilerleme bildiren dosya sarmalayıcı."""

    def __init__(self, f, size: int, progress: Callable[[int], None], cancel: Callable[[], bool]):
        self.f, self.size, self.progress, self.cancel = f, size, progress, cancel
        self.sent = 0

    def __len__(self) -> int:
        return self.size

    def read(self, n: int = -1) -> bytes:
        if self.cancel():
            raise Cancelled()
        # http.client 8 KB ister; daha büyük parça vermek gönderimi belirgin hızlandırır
        chunk = self.f.read(min(1024 * 1024, self.size - self.sent))
        if chunk:
            self.sent += len(chunk)
            self.progress(len(chunk))
        return chunk
