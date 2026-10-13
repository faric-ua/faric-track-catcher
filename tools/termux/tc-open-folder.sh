#!/usr/bin/env bash
# Відкрити Музика/Track Catcher у «Моїх файлах».
set -u
. "$(dirname -- "$0")/tc-common.sh"
uri="content://com.android.externalstorage.documents/document/primary%3AMusic%2FTrack%20Catcher"
if am start -a android.intent.action.VIEW -d "$uri" -t vnd.android.document/directory >/dev/null 2>&1; then
  ok "Відкрито: Музика › Track Catcher"
else
  warn "Не відкрилось. Відкрий «Мої файли» → Музика → Track Catcher."
fi
