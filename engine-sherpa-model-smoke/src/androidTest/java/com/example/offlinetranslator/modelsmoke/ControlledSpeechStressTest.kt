package com.example.offlinetranslator.modelsmoke

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.testkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Mandatory evaluation uses real MT, target voice and the fixed reverse recognizer. */
object ControlledSpeechStressTest {
    suspend fun measure(result: JSONObject) = coroutineScope {
        val file = File(EvaluationSession.prepared,"stress.wav")
        val row = JSONObject().put("valid",false).put("completions",JSONArray()).put("asr",JSONArray())
        result.put("stress",row);EvaluationSession.writeResult(result)
        try {
            val boundary = EvaluationSession.stressBoundary
            val store = EvaluationSession.store();val fixedStore = EvaluationSession.store(false)
            val native = EvaluationSession.recognizer(store);val oracle = EvaluationSession.recognizer(fixedStore)
            val mt = EvaluationSession.translationEngine()
            val emitted = AtomicInteger();val pending = PendingSegments();val emissions = ConcurrentLinkedQueue<Long>()
            var drops = 0;var overflow = false;var drainMs = 0.0
            try {
                val clock = nowNanos()
                for(direction in listOf(EvaluationSession.russianToEnglish,EvaluationSession.englishToRussian)) {
                    val ru = direction == EvaluationSession.russianToEnglish
                    val start = if(ru) 0 else boundary;val end = if(ru) boundary else StressTimeline.TOTAL_SAMPLES
                    EvaluationVoice(if(ru) "en" else "ru").use { voice ->
                        val recognizer = object : SpeechRecognitionEngine {
                            override suspend fun recognize(direction: LanguagePair,segment: SpeechSegment): String {
                                pending.recognitionStarted()
                                val emission = requireNotNull(emissions.peek());val compute = nowNanos()
                                val text = native.recognize(direction,segment)
                                row.getJSONArray("asr").put(JSONObject().put("source",direction.source.value).put("target",direction.target.value).put("text",text)
                                    .put("samples",segment.sampleCount).put("computeMs",millisSince(compute)).put("latencyMs",millisSince(emission)))
                                return text
                            }
                            override fun close() = Unit
                        }
                        val translation = SynthesisTranslation(mt,voice,oracle,emissions,row.getJSONArray("completions"))
                        val capture = RealtimeWaveCapture(this,file,store,start,end,clock,emissions,emitted,pending)
                        val resources = EvaluationSession.speechResources(store)
                        val pipeline = SpeechToTextPipeline(capture,recognizer,resources,translation,this)
                        try {
                            require(pipeline.start(direction));capture.awaitComplete();pipeline.stop()
                            val drain = nowNanos()
                            val state = withTimeout(30000) { pipeline.state.first { !it.sessionActive || it.phase == SpeechPipelinePhase.ERROR } }
                            drainMs = maxOf(drainMs,millisSince(drain));drops += state.droppedSegments;overflow = overflow || state.queueOverflow
                            require(!state.sessionActive && state.queuedSegments == 0) { "Stress did not drain: ${state.error}" }
                        } finally { pipeline.clear();capture.close();resources.close() }
                    }
                }
                val rows = row.getJSONArray("asr")
                val measuredRows = (0 until rows.length()).map(rows::getJSONObject)
                val outputs = row.getJSONArray("completions")
                val valid = emitted.get() == 32 && outputs.length() == 32 && pending.peak <= 1 && pending.count==0 && drops == 0 && !overflow && drainMs <= 30000 &&
                    measuredRows.all { it.getString("text").isNotBlank() && it.getDouble("latencyMs") <= 1500 } &&
                    (0 until outputs.length()).all { outputs.getJSONObject(it).getBoolean("valid") }
                row.put("emitted",emitted.get()).put("completed",outputs.length()).put("maximumQueue",pending.peak)
                    .put("drops",drops).put("overflow",overflow).put("finalQueue",0).put("inputSamples",StressTimeline.TOTAL_SAMPLES)
                    .put("drainMs",drainMs).put("durationMs",millisSince(clock)).put("valid",valid)
                if(!valid) result.getJSONArray("reasons").put("stressGate")
            } finally { native.closeAndJoin();oracle.closeAndJoin();mt.closeAndJoin();store.close();fixedStore.close() }
        } catch(failure: Exception) { row.put("notMeasured",failure.toString());result.getJSONArray("reasons").put("stressFailure: $failure") }
        finally { EvaluationSession.writeResult(result) }
    }
}

internal class RealtimeWaveCapture(
    private val scope: CoroutineScope,private val file: File,private val store: SpeechModelStore,
    private val startSample: Int,private val endSample: Int,private val clock: Long,
    private val emissions: ConcurrentLinkedQueue<Long>,private val emitted: AtomicInteger,private val pending: PendingSegments,
) : SpeechCaptureEngine {
    private var playback: Job? = null;private var vad: SherpaSpeechSegmenter? = null
    override suspend fun start(listener: SpeechCaptureListener) {
        val segmenter = EvaluationSession.segmenter(store);vad = segmenter
        playback = scope.launch(Dispatchers.Default) {
            RandomAccessFile(file,"r").use { input ->
                input.seek(44L + startSample*2);var accepted = startSample;val bytes = ByteArray(4096)
                while(accepted < endSample) {
                    ensureActive();val count = minOf(2048,endSample-accepted);input.readFully(bytes,0,count*2)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);val pcm = ShortArray(count) { buffer.short }
                    val deadline = clock + (accepted + count)*1_000_000_000L/16000
                    val wait = deadline - nowNanos();if(wait > 0) delay((wait + 999999)/1000000)
                    segmenter.accept(pcm) { emissions.add(nowNanos());emitted.incrementAndGet();pending.admitted();listener.onSegment(it) };accepted += count
                }
            }
        }
    }
    suspend fun awaitComplete() = requireNotNull(playback).join()
    override fun interruptCurrent() { playback?.cancel() }
    override suspend fun stop(flush: Boolean): SpeechSegment? {
        playback?.cancelAndJoin();playback = null;val segments = ArrayList<SpeechSegment>()
        if(flush) vad?.flush { emissions.add(nowNanos());emitted.incrementAndGet();pending.admitted();segments.add(it) }
        vad?.close();vad = null;require(segments.size <= 1);return segments.singleOrNull()
    }
    override fun close() {playback?.cancel();playback = null;vad?.close();vad = null}
}
