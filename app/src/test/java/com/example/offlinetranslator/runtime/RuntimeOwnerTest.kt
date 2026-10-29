package com.example.offlinetranslator.runtime

import com.example.offlinetranslator.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeOwnerTest {
    @Test fun liveOutputPatchRetainsActiveOwnerAndDoesNotOpenGeneralSettingsGate() = runTest {
        val observation = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observation))
        val native = NativeResources().apply { ready = true }
        val recognition = SelectedRecognition().apply { readinessState.value = SpeechRecognitionReadiness.READY }
        val capture = SelectedCapture()
        val owner = controller.replace {
            conversationOwner(native, recognition, CoroutineScope(coroutineContext + SupervisorJob()), capture)
        }
        var saved = AppSettings(translationSetupComplete = true, ttsSpeedPercent = 115)
        try {
            owner.pipeline.start()
            runCurrent()
            assertTrue(owner.pipeline.state.value.captureActive)
            assertFalse(controller.saveIfIdle({ true }) { saved = AppSettings() })
            owner.pipeline.disableSpeechOutput()
            assertTrue(controller.saveSpeechOutput(owner, { true }) {
                saved = saved.copy(speechOutputEnabled = false)
                true
            })
            assertTrue(controller.isCurrent(owner))
            assertTrue(owner.pipeline.state.value.sessionActive)
            assertTrue(owner.pipeline.state.value.captureActive)
            assertFalse(saved.speechOutputEnabled)
            assertEquals(115, saved.ttsSpeedPercent)
            assertTrue(saved.translationSetupComplete)
            assertEquals(1, capture.starts)
            val barrier = CompletableDeferred<Unit>()
            val holding = async(start = CoroutineStart.UNDISPATCHED) { controller.use(owner) { barrier.await() } }
            var fresh = true
            val stale = async { controller.saveSpeechOutput(owner, { fresh }) {
                saved = saved.copy(speechOutputEnabled = true)
                true
            } }
            runCurrent()
            fresh = false
            barrier.complete(Unit)
            holding.await()
            assertFalse(stale.await())
            assertFalse(saved.speechOutputEnabled)
            controller.release(owner)
            val successor = controller.replace { TestOwner(2) }
            assertFalse(controller.saveSpeechOutput(owner, { true }) { saved = AppSettings(); true })
            assertFalse(controller.saveSpeechOutput(null, { true }) { saved = AppSettings(); true })
            assertTrue(controller.isCurrent(successor))
            assertFalse(saved.speechOutputEnabled)
        } finally { controller.closeAndJoin(); observation.cancelAndJoin() }
    }

    @Test fun unownedOutputEscapePatchRejectsARevokedRequestWithoutConstructingAnOwner() = runTest {
        val controller = RuntimeOwnerController(this)
        var saved = AppSettings(speechOutputBackend = SpeechOutputBackend.PIPER,
            languagePair = LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH))
        assertFalse(controller.saveSpeechOutput(null, { false }) {
            saved = saved.copy(speechOutputEnabled = false)
            true
        })
        assertTrue(saved.speechOutputEnabled)
        assertTrue(controller.saveSpeechOutput(null, { true }) {
            saved = saved.copy(speechOutputEnabled = false)
            true
        })
        assertFalse(saved.speechOutputEnabled)
        assertEquals(LanguagePair(LanguageCatalog.DUTCH, LanguageCatalog.ENGLISH), saved.languagePair)
        assertEquals(0L, controller.ownershipVersion.value)
        controller.closeAndJoin()
    }

    private class TestOwner(val id: Int) : RuntimeOwner {
        val busyState = MutableStateFlow(false)
        override val activity: StateFlow<Boolean> = busyState.asStateFlow()
        override val isBusy: Boolean get() = busyState.value
        var closed = false
        val closeGate = CompletableDeferred<Unit>()

        override fun interrupt() {
            busyState.value = false
        }

        override suspend fun closeAndJoin(): Result<Unit> {
            closed = true
            closeGate.complete(Unit)
            return Result.success(Unit)
        }
    }

    /** A native lease remains live after interrupt until its actual completion barrier opens. */
    private class LeaseOwner(
        val id: Int,
        private val liveOwners: AtomicInteger,
        peakLiveOwners: AtomicInteger,
        private val leaseFinished: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        private val closeFailure: Throwable? = null,
    ) : RuntimeOwner {
        override val activity: StateFlow<Boolean> = MutableStateFlow(false)
        override val isBusy: Boolean get() = false
        val closeStarted = CompletableDeferred<Unit>()
        val interrupts = AtomicInteger()
        val closeAttempts = AtomicInteger()
        var closed = false
            private set
        private val closeLock = Mutex()

        init {
            val live = liveOwners.incrementAndGet()
            peakLiveOwners.updateAndGet { peak -> maxOf(peak, live) }
        }

        override fun interrupt() {
            interrupts.incrementAndGet()
        }

        override suspend fun closeAndJoin(): Result<Unit> = closeLock.withLock {
            if (closed) return@withLock Result.success(Unit)
            closeAttempts.incrementAndGet()
            closeStarted.complete(Unit)
            leaseFinished.await()
            closeFailure?.let { return@withLock Result.failure(it) }
            closed = true
            liveOwners.decrementAndGet()
            Result.success(Unit)
        }
    }

    @Test
    fun replaceWaitsForOldOwnerTeardownBeforeInstantiatingNew() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = RuntimeOwnerController(scope)
        val teardownOrder = mutableListOf<String>()

        val owner1 = controller.replace {
            object : RuntimeOwner {
                val busy = MutableStateFlow(false)
                override val isBusy = false
                override val activity = busy
                override fun interrupt() {}
                override suspend fun closeAndJoin(): Result<Unit> {
                    teardownOrder += "close_1"
                    return Result.success(Unit)
                }
            }
        }
        assertNotNull(owner1)

        val owner2 = controller.replace {
            teardownOrder += "build_2"
            object : RuntimeOwner {
                val busy = MutableStateFlow(false)
                override val isBusy = false
                override val activity = busy
                override fun interrupt() {}
                override suspend fun closeAndJoin(): Result<Unit> = Result.success(Unit)
            }
        }
        assertNotNull(owner2)
        assertEquals(listOf("close_1", "build_2"), teardownOrder)

        controller.closeAndJoin()
    }

    @Test
    fun saveIfIdleRejectsWhenOwnerIsBusy() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = RuntimeOwnerController(scope)

        val owner = controller.replace { TestOwner(1) }
        owner.busyState.value = true

        var committed = false
        val saved = controller.saveIfIdle({ true }) { committed = true }
        assertFalse(saved)
        assertFalse(committed)

        owner.busyState.value = false
        val savedIdle = controller.saveIfIdle({ true }) { committed = true }
        assertTrue(savedIdle)
        assertTrue(committed)

        controller.closeAndJoin()
    }

    @Test
    fun saveIfIdleRetainsOwnerOnlyWhenPredicateIsTrue() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = RuntimeOwnerController(scope)
        val owner = controller.replace { TestOwner(1) }

        var committed = false
        val retained = controller.saveIfIdle(retainOwner = { true }) { committed = true }
        assertTrue(retained)
        assertTrue(committed)
        assertFalse("Owner must not be closed when retained", owner.closed)

        val replaced = controller.saveIfIdle(retainOwner = { false }) { committed = true }
        assertTrue(replaced)
        assertTrue("Owner must be closed when retainOwner is false", owner.closed)

        controller.closeAndJoin()
    }

    @Test
    fun useKeepsControllerBusyAndRejectsConcurrentSaveEvenIfPredicateMatches() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val controller = RuntimeOwnerController(scope)
        val owner = controller.replace { TestOwner(1) }
        val inUseBarrier = CompletableDeferred<Unit>()
        val allowFinish = CompletableDeferred<Unit>()

        val useJob = launch {
            controller.use(owner) {
                inUseBarrier.complete(Unit)
                allowFinish.await()
            }
        }

        inUseBarrier.await()
        assertTrue("Controller must report busy during in-flight use", controller.busy.value)
        var committed = false
        val savedWhileBusy = controller.saveIfIdle(retainOwner = { true }) { committed = true }
        assertFalse("saveIfIdle must reject while owner is in use", savedWhileBusy)
        assertFalse(committed)

        allowFinish.complete(Unit)
        useJob.join()
        assertFalse(controller.busy.value)

        val savedAfterFinish = controller.saveIfIdle(retainOwner = { true }) { committed = true }
        assertTrue(savedAfterFinish)
        assertTrue(committed)
        assertFalse(owner.closed)

        controller.closeAndJoin()
    }

    @Test
    fun replacementAndLateReleaseWaitForNativeLeaseAndRejectStaleUse() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val oldLeaseFinished = CompletableDeferred<Unit>()
        var builtNew = false
        var staleActionRan = false

        try {
            val old = controller.replace { LeaseOwner(1, liveOwners, peakLiveOwners, oldLeaseFinished) }
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                controller.replace {
                    builtNew = true
                    LeaseOwner(2, liveOwners, peakLiveOwners)
                }
            }
            old.closeStarted.await()
            // UNDISPATCHED starts each contender up to its suspension on the held controller gate.
            val lateRelease = launch(start = CoroutineStart.UNDISPATCHED) { controller.release(old) }
            val staleUse = async(start = CoroutineStart.UNDISPATCHED) {
                controller.use(old) { staleActionRan = true }
            }

            assertFalse(builtNew)
            assertFalse(old.closed)
            assertFalse(replacement.isCompleted)
            assertFalse(lateRelease.isCompleted)
            assertFalse(staleUse.isCompleted)
            assertEquals(1, liveOwners.get())
            assertTrue(controller.busy.value)

            oldLeaseFinished.complete(Unit)
            val current = replacement.await()
            lateRelease.join()
            assertNull(staleUse.await())
            assertFalse(staleActionRan)
            assertTrue(old.closed)
            assertFalse("Late release of the old lease must not close the new owner", current.closed)
            var currentActionRan = false
            controller.use(current) { currentActionRan = true }
            assertTrue(currentActionRan)
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, liveOwners.get())

            controller.closeAndJoin()
            assertTrue(current.closed)
            assertEquals(0, liveOwners.get())
        } finally {
            oldLeaseFinished.complete(Unit)
            controller.closeAndJoin()
            observationJob.cancelAndJoin()
        }
    }

    @Test
    fun cancelledReplacementStillAwaitsNativeTeardownBeforeQueuedReplacement() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val oldLeaseFinished = CompletableDeferred<Unit>()
        var cancelledBuildRan = false
        var nextBuildRan = false

        try {
            val old = controller.replace { LeaseOwner(1, liveOwners, peakLiveOwners, oldLeaseFinished) }
            val cancelledReplacement = async(start = CoroutineStart.UNDISPATCHED) {
                controller.replace {
                    cancelledBuildRan = true
                    LeaseOwner(2, liveOwners, peakLiveOwners)
                }
            }
            old.closeStarted.await()
            cancelledReplacement.cancel()
            val nextReplacement = async(start = CoroutineStart.UNDISPATCHED) {
                controller.replace {
                    nextBuildRan = true
                    LeaseOwner(3, liveOwners, peakLiveOwners)
                }
            }
            val lateRelease = launch(start = CoroutineStart.UNDISPATCHED) { controller.release(old) }

            assertTrue(cancelledReplacement.isCancelled)
            assertFalse("Cancellation must not abandon a held native lease", cancelledReplacement.isCompleted)
            assertFalse(nextReplacement.isCompleted)
            assertFalse(lateRelease.isCompleted)
            assertFalse(cancelledBuildRan)
            assertFalse(nextBuildRan)
            assertFalse(old.closed)
            assertEquals(1, liveOwners.get())

            oldLeaseFinished.complete(Unit)
            cancelledReplacement.join()
            val current = nextReplacement.await()
            lateRelease.join()
            assertFalse("A cancelled replacement must never construct an owner", cancelledBuildRan)
            assertTrue(nextBuildRan)
            assertTrue(old.closed)
            assertFalse(current.closed)
            var staleActionRan = false
            assertNull(controller.use(old) { staleActionRan = true })
            assertFalse(staleActionRan)
            var currentActionRan = false
            controller.use(current) { currentActionRan = true }
            assertTrue(currentActionRan)
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, liveOwners.get())

            controller.closeAndJoin()
            assertTrue(current.closed)
            assertEquals(0, liveOwners.get())
        } finally {
            oldLeaseFinished.complete(Unit)
            controller.closeAndJoin()
            observationJob.cancelAndJoin()
        }
    }

    @Test
    fun languageCommitAndSuccessorWaitForReleasedNativeLease() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val pendingJobs = mutableListOf<Job>()
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val leaseFinished = CompletableDeferred<Unit>()
        var persistedVersion = 0
        var builds = 0

        try {
            val old = controller.replace { LeaseOwner(1, liveOwners, peakLiveOwners, leaseFinished) }
            val cutover = async(start = CoroutineStart.UNDISPATCHED) {
                controller.release(old)
                if (!controller.saveIfUnowned {
                        assertTrue(old.closed)
                        assertEquals(0, liveOwners.get())
                        persistedVersion = 1
                        true
                    }) return@async null
                controller.createIfUnowned(isFresh = { persistedVersion == 1 }) {
                    builds++
                    LeaseOwner(2, liveOwners, peakLiveOwners)
                }
            }.also { pendingJobs += it }
            old.closeStarted.await()
            assertFalse(cutover.isCompleted)
            assertFalse(old.closed)
            assertEquals(0, persistedVersion)
            assertEquals(0, builds)
            assertEquals(1, liveOwners.get())
            assertTrue(controller.busy.value)

            leaseFinished.complete(Unit)
            val successor = requireNotNull(cutover.await())
            assertEquals(1, persistedVersion)
            assertEquals(1, builds)
            assertEquals(1, old.closeAttempts.get())
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, liveOwners.get())
            assertEquals(2, controller.use(successor) { successor.id })
            assertFalse(controller.busy.value)
        } finally {
            withContext(NonCancellable) {
                leaseFinished.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun pausedLanguageCommitSerializesQueuedMutationAndGuardedCreation() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val pendingJobs = mutableListOf<Job>()
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val commitStarted = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        var persistedVersion = 0
        var unrelatedSetting = "before"
        var mutationRan = false
        var freshnessRan = false
        var builds = 0

        try {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfUnowned {
                    commitStarted.complete(Unit)
                    finishCommit.await()
                    persistedVersion = 1
                    true
                }
            }.also { pendingJobs += it }
            commitStarted.await()
            val queuedMutation = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfUnowned {
                    mutationRan = true
                    assertEquals(1, persistedVersion)
                    unrelatedSetting = "after"
                    persistedVersion = 2
                    true
                }
            }.also { pendingJobs += it }
            val queuedCreation = async(start = CoroutineStart.UNDISPATCHED) {
                controller.createIfUnowned(isFresh = {
                    freshnessRan = true
                    persistedVersion == 2 && unrelatedSetting == "after"
                }) {
                    builds++
                    LeaseOwner(1, liveOwners, peakLiveOwners)
                }
            }.also { pendingJobs += it }
            var ordinaryCommitRan = false
            assertFalse(controller.saveIfIdle(retainOwner = { true }) { ordinaryCommitRan = true })
            assertFalse(ordinaryCommitRan)
            assertTrue(controller.busy.value)
            assertFalse(commit.isCompleted)
            assertFalse(queuedMutation.isCompleted)
            assertFalse(queuedCreation.isCompleted)
            assertFalse(mutationRan)
            assertFalse(freshnessRan)
            assertEquals(0, persistedVersion)
            assertEquals("before", unrelatedSetting)
            assertEquals(0, builds)
            assertEquals(0, liveOwners.get())

            finishCommit.complete(Unit)
            assertTrue(commit.await())
            assertTrue(queuedMutation.await())
            val owner = requireNotNull(queuedCreation.await())
            assertEquals(2, persistedVersion)
            assertEquals("after", unrelatedSetting)
            assertEquals(1, builds)
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, controller.use(owner) { owner.id })
            assertFalse(controller.busy.value)
        } finally {
            withContext(NonCancellable) {
                finishCommit.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun postCommitCompetingOwnerRejectsSuccessorWithoutDisturbingCompetitor() =
        assertCompetingOwnerRejectsGuard(commitSucceeds = true)

    @Test
    fun postFailedCommitCompetingOwnerRejectsIdleRecoveryWithoutDisturbingCompetitor() =
        assertCompetingOwnerRejectsGuard(commitSucceeds = false)

    private fun assertCompetingOwnerRejectsGuard(commitSucceeds: Boolean) = runTest {
        val observationJob = SupervisorJob()
        val pendingJobs = mutableListOf<Job>()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val commitFinished = CompletableDeferred<Unit>()
        val allowCreation = CompletableDeferred<Unit>()
        var persistedVersion = 0
        var freshnessChecks = 0
        var successorBuilds = 0

        try {
            val cutover = async(start = CoroutineStart.UNDISPATCHED) {
                val saved = controller.saveIfUnowned {
                    if (commitSucceeds) persistedVersion = 1
                    commitSucceeds
                }
                assertEquals(commitSucceeds, saved)
                commitFinished.complete(Unit)
                // The handoff gap is outside the controller lock for successor and idle recovery.
                allowCreation.await()
                controller.createIfUnowned(isFresh = {
                    freshnessChecks++
                    persistedVersion == if (commitSucceeds) 1 else 0
                }) {
                    successorBuilds++
                    LeaseOwner(2, liveOwners, peakLiveOwners)
                }
            }.also { pendingJobs += it }
            commitFinished.await()
            val competitor = controller.replace { LeaseOwner(3, liveOwners, peakLiveOwners) }
            var occupiedCommitRan = false
            assertFalse(controller.saveIfUnowned {
                occupiedCommitRan = true
                persistedVersion = 99
                true
            })
            allowCreation.complete(Unit)
            assertNull(cutover.await())
            assertFalse(occupiedCommitRan)
            assertEquals(if (commitSucceeds) 1 else 0, persistedVersion)
            assertEquals(0, freshnessChecks)
            assertEquals(0, successorBuilds)
            assertEquals(0, competitor.interrupts.get())
            assertEquals(0, competitor.closeAttempts.get())
            assertFalse(competitor.closed)
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, liveOwners.get())
            assertEquals(3, controller.use(competitor) { competitor.id })
            assertFalse(controller.busy.value)
        } finally {
            withContext(NonCancellable) {
                allowCreation.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun changedPersistedVersionRejectsStaleCreationBeforeFactoryWithUnownedGate() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val pendingJobs = mutableListOf<Job>()
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val commitFinished = CompletableDeferred<Unit>()
        val allowCreation = CompletableDeferred<Unit>()
        var persistedVersion = 0
        var freshnessChecks = 0
        var staleBuilds = 0

        try {
            val cutover = async(start = CoroutineStart.UNDISPATCHED) {
                assertTrue(controller.saveIfUnowned {
                    persistedVersion = 1
                    true
                })
                commitFinished.complete(Unit)
                allowCreation.await()
                controller.createIfUnowned(isFresh = {
                    freshnessChecks++
                    persistedVersion == 1
                }) {
                    staleBuilds++
                    LeaseOwner(1, liveOwners, peakLiveOwners)
                }
            }.also { pendingJobs += it }
            commitFinished.await()
            assertTrue(controller.saveIfUnowned {
                persistedVersion = 2
                true
            })
            allowCreation.complete(Unit)
            assertNull(cutover.await())
            assertEquals(2, persistedVersion)
            assertEquals(1, freshnessChecks)
            assertEquals(0, staleBuilds)
            assertEquals(0, liveOwners.get())
            assertFalse(controller.busy.value)

            val freshOwner = requireNotNull(controller.createIfUnowned(isFresh = { persistedVersion == 2 }) {
                LeaseOwner(2, liveOwners, peakLiveOwners)
            })
            assertEquals(2, controller.use(freshOwner) { freshOwner.id })
            assertEquals(1, peakLiveOwners.get())
            assertEquals(2, persistedVersion)
        } finally {
            withContext(NonCancellable) {
                allowCreation.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun rejectedAndThrowingCommitsDoNotReportSuccessOrBuildSuccessor() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val failure = IllegalStateException("commit failed")
        var persistedVersion = 0
        var builds = 0
        var successes = 0

        suspend fun cutover(commit: suspend () -> Boolean) {
            if (!controller.saveIfUnowned(commit)) return
            controller.createIfUnowned(isFresh = { persistedVersion == 1 }) {
                builds++
                LeaseOwner(1, liveOwners, peakLiveOwners)
            } ?: return
            successes++
        }

        try {
            cutover { false }
            assertFailureSemantics(failure, runCatching { cutover { throw failure } }.exceptionOrNull())
            assertEquals(0, persistedVersion)
            assertEquals(0, builds)
            assertEquals(0, successes)
            assertEquals(0, liveOwners.get())
            assertFalse(controller.teardownFailed.value)
            assertFalse(controller.busy.value)

            cutover {
                persistedVersion = 1
                true
            }
            assertEquals(1, persistedVersion)
            assertEquals(1, builds)
            assertEquals(1, successes)
            assertEquals(1, peakLiveOwners.get())
            assertEquals(1, liveOwners.get())
        } finally {
            withContext(NonCancellable) {
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun cancelledQueuedSaveAndCreationNeverReachCommitFreshnessOrFactory() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val pendingJobs = mutableListOf<Job>()
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val commitStarted = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        var persistedVersion = 0
        var cancelledCommitRan = false
        var cancelledFreshnessRan = false
        var cancelledBuildRan = false

        try {
            val holdingCommit = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfUnowned {
                    commitStarted.complete(Unit)
                    finishCommit.await()
                    persistedVersion = 1
                    true
                }
            }.also { pendingJobs += it }
            commitStarted.await()
            val cancelledSave = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfUnowned {
                    cancelledCommitRan = true
                    persistedVersion = 99
                    true
                }
            }.also { pendingJobs += it }
            val cancelledCreation = async(start = CoroutineStart.UNDISPATCHED) {
                controller.createIfUnowned(isFresh = {
                    cancelledFreshnessRan = true
                    true
                }) {
                    cancelledBuildRan = true
                    LeaseOwner(1, liveOwners, peakLiveOwners)
                }
            }.also { pendingJobs += it }
            assertFalse(cancelledSave.isCompleted)
            assertFalse(cancelledCreation.isCompleted)
            cancelledSave.cancelAndJoin()
            cancelledCreation.cancelAndJoin()
            assertTrue(cancelledSave.isCancelled)
            assertTrue(cancelledCreation.isCancelled)
            assertFalse(holdingCommit.isCompleted)
            assertEquals(0, persistedVersion)
            assertFalse(cancelledCommitRan)
            assertFalse(cancelledFreshnessRan)
            assertFalse(cancelledBuildRan)

            finishCommit.complete(Unit)
            assertTrue(holdingCommit.await())
            val owner = requireNotNull(controller.createIfUnowned(isFresh = { persistedVersion == 1 }) {
                LeaseOwner(2, liveOwners, peakLiveOwners)
            })
            assertEquals(1, persistedVersion)
            assertFalse(cancelledCommitRan)
            assertFalse(cancelledFreshnessRan)
            assertFalse(cancelledBuildRan)
            assertEquals(2, controller.use(owner) { owner.id })
            assertEquals(1, peakLiveOwners.get())
        } finally {
            withContext(NonCancellable) {
                finishCommit.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                controller.closeAndJoin()
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test
    fun backendCommitWaitsForActualLeaseReleaseBeforeSavingAndOpening() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val leaseFinished = CompletableDeferred<Unit>()
        var backend = "piper"
        try {
            val old = controller.replace { LeaseOwner(1, liveOwners, peakLiveOwners, leaseFinished) }
            val apply = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfIdle(retainOwner = { false }) { backend = "system-offline" }
            }
            old.closeStarted.await()
            assertEquals("piper", backend)
            assertFalse(apply.isCompleted)
            assertEquals(1, liveOwners.get())
            leaseFinished.complete(Unit)
            assertTrue(apply.await())
            assertTrue(old.closed)
            assertEquals(0, liveOwners.get())
            assertEquals("system-offline", backend)
            val successor = controller.createIfUnowned(isFresh = { backend == "system-offline" }) {
                LeaseOwner(2, liveOwners, peakLiveOwners)
            }
            assertNotNull(successor)
            assertEquals(1, peakLiveOwners.get())
        } finally {
            leaseFinished.complete(Unit)
            controller.closeAndJoin()
            observationJob.cancelAndJoin()
        }
    }

    @Test
    fun failedBackendTeardownCannotCommitOrOpenAnotherOwner() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val failure = IllegalStateException("speech shutdown failed")
        var backend = "piper"
        var opened = false
        try {
            controller.replace { LeaseOwner(1, AtomicInteger(), AtomicInteger(), closeFailure = failure) }
            val actual = runCatching {
                controller.saveIfIdle(retainOwner = { false }) { backend = "system-offline" }
            }.exceptionOrNull()
            assertFailureSemantics(failure, actual)
            assertEquals("piper", backend)
            assertTrue(controller.teardownFailed.value)
            assertNull(controller.createIfUnowned(isFresh = { true }) {
                opened = true
                TestOwner(2)
            })
            assertFalse(opened)
        } finally {
            runCatching { controller.closeAndJoin() }
            observationJob.cancelAndJoin()
        }
    }

    @Test
    fun failedBackendSaveAfterReleaseLeavesPreviousSelectionAndSafeIdleRecovery() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val failure = java.io.IOException("settings write failed")
        var backend = "piper"
        try {
            val old = controller.replace { TestOwner(1) }
            val actual = runCatching {
                controller.saveIfIdle(retainOwner = { false }) { throw failure }
            }.exceptionOrNull()
            assertFailureSemantics(failure, actual)
            assertEquals("piper", backend)
            assertTrue(old.closed)
            assertFalse(controller.teardownFailed.value)
            assertFalse(controller.busy.value)
            assertNotNull(controller.createIfUnowned(isFresh = { backend == "piper" }) { TestOwner(2) })
        } finally {
            controller.closeAndJoin()
            observationJob.cancelAndJoin()
        }
    }

    private fun assertFailureSemantics(expected: Throwable, actual: Throwable?) {
        assertEquals(expected.javaClass, actual?.javaClass)
        val rootCause = generateSequence(actual) { it.cause }.lastOrNull()
        assertEquals(expected.javaClass, rootCause?.javaClass)
        assertEquals(expected.message, rootCause?.message)
    }

    @Test
    fun failedNativeCleanupPermanentlyBlocksQueuedAndLaterMutationsWithoutRepeatingCleanup() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val pendingJobs = mutableListOf<Job>()
        val liveOwners = AtomicInteger()
        val peakLiveOwners = AtomicInteger()
        val leaseFinished = CompletableDeferred<Unit>()
        val failure = IllegalStateException("native cleanup failed")
        var persistedVersion = 0
        var commits = 0
        var freshnessChecks = 0
        var builds = 0

        suspend fun commit(): Boolean {
            commits++
            persistedVersion = 1
            return true
        }

        suspend fun build(): LeaseOwner {
            builds++
            return LeaseOwner(2, liveOwners, peakLiveOwners)
        }

        try {
            val old = controller.replace {
                LeaseOwner(1, liveOwners, peakLiveOwners, leaseFinished, closeFailure = failure)
            }
            val release = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { controller.release(old) }.exceptionOrNull()
            }.also { pendingJobs += it }
            old.closeStarted.await()
            val queuedSave = async(start = CoroutineStart.UNDISPATCHED) {
                controller.saveIfUnowned { commit() }
            }.also { pendingJobs += it }
            val queuedCreation = async(start = CoroutineStart.UNDISPATCHED) {
                controller.createIfUnowned(isFresh = {
                    freshnessChecks++
                    true
                }) { build() }
            }.also { pendingJobs += it }
            val queuedReplacement = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { controller.replace { build() } }.exceptionOrNull()
            }.also { pendingJobs += it }
            assertFalse(release.isCompleted)
            assertFalse(queuedSave.isCompleted)
            assertFalse(queuedCreation.isCompleted)
            assertFalse(queuedReplacement.isCompleted)
            assertFalse(controller.teardownFailed.value)
            assertEquals(1, old.closeAttempts.get())
            assertEquals(0, persistedVersion)
            assertEquals(0, builds)

            leaseFinished.complete(Unit)
            assertFailureSemantics(failure, release.await())
            assertFalse(queuedSave.await())
            assertNull(queuedCreation.await())
            assertFailureSemantics(failure, queuedReplacement.await())
            assertTrue(controller.teardownFailed.value)
            assertTrue(controller.busy.value)
            assertFalse(controller.saveIfUnowned { commit() })
            assertFalse(controller.saveIfIdle(retainOwner = { true }) { commit() })
            assertNull(controller.createIfUnowned(isFresh = {
                freshnessChecks++
                true
            }) { build() })
            assertFailureSemantics(failure, runCatching { controller.replace { build() } }.exceptionOrNull())
            assertFailureSemantics(failure, runCatching { controller.release(old) }.exceptionOrNull())
            assertFailureSemantics(failure, runCatching { controller.closeAndJoin() }.exceptionOrNull())
            var staleUseRan = false
            assertNull(controller.use(old) { staleUseRan = true })
            assertFalse(staleUseRan)
            assertEquals(0, persistedVersion)
            assertEquals(0, commits)
            assertEquals(0, freshnessChecks)
            assertEquals(0, builds)
            assertEquals(1, old.closeAttempts.get())
            assertFalse(old.closed)
            assertEquals(1, liveOwners.get())
            assertEquals(1, peakLiveOwners.get())
            assertTrue(controller.teardownFailed.value)
            assertTrue(controller.busy.value)
        } finally {
            withContext(NonCancellable) {
                leaseFinished.complete(Unit)
                pendingJobs.forEach { it.cancelAndJoin() }
                runCatching { controller.closeAndJoin() }
                observationJob.cancelAndJoin()
            }
        }
    }

    @Test fun actualOwnerModelsInstallNativeAndSameRecognitionExactlyOnceWithoutOsModelRows() = runTest {
        val native = NativeResources()
        val recognition = SelectedRecognition()
        val owner = conversationOwner(native, recognition, CoroutineScope(coroutineContext + SupervisorJob()))
        try {
            assertSame(native.models, owner.models.models)
            assertSame(recognition, owner.recognition)
            assertFalse(owner.models.isConversationReady(speechOutputRequired = true))
            assertEquals(0, native.installations)
            assertEquals(0, recognition.installations)
            owner.models.install(speechOutputRequired = true)
            assertEquals(1, native.installations)
            assertEquals(1, recognition.installations)
            assertTrue(owner.models.isConversationReady(speechOutputRequired = true))
            recognition.readinessState.value = SpeechRecognitionReadiness.SCHEDULED
            assertFalse(owner.models.isConversationReady(speechOutputRequired = true))
            recognition.readinessState.value = SpeechRecognitionReadiness.READY
            assertTrue(owner.models.isConversationReady(speechOutputRequired = true))
            assertEquals(1, recognition.installations)
        } finally { owner.closeAndJoin().getOrThrow() }
        assertEquals(1, native.closes)
        assertEquals(1, recognition.closes)
        owner.closeAndJoin().getOrThrow()
        assertEquals(1, recognition.closes)
    }

    @Test fun scheduledUnsupportedAndFailedRecognitionBlockActualOwnerPipelineWithoutDownload() = runTest {
        for (state in listOf(SpeechRecognitionReadiness.SCHEDULED, SpeechRecognitionReadiness.UNSUPPORTED,
            SpeechRecognitionReadiness.DOWNLOAD_FAILED, SpeechRecognitionReadiness.CHECK_FAILED)) {
            val native = NativeResources().apply { ready = true }
            val recognition = SelectedRecognition().apply { readinessState.value = state }
            val capture = SelectedCapture()
            val owner = conversationOwner(native, recognition, CoroutineScope(coroutineContext + SupervisorJob()), capture)
            try {
                owner.pipeline.start()
                runCurrent()
                assertFalse(owner.pipeline.state.value.sessionActive)
                assertEquals(ConversationFailure.MODELS_MISSING, owner.pipeline.state.value.failure)
                assertEquals(0, capture.starts)
                assertEquals(0, recognition.installations)
                assertEquals(0, native.installations)
            } finally { owner.closeAndJoin().getOrThrow() }
        }
    }

    @Test fun actualOwnerPreparationPreservesRecognitionDownloadFailureWithoutStartingCapture() = runTest {
        val native = NativeResources()
        val recognition = SelectedRecognition().apply { installFailure = IllegalStateException("Selected download failed") }
        val capture = SelectedCapture()
        val owner = conversationOwner(native, recognition, CoroutineScope(coroutineContext + SupervisorJob()), capture)
        try {
            val failure = runCatching { owner.models.install(speechOutputRequired = true) }.exceptionOrNull()
            assertSame(recognition.installFailure, failure)
            assertTrue(native.ready)
            assertEquals(SpeechRecognitionReadiness.DOWNLOAD_FAILED, owner.recognition.readinessState.value)
            assertFalse(owner.models.isConversationReady(speechOutputRequired = true))
            assertEquals(1, native.installations)
            assertEquals(1, recognition.installations)
            assertEquals(0, capture.starts)
        } finally { owner.closeAndJoin().getOrThrow() }
    }

    @Test fun replacementAwaitsReadinessOperationAndRecognitionReleaseBeforeNextFactory() = runTest {
        val observationJob = SupervisorJob()
        val controller = RuntimeOwnerController(CoroutineScope(coroutineContext + observationJob))
        val native = NativeResources().apply { ready = true }
        val recognition = SelectedRecognition().apply {
            readinessState.value = SpeechRecognitionReadiness.READY
            checkGate = CompletableDeferred()
            releaseGate = CompletableDeferred()
        }
        val owner = controller.replace {
            conversationOwner(native, recognition, CoroutineScope(coroutineContext + SupervisorJob()))
        }
        try {
            val checking = async(start = CoroutineStart.UNDISPATCHED) {
                controller.use(owner) { owner.models.isConversationReady(speechOutputRequired = true) }
            }
            var built = false
            val replacement = async(start = CoroutineStart.UNDISPATCHED) {
                controller.replace { built = true; TestOwner(2) }
            }
            assertFalse(built)
            recognition.checkGate!!.complete(Unit)
            assertTrue(checking.await() == true)
            recognition.releaseStarted.await()
            assertFalse(built)
            assertEquals(0, native.closes)
            recognition.releaseGate!!.complete(Unit)
            replacement.await()
            assertTrue(built)
            assertEquals(1, recognition.closes)
            assertEquals(1, native.closes)
        } finally {
            recognition.checkGate?.complete(Unit)
            recognition.releaseGate?.complete(Unit)
            controller.closeAndJoin()
            observationJob.cancelAndJoin()
        }
    }

    private class NativeResources : ConversationModelRepository {
        var ready = false
        var installations = 0
        var closes = 0
        override val models = MutableStateFlow<List<SpeechModelStatus>>(emptyList())
        override suspend fun install(speechOutputRequired: Boolean) { installations++; ready = true }
        override suspend fun isConversationReady(speechOutputRequired: Boolean) = ready
        override fun close() { closes++ }
    }

    private class SelectedRecognition : ConversationRecognitionEngine {
        override val readinessState = MutableStateFlow(SpeechRecognitionReadiness.MISSING_LOCAL_MODEL)
        var installations = 0
        var closes = 0
        var checkGate: CompletableDeferred<Unit>? = null
        var releaseGate: CompletableDeferred<Unit>? = null
        var installFailure: Throwable? = null
        val releaseStarted = CompletableDeferred<Unit>()
        override suspend fun readiness(): SpeechRecognitionReadiness { checkGate?.await(); return readinessState.value }
        override suspend fun install() {
            installations++
            installFailure?.let { readinessState.value = SpeechRecognitionReadiness.DOWNLOAD_FAILED; throw it }
            readinessState.value = SpeechRecognitionReadiness.READY
        }
        override suspend fun prepare() { check(readiness() == SpeechRecognitionReadiness.READY) }
        override suspend fun recognize(segment: SpeechSegment): RecognizedUtterance = error("No segment in readiness tests")
        override fun close() = error("Owner must await release")
        override suspend fun closeAndJoin() { releaseStarted.complete(Unit); releaseGate?.await(); closes++ }
    }

    private class SelectedCapture : SpeechCaptureEngine {
        var starts = 0
        override suspend fun start(listener: SpeechCaptureListener) { starts++ }
        override fun interruptCurrent() = Unit
        override suspend fun stop(flush: Boolean): SpeechSegment? = null
        override fun close() = Unit
    }

    private fun conversationOwner(
        native: NativeResources,
        recognition: SelectedRecognition,
        scope: CoroutineScope,
        capture: SelectedCapture = SelectedCapture(),
    ) = ConversationRuntimeOwner(AppSettings().conversationConfig(), native, capture, recognition,
        object : TranslationEngine {
            override val preparation = kotlinx.coroutines.flow.MutableStateFlow(TranslationPreparationState(TranslationPreparationPhase.READY))
            override suspend fun modelAvailability() = ModelAvailability.READY
            override suspend fun prepare() = ModelAvailability.READY
            override suspend fun translate(pair: LanguagePair, text: String): String = error("No phrase in readiness tests")
            override fun close() = Unit
        }, object : ConversationSpeechOutput {
            override suspend fun readiness() = SpeechOutputReadiness.READY
            override suspend fun prepare() = Unit
            override suspend fun speak(language: LanguageId, text: String, onPlaybackStarted: (SpeechPlaybackStart) -> Unit) =
                error("No phrase in readiness tests")
            override fun interruptCurrent() = Unit
            override fun throwIfReleaseFailed() = Unit
            override fun close() = Unit
        }, scope)
}
