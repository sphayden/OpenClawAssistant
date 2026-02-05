package com.openclaw.assistant.speech.diagnostics

import android.content.Context
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 音声診断結果のデータ構造
 */
data class VoiceDiagnostic(
    val sttStatus: DiagnosticStatus,
    val ttsStatus: DiagnosticStatus,
    val sttEngine: String? = null,
    val ttsEngine: String? = null,
    val missingLanguages: List<String> = emptyList(),
    val suggestions: List<DiagnosticSuggestion> = emptyList()
)

enum class DiagnosticStatus {
    READY,      // 準備完了
    WARNING,    // 注意（設定が必要）
    ERROR       // エラー（動作不能）
}

data class DiagnosticSuggestion(
    val message: String,
    val actionLabel: String? = null,
    val intent: Intent? = null
)

/**
 * 音声機能の診断を行うクラス
 */
class VoiceDiagnostics(private val context: Context) {

    fun performFullCheck(tts: TextToSpeech?): VoiceDiagnostic {
        val sttResult = checkSTT()
        val ttsResult = checkTTS(tts)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        suggestions.addAll(sttResult.suggestions)
        suggestions.addAll(ttsResult.suggestions)

        return VoiceDiagnostic(
            sttStatus = sttResult.status,
            ttsStatus = ttsResult.status,
            sttEngine = sttResult.engine,
            ttsEngine = ttsResult.engine,
            missingLanguages = ttsResult.missingLangs,
            suggestions = suggestions
        )
    }

    private data class ComponentCheckResult(
        val status: DiagnosticStatus,
        val engine: String? = null,
        val suggestions: List<DiagnosticSuggestion> = emptyList(),
        val missingLangs: List<String> = emptyList()
    )

    private fun checkSTT(): ComponentCheckResult {
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(context)
        if (!isAvailable) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                suggestions = listOf(
                    DiagnosticSuggestion(
                        "音声認識サービスが見つかりません。Googleアプリがインストールされているか確認してください。",
                        "Playストアを開く",
                        null
                    )
                )
            )
        }
        return ComponentCheckResult(status = DiagnosticStatus.READY, engine = "System Default")
    }

    private fun checkTTS(tts: TextToSpeech?): ComponentCheckResult {
        if (tts == null) {
            return ComponentCheckResult(
                status = DiagnosticStatus.ERROR,
                suggestions = listOf(DiagnosticSuggestion("TTSエンジンの初期化に失敗しました。"))
            )
        }

        val engine = tts.defaultEngine
        val currentLocale = Locale.getDefault()
        
        // 言語サポートの詳細チェック
        val langResult = tts.isLanguageAvailable(currentLocale)
        val jaResult = tts.isLanguageAvailable(Locale.JAPANESE)
        
        val suggestions = mutableListOf<DiagnosticSuggestion>()
        val missingLangs = mutableListOf<String>()

        var status = DiagnosticStatus.READY

        // 現在のロケールがサポートされているか
        if (langResult < TextToSpeech.LANG_AVAILABLE && jaResult < TextToSpeech.LANG_AVAILABLE) {
            status = DiagnosticStatus.WARNING
            val langName = currentLocale.displayName
            suggestions.add(
                DiagnosticSuggestion(
                    "音声データ ($langName) がこのエンジン ($engine) で見つかりません。",
                    "データの管理",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        // エンジンがGoogleでない場合の推奨
        if (engine != "com.google.android.tts") {
            suggestions.add(
                DiagnosticSuggestion(
                    "現在は $engine が使用されています。Google音声サービスへの切り替えを推奨します。",
                    "エンジン設定",
                    Intent("com.android.settings.TTS_SETTINGS")
                )
            )
        }

        return ComponentCheckResult(
            status = status,
            engine = engine,
            suggestions = suggestions,
            missingLangs = missingLangs
        )
    }
}
