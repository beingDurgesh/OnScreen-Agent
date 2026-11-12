# OnScreen-Agent — Performance Baseline

**Measured:** 2026-11-12, commit `e613cc3` (pre-milestone), on the development
host and an Android 15 emulator.
**Tooling:** `cargo run --release --example bench` (Rust), `adb` + in-app
`AgentService` instrumentation (Android), `apksigner` / `apkanalyzer` (artifact).

> Every number here was measured on the machine described below. Nothing is
> extrapolated, modelled or carried over from another project. Metrics that
> could not be measured in this environment are listed explicitly as
> **unavailable** rather than estimated.

## Environment

| | |
|---|---|
| Host | Windows 11 10.0.26300, x86_64 |
| CPU / RAM | Intel + discrete NVIDIA GeForce RTX 2050, 16 GB |
| Rust | stable, `opt-level=z, lto, strip, codegen-units=1` (release profile in `Cargo.toml`) |
| JDK | Temurin 17.0.18.8 |
| Android SDK | platform 36, build-tools 36.0.0, NDK 27.0.12077973 |
| Gradle | 8.9 via the committed wrapper, AGP 8.6.1, Kotlin 2.0.21 |
| Emulator | Android 15 (API 35), `x86_64` system image, AVD `test35`, WHPX acceleration, SwiftShader GL |
| Device | **No physical Android device was available.** All on-device figures are from the emulator. |

### Emulator caveat

The emulator renders through SwiftShader and runs at 5–21 s per frame. That
inflates every wall-clock figure below and makes UI automation unreliable.
Treat the Android timings as an **upper bound**, not a device-representative
number.

---

## 1. Rust agent core (`cargo run --release --example bench`)

Each figure is the median of 200–5 000 iterations after 20–200 warm-up
iterations, on a single thread.

### 1.1 `trim_tree` — the JNI hot path, called once per agent step

| Input | min | median | mean | max |
|---|---|---|---|---|
| 10 nodes / 629 B | 9.60 µs | 19.20 µs | 16.13 µs | 36.10 µs |
| 120 nodes / 7 549 B | 102.10 µs | 107.10 µs | 134.15 µs | 667.90 µs |
| 500 nodes / 31 800 B | 216.50 µs | 226.60 µs | 254.47 µs | 1 114.20 µs |
| 2 000 nodes / 128 543 B | 755.50 µs | 804.90 µs | 843.42 µs | 1 931.10 µs |

### 1.2 `parse_action` — the validation boundary

| Case | min | median | mean | max |
|---|---|---|---|---|
| valid `click` | 0.20 µs | 0.30 µs | 0.28 µs | 6.70 µs |
| valid `input` | 0.40 µs | 0.40 µs | 0.45 µs | 49.30 µs |
| invalid → `Err` | 1.20 µs | 1.30 µs | 1.36 µs | 66.70 µs |

### 1.3 `build_prompt`

| min | median | mean | max |
|---|---|---|---|
| 14.60 µs | 15.00 µs | 16.11 µs | 432.80 µs |

> At baseline this function was **dead code** — no Kotlin caller. See the audit,
> finding D1.

### 1.4 Output budget behaviour

| Nodes in | Bytes out | Lines out |
|---|---|---|
| 10 | 387 | 10 |
| 120 | 4 667 | 120 |
| 500 | 4 667 | 120 |
| 2 000 | 4 667 | 120 |

The **node** cap (120) binds first; the 8 KB byte cap is never reached with
realistic node text. The byte budget is therefore effectively untested in
practice.

### 1.5 Optimised comparison (this milestone)

`trim_tree` was changed to deserialise at most 120 nodes instead of the whole
array. Same workload, same machine, `cargo run --release --example bench`:

| Input | median before | median after | speed-up | max before | max after |
|---|---|---|---|---|---|
| 10 nodes | 19.20 µs | 16.40 µs | 1.17× | 36.10 µs | 52.20 µs |
| 120 nodes | 107.10 µs | 60.40 µs | **1.77×** | 667.90 µs | 141.10 µs |
| 500 nodes | 226.60 µs | 86.50 µs | **2.62×** | 1 114.20 µs | 115.80 µs |
| 2 000 nodes | 804.90 µs | 194.40 µs | **4.14×** | 1 931.10 µs | 230.40 µs |

Output is **byte-identical** before and after (4 667 B / 120 lines for the
120/500/2 000-node cases), and the existing tests were extended so the
comparison cannot pass vacuously.

