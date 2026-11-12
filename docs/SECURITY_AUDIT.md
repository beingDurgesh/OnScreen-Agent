# OnScreen-Agent — Security and Privacy Audit

**Date:** 2026-11-12 · **Scope:** the whole shipped APK (release variant, arm64).

Threat model: the app holds three paid API keys, can read the screen of any
app the user opens, and can tap and type. A compromise of this app is a
compromise of the user's screen contents and their provider accounts.

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| SEC-01 | **High** | Model downloads were not integrity-checked. A corrupted or substituted Hugging Face file was renamed into place and treated as a valid model pack. | **Fixed** — SHA-256 + exact size verified against the upstream manifest before a file is accepted. |
| SEC-02 | **High** | The Gemini API key was placed in the URL query string (`?key=…`). URLs propagate into proxy logs, crash reports and `HttpLoggingInterceptor` output. | **Fixed** — moved to the `x-goog-api-key` header. |
| SEC-03 | **High** | `POST_NOTIFICATIONS` was never requested, so on Android 13+ the foreground notification could be suppressed. The agent could then run with **no visible indication and no reachable Stop button**. | **Fixed** — the permission is requested when Play is pressed. |
| SEC-04 | **High** | `MediaProjection.Callback` was not registered. On Android 14+ this means no frames are ever delivered, so the agent runs blind — and there is no signal to the user that it is blind. | **Fixed** — registered before `createVirtualDisplay`. |
| SEC-05 | **Medium** | The foreground service leaked: `done` / `needHelp` / missing-key all exited the loop without `stopSelf()`, leaving the service and its notification alive indefinitely. | **Fixed** — every exit path stops the service. |
| SEC-06 | **Medium** | `START_STICKY` allowed the system to resurrect the service with a null intent, starting an agent with an **empty goal**. | **Fixed** — `START_NOT_STICKY` plus an explicit null-intent refusal. |
| SEC-07 | **Medium** | The Rust core could panic and unwind across the JNI frame, aborting the whole app process. | **Fixed** — every JNI export is wrapped in `catch_unwind`. |
| SEC-08 | **Medium** | `MediaPlayer` was not released on the exception path, leaking native audio resources. | **Fixed** — released in a `finally`. |
| SEC-09 | **Medium** | Synthesised speech was left in `cacheDir/status.mp3` if the flow was interrupted between writing and deleting. | **Fixed** — deleted in a `finally`. |
| SEC-10 | **Low** | `canPerformGestures="true"` was declared on the accessibility service but never used — over-declared privilege. | **Fixed** — removed. |
| SEC-11 | **Low** | The accessibility service described itself to the user with the system string `"OK"`. | **Fixed** — a real description in `strings.xml`. |
| SEC-12 | **Low** | Provider HTTP calls could not be cancelled, so a Stop could leave ~90 s of in-flight network work running. | **Fixed** — the active `Call` is tracked and cancelled; a `callTimeout` bounds the whole request. |
| SEC-13 | **Info** | `androidx.security:security-crypto:1.1.0-alpha06` is an **alpha** in the key-storage path. | Open — see §5. |
| SEC-14 | **Info** | `ort 2.0.0-rc.15` is a **pre-release** crate (there is no stable 2.x). It is unused by the cloud build. | Open — see §5. |
| SEC-15 | **Info** | `kotlinx-serialization-json` is declared but never used. | Open — see §5. |
| SEC-16 | **Info** | No redaction of password-field text before it is sent to the cloud provider. `SPEC.md` asks for a redaction toggle. | Open — deliberately deferred, see §5. |
| SEC-17 | **Info** | No banking/wallet package blocklist. `SPEC.md` asks for one. | Open — see §5. |

## What is already correct

These were checked and need no change:

- **No hardcoded secrets.** No API key, token or password appears in the
  repository. The release keystore is not in git (`*.jks`, `*.keystore` are
  gitignored) and lives in repo secrets for CI.
- **API keys are encrypted at rest.** `EncryptedSharedPreferences` with
  AES256-SIV for key names and AES256-GCM for values, backed by an
  `AndroidKeyStore` master key.
- **Backups are disabled.** `android:allowBackup="false"`, so the encrypted
  preferences and the model cache cannot leave the device via `adb backup`.
- **`largeHeap=false`**, and the release build is minified and resource-shrunk.
- **No analytics or tracking.** The app has no crash reporter, no telemetry
  and no third-party SDK. The only network destinations are the AI providers
  the user explicitly configures, plus Hugging Face for model downloads.
- **No provider fallback.** If the selected provider fails, the app stops. It
  never re-routes screen content to another provider.
- **Rust is the validation boundary.** The model cannot bypass application-level
  validation: `parse_action` rejects anything outside the schema and the
  rejection is converted into a `needHelp`, not an action.
- **The native lib load failure is logged**, not swallowed. A missing `.so`
  degrades to the Kotlin fallback but says so in logcat.
- **Release builds are signed** with a key held in repo secrets, and
  `apksigner verify` is a hard gate in the release workflow — an unsigned or
  mis-signed APK cannot be published.
- **The release APK is not debuggable** (`android:debuggable` is absent from
  the release manifest).
- **Debug-only cleartext.** The release build has no network security config
  and keeps the platform default of HTTPS-only. The debug variant allows
  cleartext so a local OpenAI-compatible server can be used for development.

## Privacy notes for users

- **Screenshots leave the device.** A screenshot of the current screen is sent
  to whichever vision-language provider the user configured. The user should
  know this before granting screen capture.
- **API usage is billed to the user.** The step cap is 40 per run, but a long
  screenshot-heavy loop is not free.
- **Accessibility access is broad.** The service can read the window content of
  any app. It only acts while the agent is running.
- **Credential storage** is encrypted, but a rooted device can read the
  app's private storage.

## 5. Open items and why they are not fixed yet

| Item | Reason |
|---|---|
| SEC-13 `security-crypto` alpha | Replacing it means changing the key-storage mechanism, which risks losing users' stored keys. It is a dependency change and must not be bundled with correctness work. |
| SEC-14 `ort` pre-release | The crate is unused in the cloud build. It is only resolvable because it is pinned to an exact rc; there is no stable alternative. |
| SEC-15 unused `kotlinx-serialization-json` | Removing a dependency changes `Cargo.lock`/the Gradle graph and should be a separate, deliberate change. |
| SEC-16 password redaction | Needs a design decision: which fields count as passwords, and whether redaction is on or off by default. It changes what the model sees, so it needs its own test pass. |
| SEC-17 banking blocklist | Needs a maintained package list and a policy for what "block" means (warn vs refuse). |
| Backing up the release keystore | Operational, not code. The key is at `~/.android/onscreen-release.jks` and in repo secrets. **It must be backed up by the owner** — if it is lost, published APKs can never be updated in place. |

## Verification performed

| Check | Result |
|---|---|
| `apksigner verify --print-certs` on the published APK | verified, v2 scheme, release CN |
| `android:debuggable` in the release manifest | absent |
| `android:allowBackup` in the manifest | `false` |
| `android:extractNativeLibs` | `false` (libs loaded from the APK, not extracted to disk) |
| native lib permissions | read-only, stored uncompressed, SHA-256 pinned upstream |
| grep for hardcoded keys/tokens in the repository | none found |
| cleartext traffic permitted in the **release** build | no network security config → platform default (HTTPS only) |
