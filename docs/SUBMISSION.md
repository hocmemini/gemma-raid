# Raid — submission write-up

**Categories:** Best Use of Gemma 4 (Google DeepMind) · Best Open-Source AI Project (DigitalOcean)
**Result:** 🏆 Winner, Best Open-Source AI Project (DigitalOcean) — one of two winning projects
**Repo:** https://github.com/hocmemini/gemma-raid (Apache-2.0) · **Built:** one day, MLH Hacktoberfest Hack Day Draper x AMH, 2026-10-16

## Inspiration
Every past Gemma competition is full of accessibility assistants, offline clinics and tutors, and almost no games.
We wanted the opposite: the model as a *dungeon master* that turns whatever is in front of your phone into a
monster, judges whatever you shout at it, and never needs a server. The office becomes a dungeon.

## What it does
Point your phone at anything. Gemma 4 E2B, running entirely on the phone, writes the monster sheet: name, HP,
attacks, a weakness you can actually act on, a taunt it shouts in its own voice. On-device segmentation cuts the real
object out of the photo and that cut-out *is* the monster: it breathes in a lock-on ring pinned to your compass
bearing, sidesteps when you miss, flies at the screen with perspective when it strikes, and bursts when it dies.

You fight by typing, by shouting (the audio goes straight into Gemma 4, no speech-to-text), or by swinging the
phone. Wit is scored: a TSA liquid check on a bag is a legendary blow; "I hit it" is not. Loot, potions, levels
that unlock verbs (Taunt, Parley, Bind a slain monster as a companion, Recast its weakness), merchants that charge
XP and haggle, sages, healers, omens read from nearby Wi-Fi and Bluetooth names, barometric floors and darkness
that shape what spawns, and *lurkers*: things you walk past are turned into monsters in the background and ambush
you later. Friends' phones join over Nearby Connections with no internet; only the host runs the model.

## How we built it
- **Gemma 4 E2B** via LiteRT-LM on the GPU of a Pixel 8 Pro: 3 s warm load, ~6 s per action, 12–25 s per spawn.
- Every number in the game comes out of a **tool call** (`spawn_monster`, `resolve_action`, `drop_loot`,
  `sell_item`, `use_power`, …) or a **constrained-JSON** response, never from parsing prose.
- **ML Kit subject segmentation** (multi-subject, pick the one under the crosshair) for the sprites.
- **Nearby Connections** star topology; audio clips and sprites stream to clients; clients are thin.
- **Sensors as mechanics:** rotation vector (bearing), step detector (bigger monsters, escape rolls, ambush timing),
  linear acceleration (swing), the Pixel double-twist gesture (parry), ambient light, barometer, gyro-die temperature,
  mic loudness (battle cry), Wi-Fi/BLE scans (omens).
- 25 chiptune effects synthesized by a 120-line Python script; three Android TTS voices for DM, monsters and NPCs.
- Kotlin + Jetpack Compose, built from WSL with no Android Studio; deployed over USB through a Windows adb bridge
  because the venue Wi-Fi isolates clients.

## Challenges
- **E2B's tool-call syntax is fragile.** It drops a `<|"|>` quote token or switches to snake_case, and the runtime's
  strict parser rejects the whole reply. We renamed parameters to the snake_case it prefers, replaced list arguments
  with strings, added a lenient recovery parser that rebuilds the call from the raw response, and moved spawns to
  constrained JSON. Tool-call success went from "most of the time" to "every turn".
- **"Please provide an image."** The model sometimes answered a photo prompt as if no photo were attached.
  Constrained decoding with a schema removed that failure mode.
- **Indoor compass and GPS.** Steel desks drift the compass; indoor GPS jumps 20 m. GPS bearings are gated on
  accuracy, a long-press re-pins the monster, and a monster you turn away from comes around to face you.
- **Memory.** Android recreated the Activity while the player checked a message and the 2.6 GB engine vanished with
  the ViewModel. The session now lives in the Application.
- **The segmenter picked the table.** Multi-subject mode plus "smallest subject under the crosshair" fixed it.

## Accomplishments
A complete, playable, offline, multiplayer AR RPG with an LLM dungeon master on a phone, built and played in a day.
The monster is the actual thing on your desk, and it talks back.

## What we learned
Open-weight on-device models are good enough to *run a game*, not just answer questions, if you give them structure:
tools or schemas for every number, recovery for every syntax slip, and the sensors to make the real world the board.

## What's next
Two-phone raids in a park (lurkers thrive outdoors), an optional cloud brain (`gemma-4-26b-a4b-it` via the Gemini API
for richer narration when online), ARCore anchoring, a proper bestiary and trophy sharing.
