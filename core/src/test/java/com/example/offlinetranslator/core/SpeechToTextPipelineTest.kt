package com.example.offlinetranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechToTextPipelineTest {
    @Test fun thousandfoldSegmentBurstHasBoundedQueueAndClearRejectsLateCallbacks() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<String>()
        fixture.recognition.blockNext = gate
        fixture.recognition.ignoreCancellationOnGate = true
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(1));runCurrent()
        repeat(32000) { fixture.capture.emit(segment(2)) }
        val held = fixture.pipeline.state.value
        assertEquals(3,held.queuedSegments);assertEquals(31997,held.droppedSegments)
        assertTrue(held.queueOverflow);assertTrue(held.transcripts.size <= 30)
        fixture.pipeline.clear();fixture.capture.emit(segment(3));gate.complete("late");runCurrent()
        assertEquals(0,fixture.pipeline.state.value.queuedSegments)
        assertTrue(fixture.pipeline.state.value.transcripts.isEmpty());assertEquals(0,fixture.translation.calls)
    }
    @Test
    fun permissionFailureKeepsItsSpecificReason() = runTest {
        val fixture = Fixture(this)
        fixture.capture.startFailure = SpeechCaptureException(SpeechCaptureFailure.PERMISSION_DENIED)
        assertFalse(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        assertEquals(SpeechFailureReason.PERMISSION_DENIED, fixture.pipeline.state.value.error?.reason)
        fixture.pipeline.clear()
    }

    @Test
    fun segmentsAreBoundedAndOwnTheirPcm() {
        val pcm = shortArrayOf(1, 2, 3)
        val segment = SpeechSegment.fromPcm(pcm)
        pcm[0] = 9
        val copy = segment.copyPcm()
        copy[1] = 8

        assertEquals(1, segment.copyPcm()[0].toInt())
        assertEquals(16_000, segment.sampleRateHz)
        assertEquals(2, segment.copyPcm()[1].toInt())
        assertEquals(SpeechSegment.MAX_DURATION_MILLIS.toLong(), SpeechSegment.fromPcm(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT)).durationMillis)
        assertThrows<IllegalArgumentException> {
            SpeechSegment.fromPcm(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT + 1))
        }
        assertThrows<IllegalArgumentException> { SpeechSegment.fromPcm(shortArrayOf(1), sampleRateHz = 8_000) }
    }

    @Test
    fun processesBothDirectionsInOrderAndFlushesOnStop() = runTest {
        val fixture = Fixture(this)
        fixture.capture.finalSegment = segment(3)

        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(1))
        fixture.capture.emit(segment(2))
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()

        assertEquals(listOf("heard-1", "heard-2", "heard-3"), fixture.pipeline.state.value.transcripts.map { it.original })
        assertEquals(listOf("heard-1 translated", "heard-2 translated", "heard-3 translated"), fixture.pipeline.state.value.transcripts.map { it.translation })
        assertEquals(1, fixture.capture.stopCalls)
        assertEquals(SpeechPipelinePhase.READY, fixture.pipeline.state.value.phase)

        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)))
        fixture.capture.emit(segment(4))
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()
        assertEquals(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN), fixture.pipeline.state.value.transcripts.last().direction)
        assertEquals(4, fixture.pipeline.state.value.transcripts.last().original.removePrefix("heard-").toInt())
        fixture.pipeline.clear()
    }

    @Test
    fun stoppedSessionRejectsNewDirectionUntilDelayedWorkerDrains() = runTest {
        val fixture = Fixture(this)
        val recognitionGate = CompletableDeferred<String>()
        fixture.recognition.blockNext = recognitionGate

        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        assertTrue(fixture.pipeline.state.value.sessionActive)
        assertTrue(fixture.pipeline.state.value.captureActive)
        fixture.capture.emit(segment(5))
        runCurrent()

        fixture.pipeline.stop()
        runCurrent()
        assertEquals(SpeechPipelinePhase.STOPPING, fixture.pipeline.state.value.phase)
        assertTrue(fixture.pipeline.state.value.sessionActive)
        assertFalse(fixture.pipeline.state.value.captureActive)
        assertFalse(fixture.pipeline.start(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)))
        assertEquals(1, fixture.capture.startCalls)

        recognitionGate.complete("heard-5")
        runCurrent()
        assertEquals(SpeechPipelinePhase.READY, fixture.pipeline.state.value.phase)
        assertFalse(fixture.pipeline.state.value.sessionActive)
        assertEquals("heard-5 translated", fixture.pipeline.state.value.transcripts.single().translation)
        fixture.pipeline.clear()
    }

    @Test
    fun cancelledStopCommitsFinalTailAndDrainsBeforeRestart() = runTest {
        val fixture = Fixture(this)
        val recognitionGate = CompletableDeferred<String>()
        val captureRelease = CompletableDeferred<Unit>()
        val direction = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        fixture.recognition.blockNext = recognitionGate
        fixture.capture.stopGate = captureRelease
        fixture.capture.finalSegment = segment(3)
        try {
            assertTrue(fixture.pipeline.start(direction))
            fixture.capture.emit(segment(1))
            runCurrent()
            fixture.recognition.blockNext = null
            fixture.capture.emit(segment(2))
            var stopReturned = false
            val stop = async {
                fixture.pipeline.stop()
                stopReturned = true
            }
            runCurrent()
            assertTrue(fixture.capture.stopStarted.isCompleted)
            assertTrue(fixture.capture.microphoneOwned)
            stop.cancel()
            runCurrent()
            assertFalse("Cancellation cannot finish before capture release", stop.isCompleted)
            captureRelease.complete(Unit)
            runCurrent()
            assertTrue(stop.isCompleted)
            assertTrue(stop.isCancelled)
            assertFalse("Stop must propagate caller cancellation after committing", stopReturned)
            assertFalse(fixture.capture.microphoneOwned)
            fixture.pipeline.stop()
            assertEquals("Repeated Stop must not flush again", 1, fixture.capture.stopCalls)
            assertFalse(fixture.pipeline.start(direction))
            recognitionGate.complete("heard-1")
            runCurrent()
            assertEquals(listOf("heard-1", "heard-2", "heard-3"), fixture.pipeline.state.value.transcripts.map { it.original })
            assertEquals(listOf("heard-1 translated", "heard-2 translated", "heard-3 translated"),
                fixture.pipeline.state.value.transcripts.map { it.translation })
            assertEquals(3, fixture.recognition.calls)
            assertFalse(fixture.pipeline.state.value.sessionActive)
            assertTrue("Drained Stop must allow restart without Clear", fixture.pipeline.start(direction))
        } finally {
            recognitionGate.complete("heard-1")
            captureRelease.complete(Unit)
            fixture.pipeline.clear()
            runCurrent()
        }
    }

    @Test
    fun captureCancellationClosesSessionAndRethrowsWithoutSecondFlush() = runTest {
        val fixture = Fixture(this)
        val direction = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val failure = CancellationException("Capture stop cancelled")
        fixture.capture.stopFailure = failure
        try {
            assertTrue(fixture.pipeline.start(direction))
            val stop = async { fixture.pipeline.stop() }
            runCurrent()
            assertTrue("Capture cancellation must cancel the Stop caller", stop.isCancelled)
            val result = runCatching { stop.await() }
            assertTrue("Capture cancellation must propagate", result.exceptionOrNull() is CancellationException)
            fixture.pipeline.stop()
            assertEquals(1, fixture.capture.stopCalls)
            runCurrent()
            assertFalse(fixture.capture.microphoneOwned)
            assertFalse(fixture.pipeline.state.value.sessionActive)
            assertEquals(SpeechPipelinePhase.ERROR, fixture.pipeline.state.value.phase)
            assertEquals(SpeechPipelineError(SpeechFailureStage.CAPTURE, SpeechFailureReason.MICROPHONE_UNAVAILABLE),
                fixture.pipeline.state.value.error)
            assertTrue("Capture failure must allow restart without Clear", fixture.pipeline.start(direction))
        } finally {
            fixture.capture.stopFailure = null
            fixture.pipeline.clear()
            runCurrent()
        }
    }

    @Test
    fun queueOverflowIsVisibleAndBounded() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.blockNext = CompletableDeferred()
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(1))
        runCurrent()
        repeat(4) { fixture.capture.emit(segment(it + 2)) }

        assertEquals(SpeechToTextPipeline.MAX_QUEUED_SEGMENTS, fixture.pipeline.state.value.queuedSegments)
        assertTrue(fixture.pipeline.state.value.queueOverflow)
        assertEquals(1, fixture.pipeline.state.value.droppedSegments)

        fixture.recognition.blockNext!!.complete("heard-1")
        fixture.recognition.blockNext = null
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()
        assertEquals(4, fixture.pipeline.state.value.transcripts.size)
        fixture.pipeline.clear()
    }

    @Test
    fun recognitionFailureRetryRepeatsOnlyRecognition() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.failNext = true
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(1))
        runCurrent()

        assertEquals(SpeechFailureStage.RECOGNITION, fixture.pipeline.state.value.error?.stage)
        assertTrue(fixture.pipeline.retry())
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()

        assertEquals(2, fixture.recognition.calls)
        assertEquals(1, fixture.translation.calls)
        assertEquals(SpeechTranscriptStatus.COMPLETE, fixture.pipeline.state.value.transcripts.single().status)
        fixture.pipeline.clear()
    }

    @Test
    fun emptyRecognitionResultIsRetainedForAsrRetry() = runTest {
        val fixture = Fixture(this)
        fixture.recognition.nextResult = "   "
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(9))
        runCurrent()

        assertEquals(SpeechFailureReason.EMPTY_TRANSCRIPT, fixture.pipeline.state.value.error?.reason)
        assertEquals(0, fixture.translation.calls)
        fixture.recognition.nextResult = "heard-9"
        assertTrue(fixture.pipeline.retry())
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()

        assertEquals(2, fixture.recognition.calls)
        assertEquals("heard-9 translated", fixture.pipeline.state.value.transcripts.single().translation)
        fixture.pipeline.clear()
    }

    @Test
    fun translationFailureRetryKeepsOriginalAndDoesNotRepeatAsr() = runTest {
        val fixture = Fixture(this)
        fixture.translation.failNext = true
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)))
        fixture.capture.emit(segment(7))
        runCurrent()

        val beforeRetry = fixture.pipeline.state.value.transcripts.single()
        assertEquals("heard-7", beforeRetry.original)
        assertNull(beforeRetry.translation)
        assertEquals(SpeechFailureStage.TRANSLATION, fixture.pipeline.state.value.error?.stage)

        assertTrue(fixture.pipeline.retry())
        runCurrent()
        fixture.pipeline.stop()
        runCurrent()

        assertEquals(1, fixture.recognition.calls)
        assertEquals(2, fixture.translation.calls)
        assertEquals("heard-7 translated", fixture.pipeline.state.value.transcripts.single().translation)
        fixture.pipeline.clear()
    }

    @Test
    fun clearDropsLateRecognitionResultsAndCurrentTranscript() = runTest {
        val fixture = Fixture(this)
        val gate = CompletableDeferred<String>()
        fixture.recognition.blockNext = gate
        fixture.recognition.ignoreCancellationOnGate = true
        assertTrue(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        fixture.capture.emit(segment(8))
        runCurrent()

        fixture.pipeline.clear()
        gate.complete("late result")
        runCurrent()

        assertTrue(fixture.pipeline.state.value.transcripts.isEmpty())
        assertEquals(SpeechPipelinePhase.READY, fixture.pipeline.state.value.phase)
        assertEquals(0, fixture.translation.calls)
    }

    @Test
    fun missingSpeechModelsPreventMicrophoneStart() = runTest {
        val fixture = Fixture(this)
        fixture.models.ready = false

        assertFalse(fixture.pipeline.start(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)))
        assertEquals(0, fixture.capture.startCalls)
        assertEquals(SpeechFailureReason.SPEECH_MODELS_MISSING, fixture.pipeline.state.value.error?.reason)
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val capture = FakeCapture()
        val recognition = FakeRecognition()
        val translation = FakeTranslation()
        val models = FakeModels()
        val pipeline = SpeechToTextPipeline(capture, recognition, models, translation, scope)
    }

    private class FakeCapture : SpeechCaptureEngine {
        override fun interruptCurrent() = Unit
        private var listener: SpeechCaptureListener? = null
        var startCalls = 0
        var stopCalls = 0
        var finalSegment: SpeechSegment? = null
        var startFailure: SpeechCaptureException? = null
        var microphoneOwned = false
        val stopStarted = CompletableDeferred<Unit>()
        var stopGate: CompletableDeferred<Unit>? = null
        var stopFailure: Throwable? = null

        override suspend fun start(listener: SpeechCaptureListener) {
            startCalls++
            startFailure?.let { throw it }
            check(!microphoneOwned)
            microphoneOwned = true
            this.listener = listener
        }

        override suspend fun stop(flush: Boolean): SpeechSegment? {
            stopCalls++
            stopStarted.complete(Unit)
            withContext(NonCancellable) {
                stopGate?.await()
                microphoneOwned = false
            }
            stopFailure?.let { throw it }
            val pending = finalSegment
            finalSegment = null
            return if (flush) pending else null
        }

        fun emit(segment: SpeechSegment) = requireNotNull(listener).onSegment(segment)

        override fun close() = Unit
    }

    private class FakeRecognition : SpeechRecognitionEngine {
        var calls = 0
        var failNext = false
        var blockNext: CompletableDeferred<String>? = null
        var ignoreCancellationOnGate = false
        var nextResult: String? = null

        override suspend fun recognize(pair: LanguagePair, segment: SpeechSegment): String {
            calls++
            if (failNext) {
                failNext = false
                error("ASR failed")
            }
            val gate = blockNext
            if (gate != null) {
                val result = if (ignoreCancellationOnGate) {
                    withContext(NonCancellable) { gate.await() }
                } else {
                    gate.await()
                }
                if (result.isNotEmpty()) return result
            }
            nextResult?.let {
                nextResult = null
                return it
            }
            return "heard-${segment.copyPcm().first()}"
        }

        override fun close() = Unit
    }

    private class FakeTranslation : TranslationEngine {
        override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.READY))
        var calls = 0
        var failNext = false

        override suspend fun modelAvailability() = ModelAvailability.READY
        override suspend fun prepare() = ModelAvailability.READY
        override suspend fun translate(pair: LanguagePair, text: String): String {
            calls++
            if (failNext) {
                failNext = false
                error("MT failed")
            }
            return "$text translated"
        }

        override fun close() = Unit
    }

    private class FakeModels : SpeechModelRepository {
        var ready = true
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install() = Unit
        override suspend fun isReady(pair: LanguagePair) = ready
        override fun close() = Unit
    }

    private fun segment(value: Int) = SpeechSegment.fromPcm(shortArrayOf(value.toShort()))

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (actual: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got ${actual::class.java.simpleName}", actual is T)
            return
        }
        fail("Expected ${T::class.java.simpleName}")
    }
}
