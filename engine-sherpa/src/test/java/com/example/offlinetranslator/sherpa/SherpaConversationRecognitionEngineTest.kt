package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.UnsupportedSpeechLanguageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SherpaConversationRecognitionEngineTest {
    private val first = LanguageId("en")
    private val second = LanguageId("de")
    private val forward = LanguagePair(first, second)
    private val pairs = setOf(forward, forward.reverse())

    @Test fun selectedLanguagesRouteToTheirActualRecognizerAndReuseOnePcmConversion(): Unit = runBlocking {
        val identifier = Identifier()
        val natives = pairs.associate { it.source to Recognizer(it.source.value) }
        val created = mutableListOf<LanguageId>()
        val engine = SherpaConversationRecognitionEngine(pairs, { true }, { error("Inference must not install") }, { identifier }, { language ->
            created += language
            natives.getValue(language)
        })
        try {
            engine.prepare()
            engine.prepare()
            assertEquals(setOf(first, second), created.toSet())
            assertEquals(2, created.size)
            assertTrue(natives.values.all { it.calls == 1 }) // each selected ASR actually warmed
            identifier.result = "de"
            val utterance = engine.recognize(SpeechSegment.fromPcm(shortArrayOf(1, 2)))
            assertEquals(forward.reverse(), utterance.direction)
            assertEquals("de", utterance.text)
            assertEquals(1, natives.getValue(first).calls)
            assertEquals(2, natives.getValue(second).calls)
            assertSame(identifier.samples, natives.getValue(second).samples)
            identifier.result = "en"
            assertEquals(forward, engine.recognize(SpeechSegment.fromPcm(shortArrayOf(3))).direction)
            assertEquals(2, natives.getValue(first).calls)
            identifier.result = "ru"
            assertThrows(UnsupportedSpeechLanguageException::class.java) {
                runBlocking { engine.recognize(SpeechSegment.fromPcm(shortArrayOf(3))) }
            }
            assertEquals(2, natives.getValue(first).calls)
            assertEquals(2, natives.getValue(second).calls)
        } finally { engine.closeAndJoin() }
        assertEquals(1, identifier.closes.get())
        assertTrue(natives.values.all { it.closes.get() == 1 })
    }

    @Test fun missingSelectedDependencyNeverCreatesAnyNativeOwner(): Unit = runBlocking {
        val creates = AtomicInteger()
        val engine = SherpaConversationRecognitionEngine(pairs, { false }, { error("Prepare must not install") }, {
            creates.incrementAndGet(); Identifier()
        }, { creates.incrementAndGet(); Recognizer("text") })
        assertThrows(IllegalStateException::class.java) { runBlocking { engine.prepare() } }
        assertEquals(0, creates.get())
        engine.closeAndJoin()
    }

    @Test fun closeDuringCreationWaitsForAndDisposesUnpublishedNative(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identifier = Identifier()
        val asrCreates = AtomicInteger()
        val engine = SherpaConversationRecognitionEngine(pairs, { true }, { error("Prepare must not install") }, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            identifier
        }, { asrCreates.incrementAndGet(); Recognizer("text") })
        val preparation = async(Dispatchers.Default) { runCatching { engine.prepare() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, identifier.closes.get())
            release.countDown()
            assertTrue(preparation.await().isFailure)
            closing.await()
            assertEquals(1, identifier.closes.get())
            assertEquals(0, asrCreates.get())
        } finally { release.countDown(); preparation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun cancellationAndCloseCannotReleaseAnActiveRecognitionLease(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identifier = Identifier()
        val active = Recognizer("selected")
        val engine = SherpaConversationRecognitionEngine(pairs, { true }, { error("Inference must not install") }, { identifier }, { language ->
            if (language == first) active else Recognizer("other")
        })
        engine.prepare()
        active.block = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val recognition = async(Dispatchers.Default) { engine.recognize(SpeechSegment.fromPcm(shortArrayOf(1))) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            recognition.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, active.closes.get())
            assertEquals(0, identifier.closes.get())
            release.countDown()
            assertTrue(runCatching { recognition.await() }.exceptionOrNull() is CancellationException)
            closing.await()
            assertEquals(1, active.closes.get())
            assertEquals(1, identifier.closes.get())
        } finally { release.countDown(); recognition.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun cancellationDuringSelectedWarmupCannotPublishPreparedState(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val identifier = Identifier()
        val firstNative = Recognizer("selected")
        firstNative.block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val engine = SherpaConversationRecognitionEngine(pairs, { true }, { error("Prepare must not install") }, { identifier }, { firstNative })
        val preparation = async(Dispatchers.Default) { engine.prepare() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            preparation.cancel()
            release.countDown()
            preparation.cancelAndJoin()
            assertThrows(IllegalStateException::class.java) {
                runBlocking { engine.recognize(SpeechSegment.fromPcm(shortArrayOf(1))) }
            }
        } finally { release.countDown(); preparation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun readinessAndExplicitInstallUseDependenciesWithoutConstructingNatives() = runBlocking {
        var present = false
        var installations = 0
        val engine = SherpaConversationRecognitionEngine(pairs, { present }, {
            installations++
            present = true
        }, { error("Readiness must not construct JNI") }, { error("Readiness must not construct JNI") })
        try {
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, engine.readiness())
            assertEquals(0, installations)
            engine.install()
            assertEquals(1, installations)
            assertEquals(SpeechRecognitionReadiness.READY, engine.readinessState.value)
            present = false
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, engine.readiness())
            assertEquals(1, installations)
        } finally { engine.closeAndJoin() }
    }

    @Test fun failedCheckAndFailedDownloadPublishDistinctStates() = runBlocking {
        val engine = SherpaConversationRecognitionEngine(pairs, { error("Digest failed") },
            { error("Download failed") }, { error("Unexpected JNI") }, { error("Unexpected JNI") })
        try {
            assertEquals(SpeechRecognitionReadiness.CHECK_FAILED, engine.readiness())
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.install() } }
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, engine.readinessState.value)
        } finally { engine.closeAndJoin() }
    }

    private class Identifier : NativeLanguageIdentifier {
        var result = "en"
        var samples: FloatArray? = null
        val closes = AtomicInteger()
        override suspend fun identify(samples: FloatArray, ensureActive: suspend () -> Unit): String {
            ensureActive()
            this.samples = samples
            return result
        }
        override fun close() { closes.incrementAndGet() }
    }

    private class Recognizer(private val text: String) : NativeSpeechRecognizer {
        var calls = 0
        var samples: FloatArray? = null
        var block: (() -> Unit)? = null
        val closes = AtomicInteger()
        override suspend fun recognize(pcm: ShortArray, ensureActive: suspend () -> Unit, samples: FloatArray?): String {
            ensureActive()
            calls++
            this.samples = samples
            block?.invoke()
            ensureActive()
            return text
        }
        override fun close() { closes.incrementAndGet() }
    }
}
