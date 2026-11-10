package io.github.ngoroth.offlinetranslator.runtime

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt in with runSharedTranslationEngineNative=true, with no Activity using AppRuntime.
 * Keep the stored Tencent language pair and valid Conversation configuration; stage the
 * actual Tencent weights beforehand and disconnect every network. No settings writes,
 * downloads, speech preparation, microphone access, or installation occur here.
 */
class SharedTranslationEngineNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedOfflineTranslatorSurvivesRouteSwitchesAndReloadsOnlyAfterEviction() = runBlocking {
        assumeTrue(
            "Opt in via runSharedTranslationEngineNative=true after installing Tencent locally",
            InstrumentationRegistry.getArguments().getString("runSharedTranslationEngineNative") == "true",
        )
        assertOffline()
        // Establish a cold native lifetime without changing persisted settings.
        AppRuntime.get(context).closeAndJoin()
        val runtime = AppRuntime.get(context)
        val owners = mutableListOf<RuntimeOwner>()
        val observations = mutableListOf<Job>()
        val loading = AtomicInteger()
        val downloading = AtomicInteger()
        var original: AppSettings? = null
        val stages = JSONArray()
        val proofFile = File(context.filesDir, "shared-translation-reuse-proof/result.json")
        fun recordProgress(stage: String) {
            stages.put(JSONObject().put("stage", stage)
                .put("loading", loading.get()).put("downloading", downloading.get()))
            check(proofFile.parentFile!!.isDirectory || proofFile.parentFile!!.mkdirs())
            proofFile.writeText(JSONObject().put("stages", stages)
                .put("completed", stage == "completed").toString())
        }
        try {
            recordProgress("started")
            withTimeout(600_000) {
                try {
                val selected = runtime.settings.current()
                original = selected
                assertEquals("Stored configuration must already select Tencent", "tencent", selected.translationEngineId)
                runtime.enterForeground()
                val pair = selected.languagePair
                val conversationConfig = selected.conversationConfig()

                fun observe(engine: TranslationEngine) {
                    // Subscribe before this lease's first initialization. Different route leases
                    // need not expose the same flow; identity alone would not prove native reuse.
                    observations += launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        countPreparation(engine.preparation, loading, downloading)
                    }
                }

                val first = runtime.createTextOwner(selected.textConfig()).also { owners += it }
                observe(first.translation)
                assertEquals(ModelAvailability.READY, runtime.useText(first) { it.prepareLocal() })
                assertNativeText(requireNotNull(runtime.useText(first) { it.translate(pair, "123") }))
                assertPairRejected(first.translation, pair.reverse())
                assertCounts(loading, downloading, 1)
                recordProgress("firstText")
                runtime.release(first)
                assertNull("Released Text owner cannot execute", runtime.useText(first) { error("Revoked owner ran") })
                assertRevoked(first.translation)

                val capture = NeverStartedCapture()
                val conversation = runtime.createConversationOwner(conversationConfig) { _, _ -> capture }
                    .also { owners += it }
                observe(conversation.translation)
                // Translator-only proof: deliberately do not call prepareConversation/startConversation.
                assertEquals(ModelAvailability.READY, conversation.translation.prepareLocal())
                for (direction in conversationConfig.pairs) {
                    assertNativeText(conversation.translation.translate(direction, "123"))
                }
                val unselected = listOf(LanguageCatalog.ENGLISH, LanguageCatalog.CHINESE, LanguageCatalog.RUSSIAN)
                    .filter { it != pair.source }
                    .map { LanguagePair(pair.source, it) }
                    .first { it !in conversationConfig.pairs }
                assertPairRejected(conversation.translation, unselected)
                assertCounts(loading, downloading, 1)
                recordProgress("conversation")
                assertEquals(0, capture.starts.get())
                runtime.release(conversation)
                assertTrue("Route release closes even an unused capture", capture.closed)
                assertEquals(0, capture.starts.get())
                assertFalse("Released Conversation owner cannot start", runtime.startConversation(conversation))
                assertRevoked(conversation.translation)

                val last = runtime.createTextOwner(selected.textConfig()).also { owners += it }
                observe(last.translation)
                assertEquals(ModelAvailability.READY, runtime.useText(last) { it.prepareLocal() })
                assertNativeText(requireNotNull(runtime.useText(last) { it.translate(pair, "123") }))
                // The shared native model covers both directions, but a Text lease must not.
                assertPairRejected(last.translation, pair.reverse())
                assertCounts(loading, downloading, 1)
                recordProgress("lastText")

                // Idle eviction is bounded and cannot interrupt microphone/native inference work.
                runtime.leaveForeground()
                assertEquals(TranslationPreparationPhase.MISSING, last.translation.preparation.value.phase)
                assertCounts(loading, downloading, 1)
                runtime.enterForeground()
                assertEquals(TranslationPreparationPhase.MISSING, last.translation.preparation.value.phase)
                assertEquals(ModelAvailability.READY, runtime.useText(last) { it.prepareLocal() })
                assertNativeText(requireNotNull(runtime.useText(last) { it.translate(pair, "123") }))
                assertCounts(loading, downloading, 2)
                recordProgress("backgroundReload")

                runtime.trimMemory()
                assertEquals(TranslationPreparationPhase.MISSING, last.translation.preparation.value.phase)
                assertCounts(loading, downloading, 2)
                assertEquals(ModelAvailability.READY, runtime.useText(last) { it.prepareLocal() })
                assertNativeText(requireNotNull(runtime.useText(last) { it.translate(pair, "123") }))
                assertCounts(loading, downloading, 3)
                recordProgress("trimReload")
                assertEquals(selected, runtime.settings.current())
                assertOffline()
                } finally {
                    // launch in observe() inherits this withTimeout scope, so drain its
                    // infinite StateFlow collectors before structured completion waits on them.
                    withContext(NonCancellable) {
                        observations.forEach { it.cancelAndJoin() }
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                observations.forEach { it.cancelAndJoin() }
                try {
                    // Close every created owner even when an earlier assertion or close fails.
                    var failure: Throwable? = null
                    owners.asReversed().forEach { owner ->
                        try { runtime.release(owner) } catch (closing: Throwable) {
                            if (failure == null) failure = closing else failure!!.addSuppressed(closing)
                        }
                    }
                    original?.let { assertEquals("Settings must remain untouched", it, runtime.settings.current()) }
                    failure?.let { throw it }
                } finally {
                    runtime.closeAndJoin()
                }
            }
        }
        recordProgress("completed")
    }

