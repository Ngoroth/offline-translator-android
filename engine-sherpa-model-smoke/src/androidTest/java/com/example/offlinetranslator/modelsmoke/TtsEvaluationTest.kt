package com.example.offlinetranslator.modelsmoke

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.testkit.*
import org.json.JSONObject
import java.io.File

/** The reverse recognizer is always the fixed production baseline, including custom voice runs. */
object TtsEvaluationTest {
    suspend fun measure(cases: List<CorpusCase>,result: JSONObject) {
        val store = EvaluationSession.store(useCandidate = false)
        val oracle = EvaluationSession.recognizer(store)
        try {
            EvaluationVoice(cases.first().language).use { voice ->
                val temp = File(EvaluationSession.root,"temporary-tts.wav")
                try {
                    val direction = EvaluationSession.pair(cases.first())
                    val warmup = voice.synthesize(cases.first().reference,temp,warmup=true)
                    val warmText = oracle.recognize(direction,SpeechSegment.fromPcm(AudioResampler.resample(WaveReader.read(temp,22050).samples,22050)))
                    warmup.put("reverse",warmText);result.put("warmupTts",warmup)
                    if(!warmup.getBoolean("valid") || warmText.isBlank()) result.getJSONArray("reasons").put("invalidTtsWarmup")
                    for((index,case) in cases.withIndex()) {
                        EvaluationSession.faultCheckpoint(result.getJSONArray("standalone").length())
                        val row = try {
                            val signal = voice.synthesize(case.reference,temp)
                            val reverse = oracle.recognize(direction,SpeechSegment.fromPcm(AudioResampler.resample(WaveReader.read(temp,22050).samples,22050)))
                            if(index < 2) retainSample(temp,"own-${case.id}.wav")
                            JSONObject().put("id",case.id).put("reference",case.reference).put("language",case.language)
                                .put("reverse",reverse).put("signal",signal).put("missingPhrases",org.json.JSONArray(SemanticChecks.missing(reverse,case.phrases)))
                                .put("validOutput",signal.getBoolean("valid") && reverse.isNotBlank())
                        } catch(failure: Exception) { JSONObject().put("id",case.id).put("reverse","").put("validOutput",false).put("notMeasured",failure.toString()) }
                        result.getJSONArray("standalone").put(row)
                        if(!row.getBoolean("validOutput")) result.getJSONArray("reasons").put("TTS/output/${case.id}")
                        EvaluationSession.writeResult(result)
                    }
                    result.put("ttsScore",ModelEvaluationTest.score(cases,result.getJSONArray("standalone"),"reverse",cases.first().language,.30,.15,result.getJSONArray("reasons")))
                } finally { temp.delete() }
            }
        } finally { oracle.closeAndJoin();store.close() }
    }
    fun retainSample(file: File,name: String) {
        require(Integrity.safePath(name) && '/' !in name)
        val directory = File(EvaluationSession.root,"samples");directory.mkdirs()
        require((directory.listFiles()?.sumOf(File::length) ?: 0) + file.length() <= 32L*1024*1024)
        file.copyTo(File(directory,name),overwrite = true)
    }
}
