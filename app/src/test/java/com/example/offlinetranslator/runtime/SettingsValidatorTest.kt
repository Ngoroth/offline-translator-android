package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.sherpa.SherpaSpeechModelCatalog
import com.example.offlinetranslator.sherpa.WhisperConversationRecognitionEngine
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsValidatorTest {
    private fun catalogDocument() = JSONObject(File("../engine-sherpa/src/main/assets/speech-models.json").readText())
    private fun validator(document: JSONObject = catalogDocument()) = SettingsValidator(
        SherpaSpeechModelCatalog.parse(document.toString().byteInputStream()),
        setOf("mlkit"),
        { _, pair -> MlKitTranslationEngine.capability(pair) },
    )

    @Test fun systemPairIsEligibleButNativeRecognitionAndOutputKeepTheirRealCoverage() {
        val settings = AppSettings(languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH),
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        val validation = validator()
        assertTrue(validation.problems(settings).isEmpty())
        assertTrue(validation.textProblems(settings).isEmpty())
        assertTrue(validation.conversationProblems(settings).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(settings))
        for (native in listOf(settings.copy(speechOutputBackend = SpeechOutputBackend.PIPER))) {
            assertTrue(validation.textProblems(native).isEmpty())
            assertTrue(validation.conversationProblems(native).isNotEmpty())
            assertEquals(setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), validation.conversationLanguageCoverage(native))
        }
    }

    @Test fun systemCoverageDoesNotDependOnPiperMetadataButPiperStillRequiresItsModels() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in models.length() - 1 downTo 0) {
            if (models.getJSONObject(index).getString("role") == SpeechModelRole.TTS.name) models.remove(index)
        }
        val validation = validator(document)
        val system = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        assertTrue(validation.conversationProblems(system).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(system))
        val piper = system.copy(speechOutputBackend = SpeechOutputBackend.PIPER)
        assertTrue(validation.problems(piper).isEmpty())
        assertTrue(ConfigurationProblem.UNKNOWN_MODEL in validation.conversationProblems(piper))
    }

    @Test fun wrongModelRoleBlocksAdvancedSettingsWithoutCorruptingTextPair() {
        val settings = AppSettings(vadModelId = "asr-ru-gigaam-v1",
            translationEngineId = "mlkit")
        val validation = validator()
        assertTrue(validation.textProblems(settings).isEmpty())
        assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in validation.problems(settings))
        assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in validation.conversationProblems(settings))
    }


    @Test fun systemRecognitionIgnoresAbsentNativeAsrDefinitions() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in models.length() - 1 downTo 0) {
            if (models.getJSONObject(index).getString("role") == "ASR") models.remove(index)
        }
        val validation = validator(document)
        val system = AppSettings(languagePair = LanguagePair(LanguageCatalog.CHINESE, LanguageCatalog.JAPANESE),
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        assertTrue(validation.conversationProblems(system).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(system))
        assertTrue(validation.conversationProblems(system.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)).isNotEmpty())
    }

    @Test fun whisperDutchPairsUseOnlyTheSharedRecognitionDefinitionAndKeepOutputIndependent() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in models.length() - 1 downTo 0) {
            val model = models.getJSONObject(index)
            if (model.getString("role") == "ASR" &&
                model.getString("id") != WhisperConversationRecognitionEngine.MODEL_ID) models.remove(index)
        }
        val validation = validator(document)
        for (pair in listOf(
            LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.RUSSIAN),
            LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
        ).flatMap { listOf(it, it.reverse()) }) {
            val settings = AppSettings(languagePair = pair,
                translationEngineId = "mlkit",
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
                speechRecognitionService = "missing.provider/.RecognitionService")
            assertEquals(emptyList<ConfigurationProblem>(), validation.problems(settings))
            assertEquals(emptyList<ConfigurationProblem>(), validation.conversationProblems(settings))
        }
        val dutch = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(dutch))
        val piper = dutch.copy(speechOutputBackend = SpeechOutputBackend.PIPER)
        assertTrue(ConfigurationProblem.UNSUPPORTED_VOICE in validation.conversationProblems(piper))
        assertEquals(setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), validation.conversationLanguageCoverage(piper))
    }

    @Test fun missingOrWrongRoleWhisperMetadataBlocksRecognitionButNotIndependentText() {
        val settings = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
        for (wrongRole in listOf(false, true)) {
            val document = catalogDocument()
            val models = document.getJSONArray("models")
            for (index in models.length() - 1 downTo 0) {
                if (models.getJSONObject(index).getString("id") == WhisperConversationRecognitionEngine.MODEL_ID) {
                    models.remove(index)
                }
            }
            if (wrongRole) {
                for (index in 0 until models.length()) {
                    val model = models.getJSONObject(index)
                    if (model.getString("id") == "tts-ru-piper-irina-medium") {
                        model.put("id", WhisperConversationRecognitionEngine.MODEL_ID)
                    }
                }
            }
            val validation = validator(document)
            val expected = if (wrongRole) ConfigurationProblem.INCOMPATIBLE_MODEL else ConfigurationProblem.UNKNOWN_MODEL
            assertEquals(emptyList<ConfigurationProblem>(), validation.textProblems(settings))
            assertTrue(expected in validation.problems(settings))
            assertTrue(expected in validation.conversationProblems(settings))
            assertEquals(emptySet<LanguageId>(), validation.conversationLanguageCoverage(settings))
            assertEquals(emptyList<ConfigurationProblem>(),
                validation.conversationProblems(settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)))
        }
    }

    @Test fun incompleteWhisperPairBlocksConversationWithoutBlockingTextOrSavingSettings() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            if (model.getString("id") == WhisperConversationRecognitionEngine.MODEL_ID) {
                val languages = model.getJSONArray("languages")
                for (language in languages.length() - 1 downTo 0) {
                    if (languages.getString(language) == LanguageCatalog.DUTCH.value) languages.remove(language)
                }
            }
        }
        val validation = validator(document)
        val settings = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            translationEngineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
        assertEquals(emptyList<ConfigurationProblem>(), validation.textProblems(settings))
        assertEquals(emptyList<ConfigurationProblem>(), validation.problems(settings))
        for (pair in listOf(settings.languagePair, settings.languagePair.reverse())) {
            assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in
                validation.conversationProblems(settings.copy(languagePair = pair)))
        }
        assertTrue(LanguageCatalog.ENGLISH in validation.conversationLanguageCoverage(settings))
        assertFalse(LanguageCatalog.DUTCH in validation.conversationLanguageCoverage(settings))
    }
}
