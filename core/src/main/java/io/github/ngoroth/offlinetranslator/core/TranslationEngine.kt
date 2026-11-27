package io.github.ngoroth.offlinetranslator.core

import kotlinx.coroutines.flow.StateFlow

/**
 * A process-scoped translation engine. Implementations must never download a model from [translate].
 * Callers must pass non-blank text of at most [MAX_INPUT_CHARACTERS] UTF-16 code units
 * and call [close] when the process owner is disposed.
 */
interface TranslationEngine : AutoCloseable {
    companion object {
        const val MAX_INPUT_CHARACTERS = 4096
    }

    val preparation: StateFlow<TranslationPreparationState>

    val artifactUpdate: TranslationArtifactUpdate? get() = null

    suspend fun modelAvailability(): ModelAvailability

    /** Initializes only verified installed resources; never downloads, repairs or installs resources.
     * Providers without local initialization keep their observational availability behavior.
     */
    suspend fun prepareLocal(): ModelAvailability = modelAvailability()

    /** Prepares local inference, downloading missing models only under the Wi-Fi-only provider policy. */
    suspend fun prepare(): ModelAvailability

    suspend fun translate(pair: LanguagePair, text: String): String

    override fun close()

    /** Waits for actual SDK/native operations, including work whose coroutine wait was cancelled. */
    suspend fun awaitIdle() {}

    /** Completes only after all actual provider operations and owned resources have finished. */
    suspend fun closeAndJoin() { close() }
}

interface TranslationArtifactUpdate {
    val state: StateFlow<TranslationArtifactUpdateState>

    /** Attempts the offered update. Recoverable failures are reported in [state]. */
    suspend fun update()
}

data class TranslationArtifactUpdateState(
    val activeProvider: TranslationProviderDescriptor? = null,
    val offerAvailable: Boolean = false,
    val preparation: TranslationPreparationState? = null,
)
