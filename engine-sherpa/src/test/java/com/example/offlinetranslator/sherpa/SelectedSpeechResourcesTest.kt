package com.example.offlinetranslator.sherpa

import android.content.ContextWrapper
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class SelectedSpeechResourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recommendedSelectionIgnoresAlternativesAndRefusesUnavailableDefinitions() = runBlocking<Unit> {
        val source = File("src/main/assets/speech-models.json").readText()
        val root = JSONObject(source)
        val models = root.getJSONArray("models")
        val voice = (0 until models.length()).map(models::getJSONObject)
            .first { it.getString("id") == "tts-ru-piper-irina-medium" }
        models.put(JSONObject(voice.toString()).put("id", "alternate-ru"))
        val catalog = SpeechModelCatalog.parse(root.toString().byteInputStream())
        val store = store(catalog, SpeechModelDownloader { error("readiness must not download") })
        val settings = AppSettings(
            languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
        )
        val resources = SelectedSpeechResources.forConversation(store, settings.conversationConfig())
        try {
            val selectedVoices = resources.models.value.filter { it.role == com.example.offlinetranslator.core.SpeechModelRole.TTS }
            assertEquals(setOf("tts-ru-piper-irina-medium", "tts-en-piper-lessac-medium"), selectedVoices.map { it.id }.toSet())
            val selectedRecognizers = resources.models.value.filter { it.role == com.example.offlinetranslator.core.SpeechModelRole.ASR }
            assertEquals(setOf("asr-ru-gigaam-v1", "asr-en-whisper-tiny.en"), selectedRecognizers.map { it.id }.toSet())
            assertFalse(resources.isConversationReady())
            assertTrue(selectedVoices.all { it.state == SpeechModelInstallState.MISSING })
        } finally { resources.close(); store.close() }
        for (wrongRole in listOf(false, true)) {
            val altered = JSONObject(source)
            val entries = altered.getJSONArray("models")
            val index = (0 until entries.length()).first { entries.getJSONObject(it).getString("id") == "tts-ru-piper-irina-medium" }
            if (wrongRole) {
                val recognizer = (0 until entries.length()).map(entries::getJSONObject).first { it.getString("role") == "ASR" }
                entries.put(index, JSONObject(recognizer.toString()).put("id", "tts-ru-piper-irina-medium"))
            } else entries.remove(index)
            val unavailable = store(SpeechModelCatalog.parse(altered.toString().byteInputStream()),
                SpeechModelDownloader { error("invalid selection must not download") })
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    SelectedSpeechResources.forConversation(unavailable, settings.conversationConfig())
                }
            } finally { unavailable.close() }
        }
        for (failure in listOf("missing", "role", "language")) {
            val altered = JSONObject(source)
            val entries = altered.getJSONArray("models")
            val index = (0 until entries.length()).first { entries.getJSONObject(it).getString("id") == "asr-en-whisper-tiny.en" }
            when (failure) {
                "missing" -> entries.remove(index)
                "role" -> {
                    val voiceDefinition = (0 until entries.length()).map(entries::getJSONObject).first { it.getString("role") == "TTS" }
                    entries.put(index, JSONObject(voiceDefinition.toString()).put("id", "asr-en-whisper-tiny.en"))
                }
                "language" -> {
                    val russian = (0 until entries.length()).map(entries::getJSONObject).first { it.getString("id") == "asr-ru-gigaam-v1" }
                    entries.put(index, JSONObject(russian.toString()).put("id", "asr-en-whisper-tiny.en"))
                }
            }
            val unavailable = store(SpeechModelCatalog.parse(altered.toString().byteInputStream()),
                SpeechModelDownloader { error("invalid selection must not download") })
            try {
                assertThrows(IllegalArgumentException::class.java) {
                    SelectedSpeechResources.forConversation(unavailable, settings.conversationConfig())
                }
                assertThrows(IllegalArgumentException::class.java) {
                    SherpaConversationRecognitionEngine(unavailable, settings.conversationConfig())
                }
                assertTrue(unavailable.packages.value.all { it.state == SpeechModelInstallState.MISSING })
            } finally { unavailable.close() }
        }
    }

    @Test fun conversationPreparationDownloadsOnlyChosenPackagesOnce() = runBlocking<Unit> {
        val root = JSONObject(File("src/main/assets/speech-models.json").readText())
        val packages = root.getJSONArray("packages")
        val payloads = mutableMapOf<String, ByteArray>()
        // Preserve real model references while replacing archive payloads with tiny inventoried files.
        for (index in 0 until packages.length()) {
            val pkg = packages.getJSONObject(index)
            val id = pkg.getString("id")
            val files = pkg.getJSONArray("files")
            // This fixture exercises planning/installation; every package is a tar fixture below.
            val content = java.io.ByteArrayOutputStream()
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(content).use { tar ->
                for (f in 0 until files.length()) {
                    val file = files.getJSONObject(f)
                    val bytes = file.getString("path").toByteArray()
                    file.put("bytes", bytes.size).put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    tar.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry("$id/${file.getString("path")}").apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
            val compressed = java.io.ByteArrayOutputStream()
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(compressed).use { it.write(content.toByteArray()) }
            val bytes = compressed.toByteArray()
            payloads[id] = bytes
            pkg.put("format", "tar.bz2").put("archiveBytes", bytes.size)
                .put("archiveSha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                .put("maxUnpackedBytes", content.size() * 2L).put("maxEntries", files.length())
                .put("url", "https://models.example/$id").put("allowedRedirectHosts", JSONArray().put("models.example"))
        }
        val catalog = SpeechModelCatalog.parse(root.toString().byteInputStream())
        val requests = CopyOnWriteArrayList<String>()
        TestHttpsServer { exchange ->
            val id = exchange.requestURI.path.removePrefix("/")
            requests += id
            val bytes = payloads.getValue(id)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { https ->
            for (recognitionBackend in SpeechRecognitionBackend.entries) for (backend in SpeechOutputBackend.entries) {
                requests.clear()
                val digested = CopyOnWriteArrayList<String>()
                val store = store(catalog, SpeechModelDownloader(https::openConnection))
                store.onDigestRead = { id, _ -> digested += id }
                val config = AppSettings(speechOutputBackend = backend, speechRecognitionBackend = recognitionBackend).conversationConfig()
                val resources = SelectedSpeechResources.forConversation(store, config)
                val output = if (backend == SpeechOutputBackend.PIPER)
                    SherpaConversationSpeechOutput(object : ContextWrapper(null) {}, store, config) else null
                val recognition = if (recognitionBackend == SpeechRecognitionBackend.SHERPA)
                    SherpaConversationRecognitionEngine(store, config) else null
                try {
                    assertFalse(resources.isConversationReady())
                    if (output != null) assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, output.readiness())
                    assertTrue(requests.isEmpty())
                    resources.install()
                    assertTrue(resources.isConversationReady())
                    val voices = if (backend == SpeechOutputBackend.PIPER)
                        setOf("ru-piper-irina-medium", "en-piper-lessac-medium") else emptySet()
                    assertEquals(setOf("vad-silero-6.2.2") + voices, requests.toSet())
                    if (recognition != null) {
                        assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, recognition.readiness())
                        recognition.install()
                        assertEquals(SpeechRecognitionReadiness.READY, recognition.readiness())
                    }
                    if (output != null) assertEquals(SpeechOutputReadiness.READY, output.readiness())
                    resources.install()
                    val recognitionPackages = if (recognitionBackend == SpeechRecognitionBackend.SHERPA)
                        setOf("ru-gigaam-v1-ctc-offline-2024-10-24", "en-whisper-tiny.en-offline",
                            "language-whisper-tiny-encoder", "language-whisper-tiny-decoder") else emptySet()
                    val common = setOf("vad-silero-6.2.2") + recognitionPackages
                    assertEquals(common + voices, requests.toSet())
                    assertEquals(requests.toSet().size, requests.size)
                    assertTrue(digested.all { it in common + voices })
                    if (backend == SpeechOutputBackend.SYSTEM_OFFLINE) {
                        assertFalse(resources.models.value.any { it.role == com.example.offlinetranslator.core.SpeechModelRole.TTS })
                        assertTrue(store.packages.value.filter { it.id.contains("piper") }.all { it.state == SpeechModelInstallState.MISSING })
                    }
                    if (recognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE) {
                        assertFalse(resources.models.value.any { it.role == com.example.offlinetranslator.core.SpeechModelRole.ASR ||
                            it.role == com.example.offlinetranslator.core.SpeechModelRole.LANGUAGE_ID })
                    }
                    assertFalse("whisper-tiny-tokens" in requests)
                } finally {
                    try { recognition?.closeAndJoin(); output?.closeAndJoin() } finally { resources.close(); store.close() }
                }
            }
        }
    }

    @Test fun metadataAndSelectedWrappersDoNotDigestDownloadOrConstructNativeModels() {
        val catalog = File("src/main/assets/speech-models.json").inputStream().use(SpeechModelCatalog::parse)
        var digests = 0
        val store = store(catalog, SpeechModelDownloader { error("metadata must not download") }).also {
            it.onDigestRead = { _, _ -> digests++ }
        }
        val defaults = AppSettings()
        val selected = SelectedSpeechResources.forConversation(store, defaults.conversationConfig())
        val alternate = SelectedSpeechResources.forRecognition(store,
            setOf(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)),
            mapOf(LanguageCatalog.ENGLISH to "asr-en-whisper-tiny"), defaults.vadModelId)
        try {
            assertEquals(0, digests)
            assertEquals(setOf(defaults.vadModelId) + defaults.conversationConfig().ttsModelIds.values,
                selected.models.value.map { it.id }.toSet())
            assertFalse(alternate.models.value.any { it.id == "asr-en-whisper-tiny.en" })
            assertTrue(alternate.models.value.any { it.id == "asr-en-whisper-tiny" })
            assertTrue(selected.models.value.all { it.state == SpeechModelInstallState.MISSING })
            assertTrue(selected.models.value.all { it.role != null })
        } finally { selected.close(); alternate.close(); store.close() }
    }

    @Test fun preparationDownloadsOnlySelectedDependenciesAndWrapperCloseDoesNotCloseSharedStore() = runBlocking<Unit> {
        val ids = listOf("vad-package", "chosen-weights", "chosen-tokens", "other-weights", "other-tokens")
        val bytes = ids.associateWith { it.toByteArray() }
        val catalog = fixtureCatalog(bytes)
        val requested = CopyOnWriteArrayList<String>()
        val digested = CopyOnWriteArrayList<String>()
        TestHttpsServer { exchange ->
            val id = exchange.requestURI.path.removePrefix("/")
            requested += id
            val body = bytes.getValue(id)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }.use { https ->
            val store = store(catalog, SpeechModelDownloader(https::openConnection))
            store.onDigestRead = { id, _ -> digested += id }
            val pair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)
            val resources = SelectedSpeechResources.forRecognition(store, setOf(pair),
                mapOf(LanguageCatalog.ENGLISH to "chosen-asr"), "fixture-vad")
            try {
                assertFalse(resources.isReady(pair))
                resources.install()
                assertTrue(resources.isReady(pair))
                val ready = withTimeout(5_000) { resources.models.first { models -> models.all { it.state == SpeechModelInstallState.READY } } }
                assertEquals(setOf("fixture-vad", "chosen-asr"), ready.map { it.id }.toSet())
                assertEquals(setOf("vad-package", "chosen-weights", "chosen-tokens"), requested.toSet())
                assertTrue(digested.isNotEmpty())
                assertTrue(digested.all { it in requested })
                assertTrue(store.packages.value.filter { it.id.startsWith("other-") }.all { it.state == SpeechModelInstallState.MISSING })
                assertTrue(runCatching { resources.isReady(pair.reverse()) }.isFailure)
                resources.close()
                assertTrue(store.availability(requested.toSet()))
                assertTrue(runCatching { resources.install() }.isFailure)
            } finally { resources.close(); store.close() }
        }
    }

    @Test fun wrongRoleAndUnavailableLanguageRejectWithoutAnySpeechEffects() {
        val catalog = File("src/main/assets/speech-models.json").inputStream().use(SpeechModelCatalog::parse)
        val store = store(catalog, SpeechModelDownloader { error("invalid configuration must not download") })
        val defaults = AppSettings()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SelectedSpeechResources.forConversation(store, defaults.copy(vadModelId = defaults.languageIdModelId).conversationConfig())
            }
            assertThrows(IllegalArgumentException::class.java) {
                SelectedSpeechResources.forRecognition(store, setOf(LanguagePair(LanguageCatalog.GERMAN, LanguageCatalog.ENGLISH)),
                    mapOf(LanguageCatalog.GERMAN to "asr-en-whisper-tiny"), defaults.vadModelId)
            }
            assertTrue(store.packages.value.all { it.state == SpeechModelInstallState.MISSING })
        } finally { store.close() }
    }

    private fun store(catalog: SpeechModelCatalog, downloader: SpeechModelDownloader): SpeechModelStore {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(null) { override fun getNoBackupFilesDir(): File = root }
        return SpeechModelStore(context, catalog, downloader, SpeechArchiveExtractor(), { Long.MAX_VALUE }, { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        })
    }

    private fun fixtureCatalog(bytes: Map<String, ByteArray>): SpeechModelCatalog {
        val packages = JSONArray()
        bytes.forEach { (id, content) ->
            val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            packages.put(JSONObject().put("id", id).put("displayName", id).put("source", "fixture").put("license", "MIT")
                .put("url", "https://models.example/$id").put("allowedRedirectHosts", JSONArray().put("models.example"))
                .put("format", "raw").put("archiveBytes", content.size).put("archiveSha256", hash)
                .put("maxUnpackedBytes", content.size).put("maxEntries", 1)
                .put("files", JSONArray().put(JSONObject().put("path", "weights.bin").put("bytes", content.size).put("sha256", hash))))
        }
        fun ref(id: String) = JSONObject().put("packageId", id).put("path", "weights.bin")
        val models = JSONArray().put(JSONObject().put("id", "fixture-vad").put("displayName", "VAD").put("role", "VAD")
            .put("languages", JSONArray()).put("sampleRateHz", 16_000).put("adapter", JSONObject().put("family", "silero")
                .put("model", ref("vad-package")).put("numThreads", 1).put("threshold", .5)
                .put("minSpeechDuration", .25).put("maxSpeechDuration", 14).put("windowSize", 512)))
        for (name in listOf("chosen", "other")) models.put(JSONObject().put("id", "$name-asr").put("displayName", "$name ASR")
            .put("role", "ASR").put("languages", JSONArray().put("en")).put("sampleRateHz", 16_000)
            .put("adapter", JSONObject().put("family", "nemoCtcOffline").put("model", ref("$name-weights"))
                .put("tokens", ref("$name-tokens")).put("numThreads", 4).put("featureDim", 80)))
        return SpeechModelCatalog.parse(JSONObject().put("schemaVersion", 2).put("runtime", "sherpa-onnx-1.13.8")
            .put("packages", packages).put("models", models).toString().byteInputStream())
    }
}
