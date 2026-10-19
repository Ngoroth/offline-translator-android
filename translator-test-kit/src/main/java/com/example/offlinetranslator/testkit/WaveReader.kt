package com.example.offlinetranslator.testkit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmWave(val sampleRateHz: Int, val samples: ShortArray)

object WaveReader {
    fun read(file: File, expectedRate: Int? = null): PcmWave {
        require(file.length() in 44..(22050L * 15 * 2 + 4096)) { "WAV exceeds window quota" }
        val bytes = file.readBytes()
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        require(input.getInt(4).toLong() + 8 == bytes.size.toLong())
        var offset = 12; var rate = 0; var pcm: ShortArray? = null; var formatSeen = false
        while (offset < bytes.size) {
            require(offset + 8L <= bytes.size)
            val size = input.getInt(offset + 4)
            require(size >= 0 && offset + 8L + size <= bytes.size)
            when (String(bytes, offset, 4, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(!formatSeen && size >= 16)
                    formatSeen = true
                    require(input.getShort(offset + 8).toInt() == 1 && input.getShort(offset + 10).toInt() == 1 && input.getShort(offset + 22).toInt() == 16)
                    rate = input.getInt(offset + 12)
                    require(rate in setOf(8000, 16000, 22050))
                    require(input.getInt(offset + 16) == rate*2 && input.getShort(offset + 20).toInt() == 2)
                }
                "data" -> {
                    require(formatSeen && pcm == null && size > 0 && size % 2 == 0)
                    pcm = ShortArray(size / 2) { input.getShort(offset + 8 + it * 2) }
                }
            }
            offset += 8 + size + size % 2
        }
        require(formatSeen && pcm != null && pcm.size <= rate * 15 && offset == bytes.size)
        require(expectedRate == null || rate == expectedRate)
        return PcmWave(rate, pcm)
    }
}

object WaveWriter {
    fun fromFloats(samples: FloatArray): ShortArray {
        require(samples.isNotEmpty() && samples.size <= 330750 && samples.all { it.isFinite() && it in -1f..1f })
        return ShortArray(samples.size) { kotlin.math.round(samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }
    fun write(file: File, pcm: ShortArray, rate: Int) {
        require(rate in setOf(16000, 22050) && pcm.isNotEmpty() && pcm.size <= rate * 15)
        requireNotNull(file.parentFile).mkdirs()
        val output = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        output.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVEfmt ".toByteArray()).putInt(16)
        output.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        output.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach(output::putShort)
        file.outputStream().use { it.write(output.array()) }
    }
}
