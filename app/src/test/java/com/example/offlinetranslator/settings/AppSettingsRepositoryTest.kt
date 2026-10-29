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

    @Test fun liveOutputPatchPreservesLatestPairSetupAndPreferencesAcrossReopening() = runBlocking {
        val file = File(temp.root, "output-settings.json")
        val repository = AppSettingsRepository(file)
        val latest = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            translationSetupComplete = true, ttsSpeedPercent = 115)
        try {
            repository.save(latest)
            assertTrue(repository.updateSpeechOutput(false) { true })
            assertEquals(latest.copy(speechOutputEnabled = false), repository.current())
            assertFalse(repository.updateSpeechOutput(true) { false })
            assertFalse(repository.current().speechOutputEnabled)
        } finally { repository.closeAndJoin() }
        val reopened = AppSettingsRepository(file)
        try {
            assertEquals(latest.copy(speechOutputEnabled = false), reopened.current())
            assertFalse(reopened.corruptionNotice.value)
            assertTrue(reopened.updateSpeechOutput(true) { true })
        } finally { reopened.closeAndJoin() }
        val enabled = AppSettingsRepository(file)
        try { assertEquals(latest, enabled.current()) } finally { enabled.closeAndJoin() }
    }


    @Test
    fun missingFileYieldsDefaults() = runBlocking {
        val file = File(temp.root, "settings.json")
        val repo = AppSettingsRepository(file)
        try {
            val settings = repo.current()
            assertFalse(settings.translationSetupComplete)
            assertFalse("Loading fresh defaults must not create a settings file", file.exists())
            assertFalse(repo.corruptionNotice.value)
        } finally {
            repo.closeAndJoin()
        }
    }

    @Test
    fun explicitFirstChoicePersistsEngineAndCompletionTogetherAcrossRestart() = runBlocking {
        for (engineId in listOf("mlkit", "tencent")) {
            val file = File(temp.root, "$engineId-settings.json")
            val first = AppSettingsRepository(file)
            val selected: AppSettings
            try {
                val fresh = first.current()
                assertFalse(fresh.translationSetupComplete)
                selected = fresh.copy(translationEngineId = engineId, translationSetupComplete = true)
                first.save(selected)
            } finally { first.closeAndJoin() }
            val reopened = AppSettingsRepository(file)
            try {
                assertEquals(selected, reopened.current())
                assertEquals(engineId, reopened.current().textConfig().engineId)
                assertEquals(engineId, reopened.current().conversationConfig().engineId)
                assertFalse(reopened.corruptionNotice.value)
            } finally { reopened.closeAndJoin() }
        }
    }

    @Test
    fun explicitWhisperSurvivesRestartWithHiddenPreferencesAndCanReturnToEverySavedBackend() = runBlocking {
        val file = File(temp.root, "whisper-settings.json")
        val previous = AppSettingsTestFixtures.selected.copy(
            speechRecognitionService = "missing.provider/.RecognitionService",
            vadMinSilenceMs = 1200,
            ttsSpeedPercent = 115,
        )
        for (backend in listOf(SpeechRecognitionBackend.SYSTEM_OFFLINE)) {
            val saved = previous.copy(speechRecognitionBackend = backend)
            val selected = saved.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
            val first = AppSettingsRepository(file)
            try {
                first.save(saved)
                assertEquals(selected, first.save(selected))
            } finally { first.closeAndJoin() }
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
        val changed = initial.copy(translationEngineId = "mlkit", translationSetupComplete = true)
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
    val freshInstall = AppSettings()
    val selected = AppSettings(
        languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
        speechOutputBackend = SpeechOutputBackend.PIPER,
        speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
        translationEngineId = "mlkit",
        translationSetupComplete = true,
        vadMinSilenceMs = 900,
        ttsSpeedPercent = 110,
    )

    fun document(): JSONObject = JSONObject(
        """{"schemaVersion":7,"languagePair":{"source":"en","target":"ru"},"speechOutputBackend":"piper","speechRecognitionBackend":"system-offline","speechRecognitionService":null,"translationEngineId":"mlkit","translationSetupComplete":true,"vadModelId":"vad-silero-6.2.2","vadMinSilenceMs":900,"ttsSpeedPercent":110}""",
    )
}
