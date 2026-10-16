#!/usr/bin/env bash
# USB path for WSL: drives the Windows adb.exe directly (no network needed).
# Installs the APK and copies the model into the app. Usage: scripts/usb-setup.sh [serial]
set -euo pipefail
ADBW=/mnt/c/Users/hocme/platform-tools/adb.exe
PKG=com.hocmemini.raid
S=${1:+-s $1}
APK_W='C:\Users\hocme\Downloads\raid-debug.apk'
MODEL_W='C:\Users\hocme\Downloads\gemma-4-E2B-it.litertlm'
NAME=gemma-4-E2B-it.litertlm
cp "$(dirname "$0")/../dist/raid-debug.apk" /mnt/c/Users/hocme/Downloads/raid-debug.apk
echo "== devices =="; $ADBW devices -l | tr -d '\r'
echo "== install =="; $ADBW $S install -r -g "$APK_W" | tr -d '\r'
if $ADBW $S shell run-as $PKG ls files/$NAME >/dev/null 2>&1; then
  echo "== model already in app storage =="
else
  echo "== push model (2.6 GB over USB, ~1-2 min) =="; $ADBW $S push "$MODEL_W" /data/local/tmp/$NAME | tr -d '\r'
  echo "== move into app storage =="
  $ADBW $S shell run-as $PKG mkdir -p files
  $ADBW $S shell "run-as $PKG sh -c 'cat /data/local/tmp/$NAME > files/$NAME'"
  $ADBW $S shell rm /data/local/tmp/$NAME
fi
$ADBW $S shell run-as $PKG ls -la files/ | tr -d '\r'
$ADBW $S shell am start -n $PKG/.MainActivity >/dev/null
echo "done — Raid is open on the phone; tap Load on GPU"
