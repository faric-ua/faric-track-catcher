#!/usr/bin/env bash
# Вікно встановлення сховалось (напр., торкнувся клавіатури)? Відкрити ще раз — останній APK на цьому телефоні.
set -u
. "$(dirname -- "$0")/tc-common.sh"
dir="$TC_DOCS/apk"
apk="$(ls -t "$dir"/*/*.apk 2>/dev/null | head -n 1)"
if [ -z "$apk" ]; then
  fail "На цьому телефоні ще немає жодного APK Track Catcher."
  info "Пункт 2 — завантажити з GitHub; пункт 11 — зібрати тут."
  exit 1
fi
folder="$(basename "$(dirname "$apk")")"
case "$folder" in
  *-local) src="зібрано на цьому телефоні" ;;
  *-phone) src="зібрано на іншому телефоні" ;;
  *) src="з GitHub" ;;
esac
echo "Останній APK: $(basename "$apk")"
info "Звідки: $src · $(date -r "$apk" '+%d.%m %H:%M' 2>/dev/null)"
echo
echo "Відкриваю встановлення. Натисни «Встановити» або «Оновити»."
tc_open "$apk" || warn "Не відкрилось. Відкрий у «Моїх файлах»: Documents → Track Catcher → apk → $folder."
