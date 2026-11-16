package io.github.ngoroth.offlinetranslator.runtime

import android.net.ConnectivityManager
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.conversation.ConversationProofFixtures
import io.github.ngoroth.offlinetranslator.core.*
import io.github.ngoroth.offlinetranslator.mozilla.MozillaTranslationEngine
import io.github.ngoroth.offlinetranslator.mozilla.MozillaTranslationPreparation
import io.github.ngoroth.offlinetranslator.sherpa.SherpaConversationSpeechOutput
import io.github.ngoroth.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import io.github.ngoroth.offlinetranslator.sherpa.SherpaSpeechSegmenter
import io.github.ngoroth.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import io.github.ngoroth.offlinetranslator.speechmodels.SelectedSpeechResources
import io.github.ngoroth.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Fixed PCM through real VAD/Whisper transcribe/Mozilla/Piper; never microphone or acoustic proof. */
class MozillaConversationQualificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Before fun requireExplicitOptIn() {
        assumeTrue("Opt in with runMozillaConversationQualification=true and an explicit phase/runId",
            arguments.getString("runMozillaConversationQualification") == "true")
    }

    @Test fun prepareSelectedSpeechResources() = runBlocking<Unit>(Dispatchers.Default) {
        require(arguments.getString("allowModelDownload") == "true") { "Speech preparation requires allowModelDownload=true" }
        execute("prepare-speech", 0) { run ->
            var store: SpeechModelStore? = null
            var models: ConversationModelRepository? = null
            var recognition: WhisperConversationRecognitionEngine? = null
            try {
                val config = configuration()
                val selectedStore = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context)).also { store = it }
                val selectedModels = SelectedSpeechResources.forConversation(selectedStore, config,
                    setOf(WhisperConversationRecognitionEngine.MODEL_ID)).also { models = it }
                val selectedRecognition = WhisperConversationRecognitionEngine(selectedStore, config).also { recognition = it }
                run.event(resourceDescription(config, selectedStore).put("kind", "speech_preparation_start").put("memory", run.memory()))
                val started = SystemClock.elapsedRealtimeNanos()
                withTimeout(1_200_000) {
                    selectedModels.install(speechOutputRequired = true)
                    selectedRecognition.install()
                    check(selectedModels.isConversationReady(speechOutputRequired = true)) { "VAD/Piper installation incomplete" }
                    check(selectedRecognition.readiness() == SpeechRecognitionReadiness.READY) { "Whisper installation incomplete" }
                }
                run.event(JSONObject().put("kind", "speech_preparation_complete")
                    .put("elapsedNs", SystemClock.elapsedRealtimeNanos() - started).put("memory", run.memory())
                    .put("mozillaPreparedHere", false).put("selectedPackages", packageStates(selectedStore)))
            } finally {
                run.release("recognition") { recognition?.closeAndJoin() }
                run.release("models") { models?.close() }
                run.release("store") { store?.close() }
            }
        }
    }

    @Test fun preparedOfflineConversationBothDirectionsWithAndWithoutOutput() = runBlocking<Unit>(Dispatchers.Default) {
        execute("conversation-offline", 4) { run ->
            val config = configuration()
            val pcm = fixtures.associateWith { loadPcm(run, it) }
            val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val controller = RuntimeOwnerController(controlScope)
            var store: SpeechModelStore? = null
            var activity: ActivityScenario<ComponentActivity>? = null
            try {
                // Existing debug ui-test-manifest activity: no MainActivity/AppRuntime/settings route is mounted.
                activity = ActivityScenario.launch(ComponentActivity::class.java)
                val selectedStore = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context)).also { store = it }
                run.event(resourceDescription(config, selectedStore).put("kind", "selected_resources")
                    .put("foregroundActivity", ComponentActivity::class.java.name).put("memory", run.memory()))
                for (enabled in listOf(false, true)) runOwner(run, controller, selectedStore, config, pcm, enabled)
            } finally {
                run.release("controller") { controller.closeAndJoin() }
                run.release("store") { store?.close() }
                run.release("blank_activity") { activity?.close() }
                controlScope.coroutineContext[Job]?.cancelAndJoin()
            }
        }
    }

    private suspend fun runOwner(
        run: Evidence, controller: RuntimeOwnerController, store: SpeechModelStore, config: ConversationSessionConfig,
        pcm: Map<Fixture, ShortArray>, enabled: Boolean,
    ) {
        val mode = if (enabled) "enabled" else "disabled"
        val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val observation = Observation()
        val invocations = mutableListOf<Invocation>()
        var models: ConversationModelRepository? = null
        var capture: QualifiedCapture? = null
        var recognition: WhisperConversationRecognitionEngine? = null
        var translation: MozillaTranslationEngine? = null
        var output: SherpaConversationSpeechOutput? = null
        var owner: ConversationRuntimeOwner? = null
        var startupFailure: Throwable? = run.releaseFailure
        run.event(JSONObject().put("kind", "owner_start").put("speechOutputEnabled", enabled).put("memory", run.memory()))
        try {
            if (startupFailure == null) try {
                val selectedModels = SelectedSpeechResources.forConversation(store, config,
                    setOf(WhisperConversationRecognitionEngine.MODEL_ID)).also { models = it }
                val selectedCapture = QualifiedCapture(run, ownerScope, store, config, observation).also { capture = it }
                val selectedRecognition = WhisperConversationRecognitionEngine(store, config).also { recognition = it }
                val selectedTranslation = MozillaTranslationEngine(config.pairs, MozillaTranslationPreparation(context)).also { translation = it }
                val selectedOutput = SherpaConversationSpeechOutput(context, store, config).also { output = it }
                val created = controller.replace {
                    ConversationRuntimeOwner(config, selectedModels, selectedCapture,
                        observedRecognition(run, selectedRecognition, observation), observedTranslation(run, selectedTranslation, observation),
                        observedOutput(run, selectedOutput, observation), ownerScope, speechOutputEnabled = enabled)
                }.also { owner = it; observation.pipeline = it.pipeline; run.ownersCreated++ }
                val started = SystemClock.elapsedRealtimeNanos()
                check(created.models.isConversationReady(speechOutputRequired = enabled)) { "Prepare selected VAD/Whisper/Piper resources explicitly first" }
                check(created.translation.prepareLocal() == ModelAvailability.READY) { "Prepare Mozilla RU/EN packs explicitly first" }
                startListening(controller, created)
                if (!enabled) check(observation.noOutputAdmissions()) { "Disabled startup admitted TTS readiness/preparation/synthesis" }
                run.event(JSONObject().put("kind", "owner_ready").put("speechOutputEnabled", enabled)
                    .put("startupElapsedNs", SystemClock.elapsedRealtimeNanos() - started).put("state", stateJson(created.pipeline.state.value))
                    .put("outputAdmissions", observation.admissions()).put("memory", run.memory())
                    .put("coexistence", if (enabled) "Prepared real Whisper + Mozilla bidirectional owner + both Piper voices" else "Prepared real Whisper + Mozilla; Piper not admitted"))
            } catch (failure: Throwable) {
                startupFailure = failure
                run.failures.incrementAndGet()
                run.event(JSONObject().put("kind", "owner_start_failed").put("speechOutputEnabled", enabled).put("error", error(failure))
                    .put("state", owner?.pipeline?.state?.value?.let(::stateJson) ?: JSONObject.NULL).put("memory", run.memory()))
                owner?.let { current -> run.release("startup_failure_stop_$mode") { current.pipeline.stop().getOrThrow() } }
            }
            for (fixture in fixtures) {
                run.attempted++
                val invocation = Invocation("${fixture.id}-$mode")
                invocations += invocation
                val row = fixtureJson(fixture).put("kind", "wave_invocation").put("id", invocation.id).put("speechOutputEnabled", enabled)
                    .put("memoryBefore", run.memory())
                var failure: Throwable? = null
                var admitted = false
                var drained = false
                var previous = 0L
                var producer: Replay? = null
                val started = SystemClock.elapsedRealtimeNanos()
                try {
                    run.releaseFailure?.let { throw it }
                    startupFailure?.let { throw it }
                    val current = requireNotNull(owner)
                    val selectedCapture = requireNotNull(capture)
                    if (!current.pipeline.state.value.sessionActive) startListening(controller, current)
                    check(current.pipeline.state.value.captureActive && selectedCapture.running) { "Pipeline is not capturing" }
                    previous = current.pipeline.state.value.transcripts.lastOrNull()?.id ?: 0L
                    observation.invocation = invocation
                    val startedReplay = selectedCapture.replay(pcm.getValue(fixture), invocation)
                    producer = startedReplay
                    admitted = true
                    run.admitted++
                    withTimeout(180_000) {
                        // Join the entire producer (including half-duplex cancellation and native VAD close) FIRST.
                        startedReplay.awaitFinished()
                        val state = current.pipeline.state.first { !it.sessionActive || invocation.settled(it) }
                        if (!state.sessionActive) current.pipeline.stop().getOrThrow()
                        current.translation.awaitIdle()
                    }
                    drained = true
                    // Never validate a snapshot taken before the producer or its queued work completed.
                    val state = current.pipeline.state.value
                    val transcripts = state.transcripts.filter { it.id > previous }
                    val problems = validateInvocation(run, invocation, transcripts, state, startedReplay, config, fixture, observation, enabled)
                    row.put("newTranscripts", JSONArray(transcripts.map(::transcriptJson))).put("validationErrors", JSONArray(problems))
                    check(problems.isEmpty()) { problems.joinToString("; ") }
                    check(!current.pipeline.releaseFailed && controller.isCurrent(current))
                    run.completed++
                } catch (caught: Throwable) {
                    failure = caught
                    // A validation failure after a proven drain does not poison the next independent WAV.
                    if (!drained && owner != null) {
                        val current = requireNotNull(owner)
                        drained = run.release("wave_recovery_${invocation.id}") {
                            current.pipeline.stop().getOrThrow()
                            producer?.awaitFinished()
                            current.translation.awaitIdle()
                            check(!current.pipeline.releaseFailed)
                        }
                    }
                } finally {
                    if (drained) observation.invocation = null
                }
                if (failure != null) run.failures.incrementAndGet()
                val finalState = owner?.pipeline?.state?.value
                val finalTranscripts = if (admitted) finalState?.transcripts?.filter { it.id > previous }.orEmpty() else emptyList()
                run.emittedSegments += invocation.snapshot().size
                run.forwardedSegments += invocation.forwarded().size
                run.recognizedUtterances += invocation.snapshot().count { segment -> segment.calls("recognition").any { !it.isNull("original") } }
                run.completedUtterances += finalTranscripts.count { it.status == ConversationTranscriptStatus.COMPLETE }
                row.put("status", if (failure == null) "completed" else "failed").put("admitted", admitted).put("drained", drained)
                    .put("elapsedNsIncludingFixturePacingAndObservation", SystemClock.elapsedRealtimeNanos() - started)
                    .put("error", failure?.let(::error) ?: JSONObject.NULL).put("observedSegments", invocation.json())
                    .put("forwardedSegments", invocation.forwarded().size)
                    .put("newTranscriptsAfterBoundary", JSONArray(finalTranscripts.map(::transcriptJson)))
                    .put("outputAdmissions", observation.admissions()).put("pcmDelivery", producer?.json() ?: JSONObject.NULL)
                    .put("state", finalState?.let(::stateJson) ?: JSONObject.NULL).put("memoryAfter", run.memory())
                run.event(row)
            }
            owner?.let { current ->
                run.release("pipeline_stop_$mode") {
                    controller.use(current) { current.pipeline.stop().getOrThrow() }
                    check(!current.pipeline.state.value.sessionActive && !current.pipeline.state.value.captureActive && capture?.running != true)
                }
            }
        } finally {
            val current = owner
            if (current != null) run.release("owner_$mode") { controller.release(current) }
            else {
                run.release("partial_capture_$mode") { capture?.closeAndJoin() }
                run.release("partial_output_$mode") { output?.closeAndJoin() }
                run.release("partial_recognition_$mode") { recognition?.closeAndJoin() }
                run.release("partial_translation_$mode") { translation?.closeAndJoin() }
                run.release("partial_models_$mode") { models?.close() }
            }
            run.event(JSONObject().put("kind", "owner_final_observations").put("speechOutputEnabled", enabled)
                .put("waveInvocations", JSONArray(invocations.map { JSONObject().put("id", it.id).put("segments", it.json()) })))
            ownerScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private suspend fun startListening(controller: RuntimeOwnerController, owner: ConversationRuntimeOwner) {
        check(controller.use(owner) { owner.pipeline.start() } == true)
        val state = withTimeout(180_000) { owner.pipeline.state.first { it.captureActive || it.failure != null || !it.sessionActive } }
        check(state.sessionActive && state.captureActive && state.failure == null) { "Pipeline startup failed: ${state.failure}" }
    }

    private fun validateInvocation(
        run: Evidence, invocation: Invocation, transcripts: List<ConversationTranscript>, state: ConversationState, producer: Replay,
        config: ConversationSessionConfig, fixture: Fixture, observation: Observation, enabled: Boolean,
    ): List<String> {
        val problems = mutableListOf<String>()
        fun requireProof(condition: Boolean, message: String) { if (!condition) problems += message }
        val forwarded = invocation.forwarded()
        requireProof(forwarded.isNotEmpty(), "No source utterance was delivered to the real pipeline")
        requireProof(state.sessionActive && state.captureActive && state.queuedSegments == 0 && state.phase == ConversationPhase.LISTENING,
            "Pipeline did not return to idle listening after all delivered utterances")
        requireProof(state.failure == null && state.droppedSegments == 0, "Pipeline reported failure or dropped an utterance")
        requireProof(producer.failure == null, "PCM producer failed: ${producer.failure?.javaClass?.name}")
        requireProof(producer.sourceDelivered() > 0, "No source PCM was consumed by native VAD")
        if (!enabled) requireProof(producer.accepted.get() == producer.plannedSamples.toLong(), "Disabled capture did not consume the complete fixed replay")
        else if (producer.accepted.get() < producer.plannedSamples) {
            requireProof(producer.interruptReason == "half_duplex_capture_pause", "Partial PCM delivery lacks actual half-duplex pause evidence")
        }
        if (!enabled) requireProof(observation.noOutputAdmissions(), "Disabled output admitted readiness/preparation/synthesis")
        val observedIds = forwarded.mapNotNull { it.transcriptId }
        requireProof(observedIds.toSet().size == observedIds.size && transcripts.map { it.id }.toSet() == observedIds.toSet(),
            "Transcript/forwarded-segment coverage differs; no result may be dropped or reassigned")
        for (utterance in invocation.snapshot()) {
            val issues = mutableListOf<String>()
            fun expect(condition: Boolean, message: String) { if (!condition) issues += message }
            val recognized = utterance.calls("recognition")
            val translated = utterance.calls("translation")
            val spoken = utterance.calls("speech")
            val transcript = transcripts.singleOrNull { it.id == utterance.transcriptId }
            if (!utterance.forwarded) {
                expect(recognized.isEmpty() && translated.isEmpty() && spoken.isEmpty() && transcript == null,
                    "A segment rejected after capture pause entered the pipeline")
            } else {
                expect(transcript != null && transcript.status == ConversationTranscriptStatus.COMPLETE && transcript.failure == null,
                    "Delivered utterance did not complete")
                expect(recognized.size == 1 && translated.size == 1, "Delivered utterance must retain one actual recognition and translation call")
                val recognition = recognized.singleOrNull()
                val translation = translated.singleOrNull()
                val expected = config.pairFor(fixture.language)
                val sourceLanguage = recognition?.optString("source")
                val targetLanguage = recognition?.optString("target")
                expect(transcript?.direction == expected && sourceLanguage == expected.source.value &&
                    targetLanguage == expected.target.value, "Actual source-language judgment selected a different direction")
                val original = recognition?.text("original")
                expect(!original.isNullOrBlank() && transcript?.original == original && translation?.text("original") == original,
                    "Recognized source was lost or changed before translation/transcript")
                expect(translation?.optBoolean("publicCompleteReturn") == true && !transcript?.translation.isNullOrBlank() &&
                    transcript?.translation == translation?.text("output")?.trim(), "Final output differs from the complete actual Mozilla result")
                if (enabled) {
                    expect(spoken.size == 1, "Completed enabled utterance lacks exactly one actual speech admission")
                    for (speech in spoken) {
                        expect(translation?.optBoolean("publicCompleteReturn") == true && speech.text("text") == transcript?.translation &&
                            speech.optString("language") == expected.target.value, "Speech did not use only the successful final target text")
                        val starts = speech.optJSONArray("playbackStarts")
                        val start = if (starts != null && starts.length() == 1) starts.getJSONObject(0) else null
                        expect(speech.optBoolean("actualSpeakReturnedAfterDrain") && start != null &&
                            !start.isNull("presentationTimeNanos"), "Actual presentation/drain evidence is missing")
                    }
                } else {
                    expect(spoken.isEmpty(), "Disabled utterance admitted speech")
                    expect(transcript?.latencyMillis == null && transcript?.latencyMeasured == false, "Silent utterance invented playback latency")
                }
            }
            if (utterance.forwarded && issues.isEmpty()) run.validatedUtterances++
            problems += issues.map { "segment ${utterance.ordinal}/transcript ${utterance.transcriptId}: $it" }
            run.event(utterance.json().put("kind", "utterance_validation").put("transcript", transcript?.let(::transcriptJson) ?: JSONObject.NULL)
                .put("validationErrors", JSONArray(issues)).put("validated", utterance.forwarded && issues.isEmpty()))
        }
        return problems
    }

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun observedRecognition(run: Evidence, actual: WhisperConversationRecognitionEngine, observation: Observation) =
        object : ConversationRecognitionEngine by actual {
            override suspend fun prepare() {
                val row = JSONObject().put("kind", "recognition_prepare").put("memoryBefore", run.memory())
                val started = SystemClock.elapsedRealtimeNanos()
                var elapsed: Long? = null
                try {
                    actual.prepare()
                    elapsed = SystemClock.elapsedRealtimeNanos() - started
                    row.put("returned", true)
                } catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally { run.event(row.put("elapsedNs", elapsed ?: (SystemClock.elapsedRealtimeNanos() - started)).put("memoryAfter", run.memory())) }
            }
            override suspend fun recognize(segment: SpeechSegment): ConversationRecognitionResult {
                val utterance = observation.bindRecognition(segment)
                val row = utterance.event("recognition").put("samples", segment.sampleCount)
                    .put("speechEndNanos", segment.speechEndNanos ?: JSONObject.NULL).put("speechEndMeasured", segment.speechEndMeasured)
                    .put("memoryBefore", run.memory())
                val started = SystemClock.elapsedRealtimeNanos()
                var elapsed: Long? = null
                try {
                    val result = actual.recognize(segment)
                    elapsed = SystemClock.elapsedRealtimeNanos() - started
                    row.put("original", result.text).put("resultType", result.javaClass.simpleName)
                        .put("source", result.direction.source.value).put("target", result.direction.target.value)
                    return result
                } catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally {
                    row.put("elapsedNs", elapsed ?: (SystemClock.elapsedRealtimeNanos() - started)).put("memoryAfter", run.memory())
                    utterance.record("recognition", row)
                    run.event(row)
                }
            }
        }

    private fun observedTranslation(run: Evidence, actual: MozillaTranslationEngine, observation: Observation) =
        object : TranslationEngine by actual {
            override suspend fun prepare(): ModelAvailability {
                check(actual.preparation.value.phase == TranslationPreparationPhase.READY) { "Offline pipeline must use the already locally prepared owner" }
                return actual.prepare()
            }
            override suspend fun translate(pair: LanguagePair, text: String): String {
                val utterance = observation.currentUtterance()
                val row = utterance.event("translation").put("source", pair.source.value)
                    .put("target", pair.target.value).put("original", text).put("memoryBefore", run.memory())
                val started = SystemClock.elapsedRealtimeNanos()
                var elapsed: Long? = null
                try {
                    val result = actual.translate(pair, text)
                    elapsed = SystemClock.elapsedRealtimeNanos() - started
                    row.put("output", result).put("publicCompleteReturn", true).put("nativeEosObserved", JSONObject.NULL)
                    return result
                } catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally {
                    row.put("elapsedNs", elapsed ?: (SystemClock.elapsedRealtimeNanos() - started)).put("memoryAfter", run.memory())
                    utterance.record("translation", row)
                    run.event(row)
                }
            }
        }

    private fun observedOutput(run: Evidence, actual: SherpaConversationSpeechOutput, observation: Observation) =
        object : ConversationSpeechOutput by actual {
            override suspend fun readiness(): SpeechOutputReadiness {
                observation.readiness.incrementAndGet()
                val row = JSONObject().put("kind", "tts_readiness")
                try { return actual.readiness().also { row.put("readiness", it.name) } }
                catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally { run.event(row) }
            }
            override suspend fun prepare() {
                observation.preparations.incrementAndGet()
                val row = JSONObject().put("kind", "tts_prepared").put("backend", "piper").put("memoryBefore", run.memory())
                val started = SystemClock.elapsedRealtimeNanos()
                var elapsed: Long? = null
                try {
                    actual.prepare()
                    elapsed = SystemClock.elapsedRealtimeNanos() - started
                    row.put("returned", true)
                } catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally { run.event(row.put("elapsedNs", elapsed ?: (SystemClock.elapsedRealtimeNanos() - started)).put("memoryAfter", run.memory())) }
            }
            override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
                val utterance = observation.currentUtterance()
                observation.speeches.incrementAndGet()
                val starts: MutableList<SpeechPlaybackStart> = Collections.synchronizedList(mutableListOf())
                val row = utterance.event("speech").put("language", language.value).put("text", text).put("memoryBefore", run.memory())
                val started = SystemClock.elapsedRealtimeNanos()
                var elapsed: Long? = null
                try {
                    actual.speak(language, text) { start -> starts += start; onPlaybackStarted(start) }
                    elapsed = SystemClock.elapsedRealtimeNanos() - started
                    row.put("actualSpeakReturnedAfterDrain", true)
                } catch (failure: Throwable) { row.put("error", error(failure)); throw failure }
                finally {
                    row.put("elapsedNs", elapsed ?: (SystemClock.elapsedRealtimeNanos() - started))
                        .put("playbackStarts", synchronized(starts) { playbackJson(starts.toList()) }).put("memoryAfter", run.memory())
                    utterance.record("speech", row)
                    run.event(row)
                }
            }
        }

    private class Observation {
        @Volatile var invocation: Invocation? = null
        @Volatile var pipeline: ConversationPipeline? = null
        val readiness = AtomicInteger()
        val preparations = AtomicInteger()
        val speeches = AtomicInteger()
        fun noOutputAdmissions() = readiness.get() == 0 && preparations.get() == 0 && speeches.get() == 0
        fun admissions() = JSONObject().put("readiness", readiness.get()).put("prepare", preparations.get()).put("speak", speeches.get())
        fun bindRecognition(segment: SpeechSegment): Utterance {
            val utterance = requireNotNull(invocation).forSegment(segment)
            val row = requireNotNull(pipeline).state.value.transcripts.last()
            check(row.status == ConversationTranscriptStatus.UNDERSTANDING)
            utterance.bind(row.id)
            return utterance
        }
        fun currentUtterance(): Utterance {
            val row = requireNotNull(pipeline).state.value.transcripts.last()
            return requireNotNull(invocation).forTranscript(row.id)
        }
    }

    private class Invocation(val id: String) {
        private val segments = mutableListOf<Utterance>()
        private val byIdentity = IdentityHashMap<SpeechSegment, Utterance>()
        @Synchronized fun delivered(segment: SpeechSegment, start: Int, end: Int, forwarded: Boolean): Utterance {
            check(!byIdentity.containsKey(segment))
            return Utterance(id, segments.size + 1, segment.sampleCount, start, end, forwarded).also {
                segments += it
                byIdentity[segment] = it
            }
        }
        @Synchronized fun forSegment(segment: SpeechSegment) = requireNotNull(byIdentity[segment]) { "Unattributed PCM segment" }
        @Synchronized fun forTranscript(id: Long) = segments.single { it.transcriptId == id }
        @Synchronized fun snapshot() = segments.toList()
        fun forwarded() = snapshot().filter { it.forwarded }
        fun settled(state: ConversationState): Boolean = state.queuedSegments == 0 && state.phase == ConversationPhase.LISTENING &&
            state.captureActive && forwarded().all { segment ->
                segment.transcriptId?.let { id -> state.transcripts.any { it.id == id && it.status in terminal } } == true
            }
        fun json() = JSONArray(snapshot().map { it.json() })
    }

    private class Utterance(
        val invocationId: String, val ordinal: Int, val samples: Int, val nativeStartSample: Int, val nativeEndSample: Int,
        val forwarded: Boolean,
    ) {
        @Volatile var transcriptId: Long? = null
            private set
        private val records = mutableMapOf<String, MutableList<JSONObject>>()
        @Synchronized fun bind(id: Long) { check(transcriptId == null || transcriptId == id); transcriptId = id }
        @Synchronized fun record(kind: String, row: JSONObject) { records.getOrPut(kind) { mutableListOf() }.add(row) }
        @Synchronized fun calls(kind: String) = records[kind]?.toList().orEmpty()
        fun event(kind: String) = JSONObject().put("kind", kind).put("waveInvocationId", invocationId)
            .put("segmentOrdinal", ordinal).put("pipelineTranscriptId", transcriptId ?: JSONObject.NULL)
        fun json() = event("observed_segment").put("samples", samples).put("nativeStartSample", nativeStartSample)
            .put("nativeEndSample", nativeEndSample).put("forwardedToPipeline", forwarded)
            .put("recognitionCalls", JSONArray(calls("recognition"))).put("translationCalls", JSONArray(calls("translation")))
            .put("speechCalls", JSONArray(calls("speech")))
    }

    private class Replay(val invocation: Invocation, val sourceSamples: Int) {
        val plannedSamples = 8_000 + sourceSamples + 24_000
        val accepted = AtomicLong()
        val interrupted = AtomicBoolean()
        val finished = CompletableDeferred<Unit>()
        val pacing = ConversationProofFixtures.FramePacing((plannedSamples + 511) / 512)
        val interruptions: MutableList<JSONObject> = Collections.synchronizedList(mutableListOf())
        lateinit var job: Job
        @Volatile var startedNanos = 0L
        @Volatile var interruptReason: String? = null
        @Volatile var failure: Throwable? = null
        @Volatile var closeFailure: Throwable? = null
        suspend fun awaitFinished() { job.join(); finished.await(); closeFailure?.let { throw it } }
        fun sourceDelivered() = (accepted.get() - 8_000).coerceIn(0L, sourceSamples.toLong())
        fun json() = JSONObject().put("plannedPacedSamples", plannedSamples).put("acceptedPacedSamples", accepted.get())
            .put("acceptedSampleEvidence", "Native acceptedSampleCount after producer join; pacing frames count scheduled attempts, not pipeline admission")
            .put("sourceSamples", sourceSamples).put("sourceSamplesDelivered", sourceDelivered())
            .put("sourceSamplesNotDelivered", sourceSamples - sourceDelivered()).put("fullReplayConsumed", accepted.get() == plannedSamples.toLong())
            .put("interruptReason", interruptReason ?: JSONObject.NULL).put("producerFinished", finished.isCompleted)
            .put("error", failure?.let { JSONObject().put("type", it.javaClass.name).put("message", it.message ?: JSONObject.NULL) } ?: JSONObject.NULL)
            .put("captureInterruptions", synchronized(interruptions) { JSONArray(interruptions.toList()) }).put("pacing", pacing.toJson())
    }

    /** Same frozen replay/pacing as PacedCapture; adds native accepted-sample and per-segment evidence. */
    private inner class QualifiedCapture(
        private val run: Evidence, private val scope: CoroutineScope, private val store: SpeechModelStore,
        private val config: ConversationSessionConfig, private val observation: Observation,
    ) : SpeechCaptureEngine {
        @Volatile var running = false
            private set
        private var listener: SpeechCaptureListener? = null
        @Volatile private var replay: Replay? = null
        override suspend fun start(listener: SpeechCaptureListener) {
            check(!running && replay?.job?.isActive != true)
            this.listener = listener
            running = true
        }
        fun replay(pcm: ShortArray, invocation: Invocation): Replay {
            check(running && replay?.job?.isActive != true)
            val sink = requireNotNull(listener)
            val selected = Replay(invocation, pcm.size)
            selected.job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
                val frames = ShortArray(selected.plannedSamples)
                pcm.copyInto(frames, 8_000)
                var segmenter: SherpaSpeechSegmenter? = null
                try {
                    val vad = SherpaSpeechSegmenter.create(store, config.vadModelId, config.vadMinSilenceMs,
                        speechEndTime = { end -> (selected.startedNanos + end * 1_000_000_000L / SpeechSegment.SAMPLE_RATE_HZ) to false })
                    segmenter = vad
                    selected.startedNanos = System.nanoTime()
                    val started = selected.startedNanos
                    var offset = 0
                    while (offset < frames.size && running) {
                        ensureActive()
                        val count = minOf(512, frames.size - offset)
                        val deadline = started + (offset + count) * 1_000_000_000L / SpeechSegment.SAMPLE_RATE_HZ
                        val wait = deadline - System.nanoTime()
                        if (wait > 0) delay((wait + 999_999) / 1_000_000)
                        if (!running) break
                        selected.pacing.record(deadline - started, System.nanoTime() - started, catchUp = wait <= 0)
                        vad.accept(frames, offset, count) { segment ->
                            val state = requireNotNull(observation.pipeline).state.value
                            val forwarded = running && state.sessionActive && state.captureActive
                            val utterance = invocation.delivered(segment, vad.currentSegmentStartSample, vad.currentNativeSegmentEndSample, forwarded)
                            if (forwarded) sink.onSegment(segment)
                            run.event(utterance.event("vad_segment").put("samples", segment.sampleCount).put("forwardedToPipeline", forwarded)
                                .put("nativeStartSample", vad.currentSegmentStartSample).put("nativeEndSample", vad.currentNativeSegmentEndSample)
                                .put("nativeAcceptedSamplesAtEmission", vad.acceptedSampleCount).put("captureActiveAtEmission", state.captureActive))
                        }
                        selected.accepted.set(vad.acceptedSampleCount)
                        offset += count
                    }
                } catch (failure: Throwable) {
                    if (failure !is CancellationException || !selected.interrupted.get()) selected.failure = failure
                } finally {
                    segmenter?.let { vad ->
                        selected.accepted.set(vad.acceptedSampleCount)
                        try { vad.close() } catch (failure: Throwable) { selected.closeFailure = failure; selected.failure = failure }
                    }
                    selected.finished.complete(Unit)
                    run.event(JSONObject().put("kind", "pcm_producer_finished").put("waveInvocationId", invocation.id).put("delivery", selected.json()))
                }
            }
            selected.job.invokeOnCompletion { failure ->
                if (!selected.finished.isCompleted) { selected.failure = failure; selected.finished.complete(Unit) }
            }
            replay = selected
            selected.job.start()
            return selected
        }
        override fun interruptCurrent() {
            running = false
            val selected = replay ?: return
            if (!selected.finished.isCompleted) {
                val pipeline = requireNotNull(observation.pipeline)
                val state = pipeline.state.value
                val reason = if (state.sessionActive && !state.captureActive && pipeline.speechOutputEnabled.value &&
                    state.phase in setOf(ConversationPhase.TRANSLATING, ConversationPhase.SPEAKING)) "half_duplex_capture_pause" else "pipeline_interrupt"
                if (selected.interruptReason == null) selected.interruptReason = reason
                selected.interrupted.set(true)
                selected.interruptions += JSONObject().put("reason", reason).put("phase", state.phase.name)
                    .put("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos()).put("lastCompletedAcceptedSamplesAtInterrupt", selected.accepted.get())
                selected.job.cancel()
            }
        }
        override suspend fun stop(flush: Boolean): SpeechSegment? {
            interruptCurrent()
            replay?.awaitFinished()
            return null
        }
        override fun close() = interruptCurrent()
        override suspend fun closeAndJoin() { close(); withContext(NonCancellable) { replay?.awaitFinished() } }
    }

    private suspend fun execute(phase: String, expectedInvocations: Int, action: suspend (Evidence) -> Unit) {
        require(arguments.getString("phase") == phase) { "Method requires phase=$phase" }
        if (phase != "prepare-speech") require(arguments.getString("allowModelDownload") != "true") { "Offline execution forbids model downloads" }
        val runId = requireNotNull(arguments.getString("runId")) { "A fresh runId is required" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(runId)) { "Unsafe runId" }
        val root = File(context.filesDir, "mozilla-conversation-qualification")
        check(root.isDirectory || root.mkdirs())
        val directory = File(root, runId)
        check(directory.mkdir()) { "Evidence directory exists or cannot be reserved; never reuse runId" }
        val run = Evidence(directory)
        var summary: JSONObject
        try {
            run.event(JSONObject().put("kind", "run").put("phase", phase).put("runId", runId).put("expectedWaveInvocations", expectedInvocations)
                .put("driverRevision", 2).put("utteranceDenominator", "Observed real VAD deliveries and pipeline transcript IDs; never one utterance per WAV")
                .put("captureTiming", "Per-native-segment end mapped to replay schedule; estimated, not a measured physical speech-end clock")
                .put("pid", Process.myPid()).put("processStartElapsedRealtimeMs", Process.getStartElapsedRealtime())
                .put("physicalMicrophoneOrAcousticEvidence", false).put("recognitionTask", "production Whisper transcribe; no English-pivot recognition")
                .put("output", "Actual selected Piper voices and production AndroidSpeechPlayback; no fake synthesis or playback completion")
                .put("playbackEvidence", "Actual callback timestamp/measured flag and successful public drain return; raw PCM/head counters are private, not fabricated")
                .put("memoryEvidence", "Boundary PSS/RSS/native heap; not a true peak or isolated component attribution")
                .put("timingEvidence", "API elapsed intervals exclude boundary sampling; whole turn includes fixture pacing and observation, not an unperturbed latency benchmark")
                .put("exclusiveProcessRequired", true).put("settingsWritten", false).put("memory", run.memory()))
            try {
                if (phase != "prepare-speech") assertOffline()
                action(run)
                if (phase != "prepare-speech") assertOffline()
            } catch (failure: Throwable) {
                run.failures.incrementAndGet()
                run.event(JSONObject().put("kind", "fatal").put("error", error(failure)))
            }
            summary = JSONObject().put("kind", "summary").put("phase", phase).put("expectedWaveInvocations", expectedInvocations)
                .put("attemptedWaveInvocations", run.attempted).put("admittedWaveInvocations", run.admitted).put("completedWaveInvocations", run.completed)
                .put("missingWaveInvocations", expectedInvocations - run.attempted).put("ownersCreated", run.ownersCreated)
                .put("vadEmittedSegments", run.emittedSegments).put("forwardedSegments", run.forwardedSegments)
                .put("recognizedUtterances", run.recognizedUtterances).put("completedUtterances", run.completedUtterances)
                .put("validatedUtterances", run.validatedUtterances)
                .put("failureEvents", run.failures.get()).put("passed", run.failures.get() == 0 && run.attempted == expectedInvocations &&
                    run.completed == expectedInvocations && (expectedInvocations == 0 || (run.admitted == expectedInvocations && run.ownersCreated == 2)))
            run.event(summary)
        } finally { run.close() }
        println("MOZILLA_CONVERSATION_QUALIFICATION ${directory.name}/raw.jsonl $summary")
        assertTrue("Retain failed and missing rows: $summary", summary.getBoolean("passed"))
    }

    private inner class Evidence(directory: File) : AutoCloseable {
        private val file = File(directory, "raw.jsonl").also { check(it.createNewFile()) }
        private val stream = FileOutputStream(file, true)
        private val writer = stream.bufferedWriter(Charsets.UTF_8)
        private var sequence = 0
        val failures = AtomicInteger()
        var attempted = 0
        var admitted = 0
        var completed = 0
        var ownersCreated = 0
        var emittedSegments = 0
        var forwardedSegments = 0
        var recognizedUtterances = 0
        var completedUtterances = 0
        var validatedUtterances = 0
        @Volatile var releaseFailure: Throwable? = null
        @Synchronized fun event(row: JSONObject) {
            row.put("sequence", sequence++).put("recordedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
            writer.append(row.toString()).append('\n')
            writer.flush()
            stream.fd.sync()
        }
        fun memory(): JSONObject = try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val rss = File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong()
            }
            JSONObject().put("sampleElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos()).put("pssKb", info.totalPss)
                .put("rssKb", rss).put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        } catch (failure: Throwable) {
            failures.incrementAndGet()
            JSONObject().put("error", error(failure))
        }
        suspend fun release(label: String, action: suspend () -> Unit): Boolean = withContext(NonCancellable) {
            val row = JSONObject().put("kind", "release").put("resource", label).put("memoryBefore", memory())
            val started = SystemClock.elapsedRealtimeNanos()
            var success = true
            try { action(); row.put("status", "closed_or_stopped") } catch (failure: Throwable) {
                success = false
                failures.incrementAndGet()
                if (releaseFailure == null) releaseFailure = failure
                row.put("status", "failed").put("error", error(failure))
            }
            val elapsed = SystemClock.elapsedRealtimeNanos() - started
            event(row.put("elapsedNs", elapsed).put("memoryAfter", memory()))
            success
        }
        @Synchronized override fun close() { writer.close() }
    }

    private data class Fixture(val id: String, val asset: String, val sourcePath: String, val language: LanguageId, val bytes: Int, val sha256: String)
    private fun fixtureJson(fixture: Fixture) = JSONObject().put("sourceCaseId", fixture.id).put("asset", fixture.asset)
        .put("sourcePath", fixture.sourcePath).put("sourceLanguage", fixture.language.value).put("bytes", fixture.bytes).put("sha256", fixture.sha256)

    private fun loadPcm(run: Evidence, fixture: Fixture): ShortArray {
        val bytes = instrumentation.context.assets.open(fixture.asset).use { it.readBytes() }
        check(bytes.size == fixture.bytes && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == fixture.sha256) {
            "Frozen official WAV mismatch: ${fixture.asset}"
        }
        val file = File.createTempFile("mozilla-conversation-", ".wav", context.cacheDir)
        return try {
            file.writeBytes(bytes)
            ConversationProofFixtures.readPcm(file).also { pcm ->
                run.event(fixtureJson(fixture).put("kind", "source_fixture").put("normalizedSampleRateHz", SpeechSegment.SAMPLE_RATE_HZ)
                    .put("normalizedSamples", pcm.size).put("inputTransform", "Existing readPcm helper: duplicate 8kHz samples if needed; same fixed 8000 lead/24000 tail silence, 512-sample pacing and real VAD; exact native accepted PCM and half-duplex interruption recorded"))
            }
        } finally { check(file.delete()) { "Temporary WAV could not be removed" } }
    }

    private fun assertOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        check(connectivity.activeNetwork == null && connectivity.allNetworks.isEmpty()) { "Disconnect every network before offline execution" }
    }

    private fun configuration() = ConversationSessionConfig(
        languages = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), engineId = "mozilla",
        speechRecognitionBackend = SpeechRecognitionBackend.WHISPER, vadModelId = "vad-silero-6.2.2",
        vadMinSilenceMs = 700, ttsSpeedPercent = 100, speechOutputBackend = SpeechOutputBackend.PIPER,
    )

    private fun resourceDescription(config: ConversationSessionConfig, store: SpeechModelStore): JSONObject {
        val ids = setOf(config.vadModelId, WhisperConversationRecognitionEngine.MODEL_ID) + config.ttsModelIds.values
        return JSONObject().put("translationProvider", config.engineId).put("languages", JSONArray(config.languages.map { it.value }))
            .put("recognitionBackend", config.speechRecognitionBackend.persistedId).put("speechOutputBackend", config.speechOutputBackend.persistedId)
            .put("selectedModelIds", JSONArray(ids.toList())).put("selectedPackageIds", JSONArray(store.catalog.requiredPackages(ids).toList()))
            .put("vadMinSilenceMs", config.vadMinSilenceMs).put("ttsSpeedPercent", config.ttsSpeedPercent)
    }

    private fun packageStates(store: SpeechModelStore) = JSONArray(store.packages.value.map { status ->
        JSONObject().put("id", status.id).put("state", status.state.name).put("bytes", status.sizeBytes).put("source", status.source)
    })
    private fun error(failure: Throwable) = JSONObject().put("type", failure.javaClass.name).put("message", failure.message ?: JSONObject.NULL)
        .put("causeType", failure.cause?.javaClass?.name ?: JSONObject.NULL).put("causeMessage", failure.cause?.message ?: JSONObject.NULL)
    private fun transcriptJson(row: ConversationTranscript) = JSONObject().put("id", row.id).put("original", row.original)
        .put("source", row.direction?.source?.value ?: JSONObject.NULL).put("target", row.direction?.target?.value ?: JSONObject.NULL)
        .put("finalOutput", row.translation ?: JSONObject.NULL).put("status", row.status.name).put("failure", row.failure?.name ?: JSONObject.NULL)
        .put("translationFailure", row.translationFailure?.let(::error) ?: JSONObject.NULL).put("latencyMillis", row.latencyMillis ?: JSONObject.NULL)
        .put("latencyMeasured", row.latencyMeasured)
    private fun stateJson(state: ConversationState) = JSONObject().put("phase", state.phase.name).put("sessionActive", state.sessionActive)
        .put("captureActive", state.captureActive).put("queuedSegments", state.queuedSegments).put("droppedSegments", state.droppedSegments)
        .put("failure", state.failure?.name ?: JSONObject.NULL).put("translationFailure", state.translationFailure?.let(::error) ?: JSONObject.NULL)
        .put("transcripts", JSONArray(state.transcripts.map(::transcriptJson)))

    companion object {
        private val terminal = setOf(ConversationTranscriptStatus.COMPLETE, ConversationTranscriptStatus.ERROR, ConversationTranscriptStatus.INTERRUPTED)
        private val fixtures = listOf(
            Fixture("ru-official-0", "speech-ru-fixture.wav", "test-data/corpus/v1/smoke/ru-official-0.wav", LanguageCatalog.RUSSIAN,
                101804, "980b4327e684725859e437f05d15dfa4ec8afb994ef2e8fb356365af76547c41"),
            Fixture("en-official-0", "speech-en-fixture.wav", "test-data/corpus/v1/smoke/en-official-0.wav", LanguageCatalog.ENGLISH,
                212044, "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"),
        )
        private fun playbackJson(starts: List<SpeechPlaybackStart>) = JSONArray(starts.map { start ->
            JSONObject().put("presentationTimeNanos", start.presentationTimeNanos ?: JSONObject.NULL).put("measured", start.measured)
        })
    }
}
