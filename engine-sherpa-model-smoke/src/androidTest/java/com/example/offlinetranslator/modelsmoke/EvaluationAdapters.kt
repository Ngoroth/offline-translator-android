package com.example.offlinetranslator.modelsmoke

import android.os.SystemClock
import com.example.offlinetranslator.testkit.*
import com.example.offlinetranslator.testkit.WaveReader
import com.k2fsa.sherpa.onnx.*
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.sherpa.SpeechModelAdapter
import com.example.offlinetranslator.sherpa.SpeechModelDefinition
import com.example.offlinetranslator.sherpa.SpeechModelRef
import com.example.offlinetranslator.sherpa.SherpaModelFactories
import org.json.JSONObject
import java.io.File

internal fun nowNanos() = SystemClock.elapsedRealtimeNanos()
internal fun millisSince(start: Long) = (nowNanos() - start) / 1_000_000.0

/** Fixed, test-only adapter registry. Native resources have explicit scenario owners. */
class EvaluationVoice(language: String) : AutoCloseable {
    private val model = EvaluationSession.models().let { models ->
        val selected = EvaluationSession.selected()
        if(selected.role == EvaluationModelRole.TTS && selected.language == language) selected
        else models.single { it.role == EvaluationModelRole.TTS && it.language == language && it.id ==
            if(language == "ru") "vits-piper-ru_RU-irina-medium" else "vits-piper-en_US-lessac-medium" }
    }
    private val root = File(EvaluationSession.root, "models/${model.id}")
    private val parameters = model.json.getJSONObject("parameters")
    private fun ref(path: String) = SpeechModelRef(model.id, path)
    private val definition = SpeechModelDefinition(model.id, model.id, SpeechModelRole.TTS, setOf(LanguageId(language)),
        model.json.getInt("sampleRateHz"), SpeechModelAdapter.PiperVits(
            ref(model.files.single { it.path.endsWith(".onnx") }.path), ref("tokens.txt"), ref("espeak-ng-data"),
            parameters.getDouble("noiseScale").toFloat(), parameters.getDouble("noiseScaleW").toFloat(),
            parameters.getDouble("lengthScale").toFloat(), parameters.getInt("numThreads")), model.files.map { ref(it.path) })
    private val native = OfflineTts(config = SherpaModelFactories.ttsConfig(definition) { ref ->
        File(root, ref.path).absolutePath
    })

    fun synthesize(text: String, destination: File, warmup: Boolean = false): JSONObject {
        require(text.length in 1..4096)
        val start = nowNanos()
        val callback=BoundedSynthesisCallback()
        val audio = native.generateWithCallback(text, sid = parameters.getInt("speaker"),
            speed = parameters.getDouble("speed").toFloat(),callback=callback)
        val compute = millisSince(start)
        require(callback.valid) { "Synthesis nonfinite/range/output quota violation" }
        require(audio.sampleRate == 22050)
        val pcm = WaveWriter.fromFloats(audio.samples)
        val signal = SignalMetrics.measure(pcm)
        val seconds = pcm.size / 22050.0
        WaveWriter.write(destination,pcm,22050)
        WaveReader.read(destination,22050)
        return JSONObject().put("durationSeconds",seconds).put("sampleCount",pcm.size).put("sampleRateHz",22050)
            .put("rms",signal.rms).put("zeroFraction",signal.zeroFraction).put("clippingFraction",signal.clippingFraction)
            .put("computeMs",compute).put("rtf",compute / 1000 / seconds).put("sha256",Integrity.sha256(destination))
            .put("valid", seconds in .5..15.0 && EvaluationGates.signal(signal) && (warmup || compute / 1000 / seconds <= 1.0))
    }
    override fun close() = native.release()
}
