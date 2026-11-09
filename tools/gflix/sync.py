"""Ayrı indirilen video ve ses dosyalarının senkronu.

İki yaygın kayma nedeni otomatik düzeltilir:

1) Başlangıç zamanı farkı (sabit kayma): Siteden ayrı ayrı inen video ve ses dosyaları çoğu zaman orijinal yayının
   zaman damgalarını taşır (ör. video 2.000 sn'den, ses 1.478 sn'den başlar). MKVToolNix her dosyanın başlangıcını
   sıfıra çektiği için bu fark kaybolur ve ses baştan sona aynı miktarda kayık çalar. Fark ölçülüp --sync ile geri verilir.

2) Kare hızı farkı (gittikçe artan kayma): Ses başka bir sürümden geliyorsa (ör. 25 fps PAL dublaj, 23.976 fps video)
   ses ~%4 uzun/kısa olur; film ilerledikçe kayma büyür. Süre oranı bilinen oranlardan birine uyuyorsa ses,
   perdesi bozulmadan (atempo) videonun hızına getirilir.

Elle ince ayar: tarifte "ses tr: … | gecikme: -300" (ms; + = ses daha geç, - = ses daha erken).
"""
from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from .media import FFMPEG, FFPROBE

MAX_AUTO_OFFSET = 10.0          # sn: bundan büyük başlangıç farkı ayrı kaynak sayılır, dokunulmaz
MIN_OFFSET_MS = 15              # bir karenin yarısından küçük farklar yok sayılır
# (ses süresi / video süresi) — bilinen kare hızı dönüşümleri
SPEED_RATIOS = {
    # ses daha kısa: ses hızlandırılmış bir sürümden (ör. PAL 25 fps) geliyor
    (24000 / 1001) / 25: "ses 25 fps (PAL) sürümden, video 23.976 fps",
    24 / 25: "ses 25 fps (PAL) sürümden, video 24 fps",
    (24000 / 1001) / 24: "ses 24 fps sürümden, video 23.976 fps",
    # ses daha uzun: video hızlandırılmış sürümden geliyor
    25 / (24000 / 1001): "ses 23.976 fps sürümden, video 25 fps (PAL)",
    25 / 24: "ses 24 fps sürümden, video 25 fps (PAL)",
    24 / (24000 / 1001): "ses 23.976 fps sürümden, video 24 fps",
}


