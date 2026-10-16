# Raid — a co-op AR RPG where Gemma 4 is the dungeon master

*Working title. Built in one day at MLH Hacktoberfest Hack Day (Draper x AMH), 2026-10-16.*

Point your phone at anything in the room. **Gemma 4 E2B, running entirely on the phone**, turns it into a
monster. Your friends' phones connect to yours over Bluetooth / Wi-Fi Direct — no internet, no server — and
you fight it together: turn to face it, flank it from different sides, shout or type what you do, and Gemma
adjudicates every move through function calls so the game state stays deterministic and synced.

## How it works

| Piece | Tech |
|---|---|
| Brain | Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`) via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), GPU backend, on the host phone |
| Vision | camera photo → `spawn_monster(name, hp, attacks, weakness, flavor)` tool call |
| Rules | player action → `resolve_action(damageToMonster, damageToPlayer, narration)` tool call; host applies facing / flank modifiers |
| Multiplayer | Google Nearby Connections, star topology, JSON payloads; clients are thin, only the host runs the model |
| AR-ish | rotation-vector compass; the monster is pinned to a bearing (and GPS point) and slides across the camera view as you turn |
| Audio | Gemma 4 E2B takes audio natively — hold-to-talk actions without a speech-to-text layer (in progress) |

Open weights (Apache 2.0) are the whole game: nothing works without the model on the phone.

## Build (no Android Studio needed)

```bash
source env.sh                 # JDK 17 + Android SDK + Gradle in $HOME (see scripts/ for install)
cd android && gradle :app:installDebug
scripts/push-model.sh         # copies models/gemma-4-E2B-it.litertlm into the app
```

Or download the `.litertlm` in Chrome on the phone and use **Pick file** in the app.

Tested on Pixel 8 Pro. Any Android 12+ phone with Bluetooth can join as a player; only the host needs 6 GB+ RAM.

## Status

- [ ] on-device Gemma 4 E2B, image in, tool calling (written, not yet run on a phone)
- [ ] host / join over Nearby Connections (written, not yet run on a phone)
- [ ] heading-locked monster overlay, facing + flank modifiers (written, not yet run on a phone)
- [ ] hold-to-talk audio actions
- [ ] loot generation, persistent party
- [ ] optional online mode: `gemma-4-26b-a4b-it` via Gemini API for richer narration

## License

Apache-2.0. Gemma 4 weights are Apache-2.0 too.
