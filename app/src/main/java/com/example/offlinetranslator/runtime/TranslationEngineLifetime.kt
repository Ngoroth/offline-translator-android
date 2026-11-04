package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One retained delegate; route leases revoke work, not healthy installed inference resources. */
internal class TranslationEngineLifetime(
    private val scope: CoroutineScope,
    private val registry: TranslationEngineRegistry,
) {
    private val lock = Any()
    private val slot = Mutex()
    private var foreground = true
    private var closed = false
    private var generation = 0L
    private var delegate: TranslationEngine? = null
    private var key: Pair<String, Set<LanguagePair>>? = null
    private var poison: Throwable? = null
    private val leases = mutableSetOf<Lease>()

    fun lease(engineId: String, pairs: Set<LanguagePair>): TranslationEngine {
        registry.validate(engineId, pairs)
        val selected = pairs.toSet()
        val pair = selected.first()
        val coverage = setOf(pair, pair.reverse())
        require(selected.all { it in coverage }) { "Select one unordered language pair" }
        return synchronized(lock) {
            check(!closed)
            poison?.let { throw it }
            Lease(engineId, selected, coverage).also { leases.add(it) }
        }
    }

    fun enterForeground() = synchronized(lock) { if (!closed) foreground = true }
    fun leaveForeground() { invalidate(background = true) }
    fun trimMemory() { invalidate(background = false) }

    private fun invalidate(background: Boolean) {
        synchronized(lock) {
            if (closed) return
            if (background) foreground = false
            generation++
            leases.forEach { it.invalidate() }
            // Undispatched launch queues teardown before a fast resume can acquire the slot.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Cleanup faults are retained as poison and rethrown at every consumer boundary.
                withContext(NonCancellable) { slot.withLock { runCatching { discard() } } }
            }
        }
    }

    suspend fun invalidateSelection(engineId: String, pair: LanguagePair) {
        val selected = engineId to setOf(pair, pair.reverse())
        if (synchronized(lock) { key == null || key == selected }) return
        invalidate(background = false)
        slot.withLock { poison?.let { throw it } }
    }

    private suspend fun discard() {
        synchronized(lock) { poison?.let { throw it } }
        val old = synchronized(lock) {
            leases.forEach { it.observation?.cancel(); it.observation = null }
            delegate
        }
        var failure: Throwable? = null
        try { old?.awaitIdle() } catch (draining: Throwable) { failure = draining }
        try { old?.closeAndJoin() } catch (closing: Throwable) {
            failure = appendReleaseFailure(failure, closing)
        }
        if (failure != null) {
            synchronized(lock) { if (poison == null) poison = failure }
            throw requireNotNull(poison)
        }
        synchronized(lock) { delegate = null; key = null }
    }

    suspend fun closeAndJoin() {
        val result = withContext(NonCancellable) {
            runCatching {
                synchronized(lock) {
                    closed = true
                    foreground = false
                    generation++
                    leases.forEach { it.invalidate() }
                }
                slot.withLock {
                    poison?.let { throw it }
                    discard()
                }
            }
        }
        result.getOrThrow()
    }

    private inner class Lease(
        val engineId: String,
        val requested: Set<LanguagePair>,
        val coverage: Set<LanguagePair>,
    ) : TranslationEngine {
        private var revoked = false
        private var closeFailure: Throwable? = null
        private var joined = false
        private var usedDelegate: TranslationEngine? = null
        private val closing = Mutex()
        private val jobs = mutableSetOf<Job>()
        var observation: Job? = null
        private val state = MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.MISSING))
        override val preparation = state.asStateFlow()

        fun invalidate() {
            observation?.cancel()
            observation = null
            state.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            jobs.toList().forEach { it.cancel() }
        }

        private suspend fun <T> operation(action: suspend (TranslationEngine) -> T): T {
            val result = coroutineScope {
                val task = async(start = CoroutineStart.LAZY) {
                    try {
                        Result.success(slot.withLock {
                    val version = synchronized(lock) {
                        check(!revoked && !closed && foreground) { "Translation lease is not active" }
                        poison?.let { throw it }
                        generation
                    }
                    val selected = engineId to coverage
                    if (key != selected) discard()
                    val engine = synchronized(lock) {
                        check(version == generation && !revoked && foreground && !closed)
                        delegate ?: registry.create(engineId, coverage).also { delegate = it; key = selected }
                    }
                    synchronized(lock) {
                        usedDelegate = engine
                        observation?.cancel()
                        state.value = engine.preparation.value
                        observation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            engine.preparation.collect { value ->
                                synchronized(lock) {
                                    if (!revoked && version == generation && delegate === engine) state.value = value
                                }
                            }
                        }
                    }
                    try {
                        action(engine).also {
                            if (engine.preparation.value.phase == TranslationPreparationPhase.FAILED) {
                                publishFailure(engine, version)
                                discard()
                            }
                        }
                    } catch (failure: Throwable) {
                        if (failure is TranslationFailure && failure !is TranslationFailure.InputLimit &&
                            failure !is TranslationFailure.TokenLimit && failure !is TranslationFailure.OutputLimit &&
                            failure !is TranslationFailure.InvalidEncoding) {
                            publishFailure(engine, version, failure)
                            withContext(NonCancellable) { discard() }
                        }
                        throw failure
                    }
                        })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Result.failure<T>(synchronized(lock) { poison ?: failure })
                    }
                }
                val registration = synchronized(lock) {
                    if (revoked || closed || !foreground) IllegalStateException("Translation lease is not active")
                    else poison.also { if (it == null) jobs.add(task) }
                }
                if (registration != null) {
                    task.cancel()
                    return@coroutineScope Result.failure<T>(registration)
                }
                try { task.start(); task.await() } finally { synchronized(lock) { jobs.remove(task) } }
            }
            // Failure objects cross coroutine boundaries as values, never recovered exception copies.
            return result.getOrThrow()
        }

        private fun publishFailure(engine: TranslationEngine, version: Long, failure: TranslationFailure? = null) {
            synchronized(lock) {
                if (revoked || closed || !foreground || version != generation || delegate !== engine) return
                val current = engine.preparation.value
                val terminal = if (current.phase == TranslationPreparationPhase.FAILED) {
                    if (current.lastFailure == null && failure != null) current.copy(lastFailure = failure) else current
                } else TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
                // Invalidation clears observations, so only leases still observing this generation receive the fault.
                leases.filter { !it.revoked && it.usedDelegate === engine && it.observation != null }
                    .forEach { it.state.value = terminal }
            }
        }

        override suspend fun modelAvailability() = operation { it.modelAvailability() }
        override suspend fun prepareLocal() = operation { it.prepareLocal() }
        override suspend fun prepare() = operation { it.prepare() }
        override suspend fun translate(pair: LanguagePair, text: String): String {
            require(pair in requested) { "Translation pair was not selected by this lease" }
            return operation { it.translate(pair, text) }
        }
        override fun close() {
            synchronized(lock) {
                if (!revoked) { revoked = true; invalidate() }
            }
        }
        override suspend fun closeAndJoin() {
            val result = withContext(NonCancellable) {
                runCatching {
                    close()
                    closing.withLock {
                closeFailure?.let { throw it }
                if (joined) return@withLock
                val active = synchronized(lock) { jobs.toList() }
                active.joinAll()
                try {
                    slot.withLock {
                        poison?.let { throw it }
                        if (delegate === usedDelegate) {
                            try { usedDelegate?.awaitIdle() } catch (draining: Throwable) {
                                try { usedDelegate?.closeAndJoin() } catch (closing: Throwable) {
                                    appendReleaseFailure(draining, closing)
                                }
                                synchronized(lock) { if (poison == null) poison = draining }
                                throw requireNotNull(poison)
                            }
                        }
                    }
                    synchronized(lock) { leases.remove(this@Lease) }
                    joined = true
                } catch (failure: Throwable) {
                    synchronized(lock) {
                        if (poison == null) poison = failure
                        closeFailure = poison
                    }
                    throw requireNotNull(closeFailure)
                }
                    }
                }
            }
            result.getOrThrow()
        }
    }
}
