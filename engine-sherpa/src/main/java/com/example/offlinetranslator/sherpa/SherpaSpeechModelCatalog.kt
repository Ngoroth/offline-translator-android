package com.example.offlinetranslator.sherpa

import android.content.Context
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.speechmodels.SpeechModelAdapterReader
import com.example.offlinetranslator.speechmodels.SpeechModelCatalog
import java.io.InputStream

/** Sherpa assets and executable family contracts stay with the pinned Sherpa runtime. */
object SherpaSpeechModelCatalog {
    fun fromAssets(context: Context): SpeechModelCatalog =
        context.assets.open("speech-models.json").use(::parse)

    fun parse(input: InputStream): SpeechModelCatalog =
        SpeechModelCatalog.parse(input, "sherpa-onnx-1.13.8", ::decodeAdapter)

    private fun decodeAdapter(reader: SpeechModelAdapterReader): SherpaSpeechModelAdapter = with(reader) {
        fun threads() = intRange("numThreads", 1..16)
        fun features() = intRange("featureDim", 80..80)
        when (text("family")) {
            "silero" -> {
                fields("family", "model", "numThreads", "threshold", "minSpeechDuration", "maxSpeechDuration", "windowSize")
                require(role == SpeechModelRole.VAD && languages.isEmpty() && sampleRateHz == 16_000) { "Invalid Silero role/language/rate" }
                val min = positiveFloat("minSpeechDuration", 14f)
                val max = positiveFloat("maxSpeechDuration", 14f)
                require(min <= max) { "Invalid speech durations" }
                SherpaSpeechModelAdapter.Silero(ref("model"), threads(), positiveFloat("threshold", 1f), min, max,
                    intRange("windowSize", 512..512))
            }
            "nemoCtcOffline" -> {
                fields("family", "model", "tokens", "numThreads", "featureDim")
                requireAsr(role, languages, sampleRateHz)
                require(sampleRateHz == 16_000) { "Unsupported offline Nemo sample rate" }
                SherpaSpeechModelAdapter.NemoCtcOffline(ref("model"), ref("tokens"), threads(), features())
            }
            "whisperOffline" -> {
                fields("family", "encoder", "decoder", "tokens", "language", "task", "tailPaddings", "numThreads", "featureDim")
                val language = text("language")
                require(role == SpeechModelRole.ASR && languages.size == 1 && languages.single().value == language && sampleRateHz == 16_000) {
                    "Invalid Whisper role/language/rate"
                }
                require(text("task") == "transcribe") { "Unsupported Whisper task" }
                SherpaSpeechModelAdapter.WhisperOffline(ref("encoder"), ref("decoder"), ref("tokens"), language,
                    text("task"), intRange("tailPaddings", 0..10_000), threads(), features())
            }
            "whisperMultilingual" -> {
                fields("family", "encoder", "decoder", "tokens", "tailPaddings", "numThreads", "featureDim")
                require(role == SpeechModelRole.ASR && languages.size >= 2 && sampleRateHz == 16_000) {
                    "Invalid multilingual Whisper role/language/rate"
                }
                SherpaSpeechModelAdapter.WhisperMultilingual(
                    ref("encoder"), ref("decoder"), ref("tokens"),
                    intRange("tailPaddings", 300..300), intRange("numThreads", 4..4), features(),
                )
            }
            "piperVits" -> {
                fields("family", "model", "tokens", "dataDir", "noiseScale", "noiseScaleW", "lengthScale", "numThreads")
                require(role == SpeechModelRole.TTS && languages.size == 1 && sampleRateHz == 22_050) { "Invalid Piper role/language/rate" }
                SherpaSpeechModelAdapter.PiperVits(ref("model"), ref("tokens"), ref("dataDir", directory = true),
                    positiveFloat("noiseScale", 10f), positiveFloat("noiseScaleW", 10f), positiveFloat("lengthScale", 10f), threads())
            }
            "toneCtcOnline" -> {
                fields("family", "model", "tokens", "numThreads", "featureDim")
                requireAsr(role, languages, sampleRateHz)
                SherpaSpeechModelAdapter.ToneCtcOnline(ref("model"), ref("tokens"), threads(), features())
            }
            "zipformer2Online" -> {
                fields("family", "encoder", "decoder", "joiner", "tokens", "numThreads", "featureDim")
                requireAsr(role, languages, sampleRateHz)
                SherpaSpeechModelAdapter.Zipformer2Online(ref("encoder"), ref("decoder"), ref("joiner"), ref("tokens"), threads(), features())
            }
            else -> throw IllegalArgumentException("Unsupported speech adapter family")
        }
    }

    private fun requireAsr(role: SpeechModelRole, languages: Set<LanguageId>, rate: Int) {
        require(role == SpeechModelRole.ASR && languages.isNotEmpty() && rate in setOf(8_000, 16_000)) {
            "Invalid ASR role/language/rate"
        }
    }
}
