import com.example.offlinetranslator.testkit.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MeasurementCompletenessTest {
    @Test fun interruptedNativeRunPreservesRowsAndNamesEveryUnmeasuredCase() {
        val repo=File(System.getProperty("evaluation.repo"))
        val cases=CorpusManifest.read(File(repo,"test-data/corpus/v1"))
        val model=ModelManifest.read(File(repo,"test-data/model-sets/baseline.json")).first()
        val first=cases.first {it.cohort=="quality"}
        val row=JSONObject().put("id",first.id).put("production","completed output")
        val partial=JSONObject().put("standalone",JSONArray().put(row)).put("full",JSONArray())
        MeasurementCompleteness.annotate(partial,model,cases)
        assertEquals(47,partial.getJSONArray("unmeasured").length())
        assertEquals(48,partial.getJSONArray("fullUnmeasured").length())
        assertEquals("completed output",partial.getJSONArray("standalone").getJSONObject(0).getString("production"))
        assertTrue(partial.isNull("stress"));assertTrue(partial.isNull("releaseMemory"))
    }
}
