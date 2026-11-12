//! agent-core: tiny, low-RAM brain helpers. No network, no models bundled.
//! Kotlin does HTTPS + Accessibility; Rust enforces schema + budgets.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::de::{IgnoredAny, SeqAccess, Visitor};
use serde::{Deserialize, Serialize};
use smallvec::SmallVec;
use std::fmt::Formatter;
use std::panic::{catch_unwind, AssertUnwindSafe};

// ---- Action schema (strict; unknown -> rejected) ----
#[derive(Debug, Deserialize, Serialize, PartialEq, Clone)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum Action {
    Click { id: u32 },
    LongClick { id: u32 },
    Input { id: u32, text: String },
    Scroll { dir: ScrollDir },
    Back,
    Home,
    OpenApp { pkg: String },
    Wait { ms: u32 },
    Done,
    NeedHelp { reason: String },
}

#[derive(Debug, Deserialize, Serialize, PartialEq, Clone)]
#[serde(rename_all = "lowercase")]
pub enum ScrollDir { Up, Down, Left, Right }

#[derive(Debug, Deserialize)]
struct Node { id: u32, text: String, cls: String }

const MAX_NODES: usize = 120;
const MAX_TEXT: usize = 48;
const MAX_BYTES: usize = 8 * 1024;
const MAX_HISTORY: usize = 6;

/// Upper bound on the goal text included in a prompt.
pub const MAX_GOAL_CHARS: usize = 500;

/// Upper bound on a single `wait` action. The Kotlin caller clamps to the same
/// value, but the limit is enforced here as well so the Rust core really is
/// the validation boundary and not just a convenience.
pub const MAX_WAIT_MS: u32 = 5_000;

/// Upper bound on the raw JSON accepted for one action.
pub const MAX_ACTION_BYTES: usize = 2_048;

/// Upper bound on the text an `input` action may type.
pub const MAX_INPUT_TEXT: usize = 500;

/// Upper bound on an `openApp` package name.
pub const MAX_PACKAGE_LEN: usize = 128;

/// At most `MAX_NODES` accessibility nodes, deserialised without allocating
/// the rest of the array.
struct BoundedNodes(Vec<Node>);

impl<'de> Deserialize<'de> for BoundedNodes {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = BoundedNodes;
            fn expecting(&self, f: &mut Formatter) -> std::fmt::Result {
                f.write_str("an array of accessibility nodes")
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
                let mut out = Vec::with_capacity(MAX_NODES);
                while out.len() < MAX_NODES {
                    match seq.next_element::<Node>()? {
                        Some(n) => out.push(n),
                        None => break,
                    }
                }
                // Consume the remainder of the array without materialising it.
                // `IgnoredAny` uses serde_json's fast skip path, so the tail
                // costs a scan rather than one String allocation per field.
                // It also leaves the array properly terminated, so malformed
                // input still fails exactly as it did before.
                while seq.next_element::<IgnoredAny>()?.is_some() {}
                Ok(BoundedNodes(out))
            }
        }
        d.deserialize_seq(V)
    }
}

/// Trim AX tree to a fixed RAM budget. Pure + testable.
///
/// Only the first [`MAX_NODES`] nodes are deserialised into `Node` structs;
/// the rest of the array is skipped without allocation. A large accessibility
/// dump therefore costs a scan of the tail rather than thousands of heap
/// allocations, which is what the previous `Vec<Node>` implementation did.
///
/// Strictness is unchanged: input that is not a JSON array, or an element that
/// fails to parse, yields an empty tree exactly as before.
pub fn trim_tree(json: &str) -> String {
    let mut de = serde_json::Deserializer::from_str(json);
    let nodes: Vec<Node> = match BoundedNodes::deserialize(&mut de) {
        Ok(v) => v.0,
        Err(_) => return String::new(),
    };
    let mut out: SmallVec<[String; 128]> = SmallVec::new();
    let mut bytes = 0usize;
    for n in nodes.into_iter().take(MAX_NODES) {
        let mut t: String = n.text.chars().take(MAX_TEXT).collect();
        t = t.replace(['\n', '\r'], " ");
        let line = format!("{}|{}|{}", n.id, n.cls, t);
        bytes += line.len();
        if bytes > MAX_BYTES { break; }
        out.push(line);
    }
    out.join("\n")
}

