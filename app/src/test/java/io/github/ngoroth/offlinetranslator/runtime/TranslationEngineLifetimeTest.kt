package io.github.ngoroth.offlinetranslator.runtime

import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranslationEngineLifetimeTest {
    private val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    private class Provider : TranslationEngine {
        override val preparation = MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.MISSING))
        var warms = 0
        var closes = 0
        var closeError: Throwable? = null
        var drainError: Throwable? = null
        var drains = 0
        var translationError: Throwable? = null
        var sdkFinished = CompletableDeferred(Unit)
        var entered = CompletableDeferred<Unit>()
        var blockTranslation = false
        var availabilityFailure: TranslationFailure? = null
        var preparationFailure: TranslationFailure? = null
        override suspend fun modelAvailability(): ModelAvailability {
            availabilityFailure?.let {
                preparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = it)
                return ModelAvailability.MISSING
            }
            return if (warms > 0) ModelAvailability.READY else ModelAvailability.MISSING
        }
        override suspend fun prepareLocal(): ModelAvailability {
            preparationFailure?.let {
                preparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = it)
                throw it
            }
            if (warms == 0) warms++
            preparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
            return ModelAvailability.READY
        }
        override suspend fun prepare() = prepareLocal()
        override suspend fun translate(pair: LanguagePair, text: String): String {
            entered.complete(Unit)
            translationError?.let {
                preparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = it as? TranslationFailure)
                throw it
            }
            if (blockTranslation) awaitCancellation()
            return text
        }
        override suspend fun awaitIdle() {
            check(closes == 0) { "Closed provider must never be drained by a stale lease" }
            drains++
            drainError?.let { throw it }
            sdkFinished.await()
        }
        override fun close() { closes++; closeError?.let { throw it } }
    }

    @Test fun synchronousFatalAvailabilityPublishesBeforeQueuedObservationIsCancelled() = runTest {
        val provider = Provider()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ provider }))
        val lease = lifetime.lease("tencent", setOf(pair))
        lease.prepareLocal()
        // A second operation synchronously copies the cached READY state before the fault.
        val fatal = TranslationFailure.RuntimeLoad(IllegalStateException("availability"))
        provider.availabilityFailure = fatal
        assertEquals(ModelAvailability.MISSING, lease.modelAvailability())
        assertEquals(TranslationPreparationPhase.FAILED, lease.preparation.value.phase)
        assertSame(fatal, lease.preparation.value.lastFailure)
        assertEquals(1, provider.closes)
        lease.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun synchronousFatalPrepareAndTranslateNeverLeaveReadyLease() = runTest {
        for (prepare in listOf(true, false)) {
            val provider = Provider()
            val lifetime = TranslationEngineLifetime(backgroundScope,
                TranslationEngineRegistry({ provider }))
            val lease = lifetime.lease("tencent", setOf(pair))
            lease.prepareLocal()
            val fatal = TranslationFailure.RuntimeLoad(IllegalStateException("fatal"))
            if (prepare) provider.preparationFailure = fatal else provider.translationError = fatal
            assertSame(fatal, runCatching {
                if (prepare) lease.prepareLocal() else lease.translate(pair, "fault")
            }.exceptionOrNull())
            assertEquals(TranslationPreparationPhase.FAILED, lease.preparation.value.phase)
            assertSame(fatal, lease.preparation.value.lastFailure)
            assertEquals(1, provider.closes)
            lease.closeAndJoin()
            lifetime.closeAndJoin()
        }
    }

    @Test fun backgroundFastResumeDrainsSdkBeforeReacquiringOnSameLease() = runTest {
        val engines = mutableListOf<Provider>()
        val registry = TranslationEngineRegistry({ Provider().also(engines::add) })
        val lifetime = TranslationEngineLifetime(backgroundScope, registry)
        val lease = lifetime.lease("tencent", setOf(pair))
        val root = lease.preparation
        lease.prepareLocal()
        val old = engines.single()
        old.blockTranslation = true
        old.sdkFinished = CompletableDeferred()
        val operation = async { lease.translate(pair, "active") }
        old.entered.await()
        lifetime.leaveForeground()
        assertEquals(TranslationPreparationPhase.MISSING, root.value.phase)
        lifetime.enterForeground()
        val resumed = async { lease.prepareLocal() }
        yield()
        assertFalse(resumed.isCompleted)
        assertEquals(1, engines.size)
        old.sdkFinished.complete(Unit)
        assertEquals(ModelAvailability.READY, resumed.await())
        assertTrue(operation.isCancelled)
        assertSame(root, lease.preparation)
        assertEquals(2, engines.size)
        assertEquals(1, old.closes)
        lease.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun memoryPressurePublishesMissingWithoutEagerReacquisition() = runTest {
        val engines = mutableListOf<Provider>()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ Provider().also(engines::add) }))
        val lease = lifetime.lease("tencent", setOf(pair))
        lease.prepareLocal()
        lifetime.trimMemory()
        assertEquals(TranslationPreparationPhase.MISSING, lease.preparation.value.phase)
        yield()
        assertEquals(1, engines.size)
        assertEquals(1, engines.single().closes)
        lease.prepareLocal()
        assertEquals(2, engines.size)
        lease.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun routeGuardAndStaleDoubleCloseCannotAffectSuccessor() = runTest {
        val engines = mutableListOf<Provider>()
        val selections = mutableListOf<Set<LanguagePair>>()
        val lifetime = TranslationEngineLifetime(backgroundScope, TranslationEngineRegistry({ selected ->
            selections.add(selected)
            Provider().also(engines::add)
        }))
        val first = lifetime.lease("tencent", setOf(pair))
        first.prepareLocal()
        assertTrue(runCatching { first.translate(pair.reverse(), "denied") }.exceptionOrNull() is IllegalArgumentException)
        first.closeAndJoin()
        val next = lifetime.lease("tencent", setOf(pair.reverse()))
        next.prepareLocal()
        first.close()
        first.closeAndJoin()
        assertTrue(runCatching { first.translate(pair, "stale") }.isFailure)
        assertEquals("live", next.translate(pair.reverse(), "live"))
        assertEquals(listOf(setOf(pair, pair.reverse())), selections)
        assertEquals(0, engines.single().closes)
        next.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun ownerReplacementWaitsForCancelledSdkDrain() = runTest {
        val engines = mutableListOf<Provider>()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ Provider().also(engines::add) }))
        val controller = RuntimeOwnerController(backgroundScope)
        val first = controller.replace { TextRuntimeOwner(TextRuntimeConfig(pair, "tencent"), lifetime.lease("tencent", setOf(pair))) }
        first.translation.prepareLocal()
        val provider = engines.single()
        provider.blockTranslation = true
        provider.sdkFinished = CompletableDeferred()
        val active = async { first.operation { first.translation.translate(pair, "active") } }
        provider.entered.await()
        var constructed = false
        val replacement = async {
            controller.replace {
                constructed = true
                TextRuntimeOwner(TextRuntimeConfig(pair, "tencent"), lifetime.lease("tencent", setOf(pair)))
            }
        }
        yield()
        assertFalse(constructed)
        assertTrue(active.isCancelled)
        provider.sdkFinished.complete(Unit)
        replacement.await().translation.prepareLocal()
        assertEquals(1, engines.size)
        controller.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun providerPairChangesInvalidateImmediatelyButSwapRetains() = runTest {
        val engines = mutableListOf<Provider>()
        val create: (Set<LanguagePair>) -> TranslationEngine = { Provider().also(engines::add) }
        val descriptor = io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine.descriptor
        val registry = TranslationEngineRegistry(listOf(
            TranslationEngineRegistry.Registration(descriptor.copy(id = "isolated-provider"), { TranslationCapability.Direct }, create),
            TranslationEngineRegistry.Registration(descriptor, { TranslationCapability.Direct }, create),
        ))
        val lifetime = TranslationEngineLifetime(backgroundScope, registry)
        val first = lifetime.lease("isolated-provider", setOf(pair))
        first.prepareLocal()
        first.closeAndJoin()
        lifetime.invalidateSelection("isolated-provider", pair.reverse())
        assertEquals(0, engines.single().closes)
        lifetime.invalidateSelection("tencent", pair)
        assertEquals(1, engines.single().closes)
        val second = lifetime.lease("tencent", setOf(pair))
        second.prepareLocal()
        second.closeAndJoin()
        val other = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)
        lifetime.invalidateSelection("tencent", other)
        assertEquals(1, engines.last().closes)
        val third = lifetime.lease("tencent", setOf(other))
        third.prepareLocal()
        third.closeAndJoin()
        lifetime.closeAndJoin()
        assertEquals(3, engines.size)
        assertTrue(engines.all { it.closes == 1 })
    }

    @Test fun failedDrainStillAttemptsCloseAndPreservesFirstFailureIdentity() = runTest {
        val provider = Provider()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ provider }))
        val lease = lifetime.lease("tencent", setOf(pair))
        lease.prepareLocal()
        val draining = IllegalStateException("actual drain")
        val closing = IllegalStateException("actual close")
        provider.drainError = draining
        provider.closeError = closing
        lifetime.trimMemory()
        yield()
        assertEquals(1, provider.closes)
        assertEquals(listOf(closing), draining.suppressed.toList())
        assertSame(draining, runCatching { lease.closeAndJoin() }.exceptionOrNull())
        assertSame(draining, runCatching { lifetime.closeAndJoin() }.exceptionOrNull())
        assertEquals(1, provider.closes)
    }

    @Test fun lateCloseAfterEvictionNeverDrainsClosedProviderOrSuccessor() = runTest {
        val engines = mutableListOf<Provider>()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ Provider().also(engines::add) }))
        val old = lifetime.lease("tencent", setOf(pair))
        old.prepareLocal()
        lifetime.leaveForeground()
        lifetime.enterForeground()
        val successor = lifetime.lease("tencent", setOf(pair.reverse()))
        successor.prepareLocal()
        val drains = engines.last().drains
        old.closeAndJoin()
        old.closeAndJoin()
        assertEquals(drains, engines.last().drains)
        assertEquals("live", successor.translate(pair.reverse(), "live"))
        successor.closeAndJoin()
        lifetime.closeAndJoin()
    }

    @Test fun fatalFailureReleasesDelegateAndCleanupPoisonRetainsExactIdentity() = runTest {
        val engines = mutableListOf<Provider>()
        val lifetime = TranslationEngineLifetime(backgroundScope,
            TranslationEngineRegistry({ Provider().also(engines::add) }))
        val lease = lifetime.lease("tencent", setOf(pair))
        lease.prepareLocal()
        val fatal = TranslationFailure.RuntimeLoad(IllegalStateException("native"))
        engines.single().translationError = fatal
        assertSame(fatal, runCatching { lease.translate(pair, "fatal") }.exceptionOrNull())
        assertEquals(1, engines.single().closes)
        lease.prepareLocal()
        assertEquals(2, engines.size)
        val cleanup = IllegalStateException("actual cleanup")
        engines.last().closeError = cleanup
        lifetime.trimMemory()
        yield()
        assertSame(cleanup, runCatching { lease.prepareLocal() }.exceptionOrNull())
        assertSame(cleanup, runCatching { lease.closeAndJoin() }.exceptionOrNull())
        assertSame(cleanup, runCatching { lifetime.closeAndJoin() }.exceptionOrNull())
    }

    @Test fun textConversationTextRetainsOnePreparedDelegate() = runTest {
        var constructions = 0
        var warms = 0
        var releases = 0
        var captureClosed = false
        var recognitionClosed = false
        var outputClosed = false
        val registry = TranslationEngineRegistry({ selected ->
            constructions++
            object : TranslationEngine {
                var ready = false
                override val preparation = MutableStateFlow(TranslationPreparationState())
                override suspend fun modelAvailability() = if (ready) ModelAvailability.READY else ModelAvailability.MISSING
                override suspend fun prepare(): ModelAvailability {
                    if (!ready) warms++
                    ready = true
                    preparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
                    return ModelAvailability.READY
                }
                override suspend fun translate(pair: LanguagePair, text: String): String {
                    require(pair in selected)
                    return text
                }
                override fun close() { releases++ }
            }
        })
        val lifetime = TranslationEngineLifetime(backgroundScope, registry)
        suspend fun lease(pairs: Set<LanguagePair>) = lifetime.lease("tencent", pairs)
        val controller = RuntimeOwnerController(backgroundScope)
        val first = controller.replace { TextRuntimeOwner(TextRuntimeConfig(pair, "tencent"), lease(setOf(pair))) }
        first.translation.prepare()
        val conversation = controller.replace {
            ConversationRuntimeOwner(
                ConversationSessionConfig(listOf(pair.source, pair.target), "tencent", SpeechRecognitionBackend.SYSTEM_OFFLINE,
                    "vad", 500, 100, SpeechOutputBackend.SYSTEM_OFFLINE),
                object : ConversationModelRepository {
                    override val models = MutableStateFlow(emptyList<SpeechModelStatus>())
                    override suspend fun install(speechOutputRequired: Boolean) = Unit
                    override suspend fun isConversationReady(speechOutputRequired: Boolean) = true
                    override fun close() = Unit
                },
                object : SpeechCaptureEngine {
                    override suspend fun start(listener: SpeechCaptureListener) { error("No microphone auto-start") }
                    override fun interruptCurrent() = Unit
                    override suspend fun stop(flush: Boolean): SpeechSegment? = null
                    override fun close() { captureClosed = true }
                },
                object : ConversationRecognitionEngine {
                    override val readinessState = MutableStateFlow(SpeechRecognitionReadiness.READY)
                    override suspend fun readiness() = readinessState.value
                    override suspend fun install() = Unit
                    override suspend fun prepare() = Unit
                    override suspend fun recognize(segment: SpeechSegment): ConversationRecognitionResult = error("Unused")
                    override fun close() { recognitionClosed = true }
                }, lease(setOf(pair, pair.reverse())),
                object : ConversationSpeechOutput {
                    override suspend fun readiness() = SpeechOutputReadiness.READY
                    override suspend fun prepare() = Unit
                    override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) = Unit
                    override fun interruptCurrent() = Unit
                    override fun throwIfReleaseFailed() = Unit
                    override fun close() { outputClosed = true }
                }, CoroutineScope(coroutineContext + SupervisorJob()), false,
            )
        }
        conversation.translation.prepare()
        assertEquals("reverse", conversation.translation.translate(pair.reverse(), "reverse"))
        val last = controller.replace { TextRuntimeOwner(TextRuntimeConfig(pair, "tencent"), lease(setOf(pair))) }
        last.translation.prepare()
        assertTrue(captureClosed && recognitionClosed && outputClosed)
        controller.release(first)
        assertEquals("still live", last.translation.translate(pair, "still live"))
        controller.closeAndJoin()
        assertEquals(1, constructions)
        assertEquals(1, warms)
        assertEquals(0, releases)
        lifetime.closeAndJoin()
        assertEquals(1, releases)
    }
}
