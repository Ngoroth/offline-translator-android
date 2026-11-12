package io.github.ngoroth.offlinetranslator.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.ModelAvailability
import io.github.ngoroth.offlinetranslator.core.PreparationFailure
import io.github.ngoroth.offlinetranslator.core.TranslationEngine
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationState
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationPhase
import io.github.ngoroth.offlinetranslator.core.TextRuntimeConfig
import io.github.ngoroth.offlinetranslator.runtime.RuntimeOwnerController
import io.github.ngoroth.offlinetranslator.runtime.TextRuntimeOwner
import io.github.ngoroth.offlinetranslator.ui.ScreenAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {
    @Test fun screenDemandCoversRealLocalPreparationAndCancellationRevokesBeforeNativeReturn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY).apply {
            localGate = CompletableDeferred()
            nonCancellableLocal = true
        }
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            assertTrue(fixture.screen.keepScreenOn)
            fixture.model.leaveForeground()
            assertFalse(fixture.screen.keepScreenOn)
            engine.localGate?.complete(ModelAvailability.READY)
            runCurrent()
            assertFalse(fixture.screen.keepScreenOn)
        } finally {
            engine.localGate?.complete(ModelAvailability.READY)
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun preparationFailureReleasesDemandWithoutClearingIndependentWork() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING).apply { preparationGate = CompletableDeferred() }
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            val other = fixture.screen.owner().acquire()
            fixture.model.prepare()
            runCurrent()
            other.close()
            assertTrue(fixture.screen.keepScreenOn)
            engine.preparationGate?.completeExceptionally(java.io.IOException("Download failed"))
            runCurrent()
            assertFalse(fixture.screen.keepScreenOn)
            assertFalse(fixture.model.state.value.operationInProgress)
        } finally {
            engine.preparationGate?.complete(ModelAvailability.MISSING)
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun inferenceDemandEndsOnDisposalAndCannotBeRevivedByStaleOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY, suspendTranslation = true)
        val fixture = createFixture(engine, dispatcher, initialInput = "source")
        try {
            runCurrent()
            assertFalse(fixture.screen.keepScreenOn)
            fixture.model.translate()
            runCurrent()
            assertTrue(fixture.screen.keepScreenOn)
            fixture.model.dispose()
            assertFalse(fixture.screen.keepScreenOn)
            engine.completeSuspendedTranslation("late result")
            runCurrent()
            fixture.model.translate()
            assertFalse(fixture.screen.keepScreenOn)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun trimDuringInitialLocalPreparationSettlesMissingAndAllowsRecovery() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val gate = CompletableDeferred<Unit>()
        var creations = 0
        var localPreparations = 0
        var preparations = 0
        val lifetime = io.github.ngoroth.offlinetranslator.runtime.TranslationEngineLifetime(backgroundScope,
            io.github.ngoroth.offlinetranslator.runtime.TranslationEngineRegistry({
                val first = ++creations == 1
                object : TranslationEngine {
                    override val preparation = kotlinx.coroutines.flow.MutableStateFlow(
                        TranslationPreparationState(TranslationPreparationPhase.MISSING))
                    override suspend fun modelAvailability() = ModelAvailability.READY
                    override suspend fun prepareLocal(): ModelAvailability {
                        localPreparations++
                        preparation.value = TranslationPreparationState(TranslationPreparationPhase.LOADING)
                        if (first) gate.await()
                        preparation.value = TranslationPreparationState(TranslationPreparationPhase.READY)
                        return ModelAvailability.READY
                    }
                    override suspend fun prepare(): ModelAvailability { preparations++; return prepareLocal() }
                    override suspend fun translate(pair: LanguagePair, text: String) = text
                    override fun close() = Unit
                }
            }))
        val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val fixture = createFixture(lifetime.lease("tencent", setOf(pair)), dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            assertTrue(fixture.model.state.value.isPreparing)
            assertEquals(TranslationPreparationPhase.LOADING, requireNotNull(fixture.model.state.value.preparation).phase)
            lifetime.trimMemory()
            runCurrent()
            assertFalse(fixture.model.state.value.operationInProgress)
            assertEquals(ModelUiStatus.MISSING, fixture.model.state.value.modelStatus)
            assertEquals(TranslationPreparationPhase.MISSING, requireNotNull(fixture.model.state.value.preparation).phase)
            assertTrue(fixture.model.state.value.canPrepare)
            assertTrue(fixture.controller.isCurrent(fixture.owner))
            assertFalse(fixture.controller.busy.value)
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(1, creations)
            assertEquals(1, localPreparations)
            assertEquals(0, preparations)
            fixture.model.retry()
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            assertEquals(2, creations)
            assertEquals(1, preparations)
            fixture.model.leaveForeground()
            lifetime.leaveForeground()
            runCurrent()
            lifetime.enterForeground()
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            assertEquals(3, creations)
            assertEquals(1, preparations)
            assertEquals("Keep source", fixture.model.state.value.input)
        } finally {
            gate.complete(Unit)
            fixture.model.disposeAndJoin()
            lifetime.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun retainedPreparationEvictionRevokesReadyWithoutAutomaticWorkAndForegroundRecovers() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            fixture.model.translate()
            runCurrent()
            engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.LOADING)
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            runCurrent()
            assertFalse(fixture.model.state.value.canTranslate)
            assertEquals("Keep source", fixture.model.state.value.input)
            assertEquals("translated", fixture.model.state.value.result)
            fixture.model.translate()
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(1, engine.translateCalls)
            assertEquals(1, engine.localPrepareCalls)
            assertEquals(0, engine.prepareCalls)
            fixture.model.leaveForeground()
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            assertEquals(2, engine.localPrepareCalls)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun retainedPreparationFailurePublishesTypedRecoveryAndStaleOwnerCannotDisableSuccessor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            val failure = io.github.ngoroth.offlinetranslator.core.TranslationFailure.Memory()
            engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            runCurrent()
            assertFalse(fixture.model.state.value.canTranslate)
            assertEquals(failure, fixture.model.state.value.failure)
            fixture.model.retry()
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            assertEquals(1, engine.prepareCalls)
            val successorEngine = FakeEngine(ModelAvailability.READY)
            val successorOwner = fixture.controller.replace { TextRuntimeOwner(fixture.owner.config, successorEngine) }
            val successor = TranslationViewModel(successorOwner, fixture.controller, initialInput = "Next source")
            try {
                runCurrent()
                val oldState = fixture.model.state.value
                engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
                runCurrent()
                assertEquals(oldState, fixture.model.state.value)
                assertTrue(successor.state.value.canTranslate)
            } finally {
                successor.disposeAndJoin()
            }
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun installedModelIsInitializedLocallyOnEntryWithoutExplicitPreparation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING)
        engine.localAvailability = ModelAvailability.READY
        val fixture = createFixture(engine, dispatcher, initialInput = "Keep this source")
        try {
            runCurrent()
            assertEquals(1, engine.localPrepareCalls)
            assertEquals(0, engine.prepareCalls)
            assertEquals(ModelUiStatus.READY, fixture.model.state.value.modelStatus)
            assertTrue(fixture.model.state.value.canTranslate)
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(1, engine.localPrepareCalls)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun localProgressBlocksAllActionsWithoutDownloadingAndPreservesInput() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING)
        engine.localGate = CompletableDeferred()
        val source = "Keep\r\nthis source🙂"
        val fixture = createFixture(engine, dispatcher, initialInput = source)
        try {
            runCurrent()
            assertEquals(ModelUiStatus.CHECKING, fixture.model.state.value.modelStatus)
            for (phase in listOf(TranslationPreparationPhase.VERIFYING, TranslationPreparationPhase.LOADING)) {
                engine.preparation.value = TranslationPreparationState(phase)
                runCurrent()
                assertEquals(phase.name, fixture.model.state.value.modelStatus.name)
                assertTrue(fixture.model.state.value.isPreparing)
                assertFalse(fixture.model.state.value.canPrepare)
                assertFalse(fixture.model.state.value.canTranslate)
                fixture.model.updateInput("blocked")
                fixture.model.translate()
                fixture.model.prepare()
                fixture.model.retry()
                fixture.model.enterForeground()
                runCurrent()
                assertEquals(source, fixture.model.state.value.input)
                assertEquals(0, engine.prepareCalls)
                assertEquals(0, engine.translateCalls)
                assertEquals(1, engine.localPrepareCalls)
            }
            engine.localGate!!.complete(ModelAvailability.READY)
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            assertFalse(fixture.model.state.value.isPreparing)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun localMissingCorruptAndErrorRequireManualRecoveryWithoutAutomaticRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (failure in listOf(null,
                    io.github.ngoroth.offlinetranslator.core.TranslationFailure.ArtifactCorrupt(),
                    io.github.ngoroth.offlinetranslator.core.TranslationFailure.RuntimeLoad())) {
                val engine = FakeEngine(ModelAvailability.MISSING)
                engine.localFailure = failure
                val fixture = createFixture(engine, dispatcher, initialInput = "Keep source")
                try {
                    runCurrent()
                    assertEquals(ModelUiStatus.MISSING, fixture.model.state.value.modelStatus)
                    assertEquals(failure, fixture.model.state.value.failure)
                    assertTrue(fixture.model.state.value.canPrepare)
                    assertEquals("Keep source", fixture.model.state.value.input)
                    fixture.model.enterForeground()
                    runCurrent()
                    assertEquals(1, engine.localPrepareCalls)
                    assertEquals(0, engine.prepareCalls)
                    engine.availability = ModelAvailability.READY
                    fixture.model.retry()
                    runCurrent()
                    assertEquals(1, engine.prepareCalls)
                    assertTrue(fixture.model.state.value.canTranslate)
                    assertEquals(null, fixture.model.state.value.failure)
                } finally {
                    fixture.model.disposeAndJoin()
                    fixture.observationJob.cancelAndJoin()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun foregroundReturnReinitializesLocallyAndRejectsCancelledTranslationResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY, suspendTranslation = true)
        val fixture = createFixture(engine, dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            fixture.model.translate()
            runCurrent()
            fixture.model.leaveForeground()
            fixture.model.enterForeground()
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(ModelUiStatus.CHECKING, fixture.model.state.value.modelStatus)
            engine.completeSuspendedTranslation("stale result")
            runCurrent()
            assertEquals("", fixture.model.state.value.result)
            assertEquals("Keep source", fixture.model.state.value.input)
            assertEquals(2, engine.localPrepareCalls)
            assertEquals(0, engine.prepareCalls)
            assertTrue(fixture.model.state.value.canTranslate)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun cancelledLocalPreparationCannotPublishAfterBackgroundOrDisposal() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING)
        engine.localGate = CompletableDeferred()
        engine.nonCancellableLocal = true
        val fixture = createFixture(engine, dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            fixture.model.leaveForeground()
            engine.localGate!!.complete(ModelAvailability.READY)
            runCurrent()
            assertEquals(ModelUiStatus.CHECKING, fixture.model.state.value.modelStatus)
            assertFalse(fixture.model.state.value.isPreparing)
            engine.localGate = CompletableDeferred()
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(2, engine.localPrepareCalls)
            val disposal = async { fixture.model.disposeAndJoin() }
            runCurrent()
            val disposed = fixture.model.state.value
            engine.localGate!!.complete(ModelAvailability.READY)
            disposal.await()
            runCurrent()
            assertEquals(disposed, fixture.model.state.value)
            assertEquals(0, engine.prepareCalls)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun localForegroundCheckPreservesCompletedResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = createFixture(FakeEngine(ModelAvailability.READY), dispatcher, initialInput = "Keep source")
        try {
            runCurrent()
            fixture.model.translate()
            runCurrent()
            fixture.model.leaveForeground()
            fixture.model.enterForeground()
            runCurrent()
            assertEquals("translated", fixture.model.state.value.result)
            assertEquals("Keep source", fixture.model.state.value.input)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun verificationAndLoadingProgressCannotEnableActionsOrReviveDisposedOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING)
        engine.preparationGate = CompletableDeferred()
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            fixture.model.updateInput("Keep\r\nthis source")
            fixture.model.prepare()
            runCurrent()
            engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING, 70, 100)
            runCurrent()
            assertEquals(70L, fixture.model.state.value.preparation?.downloadedBytes)
            assertFalse(fixture.model.state.value.canTranslate)
            for (phase in listOf(TranslationPreparationPhase.VERIFYING, TranslationPreparationPhase.LOADING)) {
                engine.preparation.value = TranslationPreparationState(phase)
                runCurrent()
                assertFalse(fixture.model.state.value.canPrepare)
                assertFalse(fixture.model.state.value.canTranslate)
            }
            engine.preparationGate!!.complete(ModelAvailability.READY)
            runCurrent()
            assertTrue(fixture.model.state.value.canTranslate)
            fixture.model.disposeAndJoin()
            val disposed = fixture.model.state.value
            engine.preparation.value = TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING, 0, 100)
            runCurrent()
            assertEquals(disposed, fixture.model.state.value)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun typedArtifactFailureRequiresExplicitRepairWithoutLosingOriginalSource() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING, prepareFailures = ArrayDeque(listOf(
            io.github.ngoroth.offlinetranslator.core.TranslationFailure.ArtifactCorrupt(),
        )))
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            val source = "Keep\r\nthis source\u0000🙂"
            fixture.model.updateInput(source)
            fixture.model.prepare()
            runCurrent()
            assertTrue(fixture.model.state.value.failure is io.github.ngoroth.offlinetranslator.core.TranslationFailure.ArtifactCorrupt)
            assertEquals(source, fixture.model.state.value.input)
            assertFalse(fixture.model.state.value.canTranslate)
            engine.availability = ModelAvailability.READY
            fixture.model.retry()
            runCurrent()
            assertEquals(2, engine.prepareCalls)
            assertTrue(fixture.model.state.value.canTranslate)
            assertEquals(null, fixture.model.state.value.failure)
        } finally {
            fixture.model.disposeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun pairCutoverCleanupWaitsActualLeaseAndAllAwaitersShareRelease() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY, suspendTranslation = true)
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            fixture.model.updateInput("Keep this mounted input")
            fixture.model.translate()
            runCurrent()
            val first = async { fixture.model.disposeAndJoin() }
            val second = async { fixture.model.disposeAndJoin() }
            runCurrent()
            fixture.model.dispose()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(0, engine.closeCalls)
            engine.completeSuspendedTranslation("late result")
            first.await()
            second.await()
            fixture.model.disposeAndJoin()
            assertEquals(1, engine.closeCalls)
            assertEquals("Keep this mounted input", fixture.model.state.value.input)
            assertEquals("", fixture.model.state.value.result)
            assertFalse(fixture.model.hasCurrentOwner)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun blankAndDoubleActionsNeverReachEngineTwice() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(availability = ModelAvailability.READY)
            val fixture = createFixture(engine, dispatcher)
            val viewModel = fixture.model
            runCurrent()
            viewModel.translate()
            assertEquals(0, engine.translateCalls)

            viewModel.updateInput("hello")
            viewModel.translate()
            viewModel.translate()
            assertEquals(0, engine.translateCalls)
            runCurrent()
            assertEquals(1, engine.translateCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun wifiFailureKeepsInputAndRetryPreparesModel() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(
                availability = ModelAvailability.MISSING,
                prepareFailures = ArrayDeque(listOf(PreparationFailure.WifiRequired())),
            )
            val fixture = createFixture(engine, dispatcher)
            val viewModel = fixture.model
            runCurrent()
            viewModel.updateInput("важный текст")
            viewModel.prepare()
            runCurrent()

            assertEquals(TranslationUiError.WIFI_REQUIRED, viewModel.state.value.error)
            assertEquals("важный текст", viewModel.state.value.input)
            engine.availability = ModelAvailability.READY
            viewModel.retry()
            runCurrent()
            assertEquals(ModelUiStatus.READY, viewModel.state.value.modelStatus)
            assertEquals(2, engine.prepareCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun translationFailureKeepsInputAndCanRetry() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(ModelAvailability.READY, translationFailures = 1)
            val fixture = createFixture(engine, dispatcher)
            val viewModel = fixture.model
            runCurrent()
            viewModel.updateInput("hello")
            viewModel.translate()
            runCurrent()
            assertEquals(TranslationUiError.TRANSLATION_FAILED, viewModel.state.value.error)
            assertEquals("hello", viewModel.state.value.input)

            viewModel.retry()
            runCurrent()
            assertEquals("translated", viewModel.state.value.result)
            assertFalse(viewModel.state.value.isTranslating)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun preparationTimeoutHasDistinctRecoverableState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(
                availability = ModelAvailability.MISSING,
                prepareFailures = ArrayDeque(listOf(PreparationFailure.PreparationTimedOut())),
            )
            val fixture = createFixture(engine, dispatcher)
            val viewModel = fixture.model
            runCurrent()

            viewModel.prepare()
            runCurrent()

            assertEquals(TranslationUiError.PREPARATION_TIMED_OUT, viewModel.state.value.error)
            assertTrue(viewModel.state.value.canPrepare)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun disposalClosesEngineOnceAndRejectsLateTranslationResult() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(
                availability = ModelAvailability.READY,
                suspendTranslation = true,
            )
            val store = ViewModelStore()
            val fixture = createFixture(engine, dispatcher)
            val viewModel = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = fixture.model as T
            })[TranslationViewModel::class.java]
            runCurrent()
            viewModel.updateInput("hello")
            viewModel.translate()
            runCurrent()
            val stateBeforeDisposal = viewModel.state.value
            assertTrue(stateBeforeDisposal.isTranslating)

            store.clear()
            store.clear()
            engine.completeSuspendedTranslation("late result")
            runCurrent()
            fixture.controller.closeAndJoin()
            assertEquals(1, engine.closeCalls)

            assertEquals(stateBeforeDisposal, viewModel.state.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun translateHoldsRuntimeGateAndRejectsSettingsSave() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val engine = FakeEngine(availability = ModelAvailability.READY, suspendTranslation = true)
            val fixture = createFixture(engine, dispatcher)
            val viewModel = fixture.model
            runCurrent()
            viewModel.updateInput("hello")
            viewModel.translate()
            runCurrent()
            assertTrue("Controller must report busy during translation", fixture.controller.busy.value)
            var savedWhileTranslating = false
            assertFalse(fixture.controller.saveIfIdle({ true }) { savedWhileTranslating = true })
            assertFalse(savedWhileTranslating)

            engine.completeSuspendedTranslation("bonjour")
            runCurrent()
            assertFalse("Controller must be idle when translation completes", fixture.controller.busy.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun availabilityCheckHoldsRuntimeGateAndRefusesSaveWithoutClosingOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val availabilityGate = CompletableDeferred<ModelAvailability>()
        engine.availabilityGate = availabilityGate
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            assertEquals(1, engine.availabilityCalls)
            assertEquals(ModelUiStatus.CHECKING, fixture.model.state.value.modelStatus)
            assertTrue(fixture.controller.busy.value)
            var committed = false
            assertFalse(fixture.controller.saveIfIdle({ false }) { committed = true })
            assertFalse(committed)
            assertEquals("Readiness checks must retain their native owner", 0, engine.closeCalls)

            availabilityGate.complete(ModelAvailability.READY)
            runCurrent()
            assertEquals(ModelUiStatus.READY, fixture.model.state.value.modelStatus)
            assertFalse(fixture.controller.busy.value)
            assertTrue(fixture.controller.saveIfIdle({ false }) { committed = true })
            assertTrue(committed)
            assertEquals(1, engine.closeCalls)
        } finally {
            availabilityGate.complete(ModelAvailability.READY)
            runCurrent()
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun textPreparationHoldsRuntimeGateAndRefusesSaveWithoutClosingOwner() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.MISSING)
        val preparationGate = CompletableDeferred<ModelAvailability>()
        engine.preparationGate = preparationGate
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            fixture.model.prepare()
            runCurrent()
            assertEquals(1, engine.prepareCalls)
            assertEquals(ModelUiStatus.DOWNLOADING, fixture.model.state.value.modelStatus)
            assertTrue(fixture.controller.busy.value)
            var committed = false
            assertFalse(fixture.controller.saveIfIdle({ false }) { committed = true })
            assertFalse(committed)
            assertEquals("Preparation must retain its native owner", 0, engine.closeCalls)

            preparationGate.complete(ModelAvailability.READY)
            runCurrent()
            assertEquals(ModelUiStatus.READY, fixture.model.state.value.modelStatus)
            assertFalse(fixture.controller.busy.value)
            assertTrue(fixture.controller.saveIfIdle({ false }) { committed = true })
            assertTrue(committed)
            assertEquals(1, engine.closeCalls)
        } finally {
            preparationGate.complete(ModelAvailability.READY)
            runCurrent()
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun displacedTextOwnerCannotPrepareOrTranslateAgainstSuccessor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val oldEngine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(oldEngine, dispatcher)
        val successor = FakeEngine(ModelAvailability.READY)
        try {
            runCurrent()
            fixture.model.updateInput("Do not route through the successor")
            fixture.controller.replace { TextRuntimeOwner(TextRuntimeConfig(LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH), "test"), successor) }
            fixture.model.translate()
            fixture.model.prepare()
            runCurrent()
            assertEquals(0, oldEngine.translateCalls)
            assertEquals(0, successor.translateCalls)
            assertEquals(0, successor.prepareCalls)
            fixture.model.disposeAndJoin()
            assertEquals(1, oldEngine.closeCalls)
            assertEquals(0, successor.closeCalls)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun oversizedEditKeepsAcceptedInputAndResultWithoutDispatching() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            fixture.model.updateInput("accepted text")
            fixture.model.translate()
            runCurrent()
            assertEquals("translated", fixture.model.state.value.result)

            fixture.model.updateInput("x".repeat(4097))
            fixture.model.translate()
            runCurrent()

            assertEquals("accepted text", fixture.model.state.value.input)
            assertEquals("translated", fixture.model.state.value.result)
            assertFalse(fixture.model.state.value.canTranslate)
            assertTrue(fixture.model.state.value.inputTooLong)
            assertEquals(null, fixture.model.state.value.error)
            assertEquals(1, engine.translateCalls)

            fixture.model.updateInput("accepted text")
            fixture.model.translate()
            runCurrent()
            assertEquals("accepted text", fixture.model.state.value.input)
            assertEquals("translated", fixture.model.state.value.result)
            assertTrue(fixture.model.state.value.inputTooLong)
            assertFalse(fixture.model.state.value.canTranslate)
            assertEquals(1, engine.translateCalls)

            fixture.model.updateInput("genuine correction")
            assertFalse(fixture.model.state.value.inputTooLong)
            assertEquals("", fixture.model.state.value.result)
            assertTrue(fixture.model.state.value.canTranslate)
            fixture.model.translate()
            runCurrent()
            assertEquals(2, engine.translateCalls)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun oversizedInitialInputIsRejectedWholeWithoutDispatching() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher, initialInput = "x".repeat(100_000))
        try {
            runCurrent()
            fixture.model.translate()
            runCurrent()

            assertEquals("", fixture.model.state.value.input)
            assertEquals("", fixture.model.state.value.result)
            assertFalse(fixture.model.state.value.canTranslate)
            assertTrue(fixture.model.state.value.inputTooLong)
            assertEquals(null, fixture.model.state.value.error)
            assertEquals(0, engine.translateCalls)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun inputLimitCountsUtf16UnitsAndValidCorrectionRestoresTranslation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            for (accepted in listOf("x".repeat(4096), "\uD83D\uDE00".repeat(2048))) {
                assertEquals(4096, accepted.length)
                fixture.model.updateInput(accepted)
                assertTrue(fixture.model.state.value.canTranslate)
                assertFalse(fixture.model.state.value.inputTooLong)
                fixture.model.translate()
                runCurrent()
                val calls = engine.translateCalls
                fixture.model.updateInput(accepted + "x")
                fixture.model.translate()
                runCurrent()
                assertEquals(accepted, fixture.model.state.value.input)
                assertEquals("translated", fixture.model.state.value.result)
                assertFalse(fixture.model.state.value.canTranslate)
                assertTrue(fixture.model.state.value.inputTooLong)
                assertEquals(calls, engine.translateCalls)
                fixture.model.updateInput("corrected")
                assertTrue(fixture.model.state.value.canTranslate)
                assertFalse(fixture.model.state.value.inputTooLong)
                fixture.model.translate()
                runCurrent()
                assertEquals(calls + 1, engine.translateCalls)
            }
            fixture.model.updateInput(" \n")
            assertFalse(fixture.model.state.value.canTranslate)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun recreatedModelRetainsAcceptedInputAndValidationUntilValidEdit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val engine = FakeEngine(ModelAvailability.READY)
        val fixture = createFixture(engine, dispatcher)
        try {
            runCurrent()
            fixture.model.updateInput("accepted before replacement")
            fixture.model.updateInput("x".repeat(4097))
            val retained = fixture.model.state.value
            fixture.model.disposeAndJoin()
            val successorEngine = FakeEngine(ModelAvailability.READY)
            val successor = fixture.controller.replace { TextRuntimeOwner(fixture.owner.config, successorEngine) }
            val recreated = TranslationViewModel(
                successor, fixture.controller,
                initialInput = retained.input, initialInputTooLong = retained.inputTooLong,
            )
            runCurrent()
            assertEquals("accepted before replacement", recreated.state.value.input)
            assertTrue(recreated.state.value.inputTooLong)
            assertFalse(recreated.state.value.canTranslate)
            recreated.translate()
            runCurrent()
            assertEquals(0, successorEngine.translateCalls)
            recreated.updateInput("corrected after replacement")
            assertFalse(recreated.state.value.inputTooLong)
            assertTrue(recreated.state.value.canTranslate)
            recreated.translate()
            runCurrent()
            assertEquals(1, successorEngine.translateCalls)
        } finally {
            fixture.controller.closeAndJoin()
            fixture.observationJob.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class Fixture(
        val controller: RuntimeOwnerController,
        val observationJob: Job,
        val owner: TextRuntimeOwner,
        val model: TranslationViewModel,
        val screen: ScreenAwake,
    )

    private suspend fun createFixture(
        engine: TranslationEngine,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        initialPair: LanguagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
        initialInput: String = "",
    ): Fixture {
        val observationJob = SupervisorJob()
        val scope = CoroutineScope(dispatcher + observationJob)
        val controller = RuntimeOwnerController(scope)
        val config = TextRuntimeConfig(initialPair, "test")
        val owner = controller.replace { TextRuntimeOwner(config, engine) }
        val screen = ScreenAwake {}.apply { setStarted(true) }
        val model = TranslationViewModel(
            owner = owner,
            owners = controller,
            initialInput = initialInput,
            screenAwake = screen,
        )
        return Fixture(controller, observationJob, owner, model, screen)
    }
}

private class FakeEngine(
    var availability: ModelAvailability,
    private val translation: String = "translated",
    private val prepareFailures: ArrayDeque<Throwable> = ArrayDeque(),
    var translationFailures: Int = 0,
    private val suspendTranslation: Boolean = false,
) : TranslationEngine {
    override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(
        if (availability == ModelAvailability.READY) TranslationPreparationPhase.READY else TranslationPreparationPhase.MISSING,
    ))
    var prepareCalls = 0
    var availabilityCalls = 0
    var availabilityGate: CompletableDeferred<ModelAvailability>? = null
    var preparationGate: CompletableDeferred<ModelAvailability>? = null
    var translateCalls = 0
    var closeCalls = 0
    private var translationContinuation: Continuation<String>? = null

    var localAvailability: ModelAvailability? = null
    var localPrepareCalls = 0
    var localGate: CompletableDeferred<ModelAvailability>? = null
    var localFailure: Throwable? = null
    var nonCancellableLocal = false

    override suspend fun prepareLocal(): ModelAvailability {
        localPrepareCalls++
        localFailure?.let { throw it }
        localGate?.let { gate ->
            return if (nonCancellableLocal) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                gate.await()
            } else gate.await()
        }
        return localAvailability ?: modelAvailability()
    }

    override suspend fun modelAvailability(): ModelAvailability {
        availabilityCalls++
        return availabilityGate?.await() ?: availability
    }

    override suspend fun prepare(): ModelAvailability {
        prepareCalls += 1
        if (prepareFailures.isNotEmpty()) throw prepareFailures.removeFirst()
        availability = preparationGate?.await() ?: availability
        return availability
    }

    override suspend fun translate(pair: LanguagePair, text: String): String {
        translateCalls += 1
        if (suspendTranslation) {
            return suspendCoroutine { continuation ->
                check(translationContinuation == null)
                translationContinuation = continuation
            }
        }
        if (translationFailures > 0) {
            translationFailures -= 1
            error("failed")
        }
        return translation
    }

    fun completeSuspendedTranslation(result: String) {
        checkNotNull(translationContinuation).also { translationContinuation = null }.resume(result)
    }

    override fun close() { closeCalls += 1 }
}
