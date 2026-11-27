package io.github.ngoroth.offlinetranslator.tencent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext

/** Process-shared installation/attestation only. Mutable native inference is owned by each engine. */
class TencentArtifactPreparation internal constructor(
    private val noBackupDirectory: File,
    private val artifact: TencentTranslationArtifact = TencentTranslationArtifact.CURRENT,
    private val wifiAvailable: () -> Boolean,
    private val openConnection: (URL) -> HttpURLConnection,
    private val availableBytes: () -> Long = { noBackupDirectory.usableSpace },
    private val retired: TencentTranslationArtifact = TencentTranslationArtifact.RETIRED,
    private val afterPublish: () -> Unit = {},
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
            // Bind to Wi-Fi: a default-network change cannot silently transfer weights over cellular.
            network.openConnection(url) as HttpURLConnection
        },
    )

    private lateinit var directory: File
    private var verifiedArtifact: TencentTranslationArtifact? = null
    private var verifiedIdentity: BasicFileAttributes? = null

    internal data class Installed(val file: File, val artifact: TencentTranslationArtifact, val current: Boolean)

    /** Remains readable if dispatch back from the atomic move is cancelled. */
    internal class Commit {
        @Volatile var installed: Installed? = null
            private set

        internal fun recordPublication(value: Installed) { installed = value }
    }

    internal suspend fun verifiedFile(verifying: () -> Unit = {}): Installed? = storage {
        // A committed current name is authoritative even when its contents are corrupt.
        verify(artifact, current = true, verifying) ?: verify(retired, current = false, verifying)
    }

    private suspend fun verify(
        pin: TencentTranslationArtifact,
        current: Boolean,
        verifying: () -> Unit,
    ): Installed? {
        val file = File(directory, pin.fileName)
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        safeFile(file)
        val identity = attributes(file)
        if (verifiedArtifact != pin || !sameIdentity(identity, verifiedIdentity)) {
            verifying()
            if (identity.size() != pin.bytes || hash(file, currentCoroutineContext()) != pin.sha256) {
                verifiedIdentity = null
                throw TranslationFailure.ArtifactCorrupt()
            }
            verifiedArtifact = pin
            verifiedIdentity = identity
        }
        return Installed(file, pin, current)
    }

    /**
     * Holds the file slot through activation. [initialize] must not reenter this store.
     * The engine holds its own operation slot across this call and any subsequent recovery.
     */
    internal suspend fun prepareCurrent(
        commit: Commit,
        progress: (TranslationPreparationState) -> Unit,
        initialize: suspend (File) -> Unit,
    ): Installed = storage {
        progress(TranslationPreparationState(TranslationPreparationPhase.CHECKING))
        val existing = try {
            verify(artifact, current = true) { progress(TranslationPreparationState(TranslationPreparationPhase.VERIFYING)) }
        } catch (_: TranslationFailure.ArtifactCorrupt) {
            // Explicit preparation may repair corruption; local readiness never falls back.
            null
        }
        if (existing != null) {
            initialize(existing.file)
            return@storage existing
        }
        requireWifi()
        val staging = File(directory, "model.part")
        val installed = File(directory, artifact.fileName)
        safeFile(staging)
        Files.deleteIfExists(staging.toPath())
        if (availableBytes() < artifact.bytes + STORAGE_OVERHEAD_BYTES) throw TranslationFailure.Storage()
        var failure: Throwable? = null
        try {
            val deadline = System.nanoTime() + DOWNLOAD_DEADLINE_MILLIS * 1_000_000
            if (withTimeoutOrNull(DOWNLOAD_DEADLINE_MILLIS) {
                    download(staging, deadline, progress)
                    true
                } != true) throw PreparationFailure.PreparationTimedOut()
            val identity = attributes(staging)
            val published = Installed(installed, artifact, current = true)
            initialize(staging)
            currentCoroutineContext().ensureActive()
            Files.move(staging.toPath(), installed.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            commit.recordPublication(published)
            verifiedArtifact = artifact
            verifiedIdentity = identity
            afterPublish()
            published
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (commit.installed == null) {
                try { Files.deleteIfExists(staging.toPath()) } catch (cleanup: Exception) {
                    val original = failure
                    if (original == null) throw cleanup
                    if (original !== cleanup) original.addSuppressed(cleanup)
                }
            }
        }
    }

    internal suspend fun retirePrevious(): Unit = withContext(NonCancellable) {
        try {
            storage<Unit> {
                val file = File(directory, retired.fileName)
                safeFile(file)
                Files.deleteIfExists(file.toPath())
                if (verifiedArtifact == retired) verifiedIdentity = null
            }
        } catch (failure: TranslationFailure.Storage) {
            // Keep the committed installation. Retry retirement after the next successful load.
            logger.log(Level.WARNING, "Could not retire the previous Tencent artifact", failure)
        }
    }

    private suspend fun <T> storage(action: suspend () -> T): T = slot.withLock {
        try {
            withContext(Dispatchers.IO) {
                safeDirectory()
                action()
            }
        } catch (error: java.net.SocketTimeoutException) {
            throw PreparationFailure.PreparationTimedOut(error)
        } catch (error: IOException) {
            throw TranslationFailure.Storage(error)
        } catch (error: SecurityException) {
            throw TranslationFailure.Storage(error)
        }
    }

    private suspend fun download(staging: File, deadline: Long, progress: (TranslationPreparationState) -> Unit) {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = connect(deadline, context)
        try {
            if (connection.responseCode != 200) throw IOException("Unexpected artifact response")
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
            if (length != null && length != artifact.bytes) throw TranslationFailure.ArtifactCorrupt()
            var received = 0L
            var publishedBytes = 0L
            var publishedAt = System.nanoTime()
            fun publishDownload() = progress(TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING, received, artifact.bytes))
            publishDownload()
            connection.inputStream.use { input ->
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive(context, deadline)
                        requireWifi()
                        connection.readTimeout = remainingTimeout(deadline)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (received > artifact.bytes - count) throw TranslationFailure.ArtifactCorrupt()
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val now = System.nanoTime()
                        if (received - publishedBytes >= 4 * 1024 * 1024 || now - publishedAt >= 250_000_000L) {
                            publishDownload()
                            publishedBytes = received
                            publishedAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            publishDownload()
            progress(TranslationPreparationState(TranslationPreparationPhase.VERIFYING, received, artifact.bytes))
            if (received != artifact.bytes || hex(digest.digest()) != artifact.sha256) throw TranslationFailure.ArtifactCorrupt()
            checkActive(context, deadline)
        } finally { connection.disconnect() }
    }

    private fun connect(deadline: Long, context: CoroutineContext): HttpURLConnection {
        var url = URL(requireNotNull(artifact.url))
        repeat(6) { hop ->
            checkActive(context, deadline)
            requireWifi()
            if (url.protocol != "https" || url.host.lowercase() !in TencentTranslationArtifact.DELIVERY_HOSTS ||
                url.userInfo != null || (url.port != -1 && url.port != 443) || url.ref != null) {
                throw IOException("Untrusted artifact redirect")
            }
            val connection = openConnection(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remainingTimeout(deadline)
                connection.readTimeout = remainingTimeout(deadline)
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                if (code !in setOf(301, 302, 303, 307, 308)) return connection
                val location = connection.getHeaderField("Location")
                if (hop == 5 || location.isNullOrBlank()) throw IOException("Invalid artifact redirect")
                url = URL(url, location)
            } catch (error: Throwable) {
                connection.disconnect()
                throw error
            }
            connection.disconnect()
        }
        throw IOException("Too many artifact redirects")
    }

    private fun requireWifi() { if (!wifiAvailable()) throw PreparationFailure.WifiRequired() }

    private fun safeDirectory() {
        if (!::directory.isInitialized) {
            val resolvedDirectory = File(noBackupDirectory.toPath().toRealPath().toFile(), "tencent-translation")
            directory = resolvedDirectory
        }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create translation storage")
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile != directory.absoluteFile) {
            throw IOException("Unsafe translation storage")
        }
    }

    private fun safeFile(file: File) {
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS) && (!file.isFile || Files.isSymbolicLink(file.toPath()) ||
                file.canonicalFile != file.absoluteFile)) throw IOException("Unsafe translation artifact")
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun sameIdentity(first: BasicFileAttributes, second: BasicFileAttributes?): Boolean = second != null &&
        first.fileKey() == second.fileKey() && first.size() == second.size() && first.lastModifiedTime() == second.lastModifiedTime() &&
        first.creationTime() == second.creationTime()

    private suspend fun hash(file: File, context: CoroutineContext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    companion object {
        private val slot = Mutex()
        private val logger = Logger.getLogger(TencentArtifactPreparation::class.java.name)
        private const val DOWNLOAD_DEADLINE_MILLIS = 30 * 60 * 1000L
        private const val STORAGE_OVERHEAD_BYTES = 16 * 1024 * 1024L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun remainingTimeout(deadline: Long): Int =
            ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 60_000).toInt()
        private fun checkActive(context: CoroutineContext, deadline: Long) {
            context.ensureActive()
            if (System.nanoTime() >= deadline) throw PreparationFailure.PreparationTimedOut()
        }
        private fun NetworkCapabilities?.isWifi(): Boolean = this != null &&
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
