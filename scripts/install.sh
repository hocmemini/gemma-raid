#!/usr/bin/env bash
# Build (unless --no-build) and install the debug APK on one or all connected devices.
# Usage: scripts/install.sh [--no-build] [adb-serial]
set -euo pipefail
cd "$(dirname "$0")/.."
source env.sh
BUILD=1; SERIAL=""
for a in "$@"; do case "$a" in --no-build) BUILD=0;; *) SERIAL="$a";; esac; done
if [ "$BUILD" = 1 ]; then (cd android && gradle :app:assembleDebug --console=plain -q); fi
APK=android/app/build/outputs/apk/debug/app-debug.apk
cp "$APK" dist/raid-debug.apk 2>/dev/null || true
if [ -n "$SERIAL" ]; then DEVS="$SERIAL"; else DEVS=$(adb devices | awk 'NR>1 && $2=="device"{print $1}'); fi
[ -n "$DEVS" ] || { echo "no devices connected"; exit 1; }
for d in $DEVS; do echo "installing on $d"; adb -s "$d" install -r -g "$APK"; adb -s "$d" shell am start -n com.hocmemini.raid/.MainActivity >/dev/null; done
