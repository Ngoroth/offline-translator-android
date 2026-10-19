package com.example.offlinetranslator.sherpa

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Stateful 16 kHz to 8 kHz low-pass decimator. One instance belongs to one utterance. */
class PcmResampler {
    private val history = FloatArray(TAPS)
    private var accepted = 0L

    /** Returns normalized samples. Chunk boundaries do not reset filter or decimation phase. */
    fun accept(input: ShortArray): FloatArray {
        val result = FloatArray((input.size + if (accepted % 2L == 0L) 1 else 0) / 2)
        accept(input, 0, input.size, result)
        return result
    }

    /** Fills reusable native-input storage without copying a PCM chunk or filter output. */
    internal fun accept(input: ShortArray, offset: Int, count: Int, output: FloatArray): Int {
        require(offset >= 0 && count >= 0 && offset <= input.size - count)
        val expected = (count + if (accepted % 2L == 0L) 1 else 0) / 2
        require(output.size >= expected)
        var written = 0
        for (index in offset until offset + count) {
            history[(accepted % TAPS).toInt()] = input[index] / 32768f
            if (accepted % 2L == 0L) {
                var filtered = 0.0
                for (lag in 0 until TAPS) {
                    if (accepted >= lag) {
                        val slot = ((accepted - lag) % TAPS).toInt()
                        filtered += COEFFICIENTS[lag] * history[slot]
                    }
                }
                output[written++] = filtered.toFloat().coerceIn(-1f, 1f)
            }
            accepted++
        }
        return written
    }

    companion object {
        private const val TAPS = 63
        private const val CUTOFF = 0.22 // cycles per input sample, below the 8 kHz Nyquist limit
        private val COEFFICIENTS: DoubleArray = DoubleArray(TAPS) { lag ->
            val offset = lag - (TAPS - 1) / 2
            val sinc = if (offset == 0) 2 * CUTOFF else sin(2 * PI * CUTOFF * offset) / (PI * offset)
            val window = 0.54 - 0.46 * cos(2 * PI * lag / (TAPS - 1))
            sinc * window
        }.let { raw ->
            val gain = raw.sum()
            DoubleArray(TAPS) { raw[it] / gain }
        }
    }
}
