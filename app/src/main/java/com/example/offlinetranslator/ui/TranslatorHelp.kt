package com.example.offlinetranslator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.TranslationProviderDescriptor

object TranslatorHelpTestTags {
    const val ACTION = "translator_help_action"
    const val DIALOG = "translator_help_dialog"
    const val CLOSE = "translator_help_close"
    const val TERMS_ACTION = "translator_help_terms_action"
    const val TERMS_DIALOG = "translator_help_terms_dialog"
    const val TERMS_BACK = "translator_help_terms_back"
}

@Composable
fun TranslatorHelp(
    conversation: Boolean,
    provider: TranslationProviderDescriptor?,
    onOpenUri: ((String) -> Unit)? = null,
) {
    var helpOpen by rememberSaveable { mutableStateOf(false) }
    var termsOpen by rememberSaveable(provider?.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = { termsOpen = false; helpOpen = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TranslatorHelpTestTags.ACTION),
        ) { Text(stringResource(R.string.help_action)) }
    }
    if (!helpOpen) return
    val showingTerms = termsOpen && provider != null
    val dismiss = { if (showingTerms) termsOpen = false else helpOpen = false }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val resources = LocalResources.current
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, LocalResources provides resources) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag(if (showingTerms) TranslatorHelpTestTags.TERMS_DIALOG else TranslatorHelpTestTags.DIALOG),
        title = { Text(stringResource(if (showingTerms) R.string.help_terms_title else R.string.help_title)) },
        text = {
            if (showingTerms) {
                TranslationTerms(provider, onOpenUri)
            } else {
                Column(
                    Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(if (conversation) R.string.help_conversation else R.string.help_text))
                    Text(stringResource(R.string.help_preparation))
                    Text(stringResource(R.string.provider_privacy))
                    if (provider != null && (provider.id == "mlkit" || provider.attribution.isNotBlank() || provider.termsUrl.isNotBlank())) {
                        TextButton(
                            onClick = { termsOpen = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(TranslatorHelpTestTags.TERMS_ACTION),
                        ) { Text(stringResource(R.string.help_terms_action)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = dismiss,
                modifier = Modifier.heightIn(min = 48.dp)
                    .testTag(if (showingTerms) TranslatorHelpTestTags.TERMS_BACK else TranslatorHelpTestTags.CLOSE),
            ) { Text(stringResource(R.string.close_action)) }
        },
    )
    }
}

@Composable
private fun TranslationTerms(
    provider: TranslationProviderDescriptor,
    onOpenUri: ((String) -> Unit)?,
) {
    val uriHandler = LocalUriHandler.current
    val openUri = onOpenUri ?: uriHandler::openUri
    Column(
        Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (provider.id == "mlkit") {
            Text(stringResource(R.string.help_google_translation))
            Text(stringResource(R.string.google_disclaimer))
            TextButton(
                onClick = { openUri("https://translate.google.com") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.google_translate_link)) }
            TextButton(
                onClick = { openUri("https://docs.cloud.google.com/translate/attribution") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.google_attribution_requirements_link)) }
        } else if (provider.attribution.isNotBlank()) {
            Text(provider.attribution)
        }
        if (provider.termsUrl.isNotBlank()) {
            TextButton(
                onClick = { openUri(provider.termsUrl) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(if (provider.id == "mlkit") R.string.usage_terms_link else R.string.provider_terms)) }
        }
    }
}
