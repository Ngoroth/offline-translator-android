package com.example.offlinetranslator.speech

import android.speech.tts.TextToSpeech
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.core.SpeechPlaybackStart
import androidx.lifecycle.viewModelScope
import com.example.offlinetranslator.conversation.ConversationViewModel
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.ConversationModelRepository
import com.example.offlinetranslator.core.ConversationPhase
import com.example.offlinetranslator.core.ConversationRecognitionEngine
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.RecognizedUtterance
import com.example.offlinetranslator.core.SpeechCaptureEngine
import com.example.offlinetranslator.core.SpeechCaptureListener
import com.example.offlinetranslator.core.SpeechModelStatus
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TranslationPreparationState
import com.example.offlinetranslator.core.TranslationPreparationPhase
import com.example.offlinetranslator.runtime.ConversationRuntimeOwner
import com.example.offlinetranslator.runtime.RuntimeOwnerController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SystemConversationSpeechOutputTest {
    @Test fun everyCatalogLocaleRequiresInstalledOfflineDataAndExactActivation() = runTest {
        for (metadata in LanguageCatalog.entries) {
            val language = metadata.id
            val f = Fixture(this, selectedLanguages = listOf(language))
            val local = voice("local-${language.value}", AndroidSpeechLocales.output(language))
            f.client.voices = listOf(local)
            f.client.defaultVoice = local
            try {
                assertEquals(language.value, SpeechOutputReadiness.READY, f.output.readiness())
                val speaking = async { f.output.speak(language, metadata.warmUpText, {}) }
                runCurrent()
                assertEquals(local, f.client.speechVoices.single())
                f.client.listener.onDone(f.client.ids.single(), ENGINE_UID)
                speaking.await()
                f.client.voices = listOf(local.copy(networkRequired = true))
                assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
                f.client.voices = listOf(local.copy(notInstalled = true))
                assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
                f.client.voices = listOf(local)
                f.client.data = TextToSpeech.LANG_MISSING_DATA
                assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
                f.client.data = TextToSpeech.LANG_COUNTRY_AVAILABLE
                f.client.mismatch = true
                assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
                f.client.mismatch = false
                assertEquals(SpeechOutputReadiness.READY, f.output.recheck())
                f.client.voices = emptyList()
                assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
                assertEquals(1, f.client.ids.size)
            } finally { f.output.closeAndJoin() }
        }
    }

    @Test fun chineseOnlyAcceptsInstalledMainlandMandarinSimplifiedVoices() = runTest {
        val f = Fixture(this, selectedLanguages = listOf(LanguageCatalog.CHINESE))
        try {
            for (tag in listOf("zh-Hans-CN", "zh-CN", "cmn-Hans-CN", "cmn-CN")) {
                val local = voice("local-$tag", Locale.forLanguageTag(tag))
                f.client.voices = listOf(local)
                f.client.defaultVoice = local
                assertEquals(tag, SpeechOutputReadiness.READY, f.output.recheck())
                val speech = async { f.output.speak(LanguageCatalog.CHINESE, "我们需要两张车票。", {}) }
                runCurrent()
                assertEquals(local, f.client.speechVoices.last())
                f.client.listener.onDone(f.client.ids.last(), ENGINE_UID)
                speech.await()
            }
            for (tag in listOf("zh-Hant-CN", "cmn-Hant-CN", "zh-TW", "cmn-TW", "zh-Hans-TW",
                "zh-HK", "cmn-HK", "zh-MO", "cmn-MO", "zh-Hans", "zh", "yue-Hans-CN")) {
                val rejected = voice("rejected-$tag", Locale.forLanguageTag(tag))
                f.client.voices = listOf(rejected)
                f.client.defaultVoice = rejected
                assertEquals(tag, SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
            }
            assertEquals(4, f.client.ids.size)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun installedFixedLocaleWinsOverDeviceLikeAlternativeAndNetworkDefault() = runTest {
        val f = Fixture(this)
        val canadian = voice("a-canadian", Locale.CANADA)
        val fixed = voice("z-us", Locale.US)
        f.client.voices = listOf(canadian, fixed, voice("cloud", Locale.US, network = true), f.russian)
        f.client.defaultVoice = canadian
        try {
            assertEquals(SpeechOutputReadiness.READY, f.output.readiness())
            val speech = async { f.output.speak(LanguageCatalog.ENGLISH, "The train leaves at five.", {}) }
            runCurrent()
            assertEquals(fixed, f.client.speechVoices.single())
            f.client.listener.onDone(f.client.ids.single(), ENGINE_UID)
            speech.await()
            f.client.defaultVoice = fixed
            f.client.voices = listOf(fixed, voice("a-us", Locale.US), f.russian)
            val next = async { f.output.speak(LanguageCatalog.ENGLISH, "Two tickets.", {}) }
            runCurrent()
            assertEquals(fixed, f.client.speechVoices.last())
            f.client.listener.onDone(f.client.ids.last(), ENGINE_UID)
            next.await()
            f.client.voices = listOf(voice("z-canadian", Locale.CANADA), canadian, f.russian)
            val alternate = async { f.output.speak(LanguageCatalog.ENGLISH, "Tomorrow.", {}) }
            runCurrent()
            assertEquals(canadian, f.client.speechVoices.last())
            f.client.listener.onDone(f.client.ids.last(), ENGINE_UID)
            alternate.await()
        } finally { f.output.closeAndJoin() }
    }

    @Test fun advertisedButUnavailableVoicesCannotPrepareAndExplicitRecheckCanBecomeReady() = runTest {
        val f = Fixture(this)
        try {
            f.client.voices = listOf(f.english.copy(networkRequired = true), f.russian)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            f.client.voices = listOf(f.english.copy(notInstalled = true), f.russian)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            f.client.voices = listOf(voice("french", Locale.FRANCE), f.russian)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            f.client.voices = listOf(f.english, f.russian)
            f.client.data = TextToSpeech.LANG_MISSING_DATA
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            assertTrue(runCatching { f.output.prepare() }.isFailure)
            f.client.data = TextToSpeech.LANG_COUNTRY_AVAILABLE
            f.client.voiceResult = TextToSpeech.ERROR_NOT_INSTALLED_YET
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            f.client.voiceResult = TextToSpeech.SUCCESS
            f.client.mismatch = true
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.recheck())
            f.client.mismatch = false
            assertEquals(SpeechOutputReadiness.READY, f.output.recheck())
            assertEquals(1, f.creations)
            assertTrue(f.client.ids.isEmpty())
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun initializationFailureAndTimeoutNeverImplicitlyRecreateTheEngine() = runTest {
        val failed = Fixture(this, initStatus = TextToSpeech.ERROR)
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, failed.output.readiness())
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, failed.output.readiness())
        assertNull(failed.output.enginePackage)
        assertEquals(1, failed.creations)
        failed.output.closeAndJoin()
        val absent = Fixture(this, initStatus = TextToSpeech.ERROR)
        absent.client.engineAvailable = false
        assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, absent.output.readiness())
        absent.output.closeAndJoin()
        val timeout = Fixture(this, initStatus = null)
        val ready = async { timeout.output.readiness() }
        runCurrent()
        advanceTimeBy(SystemConversationSpeechOutput.INIT_TIMEOUT_MILLIS)
        runCurrent()
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, ready.await())
        assertEquals(1, timeout.client.shutdowns)
        timeout.init(TextToSpeech.SUCCESS)
        assertEquals(2, timeout.client.shutdowns)
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, timeout.output.readiness())
        assertEquals(1, timeout.creations)
        timeout.output.closeAndJoin()
    }

    @Test fun closeDuringInitializationReleasesConstructionAndLateSuccessCannotReviveIt() = runTest {
        val f = Fixture(this, initStatus = null)
        val readiness = async { runCatching { f.output.readiness() } }
        runCurrent()
        assertEquals(1, f.creations)
        f.output.closeAndJoin()
        readiness.await()
        assertEquals(1, f.client.shutdowns)
        f.init(TextToSpeech.SUCCESS)
        assertEquals(2, f.client.shutdowns)
        assertNull(f.output.enginePackage)
        assertTrue(runCatching { f.output.readiness() }.isFailure)
        assertEquals(1, f.creations)
    }

    @Test fun matchingStartIsPhaseOnlyOnceAndOldCallbacksCannotFinishASuccessor() = runTest {
        val f = Fixture(this)
        val starts = mutableListOf<SpeechPlaybackStart>()
        try {
            val first = async { f.output.speak(LanguageCatalog.ENGLISH, "Two tickets tomorrow.", starts::add) }
            runCurrent()
            val oldId = f.client.ids.single()
            f.client.listener.onStart("unowned", ENGINE_UID)
            f.client.listener.onStart(oldId, ENGINE_UID)
            f.client.listener.onStart(oldId, ENGINE_UID)
            assertEquals(listOf(SpeechPlaybackStart(null, false)), starts)
            f.client.listener.onDone(oldId, ENGINE_UID)
            first.await()
            val second = async { f.output.speak(LanguageCatalog.RUSSIAN, "Два билета завтра.", starts::add) }
            runCurrent()
            val nextId = f.client.ids.last()
            assertNotEquals(oldId, nextId)
            f.client.listener.onDone(oldId, ENGINE_UID)
            f.client.listener.onError(oldId, TextToSpeech.ERROR)
            f.client.listener.onStop(oldId)
            assertFalse(second.isCompleted)
            f.client.listener.onStart(nextId, ENGINE_UID)
            f.client.listener.onDone(nextId, ENGINE_UID)
            second.await()
            assertEquals(2, starts.size)
            assertTrue(starts.all { it.presentationTimeNanos == null && !it.measured })
            assertEquals(2, f.focus.abandons)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun interruptSynchronouslyStopsAndInvalidatesAlreadyWaitingRequests() = runTest {
        val f = Fixture(this)
        try {
            val first = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "First request.", {}) } }
            runCurrent()
            val queued = async { runCatching { f.output.speak(LanguageCatalog.RUSSIAN, "Второй запрос.", {}) } }
            runCurrent()
            f.output.interruptCurrent()
            assertEquals(1, f.client.stops)
            assertEquals(1, f.focus.abandons)
            runCurrent()
            assertTrue(first.await().exceptionOrNull() is CancellationException)
            assertTrue(queued.await().exceptionOrNull() is CancellationException)
            assertEquals(1, f.client.ids.size)
            val next = async { f.output.speak(LanguageCatalog.ENGLISH, "New request.", {}) }
            runCurrent()
            f.client.listener.onDone(f.client.ids.last(), ENGINE_UID)
            next.await()
        } finally { f.output.closeAndJoin() }
    }

    @Test fun reentrantStartStopCannotReportSuccessfulCompletionOrIssueAnotherStart() = runTest {
        val f = Fixture(this)
        var starts = 0
        try {
            val speaking = async { runCatching {
                f.output.speak(LanguageCatalog.ENGLISH, "Interrupted translation.") {
                    starts++
                    f.output.interruptCurrent()
                }
            } }
            runCurrent()
            val id = f.client.ids.single()
            f.client.listener.onStart(id, ENGINE_UID)
            f.client.listener.onStart(id, ENGINE_UID)
            f.client.listener.onDone(id, ENGINE_UID)
            runCurrent()
            assertTrue(speaking.await().exceptionOrNull() is CancellationException)
            assertEquals(1, starts)
            assertEquals(1, f.client.stops)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun nativeErrorAndStopBeforeStartReleaseWaitersWithoutFabricatingStart() = runTest {
        val f = Fixture(this)
        try {
            var starts = 0
            val failed = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Error fixture.") { starts++ } } }
            runCurrent()
            f.client.listener.onError(f.client.ids.last(), TextToSpeech.ERROR_NOT_INSTALLED_YET)
            runCurrent()
            assertTrue(failed.await().exceptionOrNull() is IllegalStateException)
            assertEquals(1, f.client.stops)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            assertEquals(SpeechOutputReadiness.READY, f.output.recheck())
            val stopped = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Stop fixture.") { starts++ } } }
            runCurrent()
            f.client.listener.onStop(f.client.ids.last())
            runCurrent()
            assertTrue(stopped.await().exceptionOrNull() is CancellationException)
            assertEquals(0, starts)
            assertEquals(2, f.focus.abandons)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun focusRefusalLossAndCallerCancellationStopWithoutRestarting() = runTest {
        val f = Fixture(this)
        try {
            f.focus.granted = false
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Denied fixture.", {}) }.isFailure)
            assertTrue(f.client.ids.isEmpty())
            f.focus.granted = true
            val lost = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Focus fixture.", {}) } }
            runCurrent()
            f.focus.lose()
            assertEquals(2, f.client.stops)
            runCurrent()
            assertTrue(lost.await().exceptionOrNull() is IllegalStateException)
            val cancelled = async { f.output.speak(LanguageCatalog.ENGLISH, "Cancelled fixture.", {}) }
            runCurrent()
            cancelled.cancelAndJoin()
            assertEquals(3, f.client.stops)
            assertEquals(3, f.focus.abandons)
            assertEquals(2, f.client.ids.size)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun enqueueAndQuotaErrorsNeverBecomeSuccessfulSpeech() = runTest {
        val f = Fixture(this)
        try {
            f.client.maximumInputLength = 12
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Longer than twelve characters.", {}) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, f.focus.requests)
            f.client.maximumInputLength = 4096
            f.client.speakResult = TextToSpeech.ERROR_NOT_INSTALLED_YET
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Local data error.", {}) }.isFailure)
            assertEquals(1, f.client.stops)
            assertEquals(1, f.focus.abandons)
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            f.client.speakResult = TextToSpeech.SUCCESS
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            assertEquals(SpeechOutputReadiness.READY, f.output.recheck())
        } finally { f.output.closeAndJoin() }
    }

    @Test fun stopAndShutdownFailuresRemainObservableAfterAllReleasesFinish() = runTest {
        val f = Fixture(this)
        val speaking = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Release fixture.", {}) } }
        runCurrent()
        f.client.stopResult = TextToSpeech.ERROR
        val shutdownFailure = IllegalStateException("Engine release failed")
        f.client.shutdownFailure = shutdownFailure
        val stopFailure = requireNotNull(runCatching { f.output.interruptCurrent() }.exceptionOrNull())
        assertEquals("System speech stop failed", stopFailure.message)
        assertEquals(1, f.focus.abandons)
        runCurrent()
        assertTrue(speaking.await().isFailure)
        val closing = runCatching { f.output.closeAndJoin() }
        val closeFailure = requireNotNull(closing.exceptionOrNull())
        assertTrue(speaking.isCompleted)
        assertEquals(1, f.client.stops)
        assertEquals(1, f.focus.abandons)
        assertEquals(1, f.client.shutdowns)
        // Coroutine stack recovery may wrap the primary exception while retaining it as the cause.
        val primary = generateSequence(closeFailure) { it.cause }.firstOrNull { it === stopFailure }
        assertSame(stopFailure, primary)
        assertSame(shutdownFailure, requireNotNull(primary).suppressed.single())
    }

    @Test fun configurationAndConstructionErrorsAreTypedAndNeverAcquireSpeechFocus() = runTest {
        val configuration = Fixture(this)
        configuration.client.configureResult = TextToSpeech.ERROR
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, configuration.output.readiness())
        assertEquals(0, configuration.focus.requests)
        assertEquals(1, configuration.client.shutdowns)
        configuration.output.closeAndJoin()
        val construction = Fixture(this)
        construction.creationFailure = IllegalStateException("Platform construction failed")
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, construction.output.readiness())
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, construction.output.readiness())
        assertEquals(1, construction.creations)
        assertEquals(0, construction.focus.requests)
        construction.output.closeAndJoin()
    }

    @Test fun closeStopsSpeakingAndJoinsItsOperationBeforeReturningAndRejectsLateCallbacks() = runTest {
        val f = Fixture(this)
        var starts = 0
        val speaking = async { runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Close fixture.") { starts++ } } }
        runCurrent()
        val id = f.client.ids.single()
        f.output.closeAndJoin()
        assertTrue(speaking.isCompleted)
        assertTrue(speaking.await().exceptionOrNull() is CancellationException)
        assertEquals(1, f.client.stops)
        assertEquals(1, f.client.shutdowns)
        assertEquals(1, f.focus.abandons)
        f.client.listener.onStart(id, ENGINE_UID)
        f.client.listener.onDone(id, ENGINE_UID)
        assertEquals(0, starts)
        assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "No restart.", {}) }.isFailure)
        assertEquals(1, f.creations)
    }

    @Test fun explicitRetryAwaitsTheOldAttemptAndItsLateCallbackCannotShutdownTheReplacement() = runTest {
        val voices = listOf(voice("local-us", Locale.US), voice("local-ru", Locale.forLanguageTag("ru-RU")))
        val old = Client(voices)
        val replacement = Client(voices)
        val callbacks = mutableListOf<(Int) -> Unit>()
        val output = SystemConversationSpeechOutput(
            listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), 100,
            { initialized ->
                callbacks += initialized
                if (callbacks.size == 1) old else replacement.also { initialized(TextToSpeech.SUCCESS) }
            }, Focus(), ImmediateThread, StandardTestDispatcher(testScheduler),
        )
        try {
            val originalReadiness = async { output.readiness() }
            runCurrent()
            val retry = async { output.recheck() }
            runCurrent()
            assertFalse(retry.isCompleted)
            assertEquals(1, callbacks.size)
            callbacks.first()(TextToSpeech.ERROR)
            runCurrent()
            assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, originalReadiness.await())
            assertEquals(SpeechOutputReadiness.READY, retry.await())
            assertEquals(1, old.shutdowns)
            assertEquals(0, replacement.shutdowns)
            callbacks.first()(TextToSpeech.SUCCESS)
            assertEquals(2, old.shutdowns)
            assertEquals(0, replacement.shutdowns)
            assertEquals(SpeechOutputReadiness.READY, output.readiness())
        } finally { output.closeAndJoin() }
        assertEquals(1, replacement.shutdowns)
    }

    @Test fun freshLocalIdentityAllowsSpeechAndMissingVoiceOnlyExposesExplicitDataActionPackage() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(SpeechOutputReadiness.READY, f.output.readiness())
            assertEquals(ENGINE_PACKAGE, f.output.enginePackage)
            assertTrue(f.client.controls.single().startsWith("identity-"))
            assertEquals(listOf(ENGINE_UID), f.client.resolvedUids)
            assertEquals(0, f.focus.requests)
            assertTrue(f.client.ids.isEmpty())
            f.client.voices = listOf(f.russian)
            assertEquals(ENGINE_PACKAGE, f.output.voiceDataEnginePackage())
            assertEquals(SpeechOutputReadiness.MISSING_LOCAL_VOICE, f.output.readiness())
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Do not enqueue.", {}) }.isFailure)
            assertTrue(f.client.ids.isEmpty())
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun unmappedActualCallerCannotExposeInstallerOrSendTextAndDoesNotImplicitlyRetry() = runTest {
        // Resolution boundary returns null for self/system/shared/unregistered/missing-service UIDs.
        val f = Fixture(this)
        try {
            f.client.resolvedPackage = null
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, f.output.readiness())
            assertNull(f.output.enginePackage)
            assertNull(f.output.voiceDataEnginePackage())
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Private fixture.", {}) }.isFailure)
            assertEquals(1, f.client.controls.size)
            assertTrue(f.client.ids.isEmpty())
            assertEquals(0, f.focus.requests)
            f.client.resolvedPackage = ENGINE_PACKAGE
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, f.output.readiness())
            assertEquals(SpeechOutputReadiness.READY, f.output.recheck())
        } finally { f.output.closeAndJoin() }
    }

    @Test fun metadataReconnectToAlternateCallerInvalidatesPreviouslyAttestedIdentity() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(SpeechOutputReadiness.READY, f.output.readiness())
            f.client.reconnectOnVoiceRead = true
            f.client.controlDoneUid = ALTERNATE_UID
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Never send to replacement.", {}) }.isFailure)
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, f.output.readiness())
            assertNull(f.output.voiceDataEnginePackage())
            assertNull(f.output.enginePackage)
            assertTrue(f.client.ids.isEmpty())
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun controlStopFailureCannotClaimSuccessfulReleaseOrAllowAnotherTransaction() = runTest {
        val f = Fixture(this)
        f.client.automaticControl = false
        val pending = async { runCatching { f.output.readiness() } }
        runCurrent()
        f.client.stopResult = TextToSpeech.ERROR
        assertTrue(runCatching { f.output.interruptCurrent() }.isFailure)
        runCurrent()
        assertTrue(pending.await().isFailure)
        assertNull(f.output.enginePackage)
        assertTrue(runCatching { f.output.recheck() }.isFailure)
        assertEquals(1, f.client.controls.size)
        assertTrue(runCatching { f.output.closeAndJoin() }.isFailure)
        assertEquals(1, f.client.shutdowns)
        assertEquals(0, f.focus.requests)
    }

    @Test fun unexpectedCallerWithCompleteCallbacksCannotUseValidVoiceSnapshotOrInstaller() = runTest {
        val f = Fixture(this)
        try {
            f.client.controlUid = ALTERNATE_UID
            f.client.controlDoneUid = ALTERNATE_UID
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "Keep private.", {}) }.isFailure)
            assertEquals(listOf(ALTERNATE_UID), f.client.resolvedUids)
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, f.output.readiness())
            assertNull(f.output.voiceDataEnginePackage())
            assertNull(f.output.enginePackage)
            assertTrue(f.client.ids.isEmpty())
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun missingVoiceWithoutResolvableIdentityCannotOfferAnInstallerPackage() = runTest {
        val f = Fixture(this)
        try {
            f.client.voices = emptyList()
            f.client.resolvedPackage = null
            assertNull(f.output.voiceDataEnginePackage())
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, f.output.readiness())
            assertNull(f.output.enginePackage)
            assertEquals(1, f.client.controls.size)
            assertTrue(f.client.ids.isEmpty())
        } finally { f.output.closeAndJoin() }
    }

    @Test fun silentControlQueueFailureIsStickyAndNeverSendsUserText() = runTest {
        val f = Fixture(this)
        try {
            f.client.controlResult = TextToSpeech.ERROR
            assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, f.output.readiness())
            f.client.controlResult = TextToSpeech.SUCCESS
            assertNull(f.output.voiceDataEnginePackage())
            assertTrue(runCatching { f.output.speak(LanguageCatalog.ENGLISH, "No retry.", {}) }.isFailure)
            assertEquals(1, f.client.controls.size)
            assertEquals(0, f.focus.requests)
            assertTrue(f.client.ids.isEmpty())
        } finally { f.output.closeAndJoin() }
    }

    @Test fun mismatchedControlCallbacksAndUnexpectedSynthesisCannotBecomeReadiness() = runTest {
        val mismatched = Fixture(this)
        try {
            mismatched.client.controlDoneUid = ALTERNATE_UID
            assertEquals(SpeechOutputReadiness.ENGINE_UNAVAILABLE, mismatched.output.readiness())
            assertTrue(mismatched.client.resolvedUids.isEmpty())
            assertNull(mismatched.output.voiceDataEnginePackage())
        } finally { mismatched.output.closeAndJoin() }
        val synthesized = Fixture(this)
        try {
            synthesized.client.automaticControl = false
            var phases = 0
            val speech = async { runCatching {
                synthesized.output.speak(LanguageCatalog.ENGLISH, "No synthesis for control.") { phases++ }
            } }
            runCurrent()
            val id = synthesized.client.controls.single()
            synthesized.client.listener.onControlSynthesis(id)
            runCurrent()
            assertTrue(speech.await().isFailure)
            assertNull(synthesized.output.enginePackage)
            assertTrue(synthesized.client.ids.isEmpty())
            assertEquals(0, synthesized.focus.requests)
            assertEquals(0, phases)
        } finally { synthesized.output.closeAndJoin() }
    }

    @Test fun initializationAndControlShareOneTotalTenSecondDeadlineAndTimeoutIsSticky() = runTest {
        val f = Fixture(this, initStatus = null)
        f.client.automaticControl = false
        val ready = async { f.output.readiness() }
        runCurrent()
        advanceTimeBy(9_000)
        f.init(TextToSpeech.SUCCESS)
        runCurrent()
        val id = f.client.controls.single()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(ready.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, ready.await())
        f.client.listener.onStart(id, ENGINE_UID)
        f.client.listener.onDone(id, ENGINE_UID)
        assertNull(f.output.enginePackage)
        assertNull(f.output.voiceDataEnginePackage())
        assertEquals(1, f.client.controls.size)
        assertEquals(0, f.focus.requests)
        f.output.closeAndJoin()
    }

    @Test fun subsequentControlTimeoutCannotReuseTheEarlierIdentity() = runTest {
        val f = Fixture(this)
        try {
            assertEquals(SpeechOutputReadiness.READY, f.output.readiness())
            f.client.automaticControl = false
            val action = async { f.output.voiceDataEnginePackage() }
            runCurrent()
            assertNull(f.output.enginePackage)
            advanceTimeBy(SystemConversationSpeechOutput.INIT_TIMEOUT_MILLIS)
            runCurrent()
            assertNull(action.await())
            assertEquals(SpeechOutputReadiness.INITIALIZATION_FAILED, f.output.readiness())
            assertEquals(2, f.client.controls.size)
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun cancelledControlStopsImmediatelyAndLateCallbacksCannotCompleteFreshControl() = runTest {
        val f = Fixture(this)
        try {
            f.client.automaticControl = false
            val cancelled = async { f.output.readiness() }
            runCurrent()
            val oldId = f.client.controls.single()
            cancelled.cancelAndJoin()
            assertEquals(1, f.client.stops)
            assertNull(f.output.enginePackage)
            val fresh = async { f.output.recheck() }
            runCurrent()
            val newId = f.client.controls.last()
            assertNotEquals(oldId, newId)
            f.client.listener.onStart(oldId, ENGINE_UID)
            f.client.listener.onDone(oldId, ENGINE_UID)
            runCurrent()
            assertFalse(fresh.isCompleted)
            assertNull(f.output.enginePackage)
            f.client.listener.onStart(newId, ENGINE_UID)
            f.client.listener.onDone(newId, ENGINE_UID)
            assertEquals(SpeechOutputReadiness.READY, fresh.await())
            assertEquals(0, f.focus.requests)
        } finally { f.output.closeAndJoin() }
    }

    @Test fun interruptAndCloseReleasePendingControlWithoutAwaitingDoneOrPublishingLateIdentity() = runTest {
        val interrupted = Fixture(this)
        try {
            interrupted.client.automaticControl = false
            val waiter = async { runCatching { interrupted.output.readiness() } }
            runCurrent()
            val id = interrupted.client.controls.single()
            interrupted.output.interruptCurrent()
            assertEquals(1, interrupted.client.stops)
            runCurrent()
            assertTrue(waiter.await().isFailure)
            interrupted.client.listener.onStart(id, ENGINE_UID)
            interrupted.client.listener.onDone(id, ENGINE_UID)
            assertNull(interrupted.output.enginePackage)
        } finally { interrupted.output.closeAndJoin() }
        val closed = Fixture(this)
        closed.client.automaticControl = false
        val waiter = async { runCatching { closed.output.readiness() } }
        runCurrent()
        val id = closed.client.controls.single()
        closed.output.closeAndJoin()
        assertTrue(waiter.isCompleted)
        assertTrue(waiter.await().isFailure)
        assertEquals(1, closed.client.stops)
        assertEquals(1, closed.client.shutdowns)
        closed.client.listener.onStart(id, ENGINE_UID)
        closed.client.listener.onDone(id, ENGINE_UID)
        assertNull(closed.output.enginePackage)
        assertEquals(0, closed.focus.requests)
    }
    @Test fun systemStopErrorDoesNotEscapeUiAndDisposalAwaitersShareFailure() = runTest {
        withSystemConsumer { consumer ->
            beginSystemSpeech(consumer)
            consumer.capture.terminalStopGate = CompletableDeferred()
            consumer.capture.closeGate = CompletableDeferred()
            consumer.platform.client.stopResult = TextToSpeech.ERROR
            assertTrue("Stop must establish cleanup without escaping the UI callback",
                runCatching { consumer.model.stop() }.isSuccess)
            consumer.capture.terminalStopStarted.await()
            assertFalse(consumer.model.state.value.sessionActive)
            val primary = nativeStopFailure(consumer)
            val first = async { runCatching { consumer.model.disposeAndJoin() } }
            val second = async { runCatching { consumer.model.disposeAndJoin() } }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertFalse("Session finalizer must finish before resource release", consumer.capture.closeStarted.isCompleted)
            consumer.capture.terminalStopGate!!.complete(Unit)
            consumer.capture.closeStarted.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertEquals(0, consumer.platform.client.shutdowns)
            consumer.capture.closeGate!!.complete(Unit)
            assertContainsFailure(primary, first.await().exceptionOrNull())
            assertContainsFailure(primary, second.await().exceptionOrNull())
            assertContainsFailure(primary, runCatching { consumer.model.disposeAndJoin() }.exceptionOrNull())
            assertEveryLeaseReleased(consumer)
            assertControllerBlocked(consumer, primary)
        }
    }

    @Test fun systemStopErrorOnBackgroundReleasesOwnerWithoutRestart() = runTest {
        withSystemConsumer { consumer ->
            beginSystemSpeech(consumer)
            consumer.capture.terminalStopGate = CompletableDeferred()
            consumer.platform.client.stopResult = TextToSpeech.ERROR
            assertTrue("Foreground loss must not abandon cleanup by throwing",
                runCatching { consumer.model.leaveForeground() }.isSuccess)
            consumer.capture.terminalStopStarted.await()
            val primary = nativeStopFailure(consumer)
            consumer.capture.terminalStopGate!!.complete(Unit)
            assertContainsFailure(primary, runCatching { consumer.model.disposeAndJoin() }.exceptionOrNull())
            assertEveryLeaseReleased(consumer)
            assertControllerBlocked(consumer, primary)
            val starts = consumer.capture.starts
            consumer.model.enterForeground()
            consumer.model.start()
            runCurrent()
            assertEquals("A failed background release cannot reopen or record", starts, consumer.capture.starts)
            assertFalse(consumer.model.ready.value)
        }
    }

    @Test fun systemStopErrorDisposalAwaitsAllLeasesAndPreservesEveryFailure() = runTest {
        withSystemConsumer { consumer ->
            beginSystemSpeech(consumer)
            val shutdown = IllegalStateException("SDK shutdown fixture failure")
            val capture = IllegalStateException("Capture lease fixture failure")
            val recognition = IllegalStateException("Recognition lease fixture failure")
            val translation = IllegalStateException("Translation lease fixture failure")
            val models = IllegalStateException("Model lease fixture failure")
            consumer.capture.terminalStopGate = CompletableDeferred()
            consumer.capture.closeGate = CompletableDeferred()
            consumer.capture.closeFailure = capture
            consumer.recognition.closeFailure = recognition
            consumer.translation.closeFailure = translation
            consumer.models.closeFailure = models
            consumer.platform.client.stopResult = TextToSpeech.ERROR
            consumer.platform.client.shutdownFailure = shutdown
            assertTrue("Dispose must cache an independent cleanup result before interruption",
                runCatching { consumer.model.dispose() }.isSuccess)
            consumer.capture.terminalStopStarted.await()
            val primary = nativeStopFailure(consumer)
            val cancelled = async { consumer.model.disposeAndJoin() }
            val surviving = async { runCatching { consumer.model.disposeAndJoin() } }
            runCurrent()
            cancelled.cancelAndJoin()
            assertFalse(surviving.isCompleted)
            consumer.capture.terminalStopGate!!.complete(Unit)
            consumer.capture.closeStarted.await()
            assertEquals(0, consumer.platform.client.shutdowns)
            assertEquals(0, consumer.models.closes)
            consumer.capture.closeGate!!.complete(Unit)
            val failure = surviving.await().exceptionOrNull()
            for (expected in listOf(primary, shutdown, capture, recognition, translation, models)) {
                assertContainsFailure(expected, failure)
            }
            assertContainsFailure(primary, runCatching { consumer.model.disposeAndJoin() }.exceptionOrNull())
            assertEveryLeaseReleased(consumer)
            assertControllerBlocked(consumer, primary)
        }
    }

    @Test fun systemStopErrorReplacementCannotSkipCleanupOrCommit() = runTest {
        withSystemConsumer { consumer ->
            beginSystemSpeech(consumer)
            consumer.capture.terminalStopGate = CompletableDeferred()
            consumer.platform.client.stopResult = TextToSpeech.ERROR
            var successorBuilt = false
            val replacement = async { runCatching {
                consumer.controller.replace<ConversationRuntimeOwner> {
                    successorBuilt = true
                    error("A successor must not be constructed after a failed release")
                }
            } }
            runCurrent()
            assertTrue("Replacement must enter the awaited session-finalizer barrier",
                consumer.capture.terminalStopStarted.isCompleted)
            assertFalse(replacement.isCompleted)
            assertFalse(successorBuilt)
            assertTrue(consumer.controller.busy.value)
            val primary = nativeStopFailure(consumer)
            consumer.capture.terminalStopGate!!.complete(Unit)
            assertContainsFailure(primary, replacement.await().exceptionOrNull())
            assertFalse(successorBuilt)
            assertEveryLeaseReleased(consumer)
            assertControllerBlocked(consumer, primary)
            assertContainsFailure(primary, runCatching { consumer.model.disposeAndJoin() }.exceptionOrNull())
            assertEveryLeaseReleased(consumer)
        }
    }

    private suspend fun TestScope.beginSystemSpeech(consumer: SystemConsumer) {
        runCurrent()
        assertTrue(consumer.model.ready.value)
        consumer.model.start()
        runCurrent()
        consumer.capture.emit()
        runCurrent()
        assertEquals(ConversationPhase.SPEAKING, consumer.model.state.value.phase)
        assertEquals(1, consumer.platform.client.ids.size)
        consumer.platform.client.listener.onStart(consumer.platform.client.ids.single(), ENGINE_UID)
        runCurrent()
    }

    private fun assertEveryLeaseReleased(consumer: SystemConsumer) {
        assertEquals(1, consumer.capture.closes)
        assertEquals(1, consumer.platform.client.shutdowns)
        assertEquals(1, consumer.recognition.closes)
        assertEquals(1, consumer.translation.closes)
        assertEquals(1, consumer.models.closes)
        assertEquals(1, consumer.platform.focus.abandons)
    }

    private suspend fun assertControllerBlocked(consumer: SystemConsumer, primary: Throwable) {
        assertTrue(consumer.controller.teardownFailed.value)
        assertTrue(consumer.controller.busy.value)
        assertFalse(consumer.controller.isCurrent(consumer.owner))
        var committed = false
        assertFalse(consumer.controller.saveIfIdle({ true }) { committed = true })
        assertFalse(consumer.controller.saveIfUnowned { committed = true; true })
        assertFalse(committed)
        var successorBuilt = false
        assertNull(consumer.controller.createIfUnowned({ true }) {
            successorBuilt = true
            consumer.owner
        })
        val replacement = runCatching { consumer.controller.replace<ConversationRuntimeOwner> {
            successorBuilt = true
            error("The sticky controller must not invoke a successor factory")
        } }
        assertFalse(successorBuilt)
        assertContainsFailure(primary, replacement.exceptionOrNull())
        assertContainsFailure(primary, runCatching { consumer.controller.closeAndJoin() }.exceptionOrNull())
    }

    private suspend fun nativeStopFailure(consumer: SystemConsumer): Throwable {
        // Await may recover a thrown exception into a wrapper. The adapter's original
        // SDK stop fault, not that incidental wrapper, must survive every cleanup result.
        var failure = requireNotNull(runCatching { consumer.platform.output.readiness() }.exceptionOrNull())
        while (true) failure = failure.cause ?: return failure
    }

    private fun assertContainsFailure(expected: Throwable, actual: Throwable?) {
        assertNotNull("Cleanup must expose its actual failure", actual)
        val pending = mutableListOf(requireNotNull(actual))
        val seen = mutableSetOf<Throwable>()
        while (pending.isNotEmpty()) {
            val failure = pending.removeAt(pending.lastIndex)
            if (!seen.add(failure)) continue
            if (failure === expected) return
            failure.cause?.let(pending::add)
            pending.addAll(failure.suppressed)
        }
        fail("The original failure instance was lost from the cleanup result")
    }

    private suspend fun TestScope.withSystemConsumer(block: suspend TestScope.(SystemConsumer) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val platform = Fixture(this)
        val observationJob = SupervisorJob()
        val ownerJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(dispatcher + observationJob))
        val capture = ConsumerCapture()
        val recognition = ConsumerRecognition()
        val translation = ConsumerTranslation()
        val models = ConsumerModels()
        val owner = controller.replace {
            ConversationRuntimeOwner(
                AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig(),
                models, capture, recognition, translation, platform.output,
                CoroutineScope(dispatcher + ownerJob),
            )
        }
        val model = ConversationViewModel(owner, controller)
        val consumer = SystemConsumer(platform, controller, owner, model, capture, recognition, translation, models)
        try {
            block(consumer)
        } finally {
            capture.terminalStopGate?.complete(Unit)
            capture.closeGate?.complete(Unit)
            try {
                // Fault fixtures still release their native adapter when a pre-fix assertion fails.
                runCatching { model.disposeAndJoin() }
                runCatching { platform.output.closeAndJoin() }
            } finally {
                model.viewModelScope.cancel()
                ownerJob.cancelAndJoin()
                observationJob.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }
    }

    private class SystemConsumer(
        val platform: Fixture,
        val controller: RuntimeOwnerController,
        val owner: ConversationRuntimeOwner,
        val model: ConversationViewModel,
        val capture: ConsumerCapture,
        val recognition: ConsumerRecognition,
        val translation: ConsumerTranslation,
        val models: ConsumerModels,
    )

    private class ConsumerCapture : SpeechCaptureEngine {
        var starts = 0
        var closes = 0
        var terminalStopGate: CompletableDeferred<Unit>? = null
        var closeGate: CompletableDeferred<Unit>? = null
        var closeFailure: Throwable? = null
        val terminalStopStarted = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        private var listener: SpeechCaptureListener? = null
        override suspend fun start(listener: SpeechCaptureListener) { starts++; this.listener = listener }
        override fun interruptCurrent() = Unit
        override suspend fun stop(flush: Boolean): SpeechSegment? {
            terminalStopGate?.let { gate ->
                terminalStopStarted.complete(Unit)
                withContext(NonCancellable) { gate.await() }
            }
            return null
        }
        fun emit() { requireNotNull(listener).onSegment(SpeechSegment.fromPcm(shortArrayOf(17))) }
        override fun close() { closes++ }
        override suspend fun closeAndJoin() {
            close()
            closeStarted.complete(Unit)
            closeGate?.await()
            closeFailure?.let { throw it }
        }
    }

    private class ConsumerRecognition : ConversationRecognitionEngine {
        var closes = 0
        var closeFailure: Throwable? = null
        override val readinessState = MutableStateFlow(SpeechRecognitionReadiness.READY)
        override suspend fun readiness() = readinessState.value
        override suspend fun install() { readinessState.value = SpeechRecognitionReadiness.READY }
        override suspend fun prepare() = Unit
        override suspend fun recognize(segment: SpeechSegment) =
            RecognizedUtterance(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), "Stop error fixture")
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private class ConsumerTranslation : TranslationEngine {
        override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.READY))
        var closes = 0
        var closeFailure: Throwable? = null
        override suspend fun modelAvailability() = ModelAvailability.READY
        override suspend fun prepare() = ModelAvailability.READY
        override suspend fun translate(pair: LanguagePair, text: String) = "Consumer release fixture"
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private class ConsumerModels : ConversationModelRepository {
        var closes = 0
        var closeFailure: Throwable? = null
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install() = Unit
        override suspend fun isConversationReady() = true
        override fun close() { closes++; closeFailure?.let { throw it } }
    }


    private class Fixture(
        scope: TestScope,
        private val initStatus: Int? = TextToSpeech.SUCCESS,
        selectedLanguages: List<com.example.offlinetranslator.core.LanguageId> = listOf(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
    ) {
        val english = voice("local-us", Locale.US)
        val russian = voice("local-ru", Locale.forLanguageTag("ru-RU"))
        val client = Client(listOf(english, russian))
        val focus = Focus()
        var creations = 0
        var creationFailure: Throwable? = null
        lateinit var init: (Int) -> Unit
        val output = SystemConversationSpeechOutput(
            selectedLanguages, 100,
            { callback ->
                creations++
                creationFailure?.let { throw it }
                init = callback
                initStatus?.let(callback)
                client
            },
            focus, ImmediateThread, StandardTestDispatcher(scope.testScheduler),
        )
    }

    private class Client(override var voices: List<TtsVoice>) : TtsClient {
        override var engineAvailable = true
        var resolvedPackage: String? = ENGINE_PACKAGE
        var controlUid = ENGINE_UID
        var controlDoneUid = ENGINE_UID
        var automaticControl = true
        var controlResult = TextToSpeech.SUCCESS
        var reconnectOnVoiceRead = false
        val controls = mutableListOf<String>()
        val resolvedUids = mutableListOf<Int>()
        override fun resolveEnginePackage(callerUid: Int): String? {
            resolvedUids += callerUid
            return resolvedPackage.takeIf { callerUid == ENGINE_UID }
        }
        override fun playSilentUtterance(utteranceId: String): Int {
            controls += utteranceId
            if (automaticControl && controlResult == TextToSpeech.SUCCESS) {
                listener.onStart(utteranceId, controlUid)
                listener.onDone(utteranceId, controlDoneUid)
            }
            return controlResult
        }
        override var defaultVoice: TtsVoice? = voices.first()
        private var selectedVoice: TtsVoice? = null
        override var currentVoice: TtsVoice?
            get() {
                if (reconnectOnVoiceRead) controlUid = ALTERNATE_UID
                return selectedVoice
            }
            set(value) { selectedVoice = value }
        override var maximumInputLength = 4096
        var data = TextToSpeech.LANG_COUNTRY_AVAILABLE
        var voiceResult = TextToSpeech.SUCCESS
        var mismatch = false
        var speakResult = TextToSpeech.SUCCESS
        var stopResult = TextToSpeech.SUCCESS
        var configureResult = TextToSpeech.SUCCESS
        var stops = 0
        var shutdowns = 0
        var shutdownFailure: Throwable? = null
        val ids = mutableListOf<String>()
        val speechVoices = mutableListOf<TtsVoice>()
        lateinit var listener: TtsListener
        override fun dataAvailability(locale: Locale) = data
        override fun setVoice(voice: TtsVoice): Int {
            currentVoice = if (mismatch) voice.copy(name = "different-voice") else voice
            return voiceResult
        }
        override fun configure(speed: Float) = configureResult
        override fun setListener(listener: TtsListener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(text: String, utteranceId: String): Int {
            ids += utteranceId
            speechVoices += requireNotNull(currentVoice)
            return speakResult
        }
        override fun stop(): Int { stops++; return stopResult }
        override fun shutdown() { shutdowns++; shutdownFailure?.let { throw it } }
    }
    private class Focus : TtsAudioFocus {
        var granted = true
        var requests = 0
        var abandons = 0
        private var onLoss: (() -> Unit)? = null
        override fun request(onLoss: () -> Unit): Boolean { requests++; this.onLoss = onLoss; return granted }
        override fun abandon() { abandons++; onLoss = null }
        fun lose() = requireNotNull(onLoss).invoke()
    }

    private object ImmediateThread : TtsThread {
        override fun <T> call(block: () -> T): T = block()
        override fun post(block: () -> Unit) = block()
    }
    companion object {
        private const val ENGINE_UID = 12_345
        private const val ALTERNATE_UID = 23_456
        private const val ENGINE_PACKAGE = "test.local.engine"
        private fun voice(name: String, locale: Locale, network: Boolean = false) = TtsVoice(name, locale, network, false)
    }
}
