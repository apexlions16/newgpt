"""Yükleme kuyruğu: işler sırayla, arka planda yürütülür."""
from __future__ import annotations

import threading
import time
import traceback
from dataclasses import dataclass, field
from itertools import count

from PySide6.QtCore import QObject, Qt, QThread, Signal
from PySide6.QtWidgets import (QHBoxLayout, QLabel, QListWidget, QListWidgetItem, QPlainTextEdit, QProgressBar,
                               QSplitter, QVBoxLayout, QWidget)

from gflix.uploader import STAGES, Reporter, UploadJob, run_job
from gflix.util import Cancelled

from .style import button, label

_ids = count(1)
WEIGHTS = {k: w for k, _, w in STAGES}
STAGE_NAMES = {k: n for k, n, _ in STAGES}


@dataclass
class QueueEntry:
    job: UploadJob
    id: int = field(default_factory=lambda: next(_ids))
    status: str = "waiting"   # waiting | running | done | error | cancelled
    progress: float = 0.0
    text: str = "Sırada"
    log: list[str] = field(default_factory=list)
    cancel_flag: threading.Event = field(default_factory=threading.Event)
    stage_done: dict = field(default_factory=dict)


class _Bus(QObject):
    changed = Signal(int)          # entry id
    logged = Signal(int, str)
    finished = Signal(int, bool)   # entry id, başarılı mı


class _EntryReporter(Reporter):
    def __init__(self, entry: QueueEntry, bus: _Bus):
        self.e, self.bus = entry, bus
        self._last = 0.0

    def log(self, msg: str) -> None:
        self.e.log.append(msg)
        self.bus.logged.emit(self.e.id, msg)

    def stage(self, name: str, fraction: float, text: str = "") -> None:
        self.e.stage_done[name] = fraction
        self.e.progress = sum(WEIGHTS[k] * self.e.stage_done.get(k, 0) for k in WEIGHTS)
        if text:
            self.e.text = text
        elif name in STAGE_NAMES:
            self.e.text = STAGE_NAMES[name]
        now = time.time()
        if now - self._last > 0.2 or fraction >= 1:
            self._last = now
            self.bus.changed.emit(self.e.id)


class QueueWorker(QThread):
    def __init__(self, page: "QueuePage"):
        super().__init__()
        self.page = page
        self.wake = threading.Event()
        self.stopping = False

    def run(self):
        while not self.stopping:
            entry = next((e for e in self.page.entries if e.status == "waiting"), None)
            if entry is None:
                self.wake.wait(1.0)
                self.wake.clear()
                continue
            self.process(entry)

    def process(self, e: QueueEntry):
        bus = self.page.bus
        e.status, e.text, e.progress, e.stage_done = "running", "Başlıyor", 0.0, {}
        bus.changed.emit(e.id)
        rep = _EntryReporter(e, bus)
        ok = False
        try:
            gh = self.page.config.gh(rep.log)
            if gh is None:
                raise RuntimeError("Önce Ayarlar'dan GitHub token'ını gir.")
            run_job(e.job, gh, self.page.config.workdir, rep, cancel=e.cancel_flag.is_set)
            e.status, e.text, e.progress = "done", "Tamamlandı ✓", 1.0
            ok = True
        except Cancelled:
            e.status, e.text = "cancelled", "İptal edildi (tekrar başlatınca kaldığı yerden devam eder)"
        except Exception as ex:  # noqa: BLE001
            rep.log(traceback.format_exc())
            e.status, e.text = "error", f"Hata: {ex}"
        bus.changed.emit(e.id)
        bus.finished.emit(e.id, ok)


class EntryWidget(QWidget):
    def __init__(self, entry: QueueEntry, page: "QueuePage"):
        super().__init__()
        self.entry, self.page = entry, page
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 10, 12, 10)
        top = QHBoxLayout()
        icon = {"movie": "🎬", "episode": "📺", "trailer": "🎞️"}.get(entry.job.kind, "🎬")
        self.title = label(f"{icon}  {entry.job.display}", "h2")
        top.addWidget(self.title, 1)
        self.cancel_btn = button("İptal", "link", self.cancel)
        self.retry_btn = button("Tekrar dene", "link", self.retry)
        self.remove_btn = button("Kaldır", "link", self.remove)
        for b in (self.cancel_btn, self.retry_btn, self.remove_btn):
            top.addWidget(b)
        lay.addLayout(top)
        self.bar = QProgressBar()
        self.bar.setRange(0, 1000)
        self.bar.setTextVisible(False)
        self.bar.setFixedHeight(6)
        lay.addWidget(self.bar)
        self.status = label("", "hint")
        lay.addWidget(self.status)
        self.refresh()

    def refresh(self):
        e = self.entry
        self.bar.setValue(int(e.progress * 1000))
        color = {"error": "#ff6b6b", "done": "#46d369", "cancelled": "#e8a33d"}.get(e.status, "#b3b3b3")
        self.status.setStyleSheet(f"color: {color};")
        self.status.setText(e.text)
        self.cancel_btn.setVisible(e.status in ("waiting", "running"))
        self.retry_btn.setVisible(e.status in ("error", "cancelled"))
        self.remove_btn.setVisible(e.status != "running")

    def cancel(self):
        e = self.entry
        if e.status == "waiting":
            e.status, e.text = "cancelled", "İptal edildi"
            self.refresh()
        else:
            e.cancel_flag.set()
            e.text = "İptal ediliyor…"
            self.refresh()

    def retry(self):
        self.entry.cancel_flag.clear()
        self.entry.status, self.entry.text, self.entry.progress = "waiting", "Sırada", 0.0
        self.refresh()
        self.page.kick()

    def remove(self):
        self.page.remove_entry(self.entry)


