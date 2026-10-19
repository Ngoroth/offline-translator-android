package com.example.offlinetranslator.testkit

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Host-safe pair; historical direction spellings are decoded only at this input boundary. */
data class EvaluationLanguagePair(val sourceId: String, val targetId: String) {
    init {
        require(sourceId.length <= 35 && sourceId.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")))
        require(targetId.length <= 35 && targetId.matches(Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")))
        require(sourceId != targetId)
    }
}

data class CorpusCase(val json: JSONObject) {
    val id = json.getString("id")
    val language = json.getString("language")
    val pair: EvaluationLanguagePair = when (json.getString("direction")) {
        "RU_TO_EN" -> EvaluationLanguagePair("ru", "en")
        "EN_TO_RU" -> EvaluationLanguagePair("en", "ru")
        else -> throw IllegalArgumentException("Unsupported historical direction")
    }.also { require(it.sourceId == language) { "Historical language/direction mismatch" } }
    val cohort = json.getString("cohort")
    val reference = json.getString("reference")
    val path = json.getString("path")
    val phrases: List<String> = json.getJSONArray("requiredSemanticPhrases").let { (0 until it.length()).map(it::getString) }
    val preparedReference by lazy {TextMetrics.prepare(reference)}
    val preparedPhrases by lazy {SemanticChecks.prepare(phrases)}
}

object CorpusManifest {
    fun read(directory: File, verifyFiles: Boolean = true): List<CorpusCase> {
        val locks=mapOf("source-lock.tsv" to "d535b8391c731998da1d2fedd6a70673814e8a887b04f966635c60f26603a5c1",
            "smoke-lock.json" to "d96eeee8afdc4c98de2ccccc1f3925b0b03e73937deaa2b09cb6e21fd0d0f105",
            "semantic-lock.json" to "6ca2d726b86afc521a4467dc683c32f693dad4ee5230ed28be0b5df85ce8ea42",
            "files.tsv" to "681adcf3352ac460b7eea7c83798d8062f94789337bdb3649b535bb742c3ecd0")
        locks.forEach { (name,hash) -> require(Integrity.sha256(File(directory,name))==hash) {"Corpus v1 independent lock changed: $name"} }
        val manifest = Integrity.json(File(directory, "manifest.json"))
        require(manifest.getInt("schemaVersion") == 1)
        require(Integrity.sha256(File(directory, "files.tsv")) == manifest.getString("filesSha256"))
        val cases = manifest.getJSONArray("cases").let { (0 until it.length()).map { i -> CorpusCase(it.getJSONObject(i)) } }
        val fileRows=File(directory,"files.tsv").readLines(Charsets.UTF_8).map {it.split('\t')}.associateBy {it[0]}
        require(fileRows.size==58)
        // Bound visits, not depth: reaching the budget rejects the tree instead of hiding a subtree.
        val members=directory.walkTopDown().take(129).toList()
        require(members.size<129 && members.all {it.canonicalFile==it.absoluteFile}) {"Unexpected corpus members or aliases"}
        val expectedMembers=cases.map {it.path}.toSet()+setOf("manifest.json","files.tsv","source-lock.tsv","smoke-lock.json","semantic-lock.json","ATTRIBUTION.md")
        require(members.filter(File::isFile).map {it.relativeTo(directory).invariantSeparatorsPath}.toSet()==expectedMembers) {"Canonical corpus membership changed"}
        val sourceRows=File(directory,"source-lock.tsv").readLines(Charsets.UTF_8).drop(1).map {it.split('|')}
            .associateBy {"${it[0]}/${it[1].removeSuffix(".mp3")}"}
        val semantics=Integrity.json(File(directory,"semantic-lock.json"))
        val smoke=org.json.JSONArray(File(directory,"smoke-lock.json").readText(Charsets.UTF_8))
        val smokeRows=(0 until smoke.length()).map(smoke::getJSONObject).associateBy {it.getString("id")}
        require(cases.size == 58 && cases.count { it.cohort == "smoke" } == 10 && cases.map { it.id }.toSet().size == 58)
        require(cases.sumOf { it.json.getLong("bytes") } == 8334120L)
        for (language in listOf("ru", "en")) {
            val quality = cases.filter { it.language == language && it.cohort == "quality" }
            require(quality.size == 24 && quality.map { it.json.getJSONObject("source").getString("client_id") }.toSet().size == 24)
        }
        cases.forEach { case ->
            require(case.language in setOf("ru", "en") && case.reference.length in 1..2048 && Integrity.safePath(case.path))
            require(case.pair.sourceId == case.language)
            val fileRow=fileRows.getValue(case.path)
            require(case.json.getLong("bytes")==fileRow[1].toLong() && case.json.getString("sha256")==fileRow[2])
            require(case.phrases.all { it.isNotBlank() })
            if(case.cohort=="quality") {
                val source=sourceRows.getValue("${case.language}/${case.id}")
                require(case.reference==source[3] && case.json.getJSONObject("source").getString("client_id")==source[2])
                val expected=semantics.getJSONArray("${case.language}|${case.id.substringAfterLast('_')}")
                require(case.phrases==(0 until expected.length()).map(expected::getString))
                require(case.json.getInt("sampleRateHz")==16000 && case.json.getInt("channels")==1 && case.json.getInt("bits")==16)
                require(case.json.getInt("sampleCount")==source[6].toInt()*16 && case.json.getLong("bytes")==source[10].toLong() && case.json.getString("sha256")==source[11])
                require(case.json.getInt("durationMs")==source[6].toInt() && case.path=="quality/${case.language}/${case.id}.wav")
                val provenance=case.json.getJSONObject("source")
                require(provenance.getInt("originalHz")==source[7].toInt() && provenance.getLong("mp3Bytes")==source[8].toLong() && provenance.getString("mp3Sha256")==source[9])
            } else {
                val expected=smokeRows.getValue(case.id)
                for(key in listOf("reference","path","bytes","sha256","sampleRateHz","channels","bits","sampleCount","durationMs","direction","requiredSemanticPhrases"))
                    require(case.json.get(key).toString()==expected.get(key).toString()) {"Smoke lock mismatch: ${case.id}/$key"}
            }
            if (verifyFiles) {
                val file = Integrity.verify(directory, case.path, case.json.getLong("bytes"), case.json.getString("sha256"))
                val wave = WaveReader.read(file, case.json.getInt("sampleRateHz"))
                require(wave.samples.size == case.json.getInt("sampleCount"))
            }
        }
        return cases
    }
}
