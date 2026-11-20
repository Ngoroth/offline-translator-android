package io.github.ngoroth.offlinetranslator.sherpa

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import android.system.OsConstants
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/** Owned, single-caller PCM synthesis for a local Piper voice. */
class PiperVoice internal constructor(private val native: OfflineTts, private val speed: Float) : AutoCloseable {
    val sampleRate: Int = native.sampleRate()
    private val voice = native.lang().also { check(it.isNotBlank()) { "Piper model has no phonemizer voice" } }

    fun synthesize(text: String, callback: ((FloatArray) -> Int)? = null): FloatArray {
        val phonemes = piperPhonemizeText(text, voice) ?: error("Piper phonemization failed")
        val codepoints = try {
            Array(phonemes.numSentences, phonemes::getPhonemes)
        } finally {
            phonemes.close()
        }
        check(codepoints.any(IntArray::isNotEmpty)) { "Piper phonemization returned no input" }
        val config = GenerationConfig(speed = speed, phonemeCodepoints = codepoints)
        val audio = if (callback == null) {
            native.generateWithConfig("", config)
        } else {
            native.generateWithConfigAndCallback("", config, callback)
        }
        check(audio.sampleRate == sampleRate) { "Local voice returned an unsupported sample rate" }
        return audio.samples
    }

    override fun close() = native.release()
}

internal object PiperPhonemizer {
    private const val DIRECTORY = "piper-phonemize-1.5.9"
    private const val ASSET_DIRECTORY = "espeak-ng-data"
    private var initializationResult: Int? = null

    @Synchronized
    fun initialize(context: Context) {
        // Upstream calls eSpeak only once per process and returns zero on subsequent calls, even after failure.
        val result = initializationResult ?: piperPhonemizeInitialize(dataDirectory(context).absolutePath)
            .also { initializationResult = it }
        check(result > 0) { "Piper phonemizer initialization failed" }
    }

    private fun dataDirectory(context: Context): File {
        val root = context.noBackupFilesDir
        val installed = File(root, DIRECTORY)
        if (installed.isDirectory) return installed
        RandomAccessFile(File(root, ".$DIRECTORY.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                if (!installed.isDirectory) {
                    val partial = File(root, ".$DIRECTORY.partial")
                    check(partial.deleteRecursively() && partial.mkdir()) { "Cannot stage Piper phonemizer data" }
                    try {
                        copyAsset(context.assets, ASSET_DIRECTORY, File(partial, ASSET_DIRECTORY), ByteArray(DEFAULT_BUFFER_SIZE))
                        syncDirectory(partial)
                        // Only a complete, durable tree may be reused; interrupted copies remain unpublished.
                        Os.rename(partial.absolutePath, installed.absolutePath)
                        syncDirectory(root)
                    } finally {
                        partial.deleteRecursively()
                    }
                }
            }
        }
        return installed
    }

    private fun copyAsset(assets: AssetManager, path: String, target: File, buffer: ByteArray) {
        val children = checkNotNull(assets.list(path))
        if (children.isEmpty()) {
            assets.open(path).use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
        } else {
            check(target.mkdir()) { "Cannot create Piper phonemizer data directory" }
            for (child in children) copyAsset(assets, "$path/$child", File(target, child), buffer)
            syncDirectory(target)
        }
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }
}
