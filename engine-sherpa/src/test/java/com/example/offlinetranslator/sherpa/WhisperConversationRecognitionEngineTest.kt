package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.ConversationPipeline
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.UnsupportedSpeechLanguageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

class WhisperConversationRecognitionEngineTest {
    private val pair = LanguagePair(LanguageId("en"), LanguageId("nl"))
    private fun segment() = SpeechSegment.fromPcm(shortArrayOf(Short.MIN_VALUE, 0, Short.MAX_VALUE))

    @Test fun closeDuringPostInstallCheckCannotPublishLateReadiness(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = WhisperConversationRecognitionEngine(pair, {
            entered.complete(Unit)
            release.await()
            true
        }, {}, { error("Installation cannot construct JNI") })
        val installation = async(Dispatchers.Default) { runCatching { engine.install() } }
        try {
            entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            assertTrue(installation.await().isFailure)
            closing.await()
            assertNotEquals(SpeechRecognitionReadiness.READY, engine.readinessState.value)
        } finally { release.complete(Unit); installation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun closeAloneRevokesValidNativeResultWithoutFreeingAnActiveCall(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Recognizer()
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, { native })
        engine.prepare()
        native.result = NativeWhisperResult("Late but readable", "nl", "ok")
        native.block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val recognition = async(Dispatchers.Default) { runCatching { engine.recognize(segment()) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, native.closes.get())
            release.countDown()
            assertTrue(recognition.await().isFailure)
            closing.await()
            assertEquals(1, native.closes.get())
            assertFalse(native.releasedDuringCall)
        } finally { release.countDown(); recognition.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun alternatingDirectionsReuseOneWarmOwnerAndPreserveOriginalTextWithoutInferenceResourceChecks(): Unit = runBlocking {
        val native = Recognizer()
        var creates = 0
        var checks = 0
        val engine = WhisperConversationRecognitionEngine(pair, { checks++; true }, { error("Inference cannot install") }, {
            creates++; native
        })
        try {
            engine.prepare()
            engine.prepare()
            assertEquals(1, creates)
            assertEquals(1, native.calls)
            assertEquals(1, checks)
            native.result = NativeWhisperResult("  Go! Go!  ", "en", "ok")
            val first = engine.recognize(segment())
            assertEquals(pair, first.direction)
            assertEquals("  Go! Go!  ", first.text)
            assertArrayEquals(floatArrayOf(-1f, 0f, 32767f / 32768), native.samples, 0f)
            native.result = NativeWhisperResult("Kom hier!", "nl", "ok")
            assertEquals(pair.reverse(), engine.recognize(segment()).direction)
            native.result = NativeWhisperResult("Come here!", "en", "ok")
            assertEquals(pair, engine.recognize(segment()).direction)
            assertEquals(1, creates)
            assertEquals(1, checks)
        } finally { engine.closeAndJoin() }
        assertEquals(1, native.closes.get())
    }

    @Test fun missingFailedForeignAndBlankResultsNeverAcquireADirectionAndNextTurnStillWorks(): Unit = runBlocking {
        val native = Recognizer()
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, { native })
        try {
            engine.prepare()
            for (status in listOf("", "invalid-config", "decode-error", "OK")) {
                native.result = NativeWhisperResult("Readable text", "en", status)
                assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
            }
            for (language in listOf("ru", "", "EN", "en-US")) {
                native.result = NativeWhisperResult("Readable text", language, "ok")
                assertThrows(UnsupportedSpeechLanguageException::class.java) { runBlocking { engine.recognize(segment()) } }
            }
            for (text in listOf("", " \n\t")) {
                native.result = NativeWhisperResult(text, "en", "ok")
                assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
            }
            native.result = NativeWhisperResult("x".repeat(ConversationPipeline.MAX_TEXT_CHARACTERS + 1), "en", "ok")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.recognize(segment()) } }
            native.result = NativeWhisperResult("x".repeat(ConversationPipeline.MAX_TEXT_CHARACTERS), "nl", "ok")
            assertEquals(pair.reverse(), engine.recognize(segment()).direction)
            assertEquals(0, native.closes.get())
        } finally { engine.closeAndJoin() }
    }

