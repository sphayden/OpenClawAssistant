package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Typed SSE events from the /stream endpoint
 */
sealed class StreamEvent {
    data class Text(val chunk: String) : StreamEvent()
    data class Done(val fullText: String, val model: String?) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

private val gson = Gson()

/**
 * Parse a single SSE event (event type + data payload) into a StreamEvent.
 * Returns null for unknown or ignored event types (e.g. "audio").
 */
fun parseSSEEvent(eventType: String, data: String): StreamEvent? {
    return try {
        when (eventType) {
            "text" -> {
                val json = gson.fromJson(data, JsonObject::class.java)
                val chunk = json.get("chunk")?.asString ?: return null
                StreamEvent.Text(chunk)
            }
            "audio" -> null // Ignored — client TTS always
            "done" -> {
                val json = gson.fromJson(data, JsonObject::class.java)
                val fullText = json.get("fullText")?.asString ?: ""
                val model = json.get("model")?.asString
                StreamEvent.Done(fullText, model)
            }
            "error" -> {
                val json = gson.fromJson(data, JsonObject::class.java)
                val message = json.get("message")?.asString ?: "Unknown stream error"
                StreamEvent.Error(message)
            }
            else -> null
        }
    } catch (e: Exception) {
        null // Malformed event — skip silently
    }
}
