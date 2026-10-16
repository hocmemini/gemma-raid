# Resuming work on Raid (dev notes)

State as of 2026-10-16 ~14:50. Everything is committed and pushed to https://github.com/hocmemini/gemma-raid.

## Toolchain (sudo-free, all in $HOME)
```bash
source ~/gemma-hackday/env.sh        # JDK 17, Android SDK, Gradle 8.14.3, ADB_SERVER_SOCKET -> Windows adb
```
- The laptop's work Wi-Fi blocks phone<->laptop traffic, so the phone is driven over **USB via the Windows adb server**.
  After a restart that server is gone; start it from WSL (keep it running in the background):
  ```bash
  /mnt/c/Users/hocme/platform-tools/adb.exe kill-server; /mnt/c/Users/hocme/platform-tools/adb.exe -a -P 5037 nodaemon server &
  source ~/gemma-hackday/env.sh && adb devices        # Pixel 8 Pro = 3A171FDJG000Q7 (tap Allow on the phone if asked)
  ```
- Build + install: `cd android && gradle :app:assembleDebug` then `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`
  (or `scripts/install.sh`). `scripts/usb-setup.sh` also pushes the 2.6 GB model into the app if it is missing.
- Logs: `adb logcat -v time 'Gemma:*' 'Sprite:*' 'Sfx:*' 'Raid:*' 'NearbyLink:*' 'AndroidRuntime:E' '*:F'`
  (`Gemma` logs every tool call's arguments, timings, JSON spawns and recoveries; `Sprite` says which subject the segmenter picked.)
- Never send `adb shell input` taps without checking `dumpsys window | grep mCurrentFocus` is the Raid activity first.

## Phone facts
Pixel 8 Pro, Android 17, 12 GB, Tensor G3 (no NPU build; GPU/OpenCL). Gemma 4 E2B loads in ~3 s warm / ~30 s cold.
Model file lives in the app's filesDir (also candidates in externalFilesDir). Stay-awake-on-USB is enabled.

## Where the code is
`android/app/src/main/kotlin/com/hocmemini/raid/`
- `GameSession.kt` — all game logic (process-scoped singleton), phases EXPLORE/BATTLE/NPC/VICTORY/DEFEAT, host/client dispatch.
- `llm/Gemma.kt` — LiteRT-LM wrapper: tools (snake_case params), constrained-JSON spawn/meet, lenient recovery (`LenientTools`), `clean()`.
- `llm/ModelManager.kt` — model discovery, recommendation, download, backend choice.
- `camera/SpriteMaker.kt` — ML Kit multi-subject segmentation, picks the subject under the crosshair.
- `ui/Screens.kt` (lobby, play, overlay, FX), `ui/StatsScreen.kt`, `ui/JournalScreen.kt`, `ui/ModelScreen.kt`, `ui/Theme.kt`.
- `audio/Sfx.kt` (SoundPool + TTS voices), `scripts/gen-sfx.py` (synthesizes res/raw).

## Open items / ideas
- Two-phone Nearby test never happened (Pixel 7 not on hand). Code path exists; audio and sprites stream to clients.
- Spawn takes ~25-28 s via constrained JSON (was 12-15 s with plain tool calls). Latest build runs spawn and battle in one
  conversation to save a prefill — measure with the `spawn json in N ms` log line; if still slow, consider a shorter schema.
- Last user-reported goofiness (talk-it-down became an attack, merchant not charging, empty "mumbles") was addressed blind
  because the phone was unplugged during that round; verify with the log on the next round.
- Not built: optional online mode (gemma-4-26b-a4b-it via Gemini API), proper demo video, hackathon submission write-up.
