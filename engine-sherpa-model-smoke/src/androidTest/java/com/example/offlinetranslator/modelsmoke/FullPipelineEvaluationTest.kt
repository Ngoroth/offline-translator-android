package com.example.offlinetranslator.modelsmoke

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

object FullPipelineEvaluationTest {
    suspend fun measure(cases: List<CorpusCase>,result: JSONObject) = coroutineScope {
        val selected = EvaluationSession.selected()
        val store = EvaluationSession.store()
        val fixedStore = EvaluationSession.store(false)
        val asr = EvaluationSession.recognizer(store)
        val oracle = EvaluationSession.recognizer(fixedStore)
        val mt = EvaluationSession.translationEngine()
        try {
            require(mt.modelAvailability() == ModelAvailability.READY)
            for(language in listOf("ru","en")) {
                val group = cases.filter { it.language == language }
                val target = if(language == "ru") "en" else "ru"
                if(selected.role == EvaluationModelRole.TTS && selected.language != target) continue
                if(selected.role == EvaluationModelRole.ASR && selected.language != language) continue
                EvaluationVoice(target).use { voice ->
                    val runner = FullPathRunner(store,asr,oracle,mt,voice,result)
                    val warmup = runner.run(group.first(),this,true)
                    result.put("fullWarmup-$language",warmup)
                    if(!warmup.getBoolean("valid")) result.getJSONArray("reasons").put("fullWarmup/$language")
                    for((index,case) in group.withIndex()) {
                        val row = try { runner.run(case,this,false,index < 2) } catch(failure: Exception) {
                            JSONObject().put("id",case.id).put("valid",false).put("notMeasured",failure.toString())
                        }
                        result.getJSONArray("full").put(row)
                        if(!row.getBoolean("valid")) result.getJSONArray("reasons").put("full/${case.id}")
                        EvaluationSession.writeResult(result)
                    }
                }
            }
        } finally { asr.closeAndJoin();oracle.closeAndJoin();mt.closeAndJoin();store.close();fixedStore.close() }
    }
}

/** Await synthesis within the production translation boundary, so the queue includes all work. */
internal class FullPathRunner(
    private val store: SpeechModelStore,
    private val asr: SpeechRecognitionEngine,
    private val oracle: SpeechRecognitionEngine,
    private val mt: TranslationEngine,
    private val voice: EvaluationVoice,
    private val result: JSONObject,
) {
    suspend fun run(case: CorpusCase,scope: CoroutineScope,warmup: Boolean,retain: Boolean = false): JSONObject {
        val direction = EvaluationSession.pair(case)
        val pcm = EvaluationSession.padded(case)
        val emissions = ConcurrentLinkedQueue<Long>()
        val capture = MeasuringCapture(store,pcm,emissions)
        val outputs = JSONArray()
        val translation = SynthesisTranslation(mt,voice,oracle,emissions,outputs,if(retain) case.id else null,warmup)
        val recognitionMeasurements=JSONArray()
        val measuringAsr=object: SpeechRecognitionEngine {
            override suspend fun recognize(direction: LanguagePair,segment: SpeechSegment): String {
                val emission=requireNotNull(emissions.peek());val compute=nowNanos()
                val text=asr.recognize(direction,segment)
                recognitionMeasurements.put(JSONObject().put("text",text).put("computeMs",millisSince(compute)).put("latencyMs",millisSince(emission)))
                return text
            }
            override fun close()=Unit
        }
        val resources = EvaluationSession.speechResources(store)
        val pipeline = SpeechToTextPipeline(capture,measuringAsr,resources,translation,scope)
        try {
            require(pipeline.start(direction)); pipeline.stop()
            val state = withTimeout(30000) { pipeline.state.first { !it.sessionActive || it.phase == SpeechPipelinePhase.ERROR } }
            val text = state.transcripts.joinToString(" ") { it.original }
            val valid = !state.sessionActive && !state.queueOverflow && state.droppedSegments == 0 && outputs.length() in 1..8 &&
                state.transcripts.all { it.status == SpeechTranscriptStatus.COMPLETE } && SemanticChecks.missing(text,case.preparedPhrases).isEmpty() &&
                (0 until outputs.length()).all { outputs.getJSONObject(it).getBoolean("valid") } &&
                (warmup || (0 until recognitionMeasurements.length()).all { recognitionMeasurements.getJSONObject(it).getDouble("latencyMs") <= 1500 })
            return JSONObject().put("id",case.id).put("language",case.language).put("warmup",warmup).put("reference",case.reference)
                .put("recognized",text).put("segments",outputs).put("valid",valid).put("drops",state.droppedSegments)
                .put("asr",recognitionMeasurements)
                .put("error",state.error?.toString() ?: JSONObject.NULL)
        } finally { pipeline.clear();capture.close();resources.close() }
    }
}

