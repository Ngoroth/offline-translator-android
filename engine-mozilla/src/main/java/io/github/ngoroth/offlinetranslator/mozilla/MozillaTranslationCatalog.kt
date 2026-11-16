package io.github.ngoroth.offlinetranslator.mozilla

import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguageId
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.TranslationCapability
import org.json.JSONObject
import java.io.File

/** Private file inventory. No registry or model metadata is obtained at runtime. */
internal data class MozillaTranslationFile(
    val role: String,
    val name: String,
    val compressedBytes: Long,
    val bytes: Long,
    val sha256: String,
    val generation: String,
    val url: String,
    val gzip: Boolean = true,
) {
    val installedName: String get() = sha256 + if (name.endsWith(".spm")) ".spm" else ".bin"
    val transferBytes: Long get() = if (gzip) compressedBytes else bytes
}

internal data class MozillaTranslationModel(
    val pair: LanguagePair,
    val architecture: String,
    val version: String,
    val files: List<MozillaTranslationFile>,
) {
    val key: String get() = "${pair.source.value}-${pair.target.value}"

    fun configuration(installed: (MozillaTranslationFile) -> File, prefix: File): String {
        fun path(role: String) = JSONObject.quote(installed(files.single { it.role == role }).absolutePath)
        val sourceVocab = if (files.any { it.role == "vocab" }) "vocab" else "srcvocab"
        val targetVocab = if (sourceVocab == "vocab") "vocab" else "trgvocab"
        return """
            models: [${path("model")}]
            vocabs: [${path(sourceVocab)}, ${path(targetVocab)}]
            shortlist: [${path("lex")}, false]
            ssplit-prefix-file: ${JSONObject.quote(prefix.absolutePath)}
            ssplit-mode: paragraph
            beam-size: 1
            normalize: 1.0
            word-penalty: 0.0
            max-length-factor: 2.0
            max-length-break: 128
            mini-batch-words: 1024
            workspace: 128
            cpu-threads: 4
            gemm-precision: int8shiftAll
            skip-cost: true
            alignment: soft
            quiet: true
            quiet-translation: true
        """.trimIndent() + "\n"
    }
}

