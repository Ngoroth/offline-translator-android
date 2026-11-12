package io.github.ngoroth.offlinetranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ngoroth.offlinetranslator.R
import io.github.ngoroth.offlinetranslator.core.*

object TranslationPreparationTestTags {
    const val PROGRESS = "translation_preparation_progress"
}

/** Shared provider-neutral preparation rendering for both routes. */
@Composable
fun TranslationPreparation(state: TranslationPreparationState) {
    val label = stringResource(when (state.phase) {
        TranslationPreparationPhase.CHECKING -> R.string.status_checking
        TranslationPreparationPhase.MISSING -> R.string.model_missing_explanation
        TranslationPreparationPhase.DOWNLOADING -> R.string.translation_downloading
        TranslationPreparationPhase.VERIFYING -> R.string.translation_verifying
        TranslationPreparationPhase.LOADING -> R.string.translation_loading
        TranslationPreparationPhase.READY -> R.string.conversation_resource_ready
        TranslationPreparationPhase.FAILED -> R.string.error_preparation_failed
    })
    state.lastFailure?.let {
        Text(stringResource(translationFailureLabel(it)), color = MaterialTheme.colorScheme.error)
    } ?: when (state.phase) {
        TranslationPreparationPhase.CHECKING, TranslationPreparationPhase.DOWNLOADING,
        TranslationPreparationPhase.VERIFYING, TranslationPreparationPhase.LOADING -> {
            val downloading = state.phase == TranslationPreparationPhase.DOWNLOADING
            PreparationProgress(
                label = label,
                downloadedBytes = if (downloading) state.downloadedBytes?.toDouble() else null,
                totalBytes = if (downloading) state.totalBytes?.toDouble() else null,
                modifier = Modifier.testTag(TranslationPreparationTestTags.PROGRESS),
            )
        }
        else -> Text(label)
    }
}

/** Stage-local progress; a completed transfer does not imply prepared resources. */
@Composable
internal fun PreparationProgress(
    label: String,
    downloadedBytes: Double? = null,
    totalBytes: Double? = null,
    modifier: Modifier = Modifier,
) {
    val total = totalBytes
    val downloaded = downloadedBytes
    val bounded = if (total != null && total > 0 && downloaded != null && downloaded >= 0)
        downloaded.coerceIn(0.0, total) else null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label)
        if (bounded != null && total != null) {
            Text(stringResource(R.string.preparation_download_percent, (bounded * 100 / total).toInt()))
            LinearProgressIndicator(
                progress = { (bounded / total).toFloat() },
                modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
                gapSize = 0.dp,
            )
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
