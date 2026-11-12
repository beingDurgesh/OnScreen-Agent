# OnScreen-Agent — Architecture

Current-state documentation. Where `PLAN.md` and `SPEC.md` disagree with the
code, **the code wins** — see the drift table in
[`ENGINEERING_AUDIT.md`](ENGINEERING_AUDIT.md) §9.

## Component map

```
┌───────────────────────── Android (Kotlin) ──────────────────────────┐
│                                                                     │
│  MainActivity.kt ── Compose settings UI                            │
│      │  3 BYOK cards, on-device model list, goal field, Play/Stop  │
│      │  SettingsStore.kt ── DataStore (plain) + EncryptedSharedPrefs│
│      │                          (API keys)                           │
│      ▼                                                              │
│  AgentService.kt ── foreground service ── the agent loop            │
│      │                                                              │
│      ├── AgentAccessibilityService.kt ── AX dump + act on nodes     │
│      ├── ScreenCapture.kt ── MediaProjection → JPEG                │
│      ├── CloudClients.kt ── OkHttp → OpenAI / Gemini / OpenRouter   │
│      └── RustBridge.kt ── JNI facade                                │
│                                                                     │
│  ModelManager.kt ── on-device model downloads (+ integrity check)    │
│  ModelsSection.kt ── download UI                                    │
└─────────────────────────────────────────────────────────────────────┘
                              │ JNI
┌──────────────────────── Rust (agent-core) ─────────────────────────┐
│  parse_action()  strict action schema + validation                  │
│  trim_tree()     AX tree → budgeted text (streaming)                │
│  build_prompt()  goal + tree + history, with size budgets            │
│  ondevice-*      cfg-gated placeholders (return Err today)          │
└─────────────────────────────────────────────────────────────────────┘
```

## The agent loop

`AgentService.onStartCommand` (the only place the loop lives):

```
startForeground(SPECIAL_USE | MEDIA_PROJECTION)
repeat(MAX_STEPS = 40) {
    if (!running) { stopSelf(); return }
    svc = AgentAccessibilityService.instance
    if (svc == null) { delay(1500); continue }          // bounded retry
    dump = svc.dumpIndexed()                            // stable ids, same dump for act
    tree = RustBridge.trimTreeSafe(dump.text)           // Rust: 120 nodes / 8 KB
    key  = SettingsStore.getKey(ctx, "vlm")
    if (key.isBlank()) { speakStatus(...); stopSelf(); return }
    jpeg = ScreenCapture.captureJpeg(ctx)               // one VirtualDisplay per projection
    user = RustBridge.buildPromptSafe(goal, tree, last 6 actions)
    raw  = CloudClients.nextActionMultimodal(...)       // blocking HTTP, cancellable
    valid= RustBridge.parseActionSafe(raw)              // invalid → needHelp
    when (type) {
        done / needHelp → speakStatus + stopSelf
        openApp         → launch intent
        wait            → bounded delay
        else            → svc.actOnDump(dump, valid)    // same dump the model saw
    }
}
```

### Design decisions worth keeping

- **Rust owns the schema and the budgets.** A confused model cannot act on a
  malformed action: `parse_action` rejects it and the JNI wrapper converts the
  rejection into `{"type":"needHelp"}`, which the loop treats as "stop and ask".
- **One dump, one action.** The AX tree is dumped once per step and the same
  `Dump` object is used for the action, so node ids cannot go stale between
  perception and action.
- **Stop wins.** `stopSelf()` is called on every exit path, and the foreground
  notification carries the user-visible Stop affordance.
- **Bounded retries.** The step cap (40), the frame wait (2 s), the wait
  action (5 s) and the accessibility wait (1.5 s per step) are all bounded.

### Deliberate non-goals

- **No formal state machine.** The loop is a bounded `repeat` with explicit
  exits. An Idle/Planning/Executing state machine would be a rewrite of working
  code with no measured benefit.
- **No provider fallback.** If the selected provider fails, the agent stops
  and says so. It never silently re-routes screen content to a different
  provider.
- **No local inference yet.** The on-device path is a download + integrity
  check only; the runtime is a stub.

## Data flow of one step

| Stage | Where | Cost (measured) |
|---|---|---|
| AX tree extraction | Kotlin, `AccessibilityService` | 45 ms on the emulator |
| Tree trimming | Rust via JNI | < 1 ms (60 µs typical) |
| Screenshot | Kotlin, `MediaProjection` | 2 356 ms on the emulator (no frame — see the audit) |
| Prompt assembly | Rust via JNI | ~9 µs |
| Model request | Kotlin, OkHttp | dominated by the provider |
| Action validation | Rust via JNI | < 1 µs |
| Action execution | Kotlin, `AccessibilityService` | not measured |

## Threading

| Work | Thread |
|---|---|
| Compose UI | main |
| Agent loop | `Dispatchers.IO` inside a `SupervisorJob` scope |
| HTTP | blocking OkHttp call on `Dispatchers.IO` (cancellable via `CloudClients.cancelActive()`) |
| Rust | called synchronously from the IO dispatcher |

The agent loop never touches the main thread. `speakStatus` blocks on
`MediaPlayer.prepare()` — acceptable on the IO dispatcher, and the player is
released in a `finally`.

## Process and service lifecycle

- `AgentService` is a foreground service, `START_NOT_STICKY`, so the system
  will **not** resurrect it after process death. A null intent is refused
  outright (there would be no goal and no capture grant to continue with).
- `AgentAccessibilityService` is bound by the system.
- `ScreenCapture` owns the `MediaProjection`, its `Callback` and the single
  `VirtualDisplay`; `release()` tears all three down.

## Build outputs

| Output | Produced by | Notes |
|---|---|---|
| `libagent_core.so` (arm64-v8a) | `cargo ndk` in CI | **not** in git; the build instructions cover it |
| `app-debug.apk` | `gradlew :app:assembleDebug` | |
| `app-release.apk` | `gradlew :app:assembleRelease` | signed from repo secrets |
| `rust/agent-core/target/**` | cargo | local only, gitignored |

## Adding to the codebase

- **New action type** → add the variant to `Action` in `rust/agent-core/src/lib.rs`,
  handle it in `AgentAccessibilityService.actOnDump`, and add it to the system
  prompt in `AgentService`. Extend the Rust tests.
- **New provider** → add a branch in `CloudClients`; keep the response shape
  `{"type": ...}` so the Rust parser stays the single validation point.
- **New setting** → add the key to `SettingsStore.P`; remember that API keys
  belong in `EncryptedSharedPreferences`, not DataStore.
