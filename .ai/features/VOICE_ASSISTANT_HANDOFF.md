# Voice Assistant Model Display Fix

## Problem
Echo Show 5 app reports model as "opus" when it should be "haiku". Backend is correctly configured for haiku, but app is not displaying the actual model.

## Backend Changes (✅ DONE)
- Voice adapter (`/root/clawd/projects/voice-assistant/adapter-v2.go`) now includes `"model"` field in every JSON response
- Adapter rebuilt and restarted
- Gateway config confirmed: voice agent uses `anthropic/claude-haiku-4-5`
- Active sessions confirm: both voice sessions routing to `claude-haiku-4-5`

## Frontend Changes Needed (Kotlin/Android)

### 1. Update Response Data Class
Find the data class that deserializes the adapter response (e.g., `VoiceResponse`, `AppResponse`, etc.).

Add the model field:
```kotlin
data class VoiceResponse(
    val response: String,
    val audioUrl: String? = null,
    val error: String? = null,
    val model: String? = null  // ← ADD THIS LINE
)
```

**Where to find it:** Search the codebase for the JSON response mapping (Retrofit/Gson/kotlinx.serialization). Look for the class with `response`, `audio_url`, `error` fields.

### 2. Update Display Logic
Find where the model is currently being shown/set (the part that displays "opus"). 

Update it to:
```kotlin
// Use the model from the response, fallback to a default if missing
val displayModel = response.model ?: "unknown"
// Then display displayModel wherever "opus" is currently shown
```

**Where to find it:** Search for "opus" in the codebase. That will show you exactly where it's being displayed.

### 3. Test
After changes:
- Rebuild app
- Reinstall/reload on Echo Show 5
- Ask "what model are you using?"
- Should now report "claude-haiku-4-5" instead of "opus"

## Notes
- The adapter response now always includes the actual model being used
- No other backend changes needed
- This is purely a frontend parsing + display fix
