package com.example.offlinetranslator.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPipelineTest {
    @Test fun undeterminedTextIsRetainedWithoutTranslationAndNextTurnCanSucceed() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.result = UndeterminedLanguage("Сто сорок шесть куруш")
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val uncertain = fixture.pipeline.state.value.transcripts.single()
        assertEquals("Сто сорок шесть куруш", uncertain.original)
        assertNull(uncertain.direction)
        assertNull(uncertain.translation)
        assertEquals(ConversationTranscriptStatus.ERROR, uncertain.status)
        assertEquals(ConversationFailure.UNDETERMINED_LANGUAGE, uncertain.failure)
        assertEquals(ConversationFailure.UNDETERMINED_LANGUAGE, fixture.pipeline.state.value.failure)
        assertTrue(fixture.capture.running)
        assertEquals(0, fixture.translation.calls)
        assertEquals(0, fixture.output.started)
        fixture.recognition.result = null
        fixture.capture.emit(segment())
        runCurrent()
        assertEquals(uncertain, fixture.pipeline.state.value.transcripts.first())
        assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.pipeline.state.value.transcripts.last().status)
        assertNull(fixture.pipeline.state.value.failure)
        assertEquals(1, fixture.translation.calls)
        assertEquals(1, fixture.output.started)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun blankAndOversizedUndeterminedTextNeverBecomeSuccessfulTurns() = runTest {
        for (text in listOf("", " \t", "a".repeat(ConversationPipeline.MAX_TEXT_CHARACTERS + 1))) {
            val fixture = Fixture(this)
            fixture.recognition.result = UndeterminedLanguage(text)
            fixture.pipeline.start()
            runCurrent()
            fixture.capture.emit(segment())
            runCurrent()
            val row = fixture.pipeline.state.value.transcripts.single()
            assertEquals(text.take(ConversationPipeline.MAX_TEXT_CHARACTERS), row.original)
            assertEquals(ConversationFailure.UNDETERMINED_LANGUAGE, row.failure)
            assertNull(row.direction)
            assertNull(row.translation)
            assertEquals(0, fixture.translation.calls)
            assertEquals(0, fixture.output.started)
            assertTrue(fixture.pipeline.state.value.sessionActive)
            fixture.pipeline.clear().getOrThrow()
        }
    }

    @Test fun microphoneIsSuspendedDuringOutputAndAutomaticallyResumesWithoutEcho() = runTest {
        val fixture = Fixture(this)
        val playback = CompletableDeferred<Unit>()
        fixture.output.gate = playback
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        assertEquals(ConversationPhase.SPEAKING, fixture.pipeline.state.value.phase)
        assertFalse(fixture.capture.running)
        assertFalse(fixture.pipeline.state.value.captureActive)
        fixture.capture.emit(segment()) // A late reader callback containing the phone's own sound.
        runCurrent()
        assertEquals(1, fixture.pipeline.state.value.transcripts.size)
        playback.complete(Unit)
        runCurrent()
        assertTrue(fixture.capture.running)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun stopRejectsLateNativeResultsAndNeverStartsTheirTranslationOrPlayback() = runTest {
        val fixture = Fixture(this)
        val native = CompletableDeferred<Unit>()
        fixture.recognition.gate = native
        fixture.recognition.ignoreCancellation = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        fixture.pipeline.interruptCurrent()
        assertFalse(fixture.capture.running)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertEquals(ConversationTranscriptStatus.INTERRUPTED, fixture.pipeline.state.value.transcripts.single().status)
        native.complete(Unit)
        runCurrent()
        assertEquals(0, fixture.output.started)
        assertNull(fixture.pipeline.state.value.transcripts.single().translation)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun stopDuringCapturePauseWaitsForReaderReleaseBeforeRestart() = runTest {
        val fixture = Fixture(this)
        val teardown = CompletableDeferred<Unit>()
        fixture.capture.stopGate = teardown
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        assertTrue(fixture.capture.microphoneOwned)
        assertFalse(fixture.capture.running)
        val stop = async { fixture.pipeline.stop() }
        runCurrent()
        assertFalse(stop.isCompleted)
        val restart = async { fixture.pipeline.start() }
        runCurrent()
        assertFalse(restart.isCompleted)
        teardown.complete(Unit)
        runCurrent()
        stop.await().getOrThrow()
        assertTrue(restart.await())
        runCurrent()
        assertEquals(0, fixture.output.started)
        assertTrue(fixture.capture.running)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun clearWaitsForCancelledNativeOwnershipBeforeANewSessionCanListen() = runTest {
        val fixture = Fixture(this)
        val native = CompletableDeferred<Unit>()
        fixture.recognition.gate = native
        fixture.recognition.ignoreCancellation = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val clear = async { fixture.pipeline.clear() }
        runCurrent()
        val start = async { fixture.pipeline.start() }
        runCurrent()
        assertFalse(start.isCompleted)
        assertFalse(fixture.capture.running)
        fixture.recognition.gate = null
        native.complete(Unit)
        runCurrent()
        clear.await().getOrThrow()
        assertTrue(start.await())
        runCurrent()
        assertTrue(fixture.pipeline.state.value.sessionActive)
        assertTrue(fixture.pipeline.state.value.transcripts.isEmpty())
        assertTrue(fixture.capture.running)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun burstIsBoundedAndStoppingDropsAllQueuedSpeech() = runTest {
        val fixture = Fixture(this)
        val native = CompletableDeferred<Unit>()
        fixture.recognition.gate = native
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        repeat(32_000) { fixture.capture.emit(segment()) }
        val state = fixture.pipeline.state.value
        assertEquals(ConversationPipeline.MAX_QUEUED_SEGMENTS, state.queuedSegments)
        assertEquals(31_999, state.droppedSegments)
        fixture.pipeline.stop().getOrThrow()
        runCurrent()
        assertEquals(0, fixture.pipeline.state.value.queuedSegments)
        assertEquals(0, fixture.output.started)
        assertFalse(fixture.capture.running)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun missingResourcesDoNotAcquireMicrophoneOwnership() = runTest {
        val fixture = Fixture(this)
        fixture.models.ready = false
        fixture.pipeline.start()
        runCurrent()
        assertEquals(ConversationFailure.MODELS_MISSING, fixture.pipeline.state.value.failure)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertFalse(fixture.capture.running)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun unavailableRecognitionNeverInstallsOrAcquiresMicrophone() = runTest {
        for (state in SpeechRecognitionReadiness.entries.filter { it != SpeechRecognitionReadiness.READY }) {
            val fixture = Fixture(this)
            fixture.recognition.readinessState.value = state
            fixture.pipeline.start()
            runCurrent()
            assertEquals(ConversationFailure.MODELS_MISSING, fixture.pipeline.state.value.failure)
            assertFalse(fixture.capture.running)
            assertEquals(0, fixture.recognition.installations)
            fixture.pipeline.clear().getOrThrow()
        }
    }

    @Test fun failedRecognitionPreservesErrorThenProcessesNextPhraseWithoutRestart() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.failNext = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val failure = fixture.pipeline.state.value.transcripts.single()
        assertEquals(ConversationTranscriptStatus.ERROR, failure.status)
        assertEquals(ConversationFailure.RECOGNITION_FAILED, failure.failure)
        assertEquals(0, fixture.translation.calls)
        assertEquals(0, fixture.output.started)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        assertTrue(fixture.capture.running)
        fixture.capture.emit(segment())
        runCurrent()
        assertEquals(ConversationTranscriptStatus.COMPLETE, fixture.pipeline.state.value.transcripts.last().status)
        assertEquals(failure, fixture.pipeline.state.value.transcripts.first())
        assertEquals(1, fixture.translation.calls)
        assertEquals(1, fixture.output.started)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun outputFailurePreservesTranslatedTextAndRestoresListening() = runTest {
        val fixture = Fixture(this)
        fixture.output.fail = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val row = fixture.pipeline.state.value.transcripts.single()
        assertEquals(ConversationFailure.PLAYBACK_FAILED, row.failure)
        assertEquals(ConversationTranscriptStatus.ERROR, row.status)
        assertEquals("No bus today", row.translation)
        assertTrue(fixture.capture.running)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun failedCapturePauseCannotPlayAndReacquiresListeningBeforeReportingReady() = runTest {
        val fixture = Fixture(this)
        fixture.capture.failNextStop = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val row = fixture.pipeline.state.value.transcripts.single()
        assertEquals(ConversationFailure.CAPTURE_FAILED, row.failure)
        assertEquals(ConversationTranscriptStatus.ERROR, row.status)
        assertEquals("No bus today", row.translation)
        assertEquals(0, fixture.output.started)
        assertTrue(fixture.capture.running)
        assertTrue(fixture.pipeline.state.value.captureActive)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun microphoneFailureTerminatesPendingTurnAndRejectsLateNativeResults() = runTest {
        val fixture = Fixture(this)
        val native = CompletableDeferred<Unit>()
        fixture.recognition.gate = native
        fixture.recognition.ignoreCancellation = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        fixture.capture.fail(SpeechCaptureFailure.READ_FAILED)
        native.complete(Unit)
        runCurrent()
        val state = fixture.pipeline.state.value
        fixture.pipeline.clear().getOrThrow()
        assertEquals(ConversationPhase.ERROR, state.phase)
        assertFalse(state.sessionActive)
        assertFalse(state.captureActive)
        assertEquals(ConversationFailure.CAPTURE_FAILED, state.failure)
        assertEquals(ConversationTranscriptStatus.ERROR, state.transcripts.single().status)
        assertEquals(ConversationFailure.CAPTURE_FAILED, state.transcripts.single().failure)
        assertNull(state.transcripts.single().translation)
        assertEquals(0, fixture.output.started)
    }

    @Test fun unsupportedLanguageNeverGetsAGuessedTranslationAndSessionKeepsListening() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.unsupported = true
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val row = fixture.pipeline.state.value.transcripts.single()
        assertEquals(ConversationFailure.UNSUPPORTED_LANGUAGE, row.failure)
        assertNull(row.direction)
        assertNull(row.translation)
        assertEquals(0, fixture.output.started)
        assertTrue(fixture.capture.running)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun latencyIncludesSpeechEndAndNeverPromotesEstimatedOrReversedTiming() = runTest {
        val fixture = Fixture(this)
        fixture.output.start = SpeechPlaybackStart(3_100_000_000L, measured = true)
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        val measured = fixture.pipeline.state.value.transcripts.last()
        assertEquals(2_100L, measured.latencyMillis)
        assertTrue(measured.latencyMeasured)
        assertTrue(requireNotNull(measured.latencyMillis) > ConversationPipeline.MAX_LATENCY_MILLIS)
        fixture.output.start = SpeechPlaybackStart(3_000_000_000L, measured = true)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        assertEquals(2_000L, fixture.pipeline.state.value.transcripts.last().latencyMillis)
        fixture.output.start = SpeechPlaybackStart(3_000_000_001L, measured = true)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        assertEquals(2_001L, fixture.pipeline.state.value.transcripts.last().latencyMillis)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = false))
        runCurrent()
        assertFalse(fixture.pipeline.state.value.transcripts.last().latencyMeasured)
        fixture.output.start = SpeechPlaybackStart(900_000_000L, measured = true)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        val reversed = fixture.pipeline.state.value.transcripts.last()
        assertNull(reversed.latencyMillis)
        assertFalse(reversed.latencyMeasured)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun headBackedPresentationRemainsEstimatedAndPhaseOnlySuccessNeverReusesEarlierLatency() = runTest {
        val fixture = Fixture(this)
        fixture.pipeline.start()
        runCurrent()
        fixture.output.start = SpeechPlaybackStart(3_100_000_000L, measured = true)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        assertEquals(2_100L, fixture.pipeline.state.value.transcripts.last().latencyMillis)
        assertTrue(fixture.pipeline.state.value.transcripts.last().latencyMeasured)
        fixture.output.start = SpeechPlaybackStart(1_600_000_000L, measured = false)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        assertEquals(600L, fixture.pipeline.state.value.transcripts.last().latencyMillis)
        assertFalse(fixture.pipeline.state.value.transcripts.last().latencyMeasured)
        fixture.output.start = SpeechPlaybackStart(null, measured = false)
        fixture.capture.emit(segment(end = 1_000_000_000L, measured = true))
        runCurrent()
        val latest = fixture.pipeline.state.value.transcripts.last()
        assertEquals(ConversationTranscriptStatus.COMPLETE, latest.status)
        assertEquals("Сегодня без автобуса", latest.original)
        assertEquals("No bus today", latest.translation)
        assertNull(latest.latencyMillis)
        assertFalse(latest.latencyMeasured)
        assertTrue(fixture.capture.running)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun selectedOutputReadinessBlocksMicrophoneEvenWithAllModelsPresent() = runTest {
        val fixture = Fixture(this)
        fixture.output.ready = SpeechOutputReadiness.MISSING_LOCAL_VOICE
        fixture.pipeline.start()
        runCurrent()
        assertEquals(ConversationFailure.MODELS_MISSING, fixture.pipeline.state.value.failure)
        assertFalse(fixture.capture.microphoneOwned)
        assertEquals(0, fixture.output.started)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun voiceRemovedDuringPreparationCannotStartMicrophone() = runTest {
        val fixture = Fixture(this)
        fixture.output.readyAfterPrepare = SpeechOutputReadiness.MISSING_LOCAL_VOICE
        fixture.pipeline.start()
        runCurrent()
        assertEquals(ConversationFailure.MODELS_MISSING, fixture.pipeline.state.value.failure)
        assertFalse(fixture.capture.microphoneOwned)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun stopDuringTranslationPreparationCannotEnableCaptureOnLateCompletion() = runTest {
        val fixture = Fixture(this)
        val preparation = CompletableDeferred<Unit>()
        fixture.translation.preparationGate = preparation
        fixture.pipeline.start()
        runCurrent()
        assertEquals(ConversationPhase.PREPARING, fixture.pipeline.state.value.phase)
        assertFalse(fixture.capture.microphoneOwned)
        assertFalse(fixture.capture.running)
        fixture.pipeline.stop().getOrThrow()
        preparation.complete(Unit)
        runCurrent()
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertFalse(fixture.capture.microphoneOwned)
        assertTrue(fixture.pipeline.state.value.transcripts.isEmpty())
        fixture.pipeline.start()
        runCurrent()
        assertTrue(fixture.capture.running)
        assertEquals(ConversationPhase.LISTENING, fixture.pipeline.state.value.phase)
        fixture.pipeline.clear().getOrThrow()
    }

    @Test fun stopInterruptsReplacementInstalledWhileWaitingForControl() =
        assertReplacementInterrupted(clear = false)

    @Test fun clearInterruptsReplacementInstalledWhileWaitingForControl() =
        assertReplacementInterrupted(clear = true)

    private fun assertReplacementInterrupted(clear: Boolean) = runTest {
        val fixture = Fixture(this)
        val firstRelease = CompletableDeferred<Unit>()
        val replacementRelease = CompletableDeferred<Unit>()
        try {
            assertTrue(fixture.pipeline.start())
            runCurrent()
            fixture.capture.emit(segment())
            runCurrent()
            assertEquals(1, fixture.pipeline.state.value.transcripts.size)
            fixture.capture.stopGate = firstRelease
            fixture.pipeline.interruptCurrent()
            runCurrent()
            assertTrue(fixture.capture.stopStarted.isCompleted)
            assertTrue(fixture.capture.microphoneOwned)

            val restart = async { fixture.pipeline.start() }
            runCurrent()
            assertFalse(restart.isCompleted)
            val terminal = async {
                if (clear) fixture.pipeline.clear() else fixture.pipeline.stop()
            }
            runCurrent()
            assertFalse(terminal.isCompleted)
            fixture.capture.stopGate = replacementRelease
            firstRelease.complete(Unit)
            runCurrent()
            assertTrue(restart.isCompleted)
            assertTrue(restart.await())
            assertEquals(3, fixture.capture.starts)
            assertFalse("Replacement must be interrupted before joining it", fixture.capture.running)
            assertTrue("Stop/Clear must await actual replacement release", fixture.capture.microphoneOwned)
            assertFalse(terminal.isCompleted)

            replacementRelease.complete(Unit)
            runCurrent()
            assertTrue("Stop/Clear must complete after replacement release", terminal.isCompleted)
            terminal.await().getOrThrow()
            assertFalse(fixture.capture.microphoneOwned)
            assertFalse(fixture.pipeline.state.value.sessionActive)
            if (clear) assertEquals(ConversationState(), fixture.pipeline.state.value)
        } finally {
            firstRelease.complete(Unit)
            replacementRelease.complete(Unit)
            fixture.pipeline.interruptCurrent()
            runCurrent()
            fixture.pipeline.clear().getOrThrow()
        }
    }

    @Test fun interruptionRevokesStateAndAllAwaitersWaitForStickyReleaseFailures() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.gate = CompletableDeferred()
        fixture.pipeline.start()
        runCurrent()
        fixture.capture.emit(segment())
        runCurrent()
        val detail = IllegalArgumentException("Shared native lease detail")
        val captureFailure = IllegalStateException("Native lease release failed", detail)
        val outputFailure = IllegalStateException("Native lease release failed", detail)
        val stopFailure = IllegalStateException("Native lease release failed", detail)
        val release = CompletableDeferred<Unit>()
        fixture.capture.interruptFailure = captureFailure
        fixture.output.interruptFailure = outputFailure
        fixture.capture.stopFailure = stopFailure
        fixture.capture.stopGate = release

        assertTrue(runCatching { fixture.pipeline.interruptCurrent() }.isSuccess)
        assertEquals(1, fixture.capture.interrupts)
        assertEquals(1, fixture.output.interrupts)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertFalse(fixture.pipeline.state.value.captureActive)
        assertEquals(ConversationPhase.ERROR, fixture.pipeline.state.value.phase)
        assertEquals(ConversationTranscriptStatus.INTERRUPTED, fixture.pipeline.state.value.transcripts.single().status)
        fixture.capture.emit(segment())
        val stop = async { fixture.pipeline.stop() }
        val clear = async { fixture.pipeline.clear() }
        val cancelledClear = async { fixture.pipeline.clear() }
        val restart = async { runCatching { fixture.pipeline.start() } }
        runCurrent()
        assertTrue(fixture.capture.stopStarted.isCompleted)
        assertTrue(fixture.capture.microphoneOwned)
        assertFalse(stop.isCompleted)
        assertFalse(clear.isCompleted)
        assertFalse(cancelledClear.isCompleted)
        assertFalse(restart.isCompleted)
        stop.cancel()
        cancelledClear.cancel()
        runCurrent()
        assertFalse("Cancelled awaiter must still join terminal capture cleanup", stop.isCompleted)
        assertFalse("Cancelled clear must still join terminal capture cleanup", cancelledClear.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertTrue(stop.isCompleted)
        assertTrue(cancelledClear.isCompleted)
        assertSame(captureFailure, clear.await().exceptionOrNull())
        assertContainsFailure(captureFailure, restart.await().exceptionOrNull())
        assertSame(outputFailure, captureFailure.suppressed[0])
        assertSame(stopFailure, captureFailure.suppressed[1])
        assertEquals(2, captureFailure.suppressed.size)
        assertEquals(ConversationState(), fixture.pipeline.state.value)
        assertFalse(fixture.capture.microphoneOwned)
        assertEquals(1, fixture.capture.starts)
        assertEquals(0, fixture.output.started)
        assertSame(captureFailure, fixture.pipeline.stop().exceptionOrNull())
        assertSame(captureFailure, fixture.pipeline.clear().exceptionOrNull())
        assertEquals(ConversationState(), fixture.pipeline.state.value)
    }

    @Test fun readerFailureCallbackDoesNotEscapeAndFinalizerStillAwaitsCaptureAfterOutputFailure() = runTest {
        val fixture = Fixture(this)
        fixture.pipeline.start()
        runCurrent()
        val primary = IllegalStateException("Output interruption failed")
        val secondary = IllegalStateException("Terminal microphone release failed")
        val release = CompletableDeferred<Unit>()
        fixture.output.interruptFailure = primary
        fixture.capture.stopFailure = secondary
        fixture.capture.stopGate = release
        assertTrue(runCatching { fixture.capture.fail(SpeechCaptureFailure.READ_FAILED) }.isSuccess)
        assertEquals(1, fixture.capture.interrupts)
        assertEquals(1, fixture.output.interrupts)
        assertEquals(ConversationFailure.CAPTURE_FAILED, fixture.pipeline.state.value.failure)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        val stop = async { fixture.pipeline.stop() }
        runCurrent()
        assertTrue(fixture.capture.stopStarted.isCompleted)
        assertFalse(stop.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertSame(primary, stop.await().exceptionOrNull())
        assertSame(secondary, primary.suppressed.single())
        assertFalse(fixture.capture.microphoneOwned)
        assertContainsFailure(primary, runCatching { fixture.pipeline.start() }.exceptionOrNull())
        assertEquals(1, fixture.capture.starts)
        assertSame(primary, fixture.pipeline.clear().exceptionOrNull())
    }

    @Test fun captureInterruptFailureDuringPauseStillAwaitsReleaseAndNeverResumesOrPlays() = runTest {
        val fixture = Fixture(this)
        fixture.pipeline.start()
        runCurrent()
        val primary = IllegalStateException("Microphone interruption failed")
        val secondary = IllegalStateException("Microphone release failed after interruption")
        val release = CompletableDeferred<Unit>()
        fixture.capture.interruptFailure = primary
        fixture.capture.stopFailure = secondary
        fixture.capture.stopGate = release
        fixture.capture.emit(segment())
        runCurrent()
        assertTrue(fixture.capture.stopStarted.isCompleted)
        assertTrue(fixture.capture.microphoneOwned)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertFalse(fixture.pipeline.state.value.captureActive)
        assertEquals(ConversationPhase.ERROR, fixture.pipeline.state.value.phase)
        assertEquals(ConversationFailure.CAPTURE_FAILED, fixture.pipeline.state.value.failure)
        assertEquals(ConversationTranscriptStatus.ERROR, fixture.pipeline.state.value.transcripts.single().status)
        assertEquals(0, fixture.output.started)
        assertTrue(fixture.output.interrupts > 0)
        val stop = async { fixture.pipeline.stop() }
        runCurrent()
        assertFalse(stop.isCompleted)
        release.complete(Unit)
        runCurrent()
        assertSame(primary, stop.await().exceptionOrNull())
        assertSame(secondary, primary.suppressed.single())
        assertFalse(fixture.capture.microphoneOwned)
        assertEquals(1, fixture.capture.starts)
        assertEquals(0, fixture.output.started)
        assertContainsFailure(primary, runCatching { fixture.pipeline.start() }.exceptionOrNull())
        assertSame(primary, fixture.pipeline.clear().exceptionOrNull())
    }

    @Test fun cleanupResultsPreservePrimaryIdentityAndIndependentSharedCauseFailures() = runTest {
        val fixture = Fixture(this)
        fixture.pipeline.start()
        runCurrent()
        val detail = IllegalArgumentException("Shared output detail")
        val primary = IllegalStateException("Output release failed", detail)
        fixture.output.interruptFailure = primary
        fixture.pipeline.interruptCurrent()
        val stop = async { fixture.pipeline.stop() }
        runCurrent()
        assertSame(primary, stop.await().exceptionOrNull())
        assertTrue("Repeated native faults must not suppress the primary onto itself", primary.suppressed.isEmpty())
        val independent = IllegalStateException("Output release failed", detail)
        fixture.output.interruptFailure = independent
        val clear = async { fixture.pipeline.clear() }
        runCurrent()
        assertSame(primary, clear.await().exceptionOrNull())
        assertSame(independent, primary.suppressed.single())
        assertSame(primary, fixture.pipeline.stop().exceptionOrNull())
        assertContainsFailure(primary, runCatching { fixture.pipeline.start() }.exceptionOrNull())
        assertEquals(1, fixture.capture.starts)
    }

    private fun assertContainsFailure(expected: Throwable, actual: Throwable?) {
        assertTrue("Awaited release must expose its failure", actual != null)
        val pending = mutableListOf(requireNotNull(actual))
        val seen = mutableSetOf<Throwable>()
        while (pending.isNotEmpty()) {
            val next = pending.removeAt(pending.lastIndex)
            if (next === expected) return
            if (!seen.add(next)) continue
            next.cause?.let { pending.add(it) }
            pending.addAll(next.suppressed)
        }
        throw AssertionError("Actual release failure was lost: ${expected.message}")
    }

    private class Fixture(scope: CoroutineScope) {
        val capture = Capture()
        val recognition = Recognition()
        val models = Models(recognition)
        val output = Output()
        val translation = Translation()
        val pipeline = ConversationPipeline(capture, recognition, models, translation, output, scope)
    }

    private class Capture : SpeechCaptureEngine {
        var running = false
        var starts = 0
        var interrupts = 0
        var interruptFailure: Throwable? = null
        var stopFailure: Throwable? = null
        val stopStarted = CompletableDeferred<Unit>()
        var failNextStop = false
        var microphoneOwned = false
        var stopGate: CompletableDeferred<Unit>? = null
        private var stopClaimed = false
        private var listener: SpeechCaptureListener? = null
        override suspend fun start(listener: SpeechCaptureListener) {
            check(!running && !microphoneOwned)
            starts++
            running = true
            microphoneOwned = true
            stopClaimed = false
            this.listener = listener
        }
        fun emit(segment: SpeechSegment) { requireNotNull(listener).onSegment(segment) }
        fun fail(reason: SpeechCaptureFailure) { requireNotNull(listener).onFailure(reason) }
        override fun interruptCurrent() {
            interrupts++
            running = false
            interruptFailure?.let { throw it }
        }
        override suspend fun stop(flush: Boolean): SpeechSegment? {
            running = false
            if (!microphoneOwned || stopClaimed) return null
            stopClaimed = true
            stopStarted.complete(Unit)
            stopGate?.await()
            microphoneOwned = false
            if (failNextStop) {
                failNextStop = false
                error("Microphone stop failed")
            }
            stopFailure?.let { throw it }
            return null
        }
        override fun close() { running = false }
    }

    private class Recognition : ConversationRecognitionEngine {
        var gate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        var unsupported = false
        var failNext = false
        var installations = 0
        var result: ConversationRecognitionResult? = null
        override val readinessState = MutableStateFlow(SpeechRecognitionReadiness.READY)
        override suspend fun readiness() = readinessState.value
        override suspend fun install() {
            installations++
            readinessState.value = SpeechRecognitionReadiness.READY
        }
        override suspend fun prepare() { check(readiness() == SpeechRecognitionReadiness.READY) }
        override suspend fun recognize(segment: SpeechSegment): ConversationRecognitionResult {
            val current = gate
            if (current != null) {
                if (ignoreCancellation) withContext(NonCancellable) { current.await() } else current.await()
            }
            if (unsupported) throw UnsupportedSpeechLanguageException()
            if (failNext) { failNext = false; error("Phrase recognition failed") }
            result?.let { return it }
            return RecognizedUtterance(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), "Сегодня без автобуса")
        }
        override fun close() = Unit
    }

    private class Models(private val recognition: Recognition) : ConversationModelRepository {
        var ready = true
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install() { ready = true; recognition.install() }
        override suspend fun isConversationReady() = ready && recognition.readiness() == SpeechRecognitionReadiness.READY
        override fun close() = Unit
    }

    private class Output : ConversationSpeechOutput {
        var ready = SpeechOutputReadiness.READY
        var readyAfterPrepare = SpeechOutputReadiness.READY
        override suspend fun readiness() = ready
        var started = 0
        var fail = false
        var interrupts = 0
        var interruptFailure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var start = SpeechPlaybackStart(1_500_000_000L, measured = true)
        override suspend fun prepare() { ready = readyAfterPrepare }
        override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
            if (fail) error("Output device unavailable")
            started++
            onPlaybackStarted(start)
            gate?.await()
        }
        override fun interruptCurrent() {
            interrupts++
            interruptFailure?.let { throw it }
        }
        override fun close() = Unit
    }

    private class Translation : TranslationEngine {
        override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.READY))
        var calls = 0
        var preparationGate: CompletableDeferred<Unit>? = null
        override suspend fun modelAvailability() = ModelAvailability.READY
        override suspend fun prepare(): ModelAvailability {
            preparationGate?.await()
            return ModelAvailability.READY
        }
        override suspend fun translate(pair: LanguagePair, text: String): String { calls++; return "No bus today" }
        override fun close() = Unit
    }

    private fun segment(end: Long? = null, measured: Boolean = false) =
        SpeechSegment.fromPcm(shortArrayOf(1, 2, 3), speechEndNanos = end, speechEndMeasured = measured)
}
