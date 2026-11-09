# -*- mode: python ; coding: utf-8 -*-
# PyInstaller paket tanımı:  cd studio && pyinstaller --noconfirm gflix_studio.spec
# FFMPEG_DIR ortam değişkeni ffmpeg(.exe) ve ffprobe(.exe) içeren klasörü göstermeli.
import os

binaries = []
ff = os.environ.get("FFMPEG_DIR")
if ff:
    for name in ("ffmpeg.exe", "ffprobe.exe", "ffmpeg", "ffprobe"):
        p = os.path.join(ff, name)
        if os.path.exists(p):
            binaries.append((p, "."))

a = Analysis(
    ["gflix_studio.py"],
    pathex=[".", os.path.join("..", "tools")],
    binaries=binaries,
    datas=[("assets", "assets")],
    hiddenimports=["keyring.backends.Windows", "gflix.catalog", "gflix.manage", "gflix.recipe"],
    excludes=["tkinter", "matplotlib", "numpy", "PySide6.QtWebEngineCore", "PySide6.QtQml", "PySide6.QtQuick",
              "PySide6.Qt3DCore", "PySide6.QtMultimedia", "PySide6.QtPdf", "PySide6.QtCharts"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="GFlixStudio",
    console=False,
    icon="assets/gflix.ico",
    version=None,
)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name="GFlixStudio")
