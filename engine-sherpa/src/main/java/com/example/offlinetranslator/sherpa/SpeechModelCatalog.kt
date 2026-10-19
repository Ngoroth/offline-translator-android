package com.example.offlinetranslator.sherpa

import android.content.Context
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI

data class SpeechModelFile(val path: String, val bytes: Long, val sha256: String)

data class SpeechModelPackage(
    val id: String,
    val displayName: String,
    val source: String,
    val license: String,
    val url: String,
    val allowedRedirectHosts: Set<String>,
    val format: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val maxUnpackedBytes: Long,
    val files: List<SpeechModelFile>,
    val maxEntries: Int = 1_024,
)

class SpeechModelCatalog private constructor(
    val packages: List<SpeechModelPackage>,
    val models: List<SpeechModelDefinition>,
) {
    private val packagesById = packages.associateBy { it.id }
    private val modelsById = models.associateBy { it.id }

    fun packageInfo(id: String): SpeechModelPackage =
        requireNotNull(packagesById[id]) { "Unknown speech package: $id" }

    fun model(id: String): SpeechModelDefinition =
        requireNotNull(modelsById[id]) { "Unknown speech model: $id" }

    fun requiredPackages(modelIds: Set<String>): Set<String> =
        modelIds.flatMap { model(it).requiredRefs }.mapTo(linkedSetOf()) { it.packageId }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private val ID = Regex("[a-z0-9][a-z0-9.-]{0,79}")
        // File names stay literal throughout installation; percent sequences are never URL-decoded.
        private val PATH_SEGMENT = Regex("[A-Za-z0-9.!_%-]+(?: [A-Za-z0-9.!_%-]+)*")
        internal const val MAX_CATALOG_BYTES = 256 * 1_024
        private const val MAX_PACKAGE_BYTES = 1_024L * 1_024 * 1_024
        private const val MAX_EXPECTED_FILES = 512

        fun fromAssets(context: Context): SpeechModelCatalog =
            context.assets.open("speech-models.json").use(::parse)

        fun parse(input: InputStream): SpeechModelCatalog {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_CATALOG_BYTES) { "Model catalog too large" }
                output.write(buffer, 0, read)
            }
            try {
                val document = JSONObject(output.toString(Charsets.UTF_8.name()))
                document.fields("schemaVersion", "runtime", "packages", "models")
                require(document.integer("schemaVersion") == 2L) { "Unsupported model catalog" }
                require(document.text("runtime") == "sherpa-onnx-1.13.8") { "Wrong runtime" }
                val rawPackages = document.getJSONArray("packages")
                require(rawPackages.length() <= 64) { "Too many packages" }
                val packages = (0 until rawPackages.length()).map { parsePackage(rawPackages.getJSONObject(it)) }
                require(packages.map { it.id }.toSet().size == packages.size) { "Duplicate package ID" }
                val inventory = packages.associateBy { it.id }
                val rawModels = document.getJSONArray("models")
                require(rawModels.length() <= 64) { "Too many models" }
                val models = (0 until rawModels.length()).map { parseModel(rawModels.getJSONObject(it), inventory) }
                require(models.map { it.id }.toSet().size == models.size) { "Duplicate model ID" }
                return SpeechModelCatalog(packages, models)
            } catch (error: JSONException) {
                throw IllegalArgumentException("Invalid model catalog metadata", error)
            }
        }

        private fun parsePackage(entry: JSONObject): SpeechModelPackage {
            entry.fields("id", "displayName", "source", "license", "url", "allowedRedirectHosts",
                "format", "archiveBytes", "archiveSha256", "maxUnpackedBytes", "files", "maxEntries")
            val id = entry.text("id")
            require(ID.matches(id)) { "Invalid package ID" }
            val url = entry.text("url")
            val hostArray = entry.getJSONArray("allowedRedirectHosts")
            require(hostArray.length() in 1..8) { "Invalid redirect hosts" }
            val hosts = hostArray.strings().toSet()
            require(hosts.size == hostArray.length() && hosts.all {
                it.length <= 253 && it.matches(Regex("[a-z0-9.-]+"))
            }) { "Invalid redirect hosts" }
            val uri = try {
                URI(url)
            } catch (error: java.net.URISyntaxException) {
                throw IllegalArgumentException("Unsafe model URL", error)
            }
            require(uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.rawFragment == null) { "Unsafe model URL" }
            val format = entry.text("format")
            require(format == "raw" || format == "tar.bz2") { "Unsupported package format" }
            val archiveBytes = entry.integer("archiveBytes")
            val archiveSha = entry.text("archiveSha256")
            val limit = entry.integer("maxUnpackedBytes")
            require(archiveBytes in 1..MAX_PACKAGE_BYTES && limit in 1..MAX_PACKAGE_BYTES &&
                HASH.matches(archiveSha)) { "Invalid package size or hash" }
            val maxEntries = if (entry.has("maxEntries")) entry.intRange("maxEntries", 1..1_024) else 1_024
            val rawFiles = entry.getJSONArray("files")
            require(rawFiles.length() in 1..MAX_EXPECTED_FILES && rawFiles.length() <= maxEntries) {
                "Invalid expected file count"
            }
            val files = (0 until rawFiles.length()).map { index ->
                val file = rawFiles.getJSONObject(index)
                file.fields("path", "bytes", "sha256")
                val path = file.text("path")
                val size = file.integer("bytes")
                val sha = file.text("sha256")
                require(safeRelativePath(path) && size in 1..limit && HASH.matches(sha)) { "Invalid expected file" }
                SpeechModelFile(path, size, sha)
            }
            require(files.map { it.path }.toSet().size == files.size) { "Duplicate expected file" }
            require(files.sumOf { it.bytes } <= limit) { "Expected files exceed quota" }
            require(format != "raw" || files.size == 1 && files[0].bytes == archiveBytes &&
                files[0].sha256 == archiveSha) { "Invalid raw package" }
            return SpeechModelPackage(id, entry.text("displayName"), entry.text("source"), entry.text("license"),
                url, hosts, format, archiveBytes, archiveSha, limit, files, maxEntries)
        }

        private fun parseModel(entry: JSONObject, inventory: Map<String, SpeechModelPackage>): SpeechModelDefinition {
            entry.fields("id", "displayName", "role", "languages", "sampleRateHz", "adapter")
            val id = entry.text("id")
            require(ID.matches(id)) { "Invalid model ID" }
            val role = SpeechModelRole.valueOf(entry.text("role"))
            val languageStrings = entry.getJSONArray("languages").strings()
            require(languageStrings.size <= 16 && languageStrings.distinct().size == languageStrings.size) {
                "Invalid model languages"
            }
            val languages = languageStrings.mapTo(linkedSetOf()) { value ->
                LanguageId(value).also { require(it.value == value) { "Noncanonical model language" } }
            }
            val rate = entry.intRange("sampleRateHz", 8_000..48_000)
            val json = entry.getJSONObject("adapter")
            val refs = mutableListOf<SpeechModelRef>()
            fun ref(name: String, directory: Boolean = false): SpeechModelRef {
                val value = json.getJSONObject(name)
                value.fields("packageId", "path")
                val result = SpeechModelRef(value.text("packageId"), value.text("path"))
                require(safeRelativePath(result.path)) { "Unsafe model reference" }
                val pkg = requireNotNull(inventory[result.packageId]) { "Undeclared reference package" }
                val files = if (directory) pkg.files.filter { it.path.startsWith(result.path + "/") }
                    else pkg.files.filter { it.path == result.path }
                require(files.isNotEmpty()) { "Undeclared reference file or directory" }
                refs += files.map { SpeechModelRef(pkg.id, it.path) }
                return result
            }
            fun threads() = json.intRange("numThreads", 1..16)
            fun features() = json.intRange("featureDim", 80..80)
            val adapter: SpeechModelAdapter = when (json.text("family")) {
                "silero" -> {
                    json.fields("family", "model", "numThreads", "threshold", "minSpeechDuration", "maxSpeechDuration", "windowSize")
                    require(role == SpeechModelRole.VAD && languages.isEmpty() && rate == 16_000) { "Invalid Silero role/language/rate" }
                    val min = json.positiveFloat("minSpeechDuration", 14f)
                    val max = json.positiveFloat("maxSpeechDuration", 14f)
                    require(min <= max) { "Invalid speech durations" }
                    SpeechModelAdapter.Silero(ref("model"), threads(), json.positiveFloat("threshold", 1f), min, max,
                        json.intRange("windowSize", 512..512))
                }
                "nemoCtcOffline" -> {
                    json.fields("family", "model", "tokens", "numThreads", "featureDim")
                    requireAsr(role, languages, rate)
                    require(rate == 16_000) { "Unsupported offline Nemo sample rate" }
                    SpeechModelAdapter.NemoCtcOffline(ref("model"), ref("tokens"), threads(), features())
                }
                "whisperOffline" -> {
                    json.fields("family", "encoder", "decoder", "tokens", "language", "task", "tailPaddings", "numThreads", "featureDim")
                    val language = json.text("language")
                    require(role == SpeechModelRole.ASR && languages.size == 1 && languages.single().value == language && rate == 16_000) {
                        "Invalid Whisper role/language/rate"
                    }
                    require(json.text("task") == "transcribe") { "Unsupported Whisper task" }
                    SpeechModelAdapter.WhisperOffline(ref("encoder"), ref("decoder"), ref("tokens"), language,
                        json.text("task"), json.intRange("tailPaddings", 0..10_000), threads(), features())
                }
                "whisperLanguageId" -> {
                    json.fields("family", "encoder", "decoder", "numThreads")
                    require(role == SpeechModelRole.LANGUAGE_ID && languages.size >= 2 && rate == 16_000) { "Invalid language identifier" }
                    SpeechModelAdapter.WhisperLanguageId(ref("encoder"), ref("decoder"), threads())
                }
                "piperVits" -> {
                    json.fields("family", "model", "tokens", "dataDir", "noiseScale", "noiseScaleW", "lengthScale", "numThreads")
                    require(role == SpeechModelRole.TTS && languages.size == 1 && rate == 22_050) { "Invalid Piper role/language/rate" }
                    SpeechModelAdapter.PiperVits(ref("model"), ref("tokens"), ref("dataDir", directory = true),
                        json.positiveFloat("noiseScale", 10f), json.positiveFloat("noiseScaleW", 10f),
                        json.positiveFloat("lengthScale", 10f), threads())
                }
                "toneCtcOnline" -> {
                    json.fields("family", "model", "tokens", "numThreads", "featureDim")
                    requireAsr(role, languages, rate)
                    SpeechModelAdapter.ToneCtcOnline(ref("model"), ref("tokens"), threads(), features())
                }
                "zipformer2Online" -> {
                    json.fields("family", "encoder", "decoder", "joiner", "tokens", "numThreads", "featureDim")
                    requireAsr(role, languages, rate)
                    SpeechModelAdapter.Zipformer2Online(ref("encoder"), ref("decoder"), ref("joiner"), ref("tokens"), threads(), features())
                }
                else -> throw IllegalArgumentException("Unsupported speech adapter family")
            }
            require(refs.distinct().size == refs.size) { "Aliased model references" }
            return SpeechModelDefinition(id, entry.text("displayName"), role, languages, rate, adapter, refs)
        }

        private fun requireAsr(role: SpeechModelRole, languages: Set<LanguageId>, rate: Int) {
            require(role == SpeechModelRole.ASR && languages.isNotEmpty() && rate in setOf(8_000, 16_000)) {
                "Invalid ASR role/language/rate"
            }
        }

        private fun JSONObject.fields(vararg allowed: String) {
            require(keys().asSequence().all { it in allowed }) { "Unknown catalog field" }
        }

        private fun JSONObject.text(name: String): String {
            val value = get(name)
            require(value is String && value.isNotEmpty()) { "Invalid string: $name" }
            return value
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map {
            val value = get(it)
            require(value is String && value.isNotEmpty()) { "Invalid string array" }
            value
        }

        private fun JSONObject.integer(name: String): Long {
            val value = get(name)
            require(value is Byte || value is Short || value is Int || value is Long) { "Invalid integer: $name" }
            return (value as Number).toLong()
        }

        private fun JSONObject.intRange(name: String, range: IntRange): Int {
            val value = integer(name)
            require(value in range.first.toLong()..range.last.toLong()) { "Invalid parameter: $name" }
            return value.toInt()
        }

        private fun JSONObject.positiveFloat(name: String, max: Float): Float {
            val value = get(name)
            require(value is Number) { "Invalid number: $name" }
            val result = value.toFloat()
            require(result.isFinite() && result > 0f && result <= max) { "Invalid parameter: $name" }
            return result
        }

        internal fun safeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.length > 240 || path.startsWith('/') || path.startsWith('\\') ||
                '\\' in path || ':' in path || path.count { it == '/' } >= 14) {
                return false
            }
            return path.split('/').all { segment ->
                segment != "." && segment != ".." && PATH_SEGMENT.matches(segment)
            }
        }
    }
}
