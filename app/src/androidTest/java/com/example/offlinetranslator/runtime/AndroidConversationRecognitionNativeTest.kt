package com.example.offlinetranslator.runtime

import android.media.AudioFormat
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitPreparationCoordinator
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real on-device adapter + production VAD/ML Kit/local output. No microphone or natural14 claim. */
class AndroidConversationRecognitionNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val english = LanguageCatalog.ENGLISH
    private val russian = LanguageCatalog.RUSSIAN
    private val inventoryPairs = LanguageCatalog.entries.filter { it.id != english }
        .map { LanguagePair(it.id, english) } + LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)

    /** Explicit network-capable action. Scheduled is not ready; rerun only after a user-visible recheck. */
    @Test fun prepareSelectedLanguages() = runBlocking<Unit> {
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            for (pair in inventoryPairs) {
                commitNativeSettings(runtime, selected(pair))
                val owner = runtime.createConversationOwner(runtime.settings.current().conversationConfig())
                try {
                    val prepared = withTimeout(1_200_000) { runtime.prepareConversation(owner) }
                    // Installation's terminal state belongs to this action; recheck is a separate action.
                    val state = owner.recognition.readinessState.value
                    logReadiness(pair, state, owner.recognition as AndroidConversationRecognitionEngine)
                    assertEquals("BLOCKED: public selected preparation for ${pair.id}; scheduled requires explicit later re-entry/recheck, missing TTS requires actual engine installer", SpeechRecognitionReadiness.READY, state)
                    assertEquals("BLOCKED: install selected local voices via actual engine UI for ${pair.id}", SpeechOutputReadiness.READY, owner.output.readiness())
                    assertTrue("Selected VAD/MT/output must be prepared for ${pair.id}", prepared)
                } finally { runtime.release(owner) }
            }
        } finally {
            try { commitNativeSettings(runtime, original) } finally { runtime.closeAndJoin() }
        }
    }

    @Test fun preparedOfflineProductionConversation() = runBlocking<Unit> {
        preparedHumanConversation(null)
    }

    @Test fun preparedOfflineExplicitGoogleProductionConversation() = runBlocking<Unit> {
        preparedHumanConversation(AndroidRecognitionServices.GOOGLE_COMPONENT)
    }

    private suspend fun preparedHumanConversation(service: String?) {
        assertOffline()
        // Two same-language orders and two alternating orders, each under BOTH initial directions.
        val human = ConversationProofFixtures.SHORT_HUMAN_SOURCES
        val orders = listOf(listOf(0, 1, 2, 3), listOf(2, 3, 0, 1), listOf(0, 2, 1, 3), listOf(2, 0, 3, 1))
        val failures = mutableListOf<String>()
        var attempted = 0
        var completed = 0
        var failedRows = 0
        try {
            for (pair in listOf(LanguagePair(russian, english), LanguagePair(english, russian))) {
                withProductionConversation(pair, service) { proof ->
                    for ((orderIndex, order) in orders.withIndex()) for (index in order) {
                        val source = human[index]
                        val rowId = "human:${pair.id}/order=$orderIndex/source=$index"
                        attempted++
                        val file = File.createTempFile("android-human-", ".wav", context.cacheDir)
                        val pcm = try {
                            instrumentation.context.assets.open(source.asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                            assertEquals(source.bytes, file.length())
                            assertEquals(source.sha256, ConversationProofFixtures.sha256(file))
                            ConversationProofFixtures.readPcm(file).also { assertEquals(source.samples, it.size) }
                        } finally { file.delete() }
                        val row = Bundle().apply {
                            putString("row_id", rowId)
                            putString("fixture_asset", source.asset)
                            putString("fixture_sha256", source.sha256)
                            putLong("fixture_bytes", source.bytes)
                            putInt("fixture_samples", source.samples)
                            putInt("order_index", orderIndex)
                            putString("order", order.joinToString(","))
                            putInt("source_index", index)
                        }
                        val rowFailures = proof.turn(pcm, source.language, source.reference, "human",
                            retainAssertions = true, rowMetadata = row) { translated ->
                            when (index) {
                                0 -> {
                                    assertMeaning(translated, listOf("\\bstop\\b", "\\btimer\\b"))
                                    assertFalse("Translation must preserve positive polarity: $translated",
                                        ConversationProofFixtures.hasNegation(translated))
                                }
                                1 -> assertMovementToHere(translated)
                                2 -> assertTurnRight(translated)
                                else -> {
                                    assertMeaning(translated, listOf("(?:проход|ряд)", "(?:семь|седьм|7)"))
                                    assertFalse("Translation must preserve positive polarity: $translated",
                                        ConversationProofFixtures.hasNegation(translated))
                                }
                            }
                        }
                        completed++
                        if (rowFailures.isNotEmpty()) failedRows++
                        failures += rowFailures.map { "$rowId: $it" }
                    }
                }
            }
        } finally { reportTotals("human", 32, attempted, completed, failedRows, failures.size) }
        assertOffline()
        assertEquals(32, completed)
        assertTrue("All original human32 rows must qualify; failures=${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test fun preparedOfflineSyntheticProductionConversation() = runBlocking<Unit> {
        assertOffline()
        val seen = mutableSetOf<LanguageId>()
        val failures = mutableListOf<String>()
        val expected = inventoryPairs.size * 2
        var attempted = 0
        var completed = 0
        var failedRows = 0
        // Synthetic reference audio comes from an ACTUAL installed LOCAL voice for each selected language.
        try {
            for ((pairIndex, pair) in inventoryPairs.withIndex()) withProductionConversation(pair) { proof ->
                for ((directionIndex, language) in listOf(pair.source, pair.target).withIndex()) {
                    val source = SYNTHETIC.getValue(language.value)
                    val rowId = "synthetic:${pair.id}/direction=$directionIndex"
                    attempted++
                    val pcm = proof.output.fixturePcm(language, source)
                    val row = Bundle().apply {
                        putString("row_id", rowId)
                        putString("fixture_source", "actual-installed-local-TTS")
                        putInt("fixture_pcm_samples", pcm.size)
                        putInt("pair_index", pairIndex)
                        putInt("direction_index", directionIndex)
                    }
                    val rowFailures = proof.turn(pcm, language, source, "synthetic-local-TTS:functional-only",
                        retainAssertions = true, rowMetadata = row) { translated ->
                        if (pair.source == LanguageCatalog.FRENCH && pair.target == LanguageCatalog.SPANISH) {
                            assertMeaning(translated, if (language == LanguageCatalog.FRENCH)
                                listOf("\\bdos\\b", "(?:billet|boleto|entrada)") else listOf("\\bdeux\\b", "(?:billet|ticket)"))
                        } else if (language == english && pair.source == LanguageCatalog.CHINESE) {
                            assertTrue("Chinese output must retain simplified two-ticket meaning: $translated", translated.contains("两") && translated.contains("票"))
                            assertFalse("Traditional Chinese cannot stand in for Simplified", translated.contains("兩") || translated.contains("張"))
                        } else if (language != english) {
                            assertMeaning(translated, listOf("\\b(?:two|2)\\b", "\\btickets?\\b"))
                        } else {
                            // Actual local target output is checked below; semantic240 qualification is not claimed.
                            assertTrue("Translation must not be copied source text", ConversationProofFixtures.normalizedText(source) != ConversationProofFixtures.normalizedText(translated))
                        }
                    }
                    completed++
                    if (rowFailures.isNotEmpty()) failedRows++
                    failures += rowFailures.map { "$rowId: $it" }
                    seen += language
                }
            }
        } finally { reportTotals("synthetic-local-TTS:functional-only", expected, attempted, completed, failedRows, failures.size) }
        assertEquals("Real installed exact-pair recognition and local output catalog coverage", LanguageCatalog.entries.map { it.id }.toSet(), seen)
        assertOffline()
        assertEquals(expected, completed)
        assertTrue("Synthetic proof retained failures: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    private fun selected(pair: LanguagePair, service: String? = null) = AppSettings(languagePair = pair,
        speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
        speechRecognitionService = service,
        speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)

    private suspend fun withProductionConversation(pair: LanguagePair, service: String? = null, block: suspend (Proof) -> Unit) {
        val config = selected(pair, service).conversationConfig()
        val store = SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
        val recognition = AndroidConversationRecognitionEngine(context, config)
        val translation = MlKitTranslationEngine(context, config.pairs, MlKitPreparationCoordinator(context))
        val models = SelectedSpeechResources.forConversation(store, config, emptySet())
        val output = ObservedOutput(config)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val capture = SegmentCapture()
        val owner = ConversationRuntimeOwner(config, models, capture, recognition, translation, output.delegate, scope)
        val pipeline = owner.pipeline
        try {
            assertOffline()
            val state = recognition.readiness()
            logReadiness(pair, state, recognition)
            assertEquals("BLOCKED: exact selected request must have BOTH local models installed for ${pair.id}", SpeechRecognitionReadiness.READY, state)
            assertTrue(config.asrModelIds.isEmpty())
            assertTrue(config.ttsModelIds.isEmpty())
            val resourcesReady = owner.models.isConversationReady()
            assertTrue("BLOCKED: selected native VAD and the owner's exact recognizer must be ready", resourcesReady)
            val mtReady = translation.modelAvailability()
            assertEquals("BLOCKED: prepare selected MT before offline proof", ModelAvailability.READY, mtReady)
            val voiceReady = output.delegate.readiness()
            assertEquals("BLOCKED: actual installed/nonnetwork voices required", SpeechOutputReadiness.READY, voiceReady)
            assertTrue(pipeline.start())
            val startState = withTimeout(30_000) { pipeline.state.first { it.phase == ConversationPhase.LISTENING || it.phase == ConversationPhase.ERROR } }
            assertEquals("Startup failure=${startState.failure}; recognition=${recognition.readinessState.value}; code=${recognition.errorCode}", ConversationPhase.LISTENING, startState.phase)
            block(Proof(store, config, pipeline, capture, output, recognition))
        } finally {
            withContext(NonCancellable) {
                try { owner.closeAndJoin().getOrThrow() } finally { store.close(); scope.cancel() }
            }
        }
    }

    private inner class Proof(val store: SpeechModelStore, val config: ConversationSessionConfig,
        val pipeline: ConversationPipeline, val capture: SegmentCapture, val output: ObservedOutput,
        val recognition: AndroidConversationRecognitionEngine) {
        suspend fun turn(pcm: ShortArray, language: LanguageId, reference: String, cohort: String,
            retainAssertions: Boolean = false, rowMetadata: Bundle = Bundle(),
            translationOracle: (String) -> Unit): List<String> {
            val failures = mutableListOf<String>()
            rowMetadata.putString("proof_kind", "conversation_row")
            rowMetadata.putString("cohort", cohort)
            rowMetadata.putString("initial", config.languages.first().value)
            rowMetadata.putString("recognition_service", config.speechRecognitionService ?: "designated-on-device")
            rowMetadata.putString("source_expected", language.value)
            rowMetadata.putString("source_reference", reference)
            rowMetadata.putString("row_outcome", "FATAL")
            for (stage in listOf("readiness", "source", "reference", "actualOutput", "mtSemantic"))
                rowMetadata.putString(stage, "NOT_REACHED")
            fun checkpoint(stage: String, assertion: () -> Unit) {
                try {
                    assertion()
                    rowMetadata.putString(stage, "PASS")
                } catch (failure: AssertionError) {
                    rowMetadata.putString(stage, "FAIL")
                    if (!retainAssertions) throw failure
                    failures += "$stage: ${failure.message}".take(512)
                }
            }
            try {
                check(pipeline.state.value.sessionActive && pipeline.state.value.captureActive &&
                    pipeline.state.value.phase == ConversationPhase.LISTENING) {
                    "A subsequent proof row requires an active idle listening session"
                }
                rowMetadata.putString("readiness", "PASS")
                val segment = ConversationProofFixtures.productionSegments(store, config, pcm).single()
                val previous = pipeline.state.value.transcripts.lastOrNull()?.id ?: 0L
                output.evidence.reset()
                capture.emit(segment)
                val state = withTimeout(90_000) { pipeline.state.first { state ->
                    state.transcripts.any { it.id > previous && it.status in setOf(ConversationTranscriptStatus.COMPLETE, ConversationTranscriptStatus.ERROR) }
                } }
                val turn = state.transcripts.last { it.id > previous }
                rowMetadata.putString("pipeline_status", turn.status.name)
                rowMetadata.putString("pipeline_phase", state.phase.name)
                rowMetadata.putString("pipeline_failure", turn.failure?.name ?: "none")
                rowMetadata.putString("source_actual", turn.direction?.source?.value ?: "none")
                rowMetadata.putString("asr_actual", turn.original.take(4096))
                rowMetadata.putString("mt_actual", turn.translation?.take(4096) ?: "")
                checkpoint("source") {
                    assertEquals("A failed baseline is FAIL, not replacement gold ($cohort); failure=${turn.failure}; phase=${state.phase}", ConversationTranscriptStatus.COMPLETE, turn.status)
                    assertNull(turn.failure)
                    assertEquals(config.pairFor(language), turn.direction)
                }
                // Reference reaches ONLY this post-routing oracle, never the recognizer or resolver.
                checkpoint("reference") {
                    assertEquals(cohort, ConversationProofFixtures.semanticTokens(reference), ConversationProofFixtures.semanticTokens(turn.original))
                }
                checkpoint("actualOutput") {
                    assertEquals("Completed output requires the genuine completed pipeline turn", ConversationTranscriptStatus.COMPLETE, turn.status)
                    output.assertCompleted(config.pairFor(language).target, rowMetadata)
                }
                val idle = withTimeout(10_000) { pipeline.state.first {
                    it.phase in setOf(ConversationPhase.LISTENING, ConversationPhase.ERROR) || !it.sessionActive
                } }
                check(idle.phase == ConversationPhase.LISTENING && idle.sessionActive && idle.captureActive) {
                    "Proof continuation requires an active idle listening session"
                }
                checkpoint("mtSemantic") {
                    assertNotNull("Genuine translation is required", turn.translation)
                    translationOracle(requireNotNull(turn.translation))
                }
                rowMetadata.putString("row_outcome", if (failures.isEmpty()) "PASS" else "FAIL")
                return failures
            } finally {
                rowMetadata.putString("sdk_error", recognition.errorCode?.toString() ?: "none")
                rowMetadata.putString("assertion_failures", failures.joinToString("; "))
                instrumentation.sendStatus(2, rowMetadata)
            }
        }
    }


    /** Only replaces microphone delivery, AFTER genuine production VAD; no recognition/output doubles. */
    private class SegmentCapture : SpeechCaptureEngine {
        private var listener: SpeechCaptureListener? = null
        override suspend fun start(listener: SpeechCaptureListener) { this.listener = listener }
        fun emit(segment: SpeechSegment) = requireNotNull(listener).onSegment(segment)
        override fun interruptCurrent() { listener = null }
        override suspend fun stop(flush: Boolean): SpeechSegment? { interruptCurrent(); return null }
        override fun close() = interruptCurrent()
    }

    private inner class ObservedOutput(config: ConversationSessionConfig) {
        val evidence = Synthesis()
        private lateinit var client: TtsClient
        private val thread = AndroidTtsThread()
        val delegate = SystemConversationSpeechOutput(config.languages, config.ttsSpeedPercent,
            { initialized -> AndroidTtsClient(context, initialized, evidence).also { client = it } },
            AndroidTtsAudioFocus(context), thread, Dispatchers.Main.immediate)

        fun assertCompleted(language: LanguageId, row: Bundle? = null) {
            assertNotNull(delegate.enginePackage)
            val voice = thread.call { requireNotNull(client.currentVoice) }
            assertFalse(voice.networkRequired)
            assertFalse(voice.notInstalled)
            val expected = AndroidSpeechLocales.output(language)
            if (language == LanguageCatalog.CHINESE) {
                assertTrue(voice.locale.language in setOf("zh", "cmn"))
                assertEquals("CN", voice.locale.country)
                assertTrue(voice.locale.script.isEmpty() || voice.locale.script == "Hans")
            } else assertEquals(expected.language, voice.locale.language)
            val pcm = evidence.pcm16()
            val peak = pcm.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("Non-silent PCM is required, not phase-only callback", peak > 0)
            row?.apply {
                putString("output_engine_package", delegate.enginePackage)
                putInt("output_engine_uid", context.packageManager.getApplicationInfo(requireNotNull(delegate.enginePackage), 0).uid)
                putString("output_language", language.value)
                putString("output_voice_name", voice.name)
                putString("output_voice_locale", voice.locale.toLanguageTag())
                putBoolean("output_network_required", voice.networkRequired)
                putBoolean("output_not_installed", voice.notInstalled)
                putInt("output_pcm16k_peak", peak)
                putInt("output_pcm16k_samples", pcm.size)
                putBoolean("output_done", true)
            }
            Log.i(TAG, "output=${language.value} actualEngine=${delegate.enginePackage} actualVoice=${voice.name} actualLocale=${voice.locale.toLanguageTag()} installed=true network=false completed=true")
        }

        suspend fun fixturePcm(language: LanguageId, reference: String): ShortArray {
            evidence.reset()
            val starts = mutableListOf<SpeechPlaybackStart>()
            withTimeout(60_000) { delegate.speak(language, reference, starts::add) }
            assertEquals(listOf(SpeechPlaybackStart(null, false)), starts)
            assertCompleted(language)
            return evidence.pcm16()
        }
    }

    /** Existing observer on real production AndroidTtsClient; bounded fixed-fixture memory only. */
    private class Synthesis : TtsSynthesisObserver {
        private val bytes = ByteArrayOutputStream()
        private var rate = 0
        private var format = 0
        private var channels = 0
        @Synchronized fun reset() { bytes.reset(); rate = 0; format = 0; channels = 0 }
        @Synchronized override fun onBeginSynthesis(id: String, sampleRate: Int, audioFormat: Int, channels: Int) {
            rate = sampleRate; format = audioFormat; this.channels = channels
        }
        @Synchronized override fun onAudioAvailable(id: String, audio: ByteArray) {
            check(bytes.size() + audio.size <= 2_000_000) { "Fixed-fixture synthesis exceeds bound" }
            bytes.write(audio)
        }
        @Synchronized fun pcm16(): ShortArray {
            assertEquals(AudioFormat.ENCODING_PCM_16BIT, format)
            assertEquals(1, channels)
            assertTrue(rate in 8000..48000)
            val raw = bytes.toByteArray()
            assertTrue(raw.isNotEmpty() && raw.size % 2 == 0)
            val input = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val source = ShortArray(input.remaining()).also { input.get(it) }
            // Explicit synthetic-fixture sample-rate conversion only; no silence/pacing or text hints.
            return ShortArray((source.size.toLong() * 16000 / rate).toInt()) { index ->
                val position = index.toLong() * rate
                val start = (position / 16000).toInt().coerceAtMost(source.lastIndex)
                val end = minOf(start + 1, source.lastIndex)
                val fraction = (position % 16000).toInt()
                ((source[start].toInt() * (16000 - fraction) + source[end].toInt() * fraction) / 16000).toShort()
            }
        }
    }

    private fun logReadiness(pair: LanguagePair, state: SpeechRecognitionReadiness,
        engine: AndroidConversationRecognitionEngine) {
        val inventory = engine.supportInventory
        Log.i(TAG, "exactSelectedRequest=${pair.id} locales=${AndroidSpeechLocales.recognition(pair.source)},${AndroidSpeechLocales.recognition(pair.target)} readiness=$state installed=${inventory?.installed} pending=${inventory?.pending} supported=${inventory?.supported} online=${inventory?.online} error=${engine.errorCode} osBytes=unknown")
    }
    private fun assertMeaning(text: String, patterns: List<String>) {
        for (pattern in patterns) assertTrue("Lost fixed-fixture meaning ($pattern): $text", Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
    private fun assertMovementToHere(text: String) {
        val patterns = listOf("\\b(?:come|go|move|walk|step)\\b", "\\bhere\\b")
        assertMeaning(text, patterns)
        assertFalse("Translation must preserve positive polarity: $text",
            ConversationProofFixtures.hasNegation(text))
    }
    private fun assertTurnRight(text: String) {
        val normalized = ConversationProofFixtures.normalizedText(text)
        assertMeaning(normalized, listOf("(?:поверн|поворач)", "\\b(?:(?:на|в)\\s*право|в\\s+правую\\s+сторону)\\b"))
        assertFalse("Translation must preserve positive polarity: $text",
            ConversationProofFixtures.hasNegation(text))
    }
    private fun reportTotals(cohort: String, expected: Int, attempted: Int, completed: Int, failedRows: Int, assertions: Int) {
        instrumentation.sendStatus(2, Bundle().apply {
            putString("proof_kind", "conversation_totals")
            putString("cohort", cohort)
            putInt("rows_expected", expected)
            putInt("rows_attempted", attempted)
            putInt("rows_completed", completed)
            putInt("rows_failed", failedRows)
            putInt("rows_fatal", attempted - completed)
            putInt("rows_unreached", expected - attempted)
            putInt("assertions_failed", assertions)
        })
    }
    private fun assertOffline() {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("Radio flags alone are not offline proof", connectivity.activeNetwork)
        assertTrue("No current networks may remain", connectivity.allNetworks.isEmpty())
    }

    private companion object {
        const val TAG = "AndroidConversationProof"
        val SYNTHETIC = mapOf(
            "en" to "I need two tickets.", "ru" to "Мне нужны два билета.",
            "vi" to "Tôi cần hai vé.", "es" to "Necesito dos billetes.",
            "id" to "Saya membutuhkan dua tiket.", "it" to "Ho bisogno di due biglietti.",
            "zh" to "我需要两张票。", "ko" to "표 두 장이 필요합니다.",
            "de" to "Ich brauche zwei Fahrkarten.", "pl" to "Potrzebuję dwóch biletów.",
            "pt" to "Preciso de dois bilhetes.", "th" to "ฉันต้องการตั๋วสองใบ",
            "tr" to "İki bilete ihtiyacım var.", "fr" to "J'ai besoin de deux billets.",
            "hi" to "मुझे दो टिकट चाहिए।", "ja" to "切符が二枚必要です。",
            "nl" to "Ik heb twee kaartjes nodig.",
        )
    }
}
