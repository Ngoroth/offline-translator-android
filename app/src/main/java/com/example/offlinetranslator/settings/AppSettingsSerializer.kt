package com.example.offlinetranslator.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
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
            require(integer(root, "schemaVersion") == 7) { "Unsupported settings schema" }
            require(root.keys().asSequence().all { it in FIELDS }) { "Unknown settings field" }
            val backendId = identifier(root, "speechOutputBackend")
            val backend = SpeechOutputBackend.entries.firstOrNull { it.persistedId == backendId }
                ?: throw IllegalArgumentException("Unknown speech output backend")
            val recognitionId = identifier(root, "speechRecognitionBackend")
            val recognitionBackend = SpeechRecognitionBackend.entries.firstOrNull { it.persistedId == recognitionId }
                ?: throw IllegalArgumentException("Unknown speech recognition backend")
            return AppSettings(
                languagePair = languagePair(root),
                speechOutputBackend = backend,
                speechRecognitionBackend = recognitionBackend,
                speechRecognitionService = recognitionService(root),
                translationEngineId = identifier(root, "translationEngineId"),
                translationSetupComplete = root.get("translationSetupComplete").let { require(it is Boolean); it },
                vadModelId = identifier(root, "vadModelId"),
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
            .put("schemaVersion", 7)
            .put("languagePair", JSONObject().put("source", t.languagePair.source.value).put("target", t.languagePair.target.value))
            .put("speechOutputBackend", t.speechOutputBackend.persistedId)
            .put("speechRecognitionBackend", t.speechRecognitionBackend.persistedId)
            .put("speechRecognitionService", t.speechRecognitionService ?: JSONObject.NULL)
            .put("translationEngineId", t.translationEngineId)
            .put("translationSetupComplete", t.translationSetupComplete)
            .put("vadModelId", t.vadModelId)
            .put("vadMinSilenceMs", t.vadMinSilenceMs)
            .put("ttsSpeedPercent", t.ttsSpeedPercent)
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        output.write(bytes)
    }

    private fun languagePair(root: JSONObject): LanguagePair {
        val pair = root.getJSONObject("languagePair")
        require(pair.keys().asSequence().all { it == "source" || it == "target" }) {
            "Unknown language pair field"
        }
        return LanguagePair(LanguageId(string(pair, "source")), LanguageId(string(pair, "target")))
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
        private val FIELDS = setOf(
            "schemaVersion", "languagePair", "speechOutputBackend", "speechRecognitionBackend",
            "speechRecognitionService", "translationEngineId", "translationSetupComplete",
            "vadModelId", "vadMinSilenceMs", "ttsSpeedPercent",
        )
        private val COMPONENT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*/\\.?[A-Za-z_\\$][A-Za-z0-9_\\$]*(?:\\.[A-Za-z_\\$][A-Za-z0-9_\\$]*)*")
        private val ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
    }
}
