package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class SpeechModelCatalogTest {
    @Test fun supportsSpaceSeparatedVoiceNamesWithoutAcceptingEscapingOrAmbiguousPaths() {
        assertTrue(SpeechModelCatalog.safeRelativePath("espeak-ng-data/voices/!v/Mr serious"))
        assertTrue(SpeechModelCatalog.safeRelativePath("voices/Voice variant 2.json"))
        for (unsafe in listOf(
            "../model.onnx", "/model.onnx", "voices/../model.onnx", "voices//model.onnx",
            "voices/ ../model.onnx", "voices/name /model.onnx", "voices/name\nmodel.onnx",
            "voices/name\tmodel.onnx", "C:/model.onnx", "voices\\model.onnx",
        )) assertFalse("Unsafe model path: $unsafe", SpeechModelCatalog.safeRelativePath(unsafe))
    }

    @Test fun selectedDependenciesDeduplicateSharedWeightsAndExcludeUnselectedArtifacts() {
        val catalog = parse(document())
        val alternate = catalog.requiredPackages(setOf("asr-en-whisper-tiny", "lid-whisper-tiny"))
        assertEquals(setOf("language-whisper-tiny-encoder", "language-whisper-tiny-decoder", "whisper-tiny-tokens"), alternate)
        assertFalse("en-whisper-tiny.en-offline" in alternate)
        val defaults = catalog.requiredPackages(setOf("vad-silero-6.2.2", "asr-ru-gigaam-v1",
            "asr-en-whisper-tiny.en", "lid-whisper-tiny", "tts-ru-piper-irina-medium", "tts-en-piper-lessac-medium"))
        assertEquals(7, defaults.size)
        assertFalse("whisper-tiny-tokens" in defaults)
        assertTrue(catalog.requiredPackages(emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { catalog.requiredPackages(setOf("missing")) }
    }

    @Test fun preservesNativeDefaultParametersAndExpandsVerifiedPiperDirectoryInventory() {
        val catalog = parse(document())
        val vad = catalog.model("vad-silero-6.2.2").adapter as SpeechModelAdapter.Silero
        assertEquals(1, vad.numThreads)
        assertEquals(0.5f, vad.threshold, 0f)
        assertEquals(0.25f, vad.minSpeechDuration, 0f)
        assertEquals(14f, vad.maxSpeechDuration, 0f)
        assertEquals(512, vad.windowSize)
        val nemo = catalog.model("asr-ru-gigaam-v1").adapter as SpeechModelAdapter.NemoCtcOffline
        assertEquals(4, nemo.numThreads)
        assertEquals(80, nemo.featureDim)
        for (id in listOf("asr-en-whisper-tiny.en", "asr-en-whisper-tiny")) {
            val whisper = catalog.model(id).adapter as SpeechModelAdapter.WhisperOffline
            assertEquals("en", whisper.language)
            assertEquals("transcribe", whisper.task)
            assertEquals(1_000, whisper.tailPaddings)
            assertEquals(2, whisper.numThreads)
            assertEquals(80, whisper.featureDim)
        }
        assertEquals(2, (catalog.model("lid-whisper-tiny").adapter as SpeechModelAdapter.WhisperLanguageId).numThreads)
        for (id in listOf("tts-ru-piper-irina-medium", "tts-en-piper-lessac-medium")) {
            val model = catalog.model(id)
            val voice = model.adapter as SpeechModelAdapter.PiperVits
            assertEquals(0.667f, voice.noiseScale, 0f)
            assertEquals(0.8f, voice.noiseScaleW, 0f)
            assertEquals(1f, voice.lengthScale, 0f)
            assertEquals(2, voice.numThreads)
            assertEquals(22_050, model.sampleRateHz)
            val expected = catalog.packageInfo(voice.dataDir.packageId).files
                .filter { it.path.startsWith(voice.dataDir.path + "/") }
                .map { SpeechModelRef(voice.dataDir.packageId, it.path) }.toSet()
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, model.requiredRefs.filter { it.path.startsWith(voice.dataDir.path + "/") }.toSet())
            assertFalse(voice.dataDir in model.requiredRefs)
        }
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
            assertTrue(if (family == "toneCtcOnline") parsed.adapter is SpeechModelAdapter.ToneCtcOnline
                else parsed.adapter is SpeechModelAdapter.Zipformer2Online)
        }
    }

    @Test fun rejectsUnknownFamilyAndRoleLanguageRateMismatchesBeforeNativeUse() {
        for ((field, value) in listOf(
            "role" to "TTS", "languages" to JSONArray().put("ru"), "languages" to JSONArray(),
            "languages" to JSONArray().put("EN"), "languages" to JSONArray().put("en").put("en"),
            "sampleRateHz" to 22_050, "sampleRateHz" to "16000",
        )) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(2).put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("family" to "arbitraryClass", "language" to "ru", "task" to "translate",
            "numThreads" to 0, "numThreads" to "2", "featureDim" to 81, "tailPaddings" to 1.5,
            "provider" to "cuda")) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(2).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val invalidVad = document()
        invalidVad.getJSONArray("models").getJSONObject(0).put("languages", JSONArray().put("en"))
        rejects(invalidVad)
        val invalidNemo = document()
        invalidNemo.getJSONArray("models").getJSONObject(1).put("sampleRateHz", 8_000)
        rejects(invalidNemo)
    }

    @Test fun rejectsUndeclaredUnsafeAndAliasedReferencesIncludingDirectoryPrefixes() {
        for ((field, value) in listOf("packageId" to "missing", "path" to "missing.onnx", "path" to "../model.onnx")) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(2).getJSONObject("adapter").getJSONObject("encoder").put(field, value)
            rejects(invalid)
        }
        val aliased = document()
        val adapter = aliased.getJSONArray("models").getJSONObject(2).getJSONObject("adapter")
        adapter.put("decoder", adapter.getJSONObject("encoder"))
        rejects(aliased)
        val invalidDirectory = document()
        invalidDirectory.getJSONArray("models").getJSONObject(4).getJSONObject("adapter")
            .getJSONObject("dataDir").put("path", "espeak-ng")
        rejects(invalidDirectory)
    }

    @Test fun rejectsNativeParametersOutsideBoundedCompiledFamilyContracts() {
        for ((field, value) in listOf("threshold" to 0, "threshold" to 1.1,
            "minSpeechDuration" to 15, "maxSpeechDuration" to 15, "windowSize" to 256)) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(0).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val reversedDurations = document()
        reversedDurations.getJSONArray("models").getJSONObject(0).getJSONObject("adapter")
            .put("minSpeechDuration", 5).put("maxSpeechDuration", 1)
        rejects(reversedDurations)
        for ((field, value) in listOf("noiseScale" to -1, "noiseScaleW" to "0.8", "lengthScale" to 0)) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(4).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
    }

    @Test fun rejectsUnboundedMetadataFilesEntriesBytesAndDuplicateIdentities() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechModelCatalog.parse(ByteArrayInputStream(ByteArray(SpeechModelCatalog.MAX_CATALOG_BYTES + 1) { 32 }))
        }
        for ((field, value) in listOf("maxEntries" to 1_025L, "maxUnpackedBytes" to Long.MAX_VALUE,
            "archiveBytes" to Long.MAX_VALUE, "archiveBytes" to "2327524")) {
            val invalid = document()
            invalid.getJSONArray("packages").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
        val tooMany = document()
        val files = tooMany.getJSONArray("packages").getJSONObject(5).getJSONArray("files")
        while (files.length() <= 512) files.put(files.getJSONObject(0))
        rejects(tooMany)
        for (arrayName in listOf("packages", "models")) {
            val duplicate = document()
            val array = duplicate.getJSONArray(arrayName)
            array.put(array.get(0))
            rejects(duplicate)
            val oversized = document()
            val entries = oversized.getJSONArray(arrayName)
            while (entries.length() <= 64) entries.put(entries.get(0))
            rejects(oversized)
        }
    }

    @Test fun rejectsUntrustedUrlsDigestInventoryAndRawPackageMismatch() {
        for ((field, value) in listOf("url" to "http://raw.githubusercontent.com/model.onnx",
            "url" to "https://evil.example/model.onnx", "url" to "https://user@raw.githubusercontent.com/model.onnx",
            "archiveSha256" to "bad", "archiveSha256" to "A".repeat(64))) {
            val invalid = document()
            invalid.getJSONArray("packages").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("path" to "../model.onnx", "sha256" to "bad", "bytes" to 0L,
            "bytes" to 2327523L)) {
            val invalid = document()
            invalid.getJSONArray("packages").getJSONObject(0).getJSONArray("files").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
    }

    private fun rejects(document: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { parse(document) }
    }
    private fun document() = JSONObject(File("src/main/assets/speech-models.json").readText())
    private fun parse(document: JSONObject) = SpeechModelCatalog.parse(document.toString().byteInputStream())
}
