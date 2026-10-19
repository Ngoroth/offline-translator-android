package com.example.offlinetranslator.mlkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.RequiresDevice
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RequiresDevice
@RunWith(AndroidJUnit4::class)
class MlKitTranslationSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val coordinator = MlKitPreparationCoordinator(context)

    @Test fun preparesOneModelAndTranslatesInBothDirections() = runBlocking {
        val ruEn = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        val engine = MlKitTranslationEngine(context, setOf(ruEn, ruEn.reverse()), coordinator)
        try {
            assertEquals(ModelAvailability.READY, engine.prepare())
            assertTrue(engine.translate(ruEn, "Доброе утро").isNotBlank())
            assertTrue(engine.translate(ruEn.reverse(), "Good evening").isNotBlank())
        } finally {
            engine.closeAndJoin()
        }
    }

    @Test fun selectedGermanSdkClientsPreserveNegationNumbersAndDepartureTimeBothWays() = runBlocking {
        val enDe = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)
        val engine = MlKitTranslationEngine(context, setOf(enDe, enDe.reverse()), coordinator)
        try {
            assertEquals(ModelAvailability.READY, engine.prepare())
            val german = engine.translate(
                enDe,
                "The train does not leave at 3 pm. It leaves at 4 pm and costs 25 euros.",
            ).lowercase(Locale.ROOT)
            assertTrue("German negation was lost: $german", "nicht" in german)
            assertTrue("German price was lost: $german", "25" in german)
            assertTrue("German departure time was lost: $german", departureTime.containsMatchIn(german))

            val english = engine.translate(
                enDe.reverse(),
                "Der Zug fährt nicht um 15 Uhr ab. Er fährt um 16 Uhr ab und kostet 25 Euro.",
            ).lowercase(Locale.ROOT)
            assertTrue("English negation was lost: $english", "not" in english || "n't" in english)
            assertTrue("English price was lost: $english", "25" in english)
            assertTrue("English departure time was lost: $english", departureTime.containsMatchIn(english))
        } finally {
            engine.closeAndJoin()
        }
    }

    private val departureTime = Regex("\\b(?:16|4|vier|sechzehn|four|sixteen)\\b")
}
