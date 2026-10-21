package com.example.offlinetranslator.tencent

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal class TencentNativeException(val code: String) : RuntimeException(code)

/** Blocking owner-scoped runtime. The backend supplies its serialized worker and joins cancellation. */
internal class TencentNativeRuntime : AutoCloseable {
    private val workLock = Any()
    private val handleLock = Any()
    private var handle = nativeCreate()

    fun load(modelPath: String) = synchronized(workLock) {
        nativeLoad(openHandle(), encode(modelPath))
    }

    fun translate(targetLanguage: String, text: String): String = synchronized(workLock) {
        if (text.length > 4096) throw TencentNativeException("input_limit")
        decode(nativeTranslate(openHandle(), encode(targetLanguage), encode(text)))
    }

    fun resetCancellation() = synchronized(handleLock) {
        nativeResetCancellation(requireOpen())
    }

    // A separate lock keeps the handle alive without waiting behind native inference.
    fun cancel() = synchronized(handleLock) {
        if (handle != 0L) nativeCancel(handle)
    }

    override fun close() = synchronized(workLock) {
        synchronized(handleLock) {
            if (handle != 0L) {
                nativeClose(handle)
                handle = 0L
            }
        }
    }

    private fun openHandle(): Long = synchronized(handleLock) { requireOpen() }
    private fun requireOpen(): Long = handle.takeIf { it != 0L } ?: throw TencentNativeException("closed")

    private fun encode(value: String): ByteArray = try {
        val bytes = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        ByteArray(bytes.remaining()).also { bytes.get(it) }
    } catch (_: CharacterCodingException) {
        throw TencentNativeException("invalid_encoding")
    }

    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw TencentNativeException("invalid_encoding")
    }

    private external fun nativeCreate(): Long
    private external fun nativeLoad(handle: Long, path: ByteArray)
    private external fun nativeTranslate(handle: Long, target: ByteArray, text: ByteArray): ByteArray
    private external fun nativeResetCancellation(handle: Long)
    private external fun nativeCancel(handle: Long)
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("tencent_jni") }
    }
}
