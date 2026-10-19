package com.example.offlinetranslator.testkit

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Disk-backed exact 120 second input; only one bounded clip is retained in memory. */
object StressTimeline {
    const val TOTAL_SAMPLES = 1920000
    const val FINAL_PADDING = 75712
    fun write(corpus: File,output: File): Int {
        var count = 0;var boundary = 0
        RandomAccessFile(output,"rw").use { file ->
            file.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + TOTAL_SAMPLES*2).put("WAVEfmt ".toByteArray()).putInt(16)
            header.putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(TOTAL_SAMPLES*2);file.write(header.array())
            fun silence(samples: Int) {
                val zeros = ByteArray(4096);var remaining = samples*2
                while(remaining > 0) { val bytes = minOf(remaining,zeros.size);file.write(zeros,0,bytes);remaining -= bytes }
                count += samples
            }
            silence(8000)
            for(language in listOf("ru","en")) {
                repeat(4) { for(index in 1..4) {
                    val pcm = WaveReader.read(File(corpus,"smoke/$language-$index.wav"),16000).samples
                    val bytes = ByteBuffer.allocate(pcm.size*2).order(ByteOrder.LITTLE_ENDIAN)
                    pcm.forEach(bytes::putShort);file.write(bytes.array());count += pcm.size;silence(12800)
                } }
                if(language == "ru") boundary = count
            }
            require(count == TOTAL_SAMPLES - FINAL_PADDING) { "Pinned stress sample counts changed: $count" }
            silence(FINAL_PADDING);require(count == TOTAL_SAMPLES && file.length() == 44L + TOTAL_SAMPLES*2)
        }
        return boundary
    }
}
