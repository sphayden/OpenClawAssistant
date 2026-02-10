# OpenClaw Assistant - TTS Implementation Guide for Opus

This document provides specific code changes and implementation steps. Work through these sequentially.

## Phase 1: TTS Provider Interface & Implementations

### Step 1.1: Create Abstract TTS Provider Interface
**File:** `app/src/main/java/com/opensea/assistant/tts/TTSProvider.kt`

```kotlin
package com.opensea.assistant.tts

import android.content.Context

interface TTSProvider {
    /**
     * Synthesize text to speech
     * @param text Text to synthesize
     * @return ByteArray of audio data (MP3 format)
     */
    suspend fun synthesize(text: String): ByteArray

    /**
     * Get list of available voices for this provider
     */
    fun getAvailableVoices(): List<String>

    /**
     * Validate API key and connectivity
     * @return Pair<Boolean, String> (isValid, errorMessage)
     */
    suspend fun validateConfiguration(): Pair<Boolean, String>

    /**
     * Get provider display name
     */
    fun getProviderName(): String

    /**
     * Check if provider requires API key
     */
    fun requiresApiKey(): Boolean
}
```

### Step 1.2: OpenAI TTS Provider
**File:** `app/src/main/java/com/opensea/assistant/tts/OpenAITTSProvider.kt`

```kotlin
package com.opensea.assistant.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAITTSProvider(
    private val apiKey: String,
    private val voice: String = "nova",
    private val model: String = "tts-1"  // or "tts-1-hd"
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val error = response.body?.string() ?: "Unknown error"
            Log.e("OpenAITTS", "Error: ${response.code} - $error")
            throw Exception("OpenAI TTS failed: ${response.code}")
        }

        response.body?.bytes() ?: throw Exception("Empty response from OpenAI")
    }

    override fun getAvailableVoices(): List<String> {
        return listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
    }

    override suspend fun validateConfiguration(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Try a small synthesis to validate API key
            synthesize("test")
            Pair(true, "")
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    override fun getProviderName(): String = "OpenAI"

    override fun requiresApiKey(): Boolean = true
}
```

### Step 1.3: ElevenLabs TTS Provider
**File:** `app/src/main/java/com/opensea/assistant/tts/ElevenLabsTTSProvider.kt`

```kotlin
package com.opensea.assistant.tts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ElevenLabsTTSProvider(
    private val apiKey: String,
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM",  // Rachel (default)
    private val stability: Float = 0.5f,
    private val similarityBoost: Float = 0.75f
) : TTSProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        val requestBody = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_monolingual_v1")
            put("voice_settings", JSONObject().apply {
                put("stability", stability)
                put("similarity_boost", similarityBoost)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            val error = response.body?.string() ?: "Unknown error"
            Log.e("ElevenLabsTTS", "Error: ${response.code} - $error")
            throw Exception("ElevenLabs TTS failed: ${response.code}")
        }

        response.body?.bytes() ?: throw Exception("Empty response from ElevenLabs")
    }

    override fun getAvailableVoices(): List<String> {
        // Common voice IDs - in production, fetch from API
        return listOf(
            "21m00Tcm4TlvDq8ikWAM" to "Rachel",
            "EXAVITQu4vr4xnSDxMaL" to "Bella",
            "MF3mGyEYCl7XYWbV9V6H" to "Elli",
            "TxGEqnHWrfWFTfGW9XjX" to "Josh",
            "VR6AewLsTvjNmNxZlHoj" to "Arnold"
        ).map { it.first }
    }

    override suspend fun validateConfiguration(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        return@withContext try {
            synthesize("test")
            Pair(true, "")
        } catch (e: Exception) {
            Pair(false, e.message ?: "Unknown error")
        }
    }

    override fun getProviderName(): String = "ElevenLabs"

    override fun requiresApiKey(): Boolean = true
}
```

### Step 1.4: Local TTS Provider (Fallback)
**File:** `app/src/main/java/com/opensea/assistant/tts/LocalTTSProvider.kt`

