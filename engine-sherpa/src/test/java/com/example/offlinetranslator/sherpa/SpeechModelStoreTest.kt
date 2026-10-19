package com.example.offlinetranslator.sherpa

import android.content.Context
import android.content.ContextWrapper
import com.example.offlinetranslator.core.SpeechModelInstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SpeechModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun evaluationFactoryCanonicalizesTrustedContextRootOnly() {
        val root=temporary.newFolder()
        assertEquals(File(root,"speech-models").canonicalFile,SpeechModelStore.evaluationBase(context(File(root,"child/.."))))
    }

    @Test fun evaluationReusesVerifiedFilesAcrossStoresAndRepairsCorruptDestination() = runBlocking {
        val root=temporary.newFolder();val source=temporary.newFolder();val fixture=modelSet("reuse")
        fixture.catalog.packages.forEach {pkg ->File(source,pkg.id).mkdirs();File(File(source,pkg.id),"weights.bin").writeBytes(fixture.bytes.getValue(pkg.id))}
        val first=store(root,fixture,{error("no download")})
        first.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source)
        assertEquals(fixture.bytes.values.sumOf {it.size.toLong()},first.evaluationImportCopiedBytes);first.close()
        val second=store(root,fixture,{error("no download")})
        second.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source)
        assertEquals(0L,second.evaluationImportCopiedBytes);assertEquals(3,second.evaluationImportReusedPackages)
        val pkg=fixture.catalog.packages.first();File(installedDirectory(root,pkg),"weights.bin").writeText("corrupt")
        second.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source)
        assertEquals(pkg.files.single().bytes,second.evaluationImportCopiedBytes);assertInstalled(fixture,root)
        File(installedDirectory(root,pkg),"foreign").writeText("keep")
        assertTrue(runCatching {second.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source)}.isFailure)
        assertEquals("keep",File(installedDirectory(root,pkg),"foreign").readText());second.close()
    }

    @Test fun verifiedEvaluationImportPublishesProductionLayoutWithoutDownloader() = runBlocking {
        val root=temporary.newFolder("evaluation-private");val source=temporary.newFolder("evaluation-input")
        val fixture=modelSet("evaluation")
        fixture.catalog.packages.forEach { pkg -> File(source,pkg.id).mkdirs();File(File(source,pkg.id),"weights.bin").writeBytes(fixture.bytes.getValue(pkg.id)) }
        val imported=store(root,fixture,{ error("evaluation must never download") })
        imported.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source)
        imported.install(fixture.ids);assertInstalled(fixture,root)
        assertTrue(imported.availability(fixture.ids))
        imported.close()
    }

    @Test fun wrongCallerOrCorruptInputCannotPublishEvaluationPointers() = runBlocking {
        val root=temporary.newFolder("rejected-private");val source=temporary.newFolder("rejected-input")
        val fixture=modelSet("rejected")
        fixture.catalog.packages.forEach { pkg -> File(source,pkg.id).mkdirs();File(File(source,pkg.id),"weights.bin").writeBytes(fixture.bytes.getValue(pkg.id)) }
        val imported=store(root,fixture,{ error("evaluation must never download") })
        assertTrue(runCatching { imported.importEvaluationFiles("com.example.offlinetranslator",source) }.isFailure)
        File(File(source,fixture.catalog.packages.last().id),"weights.bin").writeBytes(byteArrayOf(0))
        assertTrue(runCatching { imported.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source) }.isFailure)
        assertTrue(File(root,"speech-models").listFiles().orEmpty().none { it.name.startsWith("active-") })
        imported.close()
    }

    @Test fun failedEvaluationRenameCleansPartialStagingWithoutPublishing() = runBlocking {
        val root=temporary.newFolder("partial-private");val source=temporary.newFolder("partial-input")
        val fixture=modelSet("partial")
        fixture.catalog.packages.forEach { pkg -> File(source,pkg.id).mkdirs();File(File(source,pkg.id),"weights.bin").writeBytes(fixture.bytes.getValue(pkg.id)) }
        val imported=SpeechModelStore(context(root),fixture.catalog,SpeechModelDownloader { error("no downloads") },
            SpeechArchiveExtractor(),{ Long.MAX_VALUE },{ _,_ -> error("injected publication failure") })
        assertTrue(runCatching { imported.importEvaluationFiles("com.example.offlinetranslator.modelsmoke",source) }.isFailure)
        assertTrue(File(root,"speech-models").walkTopDown().none { it.isFile })
        imported.close()
    }

    @Test fun selectedSevenPackagesVerifyAfterRestart() = runBlocking {
        val root = temporary.newFolder("conversation-private")
        val fixture = modelSet("conversation", conversation = true)
        TestHttpsServer { exchange -> sendFull(exchange, fixture.bytes.getValue(exchange.packageId())) }.use { https ->
            val first = store(root, fixture, https::openConnection)
            assertTrue(!first.availability(fixture.ids))
            first.install(fixture.ids)
            assertTrue(first.availability(fixture.ids))
            first.close()
        }
        val restarted = store(root, fixture, { error("installed conversation must work offline") })
        assertTrue(restarted.availability(fixture.ids))
        assertEquals(7, restarted.packages.value.count { it.state == SpeechModelInstallState.READY })
        restarted.close()
        val voice = fixture.catalog.packages.last()
        File(installedDirectory(root, voice), "weights.bin").writeText("corrupt")
        val corrupt = store(root, fixture, { error("readiness must never download") })
        assertTrue(!corrupt.availability(fixture.ids))
        assertEquals(SpeechModelInstallState.MISSING,
            corrupt.packages.value.single { it.id == voice.id }.state)
        corrupt.close()
    }

    @Test fun cachedConversationReadinessRejectsMissingAndModifiedFilesWithoutDownloading() = runBlocking {
        val root = temporary.newFolder("cached-conversation-private")
        val fixture = modelSet("cached-conversation", conversation = true)
        TestHttpsServer { exchange -> sendFull(exchange, fixture.bytes.getValue(exchange.packageId())) }.use { https ->
            val installed = store(root, fixture, https::openConnection)
            installed.install(fixture.ids)
            assertTrue(installed.availability(fixture.ids))
            val voice = fixture.catalog.packages.last()
            val file = File(installedDirectory(root, voice), "weights.bin")
            val modified = file.lastModified()
            val original = fixture.bytes.getValue(voice.id)
            file.writeBytes(ByteArray(original.size))
            assertTrue(file.setLastModified(modified))
            assertTrue(!installed.availability(fixture.ids))
            assertEquals(SpeechModelInstallState.MISSING,
                installed.packages.value.single { it.id == voice.id }.state)
            assertTrue(runCatching { installed.modelFile(voice.id, "weights.bin") }.isFailure)
            file.writeBytes(original)
            assertTrue(installed.availability(fixture.ids))
            assertTrue(file.delete())
            assertTrue(!installed.availability(fixture.ids))
            installed.close()
        }
    }

    @Test fun closeBetweenVerifiedRenameAndPointerPublicationCannotMarkModelsReady() = runBlocking {
        val root = temporary.newFolder("closed-install-private")
        val fixture = modelSet("closed-install")
        TestHttpsServer { exchange -> sendFull(exchange, fixture.bytes.getValue(exchange.packageId())) }.use { https ->
            lateinit var installing: SpeechModelStore
            installing = SpeechModelStore(
                context(root), fixture.catalog, SpeechModelDownloader(https::openConnection),
                SpeechArchiveExtractor(), { Long.MAX_VALUE },
            ) { source, destination ->
                Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                if (source.name.startsWith("unpacked-")) installing.close()
            }
            assertTrue(runCatching { installing.install(fixture.ids) }.isFailure)
            assertTrue(installing.packages.value.none { it.state == SpeechModelInstallState.READY })
            assertTrue(File(root, "speech-models").listFiles().orEmpty().none { it.name.startsWith("active-") })
        }
    }

    @Test fun insufficientSpaceKeepsThePreviouslyActiveVersionIntact() = runBlocking {
        val root = temporary.newFolder("app-private")
        val versionOne = modelSet("v1")
        TestHttpsServer { exchange -> sendFull(exchange, versionOne.bytes.getValue(exchange.packageId())) }.use { https ->
            val first = store(root, versionOne, https::openConnection)
            first.install(versionOne.ids)
            first.close()
        }
        assertInstalled(versionOne, root)

        val versionTwo = modelSet("v2")
        val limited = store(
            root,
            versionTwo,
            { error("low-space install must fail before opening HTTPS") },
            availableBytes = { 0L },
        )
        val failure = runCatching { limited.install(versionTwo.ids) }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("Insufficient storage"))
        assertInstalled(versionOne, root)
        versionTwo.catalog.packages.forEach { packageInfo ->
            assertEquals(
                if (packageInfo.id == versionTwo.catalog.packages.first().id) {
                    SpeechModelInstallState.ERROR
                } else {
                    SpeechModelInstallState.MISSING
                },
                limited.packages.value.single { it.id == packageInfo.id }.state,
            )
        }
    }

    @Test fun interruptedUpgradeResumesAfterStoreRecreationAndPreservesOldFiles() = runBlocking {
        val root = temporary.newFolder("app-private")
        val versionOne = modelSet("v1")
        val versionTwo = modelSet("v2")
        val interruptOnce = AtomicBoolean(true)
        val resumedRequests = AtomicInteger()
        val interruptedPackage = versionTwo.catalog.packages.first().id

        TestHttpsServer { exchange ->
            sendFull(exchange, versionOne.bytes.getValue(exchange.packageId()))
        }.use { https ->
            store(root, versionOne, https::openConnection).useInstall(versionOne.ids)

            val upgradeTransport: (URL) -> HttpURLConnection = { url ->
                val id = url.path.removePrefix("/")
                RecoveryConnection(
                    url,
                    versionTwo.bytes.getValue(id),
                    id == interruptedPackage && interruptOnce.compareAndSet(true, false),
                    resumedRequests,
                )
            }
            val failedUpgrade = store(root, versionTwo, upgradeTransport)
            val interruption = runCatching { failedUpgrade.install(versionTwo.ids) }.exceptionOrNull()
            assertNotNull("the fixture must interrupt the HTTPS response", interruption)
            failedUpgrade.close()

            val staging = File(root, "speech-models/staging/$interruptedPackage")
            val partialBytes = File(staging, "archive.part").length()
            assertTrue(partialBytes > 0 && partialBytes < versionTwo.bytes.getValue(interruptedPackage).size.toLong())
            assertTrue(File(staging, "archive.validator").isFile)
            assertInstalled(versionOne, root)

            val recovered = store(root, versionTwo, upgradeTransport)
            recovered.install(versionTwo.ids)
            recovered.close()

            assertTrue("the new store must resume the partial HTTPS download", resumedRequests.get() >= 1)
            assertInstalled(versionTwo, root)
            versionOne.catalog.packages.forEach { oldPackage ->
                val oldDirectory = installedDirectory(root, oldPackage)
                assertTrue("the active version remains available during upgrade", oldDirectory.isDirectory)
                assertArrayEquals(versionOne.bytes.getValue(oldPackage.id),
                    File(oldDirectory, oldPackage.files.single().path).readBytes())
            }
        }
    }

    @Test fun concurrentInstallCallsShareOneSerializedInstallation() = runBlocking {
        val root = temporary.newFolder("app-private")
        val fixture = modelSet("parallel")
        val firstRequest = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requests = AtomicInteger()

        TestHttpsServer { exchange ->
            if (exchange.packageId() == fixture.catalog.packages.first().id &&
                requests.incrementAndGet() == 1) {
                firstRequest.countDown()
                check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
            } else {
                requests.incrementAndGet()
            }
            sendFull(exchange, fixture.bytes.getValue(exchange.packageId()))
        }.use { https ->
            val store = store(root, fixture, https::openConnection)
            val first = async(Dispatchers.IO) { store.install(fixture.ids) }
            assertTrue(withContextIo { firstRequest.await(3, TimeUnit.SECONDS) })
            try {
                val second = async(Dispatchers.IO) { store.install(fixture.ids) }
                delay(150)
                assertEquals("the second caller must wait on the store mutex", 1, requests.get())
                releaseFirstRequest.countDown()
                first.await()
                second.await()
            } finally {
                releaseFirstRequest.countDown()
            }

            assertEquals("each catalog package should be downloaded once", 3, requests.get())
            assertInstalled(fixture, root)
            store.close()
        }
    }

    @Test fun cancellationAfterSelectedDigestCannotPublishMigrationPointer() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("cancel", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val digestStarted = CountDownLatch(1)
        val releaseDigest = CountDownLatch(1)
        val selected = store(root, fixture, { error("no download") })
        selected.onDigestRead = { _, _ ->
            digestStarted.countDown()
            check(releaseDigest.await(5, TimeUnit.SECONDS))
        }
        val operation = async(Dispatchers.IO) { selected.install(fixture.ids) }
        try {
            assertTrue(withContextIo { digestStarted.await(3, TimeUnit.SECONDS) })
            operation.cancel()
        } finally {
            releaseDigest.countDown()
        }
        assertTrue(runCatching { operation.await() }.isFailure)
        assertTrue(!newPointer(root, fixture.catalog.packages.single()).exists())
        selected.close()
        val replay = store(root, fixture, { error("no download") })
        replay.install(fixture.ids)
        assertInstalled(fixture, root)
        replay.close()
    }

    @Test fun legacyAndSchema2SymlinkPointersCannotMakePackageReady() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("pointer-link", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val pkg = fixture.catalog.packages.single()
        val target = temporary.newFile().apply { writeText(installedDirectory(root, pkg).name) }
        val legacy = File(root, "speech-models/active-VAD.txt")
        assertTrue(legacy.delete())
        Files.createSymbolicLink(legacy.toPath(), target.toPath())
        val selected = store(root, fixture, { error("no download") })
        assertTrue(!selected.availability(fixture.ids))
        assertTrue(!newPointer(root, pkg).exists())
        Files.delete(legacy.toPath())
        legacy.writeText(installedDirectory(root, pkg).name)
        Files.createSymbolicLink(newPointer(root, pkg).toPath(), target.toPath())
        assertTrue(!selected.availability(fixture.ids))
        assertTrue(runCatching { selected.modelFile(pkg.id, "weights.bin") }.isFailure)
        assertEquals(installedDirectory(root, pkg).name, target.readText())
        selected.close()
    }

    @Test fun requiredFileSymlinkAndPointerTemporarySymlinkNeverModifyOutsideBytes() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("file-link", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val pkg = fixture.catalog.packages.single()
        val contents = fixture.bytes.getValue(pkg.id)
        val outside = temporary.newFile().apply { writeBytes(contents) }
        val weights = File(installedDirectory(root, pkg), "weights.bin")
        assertTrue(weights.delete())
        Files.createSymbolicLink(weights.toPath(), outside.toPath())
        val selected = store(root, fixture, { error("no download") })
        assertTrue(!selected.availability(fixture.ids))
        assertTrue(runCatching { selected.install(fixture.ids) }.isFailure)
        assertArrayEquals(contents, outside.readBytes())
        Files.delete(weights.toPath())
        weights.writeBytes(contents)
        val temp = File(root, "speech-models/${newPointer(root, pkg).name}.tmp")
        Files.createSymbolicLink(temp.toPath(), outside.toPath())
        assertTrue(runCatching { selected.availability(fixture.ids) }.isFailure)
        assertTrue(!newPointer(root, pkg).exists())
        assertArrayEquals(contents, outside.readBytes())
        assertTrue(Files.isSymbolicLink(temp.toPath()))
        selected.close()
    }

    @Test fun unpointedValidCurrentDirectoryPublishesWithoutDownloadingOrCopying() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("unpointed", packageIds = listOf("arbitrary-secure-package"))
        val pkg = fixture.catalog.packages.single()
        val directory = installedDirectory(root, pkg)
        assertTrue(directory.mkdirs())
        val weights = File(directory, "weights.bin").apply { writeBytes(fixture.bytes.getValue(pkg.id)) }
        val modified = weights.lastModified()
        val selected = store(root, fixture, { error("valid bytes must never redownload") })
        selected.install(fixture.ids)
        assertEquals(modified, weights.lastModified())
        assertEquals(weights.absoluteFile, selected.modelFile(pkg.id, "weights.bin"))
        assertTrue(!File(root, "speech-models/staging").exists())
        assertInstalled(fixture, root)
        selected.close()
    }

    @Test fun constructionAndEmptySelectionsDoNoSpeechFilesystemOrDownloadWork() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("idle", packageIds = legacyPointers.keys.toList())
        seedLegacy(root, fixture)
        val metadataBefore = snapshot(root)
        var downloads = 0
        val digests = mutableListOf<String>()
        val idle = SpeechModelStore(
            context(root), fixture.catalog,
            SpeechModelDownloader { downloads++; error("unexpected download") },
            SpeechArchiveExtractor(), { id, _ -> digests.add(id) }, { Long.MAX_VALUE },
            { _, _ -> error("construction and empty selection must not publish pointers") },
        )
        assertTrue(idle.packages.value.all { it.state == SpeechModelInstallState.MISSING })
        assertEquals(fixture.catalog, idle.catalog)
        assertTrue(idle.availability(emptySet()))
        idle.install(emptySet())
        assertEquals(emptyList<String>(), digests)
        assertEquals(0, downloads)
        assertEquals(metadataBefore, snapshot(root))
        idle.close()

        val emptyRoot = temporary.newFolder()
        val empty = store(emptyRoot, fixture, { error("unexpected download") })
        empty.install(emptySet())
        assertTrue(!File(emptyRoot, "speech-models").exists())
        empty.close()
    }

    @Test fun selectedMigrationReusesOriginalBytesAndLaterSelectionIsIndependent() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("legacy", packageIds = legacyPointers.keys.toList())
        seedLegacy(root, fixture)
        val original = fixture.catalog.packages.associate { pkg ->
            pkg.id to File(installedDirectory(root, pkg), "weights.bin").lastModified()
        }
        val digests = mutableListOf<String>()
        val selected = store(root, fixture, { error("migration must never download") })
        selected.onDigestRead = { id, _ -> digests.add(id) }
        val first = fixture.catalog.packages.first()
        selected.install(setOf(first.id))
        assertEquals(listOf(first.id), digests)
        assertEquals(installedDirectory(root, first).absoluteFile,
            selected.modelFile(first.id, "weights.bin").parentFile)
        assertEquals(1, selected.packages.value.count { it.state == SpeechModelInstallState.READY })
        fixture.catalog.packages.drop(1).forEach { assertTrue(!newPointer(root, it).exists()) }
        assertTrue(!File(root, "speech-models/layout-version.txt").exists())
        assertTrue(!File(root, "speech-models/staging").exists())
        selected.close()

        val late = fixture.catalog.packages.last()
        val restarted = store(root, fixture, { error("late migration must never download") })
        digests.clear()
        restarted.onDigestRead = { id, _ -> digests.add(id) }
        assertTrue(restarted.availability(setOf(late.id)))
        assertEquals(listOf(late.id), digests)
        assertEquals(installedDirectory(root, late).name, newPointer(root, late).readText())
        fixture.catalog.packages.forEach { pkg ->
            val file = File(installedDirectory(root, pkg), "weights.bin")
            assertArrayEquals(fixture.bytes.getValue(pkg.id), file.readBytes())
            assertEquals(original.getValue(pkg.id), file.lastModified())
            assertEquals(installedDirectory(root, pkg).name,
                File(root, "speech-models/active-${legacyPointers.getValue(pkg.id)}.txt").readText())
        }
        restarted.close()
    }

    @Test fun interruptedSelectedMigrationReplaysOnlyUnpublishedPackage() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("crash", packageIds = legacyPointers.keys.take(2))
        seedLegacy(root, fixture)
        val first = fixture.catalog.packages.first()
        val second = fixture.catalog.packages.last()
        val publicationAttempts = mutableListOf<String>()
        val interrupted = SpeechModelStore(context(root), fixture.catalog,
            SpeechModelDownloader { error("no download") }, SpeechArchiveExtractor(), { Long.MAX_VALUE },
            { source, destination ->
                publicationAttempts.add(destination.name)
                if (destination.name == newPointer(root, second).name) throw IOException("crash before rename")
                Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            })
        assertTrue(runCatching { interrupted.install(fixture.ids) }.isFailure)
        assertTrue(newPointer(root, first).isFile)
        assertTrue(!newPointer(root, second).exists())
        assertTrue(File(root, "speech-models/${newPointer(root, second).name}.tmp").isFile)
        interrupted.close()

        val replayPublications = mutableListOf<String>()
        val replay = SpeechModelStore(context(root), fixture.catalog,
            SpeechModelDownloader { error("no download") }, SpeechArchiveExtractor(), { Long.MAX_VALUE },
            { source, destination ->
                replayPublications.add(destination.name)
                Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            })
        replay.install(fixture.ids)
        assertEquals(listOf(newPointer(root, second).name), replayPublications)
        assertInstalled(fixture, root)
        assertTrue(!File(root, "speech-models/${newPointer(root, second).name}.tmp").exists())
        replay.close()
    }

    @Test fun crashAfterAtomicPointerRenameDoesNotRepublishOnRestart() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("post-rename", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val interrupted = SpeechModelStore(context(root), fixture.catalog,
            SpeechModelDownloader { error("no download") }, SpeechArchiveExtractor(), { Long.MAX_VALUE },
            { source, destination ->
                Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                throw IOException("crash after rename")
            })
        assertTrue(runCatching { interrupted.install(fixture.ids) }.isFailure)
        interrupted.close()
        val replay = SpeechModelStore(context(root), fixture.catalog,
            SpeechModelDownloader { error("no download") }, SpeechArchiveExtractor(), { Long.MAX_VALUE },
            { _, _ -> error("already durable package must not republish") })
        replay.install(fixture.ids)
        assertInstalled(fixture, root)
        replay.close()
    }

    @Test fun corruptNewPointerNeverFallsBackToValidLegacyEvenAfterCacheWarmup() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("authoritative", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val pkg = fixture.catalog.packages.single()
        val selected = store(root, fixture, { error("no download") })
        val digests = mutableListOf<String>()
        selected.onDigestRead = { id, _ -> digests.add(id) }
        assertTrue(selected.availability(fixture.ids))
        digests.clear()
        newPointer(root, pkg).writeText("../outside")
        assertTrue(!selected.availability(fixture.ids))
        assertTrue(runCatching { selected.modelFile(pkg.id, "weights.bin") }.isFailure)
        assertEquals(emptyList<String>(), digests)
        assertEquals(SpeechModelInstallState.MISSING, selected.packages.value.single().state)
        selected.close()
        val restarted = store(root, fixture, { error("no download") })
        assertTrue(!restarted.availability(fixture.ids))
        assertEquals("../outside", newPointer(root, pkg).readText())
        restarted.close()
    }

    @Test fun corruptCurrentDirectoryIsRefusedWithoutRepairDownloadOrOutsideDeletion() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("corrupt", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val pkg = fixture.catalog.packages.single()
        val file = File(installedDirectory(root, pkg), "weights.bin")
        val original = fixture.bytes.getValue(pkg.id)
        val modified = file.lastModified()
        val corrupt = ByteArray(original.size) { 0 }
        file.writeBytes(corrupt)
        assertTrue(file.setLastModified(modified))
        val foreign = File(installedDirectory(root, pkg), "user.txt").apply { writeText("preserve") }
        val outside = temporary.newFile().apply { writeText("outside") }
        val nonactive = File(root, "speech-models/installed/old-version").apply { mkdirs() }
        File(nonactive, "keep").writeText("nonactive")
        val selected = store(root, fixture, { error("corruption must fail before download") })
        assertTrue(!selected.availability(fixture.ids))
        val failure = runCatching { selected.install(fixture.ids) }.exceptionOrNull()
        assertEquals("Existing model directory is corrupt", failure?.message)
        assertTrue(!newPointer(root, pkg).exists())
        assertArrayEquals(corrupt, file.readBytes())
        assertEquals("preserve", foreign.readText())
        assertEquals("outside", outside.readText())
        assertEquals("nonactive", File(nonactive, "keep").readText())
        selected.close()
    }

    @Test fun legacyPointerMustMatchExactBoundedNameAndUnknownPackageNeverUsesLegacy() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("unsafe", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        val pkg = fixture.catalog.packages.single()
        val pointer = File(root, "speech-models/active-VAD.txt")
        val selected = store(root, fixture, { error("no download") })
        for (contents in listOf(installedDirectory(root, pkg).name + "\n", "../outside", "x".repeat(129))) {
            pointer.writeText(contents)
            assertTrue(!selected.availability(fixture.ids))
            assertTrue(!newPointer(root, pkg).exists())
        }
        selected.close()
        val generic = modelSet("unknown", packageIds = listOf("third-party-vad"))
        val custom = generic.catalog.packages.single()
        installedDirectory(root, custom).mkdirs()
        File(installedDirectory(root, custom), "weights.bin").writeBytes(generic.bytes.getValue(custom.id))
        pointer.writeText(installedDirectory(root, custom).name)
        val unknown = store(root, generic, { error("no download") })
        assertTrue(!unknown.availability(generic.ids))
        assertTrue(!newPointer(root, custom).exists())
        unknown.close()
    }

    @Test fun installedFileLookupRejectsUndeclaredTraversalBeforeDigestReads() = runBlocking {
        val root = temporary.newFolder()
        val fixture = modelSet("lookup", packageIds = legacyPointers.keys.take(1))
        seedLegacy(root, fixture)
        var digests = 0
        val selected = store(root, fixture, { error("no download") })
        selected.onDigestRead = { _, _ -> digests++ }
        assertTrue(runCatching { selected.modelFile(fixture.ids.single(), "../outside") }.isFailure)
        assertTrue(runCatching { selected.availability(setOf("missing-id")) }.isFailure)
        assertEquals(0, digests)
        selected.close()
    }

    private val legacyPointers = linkedMapOf(
        "vad-silero-6.2.2" to "VAD",
        "ru-gigaam-v1-ctc-offline-2024-10-24" to "RUSSIAN_ASR",
        "en-whisper-tiny.en-offline" to "ENGLISH_ASR",
        "language-whisper-tiny-encoder" to "LANGUAGE_ENCODER",
        "language-whisper-tiny-decoder" to "LANGUAGE_DECODER",
        "ru-piper-irina-medium" to "RUSSIAN_TTS",
        "en-piper-lessac-medium" to "ENGLISH_TTS",
    )

    private fun newPointer(root: File, pkg: SpeechModelPackage) =
        File(root, "speech-models/active-package-${pkg.id}.txt")

    private fun seedLegacy(root: File, fixture: ModelSet) {
        fixture.catalog.packages.forEach { pkg ->
            val directory = installedDirectory(root, pkg)
            require(directory.mkdirs())
            File(directory, "weights.bin").writeBytes(fixture.bytes.getValue(pkg.id))
            File(root, "speech-models/active-${legacyPointers.getValue(pkg.id)}.txt").writeText(directory.name)
        }
    }

    private fun snapshot(root: File): Map<String, Pair<Long, Long>> = root.walkTopDown()
        .associate { it.relativeTo(root).invariantSeparatorsPath to (it.length() to it.lastModified()) }

    private fun store(
        root: File,
        models: ModelSet,
        openConnection: (URL) -> java.net.HttpURLConnection,
        availableBytes: (File) -> Long = { Long.MAX_VALUE },
    ) = SpeechModelStore(
        context(root),
        models.catalog,
        SpeechModelDownloader(openConnection),
        SpeechArchiveExtractor(),
        availableBytes,
    ) { source, destination ->
        Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun context(root: File): Context = object : ContextWrapper(null) {
        override fun getNoBackupFilesDir(): File = root
    }

    private fun assertInstalled(models: ModelSet, root: File) {
        models.catalog.packages.forEach { packageInfo ->
            val directory = installedDirectory(root, packageInfo)
            assertArrayEquals(models.bytes.getValue(packageInfo.id),
                File(directory, packageInfo.files.single().path).readBytes())
            assertEquals(
                "${packageInfo.id}-${packageInfo.archiveSha256.take(16)}",
                File(root, "speech-models/active-package-${packageInfo.id}.txt").readText(),
            )
        }
    }

    private fun installedDirectory(root: File, packageInfo: SpeechModelPackage) =
        File(root, "speech-models/installed/${packageInfo.id}-${packageInfo.archiveSha256.take(16)}")

    private fun modelSet(
        version: String,
        conversation: Boolean = false,
        packageIds: List<String>? = null,
    ): ModelSet {
        val ids = packageIds ?: (if (conversation) {
            listOf("vad", "asr-a", "asr-b", "encoder", "decoder", "voice-a", "voice-b")
        } else {
            listOf("vad", "asr-a", "asr-b")
        })
        val bytes = ids.associate { id -> id to "weights-for-$id-$version".toByteArray() }
        val packages = JSONArray()
        for (id in ids) {
            val contents = bytes.getValue(id)
            val hash = sha256(contents)
            packages.put(JSONObject()
                .put("id", id)
                .put("displayName", "Fixture $id")
                .put("source", "https://models.example.test/$id")
                .put("license", "MIT")
                .put("url", "https://models.example.test/$id")
                .put("allowedRedirectHosts", JSONArray().put("models.example.test"))
                .put("format", "raw")
                .put("archiveBytes", contents.size)
                .put("archiveSha256", hash)
                .put("maxUnpackedBytes", contents.size + 1)
                .put("files", JSONArray().put(JSONObject()
                    .put("path", "weights.bin")
                    .put("bytes", contents.size)
                    .put("sha256", hash))))
        }
        val json = JSONObject()
            .put("schemaVersion", 2)
            .put("runtime", "sherpa-onnx-1.13.8")
            .put("packages", packages)
            .put("models", JSONArray())
        val catalog = SpeechModelCatalog.parse(ByteArrayInputStream(json.toString().toByteArray()))
        return ModelSet(catalog, bytes)
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun com.sun.net.httpserver.HttpExchange.packageId() =
        requestURI.path.removePrefix("/")

    private fun sendFull(exchange: com.sun.net.httpserver.HttpExchange, bytes: ByteArray) {
        exchange.responseHeaders.add("ETag", "\"fixture-v1\"")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class RecoveryConnection(
        url: URL,
        private val bytes: ByteArray,
        private val interruptInitialBody: Boolean,
        private val resumedRequests: AtomicInteger,
    ) : HttpURLConnection(url) {
        private val validator = "\"fixture-${url.path.removePrefix("/")}\""

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode(): Int = if (requestProperty("Range") == null) 200 else 206

        override fun getHeaderField(name: String?): String? {
            val range = requestProperty("Range")
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            return when (name) {
                "ETag" -> validator
                "Content-Length" -> (bytes.size - (start ?: 0)).toString()
                "Content-Range" -> start?.let { "bytes $it-${bytes.lastIndex}/${bytes.size}" }
                else -> null
            }
        }

        override fun getInputStream(): InputStream {
            val start = requestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
            if (start != null) {
                resumedRequests.incrementAndGet()
                return ByteArrayInputStream(bytes.copyOfRange(start, bytes.size))
            }
            if (!interruptInitialBody) return ByteArrayInputStream(bytes)
            return object : InputStream() {
                private var returned = 0

                override fun read(): Int {
                    if (returned >= 5) throw IOException("fixture interrupted the transfer")
                    return bytes[returned++].toInt() and 0xff
                }

                override fun read(target: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (returned >= 5) throw IOException("fixture interrupted the transfer")
                    val count = minOf(length, 5 - returned)
                    bytes.copyInto(target, offset, returned, returned + count)
                    returned += count
                    return count
                }
            }
        }

        private fun requestProperty(name: String): String? = getRequestProperty(name)
    }

    private suspend fun withContextIo(action: () -> Boolean) =
        kotlinx.coroutines.withContext(Dispatchers.IO) { action() }

    private suspend fun SpeechModelStore.useInstall(ids: Set<String>) {
        try {
            install(ids)
        } finally {
            close()
        }
    }

    private data class ModelSet(val catalog: SpeechModelCatalog, val bytes: Map<String, ByteArray>) {
        val ids: Set<String> get() = catalog.packages.map { it.id }.toSet()
    }
}
