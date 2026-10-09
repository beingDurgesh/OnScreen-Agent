# OnScreen-Agent — Progress (updated: v0.3, commit 32a2174)

Two bars because they differ: **Written** = code exists. **Proven** = compiled/ran.
Nothing here is proven yet — no SDK on this host.

```
Written ██████████████░░░░░░  70%
Proven  ██░░░░░░░░░░░░░░░░░░  10%  (38/38 host-side static checks only)
```

## Tracks

| Track | Written | Proven | Note |
|---|---|---|---|
| Spec / docs (SPEC, PLAN, README) | ██████████ 100% | ██████████ 100% | Done |
| Settings UI + BYOK store | ██████████ 100% | ░░░░░░░░░░ 0% | Written, never compiled |
| Screen capture | ██████████ 100% | ░░░░░░░░░░ 0% | Full code, never run |
| Agent loop + stable IDs | ██████████ 100% | ░░░░░░░░░░ 0% | Full code, never run |
| Cloud VLM/STT/TTS calls | ████████░░ 80% | ░░░░░░░░░░ 0% | Gemini TTS + KittenTTS still stubs |
| Rust core (parse/budget) | ██████████ 100% | ██░░░░░░░░ 20% | Tests written, never executed |
| On-device (whisper/gemma/kitten) | ██░░░░░░░░ 20% | ░░░░░░░░░░ 0% | Placeholders only |
| Build tooling (gradlew, CI) | ██████████ 100% | ░░░░░░░░░░ 0% | First CI run pending |
| Device test + metrics | ░░░░░░░░░░ 0% | ░░░░░░░░░░ 0% | Needs Android 15 device |
| Signed release | ░░░░░░░░░░ 0% | ░░░░░░░░░░ 0% | After device test |

## TODO

### Now (this host)
- [x] Spec, plan, scaffold
- [x] Full agent loop in code
- [x] Gradle wrapper committed
- [x] 38 host-side checks green

### After push (needs CI + you)
- [ ] `git push -u origin main`
- [ ] First CI build green (expect 1–3 compile-fix rounds — paste logs here)
- [ ] `cargo test` green in CI
- [ ] Install debug APK on Android 15, grant Accessibility + capture + mic
- [ ] Run 3 real tasks end-to-end, fix runtime issues
- [ ] Measure: APK <25MB, idle PSS <180MB
- [ ] Gemini TTS speech-config flow (currently stub)
- [ ] Signed release APK + SHA-256 + GitHub Release

### Later (on-device track)
- [ ] whisper.cpp model download + mmap + transcribe wiring
- [ ] Gemma 3n E2B/E4B Q4 inference path
- [ ] KittenTTS `.onnx` download + playback
- [ ] Heat/RAM profiling for on-device mode

## How to update this file
Check a box when its exit proof exists (CI log link, device screenshot,
metric number — not "code written"). Move its bar only then.
