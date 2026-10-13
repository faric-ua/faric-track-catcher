#!/usr/bin/env bash
# Ярлик «Track Catcher» для Termux:Widget (відкриває це меню одним дотиком).
set -u
. "$(dirname -- "$0")/tc-common.sh"
mkdir -p "$HOME/.shortcuts"
target="$HOME/.shortcuts/Track Catcher"
printf '#!/data/data/com.termux/files/usr/bin/bash\ncd "%s" && exec bash menu.sh\n' "$TC_REPO" > "$target"
chmod 700 "$target"
ok "Ярлик створено: «Track Catcher»."
info "Додай віджет Termux:Widget на головний екран (якщо ще нема) — там з'явиться «Track Catcher»."
