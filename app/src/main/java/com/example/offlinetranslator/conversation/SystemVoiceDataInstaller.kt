package com.example.offlinetranslator.conversation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech

/** No extras or fallback destinations: only the actual initialized engine may install its data. */
internal fun installSystemVoiceData(context: Context, enginePackage: String?): Boolean {
    if (enginePackage.isNullOrBlank()) return false
    return try {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage)
        val resolved = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        if (!resolved.exported || resolved.packageName != enginePackage) return false
        intent.setClassName(resolved.packageName, resolved.name)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
