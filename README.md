# OnScreen-Agent — Rust Cloud (Android 15+)

Status: **v0.4.1 — runs on Android 15, CI green, signed release published.**
Honest state: the full loop exists (capture → VLM → act, mic → goal, spoken
status), the build pipeline is proven, and the app has been run on an Android 15
emulator — the accessibility dump, the Rust tree trim and the screenshot
capture all work, and idle PSS measures 68 MB. What is **not** proven is the
loop driving a live cloud model end to end, or behaviour on a physical phone.
Use it on a throwaway profile until then.

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

The Rust `.so` is produced by cargo-ndk and is **not** in git — build it before
assembling, or the APK ships without it and the app silently falls back to the
Kotlin path:

```bash
cd rust/agent-core
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
cargo ndk --target aarch64-linux-android --platform 35 -- build --release
mkdir -p ../../app/src/main/jniLibs/arm64-v8a
cp "$(find target -name libagent_core.so -path '*aarch64*' | head -1)" \
   ../../app/src/main/jniLibs/arm64-v8a/
```

> Building inside a OneDrive-synced folder fails: the sync client holds locks on
> Gradle's cache and the build dies with "Unable to delete directory
> .../app/build/kotlin/...". Clone somewhere local (e.g. `C:\repos\OnScreen-Agent`)
> and build there.

Push: contents of this folder are the repo root of
`github.com/beingDurgesh/OnScreen-Agent`.

## Signing
Release APKs are signed with a key that is **not** in git. CI pulls it from
repo secrets and `app/build.gradle.kts` picks it up from the environment:

| Env var | Repo secret |
|---|---|
| `ANDROID_KEYSTORE_PATH` | (path, set by the workflow after decoding) |
| `ANDROID_KEYSTORE_PASSWORD` | `ANDROID_KEYSTORE_PASSWORD` |
| `ANDROID_KEY_ALIAS` | `ANDROID_KEY_ALIAS` |
| `ANDROID_KEY_PASSWORD` | `ANDROID_KEY_PASSWORD` |
| — | `ANDROID_KEYSTORE_BASE64` (base64 of the `.jks`) |

Locally the release build falls back to the **debug** signing config when
`ANDROID_KEYSTORE_PATH` is unset, so `gradlew :app:assembleRelease` always
works for a fresh clone. Without the real key the APK is still installable —
but it is signed with a throwaway key, so it cannot upgrade over a release
build, and vice versa.

**Back up the keystore.** If it is lost, published builds can never be updated
in place; users would have to uninstall and reinstall.

## Releasing
Push a `v*` tag. `release-apk.yml` builds the signed **release** variant
(minified + resource-shrunk, ~2.3 MB), hashes it, and publishes it as a
GitHub Release:

```bash
git tag -a v0.4.0 -m "v0.4.0"
git push origin v0.4.0
```

A tag containing a hyphen (e.g. `v0.4.0-beta.1`) is published as a
pre-release instead of the "Latest" release. Stable download URL:

```
https://github.com/beingDurgesh/OnScreen-Agent/releases/latest/download/OnScreen-Agent-v0.4.0.apk
```

## Safety (same as upstream — do not skip)
- Play Protect will flag it (accessibility + screen control). Banking/wallet apps may refuse
  to run until uninstall.
- Cloud tokens bill to YOUR key. Step cap is 40. Test on a spare profile first.

## Verify
- `cargo test -p agent-core` (CI + local)
- `python tools/check.py` (no SDK needed — 30+ static assertions)
