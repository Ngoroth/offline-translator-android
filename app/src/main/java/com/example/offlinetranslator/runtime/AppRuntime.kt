package com.example.offlinetranslator.runtime

import android.content.Context
import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.mlkit.MlKitPreparationCoordinator
import com.example.offlinetranslator.settings.AppSettingsRepository
import com.example.offlinetranslator.sherpa.*
import com.example.offlinetranslator.speechmodels.SelectedSpeechResources
import com.example.offlinetranslator.speechmodels.SpeechModelCatalog
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import com.example.offlinetranslator.speech.AndroidConversationRecognitionEngine
import com.example.offlinetranslator.speech.SystemConversationSpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.coroutines.coroutineContext

/** Process metadata/settings/installers; native owners live only for the selected foreground route. */
class AppRuntime private constructor(context: Context) {
    private val context = context.applicationContext
    private val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    val settings = AppSettingsRepository(File(this.context.noBackupFilesDir, "settings/app-settings.json"))
    val speechModels = SpeechModelStore(this.context, SherpaSpeechModelCatalog.fromAssets(this.context))
    val catalog: SpeechModelCatalog get() = speechModels.catalog
    val coordinator = MlKitPreparationCoordinator(this.context)
    private val tencentArtifacts = com.example.offlinetranslator.tencent.TencentArtifactPreparation(this.context)
    val registry = TranslationEngineRegistry(this.context, coordinator, tencentArtifacts)
    internal val owners = RuntimeOwnerController(scope)
    internal val conversationCaptureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine =
        { store, selected -> SherpaSpeechCaptureEngine(this.context, store, selected.vadModelId, selected.vadMinSilenceMs) }
    val busy = owners.busy
    val editable: StateFlow<Boolean> = busy.map { !it }.stateIn(scope, SharingStarted.Eagerly, true)
    private val validator = SettingsValidator(catalog, registry.descriptors.map { it.id }.toSet(), registry::capability)
    @Volatile private var closed = false
    private val closeLock = Mutex()
    private var closeResult: Result<Unit>? = null

    fun validationProblems(value: AppSettings): List<ConfigurationProblem> = validator.problems(value)
    fun textProblems(value: AppSettings): List<ConfigurationProblem> = validator.textProblems(value)
    fun conversationProblems(value: AppSettings): List<ConfigurationProblem> = validator.conversationProblems(value)
    fun conversationLanguageCoverage(value: AppSettings): Set<LanguageId> = validator.conversationLanguageCoverage(value)

    /** Advanced settings never carry a stale main-screen pair across the shared ownership gate. */
    suspend fun applySettings(value: AppSettings): Boolean {
        check(!closed)
        var changed: AppSettings? = null
        suspend fun latestPatch(): AppSettings {
            changed?.let { return it }
            val latest = settings.current()
            return latest.copy(
                speechRecognitionBackend = value.speechRecognitionBackend,
                speechRecognitionService = value.speechRecognitionService,
                speechOutputBackend = value.speechOutputBackend,
                translationEngineId = value.translationEngineId,
                vadModelId = value.vadModelId,
                vadMinSilenceMs = value.vadMinSilenceMs,
                ttsSpeedPercent = value.ttsSpeedPercent,
            ).also { requireValid(it); changed = it }
        }
        return owners.saveIfIdle(
            retainOwner = { owner ->
                val patched = latestPatch()
                when (owner) {
                    is TextRuntimeOwner -> owner.config == patched.textConfig()
                    is ConversationRuntimeOwner -> matches(patched, owner.config)
                    else -> false
                }
            },
        ) { settings.save(latestPatch()) }
    }

    /** First selection never constructs or displaces a route owner, or prepares resources. */
    suspend fun completeTranslationSetup(engineId: String): Boolean {
        check(!closed)
        return owners.saveIfUnowned {
            val latest = settings.current()
            if (latest.translationSetupComplete) return@saveIfUnowned false
            val selected = latest.copy(translationEngineId = engineId, translationSetupComplete = true)
            requireValid(selected)
            coroutineContext.ensureActive()
            settings.save(selected)
            true
        }
    }

