package com.example.offlinetranslator.settings

import androidx.datastore.core.CorruptionException
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class AppSettingsSerializerTest {
    private val serializer = AppSettingsSerializer()

    @Test
    fun freshDefaultsRoundTripAsSchemaSixWithSystemSelectionsAndNoLegacyFields() = runBlocking<Unit> {
        assertEquals(AppSettingsTestFixtures.freshInstall, serializer.defaultValue)
        val out = ByteArrayOutputStream()
        serializer.writeTo(serializer.defaultValue, out)
        val document = JSONObject(out.toString("UTF-8"))
        assertEquals("system-offline", document.getString("speechOutputBackend"))
        assertEquals("system-offline", document.getString("speechRecognitionBackend"))
        assertEquals("ru", document.getJSONObject("languagePair").getString("source"))
        assertEquals("en", document.getJSONObject("languagePair").getString("target"))
        for (field in listOf("textPair", "conversationLanguages", "asrModelIds", "ttsModelIds")) {
            assertFalse(document.has(field))
        }
        assertEquals(AppSettingsTestFixtures.freshInstall, serializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun roundTripPreservesSharedPairIndependentEnginesAndEveryAdvancedPreference() = runBlocking<Unit> {
        val original = AppSettingsTestFixtures.migrated.copy(
            languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH),
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            speechRecognitionService = "missing.provider/.RecognitionService",
            textEngineId = "other-text",
            conversationEngineId = "other-conversation",
            vadModelId = "other-vad",
            languageIdModelId = "other-lid",
            vadMinSilenceMs = 1200,
            ttsSpeedPercent = 135,
        )
        val out = ByteArrayOutputStream()
        serializer.writeTo(original, out)
        val document = JSONObject(out.toString("UTF-8"))
        assertEquals("piper", document.getString("speechOutputBackend"))
        assertEquals("sherpa", document.getString("speechRecognitionBackend"))
        assertEquals(original, serializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun legacySchemasReplaceDistinctTextPairWithOrderedConversationPairAndPiperPreservingPreferences() = runBlocking<Unit> {
        val expected = AppSettingsTestFixtures.migrated.copy(
            textEngineId = "previous-text",
            conversationEngineId = "previous-conversation",
            vadModelId = "previous-vad",
            languageIdModelId = "previous-lid",
        )
        for (version in 1..3) {
            val legacy = document(version)
                .put("textEngineId", expected.textEngineId)
                .put("conversationEngineId", expected.conversationEngineId)
                .put("vadModelId", expected.vadModelId)
                .put("languageIdModelId", expected.languageIdModelId)
            assertEquals(expected, serializer.readFrom(legacy.toString().byteInputStream()))
            legacy.put("conversationLanguages", JSONArray().put("ru").put("en"))
            assertEquals(expected.copy(languagePair = expected.languagePair.reverse()),
                serializer.readFrom(legacy.toString().byteInputStream()))
        }
    }

    @Test
    fun schemaFourMigrationPreservesOutputAndDefaultsRecognitionToSystem() = runBlocking<Unit> {
        assertEquals(AppSettingsTestFixtures.migrated, serializer.readFrom(document(4).toString().byteInputStream()))
    }

    @Test
    fun oldSchemasDefaultServiceWithoutChangingTheirRecognitionBackend() = runBlocking<Unit> {
        for (version in 1..5) {
            val source = document(version)
            val expected = if (version == 5) {
                source.put("speechRecognitionBackend", "sherpa")
                AppSettingsTestFixtures.migrated.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)
            } else AppSettingsTestFixtures.migrated
            assertEquals(expected, serializer.readFrom(source.toString().byteInputStream()))
        }
    }

    @Test
    fun schemaSixReadsEveryExplicitRecognitionChoiceWithoutChangingHiddenOrOutputPreferences() = runBlocking<Unit> {
        for ((id, backend) in listOf(
            "system-offline" to SpeechRecognitionBackend.SYSTEM_OFFLINE,
            "sherpa" to SpeechRecognitionBackend.SHERPA,
            "whisper" to SpeechRecognitionBackend.WHISPER,
        )) {
            val source = document(6).put("speechRecognitionBackend", id)
                .put("speechRecognitionService", "missing.provider/.RecognitionService")
                .put("languageIdModelId", "saved-lid")
            val expected = AppSettingsTestFixtures.migrated.copy(speechRecognitionBackend = backend,
                speechRecognitionService = "missing.provider/.RecognitionService", languageIdModelId = "saved-lid")
            assertEquals(expected, serializer.readFrom(source.toString().byteInputStream()))
        }
    }

    @Test
    fun removedOrUnknownRecognitionBackendFallsBackWithoutChangingOtherSavedFields() = runBlocking<Unit> {
        val expected = AppSettingsTestFixtures.migrated.copy(
            languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
            speechRecognitionService = "missing.provider/.RecognitionService",
            textEngineId = "tencent",
            conversationEngineId = "tencent",
            vadModelId = "saved-vad",
            languageIdModelId = "saved-lid",
            vadMinSilenceMs = 1200,
            ttsSpeedPercent = 115,
        )
        val output = ByteArrayOutputStream()
        serializer.writeTo(expected, output)
        for (version in 5..6) {
            for (backendId in listOf("nemo", "removed-backend")) {
                val source = JSONObject(output.toString("UTF-8"))
                    .put("schemaVersion", version)
                    .put("speechRecognitionBackend", backendId)
                if (version == 5) source.remove("speechRecognitionService")
                val migrated = if (version == 5) expected.copy(speechRecognitionService = null) else expected
                val loaded = serializer.readFrom(source.toString().byteInputStream())
                assertEquals(migrated, loaded)
                val rewritten = ByteArrayOutputStream()
                serializer.writeTo(loaded, rewritten)
                assertEquals("system-offline", JSONObject(rewritten.toString("UTF-8")).getString("speechRecognitionBackend"))
                assertEquals(migrated, serializer.readFrom(rewritten.toByteArray().inputStream()))
            }
        }
    }

    @Test
    fun schemaSixRequiresNullOrBoundedComponentSyntaxButNotAnInstalledPackage() = runBlocking<Unit> {
        assertCorrupt(document(6).apply { remove("speechRecognitionService") })
        for (invalid in listOf("", "on-device", "provider/", "/Service", "provider//Service",
            "provider/.Bad..Service", "provider/../../Service", "provider with spaces/.Service",
            "provider/.${"S".repeat(512)}", 12, true, JSONArray(), JSONObject())) {
            assertCorrupt(document(6).put("speechRecognitionService", invalid))
        }
        for (component in listOf("missing.provider/.RecognitionService", "missing.provider/missing.provider.RecognitionService",
            "p/.${"S".repeat(509)}")) {
            val selected = AppSettingsTestFixtures.migrated.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
                speechRecognitionService = component)
            val output = ByteArrayOutputStream()
            serializer.writeTo(selected, output)
            assertEquals(selected, serializer.readFrom(output.toByteArray().inputStream()))
        }
        assertEquals(AppSettingsTestFixtures.migrated, serializer.readFrom(document(6).toString().byteInputStream()))
        assertCorrupt(document(5).put("speechRecognitionService", JSONObject.NULL))
    }

    @Test
    fun invalidServiceCannotBeWrittenAsAnUnreadablePreference() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { serializer.writeTo(AppSettings(speechRecognitionService = "not-a-component"), output) }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun onlySchemaOneToleratesUnknownTopLevelAndPairFields() = runBlocking<Unit> {
        val legacy = document(1).put("unknown", true).apply {
            getJSONObject("textPair").put("unknown", true)
        }
        assertEquals(AppSettingsTestFixtures.migrated, serializer.readFrom(legacy.toString().byteInputStream()))
        for (version in 2..6) {
            val pairField = if (version >= 4) "languagePair" else "textPair"
            assertCorrupt(document(version).put("unknown", true))
            assertCorrupt(document(version).apply { getJSONObject(pairField).put("unknown", true) })
            assertCorrupt(document(version).put("ttsModelIds", JSONObject()))
        }
        assertCorrupt(document(3).put("asrModelIds", JSONObject()))
        for (field in listOf("textPair", "conversationLanguages", "asrModelIds", "ttsModelIds", "speechRecognitionBackend")) {
            assertCorrupt(document(4).put(field, JSONObject()))
        }
    }

    @Test
    fun schemaFourRequiresExactKnownBackendIds() {
        assertCorrupt(document(4).apply { remove("speechOutputBackend") })
        for (invalid in listOf("system", "SYSTEM_OFFLINE", "PIPER", "unknown", "", 12, true, JSONObject.NULL)) {
            assertCorrupt(document(4).put("speechOutputBackend", invalid))
        }
    }

    @Test
    fun recognitionSchemasRequireWellFormedRecognitionBackendIds() {
        for (version in 5..6) {
            assertCorrupt(document(version).apply { remove("speechRecognitionBackend") })
            for (invalid in listOf("SYSTEM_OFFLINE", "sherpa ", "", "../unsafe", "a".repeat(129), 12, true, JSONObject.NULL)) {
                assertCorrupt(document(version).put("speechRecognitionBackend", invalid))
            }
        }
        assertCorrupt(document(4).put("speechRecognitionBackend", "sherpa"))
    }

    @Test
    fun legacyMapsRemainRequiredAndValidateShapeLanguagesIdentifiersAndEntryBounds() = runBlocking<Unit> {
        for ((version, field) in listOf(1 to "asrModelIds", 1 to "ttsModelIds", 2 to "asrModelIds")) {
            assertCorrupt(document(version).apply { remove(field) })
            val invalidMaps = listOf(
                JSONObject.NULL, "invalid", JSONArray(),
                JSONObject().put("ru", JSONObject.NULL),
                JSONObject().put("en", 12),
                JSONObject().put("en", true),
                JSONObject().put("en", JSONObject()),
                JSONObject().put("ru", "../unsafe"),
                JSONObject().put("ru", ""),
                JSONObject().put("ru", "a".repeat(129)),
                JSONObject().put("invalid_language", "removed-model"),
                JSONObject().put("ru", "removed-model").put("RU", "other-model"),
                modelMap(17),
            )
            invalidMaps.forEach { assertCorrupt(document(version).put(field, it)) }
            for (valid in listOf(JSONObject(), modelMap(16), JSONObject().put("ru", "a".repeat(128)))) {
                val loaded = serializer.readFrom(document(version).put(field, valid).toString().byteInputStream())
                assertEquals(AppSettingsTestFixtures.migrated, loaded)
            }
        }
    }

    @Test
    fun legacyMigrationRejectsInvalidDiscardedTextPairAndRequiresExactlyTwoDistinctConversationLanguages() {
        val invalidOrders = listOf(
            JSONObject.NULL, "en,ru", JSONObject(),
            JSONArray(), JSONArray().put("en"),
            JSONArray().put("en").put("ru").put("de"),
            JSONArray(List(17) { "ru" }),
            JSONArray().put("ru").put("ru"),
            JSONArray().put("ru").put("RU"),
            JSONArray().put(12).put("ru"),
            JSONArray().put("en").put(JSONObject.NULL),
            JSONArray().put("en").put("invalid_language"),
        )
        for (version in 1..3) {
            assertCorrupt(document(version).apply { remove("conversationLanguages") })
            invalidOrders.forEach { assertCorrupt(document(version).put("conversationLanguages", it)) }
            assertInvalidPair(version, "textPair")
        }
    }

    @Test
    fun currentSchemasRequireValidDistinctSharedPair() {
        assertInvalidPair(4, "languagePair")
        assertInvalidPair(5, "languagePair")
        assertInvalidPair(6, "languagePair")
    }

    @Test
    fun allSchemasRejectInvalidTypesIdentifiersAndIntegers() {
        for (version in 1..6) {
            for (field in listOf("textEngineId", "conversationEngineId", "vadModelId", "languageIdModelId")) {
                assertCorrupt(document(version).apply { remove(field) })
                for (invalid in listOf("../unsafe", "", "a".repeat(129), 12, JSONObject.NULL)) {
                    assertCorrupt(document(version).put(field, invalid))
                }
            }
            for (field in listOf("schemaVersion", "vadMinSilenceMs", "ttsSpeedPercent")) {
                assertCorrupt(document(version).apply { remove(field) })
                for (invalid in listOf(100.5, "100", true, JSONObject.NULL, Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1)) {
                    assertCorrupt(document(version).put(field, invalid))
                }
            }
        }
    }

    @Test
    fun interruptedReadPreservesTheIOExceptionInsteadOfReportingCorruption() = runBlocking<Unit> {
        val out = ByteArrayOutputStream()
        serializer.writeTo(AppSettings(ttsSpeedPercent = 110), out)
        val failure = IOException("fixture read interrupted")
        val input = object : ByteArrayInputStream(out.toByteArray()) {
            private var firstRead = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!firstRead) throw failure
                firstRead = false
                return super.read(buffer, offset, minOf(length, 24))
            }
        }
        assertSame(failure, runCatching { serializer.readFrom(input) }.exceptionOrNull())
    }

    @Test
    fun failedWritePreservesTheIOException() = runBlocking<Unit> {
        val failure = IOException("fixture write interrupted")
        val output = object : OutputStream() {
            override fun write(value: Int) { throw failure }
        }
        assertSame(failure, runCatching { serializer.writeTo(AppSettingsTestFixtures.migrated, output) }.exceptionOrNull())
    }

    @Test
    fun malformedJsonThrowsCorruptionException() {
        assertThrows(CorruptionException::class.java) {
            runBlocking<Unit> { serializer.readFrom("{not valid json".byteInputStream()) }
        }
    }

    @Test
    fun unsupportedSchemaVersionsThrowCorruptionException() {
        for (version in listOf(0, 7)) assertCorrupt(document(6).put("schemaVersion", version))
    }

    @Test
    fun documentByteLimitIsInclusiveForCurrentAndLegacyDocuments() = runBlocking<Unit> {
        for (version in listOf(1, 6)) {
            val bytes = document(version).toString().toByteArray(Charsets.UTF_8)
            val atLimit = bytes + ByteArray(AppSettingsSerializer.MAX_BYTES - bytes.size) { ' '.code.toByte() }
            assertEquals(AppSettingsTestFixtures.migrated, serializer.readFrom(ByteArrayInputStream(atLimit)))
            assertThrows(CorruptionException::class.java) {
                runBlocking<Unit> { serializer.readFrom(ByteArrayInputStream(atLimit + ' '.code.toByte())) }
            }
        }
    }

    @Test
    fun oversizedOutputIsRejectedBeforeAnyBytesAreWritten() {
        val output = ByteArrayOutputStream()
        val oversized = AppSettings(textEngineId = "a".repeat(AppSettingsSerializer.MAX_BYTES))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking<Unit> { serializer.writeTo(oversized, output) }
        }
        assertEquals(0, output.size())
    }

    private fun assertInvalidPair(version: Int, field: String) {
        assertCorrupt(document(version).apply { remove(field) })
        for (invalid in listOf(JSONObject.NULL, "en-de", JSONArray())) {
            assertCorrupt(document(version).put(field, invalid))
        }
        for (member in listOf("source", "target")) {
            assertCorrupt(document(version).apply { getJSONObject(field).remove(member) })
            for (invalid in listOf(12, true, JSONObject.NULL, "invalid_language")) {
                assertCorrupt(document(version).apply { getJSONObject(field).put(member, invalid) })
            }
        }
        assertCorrupt(document(version).put(field, JSONObject().put("source", "ru").put("target", "RU")))
    }

    private fun assertCorrupt(document: JSONObject) {
        assertThrows(document.toString(), CorruptionException::class.java) {
            runBlocking<Unit> { serializer.readFrom(document.toString().byteInputStream()) }
        }
    }

    private fun document(version: Int): JSONObject = when (version) {
        in 1..3 -> AppSettingsTestFixtures.legacyDocument(version)
        4 -> AppSettingsTestFixtures.schemaFourDocument()
        5 -> AppSettingsTestFixtures.schemaFourDocument().put("schemaVersion", 5)
            .put("speechRecognitionBackend", "system-offline")
        6 -> document(5).put("schemaVersion", 6).put("speechRecognitionService", JSONObject.NULL)
        else -> error("Unsupported test schema")
    }

    private fun modelMap(size: Int): JSONObject = JSONObject().apply {
        repeat(size) { index -> put("a${'a' + index}", "removed-model-$index") }
    }
}
