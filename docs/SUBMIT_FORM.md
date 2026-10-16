# Instructions for the browser agent: fill the "Submit Your Project" form on the Hacktoberfest Hack Day Draper x AMH page

Fill every field below exactly as given, then click **Save draft**, then **Submit Project**. Do not change wording.
If a field has a Markdown "Write/Preview" toggle, stay on Write and paste the text as-is.

## Project name
Raid

## Source code
https://github.com/hocmemini/gemma-raid

## Description (paste exactly, it is Markdown)
**Raid is a co-op AR RPG where Gemma 4 runs on your phone as the dungeon master.** Point the camera at anything on your desk. Gemma 4 E2B, running fully on-device through LiteRT-LM, writes the monster: name, HP, attacks, a weakness you can act on, and a taunt it shouts in its own voice. On-device segmentation cuts the real object out of the photo and that cut-out *is* the monster: it breathes in a lock-on ring pinned to your compass bearing, dodges when you miss, flies at the screen when it strikes, and bursts when it dies.

You fight by typing, by **shouting** (the audio clip goes straight into Gemma 4, no speech-to-text), or by **swinging the phone**. Wit is scored by the model: a TSA liquid check on a bag is a legendary blow, "I hit it" is not. Every number in the game comes from a tool call or a schema-constrained JSON response, never from parsing prose.

- **Loot, potions, levels that unlock verbs:** Taunt, Parley (talk a monster into an NPC), Bind (a slain monster fights beside you), Recast.
- **NPCs:** point at an object and *Meet* it instead: merchants that charge XP and haggle, sages, healers.
- **The real world is the board:** compass, step counter, barometric floors, darkness, phone swings, the Pixel double-twist, mic loudness, and nearby Wi-Fi/Bluetooth names that become omens and boss lairs.
- **Lurkers:** things you walk past are quietly turned into monsters in the background and ambush you later.
- **Multiplayer with no internet:** friends join over Nearby Connections; only the host phone runs the model.
- 25 synthesized sound effects, three TTS voices, trophy cards saved to Photos, stats, a journal, a bestiary.

Built in one day in Kotlin/Compose from WSL with no Android Studio. Tested on a Pixel 8 Pro: 3 s warm model load, ~6 s per action, 12–25 s per spawn. Apache-2.0; the weights are Apache-2.0 too. Full write-up: https://github.com/hocmemini/gemma-raid/blob/main/docs/SUBMISSION.md

## Technologies Used (enter each as a separate tag/entry)
Gemma 4 E2B, LiteRT-LM, Kotlin, Jetpack Compose, CameraX, ML Kit Subject Segmentation, Google Nearby Connections, Android TTS, kotlinx.serialization, Gradle, Python (sound synthesis), Hugging Face (open weights)

## "AI your project calls" checkboxes
Tick: **Gemma**. Tick **Hugging Face** too (the open weights are downloaded from Hugging Face). Leave every other AI box unticked (no Gemini, no OpenAI, no Claude, no Ollama).

## Live demo link
Leave empty (no video yet). If a URL is required, use: https://github.com/hocmemini/gemma-raid

## Other links (click "Add a link" for each)
1. https://github.com/hocmemini/gemma-raid/blob/main/docs/SUBMISSION.md  (write-up)
2. https://github.com/hocmemini/gemma-raid/blob/main/docs/RESEARCH.md    (research notes on the Gemma 4 ecosystem)

## Prize categories (tick both)
- Best Use of Gemma 4 (Deepmind Google)
- Best Open-Source AI Project (DigitalOcean)

## AI coding tools
Under "Coding agents and editors" tick: **Claude Code**.
Under "Models your coding tools ran on" tick: **Claude Fable 5.1** (Anthropic).
Do NOT tick "Hand-coded, no AI".

## Finish
Click **Save draft**, confirm all 7 items show as done, then click **Submit Project**. Report back the confirmation text.
