"""Video analizi, HLS paketleme, 2 GB parçalara bölme ve altyazı dönüştürme."""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Callable

from .util import LANG3, LANG_LABEL, LANG_ORDER, Cancelled, norm_lang

# GitHub sınırı dosya başına 2 GiB. Daha küçük parçalar paralel yüklemeyi hızlandırır.
PART_LIMIT = int(os.environ.get("GFLIX_PART_LIMIT", 512 * 1024 ** 2))
TEXT_SUB_CODECS = {"subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text"}
BITMAP_SUB_CODECS = {"hdmv_pgs_subtitle", "pgssub", "dvd_subtitle", "dvdsub", "dvb_subtitle", "dvbsub", "xsub", "dvb_teletext"}
SUB_FILE_EXT = {".vtt": "webvtt", ".srt": "subrip", ".ass": "ass", ".ssa": "ssa"}

_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0  # Windows'ta konsol penceresi açılmasın


def _find_tool(name: str) -> str:
    env = os.environ.get(f"GFLIX_{name.upper()}")
    if env:
        return env
    exe = name + (".exe" if sys.platform == "win32" else "")
    for base in (getattr(sys, "_MEIPASS", None), Path(sys.executable).parent if getattr(sys, "frozen", False) else None):
        if base:
            for cand in (Path(base) / exe, Path(base) / "ffmpeg" / exe):
                if cand.exists():
                    return str(cand)
    return shutil.which(name) or name


FFMPEG = _find_tool("ffmpeg")
FFPROBE = _find_tool("ffprobe")


def tools_available() -> bool:
    return bool(shutil.which(FFMPEG) or Path(FFMPEG).exists()) and bool(shutil.which(FFPROBE) or Path(FFPROBE).exists())


