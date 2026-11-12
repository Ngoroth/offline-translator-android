package io.github.ngoroth.offlinetranslator

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import io.github.ngoroth.offlinetranslator.ui.InterfaceLanguage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ngoroth.offlinetranslator.conversation.ConversationRoute
import io.github.ngoroth.offlinetranslator.runtime.AppRuntime
import io.github.ngoroth.offlinetranslator.core.ConversationSessionConfig
import io.github.ngoroth.offlinetranslator.core.SpeechCaptureEngine
import io.github.ngoroth.offlinetranslator.speechmodels.SpeechModelStore
import io.github.ngoroth.offlinetranslator.settings.SettingsSheet
import io.github.ngoroth.offlinetranslator.settings.SettingsViewModel
import io.github.ngoroth.offlinetranslator.settings.TranslationSetupGate
import io.github.ngoroth.offlinetranslator.translation.TranslationRoute
import io.github.ngoroth.offlinetranslator.speech.AndroidRecognitionServices
import io.github.ngoroth.offlinetranslator.ui.ScreenAwake

@Composable
fun TranslatorHome(
    captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine =
        AppRuntime.get(LocalContext.current).conversationCaptureFactory,
    screenAwake: ScreenAwake? = null,
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
            persistReset = runtime::resetSettings,
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
                screenAwake = screenAwake,
                onOpenAdvancedSettings = {
                    interfaceLanguageError = false
                    advancedSettingsOpen = true
                    settingsVm.beginEditing()
                    settingsOpen = true
                },
            )
        },
        textContent = { homeControls ->
            TranslationRoute(homeControls = homeControls, onLanguageTransitionChanged = { languageChanging = it },
                screenAwake = screenAwake)
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

