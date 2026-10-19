import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuildEvidenceTest {
    @Test fun prerequisiteEvidenceRejectsFailuresAndXmlEntities() {
        val file=File.createTempFile("suite-evidence", ".xml")
        try {
            file.writeText("<testsuite tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\"/>")
            assertEquals(3,BuildEvidence.suite(file).getInt("tests"))
            file.writeText("<testsuite tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\"/>")
            assertThrows(IllegalArgumentException::class.java) {BuildEvidence.suite(file)}
            file.writeText("<!DOCTYPE testsuite [<!ENTITY x SYSTEM 'file:///must-not-open'>]><testsuite tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">&x;</testsuite>")
            assertThrows(IllegalArgumentException::class.java) {BuildEvidence.suite(file)}
        } finally {file.delete()}
    }
}