    private suspend fun countPreparation(
        preparation: StateFlow<TranslationPreparationState>,
        loading: AtomicInteger,
        downloading: AtomicInteger,
    ) {
        var previous: TranslationPreparationPhase? = null
        preparation.collect { state ->
            if (state.phase != previous) {
                if (state.phase == TranslationPreparationPhase.LOADING) loading.incrementAndGet()
                if (state.phase == TranslationPreparationPhase.DOWNLOADING) downloading.incrementAndGet()
            }
            previous = state.phase
        }
    }

    private fun assertCounts(loading: AtomicInteger, downloading: AtomicInteger, expectedLoads: Int) {
        assertEquals("Actual Tencent initialization transitions", expectedLoads, loading.get())
        assertEquals("Local preparation must never download", 0, downloading.get())
    }

    private fun assertNativeText(result: String) {
        assertTrue("Real JNI must produce nonblank output for fixed synthetic input", result.isNotBlank())
    }

    private suspend fun assertPairRejected(engine: TranslationEngine, pair: LanguagePair) {
        val failure = try {
            engine.translate(pair, "123")
            null
        } catch (rejected: IllegalArgumentException) {
            rejected
        }
        assertNotNull("Lease must reject an unselected direction", failure)
    }

    private suspend fun assertRevoked(engine: TranslationEngine) {
        val failure = try {
            engine.prepareLocal()
            null
        } catch (revoked: IllegalStateException) {
            revoked
        }
        assertNotNull("Old route must not reacquire the retained translator", failure)
    }

    private fun assertOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("Disconnect networking before this opt-in test", connectivity.activeNetwork)
        assertTrue("No active networks may remain", connectivity.allNetworks.isEmpty())
    }

    private class NeverStartedCapture : SpeechCaptureEngine {
        val starts = AtomicInteger()
        @Volatile var closed = false
            private set

        override suspend fun start(listener: SpeechCaptureListener) {
            starts.incrementAndGet()
            error("Translator-only test must never start microphone capture")
        }
        override fun interruptCurrent() = Unit
        override suspend fun stop(flush: Boolean): SpeechSegment? = null
        override fun close() { closed = true }
    }
}
