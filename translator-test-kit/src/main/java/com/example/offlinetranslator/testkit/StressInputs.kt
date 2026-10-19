package com.example.offlinetranslator.testkit

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Prepared input bytes only: no segments, processing, recognition or measurements. */
object StressInputs {
    const val VERSION="pcm-stress-v1-leading8000-trailing24000-gap12800-repeat4-rate16000"
    fun write(corpus: File,destination: File,cases: List<CorpusCase>): Int {
        require(destination.mkdirs() || destination.isDirectory)
        val boundary=StressTimeline.write(corpus,File(destination,"stress.wav"))
        for(case in cases.filter {it.cohort=="quality"}) {
            val raw=WaveReader.read(File(corpus,case.path),16000).samples
            val padded=ShortArray(raw.size+32000);raw.copyInto(padded,8000)
            WaveWriter.write(File(destination,"padded/${case.id}.wav"),padded,16000)
        }
        File(destination,"inputs.json").writeText(JSONObject().put("version",VERSION).put("boundary",boundary).toString())
        return boundary
    }
    fun padded(directory: File,case: CorpusCase): ShortArray {
        require(case.cohort=="quality" && Integrity.safePath(case.id))
        val file=File(directory,"padded/${case.id}.wav")
        require(file.canonicalFile==file.absoluteFile)
        return WaveReader.read(file,16000).samples.also {require(it.size==case.json.getInt("sampleCount")+32000)}
    }
    /** Compare to trusted corpus, not just a mutable receipt. Bounded memory, no generated output. */
    fun verify(corpus: File,directory: File,cases: List<CorpusCase>): Int {
        val metadata=Integrity.json(File(directory,"inputs.json"))
        require(metadata.getString("version")==VERSION)
        for(case in cases.filter {it.cohort=="quality"}) {
            val input=padded(directory,case)
            val raw=WaveReader.read(File(corpus,case.path),16000).samples
            require(input.take(8000).all {it==0.toShort()} && input.takeLast(24000).all {it==0.toShort()})
            for(index in raw.indices) require(raw[index]==input[index+8000]) {"Prepared PCM differs: ${case.id}"}
        }
        val file=File(directory,"stress.wav")
        require(file.canonicalFile==file.absoluteFile && file.length()==3840044L)
        var count=0;var boundary=0
        RandomAccessFile(file,"r").use { input ->
            val header=ByteArray(44);input.readFully(header)
            val expected=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            expected.put("RIFF".toByteArray()).putInt(3840036).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(3840000)
            require(header.contentEquals(expected.array()))
            fun silence(samples: Int) {
                val bytes=ByteArray(4096);var remaining=samples*2
                while(remaining>0) { val size=minOf(remaining,bytes.size);input.readFully(bytes,0,size);require((0 until size).all {bytes[it]==0.toByte()});remaining-=size }
                count+=samples
            }
            silence(8000)
            for(language in listOf("ru","en")) {
                repeat(4) {for(index in 1..4) {
                    val raw=WaveReader.read(File(corpus,"smoke/$language-$index.wav"),16000).samples
                    val bytes=ByteArray(raw.size*2);input.readFully(bytes)
                    val actual=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    raw.forEach {require(it==actual.short) {"Prepared stress differs"}}
                    count+=raw.size;silence(12800)
                }}
                if(language=="ru") boundary=count
            }
            require(count==StressTimeline.TOTAL_SAMPLES-StressTimeline.FINAL_PADDING)
            silence(StressTimeline.FINAL_PADDING);require(input.filePointer==file.length())
        }
        require(metadata.getInt("boundary")==boundary)
        return boundary
    }
}
