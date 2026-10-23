package com.example.offlinetranslator.core

sealed class PreparationFailure(message: String, cause: Throwable? = null) : TranslationFailure(message, cause) {
    class WifiRequired(cause: Throwable? = null) :
        PreparationFailure("A validated Wi-Fi connection is required", cause)

    class PreparationTimedOut(cause: Throwable? = null) :
        PreparationFailure("Model preparation timed out", cause)

    class ProviderFailure(cause: Throwable) :
        PreparationFailure("The translation model could not be prepared", cause)
}
