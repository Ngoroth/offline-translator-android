package com.example.offlinetranslator.speechmodels

import android.content.ContextWrapper
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

    @Test fun recognitionIdsAreDataAndConversationInstallsOnlyCaptureAndChosenOutput() = runBlocking<Unit> {
        val fixture = fixture()
        val requested = CopyOnWriteArrayList<String>()
        TestHttpsServer { exchange ->
            val id = exchange.requestURI.path.removePrefix("/")
            requested += id
            val bytes = fixture.bytes.getValue(id)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { https ->
            val recognitionSelections = listOf(emptySet(), setOf("multilingual"), setOf("identifier", "asr-en", "asr-ru"))
            for (backend in SpeechRecognitionBackend.entries) for (output in SpeechOutputBackend.entries) {
                for (recognitionIds in recognitionSelections) {
                    requested.clear()
                    val store = store(fixture.catalog, SpeechModelDownloader(https::openConnection))
                    val config = AppSettings(speechRecognitionBackend = backend, speechOutputBackend = output).conversationConfig()
                    val resources = SelectedSpeechResources.forConversation(store, config, recognitionIds)
                    try {
                        val owned = setOf(config.vadModelId) + config.ttsModelIds.values
                        assertEquals(owned + recognitionIds, resources.models.value.map { it.id }.toSet())
                        assertFalse(resources.isConversationReady())
                        resources.install()
                        assertTrue(resources.isConversationReady())
                        assertEquals(owned, requested.toSet())
                        assertTrue(store.packages.value.filter { it.id in recognitionIds }.all { it.state == SpeechModelInstallState.MISSING })
                        store.install(fixture.catalog.requiredPackages(recognitionIds))
                        resources.install()
                        assertEquals(owned + recognitionIds, requested.toSet())
                        assertEquals(requested.toSet().size, requested.size)
                        val ready = withTimeout(5000) { resources.models.first { rows -> rows.all { it.state == SpeechModelInstallState.READY } } }
                        assertEquals(owned + recognitionIds, ready.map { it.id }.toSet())
                        assertFalse("unused-voice" in requested)
                        resources.close()
                        assertTrue(store.availability(owned + recognitionIds))
                        assertTrue(runCatching { resources.install() }.isFailure)
                    } finally { resources.close(); store.close() }
                }
            }
        }
    }

    @Test fun rejectsUnavailableWrongRoleAndIncompleteOrForeignRecognitionCoverageBeforeEffects() {
        val fixture = fixture()
        val store = store(fixture.catalog, SpeechModelDownloader { error("invalid selections must not download") })
        var digests = 0
        store.onDigestRead = { _, _ -> digests++ }
        val config = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
        try {
            for (ids in listOf(setOf("missing"), setOf(config.vadModelId), setOf("tts-en-piper-lessac-medium"),
                setOf("asr-en"), setOf("foreign"), setOf("identifier"), setOf("multilingual", "identifier-en"))) {
                assertThrows(IllegalArgumentException::class.java) { SelectedSpeechResources.forConversation(store, config, ids) }
            }
            val wrongVad = AppSettings(vadModelId = "identifier", speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
            assertThrows(IllegalArgumentException::class.java) { SelectedSpeechResources.forConversation(store, wrongVad, emptySet()) }
            assertEquals(0, digests)
            assertTrue(store.packages.value.all { it.state == SpeechModelInstallState.MISSING })
        } finally { store.close() }
    }

    @Test fun metadataProjectionDoesNotDigestOrDownloadRecognitionAndDeduplicatesMultilingualModel() {
        val fixture = fixture()
        val store = store(fixture.catalog, SpeechModelDownloader { error("metadata must not download") })
        var digests = 0
        store.onDigestRead = { _, _ -> digests++ }
        val config = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
        val resources = SelectedSpeechResources.forConversation(store, config, setOf("multilingual"))
        try {
            assertEquals(setOf(config.vadModelId, "multilingual"), resources.models.value.map { it.id }.toSet())
            assertEquals(0, digests)
            assertTrue(resources.models.value.all { it.state == SpeechModelInstallState.MISSING })
            assertEquals(setOf(SpeechModelRole.VAD, SpeechModelRole.ASR), resources.models.value.map { it.role }.toSet())
        } finally { resources.close(); store.close() }
    }

    @Test fun legacyRecognitionProjectionInstallsSelectedDependenciesAndKeepsSharedStoreAlive() = runBlocking<Unit> {
        val fixture = fixture()
        val requested = CopyOnWriteArrayList<String>()
        TestHttpsServer { exchange ->
            val id = exchange.requestURI.path.removePrefix("/")
            requested += id
            val bytes = fixture.bytes.getValue(id)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { https ->
            val store = store(fixture.catalog, SpeechModelDownloader(https::openConnection))
            val pair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
            val resources = SelectedSpeechResources.forRecognition(store, setOf(pair), mapOf(pair.source to "asr-en"), VAD)
            try {
                assertFalse(resources.isReady(pair))
                resources.install()
                assertTrue(resources.isReady(pair))
                assertEquals(setOf(VAD, "asr-en"), requested.toSet())
                assertTrue(runCatching { resources.isReady(pair.reverse()) }.isFailure)
                assertThrows(IllegalArgumentException::class.java) {
                    SelectedSpeechResources.forRecognition(store, setOf(pair), mapOf(pair.source to "asr-ru"), VAD)
                }
                resources.close()
                assertTrue(store.availability(requested.toSet()))
                assertTrue(runCatching { resources.install() }.isFailure)
            } finally { resources.close(); store.close() }
        }
    }

    private fun store(catalog: SpeechModelCatalog, downloader: SpeechModelDownloader): SpeechModelStore {
        val root = temporary.newFolder()
        val context = object : ContextWrapper(null) { override fun getNoBackupFilesDir(): File = root }
        return SpeechModelStore(context, catalog, downloader, SpeechArchiveExtractor(), { Long.MAX_VALUE }, { source, target ->
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        })
    }

    private fun fixture(): Fixture {
        val specifications = listOf(
            Triple(VAD, "VAD", emptyList()),
            Triple("identifier", "LANGUAGE_ID", listOf("en", "ru")),
            Triple("identifier-en", "LANGUAGE_ID", listOf("en")),
            Triple("asr-en", "ASR", listOf("en")),
            Triple("asr-ru", "ASR", listOf("ru")),
            Triple("multilingual", "ASR", listOf("en", "ru", "nl")),
            Triple("foreign", "ASR", listOf("de")),
            Triple("tts-en-piper-lessac-medium", "TTS", listOf("en")),
            Triple("tts-ru-piper-irina-medium", "TTS", listOf("ru")),
            Triple("unused-voice", "TTS", listOf("ru")),
        )
        val packages = JSONArray()
        val models = JSONArray()
        val payloads = specifications.associate { it.first to it.first.toByteArray() }
        for ((id, role, languages) in specifications) {
            val bytes = payloads.getValue(id)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            packages.put(JSONObject().put("id", id).put("displayName", id).put("source", "fixture").put("license", "MIT")
                .put("url", "https://models.example/$id").put("allowedRedirectHosts", JSONArray().put("models.example"))
                .put("format", "raw").put("archiveBytes", bytes.size).put("archiveSha256", hash)
                .put("maxUnpackedBytes", bytes.size).put("maxEntries", 1)
                .put("files", JSONArray().put(JSONObject().put("path", "weights.bin").put("bytes", bytes.size).put("sha256", hash))))
            models.put(JSONObject().put("id", id).put("displayName", id).put("role", role)
                .put("languages", JSONArray(languages)).put("sampleRateHz", 16000)
                .put("adapter", JSONObject().put("family", "fixture")
                    .put("model", JSONObject().put("packageId", id).put("path", "weights.bin"))))
        }
        val document = JSONObject().put("schemaVersion", 2).put("runtime", "fixture-runtime").put("packages", packages).put("models", models)
        val catalog = SpeechModelCatalog.parse(document.toString().byteInputStream(), "fixture-runtime") { reader ->
            reader.fields("family", "model")
            require(reader.text("family") == "fixture")
            FixtureAdapter(reader.ref("model"))
        }
        return Fixture(catalog, payloads)
    }

    private data class Fixture(val catalog: SpeechModelCatalog, val bytes: Map<String, ByteArray>)
    private data class FixtureAdapter(val model: SpeechModelRef) : SpeechModelAdapter
    companion object { private const val VAD = "vad-silero-6.2.2" }
}
