package com.onscreen.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Foreground loop driver: perceive (AX + screenshot) → VLM → Rust-validate → act. Stop-first design. */
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
        startForeground(
            1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { running = false; stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        running = true
        val goal = intent?.getStringExtra("goal").orEmpty().take(500)
        val rc = intent?.getIntExtra("rc", android.app.Activity.RESULT_CANCELED) ?: android.app.Activity.RESULT_CANCELED
        val data: Intent? = intent?.getParcelableExtra("data")
        scope.launch {
            ScreenCapture.onPermissionResult(this@AgentService, rc, data)
            val history = mutableListOf<String>()
            repeat(40) { // hard step cap: token + safety bound
                if (!running) return@launch
                try {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) { delay(1500); return@repeat }
                    val dump = svc.dumpIndexed()
                    val tree = RustBridge.trimTreeSafe(dump.text)
                    val jpeg = try { ScreenCapture.captureJpeg(this@AgentService) } catch (_: Throwable) { null }
                    val vlmP = SettingsStore.get(this@AgentService, "vlmProvider") ?: "OpenAI"
                    val vlmM = SettingsStore.get(this@AgentService, "vlmModel") ?: "gpt-4o-mini"
                    val key = SettingsStore.getKey(this@AgentService, "vlm")
                    if (key.isBlank()) {
                        speakStatus("Missing agent API key. Save a key first.")
                        running = false; return@launch
                    }
                    val system = "You are an Android operator. Reply with ONE JSON action only: " +
                        """{"type":"click","id":N}|{"type":"longClick","id":N}|{"type":"input","id":N,"text":"..."}|{"type":"scroll","dir":"down"}|{"type":"back"}|{"type":"home"}|{"type":"openApp","pkg":"com.example"}|{"type":"wait","ms":500}|{"type":"done"}|{"type":"needHelp","reason":"..."} Use only ids listed in the screen text."""
                    val user = "Goal:$goal\nScreen:\n$tree\nRecent:\n${history.takeLast(6).joinToString("\n")}"
                    val raw = CloudClients.nextActionMultimodal(vlmP, vlmM, key, system, user, jpeg)
                    val valid = RustBridge.parseActionSafe(raw)
                    history.add(valid.take(300))
                    val type = try { JSONObject(valid).optString("type") } catch (_: Throwable) { "needHelp" }
                    when (type) {
                        "done" -> { speakStatus("Task complete."); running = false; return@launch }
                        "needHelp" -> {
                            val reason = try { JSONObject(valid).optString("reason", "I need help.") } catch (_: Throwable) { "I need help." }
                            speakStatus(reason.take(200)); running = false; return@launch
                        }
                        "openApp" -> {
                            val pkg = try { JSONObject(valid).optString("pkg") } catch (_: Throwable) { "" }
                            startActivity(packageManager.getLaunchIntentForPackage(pkg)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                        }
                        "wait" -> {
                            val ms = try { JSONObject(valid).optInt("ms", 500) } catch (_: Throwable) { 500 }
                            delay(ms.coerceIn(0, 5000).toLong())
                        }
                        else -> svc.actOnDump(dump, valid) // same dump the model saw: ids stay valid
                    }
                } catch (_: Throwable) { delay(1000) }
                delay(800)
            }
            speakStatus("Stopped after the step limit.")
            running = false; stopSelf()
        }
        return START_STICKY
    }

    /** Best-effort spoken status via the saved TTS slot. Silence on any failure. */
    private suspend fun speakStatus(text: String) {
        try {
            val p = SettingsStore.get(this, "ttsProvider") ?: "OpenAI"
            val m = SettingsStore.get(this, "ttsModel") ?: "tts-1"
            val k = SettingsStore.getKey(this, "tts")
            val f = File(cacheDir, "status.mp3")
            if (f.exists()) f.delete()
            if (!CloudClients.speakToFile(p, m, k, text, f) || f.length() == 0L) return
            try {
                val mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.prepare()
                mp.start()
                while (mp.isPlaying) delay(200)
                mp.release()
            } catch (_: Throwable) { /* audio is advisory only */ }
            f.delete()
        } catch (_: Throwable) { /* never crash the loop for speech */ }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        ScreenCapture.release()
        super.onDestroy()
    }
}
