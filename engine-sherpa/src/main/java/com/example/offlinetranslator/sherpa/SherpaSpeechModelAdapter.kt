package com.example.offlinetranslator.sherpa

import com.example.offlinetranslator.speechmodels.SpeechModelAdapter
import com.example.offlinetranslator.speechmodels.SpeechModelRef

/** Only compiled Sherpa families may cross its native boundary. */
sealed interface SherpaSpeechModelAdapter : SpeechModelAdapter {
    data class Silero(
        val model: SpeechModelRef,
        val numThreads: Int,
        val threshold: Float,
        val minSpeechDuration: Float,
        val maxSpeechDuration: Float,
        val windowSize: Int,
    ) : SherpaSpeechModelAdapter

    data class NemoCtcOffline(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SherpaSpeechModelAdapter

    data class WhisperOffline(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val tokens: SpeechModelRef,
        val language: String,
        val task: String,
        val tailPaddings: Int,
        val numThreads: Int,
        val featureDim: Int,
    ) : SherpaSpeechModelAdapter

    data class WhisperMultilingual(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val tokens: SpeechModelRef,
        val tailPaddings: Int,
        val numThreads: Int,
        val featureDim: Int,
    ) : SherpaSpeechModelAdapter


    data class PiperVits(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val dataDir: SpeechModelRef,
        val noiseScale: Float,
        val noiseScaleW: Float,
        val lengthScale: Float,
        val numThreads: Int,
    ) : SherpaSpeechModelAdapter

    data class ToneCtcOnline(
        val model: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SherpaSpeechModelAdapter

    data class Zipformer2Online(
        val encoder: SpeechModelRef,
        val decoder: SpeechModelRef,
        val joiner: SpeechModelRef,
        val tokens: SpeechModelRef,
        val numThreads: Int,
        val featureDim: Int,
    ) : SherpaSpeechModelAdapter
}
