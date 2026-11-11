package io.github.ngoroth.offlinetranslator.runtime

import io.github.ngoroth.offlinetranslator.core.ConfigurationProblem
import io.github.ngoroth.offlinetranslator.core.InvalidConfigurationException
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.TranslationCapability
import io.github.ngoroth.offlinetranslator.core.TranslationEngine
import io.github.ngoroth.offlinetranslator.core.TranslationProviderDescriptor
import io.github.ngoroth.offlinetranslator.core.requireSupported
import io.github.ngoroth.offlinetranslator.tencent.TencentArtifactPreparation
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine

/** Only compiled, real providers can own inference resources. */
class TranslationEngineRegistry internal constructor(registrations: List<Registration>) {
    internal class Registration(
        val descriptor: TranslationProviderDescriptor,
        val capability: (LanguagePair) -> TranslationCapability,
        val create: (Set<LanguagePair>) -> TranslationEngine,
    )

    private val registrations = registrations.toList()

    init {
        require(this.registrations.map { it.descriptor.id }.distinct().size == this.registrations.size)
    }

    internal constructor(factory: (Set<LanguagePair>) -> TranslationEngine) : this(
        listOf(Registration(TencentTranslationEngine.descriptor, { TencentTranslationEngine.capability(it) }, factory)),
    )

    constructor(artifacts: TencentArtifactPreparation) : this(
        { pairs -> TencentTranslationEngine(pairs, artifacts) },
    )

    val descriptors: List<TranslationProviderDescriptor> = this.registrations.map { it.descriptor }

    private fun registration(id: String): Registration = registrations.firstOrNull { it.descriptor.id == id }
        ?: throw InvalidConfigurationException(ConfigurationProblem.UNKNOWN_ENGINE)

    fun descriptor(id: String): TranslationProviderDescriptor = registration(id).descriptor

    fun capability(engineId: String, pair: LanguagePair): TranslationCapability =
        registration(engineId).capability(pair)

    internal fun validate(engineId: String, pairs: Set<LanguagePair>) {
        descriptor(engineId)
        require(pairs.isNotEmpty() && pairs.size <= 2) { "Select one or two directed translation pairs" }
        pairs.forEach { capability(engineId, it).requireSupported() }
    }

    fun create(engineId: String, pairs: Set<LanguagePair>): TranslationEngine {
        val selected = pairs.toSet()
        validate(engineId, selected)
        return registration(engineId).create(selected)
    }
}
