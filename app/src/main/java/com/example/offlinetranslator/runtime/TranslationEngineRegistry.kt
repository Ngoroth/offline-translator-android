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
import com.example.offlinetranslator.tencent.TencentArtifactPreparation
import com.example.offlinetranslator.tencent.TencentTranslationEngine

/** Only compiled, real providers can own inference resources. */
class TranslationEngineRegistry internal constructor(
    private val mlKitFactory: (Set<LanguagePair>) -> TranslationEngine,
    private val tencentFactory: (Set<LanguagePair>) -> TranslationEngine,
) {
    constructor(
        context: Context,
        coordinator: MlKitPreparationCoordinator,
        artifacts: TencentArtifactPreparation,
    ) : this(
        { pairs -> MlKitTranslationEngine(context.applicationContext, pairs, coordinator) },
        { pairs -> TencentTranslationEngine(pairs, artifacts) },
    )

    val descriptors: List<TranslationProviderDescriptor> =
        listOf(MlKitTranslationEngine.descriptor, TencentTranslationEngine.descriptor)

    fun descriptor(id: String): TranslationProviderDescriptor = descriptors.firstOrNull { it.id == id }
        ?: throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)

    fun capability(engineId: String, pair: LanguagePair): TranslationCapability {
        descriptor(engineId)
        return when (engineId) {
            "mlkit" -> MlKitTranslationEngine.capability(pair)
            "tencent" -> TencentTranslationEngine.capability(pair)
            else -> throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)
        }
    }

    fun create(engineId: String, pairs: Set<LanguagePair>): TranslationEngine {
        descriptor(engineId)
        require(pairs.isNotEmpty() && pairs.size <= 2) { "Select one or two directed translation pairs" }
        val selected = pairs.toSet()
        selected.forEach { capability(engineId, it).requireSupported() }
        return when (engineId) {
            "mlkit" -> mlKitFactory(selected)
            "tencent" -> tencentFactory(selected)
            else -> throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)
        }
    }
}
