import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProcessDeadlineTest {
    @Test fun hungNativeCommandAndOversizedOutputAreKilledWithBoundedReaders() {
        val java=File(System.getProperty("java.home"),"bin/java"+if(System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        val classes=File(ProcessHangFixture::class.java.protectionDomain.codeSource.location.toURI())
        val argv=listOf(java.absolutePath,"-cp",classes.absolutePath,"ProcessHangFixture")
        val start=System.nanoTime()
        assertThrows(IllegalStateException::class.java) {BoundedProcess.run(argv,1,1024)}
        assertTrue((System.nanoTime()-start)/1_000_000<8000)
        assertThrows(IllegalArgumentException::class.java) {BoundedProcess.run(argv+"output",10,512)}
    }
}
