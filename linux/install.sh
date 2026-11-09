#!/usr/bin/env bash
# G-Flix Linux bilgisayar işçisi kurulumu.
#   curl -fsSL https://raw.githubusercontent.com/<kullanici>/g-flix/<dal>/linux/install.sh | bash
# ya da depo klasöründe:  bash linux/install.sh
# Her şey tek klasörde olsun istersen:  bash install.sh --klasor ~/Masaüstü/film
#   → kurulum ~/Masaüstü/film/kurulum içine, indirmeler ~/Masaüstü/film içine yapılır.
# Tekrar çalıştırmak güvenli: araçları günceller, ayarları korur (istersen değiştirir).
set -euo pipefail

say()  { printf '\033[1;31m▶\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] && die "Bu betiği root olarak değil, kendi kullanıcınla çalıştır (sudo'yu kendisi ister)."

FILM_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --klasor|--folder) FILM_DIR="${2:-}"; shift 2 ;;
    *) die "Bilinmeyen seçenek: $1 (kullanım: bash install.sh [--klasor ~/Masaüstü/film])" ;;
  esac
done
CONF_DIR="$HOME/.config/gflix"
CONF="$CONF_DIR/worker.env"
VENV="$HOME/.local/share/gflix/venv"
DENO_DIR="$HOME/.deno"
if [ -n "$FILM_DIR" ]; then
  FILM_DIR="${FILM_DIR/#\~/$HOME}"
  mkdir -p "$FILM_DIR/kurulum"
  FILM_DIR="$(cd "$FILM_DIR" && pwd)"
  VENV="$FILM_DIR/kurulum/.venv"
  DENO_DIR="$FILM_DIR/kurulum/.deno"
fi
DEFAULT_REPO="thedrowned925/g-flix"
mkdir -p "$CONF_DIR"
[ -f "$CONF" ] && set -a && . "$CONF" && set +a

# ---------------------------------------------------------------- 1) sistem paketleri
say "Sistem paketleri kuruluyor (aria2, MKVToolNix, ffmpeg, git, python)…"
if command -v apt-get >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq git aria2 mkvtoolnix ffmpeg python3 python3-venv python3-pip curl unzip ca-certificates libxcb-cursor0
elif command -v dnf >/dev/null; then
  sudo dnf install -y git aria2 mkvtoolnix ffmpeg python3 python3-pip curl unzip xcb-util-cursor || \
    die "ffmpeg bulunamadıysa RPM Fusion deposunu aç: https://rpmfusion.org/Configuration"
elif command -v pacman >/dev/null; then
  sudo pacman -Sy --needed --noconfirm git aria2 mkvtoolnix-cli ffmpeg python python-pip curl unzip xcb-util-cursor
elif command -v zypper >/dev/null; then
  sudo zypper -n install git aria2 mkvtoolnix ffmpeg python3 python3-pip curl unzip libxcb-cursor0
else
  die "Paket yöneticisi tanınmadı. git, aria2, mkvtoolnix, ffmpeg, python3 kurup tekrar çalıştır."
fi
ok "$(mkvmerge --version | head -1)"
ok "$(aria2c --version | head -1)"
ok "$(ffmpeg -version | head -1 | cut -d' ' -f1-3)"

# ---------------------------------------------------------------- 2) ayarlar
ask() {  # ask DEĞİŞKEN "soru" "varsayılan" [gizli]
  local var="$1" q="$2" def="${3:-}" secret="${4:-}" ans
  local shown="$def"; [ -n "$secret" ] && [ -n "$def" ] && shown="(kayıtlı, değiştirmek için yaz)"
  if [ -n "$secret" ]; then read -r -s -p "$q [$shown]: " ans </dev/tty; echo; else read -r -p "$q [$shown]: " ans </dev/tty; fi
  printf -v "$var" '%s' "${ans:-$def}"
}
say "Ayarlar (Enter = köşeli parantezdeki değer)"
ask GFLIX_REPO "GitHub deposu (kullanıcı/depo)" "${GFLIX_REPO:-$DEFAULT_REPO}"
echo "  Yazma yetkili token: https://github.com/settings/personal-access-tokens/new"
echo "  → Only select repositories: $GFLIX_REPO → Contents: Read and write (G-Flix Stüdyo'dakiyle aynısı olur)"
ask GITHUB_TOKEN "GitHub token" "${GITHUB_TOKEN:-}" secret
[ -z "$GITHUB_TOKEN" ] && die "Token gerekli."
ask TMDB_API_KEY "TMDB anahtarı (isteğe bağlı, tarifte 'ad:' yoksa adı bulmak için)" "${TMDB_API_KEY:-}" secret
ask GFLIX_WORKDIR "İndirme/işleme klasörü (büyük boş alan olsun)" "${FILM_DIR:-${GFLIX_WORKDIR:-$HOME/gflix-work}}"
GFLIX_WORKDIR="${GFLIX_WORKDIR/#\~/$HOME}"
mkdir -p "$GFLIX_WORKDIR"
COOKIES="$CONF_DIR/cookies.txt"
umask 077
cat > "$CONF" <<CONF
# G-Flix bilgisayar işçisi ayarları (bash linux/install.sh ile değiştirilebilir)
GITHUB_TOKEN=$GITHUB_TOKEN
GFLIX_REPO=$GFLIX_REPO
TMDB_API_KEY=$TMDB_API_KEY
GFLIX_WORKDIR=$GFLIX_WORKDIR
# YouTube "bot" derse tarayıcıdan cookies.txt (Netscape biçimi) indirip buraya koy:
GFLIX_YT_COOKIES_FILE=$COOKIES
CONF
umask 022
ok "Ayarlar kaydedildi: $CONF"

