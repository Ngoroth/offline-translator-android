package com.example.offlinetranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class SpeechPipelinePhase {
    READY,
    LISTENING,
    STOPPING,
    RECOGNIZING,
    TRANSLATING,
    ERROR,
}

enum class SpeechFailureStage {
    MODELS,
    CAPTURE,
    RECOGNITION,
    TRANSLATION,
}

enum class SpeechFailureReason {
    SPEECH_MODELS_MISSING,
    TRANSLATION_MODEL_MISSING,
    PERMISSION_DENIED,
    MICROPHONE_UNAVAILABLE,
    AUDIO_READ_FAILED,
    EMPTY_TRANSCRIPT,
    RESULT_TOO_LARGE,
    RECOGNITION_FAILED,
    TRANSLATION_FAILED,
}

enum class SpeechTranscriptStatus {
    RECOGNIZING,
    TRANSLATING,
    COMPLETE,
    ERROR,
}

data class SpeechPipelineError(
    val stage: SpeechFailureStage,
    val reason: SpeechFailureReason,
)

data class SpeechTranscript(
    val id: Long,
    val direction: LanguagePair,
    val original: String = "",
    val translation: String? = null,
    val status: SpeechTranscriptStatus = SpeechTranscriptStatus.RECOGNIZING,
    val error: SpeechPipelineError? = null,
)

data class SpeechPipelineState(
    val phase: SpeechPipelinePhase = SpeechPipelinePhase.READY,
    val direction: LanguagePair? = null,
    val sessionActive: Boolean = false,
    val captureActive: Boolean = false,
    val transcripts: List<SpeechTranscript> = emptyList(),
    val error: SpeechPipelineError? = null,
    val queueOverflow: Boolean = false,
    val droppedSegments: Int = 0,
    val queuedSegments: Int = 0,
)

/**
 * Serializes speech segments, retaining only a bounded queue and the one failed segment that
 * can be retried. Audio and transcript state stay in memory and are cleared by [clear].
 */
