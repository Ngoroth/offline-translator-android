package io.github.ngoroth.offlinetranslator.mozilla

import io.github.ngoroth.offlinetranslator.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MozillaTranslationCatalogTest {
    @Test fun allCurrentPairsHaveCompleteDirectOrEnglishPivotModels() {
        val catalog = MozillaTranslationCatalog.PINNED
        assertEquals(LanguageCatalog.entries.map { it.id }.toSet(), catalog.languages)
        for (source in catalog.languages) for (target in catalog.languages) if (source != target) {
            val pair = LanguagePair(source, target)
            val path = catalog.models(pair)
            if (LanguageCatalog.ENGLISH in setOf(source, target)) {
                assertEquals(TranslationCapability.Direct, catalog.capability(pair))
                assertEquals(listOf(pair), path.map { it.pair })
            } else {
                assertEquals(TranslationCapability.Pivot(LanguageCatalog.ENGLISH), catalog.capability(pair))
                assertEquals(listOf(LanguagePair(source, LanguageCatalog.ENGLISH), LanguagePair(LanguageCatalog.ENGLISH, target)), path.map { it.pair })
            }
            val reverse = catalog.models(setOf(pair, pair.reverse()))
            assertEquals(if (path.size == 1) 2 else 4, reverse.size)
            assertTrue(reverse.all { it.files.any { file -> file.role == "model" } && it.files.any { file -> file.role == "lex" } })
        }
        assertEquals(TranslationCapability.Unsupported, catalog.capability(LanguagePair(LanguageCatalog.ENGLISH, LanguageId("ar"))))
    }

    @Test fun tinyAndSplitVocabularyModelsProduceTheirOwnCorrectNativePaths() {
        val catalog = MozillaTranslationCatalog.PINNED
        val tiny = setOf("vi->en", "en->id", "id->en", "en->tr", "tr->en", "en->hi", "hi->en")
        val models = catalog.languages.filter { it != LanguageCatalog.ENGLISH }.flatMap {
            catalog.models(setOf(LanguagePair(it, LanguageCatalog.ENGLISH), LanguagePair(LanguageCatalog.ENGLISH, it)))
        }
        assertEquals(tiny, models.filter { it.architecture == "tiny" }.map { it.pair.id }.toSet())
        for (target in listOf(LanguageCatalog.CHINESE, LanguageCatalog.JAPANESE, LanguageCatalog.KOREAN)) {
            val model = catalog.models(LanguagePair(LanguageCatalog.ENGLISH, target)).single()
            val sourceVocab = model.files.single { it.role == "srcvocab" }
            val targetVocab = model.files.single { it.role == "trgvocab" }
            assertNotEquals(sourceVocab.sha256, targetVocab.sha256)
            val config = model.configuration({ File("models", it.installedName).absoluteFile }, File("prefix.txt").absoluteFile)
            assertTrue(config.contains(JSONObject.quote(File("models", sourceVocab.installedName).absolutePath)))
            assertTrue(config.contains(JSONObject.quote(File("models", targetVocab.installedName).absolutePath)))
        }
    }

    @Test fun incompleteDuplicateMixedArchitectureAndUnsafeCatalogsAreRejected() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONArray("routes").remove(0) },
            { it.getJSONArray("routes").put(it.getJSONArray("routes").getJSONObject(0)) },
            { it.getJSONArray("routes").getJSONObject(0).put("architecture", "tiny") },
            { it.getJSONArray("routes").getJSONObject(0).getJSONArray("files").remove(0) },
            { it.getJSONArray("routes").getJSONObject(0).put("directory", "models/../outside/exported/") },
            { it.getJSONArray("routes").getJSONObject(0).getJSONArray("files").getJSONArray(0).put(1, "../outside.bin") },
            { it.getJSONArray("routes").getJSONObject(0).getJSONArray("files").getJSONArray(0).put(4, "not-a-hash") },
            { it.getJSONObject("rawAttachments").put(it.getJSONObject("rawAttachments").keys().next(), "https://example.invalid/model.bin") },
            { it.getJSONObject("rawAttachments").put("f".repeat(64), "https://firefox-settings-attachments.cdn.mozilla.net/main-workspace/translations-models/unknown.bin") },
        )
        for (mutate in mutations) {
            val input = JSONObject(resource())
            mutate(input)
            assertThrows(IllegalArgumentException::class.java) { MozillaTranslationCatalog.parse(input.toString()) }
        }
    }

    private fun resource() = requireNotNull(javaClass.getResourceAsStream("/mozilla-translation-catalog.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
