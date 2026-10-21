package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.InvalidConfigurationException
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TranslationPreparationState
import com.example.offlinetranslator.core.TranslationPreparationPhase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineRegistryTest {
    @Test fun tencentSelectionNeverConstructsMlKitAndRejectsUnknownCatalogLanguage() {
        val mlkit = RecordingFactory()
        val tencent = RecordingFactory()
        val registry = TranslationEngineRegistry(mlkit::create, tencent::create)
        val engine = registry.create("tencent", setOf(ruEn, ruEn.reverse()))
        try {
            assertEquals(listOf(setOf(ruEn, ruEn.reverse())), tencent.selections)
            mlkit.assertNoEffects()
            assertProblem(ConfigurationProblem.UNSUPPORTED_PAIR, runCatching {
                registry.create("tencent", setOf(LanguagePair(LanguageCatalog.ENGLISH, LanguageId("zz"))))
            }.exceptionOrNull())
            assertEquals(1, tencent.selections.size)
        } finally { engine.close() }
    }

    @Test fun unknownEngineRetainsDistinctReasonWithoutCreatingAnyOwner() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)

        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.descriptor("removed-engine") }.exceptionOrNull())
        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.capability("removed-engine", ruEn) }.exceptionOrNull())
        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.create("removed-engine", setOf(ruEn)) }.exceptionOrNull())
        factory.assertNoEffects()
    }

    @Test fun unsupportedPairRefusesBeforeFactoryEvenAfterSupportedSelection() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)
        val unsupported = LanguagePair(LanguageCatalog.ENGLISH, LanguageId("zz"))
        assertProblem(
            ConfigurationProblem.UNSUPPORTED_PAIR,
            runCatching { registry.create("mlkit", linkedSetOf(ruEn, unsupported)) }.exceptionOrNull(),
        )
        factory.assertNoEffects()
    }

    @Test fun pivotAndSdkUnknownReachFactoryWithoutManualEnglishIntermediatePairs() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)
        val pivot = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        val sdkUnknown = LanguagePair(LanguageCatalog.ENGLISH, LanguageId("ja"))
        registry.create("mlkit", setOf(pivot, pivot.reverse())).close()
        registry.create("mlkit", setOf(sdkUnknown)).close()
        assertEquals(listOf(setOf(pivot, pivot.reverse()), setOf(sdkUnknown)), factory.selections)
        assertEquals(0, factory.preparations)
        assertTrue(factory.translatedPairs.isEmpty())
    }

    @Test fun factoryReceivesOnlyImmutableSelectedPairSnapshot() = runTest {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)
        val selected = linkedSetOf(enDe)
        val engine = registry.create("mlkit", selected)
        selected.clear()
        selected += ruEn

        assertEquals(listOf(setOf(enDe)), factory.selections)
        assertEquals(0, factory.preparations)
        assertTrue(factory.translatedPairs.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun conversationFactoryReceivesOnlyTheTwoSelectedDirections() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)
        registry.create("mlkit", setOf(ruEn, ruEn.reverse())).close()
        assertEquals(listOf(setOf(ruEn, ruEn.reverse())), factory.selections)
        assertEquals(0, factory.preparations)
        assertTrue(factory.translatedPairs.isEmpty())
    }

    @Test fun emptyOrUnboundedSelectionCannotReachFactory() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create, factory::create)
        for (selection in listOf(emptySet(), setOf(ruEn, ruEn.reverse(), enDe))) {
            assertTrue(runCatching { registry.create("mlkit", selection) }.exceptionOrNull() is IllegalArgumentException)
        }
        factory.assertNoEffects()
    }

    private fun assertProblem(expected: ConfigurationProblem, error: Throwable?) {
        assertTrue(error is InvalidConfigurationException)
        assertEquals(expected, (error as InvalidConfigurationException).problem)
    }
}

private val ruEn = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
private val enDe = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)

/** Controlled factory boundary; capability evidence remains the real shipping ML Kit provider's. */
private class RecordingFactory {
    val selections = mutableListOf<Set<LanguagePair>>()
    val translatedPairs = mutableListOf<LanguagePair>()
    var preparations = 0

    fun create(pairs: Set<LanguagePair>): TranslationEngine {
        selections += pairs
        return object : TranslationEngine {
            override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.READY))
            override suspend fun modelAvailability() = ModelAvailability.READY
            override suspend fun prepare(): ModelAvailability {
                preparations += 1
                return ModelAvailability.READY
            }
            override suspend fun translate(pair: LanguagePair, text: String): String {
                require(pair in pairs)
                translatedPairs += pair
                return "translated"
            }
            override fun close() = Unit
        }
    }

    fun assertNoEffects() {
        assertTrue(selections.isEmpty())
        assertEquals(0, preparations)
        assertTrue(translatedPairs.isEmpty())
    }
}
