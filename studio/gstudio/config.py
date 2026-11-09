"""Ayarlar: depo adı ve tercihler QSettings'te, anahtarlar Windows Kimlik Bilgileri Yöneticisi'nde."""
from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSettings, QStandardPaths

from gflix.github import GitHub
from gflix.media import EncodeOptions
from gflix.tmdb import TMDB

try:
    import keyring
except Exception:  # pragma: no cover
    keyring = None

SERVICE = "G-Flix Stüdyo"
DEFAULT_REPO = "thedrowned925/g-flix"


class Config:
    def __init__(self):
        self.qs = QSettings("GFlix", "Studio")

    # ---- düz ayarlar
    def get(self, key: str, default=None):
        v = self.qs.value(key, default)
        return default if v is None else v

    def set(self, key: str, value) -> None:
        self.qs.setValue(key, value)

    @property
    def repo(self) -> str:
        return str(self.get("repo", DEFAULT_REPO)).strip()

    @property
    def workdir(self) -> Path:
        default = Path(QStandardPaths.writableLocation(QStandardPaths.AppLocalDataLocation) or Path.home() / ".gflix-studio") / "work"
        p = Path(str(self.get("workdir", str(default))))
        p.mkdir(parents=True, exist_ok=True)
        return p

    def encode_options(self) -> EncodeOptions:
        return EncodeOptions(
            video_mode=str(self.get("video_mode", "auto")),
            max_height=int(self.get("max_height", 1080)),
            crf=int(self.get("crf", 21)),
            preset=str(self.get("preset", "veryfast")),
        )

    @property
    def parallel(self) -> int:
        return max(1, min(8, int(self.get("parallel", 4))))

    # ---- gizli anahtarlar
    def secret(self, name: str) -> str:
        if keyring is not None:
            try:
                v = keyring.get_password(SERVICE, name)
                if v:
                    return v
            except Exception:
                pass
        return str(self.get(f"secret/{name}", "") or "")

    def set_secret(self, name: str, value: str) -> None:
        value = value.strip()
        if keyring is not None:
            try:
                if value:
                    keyring.set_password(SERVICE, name, value)
                else:
                    try:
                        keyring.delete_password(SERVICE, name)
                    except Exception:
                        pass
                self.qs.remove(f"secret/{name}")
                return
            except Exception:
                pass
        self.set(f"secret/{name}", value)  # anahtar kasası yoksa yedek

    @property
    def github_token(self) -> str:
        return self.secret("github_token")

    @property
    def tmdb_key(self) -> str:
        return self.secret("tmdb_key")

    def gh(self, log=print) -> GitHub | None:
        return GitHub(self.repo, self.github_token, log) if self.github_token and self.repo else None

    def tmdb(self) -> TMDB | None:
        return TMDB(self.tmdb_key) if self.tmdb_key else None

    @property
    def ready(self) -> bool:
        return bool(self.github_token and self.repo)
