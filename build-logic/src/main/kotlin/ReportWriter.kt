import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ReportWriter {
    fun write(root: File, report: JSONObject) {
        root.mkdirs()
        val sampleRows=JSONArray()
        File(root,"samples").walkTopDown().filter { it.isFile && it.extension=="wav" }.forEach { file ->
            sampleRows.put(JSONObject().put("path",file.relativeTo(root).invariantSeparatorsPath).put("bytes",file.length())
                .put("sha256",com.example.offlinetranslator.testkit.Integrity.sha256(file)))
        }
        report.put("samples",sampleRows)
        val json=report.toString(2)
        require(json.toByteArray().size<=16*1024*1024)
        fun publish(name: String, body: String) {
            val partial=File(root,"$name.partial"); partial.writeText(body,Charsets.UTF_8)
            Files.move(partial.toPath(),File(root,name).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
        }
        val candidates=report.optJSONArray("candidates") ?: JSONArray()
        require(candidates.length()<=32)
        val infrastructure=ReportSummary.infrastructure(report)
        val setup=ReportSummary.setup(report)
        val rows=(0 until candidates.length()).joinToString("\n") { index ->
            val row=candidates.getJSONObject(index)
            val metrics=row.optJSONObject("metrics")
            fun number(value: JSONObject?,key: String): String = if(value==null || !value.has(key) || value.isNull(key)) "unmeasured" else escape(value.get(key).toString())
            val quality=ReportSummary.quality(metrics)
            val full=metrics?.optJSONArray("full")
            require(full==null || full.length()<=58)
            val latency=full?.let { values -> (0 until values.length()).flatMap { item ->
                val segments=values.getJSONObject(item).optJSONArray("segments") ?: JSONArray()
                (0 until segments.length()).map { segments.getJSONObject(it).optDouble("latencyMs",Double.NaN) }
            }.filter(Double::isFinite).maxOrNull() }
            "<tr><td>${escape(row.optString("id"))}</td><td>${escape(row.optString("slot"))}</td><td>${escape(row.optString("verdict"))}</td>" +
                "<td>$quality</td><td>${ReportSummary.full(metrics)}</td><td>${latency ?: "unmeasured"}</td>" +
                "<td>${number(row.optJSONObject("memory"),"peakPssKiB")} / ${number(row.optJSONObject("memory"),"peakNativeKiB")}</td>" +
                "<td>${escape(row.optJSONArray("reasons")?.toString() ?: "[]")}</td></tr>"
        }
        val audioRows=(0 until sampleRows.length()).joinToString("\n") { index ->
            val sample=sampleRows.getJSONObject(index);val path=escape(sample.getString("path"))
            "<p>${path}<audio controls preload=\"none\" src=\"$path\"></audio></p>"
        }
        val html="""<!doctype html><html lang="en"><meta charset="utf-8"><title>Translator evaluation</title>
<style>body{font:16px system-ui;margin:2rem;max-width:1500px}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:.5rem}pre{white-space:pre-wrap;overflow-wrap:anywhere}audio{display:block}</style>
<h1>Translator evaluation: ${escape(report.optString("verdict"))}</h1>
<p>Overall reasons: ${escape(report.optJSONArray("reasons")?.toString() ?: "unmeasured")}</p>
<p>Emulator measurements. Overall and candidate verdicts and reasons are unchanged. Missing evidence is unmeasured.</p>
<h2>Infrastructure</h2><p>A stage PASS records harness completion, independently of model quality. Prerequisite and cleanup status are shown separately; absent stages are unmeasured.</p>$infrastructure
<h2>Input preparation</h2>$setup
<h2>Candidate quality and full pipeline</h2>
<p>Standalone results, full pipeline and stress are separate recorded outcomes. WER/CER are summed edits per language, with ASR limits .20/.10 and TTS reverse limits .30/.15. Numeric scores alone do not establish PASS: semantic, output and remaining gates still apply.</p>
<p>TTS is evaluated as TTS → fixed control ASR. A reverse-recognition failure does not by itself prove a bad voice; synthesis and control-recognizer effects are not isolated. Output validity and missing semantic phrases are distinct checks.</p>
<table><thead><tr><th>Candidate</th><th>Slot</th><th>Original verdict</th><th>Standalone quality subchecks</th><th>Full pipeline / stress</th><th>Maximum full latency (ms)</th><th>Peak PSS / native heap (KiB)</th><th>Original reasons</th></tr></thead><tbody>$rows</tbody></table>
<h2>Retained samples</h2>$audioRows<h2>Reproducible evidence</h2><pre id="report-json">${escape(json)}</pre></html>"""
        require(html.toByteArray(Charsets.UTF_8).size<=128*1024*1024)
        publish("report.json",json+"\n")
        publish("report.html",html)
    }
    fun escape(value: String) = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")
}
