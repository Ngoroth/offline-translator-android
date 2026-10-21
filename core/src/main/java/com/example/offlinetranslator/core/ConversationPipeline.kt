package com.example.offlinetranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class ConversationPhase { READY, PREPARING, LISTENING, UNDERSTANDING, TRANSLATING, SPEAKING, ERROR }
enum class ConversationFailure {
    MODELS_MISSING, PREPARATION_FAILED, PERMISSION_DENIED, MICROPHONE_UNAVAILABLE,
    CAPTURE_FAILED, UNSUPPORTED_LANGUAGE, RECOGNITION_FAILED, TRANSLATION_FAILED, PLAYBACK_FAILED,
}
enum class ConversationTranscriptStatus { UNDERSTANDING, TRANSLATING, SPEAKING, COMPLETE, ERROR, INTERRUPTED }

data class ConversationTranscript(
    val id: Long,
    val direction: LanguagePair? = null,
    val original: String = "",
    val translation: String? = null,
    val status: ConversationTranscriptStatus = ConversationTranscriptStatus.UNDERSTANDING,
    val failure: ConversationFailure? = null,
    val latencyMillis: Long? = null,
    val latencyMeasured: Boolean = false,
    val translationFailure: TranslationFailure? = null,
)

data class ConversationState(
    val phase: ConversationPhase = ConversationPhase.READY,
    val sessionActive: Boolean = false,
    val captureActive: Boolean = false,
    val transcripts: List<ConversationTranscript> = emptyList(),
    val failure: ConversationFailure? = null,
    val queuedSegments: Int = 0,
    val droppedSegments: Int = 0,
    val translationFailure: TranslationFailure? = null,
)