class SpeechToTextPipeline(
    private val capture: SpeechCaptureEngine,
    private val recognition: SpeechRecognitionEngine,
    private val models: SpeechModelRepository,
    private val translation: TranslationEngine,
    private val ownerScope: CoroutineScope,
) {
    private val controlMutex = Mutex()
    private val identifiers = AtomicLong(1L)
    @Volatile private var activeSession: Session? = null

    private val mutableState = MutableStateFlow(SpeechPipelineState())
    val state: StateFlow<SpeechPipelineState> = mutableState.asStateFlow()

    private class Work(
        val id: Long,
        var segment: SpeechSegment?,
        var recognizedText: String? = null,
    )

    private class Session(
        val id: Long,
        val direction: LanguagePair,
    ) {
        val queue = ArrayDeque<Work>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var accepting = true
        var closed = false
        var pendingRetry: Work? = null
        var retryRequested = false
        var captureFailure: SpeechPipelineError? = null
        var worker: Job? = null
    }

    private sealed interface NextAction {
        data class Process(val work: Work) : NextAction
        data object Wait : NextAction
        data object Finish : NextAction
        data object Abandon : NextAction
    }

    /** Checks offline readiness, then starts one explicitly directed microphone session. */
    suspend fun start(direction: LanguagePair): Boolean = controlMutex.withLock {
        if (activeSession != null) return@withLock false

        val speechReady = try {
            models.isReady(direction)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!speechReady) {
            mutableState.value = mutableState.value.copy(
                phase = SpeechPipelinePhase.ERROR,
                direction = direction,
                error = SpeechPipelineError(
                    SpeechFailureStage.MODELS,
                    SpeechFailureReason.SPEECH_MODELS_MISSING,
                ),
            )
            return@withLock false
        }
        val translationReady = try {
            translation.modelAvailability() == ModelAvailability.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!translationReady) {
            mutableState.value = mutableState.value.copy(
                phase = SpeechPipelinePhase.ERROR,
                direction = direction,
                error = SpeechPipelineError(
                    SpeechFailureStage.MODELS,
                    SpeechFailureReason.TRANSLATION_MODEL_MISSING,
                ),
            )
            return@withLock false
        }

        val session = Session(identifiers.getAndIncrement(), direction)
        activeSession = session
        mutableState.value = mutableState.value.copy(
            phase = SpeechPipelinePhase.LISTENING,
            direction = direction,
            sessionActive = true,
            captureActive = true,
            error = null,
            queueOverflow = false,
            queuedSegments = 0,
        )
        session.worker = ownerScope.launch { runWorker(session) }

        try {
            capture.start(object : SpeechCaptureListener {
                override fun onSegment(segment: SpeechSegment) = acceptSegment(session, segment)

                override fun onFailure(reason: SpeechCaptureFailure) = failCapture(session, reason)
            })
            true
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { capture.stop(flush = false) } }
            abandonSession(session)
            throw cancelled
        } catch (failure: SpeechCaptureException) {
            failCapture(session, failure.reason)
            false
        } catch (_: Exception) {
            failCapture(session, SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
            false
        }
    }

    /** Stops microphone capture, flushes its final partial segment once, then drains in order. */
    suspend fun stop() = controlMutex.withLock {
        val session = activeSession ?: return@withLock
        synchronized(session) {
            if (!session.accepting) return@withLock
            session.accepting = false
            mutableState.update { it.copy(
                phase = SpeechPipelinePhase.STOPPING,
                captureActive = false,
                queuedSegments = session.queue.size,
            ) }
        }

        // Stop is committed once accepting is revoked. Finish the capture release and
        // queue closure together, even if the caller stops waiting during capture.stop().
        withContext(NonCancellable) {
            val finalSegment = try {
                capture.stop(flush = true)
            } catch (cancelled: CancellationException) {
                failCapture(session, SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
                throw cancelled
            } catch (_: Exception) {
                failCapture(session, SpeechCaptureFailure.MICROPHONE_UNAVAILABLE)
                null
            }

            synchronized(session) {
                if (activeSession !== session) return@withContext
                if (finalSegment != null && !enqueueLocked(session, finalSegment)) {
                    mutableState.update { current -> current.copy(
                        queueOverflow = true,
                        droppedSegments = if (current.droppedSegments == Int.MAX_VALUE) Int.MAX_VALUE else current.droppedSegments + 1,
                    ) }
                }
                session.closed = true
                mutableState.update { it.copy(
                    phase = if (session.pendingRetry == null) SpeechPipelinePhase.STOPPING else SpeechPipelinePhase.ERROR,
                    queuedSegments = session.queue.size,
                ) }
                session.wake.trySend(Unit)
            }
        }
        coroutineContext.ensureActive()
    }

    /** Retries only the failed ASR or MT stage; queued later segments remain behind it. */
    suspend fun retry(): Boolean = controlMutex.withLock {
        val session = activeSession ?: return@withLock false
        val pending = synchronized(session) {
            val work = session.pendingRetry ?: return@synchronized null
            session.retryRequested = true
            work
        } ?: return@withLock false

        updateTranscript(session, pending.id) { row ->
            row.copy(status = if (pending.recognizedText == null) SpeechTranscriptStatus.RECOGNIZING else SpeechTranscriptStatus.TRANSLATING, error = null)
        }
        mutableState.update { it.copy(
            phase = if (pending.recognizedText == null) SpeechPipelinePhase.RECOGNIZING else SpeechPipelinePhase.TRANSLATING,
            error = null,
        ) }
        session.wake.trySend(Unit)
        true
    }

    /** Cancels the owner session and drops all audio/transcript data held by this pipeline. */
    suspend fun clear() = controlMutex.withLock {
        val session = activeSession
        activeSession = null
        if (session != null) {
            synchronized(session) {
                session.accepting = false
                session.closed = true
                session.queue.clear()
                session.pendingRetry = null
                session.retryRequested = false
                session.wake.close()
            }
            session.worker?.cancel()
            withContext(NonCancellable) { runCatching { capture.stop(flush = false) } }
        }
        mutableState.value = SpeechPipelineState()
    }

    private fun acceptSegment(session: Session, segment: SpeechSegment) {
        if (activeSession !== session) return
        synchronized(session) {
            if (!session.accepting || activeSession !== session) return
            if (!enqueueLocked(session, segment)) {
                mutableState.update { current -> current.copy(
                    queueOverflow = true,
                    droppedSegments = if (current.droppedSegments == Int.MAX_VALUE) Int.MAX_VALUE else current.droppedSegments + 1,
                    queuedSegments = session.queue.size,
                ) }
            } else {
                mutableState.update { it.copy(queuedSegments = session.queue.size) }
            }
            session.wake.trySend(Unit)
        }
    }

    /** Caller holds the session monitor. */
    private fun enqueueLocked(session: Session, segment: SpeechSegment): Boolean {
        if (session.queue.size >= MAX_QUEUED_SEGMENTS) return false
        session.queue.addLast(Work(identifiers.getAndIncrement(), segment))
        return true
    }

    private fun failCapture(session: Session, reason: SpeechCaptureFailure) {
        if (activeSession !== session) return
        val error = SpeechPipelineError(
            SpeechFailureStage.CAPTURE,
            when (reason) {
                SpeechCaptureFailure.PERMISSION_DENIED -> SpeechFailureReason.PERMISSION_DENIED
                SpeechCaptureFailure.MICROPHONE_UNAVAILABLE -> SpeechFailureReason.MICROPHONE_UNAVAILABLE
                SpeechCaptureFailure.READ_FAILED -> SpeechFailureReason.AUDIO_READ_FAILED
            },
        )
        synchronized(session) {
            if (activeSession !== session) return
            session.accepting = false
            session.closed = true
            session.captureFailure = error
            mutableState.update { it.copy(
                phase = SpeechPipelinePhase.ERROR,
                captureActive = false,
                error = error,
                queuedSegments = session.queue.size,
            ) }
            session.wake.trySend(Unit)
        }
    }

    private suspend fun runWorker(session: Session) {
        while (activeSession === session) {
            val action = synchronized(session) {
                when {
                    activeSession !== session -> NextAction.Abandon
                    session.pendingRetry != null && session.retryRequested -> {
                        session.retryRequested = false
                        NextAction.Process(session.pendingRetry!!)
                    }
                    session.pendingRetry != null -> NextAction.Wait
                    session.queue.isNotEmpty() -> {
                        mutableState.update { it.copy(queuedSegments = session.queue.size - 1) }
                        NextAction.Process(session.queue.removeFirst())
                    }
                    session.closed -> NextAction.Finish
                    else -> NextAction.Wait
                }
            }

            when (action) {
                is NextAction.Process -> process(session, action.work)
                NextAction.Wait -> if (session.wake.receiveCatching().isClosed) return
                NextAction.Finish -> {
                    finish(session)
                    return
                }
                NextAction.Abandon -> return
            }
        }
    }

    private suspend fun process(session: Session, work: Work) {
        if (activeSession !== session) return
        val existing = mutableState.value.transcripts.any { it.id == work.id }
        if (!existing) {
            setTranscript(session, SpeechTranscript(work.id, session.direction))
        } else {
            updateTranscript(session, work.id) {
                it.copy(
                    status = if (work.recognizedText == null) SpeechTranscriptStatus.RECOGNIZING else SpeechTranscriptStatus.TRANSLATING,
                    error = null,
                )
            }
        }
        try {
            var original = work.recognizedText
            if (original == null) {
                updateActiveState(session) { it.copy(phase = SpeechPipelinePhase.RECOGNIZING, error = null) }
                val segment = work.segment ?: return
                original = recognition.recognize(session.direction, segment).trim()
                if (original.isEmpty()) {
                    failWork(session, work, SpeechFailureStage.RECOGNITION, SpeechFailureReason.EMPTY_TRANSCRIPT)
                    return
                }
                if (original.length > MAX_ORIGINAL_CHARACTERS) {
                    failWork(session, work, SpeechFailureStage.RECOGNITION, SpeechFailureReason.RESULT_TOO_LARGE)
                    return
                }
                work.recognizedText = original
                work.segment = null
                if (activeSession !== session) return
            }
            updateTranscript(session, work.id) {
                it.copy(original = original, status = SpeechTranscriptStatus.TRANSLATING, error = null)
            }
            updateActiveState(session) { it.copy(phase = SpeechPipelinePhase.TRANSLATING, error = null) }
            val translated = translation.translate(session.direction, original).trim()
            if (translated.isEmpty() || translated.length > MAX_TRANSLATION_CHARACTERS) {
                throw IllegalStateException("Translation result is outside the accepted size")
            }
            if (activeSession !== session) return
            updateTranscript(session, work.id) {
                it.copy(translation = translated, status = SpeechTranscriptStatus.COMPLETE, error = null)
            }
            synchronized(session) {
                if (session.pendingRetry === work) session.pendingRetry = null
            }
            if (!isAccepting(session)) {
                updateActiveState(session) { it.copy(phase = SpeechPipelinePhase.STOPPING, error = null) }
            } else {
                updateActiveState(session) { it.copy(phase = SpeechPipelinePhase.LISTENING, error = null) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val stage = if (work.recognizedText == null) SpeechFailureStage.RECOGNITION else SpeechFailureStage.TRANSLATION
            val reason = if (stage == SpeechFailureStage.RECOGNITION) {
                SpeechFailureReason.RECOGNITION_FAILED
            } else {
                SpeechFailureReason.TRANSLATION_FAILED
            }
            failWork(session, work, stage, reason)
        }
    }

    private fun failWork(
        session: Session,
        work: Work,
        stage: SpeechFailureStage,
        reason: SpeechFailureReason,
    ) {
        if (activeSession !== session) return
        val error = SpeechPipelineError(stage, reason)
        synchronized(session) { session.pendingRetry = work }
        updateTranscript(session, work.id) {
            it.copy(
                original = work.recognizedText.orEmpty(),
                status = SpeechTranscriptStatus.ERROR,
                error = error,
            )
        }
        updateActiveState(session) { it.copy(phase = SpeechPipelinePhase.ERROR, error = error) }
    }

    private fun setTranscript(session: Session, transcript: SpeechTranscript) {
        if (activeSession !== session) return
        mutableState.update { current ->
            if (activeSession !== session) current
            else current.copy(
                transcripts = (current.transcripts.filterNot { it.id == transcript.id } + transcript)
                    .takeLast(MAX_VISIBLE_TRANSCRIPTS),
            )
        }
    }

    private fun updateTranscript(session: Session, id: Long, transform: (SpeechTranscript) -> SpeechTranscript) {
        if (activeSession !== session) return
        mutableState.update { current ->
            if (activeSession !== session) current
            else current.copy(transcripts = current.transcripts.map { if (it.id == id) transform(it) else it })
        }
    }

    private inline fun updateActiveState(session: Session, transform: (SpeechPipelineState) -> SpeechPipelineState) {
        mutableState.update { current -> if (activeSession === session) transform(current) else current }
    }

    private fun isAccepting(session: Session): Boolean = synchronized(session) { session.accepting }

    private fun finish(session: Session) {
        synchronized(session) {
            if (activeSession !== session) return
            activeSession = null
            val captureError = session.captureFailure
            mutableState.update { current -> current.copy(
                phase = if (captureError == null) SpeechPipelinePhase.READY else SpeechPipelinePhase.ERROR,
                direction = null,
                sessionActive = false,
                captureActive = false,
                error = captureError,
                queuedSegments = 0,
            ) }
            session.queue.clear()
            session.pendingRetry = null
            session.wake.close()
        }
    }

    private fun abandonSession(session: Session) {
        if (activeSession !== session) return
        activeSession = null
        synchronized(session) {
            session.accepting = false
            session.closed = true
            session.queue.clear()
            session.pendingRetry = null
            session.wake.close()
        }
        mutableState.value = SpeechPipelineState()
    }

    companion object {
        const val MAX_QUEUED_SEGMENTS = 3
        const val MAX_VISIBLE_TRANSCRIPTS = 30
        const val MAX_ORIGINAL_CHARACTERS = 2_048
        const val MAX_TRANSLATION_CHARACTERS = 4_096
    }
}
