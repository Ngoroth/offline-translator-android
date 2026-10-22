package com.example.offlinetranslator.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class TextFirstConversationRecognitionEngineTest {
    private val pair = LanguagePair(LanguageId("nl"), LanguageId("en"))
    private val segment = SpeechSegment.fromPcm(shortArrayOf(1, 2))

    @Test fun closeAloneRevokesActiveNativeResultAndRejectsQueuedWorkBeforeStageUse() = runBlocking {
        withTimeout(5_000) {
            val transcriber = Transcriber().apply { gate = CompletableDeferred() }
            val identifier = Identifier()
            val engine = engine(transcriber, identifier)
            engine.prepare()
            val active = async { runCatching { engine.recognize(segment) } }
            transcriber.entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { engine.recognize(segment) } }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            try {
                assertFalse(closing.isCompleted)
                assertEquals(1, transcriber.calls.get())
                assertEquals(0, transcriber.closed.get())
                transcriber.gate!!.complete(Unit)
                assertTrue(active.await().exceptionOrNull() is IllegalStateException)
                assertTrue(queued.await().exceptionOrNull() is IllegalStateException)
                closing.await()
                assertEquals(1, transcriber.calls.get())
                assertEquals(0, identifier.calls.get())
                assertEquals(1, transcriber.closed.get())
            } finally {
                transcriber.gate!!.complete(Unit)
                active.await()
                queued.await()
                engine.closeAndJoin()
            }
        }
    }

    @Test fun cancellationDuringSecondFactoryReleasesBothReturnedResourcesInReverseOrder() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val transcriber = Transcriber().apply { onClose = { order += "transcriber" } }
            val identifier = Identifier().apply { onClose = { order += "identifier" } }
            val factoryThread = CompletableDeferred<Thread>()
            val callerThread = Thread.currentThread()
            val engine = TextFirstConversationRecognitionEngine(setOf(pair), { SpeechRecognitionReadiness.READY }, {}, {
                factoryThread.complete(Thread.currentThread())
                transcriber
            }, {
                entered.complete(Unit)
                finish.await()
                identifier
            })
            val preparing = async { engine.prepare() }
            try {
                entered.await()
                assertFalse(factoryThread.await() === callerThread)
                preparing.cancel()
                assertEquals(0, identifier.closed.get())
                finish.complete(Unit)
                preparing.join()
                assertTrue(preparing.isCancelled)
                assertEquals(listOf("identifier", "transcriber"), order)
                assertEquals(1, identifier.closed.get())
                assertEquals(1, transcriber.closed.get())
            } finally { finish.complete(Unit); preparing.cancelAndJoin(); engine.closeAndJoin() }
        }
    }

    @Test fun uncertaintyPreservesTextAndDirectionsAreRestrictedToSnapshot() = runBlocking {
        val transcriber = Transcriber()
        val identifier = Identifier()
        val selection = linkedSetOf(pair, pair.reverse())
        val engine = engine(transcriber, identifier, selection)
        selection.clear()
        try {
            engine.prepare()
            identifier.source = null
            assertEquals(UndeterminedLanguage(transcriber.text), engine.recognize(segment))
            identifier.source = pair.source
            assertEquals(RecognizedUtterance(pair, transcriber.text), engine.recognize(segment))
            identifier.source = pair.target
            assertEquals(RecognizedUtterance(pair.reverse(), transcriber.text), engine.recognize(segment))
            identifier.source = LanguageId("ru")
            assertTrue(runCatching { engine.recognize(segment) }.exceptionOrNull() is UnsupportedSpeechLanguageException)
            identifier.source = null
            transcriber.text = ""
            assertEquals(UndeterminedLanguage(""), engine.recognize(segment))
        } finally { engine.closeAndJoin() }
    }

    @Test fun readinessAndInstallNeverCreateStagesAndPrepareRequiresRealReadiness() = runBlocking {
        var state = SpeechRecognitionReadiness.UNSUPPORTED
        var installations = 0
        val creates = AtomicInteger()
        val engine = TextFirstConversationRecognitionEngine(setOf(pair), { state }, {
            installations++
            state = SpeechRecognitionReadiness.MISSING_LOCAL_MODEL
        }, { creates.incrementAndGet(); Transcriber() }, { creates.incrementAndGet(); Identifier() })
        try {
            assertEquals(SpeechRecognitionReadiness.UNSUPPORTED, engine.readiness())
            assertTrue(runCatching { engine.prepare() }.isFailure)
            engine.install()
            assertEquals(1, installations)
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, engine.readinessState.value)
            assertTrue(runCatching { engine.prepare() }.isFailure)
            assertEquals(0, creates.get())
        } finally { engine.closeAndJoin() }
    }

    @Test fun failedChecksAndInstallPublishDistinctFailureStates() = runBlocking {
        val failure = IOException("Download failed")
        val engine = TextFirstConversationRecognitionEngine(setOf(pair), { error("Digest failed") }, { throw failure },
            { error("No stage may be created") }, { error("No stage may be created") })
        try {
            assertEquals(SpeechRecognitionReadiness.CHECK_FAILED, engine.readiness())
            assertTrue(runCatching { engine.install() }.exceptionOrNull() is IOException)
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, engine.readinessState.value)
        } finally { engine.closeAndJoin() }
    }

    @Test fun partialCreationFailureReleasesPriorResourceAndPreservesCleanupFailure() = runBlocking {
        val transcriber = Transcriber()
        val creationFailure = IOException("Identifier unavailable")
        val cleanupFailure = IllegalStateException("Transcriber release failed")
        transcriber.closeFailure = cleanupFailure
        val engine = TextFirstConversationRecognitionEngine(setOf(pair), { SpeechRecognitionReadiness.READY }, {},
            { transcriber }, { throw creationFailure })
        try {
            assertTrue(runCatching { engine.prepare() }.exceptionOrNull() is IOException)
            assertTrue(creationFailure.suppressed.single() is IllegalStateException)
            assertEquals(1, transcriber.closed.get())
        } finally { engine.closeAndJoin() }
        assertEquals(1, transcriber.closed.get())
    }

    @Test fun cancellationDuringCreationRecordsThenClosesReturnedResourceWithoutStartingNextFactory() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val transcriber = Transcriber()
            val identifierCreates = AtomicInteger()
            val engine = TextFirstConversationRecognitionEngine(setOf(pair), { SpeechRecognitionReadiness.READY }, {}, {
                entered.complete(Unit)
                finish.await()
                transcriber
            }, { identifierCreates.incrementAndGet(); Identifier() })
            val preparing = async { engine.prepare() }
            try {
                entered.await()
                preparing.cancel()
                assertEquals(0, transcriber.closed.get())
                finish.complete(Unit)
                preparing.join()
                assertTrue(preparing.isCancelled)
                assertEquals(1, transcriber.closed.get())
                assertEquals(0, identifierCreates.get())
            } finally { finish.complete(Unit); preparing.cancelAndJoin(); engine.closeAndJoin() }
        }
    }

    @Test fun concurrentCloseDuringFactoryJoinsRealCreationAndDisposesExactlyOnce() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val transcriber = Transcriber()
            val identifierCreates = AtomicInteger()
            val engine = TextFirstConversationRecognitionEngine(setOf(pair), { SpeechRecognitionReadiness.READY }, {}, {
                entered.complete(Unit)
                finish.await()
                transcriber
            }, { identifierCreates.incrementAndGet(); Identifier() })
            val preparing = async { runCatching { engine.prepare() } }
            entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            val secondClose = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            try {
                assertFalse(closing.isCompleted)
                assertFalse(secondClose.isCompleted)
                assertEquals(0, transcriber.closed.get())
                finish.complete(Unit)
                assertTrue(preparing.await().isFailure)
                closing.await()
                secondClose.await()
                assertEquals(1, transcriber.closed.get())
                assertEquals(0, identifierCreates.get())
            } finally { finish.complete(Unit); preparing.await(); engine.closeAndJoin() }
        }
    }

    @Test fun cancelledNativeCallKeepsOwnershipUntilCompletionAndCannotStartTextIdentification() = runBlocking {
        withTimeout(5_000) {
            val transcriber = Transcriber()
            transcriber.gate = CompletableDeferred()
            val identifier = Identifier()
            val engine = engine(transcriber, identifier)
            engine.prepare()
            val recognition = async { engine.recognize(segment) }
            transcriber.entered.await()
            recognition.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            try {
                assertFalse(closing.isCompleted)
                assertEquals(0, transcriber.closed.get())
                assertEquals(0, identifier.closed.get())
                transcriber.gate!!.complete(Unit)
                recognition.join()
                closing.await()
                assertTrue(recognition.isCancelled)
                assertEquals(0, identifier.calls.get())
                assertEquals(1, transcriber.closed.get())
                assertEquals(1, identifier.closed.get())
            } finally { transcriber.gate!!.complete(Unit); recognition.cancelAndJoin(); engine.closeAndJoin() }
        }
    }

    @Test fun cancelledIdentifierKeepsBothResourcesAliveAndCannotPublishLateDirection() = runBlocking {
        withTimeout(5_000) {
            val transcriber = Transcriber()
            val identifier = Identifier().apply { gate = CompletableDeferred() }
            val engine = engine(transcriber, identifier)
            engine.prepare()
            val recognition = async { engine.recognize(segment) }
            identifier.entered.await()
            recognition.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            try {
                assertFalse(closing.isCompleted)
                assertEquals(0, identifier.closed.get())
                assertEquals(0, transcriber.closed.get())
                identifier.gate!!.complete(Unit)
                recognition.join()
                closing.await()
                assertTrue(recognition.isCancelled)
                assertEquals(1, identifier.closed.get())
                assertEquals(1, transcriber.closed.get())
            } finally { identifier.gate!!.complete(Unit); recognition.cancelAndJoin(); engine.closeAndJoin() }
        }
    }

    @Test fun repeatedPreparationAndConcurrentRecognitionNeverDuplicateOrOverlapStages() = runBlocking {
        withTimeout(5_000) {
            val creates = AtomicInteger()
            val transcriber = Transcriber().apply { gate = CompletableDeferred() }
            val identifier = Identifier()
            val engine = TextFirstConversationRecognitionEngine(setOf(pair, pair.reverse()), { SpeechRecognitionReadiness.READY }, {},
                { creates.incrementAndGet(); transcriber }, { creates.incrementAndGet(); identifier })
            val firstPrepare = async { engine.prepare() }
            val secondPrepare = async { engine.prepare() }
            firstPrepare.await()
            secondPrepare.await()
            val first = async { engine.recognize(segment) }
            transcriber.entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { engine.recognize(segment) }
            try {
                assertEquals(2, creates.get())
                assertFalse(second.isCompleted)
                assertEquals(1, transcriber.calls.get())
                transcriber.gate!!.complete(Unit)
                first.await()
                second.await()
                assertEquals(2, transcriber.calls.get())
            } finally { transcriber.gate!!.complete(Unit); first.cancelAndJoin(); second.cancelAndJoin(); engine.closeAndJoin() }
        }
    }

    @Test fun releaseAttemptsBothStagesInReverseOrderAndAllJoinersObserveSameFailure() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val first = IOException("Identifier close failed")
        val second = IllegalStateException("Transcriber close failed")
        val transcriber = Transcriber().apply { onClose = { order += "transcriber" }; closeFailure = second }
        val identifier = Identifier().apply { onClose = { order += "identifier" }; closeFailure = first }
        val engine = engine(transcriber, identifier)
        engine.prepare()
        val closing = async { runCatching { engine.closeAndJoin() } }
        val other = async { runCatching { engine.closeAndJoin() } }
        assertTrue(closing.await().exceptionOrNull() is IOException)
        assertTrue(other.await().exceptionOrNull() is IOException)
        assertTrue(first.suppressed.single() is IllegalStateException)
        assertEquals(listOf("identifier", "transcriber"), order)
        assertEquals(1, identifier.closed.get())
        assertEquals(1, transcriber.closed.get())
        assertTrue(runCatching { engine.recognize(segment) }.isFailure)
    }

    private fun engine(transcriber: Transcriber, identifier: Identifier, pairs: Set<LanguagePair> = setOf(pair, pair.reverse())) =
        TextFirstConversationRecognitionEngine(pairs, { SpeechRecognitionReadiness.READY }, {}, { transcriber }, { identifier })

    private class Transcriber : SpeechTranscriber {
        var text = "Waar is het station?"
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        var closeFailure: Throwable? = null
        var onClose: () -> Unit = {}
        override suspend fun transcribe(segment: SpeechSegment): String {
            check(closed.get() == 0)
            calls.incrementAndGet()
            entered.complete(Unit)
            gate?.await()
            check(closed.get() == 0)
            return text
        }
        override fun close() { closed.incrementAndGet(); onClose(); closeFailure?.let { throw it } }
    }

    private class Identifier : TextLanguageIdentifier {
        var source: LanguageId? = LanguageId("nl")
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        var closeFailure: Throwable? = null
        var onClose: () -> Unit = {}
        override suspend fun identify(text: String, pair: LanguagePair): LanguageId? {
            check(closed.get() == 0)
            calls.incrementAndGet()
            entered.complete(Unit)
            gate?.await()
            check(closed.get() == 0)
            return source
        }
        override fun close() { closed.incrementAndGet(); onClose(); closeFailure?.let { throw it } }
    }
}
