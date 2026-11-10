package io.github.ngoroth.offlinetranslator.runtime

import android.content.Context
import io.github.ngoroth.offlinetranslator.core.ConfigurationProblem
import io.github.ngoroth.offlinetranslator.core.InvalidConfigurationException
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.TranslationCapability
import io.github.ngoroth.offlinetranslator.core.TranslationEngine
import io.github.ngoroth.offlinetranslator.core.TranslationProviderDescriptor
import io.github.ngoroth.offlinetranslator.core.requireSupported
import io.github.ngoroth.offlinetranslator.mlkit.MlKitPreparationCoordinator
import io.github.ngoroth.offlinetranslator.mlkit.MlKitTranslationEngine
import io.github.ngoroth.offlinetranslator.tencent.TencentArtifactPreparation
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine

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

    internal fun validate(engineId: String, pairs: Set<LanguagePair>) {
        descriptor(engineId)
        require(pairs.isNotEmpty() && pairs.size <= 2) { "Select one or two directed translation pairs" }
        pairs.forEach { capability(engineId, it).requireSupported() }
    }

    fun create(engineId: String, pairs: Set<LanguagePair>): TranslationEngine {
        val selected = pairs.toSet()
        validate(engineId, selected)
        return when (engineId) {
            "mlkit" -> mlKitFactory(selected)
            "tencent" -> tencentFactory(selected)
            else -> throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)
        }
    }
}
