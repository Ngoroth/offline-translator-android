package com.example.offlinetranslator.runtime

import android.net.ConnectivityManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitTextLanguageIdentifier
import com.example.offlinetranslator.nemo.NemoSpeechModelCatalog
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit model-bearing qualification: real installed packages, JNI, bundled SDK, and human PCM. */
class NemoRecognitionNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val nlEn = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH)
    private val ruEn = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
    private val nlRu = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.RUSSIAN)
    private val root get() = File(context.filesDir, "nemo-recognition-proof").apply { mkdirs() }

    @Test fun prepareSelectedLanguagesThroughProductionInstaller() = runBlocking {
        val runtime = AppRuntime.get(context)
        val original = runtime.settings.current()
        val rows = JSONArray()
        try {
            for (pair in listOf(nlEn, nlRu, ruEn)) {
                val selected = original.copy(languagePair = pair,
                    speechRecognitionBackend = SpeechRecognitionBackend.NEMO,
                    speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)
                commitNativeSettings(runtime, selected)
                val owner = runtime.createConversationOwner(selected.conversationConfig())
                try {
                    val ready = withTimeout(1_800_000) { runtime.prepareConversation(owner) }
                    rows.put(JSONObject().put("pair", pair.id).put("ready", ready)
                        .put("recognition", owner.recognition.readinessState.value.name)
                        .put("voice", owner.output.readiness().name))
                    assertTrue("Production preparation must finish with local recognition, translation and voices: $rows", ready)
                } finally { runtime.release(owner) }
            }
        } finally {
            File(root, "preparation.json").writeText(JSONObject().put("rows", rows).toString(2))
            try { commitNativeSettings(runtime, original) } finally { runtime.closeAndJoin() }
        }
    }

    @Test fun packagedJniPreservesHumanControlMeaningAndRejectsForeignPairAndSilence() = runBlocking {
        requireOffline()
        val runtime = AppRuntime.get(context)
        val rows = JSONArray()
        val report = JSONObject().put("microphoneEvidence", false)
            .put("input", "unaltered pinned human PCM16; no reference/source hint passed to the transcriber")
            .put("rows", rows)
        try {
            withContext(Dispatchers.Default) {
                val started = System.nanoTime()
                NemoSpeechModelCatalog.createTranscriber(runtime.speechModels).use { transcriber ->
                    report.put("creationAndWarmupMillis", (System.nanoTime() - started) / 1_000_000)
                    MlKitTextLanguageIdentifier().use { identifier ->
                        for (source in humanSources()) {
                            val pcm = loadPcm(source)
                            val recognitionStarted = System.nanoTime()
                            val text = transcriber.transcribe(SpeechSegment.fromPcm(pcm))
                            val asrMillis = (System.nanoTime() - recognitionStarted) / 1_000_000
                            val pair = if (source.language == LanguageCatalog.RUSSIAN) ruEn else nlEn
                            val identificationStarted = System.nanoTime()
                            val identified = identifier.identify(text, pair)
                            val identificationMillis = (System.nanoTime() - identificationStarted) / 1_000_000.0
                            val foreignPair = foreignPair(source.language)
                            val foreign = identifier.identify(text, foreignPair)
                            rows.put(JSONObject().put("asset", source.asset).put("spokenSource", source.language.value)
                                .put("reference", source.reference).put("rawText", text)
                                .put("acceptedSource", identified?.value ?: JSONObject.NULL)
                                .put("foreignPair", foreignPair.id).put("foreignAcceptedSource", foreign?.value ?: JSONObject.NULL)
                                .put("asrMillis", asrMillis).put("textIdentificationMillis", identificationMillis)
                                .put("sourceExactAfterNormalization", ConversationProofFixtures.normalizedText(text) ==
                                    ConversationProofFixtures.normalizedText(source.reference))
                                .put("memory", ConversationProofFixtures.memorySnapshot()))
                            assertEquals("Wrong selected-pair source: $rows", source.language, identified)
                            assertNull("A global foreign language must not be forced into the selected pair: $rows", foreign)
                            if (source.language != LanguageCatalog.DUTCH) {
                                assertEquals("Short human control meaning changed: $rows",
                                    ConversationProofFixtures.normalizedText(source.reference),
                                    ConversationProofFixtures.normalizedText(text))
                            }
                        }
                        val silenceStarted = System.nanoTime()
                        val silence = transcriber.transcribe(SpeechSegment.fromPcm(ShortArray(32_000)))
                        val acceptedSilence = identifier.identify(silence, nlEn)
                        report.put("silence", JSONObject().put("rawText", silence)
                            .put("acceptedSource", acceptedSilence?.value ?: JSONObject.NULL)
                            .put("millis", (System.nanoTime() - silenceStarted) / 1_000_000))
                        assertNull("Silence must not become an accepted utterance: $report", acceptedSilence)
                    }
                }
            }
        } finally {
            File(root, "packaged-jni.json").writeText(report.toString(2))
            runtime.closeAndJoin()
        }
    }

    @Test fun bundledIdentifierMaintainsFrozenHeldOutCoverageWithoutWrongAcceptedSources() = runBlocking {
        requireOffline()
        val document = JSONObject(instrumentation.context.assets.open("conversation/parakeet-heldout-texts.json")
            .bufferedReader().use { it.readText() })
        val cases = document.getJSONArray("cases")
        val rows = JSONArray()
        var correct = 0
        var abstained = 0
        var wrong = 0
        var clientConstructionMillis: Double? = null
        try {
            withContext(Dispatchers.Default) {
                val constructionStarted = System.nanoTime()
                MlKitTextLanguageIdentifier().use { identifier ->
                    clientConstructionMillis = (System.nanoTime() - constructionStarted) / 1_000_000.0
                    for (index in 0 until cases.length()) {
                        val case = cases.getJSONObject(index)
                        val source = LanguageId(case.getString("spokenSource"))
                        val pair = if (source == LanguageCatalog.RUSSIAN) ruEn else nlEn
                        val started = System.nanoTime()
                        val identified = identifier.identify(case.getString("text"), pair)
                        val millis = (System.nanoTime() - started) / 1_000_000.0
                        val foreign = identifier.identify(case.getString("text"), foreignPair(source))
                        rows.put(JSONObject().put("id", case.getString("id")).put("spokenSource", source.value)
                            .put("acceptedSource", identified?.value ?: JSONObject.NULL)
                            .put("foreignAcceptedSource", foreign?.value ?: JSONObject.NULL).put("millis", millis))
                        when (identified) { source -> correct++; null -> abstained++; else -> wrong++ }
                        if (foreign != null) wrong++
                    }
                }
            }
            assertEquals("Held-out routing accepted a wrong or foreign source: $rows", 0, wrong)
            assertTrue("Frozen held-out acceptance coverage regressed: $rows", correct >= 67)
        } finally {
            File(root, "bundled-text-routing.json").writeText(JSONObject().put("correctAccepted", correct)
                .put("clientConstructionMillis", clientConstructionMillis ?: JSONObject.NULL)
                .put("abstained", abstained).put("wrongAccepted", wrong).put("rows", rows).toString(2))
        }
    }

    private fun humanSources(): List<ConversationProofFixtures.HumanSource> {
        val sources = ConversationProofFixtures.SHORT_HUMAN_SOURCES.filter {
            it.language == LanguageCatalog.RUSSIAN || it.asset == "short-human-en-right.wav"
        }.toMutableList()
        sources += ConversationProofFixtures.HumanSource("short-human-en-wait.wav", LanguageCatalog.ENGLISH,
            "Wait a minute!", 206636, "5643c304164391cdb29c046c51433b82679d6001ef5689d22db7b6029a6acfc0", 103296)
        val cases = JSONObject(instrumentation.context.assets.open("conversation/manifest.json")
            .bufferedReader().use { it.readText() }).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val source = cases.getJSONObject(index)
            sources += ConversationProofFixtures.HumanSource("conversation/${source.getString("path")}",
                LanguageCatalog.DUTCH, source.getString("reference"), source.getLong("bytes"),
                source.getString("sha256"), (source.getDouble("durationMs") * 16).toInt())
        }
        return sources
    }

    private fun loadPcm(source: ConversationProofFixtures.HumanSource): ShortArray {
        val file = File.createTempFile("nemo-human-control-", ".wav", context.cacheDir)
        return try {
            instrumentation.context.assets.open(source.asset).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            assertEquals(source.bytes, file.length())
            assertEquals(source.sha256, ConversationProofFixtures.sha256(file))
            ConversationProofFixtures.readPcm(file).also { assertEquals(source.samples, it.size) }
        } finally { file.delete() }
    }

    private fun foreignPair(source: LanguageId): LanguagePair = when (source) {
        LanguageCatalog.ENGLISH -> nlRu
        LanguageCatalog.RUSSIAN -> nlEn
        LanguageCatalog.DUTCH -> ruEn
        else -> error("Unexpected source in the pinned cohort")
    }

    private fun requireOffline() {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("No default network during offline native proof", connectivity.activeNetwork)
        assertTrue("No active networks during offline native proof", connectivity.allNetworks.isEmpty())
    }
}