internal class SynthesisTranslation(
    private val mt: TranslationEngine, private val voice: EvaluationVoice, private val oracle: SpeechRecognitionEngine,
    private val emissions: ConcurrentLinkedQueue<Long>,val outputs: JSONArray,private val sampleId: String? = null,
    private val warmup: Boolean = false,
) : TranslationEngine {
    override val preparation get() = mt.preparation
    override suspend fun modelAvailability() = mt.modelAvailability()
    override suspend fun prepare(): ModelAvailability = error("Offline evaluation cannot prepare models")
    override suspend fun translate(direction: LanguagePair,text: String): String {
        val emission = requireNotNull(emissions.poll())
        val translated = mt.translate(direction,text)
        val typed = mt.translate(direction,text)
        val file = File(EvaluationSession.root,"temporary-full.wav")
        try {
            val signal = voice.synthesize(translated,file,warmup)
            val latency = millisSince(emission)
            val reverseDirection = direction.reverse()
            val reverse = oracle.recognize(reverseDirection,SpeechSegment.fromPcm(AudioResampler.resample(WaveReader.read(file,22050).samples,22050)))
            val words = TextMetrics.words(translated,reverse);val chars = TextMetrics.characters(translated,reverse)
            val script = if(direction == EvaluationSession.russianToEnglish) Regex("[A-Za-z]") else Regex("[А-Яа-яЁё]")
            val valid = translated == typed && script.containsMatchIn(translated) && signal.getBoolean("valid") &&
                reverse.isNotBlank() && words.rate <= .30 && chars.rate <= .15 && (warmup || latency <= 8000)
            if(sampleId != null && outputs.length() == 0) TtsEvaluationTest.retainSample(file,"full-$sampleId.wav")
            outputs.put(JSONObject().put("original",text).put("translated",translated).put("typedIdenticalInput",typed)
                .put("reverse",reverse).put("wer",words.rate).put("cer",chars.rate).put("wordErrors",words.errors)
                .put("referenceWords",words.referenceCount).put("characterErrors",chars.errors).put("referenceCharacters",chars.referenceCount)
                .put("signal",signal).put("latencyMs",latency).put("valid",valid))
            return translated
        } finally { file.delete() }
    }
    override fun close() = Unit
}

internal class MeasuringCapture(private val store: SpeechModelStore,private val pcm: ShortArray,private val emissions: ConcurrentLinkedQueue<Long>): SpeechCaptureEngine {
    private var vad: SherpaSpeechSegmenter? = null
    override suspend fun start(listener: SpeechCaptureListener) {
        val segmenter = EvaluationSession.segmenter(store);vad = segmenter
        val padded = pcm
        for(offset in padded.indices step 2048) segmenter.accept(padded,offset,minOf(2048,padded.size-offset)) {
            emissions.add(nowNanos());listener.onSegment(it)
        }
    }
    override fun interruptCurrent() = close()
    override suspend fun stop(flush: Boolean): SpeechSegment? {
        val remaining = ArrayList<SpeechSegment>()
        if(flush) vad?.flush { emissions.add(nowNanos());remaining.add(it) }
        close();require(remaining.size <= 1);return remaining.singleOrNull()
    }
    override fun close() { vad?.close();vad = null }
}
