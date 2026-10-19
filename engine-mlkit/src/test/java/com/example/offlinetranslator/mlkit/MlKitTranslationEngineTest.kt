package com.example.offlinetranslator.mlkit

import com.example.offlinetranslator.core.ConfigurationProblem
import com.example.offlinetranslator.core.InvalidConfigurationException
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.ModelAvailability
import com.example.offlinetranslator.core.PreparationFailure
import com.example.offlinetranslator.core.TranslationCapability
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.OnTokenCanceledListener
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlKitTranslationEngineTest {
    @Test fun unsupportedSelectionNeverCreatesClientsQueriesModelsOrDownloads() {
        val models = ControlledModels()
        val clients = ControlledClients()
        val error = runCatching {
            MlKitTranslationEngine(
                linkedSetOf(ruEn, LanguagePair(en, LanguageId("zz"))),
                coordinator(models), clients::create,
            )
        }.exceptionOrNull()
        assertProblem(ConfigurationProblem.UNSUPPORTED_PAIR, error)
        assertTrue(clients.created.isEmpty())
        assertTrue(models.queries.isEmpty())
        assertTrue(models.downloads.isEmpty())
    }

    @Test fun directPairDownloadsOnePackAndReusesItForBothDirections() = runTest {
        val models = ControlledModels()
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe, enDe.reverse()), coordinator(models), clients::create)
        assertEquals(TranslationCapability.Direct, MlKitTranslationEngine.capability(enDe))
        assertEquals(setOf(de), engine.requiredLanguages)
        val preparing = async { engine.prepare() }
        runCurrent()
        assertEquals(setOf(de), models.downloads.keys)
        models.complete(de)
        assertEquals(ModelAvailability.READY, preparing.await())
        engine.translate(enDe, "Hello.")
        engine.translate(enDe.reverse(), "Guten Tag.")
        assertEquals(ModelAvailability.READY, engine.prepare())
        assertEquals(setOf(de), models.queries.toSet())
        assertEquals(1, models.downloads.getValue(de).size)
        engine.closeAndJoin()
    }

    @Test fun pivotRequiresBothNonEnglishPacksAndKeepsOriginalSdkPairAndInput() = runTest {
        val pair = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        val models = ControlledModels()
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(pair, pair.reverse()), coordinator(models), clients::create)
        assertEquals(TranslationCapability.Pivot(en), MlKitTranslationEngine.capability(pair))
        assertEquals(setOf(pair.source, pair.target), engine.requiredLanguages)
        val preparing = async { engine.prepare() }
        runCurrent()
        assertEquals(engine.requiredLanguages, models.downloads.keys)
        models.complete(pair.source)
        runCurrent()
        assertFalse(preparing.isCompleted)
        models.complete(pair.target)
        assertEquals(ModelAvailability.READY, preparing.await())
        assertEquals(setOf(pair, pair.reverse()), clients.created.keys)
        val source = "Je n'ai pas vingt euros."
        engine.translate(pair, source)
        assertEquals(listOf(LanguageCatalog.find(pair.source)!!.warmUpText, source), clients.created.getValue(pair).inputs)
        assertEquals(1, clients.created.getValue(pair.reverse()).inputs.size)
        assertEquals(setOf(pair.source, pair.target), models.queries.toSet())
        assertTrue(models.downloads.values.all { it.size == 1 })
        engine.closeAndJoin()
    }

    @Test fun sdkSupportedUnknownDirectnessCanPrepareAndTranslateInBothDirections() = runTest {
        val japanese = LanguageId("ja")
        val pair = LanguagePair(en, japanese)
        val models = ControlledModels(setOf(japanese))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(pair, pair.reverse()), coordinator(models, ControlledWifi(false)), clients::create)
        assertEquals(TranslationCapability.Unknown, MlKitTranslationEngine.capability(pair))
        assertEquals(setOf(japanese), engine.requiredLanguages)
        assertEquals(ModelAvailability.READY, engine.prepare())
        engine.translate(pair, "Hello.")
        engine.translate(pair.reverse(), "こんにちは。")
        assertEquals(setOf(pair, pair.reverse()), clients.created.keys)
        assertEquals("Hello.", clients.created.getValue(pair).inputs.last())
        assertEquals("こんにちは。", clients.created.getValue(pair.reverse()).inputs.last())
        assertEquals(2, clients.created.getValue(pair.reverse()).inputs.size)
        assertEquals(setOf(japanese), models.queries.toSet())
        assertTrue(models.downloads.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun selectedPairSnapshotCannotBeChangedByCaller() = runTest {
        val selection = linkedSetOf(ruEn)
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(selection, coordinator(models), clients::create)
        selection.clear()
        selection += enDe

        assertEquals(setOf(ruEn), engine.pairs)
        assertEquals(setOf(ru), engine.requiredLanguages)
        assertEquals(ModelAvailability.READY, engine.prepare())
        assertEquals(setOf(ru), models.queries.toSet())
        assertEquals(setOf(ruEn), clients.created.keys)
        engine.closeAndJoin()
    }

    @Test fun unsupportedUnselectedAndBlankInputHaveNoTranslationOrModelEffects() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(models), clients::create)
        assertProblem(
            ConfigurationProblem.UNSUPPORTED_PAIR,
            runCatching { engine.translate(LanguagePair(en, LanguageId("zz")), "text") }.exceptionOrNull(),
        )
        val unselected = LanguagePair(LanguageCatalog.FRENCH, LanguageCatalog.SPANISH)
        assertTrue(runCatching { engine.translate(unselected, "text") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { engine.translate(ruEn.reverse(), "text") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { engine.translate(ruEn, " ") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(models.queries.isEmpty())
        assertTrue(models.downloads.isEmpty())
        assertTrue(clients.created.values.all { it.inputs.isEmpty() })
        engine.closeAndJoin()
    }

    @Test fun oversizedDirectInputIsRejectedBeforeModelQueriesOrSdkInference() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(models), clients::create)
        try {
            val failure = runCatching { engine.translate(ruEn, "a".repeat(4097)) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(models.queries.isEmpty())
            assertTrue(models.downloads.isEmpty())
            assertTrue(clients.created.getValue(ruEn).inputs.isEmpty())
        } finally {
            engine.closeAndJoin()
        }
    }

    @Test fun directInputAccepts4096Utf16CodeUnitsWithoutRewriting() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(models), clients::create)
        try {
            val ascii = "a".repeat(4096)
            val emoji = "\uD83D\uDE00".repeat(2048)
            assertEquals(4096, emoji.length)
            assertEquals("translated", engine.translate(ruEn, ascii))
            assertEquals("translated", engine.translate(ruEn, emoji))
            val queriesBeforeRejectedEdit = models.queries.toList()
            assertTrue(runCatching { engine.translate(ruEn, emoji + "a") }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(queriesBeforeRejectedEdit, models.queries)
            assertEquals(listOf(ascii, emoji), clients.created.getValue(ruEn).inputs)
            assertTrue(models.downloads.isEmpty())
        } finally {
            engine.closeAndJoin()
        }
    }

    @Test fun translationNeverDownloadsMissingSelectedModel() = runTest {
        val models = ControlledModels()
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), clients::create)

        assertTrue(runCatching { engine.translate(enDe, "text") }.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(de), models.queries)
        assertTrue(models.downloads.isEmpty())
        assertTrue(clients.created.getValue(enDe).inputs.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun installedSelectionPreparesOfflineWithoutRedundantDownloadsOrOtherModels() = runTest {
        val models = ControlledModels(setOf(ru, de))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(models, ControlledWifi(false)), clients::create)

        assertEquals(ModelAvailability.READY, engine.prepare())
        assertEquals(ModelAvailability.READY, engine.prepare())
        assertEquals(setOf(ru), models.queries.toSet())
        assertTrue(models.downloads.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun missingSelectionRequiresValidatedWifiBeforeDownload() = runTest {
        val models = ControlledModels()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models, ControlledWifi(false)), ControlledClients()::create)

        assertTrue(runCatching { engine.prepare() }.exceptionOrNull() is PreparationFailure.WifiRequired)
        assertEquals(listOf(de), models.queries)
        assertTrue(models.downloads.isEmpty())
        engine.closeAndJoin()
    }

    @Test fun providerDownloadFailureIsRecoverableAndRetryStartsAfterActualCompletion() = runTest {
        val models = ControlledModels()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), ControlledClients()::create)
        val first = async { runCatching { engine.prepare() }.exceptionOrNull() }
        runCurrent()
        models.downloads.getValue(de).single().setException(IllegalStateException("provider failed"))
        assertTrue(first.await() is PreparationFailure.ProviderFailure)

        val retry = async { engine.prepare() }
        runCurrent()
        assertEquals(2, models.downloads.getValue(de).size)
        models.complete(de)
        assertEquals(ModelAvailability.READY, retry.await())
        engine.closeAndJoin()
    }

    @Test fun wifiLossStopsWaitButReplacementOwnerJoinsActualPendingDownload() = runTest {
        val models = ControlledModels()
        val wifi = ControlledWifi(true)
        val coordinator = coordinator(models, wifi)
        val firstClients = ControlledClients()
        val firstEngine = MlKitTranslationEngine(setOf(enDe), coordinator, firstClients::create)
        val first = async { runCatching { firstEngine.prepare() }.exceptionOrNull() }
        runCurrent()
        wifi.connected.value = false
        assertTrue(first.await() is PreparationFailure.WifiRequired)
        assertFalse(models.downloads.getValue(de).single().task.isComplete)
        firstEngine.closeAndJoin()
        assertEquals(1, firstClients.created.getValue(enDe).closeCalls)

        wifi.connected.value = true
        val replacement = MlKitTranslationEngine(setOf(enDe.reverse()), coordinator, ControlledClients()::create)
        val retry = async { replacement.prepare() }
        runCurrent()
        assertEquals(1, models.downloads.getValue(de).size)
        models.complete(de)
        assertEquals(ModelAvailability.READY, retry.await())
        replacement.closeAndJoin()
    }

    @Test fun cancelledPrepareWaitDoesNotCancelTaskOrDuplicateDownload() = runTest {
        val models = ControlledModels()
        val coordinator = coordinator(models)
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator, ControlledClients()::create)
        val first = async { engine.prepare() }
        runCurrent()
        first.cancelAndJoin()
        assertFalse(models.downloads.getValue(ru).single().task.isComplete)
        engine.closeAndJoin()

        val replacement = MlKitTranslationEngine(setOf(ruEn.reverse()), coordinator, ControlledClients()::create)
        val retry = async { replacement.prepare() }
        runCurrent()
        assertEquals(1, models.downloads.getValue(ru).size)
        models.complete(ru)
        assertEquals(ModelAvailability.READY, retry.await())
        replacement.closeAndJoin()
    }

    @Test fun tenMinuteTimeoutStopsOnlyWaitAndRetryJoinsPendingTask() = runTest {
        val models = ControlledModels()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), ControlledClients()::create)
        val first = async { runCatching { engine.prepare() }.exceptionOrNull() }
        runCurrent()
        advanceTimeBy(10 * 60 * 1_000L + 1)
        assertTrue(first.await() is PreparationFailure.PreparationTimedOut)
        assertFalse(models.downloads.getValue(de).single().task.isComplete)

        val retry = async { engine.prepare() }
        runCurrent()
        assertEquals(1, models.downloads.getValue(de).size)
        models.complete(de)
        assertEquals(ModelAvailability.READY, retry.await())
        engine.closeAndJoin()
    }

    @Test fun sharedCoordinatorTracksIndependentLanguagesAndSingleFlightsEachResource() = runTest {
        val models = ControlledModels()
        val coordinator = coordinator(models)
        val both = MlKitTranslationEngine(setOf(ruEn, enDe), coordinator, ControlledClients()::create)
        val german = MlKitTranslationEngine(setOf(enDe.reverse()), coordinator, ControlledClients()::create)
        val bothPreparation = async { both.prepare() }
        val germanPreparation = async { german.prepare() }
        runCurrent()
        assertEquals(setOf(ru, de), models.downloads.keys)
        assertTrue(models.downloads.values.all { it.size == 1 })

        models.complete(de)
        assertEquals(ModelAvailability.READY, germanPreparation.await())
        assertFalse(bothPreparation.isCompleted)
        models.complete(ru)
        assertEquals(ModelAvailability.READY, bothPreparation.await())
        assertTrue(models.downloads.values.all { it.size == 1 })
        both.closeAndJoin()
        german.closeAndJoin()
    }

    @Test fun postDownloadReadinessJoinsPendingPresenceCheckInsteadOfStartingAnother() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val coordinator = coordinator(models)
        val preparing = async { coordinator.prepare(setOf(de)) }
        runCurrent()
        models.presence.getValue(de).single().setResult(false)
        runCurrent()
        val checking = async { coordinator.modelAvailability(setOf(de)) }
        try {
            runCurrent()
            assertEquals(listOf(de, de), models.queries)
            models.complete(de)
            runCurrent()
            assertEquals(listOf(de, de), models.queries)
            assertFalse(preparing.isCompleted)
            models.presence.getValue(de).last().setResult(true)
            assertEquals(ModelAvailability.READY, checking.await())
            assertEquals(ModelAvailability.READY, preparing.await())
        } finally {
            preparing.cancelAndJoin()
            checking.cancelAndJoin()
            models.finishPresence()
        }
    }

    @Test fun cancelledPresenceWaiterLeavesActualCheckSharedWithPrepareAndFreshLookups() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val coordinator = coordinator(models)
        val first = async { coordinator.modelAvailability(setOf(de)) }
        runCurrent()
        first.cancelAndJoin()
        val preparing = async { coordinator.prepare(setOf(de)) }
        val joined = async { coordinator.modelAvailability(setOf(de)) }
        try {
            runCurrent()
            assertEquals(listOf(de), models.queries)
            assertFalse(models.presence.getValue(de).single().task.isComplete)
            models.presence.getValue(de).single().setResult(true)
            assertEquals(ModelAvailability.READY, preparing.await())
            assertEquals(ModelAvailability.READY, joined.await())
            assertTrue(models.downloads.isEmpty())

            val missing = async { coordinator.modelAvailability(setOf(de)) }
            try {
                runCurrent()
                assertEquals(listOf(de, de), models.queries)
                models.presence.getValue(de).last().setResult(false)
                assertEquals(ModelAvailability.MISSING, missing.await())
            } finally {
                missing.cancelAndJoin()
            }
            val installed = async { coordinator.modelAvailability(setOf(de)) }
            try {
                runCurrent()
                assertEquals(listOf(de, de, de), models.queries)
                models.presence.getValue(de).last().setResult(true)
                assertEquals(ModelAvailability.READY, installed.await())
            } finally {
                installed.cancelAndJoin()
            }
        } finally {
            preparing.cancelAndJoin()
            joined.cancelAndJoin()
            models.finishPresence()
        }
    }

    @Test fun failedPresenceCheckIsSharedOnlyUntilActualFailure() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val coordinator = coordinator(models)
        val first = async { runCatching { coordinator.modelAvailability(setOf(de)) }.exceptionOrNull() }
        val joined = async { runCatching { coordinator.modelAvailability(setOf(de)) }.exceptionOrNull() }
        try {
            runCurrent()
            assertEquals(listOf(de), models.queries)
            val failure = IllegalStateException("presence failed")
            models.presence.getValue(de).single().setException(failure)
            val firstFailure = first.await()
            val joinedFailure = joined.await()
            assertEquals(failure.javaClass, firstFailure?.javaClass)
            assertEquals(failure.javaClass, joinedFailure?.javaClass)
            val retry = async { coordinator.modelAvailability(setOf(de)) }
            try {
                runCurrent()
                assertEquals(listOf(de, de), models.queries)
                models.presence.getValue(de).last().setResult(true)
                assertEquals(ModelAvailability.READY, retry.await())
            } finally {
                retry.cancelAndJoin()
            }
        } finally {
            first.cancelAndJoin()
            joined.cancelAndJoin()
            models.finishPresence()
        }
    }

    @Test fun providerCancelledPresenceCheckAllowsFreshLookup() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val coordinator = coordinator(models)
        val first = async { coordinator.modelAvailability(setOf(de)) }
        val joined = async { coordinator.modelAvailability(setOf(de)) }
        try {
            runCurrent()
            assertEquals(listOf(de), models.queries)
            models.presenceCancellations.getValue(de).single().cancel()
            first.join()
            joined.join()
            assertTrue(first.isCancelled)
            assertTrue(joined.isCancelled)
            val retry = async { coordinator.modelAvailability(setOf(de)) }
            try {
                runCurrent()
                assertEquals(listOf(de, de), models.queries)
                models.presence.getValue(de).last().setResult(true)
                assertEquals(ModelAvailability.READY, retry.await())
            } finally {
                retry.cancelAndJoin()
            }
        } finally {
            first.cancelAndJoin()
            joined.cancelAndJoin()
            models.finishPresence()
        }
    }

    @Test fun cancelledInferenceWaiterRetainsAdmissionBeforeNextModelReadinessCheck() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(ruEn, ruEn.reverse()), coordinator(models), clients::create)
        val first = async { engine.translate(ruEn, "первый") }
        runCurrent()
        first.cancelAndJoin()
        val next = async { engine.translate(ruEn.reverse(), "next") }
        try {
            runCurrent()
            assertFalse(next.isCompleted)
            assertEquals(listOf(ru), models.queries)
            assertTrue(clients.created.getValue(ruEn.reverse()).inputs.isEmpty())
            clients.created.getValue(ruEn).tasks.single().setResult("first")
            runCurrent()
            assertEquals(listOf(ru, ru), models.queries)
            val nextClient = clients.created.getValue(ruEn.reverse())
            assertEquals(listOf("next"), nextClient.inputs)
            nextClient.tasks.single().setResult("следующий")
            assertEquals("следующий", next.await())
        } finally {
            next.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun cancelledAdmissionWaiterNeverQueriesModelsOrStartsSdkWork() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(models), clients::create)
        val first = async { engine.translate(ruEn, "первый") }
        runCurrent()
        val queued = async { engine.translate(ruEn, "отменённый") }
        try {
            runCurrent()
            queued.cancelAndJoin()
            assertEquals(listOf(ru), models.queries)
            val client = clients.created.getValue(ruEn)
            assertEquals(listOf("первый"), client.inputs)
            client.tasks.single().setResult("first")
            assertEquals("first", first.await())

            val next = async { engine.translate(ruEn, "следующий") }
            try {
                runCurrent()
                assertEquals(listOf("первый", "следующий"), client.inputs)
                client.tasks.last().setResult("next")
                assertEquals("next", next.await())
            } finally {
                next.cancelAndJoin()
            }
        } finally {
            first.cancelAndJoin()
            queued.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun closeRejectsQueuedInferenceButWaitsForActualCancelledInference() = runTest {
        val models = ControlledModels(setOf(ru))
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(ruEn, ruEn.reverse()), coordinator(models), clients::create)
        val active = async { engine.translate(ruEn, "текст") }
        runCurrent()
        active.cancelAndJoin()
        val queued = async { runCatching { engine.translate(ruEn.reverse(), "text") }.exceptionOrNull() }
        val closing = async { engine.closeAndJoin() }
        try {
            runCurrent()
            engine.close()
            assertFalse(closing.isCompleted)
            assertTrue(clients.created.values.all { it.closeCalls == 0 })
            assertTrue(clients.created.getValue(ruEn.reverse()).inputs.isEmpty())
            assertEquals(listOf(ru), models.queries)

            clients.created.getValue(ruEn).tasks.single().setResult("text")
            closing.await()
            assertTrue(queued.await() is IllegalStateException)
            assertTrue(clients.created.getValue(ruEn.reverse()).inputs.isEmpty())
            assertTrue(clients.created.values.all { it.closeCalls == 1 })
            engine.closeAndJoin()
            assertTrue(clients.created.values.all { it.closeCalls == 1 })
        } finally {
            queued.cancelAndJoin()
            clients.finishPending()
            closing.await()
            engine.closeAndJoin()
        }
    }

    @Test fun readinessCancellationAndFailureReleaseAdmissionWithoutDroppingActualPresenceCheck() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), clients::create)
        val cancelled = async { engine.translate(enDe, "cancelled") }
        runCurrent()
        cancelled.cancelAndJoin()
        val failed = async { runCatching { engine.translate(enDe, "failed") }.exceptionOrNull() }
        try {
            runCurrent()
            assertEquals(listOf(de), models.queries)
            assertTrue(clients.created.getValue(enDe).inputs.isEmpty())
            val failure = IllegalStateException("presence failed")
            models.presence.getValue(de).single().setException(failure)
            val actualFailure = failed.await()
            assertEquals(failure.javaClass, actualFailure?.javaClass)

            val retry = async { engine.translate(enDe, "retry") }
            try {
                runCurrent()
                assertEquals(listOf(de, de), models.queries)
                models.presence.getValue(de).last().setResult(true)
                assertEquals("translated", retry.await())
                assertEquals(listOf("retry"), clients.created.getValue(enDe).inputs)
            } finally {
                retry.cancelAndJoin()
            }
        } finally {
            failed.cancelAndJoin()
            models.finishPresence()
            engine.closeAndJoin()
        }
    }

    @Test fun closeDuringPresenceCheckClosesClientWithoutWaitingForProcessCheck() = runTest {
        val models = ControlledModels(pendingPresence = true)
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), clients::create)
        val call = async { runCatching { engine.translate(enDe, "text") }.exceptionOrNull() }
        runCurrent()
        val closing = async { engine.closeAndJoin() }
        try {
            runCurrent()
            assertTrue(closing.isCompleted)
            closing.await()
            val client = clients.created.getValue(enDe)
            assertEquals(1, client.closeCalls)
            assertFalse(models.presence.getValue(de).single().task.isComplete)
            assertTrue(client.inputs.isEmpty())

            models.presence.getValue(de).single().setResult(true)
            assertTrue(call.await() is IllegalStateException)
            assertTrue(client.inputs.isEmpty())
        } finally {
            call.cancelAndJoin()
            models.finishPresence()
            closing.await()
            engine.closeAndJoin()
        }
    }

    @Test fun synchronousInferenceStartupFailureReleasesAdmissionForRetry() = runTest {
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val client = clients.created.getValue(enDe)
        val failure = IllegalStateException("inference startup failed")
        client.startupFailure = failure
        try {
            assertEquals(failure, runCatching { engine.translate(enDe, "first") }.exceptionOrNull())
            val retry = async { engine.translate(enDe, "retry") }
            try {
                runCurrent()
                assertTrue(retry.isCompleted)
                assertEquals("translated", retry.await())
                assertEquals(listOf("first", "retry"), client.inputs)
                assertEquals(1, client.tasks.size)
            } finally {
                retry.cancelAndJoin()
            }
        } finally {
            engine.closeAndJoin()
        }
    }

    @Test fun inlineCompletionReleasesOnlyItsOwnAdmissionBeforePendingInference() = runTest {
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val client = clients.created.getValue(enDe)
        assertEquals("translated", engine.translate(enDe, "inline"))
        client.pending = true
        val active = async { engine.translate(enDe, "pending") }
        val queued = async { engine.translate(enDe, "queued") }
        try {
            runCurrent()
            assertEquals(listOf("inline", "pending"), client.inputs)
            assertFalse(active.isCompleted)
            assertFalse(queued.isCompleted)
            client.tasks.last().setResult("second")
            assertEquals("second", active.await())
            runCurrent()
            assertEquals(listOf("inline", "pending", "queued"), client.inputs)
            assertFalse(queued.isCompleted)
            client.tasks.last().setResult("third")
            assertEquals("third", queued.await())
        } finally {
            active.cancelAndJoin()
            queued.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun failedActualInferenceReleasesAdmissionForAlreadyQueuedCaller() = runTest {
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val first = async { runCatching { engine.translate(enDe, "first") }.exceptionOrNull() }
        val queued = async { engine.translate(enDe, "queued") }
        try {
            runCurrent()
            val client = clients.created.getValue(enDe)
            assertEquals(listOf("first"), client.inputs)
            val failure = IllegalStateException("inference failed")
            client.tasks.single().setException(failure)
            val actualFailure = first.await()
            assertEquals(failure.javaClass, actualFailure?.javaClass)
            runCurrent()
            assertEquals(listOf("first", "queued"), client.inputs)
            client.tasks.last().setResult("next")
            assertEquals("next", queued.await())
        } finally {
            first.cancelAndJoin()
            queued.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun providerCancelledInferenceReleasesAdmissionForAlreadyQueuedCaller() = runTest {
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val first = async { engine.translate(enDe, "first") }
        val queued = async { engine.translate(enDe, "queued") }
        try {
            runCurrent()
            val client = clients.created.getValue(enDe)
            assertEquals(listOf("first"), client.inputs)
            client.cancellations.single().cancel()
            first.join()
            assertTrue(first.isCancelled)
            runCurrent()
            assertEquals(listOf("first", "queued"), client.inputs)
            client.tasks.last().setResult("next")
            assertEquals("next", queued.await())
        } finally {
            first.cancelAndJoin()
            queued.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun cancelledWarmupRetainsAdmissionUntilActualWarmupCompletes() = runTest {
        val models = ControlledModels(setOf(de))
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), clients::create)
        val warming = async { engine.prepare() }
        runCurrent()
        warming.cancelAndJoin()
        val queriesBeforeNextInference = models.queries.toList()
        val next = async { engine.translate(enDe, "after warmup") }
        try {
            runCurrent()
            val client = clients.created.getValue(enDe)
            assertEquals(listOf(LanguageCatalog.find(en)?.warmUpText ?: "1"), client.inputs)
            assertEquals(queriesBeforeNextInference, models.queries)
            assertFalse(next.isCompleted)
            client.tasks.single().setResult("warm")
            runCurrent()
            assertEquals("after warmup", client.inputs.last())
            client.tasks.last().setResult("next")
            assertEquals("next", next.await())
        } finally {
            next.cancelAndJoin()
            clients.finishPending()
            engine.closeAndJoin()
        }
    }

    @Test fun cancelledWarmupStillHoldsActualClientLeaseUntilSdkTaskFinishes() = runTest {
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val warming = async { engine.prepare() }
        runCurrent()
        val client = clients.created.getValue(enDe)
        assertEquals(1, client.tasks.size)
        warming.cancelAndJoin()
        val closing = async { engine.closeAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, client.closeCalls)
        assertFalse(client.tasks.single().task.isComplete)

        client.tasks.single().setResult("Guten Tag.")
        closing.await()
        assertEquals(1, client.closeCalls)
    }

    @Test fun cancellationOfCloseWaitCannotReturnBeforeActualClientClosure() = runTest {
        val clients = ControlledClients(pending = true)
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(ControlledModels(setOf(de))), clients::create)
        val translation = async { engine.translate(enDe, "text") }
        runCurrent()
        translation.cancelAndJoin()
        val closing = async { engine.closeAndJoin() }
        runCurrent()
        closing.cancel()
        runCurrent()
        assertFalse(closing.isCompleted)
        assertEquals(0, clients.created.getValue(enDe).closeCalls)

        clients.created.getValue(enDe).tasks.single().setResult("Text")
        closing.join()
        assertEquals(1, clients.created.getValue(enDe).closeCalls)
    }

    @Test fun sdkCancellationCompletesLeaseEvenWhenAwaitWasAlreadyCancelled() = runTest {
        val cancellation = DirectCancellationToken()
        val task = TaskCompletionSource<String>(cancellation)
        val client = object : TranslationClient {
            var closes = 0
            override fun translate(text: String): PendingOperation<String> = GoogleTaskOperation(task.task)
            override fun close() { closes += 1 }
        }
        val engine = MlKitTranslationEngine(setOf(ruEn), coordinator(ControlledModels(setOf(ru)))) { client }
        val call = async { engine.translate(ruEn, "текст") }
        runCurrent()
        call.cancelAndJoin()
        val closing = async { engine.closeAndJoin() }
        runCurrent()
        assertFalse(closing.isCompleted)
        cancellation.cancel()
        closing.await()
        assertTrue(task.task.isCanceled)
        assertEquals(1, client.closes)
    }

    @Test fun closedOwnerRejectsAllActionsBeforeProviderEffects() = runTest {
        val models = ControlledModels(setOf(de))
        val clients = ControlledClients()
        val engine = MlKitTranslationEngine(setOf(enDe), coordinator(models), clients::create)
        engine.closeAndJoin()
        assertTrue(runCatching { engine.modelAvailability() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { engine.prepare() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { engine.translate(enDe, "text") }.exceptionOrNull() is IllegalStateException)
        assertTrue(models.queries.isEmpty())
        assertTrue(models.downloads.isEmpty())
        assertTrue(clients.created.getValue(enDe).inputs.isEmpty())
    }

    @Test fun failedClientConstructionClosesAlreadyCreatedSelectedClient() {
        val first = ControlledClient()
        var calls = 0
        val failure = IllegalStateException("client creation failed")
        val result = runCatching {
            MlKitTranslationEngine(linkedSetOf(ruEn, ruEn.reverse()), coordinator(ControlledModels())) {
                calls += 1
                if (calls == 1) first else throw failure
            }
        }
        assertEquals(failure, result.exceptionOrNull())
        assertEquals(1, first.closeCalls)
    }

    private fun coordinator(models: ControlledModels, wifi: ControlledWifi = ControlledWifi(true)) =
        MlKitPreparationCoordinator(models, wifi)

    private fun assertProblem(expected: ConfigurationProblem, error: Throwable?) {
        assertTrue(error is InvalidConfigurationException)
        assertEquals(expected, (error as InvalidConfigurationException).problem)
    }
}

private val ru = LanguageCatalog.RUSSIAN
private val en = LanguageCatalog.ENGLISH
private val de = LanguageCatalog.GERMAN
private val ruEn = LanguagePair(ru, en)
private val enDe = LanguagePair(en, de)

private class ControlledModels(
    installed: Set<LanguageId> = emptySet(),
    private val pendingPresence: Boolean = false,
) : ModelGateway {
    private val installed = installed.toMutableSet()
    val queries = mutableListOf<LanguageId>()
    val presence = linkedMapOf<LanguageId, MutableList<TaskCompletionSource<Boolean>>>()
    val presenceCancellations = linkedMapOf<LanguageId, MutableList<DirectCancellationToken>>()
    val downloads = linkedMapOf<LanguageId, MutableList<TaskCompletionSource<Void>>>()

    override fun isModelDownloaded(language: LanguageId): PendingOperation<Boolean> {
        queries += language
        if (!pendingPresence) return GoogleTaskOperation(Tasks.forResult(language in installed))
        val cancellation = DirectCancellationToken()
        val task = TaskCompletionSource<Boolean>(cancellation)
        presence.getOrPut(language) { mutableListOf() } += task
        presenceCancellations.getOrPut(language) { mutableListOf() } += cancellation
        return GoogleTaskOperation(task.task)
    }

    override fun downloadModelWifiOnly(language: LanguageId): PendingOperation<Unit> {
        val task = TaskCompletionSource<Void>()
        downloads.getOrPut(language) { mutableListOf() } += task
        return GoogleVoidTaskOperation(task.task)
    }

    fun complete(language: LanguageId) {
        installed += language
        downloads.getValue(language).last().setResult(null)
    }

    fun finishPresence() {
        presence.values.flatten().filterNot { it.task.isComplete }.forEach { it.setResult(false) }
    }
}

private class ControlledClients(private val pending: Boolean = false) {
    val created = linkedMapOf<LanguagePair, ControlledClient>()
    fun create(pair: LanguagePair): TranslationClient = ControlledClient(pending).also { created[pair] = it }

    fun finishPending() {
        created.values.flatMap { it.tasks }.filterNot { it.task.isComplete }.forEach { it.setResult("translated") }
    }
}

private class ControlledClient(var pending: Boolean = false) : TranslationClient {
    val inputs = mutableListOf<String>()
    val tasks = mutableListOf<TaskCompletionSource<String>>()
    val cancellations = mutableListOf<DirectCancellationToken>()
    var startupFailure: Throwable? = null
    var closeCalls = 0

    override fun translate(text: String): PendingOperation<String> {
        inputs += text
        startupFailure?.let {
            startupFailure = null
            throw it
        }
        val cancellation = DirectCancellationToken()
        val source = TaskCompletionSource<String>(cancellation)
        cancellations += cancellation
        tasks += source
        if (!pending) source.setResult("translated")
        return GoogleTaskOperation(source.task)
    }

    override fun close() { closeCalls += 1 }
}

private class DirectCancellationToken : CancellationToken() {
    private var requested = false
    private val listeners = mutableListOf<OnTokenCanceledListener>()

    override fun isCancellationRequested(): Boolean = requested

    override fun onCanceledRequested(listener: OnTokenCanceledListener): CancellationToken {
        if (requested) listener.onCanceled() else listeners += listener
        return this
    }

    fun cancel() {
        requested = true
        listeners.forEach { it.onCanceled() }
        listeners.clear()
    }
}

private class ControlledWifi(initial: Boolean) : WifiConnectivity {
    val connected = MutableStateFlow(initial)
    override fun isValidatedWifi(): Boolean = connected.value
    override fun validatedWifiChanges() = connected.asStateFlow()
}
