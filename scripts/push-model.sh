#!/usr/bin/env bash
# Push the Gemma 4 E2B .litertlm into the app's private storage on a connected Android device.
# Usage: scripts/push-model.sh [path/to/gemma-4-E2B-it.litertlm]
set -euo pipefail
PKG=com.hocmemini.raid
MODEL="${1:-$(dirname "$0")/../models/gemma-4-E2B-it.litertlm}"
NAME=$(basename "$MODEL")
[ -f "$MODEL" ] || { echo "model not found: $MODEL"; exit 1; }
adb get-state >/dev/null 2>&1 || { echo "no device: pair + connect with adb first"; exit 1; }
echo "pushing $(du -h "$MODEL" | cut -f1) to /data/local/tmp (a few minutes over Wi-Fi)…"
adb push "$MODEL" /data/local/tmp/$NAME
echo "moving into app storage…"
adb shell run-as $PKG mkdir -p files
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/$NAME > files/$NAME'"
adb shell rm /data/local/tmp/$NAME
adb shell run-as $PKG ls -la files/
echo "done — open Raid and tap Load on GPU"
