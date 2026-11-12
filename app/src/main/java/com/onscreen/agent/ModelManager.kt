package com.onscreen.agent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** One remote file inside a downloadable model pack. */
data class ModelFile(val url: String, val name: String, val bytes: Long)

/**
 * Downloadable on-device pack. URLs below were verified against
 * huggingface.co file listings (Feb–Oct 2026 snapshots):
 * - whisper: ggerganov/whisper.cpp (MIT, public)
 * - gemma: ggml-org/gemma-3n-*-it-GGUF, Q8_0 only — NO Q4 exists there (gated: gemma licence)
 * - kitten: KittenML/kitten-tts-nano-0.1 (Apache-2.0, public)
 */
data class ModelEntry(
    val id: String,
    val module: String,
    val label: String,
    val sizeLabel: String,
    val files: List<ModelFile>,
    val needsHfToken: Boolean = false,
    val note: String = "",
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data object Ready : ModelState
    data class Error(val msg: String) : ModelState
}

object ModelCatalog {
    private const val WH = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"
    private const val KIT = "https://huggingface.co/KittenML/kitten-tts-nano-0.1/resolve/main"
    private const val E2B = "https://huggingface.co/ggml-org/gemma-3n-E2B-it-GGUF/resolve/main"
    private const val E4B = "https://huggingface.co/ggml-org/gemma-3n-E4B-it-GGUF/resolve/main"

    val entries = listOf(
        ModelEntry(
            "whisper-tiny-en", "STT", "Whisper tiny.en (q5_1)", "32 MB",
            listOf(ModelFile("$WH/ggml-tiny.en-q5_1.bin", "ggml-tiny.en-q5_1.bin", 32_200_000L)),
            note = "Fastest on-device STT. English-only.",
        ),
        ModelEntry(
            "whisper-base-en", "STT", "Whisper base.en (q5_1)", "60 MB",
            listOf(ModelFile("$WH/ggml-base.en-q5_1.bin", "ggml-base.en-q5_1.bin", 59_700_000L)),
            note = "Better accuracy, roughly 2x slower than tiny.",
        ),
        ModelEntry(
            "gemma-e2b-q8", "VLM", "Gemma 3n E2B (Q8_0)", "4.8 GB",
            listOf(ModelFile("$E2B/gemma-3n-E2B-it-Q8_0.gguf", "gemma-3n-E2B-it-Q8_0.gguf", 4_790_000_000L)),
            needsHfToken = true,
            note = "Gated: approve the model on Hugging Face and set a token below. Heavy — needs 6GB+ free RAM.",
        ),
        ModelEntry(
            "gemma-e4b-q8", "VLM", "Gemma 3n E4B (Q8_0)", "7.4 GB",
            listOf(ModelFile("$E4B/gemma-3n-E4B-it-Q8_0.gguf", "gemma-3n-E4B-it-Q8_0.gguf", 7_350_000_000L)),
            needsHfToken = true,
            note = "Gated: approve the model on Hugging Face and set a token below. Flagship quality, 8GB+ RAM.",
        ),
        ModelEntry(
            "kitten-nano", "TTS", "KittenTTS nano pack", "24 MB",
            listOf(
                ModelFile("$KIT/kitten_tts_nano_v0_1.onnx", "kitten_tts_nano_v0_1.onnx", 23_800_000L),
                ModelFile("$KIT/config.json", "kitten_config.json", 1_000L),
                ModelFile("$KIT/voices.npz", "kitten_voices.npz", 11_000L),
            ),
            note = "15 voices at 24kHz. Smallest download on this screen.",
        ),
    )

    fun byId(id: String): ModelEntry? = entries.firstOrNull { it.id == id }
}

/**
 * Downloads packs into filesDir/models/<id>/ with progress, cancel, delete.
 * Writes go to *.part then atomically rename — a killed download never
 * leaves a half file that inference could mistake for ready.
 */
