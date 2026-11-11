package io.github.ngoroth.offlinetranslator.settings

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.R
import androidx.test.espresso.Espresso.pressBack
import io.github.ngoroth.offlinetranslator.core.*
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsSheetTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()
    private val defaults = AppSettings(translationEngineId = "isolated-provider", translationSetupComplete = true,
        speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
    private val providers = listOf(TencentTranslationEngine.descriptor.copy(id = "isolated-provider", displayName = "Isolated translator"), TencentTranslationEngine.descriptor)

    @Test fun basicEditsAndExpandedControlsApplyOneCommonEngineWithoutChangingThePair() {
        val state = mutableStateOf(SettingsUiState(draft = defaults, loading = false, editable = true))
        val service = "installed.provider/.RecognitionService"
        state.value = state.value.copy(recognitionServices = listOf(service to "Installed recognition"))
        var applied: AppSettings? = null
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, providers, { state.value = state.value.copy(draft = it) },
                { applied = state.value.draft }, {}, {}, {})
        } }
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_BACKEND).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.RESET).assertDoesNotExist()
        selectEngine("tencent")
        choose(SettingsTestTags.SPEED, "110")
        expandAdvanced()
        choose(SettingsTestTags.RECOGNITION_SERVICE, service)
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        choose(SettingsTestTags.RECOGNITION_BACKEND, "whisper")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        choose(SettingsTestTags.SILENCE, "900")
        compose.runOnIdle { assertEquals(null, applied) }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(defaults.copy(translationEngineId = "tencent", speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.WHISPER, speechRecognitionService = service,
                vadMinSilenceMs = 900, ttsSpeedPercent = 110), applied)
        }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed()
    }

    @Test fun unavailableSavedTranslatorCanBeReplacedWithoutOpeningAdvanced() {
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(translationEngineId = "removed-provider"),
            loading = false, editable = true, problems = listOf(ConfigurationProblem.UNKNOWN_ENGINE)))
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, providers,
                { state.value = state.value.copy(draft = it, problems = emptyList()) }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithText("removed-provider", substring = true).performScrollTo().assertIsDisplayed()
        selectEngine("isolated-provider")
        compose.onNodeWithTag(engineTag("isolated-provider")).assertIsSelected()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_BACKEND).assertDoesNotExist()
    }

    @Test fun recognitionServiceSurvivesWhisperAndCollapseWithoutChangingSpeechOutput() {
        val missing = "removed.provider/.RecognitionService"
        val installed = "installed.provider/.RecognitionService"
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(speechRecognitionService = missing),
            loading = false, editable = true, recognitionServices = listOf(installed to "Installed recognition")))
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, providers, { state.value = state.value.copy(draft = it) }, {}, {}, {}, {}, initiallyExpanded = true)
        } }
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo()
            .assertIsEnabled().assertTextContains(missing, substring = true)
        choose(SettingsTestTags.RECOGNITION_SERVICE, installed)
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        choose(SettingsTestTags.RECOGNITION_BACKEND, "whisper")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        expandAdvanced()
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).assertDoesNotExist()
        expandAdvanced()
        choose(SettingsTestTags.RECOGNITION_BACKEND, "system-offline")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo().assertTextContains("Installed recognition")
        compose.runOnIdle { assertEquals(SpeechOutputBackend.PIPER, state.value.draft.speechOutputBackend) }
        choose(SettingsTestTags.RECOGNITION_SERVICE, DEFAULT_RECOGNITION_SERVICE)
        compose.runOnIdle { assertEquals(null, state.value.draft.speechRecognitionService) }
    }

    @Test fun shortFontTwoRussianFailureAndFooterStayReachableWhenAdvancedIsCollapsed() {
        val state = mutableStateOf(SettingsUiState(draft = defaults, loading = false, editable = true,
            saveError = true, corruptionNotice = true))
        var retries = 0
        var reset = 0
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = 260
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    SettingsSheet(state.value, providers, { state.value = state.value.copy(draft = it) }, {}, { reset++ },
                        { retries++; state.value = state.value.copy(saveError = false) }, {})
                }
            }
        }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.RESET).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.RETRY).performScrollTo().assertIsDisplayed().performClick()
        expandAdvanced()
        compose.onNodeWithTag(SettingsTestTags.RESET).performScrollTo().assertIsDisplayed().performClick()
        choose(SettingsTestTags.SILENCE, "1000")
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, reset); assertEquals(1, retries); assertEquals(1000, state.value.draft.vadMinSilenceMs) }
    }

    @Test fun landscapeFontTwoRetainedControlsAndFixedActionsAreReachable() {
        val pair = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
        val state = mutableStateOf(SettingsUiState(draft = defaults.copy(languagePair = pair), loading = false, editable = true))
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 800
                screenHeightDp = 360
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { SettingsSheet(state.value, providers, { state.value = state.value.copy(draft = it) }, {}, {}, {}, {}) }
            }
        }
        selectEngine("tencent")
        choose(SettingsTestTags.SPEED, "115")
        expandAdvanced()
        choose(SettingsTestTags.OUTPUT_BACKEND, "piper")
        choose(SettingsTestTags.RECOGNITION_BACKEND, "whisper")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).assertDoesNotExist()
        choose(SettingsTestTags.RECOGNITION_BACKEND, "system-offline")
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_SERVICE).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(pair, state.value.draft.languagePair)
            assertEquals(SpeechOutputBackend.PIPER, state.value.draft.speechOutputBackend)
            assertEquals(115, state.value.draft.ttsSpeedPercent)
        }
    }

    @Test fun interfaceLanguageRemainsImmediateWhileDraftEditsAreBlocked() {
        val state = mutableStateOf(SettingsUiState(draft = defaults))
        val language = mutableStateOf(LanguageCatalog.ENGLISH)
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (open.value) SettingsSheet(state.value, providers, {}, {}, {}, {}, { open.value = false },
                interfaceLanguage = language.value, onInterfaceLanguageSelected = { language.value = it })
        } }
        compose.onNodeWithTag(engineTag("tencent")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        choose(SettingsTestTags.INTERFACE_LANGUAGE, "ru")
        compose.runOnIdle {
            assertEquals(LanguageCatalog.RUSSIAN, language.value)
            assertEquals(defaults, state.value.draft)
            state.value = state.value.copy(loading = false, editable = false)
        }
        choose(SettingsTestTags.INTERFACE_LANGUAGE, "en")
        compose.onNodeWithTag(engineTag("isolated-provider")).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        compose.onNodeWithTag(SettingsTestTags.BODY).assertDoesNotExist()
    }

    @Test fun loadFailureDoesNotOfferOverwriteAndRetryStaysOutsideAdvanced() {
        val state = mutableStateOf(SettingsUiState(draft = defaults, loading = false, editable = true, loadError = true))
        compose.setContent { MaterialTheme {
            SettingsSheet(state.value, providers, {}, {}, {}, { state.value = state.value.copy(loadError = false) }, {})
        } }
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithTag(SettingsTestTags.RECOGNITION_BACKEND).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.RETRY).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
    }

    @Test fun setupRetainsFailedSelectionAndMountsRoutesOnlyAfterCommittedSuccess() {
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        val submissions = mutableListOf<String>()
        var mounted = 0
        compose.setContent { MaterialTheme {
            TranslationSetupGate(state.value, providers, { engine ->
                submissions += engine
                state.value = state.value.copy(saving = true)
            }, {}) {
                DisposableEffect(Unit) { mounted++; onDispose {} }
                Text("Route", Modifier.testTag("mounted_route"))
            }
        } }
        selectEngine("tencent")
        compose.onNodeWithTag(engineTag("tencent")).assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, mounted)
            assertEquals(listOf("tencent"), submissions)
            state.value = state.value.copy(saving = false, saveError = true)
        }
        pressBack()
        compose.onNodeWithTag(SettingsTestTags.SETUP).assertExists()
        compose.onNodeWithTag(engineTag("tencent")).assertIsSelected()
        compose.onNodeWithTag(SettingsTestTags.RETRY).assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("tencent", "tencent"), submissions)
            assertEquals(0, mounted)
            state.value = state.value.copy(saving = false, saveError = false, translationSetupComplete = true)
        }
        compose.onNodeWithTag(SettingsTestTags.SETUP).assertDoesNotExist()
        compose.onNodeWithTag("mounted_route").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, mounted) }
    }

    @Test fun setupLoadingFailureBusyAndTeardownCannotExposeRoutes() {
        val state = mutableStateOf(SettingsUiState())
        compose.setContent { MaterialTheme {
            TranslationSetupGate(state.value, providers, {}, { state.value = state.value.copy(loadError = false, loading = true) }) {
                Text("Route", Modifier.testTag("mounted_route"))
            }
        } }
        compose.onNodeWithTag(engineTag("isolated-provider")).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, editable = true, loadError = true) }
        compose.onNodeWithTag(SettingsTestTags.RETRY).assertIsDisplayed().performClick()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, editable = false) }
        compose.onNodeWithTag(engineTag("isolated-provider")).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(editable = true, teardownError = true) }
        compose.onNodeWithTag(engineTag("isolated-provider")).assertIsNotEnabled()
        pressBack()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.SETUP).assertExists()
    }

    @Test fun unfamiliarProviderSelectionDoesNotDismissWithoutCommittedCompletion() {
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        compose.setContent { MaterialTheme {
            TranslationSetupGate(state.value, providers, {
                state.value = state.value.copy(draft = state.value.draft.copy(translationEngineId = it, translationSetupComplete = true))
            }, {}) {
                Text("Route", Modifier.testTag("mounted_route"))
            }
        } }
        selectEngine("isolated-provider")
        compose.onNodeWithTag(engineTag("isolated-provider")).assertIsSelected()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(translationSetupComplete = true) }
        compose.onNodeWithTag("mounted_route").assertIsDisplayed()
    }

    @Test fun shortFontTwoSetupChoicesAndFailedSaveRetryAreReachable() {
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenWidthDp = 360
                screenHeightDp = 260
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    TranslationSetupGate(state.value, providers, { state.value = state.value.copy(saveError = true) }, {}) {
                        Text("Route", Modifier.testTag("mounted_route"))
                    }
                }
            }
        }
        selectEngine("isolated-provider")
        compose.onNodeWithTag(SettingsTestTags.RETRY).assertIsDisplayed().assertIsEnabled()
        selectEngine("tencent")
        compose.onNodeWithTag(SettingsTestTags.RETRY).assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
    }

    @Test fun tencentChoiceShowsCompactDownloadHintAndPreservesSelection() {
        val selected = mutableStateOf("tencent")
        val selections = mutableListOf<String>()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(targetContext.resources.configuration).apply {
            screenHeightDp = 260
            fontScale = 2f
            setLocales(LocaleList(Locale.forLanguageTag("ru")))
        }
        val context = targetContext.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().height(260.dp)) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            TranslationEngineChoice(selected.value, providers, true) {
                                selections += it
                                selected.value = it
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.tencent_model_download_hint))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.tencent_model_explanation)).assertDoesNotExist()
        val tencent = compose.onNodeWithTag(engineTag("tencent"))
            .assertIsSelected().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        val other = compose.onNodeWithTag(engineTag("isolated-provider"))
            .assertIsNotSelected().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        val first = other.fetchSemanticsNode()
        val second = tencent.fetchSemanticsNode()
        assertTrue("Engine rows must not overlap", first.positionInRoot.y + first.size.height <= second.positionInRoot.y)
        compose.runOnIdle { assertEquals("tencent", selected.value); assertEquals(emptyList<String>(), selections) }
        other.performScrollTo().performClick()
        compose.onNodeWithTag(engineTag("isolated-provider")).assertIsSelected().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(engineTag("tencent")).assertIsNotSelected().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText(context.getString(R.string.tencent_model_download_hint)).assertDoesNotExist()
        compose.runOnIdle { assertEquals("isolated-provider", selected.value); assertEquals(listOf("isolated-provider"), selections) }
    }

    @Test fun soleProviderCommitsThroughViewModelAndSurvivesRepositoryReopen() {
        val file = File(temp.root, "sole-provider-settings.json")
        val repository = AppSettingsRepository(file)
        val initial = AppSettings(vadMinSilenceMs = 1100, ttsSpeedPercent = 115)
        runBlocking { repository.save(initial) }
        var writes = 0
        lateinit var model: SettingsViewModel
        compose.runOnUiThread {
            model = SettingsViewModel(repository, MutableStateFlow(true), { emptyList() },
                { repository.save(it); true }, MutableStateFlow(false), completeSetup = { engineId ->
                    writes++
                    repository.save(repository.current().copy(translationEngineId = engineId, translationSetupComplete = true))
                    true
                })
        }
        try {
            compose.setContent { MaterialTheme {
                TranslationSetupGate(model.state.collectAsState().value, listOf(TencentTranslationEngine.descriptor),
                    model::completeTranslationSetup, model::retry) {
                    Text("Route", Modifier.testTag("mounted_route"))
                }
            } }
            compose.waitUntil(10_000) { model.state.value.translationSetupComplete }
            compose.onNodeWithTag("mounted_route").assertIsDisplayed()
            compose.onNodeWithTag(SettingsTestTags.TRANSLATION_ENGINE).assertDoesNotExist()
            compose.runOnIdle { assertEquals(1, writes) }
        } finally {
            compose.runOnUiThread { model.viewModelScope.cancel() }
            runBlocking { repository.closeAndJoin() }
        }
        val reopened = AppSettingsRepository(file)
        try {
            runBlocking { assertEquals(initial.copy(translationSetupComplete = true), reopened.current()) }
        } finally { runBlocking { reopened.closeAndJoin() } }
    }

    @Test fun soleProviderWaitsForGatesAndRequiresManualRetryAfterFailure() {
        val state = mutableStateOf(SettingsUiState())
        val submissions = mutableListOf<String>()
        var loads = 0
        compose.setContent { MaterialTheme {
            TranslationSetupGate(state.value, listOf(TencentTranslationEngine.descriptor), { id ->
                submissions += id
                state.value = state.value.copy(saving = true, saveError = false)
            }, { loads++ }) {
                Text("Route", Modifier.testTag("mounted_route"))
            }
        } }
        compose.onNodeWithTag(SettingsTestTags.TRANSLATION_ENGINE).assertDoesNotExist()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(loading = false, editable = true, loadError = true)
        }
        compose.onNodeWithTag(SettingsTestTags.RETRY).performClick()
        compose.runOnIdle {
            assertEquals(1, loads)
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(loadError = false, editable = false)
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(editable = true, teardownError = true)
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(teardownError = false, saving = true)
        }
        compose.runOnIdle {
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(saving = false)
        }
        compose.runOnIdle {
            assertEquals(listOf("tencent"), submissions)
            state.value = state.value.copy(saving = false, saveError = true)
        }
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(corruptionNotice = true, editable = false) }
        compose.runOnIdle { state.value = state.value.copy(editable = true) }
        compose.runOnIdle { assertEquals(listOf("tencent"), submissions) }
        compose.onNodeWithTag(SettingsTestTags.RETRY).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("tencent", "tencent"), submissions)
            state.value = state.value.copy(saving = false, translationSetupComplete = true)
        }
        compose.onNodeWithTag("mounted_route").assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.SETUP).assertDoesNotExist()
    }

    @Test fun noRegisteredProviderBlocksEvenPreviouslyCompletedSetup() {
        val state = mutableStateOf(SettingsUiState(loading = false, editable = true))
        val submissions = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            TranslationSetupGate(state.value, emptyList(), { submissions += it }, {}) {
                Text("Route", Modifier.testTag("mounted_route"))
            }
        } }
        compose.onNodeWithTag(SettingsTestTags.TRANSLATION_ENGINE).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.SETUP).assertExists()
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), submissions)
            state.value = state.value.copy(translationSetupComplete = true)
        }
        compose.onNodeWithTag("mounted_route").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), submissions) }
    }

    private fun expandAdvanced() {
        compose.onNodeWithTag(SettingsTestTags.ADVANCED).performScrollTo().performClick()
    }

    private fun engineTag(id: String) = "${SettingsTestTags.TRANSLATION_ENGINE}_option_$id"

    private fun selectEngine(id: String) {
        compose.onNodeWithTag(engineTag(id)).performScrollTo().performClick()
    }

    private fun choose(tag: String, id: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("${tag}_option_$id").performScrollTo().performClick()
    }
}
