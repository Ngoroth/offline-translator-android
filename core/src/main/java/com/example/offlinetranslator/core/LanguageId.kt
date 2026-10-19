package com.example.offlinetranslator.core

import java.util.Locale

@JvmInline
value class LanguageId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val VALID = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")
        operator fun invoke(value: String): LanguageId {
            val canonical = value.lowercase(Locale.ROOT)
            require(canonical.length <= 35 && VALID.matches(canonical)) { "Invalid language ID" }
            return LanguageId(canonical)
        }
    }
}
