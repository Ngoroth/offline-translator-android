package io.github.ngoroth.offlinetranslator.runtime

import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.SpeechOutputBackend
import io.github.ngoroth.offlinetranslator.core.SpeechRecognitionBackend
import io.github.ngoroth.offlinetranslator.speechmodels.SelectedSpeechResources
import io.github.ngoroth.offlinetranslator.sherpa.SherpaConversationSpeechOutput
import io.github.ngoroth.offlinetranslator.speechmodels.SpeechModelStore
import io.github.ngoroth.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Explicit preparation and fresh-process offline production audio-path checks, not acoustic qualification. */
class AutomaticLanguageVoicesNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext


    @Test fun prepareSelectedConversationResources() = runBlocking<Unit> {
        val selectedPair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            commitNativeSettings(runtime, original.copy(languagePair = selectedPair,
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
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
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
            ))
            val config = runtime.settings.current().conversationConfig()
            val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
            try {
                val resources = SelectedSpeechResources.forConversation(store, config,
                    setOf(io.github.ngoroth.offlinetranslator.sherpa.WhisperConversationRecognitionEngine.MODEL_ID))
                try {
                    assertTrue(resources.isConversationReady(speechOutputRequired = true))
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
