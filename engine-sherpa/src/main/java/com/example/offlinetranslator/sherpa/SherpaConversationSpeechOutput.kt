package com.example.offlinetranslator.sherpa

import android.content.Context
import com.example.offlinetranslator.core.ConversationSpeechOutput
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.SpeechPlaybackStart
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Retains only selected local voices; active JNI leases outlive coroutine cancellation. */
class SherpaConversationSpeechOutput internal constructor(
    private val createVoice: suspend (LanguageId) -> ConversationVoice,
    private val playback: AndroidSpeechPlayback,
    selectedLanguages: List<LanguageId>,
    private val localVoicesReady: suspend () -> Boolean,
) : ConversationSpeechOutput {
    constructor(context: Context, store: SpeechModelStore, config: ConversationSessionConfig) : this(
        createVoice = selectedVoiceFactory(context, store, config),
        playback = AndroidSpeechPlayback(context),
        selectedLanguages = config.languages,
        localVoicesReady = selectedReadiness(store, config),
    )

    private val languages = selectedLanguages.toList().also {
        require(it.isNotEmpty() && it.distinct().size == it.size)
    }

    private val mutex = Mutex()
    private val lifecycleLock = Any()
    private val voices = HashMap<LanguageId, ConversationVoice>(languages.size)
    private var closed = false
    private var interruptGeneration = 0L
    private var operation: Operation? = null
    private var currentPlayback: AndroidSpeechPlayback.Session? = null

    override suspend fun readiness(): SpeechOutputReadiness = mutex.withLock {
        val lease = beginOperation()
        try {
            val ready = localVoicesReady()
            lease.ensureActive()
            if (ready) SpeechOutputReadiness.READY else SpeechOutputReadiness.MISSING_LOCAL_VOICE
        } finally {
            endOperation(lease)
        }
    }

    override suspend fun prepare() = mutex.withLock {
        withContext(Dispatchers.Default) {
            val lease = beginOperation()
            try {
                prepareVoices(lease)
            } finally {
                endOperation(lease)
            }
        }
    }

    override suspend fun speak(
        language: LanguageId,
        text: String,
        onPlaybackStarted: (SpeechPlaybackStart) -> Unit,
    ) {
        require(language in languages) { "Language is not selected for speech output" }
        require(text.length in 1..MAXIMUM_TEXT_LENGTH && text.isNotBlank()) { "Speech text exceeds the text quota or is empty" }
        require(text.any(Char::isLetterOrDigit)) { "Speech text contains no pronounceable characters" }
        // An interruption also invalidates requests already waiting for the serialization mutex.
        val generation = synchronized(lifecycleLock) {
            check(!closed) { "Speech output is closed" }
            interruptGeneration
        }
        mutex.withLock {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    val lease = beginOperation(generation)
                    var session: AndroidSpeechPlayback.Session? = null
                    var observer: Job? = null
                    try {
                        prepareVoices(lease)
                        lease.ensureActive()
                        val native = voices.getValue(language)
                        val output = playback.open(
                            register = { opened ->
                                session = opened
                                synchronized(lifecycleLock) {
                                    lease.ensureActive()
                                    currentPlayback = opened
                                }
                            },
                            onStarted = onPlaybackStarted,
                        )
                        val ensureOutputActive: () -> Unit = {
                            lease.ensureActive()
                            output.ensureOpen()
                        }
                        ensureOutputActive()
                        observer = launch(Dispatchers.IO) {
                            try {
                                while (isActive) {
                                    output.observe()
                                    delay(5)
                                }
                            } catch (cancelled: CancellationException) {
                                if (!isActive) output.fail(cancelled)
                            } catch (failure: Throwable) {
                                output.fail(failure)
                            } finally {
                                if (!isActive) output.fail(CancellationException("Speech playback observation cancelled"))
                            }
                        }
                        val callback = StreamingSynthesisCallback(output, ensureOutputActive)
                        val generated = native.generate(text, callback)
                        callback.failure?.let { throw it }
                        ensureOutputActive()
                        check(generated.sampleRate == SpeechPcmBuffer.SAMPLE_RATE) { "Local voice returned an unsupported sample rate" }
                        check(generated.sampleCount == output.acceptedFrames && generated.sampleCount > 0) {
                            "Local synthesis did not deliver complete speech PCM"
                        }
                        output.drain(ensureOutputActive)
                        ensureOutputActive()
                    } finally {
                        // The native call has returned before either voice can be released.
                        try {
                            session?.close()
                        } finally {
                            withContext(NonCancellable) { observer?.cancelAndJoin() }
                            synchronized(lifecycleLock) {
                                if (currentPlayback === session) currentPlayback = null
                            }
                            endOperation(lease)
                        }
                    }
                }
            }
        }
    }

    private suspend fun prepareVoices(lease: Operation) {
        for (language in languages) {
            lease.ensureActive()
            if (voices.containsKey(language)) continue
            val voice = createVoice(language)
            try {
                lease.ensureActive()
                check(voice.sampleRate == SpeechPcmBuffer.SAMPLE_RATE) { "Local voice must synthesize mono 22050 Hz PCM" }
                voices[language] = voice
            } catch (failure: Throwable) {
                voice.close()
                throw failure
            }
        }
    }

    private suspend fun beginOperation(expectedGeneration: Long? = null): Operation {
        coroutineContext.ensureActive()
        val job = coroutineContext[Job]
        return synchronized(lifecycleLock) {
            check(!closed) { "Speech output is closed" }
            if (expectedGeneration != null && expectedGeneration != interruptGeneration) {
                throw CancellationException("Queued speech was interrupted")
            }
            check(operation == null) { "Speech output already has a native owner" }
            Operation(job).also { operation = it }
        }
    }

    private fun endOperation(lease: Operation) = synchronized(lifecycleLock) {
        check(operation === lease)
        operation = null
        if (closed) releaseVoicesLocked()
    }

    override fun interruptCurrent() {
        val output = synchronized(lifecycleLock) {
            interruptGeneration++
            operation?.cancelled?.set(true)
            currentPlayback
        }
        // Do not hold lifecycleLock: a playback-start callback may synchronously stop its owner.
        output?.fail(CancellationException("Speech playback interrupted"))
    }

    override fun close() {
        val output = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            interruptGeneration++
            operation?.cancelled?.set(true)
            if (operation == null) releaseVoicesLocked()
            currentPlayback
        }
        output?.fail(CancellationException("Speech output closed"))
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) {
            mutex.withLock { synchronized(lifecycleLock) { releaseVoicesLocked() } }
        }
    }

    private fun releaseVoicesLocked() {
        var failure: Throwable? = null
        for (voice in voices.values) {
            try {
                voice.close()
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        voices.clear()
        failure?.let { throw it }
    }

    private class Operation(private val job: Job?) {
        val cancelled = AtomicBoolean(false)
        fun ensureActive() {
            if (cancelled.get()) throw CancellationException("Speech generation interrupted")
            job?.ensureActive()
        }
    }

    /** An explicit class is required: JNI invokes invoke(float[]) returning BOXED Integer. */
    private class StreamingSynthesisCallback(
        private val output: AndroidSpeechPlayback.Session,
        private val ensureActive: () -> Unit,
    ) : (FloatArray) -> Int {
        var failure: Throwable? = null
            private set

        override fun invoke(samples: FloatArray): Int {
            if (failure != null) return 0
            return try {
                ensureActive()
                output.append(samples, ensureActive)
                1
            } catch (caught: Throwable) {
                // Never carry a pending Java exception back through the JNI callback.
                failure = caught
                output.fail(caught)
                0
            }
        }
    }

    companion object {
        internal const val MAXIMUM_TEXT_LENGTH = 4_096

        private fun selectedReadiness(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> Boolean {
            require(config.speechOutputBackend == SpeechOutputBackend.PIPER) { "Piper output is not selected" }
            val ids = config.languages.map { config.ttsModelIds.getValue(it) }.toSet()
            val packages = store.catalog.requiredPackages(ids)
            return { store.availability(packages) }
        }

        private fun selectedVoiceFactory(
            context: Context,
            store: SpeechModelStore,
            config: ConversationSessionConfig,
        ): suspend (LanguageId) -> ConversationVoice {
            val definitions = config.languages.associateWith { language ->
                store.catalog.model(config.ttsModelIds.getValue(language)).also {
                    require(it.role == com.example.offlinetranslator.core.SpeechModelRole.TTS &&
                        language in it.languages) { "Selected voice is incompatible with language" }
                }
            }
            return { language ->
                val voice = SherpaModelFactories.createVoice(
                    context.cacheDir, store, definitions.getValue(language), config.ttsSpeedPercent,
                )
                try {
                    coroutineContext.ensureActive()
                    val text = LanguageCatalog.find(language)?.warmUpText
                        ?: error("Selected language has no warm-up text")
                    val result = voice.generate(text)
                    require(result.sampleRate == SpeechPcmBuffer.SAMPLE_RATE && result.sampleCount > 0) {
                        "Voice warm-up failed"
                    }
                    coroutineContext.ensureActive()
                    voice
                } catch (failure: Throwable) {
                    voice.close()
                    throw failure
                }
            }
        }
    }
}

/** The boundary exists only to exercise ownership without loading native weights in JVM unit tests. */
internal interface ConversationVoice : AutoCloseable {
    val sampleRate: Int
    fun generate(text: String, callback: ((FloatArray) -> Int)? = null): ConversationVoiceResult
}

internal data class ConversationVoiceResult(val sampleRate: Int, val sampleCount: Int)

