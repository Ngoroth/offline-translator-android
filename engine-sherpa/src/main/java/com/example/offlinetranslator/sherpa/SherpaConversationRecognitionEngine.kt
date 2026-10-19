package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.ConversationRecognitionEngine
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.RecognizedUtterance
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechRecognitionBackend
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.UnsupportedSpeechLanguageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** The immutable selection, not a language switch, owns the LID and ASR native paths. */
class SherpaConversationRecognitionEngine internal constructor(
    pairs: Set<LanguagePair>,
    private val ready: suspend () -> Boolean,
    private val installSelected: suspend () -> Unit,
    private val createIdentifier: suspend () -> NativeLanguageIdentifier,
    private val createRecognizer: suspend (LanguageId) -> NativeSpeechRecognizer,
) : ConversationRecognitionEngine {
    constructor(store: SpeechModelStore, config: ConversationSessionConfig) : this(
        pairs = config.pairs,
        ready = selectedRecognitionReadiness(store, config),
        installSelected = selectedRecognitionInstaller(store, config),
        createIdentifier = selectedIdentifierFactory(store, config),
        createRecognizer = selectedRecognizerFactory(store, config),
    )

    private val pairs = pairs.toSet()
    private val languages = pairs.map { it.source }.toSet()
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var identifier: NativeLanguageIdentifier? = null
    private val recognizers = LinkedHashMap<LanguageId, NativeSpeechRecognizer>(languages.size)
    private var prepared = false
    private val mutableReadiness = MutableStateFlow(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
    override val readinessState: StateFlow<SpeechRecognitionReadiness> = mutableReadiness.asStateFlow()

    private suspend fun checkReadiness(): SpeechRecognitionReadiness {
        ensureOpen()
        return try {
            if (ready()) SpeechRecognitionReadiness.READY else SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SpeechRecognitionReadiness.CHECK_FAILED
        }.also { ensureOpen(); mutableReadiness.value = it }
    }

    override suspend fun readiness(): SpeechRecognitionReadiness = mutex.withLock { checkReadiness() }

    override suspend fun install() = mutex.withLock {
        ensureOpen()
        mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOADING
        try {
            installSelected()
            checkReadiness()
            Unit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!closed.get()) mutableReadiness.value = SpeechRecognitionReadiness.DOWNLOAD_FAILED
            throw failure
        }
    }

    init {
        require(pairs.size == 2 && languages.size == 2 && pairs.all { it.reverse() in pairs })
    }

    private suspend fun ensureOpen() {
        coroutineContext.ensureActive()
        check(!closed.get()) { "Conversation recognizer is closed" }
    }

    override suspend fun prepare() = mutex.withLock {
        try {
            withContext(Dispatchers.Default) {
                ensureOpen()
                check(checkReadiness() == SpeechRecognitionReadiness.READY) { "Selected recognition models are not ready" }
                if (prepared) return@withContext
                if (identifier == null) {
                    val created = createIdentifier()
                    try { ensureOpen(); identifier = created } catch (failure: Throwable) {
                        created.close()
                        throw failure
                    }
                }
                for (language in languages) {
                    ensureOpen()
                    if (language in recognizers) continue
                    val created = createRecognizer(language)
                    try { ensureOpen(); recognizers[language] = created } catch (failure: Throwable) {
                        created.close()
                        throw failure
                    }
                }
                // Construction alone does not execute ONNX kernels. Warm every selected path.
                val silence = ShortArray(SpeechSegment.SAMPLE_RATE_HZ)
                val silenceSamples = FloatArray(silence.size)
                ensureOpen()
                check(requireNotNull(identifier).identify(silenceSamples, ::ensureOpen).isNotBlank()) {
                    "Language identification warm-up failed"
                }
                for (recognizer in recognizers.values) {
                    ensureOpen()
                    recognizer.recognize(silence, ::ensureOpen, silenceSamples)
                }
                ensureOpen()
                prepared = true
            }
        } finally {
            if (closed.get()) releaseNatives()
        }
    }

    override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance = mutex.withLock {
        try {
            withContext(Dispatchers.Default) {
                ensureOpen()
                check(prepared) { "Conversation recognizer is not prepared" }
                val pcm = segment.copyPcm()
                val samples = FloatArray(pcm.size) { pcm[it] / 32_768f }
                val identified = requireNotNull(identifier).identify(samples, ::ensureOpen)
                ensureOpen()
                check(identified.isNotBlank()) { "Language identification returned no language" }
                val language = try { LanguageId(identified) } catch (_: IllegalArgumentException) {
                    throw UnsupportedSpeechLanguageException()
                }
                val pair = pairs.singleOrNull { it.source == language }
                    ?: throw UnsupportedSpeechLanguageException()
                val text = recognizers.getValue(language).recognize(pcm, ::ensureOpen, samples).trim()
                ensureOpen()
                check(text.isNotEmpty()) { "Speech recognition returned no text" }
                RecognizedUtterance(pair, text)
            }
        } finally {
            if (closed.get()) releaseNatives()
        }
    }

    private fun releaseNatives() {
        val natives = recognizers.values.toList()
        recognizers.clear()
        val oldIdentifier = identifier
        identifier = null
        prepared = false
        var failure: Throwable? = null
        for (native in listOfNotNull(oldIdentifier) + natives) {
            try { native.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        closed.set(true)
        if (mutex.tryLock()) {
            try { releaseNatives() } finally { mutex.unlock() }
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { mutex.withLock { releaseNatives() } }
    }

    companion object {
        private fun selectedRecognitionReadiness(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> Boolean {
            val definitions = selectedDefinitions(store, config)
            val dependencies = definitions.flatMap { it.requiredRefs }.map { it.packageId }.toSet()
            return { store.availability(dependencies) }
        }

        private fun selectedRecognitionInstaller(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> Unit {
            val dependencies = selectedDefinitions(store, config).flatMap { it.requiredRefs }.map { it.packageId }.toSet()
            return { store.install(dependencies) }
        }

        private fun selectedDefinitions(store: SpeechModelStore, config: ConversationSessionConfig): List<SpeechModelDefinition> {
            require(config.speechRecognitionBackend == SpeechRecognitionBackend.SHERPA)
            val identifier = store.catalog.model(config.languageIdModelId)
            require(identifier.role == SpeechModelRole.LANGUAGE_ID && identifier.languages.containsAll(config.languages)) {
                "Selected language identifier does not support the session languages"
            }
            return listOf(identifier) + config.languages.map { language ->
                store.catalog.model(config.asrModelIds.getValue(language)).also {
                    require(it.role == SpeechModelRole.ASR && language in it.languages) {
                        "Selected ASR does not support the session language"
                    }
                }
            }
        }

        private fun selectedIdentifierFactory(store: SpeechModelStore, config: ConversationSessionConfig): suspend () -> NativeLanguageIdentifier {
            val definition = selectedDefinitions(store, config).first()
            return { SherpaModelFactories.createIdentifier(store, definition) }
        }

        private fun selectedRecognizerFactory(store: SpeechModelStore, config: ConversationSessionConfig): suspend (LanguageId) -> NativeSpeechRecognizer {
            selectedDefinitions(store, config)
            val definitions = config.languages.associateWith { store.catalog.model(config.asrModelIds.getValue(it)) }
            return { language -> SherpaModelFactories.createRecognizer(store, definitions.getValue(language)) }
        }
    }
}

internal interface NativeSpeechRecognizer : AutoCloseable {
    suspend fun recognize(pcm: ShortArray, ensureActive: suspend () -> Unit, samples: FloatArray? = null): String
}

internal interface NativeLanguageIdentifier : AutoCloseable {
    suspend fun identify(samples: FloatArray, ensureActive: suspend () -> Unit): String
}
