package com.example.offlinetranslator.translation

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.AppRuntime
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

@Composable
fun TranslationRoute(homeControls: @Composable () -> Unit, onLanguageTransitionChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) { AppRuntime.get(context.applicationContext) }
    val settingsState by runtime.settings.state.collectAsStateWithLifecycle()
    val settings = settingsState.settings ?: AppSettings()
    val loaded = !settingsState.loading && !settingsState.loadError && settingsState.settings != null
    val problem = remember(settings) { runtime.textProblems(settings).firstOrNull() }
    val config = remember(settings) { settings.textConfig() }
    val descriptor = remember(settings.textEngineId) { runtime.registry.descriptors.firstOrNull { it.id == settings.textEngineId } }
    val teardownFailed by runtime.owners.teardownFailed.collectAsStateWithLifecycle()
    val ownershipVersion by runtime.owners.ownershipVersion.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val transitionCallback by rememberUpdatedState(onLanguageTransitionChanged)
    var viewModel by remember { mutableStateOf<TranslationViewModel?>(null) }
    var input by remember { mutableStateOf("") }
    var inputTooLong by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var guardedCreation by remember { mutableStateOf(false) }
    var languageError by remember { mutableStateOf<TranslationLanguageChangeError?>(null) }
    var failedOpenConfig by remember { mutableStateOf<TextRuntimeConfig?>(null) }
    var transitionJob by remember { mutableStateOf<Job?>(null) }
    var mounted by remember { mutableStateOf(true) }
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    LaunchedEffect(config, problem, loaded, changing, teardownFailed, foreground, ownershipVersion) {
        if (changing || !foreground) return@LaunchedEffect
        if (!loaded || problem != null || teardownFailed) {
            val old = viewModel
            viewModel = null
            try {
                old?.disposeAndJoin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                languageError = TranslationLanguageChangeError.TEARDOWN
            }
            return@LaunchedEffect
        }
        if (languageError == TranslationLanguageChangeError.OPEN && failedOpenConfig == config) return@LaunchedEffect
        if (viewModel?.let { it.config == config && it.hasCurrentOwner } == true) return@LaunchedEffect
        val old = viewModel
        viewModel = null
        try {
            old?.disposeAndJoin()
            coroutineContext.ensureActive()
            val owner = if (guardedCreation) runtime.createTextOwnerIfUnowned(config) else runtime.createTextOwner(config)
            if (owner != null) {
                guardedCreation = true
                viewModel = TranslationViewModel(
                    owner, runtime.owners, descriptor,
                    initialInput = input, initialInputTooLong = inputTooLong,
                )
                if (languageError == TranslationLanguageChangeError.OPEN) languageError = null
            } else {
                failedOpenConfig = config
                languageError = TranslationLanguageChangeError.OPEN
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            languageError = if (runtime.owners.teardownFailed.value) TranslationLanguageChangeError.TEARDOWN else TranslationLanguageChangeError.OPEN
            if (languageError == TranslationLanguageChangeError.OPEN) failedOpenConfig = config
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            mounted = false
            transitionJob?.cancel()
            viewModel?.dispose()
            viewModel = null
            transitionCallback(false)
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
                    transitionJob?.cancel()
                    currentVm?.leaveForeground()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); currentVm?.leaveForeground() }
    }
    val selectPair: (LanguagePair) -> Unit = select@{ selected ->
        val latest = runtime.settings.state.value.settings ?: return@select
        val vm = viewModel
        if (!loaded || !foreground || changing || teardownFailed || latest.languagePair != settings.languagePair ||
            selected == latest.languagePair || selected.source == selected.target ||
            LanguageCatalog.find(selected.source) == null || LanguageCatalog.find(selected.target) == null ||
            (vm != null && (!vm.hasCurrentOwner || vm.config != latest.textConfig() ||
                vm.state.value.isPreparing || vm.state.value.modelStatus in setOf(ModelUiStatus.DOWNLOADING,
                    ModelUiStatus.VERIFYING, ModelUiStatus.LOADING, ModelUiStatus.CHECKING)))) return@select
        input = vm?.state?.value?.input ?: input
        inputTooLong = vm?.state?.value?.inputTooLong ?: inputTooLong
        guardedCreation = true
        changing = true
        languageError = null
        transitionCallback(true)
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { vm?.disposeAndJoin() }
                if (viewModel === vm) viewModel = null
                coroutineContext.ensureActive()
                if (!mounted || !foreground) return@launch
                if (!runtime.updateLanguagePair(latest.languagePair, selected)) languageError = TranslationLanguageChangeError.SAVE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mounted) languageError = if (runtime.owners.teardownFailed.value) TranslationLanguageChangeError.TEARDOWN else TranslationLanguageChangeError.SAVE
            } finally {
                if (viewModel === vm) viewModel = null
                if (mounted) { changing = false; transitionCallback(false) }
            }
        }
    }
    val shown = currentVm?.takeIf { it.config == config && it.hasCurrentOwner }
    val state = shown?.state?.collectAsStateWithLifecycle()?.value ?: TranslationUiState(
        direction = settings.languagePair, input = input, inputTooLong = inputTooLong,
        modelStatus = ModelUiStatus.MISSING,
        provider = descriptor, configurationProblem = problem,
    )
    TranslationScreen(
        state = state,
        homeControls = homeControls,
        ownerAvailable = shown != null,
        onInputChanged = { value ->
            if (shown != null) {
                shown.updateInput(value)
                input = shown.state.value.input
                inputTooLong = shown.state.value.inputTooLong
            } else if (value.length > TranslationEngine.MAX_INPUT_CHARACTERS) {
                inputTooLong = true
            } else if (value != input) {
                input = value
                inputTooLong = false
            }
        },
        onPairSelected = selectPair,
        onPrepare = { if (!changing && !teardownFailed) shown?.prepare() },
        onTranslate = { if (!changing && !teardownFailed) shown?.translate() },
        onRetry = { if (!changing && !teardownFailed) shown?.retry() },
        provider = descriptor, configurationProblem = problem,
        languageSelectionEnabled = loaded && foreground && !teardownFailed,
        languageChanging = changing,
        languageChangeError = if (teardownFailed) TranslationLanguageChangeError.TEARDOWN else languageError,
    )
}
