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

    fun parseActionSafe(json: String): String =
        if (loaded) try { parseAction(json) } catch (_: Throwable) { json } else json
    fun trimTreeSafe(json: String): String =
        if (loaded) try { trimTree(json) } catch (_: Throwable) { json.take(8192) } else json.take(8192)
}
