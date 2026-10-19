package com.example.offlinetranslator.sherpa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.offlinetranslator.core.SpeechPlaybackStart
import kotlinx.coroutines.CancellationException

/** One foreground output owner, with no audio restart after focus loss or interruption. */
internal class AndroidSpeechPlayback(
    private val createTrack: () -> SpeechAudioTrack,
    private val createFocus: () -> SpeechAudioFocus,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMillis: (Long) -> Unit = Thread::sleep,
) {
    constructor(context: Context) : this(
        createTrack = { PlatformSpeechAudioTrack() },
        createFocus = {
            PlatformSpeechAudioFocus(context.applicationContext.getSystemService(AudioManager::class.java))
        },
    )

    private val ownerLock = Any()
    private val pcm = SpeechPcmBuffer()
    private var active: Session? = null

    fun open(
        register: (Session) -> Unit = {},
        onStarted: (SpeechPlaybackStart) -> Unit,
    ): Session {
        val session = synchronized(ownerLock) {
            check(active == null) { "Speech output is already active" }
            val track = createTrack()
            try {
                check(track.isInitialized) { "AudioTrack could not initialize speech output" }
                Session(track, createFocus(), onStarted).also {
                    pcm.reset()
                    active = it
                }
            } catch (failure: Throwable) {
                track.release()
                throw failure
            }
        }
        try {
            // Publish ownership before focus: revoke can stop even an output that has not played yet.
            register(session)
            session.acquireFocus()
            return session
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    inner class Session internal constructor(
        private val track: SpeechAudioTrack,
        private val focus: SpeechAudioFocus,
        private val onStarted: (SpeechPlaybackStart) -> Unit,
    ) : AutoCloseable {
        private val lock = Any()
        private val timestamp = SpeechAudioTimestamp()
        private var terminalFailure: Throwable? = null
        private var closed = false
        private var stopped = false
        private var released = false
        private var playing = false
        private var submittedFrames = 0L
        private var previousHead = 0L
        private var playStartedNanos = 0L
        private var lastProgressNanos = 0L
        private var initialUnderrunCount = -1
        private var firstHeadEvidence: SpeechPlaybackStart? = null
        private var firstHeadObservedNanos = 0L
        private var startReported = false
        private var focusAbandoned = false

        internal fun acquireFocus() = synchronized(lock) {
            ensureOpen()
            check(focus.request { fail(IllegalStateException("Speech audio focus was lost")) }) {
                "Foreground speech audio focus was denied"
            }
            // A loss callback during request must not be followed by a delayed start.
            ensureOpen()
        }

        val acceptedFrames: Int get() = synchronized(lock) { pcm.acceptedFrames }

        /** Called synchronously by JNI. Writes are nonblocking and never hold the lock while waiting. */
        fun append(input: FloatArray, ensureActive: () -> Unit) {
            ensureActive()
            synchronized(lock) {
                ensureOpen()
                pcm.accept(input)
            }
            var lastWriteProgressNanos = nanoTime()
            var inputOffset = 0
            while (inputOffset < input.size) {
                ensureActive()
                val count = synchronized(lock) {
                    ensureOpen()
                    pcm.convert(input, inputOffset, submittedFrames)
                }
                var chunkOffset = 0
                while (chunkOffset < count) {
                    ensureActive()
                    val written = synchronized(lock) {
                        ensureOpen()
                        val result = track.write(pcm.samples, chunkOffset, count - chunkOffset)
                        ensureOpen()
                        check(result in 0..(count - chunkOffset)) { "AudioTrack failed to write speech PCM" }
                        if (result > 0) {
                            lastWriteProgressNanos = nanoTime()
                        } else {
                            check(nanoTime() - lastWriteProgressNanos < STALL_NANOS) {
                                "AudioTrack stopped accepting speech PCM"
                            }
                        }
                        submittedFrames += result
                        if (!playing && result > 0) {
                            playStartedNanos = nanoTime()
                            lastProgressNanos = playStartedNanos
                            initialUnderrunCount = track.underrunCount()
                            track.play()
                            playing = true
                            ensureOpen()
                        }
                        observeLocked()
                        ensureOpen()
                        result
                    }
                    chunkOffset += written
                    if (written == 0) sleepMillis(POLL_MILLIS)
                }
                inputOffset += count
            }
        }

        /** Also polled independently while native code is generating the next sentence. */
        fun observe() = synchronized(lock) {
            ensureOpen()
            observeLocked()
            ensureOpen()
        }

        fun drain(ensureActive: () -> Unit) {
            synchronized(lock) {
                ensureOpen()
                check(submittedFrames > 0 && pcm.firstNonSilentFrame != null) {
                    "Local synthesis produced no non-silent speech"
                }
            }
            while (true) {
                ensureActive()
                val complete = synchronized(lock) {
                    ensureOpen()
                    val head = observeLocked()
                    ensureOpen()
                    head >= submittedFrames && startReported
                }
                if (complete) return
                sleepMillis(POLL_MILLIS)
            }
        }

        fun ensureOpen() = synchronized(lock) {
            terminalFailure?.let { throw it }
            check(!closed && !released && !stopped) { "Speech playback is closed" }
        }

        private fun observeLocked(): Long {
            if (!playing) return 0
            val head = track.playbackHeadFrames()
            check(head in previousHead..submittedFrames) { "AudioTrack returned invalid playback progress" }
            val now = nanoTime()
            if (head > previousHead) {
                previousHead = head
                lastProgressNanos = now
            }
            val first = pcm.firstNonSilentFrame
            if (!startReported && first != null && head > first) {
                if (firstHeadEvidence == null) {
                    firstHeadEvidence = SpeechPlaybackStart(
                        maxOf(playStartedNanos, now - framesToNanos(head - first)), measured = false,
                    )
                    firstHeadObservedNanos = now
                }
                val hasTimestamp = track.timestamp(timestamp)
                val presentation = if (
                    hasTimestamp && timestamp.framePosition > first && timestamp.framePosition <= submittedFrames &&
                    timestamp.nanoTime in playStartedNanos..now && initialUnderrunCount >= 0 &&
                    track.underrunCount() == initialUnderrunCount
                ) {
                    val value = timestamp.nanoTime - framesToNanos(timestamp.framePosition - first)
                    if (value in playStartedNanos..now) SpeechPlaybackStart(value, measured = true) else null
                } else null
                if (presentation != null || now - firstHeadObservedNanos >= TIMESTAMP_GRACE_NANOS) {
                    startReported = true
                    // Head progress proves that output began, but its polling clock is only an estimate.
                    onStarted(presentation ?: requireNotNull(firstHeadEvidence))
                }
            }
            check(head >= submittedFrames || now - lastProgressNanos < STALL_NANOS) {
                "AudioTrack stopped making playback progress"
            }
            return head
        }

        /** Synchronous pause/flush under the same lock used by play/write. Never resumes this session. */
        fun interrupt(reason: Throwable = CancellationException("Speech playback interrupted")) = synchronized(lock) {
            if (terminalFailure == null) terminalFailure = reason
            stopLocked()
        }

        private fun stopLocked() {
            if (stopped || released) return
            stopped = true
            var failure: Throwable? = null
            try {
                if (playing) track.pause()
            } catch (caught: Throwable) {
                failure = caught
            }
            try {
                track.flush()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            try {
                abandonFocusLocked()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            if (failure != null) {
                // Release is the last synchronous stop boundary if a device failure prevents pause/flush.
                released = true
                try {
                    track.release()
                } catch (caught: Throwable) {
                    failure.addSuppressed(caught)
                }
                terminalFailure = failure
                throw failure
            }
        }

        /** Platform focus listeners must not throw; the owner observes this stored error instead. */
        fun fail(reason: Throwable) {
            try {
                interrupt(reason)
            } catch (_: Throwable) {
                // stopLocked retained the stop/release failure for the owning speak operation.
            }
        }

        private fun abandonFocusLocked() {
            if (focusAbandoned) return
            focusAbandoned = true
            focus.abandon()
        }

        override fun close() {
            val shouldClose = synchronized(lock) {
                if (closed) false else {
                    closed = true
                    true
                }
            }
            if (!shouldClose) return
            try {
                synchronized(lock) {
                    try {
                        stopLocked()
                    } finally {
                        if (!released) {
                            released = true
                            track.release()
                        }
                    }
                }
            } finally {
                try {
                    synchronized(lock) { abandonFocusLocked() }
                } finally {
                    synchronized(ownerLock) {
                        if (active === this) {
                            pcm.reset()
                            active = null
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val POLL_MILLIS = 5L
        private const val TIMESTAMP_GRACE_NANOS = 150_000_000L
        private const val STALL_NANOS = 2_000_000_000L
        private fun framesToNanos(frames: Long) = frames * 1_000_000_000L / SpeechPcmBuffer.SAMPLE_RATE
    }
}

internal interface SpeechAudioTrack {
    val isInitialized: Boolean
    fun play()
    fun write(samples: ShortArray, offset: Int, count: Int): Int
    fun playbackHeadFrames(): Long
    fun timestamp(destination: SpeechAudioTimestamp): Boolean
    /** Negative means that the platform cannot prove a continuous presentation clock. */
    fun underrunCount(): Int
    fun pause()
    fun flush()
    fun release()
}

internal class SpeechAudioTimestamp(var framePosition: Long = 0, var nanoTime: Long = 0)

internal interface SpeechAudioFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

private fun speechAudioAttributes() = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

private class PlatformSpeechAudioTrack : SpeechAudioTrack {
    private val timestamp = AudioTimestamp()
    private val native: AudioTrack

    init {
        val minimum = AudioTrack.getMinBufferSize(
            SpeechPcmBuffer.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum in 1..MAXIMUM_BUFFER_BYTES) { "AudioTrack does not support bounded mono 22050 Hz speech" }
        native = AudioTrack.Builder()
            .setAudioAttributes(speechAudioAttributes())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SpeechPcmBuffer.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum, SpeechPcmBuffer.CHUNK_FRAMES * 2))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 31 && native.state == AudioTrack.STATE_INITIALIZED) {
                native.setStartThresholdInFrames(1)
            }
        } catch (failure: Throwable) {
            native.release()
            throw failure
        }
    }

    override val isInitialized: Boolean get() = native.state == AudioTrack.STATE_INITIALIZED
    override fun play() = native.play()
    override fun write(samples: ShortArray, offset: Int, count: Int): Int =
        native.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
    override fun playbackHeadFrames(): Long = native.playbackHeadPosition.toLong() and 0xffff_ffffL
    override fun timestamp(destination: SpeechAudioTimestamp): Boolean {
        if (!native.getTimestamp(timestamp)) return false
        destination.framePosition = timestamp.framePosition
        destination.nanoTime = timestamp.nanoTime
        return true
    }
    override fun underrunCount(): Int = if (Build.VERSION.SDK_INT >= 24) native.underrunCount else -1
    override fun pause() = native.pause()
    override fun flush() = native.flush()
    override fun release() = native.release()

    companion object {
        private const val MAXIMUM_BUFFER_BYTES = SpeechPcmBuffer.SAMPLE_RATE * 2
    }
}

private class PlatformSpeechAudioFocus(private val manager: AudioManager) : SpeechAudioFocus {
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val callback = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) onLoss()
        }
        listener = callback
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(speechAudioAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(callback, Handler(Looper.getMainLooper()))
                .build()
            request = focus
            manager.requestAudioFocus(focus)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(callback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        try {
            val result = if (Build.VERSION.SDK_INT >= 26) {
                request?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                listener?.let { manager.abandonAudioFocus(it) }
            }
            check(result == null || result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                "Speech audio focus could not be released"
            }
        } finally {
            listener = null
            request = null
        }
    }
}
