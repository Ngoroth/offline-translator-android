package com.example.offlinetranslator.speechmodels

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class SpeechModelCatalogTest {
    @Test fun preservesLiteralPathsWithoutAcceptingEscapingOrAmbiguousNames() {
        for (path in listOf("espeak-ng-data/voices/!v/Mr serious", "voices/Voice variant 2.json", "License%20NC.pdf")) {
            assertTrue(SpeechModelCatalog.safeRelativePath(path))
        }
        for (path in listOf("../model.onnx", "/model.onnx", "voices/../model.onnx", "voices//model.onnx",
            "voices/ ../model.onnx", "voices/name /model.onnx", "voices/name\nmodel.onnx", "voices/name\tmodel.onnx",
            "C:/model.onnx", "voices\\model.onnx")) assertFalse(path, SpeechModelCatalog.safeRelativePath(path))
    }

    @Test fun expandsOnlyInventoriedDirectoryMembersAndRejectsAliasedFiles() {
        val document = document()
        val adapter = document.getJSONArray("models").getJSONObject(0).getJSONObject("adapter")
        adapter.put("family", "directory").put("directory", ref("data"))
        val model = parse(document).models.single()
        assertEquals(listOf(SpeechModelRef("weights", "model.bin"), SpeechModelRef("weights", "data/file.bin")), model.requiredRefs)
        assertEquals(setOf("weights"), parse(document).requiredPackages(setOf("model")))
        assertTrue(parse(document).requiredPackages(emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { parse(document).requiredPackages(setOf("missing")) }
        adapter.put("model", ref("data/file.bin"))
        rejects(document)
        adapter.put("model", ref("model.bin"))
        adapter.put("directory", ref("data-prefix"))
        rejects(document)
        adapter.remove("directory")
        adapter.put("family", "second").put("second", ref("model.bin"))
        rejects(document)
    }

    @Test fun rejectsUnknownUnsafeOrUndeclaredReferences() {
        for ((field, value) in listOf("packageId" to "missing", "path" to "missing.bin", "path" to "../model.bin",
            "path" to "data", "unexpected" to "value")) {
            val document = document()
            document.getJSONArray("models").getJSONObject(0).getJSONObject("adapter").getJSONObject("model").put(field, value)
            rejects(document)
        }
    }

    @Test fun requiresExplicitRuntimeAndStrictBoundedScalarsAndFields() {
        val document = document()
        document.put("runtime", "other-runtime")
        rejects(document)
        for ((field, value) in listOf("threads" to "2", "threads" to 1.5, "threads" to 0, "threads" to 17,
            "scale" to "0.5", "scale" to 0, "scale" to 1.1, "family" to "unknown", "unknown" to "ignored")) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(0).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("sampleRateHz" to "16000", "sampleRateHz" to 7999, "unknown" to "ignored")) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechModelCatalog.parse(ByteArrayInputStream(ByteArray(SpeechModelCatalog.MAX_CATALOG_BYTES + 1) { 32 }), RUNTIME, ::decode)
        }
    }

    @Test fun allowsThirtyTwoCanonicalModelLanguagesButNotDuplicatesOrMore() {
        val document = document()
        val model = document.getJSONArray("models").getJSONObject(0)
        val languages = JSONArray()
        for (suffix in 'a'..'z') languages.put("a$suffix")
        for (suffix in 'a'..'f') languages.put("b$suffix")
        model.put("languages", languages)
        assertEquals(32, parse(document).models.single().languages.size)
        languages.put("bg")
        rejects(document)
        model.put("languages", JSONArray().put("en").put("en"))
        rejects(document)
        model.put("languages", JSONArray().put("EN"))
        rejects(document)
    }

    @Test fun preservesPackageInventoryQuotaAndUrlChecks() {
        for ((field, value) in listOf("maxEntries" to 1025L, "maxUnpackedBytes" to Long.MAX_VALUE,
            "archiveBytes" to Long.MAX_VALUE, "archiveBytes" to "4", "archiveSha256" to "A".repeat(64),
            "url" to "http://models.example/model.tar.bz2", "url" to "https://evil.example/model.tar.bz2",
            "url" to "https://user@models.example/model.tar.bz2", "unknown" to "ignored")) {
            val invalid = document()
            invalid.getJSONArray("packages").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
        for ((field, value) in listOf("path" to "../model.bin", "bytes" to 0, "bytes" to "1", "sha256" to "bad")) {
            val invalid = document()
            invalid.getJSONArray("packages").getJSONObject(0).getJSONArray("files").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
        val tooManyFiles = document()
        val files = tooManyFiles.getJSONArray("packages").getJSONObject(0).getJSONArray("files")
        while (files.length() <= 512) files.put(files.getJSONObject(0))
        rejects(tooManyFiles)
        val raw = document()
        raw.getJSONArray("packages").getJSONObject(0).put("format", "raw")
        rejects(raw)
        for (arrayName in listOf("packages", "models")) {
            val duplicate = document()
            val array = duplicate.getJSONArray(arrayName)
            array.put(array.get(0))
            rejects(duplicate)
            while (array.length() <= 64) array.put(array.get(0))
            rejects(duplicate)
        }
    }

    @Test fun mergesDistinctCatalogsAndRejectsEitherIdentityCollisionAndAggregateBounds() {
        val first = parse(document())
        val secondDocument = document("second-package", "second-model")
        val second = parse(secondDocument)
        val merged = SpeechModelCatalog.merge(first, second)
        assertEquals(listOf("weights", "second-package"), merged.packages.map { it.id })
        assertEquals(first.model("model"), merged.model("model"))
        assertEquals(second.model("second-model"), merged.model("second-model"))
        assertThrows(IllegalArgumentException::class.java) { SpeechModelCatalog.merge(first, parse(document("weights", "another-model"))) }
        assertThrows(IllegalArgumentException::class.java) { SpeechModelCatalog.merge(first, parse(document("other-package", "model"))) }
        val many = (0..64).map { parse(document("package-$it", "model-$it")) }
        assertThrows(IllegalArgumentException::class.java) { SpeechModelCatalog.merge(*many.toTypedArray()) }
    }

    private fun rejects(document: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { parse(document) }
    }

    private fun parse(document: JSONObject) = SpeechModelCatalog.parse(document.toString().byteInputStream(), RUNTIME, ::decode)

    private fun decode(reader: SpeechModelAdapterReader): SpeechModelAdapter = with(reader) {
        fields("family", "model", "second", "directory", "threads", "scale")
        val family = text("family")
        val model = ref("model")
        intRange("threads", 1..16)
        positiveFloat("scale", 1f)
        when (family) {
            "fixture" -> Unit
            "second" -> ref("second")
            "directory" -> ref("directory", directory = true)
            else -> throw IllegalArgumentException("Unknown compiled fixture family")
        }
        FixtureAdapter(model)
    }

    private fun document(packageId: String = "weights", modelId: String = "model"): JSONObject {
        val files = JSONArray()
        for (path in listOf("model.bin", "data/file.bin")) {
            files.put(JSONObject().put("path", path).put("bytes", 1).put("sha256", "a".repeat(64)))
        }
        val pkg = JSONObject().put("id", packageId).put("displayName", packageId).put("source", "fixture").put("license", "MIT")
            .put("url", "https://models.example/model.tar.bz2").put("allowedRedirectHosts", JSONArray().put("models.example"))
            .put("format", "tar.bz2").put("archiveBytes", 4).put("archiveSha256", "a".repeat(64))
            .put("maxUnpackedBytes", 4).put("files", files)
        val adapter = JSONObject().put("family", "fixture").put("model", ref("model.bin", packageId)).put("threads", 2).put("scale", 0.5)
        val model = JSONObject().put("id", modelId).put("displayName", modelId).put("role", "ASR")
            .put("languages", JSONArray().put("en")).put("sampleRateHz", 16000).put("adapter", adapter)
        return JSONObject().put("schemaVersion", 2).put("runtime", RUNTIME).put("packages", JSONArray().put(pkg)).put("models", JSONArray().put(model))
    }

    private fun ref(path: String, packageId: String = "weights") = JSONObject().put("packageId", packageId).put("path", path)
    private data class FixtureAdapter(val model: SpeechModelRef) : SpeechModelAdapter

    companion object { private const val RUNTIME = "fixture-runtime" }
}
