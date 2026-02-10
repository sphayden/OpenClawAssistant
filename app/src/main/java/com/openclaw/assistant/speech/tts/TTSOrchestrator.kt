package com.openclaw.assistant.speech.tts

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.AudioPlayer
import com.openclaw.assistant.speech.TTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TTSOrchestrator"

class TTSOrchestrator(context: Context) {

    private val settings = SettingsRepository.getInstance(context)
    private val audioPlayer = AudioPlayer(context)
    private val ttsManager = TTSManager(context)

    /**
     * Speak text using the configured provider, with fallback to local TTS.
     * For cloud providers, splits text into sentences and pipelines synthesis
     * with playback so audio starts after the first sentence is ready (~2s)
     * instead of waiting for the entire text to be synthesized.
     * @return true if speech completed successfully
     */
    suspend fun speak(text: String): Boolean {
        val provider = settings.ttsProvider

        if (provider == SettingsRepository.TTS_PROVIDER_LOCAL) {
            return speakLocal(text)
        }

        // Try cloud provider with sentence pipelining
        val totalStart = System.currentTimeMillis()
        return try {
            val ttsProvider = createCloudProvider(provider) ?: return speakLocal(text)
            val sentences = splitIntoSentences(text)
            Log.d(TAG, "Synthesizing with ${ttsProvider.providerName}, text length=${text.length}, sentences=${sentences.size}")

            // Short text — no benefit from pipelining
            if (sentences.size <= 1) {
                val synthStart = System.currentTimeMillis()
                val audioBytes = ttsProvider.synthesize(text)
                Log.d(TAG, "[PERF] Synthesis: ${System.currentTimeMillis() - synthStart}ms (${audioBytes.size} bytes)")
                val result = audioPlayer.playFromBytes(audioBytes)
                Log.d(TAG, "[PERF] Total speak(): ${System.currentTimeMillis() - totalStart}ms")
                return result
            }

            // Pipeline: synthesize next sentence while current one plays
            speakPipelined(ttsProvider, sentences)
                .also { Log.d(TAG, "[PERF] Total pipelined speak(): ${System.currentTimeMillis() - totalStart}ms") }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud TTS failed (${provider}), falling back to local", e)
            speakLocal(text)
        }
    }

