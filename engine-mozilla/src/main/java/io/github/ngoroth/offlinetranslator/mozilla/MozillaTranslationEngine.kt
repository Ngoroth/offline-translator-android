package io.github.ngoroth.offlinetranslator.mozilla

import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

/** One serialized owner for the selected direct models and English-pivot routes. */
class MozillaTranslationEngine(
    pairs: Set<LanguagePair>,
    private val preparationFiles: MozillaTranslationPreparation,
) : TranslationEngine {
    private val selected = pairs.toSet().also {
        require(it.isNotEmpty() && it.size <= 2) { "Select one or two directed translation pairs" }
        it.forEach { pair -> capability(pair).requireSupported() }
    }
    private val slot = Mutex()
    private val lifecycle = Any()
    private val executor = Executors.newSingleThreadExecutor { action -> Thread(action, "mozilla-inference") }
    private val released = CompletableDeferred<Unit>()
    private val mutablePreparation = MutableStateFlow(TranslationPreparationState())
    override val preparation = mutablePreparation.asStateFlow()
    // Confined to executor; close also queues disposal on that executor.
    private var native: MozillaNativeRuntime? = null
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var activeOperation: Job? = null
    private var releaseFailure: Throwable? = null

    override suspend fun modelAvailability(): ModelAvailability = operation {
        try {
            val installed = preparationFiles.localConfigurations(selected)
            if (ready && installed != null) ModelAvailability.READY else {
                if (ready) discardNative()
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
                ModelAvailability.MISSING
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            try { discardNative() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            val failure = mapFailure(error)
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            ModelAvailability.MISSING
        }
    }

    override suspend fun prepareLocal(): ModelAvailability = prepare(localOnly = true)
    override suspend fun prepare(): ModelAvailability = prepare(localOnly = false)

    private suspend fun prepare(localOnly: Boolean): ModelAvailability = operation {
        if (ready) return@operation ModelAvailability.READY
        try {
            val configs = coroutineScope {
                val observation = launch(start = CoroutineStart.UNDISPATCHED) {
                    preparationFiles.state.collect { mutablePreparation.value = it }
                }
                try {
                    if (localOnly) preparationFiles.localConfigurations(selected) else preparationFiles.prepareConfigurations(selected)
                } finally { observation.cancelAndJoin() }
            }
            if (configs == null) {
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
                return@operation ModelAvailability.MISSING
            }
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.LOADING)
            if (withTimeoutOrNull(INITIALIZATION_MILLIS) {
                    nativeOperation { load(configs.keys.toTypedArray(), configs.values.toTypedArray()) }
                    true
                } != true) throw PreparationFailure.PreparationTimedOut()
            val context = currentCoroutineContext()
            synchronized(lifecycle) {
                context.ensureActive()
                check(!closed) { "Translation engine is closed" }
                ready = true
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
            }
            ModelAvailability.READY
        } catch (cancelled: CancellationException) {
            try { discardNative() } catch (cleanup: Throwable) { cancelled.addSuppressed(cleanup) }
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            throw cancelled
        } catch (error: Throwable) {
            try { discardNative() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            val failure = mapFailure(error)
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            throw failure
        }
    }

    override suspend fun translate(pair: LanguagePair, text: String): String {
        capability(pair).requireSupported()
        require(pair in selected) { "Translation pair was not selected" }
        if (text.length > TranslationEngine.MAX_INPUT_CHARACTERS) throw TranslationFailure.InputLimit()
        require(text.isNotBlank()) { "Translation text must not be blank" }
        return operation {
            if (!ready) throw TranslationFailure.ModelMissing()
            try {
                var result = text
                for (model in MozillaTranslationCatalog.PINNED.models(pair)) {
                    currentCoroutineContext().ensureActive()
                    result = nativeOperation { translate(model.key, result) }
                    if (result.isBlank()) throw TranslationFailure.RuntimeLoad()
                    if (result.length > TranslationEngine.MAX_INPUT_CHARACTERS) throw TranslationFailure.OutputLimit()
                }
                currentCoroutineContext().ensureActive()
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val failure = mapFailure(error)
                if (!ready || failure is TranslationFailure.RuntimeLoad || failure is TranslationFailure.Memory ||
                    failure is TranslationFailure.ModelMissing || failure is TranslationFailure.ArtifactCorrupt) {
                    try { discardNative() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                    mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
                }
                throw failure
            }
        }
    }

    private suspend fun <T> operation(action: suspend () -> T): T = slot.withLock {
        val job = currentCoroutineContext()[Job]
        synchronized(lifecycle) {
            check(!closed) { "Translation engine is closed" }
            releaseFailure?.let { throw it }
            activeOperation = job
        }
        try { action() } finally { synchronized(lifecycle) { activeOperation = null } }
    }

    private suspend fun <T> nativeOperation(action: MozillaNativeRuntime.() -> T): T {
        val completed = CompletableDeferred<Result<T>>()
        try {
            return suspendCancellableCoroutine { continuation ->
                synchronized(lifecycle) {
                    if (closed) {
                        val failure = IllegalStateException("Translation engine is closed")
                        completed.complete(Result.failure(failure))
                        if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                    } else try {
                        executor.execute {
                            val result = runCatching {
                                if (!continuation.isActive) throw CancellationException("Translation caller cancelled")
                                val runtime = native ?: MozillaNativeRuntime().also { native = it }
                                runtime.action()
                            }
                            result.exceptionOrNull()?.let { error ->
                                if (error !is CancellationException) {
                                    ready = false
                                    val damaged = native
                                    native = null
                                    try { damaged?.close() } catch (closing: Throwable) { recordReleaseFailure(closing) }
                                }
                            }
                            completed.complete(result)
                            if (continuation.isActive) continuation.resumeWith(result)
                        }
                    } catch (error: Throwable) {
                        completed.complete(Result.failure(error))
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
                }
            }
        } finally {
            // Coroutine cancellation does not interrupt upstream BlockingService.
            withContext(NonCancellable) { completed.await() }
        }
    }

    private suspend fun discardNative() = withContext(NonCancellable) {
        ready = false
        val finished = CompletableDeferred<Unit>()
        val closing = synchronized(lifecycle) {
            if (closed) true else {
                executor.execute {
                    val runtime = native
                    native = null
                    try { runtime?.close(); finished.complete(Unit) } catch (error: Throwable) {
                        recordReleaseFailure(error)
                        finished.completeExceptionally(error)
                    }
                }
                false
            }
        }
        if (closing) released.await() else finished.await()
    }

    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            ready = false
            activeOperation?.cancel()
            executor.execute {
                val runtime = native
                native = null
                try { runtime?.close() } catch (error: Throwable) { recordReleaseFailure(error) }
                finally {
                    val failure = synchronized(lifecycle) { releaseFailure }
                    if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
                    executor.shutdown()
                }
            }
        }
        Unit
    }

    override suspend fun awaitIdle() {
        slot.withLock { synchronized(lifecycle) { releaseFailure?.let { throw it } } }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { slot.withLock { released.await() } }
    }

    private fun recordReleaseFailure(error: Throwable) = synchronized(lifecycle) {
        val first = releaseFailure
        if (first == null) releaseFailure = error else if (first !== error) first.addSuppressed(error)
    }

    companion object {
        private const val INITIALIZATION_MILLIS = 2 * 60 * 1000L
        val descriptor = TranslationProviderDescriptor(
            id = "mozilla", displayName = "Mozilla", provider = "Mozilla",
            sdkVersion = "Bergamot 8eada038 / Firefox translation models",
            attribution = "Mozilla Translations and contributors; MPL-2.0 models",
            termsUrl = "https://www.mozilla.org/MPL/2.0/",
        )
        fun capability(pair: LanguagePair): TranslationCapability = MozillaTranslationCatalog.PINNED.capability(pair)

        internal fun mapFailure(error: Throwable): TranslationFailure = when (error) {
            is TranslationFailure -> error
            is OutOfMemoryError -> TranslationFailure.Memory()
            is MozillaNativeException -> when (error.code) {
                "model_missing" -> TranslationFailure.ModelMissing()
                "artifact_corrupt" -> TranslationFailure.ArtifactCorrupt()
                "memory" -> TranslationFailure.Memory()
                "input_limit" -> TranslationFailure.InputLimit()
                "token_limit" -> TranslationFailure.TokenLimit()
                "output_limit" -> TranslationFailure.OutputLimit()
                "invalid_encoding" -> TranslationFailure.InvalidEncoding()
                else -> TranslationFailure.RuntimeLoad()
            }
            else -> TranslationFailure.RuntimeLoad()
        }
    }
}
