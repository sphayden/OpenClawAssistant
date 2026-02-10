# OpenClawAssistant Server TTS Implementation Guide

## Overview

Add server-side TTS support to the OpenClawAssistant Android app. When the API response includes an `audio_url` field, the app downloads and plays that audio instead of using local Android TTS.

**Repository:** `https://github.com/sphayden/OpenClawAssistant`

## API Response Format Change

**Current format:**
```json
{
  "response": "Hello, how can I help you?"
}
```

**New format (backward compatible):**
```json
{
  "response": "Hello, how can I help you?",
  "audio_url": "http://server:8765/audio/abc123.mp3"
}
```

The `audio_url` field is optional. If missing, the app falls back to local TTS.

---

## File Changes Required

### 1. `app/src/main/java/com/openclaw/assistant/api/OpenClawClient.kt`

**Change the `OpenClawResponse` data class to include `audioUrl`:**

Find this code (around line 88):
```kotlin
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
```

Replace with:
```kotlin
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null,
    val audioUrl: String? = null
) {
    fun getResponseText(): String? = response
    fun hasServerAudio(): Boolean = !audioUrl.isNullOrBlank()
}
```

**Modify the `extractResponseText` function to also extract `audio_url`:**

Find the `extractResponseText` function (around line 75) and replace the entire function with a new `parseResponse` function:

```kotlin
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
        
        OpenClawResponse(response = text, audioUrl = audioUrl)
    } catch (e: Exception) {
        OpenClawResponse(response = json)
    }
}
```

**Update the `sendMessage` function to use `parseResponse`:**

In the `sendMessage` function, find this line (around line 52):
```kotlin
val text = extractResponseText(responseBody)
Result.success(OpenClawResponse(response = text ?: responseBody))
```

Replace with:
```kotlin
val parsedResponse = parseResponse(responseBody)
Result.success(parsedResponse)
```

---

### 2. Create new file: `app/src/main/java/com/openclaw/assistant/speech/AudioPlayer.kt`

Create this new file:

```kotlin
package com.openclaw.assistant.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

private const val TAG = "AudioPlayer"

/**
 * Plays audio from URLs (for server-side TTS)
 */
class AudioPlayer(private val context: Context) {
    
    private var mediaPlayer: MediaPlayer? = null
    
    /**
     * Download audio from URL and play it
     * @return true if playback completed successfully
     */
    suspend fun playFromUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading audio from: $url")
            
            // Download to temp file
            val tempFile = File(context.cacheDir, "server_tts_${System.currentTimeMillis()}.mp3")
            URL(url).openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "Audio downloaded, playing...")
            
            // Play the file
            playFile(tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio from URL: $url", e)
            false
        }
    }
    
    private suspend fun playFile(file: File): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            stop() // Stop any existing playback
            
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                
                setOnCompletionListener {
                    Log.d(TAG, "Playback completed")
                    file.delete() // Clean up temp file
                    release()
                    mediaPlayer = null
                    if (continuation.isActive) continuation.resume(true)
                }
                
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    file.delete()
                    release()
                    mediaPlayer = null
                    if (continuation.isActive) continuation.resume(false)
                    true
                }
                
                prepare()
                start()
            }
            
            continuation.invokeOnCancellation {
                stop()
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play file", e)
            file.delete()
            if (continuation.isActive) continuation.resume(false)
        }
    }
    
    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaPlayer", e)
        }
        mediaPlayer = null
    }
    
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
}
```

---

### 3. `app/src/main/java/com/openclaw/assistant/service/OpenClawSession.kt`

**Add the AudioPlayer field near the top of the class (around line 50, near other manager declarations):**

Find where `ttsManager` is declared:
```kotlin
private val ttsManager = TTSManager(context)
```

Add below it:
```kotlin
private val audioPlayer = AudioPlayer(context)
```

**Modify the response handling in `sendMessage` function:**

Find this section (around line 345-360):
```kotlin
result.fold(
    onSuccess = { response ->
        val responseText = response.getResponseText()
        if (responseText != null) {
            displayText.value = responseText
            
            // Save AI Message
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
            
            if (settings.ttsEnabled) {
                speakResponse(responseText)
            } else if (settings.continuousMode) {
```

