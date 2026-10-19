package com.example.offlinetranslator.runtime

import android.content.Context
import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.InvalidConfigurationException
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.TranslationCapability
import com.example.offlinetranslator.core.TranslationEngine
import com.example.offlinetranslator.core.TranslationProviderDescriptor
import com.example.offlinetranslator.core.requireSupported
import com.example.offlinetranslator.mlkit.MlKitPreparationCoordinator
import com.example.offlinetranslator.mlkit.MlKitTranslationEngine

/** Only compiled, real providers can own inference resources. */
class TranslationEngineRegistry internal constructor(
    private val mlKitFactory: (Set<LanguagePair>) -> TranslationEngine,
) {
    constructor(context: Context, coordinator: MlKitPreparationCoordinator) : this(
        { pairs -> MlKitTranslationEngine(context.applicationContext, pairs, coordinator) },
    )

    val descriptors: List<TranslationProviderDescriptor> = listOf(MlKitTranslationEngine.descriptor)

    fun descriptor(id: String): TranslationProviderDescriptor {
        if (id != MlKitTranslationEngine.descriptor.id) {
            throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)
        }
        return MlKitTranslationEngine.descriptor
    }

    fun capability(engineId: String, pair: LanguagePair): TranslationCapability {
        descriptor(engineId)
        return MlKitTranslationEngine.capability(pair)
    }

    fun create(engineId: String, pairs: Set<LanguagePair>): TranslationEngine {
        descriptor(engineId)
        require(pairs.isNotEmpty() && pairs.size <= 2) { "Select one or two directed translation pairs" }
        val selected = pairs.toSet()
        selected.forEach { capability(engineId, it).requireSupported() }
        return mlKitFactory(selected)
    }
}
