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

private const val TAG = "ElevenLabsTTS"

class ElevenLabsTTSProvider(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = "eleven_multilingual_v2"
) : TTSProvider {

    override val providerName = "ElevenLabs"
    override val requiresApiKey = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun getAvailableVoices(): List<VoiceOption> = VOICES

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val effectiveVoiceId = voiceId.ifBlank { VOICES.first().id }

        val body = JsonObject().apply {
            addProperty("text", text)
            addProperty("model_id", modelId)
        }

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$effectiveVoiceId")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("xi-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "audio/mpeg")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: response.message
                Log.e(TAG, "ElevenLabs TTS failed: HTTP ${response.code} - $errorBody")
                throw IOException("ElevenLabs TTS failed: HTTP ${response.code}")
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
            VoiceOption("21m00Tcm4TlvDq8ikWAM", "Rachel"),
            VoiceOption("EXAVITQu4vr4xnSDxMaL", "Bella"),
            VoiceOption("MF3mGyEYCl7XYWbV9V6O", "Elli"),
            VoiceOption("TxGEqnHWrfWFTfGW9XjX", "Josh"),
            VoiceOption("VR6AewLTigWG4xSOukaG", "Arnold")
        )
    }
}