```kotlin
package com.opensea.assistant.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalTTSProvider(private val context: Context) : TTSProvider {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private val audioFile = File(context.cacheDir, "local_tts_output.wav")

    init {
        tts = TextToSpeech(context) { status ->
            isReady = status == TextToSpeech.SUCCESS
        }
    }

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.Main) {
        return@withContext suspendCancellableCoroutine { continuation ->
            if (!isReady) {
                continuation.resumeWithException(Exception("TextToSpeech not ready"))
                return@suspendCancellableCoroutine
            }

            // Note: Android's TextToSpeech synthesizeToFile is async and doesn't easily
            // return ByteArray. This is a simplified fallback that returns a WAV header.
            // For production, use a proper TTS library or stream to speaker + record.
            
            tts?.synthesizeToFile(text, null, audioFile)
            
            // In practice, you'd need to:
            // 1. Wait for synthesis to complete
            // 2. Read the WAV file
            // 3. Return bytes
            // For now, this is a placeholder.
            continuation.resumeWithException(
                Exception("LocalTTSProvider requires proper Android TTS integration")
            )
        }
    }

    override fun getAvailableVoices(): List<String> {
        // Android default voices - simplified
        return listOf("default")
    }

    override suspend fun validateConfiguration(): Pair<Boolean, String> {
        return if (isReady) {
            Pair(true, "")
        } else {
            Pair(false, "TextToSpeech not initialized")
        }
    }

    override fun getProviderName(): String = "Local (Android)"

    override fun requiresApiKey(): Boolean = false

    fun shutdown() {
        tts?.shutdown()
    }
}
```

## Phase 2: Settings & Configuration

### Step 2.1: Create Settings Manager
**File:** `app/src/main/java/com/opensea/assistant/settings/TTSSettings.kt`

```kotlin
package com.opensea.assistant.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TTSSettings(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "tts_preferences",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // TTS Provider
    var ttsProvider: String
        get() = prefs.getString("tts_provider", "local") ?: "local"
        set(value) = prefs.edit().putString("tts_provider", value).apply()

    // API Keys (encrypted)
    var openaiApiKey: String
        get() = prefs.getString("openai_api_key", "") ?: ""
        set(value) = prefs.edit().putString("openai_api_key", value).apply()

    var elevenlabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(value) = prefs.edit().putString("elevenlabs_api_key", value).apply()

    // Voice & Model Settings
    var ttsVoice: String
        get() = prefs.getString("tts_voice", "nova") ?: "nova"
        set(value) = prefs.edit().putString("tts_voice", value).apply()

    var ttsModel: String
        get() = prefs.getString("tts_model", "tts-1") ?: "tts-1"
        set(value) = prefs.edit().putString("tts_model", value).apply()

    // Speech Parameters
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", 1.0f)
        set(value) = prefs.edit().putFloat("speech_rate", value).apply()

    var speechPitch: Float
        get() = prefs.getFloat("speech_pitch", 1.0f)
        set(value) = prefs.edit().putFloat("speech_pitch", value).apply()

    // LLM Endpoint
    var llmStreamingEnabled: Boolean
        get() = prefs.getBoolean("llm_streaming_enabled", false)
        set(value) = prefs.edit().putBoolean("llm_streaming_enabled", value).apply()

    var llmEndpointType: String
        get() = prefs.getString("llm_endpoint_type", "text") ?: "text"
        set(value) = prefs.edit().putString("llm_endpoint_type", value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}
```

### Step 2.2: Update Settings UI (Jetpack Compose)
**File:** `app/src/main/java/com/opensea/assistant/ui/SettingsScreen.kt` (add TTS section)

