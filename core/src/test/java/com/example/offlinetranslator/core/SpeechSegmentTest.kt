package com.example.offlinetranslator.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechSegmentTest {
    @Test fun arrayNormalizationPreservesSignedPcmAndRetainedInputWithoutOverwritingTail() {
        val pcm = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        val segment = SpeechSegment.fromPcm(pcm)
        pcm.fill(0)
        val destination = FloatArray(6) { 9f }
        val expected = floatArrayOf(-1f, -1f / 32768, 0f, 1f / 32768, 32767f / 32768, 9f)
        segment.copyNormalizedTo(destination)
        assertArrayEquals(expected, destination, 0f)
        destination.fill(9f)
        segment.copyNormalizedTo(destination)
        assertArrayEquals(expected, destination, 0f)
    }

    @Test fun insufficientArrayCapacityIsRejectedBeforeAnyWrite() {
        val segment = SpeechSegment.fromPcm(shortArrayOf(1, 2, 3))
        val destination = floatArrayOf(4f, 5f)
        assertThrows(IllegalArgumentException::class.java) { segment.copyNormalizedTo(destination) }
        assertArrayEquals(floatArrayOf(4f, 5f), destination, 0f)
    }

    @Test fun exactMaximumNativeInputRetainsEverySample() {
        val segment = SpeechSegment.fromPcm(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT) {
            if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE
        })
        val destination = FloatArray(segment.sampleCount)
        segment.copyNormalizedTo(destination)
        for (index in destination.indices) {
            assertEquals(if (index % 2 == 0) -1f else 32767f / 32768, destination[index], 0f)
        }
    }

    @Test fun normalizationPreservesSignedExtremesAndCallerBufferWindow() {
        val segment = SpeechSegment.fromPcm(shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE))
        val destination = ByteBuffer.allocateDirect(8 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(8) { destination.put(it, 7f) }
        destination.position(2)
        destination.limit(7)
        segment.copyNormalizedTo(destination)
        assertEquals(7, destination.position())
        assertEquals(7, destination.limit())
        assertEquals(7f, destination.get(1), 0f)
        assertEquals(-1f, destination.get(2), 0f)
        assertEquals(-1f / 32768, destination.get(3), 0f)
        assertEquals(0f, destination.get(4), 0f)
        assertEquals(1f / 32768, destination.get(5), 0f)
        assertEquals(32767f / 32768, destination.get(6), 0f)
        destination.limit(8)
        assertEquals(7f, destination.get(7), 0f)
    }

    @Test fun insufficientRemainingCapacityCannotPartiallyOverwriteDestination() {
        val segment = SpeechSegment.fromPcm(shortArrayOf(1, 2, 3))
        val destination = FloatBuffer.wrap(floatArrayOf(4f, 5f, 6f, 7f))
        destination.position(2)
        assertThrows(IllegalArgumentException::class.java) { segment.copyNormalizedTo(destination) }
        assertEquals(2, destination.position())
        assertEquals(6f, destination.get(2), 0f)
        assertEquals(7f, destination.get(3), 0f)
    }

    @Test fun callerArraysAndPreviousCopiesCannotMutateRetainedPcm() {
        val input = shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE)
        val segment = SpeechSegment.fromPcm(input)
        input.fill(0)
        segment.copyPcm().fill(0)
        val destination = FloatBuffer.allocate(2)
        segment.copyNormalizedTo(destination)
        destination.put(0, 0f)
        destination.clear()
        segment.copyNormalizedTo(destination)
        assertEquals(-1f, destination.get(0), 0f)
        assertEquals(32767f / 32768, destination.get(1), 0f)
    }

    @Test fun fullBoundedSegmentFitsExactlyWithoutChangingLimit() {
        val segment = SpeechSegment.fromPcm(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT) { Short.MIN_VALUE })
        val destination = FloatBuffer.allocate(SpeechSegment.MAX_SAMPLE_COUNT)
        segment.copyNormalizedTo(destination)
        assertEquals(SpeechSegment.MAX_SAMPLE_COUNT, destination.position())
        assertEquals(-1f, destination.get(SpeechSegment.MAX_SAMPLE_COUNT - 1), 0f)
        assertThrows(IllegalArgumentException::class.java) {
            SpeechSegment.fromPcm(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT + 1))
        }
    }
}
