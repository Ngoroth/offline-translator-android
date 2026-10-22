package com.example.offlinetranslator.conversation

import com.example.offlinetranslator.core.*
import com.example.offlinetranslator.sherpa.SherpaSpeechSegmenter
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object ConversationProofFixtures {
    val CASE_ORDER = listOf("ru-1", "ru-2", "en-1", "en-2", "ru-3", "en-3", "en-4", "ru-4")
    val EN_CASE_ORDER = listOf("en-1", "en-2", "en-3", "en-4")

    /** Pinned Common Voice sources from the read-only 2026-10-11 production-VAD provenance. */
    data class HumanSource(
        val asset: String, val language: LanguageId, val reference: String,
        val bytes: Long, val sha256: String, val samples: Int,
    )
    val SHORT_HUMAN_SOURCES = listOf(
        HumanSource("short-human-ru-timer.wav", LanguageCatalog.RUSSIAN, "Остановить таймер.",
            96812, "c56d2b49c92ff19fb3779b9644b4e9da21aff5919ef4efed20b1054c78a5aac8", 48384),
        HumanSource("short-human-ru-come.wav", LanguageCatalog.RUSSIAN, "Иди сюда!",
            84524, "b0575a2591783d64d325d47f7127b3daf3d73810f703ac8e793a6dd5743269c4", 42240),
        HumanSource("short-human-en-right.wav", LanguageCatalog.ENGLISH, "Turn to the right.",
            87596, "1f1d013f7217228a03c293749a98de6197efdf16d81ee66ec82f2cf691bc1309", 43776),
        HumanSource("short-human-en-aisle.wav", LanguageCatalog.ENGLISH, "Please go to aisle seven.",
            100652, "418cf7345bddc6aea09007564ac19d703c31c55c4a45a76ffe6ff0972a64c518", 50304),
    )

    fun normalizedText(text: String): String = java.text.Normalizer
        .normalize(text, java.text.Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        .replace(Regex("[\\p{P}\\p{Z}\\s]+"), " ").trim()

    /** Original samples only: no appended silence, no trimming, no synthetic microphone clock. */
    suspend fun productionSegments(
        store: SpeechModelStore, config: ConversationSessionConfig, pcm: ShortArray,
    ): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        SherpaSpeechSegmenter.create(store, config.vadModelId, config.vadMinSilenceMs).use { vad ->
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val count = minOf(2048, pcm.size - offset)
                vad.accept(pcm, offset, count, segments::add)
                offset += count
            }
            vad.flush(segments::add)
            assertEquals(pcm.size.toLong(), vad.acceptedSampleCount)
        }
        assertEquals("Each bounded short source must produce exactly one genuine VAD segment", 1, segments.size)
        assertTrue(segments.single().copyPcm().any { it.toInt() != 0 })
        return segments
    }

    private val SEMANTIC_TOKEN = Regex("\\p{L}[\\p{L}\\p{M}]*|\\p{N}+")
    private val NEGATION = Regex(
        "\\b(?:не|нет|not|never|no|cannot|won['’]t|can['’]t|don['’]t|doesn['’]t|didn['’]t|isn['’]t|aren['’]t)\\b",
        RegexOption.IGNORE_CASE,
    )
    fun hasNegation(text: String): Boolean = NEGATION.containsMatchIn(text)
    private val INTEGER_WORDS = buildMap {
        val english = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
        val russian = listOf(
            "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
        )
        for (value in english.indices) {
            put(english[value], value.toString())
            put(russian[value], value.toString())
        }
        putAll(
            mapOf(
                "одна" to "1", "одну" to "1", "одной" to "1", "две" to "2", "двух" to "2",
                "двум" to "2", "семи" to "7", "пятнадцати" to "15",
            ),
        )
        putAll(
            mapOf(
                "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60",
                "seventy" to "70", "eighty" to "80", "ninety" to "90", "hundred" to "100", "thousand" to "1000",
                "двадцать" to "20", "тридцать" to "30", "сорок" to "40", "пятьдесят" to "50", "шестьдесят" to "60",
                "семьдесят" to "70", "восемьдесят" to "80", "девяносто" to "90", "сто" to "100", "тысяча" to "1000",
            ),
        )
    }

    private val TRANSLATION_MEANING = mapOf(
        "ru-1" to listOf("\\btoday\\b", "\\b(?:seven|7)\\b", "\\bmeeting\\b", "\\b(?:entrance|entry|door)\\b"),
        "ru-2" to listOf("\\b(?:not|won['’]t|can['’]t)\\b", "\\bbus\\b"),
        "ru-3" to listOf(
            "\\b(?:book(?:ed|ing|s)?|order(?:ed|ing|s)?|reserv(?:e|es|ed|ing))\\b",
            "\\b(?:two|2)\\b", "\\btickets?\\b", "\\bvancouver\\b",
        ),
        "ru-4" to listOf("\\b(?:fifteen|15)\\b", "\\bminutes?\\b", "\\b(?:call|phone|ring)\\b"),
        "en-1" to listOf("сегодня", "\\b(?:семь|7)\\b", "встреч", "вход"),
        "en-2" to listOf("\\bне\\b", "автобус"),
        "en-3" to listOf(
            "(?:закаж|заброниру|брониру|зарезервиру)",
            "\\b(?:два|две|2)\\b", "билет", "ванкувер",
        ),
        "en-4" to listOf("\\b(?:пятнадцать|15)\\b", "минут", "(?:перезвон|позвон)"),
    ).mapValues { (_, patterns) -> patterns.map { Regex(it, RegexOption.IGNORE_CASE) } }

    fun readPcm(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size in 44..500_000)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var dataStart = -1
        var dataSize = 0
        var formatValid = false
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            when (String(bytes, offset, 4)) {
                "fmt " -> {
                    require(size >= 16)
                    val format = buffer.getShort(offset + 8).toInt()
                    val channels = buffer.getShort(offset + 10).toInt()
                    val rate = buffer.getInt(offset + 12)
                    val bits = buffer.getShort(offset + 22).toInt()
                    formatValid = format == 1 && channels == 1 && (rate == 16000 || rate == 8000) && bits == 16
                }
                "data" -> {
                    dataStart = offset + 8
                    dataSize = size
                }
            }
            offset += 8 + size + size % 2
        }
        require(formatValid && dataStart >= 0 && dataSize > 0 && dataSize % 2 == 0)
        val rawSamples = ShortArray(dataSize / 2) { buffer.getShort(dataStart + it * 2) }
        if (buffer.getInt(12 + 12) == 8000) {
            // Upsample 8 kHz official fixture to 16 kHz for VAD/models by doubling each sample
            return ShortArray(rawSamples.size * 2) { rawSamples[it / 2] }
        }
        return rawSamples
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun semanticTokens(text: String): List<String> =
        SEMANTIC_TOKEN.findAll(normalizedText(text)).map { match ->
            INTEGER_WORDS[match.value] ?: match.value
        }.toList()

    fun assertSourceMeaning(case: JSONObject, original: String, proof: JSONObject) {
        val actual = semanticTokens(original)
        val reference = semanticTokens(case.getString("reference"))
        assertEquals(
            "Source changed control quantities: $proof",
            reference.filter { it.all(Char::isDigit) },
            actual.filter { it.all(Char::isDigit) },
        )
        assertEquals(
            "Source changed control polarity: $proof",
            NEGATION.findAll(case.getString("reference")).count(),
            NEGATION.findAll(original).count(),
        )
        val phrases = case.getJSONArray("requiredSemanticPhrases")
        for (index in 0 until phrases.length()) {
            val phrase = phrases.getString(index)
            val required = semanticTokens(phrase)
            assertTrue("Empty required source phrase", required.isNotEmpty())
            val matched = actual.size >= required.size && (0..actual.size - required.size).any { start ->
                required.indices.all { offset -> actual[start + offset] == required[offset] }
            }
            assertTrue("Lost required source meaning '$phrase': $proof", matched)
        }
    }

    fun assertTranslationMeaning(case: JSONObject, translation: String) {
        val id = case.getString("id")
        val reference = semanticTokens(case.getString("reference"))
        val actual = semanticTokens(translation)
        assertEquals(
            "Translation changed control quantities for $id: $translation",
            reference.filter { it.all(Char::isDigit) },
            actual.filter { it.all(Char::isDigit) },
        )
        assertEquals(
            "Translation changed control polarity for $id: $translation",
            NEGATION.findAll(case.getString("reference")).count(),
            NEGATION.findAll(translation).count(),
        )
        for (meaning in TRANSLATION_MEANING.getValue(id)) {
            assertTrue(
                "Translation lost required control meaning (${meaning.pattern}) for $id: $translation",
                meaning.containsMatchIn(translation),
            )
        }
    }

    fun memorySnapshot(): JSONObject = JSONObject()
        .put("pssKiB", android.os.Debug.getPss())
        .put("nativeAllocatedBytes", android.os.Debug.getNativeHeapAllocatedSize())

    class RetentionObservation {
        @Volatile var peakQueue = 0
        @Volatile var peakTranscripts = 0
    }

    class TurnTiming {
        var speechEnd = 0L
        var understandingStarted = 0L
        var understandingFinished = 0L
        var translationStarted = 0L
        var translationFinished = 0L
        var outputStarted = 0L
        @Volatile var presentation: Long? = null
        var outputFinished = 0L
        fun reset() {
            speechEnd = 0L
            understandingStarted = 0L
            understandingFinished = 0L
            translationStarted = 0L
            translationFinished = 0L
            outputStarted = 0L
            presentation = null
            outputFinished = 0L
        }
        private fun interval(start: Long, end: Long?): Any =
            if (start > 0 && end != null && end >= start) (end - start) / 1_000_000 else JSONObject.NULL
        fun toJson(): JSONObject = JSONObject()
            .put("vadAndDispatchMillis", interval(speechEnd, understandingStarted))
            .put("languageIdAndAsrMillis", interval(understandingStarted, understandingFinished))
            .put("translationMillis", interval(translationStarted, translationFinished))
            .put("synthesisAndPresentationMillis", interval(outputStarted, presentation))
            .put("outputThroughDrainMillis", interval(outputStarted, outputFinished))
    }

    class FramePacing(capacity: Int) {
        private val scheduled = LongArray(capacity)
        private val delivered = LongArray(capacity)
        @Volatile var frames = 0
            private set
        @Volatile var catchUpFrames = 0
            private set
        @Volatile var lateFrames = 0
            private set
        @Volatile var maximumLatenessNanos = 0L
            private set
        fun record(deadlineOffset: Long, deliveryOffset: Long, catchUp: Boolean) {
            check(frames < scheduled.size)
            scheduled[frames] = deadlineOffset
            delivered[frames] = deliveryOffset
            if (catchUp) catchUpFrames++
            val lateness = (deliveryOffset - deadlineOffset).coerceAtLeast(0)
            if (lateness > 5_000_000) lateFrames++
            maximumLatenessNanos = maxOf(maximumLatenessNanos, lateness)
            frames++
        }
        fun toJson(): JSONObject {
            val deadlines = JSONArray()
            val deliveries = JSONArray()
            repeat(frames) { index -> deadlines.put(scheduled[index]); deliveries.put(delivered[index]) }
            return JSONObject().put("frames", frames).put("catchUpFrames", catchUpFrames)
                .put("lateFramesOver5ms", lateFrames).put("maximumLatenessNanos", maximumLatenessNanos)
                .put("scheduledOffsetsNanos", deadlines).put("deliveredOffsetsNanos", deliveries)
        }
    }

    class PacedCapture(
        private val scope: CoroutineScope,
        private val store: SpeechModelStore,
        private val vadModelId: String = "vad-silero-6.2.2",
        private val vadMinSilenceMs: Int = 700,
    ) : SpeechCaptureEngine {
        @Volatile var pacing = FramePacing(0)
            private set
        @Volatile var running = false
            private set
        private var listener: SpeechCaptureListener? = null
        private var replay: Job? = null

        override suspend fun start(listener: SpeechCaptureListener) {
            check(!running)
            this.listener = listener
            running = true
        }

        suspend fun replay(pcm: ShortArray) {
            require(pcm.isNotEmpty()) { "A replay must contain PCM samples" }
            check(running && replay?.isActive != true)
            val observer = requireNotNull(listener)
            replay = scope.launch(Dispatchers.Default) {
                val lead = 8_000
                val frames = ShortArray(lead + pcm.size + 24_000)
                pcm.copyInto(frames, lead)
                val speechEndSample = lead + pcm.indexOfLast { it.toInt() != 0 } + 1
                val cadence = FramePacing((frames.size + 511) / 512)
                pacing = cadence
                SherpaSpeechSegmenter.create(store, vadModelId, vadMinSilenceMs).use { vad ->
                    val started = System.nanoTime()
                    val speechEnd = if (speechEndSample > lead)
                        started + speechEndSample * 1_000_000_000L / 16_000 else null
                    var offset = 0
                    while (offset < frames.size && running) {
                        ensureActive()
                        val count = minOf(512, frames.size - offset)
                        val deadline = started + (offset + count) * 1_000_000_000L / 16_000
                        val wait = deadline - System.nanoTime()
                        if (wait > 0) delay((wait + 999_999) / 1_000_000)
                        if (!running) break
                        cadence.record(deadline - started, System.nanoTime() - started, catchUp = wait <= 0)
                        vad.accept(frames, offset, count) { segment ->
                            observer.onSegment(
                                SpeechSegment.fromPcm(segment.copyPcm(), speechEndNanos = speechEnd, speechEndMeasured = speechEnd != null),
                            )
                        }
                        offset += count
                    }
                }
            }
        }

        suspend fun awaitReplay() {
            replay?.join()
        }

        override fun interruptCurrent() {
            running = false
            replay?.cancel()
        }

        override suspend fun stop(flush: Boolean): SpeechSegment? {
            interruptCurrent()
            replay?.join()
            replay = null
            return null
        }

        override fun close() = interruptCurrent()
    }
}
