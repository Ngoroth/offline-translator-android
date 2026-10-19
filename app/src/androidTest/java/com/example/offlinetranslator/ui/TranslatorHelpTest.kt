package com.example.offlinetranslator.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.R
import org.junit.Assert.assertEquals
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.conversation.ConversationPreparation
import com.example.offlinetranslator.conversation.ConversationScreen
import com.example.offlinetranslator.conversation.ConversationTestTags
import com.example.offlinetranslator.core.ConversationState
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine
import com.example.offlinetranslator.translation.ModelUiStatus
import com.example.offlinetranslator.translation.TranslationScreen
import com.example.offlinetranslator.translation.TranslationTestTags
import com.example.offlinetranslator.translation.TranslationUiState
import java.util.Locale
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TranslatorHelpTest {
    @get:Rule val compose = createComposeRule()

    private data class LayoutCase(val conversation: Boolean, val locale: String, val fontScale: Float)

    @Test fun helpIsBelowAllLanguageControlsAndRightAlignedInBothModesLocalesAndFontScales() {
        val cases = listOf(false, true).flatMap { conversation ->
            listOf("en", "ru").flatMap { locale ->
                listOf(1f, 2f).map { LayoutCase(conversation, locale, it) }
            }
        }
        val selected = mutableStateOf(cases.first())
        var pixelsPerDp = 1f
        compose.setContent {
            val fixture = selected.value
            val config = Configuration(LocalConfiguration.current).apply {
                fontScale = fixture.fontScale
                setLocales(LocaleList(Locale.forLanguageTag(fixture.locale)))
            }
            val context = LocalContext.current.createConfigurationContext(config)
            val density = LocalDensity.current
            pixelsPerDp = density.density
            CompositionLocalProvider(
                LocalConfiguration provides config,
                LocalContext provides context,
                LocalResources provides context.resources,
                LocalDensity provides Density(density.density, fixture.fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(420.dp).height(700.dp)) {
                        if (fixture.conversation) {
                            ConversationScreen(ConversationState(), emptyList(), ConversationPreparation.IDLE,
                                true, false, {}, {}, provider = MlKitTranslationEngine.descriptor)
                        } else {
                            TranslationScreen(TranslationUiState(modelStatus = ModelUiStatus.READY),
                                {}, {}, {}, {}, {}, homeControls = {}, provider = MlKitTranslationEngine.descriptor)
                        }
                    }
                }
            }
        }
        for (fixture in cases) {
            compose.runOnIdle { selected.value = fixture }
            val tags = if (fixture.conversation) listOf(ConversationTestTags.LANGUAGE_FIRST,
                ConversationTestTags.LANGUAGE_SECOND, ConversationTestTags.LANGUAGE_SWAP)
            else listOf(TranslationTestTags.LANGUAGE_FIRST, TranslationTestTags.LANGUAGE_SECOND, TranslationTestTags.SWAP)
            val controls = tags.map { compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
            val help = compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
            assertTrue("Help must follow the entire language area: $fixture", help.top >= controls.maxOf { it.bottom })
            assertTrue("Help must share the language area's right edge: $fixture",
                abs(help.right - controls.maxOf { it.right }) <= pixelsPerDp)
        }
    }


    @Test fun helpRemainsOpenWhenProviderArrivesAndLosesLegalBranchWhenProviderDisappears() {
        val provider = mutableStateOf<TranslationProviderDescriptor?>(null)
        compose.setContent { MaterialTheme { TranslatorHelp(conversation = false, provider = provider.value) } }
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).assertDoesNotExist()
        compose.runOnIdle { provider.value = MlKitTranslationEngine.descriptor }
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertIsDisplayed()
        compose.runOnIdle { provider.value = null }
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertIsDisplayed()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.CLOSE).performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.DIALOG).assertDoesNotExist()
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).assertIsDisplayed()
    }
    @Test fun otherProviderTermsUseOnlyItsOwnAttributionAndLegalDestination() {
        val other = MlKitTranslationEngine.descriptor.copy(id = "isolated-descriptor", displayName = "Other translator",
            provider = "Other", attribution = "Other translator attribution", termsUrl = "https://example.test/terms")
        val opened = mutableListOf<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent { MaterialTheme {
            TranslatorHelp(conversation = true, provider = other, onOpenUri = opened::add)
        } }
        compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performClick()
        compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().performClick()
        compose.onNodeWithText(other.attribution).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.google_disclaimer)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.google_translate_attribution)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.google_translate_link)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.google_attribution_requirements_link)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.provider_terms)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(other.termsUrl), opened) }
    }

}
