package com.example.offlinetranslator.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.runtime.ConversationRuntimeOwner
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import com.example.offlinetranslator.speech.SystemConversationSpeechOutput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationViewModel(
    private val owner: ConversationRuntimeOwner,
    private val owners: RuntimeOwnerController,
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
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableOutputReadiness = MutableStateFlow<SpeechOutputReadiness?>(null)
    val outputReadiness = mutableOutputReadiness.asStateFlow()
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

    fun recheckReadiness() { refreshReadiness(recheckSystem = true) }

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
                refreshReadiness()
            }
        }
    }
    private val recognitionObservation = viewModelScope.launch {
        recognitionReadiness.collect { readiness ->
            if (readiness != SpeechRecognitionReadiness.READY) mutableReady.value = false
        }
    }

    /** One explicit action prepares all selected languages; a partial installation never enables Start. */
    fun prepareLanguages() {
        if (disposed || !foreground || !hasCurrentOwner || preparationJob?.isActive == true || state.value.sessionActive) return
        mutableReady.value = false
        mutablePreparation.value = ConversationPreparation.INSTALLING
        preparationJob = viewModelScope.launch {
            readinessJob.cancelAndJoin()
            try {
                val ready = owners.use(owner) {
                    models.install()
                    mutablePreparation.value = ConversationPreparation.TRANSLATION
                    val translationReady = owner.translation.prepare() == ModelAvailability.READY
                    when (recognitionReadiness.value) {
                        SpeechRecognitionReadiness.SCHEDULED,
                        SpeechRecognitionReadiness.DOWNLOADING,
                        SpeechRecognitionReadiness.DOWNLOAD_FAILED -> {
                            // Preserve the explicit download outcome until the user or foreground re-entry rechecks.
                            mutableOutputReadiness.value = owner.output.readiness()
                            false
                        }
                        else -> translationReady && resourcesReady()
                    }
                } ?: return@launch
                mutableReady.value = ready
                mutablePreparation.value = if (mutableReady.value) ConversationPreparation.IDLE else ConversationPreparation.ERROR
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableReady.value = false
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
                } catch (_: Throwable) {
                    cleanup()
                }
            }
        }
        foregroundCleanup = barrier
        sessionAction = barrier
    }

    private suspend fun resourcesReady(recheckSystem: Boolean = false): Boolean {
        val output = if (recheckSystem && owner.output is SystemConversationSpeechOutput) owner.output.recheck() else owner.output.readiness()
        mutableOutputReadiness.value = output
        return owner.models.isConversationReady() &&
            owner.translation.modelAvailability() == ModelAvailability.READY && output == SpeechOutputReadiness.READY
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
        val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val result = cleanupScope.async(start = CoroutineStart.LAZY) {
            readinessJob.join()
            readinessObservation.join()
            recognitionObservation.join()
            preparing?.join()
            action?.join()
            runCatching { owners.release(owner) }
        }
        cleanupJob = result
        result.invokeOnCompletion { cleanupScope.cancel() }
        pipeline.interruptCurrent()
        action?.cancel()
        readinessJob.cancel()
        readinessObservation.cancel()
        recognitionObservation.cancel()
        preparing?.cancel()
        result.start()
        return result
    }

    override fun onCleared() { dispose() }
}
