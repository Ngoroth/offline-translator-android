package com.example.offlinetranslator.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.example.offlinetranslator.core.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** One owner per file; ordinary I/O failures never replace the saved document. */
class AppSettingsRepository internal constructor(
    val file: File,
    dispatcher: CoroutineDispatcher,
    serializer: Serializer<AppSettings>,
) {
    constructor(
        file: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(file, dispatcher, AppSettingsSerializer())
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val notice = MutableStateFlow(false)
    val corruptionNotice: StateFlow<Boolean> = notice.asStateFlow()
    private val mutableState = MutableStateFlow(SettingsLoadState())
    val state: StateFlow<SettingsLoadState> = mutableState.asStateFlow()
    private val store: DataStore<AppSettings> = DataStoreFactory.create(
        serializer = serializer,
        corruptionHandler = ReplaceFileCorruptionHandler {
            notice.value = true
            try { file.delete() } catch (_: Exception) {}
            AppSettings()
        },
        scope = scope,
        produceFile = { file },
    )
    private var collection: Job? = null
    @Volatile private var closed = false

    init { retry() }

    @Synchronized
    fun retry() {
        if (closed) return
        collection?.cancel()
        mutableState.value = mutableState.value.copy(loading = true, loadError = false)
        collection = scope.launch {
            try {
                store.data.collect { settings ->
                    mutableState.value = SettingsLoadState(settings = settings, loading = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                mutableState.value = SettingsLoadState(loading = false, loadError = true)
            }
        }
    }

    suspend fun current(): AppSettings {
        val loaded = state.first { !it.loading }
        if (loaded.loadError || loaded.settings == null) throw IOException("Settings unavailable")
        return loaded.settings
    }

    suspend fun save(settings: AppSettings): AppSettings {
        check(!closed)
        val current = state.first { !it.loading }
        if (current.loadError) throw IOException("Settings unavailable")
        val saved = store.updateData { settings }
        mutableState.value = SettingsLoadState(settings = saved, loading = false)
        notice.value = false
        return saved
    }
    /** Patch the actual latest document inside DataStore, rejecting revoked live requests. */
    suspend fun updateSpeechOutput(enabled: Boolean, isFresh: () -> Boolean): Boolean {
        check(!closed)
        val loaded = state.first { !it.loading }
        if (loaded.loadError) throw IOException("Settings unavailable")
        var applied = false
        val saved = store.updateData { latest ->
            if (!isFresh()) latest else {
                applied = true
                latest.copy(speechOutputEnabled = enabled)
            }
        }
        if (applied) {
            mutableState.value = SettingsLoadState(settings = saved, loading = false)
            notice.value = false
        }
        return applied
    }


    suspend fun closeAndJoin() {
        synchronized(this) { closed = true; collection?.cancel() }
        job.cancelAndJoin()
    }
}

data class SettingsLoadState(
    val settings: AppSettings? = null,
    val loading: Boolean = true,
    val loadError: Boolean = false,
)
