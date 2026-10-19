package com.example.offlinetranslator.core

data class LanguagePair(val source: LanguageId, val target: LanguageId) {
    init { require(source != target) { "Source and target must differ" } }
    fun reverse(): LanguagePair = LanguagePair(target, source)
    val id: String get() = "${source.value}->${target.value}"
}
