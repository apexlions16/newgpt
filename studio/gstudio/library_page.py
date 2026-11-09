"""Kütüphane: yüklenmiş tüm yapımlar posterleriyle."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import QComboBox, QHBoxLayout, QLineEdit, QListView, QListWidget, QListWidgetItem, QVBoxLayout, QWidget

from gflix import manage

from .images import ImageCache
from .style import button, error_box, fit_poster, label, placeholder_poster
from .workers import run_async


def _norm(s: str) -> str:
    import unicodedata
    s = s.lower().replace("ı", "i")
    return "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")


class LibraryPage(QWidget):
    open_title = Signal(dict)
    loaded = Signal()

    def __init__(self, config):
        super().__init__()
        self.setObjectName("page")
        self.config = config
        self.entries: list[dict] = []   # katalog girdileri + işlenmeyi bekleyen kayıtlar
        self.generated = None

        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 24, 28, 24)
        head = QHBoxLayout()
        head.addWidget(label("Kütüphane", "h1"))
        head.addSpacing(16)
        self.search = QLineEdit()
        self.search.setPlaceholderText("🔍  Ara (Türkçe, İngilizce ya da orijinal ad)")
        self.search.textChanged.connect(self.render)
        head.addWidget(self.search, 1)
        self.filter = QComboBox()
        for k, n in (("all", "Hepsi"), ("movie", "Filmler"), ("series", "Diziler"), ("trailer", "Fragmanı olanlar"), ("notrailer", "Fragmanı olmayanlar")):
            self.filter.addItem(n, k)
        self.filter.currentIndexChanged.connect(self.render)
        head.addWidget(self.filter)
        head.addWidget(button("↻ Yenile", "", self.refresh))
        head.addWidget(button("Kataloğu yeniden oluştur", "", self.rebuild))
        lay.addLayout(head)
        self.status = label("", "hint")
        lay.addWidget(self.status)

        self.grid = QListWidget()
        self.grid.setViewMode(QListView.IconMode)
        self.grid.setIconSize(QSize(150, 225))
        self.grid.setGridSize(QSize(176, 290))
        self.grid.setResizeMode(QListView.Adjust)
        self.grid.setMovement(QListView.Static)
        self.grid.setWordWrap(True)
        self.grid.setSpacing(6)
        self.grid.itemDoubleClicked.connect(lambda it: self.open_title.emit(it.data(Qt.UserRole)))
        self.grid.itemActivated.connect(lambda it: self.open_title.emit(it.data(Qt.UserRole)))
        lay.addWidget(self.grid, 1)

    def titles_for_picker(self) -> list[tuple[str, str, str]]:
        return [(e["id"], e.get("title", e["id"]), e.get("type", "movie")) for e in self.entries]

    def tmdb_index(self) -> list[tuple[str, str, str, int | None]]:
        return [(e["id"], e.get("title", ""), e.get("type", ""), e.get("tmdbId")) for e in self.entries]

    def refresh(self):
        gh = self.config.gh()
        if gh is None:
            self.status.setText("Başlamak için Ayarlar'dan depo adını ve GitHub token'ını gir.")
            return
        self.status.setText("Yükleniyor…")

        def work():
            cat = manage.load_catalog(gh)
            ids = manage.list_item_ids(gh)
            return cat, ids
        run_async(work, on_done=self._loaded, on_error=lambda m: self.status.setText("Hata: " + m))

    def _loaded(self, res):
        cat, ids = res
        self.generated = cat.get("generatedAt")
        entries = list(cat.get("titles", []))
        known = {e["id"] for e in entries}
        for i in ids:
            if i not in known:
                entries.append({"id": i, "title": i, "type": "series" if i.startswith("series-") else "movie", "_pending": True})
        present = set(ids)
        for e in entries:
            if e["id"] not in present and not e.get("_pending"):
                e["_deleted"] = True  # silindi, katalog henüz güncellenmedi
        self.entries = [e for e in entries if not e.get("_deleted")]
        self.render()
        self.loaded.emit()

    def render(self):
        q = _norm(self.search.text().strip())
        f = self.filter.currentData()
        self.grid.clear()
        shown = 0
        for e in self.entries:
            names = [e.get("title", "")] + list((e.get("names") or {}).values())
            if q and not any(q in _norm(n) for n in names if n):
                continue
            if f in ("movie", "series") and e.get("type") != f:
                continue
            if f == "trailer" and not e.get("trailer"):
                continue
            if f == "notrailer" and e.get("trailer"):
                continue
            text = e.get("title", e["id"])
            if e.get("_pending"):
                text += "\n⏳ işleniyor"
            elif e.get("type") == "series":
                eps = sum(len(s.get("episodes", [])) for s in e.get("seasons", []))
                text += f"\n{eps} bölüm"
            elif e.get("year"):
                text += f"\n{e['year']}"
            if e.get("trailer"):
                text += " · 🎞️"
            item = QListWidgetItem(QIcon(placeholder_poster(e.get("title", ""))), text)
            item.setData(Qt.UserRole, e)
            item.setTextAlignment(Qt.AlignHCenter | Qt.AlignTop)
            self.grid.addItem(item)
            ImageCache.get().load(e.get("poster"), lambda pm, it=item: it.setIcon(QIcon(fit_poster(pm))))
            shown += 1
        total = len(self.entries)
        when = (self.generated or "").replace("T", " ").replace("Z", " UTC")
        self.status.setText(f"{shown}/{total} yapım" + (f" · katalog güncellemesi: {when}" if when else "")
                            + " · Düzenlemek için çift tıkla")

    def rebuild(self):
        gh = self.config.gh()
        if not gh:
            return
        run_async(manage.rebuild_catalog, gh,
                  on_done=lambda _: self.status.setText("Katalog güncellemesi başlatıldı. 1-3 dakika içinde 'Yenile'ye bas."),
                  on_error=lambda m: error_box(self, "Katalog tetiklenemedi. Token'ın 'Actions: Read and write' yetkisi olmalı.\n\n" + m))
