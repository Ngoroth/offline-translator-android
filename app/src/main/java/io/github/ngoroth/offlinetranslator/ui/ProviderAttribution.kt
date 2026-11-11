package io.github.ngoroth.offlinetranslator.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.github.ngoroth.offlinetranslator.core.TranslationProviderDescriptor

@Composable
fun ProviderAttribution(provider: TranslationProviderDescriptor?) {
    if (provider != null && provider.attribution.isNotBlank()) {
        Text(provider.attribution)
    }
}
