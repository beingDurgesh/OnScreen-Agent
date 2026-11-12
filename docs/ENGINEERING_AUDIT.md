# OnScreen-Agent — Engineering Audit

**Date:** 2026-11-12
**Auditor:** automated review at commit `7bced7e` (branch `feature/engineering-hardening`)
**Scope:** whole repository — 8 Kotlin sources, 1 Rust crate, 2 CI workflows, 1 static-check script.
**Method:** full read of every source file, plus a build, a 2-test Rust run, a 55-assertion static check, and an Android 15 emulator run.

> Nothing in this document is inferred from the older `README.md` / `PLAN.md` / `SPEC.md`
> prose. Where those documents disagree with the code, **the code is treated as the source
> of truth** and the drift is reported in §9.

---

## 1. Current architecture

```
┌─ Kotlin shell (Android framework) ─────────────────────────┐
│ MainActivity.kt (185)   Compose settings UI, 3 BYOK cards   │
│ AgentService.kt (146)   foreground service, agent loop      │
│ AgentAccessibilityService.kt (71)  AX dump + act             │
│ ScreenCapture.kt (135)  MediaProjection → JPEG              │
│ CloudClients.kt (163)   OkHttp, OpenAI/Gemini/OpenRouter    │
│ SettingsStore.kt (54)   DataStore + EncryptedSharedPrefs    │
│ ModelManager.kt (192)   on-device model downloads           │
│ ModelsSection.kt (92)   download UI                         │
│ RustBridge.kt (25)      JNI facade                          │
└─────────────────────────────────────────────────────────────┘
                            │ JNI
                            ▼
┌─ Rust core (rust/agent-core, 135 LOC) ─────────────────────┐
│ parse_action()  strict action schema + validation           │
│ trim_tree()     AX tree → budgeted text                      │
│ build_prompt()  ← DEAD CODE, never called from Kotlin       │
│ ondevice-*      cfg-gated placeholders that return errors    │
└─────────────────────────────────────────────────────────────┘
```

**Measured sizes:** Rust core 135 LOC / 2 unit tests. Kotlin 1,017 LOC across 9 files /
**0 unit tests**. Total shipped APK (release, signed) 2.76 MB; arm64-v8a only.

---

## 2. Implemented and verified

These were confirmed by running the app on an Android 15 emulator, not by reading docs.

| Capability | Evidence |
|---|---|
| App installs, launches, UI renders | `app-debug.apk` installed on API 35; Compose tree renders |
| Rust `.so` loads on device | `RustBridge: agent_core loaded — Rust core is live` |
| Rust processes the live AX tree | `tree=1740B/43L head='0\|droid.widget.F'` — Rust `id\|cls\|text` format, not the Kotlin fallback |
| Screenshot capture works | `jpeg=43936B` from a live MediaProjection |
| Accessibility service binds | `dumpsys accessibility` → `Bound services:{Service[label=On Screen (Rust Cloud)...]}` |
| Foreground service runs with the right types | `isForeground=true types=0x40000020` (SPECIAL_USE\|MEDIA_PROJECTION) |
| Service stops cleanly | `stopSelf()` on every exit path; service record disappears |
| Idle memory | 68 MB PSS (target < 180 MB) |
| Signed release pipeline | `v0.4.1` published, SHA-256 verified by re-download |

---

## 3. Findings

Severity: **S1** = safety/security defect, **S2** = correctness defect, **S3** = quality/debt.

### 3.1 Safety and correctness

