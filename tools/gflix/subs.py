"""Altyazıyı ffmpeg'siz WebVTT'ye çevirir (hızlı yükleme için): SRT / VTT, UTF-8 ya da Windows-1254."""
from __future__ import annotations

import re

_TIME = re.compile(r"^\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})[,.](\d{1,3})(.*)$")


def decode_text(data: bytes) -> str:
    for enc in ("utf-8-sig", "cp1254", "iso-8859-9", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _ts(h: str, m: str, s: str, ms: str) -> str:
    return f"{int(h):02d}:{int(m):02d}:{int(s):02d}.{ms.ljust(3, '0')[:3]}"


def to_webvtt(data: bytes) -> str:
    text = decode_text(data).replace("\r\n", "\n").replace("\r", "\n").lstrip("﻿")
    if text.lstrip().startswith("WEBVTT"):
        return text.lstrip()
    if re.search(r"^\[Script Info\]", text, re.M | re.I):
        raise ValueError("ASS/SSA altyazı hızlı modda desteklenmiyor; SRT ya da VTT ver (ya da 'Birleştir' modunu kullan).")
    out = ["WEBVTT", ""]
    cues = 0
    for block in re.split(r"\n[ \t]*\n", text.strip()):
        lines = block.split("\n")
        while lines and not _TIME.match(lines[0]):
            lines = lines[1:]          # sıra numarası vb.
        if not lines:
            continue
        m = _TIME.match(lines[0])
        body = [ln for ln in lines[1:] if ln.strip()]
        if not body:
            continue
        out.append(f"{_ts(*m.groups()[0:4])} --> {_ts(*m.groups()[4:8])}")
        out += body
        out.append("")
        cues += 1
    if not cues:
        raise ValueError("Altyazı dosyası anlaşılamadı (SRT ya da VTT olmalı).")
    return "\n".join(out)