```kotlin
// In your existing SettingsScreen, add this section:

// TTS Provider Selection
Spacer(modifier = Modifier.height(16.dp))
Text("Text-to-Speech", style = MaterialTheme.typography.titleMedium)

var selectedProvider by remember { mutableStateOf(ttsSettings.ttsProvider) }
val providers = listOf("local", "openai", "elevenlabs")
val providerLabels = listOf("Local (Default)", "OpenAI", "ElevenLabs")

Row(modifier = Modifier.fillMaxWidth()) {
    providers.forEachIndexed { index, provider ->
        Button(
            onClick = {
                selectedProvider = provider
                ttsSettings.ttsProvider = provider
            },
            modifier = Modifier
                .weight(1f)
                .padding(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedProvider == provider)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(providerLabels[index], fontSize = 12.sp)
        }
    }
}

// API Key Input (if provider requires it)
if (selectedProvider == "openai") {
    Spacer(modifier = Modifier.height(12.dp))
    var apiKey by remember { mutableStateOf(ttsSettings.openaiApiKey) }
    TextField(
        value = apiKey,
        onValueChange = {
            apiKey = it
            ttsSettings.openaiApiKey = it
        },
        label = { Text("OpenAI API Key") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
    
    // Voice selector
    var selectedVoice by remember { mutableStateOf(ttsSettings.ttsVoice) }
    val voices = listOf("alloy", "echo", "fable", "onyx", "nova", "shimmer")
    ExposedDropdownMenuBox(
        expanded = false,
        onExpandedChange = { }
    ) {
        TextField(
            value = selectedVoice,
            onValueChange = {},
            label = { Text("Voice") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .menuAnchor(),
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon() }
        )
        ExposedDropdownMenu(
            expanded = false,
            onDismissRequest = { }
        ) {
            voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice) },
                    onClick = {
                        selectedVoice = voice
                        ttsSettings.ttsVoice = voice
                    }
                )
            }
        }
    }
    
    // Test button
    Button(
        onClick = {
            // TODO: Call testTTSSettings()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text("Test Voice")
    }
}

// Streaming toggle
Spacer(modifier = Modifier.height(12.dp))
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text("Stream LLM responses")
    Switch(
        checked = ttsSettings.llmStreamingEnabled,
        onCheckedChange = { ttsSettings.llmStreamingEnabled = it }
    )
}
```

## Phase 3: TTS Manager & Integration

### Step 3.1: Create TTS Manager
**File:** `app/src/main/java/com/opensea/assistant/tts/TTSManager.kt`

```kotlin
package com.opensea.assistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.opensea.assistant.settings.TTSSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TTSManager(private val context: Context) {
    private val settings = TTSSettings(context)
    private val mediaPlayer = MediaPlayer()
    private var currentProvider: TTSProvider? = null
    private val audioCache = File(context.cacheDir, "tts_audio")

    init {
        audioCache.mkdirs()
        initializeProvider()
    }

    private fun initializeProvider() {
        currentProvider = when (settings.ttsProvider) {
            "openai" -> {
                val apiKey = settings.openaiApiKey
                if (apiKey.isEmpty()) {
                    Log.w("TTSManager", "OpenAI API key not set, falling back to local")
                    LocalTTSProvider(context)
                } else {
                    OpenAITTSProvider(
                        apiKey = apiKey,
                        voice = settings.ttsVoice,
                        model = settings.ttsModel
                    )
                }
            }
            "elevenlabs" -> {
                val apiKey = settings.elevenlabsApiKey
                if (apiKey.isEmpty()) {
                    Log.w("TTSManager", "ElevenLabs API key not set, falling back to local")
                    LocalTTSProvider(context)
                } else {
                    ElevenLabsTTSProvider(
                        apiKey = apiKey,
                        voiceId = settings.ttsVoice
                    )
                }
            }
            else -> LocalTTSProvider(context)
        }
    }

    suspend fun synthesizeAndPlay(text: String): Boolean = withContext(Dispatchers.Main) {
        return@withContext try {
            initializeProvider()  // Refresh provider in case settings changed
            val provider = currentProvider ?: return@withContext false
            
            Log.d("TTSManager", "Synthesizing with ${provider.getProviderName()}")
            
            // Generate audio
            val audioBytes = provider.synthesize(text)
            
            // Save to temp file
            val audioFile = File(audioCache, "response_${System.currentTimeMillis()}.mp3")
            audioFile.writeBytes(audioBytes)
            
            // Play
            mediaPlayer.reset()
            mediaPlayer.setDataSource(audioFile.absolutePath)
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mediaPlayer.prepare()
            mediaPlayer.start()
            
            Log.d("TTSManager", "Playing audio: ${audioFile.name}")
            true
        } catch (e: Exception) {
            Log.e("TTSManager", "TTS synthesis failed", e)
            false
        }
    }

    suspend fun validateCurrentProvider(): Pair<Boolean, String> {
        return currentProvider?.validateConfiguration() 
            ?: Pair(false, "No TTS provider initialized")
    }

    fun stopPlayback() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
            mediaPlayer.reset()
        }
    }

    fun shutdown() {
        stopPlayback()
        mediaPlayer.release()
        (currentProvider as? LocalTTSProvider)?.shutdown()
    }
}
```

