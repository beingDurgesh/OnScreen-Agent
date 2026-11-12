# OnScreen-Agent — Open-Source AI Agent for Android Screen Automation

**Automate your Android phone with natural language.** OnScreen-Agent is an
open-source Android AI agent that looks at your screen, decides what to do next
with a multimodal AI model, and taps, types and scrolls through Android apps
using the AccessibilityService API. Bring your own API key — no backend, no
account, no server.

<p align="center">
  <a href="#download"><img alt="Download OnScreen-Agent APK for Android" src="https://img.shields.io/badge/download-APK-8B5CF6?style=for-the-badge&logo=android"></a>
  <a href="https://github.com/beingDurgesh/OnScreen-Agent/releases/latest"><img alt="Latest release of OnScreen-Agent" src="https://img.shields.io/github/v/release/beingDurgesh/OnScreen-Agent?label=release&style=for-the-badge"></a>
  <a href="https://github.com/beingDurgesh/OnScreen-Agent/actions/workflows/build-apk.yml"><img alt="OnScreen-Agent CI build status" src="https://github.com/beingDurgesh/OnScreen-Agent/actions/workflows/build-apk.yml/badge.svg"></a>
  <img alt="OnScreen-Agent license: MIT" src="https://img.shields.io/badge/license-MIT-8B5CF6?style=for-the-badge">
  <img alt="OnScreen-Agent requires Android 15 or newer" src="https://img.shields.io/badge/Android-15%2B-3DDC84?style=for-the-badge">
</p>

<p align="center">
  <a href="#what-is-onscreen-agent">What it is</a> •
  <a href="#features">Features</a> •
  <a href="#how-does-it-work">How it works</a> •
  <a href="#download">Download</a> •
  <a href="#build-from-source">Build from source</a> •
  <a href="#faq">FAQ</a>
</p>

---

## What is OnScreen-Agent?

OnScreen-Agent is an experimental, open-source **Android AI agent** for
**screen automation** and **app automation** driven by natural-language
instructions. You type a goal — *"turn on Dark theme in Settings"* — and the
agent captures the screen, reads the accessibility hierarchy, asks a
vision-language model what to do, and performs the action through Android's
accessibility APIs. It then re-observes the screen and repeats until the task
is done or it needs you.

Unlike rule-based Android automation (Appium, UIAutomator, Espresso scripts),
OnScreen-Agent does not follow a fixed script. It **understands whatever is on
screen right now** and picks the next action, which means it can work against
apps it has never seen.

It is built as a lightweight Kotlin app with a Rust agent core, and it is
**bring your own key (BYOK)**: you paste your own OpenAI, Google Gemini or
OpenRouter API key into the app, and your key never leaves your phone. There is
no developer-hosted inference backend.