    private suspend fun speakPipelined(
        ttsProvider: TTSProvider,
        sentences: List<String>
    ): Boolean = coroutineScope {
        val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
        var allSucceeded = true

        // Consumer: plays audio chunks sequentially
        val playerJob = launch {
            var index = 0
            for (audioData in audioChannel) {
                val playStart = System.currentTimeMillis()
                if (!audioPlayer.playFromBytes(audioData)) {
                    allSucceeded = false
                }
                Log.d(TAG, "[PERF] Played chunk ${index++}: ${System.currentTimeMillis() - playStart}ms")
            }
        }

        // Producer: synthesize sentences on IO, feed into channel
        try {
            for ((index, sentence) in sentences.withIndex()) {
                try {
                    val synthStart = System.currentTimeMillis()
                    val audioBytes = withContext(Dispatchers.IO) {
                        ttsProvider.synthesize(sentence)
                    }
                    Log.d(TAG, "[PERF] Synth chunk $index (${sentence.length} chars): ${System.currentTimeMillis() - synthStart}ms")
                    audioChannel.send(audioBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Chunk $index synthesis failed, skipping", e)
                    allSucceeded = false
                }
            }
        } finally {
            audioChannel.close()
        }

        playerJob.join()
        allSucceeded
    }

    /**
     * Speak from a streaming Flow of text chunks, synthesizing at sentence boundaries
     * for low-latency playback during SSE streaming.
     * @return true if all chunks were spoken successfully
     */
    suspend fun speakStreaming(textFlow: Flow<String>): Boolean {
        val provider = settings.ttsProvider

        if (provider == SettingsRepository.TTS_PROVIDER_LOCAL) {
            return speakStreamingLocal(textFlow)
        }

        return speakStreamingCloud(textFlow, provider)
    }

    private suspend fun speakStreamingCloud(
        textFlow: Flow<String>,
        provider: String
    ): Boolean = coroutineScope {
        val ttsProvider = createCloudProvider(provider)
        if (ttsProvider == null) {
            Log.w(TAG, "Cloud provider unavailable for streaming, falling back to local")
            return@coroutineScope speakStreamingLocal(textFlow)
        }

        val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
        var allSucceeded = true

        val playerJob = launch {
            for (audioData in audioChannel) {
                if (!audioPlayer.playFromBytes(audioData)) {
                    allSucceeded = false
                }
            }
        }

        try {
            val buffer = StringBuilder()

            textFlow.collect { chunk ->
                buffer.append(chunk)

                val boundary = findSentenceBoundary(buffer.toString())
                if (boundary != -1 || buffer.length > 300) {
                    val splitAt = if (boundary != -1) boundary else buffer.length
                    val sentence = buffer.substring(0, splitAt).trim()
                    buffer.delete(0, splitAt)

                    if (sentence.isNotEmpty()) {
                        try {
                            val audioBytes = withContext(Dispatchers.IO) {
                                ttsProvider.synthesize(sentence)
                            }
                            audioChannel.send(audioBytes)
                        } catch (e: Exception) {
                            Log.w(TAG, "Streaming chunk synthesis failed, skipping", e)
                            allSucceeded = false
                        }
                    }
                }
            }

            val remaining = buffer.toString().trim()
            if (remaining.isNotEmpty()) {
                try {
                    val audioBytes = withContext(Dispatchers.IO) {
                        ttsProvider.synthesize(remaining)
                    }
                    audioChannel.send(audioBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "Streaming final chunk synthesis failed", e)
                    allSucceeded = false
                }
            }
        } finally {
            audioChannel.close()
        }

        playerJob.join()
        allSucceeded
    }

    private suspend fun speakStreamingLocal(textFlow: Flow<String>): Boolean {
        val buffer = StringBuilder()
        var lastText = ""

        textFlow.collect { chunk ->
            buffer.append(chunk)

            val boundary = findSentenceBoundary(buffer.toString())
            if (boundary != -1 || buffer.length > 300) {
                val splitAt = if (boundary != -1) boundary else buffer.length
                val sentence = buffer.substring(0, splitAt).trim()
                buffer.delete(0, splitAt)

                if (sentence.isNotEmpty()) {
                    ttsManager.speakQueued(sentence)
                    lastText = sentence
                }
            }
        }

        val remaining = buffer.toString().trim()
        if (remaining.isNotEmpty()) {
            lastText = remaining
            ttsManager.speakQueued(remaining)
        }

        return if (lastText.isNotEmpty()) {
            ttsManager.speak(lastText)
        } else {
            true
        }
    }

    private suspend fun speakLocal(text: String): Boolean {
        return ttsManager.speak(text)
    }

    private fun createCloudProvider(provider: String): TTSProvider? {
        return when (provider) {
            SettingsRepository.TTS_PROVIDER_OPENAI -> {
                val apiKey = settings.openaiTtsApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "OpenAI API key not set, cannot use cloud TTS")
                    return null
                }
                OpenAITTSProvider(
                    apiKey = apiKey,
                    voice = settings.openaiTtsVoice,
                    model = settings.openaiTtsModel
                )
            }
            SettingsRepository.TTS_PROVIDER_ELEVENLABS -> {
                val apiKey = settings.elevenlabsTtsApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "ElevenLabs API key not set, cannot use cloud TTS")
                    return null
                }
                ElevenLabsTTSProvider(
                    apiKey = apiKey,
                    voiceId = settings.elevenlabsTtsVoice
                )
            }
            SettingsRepository.TTS_PROVIDER_FISH_AUDIO -> {
                val apiKey = settings.fishAudioTtsApiKey
                if (apiKey.isBlank()) {
                    Log.w(TAG, "Fish Audio API key not set, cannot use cloud TTS")
                    return null
                }
                FishAudioTTSProvider(
                    apiKey = apiKey,
                    referenceId = settings.fishAudioTtsReferenceId,
                    model = settings.fishAudioTtsModel
                )
            }
            else -> null
        }
    }

    /**
     * Split text into sentences for pipelined synthesis.
     * Splits on sentence-ending punctuation followed by whitespace.
     */
    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()

        var i = 0
        while (i < text.length) {
            current.append(text[i])
            if ((text[i] == '.' || text[i] == '!' || text[i] == '?') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) {
                    sentences.add(sentence)
                }
                current.clear()
            }
            i++
        }

        val remaining = current.toString().trim()
        if (remaining.isNotEmpty()) {
            if (sentences.isNotEmpty() && remaining.length < 20) {
                // Merge short trailing fragment with last sentence
                sentences[sentences.lastIndex] = sentences.last() + " " + remaining
            } else {
                sentences.add(remaining)
            }
        }

        return sentences
    }

    /**
     * Find a sentence boundary at or after minLength chars (for streaming).
     * @return index after the boundary, or -1 if none found
     */
    private fun findSentenceBoundary(text: String, minLength: Int = 100): Int {
        if (text.length < minLength) return -1

        var lastBoundary = -1
        var i = minLength
        while (i < text.length - 1) {
            val ch = text[i]
            val next = text[i + 1]
            if ((ch == '.' || ch == '!' || ch == '?') && (next == ' ' || next == '\n')) {
                lastBoundary = i + 2
            }
            i++
        }
        if (text.length >= minLength) {
            val last = text.last()
            if (last == '.' || last == '!' || last == '?') {
                lastBoundary = text.length
            }
        }
        return lastBoundary
    }

    fun stop() {
        ttsManager.stop()
        audioPlayer.stop()
    }

    fun shutdown() {
        ttsManager.shutdown()
        audioPlayer.stop()
    }
}
