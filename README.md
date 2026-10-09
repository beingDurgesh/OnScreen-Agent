# On Screen — Rust Cloud (Android 15+)

Cloud BYOK build: user pastes their own API key and picks a model in each of the 3 slots
(STT / VLM brain / TTS). No backend. Keys stay encrypted on the phone.

Reference: https://github.com/Mayankpratapsingh022/On-Screen (site + binary releases only,
no app source — this is a clean-room rebuild of the described v1 behavior).

## Why Rust + Kotlin (not pure Rust)
Android 15 automation requires `AccessibilityService`, `MediaProjection`, Compose, and
runtime permissions — framework APIs only exposed to Kotlin/Java. Rust owns the agent hot
loop via JNI (`rust/agent-core`): strict action-schema parsing, AX-tree/RAM budgeting,
prompt building. Cloud APK defaults to Rust `default` features (no model weights).

## The 3 BYOK slots
| # | Module | Providers / models |
|---|--------|--------------------|
| 1 | STT | whisper.cpp on-device `tiny.en-q4` (offline default) · OpenAI `whisper-1` · Gemini `gemini-2.0-flash` |
| 2 | VLM brain | OpenAI `gpt-4o-mini/gpt-4o/gpt-4.1` · Gemini `gemini-2.5-flash/pro` · OpenRouter custom string |
| 3 | TTS | KittenTTS on-device (offline default) · OpenAI `tts-1/gpt-4o-mini-tts` · Gemini TTS |

Optional 4th field: Brave Search key (passthrough, same as upstream).

## RAM / size optimizations
- Rust `opt-level=z, lto, strip`, `SmallVec`, 8KB/120-node AX cap, 6-step history, 500-char goal cap.
- Screenshots 960px / JPEG-60, one frame per step, bitmaps recycled. `largeHeap=false`, arm64-only, `resConfigs en`, shrink+minify.
- Targets: APK <25MB, idle PSS <180MB. On-device Gemma 3n E2B/E4B + whisper base + KittenTTS are
  **feature-gated** (`ondevice-*`) and downloaded on demand — never bundled (would add GBs + heat,
  same failure upstream reported for "On Screen Offline").

## Getting the APK (I cannot emit a signed APK from this chat host)
This host has no Android SDK/NDK or Rust toolchain, so compilation happens in CI:
1. `git init && git add onscreen-agent && git commit -m "cloud" && gh repo create --public --source=. --push`
2. Open **Actions → build-apk** → download `on-screen-cloud-arm64-debug.apk` + `SHA256.txt`.
3. Local alternative with SDK 36 + NDK 27: `cd onscreen-agent && ./gradlew :app:assembleDebug`.

APK installs on Android 15+ (`minSdk 35` per your request). Want wider install base?
Set `minSdk 29` in `app/build.gradle.kts` + manifest — still targets 35, runs on 15 identically.
Strict 35 blocks Android ≤14 installs; say the word and I'll flip it.

## Safety (same as upstream — do not skip)
- Play Protect will flag it (accessibility + screen control). Banking/wallet apps may refuse
  to run until uninstall — Android security doing its job.
- Cloud tokens bill to YOUR key. Keep the provider usage page open; step cap is 40.
- Stop always wins. Test on a spare profile first.

## Verify
- `cargo test -p agent-core` (schema + budget unit tests)
- `python onscreen-agent/tools/check.py` (manifest + source sanity, runs without SDK)
