package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.SpeechPlaybackStart
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AndroidSpeechPlaybackTest {
    @Test fun enqueueDoesNotReportStartAndPresentationTimestampTargetsTheFirstNonzeroFrame() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = player(track, clock).open(onStarted = starts::add)
        session.append(floatArrayOf(0f, 0f, 0.5f, 1f)) {}
        assertTrue(starts.isEmpty())

        clock.advanceMillis(20)
        track.presentedFrames.set(4)
        track.timestampAvailable = true
        track.timestampNanos = clock.now()
        session.observe()
        session.observe()
        session.drain {}

        assertEquals(1, starts.size)
        assertTrue(starts.single().measured)
        assertEquals(clock.now() - 2L * 1_000_000_000 / 22_050, starts.single().presentationTimeNanos)
        session.close()
    }

    @Test fun headProgressWithoutTimestampIsExplicitlyEstimatedAndStillReportsOnlyOnce() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = player(track, clock).open(onStarted = starts::add)
        session.append(floatArrayOf(0f, 1f)) {}
        clock.advanceMillis(20)
        track.presentedFrames.set(2)
        session.observe()
        val firstEvidenceEstimate = clock.now() - 1_000_000_000L / SpeechPcmBuffer.SAMPLE_RATE
        assertTrue(starts.isEmpty())
        clock.advanceMillis(150)
        session.observe()
        session.observe()
        assertEquals(1, starts.size)
        assertFalse(starts.single().measured)
        assertEquals(firstEvidenceEstimate, starts.single().presentationTimeNanos)
        session.drain {}
        session.close()
    }

    @Test fun futureOrUnpresentedTimestampCannotManufactureMeasuredEvidence() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = player(track, clock).open(onStarted = starts::add)
        session.append(floatArrayOf(1f, 1f)) {}
        track.presentedFrames.set(2)
        track.timestampAvailable = true
        track.timestampNanos = clock.now() + 1_000_000_000
        session.observe()
        clock.advanceMillis(150)
        session.observe()
        assertFalse(starts.single().measured)
        session.close()
    }

    @Test fun partialAndZeroWritesReuseThePcmChunkAndDrainWaitsForPlaybackProgress() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply {
            maximumWrite = 1
            zeroWritesRemaining = 1
            timestampAvailable = true
        }
        val playback = AndroidSpeechPlayback({ track }, { TestSpeechAudioFocus() }, clock::now) { millis ->
            clock.advanceMillis(millis)
            if (track.writtenFrames.get() > track.presentedFrames.get()) {
                track.presentedFrames.incrementAndGet()
                track.timestampNanos = clock.now()
            }
        }
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = playback.open(onStarted = starts::add)
        session.append(floatArrayOf(0f, 0.5f, -1f, 1f)) {}
        assertEquals(4, track.writtenFrames.get())
        for (buffer in track.writeBuffers) assertSame(track.writeBuffers.first(), buffer)
        assertEquals(0L, track.presentedFrames.get())
        session.drain {}
        assertEquals(4L, track.presentedFrames.get())
        assertEquals(1, starts.size)
        assertTrue(starts.single().measured)
        session.close()
    }

    @Test fun stalledAndSilentPlaybackAreErrorsRatherThanSuccessfulMissingAudio() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val session = player(track, clock).open {}
        session.append(floatArrayOf(1f)) {}
        clock.advanceMillis(2_001)
        assertThrows(IllegalStateException::class.java) { session.observe() }
        session.close()

        val silent = player(TestSpeechAudioTrack(clock::now), clock).open {}
        silent.append(floatArrayOf(0f, 0f)) {}
        assertThrows(IllegalStateException::class.java) { silent.drain {} }
        silent.close()
    }

    @Test fun invalidPcmAndAudioTrackWriteErrorsPropagateBeforeAnyFalseStart() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val session = player(track, clock).open { throw AssertionError("No presented audio") }
        assertThrows(IllegalArgumentException::class.java) { session.append(floatArrayOf(1f, Float.NaN)) {} }
        assertEquals(0, track.playCalls.get())
        track.writeError = -6
        assertThrows(IllegalStateException::class.java) { session.append(floatArrayOf(1f)) {} }
        session.close()
    }

    @Test fun interruptionSynchronouslyPausesFlushesAndNeverAllowsASecondPlay() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val focus = TestSpeechAudioFocus()
        val playback = player(track, clock, focus)
        val session = playback.open {}
        assertThrows(IllegalStateException::class.java) { playback.open {} }
        session.append(floatArrayOf(1f)) {}
        session.interrupt()
        session.interrupt()
        assertEquals(1, track.pauseCalls.get())
        assertEquals(1, track.flushCalls.get())
        assertThrows(CancellationException::class.java) { session.append(floatArrayOf(1f)) {} }
        assertEquals(1, focus.abandonCalls.get())
        assertEquals(1, track.playCalls.get())
        session.close()
        session.close()
        assertEquals(1, track.releaseCalls.get())
        assertEquals(1, focus.abandonCalls.get())
        playback.open {}.close()
        assertEquals(2, track.releaseCalls.get())
    }

    @Test fun focusDenialAndLossDoNotResumeOrHideAnAudioFailure() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val denied = TestSpeechAudioFocus().apply { granted = false }
        assertThrows(IllegalStateException::class.java) { player(track, clock, denied).open {} }
        assertEquals(0, track.playCalls.get())
        assertEquals(1, track.releaseCalls.get())
        assertEquals(1, denied.abandonCalls.get())

        val focusedTrack = TestSpeechAudioTrack(clock::now)
        val focus = TestSpeechAudioFocus()
        val session = player(focusedTrack, clock, focus).open {}
        session.append(floatArrayOf(1f)) {}
        focus.lose()
        assertEquals(1, focusedTrack.pauseCalls.get())
        assertEquals(1, focusedTrack.flushCalls.get())
        assertThrows(IllegalStateException::class.java) { session.append(floatArrayOf(1f)) {} }
        assertEquals(1, focusedTrack.playCalls.get())
        session.close()
    }

    @Test fun stopFailureStillSynchronouslyReleasesTrackAndIsObservable() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply { failPause = true }
        val session = player(track, clock).open {}
        session.append(floatArrayOf(1f)) {}
        assertThrows(IllegalStateException::class.java) { session.interrupt() }
        assertEquals(1, track.flushCalls.get())
        assertEquals(1, track.releaseCalls.get())
        assertThrows(IllegalStateException::class.java) { session.observe() }
        session.close()
        assertEquals(1, track.releaseCalls.get())
    }

    @Test fun zeroWritesTimeOutEvenWhenNothingHasEverBeenSubmitted() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply { zeroWritesRemaining = Int.MAX_VALUE }
        val session = player(track, clock).open { throw AssertionError("No sample was rendered") }
        val failure = assertThrows(IllegalStateException::class.java) { session.append(floatArrayOf(1f)) {} }
        assertEquals("AudioTrack stopped accepting speech PCM", failure.message)
        assertEquals(0, track.writtenFrames.get())
        assertEquals(0, track.playCalls.get())
        session.close()
    }

    @Test fun leadingSilenceAndOutOfRangeNativePositionsAreNotMeasuredOutputEvidence() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply { timestampAvailable = true }
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = player(track, clock).open(onStarted = starts::add)
        session.append(floatArrayOf(0f, 0f, 1f)) {}
        clock.advanceMillis(20)
        track.timestampNanos = clock.now()
        track.presentedFrames.set(2)
        track.timestampFramePosition = 3
        session.observe()
        assertTrue(starts.isEmpty())
        track.presentedFrames.set(3)
        session.observe()
        assertTrue(starts.single().measured)
        session.close()

        for (invalidFrame in listOf(0L, 3L)) {
            val invalidTrack = TestSpeechAudioTrack(clock::now).apply {
                timestampAvailable = true
                timestampFramePosition = invalidFrame
            }
            val invalidStarts = mutableListOf<SpeechPlaybackStart>()
            val invalidSession = player(invalidTrack, clock).open(onStarted = invalidStarts::add)
            invalidSession.append(floatArrayOf(0f, 1f)) {}
            clock.advanceMillis(20)
            invalidTrack.presentedFrames.set(2)
            invalidTrack.timestampNanos = clock.now()
            invalidSession.observe()
            clock.advanceMillis(150)
            invalidSession.observe()
            assertFalse(invalidStarts.single().measured)
            invalidSession.close()
        }
    }

    @Test fun timestampWhoseFirstSampleWouldPredatePlayIsRejected() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val starts = mutableListOf<SpeechPlaybackStart>()
        val session = player(track, clock).open(onStarted = starts::add)
        session.append(floatArrayOf(1f, 1f)) {}
        track.presentedFrames.set(2)
        track.timestampAvailable = true
        track.timestampNanos = clock.now()
        session.observe()
        assertTrue(starts.isEmpty())
        clock.advanceMillis(150)
        session.observe()
        assertFalse(starts.single().measured)
        session.close()
    }

    @Test fun focusLostDuringAcquisitionCannotBeFollowedByPlayAndIsAbandonedImmediately() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now)
        val focus = TestSpeechAudioFocus().apply { loseDuringRequest = true }
        assertThrows(IllegalStateException::class.java) { player(track, clock, focus).open {} }
        assertEquals(0, track.playCalls.get())
        assertEquals(1, track.flushCalls.get())
        assertEquals(1, track.releaseCalls.get())
        assertEquals(1, focus.abandonCalls.get())
    }

    @Test fun uninitializedTrackAndFocusCreationFailureReleaseWithoutClaimingOwnership() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply { isInitialized = false }
        assertThrows(IllegalStateException::class.java) { player(track, clock).open {} }
        assertEquals(1, track.releaseCalls.get())
        track.isInitialized = true
        val playback = AndroidSpeechPlayback({ track }, { throw IllegalStateException("Focus service missing") })
        assertThrows(IllegalStateException::class.java) { playback.open {} }
        assertEquals(2, track.releaseCalls.get())
        player(track, clock).open {}.close()
        assertEquals(3, track.releaseCalls.get())
    }

    @Test fun flushAndFocusReleaseFailuresStillReleaseTheTrackExactlyOnce() {
        for (focusFailure in listOf(false, true)) {
            val clock = TestPlaybackClock()
            val track = TestSpeechAudioTrack(clock::now).apply { failFlush = !focusFailure }
            val focus = TestSpeechAudioFocus().apply { failAbandon = focusFailure }
            val session = player(track, clock, focus).open {}
            session.append(floatArrayOf(1f)) {}
            assertThrows(IllegalStateException::class.java) { session.interrupt() }
            assertEquals(1, track.releaseCalls.get())
            assertEquals(1, focus.abandonCalls.get())
            session.close()
            assertEquals(1, track.releaseCalls.get())
            assertEquals(1, focus.abandonCalls.get())
        }
    }

    @Test fun underrunOrUnknownContinuityCannotTurnTimestampExtrapolationIntoMeasuredEvidence() {
        for (underruns in listOf(-1, 1)) {
            val clock = TestPlaybackClock()
            val track = TestSpeechAudioTrack(clock::now).apply { timestampAvailable = true }
            val starts = mutableListOf<SpeechPlaybackStart>()
            val session = player(track, clock).open(onStarted = starts::add)
            session.append(floatArrayOf(0f, 1f)) {}
            clock.advanceMillis(20)
            track.presentedFrames.set(2)
            track.timestampNanos = clock.now()
            track.underruns = underruns
            session.observe()
            assertTrue(starts.isEmpty())
            clock.advanceMillis(150)
            session.observe()
            assertFalse(starts.single().measured)
            session.close()
        }
    }

    @Test fun firstBoundedChunkIsSubmittedBeforePlayRatherThanStartingAnEmptyTrack() {
        val clock = TestPlaybackClock()
        val track = TestSpeechAudioTrack(clock::now).apply { zeroWritesRemaining = 1 }
        val orderedTrack = object : SpeechAudioTrack by track {
            override fun play() {
                assertEquals(SpeechPcmBuffer.CHUNK_FRAMES, track.writtenFrames.get())
                assertEquals(0, track.playCalls.get())
                track.play()
            }
        }
        val playback = AndroidSpeechPlayback({ orderedTrack }, { TestSpeechAudioFocus() }, clock::now, clock::advanceMillis)
        val session = playback.open {}
        session.append(FloatArray(SpeechPcmBuffer.CHUNK_FRAMES + 1) { .5f }) {}
        assertEquals(SpeechPcmBuffer.CHUNK_FRAMES + 1, track.writtenFrames.get())
        assertEquals(1, track.playCalls.get())
        session.close()
    }

    private fun player(
        track: TestSpeechAudioTrack,
        clock: TestPlaybackClock,
        focus: TestSpeechAudioFocus = TestSpeechAudioFocus(),
    ) = AndroidSpeechPlayback({ track }, { focus }, clock::now, clock::advanceMillis)
}

