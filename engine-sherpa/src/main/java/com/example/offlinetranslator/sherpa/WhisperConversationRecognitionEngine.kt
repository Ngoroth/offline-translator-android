package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.ConversationPipeline
import com.example.offlinetranslator.core.ConversationRecognitionEngine
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.RecognizedUtterance
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.UnsupportedSpeechLanguageException
import com.example.offlinetranslator.speechmodels.SpeechModelCatalog
import com.example.offlinetranslator.speechmodels.SpeechModelDefinition
import com.example.offlinetranslator.speechmodels.SpeechModelStore
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

/** One resident multilingual recognizer owns the immutable selected conversation pair. */
class WhisperConversationRecognitionEngine internal constructor(
    private val pair: LanguagePair,
    private val ready: suspend () -> Boolean,
    private val installSelected: suspend () -> Unit,
    private val createRecognizer: suspend () -> NativeWhisperRecognizer,
) : ConversationRecognitionEngine {
    constructor(store: SpeechModelStore, config: ConversationSessionConfig) : this(
        pair = LanguagePair(config.languages[0], config.languages[1]),
        ready = selectedReadiness(store, config),
        installSelected = selectedInstaller(store, config),
        createRecognizer = selectedFactory(store, config),
    )

    private val reverse = pair.reverse()
    private val mutex = Mutex()
    private val closed = AtomicBoolean()
    private val released = CompletableDeferred<Unit>()
    private var recognizer: NativeWhisperRecognizer? = null
    private val mutableReadiness = MutableStateFlow(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
    override val readinessState = mutableReadiness.asStateFlow()

    override suspend fun readiness(): SpeechRecognitionReadiness = mutex.withLock {
        ensureOpen(coroutineContext)
        checkReadiness().also { ensureOpen(coroutineContext); mutableReadiness.value = it }
    }

    override suspend fun install() = mutex.withLock {
        ensureOpen(coroutineContext)
        mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOADING
        try {
            installSelected()
            ensureOpen(coroutineContext)
            val state = checkReadiness()
            ensureOpen(coroutineContext)
            mutableReadiness.value = state
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!closed.get()) mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOAD_FAILED
            throw failure
        }
    }

    override suspend fun prepare() = mutex.withLock {
        val caller = coroutineContext
        ensureOpen(caller)
        if (recognizer != null) return@withLock
        var created: NativeWhisperRecognizer? = null
        var resourcesReady = false
        try {
            withContext(Dispatchers.Default + NonCancellable) {
                ensureOpen(caller)
                val state = checkReadiness()
                ensureOpen(caller)
                if (state != SpeechRecognitionReadiness.READY) {
                    mutableReadiness.value = state
                    error("Selected Whisper model is not ready")
                }
                resourcesReady = true
                // Record ownership before crossing any cancellable return/dispatch boundary.
                ensureOpen(caller)
                created = createRecognizer()
                ensureOpen(caller)
                val warmed = requireNotNull(created).recognize(FloatArray(SpeechSegment.SAMPLE_RATE_HZ)) { ensureOpen(caller) }
                ensureOpen(caller)
                validateResult(warmed, allowEmptyText = true)
            }
            ensureOpen(caller)
            recognizer = created
            created = null
            mutableReadiness.value = SpeechRecognitionReadiness.READY
        } catch (failure: Throwable) {
            if (failure !is CancellationException && !closed.get() && resourcesReady) {
                mutableReadiness.value = SpeechRecognitionReadiness.CHECK_FAILED
            }
            try { created?.close() } catch (cleanup: Throwable) {
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance = serialized { caller ->
        val native = checkNotNull(recognizer) { "Conversation recognizer is not prepared" }
        val samples = FloatArray(segment.sampleCount)
        segment.copyNormalizedTo(samples)
        val result = native.recognize(samples) { ensureOpen(caller) }
        ensureOpen(caller)
        val direction = validateResult(result, allowEmptyText = false)
        RecognizedUtterance(direction, result.text)
    }

    private fun validateResult(result: NativeWhisperResult, allowEmptyText: Boolean): LanguagePair {
        check(result.pairStatus == "ok") { "Whisper selected-pair native completion is missing or failed" }
        val direction = when (result.language) {
            pair.source.value -> pair
            pair.target.value -> reverse
            else -> throw UnsupportedSpeechLanguageException()
        }
        require(result.text.length <= ConversationPipeline.MAX_TEXT_CHARACTERS) { "Speech recognition text exceeds the input limit" }
        check(allowEmptyText || result.text.isNotBlank()) { "Speech recognition returned no text" }
        return direction
    }

    private suspend fun checkReadiness(): SpeechRecognitionReadiness = try {
        if (ready()) SpeechRecognitionReadiness.READY else SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SpeechRecognitionReadiness.CHECK_FAILED
    }

    private suspend fun <T> serialized(block: suspend (CoroutineContext) -> T): T {
        val caller = coroutineContext
        return mutex.withLock {
            ensureOpen(caller)
            // ORT cannot be interrupted: retain the lease until actual return and stream disposal.
            withContext(Dispatchers.Default + NonCancellable) {
                ensureOpen(caller)
                block(caller).also { ensureOpen(caller) }
            }
        }
    }

    private fun ensureOpen(caller: CoroutineContext) {
        caller.ensureActive()
        check(!closed.get()) { "Conversation recognizer is closed" }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        CoroutineScope(Dispatchers.Default).launch {
            mutex.withLock {
                val previous = recognizer
                recognizer = null
                try {
                    previous?.close()
                    released.complete(Unit)
                } catch (failure: Throwable) {
                    released.completeExceptionally(failure)
                }
            }
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { released.await() }
    }

    companion object {
        const val MODEL_ID = "asr-whisper-small-multilingual-int8"

        internal fun selectedDefinition(catalog: SpeechModelCatalog, config: ConversationSessionConfig): SpeechModelDefinition {
            require(config.speechRecognitionBackend == SpeechRecognitionBackend.WHISPER) { "Whisper recognition is not selected" }
            return catalog.model(MODEL_ID).also { definition ->
                val adapter = definition.adapter as? SherpaSpeechModelAdapter.WhisperMultilingual
                    ?: error("Selected model is not a multilingual Whisper ASR")
                require(definition.role == SpeechModelRole.ASR && definition.sampleRateHz == SpeechSegment.SAMPLE_RATE_HZ &&
                    definition.languages.containsAll(config.languages)) { "Selected Whisper model does not support the session" }
                require(definition.requiredRefs == listOf(adapter.encoder, adapter.decoder, adapter.tokens)) {
                    "Selected Whisper model dependencies are incomplete"
                }
            }
        }

        private fun selectedReadiness(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> Boolean {
            val dependencies = selectedDefinition(store.catalog, config).requiredRefs.mapTo(linkedSetOf()) { it.packageId }
            return { store.availability(dependencies) }
        }

        private fun selectedInstaller(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> Unit {
            val dependencies = selectedDefinition(store.catalog, config).requiredRefs.mapTo(linkedSetOf()) { it.packageId }
            return { store.install(dependencies) }
        }

        private fun selectedFactory(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> NativeWhisperRecognizer {
            val definition = selectedDefinition(store.catalog, config)
            val pair = LanguagePair(config.languages[0], config.languages[1])
            return { SherpaModelFactories.createWhisperRecognizer(store, definition, pair) }
        }
    }
}

internal data class NativeWhisperResult(val text: String, val language: String, val pairStatus: String)

internal interface NativeWhisperRecognizer : AutoCloseable {
    suspend fun recognize(samples: FloatArray, ensureActive: suspend () -> Unit): NativeWhisperResult
}
