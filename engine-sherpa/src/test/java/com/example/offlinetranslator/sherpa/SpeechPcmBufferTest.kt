package com.example.offlinetranslator.sherpa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechPcmBufferTest {
    @Test fun quantizationUsesFullSignedRangeAndTracksTheFirstAudibleFrame() {
        val pcm = SpeechPcmBuffer(ShortArray(4), maximumFrames = 8)
        val input = floatArrayOf(0f, 0.00000001f, -1f, 1f)
        pcm.accept(input)

        assertEquals(4, pcm.convert(input, 0, frameBase = 10))
        assertArrayEquals(shortArrayOf(0, 0, Short.MIN_VALUE, Short.MAX_VALUE), pcm.samples)
        assertEquals(12L, pcm.firstNonSilentFrame)
    }

    @Test fun chunksReuseOneBufferAndKeepTheOriginalFrameOffset() {
        val buffer = ShortArray(2)
        val pcm = SpeechPcmBuffer(buffer, maximumFrames = 8)
        val input = floatArrayOf(0f, 0f, 0.5f, -0.5f, 1f)
        pcm.accept(input)
        assertEquals(2, pcm.convert(input, 0, 0))
        assertNull(pcm.firstNonSilentFrame)
        assertEquals(2, pcm.convert(input, 2, 2))
        assertEquals(2L, pcm.firstNonSilentFrame)
        assertEquals(1, pcm.convert(input, 4, 4))
        assertSame(buffer, pcm.samples)
        assertEquals(Short.MAX_VALUE, pcm.samples[0])
    }

    @Test fun invalidLateSampleRejectsTheWholeCallbackWithoutConsumingQuota() {
        for (invalid in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.001f, -1.001f)) {
            val pcm = SpeechPcmBuffer(maximumFrames = 4)
            pcm.accept(floatArrayOf(0f))
            assertThrows(IllegalArgumentException::class.java) { pcm.accept(floatArrayOf(0.5f, invalid)) }
            assertEquals(1, pcm.acceptedFrames)
            assertNull(pcm.firstNonSilentFrame)
        }
    }

    @Test fun aggregateQuotaIncludesEveryCallbackAndRejectsOnlyOverflow() {
        val pcm = SpeechPcmBuffer(maximumFrames = 3)
        pcm.accept(floatArrayOf(0f, 1f))
        pcm.accept(floatArrayOf(-1f))
        pcm.accept(floatArrayOf())
        assertEquals(3, pcm.acceptedFrames)
        assertThrows(IllegalArgumentException::class.java) { pcm.accept(floatArrayOf(0f)) }
        assertEquals(3, pcm.acceptedFrames)
    }

    @Test fun subQuantizationNoiseAndSignedZeroDoNotCountAsNonSilentOutput() {
        val pcm = SpeechPcmBuffer(ShortArray(4), maximumFrames = 5)
        val silence = floatArrayOf(0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE)
        pcm.accept(silence)
        pcm.convert(silence, 0, frameBase = 0)
        assertArrayEquals(ShortArray(4), pcm.samples)
        assertNull(pcm.firstNonSilentFrame)

        val audible = floatArrayOf(-1f / 32_768f)
        pcm.accept(audible)
        pcm.convert(audible, 0, frameBase = silence.size.toLong())
        assertEquals((-1).toShort(), pcm.samples[0])
        assertEquals(4L, pcm.firstNonSilentFrame)
        assertEquals(5, pcm.acceptedFrames)
    }

    @Test fun resetClearsPrivatePcmAndAllowsANewUtterance() {
        val pcm = SpeechPcmBuffer(ShortArray(2), maximumFrames = 2)
        val input = floatArrayOf(1f, -1f)
        pcm.accept(input)
        pcm.convert(input, 0, 0)
        pcm.reset()
        assertArrayEquals(shortArrayOf(0, 0), pcm.samples)
        assertNull(pcm.firstNonSilentFrame)
        assertEquals(0, pcm.acceptedFrames)
        pcm.accept(input)
        assertEquals(2, pcm.acceptedFrames)
    }
}
