package com.example.offlinetranslator.settings

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.tencent.TencentTranslationEngine
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsSheetTest {
    @get:Rule val compose = createComposeRule()
    private val defaults = AppSettings()
    private val choices = listOf(
        SpeechModelChoice(defaults.vadModelId, SpeechModelRole.VAD, emptySet(), "Silero"),
        SpeechModelChoice(defaults.languageIdModelId, SpeechModelRole.LANGUAGE_ID, setOf(defaults.languagePair.source, defaults.languagePair.target), "Whisper language detection"),
        SpeechModelChoice(RecommendedConversationRecognition.modelId(LanguageCatalog.RUSSIAN)!!, SpeechModelRole.ASR, setOf(LanguageCatalog.RUSSIAN), "GigaAM"),
        SpeechModelChoice(RecommendedConversationRecognition.modelId(LanguageCatalog.ENGLISH)!!, SpeechModelRole.ASR, setOf(LanguageCatalog.ENGLISH), "Whisper tiny.en"),
        SpeechModelChoice("asr-en-whisper-tiny", SpeechModelRole.ASR, setOf(LanguageCatalog.ENGLISH), "Whisper tiny"),
        SpeechModelChoice(RecommendedConversationVoices.modelId(LanguageCatalog.RUSSIAN)!!, SpeechModelRole.TTS, setOf(LanguageCatalog.RUSSIAN), "Irina"),
        SpeechModelChoice(RecommendedConversationVoices.modelId(LanguageCatalog.ENGLISH)!!, SpeechModelRole.TTS, setOf(LanguageCatalog.ENGLISH), "Lessac"),
    )

    @Test fun portraitSelectorsChangeOnlyDraftAndApplyReceivesActualSelections() {
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        val service = "installed.provider/.RecognitionService"
        state.value = state.value.copy(recognitionServices = listOf(service to "Installed recognition"))
        var applied: AppSettings? = null
        compose.setContent { MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor),
                    { state.value = state.value.copy(draft = it) }, { applied = state.value.draft }, {}, {}, {})
            }
        } }
        choose(SettingsTestTags.RECOGNITION_SERVICE, service)
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        compose.onNodeWithTag(SettingsTestTags.LID).assertDoesNotExist()
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.WHISPER.persistedId)
        compose.onNodeWithTag(SettingsTestTags.LID).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(defaults.copy(speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER,
                speechRecognitionService = service), state.value.draft)
        }
        choose(SettingsTestTags.RECOGNITION_BACKEND, "system-offline")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo().assertIsDisplayed()
        choose(SettingsTestTags.RECOGNITION_BACKEND, "sherpa")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        choose(SettingsTestTags.VAD, defaults.vadModelId)
        choose(SettingsTestTags.LID, defaults.languageIdModelId)
        choose(SettingsTestTags.SILENCE, "900")
        choose(SettingsTestTags.SPEED, "110")
        compose.runOnIdle {
            assertEquals(null, applied)
            assertEquals(SpeechOutputBackend.PIPER, state.value.draft.speechOutputBackend)
            assertEquals(SpeechRecognitionBackend.SHERPA, state.value.draft.speechRecognitionBackend)
            assertEquals(service, state.value.draft.speechRecognitionService)
            assertEquals(defaults.vadModelId, state.value.draft.vadModelId)
            assertEquals(defaults.languageIdModelId, state.value.draft.languageIdModelId)
        }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(SpeechRecognitionBackend.SHERPA, applied?.speechRecognitionBackend)
            assertEquals(service, applied?.speechRecognitionService)
            assertEquals(SpeechOutputBackend.PIPER, applied?.speechOutputBackend)
            assertEquals(900, applied?.vadMinSilenceMs)
            assertEquals(110, applied?.ttsSpeedPercent)
            assertEquals(defaults.languagePair, applied?.languagePair)
        }
    }

    @Test fun unavailableSavedChoiceIsVisibleAndCanBeExplicitlyReplaced() {
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(textEngineId = "removed-provider"),
            loading = false, editable = true, problems = listOf(ConfigurationProblem.UNKNOWN_ENGINE)))
        compose.setContent { MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor),
                    { state.value = state.value.copy(draft = it, problems = emptyList()) }, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.TEXT_ENGINE).performScrollTo().assertTextContains("removed-provider", substring = true)
        choose(SettingsTestTags.TEXT_ENGINE, "mlkit")
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
        compose.runOnIdle { assertEquals("mlkit", state.value.draft.textEngineId) }
    }

    @Test fun shortFontTwoRussianNoticesAndFooterRemainReachable() {
        var reset = 0
        var closed = 0
        var retries = 0
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true, corruptionNotice = true,
            saveError = true, problems = listOf(ConfigurationProblem.UNSUPPORTED_PAIR)))
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = 260
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources,
                LocalDensity provides Density(density.density, 2f)) { MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor),
                        { state.value = state.value.copy(draft = it) }, {}, { reset++ }, { retries++ }, { closed++ })
                }
            } }
        }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RESET).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.NOTICE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.RETRY).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { state.value = state.value.copy(saveError = false, problems = emptyList()) }
        choose(SettingsTestTags.RECOGNITION_BACKEND, "sherpa")
        choose(SettingsTestTags.LID, defaults.languageIdModelId)
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).performScrollTo().assertTextContains("Piper", substring = true)
        choose(SettingsTestTags.SILENCE, "1000")
        choose(SettingsTestTags.SPEED, "115")
        compose.runOnIdle {
            assertEquals(1000, state.value.draft.vadMinSilenceMs)
            assertEquals(SpeechRecognitionBackend.SHERPA, state.value.draft.speechRecognitionBackend)
            assertEquals(115, state.value.draft.ttsSpeedPercent)
            assertEquals(defaults.languagePair, state.value.draft.languagePair)
        }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        compose.runOnIdle { assertEquals(1, reset); assertEquals(1, retries); assertEquals(1, closed) }
    }

    @Test fun loadingAndBusyDisableDraftButCloseStillCancels() {
        val state = mutableStateOf(SettingsUiState())
        var closed = false
        compose.setContent { MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor), {}, {}, {}, {}, { closed = true })
            }
        } }
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_BACKEND).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RESET).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.INTERFACE_LANGUAGE).performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }

    private fun choose(tag: String, id: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("${tag}_option_$id").performScrollTo().performClick()
    }
    @Test fun loadFailureDoesNotOfferOverwriteAndRetryIsExplicit() {
        var retries = 0
        compose.setContent { MaterialTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                SettingsSheet(SettingsUiState(loading = false, editable = true, loadError = true), choices,
                    listOf(MlKitTranslationEngine.descriptor), {}, {}, {}, { retries++ }, {})
            }
        } }
        compose.onNodeWithTag(SettingsTestTags.NOTICE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RESET).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RETRY).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun landscapeFontTwoBackendChoicesStayIndependentAndHiddenLidIsPreserved() {
        val pair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(languagePair = pair), loading = false, editable = true))
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 800
                screenHeightDp = 360
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalContext provides context,
                LocalResources provides context.resources,
                LocalDensity provides Density(density.density, 2f)) { MaterialTheme {
                SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor),
                    { state.value = state.value.copy(draft = it) }, {}, {}, {}, {})
            } }
        }
        compose.onNodeWithTag(SettingsTestTags.LID).assertDoesNotExist()
        choose(SettingsTestTags.RECOGNITION_SERVICE, DEFAULT_RECOGNITION_SERVICE)
        choose(SettingsTestTags.RECOGNITION_BACKEND, "sherpa")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        choose(SettingsTestTags.LID, defaults.languageIdModelId)
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        choose(SettingsTestTags.RECOGNITION_BACKEND, "system-offline")
        compose.onNodeWithTag(SettingsTestTags.LID).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(SpeechOutputBackend.PIPER, state.value.draft.speechOutputBackend)
            assertEquals(defaults.languageIdModelId, state.value.draft.languageIdModelId)
        }
        choose(SettingsTestTags.OUTPUT_BACKEND, "system-offline")
        compose.runOnIdle {
            assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, state.value.draft.speechRecognitionBackend)
            assertEquals(pair, state.value.draft.languagePair)
        }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed()
    }

    @Test fun registeredProviderSelectionsStayModeSpecificAndRemovedProviderIsRepairable() {
        val tencent = TencentTranslationEngine.descriptor
        val providers = mutableStateOf(listOf(MlKitTranslationEngine.descriptor, tencent))
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, choices, providers.value, { state.value = state.value.copy(draft = it) },
                {}, {}, {}, {})
        } }
        choose(SettingsTestTags.TEXT_ENGINE, tencent.id)
        compose.runOnIdle {
            assertEquals(tencent.id, state.value.draft.textEngineId)
            assertEquals("mlkit", state.value.draft.conversationEngineId)
        }
        choose(SettingsTestTags.CONVERSATION_ENGINE, tencent.id)
        choose(SettingsTestTags.TEXT_ENGINE, "mlkit")
        compose.runOnIdle {
            assertEquals("mlkit", state.value.draft.textEngineId)
            assertEquals(tencent.id, state.value.draft.conversationEngineId)
            providers.value = listOf(MlKitTranslationEngine.descriptor)
        }
        compose.onNodeWithTag(SettingsTestTags.CONVERSATION_ENGINE).performScrollTo().assertHasClickAction()
        choose(SettingsTestTags.CONVERSATION_ENGINE, "mlkit")
        compose.runOnIdle {
            assertEquals("mlkit", state.value.draft.textEngineId)
            assertEquals("mlkit", state.value.draft.conversationEngineId)
        }
    }

    @Test fun removedRecognitionServiceStaysVisibleUntilExplicitReplacement() {
        val missing = "removed.provider/.RecognitionService"
        val installed = "installed.provider/.RecognitionService"
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(speechRecognitionService = missing),
            loading = false, editable = true, recognitionServices = listOf(installed to "Installed recognition")))
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, choices, listOf(MlKitTranslationEngine.descriptor),
                { state.value = state.value.copy(draft = it) }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo()
            .assertIsEnabled().assertTextContains(missing, substring = true)
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
        choose(SettingsTestTags.RECOGNITION_SERVICE, installed)
        compose.runOnIdle { assertEquals(installed, state.value.draft.speechRecognitionService) }
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.SHERPA.persistedId)
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        compose.runOnIdle { assertEquals(installed, state.value.draft.speechRecognitionService) }
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.SYSTEM_OFFLINE.persistedId)
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo().assertTextContains("Installed recognition")
        choose(SettingsTestTags.RECOGNITION_SERVICE, DEFAULT_RECOGNITION_SERVICE)
        compose.runOnIdle { assertEquals(null, state.value.draft.speechRecognitionService) }
    }

}
