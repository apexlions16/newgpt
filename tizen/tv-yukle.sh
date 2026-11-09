#!/usr/bin/env bash
# G-Flix'i Samsung TV'ye imzalayıp kurar (Linux PC'de, Tizen Studio kuruluyken).
# Kullanım: bash tizen/tv-yukle.sh <TV-IP> [sertifika-profili]
set -euo pipefail
die() { printf '\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }
ok()  { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
TV="${1:-}"; PROFILE="${2:-gflix}"
[ -n "$TV" ] || die "TV'nin IP adresini yaz: bash tizen/tv-yukle.sh 192.168.1.50"
TS="${TIZEN_STUDIO:-$HOME/tizen-studio}"
TIZEN="$TS/tools/ide/bin/tizen"; SDB="$TS/tools/sdb"
[ -x "$TIZEN" ] || die "Tizen Studio bulunamadı ($TS). Kurulum: tizen/README.md"
DIR="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp -r "$DIR/app/." "$WORK/"
"$SDB" connect "$TV:26101" >/dev/null || true
DEV="$("$SDB" devices | awk -v ip="$TV" 'index($1, ip) == 1 && $2 == "device" { print $3; exit }')"
[ -n "$DEV" ] || die "TV'ye bağlanılamadı. TV'de Geliştirici Modu açık ve 'Host PC IP' bu bilgisayarın IP'si mi? (tizen/README.md)"
ok "TV bağlı: $DEV"
"$TIZEN" package -t wgt -s "$PROFILE" -- "$WORK" >/dev/null || die "İmzalama başarısız. Tizen Studio > Certificate Manager'da '$PROFILE' adlı Samsung sertifika profili var mı?"
WGT="$(ls "$WORK"/*.wgt | head -1)"
ok "Paket imzalandı: $(basename "$WGT")"
"$TIZEN" install -n "$(basename "$WGT")" -t "$DEV" -- "$WORK" || die "Kurulum başarısız."
ok "G-Flix TV'ye kuruldu. TV'de Uygulamalar listesinde G-Flix'i aç."
