package io.github.ngoroth.offlinetranslator.speechmodels

import android.content.ContextWrapper
import io.github.ngoroth.offlinetranslator.core.AppSettings
import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.SpeechModelInstallState
import io.github.ngoroth.offlinetranslator.core.SpeechModelRole
import io.github.ngoroth.offlinetranslator.core.SpeechOutputBackend
import io.github.ngoroth.offlinetranslator.core.SpeechRecognitionBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    @Test fun selectedPackageTransferCountsStayTruthfulUntilVerificationPublishesReady() = runBlocking<Unit> {
        val fixture = fixture(largePackageBytes = 131_072)
        val release = CompletableDeferred<Unit>()
        TestHttpsServer { exchange ->
            val bytes = fixture.bytes.getValue(exchange.requestURI.path.removePrefix("/"))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output ->
                output.write(bytes, 0, bytes.size / 2)
                output.flush()
                runBlocking { release.await() }
                output.write(bytes, bytes.size / 2, bytes.size - bytes.size / 2)
            }
        }.use { https ->
            val store = store(fixture.catalog, SpeechModelDownloader(https::openConnection))
            val config = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
            val resources = SelectedSpeechResources.forConversation(store, config, setOf("multilingual"))
            val installing = async(Dispatchers.IO) { store.install(setOf("multilingual")) }
            try {
                val row = withTimeout(5000) { resources.downloadPackages.first { rows ->
                    rows.any { it.id == "multilingual" && it.state == SpeechModelInstallState.DOWNLOADING &&
                        it.downloadedBytes > 0 }
                } }.single { it.id == "multilingual" }
                assertEquals(131_072L, row.sizeBytes)
                assertTrue(row.downloadedBytes in 1..65_536L)
                assertEquals(SpeechModelInstallState.DOWNLOADING, row.state)
                assertFalse(resources.downloadPackages.value.any { it.id == "unused-voice" })
                release.complete(Unit)
                installing.await()
                val ready = withTimeout(5000) { resources.downloadPackages.first { rows ->
                    rows.any { it.id == "multilingual" && it.state == SpeechModelInstallState.READY }
                } }.single { it.id == "multilingual" }
                assertEquals(131_072L, ready.sizeBytes)
                assertEquals(0L, ready.downloadedBytes)
            } finally {
                release.complete(Unit)
                installing.cancel()
                resources.close()
                store.close()
            }
        }
    }

    @Test fun downloadPackagesKeepCompositeDependenciesDistinctAndIncludeInstalledSharedResources() = runBlocking<Unit> {
        val fixture = fixture(composite = true)
        TestHttpsServer { exchange ->
            val bytes = fixture.bytes.getValue(exchange.requestURI.path.removePrefix("/"))
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }.use { https ->
            val store = store(fixture.catalog, SpeechModelDownloader(https::openConnection))
            val config = AppSettings(speechOutputBackend = SpeechOutputBackend.PIPER).conversationConfig()
            val resources = SelectedSpeechResources.forConversation(store, config, setOf("multilingual"))
            try {
                store.install(setOf(VAD))
                val base = withTimeout(5000) { resources.downloadPackages.first { rows ->
                    rows.any { it.id == VAD && it.state == SpeechModelInstallState.READY }
                } }
                assertEquals(setOf(VAD, "asr-en", "multilingual"), base.map { it.id }.toSet())
                assertEquals(3, base.size)
                assertEquals(SpeechModelInstallState.MISSING, base.single { it.id == "multilingual" }.state)
                // READY publication resets transfer counters; consumers must use its full archived size.
                assertEquals(0L, base.single { it.id == VAD }.downloadedBytes)
                assertEquals(fixture.bytes.getValue(VAD).size.toLong(), base.single { it.id == VAD }.sizeBytes)
                resources.install(speechOutputRequired = true)
                val withOutput = withTimeout(5000) { resources.downloadPackages.first { rows ->
                    rows.any { it.id == "tts-en-piper-lessac-medium" && it.state == SpeechModelInstallState.READY }
                } }
                val expected = setOf(VAD, "asr-en", "multilingual") + config.ttsModelIds.values
                assertEquals(expected, withOutput.map { it.id }.toSet())
                assertEquals(expected.size, withOutput.size)
                assertFalse(withOutput.any { it.id == "unused-voice" || it.id == "asr-ru" })
                resources.isConversationReady(speechOutputRequired = false)
                val silent = withTimeout(5000) { resources.downloadPackages.first { rows ->
                    rows.none { it.id in config.ttsModelIds.values }
                } }
                assertEquals(setOf(VAD, "asr-en", "multilingual"), silent.map { it.id }.toSet())
                // Logical model metadata stays separate from package inventory.
                assertEquals(setOf(VAD, "multilingual"), resources.models.first { it.size == 2 }.map { it.id }.toSet())
            } finally { resources.close(); store.close() }
        }
    }

    @Test fun systemRecognitionAndSystemOutputDoNotInventDownloadPackages() = runBlocking<Unit> {
        val fixture = fixture()
        val store = store(fixture.catalog, SpeechModelDownloader { error("checking must not download") })
        val config = AppSettings(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
        val resources = SelectedSpeechResources.forConversation(store, config, emptySet())
        try {
            assertFalse(resources.isConversationReady(speechOutputRequired = true))
            assertEquals(listOf(VAD), resources.downloadPackages.value.map { it.id })
            assertEquals(SpeechModelInstallState.MISSING, resources.downloadPackages.value.single().state)
        } finally { resources.close(); store.close() }
    }

    @Test fun silentUnsupportedPiperPairPreparesOnlyBaseResourcesWithoutResolvingVoices() = runBlocking<Unit> {
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
            val config = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
                speechOutputBackend = SpeechOutputBackend.PIPER, speechOutputEnabled = false).conversationConfig()
            val resources = SelectedSpeechResources.forConversation(store, config, setOf("multilingual"))
            try {
                assertFalse(resources.isConversationReady(speechOutputRequired = false))
                resources.install(speechOutputRequired = false)
                assertTrue(resources.isConversationReady(speechOutputRequired = false))
                assertEquals(setOf(VAD), requested.toSet())
                val ready = withTimeout(5000) { resources.models.first { rows ->
                    rows.any { it.id == VAD && it.state == SpeechModelInstallState.READY }
                } }
                assertEquals(setOf(VAD, "multilingual"), ready.map { it.id }.toSet())
                assertTrue(ready.none { it.role == SpeechModelRole.TTS })
                assertTrue(runCatching { resources.isConversationReady(speechOutputRequired = true) }.exceptionOrNull()
                    is io.github.ngoroth.offlinetranslator.core.InvalidConfigurationException)
                assertEquals(setOf(VAD), requested.toSet())
            } finally { resources.close(); store.close() }
        }
    }

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
            val recognitionSelections = listOf(emptySet(), setOf("multilingual"), setOf("asr-en", "asr-ru"))
            for (backend in SpeechRecognitionBackend.entries) for (output in SpeechOutputBackend.entries) {
                for (recognitionIds in recognitionSelections) {
                    requested.clear()
                    val store = store(fixture.catalog, SpeechModelDownloader(https::openConnection))
                    val config = AppSettings(speechRecognitionBackend = backend, speechOutputBackend = output).conversationConfig()
                    val resources = SelectedSpeechResources.forConversation(store, config, recognitionIds)
                    try {
                        val owned = setOf(config.vadModelId) + config.ttsModelIds.values
                        assertFalse(resources.isConversationReady(speechOutputRequired = true))
                        resources.install(speechOutputRequired = true)
                        assertTrue(resources.isConversationReady(speechOutputRequired = true))
                        assertEquals(owned, requested.toSet())
                        assertTrue(store.packages.value.filter { it.id in recognitionIds }.all { it.state == SpeechModelInstallState.MISSING })
                        store.install(fixture.catalog.requiredPackages(recognitionIds))
                        resources.install(speechOutputRequired = true)
                        assertEquals(owned + recognitionIds, requested.toSet())
                        assertEquals(requested.toSet().size, requested.size)
                        val ready = withTimeout(5000) { resources.models.first { rows ->
                            rows.map { it.id }.toSet() == owned + recognitionIds &&
                                rows.all { it.state == SpeechModelInstallState.READY }
                        } }
                        assertEquals(owned + recognitionIds, ready.map { it.id }.toSet())
                        assertFalse("unused-voice" in requested)
                        resources.close()
                        assertTrue(store.availability(owned + recognitionIds))
                        assertTrue(runCatching { resources.install(speechOutputRequired = true) }.isFailure)
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
                setOf("asr-en"), setOf("foreign"), setOf("multilingual", "foreign"))) {
                assertThrows(IllegalArgumentException::class.java) { SelectedSpeechResources.forConversation(store, config, ids) }
            }
            val wrongVad = AppSettings(vadModelId = "asr-en", speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()
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

    private fun fixture(composite: Boolean = false, largePackageBytes: Int = 0): Fixture {
        val specifications = listOf(
            Triple(VAD, "VAD", emptyList()),
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
        val payloads = specifications.associate { (id) ->
            id to if (id == "multilingual" && largePackageBytes > 0) ByteArray(largePackageBytes) { 7 }
                else id.toByteArray()
        }
        for ((id, role, languages) in specifications) {
            val bytes = payloads.getValue(id)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            packages.put(JSONObject().put("id", id).put("displayName", id).put("source", "fixture").put("license", "MIT")
                .put("url", "https://models.example/$id").put("allowedRedirectHosts", JSONArray().put("models.example"))
                .put("format", "raw").put("archiveBytes", bytes.size).put("archiveSha256", hash)
                .put("maxUnpackedBytes", bytes.size).put("maxEntries", 1)
                .put("files", JSONArray().put(JSONObject().put("path", "weights.bin").put("bytes", bytes.size).put("sha256", hash))))
            val adapter = JSONObject().put("family", if (composite && id == "multilingual") "composite" else "fixture")
                .put("model", JSONObject().put("packageId", id).put("path", "weights.bin"))
            if (composite && id == "multilingual") {
                adapter.put("sharedVad", JSONObject().put("packageId", VAD).put("path", "weights.bin"))
                adapter.put("sharedAsr", JSONObject().put("packageId", "asr-en").put("path", "weights.bin"))
            }
            models.put(JSONObject().put("id", id).put("displayName", id).put("role", role)
                .put("languages", JSONArray(languages)).put("sampleRateHz", 16000).put("adapter", adapter))
        }
        val document = JSONObject().put("schemaVersion", 2).put("runtime", "fixture-runtime").put("packages", packages).put("models", models)
        val catalog = SpeechModelCatalog.parse(document.toString().byteInputStream(), "fixture-runtime") { reader ->
            reader.fields("family", "model", "sharedVad", "sharedAsr")
            val model = reader.ref("model")
            if (reader.text("family") == "composite") {
                reader.ref("sharedVad")
                reader.ref("sharedAsr")
            } else require(reader.text("family") == "fixture")
            FixtureAdapter(model)
        }
        return Fixture(catalog, payloads)
    }

    private data class Fixture(val catalog: SpeechModelCatalog, val bytes: Map<String, ByteArray>)
    private data class FixtureAdapter(val model: SpeechModelRef) : SpeechModelAdapter
    companion object { private const val VAD = "vad-silero-6.2.2" }
}
