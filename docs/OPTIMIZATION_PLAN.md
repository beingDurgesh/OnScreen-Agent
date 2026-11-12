# OnScreen-Agent — Optimisation Plan

Derived from [`ENGINEERING_AUDIT.md`](ENGINEERING_AUDIT.md) and
[`benchmarks/BASELINE.md`](benchmarks/BASELINE.md). Ordered by risk-adjusted
value: correctness first, then security, then speed.

Status legend: **done** in this milestone · **next** recommended ·
**later** · **blocked** (cannot be completed in this environment).

---

## Milestone 1 — correctness and safety *(delivered)*

| # | Item | Audit ref | Why |
|---|---|---|---|
| 1.1 | Request `POST_NOTIFICATIONS` before starting the agent | C1 | The foreground notification is the only visible "the agent is running" signal and the only reachable Stop button. Without it the agent can be running invisibly. |
| 1.2 | `START_NOT_STICKY` + refuse a null intent | C2 | The system could resurrect the service with no goal and no capture grant. |
| 1.3 | Track and cancel the in-flight provider call; add `callTimeout` | C3, P4 | Stop could leave ~90 s of network work running. |
| 1.4 | Log the failed step instead of swallowing it; rethrow `CancellationException` | C4 | Invisible failures; swallowed cancellation breaks structured concurrency. |
| 1.5 | Check the action-execution result | C5 | A failed tap was indistinguishable from a successful one. |
| 1.6 | Check the API key **before** the screenshot | C7 | The screenshot is the most expensive operation in a step. |
| 1.7 | Release `MediaPlayer` and delete the speech file in `finally` | X5, X8 | Native resource leak; synthesised speech left on disk. |
| 1.8 | Enforce the wait bound in Rust, not only in Kotlin | C8 | Rust is documented as the validation boundary. |
| 1.9 | `catch_unwind` around every JNI export | — | A Rust panic unwinding into JNI aborts the app process. |
| 1.10 | One `VirtualDisplay` per `MediaProjection` | — | Android throws `SecurityException` on the second `createVirtualDisplay`; the baseline created one per step, so the agent went blind after one step. |
| 1.11 | Fix the mutual recursion between `nextAction` and `nextActionMultimodal` | — | A pre-existing `StackOverflowError` on every text-only call. |
| 1.12 | Wire `build_prompt` into the loop | D1 | It was dead code; Rust is supposed to own the prompt budgets. |
| 1.13 | Stream the AX tree instead of deserialising all of it | P1 | 4.1× faster on large dumps, byte-identical output, ~2 000 fewer allocations per step. |
| 1.14 | Verify model downloads (SHA-256 + exact size) | X1 | A corrupted or substituted pack was treated as a valid model. |
| 1.15 | Move the Gemini key out of the URL | X2 | Query strings leak into proxy logs and crash reports. |
| 1.16 | Drop the unused `canPerformGestures`; add a real service description | X6, X7 | Over-declared privilege; the user saw "OK" as the service description. |

---

## Milestone 2 — test seams *(next)*

The reason there are no Kotlin unit tests is that the testable logic is
embedded in Android-coupled classes. The fix is to extract seams, not to add
mocking frameworks everywhere.

| # | Item | Notes |
|---|---|---|
| 2.1 | Extract the provider request bodies into pure functions | `buildOpenAiBody(...)`, `buildGeminiBody(...)` return `String`. Testable on the JVM with no Android or network. |
| 2.2 | Extract the prompt assembly decision | Already in Rust; add a JVM test that the Kotlin fallback and the Rust path produce the same string. |
| 2.3 | Add `app/src/test/` with JVM unit tests | Needs `testImplementation("junit:junit:4.13.2")` — a **new** dependency, so it is a deliberate separate change. |
| 2.4 | Add `app/src/androidTest/` for the service and JNI | Needs a connected device in CI; the emulator's app-scoped capture does not deliver frames, so this is blocked on hardware. |
| 2.5 | Wire `tools/mock_provider.py` into CI | Blocked on 2.4. |

---

## Milestone 3 — provider architecture

