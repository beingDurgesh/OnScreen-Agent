"""Host-side sanity checks (no Android SDK needed). Run: python tools/check.py"""
import pathlib, re, sys

root = pathlib.Path(__file__).resolve().parents[1]
fails = []
def ok(cond, msg):
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond: fails.append(msg)

manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
ok('android:minSdkVersion="35"' in manifest, "manifest minSdk 35 (Android 15+)")
ok('android:targetSdkVersion="35"' in manifest, "manifest targetSdk 35")
ok("BIND_ACCESSIBILITY_SERVICE" in manifest, "accessibility service declared")
ok("FOREGROUND_SERVICE_SPECIAL_USE" in manifest, "FGS specialUse declared")

rs = (root / "rust/agent-core/src/lib.rs").read_text()
for pat, label in [
    (r"MAX_NODES.*120", "AX node cap"),
    (r"MAX_BYTES.*8 \* 1024", "8KB prompt cap"),
    (r"parse_action", "strict action parser"),
    (r"trim_tree", "tree trimmer"),
    (r"#\[cfg\(test\)\]", "rust unit tests present"),
    (r"transcribe_ondevice", "on-device STT placeholder"),
    (r"speak_ondevice", "on-device TTS placeholder"),
    (r"plan_ondevice", "on-device VLM placeholder"),
]:
    ok(re.search(pat, rs) is not None, f"rust: {label}")

ktdir = root / "app/src/main/java/com/onscreen/agent"
src = {p.name: p.read_text() for p in ktdir.glob("*.kt")}
for f in ["MainActivity.kt", "AgentService.kt", "AgentAccessibilityService.kt",
          "SettingsStore.kt", "CloudClients.kt", "RustBridge.kt", "ScreenCapture.kt"]:
    ok(f in src, f"kotlin: {f}")

ss = src["SettingsStore.kt"]
ok("STT_MODELS" in ss and "VLM_MODELS" in ss and "TTS_MODELS" in ss, "3 BYOK slots defined")
ok("EncryptedSharedPreferences" in ss, "keys encrypted at rest")

# Full-loop wiring (Phase 2)
sc = src["ScreenCapture.kt"]
ok("acquireLatestImage" in sc and "ImageReader" in sc, "capture: ImageReader frame path")
ok("compress" in sc and "JPEG" in sc and "MAX_EDGE" in sc, "capture: downscale + JPEG")
ok(sc.count("close()") >= 2 and "release()" in sc, "capture: surfaces/images closed")

ax = src["AgentAccessibilityService.kt"]
ok("dumpIndexed" in ax and "actOnDump" in ax, "AX: stable same-dump ids")

cc = src["CloudClients.kt"]
ok("nextActionMultimodal" in cc, "VLM: multimodal entry")
ok("image_url" in cc and "inline_data" in cc, "VLM: image parts for OpenAI + Gemini")
ok("audio/transcriptions" in cc, "STT: cloud transcription call")
ok("audio/speech" in cc, "TTS: cloud synthesis call")

ag = src["AgentService.kt"]
ok("captureJpeg" in ag and "nextActionMultimodal" in ag, "loop: screenshot -> VLM")
ok("actOnDump" in ag, "loop: acts on same dump")
ok("speakStatus" in ag, "loop: spoken status")

ma = src["MainActivity.kt"]
ok("MediaRecorder" in ma and "transcribeMicFile" in ma, "UI: mic -> transcription")
ok("intentForResult" in ma and "putExtra(\"rc\"" in ma, "UI: capture permission -> service")

cargo = (root / "rust/agent-core/Cargo.toml").read_text()
ok('opt-level = "z"' in cargo and "lto = true" in cargo, "rust size/RAM profile")

# Build tooling present
ok((root / "gradlew").exists() and (root / "gradlew.bat").exists(), "gradle launcher scripts present")
jar = root / "gradle/wrapper/gradle-wrapper.jar"
ok(jar.exists() and jar.stat().st_size > 10_000, "gradle wrapper jar present")

wf = (root / ".github/workflows/build-apk.yml").read_text()
ok("assembleDebug" in wf and "cargo test" in wf, "CI builds APK + runs rust tests")

print(f"\n{len(fails)} failures")
sys.exit(1 if fails else 0)
