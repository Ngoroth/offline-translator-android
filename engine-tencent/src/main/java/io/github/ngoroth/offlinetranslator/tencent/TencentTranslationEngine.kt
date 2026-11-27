package io.github.ngoroth.offlinetranslator.tencent

import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

internal interface TencentInference : AutoCloseable {
    fun load(modelPath: String)
    fun translate(target: String, text: String): String
    fun resetCancellation()
    fun cancel()
}

private class NativeInference : TencentInference {
    private val runtime = TencentNativeRuntime()
    override fun load(modelPath: String) = runtime.load(modelPath)
    override fun translate(target: String, text: String) = runtime.translate(target, text)
    override fun resetCancellation() = runtime.resetCancellation()
    override fun cancel() = runtime.cancel()
    override fun close() = runtime.close()
}

/** One owner, one serialized native worker, one multilingual model/context; no provider fallback. */
class TencentTranslationEngine internal constructor(
    pairs: Set<LanguagePair>,
    private val artifacts: TencentArtifactPreparation,
    private val createInference: () -> TencentInference,
) : TranslationEngine {
    constructor(pairs: Set<LanguagePair>, artifacts: TencentArtifactPreparation) :
        this(pairs, artifacts, ::NativeInference)

    private val pairs = pairs.toSet().also { selected ->
        require(selected.isNotEmpty() && selected.size <= 2) { "Select one or two directed translation pairs" }
        selected.forEach { capability(it).requireSupported() }
    }
    private val slot = Mutex()
    private val lifecycle = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "tencent-inference") }
    private val released = CompletableDeferred<Throwable?>()
    private val mutablePreparation = MutableStateFlow(TranslationPreparationState())
    override val preparation = mutablePreparation.asStateFlow()
    private val mutableUpdate = MutableStateFlow(TranslationArtifactUpdateState())
    override val artifactUpdate: TranslationArtifactUpdate = object : TranslationArtifactUpdate {
        override val state = mutableUpdate.asStateFlow()
        override suspend fun update() = updateArtifact()
    }
    private var native: TencentInference? = null
    @Volatile private var closed = false
    @Volatile private var activeArtifact: TencentArtifactPreparation.Installed? = null
    private var activeOperation: Job? = null
    private var releaseFailure: Throwable? = null

    override suspend fun modelAvailability(): ModelAvailability = operation {
        try {
            val file = verifiedFile()
            if (file != null && activeArtifact?.artifact == file.artifact) {
                publishPreparation(TranslationPreparationState(TranslationPreparationPhase.READY))
                ModelAvailability.READY
            } else {
                if (activeArtifact != null) discardNative()
                notReady()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (activeArtifact != null) discardAfter(error)
            failPreparation(error)
            ModelAvailability.MISSING
        }
    }

    override suspend fun prepare(): ModelAvailability = prepare(localOnly = false)

    override suspend fun prepareLocal(): ModelAvailability = prepare(localOnly = true)

    private suspend fun prepare(localOnly: Boolean): ModelAvailability = operation {
        if (activeArtifact != null) return@operation ModelAvailability.READY
        try {
            val installed = try { verifiedFile() } catch (corrupt: TranslationFailure.ArtifactCorrupt) {
                if (localOnly) throw corrupt
                null
            }
            if (installed != null) {
                try {
                    initialize(installed.file, ::publishPreparation)
                    activate(installed)
                    if (installed.current) artifacts.retirePrevious()
                    return@operation ModelAvailability.READY
                } catch (failure: TranslationFailure) {
                    // The explicit recovery path may replace only an unusable previous model.
                    if (localOnly || installed.current) throw failure
                    checkCanInitialize()
                }
            }
            if (localOnly) return@operation notReady()
            installCurrent(::publishPreparation)
            ModelAvailability.READY
        } catch (cancelled: CancellationException) {
            if (activeArtifact == null) {
                discardAfter(cancelled)
                notReady()
            }
            throw cancelled
        } catch (error: Throwable) {
            discardAfter(error)
            throw failPreparation(error)
        }
    }

    private suspend fun updateArtifact(): Unit = operation {
        val previous = activeArtifact?.takeUnless { it.current } ?: return@operation
        try {
            installCurrent(::publishUpdatePreparation)
        } catch (cancelled: CancellationException) {
            if (activeArtifact == null) {
                discardAfter(cancelled)
                notReady()
            }
            publishUpdatePreparation(null)
            throw cancelled
        } catch (error: Throwable) {
            try {
                if (activeArtifact == null) {
                    discardNative()
                    checkCanInitialize()
                    // Stage ownership has already unwound. Reattest before reopening.
                    val installed = artifacts.verifiedFile() ?: throw TranslationFailure.ModelMissing()
                    if (!installed.current && installed.artifact != previous.artifact) throw TranslationFailure.ArtifactCorrupt()
                    initialize(installed.file, ::publishUpdatePreparation)
                    activate(installed)
                    if (installed.current) artifacts.retirePrevious()
                }
            } catch (cancelled: CancellationException) {
                discardAfter(cancelled)
                notReady()
                publishUpdatePreparation(null)
                throw cancelled
            } catch (recovery: Throwable) {
                if (recovery !== error) recovery.addSuppressed(error)
                discardAfter(recovery)
                throw failPreparation(recovery)
            }
            if (activeArtifact?.current == false) {
                publishUpdatePreparation(TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = mapFailure(error)))
            }
        }
    }

    private suspend fun installCurrent(progress: (TranslationPreparationState) -> Unit) {
        val commit = TencentArtifactPreparation.Commit()
        try {
            val installed = artifacts.prepareCurrent(commit, progress) { file ->
                discardNative()
                initialize(file, progress)
            }
            activate(installed)
        } catch (cancelled: CancellationException) {
            // A completed move wins even when the enclosing IO dispatch did not return a value.
            commit.installed?.let { activate(it) }
            throw cancelled
        } catch (error: Throwable) {
            val installed = commit.installed ?: throw error
            activate(installed)
            logger.log(Level.WARNING, "Tencent artifact committed before a bookkeeping failure", error)
            return // Retirement is retried after a later successful local load.
        }
        artifacts.retirePrevious()
    }

    private suspend fun initialize(file: File, progress: (TranslationPreparationState) -> Unit) {
        checkCanInitialize()
        progress(TranslationPreparationState(TranslationPreparationPhase.LOADING))
        try {
            if (withTimeoutOrNull(INITIALIZATION_TIMEOUT_MILLIS) {
                    nativeOperation { load(file.absolutePath) }
                    nativeOperation { translate("English", "1") }
                    true
                } != true) throw PreparationFailure.PreparationTimedOut()
        } catch (error: Throwable) {
            discardAfter(error)
            if (error is CancellationException) throw error
            throw mapFailure(error)
        }
    }

    private suspend fun checkCanInitialize() {
        currentCoroutineContext().ensureActive()
        synchronized(lifecycle) {
            check(!closed) { "Translation engine is closed" }
            releaseFailure?.let { throw mapFailure(it) }
        }
    }

    private suspend fun verifiedFile() = artifacts.verifiedFile {
        publishPreparation(TranslationPreparationState(TranslationPreparationPhase.VERIFYING))
    }

    private fun publishPreparation(value: TranslationPreparationState) = synchronized(lifecycle) {
        if (!closed) mutablePreparation.value = value
    }

    private fun publishUpdatePreparation(value: TranslationPreparationState?) = synchronized(lifecycle) {
        if (!closed) mutableUpdate.value = mutableUpdate.value.copy(preparation = value)
    }

    private fun activate(installed: TencentArtifactPreparation.Installed) = synchronized(lifecycle) {
        if (!closed) {
            activeArtifact = installed
            mutableUpdate.value = TranslationArtifactUpdateState(installed.artifact.descriptor, offerAvailable = !installed.current)
            mutablePreparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
        }
    }

    private fun notReady(): ModelAvailability {
        publishPreparation(TranslationPreparationState(TranslationPreparationPhase.MISSING))
        return ModelAvailability.MISSING
    }

    private fun failPreparation(error: Throwable): TranslationFailure = mapFailure(error).also {
        publishUpdatePreparation(null)
        publishPreparation(TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = it))
    }

    private suspend fun discardAfter(error: Throwable) {
        try { discardNative() } catch (closing: Throwable) {
            if (closing !== error) error.addSuppressed(closing)
        }
    }

    override suspend fun translate(pair: LanguagePair, text: String): String {
        capability(pair).requireSupported()
        require(pair in pairs) { "Translation pair was not selected" }
        if (text.length > TranslationEngine.MAX_INPUT_CHARACTERS) throw TranslationFailure.InputLimit()
        require(text.isNotBlank()) { "Translation text must not be blank" }
        return operation {
            if (activeArtifact == null) throw TranslationFailure.ModelMissing()
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
    private suspend fun <T> nativeOperation(action: TencentInference.() -> T): T {
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
                    val runtime = try { native ?: createInference().also { native = it } } catch (error: Throwable) {
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

    private suspend fun discardNative() {
        val finished = CompletableDeferred<Throwable?>()
        synchronized(lifecycle) {
            activeArtifact = null
            mutableUpdate.value = TranslationArtifactUpdateState(preparation = mutableUpdate.value.preparation)
            val runtime = native
            native = null
            if (closed || runtime == null) finished.complete(null) else executor.execute {
                try { runtime.close(); finished.complete(null) } catch (error: Throwable) {
                    recordReleaseFailure(error)
                    finished.complete(error)
                }
            }
        }
        val failure = withContext(NonCancellable) { finished.await() }
        if (failure != null) throw failure
    }

    override fun close() = synchronized(lifecycle) {
        if (!closed) {
            closed = true
            activeArtifact = null
            mutableUpdate.value = TranslationArtifactUpdateState()
            activeOperation?.cancel()
            try { native?.cancel() } catch (error: Throwable) { recordReleaseFailure(error) }
            val runtime = native
            native = null
            // FIFO executor order joins all actual load/decode work before closing native state.
            executor.execute {
                try { runtime?.close() } catch (error: Throwable) { recordReleaseFailure(error) }
                finally {
                    val failure = synchronized(lifecycle) { releaseFailure }
                    released.complete(failure)
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
        // Transport the actual failure as a value so coroutine stack recovery cannot copy it.
        val failure = withContext(NonCancellable) { slot.withLock { released.await() } }
        if (failure != null) throw failure
    }

    private fun recordReleaseFailure(error: Throwable) = synchronized(lifecycle) {
        val first = releaseFailure
        if (first == null) releaseFailure = error else if (first !== error) first.addSuppressed(error)
    }

    companion object {
        private const val INITIALIZATION_TIMEOUT_MILLIS = 2 * 60 * 1000L
        private val logger = Logger.getLogger(TencentTranslationEngine::class.java.name)
        val descriptor = TencentTranslationArtifact.CURRENT.descriptor
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
