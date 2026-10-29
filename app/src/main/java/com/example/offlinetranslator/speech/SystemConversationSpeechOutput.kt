package com.example.offlinetranslator.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.ConversationSpeechOutput
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechPlaybackStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** One lazy route-owned system engine. Platform state and callbacks are confined to the main thread. */
class SystemConversationSpeechOutput internal constructor(
    selectedLanguages: List<LanguageId>,
    private val speedPercent: Int,
    private val createClient: ((Int) -> Unit) -> TtsClient,
    private val focus: TtsAudioFocus,
    private val thread: TtsThread,
    private val dispatcher: CoroutineDispatcher,
) : ConversationSpeechOutput {
    constructor(context: Context, config: ConversationSessionConfig) : this(
        config.languages.also { require(config.speechOutputBackend == SpeechOutputBackend.SYSTEM_OFFLINE) },
        config.ttsSpeedPercent,
        { initialized -> AndroidTtsClient(context, initialized) },
        AndroidTtsAudioFocus(context), AndroidTtsThread(), Dispatchers.Main.immediate,
    )

    private val languages = selectedLanguages.toList().also {
        require(it.isNotEmpty() && it.distinct().size == it.size && it.all { language -> LanguageCatalog.find(language) != null })
        require(speedPercent in 50..150)
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    // All fields below, including the init attempt, are accessed through thread.
    private var client: TtsClient? = null
    private var initialization: InitializationAttempt? = null
    private var initialized = false
    private var invalidatedReadiness: SpeechOutputReadiness? = null
    private var closed = false
    private var generation = 0L
    private var current: Utterance? = null
    private var releaseFailure: Throwable? = null
    private var initializedEngine: String? = null
    private var control: IdentityControl? = null
    private var lastControlId: String? = null
    private var lastControlGeneration = -1L

    /** Actual engine attested after the last voice RPCs, never a requested/default package guess. */
    val enginePackage: String? get() = thread.call { initializedEngine }

    private class Utterance(val id: String, val onStarted: (SpeechPlaybackStart) -> Unit) {
        val completion = CompletableDeferred<Unit>()
        var started = false
        var focusOwned = false
    }

    private class IdentityControl(val id: String, val generation: Long) {
        val completion = CompletableDeferred<String>()
        var startUid: Int? = null
    }
    private class IdentityFailure(val readiness: SpeechOutputReadiness) :
        IllegalStateException("System speech engine identity is unavailable")

    private class InitializationAttempt {
        val signal = CompletableDeferred<Int>()
        lateinit var result: Deferred<SpeechOutputReadiness>
        var client: TtsClient? = null
        var finished = false
        var succeeded = false
        var shutdown = false
    }

    override suspend fun readiness(): SpeechOutputReadiness {
        val requestedGeneration = onPlatform { ensureOpen(); generation }
        return mutex.withLock { checkedReadiness(requestedGeneration) }
    }

    /** User-triggered action gets a fresh attestation even when local voice data is missing. */
    suspend fun voiceDataEnginePackage(): String? {
        val requestedGeneration = onPlatform { ensureOpen(); generation }
        return mutex.withLock {
            val ready = checkedReadiness(requestedGeneration)
            onPlatform {
                ensureOpen()
                if (generation != requestedGeneration) throw CancellationException("Voice action was interrupted")
                if (ready == SpeechOutputReadiness.READY || ready == SpeechOutputReadiness.MISSING_LOCAL_VOICE)
                    initializedEngine else null
            }
        }
    }

    /** Only an explicit user action may clear an actual missing-data error or replace a failed client. */
    suspend fun recheck(): SpeechOutputReadiness = mutex.withLock {
        val previous = onPlatform { ensureOpen(); initialization }
        val result = previous?.result?.await()
        onPlatform {
            ensureOpen()
            val failedIdentity = invalidatedReadiness == SpeechOutputReadiness.ENGINE_UNAVAILABLE ||
                invalidatedReadiness == SpeechOutputReadiness.INITIALIZATION_FAILED
            invalidatedReadiness = null
            if (previous != null && (result != SpeechOutputReadiness.READY || failedIdentity)) {
                shutdownAttempt(previous)
                releaseFailure?.let { throw it }
                client = null
                initialization = null
            }
        }
        val requestedGeneration = onPlatform { ensureOpen(); generation }
        checkedReadiness(requestedGeneration)
    }

    /** The original 10s bound covers initialization AND the first voice/control transaction. */
    private suspend fun checkedReadiness(
        requestedGeneration: Long,
        target: LanguageId? = null,
    ): SpeechOutputReadiness {
        val latched = onPlatform {
            ensureOpen()
            if (generation != requestedGeneration) throw CancellationException("Speech check was interrupted")
            initializedEngine = null
            invalidatedReadiness
        }
        if (latched != null && latched != SpeechOutputReadiness.MISSING_LOCAL_VOICE) return latched
        val result = try {
            withTimeoutOrNull(INIT_TIMEOUT_MILLIS) {
                val ready = ensureInitialization()
                if (ready != SpeechOutputReadiness.READY) return@withTimeoutOrNull ready
                val voices = onPlatform {
                    ensureOpen()
                    if (generation != requestedGeneration) throw CancellationException("Speech check was interrupted")
                    selectVoices(target)
                }
                val actualEngine = attestEngine(requestedGeneration)
                onPlatform {
                    ensureOpen()
                    if (generation != requestedGeneration) throw CancellationException("Speech check was interrupted")
                    initializedEngine = actualEngine
                    if (latched == SpeechOutputReadiness.MISSING_LOCAL_VOICE) latched else voices
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onPlatform {
                initializedEngine = null
                val state = (failure as? IdentityFailure)?.readiness ?: SpeechOutputReadiness.INITIALIZATION_FAILED
                invalidatedReadiness = state
                releaseFailure?.let { throw it }
                state
            }
        }
        if (result != null) return result
        return onPlatform {
            initializedEngine = null
            invalidatedReadiness = SpeechOutputReadiness.INITIALIZATION_FAILED
            val attempt = initialization
            if (attempt != null && !attempt.finished) {
                attempt.signal.complete(TextToSpeech.ERROR)
                shutdownAttempt(attempt)
            }
            releaseFailure?.let { throw it }
            SpeechOutputReadiness.INITIALIZATION_FAILED
        }
    }

    private suspend fun attestEngine(requestedGeneration: Long): String {
        var active: IdentityControl? = null
        try {
            onPlatform {
                ensureOpen()
                if (generation != requestedGeneration) throw CancellationException("Speech check was interrupted")
                val pending = IdentityControl("identity-${UTTERANCE_IDS.incrementAndGet()}", requestedGeneration)
                active = pending
                control = pending
                lastControlId = pending.id
                lastControlGeneration = requestedGeneration
                if (requireNotNull(client).playSilentUtterance(pending.id) != TextToSpeech.SUCCESS) {
                    failControl(pending, IdentityFailure(SpeechOutputReadiness.INITIALIZATION_FAILED), stop = true)
                    throw IdentityFailure(SpeechOutputReadiness.INITIALIZATION_FAILED)
                }
            }
            return requireNotNull(active).completion.await()
        } finally {
            withContext(NonCancellable) {
                onPlatform {
                    val pending = active
                    if (pending != null && control === pending) {
                        failControl(pending, CancellationException("Speech identity check ended"), stop = true)
                    }
                    releaseFailure?.let { throw it }
                }
            }
        }
    }

    private fun failControl(active: IdentityControl, failure: Throwable, stop: Boolean) {
        if (control === active) control = null
        initializedEngine = null
        invalidatedReadiness = (failure as? IdentityFailure)?.readiness ?: SpeechOutputReadiness.INITIALIZATION_FAILED
        if (stop) {
            try { check(client?.stop() == TextToSpeech.SUCCESS) { "System speech control stop failed" } }
            catch (caught: Throwable) {
                recordReleaseFailure(caught)
                if (failure !== caught) failure.addSuppressed(caught)
            }
        }
        active.completion.completeExceptionally(failure)
    }

    private suspend fun ensureInitialization(): SpeechOutputReadiness {
        val attempt = onPlatform {
            ensureOpen()
            initialization ?: InitializationAttempt().also {
                initialization = it
                it.result = scope.async(start = CoroutineStart.LAZY) { initialize(it) }
                it.result.start()
            }
        }
        return attempt.result.await()
    }

    private suspend fun initialize(attempt: InitializationAttempt): SpeechOutputReadiness {
        try {
            onPlatform {
                if (closed) { attempt.signal.complete(TextToSpeech.ERROR); return@onPlatform }
                attempt.client = createClient { status -> thread.post {
                    if (closed || initialization !== attempt || (attempt.finished && !attempt.succeeded)) {
                        // The old connection, not a replacement, owns this late callback.
                        shutdownAttempt(attempt, force = true)
                    } else if (!attempt.finished) {
                        attempt.signal.complete(status)
                    }
                } }
                client = attempt.client
            }
            val status = withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { attempt.signal.await() }
            return onPlatform {
                attempt.finished = true
                if (closed || status != TextToSpeech.SUCCESS) {
                    val engineMissing = attempt.client?.engineAvailable == false
                    shutdownAttempt(attempt)
                    if (engineMissing) SpeechOutputReadiness.ENGINE_UNAVAILABLE
                    else SpeechOutputReadiness.INITIALIZATION_FAILED
                } else {
                    val platform = requireNotNull(attempt.client)
                    if (platform.configure(speedPercent / 100f) != TextToSpeech.SUCCESS ||
                        platform.setListener(listener) != TextToSpeech.SUCCESS) {
                        shutdownAttempt(attempt)
                        SpeechOutputReadiness.INITIALIZATION_FAILED
                    } else {
                        attempt.succeeded = true
                        initialized = true
                        initializedEngine = null
                        SpeechOutputReadiness.READY
                    }
                }
            }
        } catch (_: Exception) {
            return onPlatform {
                attempt.finished = true
                shutdownAttempt(attempt)
                SpeechOutputReadiness.INITIALIZATION_FAILED
            }
        }
    }

    override suspend fun prepare() {
        check(readiness() == SpeechOutputReadiness.READY) { "Selected local system voices are not ready" }
    }

    override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) {
        require(language in languages) { "Language is not selected for speech output" }
        require(text.isNotBlank() && text.length in 1..MAX_TEXT_LENGTH && text.any(Char::isLetterOrDigit)) {
            "Speech text is empty or exceeds the text quota"
        }
        val requestedGeneration = onPlatform { ensureOpen(); generation }
        mutex.withLock {
            coroutineContext.ensureActive()
            var utterance: Utterance? = null
            try {
                check(checkedReadiness(requestedGeneration, language) == SpeechOutputReadiness.READY) {
                    "Selected local system voices are not ready"
                }
                onPlatform {
                    ensureOpen()
                    if (generation != requestedGeneration) throw CancellationException("Queued speech was interrupted")
                    val platform = requireNotNull(client)
                    require(text.length <= minOf(MAX_TEXT_LENGTH, platform.maximumInputLength)) { "Speech text exceeds platform quota" }
                    val active = Utterance("offline-${UTTERANCE_IDS.incrementAndGet()}", onPlaybackStarted)
                    utterance = active
                    current = active
                    active.focusOwned = true // A rejected request must also abandon its registered listener.
                    val granted = focus.request { thread.call {
                        if (current === active) interrupt(IllegalStateException("Speech audio focus was lost"))
                    } }
                    check(granted) { "Speech audio focus was denied" }
                    if (current !== active) throw CancellationException("Speech was interrupted during focus request")
                    val result = platform.speak(text, active.id)
                    if (result == TextToSpeech.ERROR_NOT_INSTALLED_YET) {
                        initializedEngine = null
                        invalidatedReadiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
                        throw IllegalStateException("Selected local system voice data is missing")
                    }
                    if (result != TextToSpeech.SUCCESS) {
                        initializedEngine = null
                        invalidatedReadiness = SpeechOutputReadiness.INITIALIZATION_FAILED
                    }
                    check(result == TextToSpeech.SUCCESS) { "System speech could not be enqueued" }
                }
                requireNotNull(utterance).completion.await()
            } finally {
                withContext(NonCancellable) {
                    onPlatform {
                        val active = utterance
                        if (active != null && current === active) interrupt(CancellationException("Speech operation ended"))
                        releaseFailure?.let { throw it }
                    }
                }
            }
        }
    }

    private fun selectVoices(target: LanguageId?): SpeechOutputReadiness {
        if (!initialized) return SpeechOutputReadiness.INITIALIZATION_FAILED
        val platform = requireNotNull(client)
        val inventory = platform.voices
        val default = platform.defaultVoice
        if (inventory.isEmpty() || default == null) return SpeechOutputReadiness.MISSING_LOCAL_VOICE
        fun chooseAndActivate(language: LanguageId): Boolean {
            val locale = AndroidSpeechLocales.output(language)
            var fixed: TtsVoice? = null
            var other: TtsVoice? = null
            for (voice in inventory) {
                if (!voice.eligibleFor(locale)) continue
                if (voice.locale == locale) {
                    val previous = fixed
                    if (previous == null || voice == default ||
                        (previous != default && voice.name < previous.name)) fixed = voice
                } else if (other == null || voice.name < requireNotNull(other).name) {
                    other = voice
                }
            }
            val selected = fixed ?: other ?: return false
            return activate(platform, selected)
        }
        // Leave the requested target active without a duplicate setVoice/currentVoice RPC.
        for (language in languages) {
            if (language != target && !chooseAndActivate(language)) return SpeechOutputReadiness.MISSING_LOCAL_VOICE
        }
        if (target != null && !chooseAndActivate(target)) return SpeechOutputReadiness.MISSING_LOCAL_VOICE
        return SpeechOutputReadiness.READY
    }

    private fun TtsVoice.eligibleFor(locale: Locale): Boolean {
        if (networkRequired || notInstalled) return false
        if (locale.language == "zh") {
            return (this.locale.language.equals("zh", ignoreCase = true) ||
                this.locale.language.equals("cmn", ignoreCase = true)) &&
                this.locale.country.equals("CN", ignoreCase = true) &&
                (this.locale.script.isEmpty() || this.locale.script.equals("Hans", ignoreCase = true))
        }
        return this.locale.language.equals(locale.language, ignoreCase = true)
    }

    private fun activate(platform: TtsClient, voice: TtsVoice): Boolean {
        if (platform.dataAvailability(voice.locale) < TextToSpeech.LANG_AVAILABLE) return false
        val result = platform.setVoice(voice)
        if (result == TextToSpeech.ERROR_NOT_INSTALLED_YET) invalidatedReadiness = SpeechOutputReadiness.MISSING_LOCAL_VOICE
        return result == TextToSpeech.SUCCESS && platform.currentVoice == voice
    }

    private val listener = object : TtsListener {
        override fun onStart(id: String, callerUid: Int) = thread.post {
            val pending = control
            if (!closed && pending?.id == id && pending.generation == generation) {
                val previous = pending.startUid
                if (previous == null || previous == callerUid) pending.startUid = callerUid
                else failControl(pending, IdentityFailure(SpeechOutputReadiness.ENGINE_UNAVAILABLE), stop = true)
                return@post
            }
            val active = current
            if (!closed && active?.id == id && !active.started) {
                active.started = true
                try {
                    active.onStarted(PHASE_ONLY_PLAYBACK_START)
                } catch (failure: Throwable) {
                    if (current === active) interrupt(failure)
                }
            }
        }
        override fun onDone(id: String, callerUid: Int) = thread.post {
            val pending = control
            if (!closed && pending?.id == id && pending.generation == generation) {
                val actual = try {
                    if (pending.startUid == callerUid) client?.resolveEnginePackage(callerUid) else null
                } catch (_: Exception) {
                    failControl(pending, IdentityFailure(SpeechOutputReadiness.INITIALIZATION_FAILED), stop = false)
                    return@post
                }
                if (actual.isNullOrBlank()) {
                    failControl(pending, IdentityFailure(SpeechOutputReadiness.ENGINE_UNAVAILABLE), stop = false)
                } else {
                    control = null
                    pending.completion.complete(actual)
                }
            } else terminal(id, null, stop = false)
        }
        override fun onError(id: String, code: Int) = thread.post {
            val pending = control
            if (!closed && pending?.id == id) {
                failControl(pending, IdentityFailure(SpeechOutputReadiness.INITIALIZATION_FAILED), stop = true)
                return@post
            }
            if (!closed && current?.id == id) {
                initializedEngine = null
                invalidatedReadiness = if (code == TextToSpeech.ERROR_NOT_INSTALLED_YET)
                    SpeechOutputReadiness.MISSING_LOCAL_VOICE else SpeechOutputReadiness.INITIALIZATION_FAILED
            }
            terminal(id, IllegalStateException(if (code == TextToSpeech.ERROR_NOT_INSTALLED_YET)
                "Selected local system voice data is missing" else "System speech failed"), stop = true)
        }
        override fun onStop(id: String) = thread.post {
            val pending = control
            if (!closed && pending?.id == id) {
                failControl(pending, CancellationException("System speech control was stopped"), stop = false)
                return@post
            }
            terminal(id, CancellationException("System speech was stopped"), stop = false)
        }
        override fun onControlSynthesis(id: String) = thread.post {
            if (!closed && lastControlId == id && lastControlGeneration == generation) {
                val failure = IdentityFailure(SpeechOutputReadiness.INITIALIZATION_FAILED)
                val pending = control
                if (pending?.id == id) failControl(pending, failure, stop = true)
                else {
                    initializedEngine = null
                    invalidatedReadiness = failure.readiness
                    interrupt(failure)
                }
            }
        }
    }

    private fun terminal(id: String, failure: Throwable?, stop: Boolean) {
        val active = current ?: return
        if (closed || active.id != id) return
        current = null // Reentrant/stale callbacks cannot revive or finish a successor.
        finish(active, failure, stop)
    }

    private fun finish(active: Utterance, failure: Throwable?, stop: Boolean) {
        var result = failure
        fun release(block: () -> Unit) {
            try { block() } catch (caught: Throwable) {
                recordReleaseFailure(caught)
                if (result == null) result = caught else if (result !== caught) result!!.addSuppressed(caught)
            }
        }
        if (stop) release { check(client?.stop() == TextToSpeech.SUCCESS) { "System speech stop failed" } }
        if (active.focusOwned) {
            active.focusOwned = false
            release { focus.abandon() }
        }
        val terminal = result
        if (terminal == null) active.completion.complete(Unit) else active.completion.completeExceptionally(terminal)
    }

    private fun interrupt(failure: Throwable) {
        generation++
        initializedEngine = null
        lastControlId = null
        val pending = control
        if (pending != null) failControl(pending, failure, stop = true)
        val active = current
        current = null
        if (active != null) finish(active, failure, stop = true)
    }

    override fun interruptCurrent(): Unit = thread.call {
        interrupt(CancellationException("Speech playback interrupted"))
        releaseFailure?.let { throw it }
    }

    override fun throwIfReleaseFailed(): Unit = thread.call { releaseFailure?.let { throw it }; Unit }
    override fun close(): Unit = thread.call {
        if (!closed) {
            closed = true
            try { interrupt(CancellationException("Speech output closed")) } finally {
                initialization?.signal?.complete(TextToSpeech.ERROR)
                shutdownClient()
            }
        }
        releaseFailure?.let { throw it }
    }

    override suspend fun closeAndJoin() {
        withContext(NonCancellable) {
            var closeFailure: Throwable? = null
            try { close() } catch (caught: Throwable) { closeFailure = caught }
            try {
                onPlatform { initialization }?.result?.await()
                mutex.withLock { onPlatform { shutdownClient(); releaseFailure?.let { throw it } } }
            } finally { scope.cancel() }
            closeFailure?.let { throw it }
        }
    }

    private fun shutdownClient() { initialization?.let { shutdownAttempt(it) } }
    private fun shutdownAttempt(attempt: InitializationAttempt, force: Boolean = false) {
        if (initialization === attempt) {
            initialized = false
            initializedEngine = null
        }
        if (!attempt.shutdown || force) {
            attempt.shutdown = true
            try { attempt.client?.shutdown() } catch (failure: Throwable) { recordReleaseFailure(failure) }
        }
    }
    private fun recordReleaseFailure(failure: Throwable) {
        val previous = releaseFailure
        if (previous == null) releaseFailure = failure else if (previous !== failure) previous.addSuppressed(failure)
    }
    private fun ensureOpen() {
        check(!closed) { "Speech output is closed" }
        releaseFailure?.let { throw it }
    }
    private suspend fun <T> onPlatform(block: () -> T): T = withContext(dispatcher) { thread.call(block) }

    companion object {
        private const val MAX_TEXT_LENGTH = 4_096
        internal const val INIT_TIMEOUT_MILLIS = 10_000L
        private val UTTERANCE_IDS = AtomicLong()
        private val PHASE_ONLY_PLAYBACK_START = SpeechPlaybackStart(presentationTimeNanos = null, measured = false)
    }
}
