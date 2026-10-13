#!/usr/bin/env bash
# Завантажити останній реліз з GitHub, перевірити SHA-256, відкрити встановлення.
set -u
. "$(dirname -- "$0")/tc-common.sh"
tc_need_storage || exit 1

echo "Шукаю останній реліз Track Catcher…"
tag=""
if tc_gh_ready; then
  tag="$(gh release list -R "$TC_SLUG" -L 1 --exclude-drafts --json tagName -q '.[0].tagName' 2>/dev/null || true)"
fi
if [ -z "$tag" ]; then
  tag="$(curl -fsSL "https://api.github.com/repos/$TC_SLUG/releases?per_page=1" 2>/dev/null \
    | grep -o '"tag_name": *"[^"]*"' | head -n 1 | sed 's/.*"\([^"]*\)"$/\1/')"
fi
if [ -z "$tag" ]; then
  fail "Релізів ще немає."
  info "Перший раз: пункт 7 (ключ підпису) — він сам запустить збірку. Через 5–7 хв — знову пункт 2."
  exit 1
fi
version="${tag#v}"
apk="Track-Catcher-v$version.apk"
tmp="$HOME/.cache/track-catcher/$tag"
mkdir -p "$tmp"
rm -f "$tmp/$apk" "$tmp/$apk.sha256"

echo "Реліз: $tag. Завантажую…"
if tc_gh_ready; then
  gh release download "$tag" -R "$TC_SLUG" -p "$apk" -p "$apk.sha256" -D "$tmp" --clobber >/dev/null 2>&1 || true
fi
base="https://github.com/$TC_SLUG/releases/download/$tag"
[ -s "$tmp/$apk" ] || curl -fsSL -o "$tmp/$apk" "$base/$apk"
[ -s "$tmp/$apk.sha256" ] || curl -fsSL -o "$tmp/$apk.sha256" "$base/$apk.sha256"
if [ ! -s "$tmp/$apk" ] || [ ! -s "$tmp/$apk.sha256" ]; then
  fail "Не вдалося завантажити $apk."
  exit 1
fi
if ! (cd "$tmp" && sha256sum -c "$apk.sha256" >/dev/null 2>&1); then
  fail "Контрольна сума не збігається — файл пошкоджений. Не встановлюю."
  exit 1
fi
ok "SHA-256 збігається."

dest="$TC_DOCS/apk/v$version"
mkdir -p "$dest"
cp -f "$tmp/$apk" "$tmp/$apk.sha256" "$dest/"
(cd "$dest" && sha256sum -c "$apk.sha256" >/dev/null 2>&1) || { fail "Копія в Documents пошкоджена."; exit 1; }
ok "Збережено: Documents/Track Catcher/apk/v$version/$apk"
echo
echo "Відкриваю встановлення. Натисни «Встановити» (перший раз) або «Оновити»."
echo "Очікується: Track Catcher $version"
if ! tc_open "$dest/$apk"; then
  warn "Не відкрилось автоматично. Відкрий файл у «Моїх файлах»: Documents → Track Catcher → apk → v$version."
fi
