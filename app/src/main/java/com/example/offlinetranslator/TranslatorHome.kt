package com.example.offlinetranslator

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.example.offlinetranslator.ui.InterfaceLanguage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.offlinetranslator.conversation.ConversationRoute
import com.example.offlinetranslator.runtime.AppRuntime
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.SpeechCaptureEngine
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.settings.SettingsSheet
import com.example.offlinetranslator.settings.SettingsViewModel
import com.example.offlinetranslator.settings.TranslationSetupGate
import com.example.offlinetranslator.translation.TranslationRoute
import com.example.offlinetranslator.speech.AndroidRecognitionServices

@Composable
fun TranslatorHome(
    captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine =
        AppRuntime.get(LocalContext.current).conversationCaptureFactory,
) {
    val context = LocalContext.current
    val runtime = remember(context) { AppRuntime.get(context) }
    val discoveryContext = context.applicationContext
    val settingsVm: SettingsViewModel = viewModel {
        SettingsViewModel(
            repository = runtime.settings,
            canEdit = runtime.editable,
            validate = runtime::validationProblems,
            completeSetup = runtime::completeTranslationSetup,
            persist = runtime::applySettings,
            teardownFailed = runtime.owners.teardownFailed,
            recognitionServices = { AndroidRecognitionServices.choices(discoveryContext).map { it.component to it.label } },
        )
    }
    val settingsState by settingsVm.state.collectAsStateWithLifecycle()
    var conversation by rememberSaveable { mutableStateOf(true) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var advancedSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var languageChanging by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val interfaceLanguage = remember(context, configuration) { InterfaceLanguage.current(context) }
    var interfaceLanguageError by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (settingsOpen) settingsVm.refreshRecognitionServices()
    }

    TranslationSetupGate(
        state = settingsState,
        providers = runtime.registry.descriptors,
        onComplete = settingsVm::completeTranslationSetup,
        onRetry = settingsVm::retry,
    ) {
    TranslatorHomeLayout(
        conversation = conversation,
        onConversationChanged = { conversation = it },
        onOpenSettings = {
            interfaceLanguageError = false
            advancedSettingsOpen = false
            settingsVm.beginEditing()
            settingsOpen = true
        },
        conversationContent = {
            ConversationRoute(
                captureFactory, onLanguageTransitionChanged = { languageChanging = it }, onOpenText = { conversation = false },
                onOpenAdvancedSettings = {
                    interfaceLanguageError = false
                    advancedSettingsOpen = true
                    settingsVm.beginEditing()
                    settingsOpen = true
                },
            )
        },
        textContent = { homeControls ->
            TranslationRoute(homeControls = homeControls, onLanguageTransitionChanged = { languageChanging = it })
        },
    )

    if (settingsOpen) {
        SettingsSheet(
            state = settingsState.copy(editable = settingsState.editable && !languageChanging),
            providers = runtime.registry.descriptors,
            onDraftChanged = settingsVm::updateDraft,
            onApply = { if (!languageChanging) settingsVm.apply() },
            onReset = { if (!languageChanging) settingsVm.reset() },
            onRetry = { settingsVm.retry() },
            onClose = {
                settingsVm.cancelDraft()
                settingsOpen = false
            },
            interfaceLanguage = interfaceLanguage,
            onInterfaceLanguageSelected = { language ->
                interfaceLanguageError = try {
                    InterfaceLanguage.apply(context, language)
                    false
                } catch (_: RuntimeException) {
                    true
                }
            },
            interfaceLanguageError = interfaceLanguageError,
            initiallyExpanded = advancedSettingsOpen,
        )
    }
    }
}

