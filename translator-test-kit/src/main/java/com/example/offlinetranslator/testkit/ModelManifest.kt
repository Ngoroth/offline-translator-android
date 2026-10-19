package com.example.offlinetranslator.testkit

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.net.URI

object Integrity {
    fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun safePath(path: String): Boolean = path.length in 1..240 && '\\' !in path && ':' !in path && '\u0000' !in path &&
        path.split('/').let { it.size <= 12 && it.none { part ->
            part.isEmpty() || part == "." || part == ".." || part.last() in setOf('.', ' ') ||
                part.any {character -> character.code<32 || character in "<>\"|?*"} ||
                part.substringBefore('.').uppercase(java.util.Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
        } }
    fun isInside(root: File,target: File): Boolean = target.canonicalPath.startsWith(root.canonicalPath+File.separator)
    fun verify(root: File, path: String, bytes: Long, hash: String): File {
        require(safePath(path) && hash.matches(Regex("[a-f0-9]{64}")) && bytes >= 0)
        val target = File(root, path)
        require(isInside(root,target) && target.canonicalFile == target.absoluteFile)
        require(target.isFile && target.length() == bytes && sha256(target) == hash) { "Input hash/size mismatch: $path" }
        return target
    }
    fun json(file: File): JSONObject {
        require(file.isFile && file.length() in 1..1048576)
        val text=file.readText(Charsets.UTF_8)
        var depth=0;var quoted=false;var escaped=false
        for(character in text) {
            if(quoted) {
                if(escaped) escaped=false else if(character=='\\') escaped=true else if(character=='"') quoted=false
            } else when(character) {
                '"' -> quoted=true
                '{','[' -> {depth++;require(depth<=32) {"JSON nesting quota exceeded"}}
                '}',']' -> {depth--;require(depth>=0)}
            }
        }
        require(depth==0 && !quoted)
        return JSONObject(text)
    }
}

data class ModelFile(val path: String, val bytes: Long, val sha256: String)
enum class EvaluationModelRole { VAD, ASR, TTS }

data class EvaluationModel(val json: JSONObject, val manifestDirectory: File) {
    val id: String = json.getString("id")
    internal val component: String = json.getString("component")
    val role = when (component) {
        "VAD" -> EvaluationModelRole.VAD
        "RUSSIAN_ASR", "ENGLISH_ASR" -> EvaluationModelRole.ASR
        "TTS" -> EvaluationModelRole.TTS
        else -> throw IllegalArgumentException("Unsupported historical component")
    }
    val language: String = json.getString("language")
    val adapter: String = json.getString("adapter")
    val files: List<ModelFile> = json.getJSONArray("files").let { rows -> (0 until rows.length()).map { i ->
        rows.getJSONObject(i).let { ModelFile(it.getString("path"), it.getLong("bytes"), it.getString("sha256")) }
    } }
    val slot: String get() = "$component/$language"
}

data class EvaluationSpeechRef(val packageId: String, val path: String) {
    fun json(): JSONObject = JSONObject().put("packageId", packageId).put("path", path)
}

/** Host-only typed projection: build-logic compiles this source without Android or core. */
sealed interface EvaluationSpeechAdapter {
    fun json(): JSONObject

