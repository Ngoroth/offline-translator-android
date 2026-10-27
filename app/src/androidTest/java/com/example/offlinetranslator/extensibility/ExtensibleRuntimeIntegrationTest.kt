package com.example.offlinetranslator.extensibility

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.AppRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ExtensibleRuntimeIntegrationTest {

    private fun requireOptIn() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Opt in via runExtensibilityIntegration argument",
            args.getString("runExtensibilityIntegration") == "true",
        )
    }

    private val addedTextReverseInputs = linkedMapOf(
        LanguageCatalog.FRENCH to "Le train ne part pas à 15 heures. Il part à 16 heures et coûte 25 euros.",
        LanguageCatalog.SPANISH to "El tren no sale a las 15 horas. Sale a las 16 horas y cuesta 25 euros.",
        LanguageCatalog.ITALIAN to "Il treno non parte alle 15. Parte alle 16 e costa 25 euro.",
        LanguageCatalog.PORTUGUESE to "O comboio não parte às 15 horas. Parte às 16 horas e custa 25 euros.",
    )

    @Test
    fun prepareAddedTextModels() = runBlocking {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            for (language in addedTextReverseInputs.keys) {
                val selected = original.copy(languagePair = LanguagePair(LanguageCatalog.ENGLISH, language), textEngineId = "mlkit")
                selectPair(runtime, selected.languagePair)
                assertTrue("Apply prepared text provider", runtime.applySettings(selected))
                val owner = runtime.createTextOwner(selected.textConfig())
                try {
                    assertEquals(
                        "Prepare selected text pair ${selected.languagePair.id}",
                        ModelAvailability.READY,
                        requireNotNull(runtime.useText(owner) { it.prepare() }),
                    )
                } finally {
                    runtime.release(owner)
                }
            }
        } finally {
            restoreSettings(runtime, original)
            assertEquals("Original settings must be restored exactly", original, runtime.settings.current())
        }
    }

    @Test
    fun addedTextLanguagesOffline() = runBlocking {
        requireOptIn()
        val runId = requireNotNull(InstrumentationRegistry.getArguments().getString("runId")) {
            "Pass a nonblank runId instrumentation argument for offline semantic evidence"
        }
        require(runId.isNotBlank()) { "runId must not be blank" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        try {
            assertEquals(
                "Airplane mode must be enabled for offline verification",
                1,
                android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0),
            )
            assertEquals(
                "Wi-Fi must be disabled for offline verification",
                0,
                android.provider.Settings.Global.getInt(context.contentResolver, "wifi_on", 1),
            )
            val forwardInput = "The train does not leave at 3 pm. It leaves at 4 pm and costs 25 euros."
            val outputs = mutableListOf<Pair<LanguagePair, String>>()
            for ((language, reverseInput) in addedTextReverseInputs) {
                val forward = LanguagePair(LanguageCatalog.ENGLISH, language)
                for ((pair, input) in listOf(forward to forwardInput, forward.reverse() to reverseInput)) {
                    val selected = original.copy(languagePair = pair, textEngineId = "mlkit")
                    selectPair(runtime, pair)
                    assertTrue("Apply prepared text provider", runtime.applySettings(selected))
                    val owner = runtime.createTextOwner(selected.textConfig())
                    try {
                        // Offline execution deliberately never calls prepare or any model installer.
                        assertEquals(
                            "Prepare ${pair.id} online before offline verification",
                            ModelAvailability.READY,
                            requireNotNull(runtime.useText(owner) { it.modelAvailability() }),
                        )
                        val output = requireNotNull(runtime.useText(owner) { it.translate(pair, input) })
                        Log.i("AddedTextLanguagesProof", "runId=$runId pair=${pair.id} input=$input output=$output")
                        outputs += pair to output
                    } finally {
                        runtime.release(owner)
                    }
                }
            }
            // Log all eight actual outputs before lexical checks; relationship judgment is independent.
            for ((pair, output) in outputs) {
                assertAddedTextSemanticControls(pair, output)
            }
        } finally {
            restoreSettings(runtime, original)
            assertEquals("Original settings must be restored exactly", original, runtime.settings.current())
        }
    }

    private fun assertAddedTextSemanticControls(pair: LanguagePair, output: String) {
        val lower = output.lowercase(Locale.ROOT)
        fun containsControl(description: String, pattern: String) {
            assertTrue(
                "${pair.id} must preserve $description: $output",
                Regex(pattern).containsMatchIn(lower),
            )
        }
        fun assertControls(
            negation: String,
            train: String,
            departure: String,
            price: String,
            earlyTimeWords: String,
            lateTimeWords: String,
            priceWords: String,
        ) {
            containsControl("negation", negation)
            containsControl("train", train)
            containsControl("departure action", departure)
            containsControl("price meaning", price)
            containsControl("3 pm / 15 hours", "(?<!\\d)(?:3|15)(?!\\d)|\\b(?:$earlyTimeWords)\\b")
            containsControl("4 pm / 16 hours", "(?<!\\d)(?:4|16)(?!\\d)|\\b(?:$lateTimeWords)\\b")
            containsControl("price 25", "(?<!\\d)25(?!\\d)|\\b(?:$priceWords)\\b")
        }
        when (pair.target) {
            LanguageCatalog.FRENCH -> assertControls(
                negation = "\\b(?:pas|jamais|point)\\b",
                train = "\\btrain\\b",
                departure = "\\b(?:part|départ|depart|quitt|sort)\\p{L}*",
                price = "\\b(?:coût|cout|prix|tarif|vaut)\\p{L}*",
                earlyTimeWords = "trois|quinze",
                lateTimeWords = "quatre|seize",
                priceWords = "vingt[-\\s]+cinq",
            )
            LanguageCatalog.SPANISH -> assertControls(
                negation = "\\b(?:no|nunca)\\b",
                train = "\\b(?:tren|ferrocarril)\\b",
                departure = "\\b(?:sal|part|march)\\p{L}*",
                price = "\\b(?:cuest|cost|precio|vale)\\p{L}*",
                earlyTimeWords = "tres|quince",
                lateTimeWords = "cuatro|dieciséis|dieciseis",
                priceWords = "veinticinco",
            )
            LanguageCatalog.ITALIAN -> assertControls(
                negation = "\\b(?:non|mai)\\b",
                train = "\\btreno\\b",
                departure = "\\b(?:part|lasci)\\p{L}*",
                price = "\\b(?:cost|prezz|vale)\\p{L}*",
                earlyTimeWords = "tre|quindici",
                lateTimeWords = "quattro|sedici",
                priceWords = "venticinque",
            )
            LanguageCatalog.PORTUGUESE -> assertControls(
                negation = "\\b(?:não|nao|nunca)\\b",
                train = "\\b(?:comboio|trem)\\b",
                departure = "\\b(?:part|sai|saí|saem|deix)\\p{L}*",
                price = "\\b(?:cust|preço|preco|vale)\\p{L}*",
                earlyTimeWords = "três|tres|quinze",
                lateTimeWords = "quatro|dezasseis|dezesseis",
                priceWords = "vinte\\s+e\\s+cinco",
            )
            LanguageCatalog.ENGLISH -> assertControls(
                negation = "\\b(?:not|never)\\b|n['’]t\\b",
                train = "\\btrain\\b",
                departure = "\\b(?:leav|depart|go|exit)\\p{L}*",
                price = "\\b(?:cost|price|fare)\\p{L}*",
                earlyTimeWords = "three|fifteen",
                lateTimeWords = "four|sixteen",
                priceWords = "twenty[-\\s]+five",
            )
            else -> fail("Unexpected added-text control target ${pair.target}")
        }
    }

    private val proofDir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "extensibility-proof").also {
            it.mkdirs()
        }

    @Test
    fun prepareSelectedExtensions() = runBlocking<Unit> {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)

        val original = runtime.settings.current()
        val extensionDraft = original.copy(
            languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN),
            speechOutputBackend = SpeechOutputBackend.PIPER,
            textEngineId = "mlkit",
            conversationEngineId = "mlkit",
            vadModelId = "vad-silero-6.2.2",
            languageIdModelId = "lid-whisper-tiny",
            vadMinSilenceMs = 900,
            ttsSpeedPercent = 110,
        )
        try {
            selectPair(runtime, extensionDraft.languagePair)
            assertTrue("Extension advanced settings must be applied", runtime.applySettings(extensionDraft))

            // Prepare Text independently of speech coverage.
            val textOwner = runtime.createTextOwner(extensionDraft.textConfig())
            try {
                val textAvail = requireNotNull(runtime.useText(textOwner) { it.prepare() })
                assertEquals(ModelAvailability.READY, textAvail)
            } finally {
                runtime.release(textOwner)
            }

            // The native conversation scenario explicitly selects RU/EN and Piper.
            selectPair(runtime, LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH))
            val convOwner = runtime.createConversationOwner(runtime.settings.current().conversationConfig())
            try {
                val convReady = runtime.prepareConversation(convOwner)
                assertTrue("Selected conversation resources must be ready", convReady)
            } finally {
                runtime.release(convOwner)
            }
        } finally {
            restoreSettings(runtime, original)
        }
    }


    @Test
    fun restoredConfigurationUsesGermanOffline() = runBlocking {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)

        // Offline verification
        val wifi = android.provider.Settings.Global.getInt(context.contentResolver, "wifi_on", 1)
        val airplane = android.provider.Settings.Global.getInt(context.contentResolver, "airplane_mode_on", 0)
        assertEquals("Wi-Fi must be disabled for offline verification", 0, wifi)
        assertEquals("Airplane mode must be enabled for offline verification", 1, airplane)

        val original = runtime.settings.current()
        try {
        selectPair(runtime, LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN))
        val current = runtime.settings.current()
        val textOwner = runtime.createTextOwner(current.textConfig())
        val receipt = JSONObject().put("offline", true).put("pair", current.languagePair.id)
        val calls = JSONArray()
        receipt.put("calls", calls)

        try {
            assertEquals(ModelAvailability.READY, requireNotNull(runtime.useText(textOwner) { it.modelAvailability() }))

            // 1. EN -> DE: "I will not take the bus."
            val enToDe = current.languagePair
            val t1 = requireNotNull(runtime.useText(textOwner) { it.translate(enToDe, "I will not take the bus.") })
            val t1Lower = t1.lowercase(Locale.ROOT)
            assertTrue("Translation 1 must contain negation (nicht): $t1", t1Lower.contains("nicht"))
            assertTrue("Translation 1 must contain bus (bus): $t1", t1Lower.contains("bus"))
            assertTrue("Translation 1 must contain travel action: $t1", t1Lower.contains("fahr") || t1Lower.contains("nehm"))
            calls.put(JSONObject().put("in", "I will not take the bus.").put("out", t1))

            // 2. EN -> DE: "Please book two tickets for seven o'clock."
            val t2 = requireNotNull(runtime.useText(textOwner) { it.translate(enToDe, "Please book two tickets for seven o'clock.") })
            val t2Lower = t2.lowercase(Locale.ROOT)
            assertTrue("Translation 2 must contain ticket action: $t2", t2Lower.contains("buch") || t2Lower.contains("reservier"))
            assertTrue("Translation 2 must contain number two: $t2", t2Lower.contains("zwei") || t2Lower.contains("2"))
            assertTrue("Translation 2 must contain tickets: $t2", t2Lower.contains("ticket") || t2Lower.contains("kart"))
            assertTrue("Translation 2 must contain seven/clock: $t2", (t2Lower.contains("sieben") || t2Lower.contains("7")) && t2Lower.contains("uhr"))
            calls.put(JSONObject().put("in", "Please book two tickets for seven o'clock.").put("out", t2))
            runtime.release(textOwner)

            // 3. Swap direction to DE -> EN and rebuild Text owner
            val deToEn = current.languagePair.reverse()
            selectPair(runtime, deToEn)
            val reverseOwner = runtime.createTextOwner(TextRuntimeConfig(deToEn, current.textEngineId))
            try {
                assertEquals(ModelAvailability.READY, requireNotNull(runtime.useText(reverseOwner) { it.modelAvailability() }))
                val t3 = requireNotNull(runtime.useText(reverseOwner) { it.translate(deToEn, "Ich werde nicht mit dem Bus fahren.") })
                val t3Lower = t3.lowercase(Locale.ROOT)
                assertTrue("Translation 3 must contain not: $t3", t3Lower.contains("not"))
                assertTrue("Translation 3 must contain bus: $t3", t3Lower.contains("bus"))
                assertTrue("Translation 3 must contain travel: $t3", t3Lower.contains("take") || t3Lower.contains("ride") || t3Lower.contains("travel") || t3Lower.contains("go"))
                calls.put(JSONObject().put("in", "Ich werde nicht mit dem Bus fahren.").put("out", t3))

                // 4. DE -> EN: "Bitte rufen Sie mich in fünfzehn Minuten zurück."
                val t4 = requireNotNull(runtime.useText(reverseOwner) { it.translate(deToEn, "Bitte rufen Sie mich in fünfzehn Minuten zurück.") })
                val t4Lower = t4.lowercase(Locale.ROOT)
                assertTrue("Translation 4 must contain call: $t4", t4Lower.contains("call") || t4Lower.contains("ring") || t4Lower.contains("phone"))
                assertTrue("Translation 4 must contain fifteen/15: $t4", t4Lower.contains("fifteen") || t4Lower.contains("15"))
                assertTrue("Translation 4 must contain minutes: $t4", t4Lower.contains("minute"))
                calls.put(JSONObject().put("in", "Bitte rufen Sie mich in fünfzehn Minuten zurück.").put("out", t4))

                File(proofDir, "german-offline.json").writeText(receipt.toString(2))
            } finally {
                runtime.release(reverseOwner)
            }
        } finally {
            runtime.release(textOwner)
        }
        } finally {
            restoreSettings(runtime, original)
        }
    }

    @Test
    fun selectedConfigurationPreservesSemanticControls() = runBlocking<Unit> {
        requireOptIn()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runtime = AppRuntime.get(context)

        val original = runtime.settings.current()
        try {
        selectPair(runtime, LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH))
        assertTrue(runtime.applySettings(AppSettings(speechOutputBackend = SpeechOutputBackend.PIPER)))
        val current = runtime.settings.current()
        val config = current.conversationConfig()
        val fixturesDir = File(context.filesDir, "conversation-proof/fixtures")
        val manifest = JSONObject(File(fixturesDir, "manifest.json").readText())
        val allCases = manifest.getJSONArray("cases")
        val cases = (0 until allCases.length()).map(allCases::getJSONObject).associateBy { it.getString("id") }

        val activeCases = ConversationProofFixtures.CASE_ORDER
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        var pacedCapture: ConversationProofFixtures.PacedCapture? = null
        val owner = runtime.createConversationOwner(config) { store, sel ->
            ConversationProofFixtures.PacedCapture(scope, store, sel.vadModelId, sel.vadMinSilenceMs).also {
                pacedCapture = it
            }
        }

        val report = JSONObject()
            .put("scope", "extensibility-selected-configuration")
            .put("configurationProof", "recommended")
            .put("microphoneEvidence", false)
            .put("performanceVerdict", "not_qualified")
            .put("memoryBeforePreparation", ConversationProofFixtures.memorySnapshot())

        val rows = JSONArray()
        report.put("rows", rows)

        try {
            assertTrue("Prepare conversation online before execution", runtime.prepareConversation(owner))
            assertTrue(runtime.startConversation(owner))

            withTimeout(120_000) {
                owner.pipeline.state.first { it.phase == ConversationPhase.LISTENING || it.phase == ConversationPhase.ERROR }
            }
            assertEquals(ConversationPhase.LISTENING, owner.pipeline.state.value.phase)

            val timing = ConversationProofFixtures.TurnTiming()
            val capture = requireNotNull(pacedCapture)

            for ((index, caseId) in activeCases.withIndex()) {
                val case = cases.getValue(caseId)
                val file = File(fixturesDir, case.getString("path"))
                val pcm = ConversationProofFixtures.readPcm(file)
                val previousId = owner.pipeline.state.value.transcripts.lastOrNull()?.id ?: 0L

                timing.reset()
                capture.replay(pcm)

                withTimeout(30_000) {
                    owner.pipeline.state.first { state ->
                        state.transcripts.any { it.id > previousId && (it.status == ConversationTranscriptStatus.COMPLETE || it.status == ConversationTranscriptStatus.ERROR) }
                    }
                }

                val transcript = owner.pipeline.state.value.transcripts.last()
                android.util.Log.e("ExtensibilityTest", "DECODED: id=$caseId original='${transcript.original}' translation='${transcript.translation}'")
                assertEquals(ConversationTranscriptStatus.COMPLETE, transcript.status)
                assertNull(transcript.failure)
                val expectedDirection = if (case.getString("language") == "ru") {
                    LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
                } else {
                    LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
                }
                assertEquals(expectedDirection, transcript.direction)

                ConversationProofFixtures.assertSourceMeaning(case, transcript.original, report)
                ConversationProofFixtures.assertTranslationMeaning(case, requireNotNull(transcript.translation))

                val row = JSONObject()
                    .put("index", index)
                    .put("case", caseId)
                    .put("original", transcript.original)
                    .put("translation", transcript.translation)
                    .put("latencyMillis", transcript.latencyMillis)
                    .put("memoryAfterTurn", ConversationProofFixtures.memorySnapshot())
                rows.put(row)
            }

            owner.pipeline.stop().getOrThrow()
            withTimeout(30_000) {
                owner.pipeline.state.first { it.phase == ConversationPhase.READY && !it.sessionActive }
            }
            report.put("memoryAfterTeardown", ConversationProofFixtures.memorySnapshot())

            val receiptName = "selected-default.json"
            File(proofDir, receiptName).writeText(report.toString(2))
        } finally {
            runtime.release(owner)
            scope.cancel()
        }
        } finally {
            restoreSettings(runtime, original)
        }
    }

    @Test
    fun invalidSelectionsRefuseWithoutSideEffects() = runBlocking<Unit> {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = AppRuntime.get(context)

        // 1. Unknown engine
        val unknownEngine = AppSettings(textEngineId = "unknown-mt")
        val problems1 = runtime.validationProblems(unknownEngine)
        assertTrue(problems1.contains(ConfigurationProblem.UNKNOWN_ENGINE))

        // Invalid silence
        val invalidSilence = AppSettings(vadMinSilenceMs = 2500)
        val problems3 = runtime.validationProblems(invalidSilence)
        assertTrue(problems3.contains(ConfigurationProblem.INVALID_SILENCE))

        // Invalid speed
        val invalidSpeed = AppSettings(ttsSpeedPercent = 50)
        val problems4 = runtime.validationProblems(invalidSpeed)
        assertTrue(problems4.contains(ConfigurationProblem.INVALID_SPEED))

        // Incompatible activity model
        val incompatibleVad = AppSettings(vadModelId = "lid-whisper-tiny")
        val problems5 = runtime.validationProblems(incompatibleVad)
        assertTrue(problems5.contains(ConfigurationProblem.INCOMPATIBLE_MODEL))

    }

    @Test
    fun textOnlySharedSelectionRemainsValidWithoutConversationConfiguration() = runBlocking {
        requireOptIn()
        val runtime = AppRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = runtime.settings.current()
        val pair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        try {
            selectPair(runtime, pair)
            val selected = runtime.settings.current()
            assertTrue(runtime.validationProblems(selected).isEmpty())
            assertTrue(runtime.textProblems(selected).isEmpty())
            assertFalse(runtime.conversationProblems(selected).isEmpty())
            assertEquals(pair, selected.languagePair)
            // Metadata validation must not restore a speech-capable pair or acquire an owner.
            assertEquals(selected, runtime.settings.current())
            assertTrue(runtime.updateLanguagePair(pair, pair))
        } finally {
            restoreSettings(runtime, original)
        }
    }

    private suspend fun selectPair(runtime: AppRuntime, selected: LanguagePair) {
        val expected = runtime.settings.current().languagePair
        assertTrue("Commit shared pair ${selected.id}", runtime.updateLanguagePair(expected, selected))
        assertEquals(selected, runtime.settings.current().languagePair)
    }

    private suspend fun restoreSettings(runtime: AppRuntime, original: AppSettings) {
        selectPair(runtime, original.languagePair)
        assertTrue("Restore original advanced settings", runtime.applySettings(original))
        assertEquals("Original preferences must be restored exactly", original, runtime.settings.current())
    }
}
