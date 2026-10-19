import com.example.offlinetranslator.testkit.*
import org.json.JSONObject
import java.io.File

object PreparedEvaluationInputs {
    fun key(corpus: File,cases: List<CorpusCase>): String = EvaluationInputCache.key(
        StressInputs.VERSION+"\n"+listOf("manifest.json","files.tsv","source-lock.tsv","smoke-lock.json","semantic-lock.json")
            .joinToString("\n") {"$it:${Integrity.sha256(File(corpus,it))}"}+"\n"+
            cases.sortedBy {it.path}.joinToString("\n") {"${it.path}:${it.json.getLong("bytes")}:${it.json.getString("sha256")}"})

    fun stage(cache: EvaluationInputCache,corpus: File,cases: List<CorpusCase>,input: File,deadline: Long): JSONObject {
        require(CorpusManifest.read(corpus).map {it.id to it.json.getString("sha256")}==cases.map {it.id to it.json.getString("sha256")}) {"Prepared corpus identity changed"}
        val started=System.nanoTime();val key=key(corpus,cases)
        var generationMs: Double?=null
        val result=cache.obtain("derived",key,null,EvaluationInputCache.DERIVED_BUDGET,deadline,
            validate={StressInputs.verify(corpus,it,cases)}) { data ->
            val before=System.nanoTime();StressInputs.write(corpus,data,cases);generationMs=EvaluationInputCache.elapsed(before)
            data.walkTopDown().filter(File::isFile).map {ModelFile(it.relativeTo(data).invariantSeparatorsPath,it.length(),Integrity.sha256(it))}.toList()
        }
        val copy=System.nanoTime();val destination=File(input,"prepared-inputs")
        require(!destination.exists() && result.directory.copyRecursively(destination))
        StressInputs.verify(corpus,destination,cases)
        EvaluationInputCache.checkDeadline(deadline)
        return JSONObject().put("key",key).put("cacheHit",result.hit).put("generationMs",generationMs ?: JSONObject.NULL)
            .put("verificationMs",result.verificationMs).put("copyAndRunVerificationMs",EvaluationInputCache.elapsed(copy))
            .put("verifiedBytes",result.bytes).put("copiedBytes",result.bytes).put("totalMs",EvaluationInputCache.elapsed(started))
    }
}
