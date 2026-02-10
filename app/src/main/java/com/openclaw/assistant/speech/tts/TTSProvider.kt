package com.openclaw.assistant.speech.tts

data class VoiceOption(
    val id: String,
    val displayName: String
)

interface TTSProvider {
    val providerName: String
    val requiresApiKey: Boolean
    fun getAvailableVoices(): List<VoiceOption>
    suspend fun synthesize(text: String): ByteArray
    suspend fun validateConfiguration(): Result<Unit>
}
