# Gemma 4 hack day briefing — 2026-10-16 (MLH Hacktoberfest Hack Day Draper x AMH)

Categories: **Best Use of Gemma 4** (Google DeepMind) and **Best Open-Source AI Project** (DigitalOcean).
MLH judges on four equal criteria: Technology, Design, Completion, Learning. Not on pitch.

## 1. Gemma 4 family (released Apr 2, 2026; Apache 2.0 — first Gemma under a real OSS license)

| Model | Effective / total params | Modalities in | Context | INT4 size | Where it runs |
|---|---|---|---|---|---|
| E2B | 2.3B / 5.1B (per-layer embeddings) | text, image, video, audio | 128K | 2.9 GB | phones (6 GB+ RAM), laptops |
| E4B | 4.5B / 8B | text, image, video, audio | 128K | 4.5 GB | phones (flagship), laptops |
| 12B Unified | 12B dense, encoder-free | text, image, video, audio | 256K | 6.7 GB | 16 GB laptop, any GPU |
| 26B A4B | 25B MoE, 3.8B active | text, image, video | 256K | 14.4 GB | Gemini API, 32 GB machine, GPU |
| 31B | 31B dense | text, image, video | 256K | 17.5 GB | Gemini API, GPU |

New vs Gemma 3/3n: thinking mode (toggle), native function calling with dedicated tokens, native system role,
256K ctx, MoE variant, MTP drafter models for speculative decoding, official QAT checkpoints. Output is text only.
Video = frame sequences up to 60 s; audio up to 30 s (E2B/E4B/12B only). Tech report: arxiv.org/abs/2607.02770

### Access
- **Gemini API (free tier only, no paid tier):** `gemma-4-31b-it`, `gemma-4-26b-a4b-it`. Supports images, thinking, function calling,
  system instructions. Free-tier limits not published (community reports ~15 RPM / 1,500 RPD, unverified).
  Key: aistudio.google.com/apikey. Docs: ai.google.dev/gemma/docs/core/gemma_on_gemini_api
- **Ollama:** `gemma4:e2b` (5.1 GB), `gemma4:e4b` (8 GB), `gemma4:12b`, `gemma4:26b`, `gemma4:31b`, `gemma4:cloud`.
  All accept images; tools + thinking supported. Needs a 2026 Ollama (this laptop has 0.11.6 — too old).
- **llama.cpp:** `llama-server -hf ggml-org/gemma-4-E2B-it-GGUF` (OpenAI-compatible on :8080/v1, mmproj auto-downloads).
- **LM Studio:** all sizes, GGUF + MLX. **Hugging Face:** `google/gemma-4-E2B-it` … `google/gemma-4-31B-it`, QAT collections.
- **LiteRT-LM (phones):** `litert-community/gemma-4-E2B-it-litert-lm` (2.58 GB), E4B (3.65 GB).
- **DigitalOcean Gradient serverless:** `gemma-4-31B-it`, OpenAI-compatible, $0.18 in / $0.50 out per M tokens, 256K ctx.
- **Vertex / Cloud Run / Docker Hub `ai/gemma4`** for self-hosting.

### Specialty models
- **EmbeddingGemma 2** (Oct 2026, 740M, text/code/image/audio/video → 768-d): `google/embeddinggemma-2`, `ollama pull embeddinggemma-2:740m`
- **FunctionGemma** (270M function calling, Gemma 3 base): `google/functiongemma-270m-it`, `ollama run functiongemma`; mobile-actions fine-tune in litert-community
- **DiffusionGemma** (26B A4B diffusion text gen, >1100 tok/s on H100): `google/diffusiongemma-26B-A4B-it`
- **TranslateGemma** 4B/12B/27B (55 langs): `ollama run translategemma`
- **MedGemma 1.5 4B / 27B**: github.com/Google-Health/medgemma
- **ShieldGemma** text 2B/9B/27B; **ShieldGemma 2** image safety 4B: `google/shieldgemma-2-4b-it` (no Gemma 4 version yet)
- **VaultGemma** 1B (differential privacy), **T5Gemma 2** enc-dec 270M–4B multimodal, **Gemma Scope 2** SAEs for Gemma 3
- Legacy: PaliGemma 2, CodeGemma, DataGemma. SignGemma / DolphinGemma: no public weights.

