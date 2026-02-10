# OpenClaw Assistant — Client-Side Cloud TTS: Implementation Spec

## 1. Problem

TTS currently has two paths: server-generated audio via `audio_url` (requires server TTS setup) or Android's built-in `TextToSpeech` (low quality, device-dependent). Users need access to high-quality cloud TTS (OpenAI, ElevenLabs) directly from the app, without server-side changes.

## 2. Solution

Add a TTS provider abstraction layer that routes synthesis through the user's chosen provider (Local, OpenAI, or ElevenLabs). The system must handle both complete text (current bulk JSON responses) and incremental text chunks (future SSE streaming).

## 3. Constraints & Standards

### 3.1 Code Standards (derived from existing codebase)

| Convention | Pattern | Example |
|---|---|---|
| Error handling | `Result<T>` + `fold()` | `result.fold(onSuccess = {}, onFailure = {})` |
| Background work | `withContext(Dispatchers.IO)` | Network calls, file I/O |
| Callback conversion | `suspendCancellableCoroutine` | MediaPlayer completion |
| Logging | `private const val TAG` at file level, `Log.d`/`Log.e` | `Log.e(TAG, "msg")` |
| Singletons | `@Volatile` + `synchronized` double-check | `getInstance(context)` |
| Settings access | Property delegates on `EncryptedSharedPreferences` | `get() = prefs.getString(KEY, "")` |
| Compose state | `var x by remember { mutableStateOf(settings.x) }` | All settings fields |
| Save action | Bulk write to `SettingsRepository` on Save button click | See `SettingsActivity.kt:109-118` |
| HTTP client | Per-class `OkHttpClient` via builder, reused across calls | `OpenClawClient.kt:19-23` |
| JSON | `Gson` (not `org.json.JSONObject`) | Consistent with `OpenClawClient` |
| Context | `private val context: Context` constructor param; use `.applicationContext` for singletons | Prevents Activity leaks |

### 3.2 Security Requirements

- API keys stored in `EncryptedSharedPreferences` (AES256_GCM values, AES256_SIV keys) — same file as existing settings (`openclaw_secure_prefs`)
- API keys never logged, even at debug level
- API keys transmitted only over HTTPS in auth headers
- Password masking on API key input fields (with visibility toggle)
- No hardcoded keys, tokens, or voice IDs that could be mistaken for secrets

### 3.3 Streaming Compatibility

The current `OpenClawClient` returns bulk JSON (`response.body?.string()`). There is no SSE/streaming transport yet. However, the TTS layer must be designed so that when streaming is added later, it works without refactoring the TTS code.

**Design approach:**
- `speak(text: String): Boolean` — for complete text (current use case)
- `speakStreaming(textFlow: Flow<String>): Boolean` — for chunked text (future SSE use case)
- Both methods are on `TTSOrchestrator`; consumers call whichever matches their response type
- The streaming method buffers text, synthesizes at sentence boundaries, and queues audio for sequential playback

---

## 4. New Files

All new files go under `app/src/main/java/com/openclaw/assistant/speech/tts/`.

### 4.1 `TTSProvider.kt` — Interface

```kotlin
package com.openclaw.assistant.speech.tts

interface TTSProvider {
    suspend fun synthesize(text: String): ByteArray
    fun getAvailableVoices(): List<VoiceOption>
    suspend fun validateConfiguration(): Result<Unit>
    val providerName: String
    val requiresApiKey: Boolean
}

data class VoiceOption(
    val id: String,
    val displayName: String
)
```

