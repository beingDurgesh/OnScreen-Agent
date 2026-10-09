package com.onscreen.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SettingsScreen() } }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var goal by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("Cloud BYOK build. Keys stay on this phone.") }

    suspend fun load(k: String) = SettingsStore.get(ctx, k) ?: ""
    @Composable
    fun ByokCard(title: String, providers: Map<String, List<String>>, pKey: String, mKey: String, slot: String) {
        var provider by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            provider = load(pKey).ifBlank { providers.keys.first() }
            model = load(mKey).ifBlank { providers.values.first().first() }
            key = SettingsStore.getKey(ctx, slot).let { if (it.isNotBlank()) "••••••" else "" }
        }
        ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                var exp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = exp, onExpandedChange = { exp = !exp }) {
                    OutlinedTextField(provider, { }, readOnly = true, label = { Text("Provider") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
                        providers.keys.forEach {
                            DropdownMenuItem({ provider = it; model = providers[it]!!.first(); exp = false }, label = { Text(it) })
                        }
                    }
                }
                var mexp by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = mexp, onExpandedChange = { mexp = !mexp }) {
                    OutlinedTextField(model, { model = it }, label = { Text("Model (editable for OpenRouter/custom)") },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = mexp, onDismissRequest = { mexp = false }) {
                        (providers[provider] ?: listOf(model)).forEach {
                            DropdownMenuItem({ model = it; mexp = false }, label = { Text(it) })
                        }
                    }
                }
                OutlinedTextField(key, { key = it }, label = { Text("API key ($slot) — stored encrypted on-device") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = {
                    scope.launch {
                        SettingsStore.set(ctx, pKey, provider); SettingsStore.set(ctx, mKey, model)
                        if (key != "••••••" && key.isNotBlank()) SettingsStore.putKey(ctx, slot, key)
                        log = "$title saved: $provider / $model"
                    }
                }, modifier = Modifier.padding(top = 6.dp)) { Text("Save $title") }
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("On Screen — Rust Cloud", style = MaterialTheme.typography.headlineSmall)
        Text("Android 15+ (minSdk 35). No backend. Paste keys, pick a model in each of the 3 slots, press Play.",
            style = MaterialTheme.typography.bodyMedium)
        ByokCard("1 · Speech-to-text (whisper.cpp default offline)", SettingsStore.STT_MODELS, "sttProvider", "sttModel", "stt")
        ByokCard("2 · Vision-language agent brain", SettingsStore.VLM_MODELS, "vlmProvider", "vlmModel", "vlm")
        ByokCard("3 · Text-to-speech (KittenTTS default offline)", SettingsStore.TTS_MODELS, "ttsProvider", "ttsModel", "tts")
        OutlinedTextField(goal, { goal = it }, label = { Text("Goal, e.g. Turn on Dark theme in Settings") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                ctx.startForegroundService(Intent(ctx, AgentService::class.java).putExtra("goal", goal))
                log = "Agent started. Use system notification / Stop to halt."
            }) { Text("▶ Play") }
            OutlinedButton(onClick = { ctx.startService(Intent(ctx, AgentService::class.java).setAction("STOP")) }) { Text("■ Stop") }
        }
        Text(log, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        Text("Safety: banking/wallet apps may refuse to run alongside accessibility tools (same warning as upstream v1). Token use is billed to your key — watch usage. Guardrails toggle ships default OFF per upstream v0.1.1; Stop always wins.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
