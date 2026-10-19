package com.example.offlinetranslator.settings

import com.example.offlinetranslator.core.LanguageId
import com.example.offlinetranslator.core.SpeechModelRole

data class SpeechModelChoice(val id: String, val role: SpeechModelRole, val languages: Set<LanguageId>, val displayName: String)
