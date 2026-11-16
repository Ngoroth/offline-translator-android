package io.github.ngoroth.offlinetranslator.runtime

import android.net.ConnectivityManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.*
import io.github.ngoroth.offlinetranslator.mozilla.MozillaTranslationEngine
import io.github.ngoroth.offlinetranslator.mozilla.MozillaTranslationPreparation
import io.github.ngoroth.offlinetranslator.tencent.TencentArtifactPreparation
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Explicit, finite real-provider probes. No Activity, speech resources, settings writes or fallback. */
class MozillaTranslationQualificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()

    @Before fun requireExplicitOptIn() {
        assumeTrue("Opt in with runMozillaQualification=true and an explicit phase/runId",
            arguments.getString("runMozillaQualification") == "true")
    }

    @Test fun prepareSelectedResources() = runBlocking<Unit>(Dispatchers.Default) {
        require(arguments.getString("allowModelDownload") == "true") { "Preparation requires allowModelDownload=true" }
        val selection = requireNotNull(arguments.getString("prepPhase")) { "Select prepPhase=mozilla|tencent|both" }
        require(selection in setOf("mozilla", "tencent", "both"))
        val pairs = if (selection == "tencent") emptyList() else LanguageCatalog.entries
            .filter { it.id != LanguageCatalog.ENGLISH }.map { LanguagePair(LanguageCatalog.ENGLISH, it.id) }
        require(pairs.isEmpty() || pairs.size == 16)
        execute("prepare", 0, 0, pairs.size + if (selection == "mozilla") 0 else 1) { run ->
            run.event(JSONObject().put("kind", "preparation_selection").put("prepPhase", selection))
            for (pair in pairs) {
                val owner = initialize(run, "mozilla", pair, download = true, slot = "prepare-${pairKey(pair)}")
                release(run, owner, "mozilla", "prepare-${pairKey(pair)}")
            }
            if (selection != "mozilla") {
                val owner = initialize(run, "tencent", russianEnglish, download = true, slot = "prepare-tencent")
                release(run, owner, "tencent", "prepare-tencent")
            }
        }
    }

    @Test fun qualifyPreparedOutputs() = runBlocking<Unit>(Dispatchers.Default) {
        execute("outputs", 144, 40, 17) { run ->
            val fresh = inputs(run, freshCases)
            val regression = inputs(run, regressionCases)
            val warmups = inputs(run, freshWarmups) + inputs(run, regressionWarmups).filter(::inCatalog)
            val excluded = regression.filterNot(::inCatalog)
            require(fresh.size == 96 && fresh.all(::inCatalog) && excluded.size == 8)
            run.event(JSONObject().put("kind", "excluded_out_of_catalog")
                .put("reason", "Arabic is outside the current application catalog; not an executed success")
                .put("ids", JSONArray(excluded.map { it.id })).put("count", excluded.size))
            val rows = fresh + regression.filter(::inCatalog)
            val groups = rows.groupBy { pairKey(it.pair) }
            require(groups.size == 17 && rows.size == 144 && warmups.size == 40)
            for ((key, cases) in groups) {
                val owner = initialize(run, "mozilla", cases.first().pair, slot = key)
                try {
                    for (input in warmups.filter { pairKey(it.pair) == key }) translate(run, owner, "mozilla", input, key)
                    for (input in cases) translate(run, owner, "mozilla", input, key)
                } finally { release(run, owner, "mozilla", key) }
            }
        }
    }

    @Test fun qualifyPreparedTinyPivot() = runBlocking<Unit>(Dispatchers.Default) {
        execute("tiny-pivot", 2, 0, 1) { run ->
            val frozen = inputs(run, freshCases)
            val pair = LanguagePair(LanguageId("hi"), LanguageId("id"))
            val hindi = frozen.single { it.id == "moz1-hi-en-request" }
            val indonesian = frozen.single { it.id == "moz1-id-en-request" }
            require(hindi.pair == LanguagePair(pair.source, LanguageCatalog.ENGLISH) &&
                indonesian.pair == LanguagePair(pair.target, LanguageCatalog.ENGLISH))
            val cohort = "composed-route-probe"
            val cases = listOf(
                hindi.copy(corpus = cohort, id = "moz1-hi-id-request-derived", pair = pair, sourceCaseId = hindi.id),
                indonesian.copy(corpus = cohort, id = "moz1-id-hi-request-derived", pair = pair.reverse(), sourceCaseId = indonesian.id),
            )
            run.event(JSONObject().put("kind", "cohort").put("cohort", cohort)
                .put("expectedMeasured", 2).put("expectedWarmups", 0).put("expectedInitializations", 1)
                .put("derivedFromAsset", "translation/${freshCases.path}")
                .put("derivation", "Only target and probe ID changed; frozen source text is unchanged")
                .put("separateFromFresh96AndRegression48", true))
            val owner = initialize(run, "mozilla", pair, slot = "tiny-pivot-hi-id")
            try {
                for (input in cases) translate(run, owner, "mozilla", input, "tiny-pivot-hi-id")
            } finally { release(run, owner, "mozilla", "tiny-pivot-hi-id") }
        }
    }

    @Test fun comparePreparedWarmProviders() = runBlocking<Unit>(Dispatchers.Default) {
        execute("compare-warm", 192, 24, 12) { run ->
            val cases = inputs(run, regressionCases).filter(::inComparison)
            val warmups = inputs(run, regressionWarmups).filter(::inComparison)
            require(cases.size == 32 && warmups.size == 4)
            for (pass in 1..3) {
                val order = if (pass % 2 == 1) listOf("mozilla", "tencent") else listOf("tencent", "mozilla")
                run.event(JSONObject().put("kind", "pass_order").put("pass", pass).put("providers", JSONArray(order)))
                for (provider in order) {
                    for ((key, pair) in comparisonPairs) {
                        val slot = "pass-$pass-$provider-$key"
                        val owner = initialize(run, provider, pair, slot = slot)
                        try {
                            for (input in warmups.filter { pairKey(it.pair) == pairKey(pair) })
                                translate(run, owner, provider, input, slot, pass)
                            val forward = cases.filter { it.pair == pair }
                            val reverse = cases.filter { it.pair == pair.reverse() }
                            require(forward.size == reverse.size)
                            for (index in forward.indices) {
                                translate(run, owner, provider, forward[index], slot, pass)
                                translate(run, owner, provider, reverse[index], slot, pass)
                            }
                        } finally { release(run, owner, provider, slot) }
                    }
                }
            }
        }
    }

    /** Invoke once per externally restarted process, provider, pair and observation (1..3). */
    @Test fun observeProcessFirstUse() = runBlocking<Unit>(Dispatchers.Default) {
        val provider = requireNotNull(arguments.getString("provider")) { "Select provider=mozilla|tencent" }
        require(provider in setOf("mozilla", "tencent"))
        val key = requireNotNull(arguments.getString("comparisonPair")) { "Select comparisonPair=ru-en|ru-zh" }
        val pair = requireNotNull(comparisonPairs[key]) { "Unsupported comparisonPair" }
        val observation = requireNotNull(arguments.getString("observation")?.toIntOrNull()) { "Select observation=1|2|3" }
        require(observation in 1..3) { "Select observation=1|2|3" }
        require(arguments.getString("freshProcess") == "true") { "External fresh-process proof is required" }
        execute("compare-first", 1, 0, 1) { run ->
            check(run.invocation == 1) { "First-use observations cannot be repeated after another driver phase in this process" }
            run.event(JSONObject().put("kind", "process_first_use_request").put("provider", provider)
                .put("comparisonPair", key).put("observation", observation)
                .put("externalProofRequired", "Force-stop/new PID and no previous provider use; a new owner alone is not process-cold"))
            val input = inputs(run, regressionCases).first { it.pair == pair }
            val owner = initialize(run, provider, pair, slot = "first-$observation-$provider-$key")
            try { translate(run, owner, provider, input, "first-$observation-$provider-$key") }
            finally { release(run, owner, provider, "first-$observation-$provider-$key") }
        }
    }

    private suspend fun execute(
        phase: String, expectedMeasured: Int, expectedWarmups: Int, expectedInitializations: Int,
        action: suspend (Evidence) -> Unit,
    ) {
        require(arguments.getString("phase") == phase) { "Method requires phase=$phase" }
        if (phase != "prepare") require(arguments.getString("allowModelDownload") != "true") { "Offline phases forbid model downloads" }
        val runId = requireNotNull(arguments.getString("runId")) { "A new runId is required" }
        require(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(runId)) { "Unsafe runId" }
        val root = File(context.filesDir, "mozilla-qualification")
        check(root.isDirectory || root.mkdirs()) { "Cannot create evidence root" }
        val directory = File(root, runId)
        check(directory.mkdir()) { "runId already exists or cannot be reserved; evidence is never reused" }
        val run = Evidence(directory, phase, expectedMeasured, expectedWarmups, expectedInitializations)
        var summary: JSONObject
        try {
            run.event(JSONObject().put("kind", "run").put("runId", runId).put("phase", phase)
                .put("pid", Process.myPid()).put("processStartElapsedRealtimeMs", Process.getStartElapsedRealtime())
                .put("driverInvocationInProcess", run.invocation).put("sdkInt", Build.VERSION.SDK_INT)
                .put("deviceFingerprint", Build.FINGERPRINT).put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("targetPackage", context.packageName).put("testPackage", instrumentation.context.packageName)
                .put("exclusiveProcessRequired", true).put("expectedMeasured", expectedMeasured)
                .put("expectedWarmups", expectedWarmups).put("expectedInitializations", expectedInitializations)
                .put("completionEvidence", "Public provider success requires full native EOS-safe output; raw EOS flags are not exposed or fabricated here")
                .put("memoryEvidence", "PSS/RSS/native-heap boundary samples only; maximum of these samples is not a true peak")
                .put("timerBoundary", "Monotonic complete API call; JSON, memory collection and explicit warm-ups are separate")
                .put("tencentPrepareDisclosure", "prepare/prepareLocal includes native load plus hidden translate(target=English,text=1); this warm-up is inside preparation time and is not separately observable"))
            try {
                if (phase != "prepare") assertOffline()
                action(run)
                if (phase != "prepare") assertOffline()
            } catch (failure: Throwable) {
                run.failures++
                run.event(JSONObject().put("kind", "fatal").put("error", error(failure)))
            }
            summary = run.summary()
            run.event(summary)
        } finally { run.close() }
        println("MOZILLA_QUALIFICATION ${directory.name}/raw.jsonl $summary")
        assertTrue("Incomplete or failed qualification; retain every row: $summary", summary.getBoolean("passed"))
    }

    private data class Input(
        val corpus: String, val id: String, val pair: LanguagePair, val text: String, val warmup: Boolean,
        val sourceCaseId: String? = null,
    )
    private data class Pin(val path: String, val rows: Int, val bytes: Int, val sha256: String)
    private data class Owner(val engine: TranslationEngine?, val failure: Throwable?, var explicitCalls: Int = 0)

    private fun inputs(run: Evidence, pin: Pin): List<Input> {
        val bytes = instrumentation.context.assets.open("translation/${pin.path}").use { it.readBytes() }
        require(bytes.size == pin.bytes && digest(bytes) == pin.sha256) { "Frozen source fixture mismatch: ${pin.path}" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        require(text.endsWith('\n') && '\r' !in text)
        val warmup = pin.path.endsWith("warmup.jsonl")
        val rows = text.dropLast(1).split('\n').map { line ->
            val row = JSONObject(line)
            require(row.keys().asSequence().toSet() == if (warmup) setOf("id", "source", "target", "text", "warmup")
                else setOf("id", "source", "target", "text"))
            if (warmup) require(row.getBoolean("warmup"))
            Input(pin.path.substringBefore('/'), row.getString("id"),
                LanguagePair(LanguageId(row.getString("source")), LanguageId(row.getString("target"))), row.getString("text"), warmup)
                .also { require(it.id.isNotBlank()); validateText(it.text) }
        }
        require(rows.size == pin.rows && rows.map { it.id }.distinct().size == rows.size)
        run.event(JSONObject().put("kind", "source_fixture").put("asset", "translation/${pin.path}")
            .put("rows", rows.size).put("bytes", bytes.size).put("sha256", pin.sha256))
        return rows
    }

    private suspend fun initialize(run: Evidence, provider: String, pair: LanguagePair, download: Boolean = false, slot: String): Owner {
        run.initializations++
        val row = JSONObject().put("kind", "initialization").put("provider", provider).put("slot", slot)
            .put("source", pair.source.value).put("target", pair.target.value).put("bidirectional", true)
            .put("downloadAllowed", download).put("memoryBefore", run.memory())
        var engine: TranslationEngine? = null
        var failure: Throwable? = run.releaseFailure
        var constructorNs: Long? = null
        var preparationNs: Long? = null
        val phases = JSONArray()
        if (failure == null) {
            val started = SystemClock.elapsedRealtimeNanos()
            try {
                engine = when (provider) {
                    "mozilla" -> MozillaTranslationEngine(setOf(pair, pair.reverse()), MozillaTranslationPreparation(context))
                    "tencent" -> TencentTranslationEngine(setOf(pair, pair.reverse()), TencentArtifactPreparation(context))
                    else -> error("Unknown provider")
                }
            } catch (error: Throwable) { failure = error }
            constructorNs = SystemClock.elapsedRealtimeNanos() - started
            row.put("memoryAfterConstructor", run.memory())
            if (failure == null) {
                val selected = requireNotNull(engine)
                coroutineScope {
                    val preparing = launch(start = CoroutineStart.UNDISPATCHED) {
                        selected.preparation.collect { state ->
                            phases.put(JSONObject().put("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                                .put("phase", state.phase.name).put("downloadedBytes", state.downloadedBytes ?: JSONObject.NULL)
                                .put("totalBytes", state.totalBytes ?: JSONObject.NULL))
                        }
                    }
                    val prepareStarted = SystemClock.elapsedRealtimeNanos()
                    try {
                        val available = if (download) selected.prepare() else selected.prepareLocal()
                        row.put("availability", available.name).put("finalPreparationPhase", selected.preparation.value.phase.name)
                        if (available != ModelAvailability.READY) throw selected.preparation.value.lastFailure ?: TranslationFailure.ModelMissing()
                        check(selected.preparation.value.phase == TranslationPreparationPhase.READY) { "Provider returned READY without ready preparation state" }
                    } catch (error: Throwable) { failure = error }
                    finally {
                        preparationNs = SystemClock.elapsedRealtimeNanos() - prepareStarted
                        preparing.cancelAndJoin()
                    }
                }
            }
        }
        if (failure != null) run.failures++
        row.put("constructorElapsedNs", constructorNs ?: JSONObject.NULL).put("preparationElapsedNs", preparationNs ?: JSONObject.NULL)
            .put("preparationStates", phases).put("memoryAfterPreparation", run.memory())
            .put("status", if (failure == null) "ready" else "failed").put("error", failure?.let(::error) ?: JSONObject.NULL)
        try { run.event(row) } catch (recording: Throwable) {
            withContext(NonCancellable) {
                try { engine?.closeAndJoin() } catch (closing: Throwable) {
                    if (closing !== recording) recording.addSuppressed(closing)
                }
            }
            throw recording
        }
        return Owner(engine, failure)
    }

    private suspend fun translate(run: Evidence, owner: Owner, provider: String, input: Input, slot: String, pass: Int? = null) {
        if (input.warmup) run.warmupAttempts++ else run.measuredAttempts++
        val row = JSONObject().put("kind", if (input.warmup) "warmup" else "translation")
            .put("provider", provider).put("corpus", input.corpus).put("id", input.id).put("slot", slot)
            .put("cohort", input.corpus).put("sourceCaseId", input.sourceCaseId ?: JSONObject.NULL)
            .put("ownerCallOrdinal", ++owner.explicitCalls)
            .put("pass", pass ?: JSONObject.NULL).put("source", input.pair.source.value).put("target", input.pair.target.value)
            .put("text", input.text).put("memoryBefore", run.memory())
        val capability = when (provider) {
            "mozilla" -> MozillaTranslationEngine.capability(input.pair)
            else -> TencentTranslationEngine.capability(input.pair)
        }
        row.put("route", when (capability) {
            is TranslationCapability.Pivot -> "pivot:${capability.via.value}"
            TranslationCapability.Direct -> "direct"
            else -> "unsupported_or_unknown"
        })
        var failure = owner.failure
        var result: String? = null
        var elapsedNs: Long? = null
        var returned = false
        if (failure == null) {
            val started = SystemClock.elapsedRealtimeNanos()
            try { result = requireNotNull(owner.engine).translate(input.pair, input.text); returned = true }
            catch (error: Throwable) { failure = error }
            elapsedNs = SystemClock.elapsedRealtimeNanos() - started
            if (failure == null) try { validateText(requireNotNull(result)) } catch (error: Throwable) { failure = error }
        }
        if (failure == null) {
            if (input.warmup) run.warmupCompleted++ else run.measuredCompleted++
        } else run.failures++
        row.put("status", if (failure == null) "completed" else "failed").put("elapsedNs", elapsedNs ?: JSONObject.NULL)
            .put("output", result ?: JSONObject.NULL).put("error", failure?.let(::error) ?: JSONObject.NULL)
            .put("publicCompletionReturned", returned).put("outputContractValid", returned && failure == null)
            .put("nativeEosObserved", JSONObject.NULL).put("memoryAfter", run.memory())
        // Preserve exact invalid UTF-16 if a provider ever violates its public contract; never repair it silently.
        if (returned && failure != null && result != null) row.put("outputUtf16CodeUnits", JSONArray(result.map { it.code }))
        run.event(row)
    }

    private suspend fun release(run: Evidence, owner: Owner, provider: String, slot: String) {
        val row = JSONObject().put("kind", "release").put("provider", provider).put("slot", slot).put("memoryBefore", run.memory())
        val started = SystemClock.elapsedRealtimeNanos()
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try { owner.engine?.awaitIdle() } catch (error: Throwable) { failure = error }
            try { owner.engine?.closeAndJoin() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        val elapsedNs = SystemClock.elapsedRealtimeNanos() - started
        if (failure != null) {
            run.failures++
            run.releaseFailure = failure
        }
        run.event(row.put("status", when {
            failure != null -> "failed"
            owner.engine == null -> "not_constructed"
            else -> "closed"
        }).put("elapsedNs", elapsedNs)
            .put("constructed", owner.engine != null).put("error", failure?.let(::error) ?: JSONObject.NULL)
            .put("memoryAfter", run.memory()))
    }

    private inner class Evidence(
        directory: File, val phase: String, val expectedMeasured: Int, val expectedWarmups: Int, val expectedInitializations: Int,
    ) : AutoCloseable {
        val invocation = invocations.incrementAndGet()
        private val file = File(directory, "raw.jsonl").also { check(it.createNewFile()) }
        private val stream = FileOutputStream(file, true)
        private val writer = stream.bufferedWriter(Charsets.UTF_8)
        private var sequence = 0
        var initializations = 0
        var measuredAttempts = 0
        var measuredCompleted = 0
        var warmupAttempts = 0
        var warmupCompleted = 0
        var failures = 0
        var releaseFailure: Throwable? = null

        fun event(row: JSONObject) {
            row.put("sequence", sequence++).put("recordedElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
            writer.append(row.toString()).append('\n')
            writer.flush()
            stream.fd.sync()
        }

        fun memory(): JSONObject = try {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            val rss = File("/proc/self/status").useLines { lines ->
                lines.first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong()
            }
            JSONObject().put("sampleElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos()).put("pssKb", info.totalPss)
                .put("rssKb", rss).put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
        } catch (failure: Throwable) {
            failures++
            JSONObject().put("error", error(failure)).put("sampleElapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
        }

        fun summary(): JSONObject = JSONObject().put("kind", "summary").put("phase", phase)
            .put("expectedMeasured", expectedMeasured).put("measuredAttempts", measuredAttempts).put("measuredCompleted", measuredCompleted)
            .put("missingMeasured", expectedMeasured - measuredAttempts).put("expectedWarmups", expectedWarmups)
            .put("warmupAttempts", warmupAttempts).put("warmupCompleted", warmupCompleted).put("missingWarmups", expectedWarmups - warmupAttempts)
            .put("expectedInitializations", expectedInitializations).put("initializations", initializations).put("failureEvents", failures)
            .put("passed", failures == 0 && measuredAttempts == expectedMeasured && measuredCompleted == expectedMeasured &&
                warmupAttempts == expectedWarmups && warmupCompleted == expectedWarmups && initializations == expectedInitializations)

        override fun close() { writer.close() }
    }

    private fun assertOffline() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        check(connectivity.activeNetwork == null && connectivity.allNetworks.isEmpty()) { "Disconnect every network before offline qualification" }
    }

    private fun validateText(text: String) {
        check(text.isNotBlank() && text.length <= TranslationEngine.MAX_INPUT_CHARACTERS && '\u0000' !in text) { "Invalid complete-output text contract" }
        var index = 0
        while (index < text.length) {
            val character = text[index++]
            if (Character.isHighSurrogate(character)) {
                check(index < text.length && Character.isLowSurrogate(text[index])) { "Unpaired UTF-16 surrogate" }
                index++
            } else check(!Character.isLowSurrogate(character)) { "Unpaired UTF-16 surrogate" }
        }
    }

    private fun pairKey(pair: LanguagePair) = listOf(pair.source.value, pair.target.value).sorted().joinToString("-")
    private fun inCatalog(input: Input) = LanguageCatalog.find(input.pair.source) != null && LanguageCatalog.find(input.pair.target) != null
    private fun inComparison(input: Input) = comparisonPairs.values.any { pairKey(it) == pairKey(input.pair) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun error(failure: Throwable): JSONObject = JSONObject().put("type", failure.javaClass.name)
        .put("message", failure.message ?: JSONObject.NULL)
        .put("causeType", failure.cause?.javaClass?.name ?: JSONObject.NULL)
        .put("causeMessage", failure.cause?.message ?: JSONObject.NULL)
        .put("suppressed", JSONArray(failure.suppressed.map { JSONObject().put("type", it.javaClass.name).put("message", it.message ?: JSONObject.NULL) }))

    companion object {
        private val invocations = AtomicInteger()
        private val russianEnglish = LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.ENGLISH)
        private val comparisonPairs = linkedMapOf("ru-en" to russianEnglish,
            "ru-zh" to LanguagePair(LanguageCatalog.RUSSIAN, LanguageCatalog.CHINESE))
        private val freshCases = Pin("mozilla-v1/cases.jsonl", 96, 11226, "f5d0ffe50ae442d653e24fe7a74fcae70d2d4c6f854a737f045ec8f05dac3760")
        private val freshWarmups = Pin("mozilla-v1/warmup.jsonl", 32, 3498, "92411837c2a46a31ed5b1224316ea089e470b96cfb5ab90bc59ab909842ee124")
        private val regressionCases = Pin("v1/cases.jsonl", 56, 10291, "bb060ef99b129f2ebb791656ad1286c0a0de8232ebdd334f729b21d1ace8a135")
        private val regressionWarmups = Pin("v1/warmup.jsonl", 10, 3693, "5edb88309e80dc382729f093a73f5d9d7467e038ae5b6cba531e6fa25fae3c01")
    }
}
