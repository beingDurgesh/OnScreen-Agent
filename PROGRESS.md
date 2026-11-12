# OnScreen-Agent — Progress (updated: v0.4, commit 7f8e669)

Two bars because they differ: **Written** = code exists. **Proven** = compiled/ran.
Everything below compiles and the CI build is green. **Nothing has run on a
device yet** — so "Proven" here means "it builds and its tests pass", not
"it works on a phone".

```
Written ██████████████░░░░░░  70%
Proven  ███░░░░░░░░░░░░░░░░░░  20%  (compiles + CI green + cargo tests; no device run)
```

## Tracks

| Track | Written | Proven | Note |
|---|---|---|---|
| Spec / docs (SPEC, PLAN, README) | ██████████ 100% | ██████████ 100% | Done |
| Settings UI + BYOK store | ██████████ 100% | ██░░░░░░░░░░ 20% | Compiles; never run on a device |
| Screen capture | ██████████ 100% | ██░░░░░░░░░░ 20% | Compiles; `createVirtualDisplay` API-36-correct; never run |
| Agent loop + stable IDs | ██████████ 100% | ██░░░░░░░░░░ 20% | Compiles; never run |
| Cloud VLM/STT/TTS calls | ████████░░ 80% | █░░░░░░░░░░ 10% | Compiles; Gemini TTS + KittenTTS still stubs |
| Rust core (parse/budget) | ██████████ 100% | ██████████ 100% | `cargo test` green locally **and** in CI; arm64 `.so` shipped in the APK |
| On-device (whisper/gemma/kitten) | █████░░░░░░ 50% | ░░░░░░░░░░ 0% | Downloads UI + verified URLs done; inference wiring TODO |
| Build tooling (gradlew, CI) | ██████████ 100% | ██████████ 100% | CI green, APK + SHA-256 artifact uploaded |
| Device test + metrics | ░░░░░░░░░░ 0% | ░░░░░░░░░░ 0% | APK size measured; idle PSS + real tasks still need a device |
| Signed release | ░░░░░░░░░░ 0% | ░░░░░░░░░░ 0% | Testing pre-release only; signed release after device test |

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
- [ ] Install debug APK on Android 15, grant Accessibility + capture + mic
- [ ] Run 3 real tasks end-to-end, fix runtime issues
- [ ] Measure idle PSS < 180 MB (needs a device)
- [ ] Gemini TTS speech-config flow (currently stub)
- [ ] Signed release APK + SHA-256 + GitHub Release

### Later (on-device track)
- [x] Model download manager (verified HF URLs, progress/cancel/delete, HF token for Gemma)
- [ ] whisper.cpp inference on downloaded `.bin`
- [ ] Gemma 3n inference on downloaded `.gguf`
- [ ] KittenTTS playback from downloaded `.onnx`

## How to update this file
Check a box when its exit proof exists (CI log link, device screenshot,
metric number — not "code written"). Move its bar only then.
