package com.example.offlinetranslator.modelsmoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.offlinetranslator.core.*
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
            EvaluationSession.writeEvidence("preparation",org.json.JSONObject().put("sdk","ML Kit Translation 17.0.3")
                .put("readiness","READY").put("validatedWifi",true).put("providerWeightsSha256",org.json.JSONObject.NULL)
                .put("weightIdentityUnavailable","Provider-managed weights do not expose a digest")
                .put("greetings",org.json.JSONArray().put(org.json.JSONObject().put("input","Доброе утро").put("output",ru))
                    .put(org.json.JSONObject().put("input","Good evening").put("output",en))))
        } finally { engine.closeAndJoin() }
    }
}
