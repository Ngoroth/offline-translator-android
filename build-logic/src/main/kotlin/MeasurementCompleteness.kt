import com.example.offlinetranslator.testkit.CorpusCase
import com.example.offlinetranslator.testkit.EvaluationModel
import com.example.offlinetranslator.testkit.EvaluationModelRole
import org.json.JSONArray
import org.json.JSONObject

object MeasurementCompleteness {
    fun annotate(result: JSONObject,model: EvaluationModel,cases: List<CorpusCase>) {
        val quality=cases.filter {it.cohort=="quality"}
        fun missing(key: String,expected: List<CorpusCase>): JSONArray {
            val rows=result.optJSONArray(key) ?: JSONArray()
            val measured=(0 until rows.length()).map {rows.getJSONObject(it).optString("id")}.toSet()
            return JSONArray(expected.filter {it.id !in measured}.map {
                JSONObject().put("id",it.id).put("metrics",JSONObject.NULL).put("notMeasured","scenario did not complete this case")
            })
        }
        result.put("unmeasured",missing("standalone",quality.filter {model.language=="both" || it.language==model.language}))
        val full=quality.filter {
            when(model.role) {EvaluationModelRole.VAD -> true;EvaluationModelRole.TTS -> it.language!=model.language;EvaluationModelRole.ASR -> it.language==model.language}
        }
        result.put("fullUnmeasured",missing("full",full))
        for(key in listOf("standaloneVerdict","fullVerdict","stress","releaseMemory")) if(!result.has(key)) result.put(key,JSONObject.NULL)
    }
}
