"""Ayarlar: depo, GitHub token, TMDB anahtarı, kalite."""
from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QComboBox, QFileDialog, QFormLayout, QHBoxLayout, QLineEdit, QSpinBox, QVBoxLayout, QWidget

from gflix import media
from gflix.github import GitHub
from gflix.tmdb import TMDB

from .style import button, hline, label
from .workers import run_async

TOKEN_HELP = (
    "GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token<br>"
    "• Repository access: <b>Only select repositories → g-flix</b><br>"
    "• Repository permissions: <b>Contents: Read and write</b>, <b>Actions: Read and write</b> (katalog yenileme için)<br>"
    "Not: Bu, telefondaki uygulamaya gömülen salt-okunur anahtardan <b>farklı</b>, yazma yetkili bir anahtardır. Kimseyle paylaşma."
)


class SettingsPage(QWidget):
    saved = Signal()

    def __init__(self, config):
        super().__init__()
        self.setObjectName("page")
        self.config = config
        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 24, 28, 24)
        lay.addWidget(label("Ayarlar", "h1"))
        lay.addSpacing(8)

        fl = QFormLayout()
        fl.setLabelAlignment(Qt.AlignRight)
        fl.setVerticalSpacing(12)
        fl.setHorizontalSpacing(16)
        self.repo = QLineEdit(config.repo)
        self.repo.setPlaceholderText("kullanici/depo")
        fl.addRow("GitHub deposu", self.repo)
        self.token = QLineEdit(config.github_token)
        self.token.setEchoMode(QLineEdit.Password)
        self.token.setPlaceholderText("github_pat_…")
        fl.addRow("GitHub token", self.token)
        th = label(TOKEN_HELP, "hint", wrap=True)
        th.setTextFormat(Qt.RichText)
        fl.addRow("", th)
        self.tmdb = QLineEdit(config.tmdb_key)
        self.tmdb.setEchoMode(QLineEdit.Password)
        self.tmdb.setPlaceholderText("themoviedb.org → Ayarlar → API (v3 anahtar ya da eyJ… ile başlayan okuma jetonu)")
        fl.addRow("TMDB anahtarı", self.tmdb)
        lay.addLayout(fl)

        row = QHBoxLayout()
        row.addWidget(button("Kaydet", "primary", self.save))
        row.addWidget(button("Bağlantıyı test et", "", self.test))
        row.addStretch(1)
        lay.addLayout(row)
        self.result = label("", "muted", wrap=True)
        self.result.setTextFormat(Qt.RichText)
        lay.addWidget(self.result)

        lay.addSpacing(12)
        lay.addWidget(hline())
        lay.addSpacing(12)
        lay.addWidget(label("Video kalitesi", "h2"))
        q = QFormLayout()
        q.setLabelAlignment(Qt.AlignRight)
        q.setVerticalSpacing(10)
        self.mode = QComboBox()
        for k, n in (("auto", "Otomatik (H.264 ise dokunma, değilse çevir) — önerilen"), ("copy", "Hiç dokunma (her zaman kopyala)"), ("h264", "Her zaman H.264'e çevir")):
            self.mode.addItem(n, k)
        self.mode.setCurrentIndex(max(0, self.mode.findData(config.get("video_mode", "auto"))))
        q.addRow("Görüntü", self.mode)
        self.height = QComboBox()
        for h in (2160, 1440, 1080, 720):
            self.height.addItem(f"{h}p", h)
        self.height.setCurrentIndex(max(0, self.height.findData(int(config.get("max_height", 1080)))))
        q.addRow("Çevirirken en yüksek çözünürlük", self.height)
        self.crf = QSpinBox()
        self.crf.setRange(16, 30)
        self.crf.setValue(int(config.get("crf", 21)))
        q.addRow("Kalite (düşük = daha iyi, büyük dosya)", self.crf)
        self.preset = QComboBox()
        for p in ("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow"):
            self.preset.addItem(p, p)
        self.preset.setCurrentIndex(max(0, self.preset.findData(config.get("preset", "veryfast"))))
        q.addRow("Çevirme hızı", self.preset)
        self.parallel = QSpinBox()
        self.parallel.setRange(1, 8)
        self.parallel.setValue(config.parallel)
        q.addRow("Aynı anda yüklenen parça", self.parallel)
        q.addRow("", label("İnternetin hızlıysa artır (4-6). Bağlantı kopmaları olursa azalt.", "hint", wrap=True))
        wrow = QHBoxLayout()
        self.workdir = QLineEdit(str(config.workdir))
        wrow.addWidget(self.workdir, 1)
        wrow.addWidget(button("Seç…", "", self.pick_workdir))
        q.addRow("Geçici dosyalar klasörü", wrow)
        q.addRow("", label("Hazırlanan video burada tutulur, yükleme bitince silinir. Videonun boyutu kadar boş alan gerekir.", "hint", wrap=True))
        lay.addLayout(q)
        lay.addStretch(1)
        ff = "✓ ffmpeg hazır" if media.tools_available() else "⚠ ffmpeg bulunamadı (uygulama paketiyle gelmeliydi)"
        lay.addWidget(label(f"{ff}  ·  ffmpeg: {media.FFMPEG}", "hint"))

    def pick_workdir(self):
        d = QFileDialog.getExistingDirectory(self, "Geçici dosyalar klasörü", self.workdir.text())
        if d:
            self.workdir.setText(d)

    def save(self):
        c = self.config
        c.set("repo", self.repo.text().strip())
        c.set_secret("github_token", self.token.text())
        c.set_secret("tmdb_key", self.tmdb.text())
        c.set("video_mode", self.mode.currentData())
        c.set("max_height", self.height.currentData())
        c.set("crf", self.crf.value())
        c.set("preset", self.preset.currentData())
        c.set("parallel", self.parallel.value())
        if self.workdir.text().strip():
            c.set("workdir", self.workdir.text().strip())
        self.result.setText("✓ Kaydedildi.")
        self.saved.emit()

    def test(self):
        repo, token, key = self.repo.text().strip(), self.token.text().strip(), self.tmdb.text().strip()
        self.result.setText("Test ediliyor…")

        def work():
            lines = []
            if repo and token:
                try:
                    info = GitHub(repo, token).repo_info()
                    perms = info.get("permissions") or {}
                    lines.append(f"✓ GitHub: <b>{info['full_name']}</b> ({'private' if info.get('private') else 'public'}), "
                                 f"varsayılan dal: {info.get('default_branch')}" + ("" if perms.get("push", True) else " — ⚠ yazma yetkisi yok!"))
                except Exception as e:  # noqa: BLE001
                    lines.append(f"✗ GitHub: {e}")
            else:
                lines.append("✗ GitHub: depo ve token gerekli")
            if key:
                try:
                    r = TMDB(key).search("movie", "Inception")
                    lines.append(f"✓ TMDB: çalışıyor (örnek: {r[0]['title'] if r else '-'})")
                except Exception as e:  # noqa: BLE001
                    lines.append(f"✗ TMDB: {e}")
            else:
                lines.append("– TMDB anahtarı girilmedi (arama ve kapak seçimi çalışmaz)")
            return "<br>".join(lines)
        run_async(work, on_done=self.result.setText, on_error=self.result.setText)
