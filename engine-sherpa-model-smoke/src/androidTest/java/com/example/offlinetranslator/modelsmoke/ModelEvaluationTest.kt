package com.example.offlinetranslator.modelsmoke

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.testkit.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Quality verdicts are data; assertion/instrumentation failures are infrastructure failures. */
@RunWith(AndroidJUnit4::class)
class ModelEvaluationTest {
    @Test fun evaluatesSelectedCandidateWithFixedGates(): Unit = runBlocking<Unit>(Dispatchers.Default) {
        InputVerifier.verify()
        val selected = EvaluationSession.selected()
        val result = JSONObject().put("candidate",selected.id).put("verdict","FAIL").put("reasons",JSONArray())
            .put("standalone",JSONArray()).put("full",JSONArray()).put("unmeasured",JSONArray())
        val network=EvaluationSession.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        result.put("offline",network.activeNetwork==null)
        EvaluationSession.writeResult(result)
        val reasons = result.getJSONArray("reasons")
        val cases = EvaluationSession.cases.filter { it.cohort == "quality" }
        try {
            require(result.getBoolean("offline")) {"Measurement process has an active network"}
            val load = nowNanos()
            val store = EvaluationSession.store()
            val asr = EvaluationSession.recognizer(store)
            try {
                result.put("coldStoreLoadMs",millisSince(load))
                if(selected.role == EvaluationModelRole.TTS) {
                    TtsEvaluationTest.measure(cases.filter { it.language == selected.language },result)
                } else {
                    for(language in if(selected.language == "both") listOf("ru","en") else listOf(selected.language)) {
                        val group = cases.filter { it.language == language }
                        val warmup = recognizeCase(group.first(),store,asr)
                        result.put("warmup-$language",warmup)
                        if(!warmup.getBoolean("validOutput")) reasons.put("invalidWarmup/$language")
                        for(case in group) {
                            EvaluationSession.faultCheckpoint(result.getJSONArray("standalone").length())
                            val row = try { recognizeCase(case,store,asr) } catch(failure: Exception) {
                                JSONObject().put("id",case.id).put("reference",case.reference).put("production","")
                                    .put("direct","").put("validOutput",false).put("notMeasured",failure.toString())
                            }
                            result.getJSONArray("standalone").put(row)
                            EvaluationSession.writeResult(result)
                        }
                        result.put("asrScore-$language",score(group,result.getJSONArray("standalone"),"production",language,.20,.10,reasons))
                        result.put("directScore-$language",score(group,result.getJSONArray("standalone"),"direct",language,Double.MAX_VALUE,Double.MAX_VALUE,JSONArray()))
                        val rows = (0 until result.getJSONArray("standalone").length()).map(result.getJSONArray("standalone")::getJSONObject)
                            .filter { it.optString("language") == language }
                        if(rows.size != 24 || rows.any { !it.optBoolean("validOutput") || !EvaluationGates.finiteAtMost(it.optDouble("latencyMs",Double.NaN),1500.0) }) reasons.put("ASR/VAD/output/timing/$language")
                        val compute = rows.sumOf { it.optDouble("computeMs",Double.NaN) }
                        val seconds = group.sumOf { it.json.getDouble("durationMs") } / 1000
                        val rtf = compute / 1000 / seconds
                        result.put("asrRtf-$language",if(rtf.isFinite()) rtf else JSONObject.NULL)
                        if(!EvaluationGates.finiteAtMost(rtf,1.0)) reasons.put("ASR/RTF/$language")
                    }
                }
            } finally { asr.closeAndJoin(); store.close() }
            result.put("standaloneVerdict",if(reasons.length()==0) "PASS" else "FAIL")
            result.put("phase","full");EvaluationSession.writeResult(result)
            // Standalone failures do not suppress complete-path measurements.
            val beforeFull=reasons.length()
            FullPipelineEvaluationTest.measure(cases,result)
            result.put("fullVerdict",if(reasons.length()==beforeFull) "PASS" else "FAIL")
            result.put("phase","stress");EvaluationSession.writeResult(result)
            ControlledSpeechStressTest.measure(result)
        } catch(failure: Exception) { reasons.put("scenarioFailure: $failure") }
        finally {
            val measured = result.getJSONArray("standalone")
            val ids = (0 until measured.length()).map { measured.getJSONObject(it).optString("id") }.toSet()
            cases.filter { (selected.language == "both" || it.language == selected.language) && it.id !in ids }
                .forEach { result.getJSONArray("unmeasured").put(JSONObject().put("id",it.id).put("metrics",JSONObject.NULL).put("notMeasured","scenario interrupted")) }
            result.put("releaseAtNanos",nowNanos())
            EvaluationSession.writeResult(result)
            delay(10000)
            result.put("releaseMemory",MemorySampler.snapshot())
            result.put("releasePlus10AtNanos",nowNanos()).put("verdict",if(reasons.length() == 0) "PASS" else "FAIL")
            EvaluationSession.writeResult(result)
        }
    }