    data class Silero(val model: EvaluationSpeechRef, val numThreads: Int, val minSpeechDuration: Double,
        val maxSpeechDuration: Double, val windowSize: Int) : EvaluationSpeechAdapter {
        override fun json(): JSONObject = JSONObject().put("family", "silero").put("model", model.json())
            .put("numThreads", numThreads).put("threshold", .5).put("minSpeechDuration", minSpeechDuration)
            .put("maxSpeechDuration", maxSpeechDuration).put("windowSize", windowSize)
    }
    data class ToneCtc(val model: EvaluationSpeechRef, val tokens: EvaluationSpeechRef,
        val numThreads: Int, val featureDim: Int) : EvaluationSpeechAdapter {
        override fun json(): JSONObject = JSONObject().put("family", "toneCtcOnline")
            .put("model", model.json()).put("tokens", tokens.json()).put("numThreads", numThreads).put("featureDim", featureDim)
    }
    data class Zipformer2(val encoder: EvaluationSpeechRef, val decoder: EvaluationSpeechRef,
        val joiner: EvaluationSpeechRef, val tokens: EvaluationSpeechRef,
        val numThreads: Int, val featureDim: Int) : EvaluationSpeechAdapter {
        override fun json(): JSONObject = JSONObject().put("family", "zipformer2Online").put("encoder", encoder.json())
            .put("decoder", decoder.json()).put("joiner", joiner.json()).put("tokens", tokens.json())
            .put("numThreads", numThreads).put("featureDim", featureDim)
    }
}

data class EvaluationSpeechDefinition(val id: String, val displayName: String, val role: EvaluationModelRole,
    val languages: List<String>, val sampleRateHz: Int, val adapter: EvaluationSpeechAdapter) {
    fun json(): JSONObject = JSONObject().put("id", id).put("displayName", displayName).put("role", role.name)
        .put("languages", JSONArray(languages)).put("sampleRateHz", sampleRateHz).put("adapter", adapter.json())
}

object ModelManifest {
    const val RUNTIME = "sherpa-onnx-1.13.8"
    private val allowedAdapters = setOf("silero", "toneCtc", "zipformer2", "piperVits")
    fun read(file: File): List<EvaluationModel> {
        val document = Integrity.json(file)
        require(document.getInt("schemaVersion") == 1 && document.getString("runtime") == RUNTIME)
        val rows = document.getJSONArray("candidates")
        require(rows.length() in 1..32)
        val models = (0 until rows.length()).map { EvaluationModel(rows.getJSONObject(it), requireNotNull(file.canonicalFile.parentFile)) }
        require(models.map { it.id.lowercase(java.util.Locale.ROOT) }.toSet().size == models.size)
        require(models.sumOf { it.json.getLong("archiveBytes") + it.files.sumOf(ModelFile::bytes) } <= 4L * 1024 * 1024 * 1024)
        models.forEach { validate(it) }
        return models
    }
    fun merge(baseline: List<EvaluationModel>, custom: List<EvaluationModel>): List<EvaluationModel> {
        val result = baseline.toMutableList()
        custom.forEach { candidate ->
            val existing = result.find { it.id.equals(candidate.id,ignoreCase=true) }
            if (existing != null) require(canonical(existing.json) == canonical(candidate.json)) { "Baseline override is forbidden" }
            else result += candidate
        }
        require(result.size <= 32 && result.sumOf { it.json.getLong("archiveBytes") + it.files.sumOf(ModelFile::bytes) } <= 4L * 1024 * 1024 * 1024)
        require(JSONObject().put("schemaVersion",1).put("runtime",RUNTIME).put("candidates",JSONArray(result.map {it.json})).toString().toByteArray(Charsets.UTF_8).size<=1048576) {"Effective manifest exceeds byte quota"}
        return result
    }
    fun speechDefinition(model: EvaluationModel): EvaluationSpeechDefinition {
        validate(model, verifyLocalSource = false)
        val parameters = model.json.getJSONObject("parameters")
        fun ref(path: String) = EvaluationSpeechRef(model.id, path).also {
            require(model.files.any { file -> file.path == path }) { "Undeclared adapter reference: $path" }
        }
        val adapter = when (model.adapter) {
            "silero" -> EvaluationSpeechAdapter.Silero(ref("silero_vad.onnx"), parameters.getInt("numThreads"),
                parameters.getDouble("minSpeechDuration"), parameters.getDouble("maxSpeechDuration"), parameters.getInt("windowSize"))
            "toneCtc" -> EvaluationSpeechAdapter.ToneCtc(ref("model.onnx"), ref("tokens.txt"),
                parameters.getInt("numThreads"), parameters.getInt("featureDim"))
            "zipformer2" -> EvaluationSpeechAdapter.Zipformer2(
                ref("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"),
                ref("decoder-epoch-99-avg-1-chunk-16-left-128.onnx"),
                ref("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"), ref("tokens.txt"),
                parameters.getInt("numThreads"), parameters.getInt("featureDim"))
            else -> throw IllegalArgumentException("Not a historical recognition model: ${model.id}")
        }
        return EvaluationSpeechDefinition(model.id, model.json.getString("displayName"),
            model.role,
            if (model.adapter == "silero") emptyList() else listOf(model.language), model.json.getInt("sampleRateHz"), adapter)
    }

    fun speechPackage(model: EvaluationModel): JSONObject {
        validate(model, verifyLocalSource = false)
        val fields = listOf("id", "displayName", "source", "license", "format",
            "archiveBytes", "archiveSha256", "maxUnpackedBytes", "maxEntries", "files")
        return JSONObject().also { result ->
            fields.forEach { field -> result.put(field, model.json.get(field)) }
            result.put("url", model.json.optString("url", "https://evaluation.invalid/${model.id}"))
            result.put("allowedRedirectHosts", if (model.json.has("url")) model.json.getJSONArray("allowedRedirectHosts")
                else JSONArray().put("evaluation.invalid"))
        }
    }

