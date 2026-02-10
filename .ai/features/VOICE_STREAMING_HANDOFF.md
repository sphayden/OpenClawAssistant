# Voice Assistant Streaming (SSE) — App Changes

## What Changed (Backend ✅ DONE)

New endpoint added to the voice adapter:

**`POST /voice/stream`** — Returns Server-Sent Events (SSE)

The old `POST /voice` endpoint still works unchanged (backward compatible).

## SSE Event Format

The new endpoint streams three event types:

### 1. `text` — Text chunks as they arrive from the model

```
event: text
data: {"chunk":"Oh, would you look at that..."}

event: text
data: {"chunk":" The weather today is"}

event: text
data: {"chunk":" absolutely magnificent."}
```

- Arrives in real-time as the model generates text
- Display these immediately in the UI (typing effect)
- `chunk` contains the incremental text (not cumulative)

### 2. `audio` — TTS audio URLs as chunks are generated

```
event: audio
data: {"url":"http://192.168.1.250:8765/audio/abc123.mp3","index":0}

event: audio
data: {"url":"http://192.168.1.250:8765/audio/def456.mp3","index":1}
```

- Arrives as TTS generation completes for each text chunk (~300 chars)
- `index` indicates playback order (audio may arrive out of order due to parallel generation)
- Queue these and play in order by `index`
- Start playing index 0 immediately when it arrives (don't wait for all chunks)

### 3. `done` — Stream complete

```
event: done
data: {"model":"openclaw:voice","fullText":"Oh, would you look at that... The weather today is absolutely magnificent.","audioChunks":2}
```

- Signals the stream is complete
- `fullText` contains the complete response text
- `audioChunks` is the total number of audio chunks to expect
- Use this to verify all audio chunks have been received

## Kotlin Implementation

### 1. Add SSE Client Dependency

Add to `build.gradle`:
```kotlin
implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
```

Or use OkHttp's built-in EventSource support.

### 2. Create SSE Request

```kotlin
import okhttp3.*
import okhttp3.sse.*

val client = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .build()

val requestBody = """{"message":"$userMessage","session_id":"$sessionId"}"""
    .toRequestBody("application/json".toMediaType())

val request = Request.Builder()
    .url("http://192.168.1.250:8765/voice/stream")
    .post(requestBody)
    .build()
```

### 3. Handle SSE Events

```kotlin
val eventSourceFactory = EventSources.createFactory(client)

val listener = object : EventSourceListener() {
    private val audioQueue = PriorityQueue<Pair<Int, String>>(compareBy { it.first })
    private var nextPlayIndex = 0
    private val fullText = StringBuilder()

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        when (type) {
            "text" -> {
                // Parse text chunk and display immediately
                val json = JSONObject(data)
                val chunk = json.getString("chunk")
                fullText.append(chunk)
                
                runOnUiThread {
                    // Append chunk to text display (typing effect)
                    responseTextView.append(chunk)
                }
            }
            
            "audio" -> {
                // Queue audio chunk for ordered playback
                val json = JSONObject(data)
                val url = json.getString("url")
                val index = json.getInt("index")
                
                audioQueue.add(Pair(index, url))
                
                // Play next audio if it's ready
                playNextAudio()
            }
            
            "done" -> {
                // Stream complete
                val json = JSONObject(data)
                val model = json.optString("model", "unknown")
                
                runOnUiThread {
                    // Update model display
                    modelTextView.text = model
                }
            }
        }
    }

    private fun playNextAudio() {
        while (audioQueue.isNotEmpty() && audioQueue.peek()!!.first == nextPlayIndex) {
            val (_, url) = audioQueue.poll()!!
            // Queue audio for playback
            audioPlayer.queueAudio(url)
            nextPlayIndex++
        }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        Log.e("VoiceStream", "SSE error: ${t?.message}")
        runOnUiThread {
            // Show error state
        }
    }
}

// Start the SSE connection
eventSourceFactory.newEventSource(request, listener)
```

### 4. Audio Player (Sequential Playback)

```kotlin
class AudioQueuePlayer {
    private val queue = ConcurrentLinkedQueue<String>()
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false

    fun queueAudio(url: String) {
        queue.add(url)
        if (!isPlaying) {
            playNext()
        }
    }

    private fun playNext() {
        val url = queue.poll() ?: run {
            isPlaying = false
            return
        }

        isPlaying = true
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            prepareAsync()
            setOnPreparedListener { start() }
            setOnCompletionListener {
                it.release()
                playNext()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                playNext()
                true
            }
        }
    }
}
```

### 5. Update the UI to use streaming

Replace the current synchronous voice request with the SSE version:

```kotlin
// Old (synchronous):
// val response = apiClient.sendVoice(message)
// displayResponse(response.text)
// playAudio(response.audioUrl)

// New (streaming):
// 1. Clear previous response
responseTextView.text = ""

// 2. Start SSE connection (text appears as it arrives)
startStreamingRequest(message, sessionId)

// 3. Audio plays automatically as chunks arrive
```

## Testing

1. Build and deploy the updated app
2. Send a message via voice
3. You should see:
   - Text appearing word-by-word (not all at once)
   - Audio starting to play before all text has arrived
   - Seamless audio playback across chunks
4. The old `/voice` endpoint still works as fallback

## Architecture

```
User speaks → App sends POST /voice/stream
                          ↓
              Adapter forwards to Gateway (streaming)
                          ↓
              Gateway streams text chunks (SSE)
                          ↓
              Adapter receives chunks:
                → Forwards text chunks to App immediately (SSE)
                → Buffers ~300 chars, sends to OpenAI TTS
                → Forwards audio URLs to App as ready (SSE)
                          ↓
              App displays text + plays audio in real-time
```

## Notes

- Old `/voice` endpoint is unchanged — backward compatible
- Audio chunks may arrive out of order (parallel TTS generation)
- Use the `index` field to play audio in correct order
- The `done` event contains the full text as a safety net
- Chunk size is ~300 characters (configurable server-side)
