package com.example.offlinetranslator.runtime

import android.net.ConnectivityManager
import android.provider.Settings
import android.util.Log
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.R
import com.example.offlinetranslator.TranslatorHome
import com.example.offlinetranslator.conversation.ConversationProofFixtures
import com.example.offlinetranslator.conversation.ConversationTestTags
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.settings.SettingsTestTags
import com.example.offlinetranslator.translation.TranslationTestTags
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated test fixture commit through both real gates; never a second production settings path. */
internal suspend fun commitNativeSettings(runtime: AppRuntime, selected: AppSettings) {
    runtime.owners.closeAndJoin()
    val current = runtime.settings.current()
    assertTrue(runtime.updateLanguagePair(current.languagePair, selected.languagePair))
    assertTrue(runtime.applySettings(selected))
    assertEquals(selected, runtime.settings.current())
}

/** Explicit final-app offline smoke. Missing resources fail rather than becoming an Assume/pass. */
class SharedLanguagePairNativeTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var runtime: AppRuntime
    private lateinit var original: AppSettings
    private val shown = mutableStateOf(false)
    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var capture: ConversationProofFixtures.PacedCapture? = null
    private val captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine = { models, selected ->
        ConversationProofFixtures.PacedCapture(captureScope, models, selected.vadModelId, selected.vadMinSilenceMs)
            .also { capture = it }
    }
    private var contentSet = false
    private val ruEn = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    @Before fun retainOriginalAndRequireOffline() = runBlocking {
        assertEquals(1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("No default network during native offline proof", connectivity.activeNetwork)
        assertTrue("No current networks during native offline proof", connectivity.allNetworks.isEmpty())
        runtime = AppRuntime.get(context)
        original = runtime.settings.current()
    }

    @After fun restoreOriginalAndRelease() {
        if (contentSet) compose.runOnIdle { shown.value = false }
        runBlocking {
            try {
                if (::original.isInitialized) commitNativeSettings(runtime, original)
            } finally {
                if (::runtime.isInitialized) runtime.closeAndJoin()
                captureScope.cancel()
            }
        }
    }

    @Test fun pivotPairsUseMainControlsAndRealOfflineSdkInBothDirections() {
        val frEs = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        runBlocking { commitNativeSettings(runtime, original.copy(languagePair = frEs,
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)) }
        showHome()
        // A valid text-only pair remains editable on Conversation without native capture construction.
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).assertIsEnabled()
        assertNull(capture)
        clickHomeControl(compose.onNodeWithText(context.getString(R.string.mode_text)))
        awaitPair(frEs)
        translateFixture(frEs, "Je ne prendrai pas le bus à 15 heures.")
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().performClick()
        awaitPair(frEs.reverse())
        translateFixture(frEs.reverse(), "No tomaré el autobús a las 15 horas.")
        choose(TranslationTestTags.LANGUAGE_FIRST, LanguageCatalog.RUSSIAN)
        choose(TranslationTestTags.LANGUAGE_SECOND, LanguageCatalog.GERMAN)
        val ruDe = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.GERMAN)
        awaitPair(ruDe)
        translateFixture(ruDe, "Анна купит два билета завтра в 15:00.")
        compose.onNodeWithTag(TranslationTestTags.SWAP).performScrollTo().performClick()
        awaitPair(ruDe.reverse())
        translateFixture(ruDe.reverse(), "Anna kauft morgen um 15 Uhr zwei Fahrkarten.")
        clickHomeControl(compose.onNodeWithText(context.getString(R.string.mode_conversation)))
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
        compose.onNodeWithTag(ConversationTestTags.LANGUAGE_FIRST).assertIsEnabled()
        reopenHomeAndRuntime()
        awaitPair(ruDe.reverse())
        assertNull("Text-only restart must not construct capture", capture)
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).assertIsNotEnabled()
    }

    @Test fun selectedSystemAndPiperCompleteOfficialBothDirectionsStopAndReopenIdle() {
        runBlocking { commitNativeSettings(runtime, original.copy(languagePair = ruEn,
            speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)) }
        showHome()
        for (backend in listOf(SpeechOutputBackend.SYSTEM_OFFLINE, SpeechOutputBackend.PIPER)) {
            if (runtime.settings.state.value.settings?.speechOutputBackend != backend) chooseBackend(backend)
            awaitConversationReady()
            startCapture()
            replayAndAwaitComplete("speech-ru-fixture.wav", ruEn)
            replayAndAwaitComplete("speech-en-fixture.wav", ruEn.reverse())
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
            compose.waitUntil(30_000) { capture?.running == false }
            // Begin another real fixture and stop the selected pipeline during its output phase.
            startCapture()
            runBlocking { requireNotNull(capture).replay(loadPcm("speech-en-fixture.wav")) }
            compose.waitUntil(120_000) {
                compose.onAllNodes(hasTestTag(ConversationTestTags.CURRENT_TURN) and
                    hasAnyDescendant(hasText(context.getString(R.string.conversation_speaking)))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
            compose.waitUntil(30_000) { capture?.running == false }
            Log.i(TAG, "backend=${backend.persistedId} fixtureDirections=ru-en,en-ru complete=true pipelineStop=true")
            reopenHomeAndRuntime()
            awaitConversationReady()
            assertFalse("Reopen must remain idle", requireNotNull(capture).running)
            compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
            assertEquals(backend, runBlocking { runtime.settings.current().speechOutputBackend })
        }
    }

    @Test fun parakeetUsesRealOfflineVoicesAndBothDirectionsForDutchEnglishRussian() {
        val nlEn = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH)
        runBlocking { commitNativeSettings(runtime, original.copy(languagePair = nlEn,
            speechRecognitionBackend = SpeechRecognitionBackend.NEMO,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)) }
        showHome()
        val cases = listOf(
            Triple(nlEn, "conversation/nl-fleurs-test-0.wav", "short-human-en-right.wav"),
            Triple(LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.RUSSIAN),
                "conversation/nl-fleurs-test-1.wav", "short-human-ru-timer.wav"),
            Triple(ruEn, "short-human-ru-come.wav", "short-human-en-wait.wav"),
        )
        for ((pair, forward, reverse) in cases) {
            val current = runBlocking { runtime.settings.current().languagePair }
            if (current.target != pair.target) choose(ConversationTestTags.LANGUAGE_SECOND, pair.target)
            if (current.source != pair.source) choose(ConversationTestTags.LANGUAGE_FIRST, pair.source)
            awaitPair(pair)
            awaitConversationReady()
            startCapture()
            for ((asset, direction) in listOf(forward to pair, reverse to pair.reverse())) {
                replayAndAwaitComplete(asset, direction)
                val tree = recordRecognitionSurface(SpeechRecognitionBackend.NEMO, "${direction.source.value}-${direction.target.value}")
                Log.i(TAG, "recognition=nemo fixture=$asset direction=${direction.id} complete=true microphoneEvidence=false\n$tree")
            }
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
            compose.waitUntil(120_000) { capture?.running == false }
        }
        reopenHomeAndRuntime()
        awaitConversationReady()
        assertFalse("Reopen must remain idle", requireNotNull(capture).running)
        assertEquals(SpeechRecognitionBackend.NEMO, runBlocking { runtime.settings.current().speechRecognitionBackend })
    }

    @Test fun whisperUsesSelectedPairAndRealOfflineVoicesInBothDirectionsForDutchEnglishRussian() {
        val backend = SpeechRecognitionBackend.WHISPER
        val nlEn = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH)
        runBlocking { commitNativeSettings(runtime, original.copy(languagePair = nlEn,
            speechRecognitionBackend = backend,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)) }
        showHome()
        val cases = listOf(
            Triple(nlEn, "conversation/nl-fleurs-test-0.wav", "short-human-en-right.wav"),
            Triple(LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.RUSSIAN),
                "conversation/nl-fleurs-test-1.wav", "short-human-ru-timer.wav"),
            Triple(ruEn, "short-human-ru-come.wav", "short-human-en-wait.wav"),
        )
        val sourceMeaning = mapOf(
            "conversation/nl-fleurs-test-0.wav" to listOf("organisaties", "presteren", "aanpak"),
            "conversation/nl-fleurs-test-1.wav" to listOf("varkens", "ziekte", "muggen", "mensen"),
            "short-human-en-right.wav" to listOf("turn", "right"),
            "short-human-ru-timer.wav" to listOf("останов", "таймер"),
            "short-human-ru-come.wav" to listOf("иди", "сюда"),
            "short-human-en-wait.wav" to listOf("wait", "minute"),
        )
        val failures = mutableListOf<String>()
        for ((pair, forward, reverse) in cases) {
            val current = runBlocking { runtime.settings.current().languagePair }
            if (current.target != pair.target) choose(ConversationTestTags.LANGUAGE_SECOND, pair.target)
            if (current.source != pair.source) choose(ConversationTestTags.LANGUAGE_FIRST, pair.source)
            awaitPair(pair)
            awaitConversationReady()
            startCapture()
            if (pair == nlEn) {
                runBlocking {
                    requireNotNull(capture).replay(ShortArray(32_000))
                    requireNotNull(capture).awaitReplay()
                }
                compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
                assertTrue("Silence must not stop the selected capture", requireNotNull(capture).running)
                Log.i(TAG, "recognition=whisper silenceSeconds=2 acceptedTurn=false microphoneEvidence=false")
            }
            for ((asset, direction) in listOf(forward to pair, reverse to pair.reverse())) {
                replayAndAwaitComplete(asset, direction)
                val tree = recordRecognitionSurface(backend, "${direction.source.value}-${direction.target.value}")
                Log.i(TAG, "recognition=${backend.persistedId} fixture=$asset direction=${direction.id} " +
                    "complete=true microphoneEvidence=false physicalPresentationEvidence=false\n$tree")
                for (fragment in sourceMeaning.getValue(asset)) {
                    if (compose.onAllNodes(hasTestTag(ConversationTestTags.CURRENT_TURN) and
                            hasAnyDescendant(hasText(fragment, substring = true, ignoreCase = true)))
                            .fetchSemanticsNodes().size != 1) {
                        failures += "$asset (${direction.id}): missing source meaning '$fragment'"
                    }
                }
                compose.onNode(hasTestTag(ConversationTestTags.CURRENT_TURN) and
                    hasAnyDescendant(hasText(context.getString(R.string.conversation_failure_undetermined_language))))
                    .assertDoesNotExist()
            }
            compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
            awaitFixtureState(120_000, "Whisper Stop must return to idle Start") {
                capture?.running == false &&
                    compose.onAllNodes(hasTestTag(ConversationTestTags.SESSION_ACTION) and
                        hasText(context.getString(R.string.conversation_start)) and isEnabled())
                        .fetchSemanticsNodes().isNotEmpty()
            }
        }
        reopenHomeAndRuntime()
        awaitConversationReady()
        assertFalse("Whisper reopen must remain idle", requireNotNull(capture).running)
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
        assertEquals(backend, runBlocking { runtime.settings.current().speechRecognitionBackend })
        assertTrue("Whisper lost fixture meaning: ${failures.joinToString("; ")}", failures.isEmpty())
    }

    @Test fun parakeetUndeterminedHumanTurnRequestsRepeatThenResumesSelectedDirection() {
        val pair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH)
        runBlocking { commitNativeSettings(runtime, original.copy(languagePair = pair,
            speechRecognitionBackend = SpeechRecognitionBackend.NEMO,
            speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE)) }
        showHome()
        awaitConversationReady()
        startCapture()
        // This unchanged Russian control is deliberately outside the selected Dutch/English pair.
        runBlocking { requireNotNull(capture).replay(loadPcm("short-human-ru-timer.wav")) }
        val repeatRequest = hasTestTag(ConversationTestTags.CURRENT_TURN) and
            hasAnyDescendant(hasText(context.getString(R.string.conversation_failure_undetermined_language)))
        awaitFixtureState(120_000, "A real foreign human turn must request repetition without a guessed direction") {
            compose.onAllNodes(repeatRequest).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(repeatRequest and hasAnyDescendant(hasText("таймер", substring = true, ignoreCase = true))).assertExists()
        compose.onNode(hasTestTag(ConversationTestTags.CURRENT_TURN) and
            hasAnyDescendant(hasText(context.getString(R.string.conversation_speaking)))).assertDoesNotExist()
        assertTrue("Undetermined input must not stop capture", requireNotNull(capture).running)
        Log.i(TAG, "realUndeterminedTurn=true microphoneEvidence=false\n${recordRecognitionSurface(SpeechRecognitionBackend.NEMO, "undetermined")}")
        runBlocking { requireNotNull(capture).awaitReplay() }
        replayAndAwaitComplete("short-human-en-right.wav", pair.reverse())
        recordRecognitionSurface(SpeechRecognitionBackend.NEMO, "recovered-en-nl")
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
        awaitFixtureState(120_000, "Stop must reach the idle ready action, not merely stop its capture source") {
            capture?.running == false && compose.onAllNodes(hasTestTag(ConversationTestTags.SESSION_ACTION) and
                hasText(context.getString(R.string.conversation_start)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        reopenHomeAndRuntime()
        awaitConversationReady()
        assertFalse("Reopen after recovery must remain idle", requireNotNull(capture).running)
        compose.onNodeWithTag(ConversationTestTags.CURRENT_TURN).assertDoesNotExist()
    }

    private fun recordRecognitionSurface(backend: SpeechRecognitionBackend, name: String): String {
        val evidence = File(context.filesDir, "${backend.persistedId}-recognition-proof").apply { mkdirs() }
        val tree = compose.onRoot(useUnmergedTree = true).printToString()
        File(evidence, "$name-ui.txt").writeText(tree)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(evidence, "$name-ui.png").outputStream().use { output ->
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { screenshot.recycle() }
        return tree
    }

    private fun showHome() {
        if (!contentSet) {
            compose.setContent {
                MaterialTheme {
                    if (shown.value) {
                        val store = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
                        DisposableEffect(store) { onDispose { store.viewModelStore.clear() } }
                        CompositionLocalProvider(LocalViewModelStoreOwner provides store) {
                            TranslatorHome(captureFactory = captureFactory)
                        }
                    }
                }
            }
            contentSet = true
        }
        compose.runOnIdle { shown.value = true }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(ConversationTestTags.LANGUAGE_FIRST).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun reopenHomeAndRuntime() {
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        runBlocking { runtime.closeAndJoin(); runtime = AppRuntime.get(context) }
        capture = null
        compose.runOnIdle { shown.value = true }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(ConversationTestTags.LANGUAGE_FIRST).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun choose(button: String, language: LanguageId) {
        compose.onNodeWithTag(button).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("${button}_option_${language.value}").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag(button) and hasText(languageLabel(language), substring = true) and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitPair(pair: LanguagePair) {
        compose.waitUntil(30_000) { runtime.settings.state.value.settings?.languagePair == pair }
        assertEquals(pair, runBlocking { runtime.settings.current().languagePair })
    }

    private fun translateFixture(pair: LanguagePair, input: String) {
        val sourceControl = hasTestTag(TranslationTestTags.LANGUAGE_FIRST) and
            hasText(languageLabel(pair.source), substring = true) and isEnabled()
        val targetControl = hasTestTag(TranslationTestTags.LANGUAGE_SECOND) and
            hasText(languageLabel(pair.target), substring = true) and isEnabled()
        awaitFixtureState(45_000, "Visible requested pair and idle prepared Text owner before input") {
            compose.onAllNodes(sourceControl).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(targetControl).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.INPUT) and isEnabled()).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.TRANSLATE).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.INPUT).performScrollTo().performTextReplacement(input)
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertTextContains(input)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val translate = compose.onNodeWithTag(TranslationTestTags.TRANSLATE)
        translate.performScrollTo().assertIsEnabled()
        Log.i(TAG, "beforeTranslationPointer pair=${pair.id} " +
            "clippedPx=${translate.fetchSemanticsNode().boundsInRoot} " +
            "unclippedDp=${translate.getUnclippedBoundsInRoot()} " +
            "rootPx=${compose.onRoot().fetchSemanticsNode().boundsInRoot} ${textActionState()}")
        translate.assertIsDisplayed().performTouchInput { click() }
        Log.i(TAG, "afterTranslationPointer pair=${pair.id} ${textActionState()}")
        awaitFixtureState(120_000, "Actual completed requested-pair output") {
            val result = resultText()
            (result.isNotBlank() && result != context.getString(R.string.result_placeholder) &&
                compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().isNotEmpty())
                || compose.onAllNodesWithTag(TranslationTestTags.RETRY).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TranslationTestTags.RETRY).assertDoesNotExist()
        compose.onNodeWithTag(TranslationTestTags.INPUT).assertTextContains(input)
        val output = resultText()
        assertTrue(output.isNotBlank())
        assertEquals(pair, runBlocking { runtime.settings.current().languagePair })
        // Fixed evaluation input/output only; production adapters do not log conversation content.
        Log.i(TAG, "fixturePair=${pair.id} input=$input output=$output")
    }

    private fun awaitFixtureState(
        timeoutMillis: Long,
        description: String,
        diagnostics: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis, condition)
        } catch (failure: ComposeTimeoutException) {
            // Bounded diagnostics contain only this test's fixed evaluation fixtures.
            val tree = compose.onRoot(useUnmergedTree = true).printToString().take(16_384)
            throw AssertionError(
                "$description; latest=${runtime.settings.state.value.settings}; busy=${runtime.busy.value}; " +
                    "ownershipVersion=${runtime.owners.ownershipVersion.value}; ${diagnostics?.invoke().orEmpty()}\n$tree",
                failure,
            )
        }
    }

    private fun textActionState(): String =
        "progress=${compose.onAllNodesWithTag(TranslationTestTags.PROGRESS).fetchSemanticsNodes().size} " +
            "retry=${compose.onAllNodesWithTag(TranslationTestTags.RETRY).fetchSemanticsNodes().size} " +
            "enabledTranslate=${compose.onAllNodes(hasTestTag(TranslationTestTags.TRANSLATE) and isEnabled()).fetchSemanticsNodes().size} " +
            "result=${resultText().take(160)} busy=${runtime.busy.value} " +
            "ownershipVersion=${runtime.owners.ownershipVersion.value} " +
            "latestPair=${runtime.settings.state.value.settings?.languagePair}"

    private fun resultText(): String = compose.onAllNodesWithTag(
        TranslationTestTags.RESULT_TEXT, useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }
        .joinToString(" ") { it.text }

    private fun chooseBackend(backend: SpeechOutputBackend) {
        clickHomeControl(compose.onNodeWithTag("settings_button"))
        compose.onNodeWithTag(SettingsTestTags.OUTPUT_BACKEND).performScrollTo().performClick()
        compose.onNodeWithTag("${SettingsTestTags.OUTPUT_BACKEND}_option_${backend.persistedId}").performScrollTo().performClick()
        compose.onNodeWithTag(SettingsTestTags.APPLY).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.waitUntil(30_000) { runtime.settings.state.value.settings?.speechOutputBackend == backend }
        compose.onNodeWithTag(SettingsTestTags.CLOSE).assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
    }

    private fun clickHomeControl(node: SemanticsNodeInteraction) {
        // Only Text hosts Home controls in a scroll. Conversation's header stays fixed.
        val inText = compose.onAllNodesWithTag(TranslationTestTags.INPUT).fetchSemanticsNodes().isNotEmpty()
        if (inText) node.performScrollTo()
        node.assertIsEnabled().assertIsDisplayed().performTouchInput { click() }
    }

    private fun awaitConversationReady() {
        compose.waitUntil(120_000) {
            compose.onAllNodes(hasTestTag(ConversationTestTags.SESSION_ACTION) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun startCapture() {
        compose.onNodeWithTag(ConversationTestTags.SESSION_ACTION).performClick()
        compose.waitUntil(120_000) { capture?.running == true }
    }

    private fun replayAndAwaitComplete(asset: String, direction: LanguagePair) {
        val pcm = loadPcm(asset)
        runBlocking { requireNotNull(capture).replay(pcm) }
        val directionLabel = "${languageLabel(direction.source)} → ${languageLabel(direction.target)}"
        awaitFixtureState(120_000, "Actual complete turn for $asset and $directionLabel", diagnostics = {
            val pacing = capture?.pacing
            val currentTurn = hasTestTag(ConversationTestTags.CURRENT_TURN)
            val directedTurn = currentTurn and hasAnyDescendant(hasText(directionLabel))
            val completeTurn = currentTurn and
                hasAnyDescendant(hasText(context.getString(R.string.conversation_complete)))
            "asset=$asset deliveredFrames=${pacing?.frames} captureRunning=${capture?.running} " +
                "catchUpFrames=${pacing?.catchUpFrames} lateFrames=${pacing?.lateFrames} " +
                "maximumLatenessNanos=${pacing?.maximumLatenessNanos} " +
                "currentTurns=${compose.onAllNodes(currentTurn).fetchSemanticsNodes().size} " +
                "directedTurns=${compose.onAllNodes(directedTurn).fetchSemanticsNodes().size} " +
                "completeTurns=${compose.onAllNodes(completeTurn).fetchSemanticsNodes().size} " +
                "directedCompleteTurns=${compose.onAllNodes(directedTurn and completeTurn).fetchSemanticsNodes().size}"
        }) {
            capture?.running == true &&
                compose.onAllNodes(hasTestTag(ConversationTestTags.CURRENT_TURN) and
                    hasAnyDescendant(hasText(directionLabel)) and
                    hasAnyDescendant(hasText(context.getString(R.string.conversation_complete)))).fetchSemanticsNodes().isNotEmpty()
        }
        // Playback can cancel the unused replay tail. Scheduler wait<=0 is not a
        // measured inter-frame gap or burst proof; pacing remains diagnostic only.
        compose.onNode(hasTestTag(ConversationTestTags.CURRENT_TURN) and
            hasAnyDescendant(hasText(context.getString(R.string.conversation_translation)))).assertExists()
    }

    private fun languageLabel(language: LanguageId): String {
        return context.getString(checkNotNull(com.example.offlinetranslator.ui.languageNameResource(language)))
    }

    private fun loadPcm(asset: String): ShortArray {
        val file = File.createTempFile("shared-pair-fixture-", ".wav", context.cacheDir)
        return try {
            instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            ConversationProofFixtures.readPcm(file)
        } finally { file.delete() }
    }

    private companion object { const val TAG = "SharedPairProof" }
}