object ModelManager {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES) // no timeout: GB-scale downloads
        .retryOnConnectionFailure(true)
        .build()

    private val _states = MutableStateFlow<Map<String, ModelState>>(emptyMap())
    val states: StateFlow<Map<String, ModelState>> = _states

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = mutableMapOf<String, okhttp3.Call>()

    private fun set(id: String, s: ModelState) {
        _states.value = _states.value + (id to s)
    }

    fun modelsDir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun dirFor(ctx: Context, id: String): File = File(modelsDir(ctx), id).apply { mkdirs() }

    fun isReady(ctx: Context, e: ModelEntry): Boolean =
        e.files.all { File(dirFor(ctx, e.id), it.name).exists() }

    fun downloadedBytes(ctx: Context, e: ModelEntry): Long {
        val dir = dirFor(ctx, e.id)
        return e.files.sumOf { File(dir, it.name).let { f -> if (f.exists()) f.length() else 0L } }
    }

    fun stateOf(ctx: Context, e: ModelEntry): ModelState =
        _states.value[e.id]?.let { cur ->
            if (cur is ModelState.Downloading || cur is ModelState.Error) cur else null
        } ?: if (isReady(ctx, e)) ModelState.Ready else ModelState.Missing

    fun refresh(ctx: Context) {
        ModelCatalog.entries.forEach { e ->
            val cur = _states.value[e.id]
            if (cur == null || cur is ModelState.Missing || cur is ModelState.Ready) {
                set(e.id, if (isReady(ctx, e)) ModelState.Ready else ModelState.Missing)
            }
        }
    }

    /** Canonical file handle for future inference code: pathFor(ctx, "kitten-nano", "kitten_tts_nano_v0_1.onnx"). */
    fun pathFor(ctx: Context, id: String, name: String): File = File(dirFor(ctx, id), name)

    fun start(ctx: Context, id: String, hfToken: String) {
        val e = ModelCatalog.byId(id) ?: return
        synchronized(active) { if (active.containsKey(id)) return }
        scope.launch {
            try {
                val dir = dirFor(ctx, id)
                val missing = e.files.filter { !File(dir, it.name).exists() }
                val need = missing.sumOf { it.bytes }
                if (dir.usableSpace < need) {
                    set(id, ModelState.Error("Not enough storage — needs ~${e.sizeLabel} free."))
                    return@launch
                }
                if (e.needsHfToken && hfToken.isBlank()) {
                    set(id, ModelState.Error("Gated model: approve it on Hugging Face and save a token below."))
                    return@launch
                }
                val total = e.files.sumOf { it.bytes }
                var done = downloadedBytes(ctx, e)
                set(id, ModelState.Downloading(done, total))
                for (f in e.files) {
                    val dest = File(dir, f.name)
                    if (dest.exists() && dest.length() > 0) continue
                    val tmp = File(dir, f.name + ".part")
                    val reqB = Request.Builder().url(f.url)
                    if (e.needsHfToken) reqB.header("Authorization", "Bearer $hfToken")
                    val call = http.newCall(reqB.build())
                    synchronized(active) { active[id] = call }
                    val resp = call.execute()
                    if (!resp.isSuccessful) {
                        resp.close()
                        synchronized(active) { active.remove(id) }
                        val hint = if (resp.code == 401 || resp.code == 403) "approve the model on HF + check token" else "retry later"
                        set(id, ModelState.Error("HTTP ${resp.code} — $hint."))
                        return@launch
                    }
                    try {
                        resp.body!!.byteStream().use { inp ->
                            tmp.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    set(id, ModelState.Downloading(done, total))
                                }
                            }
                        }
                    } finally {
                        resp.close()
                    }
                    synchronized(active) { active.remove(id) }
                    if (!tmp.renameTo(dest)) {
                        set(id, ModelState.Error("Could not finalise file (storage?)."))
                        return@launch
                    }
                }
                set(id, ModelState.Ready)
            } catch (t: Throwable) {
                synchronized(active) { active.remove(id) }
                if (t is CancellationException) throw t
                set(id, ModelState.Error((t.message ?: "download failed").take(140)))
            }
        }
    }

    fun cancel(id: String) {
        synchronized(active) { active.remove(id) }?.cancel()
        set(id, ModelState.Error("Cancelled."))
    }

    fun delete(ctx: Context, id: String) {
        cancel(id)
        dirFor(ctx, id).deleteRecursively()
        set(id, ModelState.Missing)
    }
}
