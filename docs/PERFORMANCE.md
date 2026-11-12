# OnScreen-Agent — Performance

How the app spends its time and memory, and where the costs actually are.
Raw measurements are in [`benchmarks/BASELINE.md`](benchmarks/BASELINE.md);
the after-optimisation numbers are in [`benchmarks/RESULTS.md`](benchmarks/RESULTS.md).

## Where the time goes

| Phase | Owner | Typical cost | Notes |
|---|---|---|---|
| Model round-trip | provider | **seconds** | 3–4 orders of magnitude more than everything else combined |
| Screenshot capture | `MediaProjection` | 100 ms – 2 s | dominated by waiting for a compositor frame |
| AX tree extraction | `AccessibilityService` | 45 ms (emulator) | not measured on hardware |
| AX tree trimming | Rust | 60 µs | was 107 µs before the streaming change |
| Action validation | Rust | < 1 µs | |
| Prompt assembly | Rust | 9 µs | |
| Action execution | `AccessibilityService` | not measured | |

**The model call dominates.** Everything else in the loop is, together, in the
millisecond range. This is the single most important fact about the app's
performance: micro-optimising the agent core cannot make the agent feel
faster. It can only reduce memory pressure and tail latency.

The optimisation that *was* worth doing (streaming the AX tree) was chosen
because it removes ~2 000 heap allocations per step, not because it saved
milliseconds.

## Memory

| Measure | Value | Target |
|---|---|---|
| Idle PSS | 68 MB | < 180 MB |
| Release APK | 2.76 MB | < 25 MB |
| Java heap (allocated / reserved) | 17 MB / 46 MB | |
| Native heap | 17 MB | |

### The rules the code follows

- **One frame at a time.** A screenshot is captured, converted to JPEG and
  released before the next step. No frame queue.
- **Bitmaps are recycled in place.** `Bitmap.createScaledBitmap` followed by
  `recycle()` on the source; the scaled copy is recycled in a `finally`.
- **The ImageReader holds at most 2 images** (`ImageReader.newInstance(w, h, RGBA_8888, 2)`).
- **The AX tree is trimmed in Rust before it is ever sent.** 120 nodes / 8 KB
  maximum — the model never sees more.
- **`largeHeap=false`.** The heap is the default size; the app does not ask
  for more.
- **The Rust profile is size-first**: `opt-level = "z"`, `lto = true`,
  `strip = "symbols"`, `codegen-units = 1`. `panic = "unwind"` is kept because
  it is required for the JNI `catch_unwind` boundary.
- **The native library is one 467 KB `.so`.** No bundled model weights.

### The native library is loaded, not extracted

The release build sets `android:extractNativeLibs="false"`, so the `.so` is
stored uncompressed in the APK and mapped directly. The device's
`nativeLibraryDir` stays empty. This avoids writing a copy of the library to
disk.

## Allocation behaviour in the agent core

Before this milestone, `trim_tree` deserialised the **entire** accessibility
array into a `Vec<Node>` — every node with three owned `String`s — only to keep
the first 120. For a 2 000-node dump that is ~6 000 string allocations per
agent step, all but 360 of them thrown away.

The streaming implementation deserialises at most 120 nodes and then skips the
remainder with `IgnoredAny`, which walks the JSON without allocating. Measured:
4.1× faster on a 2 000-node dump, and the peak allocation drops by roughly an
order of magnitude.

## Concurrency

| Work | Model |
|---|---|
| Agent loop | one coroutine on `Dispatchers.IO` inside a `SupervisorJob` |
| UI | Compose on the main thread |
| Provider HTTP | blocking OkHttp on the IO dispatcher, cancellable via `CloudClients.cancelActive()` |
| Rust | synchronous, on the calling (IO) thread |

There is no unbounded concurrency: exactly one agent loop runs at a time, and
`AgentService` refuses a second `onStartCommand` while `running` is true.

## Latency budgets

| Budget | Value | Status |
|---|---|---|
| Stop latency | < 1 s (`PLAN.md` REVIEW gate) | The service stops immediately; the provider call is cancelled. The `readTimeout` fallback of 90 s is bounded by `callTimeout`. |
| Frame wait | 2 s | Bounded, and logged when it expires. |
| Accessibility wait | 1.5 s per step | Bounded, part of the 40-step budget. |
| Model call | 90 s read timeout, 120 s call timeout | Bounded. |

## Known performance gaps

| Gap | Note |
|---|---|
| Screenshot resolution / quality never benchmarked | 960 px / q60 has never been compared with alternatives for accuracy, capture time, encoding time or payload size. `OPTIMIZATION_PLAN.md` §6.2. |
| No payload accounting | The app does not log the size of the request body, only the size of the JPEG. |
| AX extraction not profiled on hardware | 45 ms is an emulator figure. |
| No 16 KB page-size verification | Required for Android 15+ devices with 16 KB pages. |
| Peak PSS not measured | The emulator's accounting is not representative. |

## Reproducing

```bash
cd rust/agent-core
cargo run --release --example bench       # Rust hot path
cargo test --release                      # correctness

adb logcat -s AgentService:V              # per-phase timings on a device
adb shell dumpsys meminfo com.onscreen.agent   # memory
```
