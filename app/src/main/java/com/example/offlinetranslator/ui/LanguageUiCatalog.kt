package com.example.offlinetranslator.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.LanguageId

@Composable
fun languageName(id: LanguageId): String {
    val resource = languageNameResource(id) ?: return id.value
    return stringResource(resource)
}

internal fun languageNameResource(id: LanguageId): Int? = when (id.value) {
    "en" -> R.string.language_name_en
    "vi" -> R.string.language_name_vi
    "es" -> R.string.language_name_es
    "id" -> R.string.language_name_id
    "it" -> R.string.language_name_it
    "zh" -> R.string.language_name_zh
    "ko" -> R.string.language_name_ko
    "de" -> R.string.language_name_de
    "pl" -> R.string.language_name_pl
    "pt" -> R.string.language_name_pt
    "ru" -> R.string.language_name_ru
    "th" -> R.string.language_name_th
    "tr" -> R.string.language_name_tr
    "fr" -> R.string.language_name_fr
    "hi" -> R.string.language_name_hi
    "ja" -> R.string.language_name_ja
    "nl" -> R.string.language_name_nl
    else -> null
}

@Composable
internal fun speechModelName(id: String, fallback: String): String {
    val resource = when (id) {
        "vad-silero-6.2.2" -> R.string.model_vad_silero
        "lid-whisper-tiny" -> R.string.model_lid_whisper
        "asr-ru-gigaam-v1" -> R.string.model_asr_gigaam
        "asr-en-whisper-tiny.en" -> R.string.model_asr_whisper_en
        "asr-en-whisper-tiny" -> R.string.model_asr_whisper_multilingual
        "asr-whisper-small-multilingual-int8" -> R.string.model_asr_whisper_small_multilingual
        "tts-ru-piper-irina-medium" -> R.string.model_tts_irina
        "tts-en-piper-lessac-medium" -> R.string.model_tts_lessac
        else -> return fallback
    }
    return stringResource(resource)
}

@Composable
fun configurationProblemMessage(problem: ConfigurationProblem): String = stringResource(when (problem) {
    ConfigurationProblem.UNKNOWN_LANGUAGE -> R.string.configuration_unknown_language
    ConfigurationProblem.UNKNOWN_ENGINE -> R.string.configuration_unknown_engine
    ConfigurationProblem.UNSUPPORTED_PAIR -> R.string.configuration_unsupported_pair
    ConfigurationProblem.UNKNOWN_MODEL -> R.string.configuration_unknown_model
    ConfigurationProblem.INCOMPATIBLE_MODEL -> R.string.configuration_incompatible_model
    ConfigurationProblem.UNSUPPORTED_VOICE -> R.string.configuration_unsupported_voice
    ConfigurationProblem.INVALID_SILENCE -> R.string.configuration_silence
    ConfigurationProblem.INVALID_SPEED -> R.string.configuration_speed
})
