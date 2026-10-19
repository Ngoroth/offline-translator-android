package com.example.offlinetranslator.core

import kotlinx.coroutines.flow.StateFlow

/** Preparation is explicit; inference must never download a missing resource. */
interface ConversationModelRepository : AutoCloseable {
    val models: StateFlow<List<SpeechModelStatus>>
    suspend fun install()
    suspend fun isConversationReady(): Boolean
    override fun close()
}

data class RecognizedUtterance(val direction: LanguagePair, val text: String)

class UnsupportedSpeechLanguageException : RuntimeException("Unsupported speech language")

/** Selected on-device recognition resources; checking never starts a download. */
enum class SpeechRecognitionReadiness {
    READY, MISSING_LOCAL_MODEL, UNSUPPORTED, ENGINE_UNAVAILABLE,
    SCHEDULED, DOWNLOADING, DOWNLOAD_FAILED, CHECK_FAILED,
}

/** Identifies each utterance independently, then transcribes its actual source language. */
interface ConversationRecognitionEngine : AutoCloseable {
    val readinessState: StateFlow<SpeechRecognitionReadiness>
    suspend fun readiness(): SpeechRecognitionReadiness
    suspend fun install()
    suspend fun prepare()
    suspend fun recognize(segment: SpeechSegment): RecognizedUtterance
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
    override fun close()
    suspend fun closeAndJoin() { close() }
}
