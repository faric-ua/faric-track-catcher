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

tc_open() {
  # Відкрити файл (APK → встановлення).
  if command -v termux-open >/dev/null 2>&1; then
    termux-open --view "$1" >/dev/null 2>&1 || termux-open "$1" >/dev/null 2>&1
  else
    return 1
  fi
}
