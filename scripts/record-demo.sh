#!/usr/bin/env bash
# Record the phone screen for a demo video (no audio). Usage: scripts/record-demo.sh [seconds=120]
set -euo pipefail
cd "$(dirname "$0")/.."; source env.sh
SECS=${1:-120}
echo "recording ${SECS}s… play now"
adb shell screenrecord --time-limit "$SECS" --bit-rate 8000000 /sdcard/raid-demo.mp4
adb pull /sdcard/raid-demo.mp4 dist/raid-demo.mp4 && adb shell rm /sdcard/raid-demo.mp4
cp dist/raid-demo.mp4 /mnt/c/Users/hocme/Downloads/raid-demo.mp4 2>/dev/null || true
echo "saved dist/raid-demo.mp4 (and Downloads on Windows)"
