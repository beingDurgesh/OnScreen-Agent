package com.onscreen.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Foreground loop driver: perceive → VLM → Rust-validate → act. Stop-first design. */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "Agent", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, "agent")
            .setContentTitle("On Screen agent running — tap Stop in app to halt")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { running = false; stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        val goal = intent?.getStringExtra("goal").orEmpty().take(500)
        scope.launch {
            var history = mutableListOf<String>()
            repeat(40) { // hard step cap: token + safety bound
                if (!running) return@launch
                try {
                    val ax = AgentAccessibilityService.instance?.dumpTree() ?: "[]"
                    val tree = RustBridge.trimTreeSafe(ax)
                    val vlmP = SettingsStore.get(this@AgentService, "vlmProvider") ?: "OpenAI"
                    val vlmM = SettingsStore.get(this@AgentService, "vlmModel") ?: "gpt-4o-mini"
                    val key = SettingsStore.getKey(this@AgentService, "vlm")
                    if (key.isBlank()) { running = false; return@launch }
                    val system = "You are an Android operator. Reply with ONE JSON action only: " +
                        """{"type":"click","id":N}|{"type":"input","id":N,"text":"..."}|{"type":"scroll","dir":"down"}|{"type":"back"}|{"type":"home"}|{"type":"openApp","pkg":"com.example"}|{"type":"wait","ms":500}|{"type":"done"}|{"type":"needHelp","reason":"..."}"""
                    val user = "Goal:$goal\nScreen:\n$tree\nRecent:\n${history.takeLast(6).joinToString("\n")}"
                    val raw = CloudClients.nextAction(vlmP, vlmM, key, system, user)
                    val valid = RustBridge.parseActionSafe(raw)
                    history.add(valid.take(300))
                    if (valid.contains("\"done\"")) { running = false; return@launch }
                    if (valid.contains("openApp")) {
                        val pkg = org.json.JSONObject(valid).optString("pkg")
                        startActivity(packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                    } else {
                        AgentAccessibilityService.instance?.doAction(valid)
                    }
                } catch (_: Throwable) { delay(1000) }
                delay(800)
            }
            running = false; stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() { running = false; scope.cancel(); super.onDestroy() }
}
