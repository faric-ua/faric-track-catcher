#!/usr/bin/env bash
# Відкрити Музика/Track Catcher у «Моїх файлах».
set -u
. "$(dirname -- "$0")/tc-common.sh"
if tc_open_dir "Music/Track Catcher"; then
  ok "Відкрито: Музика › Track Catcher"
else
  warn "Не відкрилось. Відкрий «Мої файли» → Музика → Track Catcher."
fi