def _run(cmd: list[str], log: Callable[[str], None]) -> None:
    log("$ " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", creationflags=_NO_WINDOW)
    if p.returncode != 0:
        raise RuntimeError(f"ffmpeg hatası:\n{p.stderr[-2000:]}")


def ffprobe(path: Path) -> dict:
    p = subprocess.run(
        [FFPROBE, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", str(path)],
        capture_output=True, text=True, encoding="utf-8", errors="replace", creationflags=_NO_WINDOW,
    )
    if p.returncode != 0:
        raise RuntimeError(f"Dosya okunamadı: {path}\n{p.stderr[-800:]}")
    return json.loads(p.stdout)


# ------------------------------------------------------------------ analiz

@dataclass
class AudioTrack:
    """Seçilebilir ses kanalı. file=None ise ana videonun içindeki akış."""
    stream_index: int
    lang: str
    codec: str = ""
    channels: int = 0
    title: str = ""
    file: str | None = None
    include: bool = True

    def describe(self) -> str:
        bits = [self.codec.upper()] if self.codec else []
        if self.channels:
            bits.append({1: "Mono", 2: "Stereo", 6: "5.1", 8: "7.1"}.get(self.channels, f"{self.channels} kanal"))
        if self.title:
            bits.append(self.title)
        if self.file:
            bits.append(Path(self.file).name)
        return " · ".join(bits)


@dataclass
class SubTrack:
    lang: str
    forced: bool = False
    stream_index: int | None = None   # gömülü
    file: str | None = None           # harici
    attachment: str | None = None     # MKV'ye ek dosya olarak konmuş altyazı (dosya adı)
    codec: str = ""
    title: str = ""
    bitmap: bool = False              # resim tabanlı (PGS/VobSub) -> desteklenmez
    include: bool = True

    def describe(self) -> str:
        if self.file:
            return Path(self.file).name
        if self.attachment:
            return f"MKV eki: {self.attachment}"
        s = f"Gömülü #{self.stream_index} ({self.codec or 'bilinmiyor'})"
        return s + (f" · {self.title}" if self.title else "")


@dataclass
class MediaInfo:
    path: str
    duration: float
    video: dict
    audio: list[AudioTrack]
    subs: list[SubTrack]

    @property
    def video_desc(self) -> str:
        v = self.video
        return f"{v.get('codec_name', '?').upper()} {v.get('width', '?')}x{v.get('height', '?')}"


def analyze(path: Path) -> MediaInfo:
    probe = ffprobe(path)
    streams = probe.get("streams", [])
    videos = [s for s in streams if s["codec_type"] == "video" and not s.get("disposition", {}).get("attached_pic")]
    if not videos:
        raise RuntimeError("Videoda görüntü akışı bulunamadı.")
    audio = [
        AudioTrack(
            stream_index=s["index"], lang=norm_lang(s.get("tags", {}).get("language")),
            codec=s.get("codec_name", ""), channels=int(s.get("channels") or 0), title=s.get("tags", {}).get("title", ""),
        )
        for s in streams if s["codec_type"] == "audio"
    ]
    subs = []
    for s in streams:
        tags = s.get("tags", {}) or {}
        if s["codec_type"] == "subtitle":
            codec = s.get("codec_name") or ""
            bitmap = codec in BITMAP_SUB_CODECS
            subs.append(SubTrack(
                lang=norm_lang(tags.get("language")),
                forced=bool(s.get("disposition", {}).get("forced")) or "forced" in tags.get("title", "").lower(),
                stream_index=s["index"], codec=codec, title=tags.get("title", ""),
                bitmap=bitmap, include=not bitmap,
            ))
        elif s["codec_type"] == "attachment":
            # Bazı MKV'lerde altyazı akış değil, ek dosya olarak durur (ör. turkce.vtt)
            fname = tags.get("filename", "")
            ext = Path(fname).suffix.lower()
            mime = (tags.get("mimetype") or "").lower()
            if ext in SUB_FILE_EXT or mime in ("text/vtt", "application/x-subrip", "text/x-ssa", "text/x-ass"):
                subs.append(SubTrack(
                    lang=guess_lang_from_name(fname), forced="forced" in fname.lower() or "zorunlu" in fname.lower(),
                    stream_index=s["index"], codec=SUB_FILE_EXT.get(ext, "webvtt"), attachment=fname or f"ek_{s['index']}.vtt",
                ))
    if any(s.codec in ("", "unknown", "none") and not s.attachment for s in subs):
        try:
            from . import mkv
            codecs = {t.index: t.codec for t in mkv.read_tracks(path)}
            for s in subs:
                if s.codec in ("", "unknown", "none") and not s.attachment and s.stream_index in codecs:
                    s.codec = codecs[s.stream_index]
                    if s.codec not in mkv.TEXT_CODECS:
                        s.bitmap, s.include = True, False
        except Exception:
            pass
    return MediaInfo(str(path), float(probe.get("format", {}).get("duration") or 0), videos[0], audio, subs)


def auto_select(info: MediaInfo, audio_langs: list[str] | None = None) -> None:
    """Varsayılan seçim: her dilden ilk ses kanalı, her dil/zorunlu türünden bir altyazı."""
    for i, a in enumerate(info.audio):
        if audio_langs:
            if i < len(audio_langs):
                a.lang = norm_lang(audio_langs[i])
            else:
                a.include = False
    seen = set()
    known = any(a.lang != "und" for a in info.audio if a.include)
    for a in info.audio:
        if not a.include:
            continue
        if (known and a.lang == "und") or a.lang in seen:
            a.include = False
        else:
            seen.add(a.lang)
    if not any(a.include for a in info.audio) and info.audio:
        info.audio[0].include = True
    # Dili belirtilmemiş altyazı: başka Türkçe altyazı yoksa Türkçe say (arayüzden değiştirilebilir)
    if not any(s.lang == "tr" and not s.bitmap for s in info.subs):
        for s in info.subs:
            if s.lang == "und" and not s.bitmap and not s.forced:
                s.lang = "tr"
                break
    seen_subs = set()
    for s in info.subs:
        key = (s.lang, s.forced)
        if s.bitmap or key in seen_subs:
            s.include = False
        else:
            seen_subs.add(key)


# ------------------------------------------------------------------ paketleme

@dataclass
class EncodeOptions:
    video_mode: str = "auto"     # auto | copy | h264
    max_height: int = 1080
    crf: int = 21
    preset: str = "veryfast"
    audio_bitrate: str = "192k"
    hls_time: int = 6


def package_hls(
    info: MediaInfo,
    audios: list[AudioTrack],
    out: Path,
    opts: EncodeOptions,
    log: Callable[[str], None] = print,
    progress: Callable[[float], None] = lambda f: None,
    cancel: Callable[[], bool] = lambda: False,
) -> Path:
    if not audios:
        raise RuntimeError("En az bir ses kanalı seçmelisin.")
    audios = sorted(audios, key=lambda a: LANG_ORDER.index(a.lang) if a.lang in LANG_ORDER else 99)
    out.mkdir(parents=True, exist_ok=True)
    cmd = [FFMPEG, "-hide_banner", "-y", "-nostats", "-progress", "pipe:1", "-i", info.path]
    extra_inputs = []
    for a in audios:
        if a.file and a.file not in extra_inputs:
            extra_inputs.append(a.file)
    # Harici ses dosyaları: ffmpeg her girdiyi kendi başlangıcına göre sıfırlar. Siteden ayrı inen dosyalar orijinal
    # zamanı taşıyorsa (ör. video 2.0 sn, ses 1.48 sn'den başlıyor) bu fark kaybolup ses kayar; geri ekle (gflix/sync.py).
    tempos: dict[str, float] = {}
    try:
        if os.environ.get("GFLIX_AUTO_SYNC", "0") != "1":   # varsayılan: kapalı, dosyalar olduğu gibi
            raise ImportError
        from . import sync as av_sync
        main_start = av_sync.format_start(Path(info.path))
        v_dur = av_sync.duration(Path(info.path), "video")
    except Exception:  # noqa: BLE001
        av_sync = None
    for f in extra_inputs:
        if av_sync is not None:
            try:
                pl = av_sync.plan(Path(info.path), Path(f), Path(f).name, video_start=main_start, video_dur=v_dur)
                for n in pl.notes:
                    log("🎚️ " + n)
                if pl.offset_ms:
                    cmd += ["-itsoffset", f"{pl.offset_ms / 1000:.3f}"]
                if pl.tempo:
                    tempos[f] = pl.tempo
            except Exception as e:  # noqa: BLE001
                log(f"senkron ölçülemedi ({Path(f).name}): {e}")
        cmd += ["-i", f]
    cmd += ["-map", f"0:{info.video['index']}"]
    for a in audios:
        cmd += ["-map", f"{extra_inputs.index(a.file) + 1}:a:0" if a.file else f"0:{a.stream_index}"]

    v = info.video
    codec, pix = v.get("codec_name"), v.get("pix_fmt", "")
    height = int(v.get("height") or 0)
    copy_video = opts.video_mode == "copy" or (
        opts.video_mode == "auto" and codec == "h264" and pix in ("yuv420p", "yuvj420p", "") and (not height or height <= opts.max_height)
    )
    if copy_video:
        log(f"Görüntü yeniden kodlanmadan kopyalanıyor ({codec}).")
        cmd += ["-c:v", "copy"]
    else:
        log(f"Görüntü H.264'e çevriliyor ({codec}/{pix}). Bu biraz sürebilir.")
        cmd += [
            "-c:v", "libx264", "-preset", opts.preset, "-crf", str(opts.crf), "-pix_fmt", "yuv420p",
            "-profile:v", "high", "-vf", f"scale=-2:'min({opts.max_height},ih)'",
            "-force_key_frames", f"expr:gte(t,n_forced*{opts.hls_time})", "-sc_threshold", "0",
        ]
    cmd += ["-c:a", "aac", "-b:a", opts.audio_bitrate, "-ac", "2", "-sn", "-dn"]
    for i, a in enumerate(audios):
        if a.file and a.file in tempos:
            cmd += [f"-filter:a:{i}", f"atempo={tempos[a.file]:.6f}"]
    vsm = ["v:0,agroup:aud,name:video"]
    for i, a in enumerate(audios):
        vsm.append(f"a:{i},agroup:aud,language:{LANG3.get(a.lang, a.lang)},name:audio_{a.lang},default:{'yes' if i == 0 else 'no'}")
    cmd += [
        "-f", "hls", "-hls_time", str(opts.hls_time), "-hls_playlist_type", "vod",
        "-hls_segment_type", "mpegts", "-hls_flags", "single_file+independent_segments",
        "-master_pl_name", "master.m3u8", "-var_stream_map", " ".join(vsm),
        "-hls_segment_filename", str(out / "%v.ts"), str(out / "%v.m3u8"),
    ]
    log("$ " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                            encoding="utf-8", errors="replace", creationflags=_NO_WINDOW)
    tail: list[str] = []
    import threading

    def read_err():
        for line in proc.stderr:
            tail.append(line)
            del tail[:-40]
    t = threading.Thread(target=read_err, daemon=True)
    t.start()
    for line in proc.stdout:
        if cancel():
            proc.kill()
            raise Cancelled()
        if line.startswith("out_time_us=") or line.startswith("out_time_ms="):
            try:
                us = int(line.split("=", 1)[1])
                if info.duration > 0:
                    progress(min(us / 1e6 / info.duration, 1.0))
            except ValueError:
                pass
    proc.wait()
    t.join(timeout=2)
    if proc.returncode != 0:
        raise RuntimeError("ffmpeg hatası:\n" + "".join(tail))
    progress(1.0)
    return out / "master.m3u8"


@dataclass
class Variant:
    name: str
    playlist: str
    parts: list[str] = field(default_factory=list)
    lines: list[str] = field(default_factory=list)   # {{PART:n}} yer tutuculu


def split_variant(name: str, playlist: Path, log: Callable[[str], None] = print, part_limit: int = PART_LIMIT) -> Variant:
    """Tek dosyalık (byte-range) HLS akışını ≤1.9 GiB parçalara böler."""
    lines = playlist.read_text().splitlines()
    segs = []
    pending = None
    next_offset = 0
    for i, line in enumerate(lines):
        if line.startswith("#EXT-X-BYTERANGE:"):
            length, _, off = line.split(":", 1)[1].partition("@")
            length = int(length)
            offset = int(off) if off else next_offset
            pending = (i, length, offset)
            next_offset = offset + length
        elif line and not line.startswith("#") and pending:
            segs.append((pending[0], i, pending[1], pending[2], line))
            pending = None
    if not segs:
        raise RuntimeError(f"Beklenmeyen HLS çıktısı: {playlist}")
    src_file = playlist.parent / segs[0][4]

    groups: list[list[tuple]] = [[]]
    size = 0
    for s in segs:
        if size + s[2] > part_limit and groups[-1]:
            groups.append([])
            size = 0
        groups[-1].append(s)
        size += s[2]

    parts: list[str] = []
    if len(groups) == 1:
        part = playlist.parent / f"{name}_p1.ts"
        src_file.rename(part)
        parts.append(str(part))
        for (bl, ul, length, off, _) in segs:
            lines[bl] = f"#EXT-X-BYTERANGE:{length}@{off}"
            lines[ul] = "{{PART:0}}"
    else:
        log(f"{name}: {src_file.stat().st_size / 1024**3:.2f} GB, {len(groups)} parçaya bölünüyor…")
        with open(src_file, "rb") as fin:
            for gi, group in enumerate(groups):
                part = playlist.parent / f"{name}_p{gi + 1}.ts"
                pos = 0
                with open(part, "wb") as fout:
                    for (bl, ul, length, off, _) in group:
                        fin.seek(off)
                        remaining = length
                        while remaining:
                            chunk = fin.read(min(remaining, 8 * 1024 * 1024))
                            if not chunk:
                                raise RuntimeError("Bölme sırasında dosya beklenenden kısa çıktı.")
                            fout.write(chunk)
                            remaining -= len(chunk)
                        lines[bl] = f"#EXT-X-BYTERANGE:{length}@{pos}"
                        lines[ul] = "{{PART:%d}}" % gi
                        pos += length
                parts.append(str(part))
        src_file.unlink()
    return Variant(name, str(playlist), parts, lines)


def parse_master(master: Path) -> tuple[list[str], list[str]]:
    lines = master.read_text().splitlines()
    names: list[str] = []
    for line in lines:
        for m in re.finditer(r'URI="([^"]+)"', line):
            if m.group(1) not in names:
                names.append(m.group(1))
        if line and not line.startswith("#") and line not in names:
            names.append(line)
    return lines, names


def read_text_any(path: Path) -> str:
    data = path.read_bytes()
    for enc in ("utf-8-sig", "cp1254", "iso-8859-9", "latin-1"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def guess_lang_from_name(name: str) -> str:
    """'film.tr.srt', 'Turkish.vtt', 'eng_forced.srt' -> dil kodu"""
    stem = Path(name).stem.lower()
    for token in reversed(re.split(r"[^a-zçğıöşü]+", stem)):
        n = norm_lang(token)
        if token and n in LANG_LABEL and n != "und" and (len(token) <= 3 or token in ("turkish", "english", "turkce", "türkçe", "ingilizce")):
            return n
    return "und"


def _dump_attachment(sub: SubTrack, video: str, out_dir: Path, log: Callable[[str], None]) -> Path:
    target = out_dir / f"att_{sub.stream_index}{Path(sub.attachment or '').suffix or '.vtt'}"
    target.unlink(missing_ok=True)
    cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y", f"-dump_attachment:{sub.stream_index}", str(target), "-i", video]
    log("$ " + " ".join(cmd))
    # ffmpeg ek dosyayı çıkarır ama "çıktı belirtilmedi" diye hata kodu döner; dosyanın varlığına bakıyoruz
    subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", creationflags=_NO_WINDOW)
    if not target.exists() or target.stat().st_size == 0:
        raise RuntimeError(f"MKV eki çıkarılamadı: {sub.attachment}")
    return target


def convert_subtitle(sub: SubTrack, video: str, out: Path, log: Callable[[str], None] = print) -> None:
    if sub.attachment and not sub.file:
        extracted = _dump_attachment(sub, video, out.parent, log)
        convert_subtitle(SubTrack(lang=sub.lang, forced=sub.forced, file=str(extracted)), video, out, log)
        extracted.unlink(missing_ok=True)
        return
    if sub.file:
        src = Path(sub.file)
        # Türkçe .srt'ler çoğunlukla Windows-1254: önce UTF-8'e çevir
        tmp = out.with_suffix(".src" + src.suffix.lower())
        tmp.write_text(read_text_any(src), encoding="utf-8")
        if src.suffix.lower() == ".vtt":
            shutil.copy(tmp, out)
        else:
            _run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-sub_charenc", "UTF-8", "-i", str(tmp), "-c:s", "webvtt", str(out)], log)
        tmp.unlink(missing_ok=True)
    else:
        from . import mkv
        direct = sub.codec in mkv.TEXT_CODECS and sub.codec.startswith(("S_TEXT/WEBVTT", "D_WEBVTT"))
        if not direct:
            try:
                _run([FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", video, "-map", f"0:{sub.stream_index}", "-c:s", "webvtt", str(out)], log)
                return
            except RuntimeError as e:
                if Path(video).suffix.lower() not in (".mkv", ".mka", ".mks", ".webm"):
                    raise
                log(f"  ffmpeg altyazıyı okuyamadı, MKV'den doğrudan çıkarılıyor… ({str(e).strip().splitlines()[-1]})")
        else:
            log(f"  {sub.codec} altyazı MKV'den doğrudan çıkarılıyor…")
        out.write_text(mkv.to_webvtt(video, sub.stream_index), encoding="utf-8")


def sub_label(sub: SubTrack) -> str:
    return LANG_LABEL.get(sub.lang, sub.lang) + (" (Zorunlu)" if sub.forced else "")


def to_dict(obj) -> dict:
    return asdict(obj)
