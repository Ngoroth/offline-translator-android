package com.example.offlinetranslator.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.ConfigurationProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: AppSettingsRepository,
    private val canEdit: StateFlow<Boolean>,
    private val validate: (AppSettings) -> List<ConfigurationProblem>,
    private val persist: suspend (AppSettings) -> Boolean,
    private val teardownFailed: StateFlow<Boolean>,
    private val recognitionServices: () -> List<Pair<String?, String>> = { emptyList() },
    private val completeSetup: suspend (String) -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state = mutableState.asStateFlow()
    private var editing = false
    private var committed: AppSettings? = null

    init {
        viewModelScope.launch {
            combine(repository.state, canEdit, repository.corruptionNotice, teardownFailed) { loaded, editable, notice, failed ->
                SettingsObservation(loaded, editable, notice, failed)
            }.collect { (loaded, editable, notice, failed) ->
                    committed = loaded.settings
                    val draft = loaded.settings?.let {
                        if (editing) mutableState.value.draft.copy(
                            languagePair = it.languagePair,
                            translationSetupComplete = it.translationSetupComplete,
                        ) else it
                    } ?: mutableState.value.draft
                    mutableState.value = mutableState.value.copy(
                        draft = draft, loading = loaded.loading, loadError = loaded.loadError,
                        translationSetupComplete = loaded.settings?.translationSetupComplete == true,
                        teardownError = failed,
                        editable = editable, corruptionNotice = notice, problems = validate(draft),
                    )
                }
        }
    }

    fun beginEditing() {
        editing = true
        val draft = committed ?: mutableState.value.draft
        mutableState.value = mutableState.value.copy(draft = draft, saveError = false, problems = validate(draft))
        refreshRecognitionServices()
    }

    fun refreshRecognitionServices() {
        val services = if (state.value.usesSystemRecognition) recognitionServices() else emptyList()
        mutableState.value = mutableState.value.copy(recognitionServices = services)
    }

    fun updateDraft(value: AppSettings) {
        val current = state.value
        if (!current.editable || current.loading || current.saving || current.loadError) return
        val frozen = value.copy(
            languagePair = committed?.languagePair ?: current.draft.languagePair,
            translationSetupComplete = committed?.translationSetupComplete ?: false,
        )
        mutableState.value = current.copy(draft = frozen, saveError = false, problems = validate(frozen))
        if (current.draft.speechRecognitionBackend != frozen.speechRecognitionBackend) refreshRecognitionServices()
    }

    fun apply() {
        if (!state.value.canApply) return
        submit(state.value.draft)
    }

    fun reset() {
        if (!state.value.canReset) return
        submit(AppSettings(
            languagePair = committed?.languagePair ?: state.value.draft.languagePair,
            translationSetupComplete = committed?.translationSetupComplete ?: false,
        ))
    }

    fun completeTranslationSetup(engineId: String) {
        val current = state.value
        if (!current.canReset || current.translationSetupComplete) return
        val latest = committed ?: return
        submit(latest.copy(translationEngineId = engineId, translationSetupComplete = true)) {
            completeSetup(engineId)
        }
    }

    fun cancelDraft() {
        editing = false
        val previousBackend = state.value.draft.speechRecognitionBackend
        val original = committed ?: state.value.draft
        mutableState.value = state.value.copy(draft = original, saveError = false, problems = validate(original))
        if (previousBackend != original.speechRecognitionBackend) refreshRecognitionServices()
    }

    fun retry() {
        if (state.value.saving) return
        mutableState.value = state.value.copy(saveError = false)
        repository.retry()
    }

    private fun submit(value: AppSettings, save: suspend () -> Boolean = {
        persist(value.copy(translationSetupComplete = committed?.translationSetupComplete ?: false))
    }) {
        if (!state.value.canReset || !canEdit.value) return
        val problems = validate(value)
        if (problems.isNotEmpty()) {
            mutableState.value = state.value.copy(problems = problems)
            return
        }
        mutableState.value = state.value.copy(saving = true, saveError = false)
        viewModelScope.launch {
            try {
                val saved = save()
                mutableState.value = state.value.copy(saving = false, saveError = !saved && !teardownFailed.value, teardownError = teardownFailed.value)
                if (saved) {
                    val actual = repository.current()
                    committed = actual
                    val current = state.value
                    mutableState.value = current.copy(
                        draft = actual, translationSetupComplete = actual.translationSetupComplete,
                        problems = validate(actual), corruptionNotice = false,
                    )
                    if (current.draft.speechRecognitionBackend != actual.speechRecognitionBackend) refreshRecognitionServices()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = state.value.copy(saving = false, saveError = !teardownFailed.value, teardownError = teardownFailed.value)
            }
        }
    }
}

private data class SettingsObservation(
    val loaded: SettingsLoadState,
    val editable: Boolean,
    val notice: Boolean,
    val failed: Boolean,
)
