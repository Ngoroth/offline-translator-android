package io.github.ngoroth.offlinetranslator.core

interface SpeechRecognitionEngine : AutoCloseable {
    /** Performs local recognition only. Implementations must not fetch models here. */
    suspend fun recognize(pair: LanguagePair, segment: SpeechSegment): String

    override fun close()
    suspend fun closeAndJoin() { close() }
}
