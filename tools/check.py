"""Host-side sanity checks (no Android SDK needed). Run: python onscreen-agent/tools/check.py"""
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
]:
    ok(re.search(pat, rs) is not None, f"rust: {label}")

kt = (root / "app/src/main/java/com/onscreen/agent").glob("*.kt")
names = {p.name for p in kt}
for f in ["MainActivity.kt", "AgentService.kt", "AgentAccessibilityService.kt",
          "SettingsStore.kt", "CloudClients.kt", "RustBridge.kt", "ScreenCapture.kt"]:
    ok(f in names, f"kotlin: {f}")

ss = (root / "app/src/main/java/com/onscreen/agent/SettingsStore.kt").read_text()
ok("STT_MODELS" in ss and "VLM_MODELS" in ss and "TTS_MODELS" in ss, "3 BYOK slots defined")
ok("EncryptedSharedPreferences" in ss, "keys encrypted at rest")

cargo = (root / "rust/agent-core/Cargo.toml").read_text()
ok('opt-level = "z"' in cargo and "lto = true" in cargo, "rust size/RAM profile")

wf = (root / ".github/workflows/build-apk.yml").read_text()
ok("assembleDebug" in wf and "cargo test" in wf, "CI builds APK + runs rust tests")

print(f"\n{len(fails)} failures")
sys.exit(1 if fails else 0)