# ---------------------------------------------------------------- 3) G-Flix kodu
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && cd .. && pwd || true)"
if [ -n "$FILM_DIR" ]; then
  # --klasor: kod <film>/kurulum içine (klasör boş değilse <film>/kurulum/g-flix içine)
  if [ -d "$FILM_DIR/kurulum/.git" ] || [ -z "$(ls -A "$FILM_DIR/kurulum" 2>/dev/null | grep -v -e '^\.venv$' -e '^\.deno$')" ]; then
    APP="$FILM_DIR/kurulum"
  else
    APP="$FILM_DIR/kurulum/g-flix"
  fi
  if [ ! -d "$APP/.git" ]; then
    say "Depo indiriliyor: $APP"
    tmp="$(mktemp -d)"
    git clone -q "https://x-access-token:$GITHUB_TOKEN@github.com/$GFLIX_REPO.git" "$tmp/g"
    mkdir -p "$APP" && cp -a "$tmp/g/." "$APP/" && rm -rf "$tmp"
  fi
elif [ -n "$SRC_DIR" ] && [ -f "$SRC_DIR/tools/gflix_worker.py" ]; then
  APP="$SRC_DIR"
else
  APP="$HOME/g-flix"
  if [ ! -d "$APP/.git" ]; then
    say "Depo indiriliyor: $APP"
    git clone -q "https://x-access-token:$GITHUB_TOKEN@github.com/$GFLIX_REPO.git" "$APP"
  fi
fi
git -C "$APP" remote set-url origin "https://x-access-token:$GITHUB_TOKEN@github.com/$GFLIX_REPO.git"
git -C "$APP" pull -q --ff-only || true
ok "Kod: $APP ($(git -C "$APP" rev-parse --abbrev-ref HEAD) @ $(git -C "$APP" rev-parse --short HEAD))"

# ---------------------------------------------------------------- 4) yt-dlp + deno
say "yt-dlp kuruluyor (YouTube fragmanları için)…"
python3 -m venv "$VENV"
"$VENV/bin/pip" install -q -U pip
"$VENV/bin/pip" install -q -U "yt-dlp[default]" requests gdown secretstorage PySide6 playwright cryptography
ok "yt-dlp $("$VENV/bin/yt-dlp" --version)"
# Sayfa linkleri için görünmez tarayıcı (sayfayı açıp video yayınını, sesleri ve altyazıları yakalar)
"$VENV/bin/python" -m playwright install chromium >/dev/null 2>&1 && ok "Görünmez tarayıcı (Chromium) hazır" || say "Görünmez tarayıcı kurulamadı; ilk sayfa linkinde yeniden denenecek"
if ! command -v deno >/dev/null && [ ! -x "$DENO_DIR/bin/deno" ]; then
  say "deno kuruluyor (YouTube artık JavaScript motoru istiyor)…"
  curl -fsSL https://deno.land/install.sh | DENO_INSTALL="$DENO_DIR" sh -s -- -y >/dev/null
fi
ok "deno $( (command -v deno >/dev/null && deno --version || "$DENO_DIR/bin/deno" --version) | head -1 | cut -d' ' -f2)"

