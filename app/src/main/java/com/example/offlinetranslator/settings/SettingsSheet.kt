package com.example.offlinetranslator.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.ui.InterfaceLanguage
import com.example.offlinetranslator.ui.configurationProblemMessage

object SettingsTestTags {
    const val BODY = "settings_body"
    const val NOTICE = "settings_notice"
    const val APPLY = "settings_apply"
    const val RESET = "settings_reset"
    const val CLOSE = "settings_close"
    const val RETRY = "settings_retry"
    const val TRANSLATION_ENGINE = "settings_translation_engine"
    const val ADVANCED = "settings_advanced"
    const val SETUP = "translation_setup"
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
    providers: List<TranslationProviderDescriptor>,
    onDraftChanged: (AppSettings) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    interfaceLanguage: LanguageId = LanguageCatalog.ENGLISH,
    onInterfaceLanguageSelected: (LanguageId) -> Unit = {},
    interfaceLanguageError: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    val draft = state.draft
    val enabled = state.editable && !state.loading && !state.saving && !state.loadError && !state.teardownError
    var advancedExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
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
            Box(Modifier.heightIn(max = height).fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight(), shape = MaterialTheme.shapes.large) {
                    Column {
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag(SettingsTestTags.BODY).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)
                            SettingsNotices(state, onRetry)
                            TranslationEngineChoice(draft.translationEngineId, providers, enabled) {
                                onDraftChanged(draft.copy(translationEngineId = it))
                            }
                            Choice(stringResource(R.string.settings_interface_language), interfaceLanguage.value,
                                interfaceLanguageChoices, true, SettingsTestTags.INTERFACE_LANGUAGE) {
                                onInterfaceLanguageSelected(LanguageId(it))
                            }
                            Text(stringResource(R.string.settings_interface_language_immediate), style = MaterialTheme.typography.bodySmall)
                            if (interfaceLanguageError) Text(stringResource(R.string.settings_interface_language_error), color = MaterialTheme.colorScheme.error)
                            Choice(stringResource(R.string.settings_speed), draft.ttsSpeedPercent.toString(), (75..125 step 5).map {
                                it.toString() to stringResource(R.string.settings_percent, it)
                            }, enabled, SettingsTestTags.SPEED) { onDraftChanged(draft.copy(ttsSpeedPercent = it.toInt())) }
                            val expansionDescription = stringResource(if (advancedExpanded) R.string.settings_advanced_expanded else R.string.settings_advanced_collapsed)
                            OutlinedButton(
                                onClick = { advancedExpanded = !advancedExpanded },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.ADVANCED)
                                    .semantics { stateDescription = expansionDescription },
                            ) { Text(stringResource(R.string.settings_advanced)) }
                            if (advancedExpanded) {
                                Choice(stringResource(R.string.settings_recognition_backend), draft.speechRecognitionBackend.persistedId, listOf(
                                    SpeechRecognitionBackend.WHISPER.persistedId to stringResource(R.string.settings_recognition_whisper),
                                    SpeechRecognitionBackend.SYSTEM_OFFLINE.persistedId to stringResource(R.string.settings_recognition_system),
                                ), enabled, SettingsTestTags.RECOGNITION_BACKEND) { selected ->
                                    onDraftChanged(draft.copy(speechRecognitionBackend = SpeechRecognitionBackend.entries.first { it.persistedId == selected }))
                                }
                                if (state.usesSystemRecognition) {
                                    val services = listOf(DEFAULT_RECOGNITION_SERVICE to stringResource(R.string.recognition_service_on_device)) +
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
                                Choice(stringResource(R.string.settings_silence), draft.vadMinSilenceMs.toString(), (300..1500 step 100).map {
                                    it.toString() to stringResource(R.string.settings_milliseconds, it)
                                }, enabled, SettingsTestTags.SILENCE) { onDraftChanged(draft.copy(vadMinSilenceMs = it.toInt())) }
                                Text(stringResource(R.string.settings_advanced_reset_explanation), style = MaterialTheme.typography.bodySmall)
                                TextButton(onReset, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.RESET),
                                    enabled = state.canReset, contentPadding = PaddingValues(horizontal = 12.dp)) {
                                    Text(stringResource(R.string.settings_reset))
                                }
                            }
                        }
                        HorizontalDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Button(onApply, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.APPLY), enabled = state.canApply,
                                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.settings_apply)) }
                            TextButton(onClose, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SettingsTestTags.CLOSE),
                                contentPadding = PaddingValues(horizontal = 12.dp)) { Text(stringResource(R.string.close_action)) }
                        }
                    }
                }
            }
        }
    }
}

