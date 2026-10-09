package com.onscreen.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Performs Rust-validated actions on the live AX tree.
 *
 * Stable IDs: [dumpIndexed] assigns id = index in traversal order and hands
 * the SAME node list to [actOnDump]. The model must only use ids present in
 * the trimmed text it was shown (Rust trims beyond-budget lines but keeps
 * order, so kept ids stay valid). Never resolve an id against a fresh dump.
 */
class AgentAccessibilityService : AccessibilityService() {
    companion object { var instance: AgentAccessibilityService? = null }

    /** One dump: serialised text the model sees + the live nodes behind each id. */
    data class Dump(val text: String, val nodes: List<AccessibilityNodeInfo>)

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** DFS dump capped at 120 nodes for RAM; id = position in [Dump.nodes]. */
    fun dumpIndexed(): Dump {
        val root = rootInActiveWindow ?: return Dump("[]", emptyList())
        val arr = JSONArray()
        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        fun walk(n: AccessibilityNodeInfo) {
            if (nodes.size >= 120) return
            val id = nodes.size
            nodes.add(n)
            val text = (n.text ?: n.contentDescription ?: "").toString().take(48)
            arr.put(
                JSONObject().put("id", id)
                    .put("text", text).put("cls", (n.className ?: "").toString().takeLast(24))
            )
            for (i in 0 until n.childCount) {
                walk(n.getChild(i) ?: continue)
                if (nodes.size >= 120) return
            }
        }
        walk(root)
        return Dump(arr.toString(), nodes)
    }

    /** Backwards-compatible text-only dump (no stable ids — prefer [dumpIndexed]). */
    fun dumpTree(): String = dumpIndexed().text

    /** Act on the SAME dump the model saw. Rejects unknown ids. */
    fun actOnDump(dump: Dump, validatedJson: String): Boolean {
        val o = JSONObject(RustBridge.parseActionSafe(validatedJson))
        val root = rootInActiveWindow ?: return false
        fun node(id: Int): AccessibilityNodeInfo? = dump.nodes.getOrNull(id)
        return when (o.getString("type")) {
            "click" -> node(o.getInt("id"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            "longClick" -> node(o.getInt("id"))?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
            "input" -> node(o.getInt("id"))?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, o.getString("text")) }) ?: false
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "scroll" -> {
                val id = o.optInt("id", -1)
                val target = if (id >= 0) node(id) else root
                val dir = o.optString("dir", "down")
                val act = if (dir == "up" || dir == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                target?.performAction(act) ?: false
            }
            else -> false // openApp/wait/done/needHelp handled by AgentService
        }
    }

    /** Legacy entry: dumps fresh then acts (ids may mismatch — prefer [actOnDump]). */
    fun doAction(validatedJson: String): Boolean = actOnDump(dumpIndexed(), validatedJson)
}
