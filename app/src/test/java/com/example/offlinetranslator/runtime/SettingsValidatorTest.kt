package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.sherpa.SpeechModelCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsValidatorTest {
    private fun catalogDocument() = JSONObject(File("../engine-sherpa/src/main/assets/speech-models.json").readText())
    private fun validator(document: JSONObject = catalogDocument()) = SettingsValidator(
        SpeechModelCatalog.parse(document.toString().byteInputStream()),
        setOf("mlkit"),
        { _, pair -> MlKitTranslationEngine.capability(pair) },
    )

    @Test fun systemPairIsEligibleButNativeRecognitionAndOutputKeepTheirRealCoverage() {
        val settings = AppSettings(languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH))
        val validation = validator()
        assertTrue(validation.problems(settings).isEmpty())
        assertTrue(validation.textProblems(settings).isEmpty())
        assertTrue(validation.conversationProblems(settings).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(settings))
        for (native in listOf(settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA),
            settings.copy(speechOutputBackend = SpeechOutputBackend.PIPER))) {
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
        val system = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)
        assertTrue(validation.conversationProblems(system).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(system))
        val piper = system.copy(speechOutputBackend = SpeechOutputBackend.PIPER)
        assertTrue(validation.problems(piper).isEmpty())
        assertTrue(ConfigurationProblem.UNKNOWN_MODEL in validation.conversationProblems(piper))
    }

    @Test fun wrongModelRoleBlocksAdvancedSettingsWithoutCorruptingTextPair() {
        val settings = AppSettings(languageIdModelId = "asr-ru-gigaam-v1", speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)
        val validation = validator()
        assertTrue(validation.textProblems(settings).isEmpty())
        assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in validation.problems(settings))
        assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in validation.conversationProblems(settings))
        assertTrue(validation.conversationLanguageCoverage(settings).isEmpty())
    }

    @Test fun selectedLanguageIdMustCoverBothParticipantsEvenWhenTextIsValid() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in 0 until models.length()) {
            val model = models.getJSONObject(index)
            if (model.getString("id") == "lid-whisper-tiny") {
                model.put("languages", org.json.JSONArray(listOf("ru", "de")))
            }
        }
        val validation = validator(document)
        val settings = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE, speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)
        assertTrue(validation.problems(settings).isEmpty())
        assertTrue(validation.textProblems(settings).isEmpty())
        assertTrue(ConfigurationProblem.INCOMPATIBLE_MODEL in validation.conversationProblems(settings))
        assertEquals(setOf(LanguageCatalog.RUSSIAN), validation.conversationLanguageCoverage(settings))
    }

    @Test fun systemRecognitionIgnoresAbsentNativeAsrAndIdentifierDefinitions() {
        val document = catalogDocument()
        val models = document.getJSONArray("models")
        for (index in models.length() - 1 downTo 0) {
            if (models.getJSONObject(index).getString("role") in listOf("ASR", "LANGUAGE_ID")) models.remove(index)
        }
        val validation = validator(document)
        val system = AppSettings(languagePair = LanguagePair(LanguageCatalog.CHINESE, LanguageCatalog.JAPANESE))
        assertTrue(validation.conversationProblems(system).isEmpty())
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), validation.conversationLanguageCoverage(system))
        assertTrue(validation.conversationProblems(system.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)).isNotEmpty())
    }
}
