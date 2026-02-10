package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Simple webhook client - POSTs to the configured URL
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * POST message to webhook URL and return response
     */
    suspend fun sendMessage(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null
    ): Result<OpenClawResponse> = withContext(Dispatchers.IO) {
        try {
            // Simple request body for /hooks/voice
            val requestBody = JsonObject().apply {
                addProperty("message", message)
                addProperty("session_id", sessionId)
            }

            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(webhookUrl)  // Use URL as-is
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    return@withContext Result.failure(
                        IOException("HTTP ${response.code}: $errorBody")
                    )
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IOException("Empty response")
                    )
                }

                // Parse response JSON (text + optional audio URL)
                val parsedResponse = parseResponse(responseBody)
                Result.success(parsedResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * POST message to streaming SSE endpoint and return events as a Flow
     */
    fun sendMessageStream(
        webhookUrl: String,
        message: String,
        sessionId: String,
        authToken: String? = null
    ): Flow<StreamEvent> = callbackFlow {
        val streamUrl = deriveStreamUrl(webhookUrl)

        val requestBody = JsonObject().apply {
            addProperty("message", message)
            addProperty("session_id", sessionId)
        }
        val jsonBody = gson.toJson(requestBody)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(streamUrl)
            .post(jsonBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")

        if (!authToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }

        val call = client.newCall(requestBuilder.build())

        withContext(Dispatchers.IO) {
            try {
                val response = call.execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: response.message
                    trySend(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
                    response.close()
                    return@withContext
                }

                val source = response.body?.source()
                if (source == null) {
                    trySend(StreamEvent.Error("Empty response body"))
                    response.close()
                    return@withContext
                }

                var eventType = ""
                val dataBuffer = StringBuilder()

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> dataBuffer.append(line.removePrefix("data:").trim())
                        line.isBlank() -> {
                            if (eventType.isNotEmpty() && dataBuffer.isNotEmpty()) {
                                parseSSEEvent(eventType, dataBuffer.toString())?.let { trySend(it) }
                            }
                            eventType = ""
                            dataBuffer.clear()
                        }
                        line.startsWith(":") -> { /* SSE comment, ignore */ }
                    }
                }

                response.close()
            } catch (e: Exception) {
                if (!call.isCanceled()) {
                    trySend(StreamEvent.Error(e.message ?: "Stream error"))
                }
            }
        }

        close()
        awaitClose { call.cancel() }
    }

    private fun deriveStreamUrl(webhookUrl: String): String {
        if (webhookUrl.trimEnd('/').endsWith("/stream")) return webhookUrl
        return webhookUrl.trimEnd('/') + "/stream"
    }

    /**
     * Test connection to the webhook
     */
    suspend fun testConnection(
        webhookUrl: String,
        authToken: String?
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Try a HEAD request first (lightweight)
            var requestBuilder = Request.Builder()
                .url(webhookUrl)
                .head()

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            var request = requestBuilder.build()
            
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return@withContext Result.success(true)
                    // If Method Not Allowed (405), try POST
                    if (response.code == 405) {
                         // Fallthrough to POST
                    } else {
                         return@withContext Result.failure(IOException("HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                // Fallthrough to POST on error (some servers reject HEAD)
            }

            // Fallback: POST with dummy data
            val requestBody = JsonObject().apply {
                addProperty("message", "ping")
                addProperty("session_id", "test-connection")
            }
            
            val jsonBody = gson.toJson(requestBody)
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            requestBuilder = Request.Builder()
                .url(webhookUrl)
                .post(jsonBody)
                .addHeader("Content-Type", "application/json")

            if (!authToken.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            request = requestBuilder.build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    val errorBody = response.body?.string() ?: response.message
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parse response JSON and extract text + optional audio URL
     */
    private fun parseResponse(json: String): OpenClawResponse {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)

            // Extract response text from various formats
            val text = obj.get("response")?.asString
                ?: obj.getAsJsonArray("choices")?.let { choices ->
                    choices.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString
                }
                ?: obj.get("text")?.asString
                ?: obj.get("message")?.asString
                ?: obj.get("content")?.asString

            // Extract audio URL if present
            val audioUrl = obj.get("audio_url")?.asString

            // Extract model if present
            val model = obj.get("model")?.asString

            OpenClawResponse(response = text ?: json, audioUrl = audioUrl, model = model)
        } catch (e: Exception) {
            OpenClawResponse(response = json)
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null,
    val audioUrl: String? = null,
    val model: String? = null
) {
    fun getResponseText(): String? = response
    fun hasServerAudio(): Boolean = !audioUrl.isNullOrBlank()
}
