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

private const val TAG = "FishAudioTTS"

class FishAudioTTSProvider(
    private val apiKey: String,
    private val referenceId: String = "",
    private val model: String = "s1",
    private val latency: String = "normal"
) : TTSProvider {

    override val providerName = "Fish Audio"
    override val requiresApiKey = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun getAvailableVoices(): List<VoiceOption> = VOICES

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("format", "mp3")
            addProperty("mp3_bitrate", 128)
            addProperty("normalize", true)
            addProperty("latency", latency)
            if (referenceId.isNotBlank()) {
                addProperty("reference_id", referenceId)
            }
        }

        val request = Request.Builder()
            .url("https://api.fish.audio/v1/tts")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("model", model)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: response.message
                Log.e(TAG, "Fish Audio TTS failed: HTTP ${response.code} - $errorBody")
                throw IOException("Fish Audio TTS failed: HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw IOException("Empty response body")
        }
    }

    override suspend fun validateConfiguration(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("API key is empty"))
            synthesize("test")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        val VOICES = listOf(
            VoiceOption("", "Default"),
            VoiceOption("933563129e564b19a115bedd57b7406a", "Sarah"),
            VoiceOption("bf322df2096a46f18c579d0baa36f41d", "Adrian"),
            VoiceOption("b347db033a6549378b48d00acb0d06cd", "Selene"),
            VoiceOption("536d3a5e000945adb7038665781a4aca", "Ethan"),
            VoiceOption("802e3bc2b27e49c2995d23ef70e6ac89", "Energetic Male"),
            VoiceOption("8ef4a238714b45718ce04243307c57a7", "E-girl")
        )

        val MODELS = listOf("s1", "speech-1.6", "speech-1.5")
    }
}