    /** The route releases its owner first; this commit cannot steal a subsequent contender. */
    suspend fun updateLanguagePair(expectedPair: LanguagePair, selectedPair: LanguagePair): Boolean {
        check(!closed)
        return owners.saveIfUnowned {
            val latest = settings.current()
            if (latest.languagePair != expectedPair) return@saveIfUnowned false
            if (latest.languagePair == selectedPair) return@saveIfUnowned true
            val changed = latest.copy(languagePair = selectedPair)
            requireValid(changed)
            coroutineContext.ensureActive()
            settings.save(changed)
            true
        }
    }

    suspend fun createTextOwner(config: TextRuntimeConfig): TextRuntimeOwner = owners.replace {
        check(!closed)
        val current = settings.current()
        requireTextValid(current)
        if (current.textConfig() != config) throw CancellationException("Stale text configuration")
        TextRuntimeOwner(config, registry.create(config.engineId, setOf(config.pair)))
    }

    suspend fun createTextOwnerIfUnowned(config: TextRuntimeConfig): TextRuntimeOwner? = owners.createIfUnowned(
        isFresh = {
            check(!closed)
            val current = settings.current()
            if (current.textConfig() != config) false else {
                requireTextValid(current)
                true
            }
        },
        build = { TextRuntimeOwner(config, registry.create(config.engineId, setOf(config.pair))) },
    )

    suspend fun createConversationOwner(
        config: ConversationSessionConfig,
        captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine = conversationCaptureFactory,
    ): ConversationRuntimeOwner = owners.replace {
        check(!closed)
        val current = settings.current()
        requireConversationValid(current)
        if (!matches(current, config)) throw CancellationException("Stale conversation configuration")
        buildConversationOwner(config, captureFactory)
    }

    suspend fun createConversationOwnerIfUnowned(
        config: ConversationSessionConfig,
        captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine = conversationCaptureFactory,
    ): ConversationRuntimeOwner? = owners.createIfUnowned(
        isFresh = {
            check(!closed)
            val current = settings.current()
            if (!matches(current, config)) false else {
                requireConversationValid(current)
                true
            }
        },
        build = { buildConversationOwner(config, captureFactory) },
    )