Context for prioritisation: at 40 steps the worst case saving is
40 × (1 931 − 230) µs ≈ **68 ms** of wall clock per run. The model round-trip
dominates by three to four orders of magnitude, so this is a memory and
tail-latency improvement, not a user-visible speed-up. It was still worth doing
because it removes ~2 000 heap allocations per step.

---

## 2. Android application (Android 15 emulator)

### 2.1 Startup

| Metric | Value | How measured |
|---|---|---|
| `am start -W` wait time | 10 608 ms | `adb shell am start -W` |
| Activity first frame | **unavailable** | `am start -W` reports `WaitTime` but the emulator's `Displayed` value is not comparable to a real device |

### 2.2 Agent loop, per step

From the in-app instrumentation (`AgentService` log line, added in this
milestone):

| Stage | Observed | Note |
|---|---|---|
| AX tree extraction (`dumpIndexed`) | 45 ms | 43 nodes |
| Tree processing (Rust via JNI) | < 1 ms | matches §1.1 |
| Screenshot capture | 2 356 ms (no frame) | emulator; see §2.3 |
| Model request | **unavailable** | see §2.4 |

### 2.3 Screenshot capture — **fails on the emulator**

`captureJpeg` returned `null` on every attempt, with:

```
ScreenCapture: no frame within 2000ms
```

The projection is granted and the `VirtualDisplay` is created, but the
compositor never delivers a frame. Two separate causes were identified and are
documented in the audit:

1. Android permits **one** `VirtualDisplay` per `MediaProjection`. The
   baseline code created a new one on every step, which throws
   `SecurityException("Don't re-use the resultData to retrieve the same
   projection instance … Don't take multiple captures by invoking
   MediaProjection#createVirtualDisplay multiple times on the same instance.")`.
   Fixed in this milestone.
2. With that fixed, an app-scoped projection of the agent's own window still
   yields no frames on the emulator. **Not resolved** — see the limitations in
   the final report.

### 2.4 Model request latency — **unavailable**

No live provider key was used, so the real cloud-model latency was not
measured. The provider path was exercised against the local mock server
(`tools/mock_provider.py`, 250 ms artificial latency) to validate the request
and response shapes.

### 2.5 Memory

| Metric | Value | Notes |
|---|---|---|
| Idle PSS (app in foreground) | **68 399 KB ≈ 67 MB** | `dumpsys meminfo com.onscreen.agent` |
| Target | < 180 MB | met, with ~62 % headroom |
| Peak PSS during an agent step | **unavailable** | the emulator's memory accounting is not representative |
| Java heap | 16 960 KB allocated / 46 188 KB reserved | |
| Native heap | 17 444 KB | |

### 2.6 Artifact size

| Artifact | Size |
|---|---|
| Release APK (signed, minified, resource-shrunk) | **2.76 MB** (2 821 KB) |
| Debug APK | 7.58 – 8.13 MB |
| Target | < 25 MB |

---

## 3. CI timings (GitHub Actions, ubuntu-latest)

| Workflow | Duration |
|---|---|
| `build-apk` (cargo test → cargo-ndk → assembleDebug → artifact) | 3 m 22 s – 4 m 18 s |
| `release-apk` (same + apksigner + publish) | 3 m 59 s – 4 m 49 s |

---

## 4. Metrics that are unavailable in this environment

| Metric | Why |
|---|---|
| Model request / response latency (real provider) | no live API key was used |
| Real-model task completion rate | no live model |
| Idle PSS on a physical device | no physical device |
| Peak PSS / memory pressure | emulator accounting not representative |
| Battery consumption | emulator reports nothing meaningful |
| Frame-level rendering performance (JankStats, Perfetto) | SwiftShader emulator |
| Cold vs warm start split | `am start -W` on the emulator is dominated by GPU stalls |
| Crash frequency in the field | no crash reporting in the app |

---

## 5. How to reproduce

```bash
# Rust micro-benchmarks (stable toolchain, no extra dependencies)
cd rust/agent-core
cargo run --release --example bench

# Rust unit tests
cargo test --release

# Android build + static checks
./gradlew :app:assembleDebug
python tools/check.py

# Agent-loop timings on a running emulator
adb logcat -s AgentService:V
```

The example benchmark is a Cargo *example*, not a library target: it is not
compiled into the `cdylib` that ships in the APK and it adds no dependencies.
