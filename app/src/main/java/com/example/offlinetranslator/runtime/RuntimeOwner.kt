package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

interface RuntimeOwner {
    val isBusy: Boolean
    val activity: StateFlow<Boolean>
    fun interrupt()
    /** Release faults are values until the consumer boundary, preserving their actual identity. */
    suspend fun closeAndJoin(): Result<Unit>
}

/** Settings writes, starts, and replacements share one gate; actual lease teardown precedes construction. */
class RuntimeOwnerController(private val scope: CoroutineScope) {
    private val control = Mutex()
    @Volatile private var current: RuntimeOwner? = null
    @Volatile private var exclusive = false
    private var observation: Job? = null
    private val mutableBusy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val mutableTeardownFailed = MutableStateFlow(false)
    val teardownFailed: StateFlow<Boolean> = mutableTeardownFailed.asStateFlow()
    private val mutableOwnershipVersion = MutableStateFlow(0L)
    val ownershipVersion: StateFlow<Long> = mutableOwnershipVersion.asStateFlow()
    private var teardownFailure: Throwable? = null

    fun isCurrent(owner: RuntimeOwner): Boolean = current === owner && !mutableTeardownFailed.value

    suspend fun <T : RuntimeOwner> replace(build: suspend () -> T): T = control.withLock {
        teardownFailure?.let { throw it }
        exclusive = true
        mutableBusy.value = true
        try {
            closeCurrent().getOrThrow()
            coroutineContext.ensureActive()
            val owner = build()
            current = owner
            mutableOwnershipVersion.value++
            observation = scope.launch { owner.activity.collect { mutableBusy.value = mutableTeardownFailed.value || exclusive || owner.isBusy } }
            owner
        } finally {
            exclusive = false
            mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
        }
    }

    suspend fun <T> use(owner: RuntimeOwner, action: suspend () -> T): T? = control.withLock {
        if (current !== owner || mutableTeardownFailed.value) return@withLock null
        exclusive = true
        mutableBusy.value = true
        try { action() } finally {
            exclusive = false
            mutableBusy.value = mutableTeardownFailed.value || owner.isBusy
        }
    }

    suspend fun saveIfIdle(retainOwner: suspend (RuntimeOwner) -> Boolean, commit: suspend () -> Unit): Boolean {
        if (busy.value || current?.isBusy == true || mutableTeardownFailed.value) return false
        return control.withLock {
            if (current?.isBusy == true || mutableTeardownFailed.value) return@withLock false
            exclusive = true
            mutableBusy.value = true
            try {
                val retain = current?.let { retainOwner(it) } == true
                if (!retain) closeCurrent().getOrThrow()
                coroutineContext.ensureActive()
                commit()
                true
            } finally {
                exclusive = false
                mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
            }
        }
    }

    /** Language commits never steal an owner that won the gate after disposal. */
    suspend fun saveIfUnowned(commit: suspend () -> Boolean): Boolean = control.withLock {
        coroutineContext.ensureActive()
        if (current != null || mutableTeardownFailed.value) return@withLock false
        exclusive = true
        mutableBusy.value = true
        try { commit() } finally {
            exclusive = false
            mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
        }
    }
    /** Only the live output preference may be patched while this exact owner is active. */
    suspend fun saveSpeechOutput(
        owner: ConversationRuntimeOwner?,
        isFresh: () -> Boolean,
        commit: suspend () -> Boolean,
    ): Boolean = control.withLock {
        coroutineContext.ensureActive()
        if (mutableTeardownFailed.value || current !== owner || !isFresh()) return@withLock false
        commit()
    }

    /** Check ownership and persisted freshness before any factory or teardown side effect. */
    suspend fun <T : RuntimeOwner> createIfUnowned(
        isFresh: suspend () -> Boolean,
        build: suspend () -> T,
    ): T? = control.withLock {
        coroutineContext.ensureActive()
        if (current != null || mutableTeardownFailed.value || !isFresh()) return@withLock null
        coroutineContext.ensureActive()
        exclusive = true
        mutableBusy.value = true
        try {
            val owner = build()
            current = owner
            mutableOwnershipVersion.value++
            observation = scope.launch { owner.activity.collect { mutableBusy.value = mutableTeardownFailed.value || exclusive || owner.isBusy } }
            owner
        } finally {
            exclusive = false
            mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
        }
    }

    suspend fun release(owner: RuntimeOwner) {
        val interruptionFailure = try {
            owner.interrupt()
            null
        } catch (failure: Throwable) {
            failure
        }
        val result = withContext(NonCancellable) {
            control.withLock {
                if (current === owner) {
                    exclusive = true
                    mutableBusy.value = true
                    try { closeCurrent(interruptionFailure) } finally {
                        exclusive = false
                        mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
                    }
                } else {
                    var failure = interruptionFailure
                    try {
                        owner.closeAndJoin().exceptionOrNull()?.let { failure = appendReleaseFailure(failure, it) }
                    } catch (closing: Throwable) {
                        failure = appendReleaseFailure(failure, closing)
                    }
                    if (failure == null) Result.success(Unit) else Result.failure(requireNotNull(failure))
                }
            }
        }
        result.getOrThrow()
    }

    suspend fun closeAndJoin() {
        val result = withContext(NonCancellable) {
            control.withLock {
                exclusive = true
                mutableBusy.value = true
                try { closeCurrent() } finally {
                    exclusive = false
                    mutableBusy.value = mutableTeardownFailed.value || (current?.isBusy ?: false)
                }
            }
        }
        result.getOrThrow()
    }

