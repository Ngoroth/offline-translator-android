package com.example.offlinetranslator.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.AppRuntime
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.speech.AndroidRecognitionDataInstaller
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext


@Composable
fun ConversationRoute(
    captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine =
        AppRuntime.get(LocalContext.current).conversationCaptureFactory,
    onLanguageTransitionChanged: (Boolean) -> Unit = {},
    onOpenText: () -> Unit = {},
    onOpenAdvancedSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { AppRuntime.get(context.applicationContext) }
    val settingsState by runtime.settings.state.collectAsStateWithLifecycle()
    val settings = settingsState.settings ?: AppSettings()
    val loaded = !settingsState.loading && !settingsState.loadError && settingsState.settings != null
    val conversationProblem = remember(settings) { runtime.conversationProblems(settings).firstOrNull() }
    val config = remember(settings) { if (conversationProblem == null) runCatching { settings.conversationConfig() }.getOrNull() else null }
    val descriptor = remember(settings.conversationEngineId) { runtime.registry.descriptors.firstOrNull { it.id == settings.conversationEngineId } }
    val coverage = remember(settings) { runtime.conversationLanguageCoverage(settings) }
    val teardownFailed by runtime.owners.teardownFailed.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownershipVersion by runtime.owners.ownershipVersion.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val transitionCallback by rememberUpdatedState(onLanguageTransitionChanged)
    var viewModel by remember { mutableStateOf<ConversationViewModel?>(null) }
    var changing by remember { mutableStateOf(false) }
    var guardedCreation by remember { mutableStateOf(false) }
    var languageError by remember { mutableStateOf<ConversationLanguageChangeError?>(null) }
    var failedOpenConfig by remember { mutableStateOf<ConversationSessionConfig?>(null) }
    var installError by remember { mutableStateOf(false) }
    var recognitionInstallError by remember { mutableStateOf(false) }
    var installRequest by remember { mutableStateOf<Any?>(null) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var pendingStart by remember { mutableStateOf<ConversationViewModel?>(null) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    LaunchedEffect(config, conversationProblem, loaded, changing, teardownFailed, foreground, captureFactory, ownershipVersion) {
        if (changing || !foreground) return@LaunchedEffect
        if (!loaded || config == null || teardownFailed) {
            val old = viewModel
            viewModel = null
            try {
                old?.disposeAndJoin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                languageError = ConversationLanguageChangeError.TEARDOWN
            }
            return@LaunchedEffect
        }
        val latestSettings = runtime.settings.state.value.settings ?: return@LaunchedEffect
        if (!runtime.matches(latestSettings, config)) return@LaunchedEffect
        if (languageError == ConversationLanguageChangeError.OPEN && failedOpenConfig == config) return@LaunchedEffect
        if (viewModel?.let { runtime.matches(latestSettings, it.config) && it.hasCurrentOwner } == true) return@LaunchedEffect
        val old = viewModel
        viewModel = null
        try {
            old?.disposeAndJoin()
            coroutineContext.ensureActive()
            val currentSettings = runtime.settings.state.value.settings ?: return@LaunchedEffect
            if (!runtime.matches(currentSettings, config)) return@LaunchedEffect
            val owner = if (guardedCreation) runtime.createConversationOwnerIfUnowned(config, captureFactory)
                else runtime.createConversationOwner(config, captureFactory)
            if (owner != null) {
                guardedCreation = true
                viewModel = ConversationViewModel(owner, runtime.owners)
                if (languageError == ConversationLanguageChangeError.OPEN) languageError = null
            } else {
                val currentSettings = runtime.settings.state.value.settings ?: return@LaunchedEffect
                if (!runtime.matches(currentSettings, config)) return@LaunchedEffect
                failedOpenConfig = config
                languageError = ConversationLanguageChangeError.OPEN
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!runtime.owners.teardownFailed.value) {
                val currentSettings = runtime.settings.state.value.settings ?: return@LaunchedEffect
                if (!runtime.matches(currentSettings, config)) return@LaunchedEffect
            }
            languageError = if (runtime.owners.teardownFailed.value) ConversationLanguageChangeError.TEARDOWN else ConversationLanguageChangeError.OPEN
            if (languageError == ConversationLanguageChangeError.OPEN) failedOpenConfig = config
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            mounted = false
            pendingStart = null
            installRequest = null
            installJob?.cancel()
            transitionJob?.cancel()
            viewModel?.dispose()
            viewModel = null
            transitionCallback(false)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = pendingStart
        pendingStart = null
        val latest = runtime.settings.state.value.settings
        if (requested != null && requested === viewModel && latest != null && runtime.matches(latest, requested.config) &&
            requested.hasCurrentOwner && !changing && !runtime.owners.teardownFailed.value && mounted &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) requested.start() else requested.permissionDenied()
        }
    }
    val currentVm = viewModel
    DisposableEffect(lifecycleOwner, currentVm) {
        if (foreground) currentVm?.enterForeground()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { foreground = true; currentVm?.enterForeground() }
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    pendingStart = null
                    installRequest = null
                    installJob?.cancel()
                    transitionJob?.cancel()
                    currentVm?.leaveForeground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pendingStart = null
            installRequest = null
            installJob?.cancel()
            currentVm?.leaveForeground()
        }
    }
    val selectPair: (LanguagePair) -> Unit = select@{ selected ->
        val latest = runtime.settings.state.value.settings ?: return@select
        val vm = viewModel
        val preparing = vm?.preparation?.value in setOf(ConversationPreparation.CHECKING, ConversationPreparation.INSTALLING, ConversationPreparation.TRANSLATION)
        if (!loaded || !foreground || changing || teardownFailed || preparing || latest.languagePair != settings.languagePair ||
            selected == latest.languagePair || selected.source == selected.target ||
            LanguageCatalog.find(selected.source) == null || LanguageCatalog.find(selected.target) == null ||
            (vm != null && (!vm.hasCurrentOwner || !runtime.matches(latest, vm.config)))) return@select
        guardedCreation = true
        changing = true
        languageError = null
        installError = false
        recognitionInstallError = false
        installRequest = null
        installJob?.cancel()
        pendingStart = null
        transitionCallback(true)
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { vm?.disposeAndJoin() }
                if (viewModel === vm) viewModel = null
                coroutineContext.ensureActive()
                if (!mounted || !foreground) return@launch
                if (!runtime.updateLanguagePair(latest.languagePair, selected)) languageError = ConversationLanguageChangeError.SAVE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mounted) languageError = if (runtime.owners.teardownFailed.value) ConversationLanguageChangeError.TEARDOWN else ConversationLanguageChangeError.SAVE
            } finally {
                if (viewModel === vm) viewModel = null
                if (mounted) { changing = false; transitionCallback(false) }
            }
        }
    }
    val shown = currentVm?.takeIf { runtime.matches(settings, it.config) && it.hasCurrentOwner }
    val state = shown?.state?.collectAsStateWithLifecycle()?.value ?: ConversationState()
    val statuses = shown?.modelStatuses?.collectAsStateWithLifecycle()?.value ?: emptyList()
    val preparation = shown?.preparation?.collectAsStateWithLifecycle()?.value ?: ConversationPreparation.IDLE
    val ready = shown?.ready?.collectAsStateWithLifecycle()?.value ?: false
    val permissionDenied = shown?.permissionDenied?.collectAsStateWithLifecycle()?.value ?: false
    val outputReadiness = shown?.outputReadiness?.collectAsStateWithLifecycle()?.value
    val recognitionReadiness = shown?.recognitionReadiness?.collectAsStateWithLifecycle()?.value
    val translationPreparation = shown?.translationPreparation?.collectAsStateWithLifecycle()?.value
    val preparationFailure = shown?.preparationFailure?.collectAsStateWithLifecycle()?.value
    val localRecognitionEligible = remember(settings) {
        settings.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE &&
            (runtime.conversationProblems(settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.SHERPA)).isEmpty() ||
                runtime.conversationProblems(settings.copy(speechRecognitionBackend = SpeechRecognitionBackend.WHISPER)).isEmpty())
    }
    ConversationScreen(
        state = state, models = statuses, preparation = preparation,
        ready = ready && installRequest == null && !changing && !teardownFailed, permissionDenied = permissionDenied,
        ownerAvailable = shown != null,
        translationPreparation = translationPreparation, preparationFailure = preparationFailure,
        onPrepare = { if (installRequest == null && !changing && !teardownFailed) shown?.prepareLanguages() },
        onSessionAction = {
            if (shown?.state?.value?.sessionActive == true) { pendingStart = null; shown.stop() }
            else if (shown != null && installRequest == null && !changing && !teardownFailed && foreground) {
                val latest = runtime.settings.state.value.settings
                if (latest != null && runtime.matches(latest, shown.config) && shown.hasCurrentOwner) {
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) shown.start()
                    else if (pendingStart == null) { pendingStart = shown; permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                }
            }
        },
        provider = descriptor, configurationProblem = conversationProblem,
        pair = settings.languagePair, conversationCoverage = coverage,
        languageSelectionEnabled = loaded && foreground && !changing && !teardownFailed,
        languageChanging = changing,
        languageChangeError = if (teardownFailed) ConversationLanguageChangeError.TEARDOWN else languageError,
        onPairSelected = selectPair, onOpenText = onOpenText,
        systemRecognition = settings.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE,
        recognitionReadiness = recognitionReadiness,
        speechRecognitionService = settings.speechRecognitionService,
        canChooseLocalRecognition = localRecognitionEligible,
        onOpenAdvancedSettings = open@{
            val latest = runtime.settings.state.value.settings ?: return@open
            if (mounted && foreground && !changing && !runtime.owners.busy.value && !teardownFailed &&
                latest == settings && latest.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE) {
                onOpenAdvancedSettings()
            }
        },
        systemOutput = settings.speechOutputBackend == SpeechOutputBackend.SYSTEM_OFFLINE,
        canInstallRecognitionData = shown != null && settings.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE &&
            settings.speechRecognitionService != null &&
            recognitionReadiness in setOf(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, SpeechRecognitionReadiness.SCHEDULED) &&
            installRequest == null && foreground && !changing && !teardownFailed &&
            preparation in setOf(ConversationPreparation.IDLE, ConversationPreparation.ERROR) && !state.sessionActive,
        recognitionInstallError = recognitionInstallError,
        onInstallRecognitionData = install@{
            val requested = shown ?: return@install
            val initialSettings = runtime.settings.state.value.settings ?: return@install
            val initialOwnership = runtime.owners.ownershipVersion.value
            fun fresh(): Boolean {
                val latest = runtime.settings.state.value
                if (initialSettings.speechRecognitionBackend != SpeechRecognitionBackend.SYSTEM_OFFLINE) return false
                return mounted && foreground && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    viewModel === requested && requested.hasCurrentOwner && !changing &&
                    !runtime.owners.teardownFailed.value && !latest.loading && !latest.loadError &&
                    latest.settings == initialSettings && runtime.matches(initialSettings, requested.config) &&
                    runtime.owners.ownershipVersion.value == initialOwnership
            }
            if (installRequest != null || runtime.owners.busy.value || !fresh() || requested.state.value.sessionActive ||
                requested.preparation.value !in setOf(ConversationPreparation.IDLE, ConversationPreparation.ERROR)) return@install
            val token = Any()
            installRequest = token
            recognitionInstallError = false
            pendingStart = null
            installJob = scope.launch {
                try {
                    val request = requested.recognitionDataInstallRequest { installRequest === token && fresh() }
                    coroutineContext.ensureActive()
                    // owners.use has returned: external consent cannot retain the runtime owner gate.
                    if (installRequest === token && fresh()) recognitionInstallError = !AndroidRecognitionDataInstaller(context).install(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (installRequest === token && fresh()) recognitionInstallError = true
                } finally {
                    if (installRequest === token) installRequest = null
                }
            }
        },
        outputReadiness = outputReadiness, installError = installError,
        canInstallVoiceData = shown != null && outputReadiness == SpeechOutputReadiness.MISSING_LOCAL_VOICE &&
            installRequest == null && foreground && !changing &&
            !teardownFailed && (preparation == ConversationPreparation.IDLE || preparation == ConversationPreparation.ERROR) && !state.sessionActive,
        onInstallVoiceData = install@{
            val requested = shown ?: return@install
            val initialSettings = runtime.settings.state.value.settings ?: return@install
            val initialOwnership = runtime.owners.ownershipVersion.value
            fun fresh(): Boolean {
                val latest = runtime.settings.state.value
                return mounted && foreground && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                    viewModel === requested && requested.hasCurrentOwner && !changing &&
                    !runtime.owners.teardownFailed.value && !latest.loading && !latest.loadError &&
                    latest.settings == initialSettings && runtime.matches(initialSettings, requested.config) &&
                    runtime.owners.ownershipVersion.value == initialOwnership
            }
            if (installRequest != null || runtime.owners.busy.value || !fresh() ||
                requested.outputReadiness.value != SpeechOutputReadiness.MISSING_LOCAL_VOICE || requested.state.value.sessionActive ||
                (requested.preparation.value != ConversationPreparation.IDLE && requested.preparation.value != ConversationPreparation.ERROR)) return@install
            val token = Any()
            installRequest = token
            installError = false
            pendingStart = null
            installJob = scope.launch {
                try {
                    val engine = requested.voiceDataEnginePackage { installRequest === token && fresh() }
                    coroutineContext.ensureActive()
                    if (installRequest === token && fresh()) {
                        installError = !installSystemVoiceData(context, engine)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (installRequest === token && fresh()) installError = true
                } finally {
                    if (installRequest === token) installRequest = null
                }
            }
        },
        onRecheck = {
            val latest = runtime.settings.state.value.settings
            if (installRequest == null && shown != null && latest != null && shown.hasCurrentOwner && runtime.matches(latest, shown.config) &&
                foreground && !changing && !teardownFailed) {
                installError = false
                recognitionInstallError = false
                shown.recheckReadiness()
            }
        },
    )
}
