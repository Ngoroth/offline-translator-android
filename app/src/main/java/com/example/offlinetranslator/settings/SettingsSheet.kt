package com.example.offlinetranslator.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.ui.configurationProblemMessage
import com.example.offlinetranslator.ui.InterfaceLanguage
import com.example.offlinetranslator.ui.speechModelName

object SettingsTestTags {
    const val BODY = "settings_body"
    const val NOTICE = "settings_notice"
    const val APPLY = "settings_apply"
    const val RESET = "settings_reset"
    const val CLOSE = "settings_close"
    const val RETRY = "settings_retry"
    const val TEXT_ENGINE = "settings_text_engine"
    const val CONVERSATION_ENGINE = "settings_conversation_engine"
    const val VAD = "settings_vad"
    const val LID = "settings_lid"
    const val SILENCE = "settings_silence"
    const val SPEED = "settings_speed"
    const val OUTPUT_BACKEND = "settings_output_backend"
    const val RECOGNITION_BACKEND = "settings_recognition_backend"
    const val RECOGNITION_SERVICE = "settings_recognition_service"
    const val INTERFACE_LANGUAGE = "settings_interface_language"
}

@Composable
fun SettingsSheet(
    state: SettingsUiState,
    modelChoices: List<SpeechModelChoice>,
    providers: List<TranslationProviderDescriptor>,
    onDraftChanged: (AppSettings) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    interfaceLanguage: LanguageId = LanguageCatalog.ENGLISH,
    onInterfaceLanguageSelected: (LanguageId) -> Unit = {},
    interfaceLanguageError: Boolean = false,
) {
    val draft = state.draft
    val enabled = state.editable && !state.loading && !state.saving && !state.loadError && !state.saveError && !state.teardownError
    val height = LocalConfiguration.current.screenHeightDp.dp
    val context = androidx.compose.ui.platform.LocalContext.current
    val config = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val interfaceLanguageChoices = remember { InterfaceLanguage.choices.map { it.first.value to it.second } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides context,
            LocalConfiguration provides config,
            androidx.compose.ui.platform.LocalDensity provides density,
            androidx.compose.ui.platform.LocalResources provides resources,
        ) {
            Box(Modifier.heightIn(max = height).fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(), shape = MaterialTheme.shapes.large) {
                    Column {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(SettingsTestTags.BODY).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                        Choice(stringResource(R.string.settings_interface_language), interfaceLanguage.value,
                            interfaceLanguageChoices, true,
                            SettingsTestTags.INTERFACE_LANGUAGE) { onInterfaceLanguageSelected(LanguageId(it)) }
                        Text(stringResource(R.string.settings_interface_language_immediate), style = MaterialTheme.typography.bodySmall)
                        if (interfaceLanguageError) Text(stringResource(R.string.settings_interface_language_error),
                            color = MaterialTheme.colorScheme.error)
                        Column(Modifier.testTag(SettingsTestTags.NOTICE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.loading) Text(stringResource(R.string.settings_loading))
                            if (state.saving) Text(stringResource(R.string.settings_saving))
                            if (!state.editable && !state.loading) Text(stringResource(R.string.settings_busy))
                            if (state.corruptionNotice) Text(stringResource(R.string.settings_corruption), color = MaterialTheme.colorScheme.error)
                            if (state.loadError) Text(stringResource(R.string.settings_load_error), color = MaterialTheme.colorScheme.error)
                            if (state.saveError) Text(stringResource(R.string.settings_save_error), color = MaterialTheme.colorScheme.error)
                            if (state.teardownError) Text(stringResource(R.string.settings_teardown_error), color = MaterialTheme.colorScheme.error)
                            state.problems.forEach { Text(configurationProblemMessage(it), color = MaterialTheme.colorScheme.error) }
                            if (state.loadError || state.saveError) Button(onRetry, Modifier.testTag(SettingsTestTags.RETRY).heightIn(min = 48.dp), enabled = !state.saving) {
                                Text(stringResource(R.string.retry_action))
                            }
                        }
                        Choice(stringResource(R.string.settings_recognition_backend), draft.speechRecognitionBackend.persistedId, listOf(
                            SpeechRecognitionBackend.SYSTEM_OFFLINE.persistedId to stringResource(R.string.settings_recognition_system),
                            SpeechRecognitionBackend.SHERPA.persistedId to stringResource(R.string.settings_recognition_sherpa),
                            SpeechRecognitionBackend.WHISPER.persistedId to stringResource(R.string.settings_recognition_whisper),
                        ), enabled, SettingsTestTags.RECOGNITION_BACKEND) { selected ->
                            onDraftChanged(draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.entries.first { it.persistedId == selected }))
                        }
                        if (state.usesSystemRecognition) {
                            val defaultLabel = stringResource(R.string.recognition_service_on_device)
                            val services = listOf(DEFAULT_RECOGNITION_SERVICE to defaultLabel) +
                                state.recognitionServices.mapNotNull { (component, label) -> component?.let { it to label } }
                            Choice(stringResource(R.string.settings_recognition_service),
                                draft.speechRecognitionService ?: DEFAULT_RECOGNITION_SERVICE,
                                services, enabled, SettingsTestTags.RECOGNITION_SERVICE) { selected ->
                                onDraftChanged(draft.copy(speechRecognitionService = selected.takeUnless { it == DEFAULT_RECOGNITION_SERVICE }))
                            }
                        }
                        Choice(stringResource(R.string.settings_output_backend), draft.speechOutputBackend.persistedId, listOf(
                            SpeechOutputBackend.SYSTEM_OFFLINE.persistedId to stringResource(R.string.settings_output_system),
                            SpeechOutputBackend.PIPER.persistedId to stringResource(R.string.settings_output_piper),
                        ), enabled, SettingsTestTags.OUTPUT_BACKEND) { selected ->
                            onDraftChanged(draft.copy(speechOutputBackend = SpeechOutputBackend.entries.first { it.persistedId == selected }))
                        }
                        val engines = remember(providers) { providers.map { it.id to it.displayName } }
                        val onlyEngineId = engines.singleOrNull()?.first
                        if (engines.size > 1 || draft.textEngineId != onlyEngineId) {
                            Choice(stringResource(R.string.settings_text_engine), draft.textEngineId, engines, enabled, SettingsTestTags.TEXT_ENGINE) {
                                onDraftChanged(draft.copy(textEngineId = it))
                            }
                        }
                        if (engines.size > 1 || draft.conversationEngineId != onlyEngineId) {
                            Choice(stringResource(R.string.settings_conversation_engine), draft.conversationEngineId, engines, enabled, SettingsTestTags.CONVERSATION_ENGINE) {
                                onDraftChanged(draft.copy(conversationEngineId = it))
                            }
                        }
                        val vadChoices = modelChoices.filter { it.role == SpeechModelRole.VAD }.map { it.id to speechModelName(it.id, it.displayName) }
                        Choice(stringResource(R.string.settings_vad), draft.vadModelId, vadChoices, enabled, SettingsTestTags.VAD) {
                            onDraftChanged(draft.copy(vadModelId = it))
                        }
                        if (state.usesSherpaRecognition) {
                            val lidChoices = modelChoices.filter { it.role == SpeechModelRole.LANGUAGE_ID }.map { it.id to speechModelName(it.id, it.displayName) }
                            Choice(stringResource(R.string.settings_lid), draft.languageIdModelId, lidChoices, enabled, SettingsTestTags.LID) {
                                onDraftChanged(draft.copy(languageIdModelId = it))
                            }
                        }
                        Choice(stringResource(R.string.settings_silence), draft.vadMinSilenceMs.toString(), (300..1500 step 100).map {
                            it.toString() to stringResource(R.string.settings_milliseconds, it)
                        }, enabled, SettingsTestTags.SILENCE) { onDraftChanged(draft.copy(vadMinSilenceMs = it.toInt())) }
                        Choice(stringResource(R.string.settings_speed), draft.ttsSpeedPercent.toString(), (75..125 step 5).map {
                            it.toString() to stringResource(R.string.settings_percent, it)
                        }, enabled, SettingsTestTags.SPEED) { onDraftChanged(draft.copy(ttsSpeedPercent = it.toInt())) }
                        Text(stringResource(R.string.settings_about_translation), style = MaterialTheme.typography.titleMedium)
                        for (provider in providers) {
                            if (provider.id == draft.textEngineId || provider.id == draft.conversationEngineId) {
                                Text(when (provider.id) {
                                    "mlkit" -> stringResource(R.string.settings_mlkit_summary)
                                    "tencent" -> stringResource(R.string.tencent_model_explanation)
                                    else -> stringResource(R.string.settings_provider_summary, provider.displayName)
                                })
                            }
                        }
                        Text(stringResource(R.string.settings_advanced_reset_explanation), style = MaterialTheme.typography.bodySmall)
                        TextButton(onReset, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.RESET),
                            enabled = state.canReset, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Text(stringResource(R.string.settings_reset))
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Button(onApply, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.APPLY), enabled = state.canApply, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.settings_apply)) }
                        TextButton(onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.CLOSE), contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.close_action)) }
                    }
                }
            }
        }
    }
}
}

// A UI-only ID: flattened Android components always contain a slash.
internal const val DEFAULT_RECOGNITION_SERVICE = "on-device"

@Composable
private fun Choice(label: String, selected: String, options: List<Pair<String, String>>, enabled: Boolean, tag: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) {
                Text(current ?: stringResource(R.string.settings_unavailable, selected), color = if (current == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }, modifier = Modifier.testTag("${tag}_option_$id"))
                }
            }
        }
    }
}
