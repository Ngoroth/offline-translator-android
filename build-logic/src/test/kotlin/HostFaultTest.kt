import com.example.offlinetranslator.testkit.*
import org.apache.commons.compress.archivers.tar.*
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest

class HostFaultTest {
    @Test fun stuckDnsCannotAccumulateWorkersOrQueuedLookups() {
        val release=java.util.concurrent.CountDownLatch(1)
        DnsResolver {
            while(release.count>0) try {release.await()} catch(ignored: InterruptedException) { }
            arrayOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8)))
        }.use {resolver ->
            try {
                assertThrows(IllegalStateException::class.java) {resolver.resolve("fixture",25)}
                val start=System.nanoTime()
                assertThrows(IllegalStateException::class.java) {resolver.resolve("fixture",25)}
                assertTrue((System.nanoTime()-start)/1_000_000<1000)
            } finally {release.countDown()}
        }
    }
    @Test fun lowDiskIsRejectedAtExactHostAndDeviceBoundaries() {
        SpaceRequirements.host(SpaceRequirements.HOST_BYTES)
        SpaceRequirements.device(SpaceRequirements.DEVICE_BYTES/1024)
        assertThrows(IllegalArgumentException::class.java) {SpaceRequirements.host(SpaceRequirements.HOST_BYTES-1)}
        assertThrows(IllegalArgumentException::class.java) {SpaceRequirements.device(SpaceRequirements.DEVICE_BYTES/1024-1)}
    }
    @Test fun oversizedStructuralMetadataIsRejectedBeforeAllocation() {
        val bytes=ByteArray(512)
        "pax-header".toByteArray().copyInto(bytes)
        "00000200000\u0000".toByteArray().copyInto(bytes,124)
        bytes[156]='x'.code.toByte()
        assertThrows(IllegalArgumentException::class.java) {TarQuotaInputStream(ByteArrayInputStream(bytes),16,1024*1024).read(ByteArray(512))}
    }
    @Test fun httpsPinsValidatedIpAndRejectsPrivateRedirectsCredentialsAndDeadline() {
        val hosts=setOf("github.com");val public=arrayOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8)))
        assertEquals(public.first(),PinnedHttps.validate(URI("https://github.com/file"),hosts,public))
        for(uri in listOf("http://github.com/file","https://u:p@github.com/file","https://evil.test/file"))
            assertThrows(IllegalArgumentException::class.java) { PinnedHttps.validate(URI(uri),hosts,public) }
        for(ip in listOf("127.0.0.1","10.0.0.1","169.254.1.1","100.64.0.1","198.18.0.1","192.0.0.1","192.0.2.1","203.0.113.1","::1","fc00::1"))
            assertThrows(IllegalArgumentException::class.java) { PinnedHttps.validate(URI("https://github.com/file"),hosts,arrayOf(InetAddress.getByName(ip))) }
        val file=File.createTempFile("download", ".part")
        try {
            assertThrows(IllegalArgumentException::class.java) { PinnedHttps.copy(ByteArrayInputStream(byteArrayOf(1)),file,2,Long.MAX_VALUE) }
            assertThrows(IllegalArgumentException::class.java) { PinnedHttps.copy(ByteArrayInputStream(byteArrayOf(1,2)),file,1,Long.MAX_VALUE) }
            assertThrows(IllegalArgumentException::class.java) { PinnedHttps.copy(ByteArrayInputStream(byteArrayOf(1)),file,1,0) }
        } finally { file.delete() }
    }

    @Test fun ignoredDuplicateAndDecompressionQuotaFailBeforeModelPublication() {
        val root=Files.createTempDirectory("fault-archive").toFile()
        try {
            val data=byteArrayOf(1);val hash=MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            fun model(cap: Long)=EvaluationModel(JSONObject().put("id","fixture").put("component","VAD").put("language","both").put("adapter","silero")
                .put("format","tar.bz2").put("rootPrefix","fixture").put("maxEntries",16).put("maxUnpackedBytes",cap)
                .put("files",JSONArray().put(JSONObject().put("path","model.onnx").put("bytes",1).put("sha256",hash))),root)
            for(names in listOf(listOf("fixture/A","fixture/a"),listOf("fixture/big"))) {
                val archive=File(root,"archive")
                TarArchiveOutputStream(BZip2CompressorOutputStream(archive.outputStream())).use { tar ->
                    names.forEach { name -> val entry=TarArchiveEntry(name);entry.size=2;tar.putArchiveEntry(entry);tar.write(byteArrayOf(1,2));tar.closeArchiveEntry() }
                }
                val output=File(root,"out");output.deleteRecursively()
                assertThrows(IllegalArgumentException::class.java) { SecureArchiveReader.extract(model(if(names.size==1) 1 else 1024),archive,output) }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun abruptRunRecoveryKeepsJournalAndNeverDeletesForeignFiles() {
        val root=Files.createTempDirectory("orphan-recovery").toFile()
        try {
            val unrelated=File(root,"foreign").apply { mkdir() };File(unrelated,"keep").writeText("owned elsewhere")
            var orphan=""
            RunWorkspace(root).use { owner -> owner.record("emulator-1234");orphan=owner.id;File(owner.directory,"partial").writeText("partial input") }
            RunWorkspace(root).use { next ->
                assertEquals(orphan,JSONObject(next.journal.readText()).getString("runId"))
                next.cleanupHost();assertEquals("owned elsewhere",File(unrelated,"keep").readText())
                assertTrue(next.journal.exists())
            }
        } finally { root.deleteRecursively() }
    }
}
