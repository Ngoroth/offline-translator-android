package com.example.offlinetranslator.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import java.util.Locale

/** The platform application locales are the only persisted interface-language preference. */
object InterfaceLanguage {
    val choices: List<Pair<LanguageId, String>> = LanguageCatalog.entries.map { entry ->
        val locale = Locale.forLanguageTag(localeTag(entry.id))
        entry.id to locale.getDisplayName(locale)
    }

    fun current(context: Context): LanguageId = current(manager(context).applicationLocales)

    fun ensureDefault(context: Context): LanguageId {
        val manager = manager(context)
        val locales = manager.applicationLocales
        if (!locales.isEmpty) return current(locales)
        manager.applicationLocales = LocaleList.forLanguageTags(localeTag(LanguageCatalog.ENGLISH))
        return LanguageCatalog.ENGLISH
    }

    fun apply(context: Context, language: LanguageId) {
        val requested = LocaleList.forLanguageTags(localeTag(language))
        val manager = manager(context)
        if (manager.applicationLocales != requested) manager.applicationLocales = requested
    }

    fun localeTag(language: LanguageId): String {
        require(LanguageCatalog.find(language) != null) { "Unsupported interface language: $language" }
        return if (language == LanguageCatalog.CHINESE) "zh-Hans" else language.value
    }

    private fun manager(context: Context): LocaleManager =
        requireNotNull(context.getSystemService(LocaleManager::class.java))

    private fun current(locales: LocaleList): LanguageId {
        for (index in 0 until locales.size()) {
            val locale = locales[index]
            val code = if (locale.language == "in") "id" else locale.language
            val language = LanguageCatalog.entries.firstOrNull { it.id.value == code }?.id
                ?: continue
            if (language == LanguageCatalog.CHINESE &&
                (locale.script == "Hant" || locale.country == "TW" ||
                    locale.country == "HK" || locale.country == "MO")) continue
            return language
        }
        return LanguageCatalog.ENGLISH
    }
}
