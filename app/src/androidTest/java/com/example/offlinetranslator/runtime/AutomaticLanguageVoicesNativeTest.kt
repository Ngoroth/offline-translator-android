package com.example.offlinetranslator.runtime

import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.settings.AppSettingsRepository
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.sherpa.SherpaConversationSpeechOutput
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Explicit preparation and fresh-process offline production audio-path checks, not acoustic qualification. */
class AutomaticLanguageVoicesNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val temp = TemporaryFolder()


    @Test fun legacySelectionsAreDiscardedWithoutRewritingUntilChangedSave() = runBlocking<Unit> {
        val fixtures = listOf(
            """{"schemaVersion":1,"textPair":{"source":"en","target":"de"},"conversationLanguages":["en","ru"],"textEngineId":"mlkit","conversationEngineId":"mlkit","vadModelId":"vad-silero-6.2.2","languageIdModelId":"lid-whisper-tiny","asrModelIds":{"ru":"removed-ru-asr","en":"asr-en-whisper-tiny"},"ttsModelIds":{"ru":"removed-ru-voice","en":"removed-en-voice"},"vadMinSilenceMs":900,"ttsSpeedPercent":110}""",
            """{"schemaVersion":2,"textPair":{"source":"en","target":"de"},"conversationLanguages":["en","ru"],"textEngineId":"mlkit","conversationEngineId":"mlkit","vadModelId":"vad-silero-6.2.2","languageIdModelId":"lid-whisper-tiny","asrModelIds":{"ru":"removed-ru-asr","en":"asr-en-whisper-tiny"},"vadMinSilenceMs":900,"ttsSpeedPercent":110}""",
        )
        val expected = AppSettings(
            languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
            speechOutputBackend = SpeechOutputBackend.PIPER,
            textEngineId = "mlkit",
            conversationEngineId = "mlkit",
            vadModelId = "vad-silero-6.2.2",
            languageIdModelId = "lid-whisper-tiny",
            vadMinSilenceMs = 900, ttsSpeedPercent = 110,
        )
        for ((index, legacy) in fixtures.withIndex()) {
            val file = File(temp.root, "legacy-${index + 1}.json")
            val originalBytes = legacy.toByteArray(Charsets.UTF_8)
            file.writeBytes(originalBytes)
            repeat(2) {
                val repo = AppSettingsRepository(file)
                try {
                    val loaded = repo.current()
                    assertEquals(expected, loaded)
                    assertRecommendedSelections(loaded)
                    assertFalse(repo.corruptionNotice.value)
                    assertArrayEquals(originalBytes, file.readBytes())
                } finally { repo.closeAndJoin() }
            }
            val changed = expected.copy(vadMinSilenceMs = 1000)
            val writer = AppSettingsRepository(file)
            try {
                assertEquals(expected, writer.current())
                writer.save(changed)
                assertFalse(writer.corruptionNotice.value)
            } finally { writer.closeAndJoin() }
            val document = org.json.JSONObject(file.readText())
            assertEquals("system-offline", document.getString("speechRecognitionBackend"))
            assertFalse(document.has("asrModelIds"))
            assertFalse(document.has("ttsModelIds"))
            assertFalse(document.has("textPair"))
            assertFalse(document.has("conversationLanguages"))
            val reopened = AppSettingsRepository(file)
            try {
                val loaded = reopened.current()
                assertEquals(changed, loaded)
                assertRecommendedSelections(loaded)
                assertFalse(reopened.corruptionNotice.value)
            } finally { reopened.closeAndJoin() }
        }
    }

    private fun assertRecommendedSelections(settings: AppSettings) {
        val config = settings.conversationConfig()
        assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, settings.speechRecognitionBackend)
        assertTrue(config.asrModelIds.isEmpty())
        assertEquals(mapOf(
            LanguageCatalog.RUSSIAN to "tts-ru-piper-irina-medium",
            LanguageCatalog.ENGLISH to "tts-en-piper-lessac-medium",
        ), config.ttsModelIds)
    }
    @Test fun prepareSelectedConversationResources() = runBlocking<Unit> {
        val selectedPair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            commitNativeSettings(runtime, original.copy(languagePair = selectedPair,
                speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
                speechOutputBackend = SpeechOutputBackend.PIPER))
            val owner = runtime.createConversationOwner(runtime.settings.current().conversationConfig())
            try {
                withTimeout(1_200_000) { assertTrue(runtime.prepareConversation(owner)) }
                assertTrue(runtime.conversationReady(owner))
                Log.i(TAG, "selected preparation ready voices=${owner.config.ttsModelIds}")
            } finally { runtime.release(owner) }
        } finally {
            try { commitNativeSettings(runtime, original) } finally { runtime.closeAndJoin() }
        }
    }

    @Test fun preparedOfflineRecommendedVoicesAfterRestart() = runBlocking<Unit> {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            commitNativeSettings(runtime, original.copy(
                languagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            ))
            val config = runtime.settings.current().conversationConfig()
            val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
            try {
                val resources = SelectedSpeechResources.forConversation(store, config,
                    config.asrModelIds.values.toSet() + config.languageIdModelId)
                try {
                    assertTrue(resources.isConversationReady())
                    val output = SherpaConversationSpeechOutput(context, store, config)
                    try {
                        for ((language, phrase) in listOf(
                            LanguageCatalog.RUSSIAN to "Поезд отправляется в четыре часа.",
                            LanguageCatalog.ENGLISH to "The train leaves at four o'clock.",
                        )) {
                            val starts = AtomicInteger()
                            withTimeout(60_000) { output.speak(language, phrase) { starts.incrementAndGet() } }
                            assertTrue("Playback must start for ${language.value}", starts.get() > 0)
                            Log.i(TAG, "language=${language.value} model=${config.ttsModelIds.getValue(language)} callbacks=${starts.get()} complete=true")
                        }
                    } finally { output.closeAndJoin() }
                } finally { resources.close() }
            } finally { store.close() }
        } finally {
            try { commitNativeSettings(runtime, original) } finally { runtime.closeAndJoin() }
        }
    }


    private companion object { const val TAG = "AutomaticVoicesProof" }
}
