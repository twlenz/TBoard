#!/usr/bin/env bash
# Installs the debug APK on a running emulator, makes LocalBoard the keyboard, runs the
# screenshot driver (app/src/androidTest/.../ShotRunner.java) and pulls the results.
# Usage: tools/ci_screenshots.sh OUTPUT_DIR   (needs adb and a booted emulator)
set -uo pipefail
out="${1:-shots}"
mkdir -p "$out"
apk=app/build/outputs/apk/debug/app-debug.apk
tapk=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
ime=dev.localboard/.LocalKeyboardService

adb wait-for-device
adb shell input keyevent 82 || true                       # unlock
adb shell cmd uimode night no || true
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 20                                                  # let the launcher settle so it doesn't ANR mid-run
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
# older emulators report "booted" before the package manager is ready
for i in $(seq 1 60); do adb shell pm path android 2>/dev/null | grep -q package: && break; sleep 2; done
install() {
  for i in 1 2 3 4 5; do
    adb install -r -g "$1" | tee -a "$out/install.txt" | grep -q Success && return 0
    sleep 5
  done
  return 1
}
install "$apk" || echo "app install failed" >> "$out/install.txt"
install "$tapk" || echo "screenshot driver install failed" >> "$out/install.txt"
adb shell pm list packages | grep localboard >> "$out/install.txt" || true
adb shell dumpsys package dev.localboard | grep -iE "versionName|targetSdk|minSdk|LocalKeyboardService|BIND_INPUT|android.view.InputMethod" >> "$out/install.txt" || true
# the system can take a moment to notice a newly installed keyboard
for i in $(seq 1 30); do
  adb shell ime list -a -s | grep -q "$ime" && break
  sleep 2
done
adb shell ime list -a -s > "$out/ime-list.txt"
adb shell ime enable "$ime"
adb shell ime set "$ime"
adb logcat -d | grep -iE "localboard|InputMethod" | grep -viE "latin|openwnn|softkeyboard" | tail -80 > "$out/ime-log.txt" || true
adb shell rm -rf /sdcard/lbshots
adb logcat -c
adb shell am instrument -w -r dev.localboard.test/dev.localboard.shots.ShotRunner | tee "$out/instrument.txt"

adb pull /sdcard/lbshots/. "$out/" || true
adb logcat -d -b crash > "$out/crash-log.txt" || true
adb logcat -d -s LocalBoard:V LBSHOTS:V LBGEOM:V AndroidRuntime:E > "$out/app-log.txt" || true
adb shell dumpsys input_method | grep -E "mCurMethodId|mInputShown" > "$out/ime-state.txt" || true
ls -la "$out"
# fail the step if the app crashed
! grep -q "dev.localboard" "$out/crash-log.txt"
