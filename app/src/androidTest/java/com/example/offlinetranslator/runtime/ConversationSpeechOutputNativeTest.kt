package com.example.offlinetranslator.runtime

import android.media.AudioFormat
import android.net.ConnectivityManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.SherpaConversationSpeechOutput
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import com.example.offlinetranslator.speech.*
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real offline components; only approved fixture capture and output observation are test-owned. */
class ConversationSpeechOutputNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var runtime: AppRuntime
    private lateinit var original: AppSettings
    private val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    @Before fun retainSettingsAndRequireOffline() = runBlocking {
        assertOffline()
        runtime = AppRuntime.get(context)
        original = runtime.settings.current()
    }

    @After fun restoreSettingsAndRelease() = runBlocking {
        if (::runtime.isInitialized) {
            try {
                if (::original.isInitialized) commitNativeSettings(runtime, original)
            } finally {
                runtime.closeAndJoin()
            }
        }
    }

    @Test fun systemOutputIsAbsentWhileSilentAndMuteRetainsTheConversation() = runBlocking {
        proveOutputControl(SpeechOutputBackend.SYSTEM_OFFLINE)
    }

    @Test fun piperOutputIsAbsentWhileSilentAndMuteRetainsTheConversation() = runBlocking {
        proveOutputControl(SpeechOutputBackend.PIPER)
    }

    private suspend fun proveOutputControl(backend: SpeechOutputBackend) {
        val selected = original.copy(languagePair = pair, translationEngineId = "tencent",
            translationSetupComplete = true, speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
            speechOutputBackend = backend, speechOutputEnabled = false)
        commitNativeSettings(runtime, selected)
        val config = selected.conversationConfig()
        val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture = ConversationProofFixtures.PacedCapture(scope, store, config.vadModelId, config.vadMinSilenceMs)
        val output = observedOutput(backend, store, config)
        val recognition = WhisperConversationRecognitionEngine(store, config)
        val translation = runtime.registry.create(config.engineId, config.pairs)
        val nativeModels = SelectedSpeechResources.forConversation(store, config,
            setOf(WhisperConversationRecognitionEngine.MODEL_ID))
        val owner = runtime.owners.replace {
            ConversationRuntimeOwner(config, nativeModels, capture, recognition, translation, output, scope,
                speechOutputEnabled = false)
        }
        val pipeline = owner.pipeline
        try {
            assertTrue("BLOCKED: pinned VAD and selected Whisper data must already be installed",
                owner.models.isConversationReady(speechOutputRequired = false))
            assertEquals("BLOCKED: explicitly prepared Tencent weights are required; this test never installs",
                ModelAvailability.READY, translation.prepare())
            assertTrue(runtime.startConversation(owner))
            awaitCapture(pipeline)
            assertEquals(0, output.readinessCalls.get())
            assertEquals(0, output.preparationCalls.get())
            assertSilentSystemBoundary(output)

            val first = replayComplete(capture, pipeline, "speech-en-fixture.wav", 0)
            assertEquals(pair.reverse(), first.direction)
            assertNull("A never-played turn cannot report playback latency", first.latencyMillis)
            assertFalse(first.latencyMeasured)
            assertTrue(pipeline.state.value.sessionActive && pipeline.state.value.captureActive)
            assertTrue(capture.running)
            assertTrue(output.requests.isEmpty())
            assertEquals(0, output.readinessCalls.get())
            assertEquals(0, output.preparationCalls.get())
            assertSilentSystemBoundary(output)

            val generation = pipeline.disableSpeechOutput()
            assertEquals("BLOCKED: installed voices for the selected backend are required for the enabled slice",
                SpeechOutputReadiness.READY, pipeline.prepareSpeechOutput(generation))
            assertTrue(runtime.saveSpeechOutput(owner, true) {
                runtime.owners.isCurrent(owner) && pipeline.isSpeechOutputRequestCurrent(generation)
            })
            assertTrue(pipeline.enableSpeechOutput(generation))
            assertTrue("Enabling must not replay the completed first translation", output.requests.isEmpty())
            assertEquals(first, pipeline.state.value.transcripts.single())

            capture.replay(loadPcm("speech-en-fixture.wav"))
            withTimeout(180_000) { output.started.await() }
            output.system?.let { evidence ->
                withTimeout(15_000) { evidence.nonSilent.await() }
                assertEquals(AudioFormat.ENCODING_PCM_16BIT, evidence.format)
                assertTrue(evidence.sampleRate > 0 && evidence.channels in 1..2)
                assertTrue(evidence.audioBytes.get() > 0)
                val voice = evidence.thread.call { requireNotNull(evidence.client?.currentVoice) }
                assertEquals("ru", voice.locale.language)
                assertFalse(voice.networkRequired)
                assertFalse(voice.notInstalled)
            }
            val speakingState = pipeline.state.value
            assertEquals("The test must interrupt genuine in-progress output, not a completed turn",
                ConversationPhase.SPEAKING, speakingState.phase)
            val spoken = speakingState.transcripts.last()
            assertEquals(ConversationTranscriptStatus.SPEAKING, spoken.status)
            assertEquals(pair.reverse(), spoken.direction)
            assertFalse(speakingState.captureActive)
            assertEquals(listOf(spoken.translation), output.requests.toList())

            pipeline.disableSpeechOutput()
            assertFalse(pipeline.speechOutputEnabled.value)
            assertTrue(runtime.saveSpeechOutput(owner, false) { runtime.owners.isCurrent(owner) })
            val resumed = withTimeout(30_000) {
                pipeline.state.first { state -> state.failure != null ||
                    (state.captureActive && state.transcripts.lastOrNull()?.status == ConversationTranscriptStatus.COMPLETE) }
            }
            assertNull("Intentional mute must not become a session error", resumed.failure)
            assertTrue(resumed.sessionActive && resumed.captureActive && capture.running)
            assertEquals(spoken.translation, resumed.transcripts.last().translation)
            assertEquals(first, resumed.transcripts.first())
            assertFalse(pipeline.releaseFailed)
            output.system?.let { evidence ->
                withTimeout(15_000) { evidence.stopped.await() }
                assertEquals(TextToSpeech.SUCCESS, evidence.lastStopResult)
            }

            val last = replayComplete(capture, pipeline, "speech-en-fixture.wav", spoken.id)
            assertEquals(pair.reverse(), last.direction)
            assertNull(last.latencyMillis)
            assertFalse(last.latencyMeasured)
            assertEquals("No old-row replay or new synthesis is permitted after mute", listOf(spoken.translation),
                output.requests.toList())
            assertTrue(pipeline.state.value.sessionActive && pipeline.state.value.captureActive && capture.running)
            assertFalse(runtime.settings.current().speechOutputEnabled)
            assertTrue(pipeline.stop().isSuccess)
            assertFalse(pipeline.state.value.sessionActive || pipeline.state.value.captureActive || capture.running)
            assertOffline()

            val evidence = JSONObject().put("backend", backend.persistedId).put("translator", config.engineId)
                .put("recognition", config.speechRecognitionBackend.persistedId)
                .put("firstSilentOriginal", first.original).put("firstSilentTranslation", first.translation)
                .put("neverPlayedLatencyNull", first.latencyMillis == null)
                .put("spokenNewTranslation", spoken.translation).put("lastSilentTranslation", last.translation)
                .put("userSpeechRequests", output.requests.size).put("mutedSessionRetained", true)
                .put("nextTurnCaptured", true).put("savedOutputEnabled", false).put("stoppedIdle", true)
                .put("physicalMicrophoneOrSpeakerEvidence", false)
            output.system?.let { system ->
                evidence.put("systemClientCreations", system.clientCreations.get())
                    .put("systemFocusRequests", system.focusRequests.get()).put("systemPcmBytes", system.audioBytes.get())
                    .put("systemPcmFormat", system.format).put("systemStopResult", system.lastStopResult)
                    .put("systemEnginePackage", system.output?.enginePackage)
            }
            val directory = File(context.filesDir, "conversation-speech-output-proof").apply { mkdirs() }
            File(directory, "${backend.persistedId}.json").writeText(evidence.toString(2))
        } finally {
            try { runtime.release(owner) } finally {
                scope.cancel()
                store.close()
            }
        }
    }

    private suspend fun awaitCapture(pipeline: ConversationPipeline) {
        val state = withTimeout(120_000) { pipeline.state.first { it.captureActive || it.failure != null } }
        assertNull("Real pipeline startup failed", state.failure)
        assertTrue(state.sessionActive && state.captureActive)
    }

    private suspend fun replayComplete(
        capture: ConversationProofFixtures.PacedCapture,
        pipeline: ConversationPipeline,
        asset: String,
        previousId: Long,
    ): ConversationTranscript {
        capture.awaitReplay()
        capture.replay(loadPcm(asset))
        val state = withTimeout(180_000) {
            pipeline.state.first { it.failure != null || (it.captureActive &&
                it.transcripts.lastOrNull()?.let { row -> row.id > previousId && row.status == ConversationTranscriptStatus.COMPLETE } == true) }
        }
        assertNull("Real recognition/translation failed", state.failure)
        val row = state.transcripts.last()
        assertEquals(ConversationTranscriptStatus.COMPLETE, row.status)
        assertTrue(row.original.isNotBlank() && !row.translation.isNullOrBlank())
        capture.awaitReplay()
        return row
    }

    private fun loadPcm(asset: String): ShortArray {
        val file = File.createTempFile("output-fixture-", ".wav", context.cacheDir)
        return try {
            instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
            ConversationProofFixtures.readPcm(file)
        } finally { file.delete() }
    }

    private fun observedOutput(
        backend: SpeechOutputBackend,
        store: SpeechModelStore,
        config: ConversationSessionConfig,
    ): ObservedOutput {
        if (backend == SpeechOutputBackend.PIPER) {
            return ObservedOutput(SherpaConversationSpeechOutput(context, store, config), null)
        }
        val evidence = SystemEvidence()
        val focus = object : TtsAudioFocus {
            private val actual = AndroidTtsAudioFocus(context)
            override fun request(onLoss: () -> Unit): Boolean {
                evidence.focusRequests.incrementAndGet()
                return actual.request(onLoss)
            }
            override fun abandon() = actual.abandon()
        }
        val actual = SystemConversationSpeechOutput(config.languages, config.ttsSpeedPercent, { initialized ->
            evidence.clientCreations.incrementAndGet()
            val client = ObservedClient(AndroidTtsClient(context, initialized, evidence), evidence)
            evidence.client = client
            client
        }, focus, evidence.thread, Dispatchers.Main.immediate)
        evidence.output = actual
        return ObservedOutput(actual, evidence)
    }

    private fun assertSilentSystemBoundary(output: ObservedOutput) {
        output.system?.let { evidence ->
            assertEquals("Silent startup must not initialize Android TTS", 0, evidence.clientCreations.get())
            assertEquals("Silent capture/translation must not acquire speech focus", 0, evidence.focusRequests.get())
            assertEquals(0, evidence.audioBytes.get())
        }
    }

    private class ObservedOutput(
        private val actual: ConversationSpeechOutput,
        val system: SystemEvidence?,
    ) : ConversationSpeechOutput by actual {
        val readinessCalls = AtomicInteger()
        val preparationCalls = AtomicInteger()
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val started = CompletableDeferred<Unit>()
        override suspend fun readiness(): SpeechOutputReadiness {
            readinessCalls.incrementAndGet()
            return actual.readiness()
        }
        override suspend fun prepare() {
            preparationCalls.incrementAndGet()
            actual.prepare()
        }
        override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
            requests += text
            actual.speak(language, text) { start ->
                onPlaybackStarted(start)
                started.complete(Unit)
            }
        }
    }

    private class SystemEvidence : TtsSynthesisObserver {
        val thread = AndroidTtsThread()
        val clientCreations = AtomicInteger()
        val focusRequests = AtomicInteger()
        val audioBytes = AtomicInteger()
        val nonSilent = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        @Volatile var client: TtsClient? = null
        @Volatile var output: SystemConversationSpeechOutput? = null
        @Volatile var userRequest: String? = null
        @Volatile var lastStopResult: Int? = null
        @Volatile var format = 0
        @Volatile var sampleRate = 0
        @Volatile var channels = 0
        override fun onBeginSynthesis(id: String, sampleRate: Int, audioFormat: Int, channels: Int) {
            if (id == userRequest) {
                this.sampleRate = sampleRate
                this.format = audioFormat
                this.channels = channels
            }
        }
        override fun onAudioAvailable(id: String, audio: ByteArray) {
            if (id == userRequest) {
                audioBytes.addAndGet(audio.size)
                if (audio.any { it != 0.toByte() }) nonSilent.complete(Unit)
            }
        }
    }

    private class ObservedClient(private val actual: TtsClient, private val evidence: SystemEvidence) : TtsClient by actual {
        override fun speak(text: String, utteranceId: String): Int {
            evidence.userRequest = utteranceId
            return actual.speak(text, utteranceId)
        }
        override fun setListener(listener: TtsListener): Int = actual.setListener(object : TtsListener by listener {
            override fun onStop(id: String) {
                if (id == evidence.userRequest) evidence.stopped.complete(Unit)
                listener.onStop(id)
            }
        })
        override fun stop(): Int = actual.stop().also { evidence.lastStopResult = it }
    }

    private fun assertOffline() {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("No default network during native proof", connectivity.activeNetwork)
        assertTrue("No current networks during native proof", connectivity.allNetworks.isEmpty())
    }
}
