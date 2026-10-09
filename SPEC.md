# On-Screen Rust Cloud — SPEC (DEFINE → PLAN)

> Clean-room reimplementation inspired by https://github.com/Mayankpratapsingh022/On-Screen
> The reference repo contains **only a static site + binary APK release** (`index.html`, `styles.css`, releases `v0.1.0`/`v0.1.1`). No app source is published. So this project does not copy code — it re-specifies observed behavior and builds it fresh.

## 1. Idea (DEFINE)
Autonomous on-screen Android agent, Android 15+ (API 35+), low-RAM, BYOK cloud version first, on-device path optional.

Observed v1 behavior to preserve:
- Bring-your-own API key (OpenAI + optional Brave Search), press play to launch agent
- Reads visible UI via Accessibility, reasons about next action, taps/types/scrolls in native apps
- No developer backend, memory stays on phone, provider receives what phone sends
- Risks: Play Protect flags, banking/wallet apps block accessibility tools, high token use, hallucinations

## 2. Requirements (PRD)
### Must
- `minSdk 35` (Android 15), `targetSdk 35`, `compileSdk 36`. Edge-to-edge, POST_NOTIFICATIONS runtime permission.
- Kotlin shell (framework-mandated) + Rust core via JNI. Pure-Rust APK is not viable for `AccessibilityService` / `MediaProjection` / Compose.
- Cloud-first: 3 BYOK modules, each with provider + model picker + key field:
  1. **STT**: `whisper.cpp on-device (tiny.en Q4, default offline)` | `OpenAI whisper-1` | `Gemini gemini-2.0-flash`
  2. **VLM / agent brain**: `OpenAI gpt-4o-mini / gpt-4o / gpt-4.1` | `Gemini gemini-2.5-flash / gemini-2.5-pro` | `OpenRouter custom string`
  3. **TTS**: `KittenTTS on-device (default offline)` | `OpenAI tts-1 / gpt-4o-mini-tts` | `Gemini TTS`
- Keys stored on-device only (EncryptedSharedPreferences / DataStore). No backend. Direct HTTPS from phone.
- Agent loop: `perceive (AX tree + screenshot ref) → plan (VLM JSON) → guard → act (Accessibility) → verify`.
- Action schema (strict JSON, parsed in Rust): `click{id} | longClick{id} | input{id,text} | scroll{dir} | back | home | openApp{pkg} | wait{ms} | done | needHelp`.
- Safety: foreground persistent notification, Stop button, default guardrails OFF toggle (matches v0.1.1), redaction toggle for password fields, blocklist for banking packages (warn, don't auto-bypass).
- RAM optimized: streaming screenshots (720p max, JPEG 60), AX tree cap 120 nodes / 8KB, history cap 6 steps, Rust `opt-level=z, lto, strip`, no model weights bundled.

### On-device (feature-gated, NOT in cloud APK)
- `whisper.cpp` via `whisper-rs` (`ondevice-stt`), Gemma 3n E2B/E4B Q4 via llama.cpp/MediaPipe (`ondevice-vlm`), KittenTTS ONNX via `ort` (`ondevice-tts`). Downloaded on demand to `app_files/models/`, mmap + Q4, single-thread defaults. Expected 1.5–3GB peak — excluded from cloud build for RAM/APK size.

### Non-goals for this drop
- Bundling Gemma weights in APK, root, bypassing banking `FLAG_SECURE`, Play Store release.

## 3. Acceptance
- `./gradlew :app:assembleDebug` succeeds on CI (Android SDK 36, NDK 27).
- Settings screen saves 3 keys + 3 model selections, agent runs 1 E2E loop without crash on Android 15 emulator.
- APK < 25MB (cloud, arm64), PSS < 180MB idle.