internal class MozillaTranslationCatalog private constructor(
    private val direct: Map<LanguagePair, MozillaTranslationModel>,
) {
    val languages: Set<LanguageId> = direct.keys.flatMap { listOf(it.source, it.target) }.toSet()

    fun capability(pair: LanguagePair): TranslationCapability = when {
        pair.source !in languages || pair.target !in languages -> TranslationCapability.Unsupported
        pair in direct -> TranslationCapability.Direct
        LanguagePair(pair.source, LanguageCatalog.ENGLISH) in direct &&
            LanguagePair(LanguageCatalog.ENGLISH, pair.target) in direct -> TranslationCapability.Pivot(LanguageCatalog.ENGLISH)
        else -> TranslationCapability.Unsupported
    }

    fun models(pair: LanguagePair): List<MozillaTranslationModel> = direct[pair]?.let { listOf(it) } ?: listOf(
        requireNotNull(direct[LanguagePair(pair.source, LanguageCatalog.ENGLISH)]),
        requireNotNull(direct[LanguagePair(LanguageCatalog.ENGLISH, pair.target)]),
    )

    fun models(pairs: Set<LanguagePair>): List<MozillaTranslationModel> =
        pairs.flatMap(::models).distinctBy { it.pair }

    fun files(models: List<MozillaTranslationModel>): List<MozillaTranslationFile> =
        models.flatMap { it.files }.distinctBy { it.installedName }

    companion object {
        const val SOURCE_REVISION = "8eada038fd45bbf6fa58a412b22f80c79887fabc"
        val PREFIX_LANGUAGES = setOf("en", "es", "it", "zh", "de", "pl", "pt", "ru", "fr", "nl")
        val PINNED: MozillaTranslationCatalog by lazy {
            val stream = requireNotNull(MozillaTranslationCatalog::class.java.getResourceAsStream("/mozilla-translation-catalog.json"))
            parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        internal fun parse(text: String): MozillaTranslationCatalog {
            val json = JSONObject(text)
            val base = json.getString("baseUrl")
            require(base == "https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data")
            require(json.getJSONObject("appLanguageMapping").getString("zh") == "zh-Hans")
            val rawAttachments = json.optJSONObject("rawAttachments") ?: JSONObject()
            for (hash in rawAttachments.keys()) {
                require(hash.matches(Regex("[a-f0-9]{64}")))
                require(rawAttachments.getString(hash).matches(Regex(
                    "https://firefox-settings-attachments\\.cdn\\.mozilla\\.net/main-workspace/translations-models/[A-Za-z0-9_-]+\\.(bin|spm)"
                )))
            }
            val selected = linkedMapOf<LanguagePair, MozillaTranslationModel>()
            val rows = json.getJSONArray("routes")
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                fun language(name: String): LanguageId = LanguageId(row.getString(name).let { if (it == "zh-Hans") "zh" else it })
                val pair = LanguagePair(language("source"), language("target"))
                require((pair.source == LanguageCatalog.ENGLISH) xor (pair.target == LanguageCatalog.ENGLISH))
                require(pair.source in SUPPORTED_LANGUAGES && pair.target in SUPPORTED_LANGUAGES)
                val architecture = row.getString("architecture")
                require(architecture == if (pair.id in TINY_PAIRS) "tiny" else "base-memory")
                val version = row.getString("version").also { require(it.matches(Regex("[0-9]+\\.[0-9]+"))) }
                val directory = row.getString("directory")
                require(directory.startsWith("models/") && directory.endsWith("/exported/"))
                require(directory.matches(Regex("[A-Za-z0-9_./-]+")) && directory.split('/').none { it == "." || it == ".." })
                val files = row.getJSONArray("files").let { entries ->
                    List(entries.length()) { fileIndex ->
                        val file = entries.getJSONArray(fileIndex)
                        require(file.length() == 7)
                        val role = file.getString(0).lowercase().let { if (it == "lexicalshortlist") "lex" else it }
                        require(role in setOf("model", "lex", "vocab", "srcvocab", "trgvocab"))
                        val name = file.getString(1)
                        require(name.matches(Regex("[A-Za-z0-9_.-]+")) && name != "." && name != "..")
                        require(name.endsWith(if (role.endsWith("vocab")) ".spm" else ".bin"))
                        val compressed = file.getLong(2).also { require(it in 1..MAX_FILE_BYTES) }
                        val bytes = file.getLong(3).also { require(it in 1..MAX_FILE_BYTES) }
                        val hash = file.getString(4).also { require(it.matches(Regex("[a-f0-9]{64}"))) }
                        val generation = file.getString(5).also { require(it.matches(Regex("[0-9]+"))) }
                        val rawUrl = rawAttachments.optString(hash).takeIf { it.isNotEmpty() }
                        MozillaTranslationFile(role, name, compressed, bytes, hash, generation,
                            rawUrl ?: "$base/$directory$name.gz?generation=$generation", gzip = rawUrl == null)
                    }
                }
                val roles = files.map { it.role }.toSet()
                require(roles.size == files.size && roles in listOf(setOf("model", "lex", "vocab"), setOf("model", "lex", "srcvocab", "trgvocab")))
                require(selected.put(pair, MozillaTranslationModel(pair, architecture, version, files)) == null)
            }
            val expected = SUPPORTED_LANGUAGES.filter { it != LanguageCatalog.ENGLISH }.flatMap {
                listOf(LanguagePair(LanguageCatalog.ENGLISH, it), LanguagePair(it, LanguageCatalog.ENGLISH))
            }.toSet()
            require(selected.keys == expected)
            val components = selected.values.flatMap { it.files }
            require(rawAttachments.keys().asSequence().all { hash -> components.any { it.sha256 == hash } })
            components.groupBy { it.installedName }.values.forEach { same -> require(same.map { it.bytes }.distinct().size == 1) }
            return MozillaTranslationCatalog(selected.toMap())
        }

        private const val MAX_FILE_BYTES = 128 * 1024 * 1024L
        private val SUPPORTED_LANGUAGES = setOf("en", "vi", "es", "id", "it", "zh", "ko", "de", "pl", "pt", "ru", "th", "tr", "fr", "hi", "ja", "nl").map { LanguageId(it) }.toSet()
        private val TINY_PAIRS = setOf("vi->en", "en->id", "id->en", "en->tr", "tr->en", "en->hi", "hi->en")
    }
}
