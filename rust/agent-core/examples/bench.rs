//! Reproducible micro-benchmark for the agent-core hot path.
//!
//! Run:  cargo run --release --example bench
//!
//! This is a dev-only target. It is NOT part of the cdylib that ships in the
//! APK (cargo only builds it for `cargo test` / `cargo run --example`), and it
//! pulls in no extra dependencies.
//!
//! Every number it prints is measured on the machine that runs it. Nothing
//! here is simulated or extrapolated.

use agent_core::{build_prompt, parse_action, trim_tree};
use std::time::Instant;

/// Build a node array that looks like a real Android accessibility dump:
/// a mix of class names and text lengths, not a uniform synthetic blob.
fn ax_dump(node_count: usize) -> String {
    let classes = [
        "android.widget.TextView",
        "android.widget.Button",
        "android.widget.ImageView",
        "android.view.ViewGroup",
        "android.widget.EditText",
        "android.widget.CheckBox",
    ];
    let texts = [
        "Settings",
        "Network & internet",
        "Connected devices",
        "Display",
        "Sounds & vibration",
        "Notifications",
        "Battery",
        "Storage",
        "Apps",
        "Accessibility",
        "Turn on Dark theme in Settings",
        "",
        "42%",
    ];
    let mut out = String::with_capacity(node_count * 80);
    out.push('[');
    for i in 0..node_count {
        if i > 0 {
            out.push(',');
        }
        let cls = classes[i % classes.len()];
        let text = texts[(i * 7) % texts.len()];
        out.push_str(&format!(
            r#"{{"id":{},"text":{},"cls":{}}}"#,
            i,
            serde_json::to_string(text).unwrap(),
            serde_json::to_string(cls).unwrap()
        ));
    }
    out.push(']');
    out
}

/// Time `f` over `iters` calls after `warmup` untimed calls.
/// Returns (min_us, median_us, mean_us) in microseconds.
fn bench<F: FnMut()>(label: &str, warmup: u32, iters: u32, mut f: F) {
    for _ in 0..warmup {
        f();
    }
    let mut samples = Vec::with_capacity(iters as usize);
    for _ in 0..iters {
        let t = Instant::now();
        f();
        samples.push(t.elapsed().as_nanos() as f64 / 1000.0);
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let min = samples[0];
    let median = samples[samples.len() / 2];
    let mean = samples.iter().sum::<f64>() / samples.len() as f64;
    let max = samples[samples.len() - 1];
    println!(
        "{:<46} min {:>8.2}us  med {:>8.2}us  mean {:>8.2}us  max {:>8.2}us",
        label, min, median, mean, max
    );
}

fn main() {
    println!("agent-core benchmark");
    println!("===================");

    // ---- trim_tree: the JNI hot path, called once per agent step ----
    for n in [10usize, 120, 500, 2000] {
        let dump = ax_dump(n);
        let label = format!("trim_tree({} nodes, {} B input)", n, dump.len());
        bench(&label, 20, 200, || {
            std::hint::black_box(trim_tree(std::hint::black_box(&dump)));
        });
    }

    // ---- parse_action: the validation boundary ----
    let valid_click = r#"{"type":"click","id":42}"#;
    bench("parse_action(valid click)", 200, 5000, || {
        let _ = std::hint::black_box(parse_action(std::hint::black_box(valid_click)));
    });
    let valid_input = r#"{"type":"input","id":42,"text":"hello world"}"#;
    bench("parse_action(valid input)", 200, 5000, || {
        let _ = std::hint::black_box(parse_action(std::hint::black_box(valid_input)));
    });
    let invalid = r#"{"type":"rm -rf"}"#;
    bench("parse_action(invalid -> Err)", 200, 5000, || {
        let _ = std::hint::black_box(parse_action(std::hint::black_box(invalid)));
    });

    // ---- build_prompt: currently NOT called from Kotlin (dead code) ----
    let tree = trim_tree(&ax_dump(400));
    let hist = r#"[{"type":"click","id":1},{"type":"scroll","dir":"down"}]"#;
    bench(
        "build_prompt(dead code, unused by Kotlin)",
        200,
        5000,
        || {
            std::hint::black_box(build_prompt(
                std::hint::black_box("turn on dark theme"),
                std::hint::black_box(&tree),
                std::hint::black_box(hist),
            ));
        },
    );

    println!();
    println!("trim_tree output budget check");
    println!("============================");
    for n in [10usize, 120, 500, 2000] {
        let out = trim_tree(&ax_dump(n));
        println!(
            "  {:>5} nodes in -> {:>6} B out, {:>4} lines, budget 8192 B / 120 nodes",
            n,
            out.len(),
            out.lines().count()
        );
    }
}
