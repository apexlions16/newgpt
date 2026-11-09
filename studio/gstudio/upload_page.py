"""Yükleme sayfası: sürükle-bırak, otomatik tanıma, TMDB eşleştirme, ses/altyazı ayarları."""
from __future__ import annotations

import copy
from dataclasses import dataclass, field
from pathlib import Path

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import (QCheckBox, QComboBox, QFileDialog, QFormLayout, QFrame, QHBoxLayout, QHeaderView,
                               QLineEdit, QListWidget, QListWidgetItem, QScrollArea, QSpinBox, QSplitter,
                               QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget)

from gflix import media
from gflix.media import AudioTrack, MediaInfo, SubTrack
from gflix.uploader import UploadJob
from gflix.util import LANG_LABEL, item_id_for, norm_lang, parse_filename

from .style import button, error_box, label, lang_combo
from .tmdb_dialog import TmdbSearchDialog
from .workers import run_async

VIDEO_EXT = {".mkv", ".mp4", ".m4v", ".mov", ".avi", ".ts", ".webm", ".wmv", ".flv", ".mpg", ".mpeg"}
SUB_EXT = {".srt", ".ass", ".ssa", ".vtt"}
AUDIO_EXT = {".m4a", ".aac", ".ac3", ".eac3", ".mp3", ".flac", ".wav", ".mka", ".dts", ".opus", ".ogg"}
KINDS = [("movie", "Film"), ("episode", "Dizi bölümü"), ("trailer", "Fragman")]


@dataclass
class Pending:
    path: str
    kind: str = "movie"
    title: str = ""
    parsed_title: str = ""
    item_id: str | None = None   # mevcut yapıma eklemek için
    tmdb: int | None = None
    tmdb_label: str = ""
    year: int | None = None
    season: int | None = None
    episode: int | None = None
    info: MediaInfo | None = None
    error: str = ""
    extra_subs: list[SubTrack] = field(default_factory=list)

    @property
    def name(self) -> str:
        return Path(self.path).name

    def summary(self) -> str:
        if self.error:
            return "⚠ " + self.error
        if self.info is None:
            return "İnceleniyor…"
        k = dict(KINDS)[self.kind]
        bits = [k]
        if self.kind == "episode" and self.season is not None:
            bits.append(f"S{self.season:02d}E{(self.episode or 0):02d}")
        bits.append(self.title or "—")
        langs = [LANG_LABEL.get(a.lang, a.lang) for a in self.info.audio if a.include]
        if langs and self.kind != "trailer":
            bits.append("Ses: " + "/".join(langs))
        return " · ".join(bits)


class DropFrame(QFrame):
    dropped = Signal(list)

    def __init__(self):
        super().__init__()
        self.setObjectName("drop")
        self.setAcceptDrops(True)
        self.setMinimumHeight(110)
        lay = QVBoxLayout(self)
        t = label("🎬  Video dosyalarını ya da klasörü buraya sürükle", "h2")
        t.setAlignment(Qt.AlignCenter)
        lay.addWidget(t)
        h = label("Dizi bölümleri dosya adından tanınır (ör. Dizi.S01E02.mkv). Aynı klasördeki .srt altyazılar otomatik eklenir.", "hint")
        h.setAlignment(Qt.AlignCenter)
        lay.addWidget(h)

    def _hover(self, on: bool):
        self.setProperty("hover", "true" if on else "false")
        self.style().unpolish(self)
        self.style().polish(self)

    def dragEnterEvent(self, e):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()
            self._hover(True)

    def dragLeaveEvent(self, e):
        self._hover(False)

    def dropEvent(self, e):
        self._hover(False)
        self.dropped.emit([u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()])


