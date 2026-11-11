package io.github.ngoroth.offlinetranslator.modelsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ngoroth.offlinetranslator.core.*
import io.github.ngoroth.offlinetranslator.tencent.TencentTranslationEngine
import io.github.ngoroth.offlinetranslator.testkit.Integrity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreparationTest {
    @Test fun preparesIsolatedProviderModelForBothDirections() = runBlocking {
        val connectivity=EvaluationSession.context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val capabilities=kotlinx.coroutines.withTimeout(60000) {
            while(true) {
                val current=connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                if(current?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true && current.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@withTimeout current
                kotlinx.coroutines.delay(250)
            }
            error("unreachable")
        }
        assertTrue("Preparation requires validated Wi-Fi",capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)==true &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        val engine=EvaluationSession.translationEngine()
        try {
            assertEquals(ModelAvailability.READY,engine.prepare())
            val ru=engine.translate(EvaluationSession.russianToEnglish,"Доброе утро")
            val en=engine.translate(EvaluationSession.englishToRussian,"Good evening")
            assertTrue(ru.isNotBlank());assertTrue(en.isNotBlank())
            val artifact = File(EvaluationSession.context.noBackupFilesDir, "tencent-translation/Hy-MT2-1.8B-Q4_K_M.gguf")
            assertEquals("Prepared Tencent artifact size must match its pin", 1_133_080_448L, artifact.length())
            val sha256 = Integrity.sha256(artifact)
            assertEquals("Prepared Tencent artifact digest must match its pin",
                "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699", sha256)
            EvaluationSession.writeEvidence("preparation",org.json.JSONObject().put("sdk",TencentTranslationEngine.descriptor.sdkVersion)
                .put("readiness","READY").put("validatedWifi",true).put("providerWeightsSha256",sha256)
                .put("greetings",org.json.JSONArray().put(org.json.JSONObject().put("input","Доброе утро").put("output",ru))
                    .put(org.json.JSONObject().put("input","Good evening").put("output",en))))
        } finally { engine.closeAndJoin() }
    }
}
