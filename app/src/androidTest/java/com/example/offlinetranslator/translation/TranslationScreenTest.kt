package com.example.offlinetranslator.translation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.tencent.TencentTranslationEngine
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.core.ConfigurationProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.example.offlinetranslator.R
import com.example.offlinetranslator.TranslatorHomeLayout
import org.junit.Rule
import com.example.offlinetranslator.ui.TranslatorHelpTestTags
import com.example.offlinetranslator.ui.pressSystemBack
import org.junit.Test
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import java.util.Locale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import com.example.offlinetranslator.core.TranslationEngine

class TranslationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianChooserKeepsAllLanguagesReachableAndDismissalNoOpAndAtomicSwap() {
        val pair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        val selections = mutableListOf<LanguagePair>()
        var sourceDescription = ""
        var targetDescription = ""
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = 260
                fontScale = 2f
                setLocales(LocaleList(Locale.forLanguageTag("ru")))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources, LocalDensity provides Density(density.density, config.fontScale)) {
                sourceDescription = "${context.getString(R.string.language_source)}: ${com.example.offlinetranslator.ui.languageName(pair.source)}"
                targetDescription = "${context.getString(R.string.language_target)}: ${com.example.offlinetranslator.ui.languageName(pair.target)}"
                MaterialTheme {
                    TranslationScreen(TranslationUiState(direction = pair, input = "Je ne prendrai pas le bus.", modelStatus = ModelUiStatus.READY),
                        {}, selections::add, {}, {}, {}, homeControls = {})
                }
            }
        }
        val first = compose.onNodeWithTag(TranslationTestTags.LANGUAGE_FIRST)
        first.performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_option_fr").performScrollTo().assertIsSelected()
        LanguageCatalog.entries.forEach { metadata ->
            compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_option_${metadata.id.value}")
                .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_option_pt").performScrollTo().assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals(listOf(LanguagePair(LanguageCatalog.PORTUGUESE, LanguageCatalog.SPANISH)), selections) }
        first.performClick()
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_close").performClick()
        val beforeBack = compose.runOnIdle { selections.toList() }
        first.performClick()
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_chooser").assertIsDisplayed()
        pressSystemBack(compose, "${TranslationTestTags.LANGUAGE_FIRST}_chooser")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("${TranslationTestTags.LANGUAGE_FIRST}_chooser")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_chooser").assertDoesNotExist()
        first.assertIsDisplayed()
        compose.runOnIdle { assertEquals(beforeBack, selections) }
        first.performClick()
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_FIRST}_option_fr").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, selections.size) }
        compose.onNodeWithTag(TranslationTestTags.LANGUAGE_SECOND).performScrollTo().performClick()
        compose.onNodeWithTag("${TranslationTestTags.LANGUAGE_SECOND}_option_fr").performScrollTo().performClick()
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(LanguagePair(LanguageCatalog.PORTUGUESE, LanguageCatalog.SPANISH), pair.reverse(), pair.reverse()), selections) }
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("Je ne prendrai pas le bus.")
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertContentDescriptionEquals(sourceDescription)
        compose.onNodeWithTag(TranslationTestTags.OUTPUT).performScrollTo().assertContentDescriptionEquals(targetDescription)
    }

    @Test fun missingStateOffersPreparationOnly() {
        show(TranslationUiState(modelStatus = ModelUiStatus.MISSING))
        compose.onNodeWithTag(TranslationTestTags.PREPARE).assertIsEnabled()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertDoesNotExist()
    }

    @Test fun checkingStateShowsProgressAndBlocksPrimaryActions() {
        show(TranslationUiState(modelStatus = ModelUiStatus.CHECKING))

        compose.onNodeWithTag(TranslationTestTags.PROGRESS).assertExists()
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertIsNotEnabled()
        compose.onNodeWithTag(TranslationTestTags.PREPARE).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertDoesNotExist()
    }

    @Test fun downloadingAndTranslatingHaveProgressAndBlockActions() {
        val state = show(TranslationUiState(modelStatus = ModelUiStatus.DOWNLOADING))
        compose.onNodeWithTag(TranslationTestTags.PROGRESS).assertExists()
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertIsNotEnabled()

        compose.runOnIdle {
            state.value = TranslationUiState(
                input = "hello",
                modelStatus = ModelUiStatus.READY,
                isTranslating = true,
            )
        }
        compose.onNodeWithTag(TranslationTestTags.PROGRESS).assertExists()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
    }

    @Test fun readyResultIsVisibleAndTranslateEnabled() {
        show(
            TranslationUiState(
                direction = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN),
                input = "hello",
                result = "привет",
                modelStatus = ModelUiStatus.READY,
            ),
        )
        compose.onNodeWithText("привет").assertExists()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsEnabled()
    }

    @Test fun englishInputValidationKeepsAcceptedTextAndResultWithoutRetry() {
        checkLocalizedInputValidation("en", compact = false)
    }

    @Test fun russianInputValidationRemainsReachableAtCompactLargeFont() {
        checkLocalizedInputValidation("ru", compact = true)
    }

    private fun checkLocalizedInputValidation(language: String, compact: Boolean) {
        val state = mutableStateOf(TranslationUiState(
            input = "Accepted text", result = "Accepted result",
            modelStatus = ModelUiStatus.READY, inputTooLong = true,
        ))
        var message = ""
        lateinit var localizedResources: android.content.res.Resources
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
                if (compact) { screenHeightDp = 260; fontScale = 2f }
            }
            val context = LocalContext.current.createConfigurationContext(config)
            localizedResources = context.resources
            val density = LocalDensity.current
            message = context.getString(R.string.error_input_too_long, TranslationEngine.MAX_INPUT_CHARACTERS)
            CompositionLocalProvider(
                LocalConfiguration provides config, LocalContext provides context,
                LocalResources provides context.resources,
                LocalDensity provides Density(density.density, config.fontScale),
            ) {
                MaterialTheme {
                    Box(if (compact) Modifier.fillMaxWidth().height(260.dp) else Modifier.fillMaxWidth()) {
                        TranslationScreen(
                            state.value,
                            { state.value = state.value.copy(input = it, inputTooLong = false, result = "") },
                            {}, {}, {}, {}, homeControls = {}, provider = MlKitTranslationEngine.descriptor,
                        )
                    }
                }
            }
        }
        val action = compose.onNodeWithTag(TranslationTestTags.TRANSLATE)
            .assertIsDisplayed().assertIsNotEnabled()
        val actionBefore = action.fetchSemanticsNode().boundsInRoot
        val badgeBefore = assertGoogleAboveAction(localizedResources)
        val field = compose.onNodeWithTag(TranslationTestTags.INPUT)
        field.performScrollTo().assertTextContains("Accepted text")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals("Accepted result")
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        assertSameBounds("Validation must not move Translate", actionBefore, action.fetchSemanticsNode().boundsInRoot)
        assertSameBounds("Validation must not move attribution", badgeBefore, assertGoogleAboveAction(localizedResources))
        field.performScrollTo().performTextReplacement("Corrected text")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        field.assertTextContains("Corrected text")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        action.assertIsEnabled()
    }

    @Test fun allRecoverableErrorsOfferRetry() {
        val state = show(
            TranslationUiState(modelStatus = ModelUiStatus.MISSING, error = TranslationUiError.WIFI_REQUIRED),
        )
        TranslationUiError.entries.forEachIndexed { index, error ->
            if (index > 0) compose.runOnIdle {
                state.value = TranslationUiState(modelStatus = ModelUiStatus.MISSING, error = error)
            }
            compose.onNodeWithTag(TranslationTestTags.RETRY).assertIsEnabled()
        }
    }

    @Test fun swapPreservesInputAndClearsStaleResult() {
        val state = mutableStateOf(
            TranslationUiState(
                direction = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
                input = "привет",
                result = "hello",
                modelStatus = ModelUiStatus.READY,
            ),
        )
        compose.setContent {
            MaterialTheme {
                TranslationScreen(
                    state = state.value,
                    onInputChanged = {},
                    onPairSelected = { selected ->
                        state.value = state.value.copy(
                            direction = selected,
                            result = "",
                            error = null,
                        )
                    },
                    onPrepare = {},
                    onTranslate = {},
                    onRetry = {},
                    homeControls = {},
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertContentDescriptionEquals(
            "${context.getString(R.string.language_source)}: Russian",
        )
        compose.onNodeWithTag(TranslationTestTags.OUTPUT).assertContentDescriptionEquals(
            "${context.getString(R.string.language_target)}: English",
        )
        compose.onNodeWithText("hello").assertExists()

        compose.onNodeWithTag(TranslationTestTags.SWAP).performClick()

        compose.runOnIdle {
            assertEquals(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN), state.value.direction)
        }
        compose.onNodeWithTag(TranslationTestTags.LANGUAGE_FIRST).assertTextContains("English")
        compose.onNodeWithTag(TranslationTestTags.LANGUAGE_SECOND).assertTextContains("Russian")
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertTextContains("привет")
            .assertContentDescriptionEquals("${context.getString(R.string.language_source)}: English")
        compose.onNodeWithTag(TranslationTestTags.OUTPUT).assertContentDescriptionEquals(
            "${context.getString(R.string.language_target)}: Russian",
        )
        compose.onNodeWithText("hello").assertDoesNotExist()
    }

    @Test fun helpTermsOpenBothPublicLinksAndReturnWithoutLosingInputOrResult() {
        val state = mutableStateOf(TranslationUiState(
            input = "hello", result = "привет", modelStatus = ModelUiStatus.READY,
        ))
        val openedUris = mutableListOf<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                TranslationScreen(
                    state = state.value,
                    onInputChanged = { state.value = state.value.copy(input = it) },
                    onPairSelected = {},
                    onPrepare = {},
                    onTranslate = {},
                    onRetry = {},
                    homeControls = {},
                    onOpenUri = openedUris::add,
                    provider = MlKitTranslationEngine.descriptor,
                )
            }
        }

        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performTextReplacement("hello again")
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.runOnIdle { assertTrue(openedUris.isEmpty()) }
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertIsDisplayed()
        clickVisible(compose.onNodeWithText(context.getString(R.string.google_translate_link)).performScrollTo().assertHeightIsAtLeast(48.dp))
        clickVisible(compose.onNodeWithText(context.getString(R.string.usage_terms_link)).performScrollTo().assertHeightIsAtLeast(48.dp))
        clickVisible(compose.onNodeWithText(context.getString(R.string.google_attribution_requirements_link)).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "https://translate.google.com",
                    "https://developers.google.com/ml-kit/terms",
                    "https://docs.cloud.google.com/translate/attribution",
                ),
                openedUris,
            )
        }
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_BACK).assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo())
        pressSystemBack(compose, TranslatorHelpTestTags.TERMS_DIALOG)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(TranslatorHelpTestTags.DIALOG).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 &&
                compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).isDisplayed()
        }
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        pressSystemBack(compose, TranslatorHelpTestTags.DIALOG)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(TranslatorHelpTestTags.DIALOG).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("hello again")
        compose.onNodeWithText("привет").performScrollTo().assertIsDisplayed()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo())
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("hello again")
        compose.onNodeWithText("привет").performScrollTo().assertIsDisplayed()
    }


    @Test fun shortLargeFontGermanPairKeepsControlsReachableAndInvalidPairBlocksInference() {
        val state = mutableStateOf(TranslationUiState(direction = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN),
            input = "Meet at five", modelStatus = ModelUiStatus.READY))
        var swaps = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().height(260.dp)) {
                        TranslationScreen(state.value, {}, { swaps++ }, {}, {}, {},
                            homeControls = {}, provider = MlKitTranslationEngine.descriptor)
                    }
                }
            }
        }
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsDisplayed().assertIsEnabled()
        assertGoogleAboveAction()
        compose.runOnIdle {
            assertEquals(1, swaps)
            state.value = state.value.copy(configurationProblem = ConfigurationProblem.UNSUPPORTED_PAIR)
        }
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
    }

    @Test fun compactLargeFontViewportKeepsEditorActionAndHelpReachable() {
        val state = mutableStateOf(TranslationUiState(
            direction = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
            input = "Исходный текст", result = "Translated text", modelStatus = ModelUiStatus.READY,
        ))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            val density = LocalDensity.current
            val ime = WindowInsets.ime
            val navigation = WindowInsets.navigationBars
            val imeOcclusion = with(density) {
                (ime.getBottom(this) - navigation.getBottom(this)).coerceAtLeast(0).toDp()
            }
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.fillMaxWidth().height(260.dp + imeOcclusion)) {
                        TranslatorHomeLayout(
                            conversation = false,
                            onConversationChanged = {},
                            onOpenSettings = {},
                            conversationContent = {},
                            textContent = { homeControls ->
                                TranslationScreen(
                                    state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {},
                                    homeControls = homeControls,
                                    provider = MlKitTranslationEngine.descriptor,
                                )
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("settings_button").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertIsDisplayed()
            .performTextReplacement("Новый текст")
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertTextContains("Новый текст")
        val action = compose.onNodeWithTag(TranslationTestTags.TRANSLATE)
            .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        val before = action.fetchSemanticsNode().boundsInRoot
        val badgeBefore = assertGoogleAboveAction()
        assertTrue("The visible action must retain a 48dp hit area",
            before.height >= 48f * context.resources.displayMetrics.density)
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertIsDisplayed()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_BACK).assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("Новый текст")
        compose.onNodeWithText("Translated text").performScrollTo().assertIsDisplayed()
        val after = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertSameBounds("Scrolling must not move the primary action", before, after)
        assertSameBounds("Scrolling must not move the badge", badgeBefore, assertGoogleAboveAction())
    }

    private fun clickVisible(node: SemanticsNodeInteraction) {
        node.assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }

    @Test fun absentProviderKeepsHelpAvailableWithoutFalseGoogleAttributionOrTerms() {
        compose.setContent { MaterialTheme {
            TranslationScreen(
                TranslationUiState(input = "hello", result = "привет", modelStatus = ModelUiStatus.READY),
                {}, {}, {}, {}, {}, homeControls = {}, provider = null,
            )
        } }
        compose.onNodeWithContentDescription("Powered by Google Translate").assertDoesNotExist()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().assertHeightIsAtLeast(48.dp))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Powered by Google Translate").assertDoesNotExist()
        clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE))
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("hello")
        compose.onNodeWithText("привет").performScrollTo().assertIsDisplayed()
    }

    @Test fun mlKitFooterStaysAboveNeutralActionAcrossEmptyPendingResultErrorAndRetry() {
        val request = "Keep this request"
        val translated = "Keep this result"
        val state = mutableStateOf(TranslationUiState(modelStatus = ModelUiStatus.READY))
        compose.setContent { MaterialTheme {
            TranslationScreen(state.value, {}, {}, {}, {}, { state.value = state.value.copy(error = null) },
                homeControls = {}, provider = MlKitTranslationEngine.descriptor)
        } }
        val action = compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
        val badgeBefore = assertGoogleAboveAction()
        val actionBefore = action.fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { state.value = state.value.copy(input = request) }
        action.assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(isTranslating = true) }
        action.assertIsNotEnabled()
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertIsNotEnabled().assertTextContains(request)
        assertSameBounds("Pending must not move the badge", badgeBefore, assertGoogleAboveAction())
        assertSameBounds("Pending must not move Translate", actionBefore, action.fetchSemanticsNode().boundsInRoot)

        compose.runOnIdle { state.value = state.value.copy(isTranslating = false, result = translated) }
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals(translated)
        action.assertIsEnabled()
        assertSameBounds("Result must not move the badge", badgeBefore, assertGoogleAboveAction())
        assertSameBounds("Result must not move Translate", actionBefore, action.fetchSemanticsNode().boundsInRoot)

        compose.runOnIdle { state.value = state.value.copy(error = TranslationUiError.TRANSLATION_FAILED) }
        val retry = compose.onNodeWithTag(TranslationTestTags.RETRY).assertIsDisplayed().assertIsEnabled()
        val badgeAfterError = assertGoogleAboveAction()
        assertTrue("Retry belongs above the badge/action pair", retry.fetchSemanticsNode().boundsInRoot.bottom <= badgeAfterError.top + 1f)
        assertSameBounds("Error/Retry must not move the badge", badgeBefore, badgeAfterError)
        assertSameBounds("Error/Retry must not move Translate", actionBefore, action.assertIsEnabled().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(request)
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals(translated)
        clickVisible(retry)
        retry.assertDoesNotExist()
        assertSameBounds("Retry recovery must not move the badge", badgeBefore, assertGoogleAboveAction())
        assertSameBounds("Retry recovery must not move Translate", actionBefore, action.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(request)
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals(translated)
    }

    @Test fun nullAndTencentProviderKeepNeutralActionAndPreserveInputAndResultWithoutGoogle() {
        val provider = mutableStateOf<TranslationProviderDescriptor?>(null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val google = context.getString(R.string.google_translate_attribution)
        val state = TranslationUiState(input = "Keep this request", result = "Keep this result",
            modelStatus = ModelUiStatus.READY, provider = MlKitTranslationEngine.descriptor)
        compose.setContent { MaterialTheme {
            TranslationScreen(state, {}, {}, {}, {}, {}, homeControls = {}, provider = provider.value)
        } }
        compose.onNodeWithContentDescription(google).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsEnabled()
            .assertTextEquals(context.getString(R.string.translate_action))
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("Keep this request")
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals("Keep this result")
        compose.runOnIdle {
            provider.value = TencentTranslationEngine.descriptor
        }
        compose.onNodeWithContentDescription(google).assertDoesNotExist()
        compose.onNodeWithText(TencentTranslationEngine.descriptor.attribution).assertIsDisplayed()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsEnabled()
            .assertTextEquals(context.getString(R.string.translate_action))
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains("Keep this request")
        compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT).performScrollTo().assertTextEquals("Keep this result")
    }

    @Test fun realImeKeepsTranslateFixedWhileLongEditorAndResultScroll() {
        val original = "A long editable request with enough lines to scroll.\n".repeat(30)
        val translated = "Длинный перевод остаётся доступным при открытой клавиатуре.\n".repeat(30)
        val state = mutableStateOf(TranslationUiState(input = original, result = translated, modelStatus = ModelUiStatus.READY))
        var keyboard: SoftwareKeyboardController? = null
        var imeBottom = 0
        lateinit var view: android.view.View
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            keyboard = LocalSoftwareKeyboardController.current
            imeBottom = WindowInsets.ime.getBottom(density)
            view = LocalView.current
            TranslationScreen(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {},
                homeControls = {}, provider = MlKitTranslationEngine.descriptor)
        } }
        try {
            compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performClick()
            compose.runOnIdle { keyboard?.show() }
            compose.waitUntil(10_000) { imeBottom > 0 }
            val action = compose.onNodeWithTag(TranslationTestTags.TRANSLATE)
                .assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            val before = action.fetchSemanticsNode().boundsInRoot
            val badgeBefore = assertGoogleAboveAction()
            compose.runOnIdle {
                val visible = android.graphics.Rect()
                val location = IntArray(2)
                view.getWindowVisibleDisplayFrame(visible)
                view.getLocationOnScreen(location)
                assertTrue("Translate must be above the actual keyboard", location[1] + before.bottom <= visible.bottom + 1f)
                assertTrue("The badge must be above the actual keyboard", location[1] + badgeBefore.bottom <= visible.bottom + 1f)
            }
            compose.onNodeWithText(translated).performScrollTo().assertIsDisplayed()
            val after = action.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertSameBounds("Scrolling result must not move the action", before, after)
            assertSameBounds("Scrolling result must not move the badge", badgeBefore, assertGoogleAboveAction())
            compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(original)
            clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo())
            compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
            compose.waitUntil(10_000) { imeBottom == 0 }
            clickVisible(compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo())
            compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertIsDisplayed()
            pressSystemBack(compose, TranslatorHelpTestTags.TERMS_DIALOG)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(TranslatorHelpTestTags.TERMS_DIALOG)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
                    compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).isDisplayed()
            }
            pressSystemBack(compose, TranslatorHelpTestTags.DIALOG)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(TranslatorHelpTestTags.DIALOG)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
            }
            compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(original)
            compose.onNodeWithText(translated).performScrollTo().assertIsDisplayed()
        } finally {
            androidx.test.espresso.Espresso.closeSoftKeyboard()
        }
    }

    private fun assertGoogleAboveAction(
        resources: android.content.res.Resources = InstrumentationRegistry.getInstrumentation().targetContext.resources,
    ): Rect {
        val google = resources.getString(R.string.google_translate_attribution)
        compose.onAllNodesWithContentDescription(google).assertCountEquals(1)
        val badge = compose.onNodeWithContentDescription(google).assertIsDisplayed()
            .assertHeightIsEqualTo(18.dp).fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsDisplayed()
            .assertTextEquals(resources.getString(R.string.translate_action)).fetchSemanticsNode().boundsInRoot
        assertTrue("The badge must be above Translate", badge.bottom <= action.top + 1f)
        assertEquals("The badge must be centered above Translate", action.center.x, badge.center.x, 1f)
        compose.onNode(hasTestTag(TranslationTestTags.OUTPUT) and hasAnyDescendant(hasContentDescription(google)))
            .assertDoesNotExist()
        return badge
    }

    private fun assertSameBounds(message: String, expected: Rect, actual: Rect) {
        assertEquals("$message (left)", expected.left, actual.left, 1f)
        assertEquals("$message (top)", expected.top, actual.top, 1f)
        assertEquals("$message (right)", expected.right, actual.right, 1f)
        assertEquals("$message (bottom)", expected.bottom, actual.bottom, 1f)
    }

    private fun show(state: TranslationUiState): MutableState<TranslationUiState> {
        val current = mutableStateOf(state)
        compose.setContent {
            MaterialTheme {
                TranslationScreen(current.value, {}, {}, {}, {}, {}, homeControls = {}, provider = MlKitTranslationEngine.descriptor)
            }
        }
        return current
    }
}
