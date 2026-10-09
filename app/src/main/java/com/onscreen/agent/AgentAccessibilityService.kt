package com.onscreen.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Performs Rust-validated actions on the live AX tree. Singleton bound by AgentService. */
class AgentAccessibilityService : AccessibilityService() {
    companion object { var instance: AgentAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Dump capped at 120 nodes for RAM; Rust trims again. */
    fun dumpTree(): String {
        val root = rootInActiveWindow ?: return "[]"
        val arr = JSONArray()
        var count = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (count >= 120) return
            val text = (n.text ?: n.contentDescription ?: "").toString().take(48)
            arr.put(JSONObject().put("id", System.identityHashCode(n) and 0xffffff)
                .put("text", text).put("cls", (n.className ?: "").toString().takeLast(24)))
            count++
            for (i in 0 until n.childCount) walk(n.getChild(i) ?: continue)
        }
        walk(root)
        return arr.toString()
    }

    fun doAction(validatedJson: String): Boolean {
        val o = JSONObject(RustBridge.parseActionSafe(validatedJson))
        val root = rootInActiveWindow ?: return false
        fun find(id: Int): AccessibilityNodeInfo? {
            val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (q.isNotEmpty()) {
                val n = q.removeFirst()
                if ((System.identityHashCode(n) and 0xffffff) == id) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
            return null
        }
        return when (o.getString("type")) {
            "click" -> find(o.getInt("id"))?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            "longClick" -> find(o.getInt("id"))?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ?: false
            "input" -> find(o.getInt("id"))?.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, o.getString("text")) }) ?: false
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "scroll" -> {
                val id = o.optInt("id", -1)
                val node = if (id >= 0) find(id) else root
                val dir = o.optString("dir", "down")
                val act = if (dir == "up" || dir == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                node?.performAction(act) ?: false
            }
            else -> false // openApp/wait/done/needHelp handled by AgentService
        }
    }
}
