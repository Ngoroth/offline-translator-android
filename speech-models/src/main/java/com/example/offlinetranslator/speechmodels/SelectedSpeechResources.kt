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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
        val installedByOwner = linkedSetOf(config.vadModelId)
        requireModel(store, config.vadModelId, SpeechModelRole.VAD)
        val recognitionModels = recognitionModelIds.map(store.catalog::model)
        for (model in recognitionModels) {
            require(model.role == SpeechModelRole.ASR || model.role == SpeechModelRole.LANGUAGE_ID) {
                "Selected recognition model has an incompatible role: ${model.id}"
            }
            require(if (model.role == SpeechModelRole.LANGUAGE_ID) model.languages.containsAll(config.languages)
                else config.languages.any { it in model.languages }) {
                "Selected recognition model has incompatible languages: ${model.id}"
            }
            selections += model.id
        }
        require(recognitionModels.isEmpty() || recognitionModels.filter { it.role == SpeechModelRole.ASR }
            .flatMap { it.languages }.containsAll(config.languages)) {
            "Selected recognition models do not cover the conversation languages"
        }
        if (config.speechOutputBackend == SpeechOutputBackend.PIPER) {
            for (language in config.languages) {
                val tts = config.ttsModelIds.getValue(language)
                requireModel(store, tts, SpeechModelRole.TTS, setOf(language))
                selections += tts
                installedByOwner += tts
            }
        }
        return ConversationResources(store, selections, store.catalog.requiredPackages(installedByOwner))
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
        private val selected = modelIds.map(store.catalog::model)
        protected val packageIds = store.catalog.requiredPackages(modelIds)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val closed = AtomicBoolean(false)
        val models: StateFlow<List<SpeechModelStatus>> = store.packages.map(::project)
            .stateIn(scope, SharingStarted.Eagerly, project(store.packages.value))

        private fun project(packages: List<SpeechModelStatus>): List<SpeechModelStatus> {
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
        selections: Set<String>,
        private val nativePackageIds: Set<String>,
    ) : Resources(store, selections), ConversationModelRepository {
        // Recognition owns its LID/ASR dependencies. Only capture/output packages belong here.
        override suspend fun install() { ensureOpen(); store.install(nativePackageIds) }
        override suspend fun isConversationReady(): Boolean { ensureOpen(); return store.availability(nativePackageIds) }
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
