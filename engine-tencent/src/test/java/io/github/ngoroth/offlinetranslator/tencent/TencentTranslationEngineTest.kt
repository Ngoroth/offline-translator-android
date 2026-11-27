package io.github.ngoroth.offlinetranslator.tencent

import io.github.ngoroth.offlinetranslator.core.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TencentTranslationEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val pair = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)

    @Test fun prepareLocalMissingArtifactNeverChecksWifiConnectsOrCreatesNative() = runBlocking {
        val root = temporary.newFolder()
        val artifacts = TencentArtifactPreparation(root,
            wifiAvailable = { error("Local preparation must not query Wi-Fi") },
            openConnection = { error("Local preparation must not connect") })
        val engine = TencentTranslationEngine(setOf(pair), artifacts) { error("Missing artifact must not create native inference") }
        try {
            repeat(2) {
                assertEquals(ModelAvailability.MISSING, engine.prepareLocal())
                assertEquals(TranslationPreparationPhase.MISSING, engine.preparation.value.phase)
                assertNull(engine.preparation.value.lastFailure)
            }
            assertFalse(File(root, "tencent-translation/model.part").exists())
        } finally { engine.closeAndJoin() }
    }

    @Test fun prepareLocalCorruptArtifactThrowsTypedFailureWithoutRepairOrNative() = runBlocking {
        val root = temporary.newFolder()
        val bytes = ByteArray(128) { it.toByte() }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        val pin = TencentTranslationArtifact("https://huggingface.co/pinned.gguf", bytes.size.toLong(), digest)
        val corrupt = bytes.copyOf().also { it[0] = 9 }
        val installed = File(root, "tencent-translation/${pin.fileName}").apply {
            parentFile.mkdirs(); writeBytes(corrupt)
        }
        val artifacts = TencentArtifactPreparation(root, pin,
            { error("Local preparation must not query Wi-Fi") },
            { error("Local preparation must not repair or download") })
        val engine = TencentTranslationEngine(setOf(pair), artifacts) { error("Corrupt artifact must not create native inference") }
        try {
            repeat(2) {
                val failure = runCatching { engine.prepareLocal() }.exceptionOrNull()
                assertTrue(failure is TranslationFailure.ArtifactCorrupt)
                assertEquals(TranslationPreparationPhase.FAILED, engine.preparation.value.phase)
                assertSame(failure, engine.preparation.value.lastFailure)
                assertArrayEquals(corrupt, installed.readBytes())
            }
            assertFalse(File(root, "tencent-translation/model.part").exists())
        } finally { engine.closeAndJoin() }
    }

    @Test fun prepareLocalAfterCloseRejectsWithoutArtifactOrNativeWork() = runBlocking {
        val engine = TencentTranslationEngine(setOf(pair), artifacts())
        engine.closeAndJoin()
        assertTrue(runCatching { engine.prepareLocal() }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun defaultPrepareLocalOnlyObservesAvailabilityAndNeverCallsPrepare() = runBlocking {
        var observations = 0
        var availability = ModelAvailability.MISSING
        val engine = object : TranslationEngine {
            override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState())
            override suspend fun modelAvailability(): ModelAvailability { observations++; return availability }
            override suspend fun prepare(): ModelAvailability = error("Default local preparation must not explicitly prepare")
            override suspend fun translate(pair: LanguagePair, text: String): String = error("Must not translate")
            override fun close() = Unit
        }
        val initial = engine.preparation.value
        assertEquals(ModelAvailability.MISSING, engine.prepareLocal())
        availability = ModelAvailability.READY
        assertEquals(ModelAvailability.READY, engine.prepareLocal())
        assertEquals(2, observations)
        assertSame(initial, engine.preparation.value)
    }

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

    @Test fun healthyPreviousModelPreparesOfflineAndFailedUpdateKeepsItUsable() = runBlocking {
        val fixture = MigrationFixture(wifi = false)
        val engine = fixture.engine()
        try {
            assertEquals(ModelAvailability.READY, engine.prepare())
            assertEquals(fixture.previous.descriptor, engine.artifactUpdate.state.value.activeProvider)
            assertTrue(engine.artifactUpdate.state.value.offerAvailable)
            engine.artifactUpdate.update()
            assertTrue(engine.artifactUpdate.state.value.preparation?.lastFailure is PreparationFailure.WifiRequired)
            assertEquals(fixture.previous.descriptor, engine.artifactUpdate.state.value.activeProvider)
            assertTrue(engine.artifactUpdate.state.value.offerAvailable)
            assertEquals(TranslationPreparationPhase.READY, engine.preparation.value.phase)
            assertEquals("old translation", engine.translate(pair, "Здравствуйте"))
            assertEquals(0, fixture.requests)
            assertFalse(fixture.currentFile.exists())
            assertArrayEquals(fixture.previousBytes, fixture.previousFile.readBytes())
        } finally { engine.closeAndJoin() }
    }

    @Test fun rejectedCandidateClosesBeforeReopeningVerifiedPreviousModelAndCanRetry() = runBlocking {
        val fixture = MigrationFixture()
        var rejectCandidate = true
        val engine = fixture.engine(probe = { file ->
            if (file == "model.part" && rejectCandidate) throw TencentNativeException("model_corrupt")
        })
        try {
            assertEquals(ModelAvailability.READY, engine.prepareLocal())
            engine.artifactUpdate.update()
            assertEquals(listOf("load old", "close old", "load stage", "close stage", "load old"), fixture.events)
            assertTrue(engine.artifactUpdate.state.value.preparation?.lastFailure is TranslationFailure.ArtifactCorrupt)
            assertEquals("old translation", engine.translate(pair, "Добрый день"))
            assertFalse(fixture.currentFile.exists())
            assertFalse(fixture.stage.exists())
            assertArrayEquals(fixture.previousBytes, fixture.previousFile.readBytes())
            rejectCandidate = false
            engine.artifactUpdate.update()
            assertEquals(fixture.current.descriptor, engine.artifactUpdate.state.value.activeProvider)
            assertFalse(engine.artifactUpdate.state.value.offerAvailable)
            assertNull(engine.artifactUpdate.state.value.preparation)
            assertEquals("stage translation", engine.translate(pair, "Добрый день"))
            assertFalse(fixture.previousFile.exists())
            assertArrayEquals(fixture.currentBytes, fixture.currentFile.readBytes())
        } finally { engine.closeAndJoin() }
    }

    @Test fun unusablePreviousModelNeverDownloadsLocallyButExplicitPrepareRecoversAfterRecreation() = runBlocking {
        val fixture = MigrationFixture()
        val rejectPrevious: (String) -> Unit = {
            if (it == fixture.previous.fileName) throw TencentNativeException("model_corrupt")
        }
        val first = fixture.engine(probe = rejectPrevious)
        try {
            assertTrue(runCatching { first.prepareLocal() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
            assertEquals(TranslationArtifactUpdateState(), first.artifactUpdate.state.value)
            assertEquals(0, fixture.requests)
            assertArrayEquals(fixture.previousBytes, fixture.previousFile.readBytes())
        } finally { first.closeAndJoin() }
        val recreated = fixture.engine(probe = rejectPrevious)
        try {
            assertEquals(ModelAvailability.READY, recreated.prepare())
            assertEquals(listOf("load old", "close old", "load old", "close old", "load stage"), fixture.events)
            assertEquals(1, fixture.requests)
            assertEquals(fixture.current.descriptor, recreated.artifactUpdate.state.value.activeProvider)
            assertFalse(fixture.previousFile.exists())
        } finally { recreated.closeAndJoin() }
    }

    @Test fun failedNativeReleaseAfterUnusablePreviousProbePreventsAnySecondLoadOrDownload() = runBlocking {
        val fixture = MigrationFixture()
        val releaseFailure = IllegalStateException("Fixture release failure")
        val engine = fixture.engine(probe = { throw TencentNativeException("model_corrupt") }, close = { throw releaseFailure })
        try {
            assertTrue(runCatching { engine.prepare() }.exceptionOrNull() is TranslationFailure.RuntimeLoad)
            assertEquals(listOf("load old", "close old"), fixture.events)
            assertEquals(0, fixture.requests)
            assertFalse(fixture.currentFile.exists())
            assertArrayEquals(fixture.previousBytes, fixture.previousFile.readBytes())
            assertEquals(TranslationArtifactUpdateState(), engine.artifactUpdate.state.value)
        } finally {
            assertSame(releaseFailure, runCatching { engine.closeAndJoin() }.exceptionOrNull())
        }
    }

    @Test fun cancellationDuringCandidateProbeJoinsBeforeCleanupAndAllowsOfflinePreviousModelReentry() = runBlocking {
        val fixture = MigrationFixture()
        val probing = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val engine = fixture.engine(probe = {
            if (it == "model.part") {
                probing.countDown()
                check(finish.await(5, TimeUnit.SECONDS))
            }
        }, cancel = cancelling::countDown)
        val updating = launch(start = CoroutineStart.LAZY) { engine.artifactUpdate.update() }
        try {
            assertEquals(ModelAvailability.READY, engine.prepareLocal())
            updating.start()
            withContext(Dispatchers.IO) { assertTrue(probing.await(5, TimeUnit.SECONDS)) }
            updating.cancel()
            withContext(Dispatchers.IO) { assertTrue(cancelling.await(5, TimeUnit.SECONDS)) }
            assertFalse(updating.isCompleted)
            assertTrue(fixture.stage.exists())
            assertFalse(fixture.currentFile.exists())
            finish.countDown()
            updating.join()
            assertFalse(fixture.stage.exists())
            assertFalse(fixture.currentFile.exists())
            assertArrayEquals(fixture.previousBytes, fixture.previousFile.readBytes())
            assertNull(engine.artifactUpdate.state.value.activeProvider)
            fixture.wifi = false
            assertEquals(ModelAvailability.READY, engine.prepareLocal())
            assertEquals(listOf("load old", "close old", "load stage", "close stage", "load old"), fixture.events)
            assertEquals("old translation", engine.translate(pair, "Привет"))
        } finally {
            finish.countDown()
            updating.cancelAndJoin()
            engine.closeAndJoin()
        }
    }

    @Test fun cancellationImmediatelyAfterRenameKeepsCommittedIdentityAndColdLoadRetiresOnlyPreviousFile() = runBlocking {
        lateinit var updating: Job
        val fixture = MigrationFixture(afterPublish = { updating.cancel() })
        val unrelated = File(fixture.previousFile.parentFile, "unrelated.gguf").apply { writeText("leave intact") }
        val engine = fixture.engine()
        try {
            assertEquals(ModelAvailability.READY, engine.prepareLocal())
            updating = launch(start = CoroutineStart.LAZY) { engine.artifactUpdate.update() }
            updating.start()
            updating.join()
            assertTrue(updating.isCancelled)
            assertArrayEquals(fixture.currentBytes, fixture.currentFile.readBytes())
            assertTrue(fixture.previousFile.exists())
            assertEquals(fixture.current.descriptor, engine.artifactUpdate.state.value.activeProvider)
            assertEquals("stage translation", engine.translate(pair, "Привет"))
        } finally { engine.closeAndJoin() }
        fixture.wifi = false
        val restarted = fixture.engine(store = fixture.newStore())
        try {
            assertEquals(ModelAvailability.READY, restarted.prepareLocal())
            assertEquals("current translation", restarted.translate(pair, "Привет"))
            assertFalse(fixture.previousFile.exists())
            assertEquals("leave intact", unrelated.readText())
            assertEquals(1, fixture.requests)
        } finally { restarted.closeAndJoin() }
    }

    @Test fun corruptCommittedModelNeverFallsBackToPreviousEvenWhenBothFilesExist() = runBlocking {
        val fixture = MigrationFixture(wifi = false)
        fixture.currentFile.writeBytes(fixture.previousBytes)
        val engine = fixture.engine()
        try {
            assertTrue(runCatching { engine.prepareLocal() }.exceptionOrNull() is TranslationFailure.ArtifactCorrupt)
            assertEquals(TranslationArtifactUpdateState(), engine.artifactUpdate.state.value)
            assertTrue(fixture.events.isEmpty())
            assertTrue(fixture.previousFile.exists())
        } finally { engine.closeAndJoin() }
    }

    private inner class MigrationFixture(
        @Volatile var wifi: Boolean = true,
        private val afterPublish: () -> Unit = {},
    ) {
        private val root = temporary.newFolder()
        private val directory = File(root, "tencent-translation").apply { check(mkdir()) }
        val previousBytes = "previous fixture weights".toByteArray()
        val currentBytes = "current fixture weights".toByteArray()
        val previous = pin(TencentTranslationArtifact.RETIRED, previousBytes)
        val current = pin(TencentTranslationArtifact.CURRENT, currentBytes)
        val previousFile = File(directory, previous.fileName).apply { writeBytes(previousBytes) }
        val currentFile = File(directory, current.fileName)
        val stage = File(directory, "model.part")
        val events = mutableListOf<String>()
        var requests = 0
            private set
        private var handleOpen = false
        private val artifacts = newStore()

        fun newStore() = TencentArtifactPreparation(root, current, { wifi }, ::connect,
            retired = previous, afterPublish = afterPublish)

        private fun connect(url: URL): HttpURLConnection {
            assertEquals(current.url, url.toString())
            requests++
            return object : HttpURLConnection(url) {
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(currentBytes)
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun connect() = Unit
            }
        }

        fun engine(
            probe: (String) -> Unit = {},
            close: () -> Unit = {},
            cancel: () -> Unit = {},
            store: TencentArtifactPreparation = artifacts,
        ) = TencentTranslationEngine(setOf(pair), store) {
            check(!handleOpen) { "Two native handles would overlap" }
            handleOpen = true
            object : TencentInference {
                private lateinit var fileName: String
                private val name: String get() = when (fileName) {
                    previous.fileName -> "old"
                    current.fileName -> "current"
                    "model.part" -> "stage"
                    else -> error("Unexpected artifact: $fileName")
                }
                override fun load(modelPath: String) {
                    fileName = File(modelPath).name
                    events += "load $name"
                    assertArrayEquals(if (name == "old") previousBytes else currentBytes, File(modelPath).readBytes())
                }
                override fun translate(target: String, text: String): String {
                    if (text == "1") probe(fileName)
                    return "$name translation"
                }
                override fun resetCancellation() = Unit
                override fun cancel() = cancel.invoke()
                override fun close() {
                    events += "close $name"
                    close.invoke()
                    handleOpen = false
                }
            }
        }

        private fun pin(original: TencentTranslationArtifact, bytes: ByteArray) = original.copy(
            bytes = bytes.size.toLong(),
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
    }

    private fun artifacts() = TencentArtifactPreparation(temporary.newFolder(), wifiAvailable = { false },
        openConnection = { error("Inference/readiness must never connect") })
}
