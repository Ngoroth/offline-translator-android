package com.example.offlinetranslator.core

import kotlinx.coroutines.flow.StateFlow


enum class SpeechModelInstallState {
    MISSING,
    DOWNLOADING,
    VERIFYING,
    READY,
    ERROR,
}

data class SpeechModelStatus(
    val id: String,
    val displayName: String,
    val source: String,
    val license: String,
    val sizeBytes: Long,
    val state: SpeechModelInstallState,
    val downloadedBytes: Long = 0,
    val errorCode: String? = null,
    val role: SpeechModelRole? = null,
    val languages: Set<LanguageId> = emptySet(),
)

/** The repository owns the explicit, integrity-checked installation of speech assets. */
interface SpeechModelRepository : AutoCloseable {
    val models: StateFlow<List<SpeechModelStatus>>

    suspend fun install()

    suspend fun isReady(pair: LanguagePair): Boolean

    override fun close()
}
