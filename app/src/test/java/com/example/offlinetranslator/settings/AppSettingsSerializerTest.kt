package com.example.offlinetranslator.settings

import androidx.datastore.core.CorruptionException
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class AppSettingsSerializerTest {
    private val serializer = AppSettingsSerializer()

    @Test fun schemaSevenWithoutOutputChoicePreservesExistingSettingsAndDefaultsOn() = runBlocking<Unit> {
        val old = document().apply { remove("speechOutputEnabled") }
        assertEquals(AppSettingsTestFixtures.selected.copy(speechOutputEnabled = true),
            serializer.readFrom(old.toString().byteInputStream()))
        for (invalid in listOf("false", 0, JSONObject.NULL, JSONObject(), JSONArray())) {
            assertCorrupt(document().put("speechOutputEnabled", invalid))
        }
        for (enabled in listOf(false, true)) {
            val selected = AppSettingsTestFixtures.selected.copy(speechOutputEnabled = enabled)
            val output = ByteArrayOutputStream()
            serializer.writeTo(selected, output)
            assertEquals(selected, serializer.readFrom(output.toByteArray().inputStream()))
        }
    }

    @Test fun roundTripPreservesCommonEngineCompletionPairAndIndependentSpeechPreferences() = runBlocking<Unit> {
        for (backend in SpeechRecognitionBackend.entries) {
            val selected = AppSettingsTestFixtures.selected.copy(
                languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH),
                speechRecognitionBackend = backend,
                speechRecognitionService = "missing.provider/.RecognitionService",
                translationEngineId = "other-engine", vadModelId = "other-vad",
                vadMinSilenceMs = 1200, ttsSpeedPercent = 115,
            )
            val output = ByteArrayOutputStream()
            serializer.writeTo(selected, output)
            assertEquals(selected, serializer.readFrom(output.toByteArray().inputStream()))
        }
    }

    @Test fun completionRequiresAnExplicitBoolean() {
        assertCorrupt(document().apply { remove("translationSetupComplete") })
        for (invalid in listOf("true", 1, JSONObject.NULL, JSONObject(), JSONArray())) {
            assertCorrupt(document().put("translationSetupComplete", invalid))
        }
    }

    @Test fun currentDocumentAndPairHaveClosedFields() {
        assertCorrupt(document().put("unknown", true))
        assertCorrupt(document().apply { getJSONObject("languagePair").put("unknown", true) })
    }

    @Test fun outputAndRecognitionRequireExactKnownBackendIds() {
        for (field in listOf("speechOutputBackend", "speechRecognitionBackend")) {
            assertCorrupt(document().apply { remove(field) })
            for (invalid in listOf("SYSTEM_OFFLINE", "unknown", "", "../unsafe", 12, true, JSONObject.NULL)) {
                assertCorrupt(document().put(field, invalid))
            }
        }
    }

    @Test fun recognitionServiceRequiresNullOrBoundedComponentSyntaxButNotAnInstalledPackage() = runBlocking<Unit> {
        assertCorrupt(document().apply { remove("speechRecognitionService") })
        for (invalid in listOf("", "on-device", "provider/", "/Service", "provider//Service",
            "provider/.Bad..Service", "provider/../../Service", "provider with spaces/.Service",
            "provider/.${"S".repeat(512)}", 12, true, JSONArray(), JSONObject())) {
            assertCorrupt(document().put("speechRecognitionService", invalid))
        }
        for (component in listOf("missing.provider/.RecognitionService", "missing.provider/missing.provider.RecognitionService",
            "p/.${"S".repeat(509)}")) {
            val selected = AppSettingsTestFixtures.selected.copy(speechRecognitionService = component)
            val output = ByteArrayOutputStream()
            serializer.writeTo(selected, output)
            assertEquals(selected, serializer.readFrom(output.toByteArray().inputStream()))
        }
    }

    @Test fun invalidServiceCannotBeWrittenAsAnUnreadablePreference() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { serializer.writeTo(AppSettings(speechRecognitionService = "not-a-component"), output) }
        }
        assertEquals(0, output.size())
    }

    @Test fun sharedPairRequiresExactlyTwoValidDistinctLanguages() {
        assertCorrupt(document().apply { remove("languagePair") })
        for (invalid in listOf(JSONObject.NULL, "en-de", JSONArray())) {
            assertCorrupt(document().put("languagePair", invalid))
        }
        for (member in listOf("source", "target")) {
            assertCorrupt(document().apply { getJSONObject("languagePair").remove(member) })
            for (invalid in listOf(12, true, JSONObject.NULL, "invalid_language")) {
                assertCorrupt(document().apply { getJSONObject("languagePair").put(member, invalid) })
            }
        }
        assertCorrupt(document().put("languagePair", JSONObject().put("source", "ru").put("target", "RU")))
    }

    @Test fun identifiersAndIntegersRejectInvalidTypesAndBounds() {
        for (field in listOf("translationEngineId", "vadModelId")) {
            assertCorrupt(document().apply { remove(field) })
            for (invalid in listOf("../unsafe", "", "a".repeat(129), 12, JSONObject.NULL)) {
                assertCorrupt(document().put(field, invalid))
            }
        }
        for (field in listOf("schemaVersion", "vadMinSilenceMs", "ttsSpeedPercent")) {
            assertCorrupt(document().apply { remove(field) })
            for (invalid in listOf(100.5, "100", true, JSONObject.NULL, Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1)) {
                assertCorrupt(document().put(field, invalid))
            }
        }
    }

    @Test fun interruptedReadPreservesTheIOExceptionInsteadOfReportingCorruption() = runBlocking<Unit> {
        val output = ByteArrayOutputStream()
        serializer.writeTo(AppSettings(ttsSpeedPercent = 110), output)
        val failure = IOException("fixture read interrupted")
        val input = object : ByteArrayInputStream(output.toByteArray()) {
            private var firstRead = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) throw failure
                firstRead = false
                return super.read(buffer, offset, minOf(length, 24))
            }
        }
        assertSame(failure, runCatching { serializer.readFrom(input) }.exceptionOrNull())
    }

    @Test fun failedWritePreservesTheIOException() = runBlocking<Unit> {
        val failure = IOException("fixture write interrupted")
        val output = object : OutputStream() { override fun write(value: Int) { throw failure } }
        assertSame(failure, runCatching { serializer.writeTo(AppSettingsTestFixtures.selected, output) }.exceptionOrNull())
    }

    @Test fun malformedJsonThrowsCorruptionException() {
        assertThrows(CorruptionException::class.java) {
            runBlocking<Unit> { serializer.readFrom("{not valid json".byteInputStream()) }
        }
    }

    @Test fun onlyCurrentSchemaIsSupported() {
        for (version in listOf(0, 1, 2, 3, 4, 5, 6, 8)) assertCorrupt(document().put("schemaVersion", version))
    }

    @Test fun documentByteLimitIsInclusive() = runBlocking<Unit> {
        val bytes = document().toString().toByteArray(Charsets.UTF_8)
        val atLimit = bytes + ByteArray(AppSettingsSerializer.MAX_BYTES - bytes.size) { ' '.code.toByte() }
        assertEquals(AppSettingsTestFixtures.selected, serializer.readFrom(ByteArrayInputStream(atLimit)))
        assertThrows(CorruptionException::class.java) {
            runBlocking<Unit> { serializer.readFrom(ByteArrayInputStream(atLimit + ' '.code.toByte())) }
        }
    }

    @Test fun oversizedOutputIsRejectedBeforeAnyBytesAreWritten() {
        val output = ByteArrayOutputStream()
        val oversized = AppSettings(translationEngineId = "a".repeat(AppSettingsSerializer.MAX_BYTES))
        assertThrows(IllegalArgumentException::class.java) { runBlocking<Unit> { serializer.writeTo(oversized, output) } }
        assertEquals(0, output.size())
    }

    private fun document(): JSONObject = AppSettingsTestFixtures.document()
    private fun assertCorrupt(document: JSONObject) {
        assertThrows(document.toString(), CorruptionException::class.java) {
            runBlocking<Unit> { serializer.readFrom(document.toString().byteInputStream()) }
        }
    }
}
