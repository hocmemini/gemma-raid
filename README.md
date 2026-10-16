# Raid — a co-op AR RPG where Gemma 4 is the dungeon master

*Working title. Built in one day at MLH Hacktoberfest Hack Day (Draper x AMH), 2026-10-16.*

Point your phone at anything in the room. **Gemma 4 E2B, running entirely on the phone**, turns it into a
monster. Your friends' phones connect to yours over Bluetooth / Wi-Fi Direct — no internet, no server — and
you fight it together: turn to face it, flank it from different sides, shout or type what you do, and Gemma
adjudicates every move through function calls so the game state stays deterministic and synced.

## How it works

| Piece | Tech |
|---|---|
| Brain | Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`, Apache 2.0) via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), GPU backend, on the host phone. 30 s cold load, 3 s warm, on a Pixel 8 Pro |
| Vision | camera photo → `spawn_monster(name, hp, attacks, weakness, flavor, emoji)` tool call, ~12 s |
| Rules | typed or spoken action → `resolve_action(damageToMonster, damageToPlayer, narration)`, ~6 s; the host applies facing, flank, parry and battle-cry modifiers |
| Loot & progression | `drop_loot(name, effect)` after a kill; items ride along into later fights; XP, levels, max HP |
| Multiplayer | Google Nearby Connections, star topology, JSON payloads (audio as STREAM payloads); clients are thin, only the host runs the model |
| AR-ish | rotation-vector compass; the monster is pinned to a bearing (and GPS point) and slides across the camera view as you turn |
| Audio | Gemma 4 E2B takes 16 kHz WAV natively, so hold-to-talk actions skip speech-to-text entirely |
| Sensors as mechanics | swing the phone to strike (linear acceleration); Pixel double-twist to parry; shout volume = battle cry; steps walked since the last fight grow the next monster; ambient light, barometric floors and gyro-die temperature are described to Gemma at spawn |
| Omens | Wi-Fi SSIDs and named BLE devices nearby → `write_omens(names, lores, threats)`; the strongest menacing signal becomes a boss lair for the next spawn. Offices are haunted, parks are wild country |

Every game-affecting number comes out of a tool call, never parsed from prose. Open weights are the whole game: nothing works without the model on the phone.

## Build (no Android Studio needed)

```bash
source env.sh                      # JDK 17 + Android SDK + Gradle in $HOME (sudo-free install, see docs/)
cd android && ./gradlew :app:installDebug
../scripts/push-model.sh           # copies models/gemma-4-E2B-it.litertlm into the app
```

Or download the `.litertlm` in Chrome on the phone and use **Pick file** in the app.
`scripts/usb-setup.sh` does install + model push over USB from WSL via the Windows adb.

Tested on Pixel 8 Pro (Android 17). Any Android 12+ phone with Bluetooth can join as a player; only the host needs 6 GB+ RAM.

## Play

1. Host taps **Host**; friends tap **Join a nearby host** (Bluetooth on, no internet needed).
2. Walk around. Tap **Omens** to read the airwaves. Point the camera at anything and tap **Spawn**.
3. Turn until the monster appears on screen. Type, shout, or swing. Twist to parry. Flank from different sides.
4. Loot it, level up, go find the next one.

## Status

- [x] on-device Gemma 4 E2B: image in, audio in, tool calling (verified on Pixel 8 Pro)
- [x] host / join over Nearby Connections (code complete; two-phone test pending)
- [x] heading-locked monster overlay, facing + flank modifiers
- [x] hold-to-talk audio actions
- [x] loot, XP, levels; step-scaled monsters; haptics
- [x] swing / twist / shout / light / barometer mechanics
- [x] Wi-Fi + BLE omens and boss lairs
- [ ] optional online mode: `gemma-4-26b-a4b-it` via Gemini API for richer narration

## License

Apache-2.0. Gemma 4 weights are Apache-2.0 too.
