package com.example.offlinetranslator.speechmodels

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.nio.file.Files

/** Extracts only catalog-listed files into an empty private staging directory. */
class SpeechArchiveExtractor {
    fun extract(pkg: SpeechModelPackage, archive: File, staging: File) {
        require(staging.isDirectory && staging.canonicalFile == staging.absoluteFile &&
            !Files.isSymbolicLink(staging.toPath()) && staging.list().isNullOrEmpty()) { "Staging must be private and empty" }
        require(archive.isFile && archive.canonicalFile == archive.absoluteFile &&
            !Files.isSymbolicLink(archive.toPath())) { "Unsafe model archive" }
        if (pkg.format == "raw") {
            val spec = pkg.files.single()
            require(archive.length() == spec.bytes) { "Raw model file size mismatch" }
            FileInputStream(archive).use { copyAndVerify(it, File(staging, spec.path), spec) }
            return
        }
        require(pkg.format == "tar.bz2") { "Unsupported archive" }
        val root = pkg.url.substringAfterLast('/').removeSuffix(".tar.bz2")
        require(root.isNotBlank()) { "Missing archive root" }
        val expected = pkg.files.associateBy { it.path }
        val seen = HashSet<String>()
        val extracted = HashSet<String>()
        var declaredBytes = 0L
        var entries = 0
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                entries++
                require(entries <= pkg.maxEntries) { "Too many archive entries" }
                val name = entry.name.removeSuffix("/")
                require(SpeechModelCatalog.safeRelativePath(name) && seen.add(name)) {
                    "Unsafe or duplicate archive entry"
                }
                require(!entry.isSymbolicLink && !entry.isLink && !entry.isCharacterDevice &&
                    !entry.isBlockDevice && !entry.isFIFO && (entry.isDirectory || entry.isFile)) {
                    "Archive links and special files are forbidden"
                }
                require(entry.size >= 0 && declaredBytes <= pkg.maxUnpackedBytes - entry.size) {
                    "Archive exceeds unpacked size limit"
                }
                declaredBytes += entry.size
                require(name == root || name.startsWith("$root/")) { "Unexpected archive root" }
                if (entry.isDirectory) continue
                val relative = name.removePrefix("$root/")
                val spec = expected[relative] ?: continue
                require(entry.size == spec.bytes) { "Model file size mismatch" }
                copyAndVerify(tar, File(staging, relative), spec)
                extracted.add(relative)
            }
        }
        require(extracted == expected.keys) { "Archive omits required model files" }
    }

    private fun copyAndVerify(input: java.io.InputStream, destination: File, spec: SpeechModelFile) {
        require(SpeechModelCatalog.safeRelativePath(spec.path)) { "Unsafe expected path" }
        val parent = requireNotNull(destination.parentFile)
        require(parent.mkdirs() || parent.isDirectory) { "Cannot create model directory" }
        require(parent.canonicalFile == parent.absoluteFile && !Files.isSymbolicLink(parent.toPath()) &&
            destination.canonicalFile == destination.absoluteFile && !Files.isSymbolicLink(destination.toPath())) {
            "Unsafe extraction destination"
        }
        require(destination.createNewFile()) { "Model file already exists" }
        val digest = MessageDigest.getInstance("SHA-256")
        var remaining = spec.bytes
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1_024)
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                require(count > 0) { "Truncated model file" }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        require(actual == spec.sha256) { "Model file digest mismatch" }
    }
}