    private suspend fun buildConversationOwner(
        config: ConversationSessionConfig,
        captureFactory: (SpeechModelStore, ConversationSessionConfig) -> SpeechCaptureEngine,
    ): ConversationRuntimeOwner {
        val ownerScope = CoroutineScope(SupervisorJob(job) + Dispatchers.Main.immediate)
        var models: ConversationModelRepository? = null
        var translation: TranslationEngine? = null
        var capture: SpeechCaptureEngine? = null
        var recognition: ConversationRecognitionEngine? = null
        var output: ConversationSpeechOutput? = null
        try {
            val recognitionModelIds = recognitionModelIds(config)
            models = SelectedSpeechResources.forConversation(speechModels, config, recognitionModelIds)
            translation = registry.create(config.engineId, config.pairs)
            capture = captureFactory(speechModels, config)
            recognition = when (config.speechRecognitionBackend) {
                SpeechRecognitionBackend.SYSTEM_OFFLINE -> AndroidConversationRecognitionEngine(context, config)
                SpeechRecognitionBackend.WHISPER -> WhisperConversationRecognitionEngine(speechModels, config)
            }
            output = when (config.speechOutputBackend) {
                SpeechOutputBackend.SYSTEM_OFFLINE -> SystemConversationSpeechOutput(context, config)
                SpeechOutputBackend.PIPER -> SherpaConversationSpeechOutput(context, speechModels, config)
            }
            return ConversationRuntimeOwner(config, models, capture, recognition, translation, output, ownerScope)
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { capture?.closeAndJoin() } catch (closing: Throwable) {
                    appendReleaseFailure(failure, closing)
                }
                try { output?.closeAndJoin() } catch (closing: Throwable) {
                    appendReleaseFailure(failure, closing)
                }
                try { recognition?.closeAndJoin() } catch (closing: Throwable) {
                    appendReleaseFailure(failure, closing)
                }
                try { translation?.closeAndJoin() } catch (closing: Throwable) {
                    appendReleaseFailure(failure, closing)
                }
                try { models?.close() } catch (closing: Throwable) {
                    appendReleaseFailure(failure, closing)
                }
                ownerScope.cancel()
            }
            throw failure
        }
    }
    private fun recognitionModelIds(config: ConversationSessionConfig): Set<String> = when (config.speechRecognitionBackend) {
        SpeechRecognitionBackend.SYSTEM_OFFLINE -> emptySet()
        SpeechRecognitionBackend.WHISPER -> setOf(WhisperConversationRecognitionEngine.MODEL_ID)
    }


    suspend fun conversationReady(owner: ConversationRuntimeOwner): Boolean = owners.use(owner) {
        owner.output.readiness() == SpeechOutputReadiness.READY &&
            owner.models.isConversationReady() && owner.translation.modelAvailability() == ModelAvailability.READY
    } ?: false

    suspend fun prepareConversation(owner: ConversationRuntimeOwner): Boolean = owners.use(owner) {
        owner.models.install()
        val recognitionPending = when (owner.recognition.readinessState.value) {
            SpeechRecognitionReadiness.SCHEDULED, SpeechRecognitionReadiness.DOWNLOADING,
            SpeechRecognitionReadiness.DOWNLOAD_FAILED -> true
            else -> false
        }
        owner.translation.prepare() == ModelAvailability.READY &&
            owner.output.readiness() == SpeechOutputReadiness.READY && !recognitionPending &&
            owner.models.isConversationReady()
    } ?: false

    suspend fun startConversation(owner: ConversationRuntimeOwner): Boolean = owners.use(owner) { owner.pipeline.start() } ?: false

    suspend fun <T> useText(owner: TextRuntimeOwner, operation: suspend (TranslationEngine) -> T): T? =
        owners.use(owner) { owner.operation { operation(owner.translation) } }

    suspend fun release(owner: RuntimeOwner) = owners.release(owner)

    suspend fun closeAndJoin() {
        val result = withContext(NonCancellable) {
            closed = true
            closeLock.withLock {
                closeResult?.let { return@withLock it }
                var failure: Throwable? = null
                try { owners.closeAndJoin() } catch (closing: Throwable) {
                    failure = appendReleaseFailure(failure, closing)
                }
                try { settings.closeAndJoin() } catch (closing: Throwable) {
                    failure = appendReleaseFailure(failure, closing)
                }
                try { speechModels.close() } catch (closing: Throwable) {
                    failure = appendReleaseFailure(failure, closing)
                }
                try { job.cancelAndJoin() } catch (closing: Throwable) {
                    failure = appendReleaseFailure(failure, closing)
                }
                synchronized(Companion) { if (instance === this@AppRuntime) instance = null }
                (if (failure == null) Result.success(Unit) else Result.failure(requireNotNull(failure)))
                    .also { closeResult = it }
            }
        }
        result.getOrThrow()
    }

    private fun requireValid(current: AppSettings) {
        val problems = validationProblems(current)
        if (problems.isNotEmpty()) throw InvalidConfigurationException(problems.first())
    }

    private fun requireTextValid(current: AppSettings) {
        textProblems(current).firstOrNull()?.let { throw InvalidConfigurationException(it) }
    }

    private fun requireConversationValid(current: AppSettings) {
        conversationProblems(current).firstOrNull()?.let { throw InvalidConfigurationException(it) }
    }

    internal fun matches(settings: AppSettings, config: ConversationSessionConfig): Boolean =
        config.languages.size == 2 &&
            settings.languagePair.source == config.languages[0] && settings.languagePair.target == config.languages[1] &&
            settings.speechOutputBackend == config.speechOutputBackend && settings.translationEngineId == config.engineId &&
            settings.speechRecognitionBackend == config.speechRecognitionBackend &&
            settings.speechRecognitionService == config.speechRecognitionService &&
            settings.vadModelId == config.vadModelId &&
            settings.vadMinSilenceMs == config.vadMinSilenceMs && settings.ttsSpeedPercent == config.ttsSpeedPercent

    companion object {
        @Volatile private var instance: AppRuntime? = null
        fun get(context: Context): AppRuntime = instance ?: synchronized(this) {
            instance ?: AppRuntime(context).also { instance = it }
        }
    }
}

