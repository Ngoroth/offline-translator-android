package com.example.offlinetranslator.speech

import android.media.AudioFormat
import android.net.ConnectivityManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.AppSettings
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechPlaybackStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/** Radio-off genuine platform synthesis/playback proof. Not acoustic, quality or latency qualification. */
class SystemSpeechOutputNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val config = AppSettings(speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE).conversationConfig()

    @Test fun productionClientCompletesBothLocalLanguagesAndStopsThenRechecksIdle() = runBlocking<Unit> {
        assertOffline()
        val output = SystemConversationSpeechOutput(context, config)
        try {
            assertEquals("BLOCKED: install selected RU/EN offline voices explicitly before this test", SpeechOutputReadiness.READY,
                withTimeout(15_000) { output.readiness() })
            assertNotNull(output.enginePackage)
            for ((language, text) in FIXTURES) {
                val starts = mutableListOf<SpeechPlaybackStart>()
                withTimeout(60_000) { output.speak(language, text, starts::add) }
                assertEquals(listOf(SpeechPlaybackStart(null, false)), starts)
            }
            val started = CompletableDeferred<Unit>()
            val longSpeech = async { runCatching { output.speak(LanguageCatalog.ENGLISH, LONG_FIXTURE) { started.complete(Unit) } } }
            withTimeout(15_000) { started.await() }
            delay(200)
            assertFalse("Stop must interrupt actual ongoing playback", longSpeech.isCompleted)
            output.interruptCurrent()
            assertTrue(withTimeout(10_000) { longSpeech.await() }.exceptionOrNull() is CancellationException)
            assertEquals(SpeechOutputReadiness.READY, output.recheck())
        } finally { output.closeAndJoin() }
        val reopened = SystemConversationSpeechOutput(context, config)
        try {
            assertEquals(SpeechOutputReadiness.READY, withTimeout(15_000) { reopened.readiness() })
        } finally { reopened.closeAndJoin() }
        assertOffline()
    }

    @Test fun genuineProductionProgressBuffersProvideFixtureOnlyOfflineEvidenceAndMatchingStop() = runBlocking<Unit> {
        assertOffline()
        val evidence = Evidence()
        val thread = AndroidTtsThread()
        val focus = ObservedFocus(AndroidTtsAudioFocus(context))
        lateinit var observed: ObservedClient
        val output = SystemConversationSpeechOutput(
            config.languages, config.ttsSpeedPercent,
            { initialized -> ObservedClient(AndroidTtsClient(context, initialized, evidence), evidence).also { observed = it } },
            focus, thread, Dispatchers.Main.immediate,
        )
        val destination = InstrumentationRegistry.getArguments().getString("systemTtsEvidenceDir")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)) { "BLOCKED: external fixture evidence storage unavailable" }, "system-tts-evidence")
        check(destination.isDirectory || destination.mkdirs()) { "Fixture evidence directory is inaccessible" }
        Log.i(TAG, "fixtureEvidenceDirectory=${destination.absolutePath}")
        try {
            assertEquals("BLOCKED: installed local RU/EN data is required; no Assume skip", SpeechOutputReadiness.READY,
                withTimeout(15_000) { output.readiness() })
            evidence.assertSilentControls()
            assertNull("Readiness controls must not create a user request", evidence.latest)
            assertEquals("Readiness control must not acquire user audio focus", 0, focus.requests)
            for ((language, text) in FIXTURES) {
                val starts = mutableListOf<SpeechPlaybackStart>()
                withTimeout(60_000) { output.speak(language, text, starts::add) }
                assertEquals(listOf(SpeechPlaybackStart(null, false)), starts)
                val request = requireNotNull(evidence.latest)
                assertEquals("done", request.terminal)
                assertLocalIdentity(request, language)
                request.write(destination, "complete-${language.value}")
            }
            val started = CompletableDeferred<Unit>()
            val speaking = async { runCatching {
                output.speak(LanguageCatalog.RUSSIAN, LONG_RUSSIAN_FIXTURE) { start ->
                    assertNull(start.presentationTimeNanos)
                    assertFalse(start.measured)
                    started.complete(Unit)
                }
            } }
            withTimeout(15_000) { started.await() }
            val interrupted = requireNotNull(evidence.latest)
            withTimeout(10_000) { interrupted.nonSilent.await() }
            delay(200)
            assertFalse("Long real utterance must still own playback before stop", speaking.isCompleted)
            output.interruptCurrent()
            assertTrue(withTimeout(10_000) { speaking.await() }.exceptionOrNull() is CancellationException)
            withTimeout(10_000) { interrupted.stopped.await() }
            assertEquals("stop", interrupted.terminal)
            assertEquals(TextToSpeech.SUCCESS, observed.lastStopResult)
            assertLocalIdentity(interrupted, LanguageCatalog.RUSSIAN)
            interrupted.write(destination, "interrupted-ru")
            val focusRequests = focus.requests
            assertEquals(SpeechOutputReadiness.READY, output.recheck())
            evidence.assertSilentControls()
            assertEquals("Recheck control must not acquire user audio focus", focusRequests, focus.requests)
            assertTrue(runCatching { output.speak(LanguageCatalog.ENGLISH, "a".repeat(4097), {}) }.isFailure)
            // Recheck did not speak, restart a queue, or change the selected voice metadata.
            assertEquals(interrupted.id, evidence.latest?.id)
        } finally { output.closeAndJoin() }
        assertOffline()
    }
    @Test fun allSelectedLocalesHaveGenuineInstalledOfflineVoiceAndCompletedPcm() = runBlocking<Unit> {
        assertOffline()
        val destination = InstrumentationRegistry.getArguments().getString("systemTtsEvidenceDir")?.let(::File)
            ?: File(requireNotNull(context.getExternalFilesDir(null)), "system-tts-evidence")
        check(destination.isDirectory || destination.mkdirs()) { "Fixture evidence directory is inaccessible" }
        val requestedLanguageIds = InstrumentationRegistry.getArguments()
            .getString("systemTtsLanguageIds")?.split(',')?.toSet()
        val fixtures = if (requestedLanguageIds == null) ALL_FIXTURES else {
            ALL_FIXTURES.filter { it.first.value in requestedLanguageIds }.also { selected ->
                require(selected.map { it.first.value }.toSet() == requestedLanguageIds) {
                    "Every requested speech-output language must have a fixed fixture"
                }
            }
        }
        Log.i(TAG, "voiceFixtureLanguages=${fixtures.map { it.first.value }}")
        for ((language, text) in fixtures) {
            val partner = if (language == LanguageCatalog.ENGLISH) LanguageCatalog.RUSSIAN else LanguageCatalog.ENGLISH
            val selected = AppSettings(
                languagePair = LanguagePair(language, partner),
                speechOutputBackend = SpeechOutputBackend.SYSTEM_OFFLINE,
            ).conversationConfig()
            val evidence = Evidence()
            val focus = ObservedFocus(AndroidTtsAudioFocus(context))
            lateinit var observed: ObservedClient
            val output = SystemConversationSpeechOutput(
                selected.languages, selected.ttsSpeedPercent,
                { initialized -> ObservedClient(AndroidTtsClient(context, initialized, evidence), evidence).also { observed = it } },
                focus, AndroidTtsThread(), Dispatchers.Main.immediate,
            )
            try {
                assertEquals("BLOCKED: explicitly install local voices for ${language.value}/${partner.value}",
                    SpeechOutputReadiness.READY, withTimeout(15_000) { output.readiness() })
                evidence.assertSilentControls()
                assertEquals(0, focus.requests)
                val starts = mutableListOf<SpeechPlaybackStart>()
                withTimeout(60_000) { output.speak(language, text, starts::add) }
                assertEquals(listOf(SpeechPlaybackStart(null, false)), starts)
                val request = requireNotNull(evidence.latest)
                assertEquals("done", request.terminal)
                assertLocalIdentity(request, language)
                request.write(destination, "catalog-complete-${language.value}")
                Log.i(TAG, "localVoice language=${language.value} locale=${request.actual?.locale?.toLanguageTag()} engine=${request.engine} terminal=${request.terminal}")
                val started = CompletableDeferred<Unit>()
                val ongoing = async { runCatching {
                    output.speak(language, "$text ".repeat(40)) { started.complete(Unit) }
                } }
                withTimeout(15_000) { started.await() }
                val interrupted = requireNotNull(evidence.latest)
                withTimeout(10_000) { interrupted.nonSilent.await() }
                delay(200)
                assertFalse("Stop must interrupt actual ${language.value} playback", ongoing.isCompleted)
                output.interruptCurrent()
                assertTrue(withTimeout(10_000) { ongoing.await() }.exceptionOrNull() is CancellationException)
                withTimeout(10_000) { interrupted.stopped.await() }
                assertEquals("stop", interrupted.terminal)
                assertEquals(TextToSpeech.SUCCESS, observed.lastStopResult)
                assertLocalIdentity(interrupted, language)
                interrupted.write(destination, "catalog-interrupted-${language.value}")
                val focusRequests = focus.requests
                assertEquals(SpeechOutputReadiness.READY, output.recheck())
                assertEquals("Recheck must not speak another fixture", 2, evidence.requests.size)
                assertEquals(focusRequests, focus.requests)
                evidence.assertSilentControls()
            } finally { output.closeAndJoin() }
        }
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), ALL_FIXTURES.map { it.first }.toSet())
        assertOffline()
    }


    private fun assertLocalIdentity(request: Request, language: LanguageId) {
        assertNotNull(request.engine)
        assertNotNull(request.controlId)
        assertEquals(request.controlStartUid, request.controlDoneUid)
        assertNotNull(request.controlStartUid)
        assertNotNull(request.startUid)
        if (request.terminal == "done") assertEquals(request.startUid, request.doneUid)
        if (language == LanguageCatalog.CHINESE) {
            val locale = request.requested.locale
            assertTrue(locale.language == "zh" || locale.language == "cmn")
            assertEquals("CN", locale.country)
            assertTrue(locale.script.isEmpty() || locale.script == "Hans")
        } else {
            assertEquals(AndroidSpeechLocales.output(language).language, request.requested.locale.language)
        }
        assertFalse(request.requested.networkRequired)
        assertFalse(request.requested.notInstalled)
        assertEquals(TextToSpeech.SUCCESS, request.setVoiceResult)
        assertTrue(request.dataAvailability >= TextToSpeech.LANG_AVAILABLE)
        assertEquals("setVoice/getVoice must identify the exact installed local voice", request.requested, request.actual)
        assertTrue(request.events.contains("start"))
        assertTrue(request.hasNonSilentPcm())
    }

    private fun assertOffline() {
        assertEquals("BLOCKED: verifier must disconnect all radios first", 1, Settings.Global.getInt(context.contentResolver, "airplane_mode_on"))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on"))
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertNull("Radio flags alone are not offline proof", manager.activeNetwork)
        @Suppress("DEPRECATION")
        assertTrue("No connected network may remain", manager.allNetworks.none { network ->
            @Suppress("DEPRECATION")
            manager.getNetworkInfo(network)?.isConnected == true
        })
    }

    private class ObservedFocus(private val delegate: TtsAudioFocus) : TtsAudioFocus {
        var requests = 0
        override fun request(onLoss: () -> Unit): Boolean {
            requests++
            return delegate.request(onLoss)
        }
        override fun abandon() = delegate.abandon()
    }

    private class ObservedClient(private val delegate: TtsClient, private val evidence: Evidence) : TtsClient by delegate {
        private var selected: TtsVoice? = null
        private var selectedResult = TextToSpeech.ERROR
        private var selectedData = TextToSpeech.LANG_MISSING_DATA
        private var actual: TtsVoice? = null
        private var engine: String? = null
        override val currentVoice: TtsVoice?
            get() = delegate.currentVoice.also { actual = it }
        override fun playSilentUtterance(utteranceId: String): Int {
            engine = null
            evidence.controls[utteranceId] = Control(utteranceId)
            return delegate.playSilentUtterance(utteranceId)
        }
        override fun resolveEnginePackage(callerUid: Int): String? =
            delegate.resolveEnginePackage(callerUid).also { resolved ->
                engine = resolved
                evidence.latestControl()?.let { it.resolvedUid = callerUid; it.engine = resolved }
            }
        override fun dataAvailability(locale: java.util.Locale): Int =
            delegate.dataAvailability(locale).also { selectedData = it }
        var lastStopResult: Int? = null
        override fun setVoice(voice: TtsVoice): Int {
            val result = delegate.setVoice(voice)
            selectedResult = result
            if (result == TextToSpeech.SUCCESS) selected = voice
            return result
        }
        override fun speak(text: String, utteranceId: String): Int {
            val control = requireNotNull(evidence.latestControl())
            val request = Request(
                utteranceId, engine, requireNotNull(selected), actual, selectedResult, selectedData,
                control.id, control.startUid, control.doneUid,
            )
            evidence.requests[utteranceId] = request
            evidence.latest = request
            return delegate.speak(text, utteranceId)
        }
        override fun setListener(listener: TtsListener): Int = delegate.setListener(object : TtsListener {
            override fun onStart(id: String, callerUid: Int) {
                evidence.controls[id]?.startUid = callerUid
                evidence.requests[id]?.let { it.startUid = callerUid; it.event("start") }
                listener.onStart(id, callerUid)
            }
            override fun onDone(id: String, callerUid: Int) {
                evidence.controls[id]?.doneUid = callerUid
                evidence.requests[id]?.let { it.doneUid = callerUid; it.terminal("done") }
                listener.onDone(id, callerUid)
            }
            override fun onError(id: String, code: Int) {
                evidence.requests[id]?.terminal("error-$code")
                listener.onError(id, code)
            }
            override fun onStop(id: String) {
                evidence.requests[id]?.let { it.terminal("stop"); it.stopped.complete(Unit) }
                listener.onStop(id)
            }
            override fun onControlSynthesis(id: String) {
                evidence.controls[id]?.let { it.synthesisCallbacks++ }
                listener.onControlSynthesis(id)
            }
        })
        override fun stop(): Int = delegate.stop().also { lastStopResult = it }
    }

    private class Evidence : TtsSynthesisObserver {
        val requests = ConcurrentHashMap<String, Request>()
        val controls = ConcurrentHashMap<String, Control>()
        fun latestControl(): Control? = controls.values.maxByOrNull { it.sequence }
        fun assertSilentControls() {
            assertTrue("A genuine zero-silent control must be observed", controls.isNotEmpty())
            controls.values.forEach {
                assertEquals("Control must never synthesize/audio", 0, it.synthesisCallbacks)
                assertNotNull(it.startUid)
                assertEquals(it.startUid, it.doneUid)
                assertEquals(it.startUid, it.resolvedUid)
                assertNotNull(it.engine)
                assertFalse(requests.containsKey(it.id))
            }
        }
        @Volatile var latest: Request? = null
        override fun onBeginSynthesis(id: String, sampleRate: Int, audioFormat: Int, channels: Int) {
            controls[id]?.let { it.synthesisCallbacks++ }
            requests[id]?.begin(sampleRate, audioFormat, channels)
        }
        override fun onAudioAvailable(id: String, audio: ByteArray) {
            controls[id]?.let { it.synthesisCallbacks++ }
            requests[id]?.audio(audio)
        }
    }

    private class Control(val id: String) {
        val sequence = nextSequence.incrementAndGet()
        @Volatile var startUid: Int? = null
        @Volatile var doneUid: Int? = null
        @Volatile var resolvedUid: Int? = null
        @Volatile var engine: String? = null
        @Volatile var synthesisCallbacks = 0
        companion object { private val nextSequence = java.util.concurrent.atomic.AtomicLong() }
    }

    private class Request(
        val id: String, val engine: String?, val requested: TtsVoice, val actual: TtsVoice?,
        val setVoiceResult: Int, val dataAvailability: Int,
        val controlId: String, val controlStartUid: Int?, val controlDoneUid: Int?,
    ) {
        private val pcm = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        val nonSilent = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        @Volatile var terminal: String? = null
        @Volatile var startUid: Int? = null
        @Volatile var doneUid: Int? = null
        private var rate = 0
        private var format = 0
        private var channels = 0
        @Synchronized fun begin(sampleRate: Int, audioFormat: Int, channelCount: Int) {
            rate = sampleRate; format = audioFormat; channels = channelCount
            events += "begin-synthesis"
        }
        @Synchronized fun event(value: String) { events += value }
        @Synchronized fun terminal(value: String) { terminal = value; events += value }
        @Synchronized fun audio(bytes: ByteArray) {
            check(pcm.size() + bytes.size <= 16 * 1024 * 1024) { "Fixture synthesis exceeded evidence quota" }
            pcm.write(bytes)
            if (bytes.any { it != 0.toByte() }) nonSilent.complete(Unit)
        }
        @Synchronized fun hasNonSilentPcm() = pcm.size() > 0 && pcm.toByteArray().any { it != 0.toByte() }
        @Synchronized fun write(directory: File, stem: String) {
            assertEquals("Real fixture WAV proof requires PCM16", AudioFormat.ENCODING_PCM_16BIT, format)
            assertTrue(rate > 0 && channels in 1..2)
            val samples = pcm.toByteArray()
            assertTrue(samples.isNotEmpty() && samples.size % (channels * 2) == 0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1)
                .putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
                .putShort((channels * 2).toShort()).putShort(16)
                .put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size).array()
            File(directory, "$stem.wav").outputStream().use { stream -> stream.write(header); stream.write(samples) }
            File(directory, "$stem.txt").writeText(
                "utteranceId=$id\nengine=$engine\nrequested=$requested\nactual=$actual\nsetVoice=$setVoiceResult\ndataAvailability=$dataAvailability\n" +
                    "controlId=$controlId\ncontrolStartUid=$controlStartUid\ncontrolDoneUid=$controlDoneUid\nstartUid=$startUid\ndoneUid=$doneUid\n" +
                    "sampleRate=$rate\nformat=$format\nchannels=$channels\nbytes=${samples.size}\n" +
                    "events=${events.joinToString(",")}\nterminal=$terminal\nphaseOnly=true\n",
            )
            Log.i(TAG, "fixture=$stem engine=$engine voice=${requested.name} network=${requested.networkRequired} notInstalled=${requested.notInstalled} bytes=${samples.size} terminal=$terminal phaseOnly=true")
        }
    }
    companion object {
        private const val TAG = "SystemSpeechProof"
        private val FIXTURES = listOf(
            LanguageCatalog.RUSSIAN to "Поезд отправляется в четыре часа.",
            LanguageCatalog.ENGLISH to "The train leaves at four o'clock.",
        )
        private val ALL_FIXTURES = listOf(
            LanguageCatalog.ENGLISH to "The train leaves at four o'clock.",
            LanguageCatalog.VIETNAMESE to "Chúng tôi cần hai vé tàu.",
            LanguageCatalog.SPANISH to "Necesitamos dos billetes de tren.",
            LanguageCatalog.INDONESIAN to "Kami perlu dua tiket kereta.",
            LanguageCatalog.ITALIAN to "Abbiamo bisogno di due biglietti del treno.",
            LanguageCatalog.CHINESE to "我们需要两张火车票。",
            LanguageCatalog.KOREAN to "기차표 두 장이 필요합니다.",
            LanguageCatalog.GERMAN to "Wir brauchen zwei Zugfahrkarten.",
            LanguageCatalog.POLISH to "Potrzebujemy dwóch biletów na pociąg.",
            LanguageCatalog.PORTUGUESE to "Precisamos de duas passagens de trem.",
            LanguageCatalog.RUSSIAN to "Нам нужны два билета на поезд.",
            LanguageCatalog.THAI to "เราต้องการตั๋วรถไฟสองใบ",
            LanguageCatalog.TURKISH to "İki tren biletine ihtiyacımız var.",
            LanguageCatalog.FRENCH to "Nous avons besoin de deux billets de train.",
            LanguageCatalog.HINDI to "हमें ट्रेन के दो टिकट चाहिए।",
            LanguageCatalog.JAPANESE to "電車の切符が二枚必要です。",
            LanguageCatalog.DUTCH to "We hebben twee treinkaartjes nodig.",
        )
        private val LONG_FIXTURE = "The train leaves tomorrow. We need two tickets at five o'clock. ".repeat(40)
        private val LONG_RUSSIAN_FIXTURE = "Поезд отправляется завтра. Нам нужны два билета на пять часов. ".repeat(40)
    }
}
