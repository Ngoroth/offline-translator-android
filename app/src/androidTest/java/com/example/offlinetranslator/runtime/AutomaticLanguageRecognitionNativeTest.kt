package com.example.offlinetranslator.runtime

import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Fresh-process offline joint Whisper fixture smoke, not general recognition qualification. */
class AutomaticLanguageRecognitionNativeTest {
    @Test fun preparedOfflineRecommendedRecognitionAfterRestart() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            commitNativeSettings(runtime, original.copy(
                languagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
            ))
            val config = runtime.settings.current().conversationConfig()
            assertEquals(setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), config.languages.toSet())
            val resources = SelectedSpeechResources.forConversation(runtime.speechModels, config,
                setOf(WhisperConversationRecognitionEngine.MODEL_ID))
            try {
                assertTrue("Selected conversation resources must already be ready", resources.isConversationReady())
                val engine = WhisperConversationRecognitionEngine(runtime.speechModels, config)
                try {
                    withTimeout(120_000) { engine.prepare() }
                    for (fixture in listOf(
                        Fixture(
                            "speech-ru-fixture.wav",
                            101_760,
                            LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                            listOf("сейчас к тебе приедет бригада", "давай давай я жду"),
                        ),
                        Fixture(
                            "speech-en-fixture.wav",
                            106_000,
                            LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
                            listOf(
                                "after early nightfall",
                                "the yellow lamps",
                                "light up here and there",
                                "the squalid quarter of the brothels",
                            ),
                        ),
                    )) {
                        val file = File.createTempFile("automatic-recognition-", ".wav", context.cacheDir)
                        try {
                            instrumentation.context.assets.open(fixture.asset).use { input ->
                                file.outputStream().use { output -> input.copyTo(output) }
                            }
                            val pcm = ConversationProofFixtures.readPcm(file)
                            assertEquals("Sample count for ${fixture.asset}", fixture.sampleCount, pcm.size)
                            val utterance = withTimeout(120_000) {
                                engine.recognize(SpeechSegment.fromPcm(pcm))
                            }
                            assertTrue("Whisper must establish a paired direction", utterance is com.example.offlinetranslator.core.RecognizedUtterance)
                            val recognized = utterance as com.example.offlinetranslator.core.RecognizedUtterance
                            val normalized = utterance.text.lowercase(Locale.ROOT)
                                .replace('ё', 'е')
                                .replace(Regex("\\p{P}+"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            var nextOffset = 0
                            val padded = " $normalized "
                            val oraclePassed = fixture.phrases.all { phrase ->
                                val index = padded.indexOf(" $phrase ", nextOffset)
                                if (index < 0) false else {
                                    nextOffset = index + phrase.length + 1
                                    true
                                }
                            }
                            val direction = recognized.direction
                            Log.i(
                                TAG,
                                "fixture=${fixture.asset} model=${WhisperConversationRecognitionEngine.MODEL_ID} " +
                                    "direction=${direction.source.value}->${direction.target.value} " +
                                    "oracle=$oraclePassed complete=true",
                            )
                            assertEquals("Direction for ${fixture.asset}", fixture.direction, direction)
                            assertTrue("Ordered phrase oracle for ${fixture.asset}", oraclePassed)
                        } finally {
                            file.delete()
                        }
                    }
                } finally {
                    engine.closeAndJoin()
                }
            } finally {
                resources.close()
            }
        } finally {
            try { commitNativeSettings(runtime, original) } finally { runtime.closeAndJoin() }
        }
    }

    private data class Fixture(
        val asset: String,
        val sampleCount: Int,
        val direction: LanguagePair,
        val phrases: List<String>,
    )

    private companion object {
        const val TAG = "AutomaticRecognitionProof"
    }
}
