package com.example.offlinetranslator.core

sealed interface TranslationCapability {
    data object Direct : TranslationCapability
    data class Pivot(val via: LanguageId) : TranslationCapability
    data object Unsupported : TranslationCapability
    data object Unknown : TranslationCapability
}

enum class ConfigurationProblem {
    UNKNOWN_LANGUAGE, UNKNOWN_ENGINE, UNSUPPORTED_PAIR,
    UNKNOWN_MODEL, INCOMPATIBLE_MODEL, UNSUPPORTED_VOICE,
    INVALID_SILENCE, INVALID_SPEED,
}

class InvalidConfigurationException(val problem: ConfigurationProblem) : IllegalArgumentException(problem.name)

/** Directness describes the SDK path, not whether that path can execute. */
fun TranslationCapability.requireSupported() {
    if (this == TranslationCapability.Unsupported) {
        throw InvalidConfigurationException(ConfigurationProblem.UNSUPPORTED_PAIR)
    }
}

data class TranslationProviderDescriptor(
    val id: String,
    val displayName: String,
    val provider: String,
    val sdkVersion: String,
    val attribution: String,
    val termsUrl: String,
)
