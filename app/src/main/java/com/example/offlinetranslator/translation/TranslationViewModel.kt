package com.example.offlinetranslator.translation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import com.example.offlinetranslator.runtime.TextRuntimeOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TranslationViewModel(
    private val owner: TextRuntimeOwner,
    private val owners: RuntimeOwnerController,
    provider: TranslationProviderDescriptor? = null,
    initialInput: String = "",
    initialInputTooLong: Boolean = false,
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

    init {
        preparationObservation = viewModelScope.launch {
            owner.translation.preparation.collect { preparation ->
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
        if (disposed || mutableState.value.operationInProgress) return
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
        mutableState.update { it.copy(modelStatus = ModelUiStatus.DOWNLOADING, isPreparing = true, error = null, failure = null) }
        operation = viewModelScope.launch {
            try {
                val availability = useEngine { it.prepare() } ?: return@launch
                updateIfCurrent(request) {
                    it.copy(modelStatus = availability.toUiStatus(), isPreparing = false, error = null, failure = null)
                }
            } catch (cancelled: CancellationException) {
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
        if (disposed || mutableState.value.operationInProgress) return
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
        generation++
        operation?.cancel()
        mutableState.update { it.copy(isTranslating = false, isPreparing = false, modelStatus = ModelUiStatus.CHECKING, result = "") }
    }

    fun dispose() { cleanup() }

    suspend fun disposeAndJoin() { cleanup().await().getOrThrow() }

    @Synchronized
    private fun cleanup(): Deferred<Result<Unit>> {
        cleanupJob?.let { return it }
        disposed = true
        foreground = false
        generation++
        operation?.cancel()
        preparationObservation?.cancel()
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
        val request = ++generation
        operation = viewModelScope.launch {
            try {
                val availability = useEngine { it.modelAvailability() } ?: return@launch
                val failure = owner.translation.preparation.value.lastFailure
                updateIfCurrent(request) {
                    it.copy(modelStatus = availability.toUiStatus(), failure = failure,
                        error = failure?.toUiError())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailedAction = FailedAction.PREPARE
                updateIfCurrent(request) {
                    it.copy(modelStatus = ModelUiStatus.MISSING, error = TranslationUiError.PREPARATION_FAILED,
                        failure = failure as? TranslationFailure)
                }
            }
        }
    }

    private suspend fun <T> useEngine(action: suspend (TranslationEngine) -> T): T? =
        owners.use(owner) { owner.operation { action(owner.translation) } }

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
