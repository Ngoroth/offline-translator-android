import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class CommandResult(val exitCode: Int, val output: ByteArray) {
    val text: String get() = output.toString(Charsets.UTF_8)
}

object BoundedProcess {
    fun run(argv: List<String>,timeoutSeconds: Long,maxBytes: Int,directory: File? = null): CommandResult {
        require(timeoutSeconds>0 && maxBytes in 1..16*1024*1024)
        val process = ProcessBuilder(argv).directory(directory).start()
        val output = ByteArrayOutputStream()
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val readers = listOf(process.inputStream, process.errorStream).map { stream -> thread(isDaemon=true) {
            try { stream.use {
                val buffer = ByteArray(65536)
                while (true) {
                    val count = it.read(buffer); if (count < 0) break
                    synchronized(output) {
                        require(output.size() + count <= maxBytes) { "Process output quota exceeded" }
                        output.write(buffer,0,count)
                    }
                }
            } } catch (failure: Throwable) { errors.add(failure); process.destroyForcibly() }
        } }
        if (!process.waitFor(timeoutSeconds,TimeUnit.SECONDS)) {
            process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);readers.forEach {it.join(5000)}
            throw IllegalStateException("Command deadline exceeded: ${argv.firstOrNull()}")
        }
        readers.forEach { it.join(5000) }
        require(readers.none { it.isAlive } && errors.isEmpty()) { "Process stream failure: ${errors.peek()?.message}" }
        return CommandResult(process.exitValue(),output.toByteArray())
    }
}

/** All subprocesses have bounded duration/output; remote arguments are shell-quoted. */
open class AdbClient(private val executable: File, var serial: String? = null) {
    open fun command(vararg args: String, timeoutSeconds: Long = 60, maxBytes: Int = 4 * 1024 * 1024): CommandResult {
        val argv = listOf(executable.absolutePath) + (serial?.let { listOf("-s", it) } ?: emptyList()) + args
        return BoundedProcess.run(argv,timeoutSeconds,maxBytes)
    }
    fun checked(vararg args: String, timeoutSeconds: Long = 60, maxBytes: Int = 4 * 1024 * 1024): String {
        val result = command(*args,timeoutSeconds=timeoutSeconds,maxBytes=maxBytes)
        check(result.exitCode==0) { "adb ${args.firstOrNull()} failed: ${result.text.take(1000)}" }
        return result.text.trim()
    }
    fun shell(vararg args: String): String = checked("shell",args.joinToString(" ",transform=::quote))
    fun discover(): String {
        check(serial == null)
        val lines = checked("devices","-l").lineSequence().drop(1).filter(String::isNotBlank).toList()
        require(lines.size==1) { "Exactly one attached online emulator is required" }
        val fields = lines.single().split(Regex("\\s+"))
        require(fields.size>=2 && fields[1]=="device" && fields[0].matches(Regex("emulator-[0-9]+"))) { "Physical/offline/unauthorized device is forbidden" }
        serial=fields[0]
        require(shell("getprop","ro.kernel.qemu")=="1")
        require(shell("getprop","ro.product.cpu.abi") in setOf("x86_64","arm64-v8a"))
        require(shell("getprop","ro.build.version.sdk").toInt()>=23)
        return fields[0]
    }
    companion object {
        const val TARGET = "com.example.offlinetranslator.modelsmoke"
        const val TEST = "$TARGET.test"
        const val DEVICE_ROOT = "/data/local/tmp/offline-translator-evaluation"
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