| # | Sev | Finding | Evidence |
|---|---|---|---|
| C1 | S1 | **`POST_NOTIFICATIONS` is never requested at runtime.** The manifest declares it, but the only `RequestPermission()` launcher is for `RECORD_AUDIO`. On Android 13+ the foreground notification is therefore suppressed — and that notification carries the only visible **Stop** affordance. The agent can be running with no user-visible indication. | `AndroidManifest.xml:11`; `MainActivity.kt:85` (only permission launcher) |
| C2 | S1 | **`START_STICKY` lets the system resurrect a phantom agent.** After process death the system restarts the service with a `null` intent; `onStartCommand` then proceeds with `goal = ""` and no projection, and the model is asked to act on an empty goal. | `AgentService.kt:116` (`return START_STICKY`), `:45` (goal from a nullable intent) |
| C3 | S1 | **Provider HTTP calls cannot be cancelled.** `CloudClients.nextActionMultimodal` is a blocking OkHttp call inside a coroutine; `scope.cancel()` cannot interrupt a socket read. With `readTimeout(90s)` a Stop can leave ~90 s of in-flight network work running. | `CloudClients.kt:19`, `:73`; `AgentService.kt:142` (`scope.cancel()`) |
| C4 | S2 | **Blanket `catch (_: Throwable)` swallows everything** — including `CancellationException` and every genuine error — with no logging. Failures are invisible in the field. | `AgentService.kt:110` |
| C5 | S2 | **Action execution result is discarded.** `actOnDump` returns `Boolean` (target found and action performed) but the caller ignores it, so a failed tap is indistinguishable from a successful one. | `AgentService.kt:97` |
| C6 | S2 | **No verification step.** `SPEC.md:24` specifies `perceive → plan → guard → act → verify`; only the first four exist. `done` is accepted verbatim from the model with no check against actual UI state. | `AgentService.kt:83` |
| C7 | S2 | **The screenshot is captured before the API-key check.** The most expensive operation in the step runs even on the iteration that is about to exit. | `AgentService.kt:61` (capture) vs `:65` (key check) |
| C8 | S2 | **`Wait` has no duration limit in the validation layer.** Rust accepts any `u32` ms; the 5 s clamp exists only in the Kotlin caller. Rust is documented as the validation boundary. | `lib.rs:21`; clamp at `AgentService.kt:93` |
| C9 | S3 | **`guardrailsOff` is dead configuration** — stored in DataStore, never read, no UI to set it. `SPEC.md:26` requires a working guardrails toggle. | `SettingsStore.kt:24` |

### 3.2 Security and privacy

| # | Sev | Finding | Evidence |
|---|---|---|---|
| X1 | S1 | **Model downloads are not integrity-checked.** Hugging Face packs are downloaded to `*.part` and renamed into place with no checksum or signature check. A corrupted or substituted file is then treated as a valid model. | `ModelManager.kt:158`, `:188`; no sha/checksum reference anywhere in the file |
| X2 | S1 | **The Gemini API key is placed in the URL query string.** URLs propagate into proxies, logs and crash reports. Gemini accepts an `x-goog-api-key` header instead, which keeps the key out of the URL. | `CloudClients.kt:45`, `:113` (OpenAI uses a header — correct) |
| X3 | S1 | **No password-field redaction.** `SPEC.md:26` requires a redaction toggle. Password field contents are included verbatim in the AX tree and therefore sent to the cloud provider. | `AgentAccessibilityService.kt:36` (text captured unconditionally) |
| X4 | S3 | **No banking/wallet blocklist** — required by `SPEC.md:26`. | absent from `AgentService.kt` |
| X5 | S2 | **`MediaPlayer` leaks on the exception path.** `mp.release()` is only reached on the success path. | `AgentService.kt:134-135` |
| X6 | S3 | **Unused accessibility capability.** `canPerformGestures="true"` is declared but the code only ever calls `performAction`. Over-declared privilege. | `accessibility_service_config.xml:7` |
| X7 | S3 | **Placeholder service description.** The accessibility service describes itself to the user as the system string `"OK"`. | `accessibility_service_config.xml:8` |
| X8 | S3 | **Spoken status file can persist.** `cacheDir/status.mp3` is deleted only on the success path; an interruption between write and delete leaves TTS content on disk. | `AgentService.kt` `speakStatus` |

### 3.3 Performance

Measured on the development host; Rust numbers are from `cargo run --release --example bench`.