> **Project status:** experimental. The build pipeline is proven and the agent
> loop runs on an Android 15 emulator, but the loop has **not** yet driven a
> live cloud model end to end. Read [Project status](#project-status) before
> you trust it with anything important.

## Features

- **AI-powered Android automation** — turn natural-language instructions into
  real UI taps, text entry, scrolling and navigation.
- **Screen understanding** — combines a downscaled screenshot with the
  accessibility node hierarchy so the model sees both pixels and structure.
- **Multimodal AI integration** — works with OpenAI (`gpt-4o-mini`, `gpt-4o`,
  `gpt-4.1`), Google Gemini (`gemini-2.5-flash`, `gemini-2.5-pro`) and any
  OpenRouter model.
- **Bring Your Own Key (BYOK)** — configure your own credentials; requests go
  straight from your phone to the provider. Keys are encrypted at rest.
- **Voice interaction** — speak your goal via speech-to-text, hear spoken status
  back via text-to-speech.
- **Rust agent core** — strict action-schema parsing, accessibility-tree and
  RAM budgeting, and prompt building in Rust, called from Kotlin over JNI.
- **RAM-conscious by design** — 8 KB accessibility-tree cap, 120-node cap,
  6-step history, one frame per step, 960 px screenshots. Idle PSS measures
  **68 MB**; the release APK is **2.8 MB**.
- **On-device AI roadmap** — download and run Whisper, Gemma 3n and KittenTTS
  locally so the app can work without the cloud.

## How does it work?

The agent runs a perception-to-action loop:

1. **Instruction** — you give a goal in text or by voice.
2. **Perceive** — `AgentService` captures the screen with `MediaProjection`
   (JPEG, long edge 960 px, quality 60) and dumps the accessibility tree with
   stable node ids via `AccessibilityService`.
3. **Reason** — the screenshot and the trimmed tree go to your configured
   vision-language model with a strict action schema.
4. **Validate** — Rust `parseAction` rejects anything that does not match the
   schema (unknown action types, oversized input text, malformed package
   names), so a confused model cannot inject arbitrary actions.
5. **Act** — the validated action executes against **the same dump the model
   saw**, so node ids stay valid between perception and action.
6. **Repeat** — the screen is re-observed and the loop continues, up to a hard
   cap of 40 steps. `done`, `needHelp` or the step limit stops the agent, and
   **Stop always wins** — you can halt it at any time.

Supported actions: `click`, `longClick`, `input` (type text), `scroll`,
`back`, `home`, `openApp`, `wait`, `done`, `needHelp`.

## Tech stack

| Technology | Purpose |
|---|---|
| Kotlin | Android application and framework APIs |
| Rust | Agent core: action parsing, AX-tree/RAM budgeting, prompt building |
| JNI | Kotlin ↔ Rust bridge (`rust/agent-core`) |
| Jetpack Compose | Android UI |
| AccessibilityService | Screen interaction and node extraction |
| MediaProjection | Screen capture |
| Gradle + Cargo | Build system for the app and the native library |
| OpenAI API | Cloud AI, speech-to-text and text-to-speech |
| Google Gemini API | Multimodal AI and speech integrations |
| OpenRouter | Custom vision-language model routing |
| Whisper.cpp | On-device speech-to-text (integration) |
| Gemma 3n | On-device vision-language inference (roadmap) |
| KittenTTS | On-device speech synthesis (integration) |

### Why Rust + Kotlin (not pure Rust)

Android 15 automation requires `AccessibilityService`, `MediaProjection`,
Jetpack Compose and runtime permissions — framework APIs that are only exposed
to Kotlin/Java. So Rust cannot own the whole app. Rust owns the agent hot loop
via JNI instead: strict action-schema parsing, accessibility-tree and RAM
budgeting, and prompt building. The cloud APK is built with Rust `default`
features, so **no model weights are bundled**.

## Cloud AI and bring your own key

OnScreen-Agent has **no developer-hosted backend**. The app talks directly to
your chosen AI provider from the phone, and you are billed by that provider.

You configure three slots — pick a provider and a model in each:

| # | Module | Providers / models |
|---|--------|--------------------|
| 1 | Speech-to-text | whisper.cpp on-device (flagged, not bundled) · OpenAI `whisper-1` · Gemini `gemini-2.0-flash` |
| 2 | VLM brain | OpenAI `gpt-4o-mini` / `gpt-4o` / `gpt-4.1` · Gemini `gemini-2.5-flash` / `gemini-2.5-pro` · OpenRouter custom string |
| 3 | Text-to-speech | KittenTTS on-device (flagged, not bundled) · OpenAI `tts-1` · Gemini TTS (stub, speech-config flow TODO) |

There is an optional fourth field for a Brave Search key (passthrough).

### On-device models (roadmap)

The app can already **download** on-device model packs into
`filesDir/models/<id>/` with progress, cancel and delete. Local inference is
the next build:

- Whisper `tiny.en-q5_1` (32 MB) / `base.en-q5_1` (60 MB)
- Gemma 3n E2B-Q8_0 (4.8 GB) / E4B-Q8_0 (7.4 GB) — both gated: approve the
  model on Hugging Face and save a token
- KittenTTS nano pack (24 MB)

Official `ggml-org` Q4 builds do not exist for Gemma 3n, so Q8_0 is offered
with a RAM warning. Until inference ships, these are `cfg-gated` placeholders
(`transcribe_ondevice` and friends return errors).

## Android compatibility

- **Minimum Android version:** Android 15 (API 35)
- **Architecture:** ARM64 (`arm64-v8a`)
- **Development toolchain:** Android SDK 36, NDK 27, JDK 17, Rust, cargo-ndk
- **Build system:** Gradle (app) + Cargo (native agent core)

## Project status

OnScreen-Agent is actively evolving. Here is what is actually proven.

**Implemented and tested:**

- Android application build pipeline (CI is green; signed release APK published)
- Accessibility hierarchy extraction on an Android 15 emulator
- Screenshot capture (a real 43,936-byte JPEG frame from a live MediaProjection)
- Rust accessibility-tree processing (verified on-device: the trimmed tree
  comes back in Rust's `id|class|text` format)
- Strict structured action parsing, covered by unit tests
- Provider configuration architecture (BYOK)

**Still to do or needing further validation:**

- End-to-end live cloud AI automation on a physical Android device
- Reliability testing across different Android applications
- On-device inference for the downloaded Whisper, Gemma 3n and KittenTTS packs
- Task verification and error recovery improvements

This is an experimental project, not a production-ready autonomous assistant.

## Safety and privacy

OnScreen-Agent uses `AccessibilityService` and `MediaProjection` to read and
control the screen. Please consider:

- **Screen content may be sent to your AI provider.** A screenshot of your
  screen goes to whichever model you configured.
- **Your API usage is billed to you**, by the provider whose key you entered.
- **API credentials are stored encrypted on the device** using
  `EncryptedSharedPreferences`.
- **Android security restrictions may block automation** in certain apps.
  Banking and wallet apps often refuse to run while an accessibility service
  is enabled.
- **Play Protect will flag this app.** It is not distributed through Google
  Play, and the accessibility + screen-control permissions look alarming to
  antivirus scanners. That warning is expected.
- **Use a test device or a secondary user profile** while evaluating the
  project. The agent step cap is 40 per run, but it can still tap things.

## Download

The latest signed release APK:

**→ [beingDurgesh/OnScreen-Agent releases](https://github.com/beingDurgesh/OnScreen-Agent/releases/latest)**

Direct link for the current version (2.8 MB, ARM64, Android 15+):

```
https://github.com/beingDurgesh/OnScreen-Agent/releases/latest/download/OnScreen-Agent-v0.4.1.apk
```

A `SHA256.txt` is published next to every APK — check it if you want to verify
your download.

**Installing**

1. Download the APK.
2. On your phone: Settings → Apps → Special app access → **Install unknown
   apps** → allow the browser or file manager you used.
3. Install the APK and open **On Screen (Rust Cloud)**.
4. Grant the permissions it asks for:
   - **Accessibility** (Settings → Accessibility → On Screen) — required to tap
     things.
   - **Screen capture** — required to see the screen.
   - **Microphone** — only if you use voice goals.
5. Paste your API key, pick a model in each of the three slots, and press
   **Play**.

On Android 15 the capture consent has a **mode** dropdown that defaults to
*"A single app"*. Change it to **"Entire screen"** — the agent has to see
whatever app it is driving, and an app-scoped projection only mirrors one
app's window. Android also revokes an app-scoped projection of the agent's
own window, which leaves the agent unable to capture anything.

## Build from source

**Requirements:** Android SDK 36 · Android NDK 27 · JDK 17 · Rust toolchain ·
cargo-ndk

```bash
git clone https://github.com/beingDurgesh/OnScreen-Agent.git
cd OnScreen-Agent
```

Build the Rust native library first — it is **not** committed to the repo, and
without it the app silently falls back to a Kotlin implementation:

```bash
cd rust/agent-core
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
cargo ndk --target aarch64-linux-android --platform 35 -- build --release
mkdir -p ../../app/src/main/jniLibs/arm64-v8a
cp "$(find target -name libagent_core.so -path '*aarch64*' | head -1)" \
   ../../app/src/main/jniLibs/arm64-v8a/
cd ../..
```

Then assemble the APK:

```bash
./gradlew :app:assembleDebug
```

> **Note:** building inside a OneDrive-synced folder fails — the sync client
> holds locks on Gradle's cache and the build dies with `Unable to delete
> directory .../app/build/kotlin/...`. Clone to a local path and build there.

CI (`.github/workflows/build-apk.yml`) does the same thing: `cargo test` →
cargo-ndk arm64 `.so` → `assembleDebug` → APK artifact + SHA-256.

### Verifying

```bash
cargo test --manifest-path rust/agent-core/Cargo.toml   # Rust agent-core unit tests
python tools/check.py                                   # 55 static checks, no SDK needed
```

## FAQ

### What is an Android AI agent?

Software that interprets plain-language instructions, understands the current
Android interface, and performs actions through supported system APIs.
OnScreen-Agent explores that approach using multimodal AI and
accessibility-driven automation instead of fixed scripts.

### Can OnScreen-Agent automate Android apps?

It is designed to interact with Android apps through accessibility-based
actions — clicking, typing, scrolling and navigating. Compatibility and
reliability still need testing app by app, and the loop has not yet been driven
by a live cloud model.

### Does OnScreen-Agent require an API key?

Yes, for the cloud configuration. It follows a bring-your-own-key model: you
supply credentials from a supported provider (OpenAI, Gemini, OpenRouter) and
pay your own usage costs. There is no hosted backend and no account to create.

### Which AI providers and models are supported?

OpenAI (`gpt-4o-mini`, `gpt-4o`, `gpt-4.1`) for the vision-language brain,
Google Gemini (`gemini-2.5-flash`, `gemini-2.5-pro`) for the same slot,
OpenRouter for any custom model string, plus OpenAI `whisper-1` and Gemini for
speech-to-text, and OpenAI `tts-1` for text-to-speech.

### Does it work completely offline?

Not yet. The current cloud configuration relies on external AI providers.
On-device model downloads (Whisper, Gemma 3n, KittenTTS) are implemented, and
local inference is in progress.

### What Android versions are supported?

Android 15 (API 35) and newer, ARM64 devices only.

### Is it safe to give an AI agent Accessibility access?

Treat it as you would any automation tool with screen control: use a test
device or a secondary profile, keep an eye on the step count, and stop it when
it misbehaves. The app never leaves your device except to call the AI provider
you chose.

### Is OnScreen-Agent open source?

Yes. The full source, build scripts and development documentation are in this
public repository under the MIT license.

### How do I build it from source?

See [Build from source](#build-from-source) — you need the Android SDK 36,
NDK 27, JDK 17, Rust and cargo-ndk, and you must build the Rust native library
before assembling the APK.

## Contributing

Contributions, bug reports, suggestions and technical discussions are welcome.
Open an issue to discuss a change before opening a pull request, and please
include what you tested on (emulator or device, Android version, ABI).

If you are reporting a bug in the agent loop, the `AgentService` and
`ScreenCapture` log lines are the most useful thing to paste — they record the
accessibility node count, the trimmed tree size and the captured JPEG size for
every step.

## License

MIT — see [LICENSE](LICENSE).

## Links

- **Source code:** https://github.com/beingDurgesh/OnScreen-Agent
- **Issues:** https://github.com/beingDurgesh/OnScreen-Agent/issues
- **Releases:** https://github.com/beingDurgesh/OnScreen-Agent/releases
- **Project docs:** [SPEC.md](SPEC.md) · [PLAN.md](PLAN.md) ·
  [PROGRESS.md](PROGRESS.md)

Reference project: https://github.com/Mayankpratapsingh022/On-Screen (site and
binary releases only, no app source — OnScreen-Agent is a clean-room rebuild
of that v1 behaviour).

---

**OnScreen-Agent — exploring AI-powered automation for Android.**

Built by [beingDurgesh](https://github.com/beingDurgesh).

---

<details>
<summary><b>Maintainer notes — signing and releasing</b></summary>

### Signing

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

### Releasing

Push a `v*` tag. `release-apk.yml` builds the signed **release** variant
(minified + resource-shrunk, ~2.8 MB), verifies it with `apksigner`, hashes
it, and publishes it as a GitHub Release:

```bash
git tag -a v0.4.1 -m "v0.4.1"
git push origin v0.4.1
```

A tag containing a hyphen (e.g. `v0.4.1-beta.1`) is published as a
pre-release instead of the "Latest" release. The release asset is named
`OnScreen-Agent-<tag>.apk`, so the stable download URL for the current
version is:

```
https://github.com/beingDurgesh/OnScreen-Agent/releases/latest/download/OnScreen-Agent-v0.4.1.apk
```

### RAM / size profile

- Rust `opt-level=z, lto, strip`, `SmallVec`, 8 KB/120-node AX cap, 6-step
  history, 500-char goal cap.
- One frame per step; bitmaps and surfaces are closed in-function.
  `largeHeap=false`, ARM64-only, `resConfigs en`, shrink + minify.
- Targets: APK < 25 MB, idle PSS < 180 MB. Measured: APK 2.8 MB (release),
  idle PSS 68 MB.

</details>
