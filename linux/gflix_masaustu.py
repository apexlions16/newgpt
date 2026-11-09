#!/usr/bin/env python3
"""
G-Flix Masaüstü (Linux): sürükle-bırak ile film/bölüm ekleme.

Her kutuya (Video, Türkçe ses, İngilizce ses, altyazılar) bir dosya sürükle ya da sitenin linkini yapıştır.
Linkleri bu bilgisayar indirir (aria2, 16 bağlantı; site izin vermezse tarayıcının çerezleriyle — 1DM gibi;
olmazsa tarayıcıdaki "cURL olarak kopyala" komutu yapıştırılabilir). Sonra iş, aynı bilgisayardaki G-Flix işçisine
(gflix-worker servisi) gider: MKVToolNix ile birleştirir, GitHub'a yükler, doğrular, dosyaları siler.

    python3 linux/gflix_masaustu.py          (kurulumdan sonra menüde "G-Flix Masaüstü")
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import uuid
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from PySide6.QtCore import Qt, QTimer  # noqa: E402
from PySide6.QtGui import QDragEnterEvent, QDropEvent, QFont  # noqa: E402
from PySide6.QtWidgets import (QApplication, QCheckBox, QComboBox, QFileDialog, QFrame, QGridLayout, QHBoxLayout,  # noqa: E402
                               QHeaderView, QLabel, QLineEdit, QMainWindow, QMessageBox, QPushButton, QScrollArea,
                               QSpinBox, QTreeWidget, QTreeWidgetItem, QVBoxLayout, QWidget)

from gflix.fetch import Downloader, check_link, fetch_link, parse_curl  # noqa: E402
from gflix.github import GitHub  # noqa: E402

CONF = Path.home() / ".config" / "gflix" / "worker.env"
STATUS_MARK = "<!--GFLIX_STATUS"
VIDEO_EXT = {".mp4", ".mkv", ".m4v", ".avi", ".mov", ".ts", ".webm", ".wmv", ".flv", ".m2ts"}
AUDIO_EXT = {".m4a", ".aac", ".ac3", ".eac3", ".mka", ".mp3", ".dts", ".opus", ".flac", ".ogg", ".wav", ".thd"}
SUB_EXT = {".vtt", ".srt", ".ass", ".ssa"}

STYLE = """
QWidget { background: #141414; color: #e5e5e5; font-size: 13px; }
QLineEdit, QSpinBox, QComboBox { background: #222; border: 1px solid #3a3a3a; border-radius: 4px; padding: 6px; }
QLineEdit:focus { border-color: #e50914; }
QPushButton { background: #2b2b2b; border: 1px solid #444; border-radius: 4px; padding: 6px 12px; }
QPushButton:hover { background: #383838; }
QPushButton#primary { background: #e50914; border: none; font-weight: bold; font-size: 15px; padding: 10px 22px; }
QPushButton#primary:hover { background: #f6121d; }
QPushButton#primary:disabled { background: #5a1a1d; color: #aaa; }
QFrame#slot { background: #1d1d1d; border: 2px dashed #3a3a3a; border-radius: 8px; }
QFrame#slot[filled="true"] { border: 2px solid #2e7d32; }
QFrame#slot[hover="true"] { border: 2px dashed #e50914; background: #261516; }
QLabel#h1 { font-size: 22px; font-weight: bold; color: white; }
QLabel#h2 { font-size: 15px; font-weight: bold; color: white; }
QLabel#hint { color: #9a9a9a; }
QTreeWidget { background: #1a1a1a; border: 1px solid #333; }
QHeaderView::section { background: #222; color: #bbb; border: none; padding: 4px; }
QCheckBox { background: transparent; spacing: 8px; }
QCheckBox::indicator { width: 16px; height: 16px; border: 1px solid #888; border-radius: 3px; background: #222; }
QCheckBox::indicator:checked { background: #e50914; border-color: #e50914; }
"""


def load_conf() -> dict:
    conf = {}
    if CONF.exists():
        for line in CONF.read_text(encoding="utf-8").splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                conf[k.strip()] = v.strip()
    for k in ("GITHUB_TOKEN", "GFLIX_REPO", "TMDB_API_KEY", "GFLIX_WORKDIR"):
        conf.setdefault(k, os.environ.get(k, ""))
    conf["GFLIX_WORKDIR"] = os.path.expanduser(conf.get("GFLIX_WORKDIR") or "~/gflix-work")
    return conf


def human(n: float) -> str:
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024 or u == "GB":
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1024
    return f"{n:.1f} GB"


def guess_lang(name: str) -> str:
    low = name.lower()
    toks = set(re.split(r"[^a-zçğıöşü]+", low))
    if toks & {"tr", "tur", "turkish", "turkce", "türkçe", "dublaj", "tr-dub"}:
        return "tr"
    if toks & {"en", "eng", "english", "ingilizce"}:
        return "en"
    return ""


def is_forced(name: str) -> bool:
    return bool(set(re.split(r"[^a-z]+", name.lower())) & {"forced", "zorunlu"})


# ------------------------------------------------------------------ kutular

SLOTS = [
    # anahtar, başlık, rol, dil, zorunlu
    ("video_tr", "🎬 Türkçe dublajlı video", "video", "tr", False),
    ("video_en", "🎬 İngilizce video", "video", "en", False),
    ("alt_tr", "💬 Türkçe altyazı", "subtitle", "tr", False),
    ("alt_en", "💬 İngilizce altyazı", "subtitle", "en", False),
    ("alt_tr_f", "💬 Türkçe zorunlu altyazı", "subtitle", "tr", True),
    ("ses_tr", "🔊 Ayrı Türkçe ses (isteğe bağlı)", "audio", "tr", False),
    ("ses_en", "🔊 Ayrı İngilizce ses (isteğe bağlı)", "audio", "en", False),
]


class Slot(QFrame):
    """Dosya sürüklenebilen ya da link (veya cURL komutu) yapıştırılabilen kutu."""

    def __init__(self, key, title, role, lang, forced, window):
        super().__init__()
        self.key, self.title, self.role, self.lang, self.forced, self.win = key, title, role, lang, forced, window
        self.path: Path | None = None
        self.url = ""
        self.headers: dict = {}
        self.setObjectName("slot")
        self.setAcceptDrops(True)
        self.setMinimumHeight(118)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(12, 10, 12, 10)
        top = QHBoxLayout()
        t = QLabel(title)
        t.setObjectName("h2")
        t.setStyleSheet("background: transparent;")
        top.addWidget(t, 1)
        self.pick = QPushButton("Dosya seç…")
        self.pick.clicked.connect(self.choose)
        top.addWidget(self.pick)
        self.clear_btn = QPushButton("✕")
        self.clear_btn.setFixedWidth(34)
        self.clear_btn.clicked.connect(self.clear)
        top.addWidget(self.clear_btn)
        lay.addLayout(top)
        self.info = QLabel("Dosyayı buraya sürükle ya da aşağıya linki yapıştır")
        self.info.setObjectName("hint")
        self.info.setWordWrap(True)
        self.info.setStyleSheet("background: transparent;")
        lay.addWidget(self.info)
        self.link = QLineEdit()
        self.link.setPlaceholderText("https://… (sitenin .txt linki de olur) ya da cURL komutu")
        self.link.editingFinished.connect(self.on_link)
        lay.addWidget(self.link)

    # --- durum
    @property
    def filled(self) -> bool:
        return self.path is not None or bool(self.url)

    def set_style(self, hover=False):
        self.setProperty("filled", "true" if self.filled else "false")
        self.setProperty("hover", "true" if hover else "false")
        self.style().unpolish(self)
        self.style().polish(self)

    def set_file(self, path: Path):
        self.path, self.url, self.headers = path, "", {}
        self.link.blockSignals(True)
        self.link.clear()
        self.link.blockSignals(False)
        self.info.setText(f"📄 {path.name}  ·  {human(path.stat().st_size)}")
        self.info.setStyleSheet("background: transparent; color: #e5e5e5;")
        self.set_style()

    def clear(self):
        self.path, self.url, self.headers = None, "", {}
        self.link.blockSignals(True)
        self.link.clear()
        self.link.blockSignals(False)
        self.info.setText("Dosyayı buraya sürükle ya da aşağıya linki yapıştır")
        self.info.setStyleSheet("background: transparent; color: #9a9a9a;")
        self.set_style()

    def choose(self):
        f, _ = QFileDialog.getOpenFileName(self, self.title, str(Path.home() / "İndirilenler" if (Path.home() / "İndirilenler").exists() else Path.home()))
        if f:
            self.set_file(Path(f))

    def on_link(self):
        text = self.link.text().strip()
        if not text or text == self.url:
            return
        try:
            if text.lower().startswith("curl "):
                url, headers = parse_curl(text)
            else:
                url, headers = text, {}
            if not url.lower().startswith(("http://", "https://")):
                raise ValueError("link http:// ya da https:// ile başlamalı")
        except Exception as e:  # noqa: BLE001
            self.info.setText(f"⚠ {e}")
            self.info.setStyleSheet("background: transparent; color: #ff6b6b;")
            return
        self.path, self.url, self.headers = None, url, headers
        if text.lower().startswith("curl "):
            self.link.blockSignals(True)
            self.link.setText(url)
            self.link.blockSignals(False)
        extra = f" (+{len(headers)} başlık: {', '.join(headers)})" if headers else ""
        self.info.setText("🔗 Link deneniyor…" + extra)
        self.info.setStyleSheet("background: transparent; color: #e5e5e5;")
        self.set_style()
        threading.Thread(target=self._test, args=(url, dict(headers), extra), daemon=True).start()

    def _test(self, url, headers, extra):
        try:
            code, size, ctype = check_link(url, headers)
            if code < 400:
                msg = f"✓ Link çalışıyor · {human(size) if size else 'boyut bilinmiyor'}{extra}"
            elif code in (401, 403):
                msg = f"⚠ Site izin vermedi ({code}). Gönderince tarayıcı çerezleriyle denenecek{extra}"
            else:
                msg = f"⚠ HTTP {code}{extra}"
        except Exception as e:  # noqa: BLE001
            msg = f"⚠ Denenemedi: {str(e)[:120]}"
        self.win.ui_queue.append(lambda: (self.url == url) and self.info.setText(msg))

    # --- sürükle bırak
    def dragEnterEvent(self, e: QDragEnterEvent):
        if e.mimeData().hasUrls() or e.mimeData().hasText():
            e.acceptProposedAction()
            self.set_style(hover=True)

    def dragLeaveEvent(self, e):
        self.set_style()

    def dropEvent(self, e: QDropEvent):
        self.set_style()
        md = e.mimeData()
        if md.hasUrls():
            u = md.urls()[0]
            if u.isLocalFile():
                self.set_file(Path(u.toLocalFile()))
            else:
                self.link.setText(u.toString())
                self.on_link()
        elif md.hasText():
            self.link.setText(md.text().strip())
            self.on_link()
        e.acceptProposedAction()


# ------------------------------------------------------------------ işler

@dataclass
class Job:
    id: str
    title: str
    label: str
    recipe_head: list[str]
    files: list[dict]              # {role, lang, forced, path?, url?, headers, delete}
    browser: str
    state: str = "Hazırlanıyor"
    progress: float = 0.0
    detail: str = ""
    tag: str = ""
    done: bool = False
    failed: bool = False
    log: list[str] = field(default_factory=list)


class Runner:
    """İşleri arka planda yürütür: linkleri indir → tarif → pcjob Release → işçinin canlı durumunu izle."""

    def __init__(self, conf: dict):
        self.conf = conf
        self.dl = Downloader(log=lambda m: None)
        self.jobs: list[Job] = []

    def gh(self) -> GitHub:
        return GitHub(self.conf["GFLIX_REPO"], self.conf["GITHUB_TOKEN"], log=lambda m: None)

    def submit(self, job: Job):
        self.jobs.insert(0, job)
        threading.Thread(target=self._run, args=(job,), daemon=True).start()

    def _run(self, job: Job):
        try:
            dest = Path(self.conf["GFLIX_WORKDIR"]) / "indirilenler" / job.id
            links = [f for f in job.files if f.get("url")]
            for i, f in enumerate(links):
                name = f"{f['role']}_{f['lang'] or 'x'}{'_zorunlu' if f['forced'] else ''}"

                def prog(d, i=i):
                    tot = d.total or 0
                    job.state = f"İndiriliyor ({i + 1}/{len(links)})"
                    job.detail = f"{d.label}: {human(d.done)}" + (f" / {human(tot)}" if tot else "") + f" · {human(d.speed)}/sn"
                    job.progress = 0.4 * ((i + (d.done / tot if tot else 0)) / len(links))
                path = fetch_link(self.dl, f["url"], dest, name, label=name, headers=f.get("headers"), browser=job.browser,
                                  log=lambda m: job.log.append(m.strip()), on_progress=prog)
                f["path"], f["delete"] = str(path), True     # bu programın indirdiği dosya: yüklenince silinir
            job.state, job.detail, job.progress = "GitHub'a gönderiliyor", "", 0.4
            recipe = self.recipe(job)
            gh = self.gh()
            job.tag = "pcjob-" + uuid.uuid4().hex[:12]
            rel = gh.req("POST", "/releases", json={"tag_name": job.tag, "name": f"🖥️ {job.title} {job.label}".strip(),
                                                    "body": "Hazırlanıyor…", "draft": True, "prerelease": True}).json()
            gh.req("POST", rel["upload_url"].split("{")[0], params={"name": "recipe.txt"}, data=recipe.encode("utf-8"),
                   headers={"Content-Type": "text/plain; charset=utf-8"})
            gh.req("PATCH", f"/releases/{rel['id']}", json={"draft": False, "body": "⏳ Sırada — bilgisayar alacak"})
            job.state = "Sırada (işçi alacak)"
            self.follow(job, gh, rel["id"])
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            job.failed, job.done = True, True
            job.state, job.detail = "❌ Hata", str(e)

    def recipe(self, job: Job) -> str:
        lines = list(job.recipe_head)
        first_tr = next((f for f in job.files if f["role"] == "audio" and f["lang"] == "tr"), None)
        for f in job.files:
            uri = Path(f["path"]).resolve().as_uri()
            if f["role"] == "video":
                # İki video varsa dil başına ayrı sürüm olarak yüklenir (birleştirme yok)
                key = f"video {f['lang']}" if f["lang"] else "video"
            elif f["role"] == "audio":
                key = f"ses {f['lang']}" + (" varsayılan" if f is first_tr else "")
            else:
                key = f"altyazı {f['lang']}" + (" zorunlu" if f["forced"] else "")
            lines.append(f"{key}: {uri}" + (" | sil" if f.get("delete") else ""))
        return "\n".join(lines) + "\n"

    def follow(self, job: Job, gh: GitHub, rid: int):
        while True:
            time.sleep(4)
            try:
                body = gh.req("GET", f"/releases/{rid}").json().get("body") or ""
            except Exception:  # noqa: BLE001
                continue
            if STATUS_MARK not in body:
                if body.startswith("⚙️"):
                    job.state = "İşçi aldı, başlıyor"
                continue
            try:
                d = json.loads(body.split(STATUS_MARK, 1)[1].rsplit("-->", 1)[0])
            except ValueError:
                continue
            items = d.get("items") or []
            it = items[0] if items else {}
            job.progress = 0.4 + 0.6 * float(it.get("progress") or 0)
            job.state = d.get("stage") or job.state
            job.detail = it.get("text", "") + (f"  ·  Fragman: {d['trailer']['text']}" if d.get("trailer") else "")
            if d.get("state") != "running":
                job.done = True
                job.failed = d.get("state") != "done"
                job.state = {"done": "✅ Tamamlandı", "partial": "⚠ Kısmen", "error": "❌ Hata", "cancelled": "🛑 İptal"}.get(d["state"], d["state"])
                if job.failed:
                    job.detail = (it.get("error") or job.detail)[:400]
                return


# ------------------------------------------------------------------ ana pencere

class Main(QMainWindow):
    def __init__(self):
        super().__init__()
        self.conf = load_conf()
        self.runner = Runner(self.conf)
        self.ui_queue: list = []
        self.setWindowTitle("G-Flix Masaüstü")
        self.resize(1080, 860)
        self.setAcceptDrops(True)

        root = QWidget()
        outer = QVBoxLayout(root)
        outer.setContentsMargins(22, 18, 22, 18)
        head = QHBoxLayout()
        h1 = QLabel("G-FLIX  Masaüstü")
        h1.setObjectName("h1")
        h1.setStyleSheet("color: #e50914;")
        head.addWidget(h1)
        head.addStretch(1)
        self.worker_label = QLabel("")
        self.worker_label.setObjectName("hint")
        head.addWidget(self.worker_label)
        outer.addLayout(head)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QFrame.NoFrame)
        body = QWidget()
        lay = QVBoxLayout(body)
        lay.setContentsMargins(0, 0, 6, 0)

        row = QHBoxLayout()
        self.tmdb = QLineEdit()
        self.tmdb.setPlaceholderText("TMDB linki: https://www.themoviedb.org/movie/3003-ornek-film  ya da /tv/1001-…")
        self.tmdb.textChanged.connect(self.on_tmdb)
        row.addWidget(self.tmdb, 1)
        self.season = QSpinBox()
        self.season.setRange(1, 99)
        self.season.setPrefix("Sezon ")
        self.episode = QSpinBox()
        self.episode.setRange(1, 999)
        self.episode.setPrefix("Bölüm ")
        row.addWidget(self.season)
        row.addWidget(self.episode)
        lay.addLayout(row)
        self.tmdb_info = QLabel("themoviedb.org'da filmi/diziyi açıp adresini kopyala.")
        self.tmdb_info.setObjectName("hint")
        lay.addWidget(self.tmdb_info)
        self.title_edit = QLineEdit()
        self.title_edit.setPlaceholderText("Ad (isteğe bağlı — boşsa TMDB'deki Türkçe ad)")
        lay.addWidget(self.title_edit)

        hint = QLabel("Türkçe dublajlı videoyu ve İngilizce videoyu ayrı kutulara sürükle: birleştirilmez, oynatıcıda dil değişince aynı "
                      "saniyeden öbür videoya geçer (sesler kaymaz). Altyazılar ortak. Hepsini birden pencereye de bırakabilirsin; "
                      "dil dosya adından anlaşılır (Film.TR.mkv, Film.EN.mkv).")
        hint.setObjectName("hint")
        hint.setWordWrap(True)
        lay.addWidget(hint)
        grid = QGridLayout()
        grid.setSpacing(10)
        self.slots: dict[str, Slot] = {}
        for n, (key, title, role, lang, forced) in enumerate(SLOTS):
            s = Slot(key, title, role, lang, forced, self)
            self.slots[key] = s
            grid.addWidget(s, n // 2, n % 2)
        lay.addLayout(grid)

        tr_row = QHBoxLayout()
        self.trailer_tr = QLineEdit()
        self.trailer_tr.setPlaceholderText("🎞️ Türkçe fragman (YouTube linki, isteğe bağlı)")
        self.trailer_en = QLineEdit()
        self.trailer_en.setPlaceholderText("🎞️ İngilizce fragman (YouTube linki, isteğe bağlı)")
        tr_row.addWidget(self.trailer_tr)
        tr_row.addWidget(self.trailer_en)
        lay.addLayout(tr_row)

        opt = QHBoxLayout()
        self.delete_local = QCheckBox("Yükleme GitHub'da doğrulanınca sürüklediğim dosyaları da sil")
        self.delete_local.setChecked(True)
        opt.addWidget(self.delete_local)
        opt.addStretch(1)
        opt.addWidget(QLabel("Linkler için tarayıcı çerezleri:"))
        self.browser = QComboBox()
        self.browser.addItems(["Otomatik", "Firefox", "Chrome", "Chromium", "Brave", "Kapalı"])
        self.browser.setToolTip("Site linki vermezse (403), tarayıcında o sitede açık oturumun çerezleriyle indirilir — 1DM gibi.")
        opt.addWidget(self.browser)
        lay.addLayout(opt)

        btns = QHBoxLayout()
        self.next_btn = QPushButton("Sonraki bölüm")
        self.next_btn.setToolTip("TMDB ve ayarlar kalır, kutular boşalır, bölüm numarası bir artar")
        self.next_btn.clicked.connect(self.next_episode)
        btns.addWidget(self.next_btn)
        btns.addStretch(1)
        self.msg = QLabel("")
        self.msg.setWordWrap(True)
        btns.addWidget(self.msg, 3)
        self.send_btn = QPushButton("Gönder  ➜")
        self.send_btn.setObjectName("primary")
        self.send_btn.clicked.connect(self.send)
        btns.addWidget(self.send_btn)
        lay.addLayout(btns)

        jl = QLabel("İşler")
        jl.setObjectName("h2")
        lay.addWidget(jl)
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["Yapım", "Durum", "%", "Ayrıntı"])
        self.tree.setRootIsDecorated(False)
        self.tree.header().setSectionResizeMode(0, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.tree.header().setSectionResizeMode(2, QHeaderView.ResizeToContents)
        self.tree.header().setStretchLastSection(True)
        self.tree.setMinimumHeight(200)
        lay.addWidget(self.tree)
        scroll.setWidget(body)
        outer.addWidget(scroll, 1)
        self.setCentralWidget(root)

        self.on_tmdb()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.tick)
        self.timer.start(700)
        self.worker_timer = QTimer(self)
        self.worker_timer.timeout.connect(self.check_worker)
        self.worker_timer.start(15000)
        self.check_worker()
        if not self.conf.get("GITHUB_TOKEN") or not self.conf.get("GFLIX_REPO"):
            QTimer.singleShot(300, lambda: QMessageBox.warning(self, "Ayar yok", f"{CONF} bulunamadı.\nÖnce: bash linux/install.sh"))

    # --- TMDB
    def kind_id(self):
        m = re.search(r"(movie|tv)/(\d+)", self.tmdb.text())
        return (m.group(1), int(m.group(2))) if m else (None, None)

    def on_tmdb(self):
        kind, tid = self.kind_id()
        series = kind == "tv"
        self.season.setVisible(series)
        self.episode.setVisible(series)
        self.next_btn.setVisible(series)
        if not tid:
            self.tmdb_info.setText("themoviedb.org'da filmi/diziyi açıp adresini kopyala." if not self.tmdb.text() else "⚠ Link anlaşılamadı (/movie/123 ya da /tv/123 olmalı)")
            return
        self.tmdb_info.setText(f"✓ {'Dizi' if series else 'Film'} · TMDB #{tid}")
        key = self.conf.get("TMDB_API_KEY")
        if key:
            def look():
                try:
                    from gflix.tmdb import TMDB
                    d = TMDB(key).get(f"/{kind}/{tid}", language="tr-TR") or {}
                    name = d.get("title") or d.get("name")
                    year = (d.get("release_date") or d.get("first_air_date") or "")[:4]
                    if name:
                        self.ui_queue.append(lambda: self.kind_id()[1] == tid and self.tmdb_info.setText(
                            f"✓ {'Dizi' if series else 'Film'}: {name} ({year})"))
                except Exception:  # noqa: BLE001
                    pass
            threading.Thread(target=look, daemon=True).start()

    # --- pencereye toplu bırakma: dosya türü ve adındaki dile göre kutulara dağıt
    def dragEnterEvent(self, e: QDragEnterEvent):
        if e.mimeData().hasUrls():
            e.acceptProposedAction()

    def dropEvent(self, e: QDropEvent):
        paths = [Path(u.toLocalFile()) for u in e.mimeData().urls() if u.isLocalFile()]
        unknown = []
        for p in paths:
            ext = p.suffix.lower()
            lang = guess_lang(p.stem)
            if ext in VIDEO_EXT:
                key = "video_en" if lang == "en" else "video_tr" if lang == "tr" else ("video_en" if self.slots["video_tr"].filled else "video_tr")
                self.slots[key].set_file(p)
            elif ext in AUDIO_EXT:
                key = "ses_en" if lang == "en" else "ses_tr" if lang == "tr" else ("ses_en" if self.slots["ses_tr"].filled else "ses_tr")
                self.slots[key].set_file(p)
            elif ext in SUB_EXT:
                if is_forced(p.stem):
                    self.slots["alt_tr_f"].set_file(p)
                else:
                    key = "alt_en" if lang == "en" else "alt_tr" if lang == "tr" else ("alt_en" if self.slots["alt_tr"].filled else "alt_tr")
                    self.slots[key].set_file(p)
            else:
                unknown.append(p.name)
        if unknown:
            self.flash(f"Tanınmadı, ilgili kutuya sürükle: {', '.join(unknown)}", error=True)
        e.acceptProposedAction()

    # --- gönder
    def flash(self, text: str, error=False):
        self.msg.setText(text)
        self.msg.setStyleSheet(f"color: {'#ff6b6b' if error else '#46d369'};")

    def send(self):
        kind, tid = self.kind_id()
        if not tid:
            return self.flash("TMDB linkini yapıştır.", error=True)
        n_videos = sum(self.slots[k].filled for k in ("video_tr", "video_en"))
        if not n_videos:
            return self.flash("En az bir video gerekli (Türkçe dublajlı ya da İngilizce).", error=True)
        if n_videos == 2 and any(self.slots[k].filled for k in ("ses_tr", "ses_en")):
            QMessageBox.information(self, "Ayrı ses kullanılmayacak", "İki dilde ayrı video verdiğin için ayrı ses dosyaları yok sayılacak; "
                                    "her video kendi sesiyle yüklenir.")
        for s in self.slots.values():
            if s.path is not None and not s.path.is_file():
                return self.flash(f"{s.title}: dosya bulunamadı ({s.path})", error=True)
        series = kind == "tv"
        head = [f"tmdb: https://www.themoviedb.org/{kind}/{tid}"]
        if self.title_edit.text().strip():
            head.append(f"ad: {self.title_edit.text().strip()}")
        if self.trailer_tr.text().strip():
            head.append(f"fragman tr: {self.trailer_tr.text().strip()}")
        if self.trailer_en.text().strip():
            head.append(f"fragman en: {self.trailer_en.text().strip()}")
        label = f"S{self.season.value():02d}E{self.episode.value():02d}" if series else ""
        if series:
            head.append(f"bölüm {label}:")
        files = []
        for s in self.slots.values():
            if not s.filled:
                continue
            files.append({"role": s.role, "lang": s.lang, "forced": s.forced, "path": str(s.path) if s.path else None,
                          "url": s.url or None, "headers": dict(s.headers), "delete": bool(s.path) and self.delete_local.isChecked()})
        browser = self.browser.currentText().lower()
        browser = {"otomatik": "auto", "kapalı": "off"}.get(browser, browser)
        info = self.tmdb_info.text()
        title = self.title_edit.text().strip() or (info.split(": ", 1)[-1] if ": " in info else f"TMDB #{tid}")
        job = Job(uuid.uuid4().hex[:8], title, label, head, files, browser)
        self.runner.submit(job)
        self.flash(f"✓ Gönderildi: {title} {label}".strip())
        if series:
            self.next_episode()
        else:
            for s in self.slots.values():
                s.clear()
            self.trailer_tr.clear()
            self.trailer_en.clear()
            self.title_edit.clear()
            self.tmdb.clear()

    def next_episode(self):
        for s in self.slots.values():
            s.clear()
        self.trailer_tr.clear()
        self.trailer_en.clear()
        self.episode.setValue(self.episode.value() + 1)

    # --- yenileme
    def tick(self):
        while self.ui_queue:
            try:
                self.ui_queue.pop(0)()
            except Exception:  # noqa: BLE001
                traceback.print_exc()
        jobs = self.runner.jobs
        while self.tree.topLevelItemCount() < len(jobs):
            self.tree.insertTopLevelItem(0, QTreeWidgetItem(["", "", "", ""]))
        for i, j in enumerate(jobs):
            it = self.tree.topLevelItem(i)
            it.setText(0, f"{j.title} {j.label}".strip())
            it.setText(1, j.state)
            it.setText(2, f"%{int(j.progress * 100)}")
            it.setText(3, j.detail)
            it.setToolTip(3, j.detail + ("\n\n" + "\n".join(j.log[-8:]) if j.log else ""))

    def check_worker(self):
        def run():
            active = subprocess.run(["systemctl", "--user", "is-active", "gflix-worker"], capture_output=True, text=True).stdout.strip() \
                if shutil.which("systemctl") else "unknown"
            text = {"active": "🟢 İşçi çalışıyor", "inactive": "🔴 İşçi kapalı — systemctl --user start gflix-worker",
                    "failed": "🔴 İşçi hata verdi — journalctl --user -u gflix-worker"}.get(active, "")
            self.ui_queue.append(lambda: self.worker_label.setText(text))
        threading.Thread(target=run, daemon=True).start()

    def closeEvent(self, e):
        busy = [j for j in self.runner.jobs if not j.done and j.state.startswith("İndiriliyor")]
        if busy and QMessageBox.question(self, "İndirme sürüyor", "Bazı linkler hâlâ iniyor. Kapatırsan yarım kalır. Kapatılsın mı?") != QMessageBox.Yes:
            e.ignore()
            return
        self.runner.dl.close()
        e.accept()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("G-Flix Masaüstü")
    app.setStyleSheet(STYLE)
    app.setFont(QFont("Sans", 10))
    icon = ROOT / "studio" / "assets" / "gflix.png"
    if icon.exists():
        from PySide6.QtGui import QIcon
        app.setWindowIcon(QIcon(str(icon)))
    w = Main()
    w.show()
    if "--screenshot" in sys.argv:   # test için
        out = sys.argv[sys.argv.index("--screenshot") + 1]
        QTimer.singleShot(1500, lambda: (w.grab().save(out), app.quit()))
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
