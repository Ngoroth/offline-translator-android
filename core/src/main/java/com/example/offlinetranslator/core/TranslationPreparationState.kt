package com.example.offlinetranslator.core

enum class TranslationPreparationPhase { CHECKING, MISSING, DOWNLOADING, VERIFYING, LOADING, READY, FAILED }

/** Byte counts are absent when the provider cannot report them; READY includes initialization. */
data class TranslationPreparationState(
    val phase: TranslationPreparationPhase = TranslationPreparationPhase.CHECKING,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val lastFailure: TranslationFailure? = null,
) {
    init {
        require(downloadedBytes == null || downloadedBytes >= 0)
        require(totalBytes == null || totalBytes > 0)
        require(downloadedBytes == null || totalBytes == null || downloadedBytes <= totalBytes)
        require((phase == TranslationPreparationPhase.FAILED) == (lastFailure != null))
    }
}
