# PLAN → BUILD → VERIFY → REVIEW → SHIP

## Architecture
```
┌─ Kotlin shell (Android framework) ─────────────┐  ┌─ Rust core (JNI) ──────┐
│ MainActivity (Compose) : 3 BYOK cards + Play  │  │ agent-core/lib.rs      │
│ AgentService (FG service, loop driver)         │◄─┤ parseAction()          │
│ AgentAccessibilityService (tap/type/scroll)   │  │ buildPrompt()          │
│ ScreenCapture (MediaProjection, 720p JPEG)    │  │ trimTree(), budget()   │
│ SettingsStore (Encrypted prefs)               │  │ cloud router (https)   │
│ CloudClients (OkHttp, SSE)                    │  └────────────────────────┘
└───────────────────────────────────────────────┘   ondevice-* features OFF by default
```

Why Kotlin + Rust, not pure Rust: `AccessibilityService`, `MediaProjection`, runtime permissions, and Play policy all require the Android framework. Rust owns the hot loop (parsing, budgeting, prompt building) for size/RAM wins.

## RAM optimizations
- Rust: `opt-level=z`, `lto=true`, `strip=symbols`, `codegen-units=1`, `panic="unwind"` (JNI-safe), `smallvec`, `serde` streaming, no regex, no tokio in core (caller supplies dispatcher).
- Kotlin: screenshot downscale to 960px, recycle bitmaps, AX dump capped, `LargeHeap=false`, Coil/OkHttp connection reuse, WorkManager off.
- APK: `arm64-v8a` only for cloud drop, `resConfigs en`, shrink+minify on, no bundled `.bin/.onnx`.

## File map
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`
- `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/onscreen/agent/{MainActivity,AgentService,AgentAccessibilityService,ScreenCapture,SettingsStore,CloudClients,RustBridge}.kt`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `rust/agent-core/{Cargo.toml,src/lib.rs}`
- `.github/workflows/build-apk.yml` — this is how the APK is compiled (no local SDK here)

## VERIFY
1. `python tools/check.py` — validates manifest minSdk/target, action-schema fixtures via Rust logic mirror, settings keys present.
2. CI `assembleDebug` + `cargo test -p agent-core`.
3. Manual on Pixel Android 15: grant Accessibility + ScreenCapture + Mic, paste key, press Play, Stop works.

## REVIEW gate
- No hardcoded keys, no banking bypass, stop latency <1s, token meter visible.

## SHIP
- Cloud debug APK from CI artifacts (`on-screen-cloud-arm64-debug.apk`), plus SHA-256. Play release needs signing + disclosure + privacy policy.
