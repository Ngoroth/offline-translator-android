package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole

/** Artifact references are resolved against the catalog inventory before any native factory runs. */
data class SpeechModelRef(val packageId: String, val path: String)

data class SpeechModelDefinition(
    val id: String,
    val displayName: String,
    val role: SpeechModelRole,
    val languages: Set<LanguageId>,
    val sampleRateHz: Int,
    val adapter: SpeechModelAdapter,
    val requiredRefs: List<SpeechModelRef>,
)

/** Only compiled families may cross the native boundary; provider/library paths are not metadata. */
sealed interface SpeechModelAdapter {
    data class Silero(
        val model: SpeechModelRef,
        val numThreads: Int,
        val threshold: Float,
        val minSpeechDuration: Float,
        val maxSpeechDuration: Float,
        val windowSize: Int,
    ) : SpeechModelAdapter

    data class NemoCtcOffline(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SpeechModelAdapter

    data class WhisperOffline(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val tokens: SpeechModelRef,
        val language: String,
        val task: String,
        val tailPaddings: Int,
        val numThreads: Int,
        val featureDim: Int,
    ) : SpeechModelAdapter

    data class WhisperLanguageId(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val numThreads: Int,
    ) : SpeechModelAdapter

    data class PiperVits(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val dataDir: SpeechModelRef,
        val noiseScale: Float,
        val noiseScaleW: Float,
        val lengthScale: Float,
        val numThreads: Int,
    ) : SpeechModelAdapter

    data class ToneCtcOnline(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SpeechModelAdapter

    data class Zipformer2Online(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val joiner: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SpeechModelAdapter
}