    private suspend fun closeCurrent(interruptionFailure: Throwable? = null): Result<Unit> {
        teardownFailure?.let { return Result.failure(it) }
        val old = current
        var failure = interruptionFailure
        try { old?.interrupt() } catch (interrupted: Throwable) {
            failure = appendReleaseFailure(failure, interrupted)
        }
        try {
            withContext(NonCancellable) { old?.closeAndJoin() }
                ?.exceptionOrNull()?.let { failure = appendReleaseFailure(failure, it) }
        } catch (closing: Throwable) {
            failure = appendReleaseFailure(failure, closing)
        }
        failure?.let {
            teardownFailure = it
            mutableTeardownFailed.value = true
            mutableBusy.value = true
            return Result.failure(it)
        }
        observation?.cancel()
        observation = null
        current = null
        if (old != null) mutableOwnershipVersion.value++
        return Result.success(Unit)
    }
}

class ConversationRuntimeOwner(
    val config: ConversationSessionConfig,
    models: ConversationModelRepository,
    val capture: SpeechCaptureEngine,
    val recognition: ConversationRecognitionEngine,
    val translation: TranslationEngine,
    val output: ConversationSpeechOutput,
    private val scope: CoroutineScope,
    speechOutputEnabled: Boolean = true,
) : RuntimeOwner {
    /** The real screen and pipeline share this boundary and the owner's exact recognizer. */
    val models: ConversationModelRepository = ConversationResources(models, recognition)
    val pipeline = ConversationPipeline(capture, recognition, this.models, translation, output, scope, speechOutputEnabled)
    @Volatile private var closed = false
    private val closeLock = Mutex()
    private var closeResult: Result<Unit>? = null
    override val isBusy: Boolean get() = closed || pipeline.state.value.sessionActive
    override val activity: StateFlow<Boolean> = pipeline.state.map { closed || it.sessionActive }
        .stateIn(scope, SharingStarted.Eagerly, false)

    override fun interrupt() { pipeline.interruptCurrent() }

    override suspend fun closeAndJoin(): Result<Unit> = closeLock.withLock {
        closeResult?.let { return@withLock it }
        withContext(NonCancellable) {
            var failure: Throwable? = null
            try { interrupt() } catch (interrupted: Throwable) {
                failure = appendReleaseFailure(failure, interrupted)
            }
            try {
                pipeline.clear().exceptionOrNull()?.let { failure = appendReleaseFailure(failure, it) }
            } catch (clearing: Throwable) {
                failure = appendReleaseFailure(failure, clearing)
            }
            try { capture.closeAndJoin() } catch (closing: Throwable) {
                failure = appendReleaseFailure(failure, closing)
            }
            try { output.closeAndJoin() } catch (closing: Throwable) {
                failure = appendReleaseFailure(failure, closing)
            }
            try { recognition.closeAndJoin() } catch (closing: Throwable) {
                failure = appendReleaseFailure(failure, closing)
            }
            try { translation.closeAndJoin() } catch (closing: Throwable) {
                failure = appendReleaseFailure(failure, closing)
            }
            try { models.close() } catch (closing: Throwable) {
                failure = appendReleaseFailure(failure, closing)
            }
            closed = true
            scope.cancel()
            (if (failure == null) Result.success(Unit) else Result.failure(requireNotNull(failure)))
                .also { closeResult = it }
        }
    }
}

private class ConversationResources(
    private val native: ConversationModelRepository,
    private val recognition: ConversationRecognitionEngine,
) : ConversationModelRepository {
    override val models: StateFlow<List<SpeechModelStatus>> get() = native.models
    override suspend fun install(speechOutputRequired: Boolean) {
        native.install(speechOutputRequired)
        recognition.install()
    }
    override suspend fun isConversationReady(speechOutputRequired: Boolean): Boolean {
        // Recheck recognition even when a native package is missing: expose the actual OS state.
        val recognitionReady = recognition.readiness() == SpeechRecognitionReadiness.READY
        return native.isConversationReady(speechOutputRequired) && recognitionReady
    }
    // The owner awaits recognition release once, before closing this native projection.
    override fun close() = native.close()
}

class TextRuntimeOwner(val config: TextRuntimeConfig, val translation: TranslationEngine) : RuntimeOwner {
    private val mutableActivity = MutableStateFlow(false)
    override val activity: StateFlow<Boolean> = mutableActivity.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var activeOperation: Job? = null
    private val closeLock = Mutex()
    private var closeResult: Result<Unit>? = null
    override val isBusy: Boolean get() = closed || mutableActivity.value
    suspend fun <T> operation(action: suspend () -> T): T {
        check(!closed)
        activeOperation = coroutineContext[Job]
        mutableActivity.value = true
        try { return action() } finally {
            activeOperation = null
            mutableActivity.value = false
        }
    }
    override fun interrupt() { activeOperation?.cancel() }
    override suspend fun closeAndJoin(): Result<Unit> = closeLock.withLock {
        closeResult?.let { return@withLock it }
        withContext(NonCancellable) {
            interrupt()
            runCatching { translation.closeAndJoin() }.also {
                closed = true
                closeResult = it
            }
        }
    }
}

/** Add distinct actual lease faults without manufacturing copies or creating cause cycles. */
internal fun appendReleaseFailure(first: Throwable?, next: Throwable): Throwable {
    if (first == null) return next
    if (first !== next && !containsReleaseFailure(first, next) && !containsReleaseFailure(next, first)) {
        first.addSuppressed(next)
    }
    return first
}

private fun containsReleaseFailure(root: Throwable, target: Throwable): Boolean {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>()
    pending.add(root)
    while (pending.isNotEmpty()) {
        val current = pending.removeLast()
        if (current === target) return true
        if (!seen.add(current)) continue
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return false
}