## Phase 4: Integration with VoiceManager

### Step 4.1: Update VoiceManager to Use TTS
**File:** `app/src/main/java/com/opensea/assistant/voice/VoiceManager.kt`

Replace the existing audio/TTS handling with:

```kotlin
// In your existing VoiceManager class:

private val ttsManager = TTSManager(context)

// In your LLM response handler:
suspend fun handleLLMResponse(responseText: String) {
    // Instead of expecting audio from adapter:
    // adapter.voice → returns text only
    
    // Now handle TTS locally:
    val success = ttsManager.synthesizeAndPlay(responseText)
    if (!success) {
        // Fallback: show notification that TTS failed
        Log.e("VoiceManager", "Failed to synthesize response")
    }
}

// For streaming endpoint:
suspend fun handleStreamingResponse(streamUrl: String) {
    // Use SSE to receive text chunks
    // Buffer and synthesize as they arrive
    
    var buffer = ""
    val chunkSize = 300
    
    subscribeToSSEStream(streamUrl) { event ->
        when (event.type) {
            "text" -> {
                buffer += event.data["chunk"]
                
                // Synthesize when buffer is full
                if (buffer.length >= chunkSize) {
                    ttsManager.synthesizeAndPlay(buffer)
                    buffer = ""
                }
            }
            "done" -> {
                // Synthesize remaining buffer
                if (buffer.isNotEmpty()) {
                    ttsManager.synthesizeAndPlay(buffer)
                }
            }
        }
    }
}
```

## Phase 5: Build & Test

### Step 5.1: Update build.gradle.kts
Add dependencies (if not already present):

```kotlin
dependencies {
    // ... existing deps ...
    
    // Security (for encrypted prefs)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // Already present:
    // - okhttp3
    // - jetpack compose
    // - json parsing
}
```

### Step 5.2: Testing Checklist

- [ ] App starts without crashes
- [ ] Settings screen shows TTS options
- [ ] API key can be entered and encrypted
- [ ] Provider selection works
- [ ] Voice dropdown populated (OpenAI)
- [ ] Test button works (sends small request to TTS)
- [ ] LLM response → TTS → audio plays
- [ ] Streaming endpoint works (`/voice/stream`)
- [ ] Multiple chunks synthesized incrementally
- [ ] Error handling: invalid API key, network error, empty response
- [ ] Fallback to local TTS if API key missing
- [ ] Audio files cached properly
- [ ] No API keys logged or exposed

## Debugging

**Log tags to monitor:**
- `TTSManager` - TTS operations
- `VoiceManager` - LLM/voice flow
- `OpenAITTS`, `ElevenLabsTTS` - Provider-specific

**Common issues:**
1. **"No API key found"** → Check TTSSettings storage
2. **"Synthesis failed"** → Check API key validity via `validateCurrentProvider()`
3. **No audio playback** → Check MediaPlayer initialization and audio permissions
4. **Slow TTS** → Normal for first call; caching helps subsequent calls

## Notes

- Keep API keys **encrypted** at all times
- Test with small text chunks first (cheaper, faster)
- ElevenLabs has free trial; OpenAI requires paid account
- Local TTS is free but lower quality
- Consider adding request caching by text hash (like adapter does)
