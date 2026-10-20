package com.example.offlinetranslator.speech

import com.example.offlinetranslator.conversation.ConversationPreparation
import com.example.offlinetranslator.conversation.ConversationViewModel
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.ConversationRuntimeOwner
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Consumer proof: only the external Android recognizer/FD boundary replaces recognition. */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidRecognitionPreparationIntegrationTest {
    @Test fun explicitPublicPreparationReturnsMissingSelectedLocaleWithoutSdkDownloadOrHeldOwnerGate() = runTest {
        withEnvironment { environment ->
            val platform = Platform(explicit = true)
            val fixture = environment.create(platform)
            assertFalse(fixture.model.ready.value)
            assertNull(fixture.model.recognitionDataInstallRequest { false })
            val first = fixture.model.recognitionDataInstallRequest { true }
            assertEquals("ru-RU", first?.language)
            assertFalse(environment.controller.busy.value)
            // A returned request is not consent completion or readiness, and does not retain the owner gate.
            assertTrue(environment.controller.saveIfIdle(retainOwner = { true }) {})
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertFalse(fixture.model.ready.value)
            assertTrue(platform.downloads.isEmpty())
            fixture.model.leaveForeground()
            runCurrent()
            assertNull(platform.packageChanged)
            platform.installed += "ru-RU"
            fixture.model.enterForeground()
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertEquals("en-US", fixture.model.recognitionDataInstallRequest { true }?.language)
            assertEquals(0, fixture.capture.starts)
            fixture.model.leaveForeground()
            runCurrent()
            platform.installed += "en-US"
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertNull(fixture.model.recognitionDataInstallRequest { true })
            assertTrue(platform.downloads.isEmpty())
            assertEquals(0, fixture.capture.starts)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
        }
    }

    @Test fun cancelledInstallerAttestationAndLateInventoryCannotLaunchOrEnableReplacedOwner() = runTest {
        withEnvironment { environment ->
            val platform = Platform(explicit = true)
            val old = environment.create(platform)
            platform.holdSupport = true
            val request = async { old.model.recognitionDataInstallRequest { true } }
            runCurrent()
            val callback = platform.supportCallbacks.single()
            old.model.leaveForeground()
            request.cancelAndJoin()
            runCurrent()
            val current = environment.create(Platform(explicit = true).apply { installed += listOf("ru-RU", "en-US") })
            callback(AndroidRecognitionSupport(listOf("ru-RU", "en-US"), emptyList(), emptyList(), emptyList()), null)
            runCurrent()
            assertTrue(request.isCancelled)
            assertFalse(old.model.ready.value)
            assertTrue(current.model.ready.value)
            assertNull(old.model.recognitionDataInstallRequest { true })
            assertTrue(platform.downloads.isEmpty())
            assertEquals(0, old.capture.starts)
            assertEquals(0, current.capture.starts)
        }
    }

    @Test fun packageReplacementStopsCaptureAndAwaitsWriterJoinBeforeReplacementOwner() = runTest {
        withEnvironment { environment ->
            val platform = Platform(explicit = true).apply {
                installed += listOf("ru-RU", "en-US")
                automaticResults = false
                writerGate = CompletableDeferred()
            }
            val old = environment.create(platform)
            old.model.start()
            runCurrent()
            old.capture.emit()
            runCurrent()
            val session = platform.started.single()
            val audio = requireNotNull(session.audio)
            assertTrue(audio.writerEntered.isCompleted)
            platform.packageChanged!!()
            runCurrent()
            assertFalse(old.capture.running)
            assertFalse(old.model.ready.value)
            assertTrue(audio.closed.isCompleted && session.destroyed)
            assertFalse(audio.writerReleased.isCompleted)
            var built = false
            val replacement = async {
                environment.controller.replace {
                    built = true
                    environment.build(Platform(explicit = true), AppSettings().languagePair, true).owner
                }
            }
            runCurrent()
            assertFalse(built)
            assertFalse(replacement.isCompleted)
            session.listener.ready()
            session.listener.language("ru-RU", 3, 0)
            session.listener.segment("Late.")
            session.listener.terminal()
            requireNotNull(platform.writerGate).complete(Unit)
            replacement.await()
            runCurrent()
            assertTrue(audio.writerReleased.isCompleted)
            assertTrue(built)
            assertTrue(old.translation.phrases.isEmpty() && old.output.spoken.isEmpty())
            assertNull(platform.packageChanged)
        }
    }

    @Test fun explicitPrepareScheduledThenForegroundInstalledDoesNotDownloadOrStartImplicitly() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { downloadOutcome = DownloadOutcome.SCHEDULED }
            val fixture = environment.create(platform, nativeReady = false)
            assertFalse(fixture.model.ready.value)
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, fixture.model.recognitionReadiness.value)
            assertTrue(platform.downloads.isEmpty())
            fixture.model.recheckReadiness()
            runCurrent()
            assertTrue(platform.downloads.isEmpty())
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(1, fixture.native.installations)
            assertEquals(1, platform.downloads.size)
            assertEquals(SpeechRecognitionReadiness.SCHEDULED, fixture.model.recognitionReadiness.value)
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            fixture.model.leaveForeground()
            runCurrent()
            platform.installed += listOf("ru-RU", "en-US")
            platform.pending.clear()
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertEquals(SpeechRecognitionReadiness.READY, fixture.model.recognitionReadiness.value)
            assertEquals(1, platform.downloads.size)
            assertEquals(0, fixture.capture.starts)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals(1, fixture.translation.phrases.size)
            assertEquals(1, fixture.output.spoken.size)
            assertEquals(1, platform.downloads.size)
            assertTrue(platform.requestPairs.all { it.toSet() == setOf("ru-RU", "en-US") })
            assertTrue(platform.audios.all { it.closed.isCompleted })
            assertTrue(fixture.model.modelStatuses.value.none { it.role == SpeechModelRole.ASR })
        }
    }

    @Test fun unsupportedPairNeverStartsOrDownloadsEvenWithExplicitPrepare() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { supported = false }
            val fixture = environment.create(platform)
            assertEquals(SpeechRecognitionReadiness.UNSUPPORTED, fixture.model.recognitionReadiness.value)
            fixture.model.start()
            fixture.model.prepareLanguages()
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertEquals(SpeechRecognitionReadiness.UNSUPPORTED, fixture.model.recognitionReadiness.value)
            assertEquals(0, fixture.capture.starts)
            assertTrue(platform.downloads.isEmpty())
            assertTrue(fixture.translation.phrases.isEmpty())
        }
    }

    @Test fun failedExplicitDownloadRemainsVisibleUntilExplicitReadinessRefresh() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { downloadOutcome = DownloadOutcome.FAILED }
            val fixture = environment.create(platform, nativeReady = false)
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, fixture.model.recognitionReadiness.value)
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertFalse(fixture.model.ready.value)
            assertEquals(1, platform.downloads.size)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            fixture.model.recheckReadiness()
            runCurrent()
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, fixture.model.recognitionReadiness.value)
            assertEquals(1, platform.downloads.size)
            assertFalse(fixture.model.ready.value)
        }
    }

    @Test fun failedPhraseSkipsTranslationAndOutputThenNextActualAdapterPhraseSucceeds() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { installed += listOf("ru-RU", "en-US"); failNextPhrase = true }
            val fixture = environment.create(platform)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            val failure = fixture.model.state.value.transcripts.single()
            assertEquals(ConversationTranscriptStatus.ERROR, failure.status)
            assertEquals(ConversationFailure.RECOGNITION_FAILED, failure.failure)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            assertTrue(fixture.capture.running)
            assertTrue(fixture.translation.phrases.isEmpty())
            assertTrue(fixture.output.spoken.isEmpty())
            fixture.capture.emit()
            runCurrent()
            assertEquals(failure, fixture.model.state.value.transcripts.first())
            val success = fixture.model.state.value.transcripts.last()
            assertEquals(ConversationTranscriptStatus.COMPLETE, success.status)
            assertEquals(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), success.direction)
            assertEquals("recognized phrase", success.original)
            assertEquals("translated phrase", success.translation)
            assertEquals(listOf("recognized phrase"), fixture.translation.phrases)
            assertEquals(listOf(LanguageCatalog.ENGLISH to "translated phrase"), fixture.output.spoken)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            assertTrue(platform.downloads.isEmpty())
        }
    }

    @Test fun explicitRuntimeErrorsBeforeAndAfterReadyFailOnlyTurnThenSeparatelyCapturedPhraseSucceeds() = runTest {
        for (code in listOf(14, 5)) for (ready in listOf(false, true)) withEnvironment { environment ->
            val platform = Platform(explicit = true).apply {
                installed += listOf("ru-RU", "en-US")
                holdSupport = true
                automaticReady = false
                automaticResults = false
            }
            val fixture = environment.create(platform)
            val support = platform.supportCallbacks.single()
            // Error14 is provisional only at the support boundary, never a readiness result.
            support(null, 14)
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertTrue(platform.started.isEmpty() && platform.downloads.isEmpty())
            support(AndroidRecognitionSupport(platform.installed.toList(), emptyList(), emptyList(), emptyList()), null)
            runCurrent()
            assertTrue(fixture.model.ready.value)
            platform.holdSupport = false
            fixture.model.start()
            runCurrent()
            val sessionsBefore = platform.sessions.size
            val requestsBefore = platform.requestPairs.size
            fixture.capture.emit()
            runCurrent()
            val rejected = platform.started.single()
            val audio = requireNotNull(rejected.audio)
            assertFalse(audio.writerEntered.isCompleted)
            if (ready) {
                rejected.listener.ready()
                runCurrent()
                assertTrue(audio.writerEntered.isCompleted && audio.writerReleased.isCompleted)
            }
            // The same code at the recognition boundary must fail immediately, even before ready.
            rejected.listener.error(code)
            runCurrent()
            val failure = fixture.model.state.value.transcripts.single()
            assertEquals(ConversationTranscriptStatus.ERROR, failure.status)
            assertEquals(ConversationFailure.RECOGNITION_FAILED, failure.failure)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            assertTrue(fixture.model.state.value.sessionActive && fixture.model.state.value.captureActive && fixture.capture.running)
            assertTrue(fixture.translation.phrases.isEmpty() && fixture.output.spoken.isEmpty())
            assertTrue(rejected.destroyed && audio.closed.isCompleted)
            assertEquals(ready, audio.writerEntered.isCompleted)
            assertEquals(ready, audio.writerReleased.isCompleted)
            assertTrue(platform.sessions.all { it.destroyed })
            assertTrue(platform.audios.all { it.closed.isCompleted })
            rejected.listener.ready()
            rejected.listener.language("ru-RU", 3, 0)
            rejected.listener.segment("late rejected phrase")
            rejected.listener.terminal()
            advanceTimeBy(AndroidConversationRecognitionEngine.REQUEST_TIMEOUT_MILLIS + 1)
            runCurrent()
            assertEquals(listOf(failure), fixture.model.state.value.transcripts)
            assertEquals(1, platform.started.size)
            assertEquals(sessionsBefore + 1, platform.sessions.size)
            assertEquals(requestsBefore + 1, platform.requestPairs.size)
            assertTrue(platform.downloads.isEmpty())
            assertTrue(fixture.translation.phrases.isEmpty() && fixture.output.spoken.isEmpty())
            platform.automaticReady = true
            platform.automaticResults = true
            fixture.capture.emit()
            runCurrent()
            val completed = fixture.model.state.value.transcripts
            assertEquals(2, completed.size)
            assertEquals(failure, completed.first())
            assertEquals(ConversationTranscriptStatus.COMPLETE, completed.last().status)
            assertEquals(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), completed.last().direction)
            assertEquals("recognized phrase", completed.last().original)
            assertEquals("translated phrase", completed.last().translation)
            assertEquals(listOf("recognized phrase"), fixture.translation.phrases)
            assertEquals(listOf(LanguageCatalog.ENGLISH to "translated phrase"), fixture.output.spoken)
            assertEquals(2, platform.started.size)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            assertTrue(fixture.model.state.value.sessionActive && fixture.model.state.value.captureActive && fixture.capture.running)
            assertTrue(platform.downloads.isEmpty())
            assertTrue(platform.sessions.all { it.destroyed })
            assertTrue(platform.audios.all { it.closed.isCompleted })
            assertTrue(requireNotNull(platform.started.last().audio).writerReleased.isCompleted)
        }
    }

    @Test fun pairReplacementDisposesPendingDownloadAndIgnoresItsLateCallbacks() = runTest {
        withEnvironment { environment ->
            val oldPlatform = Platform().apply { downloadOutcome = DownloadOutcome.HELD }
            val old = environment.create(oldPlatform)
            old.model.prepareLanguages()
            runCurrent()
            val callback = oldPlatform.downloads.single().listener
            val oldState = old.recognition.readinessState.value
            old.model.disposeAndJoin()
            val platform = Platform().apply { installed += listOf("fr-FR", "es-ES") }
            val current = environment.create(platform, pair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH))
            assertTrue(current.model.ready.value)
            callback.progress()
            callback.success()
            callback.error(1)
            runCurrent()
            assertEquals(oldState, old.recognition.readinessState.value)
            assertFalse(old.model.ready.value)
            assertTrue(current.model.ready.value)
            assertSame(current.owner, environment.currentOwner)
            old.model.start()
            old.model.enterForeground()
            current.model.start()
            runCurrent()
            assertEquals(0, old.capture.starts)
            assertEquals(1, current.capture.starts)
            current.capture.emit()
            runCurrent()
            assertEquals(LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH), current.model.state.value.transcripts.single().direction)
            assertTrue(platform.requestPairs.all { it.toSet() == setOf("fr-FR", "es-ES") })
            assertTrue(platform.downloads.isEmpty())
            assertTrue(oldPlatform.sessions.all { it.destroyed })
            assertTrue(oldPlatform.audios.all { it.closed.isCompleted })
        }
    }

    @Test fun stopAndDisposalAwaitBlockedAudioWriterBeforeReplacementBuildsNextOwner() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply {
                installed += listOf("ru-RU", "en-US")
                automaticResults = false
                writerGate = CompletableDeferred()
            }
            val fixture = environment.create(platform)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            val active = platform.started.single()
            val audio = requireNotNull(active.audio)
            assertTrue(audio.writerEntered.isCompleted)
            fixture.model.stop()
            runCurrent()
            assertTrue(audio.closed.isCompleted)
            assertFalse(audio.writerReleased.isCompleted)
            assertTrue(active.destroyed)
            fixture.model.dispose()
            var built = false
            val replacement = async {
                environment.controller.replace {
                    built = true
                    environment.build(Platform().apply { installed += listOf("fr-FR", "es-ES") },
                        LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH), true).owner
                }
            }
            runCurrent()
            assertFalse(built)
            assertFalse(replacement.isCompleted)
            active.listener.language("en-US", 3, 1)
            active.listener.segment("late phrase")
            active.listener.terminal()
            requireNotNull(platform.writerGate).complete(Unit)
            replacement.await()
            fixture.model.disposeAndJoin()
            runCurrent()
            assertTrue(audio.writerReleased.isCompleted)
            assertTrue(built)
            assertEquals(0, fixture.translation.phrases.size)
            assertTrue(fixture.output.spoken.isEmpty())
            assertFalse(fixture.model.state.value.sessionActive)
        }
    }

    @Test fun backgroundReleasesHeldSupportAndRejectsLateReadinessBeforeFreshForegroundCheck() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { holdSupport = true }
            val fixture = environment.create(platform)
            val held = platform.sessions.single()
            val callback = platform.supportCallbacks.single()
            fixture.model.leaveForeground()
            runCurrent()
            assertTrue(held.destroyed && platform.audios.single().closed.isCompleted)
            callback(AndroidRecognitionSupport(listOf("ru-RU", "en-US"), emptyList(), emptyList(), emptyList()), null)
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertTrue(platform.downloads.isEmpty())
            assertEquals(0, fixture.capture.starts)
            platform.holdSupport = false
            platform.installed += listOf("ru-RU", "en-US")
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertTrue(platform.downloads.isEmpty())
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun backgroundRevokesHeldDownloadAndForegroundChecksWithoutImplicitNextDownload() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply { downloadOutcome = DownloadOutcome.HELD }
            val fixture = environment.create(platform, nativeReady = false)
            fixture.model.prepareLanguages()
            runCurrent()
            val held = platform.sessions.last()
            val completion = platform.downloads.single().listener
            fixture.model.leaveForeground()
            runCurrent()
            assertTrue(held.destroyed && platform.audios.last().closed.isCompleted)
            completion.success()
            completion.progress()
            completion.scheduled()
            runCurrent()
            assertEquals(1, platform.downloads.size)
            assertFalse(fixture.model.ready.value)
            assertEquals(0, fixture.capture.starts)
            platform.installed += listOf("ru-RU", "en-US")
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertEquals(SpeechRecognitionReadiness.READY, fixture.model.recognitionReadiness.value)
            assertEquals(1, platform.downloads.size)
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun explicitDownloadCanAwaitSlowConsentAndProviderBeyondRecognitionBudgetWithoutEnablingStart() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply {
                installed += "en-US"
                downloadOutcome = DownloadOutcome.HELD
            }
            val fixture = environment.create(platform, nativeReady = false)
            fixture.model.prepareLanguages()
            runCurrent()
            val completion = platform.downloads.single().listener
            val downloading = platform.sessions.last()
            advanceTimeBy(40_001)
            runCurrent()
            completion.progress()
            runCurrent()
            assertEquals(SpeechRecognitionReadiness.DOWNLOADING, fixture.model.recognitionReadiness.value)
            assertEquals(ConversationPreparation.INSTALLING, fixture.model.preparation.value)
            assertFalse(downloading.destroyed)
            assertTrue(fixture.native.ready)
            assertFalse(fixture.model.ready.value)
            fixture.model.start()
            runCurrent()
            assertFalse(fixture.model.state.value.sessionActive)
            assertEquals(0, fixture.capture.starts)
            platform.installed += "ru-RU"
            completion.success()
            runCurrent()
            assertTrue(downloading.destroyed)
            assertEquals(SpeechRecognitionReadiness.READY, fixture.model.recognitionReadiness.value)
            assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
            assertTrue(fixture.model.ready.value)
            assertEquals(1, platform.downloads.size)
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun queuedSdkCancellationMustDispatchBeforeFreshSelectedDownloadCanBecomeReady() = runTest {
        for (awaitDispatch in listOf(false, true)) withEnvironment { environment ->
            val platform = Platform().apply {
                installed += "en-US"
                queuedScope = backgroundScope
                awaitQueuedDispatch = awaitDispatch
            }
            val fixture = environment.create(platform, nativeReady = false)
            fixture.model.prepareLanguages()
            runCurrent()
            if (awaitDispatch) {
                assertEquals(SpeechRecognitionReadiness.READY, fixture.model.recognitionReadiness.value)
                assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
                assertTrue(fixture.model.ready.value)
            } else {
                assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, fixture.model.recognitionReadiness.value)
                assertEquals(11, fixture.recognition.errorCode)
                assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
                assertFalse(fixture.model.ready.value)
            }
            assertEquals(1, platform.downloads.size)
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun localRecognitionDeadlineFailsOnlyTurnAndKeepsCaptureUsableForNextTurn() = runTest {
        withEnvironment { environment ->
            val platform = Platform().apply {
                installed += listOf("ru-RU", "en-US")
                automaticResults = false
            }
            val fixture = environment.create(platform)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            val stalled = platform.started.single()
            advanceTimeBy(AndroidConversationRecognitionEngine.REQUEST_TIMEOUT_MILLIS)
            runCurrent()
            val failed = fixture.model.state.value
            assertEquals(ConversationTranscriptStatus.ERROR, failed.transcripts.single().status)
            assertEquals(ConversationFailure.RECOGNITION_FAILED, failed.transcripts.single().failure)
            assertEquals(ConversationPhase.LISTENING, failed.phase)
            assertTrue(failed.sessionActive && failed.captureActive && fixture.capture.running)
            assertTrue(stalled.destroyed)
            assertTrue(requireNotNull(stalled.audio).closed.isCompleted)
            assertTrue(requireNotNull(stalled.audio).writerReleased.isCompleted)
            assertTrue(fixture.translation.phrases.isEmpty() && fixture.output.spoken.isEmpty())
            stalled.listener.language("ru-RU", 3, 1)
            stalled.listener.segment("late")
            stalled.listener.terminal()
            platform.automaticResults = true
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.last().status)
            assertEquals(1, fixture.translation.phrases.size)
            assertEquals(1, fixture.output.spoken.size)
            assertTrue(fixture.model.state.value.sessionActive && fixture.capture.running)
        }
    }

    @Test fun historicalHistogramAndTerminalDisagreementFailsWithoutMtThenNextConvergedTurnCompletes() = runTest {
        for (pair in listOf(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
            LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN))) withEnvironment { environment ->
            val platform = Platform().apply {
                installed += listOf("ru-RU", "en-US")
                automaticResults = false
            }
            val fixture = environment.create(platform, pair)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            val rejected = platform.started.single()
            rejected.listener.segment("A primary result cannot choose its own source.")
            repeat(11) { rejected.listener.language("en-US", 1, 0) }
            repeat(15) { rejected.listener.language("ru-RU", 1, 0) }
            repeat(3) { rejected.listener.language("en-US", 1, 1) }
            rejected.listener.terminal()
            runCurrent()
            val failed = fixture.model.state.value
            assertEquals(ConversationTranscriptStatus.ERROR, failed.transcripts.single().status)
            assertEquals(ConversationFailure.RECOGNITION_FAILED, failed.transcripts.single().failure)
            assertEquals(ConversationPhase.LISTENING, failed.phase)
            assertTrue(failed.sessionActive && failed.captureActive && fixture.capture.running)
            assertTrue(fixture.translation.phrases.isEmpty() && fixture.output.spoken.isEmpty())
            assertTrue(rejected.destroyed && requireNotNull(rejected.audio).writerReleased.isCompleted)
            fixture.capture.emit()
            runCurrent()
            val recovered = platform.started.last()
            recovered.listener.language("en-US", 1, 0)
            recovered.listener.segment("Иди сюда!")
            recovered.listener.language("ru-RU", 1, 1)
            recovered.listener.language("ru-BY", 1, 0)
            recovered.listener.terminal()
            rejected.listener.language("en-US", 3, 3)
            runCurrent()
            val completed = fixture.model.state.value
            assertEquals(ConversationTranscriptStatus.COMPLETE, completed.transcripts.last().status)
            assertEquals(LanguageCatalog.RUSSIAN, completed.transcripts.last().direction?.source)
            assertEquals(listOf("Иди сюда!"), fixture.translation.phrases)
            assertEquals(listOf(LanguageCatalog.ENGLISH to "translated phrase"), fixture.output.spoken)
            assertEquals(ConversationPhase.LISTENING, completed.phase)
            assertTrue(completed.sessionActive && completed.captureActive && fixture.capture.running)
        }
    }

    private suspend fun TestScope.withEnvironment(action: suspend TestScope.(Environment) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val environment = Environment(dispatcher, this)
        try { action(environment) } finally {
            environment.fixtures.forEach { fixture -> fixture.platform.writerGate?.complete(Unit) }
            environment.fixtures.forEach { it.dispose() }
            environment.controller.closeAndJoin()
            environment.observation.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class Environment(private val dispatcher: CoroutineDispatcher, private val scope: TestScope) {
        val observation = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(observation + dispatcher))
        val fixtures = mutableListOf<Fixture>()
        var currentOwner: ConversationRuntimeOwner? = null
        suspend fun create(platform: Platform, pair: LanguagePair = AppSettings().languagePair, nativeReady: Boolean = true): Fixture {
            lateinit var fixture: Fixture
            controller.replace { build(platform, pair, nativeReady).also { fixture = it }.owner }
            fixture.attach(controller)
            currentOwner = fixture.owner
            scope.runCurrent()
            return fixture
        }
        fun build(platform: Platform, pair: LanguagePair, nativeReady: Boolean): Fixture {
            val config = AppSettings(languagePair = pair,
                speechRecognitionService = if (platform.explicit) AndroidRecognitionServices.GOOGLE_COMPONENT else null).conversationConfig()
            val native = NativeResources(nativeReady)
            val capture = Capture()
            val translation = Translation()
            val output = Output()
            val recognition = AndroidConversationRecognitionEngine(config, platform, dispatcher, dispatcher)
            val owner = ConversationRuntimeOwner(config, native, capture, recognition, translation, output,
                CoroutineScope(SupervisorJob() + dispatcher))
            return Fixture(platform, native, capture, translation, output, recognition, owner).also { fixtures += it }
        }
    }

    private class Fixture(val platform: Platform, val native: NativeResources, val capture: Capture,
        val translation: Translation, val output: Output, val recognition: AndroidConversationRecognitionEngine,
        val owner: ConversationRuntimeOwner) {
        private var attached: ConversationViewModel? = null
        val model: ConversationViewModel get() = requireNotNull(attached)
        fun attach(controller: RuntimeOwnerController) { attached = ConversationViewModel(owner, controller) }
        suspend fun dispose() { if (attached != null) model.disposeAndJoin() else owner.closeAndJoin().getOrThrow() }
    }

    private enum class DownloadOutcome { SCHEDULED, FAILED, HELD, SUCCESS }
    private data class Download(val languages: List<String>, val listener: AndroidModelDownloadListener)
    private class Platform(val explicit: Boolean = false) : AndroidSpeechRecognizerClient {
        var packageChanged: (() -> Unit)? = null
        override fun serviceStatus() = AndroidRecognitionServiceStatus(SpeechRecognitionReadiness.READY,
            if (explicit) AndroidRecognitionServiceIdentity(AndroidRecognitionServices.GOOGLE_COMPONENT,
                210683481L, 1, AndroidRecognitionServices.GOOGLE_SIGNER) else null)
        override fun observePackage(onChanged: () -> Unit) { packageChanged = onChanged }
        override fun stopObservingPackage() { packageChanged = null }
        var supported = true
        var holdSupport = false
        val supportCallbacks = mutableListOf<(AndroidRecognitionSupport?, Int?) -> Unit>()
        val installed = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        var downloadOutcome = DownloadOutcome.SUCCESS
        var failNextPhrase = false
        var automaticReady = true
        var automaticResults = true
        var writerGate: CompletableDeferred<Unit>? = null
        var queuedScope: CoroutineScope? = null
        var awaitQueuedDispatch = true
        var serviceBound = false
        val queuedSdkActions = ArrayDeque<() -> Unit>()
        fun dispatchSdkActions() {
            while (queuedSdkActions.isNotEmpty()) queuedSdkActions.removeFirst().invoke()
        }
        val requestPairs = mutableListOf<List<String>>()
        val downloads = mutableListOf<Download>()
        val sessions = mutableListOf<Session>()
        val started = mutableListOf<Session>()
        val audios = mutableListOf<Audio>()
        override fun permissionGranted() = true
        override fun openAudio(): AndroidRecognitionAudio = Audio(this).also { audios += it }
        override suspend fun create(listener: AndroidRecognitionListener): AndroidRecognitionSession =
            Session(this, listener).also { sessions += it; serviceBound = true }
    }

    private class Session(private val platform: Platform, val listener: AndroidRecognitionListener) : AndroidRecognitionSession {
        var destroyed = false
        var audio: Audio? = null
        override fun support(languages: List<String>, audio: AndroidRecognitionAudio, callback: (AndroidRecognitionSupport?, Int?) -> Unit) {
            platform.requestPairs += languages.toList()
            if (platform.holdSupport) { platform.supportCallbacks += callback; return }
            callback(AndroidRecognitionSupport(platform.installed.toList(), platform.pending.toList(),
                if (platform.supported) languages else emptyList(), emptyList()), null)
        }
        override fun download(languages: List<String>, audio: AndroidRecognitionAudio, listener: AndroidModelDownloadListener) {
            platform.requestPairs += languages.toList()
            platform.downloads += Download(languages.toList(), listener)
            fun deliver() {
                when (platform.downloadOutcome) {
                    DownloadOutcome.SCHEDULED -> { platform.pending += languages; listener.scheduled() }
                    DownloadOutcome.FAILED -> listener.error(1)
                    DownloadOutcome.HELD -> listener.progress()
                    DownloadOutcome.SUCCESS -> { platform.installed += languages.first(); listener.success() }
                }
            }
            val queue = platform.queuedScope
            if (queue == null) deliver() else {
                platform.queuedSdkActions += { if (platform.serviceBound) deliver() else listener.error(11) }
                queue.launch { platform.dispatchSdkActions() }
            }
        }
        override fun start(languages: List<String>, audio: AndroidRecognitionAudio) {
            platform.requestPairs += languages.toList()
            this.audio = audio as Audio
            platform.started += this
            if (platform.automaticReady) listener.ready()
            if (!platform.automaticResults) return
            listener.segment("recognized phrase")
            if (platform.failNextPhrase) {
                platform.failNextPhrase = false
                listener.language(languages.first(), 0, 0)
            } else listener.language(languages.first(), 3, 0)
            listener.terminal()
        }
        override fun cancel() {
            if (platform.queuedScope != null) platform.queuedSdkActions += { platform.serviceBound = false }
        }
        override suspend fun destroy() {
            check(!destroyed)
            val queue = platform.queuedScope
            if (queue == null) { destroyed = true; return }
            platform.queuedSdkActions += { platform.serviceBound = false; destroyed = true }
            if (platform.awaitQueuedDispatch) {
                val dispatched = CompletableDeferred<Unit>()
                platform.queuedSdkActions += { dispatched.complete(Unit) }
                queue.launch { platform.dispatchSdkActions() }
                dispatched.await()
            }
        }
    }

    private class Audio(private val platform: Platform) : AndroidRecognitionAudio {
        val closed = CompletableDeferred<Unit>()
        val writerEntered = CompletableDeferred<Unit>()
        val writerReleased = CompletableDeferred<Unit>()
        override suspend fun write(pcm: ShortArray) {
            writerEntered.complete(Unit)
            assertArrayEquals(shortArrayOf(1, 2, 3), pcm)
            try { platform.writerGate?.let { gate -> withContext(NonCancellable) { gate.await() } } }
            finally { writerReleased.complete(Unit) }
        }
        override fun close() { closed.complete(Unit) }
    }

    private class NativeResources(var ready: Boolean) : ConversationModelRepository {
        var installations = 0
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install() { installations++; ready = true }
        override suspend fun isConversationReady() = ready
        override fun close() = Unit
    }
    private class Capture : SpeechCaptureEngine {
        var starts = 0
        var running = false
        private var listener: SpeechCaptureListener? = null
        override suspend fun start(listener: SpeechCaptureListener) { this.listener = listener; starts++; running = true }
        fun emit() { check(running); requireNotNull(listener).onSegment(SpeechSegment.fromPcm(shortArrayOf(1, 2, 3))) }
        override fun interruptCurrent() { running = false }
        override suspend fun stop(flush: Boolean): SpeechSegment? { running = false; return null }
        override fun close() { running = false }
    }
    private class Translation : TranslationEngine {
        val phrases = mutableListOf<String>()
        override suspend fun modelAvailability() = ModelAvailability.READY
        override suspend fun prepare() = ModelAvailability.READY
        override suspend fun translate(pair: LanguagePair, text: String): String { phrases += text; return "translated phrase" }
        override fun close() = Unit
    }
    private class Output : ConversationSpeechOutput {
        val spoken = mutableListOf<Pair<LanguageId, String>>()
        override suspend fun readiness() = SpeechOutputReadiness.READY
        override suspend fun prepare() = Unit
        override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
            spoken += language to text
            onPlaybackStarted(SpeechPlaybackStart(null, false))
        }
        override fun interruptCurrent() = Unit
        override fun close() = Unit
    }
}
