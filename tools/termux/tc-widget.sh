#!/usr/bin/env bash
# Ярлик «Track Catcher» для Termux:Widget — копія з репо (termux-widget/) у ~/.shortcuts.
# --quiet: тихо, лише якщо ярлика ще немає або він застарів (викликає menu.sh при кожному запуску).
set -u
. "$(dirname -- "$0")/tc-common.sh"
quiet=0
[ "${1:-}" = "--quiet" ] && quiet=1
src="$TC_REPO/termux-widget/Track Catcher"
dest_dir="$HOME/.shortcuts"
dest="$dest_dir/Track Catcher"
[ -f "$src" ] || { [ "$quiet" = 1 ] || fail "Немає $src"; exit 1; }
if [ "$quiet" = 1 ] && cmp -s "$src" "$dest" 2>/dev/null; then
  exit 0
fi
mkdir -p "$dest_dir"
chmod 700 "$dest_dir"
install -m 700 "$src" "$dest"
if [ "$quiet" = 1 ]; then
  echo "✅ Ярлик «Track Catcher» для Termux:Widget оновлено."
else
  ok "Ярлик «Track Catcher» є в Termux:Widget."
  info "Тепер: віджет Termux:Widget на головному екрані → кнопка оновлення (⟳) → «Track Catcher»."
  info "Віджета ще нема? Тримай палець на головному екрані → Віджети → Termux:Widget."
fi
