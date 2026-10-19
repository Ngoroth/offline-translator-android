import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RootBuildCleanupTest {
    @Test fun cleanDeletesReportsAndGeneratedInputsWhileKeepingTheLockAndSource() {
        val repo=Files.createTempDirectory("root-clean").toFile()
        try {
            File(repo,"source.txt").writeText("preserved source")
            RunWorkspace(repo).use {owner ->
                val report=File(repo,"build/reports/model-evaluation/report.json")
                report.parentFile.mkdirs();report.writeText("generated report")
                RootBuildCleanup.clear(owner)
                assertFalse(report.exists());assertTrue(File(repo,"build/translator-verification.lock").isFile)
                assertThrows(Exception::class.java) {RunWorkspace(repo)}
                assertEquals("preserved source",File(repo,"source.txt").readText())
            }
            RunWorkspace(repo).use {assertTrue(it.base.isDirectory)}
        } finally {repo.deleteRecursively()}
    }
}
