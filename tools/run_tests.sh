#!/usr/bin/env bash
# Runs the two checks that need only a JDK (no Android SDK):
#   1. FlowTest: compiles the whole app against Android stand-ins and drives the keyboard
#      service through typing scenarios (autocorrect, undo, swipe spacing, settings, learning).
#   2. Sim: measures autocorrect and swipe accuracy and speed on simulated noisy input.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
dict="$repo/app/src/main/assets/en_words.tsv"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

python3 "$here/android_stubs.py" "$work/stubs" >/dev/null
mkdir -p "$work/cls"
javac -nowarn -sourcepath "" -d "$work/cls" \
  $(find "$work/stubs" -name '*.java') \
  $(find "$repo/app/src/main/java" -name '*.java') \
  "$here/FlowTest.java" "$here/Sim.java"

echo "== Flow tests"
(cd "$work" && timeout 120 java -cp "$work/cls" FlowTest "$dict")
echo
echo "== Accuracy simulation (tap noise 0.30 key widths)"
(cd "$work" && java -cp "$work/cls" Sim "$dict" 0.3 | grep -E '^(TAP|SWIPE|learned|storage)')
