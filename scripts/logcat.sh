#!/usr/bin/env bash
# Tail the app's logs. Usage: scripts/logcat.sh [adb-serial]
cd "$(dirname "$0")/.."; source env.sh
S=${1:+-s $1}
adb $S logcat -c
adb $S logcat -v time 'Gemma:*' 'NearbyLink:*' 'AndroidRuntime:E' 'litert:*' 'LiteRT:*' 'litertlm:*' 'tflite:*' 'System.err:*' '*:S'