/** One foreground, automatically directed conversation with bounded work and half-duplex output. */
class ConversationPipeline(
    private val capture: SpeechCaptureEngine,
    private val recognition: ConversationRecognitionEngine,
    private val models: ConversationModelRepository,
    private val translation: TranslationEngine,
    private val output: ConversationSpeechOutput,
    private val ownerScope: CoroutineScope,
) {
    private val control = Mutex()
    private val releaseLock = Any()
    private var releaseResult: Result<Unit> = Result.success(Unit)
    private val identifiers = AtomicLong(1)
    @Volatile private var active: Session? = null
    private val mutableState = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = mutableState.asStateFlow()

    private class Session {
        val queue = Channel<SpeechSegment>(MAX_QUEUED_SEGMENTS)
        @Volatile var accepting = true
        @Volatile var capturing = false
        var queued = 0
        lateinit var job: Job
    }

    /** Native preparation happens before the first microphone sample, never between speaker turns. */
    suspend fun start(): Boolean = control.withLock {
        val previous = active
        if (previous?.accepting == true) return@withLock false
        previous?.job?.join()
        synchronized(releaseLock) { releaseResult.exceptionOrNull() }?.let { throw it }
        val session = Session()
        session.job = ownerScope.launch(start = CoroutineStart.LAZY) { runSession(session) }
        active = session
        mutableState.value = ConversationState(phase = ConversationPhase.PREPARING, sessionActive = true)
        session.job.start()
        true
    }

    /** Foreground loss and Stop revoke ownership before any asynchronous cleanup can suspend. */
    fun interruptCurrent() {
        val session = active
        if (session != null) {
            synchronized(session) {
                session.accepting = false
                session.capturing = false
                session.queue.cancel()
                session.queued = 0
            }
        }
        mutableState.update { current -> current.copy(
            phase = if (current.failure == null) ConversationPhase.READY else ConversationPhase.ERROR,
            sessionActive = false,
            captureActive = false,
            queuedSegments = 0,
            transcripts = current.transcripts.map { row ->
                if (row.status in ACTIVE_TRANSCRIPT_STATUSES) row.copy(status = ConversationTranscriptStatus.INTERRUPTED)
                else row
            },
        ) }
        session?.job?.cancel()
        interruptLeases()
    }

    suspend fun stop(): Result<Unit> = withContext(NonCancellable) {
        interruptCurrent()
        control.withLock {
            val session = active
            if (session?.accepting == true) interruptCurrent()
            session?.job?.join()
            synchronized(releaseLock) { releaseResult }
        }
    }

    suspend fun clear(): Result<Unit> = withContext(NonCancellable) {
        interruptCurrent()
        control.withLock {
            val session = active
            if (session?.accepting == true) interruptCurrent()
            session?.job?.join()
            mutableState.value = ConversationState()
            synchronized(releaseLock) { releaseResult }
        }
    }

    private fun isCurrent(session: Session) = active === session && session.accepting

    private fun update(session: Session, transform: (ConversationState) -> ConversationState) {
        mutableState.update { if (isCurrent(session)) transform(it) else it }
    }

    private suspend fun runSession(session: Session) {
        try {
            if (!models.isConversationReady() || translation.modelAvailability() != ModelAvailability.READY ||
                output.readiness() != SpeechOutputReadiness.READY) {
                failSession(session, ConversationFailure.MODELS_MISSING)
                return
            }
            recognition.prepare()
            check(translation.prepare() == ModelAvailability.READY) { "Translation preparation failed" }
            output.prepare()
            coroutineContext.ensureActive()
            if (!isCurrent(session)) return
            if (output.readiness() != SpeechOutputReadiness.READY) {
                failSession(session, ConversationFailure.MODELS_MISSING)
                return
            }
            startCapture(session)
            for (segment in session.queue) {
                synchronized(session) {
                    session.queued--
                    update(session) { it.copy(queuedSegments = session.queued) }
                }
                if (!isCurrent(session)) break
                process(session, segment)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeechCaptureException) {
            failSession(session, failure.reason.toConversationFailure())
        } catch (failure: TranslationFailure) {
            update(session) { it.copy(translationFailure = failure) }
            failSession(session, ConversationFailure.PREPARATION_FAILED)
        } catch (_: Exception) {
            failSession(session, ConversationFailure.PREPARATION_FAILED)
        } finally {
            synchronized(session) {
                session.accepting = false
                session.capturing = false
                session.queued = 0
                session.queue.cancel()
            }
            withContext(NonCancellable) {
                interruptLeases()
                try {
                    capture.stop(flush = false)
                } catch (failure: Throwable) {
                    recordReleaseFailure(failure, ConversationFailure.CAPTURE_FAILED)
                }
            }
            // Keep the completed owner until start() joins/replaces it; clearing it first lets
            // an old finalizer overwrite the state of a newly started session.
            mutableState.update {
                if (active === session) it.copy(sessionActive = false, captureActive = false, queuedSegments = 0) else it
            }
        }
    }

    private suspend fun startCapture(session: Session) {
        coroutineContext.ensureActive()
        if (!isCurrent(session)) return
        session.capturing = true
        capture.start(object : SpeechCaptureListener {
            override fun onSegment(segment: SpeechSegment) {
                synchronized(session) {
                    if (!isCurrent(session) || !session.capturing) return
                    if (session.queue.trySend(segment).isSuccess) {
                        session.queued++
                        update(session) { it.copy(queuedSegments = session.queued) }
                    } else {
                        update(session) { it.copy(
                            droppedSegments = if (it.droppedSegments == Int.MAX_VALUE) Int.MAX_VALUE else it.droppedSegments + 1,
                        ) }
                    }
                }
            }
            override fun onFailure(reason: SpeechCaptureFailure) = failSession(session, reason.toConversationFailure())
        })
        if (!isCurrent(session)) interruptCapture()
        else update(session) { it.copy(phase = ConversationPhase.LISTENING, captureActive = true) }
    }

    private suspend fun process(session: Session, segment: SpeechSegment) {
        val id = identifiers.getAndIncrement()
        update(session) { it.copy(
            phase = ConversationPhase.UNDERSTANDING,
            failure = null,
            translationFailure = null,
            transcripts = it.transcripts.takeLast(MAX_TRANSCRIPTS - 1) + ConversationTranscript(id),
        ) }
        var failureStage = ConversationFailure.RECOGNITION_FAILED
        var paused = false
        try {
            val utterance = recognition.recognize(segment)
            coroutineContext.ensureActive()
            if (!isCurrent(session)) return
            require(utterance.text.isNotBlank() && utterance.text.length <= MAX_TEXT_CHARACTERS)
            updateRow(session, id) { it.copy(
                direction = utterance.direction,
                original = utterance.text,
                status = ConversationTranscriptStatus.TRANSLATING,
            ) }
            update(session) { it.copy(phase = ConversationPhase.TRANSLATING) }
            failureStage = ConversationFailure.TRANSLATION_FAILED
            val translated = translation.translate(utterance.direction, utterance.text).trim()
            coroutineContext.ensureActive()
            if (!isCurrent(session)) return
            require(translated.isNotEmpty() && translated.length <= MAX_TEXT_CHARACTERS)
            updateRow(session, id) { it.copy(translation = translated, status = ConversationTranscriptStatus.SPEAKING) }
            failureStage = ConversationFailure.CAPTURE_FAILED
            // Reject reader callbacks before stopping it, so the phone cannot translate its own voice.
            session.capturing = false
            paused = true
            update(session) { it.copy(captureActive = false) }
            if (!interruptCapture()) failSession(session, ConversationFailure.CAPTURE_FAILED)
            // The independent reader must release its microphone even if Stop cancels this pause.
            withContext(NonCancellable) {
                try {
                    capture.stop(flush = false)
                } catch (failure: Throwable) {
                    if (!isCurrent(session) || failure !is Exception) {
                        recordReleaseFailure(failure, ConversationFailure.CAPTURE_FAILED)
                        failSession(session, ConversationFailure.CAPTURE_FAILED)
                    } else {
                        // A failed ordinary pause can release the reader and recover by listening again.
                        throw failure
                    }
                }
            }
            coroutineContext.ensureActive()
            if (!isCurrent(session)) return
            update(session) { it.copy(phase = ConversationPhase.SPEAKING, captureActive = false) }
            failureStage = ConversationFailure.PLAYBACK_FAILED
            output.speak(utterance.direction.target, translated) { start ->
                val end = segment.speechEndNanos
                val presentation = start.presentationTimeNanos
                val validInterval = end != null && presentation != null && end >= 0 && presentation >= end
                val latency = if (validInterval) {
                    val nanos = requireNotNull(presentation) - requireNotNull(end)
                    nanos / 1_000_000 + if (nanos % 1_000_000 == 0L) 0 else 1
                } else null
                updateRow(session, id) { it.copy(
                    latencyMillis = latency,
                    latencyMeasured = validInterval && segment.speechEndMeasured && start.measured,
                ) }
            }
            coroutineContext.ensureActive()
            if (!isCurrent(session)) return
            updateRow(session, id) { it.copy(status = ConversationTranscriptStatus.COMPLETE) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedSpeechLanguageException) {
            updateRow(session, id) { it.copy(status = ConversationTranscriptStatus.ERROR, failure = ConversationFailure.UNSUPPORTED_LANGUAGE) }
            update(session) { it.copy(failure = ConversationFailure.UNSUPPORTED_LANGUAGE) }
        } catch (error: Exception) {
            val typed = error as? TranslationFailure
            updateRow(session, id) { it.copy(status = ConversationTranscriptStatus.ERROR, failure = failureStage, translationFailure = typed) }
            update(session) { it.copy(failure = failureStage, translationFailure = typed) }
            if (typed is TranslationFailure.ModelMissing || typed is TranslationFailure.ArtifactCorrupt ||
                typed is TranslationFailure.RuntimeLoad || typed is TranslationFailure.Memory) {
                failSession(session, ConversationFailure.MODELS_MISSING)
            }
            if (failureStage == ConversationFailure.PLAYBACK_FAILED) {
                val playbackReady = try {
                    output.readiness() == SpeechOutputReadiness.READY
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!playbackReady) failSession(session, ConversationFailure.PLAYBACK_FAILED)
            }
        } finally {
            if (isCurrent(session)) {
                if (paused) startCapture(session)
                else update(session) { it.copy(phase = ConversationPhase.LISTENING) }
            }
        }
    }

    private fun updateRow(session: Session, id: Long, transform: (ConversationTranscript) -> ConversationTranscript) {
        update(session) { it.copy(transcripts = it.transcripts.map { row -> if (row.id == id) transform(row) else row }) }
    }

    private fun failSession(session: Session, failure: ConversationFailure) {
        synchronized(session) {
            if (!isCurrent(session)) return
            session.accepting = false
            session.capturing = false
            session.queued = 0
            session.queue.cancel()
            mutableState.update { current ->
                if (active !== session) current else current.copy(
                    phase = ConversationPhase.ERROR,
                    failure = failure,
                    sessionActive = false,
                    captureActive = false,
                    queuedSegments = 0,
                    transcripts = current.transcripts.map { row ->
                        if (row.status in ACTIVE_TRANSCRIPT_STATUSES) row.copy(
                            status = ConversationTranscriptStatus.ERROR,
                            failure = failure,
                        ) else row
                    },
                )
            }
        }
        session.job.cancel()
        interruptLeases()
    }

    private fun interruptCapture(): Boolean = try {
        capture.interruptCurrent()
        true
    } catch (failure: Throwable) {
        recordReleaseFailure(failure, ConversationFailure.CAPTURE_FAILED)
        false
    }

    private fun interruptLeases() {
        interruptCapture()
        try {
            output.interruptCurrent()
        } catch (failure: Throwable) {
            recordReleaseFailure(failure, ConversationFailure.PLAYBACK_FAILED)
        }
    }

    private fun recordReleaseFailure(caught: Throwable, reason: ConversationFailure) {
        synchronized(releaseLock) {
            val first = releaseResult.exceptionOrNull()
            if (first == null) {
                releaseResult = Result.failure(caught)
            } else if (first !== caught && !first.containsReleaseFailure(caught) && !caught.containsReleaseFailure(first)) {
                first.addSuppressed(caught)
            }
        }
        mutableState.update { it.copy(
            phase = ConversationPhase.ERROR,
            failure = it.failure ?: reason,
            sessionActive = false,
            captureActive = false,
        ) }
    }

    private fun Throwable.containsReleaseFailure(expected: Throwable): Boolean {
        if (this === expected) return true
        if (cause == null && suppressed.isEmpty()) return false
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (next === expected) return true
            if (!seen.add(next)) continue
            next.cause?.let { pending.add(it) }
            pending.addAll(next.suppressed)
        }
        return false
    }

    private fun SpeechCaptureFailure.toConversationFailure() = when (this) {
        SpeechCaptureFailure.PERMISSION_DENIED -> ConversationFailure.PERMISSION_DENIED
        SpeechCaptureFailure.MICROPHONE_UNAVAILABLE -> ConversationFailure.MICROPHONE_UNAVAILABLE
        SpeechCaptureFailure.READ_FAILED -> ConversationFailure.CAPTURE_FAILED
    }

    companion object {
        const val MAX_QUEUED_SEGMENTS = 1
        const val MAX_TRANSCRIPTS = 30
        const val MAX_TEXT_CHARACTERS = 4_096
        const val MAX_LATENCY_MILLIS = 2_000L
        private val ACTIVE_TRANSCRIPT_STATUSES = setOf(
            ConversationTranscriptStatus.UNDERSTANDING,
            ConversationTranscriptStatus.TRANSLATING,
            ConversationTranscriptStatus.SPEAKING,
        )
    }
}
