import java.io.File
import java.net.URI
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaArchivePreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "synthetic archive".toByteArray()
    private val url = URI("https://github.com/example/archive.aar")
    private val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun helper(download: (URI, Set<String>, File, Long) -> Unit) =
        SherpaArchivePreparation(url, bytes.size.toLong(), hash, download)

    @Test fun publishesOnlyVerifiedBytesAndReusesWithoutDownload() {
        val archive = File(temporary.newFolder(), "libs/runtime.aar")
        var calls = 0
        val preparation = helper { actualUrl, hosts, staged, size ->
            calls++
            assertEquals(url, actualUrl)
            assertEquals(setOf("github.com", "release-assets.githubusercontent.com"), hosts)
            assertEquals(bytes.size.toLong(), size)
            assertEquals(archive.parentFile, staged.parentFile)
            assertFalse(archive.exists())
            staged.writeBytes(bytes)
        }
        preparation.prepare(archive)
        assertArrayEquals(bytes, archive.readBytes())
        assertEquals(hash, preparation.verify(archive))
        preparation.prepare(archive)
        assertEquals(1, calls)
        assertEquals(listOf(archive.name), archive.parentFile.list()!!.toList())
    }

    @Test fun hashAndSizeMismatchNeverPublishAndOnlyOwnStagingIsDeleted() {
        for (damaged in listOf(ByteArray(bytes.size) { 0 }, bytes.copyOf(bytes.size - 1))) {
            val root = temporary.newFolder()
            val archive = File(root, "runtime.aar")
            val foreign = File(root, "foreign.part").apply { writeText("keep") }
            val preparation = helper { _, _, staged, _ -> staged.writeBytes(damaged) }
            val failure = assertThrows(IllegalStateException::class.java) { preparation.prepare(archive) }
            assertTrue(failure.message!!.contains("SHA-256"))
            assertFalse(archive.exists())
            assertEquals(listOf(foreign.name), root.list()!!.toList())
            assertEquals("keep", foreign.readText())
        }
    }

    @Test fun transferFailureCleansPartialWithoutPublishing() {
        val root = temporary.newFolder()
        val archive = File(root, "runtime.aar")
        val preparation = helper { _, _, staged, _ ->
            staged.writeText("partial")
            error("truncated transfer")
        }
        assertThrows(IllegalStateException::class.java) { preparation.prepare(archive) }
        assertFalse(archive.exists())
        assertTrue(root.list()!!.isEmpty())
    }

    @Test fun missingVerificationFailsWithPathAndPreparationCommand() {
        val archive = File(temporary.newFolder(), "runtime.aar")
        val preparation = helper { _, _, _, _ -> fail("Verification must not download") }
        val failure = assertThrows(IllegalStateException::class.java) { preparation.verify(archive) }
        assertTrue(failure.message!!.contains(archive.absolutePath))
        assertTrue(failure.message!!.contains(":engine-sherpa:prepareSherpaArchive"))
        assertFalse(archive.exists())
    }

    @Test fun corruptExistingArchiveFailsUnchangedWithoutDownloading() {
        val root = temporary.newFolder()
        val archive = File(root, "runtime.aar").apply { writeText("corrupt") }
        val preparation = helper { _, _, _, _ -> fail("Existing archive must not download") }
        for (operation in listOf<() -> Unit>({ preparation.verify(archive) }, { preparation.prepare(archive) })) {
            val failure = assertThrows(IllegalStateException::class.java) { operation() }
            assertTrue(failure.message!!.contains(archive.absolutePath))
            assertTrue(failure.message!!.contains("SHA-256"))
            assertEquals("corrupt", archive.readText())
            assertEquals(listOf(archive.name), root.list()!!.toList())
        }
    }
}
