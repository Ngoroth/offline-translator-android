package com.example.offlinetranslator.core

data class LanguageMetadata(val id: LanguageId, val englishName: String, val russianName: String, val warmUpText: String)

object LanguageCatalog {
    val RUSSIAN = LanguageId("ru")
    val ENGLISH = LanguageId("en")
    val GERMAN = LanguageId("de")
    val FRENCH = LanguageId("fr")
    val SPANISH = LanguageId("es")
    val ITALIAN = LanguageId("it")
    val PORTUGUESE = LanguageId("pt")
    val VIETNAMESE = LanguageId("vi")
    val INDONESIAN = LanguageId("id")
    val CHINESE = LanguageId("zh")
    val KOREAN = LanguageId("ko")
    val POLISH = LanguageId("pl")
    val THAI = LanguageId("th")
    val TURKISH = LanguageId("tr")
    val HINDI = LanguageId("hi")
    val JAPANESE = LanguageId("ja")
    val DUTCH = LanguageId("nl")
    val entries = listOf(
        LanguageMetadata(ENGLISH, "English", "Английский", "Good afternoon."),
        LanguageMetadata(VIETNAMESE, "Vietnamese", "Вьетнамский", "Chào buổi chiều."),
        LanguageMetadata(SPANISH, "Spanish", "Испанский", "Buenas tardes."),
        LanguageMetadata(INDONESIAN, "Indonesian", "Индонезийский", "Selamat siang."),
        LanguageMetadata(ITALIAN, "Italian", "Итальянский", "Buon pomeriggio."),
        LanguageMetadata(CHINESE, "Chinese (Simplified Mandarin)", "Китайский (мандаринский, упрощённый)", "下午好。"),
        LanguageMetadata(KOREAN, "Korean", "Корейский", "안녕하세요."),
        LanguageMetadata(GERMAN, "German", "Немецкий", "Guten Tag."),
        LanguageMetadata(POLISH, "Polish", "Польский", "Dzień dobry."),
        LanguageMetadata(PORTUGUESE, "Portuguese", "Португальский", "Boa tarde."),
        LanguageMetadata(RUSSIAN, "Russian", "Русский", "Добрый день."),
        LanguageMetadata(THAI, "Thai", "Тайский", "สวัสดี"),
        LanguageMetadata(TURKISH, "Turkish", "Турецкий", "İyi günler."),
        LanguageMetadata(FRENCH, "French", "Французский", "Bonjour."),
        LanguageMetadata(HINDI, "Hindi", "Хинди", "नमस्ते।"),
        LanguageMetadata(JAPANESE, "Japanese", "Японский", "こんにちは。"),
        LanguageMetadata(DUTCH, "Dutch", "Нидерландский", "Goedendag."),
    )
    private val byId = entries.associateBy { it.id }
    fun find(id: LanguageId): LanguageMetadata? = byId[id]
}
