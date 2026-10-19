package com.example.offlinetranslator.speech

import android.content.Context
import android.speech.SpeechRecognizer
import com.example.offlinetranslator.core.ConversationRecognitionEngine
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.RecognizedUtterance
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** One owner, one bounded request at a time; never opens a second microphone or downloads during inference. */
class AndroidConversationRecognitionEngine internal constructor(
    private val config: ConversationSessionConfig,
    private val platform: AndroidSpeechRecognizerClient,
    private val main: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) : ConversationRecognitionEngine {
    constructor(context: Context, config: ConversationSessionConfig) : this(
        config, AndroidPlatformSpeechRecognizerClient(context), Dispatchers.Main.immediate, Dispatchers.IO,
    )
    init { require(config.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE) }
    private val languages = config.languages.map(AndroidSpeechLocales::recognition)
    private val mutableReadiness = MutableStateFlow(SpeechRecognitionReadiness.CHECK_FAILED)
    override val readinessState = mutableReadiness.asStateFlow()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    @Volatile private var operation: Job? = null
    @Volatile private var releaseFailure: Throwable? = null
    /** Bounded provider error metadata, never audio or recognized content. */
    @Volatile var errorCode: Int? = null
        private set
    internal var supportInventory: AndroidRecognitionSupport? = null
        private set

    private class Request {
        val accepting = AtomicBoolean(true)
        var session: AndroidRecognitionSession? = null
        var audio: AndroidRecognitionAudio? = null
        var writer: Job? = null
    }
    private fun ensureOpen() {
        check(!closed.get()) { "Conversation recognizer is closed" }
        releaseFailure?.let { throw it }
    }

    override suspend fun readiness(): SpeechRecognitionReadiness = mutex.withLock {
        ensureOpen()
        checkedReadiness()
    }
    private suspend fun checkedReadiness(): SpeechRecognitionReadiness {
        errorCode = null
        val state = try {
            withTimeoutOrNull(CHECK_TIMEOUT_MILLIS) {
                if (!withContext(main) { platform.available() }) return@withTimeoutOrNull SpeechRecognitionReadiness.ENGINE_UNAVAILABLE
                request { request ->
                    val result = CompletableDeferred<Pair<AndroidRecognitionSupport?, Int?>>()
                    withContext(main) {
                        requireNotNull(request.session).support(languages, requireNotNull(request.audio)) { support, error ->
                            if (request.accepting.get()) result.complete(support to error)
                        }
                    }
                    val (support, error) = result.await()
                    errorCode = error
                    supportInventory = support
                    if (support == null) errorReadiness(error) else classify(support)
                }
            } ?: SpeechRecognitionReadiness.CHECK_FAILED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            releaseFailure?.let { throw it }
            SpeechRecognitionReadiness.CHECK_FAILED
        }
        mutableReadiness.value = state
        return state
    }
    private fun classify(support: AndroidRecognitionSupport): SpeechRecognitionReadiness {
        fun covers(tags: List<String>, language: LanguageId) = tags.any { AndroidSpeechLocales.source(it) == language }
        if (config.languages.all { covers(support.installed, it) }) return SpeechRecognitionReadiness.READY
        if (config.languages.any { language ->
                !covers(support.installed, language) && !covers(support.pending, language) && !covers(support.supported, language)
            }) return SpeechRecognitionReadiness.UNSUPPORTED
        if (config.languages.any { covers(support.pending, it) && !covers(support.installed, it) }) return SpeechRecognitionReadiness.SCHEDULED
        return SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
    }
    private fun errorReadiness(error: Int?): SpeechRecognitionReadiness = when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> SpeechRecognitionReadiness.UNSUPPORTED
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> SpeechRecognitionReadiness.ENGINE_UNAVAILABLE
        else -> SpeechRecognitionReadiness.CHECK_FAILED
    }

    override suspend fun install() = mutex.withLock {
        ensureOpen()
        val before = checkedReadiness()
        if (before == SpeechRecognitionReadiness.READY || before == SpeechRecognitionReadiness.UNSUPPORTED ||
            before == SpeechRecognitionReadiness.ENGINE_UNAVAILABLE || before == SpeechRecognitionReadiness.CHECK_FAILED) return@withLock
        // Each primary language receives its own public request; both allowlists remain exactly the pair.
        for (initial in languages.indices) {
            if (supportInventory?.installed?.any { AndroidSpeechLocales.source(it) == config.languages[initial] } == true) continue
            val selected = if (initial == 0) languages else languages.reversed()
            val outcome = try {
                // Interactive consent/download ends on the public terminal callback or owner cancellation,
                // not the recognition-only30s PCM request budget.
                request { request ->
                    val completion = CompletableDeferred<SpeechRecognitionReadiness>()
                    withContext(main) {
                        requireNotNull(request.session).download(selected, requireNotNull(request.audio), object : AndroidModelDownloadListener {
                            override fun progress() {
                                if (request.accepting.get() && !completion.isCompleted) mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOADING
                            }
                            override fun scheduled() {
                                if (request.accepting.get() && !completion.isCompleted) completion.complete(SpeechRecognitionReadiness.SCHEDULED)
                            }
                            override fun success() {
                                if (request.accepting.get() && !completion.isCompleted) completion.complete(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
                            }
                            override fun error(code: Int) {
                                if (request.accepting.get() && !completion.isCompleted) {
                                    errorCode = code
                                    completion.complete(SpeechRecognitionReadiness.DOWNLOAD_FAILED)
                                }
                            }
                        })
                    }
                    completion.await()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                releaseFailure?.let { throw it }
                SpeechRecognitionReadiness.DOWNLOAD_FAILED
            }
            mutableReadiness.value = outcome
            if (outcome == SpeechRecognitionReadiness.DOWNLOAD_FAILED) return@withLock
            if (outcome == SpeechRecognitionReadiness.SCHEDULED) return@withLock
        }
        checkedReadiness()
        Unit
    }
    override suspend fun prepare() {
        check(readiness() == SpeechRecognitionReadiness.READY) { "Selected on-device recognition is not ready" }
    }

    override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance = mutex.withLock {
        ensureOpen()
        val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            check(withContext(main) { platform.permissionGranted() }) { "Microphone permission is required" }
            check(mutableReadiness.value == SpeechRecognitionReadiness.READY) { "Selected on-device recognition is not prepared" }
            coroutineScope {
                val resolver = Resolution(config)
                val terminal = CompletableDeferred<Unit>()
                request(resolver.listener(terminal)) { request ->
                    withContext(main) { requireNotNull(request.session).start(languages, requireNotNull(request.audio)) }
                    // Exactly one copy across the existing immutable PCM boundary, encoded in reusable chunks.
                    val pcm = segment.copyPcm()
                    val writer = async(io) { requireNotNull(request.audio).write(pcm) }
                    request.writer = writer
                    terminal.await()
                    writer.await()
                    coroutineContext.ensureActive()
                    resolver.result()
                }
            }
        }
        checkNotNull(result) { "Speech recognition deadline elapsed" }
    }

    private suspend fun <T> request(
        listener: AndroidRecognitionListener = SilentListener,
        block: suspend (Request) -> T,
    ): T {
        val request = Request()
        operation = coroutineContext[Job]
        try {
            // Retain a late factory result before observing cancellation, so its lease is always destroyed.
            withContext(NonCancellable + main) {
                ensureOpen()
                request.audio = platform.openAudio()
                request.session = platform.create(listener)
            }
            coroutineContext.ensureActive()
            ensureOpen()
            return block(request)
        } finally {
            request.accepting.set(false)
            if (listener is Resolution.Listener) listener.deactivate()
            withContext(NonCancellable) {
                var failure: Throwable? = null
                fun record(caught: Throwable) {
                    if (failure == null) failure = caught else if (failure !== caught) failure?.addSuppressed(caught)
                }
                try { request.audio?.close() } catch (caught: Throwable) { record(caught) }
                withContext(main) {
                    try { request.session?.cancel() } catch (caught: Throwable) { record(caught) }
                    try { request.session?.destroy() } catch (caught: Throwable) { record(caught) }
                }
                request.writer?.cancelAndJoin()
                operation = null
                failure?.let { releaseFailure = it; throw it }
            }
        }
    }
    override fun close() {
        closed.set(true)
        operation?.cancel(CancellationException("Conversation recognizer closed"))
    }
    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { mutex.withLock { releaseFailure?.let { throw it } } }
    }

    /** Bounded masks and saturated counters; neither history, initial language nor text determine source. */
    private class Resolution(private val config: ConversationSessionConfig) {
        private var strongMask = 0
        private var weakBlocked = false
        private var firstWeakCount = 0
        private var secondWeakCount = 0
        private var weakRunIndex = -1
        private var weakRunCount = 0
        private var failed = false
        private val text = StringBuilder()
        private var fullText: String? = null
        private var segments = false
        private var active = true
        inner class Listener(private val terminal: CompletableDeferred<Unit>) : AndroidRecognitionListener {
            fun deactivate() { synchronized(this@Resolution) { active = false } }
            override fun language(tag: String?, confidence: Int?, switchResult: Int?) = synchronized(this@Resolution) {
                if (!active || terminal.isCompleted) return@synchronized
                if (switchResult == 2 || switchResult == 3 || (switchResult != null && switchResult !in 0..3)) failed = true
                if (confidence != null && confidence !in 0..3) failed = true
                val source = tag?.let(AndroidSpeechLocales::source)
                val index = config.languages.indexOf(source)
                if (tag != null && index < 0) weakBlocked = true
                // Switching describes the decoder, not confidence in the utterance's source.
                if (confidence == 2 || confidence == 3) {
                    strongMask = strongMask or if (index >= 0) 1 shl index else 4
                }
                if (confidence == 1 && index >= 0) {
                    if (index == 0) {
                        if (firstWeakCount < Int.MAX_VALUE) firstWeakCount++
                    } else if (secondWeakCount < Int.MAX_VALUE) secondWeakCount++
                    if (weakRunIndex == index) {
                        if (weakRunCount < Int.MAX_VALUE) weakRunCount++
                    } else {
                        weakRunIndex = index
                        weakRunCount = 1
                    }
                } else {
                    weakRunIndex = -1
                    weakRunCount = 0
                }
            }
            override fun segment(primary: String?) = synchronized(this@Resolution) {
                if (!active || terminal.isCompleted) return@synchronized
                segments = true
                append(primary)
            }
            override fun results(primary: String?) = synchronized(this@Resolution) {
                if (!active || terminal.isCompleted) return@synchronized
                // Whole-session final is not another segment and does not end a segmented request.
                if (primary == null || primary.trim().isEmpty() || primary.length > MAX_TEXT) failed = true
                else fullText = primary.trim()
            }
            override fun terminal() = synchronized(this@Resolution) { if (active) terminal.complete(Unit); Unit }
            override fun error(code: Int) = synchronized(this@Resolution) {
                if (active && !terminal.isCompleted) { failed = true; terminal.complete(Unit) }
                Unit
            }
        }
        fun listener(terminal: CompletableDeferred<Unit>) = Listener(terminal)
        private fun append(primary: String?) {
            val next = primary?.trim()
            if (next.isNullOrEmpty()) return
            if (next.length > MAX_TEXT || text.length + next.length + (if (text.isEmpty()) 0 else 1) > MAX_TEXT) {
                failed = true
                return
            }
            if (text.isNotEmpty()) text.append(' ')
            text.append(next)
        }
        @Synchronized fun result(): RecognizedUtterance {
            check(!failed) { "Speech recognition failed" }
            val mask = if (strongMask != 0) strongMask else {
                when {
                    weakBlocked || weakRunCount < 2 -> 0
                    weakRunIndex == 0 && firstWeakCount >= 2 && firstWeakCount > secondWeakCount -> 1
                    weakRunIndex == 1 && secondWeakCount >= 2 && secondWeakCount > firstWeakCount -> 2
                    else -> 0
                }
            }
            check(mask == 1 || mask == 2) { "Speech recognition source is ambiguous" }
            val value = if (segments) text.toString() else fullText.orEmpty()
            check(value.isNotBlank() && value.length <= MAX_TEXT) { "Speech recognition returned no usable text" }
            val source = config.languages[if (mask == 1) 0 else 1]
            return RecognizedUtterance(config.pairFor(source), value)
        }
    }
    private object SilentListener : AndroidRecognitionListener {
        override fun language(tag: String?, confidence: Int?, switchResult: Int?) = Unit
        override fun segment(primary: String?) = Unit
        override fun results(primary: String?) = Unit
        override fun terminal() = Unit
        override fun error(code: Int) = Unit
    }
    companion object {
        internal const val CHECK_TIMEOUT_MILLIS = 10_000L
        internal const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_TEXT = 4096
    }
}
