package com.example.offlinetranslator.tencent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlin.coroutines.CoroutineContext

/** Process-shared installation/attestation only. Mutable native inference is owned by each engine. */
class TencentArtifactPreparation internal constructor(
    private val noBackupDirectory: File,
    private val artifact: TencentTranslationArtifact = TencentTranslationArtifact.PINNED,
    private val wifiAvailable: () -> Boolean,
    private val openConnection: (URL) -> HttpURLConnection,
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
            // Bind to Wi-Fi: a default-network change cannot silently transfer weights over cellular.
            network.openConnection(url) as HttpURLConnection
        },
    )

    private lateinit var directory: File
    private val slot = Mutex()
    private val mutableState = MutableStateFlow(TranslationPreparationState())
    internal val state = mutableState.asStateFlow()
    private lateinit var installed: File
    private var verifiedIdentity: BasicFileAttributes? = null

    internal suspend fun verifiedFile(verifying: () -> Unit = {}): File? = slot.withLock {
        try {
            withContext(Dispatchers.IO) {
                safeDirectory()
                if (!Files.exists(installed.toPath(), NOFOLLOW_LINKS)) return@withContext null
                safeFile(installed)
                val identity = attributes()
                if (!sameIdentity(identity, verifiedIdentity)) {
                    mutableState.value = TranslationPreparationState(TranslationPreparationPhase.VERIFYING)
                    verifying()
                    if (identity.size() != artifact.bytes || hash(installed, currentCoroutineContext()) != artifact.sha256) {
                        verifiedIdentity = null
                        throw TranslationFailure.ArtifactCorrupt()
                    }
                    verifiedIdentity = identity
                }
                installed
            }
        } catch (error: IOException) {
            fail(TranslationFailure.Storage(error))
        } catch (error: SecurityException) {
            fail(TranslationFailure.Storage(error))
        } catch (failure: TranslationFailure) {
            fail(failure)
        }
    }

    internal suspend fun prepare(): File = slot.withLock {
        try {
            val deadline = System.nanoTime() + DOWNLOAD_DEADLINE_MILLIS * 1_000_000
            withTimeoutOrNull(DOWNLOAD_DEADLINE_MILLIS) {
                withContext(Dispatchers.IO) {
                    safeDirectory()
                    mutableState.value = TranslationPreparationState(TranslationPreparationPhase.CHECKING)
                    if (Files.exists(installed.toPath(), NOFOLLOW_LINKS)) {
                        safeFile(installed)
                        val identity = attributes()
                        if (sameIdentity(identity, verifiedIdentity)) return@withContext installed
                        mutableState.value = TranslationPreparationState(TranslationPreparationPhase.VERIFYING)
                        if (identity.size() == artifact.bytes && hash(installed, currentCoroutineContext()) == artifact.sha256) {
                            verifiedIdentity = identity
                            return@withContext installed
                        }
                        verifiedIdentity = null
                        // Explicit Prepare may repair corruption; readiness never adopts corrupt bytes.
                    }
                    requireWifi()
                    val staging = File(directory, "model.part")
                    safeFile(staging)
                    // A crash-left stage is never adopted and must not consume the next install's free-space budget.
                    Files.deleteIfExists(staging.toPath())
                    if (availableBytes() < artifact.bytes + STORAGE_OVERHEAD_BYTES) throw TranslationFailure.Storage()
                    val context = currentCoroutineContext()
                    var transferFailure: Throwable? = null
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        val connection = connect(deadline, context)
                        try {
                            if (connection.responseCode != 200) throw IOException("Unexpected artifact response")
                            val length = connection.getHeaderField("Content-Length")?.toLongOrNull()
                            if (length != null && length != artifact.bytes) throw TranslationFailure.ArtifactCorrupt()
                            var received = 0L
                            var publishedBytes = 0L
                            var publishedAt = System.nanoTime()
                            publishDownload(received)
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
                                            publishDownload(received)
                                            publishedBytes = received
                                            publishedAt = now
                                        }
                                    }
                                    output.fd.sync()
                                }
                            }
                            publishDownload(received)
                            mutableState.value = TranslationPreparationState(TranslationPreparationPhase.VERIFYING, received, artifact.bytes)
                            if (received != artifact.bytes || hex(digest.digest()) != artifact.sha256) throw TranslationFailure.ArtifactCorrupt()
                            checkActive(context, deadline)
                            Files.move(staging.toPath(), installed.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                            verifiedIdentity = attributes()
                            installed
                        } finally { connection.disconnect() }
                    } catch (error: Throwable) {
                        transferFailure = error
                        throw error
                    } finally {
                        try { Files.deleteIfExists(staging.toPath()) } catch (cleanup: Throwable) {
                            val original = transferFailure
                            if (original == null) throw cleanup else original.addSuppressed(cleanup)
                        }
                    }
                }
            } ?: throw PreparationFailure.PreparationTimedOut()
        } catch (cancelled: CancellationException) {
            mutableState.value = TranslationPreparationState(TranslationPreparationPhase.MISSING)
            throw cancelled
        } catch (failure: TranslationFailure) {
            fail(failure)
        } catch (error: java.net.SocketTimeoutException) {
            fail(PreparationFailure.PreparationTimedOut(error))
        } catch (error: IOException) {
            fail(TranslationFailure.Storage(error))
        } catch (error: SecurityException) {
            fail(TranslationFailure.Storage(error))
        }
    }

    private fun fail(failure: TranslationFailure): Nothing {
        mutableState.value = TranslationPreparationState(TranslationPreparationPhase.FAILED, lastFailure = failure)
        throw failure
    }

    private fun publishDownload(bytes: Long) {
        mutableState.value = TranslationPreparationState(TranslationPreparationPhase.DOWNLOADING, bytes, artifact.bytes)
    }

    private fun connect(deadline: Long, context: CoroutineContext): HttpURLConnection {
        var url = URL(artifact.url)
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
            installed = File(resolvedDirectory, "Hy-MT2-1.8B-Q4_K_M.gguf")
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

    private fun attributes(): BasicFileAttributes = Files.readAttributes(installed.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)

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
