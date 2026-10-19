package com.example.offlinetranslator.modelsmoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.sherpa.PcmResampler
import com.example.offlinetranslator.sherpa.SherpaSpeechRecognitionEngine
import com.example.offlinetranslator.sherpa.SherpaSpeechSegmenter
import com.example.offlinetranslator.sherpa.SpeechModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Runs only in the isolated modelSmoke build type; no user audio or production storage. */
@RunWith(AndroidJUnit4::class)
class SpeechModelSmokeTest {
    @Test fun vadAndBothAsrDirectionsLoadPinnedWeightsOfflineAfterInstallation() = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = EvaluationSession.store()
        try {
            EvaluationSession.speechResources(store).use { resources ->
                resources.install()
                assertTrue(resources.isReady(EvaluationSession.russianToEnglish))
                assertTrue(resources.isReady(EvaluationSession.englishToRussian))
            }

            val ruFixture = readPcm16(instrumentation.context.assets.open("ru-official-0.wav"), 8_000)
            val enFixture = readPcm16(instrumentation.context.assets.open("en-official-0.wav"), 16_000)
            val ruAt16k = ShortArray(ruFixture.size * 2) { ruFixture[it / 2] }
            val recognizer = EvaluationSession.recognizer(store)
            try {
                compareDirectAndProductionPath(
                    direction = EvaluationSession.russianToEnglish,
                    pcm16k = ruAt16k,
                    store = store,
                    recognizer = recognizer,
                )
                compareDirectAndProductionPath(
                    direction = EvaluationSession.englishToRussian,
                    pcm16k = enFixture,
                    store = store,
                    recognizer = recognizer,
                )
            } finally {
                recognizer.closeAndJoin()
            }
        } finally {
            store.close()
        }
    }

    private suspend fun compareDirectAndProductionPath(
        direction: LanguagePair,
        pcm16k: ShortArray,
        store: SpeechModelStore,
        recognizer: SherpaSpeechRecognitionEngine,
    ) {
        val source = signalMetrics(pcm16k)
        assertHealthy("source $direction", source)
        val padded = ShortArray(LEADING_SILENCE_SAMPLES + pcm16k.size + TRAILING_SILENCE_SAMPLES)
        pcm16k.copyInto(padded, LEADING_SILENCE_SAMPLES)

        val segments = ArrayList<SpeechSegment>(4)
        val segmentStarts = ArrayList<Int>(4)
        val nativeEnds = ArrayList<Int>(4)
        val emissionOffsets = ArrayList<Long>(4)
        val segmenter = EvaluationSession.segmenter(store)
        try {
            var offset = 0
            while (offset < padded.size) {
                val count = minOf(PRODUCTION_READ_SAMPLES, padded.size - offset)
                segmenter.accept(padded, offset, count) {
                    segments += it
                    segmentStarts += segmenter.currentSegmentStartSample
                    nativeEnds += segmenter.currentNativeSegmentEndSample
                    emissionOffsets += segmenter.acceptedSampleCount
                }
                offset += count
            }
            segmenter.flush {
                segments += it
                segmentStarts += segmenter.currentSegmentStartSample
                nativeEnds += segmenter.currentNativeSegmentEndSample
                emissionOffsets += segmenter.acceptedSampleCount
            }
        } finally {
            segmenter.close()
        }

        assertTrue("controlled fixture must produce at least one segment for $direction", segments.isNotEmpty())
        assertEquals(segments.size, segmentStarts.size)
        assertEquals(segments.size, nativeEnds.size)
        assertEquals(segments.size, emissionOffsets.size)
        val segmentMetrics = segments.mapIndexed { index, segment ->
            signalMetrics(segment.copyPcm()).also {
                assertHealthy("segment $index $direction", it)
                assertTrue("segment $index exceeds the 15 second bound", it.samples <= SpeechSegment.MAX_SAMPLE_COUNT)
            }
        }
        val closureMillis = emissionOffsets.mapIndexed { index, emissionOffset ->
            val audibleBeforeEmission = padded.asList()
                .subList(0, minOf(nativeEnds[index], padded.size))
                .indexOfLast { kotlin.math.abs(it.toInt()) > AUDIBLE_THRESHOLD }
            (emissionOffset - audibleBeforeEmission - 1L) * 1_000 / 16_000
        }
        closureMillis.forEachIndexed { index, milliseconds ->
            assertTrue(
                "VAD closure for segment $index $direction must be 700-900 ms, was $milliseconds ms",
                milliseconds in 700L..900L,
            )
        }

        val adaptedMetrics = segments.mapIndexed { index, segment ->
            val pcm = segment.copyPcm()
            val adapted = if (direction == EvaluationSession.russianToEnglish) resampleLikeProduction(pcm) else pcm
            signalMetrics(adapted).also {
                assertHealthy("ASR input $index $direction", it)
                val expectedSamples = if (direction == EvaluationSession.russianToEnglish) (pcm.size + 1) / 2 else pcm.size
                assertEquals("ASR input sample count for segment $index $direction", expectedSamples, adapted.size)
            }
        }

        val directStart = SystemClock.elapsedRealtimeNanos()
        val direct = recognizer.recognize(direction, SpeechSegment.fromPcm(pcm16k))
        val directMillis = (SystemClock.elapsedRealtimeNanos() - directStart) / 1_000_000
        var segmentedNanos = 0L
        val productionParts = ArrayList<String>(segments.size)
        for (segment in segments) {
            val start = SystemClock.elapsedRealtimeNanos()
            productionParts += recognizer.recognize(direction, segment)
            segmentedNanos += SystemClock.elapsedRealtimeNanos() - start
        }
        val throughProduction = productionParts.joinToString(" ")
        EvaluationSession.recordSmoke(if(direction==EvaluationSession.russianToEnglish) "ru-official-0" else "en-official-0",direction,direct,throughProduction)
        val segmentedMillis = segmentedNanos / 1_000_000
        assertTrue("direct recognizer output for $direction", direct.isNotBlank())
        assertTrue("production-segment output for $direction", throughProduction.isNotBlank())
        println(
            "PCM_DIAGNOSTIC direction=$direction source=${source.describe()} segments=${segmentMetrics.map { it.describe() }} " +
                "asrInputHz=${if (direction == EvaluationSession.russianToEnglish) 8_000 else 16_000} " +
                "asrInput=$adaptedMetrics starts=$segmentStarts nativeEnds=$nativeEnds emissions=$emissionOffsets " +
                "closureMs=$closureMillis " +
                "directMs=$directMillis productionMs=$segmentedMillis " +
                "directTranscript=${normalize(direct)} productionTranscript=${normalize(throughProduction)}",
        )
        assertTokensPreserved(direction, direct, throughProduction)
    }

    private fun resampleLikeProduction(pcm: ShortArray): ShortArray {
        val resampler = PcmResampler()
        val output = ArrayList<Float>(pcm.size / 2 + 1)
        for (offset in pcm.indices step RECOGNIZER_CHUNK_SAMPLES) {
            val chunk = pcm.copyOfRange(offset, minOf(offset + RECOGNIZER_CHUNK_SAMPLES, pcm.size))
            resampler.accept(chunk).forEach(output::add)
        }
        return ShortArray(output.size) { index ->
            (output[index].coerceIn(-1f, 1f) * 32_767).toInt().toShort()
        }
    }

    private data class SignalMetrics(
        val samples: Int,
        val peak: Int,
        val rms: Double,
        val zeroRatio: Double,
        val clipped: Int,
    ) {
        fun describe() = "samples=$samples,peak=$peak,rms=${"%.1f".format(rms)},zeroRatio=${"%.4f".format(zeroRatio)},clipped=$clipped"
    }

    private fun signalMetrics(pcm: ShortArray): SignalMetrics {
        var peak = 0
        var squareSum = 0.0
        var zeroes = 0
        var clipped = 0
        for (sample in pcm) {
            val value = sample.toInt()
            val absolute = kotlin.math.abs(value)
            peak = maxOf(peak, absolute)
            squareSum += value.toDouble() * value
            if (value == 0) zeroes += 1
            if (absolute >= 32_767) clipped += 1
        }
        return SignalMetrics(
            samples = pcm.size,
            peak = peak,
            rms = kotlin.math.sqrt(squareSum / pcm.size),
            zeroRatio = zeroes.toDouble() / pcm.size,
            clipped = clipped,
        )
    }

    private fun assertHealthy(label: String, metrics: SignalMetrics) {
        assertTrue("$label is effectively silent: ${metrics.describe()}", metrics.peak > AUDIBLE_THRESHOLD)
        assertTrue("$label RMS is too low: ${metrics.describe()}", metrics.rms > 100.0)
        assertTrue("$label is mostly zeroed: ${metrics.describe()}", metrics.zeroRatio < 0.98)
        assertTrue("$label is clipped: ${metrics.describe()}", metrics.clipped * 100L < metrics.samples)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun assertTokensPreserved(
        direction: LanguagePair,
        direct: String,
        throughProduction: String,
    ) {
        val expected = normalize(direct).split(' ').filter(String::isNotEmpty)
        val actual = normalize(throughProduction).split(' ').filter(String::isNotEmpty)
        var actualIndex = 0
        for (token in expected) {
            while (actualIndex < actual.size && !tokensEquivalent(token, actual[actualIndex])) actualIndex += 1
            assertTrue(
                "VAD/resampling lost token '$token' for $direction; direct=$direct production=$throughProduction",
                actualIndex < actual.size,
            )
            actualIndex += 1
        }
    }

    private fun tokensEquivalent(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val previous = IntArray(actual.length + 1) { it }
        for (expectedIndex in expected.indices) {
            var diagonal = previous[0]
            previous[0] = expectedIndex + 1
            for (actualIndex in actual.indices) {
                val above = previous[actualIndex + 1]
                previous[actualIndex + 1] = minOf(
                    previous[actualIndex] + 1,
                    above + 1,
                    diagonal + if (expected[expectedIndex] == actual[actualIndex]) 0 else 1,
                )
                diagonal = above
            }
        }
        return previous.last() <= maxOf(1, expected.length / 3)
    }

    private fun readPcm16(input: java.io.InputStream, expectedRate: Int): ShortArray {
        val bytes = input.use { it.readBytes() }
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var rate = -1
        var dataStart = -1
        var dataBytes = -1
        while (offset + 8 <= bytes.size) {
            val name = String(bytes, offset, 4)
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            if (name == "fmt ") {
                require(size >= 16)
                require(buffer.getShort(offset + 8).toInt() == 1) // PCM
                require(buffer.getShort(offset + 10).toInt() == 1) // mono
                rate = buffer.getInt(offset + 12)
                require(buffer.getShort(offset + 22).toInt() == 16)
            }
            if (name == "data") {
                dataStart = offset + 8
                dataBytes = size
                break
            }
            offset += 8 + size + size % 2
        }
        require(rate == expectedRate && dataStart >= 0 && dataBytes > 0 && dataBytes % 2 == 0)
        return ShortArray(dataBytes / 2) { buffer.getShort(dataStart + it * 2) }
    }

    companion object {
        private const val LEADING_SILENCE_SAMPLES = 16_000 / 2
        private const val TRAILING_SILENCE_SAMPLES = 16_000 * 3 / 2
        private const val PRODUCTION_READ_SAMPLES = 2_048
        private const val RECOGNIZER_CHUNK_SAMPLES = 5_120
        private const val AUDIBLE_THRESHOLD = 256
    }
}
