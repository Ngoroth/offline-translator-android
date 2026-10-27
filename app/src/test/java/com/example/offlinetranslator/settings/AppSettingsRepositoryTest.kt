package com.example.offlinetranslator.settings

import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import androidx.datastore.core.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AppSettingsRepositoryTest {
    @get:Rule val temp = TemporaryFolder()


    @Test
    fun missingFileYieldsDefaults() = runBlocking {
        val file = File(temp.root, "settings.json")
        val repo = AppSettingsRepository(file)
        try {
            val settings = repo.current()
            assertEquals(AppSettingsTestFixtures.freshInstall, settings)
            assertFalse("Loading fresh defaults must not create a settings file", file.exists())
            assertFalse(repo.corruptionNotice.value)
        } finally {
            repo.closeAndJoin()
        }
    }

    @Test
    fun legacyMigrationIsLazyNoOpPreservesBytesAndChangedSchemaSixSettingsSurviveRestart() = runBlocking {
        for (version in 1..5) {
            val file = File(temp.root, "schema-$version.json")
            val source = when (version) {
                5 -> AppSettingsTestFixtures.schemaFourDocument().put("schemaVersion", 5)
                    .put("speechRecognitionBackend", "sherpa")
                4 -> AppSettingsTestFixtures.schemaFourDocument()
                else -> AppSettingsTestFixtures.legacyDocument(version)
            }
            val migrated = AppSettingsTestFixtures.migrated.copy(speechRecognitionBackend =
                if (version == 5) SpeechRecognitionBackend.SHERPA else SpeechRecognitionBackend.SYSTEM_OFFLINE)
            val originalBytes = (source.toString(2) + "\n").toByteArray(Charsets.UTF_8)
            file.writeBytes(originalBytes)
            val firstRepo = AppSettingsRepository(file)
            try {
                assertEquals(migrated, firstRepo.current())
                assertArrayEquals(originalBytes, file.readBytes())
                assertEquals(migrated, firstRepo.save(firstRepo.current()))
                assertArrayEquals("A no-op save must not rewrite a legacy file", originalBytes, file.readBytes())
                assertFalse(firstRepo.corruptionNotice.value)
            } finally {
                firstRepo.closeAndJoin()
            }

            val updated = migrated.copy(
                languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH),
                speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
                textEngineId = "tencent",
                ttsSpeedPercent = 115,
                speechRecognitionService = "missing.provider/.RecognitionService",
            )
            val secondRepo = AppSettingsRepository(file)
            try {
                assertEquals(migrated, secondRepo.current())
                assertArrayEquals(originalBytes, file.readBytes())
                assertEquals(updated, secondRepo.save(updated))
                assertEquals(updated, secondRepo.current())
                val document = JSONObject(file.readText(Charsets.UTF_8))
                assertEquals("system-offline", document.getString("speechOutputBackend"))
                assertEquals(migrated.speechRecognitionBackend.persistedId, document.getString("speechRecognitionBackend"))
                for (field in listOf("textPair", "conversationLanguages", "asrModelIds", "ttsModelIds")) {
                    assertFalse(document.has(field))
                }
            } finally {
                secondRepo.closeAndJoin()
            }
            val rewrittenBytes = file.readBytes()
            val reopened = AppSettingsRepository(file)
            try {
                assertEquals(updated, reopened.current())
                assertFalse(reopened.corruptionNotice.value)
                assertEquals(updated, reopened.save(reopened.current()))
                assertArrayEquals(rewrittenBytes, file.readBytes())
            } finally {
                reopened.closeAndJoin()
            }
        }
    }

    @Test
    fun selectedSherpaAndOutputChoiceSurviveRepositoryRestartIndependently() = runBlocking {
        val file = File(temp.root, "settings.json")
        val selected = AppSettingsTestFixtures.migrated.copy(
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            speechOutputBackend = SpeechOutputBackend.PIPER,
            speechRecognitionService = "missing.provider/.RecognitionService",
        )
        val first = AppSettingsRepository(file)
        try {
            assertEquals(selected, first.save(selected))
        } finally {
            first.closeAndJoin()
        }
        val document = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals("sherpa", document.getString("speechRecognitionBackend"))
        assertEquals("piper", document.getString("speechOutputBackend"))

        val reopened = AppSettingsRepository(file)
        try {
            assertEquals(selected, reopened.current())
        } finally {
            reopened.closeAndJoin()
        }
    }

    @Test
    fun explicitWhisperSurvivesRestartWithHiddenPreferencesAndCanReturnToEverySavedBackend() = runBlocking {
        val file = File(temp.root, "whisper-settings.json")
        val previous = AppSettingsTestFixtures.migrated.copy(
            speechRecognitionService = "missing.provider/.RecognitionService",
            languageIdModelId = "saved-lid",
            vadMinSilenceMs = 1200,
            ttsSpeedPercent = 115,
        )
        for (backend in listOf(SpeechRecognitionBackend.SYSTEM_OFFLINE, SpeechRecognitionBackend.SHERPA)) {
            val saved = previous.copy(speechRecognitionBackend = backend)
            val selected = saved.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
            val first = AppSettingsRepository(file)
            try {
                first.save(saved)
                assertEquals(selected, first.save(selected))
            } finally { first.closeAndJoin() }
            val document = JSONObject(file.readText(Charsets.UTF_8))
            assertEquals(6, document.getInt("schemaVersion"))
            assertEquals("whisper", document.getString("speechRecognitionBackend"))
            val reopened = AppSettingsRepository(file)
            try {
                assertEquals(selected, reopened.current())
                assertFalse(reopened.corruptionNotice.value)
                assertEquals(saved, reopened.save(reopened.current().copy(speechRecognitionBackend = backend)))
            } finally { reopened.closeAndJoin() }
            val restored = AppSettingsRepository(file)
            try { assertEquals(saved, restored.current()) } finally { restored.closeAndJoin() }
        }
    }

    @Test
    fun corruptedFileTriggersNoticeAndFallsBackToDefaults() = runBlocking {
        val file = File(temp.root, "settings.json")
        file.parentFile?.mkdirs()
        file.writeText("{ corrupted json content [", Charsets.UTF_8)

        val repo = AppSettingsRepository(file)
        try {
            val settings = repo.current()
            assertEquals(AppSettingsTestFixtures.freshInstall, settings)
            assertTrue(repo.corruptionNotice.first { it })

            // Saving valid settings clears the corruption notice
            repo.save(AppSettings(vadMinSilenceMs = 1200))
            assertFalse(repo.corruptionNotice.value)
            assertEquals(1200, repo.current().vadMinSilenceMs)
        } finally {
            repo.closeAndJoin()
        }
    }

    @Test
    fun streamIoExceptionPreservesDocumentAndEnablesRetryWithoutReset() = runBlocking {
        val file = File(temp.root, "settings.json")
        val initial = AppSettings(vadMinSilenceMs = 1100, ttsSpeedPercent = 115)
        val baseRepo = AppSettingsRepository(file)
        try {
            baseRepo.save(initial)
        } finally {
            baseRepo.closeAndJoin()
        }
        val savedBytes = file.readBytes()

        val shouldFail = AtomicBoolean(true)
        val bytesReadBeforeFailure = AtomicInteger()
        val delegate = AppSettingsSerializer()
        val faultingSerializer = object : Serializer<AppSettings> {
            override val defaultValue: AppSettings get() = delegate.defaultValue
            override suspend fun readFrom(input: InputStream): AppSettings {
                if (!shouldFail.get()) return delegate.readFrom(input)
                return delegate.readFrom(object : FilterInputStream(input) {
                    private var remainingBeforeFailure = 16

                    override fun read(): Int {
                        if (remainingBeforeFailure == 0) throw IOException("temporary storage read failure")
                        return super.read().also { value ->
                            if (value >= 0) {
                                remainingBeforeFailure--
                                bytesReadBeforeFailure.incrementAndGet()
                            }
                        }
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (length == 0) return 0
                        if (remainingBeforeFailure == 0) throw IOException("temporary storage read failure")
                        val count = super.read(buffer, offset, minOf(length, remainingBeforeFailure))
                        if (count > 0) {
                            remainingBeforeFailure -= count
                            bytesReadBeforeFailure.addAndGet(count)
                        }
                        return count
                    }
                })
            }
            override suspend fun writeTo(t: AppSettings, output: OutputStream) = delegate.writeTo(t, output)
        }

        val failingRepo = AppSettingsRepository(file, Dispatchers.IO, faultingSerializer)
        try {
            val state = failingRepo.state.first { !it.loading }
            assertTrue("The real serializer must read a prefix of the stored document before I/O fails",
                bytesReadBeforeFailure.get() in 1 until savedBytes.size)
            assertTrue("Load error must be flagged", state.loadError)
            assertFalse("Corruption notice must not trigger for stream I/O errors", failingRepo.corruptionNotice.value)
            assertArrayEquals(savedBytes, file.readBytes())

            // current and save reject access when unavailable
            assertThrows(IOException::class.java) { runBlocking { failingRepo.current() } }
            assertThrows(IOException::class.java) { runBlocking { failingRepo.save(AppSettings()) } }
            assertArrayEquals(savedBytes, file.readBytes())

            // Recover read and retry
            shouldFail.set(false)
            failingRepo.retry()
            val recovered = failingRepo.state.first { !it.loading && !it.loadError }
            assertEquals(initial, recovered.settings)
            assertEquals(initial, failingRepo.current())
            assertFalse(failingRepo.corruptionNotice.value)
            assertArrayEquals(savedBytes, file.readBytes())
        } finally {
            failingRepo.closeAndJoin()
        }
    }

    @Test
    fun failedPartialWritePreservesCommittedSettingsAndBytesUntilExplicitSuccessfulSave() = runBlocking {
        val file = File(temp.root, "settings.json")
        val initial = AppSettingsTestFixtures.freshInstall.copy(vadMinSilenceMs = 1100, ttsSpeedPercent = 115)
        val changed = AppSettingsTestFixtures.migrated
        val shouldFail = AtomicBoolean(false)
        val delegate = AppSettingsSerializer()
        val faultingSerializer = object : Serializer<AppSettings> {
            override val defaultValue: AppSettings get() = delegate.defaultValue
            override suspend fun readFrom(input: InputStream): AppSettings = delegate.readFrom(input)
            override suspend fun writeTo(t: AppSettings, output: OutputStream) {
                if (shouldFail.get()) {
                    output.write('{'.code)
                    throw IOException("temporary storage write failure")
                }
                delegate.writeTo(t, output)
            }
        }
        val repo = AppSettingsRepository(file, Dispatchers.IO, faultingSerializer)
        try {
            repo.save(initial)
            val savedBytes = file.readBytes()
            shouldFail.set(true)
            assertThrows(IOException::class.java) { runBlocking { repo.save(changed) } }
            assertEquals(initial, repo.current())
            assertEquals(initial, repo.state.value.settings)
            assertFalse(repo.corruptionNotice.value)
            assertArrayEquals(savedBytes, file.readBytes())

            shouldFail.set(false)
            assertEquals(changed, repo.save(changed))
        } finally {
            repo.closeAndJoin()
        }
        val reopened = AppSettingsRepository(file)
        try {
            assertEquals(changed, reopened.current())
            assertFalse(reopened.corruptionNotice.value)
        } finally {
            reopened.closeAndJoin()
        }
    }
}