    fun fixedDirectionSpeechCatalog(document: JSONObject): JSONObject {
        require(document.getInt("schemaVersion") == 1 && document.getString("runtime") == RUNTIME)
        val slots = setOf("VAD/both", "RUSSIAN_ASR/ru", "ENGLISH_ASR/en")
        val rows = document.getJSONArray("candidates")
        val candidates = (0 until rows.length()).map { EvaluationModel(rows.getJSONObject(it), File(".").canonicalFile) }
        val selected = candidates.filter { it.component in setOf("VAD", "RUSSIAN_ASR", "ENGLISH_ASR") }
        require(selected.size == 3 && selected.map { it.slot }.toSet() == slots) {
            "Evaluation catalog must contain exactly the original VAD/Russian ASR/English ASR slots"
        }
        require(selected.map { it.id }.toSet().size == 3)
        return JSONObject().put("schemaVersion", 2).put("runtime", RUNTIME)
            .put("packages", JSONArray(selected.map(::speechPackage)))
            .put("models", JSONArray(selected.map { speechDefinition(it).json() }))
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix="{", postfix="}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix="[", postfix="]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    private fun validate(model: EvaluationModel, verifyLocalSource: Boolean = true) {
        val j = model.json
        require(model.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) && Integrity.safePath(model.id) && model.adapter in allowedAdapters)
        require(j.has("url") != j.has("localSource")) {"Declare exactly one model source"}
        require((model.component == "VAD" && model.language == "both" && model.adapter == "silero") ||
            (model.component == "RUSSIAN_ASR" && model.language == "ru" && model.adapter == "toneCtc") ||
            (model.component == "ENGLISH_ASR" && model.language == "en" && model.adapter == "zipformer2") ||
            (model.component == "TTS" && model.language in setOf("ru", "en") && model.adapter == "piperVits"))
        require(j.getLong("archiveBytes") in 1..536870912 && j.getLong("maxUnpackedBytes") in 1..1073741824)
        require(j.getString("archiveSha256").matches(Regex("[a-f0-9]{64}")) && j.getInt("maxEntries") in 1..1024)
        require(j.getString("format") in setOf("raw", "tar.bz2"))
        require(j.getString("rootPrefix").isEmpty() || Integrity.safePath(j.getString("rootPrefix")))
        require(model.files.size in 1..1024 && model.files.map { it.path.lowercase(java.util.Locale.ROOT) }.toSet().size == model.files.size)
        require(model.files.all { Integrity.safePath(it.path) && it.bytes in 1..1073741824 && it.sha256.matches(Regex("[a-f0-9]{64}")) })
        require(model.files.sumOf(ModelFile::bytes) <= j.getLong("maxUnpackedBytes"))
        require(j.getInt("sampleRateHz") in setOf(8000, 16000, 22050))
        val parameters = j.getJSONObject("parameters")
        parameters.keys().forEach { key -> val value = parameters.get(key); require(value !is Number || value.toDouble().isFinite()) }
        val expected = when(model.adapter) {
            "silero" -> JSONObject().put("numThreads",1).put("provider","cpu").put("minSilenceDuration",.7)
                .put("minSpeechDuration",.25).put("maxSpeechDuration",14.0).put("windowSize",512)
            "piperVits" -> JSONObject().put("numThreads",2).put("provider","cpu").put("speaker",0)
                .put("noiseScale",.667).put("noiseScaleW",.8).put("lengthScale",1.0).put("speed",1.0)
            else -> JSONObject().put("numThreads",2).put("provider","cpu").put("featureDim",80).put("enableEndpoint",false)
        }
        require(parameters.keys().asSequence().toSet()==expected.keys().asSequence().toSet()) { "Unsupported runtime parameters for ${model.adapter}" }
        expected.keys().forEach {key ->
            val value=expected.get(key)
            require(if(value is Number) parameters.getDouble(key)==value.toDouble() else parameters.get(key)==value) { "Unsupported runtime parameter $key" }
        }
        require(j.getInt("sampleRateHz")==when(model.component) {"RUSSIAN_ASR" -> 8000;"TTS" -> 22050;else -> 16000})
        if(model.component!="TTS") require(model.id.matches(Regex("[a-z0-9][a-z0-9.-]{0,79}"))) { "Production store ID unsupported" }
        val requiredFiles=when(model.component) {
            "VAD" -> setOf("silero_vad.onnx")
            "RUSSIAN_ASR" -> setOf("model.onnx","tokens.txt")
            "ENGLISH_ASR" -> setOf("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx","decoder-epoch-99-avg-1-chunk-16-left-128.onnx","joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx","tokens.txt")
            else -> setOf("tokens.txt")
        }
        require(model.files.map {it.path}.containsAll(requiredFiles)) { "Adapter file layout unsupported" }
        if(model.component=="TTS") require(model.files.count {it.path.endsWith(".onnx")}==1 && model.files.any {it.path.startsWith("espeak-ng-data/")})
        if (j.has("url")) {
            val url = URI(j.getString("url"))
            require(url.scheme == "https" && url.userInfo == null && url.port in setOf(-1, 443))
            val hosts = j.getJSONArray("allowedRedirectHosts").let { (0 until it.length()).map(it::getString) }
            require(url.host in hosts && hosts.isNotEmpty() && hosts.size <= 8 && hosts.all { it.matches(Regex("[a-z0-9.-]+")) })
        } else {
            val path = j.getString("localSource")
            require(Integrity.safePath(path))
            val source = File(model.manifestDirectory, path)
            if (verifyLocalSource) require(source.canonicalFile == source.absoluteFile && Integrity.isInside(model.manifestDirectory,source) && source.isFile)
        }
    }
}
