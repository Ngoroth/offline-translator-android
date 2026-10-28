package com.example.offlinetranslator.ui

import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.MainActivity
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.AppRuntime
import com.example.offlinetranslator.runtime.commitNativeSettings
import com.example.offlinetranslator.settings.SettingsTestTags
import com.example.offlinetranslator.settings.SettingsViewModel
import com.example.offlinetranslator.translation.TranslationTestTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual MainActivity, native configuration delivery and prepared ML Kit; no downloads or microphone. */
class InterfaceLanguageNativeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val localeManager = requireNotNull(context.getSystemService(LocaleManager::class.java))
    private lateinit var originalLocales: LocaleList
    private lateinit var settingsFile: File
    private var originalSettingsBytes: ByteArray? = null
    private lateinit var runtime: AppRuntime
    private var scenario: ActivityScenario<MainActivity>? = null
    private val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    @Before fun retainPlatformAndSettings() = runBlocking {
        originalLocales = localeManager.applicationLocales
        runtime = AppRuntime.get(context)
        settingsFile = runtime.settings.file
        originalSettingsBytes = if (settingsFile.exists()) settingsFile.readBytes() else null
        val original = runtime.settings.current()
        commitNativeSettings(runtime, original.copy(
            languagePair = pair,
            translationEngineId = "mlkit",
            translationSetupComplete = true,
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
        ))
        val engine = runtime.registry.create("mlkit", setOf(pair))
        try {
            assertEquals("Prepared RU/EN required; this regression never downloads", ModelAvailability.READY,
                engine.modelAvailability())
        } finally {
            engine.closeAndJoin()
        }
        instrumentation.runOnMainSync { InterfaceLanguage.apply(context, LanguageCatalog.ENGLISH) }
    }

    @After fun restorePlatformAndExactSettings() {
        try {
            scenario?.close()
            scenario = null
            if (::runtime.isInitialized) runBlocking { runtime.closeAndJoin() }
        } finally {
            try {
                if (::settingsFile.isInitialized) {
                    originalSettingsBytes?.let { settingsFile.writeBytes(it) }
                        ?: check(!settingsFile.exists() || settingsFile.delete())
                }
            } finally {
                if (::originalLocales.isInitialized) {
                    instrumentation.runOnMainSync { localeManager.applicationLocales = originalLocales }
                }
            }
        }
    }

    @Test fun localeChangeRetainsActualTextResultOwnerAndAdvancedDraftAndPersistsAfterRelaunch() {
        launchActivity()
        awaitLocale(LanguageCatalog.ENGLISH)
        compose.onNodeWithText(activityString(R.string.mode_text)).performClick()
        compose.waitUntil(45_000) {
            compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty()
        }
        // Arbitrary synthetic input is never injected into a ViewModel or saved as product history.
        val input = "Анна не поедет на автобусе № 17 завтра в 15:00. Заказ ZX-42, два билета."
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performTextReplacement(input)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsEnabled().performClick()
        compose.waitUntil(120_000) {
            compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                resultText().let { it.isNotBlank() && it != activityString(R.string.result_placeholder) }
        }
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        val result = resultText()
        assertNotEquals("Output must be a genuine translated result, not the entered fixture", input, result)
        val ownerVersion = runtime.owners.ownershipVersion.value
        val committed = runBlocking { runtime.settings.current() }
        val draftSpeed = if (committed.ttsSpeedPercent == 115) 120 else 115
        var originalActivity: MainActivity? = null
        requireNotNull(scenario).onActivity { originalActivity = it }

        compose.onNodeWithTag("settings_button").performClick()
        choose(SettingsTestTags.SPEED, draftSpeed.toString())
        choose(SettingsTestTags.INTERFACE_LANGUAGE, LanguageCatalog.RUSSIAN.value)
        awaitLocale(LanguageCatalog.RUSSIAN)
        assertRetainedDraft(originalActivity, draftSpeed, committed, ownerVersion)

        // Exercise a real external platform selection, not just the in-app helper's setter.
        instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.forLanguageTags("en") }
        awaitLocale(LanguageCatalog.ENGLISH)
        assertRetainedDraft(originalActivity, draftSpeed, committed, ownerVersion)
        choose(SettingsTestTags.INTERFACE_LANGUAGE, LanguageCatalog.RUSSIAN.value)
        awaitLocale(LanguageCatalog.RUSSIAN)
        assertRetainedDraft(originalActivity, draftSpeed, committed, ownerVersion)

        // Back dismisses only the top locale menu, then Settings; neither commits the draft.
        compose.onNodeWithTag(SettingsTestTags.INTERFACE_LANGUAGE).performScrollTo().performClick()
        compose.onNodeWithTag("${SettingsTestTags.INTERFACE_LANGUAGE}_option_ru").assertExists()
        pressSystemBack(compose, "${SettingsTestTags.INTERFACE_LANGUAGE}_option_ru")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("${SettingsTestTags.INTERFACE_LANGUAGE}_option_ru")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag(SettingsTestTags.BODY).assertIsDisplayed()
        assertRetainedDraft(originalActivity, draftSpeed, committed, ownerVersion)
        pressSystemBack(compose, SettingsTestTags.BODY)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(SettingsTestTags.BODY)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(input)
        assertEquals("Exact real SDK output survives foreground locale delivery", result, resultText())
        assertEquals(ownerVersion, runtime.owners.ownershipVersion.value)
        assertEquals(committed, runBlocking { runtime.settings.current() })
        assertEquals("Back must not cancel the independent interface selection", "ru", localeManager.applicationLocales.toLanguageTags())
        compose.onNodeWithTag("settings_button").performClick()
        compose.onNodeWithTag(SettingsTestTags.BODY).assertIsDisplayed()
        requireNotNull(scenario).onActivity {
            assertEquals("Back discards only the advanced draft", committed,
                ViewModelProvider(it)[SettingsViewModel::class.java].state.value.draft)
        }
        pressSystemBack(compose, SettingsTestTags.BODY)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(SettingsTestTags.BODY)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }

        // Reopened windows must not route Back to the Activity behind Help.
        repeat(3) {
            compose.onNodeWithTag(TranslatorHelpTestTags.ACTION).performScrollTo().performClick()
            compose.onNodeWithTag(TranslatorHelpTestTags.TERMS_ACTION).performScrollTo().performClick()
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
            requireNotNull(scenario).onActivity { assertSame(originalActivity, it) }
            compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().assertTextContains(input)
            assertEquals(result, resultText())
            assertEquals(ownerVersion, runtime.owners.ownershipVersion.value)
            assertEquals(committed, runBlocking { runtime.settings.current() })
        }

        // Activity relaunch proves persisted platform selection; process-restart proof is the final device gate.
        requireNotNull(scenario).close()
        scenario = null
        runBlocking { runtime.closeAndJoin() }
        runtime = AppRuntime.get(context)
        launchActivity()
        awaitLocale(LanguageCatalog.RUSSIAN)
        compose.onNodeWithText(activityString(R.string.mode_conversation)).assertExists()
        assertEquals("ru", localeManager.applicationLocales.toLanguageTags())
        assertEquals(committed, runBlocking { runtime.settings.current() })
    }

    @Test fun emptyNativePreferenceLaunchesEnglishRatherThanFollowingDeviceLanguage() {
        instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.getEmptyLocaleList() }
        launchActivity()
        awaitLocale(LanguageCatalog.ENGLISH)
        compose.onNodeWithText(activityString(R.string.mode_conversation)).assertExists()
        assertEquals("en", localeManager.applicationLocales.toLanguageTags())
        // The same test can run under the non-English device configuration established by the final gate.
    }

    @Test fun existingScriptlessSimplifiedChineseNativePreferenceDrawsLocalizedHomeWithoutRewrite() {
        instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.forLanguageTags("zh-CN") }
        launchActivity()
        awaitLocale(LanguageCatalog.CHINESE)
        val localized = activityString(R.string.mode_conversation)
        val englishConfiguration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en"))
        }
        assertNotEquals("Scriptless Simplified Chinese must render its localized surface, not English fallback",
            context.createConfigurationContext(englishConfiguration).getString(R.string.mode_conversation), localized)
        compose.onNodeWithText(localized).assertIsDisplayed()
        assertEquals("Retain the supported native preference without a needless canonicalizing write",
            "zh-CN", localeManager.applicationLocales.toLanguageTags())
    }

    private fun assertRetainedDraft(
        originalActivity: MainActivity?, draftSpeed: Int, committed: AppSettings, ownerVersion: Long,
    ) {
        requireNotNull(scenario).onActivity {
            assertSame("Locale-only delivery must not recreate MainActivity", originalActivity, it)
            assertEquals(draftSpeed, ViewModelProvider(it)[SettingsViewModel::class.java].state.value.draft.ttsSpeedPercent)
        }
        compose.onNodeWithTag(SettingsTestTags.SPEED).performScrollTo().assertTextContains(draftSpeed.toString(), substring = true)
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
        assertEquals("Locale selection cannot rebuild the unchanged native owner", ownerVersion, runtime.owners.ownershipVersion.value)
        assertEquals("Independent interface choice cannot commit the advanced draft/pair", committed,
            runBlocking { runtime.settings.current() })
    }

    private fun launchActivity() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    private fun awaitLocale(language: LanguageId) {
        compose.waitUntil(30_000) {
            var matching = false
            requireNotNull(scenario).onActivity {
                matching = it.resources.configuration.locales[0].language == language.value &&
                    InterfaceLanguage.current(it) == language
            }
            matching
        }
        compose.waitForIdle()
    }

    private fun activityString(resource: Int): String {
        var value = ""
        requireNotNull(scenario).onActivity { value = it.getString(resource) }
        return value
    }

    private fun choose(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("${tag}_option_$value").performScrollTo().performClick()
    }

    private fun resultText(): String = compose.onAllNodesWithTag(
        TranslationTestTags.RESULT_TEXT, useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.joinToString("") { it.text }
}
