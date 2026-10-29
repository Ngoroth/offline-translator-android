package com.example.offlinetranslator.core

import kotlinx.coroutines.flow.StateFlow

/** Preparation is explicit; inference must never download a missing resource. */
interface ConversationModelRepository : AutoCloseable {
    val models: StateFlow<List<SpeechModelStatus>>
    suspend fun install(speechOutputRequired: Boolean)
    suspend fun isConversationReady(speechOutputRequired: Boolean): Boolean
    override fun close()
}

sealed interface ConversationRecognitionResult {
    val text: String
}

data class RecognizedUtterance(
    val direction: LanguagePair,
    override val text: String,
) : ConversationRecognitionResult

data class UndeterminedLanguage(override val text: String) : ConversationRecognitionResult

class UnsupportedSpeechLanguageException : RuntimeException("Unsupported speech language")

/** Selected on-device recognition resources; checking never starts a download. */
enum class SpeechRecognitionReadiness {
    READY, MISSING_LOCAL_MODEL, UNSUPPORTED, ENGINE_UNAVAILABLE, INCOMPATIBLE_SERVICE,
    SCHEDULED, DOWNLOADING, DOWNLOAD_FAILED, CHECK_FAILED,
}

/** Recognizes an utterance and determines its direction, or retains text without guessing one. */
interface ConversationRecognitionEngine : AutoCloseable {
    val readinessState: StateFlow<SpeechRecognitionReadiness>
    suspend fun readiness(): SpeechRecognitionReadiness
    suspend fun install()
    suspend fun prepare()
    suspend fun recognize(segment: SpeechSegment): ConversationRecognitionResult
    override fun close()
    suspend fun closeAndJoin() { close() }
}

/** Null denotes a phase-only notification; a timestamp requires first non-silent presentation evidence. */
data class SpeechPlaybackStart(
    val presentationTimeNanos: Long?,
    val measured: Boolean,
)

enum class SpeechOutputReadiness { READY, MISSING_LOCAL_VOICE, ENGINE_UNAVAILABLE, INITIALIZATION_FAILED }

/** Owns local synthesis, audio focus and playback; interrupt must stop output synchronously. */
interface ConversationSpeechOutput : AutoCloseable {
    suspend fun readiness(): SpeechOutputReadiness
    suspend fun prepare()
    suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit)
    fun interruptCurrent()
    /** Throw the retained actual audio/focus release fault after interrupted work has settled. */
    fun throwIfReleaseFailed()
    override fun close()
    suspend fun closeAndJoin() { close() }
}
