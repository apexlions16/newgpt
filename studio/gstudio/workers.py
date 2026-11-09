"""Arayüzü dondurmadan arka planda iş yaptırma."""
from __future__ import annotations

import traceback

from PySide6.QtCore import QObject, QRunnable, QThreadPool, Signal

_alive: set = set()


class _Signals(QObject):
    done = Signal(object)
    error = Signal(str)


class _Task(QRunnable):
    def __init__(self, fn, args, kwargs):
        super().__init__()
        self.fn, self.args, self.kwargs = fn, args, kwargs
        self.signals = _Signals()

    def run(self):
        try:
            result = self.fn(*self.args, **self.kwargs)
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            self.signals.error.emit(str(e) or e.__class__.__name__)
        else:
            self.signals.done.emit(result)


def run_async(fn, *args, on_done=None, on_error=None, **kwargs) -> None:
    task = _Task(fn, args, kwargs)
    _alive.add(task.signals)

    def finish(*_):
        _alive.discard(task.signals)

    if on_done:
        task.signals.done.connect(on_done)
    if on_error:
        task.signals.error.connect(on_error)
    task.signals.done.connect(finish)
    task.signals.error.connect(finish)
    QThreadPool.globalInstance().start(task)
