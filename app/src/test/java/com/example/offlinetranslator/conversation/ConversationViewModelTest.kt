package com.example.offlinetranslator.conversation

import com.example.offlinetranslator.core.ConversationFailure
import com.example.offlinetranslator.core.ConversationModelRepository
import com.example.offlinetranslator.core.ConversationPhase
import com.example.offlinetranslator.core.ConversationRecognitionEngine
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.ConversationSpeechOutput
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.RecognizedUtterance
import com.example.offlinetranslator.core.SpeechCaptureEngine
import com.example.offlinetranslator.core.SpeechCaptureListener
import com.example.offlinetranslator.core.SpeechModelStatus
import com.example.offlinetranslator.core.SpeechPlaybackStart
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.ConversationTranscriptStatus
import com.example.offlinetranslator.core.TranslationPreparationState
import com.example.offlinetranslator.core.TranslationPreparationPhase
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.runtime.ConversationRuntimeOwner
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @Test fun stopRevokesPendingMutePersistenceWithoutHidingTheUnsavedChoiceOrUndoingMute() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.preference.gate = CompletableDeferred()
            fixture.model.toggleSpeechOutput()
            runCurrent()
            fixture.model.stop()
            runCurrent()
            fixture.preference.gate!!.complete(Unit)
            runCurrent()
            assertTrue(fixture.preference.saved)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertTrue(fixture.model.speechOutputSaveError.value)
            assertFalse(fixture.model.state.value.sessionActive)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertTrue(fixture.model.state.value.captureActive)
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertTrue(fixture.output.spoken.isEmpty())
        }
    }

    @Test fun voiceLostAtEnableCommitKeepsActualNonfatalReasonAndSavedOffChoice() = runTest {
        withModel(configure = { it.owner.pipeline.disableSpeechOutput(); it.preference.saved = false }) { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.preference.unavailable = SpeechOutputReadiness.ENGINE_UNAVAILABLE
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, fixture.model.outputReadiness.value)
            assertTrue(fixture.model.speechOutputUnavailable.value)
            assertFalse(fixture.model.speechOutputSaveError.value)
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun savedOffMissingOutputStartsTextOnlyAndUnavailableEnableIsNonfatal() = runTest {
        withModel(configure = {
            it.owner.pipeline.disableSpeechOutput()
            it.preference.saved = false
            it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
        }) { fixture ->
            assertTrue(fixture.model.ready.value)
            assertEquals(null, fixture.model.outputReadiness.value)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertTrue(fixture.model.state.value.captureActive)
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertTrue(fixture.model.speechOutputUnavailable.value)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.model.outputReadiness.value)
            assertTrue(fixture.model.state.value.sessionActive)
            fixture.capture.emit()
            runCurrent()
            assertTrue(fixture.model.state.value.transcripts.all { it.status == ConversationTranscriptStatus.COMPLETE })
            assertTrue(fixture.output.spoken.isEmpty())
            assertEquals(0, fixture.models.installs)
        }
    }

    @Test fun missingPiperVoiceRecoveryPreparesOnlyAfterStopAndRequiresExplicitEnable() = runTest {
        withModel(
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechOutputEnabled = false,
            configure = {
                it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                it.models.output = it.output
            },
        ) { fixture ->
            assertTrue(fixture.model.ready.value)
            assertTrue(fixture.owner.models.isConversationReady(speechOutputRequired = false))
            assertFalse(fixture.owner.models.isConversationReady(speechOutputRequired = true))
            fixture.model.start()
            runCurrent()
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertTrue(fixture.model.speechOutputUnavailable.value)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.model.outputReadiness.value)
            assertEquals(null, fixture.model.speechOutputConfigurationProblem.value)
            assertFalse(fixture.model.speechOutputSaveError.value)
            assertEquals(0, fixture.models.installs)

            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(0, fixture.models.installs)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.output.readiness)
            assertTrue(fixture.model.state.value.sessionActive)
            assertTrue(fixture.model.state.value.captureActive)
            fixture.capture.emit()
            runCurrent()
            val silent = fixture.model.state.value.transcripts.single()
            assertEquals("original", silent.original)
            assertEquals("translation", silent.translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, silent.status)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.captureActive)

            fixture.model.stop()
            runCurrent()
            assertFalse(fixture.model.state.value.sessionActive)
            assertFalse(fixture.model.state.value.captureActive)
            assertFalse(fixture.controller.busy.value)
            fixture.model.prepareLanguages()
            runCurrent()
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertEquals(
                "Explicit idle recovery must prepare the missing Piper voice, not just silent-session resources",
                SpeechOutputReadiness.READY,
                fixture.output.readiness,
            )
            assertTrue(fixture.owner.models.isConversationReady(speechOutputRequired = true))
            assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
            assertTrue(fixture.model.ready.value)
            assertTrue(fixture.output.spoken.isEmpty())

            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            val preparedButSilent = fixture.model.state.value.transcripts.single()
            assertEquals("translation", preparedButSilent.translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, preparedButSilent.status)
            assertTrue(fixture.output.spoken.isEmpty())
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertTrue(fixture.model.speechOutputEnabled.value)
            assertTrue(fixture.preference.saved)
            assertFalse(fixture.model.speechOutputUnavailable.value)
            assertEquals(SpeechOutputReadiness.READY, fixture.model.outputReadiness.value)
            assertEquals(preparedButSilent, fixture.model.state.value.transcripts.single())
            assertTrue(fixture.output.spoken.isEmpty())
            fixture.capture.emit()
            runCurrent()
            assertEquals(listOf("translation"), fixture.output.spoken)
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.last().status)
            assertTrue(fixture.model.state.value.sessionActive)
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun ordinarySilentPiperPreparationDoesNotInstallMissingVoices() = runTest {
        withModel(
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechOutputEnabled = false,
            configure = {
                it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                it.models.output = it.output
            },
        ) { fixture ->
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
            assertTrue(fixture.model.ready.value)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.output.readiness)
            assertFalse(fixture.owner.models.isConversationReady(speechOutputRequired = true))
            assertEquals(null, fixture.model.outputReadiness.value)
            assertFalse(fixture.model.speechOutputUnavailable.value)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun failedPiperRecoveryRetainsMissingVoiceAndSilentStartWithoutSavingEnable() = runTest {
        withModel(
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechOutputEnabled = false,
            configure = {
                it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                it.models.output = it.output
            },
        ) { fixture ->
            fixture.model.toggleSpeechOutput()
            runCurrent()
            fixture.models.installFailure = true
            val installationGate = CompletableDeferred<Unit>()
            fixture.models.installationGate = installationGate
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.INSTALLING, fixture.model.preparation.value)
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertFalse(fixture.model.speechOutputChanging.value)
            assertFalse(fixture.preference.saved)
            installationGate.complete(Unit)
            runCurrent()
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.model.outputReadiness.value)
            assertTrue(fixture.model.speechOutputUnavailable.value)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertFalse(fixture.model.speechOutputSaveError.value)
            assertTrue(fixture.model.ready.value)
            assertFalse(fixture.owner.models.isConversationReady(speechOutputRequired = true))
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.sessionActive)
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun backgroundCancelsPiperRecoveryWithoutEnablingOrBlockingLaterSilentCapture() = runTest {
        withModel(
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechOutputEnabled = false,
            configure = {
                it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                it.models.output = it.output
            },
        ) { fixture ->
            fixture.model.toggleSpeechOutput()
            runCurrent()
            val installationGate = CompletableDeferred<Unit>()
            fixture.models.installationGate = installationGate
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.INSTALLING, fixture.model.preparation.value)
            fixture.model.leaveForeground()
            runCurrent()
            installationGate.complete(Unit)
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.output.readiness)
            assertFalse(fixture.model.state.value.captureActive)
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertTrue(fixture.model.speechOutputUnavailable.value)
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun muteIsImmediateDespiteBlockedFailedSaveAndPreservesNativeBarrierAndText() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.output.playbackGate = CompletableDeferred()
            fixture.capture.emit()
            runCurrent()
            fixture.preference.gate = CompletableDeferred()
            fixture.preference.failure = java.io.IOException("Preference disk full")
            fixture.model.toggleSpeechOutput()
            assertFalse(fixture.model.speechOutputEnabled.value)
            runCurrent()
            assertFalse(fixture.model.state.value.captureActive)
            fixture.output.playbackGate!!.complete(Unit)
            runCurrent()
            assertTrue(fixture.model.state.value.captureActive)
            assertEquals("translation", fixture.model.state.value.transcripts.single().translation)
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.single().status)
            fixture.preference.gate!!.complete(Unit)
            runCurrent()
            assertTrue(fixture.model.speechOutputSaveError.value)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertTrue(fixture.model.state.value.sessionActive)
            fixture.capture.emit()
            runCurrent()
            assertEquals(2, fixture.model.state.value.transcripts.size)
            assertEquals(1, fixture.output.spoken.size)
        }
    }

    @Test fun enableWarmUpLeavesCompletedTurnsSilentAndDoesNotHoldOwnerGate() = runTest {
        withModel(configure = { it.owner.pipeline.disableSpeechOutput(); it.preference.saved = false }) { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.output.prepareGate = CompletableDeferred()
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertTrue(fixture.model.speechOutputChanging.value)
            assertFalse(fixture.preference.saved)
            assertTrue(fixture.controller.saveSpeechOutput(fixture.owner, { true }) { true })
            fixture.capture.emit()
            runCurrent()
            val silent = fixture.model.state.value.transcripts.single()
            assertEquals(ConversationTranscriptStatus.COMPLETE, silent.status)
            assertTrue(fixture.output.spoken.isEmpty())
            fixture.output.prepareGate!!.complete(Unit)
            runCurrent()
            assertTrue(fixture.model.speechOutputEnabled.value)
            assertTrue(fixture.preference.saved)
            assertEquals(silent, fixture.model.state.value.transcripts.single())
            fixture.capture.emit()
            runCurrent()
            assertEquals(listOf("translation"), fixture.output.spoken)
            assertTrue(fixture.model.state.value.captureActive)
        }
    }

    @Test fun failedEnableSaveAndBackgroundDuringNativeWarmUpCannotResurrectOutput() = runTest {
        withModel(configure = { it.owner.pipeline.disableSpeechOutput(); it.preference.saved = false }) { fixture ->
            fixture.preference.failEnable = true
            fixture.model.toggleSpeechOutput()
            runCurrent()
            assertTrue(fixture.model.speechOutputSaveError.value)
            assertFalse(fixture.model.speechOutputUnavailable.value)
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            fixture.preference.failEnable = false
            fixture.output.prepareGate = CompletableDeferred()
            fixture.output.ignorePrepareCancellation = true
            fixture.model.toggleSpeechOutput()
            runCurrent()
            fixture.model.leaveForeground()
            fixture.output.prepareGate!!.complete(Unit)
            runCurrent()
            assertFalse(fixture.model.speechOutputEnabled.value)
            assertFalse(fixture.preference.saved)
            assertFalse(fixture.model.state.value.sessionActive)
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun translationLimitKeepsRecognizedSourceAndSkipsPlaybackWithoutStoppingNextTurn() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.translation.failure = com.example.offlinetranslator.core.TranslationFailure.TokenLimit()
            fixture.capture.emit()
            runCurrent()
            val row = fixture.model.state.value.transcripts.last()
            assertEquals("original", row.original)
            assertTrue(row.translationFailure is com.example.offlinetranslator.core.TranslationFailure.TokenLimit)
            assertEquals(ConversationTranscriptStatus.ERROR, row.status)
            assertEquals(null, row.translation)
            assertTrue(fixture.output.spoken.isEmpty())
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.model.state.value.transcripts.last().status)
        }
    }


    @Test fun scheduledRecognitionRequiresExplicitForegroundRecheckAndNeverStartsOrDownloadsImplicitly() = runTest {
        withModel(configure = { it.recognition.readinessState.value = SpeechRecognitionReadiness.MISSING_LOCAL_MODEL }) { fixture ->
            assertFalse(fixture.model.ready.value)
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, fixture.model.recognitionReadiness.value)
            assertEquals(0, fixture.recognition.installs)
            fixture.recognition.installedReadiness = SpeechRecognitionReadiness.SCHEDULED
            fixture.recognition.checkedReadiness = SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
            val checksBeforePrepare = fixture.recognition.checks
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(1, fixture.models.installs)
            assertEquals(1, fixture.recognition.installs)
            assertEquals(SpeechRecognitionReadiness.SCHEDULED, fixture.model.recognitionReadiness.value)
            assertEquals("Preparation must preserve scheduled download state until explicit recheck",
                checksBeforePrepare, fixture.recognition.checks)
            assertFalse(fixture.model.ready.value)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            fixture.model.leaveForeground()
            runCurrent()
            fixture.recognition.checkedReadiness = SpeechRecognitionReadiness.READY
            fixture.model.enterForeground()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertEquals(1, fixture.recognition.installs)
            assertEquals(0, fixture.capture.starts)
            fixture.model.start()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
        }
    }

    @Test fun unsupportedDownloadFailureAndLateReadinessCannotEnableStart() = runTest {
        withModel { fixture ->
            fixture.recognition.readinessState.value = SpeechRecognitionReadiness.UNSUPPORTED
            runCurrent()
            assertFalse(fixture.model.ready.value)
            fixture.model.recheckReadiness()
            runCurrent()
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            fixture.recognition.installedReadiness = SpeechRecognitionReadiness.DOWNLOAD_FAILED
            fixture.recognition.checkedReadiness = SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
            val checksBeforePrepare = fixture.recognition.checks
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, fixture.model.recognitionReadiness.value)
            assertFalse(fixture.model.ready.value)
            assertEquals("Preparation must not erase the failed download outcome",
                checksBeforePrepare, fixture.recognition.checks)
            fixture.model.disposeAndJoin()
            fixture.recognition.readinessState.value = SpeechRecognitionReadiness.READY
            fixture.model.enterForeground()
            fixture.model.start()
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun recognitionPhraseFailureSkipsTranslationAndOutputThenListeningContinues() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.recognition.failNext = true
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationFailure.RECOGNITION_FAILED, fixture.model.state.value.transcripts.last().failure)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            assertTrue(fixture.model.state.value.sessionActive)
            assertEquals(0, fixture.translation.translations)
            assertTrue(fixture.output.spoken.isEmpty())
            fixture.capture.emit()
            runCurrent()
            assertEquals(1, fixture.translation.translations)
            assertEquals(listOf("translation"), fixture.output.spoken)
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
        }
    }

    @Test fun missingLocalVoiceBlocksPreparedSessionAndRecheckNeverStartsCapture() = runTest {
        withModel(configure = { it.output.readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE }) { fixture ->
            assertFalse(fixture.model.ready.value)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.model.outputReadiness.value)
            fixture.model.start()
            fixture.model.prepareLanguages()
            runCurrent()
            assertFalse(fixture.model.ready.value)
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertEquals(0, fixture.capture.starts)
            fixture.output.readiness = SpeechOutputReadiness.READY
            fixture.model.recheckReadiness()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertEquals(0, fixture.capture.starts)
            fixture.model.start()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
        }
    }

    @Test fun terminalPlaybackFailureRefreshesMissingVoiceAndPreservesTranslationWithoutRestart() = runTest {
        withModel { fixture ->
            fixture.output.failMissingOnSpeak = true
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationFailure.PLAYBACK_FAILED, fixture.model.state.value.failure)
            assertEquals("original", fixture.model.state.value.transcripts.last().original)
            assertEquals("translation", fixture.model.state.value.transcripts.last().translation)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, fixture.model.outputReadiness.value)
            assertFalse(fixture.model.ready.value)
            assertFalse(fixture.model.state.value.sessionActive)
            assertFalse(fixture.model.state.value.captureActive)
            assertEquals(1, fixture.capture.starts)
            fixture.model.enterForeground()
            fixture.model.start()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
        }
    }

    @Test fun backgroundInterruptsBothOwnersSynchronouslyAndClearsSession() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            assertEquals(ConversationPhase.LISTENING, fixture.model.state.value.phase)
            val captureBefore = fixture.capture.interrupts
            val outputBefore = fixture.output.interrupts
            fixture.model.leaveForeground()
            assertEquals(captureBefore + 1, fixture.capture.interrupts)
            assertEquals(outputBefore + 1, fixture.output.interrupts)
            assertFalse(fixture.model.state.value.sessionActive)
            runCurrent()
            assertEquals(ConversationPhase.READY, fixture.model.state.value.phase)
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
            fixture.model.start()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
        }
    }

    @Test fun preparationAndSessionHoldRuntimeGateAndRefuseSave() = runTest {
        withModel(initiallyReady = false) { fixture ->
            fixture.translation.preparationGate = CompletableDeferred()
            fixture.model.prepareLanguages()
            runCurrent()
            assertTrue("Controller must be busy while preparation is in-flight", fixture.controller.busy.value)
            var savedDuringPrepare = false
            assertFalse(fixture.controller.saveIfIdle({ false }) { savedDuringPrepare = true })
            assertFalse(savedDuringPrepare)
            assertFalse(fixture.models.closed.isCompleted)
            assertFalse(fixture.recognition.closed)

            fixture.translation.preparationGate!!.complete(ModelAvailability.READY)
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertFalse("Controller must be idle when preparation completes", fixture.controller.busy.value)

            fixture.model.start()
            runCurrent()
            assertTrue("Controller must be busy during active session", fixture.controller.busy.value)
            var savedDuringSession = false
            assertFalse(fixture.controller.saveIfIdle({ true }) { savedDuringSession = true })
            assertFalse(savedDuringSession)

            fixture.model.stop()
            runCurrent()
            assertFalse("Controller must be idle after stop", fixture.controller.busy.value)
        }
    }

    @Test fun speechInstallationHoldsRuntimeGateAndRefusesSaveWithoutClosingOwner() = runTest {
        withModel(initiallyReady = false) { fixture ->
            val installationGate = CompletableDeferred<Unit>()
            fixture.models.installationGate = installationGate
            try {
                fixture.model.prepareLanguages()
                runCurrent()
                assertEquals(1, fixture.models.installs)
                assertEquals(0, fixture.translation.preparations)
                assertEquals(ConversationPreparation.INSTALLING, fixture.model.preparation.value)
                assertFalse(fixture.model.ready.value)
                assertTrue(fixture.controller.busy.value)
                var committed = false
                assertFalse(fixture.controller.saveIfIdle({ false }) { committed = true })
                assertFalse(committed)
                assertFalse(fixture.models.closed.isCompleted)
                assertFalse(fixture.recognition.closed)

                installationGate.complete(Unit)
                runCurrent()
                assertEquals(1, fixture.translation.preparations)
                assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
                assertTrue(fixture.model.ready.value)
                assertFalse(fixture.controller.busy.value)
                assertTrue(fixture.controller.saveIfIdle({ true }) { committed = true })
                assertTrue(committed)
                assertFalse(fixture.models.closed.isCompleted)
            } finally {
                installationGate.complete(Unit)
            }
        }
    }

    @Test fun onePreparationActionRequiresSpeechAndTranslationReadiness() = runTest {
        withModel(initiallyReady = false) { fixture ->
            fixture.translation.preparationGate = CompletableDeferred()
            fixture.model.prepareLanguages()
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(1, fixture.models.installs)
            assertEquals(1, fixture.translation.preparations)
            assertEquals(ConversationPreparation.TRANSLATION, fixture.model.preparation.value)
            assertFalse(fixture.model.ready.value)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            fixture.translation.preparationGate!!.complete(ModelAvailability.READY)
            runCurrent()
            assertTrue(fixture.model.ready.value)
            assertEquals(ConversationPreparation.IDLE, fixture.model.preparation.value)
        }
    }

    @Test fun failedOrIncompletePreparationCannotEnableAConversation() = runTest {
        withModel(initiallyReady = false) { fixture ->
            fixture.models.installFailure = true
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertFalse(fixture.model.ready.value)
            assertEquals(0, fixture.translation.preparations)
            fixture.models.installFailure = false
            fixture.models.readyAfterInstall = false
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun translationPreparationFailureDoesNotEnableInstalledSpeechResources() = runTest {
        withModel(initiallyReady = false) { fixture ->
            fixture.translation.preparationGate = CompletableDeferred(ModelAvailability.MISSING)
            fixture.model.prepareLanguages()
            runCurrent()
            assertTrue(fixture.models.ready)
            assertEquals(ConversationPreparation.ERROR, fixture.model.preparation.value)
            assertFalse(fixture.model.ready.value)
            fixture.model.start()
            runCurrent()
            assertEquals(0, fixture.capture.starts)
        }
    }

    @Test fun damagedResourcesFoundAtStartRevokeReadinessAndRequirePreparation() = runTest {
        withModel { fixture ->
            fixture.models.ready = false
            fixture.model.start()
            runCurrent()
            assertEquals(ConversationFailure.MODELS_MISSING, fixture.model.state.value.failure)
            assertFalse(fixture.model.ready.value)
            assertFalse(fixture.model.state.value.sessionActive)
            assertEquals(0, fixture.capture.starts)
            fixture.model.prepareLanguages()
            runCurrent()
            assertTrue(fixture.model.ready.value)
            fixture.model.start()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
        }
    }

    @Test fun stopDuringNativeWarmupPreventsTheFirstMicrophoneSample() = runTest {
        withModel { fixture ->
            fixture.recognition.preparationGate = CompletableDeferred()
            fixture.model.start()
            runCurrent()
            assertEquals(ConversationPhase.PREPARING, fixture.model.state.value.phase)
            fixture.model.stop()
            assertFalse(fixture.model.state.value.sessionActive)
            fixture.recognition.preparationGate!!.complete(Unit)
            runCurrent()
            assertEquals(0, fixture.capture.starts)
            assertTrue(fixture.output.spoken.isEmpty())
        }
    }

    @Test fun permissionRevocationStopsBothOwnersWithoutWaitingForTheWorker() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            val captureBefore = fixture.capture.interrupts
            val outputBefore = fixture.output.interrupts
            fixture.model.permissionDenied()
            assertTrue(fixture.model.permissionDenied.value)
            assertEquals(captureBefore + 1, fixture.capture.interrupts)
            assertEquals(outputBefore + 1, fixture.output.interrupts)
            assertFalse(fixture.model.state.value.sessionActive)
            runCurrent()
        }
    }

    @Test fun disposalAwaitersAndDisposeShareRecognitionAndCaptureBarriers() = runTest {
        withModel { fixture ->
            fixture.recognition.recognitionGate = CompletableDeferred()
            fixture.capture.closeGate = CompletableDeferred()
            fixture.recognition.closeGate = CompletableDeferred()
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationPhase.UNDERSTANDING, fixture.model.state.value.phase)
            val first = async { fixture.model.disposeAndJoin() }
            val second = async { fixture.model.disposeAndJoin() }
            runCurrent()
            fixture.model.dispose()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(fixture.capture.closeStarted.isCompleted)
            assertFalse(fixture.recognition.closed)
            assertFalse(fixture.models.closed.isCompleted)
            fixture.recognition.recognitionGate!!.complete(Unit)
            fixture.capture.closeStarted.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.capture.closes)
            assertFalse(fixture.models.closed.isCompleted)
            fixture.capture.closeGate!!.complete(Unit)
            fixture.recognition.closeStarted.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(1, fixture.recognition.closes)
            assertFalse(fixture.models.closed.isCompleted)
            fixture.recognition.closeGate!!.complete(Unit)
            first.await()
            second.await()
            fixture.model.disposeAndJoin()
            assertEquals(1, fixture.capture.closes)
            assertEquals(1, fixture.recognition.closes)
            assertEquals(1, fixture.output.closes)
            assertEquals(1, fixture.translation.closes)
            assertEquals(1, fixture.models.closes)
            assertFalse(fixture.controller.teardownFailed.value)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
        }
    }

    @Test fun cancelledDisposalAwaiterDoesNotAbandonIndependentNativeCleanup() = runTest {
        withModel { fixture ->
            fixture.capture.closeGate = CompletableDeferred()
            val cancelledAwaiter = async { fixture.model.disposeAndJoin() }
            runCurrent()
            fixture.capture.closeStarted.await()
            cancelledAwaiter.cancelAndJoin()
            assertTrue(cancelledAwaiter.isCancelled)
            assertFalse(fixture.models.closed.isCompleted)
            val survivingAwaiter = async { fixture.model.disposeAndJoin() }
            runCurrent()
            assertFalse(survivingAwaiter.isCompleted)
            assertEquals(1, fixture.capture.closes)
            fixture.capture.closeGate!!.complete(Unit)
            survivingAwaiter.await()
            assertEquals(1, fixture.capture.closes)
            assertEquals(1, fixture.recognition.closes)
            assertEquals(1, fixture.models.closes)
            assertFalse(fixture.controller.teardownFailed.value)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
        }
    }

    @Test fun disposalCancelsAndJoinsReadinessBeforeClosingResources() = runTest {
        withModel(configure = { fixture ->
            fixture.models.readinessGate = CompletableDeferred()
            fixture.models.readinessExitGate = CompletableDeferred()
        }) { fixture ->
            assertEquals(ConversationPreparation.CHECKING, fixture.model.preparation.value)
            val disposal = async { fixture.model.disposeAndJoin() }
            runCurrent()
            fixture.models.readinessExiting.await()
            assertFalse(disposal.isCompleted)
            assertFalse(fixture.models.readinessFinished)
            assertFalse(fixture.capture.closeStarted.isCompleted)
            assertFalse(fixture.models.closed.isCompleted)
            fixture.models.readinessExitGate!!.complete(Unit)
            disposal.await()
            assertTrue(fixture.models.readinessFinished)
            assertFalse(fixture.model.ready.value)
            assertEquals(1, fixture.capture.closes)
            assertEquals(1, fixture.models.closes)
        }
    }

    @Test fun disposalCancelsAndJoinsPreparationBeforeClosingResources() = runTest {
        withModel(initiallyReady = false) { fixture ->
            fixture.translation.preparationGate = CompletableDeferred()
            fixture.translation.preparationExitGate = CompletableDeferred()
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(ConversationPreparation.TRANSLATION, fixture.model.preparation.value)
            val disposal = async { fixture.model.disposeAndJoin() }
            runCurrent()
            fixture.translation.preparationExiting.await()
            assertFalse(disposal.isCompleted)
            assertFalse(fixture.translation.preparationFinished)
            assertFalse(fixture.capture.closeStarted.isCompleted)
            assertFalse(fixture.models.closed.isCompleted)
            fixture.translation.preparationExitGate!!.complete(Unit)
            disposal.await()
            assertTrue(fixture.translation.preparationFinished)
            assertFalse(fixture.model.ready.value)
            assertEquals(1, fixture.translation.closes)
            assertEquals(1, fixture.models.closes)
        }
    }

    @Test fun actualCloseFailureIsSharedByEveryDisposalAwaitAndPoisonsController() = runTest {
        val failure = IllegalStateException("Native capture close failed")
        withModel(expectedDisposalFailure = failure) { fixture ->
            fixture.capture.closeGate = CompletableDeferred()
            fixture.capture.closeFailure = failure
            val first = async { runCatching { fixture.model.disposeAndJoin() } }
            val second = async { runCatching { fixture.model.disposeAndJoin() } }
            runCurrent()
            fixture.capture.closeStarted.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse(fixture.controller.teardownFailed.value)
            fixture.capture.closeGate!!.complete(Unit)
            assertFailureSemantics(failure, first.await().exceptionOrNull())
            assertFailureSemantics(failure, second.await().exceptionOrNull())
            assertFailureSemantics(failure, runCatching { fixture.model.disposeAndJoin() }.exceptionOrNull())
            fixture.model.dispose()
            assertTrue(fixture.controller.teardownFailed.value)
            assertTrue(fixture.controller.busy.value)
            assertEquals(1, fixture.capture.closes)
            assertEquals(1, fixture.output.closes)
            assertEquals(1, fixture.recognition.closes)
            assertEquals(1, fixture.translation.closes)
            assertEquals(1, fixture.models.closes)
            assertTrue(fixture.output.spoken.isEmpty())
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
        }
    }

    @Test fun backgroundDuringPlaybackInterruptsOutputAndDoesNotAutomaticallyRestart() = runTest {
        withModel { fixture ->
            fixture.output.playbackGate = CompletableDeferred()
            fixture.model.start()
            runCurrent()
            fixture.capture.emit()
            runCurrent()
            assertEquals(ConversationPhase.SPEAKING, fixture.model.state.value.phase)
            assertFalse(fixture.model.state.value.captureActive)
            val captureBefore = fixture.capture.interrupts
            val outputBefore = fixture.output.interrupts
            fixture.model.leaveForeground()
            assertEquals(captureBefore + 1, fixture.capture.interrupts)
            assertEquals(outputBefore + 1, fixture.output.interrupts)
            assertFalse(fixture.model.state.value.sessionActive)
            fixture.output.playbackGate!!.complete(Unit)
            runCurrent()
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
            fixture.model.enterForeground()
            runCurrent()
            assertEquals(1, fixture.capture.starts)
            fixture.model.start()
            runCurrent()
            assertEquals(2, fixture.capture.starts)
        }
    }

    @Test fun disposedOwnerIgnoresLateRouteAndPermissionCallbacks() = runTest {
        withModel { fixture ->
            fixture.model.start()
            runCurrent()
            fixture.model.dispose()
            runCurrent()
            fixture.models.closed.await()
            val captureInterrupts = fixture.capture.interrupts
            val outputInterrupts = fixture.output.interrupts
            fixture.model.stop()
            fixture.model.leaveForeground()
            fixture.model.permissionDenied()
            fixture.model.enterForeground()
            fixture.model.start()
            fixture.model.prepareLanguages()
            runCurrent()
            assertEquals(captureInterrupts, fixture.capture.interrupts)
            assertEquals(outputInterrupts, fixture.output.interrupts)
            assertEquals(1, fixture.capture.starts)
            assertEquals(0, fixture.models.installs)
            assertTrue(fixture.model.state.value.transcripts.isEmpty())
        }
    }

    private fun assertFailureSemantics(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        val rootCause = generateSequence(actual) { it.cause }.lastOrNull()
        assertEquals(expected.javaClass, rootCause?.javaClass)
        assertEquals(expected.message, rootCause?.message)
    }

    private suspend fun TestScope.withModel(
        initiallyReady: Boolean = true,
        speechOutputBackend: SpeechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
        speechOutputEnabled: Boolean = true,
        expectedDisposalFailure: Throwable? = null,
        configure: (Fixture) -> Unit = {},
        block: suspend TestScope.(Fixture) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val fixture = createFixture(initiallyReady, dispatcher, speechOutputBackend, speechOutputEnabled)
        configure(fixture)
        try {
            runCurrent()
            block(fixture)
        } finally {
            fixture.recognition.recognitionGate?.complete(Unit)
            fixture.recognition.closeGate?.complete(Unit)
            fixture.capture.closeGate?.complete(Unit)
            fixture.models.readinessExitGate?.complete(Unit)
            fixture.translation.preparationExitGate?.complete(Unit)
            fixture.preference.gate?.complete(Unit)
            fixture.output.prepareGate?.complete(Unit)
            fixture.output.playbackGate?.complete(Unit)
            try {
                val cleanupFailure = runCatching { fixture.model.disposeAndJoin() }.exceptionOrNull()
                if (expectedDisposalFailure == null) {
                    cleanupFailure?.let { throw it }
                } else {
                    assertFailureSemantics(expectedDisposalFailure, cleanupFailure)
                }
            } finally {
                fixture.observationJob.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }
    }

    private suspend fun createFixture(
        initiallyReady: Boolean,
        dispatcher: CoroutineDispatcher,
        speechOutputBackend: SpeechOutputBackend,
        speechOutputEnabled: Boolean,
    ): Fixture {
        val observationJob = SupervisorJob()
        val scope = CoroutineScope(dispatcher + observationJob)
        val controller = RuntimeOwnerController(scope)
        val models = FakeModels(initiallyReady)
        val capture = FakeCapture()
        val recognition = FakeRecognition()
        val translation = FakeTranslation(initiallyReady)
        val output = FakeOutput()
        val config = ConversationSessionConfig(
            languages = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
            engineId = "test",
            vadModelId = "vad",
            vadMinSilenceMs = 900,
            ttsSpeedPercent = 100,
            speechOutputBackend = speechOutputBackend,
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
        )
        val owner = controller.replace {
            ConversationRuntimeOwner(
                config = config,
                models = models,
                capture = capture,
                recognition = recognition,
                translation = translation,
                output = output,
                scope = CoroutineScope(dispatcher + SupervisorJob()),
                speechOutputEnabled = speechOutputEnabled,
            )
        }
        val preference = OutputPreference().apply { saved = speechOutputEnabled }
        val model = ConversationViewModel(owner, controller, saveSpeechOutput = { enabled, fresh ->
            controller.saveSpeechOutput(owner, fresh) {
                preference.gate?.await()
                if (!fresh()) false else {
                    preference.failure?.let { throw it }
                    if (enabled && preference.failEnable) throw java.io.IOException("Enable preference save failed")
                    if (enabled) preference.unavailable?.let {
                        throw com.example.offlinetranslator.runtime.SpeechOutputUnavailableException(it)
                    }
                    preference.saved = enabled
                    true
                }
            }
        })
        return Fixture(controller, observationJob, models, capture, recognition, translation, output, owner, model, preference)
    }

    private class Fixture(
        val controller: RuntimeOwnerController,
        val observationJob: kotlinx.coroutines.Job,
        val models: FakeModels,
        val capture: FakeCapture,
        val recognition: FakeRecognition,
        val translation: FakeTranslation,
        val output: FakeOutput,
        val owner: ConversationRuntimeOwner,
        val model: ConversationViewModel,
        val preference: OutputPreference,
    )

    private class OutputPreference {
        var saved = true
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var unavailable: SpeechOutputReadiness? = null
        var failEnable = false
    }

    private class FakeModels(var ready: Boolean) : ConversationModelRepository {
        var installs = 0
        var output: FakeOutput? = null
        var installFailure = false
        var readyAfterInstall = true
        var installationGate: CompletableDeferred<Unit>? = null
        val closed = CompletableDeferred<Unit>()
        var closes = 0
        var readinessGate: CompletableDeferred<Unit>? = null
        var readinessExitGate: CompletableDeferred<Unit>? = null
        val readinessExiting = CompletableDeferred<Unit>()
        var readinessFinished = false
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install(speechOutputRequired: Boolean) {
            installs++
            installationGate?.await()
            if (installFailure) error("Installation failed")
            ready = readyAfterInstall
            if (ready && speechOutputRequired) output?.readiness = SpeechOutputReadiness.READY
        }
        override suspend fun isConversationReady(speechOutputRequired: Boolean): Boolean {
            try {
                readinessGate?.await()
                return ready && (!speechOutputRequired ||
                    (output?.readiness ?: SpeechOutputReadiness.READY) == SpeechOutputReadiness.READY)
            } finally {
                readinessExiting.complete(Unit)
                withContext(NonCancellable) { readinessExitGate?.await() }
                readinessFinished = true
            }
        }
        override fun close() { closes++; closed.complete(Unit) }
    }

    private class FakeCapture : SpeechCaptureEngine {
        var starts = 0
        var interrupts = 0
        var closes = 0
        var closeGate: CompletableDeferred<Unit>? = null
        var closeFailure: Throwable? = null
        val closeStarted = CompletableDeferred<Unit>()
        private var listener: SpeechCaptureListener? = null
        override suspend fun start(listener: SpeechCaptureListener) { starts++; this.listener = listener }
        override fun interruptCurrent() { interrupts++ }
        override suspend fun stop(flush: Boolean): SpeechSegment? = null
        fun emit() { requireNotNull(listener).onSegment(SpeechSegment.fromPcm(shortArrayOf(17))) }
        override fun close() { closes++ }
        override suspend fun closeAndJoin() {
            close()
            closeStarted.complete(Unit)
            closeGate?.await()
            closeFailure?.let { throw it }
        }
    }

    private class FakeRecognition : ConversationRecognitionEngine {
        var preparationGate: CompletableDeferred<Unit>? = null
        var recognitionGate: CompletableDeferred<Unit>? = null
        var closed = false
        var closes = 0
        var closeGate: CompletableDeferred<Unit>? = null
        val closeStarted = CompletableDeferred<Unit>()
        override val readinessState = MutableStateFlow(SpeechRecognitionReadiness.READY)
        var checkedReadiness: SpeechRecognitionReadiness? = null
        var installedReadiness = SpeechRecognitionReadiness.READY
        var installs = 0
        var checks = 0
        var failNext = false
        override suspend fun readiness(): SpeechRecognitionReadiness {
            checks++
            checkedReadiness?.let { readinessState.value = it }
            return readinessState.value
        }
        override suspend fun install() { installs++; readinessState.value = installedReadiness }
        override suspend fun prepare() { preparationGate?.await() }
        override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance {
            withContext(NonCancellable) { recognitionGate?.await() }
            if (failNext) { failNext = false; error("Recognition failed") }
            return RecognizedUtterance(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), "original")
        }
        override fun close() { closes++; closed = true }
        override suspend fun closeAndJoin() {
            close()
            closeStarted.complete(Unit)
            closeGate?.await()
        }
    }

    private class FakeTranslation(private var ready: Boolean) : TranslationEngine {
        override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(
            if (ready) TranslationPreparationPhase.READY else TranslationPreparationPhase.MISSING,
        ))
        var preparations = 0
        var translations = 0
        var preparationGate: CompletableDeferred<ModelAvailability>? = null
        var closes = 0
        var preparationExitGate: CompletableDeferred<Unit>? = null
        val preparationExiting = CompletableDeferred<Unit>()
        var preparationFinished = false
        var failure: com.example.offlinetranslator.core.TranslationFailure? = null
        override suspend fun modelAvailability() = if (ready) ModelAvailability.READY else ModelAvailability.MISSING
        override suspend fun prepare(): ModelAvailability {
            preparations++
            try {
                val availability = preparationGate?.await() ?: ModelAvailability.READY
                ready = availability == ModelAvailability.READY
                return availability
            } finally {
                preparationExiting.complete(Unit)
                withContext(NonCancellable) { preparationExitGate?.await() }
                preparationFinished = true
            }
        }
        override suspend fun translate(pair: LanguagePair, text: String): String {
            translations++
            failure?.let { failure = null; throw it }
            return "translation"
        }
        override fun close() { closes++ }
    }

    private class FakeOutput : ConversationSpeechOutput {
        var interrupts = 0
        var closes = 0
        val spoken = mutableListOf<String>()
        var playbackGate: CompletableDeferred<Unit>? = null
        var readiness = SpeechOutputReadiness.READY
        var failMissingOnSpeak = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var ignorePrepareCancellation = false
        override suspend fun readiness() = readiness
        override suspend fun prepare() {
            if (ignorePrepareCancellation) withContext(NonCancellable) { prepareGate?.await() }
            else prepareGate?.await()
        }
        override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
            spoken += text
            if (failMissingOnSpeak) {
                readiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                error("Local voice disappeared")
            }
            onPlaybackStarted(SpeechPlaybackStart(null, measured = false))
            withContext(NonCancellable) { playbackGate?.await() }
        }
        override fun interruptCurrent() { interrupts++ }
        override fun throwIfReleaseFailed() = Unit
        override fun close() { closes++ }
    }
}
