import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID
import org.json.JSONObject

class RunWorkspace(val repo: File) : AutoCloseable {
    val id = UUID.randomUUID().toString()
    val base = File(repo,"build/translator-verification")
    val journal = File(base,"run-state.json")
    val directory = File(base,"runs/$id")
    private val channel: FileChannel
    private val lock: FileLock
    fun requireOwnership() { check(lock.isValid && channel.isOpen) { "Verification ownership is no longer held" } }
    init {
        val build=File(repo,"build")
        build.mkdirs();require(build.canonicalFile==build.absoluteFile) { "Workspace build directory is an alias" }
        base.mkdirs();require(base.canonicalFile==base.absoluteFile) { "Workspace staging directory is an alias" }
        val lockFile=File(repo,"build/translator-verification.lock")
        require(lockFile.canonicalFile==lockFile.absoluteFile) { "Workspace lock is an alias" }
        channel=FileChannel.open(lockFile.toPath(),StandardOpenOption.CREATE,StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)
        lock=try { requireNotNull(channel.tryLock()) { "Another verification run owns workspace" } } catch (failure: Throwable) { channel.close(); throw failure }
    }
    fun record(serial: String) {
        require(serial.matches(Regex("emulator-[0-9]+")))
        require(directory.canonicalFile==directory.absoluteFile && journal.canonicalFile==journal.absoluteFile)
        publish(JSONObject().put("runId",id).put("serial",serial).put("target",AdbClient.TARGET).put("test",AdbClient.TEST))
        directory.mkdirs()
    }
    fun saveNetwork(wifi: String,airplane: String) {
        val state=JSONObject(journal.readText()).put("wifi",wifi).put("airplane",airplane)
        publish(state)
    }
    private fun publish(state: JSONObject) {
        val partial=File(base,"run-state.partial.json")
        java.io.FileOutputStream(partial).use { output -> output.write(state.toString().toByteArray(Charsets.UTF_8));output.fd.sync() }
        Files.move(partial.toPath(),journal.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    fun cleanupHost() {
        val prior=if(journal.isFile) JSONObject(journal.readText()).getString("runId") else id
        require(prior.matches(Regex("[a-f0-9-]{36}")))
        safeDelete(File(base,"runs/$prior"),File(repo,"build"))
        if(prior != id) safeDelete(directory,File(repo,"build"))
    }
    override fun close() { lock.release(); channel.close() }
    companion object {
        fun safeDelete(target: File, allowedRoot: File) {
            if (!target.exists()) return
            require(target.absoluteFile.toPath().startsWith(allowedRoot.absoluteFile.toPath()) && target != allowedRoot)
            require(target.canonicalFile==target.absoluteFile)
            val paths=Files.walk(target.toPath(),32).use { it.limit(100001).toList() }
            require(paths.size<=100000 && paths.none { Files.isSymbolicLink(it) || !it.toRealPath().startsWith(allowedRoot.canonicalFile.toPath()) }) { "Unsafe recursive cleanup target" }
            paths.sortedByDescending { it.nameCount }.forEach(Files::delete)
        }
    }
}
