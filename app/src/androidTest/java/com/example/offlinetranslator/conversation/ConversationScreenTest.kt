package com.example.offlinetranslator.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsSelected
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.ConversationFailure
import com.example.offlinetranslator.core.ConversationPhase
import com.example.offlinetranslator.core.ConversationState
import com.example.offlinetranslator.core.ConversationTranscript
import com.example.offlinetranslator.core.ConversationTranscriptStatus
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechModelStatus
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.ui.pressSystemBack
import com.example.offlinetranslator.ui.TranslatorHelpTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun outputRecoveryKeepsInstallAvailableForEverySelectedPairWithoutStarting() {
        val pair = mutableStateOf(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH))
        compose.setContent { MaterialTheme {
            ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.IDLE, false, false, {}, {},
                pair = pair.value, systemOutput = true, outputReadiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE,
                canInstallVoiceData = true)
        } }
        compose.onNodeWithTag(ConversationTestTags.INSTALL_VOICES).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        for (language in LanguageCatalog.entries.map { it.id }.filter { it != LanguageCatalog.ENGLISH && it != LanguageCatalog.RUSSIAN }) {
            compose.runOnIdle { pair.value = LanguagePair(language, LanguageCatalog.ENGLISH) }
            compose.onNodeWithTag(ConversationTestTags.INSTALL_VOICES).performScrollTo().assertIsEnabled()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
            compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
            compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
            compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        }
        compose.runOnIdle { pair.value = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN) }
        compose.onNodeWithTag(ConversationTestTags.INSTALL_VOICES).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
    }

    @Test fun androidRecognitionStatesBlockStaleStartAndOfferExplicitEligibleSettingsAction() {
        val readiness = mutableStateOf(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
        val eligible = mutableStateOf(true)
        var checks = 0
        var opens = 0
        compose.setContent { MaterialTheme {
            ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.IDLE, true, false, {}, {},
                systemRecognition = true, recognitionReadiness = readiness.value, canChooseSherpa = eligible.value,
                onRecheck = { checks++ }, onOpenAdvancedSettings = { opens++ })
        } }
        for (value in SpeechRecognitionReadiness.entries.filter { it != SpeechRecognitionReadiness.READY }) {
            compose.runOnIdle { readiness.value = value }
            compose.onNodeWithTag(ConversationTestTags.RECOGNITION_READINESS).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        }
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        compose.runOnIdle { assertEquals(0, checks); assertEquals(0, opens) }
        compose.onNodeWithTag(ConversationTestTags.RECHECK_RECOGNITION).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag(ConversationTestTags.OPEN_ADVANCED_SETTINGS).performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, opens); eligible.value = false }
        compose.onNodeWithTag(ConversationTestTags.OPEN_ADVANCED_SETTINGS).assertDoesNotExist()
        compose.runOnIdle { readiness.value = SpeechRecognitionReadiness.READY }
        compose.onNodeWithTag(ConversationTestTags.RECHECK_RECOGNITION).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.RECOGNITION_READINESS).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.PREPARATION).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsEnabled()
    }

    @Test fun fontTwoChineseStatusAndEveryCatalogChooserEntryRemainReachable() {
        var opens = 0
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
                LocalResources provides context.resources, LocalDensity provides Density(density.density, 2f)) { MaterialTheme {
                Box(Modifier.fillMaxWidth().height(360.dp)) {
                    ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.ERROR, false, false, {}, {},
                        provider = MlKitTranslationEngine.descriptor,
                        pair = LanguagePair(LanguageCatalog.CHINESE, LanguageCatalog.ENGLISH),
                        conversationCoverage = LanguageCatalog.entries.map { it.id }.toSet(),
                        systemRecognition = true, recognitionReadiness = SpeechRecognitionReadiness.DOWNLOAD_FAILED,
                        onOpenAdvancedSettings = { opens++ })
                }
            } }
        }
        compose.onNodeWithTag(ConversationTestTags.RECOGNITION_READINESS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.RECHECK_RECOGNITION).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.OPEN_ADVANCED_SETTINGS).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription(localizedAttribution("ru")).assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().performClick()
        for (language in LanguageCatalog.entries) {
            compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_option_${language.id.value}")
                .performScrollTo().assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_close").performClick()
        compose.runOnIdle { assertEquals(0, opens) }
    }

    @Test fun missingInstallerDoesNotLaunchAnyFallbackActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var launches = 0
        val guarded = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launches++; throw SecurityException("Unexpected launch") }
        }
        assertEquals(false, installSystemVoiceData(guarded, null))
        assertEquals(false, installSystemVoiceData(guarded, "com.example.offlinetranslator.missingengine"))
        assertEquals(0, launches)
    }


    @Test fun chooserCoverageSelectionCloseAndBackDoNotPromiseInstalledReadiness() {
        val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val selections = mutableListOf<LanguagePair>()
        compose.setContent { MaterialTheme {
            ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.IDLE, false, false, {}, {},
                pair = pair, conversationCoverage = setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                systemOutput = true, outputReadiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE,
                onPairSelected = selections::add)
        } }
        val first = compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST)
        first.performScrollTo().assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_option_ru").performScrollTo()
            .assertIsSelected().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_option_ru").performClick()
        compose.runOnIdle { assertTrue(selections.isEmpty()) }
        first.performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_option_fr").performScrollTo()
            .assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.ENGLISH)), selections) }
        first.performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_close").performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser").assertDoesNotExist()
        val beforeBack = compose.runOnIdle { selections.toList() }
        first.performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser").assertIsDisplayed()
        pressSystemBack(compose, "${ConversationTestTags.LANGUAGE_FIRST}_chooser")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser").assertDoesNotExist()
        first.assertIsDisplayed()
        compose.runOnIdle { assertEquals(beforeBack, selections) }
        first.performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_option_en").performScrollTo().performClick()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).performScrollTo().performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_SECOND}_option_ru").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.ENGLISH), pair.reverse(), pair.reverse()), selections) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
    }

    @Test fun changingBusyAndTeardownBlockEditsAndStartButNeverStop() {
        val changing = mutableStateOf(false)
        val editable = mutableStateOf(true)
        val error = mutableStateOf<ConversationLanguageChangeError?>(null)
        val preparation = mutableStateOf(ConversationPreparation.IDLE)
        val ready = mutableStateOf(true)
        val state = mutableStateOf(ConversationState())
        var actions = 0
        compose.setContent { MaterialTheme {
            ConversationScreen(state.value, readyModels(), preparation.value, ready.value, false, {}, { actions++ },
                languageSelectionEnabled = editable.value, languageChanging = changing.value,
                languageChangeError = error.value)
        } }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser").assertIsDisplayed()
        compose.runOnIdle { changing.value = true }
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_chooser").assertDoesNotExist()
        for (tag in listOf(ConversationTestTags.LANGUAGE_FIRST, ConversationTestTags.LANGUAGE_SECOND)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.runOnIdle { ready.value = false }
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { changing.value = false; editable.value = false }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { editable.value = true; preparation.value = ConversationPreparation.CHECKING }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            preparation.value = ConversationPreparation.IDLE
            error.value = ConversationLanguageChangeError.TEARDOWN
        }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { ready.value = true }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.SPEAKING) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        compose.runOnIdle {
            state.value = ConversationState()
            error.value = ConversationLanguageChangeError.SAVE
        }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_FIRST}_close").performClick()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.SPEAKING) }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_SECOND}_close").performClick()
    }
    @Test fun incompleteOrBusyPreparationCannotStartEvenWithInstalledSpeechPackages() {
        val ready = mutableStateOf(false)
        val preparation = mutableStateOf(ConversationPreparation.ERROR)
        compose.setContent { Screen(ConversationState(), preparation.value, ready.value) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            ready.value = true
            preparation.value = ConversationPreparation.TRANSLATION
        }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { preparation.value = ConversationPreparation.IDLE }
        compose.onNodeWithTag(ConversationTestTags.PREPARE).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsEnabled()
    }

    @Test fun preparationProgressExcludesOtherLanguagesAndDoesNotInventAndroidSizes() {
        val models = mutableStateOf(listOf(
            SpeechModelStatus("selected", "Selected", "https://example.test", "test", 100, SpeechModelInstallState.DOWNLOADING,
                downloadedBytes = 25, role = SpeechModelRole.ASR, languages = setOf(LanguageCatalog.ENGLISH)),
            SpeechModelStatus("shared", "Shared", "https://example.test", "test", 100, SpeechModelInstallState.READY,
                role = SpeechModelRole.VAD),
            SpeechModelStatus("unselected", "Unselected", "https://example.test", "test", 1_000, SpeechModelInstallState.DOWNLOADING,
                downloadedBytes = 1_000, role = SpeechModelRole.ASR, languages = setOf(LanguageCatalog.FRENCH)),
        ))
        compose.setContent { MaterialTheme {
            ConversationScreen(ConversationState(), models.value, ConversationPreparation.INSTALLING, false, false, {}, {},
                systemRecognition = true, recognitionReadiness = SpeechRecognitionReadiness.DOWNLOADING)
        } }
        compose.onNodeWithTag(ConversationTestTags.PREPARATION_PROGRESS).performScrollTo()
            .assertIsDisplayed().assertTextContains("62%", substring = true)
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.runOnIdle { models.value = emptyList() }
        compose.onNodeWithTag(ConversationTestTags.PREPARATION_PROGRESS).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.RECOGNITION_READINESS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
    }

    @Test fun stopRemainsAvailableWhenActiveSessionCannotStartAnotherOne() {
        val state = mutableStateOf(ConversationState())
        compose.setContent { Screen(state.value, ConversationPreparation.TRANSLATION, false) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.PHASE).assertDoesNotExist()
        for (phase in listOf(ConversationPhase.PREPARING, ConversationPhase.SPEAKING, ConversationPhase.ERROR)) {
            compose.runOnIdle { state.value = state.value.copy(phase = phase, sessionActive = true, captureActive = false) }
            compose.onNodeWithTag(ConversationTestTags.PHASE).assertIsDisplayed()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = state.value.copy(sessionActive = false) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.PHASE).assertIsDisplayed()
        compose.runOnIdle { state.value = ConversationState() }
        compose.onNodeWithTag(ConversationTestTags.PHASE).assertDoesNotExist()
    }

    @Test fun newTurnReplacesCurrentAndKeepsImmediatelyPreviousEvenWhenItFailed() {
        val old = ConversationTranscript(1, original = "Older turn must leave the screen", translation = "Older translation",
            status = ConversationTranscriptStatus.COMPLETE)
        val failed = ConversationTranscript(2, original = "Previous turn failed", translation = "Previous text is still useful",
            status = ConversationTranscriptStatus.ERROR, failure = ConversationFailure.PLAYBACK_FAILED)
        val current = ConversationTranscript(3)
        val state = mutableStateOf(ConversationState(phase = ConversationPhase.UNDERSTANDING, sessionActive = true,
            transcripts = listOf(old, failed, current)))
        compose.setContent { Screen(state.value, ConversationPreparation.IDLE, true) }
        compose.onNodeWithText(old.original).assertDoesNotExist()
        compose.onNodeWithText(requireNotNull(old.translation)).assertDoesNotExist()
        compose.onNodeWithText(failed.original).assertExists()
        compose.onNodeWithText(requireNotNull(failed.translation)).assertExists()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertExists()

        val translating = current.copy(original = "Where is platform five?", status = ConversationTranscriptStatus.TRANSLATING)
        compose.runOnIdle { state.value = state.value.copy(phase = ConversationPhase.TRANSLATING,
            transcripts = listOf(old, failed, translating)) }
        compose.onNodeWithText(translating.original).assertExists()
        val speaking = translating.copy(translation = "Где пятая платформа?", status = ConversationTranscriptStatus.SPEAKING)
        compose.runOnIdle { state.value = state.value.copy(phase = ConversationPhase.SPEAKING,
            transcripts = listOf(old, failed, speaking)) }
        compose.onNodeWithText(requireNotNull(speaking.translation)).assertExists()
        compose.onNodeWithText(failed.original).assertExists()
        val interrupted = speaking.copy(status = ConversationTranscriptStatus.INTERRUPTED)
        val next = ConversationTranscript(4, original = "The next person is speaking")
        compose.runOnIdle { state.value = state.value.copy(phase = ConversationPhase.UNDERSTANDING,
            transcripts = listOf(old, failed, interrupted, next)) }
        compose.onNodeWithText(failed.original).assertDoesNotExist()
        compose.onNodeWithText(requireNotNull(failed.translation)).assertDoesNotExist()
        compose.onNodeWithText(interrupted.original).assertExists()
        compose.onNodeWithText(requireNotNull(interrupted.translation)).assertExists()
        compose.onNodeWithText(next.original).assertExists()
    }

    @Test fun shortViewportKeepsActionVisibleAndPreservesReadingUntilNextTurn() {
        val longOriginal = "Please keep the whole original available while the conversation continues. ".repeat(24)
        val longTranslation = "Весь перевод должен оставаться доступным даже при увеличенном шрифте. ".repeat(24)
        val row = ConversationTranscript(7, direction = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN), original = longOriginal,
            translation = longTranslation, status = ConversationTranscriptStatus.ERROR, failure = ConversationFailure.PLAYBACK_FAILED)
        val state = mutableStateOf(ConversationState(failure = ConversationFailure.CAPTURE_FAILED, droppedSegments = 1,
            transcripts = listOf(row)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.fillMaxWidth().height(260.dp)) {
                    MaterialTheme {
                        ConversationScreen(state.value, readyModels(), ConversationPreparation.ERROR, false, true, {}, {},
                            provider = MlKitTranslationEngine.descriptor)
                    }
                }
            }
        }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val badge = compose.onNodeWithContentDescription(localizedAttribution())
        badge.assertIsDisplayed()
        for (tag in listOf(ConversationTestTags.LANGUAGE_FIRST, ConversationTestTags.LANGUAGE_SECOND)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                .assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("${tag}_option_en").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed()
        }
        compose.onNodeWithTag(ConversationTestTags.PERMISSION_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.DROPPED).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.PREPARE).performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText(longOriginal).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(longTranslation).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed()
        badge.assertIsDisplayed()
        val content = compose.onNodeWithTag(ConversationTestTags.CONTENT)
        val position = content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Reading a long translation must scroll only the working area", position > 0f)
        compose.runOnIdle { state.value = state.value.copy(transcripts = listOf(row.copy(status = ConversationTranscriptStatus.COMPLETE))) }
        compose.waitForIdle()
        assertEquals(position, content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        compose.runOnIdle { state.value = state.value.copy(transcripts = listOf(row, ConversationTranscript(8))) }
        compose.waitForIdle()
        assertEquals(0f, content.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        compose.runOnIdle { state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.ERROR) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        badge.assertIsDisplayed()
    }

    @Test fun compactRussianUnavailablePairKeepsSelectorsAndTextActionTouchableAndStopFixed() {
        val pair = mutableStateOf(LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.ENGLISH))
        val state = mutableStateOf(ConversationState())
        val selections = mutableListOf<LanguagePair>()
        var textOpens = 0
        var sessionActions = 0
        val pixelsPerDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = 260
                fontScale = 2f
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, config.fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().height(260.dp)) {
                        ConversationScreen(
                            state.value, emptyList(), ConversationPreparation.IDLE, false, false, {},
                            { sessionActions++ }, pair = pair.value, provider = MlKitTranslationEngine.descriptor,
                            configurationProblem = ConfigurationProblem.UNSUPPORTED_VOICE,
                            conversationCoverage = setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                            ownerAvailable = false,
                            onPairSelected = { pair.value = it; selections += it },
                            onOpenText = { textOpens++ },
                        )
                    }
                }
            }
        }
        fun touchTarget(tag: String, scrollTo: Boolean = true): SemanticsNodeInteraction {
            val node = compose.onNodeWithTag(tag)
            if (scrollTo) node.performScrollTo()
            node.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            val visible = node.fetchSemanticsNode().boundsInRoot
            assertTrue("The clipped $tag must retain a 48dp touch area",
                visible.height >= 48f * pixelsPerDp && visible.width >= 48f * pixelsPerDp)
            return node
        }
        touchTarget(ConversationTestTags.SESSION_ACTION, scrollTo = false).assertIsNotEnabled()
        val badge = compose.onNodeWithContentDescription(localizedAttribution("ru"))
        badge.assertIsDisplayed()
        for ((tag, language) in listOf(
            ConversationTestTags.LANGUAGE_FIRST to LanguageCatalog.SPANISH,
            ConversationTestTags.LANGUAGE_SECOND to LanguageCatalog.GERMAN,
        )) {
            touchTarget(tag).assertIsEnabled().performTouchInput { click() }
            touchTarget("${tag}_option_${language.value}").assertIsEnabled().performTouchInput { click() }
            touchTarget(ConversationTestTags.SESSION_ACTION, scrollTo = false).assertIsNotEnabled()
        }
        touchTarget(ConversationTestTags.LANGUAGE_SWAP).assertIsEnabled().performTouchInput { click() }
        touchTarget(ConversationTestTags.OPEN_TEXT).assertIsEnabled().performTouchInput { click() }
        badge.assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(
                LanguagePair(LanguageCatalog.SPANISH, LanguageCatalog.ENGLISH),
                LanguagePair(LanguageCatalog.SPANISH, LanguageCatalog.GERMAN),
                LanguagePair(LanguageCatalog.GERMAN, LanguageCatalog.SPANISH),
            ), selections)
            assertEquals(1, textOpens)
            state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.SPEAKING)
        }
        touchTarget(ConversationTestTags.SESSION_ACTION, scrollTo = false).assertIsEnabled().performTouchInput { click() }
        badge.assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sessionActions) }
    }

    @Test fun helpAndTermsDismissWithoutRemovingLiveCurrentTurnUpdates() {
        val row = ConversationTranscript(9, original = "Call me in fifteen minutes", translation = "Перезвоните через пятнадцать минут",
            status = ConversationTranscriptStatus.COMPLETE, latencyMillis = 2_501, latencyMeasured = false)
        val state = mutableStateOf(ConversationState(transcripts = listOf(row)))
        compose.setContent { MaterialTheme {
            ConversationScreen(state.value, readyModels(), ConversationPreparation.IDLE, true, false, {}, {},
                provider = MlKitTranslationEngine.descriptor)
        } }
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertIsDisplayed()
        val updated = row.copy(original = "Call me in five minutes", translation = "Перезвоните через пять минут",
            latencyMillis = 801, latencyMeasured = true)
        compose.runOnIdle { state.value = state.value.copy(transcripts = listOf(updated)) }
        pressSystemBack(compose, TranslatorHelpTestTags.TERMS_DIALOG)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(TranslatorHelpTestTags.DIALOG).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 &&
                compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).isDisplayed()
        }
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_BACK).performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithText(requireNotNull(row.translation)).assertDoesNotExist()
        compose.onNodeWithText(updated.original).assertExists()
        compose.onNodeWithText(requireNotNull(updated.translation)).assertExists()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled()
        repeat(3) {
            compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
            compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
            pressSystemBack(compose, TranslatorHelpTestTags.DIALOG)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(TranslatorHelpTestTags.DIALOG)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
            compose.onNodeWithText(updated.original).assertExists()
            compose.onNodeWithText(requireNotNull(updated.translation)).assertExists()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed()
        }
    }


    @Test fun emptyCardRemainsDuringActiveWaitUntilFirstCurrentTurnReplacesIt() {
        val state = mutableStateOf(ConversationState())
        var actions = 0
        compose.setContent { Screen(state.value, ConversationPreparation.IDLE, true, onSessionAction = { actions++ }) }
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.LISTENING) }
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions) }
        val first = ConversationTranscript(1, direction = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
            original = "Which platform should I use?", translation = "С какой платформы отправляться?",
            status = ConversationTranscriptStatus.SPEAKING)
        compose.runOnIdle { state.value = state.value.copy(phase = ConversationPhase.SPEAKING, transcripts = listOf(first)) }
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(first.original).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(requireNotNull(first.translation)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled()
    }

    @Test fun helpDismissalKeepsCurrentAndImmediatelyPreviousTurns() {
        val previous = ConversationTranscript(1, original = "Previous", translation = "Предыдущая",
            status = ConversationTranscriptStatus.COMPLETE, latencyMillis = 2501, latencyMeasured = true)
        val current = ConversationTranscript(2, original = "Current", translation = "Текущая",
            status = ConversationTranscriptStatus.COMPLETE, latencyMillis = null, latencyMeasured = false)
        compose.setContent { Screen(ConversationState(transcripts = listOf(previous, current)), ConversationPreparation.IDLE, true) }
        compose.onNodeWithText(requireNotNull(current.translation)).assertExists()
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        compose.onNodeWithText(requireNotNull(current.translation)).assertExists()
        compose.onNodeWithText(requireNotNull(previous.translation)).assertExists()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertExists()
        compose.onNodeWithTag(ConversationTestTags.PREVIOUS_TURN).assertExists()
    }

    @Test fun missingVoiceHasExplicitInstallRecheckFailureAndDisabledStart() {
        var installs = 0
        var rechecks = 0
        compose.setContent { MaterialTheme {
            ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.IDLE, false, false, {}, {},
                systemOutput = true, outputReadiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE,
                canInstallVoiceData = true, installError = true, onInstallVoiceData = { installs++ }, onRecheck = { rechecks++ })
        } }
        compose.onNodeWithTag(ConversationTestTags.OUTPUT_READINESS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.INSTALL_VOICES).performScrollTo().performClick()
        compose.onNodeWithTag(ConversationTestTags.RECHECK).performScrollTo().performClick()
        compose.onNodeWithTag(ConversationTestTags.INSTALL_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, installs); assertEquals(1, rechecks) }
    }

    @Test fun invalidSelectedConfigurationBlocksStartButNeverStop() {
        val state = mutableStateOf(ConversationState())
        var actions = 0
        compose.setContent { MaterialTheme {
            ConversationScreen(state.value, readyModels(), ConversationPreparation.IDLE, true, false, {}, { actions++ },
                configurationProblem = ConfigurationProblem.UNKNOWN_MODEL)
        } }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(sessionActive = true, phase = ConversationPhase.SPEAKING) }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions) }
    }

    @Test fun oneMlKitFooterBadgeStaysOutsidePendingTranslatedFailedAndInterruptedCardsAcrossProviderChanges() {
        val previous = ConversationTranscript(1, original = "A previous request", translation = "Предыдущий запрос",
            status = ConversationTranscriptStatus.ERROR, failure = ConversationFailure.PLAYBACK_FAILED)
        val current = ConversationTranscript(2, original = "A new request")
        val state = mutableStateOf(ConversationState(sessionActive = true, phase = ConversationPhase.UNDERSTANDING,
            transcripts = listOf(previous, current)))
        val provider = mutableStateOf<TranslationProviderDescriptor?>(MlKitTranslationEngine.descriptor)
        val attribution = localizedAttribution()
        val google = hasContentDescription(attribution)
        compose.setContent { MaterialTheme {
            ConversationScreen(state.value, emptyList(), ConversationPreparation.IDLE, true, false, {}, {},
                provider = provider.value)
        } }
        fun assertFooterOnly() {
            compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithContentDescription(attribution).assertIsDisplayed()
            for (tag in listOf(ConversationTestTags.CURRENT_TURN, ConversationTestTags.PREVIOUS_TURN,
                ConversationTestTags.SESSION_ACTION)) {
                compose.onNode(hasTestTag(tag) and hasAnyDescendant(google), useUnmergedTree = true).assertDoesNotExist()
            }
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsEnabled()
        }
        assertFooterOnly()
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).assertDoesNotExist()
        compose.onNode(hasTestTag(ConversationTestTags.CURRENT_TURN) and hasAnyDescendant(hasText(current.original))).assertExists()
        compose.onNode(hasTestTag(ConversationTestTags.PREVIOUS_TURN) and
            hasAnyDescendant(hasText(requireNotNull(previous.translation)))).assertExists()
        for (status in listOf(ConversationTranscriptStatus.COMPLETE, ConversationTranscriptStatus.ERROR,
            ConversationTranscriptStatus.INTERRUPTED)) {
            compose.runOnIdle { state.value = state.value.copy(transcripts = listOf(previous,
                current.copy(translation = "Новый запрос", status = status,
                    failure = if (status == ConversationTranscriptStatus.ERROR) ConversationFailure.PLAYBACK_FAILED else null))) }
            assertFooterOnly()
            compose.onNode(hasTestTag(ConversationTestTags.CURRENT_TURN) and hasAnyDescendant(hasText("Новый запрос"))).assertExists()
            compose.onNode(hasTestTag(ConversationTestTags.PREVIOUS_TURN) and
                hasAnyDescendant(hasText(requireNotNull(previous.translation)))).assertExists()
        }
        compose.runOnIdle { provider.value = null }
        compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle { provider.value = MlKitTranslationEngine.descriptor.copy(
            id = "isolated-descriptor", displayName = "Other translator", provider = "Other",
            attribution = "Other translator attribution", termsUrl = "https://example.test/terms") }
        compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Other translator attribution", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Новый запрос").assertExists()
        compose.onNodeWithText(requireNotNull(previous.translation)).assertExists()
        compose.runOnIdle { provider.value = MlKitTranslationEngine.descriptor }
        assertFooterOnly()
    }

    @Test fun badgeAndActionBoundsStayFixedAcrossIdleActivePendingResultErrorStoppedAndScrolling() {
        val state = mutableStateOf(ConversationState())
        val blocked = mutableStateOf(false)
        var stops = 0
        val attribution = localizedAttribution()
        compose.setContent { MaterialTheme {
            ConversationScreen(state.value, emptyList(),
                if (blocked.value) ConversationPreparation.TRANSLATION else ConversationPreparation.IDLE,
                !blocked.value, false, {}, { stops++ }, provider = MlKitTranslationEngine.descriptor,
                configurationProblem = if (blocked.value) ConfigurationProblem.UNKNOWN_MODEL else null,
                languageChanging = blocked.value, systemRecognition = true,
                recognitionReadiness = if (blocked.value) SpeechRecognitionReadiness.MISSING_LOCAL_MODEL else SpeechRecognitionReadiness.READY)
        } }
        val badge = compose.onNodeWithContentDescription(attribution)
        val action = compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION)
        val badgeBounds = badge.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val actionBounds = action.assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        fun assertFixedFooter() {
            compose.onAllNodesWithContentDescription(attribution, useUnmergedTree = true).assertCountEquals(1)
            val actualBadge = badge.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val actualAction = action.assertIsDisplayed().assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertBoundsUnchanged(badgeBounds, actualBadge)
            assertBoundsUnchanged(actionBounds, actualAction)
            assertTrue("The badge must remain above the action", actualBadge.bottom <= actualAction.top)
            if (state.value.sessionActive || state.value.phase == ConversationPhase.ERROR) {
                val phase = compose.onNodeWithTag(ConversationTestTags.PHASE).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("Variable phase text must remain above the badge/action pair", phase.bottom <= actualBadge.top)
            }
        }
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).assertExists()
        compose.runOnIdle { state.value = ConversationState(sessionActive = true, phase = ConversationPhase.LISTENING) }
        assertFixedFooter()
        action.assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).assertExists()
        val previous = ConversationTranscript(1, original = "The previous passenger asked for a return ticket.",
            translation = "Предыдущий пассажир попросил билет туда и обратно.", status = ConversationTranscriptStatus.COMPLETE)
        val pending = ConversationTranscript(2, original = "Please keep the full request available. ".repeat(24),
            status = ConversationTranscriptStatus.TRANSLATING)
        val translated = pending.copy(translation = "Пожалуйста, сохраните весь перевод доступным. ".repeat(24),
            status = ConversationTranscriptStatus.COMPLETE)
        for ((phase, row) in listOf(ConversationPhase.TRANSLATING to pending,
            ConversationPhase.LISTENING to translated,
            ConversationPhase.ERROR to translated.copy(status = ConversationTranscriptStatus.ERROR,
                failure = ConversationFailure.PLAYBACK_FAILED))) {
            compose.runOnIdle { state.value = state.value.copy(phase = phase, transcripts = listOf(previous, row)) }
            compose.onNodeWithTag(ConversationTestTags.EMPTY_STATE).assertDoesNotExist()
            assertFixedFooter()
            action.assertIsEnabled()
            compose.onNodeWithText(row.translation ?: row.original).performScrollTo().assertIsDisplayed()
            assertFixedFooter()
            compose.onNodeWithText(requireNotNull(previous.translation)).performScrollTo().assertIsDisplayed()
            assertTrue("Long turns must scroll the workspace",
                compose.onNodeWithTag(ConversationTestTags.CONTENT).fetchSemanticsNode()
                    .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertFixedFooter()
        }
        compose.runOnIdle { blocked.value = true }
        assertFixedFooter()
        action.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            state.value = ConversationState(transcripts = listOf(previous,
                translated.copy(status = ConversationTranscriptStatus.INTERRUPTED)))
        }
        assertFixedFooter()
        action.assertIsNotEnabled()
        compose.runOnIdle { blocked.value = false }
        assertFixedFooter()
        action.assertIsEnabled()
    }

    private fun assertBoundsUnchanged(expected: Rect, actual: Rect) {
        assertEquals("Footer left moved within a stable viewport", expected.left, actual.left, 1f)
        assertEquals("Footer top moved within a stable viewport", expected.top, actual.top, 1f)
        assertEquals("Footer right moved within a stable viewport", expected.right, actual.right, 1f)
        assertEquals("Footer bottom moved within a stable viewport", expected.bottom, actual.bottom, 1f)
    }

    private fun localizedAttribution(languageTag: String? = null): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (languageTag == null) return context.getString(R.string.google_translate_attribution)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
        }
        return context.createConfigurationContext(configuration).getString(R.string.google_translate_attribution)
    }

    @Composable
    private fun Screen(
        state: ConversationState,
        preparation: ConversationPreparation,
        ready: Boolean,
        permissionDenied: Boolean = false,
        onSessionAction: () -> Unit = {},
    ) {
        MaterialTheme {
            ConversationScreen(
                state = state,
                models = readyModels(),
                preparation = preparation,
                ready = ready,
                permissionDenied = permissionDenied,
                onPrepare = {},
                onSessionAction = onSessionAction,
            )
        }
    }

    private fun readyModels() = SpeechModelRole.entries.map {
        SpeechModelStatus(it.name, it.name, "https://example.test", "test", 1_000_000, SpeechModelInstallState.READY,
            role = it, languages = setOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH))
    }
}
