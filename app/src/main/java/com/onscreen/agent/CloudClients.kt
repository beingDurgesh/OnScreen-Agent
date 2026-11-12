package com.onscreen.agent

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Minimal cloud router: OpenAI / Gemini / OpenRouter, direct from phone. No app backend. */
object CloudClients {
    /**
     * The request currently in flight. The agent loop makes one request at a
     * time, so a single slot is enough — and it lets [cancelActive] abort a
     * request the moment the user presses Stop instead of waiting for the
     * socket timeout.
     */
    @Volatile private var activeCall: okhttp3.Call? = null

    /** Abort the in-flight provider request, if any. Safe to call when idle. */
    fun cancelActive() {
        activeCall?.cancel()
        activeCall = null
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // Bounds the whole call including redirects and retries, so a wedged
        // server cannot hold the agent loop open indefinitely.
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Execute a request while registering it as the active call, so a Stop
     * can abort it. The call is cleared on the way out.
     */
    private fun execute(req: Request): okhttp3.Response {
        val call = http.newCall(req)
        activeCall = call
        try {
            return call.execute()
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    // ---------- vision-language: text + optional screenshot ----------

    /** Multimodal step. jpeg==null falls back to the text-only [nextAction]. */
    fun nextActionMultimodal(
        vlmProvider: String, vlmModel: String, apiKey: String,
        system: String, user: String, jpeg: ByteArray?,
        baseUrl: String = "",
    ): String {
        if (jpeg == null) return nextAction(vlmProvider, vlmModel, apiKey, system, user, baseUrl)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        return when (vlmProvider) {
            "Gemini" -> {
                val parts = JSONArray()
                    .put(JSONObject().put("text", user))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))
                    .toString().toRequestBody(JSON)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$vlmModel:generateContent")
                    // The key goes in a header, not the query string: URLs end up
                    // in proxy logs and crash reports, headers do not.
                    .header("x-goog-api-key", apiKey)
                    .post(body).build()
                execute(req).use { r ->
                    JSONObject(r.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim()
                }
            }
            else -> {
                // OpenAI + OpenRouter share the image_url content-part shape.
                val content = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", user))
                    .put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64").put("detail", "low")))
                val (url, auth) = if (vlmProvider == "OpenRouter") {
                    "https://openrouter.ai/api/v1/chat/completions" to "Bearer $apiKey"
                } else {
                    chatCompletionsUrl(baseUrl) to "Bearer $apiKey"
                }
                val body = JSONObject()
                    .put("model", vlmModel.ifBlank { "gpt-4o-mini" })
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", content)))
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("max_tokens", 500)
                    .toString().toRequestBody(JSON)
                val req = Request.Builder().url(url).header("Authorization", auth).post(body).build()
                execute(req).use { r ->
                    val root = JSONObject(r.body!!.string())
                    if (vlmProvider == "OpenRouter") {
                        root.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                    } else {
                        root.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").getString("content").trim()
                    }
                }
            }
        }
    }

    /**
     * Text-only request (no screenshot). This builds the body itself rather
     * than delegating back to [nextActionMultimodal] with a null image — that
     * delegation was mutually recursive and blew the stack with a
     * `StackOverflowError` on every text-only call.
     */
    fun nextAction(
        vlmProvider: String, vlmModel: String, apiKey: String, system: String, user: String,
        baseUrl: String = "",
    ): String {
        return when (vlmProvider) {
            "Gemini" -> {
                val parts = JSONArray().put(JSONObject().put("text", user))
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                    .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))
                    .toString().toRequestBody(JSON)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$vlmModel:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body).build()
                execute(req).use { r ->
                    JSONObject(r.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text").trim()
                }
            }
            else -> {
                val body = JSONObject()
                    .put("model", vlmModel.ifBlank { "gpt-4o-mini" })
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("max_tokens", 500)
                    .toString().toRequestBody(JSON)
                val (url, auth) = if (vlmProvider == "OpenRouter") {
                    "https://openrouter.ai/api/v1/chat/completions" to "Bearer $apiKey"
                } else {
                    chatCompletionsUrl(baseUrl) to "Bearer $apiKey"
                }
                val req = Request.Builder().url(url).header("Authorization", auth).post(body).build()
                execute(req).use { r ->
                    JSONObject(r.body!!.string())
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                }
            }
        }
    }

