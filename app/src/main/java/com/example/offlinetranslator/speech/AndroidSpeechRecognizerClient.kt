package com.example.offlinetranslator.speech

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.coroutineContext
import java.io.Closeable

/** The only substituted boundary in host tests is the external Android service/FD. */
internal interface AndroidSpeechRecognizerClient {
    fun permissionGranted(): Boolean
    suspend fun create(listener: AndroidRecognitionListener): AndroidRecognitionSession
    fun openAudio(): AndroidRecognitionAudio
    fun serviceStatus(): AndroidRecognitionServiceStatus
    fun observePackage(onChanged: () -> Unit)
    fun stopObservingPackage()
}

internal interface AndroidRecognitionAudio : Closeable {
    suspend fun write(pcm: ShortArray)
}
internal interface AndroidRecognitionSession {
    fun support(languages: List<String>, audio: AndroidRecognitionAudio, callback: (AndroidRecognitionSupport?, Int?) -> Unit)
    fun download(languages: List<String>, audio: AndroidRecognitionAudio, listener: AndroidModelDownloadListener)
    fun start(languages: List<String>, audio: AndroidRecognitionAudio)
    fun cancel()
    suspend fun destroy()
}
internal data class AndroidRecognitionSupport(
    val installed: List<String>, val pending: List<String>, val supported: List<String>, val online: List<String>,
)
internal interface AndroidRecognitionListener {
    fun ready() = Unit
    fun language(tag: String?, confidence: Int?, switchResult: Int?)
    fun segment(primary: String?)
    fun results(primary: String?)
    fun terminal()
    fun error(code: Int)
}
internal interface AndroidModelDownloadListener {
    fun progress()
    fun scheduled()
    fun success()
    fun error(code: Int)
}

