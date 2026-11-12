package com.onscreen.agent

import android.util.Log

/** Thin JNI facade. Pure logic lives in Rust; fallbacks keep cloud mode working without .so. */
object RustBridge {
    private const val TAG = "RustBridge"

    /**
     * True when the Rust core is live. When false the Kotlin fallbacks below
     * are used, so a missing .so degrades instead of crashing — but it should
     * never be silent, hence the log.
     */
    val loaded: Boolean = try {
        System.loadLibrary("agent_core")
        Log.i(TAG, "agent_core loaded — Rust core is live")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "agent_core NOT loaded — using Kotlin fallback", t)
        false
    }

    @JvmStatic external fun parseAction(json: String): String
    @JvmStatic external fun trimTree(json: String): String
    @JvmStatic external fun buildPrompt(goal: String, tree: String, history: String): String

    fun parseActionSafe(json: String): String =
        if (loaded) try { parseAction(json) } catch (_: Throwable) { json } else json

    fun trimTreeSafe(json: String): String =
        if (loaded) try { trimTree(json) } catch (_: Throwable) { json.take(8192) } else json.take(8192)

    /**
     * Assemble the model prompt with all the size budgets applied in Rust.
     * The Kotlin fallback mirrors the same caps so the two paths stay
     * equivalent. `history` is the newline-separated list of recent actions,
     * oldest first.
     */
    fun buildPromptSafe(goal: String, tree: String, history: String): String =
        if (loaded) {
            try { buildPrompt(goal, tree, history) } catch (_: Throwable) { kotlinPrompt(goal, tree, history) }
        } else {
            kotlinPrompt(goal, tree, history)
        }

    private const val MAX_PROMPT_CHARS = 8 * 1024
    private const val MAX_GOAL_CHARS = 500
    private const val MAX_HISTORY = 6

    private fun kotlinPrompt(goal: String, tree: String, history: String): String {
        val g = goal.take(MAX_GOAL_CHARS)
        val t = tree.take(MAX_PROMPT_CHARS)
        val h = history.split("\n").filter { it.isNotEmpty() }.takeLast(MAX_HISTORY).joinToString("\n")
        return "Goal:$g\nScreen:\n$t\nRecent:\n$h"
    }
}
