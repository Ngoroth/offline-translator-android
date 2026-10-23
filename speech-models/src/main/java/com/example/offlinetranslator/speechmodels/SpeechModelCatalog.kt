package com.example.offlinetranslator.speechmodels

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

        fun merge(vararg catalogs: SpeechModelCatalog): SpeechModelCatalog {
            val packages = catalogs.flatMap { it.packages }
            val models = catalogs.flatMap { it.models }
            require(packages.size <= 64) { "Too many packages" }
            require(models.size <= 64) { "Too many models" }
            require(packages.map { it.id }.toSet().size == packages.size) { "Duplicate package ID" }
            require(models.map { it.id }.toSet().size == models.size) { "Duplicate model ID" }
            return SpeechModelCatalog(packages, models)
        }

        fun parse(
            input: InputStream,
            expectedRuntime: String,
            decodeAdapter: (SpeechModelAdapterReader) -> SpeechModelAdapter,
        ): SpeechModelCatalog {
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
                require(document.text("runtime") == expectedRuntime) { "Wrong runtime" }
                val rawPackages = document.getJSONArray("packages")
                require(rawPackages.length() <= 64) { "Too many packages" }
                val packages = (0 until rawPackages.length()).map { parsePackage(rawPackages.getJSONObject(it)) }
                require(packages.map { it.id }.toSet().size == packages.size) { "Duplicate package ID" }
                val inventory = packages.associateBy { it.id }
                val rawModels = document.getJSONArray("models")
                require(rawModels.length() <= 64) { "Too many models" }
                val models = (0 until rawModels.length()).map { parseModel(rawModels.getJSONObject(it), inventory, decodeAdapter) }
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

        private fun parseModel(
            entry: JSONObject,
            inventory: Map<String, SpeechModelPackage>,
            decodeAdapter: (SpeechModelAdapterReader) -> SpeechModelAdapter,
        ): SpeechModelDefinition {
            entry.fields("id", "displayName", "role", "languages", "sampleRateHz", "adapter")
            val id = entry.text("id")
            require(ID.matches(id)) { "Invalid model ID" }
            val role = SpeechModelRole.valueOf(entry.text("role"))
            val languageStrings = entry.getJSONArray("languages").strings()
            require(languageStrings.size <= 32 && languageStrings.distinct().size == languageStrings.size) {
                "Invalid model languages"
            }
            val languages = languageStrings.mapTo(linkedSetOf()) { value ->
                LanguageId(value).also { require(it.value == value) { "Noncanonical model language" } }
            }
            val rate = entry.intRange("sampleRateHz", 8_000..48_000)
            val reader = SpeechModelAdapterReader(role, languages, rate, entry.getJSONObject("adapter"), inventory)
            val adapter = decodeAdapter(reader)
            val refs = reader.requiredRefs
            require(refs.distinct().size == refs.size) { "Aliased model references" }
            return SpeechModelDefinition(id, entry.text("displayName"), role, languages, rate, adapter, refs.toList())
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
