"""MKV içindeki metin altyazıları ffmpeg olmadan çıkarır.

ffmpeg, MKVToolNix'in WebVTT için kullandığı `S_TEXT/WEBVTT` kodunu tanımıyor
("no decoder found"). Bu modül Matroska dosyasını doğrudan okuyup altyazı
bloklarını zamanlarıyla birlikte WebVTT'ye çevirir. S_TEXT/UTF8 (SRT) ve
D_WEBVTT/* da desteklenir. Video/ses blokları okunmadan atlanır, 2+ GB dosyalarda
da birkaç saniye sürer.
"""
from __future__ import annotations

import io
from dataclasses import dataclass, field
from pathlib import Path

# Matroska / EBML eleman kimlikleri
SEGMENT = 0x18538067
INFO = 0x1549A966
TIMESTAMP_SCALE = 0x2AD7B1
TRACKS = 0x1654AE6B
TRACK_ENTRY = 0xAE
TRACK_NUMBER = 0xD7
TRACK_TYPE = 0x83
CODEC_ID = 0x86
CODEC_PRIVATE = 0x63A2
DEFAULT_DURATION = 0x23E383
LANGUAGE = 0x22B59C
LANGUAGE_BCP47 = 0x22B59D
CLUSTER = 0x1F43B675
CLUSTER_TIMESTAMP = 0xE7
SIMPLE_BLOCK = 0xA3
BLOCK_GROUP = 0xA0
BLOCK = 0xA1
BLOCK_DURATION = 0x9B
BLOCK_ADDITIONS = 0x75A1
BLOCK_MORE = 0xA6
BLOCK_ADDITIONAL = 0xA5
BLOCK_ADD_ID = 0xEE

TEXT_CODECS = ("S_TEXT/WEBVTT", "S_TEXT/UTF8", "S_TEXT/ASCII", "D_WEBVTT/SUBTITLES", "D_WEBVTT/CAPTIONS", "D_WEBVTT/DESCRIPTIONS")
UNKNOWN = -1


@dataclass
class Track:
    index: int              # ffprobe akış sırası (TrackEntry sırası)
    number: int
    type: int = 0
    codec: str = ""
    private: bytes = b""
    language: str = ""
    default_duration_ns: int = 0


@dataclass
class Cue:
    start_ns: int
    duration_ns: int | None
    text: str
    settings: str = ""
    ident: str = ""


@dataclass
class _State:
    scale: int = 1_000_000
    tracks: list[Track] = field(default_factory=list)


def _vint(f, keep_marker: bool) -> tuple[int, int]:
    first = f.read(1)
    if not first:
        raise EOFError
    b = first[0]
    length = 1
    mask = 0x80
    while length <= 8 and not (b & mask):
        mask >>= 1
        length += 1
    if length > 8:
        raise ValueError("Geçersiz EBML")
    value = b if keep_marker else b & (mask - 1)
    rest = f.read(length - 1)
    if len(rest) != length - 1:
        raise EOFError
    for x in rest:
        value = (value << 8) | x
    if not keep_marker and value == (1 << (7 * length)) - 1:
        return UNKNOWN, length
    return value, length


def _header(f) -> tuple[int, int, int]:
    eid, a = _vint(f, True)
    size, b = _vint(f, False)
    return eid, size, a + b


def _uint(data: bytes) -> int:
    return int.from_bytes(data, "big") if data else 0


def _children(f, end: int):
    while f.tell() < end:
        try:
            eid, size, _ = _header(f)
        except EOFError:
            return
        start = f.tell()
        yield eid, size, start
        f.seek(start + size if size != UNKNOWN else end)


def _parse_tracks(f, end: int, st: _State) -> None:
    for eid, size, start in _children(f, end):
        if eid != TRACK_ENTRY:
            continue
        t = Track(index=len(st.tracks), number=0)
        for cid, csize, cstart in _children(f, start + size):
            f.seek(cstart)
            data = f.read(min(csize, 1 << 20))
            if cid == TRACK_NUMBER:
                t.number = _uint(data)
            elif cid == TRACK_TYPE:
                t.type = _uint(data)
            elif cid == CODEC_ID:
                t.codec = data.rstrip(b"\0").decode("ascii", "replace")
            elif cid == CODEC_PRIVATE:
                t.private = data
            elif cid in (LANGUAGE, LANGUAGE_BCP47):
                t.language = t.language or data.rstrip(b"\0").decode("ascii", "replace")
            elif cid == DEFAULT_DURATION:
                t.default_duration_ns = _uint(data)
        st.tracks.append(t)


def _open_segment(f) -> tuple[int, int]:
    f.seek(0, io.SEEK_END)
    file_end = f.tell()
    f.seek(0)
    while f.tell() < file_end:
        eid, size, _ = _header(f)
        if eid == SEGMENT:
            start = f.tell()
            return start, (file_end if size == UNKNOWN else min(file_end, start + size))
        f.seek(f.tell() + size)
    raise ValueError("Matroska segmenti bulunamadı")


def read_tracks(path: str | Path) -> list[Track]:
    """Sadece başlığı okur (hızlı)."""
    st = _State()
    with open(path, "rb", buffering=1 << 16) as f:
        seg_start, seg_end = _open_segment(f)
        for eid, size, start in _children(f, seg_end):
            if eid == INFO:
                for cid, csize, cstart in _children(f, start + size):
                    if cid == TIMESTAMP_SCALE:
                        f.seek(cstart)
                        st.scale = _uint(f.read(csize))
            elif eid == TRACKS:
                _parse_tracks(f, start + size, st)
                return st.tracks
            elif eid == CLUSTER:
                break
    return st.tracks


