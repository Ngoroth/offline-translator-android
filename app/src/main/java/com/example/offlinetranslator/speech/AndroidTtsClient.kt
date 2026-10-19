package com.example.offlinetranslator.speech

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Metadata, no-audio identity control, and explicit local speech cross this platform boundary. */
internal interface TtsClient {
    val engineAvailable: Boolean
    val voices: List<TtsVoice>
    val defaultVoice: TtsVoice?
    val currentVoice: TtsVoice?
    val maximumInputLength: Int
    fun dataAvailability(locale: Locale): Int
    fun setVoice(voice: TtsVoice): Int
    fun configure(speed: Float): Int
    fun setListener(listener: TtsListener): Int
    fun resolveEnginePackage(callerUid: Int): String?
    fun playSilentUtterance(utteranceId: String): Int
    fun speak(text: String, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

internal data class TtsVoice(
    val name: String,
    val locale: Locale,
    val networkRequired: Boolean,
    val notInstalled: Boolean,
)

internal interface TtsListener {
    fun onStart(id: String, callerUid: Int)
    fun onDone(id: String, callerUid: Int)
    fun onError(id: String, code: Int)
    fun onStop(id: String)
    fun onControlSynthesis(id: String)
}

/** Optional test-owned evidence observer; production never collects or persists synthesis buffers. */
internal interface TtsSynthesisObserver {
    fun onBeginSynthesis(id: String, sampleRate: Int, audioFormat: Int, channels: Int)
    fun onAudioAvailable(id: String, audio: ByteArray)
}

/** Calls (including synchronous interruption) serialize with construction on the actual main thread. */
internal interface TtsThread {
    fun <T> call(block: () -> T): T
    fun post(block: () -> Unit)
}

internal class AndroidTtsThread : TtsThread {
    private val handler = Handler(Looper.getMainLooper())
    override fun <T> call(block: () -> T): T {
        if (Looper.myLooper() == handler.looper) return block()
        val task = FutureTask(block)
        check(handler.post(task)) { "Speech platform thread is unavailable" }
        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    // Returning early would falsely claim that stop/release has finished.
                    interrupted = true
                } catch (failure: ExecutionException) {
                    throw requireNotNull(failure.cause)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    override fun post(block: () -> Unit) {
        check(handler.post(block)) { "Speech platform thread is unavailable" }
    }
}

internal class AndroidTtsClient(
    context: Context,
    onInit: (Int) -> Unit,
    private val synthesisObserver: TtsSynthesisObserver? = null,
) : TtsClient {
    private val application = context.applicationContext
    private val native = TextToSpeech(application) { status -> onInit(status) }
    // Only the current voice-selection transaction owns this inventory, never a readiness cache.
    private var nativeVoices: Set<Voice> = emptySet()
    @Volatile private var silentId: String? = null
    private fun Voice.metadata() = TtsVoice(
        name, locale, isNetworkConnectionRequired,
        features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
    )
    override val engineAvailable: Boolean get() = native.engines.orEmpty().isNotEmpty()
    override val voices: List<TtsVoice> get() {
        nativeVoices = native.voices.orEmpty()
        return nativeVoices.map { it.metadata() }
    }
    override val defaultVoice: TtsVoice? get() = native.defaultVoice?.metadata()
    override val currentVoice: TtsVoice? get() = native.voice?.metadata()
    override val maximumInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()
    override fun dataAvailability(locale: Locale): Int = native.isLanguageAvailable(locale)
    override fun setVoice(voice: TtsVoice): Int {
        val installed = nativeVoices.firstOrNull { candidate ->
            candidate.name == voice.name && candidate.locale == voice.locale &&
                candidate.isNetworkConnectionRequired == voice.networkRequired &&
                (candidate.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) == voice.notInstalled
        } ?: return TextToSpeech.ERROR_NOT_INSTALLED_YET
        return native.setVoice(installed)
    }
    override fun configure(speed: Float): Int {
        val attributes = native.setAudioAttributes(speechAttributes())
        if (attributes != TextToSpeech.SUCCESS) return attributes
        return native.setSpeechRate(speed)
    }
    override fun setListener(listener: TtsListener): Int = native.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val callerUid = Binder.getCallingUid()
                listener.onStart(utteranceId, callerUid)
            }
            override fun onDone(utteranceId: String) {
                val callerUid = Binder.getCallingUid()
                listener.onDone(utteranceId, callerUid)
            }
            @Deprecated("Platform compatibility callback")
            override fun onError(utteranceId: String) = listener.onError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String, errorCode: Int) = listener.onError(utteranceId, errorCode)
            override fun onStop(utteranceId: String, interrupted: Boolean) = listener.onStop(utteranceId)
            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                if (utteranceId == silentId) listener.onControlSynthesis(utteranceId)
                synthesisObserver?.onBeginSynthesis(utteranceId, sampleRateInHz, audioFormat, channelCount)
            }
            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                if (utteranceId == silentId) listener.onControlSynthesis(utteranceId)
                synthesisObserver?.onAudioAvailable(utteranceId, audio)
            }
        },
    )
    /** Called after the callback has left its Binder stack; nomination alone never proves identity. */
    override fun resolveEnginePackage(callerUid: Int): String? {
        if (callerUid == Process.myUid() || callerUid < Process.FIRST_APPLICATION_UID) return null
        val manager = application.packageManager
        val packages = manager.getPackagesForUid(callerUid) ?: return null
        if (packages.size != 1) return null
        val actual = packages[0]
        if (actual.isBlank() || actual != native.defaultEngine ||
            native.engines.orEmpty().none { it.name == actual }) return null
        val services = manager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(actual), 0,
        )
        return actual.takeIf {
            services.any { resolved ->
                val service = resolved.serviceInfo
                service != null && service.packageName == actual && service.exported && service.enabled &&
                    service.applicationInfo.enabled && service.applicationInfo.uid == callerUid
            }
        }
    }
    override fun playSilentUtterance(utteranceId: String): Int {
        nativeVoices = emptySet()
        silentId = utteranceId
        return native.playSilentUtterance(0L, TextToSpeech.QUEUE_FLUSH, utteranceId)
    }
    override fun speak(text: String, utteranceId: String): Int {
        silentId = null
        return native.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }
    override fun stop(): Int {
        silentId = null
        nativeVoices = emptySet()
        return native.stop()
    }
    override fun shutdown() {
        silentId = null
        nativeVoices = emptySet()
        native.shutdown()
    }
}


internal interface TtsAudioFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

private fun speechAttributes() = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

internal class AndroidTtsAudioFocus(context: Context) : TtsAudioFocus {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var listener: AudioManager.OnAudioFocusChangeListener? = null
    private var focus: AudioFocusRequest? = null
    override fun request(onLoss: () -> Unit): Boolean {
        val callback = AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) onLoss() }
        listener = callback
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(speechAttributes())
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(callback, Handler(Looper.getMainLooper()))
                .build()
            focus = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(callback, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    override fun abandon() {
        try {
            val result = if (Build.VERSION.SDK_INT >= 26) {
                focus?.let(manager::abandonAudioFocusRequest)
            } else {
                @Suppress("DEPRECATION")
                listener?.let(manager::abandonAudioFocus)
            }
            check(result == null || result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Speech audio focus release failed" }
        } finally {
            focus = null
            listener = null
        }
    }
}
