package com.example.offlinetranslator.core

enum class SpeechModelRole { VAD, ASR, TTS }

data class TextRuntimeConfig(val pair: LanguagePair, val engineId: String)

object RecommendedConversationVoices {
    fun modelId(language: LanguageId): String? = when (language) {
        LanguageCatalog.RUSSIAN -> "tts-ru-piper-irina-medium"
        LanguageCatalog.ENGLISH -> "tts-en-piper-lessac-medium"
        else -> null
    }
}


class ConversationSessionConfig(
    languages: List<LanguageId>,
    val engineId: String,
    val speechRecognitionBackend: SpeechRecognitionBackend,
    val vadModelId: String,
    val vadMinSilenceMs: Int,
    val ttsSpeedPercent: Int,
    val speechOutputBackend: SpeechOutputBackend,
    val speechRecognitionService: String? = null,
) {
    val languages: List<LanguageId> = languages.toList()
    val ttsModelIds: Map<LanguageId, String> = if (speechOutputBackend == SpeechOutputBackend.PIPER) {
        this.languages.associateWith { language ->
            RecommendedConversationVoices.modelId(language)
                ?: throw InvalidConfigurationException(ConfigurationProblem.UNSUPPORTED_VOICE)
        }
    } else emptyMap()
    init {
        require(this.languages.size == 2 && this.languages.distinct().size == 2)
        require(vadMinSilenceMs in 300..1500 && ttsSpeedPercent in 75..125)
    }
    val pairs: Set<LanguagePair> = setOf(LanguagePair(this.languages[0], this.languages[1]), LanguagePair(this.languages[1], this.languages[0]))
    fun pairFor(source: LanguageId): LanguagePair = pairs.singleOrNull { it.source == source }
        ?: throw UnsupportedSpeechLanguageException()
}
