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

    @Test fun invalidAdvancedDraftAndBusyApplyNeverPersist() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "validation.json"), dispatcher)
        val editable = MutableStateFlow(true)
        val vm = SettingsViewModel(repo, editable, { if (it.vadMinSilenceMs !in 300..1500) listOf(ConfigurationProblem.INVALID_SILENCE) else emptyList() },
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false))
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
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false))
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
                speechOutputBackend = SpeechOutputBackend.PIPER, ttsSpeedPercent = 110))
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionService = "missing.provider/.RecognitionService"))
            repo.save(repo.current().copy(languagePair = frenchSpanish))
            runCurrent()
            val draft = AppSettings(languagePair = frenchSpanish, speechRecognitionBackend = SpeechRecognitionBackend.SHERPA,
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
            assertEquals(AppSettings(languagePair = frenchSpanish), repo.current())
            assertEquals(SpeechOutputBackend.SYSTEM_OFFLINE, vm.state.value.draft.speechOutputBackend)
            assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, vm.state.value.draft.speechRecognitionBackend)
            assertFalse(vm.state.value.usesSherpaRecognition)
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
        }, MutableStateFlow(false))
        try {
            runCurrent()
            vm.beginEditing()
            vm.updateDraft(vm.state.value.draft.copy(ttsSpeedPercent = 120, speechOutputBackend = SpeechOutputBackend.PIPER,
                speechRecognitionBackend = SpeechRecognitionBackend.SHERPA))
            vm.apply()
            runCurrent()
            assertTrue(vm.state.value.saving)
            repo.save(repo.current().copy(languagePair = frenchSpanish))
            runCurrent()
            gate.complete(Unit)
            val actual = AppSettings(languagePair = frenchSpanish, ttsSpeedPercent = 120,
                speechOutputBackend = SpeechOutputBackend.PIPER, speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)
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
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() }, { false }, teardown)
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

    @Test fun independentRecognitionAndOutputChoicesPersistAndReloadWithoutChangingPairOrLid() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val file = File(temp.root, "independent-backends.json")
        val repo = AppSettingsRepository(file, dispatcher)
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it.copy(languagePair = repo.current().languagePair)); true }, MutableStateFlow(false))
        try {
            runCurrent()
            val reversed = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN)
            repo.save(repo.current().copy(languagePair = reversed))
            runCurrent()
            vm.beginEditing()
            val originalLid = vm.state.value.draft.languageIdModelId
            vm.updateDraft(vm.state.value.draft.copy(speechOutputBackend = SpeechOutputBackend.PIPER))
            assertEquals(SpeechRecognitionBackend.SYSTEM_OFFLINE, vm.state.value.draft.speechRecognitionBackend)
            assertFalse(vm.state.value.usesSherpaRecognition)
            vm.apply()
            runCurrent()
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA))
            assertEquals(SpeechOutputBackend.PIPER, vm.state.value.draft.speechOutputBackend)
            assertTrue(vm.state.value.usesSherpaRecognition)
            vm.apply()
            runCurrent()
            vm.updateDraft(vm.state.value.draft.copy(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE))
            assertEquals(SpeechRecognitionBackend.SHERPA, vm.state.value.draft.speechRecognitionBackend)
            vm.apply()
            runCurrent()
            assertEquals(reversed, repo.current().languagePair)
            assertEquals(originalLid, repo.current().languageIdModelId)
            vm.updateDraft(vm.state.value.draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.SYSTEM_OFFLINE))
            vm.cancelDraft()
            assertEquals(SpeechRecognitionBackend.SHERPA, vm.state.value.draft.speechRecognitionBackend)
            assertEquals(reversed, vm.state.value.draft.languagePair)
            val committed = repo.current()
            vm.viewModelScope.cancel()
            repo.closeAndJoin()
            val reloaded = AppSettingsRepository(file, dispatcher)
            try {
                runCurrent()
                assertEquals(committed, reloaded.current())
                assertEquals(SpeechRecognitionBackend.SHERPA, reloaded.current().speechRecognitionBackend)
                assertEquals(SpeechOutputBackend.SYSTEM_OFFLINE, reloaded.current().speechOutputBackend)
            } finally { reloaded.closeAndJoin() }
        } finally { repo.closeAndJoin(); Dispatchers.resetMain() }
    }

    @Test fun inventoryRefreshRetainsMissingDraftUntilUserRepairsOrResetsIt() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = AppSettingsRepository(File(temp.root, "services.json"), dispatcher)
        val selected = "installed.provider/.RecognitionService"
        var inventory: List<Pair<String?, String>> = listOf(null to "Default", selected to "Installed service")
        val vm = SettingsViewModel(repo, MutableStateFlow(true), { emptyList() },
            { repo.save(it); true }, MutableStateFlow(false), { inventory })
        try {
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
}
