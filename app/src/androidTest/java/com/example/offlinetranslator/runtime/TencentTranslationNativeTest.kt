package com.example.offlinetranslator.runtime

import android.net.ConnectivityManager
import androidx.test.platform.app.InstrumentationRegistry
import com.example.offlinetranslator.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Real compiled registry/owner/JNI scenarios. Prepare pinned weights through app UI before going offline. */
class TencentTranslationNativeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var runtime: AppRuntime
    private lateinit var original: AppSettings

    @Before fun selectTencent() = runBlocking {
        assertOffline()
        runtime = AppRuntime.get(context)
        original = runtime.settings.current()
        commitNativeSettings(runtime, original.copy(
            languagePair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH),
            textEngineId = "tencent", conversationEngineId = "tencent",
        ))
    }

    @After fun restoreSettings() = runBlocking {
        if (!::original.isInitialized) return@runBlocking
        runtime.closeAndJoin()
        val restoring = AppRuntime.get(context)
        try { commitNativeSettings(restoring, original) } finally { restoring.closeAndJoin() }
    }

    @Test fun preparedOfflineTextWorksAfterRuntimeRestartWithoutDownloading() = runBlocking {
        val first = translateSelected("Привет! Как ты?")
        assertTrue(first.lowercase().contains("hello"))
        runtime.closeAndJoin()
        runtime = AppRuntime.get(context)
        assertEquals("tencent", runtime.settings.current().textEngineId)
        val reopened = translateSelected("Привет! Как ты?")
        assertEquals(first, reopened)
        assertOffline()
    }

    @Test fun cancelledRequestJoinsBeforeOwnerReplacementAndCannotDeliverLateText() = runBlocking {
        val selected = runtime.settings.current()
        val owner = runtime.createTextOwner(selected.textConfig())
        val delivered = AtomicBoolean(false)
        try {
            assertEquals(ModelAvailability.READY, runtime.useText(owner) { it.prepare() })
            val request = async(Dispatchers.Default) {
                runtime.useText(owner) { it.translate(selected.languagePair, "旅".repeat(4096)) }
                delivered.set(true)
            }
            withTimeout(10_000) { owner.activity.first { it } }
            request.cancelAndJoin()
            assertFalse(delivered.get())
            assertFalse(owner.activity.value)
        } finally { runtime.release(owner) }
        assertTrue(runtime.updateLanguagePair(selected.languagePair, selected.languagePair.reverse()))
        val next = translateSelected("Hello! How are you?")
        assertTrue(next.contains("Привет"))
        assertFalse(delivered.get())
        assertOffline()
    }

    private suspend fun translateSelected(source: String): String {
        val selected = runtime.settings.current()
        val owner = runtime.createTextOwner(selected.textConfig())
        try {
            // Explicit local preparation only: offline prerequisite prohibits any repair download.
            assertEquals(ModelAvailability.READY, runtime.useText(owner) { it.prepare() })
            assertEquals(TranslationPreparationPhase.READY, owner.translation.preparation.value.phase)
            return requireNotNull(runtime.useText(owner) { it.translate(selected.languagePair, source) })
        } finally { runtime.release(owner) }
    }

    private fun assertOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        assertNull("Disconnect networking before this explicit offline scenario", connectivity.activeNetwork)
        assertTrue("No active networks may remain", connectivity.allNetworks.isEmpty())
    }
}