/** Pure metadata validation: no provider factories, installed-data probes, digests, downloads or JNI. */
class SettingsValidator(
    private val catalog: SpeechModelCatalog,
    private val engineIds: Set<String>,
    private val capability: (String, LanguagePair) -> TranslationCapability,
) {
    fun textProblems(settings: AppSettings): List<ConfigurationProblem> =
        pairProblems(settings.translationEngineId, settings.languagePair)

    /** Saved settings and Text do not require voice-language coverage. */
    fun problems(settings: AppSettings): List<ConfigurationProblem> {
        val result = LinkedHashSet(textProblems(settings))
        modelProblem(settings.vadModelId, SpeechModelRole.VAD, emptySet())?.let(result::add)
        if (settings.speechRecognitionBackend == SpeechRecognitionBackend.WHISPER) {
            modelProblem(WhisperConversationRecognitionEngine.MODEL_ID, SpeechModelRole.ASR, emptySet())?.let(result::add)
        }
        if (settings.vadMinSilenceMs !in 300..1500) result += ConfigurationProblem.INVALID_SILENCE
        if (settings.ttsSpeedPercent !in 75..125) result += ConfigurationProblem.INVALID_SPEED
        return result.toList()
    }

    fun conversationProblems(settings: AppSettings): List<ConfigurationProblem> {
        val result = LinkedHashSet(problems(settings))
        result += pairProblems(settings.translationEngineId, settings.languagePair)
        result += pairProblems(settings.translationEngineId, settings.languagePair.reverse())
        val languages = setOf(settings.languagePair.source, settings.languagePair.target)
        if (settings.speechRecognitionBackend == SpeechRecognitionBackend.WHISPER) {
            modelProblem(WhisperConversationRecognitionEngine.MODEL_ID, SpeechModelRole.ASR, languages)?.let(result::add)
        }
        languages.forEach { language ->
            if (settings.speechRecognitionBackend == SpeechRecognitionBackend.SYSTEM_OFFLINE &&
                LanguageCatalog.find(language) == null) {
                result += ConfigurationProblem.UNKNOWN_LANGUAGE
            }
            if (settings.speechOutputBackend == SpeechOutputBackend.PIPER) {
                modelProblem(RecommendedConversationVoices.modelId(language), SpeechModelRole.TTS, setOf(language))?.let(result::add)
            } else if (LanguageCatalog.find(language) == null) {
                result += ConfigurationProblem.UNSUPPORTED_VOICE
            }
        }
        return result.toList()
    }

    /** Coverage labels are metadata, not an assertion that models/voice data have been installed. */
    fun conversationLanguageCoverage(settings: AppSettings): Set<LanguageId> =
        LanguageCatalog.entries.asSequence().map { it.id }.filter { language ->
            val recognitionSupported = when (settings.speechRecognitionBackend) {
                SpeechRecognitionBackend.SYSTEM_OFFLINE -> true
                SpeechRecognitionBackend.WHISPER ->
                    modelProblem(WhisperConversationRecognitionEngine.MODEL_ID, SpeechModelRole.ASR, setOf(language)) == null
            }
            recognitionSupported && if (settings.speechOutputBackend == SpeechOutputBackend.PIPER) {
                modelProblem(RecommendedConversationVoices.modelId(language), SpeechModelRole.TTS, setOf(language)) == null
            } else true
        }.toSet()

    private fun pairProblems(engine: String, pair: LanguagePair): List<ConfigurationProblem> {
        val result = ArrayList<ConfigurationProblem>(2)
        if (LanguageCatalog.find(pair.source) == null || LanguageCatalog.find(pair.target) == null) {
            result += ConfigurationProblem.UNKNOWN_LANGUAGE
        }
        if (engine !in engineIds) result += ConfigurationProblem.UNKNOWN_ENGINE
        else if (capability(engine, pair) == TranslationCapability.Unsupported) result += ConfigurationProblem.UNSUPPORTED_PAIR
        return result
    }

    private fun modelProblem(id: String?, role: SpeechModelRole, languages: Set<LanguageId>): ConfigurationProblem? {
        val definition = catalog.models.firstOrNull { it.id == id }
            ?: return if (id == null && role == SpeechModelRole.TTS) ConfigurationProblem.UNSUPPORTED_VOICE
            else ConfigurationProblem.UNKNOWN_MODEL
        return if (definition.role != role || (role != SpeechModelRole.VAD && !definition.languages.containsAll(languages))) {
            ConfigurationProblem.INCOMPATIBLE_MODEL
        } else null
    }

}
