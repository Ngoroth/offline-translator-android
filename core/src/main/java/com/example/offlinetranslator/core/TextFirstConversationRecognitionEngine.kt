package com.example.offlinetranslator.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** One owner and one serialization boundary for prepared, unconditioned transcription and text ID. */
class TextFirstConversationRecognitionEngine(
    pairs: Set<LanguagePair>,
    private val ready: suspend () -> SpeechRecognitionReadiness,
    private val installSelected: suspend () -> Unit,
    private val createTranscriber: suspend () -> SpeechTranscriber,
    private val createIdentifier: suspend () -> TextLanguageIdentifier,
) : ConversationRecognitionEngine {
    private val pairs = pairs.toSet().also { selected ->
        require(selected.size in 1..2) { "Select one pair and optionally its reverse" }
        val first = selected.first()
        require(selected.all { it == first || it == first.reverse() }) { "Select a single conversation pair" }
    }
    private val pair = this.pairs.first()
    private val mutex = Mutex()
    private val disposed = AtomicBoolean()
    private val released = CompletableDeferred<Unit>()
    private val mutableReadiness = MutableStateFlow(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
    override val readinessState = mutableReadiness.asStateFlow()
    private var transcriber: SpeechTranscriber? = null
    private var identifier: TextLanguageIdentifier? = null

    override suspend fun readiness(): SpeechRecognitionReadiness = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureOpen()
            checkReadiness()
        }
    }

    override suspend fun install() = mutex.withLock {
        withContext(Dispatchers.Default) {
            ensureOpen()
            mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOADING
            try {
                installSelected()
                ensureOpen()
                checkReadiness()
                Unit
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOAD_FAILED
                throw failure
            }
        }
    }

    override suspend fun prepare() = serialized { caller ->
        if (transcriber != null && identifier != null) return@serialized
        check(checkReadiness() == SpeechRecognitionReadiness.READY) { "Selected recognition resources are not ready" }
        checkpoint(caller)
        try {
            // Factory owns a resource until return; record it before observing cancellation/close.
            transcriber = createTranscriber()
            checkpoint(caller)
            identifier = createIdentifier()
            checkpoint(caller)
        } catch (failure: Throwable) {
            releaseStages()?.let { if (it !== failure) failure.addSuppressed(it) }
            throw failure
        }
    }

    override suspend fun recognize(segment: SpeechSegment): ConversationRecognitionResult = serialized { caller ->
        val preparedTranscriber = checkNotNull(transcriber) { "Conversation recognizer is not prepared" }
        val preparedIdentifier = checkNotNull(identifier) { "Conversation recognizer is not prepared" }
        val text = preparedTranscriber.transcribe(segment)
        checkpoint(caller)
        require(text.length <= ConversationPipeline.MAX_TEXT_CHARACTERS) { "Speech recognition text exceeds the input limit" }
        val source = preparedIdentifier.identify(text, pair)
        checkpoint(caller)
        if (source == null) UndeterminedLanguage(text)
        else {
            val direction = pairs.singleOrNull { it.source == source }
                ?: throw UnsupportedSpeechLanguageException()
            require(text.isNotBlank()) { "Identified speech recognition text must not be blank" }
            RecognizedUtterance(direction, text)
        }
    }

    override fun close() {
        if (!disposed.compareAndSet(false, true)) return
        // Not a child of a cancelled caller: native/Task work retains the mutex until it settles.
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                val failure = releaseStages()
                if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
            }
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { released.await() }
    }

    private suspend fun <T> serialized(block: suspend (CoroutineContext) -> T): T {
        val caller = coroutineContext
        return mutex.withLock {
            checkpoint(caller)
            // Synchronous C calls and SDK Tasks cannot be interrupted. Keep ownership until real
            // completion, but check the original caller between stages and before publication.
            withContext(Dispatchers.Default + NonCancellable) {
                checkpoint(caller)
                block(caller)
            }
        }
    }

    private fun checkpoint(caller: CoroutineContext) {
        caller.ensureActive()
        ensureOpen()
    }

    private fun ensureOpen() = check(!disposed.get()) { "Conversation recognizer is closed" }

    private suspend fun checkReadiness(): SpeechRecognitionReadiness {
        val state = try {
            ready()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SpeechRecognitionReadiness.CHECK_FAILED
        }
        mutableReadiness.value = state
        return state
    }

    /** Called only while holding the serialization boundary; always attempts reverse-order release. */
    private fun releaseStages(): Throwable? {
        val previousIdentifier = identifier
        val previousTranscriber = transcriber
        identifier = null
        transcriber = null
        var failure: Throwable? = null
        try { previousIdentifier?.close() } catch (caught: Throwable) { failure = caught }
        try { previousTranscriber?.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
        }
        return failure
    }
}
