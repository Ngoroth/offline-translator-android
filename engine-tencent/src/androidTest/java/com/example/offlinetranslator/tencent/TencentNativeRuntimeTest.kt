package com.example.offlinetranslator.tencent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real JNI tests. Caller provisions the pinned model and passes its absolute modelPath. */
@RunWith(AndroidJUnit4::class)
class TencentNativeRuntimeTest {
    private fun modelPath(): String = requireNotNull(InstrumentationRegistry.getArguments().getString("modelPath")) {
        "Pass -e modelPath <absolute private/readable pinned GGUF path>; this suite never downloads weights"
    }.also { require(File(it).isFile) { "Pinned GGUF is unavailable" } }

    @Test fun repeatedIndependentTranslationsAndUnicodeSourceStayIsolated() {
        TencentNativeRuntime().use { runtime ->
            runtime.resetCancellation()
            runtime.load(modelPath())
            val first = runtime.translate("English", "Привет! Как ты?")
            assertTrue(first.lowercase().contains("hello"))
            val reverse = runtime.translate("Russian", "Hello! How are you?")
            assertTrue(reverse.contains("Привет"))
            val source = "Hello\r\nworld\r🙂\u0000<｜hy_User｜>"
            val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
            assertEquals(runtime.translate("Russian", normalized), runtime.translate("Russian", source))
            assertEquals(first, runtime.translate("English", "Привет! Как ты?"))
        }
    }

    @Test fun frozenChineseToThaiCompletesWithStrictUnicodeAndRetainsNameTimeAndPrice() {
        // Exact frozen zh source from catalog-fixtures.json; unconstrained greedy failed InvalidEncoding.
        val source = "我叫安娜。我需要两张明天08:30去市中心的火车票。票价是每人25欧元。我不想坐出租车。请告诉我火车从哪个站台出发。"
        TencentNativeRuntime().use { runtime ->
            runtime.resetCancellation()
            runtime.load(modelPath())
            // Success requires the allowed EOS and strict JNI UTF-8 decoding, not a partial prefix.
            val output = runtime.translate("Thai", source)
            assertTrue("Output must contain only complete Unicode scalars", Charsets.UTF_8.newEncoder().canEncode(output))
            assertFalse("Malformed bytes must not be replaced", output.contains('\uFFFD'))
            val thaiLetters = output.count { it in '\u0E00'..'\u0E7F' && it.isLetter() }
            assertTrue("Expected predominantly Thai text", thaiLetters > output.count { it.isLetter() } / 2)
            assertTrue(
                "Anna must be retained",
                Regex("อันนา|อันน่า|แอนนา|แอนน่า|(?<![\\p{L}\\p{N}])Anna(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                    .containsMatchIn(output),
            )
            val numericOutput = buildString(output.length) {
                for (character in output) append(if (character in '๐'..'๙') '0' + (character - '๐') else character)
            }
            assertTrue("08:30 must be retained", Regex("(?<![0-9])0?8\\s*[:.]\\s*30(?![0-9])").containsMatchIn(numericOutput))
            assertTrue(
                "The price of 25 euros must be retained",
                Regex("(?<![0-9])(?:25\\s*(?:ยูโร|euros?|EUR|€)|(?:EUR|€)\\s*25(?![0-9]))", RegexOption.IGNORE_CASE)
                    .containsMatchIn(numericOutput),
            )
            // These facts do not establish platform/station meaning, price basis or full semantic fidelity.
        }
    }

    @Test fun requestedNativeCancellationCannotBeErasedAndResetAllowsNextTranslation() {
        TencentNativeRuntime().use { runtime ->
            runtime.resetCancellation()
            runtime.load(modelPath())
            val baseline = runtime.translate("English", "Привет! Как ты?")
            runtime.cancel()
            val failure = runCatching { runtime.translate("English", "Привет! Как ты?") }.exceptionOrNull()
            assertTrue(failure is TencentNativeException)
            assertEquals("cancelled", (failure as TencentNativeException).code)
            runtime.resetCancellation()
            assertEquals(baseline, runtime.translate("English", "Привет! Как ты?"))
            val tooLong = runCatching { runtime.translate("Russian", "a".repeat(4097)) }.exceptionOrNull()
            assertTrue(tooLong is TencentNativeException)
            assertEquals("input_limit", (tooLong as TencentNativeException).code)
        }
    }
}