internal class TestPlaybackClock {
    private val nanos = AtomicLong(1_000_000_000)
    fun now(): Long = nanos.get()
    fun advanceMillis(millis: Long) { nanos.addAndGet(millis * 1_000_000) }
}

internal class TestSpeechAudioTrack(private val now: () -> Long = System::nanoTime) : SpeechAudioTrack {
    override var isInitialized = true
    val playCalls = AtomicInteger()
    val pauseCalls = AtomicInteger()
    val flushCalls = AtomicInteger()
    val releaseCalls = AtomicInteger()
    val writtenFrames = AtomicInteger()
    val presentedFrames = AtomicLong()
    val writeBuffers = mutableListOf<ShortArray>()
    var maximumWrite = Int.MAX_VALUE
    var zeroWritesRemaining = 0
    var writeError: Int? = null
    var failPause = false
    var failFlush = false
    var advanceOnWrite = false
    @Volatile var timestampAvailable = false
    @Volatile var timestampNanos = 0L
    @Volatile var timestampFramePosition: Long? = null
    @Volatile var underruns = 0
    override fun play() { playCalls.incrementAndGet() }
    override fun write(samples: ShortArray, offset: Int, count: Int): Int {
        writeBuffers.add(samples)
        writeError?.let { return it }
        if (zeroWritesRemaining > 0) {
            zeroWritesRemaining--
            return 0
        }
        val result = minOf(count, maximumWrite)
        writtenFrames.addAndGet(result)
        if (advanceOnWrite) {
            presentedFrames.addAndGet(result.toLong())
            timestampNanos = now()
        }
        return result
    }
    override fun playbackHeadFrames(): Long = presentedFrames.get()
    override fun timestamp(destination: SpeechAudioTimestamp): Boolean {
        destination.framePosition = timestampFramePosition ?: presentedFrames.get()
        destination.nanoTime = timestampNanos
        return timestampAvailable
    }
    override fun underrunCount(): Int = underruns
    override fun pause() {
        pauseCalls.incrementAndGet()
        if (failPause) throw IllegalStateException("Audio device lost")
    }
    override fun flush() {
        flushCalls.incrementAndGet()
        if (failFlush) throw IllegalStateException("Audio flush failed")
    }
    override fun release() { releaseCalls.incrementAndGet() }
}

internal class TestSpeechAudioFocus : SpeechAudioFocus {
    var granted = true
    var loseDuringRequest = false
    var failAbandon = false
    private var onLoss: (() -> Unit)? = null
    val abandonCalls = AtomicInteger()
    override fun request(onLoss: () -> Unit): Boolean {
        this.onLoss = onLoss
        if (loseDuringRequest) onLoss()
        return granted
    }
    fun lose() { requireNotNull(onLoss).invoke() }
    override fun abandon() {
        abandonCalls.incrementAndGet()
        if (failAbandon) throw IllegalStateException("Audio focus release failed")
    }
}
