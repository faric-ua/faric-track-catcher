#!/usr/bin/env bash
# Відкрити в «Моїх файлах» папку з останнім APK Track Catcher (з GitHub, зібраним тут чи на іншому телефоні).
set -u
. "$(dirname -- "$0")/tc-common.sh"
apk="$(ls -t "$TC_DOCS"/apk/*/*.apk 2>/dev/null | head -n 1)"
if [ -z "$apk" ]; then
  fail "На цьому телефоні ще немає жодного APK Track Catcher."
  info "Пункт 2 — завантажити з GitHub; пункт 11 — зібрати тут."
  exit 1
fi
folder="$(basename "$(dirname "$apk")")"
echo "Папка: Documents › Track Catcher › apk › $folder"
info "APK: $(basename "$apk")"
if tc_open_dir "Documents/Track Catcher/apk/$folder"; then
  ok "Відкрито. Торкнись APK, щоб встановити."
else
  warn "Android не відкрив папку сам. Відкрий «Мої файли» → Documents → Track Catcher → apk → $folder."
fi
