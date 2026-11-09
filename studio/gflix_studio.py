#!/usr/bin/env python3
"""G-Flix Stüdyo — Windows için içerik yükleme ve yönetim uygulaması.

Geliştirme ortamında:  pip install -r studio/requirements.txt && python studio/gflix_studio.py
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "tools"))

from PySide6.QtWidgets import QApplication  # noqa: E402

from gstudio.main_window import MainWindow  # noqa: E402
from gstudio.style import QSS, app_icon, load_fonts  # noqa: E402


def selftest() -> int:
    """CI'da paketlenmiş exe'nin açılabildiğini doğrular (pencere göstermeden)."""
    import os
    import traceback
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    out = Path(os.environ.get("GFLIX_SELFTEST_LOG", "selftest.log"))
    lines = []
    try:
        from gflix import media
        app = QApplication(sys.argv)
        load_fonts()
        app.setStyleSheet(QSS)
        w = MainWindow()
        w.show()
        app.processEvents()
        lines.append(f"ffmpeg: {media.FFMPEG} -> {media.tools_available()}")
        lines.append(f"pages: {w.stack.count()}")
        ok = media.tools_available() and w.stack.count() == 5
        w.queue.shutdown()
        lines.append("OK" if ok else "FAIL")
        return 0 if ok else 1
    except Exception:
        lines.append(traceback.format_exc())
        return 2
    finally:
        out.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    if "--selftest" in sys.argv:
        return selftest()
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("GFlix.Studio")
        except Exception:
            pass
    app = QApplication(sys.argv)
    app.setApplicationName("G-Flix Stüdyo")
    app.setOrganizationName("GFlix")
    load_fonts()
    app.setStyleSheet(QSS)
    app.setWindowIcon(app_icon())
    w = MainWindow()
    w.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
