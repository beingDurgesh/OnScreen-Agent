package com.onscreen.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** On-device model downloads: user picks what to fetch into app storage. Inference wiring is next. */
@Composable
fun ModelsSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by ModelManager.states.collectAsState()
    var hfKey by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ModelManager.refresh(ctx)
        hfKey = SettingsStore.getKey(ctx, "hf").let { if (it.isNotBlank()) "••••••" else "" }
    }

    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("4 · On-device models (optional downloads)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cloud mode needs none of this. Download only what you want; files land in app-private storage. " +
                    "Gemma packs are gated — approve them on Hugging Face and save a token.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                hfKey, { hfKey = it },
                label = { Text("Hugging Face token (only for Gemma, encrypted)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Button(onClick = {
                scope.launch {
                    if (hfKey != "••••••" && hfKey.isNotBlank()) SettingsStore.putKey(ctx, "hf", hfKey)
                }
            }, modifier = Modifier.padding(top = 4.dp)) { Text("Save HF token") }
            Spacer(Modifier.height(8.dp))

            ModelCatalog.entries.forEach { e ->
                val st = states[e.id] ?: ModelManager.stateOf(ctx, e)
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text("[${e.module}] ${e.label} — ${e.sizeLabel}", style = MaterialTheme.typography.titleSmall)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        when (st) {
                            is ModelState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Ready on device.", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.weight(1f))
                                OutlinedButton(onClick = {
                                    scope.launch(Dispatchers.IO) { ModelManager.delete(ctx, e.id) }
                                }) { Text("Delete") }
                            }
                            is ModelState.Downloading -> {
                                val frac = if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f
                                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "${(frac * 100).toInt()}% (${st.done / 1_000_000}MB / ${st.total / 1_000_000}MB)",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    OutlinedButton(onClick = { ModelManager.cancel(e.id) }) { Text("Cancel") }
                                }
                            }
                            is ModelState.Error -> {
                                Text(st.msg, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        scope.launch(Dispatchers.IO) {
                                            ModelManager.start(ctx, e.id, SettingsStore.getKey(ctx, "hf"))
                                        }
                                    }) { Text("Retry") }
                                    OutlinedButton(onClick = {
                                        scope.launch(Dispatchers.IO) { ModelManager.delete(ctx, e.id) }
                                    }) { Text("Clear") }
                                }
                            }
                            is ModelState.Missing -> Button(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    ModelManager.start(ctx, e.id, SettingsStore.getKey(ctx, "hf"))
                                }
                            }) { Text("Download ${e.sizeLabel}") }
                        }
                    }
                }
            }
        }
    }
}
