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
adb install -r -g "$apk"
adb install -r -g "$tapk"
adb shell ime enable "$ime"
adb shell ime set "$ime"
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
