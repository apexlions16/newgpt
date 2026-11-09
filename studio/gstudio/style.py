"""Koyu tema, yazı tipleri ve ortak küçük bileşenler."""
from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QFont, QFontDatabase, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import QComboBox, QFrame, QLabel, QMessageBox, QPushButton, QWidget

RED = "#E50914"
BG = "#141414"
PANEL = "#1F1F1F"
CARD = "#2A2A2A"
TEXT = "#FFFFFF"
MUTED = "#B3B3B3"

LANGS = [("tr", "Türkçe"), ("en", "İngilizce"), ("de", "Almanca"), ("fr", "Fransızca"), ("es", "İspanyolca"),
         ("it", "İtalyanca"), ("ja", "Japonca"), ("ko", "Korece"), ("ru", "Rusça"), ("ar", "Arapça"), ("und", "Belirsiz")]


def asset(name: str) -> Path:
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base / "assets" / name


_fonts_loaded = False
BEBAS = "Bebas Neue"


def load_fonts() -> None:
    global _fonts_loaded
    if _fonts_loaded:
        return
    for f in ("bebas_neue.ttf", "inter_regular.ttf", "inter_semibold.ttf"):
        p = asset(f)
        if p.exists():
            QFontDatabase.addApplicationFont(str(p))
    _fonts_loaded = True


QSS = f"""
* {{ font-family: "Inter", "Segoe UI", sans-serif; font-size: 13px; color: {TEXT}; }}
QMainWindow, QDialog, QWidget#page {{ background: {BG}; }}
QWidget#sidebar {{ background: #000000; }}
QListWidget#nav {{ background: #000000; border: none; outline: 0; }}
QListWidget#nav::item {{ padding: 12px 18px; color: {MUTED}; border-left: 3px solid transparent; }}
QListWidget#nav::item:selected {{ color: white; background: #1a1a1a; border-left: 3px solid {RED}; }}
QListWidget#nav::item:hover {{ color: white; }}
QLabel#h1 {{ font-size: 22px; font-weight: 600; }}
QLabel#h2 {{ font-size: 15px; font-weight: 600; }}
QLabel#muted {{ color: {MUTED}; }}
QLabel#hint {{ color: {MUTED}; font-size: 12px; }}
QLineEdit, QSpinBox, QComboBox, QPlainTextEdit, QTextEdit {{
    background: #333333; border: 1px solid #333333; border-radius: 4px; padding: 6px 8px; selection-background-color: {RED};
}}
QLineEdit:focus, QSpinBox:focus, QComboBox:focus, QPlainTextEdit:focus {{ border: 1px solid #8c8c8c; }}
QComboBox QAbstractItemView {{ background: #2b2b2b; selection-background-color: {RED}; border: 1px solid #444; }}
QPushButton {{ background: #333333; border: none; border-radius: 4px; padding: 8px 16px; font-weight: 600; }}
QPushButton:hover {{ background: #444444; }}
QPushButton:disabled {{ color: #777; background: #262626; }}
QPushButton#primary {{ background: {RED}; }}
QPushButton#primary:hover {{ background: #f6121d; }}
QPushButton#white {{ background: white; color: black; }}
QPushButton#white:hover {{ background: #dddddd; }}
QPushButton#danger {{ background: transparent; color: #ff6b6b; border: 1px solid #5a2a2a; }}
QPushButton#danger:hover {{ background: #3a1414; }}
QPushButton#link {{ background: transparent; color: {MUTED}; padding: 4px 8px; }}
QPushButton#link:hover {{ color: white; }}
QTableWidget {{ background: {PANEL}; gridline-color: #2c2c2c; border: none; border-radius: 4px; }}
QHeaderView::section {{ background: #262626; color: {MUTED}; border: none; padding: 6px; font-weight: 600; }}
QTableWidget::item {{ padding: 4px; }}
QTableWidget QComboBox {{ padding: 2px 6px; margin: 2px; }}
QTableWidget::item:selected {{ background: #3a3a3a; }}
QListWidget {{ background: {BG}; border: none; outline: 0; }}
QListWidget::item:selected {{ background: #2a2a2a; border-radius: 4px; }}
QListWidget#files::item {{ padding: 8px; border-bottom: 1px solid #222; }}
QTabWidget::pane {{ border: none; }}
QTabBar::tab {{ background: transparent; color: {MUTED}; padding: 10px 18px; font-weight: 600; border-top: 3px solid transparent; }}
QTabBar::tab:selected {{ color: white; border-top: 3px solid {RED}; }}
QProgressBar {{ background: #333; border: none; border-radius: 3px; height: 8px; text-align: center; font-size: 10px; }}
QProgressBar::chunk {{ background: {RED}; border-radius: 3px; }}
QScrollArea {{ border: none; background: transparent; }}
QScrollBar:vertical {{ background: transparent; width: 10px; }}
QScrollBar::handle:vertical {{ background: #444; border-radius: 5px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
QCheckBox::indicator {{ width: 16px; height: 16px; }}
QFrame#card {{ background: {PANEL}; border-radius: 6px; }}
QFrame#drop {{ border: 2px dashed #555; border-radius: 10px; background: #1a1a1a; }}
QFrame#drop[hover="true"] {{ border-color: {RED}; background: #221416; }}
QToolTip {{ background: #222; color: white; border: 1px solid #444; }}
QMenu {{ background: #222; border: 1px solid #444; }}
QMenu::item:selected {{ background: {RED}; }}
"""


