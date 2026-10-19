package com.example.offlinetranslator.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.PreparationFailure
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TextRuntimeConfig
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import com.example.offlinetranslator.runtime.TextRuntimeOwner
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
        val model = TranslationViewModel(
            owner = owner,
            owners = controller,
            initialInput = initialInput,
        )
        return Fixture(controller, observationJob, owner, model)
    }
}

private class FakeEngine(
    var availability: ModelAvailability,
    private val translation: String = "translated",
    private val prepareFailures: ArrayDeque<Throwable> = ArrayDeque(),
    var translationFailures: Int = 0,
    private val suspendTranslation: Boolean = false,
) : TranslationEngine {
    var prepareCalls = 0
    var availabilityCalls = 0
    var availabilityGate: CompletableDeferred<ModelAvailability>? = null
    var preparationGate: CompletableDeferred<ModelAvailability>? = null
    var translateCalls = 0
    var closeCalls = 0
    private var translationContinuation: Continuation<String>? = null

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
