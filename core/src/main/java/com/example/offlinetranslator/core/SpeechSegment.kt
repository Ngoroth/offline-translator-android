package com.example.offlinetranslator.core

import java.nio.FloatBuffer

/** A bounded, mono 16 kHz PCM utterance emitted by VAD. */
class SpeechSegment private constructor(
    pcm: ShortArray,
    val speechEndNanos: Long?,
    val speechEndMeasured: Boolean,
) {
    private val samples = pcm.copyOf()

    val sampleRateHz: Int get() = SAMPLE_RATE_HZ
    val sampleCount: Int get() = samples.size
    val durationMillis: Long get() = sampleCount * 1_000L / SAMPLE_RATE_HZ

    /** Returns a copy so a recognizer cannot mutate data retained by another stage. */
    fun copyPcm(): ShortArray = samples.copyOf()

    /** Copies immutable PCM directly into caller-owned storage without an intermediate array. */
    fun copyNormalizedTo(destination: FloatBuffer) {
        require(destination.remaining() >= samples.size) { "Insufficient remaining PCM buffer capacity" }
        for (sample in samples) destination.put(sample / 32_768f)
    }

    /** Copies immutable PCM directly into caller-owned native input storage. */
    fun copyNormalizedTo(destination: FloatArray) {
        require(destination.size >= samples.size) { "Insufficient PCM array capacity" }
        for (index in samples.indices) destination[index] = samples[index] / 32_768f
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_DURATION_MILLIS = 15_000
        const val MAX_SAMPLE_COUNT = SAMPLE_RATE_HZ * MAX_DURATION_MILLIS / 1_000

        fun fromPcm(
            pcm: ShortArray,
            sampleRateHz: Int = SAMPLE_RATE_HZ,
            speechEndNanos: Long? = null,
            speechEndMeasured: Boolean = false,
        ): SpeechSegment {
            require(sampleRateHz == SAMPLE_RATE_HZ) { "Speech segments must use 16 kHz PCM" }
            require(pcm.isNotEmpty()) { "A speech segment must contain samples" }
            require(pcm.size <= MAX_SAMPLE_COUNT) { "Speech segment exceeds 15 seconds" }
            require(speechEndNanos == null || speechEndNanos > 0) { "Invalid speech-end timestamp" }
            require(!speechEndMeasured || speechEndNanos != null) { "Measured timing needs a timestamp" }
            return SpeechSegment(pcm, speechEndNanos, speechEndMeasured)
        }
    }
}
