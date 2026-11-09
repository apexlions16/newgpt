"""
Canlı kayıt: işçinin ekrana (journal'a) yazdığı her satırın bir kopyası bellekte tutulur; telefondaki
"Canlı Kayıt" paneli bunları ev ağından (lan.py /log) ya da GitHub'dan ("pclog" Release'i) okur.

sys.stdout / sys.stderr sarılır: yazılan her şey önce asıl çıkışa gider (journal aynen sürer), sonra tampona eklenir.
Tampon hata verse bile asıl yazma etkilenmez.
"""
from __future__ import annotations

import sys
import threading
import time
from collections import deque

MAX_LINES = 3000
MAX_LEN = 600


class LiveLog:
    def __init__(self, maxlen: int = MAX_LINES):
        self.lines: deque[tuple[int, float, str]] = deque(maxlen=maxlen)   # (sıra no, zaman, metin)
        self.seq = 0
        self.lock = threading.Lock()
        self._partial: dict[tuple, str] = {}     # (akış, iş parçacığı) → yarım satır

    def add(self, text: str) -> None:
        with self.lock:
            for line in text.splitlines():
                if not line.strip():
                    continue
                self.seq += 1
                self.lines.append((self.seq, time.time(), line[:MAX_LEN]))

    def write_chunk(self, key: int, data: str) -> None:
        """Parça parça gelen yazıyı satıra çevir (print satırı ve sonundaki "\\n"i ayrı yazar; her iş parçacığı kendi
        yarım satırını tutar, aynı anda yazan iş parçacıklarının satırları birbirine karışmaz)."""
        k = (key, threading.get_ident())
        with self.lock:
            buf = self._partial.pop(k, "") + data
            if "\n" not in buf and "\r" not in buf:
                self._partial[k] = buf[-4000:]
                return
            parts = buf.replace("\r", "\n").split("\n")
            rest = parts.pop()
            if rest:
                self._partial[k] = rest[-4000:]
        self.add("\n".join(parts))

    def since(self, after: int, limit: int = 500) -> dict:
        with self.lock:
            out = [[s, int(t), x] for s, t, x in self.lines if s > after]
            first = self.lines[0][0] if self.lines else 0
            seq = self.seq
        return {"seq": seq, "first": first, "lines": out[-limit:]}


class _Tee:
    def __init__(self, orig, log: LiveLog, key: int):
        self._orig, self._log, self._key = orig, log, key

    def write(self, data):
        n = self._orig.write(data)
        try:
            self._log.write_chunk(self._key, data if isinstance(data, str) else str(data))
        except Exception:  # noqa: BLE001  kayıt kopyası asla asıl yazmayı bozmasın
            pass
        return n

    def flush(self):
        return self._orig.flush()

    def __getattr__(self, name):
        return getattr(self._orig, name)


LOG = LiveLog()


def install() -> LiveLog:
    if not isinstance(sys.stdout, _Tee):
        sys.stdout = _Tee(sys.stdout, LOG, 1)
    if not isinstance(sys.stderr, _Tee):
        sys.stderr = _Tee(sys.stderr, LOG, 2)
    return LOG
