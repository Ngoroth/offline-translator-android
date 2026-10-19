package com.example.offlinetranslator.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PcmResamplerTest {
    @Test fun chunkBoundariesPreserveOutputAndDuration() {
        val input = tone(1_000, 16_001)
        val whole = PcmResampler().accept(input)
        val chunked = PcmResampler().let { filter ->
            val first = filter.accept(input.copyOfRange(0, 319))
            val second = filter.accept(input.copyOfRange(319, 7_911))
            val third = filter.accept(input.copyOfRange(7_911, input.size))
            first + second + third
        }
        assertEquals(8_001, whole.size)
        assertTrue(whole.contentEquals(chunked))
    }

    @Test fun callerOwnedChunkBufferMatchesWholeUtteranceAcrossOddBoundaries() {
        val input = tone(1_000, 16_001)
        val expected = PcmResampler().accept(input)
        val filter = PcmResampler()
        val scratch = FloatArray(256)
        val actual = FloatArray(expected.size)
        var start = 0
        var written = 0
        while (start < input.size) {
            val count = minOf(511, input.size - start)
            val size = filter.accept(input, start, count, scratch)
            scratch.copyInto(actual, written, 0, size)
            written += size
            start += count
        }
        assertEquals(expected.size, written)
        assertTrue(expected.contentEquals(actual))
    }

    @Test fun passesSpeechBandAndRejectsAliasingAboveFourKilohertz() {
        val low = PcmResampler().accept(tone(1_000, 16_000))
        val high = PcmResampler().accept(tone(6_000, 16_000))
        val lowRms = rms(low.copyOfRange(100, low.size))
        val highRms = rms(high.copyOfRange(100, high.size))
        assertTrue("1 kHz should survive filtering: $lowRms", lowRms > 0.5)
        assertTrue("6 kHz should be attenuated: $highRms", highRms < lowRms * 0.02)
    }

    private fun tone(hz: Int, count: Int): ShortArray = ShortArray(count) { index ->
        (sin(2 * PI * hz * index / 16_000) * 26_000).toInt().toShort()
    }

    private fun rms(samples: FloatArray): Double = sqrt(samples.sumOf { abs(it.toDouble()) * abs(it.toDouble()) } / samples.size)
}
