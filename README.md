# OnScreen-Agent — Rust Cloud (Android 15+)

Status: **v0.4 — compiles, CI green, APK builds. Never run on a device.**
Honest state: the full loop exists (capture → VLM → act, mic → goal, spoken
status) and the build pipeline is proven — CI produces a signed-debug APK with
the Rust `.so` inside. What is *not* proven is runtime behaviour on a real
Android 15 phone. Do not distribute the APK until a device test passes.

Cloud BYOK: user pastes their own API key and picks a model in each of the 3
slots (STT / VLM brain / TTS). No backend. Keys stay encrypted on the phone.

Reference: https://github.com/Mayankpratapsingh022/On-Screen (site + binary
releases only, no app source — this is a clean-room rebuild of v1 behavior).

## Why Rust + Kotlin (not pure Rust)
Android 15 automation requires `AccessibilityService`, `MediaProjection`,
Compose, and runtime permissions — framework APIs only exposed to Kotlin/Java.
Rust owns the agent hot loop via JNI (`rust/agent-core`): strict action-schema
parsing, AX-tree/RAM budgeting, prompt building. Cloud APK uses Rust `default`
features (no model weights).

## The 3 BYOK slots
| # | Module | Providers / models |
|---|--------|--------------------|
| 1 | STT | whisper.cpp on-device (flagged, not bundled) · OpenAI `whisper-1` · Gemini `gemini-2.0-flash` |
| 2 | VLM brain | OpenAI `gpt-4o-mini/gpt-4o/gpt-4.1` · Gemini `gemini-2.5-flash/pro` · OpenRouter custom string |
| 3 | TTS | KittenTTS on-device (flagged, not bundled) · OpenAI `tts-1` · Gemini TTS (stub, speech-config flow TODO) |

Optional 4th field: Brave Search key (passthrough, same as upstream).

On-device downloads (section 4 in the app): Whisper `tiny.en-q5_1` (32MB) /
`base.en-q5_1` (60MB), Gemma 3n E2B-Q8_0 (4.8GB) / E4B-Q8_0 (7.4GB, both gated —
approve on Hugging Face + save a token), KittenTTS nano pack (24MB). Official
ggml-org Q4 does not exist, so Q8_0 is offered with a RAM warning. Inference on
downloaded files is the next build; downloads land in `filesDir/models/<id>/`.

## How the loop works
1. Play → screen-capture permission → `AgentService` starts with goal + projection.
2. Each step: `dumpIndexed()` (stable ids) + `captureJpeg()` (960px q60) →
   `nextActionMultimodal()` → Rust `parseAction` → `actOnDump()` on the same dump.
3. `done` / `needHelp` / step-limit → spoken status via saved TTS slot. Stop always wins.

## RAM / size optimizations
- Rust `opt-level=z, lto, strip`, `SmallVec`, 8KB/120-node AX cap, 6-step history, 500-char goal cap.
- One frame per step, bitmaps/surfaces closed in-function. `largeHeap=false`, arm64-only, `resConfigs en`, shrink+minify.
- Targets: APK <25MB, idle PSS <180MB. On-device Gemma 3n + whisper + KittenTTS are
  **cfg-gated placeholders** (`transcribe_ondevice` etc. return errors) — weights
  download/mmap/quant code is still TODO.

## Build
CI (`build-apk.yml`): `cargo test` → cargo-ndk arm64 `.so` → `assembleDebug` →
APK artifact + SHA-256. Latest green run: [43144650617](https://github.com/beingDurgesh/OnScreen-Agent/actions/runs/43144650617).
Local (needs SDK 36 + NDK 27 + JDK 17 + Rust): `gradlew :app:assembleDebug`
(`gradlew` scripts + wrapper jar are committed). A local build produces
`app-debug.apk` at ~7.9 MB.

> Building inside a OneDrive-synced folder fails: the sync client holds locks on
> Gradle's cache and the build dies with "Unable to delete directory
> .../app/build/kotlin/...". Clone somewhere local (e.g. `C:\repos\OnScreen-Agent`)
> and build there.

Push: contents of this folder are the repo root of
`github.com/beingDurgesh/OnScreen-Agent`.

## Safety (same as upstream — do not skip)
- Play Protect will flag it (accessibility + screen control). Banking/wallet apps may refuse
  to run until uninstall.
- Cloud tokens bill to YOUR key. Step cap is 40. Test on a spare profile first.

## Verify
- `cargo test -p agent-core` (CI + local)
- `python tools/check.py` (no SDK needed — 30+ static assertions)
