package com.example.offlinetranslator.speech

import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidConversationRecognitionEngineTest {
    @Test fun uniqueTrueConfidenceOverridesOppositeWeakTailWithoutInitialOrPreviousLanguage() = runTest {
        for (initial in listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)) {
            val f = Fixture(this, initial)
            try {
                f.ready()
                val recognized = async { f.engine.recognize(segment()) }
                runCurrent()
                val request = f.platform.sessions.last()
                request.listener.language("en-US", 1, 0)
                request.listener.segment("Включите таймер.")
                assertFalse(recognized.isCompleted)
                request.listener.language("ru-RU", 3, 0)
                repeat(2) { request.listener.language("en-US", 1, 1) }
                request.listener.terminal()
                assertEquals(LanguageCatalog.RUSSIAN, recognized.await().direction.source)
                val next = async { f.engine.recognize(segment()) }
                runCurrent()
                val fresh = f.platform.sessions.last()
                fresh.listener.language("en-US", 2, 0)
                fresh.listener.segment("Turn right.")
                request.listener.language("ru-RU", 3, 1)
                fresh.listener.terminal()
                val value = next.await()
                assertEquals(LanguageCatalog.ENGLISH, value.direction.source)
                assertEquals("Turn right.", value.text)
                assertNotSame(request, fresh)
                assertTrue(f.platform.sessions.all { it.destroyed && it.cancelled })
            } finally { f.engine.closeAndJoin() }
        }
    }
    @Test fun terminalSourceRequiresBothWeakHistogramLeaderAndMatchingRunOrUniqueTrueConfidence() = runTest {
        data class Observation(val tag: String?, val confidence: Int?, val switch: Int? = 0)
        val en = Observation("en-US", 1)
        val ru = Observation("ru-RU", 1)
        val cases = listOf(
            listOf(en, Observation("en-GB", 1)) to LanguageCatalog.ENGLISH,
            listOf(en, ru, ru) to LanguageCatalog.RUSSIAN,
            listOf(en, ru, ru, en, en) to LanguageCatalog.ENGLISH,
            List(500) { en } + List(501) { ru } to LanguageCatalog.RUSSIAN,
            listOf(en) to null,
            emptyList<Observation>() to null,
            listOf(Observation(null, null, null)) to null,
            listOf(Observation("en-US", 0), Observation("en-US", 0)) to null,
            listOf(en, en, ru, ru) to null,
            listOf(en, en, en, ru, ru) to null,
            listOf(en, en, ru, en) to null,
            List(11) { en } + List(15) { ru } + List(3) { en } to null,
            listOf(en, en, Observation(null, null, null)) to null,
            listOf(en, en, Observation("en-US", 0)) to null,
            listOf(en, en, Observation("en-US", null)) to null,
            listOf(en, en, Observation(null, 1)) to null,
            listOf(en, en, Observation(null, null, null), en) to null,
            listOf(en, en, Observation(null, null, null), en, en) to LanguageCatalog.ENGLISH,
            listOf(en, en, Observation("en-US", 0), en, en) to LanguageCatalog.ENGLISH,
            listOf(en, en, Observation("en-US", null), en, en) to LanguageCatalog.ENGLISH,
            listOf(Observation(null, 0), en, en) to LanguageCatalog.ENGLISH,
            listOf(en, Observation("fr-FR", 0), en, en) to null,
            listOf(en, Observation("not a tag", 0), en, en) to null,
            listOf(en, Observation("", 1), en, en) to null,
            listOf(Observation("ru-RU", 1, 1)) to null,
            listOf(en, Observation("ru-RU", 1, 1)) to null,
            listOf(Observation("en-US", 1, 1), Observation("ru-RU", 1, 1)) to null,
            listOf(Observation("en-US", 1, 1), Observation("en-GB", 1, 1)) to LanguageCatalog.ENGLISH,
            listOf(en, Observation("ru-RU", 1, 1), Observation("ru-RU", 1, 1)) to LanguageCatalog.RUSSIAN,
            listOf(Observation("en-US", 2), ru, ru) to LanguageCatalog.ENGLISH,
            listOf(Observation("ru-RU", 3), en, en, en) to LanguageCatalog.RUSSIAN,
            listOf(Observation("en-US", 2), Observation("en-GB", 3)) to LanguageCatalog.ENGLISH,
            listOf(Observation("fr-FR", 1), Observation("en-US", 2)) to LanguageCatalog.ENGLISH,
            listOf(Observation("not a tag", 1), Observation("en-US", 2)) to LanguageCatalog.ENGLISH,
            listOf(Observation("en-US", 2), Observation(null, 0)) to LanguageCatalog.ENGLISH,
            listOf(Observation("en-US", 3), Observation("ru-RU", 2), en, en) to null,
            listOf(Observation("en-US", 3), Observation("fr-FR", 2)) to null,
            listOf(Observation("not a tag", 2), Observation("en-US", 3)) to null,
            listOf(Observation(null, 2, 1), Observation("en-US", 3)) to null,
            listOf(Observation("en-US", 2, 2)) to null,
            listOf(Observation("en-US", 2, 3)) to null,
            listOf(Observation(null, null, 2), en, en) to null,
            listOf(Observation("en-US", 99), en, en) to null,
            listOf(Observation("en-US", -1), en, en) to null,
            listOf(Observation("en-US", 1, 99), en, en) to null,
        )
        for (initial in listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)) {
            val f = Fixture(this, initial)
            try {
                f.ready()
                for ((caseIndex, case) in cases.withIndex()) {
                    val (observations, expected) = case
                    val outcome = async { runCatching { f.engine.recognize(segment()) } }
                    runCurrent()
                    val request = f.platform.sessions.last()
                    // Deliberately unrelated script/reference; metadata after final still decides source.
                    request.listener.segment("Unrelated primary text.")
                    observations.forEach { request.listener.language(it.tag, it.confidence, it.switch) }
                    request.listener.terminal()
                    request.listener.language("outside", 3, 3)
                    val result = outcome.await()
                    assertEquals("case=$caseIndex initial=$initial", expected != null, result.isSuccess)
                    if (expected != null) {
                        assertEquals(expected, result.getOrThrow().direction.source)
                        assertEquals("Unrelated primary text.", result.getOrThrow().text)
                    }
                }
            } finally { f.engine.closeAndJoin() }
        }
    }
    @Test fun orderedPrimarySegmentsWaitForTerminalAndBoundTheWholeText() = runTest {
        val f = Fixture(this)
        try {
            f.ready()
            val good = async { f.engine.recognize(segment()) }
            runCurrent()
            val session = f.platform.sessions.last()
            repeat(3) { session.listener.language("en-US", 1, 0) }
            session.listener.segment(null)
            session.listener.segment("")
            session.listener.segment(" \t ")
            session.listener.segment("First.")
            session.listener.segment(null)
            session.listener.segment(" ")
            session.listener.segment("Second.")
            session.listener.segment("")
            session.listener.segment("\t")
            session.listener.results("Whole result must not be appended.")
            assertFalse(good.isCompleted)
            session.listener.terminal()
            assertEquals("First. Second.", good.await().text)
            for (parts in listOf(listOf<String?>(null), listOf(""), listOf(" \t "), listOf(null, "", "\t"),
                listOf("x".repeat(4097)), listOf("x".repeat(4096), "", "y"))) {
                val bad = async { runCatching { f.engine.recognize(segment()) } }
                runCurrent()
                val next = f.platform.sessions.last()
                next.listener.language("en-US", 3, 0)
                parts.forEach(next.listener::segment)
                next.listener.terminal()
                assertTrue(bad.await().isFailure)
            }
            val limit = async { f.engine.recognize(segment()) }
            runCurrent()
            val next = f.platform.sessions.last()
            next.listener.language("ru-RU", 2, 0)
            next.listener.segment("x".repeat(4096))
            next.listener.terminal()
            assertEquals(4096, limit.await().text.length)
            assertTrue(f.platform.audio.all { it.closed })
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun checksAndInferenceNeverDownloadScheduledRequiresExplicitRecheck() = runTest {
        val f = Fixture(this)
        try {
            f.platform.support = AndroidRecognitionSupport(listOf("ru-RU"), emptyList(), listOf("en-US"), listOf("fr-FR"))
            assertEquals(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL, f.engine.readiness())
            assertTrue(runCatching { f.engine.prepare() }.isFailure)
            assertTrue(runCatching { f.engine.recognize(segment()) }.isFailure)
            assertEquals(0, f.platform.downloads)
            f.platform.downloadAction = { it.scheduled() }
            f.engine.install()
            assertEquals(SpeechRecognitionReadiness.SCHEDULED, f.engine.readinessState.value)
            assertEquals(1, f.platform.downloads)
            f.platform.support = readySupport()
            assertEquals(SpeechRecognitionReadiness.SCHEDULED, f.engine.readinessState.value)
            assertEquals(SpeechRecognitionReadiness.READY, f.engine.readiness())
            assertEquals(1, f.platform.downloads)
            assertTrue(f.platform.requests.all { it.toSet() == setOf("ru-RU", "en-US") && it.size == 2 })
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun successfulSelectedDownloadIsNotReadyUntilBothActuallyInstalledAndPermissionIsCheckedEachTurn() = runTest {
        val f = Fixture(this)
        try {
            f.platform.support = AndroidRecognitionSupport(emptyList(), emptyList(), listOf("ru-RU", "en-US"), emptyList())
            f.platform.downloadAction = {
                it.progress()
                assertEquals(SpeechRecognitionReadiness.DOWNLOADING, f.engine.readinessState.value)
                if (f.platform.downloads == 2) f.platform.support = readySupport()
                it.success()
                it.error(2) // Terminal download listener cannot publish a late failure.
            }
            f.engine.install()
            assertEquals(SpeechRecognitionReadiness.READY, f.engine.readinessState.value)
            assertNull(f.engine.errorCode)
            assertEquals(2, f.platform.downloads)
            f.platform.permission = false
            val leases = f.platform.sessions.size
            assertTrue(runCatching { f.engine.recognize(segment()) }.isFailure)
            assertEquals(leases, f.platform.sessions.size)
            f.platform.permission = true
            val next = async { f.engine.recognize(segment()) }
            runCurrent()
            val request = f.platform.sessions.last()
            request.listener.language("en-US", 2, 0)
            request.listener.segment("Hello.")
            request.listener.terminal()
            assertEquals("Hello.", next.await().text)
            assertEquals(2, f.platform.downloads)
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun onlineOnlyUnsupportedFailedAndUnavailableAreNotReadyOrDownloaded() = runTest {
        val f = Fixture(this)
        try {
            f.platform.support = AndroidRecognitionSupport(emptyList(), emptyList(), emptyList(), listOf("ru-RU", "en-US"))
            assertEquals(SpeechRecognitionReadiness.UNSUPPORTED, f.engine.readiness())
            f.engine.install()
            assertEquals(0, f.platform.downloads)
            f.platform.available = false
            assertEquals(SpeechRecognitionReadiness.ENGINE_UNAVAILABLE, f.engine.readiness())
            f.platform.available = true
            f.platform.supportError = 5
            assertEquals(SpeechRecognitionReadiness.CHECK_FAILED, f.engine.readiness())
            f.platform.supportError = null
            f.platform.support = AndroidRecognitionSupport(emptyList(), emptyList(), listOf("ru-RU", "en-US"), emptyList())
            f.platform.downloadAction = { it.error(2) }
            f.engine.install()
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, f.engine.readinessState.value)
            assertEquals(2, f.engine.errorCode)
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun deadlineAndCancellationCloseUnblockWriterAndRejectLateCallbacksBeforeNewRequest() = runTest {
        val f = Fixture(this)
        try {
            f.ready()
            f.platform.blockWriter = true
            val timeout = async { runCatching { f.engine.recognize(segment()) } }
            runCurrent()
            val old = f.platform.sessions.last()
            advanceTimeBy(AndroidConversationRecognitionEngine.REQUEST_TIMEOUT_MILLIS)
            runCurrent()
            val deadlineFailure = timeout.await().exceptionOrNull()
            assertNotNull(deadlineFailure)
            assertFalse(deadlineFailure is CancellationException)
            assertTrue(old.destroyed && old.cancelled)
            assertTrue(f.platform.audio.last().closed && f.platform.audio.last().finished)
            old.listener.language("ru-RU", 3, 1)
            old.listener.segment("Late text.")
            old.listener.terminal()
            f.platform.blockWriter = false
            val next = async { f.engine.recognize(segment()) }
            runCurrent()
            val fresh = f.platform.sessions.last()
            fresh.listener.language("en-US", 2, 0)
            fresh.listener.segment("Fresh.")
            fresh.listener.terminal()
            assertEquals("Fresh.", next.await().text)
            f.platform.blockWriter = true
            val cancelled = async { f.engine.recognize(segment()) }
            runCurrent()
            val last = f.platform.sessions.last()
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            assertTrue(last.destroyed && last.cancelled)
            assertTrue(f.platform.audio.last().finished)
            val parentTimeout = async { runCatching { withTimeout(5) { f.engine.recognize(segment()) } } }
            runCurrent()
            val parentRequest = f.platform.sessions.last()
            advanceTimeBy(5)
            runCurrent()
            assertTrue(parentTimeout.await().exceptionOrNull() is TimeoutCancellationException)
            assertTrue(parentRequest.destroyed && parentRequest.cancelled)
            assertTrue(f.platform.audio.last().finished)
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun supportTimeoutAndLateCreatedRecognizerStillReleaseTheirLeases() = runTest {
        val f = Fixture(this)
        try {
            f.platform.ignoreSupport = true
            val support = async { f.engine.readiness() }
            runCurrent()
            advanceTimeBy(AndroidConversationRecognitionEngine.CHECK_TIMEOUT_MILLIS)
            runCurrent()
            assertEquals(SpeechRecognitionReadiness.CHECK_FAILED, support.await())
            assertTrue(f.platform.sessions.single().destroyed)
            f.platform.ignoreSupport = false
            f.ready()
            val pending = async(start = kotlinx.coroutines.CoroutineStart.LAZY) { f.engine.recognize(segment()) }
            // The external factory returns its newly allocated SDK lease only after cancellation.
            f.platform.onCreate = { pending.cancel() }
            pending.start()
            runCurrent()
            f.engine.closeAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(f.platform.sessions.all { it.destroyed })
            assertTrue(f.platform.audio.all { it.closed })
        } finally { f.engine.closeAndJoin() }
    }

    @Test fun callerDeadlineDuringSupportDoesNotContinueCallerAndReleasesTheRequest() = runTest {
        val f = Fixture(this)
        try {
            f.platform.ignoreSupport = true
            var continued = false
            val caller = async {
                runCatching { withTimeout(5) { f.engine.readiness(); continued = true } }.exceptionOrNull()
            }
            runCurrent()
            advanceTimeBy(5)
            runCurrent()
            assertTrue(caller.await() is TimeoutCancellationException)
            assertFalse(continued)
            assertTrue(f.platform.sessions.single().cancelled && f.platform.sessions.single().destroyed)
            assertTrue(f.platform.audio.single().closed)
            assertEquals(0, f.platform.downloads)
            f.platform.ignoreSupport = false
            f.ready()
        } finally { f.engine.closeAndJoin() }
    }

    @Test fun callerDeadlineDuringExplicitDownloadPropagatesAndLateSuccessDoesNotPublishReady() = runTest {
        val f = Fixture(this)
        try {
            f.platform.support = readySupport().copy(installed = listOf("en-US"))
            var download: AndroidModelDownloadListener? = null
            f.platform.downloadAction = { download = it; it.progress() }
            var continued = false
            val caller = async {
                runCatching { withTimeout(5) { f.engine.install(); continued = true } }.exceptionOrNull()
            }
            runCurrent()
            assertNotNull(download)
            advanceTimeBy(5)
            runCurrent()
            assertTrue(caller.await() is TimeoutCancellationException)
            assertFalse(continued)
            assertTrue(f.platform.sessions.all { it.cancelled && it.destroyed })
            assertTrue(f.platform.audio.all { it.closed })
            download!!.success()
            assertEquals(SpeechRecognitionReadiness.DOWNLOADING, f.engine.readinessState.value)
            assertEquals(1, f.platform.downloads)
            f.platform.support = readySupport()
            f.ready()
            assertEquals(1, f.platform.downloads)
        } finally { f.engine.closeAndJoin() }
    }
    @Test fun exactPlatformTagsAndChineseScriptBoundaryAreExplicit() {
        val tags = linkedMapOf("en" to "en-US", "vi" to "vi-VN", "es" to "es-ES", "id" to "id-ID", "it" to "it-IT", "zh" to "cmn-Hans-CN", "ko" to "ko-KR", "de" to "de-DE", "pl" to "pl-PL", "pt" to "pt-BR", "ru" to "ru-RU", "th" to "th-TH", "tr" to "tr-TR", "fr" to "fr-FR", "hi" to "hi-IN", "ja" to "ja-JP")
        for ((code, tag) in tags) {
            val language = LanguageId(code)
            assertEquals(tag, AndroidSpeechLocales.recognition(language))
            assertEquals(if (code == "zh") "zh-Hans-CN" else tag, AndroidSpeechLocales.output(language).toLanguageTag())
            assertEquals(language, AndroidSpeechLocales.source(tag))
        }
        for (tag in listOf("zh-Hant-CN", "zh-TW", "cmn-Hant-TW", "zh-HK", "zh-MO", "zh", "cmn", "invalid input")) assertNull(tag, AndroidSpeechLocales.source(tag))
        for (tag in listOf("zh-CN", "cmn-CN", "zh-Hans-CN", "cmn-Hans-CN")) assertEquals(LanguageId("zh"), AndroidSpeechLocales.source(tag))
    }

    private class Fixture(scope: TestScope, initial: LanguageId = LanguageCatalog.RUSSIAN) {
        val platform = Platform()
        private val config = AppSettings(languagePair = LanguagePair(initial, if (initial == LanguageCatalog.RUSSIAN) LanguageCatalog.ENGLISH else LanguageCatalog.RUSSIAN)).conversationConfig()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val engine = AndroidConversationRecognitionEngine(config, platform, dispatcher, dispatcher)
        suspend fun ready() { assertEquals(SpeechRecognitionReadiness.READY, engine.readiness()) }
    }
    private class Platform : AndroidSpeechRecognizerClient {
        var available = true
        var permission = true
        var support = readySupport()
        var supportError: Int? = null
        var ignoreSupport = false
        var downloads = 0
        var blockWriter = false
        var onCreate: (() -> Unit)? = null
        var downloadAction: (AndroidModelDownloadListener) -> Unit = { it.success() }
        val sessions = mutableListOf<Session>()
        val audio = mutableListOf<Audio>()
        val requests = mutableListOf<List<String>>()
        override fun available() = available
        override fun permissionGranted() = permission
        override suspend fun create(listener: AndroidRecognitionListener): AndroidRecognitionSession = Session(this, listener).also { sessions += it; onCreate?.invoke() }
        override fun openAudio(): AndroidRecognitionAudio = Audio(blockWriter).also { audio += it }
    }
    private class Session(val platform: Platform, val listener: AndroidRecognitionListener) : AndroidRecognitionSession {
        var destroyed = false
        var cancelled = false
        override fun support(languages: List<String>, audio: AndroidRecognitionAudio, callback: (AndroidRecognitionSupport?, Int?) -> Unit) {
            platform.requests += languages
            if (!platform.ignoreSupport) callback(if (platform.supportError == null) platform.support else null, platform.supportError)
        }
        override fun download(languages: List<String>, audio: AndroidRecognitionAudio, listener: AndroidModelDownloadListener) {
            platform.requests += languages
            platform.downloads++
            platform.downloadAction(listener)
        }
        override fun start(languages: List<String>, audio: AndroidRecognitionAudio) { platform.requests += languages }
        override fun cancel() { cancelled = true }
        override suspend fun destroy() { destroyed = true }
    }
    private class Audio(val blocked: Boolean) : AndroidRecognitionAudio {
        var closed = false
        var finished = false
        val released = CompletableDeferred<Unit>()
        override suspend fun write(pcm: ShortArray) {
            try { if (blocked) withContext(NonCancellable) { released.await() } }
            finally { finished = true }
        }
        override fun close() { closed = true; released.complete(Unit) }
    }
    companion object {
        private fun readySupport() = AndroidRecognitionSupport(listOf("ru-RU", "en-US"), emptyList(), listOf("ru-RU", "en-US"), emptyList())
        private fun segment() = SpeechSegment.fromPcm(shortArrayOf(100, -100, 200, -200))
    }
}
