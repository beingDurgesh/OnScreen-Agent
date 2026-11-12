# OnScreen-Agent — Testing

**Date:** 2026-11-12

## What exists

| Suite | Where | Count | Runs in |
|---|---|---|---|
| Rust unit tests | `rust/agent-core/src/lib.rs` (`#[cfg(test)]`) | 15 | `cargo test --release` |
| Rust micro-benchmarks | `rust/agent-core/examples/bench.rs` | 4 workloads | `cargo run --release --example bench` |
| Static assertions | `tools/check.py` | 55 | `python tools/check.py` |
| Mock provider | `tools/mock_provider.py` | — | `python tools/mock_provider.py --port 8099` |
| Android lint | AGP `lintVital` | — | part of `assembleRelease` |
| CI | `.github/workflows/build-apk.yml`, `release-apk.yml` | — | every push |

## Coverage before this milestone

Two Rust tests and **zero** Android unit tests. There was no `app/src/test/`
directory at all, and no integration test of any kind. The static check script
asserts *textual* properties (a regex appears in a file) — useful as a smoke
test, but it would not catch a single one of the defects listed in
[`ENGINEERING_AUDIT.md`](ENGINEERING_AUDIT.md).

## Rust tests added in this milestone

| Test | What it pins down |
|---|---|
| `parses_every_action_variant` | every `Action` variant round-trips |
| `rejects_unknown_and_malformed_actions` | 11 malformed inputs are rejected (unknown type, missing fields, wrong types, non-JSON, empty) |
| `enforces_action_size_limit` | the 2 048-byte action cap, at and over the limit |
| `enforces_input_text_limit` | the 500-character input cap |
| `enforces_package_name_rules` | package names accept `.` and `_`, reject `/`, `;`, spaces and over-length names |
| `enforces_wait_limit` | the 5 000 ms wait cap (new — Rust now owns this bound) |
| `trims_to_budget` | node and byte caps, **and** that the output is non-empty (guards against a vacuous pass) |
| `trims_streams_and_stops_early` | 2 000 nodes produce exactly the same result as 120 |
| `trims_a_short_tree_completely` | a tree shorter than the cap is not truncated |
| `trim_handles_garbage_without_panicking` | unparseable input yields an empty tree, not a panic |
| `trim_caps_text_and_flattens_newlines` | text is capped and newlines flattened |
| `build_prompt_matches_the_kotlin_format` | the prompt is byte-for-byte what Kotlin used to build inline |
| `build_prompt_caps_goal_tree_and_history` | goal 500 chars, tree 8 KB, last 6 steps |
| `guarded_converts_panics_into_a_fallback` | the JNI panic boundary works |

### The vacuous-pass trap

Two of these tests initially passed for the wrong reason: an earlier
implementation of `trim_tree` returned an empty string, and
`assert!(t.lines().count() <= MAX_NODES)` is satisfied by an empty string.
`trims_to_budget` now asserts the exact expected line count and the exact
first line, so a regression to "returns nothing" fails the suite.

## Integration test: the mock provider

`tools/mock_provider.py` is a small OpenAI-compatible server. It lets the
Android provider path be exercised — request shape, response parsing, error
paths, timeouts — **without a paid API**, which is what `Phase 8` asks for.

```bash
python tools/mock_provider.py --port 8099 --delay-ms 250 --log
```

From the Android emulator the host is `http://10.0.2.2:8099/v1`; point the
app's "Custom base URL" field at it.

| Flag | Use |
|---|---|
| `--delay-ms N` | make timeouts and cancellation testable |
| `--fail N` | return an HTTP error instead of a completion |
| `--log` | print model, message count and whether an image was attached |

**Not yet wired into CI.** The blocker is that driving the Android UI through
`adb` on an emulator is slow and flaky (see the limitations below); an
`androidTest` instrumented test would be the proper home for this.

## Verifying the agent loop on a device

The agent loop logs a structured line per phase:

```
AgentService: perceive: ax=43/45ms tree=1798B/0ms jpeg=43936B/120ms
AgentService: act: step 0 vlm=1240ms parse=0ms act=3ms [done:ok]
```

```bash
adb logcat -s AgentService:V
adb logcat -s ScreenCapture:V
adb logcat -s RustBridge:V
```

This is the intended way to check a real device: it shows the accessibility
node count, the trimmed tree size, the JPEG size and the model latency for
each step.

## Test commands

```bash
# Rust
cd rust/agent-core && cargo test --release
cd rust/agent-core && cargo run --release --example bench

# Android
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:assembleRelease      # release APK (signed if the secrets are set)
python tools/check.py                # 55 static assertions, no SDK needed

# Everything CI runs
python tools/check.py && (cd rust/agent-core && cargo test --release) && ./gradlew :app:assembleDebug
```

## Gaps — what is still not tested

| Gap | Why it is still open |
|---|---|
| No Android unit tests | There is no `app/src/test/` source set. The pure logic that could be tested JVM-side (the `CloudClients` request bodies, the prompt assembly) is currently inline in Android-coupled classes. Extracting those seams is the next step; see `OPTIMIZATION_PLAN.md`. |
| No Kotlin↔Rust integration test in CI | The JNI contract is verified by the emulator run and by matching the ELF export table against the Kotlin `external fun` declarations, but not automatically in CI. |
| No provider-failure test | The mock provider supports `--fail`, but nothing drives it from CI yet. |
| No cancellation test | `CloudClients.cancelActive()` has no automated test. |
| No physical-device test | No Android 15 hardware was available. The emulator's SwiftShader rendering makes UI automation unreliable, and app-scoped screen capture does not deliver frames there. |
| No live-model test | No provider key was used. The cloud round-trip has never run end to end. |

## Constraints observed

- Unit tests never depend on a live paid API.
- The Rust test suite adds **no** dependencies.
- The benchmark is a Cargo *example*: it is not part of the shipped `cdylib`
  and does not affect the APK.
- No existing test was removed or weakened to make a build pass. The two
  original Rust tests were kept and extended.
