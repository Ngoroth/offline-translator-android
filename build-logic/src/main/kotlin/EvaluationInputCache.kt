import com.example.offlinetranslator.testkit.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** One owned, bounded input store. All callers hold the original verification lock. */
class EvaluationInputCache(
    private val owner: RunWorkspace,
    models: List<EvaluationModel>,
    derivedKey: String? = null,
    private val modelBudget: Long = MODEL_BUDGET,
    private val derivedBudget: Long = DERIVED_BUDGET,
    private val policy: String = POLICY,
) {
    val root = File(owner.repo,".cache/translator-evaluation")
    private val active = models.flatMap { listOf("archives/${archiveKey(it)}", "bundles/${bundleKey(it,policy)}") }.toSet() +
        listOfNotNull(derivedKey?.let { "derived/$it" })
    data class Entry(val directory: File, val hit: Boolean, val verificationMs: Double, val bytes: Long)

    init {
        owner.requireOwnership()
        require(modelBudget in 1..MODEL_BUDGET && derivedBudget in 1..DERIVED_BUDGET)
        val parent = root.parentFile
        if (!parent.exists()) require(parent.mkdir())
        safe(parent)
        if (!root.exists()) {
            require(root.mkdir())
            sync(File(root,"owner.txt"), MAGIC.toByteArray())
        }
        safe(root)
        require(File(root,"owner.txt").let { safe(it); it.isFile && it.length()==MAGIC.length.toLong() && it.readText()==MAGIC }) { "Foreign cache root" }
        for (kind in KINDS) {
            val directory=File(root,kind)
            if (!directory.exists()) require(directory.mkdir())
            safe(directory)
        }
        require(root.list()!!.toSet()==KINDS.toSet()+"owner.txt") { "Foreign cache members" }
        housekeeping()
        val archiveMisses=models.distinctBy(::archiveKey).filter { !File(root,"archives/${archiveKey(it)}").exists() }.sumOf {it.json.getLong("archiveBytes")}
        val bundleMisses=models.distinctBy {bundleKey(it,policy)}.filter { !File(root,"bundles/${bundleKey(it,policy)}").exists() }.sumOf {it.files.sumOf(ModelFile::bytes)}
        SpaceRequirements.host(owner.repo.usableSpace,archiveMisses+bundleMisses+models.sumOf {it.files.sumOf(ModelFile::bytes)}+2*DERIVED_BUDGET)
    }

    fun obtain(kind: String,key: String,expected: List<ModelFile>?,reservation: Long,deadline: Long,
               validate: (File) -> Unit = {}, build: (File) -> List<ModelFile>): Entry {
        owner.requireOwnership(); checkDeadline(deadline)
        require(kind in KINDS && key.matches(Regex("[a-f0-9]{64}")) && reservation>0)
        val entry=File(root,"$kind/$key")
        if (Files.exists(entry.toPath(),NOFOLLOW_LINKS)) {
            // Safety/ownership errors are never treated as ordinary corruption.
            inspect(entry,kind,key)
            val start=System.nanoTime()
            val valid=runCatching { verify(entry,expected,deadline);validate(File(entry,"data")) }.isSuccess
            checkDeadline(deadline)
            if (valid) return Entry(File(entry,"data"),true,elapsed(start),payload(entry))
            remove(entry,kind,key)
        }
        val retained=entries().filter { if(kind=="derived") it.parentFile.name=="derived" else it.parentFile.name!="derived" }.sumOf(::payload)
        val budget=if(kind=="derived") derivedBudget else modelBudget
        require(reservation<=budget-retained) { "Input cache quota exceeded" }
        SpaceRequirements.host(owner.repo.usableSpace,reservation)
        val partial=File(root,"$kind/partial-${owner.id}")
        require(!partial.exists() && partial.mkdir())
        // A fixed marker owns this bounded tree, not any paths supplied by metadata.
        sync(File(partial,"owner.json"),identity(kind,key).toString().toByteArray())
        require(File(partial,"data").mkdir())
        try {
            val specs=build(File(partial,"data"))
            checkDeadline(deadline)
            require(specs.sumOf(ModelFile::bytes)<=reservation)
            val receipt=identity(kind,key).put("files",JSONArray(specs.sortedBy {it.path}.map {
                JSONObject().put("path",it.path).put("bytes",it.bytes).put("sha256",it.sha256)
            }))
            sync(File(partial,"receipt.json"),receipt.toString().toByteArray())
            val start=System.nanoTime()
            verify(partial,expected,deadline);validate(File(partial,"data"))
            tree(partial).filter(File::isFile).forEach { FileOutputStream(it,true).use { stream -> stream.fd.sync() } }
            Files.move(partial.toPath(),entry.toPath(),ATOMIC_MOVE)
            return Entry(File(entry,"data"),false,elapsed(start),specs.sumOf(ModelFile::bytes))
        } finally {
            if(Files.exists(partial.toPath(),NOFOLLOW_LINKS)) remove(partial,kind,key)
        }
    }

    fun receipt(): JSONObject {
        owner.requireOwnership()
        val entries=entries()
        return JSONObject().put("root",".cache/translator-evaluation").put("retainedBytes",entries.sumOf(::payload))
            .put("modelBytes",entries.filter {it.parentFile.name!="derived"}.sumOf(::payload))
            .put("derivedBytes",entries.filter {it.parentFile.name=="derived"}.sumOf(::payload)).put("entryCount",entries.size)
    }

    private fun housekeeping() {
        val entries=entries()
        // Validate every entry's bounded ownership before any deletion.
        entries.forEach { entry -> inspect(entry,entry.parentFile.name,entryKey(entry)) }
        entries.filter { it.name.startsWith("partial-") || "${it.parentFile.name}/${it.name}" !in active }
            .forEach { remove(it,it.parentFile.name,entryKey(it)) }
        val remaining=entries()
        require(remaining.filter {it.parentFile.name!="derived"}.sumOf(::payload)<=modelBudget)
        require(remaining.filter {it.parentFile.name=="derived"}.sumOf(::payload)<=derivedBudget)
    }
    private fun entries(): List<File> = KINDS.flatMap { kind ->
        val directory=File(root,kind);safe(directory)
        val children=directory.listFiles() ?: error("Cannot inventory cache")
        require(children.size<=33) { "Cache entry count quota exceeded" }
        children.toList()
    }.also { require(it.sumOf { entry -> tree(entry).size }<=430000) { "Cache scan quota exceeded" } }
    private fun entryKey(entry: File): String {
        safe(entry)
        if(entry.name.matches(Regex("[a-f0-9]{64}"))) return entry.name
        require(entry.name.matches(Regex("partial-[a-f0-9-]{36}"))) { "Foreign cache entry" }
        return Integrity.json(File(entry,"owner.json")).getString("key").also {require(it.matches(Regex("[a-f0-9]{64}")))}
    }
    private fun identity(kind: String,key: String) = JSONObject().put("owner",MAGIC).put("kind",kind).put("key",key)
    private fun inspect(entry: File,kind: String,key: String) {
        require(entry.isDirectory);tree(entry)
        windowsHardlinks(entry)
        val marker=Integrity.json(File(entry,"owner.json"))
        require(marker.getString("owner")==MAGIC && marker.getString("kind")==kind && marker.getString("key")==key) { "Foreign cache entry" }
        require(entry.list()!!.all {it in setOf("owner.json","receipt.json","data")}) { "Foreign cache metadata" }
    }
    private fun verify(entry: File,expected: List<ModelFile>?,deadline: Long) {
        val receipt=Integrity.json(File(entry,"receipt.json"))
        val marker=Integrity.json(File(entry,"owner.json"))
        require(listOf("owner","kind","key").all {receipt.getString(it)==marker.getString(it)})
        val rows=receipt.getJSONArray("files");require(rows.length() in 1..1024)
        val specs=(0 until rows.length()).map { rows.getJSONObject(it).let { row -> ModelFile(row.getString("path"),row.getLong("bytes"),row.getString("sha256")) } }
        require(specs.map {it.path.lowercase(java.util.Locale.ROOT)}.toSet().size==specs.size)
        if(expected!=null) require(specs.sortedBy {it.path}==expected.sortedBy {it.path})
        val data=File(entry,"data")
        val expectedMembers=specs.map {it.path}.toMutableSet()
        specs.forEach { spec ->
            require(Integrity.safePath(spec.path) && spec.bytes>0)
            var parent=File(spec.path).parentFile
            while(parent!=null) {expectedMembers.add(parent.invariantSeparatorsPath);parent=parent.parentFile}
        }
        require(tree(data).drop(1).map {it.relativeTo(data).invariantSeparatorsPath}.toSet()==expectedMembers) { "Cache membership mismatch" }
        specs.forEach { spec ->
            checkDeadline(deadline)
            val file=File(data,spec.path)
            require(spec.sha256.matches(Regex("[a-f0-9]{64}")) && file.isFile && file.length()==spec.bytes)
            val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input ->
                val buffer=ByteArray(65536)
                while(true) {checkDeadline(deadline);val count=input.read(buffer);if(count<0) break;digest.update(buffer,0,count)}
            }
            require(digest.digest().joinToString("") {"%02x".format(it)}==spec.sha256) {"Cache content hash mismatch"}
        }
        checkDeadline(deadline)
    }
    private fun payload(entry: File): Long = File(entry,"data").takeIf(File::isDirectory)?.let {tree(it).filter(File::isFile).sumOf(File::length)} ?: 0L
    private fun remove(entry: File,kind: String,key: String) {
        inspect(entry,kind,key)
        tree(entry).sortedByDescending {it.toPath().nameCount}.forEach { Files.delete(it.toPath()) }
    }
    private fun tree(directory: File): List<File> {
        safe(directory)
        val paths=Files.walk(directory.toPath(),15).use {it.limit(14001).map { path -> path.toFile() }.toList()}
        require(paths.size<=14000) { "Cache tree quota exceeded" }
        val keys=HashSet<Any>()
        paths.forEach { file ->
            safe(file)
            val attrs=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            require(attrs.isDirectory || attrs.isRegularFile) { "Non-regular cache member" }
            if(attrs.isRegularFile) {
                require(attrs.size()<=MODEL_BUDGET)
                attrs.fileKey()?.let { require(keys.add(it)) { "Aliased cache files" } }
                if(file.toPath().fileSystem.supportedFileAttributeViews().contains("unix"))
                    require((Files.getAttribute(file.toPath(),"unix:nlink",NOFOLLOW_LINKS) as Number).toInt()==1) { "Hardlinked cache file" }
            }
        }
        return paths
    }
    private fun safe(file: File) {
        require(file.canonicalFile==file.absoluteFile && !Files.isSymbolicLink(file.toPath())) { "Unsafe cache alias: ${file.name}" }
    }
    // Java's Windows attribute view omits link count. Use the already-required host Python
    // in one bounded read-only inventory, rather than spawning a process for every model file.
    private fun windowsHardlinks(entry: File) {
        if(!System.getProperty("os.name").startsWith("Windows")) return
        val script="""
import os, sys
count = 0
for parent, dirs, files in os.walk(sys.argv[1], followlinks=False):
    for name in dirs + files:
        count += 1
        if count > 14000: raise ValueError('link scan quota')
        path = os.path.join(parent, name)
        info = os.stat(path, follow_symlinks=False)
        if os.path.islink(path) or os.path.isjunction(path) or (os.path.isfile(path) and info.st_nlink != 1):
            raise ValueError('linked cache member')
""".trimIndent()
        require(BoundedProcess.run(listOf("python","-B","-c",script,entry.absolutePath),30,4096).exitCode==0) {"Cache hardlink inventory failed"}
    }
    companion object {
        const val MODEL_BUDGET=4L*1024*1024*1024
        const val DERIVED_BUDGET=32L*1024*1024
        const val POLICY="secure-archive-v1"
        private const val MAGIC="offline-translator-evaluation-inputs-v1"
        private val KINDS=listOf("archives","bundles","derived")
        fun key(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        fun archiveKey(model: EvaluationModel)=key("${model.json.getString("archiveSha256")}:${model.json.getLong("archiveBytes")}")
        fun bundleKey(model: EvaluationModel,policy: String=POLICY)=key(listOf(archiveKey(model),policy,model.json.getString("format"),
            model.json.getString("rootPrefix"),model.json.getInt("maxEntries"),model.json.getLong("maxUnpackedBytes"),
            model.files.sortedBy {it.path}.joinToString("\n") {"${it.path}:${it.bytes}:${it.sha256}"}).joinToString("\n"))
        fun checkDeadline(deadline: Long) {require(System.nanoTime()<deadline) {"Input preparation deadline exceeded"}}
        fun elapsed(start: Long)=(System.nanoTime()-start)/1_000_000.0
        private fun sync(file: File,bytes: ByteArray) {
            require(bytes.size<=1048576)
            FileOutputStream(file).use {it.write(bytes);it.fd.sync()}
        }
    }
}
