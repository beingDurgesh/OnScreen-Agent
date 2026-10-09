package com.onscreen.agent

/** Thin JNI facade. Pure logic lives in Rust; fallbacks keep cloud mode working without .so. */
object RustBridge {
    private val loaded = try { System.loadLibrary("agent_core"); true } catch (_: Throwable) { false }
    @JvmStatic external fun parseAction(json: String): String
    @JvmStatic external fun trimTree(json: String): String

    fun parseActionSafe(json: String): String =
        if (loaded) try { parseAction(json) } catch (_: Throwable) { json } else json
    fun trimTreeSafe(json: String): String =
        if (loaded) try { trimTree(json) } catch (_: Throwable) { json.take(8192) } else json.take(8192)
}
