#!/usr/bin/env python3
"""
G-Flix içerik yükleyici (komut satırı). Windows'ta grafik arayüz için: G-Flix Stüdyo.

  1. ffmpeg ile videoyu HLS'e çevirir (her ses dili ayrı kanal, altyazılar WebVTT).
  2. 1,9 GB'ı aşan akışları GitHub'ın 2 GB sınırına takılmayacak şekilde parçalar.
  3. Deponun bir Release'ine yükler (kesilirse tekrar çalıştır: yüklenen parçalar atlanır).
  4. catalog/items/<id>.json'u günceller; GitHub Actions TMDB'den Türkçe bilgileri çeker.

Gerekenler: Python 3.9+, ffmpeg/ffprobe, `pip install requests`, GITHUB_TOKEN ortam değişkeni.

Örnekler:
  python tools/gflix_upload.py movie "Inception.2010.mkv" --title "Başlangıç" --tmdb 27205
  python tools/gflix_upload.py episode "BB.S01E01.mkv" --title "Dark Road" --tmdb 1396 --season 1 --episode 1 \\
      --audio tr en --sub tr=bb.tr.srt
  python tools/gflix_upload.py movie film.mp4 --title "Film" --audio en --audio-file tr=dublaj.m4a
  python tools/gflix_upload.py trailer fragman.mp4 --id movie-baslangic --title "Başlangıç"
"""
import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gflix import media  # noqa: E402
from gflix.github import GitHub  # noqa: E402
from gflix.media import AudioTrack, EncodeOptions, SubTrack  # noqa: E402
from gflix.uploader import UploadJob, run_job  # noqa: E402
from gflix.util import norm_lang  # noqa: E402

DEFAULT_REPO = os.environ.get("GFLIX_REPO", "thedrowned925/g-flix")


def main() -> None:
    p = argparse.ArgumentParser(description="G-Flix'e film, dizi bölümü ya da fragman yükle.",
                                formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    p.add_argument("kind", choices=["movie", "episode", "trailer"], help="movie = film, episode = dizi bölümü, trailer = fragman")
    p.add_argument("input", type=Path)
    p.add_argument("--title", required=True, help="Film/dizi adı")
    p.add_argument("--tmdb", type=int, help="TMDB numarası")
    p.add_argument("--year", type=int)
    p.add_argument("--id", help="Katalog kimliği (fragman için gerekli; dizide tüm bölümlerde aynı olmalı)")
    p.add_argument("--season", type=int)
    p.add_argument("--episode", type=int)
    p.add_argument("--audio", nargs="+", metavar="DİL", help="Ses kanallarının dilleri sırasıyla (ör. --audio tr en)")
    p.add_argument("--audio-file", action="append", metavar="DİL=DOSYA", help="Harici ses dosyası (ör. tr=dublaj.m4a)")
    p.add_argument("--sub", action="append", metavar="DİL=DOSYA", help="Harici altyazı (ör. tr=film.srt; zorunlu: tr!=dosya)")
    p.add_argument("--no-embedded-subs", action="store_true")
    p.add_argument("--video", choices=["auto", "copy", "h264"], default="auto")
    p.add_argument("--max-height", type=int, default=1080)
    p.add_argument("--crf", type=int, default=21)
    p.add_argument("--preset", default="veryfast")
    p.add_argument("--parallel", type=int, default=4, help="Aynı anda yüklenen parça sayısı (varsayılan 4)")
    p.add_argument("--repo", default=DEFAULT_REPO)
    p.add_argument("--workdir", type=Path, default=Path("gflix-work"))
    p.add_argument("--keep", action="store_true")
    p.add_argument("--dry-run", action="store_true", help="Sadece hazırla, yükleme")
    a = p.parse_args()

    if a.kind == "episode" and (a.season is None or a.episode is None):
        sys.exit("Dizi bölümü için --season ve --episode gerekli.")
    if a.kind == "trailer" and not a.id:
        sys.exit("Fragman için yapımın kimliği gerekli: --id movie-... (catalog/items içindeki dosya adı)")
    if not a.input.exists():
        sys.exit(f"Dosya bulunamadı: {a.input}")
    if not media.tools_available():
        sys.exit("ffmpeg/ffprobe bulunamadı. https://ffmpeg.org adresinden kurup PATH'e ekle.")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not token and not a.dry_run:
        sys.exit("GITHUB_TOKEN ortam değişkenini ayarla (depoya yazma yetkili token).")

    info = media.analyze(a.input)
    media.auto_select(info, a.audio)
    for spec in a.audio_file or []:
        lang, _, f = spec.partition("=")
        info.audio.append(AudioTrack(stream_index=-1, lang=norm_lang(lang), file=str(Path(f).expanduser())))
    if a.no_embedded_subs:
        for s in info.subs:
            s.include = False
    for spec in a.sub or []:
        lang, _, f = spec.partition("=")
        if not f or not Path(f).expanduser().exists():
            sys.exit(f"Altyazı dosyası bulunamadı: {spec}")
        forced = lang.endswith("!")
        lang = norm_lang(lang.rstrip("!"))
        for s in info.subs:  # harici altyazı aynı dildeki gömülüyü ezer
            if s.lang == lang and s.forced == forced:
                s.include = False
        info.subs.append(SubTrack(lang=lang, forced=forced, file=str(Path(f).expanduser())))

    job = UploadJob(kind=a.kind, input=str(a.input), title=a.title, item_id=a.id, tmdb=a.tmdb, year=a.year,
                    season=a.season, episode=a.episode, info=info,
                    options=EncodeOptions(video_mode=a.video, max_height=a.max_height, crf=a.crf, preset=a.preset),
                    parallel=a.parallel)
    gh = None if a.dry_run else GitHub(a.repo, token)
    run_job(job, gh, a.workdir, keep=a.keep or a.dry_run)


if __name__ == "__main__":
    main()
