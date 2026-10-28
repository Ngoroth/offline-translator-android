package com.example.offlinetranslator.runtime

import android.net.ConnectivityManager
import android.system.Os
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.R
import com.example.offlinetranslator.TranslatorHome
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.settings.SettingsTestTags
import com.example.offlinetranslator.translation.TranslationTestTags
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.conversation.ConversationTestTags
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.CoroutineScope
import com.example.offlinetranslator.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Real composition and prepared providers; synthetic input only, no model downloads. */
class RuntimeSettingsRouteTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: AppRuntime
    private lateinit var original: AppSettings
    private val showHome = mutableStateOf(false)
    private var contentSet = false
    private var pacedCapture: ConversationProofFixtures.PacedCapture? = null
    private var captureScope: CoroutineScope? = null
    private var captureCloseBarrier: CaptureCloseBarrier? = null
    private val captureBuilds = AtomicInteger()
    private var expectedTeardownFailure: IllegalStateException? = null
    @Volatile private var failCaptureBuild = false
    private val enRu = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
    private val enDe = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)


    @Before fun saveSettings() = runBlocking {
        runtime = AppRuntime.get(context)
        original = runtime.settings.current()
        commitNativeSettings(runtime, AppSettings(translationEngineId = "mlkit", translationSetupComplete = true,
            speechOutputBackend = SpeechOutputBackend.PIPER, speechRecognitionBackend = SpeechRecognitionBackend.WHISPER))
        val pair = AppSettings().languagePair
        if (runtime.settings.current().languagePair != pair) assertTrue(runtime.updateLanguagePair(runtime.settings.current().languagePair, pair))
        requirePreparedTranslation(pair)
    }

    @After fun restoreSettings() {
        if (!::original.isInitialized) return
        captureCloseBarrier?.proceed?.complete(Unit)
        if (contentSet) compose.runOnIdle { showHome.value = false }
        captureScope?.cancel()
        captureScope = null
        pacedCapture = null
        runBlocking {
            try {
                runtime.closeAndJoin()
            } catch (failure: IllegalStateException) {
                val expected = expectedTeardownFailure
                val rootCause = generateSequence<Throwable>(failure) { it.cause }.last()
                if (!runtime.owners.teardownFailed.value || expected == null ||
                    rootCause.javaClass != expected.javaClass || rootCause.message != expected.message) throw failure
                assertEquals("Injected capture teardown failure", rootCause.message)
            }
            val restoring = AppRuntime.get(context)
            try {
                commitNativeSettings(restoring, original)
            } finally {
                restoring.closeAndJoin()
            }
        }
    }

    @Test fun textSurvivesConversationOnlyApply() {
        showText()
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current().languagePair == enRu && !runtime.busy.value } }
        translateSyntheticEnglish("автобус")
        val baseline = runBlocking { runtime.settings.current() }
        val retainedOwnerVersion = runtime.owners.ownershipVersion.value
        val result = compose.onNodeWithTag(TranslationTestTags.RESULT_TEXT, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }
        openSettings()
        choose(SettingsTestTags.SPEED, "110")
        choose(SettingsTestTags.OUTPUT_BACKEND, SpeechOutputBackend.SYSTEM_OFFLINE.persistedId)
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.SYSTEM_OFFLINE.persistedId)
        val service = checkNotNull(com.example.offlinetranslator.speech.AndroidRecognitionServices.choices(context)
            .firstOrNull { it.component != null }?.component) { "An installed recognition service is required for selection proof" }
        choose(SettingsTestTags.RECOGNITION_SERVICE, service)
        applyAndClose()
        runBlocking { assertEquals(baseline.copy(ttsSpeedPercent = 110, speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE, speechRecognitionService = service), runtime.settings.current()) }
        compose.onNodeWithText(result).assertExists()
        assertEquals("Conversation-only Apply must retain the actual Text owner", retainedOwnerVersion, runtime.owners.ownershipVersion.value)
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertTextContains("I will not take the bus.")
        translateSyntheticEnglish("автобус")
    }

    @Test fun oversizedTextIsRejectedAcrossOwnerRecreation() {
        showText()
        val accepted = "Синтетический текст"
        val initialPair = runBlocking { runtime.settings.current().languagePair }
        val field = compose.onNodeWithTag(TranslationTestTags.INPUT)
        awaitTextCondition("Editable Text route before setup") {
            compose.onAllNodes(hasTestTag(TranslationTestTags.LANGUAGE_FIRST) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.LANGUAGE_ERROR).fetchSemanticsNodes().isEmpty()
        }
        field.performScrollTo().performTextReplacement(accepted)
        awaitTextCondition("Accepted input belongs to a ready Text owner before rejection") {
            compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and hasText(accepted)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        field.performTextReplacement("x".repeat(4097))
        field.assertTextContains(accepted).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextReplacement(accepted)
        field.assertTextContains(accepted).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        field.assertTextContains(accepted).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()

        val version = runtime.owners.ownershipVersion.value
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().performClick()
        awaitTextCondition("Rejected edit survives the language-pair owner replacement") {
            runBlocking { runtime.settings.current().languagePair == initialPair.reverse() } &&
                runtime.owners.ownershipVersion.value > version &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty()
        }
        field.performScrollTo().assertTextContains(accepted)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()

        val competitor = object : RuntimeOwner {
            override val isBusy = false
            override val activity = MutableStateFlow(false)
            override fun interrupt() = Unit
            override suspend fun closeAndJoin(): Result<Unit> = Result.success(Unit)
        }
        runBlocking { runtime.owners.replace { competitor } }
        awaitTextCondition("Displaced Text owner becomes an editable ownerless route") {
            compose.onAllNodesWithTag(TranslationTestTags.LANGUAGE_ERROR).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        field.performScrollTo().assertTextContains(accepted)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextReplacement("Ownerless accepted text")
        field.assertTextContains("Ownerless accepted text")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        field.performTextReplacement("y".repeat(4097))
        field.assertTextContains("Ownerless accepted text")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        field.assertTextContains("Ownerless accepted text")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        field.performTextReplacement("Ownerless accepted text")
        field.assertTextContains("Ownerless accepted text")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        runBlocking {
            runtime.owners.release(competitor)
            assertTrue(runtime.updateLanguagePair(initialPair.reverse(), initialPair))
        }
        awaitTextCondition("Ownerless accepted input and validation reach the recreated owner") {
            compose.onAllNodesWithTag(TranslationTestTags.LANGUAGE_ERROR).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty()
        }
        field.performScrollTo().assertTextContains("Ownerless accepted text")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsNotEnabled()
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        field.performTextReplacement("Я не поеду на автобусе.")
        field.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsEnabled()
        clickTranslateAndWait("bus")
    }

    @Test fun independentAdvancedBackendApplyPersistsAcrossRestartWithoutChangingMainPair() {
        showText()
        val baseline = runBlocking { runtime.settings.current() }
        openSettings()
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.SYSTEM_OFFLINE.persistedId)
        applyAndClose()
        val android = baseline.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == android && !runtime.busy.value } }
        assertEquals(SpeechOutputBackend.PIPER, android.speechOutputBackend)
        openSettings()
        choose(SettingsTestTags.OUTPUT_BACKEND, SpeechOutputBackend.SYSTEM_OFFLINE.persistedId)
        applyAndClose()
        val independent = android.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == independent && !runtime.busy.value } }
        openSettings()
        choose(SettingsTestTags.RECOGNITION_BACKEND, SpeechRecognitionBackend.WHISPER.persistedId)
        applyAndClose()
        val final = independent.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == final && !runtime.busy.value } }
        assertEquals(baseline.languagePair, final.languagePair)
        hideHome()
        runBlocking {
            runtime.closeAndJoin()
            runtime = AppRuntime.get(context)
            assertEquals(final, runtime.settings.current())
        }
    }

    @Test fun textOnlyMainSelectionPersistsWithoutBuildingConversationAndTextRemainsEditable() {
        val baseline = runBlocking { runtime.settings.current() }
        val selected = baseline.copy(languagePair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.ENGLISH))
        runBlocking { requirePreparedTranslation(selected.languagePair) }
        showConversation()
        awaitConversationReady()
        val builds = captureBuilds.get()
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.FRENCH.value)
        compose.waitUntil(30_000) {
            runBlocking { runtime.settings.current() == selected } &&
                compose.onAllNodes(hasTestTag(ConversationTestTags.LANGUAGE_FIRST) and isEnabled())
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsEnabled()
        assertEquals("Text-only conversation selection must not construct speech", builds, captureBuilds.get())
        compose.onNodeWithTag(ConversationTestTags.OPEN_TEXT).performScrollTo().assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(TranslationTestTags.LANGUAGE_FIRST).performScrollTo().assertTextContains(languageLabel(LanguageCatalog.FRENCH))
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performTextReplacement("Je ne prendrai pas le bus.")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        clickHomeControl(compose.onNodeWithText(context.getString(R.string.mode_conversation)))
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        assertEquals(builds, captureBuilds.get())
        runBlocking { assertEquals(selected, runtime.settings.current()) }
    }

    @Test fun advancedResetKeepsTextPairAndSelectsTencentWhisperAndSystemOutput() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertTrue("Disconnect networking before explicit local Tencent preparation", connectivity.activeNetwork == null &&
            connectivity.allNetworks.isEmpty())
        runBlocking {
            requirePreparedTranslation(enDe)
            assertTrue(runtime.updateLanguagePair(runtime.settings.current().languagePair, enDe))
        }
        showText()
        openSettings()
        choose(SettingsTestTags.SPEED, "110")
        compose.onNodeWithTag(SettingsTestTags.RESET).performScrollTo().assertIsDisplayed().performClick()
        val expected = AppSettings(languagePair = enDe, translationEngineId = "tencent", translationSetupComplete = true,
            speechRecognitionBackend = SpeechRecognitionBackend.WHISPER, speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == expected && !runtime.busy.value } }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        compose.onNodeWithTag(TranslationTestTags.LANGUAGE_SECOND).performScrollTo().assertTextContains(languageLabel(LanguageCatalog.GERMAN))
        awaitTextCondition("Reset Tencent owner requires explicit preparation") {
            compose.onAllNodes(hasTestTag(TranslationTestTags.PREPARE) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertDoesNotExist()
        // Use already-installed weights offline; Reset itself must not initialize or download them.
        compose.onNodeWithTag(TranslationTestTags.PREPARE).assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(180_000) {
            compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag(TranslationTestTags.RETRY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.PREPARE).assertDoesNotExist()
        translateSyntheticEnglish("Bus")
        runBlocking { assertEquals(expected, runtime.settings.current()) }
    }

    @Test fun conversationLanguageControlsSwapStopAndPersistAfterRestart() {
        val baseline = runBlocking { runtime.settings.current() }
        val swapped = baseline.copy(languagePair = baseline.languagePair.reverse())
        showConversation()
        awaitConversationReady()
        assertConversationLanguages(baseline.languagePair)

        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        awaitConversationSettings(swapped)
        assertConversationLanguages(swapped.languagePair)
        compose.onNodeWithText(context.getString(R.string.conversation_start)).assertExists()
        assertTrue(!checkNotNull(pacedCapture).running)

        startAndCompleteOfficialRussian()
        val activeCapture = checkNotNull(pacedCapture)
        val buildsBeforeNoOp = captureBuilds.get()
        val completedText = currentTurnTexts()
        clickHomeControl(compose.onNodeWithTag("settings_button"))
        compose.onNodeWithTag(SettingsTestTags.ADVANCED).performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag(SettingsTestTags.RESET).performScrollTo().assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).performScrollTo().assertIsNotEnabled()
        runBlocking { assertEquals("Busy Apply and Reset must not commit", swapped, runtime.settings.current()) }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        assertTrue("Opening settings while busy cannot stop capture", activeCapture.running)
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        compose.waitForIdle()
        assertTrue("Own-language selection must leave capture running", activeCapture.running)
        assertTrue("Own-language selection must retain capture identity", pacedCapture === activeCapture)
        assertEquals(buildsBeforeNoOp, captureBuilds.get())
        assertEquals(completedText, currentTurnTexts())
        runBlocking { assertEquals(swapped, runtime.settings.current()) }

        choose(ConversationTestTags.LANGUAGE_SECOND, LanguageCatalog.ENGLISH.value)
        awaitConversationSettings(baseline)
        assertTrue("Changed selection must stop old capture", !activeCapture.running)
        assertTrue("Changed selection must build a fresh capture", pacedCapture !== activeCapture)
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.PREVIOUS_TURN).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.conversation_start)).assertExists()
        assertConversationLanguages(baseline.languagePair)

        // Explicit Start after the active UI cutover exercises the successor's native pipeline.
        startAndCompleteOfficialRussian()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
        compose.waitUntil(15_000) { !checkNotNull(pacedCapture).running && !runtime.busy.value }
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        awaitConversationSettings(swapped)
        hideHome()
        captureScope?.cancel()
        captureScope = null
        pacedCapture = null
        runBlocking {
            runtime.closeAndJoin()
            runtime = AppRuntime.get(context)
            assertEquals(swapped, runtime.settings.current())
        }
        showConversation()
        awaitConversationReady()
        assertConversationLanguages(swapped.languagePair)
        compose.onNodeWithText(context.getString(R.string.conversation_start)).assertExists()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        assertTrue("Restart must not auto-start capture", !checkNotNull(pacedCapture).running)
        runBlocking { assertEquals(swapped, runtime.settings.current()) }
    }

    @Test fun languageSaveFailureKeepsOldPairAndRecoversIdleSelection() {
        val baseline = runBlocking { runtime.settings.current() }
        val swapped = baseline.copy(languagePair = baseline.languagePair.reverse())
        showConversation()
        awaitConversationReady()
        val builds = captureBuilds.get()
        val directory = checkNotNull(runtime.settings.file.parentFile)
        val permissions = Os.stat(directory.path).st_mode and 0x1ff
        try {
            // Deny the real DataStore atomic write without modifying its saved document.
            Os.chmod(directory.path, permissions and 0x92.inv())
            choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).fetchSemanticsNodes().isNotEmpty()
            }
            awaitConversationReady()
            runBlocking { assertEquals(baseline, runtime.settings.current()) }
            assertConversationLanguages(baseline.languagePair)
            compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).performScrollTo()
                .assertTextEquals(context.getString(R.string.conversation_language_save_error))
            compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).performScrollTo().assertIsEnabled()
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsEnabled()
            assertEquals("Failed save recovers one idle owner", builds + 1, captureBuilds.get())
            assertTrue("Recovery cannot auto-start", !checkNotNull(pacedCapture).running)
        } finally {
            Os.chmod(directory.path, permissions)
        }
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        awaitConversationSettings(swapped)
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).assertDoesNotExist()
        assertTrue(!checkNotNull(pacedCapture).running)
    }

    @Test fun committedLanguagePairSurvivesFailedSuccessorAndReopensReady() {
        val baseline = runBlocking { runtime.settings.current() }
        val swapped = baseline.copy(languagePair = baseline.languagePair.reverse())
        showConversation()
        awaitConversationReady()
        val oldCapture = checkNotNull(pacedCapture)
        val builds = captureBuilds.get()
        compose.runOnIdle { failCaptureBuild = true }
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        assertConversationOpenFailure(swapped)
        assertEquals("Only the failed successor factory was called", builds + 1, captureBuilds.get())
        assertTrue("Old capture must stop before the commit", !oldCapture.running)
        assertTrue(!runtime.owners.teardownFailed.value)
        hideHome()
        compose.runOnIdle { failCaptureBuild = false }
        runBlocking {
            runtime.closeAndJoin()
            runtime = AppRuntime.get(context)
            assertEquals(swapped, runtime.settings.current())
        }
        showConversation()
        awaitConversationReady()
        assertConversationLanguages(swapped.languagePair)
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).assertDoesNotExist()
        assertTrue("Reopening the committed pair cannot auto-start", !checkNotNull(pacedCapture).running)
    }

    @Test fun committedLanguagePairWithCompetingOwnerReportsOpenFailureWithoutStealingOwner() {
        val baseline = runBlocking { runtime.settings.current() }
        val swapped = baseline.copy(languagePair = baseline.languagePair.reverse())
        showConversation()
        awaitConversationReady()
        val builds = captureBuilds.get()
        val interrupts = AtomicInteger()
        val closes = AtomicInteger()
        val competitor = object : RuntimeOwner {
            override val isBusy = false
            override val activity = MutableStateFlow(false)
            override fun interrupt() { interrupts.incrementAndGet() }
            override suspend fun closeAndJoin(): Result<Unit> {
                closes.incrementAndGet()
                return Result.success(Unit)
            }
        }
        val contenderScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val installed = CompletableDeferred<RuntimeOwner?>()
        try {
            contenderScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Observe the real committed snapshot while the save still holds the gate.
                runtime.settings.state.first { it.settings == swapped }
                installed.complete(runtime.owners.createIfUnowned({ true }) { competitor })
            }
            choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
            compose.waitUntil(30_000) { installed.isCompleted }
            runBlocking { assertTrue(installed.await() === competitor) }
            assertConversationOpenFailure(swapped)
            assertEquals("Refused successor must not enter its factory", builds, captureBuilds.get())
            assertTrue(runtime.owners.isCurrent(competitor))
            assertEquals(0, interrupts.get())
            assertEquals(0, closes.get())
            runBlocking { assertEquals(true, runtime.owners.use(competitor) { true }) }
            runBlocking { runtime.owners.release(competitor) }
            compose.waitForIdle()
            assertConversationOpenFailure(swapped)
            assertEquals("Owner removal must not implicitly retry a failed committed opening", builds, captureBuilds.get())
            val interruptsAfterRelease = interrupts.get()
            val closesAfterRelease = closes.get()
            hideHome()
            assertEquals("Route disposal must not interrupt the released competing owner", interruptsAfterRelease, interrupts.get())
            assertEquals("Route disposal must not close the released competing owner", closesAfterRelease, closes.get())
            runBlocking {
                runtime.closeAndJoin()
                runtime = AppRuntime.get(context)
                assertEquals(swapped, runtime.settings.current())
            }
            showConversation()
            awaitConversationReady()
            assertConversationLanguages(swapped.languagePair)
            assertTrue(!checkNotNull(pacedCapture).running)
        } finally {
            contenderScope.cancel()
        }
    }

    @Test fun conversationLanguageSwitchCancellationWaitsForCloseWithoutSaving() {
        val baseline = runBlocking { runtime.settings.current() }
        val barrier = CaptureCloseBarrier()
        captureCloseBarrier = barrier
        showConversation()
        awaitConversationReady()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
        compose.waitUntil(30_000) { checkNotNull(pacedCapture).running }
        val oldCapture = checkNotNull(pacedCapture)
        val builds = captureBuilds.get()
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        compose.waitUntil(15_000) { barrier.entered.isCompleted }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        // Real pointer clicks cannot enqueue duplicate edits on disabled controls.
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).performTouchInput { click() }
        compose.onNodeWithTag("${ConversationTestTags.LANGUAGE_SECOND}_option_en").assertDoesNotExist()
        assertTrue(!oldCapture.running)
        assertEquals(builds, captureBuilds.get())
        runBlocking { assertEquals(baseline, runtime.settings.current()) }
        hideHome() // Cancels the route request, but not the independently owned cleanup.
        assertTrue(!barrier.finished.isCompleted)
        assertEquals(builds, captureBuilds.get())
        runBlocking { assertEquals(baseline, runtime.settings.current()) }
        barrier.proceed.complete(Unit)
        compose.waitUntil(30_000) { barrier.finished.isCompleted && !runtime.busy.value }
        assertEquals(builds, captureBuilds.get())
        runBlocking { assertEquals(baseline, runtime.settings.current()) }

    }

    @Test fun conversationLanguageSwitchTeardownFailureBlocksRemountWithoutSaving() {
        val baseline = runBlocking { runtime.settings.current() }
        val failure = CaptureCloseBarrier(fail = true)
        expectedTeardownFailure = checkNotNull(failure.failure)
        captureCloseBarrier = failure
        showConversation()
        awaitConversationReady()
        choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
        compose.waitUntil(15_000) { failure.entered.isCompleted }
        failure.proceed.complete(Unit)
        compose.waitUntil(30_000) { runtime.owners.teardownFailed.value }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).assertExists()
        runBlocking { assertEquals(baseline, runtime.settings.current()) }
        val buildsAtFailure = captureBuilds.get()
        hideHome()
        captureCloseBarrier = null
        showConversation()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_SECOND).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        assertEquals("Blocked remount must not build a successor", buildsAtFailure, captureBuilds.get())
        runBlocking { assertEquals(baseline, runtime.settings.current()) }
    }

    @Test fun advancedDraftSurvivesInterveningMainPairCloseApplyAndReset() {
        val baseline = runBlocking { runtime.settings.current() }
        val swapped = baseline.copy(languagePair = baseline.languagePair.reverse())
        val store = ViewModelStore()
        lateinit var vm: SettingsViewModel
        compose.runOnIdle {
            vm = SettingsViewModel(runtime.settings, runtime.editable, runtime::validationProblems, runtime::applySettings,
                runtime.owners.teardownFailed, completeSetup = runtime::completeTranslationSetup)
            store.put("draft-race", vm)
        }
        try {
            showConversation()
            awaitConversationReady()
            compose.waitUntil(15_000) { !vm.state.value.loading }
            compose.runOnIdle {
                vm.beginEditing()
                vm.updateDraft(baseline.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE, ttsSpeedPercent = 110))
            }
            choose(ConversationTestTags.LANGUAGE_FIRST, LanguageCatalog.ENGLISH.value)
            awaitConversationSettings(swapped)
            val firstDraft = swapped.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE, ttsSpeedPercent = 110)
            compose.waitUntil(15_000) { vm.state.value.draft == firstDraft }
            compose.runOnIdle { vm.cancelDraft(); assertEquals(swapped, vm.state.value.draft) }
            runBlocking { assertEquals(swapped, runtime.settings.current()) }
            compose.runOnIdle {
                vm.beginEditing()
                vm.updateDraft(swapped.copy(ttsSpeedPercent = 120))
            }
            choose(ConversationTestTags.LANGUAGE_SECOND, LanguageCatalog.ENGLISH.value)
            awaitConversationSettings(baseline)
            val applied = baseline.copy(ttsSpeedPercent = 120)
            compose.waitUntil(15_000) { vm.state.value.draft == applied && vm.state.value.canApply }
            compose.runOnIdle { vm.apply() }
            compose.waitUntil(30_000) { !vm.state.value.saving && vm.state.value.draft == applied && runBlocking { runtime.settings.current() == applied } }
            assertTrue(!vm.state.value.saveError)
            hideHome()
            compose.waitUntil(30_000) { vm.state.value.canReset && !runtime.busy.value }
            compose.runOnIdle { vm.beginEditing(); vm.reset() }
            val reset = AppSettings(languagePair = baseline.languagePair, translationSetupComplete = baseline.translationSetupComplete)
            compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == reset } }
            compose.runOnIdle { store.clear() }
            runBlocking {
                runtime.closeAndJoin()
                runtime = AppRuntime.get(context)
                assertEquals(reset, runtime.settings.current())
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun backendTeardownFailureOccursBeforePersistenceAndRemainsSticky() {
        val baseline = runBlocking { runtime.settings.current() }
        val barrier = CaptureCloseBarrier(fail = true)
        captureCloseBarrier = barrier
        expectedTeardownFailure = checkNotNull(barrier.failure)
        showConversation()
        awaitConversationReady()
        openSettings()
        choose(SettingsTestTags.OUTPUT_BACKEND, SpeechOutputBackend.SYSTEM_OFFLINE.persistedId)
        compose.onNodeWithTag(SettingsTestTags.APPLY).performClick()
        compose.waitUntil(15_000) { barrier.entered.isCompleted }
        runBlocking { assertEquals("Backend cannot commit until actual release", baseline, runtime.settings.current()) }
        barrier.proceed.complete(Unit)
        compose.waitUntil(30_000) { runtime.owners.teardownFailed.value }
        compose.onNodeWithText(context.getString(R.string.settings_teardown_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsNotEnabled()
        runBlocking { assertEquals(baseline, runtime.settings.current()) }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
    }

    @Test fun backendSaveFailureKeepsPiperWhilePostCommitOpenFailureKeepsSystem() {
        val baseline = runBlocking { runtime.settings.current() }
        showConversation()
        awaitConversationReady()
        val directory = checkNotNull(runtime.settings.file.parentFile)
        val permissions = Os.stat(directory.path).st_mode and 0x1ff
        try {
            Os.chmod(directory.path, permissions and 0x92.inv())
            openSettings()
            choose(SettingsTestTags.OUTPUT_BACKEND, SpeechOutputBackend.SYSTEM_OFFLINE.persistedId)
            compose.onNodeWithTag(SettingsTestTags.APPLY).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(context.getString(R.string.settings_save_error)).fetchSemanticsNodes().isNotEmpty() }
            runBlocking { assertEquals(baseline, runtime.settings.current()) }
            compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        } finally { Os.chmod(directory.path, permissions) }
        awaitConversationReady()
        compose.runOnIdle { failCaptureBuild = true }
        openSettings()
        choose(SettingsTestTags.OUTPUT_BACKEND, SpeechOutputBackend.SYSTEM_OFFLINE.persistedId)
        compose.onNodeWithTag(SettingsTestTags.APPLY).performClick()
        val committed = baseline.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == committed && !runtime.busy.value } }
        compose.onNodeWithText(context.getString(R.string.settings_save_error)).assertDoesNotExist()
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
        assertConversationOpenFailure(committed)
        hideHome()
        compose.runOnIdle { failCaptureBuild = false }
        runBlocking {
            runtime.closeAndJoin()
            runtime = AppRuntime.get(context)
            assertEquals(committed, runtime.settings.current())
        }
    }

    private fun showText() {
        if (!contentSet) {
            compose.setContent {
                MaterialTheme {
                    if (showHome.value) {
                        // SettingsViewModel must not retain a repository from a closed runtime.
                        val homeViewModels = remember {
                            object : ViewModelStoreOwner {
                                override val viewModelStore = ViewModelStore()
                            }
                        }
                        DisposableEffect(homeViewModels) {
                            onDispose { homeViewModels.viewModelStore.clear() }
                        }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides homeViewModels) {
                            TranslatorHome()
                        }
                    }
                }
            }
            contentSet = true
        }
        compose.runOnIdle { showHome.value = true }
        clickHomeControl(compose.onNodeWithText(context.getString(R.string.mode_text)))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun showConversation() {
        if (captureScope == null) captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        if (!contentSet) {
            val factory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine = { store, selected ->
                captureBuilds.incrementAndGet()
                check(!failCaptureBuild) { "Injected successor capture construction failure" }
                val capture = ConversationProofFixtures.PacedCapture(
                    checkNotNull(captureScope), store, selected.vadModelId, selected.vadMinSilenceMs,
                ).also { pacedCapture = it }
                captureCloseBarrier?.wrap(capture) ?: capture
            }
            compose.setContent {
                MaterialTheme {
                    if (showHome.value) {
                        val homeViewModels = remember {
                            object : ViewModelStoreOwner {
                                override val viewModelStore = ViewModelStore()
                            }
                        }
                        DisposableEffect(homeViewModels) {
                            onDispose { homeViewModels.viewModelStore.clear() }
                        }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides homeViewModels) {
                            TranslatorHome(captureFactory = factory)
                        }
                    }
                }
            }
            contentSet = true
        }
        compose.runOnIdle { showHome.value = true }
        clickHomeControl(compose.onNodeWithText(context.getString(R.string.mode_conversation)))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(ConversationTestTags.SESSION_ACTION).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun hideHome() {
        compose.runOnIdle { showHome.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertDoesNotExist()
    }

    private fun awaitConversationReady() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag(ConversationTestTags.SESSION_ACTION) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(ConversationTestTags.LANGUAGE_FIRST) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertConversationOpenFailure(expected: AppSettings) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { assertEquals("Save has completed despite successor failure", expected, runtime.settings.current()) }
        assertConversationLanguages(expected.languagePair)
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR).performScrollTo()
            .assertTextEquals(context.getString(R.string.conversation_open_error))
        for (tag in listOf(ConversationTestTags.LANGUAGE_FIRST, ConversationTestTags.LANGUAGE_SECOND,
            ConversationTestTags.PREPARE)) {
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        compose.onNodeWithTag(ConversationTestTags.PREVIOUS_TURN).assertDoesNotExist()
    }

    private fun awaitConversationSettings(expected: AppSettings) {
        compose.waitUntil(30_000) { runBlocking { runtime.settings.current() == expected } }
        awaitConversationReady()
        runBlocking { assertEquals(expected, runtime.settings.current()) }
    }

    private fun languageLabel(language: LanguageId): String {
        return context.getString(checkNotNull(com.example.offlinetranslator.ui.languageNameResource(language)))
    }

    private fun assertConversationLanguages(pair: LanguagePair) {
        for ((tag, language) in listOf(
            ConversationTestTags.LANGUAGE_FIRST to pair.source,
            ConversationTestTags.LANGUAGE_SECOND to pair.target,
        )) compose.onNodeWithTag(tag).assertTextContains(languageLabel(language), substring = true)
    }

    private fun startAndCompleteOfficialRussian() {
        compose.onNodeWithText(context.getString(R.string.conversation_start)).assertExists()
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            checkNotNull(pacedCapture).running ||
                compose.onAllNodesWithTag(ConversationTestTags.PERMISSION_ERROR).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ConversationTestTags.PERMISSION_ERROR).assertDoesNotExist()
        runBlocking { checkNotNull(pacedCapture).replay(loadOfficialRussianPcm()) }
        val completed = hasTestTag(ConversationTestTags.CURRENT_TURN) and
            hasAnyDescendant(hasText(context.getString(R.string.conversation_complete)))
        compose.waitUntil(45_000) { compose.onAllNodes(completed).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(completed).assertIsDisplayed()
        val texts = currentTurnTexts()
        assertTrue(texts.contains("${languageLabel(LanguageCatalog.RUSSIAN)} → ${languageLabel(LanguageCatalog.ENGLISH)}"))
        assertTrue("Completed turn exposes recognition", texts.contains(context.getString(R.string.conversation_original)))
        assertTrue("Completed turn exposes translation", texts.contains(context.getString(R.string.conversation_translation)))
        val source = texts[texts.indexOf(context.getString(R.string.conversation_original)) + 1]
        val translation = texts[texts.indexOf(context.getString(R.string.conversation_translation)) + 1]
        assertTrue("Official RU input must produce Russian recognition: $source", Regex("[А-Яа-яЁё]").containsMatchIn(source))
        assertTrue("Completed RU→EN translation must contain English text: $translation", Regex("[A-Za-z]").containsMatchIn(translation))
        compose.onNodeWithTag(ConversationTestTags.SESSION_ERROR).assertDoesNotExist()
        assertTrue("Native completion must retain active capture", checkNotNull(pacedCapture).running)
    }

    private fun currentTurnTexts(): List<String> = compose.onAllNodes(
        hasAnyAncestor(hasTestTag(ConversationTestTags.CURRENT_TURN)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { node ->
        node.config[SemanticsProperties.Text].map { it.text }
    }

    /** Only capture close is delayed; providers, route, owner gate and DataStore remain production. */
    private class CaptureCloseBarrier(fail: Boolean = false) {
        val failure = if (fail) IllegalStateException("Injected capture teardown failure") else null
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        fun wrap(delegate: SpeechCaptureEngine): SpeechCaptureEngine = object : SpeechCaptureEngine by delegate {
            override suspend fun closeAndJoin() {
                entered.complete(Unit)
                proceed.await()
                try {
                    delegate.closeAndJoin()
                    failure?.let { throw it }
                } finally {
                    finished.complete(Unit)
                }
            }
        }
    }

    private fun translateSyntheticEnglish(expectedToken: String) {
        val pair = runBlocking { runtime.settings.current().languagePair }
        val sourceControl = hasTestTag(TranslationTestTags.LANGUAGE_FIRST) and
            hasText(languageLabel(pair.source), substring = true) and isEnabled()
        val targetControl = hasTestTag(TranslationTestTags.LANGUAGE_SECOND) and
            hasText(languageLabel(pair.target), substring = true) and isEnabled()
        awaitTextCondition("Committed ordered pair and ready Text owner before editing") {
            compose.onAllNodes(sourceControl).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(targetControl).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty()
        }
        val input = "I will not take the bus."
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performTextReplacement(input)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        awaitTextCondition("Input accepted by ready Text owner before Translate") {
            compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and hasText(input)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        clickTranslateAndWait(expectedToken)
    }

    private fun clickTranslateAndWait(expectedToken: String) {
        compose.onNodeWithTag(TranslationTestTags.TRANSLATE).assertIsDisplayed().assertIsEnabled().performClick()
        val translatedOutput = hasTestTag(TranslationTestTags.OUTPUT) and
            hasAnyDescendant(hasText(expectedToken, substring = true, ignoreCase = true))
        awaitTextCondition("Completed translation containing the original semantic token") {
            (compose.onAllNodes(translatedOutput, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty())
                || compose.onAllNodesWithTag(TranslationTestTags.RETRY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        compose.onNode(translatedOutput, useUnmergedTree = true).assertExists()
    }

    private fun awaitTextCondition(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(30_000, condition)
        } catch (failure: ComposeTimeoutException) {
            // Only these test-owned fixed inputs/results are present; no production diagnostics.
            val settings = runBlocking { runtime.settings.current() }
            val tree = compose.onRoot(useUnmergedTree = true).printToString().take(16_384)
            throw AssertionError(
                "$description; latest=$settings; busy=${runtime.busy.value}; " +
                    "ownershipVersion=${runtime.owners.ownershipVersion.value}\n$tree",
                failure,
            )
        }
    }



    private fun openSettings() {
        clickHomeControl(compose.onNodeWithTag("settings_button"))
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsEnabled()
        compose.onNodeWithTag(SettingsTestTags.ADVANCED).performScrollTo().performClick()
    }

    private fun clickHomeControl(node: SemanticsNodeInteraction) {
        val inText = compose.onAllNodesWithTag(TranslationTestTags.INPUT).fetchSemanticsNodes().isNotEmpty()
        if (inText) node.performScrollTo()
        node.assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }

    private fun choose(tag: String, id: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("${tag}_option_$id").performScrollTo().performClick()
    }

    private fun applyAndClose() {
        compose.onNodeWithTag(SettingsTestTags.APPLY).performClick()
        compose.waitUntil(15_000) { !runtime.busy.value }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).performClick()
    }

    private suspend fun requirePreparedTranslation(pair: LanguagePair) {
        val engine = runtime.registry.create("mlkit", setOf(pair))
        try {
            assertEquals("Prepared translation models required; this test never downloads them", ModelAvailability.READY, engine.modelAvailability())
        } finally {
            engine.closeAndJoin()
        }
    }

    private fun loadOfficialRussianPcm(): ShortArray {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val temp = java.io.File(context.cacheDir, "temp-official-ru.wav")
        testContext.assets.open("speech-ru-fixture.wav").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            return ConversationProofFixtures.readPcm(temp)
        } finally {
            temp.delete()
        }
    }
}
