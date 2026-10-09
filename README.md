# OnScreen-Agent — Rust Cloud (Android 15+)

Status: **functional scaffold v0.3 — full agent loop written, never compiled.**
Honest state: all code paths exist (capture → VLM → act, mic → goal, spoken
status), but no build has ever run here. Expect 1–3 rounds of compile fixes
from CI logs after push. Do not distribute the APK until a device test passes.

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
CI (`build-apk.yml`): `cargo test` + `assembleDebug` → APK artifact + SHA-256.
Local (needs SDK 36 + NDK 27 + JDK 17 + Rust): `gradlew :app:assembleDebug`
(`gradlew` scripts + wrapper jar are committed; this host has no SDK so no
local build has ever run).

Push: contents of this folder are the repo root of
`github.com/beingDurgesh/OnScreen-Agent`.

## Safety (same as upstream — do not skip)
- Play Protect will flag it (accessibility + screen control). Banking/wallet apps may refuse
  to run until uninstall.
- Cloud tokens bill to YOUR key. Step cap is 40. Test on a spare profile first.

## Verify
- `cargo test -p agent-core` (CI)
- `python tools/check.py` (no SDK needed — 30+ static assertions)
