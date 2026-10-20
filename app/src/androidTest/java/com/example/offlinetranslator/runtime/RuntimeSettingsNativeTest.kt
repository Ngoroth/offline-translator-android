package com.example.offlinetranslator.runtime

import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.conversation.ConversationViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/** Real production VM and controller with official RU PCM through native VAD, LID, ASR, MT, TTS, playback. */
class RuntimeSettingsNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: AppRuntime
    private lateinit var original: AppSettings
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Before fun setUp() = runBlocking {
        runtime = AppRuntime.get(context)
        original = runtime.settings.current()
        val config = AppSettings(
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
        )
        commitNativeSettings(runtime, config)
    }

    @After fun tearDown() {
        scope.cancel()
        if (!::original.isInitialized) return
        runBlocking {
            runtime.closeAndJoin()
            val restoring = AppRuntime.get(context)
            try {
                commitNativeSettings(restoring, original)
                assertEquals(original, restoring.settings.current())
            } finally {
                restoring.closeAndJoin()
            }
        }
    }

    @Test fun preparedRuntimeOwnerProcessesOfficialRussianFixture() = runBlocking {
        val config = runtime.settings.current().conversationConfig()
        var pacedCapture: ConversationProofFixtures.PacedCapture? = null
        val owner = runtime.createConversationOwner(config) { store, sel ->
            ConversationProofFixtures.PacedCapture(scope, store, sel.vadModelId, sel.vadMinSilenceMs).also {
                pacedCapture = it
            }
        }
        val viewModel = ConversationViewModel(owner, runtime.owners)

        try {
            assertTrue("Selected speech resources must be ready", runtime.conversationReady(owner))
            withTimeout(30_000) { viewModel.ready.first { it } }

            viewModel.start()
            withTimeout(30_000) {
                owner.pipeline.state.first { it.phase == ConversationPhase.LISTENING || it.phase == ConversationPhase.ERROR }
            }
            assertEquals(ConversationPhase.LISTENING, owner.pipeline.state.value.phase)
            assertTrue("Runtime controller must report busy during active session", runtime.busy.value)

            // Settings save must be rejected while the session is active without modifying persisted settings
            val beforeBusyApply = runtime.settings.current()
            assertFalse(runtime.applySettings(AppSettings(vadMinSilenceMs = 1200)))
            assertEquals(beforeBusyApply, runtime.settings.current())

            val pcm = loadOfficialRussianPcm()
            val capture = requireNotNull(pacedCapture)
            val previousId = owner.pipeline.state.value.transcripts.lastOrNull()?.id ?: 0L

            capture.replay(pcm)
            withTimeout(45_000) {
                owner.pipeline.state.first { state ->
                    state.transcripts.any { it.id > previousId && it.status == ConversationTranscriptStatus.COMPLETE }
                }
            }
            val completed = owner.pipeline.state.value.transcripts.last { it.id > previousId }
            assertEquals(ConversationTranscriptStatus.COMPLETE, completed.status)
            val direction = checkNotNull(completed.direction)
            assertEquals(LanguageCatalog.RUSSIAN, direction.source)
            assertEquals(LanguageCatalog.ENGLISH, direction.target)
            val translatedText = checkNotNull(completed.translation)
            assertTrue("Translation must be non-empty", translatedText.isNotBlank())

            viewModel.stop()
            withTimeout(15_000) {
                owner.pipeline.state.first { !it.sessionActive }
            }
            assertFalse("Pipeline must not be active after stop", owner.pipeline.state.value.sessionActive)
        } finally {
            viewModel.disposeAndJoin()
        }
    }

    @Test fun recognitionBackendCutoverReleasesOldOwnerAndRejectsStaleConfiguration() = runBlocking {
        val stale = runtime.settings.current().conversationConfig()
        val old = runtime.createConversationOwner(stale)
        assertTrue(old.recognition is com.example.offlinetranslator.sherpa.SherpaConversationRecognitionEngine)
        val advanced = runtime.settings.current().copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        assertTrue(runtime.applySettings(advanced))
        assertEquals(SpeechOutputBackend.PIPER, runtime.settings.current().speechOutputBackend)
        assertFalse("Backend change must release the old owner", runtime.owners.isCurrent(old))
        var staleCaptureBuilt = false
        assertNull(runtime.createConversationOwnerIfUnowned(stale) { _, _ ->
            staleCaptureBuilt = true
            error("Stale backend must be rejected before capture construction")
        })
        assertFalse(staleCaptureBuilt)
        assertFalse("Closed prior owner must not acquire the new runtime lease", runtime.startConversation(old))
        val fresh = runtime.createConversationOwner(runtime.settings.current().conversationConfig())
        try {
            assertTrue(fresh.recognition is com.example.offlinetranslator.speech.AndroidConversationRecognitionEngine)
            assertTrue(fresh.config.asrModelIds.isEmpty())
            assertEquals(SpeechOutputBackend.PIPER, fresh.config.speechOutputBackend)
        } finally { runtime.release(fresh) }
    }

    @Test fun recognitionServiceCutoverRevokesOwnerAndRejectsOldServiceBeforeCapture() = runBlocking {
        assertTrue(runtime.applySettings(runtime.settings.current().copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)))
        val previous = runtime.settings.current()
        val stale = previous.conversationConfig()
        val owner = runtime.createConversationOwner(stale)
        val selected = previous.copy(speechRecognitionService = "missing.provider/.RecognitionService")
        assertTrue(runtime.applySettings(selected))
        assertEquals(selected, runtime.settings.current())
        assertFalse(runtime.owners.isCurrent(owner))
        var staleCaptureBuilt = false
        assertNull(runtime.createConversationOwnerIfUnowned(stale) { _, _ ->
            staleCaptureBuilt = true
            error("Stale service must be rejected before capture construction")
        })
        assertFalse(staleCaptureBuilt)
        assertFalse(runtime.startConversation(owner))
        runtime.closeAndJoin()
        runtime = AppRuntime.get(context)
        assertEquals(selected, runtime.settings.current())
    }

    private fun loadOfficialRussianPcm(): ShortArray {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val temp = File(context.cacheDir, "temp-native-ru.wav")
        testContext.assets.open("speech-ru-fixture.wav").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            return ConversationProofFixtures.readPcm(temp)
        } finally {
            temp.delete()
        }
    }
}
