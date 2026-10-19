package com.example.offlinetranslator.testkit

enum class Scenario { REGRESSION, FEATURE, SMOKE, ASR, VAD, TTS, FULL_PIPELINE, STRESS }

object EvaluationGates {
    const val MAX_ASR_MS = 1500.0
    const val MAX_FULL_MS = 8000.0
    const val MAX_PSS_KIB = 1310720L
    const val MAX_NATIVE_KIB = 1048576L
    const val RELEASE_PSS_KIB = 524288L
    const val RELEASE_NATIVE_KIB = 393216L
    fun finiteAtMost(value: Double?, limit: Double) = value != null && value.isFinite() && value <= limit && value >= 0
    fun asr(wer: Double?, cer: Double?, semanticFailures: Int, empty: Int, misses: Int, rtf: Double?) =
        finiteAtMost(wer, .20) && finiteAtMost(cer, .10) && finiteAtMost(rtf, 1.0) && semanticFailures == 0 && empty == 0 && misses == 0
    fun tts(wer: Double?, cer: Double?, semanticFailures: Int) =
        finiteAtMost(wer, .30) && finiteAtMost(cer, .15) && semanticFailures == 0
    fun signal(metrics: SignalMetrics) = metrics.rms.isFinite() && metrics.rms > 100 &&
        metrics.zeroFraction.isFinite() && metrics.zeroFraction in 0.0..1.0 && metrics.zeroFraction < .98 &&
        metrics.clippingFraction.isFinite() && metrics.clippingFraction in 0.0..1.0 && metrics.clippingFraction < .01
}

data class SignalMetrics(val rms: Double, val zeroFraction: Double, val clippingFraction: Double) {
    companion object {
        fun measure(pcm: ShortArray): SignalMetrics {
            require(pcm.isNotEmpty())
            var squares = 0.0; var zeros = 0; var clipped = 0
            pcm.forEach { sample ->
                squares += sample.toDouble() * sample
                if (sample == 0.toShort()) zeros++
                if (kotlin.math.abs(sample.toInt()) >= 32767) clipped++
            }
            return SignalMetrics(kotlin.math.sqrt(squares / pcm.size), zeros.toDouble() / pcm.size, clipped.toDouble() / pcm.size)
        }
    }
}
