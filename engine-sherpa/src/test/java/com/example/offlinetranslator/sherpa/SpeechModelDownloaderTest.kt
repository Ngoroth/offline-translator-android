package com.example.offlinetranslator.sherpa

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.Files
import kotlinx.coroutines.CancellationException

class SpeechModelDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun downloadsAndVerifiesPinnedBytes() = runBlocking {
        val bytes = "small-model".toByteArray()
        val response = FakeConnection(200, bytes, mapOf("Content-Length" to bytes.size.toString()))
        val output = SpeechModelDownloader { response }.download(pkg(bytes), temporary.root) { }
        assertArrayEquals(bytes, output.readBytes())
        assertEquals(false, File(temporary.root, "archive.validator").exists())
    }

    @Test fun validRangeResumesOnlyWithMatchingValidator() = runBlocking {
        val bytes = "small-model".toByteArray()
        File(temporary.root, "archive.part").writeBytes(bytes.copyOfRange(0, 5))
        File(temporary.root, "archive.validator").writeText("E:\"v1\"")
        val remaining = bytes.copyOfRange(5, bytes.size)
        val response = FakeConnection(
            206, remaining, mapOf(
                "Content-Range" to "bytes 5-${bytes.size - 1}/${bytes.size}",
                "Content-Length" to remaining.size.toString(),
                "ETag" to "\"v1\"",
            ),
        )
        val result = SpeechModelDownloader { response }.download(pkg(bytes), temporary.root) { }
        assertArrayEquals(bytes, result.readBytes())
        assertEquals("bytes=5-", response.getRequestProperty("Range"))
        assertEquals("\"v1\"", response.getRequestProperty("If-Range"))
    }

    @Test fun badRangeRestartsFromZero() = runBlocking {
        val bytes = "small-model".toByteArray()
        File(temporary.root, "archive.part").writeBytes(bytes.copyOfRange(0, 5))
        File(temporary.root, "archive.validator").writeText("E:\"v1\"")
        val badRange = FakeConnection(206, bytes.copyOfRange(5, bytes.size), mapOf(
            "Content-Range" to "bytes 4-${bytes.size - 1}/${bytes.size}",
            "Content-Length" to (bytes.size - 5).toString(),
            "ETag" to "\"v1\"",
        ))
        val full = FakeConnection(200, bytes, mapOf("Content-Length" to bytes.size.toString()))
        val queue = ArrayDeque(listOf(badRange, full))
        val result = SpeechModelDownloader { queue.removeFirst() }.download(pkg(bytes), temporary.root) { }
        assertArrayEquals(bytes, result.readBytes())
        assertEquals(null, full.getRequestProperty("Range"))
    }

    @Test fun refusesInsecureRedirectAndBadDigest() {
        val bytes = "small-model".toByteArray()
        val redirect = FakeConnection(302, byteArrayOf(), mapOf("Location" to "http://example.test/model"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SpeechModelDownloader { redirect }.download(pkg(bytes), temporary.root) { } }
        }
        val wrong = FakeConnection(200, "wrong-model".toByteArray(), mapOf("Content-Length" to bytes.size.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { SpeechModelDownloader { wrong }.download(pkg(bytes), temporary.root) { } }
        }
        assertEquals(false, File(temporary.root, "archive.complete").exists())
    }

    @Test fun rejectsUntrustedHttpsRedirectFromATrustedTlsFixture() {
        val bytes = "small-model".toByteArray()
        val requests = AtomicInteger()
        val packageWithFixtureHost = pkg(bytes).copy(
            url = "https://models.example.test/redirect",
            allowedRedirectHosts = setOf("models.example.test"),
        )
        TestHttpsServer { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Location", "https://attacker.example.test/model")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }.use { https ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    SpeechModelDownloader(https::openConnection)
                        .download(packageWithFixtureHost, temporary.root) { }
                }
            }

            assertEquals("Unsafe model URL", failure.message)
            assertEquals("the redirect destination must be rejected before another TLS request", 1, requests.get())
        }
    }

    @Test fun validCompletedArchiveIsReusedWithoutNetwork() = runBlocking {
        val bytes = "already-pinned".toByteArray()
        val completed = File(temporary.root, "archive.complete").apply { writeBytes(bytes) }
        val modified = completed.lastModified()
        val result = SpeechModelDownloader { error("valid archive must not redownload") }
            .download(pkg(bytes), temporary.root) {}
        assertEquals(completed, result)
        assertEquals(modified, result.lastModified())
    }

    @Test fun cancelledTransferRetainsValidatedPartialForSafeResume() = runBlocking {
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val full = FakeConnection(200, bytes, mapOf(
            "Content-Length" to bytes.size.toString(), "ETag" to "\"cancel-fixture\"",
        ))
        val failure = runCatching {
            SpeechModelDownloader { full }.download(pkg(bytes), temporary.root) {
                throw CancellationException("cancel transfer")
            }
        }.exceptionOrNull()
        assertEquals(true, failure is CancellationException)
        val partial = File(temporary.root, "archive.part")
        val offset = partial.length().toInt()
        assertEquals(true, offset in 1 until bytes.size)
        assertEquals("E:\"cancel-fixture\"", File(temporary.root, "archive.validator").readText())
        val remaining = bytes.copyOfRange(offset, bytes.size)
        val resumed = FakeConnection(206, remaining, mapOf(
            "Content-Length" to remaining.size.toString(),
            "Content-Range" to "bytes $offset-${bytes.lastIndex}/${bytes.size}",
            "ETag" to "\"cancel-fixture\"",
        ))
        val result = SpeechModelDownloader { resumed }.download(pkg(bytes), temporary.root) {}
        assertArrayEquals(bytes, result.readBytes())
        assertEquals("bytes=$offset-", resumed.getRequestProperty("Range"))
    }

    @Test fun changedResumeValidatorRestartsInsteadOfAppendingUntrustedBytes() = runBlocking {
        val bytes = "small-model".toByteArray()
        File(temporary.root, "archive.part").writeBytes(bytes.copyOfRange(0, 5))
        File(temporary.root, "archive.validator").writeText("E:\"old\"")
        val changed = FakeConnection(206, bytes.copyOfRange(5, bytes.size), mapOf(
            "Content-Range" to "bytes 5-${bytes.lastIndex}/${bytes.size}",
            "Content-Length" to (bytes.size - 5).toString(), "ETag" to "\"new\"",
        ))
        val full = FakeConnection(200, bytes, mapOf("Content-Length" to bytes.size.toString()))
        val queue = ArrayDeque(listOf(changed, full))
        val result = SpeechModelDownloader { queue.removeFirst() }.download(pkg(bytes), temporary.root) {}
        assertArrayEquals(bytes, result.readBytes())
        assertEquals(null, full.getRequestProperty("Range"))
    }

    @Test fun stagingSymlinksAreRejectedBeforeNetworkOrOutsideDeletion() {
        val bytes = "outside-sentinel".toByteArray()
        for (name in listOf("archive.part", "archive.validator", "archive.complete")) {
            val staging = temporary.newFolder()
            val outside = temporary.newFile().apply { writeBytes(bytes) }
            val link = File(staging, name)
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    SpeechModelDownloader { error("unsafe staging must not open HTTPS") }
                        .download(pkg(bytes), staging) {}
                }
            }
            assertArrayEquals(bytes, outside.readBytes())
            assertEquals(true, Files.isSymbolicLink(link.toPath()))
        }
    }

    private fun pkg(bytes: ByteArray): SpeechModelPackage {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return SpeechModelPackage("fixture", "fixture", "fixture", "MIT",
            "https://example.test/model", setOf("example.test"), "raw", bytes.size.toLong(), hash,
            bytes.size.toLong(), listOf(SpeechModelFile("model", bytes.size.toLong(), hash)))
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val headers: Map<String, String>,
    ) : HttpURLConnection(URL("https://example.test/model")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int = status
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body)
    }
}