internal class AndroidPlatformSpeechRecognizerClient(context: Context, private val selectedService: String?) : AndroidSpeechRecognizerClient {
    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    override fun serviceStatus(): AndroidRecognitionServiceStatus = selectedService?.let {
        AndroidRecognitionServices.status(context, it)
    } ?: AndroidRecognitionServiceStatus(
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognitionReadiness.READY
        else SpeechRecognitionReadiness.ENGINE_UNAVAILABLE,
    )
    override fun observePackage(onChanged: () -> Unit) {
        val component = selectedService?.let(ComponentName::unflattenFromString) ?: return
        if (receiver != null) return
        val observer = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == component.packageName) onChanged()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            addDataSchemeSpecificPart(component.packageName, android.os.PatternMatcher.PATTERN_LITERAL)
        }
        // These protected system actions cannot be sent by an ordinary application.
        context.registerReceiver(observer, filter, Context.RECEIVER_NOT_EXPORTED)
        receiver = observer
    }
    override fun stopObservingPackage() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }
    override fun permissionGranted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    override fun openAudio(): AndroidRecognitionAudio = PipeAudio()
    override suspend fun create(listener: AndroidRecognitionListener): AndroidRecognitionSession {
        // All factory/session methods are called on the application's main dispatcher by the owner.
        check(serviceStatus().readiness == SpeechRecognitionReadiness.READY) { "Recognition service is not qualified" }
        val recognizer = selectedService?.let {
            SpeechRecognizer.createSpeechRecognizer(context, requireNotNull(ComponentName.unflattenFromString(it)))
        } ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = listener.ready()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = listener.error(error)
                override fun onResults(results: Bundle?) = listener.results(primary(results))
                override fun onSegmentResults(segmentResults: Bundle) = listener.segment(primary(segmentResults))
                override fun onEndOfSegmentedSession() = listener.terminal()
                override fun onLanguageDetection(results: Bundle) = listener.language(
                    results.getString(SpeechRecognizer.DETECTED_LANGUAGE),
                    if (results.containsKey(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL)) results.getInt(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL) else null,
                    if (results.containsKey(SpeechRecognizer.LANGUAGE_SWITCH_RESULT)) results.getInt(SpeechRecognizer.LANGUAGE_SWITCH_RESULT) else null,
                )
            })
            return Session(recognizer)
        } catch (failure: Throwable) {
            try { destroyOnMain(recognizer) } catch (release: Throwable) { failure.addSuppressed(release) }
            throw failure
        }
    }
    private fun primary(results: Bundle?): String? = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    private inner class Session(private val recognizer: SpeechRecognizer) : AndroidRecognitionSession {
        override fun support(languages: List<String>, audio: AndroidRecognitionAudio, callback: (AndroidRecognitionSupport?, Int?) -> Unit) {
            recognizer.checkRecognitionSupport(intent(languages, audio), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    callback(AndroidRecognitionSupport(
                        recognitionSupport.installedOnDeviceLanguages.take(MAX_LANGUAGES),
                        recognitionSupport.pendingOnDeviceLanguages.take(MAX_LANGUAGES),
                        recognitionSupport.supportedOnDeviceLanguages.take(MAX_LANGUAGES),
                        recognitionSupport.onlineLanguages.take(MAX_LANGUAGES),
                    ), null)
                }
                override fun onError(error: Int) = callback(null, error)
            })
        }
        override fun download(languages: List<String>, audio: AndroidRecognitionAudio, listener: AndroidModelDownloadListener) {
            recognizer.triggerModelDownload(intent(languages, audio), context.mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = listener.progress()
                override fun onScheduled() = listener.scheduled()
                override fun onSuccess() = listener.success()
                override fun onError(error: Int) = listener.error(error)
            })
        }
        override fun start(languages: List<String>, audio: AndroidRecognitionAudio) = recognizer.startListening(intent(languages, audio))
        override fun cancel() = recognizer.cancel()
        override suspend fun destroy() = destroyOnMain(recognizer)
    }
    private suspend fun destroyOnMain(recognizer: SpeechRecognizer) = withContext(NonCancellable) {
        try { recognizer.destroy() } finally {
            // Public SDK cancel/destroy enqueue MAIN work. This ordinary MAIN marker observes that
            // local dispatch before another request, not remote acknowledgement or pending unbind.
            suspendCoroutine<Unit> { continuation ->
                check(mainHandler.post { continuation.resume(Unit) }) { "Main SDK cleanup dispatch unavailable" }
            }
        }
    }
    private fun intent(languages: List<String>, audio: AndroidRecognitionAudio): Intent {
        require(languages.size == 2 && languages.distinct().size == 2)
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languages.first())
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, (audio as PipeAudio).read)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SpeechSegment.SAMPLE_RATE_HZ)
            .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
            .putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE)
            .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(languages))
            .putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(languages))
            .apply { if (selectedService != null) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
    }
    internal class PipeAudio : AndroidRecognitionAudio {
        private val pipe = ParcelFileDescriptor.createReliablePipe()
        val read = pipe[0]
        private val write = pipe[1]
        init {
            try {
                val flags = Os.fcntlInt(write.fileDescriptor, OsConstants.F_GETFL, 0)
                Os.fcntlInt(write.fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            } catch (failure: Throwable) {
                try { close() } catch (release: Throwable) { failure.addSuppressed(release) }
                throw failure
            }
        }
        override suspend fun write(pcm: ShortArray) {
            val buffer = ByteArray(4096)
            val poll = StructPollfd().apply {
                fd = write.fileDescriptor
                events = OsConstants.POLLOUT.toShort()
            }
            val polls = arrayOf(poll)
            try {
                var offset = 0
                while (offset < pcm.size) {
                    coroutineContext.ensureActive()
                    val count = minOf(buffer.size / 2, pcm.size - offset)
                    for (index in 0 until count) {
                        val sample = pcm[offset + index].toInt()
                        buffer[index * 2] = sample.toByte()
                        buffer[index * 2 + 1] = (sample shr 8).toByte()
                    }
                    var written = 0
                    while (written < count * 2) {
                        coroutineContext.ensureActive()
                        try {
                            written += Os.write(write.fileDescriptor, buffer, written, count * 2 - written)
                        } catch (failure: ErrnoException) {
                            when (failure.errno) {
                                OsConstants.EAGAIN -> {
                                    // Only pipe backpressure waits: never a blocking write or inference pacing.
                                    // A retained provider read FD cannot prevent cancellation within this bound.
                                    try { Os.poll(polls, 100) } catch (interrupted: ErrnoException) {
                                        if (interrupted.errno != OsConstants.EINTR) throw interrupted
                                    }
                                }
                                OsConstants.EINTR -> Unit
                                else -> throw failure
                            }
                        }
                    }
                    offset += count
                }
            } finally {
                write.close()
            }
        }
        override fun close() {
            var failure: Throwable? = null
            try { read.close() } catch (caught: Throwable) { failure = caught }
            try { write.close() } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure.addSuppressed(caught)
            }
            failure?.let { throw it }
        }
    }
    companion object { private const val MAX_LANGUAGES = 128 }
}