### Docs
- Hub: ai.google.dev/gemma/docs (moving to gemma.ai). Function calling: /gemma/docs/capabilities/text/function-calling-gemma4
- Thinking: /gemma/docs/capabilities/thinking · Image: /gemma/docs/capabilities/vision/image · Audio: /gemma/docs/capabilities/audio
- Cookbook: github.com/google-gemma/cookbook · awesome list: github.com/google-gemma/awesome-gemma · HF launch post: huggingface.co/blog/gemma4
- Fine-tune: unsloth.ai/docs/models/gemma-4/train (free Colabs for E4B vision + audio)
- ADK agents: adk.dev/agents/models/google-gemma/

## 2. On-device (Android) stack

| Layer | Status | Gemma 4 | Image | Audio | Tools |
|---|---|---|---|---|---|
| LiteRT-LM (Kotlin) | primary, stable | E2B/E4B | yes | yes | yes (constrained decoding) |
| MediaPipe LLM Inference | maintenance only | partial | yes | yes | legacy FC SDK |
| AI Edge Gallery app | Play Store / APK | yes | camera | yes | FunctionGemma demos |
| ML Kit GenAI / AICore | beta, Gemini Nano; Gemma 4 dev preview | preview | yes | preview | no |

- Gradle: `implementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")`
- Docs: developers.google.com/edge/litert-lm/android · github.com/google-ai-edge/LiteRT-LM
- Best fork target: github.com/google-ai-edge/gallery (Kotlin/Compose, camera + audio + tools wired).
  Also github.com/google-ai-edge/litert-samples (voice_assistant sample: ASR → LLM tools → TTS).
- Perf, Gemma 4 E2B on Galaxy S26 Ultra GPU: 3,808 tok/s prefill, 52 tok/s decode, 0.3 s TTFT, 676 MB peak RAM. CPU: 557 / 47 / 1.8 s.
  Google: runs on any Android with 6 GB+ RAM. Mid-range phones: expect roughly half.
- Zero-code demo: install AI Edge Gallery, "Ask Image" mode uses the live camera with Gemma 4 E2B. Sideload any .litertlm via adb.
- Cross-platform (community, unverified quality): `react-native-litert-lm`, `expo-litert-lm`, Flutter `flutter_edge_ai`.
- Web/WASM: transformers.js v4 `onnx-community/gemma-4-E2B-it-ONNX` (q4f16, image + audio, WebGPU) — desktop Chrome only verified;
  a 2–3 GB model in a phone browser tab is memory-risky. MediaPipe web Gemma 4 build is text-only.

## 3. DigitalOcean
- $200 MLH credit: mlh.link/digitalocean-signup (GitHub Student Pack no longer includes DO).
- GPU Droplets: RTX 4000 Ada $0.76/hr, L40S $1.57/hr, H100 $4.41/hr, per-second billing. Marketplace 1-click "Ollama with Open WebUI".
- Gradient serverless inference: `gemma-4-31B-it` via OpenAI-compatible Chat Completions. `gradient-adk` CLI was deprecated Oct 1, 2026 — use plain OpenAI client.
- Open-source category rules: open-weight AI must be core; public GitHub repo + OSS license; agent skills must follow agentskills.io/specification
  (SKILL.md with `name` [a-z0-9-] matching dir name, `description` ≤1024 chars, optional `license`, body <500 lines; validate with `skills-ref validate`).

## 4. What has already been built (avoid these archetypes)
- Accessibility assistants for blind / speech-impaired / cognitive / sign language (~10 past winners: Gemma Vision, Vite Vere, 3VA, AccessLens, KawanIsyarat…)
- Offline AI-in-a-box for rural / disaster / school / clinic (LENTERA, LIKAS, Acuífero·Vigía, PreVillage, PathOS)
- Medical screening / triage (skin, TB, pathology, dementia, mental health)
- Tutors / study-notes / essay feedback (CodeBuddy, EduAI, StudyMate, Pīzhù)
- "Read my relative's documents" and scam detectors (Gemma 4 mail reader won Hacktoberfest Build-for-a-Friend)
- Games are nearly absent. Closest: Outdraw AI (draw-vs-AI party game, Gemini 2024), PenApple (roguelike deckbuilder).

## 5. This laptop
- 7.7 GB RAM total, Intel iGPU, WSL2 capped at 6 GB + 12 GB swap. Local Gemma 4 ceiling: E2B via Ollama (5.1 GB) — tight and slow.
- No Android SDK / Java / Gradle installed. Ollama 0.11.6 (Aug 2025) must be updated before `gemma4:*` tags work.
- Conclusion: brain runs on the Gemini API (free) or a DO GPU droplet; a modern Android phone is a better local inference box than this laptop.
