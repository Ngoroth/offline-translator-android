package com.example.offlinetranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.*

/** Shared provider-neutral preparation rendering for both routes. */
@Composable
fun TranslationPreparation(state: TranslationPreparationState) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.lastFailure?.let { Text(stringResource(translationFailureLabel(it)), color = MaterialTheme.colorScheme.error) }
            ?: Text(stringResource(when (state.phase) {
                TranslationPreparationPhase.CHECKING -> R.string.status_checking
                TranslationPreparationPhase.MISSING -> R.string.model_missing_explanation
                TranslationPreparationPhase.DOWNLOADING -> R.string.status_downloading
                TranslationPreparationPhase.VERIFYING -> R.string.translation_verifying
                TranslationPreparationPhase.LOADING -> R.string.translation_loading
                TranslationPreparationPhase.READY -> R.string.conversation_resource_ready
                TranslationPreparationPhase.FAILED -> R.string.error_preparation_failed
            }))
        if (state.phase in setOf(TranslationPreparationPhase.CHECKING, TranslationPreparationPhase.VERIFYING,
                TranslationPreparationPhase.LOADING) ||
            (state.phase == TranslationPreparationPhase.DOWNLOADING && state.totalBytes == null)) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        val downloaded = state.downloadedBytes
        val total = state.totalBytes
        if (state.phase == TranslationPreparationPhase.DOWNLOADING && downloaded != null && total != null) {
            LinearProgressIndicator(progress = { (downloaded.toDouble() / total).toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.translation_download_bytes, downloaded, total))
        }
    }
}

fun translationFailureLabel(failure: TranslationFailure): Int = when (failure) {
    is PreparationFailure.WifiRequired -> R.string.error_wifi_required
    is PreparationFailure.PreparationTimedOut -> R.string.translation_timeout
    is PreparationFailure.ProviderFailure -> R.string.error_preparation_failed
    is TranslationFailure.ModelMissing, is TranslationFailure.ArtifactCorrupt -> R.string.translation_model_repair
    is TranslationFailure.Storage -> R.string.translation_storage_error
    is TranslationFailure.RuntimeLoad -> R.string.translation_runtime_error
    is TranslationFailure.Memory -> R.string.translation_memory_error
    is TranslationFailure.InputLimit, is TranslationFailure.TokenLimit, is TranslationFailure.OutputLimit -> R.string.translation_limit_error
    is TranslationFailure.InvalidEncoding -> R.string.translation_encoding_error
}
