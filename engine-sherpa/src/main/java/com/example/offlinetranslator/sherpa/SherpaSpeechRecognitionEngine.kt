package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechRecognitionEngine
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Selected ASRs, including the historical native online families and their 8 kHz resampler. */
class SherpaSpeechRecognitionEngine(
    private val store: SpeechModelStore,
    pairs: Set<LanguagePair>,
    asrModelIds: Map<LanguageId, String>,
) : SpeechRecognitionEngine {
    private val selectedPairs = pairs.toSet().also { require(it.isNotEmpty() && it.size <= 2) }
    private val definitions = selectedPairs.map { it.source }.distinct().associateWith { language ->
        store.catalog.model(asrModelIds.getValue(language)).also {
            require(it.role == SpeechModelRole.ASR && language in it.languages) { "Selected ASR is incompatible" }
        }
    }
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val recognizers = LinkedHashMap<LanguageId, NativeSpeechRecognizer>(definitions.size)

    private suspend fun ensureOpen() {
        coroutineContext.ensureActive()
        check(!closed.get()) { "Speech recognizer is closed" }
    }

    override suspend fun recognize(pair: LanguagePair, segment: SpeechSegment): String {
        require(pair in selectedPairs) { "Language pair is not selected" }
        return mutex.withLock {
            try {
                withContext(Dispatchers.Default) {
                    ensureOpen()
                    val native = recognizers[pair.source] ?: run {
                        val created = SherpaModelFactories.createRecognizer(store, definitions.getValue(pair.source))
                        try { ensureOpen(); recognizers[pair.source] = created } catch (failure: Throwable) {
                            created.close()
                            throw failure
                        }
                        created
                    }
                    native.recognize(segment.copyPcm(), ::ensureOpen).trim().also { ensureOpen() }
                }
            } finally {
                if (closed.get()) releaseRecognizers()
            }
        }
    }

    private fun releaseRecognizers() {
        val old = recognizers.values.toList()
        recognizers.clear()
        var failure: Throwable? = null
        for (recognizer in old) {
            try { recognizer.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        closed.set(true)
        if (mutex.tryLock()) {
            try { releaseRecognizers() } finally { mutex.unlock() }
        }
    }

    override suspend fun closeAndJoin() {
        close()
        withContext(NonCancellable) { mutex.withLock { releaseRecognizers() } }
    }
}

internal interface NativeSpeechRecognizer : AutoCloseable {
    suspend fun recognize(pcm: ShortArray, ensureActive: suspend () -> Unit, samples: FloatArray? = null): String
}
