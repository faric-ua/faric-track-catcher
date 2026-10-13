#!/usr/bin/env bash
# Встановити APK, який зібрав інший телефон (реліз «phone-build»), — для старого телефона.
set -u
. "$(dirname -- "$0")/tc-common.sh"
tc_need_storage || exit 1
TAG="phone-build"
tmp="$HOME/.cache/track-catcher/phone-build-download"
mkdir -p "$tmp"
rm -f "$tmp"/*
base="https://github.com/$TC_SLUG/releases/download/$TAG"

echo "Шукаю APK, зібраний на телефоні…"
if tc_gh_ready; then
  gh release download "$TAG" -R "$TC_SLUG" -p "phone-build.env" -D "$tmp" --clobber >/dev/null 2>&1 || true
fi
[ -s "$tmp/phone-build.env" ] || curl -fsSL -o "$tmp/phone-build.env" "$base/phone-build.env" 2>/dev/null
if [ ! -s "$tmp/phone-build.env" ]; then
  fail "Ще ніхто не публікував APK з телефона."
  info "На основному телефоні: пункт 11 (збирає і сам відправляє сюди)."
  exit 1
fi
name="$(sed -n 's/^APK_NAME=//p' "$tmp/phone-build.env")"
version="$(sed -n 's/^VERSION_NAME=//p' "$tmp/phone-build.env")"
built="$(sed -n 's/^BUILT_AT=//p' "$tmp/phone-build.env")"
want="$(sed -n 's/^APK_SHA256=//p' "$tmp/phone-build.env")"
if [ -z "$name" ] || [ -z "$version" ] || [ -z "$want" ]; then
  fail "Опис APK неповний — не встановлюю."
  exit 1
fi
info "Версія $version, зібрано $built"
if tc_gh_ready; then
  gh release download "$TAG" -R "$TC_SLUG" -p "$name" -D "$tmp" --clobber >/dev/null 2>&1 || true
fi
[ -s "$tmp/$name" ] || curl -fsSL -o "$tmp/$name" "$base/$name"
have="$(sha256sum "$tmp/$name" 2>/dev/null | cut -d' ' -f1)"
if [ "$have" != "$want" ]; then
  fail "Контрольна сума не збігається — файл пошкоджений. Не встановлюю."
  exit 1
fi
ok "SHA-256 збігається."
dest="$TC_DOCS/apk/v$version-phone"
mkdir -p "$dest"
cp -f "$tmp/$name" "$dest/"
ok "Збережено: Documents/Track Catcher/apk/v$version-phone/$name"
echo
echo "Відкриваю встановлення. Натисни «Встановити» або «Оновити»."
tc_open "$dest/$name" || warn "Відкрий файл у «Моїх файлах»: Documents → Track Catcher → apk → v$version-phone."