internal object AppSettingsTestFixtures {
    val freshInstall = AppSettings(
        languagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
        speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
        speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
        textEngineId = "tencent",
        conversationEngineId = "tencent",
    )
    val migrated = AppSettings(
        languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
        speechOutputBackend = SpeechOutputBackend.PIPER,
        speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
        textEngineId = "mlkit",
        conversationEngineId = "mlkit",
        vadMinSilenceMs = 900,
        ttsSpeedPercent = 110,
    )

    fun schemaFourDocument(): JSONObject = JSONObject(
        """{"schemaVersion":4,"languagePair":{"source":"en","target":"ru"},"speechOutputBackend":"piper","textEngineId":"mlkit","conversationEngineId":"mlkit","vadModelId":"vad-silero-6.2.2","languageIdModelId":"lid-whisper-tiny","vadMinSilenceMs":900,"ttsSpeedPercent":110}""",
    )

    fun legacyDocument(version: Int): JSONObject {
        require(version in 1..3)
        return JSONObject(
            """{"schemaVersion":$version,"textPair":{"source":"en","target":"de"},"conversationLanguages":["en","ru"],"textEngineId":"mlkit","conversationEngineId":"mlkit","vadModelId":"vad-silero-6.2.2","languageIdModelId":"lid-whisper-tiny","vadMinSilenceMs":900,"ttsSpeedPercent":110}""",
        ).apply {
            if (version <= 2) put("asrModelIds", JSONObject().put("ru", "removed-ru-asr").put("en", "asr-en-whisper-tiny"))
            if (version == 1) put("ttsModelIds", JSONObject().put("ru", "removed-ru-voice").put("en", "removed-en-voice"))
        }
    }
}
