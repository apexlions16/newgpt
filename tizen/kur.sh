#!/usr/bin/env bash
# G-Flix TV sunucusunu Linux PC'ye kurar (bir kere çalıştır): bash tizen/kur.sh
set -euo pipefail
say() { printf '\033[1;31m▶\033[0m %s\n' "$*"; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"
PORT="${GFLIX_TV_PORT:-8766}"

need_node() { command -v node >/dev/null && [ "$(node -e 'console.log(process.versions.node.split(".")[0])')" -ge 18 ]; }
if ! need_node; then
  say "Node.js kuruluyor…"
  if command -v apt-get >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y -qq nodejs
  elif command -v dnf >/dev/null; then sudo dnf install -y nodejs
  elif command -v pacman >/dev/null; then sudo pacman -Sy --needed --noconfirm nodejs
  elif command -v zypper >/dev/null; then sudo zypper -n install nodejs
  fi
  need_node || die "Node.js 18 veya üstü gerekli (node -v). https://nodejs.org adresinden kurup tekrar çalıştır."
fi
ok "Node.js $(node -v)"
[ -f "$HOME/.config/gflix/worker.env" ] && grep -q '^GITHUB_TOKEN=' "$HOME/.config/gflix/worker.env" \
  && ok "Token işçinin ayarlarından okunacak" || say "Uyarı: ~/.config/gflix/worker.env bulunamadı; önce linux/install.sh çalıştırılmalı."

say "Servis kuruluyor (bilgisayar açılınca kendiliğinden başlar)…"
UNIT_DIR="$HOME/.config/systemd/user"; mkdir -p "$UNIT_DIR"
cat > "$UNIT_DIR/gflix-tv.service" <<UNIT
[Unit]
Description=G-Flix TV sunucusu (Samsung TV'ye video aktarımı, port $PORT)
After=network-online.target
Wants=network-online.target

[Service]
Environment=GFLIX_TV_PORT=$PORT
ExecStart=$(command -v node) $DIR/sunucu.js
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
if command -v ufw >/dev/null && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
  sudo ufw allow "$PORT/tcp" comment "G-Flix TV" >/dev/null && ok "Güvenlik duvarında $PORT portu açıldı (ev ağı)"
elif command -v firewall-cmd >/dev/null && sudo firewall-cmd --state >/dev/null 2>&1; then
  sudo firewall-cmd -q --permanent --add-port="$PORT/tcp" && sudo firewall-cmd -q --reload && ok "Güvenlik duvarında $PORT portu açıldı"
fi
sudo loginctl enable-linger "$USER" >/dev/null 2>&1 || true
systemctl --user daemon-reload
systemctl --user enable -q gflix-tv.service
systemctl --user restart gflix-tv.service
sleep 2
systemctl --user is-active -q gflix-tv.service || { journalctl --user -u gflix-tv -n 20 --no-pager; die "Servis başlamadı."; }
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
ok "G-Flix TV sunucusu çalışıyor."
echo
echo "  TV uygulaması sunucuyu kendisi bulur. Bulamazsa adres:  $IP"
echo "  Uygulama kurmadan denemek için TV'nin İnternet uygulamasında:  http://$IP:$PORT/tv/"
echo "  Kayıtlar:  journalctl --user -u gflix-tv -f"