def _probe(path: Path, args: list[str]) -> dict:
    r = subprocess.run([FFPROBE, "-v", "error", "-of", "json", *args, str(path)], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    try:
        return json.loads(r.stdout or "{}")
    except ValueError:
        return {}


def stream_start(path: Path, kind: str) -> float:
    """Dosyadaki ilk video/ses akışının başlangıç zamanı (sn). Bilinmiyorsa 0."""
    sel = "v:0" if kind == "video" else "a:0"
    d = _probe(path, ["-select_streams", sel, "-show_entries", "stream=start_time:format=start_time,format_name"])
    st = (d.get("streams") or [{}])[0].get("start_time")
    if st in (None, "N/A"):
        st = (d.get("format") or {}).get("start_time")
    try:
        return float(st)
    except (TypeError, ValueError):
        return 0.0


def format_start(path: Path) -> float:
    """Dosyanın (bütün akışlarının en erkeni) başlangıç zamanı; ffmpeg her girdiyi buna göre sıfırlar."""
    d = _probe(path, ["-show_entries", "format=start_time"])
    try:
        return float((d.get("format") or {}).get("start_time"))
    except (TypeError, ValueError):
        return 0.0


def duration(path: Path, kind: str) -> float:
    """Akışın süresi (sn). Çıplak .aac (ADTS) gibi süresi tahmin edilen biçimlerde paketler sayılır."""
    sel = "v:0" if kind == "video" else "a:0"
    d = _probe(path, ["-select_streams", sel, "-show_entries", "stream=duration,sample_rate,codec_name:format=duration,format_name"])
    fmt = (d.get("format") or {}).get("format_name", "")
    s = (d.get("streams") or [{}])[0]
    if kind == "audio" and fmt in ("aac", "mp3", "ac3", "eac3", "dts"):
        # süre bit hızından tahmin edilir ve yanlış olabilir: gerçek paket zamanlarını oku
        d2 = _probe(path, ["-select_streams", "a:0", "-show_entries", "packet=pts_time,duration_time"])
        pk = [p for p in d2.get("packets", []) if p.get("pts_time") not in (None, "N/A")]
        if pk:
            last = pk[-1]
            return float(last["pts_time"]) + float(last.get("duration_time") or 0) - float(pk[0]["pts_time"])
    for v in (s.get("duration"), (d.get("format") or {}).get("duration")):
        try:
            if v not in (None, "N/A"):
                return float(v)
        except (TypeError, ValueError):
            pass
    return 0.0


@dataclass
class AudioPlan:
    offset_ms: int = 0              # mkvmerge --sync (+ = ses daha geç)
    tempo: float | None = None      # atempo oranı (sesi videonun hızına getirmek için)
    notes: list[str] = field(default_factory=list)


def plan(video: Path, audio: Path, label: str, manual_ms: int = 0, video_start: float | None = None,
         video_dur: float | None = None) -> AudioPlan:
    p = AudioPlan()
    vs = stream_start(video, "video") if video_start is None else video_start
    as_ = stream_start(audio, "audio")
    diff = as_ - vs
    if abs(diff) * 1000 >= MIN_OFFSET_MS:
        if abs(diff) <= MAX_AUTO_OFFSET:
            p.offset_ms = round(diff * 1000)
            yön = "öne alındı" if diff < 0 else "geciktirildi"
            p.notes.append(f"{label}: dosyaların başlangıç zamanına göre {abs(p.offset_ms)} ms {yön} "
                           f"(video {vs:.3f} sn, ses {as_:.3f} sn'den başlıyor)")
        else:
            p.notes.append(f"{label}: başlangıç farkı çok büyük ({diff:+.1f} sn), ayrı kaynak sayıldı, kaydırılmadı")
    vd = duration(video, "video") if video_dur is None else video_dur
    ad = duration(audio, "audio")
    if vd > 60 and ad > 60:
        r = ad / vd
        for ratio, why in SPEED_RATIOS.items():
            # 24/23.976 gibi oranlar 1'e çok yakın: sıkı tolerans ve gerçekten saniyelerce fark şartı (yanlış eşleşme olmasın)
            if abs(r - ratio) < (0.0003 if abs(ratio - 1) < 0.01 else 0.002) and abs(ad - vd) > 2:
                p.tempo = r
                p.notes.append(f"{label}: ses videodan %{abs(r - 1) * 100:.1f} {'uzun' if r > 1 else 'kısa'} ({why}) — "
                               f"film ilerledikçe kayardı, sesin hızı videoya uyduruldu")
                break
    if manual_ms:
        p.offset_ms += manual_ms
        p.notes.append(f"{label}: elle {manual_ms:+d} ms gecikme uygulandı")
    return p


def retime(audio: Path, tempo: float, out: Path) -> Path:
    """Sesi perdesini bozmadan hızlandırır/yavaşlatır (atempo), AAC olarak yazar."""
    d = _probe(audio, ["-select_streams", "a:0", "-show_entries", "stream=channels"])
    ch = int((d.get("streams") or [{}])[0].get("channels") or 2)
    cmd = [FFMPEG, "-hide_banner", "-loglevel", "error", "-y", "-i", str(audio), "-map", "0:a:0", "-vn",
           "-af", f"atempo={tempo:.6f}", "-c:a", "aac", "-b:a", "192k" if ch <= 2 else "384k", str(out)]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode != 0 or not out.exists():
        raise RuntimeError("Sesin hızı düzeltilemedi:\n" + r.stderr[-800:])
    return out
