package com.example.offlinetranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigurationTest {
    @Test fun canonicalLanguageIdentityAndSameLanguagePairRejection() {
        assertEquals(LanguageCatalog.GERMAN, LanguageId("DE"))
        assertEquals("en-us", LanguageId("EN-US").value)
        assertThrows(IllegalArgumentException::class.java) { LanguageId("../en") }
        assertThrows(IllegalArgumentException::class.java) { LanguagePair(LanguageId("en"), LanguageId("EN")) }
    }

    @Test fun supportedExecutionDoesNotClaimVerifiedDirectness() {
        TranslationCapability.Direct.requireSupported()
        TranslationCapability.Pivot(LanguageCatalog.ENGLISH).requireSupported()
        TranslationCapability.Unknown.requireSupported()
        val error = assertThrows(InvalidConfigurationException::class.java) {
            TranslationCapability.Unsupported.requireSupported()
        }
        assertEquals(ConfigurationProblem.UNSUPPORTED_PAIR, error.problem)
    }

    @Test fun snapshotOwnsCollectionsAndRoutesIdentifiedSourceWithoutDefaulting() {
        val settings = AppSettings(
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            speechOutputBackend = SpeechOutputBackend.PIPER,
        )
        val languages = listOf(settings.languagePair.source, settings.languagePair.target).toMutableList()
        val snapshot = ConversationSessionConfig(
            languages = languages,
            engineId = "mlkit",
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            vadModelId = settings.vadModelId,
            languageIdModelId = settings.languageIdModelId,
            vadMinSilenceMs = 900,
            ttsSpeedPercent = 110,
            speechOutputBackend = SpeechOutputBackend.PIPER,
        )
        languages.clear()
        assertEquals(2, snapshot.languages.size)
        assertEquals("asr-en-whisper-tiny.en", snapshot.asrModelIds[LanguageCatalog.ENGLISH])
        assertEquals(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN), snapshot.pairFor(LanguageCatalog.ENGLISH))
        assertThrows(UnsupportedSpeechLanguageException::class.java) { snapshot.pairFor(LanguageCatalog.GERMAN) }
    }

    @Test fun systemRecognitionDoesNotRequireCuratedNativeModels() {
        val settings = AppSettings(
            languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN),
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
        )
        val system = settings.conversationConfig()
        assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, system.speechRecognitionBackend)
        assertEquals(listOf(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN), system.languages)
        assertEquals(emptyMap<LanguageId, String>(), system.asrModelIds)
        assertEquals(null, RecommendedConversationVoices.modelId(LanguageCatalog.GERMAN))

        val sherpa = assertThrows(InvalidConfigurationException::class.java) {
            settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA).conversationConfig()
        }
        assertEquals(ConfigurationProblem.UNKNOWN_MODEL, sherpa.problem)
    }

    @Test fun sharedPairKeepsRecognitionAndOutputSelectionsIndependent() {
        val settings = AppSettings(languagePair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        assertEquals(settings.languagePair, settings.textConfig().pair)
        val system = settings.conversationConfig()
        assertEquals(listOf(settings.languagePair.source, settings.languagePair.target), system.languages)
        assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, system.speechRecognitionBackend)
        assertEquals(SpeechOutputBackend.SYSTEM_OFFLINE, system.speechOutputBackend)
        assertEquals(emptyMap<LanguageId, String>(), system.asrModelIds)
        assertEquals(emptyMap<LanguageId, String>(), system.ttsModelIds)

        val sherpa = settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA).conversationConfig()
        assertEquals("asr-ru-gigaam-v1", sherpa.asrModelIds[LanguageCatalog.RUSSIAN])
        assertEquals("asr-en-whisper-tiny.en", sherpa.asrModelIds[LanguageCatalog.ENGLISH])
        assertEquals(emptyMap<LanguageId, String>(), sherpa.ttsModelIds)

        val piper = settings.copy(speechOutputBackend = SpeechOutputBackend.PIPER).conversationConfig()
        assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, piper.speechRecognitionBackend)
        assertEquals(emptyMap<LanguageId, String>(), piper.asrModelIds)
        assertEquals("tts-en-piper-lessac-medium", piper.ttsModelIds[LanguageCatalog.ENGLISH])
        assertEquals("tts-ru-piper-irina-medium", piper.ttsModelIds[LanguageCatalog.RUSSIAN])
    }

    @Test fun whisperRoutesEitherParticipantWithoutProjectingLegacyRecognitionOrChangingHiddenChoices() {
        val settings = AppSettings(
            languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.RUSSIAN),
            speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
            languageIdModelId = "saved-lid",
            speechRecognitionService = "missing.provider/.RecognitionService",
        )
        val config = settings.conversationConfig()
        assertEquals(emptyMap<LanguageId, String>(), config.asrModelIds)
        assertEquals(emptyMap<LanguageId, String>(), config.ttsModelIds)
        assertEquals(settings.languagePair, config.pairFor(LanguageCatalog.DUTCH))
        assertEquals(settings.languagePair.reverse(), config.pairFor(LanguageCatalog.RUSSIAN))
        assertThrows(UnsupportedSpeechLanguageException::class.java) { config.pairFor(LanguageCatalog.ENGLISH) }
        assertEquals(settings.languageIdModelId, config.languageIdModelId)
        assertEquals(settings.speechRecognitionService, config.speechRecognitionService)
        val piper = settings.copy(languagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
            speechOutputBackend = SpeechOutputBackend.PIPER).conversationConfig()
        assertEquals(emptyMap<LanguageId, String>(), piper.asrModelIds)
        assertEquals(mapOf(LanguageCatalog.RUSSIAN to "tts-ru-piper-irina-medium",
            LanguageCatalog.ENGLISH to "tts-en-piper-lessac-medium"), piper.ttsModelIds)
    }

    @Test fun unknownSelectionsRemainPersistableButInvalidNumericSnapshotCannotRun() {
        val settings = AppSettings(vadModelId = "removed-model", vadMinSilenceMs = 12345)
        assertEquals("removed-model", settings.vadModelId)
        assertNotEquals(AppSettings(), settings)
        assertThrows(IllegalArgumentException::class.java) { settings.conversationConfig() }
    }
}
