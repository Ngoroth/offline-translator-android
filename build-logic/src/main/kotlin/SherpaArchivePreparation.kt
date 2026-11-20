import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

/** Explicit preparation only: normal builds verify the local archive without network access. */
class SherpaArchivePreparation(
    private val url: URI,
    private val expectedBytes: Long,
    private val expectedSha256: String,
    private val download: (URI, Set<String>, File, Long) -> Unit = { uri, hosts, file, bytes ->
        PinnedHttps.download(uri, hosts, file, bytes)
    },
) {
    fun verify(archive: File): String {
        check(archive.isFile) {
            "Missing Sherpa archive: ${archive.absolutePath}. Run :engine-sherpa:prepareSherpaArchive --console=plain before building."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(archive.length() == expectedBytes && actual.equals(expectedSha256, ignoreCase = true)) {
            "Unexpected Sherpa archive at ${archive.absolutePath}: ${archive.length()} bytes, SHA-256 $actual; expected $expectedBytes bytes, SHA-256 $expectedSha256. Existing files are not replaced; remove the corrupt file explicitly, then run :engine-sherpa:prepareSherpaArchive --console=plain."
        }
        return actual
    }

    fun prepare(archive: File) {
        if (archive.exists()) {
            verify(archive)
            return
        }
        val target = archive.toPath().toAbsolutePath()
        Files.createDirectories(target.parent)
        val staged = Files.createTempFile(target.parent, ".${archive.name}-", ".part")
        try {
            download(url, setOf("github.com", "release-assets.githubusercontent.com"), staged.toFile(), expectedBytes)
            verify(staged.toFile())
            check(!Files.exists(target)) { "Sherpa archive appeared during preparation: $target; rerun to verify it." }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}

/** A configuration-cache input when Gradle resolves the runtime before preBuild. */
abstract class VerifiedSherpaArchives : ValueSource<Boolean, VerifiedSherpaArchives.Parameters> {
    interface Parameters : ValueSourceParameters {
        val archives: ListProperty<File>
        val bytes: ListProperty<Long>
        val sha256: ListProperty<String>
    }

    override fun obtain(): Boolean {
        val sizes = parameters.bytes.get()
        val hashes = parameters.sha256.get()
        parameters.archives.get().forEachIndexed { index, archive ->
            SherpaArchivePreparation(archive.toURI(), sizes[index], hashes[index]).verify(archive)
        }
        return true
    }
}
