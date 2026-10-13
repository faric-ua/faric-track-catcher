#!/usr/bin/env bash
# APK, зібраний на цьому телефоні (пункт 11/12), — на GitHub, щоб інший (старий) телефон
# забрав його пунктом 5. Кладемо в окремий реліз «phone-build» (файли замінюються щоразу),
# офіційні релізи vX.Y.Z з GitHub Actions не чіпаються. Хвилини Actions не витрачаються.
# Використання: tc-publish-phone-apk.sh <шлях до APK>
set -u
. "$(dirname -- "$0")/tc-common.sh"
APK="${1:-}"
TAG="phone-build"
cd "$TC_REPO" || exit 1

[ -n "$APK" ] && [ -s "$APK" ] || { fail "Для іншого телефона: APK не знайдено — не публікую."; exit 1; }
# Опис має відповідати коду в Git — інакше інший телефон отримає «не той» APK.
if ! git diff --quiet HEAD -- android || [ -n "$(git ls-files --others --exclude-standard -- android)" ]; then
  fail "Для іншого телефона: в android/ є незбережені зміни — не публікую."
  exit 1
fi
tc_gh_ready || { warn "gh не увійшов у GitHub — APK лишився лише на цьому телефоні."; exit 1; }

version="$(tc_version)"
code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$TC_REPO/android/app/build.gradle.kts" | head -n 1)"
src="$(git rev-parse HEAD)"
stamp="$(date +%Y-%m-%d\ %H:%M)"
work="$HOME/.cache/track-catcher/phone-build"
mkdir -p "$work"
rm -f "$work"/*
name="Track-Catcher-v$version-phone.apk"
cp "$APK" "$work/$name"
(cd "$work" && sha256sum "$name" > "$name.sha256")
sha="$(cut -d' ' -f1 "$work/$name.sha256")"
cat > "$work/phone-build.env" <<META
VERSION_NAME=$version
VERSION_CODE=$code
SOURCE_SHA=$src
APK_NAME=$name
APK_SHA256=$sha
BUILT_AT=$stamp
META
notes="Track Catcher $version (build $code) — зібрано на телефоні $stamp.
Код: $src
APK SHA-256: $sha
Інший телефон: меню Track Catcher → 5."

echo "Відправляю APK на GitHub для іншого телефона (≈$(( $(wc -c < "$work/$name") / 1048576 )) МБ)…"
if ! gh release view "$TAG" -R "$TC_SLUG" >/dev/null 2>&1; then
  gh release create "$TAG" -R "$TC_SLUG" --prerelease --target main \
    --title "Зібрано на телефоні" --notes "$notes" >/dev/null 2>&1 || { warn "Не вдалося створити реліз phone-build (інтернет?)."; exit 1; }
fi
if gh release upload "$TAG" -R "$TC_SLUG" --clobber "$work/$name" "$work/$name.sha256" "$work/phone-build.env" >/dev/null 2>&1; then
  gh release edit "$TAG" -R "$TC_SLUG" --notes "$notes" >/dev/null 2>&1 || true
  # старі APK інших версій прибираємо з цього релізу, щоб не плутатись
  gh release view "$TAG" -R "$TC_SLUG" --json assets -q '.assets[].name' 2>/dev/null \
    | grep -E '^Track-Catcher-v.*-phone\.apk(\.sha256)?$' | grep -vxF -e "$name" -e "$name.sha256" \
    | while IFS= read -r old; do gh release delete-asset "$TAG" "$old" -R "$TC_SLUG" -y >/dev/null 2>&1 || true; done
  ok "Інший телефон може забрати v$version: меню Track Catcher → 5."
else
  warn "Не вдалося відправити APK на GitHub (немає інтернету?). На цьому телефоні APK є."
  exit 1
fi
