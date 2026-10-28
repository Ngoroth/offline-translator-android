package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.speechmodels.SpeechModelDefinition
import com.example.offlinetranslator.speechmodels.SpeechModelRef
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Native implementations are compiled and CPU-only; metadata cannot select code or provider paths. */
object SherpaModelFactories {
    fun offlineConfig(definition: SpeechModelDefinition, paths: (SpeechModelRef) -> String): OfflineRecognizerConfig {
        require(definition.role == SpeechModelRole.ASR && definition.sampleRateHz == SpeechSegment.SAMPLE_RATE_HZ)
        val featureDim: Int
        val model = when (val adapter = definition.adapter) {
            is SherpaSpeechModelAdapter.NemoCtcOffline -> {
                featureDim = adapter.featureDim
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = paths(adapter.model)),
                    tokens = paths(adapter.tokens), modelType = "nemo_ctc",
                    numThreads = adapter.numThreads, provider = "cpu",
                )
            }
            is SherpaSpeechModelAdapter.WhisperOffline -> {
                featureDim = adapter.featureDim
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = paths(adapter.encoder), decoder = paths(adapter.decoder),
                        language = adapter.language, task = adapter.task, tailPaddings = adapter.tailPaddings,
                    ),
                    tokens = paths(adapter.tokens), numThreads = adapter.numThreads, provider = "cpu",
                )
            }
            is SherpaSpeechModelAdapter.WhisperMultilingual -> {
                featureDim = adapter.featureDim
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = paths(adapter.encoder), decoder = paths(adapter.decoder),
                        language = "", task = "transcribe", tailPaddings = adapter.tailPaddings,
                        enableTokenTimestamps = false, enableSegmentTimestamps = false,
                    ),
                    tokens = paths(adapter.tokens), numThreads = adapter.numThreads, provider = "cpu",
                )
            }
            else -> error("Model is not an offline ASR")
        }
        return OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = definition.sampleRateHz, featureDim = featureDim),
            modelConfig = model, decodingMethod = "greedy_search",
        )
    }

    fun onlineConfig(definition: SpeechModelDefinition, paths: (SpeechModelRef) -> String): OnlineRecognizerConfig {
        require(definition.role == SpeechModelRole.ASR && definition.sampleRateHz in setOf(8_000, 16_000))
        val featureDim: Int
        val model = when (val adapter = definition.adapter) {
            is SherpaSpeechModelAdapter.ToneCtcOnline -> {
                featureDim = adapter.featureDim
                OnlineModelConfig(
                    toneCtc = OnlineToneCtcModelConfig(model = paths(adapter.model)),
                    tokens = paths(adapter.tokens), numThreads = adapter.numThreads, provider = "cpu",
                )
            }
            is SherpaSpeechModelAdapter.Zipformer2Online -> {
                featureDim = adapter.featureDim
                OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = paths(adapter.encoder), decoder = paths(adapter.decoder), joiner = paths(adapter.joiner),
                    ),
                    tokens = paths(adapter.tokens), modelType = "zipformer2",
                    numThreads = adapter.numThreads, provider = "cpu",
                )
            }
            else -> error("Model is not an online ASR")
        }
        return OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = definition.sampleRateHz, featureDim = featureDim),
            modelConfig = model, enableEndpoint = false,
        )
    }


    fun vadConfig(definition: SpeechModelDefinition, paths: (SpeechModelRef) -> String, vadMinSilenceMs: Int): VadModelConfig {
        require(definition.role == SpeechModelRole.VAD && definition.sampleRateHz == SpeechSegment.SAMPLE_RATE_HZ)
        require(vadMinSilenceMs in 300..1500)
        val adapter = definition.adapter as? SherpaSpeechModelAdapter.Silero ?: error("Model is not a VAD")
        require(adapter.windowSize == SherpaSpeechSegmenter.FRAME_SAMPLES)
        return VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = paths(adapter.model), threshold = adapter.threshold,
                minSilenceDuration = vadMinSilenceMs / 1_000f,
                minSpeechDuration = adapter.minSpeechDuration, maxSpeechDuration = adapter.maxSpeechDuration,
                windowSize = adapter.windowSize,
            ),
            sampleRate = definition.sampleRateHz, numThreads = adapter.numThreads, provider = "cpu",
        )
    }

    fun ttsConfig(definition: SpeechModelDefinition, paths: (SpeechModelRef) -> String): OfflineTtsConfig {
        require(definition.role == SpeechModelRole.TTS && definition.sampleRateHz == SpeechPcmBuffer.SAMPLE_RATE)
        val adapter = definition.adapter as? SherpaSpeechModelAdapter.PiperVits ?: error("Model is not a Piper voice")
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = paths(adapter.model), tokens = paths(adapter.tokens), dataDir = paths(adapter.dataDir),
                    lexicon = "", dictDir = "", noiseScale = adapter.noiseScale,
                    noiseScaleW = adapter.noiseScaleW, lengthScale = adapter.lengthScale,
                ),
                numThreads = adapter.numThreads, provider = "cpu", debug = false,
            ),
            maxNumSentences = 1, ruleFsts = "", ruleFars = "",
        )
    }

    internal suspend fun selectedVadConfig(store: SpeechModelStore, definition: SpeechModelDefinition, silenceMs: Int): VadModelConfig {
        val paths = resolveFiles(store, definition)
        return vadConfig(definition, paths::getValue, silenceMs)
    }


    internal suspend fun createRecognizer(store: SpeechModelStore, definition: SpeechModelDefinition): NativeSpeechRecognizer {
        val paths = resolveFiles(store, definition)
        currentCoroutineContext().ensureActive()
        return when (definition.adapter) {
            is SherpaSpeechModelAdapter.NemoCtcOffline, is SherpaSpeechModelAdapter.WhisperOffline ->
                OfflineAsr(OfflineRecognizer(config = offlineConfig(definition, paths::getValue)))
            is SherpaSpeechModelAdapter.ToneCtcOnline, is SherpaSpeechModelAdapter.Zipformer2Online ->
                OnlineAsr(OnlineRecognizer(config = onlineConfig(definition, paths::getValue)), definition.sampleRateHz)
            else -> error("Model is not an ASR")
        }
    }

    internal suspend fun createWhisperRecognizer(
        store: SpeechModelStore,
        definition: SpeechModelDefinition,
        pair: LanguagePair,
    ): NativeWhisperRecognizer {
        require(definition.adapter is SherpaSpeechModelAdapter.WhisperMultilingual) {
            "Model is not a multilingual Whisper ASR"
        }
        require(pair.source in definition.languages && pair.target in definition.languages)
        val paths = resolveFiles(store, definition)
        currentCoroutineContext().ensureActive()
        return PairedWhisperAsr(OfflineRecognizer(config = offlineConfig(definition, paths::getValue)), pair)
    }

    internal suspend fun createVoice(cacheDir: File, store: SpeechModelStore, definition: SpeechModelDefinition, speedPercent: Int): ConversationVoice {
        require(speedPercent in 75..125)
        val adapter = definition.adapter as? SherpaSpeechModelAdapter.PiperVits ?: error("Model is not a Piper voice")
        val paths = resolveFiles(store, definition)
        val inventoryRef = definition.requiredRefs.firstOrNull {
            it.packageId == adapter.dataDir.packageId && it.path.startsWith(adapter.dataDir.path + "/")
        } ?: error("Voice phonemizer inventory is missing")
        val inventoryFile = store.modelFile(inventoryRef.packageId, inventoryRef.path)
        var root = inventoryFile
        repeat(inventoryRef.path.count { it == '/' } + 1) { root = requireNotNull(root.parentFile) }
        val dataDir = File(root, adapter.dataDir.path)
        require(dataDir.isDirectory && dataDir.canonicalFile == dataDir.absoluteFile) { "Unsafe voice phonemizer directory" }
        val config = ttsConfig(definition) { ref ->
            if (ref == adapter.dataDir) dataDir.absolutePath else paths.getValue(ref)
        }
        // Private trusted options disable the expanding VITS high-water CPU memory arena.
        // Native construction consumes this synchronously; metadata never supplies this path.
        val options = File.createTempFile("piper-options-", ".conf", cacheDir)
        return try {
            options.writeText("EnableCpuMemArena=0\n")
            config.model.provider = "cpu:${options.absolutePath}"
            currentCoroutineContext().ensureActive()
            PiperVoice(OfflineTts(config = config), synthesisSpeed(speedPercent))
        } finally {
            options.delete()
        }
    }

    internal fun synthesisSpeed(speedPercent: Int): Float {
        require(speedPercent in 75..125)
        return speedPercent / 100f
    }

    private suspend fun resolveFiles(store: SpeechModelStore, definition: SpeechModelDefinition): Map<SpeechModelRef, String> {
        val refs = when (val adapter = definition.adapter) {
            is SherpaSpeechModelAdapter.PiperVits -> listOf(adapter.model, adapter.tokens)
            else -> definition.requiredRefs
        }
        val paths = HashMap<SpeechModelRef, String>(refs.size)
        for (ref in refs) paths[ref] = store.modelFile(ref.packageId, ref.path).absolutePath
        return paths
    }
}

