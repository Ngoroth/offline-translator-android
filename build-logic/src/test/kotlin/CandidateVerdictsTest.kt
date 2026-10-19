import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CandidateVerdictsTest {
    private fun row(id: String,slot: String,verdict: String)=JSONObject().put("id",id).put("slot",slot).put("verdict",verdict)
        .put("reasons",JSONArray()).put("metrics",JSONObject().put("standaloneVerdict",verdict).put("coldStoreLoadMs",0.0))
    @Test fun losingCandidateFollowedByUsableCandidateDoesNotFailTheSlot() {
        val rows=JSONArray()
        CandidateVerdicts.slots.forEachIndexed { i,slot -> rows.put(row("base$i",slot,"PASS")) }
        rows.put(row("loser","TTS/ru","FAIL")).put(row("winner","TTS/ru","PASS"))
        CandidateVerdicts.applyDependencies(rows,(0..4).map { "base$it" }.toSet())
        assertTrue(CandidateVerdicts.missingWinners(rows).isEmpty())
        assertEquals("FAIL",rows.getJSONObject(5).getString("verdict"))
        assertEquals("PASS",rows.getJSONObject(6).getString("verdict"))
        CandidateVerdicts.deltas(rows,(0..4).map { "base$it" }.toSet())
        assertTrue(rows.getJSONObject(6).getJSONObject("baselineDeltas").getJSONObject("coldStoreLoadMs").isNull("value"))
    }
    @Test fun noWinnerAndFailedFixedCompanionRemainExplicitFailure() {
        val rows=JSONArray().put(row("broken","VAD/both","FAIL")).put(row("voice","TTS/ru","PASS"))
        CandidateVerdicts.applyDependencies(rows,setOf("broken"))
        assertEquals("FAIL",rows.getJSONObject(1).getString("verdict"))
        assertEquals(5,CandidateVerdicts.missingWinners(rows).size)
    }
}
