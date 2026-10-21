package com.example.offlinetranslator.tencent

import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.PreparationFailure
import com.example.offlinetranslator.core.TranslationFailure
import com.example.offlinetranslator.core.TranslationPreparationPhase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TencentArtifactPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = ByteArray(128 * 1024) { (it * 31).toByte() }
    private val pin get() = TencentTranslationArtifact("https://huggingface.co/pinned.gguf", bytes.size.toLong(), hash(bytes))

    @Test fun unavailableTrustedRootReportsTypedFailureAndRecoversWithoutBreakingMetadata() = runBlocking {
        val root = File(temporary.root, "unavailable-private-root")
        val connections = AtomicInteger()
        val selected = store(root) { connections.incrementAndGet(); Response(it, bytes) }
        val engine = TencentTranslationEngine(
            setOf(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)), selected)
        try {
            assertEquals(ModelAvailability.MISSING, engine.modelAvailability())
            assertEquals(TranslationPreparationPhase.FAILED, engine.preparation.value.phase)
            assertTrue(engine.preparation.value.lastFailure is TranslationFailure.Storage)
            assertTrue(runCatching { selected.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
            assertEquals(TranslationPreparationPhase.FAILED, selected.state.value.phase)
            assertEquals(0, connections.get())
            assertTrue(root.mkdir())
            assertEquals(ModelAvailability.MISSING, engine.modelAvailability())
            assertEquals(TranslationPreparationPhase.MISSING, engine.preparation.value.phase)
            val installed = selected.prepare()
            assertArrayEquals(bytes, installed.readBytes())
            assertEquals(installed, selected.verifiedFile())
            assertEquals(1, connections.get())
        } finally { engine.closeAndJoin() }
    }

    @Test fun trustedRootAliasAllowsInstallationAndColdOfflineReuse() = runBlocking {
        val root = temporary.newFolder()
        val alias = File(temporary.root, "private-root-alias")
        Files.createSymbolicLink(alias.toPath(), root.toPath())
        val connections = AtomicInteger()
        val first = store(alias) { connections.incrementAndGet(); Response(it, bytes) }
        assertNull(first.verifiedFile())
        val installed = first.prepare()
        assertEquals(File(root, "tencent-translation/Hy-MT2-1.8B-Q4_K_M.gguf"), installed)
        assertArrayEquals(bytes, installed.readBytes())
        val restarted = TencentArtifactPreparation(alias, pin, { false }, { error("Cold reuse must remain offline") })
        assertEquals(installed, restarted.verifiedFile())
        assertEquals(installed, restarted.prepare())
        assertEquals(1, connections.get())
    }

    @Test fun providerOwnedDirectoryModelAndStagingLinksRemainRejected() = runBlocking {
        for (relativePath in listOf("tencent-translation", "tencent-translation/Hy-MT2-1.8B-Q4_K_M.gguf",
            "tencent-translation/model.part")) {
            val root = temporary.newFolder()
            val outside = if (relativePath == "tencent-translation") temporary.newFolder()
                else temporary.newFile().apply { writeBytes(bytes) }
            val link = File(root, relativePath)
            link.parentFile.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            val selected = store(root) { error("Unsafe storage must not connect") }
            if (!relativePath.endsWith("model.part")) {
                assertTrue(runCatching { selected.verifiedFile() }.exceptionOrNull() is TranslationFailure.Storage)
            }
            assertTrue(runCatching { selected.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
            if (outside.isFile) assertArrayEquals(bytes, outside.readBytes())
        }
    }

    @Test fun crashLeftStageIsNotReadyAndExplicitPreparationReclaimsItsSpace() = runBlocking {
        val root = temporary.newFolder()
        val staging = File(root, "tencent-translation/model.part").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val store = TencentArtifactPreparation(root, pin, { true }, { Response(it, bytes) },
            { if (staging.exists()) 0L else pin.bytes + 16 * 1024 * 1024L })
        assertNull(store.verifiedFile())
        val installed = store.prepare()
        assertArrayEquals(bytes, installed.readBytes())
        assertFalse(staging.exists())
    }

    @Test fun verifiedPublicationSurvivesColdRestartWithoutNetworkOrSecondCopy() = runBlocking {
        val root = temporary.newFolder()
        val connections = AtomicInteger()
        val first = store(root) { connections.incrementAndGet(); Response(it, bytes) }
        val file = first.prepare()
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(root, "tencent-translation/model.part").exists())
        val restarted = TencentArtifactPreparation(root, pin, { false }, { error("Offline attestation must not connect") })
        assertEquals(file, restarted.verifiedFile())
        assertEquals(file, restarted.prepare())
        assertEquals(1, connections.get())
    }

    @Test fun wrongDigestTruncationAndOversizeNeverPublish() = runBlocking {
        val wrong = bytes.copyOf().also { it[20] = (it[20] + 1).toByte() }
        for (body in listOf(wrong, bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            val root = temporary.newFolder()
            val store = store(root) { Response(it, body, declaredLength = null) }
            assertTrue(runCatching { store.prepare() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
            assertFalse(File(root, "tencent-translation/Hy-MT2-1.8B-Q4_K_M.gguf").exists())
            assertFalse(File(root, "tencent-translation/model.part").exists())
            assertEquals(TranslationPreparationPhase.FAILED, store.state.value.phase)
            assertNull(store.verifiedFile())
        }
    }

    @Test fun corruptInstalledBytesRemainNonReadyUntilExplicitRepair() = runBlocking {
        val root = temporary.newFolder()
        val store = store(root) { Response(it, bytes) }
        val installed = store.prepare()
        val replacement = File(root, "changed.gguf").apply { writeBytes(bytes.copyOf().also { it[0] = 9 }) }
        Files.move(replacement.toPath(), installed.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        assertTrue(runCatching { store.verifiedFile() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
        assertEquals(9, installed.readBytes()[0].toInt())
        assertEquals(installed, store.prepare())
        assertArrayEquals(bytes, installed.readBytes())
    }

    @Test fun cancelledStagingIsJoinedAndCannotAppearInstalled() = runBlocking {
        val root = temporary.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (pos > 0) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                return super.read(buffer, offset, length)
            }
        }
        val store = store(root) { Response(it, bytes, stream = stream) }
        val request = launch(Dispatchers.Default) { store.prepare() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        request.cancel()
        release.countDown()
        request.join()
        assertFalse(File(root, "tencent-translation/model.part").exists())
        assertNull(store.verifiedFile())
        assertEquals(TranslationPreparationPhase.MISSING, store.state.value.phase)
    }

    @Test fun concurrentPreparationSharesOneInstallerAndPublication() = runBlocking {
        val root = temporary.newFolder()
        val requests = AtomicInteger()
        val store = store(root) { requests.incrementAndGet(); Response(it, bytes) }
        val first = async(Dispatchers.Default) { store.prepare() }
        val second = async(Dispatchers.Default) { store.prepare() }
        assertEquals(first.await(), second.await())
        assertEquals(1, requests.get())
        assertArrayEquals(bytes, store.verifiedFile()!!.readBytes())
    }

    @Test fun unsafeRedirectNeverOpensTheUntrustedDestination() = runBlocking {
        for (location in listOf("http://us.aws.cdn.hf.co/model", "https://attacker.example/model",
            "https://user:secret@us.aws.cdn.hf.co/model", "https://us.aws.cdn.hf.co:444/model")) {
            val opened = mutableListOf<URL>()
            val store = store(temporary.newFolder()) { url -> opened += url; Response(url, bytes, 302, location) }
            assertTrue(runCatching { store.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
            assertEquals(listOf(URL(pin.url)), opened)
            assertNull(store.verifiedFile())
        }
    }

    @Test fun wifiAndStorageRefusalHaveNoDownloadEffects() = runBlocking {
        val root = temporary.newFolder()
        val offline = TencentArtifactPreparation(root, pin, { false }, { error("Must not connect") })
        assertTrue(runCatching { offline.prepare() }.exceptionOrNull() is PreparationFailure.WifiRequired)
        val full = TencentArtifactPreparation(root, pin, { true }, { error("Must not connect") }, { bytes.size.toLong() })
        assertTrue(runCatching { full.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
        assertNull(full.verifiedFile())
    }

    @Test fun rejectedResponseAndTransferFailureCleanPrivateStaging() = runBlocking {
        val root = temporary.newFolder()
        val response = store(root) { Response(it, bytes, status = 503) }
        assertTrue(runCatching { response.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
        val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("Fixture interruption") }
        val interrupted = store(root) { Response(it, bytes, stream = failing) }
        assertTrue(runCatching { interrupted.prepare() }.exceptionOrNull() is TranslationFailure.Storage)
        assertFalse(File(root, "tencent-translation/model.part").exists())
        assertNull(interrupted.verifiedFile())
    }

    private fun store(root: File, connection: (URL) -> HttpURLConnection) = TencentArtifactPreparation(root, pin, { true }, connection)
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Response(
        url: URL,
        private val bytes: ByteArray,
        private val status: Int = 200,
        private val location: String? = null,
        private val declaredLength: Long? = bytes.size.toLong(),
        private val stream: InputStream = ByteArrayInputStream(bytes),
    ) : HttpURLConnection(url) {
        override fun getResponseCode() = status
        override fun getHeaderField(name: String): String? = when (name) {
            "Location" -> location
            "Content-Length" -> declaredLength?.toString()
            else -> null
        }
        override fun getInputStream(): InputStream = stream
        override fun disconnect() { stream.close() }
        override fun usingProxy() = false
        override fun connect() = Unit
    }
}
