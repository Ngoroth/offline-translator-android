package com.example.offlinetranslator.modelsmoke

import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.testkit.*
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.mlkit.MlKitPreparationCoordinator
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.sherpa.SherpaSpeechRecognitionEngine
import com.example.offlinetranslator.sherpa.SherpaSpeechSegmenter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object EvaluationSession {
    val russianToEnglish = LanguagePair(LanguageId("ru"), LanguageId("en"))
    val englishToRussian = russianToEnglish.reverse()
    val pairs = setOf(russianToEnglish, englishToRussian)
    private val preparationCoordinator by lazy { MlKitPreparationCoordinator(context) }
    fun translationEngine() = MlKitTranslationEngine(context, pairs, preparationCoordinator)
    fun pair(case: CorpusCase) = LanguagePair(LanguageId(case.pair.sourceId), LanguageId(case.pair.targetId))
    fun asrModelIds(store: SpeechModelStore): Map<LanguageId, String> = pairs.associate { pair ->
        val eligible = store.catalog.models.filter { it.role == SpeechModelRole.ASR && pair.source in it.languages }
        val model = if (active) eligible.single() else eligible.single { it.id == when (pair.source.value) {
            "ru" -> "asr-ru-gigaam-v1"
            "en" -> "asr-en-whisper-tiny.en"
            else -> error("No controlled fixture model")
        } }
        pair.source to model.id
    }
    fun vadModelId(store: SpeechModelStore) = store.catalog.models.single { it.role == SpeechModelRole.VAD }.id
    fun speechResources(store: SpeechModelStore) =
        SelectedSpeechResources.forRecognition(store, pairs, asrModelIds(store), vadModelId(store))
    fun recognizer(store: SpeechModelStore) = SherpaSpeechRecognitionEngine(store, pairs, asrModelIds(store))
    suspend fun segmenter(store: SpeechModelStore, speechEndTime: (Long) -> Pair<Long, Boolean>? = { null }) =
        SherpaSpeechSegmenter.create(store, vadModelId(store), 700, speechEndTime)
    val args get() = InstrumentationRegistry.getArguments()
    val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    val active get() = args.getString("runId") != null
    val root: File get() {
        val id = requireNotNull(args.getString("runId"))
        require(id.matches(Regex("[a-f0-9-]{36}")))
        // Android's trusted Context path may itself alias /data/user/0 via /data/data.
        val base=context.filesDir.canonicalFile
        return File(base,"evaluation/$id").also { require(it.canonicalFile == it.absoluteFile && it.isDirectory) }
    }
    val corpus get() = File(root,"corpus")
    val prepared get() = File(root,"prepared-inputs")
    val cases: List<CorpusCase> by lazy {CorpusManifest.read(corpus)}
    val stressBoundary: Int by lazy {StressInputs.verify(corpus,prepared,cases)}
    fun padded(case: CorpusCase) = StressInputs.padded(prepared,case)
    fun models(): List<EvaluationModel> {
        val doc=Integrity.json(File(root,"models.json"))
        require(doc.getInt("schemaVersion")==1 && doc.getString("runtime")==ModelManifest.RUNTIME)
        val rows=doc.getJSONArray("candidates")
        require(rows.length()<=32)
        return (0 until rows.length()).map { EvaluationModel(rows.getJSONObject(it),root) }
    }
    fun selected(): EvaluationModel = models().single { it.id == args.getString("candidate") }
    suspend fun faultCheckpoint(completed: Int) {
        when(args.getString("evaluationFault")) {
            "force-stop" -> if(completed>=3) android.os.Process.killProcess(android.os.Process.myPid())
            "native-hang" -> kotlinx.coroutines.delay(Long.MAX_VALUE)
        }
    }
    suspend fun store(useCandidate: Boolean = true): SpeechModelStore {
        if (!active) return SpeechModelStore(context, SherpaSpeechModelCatalog.fromAssets(context))
        val catalog=Integrity.json(File(root,"speech-models.json"))
        val candidate=if(useCandidate) args.getString("candidate")?.takeIf(String::isNotBlank)?.let { selected() } else null
        if(candidate!=null && candidate.role!=EvaluationModelRole.TTS) {
            val normalized = JSONObject(candidate.json.toString()).put("displayName", candidate.id)
                .put("source", candidate.json.optString("source", candidate.json.optString("url", "evaluation-local")))
                .put("license", candidate.json.optString("license", "evaluation-only"))
            val projected = EvaluationModel(normalized, candidate.manifestDirectory)
            val packages = catalog.getJSONArray("packages")
            val definitions = catalog.getJSONArray("models")
            val replacement = ModelManifest.speechDefinition(projected)
            val index = (0 until definitions.length()).single { index ->
                val definition = definitions.getJSONObject(index)
                definition.getString("role") == replacement.role.name &&
                    definition.getJSONArray("languages").toString() == JSONArray(replacement.languages).toString()
            }
            val oldId = definitions.getJSONObject(index).getString("id")
            val packageIndex = (0 until packages.length()).single { packages.getJSONObject(it).getString("id") == oldId }
            packages.put(packageIndex, ModelManifest.speechPackage(projected))
            definitions.put(index, replacement.json())
        }
        return SpeechModelStore.fromVerifiedEvaluationFiles(context,SherpaSpeechModelCatalog.parse(catalog.toString().byteInputStream()),File(root,"models"))
    }
    fun writeResult(result: JSONObject) {
        writeAtomic("result",result,16*1024*1024)
    }
    fun writeStage(stage: JSONObject) = writeAtomic("stage",stage,65536)
    fun writeInputReuseProof(evidence: JSONObject) = writeAtomic("input-reuse-proof",evidence,1048576)
    fun writeEvidence(phase: String,evidence: JSONObject) {
        require(phase in setOf("preparation","feature","smoke"))
        if(active) writeAtomic("evidence-$phase",evidence,262144)
    }
    private val smokeRows=JSONArray()
    fun recordSmoke(id: String,pair: LanguagePair,direct: String,production: String) {
        if(!active) return
        require(smokeRows.length()<10)
        val case=CorpusManifest.read(corpus,false).single {it.id==id && it.cohort=="smoke"}
        smokeRows.put(JSONObject().put("id",id).put("reference",case.reference).put("sha256",case.json.getString("sha256"))
            .put("source",pair.source.value).put("target",pair.target.value).put("direct",direct).put("production",production))
        writeEvidence("smoke",JSONObject().put("cases",smokeRows))
    }
    private fun writeAtomic(name: String,result: JSONObject,limit: Int) {
        require(name in setOf("result","stage","evidence-preparation","evidence-feature","evidence-smoke","input-reuse-proof"))
        val partial=File(root,"$name.partial.json")
        val text=result.toString()
        require(text.toByteArray().size<=limit)
        java.io.FileOutputStream(partial).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        val final=File(root,"$name.json")
        android.system.Os.rename(partial.absolutePath,final.absolutePath)
    }
}

object InputVerifier {
    fun verify() {
        CorpusManifest.read(EvaluationSession.corpus)
        EvaluationSession.stressBoundary
        EvaluationSession.models().forEach { model ->
            val root=File(EvaluationSession.root,"models/${model.id}")
            model.files.forEach { Integrity.verify(root,it.path,it.bytes,it.sha256) }
        }
    }
}