| # | Sev | Finding | Measurement |
|---|---|---|---|
| P1 | S2 | **`trim_tree` deserialises the entire AX array before truncating to 120 nodes.** `serde_json::from_str` materialises every node, then `.take(MAX_NODES)` discards all but the first 120. | 120 nodes → **107 µs**; 500 nodes → **227 µs**; 2000 nodes (128 KB) → **805 µs** — all producing an identical 4,667 B / 120-line output. ~90 % of the work at 2000 nodes is discarded. (`lib.rs:40`) |
| P2 | S3 | **The byte budget can be exceeded by one line.** The `bytes > MAX_BYTES` check runs *after* the line is pushed. | `lib.rs:47-48`; the existing test asserts the looser `MAX_BYTES + 64` (`lib.rs:132`) |
| P3 | S3 | **The 8 KB byte budget never binds.** 120 nodes of realistic text produce 4,667 B — the node cap, not the byte cap, is the real limit. The budget is therefore untested in practice. | bench output §5 |
| P4 | S2 | **No `callTimeout`** on the shared OkHttp client — only connect (20 s) and read (90 s) timeouts. | `CloudClients.kt:17-21` |
| P5 | S3 | **`history` accumulates 40 entries but only the last 6 are used.** | `AgentService.kt` `history.add` vs `history.takeLast(6)` |
| P6 | S3 | **The AX tree is serialised in full then parsed in full.** `dumpIndexed()` builds a complete `JSONArray`; Rust then parses that whole string and throws most of it away. | `AgentAccessibilityService.kt:47` → `lib.rs:40` |

### 3.4 Dead code and contract mismatches

| # | Sev | Finding | Evidence |
|---|---|---|---|
| D1 | S2 | **`build_prompt()` is dead code.** It is the Rust implementation of the prompt-building responsibility claimed in `PLAN.md:15`, but no Kotlin code calls it — the prompt is assembled inline in the service. | `lib.rs:70-79`; grep across the repo finds no Kotlin caller |
| D2 | S2 | **`scroll` can never target a specific node.** The Rust enum has no id field, the system prompt advertises no id, but the executor reads `optInt("id", -1)` and falls back to the AX root — so a scroll always lands on the root node. | `lib.rs:17`; `AgentAccessibilityService.kt:67-70` |
| D3 | S3 | **`serde` accepts unknown action fields** (no `deny_unknown_fields`), so `{"type":"click","id":7,"extra":…}` parses successfully. | `lib.rs:11-13` |

### 3.5 Testing gaps

| # | Finding |
|---|---|
| T1 | **2 Rust unit tests** (`lib.rs:123-134`). Nothing covers per-variant action parsing, boundary lengths (2 048 / 500 / 128 bytes), `ScrollDir` variants, unknown fields, empty input, or `Wait` bounds. |
| T2 | **Zero Android/Kotlin unit tests** — there is no `app/src/test/` directory. |
| T3 | **No integration tests** — nothing exercises Kotlin↔Rust, provider request/response shapes, timeouts, or cancellation. |
| T4 | `tools/check.py` is 55 regex/string assertions. Useful as a smoke test, but it asserts *textual* properties, not behaviour — it would not catch C1–C9. |
| T5 | **CI runs no linters**: no `cargo fmt --check`, no `cargo clippy`, no Android Lint, no dependency audit, no APK size gate. |

### 3.6 Dependency risk

| # | Finding |
|---|---|
| V1 | `androidx.security:security-crypto:1.1.0-alpha06` is an **alpha** in a security-critical path (API-key storage). It also drags in Google Tink, which required an R8 `-dontwarn` for compile-only ErrorProne annotations. |
| V2 | `ort 2.0.0-rc.15` is a **pre-release** crate. There is no stable 2.x on crates.io (see `PROGRESS.md`), so the pin is forced; it is currently unused (only `ondevice-tts` uses it, and that feature is off). |
| V3 | `kotlinx-serialization-json` is declared but **never used** — no `@Serializable` and no `kotlinx.serialization` import exists. Dead dependency. |
| V4 | `whisper-rs 0.12` is optional and only enabled by `ondevice-stt`; it is not built in the cloud APK, but it still participates in dependency resolution. |

---

## 4. What is *not* implemented

Recorded so the plan is not confused with reality.

| Feature | State |
|---|---|
| On-device STT / TTS / VLM inference | `cfg`-gated stubs that always return `Err` (`lib.rs:84,89,94`). Download works; inference does not. |
| Gemini TTS | Stub — the speech-config flow is TODO (`CloudClients.kt` `speakToFile` handles OpenAI only). |
| SSE streaming | Claimed in `PLAN.md:11`; not implemented. |
| Token metering / cost estimation | Required by `PLAN.md:36`; absent. |
| Password redaction toggle | Required by `SPEC.md:26`; absent. |
| Banking blocklist | Required by `SPEC.md:26`; absent. |
| Guardrails toggle | Key exists, no effect, no UI. |
| OpenRouter TTS | Not offered (TTS is OpenAI-only). |
| Verified-action outcome checking | Absent. |

