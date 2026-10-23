package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.ensureActive
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production PCM-to-segment boundary shared by microphone capture and model diagnostics.
 * Input is always mono 16 kHz PCM16 and no audio is retained after [close].
 */
class SherpaSpeechSegmenter private constructor(
    private val vad: Vad,
    private val speechEndTime: (Long) -> Pair<Long, Boolean>?,
) : AutoCloseable {
    private val frame = FloatArray(FRAME_SAMPLES)
    private val history = PcmHistory(HISTORY_SAMPLES)
    private var frameSize = 0
    private var acceptedSamples = 0L
    private var lastEmittedEndSample = 0L
    private var flushed = false
    private val closed = AtomicBoolean(false)

    /** Number of real input samples consumed, useful for precise boundary diagnostics. */
    val acceptedSampleCount: Long
        get() = acceptedSamples

    /** Start sample of the segment currently being emitted through the callback. */
    var currentSegmentStartSample: Int = -1
        private set

    /** End sample reported by VAD before bounded post-roll is appended. */
    var currentNativeSegmentEndSample: Int = -1
        private set

    @Synchronized
    fun accept(
        input: ShortArray,
        offset: Int = 0,
        count: Int = input.size - offset,
        onSegment: (SpeechSegment) -> Unit,
    ) {
        check(!closed.get()) { "Segmenter is closed" }
        check(!flushed) { "Segmenter is already flushed" }
        require(offset >= 0 && count >= 0 && offset + count <= input.size) { "Invalid PCM range" }

        for (index in offset until offset + count) {
            val sample = input[index]
            history.append(acceptedSamples, sample)
            frame[frameSize++] = sample / 32_768f
            acceptedSamples += 1
            if (frameSize == frame.size) {
                vad.acceptWaveform(frame)
                frameSize = 0
                drain(onSegment)
                if (closed.get()) return
            }
        }
    }

    /** Emits every remaining segment once. Further input is rejected. */
    @Synchronized
    fun flush(onSegment: (SpeechSegment) -> Unit) {
        check(!closed.get()) { "Segmenter is closed" }
        if (flushed) return
        flushed = true
        if (frameSize > 0) {
            java.util.Arrays.fill(frame, frameSize, frame.size, 0f)
            vad.acceptWaveform(frame)
            frameSize = 0
        }
        vad.flush()
        drain(onSegment)
    }

    private fun drain(onSegment: (SpeechSegment) -> Unit) {
        while (!closed.get() && !vad.empty()) {
            val nativeSegment = vad.front()
            val samples = nativeSegment.samples
            val nativeStart = nativeSegment.start.toLong()
            vad.pop()
            if (samples.isEmpty()) continue
            val nativeCount = minOf(samples.size, SpeechSegment.MAX_SAMPLE_COUNT)
            val nativeEnd = nativeStart + nativeCount
            currentNativeSegmentEndSample = nativeEnd.toInt()
            val postRoll = history.copyAvailable(
                nativeEnd,
                minOf(nativeEnd + minOf(POST_ROLL_SAMPLES,
                    SpeechSegment.MAX_SAMPLE_COUNT - nativeCount), acceptedSamples),
                acceptedSamples,
            )
            val preRollCapacity = SpeechSegment.MAX_SAMPLE_COUNT - nativeCount - postRoll.size
            val requestedStart = maxOf(
                nativeStart - minOf(PRE_ROLL_SAMPLES, preRollCapacity),
                lastEmittedEndSample,
            )
            val preRoll = history.copyAvailable(requestedStart, nativeStart, acceptedSamples)
            currentSegmentStartSample = (nativeStart - preRoll.size).toInt()
            val pcm = ShortArray(preRoll.size + nativeCount + postRoll.size)
            preRoll.copyInto(pcm)
            for (index in 0 until nativeCount) {
                pcm[preRoll.size + index] =
                    (samples[index].coerceIn(-1f, 1f) * 32_767).toInt().toShort()
            }
            postRoll.copyInto(pcm, preRoll.size + nativeCount)
            lastEmittedEndSample = nativeEnd + postRoll.size
            // Native Silero VAD has already excluded its min-silence wait from this boundary.
            // Pre/post-roll is recognition context, not extra speech or a second silence deduction.
            val timing = speechEndTime(minOf(nativeEnd, acceptedSamples))
            onSegment(SpeechSegment.fromPcm(
                pcm,
                speechEndNanos = timing?.first,
                speechEndMeasured = timing?.second == true,
            ))
        }
    }

    @Synchronized
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { vad.release() } finally {
                frame.fill(0f)
                frameSize = 0
                history.clear()
            }
        }
    }

    companion object {
        const val FRAME_SAMPLES = 512
        const val MIN_SILENCE_DURATION_SECONDS = 0.7f
        const val PRE_ROLL_MILLISECONDS = 400
        const val POST_ROLL_MILLISECONDS = 400
        private const val PRE_ROLL_SAMPLES = SpeechSegment.SAMPLE_RATE_HZ * PRE_ROLL_MILLISECONDS / 1_000
        private const val POST_ROLL_SAMPLES = SpeechSegment.SAMPLE_RATE_HZ * POST_ROLL_MILLISECONDS / 1_000
        private const val HISTORY_SAMPLES = SpeechSegment.MAX_SAMPLE_COUNT + SpeechSegment.SAMPLE_RATE_HZ

        suspend fun create(
            store: SpeechModelStore,
            vadModelId: String,
            vadMinSilenceMs: Int,
            speechEndTime: (Long) -> Pair<Long, Boolean>? = { null },
        ): SherpaSpeechSegmenter {
            val definition = store.catalog.model(vadModelId)
            val config = SherpaModelFactories.selectedVadConfig(store, definition, vadMinSilenceMs)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return SherpaSpeechSegmenter(Vad(config = config), speechEndTime)
        }
    }
}

/** Fixed-size absolute-indexed PCM history; memory use does not grow with capture duration. */
private class PcmHistory(capacity: Int) {
    private val samples = ShortArray(capacity)

    fun append(absoluteIndex: Long, sample: Short) {
        samples[(absoluteIndex % samples.size).toInt()] = sample
    }

    fun clear() = samples.fill(0)

    fun copyAvailable(start: Long, end: Long, samplesWritten: Long): ShortArray {
        val availableStart = maxOf(0L, samplesWritten - samples.size)
        val boundedStart = maxOf(start, availableStart).coerceAtMost(end)
        val length = (end - boundedStart).toInt()
        return ShortArray(length) { offset ->
            samples[((boundedStart + offset) % samples.size).toInt()]
        }
    }
}
