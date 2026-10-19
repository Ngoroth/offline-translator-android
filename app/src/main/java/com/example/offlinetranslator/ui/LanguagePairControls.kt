package com.example.offlinetranslator.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair

/** Selecting the other participant's language exchanges the whole ordered pair atomically. */
fun LanguagePair.selectLanguage(index: Int, language: LanguageId): LanguagePair = when (index) {
    0 -> when (language) { source -> this; target -> reverse(); else -> copy(source = language) }
    1 -> when (language) { target -> this; source -> reverse(); else -> copy(target = language) }
    else -> this
}

@Composable
fun LanguagePairControls(
    pair: LanguagePair,
    enabled: Boolean,
    firstLabel: String,
    secondLabel: String,
    firstTag: String,
    secondTag: String,
    swapTag: String,
    onPairSelected: (LanguagePair) -> Unit,
    conversationCoverage: Set<LanguageId>? = null,
) {
    var choosing by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(enabled) { if (!enabled) choosing = null }
    val swapDescription = stringResource(R.string.language_pair_swap_description)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.5f
        val first: @Composable (Modifier) -> Unit = { modifier ->
            LanguageButton(firstLabel, pair.source, enabled, firstTag, modifier) { choosing = 0 }
        }
        val second: @Composable (Modifier) -> Unit = { modifier ->
            LanguageButton(secondLabel, pair.target, enabled, secondTag, modifier) { choosing = 1 }
        }
        val swap: @Composable () -> Unit = {
            IconButton(
                onClick = { onPairSelected(pair.reverse()) }, enabled = enabled,
                modifier = Modifier.size(48.dp).testTag(swapTag)
                    .semantics { contentDescription = swapDescription },
            ) { Text("⇄", modifier = Modifier.clearAndSetSemantics {}) }
        }
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.fillMaxWidth())
                swap()
                second(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                first(Modifier.weight(1f))
                swap()
                second(Modifier.weight(1f))
            }
        }
    }
    choosing?.takeIf { enabled }?.let { index ->
        val tag = if (index == 0) firstTag else secondTag
        val selected = if (index == 0) pair.source else pair.target
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        val density = LocalDensity.current
        val resources = LocalResources.current
        AlertDialog(
            onDismissRequest = { choosing = null },
            modifier = Modifier.widthIn(max = 680.dp).testTag("${tag}_chooser"),
            title = {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides density, LocalResources provides resources,
                ) { Text(if (index == 0) firstLabel else secondLabel) }
            },
            text = {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides density, LocalResources provides resources,
                ) {
                    Column(
                        Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp.dp * 0.55f))
                            .verticalScroll(rememberScrollState()).selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (conversationCoverage != null) Text(stringResource(R.string.language_conversation_coverage_explanation))
                        LanguageCatalog.entries.forEach { metadata ->
                            val language = metadata.id
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = selected == language, role = Role.RadioButton, onClick = {
                                        choosing = null
                                        val next = pair.selectLanguage(index, language)
                                        if (next != pair) onPairSelected(next)
                                    }).semantics(mergeDescendants = true) {}
                                    .testTag("${tag}_option_${language.value}").padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == language, onClick = null)
                                Column(Modifier.weight(1f)) {
                                    Text(languageName(language))
                                    if (conversationCoverage != null) Text(
                                        stringResource(if (language in conversationCoverage) R.string.language_conversation_supported else R.string.language_text_only),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides configuration,
                    LocalDensity provides density, LocalResources provides resources,
                ) {
                    TextButton(onClick = { choosing = null }, modifier = Modifier.heightIn(min = 48.dp).testTag("${tag}_close")) {
                        Text(stringResource(R.string.close_action))
                    }
                }
            },
        )
    }
}

@Composable
private fun LanguageButton(label: String, language: LanguageId, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val name = languageName(language)
    OutlinedButton(onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).testTag(tag)
        .semantics { contentDescription = "$label: $name" }) {
        Text(name)
    }
}
