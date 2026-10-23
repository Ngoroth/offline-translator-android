package com.example.offlinetranslator.tencent

import com.example.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One owner, one serialized native worker, one multilingual model/context; no provider fallback. */
class TencentTranslationEngine(
    pairs: Set<LanguagePair>,
    private val artifacts: TencentArtifactPreparation,
) : TranslationEngine {
    private val pairs = pairs.toSet().also { selected ->
        require(selected.isNotEmpty() && selected.size <= 2) { "Select one or two directed translation pairs" }
        selected.forEach { capability(it).requireSupported() }
    }
    private val slot = Mutex()
    private val lifecycle = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tencent-inference") }
    private val released = CompletableDeferred<Unit>()
    private val mutablePreparation = MutableStateFlow(TranslationPreparationState())
    override val preparation = mutablePreparation.asStateFlow()
    private var native: TencentNativeRuntime? = null
    @Volatile private var closed = false
    @Volatile private var ready = false
    private var activeOperation: Job? = null
    private var releaseFailure: Throwable? = null

    override suspend fun modelAvailability(): ModelAvailability = operation {
        try {
            val file = artifacts.verifiedFile {
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.VERIFYING)
            }
            if (ready && file != null) {
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
                ModelAvailability.READY
            } else {
                // Installed weights still need explicit local initialization after an owner/process restart.
                if (ready) discardNative()
                ready = false
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
                ModelAvailability.MISSING
            }
        } catch (failure: TranslationFailure) {
            if (ready) try { discardNative() } catch (closing: Throwable) { failure.addSuppressed(closing) }
            ready = false
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            ModelAvailability.MISSING
        }
    }

    override suspend fun prepare(): ModelAvailability = operation {
        if (ready) return@operation ModelAvailability.READY
        try {
            coroutineScope {
                val observation = launch(start = CoroutineStart.UNDISPATCHED) {
                    artifacts.state.collect { mutablePreparation.value = it }
                }
                val file = try { artifacts.prepare() } finally { observation.cancelAndJoin() }
                mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.LOADING)
                if (withTimeoutOrNull(INITIALIZATION_TIMEOUT_MILLIS) {
                        nativeOperation { load(file.absolutePath) }
                        nativeOperation { translate("English", "1") }
                        true
                    } != true) throw PreparationFailure.PreparationTimedOut()
            }
            ready = true
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
            ModelAvailability.READY
        } catch (cancelled: CancellationException) {
            try { discardNative() } catch (closing: Throwable) { cancelled.addSuppressed(closing) }
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            throw cancelled
        } catch (error: Throwable) {
            try { discardNative() } catch (closing: Throwable) { if (closing !== error) error.addSuppressed(closing) }
            val failure = mapFailure(error)
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            throw failure
        }
    }

    override suspend fun translate(pair: LanguagePair, text: String): String {
        capability(pair).requireSupported()
        require(pair in pairs) { "Translation pair was not selected" }
        if (text.length > TranslationEngine.MAX_INPUT_CHARACTERS) throw TranslationFailure.InputLimit()
        require(text.isNotBlank()) { "Translation text must not be blank" }
        return operation {
            if (!ready) throw TranslationFailure.ModelMissing()
            // Native validates the original UTF-16 bound and normalizes only its inference copy.
            try {
                nativeOperation { translate(
                    if (pair.target == LanguageCatalog.CHINESE) "Simplified Chinese" else LanguageCatalog.find(pair.target)!!.englishName,
                    text,
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val failure = mapFailure(error)
                if (failure is TranslationFailure.ModelMissing || failure is TranslationFailure.ArtifactCorrupt ||
                    failure is TranslationFailure.RuntimeLoad || failure is TranslationFailure.Memory) {
                    try { discardNative() } catch (closing: Throwable) { if (closing !== error) error.addSuppressed(closing) }
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

    /** Cancellation stops actual native work and joins it before the serialized slot can be reused. */
    private suspend fun <T> nativeOperation(action: TencentNativeRuntime.() -> T): T {
        val completed = CompletableDeferred<Result<T>>()
        try {
            return suspendCancellableCoroutine { continuation ->
                synchronized(lifecycle) {
                    if (closed) {
                        val failure = IllegalStateException("Translation engine is closed")
                        completed.complete(Result.failure(failure))
                        continuation.resumeWith(Result.failure(failure))
                        return@synchronized
                    }
                    val runtime = try { native ?: TencentNativeRuntime().also { native = it } } catch (error: Throwable) {
                        completed.complete(Result.failure(error))
                        continuation.resumeWith(Result.failure(error))
                        return@synchronized
                    }
                    try {
                        // Reset BEFORE cancellation registration: cancellation must never be erased by the worker.
                        runtime.resetCancellation()
                        continuation.invokeOnCancellation {
                            try { runtime.cancel() } catch (error: Throwable) { recordReleaseFailure(error) }
                        }
                        executor.execute {
                            val result = runCatching { runtime.action() }
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
            withContext(NonCancellable) { completed.await() }
        }
    }

    private suspend fun discardNative() = withContext(NonCancellable) {
        ready = false
        val finished = CompletableDeferred<Unit>()
        synchronized(lifecycle) {
            val runtime = native
            native = null
            if (closed || runtime == null) finished.complete(Unit) else executor.execute {
                try { runtime.close(); finished.complete(Unit) } catch (error: Throwable) {
                    recordReleaseFailure(error)
                    finished.completeExceptionally(error)
                }
            }
        }
        finished.await()
    }

    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            ready = false
            activeOperation?.cancel()
            try { native?.cancel() } catch (error: Throwable) { recordReleaseFailure(error) }
            val runtime = native
            native = null
            // FIFO executor order joins all actual load/decode work before closing native state.
            executor.execute {
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

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { slot.withLock { released.await() } }
    }

    private fun recordReleaseFailure(error: Throwable) = synchronized(lifecycle) {
        val first = releaseFailure
        if (first == null) releaseFailure = error else if (first !== error) first.addSuppressed(error)
    }

    companion object {
        private const val INITIALIZATION_TIMEOUT_MILLIS = 2 * 60 * 1000L
        val descriptor = TranslationProviderDescriptor(
            id = "tencent", displayName = "Tencent Hy-MT2", provider = "Tencent",
            sdkVersion = "Hy-MT2-1.8B Q4_K_M a0c709d9 / llama.cpp d8d92973",
            attribution = "Hy-MT2 by Tencent",
            termsUrl = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/blob/${TencentTranslationArtifact.REVISION}/LICENSE.txt",
        )
        fun capability(pair: LanguagePair): TranslationCapability =
            if (pair.source != pair.target && LanguageCatalog.find(pair.source) != null && LanguageCatalog.find(pair.target) != null)
                TranslationCapability.Direct else TranslationCapability.Unsupported

        internal fun mapFailure(error: Throwable): TranslationFailure = when (error) {
            is TranslationFailure -> error
            is OutOfMemoryError -> TranslationFailure.Memory()
            is TencentNativeException -> when (error.code) {
                "model_missing" -> TranslationFailure.ModelMissing()
                "model_corrupt" -> TranslationFailure.ArtifactCorrupt()
                "memory" -> TranslationFailure.Memory()
                "input_limit" -> TranslationFailure.InputLimit()
                "token_limit" -> TranslationFailure.TokenLimit()
                "output_limit" -> TranslationFailure.OutputLimit()
                "invalid_encoding" -> TranslationFailure.InvalidEncoding()
                "cancelled" -> throw CancellationException("Native translation cancelled")
                else -> TranslationFailure.RuntimeLoad(error)
            }
            else -> TranslationFailure.RuntimeLoad(error)
        }
    }
}