/** Does not compose either route until setup has been durably committed. */
@Composable
internal fun TranslationSetupGate(
    state: SettingsUiState,
    providers: List<TranslationProviderDescriptor>,
    onComplete: (String) -> Unit,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!state.loading && !state.loadError && state.translationSetupComplete) {
        content()
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val enabled = state.editable && !state.loading && !state.saving && !state.loadError && !state.teardownError
    val height = LocalConfiguration.current.screenHeightDp.dp
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    Surface(Modifier.fillMaxSize()) {}
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalContext provides context,
            LocalConfiguration provides configuration,
            androidx.compose.ui.platform.LocalDensity provides density,
            androidx.compose.ui.platform.LocalResources provides resources,
        ) {
        Box(Modifier.heightIn(max = height).fillMaxSize().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 520.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large) {
                Column(Modifier.testTag(SettingsTestTags.SETUP)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.translation_setup_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.translation_setup_explanation))
                        SettingsNotices(state, onRetry = {}, showRetry = false)
                        TranslationEngineChoice(selected, providers, enabled) { engineId ->
                            selected = engineId
                            onComplete(engineId)
                        }
                    }
                    if (state.loadError || state.saveError) {
                        HorizontalDivider()
                        Button(
                            onClick = { if (state.loadError) onRetry() else selected?.let(onComplete) },
                            modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag(SettingsTestTags.RETRY),
                            enabled = !state.loading && !state.saving && !state.teardownError && (state.loadError || (state.editable && selected != null)),
                        ) { Text(stringResource(R.string.retry_action)) }
                    }
                }
            }
        }
        }
    }
}

@Composable
internal fun TranslationEngineChoice(
    selected: String?,
    providers: List<TranslationProviderDescriptor>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().selectableGroup().testTag(SettingsTestTags.TRANSLATION_ENGINE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_translation_engine), style = MaterialTheme.typography.titleMedium)
        if (selected != null && providers.none { it.id == selected }) {
            Text(stringResource(R.string.settings_unavailable, selected), color = MaterialTheme.colorScheme.error)
        }
        for (id in listOf("mlkit", "tencent")) {
            val provider = providers.firstOrNull { it.id == id } ?: continue
            Surface(shape = MaterialTheme.shapes.medium, color = if (selected == id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected == id, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(id) })
                    .testTag("${SettingsTestTags.TRANSLATION_ENGINE}_option_$id").padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected == id, onClick = null, enabled = enabled)
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (id == "mlkit") R.string.translation_choice_fast else R.string.translation_choice_accurate), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(if (id == "mlkit") R.string.translation_choice_fast_description else R.string.translation_choice_accurate_description), style = MaterialTheme.typography.bodySmall)
                        Text(provider.displayName, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text(stringResource(R.string.translation_choice_disclaimer), style = MaterialTheme.typography.bodySmall)
        if (selected == "tencent") Text(stringResource(R.string.tencent_model_explanation), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsNotices(state: SettingsUiState, onRetry: () -> Unit, showRetry: Boolean = true) {
    Column(Modifier.testTag(SettingsTestTags.NOTICE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) Text(stringResource(R.string.settings_loading))
        if (state.saving) Text(stringResource(R.string.settings_saving))
        if (!state.editable && !state.loading) Text(stringResource(R.string.settings_busy))
        if (state.corruptionNotice) Text(stringResource(R.string.settings_corruption), color = MaterialTheme.colorScheme.error)
        if (state.loadError) Text(stringResource(R.string.settings_load_error), color = MaterialTheme.colorScheme.error)
        if (state.saveError) Text(stringResource(R.string.settings_save_error), color = MaterialTheme.colorScheme.error)
        if (state.teardownError) Text(stringResource(R.string.settings_teardown_error), color = MaterialTheme.colorScheme.error)
        state.problems.forEach { Text(configurationProblemMessage(it), color = MaterialTheme.colorScheme.error) }
        if (showRetry && (state.loadError || state.saveError)) Button(onRetry, Modifier.testTag(SettingsTestTags.RETRY).heightIn(min = 48.dp), enabled = !state.saving) {
            Text(stringResource(R.string.retry_action))
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
