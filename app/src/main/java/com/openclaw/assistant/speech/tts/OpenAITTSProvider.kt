package com.openclaw.assistant.speech.tts

import android.util.Log
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAITTSProvider"

class OpenAITTSProvider(
    private val apiKey: String,
    private val voice: String = "nova",
    private val model: String = "tts-1"
) : TTSProvider {

    override val providerName = "OpenAI"
    override val requiresApiKey = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun getAvailableVoices(): List<VoiceOption> = VOICES

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("input", text)
            addProperty("voice", voice)
            addProperty("response_format", "mp3")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: response.message
                Log.e(TAG, "OpenAI TTS failed: HTTP ${response.code} - $errorBody")
                throw IOException("OpenAI TTS failed: HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    override suspend fun validateConfiguration(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("API key is empty"))
            // Synthesize a short test phrase
            synthesize("test")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        val VOICES = listOf(
            VoiceOption("alloy", "Alloy"),
            VoiceOption("ash", "Ash"),
            VoiceOption("ballad", "Ballad"),
            VoiceOption("coral", "Coral"),
            VoiceOption("echo", "Echo"),
            VoiceOption("fable", "Fable"),
            VoiceOption("nova", "Nova"),
            VoiceOption("onyx", "Onyx"),
            VoiceOption("sage", "Sage"),
            VoiceOption("shimmer", "Shimmer")
        )

        val MODELS = listOf("tts-1", "tts-1-hd")
    }
}
