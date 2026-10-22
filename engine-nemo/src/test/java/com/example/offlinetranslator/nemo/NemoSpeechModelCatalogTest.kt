package com.example.offlinetranslator.nemo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class NemoSpeechModelCatalogTest {
    @Test fun rejectsProfilesThatCannotMeetTheNativeAudioContract() {
        for ((field, value) in listOf(
            "role" to "TTS", "role" to "LANGUAGE_ID", "languages" to JSONArray(),
            "sampleRateHz" to 8_000, "sampleRateHz" to 48_000,
        )) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(0).put(field, value)
            rejects(invalid)
        }
    }

    @Test fun rejectsForeignRuntimeFamiliesHintsAndUninventoriedWeights() {
        val wrongRuntime = document().put("runtime", "sherpa-onnx-1.13.8")
        rejects(wrongRuntime)
        for ((field, value) in listOf(
            "family" to "nemoCtcOffline", "language" to "en", "numThreads" to 2,
            "library" to "arbitrary.so",
        )) {
            val invalid = document()
            invalid.getJSONArray("models").getJSONObject(0).getJSONObject("adapter").put(field, value)
            rejects(invalid)
        }
        val missingWeight = document()
        missingWeight.getJSONArray("models").getJSONObject(0).getJSONObject("adapter")
            .getJSONObject("model").put("path", "unverified.gguf")
        rejects(missingWeight)
    }

    private fun document() = JSONObject(File("src/main/assets/nemo-speech-models.json").readText())
    private fun parse(document: JSONObject) = NemoSpeechModelCatalog.parse(document.toString().byteInputStream())
    private fun rejects(document: JSONObject) {
        assertThrows(IllegalArgumentException::class.java) { parse(document) }
    }
}
