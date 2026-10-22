package com.example.offlinetranslator.nemo

import android.content.Context
import com.example.offlinetranslator.core.SpeechModelRole
import com.example.offlinetranslator.core.SpeechTranscriber
import com.example.offlinetranslator.speechmodels.SpeechModelAdapterReader
import com.example.offlinetranslator.speechmodels.SpeechModelCatalog
import com.example.offlinetranslator.speechmodels.SpeechModelStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream

object NemoSpeechModelCatalog {
    const val MODEL_ID = "nemo-parakeet-tdt-v3-q8"
    private const val RUNTIME = "nemo-speech.cpp-0.2.0"

    fun fromAssets(context: Context): SpeechModelCatalog =
        context.assets.open("nemo-speech-models.json").use(::parse)

    fun parse(input: InputStream): SpeechModelCatalog =
        SpeechModelCatalog.parse(input, RUNTIME, ::decodeAdapter)

    private fun decodeAdapter(reader: SpeechModelAdapterReader): NemoSpeechModelAdapter {
        reader.fields("family", "model")
        require(reader.text("family") == "parakeetTdtGguf") { "Unsupported NeMo adapter family" }
        require(reader.role == SpeechModelRole.ASR && reader.sampleRateHz == 16_000 &&
            reader.languages.isNotEmpty()) { "Invalid NeMo ASR role/languages/rate" }
        return NemoSpeechModelAdapter.ParakeetTdtGguf(reader.ref("model"))
    }

    /** The combined recognition owner calls this off main and owns the returned stage. */
    suspend fun createTranscriber(store: SpeechModelStore): SpeechTranscriber {
        val definition = store.catalog.model(MODEL_ID)
        val adapter = requireNotNull(definition.adapter as? NemoSpeechModelAdapter.ParakeetTdtGguf) {
            "Selected model is not a NeMo GGUF profile"
        }
        val file = store.modelFile(adapter.model.packageId, adapter.model.path)
        currentCoroutineContext().ensureActive()
        // No suspension after native creation: ownership must reach the combined owner first.
        return NemoSpeechTranscriber.create(file)
    }
}
