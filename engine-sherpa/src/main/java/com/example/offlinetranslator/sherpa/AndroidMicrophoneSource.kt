package com.example.offlinetranslator.sherpa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import com.example.offlinetranslator.core.SpeechCaptureFailure
import com.example.offlinetranslator.core.SpeechCaptureException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one foreground microphone. The caller must stop it when the screen leaves the foreground. */
internal class AndroidMicrophoneSource private constructor(
    private val hasPermission: () -> Boolean,
    private val createRecord: () -> AudioRecordBoundary?,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    constructor(context: Context) : this(
        hasPermission = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
        createRecord = { createPlatformRecord(context) },
    )

    internal constructor(
        hasPermission: () -> Boolean,
        factory: AudioRecordBoundaryFactory,
        clockNanos: () -> Long = System::nanoTime,
    ) : this(hasPermission, factory::create, clockNanos)

    private val active = AtomicBoolean(false)

    fun open(): Session {
        checkPermission()
        if (!active.compareAndSet(false, true)) {
            throw SpeechCaptureException(SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
        }

        var record: AudioRecordBoundary? = null
        try {
            checkPermission()
            record = createRecord() ?: throw SpeechCaptureException(SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
            if (!record.isInitialized) {
                record.release()
                record = null
                throw SpeechCaptureException(SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
            }
            record.startRecording()
            return Session(record, clockNanos) { active.set(false) }
        } catch (failure: SecurityException) {
            releaseAfterOpenFailure(record)
            throw SpeechCaptureException(SpeechCaptureFailure.PERMISSION_DENIED, failure)
        } catch (failure: IllegalArgumentException) {
            releaseAfterOpenFailure(record)
            throw SpeechCaptureException(SpeechCaptureFailure.MICROPHONE_UNAVAILABLE, failure)
        } catch (failure: IllegalStateException) {
            releaseAfterOpenFailure(record)
            throw SpeechCaptureException(SpeechCaptureFailure.MICROPHONE_UNAVAILABLE, failure)
        } catch (failure: Exception) {
            releaseAfterOpenFailure(record)
            throw failure
        }
    }

    private fun checkPermission() {
        val granted = try {
            hasPermission()
        } catch (failure: SecurityException) {
            throw SpeechCaptureException(SpeechCaptureFailure.PERMISSION_DENIED, failure)
        }
        if (!granted) throw SpeechCaptureException(SpeechCaptureFailure.PERMISSION_DENIED)
    }

    private fun releaseAfterOpenFailure(record: AudioRecordBoundary?) {
        try {
            record?.release()
        } finally {
            active.set(false)
        }
    }

    class Session internal constructor(
        private val record: AudioRecordBoundary,
        private val clockNanos: () -> Long,
        private val onReleased: () -> Unit,
    ) {
        private val stopped = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val timestamp = CaptureAudioTimestamp()
        private var samplesRead = 0L
        private var anchorFrame = 0L
        private var anchorNanos = 0L
        private var measuredAnchor = false

        suspend fun read(buffer: ShortArray): Int = try {
            withContext(Dispatchers.IO) {
                if (stopped.get()) return@withContext 0
                val count = try {
                    record.read(buffer)
                        .also { count ->
                            if (count > 0) {
                                check(count <= buffer.size) { "Invalid microphone sample count" }
                                samplesRead += count
                                if (record.captureTimestamp(timestamp) &&
                                    timestamp.framePosition >= 0 && timestamp.nanoTime > 0) {
                                    anchorFrame = timestamp.framePosition
                                    anchorNanos = timestamp.nanoTime
                                    measuredAnchor = true
                                } else if (!measuredAnchor) {
                                    // Read completion estimates the last drained sample, never speech end itself.
                                    anchorFrame = samplesRead
                                    anchorNanos = clockNanos()
                                }
                            }
                        }
                } catch (failure: SecurityException) {
                    throw SpeechCaptureException(SpeechCaptureFailure.PERMISSION_DENIED, failure)
                } catch (failure: IllegalStateException) {
                    if (stopped.get()) return@withContext 0
                    throw SpeechCaptureException(SpeechCaptureFailure.READ_FAILED, failure)
                }
                if (count < 0 && !stopped.get()) {
                    throw SpeechCaptureException(SpeechCaptureFailure.READ_FAILED)
                }
                if (count == 0 && !stopped.get()) delay(10)
                if (stopped.get()) 0 else count
            }
        } catch (cancelled: CancellationException) {
            release()
            throw cancelled
        } catch (failure: SpeechCaptureException) {
            release()
            throw failure
        }

        val isStopped: Boolean get() = stopped.get()

        /** Maps a VAD sample boundary into AudioRecord's monotonic sample clock. */
        fun speechEndTime(endSample: Long): Pair<Long, Boolean>? {
            if (anchorNanos <= 0 || endSample < 0 || endSample > samplesRead) return null
            val nanos = anchorNanos + (endSample - anchorFrame) * NANOS_PER_SAMPLE
            return if (nanos > 0) nanos to measuredAnchor else null
        }

        fun stop() {
            if (stopped.compareAndSet(false, true)) {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // AudioRecord may have stopped itself after a permission or device change.
                } catch (_: SecurityException) {
                    // Release still runs even if app-op revocation prevents stop().
                }
            }
        }

        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    stop()
                } finally {
                    try {
                        record.release()
                    } finally {
                        onReleased()
                    }
                }
            }
        }
    }

    companion object {
        const val RATE = 16_000
        private const val NANOS_PER_SAMPLE = 1_000_000_000L / RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT

        private fun createPlatformRecord(context: Context): AudioRecordBoundary? {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone permission is not granted")
            }
            val minimum = AudioRecord.getMinBufferSize(RATE, CHANNEL, FORMAT)
            if (minimum <= 0) return null
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                CHANNEL,
                FORMAT,
                maxOf(minimum, 4 * 1_024),
            )
            return PlatformAudioRecordBoundary(record)
        }
    }
}

internal fun interface AudioRecordBoundaryFactory {
    fun create(): AudioRecordBoundary?
}

internal class CaptureAudioTimestamp {
    var framePosition: Long = 0
    var nanoTime: Long = 0
}

internal interface AudioRecordBoundary {
    val isInitialized: Boolean
    fun startRecording()
    fun read(buffer: ShortArray): Int
    fun captureTimestamp(timestamp: CaptureAudioTimestamp): Boolean
    fun stop()
    fun release()
}

private class PlatformAudioRecordBoundary(private val record: AudioRecord) : AudioRecordBoundary {
    private val timestamp = AudioTimestamp()
    override val isInitialized: Boolean get() = record.state == AudioRecord.STATE_INITIALIZED

    override fun startRecording() = record.startRecording()

    override fun read(buffer: ShortArray): Int =
        record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)

    override fun captureTimestamp(timestamp: CaptureAudioTimestamp): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        val result = try {
            record.getTimestamp(this.timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        } catch (_: IllegalStateException) {
            return false
        }
        if (result != AudioRecord.SUCCESS) return false
        timestamp.framePosition = this.timestamp.framePosition
        timestamp.nanoTime = this.timestamp.nanoTime
        return true
    }

    override fun stop() = record.stop()

    override fun release() = record.release()
}
