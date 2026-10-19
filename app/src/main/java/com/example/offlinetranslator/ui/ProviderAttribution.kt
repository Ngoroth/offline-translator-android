package com.example.offlinetranslator.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.TranslationProviderDescriptor

@Composable
fun ProviderAttribution(provider: TranslationProviderDescriptor?) {
    if (provider == null) return
    if (provider.id == "mlkit") {
        Image(painterResource(R.drawable.powered_by_google_translate), stringResource(R.string.google_translate_attribution),
            contentScale = ContentScale.Fit, modifier = Modifier.height(18.dp))
    } else if (provider.attribution.isNotBlank()) {
        Text(provider.attribution)
    }
}
