package com.onscreen.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/** Foreground loop driver: perceive (AX + screenshot) → VLM → Rust-validate → act. Stop-first design. */
class AgentService : Service() {
    companion object {
        private const val TAG = "AgentService"

        /** Hard cap on agent steps per run: a token and safety bound. */
        const val MAX_STEPS = 40

        /** How many previous actions are shown to the model. */
        const val MAX_HISTORY = 6
    }

    /** Elapsed milliseconds between two [System.nanoTime] readings. */
    private fun ms(from: Long, to: Long): Long = (to - from) / 1_000_000

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
        // A null intent means the system restarted us after process death. There
        // is no goal and no screen-capture grant to continue with, so do not
        // start a phantom agent — START_NOT_STICKY stops that from recurring.
        if (intent == null) {
            Log.w(TAG, "restarted with a null intent — refusing to start without a goal")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        running = true
        val goal = intent.getStringExtra("goal").orEmpty().take(500)
        val rc = intent.getIntExtra("rc", android.app.Activity.RESULT_CANCELED)
        val data: Intent? = intent.getParcelableExtra("data", Intent::class.java)
        scope.launch {
            ScreenCapture.onPermissionResult(this@AgentService, rc, data)
            val history = mutableListOf<String>()
            repeat(MAX_STEPS) { // hard step cap: token + safety bound
                if (!running) { stopSelf(); return@launch }
                try {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) { delay(1500); return@repeat }
                    val t0 = System.nanoTime()
                    val dump = svc.dumpIndexed()
                    val tAx = System.nanoTime()
                    val tree = RustBridge.trimTreeSafe(dump.text)
                    val tTree = System.nanoTime()
                    // Check the key before the screenshot: capturing a frame is
                    // the most expensive thing in a step, and without a key the
                    // loop is about to exit anyway.
                    val vlmP = SettingsStore.get(this@AgentService, "vlmProvider") ?: "OpenAI"
                    val vlmM = SettingsStore.get(this@AgentService, "vlmModel") ?: "gpt-4o-mini"
                    val baseUrl = SettingsStore.get(this@AgentService, "vlmBaseUrl").orEmpty()
                    val key = SettingsStore.getKey(this@AgentService, "vlm")
                    if (key.isBlank()) {
                        speakStatus("Missing agent API key. Save a key first.")
                        running = false; stopSelf(); return@launch
                    }
                    val jpeg = try { ScreenCapture.captureJpeg(this@AgentService) } catch (_: Throwable) { null }
                    val tShot = System.nanoTime()
                    Log.i(
                        TAG,
                        "perceive: ax=${dump.nodes.size}/${ms(t0, tAx)}ms " +
                            "tree=${tree.length}B/${ms(tAx, tTree)}ms " +
                            "jpeg=${jpeg?.size ?: -1}B/${ms(tTree, tShot)}ms"
                    )
                    val system = "You are an Android operator. Reply with ONE JSON action only: " +
                        """{"type":"click","id":N}|{"type":"longClick","id":N}|{"type":"input","id":N,"text":"..."}|{"type":"scroll","dir":"down"}|{"type":"back"}|{"type":"home"}|{"type":"openApp","pkg":"com.example"}|{"type":"wait","ms":500}|{"type":"done"}|{"type":"needHelp","reason":"..."} Use only ids listed in the screen text."""
                    // The prompt is assembled in Rust so the size budgets live in
                    // one place (goal 500 chars, tree 8 KB, last 6 steps).
                    val user = RustBridge.buildPromptSafe(
                        goal,
                        tree,
                        history.takeLast(MAX_HISTORY).joinToString("\n"),
                    )
                    val raw = CloudClients.nextActionMultimodal(vlmP, vlmM, key, system, user, jpeg, baseUrl)
                    val tVlm = System.nanoTime()
                    val valid = RustBridge.parseActionSafe(raw)
                    val tParse = System.nanoTime()
                    history.add(valid.take(300))
                    val type = try { JSONObject(valid).optString("type") } catch (_: Throwable) { "needHelp" }
                    var acted = "none"
                    when (type) {
                        "done" -> { speakStatus("Task complete."); running = false; stopSelf(); return@launch }
                        "needHelp" -> {
                            val reason = try { JSONObject(valid).optString("reason", "I need help.") } catch (_: Throwable) { "I need help." }
                            speakStatus(reason.take(200)); running = false; stopSelf(); return@launch
                        }
                        "openApp" -> {
                            val pkg = try { JSONObject(valid).optString("pkg") } catch (_: Throwable) { "" }
                            val intent = packageManager.getLaunchIntentForPackage(pkg)
                            if (intent == null) {
                                Log.w(TAG, "openApp: no launcher intent for '$pkg'")
                            } else {
                                startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                            }
                            acted = "openApp"
                        }
                        "wait" -> {
                            val ms = try { JSONObject(valid).optInt("ms", 500) } catch (_: Throwable) { 500 }
                            delay(ms.coerceIn(0, 5000).toLong())
                            acted = "wait"
                        }
                        else -> {
                            val ok = svc.actOnDump(dump, valid) // same dump the model saw: ids stay valid
                            if (!ok) Log.w(TAG, "action '$type' did not execute (target missing or refused)")
                            acted = "${type}:${if (ok) "ok" else "FAILED"}"
                        }
                    }
                    val tAct = System.nanoTime()
                    Log.i(
                        TAG,
                        "act: step ${history.size - 1} vlm=${ms(tShot, tVlm)}ms " +
                            "parse=${ms(tVlm, tParse)}ms act=${ms(tParse, tAct)}ms [$acted]"
                    )
                } catch (ce: CancellationException) {
                    throw ce // cooperative cancellation must not be swallowed
                } catch (t: Throwable) {
                    Log.w(TAG, "step failed: ${t.javaClass.simpleName}: ${t.message}", t)
                    delay(1000)
                }
                delay(800)
            }
            speakStatus("Stopped after the step limit.")
            running = false; stopSelf()
        }
        return START_NOT_STICKY
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
            var mp: MediaPlayer? = null
            try {
                mp = MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.prepare()
                mp.start()
                while (mp.isPlaying) delay(200)
            } catch (_: Throwable) { /* audio is advisory only */ }
            finally {
                // MediaPlayer holds native resources; it must be released on
                // every path or the audio track leaks.
                try { mp?.release() } catch (_: Throwable) { /* ignore */ }
                // Remove the synthesised speech so it does not linger on disk.
                try { f.delete() } catch (_: Throwable) { /* ignore */ }
            }
        } catch (_: Throwable) { /* never crash the loop for speech */ }
    }

    override fun onDestroy() {
        running = false
        // Cancel any in-flight provider request before tearing the scope down,
        // so a Stop does not leave a socket open for the rest of the timeout.
        CloudClients.cancelActive()
        scope.cancel()
        ScreenCapture.release()
        super.onDestroy()
    }
}
