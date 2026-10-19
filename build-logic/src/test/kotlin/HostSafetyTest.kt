import com.example.offlinetranslator.testkit.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class HostSafetyTest {
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun model(root: File, expected: ByteArray)=EvaluationModel(JSONObject()
        .put("id","fixture").put("component","VAD").put("language","both").put("adapter","silero")
        .put("format","tar.bz2").put("rootPrefix","fixture").put("maxEntries",16).put("maxUnpackedBytes",1024)
        .put("files",JSONArray().put(JSONObject().put("path","model.onnx").put("bytes",expected.size).put("sha256",hash(expected)))),root)
    @Test fun ignoredTraversalAndLinkEntriesAreRejected() {
        val root=Files.createTempDirectory("archive-test").toFile()
        try {
            val bytes="model".toByteArray()
            for(name in listOf("../ignored","fixture/../ignored","fixture/link")) {
                val archive=File(root,"archive")
                TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
                    val entry=if(name.endsWith("link")) TarArchiveEntry(name,org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK) else TarArchiveEntry(name)
                    entry.size=0; entry.linkName="../outside"; tar.putArchiveEntry(entry); tar.closeArchiveEntry()
                }
                val output=File(root,"out"); output.deleteRecursively()
                assertThrows(IllegalArgumentException::class.java) { SecureArchiveReader.extract(model(root,bytes),archive,output) }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun missingOrCorruptConsumedFileCannotStage() {
        val root=Files.createTempDirectory("missing-test").toFile()
        try {
            val archive=File(root,"archive")
            TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
                val entry=TarArchiveEntry("fixture/ignored");entry.size=1;tar.putArchiveEntry(entry);tar.write(byteArrayOf(1));tar.closeArchiveEntry()
            }
            assertThrows(IllegalArgumentException::class.java) { SecureArchiveReader.extract(model(root,byteArrayOf(1)),archive,File(root,"out")) }
        } finally { root.deleteRecursively() }
    }
    @Test fun lockRejectsSecondRunAndDeletionCannotEscapeBuild() {
        val root=Files.createTempDirectory("workspace-test").toFile()
        try {
            RunWorkspace(root).use { owner ->
                repeat(1000) { assertThrows(Exception::class.java) { RunWorkspace(root) } }
                File(root,"outside").mkdir()
                assertThrows(IllegalArgumentException::class.java) { RunWorkspace.safeDelete(File(root,"outside"),File(root,"build")) }
                owner.record("emulator-1234")
                File(owner.directory,"input").writeText("temporary")
                owner.cleanupHost()
                assertFalse(owner.directory.exists())
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun htmlUsesFinalJsonAndEscapesUntrustedReasons() {
        val root=Files.createTempDirectory("report-test").toFile()
        try {
            val report=JSONObject().put("verdict","FAIL").put("candidates",JSONArray().put(JSONObject().put("id","<script>").put("verdict","FAIL").put("reasons",JSONArray().put("<img onerror=bad>"))))
            ReportWriter.write(root,report)
            assertEquals("FAIL",JSONObject(File(root,"report.json").readText()).getString("verdict"))
            val html=File(root,"report.html").readText()
            assertFalse(html.contains("<script>"));assertFalse(html.contains("<img onerror"));assertTrue(html.contains("&lt;script&gt;"))
            val embedded=html.substringAfter("<pre id=\"report-json\">").substringBefore("</pre>").replace("&quot;","\"").replace("&#39;","'").replace("&gt;",">").replace("&lt;","<").replace("&amp;","&")
            assertEquals(report.toString(),JSONObject(embedded).toString())
        } finally { root.deleteRecursively() }
    }
    @Test fun deviceSelectionNeverAcceptsPhysicalOfflineOrMultiple() {
        class FakeAdb(private val devices: String):AdbClient(File("unused")) {
            override fun command(vararg args: String,timeoutSeconds:Long,maxBytes:Int):CommandResult =
                CommandResult(0,(if(args[0]=="devices") devices else if(args.last().contains("ro.kernel.qemu")) "1" else if(args.last().contains("cpu.abi")) "x86_64" else "37").toByteArray())
        }
        for(text in listOf("List of devices attached\n", "List of devices attached\nreal-phone device\n", "List of devices attached\nemulator-1 offline\n", "List of devices attached\nemulator-1 device\nemulator-2 device\n")) {
            assertThrows(IllegalArgumentException::class.java) { FakeAdb(text).discover() }
        }
        assertEquals("emulator-1",FakeAdb("List of devices attached\nemulator-1 device\n").discover())
    }
}