Replace the TTS section with:
```kotlin
result.fold(
    onSuccess = { response ->
        val responseText = response.getResponseText()
        if (responseText != null) {
            displayText.value = responseText
            
            // Save AI Message
            currentSessionId?.let { sessionId ->
                chatRepository.addMessage(sessionId, responseText, isUser = false)
            }
            
            if (settings.ttsEnabled) {
                // Check for server-generated audio first
                if (response.hasServerAudio()) {
                    playServerAudio(response.audioUrl!!, responseText)
                } else {
                    speakResponse(responseText)
                }
            } else if (settings.continuousMode) {
```

**Add the new `playServerAudio` function after the `speakResponse` function (around line 395):**

```kotlin
/**
 * Play server-generated TTS audio, with fallback to local TTS
 */
private fun playServerAudio(audioUrl: String, fallbackText: String) {
    currentState.value = AssistantState.SPEAKING
    
    scope.launch {
        Log.d(TAG, "Playing server audio: $audioUrl")
        val success = audioPlayer.playFromUrl(audioUrl)
        
        // Abandon audio focus after playback
        abandonAudioFocus()
        
        if (success) {
            Log.d(TAG, "Server audio playback completed successfully")
            currentState.value = AssistantState.IDLE
            
            // Continue listening if continuous mode enabled
            if (settings.continuousMode) {
                delay(500)
                startListening()
            }
        } else {
            // Fallback to local TTS if server audio fails
            Log.w(TAG, "Server audio failed, falling back to local TTS")
            ttsManager.speak(fallbackText)
            
            if (settings.continuousMode) {
                delay(500)
                startListening()
            }
        }
    }
}
```

**Add the import for AudioPlayer at the top of the file:**

```kotlin
import com.openclaw.assistant.speech.AudioPlayer
```

---

### 4. `app/src/main/java/com/openclaw/assistant/ui/chat/ChatViewModel.kt`

The ChatViewModel also handles TTS for the chat UI. Make similar changes:

**Add AudioPlayer field (near line 35, with other declarations):**
```kotlin
private val audioPlayer = AudioPlayer(application)
```

**Find the `handleVoiceResponse` or similar function that calls TTS, and add the same server audio check:**

Look for where `ttsManager.speak()` is called and wrap it with the audio URL check:
```kotlin
if (response.hasServerAudio()) {
    scope.launch {
        val success = audioPlayer.playFromUrl(response.audioUrl!!)
        if (!success) {
            // Fallback to local TTS
            ttsManager.speak(responseText)
        }
    }
} else {
    ttsManager.speak(responseText)
}
```

**Add the import:**
```kotlin
import com.openclaw.assistant.speech.AudioPlayer
```

---

## Testing

1. Build the APK with these changes
2. Configure your voice adapter to return `audio_url` in responses
3. Test with server TTS:
   - Response should play audio from URL
   - Check logcat for "Playing server audio" messages
4. Test fallback:
   - If audio download fails, should fall back to local TTS
   - If `audio_url` is missing, should use local TTS
5. Test continuous mode:
   - After audio playback, should resume listening if enabled

## Server-Side Setup

The voice adapter (Go binary) is already updated to support server TTS. Configure with:

```bash
TTS_ENABLED=true
OPENAI_API_KEY=sk-your-key
TTS_VOICE=nova  # Options: alloy, echo, fable, onyx, nova, shimmer
AUDIO_BASE_URL=http://your-server-ip:8765
```

---

## Summary of Changes

| File | Change |
|------|--------|
| `OpenClawClient.kt` | Add `audioUrl` field to response, parse `audio_url` from JSON |
| `AudioPlayer.kt` | NEW FILE - Download and play audio from URLs |
| `OpenClawSession.kt` | Add AudioPlayer, check for server audio before local TTS |
| `ChatViewModel.kt` | Same changes as OpenClawSession for chat UI |

Total: ~150 lines of new/modified Kotlin code across 4 files.
