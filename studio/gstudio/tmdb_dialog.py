"""TMDB'de arama yapıp posterleriyle doğru yapımı seçtiren pencere."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (QButtonGroup, QDialog, QHBoxLayout, QLineEdit, QListWidget, QListWidgetItem,
                               QRadioButton, QSpinBox, QVBoxLayout)

from .images import ImageCache
from .style import button, error_box, fit_poster, label, placeholder_poster
from .workers import run_async


class TmdbSearchDialog(QDialog):
    def __init__(self, parent, tmdb, query: str, kind: str = "movie", year: int | None = None):
        super().__init__(parent)
        self.setWindowTitle("TMDB'de Ara")
        self.resize(720, 620)
        self.tmdb = tmdb
        self.result: dict | None = None
        self.kind = "tv" if kind in ("episode", "series", "tv") else "movie"

        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 20, 20, 20)
        lay.addWidget(label("Doğru yapımı seç", "h1"))
        row = QHBoxLayout()
        self.q = QLineEdit(query)
        self.q.setPlaceholderText("Film ya da dizi adı (Türkçe ya da İngilizce)")
        self.q.returnPressed.connect(self.search)
        row.addWidget(self.q, 1)
        self.year = QSpinBox()
        self.year.setRange(0, 2100)
        self.year.setSpecialValueText("Yıl")
        self.year.setValue(year or 0)
        row.addWidget(self.year)
        self.movie_rb = QRadioButton("Film")
        self.tv_rb = QRadioButton("Dizi")
        grp = QButtonGroup(self)
        grp.addButton(self.movie_rb)
        grp.addButton(self.tv_rb)
        (self.tv_rb if self.kind == "tv" else self.movie_rb).setChecked(True)
        row.addWidget(self.movie_rb)
        row.addWidget(self.tv_rb)
        row.addWidget(button("Ara", "primary", self.search))
        lay.addLayout(row)

        self.list = QListWidget()
        self.list.setIconSize(QSize(62, 92))
        self.list.setSpacing(2)
        self.list.itemDoubleClicked.connect(lambda *_: self.accept_current())
        lay.addWidget(self.list, 1)
        self.status = label("", "hint")
        lay.addWidget(self.status)
        btns = QHBoxLayout()
        btns.addStretch(1)
        btns.addWidget(button("Vazgeç", "", self.reject))
        btns.addWidget(button("Seç", "primary", self.accept_current))
        lay.addLayout(btns)
        if query:
            self.search()

    def search(self):
        q = self.q.text().strip()
        if not q:
            return
        kind = "tv" if self.tv_rb.isChecked() else "movie"
        self.status.setText("Aranıyor…")
        self.list.clear()
        run_async(self.tmdb.search, kind, q, self.year.value() or None,
                  on_done=lambda res, k=kind: self.show_results(res, k),
                  on_error=lambda msg: self.status.setText("Hata: " + msg))

    def show_results(self, results: list[dict], kind: str):
        self.list.clear()
        self.status.setText(f"{len(results)} sonuç" if results else "Sonuç bulunamadı. Farklı bir ad ya da İngilizce adını dene.")
        for r in results:
            text = f"{r['title']}" + (f"  ({r['year']})" if r.get("year") else "")
            if r.get("original_title") and r["original_title"] != r["title"]:
                text += f"\n{r['original_title']}"
            text += f"\nTMDB #{r['id']}"
            item = QListWidgetItem(QIcon(placeholder_poster("", 62, 92)), text)
            r["kind"] = kind
            item.setData(Qt.UserRole, r)
            item.setToolTip(r.get("overview", "")[:400])
            self.list.addItem(item)
            ImageCache.get().load(r.get("poster"), lambda pm, it=item: it.setIcon(QIcon(fit_poster(pm, 62, 92))))
        if results:
            self.list.setCurrentRow(0)

    def accept_current(self):
        item = self.list.currentItem()
        if not item:
            error_box(self, "Listeden bir yapım seç.")
            return
        self.result = item.data(Qt.UserRole)
        self.accept()
