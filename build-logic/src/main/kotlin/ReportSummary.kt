import org.json.JSONArray
import org.json.JSONObject

/** Presentation of recorded evidence only; never changes a verdict or rescoring rule. */
object ReportSummary {
    fun setup(report: JSONObject): String {
        val candidates=report.optJSONArray("candidates") ?: JSONArray()
        require(candidates.length()<=32)
        fun row(name: String,setup: JSONObject?): String {
            val hit=setup?.opt("cacheHit")?.takeIf {it is Boolean} ?: "unmeasured"
            val fields=listOf("verifiedBytes","copiedBytes","downloadMs","extractionAndFileVerificationMs","generationMs","verificationMs","archiveVerificationMs","bundleVerificationMs","copyAndRunVerificationMs","totalMs")
                .filter {setup?.has(it)==true}.joinToString("; ") {"${text(it)}=${text(setup?.opt(it))}"}
            return "<tr><td>${text(name)}</td><td>${text(hit)}</td><td>$fields</td></tr>"
        }
        val rows=row("Prepared audio",report.optJSONObject("preparedInputs"))+(0 until candidates.length()).joinToString("") {
            val candidate=candidates.getJSONObject(it);row(candidate.optString("id"),candidate.optJSONObject("staging"))
        }
        return "<p>Cache hits reuse inputs only. All inference remains live. Skipped setup times are unmeasured, not zero. Intentional retained cache: ${text(report.optJSONObject("inputCache"))}</p>"+
            "<table><tr><th>Input</th><th>Cache hit</th><th>Fresh setup evidence</th></tr>$rows</table>"
    }
    private fun text(value: Any?): String = ReportWriter.escape(value?.takeUnless { it==JSONObject.NULL }?.toString() ?: "unmeasured")
    fun verdict(row: JSONObject?,key: String="verdict"): String = when(val value=row?.optString(key)) {
        "PASS","FAIL" -> value
        else -> "unmeasured"
    }
    fun infrastructure(report: JSONObject): String {
        val rows=mutableListOf<String>()
        fun row(name: String,status: String,detail: Any?=null) {
            rows.add("<tr><td>${text(name)}</td><td>${text(status)}</td><td>${text(detail)}</td></tr>")
        }
        val build=report.optJSONObject("buildTests")
        val modules=build?.optJSONArray("jvm") ?: JSONArray()
        require(modules.length()<=16)
        if(modules.isEmpty) row("JVM prerequisites","unmeasured")
        for(i in 0 until modules.length()) {
            val module=modules.getJSONObject(i)
            row("JVM: ${module.optString("module")}",verdict(module,"status"))
        }
        row("Lint prerequisite",verdict(build?.optJSONObject("lint"),"status"))
        row("Runner assemblies",verdict(build,"runnerAssemblies"))
        val stages=report.optJSONArray("stages") ?: JSONArray()
        require(stages.length()<=64)
        if(stages.isEmpty) row("Instrumentation stages","unmeasured")
        for(i in 0 until stages.length()) {
            val stage=stages.getJSONObject(i)
            row("${stage.optString("phase")} ${stage.optString("candidate")}".trim(),verdict(stage),
                stage.opt("reason") ?: stage.opt("testCount"))
        }
        val recorded=(0 until stages.length()).map(stages::getJSONObject)
        for(phase in listOf("regression","preparation","feature","smoke","cleanup")) {
            if(recorded.none {it.optString("phase")==phase}) row(phase,"unmeasured")
        }
        val candidates=report.optJSONArray("candidates") ?: JSONArray()
        require(candidates.length()<=32)
        for(i in 0 until candidates.length()) {
            val id=candidates.getJSONObject(i).optString("id")
            if(recorded.none {it.optString("phase")=="candidate" && it.optString("candidate")==id}) {
                row("candidate $id","unmeasured")
            }
        }
        val cleanup=report.optJSONObject("cleanup")
        row("Cleanup",cleanup?.optString("status")?.takeIf {it.isNotBlank()} ?: "unmeasured",cleanup?.opt("reason"))
        return "<table><thead><tr><th>Infrastructure check</th><th>Recorded status</th><th>Tests or reason</th></tr></thead><tbody>${rows.joinToString("")}</tbody></table>"
    }
    fun quality(metrics: JSONObject?): String {
        val scores=listOf("asrScore-ru" to "RU ASR", "asrScore-en" to "EN ASR", "ttsScore" to "TTS → fixed control ASR")
            .mapNotNull { (key,label) -> metrics?.optJSONObject(key)?.let {
                "${text(label)} WER / CER: ${text(it.opt("wer"))} / ${text(it.opt("cer"))}"
            } }.joinToString("<br>").ifEmpty {"WER / CER: unmeasured"}
        val rows=metrics?.optJSONArray("standalone") ?: JSONArray()
        require(rows.length()<=58)
        fun count(predicate: (JSONObject)->Boolean)=(0 until rows.length()).count {predicate(rows.getJSONObject(it))}
        val semantics=if(rows.isEmpty) "unmeasured" else {
            val missing=count { (it.optJSONArray("missingPhrases")?.length() ?: 0)>0 }
            val unknown=count {it.optJSONArray("missingPhrases")==null || it.has("notMeasured")}
            "$missing / ${rows.length()} recorded cases missing phrases; $unknown cases unmeasured"
        }
        val outputs=if(rows.isEmpty) "unmeasured" else {
            val valid=count {it.opt("validOutput")==true && !it.has("notMeasured")}
            val unknown=count {it.opt("validOutput") !is Boolean || it.has("notMeasured")}
            "$valid / ${rows.length()} recorded cases valid; $unknown cases unmeasured"
        }
        val missing=metrics?.optJSONArray("unmeasured")?.length()?.toString() ?: "unmeasured"
        val signal=if(metrics?.optJSONObject("ttsScore")!=null && rows.isEmpty) "<br>TTS signal validity: unmeasured"
        else if(metrics?.optJSONObject("ttsScore")!=null) {
            val valid=count {it.optJSONObject("signal")?.opt("valid")==true && !it.has("notMeasured")}
            val unknown=count {it.optJSONObject("signal")?.opt("valid") !is Boolean || it.has("notMeasured")}
            "<br>TTS signal validity: $valid / ${rows.length()} recorded cases valid; $unknown cases unmeasured"
        } else ""
        return "Standalone: ${verdict(metrics,"standaloneVerdict")}<br>$scores<br>Semantic checks: $semantics<br>Output validity: $outputs$signal<br>Missing standalone cases: $missing"
    }
    fun full(metrics: JSONObject?): String {
        val stress=metrics?.optJSONObject("stress")
        val status=when(stress?.opt("valid")) {true -> "PASS";false -> "FAIL";else -> "unmeasured"}
        val missing=metrics?.optJSONArray("fullUnmeasured")?.length()?.toString() ?: "unmeasured"
        return "Full pipeline: ${verdict(metrics,"fullVerdict")}<br>Stress: $status<br>Missing full cases: $missing"
    }
}
