//! agent-core: tiny, low-RAM brain helpers. No network, no models bundled.
//! Kotlin does HTTPS + Accessibility; Rust enforces schema + budgets.

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use smallvec::SmallVec;

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

/// Trim AX tree to a fixed RAM budget. Pure + testable.
pub fn trim_tree(json: &str) -> String {
    let nodes: Vec<Node> = serde_json::from_str(json).unwrap_or_default();
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
    if json.len() > 2048 { return Err("action too large".into()); }
    let a: Action = serde_json::from_str(json).map_err(|e| format!("bad action: {e}"))?;
    if let Action::Input { text, .. } = &a {
        if text.len() > 500 { return Err("input text too long".into()); }
    }
    if let Action::OpenApp { pkg } = &a {
        if pkg.len() > 128 || !pkg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_') {
            return Err("bad package".into());
        }
    }
    Ok(a)
}

/// Build bounded prompt: goal + trimmed tree + last N steps. Caps tokens/RAM.
pub fn build_prompt(goal: &str, tree: &str, history_json: &str) -> String {
    let goal: String = goal.chars().take(500).collect();
    let tree: String = tree.chars().take(MAX_BYTES).collect();
    let hist: Vec<String> = serde_json::from_str(history_json).unwrap_or_default();
    let tail: Vec<&String> = hist.iter().rev().take(MAX_HISTORY).collect();
    format!(
        "Goal:{goal}\nScreen:\n{tree}\nRecent:\n{}\nReply with ONE JSON action only.",
        tail.into_iter().rev().map(|s| s.as_str()).collect::<Vec<_>>().join("\n")
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
#[no_mangle]
pub extern "system" fn Java_com_onscreen_agent_RustBridge_parseAction(
    mut env: JNIEnv, _cls: JClass, input: JString,
) -> jstring {
    let s: String = env.get_string(&input).map(|v| v.into()).unwrap_or_default();
    let out = match parse_action(&s) {
        Ok(a) => serde_json::to_string(&a).unwrap_or_default(),
        Err(e) => format!(r#"{{"type":"needHelp","reason":{}}}"#, serde_json::to_string(&e).unwrap_or_default()),
    };
    env.new_string(out).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_onscreen_agent_RustBridge_trimTree(
    mut env: JNIEnv, _cls: JClass, input: JString,
) -> jstring {
    let s: String = env.get_string(&input).map(|v| v.into()).unwrap_or_default();
    let out = trim_tree(&s);
    env.new_string(out).map(|v| v.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_click_and_rejects_junk() {
        assert_eq!(parse_action(r#"{"type":"click","id":7}"#).unwrap(), Action::Click { id: 7 });
        assert!(parse_action(r#"{"type":"rm -rf"}"#).is_err());
    }
    #[test]
    fn trims_to_budget() {
        let big = (0..500).map(|i| format!(r#"{{"id":{i},"text":"x","cls":"B"}}"#)).collect::<Vec<_>>().join(",");
        let t = trim_tree(&format!("[{big}]"));
        assert!(t.len() <= MAX_BYTES + 64);
        assert!(t.lines().count() <= MAX_NODES);
    }
}
