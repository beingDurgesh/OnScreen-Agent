package com.onscreen.agent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the bridge at startup so the native lib is loaded (and any
        // failure is visible in logcat) before the agent loop needs it.
        android.util.Log.i("OnScreen", "Rust core loaded=${RustBridge.loaded}")
        setContent { MaterialTheme { SettingsScreen() } }
    }
}

/** Short voice-note recorder. File is transcribed, then deleted. */
object VoiceMemo {
    private var rec: MediaRecorder? = null
    private var path: String? = null

    fun start(ctx: Context): Boolean {
        return try {
            val f = File(ctx.cacheDir, "goal.m4a")
            if (f.exists()) f.delete()
            val r = MediaRecorder(ctx).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            rec = r
            path = f.absolutePath
            true
        } catch (_: Throwable) { false }
    }

    fun stop(): File? {
        val r = rec
        rec = null
        try { r?.stop() } catch (_: Throwable) { /* too short — ignore */ }
        try { r?.release() } catch (_: Throwable) { /* ignore */ }
        val p = path
        path = null
        val f = if (p != null) File(p) else null
        return if (f != null && f.exists() && f.length() > 0) f else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("Cloud BYOK build. Keys stay on this phone.") }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    suspend fun load(k: String) = SettingsStore.get(ctx, k) ?: ""

    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (VoiceMemo.start(ctx)) recording = true else log = "Mic unavailable."
        } else log = "Mic permission denied."
    }

    // The foreground-service notification is the only visible indication that
    // the agent is running and the only Stop button the user can reach. On
    // Android 13+ it needs POST_NOTIFICATIONS, otherwise the system hides it
    // and the agent becomes invisible and unstoppable from outside the app.
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) log = "Notifications denied — the agent's Stop button will not be visible."
    }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        ctx.startForegroundService(Intent(ctx, AgentService::class.java).apply {
            putExtra("goal", goal)
            putExtra("rc", res.resultCode)
            putExtra("data", res.data)
        })
        log = "Agent started. Use Stop to halt."
    }

    @Composable
    fun ByokCard(
        title: String,
        providers: Map<String, List<String>>,
        pKey: String,
        mKey: String,
        slot: String,
        showBaseUrl: Boolean = false,
    ) {
        var provider by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var baseUrl by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            provider = load(pKey).ifBlank { providers.keys.first() }
            model = load(mKey).ifBlank { providers.values.first().first() }
            key = SettingsStore.getKey(ctx, slot).let { if (it.isNotBlank()) "••••••" else "" }
            if (showBaseUrl) baseUrl = load("vlmBaseUrl")
        }
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                var exp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = exp, onExpandedChange = { exp = !exp }) {
                    OutlinedTextField(provider, { }, readOnly = true, label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                        providers.keys.forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = { provider = it; model = providers[it]!!.first(); exp = false },
                            )
                        }
                    }
                }
                var mexp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = mexp, onExpandedChange = { mexp = !mexp }) {
                    OutlinedTextField(model, { model = it }, label = { Text("Model (editable for OpenRouter/custom)") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth())
                    ExposedDropdownMenu(expanded = mexp, onDismissRequest = { mexp = false }) {
                        (providers[provider] ?: listOf(model)).forEach {
                            DropdownMenuItem(
                                text = { Text(it) },
                                onClick = { model = it; mexp = false },
                            )
                        }
                    }
                }
                OutlinedTextField(key, { key = it }, label = { Text("API key ($slot) — stored encrypted on-device") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (showBaseUrl) {
                    OutlinedTextField(
                        baseUrl, { baseUrl = it },
                        label = { Text("Custom base URL (optional, OpenAI-compatible)") },
                        supportingText = { Text("Leave empty for api.openai.com. Use for vLLM, Ollama, LM Studio, NIM…") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
                Button(onClick = {
                    scope.launch {
                        SettingsStore.set(ctx, pKey, provider); SettingsStore.set(ctx, mKey, model)
                        if (key != "••••••" && key.isNotBlank()) SettingsStore.putKey(ctx, slot, key)
                        if (showBaseUrl) SettingsStore.set(ctx, "vlmBaseUrl", baseUrl.trim())
                        log = "$title saved: $provider / $model"
                    }
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Save $title") }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("On Screen — Rust Cloud", style = MaterialTheme.typography.headlineSmall)
        Text("Android 15+ (minSdk 35). No backend. Paste keys, pick a model in each of the 3 slots, speak or type a goal, press Play.",
            style = MaterialTheme.typography.bodyMedium)
        ByokCard("1 · Speech-to-text (whisper.cpp default offline)", SettingsStore.STT_MODELS, "sttProvider", "sttModel", "stt")
        ByokCard("2 · Vision-language agent brain", SettingsStore.VLM_MODELS, "vlmProvider", "vlmModel", "vlm", showBaseUrl = true)
        ByokCard("3 · Text-to-speech (KittenTTS default offline)", SettingsStore.TTS_MODELS, "ttsProvider", "ttsModel", "tts")
        ModelsSection()
        OutlinedTextField(goal, { goal = it }, label = { Text("Goal, e.g. Turn on Dark theme in Settings") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (recording) {
                    recording = false
                    val f = VoiceMemo.stop()
                    if (f == null) { log = "Recording too short."; return@Button }
                    busy = true
                    scope.launch(Dispatchers.IO) {
                        val p = SettingsStore.get(ctx, "sttProvider") ?: "OpenAI"
                        val m = SettingsStore.get(ctx, "sttModel") ?: "whisper-1"
                        val k = SettingsStore.getKey(ctx, "stt")
                        val t = CloudClients.transcribeMicFile(p, m, k, f)
                        f.delete()
                        launch(Dispatchers.Main) {
                            busy = false
                            if (t.isNotBlank()) { goal = t; log = "Heard: $t" }
                            else log = "Transcription failed (key? network?)."
                        }
                    }
                } else {
                    micPerm.launch(Manifest.permission.RECORD_AUDIO)
                }
            }, enabled = !busy) { Text(if (recording) "Stop mic" else "Mic") }
            Button(onClick = {
                // Screen capture permission doubles as the Play gate: result starts the service.
                // Make sure the notification permission is granted first, so the
                // agent's foreground notification (and its Stop button) is visible.
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                captureLauncher.launch(ScreenCapture.intentForResult(activity))
            }, enabled = !recording && !busy) { Text("Play") }
            OutlinedButton(onClick = { ctx.startService(Intent(ctx, AgentService::class.java).setAction("STOP")) }) { Text("Stop") }
        }
        Text(log, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Text("Safety: banking/wallet apps may refuse to run alongside accessibility tools (same warning as upstream v1). Token use is billed to your key — watch usage. Stop always wins.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
