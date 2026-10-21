package com.example.offlinetranslator.tencent

import com.example.offlinetranslator.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TencentTranslationEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    @Test fun closingDuringDownloadCancelsAndJoinsStagingBeforeReportingReleased() = runBlocking {
        val root = temporary.newFolder()
        val bytes = ByteArray(128 * 1024) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val pin = TencentTranslationArtifact("https://huggingface.co/pinned.gguf", bytes.size.toLong(), digest)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val artifacts = TencentArtifactPreparation(root, pin, { true }, { url ->
            object : HttpURLConnection(url) {
                override fun getResponseCode() = 200
                override fun getHeaderField(name: String) = if (name == "Content-Length") bytes.size.toString() else null
                override fun getInputStream() = stream
                override fun disconnect() { stream.close() }
                override fun usingProxy() = false
                override fun connect() = Unit
            }
        })
        val engine = TencentTranslationEngine(setOf(pair), artifacts)
        val preparing = launch(Dispatchers.Default) { engine.prepare() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.close()
            val closing = async(Dispatchers.Default) { engine.closeAndJoin() }
            assertFalse(preparing.isCompleted)
            assertFalse(closing.isCompleted)
            release.countDown()
            preparing.join()
            closing.await()
            assertTrue(preparing.isCancelled)
            assertFalse(File(root, "tencent-translation/model.part").exists())
            assertNull(artifacts.verifiedFile())
        } finally {
            release.countDown()
            preparing.cancelAndJoin()
            engine.closeAndJoin()
        }
    }

    @Test fun unsupportedOrUnboundedSelectionIsRejectedWithoutNativeOrDownloadEffects() {
        val artifacts = artifacts()
        for (pairs in listOf(emptySet(), setOf(pair, pair.reverse(), LanguagePair(LanguageCatalog.ENGLISH, LanguageCatalog.GERMAN)))) {
            assertTrue(runCatching { TencentTranslationEngine(pairs, artifacts) }.exceptionOrNull() is IllegalArgumentException)
        }
        val error = runCatching { TencentTranslationEngine(setOf(LanguagePair(LanguageCatalog.ENGLISH, LanguageId("zz"))), artifacts) }.exceptionOrNull()
        assertTrue(error is InvalidConfigurationException)
        assertEquals(ConfigurationProblem.UNSUPPORTED_PAIR, (error as InvalidConfigurationException).problem)
    }

    @Test fun invalidRequestsAndMissingReadinessNeverStartNativeOrNetwork() = runBlocking {
        val engine = TencentTranslationEngine(setOf(pair), artifacts())
        try {
            assertTrue(runCatching { engine.translate(pair.reverse(), "Hello") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { engine.translate(pair, " ") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { engine.translate(pair, "x".repeat(4097)) }.exceptionOrNull() is TranslationFailure.InputLimit)
            assertTrue(runCatching { engine.translate(pair, "Привет") }.exceptionOrNull() is TranslationFailure.ModelMissing)
            assertEquals(ModelAvailability.MISSING, engine.modelAvailability())
        } finally { engine.closeAndJoin() }
    }

    private fun artifacts() = TencentArtifactPreparation(temporary.newFolder(), wifiAvailable = { false },
        openConnection = { error("Inference/readiness must never connect") })
}
