package com.example.offlinetranslator.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagePairTest {
    @Test fun directionsExposeSourceTargetAndReverse() {
        assertEquals(LanguageCatalog.RUSSIAN, LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).source)
        assertEquals(LanguageCatalog.ENGLISH, LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).target)
        assertEquals(LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN), LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH).reverse())
        assertEquals(LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH), LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.RUSSIAN).reverse())
    }

}
