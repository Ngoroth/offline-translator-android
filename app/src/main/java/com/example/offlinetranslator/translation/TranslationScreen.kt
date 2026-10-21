package com.example.offlinetranslator.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import com.example.offlinetranslator.ui.languageName
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.ui.LanguagePairControls
import com.example.offlinetranslator.ui.configurationProblemMessage
import com.example.offlinetranslator.ui.ProviderAttribution
import com.example.offlinetranslator.ui.TranslatorHelp

object TranslationTestTags {
    const val INPUT = "translation_input"
    const val OUTPUT = "translation_output"
    const val RESULT_TEXT = "translation_result_text"
    const val SWAP = "swap_direction"
    const val PREPARE = "prepare_model"
    const val TRANSLATE = "translate"
    const val RETRY = "retry"
    const val PROGRESS = "operation_progress"
    const val LANGUAGE_FIRST = "translation_language_0"
    const val LANGUAGE_SECOND = "translation_language_1"
    const val LANGUAGE_ERROR = "translation_language_error"
}

@Composable
fun TranslationScreen(
    state: TranslationUiState,
    onInputChanged: (String) -> Unit,
    onPairSelected: (LanguagePair) -> Unit,
    onPrepare: () -> Unit,
    onTranslate: () -> Unit,
    onRetry: () -> Unit,
    homeControls: @Composable () -> Unit,
    onOpenUri: ((String) -> Unit)? = null,
    provider: TranslationProviderDescriptor? = state.provider,
    configurationProblem: ConfigurationProblem? = state.configurationProblem,
    languageSelectionEnabled: Boolean = true,
    languageChanging: Boolean = false,
    languageChangeError: TranslationLanguageChangeError? = null,
    ownerAvailable: Boolean = true,
) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        val blocked = !ownerAvailable || languageChanging || languageChangeError == TranslationLanguageChangeError.OPEN ||
            languageChangeError == TranslationLanguageChangeError.TEARDOWN
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { homeControls() }
                Column(
                    modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                LanguagePairControls(
                    pair = state.direction, enabled = languageSelectionEnabled && !languageChanging &&
                        !state.operationInProgress && languageChangeError != TranslationLanguageChangeError.TEARDOWN &&
                        languageChangeError != TranslationLanguageChangeError.OPEN,
                    firstLabel = stringResource(R.string.language_source), secondLabel = stringResource(R.string.language_target),
                    firstTag = TranslationTestTags.LANGUAGE_FIRST, secondTag = TranslationTestTags.LANGUAGE_SECOND,
                    swapTag = TranslationTestTags.SWAP, onPairSelected = onPairSelected,
                )
                TranslatorHelp(conversation = false, provider = provider, onOpenUri = onOpenUri)
                if (languageChanging) Text(stringResource(R.string.conversation_languages_changing))
                languageChangeError?.let {
                    Text(stringResource(when (it) {
                        TranslationLanguageChangeError.SAVE -> R.string.conversation_language_save_error
                        TranslationLanguageChangeError.OPEN -> R.string.translation_open_error
                        TranslationLanguageChangeError.TEARDOWN -> R.string.language_pair_teardown_error
                    }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(TranslationTestTags.LANGUAGE_ERROR))
                }
                val sourceLabel = stringResource(R.string.language_source)
                val sourceName = languageName(state.direction.source)
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChanged,
                    enabled = !state.operationInProgress && !languageChanging,
                    label = { Text(stringResource(R.string.input_hint)) },
                    isError = state.inputTooLong,
                    supportingText = if (state.inputTooLong) {
                        { Text(stringResource(R.string.error_input_too_long, TranslationEngine.MAX_INPUT_CHARACTERS)) }
                    } else null,
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag(TranslationTestTags.INPUT)
                        .semantics {
                            contentDescription = "${sourceLabel}: $sourceName"
                        },
                )
                ResultPanel(state)
                configurationProblem?.let { Text(configurationProblemMessage(it), color = MaterialTheme.colorScheme.error) }
                StatusPanel(state)
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.error != null) {
                        Button(
                            onClick = onRetry,
                            enabled = !blocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TranslationTestTags.RETRY),
                        ) { Text(stringResource(R.string.retry_action)) }
                    }
                    ProviderAttribution(provider)
                    TranslationActions(state, onPrepare, onTranslate, configurationProblem, blocked)
                }
            }
        }
    }
}


@Composable
private fun ResultPanel(state: TranslationUiState) {
    val targetLabel = stringResource(R.string.language_target)
    val targetName = languageName(state.direction.target)
    val outputModifier = Modifier.fillMaxWidth().testTag(TranslationTestTags.OUTPUT)
        .semantics { contentDescription = "$targetLabel: $targetName" }
    if (state.result.isEmpty()) {
        Text(stringResource(R.string.result_placeholder), modifier = outputModifier,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Surface(modifier = outputModifier, tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.result_label), style = MaterialTheme.typography.labelLarge)
                SelectionContainer {
                    Text(state.result, modifier = Modifier.testTag(TranslationTestTags.RESULT_TEXT),
                        style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: TranslationUiState) {
    if (state.operationInProgress && !state.isPreparing) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp).testTag(TranslationTestTags.PROGRESS),
                strokeWidth = 3.dp,
            )
            Text(
                when {
                    state.modelStatus == ModelUiStatus.CHECKING -> stringResource(R.string.status_checking)
                    state.modelStatus == ModelUiStatus.DOWNLOADING -> stringResource(R.string.status_downloading)
                    state.modelStatus == ModelUiStatus.VERIFYING -> stringResource(R.string.translation_verifying)
                    state.modelStatus == ModelUiStatus.LOADING -> stringResource(R.string.translation_loading)
                    else -> stringResource(R.string.status_translating)
                },
            )
        }
    }

    if (state.isPreparing && state.preparation != null) {
        com.example.offlinetranslator.ui.TranslationPreparation(state.preparation)
    }
    if (state.failure != null) {
        Text(stringResource(com.example.offlinetranslator.ui.translationFailureLabel(state.failure)), color = MaterialTheme.colorScheme.error)
    } else state.error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
    if (state.modelStatus == ModelUiStatus.MISSING && state.error == null) {
        Text(stringResource(if (state.provider?.id == "tencent") R.string.tencent_model_explanation else R.string.model_missing_explanation))
    }
}

@Composable
private fun TranslationActions(
    state: TranslationUiState,
    onPrepare: () -> Unit,
    onTranslate: () -> Unit,
    configurationProblem: ConfigurationProblem?,
    blocked: Boolean,
) {

    if (state.modelStatus == ModelUiStatus.MISSING && state.error == null) {
        Button(
            onClick = onPrepare,
            enabled = !blocked && configurationProblem == null && state.canPrepare,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TranslationTestTags.PREPARE),
        ) { Text(stringResource(R.string.prepare_action)) }
    }

    if (state.modelStatus == ModelUiStatus.READY) {
        Button(
            onClick = onTranslate,
            enabled = !blocked && configurationProblem == null && state.canTranslate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TranslationTestTags.TRANSLATE),
        ) { Text(stringResource(R.string.translate_action)) }
    }
}


@Composable
private fun errorMessage(error: TranslationUiError): String = stringResource(
    when (error) {
        TranslationUiError.WIFI_REQUIRED -> R.string.error_wifi_required
        TranslationUiError.PREPARATION_TIMED_OUT -> R.string.error_preparation_timed_out
        TranslationUiError.PREPARATION_FAILED -> R.string.error_preparation_failed
        TranslationUiError.TRANSLATION_FAILED -> R.string.error_translation_failed
    },
)
