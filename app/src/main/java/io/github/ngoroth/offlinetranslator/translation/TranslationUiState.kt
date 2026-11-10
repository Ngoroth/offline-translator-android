package io.github.ngoroth.offlinetranslator.translation

import io.github.ngoroth.offlinetranslator.core.ConfigurationProblem
import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.TranslationEngine
import io.github.ngoroth.offlinetranslator.core.TranslationProviderDescriptor
import io.github.ngoroth.offlinetranslator.core.TranslationFailure
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationState

enum class ModelUiStatus { CHECKING, MISSING, DOWNLOADING, VERIFYING, LOADING, READY }
enum class TranslationLanguageChangeError { SAVE, OPEN, TEARDOWN }

enum class TranslationUiError {
    WIFI_REQUIRED,
    PREPARATION_TIMED_OUT,
    PREPARATION_FAILED,
    TRANSLATION_FAILED,
}

data class TranslationUiState(
    val direction: LanguagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
    val input: String = "",
    val inputTooLong: Boolean = false,
    val result: String = "",
    val modelStatus: ModelUiStatus = ModelUiStatus.CHECKING,
    val isTranslating: Boolean = false,
    val error: TranslationUiError? = null,
    val configurationProblem: ConfigurationProblem? = null,
    val provider: TranslationProviderDescriptor? = null,
    val preparation: TranslationPreparationState? = null,
    val isPreparing: Boolean = false,
    val failure: TranslationFailure? = null,
) {
    val operationInProgress: Boolean
        get() = isPreparing || modelStatus == ModelUiStatus.CHECKING ||
            modelStatus == ModelUiStatus.DOWNLOADING || modelStatus == ModelUiStatus.VERIFYING ||
            modelStatus == ModelUiStatus.LOADING || isTranslating

    val canPrepare: Boolean
        get() = configurationProblem == null && modelStatus == ModelUiStatus.MISSING && !operationInProgress

    val canTranslate: Boolean
        get() = configurationProblem == null && modelStatus == ModelUiStatus.READY &&
            !inputTooLong && input.length <= TranslationEngine.MAX_INPUT_CHARACTERS &&
            input.isNotBlank() && !operationInProgress
}
