# OpenClaw Assistant App - TTS Architecture Redesign

## Overview
Move Text-to-Speech (TTS) computation from adapter (server-side) to app (client-side). This eliminates unnecessary network round-trips and latency, letting the app handle voice synthesis with configurable providers.

## Current State (Problematic)
```
User speaks → App (STT) → Adapter → LLM response (text) → Adapter (TTS) → App (playback)
                                    ↑                         ↑
                            Network hop                Network hop + computation
```

**Problems:**
- Extra network latency (adapter TTS call)
- Audio streaming from adapter adds complexity
- Single TTS provider hardcoded in adapter
- Can't easily swap providers without server restart

## Desired State
```
User speaks → App (STT) → Adapter → LLM response (text) → App (TTS) → Playback
                       ↑                        ↑
          Local processing              Direct API call (OpenAI, ElevenLabs, etc.)
```

**Benefits:**
- Minimal latency: text response → immediate TTS
- App fully controls TTS provider (OpenAI, ElevenLabs, Google, local, etc.)
- Streaming text support (`/voice/stream` endpoint)
- Flexible voice selection per session
- No adapter restart needed for provider changes

## Architecture Changes

### 1. Adapter Role (Simplified)
**Current:** Text → LLM → TTS → Audio → App  
**New:** Text → LLM → Text → App  

**What adapter does:**
- Accept `{"message": "...", "session_id": "..."}` from app
- Forward to OpenClaw Gateway/LLM
- Stream or return text response
- Return `{"response": "...", "model": "..."}` (just text)

**What adapter does NOT do:**
- Generate audio/TTS
- Encode/decode voice
- Manage TTS API keys

### 2. App-Side TTS Pipeline

#### Settings Storage
Store in `EncryptedSharedPreferences`:
```kotlin
// TTS Provider config
tts_provider: String         // "openai" | "elevenlabs" | "google" | "local"
tts_api_key: String         // API key (encrypted)
tts_voice: String           // Provider-specific voice ID
tts_model: String           // Provider-specific model (e.g., "tts-1-hd")
tts_speech_rate: Float      // 0.5-2.0 (speed)
tts_pitch: Float            // 0.5-2.0 (pitch)

// Endpoint config
llm_endpoint_type: String   // "text" | "stream"
llm_streaming_enabled: Boolean
```

#### TTS Interface (Abstract)
```kotlin
interface TTSProvider {
    suspend fun synthesize(text: String): ByteArray  // Returns audio MP3
    suspend fun supportsStreaming(): Boolean
    fun getAvailableVoices(): List<String>
}
```

Implementations:
- `OpenAITTSProvider` - via OpenAI API
- `ElevenLabsTTSProvider` - via ElevenLabs API
- `GoogleTTSProvider` - via Google Cloud TTS
- `LocalTTSProvider` - Android TextToSpeech (fallback)

#### LLM Response Handling

**Non-streaming (`/voice`):**
```
1. App sends text to `/voice`
2. Wait for full response
3. Get text → Pass to TTS provider
4. Synthesize audio
5. Play
```

**Streaming (`/voice/stream`):**
```
1. App opens SSE connection to `/voice/stream`
2. Receive text chunks as they arrive
3. Buffer chunks (e.g., 300 chars)
4. When buffer full → Pass to TTS provider
5. Synthesize audio chunk
6. Queue for playback
7. Continue receiving next chunks
8. Play audio queue as chunks complete
```

### 3. Settings UI Updates

Add to settings screen:

**LLM Endpoint Section:**
- ☑️ "Stream responses" (toggle → use `/voice/stream`)

**TTS Provider Section:**
- Dropdown: `[OpenAI ▼] [ElevenLabs] [Google] [Local]`
- API Key field (shown/hidden based on provider)
- Voice selector (dropdown, populated from provider)
- Model selector (if applicable)
- Sliders: Speech rate, pitch

**Test Button:**
- Synthesize sample text with current settings
- Play result to user

## Implementation Steps (For Opus)

See: `OPENCLAW_ASSISTANT_TTS_IMPLEMENTATION.md`

## Files That Need Changes

**Kotlin Source:**
- `MainActivity.kt` - Settings UI integration
- `AudioManager.kt` or new `TTSManager.kt` - TTS orchestration
- `VoiceManager.kt` - LLM API calls
- `Settings.kt` - Add TTS config (new file or expand existing)
- `SettingsScreen.kt` - Jetpack Compose settings UI

**Dependencies:**
- `okhttp3` (already present) - HTTP calls
- `androidx.security:security-crypto` (already present) - Encrypted prefs
- Android `TextToSpeech` (built-in) - Local TTS fallback

**Adapter (Go):**
- `adapter-v2.go` - Already handles text-only mode with `TTS_ENABLED=false`
- No changes needed (we already disabled TTS)

## Security Considerations

- **API Keys:** Store encrypted in `EncryptedSharedPreferences`, never log
- **Rate Limiting:** Implement client-side request debouncing (don't spam TTS on every keystroke)
- **Cost Control:** Add usage tracking/warnings for OpenAI/ElevenLabs (optional)

## Testing Checklist

- [ ] App can send request to `/voice` endpoint
- [ ] App receives text response
- [ ] OpenAI TTS synthesizes audio
- [ ] Audio plays with correct voice/settings
- [ ] ElevenLabs TTS works (if configured)
- [ ] Local TextToSpeech fallback works
- [ ] Streaming `/voice/stream` endpoint receives chunks
- [ ] App buffers and synthesizes incrementally
- [ ] Voice/speech rate/pitch changes apply
- [ ] API key validation & error handling
- [ ] No API keys logged or exposed

## Dependencies/Versions

```kotlin
// Already in project (check build.gradle.kts)
implementation("com.squareup.okhttp3:okhttp:4.x.x")
implementation("androidx.security:security-crypto:1.1.0-alpha06")
implementation("com.google.code.gson:gson:2.x.x")

// May need to add for advanced TTS features
implementation("com.google.cloud:google-cloud-texttospeech:2.x.x") // Optional: Google TTS
```

## Notes for Opus

- This is a **clean separation of concerns**: adapter = LLM gateway, app = TTS client
- The app already has STT working (Android SpeechRecognizer), so adding app-side TTS completes the local-processing philosophy
- Current adapter is correctly disabled for TTS (`TTS_ENABLED=false`), so no adapter code changes needed
- Prioritize OpenAI TTS first (most common), then ElevenLabs if time allows
- Local fallback is important for users without API keys
