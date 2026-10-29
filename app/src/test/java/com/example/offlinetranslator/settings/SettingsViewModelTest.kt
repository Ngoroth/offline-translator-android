package com.example.offlinetranslator.settings

import com.example.offlinetranslator.core.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val frenchSpanish = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)

    @Test fun liveOutputChoiceUpdatesAnOpenDraftAndNormalApplyWhileResetRestoresOn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "output-draft.json"), dispatcher)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it); true }, MutableStateFlow(false),
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            vm.beginEditing()
            val stale = vm.state.value.draft.copy(ttsSpeedPercent = 120)
            vm.updateDraft(stale)
            assertTrue(repo.updateSpeechOutput(false) { true })
            runCurrent()
            assertFalse(vm.state.value.draft.speechOutputEnabled)
            vm.updateDraft(stale)
            assertFalse(vm.state.value.draft.speechOutputEnabled)
            vm.apply()
            runCurrent()
            assertEquals(120, repo.current().ttsSpeedPercent)
            assertFalse(repo.current().speechOutputEnabled)
            vm.reset()
            runCurrent()
            assertTrue(repo.current().speechOutputEnabled)
            assertEquals(AppSettings(), repo.current())
        } finally { vm.viewModelScope.cancel(); repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun invalidAdvancedDraftAndBusyApplyNeverPersist() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "validation.json"), dispatcher)
        val editable = MutableStateFlow(true)
        val vm = SettingsViewModel(repo, editable, { if (it.vadMinSilenceMs !in 300..1500) listOf(ConfigurationProblem.INVALID_SILENCE) else emptyList() },
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false),
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(vadMinSilenceMs = 2000))
            assertFalse(vm.state.value.canApply)
            vm.apply()
            runCurrent()
            assertEquals(AppSettings(), repo.current())
            vm.updateDraft(vm.state.value.draft.copy(vadMinSilenceMs = 900))
            editable.value = false
            runCurrent()
            vm.apply()
            vm.reset()
            runCurrent()
            assertEquals(AppSettings(), repo.current())
        } finally { repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun pairCommitPreservesAdvancedDraftCloseApplyAndAdvancedReset() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "draft.json"), dispatcher)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false),
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
                speechOutputBackend = SpeechOutputBackend.PIPER, ttsSpeedPercent = 110))
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = "missing.provider/.RecognitionService"))
            repo.save(repo.current().copy(languagePair = frenchSpanish))
            runCurrent()
            val draft = AppSettings(languagePair = frenchSpanish, speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
                speechOutputBackend = SpeechOutputBackend.PIPER, ttsSpeedPercent = 110)
                .copy(speechRecognitionService = "missing.provider/.RecognitionService")
            assertEquals(draft, vm.state.value.draft)
            vm.cancelDraft()
            assertEquals(AppSettings(languagePair = frenchSpanish), vm.state.value.draft)
            assertEquals(AppSettings(languagePair = frenchSpanish), repo.current())
            vm.beginEditing()
            vm.updateDraft(draft)
            vm.apply()
            runCurrent()
            assertEquals(draft, repo.current())
            assertEquals(repo.current(), vm.state.value.draft)
            vm.reset()
            runCurrent()
            assertEquals(AppSettingsTestFixtures.freshInstall.copy(languagePair = frenchSpanish), repo.current())
            assertEquals(SpeechOutputBackend.SYSTEM_OFFLINE, vm.state.value.draft.speechOutputBackend)
            assertEquals(SpeechRecognitionBackend.WHISPER, vm.state.value.draft.speechRecognitionBackend)
        } finally { repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun successfulStaleApplyRefreshesActualRepositoryRatherThanSubmittedPair() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "race.json"), dispatcher)
        val gate = CompletableDeferred<Unit>()
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() }, { draft ->
            gate.await()
            repo.save(draft.copy(languagePair = repo.current().languagePair))
            true
        }, MutableStateFlow(false), completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(ttsSpeedPercent = 120, speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            vm.apply()
            runCurrent()
            assertTrue(vm.state.value.saving)
            repo.save(repo.current().copy(languagePair = frenchSpanish))
            runCurrent()
            gate.complete(Unit)
            val actual = AppSettings(languagePair = frenchSpanish, ttsSpeedPercent = 120,
                speechOutputBackend = SpeechOutputBackend.PIPER, speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE)
            vm.state.first { !it.saving }
            assertEquals(actual, repo.current())
            assertEquals(actual, vm.state.value.draft)
            assertFalse(vm.state.value.saveError)
        } finally { gate.complete(Unit); repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun failedSaveKeepsDraftWhileTeardownFailureBlocksRetryCommit() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "failure.json"), dispatcher)
        val teardown = MutableStateFlow(false)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() }, { false }, teardown,
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(ttsSpeedPercent = 110))
            vm.apply()
            runCurrent()
            assertTrue(vm.state.value.saveError)
            assertEquals(110, vm.state.value.draft.ttsSpeedPercent)
            assertEquals(AppSettings(), repo.current())
            teardown.value = true
            runCurrent()
            assertTrue(vm.state.value.teardownError)
            assertFalse(vm.state.value.canApply)
            assertFalse(vm.state.value.canReset)
            vm.apply()
            vm.reset()
            assertEquals(AppSettings(), repo.current())
        } finally { repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun independentRecognitionAndOutputChoicesPersistAndReloadWithoutChangingPair() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File(temp.root, "independent-backends.json")
        val repo = AppSettingsRepository(file, dispatcher)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false),
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            val reversed = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
            repo.save(repo.current().copy(languagePair = reversed,
                speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(speechOutputBackend = SpeechOutputBackend.PIPER))
            assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, vm.state.value.draft.speechRecognitionBackend)
            vm.apply()
            runCurrent()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER))
            assertEquals(SpeechOutputBackend.PIPER, vm.state.value.draft.speechOutputBackend)
            assertFalse(vm.state.value.usesSystemRecognition)
            vm.apply()
            runCurrent()
            vm.updateDraft(vm.state.value.draft.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE))
            assertEquals(SpeechRecognitionBackend.WHISPER, vm.state.value.draft.speechRecognitionBackend)
            vm.apply()
            runCurrent()
            assertEquals(reversed, repo.current().languagePair)
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            vm.cancelDraft()
            assertEquals(SpeechRecognitionBackend.WHISPER, vm.state.value.draft.speechRecognitionBackend)
            assertEquals(reversed, vm.state.value.draft.languagePair)
            val committed = repo.current()
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            val reloaded = AppSettingsRepository(file, dispatcher)
            try {
                runCurrent()
                assertEquals(committed, reloaded.current())
                assertEquals(SpeechRecognitionBackend.WHISPER, reloaded.current().speechRecognitionBackend)
                assertEquals(SpeechOutputBackend.SYSTEM_OFFLINE, reloaded.current().speechOutputBackend)
            } finally { reloaded.closeAndJoin() }
        } finally { repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun whisperRetainsSystemServiceAndResetPreservesCompletedSetup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File(temp.root, "whisper-draft.json")
        val repo = AppSettingsRepository(file, dispatcher)
        val selected = AppSettings(languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH),
            speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE,
            speechOutputBackend = SpeechOutputBackend.PIPER,
            translationEngineId = "mlkit", translationSetupComplete = true,
            speechRecognitionService = "installed.provider/.RecognitionService",
            vadMinSilenceMs = 1200, ttsSpeedPercent = 115)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it); true }, MutableStateFlow(false),
            { listOf(selected.speechRecognitionService to "Installed recognition") },
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            repo.save(selected)
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER))
            val whisper = selected.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)
            assertEquals(whisper, vm.state.value.draft)
            assertFalse(vm.state.value.usesSystemRecognition)
            vm.cancelDraft()
            assertEquals(selected, vm.state.value.draft)
            assertEquals(selected, repo.current())
            vm.beginEditing()
            vm.updateDraft(whisper)
            vm.apply()
            runCurrent()
            assertEquals(whisper, repo.current())
            vm.cancelDraft()
            vm.beginEditing()
            assertEquals(whisper, vm.state.value.draft)
            vm.updateDraft(whisper.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            assertTrue(vm.state.value.usesSystemRecognition)
            assertEquals(selected.speechRecognitionService, vm.state.value.draft.speechRecognitionService)
            vm.cancelDraft()
            assertTrue(vm.state.value.recognitionServices.isEmpty())
            vm.reset()
            runCurrent()
            val reset = AppSettingsTestFixtures.freshInstall.copy(languagePair = selected.languagePair, translationSetupComplete = true)
            assertEquals(reset, repo.current())
            assertEquals(repo.current(), vm.state.value.draft)
            assertFalse(vm.state.value.usesSystemRecognition)
            assertEquals(null, vm.state.value.draft.speechRecognitionService)
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            val reopened = AppSettingsRepository(file, dispatcher)
            try {
                runCurrent()
                assertEquals(reset, reopened.current())
            } finally { reopened.closeAndJoin() }
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun explicitSystemSelectionRefreshesCurrentServiceChoicesAfterNativeBackends() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "native-reset-services.json"), dispatcher)
        val firstService = "installed.provider/.RecognitionService"
        val replacementService = "replacement.provider/.RecognitionService"
        var inventory: List<Pair<String?, String>> = listOf(firstService to "Installed service")
        var discoveries = 0
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it); true }, MutableStateFlow(false), { discoveries++; inventory },
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            repo.save(AppSettings(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER))
            runCurrent()
            vm.beginEditing()
            assertTrue(vm.state.value.recognitionServices.isEmpty())
            assertEquals("Whisper settings must not probe Android services", 0, discoveries)

            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            runCurrent()
            assertTrue(vm.state.value.usesSystemRecognition)
            assertTrue("Selecting System must immediately offer the installed provider",
                vm.state.value.recognitionServices.any { it.first == firstService })
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = firstService))
            vm.apply()
            runCurrent()
            assertEquals(firstService, repo.current().speechRecognitionService)

            val discoveriesBeforeWhisper = discoveries
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER))
            vm.apply()
            runCurrent()
            assertTrue(vm.state.value.recognitionServices.isEmpty())
            assertEquals("Whisper settings must not probe Android services", discoveriesBeforeWhisper, discoveries)
            inventory = listOf(replacementService to "Replacement service")

            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            runCurrent()
            assertTrue(vm.state.value.usesSystemRecognition)
            assertTrue("Selecting System again must offer the current, not cached, installed provider",
                vm.state.value.recognitionServices.any { it.first == replacementService })
            assertFalse(vm.state.value.recognitionServices.any { it.first == firstService })
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = replacementService))
            vm.apply()
            runCurrent()
            assertEquals(replacementService, repo.current().speechRecognitionService)
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun inventoryRefreshRetainsMissingDraftUntilUserRepairsOrResetsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "services.json"), dispatcher)
        val selected = "installed.provider/.RecognitionService"
        var inventory: List<Pair<String?, String>> = listOf(null to "Default", selected to "Installed service")
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it); true }, MutableStateFlow(false), { inventory },
            completeSetup = { error("Unexpected setup action") })
        try {
            runCurrent()
            repo.save(AppSettings(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = selected))
            inventory = listOf(null to "Default")
            vm.refreshRecognitionServices()
            assertEquals(selected, vm.state.value.draft.speechRecognitionService)
            assertTrue(vm.state.value.canApply)
            assertEquals(inventory, vm.state.value.recognitionServices)
            vm.apply()
            runCurrent()
            assertEquals(selected, repo.current().speechRecognitionService)
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = null))
            vm.cancelDraft()
            assertEquals(selected, vm.state.value.draft.speechRecognitionService)
            vm.beginEditing()
            inventory = listOf(null to "Default", selected to "Reinstalled service")
            vm.refreshRecognitionServices()
            assertEquals(selected, vm.state.value.draft.speechRecognitionService)
            assertEquals(inventory, vm.state.value.recognitionServices)
            vm.reset()
            runCurrent()
            assertEquals(null, repo.current().speechRecognitionService)
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }
    @Test fun firstChoiceCompletesOnlyAfterPersistenceAndCannotBeRepeated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            for (engineId in listOf("mlkit", "tencent")) {
                val file = File(temp.root, "setup-$engineId.json")
                val repo = AppSettingsRepository(file, dispatcher)
                val gate = CompletableDeferred<Unit>()
                var writes = 0
                val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
                    { repo.save(it); true }, MutableStateFlow(false), completeSetup = { chosen ->
                        writes++
                        gate.await()
                        repo.save(repo.current().copy(translationEngineId = chosen, translationSetupComplete = true))
                        true
                    })
                try {
                    vm.completeTranslationSetup(engineId)
                    assertEquals(0, writes)
                    runCurrent()
                    vm.completeTranslationSetup(engineId)
                    runCurrent()
                    assertTrue(vm.state.value.saving)
                    assertFalse(vm.state.value.translationSetupComplete)
                    assertFalse(repo.current().translationSetupComplete)
                    vm.completeTranslationSetup(engineId)
                    gate.complete(Unit)
                    runCurrent()
                    assertTrue(vm.state.value.translationSetupComplete)
                    assertEquals(engineId, repo.current().translationEngineId)
                    vm.completeTranslationSetup(engineId)
                    runCurrent()
                    assertEquals(1, writes)
                } finally {
                    gate.complete(Unit)
                    vm.viewModelScope.cancel()
                    repo.closeAndJoin()
                }
                val reopened = AppSettingsRepository(file, dispatcher)
                try {
                    runCurrent()
                    assertTrue(reopened.current().translationSetupComplete)
                    assertEquals(engineId, reopened.current().translationEngineId)
                } finally { reopened.closeAndJoin() }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun rejectedFailedBusyAndTeardownSetupNeverCompleteAndExplicitRetryWorks() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "setup-failure.json"), dispatcher)
        val editable = MutableStateFlow(false)
        val teardown = MutableStateFlow(false)
        var attempts = 0
        val vm = SettingsViewModel(repo, editable, { emptyList() }, { repo.save(it); true }, teardown,
            completeSetup = { engineId ->
                when (++attempts) {
                    1 -> false
                    2 -> throw java.io.IOException("Storage unavailable")
                    else -> {
                        repo.save(repo.current().copy(translationEngineId = engineId, translationSetupComplete = true))
                        true
                    }
                }
            })
        try {
            runCurrent()
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertEquals(0, attempts)
            editable.value = true
            teardown.value = true
            runCurrent()
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertEquals(0, attempts)
            teardown.value = false
            runCurrent()
            repeat(2) {
                vm.completeTranslationSetup("mlkit")
                runCurrent()
                assertTrue(vm.state.value.saveError)
                assertFalse(vm.state.value.translationSetupComplete)
                assertFalse(repo.current().translationSetupComplete)
                assertFalse(vm.state.value.saving)
            }
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertTrue(vm.state.value.translationSetupComplete)
            assertFalse(vm.state.value.saveError)
            assertEquals("mlkit", repo.current().translationEngineId)
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun ordinaryDraftApplyAndResetPreserveLatestCommittedCompletion() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "setup-draft.json"), dispatcher)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() }, { repo.save(it); true },
            MutableStateFlow(false), completeSetup = { error("Ordinary editing must not complete setup") })
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(translationSetupComplete = true, ttsSpeedPercent = 110))
            assertFalse(vm.state.value.translationSetupComplete)
            vm.apply()
            runCurrent()
            assertFalse(repo.current().translationSetupComplete)
            repo.save(repo.current().copy(languagePair = frenchSpanish, translationSetupComplete = true))
            runCurrent()
            vm.updateDraft(vm.state.value.draft.copy(translationSetupComplete = false, ttsSpeedPercent = 115))
            vm.apply()
            runCurrent()
            assertTrue(repo.current().translationSetupComplete)
            assertEquals(frenchSpanish, repo.current().languagePair)
            vm.reset()
            runCurrent()
            assertTrue(repo.current().translationSetupComplete)
            assertTrue(vm.state.value.translationSetupComplete)
            assertEquals(frenchSpanish, repo.current().languagePair)
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun loadFailureAndInvalidChoiceCannotCompleteSetup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File(temp.root, "setup-load-error.json")
        file.writeText(AppSettingsTestFixtures.document().put("translationSetupComplete", false).toString())
        var failRead = true
        val delegate = AppSettingsSerializer()
        val serializer = object : androidx.datastore.core.Serializer<AppSettings> {
            override val defaultValue = delegate.defaultValue
            override suspend fun readFrom(input: java.io.InputStream): AppSettings {
                if (failRead) throw java.io.IOException("Unavailable settings")
                return delegate.readFrom(input)
            }
            override suspend fun writeTo(t: AppSettings, output: java.io.OutputStream) = delegate.writeTo(t, output)
        }
        val repo = AppSettingsRepository(file, dispatcher, serializer)
        var saves = 0
        val vm = SettingsViewModel(repo, MutableStateFlow(true),
            { if (it.translationEngineId == "unknown") listOf(ConfigurationProblem.UNKNOWN_ENGINE) else emptyList() },
            { repo.save(it); true }, MutableStateFlow(false), completeSetup = { engineId ->
                saves++
                repo.save(repo.current().copy(translationEngineId = engineId, translationSetupComplete = true))
                true
            })
        try {
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertTrue(vm.state.value.loadError)
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertEquals(0, saves)
            assertFalse(vm.state.value.translationSetupComplete)
            failRead = false
            vm.retry()
            runCurrent()
            assertFalse(vm.state.value.loadError)
            vm.completeTranslationSetup("unknown")
            runCurrent()
            assertEquals(0, saves)
            assertTrue(ConfigurationProblem.UNKNOWN_ENGINE in vm.state.value.problems)
            assertFalse(repo.current().translationSetupComplete)
            vm.completeTranslationSetup("mlkit")
            runCurrent()
            assertEquals(1, saves)
            assertTrue(vm.state.value.translationSetupComplete)
        } finally {
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            Dispatchers.resetMain()
        }
    }
}
