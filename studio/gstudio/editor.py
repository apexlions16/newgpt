"""Yapım düzenleme: bilgiler, kapaklar (sabitle/gizle), fragman, bölümler, silme."""
from __future__ import annotations

from PySide6.QtCore import QSize, Qt, Signal
from PySide6.QtGui import QIcon
from PySide6.QtWidgets import (QDialog, QFileDialog, QFormLayout, QHBoxLayout, QHeaderView, QLineEdit, QListView,
                               QListWidget, QListWidgetItem, QPlainTextEdit, QTableWidget, QTableWidgetItem,
                               QTabWidget, QVBoxLayout, QWidget)

from gflix import manage, media
from gflix.tmdb import img
from gflix.uploader import UploadJob

from .images import ImageCache
from .style import button, confirm, dim, error_box, fit_poster, label, placeholder_poster
from .tmdb_dialog import TmdbSearchDialog
from .workers import run_async

ART_KINDS = [("posters", "Kapaklar", QSize(120, 180), "w342"),
             ("backdrops", "Arka planlar", QSize(240, 135), "w300"),
             ("logos", "Logolar", QSize(200, 90), "w300")]
LANG_NAMES = {"tr": "Türkçe", "en": "İngilizce", None: "Yazısız"}


class ArtPicker(QWidget):
    """TMDB görsellerini gösterir; tıklayınca sabitle / gizle."""

    def __init__(self, kind: str, size: QSize, thumb: str, prefs: dict):
        super().__init__()
        self.kind, self.size, self.thumb = kind, size, thumb
        self.pin = prefs.get("pin")
        self.hidden = set(prefs.get("hidden") or [])
        self.pix: dict[str, object] = {}
        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 8, 0, 0)
        bar = QHBoxLayout()
        self.info = label("Bir görsel seç, sonra:", "hint")
        bar.addWidget(self.info, 1)
        bar.addWidget(button("★ Sabitle", "", self.toggle_pin))
        bar.addWidget(button("Gizle / Göster", "", self.toggle_hidden))
        bar.addWidget(button("Varsayılana dön", "link", self.reset))
        lay.addLayout(bar)
        self.list = QListWidget()
        self.list.setViewMode(QListView.IconMode)
        self.list.setIconSize(size)
        self.list.setGridSize(QSize(size.width() + 22, size.height() + 46))
        self.list.setResizeMode(QListView.Adjust)
        self.list.setMovement(QListView.Static)
        self.list.setSelectionMode(QListWidget.ExtendedSelection)
        lay.addWidget(self.list, 1)

    def set_images(self, images: list[dict]):
        self.list.clear()
        images = sorted(images, key=lambda i: ({"tr": 0, "en": 1, None: 2}.get(i.get("iso_639_1"), 3), -(i.get("vote_average") or 0)))
        for i in images:
            path = i["file_path"]
            item = QListWidgetItem(QIcon(placeholder_poster("", self.size.width(), self.size.height())), "")
            item.setData(Qt.UserRole, path)
            item.setData(Qt.UserRole + 1, i.get("iso_639_1"))
            self.list.addItem(item)
            self._decorate(item)
            ImageCache.get().load(img(path, self.thumb), lambda pm, it=item, p=path: self._got(it, p, pm))
        self.info.setText(f"{len(images)} görsel · Gizlenenler uygulamada gösterilmez. Sabitlenen her profilde hep kullanılır."
                          if images else "TMDB'de görsel bulunamadı.")

    def _got(self, item, path, pm):
        self.pix[path] = fit_poster(pm, self.size.width(), self.size.height()) if self.kind != "logos" else \
            pm.scaled(self.size, Qt.KeepAspectRatio, Qt.SmoothTransformation)
        self._decorate(item)

    def _decorate(self, item: QListWidgetItem):
        path = item.data(Qt.UserRole)
        lang = item.data(Qt.UserRole + 1)
        tags = [LANG_NAMES.get(lang, (lang or "").upper())]
        if path == self.pin:
            tags.insert(0, "★ SABİT")
        if path in self.hidden:
            tags.insert(0, "GİZLİ")
        item.setText(" · ".join(tags))
        pm = self.pix.get(path)
        if pm is not None:
            item.setIcon(QIcon(dim(pm) if path in self.hidden else pm))

    def _redraw(self):
        for i in range(self.list.count()):
            self._decorate(self.list.item(i))

    def toggle_pin(self):
        it = self.list.currentItem()
        if not it:
            return
        p = it.data(Qt.UserRole)
        self.pin = None if self.pin == p else p
        self.hidden.discard(p)
        self._redraw()

    def toggle_hidden(self):
        for it in self.list.selectedItems():
            p = it.data(Qt.UserRole)
            if p in self.hidden:
                self.hidden.discard(p)
            else:
                self.hidden.add(p)
                if self.pin == p:
                    self.pin = None
        self._redraw()

    def reset(self):
        self.pin, self.hidden = None, set()
        self._redraw()

    def prefs(self) -> dict:
        out = {}
        if self.pin:
            out["pin"] = self.pin
        if self.hidden:
            out["hidden"] = sorted(self.hidden)
        return out


