package com.example.offlinetranslator.modelsmoke

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.sherpa.SherpaSpeechRecognitionEngine
import com.example.offlinetranslator.sherpa.SherpaSpeechSegmenter
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.sherpa.SherpaSpeechModelAdapter
import com.example.offlinetranslator.speechmodels.SpeechModelDefinition
import com.example.offlinetranslator.speechmodels.SpeechModelRef
import com.example.offlinetranslator.sherpa.SherpaModelFactories
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional, reproducible eight-phrase evaluation fed from the app's external test directory. */
@RunWith(AndroidJUnit4::class)
class ControlledSpeechCorpusTest {
    @Test fun eightControlPhrasesPreserveMeaningThroughProductionBoundaries() {
        if (InstrumentationRegistry.getArguments().getString(ARGUMENT) != "true") {
            println("CONTROL_CORPUS_DISABLED: pass controlCorpus=true to run the external corpus")
            return
        }
        runBlocking(Dispatchers.IO) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val corpus = if (EvaluationSession.active) File(EvaluationSession.corpus, "smoke") else requireNotNull(context.getExternalFilesDir(CORPUS_DIRECTORY))
            require(corpus.isDirectory) { "Missing controlled speech corpus at $corpus" }

            val store = EvaluationSession.store()
            try {
                EvaluationSession.speechResources(store).use { it.install() }
                val recognizer = EvaluationSession.recognizer(store)
                val candidate = if (InstrumentationRegistry.getArguments().getString(CANDIDATE_ARGUMENT) == "true") {
                    EnglishCandidateRecognizer(requireNotNull(context.getExternalFilesDir(CANDIDATE_DIRECTORY)))
                } else {
                    null
                }
                val russianRuntimeResampler = if (
                    InstrumentationRegistry.getArguments().getString(RUSSIAN_RUNTIME_RESAMPLER_ARGUMENT) == "true"
                ) {
                    val definition = store.catalog.model(EvaluationSession.asrModelIds(store).getValue(LanguageId("ru")))
                    require(definition.adapter is SherpaSpeechModelAdapter.ToneCtcOnline) { "Runtime-resampler comparison requires T-one" }
                    val paths = definition.requiredRefs.associateWith { store.modelFile(it.packageId, it.path).absolutePath }
                    RussianRuntimeResamplerRecognizer(definition, paths)
                } else {
                    null
                }
                try {
                    val failures = ArrayList<String>()
                    for (case in CASES) {
                        val pcm = readMonoPcm16Wave(File(corpus, case.fileName))
                        val direct = timedRecognition(
                            recognizer, candidate, russianRuntimeResampler, case.pair, SpeechSegment.fromPcm(pcm),
                        )
                        val production = recognizeProductionPath(
                            pcm, store, recognizer, candidate, russianRuntimeResampler, case.pair,
                        )
                        EvaluationSession.recordSmoke(case.fileName.removeSuffix(".wav"),case.pair,direct.text,production.text)
                        println(
                            "CONTROL_PHRASE enModel=${candidate?.id ?: "catalog"} " +
                                "ruResampler=${if (russianRuntimeResampler == null) "fir" else "runtime"} " +
                                "file=${case.fileName} direction=${case.pair.id} " +
                                "directMs=${direct.milliseconds} productionMs=${production.milliseconds} " +
                                "direct=${normalize(direct.text)} production=${normalize(production.text)}",
                        )
                        failures += missingMeaning(case, "direct", direct.text)
                        failures += missingMeaning(case, "production", production.text)
                    }
                    assertTrue("Control corpus failures:\n${failures.joinToString("\n")}", failures.isEmpty())
                } finally {
                    russianRuntimeResampler?.close()
                    candidate?.close()
                    recognizer.closeAndJoin()
                }
            } finally {
                store.close()
            }
        }
    }

    private suspend fun recognizeProductionPath(
        pcm: ShortArray,
        store: SpeechModelStore,
        recognizer: SherpaSpeechRecognitionEngine,
        candidate: EnglishCandidateRecognizer?,
        russianRuntimeResampler: RussianRuntimeResamplerRecognizer?,
        pair: LanguagePair,
    ): TimedText {
        val padded = ShortArray(LEADING_SILENCE + pcm.size + TRAILING_SILENCE)
        pcm.copyInto(padded, LEADING_SILENCE)
        val segments = ArrayList<SpeechSegment>(2)
        EvaluationSession.segmenter(store).use { segmenter ->
            for (offset in padded.indices step READ_SAMPLES) {
                segmenter.accept(padded, offset, minOf(READ_SAMPLES, padded.size - offset), segments::add)
            }
            segmenter.flush(segments::add)
        }
        assertTrue("VAD produced no segment for $pair", segments.isNotEmpty())
        var elapsed = 0L
        val parts = ArrayList<String>(segments.size)
        for (segment in segments) {
            val result = timedRecognition(recognizer, candidate, russianRuntimeResampler, pair, segment)
            parts += result.text
            elapsed += result.milliseconds
        }
        return TimedText(parts.joinToString(" "), elapsed)
    }

    private suspend fun timedRecognition(
        recognizer: SherpaSpeechRecognitionEngine,
        candidate: EnglishCandidateRecognizer?,
        russianRuntimeResampler: RussianRuntimeResamplerRecognizer?,
        pair: LanguagePair,
        segment: SpeechSegment,
    ): TimedText {
        val start = SystemClock.elapsedRealtimeNanos()
        val text = when {
            pair == EvaluationSession.englishToRussian && candidate != null -> candidate.recognize(segment)
            pair == EvaluationSession.russianToEnglish && russianRuntimeResampler != null -> {
                russianRuntimeResampler.recognize(segment)
            }
            else -> recognizer.recognize(pair, segment)
        }
        return TimedText(text, (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000)
    }

    private fun missingMeaning(case: ControlCase, path: String, transcript: String): List<String> {
        val tokens = normalize(transcript).split(' ').filter(String::isNotEmpty)
        return case.requiredTokens
            .filterNot { required ->
                tokens.any { tokensEquivalent(required, it) } ||
                    tokens.zipWithNext().any { (first, second) -> required == first + second }
            }
            .map { required -> "${case.fileName} $path lost '$required': $transcript" }
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
        return previous.last() <= maxOf(1, expected.length / 4)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private data class TimedText(val text: String, val milliseconds: Long)
    private data class ControlCase(
        val fileName: String,
        val pair: LanguagePair,
        val requiredTokens: List<String>,
    )

    private class EnglishCandidateRecognizer(directory: File) : AutoCloseable {
        val id = directory.name
        private val recognizer: OnlineRecognizer

        init {
            val required = listOf(ENCODER, DECODER, JOINER, TOKENS)
            require(required.all { File(directory, it).isFile }) { "Incomplete English candidate at $directory" }
            fun ref(path: String) = SpeechModelRef(id, path)
            val refs = required.map(::ref)
            val definition = SpeechModelDefinition(id, id, SpeechModelRole.ASR, setOf(LanguageId("en")), 16_000,
                SherpaSpeechModelAdapter.Zipformer2Online(ref(ENCODER), ref(DECODER), ref(JOINER), ref(TOKENS), 2, 80), refs)
            recognizer = OnlineRecognizer(config = SherpaModelFactories.onlineConfig(definition) { ref ->
                File(directory, ref.path).absolutePath
            })
        }

        suspend fun recognize(segment: SpeechSegment): String = withContext(Dispatchers.Default) {
            val stream = recognizer.createStream()
            try {
                val pcm = segment.copyPcm()
                for (start in pcm.indices step RECOGNIZER_CHUNK_SAMPLES) {
                    val end = minOf(start + RECOGNIZER_CHUNK_SAMPLES, pcm.size)
                    stream.acceptWaveform(
                        FloatArray(end - start) { pcm[start + it] / 32_768f },
                        sampleRate = SpeechSegment.SAMPLE_RATE_HZ,
                    )
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                }
                stream.acceptWaveform(FloatArray(SpeechSegment.SAMPLE_RATE_HZ * 4 / 5), SpeechSegment.SAMPLE_RATE_HZ)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        override fun close() = recognizer.release()

        companion object {
            private const val ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            private const val DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
            private const val JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
            private const val TOKENS = "tokens.txt"
        }
    }

    private class RussianRuntimeResamplerRecognizer(
        definition: SpeechModelDefinition,
        paths: Map<SpeechModelRef, String>,
    ) : AutoCloseable {
        private val recognizer = OnlineRecognizer(config = SherpaModelFactories.onlineConfig(definition, paths::getValue))

        suspend fun recognize(segment: SpeechSegment): String = withContext(Dispatchers.Default) {
            val stream = recognizer.createStream()
            try {
                val pcm = segment.copyPcm()
                for (start in pcm.indices step RECOGNIZER_CHUNK_SAMPLES) {
                    val end = minOf(start + RECOGNIZER_CHUNK_SAMPLES, pcm.size)
                    stream.acceptWaveform(
                        FloatArray(end - start) { pcm[start + it] / 32_768f },
                        sampleRate = SpeechSegment.SAMPLE_RATE_HZ,
                    )
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                }
                stream.acceptWaveform(FloatArray(SpeechSegment.SAMPLE_RATE_HZ * 4 / 5), SpeechSegment.SAMPLE_RATE_HZ)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        override fun close() = recognizer.release()
    }

    companion object {
        private const val ARGUMENT = "controlCorpus"
        private const val CANDIDATE_ARGUMENT = "englishCandidate"
        private const val RUSSIAN_RUNTIME_RESAMPLER_ARGUMENT = "russianRuntimeResampler"
        private const val CORPUS_DIRECTORY = "speech-control"
        private const val CANDIDATE_DIRECTORY = "speech-candidate-en-2023-06-26"
        private const val LEADING_SILENCE = 16_000 / 2
        private const val TRAILING_SILENCE = 16_000 * 3 / 2
        private const val READ_SAMPLES = 2_048
        private const val RECOGNIZER_CHUNK_SAMPLES = 5_120

        private val CASES = listOf(
            ControlCase("ru-1.wav", EvaluationSession.russianToEnglish, listOf("сегодня", "семь", "встреча", "входа")),
            ControlCase("ru-2.wav", EvaluationSession.russianToEnglish, listOf("не", "автобусе")),
            ControlCase("ru-3.wav", EvaluationSession.russianToEnglish, listOf("два", "билета", "ванкувера")),
            ControlCase("ru-4.wav", EvaluationSession.russianToEnglish, listOf("пятнадцать", "минут")),
            ControlCase("en-1.wav", EvaluationSession.englishToRussian, listOf("meeting", "seven", "entrance")),
            ControlCase("en-2.wav", EvaluationSession.englishToRussian, listOf("not", "bus")),
            ControlCase("en-3.wav", EvaluationSession.englishToRussian, listOf("two", "tickets", "vancouver")),
            ControlCase("en-4.wav", EvaluationSession.englishToRussian, listOf("fifteen", "minutes")),
        )
    }
}

/** Strict reader shared by the optional quality and sustained-load instrumentation. */
internal fun readMonoPcm16Wave(file: File): ShortArray {
    val bytes = file.readBytes()
    require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var sampleRate = -1
    var dataStart = -1
    var dataBytes = -1
    while (offset + 8 <= bytes.size) {
        val name = String(bytes, offset, 4)
        val size = buffer.getInt(offset + 4)
        require(size >= 0 && offset + 8L + size <= bytes.size)
        if (name == "fmt ") {
            require(size >= 16 && buffer.getShort(offset + 8).toInt() == 1)
            require(buffer.getShort(offset + 10).toInt() == 1)
            sampleRate = buffer.getInt(offset + 12)
            require(buffer.getShort(offset + 22).toInt() == 16)
        }
        if (name == "data") {
            dataStart = offset + 8
            dataBytes = size
            break
        }
        offset += 8 + size + size % 2
    }
    require(sampleRate == SpeechSegment.SAMPLE_RATE_HZ && dataStart >= 0 && dataBytes > 0)
    return ShortArray(dataBytes / 2) { buffer.getShort(dataStart + it * 2) }
}