private class OfflineAsr(private val native: OfflineRecognizer) : NativeSpeechRecognizer {
    override suspend fun recognize(pcm: ShortArray, ensureActive: suspend () -> Unit, samples: FloatArray?): String {
        ensureActive()
        val waveform = samples ?: FloatArray(pcm.size) { pcm[it] / 32_768f }
        val stream = native.createStream()
        return try {
            stream.acceptWaveform(waveform, sampleRate = SpeechSegment.SAMPLE_RATE_HZ)
            ensureActive()
            native.decode(stream)
            ensureActive()
            val rawText = native.getResult(stream).text
            deduplicateConsecutiveSentences(rawText)
        } finally { stream.release() }
    }
    override fun close() = native.release()
}

/** A fresh stream must prove paired native completion; a generic option roundtrip cannot do so. */
private class PairedWhisperAsr(
    private val native: OfflineRecognizer,
    pair: LanguagePair,
) : NativeWhisperRecognizer {
    private val selectedPair = "${pair.source.value},${pair.target.value}"

    override suspend fun recognize(samples: FloatArray, ensureActive: suspend () -> Unit): NativeWhisperResult {
        ensureActive()
        val stream = native.createStream()
        return try {
            stream.setOption("whisper-language-pair", selectedPair)
            stream.acceptWaveform(samples, sampleRate = SpeechSegment.SAMPLE_RATE_HZ)
            ensureActive()
            native.decode(stream)
            ensureActive()
            val result = native.getResult(stream)
            NativeWhisperResult(result.text, result.lang, stream.getOption("whisper-language-pair-status"))
        } finally { stream.release() }
    }

    override fun close() = native.release()
}


