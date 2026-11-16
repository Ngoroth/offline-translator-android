package io.github.ngoroth.offlinetranslator.mozilla

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.core.LanguageId
import io.github.ngoroth.offlinetranslator.core.LanguagePair
import io.github.ngoroth.offlinetranslator.core.ModelAvailability
import io.github.ngoroth.offlinetranslator.core.TranslationFailure
import io.github.ngoroth.offlinetranslator.core.TranslationPreparationPhase
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Public consumer scenarios over explicitly prepared real resources. Never downloads or skips. */
@RunWith(AndroidJUnit4::class)
class MozillaTranslationEngineConsumerTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val russianEnglish = pair("ru", "en")
    private val russianChinese = pair("ru", "zh")

    private fun pair(source: String, target: String) = LanguagePair(LanguageId(source), LanguageId(target))

    private fun owner(pair: LanguagePair): MozillaTranslationEngine = MozillaTranslationEngine(
        setOf(pair, pair.reverse()), MozillaTranslationPreparation(context),
    )

    private suspend fun prepareLocal(engine: MozillaTranslationEngine) {
        assertEquals("Explicit fixture preparation must precede this offline runtime class",
            ModelAvailability.READY, engine.prepareLocal())
    }

    private fun inNative(frames: Array<StackTraceElement>, method: String? = null): Boolean = frames.any {
        it.className == NATIVE_CLASS && it.isNativeMethod &&
            if (method == null) it.methodName == "nativeLoad" || it.methodName == "nativeTranslate"
            else it.methodName == method
    }

    private data class AcceptedNativeWork(
        val worker: Thread,
        val frames: Array<StackTraceElement>,
        val observedNanos: Long,
    )

    private suspend fun accepted(call: Deferred<*>, method: String): AcceptedNativeWork = withTimeout(ACCEPT_MILLIS) {
        while (true) {
            val observation = Thread.getAllStackTraces().entries.firstOrNull {
                it.key.name == "mozilla-inference" && inNative(it.value, method)
            }
            if (observation != null) {
                assertTrue("Caller must still be active when actual JNI acceptance is observed", call.isActive)
                return@withTimeout AcceptedNativeWork(observation.key, observation.value, System.nanoTime())
            }
            if (call.isCompleted) {
                // Surface a genuine preparation/inference failure rather than relabel it as a sampling timeout.
                call.await()
                throw AssertionError("Operation finished before actual $method acceptance was observed")
            }
            delay(1)
        }
        @Suppress("UNREACHABLE_CODE")
        throw AssertionError("Unreachable native acceptance loop")
    }

    private fun assertDrained(worker: Thread) {
        assertFalse("Joined owner must not retain an active JNI load/translate frame", inNative(worker.stackTrace))
    }

    private suspend fun closed(engine: MozillaTranslationEngine) {
        try {
            engine.translate(russianEnglish, "Где гостиница?")
            throw AssertionError("Closed owner admitted another translation")
        } catch (_: IllegalStateException) {
            // This is the public owner-lifetime rejection, not a simulated native failure.
        }
    }

    private suspend fun freshOwnerTranslates() {
        val replacement = owner(russianEnglish)
        try {
            prepareLocal(replacement)
            val result = replacement.translate(russianEnglish, "Где ближайшая гостиница?")
            assertTrue("Fresh owner must produce a complete nonblank result", result.isNotBlank())
            assertTrue(result.length <= 4096)
            assertFalse('\u0000' in result)
        } finally { withContext(NonCancellable) { replacement.closeAndJoin() } }
    }

    private suspend fun CoroutineScope.cancelAndDrain(
        engine: MozillaTranslationEngine,
        call: Deferred<*>,
        method: String,
        published: AtomicBoolean,
        awaitIdleFirst: Boolean = false,
    ) {
        val acceptance = accepted(call, method)
        val worker = acceptance.worker
        val cancelNanos = System.nanoTime()
        call.cancel(CancellationException("Consumer stopped after actual JNI acceptance"))
        var boundaryNanos = 0L
        withTimeout(DRAIN_MILLIS) {
            if (awaitIdleFirst) assertTrue(
                "awaitIdle proof must begin while accepted native work is still active",
                inNative(worker.stackTrace, method),
            )
            if (awaitIdleFirst) engine.awaitIdle() else engine.closeAndJoin()
            boundaryNanos = System.nanoTime()
            // In the awaitIdle scenario no close has been invoked yet. Check this
            // boundary before joining the caller or doing any other draining work:
            // a no-op awaitIdle must fail here while the native frame is active.
            assertDrained(worker)
            call.join()
        }
        assertTrue("Accepted caller must complete cancelled", call.isCancelled)
        assertFalse("Cancelled work must not publish a late successful value", published.get())
        if (awaitIdleFirst) engine.closeAndJoin()
        closed(engine)
        val evidence = File(context.filesDir, "mozilla-native-consumer-evidence")
        assertTrue(evidence.mkdirs() || evidence.isDirectory)
        File(evidence, "$method-${acceptance.observedNanos}.properties").writeText(
            "method=$method\n" +
                "worker=${worker.name}\n" +
                "accepted.nanos=${acceptance.observedNanos}\n" +
                "cancel.nanos=$cancelNanos\n" +
                "wait.boundary=${if (awaitIdleFirst) "awaitIdle" else "closeAndJoin"}\n" +
                "boundary.returned.nanos=$boundaryNanos\n" +
                "close.after.idle=$awaitIdleFirst\n" +
                "drained.nanos=${System.nanoTime()}\n" +
                "caller.cancelled=${call.isCancelled}\n" +
                "late.publication=${published.get()}\n" +
                "stack=${acceptance.frames.joinToString(" | ")}\n",
        )
    }

    @Test fun acceptedLocalInitializationCancellationDrainsBeforeReplacement() = runBlocking {
        coroutineScope {
            val engine = owner(russianEnglish)
            val published = AtomicBoolean(false)
            val preparation = async(Dispatchers.Default) {
                engine.prepareLocal().also {
                    assertEquals(ModelAvailability.READY, it)
                    published.set(true)
                }
            }
            try {
                cancelAndDrain(engine, preparation, "nativeLoad", published)
                // This owner was never READY before cancellation: READY here would
                // be a late preparation publication, not an incidental closed-state value.
                assertFalse(engine.preparation.value.phase == TranslationPreparationPhase.READY)
                freshOwnerTranslates()
            } finally {
                withContext(NonCancellable) {
                    preparation.cancelAndJoin()
                    engine.closeAndJoin()
                }
            }
        }
    }

    @Test fun acceptedTranslationCancellationDrainsBeforeReplacement() = runBlocking {
        cancelledTranslation(awaitIdleFirst = false)
    }

    @Test fun cancelledAcceptedTranslationAwaitIdleDrainsBeforeClose() = runBlocking {
        cancelledTranslation(awaitIdleFirst = true)
    }

    private suspend fun cancelledTranslation(awaitIdleFirst: Boolean) {
        coroutineScope {
            val engine = owner(russianEnglish)
            var translation: Deferred<String>? = null
            try {
                prepareLocal(engine)
                val input = "Пожалуйста, помогите найти ближайшую аптеку. ".repeat(90)
                assertTrue(input.length <= 4096)
                val published = AtomicBoolean(false)
                val call = async(Dispatchers.Default) {
                    engine.translate(russianEnglish, input).also { published.set(true) }
                }
                translation = call
                cancelAndDrain(engine, call, "nativeTranslate", published, awaitIdleFirst)
                freshOwnerTranslates()
            } finally {
                withContext(NonCancellable) {
                    translation?.cancelAndJoin()
                    engine.closeAndJoin()
                }
            }
        }
    }

    private suspend fun boundFailure(engine: MozillaTranslationEngine, pair: LanguagePair, text: String): TranslationFailure {
        try {
            engine.translate(pair, text)
        } catch (failure: TranslationFailure) {
            assertTrue("The real first model leg must reach an actual token/output bound, not another failure",
                failure is TranslationFailure.TokenLimit || failure is TranslationFailure.OutputLimit)
            return failure
        }
        throw AssertionError("Bounded Russian expansion probe unexpectedly completed; no first-hop failure was established")
    }

    @Test fun actualFirstPivotHopBoundFailureNeverEntersSecondNativeLeg() = runBlocking {
        val input = "Ёж. ".repeat(800)
        assertEquals(3200, input.length)
        val direct = owner(russianEnglish)
        val firstFailure = try {
            prepareLocal(direct)
            boundFailure(direct, russianEnglish, input)
        } finally { withContext(NonCancellable) { direct.closeAndJoin() } }

        val pivot = owner(russianChinese)
        val evidence = File(context.filesDir, "mozilla-native-consumer-evidence")
        assertTrue(evidence.mkdirs() || evidence.isDirectory)
        val trace = File(evidence, "first-hop-${System.nanoTime()}.trace")
        try {
            prepareLocal(pivot)
            // Instrumented method tracing, not sampling: every actual JNI boundary
            // entry is recorded. Preparation and the direct probe are outside this interval.
            Debug.startMethodTracing(trace.absolutePath, TRACE_BYTES, 0)
            val pivotFailure = try {
                boundFailure(pivot, russianChinese, input)
            } finally { Debug.stopMethodTracing() }
            assertEquals("The pivot must propagate the exact real first-leg bound type",
                firstFailure.javaClass, pivotFailure.javaClass)
            pivot.awaitIdle()
            val observed = completeNativeTrace(trace)
            assertEquals("Exactly one actual native leg may enter after a failing first hop", 1, observed.entries)
            assertEquals("The observed native leg must complete/unwind before tracing stops", 1, observed.completions)
            assertEquals("Both events must belong to the same Mozilla worker", 1, observed.workers)
            File(trace.parentFile, "${trace.nameWithoutExtension}.properties").writeText(
                "trace.sha256=${sha256(trace)}\n" +
                    "trace.bytes=${trace.length()}\n" +
                    "trace.records=${observed.records}\n" +
                    "native.entries=${observed.entries}\n" +
                    "native.completions=${observed.completions}\n" +
                    "failure=${pivotFailure.javaClass.simpleName}\n",
            )
        } finally { withContext(NonCancellable) { pivot.closeAndJoin() } }
        freshOwnerTranslates()
    }

    private data class NativeTrace(val entries: Int, val completions: Int, val workers: Int, val records: Int)

    /** ART's non-streaming v2/v3 format. Unknown, overflowing or incomplete traces fail closed.
     * Source: android.googlesource.com/platform/art/+/android-15.0.0_r1/runtime/trace.cc
     */
    private fun completeNativeTrace(file: File): NativeTrace {
        assertTrue("Actual method trace must exist", file.isFile && file.length() in 32..MAX_TRACE_FILE_BYTES)
        val bytes = file.readBytes()
        val delimiter = "*end\n".toByteArray(Charsets.US_ASCII)
        val end = (0..minOf(bytes.size - delimiter.size, MAX_TRACE_HEADER_BYTES)).firstOrNull { start ->
            delimiter.indices.all { bytes[start + it] == delimiter[it] }
        } ?: throw AssertionError("Method trace has no bounded complete dictionary")
        val headerSize = end + delimiter.size
        val lines = String(bytes, 0, headerSize, Charsets.UTF_8).lineSequence().toList()
        assertEquals("*version", lines.first())
        val version = lines[1].toInt()
        assertTrue("Only complete ART v2/v3 non-streaming traces are accepted", version == 2 || version == 3)
        assertTrue("Overflowed trace cannot establish absence of a second hop", "data-file-overflow=false" in lines)
        val declared = lines.single { it.startsWith("num-method-calls=") }.substringAfter('=').toInt()
        val threadSection = lines.indexOf("*threads")
        val methodSection = lines.indexOf("*methods")
        assertTrue(threadSection > 1 && methodSection > threadSection)
        val threads = lines.subList(threadSection + 1, methodSection).associate { line ->
            line.substringBefore('\t').toInt() to line.substringAfter('\t')
        }
        val methods = lines.subList(methodSection + 1, lines.indexOf("*end")).map { it.split('\t') }
        val nativeIds = methods.filter {
            it.size >= 4 && it[1] == NATIVE_CLASS && it[2] == "nativeTranslate" && it[3] == "(J[B[B)[B"
        }.map { it[0].removePrefix("0x").toLong(16) }.toSet()
        assertEquals("The actual native translation method must be present in the dictionary", 1, nativeIds.size)
        val binary = ByteBuffer.wrap(bytes, headerSize, bytes.size - headerSize).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(binary.remaining() >= 32)
        assertEquals("ART trace magic", 0x574f4c53, binary.getInt(0))
        assertEquals(version, binary.getShort(4).toInt() and 65535)
        val offset = binary.getShort(6).toInt() and 65535
        val width = if (version == 2) 10 else binary.getShort(16).toInt() and 65535
        assertTrue(offset >= 32 && offset <= binary.limit())
        assertEquals(if (version == 2) 10 else 14, width)
        assertEquals("Truncated event records cannot prove an absent hop", 0, (binary.limit() - offset) % width)
        val records = (binary.limit() - offset) / width
        assertEquals("Every declared ART event must be present", declared, records)
        var entries = 0
        var completions = 0
        val workers = mutableSetOf<Int>()
        for (position in offset until binary.limit() step width) {
            val thread = binary.getShort(position).toInt() and 65535
            val event = binary.getInt(position + 2).toLong() and 0xffffffffL
            if ((event and -4L) !in nativeIds) continue
            assertEquals("Actual JNI entry must be on the provider worker", "mozilla-inference", threads[thread])
            workers += thread
            when ((event and 3).toInt()) {
                0 -> { entries++; assertEquals("No second native leg may start", 1, entries) }
                1, 2 -> { completions++; assertEquals("Completion requires an observed entry", 1, entries) }
                else -> throw AssertionError("Unknown native trace action")
            }
        }
        return NativeTrace(entries, completions, workers.size, records)
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val NATIVE_CLASS = "io.github.ngoroth.offlinetranslator.mozilla.MozillaNativeRuntime"
        private const val ACCEPT_MILLIS = 30_000L
        private const val DRAIN_MILLIS = 180_000L
        private const val TRACE_BYTES = 16 * 1024 * 1024
        private const val MAX_TRACE_HEADER_BYTES = 2 * 1024 * 1024
        private const val MAX_TRACE_FILE_BYTES = 18L * 1024 * 1024
    }
}
