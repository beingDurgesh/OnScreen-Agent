package com.onscreen.agent

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.ds by preferencesDataStore("onscreen")

/** BYOK store: 3 modules x (provider, model, key). Keys encrypted; models plain. No backend. */
object SettingsStore {
    private val P = mapOf(
        "sttProvider" to stringPreferencesKey("stt_provider"),
        "sttModel" to stringPreferencesKey("stt_model"),
        "vlmProvider" to stringPreferencesKey("vlm_provider"),
        "vlmModel" to stringPreferencesKey("vlm_model"),
        "ttsProvider" to stringPreferencesKey("tts_provider"),
        "ttsModel" to stringPreferencesKey("tts_model"),
        "braveKeyRef" to stringPreferencesKey("brave_key_ref"),
        "guardrailsOff" to stringPreferencesKey("guardrails_off"),
    )

    val STT_MODELS = mapOf(
        "whisper.cpp (on-device)" to listOf("tiny.en-q4", "base.en-q4"),
        "OpenAI" to listOf("whisper-1"),
        "Gemini" to listOf("gemini-2.0-flash")
    )
    val VLM_MODELS = mapOf(
        "OpenAI" to listOf("gpt-4o-mini", "gpt-4o", "gpt-4.1"),
        "Gemini" to listOf("gemini-2.5-flash", "gemini-2.5-pro"),
        "OpenRouter" to listOf("custom…")
    )
    val TTS_MODELS = mapOf(
        "KittenTTS (on-device)" to listOf("kitten-nano-15voices"),
        "OpenAI" to listOf("tts-1", "gpt-4o-mini-tts"),
        "Gemini" to listOf("gemini-2.5-flash-tts")
    )

    private fun keys(ctx: Context) = EncryptedSharedPreferences.create(
        ctx, "onscreen_keys",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    suspend fun get(ctx: Context, k: String): String? =
        ctx.ds.data.map { it[P[k]] }.first()

    suspend fun set(ctx: Context, k: String, v: String) {
        ctx.ds.edit { it[P[k]!!] = v }
    }

    fun putKey(ctx: Context, slot: String, v: String) {
        keys(ctx).edit().putString("key_$slot", v).apply() // stt|vlm|tts|brave
    }
    fun getKey(ctx: Context, slot: String): String =
        keys(ctx).getString("key_$slot", "") ?: ""
}
