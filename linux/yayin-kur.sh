#!/usr/bin/env bash
# G-Flix yayın sunucusu kurulumu (Linux bilgisayar "Hasan"): linkten anlık yayın, GitHub'sız.
#   Depo klasöründe:  bash linux/yayin-kur.sh
# Yaptıkları: eski işçiyi (GitHub'a video yükleyen) ve eski TV sunucusunu durdurur (silmez), Python ortamını ve
# görünmez tarayıcıyı kurar, ~/.config/gflix/yayin.env yazar, "gflix-yayin" servisini kurup başlatır, anahtarı gösterir.
# Tekrar çalıştırmak güvenli.
set -euo pipefail

say()  { printf '\033[1;31m▶\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] && die "Bu betiği root olarak değil, kendi kullanıcınla çalıştır."
APP="$(cd "$(dirname "$0")/.." && pwd)"
[ -f "$APP/tools/gflix_yayin.py" ] || die "Depo klasörü bulunamadı ($APP)."
CONF_DIR="$HOME/.config/gflix"
ENV_FILE="$CONF_DIR/yayin.env"
PORT="${GFLIX_YAYIN_PORT:-8766}"
mkdir -p "$CONF_DIR"

# ---------------------------------------------------------------- 1) eski servisler (silinmez, sadece durdurulur)
for svc in gflix-worker gflix-tv; do
  if systemctl --user list-unit-files "$svc.service" >/dev/null 2>&1 && systemctl --user cat "$svc.service" >/dev/null 2>&1; then
    systemctl --user stop "$svc.service" 2>/dev/null || true
    systemctl --user disable -q "$svc.service" 2>/dev/null || true
    ok "$svc durduruldu ve açılışta başlamayacak (dosyası duruyor)"
  fi
done

# ---------------------------------------------------------------- 2) Python ortamı
VENV=""
for v in "$APP/.venv" "$APP/../.venv" "$HOME/.local/share/gflix/venv"; do
  [ -x "$v/bin/python" ] && VENV="$(cd "$v" && pwd)" && break
done
if [ -z "$VENV" ]; then
  VENV="$HOME/.local/share/gflix/venv"
  say "Python ortamı kuruluyor: $VENV"
  python3 -m venv "$VENV"
fi
"$VENV/bin/pip" install -q -U requests playwright "yt-dlp[default]"
# YouTube (fragmanlar) için yt-dlp bir JavaScript çalıştırıcısı ister: deno
DENO_BIN=""
for d in "$HOME/.deno/bin" "$APP/../.deno/bin" "$APP/.deno/bin"; do [ -x "$d/deno" ] && DENO_BIN="$(cd "$d" && pwd)" && break; done
if [ -z "$DENO_BIN" ] && ! command -v deno >/dev/null; then
  say "deno kuruluyor (YouTube fragmanları için)…"
  curl -fsSL https://deno.land/install.sh | sh -s -- -y >/dev/null 2>&1 && DENO_BIN="$HOME/.deno/bin" || say "deno kurulamadı; YouTube bazı videolarda açılmayabilir"
fi
"$VENV/bin/python" -m playwright install chromium >/dev/null 2>&1 && ok "Görünmez tarayıcı (Chromium) hazır" \
  || say "Görünmez tarayıcı kurulamadı; 'sudo $VENV/bin/python -m playwright install-deps chromium' gerekebilir"
# Görünmez yakalama: sanal masaüstü (Xvfb, ekranda hiçbir şey görünmez) + gerçek Google Chrome (video çözücüleri tam)
if ! command -v Xvfb >/dev/null; then
  say "Görünmez masaüstü (Xvfb) kuruluyor…"
  if command -v apt-get >/dev/null; then sudo apt-get install -y -qq xvfb
  elif command -v dnf >/dev/null; then sudo dnf install -y xorg-x11-server-Xvfb
  elif command -v pacman >/dev/null; then sudo pacman -Sy --needed --noconfirm xorg-server-xvfb
  elif command -v zypper >/dev/null; then sudo zypper -n install xvfb-run; fi
fi
command -v Xvfb >/dev/null && ok "Görünmez masaüstü (Xvfb) hazır" || say "Xvfb kurulamadı: bilgisayardaki yakalama görünmez olmaz"
if ! command -v google-chrome >/dev/null && ! command -v google-chrome-stable >/dev/null; then
  say "Google Chrome kuruluyor (görünmez yakalama için)…"
  tmpc="$(mktemp -d)"
  if command -v apt-get >/dev/null; then
    curl -fsSL -o "$tmpc/chrome.deb" https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb && sudo apt-get install -y -qq "$tmpc/chrome.deb"
  elif command -v dnf >/dev/null; then
    curl -fsSL -o "$tmpc/chrome.rpm" https://dl.google.com/linux/direct/google-chrome-stable_current_x86_64.rpm && sudo dnf install -y "$tmpc/chrome.rpm"
  fi
  rm -rf "$tmpc"
fi
command -v google-chrome >/dev/null || command -v google-chrome-stable >/dev/null && ok "Google Chrome hazır" \
  || say "Google Chrome kurulamadı: Playwright'ın Chromium'u kullanılır (bazı videoları oynatamayabilir)"
command -v ffprobe >/dev/null || say "ffprobe yok (sudo apt install ffmpeg): ana listesi olmayan sitelerde ses/görüntü ayrımı için gerekir"
command -v qrencode >/dev/null || say "QR göstermek için: sudo apt install qrencode (isteğe bağlı)"

# ---------------------------------------------------------------- 3) ayarlar
OLD="$CONF_DIR/worker.env"
get_old() { grep -s "^$1=" "$OLD" | head -1 | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
if [ ! -f "$ENV_FILE" ]; then
  WD="$(get_old GFLIX_WORKDIR)"; WD="${WD:-$HOME/Masaüstü/FİLM}"
  TK="$(get_old TMDB_API_KEY)"
  if [ -z "$TK" ]; then read -r -p "TMDB API anahtarı (boş geçilebilir): " TK </dev/tty || true; fi
  {
    echo "# G-Flix yayın sunucusu ayarları"
    echo "GFLIX_WORKDIR=$WD"
    echo "TMDB_API_KEY=$TK"
    echo "GFLIX_YAYIN_PORT=$PORT"
    echo "GFLIX_WORKER_NAME=$(get_old GFLIX_WORKER_NAME)"
    echo "# Telefon güncellemesi için (isteğe bağlı): GitHub'da sadece bu depoya 'Contents: Read' yetkili anahtar"
    echo "GFLIX_GITHUB_TOKEN="
    echo "GFLIX_REPO=apexlions16/new"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  ok "Ayarlar yazıldı: $ENV_FILE"
else
  ok "Ayarlar korunuyor: $ENV_FILE"
fi

# ---------------------------------------------------------------- 4) servis
UNIT_DIR="$HOME/.config/systemd/user"
mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/gflix-yayin.service" <<UNIT
[Unit]
Description=G-Flix yayın sunucusu (linkten anlık yayın; GitHub'a hiçbir şey gitmez)
After=network-online.target
Wants=network-online.target

[Service]
EnvironmentFile=$ENV_FILE
Environment=PATH=$VENV/bin:${DENO_BIN:+$DENO_BIN:}/usr/local/bin:/usr/bin:/bin
Environment=PYTHONUNBUFFERED=1
WorkingDirectory=$APP
ExecStart=$VENV/bin/python $APP/tools/gflix_yayin.py
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
UNIT
mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/gflix-yayin" <<CMD
#!/usr/bin/env bash
set -a; [ -f "$ENV_FILE" ] && . "$ENV_FILE"; set +a
export PATH="$VENV/bin:${DENO_BIN:+$DENO_BIN:}\$PATH"
exec "$VENV/bin/python" "$APP/tools/gflix_yayin.py" "\$@"
CMD
chmod +x "$HOME/.local/bin/gflix-yayin"

# ---------------------------------------------------------------- 5) yapım kayıtları (adlar sadece bu bilgisayarda)
WD="$(grep -s '^GFLIX_WORKDIR=' "$ENV_FILE" | cut -d= -f2-)"; WD="${WD/#\~/$HOME}"
ITEMS="$WD/katalog/items"
if [ -z "$(ls -A "$ITEMS" 2>/dev/null)" ]; then
  for c in "$HOME/İndirilenler/katalog" "$HOME/Downloads/katalog" "$APP/catalog" "$APP/../catalog" \
           "$HOME/Masaüstü/FİLM/kurulum/catalog" "$HOME/İndirilenler/g-flix-yeni/catalog" "$HOME/g-flix/catalog"; do
    if [ -d "$c/items" ] && ls "$c/items"/*.json >/dev/null 2>&1; then
      "$HOME/.local/bin/gflix-yayin" ice-aktar "$c/items" && break
    fi
  done
fi
if [ -z "$(ls -A "$ITEMS" 2>/dev/null)" ] && [ -d "$APP/.git" ]; then
  # Eski depo geçmişinden (kayıtlar yeni depoda yok; eski kopyanın geçmişinde duruyor)
  for sha in $(git -C "$APP" rev-list --all -- catalog/items 2>/dev/null); do
    if git -C "$APP" cat-file -e "$sha:catalog/items" 2>/dev/null; then
      tmp="$(mktemp -d)"; git -C "$APP" archive "$sha" catalog | tar -x -C "$tmp"
      "$HOME/.local/bin/gflix-yayin" ice-aktar "$tmp/catalog/items"; rm -rf "$tmp"; break
    fi
  done
fi
[ -n "$(ls -A "$ITEMS" 2>/dev/null)" ] && ok "Yapım kayıtları: $ITEMS ($(ls "$ITEMS" | wc -l) yapım)" \
  || say "Yapım kayıtları bulunamadı. katalog.zip'i İndirilenler'e çıkarıp bu betiği tekrar çalıştır."

if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
  sudo ufw allow "$PORT/tcp" comment "G-Flix yayin" >/dev/null && ok "Güvenlik duvarında $PORT portu açıldı"
elif command -v firewall-cmd >/dev/null && sudo firewall-cmd --state >/dev/null 2>&1; then
  sudo firewall-cmd -q --permanent --add-port="$PORT/tcp" && sudo firewall-cmd -q --reload && ok "Güvenlik duvarında $PORT portu açıldı"
fi
loginctl enable-linger "$USER" 2>/dev/null || true      # oturum açılmasa da servis çalışsın
systemctl --user daemon-reload
systemctl --user enable -q gflix-yayin.service
systemctl --user restart gflix-yayin.service
sleep 2
systemctl --user is-active -q gflix-yayin.service || { journalctl --user -u gflix-yayin -n 30 --no-pager; die "Servis başlamadı."; }
ok "gflix-yayin çalışıyor"
"$HOME/.local/bin/gflix-yayin" anahtar
cat <<TXT
Komutlar:
  gflix-yayin kaynaklar                              kayıtlı sayfalar
  gflix-yayin kaynak <id> '<sayfa linki>'            film sayfası
  gflix-yayin kaynak <id> --kalip '<…sezon-{s}/bolum-{e}>'   dizinin bütün bölümleri
  gflix-yayin dene <id> [--sezon 1 --bolum 2]        çözmeyi dene, süreyi ölç
  journalctl --user -u gflix-yayin -f -o cat         canlı kayıt
TXT
