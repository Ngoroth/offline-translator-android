package com.example.offlinetranslator.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.ConversationRuntimeOwner
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import com.example.offlinetranslator.runtime.SpeechOutputUnavailableException
import com.example.offlinetranslator.speech.SystemConversationSpeechOutput
import com.example.offlinetranslator.speech.AndroidConversationRecognitionEngine
import com.example.offlinetranslator.speech.AndroidRecognitionDataInstallRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationViewModel(
    private val owner: ConversationRuntimeOwner,
    private val owners: RuntimeOwnerController,
    private val saveSpeechOutput: suspend (Boolean, () -> Boolean) -> Boolean,
    private val speechOutputProblem: () -> ConfigurationProblem? = { null },
) : ViewModel() {
    val config = owner.config
    val hasCurrentOwner: Boolean get() = owners.isCurrent(owner)
    val models = owner.models
    private val pipeline = owner.pipeline
    val state = pipeline.state
    val modelStatuses = models.models
    val recognitionReadiness = owner.recognition.readinessState
    private val mutablePreparation = MutableStateFlow(ConversationPreparation.CHECKING)
    val preparation = mutablePreparation.asStateFlow()
    val translationPreparation = owner.translation.preparation
    private val mutablePreparationFailure = MutableStateFlow<TranslationFailure?>(null)
    val preparationFailure = mutablePreparationFailure.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableOutputReadiness = MutableStateFlow<SpeechOutputReadiness?>(null)
    val outputReadiness = mutableOutputReadiness.asStateFlow()
    val speechOutputEnabled = pipeline.speechOutputEnabled
    private var savedSpeechOutputEnabled = speechOutputEnabled.value
    private val mutableSpeechOutputChanging = MutableStateFlow(false)
    val speechOutputChanging = mutableSpeechOutputChanging.asStateFlow()
    private val mutableSpeechOutputUnavailable = MutableStateFlow(false)
    val speechOutputUnavailable = mutableSpeechOutputUnavailable.asStateFlow()
    private val mutableSpeechOutputProblem = MutableStateFlow<ConfigurationProblem?>(null)
    val speechOutputConfigurationProblem = mutableSpeechOutputProblem.asStateFlow()
    private val mutableSpeechOutputSaveError = MutableStateFlow(false)
    val speechOutputSaveError = mutableSpeechOutputSaveError.asStateFlow()
    private var speechOutputJob: Job? = null

    fun toggleSpeechOutput() {
        if (disposed || !foreground || !hasCurrentOwner) return
        val enabling = !speechOutputEnabled.value
        if (enabling && (mutableSpeechOutputChanging.value || preparationJob?.isActive == true)) return
        val generation = pipeline.disableSpeechOutput()
        speechOutputJob?.cancel()
        readinessJob.cancel()
        mutableOutputReadiness.value = null
        mutableSpeechOutputUnavailable.value = false
        mutableSpeechOutputProblem.value = null
        mutableSpeechOutputSaveError.value = false
        mutableSpeechOutputChanging.value = true
        fun fresh() = !disposed && foreground && hasCurrentOwner && pipeline.isSpeechOutputRequestCurrent(generation)
        speechOutputJob = viewModelScope.launch {
            var savingPreference = true
            try {
                // A failed enable must also remain persistently off, even after an earlier failed mute save.
                if (!saveSpeechOutput(false, ::fresh)) {
                    if (fresh()) mutableSpeechOutputSaveError.value = true
                    return@launch
                }
                savingPreference = false
                savedSpeechOutputEnabled = false
                if (!fresh()) return@launch
                if (enabling) {
                    val problem = speechOutputProblem()
                    if (problem != null) {
                        mutableSpeechOutputProblem.value = problem
                        mutableSpeechOutputUnavailable.value = true
                        return@launch
                    }
                    val ready = pipeline.prepareSpeechOutput(generation) {
                        val output = owner.output
                        if (output is SystemConversationSpeechOutput) output.recheck() else output.readiness()
                    } ?: return@launch
                    if (!fresh()) return@launch
                    mutableOutputReadiness.value = ready
                    if (ready != SpeechOutputReadiness.READY) {
                        mutableSpeechOutputUnavailable.value = true
                        return@launch
                    }
                    savingPreference = true
                    if (!saveSpeechOutput(true, ::fresh)) {
                        if (fresh()) mutableSpeechOutputSaveError.value = true
                        return@launch
                    }
                    savingPreference = false
                    savedSpeechOutputEnabled = true
                    if (fresh()) pipeline.enableSpeechOutput(generation)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (unavailable: SpeechOutputUnavailableException) {
                if (fresh()) {
                    mutableOutputReadiness.value = unavailable.readiness
                    mutableSpeechOutputUnavailable.value = true
                }
            } catch (invalid: InvalidConfigurationException) {
                if (fresh()) {
                    mutableSpeechOutputProblem.value = invalid.problem
                    mutableSpeechOutputUnavailable.value = true
                }
            } catch (_: Exception) {
                if (fresh()) {
                    if (savingPreference) mutableSpeechOutputSaveError.value = true
                    else if (enabling) {
                        mutableOutputReadiness.value = SpeechOutputReadiness.INITIALIZATION_FAILED
                        mutableSpeechOutputUnavailable.value = true
                    }
                }
            } finally {
                if (pipeline.isSpeechOutputRequestCurrent(generation)) {
                    mutableSpeechOutputChanging.value = false
                    if (fresh() && !state.value.sessionActive) refreshReadiness()
                }
            }
        }
    }
    /** A user action gets one fresh attestation under the shared owner gate, never a recheck. */
    suspend fun voiceDataEnginePackage(isFresh: () -> Boolean): String? {
        fun allowed() = !disposed && foreground && hasCurrentOwner && isFresh() &&
            !state.value.sessionActive && preparationJob?.isActive != true && !readinessJob.isActive
        if (!allowed()) return null
        return owners.use(owner) {
            if (!allowed()) return@use null
            val output = owner.output as? SystemConversationSpeechOutput ?: return@use null
            val engine = output.voiceDataEnginePackage()
            currentCoroutineContext().ensureActive()
            if (allowed()) engine else null
        }
    }

    /** Attestation ends at the owner gate; the caller launches external consent only after this returns. */
    internal suspend fun recognitionDataInstallRequest(isFresh: () -> Boolean): AndroidRecognitionDataInstallRequest? {
        fun allowed() = !disposed && foreground && hasCurrentOwner && isFresh() &&
            !state.value.sessionActive && preparationJob?.isActive != true && !readinessJob.isActive
        if (!allowed()) return null
        return owners.use(owner) {
            if (!allowed()) return@use null
            val recognition = owner.recognition as? AndroidConversationRecognitionEngine ?: return@use null
            val request = recognition.recognitionDataInstallRequest()
            currentCoroutineContext().ensureActive()
            if (allowed()) request else null
        }
    }
    private val mutablePermissionDenied = MutableStateFlow(false)
    val permissionDenied = mutablePermissionDenied.asStateFlow()
    private var foreground = true
    private var disposed = false
    private var preparationJob: Job? = null
    private var sessionAction: Job? = null
    private var cleanupJob: Deferred<Result<Unit>>? = null
    private var foregroundCleanup: Job? = null
    private var readinessJob: Job = checkReadiness()

    private fun checkReadiness(recheckSystem: Boolean = false) = viewModelScope.launch {
        try {
            foregroundCleanup?.join()
            currentCoroutineContext().ensureActive()
            if (disposed || !foreground || !hasCurrentOwner) return@launch
            val ready = owners.use(owner) { resourcesReady(recheckSystem) } ?: false
            currentCoroutineContext().ensureActive()
            mutableReady.value = ready && !disposed && foreground && hasCurrentOwner
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableReady.value = false
        } finally {
            if (!disposed && mutablePreparation.value == ConversationPreparation.CHECKING) {
                mutablePreparation.value = ConversationPreparation.IDLE
            }
        }
    }

    fun recheckReadiness() {
        if (!speechOutputEnabled.value && speechOutputUnavailable.value) toggleSpeechOutput()
        else refreshReadiness(recheckSystem = true)
    }

    private fun refreshReadiness(recheckSystem: Boolean = false) {
        if (disposed || !foreground || !hasCurrentOwner || state.value.sessionActive ||
            preparationJob?.isActive == true || readinessJob.isActive) return
        mutableReady.value = false
        mutablePreparation.value = ConversationPreparation.CHECKING
        readinessJob = checkReadiness(recheckSystem)
    }
    private val readinessObservation = viewModelScope.launch {
        state.collect { current ->
            if (current.failure == ConversationFailure.MODELS_MISSING || current.failure == ConversationFailure.PLAYBACK_FAILED) {
                mutableReady.value = false
                if (pipeline.releaseFailed) cleanup() else refreshReadiness()
            }
        }
    }
    private val recognitionObservation = viewModelScope.launch {
        recognitionReadiness.collect { readiness ->
            if (readiness != SpeechRecognitionReadiness.READY) {
                mutableReady.value = false
                if (state.value.sessionActive) stop()
            }
        }
    }

    /** Explicit recovery may prepare output resources without enabling them or blocking silent Start. */
    fun prepareLanguages() {
        if (disposed || !foreground || !hasCurrentOwner || preparationJob?.isActive == true ||
            speechOutputChanging.value || state.value.sessionActive) return
        val recoveringSpeechOutput = config.speechOutputBackend == SpeechOutputBackend.PIPER &&
            !speechOutputEnabled.value && speechOutputUnavailable.value &&
            speechOutputConfigurationProblem.value == null
        mutableReady.value = false
        mutablePreparation.value = ConversationPreparation.INSTALLING
        mutablePreparationFailure.value = null
        preparationJob = viewModelScope.launch {
            readinessJob.cancelAndJoin()
            try {
                val ready = owners.use(owner) {
                    models.install(speechOutputEnabled.value || recoveringSpeechOutput)
                    currentCoroutineContext().ensureActive()
                    if (recoveringSpeechOutput) {
                        mutableOutputReadiness.value = pipeline.checkSpeechOutputForEnable() ?: mutableOutputReadiness.value
                        mutableSpeechOutputUnavailable.value = mutableOutputReadiness.value != SpeechOutputReadiness.READY
                    }
                    mutablePreparation.value = ConversationPreparation.TRANSLATION
                    val translationReady = owner.translation.prepare() == ModelAvailability.READY
                    when (recognitionReadiness.value) {
                        SpeechRecognitionReadiness.SCHEDULED,
                        SpeechRecognitionReadiness.DOWNLOADING,
                        SpeechRecognitionReadiness.DOWNLOAD_FAILED -> {
                            // Preserve the explicit download outcome until the user or foreground re-entry rechecks.
                            if (speechOutputEnabled.value) mutableOutputReadiness.value = pipeline.checkSpeechOutput()
                            false
                        }
                        else -> translationReady && resourcesReady()
                    }
                } ?: return@launch
                currentCoroutineContext().ensureActive()
                if (disposed || !foreground || !hasCurrentOwner) return@launch
                mutableReady.value = ready
                mutablePreparation.value = if (ready && (!recoveringSpeechOutput || !speechOutputUnavailable.value))
                    ConversationPreparation.IDLE else ConversationPreparation.ERROR
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (disposed || !foreground || !hasCurrentOwner) return@launch
                if (pipeline.releaseFailed) {
                    cleanup()
                    return@launch
                }
                mutableReady.value = if (recoveringSpeechOutput) {
                    try {
                        owners.use(owner) { resourcesReady() } == true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (pipeline.releaseFailed) cleanup()
                        false
                    }
                } else false
                currentCoroutineContext().ensureActive()
                if (disposed || !foreground || !hasCurrentOwner) return@launch
                mutablePreparationFailure.value = failure as? TranslationFailure
                mutablePreparation.value = ConversationPreparation.ERROR
            }
        }
    }

    fun permissionDenied() {
        if (disposed) return
        mutablePermissionDenied.value = true
        stop()
    }

    fun start() {
        if (disposed || !foreground || !hasCurrentOwner || !ready.value ||
            recognitionReadiness.value != SpeechRecognitionReadiness.READY ||
            preparationJob?.isActive == true || sessionAction?.isActive == true) return
        mutablePermissionDenied.value = false
        sessionAction = viewModelScope.launch {
            try {
                owners.use(owner) { pipeline.start() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                cleanup()
            }
        }
    }

    fun stop() {
        if (disposed) return
        sessionAction?.cancel()
        if (speechOutputEnabled.value != savedSpeechOutputEnabled) mutableSpeechOutputSaveError.value = true
        speechOutputJob?.cancel()
        mutableSpeechOutputChanging.value = false
        pipeline.interruptCurrent()
        sessionAction = viewModelScope.launch {
            try {
                if (pipeline.stop().isFailure) cleanup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                cleanup()
            }
        }
    }

    fun enterForeground() {
        if (disposed) return
        foreground = true
        refreshReadiness()
    }

    fun leaveForeground() {
        if (disposed) return
        foreground = false
        mutableReady.value = false
        if (speechOutputEnabled.value != savedSpeechOutputEnabled) mutableSpeechOutputSaveError.value = true
        speechOutputJob?.cancel()
        mutableSpeechOutputChanging.value = false
        val checking = readinessJob
        val preparing = preparationJob
        val previousAction = sessionAction
        checking.cancel()
        preparing?.cancel()
        previousAction?.cancel()
        pipeline.interruptCurrent()
        val barrier = viewModelScope.launch {
            withContext(NonCancellable) {
                try {
                    previousAction?.join()
                    checking.join()
                    preparing?.join()
                    if (pipeline.clear().isFailure) cleanup()
                    if (config.speechRecognitionService != null) {
                        (owner.recognition as? AndroidConversationRecognitionEngine)?.leaveForegroundAndJoin()
                    }
                } catch (_: Throwable) {
                    cleanup()
                }
            }
        }
        foregroundCleanup = barrier
        sessionAction = barrier
    }

    private suspend fun resourcesReady(recheckSystem: Boolean = false): Boolean {
        pipeline.throwIfReleaseFailed()
        val required = speechOutputEnabled.value
        val output = if (!required) null else pipeline.checkSpeechOutput {
            if (recheckSystem && owner.output is SystemConversationSpeechOutput) owner.output.recheck()
            else owner.output.readiness()
        }
        val stillRequired = speechOutputEnabled.value
        if (required == stillRequired) {
            mutableOutputReadiness.value = if (required || speechOutputUnavailable.value) output ?: mutableOutputReadiness.value else null
        }
        return owner.models.isConversationReady(stillRequired) &&
            owner.translation.modelAvailability() == ModelAvailability.READY && (!stillRequired || output == SpeechOutputReadiness.READY)
    }

    /** One cleanup result survives route cancellation and propagates actual native teardown errors. */
    fun dispose() { cleanup() }

    suspend fun disposeAndJoin() { cleanup().await().getOrThrow() }

    @Synchronized
    private fun cleanup(): Deferred<Result<Unit>> {
        cleanupJob?.let { return it }
        disposed = true
        foreground = false
        mutableReady.value = false
        val action = sessionAction
        val preparing = preparationJob
        val outputAction = speechOutputJob
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = cleanupScope.async(start = CoroutineStart.LAZY) {
            readinessJob.join()
            readinessObservation.join()
            recognitionObservation.join()
            preparing?.join()
            action?.join()
            outputAction?.join()
            runCatching { owners.release(owner) }
        }
        cleanupJob = result
        result.invokeOnCompletion { cleanupScope.cancel() }
        pipeline.interruptCurrent()
        action?.cancel()
        outputAction?.cancel()
        readinessJob.cancel()
        readinessObservation.cancel()
        recognitionObservation.cancel()
        preparing?.cancel()
        result.start()
        return result
    }

    override fun onCleared() { dispose() }
}
