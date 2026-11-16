package io.github.ngoroth.offlinetranslator.runtime

import io.github.ngoroth.offlinetranslator.core.ConfigurationProblem
import io.github.ngoroth.offlinetranslator.core.InvalidConfigurationException
import io.github.ngoroth.offlinetranslator.core.LanguageCatalog
import io.github.ngoroth.offlinetranslator.core.LanguageId
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.ModelAvailability
import io.github.ngoroth.offlinetranslator.core.TranslationEngine
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationState
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationPhase
import io.github.ngoroth.offlinetranslator.core.TranslationCapability
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineRegistryTest {
    @Test fun registeredProvidersRejectUnknownCatalogLanguageBeforeConstruction() {
        for (engineId in listOf("tencent", "mozilla")) {
            val factory = RecordingFactory()
            val registry = TranslationEngineRegistry(factory::create)
            val engine = registry.create(engineId, setOf(ruEn, ruEn.reverse()))
            try {
                assertEquals(listOf(setOf(ruEn, ruEn.reverse())), factory.selections)
                assertProblem(ConfigurationProblem.UNSUPPORTED_PAIR, runCatching {
                    registry.create(engineId, setOf(LanguagePair(LanguageCatalog.ENGLISH, LanguageId("zz"))))
                }.exceptionOrNull())
                assertEquals(1, factory.selections.size)
            } finally { engine.close() }
        }
    }

    @Test fun unknownEngineRetainsDistinctReasonWithoutCreatingAnyOwner() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create)

        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.descriptor("removed-engine") }.exceptionOrNull())
        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.capability("removed-engine", ruEn) }.exceptionOrNull())
        assertProblem(ConfigurationProblem.UNKNOWN_ENGINE, runCatching { registry.create("removed-engine", setOf(ruEn)) }.exceptionOrNull())
        factory.assertNoEffects()
    }

    @Test fun unsupportedPairRefusesBeforeFactoryEvenAfterSupportedSelection() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create)
        val unsupported = LanguagePair(LanguageCatalog.ENGLISH, LanguageId("zz"))
        assertProblem(
            ConfigurationProblem.UNSUPPORTED_PAIR,
            runCatching { registry.create("tencent", linkedSetOf(ruEn, unsupported)) }.exceptionOrNull(),
        )
        factory.assertNoEffects()
    }

    @Test fun descriptorRegistrationPreservesPivotAndUnknownWithoutInventingIntermediatePairs() {
        val factory = RecordingFactory()
        val descriptor = TencentTranslationEngine.descriptor.copy(id = "isolated-provider")
        val pivot = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        val unknown = LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.JAPANESE)
        val registry = TranslationEngineRegistry(listOf(TranslationEngineRegistry.Registration(
            descriptor,
            { if (it == unknown) TranslationCapability.Unknown else TranslationCapability.Pivot(LanguageCatalog.ENGLISH) },
            factory::create,
        )))
        assertEquals(descriptor, registry.descriptor(descriptor.id))
        assertEquals(TranslationCapability.Unknown, registry.capability(descriptor.id, unknown))
        registry.create(descriptor.id, setOf(pivot, pivot.reverse())).close()
        registry.create(descriptor.id, setOf(unknown)).close()
        assertEquals(listOf(setOf(pivot, pivot.reverse()), setOf(unknown)), factory.selections)
        assertEquals(0, factory.preparations)
        assertTrue(factory.translatedPairs.isEmpty())
    }

    @Test fun pivotFactoryReceivesOnlyImmutableSelectedPairSnapshotWithoutIntermediatePairs() = runTest {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create)
        val pivot = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.CHINESE)
        val selected = linkedSetOf(pivot)
        val engine = registry.create("mozilla", selected)
        selected.clear()
        selected += ruEn

        assertEquals(listOf(setOf(pivot)), factory.selections)
        assertEquals(0, factory.preparations)
        assertTrue(factory.translatedPairs.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun conversationFactoryReceivesOnlyTheTwoSelectedDirections() {
        for (engineId in listOf("tencent", "mozilla")) {
            val factory = RecordingFactory()
            val registry = TranslationEngineRegistry(factory::create)
            registry.create(engineId, setOf(ruEn, ruEn.reverse())).close()
            assertEquals(listOf(setOf(ruEn, ruEn.reverse())), factory.selections)
            assertEquals(0, factory.preparations)
            assertTrue(factory.translatedPairs.isEmpty())
        }
    }

    @Test fun emptyOrUnboundedSelectionCannotReachFactory() {
        val factory = RecordingFactory()
        val registry = TranslationEngineRegistry(factory::create)
        for (selection in listOf(emptySet(), setOf(ruEn, ruEn.reverse(), enDe))) {
            assertTrue(runCatching { registry.create("tencent", selection) }.exceptionOrNull() is IllegalArgumentException)
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

/** Controlled factory boundary; capability evidence remains the shipping providers'. */
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
