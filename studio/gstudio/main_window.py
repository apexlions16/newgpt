"""Ana pencere: kenar menü + sayfalar."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt
from PySide6.QtWidgets import (QHBoxLayout, QListWidget, QListWidgetItem, QMainWindow, QMessageBox, QStackedWidget,
                               QVBoxLayout, QWidget)

from . import __version__
from .config import Config
from .editor import EditorDialog
from .library_page import LibraryPage
from .links_page import LinksPage
from .queue_page import QueuePage
from .settings_page import SettingsPage
from .style import app_icon, label, logo_label
from .upload_page import UploadPage


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.config = Config()
        self.setWindowTitle("G-Flix Stüdyo")
        self.setWindowIcon(app_icon())
        self.resize(1280, 820)
        self.setMinimumSize(1000, 640)

        central = QWidget()
        root = QHBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        side = QWidget()
        side.setObjectName("sidebar")
        side.setFixedWidth(220)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(0, 20, 0, 16)
        logo = logo_label("G-FLIX", 40)
        logo.setContentsMargins(20, 0, 0, 0)
        sl.addWidget(logo)
        sub = label("STÜDYO", "hint")
        sub.setContentsMargins(22, 0, 0, 12)
        sub.setStyleSheet("letter-spacing: 4px; color: #b3b3b3;")
        sl.addWidget(sub)
        self.nav = QListWidget()
        self.nav.setObjectName("nav")
        self.nav.setIconSize(QSize(18, 18))
        for text in ("📚   Kütüphane", "⬆️   Yükle", "⏳   Kuyruk", "🔗   Linkten Ekle", "⚙️   Ayarlar"):
            self.nav.addItem(QListWidgetItem(text))
        sl.addWidget(self.nav, 1)
        sl.addWidget(label(f"   Sürüm {__version__}", "hint"))
        root.addWidget(side)

        self.stack = QStackedWidget()
        self.queue = QueuePage(self.config)
        self.library = LibraryPage(self.config)
        self.upload = UploadPage(self.config, self.queue, self.library.titles_for_picker)
        self.upload.set_tmdb_index(self.library.tmdb_index)
        self.settings = SettingsPage(self.config)
        self.links = LinksPage(self.config)
        for page in (self.library, self.upload, self.queue, self.links, self.settings):
            self.stack.addWidget(page)
        root.addWidget(self.stack, 1)
        self.setCentralWidget(central)

        self.nav.currentRowChanged.connect(self.stack.setCurrentIndex)
        self.queue.count_changed.connect(self._queue_count)
        self.queue.job_finished.connect(lambda ok: ok and self.library.refresh())
        self.library.open_title.connect(self.open_editor)
        self.settings.saved.connect(self.library.refresh)

        if self.config.ready:
            self.nav.setCurrentRow(0)
            self.library.refresh()
        else:
            self.nav.setCurrentRow(4)

    def _queue_count(self, n: int):
        self.nav.item(2).setText(f"⏳   Kuyruk  ({n})" if n else "⏳   Kuyruk")

    def open_editor(self, entry: dict):
        if entry.get("_pending"):
            QMessageBox.information(self, "İşleniyor", "Bu yapım yeni yüklendi; GitHub Actions kataloğu güncelleyince düzenleyebilirsin.\n'Yenile'ye basarak kontrol et.")
            return
        dlg = EditorDialog(self, self.config, entry)
        dlg.changed.connect(self.library.refresh)
        dlg.request_trailer.connect(lambda job: (self.queue.add(job), self._queue_count(sum(1 for e in self.queue.entries if e.status in ('waiting', 'running')))))
        dlg.exec()

    def closeEvent(self, e):
        if self.queue.busy():
            if QMessageBox.question(self, "Yükleme sürüyor",
                                    "Kuyrukta devam eden işler var. Çıkarsan yarıda kalır (sonra kaldığı yerden devam edebilir).\nYine de çıkılsın mı?",
                                    QMessageBox.Yes | QMessageBox.No, QMessageBox.No) != QMessageBox.Yes:
                e.ignore()
                return
        self.queue.shutdown()
        e.accept()
