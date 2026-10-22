package com.example.offlinetranslator.settings

import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.SpeechRecognitionBackend

data class SettingsUiState(
    val draft: AppSettings = AppSettings(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val editable: Boolean = false,
    val corruptionNotice: Boolean = false,
    val loadError: Boolean = false,
    val saveError: Boolean = false,
    val teardownError: Boolean = false,
    val problems: List<ConfigurationProblem> = emptyList(),
    val recognitionServices: List<Pair<String?, String>> = emptyList(),
) {
    val usesSherpaRecognition: Boolean get() = draft.speechRecognitionBackend == SpeechRecognitionBackend.SHERPA
    val usesSystemRecognition: Boolean get() = draft.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE
    val canApply: Boolean get() = editable && !loading && !saving && !loadError && !teardownError && problems.isEmpty()
    val canReset: Boolean get() = editable && !loading && !saving && !loadError && !teardownError
}
