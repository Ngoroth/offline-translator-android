package com.example.offlinetranslator.core

/** Provider-independent failures safe to map to localized repair actions. Never carries source text. */
sealed class TranslationFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ModelMissing : TranslationFailure("Translation model is missing")
    class ArtifactCorrupt : TranslationFailure("Translation model integrity verification failed")
    class Storage(cause: Throwable? = null) : TranslationFailure("Translation model storage or transfer failed", cause)
    class RuntimeLoad(cause: Throwable? = null) : TranslationFailure("Translation runtime could not load", cause)
    class Memory(cause: Throwable? = null) : TranslationFailure("Insufficient memory for local translation", cause)
    class InputLimit : TranslationFailure("Translation input exceeds the character limit")
    class TokenLimit : TranslationFailure("Translation exceeds the context or generation token limit")
    class OutputLimit : TranslationFailure("Translation exceeds the output character limit")
    class InvalidEncoding : TranslationFailure("Translation input contains invalid Unicode")
}