# ---------------------------------------------------------------- 4b) G-Flix Masaüstü + gflix-indir
say "G-Flix Masaüstü (sürükle-bırak) ve gflix-indir komutu kuruluyor…"
mkdir -p "$HOME/.local/bin" "$HOME/.local/share/applications"
cat > "$HOME/.local/bin/gflix-masaustu" <<BIN
#!/usr/bin/env bash
export PATH="$VENV/bin:$DENO_DIR/bin:\$PATH"
exec "$VENV/bin/python" "$APP/linux/gflix_masaustu.py" "\$@"
BIN
cat > "$HOME/.local/bin/gflix-indir" <<BIN
#!/usr/bin/env bash
export PATH="$VENV/bin:$DENO_DIR/bin:\$PATH"
exec "$VENV/bin/python" "$APP/linux/gflix_indir.py" "\$@"
BIN
cat > "$HOME/.local/bin/gflix-kayit" <<'BIN'
#!/usr/bin/env bash
# G-Flix işçisinin canlı kaydı (Ctrl+C ile çık). Son 200 satırı gösterir, sonra yenileri geldikçe yazar.
echo "G-Flix canlı kayıt — durum: $(systemctl --user is-active gflix-worker)   (çıkmak için Ctrl+C)"
echo "Ayrıntılı iş kayıtları: $(grep -s '^GFLIX_WORKDIR=' "$HOME/.config/gflix/worker.env" | cut -d= -f2-)/kayitlar/"
exec journalctl --user -u gflix-worker -n 200 -f -o cat
BIN
chmod +x "$HOME/.local/bin/gflix-masaustu" "$HOME/.local/bin/gflix-indir" "$HOME/.local/bin/gflix-kayit"
cat > "$HOME/.local/share/applications/gflix-kayit.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=G-Flix Canlı Kayıt
Comment=Bilgisayar işçisinin ne yaptığını canlı göster
Exec=$HOME/.local/bin/gflix-kayit
Icon=$APP/studio/assets/gflix.png
Terminal=true
Categories=AudioVideo;Video;
DESK
cat > "$HOME/.local/share/applications/gflix-masaustu.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=G-Flix Masaüstü
Comment=Film/dizi ekle: dosyaları sürükle, linkleri indir, GitHub'a yükle
Exec=$HOME/.local/bin/gflix-masaustu
Icon=$APP/studio/assets/gflix.png
Terminal=false
Categories=AudioVideo;Video;
DESK
update-desktop-database "$HOME/.local/share/applications" >/dev/null 2>&1 || true
ok "Menüde 'G-Flix Masaüstü' (ya da terminalde: gflix-masaustu)"

# ---------------------------------------------------------------- 5) systemd servisi
say "Arka plan servisi kuruluyor (bilgisayar açılınca kendiliğinden başlar)…"
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/gflix-worker.service" <<UNIT
[Unit]
Description=G-Flix bilgisayar işçisi (indir, MKVToolNix, GitHub'a yükle)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=$CONF
Environment=PATH=$VENV/bin:$DENO_DIR/bin:/usr/local/bin:/usr/bin:/bin
Environment=PYTHONUNBUFFERED=1
WorkingDirectory=$APP
ExecStart=$VENV/bin/python $APP/tools/gflix_worker.py
Restart=always
RestartSec=15
# İş bitene kadar beklesin (en fazla 6 saat)
TimeoutStopSec=6h
KillSignal=SIGTERM
Nice=5

[Install]
WantedBy=default.target
UNIT
# Telefondan doğrudan alım (ev ağı, port 8765): güvenlik duvarı açıksa izin ver
if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
  sudo ufw allow 8765/tcp comment "G-Flix telefondan alim" >/dev/null && ok "Güvenlik duvarında 8765 portu açıldı (ev ağı)"
elif command -v firewall-cmd >/dev/null && sudo firewall-cmd --state >/dev/null 2>&1; then
  sudo firewall-cmd -q --permanent --add-port=8765/tcp && sudo firewall-cmd -q --reload && ok "Güvenlik duvarında 8765 portu açıldı (ev ağı)"
fi
# Oturum açmadan da çalışsın
sudo loginctl enable-linger "$USER" || true
systemctl --user daemon-reload
PATH="$VENV/bin:$DENO_DIR/bin:$PATH" GITHUB_TOKEN="$GITHUB_TOKEN" GFLIX_REPO="$GFLIX_REPO" "$VENV/bin/python" "$APP/tools/gflix_worker.py" --check \
  || die "Kontrol başarısız: token/depo adını kontrol et ve tekrar çalıştır."
systemctl --user enable -q gflix-worker.service
systemctl --user restart gflix-worker.service
sleep 2
systemctl --user --no-pager --lines=0 status gflix-worker.service | head -3 || true

cat <<DONE

$(ok "Kurulum tamam!")
  • Bu bilgisayarda: menüden "G-Flix Masaüstü" → dosyaları kutulara sürükle ya da linkleri yapıştır → Gönder
  • Sadece indirmek için: gflix-indir LINK   (ya da: gflix-indir --curl)
  • Telefonda: Linkten Ekle / Dosyalardan Ekle → "Kim indirsin?" → 🖥️ Bilgisayarım
  • Canlı kayıt:      gflix-kayit   (ya da menüde "G-Flix Canlı Kayıt")
  • Durdur / başlat:  systemctl --user stop gflix-worker   |   systemctl --user start gflix-worker
  • Ayarları değiştir: bash $APP/linux/install.sh
  • İş klasörü:       $GFLIX_WORKDIR  (iş bitip yükleme GitHub'da doğrulanınca dosyalar silinir)
DONE
