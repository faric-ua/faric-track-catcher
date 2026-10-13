#!/usr/bin/env bash
# Track Catcher — меню для Termux.
# Правило: номери пунктів ніколи не зсуваються; нове — наступним вільним номером.
set -u

REPO="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$REPO/tools/termux"
export TC_REPO="$REPO"
cd "$REPO" || exit 1

# Раз на запуск — тихо оновити меню з GitHub і перезапуститись.
# tc-sync.sh: 0 — оновлено, 10 — уже актуально, інше — проблема (показати й чекати Enter).
if [ -z "${TC_MENU_SYNCED:-}" ]; then
  export TC_MENU_SYNCED=1
  bash "$TOOLS/tc-sync.sh" --quiet
  rc=$?
  if [ "$rc" = 0 ]; then
    exec bash "$REPO/menu.sh"
  elif [ "$rc" != 10 ]; then
    echo
    read -r -p "Натисни Enter, щоб відкрити меню…" _
  fi
fi

pause_menu() {
  echo
  read -r -p "Натисни Enter, щоб повернутись у меню…" _
}

run_tool() {
  clear
  bash "$TOOLS/$1"
  pause_menu
}

while true; do
  clear
  version="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$REPO/android/app/build.gradle.kts" 2>/dev/null | head -n 1)"
  echo "Track Catcher · меню"
  echo "$(git branch --show-current 2>/dev/null) · $(git rev-parse --short HEAD 2>/dev/null) · у репо v${version:-?}"
  echo
  echo " ЩОДНЯ"
  echo "  1 — Оновити меню з GitHub"
  echo "  2 — Встановити останній реліз (завантажити, перевірити, відкрити встановлення)"
  echo "  3 — Статус: що на GitHub і на телефоні"
  echo "  4 — Відкрити папку з записами"
  echo
  echo " ПЕРШЕ НАЛАШТУВАННЯ (один раз)"
  echo "  7 — Ключ підпису: створити й передати в GitHub"
  echo "  8 — Ключ підпису: резервна копія в Documents"
  echo "  9 — Ярлик «Track Catcher» для Termux:Widget"
  echo
  echo " РЕЗЕРВ"
  echo " 11 — Зібрати APK на телефоні й встановити"
  echo " 12 — Зібрати на телефоні БЕЗ інтернету (після першої збірки)"
  echo
  echo "  0 — Вийти"
  echo
  read -r -p "Вибір: " choice
  case "$choice" in
    1)
      clear
      bash "$TOOLS/tc-sync.sh"
      pause_menu
      exec bash "$REPO/menu.sh"
      ;;
    2) run_tool tc-install-latest.sh ;;
    3) run_tool tc-status.sh ;;
    4) run_tool tc-open-folder.sh ;;
    7) run_tool tc-signing-setup.sh ;;
    8) run_tool tc-signing-backup.sh ;;
    9) run_tool tc-widget.sh ;;
    11) run_tool tc-local-build.sh ;;
    12)
      clear
      bash "$TOOLS/tc-local-build.sh" --offline
      pause_menu
      ;;
    0 | q | Q) exit 0 ;;
    *) ;;
  esac
done
