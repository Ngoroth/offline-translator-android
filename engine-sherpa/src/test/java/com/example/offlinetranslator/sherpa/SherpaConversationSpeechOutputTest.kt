package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.SpeechPlaybackStart
import com.example.offlinetranslator.core.SpeechOutputReadiness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SherpaConversationSpeechOutputTest {
    @Test fun preparationRetainsBothVoicesAndRepeatedLanguageTurnsReuseTheirNativeOwner() = runBlocking<Unit> {
        val harness = OutputPlaybackHarness()
        val voices = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).associateWith { TestConversationVoice() }
        val creates = AtomicInteger()
        val output = SherpaConversationSpeechOutput({ language ->
            creates.incrementAndGet()
            voices.getValue(language)
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        val starts = CopyOnWriteArrayList<SpeechPlaybackStart>()
        try {
            output.prepare()
            output.prepare()
            output.speak(LanguageCatalog.RUSSIAN, "Первый перевод", starts::add)
            output.speak(LanguageCatalog.RUSSIAN, "Второй перевод", starts::add)
            output.speak(LanguageCatalog.ENGLISH, "The third translation", starts::add)
            assertEquals(2, creates.get())
            assertEquals(2, voices.getValue(LanguageCatalog.RUSSIAN).generateCalls.get())
            assertEquals(1, voices.getValue(LanguageCatalog.ENGLISH).generateCalls.get())
            assertEquals(3, starts.size)
            assertTrue(starts.none { it.measured })
            assertTrue(harness.tracks.all { it.releaseCalls.get() == 1 })
            assertTrue(harness.focuses.all { it.abandonCalls.get() == 1 })
            assertTrue(voices.values.all { it.closeCalls.get() == 0 })
        } finally {
            output.close()
            output.close()
        }
        assertTrue(voices.values.all { it.closeCalls.get() == 1 })
        assertThrows(IllegalStateException::class.java) { runBlocking { output.prepare() } }
    }

    @Test fun rejectedVoiceIsClosedAndRetryDoesNotRecreateTheOtherPreparedVoice() = runBlocking {
        val firstLanguage = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).first()
        val retained = TestConversationVoice()
        val rejected = TestConversationVoice(sampleRate = 16_000)
        val repaired = TestConversationVoice()
        val secondCreates = AtomicInteger()
        val harness = OutputPlaybackHarness()
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == firstLanguage) retained
            else if (secondCreates.incrementAndGet() == 1) rejected else repaired
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalStateException::class.java) { runBlocking { output.prepare() } }
            assertEquals(1, rejected.closeCalls.get())
            assertEquals(0, retained.closeCalls.get())
            assertTrue(harness.tracks.isEmpty())
            output.prepare()
            assertEquals(2, secondCreates.get())
            assertEquals(0, repaired.closeCalls.get())
        } finally {
            output.close()
        }
        assertEquals(1, retained.closeCalls.get())
        assertEquals(1, repaired.closeCalls.get())
        assertEquals(1, rejected.closeCalls.get())
    }

    @Test fun callbackHasTheBoxedJniSignatureAndNeverThrowsInvalidPcmBackIntoNativeCode() = runBlocking {
        val harness = OutputPlaybackHarness()
        val codes = mutableListOf<Int>()
        val attempts = AtomicInteger()
        val voice = TestConversationVoice(generator = { _, callback ->
            assertEquals(
                java.lang.Integer::class.java,
                callback.javaClass.getDeclaredMethod("invoke", FloatArray::class.java).returnType,
            )
            if (attempts.incrementAndGet() == 1) {
                codes += callback(floatArrayOf(1f, Float.NaN))
                codes += callback(floatArrayOf(1f))
                ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 3)
            } else {
                val samples = floatArrayOf(0f, .5f)
                assertEquals(1, callback(samples))
                ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, samples.size)
            }
        })
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
            }
            assertEquals(listOf(0, 0), codes)
            assertEquals(0, harness.tracks.single().playCalls.get())
            assertEquals(1, harness.tracks.single().releaseCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            output.speak(LanguageCatalog.ENGLISH, "Another translation", {})
            assertEquals(2, voice.generateCalls.get())
        } finally {
            output.close()
        }
    }

    @Test fun aggregateNativeCallbackQuotaStopsFurtherWritesAndSafelyReturnsZero() = runBlocking {
        val harness = OutputPlaybackHarness()
        val codes = mutableListOf<Int>()
        val voice = TestConversationVoice(generator = { _, callback ->
            codes += callback(floatArrayOf(.5f))
            codes += callback(FloatArray(SpeechPcmBuffer.MAXIMUM_FRAMES) { .5f })
            codes += callback(floatArrayOf(.5f))
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, SpeechPcmBuffer.MAXIMUM_FRAMES + 2)
        })
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
            }
            assertEquals(listOf(1, 0, 0), codes)
            assertEquals(1, harness.tracks.single().writtenFrames.get())
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
        } finally {
            output.close()
        }
    }

    @Test fun nativeSampleRateAndMissingCallbackSamplesArePlaybackFailures() = runBlocking {
        for (result in listOf(
            ConversationVoiceResult(16_000, 1),
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 2),
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 0),
        )) {
            val harness = OutputPlaybackHarness()
            val voice = TestConversationVoice(generator = { _, callback ->
                assertEquals(1, callback(floatArrayOf(.5f)))
                result
            })
            val output = SherpaConversationSpeechOutput({ language ->
                if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
            }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
            try {
                assertThrows(IllegalStateException::class.java) {
                    runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
                }
                assertEquals(1, harness.tracks.single().releaseCalls.get())
                assertEquals(1, harness.focuses.single().abandonCalls.get())
            } finally {
                output.close()
            }
        }
    }

    @Test fun deniedFocusNeverEntersNativeGenerationAndDoesNotPoisonTheNextTurn() = runBlocking {
        val requests = AtomicInteger()
        val harness = OutputPlaybackHarness { focus ->
            if (requests.incrementAndGet() == 1) focus.granted = false
        }
        val voices = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).associateWith { TestConversationVoice() }
        val output = SherpaConversationSpeechOutput({ language -> voices.getValue(language) }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
            }
            assertEquals(0, voices.getValue(LanguageCatalog.ENGLISH).generateCalls.get())
            assertEquals(0, harness.tracks.single().playCalls.get())
            assertEquals(1, harness.tracks.single().releaseCalls.get())
            output.speak(LanguageCatalog.ENGLISH, "Another translation", {})
            assertEquals(1, voices.getValue(LanguageCatalog.ENGLISH).generateCalls.get())
            assertEquals(2, harness.tracks.size)
            assertTrue(harness.focuses.all { it.abandonCalls.get() == 1 })
        } finally {
            output.close()
        }
    }

    @Test fun focusLossStopsAudioAndCancelsTheJniCallbackWithoutReleasingWarmVoices() = runBlocking {
        val harness = OutputPlaybackHarness()
        val codes = mutableListOf<Int>()
        val voice = TestConversationVoice(generator = { _, callback ->
            codes += callback(floatArrayOf(.5f))
            harness.focuses.single().lose()
            codes += callback(floatArrayOf(.5f))
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 2)
        })
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalStateException::class.java) {
                runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
            }
            assertEquals(listOf(1, 0), codes)
            assertEquals(1, harness.tracks.single().writtenFrames.get())
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            assertEquals(0, voice.closeCalls.get())
        } finally {
            output.close()
        }
        assertEquals(1, voice.closeCalls.get())
    }

    @Test fun closeSynchronouslyStopsAudioButReleasesVoicesOnlyAfterActiveJniReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = OutputPlaybackHarness()
        val cancelledCallback = AtomicInteger(-1)
        val voice = TestConversationVoice(generator = { _, callback ->
            assertEquals(1, callback(floatArrayOf(.5f)))
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            cancelledCallback.set(callback(floatArrayOf(.5f)))
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 2)
        })
        val otherVoice = TestConversationVoice()
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else otherVoice
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        output.prepare()
        val speaking = async(Dispatchers.Default) {
            runCatching { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            harness.tracks.single().failPause = true
            output.close()
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            assertEquals(0, voice.closeCalls.get())
            assertEquals(0, otherVoice.closeCalls.get())
            release.countDown()
            assertTrue(speaking.await().exceptionOrNull() is CancellationException)
            assertEquals(0, cancelledCallback.get())
            assertEquals(1, voice.closeCalls.get())
            assertEquals(1, otherVoice.closeCalls.get())
            assertEquals(1, harness.tracks.single().playCalls.get())
        } finally {
            release.countDown()
            output.close()
            speaking.cancelAndJoin()
        }
    }

    @Test fun interruptionInvalidatesRequestsAlreadyWaitingForSerializationButAllowsANewTurn() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val harness = OutputPlaybackHarness()
        val voice = TestConversationVoice(generator = { _, callback ->
            assertEquals(1, callback(floatArrayOf(.5f)))
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            val code = callback(floatArrayOf(.5f))
            assertTrue(code == 0 || code == 1)
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 2)
        })
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        output.prepare()
        val first = async(Dispatchers.Default) {
            runCatching { output.speak(LanguageCatalog.ENGLISH, "First translation", {}) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { output.speak(LanguageCatalog.ENGLISH, "Queued translation", {}) }
            }
            output.interruptCurrent()
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            release.countDown()
            assertTrue(first.await().exceptionOrNull() is CancellationException)
            assertTrue(queued.await().exceptionOrNull() is CancellationException)
            assertEquals(1, voice.generateCalls.get())
            assertEquals(1, harness.tracks.size)
            assertEquals(0, voice.closeCalls.get())
            output.speak(LanguageCatalog.ENGLISH, "New translation", {})
            assertEquals(2, voice.generateCalls.get())
            assertEquals(2, harness.tracks.size)
        } finally {
            release.countDown()
            output.close()
            first.cancelAndJoin()
        }
    }

    @Test fun revokeDuringTrackCreationCannotAcquireFocusOrEnterNativeGenerationAfterward() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val track = TestSpeechAudioTrack()
        val focusRequests = AtomicInteger()
        val focus = object : SpeechAudioFocus {
            override fun request(onLoss: () -> Unit): Boolean {
                focusRequests.incrementAndGet()
                return true
            }
            override fun abandon() = Unit
        }
        val playback = AndroidSpeechPlayback({
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            track
        }, { focus })
        val voice = TestConversationVoice()
        val output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        output.prepare()
        val speaking = async(Dispatchers.Default) {
            runCatching { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            output.interruptCurrent()
            release.countDown()
            assertTrue(speaking.await().exceptionOrNull() is CancellationException)
            assertEquals(0, focusRequests.get())
            assertEquals(0, voice.generateCalls.get())
            assertEquals(0, track.playCalls.get())
            assertEquals(1, track.releaseCalls.get())
        } finally {
            release.countDown()
            output.close()
            speaking.cancelAndJoin()
        }
    }

    @Test fun closeDuringNativeVoiceCreationClosesTheReturnedVoiceWithoutPublishingIt() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val voice = TestConversationVoice()
        val harness = OutputPlaybackHarness()
        val creates = AtomicInteger()
        val output = SherpaConversationSpeechOutput({
            creates.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            voice
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        val preparing = async(Dispatchers.Default) { runCatching { output.prepare() } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            output.close()
            assertEquals(0, voice.closeCalls.get())
            release.countDown()
            assertTrue(preparing.await().exceptionOrNull() is CancellationException)
            assertEquals(1, voice.closeCalls.get())
            assertEquals(1, creates.get())
            assertTrue(harness.tracks.isEmpty())
        } finally {
            release.countDown()
            output.close()
            preparing.cancelAndJoin()
        }
    }

    @Test fun playbackStartCallbackCanSynchronouslyStopItsOwnerWithoutDeadlockOrSuccessfulCompletion() = runBlocking {
        val harness = OutputPlaybackHarness()
        val voices = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).associateWith { TestConversationVoice() }
        val output = SherpaConversationSpeechOutput({ language -> voices.getValue(language) }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        val starts = AtomicInteger()
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    output.speak(LanguageCatalog.ENGLISH, "Translation") {
                        starts.incrementAndGet()
                        output.interruptCurrent()
                    }
                }
            }
            assertEquals(1, starts.get())
            assertEquals(1, harness.tracks.single().playCalls.get())
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.tracks.single().releaseCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            assertTrue(voices.values.all { it.closeCalls.get() == 0 })
        } finally {
            output.close()
        }
    }

    @Test fun interruptWithFailedPauseRetainsActualReleaseFaultAfterCancelledSynthesis() = runBlocking {
        val harness = OutputPlaybackHarness()
        lateinit var output: SherpaConversationSpeechOutput
        val voice = TestConversationVoice(generator = { _, callback ->
            assertEquals(1, callback(floatArrayOf(.5f)))
            harness.tracks.single().failPause = true
            output.interruptCurrent()
            assertEquals(1, harness.tracks.single().releaseCalls.get())
            assertEquals(0, callback(floatArrayOf(.5f)))
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 2)
        })
        output = SherpaConversationSpeechOutput({ language ->
            if (language == LanguageCatalog.ENGLISH) voice else TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(CancellationException::class.java) {
                runBlocking { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
            }
            assertEquals(1, harness.tracks.single().playCalls.get())
            assertEquals(1, harness.tracks.single().flushCalls.get())
            assertEquals(1, harness.focuses.single().abandonCalls.get())
            assertEquals(0, voice.closeCalls.get())
            val retained = assertThrows(IllegalStateException::class.java) { output.throwIfReleaseFailed() }
            assertSame(retained, assertThrows(IllegalStateException::class.java) { output.throwIfReleaseFailed() })
            val closeFailure = runCatching { output.closeAndJoin() }.exceptionOrNull()
            assertTrue(generateSequence(closeFailure) { it.cause }.any { it === retained })
        } finally {
            runCatching { output.closeAndJoin() }
        }
        assertEquals(1, voice.closeCalls.get())
    }

    @Test fun emptyUnpronounceableAndOverQuotaTextCannotOpenAudioOrCreateNativeOwners() {
        val harness = OutputPlaybackHarness()
        val creates = AtomicInteger()
        val output = SherpaConversationSpeechOutput({
            creates.incrementAndGet()
            TestConversationVoice()
        }, harness.playback, listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), { true })
        try {
            for (text in listOf("", " \n ", "...!?", "a".repeat(SherpaConversationSpeechOutput.MAXIMUM_TEXT_LENGTH + 1))) {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { output.speak(LanguageCatalog.ENGLISH, text, {}) }
                }
            }
            assertEquals(0, creates.get())
            assertTrue(harness.tracks.isEmpty())
            assertFalse(harness.focuses.any())
        } finally {
            output.close()
        }
    }
    @Test fun onlySelectedVoicesPrepareAndUnselectedSpeechRejectsBeforeEffects() = runBlocking {
        val harness = OutputPlaybackHarness()
        val created = mutableListOf<com.example.offlinetranslator.core.LanguageId>()
        val voice = TestConversationVoice()
        val output = SherpaConversationSpeechOutput({ language ->
            created += language
            voice
        }, harness.playback, listOf(LanguageCatalog.ENGLISH), { true })
        try {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { output.speak(LanguageCatalog.GERMAN, "Guten Tag.", {}) }
            }
            assertTrue(created.isEmpty())
            assertTrue(harness.tracks.isEmpty())
            output.prepare()
            output.speak(LanguageCatalog.ENGLISH, "Good afternoon.", {})
            assertEquals(listOf(LanguageCatalog.ENGLISH), created)
            assertEquals(1, voice.generateCalls.get())
        } finally { output.closeAndJoin() }
        assertEquals(1, voice.closeCalls.get())
    }

    @Test fun closeAndJoinBlocksNewOwnerUntilSynchronousNativeGenerationReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val voice = TestConversationVoice(generator = { _, callback ->
            assertEquals(1, callback(floatArrayOf(.5f)))
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, 1)
        })
        val harness = OutputPlaybackHarness()
        val output = SherpaConversationSpeechOutput({ voice }, harness.playback, listOf(LanguageCatalog.ENGLISH), { true })
        val speaking = async(Dispatchers.Default) {
            runCatching { output.speak(LanguageCatalog.ENGLISH, "Translation", {}) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { output.closeAndJoin() }
            assertFalse(closing.isCompleted)
            assertEquals(0, voice.closeCalls.get())
            assertEquals(1, harness.tracks.single().pauseCalls.get())
            release.countDown()
            assertTrue(speaking.await().exceptionOrNull() is CancellationException)
            closing.await()
            assertEquals(1, voice.closeCalls.get())
        } finally { release.countDown(); speaking.cancelAndJoin(); output.closeAndJoin() }
    }

    @Test fun readinessRechecksLocalResourcesWithoutCreatingNativeVoicesOrAudio() = runBlocking {
        val harness = OutputPlaybackHarness()
        var installed = false
        val output = SherpaConversationSpeechOutput(
            { error("Readiness must not create a native voice") },
            harness.playback, listOf(LanguageCatalog.ENGLISH), { installed },
        )
        try {
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, output.readiness())
            installed = true
            assertEquals(SpeechOutputReadiness.READY, output.readiness())
            installed = false
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, output.readiness())
            assertTrue(harness.tracks.isEmpty())
            assertTrue(harness.focuses.isEmpty())
        } finally { output.closeAndJoin() }
    }

}

