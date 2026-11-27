package io.github.ngoroth.offlinetranslator.tencent

import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.ModelAvailability
import io.github.ngoroth.offlinetranslator.core.PreparationFailure
import io.github.ngoroth.offlinetranslator.core.TranslationFailure
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationPhase
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
    private val currentFileName = TencentTranslationArtifact.CURRENT.fileName
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
            assertTrue(runCatching { selected.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
            assertEquals(0, connections.get())
            assertTrue(root.mkdir())
            assertEquals(ModelAvailability.MISSING, engine.modelAvailability())
            assertEquals(TranslationPreparationPhase.MISSING, engine.preparation.value.phase)
            val installed = selected.publishCurrent()
            assertArrayEquals(bytes, installed.readBytes())
            assertEquals(installed, selected.verifiedFile()?.file)
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
        val installed = first.publishCurrent()
        assertEquals(File(root, "tencent-translation/$currentFileName"), installed)
        assertArrayEquals(bytes, installed.readBytes())
        val restarted = TencentArtifactPreparation(alias, pin, { false }, { error("Cold reuse must remain offline") })
        assertEquals(installed, restarted.verifiedFile()?.file)
        assertEquals(installed, restarted.publishCurrent())
        assertEquals(1, connections.get())
    }

    @Test fun providerOwnedDirectoryModelAndStagingLinksRemainRejected() = runBlocking {
        for (relativePath in listOf("tencent-translation", "tencent-translation/$currentFileName",
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
            assertTrue(runCatching { selected.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
            if (outside.isFile) assertArrayEquals(bytes, outside.readBytes())
        }
    }

    @Test fun crashLeftStageIsNotReadyAndExplicitPreparationReclaimsItsSpace() = runBlocking {
        val root = temporary.newFolder()
        val staging = File(root, "tencent-translation/model.part").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val store = TencentArtifactPreparation(root, pin, { true }, { Response(it, bytes) },
            { if (staging.exists()) 0L else pin.bytes + 16 * 1024 * 1024L })
        assertNull(store.verifiedFile())
        val installed = store.publishCurrent()
        assertArrayEquals(bytes, installed.readBytes())
        assertFalse(staging.exists())
    }

    @Test fun verifiedPublicationSurvivesColdRestartWithoutNetworkOrSecondCopy() = runBlocking {
        val root = temporary.newFolder()
        val connections = AtomicInteger()
        val first = store(root) { connections.incrementAndGet(); Response(it, bytes) }
        val file = first.publishCurrent()
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(root, "tencent-translation/model.part").exists())
        val restarted = TencentArtifactPreparation(root, pin, { false }, { error("Offline attestation must not connect") })
        assertEquals(file, restarted.verifiedFile()?.file)
        assertEquals(file, restarted.publishCurrent())
        assertEquals(1, connections.get())
    }

    @Test fun wrongDigestTruncationAndOversizeNeverPublish() = runBlocking {
        val wrong = bytes.copyOf().also { it[20] = (it[20] + 1).toByte() }
        for (body in listOf(wrong, bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(1))) {
            val root = temporary.newFolder()
            val store = store(root) { Response(it, body, declaredLength = null) }
            assertTrue(runCatching { store.publishCurrent() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
            assertFalse(File(root, "tencent-translation/$currentFileName").exists())
            assertFalse(File(root, "tencent-translation/model.part").exists())
            assertNull(store.verifiedFile())
        }
    }

    @Test fun localVerificationCachesOnlyUnchangedIdentityAndRechecksReplacementWithoutNetwork() = runBlocking {
        val root = temporary.newFolder()
        val installed = File(root, "tencent-translation/$currentFileName").apply {
            parentFile.mkdirs(); writeBytes(bytes)
        }
        val selected = TencentArtifactPreparation(root, pin,
            { error("Local verification must not query Wi-Fi") },
            { error("Local verification must not connect") })
        var verifications = 0
        assertEquals(installed, selected.verifiedFile { verifications++ }?.file)
        assertEquals(installed, selected.verifiedFile { verifications++ }?.file)
        assertEquals(1, verifications)

        val timestamp = Files.getLastModifiedTime(installed.toPath())
        val corrupt = bytes.copyOf().also { it[0] = 9 }
        val replacement = File(root, "replacement.gguf").apply { writeBytes(corrupt) }
        Files.setLastModifiedTime(replacement.toPath(), timestamp)
        Files.move(replacement.toPath(), installed.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        repeat(2) {
            assertTrue(runCatching { selected.verifiedFile { verifications++ } }.exceptionOrNull()
                is TranslationFailure.ArtifactCorrupt)
        }
        assertEquals(3, verifications)
        assertArrayEquals(corrupt, installed.readBytes())
        assertFalse(File(root, "tencent-translation/model.part").exists())

        assertTrue(installed.delete())
        assertNull(selected.verifiedFile { verifications++ })
        assertEquals(3, verifications)
    }

    @Test fun corruptInstalledBytesRemainNonReadyUntilExplicitRepair() = runBlocking {
        val root = temporary.newFolder()
        val store = store(root) { Response(it, bytes) }
        val installed = store.publishCurrent()
        val replacement = File(root, "changed.gguf").apply { writeBytes(bytes.copyOf().also { it[0] = 9 }) }
        Files.move(replacement.toPath(), installed.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        assertTrue(runCatching { store.verifiedFile() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
        assertEquals(9, installed.readBytes()[0].toInt())
        assertEquals(installed, store.publishCurrent())
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
        val request = launch(Dispatchers.Default) { store.publishCurrent() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        request.cancel()
        release.countDown()
        request.join()
        assertFalse(File(root, "tencent-translation/model.part").exists())
        assertNull(store.verifiedFile())
    }

    @Test fun competingInstallCannotMutateOrPublishWhileVerifiedStageIsBeingProbed() = runBlocking {
        val root = temporary.newFolder()
        val requests = AtomicInteger()
        val probeEntered = CompletableDeferred<String>()
        val releaseProbe = CompletableDeferred<Unit>()
        val connect: (URL) -> HttpURLConnection = { requests.incrementAndGet(); Response(it, bytes) }
        val first = async {
            store(root, connect).publishCurrent { stage ->
                probeEntered.complete(hash(stage.readBytes()))
                releaseProbe.await()
            }
        }
        val probed = withTimeout(5_000) { probeEntered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { store(root, connect).publishCurrent() }
        try {
            val directory = File(root, "tencent-translation")
            assertFalse(File(directory, currentFileName).exists())
            assertEquals(probed, hash(File(directory, "model.part").readBytes()))
            assertFalse(second.isCompleted)
            assertEquals(1, requests.get())
        } finally { releaseProbe.complete(Unit) }
        val published = first.await()
        assertEquals(published, second.await())
        assertEquals(probed, hash(published.readBytes()))
        assertEquals(hash(bytes), probed)
        assertEquals(1, requests.get())
    }

    @Test fun unsafeRedirectNeverOpensTheUntrustedDestination() = runBlocking {
        for (location in listOf("http://us.aws.cdn.hf.co/model", "https://attacker.example/model",
            "https://user:secret@us.aws.cdn.hf.co/model", "https://us.aws.cdn.hf.co:444/model")) {
            val opened = mutableListOf<URL>()
            val store = store(temporary.newFolder()) { url -> opened += url; Response(url, bytes, 302, location) }
            assertTrue(runCatching { store.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
            assertEquals(listOf(URL(requireNotNull(pin.url))), opened)
            assertNull(store.verifiedFile())
        }
    }

    @Test fun wifiAndStorageRefusalHaveNoDownloadEffects() = runBlocking {
        val root = temporary.newFolder()
        val offline = TencentArtifactPreparation(root, pin, { false }, { error("Must not connect") })
        assertTrue(runCatching { offline.publishCurrent() }.exceptionOrNull() is PreparationFailure.WifiRequired)
        val full = TencentArtifactPreparation(root, pin, { true }, { error("Must not connect") }, { bytes.size.toLong() })
        assertTrue(runCatching { full.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
        assertNull(full.verifiedFile())
    }

    @Test fun rejectedResponseAndTransferFailureCleanPrivateStaging() = runBlocking {
        val root = temporary.newFolder()
        val response = store(root) { Response(it, bytes, status = 503) }
        assertTrue(runCatching { response.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
        val failing = object : InputStream() { override fun read(): Int = throw java.io.IOException("Fixture interruption") }
        val interrupted = store(root) { Response(it, bytes, stream = failing) }
        assertTrue(runCatching { interrupted.publishCurrent() }.exceptionOrNull() is TranslationFailure.Storage)
        assertFalse(File(root, "tencent-translation/model.part").exists())
        assertNull(interrupted.verifiedFile())
    }

    private suspend fun TencentArtifactPreparation.publishCurrent(probe: suspend (File) -> Unit = {}): File =
        prepareCurrent(TencentArtifactPreparation.Commit(), {}, probe).file

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