/// Parse + validate one model action. Rejects oversized input text (injection/RAM guard).
pub fn parse_action(json: &str) -> Result<Action, String> {
    if json.len() > MAX_ACTION_BYTES { return Err("action too large".into()); }
    let a: Action = serde_json::from_str(json).map_err(|e| format!("bad action: {e}"))?;
    if let Action::Input { text, .. } = &a {
        if text.len() > MAX_INPUT_TEXT { return Err("input text too long".into()); }
    }
    if let Action::OpenApp { pkg } = &a {
        if pkg.len() > MAX_PACKAGE_LEN || !pkg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_') {
            return Err("bad package".into());
        }
    }
    if let Action::Wait { ms } = &a {
        if *ms > MAX_WAIT_MS { return Err("wait too long".into()); }
    }
    Ok(a)
}

/// Build bounded prompt: goal + trimmed tree + last N steps. Caps tokens/RAM.
///
/// `history` is the newline-separated list of recent actions, oldest first.
/// The output format is byte-for-byte what the Kotlin service produced inline
/// before this function was wired up, so switching the caller over does not
/// change what the model sees.
pub fn build_prompt(goal: &str, tree: &str, history: &str) -> String {
    let goal: String = goal.chars().take(MAX_GOAL_CHARS).collect();
    let tree: String = tree.chars().take(MAX_BYTES).collect();
    let tail: Vec<&str> = history
        .split('\n')
        .filter(|s| !s.is_empty())
        .rev()
        .take(MAX_HISTORY)
        .collect();
    format!(
        "Goal:{goal}\nScreen:\n{tree}\nRecent:\n{}",
        tail.into_iter().rev().collect::<Vec<_>>().join("\n")
    )
}

// ---- On-device placeholders (UNVERIFIED: need NDK build + model files on
// first use; cfg-gated so the default cloud build never compiles them) ----
#[cfg(feature = "ondevice-stt")]
pub fn transcribe_ondevice(_wav: &[u8]) -> Result<String, String> {
    Err("whisper model not bundled — download tiny.en-q4 first".into())
}

#[cfg(feature = "ondevice-tts")]
pub fn speak_ondevice(_text: &str) -> Result<Vec<u8>, String> {
    Err("kitten-tts model not bundled".into())
}

#[cfg(feature = "ondevice-vlm")]
pub fn plan_ondevice(_prompt: &str) -> Result<String, String> {
    Err("gemma-3n weights not bundled".into())
}

// ---- JNI (thin; heavy work stays in pure fns above for testability) ----
//
// Every export is wrapped in `catch_unwind`. A Rust panic that unwound
// across the JNI frame would abort the whole Android process; here it is
// converted into the same fail-safe error shape the pure functions use.

/// Run `f`, converting a panic into `fallback` instead of unwinding into JNI.
fn guarded<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

