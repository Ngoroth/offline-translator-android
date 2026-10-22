package com.example.offlinetranslator.speechmodels

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole

/** Artifact references are resolved against the catalog inventory before any native factory runs. */
data class SpeechModelRef(val packageId: String, val path: String)

data class SpeechModelDefinition(
    val id: String,
    val displayName: String,
    val role: SpeechModelRole,
    val languages: Set<LanguageId>,
    val sampleRateHz: Int,
    val adapter: SpeechModelAdapter,
    val requiredRefs: List<SpeechModelRef>,
)

/** A compiled provider profile; catalog metadata cannot select arbitrary classes or libraries. */
interface SpeechModelAdapter
