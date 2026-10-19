package com.example.offlinetranslator.sherpa

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Downloads a pinned package into private staging; inference never calls this class. */
class SpeechModelDownloader(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun download(pkg: SpeechModelPackage, staging: File, progress: (Long) -> Unit): File {
        require(staging.isDirectory && staging.canonicalFile == staging.absoluteFile &&
            !Files.isSymbolicLink(staging.toPath())) { "Missing or unsafe private staging directory" }
        val partial = File(staging, "archive.part")
        val validatorFile = File(staging, "archive.validator")
        val completed = File(staging, "archive.complete")
        fun requireSafeStagingFile(file: File) {
            require(!Files.exists(file.toPath(), NOFOLLOW_LINKS) ||
                (file.isFile && file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()))) {
                "Unsafe download staging file"
            }
        }
        requireSafeStagingFile(partial)
        requireSafeStagingFile(validatorFile)
        requireSafeStagingFile(completed)
        if (completed.exists()) {
            if (completed.length() == pkg.archiveBytes && sha256(completed) == pkg.archiveSha256) return completed
            completed.delete()
        }
        var validator = readValidator(validatorFile)
        if (validator == null || partial.length() !in 1 until pkg.archiveBytes) {
            partial.delete()
            validatorFile.delete()
            validator = null
        }
        repeat(2) { attempt ->
            val offset = if (attempt == 0 && validator != null) partial.length() else 0L
            if (offset == 0L) {
                partial.delete()
                validatorFile.delete()
            }
            val connection = connect(pkg, offset, validator)
            try {
                val response = connection.responseCode
                if (offset > 0 && !validPartialResponse(connection, pkg, offset, validator!!)) {
                    partial.delete()
                    validatorFile.delete()
                    validator = null
                    return@repeat
                }
                require(response == if (offset > 0) 206 else 200) { "Unexpected HTTP status $response" }
                if (offset == 0L) {
                    require(contentLength(connection) == pkg.archiveBytes) { "Wrong model download length" }
                    validator = responseValidator(connection)
                    if (validator != null) writeValidator(validatorFile, validator!!)
                }
                var received = offset
                connection.inputStream.use { input ->
                    FileOutputStream(partial, offset > 0).use { output ->
                        val buffer = ByteArray(64 * 1_024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(received <= pkg.archiveBytes - count) { "Model download exceeds expected size" }
                            output.write(buffer, 0, count)
                            received += count
                            progress(received)
                        }
                        output.fd.sync()
                    }
                }
                require(received == pkg.archiveBytes) { "Truncated model download" }
                require(sha256(partial) == pkg.archiveSha256) { "Model archive digest mismatch" }
                require(partial.renameTo(completed)) { "Cannot finish model download" }
                validatorFile.delete()
                return completed
            } catch (failure: Exception) {
                if (failure is IllegalArgumentException || validator == null) {
                    partial.delete()
                    validatorFile.delete()
                }
                throw failure
            } finally {
                connection.disconnect()
            }
        }
        error("Model server did not honor safe resume")
    }

    private fun connect(pkg: SpeechModelPackage, offset: Long, validator: String?): HttpURLConnection {
        var url = URL(pkg.url)
        repeat(6) { hop ->
            require(url.protocol == "https" && url.host.lowercase() in pkg.allowedRedirectHosts &&
                url.userInfo == null && (url.port == -1 || url.port == 443)) { "Unsafe model URL" }
            val connection = openConnection(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) {
                connection.setRequestProperty("Range", "bytes=$offset-")
                connection.setRequestProperty("If-Range", validator?.removeRange(0, 2))
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(hop < 5 && !location.isNullOrBlank()) { "Invalid model redirect" }
            url = URL(url, location)
        }
        error("Too many model redirects")
    }

    private fun validPartialResponse(
        connection: HttpURLConnection,
        pkg: SpeechModelPackage,
        offset: Long,
        validator: String,
    ): Boolean {
        if (connection.responseCode != 206) return false
        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(
            connection.getHeaderField("Content-Range") ?: return false,
        ) ?: return false
        val (start, end, total) = range.destructured
        val startValue = start.toLongOrNull() ?: return false
        val endValue = end.toLongOrNull() ?: return false
        val totalValue = total.toLongOrNull() ?: return false
        if (startValue != offset || totalValue != pkg.archiveBytes || endValue < startValue ||
            endValue >= totalValue || contentLength(connection) != endValue - startValue + 1) return false
        return responseValidator(connection) == validator
    }

    private fun responseValidator(connection: HttpURLConnection): String? {
        val etag = connection.getHeaderField("ETag")
        val modified = connection.getHeaderField("Last-Modified")
        return listOfNotNull(etag?.let { "E:$it" }, modified?.let { "M:$it" }).firstOrNull {
            it.length in 3..202 && '\r' !in it && '\n' !in it
        }
    }

    private fun contentLength(connection: HttpURLConnection): Long? =
        connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun readValidator(file: File): String? {
        if (!file.isFile || file.length() !in 3..202) return null
        val text = file.readText()
        return text.takeIf { (it.startsWith("E:") || it.startsWith("M:")) && '\r' !in it && '\n' !in it }
    }

    private fun writeValidator(file: File, validator: String) {
        FileOutputStream(file).use { output ->
            output.write(validator.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
