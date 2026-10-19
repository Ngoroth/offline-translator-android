package com.example.offlinetranslator.core

/**
 * A process-scoped translation engine. Implementations must never download a model from [translate].
 * Callers must pass non-blank text of at most [MAX_INPUT_CHARACTERS] UTF-16 code units
 * and call [close] when the process owner is disposed.
 */
interface TranslationEngine : AutoCloseable {
    companion object {
        const val MAX_INPUT_CHARACTERS = 4096
    }

    suspend fun modelAvailability(): ModelAvailability

    /** Prepares local inference, downloading missing models only under the Wi-Fi-only provider policy. */
    suspend fun prepare(): ModelAvailability

    suspend fun translate(pair: LanguagePair, text: String): String

    override fun close()

    /** Completes only after all actual provider operations and owned resources have finished. */
    suspend fun closeAndJoin() { close() }
}
