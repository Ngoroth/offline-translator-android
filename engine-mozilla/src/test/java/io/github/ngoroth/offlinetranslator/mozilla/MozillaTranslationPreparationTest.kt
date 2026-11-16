package io.github.ngoroth.offlinetranslator.mozilla

import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class MozillaTranslationPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
    private val pairs = setOf(pair, pair.reverse())

    @Test fun localPreparationNeverInstallsOrOpensNetworkAndMissingEngineCannotTranslate() = runBlocking {
        val fixture = Fixture()
        val preparation = fixture.store()
        assertNull(preparation.localConfigurations(pairs))
        assertEquals(0, fixture.opened.size)
        assertFalse(File(fixture.root, "mozilla-translation").exists())
        val engine = MozillaTranslationEngine(pairs, preparation)
        try {
            assertEquals(ModelAvailability.MISSING, engine.prepareLocal())
            assertThrows(TranslationFailure.ModelMissing::class.java) {
                runBlocking { engine.translate(pair, "Where is the station?") }
            }
            assertEquals(0, fixture.opened.size)
        } finally { engine.closeAndJoin() }
    }

    @Test fun completeSelectedUnionIsVerifiedPublishedAndReusedAcrossColdOfflineRestart() = runBlocking {
        val fixture = Fixture()
        val configs = fixture.store().prepareConfigurations(pairs)
        assertEquals(setOf("en-ru", "ru-en"), configs.keys)
        val files = fixture.catalog.files(fixture.catalog.models(pairs))
        assertEquals(files.map { it.url }.toSet(), fixture.opened.map { it.toString() }.toSet())
        files.forEach { file -> assertArrayEquals(fixture.plain.getValue(file.url), File(fixture.objects, file.installedName).readBytes()) }
        fixture.opened.clear()
        val offline = fixture.store(wifi = false, open = { throw AssertionError("Offline path opened HTTP") })
        assertEquals(configs, offline.localConfigurations(pairs))
        assertEquals(configs, offline.prepareConfigurations(pairs))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun explicitRawAttachmentKeepsThePinnedBytesAndNeverFallsBackOnCorruption() = runBlocking {
        val fixture = Fixture()
        val forward = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.CHINESE)
        val selected = setOf(forward, forward.reverse())
        val raw = fixture.catalog.files(fixture.catalog.models(selected)).single { !it.gzip }
        val original = fixture.plain.getValue(raw.url)
        fixture.wire[raw.url] = original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertThrows(TranslationFailure.ArtifactCorrupt::class.java) {
            runBlocking { fixture.store().prepareConfigurations(selected) }
        }
        assertEquals(listOf(raw.url), fixture.opened.map { it.toString() })
        assertFalse(File(fixture.objects, raw.installedName).exists())
        fixture.opened.clear()
        assertNull(fixture.store(wifi = false, open = { throw AssertionError("Local repair opened HTTP") })
            .localConfigurations(selected))
        fixture.wire[raw.url] = original
        val prepared = fixture.store().prepareConfigurations(selected)
        assertEquals(setOf("en-zh", "zh-en"), prepared.keys)
        assertArrayEquals(original, File(fixture.objects, raw.installedName).readBytes())
    }

    @Test fun replacedCorruptBytesStayNonReadyUntilAnExplicitSingleFileRepair() = runBlocking {
        val fixture = Fixture()
        val store = fixture.store()
        store.prepareConfigurations(pairs)
        val file = fixture.catalog.files(fixture.catalog.models(pairs)).first()
        val installed = File(fixture.objects, file.installedName)
        val corrupt = File(fixture.objects, "replacement")
        corrupt.writeBytes(ByteArray(file.bytes.toInt()) { 0 })
        Files.move(corrupt.toPath(), installed.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        fixture.opened.clear()
        assertThrows(TranslationFailure.ArtifactCorrupt::class.java) { runBlocking { store.localConfigurations(pairs) } }
        assertTrue(fixture.opened.isEmpty())
        store.prepareConfigurations(pairs)
        assertEquals(listOf(file.url), fixture.opened.map { it.toString() })
        assertArrayEquals(fixture.plain.getValue(file.url), installed.readBytes())
    }

    @Test fun cellularStorageAndForeignRedirectRefusalsCannotPublishModels() = runBlocking {
        val cellular = Fixture()
        assertThrows(PreparationFailure.WifiRequired::class.java) { runBlocking { cellular.store(wifi = false).prepareConfigurations(pairs) } }
        assertTrue(cellular.opened.isEmpty())
        val full = Fixture()
        assertThrows(TranslationFailure.Storage::class.java) { runBlocking { full.store(space = 0).prepareConfigurations(pairs) } }
        assertTrue(full.opened.isEmpty())
        val redirect = Fixture()
        assertThrows(TranslationFailure.Storage::class.java) {
            runBlocking { redirect.store(open = { url ->
                redirect.opened.add(url)
                Response(url, byteArrayOf(), status = 302, location = "https://untrusted.example/model.gz")
            }).prepareConfigurations(pairs) }
        }
        assertEquals(1, redirect.opened.size)
        assertTrue(redirect.objects.listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongDigestTruncatedAndExpandedPayloadsLeaveNoPublishedOrStagingFile() = runBlocking {
        for (kind in listOf("digest", "truncated", "expanded")) {
            val fixture = Fixture()
            val first = fixture.catalog.files(fixture.catalog.models(pairs)).first()
            val original = fixture.plain.getValue(first.url)
            val bad = when (kind) {
                "digest" -> gzip(ByteArray(original.size) { 0 })
                "truncated" -> fixture.wire.getValue(first.url).dropLast(5).toByteArray()
                else -> gzip(original + original)
            }
            // Pin the malicious wire length so this exercises gzip/content bounds, not only HTTP headers.
            fixture.replaceWireLength(first.url, bad.size)
            val store = fixture.store(open = { url -> Response(url, if (url.toString() == first.url) bad else fixture.wire.getValue(url.toString())) })
            assertThrows(TranslationFailure::class.java) { runBlocking { store.prepareConfigurations(pairs) } }
            assertTrue(fixture.objects.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun changedGeneratedConfigRequiresExplicitPreparationAndLocalCheckDoesNotRepairIt() = runBlocking {
        val fixture = Fixture()
        val store = fixture.store()
        val configs = store.prepareConfigurations(pairs)
        val config = File(configs.getValue("en-ru"))
        config.writeText("models: [untrusted]\n")
        assertThrows(TranslationFailure.ArtifactCorrupt::class.java) { runBlocking { store.localConfigurations(pairs) } }
        assertEquals("models: [untrusted]\n", config.readText())
        fixture.opened.clear()
        store.prepareConfigurations(pairs)
        assertTrue(config.readText().contains("gemm-precision: int8shiftAll"))
        assertTrue(fixture.opened.isEmpty())
    }

    @Test fun cancellationDisconnectsBlockedReadAndJoinsPrivateStageCleanup() = runBlocking {
        val fixture = Fixture()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = fixture.store(open = { url ->
            object : Response(url, fixture.wire.getValue(url.toString())) {
                override fun getInputStream(): InputStream = object : InputStream() {
                    override fun read(): Int {
                        reading.countDown()
                        if (!release.await(10, TimeUnit.SECONDS)) throw IOException("Read deadline")
                        throw IOException("Disconnected")
                    }
                }
                override fun disconnect() { release.countDown() }
            }
        })
        val active = launch(Dispatchers.Default) { store.prepareConfigurations(pairs) }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            withTimeout(10_000) { active.cancelAndJoin() }
            assertEquals(TranslationPreparationPhase.MISSING, store.state.value.phase)
            assertTrue(fixture.objects.listFiles().orEmpty().isEmpty())
        } finally {
            release.countDown()
            active.cancelAndJoin()
        }
    }

    private inner class Fixture {
        val root: File = temporary.newFolder()
        val objects get() = File(root, "mozilla-translation/objects")
        val opened = mutableListOf<URL>()
        val plain = linkedMapOf<String, ByteArray>()
        val wire = linkedMapOf<String, ByteArray>()
        private val json = JSONObject(requireNotNull(javaClass.getResourceAsStream("/mozilla-translation-catalog.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        var catalog: MozillaTranslationCatalog
            private set

        init {
            val rows = json.getJSONArray("routes")
            val originalRawAttachments = json.optJSONObject("rawAttachments") ?: JSONObject()
            val fixtureRawAttachments = JSONObject()
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val files = row.getJSONArray("files")
                for (fileIndex in 0 until files.length()) {
                    val file = files.getJSONArray(fileIndex)
                    val bytes = "${row.getString("source")}/${row.getString("target")}/${file.getString(0)}: verified model fixture".toByteArray()
                    val rawUrl = originalRawAttachments.optString(file.getString(4)).takeIf { it.isNotEmpty() }
                    val encoded = if (rawUrl == null) gzip(bytes) else bytes
                    val url = rawUrl ?: "${json.getString("baseUrl")}/${row.getString("directory")}${file.getString(1)}.gz?generation=${file.getString(5)}"
                    val hash = hash(bytes)
                    if (rawUrl != null) fixtureRawAttachments.put(hash, rawUrl)
                    file.put(2, encoded.size).put(3, bytes.size).put(4, hash)
                    plain[url] = bytes
                    wire[url] = encoded
                }
            }
            json.put("rawAttachments", fixtureRawAttachments)
            catalog = MozillaTranslationCatalog.parse(json.toString())
        }

        fun replaceWireLength(url: String, length: Int) {
            val rows = json.getJSONArray("routes")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val files = row.getJSONArray("files")
                for (fileIndex in 0 until files.length()) {
                    val file = files.getJSONArray(fileIndex)
                    val candidate = "${json.getString("baseUrl")}/${row.getString("directory")}${file.getString(1)}.gz?generation=${file.getString(5)}"
                    if (candidate == url) file.put(2, length)
                }
            }
            catalog = MozillaTranslationCatalog.parse(json.toString())
        }

        fun store(wifi: Boolean = true, space: Long = Long.MAX_VALUE, open: (URL) -> HttpURLConnection = { url ->
            opened.add(url)
            Response(url, wire.getValue(url.toString()))
        }) = MozillaTranslationPreparation(root, catalog, { wifi }, open, { "# exact fixture prefix\n".toByteArray() }, { space })
    }

    private open class Response(
        url: URL,
        private val bytes: ByteArray,
        private val status: Int = 200,
        private val location: String? = null,
    ) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = when (name) {
            "Content-Length" -> bytes.size.toString()
            "Location" -> location
            else -> null
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
}