def logo_label(text: str = "G-FLIX", size: int = 34) -> QLabel:
    lbl = QLabel(text)
    f = QFont(BEBAS)
    f.setPixelSize(size)
    f.setLetterSpacing(QFont.AbsoluteSpacing, 1.5)
    lbl.setFont(f)
    lbl.setStyleSheet(f"color: {RED}; font-family: '{BEBAS}'; font-size: {size}px; letter-spacing: 1px;")
    return lbl


def app_icon() -> QIcon:
    ico = asset("gflix.ico")
    if ico.exists():
        return QIcon(str(ico))
    pm = QPixmap(256, 256)
    pm.fill(QColor("black"))
    p = QPainter(pm)
    f = QFont(BEBAS)
    f.setPixelSize(220)
    p.setFont(f)
    p.setPen(QColor(RED))
    p.drawText(pm.rect(), Qt.AlignCenter, "G")
    p.end()
    return QIcon(pm)


def button(text: str, kind: str = "", on_click=None) -> QPushButton:
    b = QPushButton(text)
    if kind:
        b.setObjectName(kind)
    b.setCursor(Qt.PointingHandCursor)
    if on_click:
        b.clicked.connect(on_click)
    return b


def label(text: str, kind: str = "", wrap: bool = False) -> QLabel:
    l = QLabel(text)
    if kind:
        l.setObjectName(kind)
    l.setWordWrap(wrap)
    return l


def lang_combo(selected: str = "und") -> QComboBox:
    c = QComboBox()
    for code, name in LANGS:
        c.addItem(name, code)
    if selected not in [code for code, _ in LANGS]:
        c.addItem(selected, selected)
    c.setCurrentIndex(max(0, c.findData(selected)))
    return c


def hline() -> QFrame:
    f = QFrame()
    f.setFixedHeight(1)
    f.setStyleSheet("background: #2a2a2a;")
    return f


def error_box(parent: QWidget, text: str, title: str = "Hata") -> None:
    QMessageBox.critical(parent, title, text)


def confirm(parent: QWidget, text: str, title: str = "Emin misin?") -> bool:
    return QMessageBox.question(parent, title, text, QMessageBox.Yes | QMessageBox.No, QMessageBox.No) == QMessageBox.Yes


def placeholder_poster(text: str, w: int = 150, h: int = 225) -> QPixmap:
    pm = QPixmap(w, h)
    pm.fill(QColor(CARD))
    p = QPainter(pm)
    p.setPen(QColor(MUTED))
    f = QFont("Inter")
    f.setPixelSize(13)
    f.setBold(True)
    p.setFont(f)
    p.drawText(pm.rect().adjusted(8, 8, -8, -8), Qt.AlignCenter | Qt.TextWordWrap, text)
    p.end()
    return pm


def fit_poster(pm: QPixmap, w: int = 150, h: int = 225) -> QPixmap:
    scaled = pm.scaled(w, h, Qt.KeepAspectRatioByExpanding, Qt.SmoothTransformation)
    x = (scaled.width() - w) // 2
    y = (scaled.height() - h) // 2
    return scaled.copy(x, y, w, h)


def dim(pm: QPixmap) -> QPixmap:
    out = QPixmap(pm.size())
    out.fill(Qt.transparent)
    p = QPainter(out)
    p.setOpacity(0.25)
    p.drawPixmap(0, 0, pm)
    p.end()
    return out
