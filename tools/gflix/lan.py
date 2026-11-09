"""Telefondan bilgisayara doğrudan (ev ağı üzerinden) dosya aktarımı.

Telefon aynı Wi-Fi'deyse dosyalar GitHub'a uğramadan Linux bilgisayara gelir. Bilgisayar MKVToolNix ile birleştirir
ve sadece çıkan MKV'yi GitHub'a yükler.

Uç noktalar (hepsi X-GFlix-Key başlığı ister):
  GET  /ping                              -> {"ok": true, "host": …, "free": …}
  GET  /log?after=N                       -> canlı kayıt: {"seq", "first", "lines": [[no, zaman, metin], …]}
  GET  /files/<iş>/<ad>                   -> {"size": toplam?, "ranges": [[başlangıç, bitiş), …], "path": …}
  PUT  /files/<iş>/<ad>?offset=N&total=T  -> gövdeyi N. bayttan yazar (parçalar paralel ve sırasız gelebilir)
  POST /files/<iş>/<ad>/done?size=T       -> bütün baytlar geldiyse {"path", "uri"}; eksik varsa 409
  POST /cancel-all                        -> bütün işleri iptal et (telefon: "Tüm işleri iptal et"), {"ok", "cancelled"}

Anahtar: sha256("gflix-lan:" + GitHub token). Telefon ve bilgisayar aynı token'ı bildiği için ayrıca şifre gerekmez;
token'ın kendisi ağda hiç dolaşmaz.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

DEFAULT_PORT = 8765
_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def lan_key(github_token: str) -> str:
    return hashlib.sha256(("gflix-lan:" + github_token).encode()).hexdigest()


def local_ips() -> list[str]:
    """Bu bilgisayarın ev ağındaki adresleri (192.168.x.x, 10.x.x.x …)."""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))     # paket gönderilmez, sadece yönlendirme tablosu okunur
        ips.append(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return [ip for ip in ips if ip.startswith(("192.168.", "10.", "172."))]


def _merge(ranges: list[list[int]]) -> list[list[int]]:
    out: list[list[int]] = []
    for a, b in sorted(ranges):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


class LanServer:
    def __init__(self, root: Path, token: str, port: int = DEFAULT_PORT, log=print, host_name: str = ""):
        self.root = root
        self.key = lan_key(token)
        self.port = port
        self.log = log
        self.host_name = host_name or socket.gethostname()
        self.lock = threading.Lock()
        self.last_progress: dict[str, float] = {}
        self.httpd: ThreadingHTTPServer | None = None
        # Telefondan komut (aynı Wi-Fi'de anında): "cancel_all" → işçinin bütün işleri iptal etmesi. İşçi atar.
        self.on_command = None

    def path_for(self, job: str, name: str) -> Path:
        job, name = _SAFE.sub("_", job)[:80], _SAFE.sub("_", name)[:160]
        if not job or not name or job.startswith(".") or name.startswith("."):
            raise ValueError("geçersiz ad")
        return self.root / job / name

    def _state(self, p: Path) -> dict:
        meta = p.with_name(p.name + ".parcalar.json")
        if meta.exists():
            try:
                return json.loads(meta.read_text())
            except ValueError:
                pass
        return {"total": None, "ranges": []}

    def _save(self, p: Path, st: dict) -> None:
        p.with_name(p.name + ".parcalar.json").write_text(json.dumps(st))

    def start(self) -> None:
        server = self

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt, *a):
                pass

            def _j(self, code: int, obj: dict):
                b = json.dumps(obj, ensure_ascii=False).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(b)))
                self.end_headers()
                self.wfile.write(b)

            def _auth(self) -> bool:
                if self.headers.get("X-GFlix-Key", "") != server.key:
                    self._j(401, {"error": "anahtar yanlış"})
                    return False
                return True

            def _target(self):
                u = urlparse(self.path)
                parts = [unquote(x) for x in u.path.strip("/").split("/")]
                return u, parts

            def do_GET(self):
                if not self._auth():
                    return
                u, parts = self._target()
                if parts == ["ping"]:
                    return self._j(200, {"ok": True, "host": server.host_name, "free": shutil.disk_usage(server.root).free})
                if parts == ["log"]:
                    from . import livelog
                    q = parse_qs(u.query)
                    try:
                        after = int((q.get("after") or ["0"])[0])
                    except ValueError:
                        after = 0
                    return self._j(200, livelog.LOG.since(after))
                if len(parts) == 3 and parts[0] == "files":
                    try:
                        p = server.path_for(parts[1], parts[2])
                    except ValueError as e:
                        return self._j(400, {"error": str(e)})
                    with server.lock:
                        st = server._state(p) if p.exists() else {"total": None, "ranges": []}
                    return self._j(200, {"size": st["total"], "ranges": st["ranges"], "path": str(p)})
                self._j(404, {"error": "yok"})

            def do_PUT(self):
                if not self._auth():
                    return
                u, parts = self._target()
                q = parse_qs(u.query)
                n = int(self.headers.get("Content-Length") or 0)
                if len(parts) != 3 or parts[0] != "files":
                    return self._j(404, {"error": "yok"})
                try:
                    p = server.path_for(parts[1], parts[2])
                    offset = int(q.get("offset", ["0"])[0])
                    total = int(q.get("total", ["0"])[0]) or None
                except ValueError as e:
                    return self._j(400, {"error": str(e)})
                p.parent.mkdir(parents=True, exist_ok=True)
                if shutil.disk_usage(p.parent).free < n + 2 * 1024 ** 3:
                    return self._j(507, {"error": "bilgisayarda yer kalmadı"})
                with server.lock:
                    if not p.exists():
                        p.touch()
                    st = server._state(p)
                    if total:
                        st["total"] = total
                    server._save(p, st)
                got = 0
                with open(p, "r+b") as f:
                    f.seek(offset)
                    while got < n:
                        chunk = self.rfile.read(min(1024 * 1024, n - got))
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                if got != n:
                    return self._j(400, {"error": f"eksik geldi ({got}/{n})"})
                with server.lock:
                    st = server._state(p)
                    st["ranges"] = _merge(st["ranges"] + [[offset, offset + n]])
                    server._save(p, st)
                    have = sum(b - a for a, b in st["ranges"])
                    now = time.time()
                    if now - server.last_progress.get(str(p), 0) > 10:
                        server.last_progress[str(p)] = now
                        tot = st.get("total")
                        server.log(f"📶 Telefondan geliyor: {parts[1]}/{p.name} {have / 1e9:.2f}" + (f" / {tot / 1e9:.2f} GB (%{have * 100 // tot})" if tot else " GB"))
                self._j(200, {"ok": True, "ranges": st["ranges"]})

            def do_POST(self):
                if not self._auth():
                    return
                u, parts = self._target()
                q = parse_qs(u.query)
                if parts == ["cancel-all"]:
                    if server.on_command is None:
                        return self._j(503, {"error": "işçi hazır değil"})
                    try:
                        return self._j(200, {"ok": True, **(server.on_command("cancel_all") or {})})
                    except Exception as e:  # noqa: BLE001
                        return self._j(500, {"error": str(e)[:300]})
                if len(parts) != 4 or parts[0] != "files" or parts[3] != "done":
                    return self._j(404, {"error": "yok"})
                try:
                    p = server.path_for(parts[1], parts[2])
                    size = int(q.get("size", ["0"])[0])
                except ValueError as e:
                    return self._j(400, {"error": str(e)})
                with server.lock:
                    st = server._state(p) if p.exists() else {"ranges": []}
                    if st["ranges"] != [[0, size]]:
                        return self._j(409, {"error": "eksik parçalar var", "ranges": st["ranges"]})
                    with open(p, "r+b") as f:
                        f.truncate(size)
                    p.with_name(p.name + ".parcalar.json").unlink(missing_ok=True)
                server.log(f"📥 Telefondan geldi: {p.name} ({size / 1e9:.2f} GB)")
                self._j(200, {"ok": True, "path": str(p), "uri": p.resolve().as_uri()})

        self.root.mkdir(parents=True, exist_ok=True)
        class Quiet(ThreadingHTTPServer):
            daemon_threads = True

            def handle_error(self, request, client_address):
                # Telefon bağlantıyı yarıda kesti (ping zaman aşımı, ekrandan çıkış): zararsız, kayda yığın basma
                import sys as _sys
                e = _sys.exc_info()[1]
                if isinstance(e, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError, TimeoutError)):
                    return
                super().handle_error(request, client_address)

        self.httpd = Quiet(("0.0.0.0", self.port), H)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def stop(self) -> None:
        if self.httpd:
            self.httpd.shutdown()
