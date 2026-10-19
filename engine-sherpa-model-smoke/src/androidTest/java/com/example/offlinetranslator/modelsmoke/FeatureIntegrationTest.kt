package com.example.offlinetranslator.modelsmoke

import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FeatureIntegrationTest {
    @Test fun isolatedFakeFailuresRetryAndClearLateCallbacksWithoutAudioInput(): Unit = runBlocking<Unit> {
        var captureListener: SpeechCaptureListener?=null
        var stops=0
        val capture=object: SpeechCaptureEngine {
            override suspend fun start(listener: SpeechCaptureListener) {captureListener=listener}
            override suspend fun stop(flush: Boolean): SpeechSegment? {stops++;return null}
            override fun interruptCurrent()=Unit
            override fun close()=Unit
        }
        var fail=true;var gate: CompletableDeferred<String>?=null
        val recognizer=object: SpeechRecognitionEngine {
            override suspend fun recognize(direction: LanguagePair,segment: SpeechSegment): String {
                gate?.let { return withContext(NonCancellable) { it.await() } }
                if(fail) {fail=false;error("injected ASR failure")};return "recognized"
            }
            override fun close()=Unit
        }
        val models=object: SpeechModelRepository {
            override val models=kotlinx.coroutines.flow.MutableStateFlow<List<SpeechModelStatus>>(emptyList())
            override suspend fun isReady(pair: LanguagePair)=true
            override suspend fun install(): Unit=error("offline fake cannot prepare")
            override fun close()=Unit
        }
        val mt=object: TranslationEngine {
            override suspend fun modelAvailability()=ModelAvailability.READY
            override suspend fun prepare(): ModelAvailability=error("offline fake cannot prepare")
            override suspend fun translate(pair: LanguagePair,text: String)="translated"
            override fun close()=Unit
        }
        val pipeline=SpeechToTextPipeline(capture,recognizer,models,mt,this)
        try {
            assertTrue(pipeline.start(EvaluationSession.russianToEnglish))
            captureListener!!.onSegment(SpeechSegment.fromPcm(shortArrayOf(1)))
            withTimeout(5000) {pipeline.state.first {it.phase==SpeechPipelinePhase.ERROR}}
            assertTrue(pipeline.retry());pipeline.stop();pipeline.stop()
            val drained=withTimeout(5000) {pipeline.state.first {!it.sessionActive}}
            assertEquals(SpeechTranscriptStatus.COMPLETE,drained.transcripts.single().status);assertEquals(1,stops)
            assertTrue(pipeline.start(EvaluationSession.englishToRussian));gate=CompletableDeferred()
            captureListener!!.onSegment(SpeechSegment.fromPcm(shortArrayOf(2)))
            withTimeout(5000) {pipeline.state.first {it.phase==SpeechPipelinePhase.RECOGNIZING}}
            pipeline.clear();gate!!.complete("late callback");yield()
            assertTrue(pipeline.state.value.transcripts.isEmpty());assertEquals(0,pipeline.state.value.queuedSegments)
        } finally {gate?.complete("cleanup");pipeline.clear()}
    }
    @Test fun freshOfflineTextAndProductionWavPipelineBothDirections() = runBlocking {
        assertNull((EvaluationSession.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork)
        InputVerifier.verify()
        val store=EvaluationSession.store()
        val asr=EvaluationSession.recognizer(store)
        val mt=EvaluationSession.translationEngine()
        try {
            assertEquals(ModelAvailability.READY,mt.modelAvailability())
            val evidence=org.json.JSONObject().put("offline",true).put("readiness","READY")
                .put("greetings",org.json.JSONArray()).put("typed",org.json.JSONArray()).put("wav",org.json.JSONArray())
            for((direction,text) in listOf(EvaluationSession.russianToEnglish to "Доброе утро",EvaluationSession.englishToRussian to "Good evening")) {
                val output=mt.translate(direction,text)
                val script=if(direction==EvaluationSession.russianToEnglish) Regex("[A-Za-z]") else Regex("[А-Яа-яЁё]")
                assertTrue(script.containsMatchIn(output));assertEquals(output,mt.translate(direction,text))
                evidence.getJSONArray("greetings").put(org.json.JSONObject().put("source",direction.source.value).put("target",direction.target.value).put("input",text).put("output",output))
            }
            val cases=CorpusManifest.read(EvaluationSession.corpus).filter { it.cohort=="smoke" && "official" !in it.id }
            for(case in cases) {
                val direction=EvaluationSession.pair(case)
                val typed=mt.translate(direction,case.reference)
                val script=if(direction==EvaluationSession.russianToEnglish) Regex("[A-Za-z]") else Regex("[А-Яа-яЁё]")
                assertTrue(script.containsMatchIn(typed)); assertEquals(typed,mt.translate(direction,case.reference))
                evidence.getJSONArray("typed").put(org.json.JSONObject().put("id",case.id).put("input",case.reference).put("output",typed))
                val samples=WaveReader.read(File(EvaluationSession.corpus,case.path),16000).samples
                val capture=FixtureCapture(store,samples)
                val resources=EvaluationSession.speechResources(store)
                val pipeline=SpeechToTextPipeline(capture,asr,resources,mt,this)
                try {
                    assertTrue(pipeline.start(direction)); pipeline.stop()
                    val state=withTimeout(30000) { pipeline.state.first { !it.sessionActive } }
                    assertFalse(state.queueOverflow); assertEquals(0,state.droppedSegments)
                    assertTrue(state.transcripts.isNotEmpty())
                    val transcripts=org.json.JSONArray()
                    for(row in state.transcripts) {
                        assertEquals(SpeechTranscriptStatus.COMPLETE,row.status)
                        assertEquals(mt.translate(direction,row.original),row.translation)
                        transcripts.put(org.json.JSONObject().put("original",row.original).put("translation",row.translation)
                            .put("typedIdenticalInput",mt.translate(direction,row.original)))
                    }
                    evidence.getJSONArray("wav").put(org.json.JSONObject().put("id",case.id).put("transcripts",transcripts))
                    EvaluationSession.writeEvidence("feature",evidence)
                } finally { pipeline.clear(); capture.close();resources.close() }
            }
        } finally { asr.closeAndJoin();mt.closeAndJoin();store.close() }
    }
}

internal class FixtureCapture(private val store: SpeechModelStore, private val pcm: ShortArray):SpeechCaptureEngine {
    private var segmenter:SherpaSpeechSegmenter?=null
    override suspend fun start(listener:SpeechCaptureListener) {
        val vad=EvaluationSession.segmenter(store);segmenter=vad
        val padded=ShortArray(8000+pcm.size+24000);pcm.copyInto(padded,8000)
        for(offset in padded.indices step 2048) vad.accept(padded,offset,minOf(2048,padded.size-offset),listener::onSegment)
    }
    override fun interruptCurrent() { close() }
    override suspend fun stop(flush:Boolean):SpeechSegment? {
        val remaining=ArrayList<SpeechSegment>()
        if(flush) segmenter?.flush(remaining::add)
        close(); require(remaining.size<=1)
        return remaining.singleOrNull()
    }
    override fun close() {segmenter?.close();segmenter=null}
}
