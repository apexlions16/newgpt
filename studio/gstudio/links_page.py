"""Linkten toplu ekleme: tarifi gönder, GitHub'daki işi canlı takip et."""
from __future__ import annotations

import json
import time
import uuid
import webbrowser

from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import (QHBoxLayout, QHeaderView, QPlainTextEdit, QProgressBar, QSplitter, QTreeWidget,
                               QTreeWidgetItem, QVBoxLayout, QWidget)

from gflix.recipe import TEMPLATE_MOVIE, TEMPLATE_SERIES, parse

from .style import button, error_box, label
from .workers import run_async

MARK = "<!--GFLIX_STATUS"
STATE_ICON = {"running": "⚙️", "done": "✅", "partial": "⚠️", "error": "❌", "cancelled": "🛑"}


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}" if unit != "B" else f"{int(n)} B"
        n /= 1024
    return ""


def parse_status(body: str) -> dict | None:
    if MARK not in body:
        return None
    try:
        return json.loads(body.split(MARK, 1)[1].rsplit("-->", 1)[0])
    except Exception:  # noqa: BLE001
        return None


class LinksPage(QWidget):
    def __init__(self, config):
        super().__init__()
        self.setObjectName("page")
        self.config = config
        self.statuses: dict[str, tuple[str, dict | None]] = {}
        self._busy = False

        lay = QVBoxLayout(self)
        lay.setContentsMargins(28, 24, 28, 24)
        head = QHBoxLayout()
        head.addWidget(label("Linkten Ekle", "h1"), 1)
        head.addWidget(button("Film şablonu", "", lambda: self.editor.setPlainText(TEMPLATE_MOVIE)))
        head.addWidget(button("Dizi şablonu", "", lambda: self.editor.setPlainText(TEMPLATE_SERIES)))
        lay.addLayout(head)
        lay.addWidget(label(
            "GitHub bütün linkleri aynı anda indirir, .txt uzantılarını düzeltir, MKVToolNix ile 'Film Adı (Yıl).mkv' yapar, yükler, "
            "fragmanı YouTube'dan ekler ve dosyaları siler. Satırlar: video / ses tr / ses tr varsayılan / altyazı tr / altyazı tr zorunlu / "
            "fragman / ad / video sesleri: tr en … Dizide her bölüm 'bölüm S01E01:' satırıyla başlar.", "hint", wrap=True))

        split = QSplitter(Qt.Vertical)
        top = QWidget()
        tl = QVBoxLayout(top)
        tl.setContentsMargins(0, 8, 0, 0)
        self.editor = QPlainTextEdit()
        self.editor.setPlaceholderText("tmdb: https://www.themoviedb.org/movie/3003-ornek-film\nfragman: https://www.youtube.com/watch?v=…\nvideo: https://…\nses tr varsayılan: https://…\naltyazı tr: https://…")
        self.editor.setStyleSheet("font-family: Consolas, monospace; font-size: 13px;")
        self.editor.textChanged.connect(self.validate)
        tl.addWidget(self.editor, 1)
        row = QHBoxLayout()
        self.check = label("", "hint", wrap=True)
        row.addWidget(self.check, 1)
        from PySide6.QtWidgets import QComboBox
        self.who = QComboBox()
        self.who.addItems(["🖥️ Linux bilgisayarım yapsın", "☁️ GitHub yapsın"])
        self.who.setToolTip("Bilgisayarım: Linux'taki G-Flix işçisi indirir, MKVToolNix ile birleştirir, MKV'yi yükler.")
        row.addWidget(self.who)
        self.send_btn = button("Gönder  ➜", "primary", self.send)
        row.addWidget(self.send_btn)
        tl.addLayout(row)
        split.addWidget(top)

        bottom = QWidget()
        bl = QVBoxLayout(bottom)
        bl.setContentsMargins(0, 8, 0, 0)
        jh = QHBoxLayout()
        jh.addWidget(label("İşler (canlı)", "h2"), 1)
        jh.addWidget(button("Canlı kaydı aç", "", self.open_log))
        jh.addWidget(button("İptal et", "", self.cancel_selected))
        jh.addWidget(button("Listeden kaldır", "link", self.remove_selected))
        bl.addLayout(jh)
        self.tree = QTreeWidget()
        self.tree.setColumnCount(4)
        self.tree.setHeaderLabels(["İş / öğe / dosya", "Durum", "İlerleme", "Hız"])
        self.tree.header().setSectionResizeMode(0, QHeaderView.Stretch)
        self.tree.header().setSectionResizeMode(1, QHeaderView.Stretch)
        self.tree.setColumnWidth(2, 160)
        self.tree.setColumnWidth(3, 110)
        self.tree.setStyleSheet("QTreeWidget { background: #1F1F1F; border: none; } QTreeWidget::item { padding: 3px; }")
        bl.addWidget(self.tree, 1)
        split.addWidget(bottom)
        split.setSizes([300, 420])
        lay.addWidget(split, 1)

        self.timer = QTimer(self)
        self.timer.timeout.connect(self.refresh)
        self.timer.start(3000)
        self.validate()
        self.render()

    # ---- iş listesi (QSettings)
    def jobs(self) -> list[dict]:
        try:
            return json.loads(self.config.get("link_jobs", "[]") or "[]")
        except Exception:  # noqa: BLE001
            return []

    def save_jobs(self, jobs: list[dict]) -> None:
        self.config.set("link_jobs", json.dumps(jobs, ensure_ascii=False))

    # ---- tarif
    def validate(self):
        text = self.editor.toPlainText()
        if not text.strip():
            self.check.setText("")
            self.send_btn.setEnabled(False)
            return
        r = parse(text)
        if r.errors:
            self.check.setStyleSheet("color: #ff6b6b;")
            self.check.setText("✗ " + "  •  ".join(r.errors[:4]))
        else:
            files = sum(len(i.files) for i in r.items)
            kind = "Dizi" if r.kind == "series" else "Film"
            msg = f"✓ {kind}, TMDB #{r.tmdb_id or '—'}, {len(r.items)} öğe, {files} dosya" + (", fragman var" if r.trailer else "")
            if r.warnings:
                msg += "   ⚠ " + "  •  ".join(r.warnings[:2])
            self.check.setStyleSheet("color: #46d369;" if not r.warnings else "color: #e8a33d;")
            self.check.setText(msg)
        self.send_btn.setEnabled(not r.errors)

    def send(self):
        gh = self.config.gh()
        if not gh:
            error_box(self, "Önce Ayarlar'dan GitHub token'ını gir.")
            return
        text = self.editor.toPlainText()
        r = parse(text)
        if r.errors:
            return
        self.send_btn.setEnabled(False)
        title = r.title or (f"{'tv' if r.kind == 'series' else 'movie'}/{r.tmdb_id}")

        to_pc = self.who.currentIndex() == 0

        def work():
            tag = ("pcjob-" if to_pc else "linkjob-") + uuid.uuid4().hex[:12]
            rel = gh.req("POST", "/releases", json={"tag_name": tag, "name": f"🔗 {title}", "body": "Hazırlanıyor…", "draft": True, "prerelease": True}).json()
            url = rel["upload_url"].split("{")[0]
            gh.req("POST", url, params={"name": "recipe.txt"}, data=text.encode("utf-8"), headers={"Content-Type": "text/plain; charset=utf-8"})
            gh.req("PATCH", f"/releases/{rel['id']}", json={"draft": False, "body": "⏳ Sırada — bilgisayar alacak" if to_pc else "⏳ Sırada — GitHub başlatıyor"})
            return {"tag": tag, "id": rel["id"], "title": title, "created": int(time.time())}

        def done(job):
            self.save_jobs([job] + self.jobs())
            self.editor.clear()
            self.check.setStyleSheet("color: #46d369;")
            self.check.setText("✓ Gönderildi. " + ("Bilgisayarın 20 saniye içinde alır." if to_pc else "GitHub birkaç saniye içinde başlar."))
            self.refresh()
        run_async(work, on_done=done, on_error=lambda m: (error_box(self, "Gönderilemedi:\n" + m), self.send_btn.setEnabled(True)))

    # ---- canlı durum
    def refresh(self):
        if self._busy or not self.isVisible():
            return
        jobs = self.jobs()
        gh = self.config.gh()
        if not jobs or not gh:
            return
        todo = [j for j in jobs if (self.statuses.get(j["tag"], ("", None))[1] or {}).get("state", "running") == "running"]
        if not todo:
            return
        self._busy = True

        def work():
            out = {}
            for j in todo:
                r = gh.req("GET", f"/releases/{j['id']}", ok=(200, 404))
                if r.status_code == 404:
                    out[j["tag"]] = ("Silinmiş", None)
                else:
                    body = r.json().get("body") or ""
                    out[j["tag"]] = (body.splitlines()[0] if body else "", parse_status(body))
            return out

        def done(res):
            self._busy = False
            self.statuses.update(res)
            self.render()
        run_async(work, on_done=done, on_error=lambda m: setattr(self, "_busy", False))

    def _bar(self, item: QTreeWidgetItem, frac: float):
        bar = QProgressBar()
        bar.setRange(0, 1000)
        bar.setValue(int(frac * 1000))
        bar.setFormat(f"%{int(frac * 100)}")
        bar.setTextVisible(True)
        bar.setFixedHeight(14)
        self.tree.setItemWidget(item, 2, bar)

    def render(self):
        open_tags = {self.tree.topLevelItem(i).data(0, Qt.UserRole) for i in range(self.tree.topLevelItemCount()) if self.tree.topLevelItem(i).isExpanded()}
        selected = self.tree.currentItem().data(0, Qt.UserRole) if self.tree.currentItem() else None
        self.tree.clear()
        now = time.time()
        for j in self.jobs():
            head, st = self.statuses.get(j["tag"], ("Yükleniyor…", None))
            top = QTreeWidgetItem()
            top.setData(0, Qt.UserRole, j["tag"])
            if st:
                stale = st["state"] == "running" and now - st.get("updated", now) > 600
                icon = "⚠️" if stale else STATE_ICON.get(st["state"], "⚙️")
                top.setText(0, f"{icon}  {st.get('title') or j['title']}")
                top.setText(1, "GitHub'dan 10 dk'dır haber yok — canlı kayda bak" if stale else st.get("stage", ""))
                items = st.get("items", [])
                overall = sum(i.get("progress", 0) for i in items) / len(items) if items else 0
                self.tree.addTopLevelItem(top)
                self._bar(top, overall)
                for it in items:
                    child = QTreeWidgetItem([it["label"], (it.get("text") or "") + (f" — {it['error']}" if it.get("error") else ""), "", ""])
                    child.setData(0, Qt.UserRole, j["tag"])
                    top.addChild(child)
                    self._bar(child, it.get("progress", 0))
                    for f in it.get("files", []):
                        state = {"done": f"✓ {human(f['total'])}", "error": f"Hata: {f.get('error', '')}"}.get(
                            f["state"], f"{human(f['done'])}" + (f" / {human(f['total'])}" if f.get("total") else ""))
                        g = QTreeWidgetItem([f["label"], state, "", f"{human(f.get('speed', 0))}/sn" if f["state"] == "active" else ""])
                        g.setData(0, Qt.UserRole, j["tag"])
                        child.addChild(g)
                        if f.get("total"):
                            self._bar(g, f["done"] / max(f["total"], 1))
                if st.get("trailer"):
                    t = st["trailer"]
                    child = QTreeWidgetItem(["🎞️ Fragman", t.get("text", ""), "", ""])
                    child.setData(0, Qt.UserRole, j["tag"])
                    top.addChild(child)
                for w in st.get("warnings", []):
                    wi = QTreeWidgetItem(["⚠ Uyarı", w, "", ""])
                    wi.setData(0, Qt.UserRole, j["tag"])
                    top.addChild(wi)
                top.setData(1, Qt.UserRole, st.get("run_url", ""))
            else:
                top.setText(0, f"⏳  {j['title']}")
                top.setText(1, head or "Sırada")
                self.tree.addTopLevelItem(top)
            if j["tag"] in open_tags or (st and st.get("state") == "running"):
                top.setExpanded(True)
                for k in range(top.childCount()):
                    top.child(k).setExpanded(True)
            if j["tag"] == selected:
                self.tree.setCurrentItem(top)

    def _selected_job(self) -> dict | None:
        it = self.tree.currentItem()
        if not it:
            return None
        tag = it.data(0, Qt.UserRole)
        return next((j for j in self.jobs() if j["tag"] == tag), None)

    def open_log(self):
        j = self._selected_job()
        if not j:
            return
        st = self.statuses.get(j["tag"], ("", None))[1]
        if st and st.get("run_url"):
            webbrowser.open(st["run_url"])
        else:
            webbrowser.open(f"https://github.com/{self.config.repo}/actions/workflows/link-ingest.yml")

    def cancel_selected(self):
        j = self._selected_job()
        gh = self.config.gh()
        if not j or not gh:
            return

        def work():
            rel = gh.req("GET", f"/releases/{j['id']}").json()
            gh.req("POST", rel["upload_url"].split("{")[0], params={"name": "cancel"}, data=b"1", headers={"Content-Type": "text/plain"})
        run_async(work, on_done=lambda _: self.check.setText("İptal isteği gönderildi (15 sn içinde durur)."),
                  on_error=lambda m: error_box(self, m))

    def remove_selected(self):
        j = self._selected_job()
        gh = self.config.gh()
        if not j:
            return
        self.save_jobs([x for x in self.jobs() if x["tag"] != j["tag"]])
        self.statuses.pop(j["tag"], None)
        self.render()
        if gh:
            run_async(gh.delete_release, j["tag"])