#[no_mangle]
pub extern "system" fn Java_com_onscreen_agent_RustBridge_parseAction(
    mut env: JNIEnv, _cls: JClass, input: JString,
) -> jstring {
    let s: String = env.get_string(&input).map(|v| v.into()).unwrap_or_default();
    let out = guarded(String::new(), || {
        match parse_action(&s) {
            Ok(a) => serde_json::to_string(&a).unwrap_or_default(),
            Err(e) => format!(r#"{{"type":"needHelp","reason":{}}}"#, serde_json::to_string(&e).unwrap_or_default()),
        }
    });
    env.new_string(out).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_onscreen_agent_RustBridge_trimTree(
    mut env: JNIEnv, _cls: JClass, input: JString,
) -> jstring {
    let s: String = env.get_string(&input).map(|v| v.into()).unwrap_or_default();
    let out = guarded(String::new(), || trim_tree(&s));
    env.new_string(out).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Build the agent prompt (goal + trimmed tree + recent steps) with all the
/// size budgets applied in Rust. Mirrors the format the Kotlin service used
/// to assemble inline.
#[no_mangle]
pub extern "system" fn Java_com_onscreen_agent_RustBridge_buildPrompt(
    mut env: JNIEnv, _cls: JClass,
    goal: JString, tree: JString, history: JString,
) -> jstring {
    let g: String = env.get_string(&goal).map(|v| v.into()).unwrap_or_default();
    let t: String = env.get_string(&tree).map(|v| v.into()).unwrap_or_default();
    let h: String = env.get_string(&history).map(|v| v.into()).unwrap_or_default();
    let out = guarded(String::new(), || build_prompt(&g, &t, &h));
    env.new_string(out).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn node(id: u32, text: &str, cls: &str) -> String {
        format!(
            r#"{{"id":{id},"text":"{text}","cls":"{cls}"}}"#,
        )
    }

    // ---------- action parsing: every variant ----------

    #[test]
    fn parses_click_and_rejects_junk() {
        assert_eq!(parse_action(r#"{"type":"click","id":7}"#).unwrap(), Action::Click { id: 7 });
        assert!(parse_action(r#"{"type":"rm -rf"}"#).is_err());
    }

    #[test]
    fn parses_every_action_variant() {
        assert_eq!(parse_action(r#"{"type":"click","id":1}"#).unwrap(), Action::Click { id: 1 });
        assert_eq!(parse_action(r#"{"type":"longClick","id":2}"#).unwrap(), Action::LongClick { id: 2 });
        assert_eq!(
            parse_action(r#"{"type":"input","id":3,"text":"hi"}"#).unwrap(),
            Action::Input { id: 3, text: "hi".into() }
        );
        assert_eq!(parse_action(r#"{"type":"scroll","dir":"up"}"#).unwrap(), Action::Scroll { dir: ScrollDir::Up });
        assert_eq!(parse_action(r#"{"type":"scroll","dir":"down"}"#).unwrap(), Action::Scroll { dir: ScrollDir::Down });
        assert_eq!(parse_action(r#"{"type":"scroll","dir":"left"}"#).unwrap(), Action::Scroll { dir: ScrollDir::Left });
        assert_eq!(parse_action(r#"{"type":"scroll","dir":"right"}"#).unwrap(), Action::Scroll { dir: ScrollDir::Right });
        assert_eq!(parse_action(r#"{"type":"back"}"#).unwrap(), Action::Back);
        assert_eq!(parse_action(r#"{"type":"home"}"#).unwrap(), Action::Home);
        assert_eq!(parse_action(r#"{"type":"openApp","pkg":"com.example.app"}"#).unwrap(),
                   Action::OpenApp { pkg: "com.example.app".into() });
        assert_eq!(parse_action(r#"{"type":"wait","ms":250}"#).unwrap(), Action::Wait { ms: 250 });
        assert_eq!(parse_action(r#"{"type":"done"}"#).unwrap(), Action::Done);
        assert_eq!(parse_action(r#"{"type":"needHelp","reason":"stuck"}"#).unwrap(),
                   Action::NeedHelp { reason: "stuck".into() });
    }

    #[test]
    fn rejects_unknown_and_malformed_actions() {
        for bad in [
            r#"{"type":"rm -rf"}"#,
            r#"{"type":"click"}"#,          // missing id
            r#"{"type":"click","id":"7"}"#,  // id must be a number
            r#"{"type":"scroll","dir":"sideways"}"#,
            r#"{"type":"scroll"}"#,          // missing dir
            r#"{"type":"input","id":1}"#,    // missing text
            r#"{"type":"openApp"}"#,         // missing pkg
            r#"{"type":"wait"}"#,            // missing ms
            r#"{"type":"needHelp"}"#,        // missing reason
            r#"not json at all"#,
            r#""#,
            r#"{"type":123}"#,
        ] {
            assert!(parse_action(bad).is_err(), "should have rejected: {bad}");
        }
    }

    // ---------- boundary conditions ----------

    #[test]
    fn enforces_action_size_limit() {
        let at_limit = format!(r#"{{"type":"click","id":1,"pad":"{}"}}"#, "x".repeat(MAX_ACTION_BYTES - 40));
        assert!(at_limit.len() <= MAX_ACTION_BYTES);
        let over = format!(r#"{{"type":"click","id":1,"pad":"{}"}}"#, "x".repeat(MAX_ACTION_BYTES));
        assert!(over.len() > MAX_ACTION_BYTES);
        assert!(parse_action(&over).is_err());
    }

    #[test]
    fn enforces_input_text_limit() {
        let ok = format!(r#"{{"type":"input","id":1,"text":"{}"}}"#, "a".repeat(MAX_INPUT_TEXT));
        assert!(parse_action(&ok).is_ok());
        let too_long = format!(r#"{{"type":"input","id":1,"text":"{}"}}"#, "a".repeat(MAX_INPUT_TEXT + 1));
        assert!(parse_action(&too_long).is_err());
    }

    #[test]
    fn enforces_package_name_rules() {
        assert!(parse_action(r#"{"type":"openApp","pkg":"com.example.app"}"#).is_ok());
        assert!(parse_action(r#"{"type":"openApp","pkg":"com.example.app_2"}"#).is_ok());
        // path separators and shell metacharacters must never reach an intent
        assert!(parse_action(r#"{"type":"openApp","pkg":"com.example/app"}"#).is_err());
        assert!(parse_action(r#"{"type":"openApp","pkg":"com.example;rm -rf /"}"#).is_err());
        assert!(parse_action(r#"{"type":"openApp","pkg":"com.example app"}"#).is_err());
        let too_long = format!(r#"{{"type":"openApp","pkg":"{}"}}"#, "a".repeat(MAX_PACKAGE_LEN + 1));
        assert!(parse_action(&too_long).is_err());
    }

    #[test]
    fn enforces_wait_limit() {
        assert!(parse_action(&format!(r#"{{"type":"wait","ms":{MAX_WAIT_MS}}}"#)).is_ok());
        assert!(parse_action(&format!(r#"{{"type":"wait","ms":{}}}"#, MAX_WAIT_MS + 1)).is_err());
    }

    // ---------- tree trimming ----------

    #[test]
    fn trims_to_budget() {
        let big = (0..500).map(|i| format!(r#"{{"id":{i},"text":"x","cls":"B"}}"#)).collect::<Vec<_>>().join(",");
        let t = trim_tree(&format!("[{big}]"));
        assert!(t.len() <= MAX_BYTES + 64);
        assert!(t.lines().count() <= MAX_NODES);
        // Guard against a vacuous pass: the trim must actually produce content.
        assert_eq!(t.lines().count(), MAX_NODES);
        assert!(t.starts_with("0|B|x"), "unexpected trim output: {t}");
    }

    #[test]
    fn trims_streams_and_stops_early() {
        // 2 000 nodes must produce exactly the same result as 120: only the
        // first MAX_NODES are ever deserialised.
        let many: Vec<String> = (0..2000).map(|i| node(i, "x", "B")).collect();
        let few: Vec<String> = (0..120).map(|i| node(i, "x", "B")).collect();
        let a = trim_tree(&format!("[{}]", many.join(",")));
        let b = trim_tree(&format!("[{}]", few.join(",")));
        assert_eq!(a, b);
        assert_eq!(a.lines().count(), MAX_NODES);
    }

    #[test]
    fn trims_a_short_tree_completely() {
        // Fewer nodes than the cap must all survive.
        let t = trim_tree(r#"[{"id":1,"text":"a","cls":"b"},{"id":2,"text":"c","cls":"d"}]"#);
        assert_eq!(t.lines().count(), 2);
        assert_eq!(t, "1|b|a\n2|d|c");
    }

    #[test]
    fn trim_handles_garbage_without_panicking() {
        // Unparseable input yields an empty tree rather than panicking.
        assert_eq!(trim_tree("not json"), "");
        assert_eq!(trim_tree(""), "");
        assert_eq!(trim_tree("{}"), "");
        assert_eq!(trim_tree(r#"[{"id":1,"text":"a","cls":"b"}"#), "");
    }

    #[test]
    fn trim_caps_text_and_flattens_newlines() {
        let long = "x".repeat(200);
        let t = trim_tree(&format!(r#"[{}]"#, node(1, &long, "B")));
        assert!(t.starts_with("1|B|"), "unexpected trim output: {t}");
        // text is capped at MAX_TEXT
        assert_eq!(t.len(), format!("1|B|{}", "x".repeat(MAX_TEXT)).len());

        let t2 = trim_tree(r#"[{"id":2,"text":"a\nb\rc","cls":"B"}]"#);
        assert_eq!(t2.lines().count(), 1);
        assert!(!t2.contains('\r'));
        assert!(!t2.contains('\n'));
    }

    // ---------- prompt building ----------

    #[test]
    fn build_prompt_matches_the_kotlin_format() {
        // The Kotlin service used to assemble exactly this string inline.
        assert_eq!(build_prompt("goal", "tree", ""), "Goal:goal\nScreen:\ntree\nRecent:\n");
        assert_eq!(build_prompt("goal", "tree", "a\nb"), "Goal:goal\nScreen:\ntree\nRecent:\na\nb");
    }

    #[test]
    fn build_prompt_caps_goal_tree_and_history() {
        let goal = "g".repeat(2000);
        let tree = "t".repeat(20_000);
        let hist = (0..50).map(|i| format!("h{i}")).collect::<Vec<_>>().join("\n");
        let p = build_prompt(&goal, &tree, &hist);
        assert!(p.len() < 9_000, "prompt should stay bounded, got {}", p.len());
        // only the most recent MAX_HISTORY entries survive
        assert!(p.contains("h49"));
        assert!(!p.contains("h0\n"));
    }

    // ---------- JNI boundary ----------

    #[test]
    fn guarded_converts_panics_into_a_fallback() {
        let out: String = guarded("fallback".to_string(), || panic!("boom"));
        assert_eq!(out, "fallback");
    }
}