**Notes:**
- `synthesize()` returns raw audio bytes (MP3). Runs on `Dispatchers.IO`.
- `validateConfiguration()` uses `Result<Unit>` (not `Pair<Boolean, String>` — matches repo pattern).
- This interface is only for cloud providers. Local TTS uses the existing `TTSManager` directly (it speaks via Android TTS, doesn't return bytes).

### 4.2 `OpenAITTSProvider.kt`

```kotlin
package com.openclaw.assistant.speech.tts

class OpenAITTSProvider(
    private val apiKey: String,
    private val voice: String = "nova",
    private val model: String = "tts-1"
) : TTSProvider
```

**Implementation details:**
- POST `https://api.openai.com/v1/audio/speech`
- Headers: `Authorization: Bearer $apiKey`, `Content-Type: application/json`
- Body (Gson): `{"model": "$model", "input": "$text", "voice": "$voice"}`
- Response: raw MP3 bytes (`response.body?.bytes()`)
- OkHttp client: 30s connect, 120s read (matches existing `OpenClawClient` pattern)
- Voices: `alloy, ash, ballad, coral, echo, fable, onyx, nova, sage, shimmer`
- `validateConfiguration()`: small synthesis request ("test"), wrapped in `Result`
- **Text limit**: OpenAI TTS has 4096 char limit. If text exceeds 4000 chars, truncate at the last sentence boundary before 4000 and log a warning. Do not silently fail.
- Error responses: parse error body for message, throw `IOException("OpenAI TTS: ${response.code} - $errorMsg")`

### 4.3 `ElevenLabsTTSProvider.kt`

```kotlin
package com.openclaw.assistant.speech.tts

class ElevenLabsTTSProvider(
    private val apiKey: String,
    private val voiceId: String = "21m00Tcm4TlvDq8ikWAM",
    private val modelId: String = "eleven_multilingual_v2"
) : TTSProvider
```

**Implementation details:**
- POST `https://api.elevenlabs.io/v1/text-to-speech/$voiceId`
- Headers: `xi-api-key: $apiKey`, `Content-Type: application/json`
- Body (Gson): `{"text": "$text", "model_id": "$modelId", "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}`
- Response: raw MP3 bytes
- Voices (hardcoded for now):
  - `21m00Tcm4TlvDq8ikWAM` → "Rachel"
  - `EXAVITQu4vr4xnSDxMaL` → "Bella"
  - `MF3mGyEYCl7XYWbV9V6H` → "Elli"
  - `TxGEqnHWrfWFTfGW9XjX` → "Josh"
  - `VR6AewLsTvjNmNxZlHoj` → "Arnold"
- `validateConfiguration()`: GET `https://api.elevenlabs.io/v1/voices` with api key header. Success = valid key.

### 4.4 `TTSOrchestrator.kt` — Central Dispatcher

```kotlin
package com.openclaw.assistant.speech.tts

class TTSOrchestrator(private val context: Context) {
    private val settings = SettingsRepository.getInstance(context)
    private val localTTS = TTSManager(context)
    private val audioPlayer = AudioPlayer(context)

    suspend fun speak(text: String): Boolean
    suspend fun speakStreaming(textFlow: Flow<String>): Boolean
    fun stop()
    fun shutdown()
}
```

**`speak(text)` logic:**
```
1. Read settings.ttsProvider
2. If "local" → localTTS.speak(text) → return result
3. If "openai" or "elevenlabs":
   a. Build provider with API key + voice + model from settings
   b. If API key is blank → log warning → fall through to local
   c. try { provider.synthesize(text) } → audioBytes
   d. audioPlayer.playFromBytes(audioBytes) → return result
   e. catch → Log.e, fall back to localTTS.speak(text)
```

**`speakStreaming(textFlow)` logic (streaming-ready):**
```
1. Read settings.ttsProvider
2. If "local":
   - Collect chunks, buffer until sentence boundary
   - localTTS.speakQueued(buffered) for each buffer flush
   - Return true when flow completes
3. If cloud provider:
   - Collect chunks into buffer (StringBuilder)
   - When buffer hits sentence boundary OR 300+ chars:
     - Synthesize buffered text → audioBytes
     - Write to temp file, add to playback queue
     - Clear buffer
   - Play queue sequentially (AudioPlayer plays one file, on completion → next)
   - On flow completion: flush remaining buffer
```

**Sentence boundary detection** (for streaming buffer flush):
```kotlin
private fun findSentenceBoundary(text: String, minLength: Int = 100): Int {
    if (text.length < minLength) return -1
    val sentenceEnds = listOf(". ", "! ", "? ", ".\n", "!\n", "?\n")
    return sentenceEnds
        .mapNotNull { end -> text.lastIndexOf(end).takeIf { it >= minLength } }
        .maxOrNull()
        ?.plus(1) ?: -1  // +1 to include the punctuation
}
```

**Provider creation** (private helper):
```kotlin
private fun createCloudProvider(): TTSProvider? {
    return when (settings.ttsProvider) {
        SettingsRepository.TTS_PROVIDER_OPENAI -> {
            val key = settings.openaiTtsApiKey
            if (key.isBlank()) { Log.w(TAG, "OpenAI API key not configured"); return null }
            OpenAITTSProvider(apiKey = key, voice = settings.openaiTtsVoice, model = settings.openaiTtsModel)
        }
        SettingsRepository.TTS_PROVIDER_ELEVENLABS -> {
            val key = settings.elevenlabsTtsApiKey
            if (key.isBlank()) { Log.w(TAG, "ElevenLabs API key not configured"); return null }
            ElevenLabsTTSProvider(apiKey = key, voiceId = settings.elevenlabsTtsVoice)
        }
        else -> null
    }
}
```

**stop() and shutdown():**
- `stop()`: `localTTS.stop()` + `audioPlayer.stop()`
- `shutdown()`: `localTTS.shutdown()` + `audioPlayer.stop()`

---

## 5. Modified Files

### 5.1 `data/SettingsRepository.kt`

**Add to companion object:**
```kotlin
// TTS Provider
private const val KEY_TTS_PROVIDER = "tts_provider"
private const val KEY_OPENAI_TTS_API_KEY = "openai_tts_api_key"
private const val KEY_ELEVENLABS_TTS_API_KEY = "elevenlabs_tts_api_key"
private const val KEY_OPENAI_TTS_VOICE = "openai_tts_voice"
private const val KEY_ELEVENLABS_TTS_VOICE = "elevenlabs_tts_voice"
private const val KEY_OPENAI_TTS_MODEL = "openai_tts_model"

const val TTS_PROVIDER_LOCAL = "local"
const val TTS_PROVIDER_OPENAI = "openai"
const val TTS_PROVIDER_ELEVENLABS = "elevenlabs"
```

**Add properties (after existing `ttsEngine` property, ~line 111):**
```kotlin
var ttsProvider: String
    get() = prefs.getString(KEY_TTS_PROVIDER, TTS_PROVIDER_LOCAL) ?: TTS_PROVIDER_LOCAL
    set(value) = prefs.edit().putString(KEY_TTS_PROVIDER, value).apply()

var openaiTtsApiKey: String
    get() = prefs.getString(KEY_OPENAI_TTS_API_KEY, "") ?: ""
    set(value) = prefs.edit().putString(KEY_OPENAI_TTS_API_KEY, value).apply()

var elevenlabsTtsApiKey: String
    get() = prefs.getString(KEY_ELEVENLABS_TTS_API_KEY, "") ?: ""
    set(value) = prefs.edit().putString(KEY_ELEVENLABS_TTS_API_KEY, value).apply()

var openaiTtsVoice: String
    get() = prefs.getString(KEY_OPENAI_TTS_VOICE, "nova") ?: "nova"
    set(value) = prefs.edit().putString(KEY_OPENAI_TTS_VOICE, value).apply()

var elevenlabsTtsVoice: String
    get() = prefs.getString(KEY_ELEVENLABS_TTS_VOICE, "21m00Tcm4TlvDq8ikWAM") ?: "21m00Tcm4TlvDq8ikWAM"
    set(value) = prefs.edit().putString(KEY_ELEVENLABS_TTS_VOICE, value).apply()

var openaiTtsModel: String
    get() = prefs.getString(KEY_OPENAI_TTS_MODEL, "tts-1") ?: "tts-1"
    set(value) = prefs.edit().putString(KEY_OPENAI_TTS_MODEL, value).apply()
```

**Why in SettingsRepository and not a separate class:** All settings already live here with the same `EncryptedSharedPreferences` instance. Creating a second encrypted prefs file would be unnecessary complexity and could cause issues with the MasterKey.

### 5.2 `speech/AudioPlayer.kt`

**Add method after `playFromUrl()` (~line 46):**
```kotlin
/**
 * Play audio from a byte array (for client-side cloud TTS).
 * Writes bytes to a temp file then plays via MediaPlayer.
 */
suspend fun playFromBytes(audioData: ByteArray, format: String = "mp3"): Boolean =
    withContext(Dispatchers.IO) {
        try {
            val tempFile = File(context.cacheDir, "cloud_tts_${System.currentTimeMillis()}.$format")
            tempFile.writeBytes(audioData)
            playFile(tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio from bytes", e)
            false
        }
    }
```

**Why this works:** `playFile()` is `private suspend fun` in the same class. `playFromBytes()` writes to a temp file (same pattern as `playFromUrl()`) and delegates to it. `playFile()` already handles MediaPlayer lifecycle, completion callbacks, and temp file cleanup.

### 5.3 `ChatActivity.kt`

**Remove all TTS lifecycle code.** The `TTSOrchestrator` (created inside `ChatViewModel`) now owns TTS completely.

**Remove:**
- `TextToSpeech.OnInitListener` from class declaration (line 58)
- `private var tts: TextToSpeech?` and `private var isRetry` (lines 61-62)
- `initializeTTS()` method (lines 113-128)
- `onInit()` method (lines 130-145)
- TTS cleanup in `onDestroy()` — replace with just `super.onDestroy()` (lines 147-152)
- `import android.speech.tts.TextToSpeech` (line 6)
- `import com.openclaw.assistant.speech.TTSUtils` (line 45)

**Result:** `ChatActivity` becomes a simple host: permissions + Compose UI + ViewModel lifecycle. No TTS awareness.

### 5.4 `ui/chat/ChatViewModel.kt`

**Remove:**
- `import android.speech.tts.TextToSpeech` (line 5)
- `import android.speech.tts.UtteranceProgressListener` (line 6)
- `private var tts: TextToSpeech?` and `private var isTTSReady` (lines 157-158)
- `fun setTTS(textToSpeech: TextToSpeech)` (lines 163-167)
- `private suspend fun speakWithTTS(text: String): Boolean` (lines 366-407)

**Add:**
```kotlin
import com.openclaw.assistant.speech.tts.TTSOrchestrator
```
```kotlin
private val ttsOrchestrator = TTSOrchestrator(application)
```

**Modify `speak()` (line 309):**
```kotlin
private fun speak(text: String) {
    viewModelScope.launch {
        _uiState.update { it.copy(isSpeaking = true) }
        val success = ttsOrchestrator.speak(text)
        _uiState.update { it.copy(isSpeaking = false) }

        if (success && lastInputWasVoice && settings.continuousMode) {
            speechManager.destroy()
            delay(1000)
            startListening()
        } else {
            sendResumeBroadcast()
        }
    }
}
```

**Modify `startListening()` (line 237) — replace `tts?.stop()`:**
```kotlin
ttsOrchestrator.stop()
```

**Modify `stopSpeaking()` (line 409):**
```kotlin
fun stopSpeaking() {
    lastInputWasVoice = false
    ttsOrchestrator.stop()
    audioPlayer.stop()
    _uiState.update { it.copy(isSpeaking = false) }
    sendResumeBroadcast()
}
```

**Modify `onCleared()` (line 419):**
```kotlin
override fun onCleared() {
    super.onCleared()
    speechManager.destroy()
    ttsOrchestrator.shutdown()
    audioPlayer.stop()
    sendResumeBroadcast()
}
```

**`playServerAudio()` (line 340) stays unchanged** — server audio URL still takes priority. Its fallback `speak(fallbackText)` now routes through the orchestrator.

### 5.5 `service/OpenClawSession.kt`

**Replace import (line 43):**
```kotlin
// Remove: import com.openclaw.assistant.speech.TTSManager
// Add:
import com.openclaw.assistant.speech.tts.TTSOrchestrator
```

**Replace field (line 70):**
```kotlin
// Remove: private lateinit var ttsManager: TTSManager
// Add:
private lateinit var ttsOrchestrator: TTSOrchestrator
```

**In `onCreate()` (line 111):**
```kotlin
// Remove: ttsManager = TTSManager(context)
// Add:
ttsOrchestrator = TTSOrchestrator(context)
```

**Modify `speakResponse()` (line 388):**
```kotlin
private fun speakResponse(text: String) {
    currentState.value = AssistantState.SPEAKING
    scope.launch {
        val success = ttsOrchestrator.speak(text)
        abandonAudioFocus()
        if (success) {
            if (settings.continuousMode) {
                delay(500)
                startListening()
            }
        } else {
            currentState.value = AssistantState.ERROR
            errorMessage.value = context.getString(R.string.error_speech_general)
        }
    }
}
```

**In `onHide()` (line 206):**
```kotlin
// Replace: ttsManager.stop()
ttsOrchestrator.stop()
```

**In `onDestroy()` (line 220):**
```kotlin
// Replace: ttsManager.shutdown()
ttsOrchestrator.shutdown()
```

### 5.6 `SettingsActivity.kt`

**Add new state variables (after line 82):**
```kotlin
var ttsProvider by remember { mutableStateOf(settings.ttsProvider) }
var openaiApiKey by remember { mutableStateOf(settings.openaiTtsApiKey) }
var elevenlabsApiKey by remember { mutableStateOf(settings.elevenlabsTtsApiKey) }
var openaiVoice by remember { mutableStateOf(settings.openaiTtsVoice) }
var elevenlabsVoice by remember { mutableStateOf(settings.elevenlabsTtsVoice) }
var openaiModel by remember { mutableStateOf(settings.openaiTtsModel) }
var showProviderMenu by remember { mutableStateOf(false) }
var showVoiceMenu by remember { mutableStateOf(false) }
var showModelMenu by remember { mutableStateOf(false) }
var showOpenaiKey by remember { mutableStateOf(false) }
var showElevenlabsKey by remember { mutableStateOf(false) }
var isTestingVoice by remember { mutableStateOf(false) }
var voiceTestResult by remember { mutableStateOf<TestResult?>(null) }
```

**Update save action (line 109-118) — add after existing saves:**
```kotlin
settings.ttsProvider = ttsProvider
settings.openaiTtsApiKey = openaiApiKey
settings.elevenlabsTtsApiKey = elevenlabsApiKey
settings.openaiTtsVoice = openaiVoice
settings.elevenlabsTtsVoice = elevenlabsVoice
settings.openaiTtsModel = openaiModel
```

**Replace the Voice section content (lines 282-376).**

When `ttsEnabled` is true, show:

```
TTS Provider dropdown: [Local (Device) | OpenAI | ElevenLabs]
│
├─ If "local":
│    Local TTS Engine dropdown (existing, unchanged)
│    Voice Speed slider (existing, unchanged, Google TTS only)
│
├─ If "openai":
│    API Key (OutlinedTextField, PasswordVisualTransformation, visibility toggle)
│    Model dropdown: [Standard (tts-1) | HD (tts-1-hd)]
│    Voice dropdown: [alloy | ash | ballad | coral | echo | fable | onyx | nova | sage | shimmer]
│    [Test Voice] button
│
└─ If "elevenlabs":
     API Key (OutlinedTextField, PasswordVisualTransformation, visibility toggle)
     Voice dropdown: [Rachel | Bella | Elli | Josh | Arnold]
     [Test Voice] button
```

**UI patterns** (match existing SettingsActivity exactly):
- Provider dropdown: `ExposedDropdownMenuBox` + `OutlinedTextField(readOnly=true)` + `ExposedDropdownMenu` — same pattern as TTS Engine and Wake Word dropdowns
- API key field: `OutlinedTextField` with `PasswordVisualTransformation` + visibility toggle `IconButton` — same pattern as Auth Token field (line 169-188)
- Test Voice button: `Button` with `CircularProgressIndicator` while testing — same pattern as Test Connection button (line 193-249)

**Test Voice button action:**
```kotlin
scope.launch {
    isTestingVoice = true
    voiceTestResult = null
    try {
        val orchestrator = TTSOrchestrator(context)
        val success = orchestrator.speak(context.getString(R.string.tts_test_sample))
        orchestrator.shutdown()
        voiceTestResult = TestResult(
            success = success,
            message = if (success) context.getString(R.string.tts_test_success)
                      else context.getString(R.string.tts_test_failed_generic)
        )
    } catch (e: Exception) {
        voiceTestResult = TestResult(success = false, message = e.message ?: "Unknown error")
    } finally {
        isTestingVoice = false
    }
}
```

**Important:** The Test Voice button must save the relevant settings (provider, API key, voice, model) to `SettingsRepository` before creating the orchestrator, because the orchestrator reads from settings. Add a temporary save before testing:
```kotlin
settings.ttsProvider = ttsProvider
settings.openaiTtsApiKey = openaiApiKey
settings.openaiTtsVoice = openaiVoice
settings.openaiTtsModel = openaiModel
settings.elevenlabsTtsApiKey = elevenlabsApiKey
settings.elevenlabsTtsVoice = elevenlabsVoice
```

### 5.7 `res/values/strings.xml`

**Add after line 108 (before wake word presets):**
```xml
<!-- Cloud TTS Settings -->
<string name="tts_provider_label">TTS Provider</string>
<string name="tts_provider_local">Local (Device)</string>
<string name="tts_provider_openai">OpenAI</string>
<string name="tts_provider_elevenlabs">ElevenLabs</string>
<string name="tts_api_key_label">API Key</string>
<string name="tts_voice_label">Voice</string>
<string name="tts_model_label">Model</string>
<string name="tts_model_standard">Standard (tts-1)</string>
<string name="tts_model_hd">HD (tts-1-hd)</string>
<string name="tts_test_voice">Test Voice</string>
<string name="tts_testing_voice">Testing…</string>
<string name="tts_test_sample">Hello, this is a test of your selected voice.</string>
<string name="tts_test_success">Voice test successful!</string>
<string name="tts_test_failed_generic">Voice test failed</string>
```

---

## 6. Files NOT Modified

| File | Why unchanged |
|---|---|
| `speech/TTSManager.kt` | Wrapped by `TTSOrchestrator` for local TTS — no changes needed |
| `speech/TTSUtils.kt` | Used internally by `TTSManager` — unchanged |
| `speech/TTSEngineUtils.kt` | Used by `SettingsActivity` for local engine list — unchanged |
| `api/OpenClawClient.kt` | Returns text + optional `audio_url` — no TTS responsibility |
| `app/build.gradle.kts` | OkHttp 4.12.0 and Gson 2.10.1 already present. No new deps. |
| `speech/SpeechRecognizerManager.kt` | STT only — unrelated to TTS |

---

## 7. TTS Resolution Order

When a response arrives in `ChatViewModel.sendMessage()` or `OpenClawSession.sendToOpenClaw()`:

```
1. ttsEnabled == false → no speech, done
2. response.hasServerAudio() == true
   → AudioPlayer.playFromUrl(audioUrl)
   → success → done
   → failure → fall through to step 3
3. settings.ttsProvider == "openai" AND openaiTtsApiKey is not blank
   → OpenAITTSProvider.synthesize(text) → AudioPlayer.playFromBytes(bytes)
   → success → done
   → failure → fall through to step 5
4. settings.ttsProvider == "elevenlabs" AND elevenlabsTtsApiKey is not blank
   → ElevenLabsTTSProvider.synthesize(text) → AudioPlayer.playFromBytes(bytes)
   → success → done
   → failure → fall through to step 5
5. Local fallback (always available)
   → TTSManager.speak(text)
```

Steps 3-5 are handled inside `TTSOrchestrator.speak()`. The caller (`ChatViewModel`/`OpenClawSession`) just calls `ttsOrchestrator.speak(text)` and gets a boolean back.

---

## 8. Implementation Order

Execute in this exact sequence. Each step should compile before moving to the next.

1. **`SettingsRepository.kt`** — Add constants + properties. Zero risk, everything else depends on this.
2. **`speech/tts/TTSProvider.kt`** — Define interface + `VoiceOption` data class.
3. **`speech/tts/OpenAITTSProvider.kt`** — Implement. Can be tested in isolation.
4. **`speech/tts/ElevenLabsTTSProvider.kt`** — Implement. Can be tested in isolation.
5. **`speech/AudioPlayer.kt`** — Add `playFromBytes()`. One method, no breaking changes.
6. **`speech/tts/TTSOrchestrator.kt`** — Wire up providers + fallback chain + streaming session.
7. **`ChatViewModel.kt` + `ChatActivity.kt`** — Replace raw TTS with orchestrator. These two change together because removing TTS from Activity requires ViewModel to own it.
8. **`service/OpenClawSession.kt`** — Replace `TTSManager` with orchestrator.
9. **`res/values/strings.xml`** — Add string resources for settings UI.
10. **`SettingsActivity.kt`** — Build the provider selection UI. This is last because it depends on everything above being functional.

---

## 9. Verification

After implementation, verify:

- [ ] `./gradlew assembleDebug` compiles without errors
- [ ] App launches without crashes
- [ ] Settings screen shows TTS Provider dropdown under Voice section
- [ ] Selecting "Local" shows existing engine dropdown + speed slider (unchanged behavior)
- [ ] Selecting "OpenAI" shows API key field, model dropdown, voice dropdown, test button
- [ ] Selecting "ElevenLabs" shows API key field, voice dropdown, test button
- [ ] API key fields use password masking with visibility toggle
- [ ] Save button persists all new settings
- [ ] With provider "local": chat response triggers Android TTS (same as before)
- [ ] With provider "openai" + valid key: chat response triggers OpenAI synthesis + playback
- [ ] With provider "openai" + blank key: gracefully falls back to local TTS
- [ ] With provider "openai" + invalid key: logs error, falls back to local TTS
- [ ] Test Voice button plays sample audio with current provider settings
- [ ] Server `audio_url` still takes priority when present (regardless of provider setting)
- [ ] Continuous conversation mode works after TTS completes (all providers)
- [ ] `stopSpeaking()` stops both cloud audio playback and local TTS
- [ ] No API keys appear in logcat output
- [ ] Voice Interaction Session (`OpenClawSession`) uses cloud TTS when configured