class EditorDialog(QDialog):
    changed = Signal()
    request_trailer = Signal(object)   # UploadJob

    def __init__(self, parent, config, entry: dict):
        super().__init__(parent)
        self.config, self.entry = config, entry
        self.item: dict | None = None
        self.sha: str | None = None
        self.setWindowTitle(entry.get("title", entry["id"]))
        self.resize(1000, 720)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 18, 20, 18)
        head = QHBoxLayout()
        self.poster = label("")
        self.poster.setFixedSize(92, 138)
        self.poster.setPixmap(placeholder_poster(entry.get("title", ""), 92, 138))
        ImageCache.get().load(entry.get("poster"), lambda pm: self.poster.setPixmap(fit_poster(pm, 92, 138)))
        head.addWidget(self.poster)
        info = QVBoxLayout()
        info.addWidget(label(entry.get("title", entry["id"]), "h1"))
        names = entry.get("names") or {}
        sub = " · ".join(x for x in [names.get("en"), names.get("original")] if x and x != entry.get("title"))
        info.addWidget(label(sub, "muted"))
        info.addWidget(label(f"Kimlik: {entry['id']}  ·  TMDB #{entry.get('tmdbId') or '—'}  ·  {'Dizi' if entry.get('type') == 'series' else 'Film'}", "hint"))
        self.state = label("Kayıt yükleniyor…", "hint")
        info.addWidget(self.state)
        info.addStretch(1)
        head.addLayout(info, 1)
        lay.addLayout(head)

        self.tabs = QTabWidget()
        self.tabs.addTab(self._info_tab(), "Bilgiler")
        self.art_pickers: dict[str, ArtPicker] = {}
        for kind, title, size, thumb in ART_KINDS:
            picker = ArtPicker(kind, size, thumb, {})
            self.art_pickers[kind] = picker
            self.tabs.addTab(picker, title)
        self.tabs.addTab(self._trailer_tab(), "Fragman")
        if entry.get("type") == "series":
            self.tabs.addTab(self._episodes_tab(), "Bölümler")
        lay.addWidget(self.tabs, 1)

        foot = QHBoxLayout()
        foot.addWidget(button("Yapımı sil", "danger", self.delete_title))
        foot.addStretch(1)
        foot.addWidget(button("Kapat", "", self.reject))
        self.save_btn = button("Kaydet", "primary", self.save)
        self.save_btn.setEnabled(False)
        foot.addWidget(self.save_btn)
        lay.addLayout(foot)

        gh = config.gh()
        run_async(manage.get_item, gh, entry["id"], on_done=self._item_loaded, on_error=lambda m: self.state.setText("Hata: " + m))
        tmdb = config.tmdb()
        if tmdb and entry.get("tmdbId"):
            kind = "tv" if entry.get("type") == "series" else "movie"
            run_async(tmdb.images, kind, entry["tmdbId"], on_done=self._images_loaded,
                      on_error=lambda m: self.state.setText("TMDB görselleri alınamadı: " + m))
        elif not tmdb:
            for p in self.art_pickers.values():
                p.info.setText("Kapakları görmek için Ayarlar'a TMDB anahtarını gir.")

    # ------------------------------------------------------------ sekmeler
    def _info_tab(self) -> QWidget:
        w = QWidget()
        fl = QFormLayout(w)
        fl.setContentsMargins(8, 16, 8, 8)
        fl.setVerticalSpacing(12)
        self.title_edit = QLineEdit()
        self.title_edit.setPlaceholderText("Boş bırakırsan TMDB'deki Türkçe ad kullanılır")
        fl.addRow("Türkçe ad (elle)", self.title_edit)
        self.en_edit = QLineEdit()
        self.en_edit.setPlaceholderText("Boş bırakırsan TMDB'deki İngilizce ad kullanılır")
        fl.addRow("İngilizce ad (elle)", self.en_edit)
        self.overview_edit = QPlainTextEdit()
        self.overview_edit.setPlaceholderText("Boş bırakırsan TMDB'deki Türkçe özet kullanılır")
        self.overview_edit.setFixedHeight(110)
        fl.addRow("Özet (elle)", self.overview_edit)
        trow = QHBoxLayout()
        self.tmdb_edit = QLineEdit(str(self.entry.get("tmdbId") or ""))
        self.tmdb_edit.setPlaceholderText("TMDB numarası")
        trow.addWidget(self.tmdb_edit, 1)
        trow.addWidget(button("TMDB'de Ara", "white", self.search_tmdb))
        fl.addRow("TMDB eşleşmesi", trow)
        fl.addRow("", label("Yanlış film/dizi eşleştiyse buradan düzelt. Kaydettikten sonra bilgiler ve kapaklar yeniden çekilir.", "hint", wrap=True))
        cur = QPlainTextEdit((self.entry.get("overview") or "")[:2000])
        cur.setReadOnly(True)
        cur.setFixedHeight(90)
        fl.addRow("Şu anki özet", cur)
        return w

    def _trailer_tab(self) -> QWidget:
        w = QWidget()
        l = QVBoxLayout(w)
        l.setContentsMargins(8, 16, 8, 8)
        t = self.entry.get("trailer")
        self.trailer_state = label(
            f"✓ Fragman var ({int((t.get('duration') or 0) // 60)} dk {int((t.get('duration') or 0) % 60)} sn)" if t else "Bu yapımın fragmanı yok.", "h2")
        l.addWidget(self.trailer_state)
        l.addWidget(label("Fragman uygulamada detay sayfasının üstünde ve 'Yeni ve Popüler'de sessiz başlar (profil ayarına göre). "
                          "Yeni dosya yüklersen eskisinin yerine geçer.", "hint", wrap=True))
        row = QHBoxLayout()
        row.addWidget(button("Fragman dosyası seç ve yükle…", "primary", self.upload_trailer))
        self.remove_trailer_btn = button("Fragmanı kaldır", "danger", self.remove_trailer)
        self.remove_trailer_btn.setEnabled(bool(t))
        row.addWidget(self.remove_trailer_btn)
        row.addStretch(1)
        l.addLayout(row)
        l.addStretch(1)
        return w

    def _episodes_tab(self) -> QWidget:
        w = QWidget()
        l = QVBoxLayout(w)
        l.setContentsMargins(8, 16, 8, 8)
        self.ep_table = QTableWidget(0, 5)
        self.ep_table.setHorizontalHeaderLabels(["Sezon", "Bölüm", "Ad", "Ses", "Altyazı"])
        self.ep_table.verticalHeader().setVisible(False)
        self.ep_table.horizontalHeader().setSectionResizeMode(2, QHeaderView.Stretch)
        self.ep_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.ep_table.setEditTriggers(QTableWidget.NoEditTriggers)
        rows = []
        for s in self.entry.get("seasons", []):
            for ep in s.get("episodes", []):
                st = ep.get("stream") or {}
                rows.append((s["number"], ep["number"], ep.get("title") or "", ", ".join(st.get("audio", [])),
                             ", ".join(x.get("lang", "") for x in st.get("subtitles", []))))
        for r in rows:
            i = self.ep_table.rowCount()
            self.ep_table.insertRow(i)
            for c, v in enumerate(r):
                self.ep_table.setItem(i, c, QTableWidgetItem(str(v)))
        l.addWidget(self.ep_table, 1)
        b = QHBoxLayout()
        b.addWidget(label("Yeni bölüm eklemek için 'Yükle' sayfasını kullan.", "hint"), 1)
        b.addWidget(button("Seçili bölümü sil", "danger", self.delete_episode))
        l.addLayout(b)
        return w

    # ------------------------------------------------------------ veri
    def _item_loaded(self, res):
        self.item, self.sha = res
        if not self.item:
            self.state.setText("Bu yapımın kaydı bulunamadı (silinmiş olabilir).")
            return
        ov = self.item.get("overrides") or {}
        self.title_edit.setText(ov.get("title", ""))
        self.en_edit.setText((ov.get("names") or {}).get("en", "") if isinstance(ov.get("names"), dict) else "")
        self.overview_edit.setPlainText(ov.get("overview", ""))
        if self.item.get("tmdbId"):
            self.tmdb_edit.setText(str(self.item["tmdbId"]))
        art = self.item.get("art") or {}
        for kind, picker in self.art_pickers.items():
            prefs = art.get(kind) or {}
            picker.pin = prefs.get("pin")
            picker.hidden = set(prefs.get("hidden") or [])
            picker._redraw()
        self.state.setText("Hazır. Değişiklikler kaydedilince katalog 1-3 dakika içinde güncellenir.")
        self.save_btn.setEnabled(True)

    def _images_loaded(self, images: dict):
        for kind, picker in self.art_pickers.items():
            picker.set_images([i for i in images.get(kind, []) if i.get("file_path")])

    # ------------------------------------------------------------ eylemler
    def search_tmdb(self):
        tmdb = self.config.tmdb()
        if not tmdb:
            error_box(self, "Önce Ayarlar'a TMDB anahtarını gir.")
            return
        names = self.entry.get("names") or {}
        dlg = TmdbSearchDialog(self, tmdb, names.get("original") or self.entry.get("title", ""), self.entry.get("type", "movie"), self.entry.get("year"))
        if dlg.exec() and dlg.result:
            self.tmdb_edit.setText(str(dlg.result["id"]))

    def save(self):
        if not self.item:
            return
        item = dict(self.item)
        ov = dict(item.get("overrides") or {})
        for key, val in (("title", self.title_edit.text().strip()), ("overview", self.overview_edit.toPlainText().strip())):
            if val:
                ov[key] = val
            else:
                ov.pop(key, None)
        en = self.en_edit.text().strip()
        names = dict(ov.get("names") or {}) if isinstance(ov.get("names"), dict) else {}
        if en:
            names["en"] = en
        else:
            names.pop("en", None)
        if names:
            ov["names"] = names
        else:
            ov.pop("names", None)
        if ov:
            item["overrides"] = ov
        else:
            item.pop("overrides", None)
        tid = self.tmdb_edit.text().strip()
        if tid.isdigit():
            item["tmdbId"] = int(tid)
        art = {k: p.prefs() for k, p in self.art_pickers.items() if p.prefs()}
        if art:
            item["art"] = art
        else:
            item.pop("art", None)
        self.save_btn.setEnabled(False)
        self.state.setText("Kaydediliyor…")
        gh = self.config.gh()
        run_async(manage.save_item, gh, item, self.sha, f"Düzenlendi: {item.get('title', item['id'])}",
                  on_done=lambda sha: self._saved(item, sha), on_error=self._save_failed)

    def _saved(self, item, sha):
        self.item, self.sha = item, sha
        self.save_btn.setEnabled(True)
        self.state.setText("✓ Kaydedildi. Katalog birkaç dakika içinde güncellenecek.")
        self.changed.emit()

    def _save_failed(self, msg):
        self.save_btn.setEnabled(True)
        error_box(self, "Kaydedilemedi:\n" + msg)
        self.state.setText("Kaydedilemedi.")

    def upload_trailer(self):
        f, _ = QFileDialog.getOpenFileName(self, "Fragman dosyası seç", "", "Videolar (*.mkv *.mp4 *.m4v *.mov *.webm *.avi);;Tüm dosyalar (*)")
        if not f:
            return
        self.state.setText("Fragman inceleniyor…")

        def analyzed(info):
            media.auto_select(info)
            job = UploadJob(kind="trailer", input=f, title=self.entry.get("title", self.entry["id"]), item_id=self.entry["id"],
                            info=info, options=self.config.encode_options(), parallel=self.config.parallel)
            self.request_trailer.emit(job)
            self.state.setText("Fragman yükleme kuyruğuna eklendi.")
            self.trailer_state.setText("⏳ Fragman yükleniyor (Kuyruk sayfasından izle)")
        run_async(media.analyze, __import__("pathlib").Path(f), on_done=analyzed, on_error=lambda m: error_box(self, m))

    def remove_trailer(self):
        if not confirm(self, "Fragman silinsin mi?"):
            return
        run_async(manage.remove_trailer, self.config.gh(), self.entry["id"],
                  on_done=lambda _: (self.trailer_state.setText("Fragman kaldırıldı."), self.remove_trailer_btn.setEnabled(False), self.changed.emit()),
                  on_error=lambda m: error_box(self, m))

    def delete_episode(self):
        row = self.ep_table.currentRow()
        if row < 0:
            return
        s, e = int(self.ep_table.item(row, 0).text()), int(self.ep_table.item(row, 1).text())
        if not confirm(self, f"S{s:02d}E{e:02d} kalıcı olarak silinsin mi? (video dosyaları da silinir)"):
            return
        run_async(manage.delete_episode, self.config.gh(), self.entry["id"], s, e,
                  on_done=lambda _: (self.ep_table.removeRow(row), self.state.setText("✓ Bölüm silindi."), self.changed.emit(), self._reload_item()),
                  on_error=lambda m: error_box(self, m))

    def _reload_item(self):
        run_async(manage.get_item, self.config.gh(), self.entry["id"], on_done=self._item_loaded)

    def delete_title(self):
        if not confirm(self, f"\"{self.entry.get('title')}\" ve tüm video dosyaları kalıcı olarak silinsin mi?\nBu işlem geri alınamaz."):
            return
        self.state.setText("Siliniyor…")
        run_async(manage.delete_title, self.config.gh(), self.entry["id"],
                  on_done=lambda _: (self.changed.emit(), self.accept()), on_error=lambda m: error_box(self, m))
