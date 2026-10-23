package com.example.offlinetranslator.speechmodels

import android.content.Context
import android.os.StatFs
import android.system.Os
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import android.system.OsConstants
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide installer; active package pointers change only after full verification. */
class SpeechModelStore private constructor(
    private val base: File,
    val catalog: SpeechModelCatalog,
    private val downloader: SpeechModelDownloader,
    private val extractor: SpeechArchiveExtractor,
    private val availableBytes: (File) -> Long,
    private val atomicRename: (File, File) -> Unit,
    private val syncDirectory: (File) -> Unit,
    /** Test-only observer of actual digest reads; never replaces verification. */
    internal var onDigestRead: ((packageId: String, file: File) -> Unit)? = null,
) : AutoCloseable {
    constructor(context: Context, catalog: SpeechModelCatalog) : this(
        File(context.noBackupFilesDir, "speech-models").canonicalFile,
        catalog,
        SpeechModelDownloader(),
        SpeechArchiveExtractor(),
        { directory -> StatFs(directory.absolutePath).availableBytes },
        { source, destination -> Os.rename(source.absolutePath, destination.absolutePath) },
        ::fsyncDirectory,
    )

    internal constructor(
        context: Context,
        catalog: SpeechModelCatalog,
        downloader: SpeechModelDownloader,
        extractor: SpeechArchiveExtractor,
        availableBytes: (File) -> Long,
        atomicRename: (File, File) -> Unit,
    ) : this(
        File(context.noBackupFilesDir, "speech-models").canonicalFile,
        catalog,
        downloader,
        extractor,
        availableBytes,
        atomicRename,
        {}, // JVM fixture rename seam; production always fsyncs directory publications.
    )

    internal constructor(
        context: Context,
        catalog: SpeechModelCatalog,
        downloader: SpeechModelDownloader,
        extractor: SpeechArchiveExtractor,
        onDigestRead: (String, File) -> Unit,
        availableBytes: (File) -> Long,
        atomicRename: (File, File) -> Unit,
    ) : this(
        File(context.noBackupFilesDir, "speech-models").canonicalFile,
        catalog, downloader, extractor, availableBytes, atomicRename, {}, onDigestRead,
    )

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var evaluationOnly = false
    /** Setup evidence for the isolated evaluator; never an inference measurement. */
    var evaluationImportCopiedBytes: Long = 0
        private set
    var evaluationImportReusedPackages: Int = 0
        private set

    private class VerifiedPackage(val directory: File, private val pkg: SpeechModelPackage) {
        private val files = pkg.files.map { File(directory, it.path) }
        private val modified = LongArray(files.size) { files[it].lastModified() }

        fun isCurrent(): Boolean = safeDirectory(directory) && files.indices.all { index ->
            val file = files[index]
            val spec = pkg.files[index]
            safeFile(file) && file.length() == spec.bytes && file.lastModified() == modified[index]
        }
    }

    private val verified = HashMap<String, VerifiedPackage>()
    private val state = MutableStateFlow(catalog.packages.map { it.status(SpeechModelInstallState.MISSING) })
    val packages: StateFlow<List<SpeechModelStatus>> = state.asStateFlow()

    suspend fun install(requiredPackageIds: Set<String>) = mutex.withLock {
        require(!closed.get()) { "Model store is closed" }
        withContext(Dispatchers.IO) {
            val selected = selectedPackages(requiredPackageIds)
            if (selected.isEmpty()) return@withContext
            ensureDirectory(base)
            cleanupAbandonedUnpacks(selected)
            for (pkg in selected) {
                currentCoroutineContext().ensureActive()
                check(!closed.get()) { "Model store closed during install" }
                if (verifyActive(pkg, verifyDigest = true)) {
                    publish(pkg, SpeechModelInstallState.READY)
                    continue
                }
                check(!evaluationOnly) { "Evaluation cannot download missing models" }
                try {
                    install(pkg)
                    publish(pkg, SpeechModelInstallState.READY)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    publish(pkg, SpeechModelInstallState.ERROR, failure.javaClass.simpleName)
                    throw failure
                }
            }
        }
    }

    suspend fun availability(requiredPackageIds: Set<String>): Boolean = mutex.withLock {
        require(!closed.get()) { "Model store is closed" }
        withContext(Dispatchers.IO) {
            var allReady = true
            for (pkg in selectedPackages(requiredPackageIds)) {
                currentCoroutineContext().ensureActive()
                val ready = verifyActive(pkg, verifyDigest = true)
                publish(pkg, if (ready) SpeechModelInstallState.READY else SpeechModelInstallState.MISSING)
                allReady = allReady && ready
            }
            allReady
        }
    }

    /** Returns only a catalog-declared file in a verified selected package. */
    suspend fun modelFile(packageId: String, path: String): File = mutex.withLock {
        require(!closed.get()) { "Model store is closed" }
        withContext(Dispatchers.IO) {
            val pkg = catalog.packageInfo(packageId)
            require(pkg.files.any { it.path == path }) { "Unknown model file" }
            require(verifyActive(pkg)) { "Model package is not ready" }
            File(verified.getValue(packageId).directory, path)
        }
    }

    fun packageInfo(id: String): SpeechModelPackage = catalog.packageInfo(id)

    private fun selectedPackages(ids: Set<String>): List<SpeechModelPackage> {
        ids.forEach(catalog::packageInfo)
        return catalog.packages.filter { it.id in ids }
    }

    override fun close() {
        closed.set(true)
    }

    /** Imports already hash-verified evaluation files; never downloads or changes production data. */
    internal suspend fun importEvaluationFiles(packageName: String, sourceRoot: File) = mutex.withLock {
        require(packageName == "com.example.offlinetranslator.modelsmoke") { "Evaluation import requires isolated test application" }
        require(!closed.get()) { "Model store is closed" }
        require(sourceRoot.canonicalFile == sourceRoot.absoluteFile && sourceRoot.isDirectory)
        withContext(Dispatchers.IO) {
            ensureDirectory(base)
            evaluationImportCopiedBytes=0; evaluationImportReusedPackages=0
            // Check every source before any copy or active-pointer publication.
            catalog.packages.forEach { pkg ->
                val source = File(sourceRoot, pkg.id)
                evaluationTree(pkg,source)
                require(source.canonicalFile == source.absoluteFile && verifyFiles(pkg, source)) { "Invalid evaluation package: ${pkg.id}" }
                pkg.files.forEach { spec -> require(File(source, spec.path).canonicalFile == File(source, spec.path).absoluteFile) }
            }
            val stages = LinkedHashMap<SpeechModelPackage, File>()
            try {
                val reusable=catalog.packages.filter {pkg ->
                    val destination=File(base,"installed/${pkg.id}-${pkg.archiveSha256.take(16)}")
                    if(destination.exists()) evaluationTree(pkg,destination)
                    verifyFiles(pkg,destination)
                }.toSet()
                require(availableBytes(base)>=catalog.packages.filter {it !in reusable}.sumOf {it.files.sumOf(SpeechModelFile::bytes)})
                catalog.packages.forEach { pkg ->
                    if(pkg in reusable) { evaluationImportReusedPackages++;return@forEach }
                    val packageStage = File(base, "staging/${pkg.id}")
                    ensureDirectory(packageStage)
                    val stage = File(packageStage, "unpacked-${UUID.randomUUID()}")
                    require(stage.mkdir())
                    stages[pkg] = stage
                    pkg.files.forEach { spec ->
                        val destination = File(stage, spec.path)
                        destination.parentFile?.mkdirs()
                        File(File(sourceRoot, pkg.id), spec.path).copyTo(destination)
                        evaluationImportCopiedBytes+=spec.bytes
                    }
                    require(verifyFiles(pkg, stage))
                }
                stages.forEach { (pkg, stage) ->
                    val destination = File(base, "installed/${pkg.id}-${pkg.archiveSha256.take(16)}")
                    ensureDirectory(requireNotNull(destination.parentFile))
                    if(destination.exists()) {
                        // Only a checked exact evaluation layout may be replaced.
                        evaluationTree(pkg,destination).sortedByDescending {it.absolutePath.length}.forEach {check(it.delete())}
                        verified.remove(pkg.id)
                    }
                    atomicRename(stage, destination)
                    syncDirectory(requireNotNull(destination.parentFile))
                }
                catalog.packages.forEach { pkg ->
                    val destination = File(base, "installed/${pkg.id}-${pkg.archiveSha256.take(16)}")
                    updatePointer(pkg, destination.name)
                    verified[pkg.id] = VerifiedPackage(destination, pkg)
                    publish(pkg, SpeechModelInstallState.READY)
                }
                evaluationOnly = true
            } finally { stages.values.forEach { if (exists(it)) deletePrivateTree(it) } }
        }
    }

    private fun evaluationTree(pkg: SpeechModelPackage,directory: File): List<File> {
        require(directory.canonicalFile==directory.absoluteFile && directory.isDirectory)
        val expected=pkg.files.map {it.path}.toMutableSet()
        pkg.files.forEach {spec ->
            var parent=File(spec.path).parentFile
            while(parent!=null) {expected.add(parent.invariantSeparatorsPath);parent=parent.parentFile}
        }
        val members=directory.walkTopDown().maxDepth(14).take(14001).toList()
        require(members.size<=14000 && members.all {it.canonicalFile==it.absoluteFile && (it.isFile || it.isDirectory)})
        require(members.drop(1).all {it.relativeTo(directory).invariantSeparatorsPath in expected}) {"Foreign evaluation model members"}
        return members
    }

    companion object {
        suspend fun fromVerifiedEvaluationFiles(context: Context, catalog: SpeechModelCatalog, sourceRoot: File): SpeechModelStore {
            require(context.packageName == "com.example.offlinetranslator.modelsmoke")
            // Context owns this path; Android may expose /data/user/0 through /data/data.
            // Canonicalize only that trusted root, never imported/cache paths.
            val store = SpeechModelStore(evaluationBase(context), catalog, SpeechModelDownloader(), SpeechArchiveExtractor(),
                { StatFs(it.absolutePath).availableBytes }, { source, destination -> Os.rename(source.absolutePath, destination.absolutePath) },
                ::fsyncDirectory)
            try { store.importEvaluationFiles(context.packageName, sourceRoot); return store }
            catch (failure: Throwable) { store.close(); throw failure }
        }
        internal fun evaluationBase(context: Context): File = File(context.noBackupFilesDir,"speech-models").canonicalFile

        private fun exists(file: File): Boolean = Files.exists(file.toPath(), NOFOLLOW_LINKS)
        private fun safeFile(file: File): Boolean = file.isFile &&
            !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile
        private fun safeDirectory(file: File): Boolean = file.isDirectory &&
            !Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile

        private fun fsyncDirectory(directory: File) {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }
        private val LEGACY_POINTERS = mapOf(
            "vad-silero-6.2.2" to "VAD",
            "ru-gigaam-v1-ctc-offline-2024-10-24" to "RUSSIAN_ASR",
            "en-whisper-tiny.en-offline" to "ENGLISH_ASR",
            "language-whisper-tiny-encoder" to "LANGUAGE_ENCODER",
            "language-whisper-tiny-decoder" to "LANGUAGE_DECODER",
            "ru-piper-irina-medium" to "RUSSIAN_TTS",
            "en-piper-lessac-medium" to "ENGLISH_TTS",
        )
    }

    private suspend fun install(pkg: SpeechModelPackage) {
        val finalDir = installedDirectory(pkg)
        if (exists(finalDir)) {
            require(verifyFiles(pkg, finalDir)) { "Existing model directory is corrupt" }
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Model store closed during install" }
            updatePointer(pkg, finalDir.name)
            verified[pkg.id] = VerifiedPackage(finalDir, pkg)
            return
        }
        val stage = File(base, "staging/${pkg.id}")
        ensureDirectory(stage)
        val available = availableBytes(base)
        val needed = pkg.archiveBytes + pkg.maxUnpackedBytes + 32L * 1_024 * 1_024 -
            File(stage, "archive.part").length().coerceIn(0L, pkg.archiveBytes)
        require(available >= needed) { "Insufficient storage for speech model" }
        publish(pkg, SpeechModelInstallState.DOWNLOADING)
        val archive = downloader.download(pkg, stage) { downloaded ->
            check(!closed.get()) { "Model store closed during install" }
            publish(pkg, SpeechModelInstallState.DOWNLOADING, downloaded = downloaded)
        }
        publish(pkg, SpeechModelInstallState.VERIFYING, downloaded = pkg.archiveBytes)
        val unpacked = File(stage, "unpacked-${UUID.randomUUID()}")
        require(unpacked.mkdir()) { "Cannot create unpack staging" }
        try {
            extractor.extract(pkg, archive, unpacked)
            require(verifyFiles(pkg, unpacked)) { "Installed model verification failed" }
            val installedRoot = requireNotNull(finalDir.parentFile)
            ensureDirectory(installedRoot)
            if (exists(finalDir)) {
                require(verifyFiles(pkg, finalDir)) { "Existing model directory is corrupt" }
            } else {
                atomicRename(unpacked, finalDir)
                syncDirectory(installedRoot)
            }
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Model store closed during install" }
            updatePointer(pkg, finalDir.name)
            verified[pkg.id] = VerifiedPackage(finalDir, pkg)
            archive.delete()
        } finally {
            if (exists(unpacked)) deletePrivateTree(unpacked)
        }
    }

    private fun ensureDirectory(directory: File) {
        directory.parentFile?.let { parent ->
            if (!exists(parent)) ensureDirectory(parent)
            require(safeDirectory(parent)) { "Unsafe model parent directory" }
        }
        require(directory.mkdirs() || directory.isDirectory) { "Cannot create model directory" }
        require(safeDirectory(directory)) { "Unsafe model directory" }
    }

    private fun deletePrivateTree(directory: File) {
        require(safeDirectory(directory)) { "Unsafe staging directory" }
        // Check the entire tree before deleting anything, never traverse symbolic links.
        val members = ArrayList<File>()
        fun collect(file: File) {
            require(safeDirectory(file) || safeFile(file)) { "Unsafe staging member" }
            members.add(file)
            if (file.isDirectory) {
                val children = requireNotNull(file.listFiles()) { "Cannot inspect private staging" }
                children.forEach(::collect)
            }
        }
        collect(directory)
        members.asReversed().forEach { check(it.delete()) { "Cannot remove private staging" } }
    }

    private fun cleanupAbandonedUnpacks(selected: List<SpeechModelPackage>) {
        for (pkg in selected) {
            val packageStage = File(base, "staging/${pkg.id}")
            if (!exists(packageStage)) continue
            require(safeDirectory(packageStage)) { "Unsafe staging directory" }
            packageStage.listFiles()?.forEach { child ->
                if (child.name.matches(Regex("unpacked-[0-9a-f-]{36}")) && safeDirectory(child)) {
                    deletePrivateTree(child)
                }
            }
        }
    }

    private fun installedDirectory(pkg: SpeechModelPackage): File =
        File(base, "installed/${pkg.id}-${pkg.archiveSha256.take(16)}")

    private suspend fun verifyActive(pkg: SpeechModelPackage, verifyDigest: Boolean = false): Boolean {
        val pointer = File(base, "active-package-${pkg.id}.txt")
        val directory = installedDirectory(pkg)
        val cached = verified[pkg.id]
        // A present but corrupt schema2 pointer is authoritative: never consult legacy.
        if (exists(pointer)) {
            if (!validPointer(pointer, directory.name)) {
                verified.remove(pkg.id)
                return false
            }
            if (!verifyDigest && cached?.isCurrent() == true) return true
        } else {
            verified.remove(pkg.id)
            val legacyName = LEGACY_POINTERS[pkg.id] ?: return false
            val legacy = File(base, "active-$legacyName.txt")
            if (!validPointer(legacy, directory.name) || !verifyFiles(pkg, directory)) return false
            currentCoroutineContext().ensureActive()
            check(!closed.get()) { "Model store closed during migration" }
            updatePointer(pkg, directory.name)
            verified[pkg.id] = VerifiedPackage(directory, pkg)
            return true
        }
        verified.remove(pkg.id)
        if (!verifyFiles(pkg, directory)) return false
        verified[pkg.id] = VerifiedPackage(directory, pkg)
        return true
    }

    private fun validPointer(pointer: File, expectedName: String): Boolean =
        safeFile(pointer) && pointer.length() <= 128 && pointer.readText(Charsets.UTF_8) == expectedName

    private fun verifyFiles(pkg: SpeechModelPackage, directory: File): Boolean =
        safeDirectory(directory) && pkg.files.all { spec ->
            val file = File(directory, spec.path)
            safeFile(file) && file.length() == spec.bytes && run {
                onDigestRead?.invoke(pkg.id, file)
                SpeechModelDownloader.sha256(file) == spec.sha256
            }
        }

    private fun updatePointer(pkg: SpeechModelPackage, directoryName: String) {
        require(directoryName == installedDirectory(pkg).name)
        ensureDirectory(base)
        val pointer = File(base, "active-package-${pkg.id}.txt")
        val temp = File(base, "${pointer.name}.tmp")
        require(!exists(temp) || safeFile(temp)) { "Unsafe pointer staging file" }
        require(!exists(pointer) || safeFile(pointer)) { "Unsafe active pointer" }
        FileOutputStream(temp).use { output ->
            output.write(directoryName.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(!closed.get()) { "Model store closed during publication" }
        atomicRename(temp, pointer)
        syncDirectory(base)
    }

    private fun publish(
        pkg: SpeechModelPackage,
        next: SpeechModelInstallState,
        error: String? = null,
        downloaded: Long = 0,
    ) {
        state.value = state.value.map { current ->
            if (current.id == pkg.id) {
                pkg.status(next, downloaded, error)
            } else {
                current
            }
        }
    }

    private fun SpeechModelPackage.status(
        next: SpeechModelInstallState,
        downloaded: Long = 0,
        error: String? = null,
    ) = SpeechModelStatus(id, displayName, source, license, archiveBytes, next, downloaded, error)
}
