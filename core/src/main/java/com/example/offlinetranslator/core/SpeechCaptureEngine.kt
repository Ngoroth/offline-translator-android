package com.example.offlinetranslator.core

interface SpeechCaptureListener {
    /** Must return promptly; the capture implementation may call this from its reader thread. */
    fun onSegment(segment: SpeechSegment)

    /** A failed source must stop reading and release its microphone before reporting the error. */
    fun onFailure(reason: SpeechCaptureFailure)
}

enum class SpeechCaptureFailure {
    PERMISSION_DENIED,
    MICROPHONE_UNAVAILABLE,
    READ_FAILED,
}

class SpeechCaptureException(val reason: SpeechCaptureFailure, cause: Throwable? = null) :
    RuntimeException(reason.name, cause)

/** Owns one foreground microphone session. Implementations must release the mic on stop/close. */
interface SpeechCaptureEngine : AutoCloseable {
    suspend fun start(listener: SpeechCaptureListener)

    /** Stops microphone use synchronously when the owner leaves the foreground. */
    fun interruptCurrent()

    /** Stops capture and returns the final partial utterance once when [flush] is true. */
    suspend fun stop(flush: Boolean): SpeechSegment?

    override fun close()
    suspend fun closeAndJoin() {
        interruptCurrent()
        stop(flush = false)
        close()
    }
}
