package com.example.offlinetranslator.modelsmoke

import android.net.ConnectivityManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.sherpa.SherpaModelFactories
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.testkit.WaveReader
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real patched SDK with isolated hash-verified inputs; never downloads or accesses user audio. */
@RunWith(AndroidJUnit4::class)
class WhisperLanguagePairNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun selectedPairProducesItsOwnTranscriptAndRejectsInvalidConfiguration() = runBlocking(Dispatchers.Default) {
        assertOffline()
        val store = selectedStore()
        try {
            val definition = store.catalog.model(WhisperConversationRecognitionEngine.MODEL_ID)
            val paths = definition.requiredRefs.associateWith { ref ->
                store.modelFile(ref.packageId, ref.path).absolutePath
            }
            val config = SherpaModelFactories.offlineConfig(definition, paths::getValue)
            val samples = englishFixture()
            val native = OfflineRecognizer(config = config)
            try {
                // A third candidate is not a valid request, even if all three model codes exist.
                for (pair in listOf("en,ru,nl", "en,en", "en,zz", "")) {
                    assertRejected(native, pair, samples)
                }
                val stream = native.createStream()
                try {
                    stream.setOption(PAIR, "nl,en")
                    stream.acceptWaveform(samples, sampleRate = 16_000)
                    native.decode(stream)
                    val result = native.getResult(stream)
                    assertEquals("ok", stream.getOption(STATUS))
                    assertEquals("en", result.lang)
                    val text = result.text.lowercase(java.util.Locale.ROOT)
                    for (meaning in listOf("nightfall", "yellow", "lamps", "quarter")) {
                        assertTrue("Lost fixture meaning '$meaning': ${result.text}", meaning in text)
                    }
                } finally { stream.release() }
            } finally { native.release() }

            // A forced source must not silently override the requested automatic pair.
            val forcedConfig: OfflineRecognizerConfig = config.copy(
                modelConfig = config.modelConfig.copy(
                    whisper = config.modelConfig.whisper.copy(language = "en"),
                ),
            )
            val forced = OfflineRecognizer(config = forcedConfig)
            try { assertRejected(forced, "en,ru", samples) }
            finally { forced.release() }
        } finally { store.close() }
        assertOffline()
    }

    private fun assertRejected(native: OfflineRecognizer, pair: String, samples: FloatArray) {
        val stream = native.createStream()
        try {
            stream.setOption(PAIR, pair)
            stream.acceptWaveform(samples, sampleRate = 16_000)
            native.decode(stream)
            val result = native.getResult(stream)
            assertEquals("Invalid selected pair must fail closed: '$pair'", "invalid-config", stream.getOption(STATUS))
            assertTrue("An invalid pair must not fabricate text", result.text.isBlank())
            assertTrue("An invalid pair must not assign a direction", result.lang.isBlank())
        } finally { stream.release() }
    }

    private suspend fun selectedStore(): SpeechModelStore {
        val input = requireNotNull(InstrumentationRegistry.getArguments().getString("whisperModelInput")) {
            "Stage the three pinned packages in this isolated application's owned input directory and pass whisperModelInput"
        }
        val document = context.assets.open("speech-models.json").bufferedReader().use { JSONObject(it.readText()) }
        val definitions = document.getJSONArray("models")
        val selected = (0 until definitions.length()).map(definitions::getJSONObject)
            .single { it.getString("id") == WhisperConversationRecognitionEngine.MODEL_ID }
        val catalog = SherpaSpeechModelCatalog.fromAssets(context)
        val required = catalog.requiredPackages(setOf(WhisperConversationRecognitionEngine.MODEL_ID))
        val packages = document.getJSONArray("packages")
        document.put("packages", JSONArray().apply {
            for (index in 0 until packages.length()) {
                val entry = packages.getJSONObject(index)
                if (entry.getString("id") in required) put(entry)
            }
        })
        document.put("models", JSONArray().put(selected))
        val selectedCatalog = SherpaSpeechModelCatalog.parse(document.toString().byteInputStream())
        return SpeechModelStore.fromVerifiedEvaluationFiles(context, selectedCatalog, File(input))
    }

    private fun englishFixture(): FloatArray {
        val fixture = File.createTempFile("whisper-pair-fixture-", ".wav", context.cacheDir)
        return try {
            instrumentation.context.assets.open("en-official-0.wav").use { input ->
                fixture.outputStream().use { output -> input.copyTo(output) }
            }
            val pcm = WaveReader.read(fixture, 16_000).samples
            FloatArray(pcm.size) { pcm[it] / 32_768f }
        } finally { fixture.delete() }
    }

    private fun assertOffline() {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull(connectivity.activeNetwork)
        assertTrue(connectivity.allNetworks.isEmpty())
    }

    private companion object {
        const val PAIR = "whisper-language-pair"
        const val STATUS = "whisper-language-pair-status"
    }
}
