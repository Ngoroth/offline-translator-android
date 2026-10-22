package com.example.offlinetranslator.mlkit

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.SpeechTranscriber
import com.example.offlinetranslator.core.TextFirstConversationRecognitionEngine
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MlKitTextLanguageIdentifierTest {
    private val pair = LanguagePair(LanguageId("nl"), LanguageId("en"))

    @Test fun rawScoreAndGlobalGapBoundariesAreInclusiveWithoutPairRenormalization() {
        assertEquals(pair.source, selected("nl" to 0.50, "de" to 0.30))
        assertNull(selected("nl" to Math.nextDown(0.50), "de" to 0.10))
        assertNull(selected("nl" to 0.50, "de" to Math.nextUp(0.30)))
        assertEquals(pair.source, selected("nl" to 0.50, "de" to Math.nextDown(0.30)))
        assertNull(selected("nl" to 0.49, "en" to 0.01))
        assertEquals(pair.target, selected("nl" to 0.05, "en" to 0.90, "ru" to 0.04))
    }

    @Test fun globalForeignWinnerAndForeignRunnerUpBothPreventForcedPairChoice() {
        assertNull(selected("nl" to 0.60, "en" to 0.05, "ru" to 0.90))
        assertNull(selected("nl" to 0.60, "en" to 0.01, "ru" to 0.45))
        assertNull(selected("und" to 0.95, "nl" to 0.02))
        assertNull(selected("ru-Latn" to 0.95, "en" to 0.02))
        val russian = LanguagePair(LanguageId("ru"), pair.target)
        assertNull(selectTextLanguage(listOf(Score("ru-Latn", 0.95)), russian, { it.tag }, { it.score }))
        assertNull(selected("NL" to 0.95))
    }

    @Test fun candidateOrderDoesNotAffectGlobalWinnerAndTiesAbstain() {
        val scores = listOf(Score("de", 0.10), Score("en", 0.70), Score("nl", 0.15))
        for (ordered in listOf(scores, scores.reversed(), listOf(scores[1], scores[0], scores[2]))) {
            assertEquals(pair.target, selectTextLanguage(ordered, pair, { it.tag }, { it.score }))
        }
        assertNull(selected("nl" to 0.75, "en" to 0.75))
        assertNull(selected())
        assertEquals(pair.source, selected("nl" to 0.90))
    }

    @Test fun nonFiniteScoresCannotAuthorizeLanguageOrHideAGlobalCompetitor() {
        for (score in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(selected("nl" to score, "en" to 0.01))
            assertNull(selected("nl" to 0.95, "ru" to score))
        }
    }

    @Test fun unicodeLetterGuardRejectsNoReadableWordsButAcceptsSupplementaryLetters() {
        for (text in listOf("", " \t", "123,45", "!?!", "\uD83D\uDE00", "\u0301")) assertFalse(hasUnicodeLetter(text))
        for (text in listOf("Waar?", "Привет", "駅", "\uD801\uDC00")) assertTrue(hasUnicodeLetter(text))
    }

    @Test fun noLetterInputAbstainsWithoutStartingSdkTask() = runBlocking {
        val client = Client()
        val identifier = MlKitTextLanguageIdentifier(client)
        try {
            assertNull(identifier.identify("123!?", pair))
            assertEquals(0, client.calls.get())
        } finally { identifier.close() }
    }

    @Test fun cancellationWaitsForActualGoogleTaskAndOwnerCloseCannotDisposeEarly() = runBlocking {
        withTimeout(5_000) {
            val client = Client()
            val identifier = MlKitTextLanguageIdentifier(client)
            val transcriberClosed = AtomicInteger()
            val owner = TextFirstConversationRecognitionEngine(setOf(pair, pair.reverse()),
                { SpeechRecognitionReadiness.READY }, {}, {
                    object : SpeechTranscriber {
                        override suspend fun transcribe(segment: SpeechSegment) = "Waar is het station?"
                        override fun close() { transcriberClosed.incrementAndGet() }
                    }
                }, { identifier })
            owner.prepare()
            val recognition = async { owner.recognize(SpeechSegment.fromPcm(shortArrayOf(1))) }
            client.started.await()
            recognition.cancel()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.closeAndJoin() }
            try {
                assertFalse(closing.isCompleted)
                assertEquals(0, client.closed.get())
                assertEquals(0, transcriberClosed.get())
                client.completion.setResult(emptyList())
                recognition.join()
                closing.await()
                assertTrue(recognition.isCancelled)
                assertEquals(1, client.closed.get())
                assertEquals(1, transcriberClosed.get())
            } finally {
                client.completion.trySetResult(emptyList())
                recognition.cancelAndJoin()
                owner.closeAndJoin()
            }
        }
    }

    @Test fun directCancelledWaitDoesNotFinishBeforeActualTaskSettlement() = runBlocking {
        withTimeout(5_000) {
            val client = Client()
            val identifier = MlKitTextLanguageIdentifier(client)
            val identifying = async { identifier.identify("Waar?", pair) }
            client.started.await()
            identifying.cancel()
            try {
                assertFalse(identifying.isCompleted)
                client.completion.setResult(emptyList())
                identifying.join()
                assertTrue(identifying.isCancelled)
            } finally {
                client.completion.trySetResult(emptyList())
                identifying.cancelAndJoin()
                identifier.close()
            }
        }
    }

    @Test fun failedGoogleTaskRemainsFailureInsteadOfExpectedPolicyAbstention() = runBlocking {
        withTimeout(5_000) {
            val client = Client()
            val identifier = MlKitTextLanguageIdentifier(client)
            val failure = IllegalStateException("SDK unavailable")
            val identifying = async { runCatching { identifier.identify("Waar?", pair) } }
            try {
                client.started.await()
                client.completion.setException(failure)
                assertTrue(identifying.await().isFailure)
            } finally {
                client.completion.trySetResult(emptyList())
                identifying.cancelAndJoin()
                identifier.close()
            }
        }
    }

    private data class Score(val tag: String, val score: Double)
    private fun selected(vararg scores: Pair<String, Double>): LanguageId? =
        selectTextLanguage(scores.map { Score(it.first, it.second) }, pair, { it.tag }, { it.score })

    private class Client : TextLanguageIdentificationClient {
        val completion = TaskCompletionSource<List<IdentifiedLanguage>>()
        val started = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val closed = AtomicInteger()
        override fun identify(text: String): PendingOperation<List<IdentifiedLanguage>> {
            check(closed.get() == 0)
            calls.incrementAndGet()
            started.complete(Unit)
            return GoogleTaskOperation(completion.task)
        }
        override fun close() { closed.incrementAndGet() }
    }
}
