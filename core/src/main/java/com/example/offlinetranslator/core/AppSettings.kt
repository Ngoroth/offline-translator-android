package com.example.offlinetranslator.core

enum class SpeechOutputBackend(val persistedId: String) {
    SYSTEM_OFFLINE("system-offline"),
    PIPER("piper"),
}

enum class SpeechRecognitionBackend(val persistedId: String) {
    SYSTEM_OFFLINE("system-offline"),
    SHERPA("sherpa"),
    WHISPER("whisper"),
}

data class AppSettings(
    val languagePair: LanguagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
    val speechOutputBackend: SpeechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
    val speechRecognitionBackend: SpeechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
    val textEngineId: String = "tencent",
    val conversationEngineId: String = "tencent",
    val vadModelId: String = "vad-silero-6.2.2",
    val languageIdModelId: String = "lid-whisper-tiny",
    val vadMinSilenceMs: Int = 700,
    val ttsSpeedPercent: Int = 100,
    val speechRecognitionService: String? = null,
) {
    fun textConfig(): TextRuntimeConfig = TextRuntimeConfig(languagePair, textEngineId)
    fun conversationConfig(): ConversationSessionConfig = ConversationSessionConfig(
        languages = listOf(languagePair.source, languagePair.target),
        engineId = conversationEngineId,
        speechRecognitionBackend = speechRecognitionBackend,
        speechRecognitionService = speechRecognitionService,
        vadModelId = vadModelId,
        languageIdModelId = languageIdModelId,
        vadMinSilenceMs = vadMinSilenceMs,
        ttsSpeedPercent = ttsSpeedPercent,
        speechOutputBackend = speechOutputBackend,
    )
}
