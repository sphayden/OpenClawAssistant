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

    /**
     * Play audio from raw bytes (for cloud TTS)
     * @return true if playback completed successfully
     */
    suspend fun playFromBytes(audioData: ByteArray, format: String = "mp3"): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Playing audio from bytes (${audioData.size} bytes, format=$format)")
            val writeStart = System.currentTimeMillis()
            val tempFile = File(context.cacheDir, "cloud_tts_${System.currentTimeMillis()}.$format")
            tempFile.writeBytes(audioData)
            Log.d(TAG, "[PERF] File write: ${System.currentTimeMillis() - writeStart}ms")
            playFile(tempFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play audio from bytes", e)
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
