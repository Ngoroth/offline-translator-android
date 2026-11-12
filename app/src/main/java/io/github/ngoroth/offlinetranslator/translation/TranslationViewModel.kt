package io.github.ngoroth.offlinetranslator.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ngoroth.offlinetranslator.core.*
import io.github.ngoroth.offlinetranslator.runtime.RuntimeOwnerController
import io.github.ngoroth.offlinetranslator.runtime.TextRuntimeOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import io.github.ngoroth.offlinetranslator.ui.ScreenAwake

class TranslationViewModel(
    private val owner: TextRuntimeOwner,
    private val owners: RuntimeOwnerController,
    provider: TranslationProviderDescriptor? = null,
    initialInput: String = "",
    initialInputTooLong: Boolean = false,
    private val screenAwake: ScreenAwake? = null,
) : ViewModel() {
    val config = owner.config
    val hasCurrentOwner: Boolean get() = owners.isCurrent(owner)
    private val mutableState = MutableStateFlow(
        TranslationUiState(
            direction = owner.config.pair,
            provider = provider,
            input = if (initialInput.length <= TranslationEngine.MAX_INPUT_CHARACTERS) initialInput else "",
            inputTooLong = initialInputTooLong || initialInput.length > TranslationEngine.MAX_INPUT_CHARACTERS,
        )
    )
    val state: StateFlow<TranslationUiState> = mutableState.asStateFlow()

    private var generation = 0L
    private var lastFailedAction = FailedAction.PREPARE
    private var operation: Job? = null
    private var foreground = true
    private var disposed = false
    private var cleanupJob: Deferred<Result<Unit>>? = null
    private var preparationObservation: Job? = null
    private var localPreparation = false
    private val awakeOwner = screenAwake?.owner { !disposed && foreground && hasCurrentOwner }
    private val ownershipObservation = viewModelScope.launch {
        combine(owners.ownershipVersion, owners.teardownFailed) { _, _ -> Unit }.collect {
            screenAwake?.refresh()
        }
    }

    init {
        preparationObservation = viewModelScope.launch {
            owner.translation.preparation.collect { preparation ->
                if (disposed || !foreground || !hasCurrentOwner) return@collect
                // Only terminal loss revokes a usable engine; preparation progress is not an eviction signal.
                if (preparation.phase in setOf(TranslationPreparationPhase.MISSING, TranslationPreparationPhase.FAILED) &&
                    (mutableState.value.modelStatus == ModelUiStatus.READY ||
                        (preparation.phase == TranslationPreparationPhase.FAILED && !mutableState.value.operationInProgress))) {
                    generation++
                    operation?.cancel()
                    lastFailedAction = FailedAction.PREPARE
                    mutableState.update {
                        it.copy(modelStatus = ModelUiStatus.MISSING, isPreparing = false, isTranslating = false,
                            preparation = preparation, failure = preparation.lastFailure,
                            error = preparation.lastFailure?.toUiError())
                    }
                    return@collect
                }
                if (localPreparation && preparation.phase !in setOf(
                        TranslationPreparationPhase.CHECKING, TranslationPreparationPhase.VERIFYING,
                        TranslationPreparationPhase.LOADING,
                    )) return@collect
                if (!disposed && foreground && hasCurrentOwner) mutableState.update {
                    it.copy(
                        preparation = preparation,
                        modelStatus = if (it.isPreparing || (it.modelStatus == ModelUiStatus.CHECKING &&
                            preparation.phase == TranslationPreparationPhase.VERIFYING)) preparation.phase.toUiStatus() else it.modelStatus,
                    )
                }
            }
        }
        refreshAvailability()
    }

    fun updateInput(value: String) {
        if (disposed || !foreground || !hasCurrentOwner || mutableState.value.operationInProgress) return
        mutableState.update {
            if (value.length > TranslationEngine.MAX_INPUT_CHARACTERS) {
                it.copy(inputTooLong = true, error = null, failure = null)
            } else if (value != it.input) {
                it.copy(input = value, inputTooLong = false, result = "", error = null, failure = null)
            } else {
                it
            }
        }
    }


    fun prepare() {
        if (disposed || !foreground || !hasCurrentOwner || !mutableState.value.canPrepare) return
        val request = ++generation
        localPreparation = false
        mutableState.update { it.copy(modelStatus = ModelUiStatus.DOWNLOADING, isPreparing = true, error = null, failure = null) }
        operation = viewModelScope.launch {
            try {
                val availability = useEngine { it.prepare() } ?: return@launch
                updateIfCurrent(request) {
                    it.copy(modelStatus = availability.toUiStatus(), isPreparing = false, error = null, failure = null)
                }
            } catch (cancelled: CancellationException) {
                updateAfterCancellation(request)
                throw cancelled
            } catch (failure: Throwable) {
                lastFailedAction = FailedAction.PREPARE
                updateIfCurrent(request) {
                    it.copy(modelStatus = ModelUiStatus.MISSING, isPreparing = false, error = failure.toUiError(), failure = failure as? TranslationFailure)
                }
            }
        }
    }

    fun translate() {
        val snapshot = mutableState.value
        if (disposed || !foreground || !hasCurrentOwner || !snapshot.canTranslate) return
        val request = ++generation
        mutableState.update { it.copy(isTranslating = true, result = "", error = null, failure = null) }
        operation = viewModelScope.launch {
            try {
                val translated = useEngine { it.translate(snapshot.direction, snapshot.input) } ?: return@launch
                updateIfCurrent(request) {
                    it.copy(isTranslating = false, result = translated, error = null)
                }
            } catch (cancelled: CancellationException) {
                updateAfterCancellation(request)
                throw cancelled
            } catch (failure: Throwable) {
                val repair = failure is TranslationFailure.ModelMissing || failure is TranslationFailure.ArtifactCorrupt ||
                    failure is TranslationFailure.RuntimeLoad || failure is TranslationFailure.Memory
                lastFailedAction = if (repair) FailedAction.PREPARE else FailedAction.TRANSLATE
                updateIfCurrent(request) {
                    it.copy(isTranslating = false, error = TranslationUiError.TRANSLATION_FAILED,
                        failure = failure as? TranslationFailure,
                        modelStatus = if (repair) ModelUiStatus.MISSING else it.modelStatus)
                }
            }
        }
    }

    fun retry() {
        if (disposed || !foreground || !hasCurrentOwner || mutableState.value.operationInProgress) return
        when (lastFailedAction) {
            FailedAction.PREPARE -> prepare()
            FailedAction.TRANSLATE -> translate()
        }
    }

    fun enterForeground() {
        if (disposed) return
        val wasBackground = !foreground
        foreground = true
        if (wasBackground) refreshAvailability()
    }

    fun leaveForeground() {
        if (disposed) return
        foreground = false
        awakeOwner?.clear()
        generation++
        operation?.cancel()
        mutableState.update { it.copy(isTranslating = false, isPreparing = false, modelStatus = ModelUiStatus.CHECKING) }
    }

    fun dispose() { cleanup() }

    suspend fun disposeAndJoin() { cleanup().await().getOrThrow() }

    @Synchronized
    private fun cleanup(): Deferred<Result<Unit>> {
        cleanupJob?.let { return it }
        disposed = true
        foreground = false
        awakeOwner?.close()
        generation++
        operation?.cancel()
        preparationObservation?.cancel()
        ownershipObservation.cancel()
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = cleanupScope.async(start = CoroutineStart.LAZY) {
            operation?.join()
            runCatching { owners.release(owner) }
        }
        cleanupJob = result
        result.invokeOnCompletion { cleanupScope.cancel() }
        result.start()
        return result
    }

    override fun onCleared() {
        dispose()
    }

    private fun refreshAvailability() {
        if (disposed || !foreground || !hasCurrentOwner) return
        val request = ++generation
        lastFailedAction = FailedAction.PREPARE
        localPreparation = true
        mutableState.update {
            it.copy(modelStatus = ModelUiStatus.CHECKING, isPreparing = true, error = null, failure = null)
        }
        operation = viewModelScope.launch {
            try {
                val availability = useEngine { it.prepareLocal() } ?: return@launch
                val failure = owner.translation.preparation.value.lastFailure
                updateIfCurrent(request) {
                    it.copy(modelStatus = availability.toUiStatus(), isPreparing = false, failure = failure,
                        error = failure?.toUiError())
                }
            } catch (cancelled: CancellationException) {
                updateAfterCancellation(request)
                throw cancelled
            } catch (failure: Throwable) {
                lastFailedAction = FailedAction.PREPARE
                updateIfCurrent(request) {
                    it.copy(modelStatus = ModelUiStatus.MISSING, isPreparing = false, error = failure.toUiError(),
                        failure = failure as? TranslationFailure)
                }
            }
        }
    }

    private suspend fun <T> useEngine(action: suspend (TranslationEngine) -> T): T? =
        owners.use(owner) {
            val demand = awakeOwner?.acquire()
            try { owner.operation { action(owner.translation) } } finally { demand?.close() }
        }

    private fun updateAfterCancellation(request: Long) {
        val preparation = owner.translation.preparation.value
        if (preparation.phase != TranslationPreparationPhase.MISSING) return
        updateIfCurrent(request) {
            lastFailedAction = FailedAction.PREPARE
            it.copy(modelStatus = ModelUiStatus.MISSING, isPreparing = false, isTranslating = false,
                preparation = preparation, failure = preparation.lastFailure,
                error = preparation.lastFailure?.toUiError())
        }
    }

    private inline fun updateIfCurrent(request: Long, transform: (TranslationUiState) -> TranslationUiState) {
        if (!disposed && foreground && hasCurrentOwner && request == generation) mutableState.update(transform)
    }

    private fun ModelAvailability.toUiStatus(): ModelUiStatus = when (this) {
        ModelAvailability.MISSING -> ModelUiStatus.MISSING
        ModelAvailability.READY -> ModelUiStatus.READY
    }

    private fun TranslationPreparationPhase.toUiStatus(): ModelUiStatus = when (this) {
        TranslationPreparationPhase.CHECKING -> ModelUiStatus.CHECKING
        TranslationPreparationPhase.MISSING, TranslationPreparationPhase.FAILED -> ModelUiStatus.MISSING
        TranslationPreparationPhase.DOWNLOADING -> ModelUiStatus.DOWNLOADING
        TranslationPreparationPhase.VERIFYING -> ModelUiStatus.VERIFYING
        TranslationPreparationPhase.LOADING -> ModelUiStatus.LOADING
        TranslationPreparationPhase.READY -> ModelUiStatus.READY
    }

    private fun Throwable.toUiError(): TranslationUiError = when (this) {
        is PreparationFailure.WifiRequired -> TranslationUiError.WIFI_REQUIRED
        is PreparationFailure.PreparationTimedOut -> TranslationUiError.PREPARATION_TIMED_OUT
        else -> TranslationUiError.PREPARATION_FAILED
    }

    private enum class FailedAction { PREPARE, TRANSLATE }
}
