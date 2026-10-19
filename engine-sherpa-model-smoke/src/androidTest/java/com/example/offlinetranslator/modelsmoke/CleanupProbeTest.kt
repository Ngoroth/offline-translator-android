package com.example.offlinetranslator.modelsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CleanupProbeTest {
    @Test fun scenarioFinallyReleasedAllIntermediateWaveFiles() {
        val bytes=listOf("temporary-tts.wav","temporary-full.wav","stress.wav").sumOf { File(EvaluationSession.root,it).length() }
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("stream","CLEANUP_PROBE intermediateWaveBytes=$bytes\n")})
        assertEquals("Scenario leaked an intermediate WAV",0L,bytes)
    }
}
