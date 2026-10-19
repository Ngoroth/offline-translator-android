package com.example.offlinetranslator.sherpa

import kotlin.math.roundToInt

/** A single reusable PCM chunk; the native callback never retains its input array. */
internal class SpeechPcmBuffer(
    val samples: ShortArray = ShortArray(CHUNK_FRAMES),
    private val maximumFrames: Int = MAXIMUM_FRAMES,
) {
    var acceptedFrames: Int = 0
        private set
    var firstNonSilentFrame: Long? = null
        private set

    init {
        require(samples.isNotEmpty())
        require(maximumFrames > 0)
    }

    fun reset() {
        acceptedFrames = 0
        firstNonSilentFrame = null
        samples.fill(0)
    }

    /** Validate the entire callback before any of its samples can become audible. */
    fun accept(input: FloatArray) {
        require(input.size <= maximumFrames - acceptedFrames) { "Synthesized speech exceeds the audio quota" }
        for (sample in input) {
            require(sample.isFinite() && sample in -1f..1f) { "Synthesized speech contains invalid PCM" }
        }
        acceptedFrames += input.size
    }

    fun convert(input: FloatArray, offset: Int, frameBase: Long): Int {
        require(offset in input.indices)
        val count = minOf(samples.size, input.size - offset)
        for (index in 0 until count) {
            val sample = input[offset + index]
            val pcm = (sample * if (sample < 0f) 32_768f else 32_767f).roundToInt().toShort()
            samples[index] = pcm
            if (pcm != 0.toShort() && firstNonSilentFrame == null) {
                firstNonSilentFrame = frameBase + index
            }
        }
        return count
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val CHUNK_FRAMES = 2_048
        const val MAXIMUM_FRAMES = SAMPLE_RATE * 60
    }
}
