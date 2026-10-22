package com.example.offlinetranslator.speech

import com.example.offlinetranslator.core.LanguageId
import java.util.Locale

/** Platform tags are deliberately distinct from the base IDs used by ML Kit. */
internal object AndroidSpeechLocales {
    private val recognitionTags = mapOf(
        "en" to "en-US", "vi" to "vi-VN", "es" to "es-ES", "id" to "id-ID",
        "it" to "it-IT", "zh" to "cmn-Hans-CN", "ko" to "ko-KR", "de" to "de-DE",
        "pl" to "pl-PL", "pt" to "pt-BR", "ru" to "ru-RU", "th" to "th-TH",
        "tr" to "tr-TR", "fr" to "fr-FR", "hi" to "hi-IN", "ja" to "ja-JP",
        "nl" to "nl-NL",
    )
    fun recognition(language: LanguageId): String = recognitionTags.getValue(language.value)
    fun output(language: LanguageId): Locale = Locale.forLanguageTag(
        if (language.value == "zh") "zh-Hans-CN" else recognition(language),
    )
    fun source(tag: String): LanguageId? {
        if (tag.isBlank() || tag.length > 128 || !TAG.matches(tag)) return null
        val locale = Locale.forLanguageTag(tag)
        val base = locale.language.lowercase(Locale.ROOT)
        if (base == "zh" || base == "cmn") {
            if (!locale.country.equals("CN", true) ||
                (locale.script.isNotEmpty() && !locale.script.equals("Hans", true))) return null
            return LanguageId("zh")
        }
        return if (base in recognitionTags) LanguageId(base) else null
    }
    private val TAG = Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*")
}
