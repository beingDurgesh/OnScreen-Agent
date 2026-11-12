# OnScreen-Agent — Progress (updated: v0.4.2)

Two bars because they differ: **Written** = code exists. **Proven** = compiled/ran.
Everything below compiles, the CI build is green, and a **signed release APK is
published**. The app has now been **run on an Android 15 emulator** — the
accessibility dump, the Rust tree trim and the screenshot capture all work —
but it has **not** been run against a live cloud model, so end-to-end task
success is still unproven.

```
Written ██████████████░░░░░░  70%
Proven  ███████░░░░░░░░░░░░░  35%  (runs on Android 15 + CI green + signed release; no live model run)
```

## Tracks

| Track | Written | Proven | Note |
|---|---|---|---|
| Spec / docs (SPEC, PLAN, README) | ██████████ 100% | ██████████ 100% | Done |
| Settings UI + BYOK store | ██████████ 100% | ███░░░░░░░░ 30% | UI renders and scrolls on Android 15 |
| Screen capture | ██████████ 100% | ████░░░░░░░ 40% | Captures a real frame (43 KB JPEG) on Android 15 |
| Agent loop + stable IDs | ██████████ 100% | ████░░░░░░░ 40% | Loop runs: 43 AX nodes → Rust trim → JPEG |
| Cloud VLM/STT/TTS calls | ████████░░ 80% | █░░░░░░░░░░ 10% | Compiles; Gemini TTS + KittenTTS still stubs |
| Rust core (parse/budget) | ██████████ 100% | ██████████ 100% | `cargo test` green locally **and** in CI; arm64 `.so` ships in the APK and loads on device |
| On-device (whisper/gemma/kitten) | █████░░░░░░ 50% | ░░░░░░░░░░ 0% | Downloads UI + verified URLs done; inference wiring TODO |
| Build tooling (gradlew, CI) | ██████████ 100% | ██████████ 100% | CI green, APK + SHA-256 artifact uploaded |
| Device test + metrics | ███░░░░░░░░ 30% | ███░░░░░░░░ 30% | Android 15 emulator: launches, loops, captures. Idle PSS 68 MB. No physical device yet |
| Signed release | ██████████ 100% | ███░░░░░░░░ 30% | Signed release published — still no live-model run |

## TODO

### Now (this host)
- [x] Spec, plan, scaffold
- [x] Full agent loop in code
- [x] Gradle wrapper committed
- [x] 38 host-side checks green
- [x] The code actually compiles (it never had before — see the fix commit)

### After push (needs CI + you)
- [x] `git push -u origin main`
- [x] First CI build green — run [43144650617](https://github.com/beingDurgesh/OnScreen-Agent/actions/runs/43144650617) (3m49s, all 13 steps green)
- [x] `cargo test` green in CI — same run
- [x] Build the real arm64 `.so` in CI (was silently skipped before; APK now ships `lib/arm64-v8a/libagent_core.so`, 467 KB)
- [x] Measure APK size: **7.9 MB** < 25 MB target (CI artifact)
- [x] Testing APK: `git tag v0.4-testing && git push origin v0.4-testing` → pre-release APK under Releases
- [x] Install on Android 15, grant Accessibility + screen capture
      (Android 15 emulator, API 35: accessibility service binds, agent loop runs,
      screenshot capture returns a 44 KB JPEG)
- [x] Fix the defects found by running it - see
      [`docs/ENGINEERING_AUDIT.md`](docs/ENGINEERING_AUDIT.md) and
      [`docs/benchmarks/RESULTS.md`](docs/benchmarks/RESULTS.md):
      one-VirtualDisplay-per-projection, the `nextAction` recursion,
      `MediaProjection.Callback`, `POST_NOTIFICATIONS`, `START_STICKY`,
      provider cancellation, structured errors, download integrity, the Gemini
      key out of the URL, and the Rust panic boundary
- [ ] Run 3 real tasks end-to-end, fix runtime issues
      (blocked: the Android 15 capture dialog defaults to "A single app",
      which Android revokes for the agent's own window - use "Entire screen") (needs a live VLM key)
- [x] Measure idle PSS: **68 MB** < 180 MB target (Android 15 emulator)
- [x] Trim the AX tree without allocating the whole dump - **4.1x faster** on
      a 2 000-node tree, byte-identical output
- [x] Rust unit tests: **2 -> 15**
- [ ] Gemini TTS speech-config flow (currently stub)
- [x] Signed release APK + SHA-256 + GitHub Release — [v0.4.2](https://github.com/beingDurgesh/OnScreen-Agent/releases/tag/v0.4.2)
      (`OnScreen-Agent-v0.4.2.apk`, release-signed, arm64-v8a, SHA-256 published alongside it) The release workflow now fails if the tag and versionName disagree.

### Later (on-device track)
- [x] Model download manager (verified HF URLs, progress/cancel/delete, HF token for Gemma)
- [ ] whisper.cpp inference on downloaded `.bin`
- [ ] Gemma 3n inference on downloaded `.gguf`
- [ ] KittenTTS playback from downloaded `.onnx`

## How to update this file
Check a box when its exit proof exists (CI log link, device screenshot,
metric number — not "code written"). Move its bar only then.