def extract_cues(path: str | Path, stream_index: int) -> tuple[Track, list[Cue], int]:
    st = _State()
    target: Track | None = None
    cues: list[Cue] = []
    with open(path, "rb", buffering=1 << 20) as f:
        seg_start, seg_end = _open_segment(f)
        f.seek(seg_start)
        cluster_ts = 0
        pos = seg_start
        while pos < seg_end:
            f.seek(pos)
            try:
                eid, size, hlen = _header(f)
            except EOFError:
                break
            start = f.tell()
            if eid == CLUSTER:
                pos = start  # kümenin içine gir (boyutu bilinmese bile çalışır)
                continue
            if size == UNKNOWN:
                break
            pos = start + size
            if eid == INFO:
                for cid, csize, cstart in _children(f, start + size):
                    if cid == TIMESTAMP_SCALE:
                        f.seek(cstart)
                        st.scale = _uint(f.read(csize))
            elif eid == TRACKS:
                _parse_tracks(f, start + size, st)
                target = next((t for t in st.tracks if t.index == stream_index), None)
                if target is None:
                    raise ValueError(f"MKV'de {stream_index}. akış yok")
            elif eid == CLUSTER_TIMESTAMP:
                cluster_ts = _uint(f.read(size))
            elif eid == SIMPLE_BLOCK and target is not None:
                num, _ = _vint(f, False)
                if num == target.number:
                    rest = f.read(size - (f.tell() - start))
                    cues.append(_cue(rest, cluster_ts, None, b"", st.scale))
            elif eid == BLOCK_GROUP and target is not None:
                block, dur, add = None, None, b""
                for cid, csize, cstart in _children(f, start + size):
                    f.seek(cstart)
                    if cid == BLOCK:
                        num, n = _vint(f, False)
                        if num != target.number:
                            block = None
                            break
                        block = f.read(csize - n)
                    elif cid == BLOCK_DURATION:
                        dur = _uint(f.read(csize))
                    elif cid == BLOCK_ADDITIONS:
                        for mid, msize, mstart in _children(f, cstart + csize):
                            if mid != BLOCK_MORE:
                                continue
                            add_id, add_data = 1, b""
                            for aid, asize, astart in _children(f, mstart + msize):
                                f.seek(astart)
                                if aid == BLOCK_ADD_ID:
                                    add_id = _uint(f.read(asize))
                                elif aid == BLOCK_ADDITIONAL:
                                    add_data = f.read(asize)
                            if add_id == 1:
                                add = add_data
                if block is not None:
                    cues.append(_cue(block, cluster_ts, dur, add, st.scale))
    if target is None:
        raise ValueError("MKV'de iz listesi bulunamadı")
    return target, cues, st.scale


def _cue(data: bytes, cluster_ts: int, duration: int | None, additional: bytes, scale: int) -> Cue:
    rel = int.from_bytes(data[0:2], "big", signed=True)
    flags = data[2]
    payload = data[3:]
    if flags & 0x06:  # lacing: altyazıda olmaz, ilk kareyi al
        payload = payload[1:]
    text = payload.decode("utf-8", "replace").replace("\r\n", "\n").strip("\n")
    settings = ident = ""
    if additional:
        lines = additional.decode("utf-8", "replace").split("\n")
        settings = lines[0].strip() if lines else ""
        ident = lines[1].strip() if len(lines) > 1 else ""
    return Cue((cluster_ts + rel) * scale, duration * scale if duration is not None else None, text, settings, ident)


def _ts(ns: int) -> str:
    ms = max(ns, 0) // 1_000_000
    h, ms = divmod(ms, 3_600_000)
    m, ms = divmod(ms, 60_000)
    s, ms = divmod(ms, 1000)
    return f"{h:02d}:{m:02d}:{s:02d}.{ms:03d}"


def to_webvtt(path: str | Path, stream_index: int) -> str:
    track, cues, _scale = extract_cues(path, stream_index)
    if track.codec not in TEXT_CODECS:
        raise ValueError(f"Desteklenmeyen altyazı türü: {track.codec}")
    out = []
    header = track.private.decode("utf-8", "replace").strip() if track.codec == "S_TEXT/WEBVTT" else ""
    out.append(header if header.startswith("WEBVTT") else "WEBVTT")
    out.append("")
    cues.sort(key=lambda c: c.start_ns)
    for i, c in enumerate(cues):
        text, settings, ident = c.text, c.settings, c.ident
        if track.codec.startswith("D_WEBVTT"):
            # WebM: "kimlik\nayarlar\nmetin"
            parts = text.split("\n", 2)
            ident, settings, text = (parts + ["", "", ""])[:3]
        if not text.strip():
            continue
        dur = c.duration_ns or track.default_duration_ns
        if not dur:
            nxt = cues[i + 1].start_ns if i + 1 < len(cues) else c.start_ns + 4_000_000_000
            dur = max(min(nxt - c.start_ns, 7_000_000_000), 500_000_000)
        if ident:
            out.append(ident)
        out.append(f"{_ts(c.start_ns)} --> {_ts(c.start_ns + dur)}" + (f" {settings}" if settings else ""))
        out.append(text.replace("\n\n", "\n"))
        out.append("")
    if len(out) <= 2:
        raise ValueError("Altyazıda hiç satır bulunamadı")
    return "\n".join(out) + "\n"
