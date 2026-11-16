package io.github.ngoroth.offlinetranslator.mozilla

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.coroutines.CoroutineContext

/** Process-shared immutable files only; each Translation engine owns its native state. */
class MozillaTranslationPreparation internal constructor(
    private val noBackupDirectory: File,
    private val catalog: MozillaTranslationCatalog = MozillaTranslationCatalog.PINNED,
    private val wifiAvailable: () -> Boolean,
    private val openConnection: (URL) -> HttpURLConnection,
    private val prefixBytes: (String) -> ByteArray,
    private val availableBytes: () -> Long = { noBackupDirectory.usableSpace },
) {
    constructor(context: Context) : this(
        context.applicationContext.noBackupFilesDir,
        wifiAvailable = {
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            connectivity.getNetworkCapabilities(connectivity.activeNetwork).isWifi()
        },
        openConnection = { url ->
            val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            val network = connectivity.activeNetwork ?: throw PreparationFailure.WifiRequired()
            if (!connectivity.getNetworkCapabilities(network).isWifi()) throw PreparationFailure.WifiRequired()
            network.openConnection(url) as HttpURLConnection
        },
        prefixBytes = { language -> context.applicationContext.assets.open("mozilla/prefixes/$language.txt").use { it.readBytes() } },
    )

    private val slot = Mutex()
    private val mutableState = MutableStateFlow(TranslationPreparationState())
    internal val state = mutableState.asStateFlow()
    private val verified = mutableMapOf<String, BasicFileAttributes>()
    private val prefixes = mutableMapOf<String, ByteArray>()
    private lateinit var root: File
    private lateinit var objects: File
    private lateinit var configurations: File

    internal suspend fun localConfigurations(pairs: Set<LanguagePair>): Map<String, String>? = prepare(pairs, localOnly = true)
    internal suspend fun prepareConfigurations(pairs: Set<LanguagePair>): Map<String, String> =
        prepare(pairs, localOnly = false) ?: throw TranslationFailure.ModelMissing()

    private suspend fun prepare(pairs: Set<LanguagePair>, localOnly: Boolean): Map<String, String>? = slot.withLock {
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val deadline = System.nanoTime() + PREPARATION_MILLIS * 1_000_000
                mutableState.value = TranslationPreparationState(TranslationPreparationPhase.CHECKING)
                if (!directories(create = !localOnly)) return@withContext missing()
                val models = catalog.models(pairs)
                val files = catalog.files(models)
                val absent = files.filterNot { file ->
                    verify(File(objects, file.installedName), file.bytes, file.sha256, context, repair = !localOnly)
                }
                if (absent.isNotEmpty()) {
                    if (localOnly) return@withContext missing()
                    requireWifi()
                    if (availableBytes() < absent.sumOf { it.bytes } + STORAGE_OVERHEAD) throw TranslationFailure.Storage()
                    val total = absent.sumOf { it.transferBytes }
                    var completed = 0L
                    for (file in absent) {
                        download(file, context, deadline) { received ->
                            mutableState.value = TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING, completed + received, total)
                        }
                        completed += file.transferBytes
                    }
                }
                val result = linkedMapOf<String, String>()
                for (model in models) {
                    context.ensureActive()
                    val source = model.pair.source.value
                    val prefix = prefixes.getOrPut(source) {
                        if (source in MozillaTranslationCatalog.PREFIX_LANGUAGES) prefixBytes(source).also {
                            if (it.size > 128 * 1024) throw TranslationFailure.ArtifactCorrupt()
                        } else ByteArray(0) // Upstream regex-only segmentation, not another language's prefix list.
                    }
                    val prefixFile = generated(prefix, ".txt", context, localOnly) ?: return@withContext missing()
                    val config = model.configuration({ File(objects, it.installedName) }, prefixFile).toByteArray(Charsets.UTF_8)
                    val configFile = generated(config, ".yml", context, localOnly) ?: return@withContext missing()
                    result[model.key] = configFile.absolutePath
                }
                // Native graph initialization still precedes READY, and belongs to the engine.
                result.toMap()
            }
        } catch (error: Throwable) {
            if (error is CancellationException || !currentCoroutineContext().isActive) {
                mutableState.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
                currentCoroutineContext().ensureActive()
                throw error
            }
            val failure = when (error) {
                is TranslationFailure -> error
                is OutOfMemoryError -> TranslationFailure.Memory()
                is java.net.SocketTimeoutException -> PreparationFailure.PreparationTimedOut()
                is java.util.zip.ZipException -> TranslationFailure.ArtifactCorrupt()
                is IOException, is SecurityException -> TranslationFailure.Storage()
                else -> throw error
            }
            mutableState.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
            throw failure
        }
    }

    private fun missing(): Map<String, String>? {
        mutableState.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
        return null
    }

    private suspend fun verify(file: File, bytes: Long, hash: String, context: CoroutineContext, repair: Boolean): Boolean {
        safeFile(file)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return false
        val before = attributes(file)
        if (sameIdentity(before, verified[file.absolutePath])) return true
        mutableState.value = TranslationPreparationState(TranslationPreparationPhase.VERIFYING)
        if (before.size() != bytes || digestFile(file, context) != hash || !sameIdentity(before, attributes(file))) {
            verified.remove(file.absolutePath)
            if (!repair) throw TranslationFailure.ArtifactCorrupt()
            return false
        }
        verified[file.absolutePath] = before
        return true
    }

    private suspend fun generated(bytes: ByteArray, suffix: String, context: CoroutineContext, localOnly: Boolean): File? {
        val hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val target = File(configurations, hash + suffix)
        if (verify(target, bytes.size.toLong(), hash, context, repair = !localOnly)) return target
        if (localOnly) return null
        publish(target, context) { channel ->
            Channels.newOutputStream(channel).write(bytes)
        }
        verified[target.absolutePath] = attributes(target)
        return target
    }

    private suspend fun download(file: MozillaTranslationFile, context: CoroutineContext, deadline: Long, progress: (Long) -> Unit) = coroutineScope {
        val connection = connect(URL(file.url), context, deadline)
        // Closing the Wi-Fi-bound connection also releases a blocked read on cancellation.
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try {
            if (connection.responseCode != 200 || connection.getHeaderField("Content-Encoding")?.let { it != "identity" } == true) {
                throw TranslationFailure.ArtifactCorrupt()
            }
            if (connection.getHeaderField("Content-Length")?.toLongOrNull() != file.transferBytes) throw TranslationFailure.ArtifactCorrupt()
            val digest = MessageDigest.getInstance("SHA-256")
            var outputBytes = 0L
            var publishedBytes = 0L
            var publishedAt = System.nanoTime()
            connection.inputStream.use { raw ->
                val bounded = TransferInput(raw, file.transferBytes) {
                    checkActive(context, deadline)
                    requireWifi()
                    connection.readTimeout = remainingTimeout(deadline)
                    val now = System.nanoTime()
                    if (it == 0L || it == file.transferBytes || it - publishedBytes >= 1024 * 1024 || now - publishedAt >= 250_000_000L) {
                        progress(it)
                        publishedBytes = it
                        publishedAt = now
                    }
                }
                publish(File(objects, file.installedName), context) { channel ->
                    (if (file.gzip) GZIPInputStream(bounded, BUFFER_BYTES) else bounded).use { input ->
                        val output = Channels.newOutputStream(channel)
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            checkActive(context, deadline)
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (outputBytes > file.bytes - count) throw TranslationFailure.ArtifactCorrupt()
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            outputBytes += count
                        }
                        // Both the selected wire representation and the installed bytes are pinned.
                        if (outputBytes != file.bytes || bounded.received != file.transferBytes ||
                            hex(digest.digest()) != file.sha256) throw TranslationFailure.ArtifactCorrupt()
                    }
                }
            }
            val installed = File(objects, file.installedName)
            verified[installed.absolutePath] = attributes(installed)
        } finally {
            withContext(NonCancellable) { cancellation.cancelAndJoin() }
            connection.disconnect()
        }
    }

    private suspend fun publish(target: File, context: CoroutineContext, write: suspend (FileChannel) -> Unit) {
        safeFile(target)
        val stage = File(target.parentFile, ".${UUID.randomUUID()}.part")
        var identity: BasicFileAttributes? = null
        var primary: Throwable? = null
        try {
            FileChannel.open(stage.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                identity = attributes(stage)
                write(channel)
                context.ensureActive()
                channel.force(true)
            }
            requireStage(stage, identity)
            safeFile(target)
            context.ensureActive()
            Files.move(stage.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            if (identity != null && Files.exists(stage.toPath(), NOFOLLOW_LINKS)) try {
                requireStage(stage, identity)
                Files.delete(stage.toPath())
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun requireStage(stage: File, original: BasicFileAttributes?) {
        safeFile(stage)
        val current = attributes(stage)
        if (original == null || current.fileKey() != original.fileKey() ||
            (current.fileKey() == null && current.creationTime() != original.creationTime())) {
            throw IOException("Staging identity changed")
        }
    }

    private fun directories(create: Boolean): Boolean {
        if (!::root.isInitialized) {
            root = File(noBackupDirectory.toPath().toRealPath().toFile(), "mozilla-translation")
            objects = File(root, "objects")
            configurations = File(root, "configurations")
        }
        for (directory in listOf(root, objects, configurations)) {
            if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) {
                if (!create) return false
                Files.createDirectory(directory.toPath())
            }
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || Files.isSymbolicLink(directory.toPath()) ||
                directory.canonicalFile != directory.absoluteFile) throw IOException("Unsafe translation storage")
        }
        return true
    }

    private fun safeFile(file: File) {
        if (file.canonicalFile != file.absoluteFile || Files.isSymbolicLink(file.toPath()) ||
            (Files.exists(file.toPath(), NOFOLLOW_LINKS) && !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))) {
            throw IOException("Unsafe translation file")
        }
    }

    private fun requireWifi() {
        if (!wifiAvailable()) throw PreparationFailure.WifiRequired()
    }

    private fun connect(initial: URL, context: CoroutineContext, deadline: Long): HttpURLConnection {
        var url = initial
        repeat(6) { hop ->
            checkActive(context, deadline)
            requireWifi()
            if (url.protocol != "https" || url.host !in MODEL_HOSTS || url.userInfo != null ||
                url.ref != null || (url.port != -1 && url.port != 443)) throw IOException("Untrusted model endpoint")
            val connection = openConnection(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remainingTimeout(deadline)
                connection.readTimeout = remainingTimeout(deadline)
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (connection.responseCode !in REDIRECTS) return connection
                val location = connection.getHeaderField("Location")
                if (hop == 5 || location.isNullOrBlank()) throw IOException("Invalid model redirect")
                url = URL(url, location)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many model redirects")
    }

    private class TransferInput(input: InputStream, private val limit: Long, private val progress: (Long) -> Unit) : FilterInputStream(input) {
        var received = 0L
            private set
        override fun read(): Int {
            progress(received)
            val value = `in`.read()
            if (value >= 0) record(1)
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            progress(received)
            val count = `in`.read(buffer, offset, length)
            if (count > 0) record(count)
            return count
        }
        private fun record(count: Int) {
            if (received > limit - count) throw TranslationFailure.ArtifactCorrupt()
            received += count
            progress(received)
        }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val PREPARATION_MILLIS = 30 * 60 * 1000L
        private const val STORAGE_OVERHEAD = 16 * 1024 * 1024L
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val MODEL_HOSTS = setOf("storage.googleapis.com", "firefox-settings-attachments.cdn.mozilla.net")
        private fun NetworkCapabilities?.isWifi() = this != null && hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        private fun remainingTimeout(deadline: Long) = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 15_000).toInt()
        private fun checkActive(context: CoroutineContext, deadline: Long) {
            context.ensureActive()
            if (System.nanoTime() >= deadline) throw PreparationFailure.PreparationTimedOut()
        }
        private fun attributes(file: File): BasicFileAttributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        private fun sameIdentity(first: BasicFileAttributes, second: BasicFileAttributes?) = second != null &&
            first.fileKey() == second.fileKey() && first.size() == second.size() &&
            first.lastModifiedTime() == second.lastModifiedTime() && first.creationTime() == second.creationTime()
        private fun hex(bytes: ByteArray) = buildString(bytes.size * 2) {
            for (value in bytes) {
                append("0123456789abcdef"[(value.toInt() and 255) ushr 4])
                append("0123456789abcdef"[value.toInt() and 15])
            }
        }
        private suspend fun digestFile(file: File, context: CoroutineContext): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
    }
}
