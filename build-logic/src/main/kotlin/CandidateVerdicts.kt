import org.json.JSONArray
import org.json.JSONObject

/** Comparable slots; candidate loss does not hide a usable winner or missing required evidence. */
object CandidateVerdicts {
    val slots=listOf("VAD/both","RUSSIAN_ASR/ru","ENGLISH_ASR/en","TTS/ru","TTS/en")
    fun missingWinners(rows: JSONArray): List<String> = slots.filter { slot ->
        (0 until rows.length()).none { rows.getJSONObject(it).optString("slot")==slot && rows.getJSONObject(it).optString("verdict")=="PASS" }
    }
    fun applyDependencies(rows: JSONArray,baselineIds: Set<String>) {
        val baselines=(0 until rows.length()).map(rows::getJSONObject).filter { it.getString("id") in baselineIds }
        for(index in 0 until rows.length()) {
            val row=rows.getJSONObject(index)
            val ownSlot=row.getString("slot")
            val failures=baselines.filter { it.getString("slot") != ownSlot &&
                (it.optJSONObject("metrics")?.optString("standaloneVerdict") != "PASS") }
            row.put("companionDependencies",JSONArray(failures.map { it.getString("id") }))
            if(failures.isNotEmpty()) {
                row.put("verdict","FAIL");row.getJSONArray("reasons").put("fixedCompanionStandaloneFailure")
                row.optJSONObject("metrics")?.put("fullVerdict","FAIL")
            }
        }
    }
    fun deltas(rows: JSONArray,baselineIds: Set<String>) {
        val all=(0 until rows.length()).map(rows::getJSONObject)
        all.forEach { row ->
            val baseline=all.firstOrNull { it.getString("id") in baselineIds && it.getString("slot")==row.getString("slot") }
            val deltas=JSONObject()
            for(key in listOf("asrRtf-ru","asrRtf-en","coldStoreLoadMs")) {
                val value=row.optJSONObject("metrics")?.optDouble(key,Double.NaN) ?: Double.NaN
                val previous=baseline?.optJSONObject("metrics")?.optDouble(key,Double.NaN) ?: Double.NaN
                deltas.put(key,if(value.isFinite() && previous.isFinite() && previous != 0.0)
                    JSONObject().put("absolute",value-previous).put("relative",(value-previous)/previous)
                    else JSONObject().put("value",JSONObject.NULL).put("notMeasured","missing or zero baseline denominator"))
            }
            row.put("baselineDeltas",deltas)
        }
    }
}
