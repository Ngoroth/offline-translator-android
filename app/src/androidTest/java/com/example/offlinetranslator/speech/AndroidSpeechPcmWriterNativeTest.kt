package com.example.offlinetranslator.speech

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranslator.core.SpeechSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real kernel-pipe backpressure proof, not an ASR/provider-language quality test. */
@RunWith(AndroidJUnit4::class)
class AndroidSpeechPcmWriterNativeTest {
    @Test fun cancellationReleasesWriterEvenWhenConsumerRetainsDuplicatedReadFd() = runBlocking {
        val pipe = AndroidPlatformSpeechRecognizerClient.PipeAudio()
        val retainedConsumer = ParcelFileDescriptor.dup(pipe.read.fileDescriptor)
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.IO) {
            started.complete(Unit)
            try { pipe.write(ShortArray(SpeechSegment.MAX_SAMPLE_COUNT) { 123 }) }
            finally { released.complete(Unit) }
        }
        try {
            started.await()
            delay(200)
            assertFalse("A non-reading retained consumer must impose actual backpressure", released.isCompleted)
            // Closing only the local ends cannot revoke the retained consumer. Cancellation must
            // be intrinsic to the nonblocking writer, not assumed to interrupt a blocking syscall.
            withTimeout(2_000) { writer.cancelAndJoin(); released.await() }
            pipe.close()
            assertTrue(writer.isCompleted)
            assertTrue(retainedConsumer.fileDescriptor.valid())
        } finally {
            writer.cancel()
            retainedConsumer.close()
            pipe.close()
            withTimeout(2_000) { writer.join() }
        }
    }
    @Test fun writerPreservesExactLittleEndianPcmAndClosesAtEofWithoutPadding() = runBlocking {
        val pipe = AndroidPlatformSpeechRecognizerClient.PipeAudio()
        val samples = shortArrayOf(0, 1, -1, 32767, -32768, 0x1234)
        val writer = launch(Dispatchers.IO) { pipe.write(samples) }
        try {
            val bytes = withTimeout(2_000) {
                withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(pipe.read).use { it.readBytes() } }
            }
            writer.join()
            assertTrue(bytes.size == samples.size * 2)
            for (index in samples.indices) {
                val value = (bytes[index * 2].toInt() and 255) or ((bytes[index * 2 + 1].toInt() and 255) shl 8)
                assertTrue(value.toShort() == samples[index])
            }
        } finally { writer.cancelAndJoin(); pipe.close() }
    }
}