private class OutputPlaybackHarness(private val configureFocus: (TestSpeechAudioFocus) -> Unit = {}) {
    private val clock = TestPlaybackClock()
    val tracks = CopyOnWriteArrayList<TestSpeechAudioTrack>()
    val focuses = CopyOnWriteArrayList<TestSpeechAudioFocus>()
    val playback = AndroidSpeechPlayback(
        createTrack = {
            TestSpeechAudioTrack(clock::now).apply { advanceOnWrite = true }.also { tracks.add(it) }
        },
        createFocus = { TestSpeechAudioFocus().apply(configureFocus).also { focuses.add(it) } },
        nanoTime = clock::now,
        sleepMillis = clock::advanceMillis,
    )
}

private class TestConversationVoice(
    override val sampleRate: Int = SpeechPcmBuffer.SAMPLE_RATE,
    private val generator: (String, (FloatArray) -> Int) -> ConversationVoiceResult = { _, callback ->
        val samples = floatArrayOf(0f, .5f, -.5f)
        assertEquals(1, callback(samples))
        ConversationVoiceResult(SpeechPcmBuffer.SAMPLE_RATE, samples.size)
    },
) : ConversationVoice {
    val generateCalls = AtomicInteger()
    val closeCalls = AtomicInteger()
    override fun generate(text: String, callback: ((FloatArray) -> Int)?): ConversationVoiceResult {
        generateCalls.incrementAndGet()
        return generator(text, callback ?: { 1 })
    }
    override fun close() { closeCalls.incrementAndGet() }
}
