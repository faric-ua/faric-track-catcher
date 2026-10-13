#!/usr/bin/env bash
# Знімки екранів Track Catcher на емуляторі: книжкова й альбомна, шрифт 1.0 і 1.3, вікно після повороту.
# Запускається в CI (ui-screens.yml). Демо-дані — лише в debug-збірці (.dev).
set -u
OUT="${1:-ui-screens}"
APK="android/app/build/outputs/apk/debug/app-debug.apk"
PKG="ua.faric.trackcatcher.dev"
ACT="$PKG/ua.faric.trackcatcher.ui.MainActivity"
mkdir -p "$OUT"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "знімок: $1"; }
rotate() {
  adb shell settings put system user_rotation "$1"
  adb shell cmd window user-rotation lock "$1" >/dev/null 2>&1 || adb shell wm user-rotation lock "$1" >/dev/null 2>&1 || true
  sleep 4
}
start() { adb shell am start -n "$ACT" "$@" >/dev/null; }

adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system accelerometer_rotation 0
adb shell settings put system font_scale 1.0
# не показувати системну підказку «Viewing full screen» над нічним екраном
adb shell settings put secure immersive_mode_confirmations confirmed || true
adb install -r "$APK"
adb logcat -c
rotate 0

start
shot p01-first-run 5
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell cmd notification allow_listener "$PKG/ua.faric.trackcatcher.capture.NowPlayingListener" || true
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null || true
start --ez demo_seed true --es tab RECORD
shot p02-record-ready
start --es demo_state recording --es tab RECORD
shot p03-record-recording
start --es demo_state recording --es tab RECORD --es dialog night
shot p03b-night-screen
adb shell input keyevent KEYCODE_BACK
start --es tab HISTORY
shot p04-history
start --es tab HISTORY --es dialog detail:3
shot p05-history-detail
start --es tab HISTORY --es dialog delete:3
shot p06-delete-confirm
rotate 1
shot l01-delete-confirm-after-rotation
adb shell input keyevent KEYCODE_BACK
start --es tab HISTORY --es dialog detail:3
shot l02-history-two-pane
start --es tab RECORD
shot l03-record-two-pane
start --es tab SETTINGS
shot l04-settings-two-pane
rotate 0
shot p07-settings
# інструкція користувача (окреме вікно; singleTop — розділи змінюються без закриття)
guide() { adb shell am start -n "$PKG/ua.faric.trackcatcher.ui.GuideActivity" --es section "$1" >/dev/null; }
guide home
shot g01-guide-home 5
guide record
shot g02-guide-record 4
guide history
shot g03-guide-history 4
guide night
shot g04-guide-night 4
adb shell input keyevent KEYCODE_BACK
start --es tab SETTINGS
adb shell settings put system font_scale 1.3
sleep 2
start --es tab RECORD
shot p08-record-font130 4
start --es tab SETTINGS
shot p09-settings-font130
rotate 1
start --es tab HISTORY
shot l05-history-font130
rotate 0
adb shell settings put system font_scale 1.0

# Падіння лише нашого застосунку (системні застосунки емулятора теж іноді падають — їх не рахуємо).
adb logcat -d -b crash > "$OUT/crash-all.txt" || true
grep -A 30 "Process: $PKG" "$OUT/crash-all.txt" > "$OUT/crash.txt" || true
adb logcat -d -s AndroidRuntime:E > "$OUT/runtime-errors.txt" || true
adb shell wm size > "$OUT/display.txt" 2>&1 || true
if [ -s "$OUT/crash.txt" ]; then
  echo "::error title=UI screens::Track Catcher падав на емуляторі: $(head -c 600 "$OUT/crash.txt" | tr '\n' ' ')"
  exit 1
fi
echo "::notice title=UI screens::$(ls "$OUT"/*.png | wc -l) знімків, падінь немає"