class QueuePage(QWidget):
    count_changed = Signal(int)
    job_finished = Signal(bool)

    def __init__(self, config):
        super().__init__()
        self.setObjectName("page")
        self.config = config
        self.entries: list[QueueEntry] = []
        self.widgets: dict[int, tuple[QListWidgetItem, EntryWidget]] = {}
        self.bus = _Bus()
        self.bus.changed.connect(self._changed)
        self.bus.logged.connect(self._logged)
        self.bus.finished.connect(lambda _id, ok: self.job_finished.emit(ok))

        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 24, 28, 24)
        head = QHBoxLayout()
        head.addWidget(label("Yükleme Kuyruğu", "h1"), 1)
        head.addWidget(button("Bitenleri temizle", "", self.clear_done))
        lay.addLayout(head)
        lay.addWidget(label("İşler sırayla yapılır. Uygulamayı kapatırsan ya da iptal edersen, tekrar başlattığında "
                            "hazırlanmış dosyalar ve yüklenmiş parçalar atlanır.", "hint", wrap=True))
        split = QSplitter(Qt.Vertical)
        self.list = QListWidget()
        self.list.currentRowChanged.connect(self._show_log)
        split.addWidget(self.list)
        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setPlaceholderText("Ayrıntılı kayıt burada görünür.")
        self.log.setStyleSheet("font-family: Consolas, monospace; font-size: 12px;")
        split.addWidget(self.log)
        split.setSizes([400, 200])
        lay.addWidget(split, 1)
        self.empty = label("Kuyruk boş. 'Yükle' sayfasından içerik ekle.", "muted")
        self.empty.setAlignment(Qt.AlignCenter)
        lay.addWidget(self.empty)

        self.worker = QueueWorker(self)
        self.worker.start()

    # ---- dışarıdan
    def add(self, job: UploadJob) -> None:
        e = QueueEntry(job)
        self.entries.append(e)
        item = QListWidgetItem()
        w = EntryWidget(e, self)
        item.setSizeHint(w.sizeHint())
        self.list.addItem(item)
        self.list.setItemWidget(item, w)
        self.widgets[e.id] = (item, w)
        self._update_count()
        self.kick()

    def kick(self):
        self.worker.wake.set()

    def busy(self) -> bool:
        return any(e.status in ("running", "waiting") for e in self.entries)

    def shutdown(self):
        for e in self.entries:
            if e.status == "running":
                e.cancel_flag.set()
        self.worker.stopping = True
        self.worker.wake.set()
        self.worker.wait(5000)

    # ---- iç
    def remove_entry(self, e: QueueEntry):
        if e.status == "running":
            return
        self.entries.remove(e)
        item, _ = self.widgets.pop(e.id)
        self.list.takeItem(self.list.row(item))
        self._update_count()

    def clear_done(self):
        for e in [e for e in self.entries if e.status == "done"]:
            self.remove_entry(e)

    def _update_count(self):
        self.empty.setVisible(not self.entries)
        self.count_changed.emit(sum(1 for e in self.entries if e.status in ("waiting", "running")))

    def _changed(self, entry_id: int):
        if entry_id in self.widgets:
            self.widgets[entry_id][1].refresh()
        self._update_count()

    def _logged(self, entry_id: int, msg: str):
        cur = self.list.currentRow()
        if 0 <= cur < len(self.entries) and self.entries[cur].id == entry_id:
            self.log.appendPlainText(msg)
        elif cur < 0:
            self.log.appendPlainText(msg)

    def _show_log(self, row: int):
        self.log.clear()
        if 0 <= row < len(self.entries):
            self.log.setPlainText("\n".join(self.entries[row].log))
