package com.example.offlinetranslator.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.ConversationFailure
import com.example.offlinetranslator.core.ConversationPhase
import com.example.offlinetranslator.core.ConversationState
import com.example.offlinetranslator.core.ConversationTranscript
import com.example.offlinetranslator.core.ConversationTranscriptStatus
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechOutputReadiness
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import com.example.offlinetranslator.ui.LanguagePairControls
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.ui.languageName
import com.example.offlinetranslator.ui.configurationProblemMessage
import com.example.offlinetranslator.ui.ProviderAttribution
import com.example.offlinetranslator.ui.TranslatorHelp
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechModelStatus

object ConversationTestTags {
    const val PREPARE = "conversation_prepare"
    const val SESSION_ACTION = "conversation_session_action"
    const val CONTENT = "conversation_content"
    const val CURRENT_TURN = "conversation_current_turn"
    const val PREVIOUS_TURN = "conversation_previous_turn"
    const val EMPTY_STATE = "conversation_empty_state"
    const val PREPARATION = "conversation_preparation"
    const val PREPARATION_PROGRESS = "conversation_preparation_progress"
    const val SESSION_ERROR = "conversation_session_error"
    const val DROPPED = "conversation_dropped"
    const val PERMISSION_ERROR = "conversation_permission_error"
    const val LANGUAGE_FIRST = "conversation_language_0"
    const val LANGUAGE_SECOND = "conversation_language_1"
    const val LANGUAGE_CHANGE_ERROR = "conversation_language_change_error"
    const val LANGUAGE_SWAP = "conversation_language_swap"
    const val OPEN_TEXT = "conversation_open_text"
    const val OUTPUT_READINESS = "conversation_output_readiness"
    const val INSTALL_VOICES = "conversation_install_voices"
    const val RECHECK = "conversation_recheck"
    const val INSTALL_ERROR = "conversation_install_error"
    const val RECOGNITION_READINESS = "conversation_recognition_readiness"
    const val RECHECK_RECOGNITION = "conversation_recheck_recognition"
    const val OPEN_ADVANCED_SETTINGS = "conversation_open_advanced_settings"
    const val INSTALL_RECOGNITION_DATA = "conversation_install_recognition_data"
    const val RECOGNITION_INSTALL_ERROR = "conversation_recognition_install_error"
    const val PHASE = "conversation_phase"
}



