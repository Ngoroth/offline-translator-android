package com.example.offlinetranslator.mlkit

import android.content.Context
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.TranslationCapability
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import com.example.offlinetranslator.core.requireSupported
import com.example.offlinetranslator.core.PreparationFailure
import com.example.offlinetranslator.core.TranslationPreparationState
import com.example.offlinetranslator.core.TranslationPreparationPhase
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class MlKitTranslationEngine internal constructor(
    pairs: Set<LanguagePair>,
    private val coordinator: MlKitPreparationCoordinator,
    clientFactory: (LanguagePair) -> TranslationClient,
) : TranslationEngine {
    val pairs: Set<LanguagePair> = selectedPairs(pairs)
    val requiredLanguages: Set<LanguageId> = buildSet {
        for (pair in this@MlKitTranslationEngine.pairs) {
            if (pair.source != LanguageCatalog.ENGLISH) add(pair.source)
            if (pair.target != LanguageCatalog.ENGLISH) add(pair.target)
        }
    }
    private val lifecycleLock = Any()
    private val inferenceSlot = Semaphore(1)
    private val closed = CompletableDeferred<Unit>()
    private var disposed = false
    private var inFlightTranslations = 0
    private var clientsClosed = false
    private val clients: Map<LanguagePair, TranslationClient> = createClients(clientFactory)
    private val mutablePreparation = MutableStateFlow(TranslationPreparationState())
    override val preparation = mutablePreparation.asStateFlow()
    private val preparationSlot = Mutex()
    private var warmed = false

    constructor(
        @Suppress("UNUSED_PARAMETER") context: Context,
        pairs: Set<LanguagePair>,
        coordinator: MlKitPreparationCoordinator,
    ) : this(pairs, coordinator, ::GoogleTranslationClient)

    override suspend fun modelAvailability(): ModelAvailability {
        ensureOpen()
        return coordinator.modelAvailability(requiredLanguages).also {
            if (!warmed && !preparationSlot.isLocked) {
                mutablePreparation.value = TranslationPreparationState(
                    if (it == ModelAvailability.READY) TranslationPreparationPhase.LOADING else TranslationPreparationPhase.MISSING,
                )
            }
        }
    }

    override suspend fun prepare(): ModelAvailability = preparationSlot.withLock {
        ensureOpen()
        if (warmed) return@withLock ModelAvailability.READY
        mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.CHECKING)
        try {
            val availability = coordinator.prepare(requiredLanguages) {
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING)
            }
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.LOADING)
            // Warm only selected SDK paths; never rewrite user input.
            for (pair in pairs) translate(pair, LanguageCatalog.find(pair.source)?.warmUpText ?: "1")
            warmed = true
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
            availability
        } catch (cancelled: CancellationException) {
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            throw cancelled
        } catch (error: Exception) {
            val failure = error as? PreparationFailure ?: PreparationFailure.ProviderFailure(error)
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            throw failure
        }
    }

    override suspend fun translate(pair: LanguagePair, text: String): String {
        capability(pair).requireSupported()
        require(pair in pairs) { "Translation pair was not selected" }
        require(text.length <= TranslationEngine.MAX_INPUT_CHARACTERS) { "Translation text exceeds the input limit" }
        require(text.isNotBlank()) { "Translation text must not be blank" }
        ensureOpen()
        inferenceSlot.acquire()
        var inferenceStarted = false
        try {
            coroutineContext.ensureActive()
            check(modelAvailability() == ModelAvailability.READY) { "Selected translation models are not ready" }
            coroutineContext.ensureActive()

            val operation = synchronized(lifecycleLock) {
                ensureOpenLocked()
                inFlightTranslations += 1
                val started = try {
                    clients.getValue(pair).translate(text)
                } catch (error: Throwable) {
                    inFlightTranslations -= 1
                    throw error
                }
                // Transfer ownership before registration: an already completed Task
                // invokes this listener inline and must release the slot exactly once.
                inferenceStarted = true
                started.onComplete(::translationFinished)
                started
            }
            // Cancellation stops only this wait, not the actual SDK inference.
            return operation.await()
        } finally {
            if (!inferenceStarted) inferenceSlot.release()
        }
    }

    override fun close() = synchronized(lifecycleLock) {
        if (!disposed) {
            disposed = true
            if (inFlightTranslations == 0) closeClientsLocked()
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { closed.await() }
    }

    private fun createClients(factory: (LanguagePair) -> TranslationClient): Map<LanguagePair, TranslationClient> {
        val created = linkedMapOf<LanguagePair, TranslationClient>()
        try {
            for (pair in pairs) created[pair] = factory(pair)
        } catch (error: Throwable) {
            for (client in created.values) {
                try {
                    client.close()
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
            }
            throw error
        }
        return created
    }

    private fun translationFinished() = synchronized(lifecycleLock) {
        inFlightTranslations -= 1
        if (disposed && inFlightTranslations == 0) closeClientsLocked()
        inferenceSlot.release()
    }

    private fun closeClientsLocked() {
        if (clientsClosed) return
        clientsClosed = true
        var failure: Throwable? = null
        for (client in clients.values) {
            try {
                client.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        if (failure == null) closed.complete(Unit) else closed.completeExceptionally(failure)
    }

    private fun ensureOpen() = synchronized(lifecycleLock) { ensureOpenLocked() }

    private fun ensureOpenLocked() {
        check(!disposed) { "Translation engine is closed" }
    }

    companion object {
        val descriptor = TranslationProviderDescriptor(
            id = "mlkit",
            displayName = "ML Kit",
            provider = "Google",
            sdkVersion = "17.0.3",
            attribution = "Powered by Google Translate",
            termsUrl = "https://developers.google.com/ml-kit/terms",
        )

        // SDK support and released direct-pair evidence are separate boundaries.
        // The SDK owns pivot routing; Unknown denotes supported but unqualified directness.
        private val supportedLanguages = (
            "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu " +
                "id is it ja ka kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw " +
                "ta te th tl tr uk ur vi zh"
            ).split(' ').mapTo(hashSetOf<LanguageId>()) { LanguageId(it) }
        private val verifiedEnglishPartners = setOf(
            LanguageCatalog.RUSSIAN, LanguageCatalog.GERMAN, LanguageCatalog.FRENCH,
            LanguageCatalog.SPANISH, LanguageCatalog.ITALIAN, LanguageCatalog.PORTUGUESE,
        )

        fun capability(pair: LanguagePair): TranslationCapability {
            if (pair.source !in supportedLanguages || pair.target !in supportedLanguages) {
                return TranslationCapability.Unsupported
            }
            if (pair.source != LanguageCatalog.ENGLISH && pair.target != LanguageCatalog.ENGLISH) {
                return TranslationCapability.Pivot(LanguageCatalog.ENGLISH)
            }
            val partner = if (pair.source == LanguageCatalog.ENGLISH) pair.target else pair.source
            return if (partner in verifiedEnglishPartners) TranslationCapability.Direct else TranslationCapability.Unknown
        }

        private fun selectedPairs(pairs: Set<LanguagePair>): Set<LanguagePair> {
            require(pairs.isNotEmpty() && pairs.size <= 2) { "Select one or two directed translation pairs" }
            pairs.forEach { capability(it).requireSupported() }
            return java.util.Collections.unmodifiableSet(pairs.toSet())
        }
    }
}

internal interface TranslationClient {
    fun translate(text: String): PendingOperation<String>
    fun close()
}

private class GoogleTranslationClient(pair: LanguagePair) : TranslationClient {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(pair.source.value)
            .setTargetLanguage(pair.target.value)
            .build(),
    )

    override fun translate(text: String): PendingOperation<String> =
        GoogleTaskOperation(translator.translate(text))

    override fun close() = translator.close()
}
