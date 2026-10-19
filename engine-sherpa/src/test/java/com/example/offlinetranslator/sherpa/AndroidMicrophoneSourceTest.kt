package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.SpeechCaptureException
import com.example.offlinetranslator.core.SpeechCaptureFailure
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertNull
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AndroidMicrophoneSourceTest {
    @Test fun duplicateOpenIsRejectedAndStopReleaseAreIdempotent() {
        val record = FakeAudioRecord()
        val source = source(record)
        val session = source.open()

        assertThrows(SpeechCaptureException::class.java) { source.open() }
        session.stop()
        session.stop()
        session.release()
        session.release()

        assertEquals(1, record.startCalls)
        assertEquals(1, record.stopCalls)
        assertEquals(1, record.releaseCalls)

        source.open().release()
        assertEquals(2, record.startCalls)
        assertEquals(2, record.stopCalls)
        assertEquals(2, record.releaseCalls)
    }

    @Test fun permissionRevokedBeforeOpenDoesNotCreateAudioRecord() {
        val granted = AtomicBoolean(true)
        val record = FakeAudioRecord()
        val source = AndroidMicrophoneSource(
            { granted.get() },
            AudioRecordBoundaryFactory { record },
        )
        granted.set(false)

        val failure = assertThrows(SpeechCaptureException::class.java) { source.open() }

        assertEquals(SpeechCaptureFailure.PERMISSION_DENIED, failure.reason)
        assertEquals(0, record.startCalls)
        assertEquals(0, record.releaseCalls)
    }

    @Test fun permissionRevokedDuringReadReleasesTheSession() = runTest {
        val granted = AtomicBoolean(true)
        val record = FakeAudioRecord {
            if (!granted.get()) throw SecurityException("recording app-op revoked")
            0
        }
        val session = AndroidMicrophoneSource(
            { granted.get() },
            AudioRecordBoundaryFactory { record },
        ).open()
        granted.set(false)

        val failure = assertThrows(SpeechCaptureException::class.java) {
            kotlinx.coroutines.runBlocking { session.read(ShortArray(64)) }
        }

        assertEquals(SpeechCaptureFailure.PERMISSION_DENIED, failure.reason)
        assertTrue(session.isStopped)
        assertEquals(1, record.stopCalls)
        assertEquals(1, record.releaseCalls)
    }

    @Test fun audioRecordReadErrorIsTerminalAndReleasesTheSession() = runTest {
        val record = FakeAudioRecord { -6 }
        val session = source(record).open()

        val failure = assertThrows(SpeechCaptureException::class.java) {
            kotlinx.coroutines.runBlocking { session.read(ShortArray(64)) }
        }

        assertEquals(SpeechCaptureFailure.READ_FAILED, failure.reason)
        assertTrue(session.isStopped)
        assertEquals(1, record.stopCalls)
        assertEquals(1, record.releaseCalls)
    }

    @Test fun cancelledReadStopsAndReleasesTheSession() = runTest {
        val enteredRead = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val record = FakeAudioRecord {
            enteredRead.countDown()
            check(continueRead.await(2, TimeUnit.SECONDS))
            1
        }
        val session = source(record).open()
        val read = launch { session.read(ShortArray(64)) }
        assertTrue(withContext(Dispatchers.IO) { enteredRead.await(2, TimeUnit.SECONDS) })

        read.cancel()
        continueRead.countDown()
        read.cancelAndJoin()

        assertTrue(session.isStopped)
        assertEquals(1, record.stopCalls)
        assertEquals(1, record.releaseCalls)
    }

    @Test fun vadEndSampleUsesMeasuredAudioRecordMonotonicClock() = runTest {
        val record = FakeAudioRecord { 1_600 }
        record.timestampFrame = 2_000
        record.timestampNanos = 2_000_000_000L
        val session = source(record).open()
        assertNull(session.speechEndTime(800))
        session.read(ShortArray(1_600))
        assertEquals(1_925_000_000L to true, session.speechEndTime(800))
        assertNull(session.speechEndTime(1_601))
        record.timestampFrame = null
        session.read(ShortArray(1_600))
        assertEquals(2_075_000_000L to true, session.speechEndTime(3_200))
        session.release()
    }

    @Test fun missingPlatformTimestampIsExplicitlyEstimatedAtVadEndNotCallbackStart() = runTest {
        val record = FakeAudioRecord { 1_600 }
        val session = AndroidMicrophoneSource(
            { true }, AudioRecordBoundaryFactory { record }, { 2_000_000_000L },
        ).open()
        session.read(ShortArray(1_600))
        assertEquals(1_950_000_000L to false, session.speechEndTime(800))
        assertEquals(2_000_000_000L to false, session.speechEndTime(1_600))
        assertNull(session.speechEndTime(-1))
        session.release()
    }

    @Test fun invalidPlatformTimestampNeverProducesMeasuredSpeechEnd() = runTest {
        val record = FakeAudioRecord { 1_600 }
        val session = AndroidMicrophoneSource(
            { true }, AudioRecordBoundaryFactory { record }, { 2_000_000_000L },
        ).open()
        record.timestampFrame = -1
        record.timestampNanos = 2_000_000_000L
        session.read(ShortArray(1_600))
        assertEquals(1_950_000_000L to false, session.speechEndTime(800))
        record.timestampFrame = 3_200
        record.timestampNanos = 0
        session.read(ShortArray(1_600))
        assertEquals(2_000_000_000L to false, session.speechEndTime(3_200))
        session.release()
    }

    private fun source(record: FakeAudioRecord) =
        AndroidMicrophoneSource({ true }, AudioRecordBoundaryFactory { record })

    private class FakeAudioRecord(
        private val readAction: () -> Int = { 0 },
    ) : AudioRecordBoundary {
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        var releaseCalls = 0
            private set
        override val isInitialized: Boolean = true
        var timestampFrame: Long? = null
        var timestampNanos: Long = 0

        override fun startRecording() { startCalls++ }
        override fun read(buffer: ShortArray): Int = readAction()
        override fun captureTimestamp(timestamp: CaptureAudioTimestamp): Boolean {
            timestamp.framePosition = timestampFrame ?: return false
            timestamp.nanoTime = timestampNanos
            return true
        }
        override fun stop() { stopCalls++ }
        override fun release() { releaseCalls++ }
    }
}
