package com.onscreen.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Minimal cloud router: OpenAI / Gemini / OpenRouter, direct from phone. No app backend. */
object CloudClients {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Returns ONE raw JSON action string from the VLM. Caller validates via RustBridge.parseActionSafe. */
    fun nextAction(
        vlmProvider: String, vlmModel: String, apiKey: String, system: String, user: String,
    ): String {
        val mt = "application/json".toMediaType()
        return when (vlmProvider) {
            "Gemini" -> {
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", system))))
                    .put("contents", org.json.JSONArray().put(JSONObject().put("parts", org.json.JSONArray().put(JSONObject().put("text", user)))))
                    .put("generationConfig", JSONObject().put("response_mime_type", "application/json"))
                    .toString().toRequestBody(mt)
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$vlmModel:generateContent?key=$apiKey")
                    .post(body).build()
                http.newCall(req).execute().use { r ->
                    val t = JSONObject(r.body!!.string())
                        .getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                        .getJSONObject(0).getString("text")
                    t.trim()
                }
            }
            "OpenRouter" -> {
                val body = JSONObject()
                    .put("model", vlmModel)
                    .put("messages", org.json.JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .toString().toRequestBody(mt)
                val req = Request.Builder()
                    .url("https://openrouter.ai/api/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body).build()
                http.newCall(req).execute().use { r ->
                    JSONObject(r.body!!.string()).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }
            else -> { // OpenAI default
                val body = JSONObject()
                    .put("model", vlmModel.ifBlank { "gpt-4o-mini" })
                    .put("messages", org.json.JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)))
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .toString().toRequestBody(mt)
                val req = Request.Builder()
                    .url("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body).build()
                http.newCall(req).execute().use { r ->
                    JSONObject(r.body!!.string()).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content").trim()
                }
            }
        }
    }
}