    private suspend fun recognizeCase(case: CorpusCase,store: SpeechModelStore,asr: SpeechRecognitionEngine): JSONObject {
        val direction = EvaluationSession.pair(case)
        val pcm = WaveReader.read(File(EvaluationSession.corpus,case.path),16000).samples
        val directStart = nowNanos()
        val direct = asr.recognize(direction,SpeechSegment.fromPcm(pcm))
        val directMs = millisSince(directStart)
        val outputs = ArrayList<String>(); val segments = JSONArray()
        var compute = 0.0; var latency = 0.0
        val vad = EvaluationSession.segmenter(store)
        try {
            val input = EvaluationSession.padded(case)
            val pending = ArrayList<Pair<SpeechSegment,Long>>()
            fun emitted(segment: SpeechSegment) { require(segments.length() + pending.size < 8); pending.add(segment to nowNanos()) }
            for(offset in input.indices step 2048) {
                vad.accept(input,offset,minOf(2048,input.size-offset),::emitted)
                for((segment,start) in pending) {
                    val computeStart = nowNanos()
                    val text = asr.recognize(direction,segment)
                    val elapsed = millisSince(computeStart); compute += elapsed
                    val final = millisSince(start); latency = maxOf(latency,final)
                    outputs += text
                    segments.put(JSONObject().put("samples",segment.sampleCount).put("text",text).put("computeMs",elapsed).put("latencyMs",final))
                }
                pending.clear()
            }
            vad.flush(::emitted)
            for((segment,start) in pending) {
                val computeStart = nowNanos(); val text = asr.recognize(direction,segment)
                val elapsed = millisSince(computeStart); compute += elapsed
                val final = millisSince(start); latency = maxOf(latency,final); outputs += text
                segments.put(JSONObject().put("samples",segment.sampleCount).put("text",text).put("computeMs",elapsed).put("latencyMs",final))
            }
        } finally { vad.close() }
        val text = outputs.joinToString(" ").trim()
        return JSONObject().put("id",case.id).put("language",case.language).put("reference",case.reference).put("hash",case.json.getString("sha256"))
            .put("direct",direct).put("directMs",directMs).put("production",text).put("computeMs",compute).put("latencyMs",latency)
            .put("segments",segments).put("missingPhrases",JSONArray(SemanticChecks.missing(text,case.preparedPhrases)))
            .put("validOutput",direct.isNotBlank() && outputs.isNotEmpty() && outputs.all(String::isNotBlank) && SemanticChecks.missing(text,case.preparedPhrases).isEmpty())
    }
    companion object {
        fun score(cases: List<CorpusCase>,rows: JSONArray,key: String,language: String,werLimit: Double,cerLimit: Double,reasons: JSONArray): JSONObject {
            var words = EditScore(0,0); var chars = EditScore(0,0)
            for(case in cases) {
                val row = (0 until rows.length()).map(rows::getJSONObject).find { it.optString("id") == case.id }
                val actual = row?.optString(key).orEmpty()
                words += TextMetrics.words(case.preparedReference,actual); chars += TextMetrics.characters(case.preparedReference,actual)
                if(actual.isBlank() || SemanticChecks.missing(actual,case.preparedPhrases).isNotEmpty()) reasons.put("semantics/$key/${case.id}")
            }
            if(!EvaluationGates.finiteAtMost(words.rate,werLimit) || !EvaluationGates.finiteAtMost(chars.rate,cerLimit)) reasons.put("quality/$key/$language: WER=${words.rate} CER=${chars.rate}")
            return JSONObject().put("wordErrors",words.errors).put("referenceWords",words.referenceCount).put("wer",words.rate)
                .put("characterErrors",chars.errors).put("referenceCharacters",chars.referenceCount).put("cer",chars.rate)
        }
    }
}
