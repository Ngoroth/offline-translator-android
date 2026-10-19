package com.example.offlinetranslator.testkit

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class EvaluationContractTest {
    @Test fun historicalCorpusPairsAreCheckedOnceWithoutChangingInputIdentity() {
        val corpus = File(requireNotNull(System.getProperty("evaluation.repo")), "test-data/corpus/v1")
        val cases = CorpusManifest.read(corpus)
        assertEquals(58, cases.size)
        assertEquals(8334120L, cases.sumOf { it.json.getLong("bytes") })
        for (case in cases) {
            assertEquals(case.language, case.pair.sourceId)
            assertEquals(if (case.language == "ru") "en" else "ru", case.pair.targetId)
        }
        val original = cases.first()
        val invalid = org.json.JSONObject(original.json.toString())
        invalid.put("direction", "en-de")
        assertThrows(IllegalArgumentException::class.java) { CorpusCase(invalid) }
        invalid.put("direction", if (original.language == "ru") "EN_TO_RU" else "RU_TO_EN")
        assertThrows(IllegalArgumentException::class.java) { CorpusCase(invalid) }
        assertThrows(IllegalArgumentException::class.java) { EvaluationLanguagePair("en", "en") }
        assertThrows(IllegalArgumentException::class.java) { EvaluationLanguagePair("../en", "ru") }
    }

    @Test fun preparedReferenceRepresentationsPreserveAllScoringRules() {
        for(reference in listOf("Ёлка 42", "don't sign", "don’t sign", "\uD801\uDC00 word", "1,234.5")) {
            val prepared=TextMetrics.prepare(reference)
            for(actual in listOf(reference,"","different 42 ё")) {
                assertEquals(TextMetrics.words(reference,actual),TextMetrics.words(prepared,actual))
                assertEquals(TextMetrics.characters(reference,actual),TextMetrics.characters(prepared,actual))
                val phrases=listOf(reference,"42")
                assertEquals(SemanticChecks.missing(actual,phrases),SemanticChecks.missing(actual,SemanticChecks.prepare(phrases)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {TextMetrics.prepare("x".repeat(4097))}
    }
    @Test fun queuePeakCannotDisappearWhenObserversSeeOnlyTheDrainedState() {
        val counter=PendingSegments()
        repeat(3) {counter.admitted()}
        repeat(3) {counter.recognitionStarted()}
        assertEquals(0,counter.count);assertEquals(3,counter.peak)
        assertThrows(IllegalStateException::class.java) {counter.recognitionStarted()}
    }
    @Test fun jsonDepthIsBoundedBeforeRecursiveParsing() {
        val file=File.createTempFile("nested-input", ".json")
        try {
            file.writeText("{\"value\":"+"[".repeat(33)+"0"+"]".repeat(33)+"}")
            assertThrows(IllegalArgumentException::class.java) {Integrity.json(file)}
            file.writeText("{\"value\":\"escaped \\\" [ { text\"}")
            assertTrue(Integrity.json(file).getString("value").contains("[ {"))
        } finally {file.delete()}
    }
    @Test fun canonicalCorpusRequiresAttributionAndRejectsUnlistedDocuments() {
        val repo = File(requireNotNull(System.getProperty("evaluation.repo")))
        val directory = kotlin.io.path.createTempDirectory("corpus-attribution").toFile()
        try {
            File(repo, "test-data/corpus/v1").copyRecursively(directory, overwrite = true)
            val cases = CorpusManifest.read(directory)
            assertEquals(58, cases.size)
            val attribution = File(directory, "ATTRIBUTION.md")
            val originalAttribution = attribution.readBytes()
            assertTrue(attribution.delete())
            assertThrows(IllegalArgumentException::class.java) { CorpusManifest.read(directory) }
            attribution.writeBytes(originalAttribution)
            for (path in listOf("README.md", "ATTRIBUTION-copy.md", "smoke/ATTRIBUTION.md")) {
                val extra = File(directory, path)
                extra.writeText("Unlisted corpus document")
                assertThrows(IllegalArgumentException::class.java) { CorpusManifest.read(directory) }
                assertTrue(extra.delete())
            }
            assertEquals(cases.map { it.id }, CorpusManifest.read(directory).map { it.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun corpusRejectsDeepCopiedAttribution() {
        val repo = File(requireNotNull(System.getProperty("evaluation.repo")))
        val directory = kotlin.io.path.createTempDirectory("corpus-deep-attribution").toFile()
        try {
            File(repo, "test-data/corpus/v1").copyRecursively(directory, overwrite = true)
            val cases = CorpusManifest.read(directory, true)
            assertEquals(58, cases.size)
            val extra = File(directory, "a/b/c/d/e/f/ATTRIBUTION.md")
            assertTrue(requireNotNull(extra.parentFile).mkdirs())
            File(directory, "ATTRIBUTION.md").copyTo(extra)
            assertThrows(IllegalArgumentException::class.java) { CorpusManifest.read(directory, true) }
            assertTrue(File(directory, "a").deleteRecursively())
            assertEquals(cases.map { it.id }, CorpusManifest.read(directory, true).map { it.id })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun corpusRejectsDirectoryCountBeyondTraversalBudget() {
        val repo = File(requireNotNull(System.getProperty("evaluation.repo")))
        val directory = kotlin.io.path.createTempDirectory("corpus-member-budget").toFile()
        try {
            File(repo, "test-data/corpus/v1").copyRecursively(directory, overwrite = true)
            CorpusManifest.read(directory, true)
            repeat(129) { assertTrue(File(directory, "extra-$it").mkdir()) }
            assertThrows(IllegalArgumentException::class.java) { CorpusManifest.read(directory, true) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun corpusCannotInflateDurationsOrAddUnlistedWavs() {
        val repo=File(requireNotNull(System.getProperty("evaluation.repo")))
        val directory=kotlin.io.path.createTempDirectory("corpus-identity").toFile()
        try {
            File(repo,"test-data/corpus/v1").copyRecursively(directory,overwrite=true)
            CorpusManifest.read(directory, false)
            val manifest=File(directory,"manifest.json")
            val original=manifest.readText()
            val doc=org.json.JSONObject(original)
            val cases=doc.getJSONArray("cases")
            val quality=(0 until cases.length()).map(cases::getJSONObject).first {it.getString("cohort")=="quality"}
            quality.put("durationMs",1000000);manifest.writeText(doc.toString())
            assertThrows(IllegalArgumentException::class.java) {CorpusManifest.read(directory,false)}
            manifest.writeText(original)
            File(directory,"unlisted.wav").writeBytes(byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java) {CorpusManifest.read(directory,false)}
        } finally {directory.deleteRecursively()}
    }
    @Test fun synthesisCallbackHasNativeBoxedSignatureAndStopsUnsafeOutputWithoutThrowing() {
        assertEquals(java.lang.Integer::class.java,BoundedSynthesisCallback::class.java.getDeclaredMethod("invoke",FloatArray::class.java).returnType)
        val callback=BoundedSynthesisCallback()
        assertEquals(1,callback.invoke(FloatArray(330750)))
        assertEquals(0,callback.invoke(floatArrayOf(0f)));assertFalse(callback.valid)
        val invalid=BoundedSynthesisCallback();assertEquals(0,invalid.invoke(floatArrayOf(Float.NaN)));assertFalse(invalid.valid)
    }
    @Test fun stressTimelineIsExactAndStreamsThirtyTwoPinnedInsertions() {
        val file = File.createTempFile("stress", ".wav")
        try {
            val corpus = File(requireNotNull(System.getProperty("evaluation.repo")),"test-data/corpus/v1")
            val boundary = StressTimeline.write(corpus,file)
            assertTrue(boundary in 1 until StressTimeline.TOTAL_SAMPLES)
            assertEquals(3840044,file.length())
            java.io.RandomAccessFile(file,"r").use { input ->
                input.seek(44L + (StressTimeline.TOTAL_SAMPLES-StressTimeline.FINAL_PADDING)*2)
                val padding = ByteArray(StressTimeline.FINAL_PADDING*2);input.readFully(padding)
                assertTrue(padding.all { it == 0.toByte() })
            }
        } finally { file.delete() }
    }
    @Test fun pinnedModelAndCorpusSchemasRejectUnsupportedRuntimeAndDuplicateIds() {
        val root = File(requireNotNull(System.getProperty("evaluation.repo")))
        val models = ModelManifest.read(File(root,"test-data/model-sets/baseline.json"))
        assertEquals(5, models.size)
        assertEquals(5, ModelManifest.merge(models, models).size)
        assertEquals(58, CorpusManifest.read(File(root,"test-data/corpus/v1")).size)
        val file = File.createTempFile("bad-model", ".json")
        try {
            val doc = org.json.JSONObject(File(root,"test-data/model-sets/baseline.json").readText())
            doc.put("runtime", "unknown")
            file.writeText(doc.toString())
            assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(file) }
            doc.put("runtime", ModelManifest.RUNTIME)
            doc.getJSONArray("candidates").put(doc.getJSONArray("candidates").getJSONObject(0))
            file.writeText(doc.toString())
            assertThrows(IllegalArgumentException::class.java) { ModelManifest.read(file) }
        } finally { file.delete() }
    }
    @Test fun languageAggregatesUseEditNumeratorsNotCaseMeans() {
        val score = TextMetrics.words("One two three four", "one two four") + TextMetrics.words("five", "six")
        assertEquals(2, score.errors); assertEquals(5, score.referenceCount); assertEquals(.4, score.rate, 1e-9)
        assertEquals(0, TextMetrics.words("HELLO, world!", "hello world").errors)
        assertEquals(1, TextMetrics.characters("ё", "е").errors)
    }
    @Test fun strictSemanticsNeverFuzzNegationOrNumber() {
        assertTrue(SemanticChecks.missing("I don't take the bus", listOf("do not", "bus")).isEmpty())
        assertEquals(listOf("not", "two"), SemanticChecks.missing("I now book to tickets", listOf("not", "two")))
        assertTrue(SemanticChecks.missing("зелёный", listOf("зеленый")).isEmpty())
    }
    @Test fun gateBoundariesAndMissingMeasurements() {
        assertTrue(EvaluationGates.asr(.20, .10, 0, 0, 0, 1.0))
        assertFalse(EvaluationGates.asr(.20001, .10, 0, 0, 0, 1.0))
        assertFalse(EvaluationGates.asr(.1, .1, 1, 0, 0, 1.0))
        assertFalse(EvaluationGates.finiteAtMost(Double.NaN, 1.0))
        assertFalse(EvaluationGates.finiteAtMost(null, 1.0))
        assertFalse(EvaluationGates.signal(SignalMetrics(100.0, .0, .0)))
        assertFalse(EvaluationGates.signal(SignalMetrics(101.0, .98, .0)))
        assertFalse(EvaluationGates.signal(SignalMetrics(Double.POSITIVE_INFINITY, .0, .0)))
        assertFalse(EvaluationGates.signal(SignalMetrics(101.0, -.1, .0)))
        assertFalse(EvaluationGates.signal(SignalMetrics(101.0, .0, .01)))
    }
    @Test fun waveReaderRejectsMalformedAndWriterRejectsNonfinite() {
        val file = File.createTempFile("pcm", ".wav")
        try {
            WaveWriter.write(file, ShortArray(8000) { 500 }, 16000)
            assertEquals(8000, WaveReader.read(file,16000).samples.size)
            file.appendBytes(byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java) { WaveReader.read(file) }
            assertThrows(IllegalArgumentException::class.java) { WaveWriter.fromFloats(floatArrayOf(Float.NaN)) }
        } finally { file.delete() }
    }
    @Test fun resamplerPreservesDurationDcAndRejectsAboveNyquist() {
        val dc = AudioResampler.resample(ShortArray(22050) { 1000 },22050)
        assertEquals(16000,dc.size)
        assertTrue(dc.drop(100).dropLast(100).all { abs(it.toInt()-1000)<=1 })
        fun rms(hz: Double): Double {
            val output = AudioResampler.resample(ShortArray(22050) { (10000*sin(2*PI*hz*it/22050)).toInt().toShort() },22050)
            return SignalMetrics.measure(output.copyOfRange(100,output.size-100)).rms
        }
        assertTrue(rms(1000.0)>6000); assertTrue(rms(10000.0)<100)
    }
    @Test fun resamplerOverlappedChunksPreserveTheGlobalSamplePhase() {
        val pcm=ShortArray(66150) {index -> (12000*sin(2*PI*733*index/22050)).roundToInt().toShort()}
        val full=AudioResampler.resample(pcm,22050)
        val left=AudioResampler.resample(pcm.copyOfRange(0,44100),22050)
        val right=AudioResampler.resample(pcm.copyOfRange(22050,66150),22050)
        val reconstructed=left.copyOfRange(0,24000)+right.copyOfRange(8000,32000)
        assertArrayEquals(full,reconstructed)
    }
    @Test fun unsafePathsCannotEscapeRunRoot() {
        listOf("../file","/file","a\\b","C:/file","a//b","a/./b","a/CON.txt","file.","file ","a?b","NUL").forEach { assertFalse(Integrity.safePath(it)) }
        assertTrue(Integrity.safePath("espeak-ng-data/lang/en"))
    }
    @Test fun manifestRejectsParametersThatAdaptersWouldOtherwiseIgnore() {
        val repo=File(requireNotNull(System.getProperty("evaluation.repo")))
        val doc=org.json.JSONObject(File(repo,"test-data/model-sets/baseline.json").readText())
        val temp=File.createTempFile("unsupported-parameter", ".json")
        try {
            val parameters=doc.getJSONArray("candidates").getJSONObject(0).getJSONObject("parameters")
            parameters.put("numThreads",2);temp.writeText(doc.toString())
            assertThrows(IllegalArgumentException::class.java) {ModelManifest.read(temp)}
            parameters.put("numThreads",1).put("unimplementedParameter",true);temp.writeText(doc.toString())
            assertThrows(IllegalArgumentException::class.java) {ModelManifest.read(temp)}
        } finally {temp.delete()}
    }
}
