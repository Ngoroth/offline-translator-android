package com.example.offlinetranslator.speechmodels

import com.example.offlinetranslator.core.ConversationModelRepository
import com.example.offlinetranslator.core.ConversationSessionConfig
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.SpeechModelInstallState
import com.example.offlinetranslator.core.SpeechModelRepository
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechOutputBackend
import com.example.offlinetranslator.core.SpeechModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.atomic.AtomicBoolean

/** Route-owned projections; closing one never closes the process-shared installer. */
object SelectedSpeechResources {
    fun forConversation(
        store: SpeechModelStore,
        config: ConversationSessionConfig,
        recognitionModelIds: Set<String>,
    ): ConversationModelRepository {
        val selections = linkedSetOf(config.vadModelId)
        requireModel(store, config.vadModelId, SpeechModelRole.VAD)
        val recognitionModels = recognitionModelIds.map(store.catalog::model)
        for (model in recognitionModels) {
            require(model.role == SpeechModelRole.ASR) {
                "Selected recognition model has an incompatible role: ${model.id}"
            }
            require(config.languages.any { it in model.languages }) {
                "Selected recognition model has incompatible languages: ${model.id}"
            }
            selections += model.id
        }
        require(recognitionModels.isEmpty() || recognitionModels.flatMap { it.languages }.containsAll(config.languages)) {
            "Selected recognition models do not cover the conversation languages"
        }
        return ConversationResources(store, selections, config)
    }

    fun forRecognition(
        store: SpeechModelStore,
        pairs: Set<LanguagePair>,
        asrModelIds: Map<LanguageId, String>,
        vadModelId: String,
    ): SpeechModelRepository {
        require(pairs.isNotEmpty() && pairs.size <= 2)
        requireModel(store, vadModelId, SpeechModelRole.VAD)
        val selections = linkedSetOf(vadModelId)
        val dependencies = pairs.associateWith { pair ->
            val asr = asrModelIds.getValue(pair.source)
            requireModel(store, asr, SpeechModelRole.ASR, setOf(pair.source))
            selections += asr
            store.catalog.requiredPackages(setOf(vadModelId, asr))
        }
        return RecognitionResources(store, selections, dependencies)
    }

    private fun requireModel(store: SpeechModelStore, id: String, role: SpeechModelRole, languages: Set<LanguageId> = emptySet()) {
        val model = store.catalog.model(id)
        require(model.role == role && model.languages.containsAll(languages)) { "Selected model is incompatible: $id" }
    }

    private abstract class Resources(
        protected val store: SpeechModelStore,
        modelIds: Set<String>,
    ) : AutoCloseable {
        private val selected = MutableStateFlow(modelIds.map(store.catalog::model))
        private var selectedIds = modelIds
        protected val packageIds = store.catalog.requiredPackages(modelIds)
        protected fun selectModels(modelIds: Set<String>) = synchronized(selected) {
            if (selectedIds != modelIds) {
                selectedIds = modelIds
                selected.value = modelIds.map(store.catalog::model)
            }
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val closed = AtomicBoolean(false)
        val models: StateFlow<List<SpeechModelStatus>> = combine(store.packages, selected, ::project)
            .stateIn(scope, SharingStarted.Eagerly, project(store.packages.value, selected.value))

        private fun project(packages: List<SpeechModelStatus>, selected: List<SpeechModelDefinition>): List<SpeechModelStatus> {
            val byId = packages.associateBy { it.id }
            return selected.map { model ->
                val dependencies = model.requiredRefs.map { it.packageId }.distinct().map(byId::getValue)
                val state = when {
                    dependencies.any { it.state == SpeechModelInstallState.ERROR } -> SpeechModelInstallState.ERROR
                    dependencies.any { it.state == SpeechModelInstallState.DOWNLOADING } -> SpeechModelInstallState.DOWNLOADING
                    dependencies.any { it.state == SpeechModelInstallState.VERIFYING } -> SpeechModelInstallState.VERIFYING
                    dependencies.all { it.state == SpeechModelInstallState.READY } -> SpeechModelInstallState.READY
                    else -> SpeechModelInstallState.MISSING
                }
                SpeechModelStatus(
                    id = model.id,
                    displayName = model.displayName,
                    source = dependencies.map { it.source }.distinct().joinToString("; "),
                    license = dependencies.map { it.license }.distinct().joinToString("; "),
                    sizeBytes = dependencies.sumOf { it.sizeBytes },
                    state = state,
                    downloadedBytes = dependencies.sumOf { it.downloadedBytes },
                    errorCode = dependencies.firstOrNull { it.errorCode != null }?.errorCode,
                    role = model.role,
                    languages = model.languages,
                )
            }
        }

        protected fun ensureOpen() { check(!closed.get()) { "Selected speech resources are closed" } }
        suspend fun installSelected() { ensureOpen(); store.install(packageIds) }
        override fun close() { if (closed.compareAndSet(false, true)) scope.cancel() }
    }

    private class ConversationResources(
        store: SpeechModelStore,
        private val selections: Set<String>,
        private val config: ConversationSessionConfig,
    ) : Resources(store, selections), ConversationModelRepository {
        private val basePackages = store.catalog.requiredPackages(setOf(config.vadModelId))
        private val outputModels: Set<String> by lazy {
            if (config.speechOutputBackend == SpeechOutputBackend.PIPER) {
                config.languages.map { language ->
                    config.ttsModelIds.getValue(language).also {
                        requireModel(store, it, SpeechModelRole.TTS, setOf(language))
                    }
                }.toSet()
            } else emptySet()
        }
        private val outputSelections by lazy { selections + outputModels }
        private val outputPackages by lazy { basePackages + store.catalog.requiredPackages(outputModels) }
        private fun requiredPackages(speechOutputRequired: Boolean): Set<String> {
            ensureOpen()
            selectModels(if (speechOutputRequired) outputSelections else selections)
            return if (speechOutputRequired) outputPackages else basePackages
        }
        // Recognition owns its dependencies. Output packages are resolved only on explicit demand.
        override suspend fun install(speechOutputRequired: Boolean) = store.install(requiredPackages(speechOutputRequired))
        override suspend fun isConversationReady(speechOutputRequired: Boolean): Boolean =
            store.availability(requiredPackages(speechOutputRequired))
    }

    private class RecognitionResources(
        store: SpeechModelStore,
        selections: Set<String>,
        private val dependencies: Map<LanguagePair, Set<String>>,
    ) : Resources(store, selections), SpeechModelRepository {
        override suspend fun install() = installSelected()
        override suspend fun isReady(pair: LanguagePair): Boolean {
            ensureOpen()
            return store.availability(dependencies[pair] ?: error("Language pair is not selected"))
        }
    }
}
