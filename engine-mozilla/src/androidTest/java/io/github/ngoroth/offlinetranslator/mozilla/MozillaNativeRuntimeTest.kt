package io.github.ngoroth.offlinetranslator.mozilla

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real module JNI tests. Required fixtures are prepared explicitly; nothing downloads here. */
@RunWith(AndroidJUnit4::class)
class MozillaNativeRuntimeTest {
    init { System.loadLibrary("mozilla_jni") }

    private val fixtureRoot: File
        get() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val value = InstrumentationRegistry.getArguments().getString("mozillaFixtureRoot")
            return if (value == null) File(instrumentation.targetContext.filesDir, "mozilla-native-fixtures") else File(value)
        }

    private fun config(route: String): String = File(fixtureRoot, "$route.yml").also {
        assertTrue("Explicit native fixture config missing for $route", it.isFile)
        assertTrue("Fixture root must be absolute", it.isAbsolute)
    }.absolutePath

    private fun load(vararg routes: String): MozillaNativeRuntime = MozillaNativeRuntime().also { runtime ->
        try {
            runtime.load(arrayOf(*routes), routes.map(::config).toTypedArray())
        } catch (failure: Throwable) {
            runtime.close()
            throw failure
        }
    }

    private fun expect(code: String, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected native failure $code")
        } catch (failure: MozillaNativeException) {
            assertEquals(code, failure.code)
            assertEquals(code, failure.message)
        }
    }

    private fun valid(text: String) {
        assertFalse(text.isBlank())
        assertTrue(text.length <= 4096)
        assertFalse('\u0000' in text)
        var index = 0
        while (index < text.length) {
            val current = text[index++]
            if (current.isHighSurrogate()) {
                assertTrue(index < text.length && text[index++].isLowSurrogate())
            } else assertFalse(current.isLowSurrogate())
        }
    }

    @Test fun rejectsMalformedUtf8WithoutReplacement() {
        val malformed = listOf(
            byteArrayOf(0), byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0x80.toByte()),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
            byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xf8.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte()),
        )
        for (value in malformed) {
            expect("invalid_encoding") { nativeCheck(0, value) }
            expect("invalid_encoding") { nativeCheck(1, value) }
        }
        expect("invalid_encoding") { nativeCheck(0, " \n\u00a0".toByteArray()) }
    }

    @Test fun countsSupplementaryScalarsAsTwoUtf16Units() {
        nativeCheck(0, "a".repeat(4096).toByteArray())
        nativeCheck(1, "😀".repeat(2048).toByteArray())
        expect("input_limit") { nativeCheck(0, "😀".repeat(2048).plus("a").toByteArray()) }
        expect("output_limit") { nativeCheck(1, "a".repeat(4097).toByteArray()) }
        nativeCheck(0, "A😀Б中".toByteArray())
    }

    @Test fun rejectsAWinningHypothesisWithoutEos() {
        expect("token_limit") { nativeCheck(2, byteArrayOf()) }
    }

    @Test fun mapsActualModelBufferEnomemInChildProcess() {
        val before = nativeHandlers()
        expect("memory") { nativeCheck(3, byteArrayOf()) }
        assertArrayEquals(before, nativeHandlers())
    }

    @Test fun mapsActualTensorBufferEnomemInChildProcess() {
        val before = nativeHandlers()
        expect("memory") { nativeCheck(4, byteArrayOf()) }
        assertArrayEquals(before, nativeHandlers())
    }

    @Test fun upstreamAbortThrowsWithoutReplacingProcessHandlers() {
        val before = nativeHandlers()
        expect("runtime_load") { nativeCheck(5, byteArrayOf()) }
        assertArrayEquals(before, nativeHandlers())
    }

    @Test fun kotlinRejectsUnpairedUtf16NulBlankAndInputLimit() {
        for (text in listOf("\ud800", "\udc00", "x\u0000y", " \n\u00a0")) {
            MozillaNativeRuntime().use { runtime ->
                expect("invalid_encoding") { runtime.translate("en-ru", text) }
                expect("closed") { runtime.translate("en-ru", "Hello") }
            }
        }
        MozillaNativeRuntime().use { runtime ->
            expect("input_limit") { runtime.translate("en-ru", "a".repeat(4097)) }
        }
    }

    @Test fun missingConfigPoisonsOwnerAndCloseIsIdempotent() {
        val before = nativeHandlers()
        val runtime = MozillaNativeRuntime()
        val missing = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "missing-mozilla-${System.nanoTime()}.yml")
        expect("model_missing") { runtime.load(arrayOf("en-ru"), arrayOf(missing.absolutePath)) }
        expect("closed") { runtime.translate("en-ru", "Hello") }
        runtime.close()
        runtime.close()
        assertArrayEquals(before, nativeHandlers())
    }

    @Test fun rejectsMismatchedAndDuplicateRouteConfigArrays() {
        MozillaNativeRuntime().use { runtime ->
            expect("runtime_load") { runtime.load(arrayOf("en-ru"), emptyArray()) }
        }
        MozillaNativeRuntime().use { runtime ->
            expect("runtime_load") { runtime.load(arrayOf("en-ru", "en-ru"), arrayOf("/a", "/b")) }
        }
    }

    @Test fun eagerUpstreamLoadFailurePoisonsAndFreshPreparationSucceeds() {
        val before = nativeHandlers()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val invalid = File.createTempFile("mozilla-invalid-precision-", ".yml", context.cacheDir)
        try {
            invalid.writeText(File(config("en-ru")).readText() + "\nprecision: [invalid-native-contract-type]\n")
            MozillaNativeRuntime().use { failed ->
                expect("runtime_load") { failed.load(arrayOf("en-ru"), arrayOf(invalid.absolutePath)) }
                expect("closed") { failed.translate("en-ru", "Where is the hotel?") }
            }
            assertArrayEquals(before, nativeHandlers())
            load("en-ru", "ru-en").use { fresh ->
                valid(fresh.translate("en-ru", "Where is the hotel? 😀"))
                valid(fresh.translate("ru-en", "Где гостиница?"))
            }
            assertArrayEquals(before, nativeHandlers())
        } finally { invalid.delete() }
    }

    @Test fun retainsBidirectionalDirectModelsAndComposesBothPivotLegs() {
        val before = nativeHandlers()
        load("ru-en", "en-ru", "en-zh", "zh-en").use { runtime ->
            val english = runtime.translate("ru-en", "Мне нужна вода, а не вино.")
            valid(english)
            valid(runtime.translate("en-zh", english))
            val reverse = runtime.translate("zh-en", "请给我水，不要酒。")
            valid(reverse)
            valid(runtime.translate("en-ru", reverse))
            valid(runtime.translate("ru-en", "Где мой поезд?"))
            valid(runtime.translate("en-ru", "Where is my train?"))
        }
        assertArrayEquals(before, nativeHandlers())
    }

    @Test fun tinyTopologiesInitializeWithoutBaseDimensionOverrides() {
        load("hi-en", "en-id").use { runtime ->
            val english = runtime.translate("hi-en", "मुझे पानी चाहिए।")
            valid(english)
            valid(runtime.translate("en-id", english))
        }
    }

    @Test fun closedOwnerCannotLoadOrTranslate() {
        val runtime = MozillaNativeRuntime()
        runtime.close()
        runtime.close()
        expect("closed") { runtime.translate("en-ru", "Hello") }
        expect("closed") { runtime.load(arrayOf("en-ru"), arrayOf("/unused.yml")) }
    }

    private external fun nativeCheck(kind: Int, value: ByteArray)
    private external fun nativeHandlers(): LongArray
}
