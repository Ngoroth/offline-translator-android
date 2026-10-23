package com.example.offlinetranslator.mlkit

import android.content.Context
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.PreparationFailure
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/** Process-scoped SDK model checks and downloads, independent of inference clients' lifetimes. */
class MlKitPreparationCoordinator internal constructor(
    private val models: ModelGateway,
    private val wifi: WifiConnectivity,
    private val preparationTimeoutMillis: Long = PREPARATION_TIMEOUT_MILLIS,
) {
    private val presenceLock = Any()
    private val pendingPresence = mutableMapOf<LanguageId, PendingOperation<Boolean>>()
    private val downloadLock = Any()
    private val pendingDownloads = mutableMapOf<LanguageId, PendingOperation<Unit>>()

    constructor(context: Context) : this(
        GoogleModelGateway(),
        AndroidWifiConnectivity(context.applicationContext),
    )

    internal suspend fun modelAvailability(languages: Set<LanguageId>): ModelAvailability {
        var ready = true
        for (language in languages) {
            require(language != LanguageCatalog.ENGLISH) { "English is built into ML Kit" }
            if (!currentOrNewPresence(language).await()) ready = false
        }
        return if (ready) ModelAvailability.READY else ModelAvailability.MISSING
    }

    internal suspend fun prepare(languages: Set<LanguageId>, downloading: () -> Unit = {}): ModelAvailability {
        try {
            val missing = linkedSetOf<LanguageId>()
            for (language in languages) {
                require(language != LanguageCatalog.ENGLISH) { "English is built into ML Kit" }
                if (!currentOrNewPresence(language).await()) missing += language
            }
            if (missing.isEmpty()) return ModelAvailability.READY
            if (!wifi.isValidatedWifi()) throw PreparationFailure.WifiRequired()
            downloading()

            // Retain the actual SDK operation, not a cancellable coroutine proxy.
            // Every owner preparing this language joins it until its own completion.
            val downloads = missing.map(::currentOrNewDownload)
            withTimeout(preparationTimeoutMillis) {
                coroutineScope {
                    val wifiLost = async { wifi.validatedWifiChanges().first { connected -> !connected } }
                    val completed = async { downloads.forEach { it.await() } }
                    try {
                        select<Unit> {
                            completed.onAwait { }
                            wifiLost.onAwait { throw PreparationFailure.WifiRequired() }
                        }
                    } finally {
                        completed.cancel()
                        wifiLost.cancel()
                    }
                }
                check(modelAvailability(languages) == ModelAvailability.READY) {
                    "Provider completed without installing selected translation models"
                }
            }
            return ModelAvailability.READY
        } catch (failure: PreparationFailure) {
            throw failure
        } catch (timeout: TimeoutCancellationException) {
            throw PreparationFailure.PreparationTimedOut(timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw PreparationFailure.ProviderFailure(error)
        }
    }

    private fun currentOrNewPresence(language: LanguageId): PendingOperation<Boolean> = synchronized(presenceLock) {
        pendingPresence[language] ?: models.isModelDownloaded(language).also { started ->
            pendingPresence[language] = started
            started.onComplete {
                synchronized(presenceLock) {
                    if (pendingPresence[language] === started) pendingPresence.remove(language)
                }
            }
        }
    }

    private fun currentOrNewDownload(language: LanguageId): PendingOperation<Unit> = synchronized(downloadLock) {
        pendingDownloads[language] ?: models.downloadModelWifiOnly(language).also { started ->
            pendingDownloads[language] = started
            started.onComplete {
                synchronized(downloadLock) {
                    if (pendingDownloads[language] === started) pendingDownloads.remove(language)
                }
            }
        }
    }

    companion object {
        private const val PREPARATION_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    }
}

internal interface ModelGateway {
    fun isModelDownloaded(language: LanguageId): PendingOperation<Boolean>
    fun downloadModelWifiOnly(language: LanguageId): PendingOperation<Unit>
}

private class GoogleModelGateway : ModelGateway {
    private val manager = RemoteModelManager.getInstance()

    override fun isModelDownloaded(language: LanguageId): PendingOperation<Boolean> =
        GoogleTaskOperation(manager.isModelDownloaded(model(language)))

    override fun downloadModelWifiOnly(language: LanguageId): PendingOperation<Unit> =
        GoogleVoidTaskOperation(manager.download(model(language), DownloadConditions.Builder().requireWifi().build()))

    private fun model(language: LanguageId) = TranslateRemoteModel.Builder(language.value).build()
}
