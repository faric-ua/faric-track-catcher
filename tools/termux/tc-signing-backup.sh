#!/usr/bin/env bash
# Резервна копія ключа підпису в Documents/Track Catcher/key-backup.
set -u
. "$(dirname -- "$0")/tc-common.sh"
if [ ! -f "$TC_KEY" ] || [ ! -f "$TC_PROPS" ]; then
  fail "Ключа ще немає — спершу пункт 7."
  exit 1
fi
tc_need_storage || exit 1
dest="$TC_DOCS/key-backup"
mkdir -p "$dest"
stamp="$(date +%Y%m%d-%H%M)"
cp "$TC_KEY" "$dest/track-catcher-$stamp.jks"
cp "$TC_PROPS" "$dest/track-catcher-signing-$stamp.properties"
ok "Копія: Documents/Track Catcher/key-backup/ ($stamp)"
warn "Цей файл = право підписувати Track Catcher. Нікому не пересилай і не клади в GitHub."
