package com.example.offlinetranslator.nemo

import java.nio.ByteBuffer

internal object NemoNative {
    init {
        System.loadLibrary("nemo_speech_jni")
    }

    external fun create(modelPathUtf8: ByteArray): Long
    external fun warm(handle: Long, pcm: ByteBuffer)
    external fun recognize(handle: Long, pcm: ByteBuffer, sampleCount: Int): ByteArray
    external fun destroy(handle: Long)
}
