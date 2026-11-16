package io.github.ngoroth.offlinetranslator.mozilla

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal class MozillaNativeException(val code: String) : RuntimeException(code)

/** Blocking owner. The caller joins accepted work; close waits for the same lock. */
internal class MozillaNativeRuntime : AutoCloseable {
    private val workLock = Any()
    private var handle: Long = createHandle()

    fun load(routes: Array<String>, configPaths: Array<String>) = synchronized(workLock) {
        guarded {
            if (routes.isEmpty() || routes.size > 32 || routes.size != configPaths.size ||
                routes.toSet().size != routes.size
            ) throw MozillaNativeException("runtime_load")
            nativeLoad(requireOpen(), routes.map(::encode).toTypedArray(), configPaths.map(::encode).toTypedArray())
        }
    }

    fun translate(route: String, text: String): String = synchronized(workLock) {
        guarded {
            requireOpen()
            if (text.length > 4096) throw MozillaNativeException("input_limit")
            if (text.isBlank()) throw MozillaNativeException("invalid_encoding")
            val translated = decode(nativeTranslate(handle, encode(route), encode(text)))
            if (translated.length > 4096) throw MozillaNativeException("output_limit")
            if (translated.isBlank()) throw MozillaNativeException("invalid_encoding")
            translated
        }
    }

    override fun close() = synchronized(workLock) { dispose() }

    private fun dispose() {
        val previous = handle
        handle = 0L
        if (previous != 0L) nativeClose(previous)
    }

    private inline fun <T> guarded(action: () -> T): T = try {
        action()
    } catch (failure: MozillaNativeException) {
        dispose()
        throw failure
    } catch (_: OutOfMemoryError) {
        dispose()
        throw MozillaNativeException("memory")
    }

    private fun requireOpen(): Long = handle.takeIf { it != 0L } ?: throw MozillaNativeException("closed")

    private fun encode(value: String): ByteArray {
        if ('\u0000' in value) throw MozillaNativeException("invalid_encoding")
        return try {
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: CharacterCodingException) {
            throw MozillaNativeException("invalid_encoding")
        }
    }

    private fun decode(value: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value)).toString().also {
                if ('\u0000' in it) throw MozillaNativeException("invalid_encoding")
            }
    } catch (_: CharacterCodingException) {
        throw MozillaNativeException("invalid_encoding")
    }

    private external fun nativeCreate(): Long
    private external fun nativeLoad(handle: Long, routes: Array<ByteArray>, configPaths: Array<ByteArray>)
    private external fun nativeTranslate(handle: Long, route: ByteArray, text: ByteArray): ByteArray
    private external fun nativeClose(handle: Long)

    private fun createHandle(): Long = try {
        ensureLibrary()
        nativeCreate()
    } catch (_: OutOfMemoryError) {
        throw MozillaNativeException("memory")
    }

    companion object {
        private val library = lazy {
            try {
                System.loadLibrary("mozilla_jni")
            } catch (_: LinkageError) {
                throw MozillaNativeException("runtime_load")
            }
        }
        private fun ensureLibrary() { library.value }
    }
}
