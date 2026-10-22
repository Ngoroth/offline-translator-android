package com.example.offlinetranslator.nemo

import android.os.Looper
import com.example.offlinetranslator.core.SpeechSegment
import com.example.offlinetranslator.core.SpeechTranscriber
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Single-owner stage; the combined recognition owner serializes calls and joins before close. */
class NemoSpeechTranscriber private constructor() : SpeechTranscriber {
    private val pcmBytes = ByteBuffer.allocateDirect(SpeechSegment.MAX_SAMPLE_COUNT * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    private val pcm = pcmBytes.asFloatBuffer()
    private var handle = 0L

    override suspend fun transcribe(segment: SpeechSegment): String {
        requireWorkerThread()
        check(handle != 0L) { "NeMo transcriber is closed" }
        currentCoroutineContext().ensureActive()
        pcm.clear()
        segment.copyNormalizedTo(pcm)
        val bytes = NemoNative.recognize(handle, pcmBytes, segment.sampleCount)
        // The synchronous C call really finished; cancellation revokes its result, not its work.
        currentCoroutineContext().ensureActive()
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    override fun close() {
        val owned = handle
        if (owned == 0L) return
        handle = 0L
        NemoNative.destroy(owned)
    }

    companion object {
        internal fun create(model: File): NemoSpeechTranscriber {
            requireWorkerThread()
            val stage = NemoSpeechTranscriber()
            stage.handle = NemoNative.create(model.absolutePath.encodeToByteArray())
            check(stage.handle != 0L) { "NeMo returned an empty recognizer" }
            try {
                // Newly allocated direct storage is zeroed; a real two-second decode warms weights/graph.
                NemoNative.warm(stage.handle, stage.pcmBytes)
                return stage
            } catch (failure: Throwable) {
                try {
                    stage.close()
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun requireWorkerThread() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "NeMo inference must run off main" }
        }
    }
}
