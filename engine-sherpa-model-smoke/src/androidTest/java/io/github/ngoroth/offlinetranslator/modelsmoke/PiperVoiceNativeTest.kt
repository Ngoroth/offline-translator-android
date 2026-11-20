package io.github.ngoroth.offlinetranslator.modelsmoke

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.sherpa.SherpaModelFactories
import io.github.ngoroth.offlinetranslator.testkit.BoundedSynthesisCallback
import io.github.ngoroth.offlinetranslator.testkit.EvaluationModelRole
import io.github.ngoroth.offlinetranslator.testkit.Integrity
import io.github.ngoroth.offlinetranslator.testkit.WaveReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PiperVoiceNativeTest {
    @Test fun selectedRussianAndEnglishVoicesStreamOfflinePcm() = runBlocking(Dispatchers.Default) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertOffline(context)
        val cases = mapOf(
            "tts-ru-piper-irina-medium" to "Какое прекрасное утро! Пойдём гулять на рынок.",
            "tts-en-piper-lessac-medium" to "What a beautiful morning! Let's walk to the market.",
        )
        val store = verifiedModelStore(context, "piperModelInput", cases.keys)
        try {
            for ((modelId, text) in cases) {
                SherpaModelFactories.createPiperVoice(context, store, store.catalog.model(modelId), 100).use { voice ->
                    val bounded = BoundedSynthesisCallback()
                    var chunks = 0
                    val callback = object : (FloatArray) -> Int {
                        override fun invoke(samples: FloatArray): Int {
                            chunks++
                            return bounded(samples)
                        }
                    }
                    val pcm = voice.synthesize(text, callback)
                    assertEquals(22_050, voice.sampleRate)
                    assertTrue("$modelId did not stream both sentences", chunks >= 2)
                    assertTrue("$modelId streamed invalid PCM", bounded.valid)
                    assertEquals("$modelId callback dropped PCM", pcm.size, bounded.sampleCount)
                    assertTrue("$modelId produced no audible PCM", pcm.any { it != 0f })
                    record("$modelId chunks=$chunks callbackSamples=${bounded.sampleCount} sampleRateHz=${voice.sampleRate}")
                }
            }
        } finally {
            store.close()
        }
        assertOffline(context)
    }

    @Test fun nativeInitializationFailureRemainsTerminalInProcess() = runBlocking(Dispatchers.Default) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPiperInitializationFailure") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertOffline(context)
        val installed = File(context.noBackupFilesDir, "piper-phonemize-1.5.9")
        assertTrue("Stage the incomplete published tree before starting instrumentation", installed.isDirectory)
        assertTrue("The fault fixture must omit phoneme data", !File(installed, "espeak-ng-data/phontab").exists())
        val modelId = "tts-ru-piper-irina-medium"
        verifiedModelStore(context, "piperModelInput", setOf(modelId)).use { store ->
            repeat(2) { attempt ->
                val failure = runCatching {
                    SherpaModelFactories.createPiperVoice(context, store, store.catalog.model(modelId), 100).close()
                }.exceptionOrNull()
                assertTrue("Attempt ${attempt + 1} did not fail in the production factory", failure is IllegalStateException)
                assertEquals("Piper phonemizer initialization failed", failure?.message)
                record("pid=${Process.myPid()} attempt=${attempt + 1}: ${failure?.message}; no voice opened")
            }
        }
        assertOffline(context)
    }

    @Test fun evaluationVoiceSynthesizesBothPinnedVoicesOffline() = runBlocking(Dispatchers.Default) {
        assumeTrue(EvaluationSession.active && EvaluationSession.args.getString("phase") == "smoke")
        assertOffline(EvaluationSession.context)
        val russian = "vits-piper-ru_RU-irina-medium"
        val english = "vits-piper-en_US-lessac-medium"
        assertEquals(russian, EvaluationSession.selected().id)
        val manifest = EvaluationSession.models()
        assertEquals(2, manifest.size)
        val models = manifest.associateBy { it.id }
        assertEquals(setOf(russian, english), models.keys)
        for ((id, language, text) in listOf(
            Triple(russian, "ru", "Какое прекрасное утро! Пойдём гулять на рынок."),
            Triple(english, "en", "What a beautiful morning! Let's walk to the market."),
        )) {
            val model = models.getValue(id)
            assertEquals(EvaluationModelRole.TTS, model.role)
            assertEquals(language, model.language)
            val modelRoot = File(EvaluationSession.root, "models/$id")
            model.files.forEach { Integrity.verify(modelRoot, it.path, it.bytes, it.sha256) }

            val destination = File(EvaluationSession.root, "$id.wav")
            val report = EvaluationVoice(language).use { it.synthesize(text, destination, warmup = true) }
            val decoded = WaveReader.read(destination, 22_050)
            val callbackSamples = report.getInt("callbackSampleCount")
            assertTrue("$id did not stream valid PCM", report.getBoolean("valid") && callbackSamples > 0)
            assertEquals("$id callback/report count mismatch", callbackSamples, report.getInt("sampleCount"))
            assertEquals("$id callback/WAV count mismatch", callbackSamples, decoded.samples.size)
            assertTrue("$id WAV is silent", decoded.samples.any { it != 0.toShort() })
            File(EvaluationSession.root, "$id.json").writeText(report.toString())
            record("$id callbackSamples=$callbackSamples reportSamples=${report.getInt("sampleCount")} " +
                "wavSamples=${decoded.samples.size} sampleRateHz=${decoded.sampleRateHz}")
        }
        assertOffline(EvaluationSession.context)
    }

    private fun record(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nPIPER_SMOKE $message\n")
        })
    }
}
