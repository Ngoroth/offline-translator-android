package com.example.offlinetranslator.nemo

import com.example.offlinetranslator.speechmodels.SpeechModelAdapter
import com.example.offlinetranslator.speechmodels.SpeechModelRef

/** Local GGUF executed by the compiled CPU NeMo C ABI, never a Sherpa profile. */
sealed interface NemoSpeechModelAdapter : SpeechModelAdapter {
    data class ParakeetTdtGguf(val model: SpeechModelRef) : NemoSpeechModelAdapter
}
