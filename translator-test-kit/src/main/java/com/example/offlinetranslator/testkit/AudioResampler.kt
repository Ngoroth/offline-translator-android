package com.example.offlinetranslator.testkit

import kotlin.math.*

/** Bounded window resampling for TTS oracle only; production RU FIR is unchanged. */
object AudioResampler {
    fun resample(pcm: ShortArray, inputHz: Int, outputHz: Int = 16000): ShortArray {
        require(inputHz in setOf(16000, 22050) && outputHz == 16000 && pcm.size <= inputHz * 15)
        if (inputHz == outputHz) return pcm.copyOf()
        val count = (pcm.size.toDouble() * outputHz / inputHz).roundToInt()
        val cutoff = .45 * min(inputHz, outputHz) / inputHz
        return ShortArray(count) { index ->
            val center = index.toDouble() * inputHz / outputHz
            val start = floor(center).toInt() - 31
            var weighted = 0.0; var weights = 0.0
            for (tap in 0 until 64) {
                val source = start + tap
                val distance = source - center
                val sinc = if (abs(distance) < 1e-9) 2 * cutoff else sin(2 * PI * cutoff * distance) / (PI * distance)
                val weight = sinc * (.5 - .5 * cos(2 * PI * tap / 63))
                weights += weight
                if (source in pcm.indices) weighted += pcm[source] * weight
            }
            (weighted / weights).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
