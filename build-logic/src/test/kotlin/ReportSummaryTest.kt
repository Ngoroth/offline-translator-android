import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReportSummaryTest {
    private fun render(report: JSONObject,check: (String)->Unit) {
        val root=Files.createTempDirectory("layered-report").toFile()
        try {
            val before=report.toString()
            ReportWriter.write(root,report)
            assertEquals(before,JSONObject(report.toString()).also {it.remove("samples")}.toString())
            val html=File(root,"report.html").readText()
            val embedded=html.substringAfter("<pre id=\"report-json\">").substringBefore("</pre>")
                .replace("&quot;","\"").replace("&#39;","'").replace("&gt;",">").replace("&lt;","<").replace("&amp;","&")
            assertTrue(JSONObject(File(root,"report.json").readText()).similar(JSONObject(embedded)))
            assertFalse(html.contains("<script"));assertFalse(html.contains("http://"));assertFalse(html.contains("https://"))
            check(html.substringBefore("<h2>Reproducible evidence"))
        } finally {root.deleteRecursively()}
    }
    private fun candidate(metrics: Any=JSONObject.NULL)=JSONObject().put("id","voice<&\"").put("slot","TTS/en")
        .put("verdict","FAIL").put("reasons",JSONArray().put("<img onerror=bad>" )).put("metrics",metrics)

    @Test fun infrastructurePassDoesNotHideStandaloneSemanticAndFullFailures() {
        val metrics=JSONObject().put("standaloneVerdict","FAIL").put("fullVerdict","FAIL")
            .put("ttsScore",JSONObject().put("wer",.1).put("cer",.05))
            .put("standalone",JSONArray().put(JSONObject().put("validOutput",true).put("signal",JSONObject().put("valid",true)).put("missingPhrases",JSONArray().put("aisle"))))
            .put("unmeasured",JSONArray()).put("stress",JSONObject().put("valid",false))
        val report=JSONObject().put("verdict","FAIL").put("stages",JSONArray().put(JSONObject().put("phase","candidate").put("verdict","PASS").put("testCount",1)))
            .put("candidates",JSONArray().put(candidate(metrics)))
        render(report) { html ->
            assertTrue(html.contains("<td>candidate</td><td>PASS</td>"))
            assertTrue(html.contains("Standalone: FAIL"))
            assertTrue(html.contains("TTS → fixed control ASR WER / CER: 0.1 / 0.05"))
            assertTrue(html.contains("1 / 1 recorded cases missing phrases"))
            assertTrue(html.contains("1 / 1 recorded cases valid"))
            assertTrue(html.contains("TTS signal validity: 1 / 1 recorded cases valid"))
            assertTrue(html.contains("Full pipeline: FAIL<br>Stress: FAIL"))
            assertTrue(html.contains("does not by itself prove a bad voice"))
            assertTrue(html.contains("voice&lt;&amp;&quot;"));assertTrue(html.contains("&lt;img onerror=bad&gt;"))
            assertFalse(html.contains("<img"))
        }
    }
    @Test fun failedPrerequisitesAndAbsentMeasurementsRemainUnmeasured() {
        val report=JSONObject().put("verdict","FAIL").put("reasons",JSONArray().put("prerequisiteFailure"))
            .put("buildTests",JSONObject().put("lint",JSONObject().put("status","FAIL")))
            .put("cleanup",JSONObject().put("status","unverified").put("reason","<device lost>"))
            .put("candidates",JSONArray().put(candidate()))
        render(report) { html ->
            assertTrue(html.contains("<td>Lint prerequisite</td><td>FAIL</td>"))
            assertTrue(html.contains("Overall reasons: [&quot;prerequisiteFailure&quot;]"))
            assertTrue(html.contains("<td>Instrumentation stages</td><td>unmeasured</td>"))
            assertTrue(html.contains("<td>preparation</td><td>unmeasured</td>"))
            assertTrue(html.contains("<td>Cleanup</td><td>unverified</td><td>&lt;device lost&gt;"))
            assertTrue(html.contains("Standalone: unmeasured"))
            assertTrue(html.contains("Full pipeline: unmeasured<br>Stress: unmeasured"))
            assertFalse(html.contains("<td>PASS</td>"))
        }
    }
    @Test fun partialRowsAndMissingStatusCannotBecomePass() {
        val metrics=JSONObject().put("standalone",JSONArray().put(JSONObject().put("notMeasured","interrupted")))
            .put("ttsScore",JSONObject().put("wer",.1).put("cer",.05))
            .put("unmeasured",JSONArray().put(JSONObject().put("id","missing"))).put("stress",JSONObject().put("valid",JSONObject.NULL))
        val stages=JSONArray().put(JSONObject().put("phase","feature").put("verdict","FAIL").put("reason","<offline failure>"))
        render(JSONObject().put("verdict","FAIL").put("stages",stages).put("candidates",JSONArray().put(candidate(metrics)))) { html ->
            assertTrue(html.contains("Standalone: unmeasured"));assertTrue(html.contains("1 cases unmeasured"))
            assertTrue(html.contains("Missing standalone cases: 1"));assertTrue(html.contains("Stress: unmeasured"))
            assertTrue(html.contains("TTS signal validity: 0 / 1 recorded cases valid; 1 cases unmeasured"))
            assertTrue(html.contains("<td>feature</td><td>FAIL</td><td>&lt;offline failure&gt;"))
            assertTrue(html.contains("<td>regression</td><td>unmeasured</td>"))
        }
    }
    @Test fun successfulRecordedLayersStayDistinctFromOriginalCandidateFailure() {
        val metrics=JSONObject().put("standaloneVerdict","PASS").put("fullVerdict","PASS").put("stress",JSONObject().put("valid",true))
            .put("asrScore-en",JSONObject().put("wer",.1).put("cer",.05))
            .put("standalone",JSONArray().put(JSONObject().put("validOutput",true).put("missingPhrases",JSONArray())))
            .put("unmeasured",JSONArray()).put("fullUnmeasured",JSONArray())
        render(JSONObject().put("verdict","FAIL").put("candidates",JSONArray().put(candidate(metrics)))) { html ->
            assertTrue(html.contains("Standalone: PASS"));assertTrue(html.contains("Full pipeline: PASS<br>Stress: PASS"))
            assertTrue(html.contains("<td>FAIL</td>"))
            assertTrue(html.contains("EN ASR WER / CER: 0.1 / 0.05"))
        }
    }
    @Test fun summaryRejectsOversizedCollectionsBeforePublishing() {
        val root=Files.createTempDirectory("bounded-report").toFile()
        try {
            val report=JSONObject().put("candidates",JSONArray((0..32).map {candidate()}))
            assertThrows(IllegalArgumentException::class.java) {ReportWriter.write(root,report)}
            assertFalse(File(root,"report.json").exists());assertFalse(File(root,"report.html").exists())
            assertThrows(IllegalArgumentException::class.java) {
                ReportSummary.infrastructure(JSONObject().put("stages",JSONArray((0..64).map {JSONObject()})))
            }
        } finally {root.deleteRecursively()}
    }
}
