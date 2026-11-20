package io.github.ngoroth.offlinetranslator.modelsmoke

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.ConversationSessionConfig
import io.github.ngoroth.offlinetranslator.core.LanguageId
import io.github.ngoroth.offlinetranslator.core.SpeechOutputBackend
import io.github.ngoroth.offlinetranslator.core.SpeechRecognitionBackend
import io.github.ngoroth.offlinetranslator.core.SpeechSegment
import io.github.ngoroth.offlinetranslator.sherpa.SherpaModelFactories
import io.github.ngoroth.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import io.github.ngoroth.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import io.github.ngoroth.offlinetranslator.speechmodels.SpeechModelStore
import io.github.ngoroth.offlinetranslator.testkit.WaveReader
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real upstream SDK with isolated hash-verified inputs; never downloads or accesses user audio. */
@RunWith(AndroidJUnit4::class)
class WhisperLanguagePairNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun selectedPairProducesItsOwnTranscriptAndRejectsInvalidConfiguration() = runBlocking(Dispatchers.Default) {
        assertOffline(context)
        val store = verifiedModelStore(context, "whisperModelInput", setOf(WhisperConversationRecognitionEngine.MODEL_ID))
        try {
            val definition = store.catalog.model(WhisperConversationRecognitionEngine.MODEL_ID)
            val paths = definition.requiredRefs.associateWith { ref ->
                store.modelFile(ref.packageId, ref.path).absolutePath
            }
            val config = SherpaModelFactories.offlineConfig(definition, paths::getValue)
            val segment = SpeechSegment.fromPcm(englishFixture())
            val samples = FloatArray(segment.sampleCount).also(segment::copyNormalizedTo)
            val native = OfflineRecognizer(config = config)
            val excluded = try {
                val unrestricted = decode(native, samples)
                assertEquals("en", unrestricted.lang)
                val selected = decode(native, samples, "nl,en")
                assertEquals("en", selected.lang)
                val text = selected.text.lowercase(java.util.Locale.ROOT)
                for (meaning in listOf("nightfall", "yellow", "lamps", "quarter")) {
                    assertTrue("Lost fixture meaning '$meaning': ${selected.text}", meaning in text)
                }

                // The very same English fixture must no longer be attributed to English.
                val restricted = decode(native, samples, "nl,ru")
                assertTrue("Ignored or failed language candidates: '${restricted.lang}'", restricted.lang in setOf("nl", "ru"))
                // Upstream accepts singleton and longer lists; the app still supplies exactly two.
                assertEquals("ru", decode(native, samples, "ru").lang)
                assertEquals("en", decode(native, samples, " nl, ru , en ").lang)
                for (invalid in listOf("", "en,en", "en,zz", "en,", "EN,nl")) {
                    assertEmptyResult(decode(native, samples, invalid), "invalid candidates '$invalid'")
                }
                native.setConfig(config.copy(modelConfig = config.modelConfig.copy(
                    whisper = config.modelConfig.whisper.copy(language = "en"),
                )))
                assertEmptyResult(decode(native, samples, "nl,en"), "automatic candidates with forced English")
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nWHISPER_SMOKE unrestricted=${unrestricted.lang} " +
                        "nl,en=${selected.lang} nl,ru=${restricted.lang}; invalid and forced options rejected\n")
                })
                restricted
            } finally { native.release() }

            val configWithoutEnglish = ConversationSessionConfig(
                languages = listOf(LanguageId("nl"), LanguageId("ru")), engineId = "speech-only",
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER, vadModelId = "vad-silero-6.2.2",
                vadMinSilenceMs = 700, ttsSpeedPercent = 100, speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
            )
            val engine = WhisperConversationRecognitionEngine(store, configWithoutEnglish)
            try {
                engine.prepare()
                if (excluded.text.isBlank()) {
                    // A blank restricted transcript is permitted by native, but not a conversation turn.
                    val failure = runCatching { engine.recognize(segment) }.exceptionOrNull()
                    assertEquals("Speech recognition returned no text", failure?.message)
                } else {
                    val turn = engine.recognize(segment)
                    assertEquals(configWithoutEnglish.pairFor(LanguageId(excluded.lang)), turn.direction)
                    assertEquals(excluded.text, turn.text)
                }
            } finally { engine.closeAndJoin() }
        } finally { store.close() }
        assertOffline(context)
    }

    private fun decode(native: OfflineRecognizer, samples: FloatArray, candidates: String? = null): OfflineRecognizerResult {
        val stream = native.createStream()
        return try {
            if (candidates != null) stream.setOption("language_candidates", candidates)
            stream.acceptWaveform(samples, sampleRate = SpeechSegment.SAMPLE_RATE_HZ)
            native.decode(stream)
            native.getResult(stream)
        } finally { stream.release() }
    }

    private fun assertEmptyResult(result: OfflineRecognizerResult, case: String) {
        assertTrue("$case must produce no text", result.text.isEmpty())
        assertTrue("$case must produce no language", result.lang.isEmpty())
        assertTrue("$case must produce no tokens", result.tokens.isEmpty())
    }

    private fun englishFixture(): ShortArray {
        val fixture = File.createTempFile("whisper-pair-fixture-", ".wav", context.cacheDir)
        return try {
            instrumentation.context.assets.open("en-official-0.wav").use { input ->
                fixture.outputStream().use { output -> input.copyTo(output) }
            }
            WaveReader.read(fixture, SpeechSegment.SAMPLE_RATE_HZ).samples
        } finally { fixture.delete() }
    }
}

internal suspend fun verifiedModelStore(context: Context, inputArgument: String, modelIds: Set<String>): SpeechModelStore {
    val input = requireNotNull(InstrumentationRegistry.getArguments().getString(inputArgument)) {
        "Stage the pinned packages in this isolated application's owned input directory and pass $inputArgument"
    }
    val document = context.assets.open("speech-models.json").bufferedReader().use { JSONObject(it.readText()) }
    val required = SherpaSpeechModelCatalog.fromAssets(context).requiredPackages(modelIds)
    fun select(rows: JSONArray, ids: Set<String>): JSONArray = JSONArray().apply {
        for (index in 0 until rows.length()) {
            val entry = rows.getJSONObject(index)
            if (entry.getString("id") in ids) put(entry)
        }
    }
    document.put("packages", select(document.getJSONArray("packages"), required))
    document.put("models", select(document.getJSONArray("models"), modelIds))
    val catalog = SherpaSpeechModelCatalog.parse(document.toString().byteInputStream())
    return SpeechModelStore.fromVerifiedEvaluationFiles(context, catalog, File(input))
}

internal fun assertOffline(context: Context) {
    assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
    assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    assertNull(connectivity.activeNetwork)
    assertTrue(connectivity.allNetworks.isEmpty())
}