class UploadPage(QWidget):
    def __init__(self, config, queue, library_provider):
        super().__init__()
        self.setObjectName("page")
        self.config, self.queue = config, queue
        self.library_provider = library_provider  # () -> list[(id, title, type)]
        self.pending: list[Pending] = []
        self.current: Pending | None = None
        self._loading = False

        root = QVBoxLayout(self)
        root.setContentsMargins(28, 24, 28, 24)
        head = QHBoxLayout()
        head.addWidget(label("İçerik Yükle", "h1"), 1)
        head.addWidget(button("Dosya seç…", "", self.pick_files))
        head.addWidget(button("Klasör seç…", "", self.pick_folder))
        root.addLayout(head)
        self.drop = DropFrame()
        self.drop.dropped.connect(self.add_paths)
        root.addWidget(self.drop)

        split = QSplitter(Qt.Horizontal)
        left = QWidget()
        ll = QVBoxLayout(left)
        ll.setContentsMargins(0, 8, 8, 0)
        ll.addWidget(label("Eklenecek dosyalar", "h2"))
        self.files = QListWidget()
        self.files.setObjectName("files")
        self.files.currentRowChanged.connect(self.select)
        ll.addWidget(self.files, 1)
        lb = QHBoxLayout()
        lb.addWidget(button("Listeden çıkar", "link", self.remove_current))
        lb.addStretch(1)
        ll.addLayout(lb)
        split.addWidget(left)

        self.form_scroll = QScrollArea()
        self.form_scroll.setWidgetResizable(True)
        self.form = QWidget()
        self.form.setObjectName("page")
        self.form_scroll.setWidget(self.form)
        self._build_form()
        split.addWidget(self.form_scroll)
        split.setSizes([380, 700])
        root.addWidget(split, 1)

        bottom = QHBoxLayout()
        self.hint = label("", "hint")
        bottom.addWidget(self.hint, 1)
        bottom.addWidget(button("Seçiliyi kuyruğa ekle", "", lambda: self.enqueue([self.current] if self.current else [])))
        bottom.addWidget(button("Hepsini kuyruğa ekle  ➜", "primary", lambda: self.enqueue(list(self.pending))))
        root.addLayout(bottom)
        self._set_form_enabled(False)

    # ------------------------------------------------------------ form
    def _build_form(self):
        lay = QVBoxLayout(self.form)
        lay.setContentsMargins(16, 8, 4, 8)
        self.file_label = label("Bir dosya seç", "h2", wrap=True)
        lay.addWidget(self.file_label)
        self.video_label = label("", "hint")
        lay.addWidget(self.video_label)

        fl = QFormLayout()
        self.formlayout = fl
        fl.setLabelAlignment(Qt.AlignRight)
        fl.setHorizontalSpacing(14)
        fl.setVerticalSpacing(10)
        self.kind = QComboBox()
        for k, n in KINDS:
            self.kind.addItem(n, k)
        self.kind.currentIndexChanged.connect(self._kind_changed)
        fl.addRow("Tür", self.kind)

        self.target = QComboBox()
        self.target.currentIndexChanged.connect(self._sync)
        fl.addRow("Hangi yapımın", self.target)

        trow = QHBoxLayout()
        self.title = QLineEdit()
        self.title.setPlaceholderText("Uygulamada görünecek ad (TMDB'den otomatik gelir)")
        self.title.textEdited.connect(self._sync)
        trow.addWidget(self.title, 1)
        trow.addWidget(button("TMDB'de Ara", "white", self.search_tmdb))
        fl.addRow("Ad", trow)
        self.tmdb_label = label("", "hint", wrap=True)
        fl.addRow("", self.tmdb_label)

        nrow = QHBoxLayout()
        self.year = QSpinBox()
        self.year.setRange(0, 2100)
        self.year.setSpecialValueText("—")
        self.year.valueChanged.connect(self._sync)
        nrow.addWidget(label("Yıl"))
        nrow.addWidget(self.year)
        self.season = QSpinBox()
        self.season.setRange(0, 99)
        self.season.valueChanged.connect(self._sync)
        self.episode = QSpinBox()
        self.episode.setRange(0, 999)
        self.episode.valueChanged.connect(self._sync)
        self.season_lbl, self.episode_lbl = label("Sezon"), label("Bölüm")
        nrow.addSpacing(16)
        nrow.addWidget(self.season_lbl)
        nrow.addWidget(self.season)
        nrow.addWidget(self.episode_lbl)
        nrow.addWidget(self.episode)
        nrow.addStretch(1)
        fl.addRow("", nrow)
        lay.addLayout(fl)

        self.apply_series_btn = button("Bu dizi bilgilerini ve ses dillerini aynı dizinin diğer bölümlerine uygula", "link", self.apply_to_series)
        lay.addWidget(self.apply_series_btn, 0, Qt.AlignLeft)

        lay.addSpacing(10)
        ah = QHBoxLayout()
        ah.addWidget(label("Ses kanalları", "h2"), 1)
        ah.addWidget(button("+ Ses dosyası ekle", "link", self.add_audio_file))
        lay.addLayout(ah)
        lay.addWidget(label("Uygulamada değiştirilebilir. Dil etiketi yanlışsa buradan düzelt. Fragmanlarda sadece ilk işaretli ses kullanılır.", "hint", wrap=True))
        self.audio_table = QTableWidget(0, 3)
        self.audio_table.setHorizontalHeaderLabels(["Dahil", "Kanal", "Dil"])
        self.audio_table.verticalHeader().setVisible(False)
        self.audio_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.audio_table.setColumnWidth(0, 60)
        self.audio_table.setColumnWidth(2, 150)
        self.audio_table.setMinimumHeight(130)
        self.audio_table.verticalHeader().setDefaultSectionSize(38)
        lay.addWidget(self.audio_table)

        lay.addSpacing(10)
        sh = QHBoxLayout()
        sh.addWidget(label("Altyazılar", "h2"), 1)
        sh.addWidget(button("+ Altyazı dosyası ekle", "link", self.add_sub_file))
        lay.addLayout(sh)
        lay.addWidget(label("Resim tabanlı altyazılar (Blu-ray PGS) desteklenmez; onların yerine .srt ekle.", "hint", wrap=True))
        self.sub_table = QTableWidget(0, 4)
        self.sub_table.setHorizontalHeaderLabels(["Dahil", "Kaynak", "Dil", "Zorunlu"])
        self.sub_table.verticalHeader().setVisible(False)
        self.sub_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.Stretch)
        self.sub_table.setColumnWidth(0, 60)
        self.sub_table.setColumnWidth(2, 150)
        self.sub_table.setColumnWidth(3, 80)
        self.sub_table.setMinimumHeight(130)
        self.sub_table.verticalHeader().setDefaultSectionSize(38)
        lay.addWidget(self.sub_table)
        lay.addStretch(1)

    def _set_form_enabled(self, on: bool):
        self.form.setEnabled(on)

    # ------------------------------------------------------------ dosya ekleme
    def pick_files(self):
        files, _ = QFileDialog.getOpenFileNames(self, "Video seç", "", "Videolar (*.mkv *.mp4 *.m4v *.mov *.avi *.ts *.webm);;Tüm dosyalar (*)")
        self.add_paths(files)

    def pick_folder(self):
        d = QFileDialog.getExistingDirectory(self, "Klasör seç")
        if d:
            self.add_paths([d])

    def add_paths(self, paths: list[str]):
        videos: list[Path] = []
        for p in map(Path, paths):
            if p.is_dir():
                videos += sorted(x for x in p.rglob("*") if x.suffix.lower() in VIDEO_EXT)
            elif p.suffix.lower() in VIDEO_EXT:
                videos.append(p)
            elif p.suffix.lower() in SUB_EXT and self.current:
                self._attach_sub(self.current, p)
                self._load_tables(self.current)
        known = {x.path for x in self.pending}
        for v in videos:
            if str(v) in known:
                continue
            parsed = parse_filename(v.name)
            item = Pending(path=str(v), kind=parsed["kind"], title=parsed["title"], parsed_title=parsed["title"],
                           year=parsed["year"], season=parsed["season"], episode=parsed["episode"])
            if item.kind == "trailer":
                item.item_id = self._guess_target(parsed["title"])
            # Aynı klasördeki altyazılar: film.tr.srt, film.en.srt …
            for s in sorted(v.parent.glob(v.stem + "*")):
                if s.suffix.lower() in SUB_EXT:
                    self._attach_sub(item, s)
            self.pending.append(item)
            self.files.addItem(QListWidgetItem(f"{item.name}\n{item.summary()}"))
            run_async(media.analyze, v, on_done=lambda info, it=item: self._analyzed(it, info),
                      on_error=lambda msg, it=item: self._failed(it, msg))
            if self.config.tmdb() and item.kind != "trailer" and item.title:
                self._auto_match(item)
        if self.files.currentRow() < 0 and self.pending:
            self.files.setCurrentRow(0)
        self._update_hint()

    def _attach_sub(self, item: Pending, path: Path):
        if any(s.file == str(path) for s in item.extra_subs):
            return
        stem_rest = path.stem.lower().split(".")
        lang = "und"
        for token in reversed(stem_rest[1:] if len(stem_rest) > 1 else []):
            n = norm_lang(token)
            if n in LANG_LABEL and n != "und":
                lang = n
                break
        forced = "forced" in path.stem.lower() or "zorunlu" in path.stem.lower()
        item.extra_subs.append(SubTrack(lang=lang, forced=forced, file=str(path)))

    def _guess_target(self, title: str) -> str | None:
        t = title.lower()
        for item_id, name, _ in self.library_provider():
            if name.lower() == t or name.lower() in t:
                return item_id
        return None

    def _auto_match(self, item: Pending):
        kind = "tv" if item.kind == "episode" else "movie"
        same = next((p for p in self.pending if p is not item and p.kind == "episode" and p.tmdb
                     and p.parsed_title.lower() == item.parsed_title.lower()), None)
        if same:
            item.tmdb, item.title, item.tmdb_label, item.item_id = same.tmdb, same.title, same.tmdb_label, same.item_id
            return
        tmdb = self.config.tmdb()

        def done(results):
            if results and not item.tmdb:
                r = results[0]
                item.tmdb, item.title = r["id"], r["title"]
                item.year = item.year or r.get("year")
                item.item_id = self._existing_id_for(r["id"])
                item.tmdb_label = f"Otomatik eşleşme: {r['title']} ({r.get('year') or '?'}) — TMDB #{r['id']}. Yanlışsa 'TMDB'de Ara'."
                self._refresh_item(item)
        run_async(tmdb.search, kind, item.parsed_title, item.year if kind == "movie" else None, on_done=done, on_error=lambda m: None)

    def _analyzed(self, item: Pending, info: MediaInfo):
        media.auto_select(info)
        # Harici altyazılar aynı dildeki gömülüyü ezer
        for ex in item.extra_subs:
            for s in info.subs:
                if s.lang == ex.lang and s.forced == ex.forced:
                    s.include = False
        info.subs += item.extra_subs
        item.extra_subs = []
        item.info = info
        self._refresh_item(item)

    def _failed(self, item: Pending, msg: str):
        item.error = msg.splitlines()[0] if msg else "Dosya okunamadı"
        self._refresh_item(item)

    def _refresh_item(self, item: Pending):
        if item not in self.pending:
            return
        row = self.pending.index(item)
        self.files.item(row).setText(f"{item.name}\n{item.summary()}")
        if item is self.current:
            self.select(row)
        self._update_hint()

    def remove_current(self):
        row = self.files.currentRow()
        if row < 0:
            return
        self.pending.pop(row)
        self.files.takeItem(row)
        if not self.pending:
            self.current = None
            self._set_form_enabled(False)
        self._update_hint()

    def _update_hint(self):
        ready = sum(1 for p in self.pending if p.info and not p.error)
        self.hint.setText(f"{len(self.pending)} dosya, {ready} tanesi hazır." if self.pending else "")

    # ------------------------------------------------------------ seçim / form senkronu
    def select(self, row: int):
        if row < 0 or row >= len(self.pending):
            return
        item = self.pending[row]
        self.current = item
        self._loading = True
        self._set_form_enabled(item.info is not None)
        self.file_label.setText(item.name)
        self.video_label.setText(
            ("⚠ " + item.error) if item.error else
            (f"{item.info.video_desc} · {int(item.info.duration // 60)} dk · {Path(item.path).stat().st_size / 1024**3:.2f} GB" if item.info else "İnceleniyor…"))
        self.kind.setCurrentIndex(self.kind.findData(item.kind))
        self.target.clear()
        self.target.addItem("— seç —", None)
        for item_id, name, typ in self.library_provider():
            self.target.addItem(f"{name}  ({'Dizi' if typ == 'series' else 'Film'})", item_id)
        if item.item_id:
            idx = self.target.findData(item.item_id)
            if idx < 0:
                self.target.addItem(item.title or item.item_id, item.item_id)
                idx = self.target.count() - 1
            self.target.setCurrentIndex(idx)
        self.title.setText(item.title)
        self.tmdb_label.setText(item.tmdb_label)
        self.tmdb_label.setVisible(bool(item.tmdb_label))
        self.year.setValue(item.year or 0)
        self.season.setValue(item.season or 0)
        self.episode.setValue(item.episode or 0)
        self._load_tables(item)
        self._loading = False
        self._apply_kind_visibility()

    def _apply_kind_visibility(self):
        k = self.kind.currentData()
        ep = k == "episode"
        tr = k == "trailer"
        for w in (self.season, self.episode, self.season_lbl, self.episode_lbl):
            w.setVisible(ep)
        self.apply_series_btn.setVisible(ep)
        self.formlayout.setRowVisible(self.target, tr)
        self.sub_table.setEnabled(not tr)

    def _kind_changed(self):
        if self._loading:
            return
        self._sync()
        self._apply_kind_visibility()

    def _sync(self, *_):
        if self._loading or not self.current:
            return
        it = self.current
        it.kind = self.kind.currentData()
        it.title = self.title.text().strip()
        it.year = self.year.value() or None
        it.season = self.season.value() if it.kind == "episode" else None
        it.episode = self.episode.value() if it.kind == "episode" else None
        if it.kind == "trailer":
            it.item_id = self.target.currentData()
            if it.item_id and not it.title:
                it.title = self.target.currentText().rsplit("  (", 1)[0]
        row = self.pending.index(it)
        self.files.item(row).setText(f"{it.name}\n{it.summary()}")

    def _load_tables(self, item: Pending):
        self.audio_table.setRowCount(0)
        self.sub_table.setRowCount(0)
        if not item.info:
            return
        for a in item.info.audio:
            r = self.audio_table.rowCount()
            self.audio_table.insertRow(r)
            cb = QCheckBox()
            cb.setChecked(a.include)
            cb.toggled.connect(lambda on, a=a: (setattr(a, "include", on), self._refresh_summary()))
            self.audio_table.setCellWidget(r, 0, self._center(cb))
            self.audio_table.setItem(r, 1, QTableWidgetItem(a.describe() or f"Ses #{a.stream_index}"))
            combo = lang_combo(a.lang)
            combo.currentIndexChanged.connect(lambda _i, a=a, c=combo: (setattr(a, "lang", c.currentData()), self._refresh_summary()))
            self.audio_table.setCellWidget(r, 2, combo)
        for s in item.info.subs:
            r = self.sub_table.rowCount()
            self.sub_table.insertRow(r)
            cb = QCheckBox()
            cb.setChecked(s.include and not s.bitmap)
            cb.setEnabled(not s.bitmap)
            cb.toggled.connect(lambda on, s=s: setattr(s, "include", on))
            self.sub_table.setCellWidget(r, 0, self._center(cb))
            desc = s.describe() + ("  (resim, desteklenmiyor)" if s.bitmap else "")
            self.sub_table.setItem(r, 1, QTableWidgetItem(desc))
            combo = lang_combo(s.lang)
            combo.setEnabled(not s.bitmap)
            combo.currentIndexChanged.connect(lambda _i, s=s, c=combo: setattr(s, "lang", c.currentData()))
            self.sub_table.setCellWidget(r, 2, combo)
            fc = QCheckBox()
            fc.setChecked(s.forced)
            fc.setEnabled(not s.bitmap)
            fc.toggled.connect(lambda on, s=s: setattr(s, "forced", on))
            self.sub_table.setCellWidget(r, 3, self._center(fc))

    def _refresh_summary(self):
        if self.current:
            self._refresh_item_text(self.current)

    def _refresh_item_text(self, it: Pending):
        row = self.pending.index(it)
        self.files.item(row).setText(f"{it.name}\n{it.summary()}")

    @staticmethod
    def _center(w: QWidget) -> QWidget:
        box = QWidget()
        l = QHBoxLayout(box)
        l.setContentsMargins(0, 0, 0, 0)
        l.setAlignment(Qt.AlignCenter)
        l.addWidget(w)
        return box

    # ------------------------------------------------------------ eylemler
    def add_audio_file(self):
        if not self.current or not self.current.info:
            return
        f, _ = QFileDialog.getOpenFileName(self, "Ses dosyası seç", str(Path(self.current.path).parent),
                                           "Ses (*.m4a *.aac *.ac3 *.eac3 *.mp3 *.flac *.wav *.mka *.dts *.opus);;Tüm dosyalar (*)")
        if f:
            lang = "tr" if any(x in Path(f).stem.lower() for x in ("tr", "turk", "dublaj")) else "und"
            self.current.info.audio.append(AudioTrack(stream_index=-1, lang=lang, file=f))
            self._load_tables(self.current)
            self._refresh_summary()

    def add_sub_file(self):
        if not self.current or not self.current.info:
            return
        files, _ = QFileDialog.getOpenFileNames(self, "Altyazı seç", str(Path(self.current.path).parent), "Altyazı (*.srt *.ass *.ssa *.vtt)")
        for f in files:
            before = len(self.current.extra_subs)
            self._attach_sub(self.current, Path(f))
            if len(self.current.extra_subs) > before:
                new = self.current.extra_subs.pop()
                if new.lang == "und":
                    new.lang = "tr"
                self.current.info.subs.append(new)
        self._load_tables(self.current)

    def search_tmdb(self):
        if not self.current:
            return
        tmdb = self.config.tmdb()
        if not tmdb:
            error_box(self, "TMDB araması için önce Ayarlar'a TMDB anahtarını gir.")
            return
        it = self.current
        dlg = TmdbSearchDialog(self, tmdb, it.parsed_title or it.title, it.kind, it.year)
        if dlg.exec() and dlg.result:
            r = dlg.result
            it.tmdb, it.title = r["id"], r["title"]
            it.year = r.get("year") or it.year
            if r.get("kind") == "tv" and it.kind == "movie":
                it.kind = "episode"
            it.tmdb_label = f"Seçildi: {r['title']} ({r.get('year') or '?'}) — TMDB #{r['id']}"
            # Bu TMDB numarası kütüphanede varsa aynı kimliği kullan (aynı diziye bölüm ekleme)
            it.item_id = self._existing_id_for(r["id"])
            self.select(self.pending.index(it))

    def _existing_id_for(self, tmdb_id: int) -> str | None:
        for item_id, _name, _typ, tid in getattr(self, "_tmdb_index", lambda: [])():
            if tid == tmdb_id:
                return item_id
        return None

    def set_tmdb_index(self, fn):
        self._tmdb_index = fn

    def apply_to_series(self):
        src = self.current
        if not src or src.kind != "episode":
            return
        n = 0
        for p in self.pending:
            if p is src or p.parsed_title.lower() != src.parsed_title.lower():
                continue
            p.kind, p.title, p.tmdb, p.tmdb_label, p.item_id, p.year = "episode", src.title, src.tmdb, src.tmdb_label, src.item_id, src.year
            if p.info and src.info:
                for a, b in zip(p.info.audio, src.info.audio):
                    a.lang, a.include = b.lang, b.include
            n += 1
            self._refresh_item_text(p)
        self.hint.setText(f"{n} bölüme uygulandı.")

    def enqueue(self, items: list[Pending]):
        added, problems = 0, []
        for it in items:
            if it is None:
                continue
            err = self._validate(it)
            if err:
                problems.append(f"• {it.name}: {err}")
                continue
            info = copy.deepcopy(it.info)
            job = UploadJob(kind=it.kind, input=it.path, title=it.title, item_id=it.item_id, tmdb=it.tmdb, year=it.year,
                            season=it.season, episode=it.episode, info=info, options=self.config.encode_options(),
                            parallel=self.config.parallel)
            if it.kind != "trailer" and not job.item_id:
                job.item_id = item_id_for(it.kind, it.title)
            self.queue.add(job)
            idx = self.pending.index(it)
            self.pending.pop(idx)
            self.files.takeItem(idx)
            added += 1
        if not self.pending:
            self.current = None
            self._set_form_enabled(False)
        self._update_hint()
        if problems:
            error_box(self, "Bazı dosyalar eklenemedi:\n\n" + "\n".join(problems), "Eksik bilgi")
        elif added:
            self.hint.setText(f"{added} dosya kuyruğa eklendi. İlerlemeyi 'Kuyruk' sayfasından izleyebilirsin.")

    def _validate(self, it: Pending) -> str | None:
        if it.error:
            return it.error
        if it.info is None:
            return "henüz incelenmedi"
        if not media.tools_available():
            return "ffmpeg bulunamadı"
        if not any(a.include for a in it.info.audio):
            return "en az bir ses kanalı seçilmeli"
        if it.kind == "trailer":
            return None if it.item_id else "fragmanın hangi yapıma ait olduğunu seç"
        if not it.title:
            return "ad boş"
        if it.kind == "episode" and (not it.season or not it.episode):
            return "sezon ve bölüm numarası gerekli"
        return None
