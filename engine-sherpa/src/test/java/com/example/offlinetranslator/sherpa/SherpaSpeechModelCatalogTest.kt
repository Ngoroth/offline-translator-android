package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SherpaSpeechModelCatalogTest {
    @Test fun oneSharedWhisperSelectionCoversCurrentPairsWithoutOldIdentifierOrPerLanguagePackages() {
        val catalog = parse(document())
        val modelId = WhisperConversationRecognitionEngine.MODEL_ID
        val dependencies = catalog.requiredPackages(setOf(modelId))
        assertEquals(setOf("whisper-small-multilingual-int8-encoder", "whisper-small-multilingual-int8-decoder",
            "whisper-small-multilingual-int8-tokens"), dependencies)
        for (languages in listOf(listOf("nl", "en"), listOf("en", "nl"), listOf("nl", "ru"),
            listOf("ru", "nl"), listOf("ru", "en"), listOf("en", "ru"))) {
            val selected = WhisperConversationRecognitionEngine.selectedDefinition(catalog, config(languages))
            assertEquals(dependencies, catalog.requiredPackages(setOf(selected.id)))
        }
        assertEquals(LanguageCatalog.entries.mapTo(linkedSetOf()) { it.id }, catalog.model(modelId).languages)
        assertEquals(setOf(LanguageId("en"), LanguageId("ru")),
            catalog.models.filter { it.role == SpeechModelRole.TTS }.flatMap { it.languages }.toSet())
    }

    @Test fun multilingualWhisperRejectsForcedLanguageWrongRoleAndUnboundedNativeParameters() {
        for ((field, value) in listOf("role" to "TTS", "sampleRateHz" to 8_000,
            "languages" to JSONArray().put("en"))) {
            val invalid = document()
            whisper(invalid).put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("language" to "en", "task" to "translate",
            "tailPaddings" to 0, "tailPaddings" to 301, "numThreads" to 2, "featureDim" to 128)) {
            val invalid = document()
            whisper(invalid).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val oldMonolingual = document()
        model(oldMonolingual, "asr-en-whisper-tiny.en").put("languages", JSONArray().put("en").put("nl"))
        rejects(oldMonolingual)
    }

    @Test fun sharedWhisperDependenciesMustBeDeclaredDistinctFilesBeforeSelection() {
        for (name in listOf("encoder", "decoder", "tokens")) {
            val missing = document()
            whisper(missing).getJSONObject("adapter").getJSONObject(name).put("path", "absent.onnx")
            rejects(missing)
        }
        val aliased = document()
        val adapter = whisper(aliased).getJSONObject("adapter")
        adapter.put("decoder", adapter.getJSONObject("encoder"))
        rejects(aliased)
        val missingPackage = document()
        val packages = missingPackage.getJSONArray("packages")
        for (index in 0 until packages.length()) {
            if (packages.getJSONObject(index).getString("id") == "whisper-small-multilingual-int8-decoder") {
                packages.remove(index)
                break
            }
        }
        rejects(missingPackage)
    }

    @Test fun selectedWhisperCannotUseMissingWrongFamilyOrUnsupportedSessionModel() {
        val selectedConfig = config(listOf("en", "nl"))
        val missing = document()
        val models = missing.getJSONArray("models")
        for (index in 0 until models.length()) {
            if (models.getJSONObject(index).getString("id") == WhisperConversationRecognitionEngine.MODEL_ID) {
                models.remove(index)
                break
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WhisperConversationRecognitionEngine.selectedDefinition(parse(missing), selectedConfig)
        }
        val wrongFamily = document()
        val old = model(wrongFamily, "asr-en-whisper-tiny.en")
        whisper(wrongFamily).put("adapter", old.getJSONObject("adapter")).put("languages", old.getJSONArray("languages"))
        assertThrows(IllegalStateException::class.java) {
            WhisperConversationRecognitionEngine.selectedDefinition(parse(wrongFamily), selectedConfig)
        }
        val unsupported = document()
        whisper(unsupported).put("languages", JSONArray().put("en").put("ru"))
        assertThrows(IllegalArgumentException::class.java) {
            WhisperConversationRecognitionEngine.selectedDefinition(parse(unsupported), selectedConfig)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WhisperConversationRecognitionEngine.selectedDefinition(parse(document()), config(listOf("en", "nl"), SpeechRecognitionBackend.SYSTEM_OFFLINE))
        }
    }

    @Test fun selectedDependenciesDeduplicateSharedWeightsAndExcludeUnselectedArtifacts() {
        val catalog = parse(document())
        val alternate = catalog.requiredPackages(setOf("asr-en-whisper-tiny"))
        assertEquals(setOf("language-whisper-tiny-encoder", "language-whisper-tiny-decoder", "whisper-tiny-tokens"), alternate)
        assertFalse("en-whisper-tiny.en-offline" in alternate)
        val selected = catalog.requiredPackages(setOf("vad-silero-6.2.2", "asr-ru-gigaam-v1",
            "asr-en-whisper-tiny.en", "tts-ru-piper-irina-medium", "tts-en-piper-lessac-medium"))
        assertFalse("whisper-tiny-tokens" in selected)
        assertFalse("language-whisper-tiny-encoder" in selected)
        assertFalse("language-whisper-tiny-decoder" in selected)
        assertTrue(catalog.requiredPackages(emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { catalog.requiredPackages(setOf("missing")) }
    }

    @Test fun acceptsIsolatedDefinitionsWithoutRequiringEveryRole() {
        val document = document()
        val packages = document.getJSONArray("packages")
        val models = document.getJSONArray("models")
        document.put("packages", JSONArray().put(packages.get(0)))
        document.put("models", JSONArray().put(models.get(0)))
        assertEquals(SpeechModelRole.VAD, parse(document).models.single().role)
        document.put("models", JSONArray())
        assertTrue(parse(document).models.isEmpty())
    }

    @Test fun parsesHistoricalOnlineFamiliesWithDeclaredReferences() {
        for (family in listOf("toneCtcOnline", "zipformer2Online")) {
            val document = document()
            val model = document.getJSONArray("models").getJSONObject(1)
            val pkg = document.getJSONArray("packages").getJSONObject(1)
            val adapter = JSONObject().put("family", family).put("numThreads", 2).put("featureDim", 80)
            fun ref(path: String) = JSONObject().put("packageId", pkg.getString("id")).put("path", path)
            adapter.put("tokens", ref("tokens.txt"))
            if (family == "toneCtcOnline") {
                adapter.put("model", ref("model.int8.onnx"))
                model.put("sampleRateHz", 8_000)
            } else {
                val files = pkg.getJSONArray("files")
                for (path in listOf("decoder.onnx", "joiner.onnx")) {
                    files.put(JSONObject().put("path", path).put("bytes", 1).put("sha256", "a".repeat(64)))
                }
                adapter.put("encoder", ref("model.int8.onnx"))
                    .put("decoder", ref("decoder.onnx")).put("joiner", ref("joiner.onnx"))
            }
            model.put("adapter", adapter)
            val parsed = parse(document).model("asr-ru-gigaam-v1")
            assertEquals(setOf(LanguageId("ru")), parsed.languages)
            assertEquals(if (family == "toneCtcOnline") 2 else 4, parsed.requiredRefs.size)
            assertTrue(if (family == "toneCtcOnline") parsed.adapter is SherpaSpeechModelAdapter.ToneCtcOnline
                else parsed.adapter is SherpaSpeechModelAdapter.Zipformer2Online)
        }
    }

    @Test fun rejectsUnknownFamilyAndRoleLanguageRateMismatchesBeforeNativeUse() {
        for ((field, value) in listOf(
            "role" to "TTS", "languages" to JSONArray().put("ru"), "languages" to JSONArray(),
            "languages" to JSONArray().put("EN"), "languages" to JSONArray().put("en").put("en"),
            "sampleRateHz" to 22_050, "sampleRateHz" to "16000",
        )) {
            val invalid = document()
            model(invalid, "asr-en-whisper-tiny.en").put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("family" to "arbitraryClass", "language" to "ru", "task" to "translate",
            "numThreads" to 0, "numThreads" to "2", "featureDim" to 81, "tailPaddings" to 1.5,
            "provider" to "cuda")) {
            val invalid = document()
            model(invalid, "asr-en-whisper-tiny.en").getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val invalidVad = document()
        model(invalidVad, "vad-silero-6.2.2").put("languages", JSONArray().put("en"))
        rejects(invalidVad)
        val invalidNemo = document()
        model(invalidNemo, "asr-ru-gigaam-v1").put("sampleRateHz", 8_000)
        rejects(invalidNemo)
    }

    @Test fun rejectsUndeclaredUnsafeAndAliasedReferencesIncludingDirectoryPrefixes() {
        for ((field, value) in listOf("packageId" to "missing", "path" to "missing.onnx", "path" to "../model.onnx")) {
            val invalid = document()
            model(invalid, "asr-en-whisper-tiny.en").getJSONObject("adapter").getJSONObject("encoder").put(field, value)
            rejects(invalid)
        }
        val aliased = document()
        val adapter = model(aliased, "asr-en-whisper-tiny.en").getJSONObject("adapter")
        adapter.put("decoder", adapter.getJSONObject("encoder"))
        rejects(aliased)
        val invalidDirectory = document()
        model(invalidDirectory, "tts-ru-piper-irina-medium").getJSONObject("adapter")
            .getJSONObject("dataDir").put("path", "espeak-ng")
        rejects(invalidDirectory)
    }

    @Test fun rejectsNativeParametersOutsideBoundedCompiledFamilyContracts() {
        for ((field, value) in listOf("threshold" to 0, "threshold" to 1.1,
            "minSpeechDuration" to 15, "maxSpeechDuration" to 15, "windowSize" to 256)) {
            val invalid = document()
            model(invalid, "vad-silero-6.2.2").getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val reversedDurations = document()
        model(reversedDurations, "vad-silero-6.2.2").getJSONObject("adapter")
            .put("minSpeechDuration", 5).put("maxSpeechDuration", 1)
        rejects(reversedDurations)
        for ((field, value) in listOf("noiseScale" to -1, "noiseScaleW" to "0.8", "lengthScale" to 0)) {
            val invalid = document()
            model(invalid, "tts-ru-piper-irina-medium").getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
    }

    private fun whisper(document: JSONObject): JSONObject {
        val models = document.getJSONArray("models")
        return (0 until models.length()).map { models.getJSONObject(it) }
            .single { it.getString("id") == WhisperConversationRecognitionEngine.MODEL_ID }
    }

    private fun config(languages: List<String>, backend: SpeechRecognitionBackend = SpeechRecognitionBackend.WHISPER) =
        ConversationSessionConfig(languages.map { LanguageId(it) }, "test", backend,
            "vad-silero-6.2.2", 700, 100, SpeechOutputBackend.SYSTEM_OFFLINE)

    private fun rejects(document: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { parse(document) }
    }
    private fun model(document: JSONObject, id: String): JSONObject {
        val models = document.getJSONArray("models")
        return (0 until models.length()).map(models::getJSONObject).single { it.getString("id") == id }
    }
    private fun document() = JSONObject(File("src/main/assets/speech-models.json").readText())
    private fun parse(document: JSONObject) = SherpaSpeechModelCatalog.parse(document.toString().byteInputStream())
}
