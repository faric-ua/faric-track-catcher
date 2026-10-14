#!/usr/bin/env bash
# Спільне для скриптів меню Track Catcher.
TC_SLUG="faric-ua/faric-track-catcher"
TC_REPO="${TC_REPO:-$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)}"
TC_DOCS="/storage/emulated/0/Documents/Track Catcher"
TC_KEY="$HOME/.android/track-catcher.jks"
TC_PROPS="$HOME/.android/track-catcher-signing.properties"
TC_ALIAS="trackcatcher"

ok() { printf '✅ %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '⚠️  %s\n' "$*"; }
fail() { printf '❌ %s\n' "$*"; }

tc_version() {
  sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$TC_REPO/android/app/build.gradle.kts" 2>/dev/null | head -n 1
}

tc_need_storage() {
  if ! mkdir -p "$TC_DOCS" 2>/dev/null; then
    fail "Termux не має доступу до пам'яті телефона."
    info "Один раз: команда termux-setup-storage → «Дозволити»."
    return 1
  fi
}

tc_gh_ready() {
  command -v gh >/dev/null 2>&1 && gh auth status -h github.com >/dev/null 2>&1
}

tc_prop() {
  sed -n "s/^$1=//p" "$TC_PROPS" 2>/dev/null | head -n 1
}

# Відкрити папку пам'яті телефона в «Моїх файлах». Аргумент — шлях від /storage/emulated/0,
# напр. "Documents/Track Catcher/apk/v0.1.4". Три спроби, як у Renault (A26 і M30s відкривають по-різному).
tc_open_dir() {
  local enc uri
  enc="$(printf '%s' "$1" | sed 's/%/%25/g; s/ /%20/g; s#/#%2F#g')"
  uri="content://com.android.externalstorage.documents/document/primary%3A$enc"
  if am start -a android.intent.action.VIEW -d "$uri" -t vnd.android.document/directory 2>&1 | grep -qi "error"; then
    :
  else
    return 0
  fi
  if am start -a android.intent.action.OPEN_DOCUMENT_TREE --eu android.provider.extra.INITIAL_URI "$uri" 2>&1 | grep -qi "error"; then
    return 1
  fi
  return 0
}

tc_open() {
  # Відкрити файл (APK → встановлення).
  if command -v termux-open >/dev/null 2>&1; then
    termux-open --view "$1" >/dev/null 2>&1 || termux-open "$1" >/dev/null 2>&1
  else
    return 1
  fi
}
