package com.example.offlinetranslator.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings {
        val raw = input.use { stream ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > MAX_BYTES) throw CorruptionException("Settings document exceeds limit")
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
        try {
            val root = JSONObject(raw.toString(Charsets.UTF_8))
            val version = integer(root, "schemaVersion")
            require(version in 1..6) { "Unsupported settings schema" }
            if (version == 1) validateLegacyModels(root.getJSONObject("ttsModelIds"))
            else {
                val allowedFields = when (version) {
                    2 -> V2_FIELDS
                    3 -> V3_FIELDS
                    4 -> V4_FIELDS
                    5 -> V5_FIELDS
                    else -> V6_FIELDS
                }
                require(root.keys().asSequence().all { it in allowedFields }) { "Unknown settings field" }
            }
            val pair: LanguagePair
            val backend: SpeechOutputBackend
            if (version <= 3) {
                if (version <= 2) validateLegacyModels(root.getJSONObject("asrModelIds"))
                // The discarded text pair must still satisfy the legacy document contract.
                languagePair(root, "textPair", closedFields = version >= 2)
                val languages = root.getJSONArray("conversationLanguages")
                require(languages.length() == 2) { "Conversation languages must contain exactly two entries" }
                pair = LanguagePair(
                    LanguageId(string(languages, 0)),
                    LanguageId(string(languages, 1)),
                )
                backend = SpeechOutputBackend.PIPER
            } else {
                pair = languagePair(root, "languagePair", closedFields = true)
                val backendId = string(root, "speechOutputBackend")
                backend = SpeechOutputBackend.entries.firstOrNull { it.persistedId == backendId }
                    ?: throw IllegalArgumentException("Unknown speech output backend")
            }
            val recognitionBackend = if (version >= 5) {
                val backendId = string(root, "speechRecognitionBackend")
                SpeechRecognitionBackend.entries.firstOrNull { it.persistedId == backendId }
                    ?: throw IllegalArgumentException("Unknown speech recognition backend")
            } else SpeechRecognitionBackend.SYSTEM_OFFLINE
            return AppSettings(
                languagePair = pair,
                speechOutputBackend = backend,
                speechRecognitionBackend = recognitionBackend,
                speechRecognitionService = if (version >= 6) recognitionService(root) else null,
                textEngineId = identifier(root, "textEngineId"),
                conversationEngineId = identifier(root, "conversationEngineId"),
                vadModelId = identifier(root, "vadModelId"),
                languageIdModelId = identifier(root, "languageIdModelId"),
                vadMinSilenceMs = integer(root, "vadMinSilenceMs"),
                ttsSpeedPercent = integer(root, "ttsSpeedPercent"),
            )
        } catch (invalid: JSONException) {
            throw CorruptionException("Malformed settings document", invalid)
        } catch (invalid: IllegalArgumentException) {
            throw CorruptionException("Invalid settings document", invalid)
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        t.speechRecognitionService?.let { require(validRecognitionService(it)) { "Invalid recognition service component" } }
        val root = JSONObject()
            .put("schemaVersion", 6)
            .put("languagePair", JSONObject().put("source", t.languagePair.source.value).put("target", t.languagePair.target.value))
            .put("speechOutputBackend", t.speechOutputBackend.persistedId)
            .put("speechRecognitionBackend", t.speechRecognitionBackend.persistedId)
            .put("speechRecognitionService", t.speechRecognitionService ?: JSONObject.NULL)
            .put("textEngineId", t.textEngineId)
            .put("conversationEngineId", t.conversationEngineId)
            .put("vadModelId", t.vadModelId)
            .put("languageIdModelId", t.languageIdModelId)
            .put("vadMinSilenceMs", t.vadMinSilenceMs)
            .put("ttsSpeedPercent", t.ttsSpeedPercent)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        output.write(bytes)
    }

    private fun languagePair(root: JSONObject, key: String, closedFields: Boolean): LanguagePair {
        val pair = root.getJSONObject(key)
        if (closedFields) require(pair.keys().asSequence().all { it == "source" || it == "target" }) {
            "Unknown language pair field"
        }
        return LanguagePair(LanguageId(string(pair, "source")), LanguageId(string(pair, "target")))
    }

    private fun string(array: JSONArray, index: Int): String =
        array.get(index).let { require(it is String); it }

    private fun validateLegacyModels(root: JSONObject) {
        require(root.length() <= 16)
        val languages = HashSet<LanguageId>(root.length())
        root.keys().forEach { key ->
            val language = LanguageId(key)
            identifier(root, key)
            require(languages.add(language)) { "Duplicate normalized language" }
        }
    }

    private fun recognitionService(root: JSONObject): String? {
        val value = root.get("speechRecognitionService")
        if (value === JSONObject.NULL) return null
        require(value is String && validRecognitionService(value)) { "Invalid recognition service component" }
        return value
    }

    private fun validRecognitionService(value: String): Boolean =
        value.length <= 512 && COMPONENT.matches(value)

    private fun identifier(root: JSONObject, key: String): String = string(root, key).also { require(ID.matches(it)) }
    private fun string(root: JSONObject, key: String): String = root.get(key).let { require(it is String); it }
    private fun integer(root: JSONObject, key: String): Int {
        val value = root.get(key)
        require(value is Number && (value is Byte || value is Short || value is Int || value is Long))
        val long = value.toLong()
        require(long in Int.MIN_VALUE..Int.MAX_VALUE)
        return long.toInt()
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
        private val V3_FIELDS = setOf(
            "schemaVersion", "textPair", "conversationLanguages", "textEngineId",
            "conversationEngineId", "vadModelId", "languageIdModelId",
            "vadMinSilenceMs", "ttsSpeedPercent",
        )
        private val V2_FIELDS = V3_FIELDS + "asrModelIds"
        private val V4_FIELDS = setOf(
            "schemaVersion", "languagePair", "speechOutputBackend", "textEngineId",
            "conversationEngineId", "vadModelId", "languageIdModelId",
            "vadMinSilenceMs", "ttsSpeedPercent",
        )
        private val V5_FIELDS = V4_FIELDS + "speechRecognitionBackend"
        private val V6_FIELDS = V5_FIELDS + "speechRecognitionService"
        private val COMPONENT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*/\\.?[A-Za-z_\\$][A-Za-z0-9_\\$]*(?:\\.[A-Za-z_\\$][A-Za-z0-9_\\$]*)*")
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}