private class OnlineAsr(private val native: OnlineRecognizer, private val inputRate: Int) : NativeSpeechRecognizer {
    private val chunk = FloatArray(if (inputRate == 8_000) CHUNK_SAMPLES / 2 else CHUNK_SAMPLES)
    private val silence = FloatArray(inputRate * 8 / 10)

    override suspend fun recognize(pcm: ShortArray, ensureActive: suspend () -> Unit, samples: FloatArray?): String {
        ensureActive()
        val stream = native.createStream()
        return try {
            val decimator = if (inputRate == 8_000) PcmResampler() else null
            for (start in pcm.indices step CHUNK_SAMPLES) {
                ensureActive()
                val count = minOf(CHUNK_SAMPLES, pcm.size - start)
                val size = if (decimator != null) (count + 1) / 2 else count
                val samples = if (size == chunk.size) chunk else FloatArray(size)
                if (decimator != null) {
                    check(decimator.accept(pcm, start, count, samples) == samples.size)
                } else {
                    for (index in samples.indices) samples[index] = pcm[start + index] / 32_768f
                }
                stream.acceptWaveform(samples, sampleRate = inputRate)
                while (native.isReady(stream)) { ensureActive(); native.decode(stream) }
            }
            ensureActive()
            stream.acceptWaveform(silence, sampleRate = inputRate)
            while (native.isReady(stream)) { ensureActive(); native.decode(stream) }
            ensureActive()
            native.getResult(stream).text
        } finally { stream.release() }
    }

    override fun close() = native.release()
    companion object { private const val CHUNK_SAMPLES = 5_120 }
}

private fun deduplicateConsecutiveSentences(text: String): String {
    val sentences = text.split(Regex("(?<=[.!?])\\s+"))
    if (sentences.size <= 1) return text
    val result = mutableListOf<String>()
    for (s in sentences) {
        val normalized = s.trim().trimEnd('.', '!', '?', ',', ';').lowercase(java.util.Locale.ROOT)
        val prevNormalized = result.lastOrNull()?.trim()?.trimEnd('.', '!', '?', ',', ';')?.lowercase(java.util.Locale.ROOT)
        if (normalized != prevNormalized) {
            result += s
        }
    }
    return result.joinToString(" ")
}

private class PiperVoice(private val native: OfflineTts, private val speed: Float) : ConversationVoice {
    override val sampleRate: Int get() = native.sampleRate()
    override fun generate(text: String, callback: ((FloatArray) -> Int)?): ConversationVoiceResult {
        val audio = if (callback != null) {
            native.generateWithCallback(text, sid = 0, speed = speed, callback = callback)
        } else {
            native.generate(text, sid = 0, speed = speed)
        }
        return ConversationVoiceResult(audio.sampleRate, audio.samples.size)
    }
    override fun close() = native.release()
}
