package com.example.offlinetranslator.conversation

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.MainActivity
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitPreparationCoordinator
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.sherpa.SherpaConversationRecognitionEngine
import com.example.offlinetranslator.sherpa.SherpaConversationSpeechOutput
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.runtime.appendReleaseFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real model/VAD/MT/Piper/AudioTrack checks; fixture PCM is paced, not microphone evidence. */
@RunWith(AndroidJUnit4::class)
class ConversationProductionIntegrationTest {
    private val defaultConfig = ConversationSessionConfig(
        languages = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
        engineId = "mlkit",
        speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
        vadModelId = "vad-silero-6.2.2",
        languageIdModelId = "lid-whisper-tiny",
        vadMinSilenceMs = 700,
        ttsSpeedPercent = 100,
        speechOutputBackend = SpeechOutputBackend.PIPER,
    )

    @Test fun prepareLanguagesThroughProductionInstaller() = runBlocking<Unit> {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
        val coordinator = MlKitPreparationCoordinator(context)
        val translation = MlKitTranslationEngine(context, defaultConfig.pairs, coordinator)
        val models = SelectedSpeechResources.forConversation(store, defaultConfig,
            defaultConfig.asrModelIds.values.toSet() + defaultConfig.languageIdModelId)
        val recognition = SherpaConversationRecognitionEngine(store, defaultConfig)
        try {
            withTimeout(1_200_000) {
                models.install()
                recognition.install()
            }
            assertEquals(ModelAvailability.READY, translation.prepare())
            assertTrue("Selected native VAD/Piper resources must be verified", models.isConversationReady())
            assertEquals("Selected genuine Sherpa ASR/LID must also be installed", SpeechRecognitionReadiness.READY, recognition.readiness())
        } finally {
            try { recognition.closeAndJoin() } finally {
                try { translation.closeAndJoin() } finally {
                    try { models.close() } finally { store.close() }
                }
            }
        }
    }

    @Test fun preparedOfflineConversationUsesActualDirectionAndSpeakerPresentation() = runBlocking<Unit> {
        requireOptIn()
        runConversation(stress = false)
    }

    @Test fun preparedOfflineConversationKeepsEveryLivePacedTurnWithinTwoSeconds() = runBlocking<Unit> {
        requireOptIn()
        runConversation(stress = true)
    }