@Composable
fun ConversationScreen(
    state: ConversationState,
    models: List<SpeechModelStatus>,
    preparation: ConversationPreparation,
    ready: Boolean,
    permissionDenied: Boolean,
    onPrepare: () -> Unit,
    onSessionAction: () -> Unit,
    provider: TranslationProviderDescriptor? = null,
    configurationProblem: ConfigurationProblem? = null,
    pair: LanguagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
    conversationCoverage: Set<LanguageId> = emptySet(),
    languageSelectionEnabled: Boolean = true,
    languageChanging: Boolean = false,
    languageChangeError: ConversationLanguageChangeError? = null,
    onPairSelected: (LanguagePair) -> Unit = {},
    onOpenText: () -> Unit = {},
    systemOutput: Boolean = false,
    outputReadiness: SpeechOutputReadiness? = null,
    canInstallVoiceData: Boolean = false,
    installError: Boolean = false,
    onInstallVoiceData: () -> Unit = {},
    onRecheck: () -> Unit = {},
    ownerAvailable: Boolean = true,
    systemRecognition: Boolean = false,
    recognitionReadiness: SpeechRecognitionReadiness? = null,
    canChooseLocalRecognition: Boolean = false,
    onOpenAdvancedSettings: () -> Unit = {},
    translationPreparation: com.example.offlinetranslator.core.TranslationPreparationState? = null,
    preparationFailure: com.example.offlinetranslator.core.TranslationFailure? = null,
    canInstallRecognitionData: Boolean = false,
    recognitionInstallError: Boolean = false,
    onInstallRecognitionData: () -> Unit = {},
    speechRecognitionService: String? = null,
) {
    val preparing = preparation == ConversationPreparation.CHECKING ||
        preparation == ConversationPreparation.INSTALLING || preparation == ConversationPreparation.TRANSLATION
    val languageBlocked = languageChangeError == ConversationLanguageChangeError.TEARDOWN ||
        languageChangeError == ConversationLanguageChangeError.OPEN
    val canEditLanguages = languageSelectionEnabled && !languageChanging && !languageBlocked && !preparing
    val languages = listOf(pair.source, pair.target)
    val current = state.transcripts.lastOrNull()
    val previous = state.transcripts.getOrNull(state.transcripts.lastIndex - 1)
    val scroll = rememberScrollState()
    LaunchedEffect(current?.id) { scroll.scrollTo(0) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val emptyHeight = (maxHeight - 180.dp).coerceIn(0.dp, 320.dp)
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll).testTag(ConversationTestTags.CONTENT),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
            Column(
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LanguagePairControls(
                    pair = pair, enabled = canEditLanguages,
                    firstLabel = stringResource(R.string.language_participant_first),
                    secondLabel = stringResource(R.string.language_participant_second),
                    firstTag = ConversationTestTags.LANGUAGE_FIRST, secondTag = ConversationTestTags.LANGUAGE_SECOND,
                    swapTag = ConversationTestTags.LANGUAGE_SWAP, onPairSelected = onPairSelected,
                    conversationCoverage = conversationCoverage,
                )
                TranslatorHelp(conversation = true, provider = provider)
                if (languageChanging) {
                    Text(stringResource(R.string.conversation_languages_changing))
                }
                languageChangeError?.let {
                    Text(
                        stringResource(when (it) {
                            ConversationLanguageChangeError.SAVE -> R.string.conversation_language_save_error
                            ConversationLanguageChangeError.OPEN -> R.string.conversation_open_error
                            ConversationLanguageChangeError.TEARDOWN -> R.string.conversation_language_teardown_error
                        }),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ConversationTestTags.LANGUAGE_CHANGE_ERROR),
                    )
                }
                if (systemRecognition && recognitionReadiness != SpeechRecognitionReadiness.READY) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        recognitionReadiness?.let {
                            Text(stringResource(recognitionReadinessLabel(it, speechRecognitionService != null)),
                                modifier = Modifier.testTag(ConversationTestTags.RECOGNITION_READINESS))
                        }
                        if (canInstallRecognitionData) {
                            Text(stringResource(R.string.conversation_recognition_install_hint))
                            TextButton(onInstallRecognitionData,
                                enabled = ownerAvailable && !preparing && !languageChanging && !languageBlocked && !state.sessionActive,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.INSTALL_RECOGNITION_DATA)) {
                                Text(stringResource(R.string.conversation_install_recognition_data))
                            }
                        }
                        if (recognitionInstallError) Text(stringResource(R.string.conversation_recognition_install_error),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag(ConversationTestTags.RECOGNITION_INSTALL_ERROR))
                        TextButton(onRecheck,
                            enabled = ownerAvailable && !preparing && !languageChanging && !languageBlocked && !state.sessionActive,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.RECHECK_RECOGNITION)) {
                            Text(stringResource(R.string.conversation_recheck_recognition))
                        }
                        if (canChooseLocalRecognition || recognitionReadiness == SpeechRecognitionReadiness.INCOMPATIBLE_SERVICE ||
                            recognitionReadiness == SpeechRecognitionReadiness.ENGINE_UNAVAILABLE) {
                            TextButton(onOpenAdvancedSettings,
                                enabled = !preparing && !languageChanging && !languageBlocked && !state.sessionActive,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.OPEN_ADVANCED_SETTINGS)) {
                                Text(stringResource(R.string.conversation_recognition_settings_action))
                            }
                        }
                    }
                }
                if (permissionDenied) {
                    Text(stringResource(R.string.conversation_permission_denied), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ConversationTestTags.PERMISSION_ERROR))
                }
                if (configurationProblem != null || installError ||
                    (systemOutput && outputReadiness != null && outputReadiness != SpeechOutputReadiness.READY)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (configurationProblem != null) {
                            Text(
                                if (pair.source !in conversationCoverage || pair.target !in conversationCoverage)
                                    stringResource(R.string.conversation_pair_unavailable)
                                else configurationProblemMessage(configurationProblem),
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                            TextButton(onOpenText, modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.OPEN_TEXT)) {
                                Text(stringResource(R.string.conversation_open_text))
                            }
                        } else if (systemOutput && outputReadiness != null && outputReadiness != SpeechOutputReadiness.READY) {
                            Text(stringResource(when (outputReadiness) {
                                SpeechOutputReadiness.MISSING_LOCAL_VOICE -> R.string.system_voice_missing
                                SpeechOutputReadiness.ENGINE_UNAVAILABLE -> R.string.system_engine_unavailable
                                SpeechOutputReadiness.INITIALIZATION_FAILED -> R.string.system_engine_initialization_failed
                                SpeechOutputReadiness.READY -> R.string.conversation_resource_ready
                            }), modifier = Modifier.testTag(ConversationTestTags.OUTPUT_READINESS), textAlign = TextAlign.Center)
                            if (canInstallVoiceData) TextButton(onInstallVoiceData,
                                enabled = !preparing && !languageChanging && !languageBlocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.INSTALL_VOICES)) {
                                Text(stringResource(R.string.system_install_voices))
                            }
                            TextButton(onRecheck, enabled = !preparing && !languageChanging && !languageBlocked,
                                modifier = Modifier.heightIn(min = 48.dp).testTag(ConversationTestTags.RECHECK)) {
                                Text(stringResource(R.string.system_recheck))
                            }
                        }
                        if (installError) Text(stringResource(R.string.system_voice_install_error),
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ConversationTestTags.INSTALL_ERROR))
                    }
                }
                state.failure?.let { failure ->
                    Text(stringResource(state.translationFailure?.let { typed ->
                        com.example.offlinetranslator.ui.translationFailureLabel(typed)
                    } ?: failureLabel(failure)), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(ConversationTestTags.SESSION_ERROR))
                }
                if (state.droppedSegments > 0) {
                    Text(stringResource(R.string.conversation_dropped, state.droppedSegments),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ConversationTestTags.DROPPED))
                }
                if (configurationProblem == null && (!ready ||
                    (systemRecognition && recognitionReadiness != SpeechRecognitionReadiness.READY) ||
                    preparing || preparation == ConversationPreparation.ERROR)) {
                    Card(Modifier.fillMaxWidth().testTag(ConversationTestTags.PREPARATION)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(preparationLabel(preparation)), style = MaterialTheme.typography.bodyMedium,
                                color = if (preparation == ConversationPreparation.ERROR) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface)
                            if (provider?.id == "tencent" && !preparing) Text(stringResource(R.string.tencent_model_explanation))
                            if (translationPreparation != null && (preparation == ConversationPreparation.TRANSLATION ||
                                translationPreparation.phase == com.example.offlinetranslator.core.TranslationPreparationPhase.VERIFYING ||
                                translationPreparation.phase == com.example.offlinetranslator.core.TranslationPreparationPhase.FAILED)) {
                                com.example.offlinetranslator.ui.TranslationPreparation(translationPreparation)
                            } else preparationFailure?.let {
                                Text(stringResource(com.example.offlinetranslator.ui.translationFailureLabel(it)), color = MaterialTheme.colorScheme.error)
                            }
                            if (preparation == ConversationPreparation.INSTALLING) {
                                var total = 0L
                                var downloaded = 0L
                                var downloading = false
                                models.forEach { model ->
                                    if (model.role == SpeechModelRole.VAD || model.role == SpeechModelRole.LANGUAGE_ID ||
                                        ((model.role == SpeechModelRole.ASR || model.role == SpeechModelRole.TTS) &&
                                            languages.any { it in model.languages })) {
                                        total += model.sizeBytes
                                        downloaded += if (model.state == SpeechModelInstallState.READY) model.sizeBytes else model.downloadedBytes
                                        downloading = downloading || model.state == SpeechModelInstallState.DOWNLOADING
                                    }
                                }
                                if (downloading && total > 0) {
                                    Text(stringResource(R.string.conversation_download_progress,
                                        (downloaded.coerceIn(0, total) * 100 / total).toInt()),
                                        modifier = Modifier.testTag(ConversationTestTags.PREPARATION_PROGRESS))
                                }
                            }
                            Button(
                                onClick = onPrepare,
                                enabled = ownerAvailable && !preparing && !state.sessionActive &&
                                    recognitionReadiness != SpeechRecognitionReadiness.UNSUPPORTED &&
                                    recognitionReadiness != SpeechRecognitionReadiness.INCOMPATIBLE_SERVICE &&
                                    recognitionReadiness != SpeechRecognitionReadiness.ENGINE_UNAVAILABLE &&
                                    !languageChanging && !languageBlocked,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(ConversationTestTags.PREPARE),
                            ) { Text(stringResource(R.string.conversation_prepare)) }
                        }
                    }
                }
                if (current != null) {
                    TranscriptCard(current, primary = true)
                    previous?.let { TranscriptCard(it, primary = false) }
                } else {
                    val recoveryVisible = !ready || preparing || configurationProblem != null || permissionDenied ||
                        languageChangeError != null || installError || state.failure != null ||
                        (systemOutput && outputReadiness != SpeechOutputReadiness.READY) ||
                        (systemRecognition && recognitionReadiness != SpeechRecognitionReadiness.READY)
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = if (recoveryVisible) 0.dp else emptyHeight)
                            .testTag(ConversationTestTags.EMPTY_STATE).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(painterResource(R.drawable.conversation_empty), contentDescription = null,
                            modifier = Modifier.size(96.dp),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant))
                        Text(stringResource(R.string.conversation_empty_title),
                            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.conversation_empty), textAlign = TextAlign.Center)
                        Text(stringResource(R.string.conversation_empty_result),
                            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.sessionActive || state.phase == ConversationPhase.ERROR) {
                        Text(stringResource(phaseLabel(state.phase)), style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center, modifier = Modifier.testTag(ConversationTestTags.PHASE))
                    }
                    ProviderAttribution(provider)
                    Button(
                        onClick = onSessionAction,
                        enabled = state.sessionActive || (configurationProblem == null && ready && !preparing &&
                            (!systemRecognition || recognitionReadiness == SpeechRecognitionReadiness.READY) &&
                            !languageChanging && !languageBlocked),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(ConversationTestTags.SESSION_ACTION),
                    ) {
                        Text(stringResource(if (state.sessionActive) R.string.conversation_stop else R.string.conversation_start),
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}



@Composable
private fun TranscriptCard(row: ConversationTranscript, primary: Boolean) {
    val translation = row.translation
    Card(
        modifier = Modifier.fillMaxWidth().testTag(
            if (primary) ConversationTestTags.CURRENT_TURN else ConversationTestTags.PREVIOUS_TURN,
        ),
        colors = CardDefaults.cardColors(containerColor =
            if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(if (primary) R.string.conversation_current_turn else R.string.conversation_previous_turn),
                style = MaterialTheme.typography.labelLarge,
            )
            row.direction?.let {
                Text("${languageName(it.source)} → ${languageName(it.target)}",
                    style = MaterialTheme.typography.labelMedium)
            }
            if (row.original.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.conversation_original), style = MaterialTheme.typography.labelMedium)
                    Text(row.original, style = if (primary) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.conversation_translation), style = MaterialTheme.typography.labelMedium)
                if (translation == null) {
                    Text(stringResource(transcriptLabel(row.status)),
                        style = if (primary) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
                } else {
                    SelectionContainer {
                        Text(translation,
                            style = if (primary) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (translation != null) {
                Text(stringResource(transcriptLabel(row.status)), style = MaterialTheme.typography.bodySmall)
            }
            row.failure?.let { Text(stringResource(row.translationFailure?.let { typed ->
                com.example.offlinetranslator.ui.translationFailureLabel(typed)
            } ?: failureLabel(it)), color = MaterialTheme.colorScheme.error) }
        }
    }
}


private fun preparationLabel(value: ConversationPreparation) = when (value) {
    ConversationPreparation.CHECKING -> R.string.conversation_checking
    ConversationPreparation.INSTALLING -> R.string.conversation_preparing_languages
    ConversationPreparation.TRANSLATION -> R.string.conversation_preparing_translation
    ConversationPreparation.IDLE -> R.string.conversation_preparation_missing
    ConversationPreparation.ERROR -> R.string.conversation_preparation_error
}


private fun phaseLabel(value: ConversationPhase) = when (value) {
    ConversationPhase.READY -> R.string.conversation_phase_ready
    ConversationPhase.PREPARING -> R.string.conversation_phase_preparing
    ConversationPhase.LISTENING -> R.string.conversation_phase_listening
    ConversationPhase.UNDERSTANDING -> R.string.conversation_phase_understanding
    ConversationPhase.TRANSLATING -> R.string.conversation_phase_translating
    ConversationPhase.SPEAKING -> R.string.conversation_phase_speaking
    ConversationPhase.ERROR -> R.string.conversation_phase_error
}

private fun transcriptLabel(value: ConversationTranscriptStatus) = when (value) {
    ConversationTranscriptStatus.UNDERSTANDING -> R.string.conversation_understanding
    ConversationTranscriptStatus.TRANSLATING -> R.string.conversation_translating
    ConversationTranscriptStatus.SPEAKING -> R.string.conversation_speaking
    ConversationTranscriptStatus.COMPLETE -> R.string.conversation_complete
    ConversationTranscriptStatus.ERROR -> R.string.conversation_turn_error
    ConversationTranscriptStatus.INTERRUPTED -> R.string.conversation_interrupted
}

private fun failureLabel(value: ConversationFailure) = when (value) {
    ConversationFailure.MODELS_MISSING -> R.string.conversation_failure_models
    ConversationFailure.PREPARATION_FAILED -> R.string.conversation_failure_preparation
    ConversationFailure.PERMISSION_DENIED -> R.string.conversation_permission_denied
    ConversationFailure.MICROPHONE_UNAVAILABLE -> R.string.conversation_failure_microphone
    ConversationFailure.CAPTURE_FAILED -> R.string.conversation_failure_capture
    ConversationFailure.UNSUPPORTED_LANGUAGE -> R.string.conversation_failure_language
    ConversationFailure.UNDETERMINED_LANGUAGE -> R.string.conversation_failure_undetermined_language
    ConversationFailure.RECOGNITION_FAILED -> R.string.conversation_failure_recognition
    ConversationFailure.TRANSLATION_FAILED -> R.string.conversation_failure_translation
    ConversationFailure.PLAYBACK_FAILED -> R.string.conversation_failure_playback
}

private fun recognitionReadinessLabel(readiness: SpeechRecognitionReadiness, explicitService: Boolean): Int = when (readiness) {
    SpeechRecognitionReadiness.READY -> R.string.conversation_recognition_ready
    SpeechRecognitionReadiness.MISSING_LOCAL_MODEL -> R.string.conversation_recognition_missing_model
    SpeechRecognitionReadiness.UNSUPPORTED -> R.string.conversation_recognition_unsupported
    SpeechRecognitionReadiness.INCOMPATIBLE_SERVICE -> R.string.conversation_recognition_incompatible_service
    SpeechRecognitionReadiness.ENGINE_UNAVAILABLE -> if (explicitService) R.string.conversation_recognition_service_unavailable
        else R.string.conversation_recognition_engine_unavailable
    SpeechRecognitionReadiness.SCHEDULED -> R.string.conversation_recognition_scheduled
    SpeechRecognitionReadiness.DOWNLOADING -> R.string.conversation_recognition_downloading
    SpeechRecognitionReadiness.DOWNLOAD_FAILED -> R.string.conversation_recognition_download_failed
    SpeechRecognitionReadiness.CHECK_FAILED -> R.string.conversation_recognition_check_failed
}
