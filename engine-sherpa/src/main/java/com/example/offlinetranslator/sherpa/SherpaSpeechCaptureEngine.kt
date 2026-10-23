package com.example.offlinetranslator.sherpa

import android.content.Context
import com.example.offlinetranslator.core.SpeechCaptureEngine
import com.example.offlinetranslator.core.SpeechCaptureException
import com.example.offlinetranslator.core.SpeechCaptureFailure
import com.example.offlinetranslator.core.SpeechCaptureListener
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Emits bounded 16 kHz speech segments while the foreground owner keeps it active. */
class SherpaSpeechCaptureEngine(
    context: Context,
    private val models: SpeechModelStore,
    private val vadModelId: String,
    private val vadMinSilenceMs: Int,
) : SpeechCaptureEngine {
    private val microphone = AndroidMicrophoneSource(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = Mutex()
    private val closed = AtomicBoolean(false)
    private val microphoneOwnership = Any()
    private var interruptionGeneration = 0L
    @Volatile private var running: Running? = null

    init {
        require(vadMinSilenceMs in 300..1500)
        require(models.catalog.model(vadModelId).role == SpeechModelRole.VAD) { "Selected model is not a VAD" }
    }

    private class Running(
        val mic: AndroidMicrophoneSource.Session,
        val segmenter: SherpaSpeechSegmenter,
        val listener: SpeechCaptureListener,
    ) {
        lateinit var job: Job
        @Volatile var flush = false
        @Volatile var tail: SpeechSegment? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun start(listener: SpeechCaptureListener) = control.withLock {
        check(!closed.get()) { "Capture engine is closed" }
        check(running == null) { "Microphone already active" }
        val generation = synchronized(microphoneOwnership) { interruptionGeneration }
        var mic: AndroidMicrophoneSource.Session? = null
        // Load native VAD before acquiring the microphone; Stop can revoke a pending start.
        val segmenter = SherpaSpeechSegmenter.create(
            models, vadModelId, vadMinSilenceMs, speechEndTime = { mic?.speechEndTime(it) },
        )
        val context = coroutineContext
        try {
            synchronized(microphoneOwnership) {
                context.ensureActive()
                check(!closed.get()) { "Capture engine is closed" }
                if (generation != interruptionGeneration) {
                    throw CancellationException("Microphone start was interrupted")
                }
                val opened = microphone.open()
                mic = opened
                val session = Running(opened, segmenter, listener)
                running = session
                // Even cancellation before dispatch must enter readLoop's ownership finally block.
                session.job = scope.launch(start = CoroutineStart.ATOMIC) { readLoop(session) }
            }
        } catch (failure: Throwable) {
            try { mic?.release() } finally { segmenter.close() }
            throw failure
        }
    }

    override suspend fun stop(flush: Boolean): SpeechSegment? = control.withLock {
        val session = running ?: return@withLock null
        synchronized(microphoneOwnership) {
            interruptionGeneration += 1
            session.flush = flush
            running = null
            session.mic.stop()
        }
        session.job.join()
        session.tail
    }

    /** Called synchronously by lifecycle ON_STOP before the suspending cleanup begins. */
    override fun interruptCurrent() {
        synchronized(microphoneOwnership) {
            interruptionGeneration += 1
            running?.mic?.stop()
        }
    }

    private suspend fun readLoop(session: Running) {
        val readBuffer = ShortArray(2_048)
        val onSegment: (SpeechSegment) -> Unit = session.listener::onSegment
        var failure: SpeechCaptureFailure? = null
        try {
            while (running === session && !closed.get()) {
                val count = session.mic.read(readBuffer)
                if (count == 0) {
                    if (session.mic.isStopped) break
                    continue
                }
                session.segmenter.accept(readBuffer, count = count, onSegment = onSegment)
            }
            if (session.flush && !closed.get()) {
                val finalSegments = ArrayList<SpeechSegment>(2)
                session.segmenter.flush(finalSegments::add)
                finalSegments.dropLast(1).forEach(session.listener::onSegment)
                session.tail = finalSegments.lastOrNull()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SpeechCaptureException) {
            if (running === session) failure = error.reason
        } catch (_: Exception) {
            if (running === session) failure = SpeechCaptureFailure.READ_FAILED
        } finally {
            try { session.mic.release() } finally { session.segmenter.close() }
            if (running === session) running = null
            failure?.let(session.listener::onFailure)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            interruptCurrent()
            scope.cancel()
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) {
            control.withLock {
                scope.coroutineContext[Job]?.join()
            }
        }
    }

}
