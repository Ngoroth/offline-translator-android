package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SherpaModelFactoriesTest {
    private fun catalog() = File("src/main/assets/speech-models.json").inputStream().use(SpeechModelCatalog::parse)
    private fun path(ref: SpeechModelRef) = "/verified/${ref.packageId}/${ref.path}"

    @Test fun alternateWhisperBuildsItsOwnDecodeWithExactLidWeightsAndMatchingVocabulary() {
        val catalog = catalog()
        val selected = catalog.model("asr-en-whisper-tiny")
        val identifier = catalog.model("lid-whisper-tiny")
        val asr = SherpaModelFactories.offlineConfig(selected, ::path)
        val lid = SherpaModelFactories.languageIdConfig(identifier, ::path)
        assertEquals(lid.whisper.encoder, asr.modelConfig.whisper.encoder)
        assertEquals(lid.whisper.decoder, asr.modelConfig.whisper.decoder)
        assertEquals("/verified/whisper-tiny-tokens/tiny-tokens.txt", asr.modelConfig.tokens)
        assertFalse(asr.modelConfig.tokens.contains("tiny.en"))
        assertEquals("en", asr.modelConfig.whisper.language)
        assertEquals("transcribe", asr.modelConfig.whisper.task)
        assertEquals(1_000, asr.modelConfig.whisper.tailPaddings)
        assertEquals(16_000, asr.featConfig.sampleRate)
        assertEquals(80, asr.featConfig.featureDim)
        assertEquals(2, asr.modelConfig.numThreads)
        assertEquals("cpu", asr.modelConfig.provider)
        assertEquals("greedy_search", asr.decodingMethod)
        val baseline = SherpaModelFactories.offlineConfig(catalog.model("asr-en-whisper-tiny.en"), ::path)
        assertNotEquals(baseline.modelConfig.whisper.encoder, asr.modelConfig.whisper.encoder)
        assertTrue(baseline.modelConfig.tokens.contains("tiny.en-tokens"))
    }

    @Test fun nemoAndBothRealOnlineFamiliesKeepTheirActualNativeDecodingFields() {
        val nemo = SherpaModelFactories.offlineConfig(catalog().model("asr-ru-gigaam-v1"), ::path)
        assertEquals("nemo_ctc", nemo.modelConfig.modelType)
        assertEquals(4, nemo.modelConfig.numThreads)
        assertTrue(nemo.modelConfig.nemo.model.endsWith("model.int8.onnx"))
        val model = SpeechModelRef("tone", "model.onnx")
        val tokens = SpeechModelRef("tone", "tokens.txt")
        val tone = SpeechModelDefinition("tone", "Tone", SpeechModelRole.ASR, setOf(LanguageId("ru")), 8_000,
            SpeechModelAdapter.ToneCtcOnline(model, tokens, 2, 80), listOf(model, tokens))
        val toneConfig = SherpaModelFactories.onlineConfig(tone, ::path)
        assertEquals(path(model), toneConfig.modelConfig.toneCtc.model)
        assertEquals(8_000, toneConfig.featConfig.sampleRate)
        assertEquals(2, toneConfig.modelConfig.numThreads)
        assertFalse(toneConfig.enableEndpoint)
        val encoder = SpeechModelRef("zipformer", "encoder.onnx")
        val decoder = SpeechModelRef("zipformer", "decoder.onnx")
        val joiner = SpeechModelRef("zipformer", "joiner.onnx")
        val zipTokens = SpeechModelRef("zipformer", "tokens.txt")
        val zip = SpeechModelDefinition("zipformer", "Zipformer", SpeechModelRole.ASR, setOf(LanguageId("en")), 16_000,
            SpeechModelAdapter.Zipformer2Online(encoder, decoder, joiner, zipTokens, 2, 80), listOf(encoder, decoder, joiner, zipTokens))
        val zipConfig = SherpaModelFactories.onlineConfig(zip, ::path)
        assertEquals(path(encoder), zipConfig.modelConfig.transducer.encoder)
        assertEquals(path(decoder), zipConfig.modelConfig.transducer.decoder)
        assertEquals(path(joiner), zipConfig.modelConfig.transducer.joiner)
        assertEquals("zipformer2", zipConfig.modelConfig.modelType)
        assertEquals(16_000, zipConfig.featConfig.sampleRate)
        assertEquals("cpu", zipConfig.modelConfig.provider)
        assertFalse(zipConfig.enableEndpoint)
    }

    @Test fun selectedSilenceChangesActualVadDurationWithoutChangingOtherNativeParameters() {
        val definition = catalog().model("vad-silero-6.2.2")
        for (silence in listOf(300, 700, 1500)) {
            val config = SherpaModelFactories.vadConfig(definition, ::path, silence)
            assertEquals(silence / 1000f, config.sileroVadModelConfig.minSilenceDuration, 0f)
            assertEquals(.5f, config.sileroVadModelConfig.threshold, 0f)
            assertEquals(.25f, config.sileroVadModelConfig.minSpeechDuration, 0f)
            assertEquals(14f, config.sileroVadModelConfig.maxSpeechDuration, 0f)
            assertEquals(512, config.sileroVadModelConfig.windowSize)
            assertEquals(1, config.numThreads)
            assertEquals(16_000, config.sampleRate)
            assertEquals("cpu", config.provider)
        }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.vadConfig(definition, ::path, 299) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.vadConfig(definition, ::path, 1501) }
    }

    @Test fun selectedPiperVoicesPreserveSynthesisAndOutputBoundsWithNormalizedSpeed() {
        for (id in listOf("tts-ru-piper-irina-medium", "tts-en-piper-lessac-medium")) {
            val definition = catalog().model(id)
            val adapter = definition.adapter as SpeechModelAdapter.PiperVits
            val config = SherpaModelFactories.ttsConfig(definition, ::path)
            assertEquals(path(adapter.model), config.model.vits.model)
            assertEquals(path(adapter.tokens), config.model.vits.tokens)
            assertEquals(path(adapter.dataDir), config.model.vits.dataDir)
            assertEquals(.667f, config.model.vits.noiseScale, 0f)
            assertEquals(.8f, config.model.vits.noiseScaleW, 0f)
            assertEquals(1f, config.model.vits.lengthScale, 0f)
            assertEquals(2, config.model.numThreads)
            assertEquals("cpu", config.model.provider)
            assertEquals(1, config.maxNumSentences)
            assertEquals("", config.ruleFsts)
            assertEquals("", config.ruleFars)
        }
        assertEquals(.75f, SherpaModelFactories.synthesisSpeed(75), 0f)
        assertEquals(1f, SherpaModelFactories.synthesisSpeed(100), 0f)
        assertEquals(1.25f, SherpaModelFactories.synthesisSpeed(125), 0f)
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.synthesisSpeed(74) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.synthesisSpeed(126) }
    }

    @Test fun incompatibleRoleFailsBeforeAnyFileResolutionOrNativeConstruction() {
        val vad = catalog().model("vad-silero-6.2.2")
        var reads = 0
        val paths: (SpeechModelRef) -> String = { reads++; path(it) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.offlineConfig(vad, paths) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.onlineConfig(vad, paths) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.ttsConfig(vad, paths) }
        assertThrows(IllegalArgumentException::class.java) { SherpaModelFactories.languageIdConfig(vad, paths) }
        assertEquals(0, reads)
    }
}
