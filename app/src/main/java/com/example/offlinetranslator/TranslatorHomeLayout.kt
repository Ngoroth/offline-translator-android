package com.example.offlinetranslator

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun TranslatorHomeLayout(
    conversation: Boolean,
    onConversationChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    conversationContent: @Composable () -> Unit,
    textContent: @Composable (homeControls: @Composable () -> Unit) -> Unit,
) {
    val homeControls: @Composable () -> Unit = {
        TranslatorHomeControls(conversation, onConversationChanged, onOpenSettings)
    }
    Column(Modifier.fillMaxSize().then(if (conversation) Modifier.statusBarsPadding() else Modifier)) {
        if (conversation) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { homeControls() }
            Box(Modifier.weight(1f)) { conversationContent() }
        } else {
            textContent(homeControls)
        }
    }
}

@Composable
private fun TranslatorHomeControls(
    conversation: Boolean,
    onConversationChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settingsLabel = stringResource(R.string.settings_title)
    Row(
        modifier = Modifier
            .widthIn(max = 680.dp)
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("home_controls"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
            SegmentedButton(
                selected = conversation,
                onClick = { onConversationChanged(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.mode_conversation)) }
            SegmentedButton(
                selected = !conversation,
                onClick = { onConversationChanged(false) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.mode_text)) }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .testTag("settings_button")
                .semantics { contentDescription = settingsLabel }
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        ) {
            Text(text = "⚙", style = MaterialTheme.typography.titleLarge)
        }
    }
}