    private suspend fun runConversation(stress: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "conversation-proof")
        val fixtures = File(root, "fixtures")
        val manifest = JSONObject(File(fixtures, "manifest.json").readText())
        val allCases = manifest.getJSONArray("cases")
        val cases = (0 until allCases.length()).map(allCases::getJSONObject)
            .filter { it.getString("cohort") == "smoke" && it.getString("id") in ConversationProofFixtures.CASE_ORDER }
            .associateBy { it.getString("id") }
        assertEquals(ConversationProofFixtures.CASE_ORDER.toSet(), cases.keys)
        val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
        val coordinator = MlKitPreparationCoordinator(context)
        val translation = MlKitTranslationEngine(context, defaultConfig.pairs, coordinator)
        val recognition = SherpaConversationRecognitionEngine(store, defaultConfig)
        val output = SherpaConversationSpeechOutput(context, store, defaultConfig)
        val models = SelectedSpeechResources.forConversation(store, defaultConfig,
            defaultConfig.asrModelIds.values.toSet() + defaultConfig.languageIdModelId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture = ConversationProofFixtures.PacedCapture(scope, store)
        val timing = ConversationProofFixtures.TurnTiming()
        val timedRecognition = object : ConversationRecognitionEngine by recognition {
            override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance {
                timing.speechEnd = segment.speechEndNanos ?: 0L
                timing.understandingStarted = System.nanoTime()
                try { return recognition.recognize(segment) }
                finally { timing.understandingFinished = System.nanoTime() }
            }
        }
        val timedTranslation = object : TranslationEngine by translation {
            override suspend fun translate(pair: LanguagePair, text: String): String {
                timing.translationStarted = System.nanoTime()
                try { return translation.translate(pair, text) }
                finally { timing.translationFinished = System.nanoTime() }
            }
        }
        val timedOutput = object : ConversationSpeechOutput by output {
            override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
                timing.outputStarted = System.nanoTime()
                try {
                    output.speak(language, text) { start ->
                        timing.presentation = start.presentationTimeNanos
                        onPlaybackStarted(start)
                    }
                } finally { timing.outputFinished = System.nanoTime() }
            }
        }
        val pipeline = ConversationPipeline(capture, timedRecognition, models, timedTranslation, timedOutput, scope)
        val retention = ConversationProofFixtures.RetentionObservation()
        val retentionMonitor = scope.launch {
            pipeline.state.collect { state ->
                retention.peakQueue = maxOf(retention.peakQueue, state.queuedSegments)
                retention.peakTranscripts = maxOf(retention.peakTranscripts, state.transcripts.size)
            }
        }
        val report = JSONObject().put("input", "live-paced committed fixture PCM through production VAD")
            .put("playback", "production Piper and AudioTrack")
            .put("microphoneEvidence", false).put("stress", stress)
        val rows = JSONArray()
        report.put("rows", rows)
        report.put("memoryBeforePreparation", ConversationProofFixtures.memorySnapshot())
        var warmMemory: JSONObject? = null
        val result = File(root, if (stress) "stress.json" else "controls.json")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var terminalFailure: Throwable? = null
        try {
            assertTrue("Prepare models online before the offline invocation", models.isConversationReady())
            assertEquals(ModelAvailability.READY, translation.modelAvailability())
            assertEquals("Wi-Fi must be disabled for offline proof", 0, android.provider.Settings.Global.getInt(context.contentResolver, "wifi_on"))
            assertEquals("Airplane mode must be enabled for offline proof", 1, android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
            val preparationStarted = System.nanoTime()
            assertTrue(pipeline.start())
            withTimeout(120_000) { pipeline.state.first { it.phase == ConversationPhase.LISTENING || it.phase == ConversationPhase.ERROR } }
            assertEquals(ConversationPhase.LISTENING, pipeline.state.value.phase)
            report.put("nativePreparationMillis", (System.nanoTime() - preparationStarted) / 1_000_000)
            val started = System.nanoTime()
            var index = 0
            do {
                val case = cases.getValue(ConversationProofFixtures.CASE_ORDER[index % ConversationProofFixtures.CASE_ORDER.size])
                val file = File(fixtures, case.getString("path"))
                assertEquals(case.getLong("bytes"), file.length())
                assertEquals(case.getString("sha256"), ConversationProofFixtures.sha256(file))
                val pcm = ConversationProofFixtures.readPcm(file)
                val previous = pipeline.state.value.transcripts.lastOrNull()?.id ?: 0L
                timing.reset()
                capture.replay(pcm)
                withTimeout(30_000) {
                    pipeline.state.first { state ->
                        state.transcripts.any { it.id > previous && it.status in TERMINAL }
                    }
                }
                val transcript = pipeline.state.value.transcripts.last()
                assertEquals(ConversationTranscriptStatus.COMPLETE, transcript.status)
                val expectedDirection = if (case.getString("language") == "ru") {
                    LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
                } else {
                    LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
                }
                assertEquals(expectedDirection, transcript.direction)
                assertNull(transcript.failure)
                ConversationProofFixtures.assertSourceMeaning(case, transcript.original, report)
                ConversationProofFixtures.assertTranslationMeaning(case, requireNotNull(transcript.translation))
                val row = JSONObject().put("index", index).put("case", case.getString("id"))
                    .put("original", transcript.original).put("translation", transcript.translation)
                    .put("latencyMillis", transcript.latencyMillis).put("latencyMeasured", transcript.latencyMeasured)
                    .put("timings", timing.toJson()).put("pacing", capture.pacing.toJson())
                    .put("memoryAfterTurn", ConversationProofFixtures.memorySnapshot())
                rows.put(row)
                if (stress) {
                    assertEquals("Turn dropped audio frames during stress run", 0, capture.pacing.catchUpFrames)
                    assertTrue("Turn exceeded two-second latency limit: ${transcript.latencyMillis}ms for ${case.getString("id")}",
                        requireNotNull(transcript.latencyMillis) <= 2_000L)
                }
                if (index == ConversationProofFixtures.CASE_ORDER.size - 1) warmMemory = ConversationProofFixtures.memorySnapshot()
                index++
            } while (if (stress) System.nanoTime() - started < 120_000_000_000L else index < ConversationProofFixtures.CASE_ORDER.size)
            pipeline.stop().getOrThrow()
            withTimeout(30_000) { pipeline.state.first { it.phase == ConversationPhase.READY && !it.sessionActive } }
            retentionMonitor.cancelAndJoin()
            assertEquals("Queue overflowed single-utterance limit", 1, retention.peakQueue)
            assertTrue("Transcripts retained more than thirty utterances", retention.peakTranscripts <= 30)
            report.put("memoryAfterTeardown", ConversationProofFixtures.memorySnapshot())
            if (!stress) assertMemoryPlateau(requireNotNull(warmMemory), ConversationProofFixtures.memorySnapshot())
            root.mkdirs()
            result.writeText(report.toString(2))
        } catch (failure: Throwable) {
            terminalFailure = failure
        } finally {
            withContext(NonCancellable) {
                try {
                    pipeline.clear().exceptionOrNull()?.let { terminalFailure = appendReleaseFailure(terminalFailure, it) }
                } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { capture.closeAndJoin() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { output.closeAndJoin() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { recognition.closeAndJoin() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { translation.closeAndJoin() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { models.close() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { store.close() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                try { scenario.close() } catch (failure: Throwable) {
                    terminalFailure = appendReleaseFailure(terminalFailure, failure)
                }
                scope.cancel()
            }
            terminalFailure?.let { throw it }
        }
    }

    private fun assertMemoryPlateau(warm: JSONObject, current: JSONObject) {
        assertTrue("Native heap grew by more than 64 MiB after the warmed control cycle",
            current.getLong("nativeAllocatedBytes") - warm.getLong("nativeAllocatedBytes") <= 64L * 1024 * 1024)
        assertTrue("PSS grew by more than 128 MiB after the warmed control cycle",
            current.getLong("pssKiB") - warm.getLong("pssKiB") <= 128L * 1024)
    }

    private fun requireOptIn() {
        assumeTrue("Opt in after staging the committed corpus; no download in ordinary connected tests",
            InstrumentationRegistry.getArguments().getString("runConversationIntegration") == "true")
    }

    companion object {
        private val TERMINAL = setOf(ConversationTranscriptStatus.COMPLETE, ConversationTranscriptStatus.ERROR)
    }
}