    /**
     * Chat-completions URL for the OpenAI-compatible provider.
     *
     * An empty [baseUrl] keeps the default OpenAI endpoint, so existing
     * installs behave exactly as before. A custom base URL lets the user point
     * the agent at any OpenAI-compatible server (vLLM, Ollama, LM Studio, an
     * NVIDIA NIM deployment, …). The path is appended only when the caller
     * gave a bare base, so a full endpoint URL is also accepted.
     */
    fun chatCompletionsUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        if (b.isEmpty()) return "https://api.openai.com/v1/chat/completions"
        if (b.endsWith("/chat/completions")) return b
        return "$b/chat/completions"
    }

    // ---------- speech-to-text: mic file -> goal text ----------

    /** Transcribes a recorded mic file. Returns "" on any failure (caller keeps old goal). */
    fun transcribeMicFile(sttProvider: String, sttModel: String, apiKey: String, file: File): String {
        if (!file.exists() || file.length() == 0L || apiKey.isBlank()) return ""
        return try {
            when (sttProvider) {
                "Gemini" -> {
                    val bytes = file.readBytes()
                    if (bytes.size > 8 * 1024 * 1024) return "" // 8MB voice-note cap
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val parts = JSONArray()
                        .put(JSONObject().put("text", "Transcribe this voice note. Reply with the transcription only."))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/mp4").put("data", b64)))
                    val body = JSONObject()
                        .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
                        .toString().toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("https://generativelanguage.googleapis.com/v1beta/models/${sttModel.ifBlank { "gemini-2.0-flash" }}:generateContent")
                        .header("x-goog-api-key", apiKey)
                        .post(body).build()
                    execute(req).use { r ->
                        JSONObject(r.body!!.string())
                            .getJSONArray("candidates").getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts")
                            .getJSONObject(0).getString("text").trim()
                    }
                }
                "whisper.cpp (on-device)" -> "" // on-device path: not bundled, see SPEC; cloud key path below
                else -> { // OpenAI whisper
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", sttModel.ifBlank { "whisper-1" })
                        .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                        .build()
                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/audio/transcriptions")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body).build()
                    execute(req).use { r ->
                        JSONObject(r.body!!.string()).optString("text", "").trim()
                    }                }
            }
        } catch (_: Throwable) { "" }
    }

    // ---------- text-to-speech: status text -> audio file ----------

    /**
     * Synthesises [text] into [outFile]. False = provider unsupported/offline
     * here (Gemini TTS needs a speech-config flow; KittenTTS needs its .onnx
     * which is deliberately not bundled — see SPEC).
     */
    fun speakToFile(ttsProvider: String, ttsModel: String, apiKey: String, text: String, outFile: File): Boolean {
        val short = text.take(400)
        if (short.isBlank() || apiKey.isBlank()) return false
        return try {
            when (ttsProvider) {
                "OpenAI" -> {
                    val body = JSONObject()
                        .put("model", ttsModel.ifBlank { "tts-1" }.let { if (it == "gpt-4o-mini-tts") it else "tts-1" })
                        .put("input", short)
                        .put("voice", "alloy")
                        .put("response_format", "mp3")
                        .toString().toRequestBody(JSON)
                    val req = Request.Builder()
                        .url("https://api.openai.com/v1/audio/speech")
                        .header("Authorization", "Bearer $apiKey")
                        .post(body).build()
                    execute(req).use { r ->
                        if (!r.isSuccessful) return false
                        outFile.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
                        true
                    }
                }
                else -> false // Gemini TTS + KittenTTS: wired behind on-device work (SPEC), not in cloud drop
            }
        } catch (_: Throwable) { false }
    }
}