---

## 5. Baseline measurements

Full numbers and method in [`benchmarks/BASELINE.md`](benchmarks/BASELINE.md).

Rust hot path (release build, development host):

| Operation | min | median | max |
|---|---|---|---|
| `trim_tree` (120 nodes / 7.5 KB) | 102.1 µs | 107.1 µs | 667.9 µs |
| `trim_tree` (2000 nodes / 128 KB) | 755.5 µs | 804.9 µs | 1931.1 µs |
| `parse_action` (valid) | 0.2 µs | 0.3 µs | 6.7 µs |
| `parse_action` (invalid) | 1.2 µs | 1.3 µs | 66.7 µs |
| `build_prompt` (**dead code**) | 14.6 µs | 15.0 µs | 432.8 µs |

---

## 6. Recommended order of work

| Priority | Item | Rationale |
|---|---|---|
| 1 | C1 (notification permission) | The agent must be visibly stoppable. Small, contained. |
| 2 | C2 (`START_NOT_STICKY` + null-intent guard) | Removes the phantom-agent path. Two lines. |
| 3 | C3 + P4 (cancellable, time-bounded provider calls) | Stop latency and resource usage. |
| 4 | C4, C5 (structured errors, check action result) | Makes failures visible and recoverable. |
| 5 | X1 (download integrity), X2 (key out of the URL) | Security, low risk. |
| 6 | Rust: `Wait` bound, `catch_unwind` at the JNI boundary, per-variant tests | Hardens the validation boundary. |
| 7 | P1 (`trim_tree` early-bail) | Removes ~90 % wasted parse work at large AX trees. |
| 8 | D1/D2 (`build_prompt` wiring, scroll target id) | Closes contract mismatches. |
| 9 | CI: fmt/clippy/lint/size gate | Prevents regression of the above. |

---

## 7. Explicitly out of scope for this milestone

- **Local inference** (Phase 6). Audit only: the download path exists, the runtime does
  not. Adding llama.cpp/ONNX Runtime is a separate, larger milestone and must not be
  bundled with correctness work.
- **A formal agent state machine.** The current loop is a bounded `repeat(40)` with
  explicit exit paths. Introducing Idle/Planning/Executing/… states would be a rewrite of
  working code for no measured benefit. The concrete gaps (C4, C5, C6) are better fixed
  locally.
- **Dependency upgrades.** Not a single dependency is upgraded in this milestone
  (rule 8).

---

## 8. Preservation commitments

The following working behaviour is deliberately **not** changed:

- Rust remains the action-schema and budgeting authority; Kotlin remains the Android shell.
- The `needHelp` fail-safe: a Rust parse error becomes `{"type":"needHelp"}` so a confused
  model cannot act. (`lib.rs:104-107`)
- The Kotlin fallback in `RustBridge` when the `.so` is missing — the fallback path stays,
  and stays logged.
- The `.part` → atomic rename download pattern (only the integrity check is added).
- `EncryptedSharedPreferences` key storage, `allowBackup=false`.
- All three BYOK slots and every provider/model choice already offered.
- The 40-step cap, the 8 KB / 120-node budgets, and the 960 px screenshot size.
- The 68 MB / 2.76 MB memory and size targets.

---

## 9. Documentation drift (documentation only — no code impact)

| Doc | Claim | Reality |
|---|---|---|
| `PLAN.md:9` | "720p JPEG" | `ScreenCapture.kt` uses `MAX_EDGE = 960` |
| `SPEC.md:27` | "720p max" | same |
| `PLAN.md:11` | "CloudClients (OkHttp, SSE)" | no SSE is implemented |
| `PLAN.md:15` | "Rust owns ... prompt building" | `build_prompt` is dead code (D1) |
| `PLAN.md:19` | "Coil/OkHttp connection reuse" | no Coil dependency exists |
| `PLAN.md:25` | file map lists 7 Kotlin files | 9 exist (`ModelManager.kt`, `ModelsSection.kt` added in v0.4) |
| `PLAN.md:28` | "no local SDK here" | a full SDK 36 + NDK 27 is now installed |
| `PLAN.md:36` | "token meter visible" | not implemented |
| `README.md` "On-device downloads ... Inference on downloaded files is the next build" | accurate | — |
