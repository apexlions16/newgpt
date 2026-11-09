"""Poster ve görselleri internetten indirip önbellekte tutar."""
from __future__ import annotations

from collections import defaultdict
from typing import Callable

from PySide6.QtCore import QStandardPaths, QUrl
from PySide6.QtGui import QPixmap
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkDiskCache, QNetworkReply, QNetworkRequest


class ImageCache:
    _instance: "ImageCache | None" = None

    @classmethod
    def get(cls) -> "ImageCache":
        if cls._instance is None:
            cls._instance = ImageCache()
        return cls._instance

    def __init__(self):
        self.nam = QNetworkAccessManager()
        disk = QNetworkDiskCache(self.nam)
        disk.setCacheDirectory((QStandardPaths.writableLocation(QStandardPaths.CacheLocation) or ".") + "/images")
        disk.setMaximumCacheSize(400 * 1024 * 1024)
        self.nam.setCache(disk)
        self.mem: dict[str, QPixmap] = {}
        self.waiting: dict[str, list[Callable[[QPixmap], None]]] = defaultdict(list)

    def load(self, url: str | None, callback: Callable[[QPixmap], None]) -> None:
        if not url:
            return
        if url in self.mem:
            callback(self.mem[url])
            return
        self.waiting[url].append(callback)
        if len(self.waiting[url]) > 1:
            return
        req = QNetworkRequest(QUrl(url))
        req.setAttribute(QNetworkRequest.CacheLoadControlAttribute, QNetworkRequest.PreferCache)
        reply = self.nam.get(req)
        reply.finished.connect(lambda r=reply, u=url: self._done(r, u))

    def _done(self, reply: QNetworkReply, url: str) -> None:
        callbacks = self.waiting.pop(url, [])
        if reply.error() == QNetworkReply.NoError:
            pm = QPixmap()
            if pm.loadFromData(reply.readAll()):
                self.mem[url] = pm
                for cb in callbacks:
                    try:
                        cb(pm)
                    except RuntimeError:
                        pass  # pencere kapanmış olabilir
        reply.deleteLater()
