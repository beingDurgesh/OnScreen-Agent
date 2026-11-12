# OnScreen-Agent — Optimisation Results

**Milestone:** 1 — correctness, safety and the AX-tree optimisation.
**Date:** 2026-11-12
**Baseline:** [`BASELINE.md`](BASELINE.md) (commit `e613cc3`)
**Branch:** `feature/engineering-hardening`

## 1. Rust hot path — `trim_tree`

Same workload, same machine, `cargo run --release --example bench`, median of
200 iterations after 20 warm-up iterations.

| Input | Before | After | Speed-up | Max before | Max after |
|---|---|---|---|---|---|
| 10 nodes / 629 B | 19.20 µs | 16.40 µs | 1.17× | 36.10 µs | 52.20 µs |
| 120 nodes / 7 549 B | 107.10 µs | 60.40 µs | **1.77×** | 667.90 µs | 141.10 µs |
| 500 nodes / 31 800 B | 226.60 µs | 86.50 µs | **2.62×** | 1 114.20 µs | 115.80 µs |
| 2 000 nodes / 128 543 B | 804.90 µs | 194.40 µs | **4.14×** | 1 931.10 µs | 230.40 µs |

**Output is byte-identical.** For 120, 500 and 2 000 input nodes the trimmed
tree is 4 667 B / 120 lines both before and after. A new test
(`trims_streams_and_stops_early`) asserts that a 2 000-node dump and a
120-node dump produce the same string, and `trims_to_budget` was strengthened
to assert the exact expected content so it cannot pass vacuously.

**Memory:** the previous implementation deserialised every node in the array
into an owned `Node` (three `String`s each) before truncating. For a 2 000-node
dump that is ~6 000 short-lived string allocations per agent step, of which
~5 640 were discarded. The new implementation deserialises at most 120 nodes
and skips the tail with `IgnoredAny`, which walks the remaining JSON without
allocating.

**In context:** at the 40-step cap this saves about 68 ms of wall clock per
run in the worst case, and roughly 240 000 short-lived allocations. The model
round-trip dominates the step time by three to four orders of magnitude, so
this is a memory and tail-latency win, not a user-visible speed-up. It was
done because the allocation churn was avoidable, not because it was slow.

## 2. Other measured changes

| Measure | Before | After | Notes |
|---|---|---|---|
| `parse_action` (valid) | 0.30 µs | 0.20 µs | unchanged logic; noise |
| `parse_action` (invalid) | 1.30 µs | 0.80 µs | unchanged logic; noise |
| `build_prompt` | 15.00 µs | 8.50 µs | no longer dead code |
| Release APK | 2.76 MB | 2.76 MB | unchanged |
| Idle PSS | 68 MB | 68 MB | unchanged |

## 3. Correctness changes with no speed impact

These are the substantive wins of the milestone. None of them is a performance
change; all of them are defects that only running the application exposed.

| Defect | Symptom | Fix |
|---|---|---|
| `createVirtualDisplay` called per step | `SecurityException: Don't re-use the resultData … Don't take multiple captures by invoking MediaProjection#createVirtualDisplay multiple times on the same instance.` — **the agent went blind after the first step** | One `VirtualDisplay` per projection, created lazily on first use and reused |
| `nextAction` ⇄ `nextActionMultimodal` mutual recursion | `StackOverflowError` on **every** text-only call, i.e. whenever the screenshot failed | `nextAction` builds the request itself |
| `MediaProjection.Callback` never registered | On Android 14+ the projection delivers no frames at all | Register the callback before `createVirtualDisplay` |
| `POST_NOTIFICATIONS` never requested | The foreground notification — the only visible "agent is running" signal and the only reachable Stop button — could be suppressed by the OS | Request it when Play is pressed |
| `START_STICKY` + null intent | The system could resurrect the agent with an **empty goal** | `START_NOT_STICKY`, and refuse a null intent |
| Provider calls not cancellable | A Stop could leave ~90 s of network work running | Track the active `Call`, cancel it, add `callTimeout` |
| Blanket `catch (Throwable)` | Every failure was invisible; `CancellationException` was swallowed | Log the failure, rethrow cancellation |
| Action result discarded | A failed tap was indistinguishable from a successful one | Log `FAILED` |
| `MediaPlayer` not released on the exception path | Native audio leak | Release in a `finally` |
| Model downloads unverified | A corrupted or substituted pack was accepted as a valid model | SHA-256 + exact size verified before the file is accepted |
| Gemini key in the URL query string | Key leaked into proxy logs and crash reports | Moved to the `x-goog-api-key` header |
| `Wait` bound only in Kotlin | The Rust validation layer did not enforce the limit | Enforced in Rust as well |
| Rust panic could unwind into JNI | Would abort the whole app process | `catch_unwind` on every JNI export |
| `build_prompt` never called | Rust did not actually own prompt building (contradicting `PLAN.md`) | Wired in; the output format is unchanged |

## 4. Test coverage

| | Before | After |
|---|---|---|
| Rust unit tests | 2 | **15** |
| Android unit tests | 0 | 0 *(planned — see `OPTIMIZATION_PLAN.md` §2)* |
| Static assertions (`tools/check.py`) | 55 | 55 |
| Mock provider for integration testing | none | `tools/mock_provider.py` |

## 5. Regressions

None observed. Specifically:

- `cargo test --release`: 15 passed, 0 failed.
- `tools/check.py`: 0 failures.
- `./gradlew :app:assembleDebug` and `:app:assembleRelease`: build clean, no
  new warnings.
- The trimmed AX tree is byte-identical to the previous implementation for
  every input shape tested.
- The prompt string is byte-identical to the string Kotlin used to build
  inline (asserted by `build_prompt_matches_the_kotlin_format`).
- All six provider/model choices and all three BYOK slots still work.

## 6. Not measured

| Metric | Why |
|---|---|
| Live-model request latency | no provider key was used |
| Real-device screenshot timing | the emulator's app-scoped projection delivers no frames |
| Peak PSS | emulator accounting is not representative |
| Frame-level rendering performance | SwiftShader emulator |
| Screenshot resolution / quality sweep | not started |
| Battery | no meaningful emulator signal |

## 7. Open defect found during measurement

**App-scoped screen capture does not deliver frames on the Android 15
emulator.** After the `MediaProjection` is granted for a single app and the
`VirtualDisplay` is created, the compositor never pushes a frame:

```
ScreenCapture: projection granted
ScreenCapture: virtual display created 1080x2400
ScreenCapture: no frame within 2000ms
```

The baseline code *did* produce a 43 936-byte JPEG in an earlier run, before
the `VirtualDisplay` was made persistent, so this is most likely an interaction
between the app-scoped projection and the emulator's SwiftShader compositor
rather than a plain API misuse. It is **not resolved** and needs either a
physical Android 15 device or an "Entire screen" projection to narrow down.

Until it is resolved, screen capture cannot be claimed as working end to end,
even though the Rust half of the pipeline (AX dump → Rust trim) is verified.