| # | Item | Notes |
|---|---|---|
| 3.1 | Classify provider errors | `ProviderError` enum: `Timeout`, `RateLimited`, `AuthFailed`, `Network`, `MalformedResponse`, `Cancelled`. Today every failure surfaces as a generic exception and the loop retries blindly. |
| 3.2 | Handle HTTP 429 with `Retry-After` | Bounded retry with backoff; currently the step just fails. |
| 3.3 | Track token usage and estimate cost | `PLAN.md` asks for a visible token meter; the provider responses already carry `usage`. |
| 3.4 | Detect model capabilities | Some models do not accept images or `response_format`; the app sends both unconditionally. |
| 3.5 | Surface provider errors to the user | `speakStatus` currently only runs for `done`/`needHelp`/missing-key. |

---

## Milestone 4 — agent reliability

| # | Item | Notes |
|---|---|---|
| 4.1 | Add a verification step after a state-changing action | `SPEC.md` asks for `perceive → plan → guard → act → verify`; only the first four exist. `done` is currently taken on the model's word. |
| 4.2 | Detect stale targets | The dump and the action use the same `Dump`, so ids cannot go stale *within* a step. A target can still disappear between the dump and the action; `actOnDump` returns `false` and that is now logged (1.5) but not recovered from. |
| 4.3 | Detect false completion | Ask the model to confirm, then check the UI. Must not rely on the model alone. |
| 4.4 | Sensitive-action confirmation | Explicit user confirmation outside the model prompt for high-impact actions. Needs a policy definition first. |

---

## Milestone 5 — local inference *(blocked on scope)*

| # | Item | Notes |
|---|---|---|
| 5.1 | Local speech-to-text via `whisper.cpp` | `whisper-rs` is already an optional dependency of `ondevice-stt`. Needs the NDK build of `whisper-rs` and a proof of concept on ARM64. |
| 5.2 | Local text-to-speech via KittenTTS/ONNX Runtime | `ort` is already an optional dependency of `ondevice-tts`, pinned to `2.0.0-rc.15` because there is no stable 2.x. |
| 5.3 | Local VLM via llama.cpp | Largest effort (RAM, APK size, licence review). |
| 5.4 | Never silently switch local → cloud | A hard requirement. The mode must be explicit and visible. |

Do **not** add more than one runtime at a time, and do not claim offline
support until the workflow has been executed with the network disabled.

---

## Milestone 6 — performance, in measured order

Baseline numbers are in [`benchmarks/BASELINE.md`](benchmarks/BASELINE.md).

| # | Item | Measured impact |
|---|---|---|
| 6.1 | ✅ Stream the AX tree | 4.1× on 2 000-node dumps; ~68 ms per 40-step run |
| 6.2 | Screenshot resolution / JPEG quality sweep | **Unmeasured.** The current 960 px / q60 has never been compared against alternatives for model accuracy, capture time or payload size. |
| 6.3 | Avoid the screenshot entirely when the model does not need it | Unmeasured; the app always sends an image. |
| 6.4 | Cap the history list instead of growing it to 40 | Trivial; the list is bounded by the step cap but only the last 6 are used. |
| 6.5 | Profile the Kotlin side (AX extraction was 45 ms on the emulator) | Unmeasured on real hardware. |
| 6.6 | 16 KB page-size compatibility | Required for Android 15+ devices with 16 KB pages; the native lib is not yet verified for it. |

---

## Milestone 7 — CI/CD

| # | Item | Notes |
|---|---|---|
| 7.1 | `cargo fmt --check` and `cargo clippy -D warnings` | Not run today. |
| 7.2 | Android Lint report as a CI artifact | `lintVital` runs during `assembleRelease` but the report is not published. |
| 7.3 | APK size gate | Add a budget check against the 25 MB target. |
| 7.4 | Dependency audit (`cargo audit`, Dependabot) | Not enabled. |
| 7.5 | Secret scanning | Not enabled. |
| 7.6 | Publish the `cargo test` and benchmark output as artifacts | Makes regressions visible without digging through logs. |

---

## Explicitly not planned

- **A formal agent state machine.** The current bounded loop with explicit
  exits is simpler and works; a rewrite would be churn.
- **C/C++ integration.** Nothing in the audit needs it. The Rust `cdylib` is
  already the native layer.
- **A second language in the app.** Python stays outside the APK, as a
  tooling and evaluation layer.
- **Upgrading dependencies wholesale.** The mandate is to change one thing at
  a time; the dependency work is split across §5 of the security audit.
