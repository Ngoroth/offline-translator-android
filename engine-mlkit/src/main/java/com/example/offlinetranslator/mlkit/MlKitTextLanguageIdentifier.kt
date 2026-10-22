package com.example.offlinetranslator.mlkit

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.LanguagePair
import com.example.offlinetranslator.core.TextLanguageIdentifier
import com.google.mlkit.nl.languageid.IdentifiedLanguage
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Bundled text ID, independent of translation. Its prepared owner serializes use and close. */
class MlKitTextLanguageIdentifier internal constructor(
    private val client: TextLanguageIdentificationClient,
) : TextLanguageIdentifier {
    constructor() : this(GoogleTextLanguageIdentificationClient())

    override suspend fun identify(text: String, pair: LanguagePair): LanguageId? {
        coroutineContext.ensureActive()
        if (!hasUnicodeLetter(text)) return null
        val caller = coroutineContext
        // Cancellation must not release the outer owner's lease while the SDK still uses its client.
        val candidates = withContext(NonCancellable) { client.identify(text).await() }
        caller.ensureActive()
        return selectTextLanguage(candidates, pair, { it.languageTag }, { it.confidence.toDouble() })
    }

    override fun close() = client.close()
}

internal interface TextLanguageIdentificationClient {
    fun identify(text: String): PendingOperation<List<IdentifiedLanguage>>
    fun close()
}

private class GoogleTextLanguageIdentificationClient : TextLanguageIdentificationClient {
    private val identifier = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.01f).build(),
    )

    override fun identify(text: String): PendingOperation<List<IdentifiedLanguage>> =
        GoogleTaskOperation(identifier.identifyPossibleLanguages(text))

    override fun close() = identifier.close()
}

internal fun hasUnicodeLetter(text: String): Boolean {
    var offset = 0
    while (offset < text.length) {
        val codePoint = Character.codePointAt(text, offset)
        if (Character.isLetter(codePoint)) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

/**
 * Frozen global-score policy. Scan the SDK list directly: no sorting, copied score hierarchy,
 * selected-pair renormalization or tag rewriting. Scores below the SDK's 0.01 floor are omitted;
 * these values are neither a full vector nor calibrated acoustic probabilities.
 */
internal inline fun <T> selectTextLanguage(
    candidates: List<T>,
    pair: LanguagePair,
    languageTag: (T) -> String,
    confidence: (T) -> Double,
): LanguageId? {
    var bestTag: String? = null
    var bestScore = 0.0
    var runnerUp = 0.0
    for (candidate in candidates) {
        val score = confidence(candidate)
        if (!score.isFinite()) return null
        if (score > bestScore) {
            runnerUp = bestScore
            bestScore = score
            bestTag = languageTag(candidate)
        } else if (score > runnerUp) {
            runnerUp = score
        }
    }
    if (bestScore < 0.50 || bestScore - runnerUp < 0.20) return null
    return when (bestTag) {
        pair.source.value -> pair.source
        pair.target.value -> pair.target
        else -> null
    }
}