    @Test fun failedWarmupDisposesUnpublishedOwnerAndRetryRequiresFreshSuccessfulHandshake(): Unit = runBlocking {
        for (invalid in listOf(
            NativeWhisperResult("", "en", ""),
            NativeWhisperResult("", "en", "decode-error"),
            NativeWhisperResult("", "ru", "ok"),
        )) {
            val rejected = Recognizer().also { it.result = invalid }
            val replacement = Recognizer()
            var creates = 0
            val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, {
                if (creates++ == 0) rejected else replacement
            })
            try {
                assertTrue(runCatching { engine.prepare() }.isFailure)
                assertEquals(1, rejected.closes.get())
                assertEquals(SpeechRecognitionReadiness.CHECK_FAILED, engine.readinessState.value)
                assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
                engine.prepare() // Empty warmup text is allowed, but status and pair language are real.
                replacement.result = NativeWhisperResult("Ready", "en", "ok")
                assertEquals(pair, engine.recognize(segment()).direction)
                assertEquals(2, creates)
            } finally { engine.closeAndJoin() }
            assertEquals(1, replacement.closes.get())
        }
    }

    @Test fun concurrentPreparationCannotCreateOverlappingOwnersOrWarmTwice(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Recognizer()
        val creates = AtomicInteger()
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, {
            creates.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            native
        })
        val first = async(Dispatchers.Default) { engine.prepare() }
        var second: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            second = async(start = CoroutineStart.UNDISPATCHED) { engine.prepare() }
            assertFalse(second.isCompleted)
            assertEquals(1, creates.get())
            release.countDown()
            first.await()
            second.await()
            assertEquals(1, creates.get())
            assertEquals(1, native.calls)
        } finally {
            release.countDown()
            first.cancelAndJoin()
            second?.cancelAndJoin()
            engine.closeAndJoin()
        }
    }

    @Test fun closeDuringConstructionWaitsForUnpublishedOwnerAndPreventsWarmup(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Recognizer()
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            native
        })
        val preparation = async(Dispatchers.Default) { runCatching { engine.prepare() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, native.closes.get())
            release.countDown()
            assertTrue(preparation.await().isFailure)
            closing.await()
            assertEquals(1, native.closes.get())
            assertEquals(0, native.calls)
        } finally { release.countDown(); preparation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun cancellationDuringSuspendingCreationStillDisposesReturnedOwnerBeforeRetry(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejected = Recognizer()
        val replacement = Recognizer()
        var creates = 0
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, {
            if (creates++ == 0) {
                entered.complete(Unit)
                release.await()
                rejected
            } else replacement
        })
        val preparation = async(Dispatchers.Default) { engine.prepare() }
        try {
            entered.await()
            preparation.cancel()
            assertEquals(0, rejected.closes.get())
            release.complete(Unit)
            preparation.cancelAndJoin()
            assertEquals(1, rejected.closes.get())
            assertEquals(0, rejected.calls)
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
            engine.prepare()
            replacement.result = NativeWhisperResult("Recovered", "nl", "ok")
            assertEquals(pair.reverse(), engine.recognize(segment()).direction)
        } finally { release.complete(Unit); preparation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun cancellationDuringWarmupCannotPublishOrReleaseBeforeActualReturn(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Recognizer().also { it.block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, { native })
        val preparation = async(Dispatchers.Default) { engine.prepare() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            preparation.cancel()
            assertEquals(0, native.closes.get())
            release.countDown()
            preparation.cancelAndJoin()
            assertEquals(1, native.closes.get())
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
        } finally { release.countDown(); preparation.cancelAndJoin(); engine.closeAndJoin() }
    }

    @Test fun cancelledActiveDecodeKeepsLeaseUntilReturnAndQueuedTurnCannotPublishAfterClose(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val native = Recognizer()
        val engine = WhisperConversationRecognitionEngine(pair, { true }, {}, { native })
        engine.prepare()
        native.result = NativeWhisperResult("Late result", "en", "ok")
        native.block = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val recognition = async(Dispatchers.Default) { engine.recognize(segment()) }
        var queued: kotlinx.coroutines.Deferred<Result<*>>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.recognize(segment()) } }
            recognition.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, native.closes.get())
            release.countDown()
            assertTrue(runCatching { recognition.await() }.exceptionOrNull() is CancellationException)
            assertTrue(queued.await().isFailure)
            closing.await()
            assertEquals(2, native.calls) // warmup plus the active decode; the queued turn never ran.
            assertEquals(1, native.closes.get())
            assertFalse(native.releasedDuringCall)
        } finally {
            release.countDown()
            recognition.cancelAndJoin()
            queued?.cancelAndJoin()
            engine.closeAndJoin()
        }
    }

    @Test fun missingDependenciesAndFailedFactoryCannotLeavePreparedStateAndExplicitInstallCanRecover(): Unit = runBlocking {
        var present = false
        var failCreation = true
        var creates = 0
        val native = Recognizer()
        val engine = WhisperConversationRecognitionEngine(pair, { present }, { present = true }, {
            creates++
            if (failCreation) error("Construction failed") else native
        })
        try {
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, engine.readiness())
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.prepare() } }
            assertEquals(0, creates)
            engine.install()
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.prepare() } }
            assertThrows(IllegalStateException::class.java) { runBlocking { engine.recognize(segment()) } }
            failCreation = false
            engine.prepare()
            native.result = NativeWhisperResult("Recovered", "en", "ok")
            assertEquals(pair, engine.recognize(segment()).direction)
            assertEquals(2, creates)
        } finally { engine.closeAndJoin() }
    }

    private class Recognizer : NativeWhisperRecognizer {
        var result = NativeWhisperResult("", "en", "ok")
        var calls = 0
        var samples: FloatArray? = null
        var block: (() -> Unit)? = null
        val closes = AtomicInteger()
        private var active = false
        var releasedDuringCall = false
        override suspend fun recognize(samples: FloatArray, ensureActive: suspend () -> Unit): NativeWhisperResult {
            ensureActive()
            calls++
            this.samples = samples
            active = true
            return try {
                block?.invoke()
                result
            } finally { active = false }
        }
        override fun close() {
            if (active) releasedDuringCall = true
            closes.incrementAndGet()
        }
    }
}
