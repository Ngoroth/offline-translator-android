package com.example.offlinetranslator.speechmodels

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files

class SpeechArchiveExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun extractsOnlyExpectedFileAndVerifiesDigest() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/README.md" to "ignored".toByteArray())
        val staging = temporary.newFolder("staging")
        SpeechArchiveExtractor().extract(pkg(bytes), archive, staging)
        assertArrayEquals(bytes, File(staging, "model.onnx").readBytes())
        assertEquals(false, File(staging, "README.md").exists())
    }

    @Test fun rejectsTraversalEvenForUnselectedEntry() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/../outside" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder("unsafe"))
        }
    }

    @Test fun rejectsMissingExpectedFile() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/README.md" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder("missing"))
        }
    }

    @Test fun rejectsArchiveSymlink() {
        val bytes = "weights".toByteArray()
        val archive = temporary.newFile("link.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { output ->
                val file = TarArchiveEntry("model/model.onnx")
                file.size = bytes.size.toLong()
                output.putArchiveEntry(file)
                output.write(bytes)
                output.closeArchiveEntry()
                val link = TarArchiveEntry("model/link", TarConstants.LF_SYMLINK)
                link.linkName = "../../outside"
                output.putArchiveEntry(link)
                output.closeArchiveEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder("link-stage"))
        }
    }

    @Test fun entryQuotaIncludesUnselectedArchiveMembers() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/README.md" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes).copy(maxEntries = 1), archive, temporary.newFolder())
        }
    }

    @Test fun extractsFullVoiceSizedInventoryIncludingEveryEspeakFile() {
        val bytes = byteArrayOf(1)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val files = (0 until 357).map { index ->
            SpeechModelFile("espeak-ng-data/voices/!v/dictionary-$index", 1, hash)
        }
        val archive = tar(*files.map { "model/${it.path}" to bytes }.toTypedArray())
        val voice = pkg(bytes).copy(
            files = files,
            maxEntries = 512,
            maxUnpackedBytes = 357,
        )
        val staging = temporary.newFolder()
        SpeechArchiveExtractor().extract(voice, archive, staging)
        files.forEach { assertArrayEquals(bytes, File(staging, it.path).readBytes()) }
    }

    @Test fun omittedEspeakMemberCannotBecomeReady() {
        val bytes = "weights".toByteArray()
        val model = pkg(bytes)
        val required = model.files + model.files.single().copy(path = "espeak-ng-data/ru_dict")
        val archive = tar("model/model.onnx" to bytes)
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(model.copy(files = required), archive, temporary.newFolder())
        }
    }

    @Test fun percentNamesRemainLiteralAndCannotEscapeStaging() {
        val bytes = "pinned contents".toByteArray()
        val paths = listOf(
            "GigaAM%20License_NC.pdf", "%2e%2e/outside.bin", "%2e%2e%2foutside.bin",
            "%2Fetc%2Fpasswd", "C%3A%5Coutside", "%00", "%", "%ZZ",
        )
        val base = pkg(bytes)
        val model = base.copy(
            files = paths.map { base.files.single().copy(path = it) },
            maxUnpackedBytes = bytes.size.toLong() * paths.size,
        )
        val outside = temporary.newFile("outside.bin").apply { writeText("outside sentinel") }
        val archive = tar(*paths.map { "model/$it" to bytes }.toTypedArray())
        val staging = temporary.newFolder("literal-percent")
        SpeechArchiveExtractor().extract(model, archive, staging)
        for (path in paths) {
            val installed = File(staging, path)
            assertArrayEquals(bytes, installed.readBytes())
            assertTrue("Literal percent name escaped staging: $path",
                installed.canonicalPath.startsWith(staging.canonicalPath + File.separator))
        }
        assertEquals("outside sentinel", outside.readText())
        assertEquals(false, File(staging, "GigaAM License_NC.pdf").exists())
        assertEquals(false, File(staging, "GigaAM%2520License_NC.pdf").exists())
    }

    @Test fun decodedAliasDoesNotSatisfyRequiredPercentNamedFile() {
        val bytes = "pinned notice".toByteArray()
        val base = pkg(bytes)
        val model = base.copy(files = listOf(base.files.single().copy(path = "License%20NC.pdf")))
        val archive = tar("model/License NC.pdf" to bytes)
        val staging = temporary.newFolder("decoded-alias")
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(model, archive, staging)
        }
        assertEquals(false, File(staging, "License%20NC.pdf").exists())
    }

    @Test fun percentComponentDoesNotPermitActualParentTraversal() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/%2e%2e/../outside" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder("mixed-traversal"))
        }
    }

    @Test fun duplicateRequiredArchiveMembersAreRejected() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/model.onnx" to bytes)
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder())
        }
    }

    @Test fun unpackedQuotaCountsIgnoredFilesToo() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes, "model/ignored" to ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, temporary.newFolder())
        }
    }

    @Test fun stagingDirectorySymlinkCannotWriteOutside() {
        val bytes = "weights".toByteArray()
        val archive = tar("model/model.onnx" to bytes)
        val outside = temporary.newFolder()
        val link = File(temporary.root, "linked-staging")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(pkg(bytes), archive, link)
        }
        assertTrue(outside.list().isNullOrEmpty())
    }

    @Test fun rawArchiveWithTrailingBytesCannotPublishValidPrefix() {
        val bytes = "weights".toByteArray()
        val archive = temporary.newFile().apply { writeBytes(bytes + byteArrayOf(1)) }
        val model = pkg(bytes).copy(format = "raw", archiveBytes = bytes.size.toLong())
        val staging = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            SpeechArchiveExtractor().extract(model, archive, staging)
        }
        assertTrue(staging.list().isNullOrEmpty())
    }

    private fun tar(vararg files: Pair<String, ByteArray>): File {
        val archive = temporary.newFile("archive-${System.nanoTime()}.tar.bz2")
        BZip2CompressorOutputStream(archive.outputStream()).use { compressed ->
            TarArchiveOutputStream(compressed).use { output ->
                for ((name, bytes) in files) {
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    output.putArchiveEntry(entry)
                    output.write(bytes)
                    output.closeArchiveEntry()
                }
            }
        }
        return archive
    }

    private fun pkg(bytes: ByteArray): SpeechModelPackage {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return SpeechModelPackage("test", "test", "test", "MIT",
            "https://example.test/model.tar.bz2", setOf("example.test"), "tar.bz2", 1,
            hash, 100, listOf(SpeechModelFile("model.onnx", bytes.size.toLong(), hash)))
    }
}
