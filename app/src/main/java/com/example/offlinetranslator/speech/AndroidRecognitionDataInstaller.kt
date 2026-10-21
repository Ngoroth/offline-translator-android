package com.example.offlinetranslator.speech

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import com.example.offlinetranslator.core.LanguageCatalog

internal data class AndroidRecognitionDataInstallRequest(
    val identity: AndroidRecognitionServiceIdentity,
    val language: String,
)

internal data class AndroidRecognitionInstallerActivity(
    val packageName: String,
    val name: String,
    val exported: Boolean,
    val enabled: Boolean,
)

/** The substituted test boundary is only external package resolution and activity launch. */
internal interface AndroidRecognitionInstallerClient {
    fun identity(component: String): AndroidRecognitionServiceIdentity?
    fun resolve(packageName: String, language: String): AndroidRecognitionInstallerActivity?
    fun launch(activity: AndroidRecognitionInstallerActivity, language: String)
}

internal class AndroidRecognitionDataInstaller(private val platform: AndroidRecognitionInstallerClient) {
    constructor(context: Context) : this(Platform(context))

    fun install(request: AndroidRecognitionDataInstallRequest?): Boolean {
        request ?: return false
        if (!AndroidRecognitionServices.isQualified(request.identity) || LanguageCatalog.entries.none {
                AndroidSpeechLocales.recognition(it.id) == request.language
            }) return false
        val packageName = request.identity.component.substringBefore('/')
        return try {
            if (platform.identity(request.identity.component) != request.identity) return false
            val activity = platform.resolve(packageName, request.language) ?: return false
            if (!activity.exported || !activity.enabled || activity.packageName != packageName) return false
            // Fresh identity at launch too; public SDK/PM do not offer version-atomic remote attestation.
            if (platform.identity(request.identity.component) != request.identity) return false
            platform.launch(activity, request.language)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private class Platform(private val context: Context) : AndroidRecognitionInstallerClient {
        override fun identity(component: String) = AndroidRecognitionServices.status(context, component).identity
        override fun resolve(packageName: String, language: String): AndroidRecognitionInstallerActivity? {
            val info = context.packageManager.resolveActivity(intent(packageName, language), 0)?.activityInfo ?: return null
            return AndroidRecognitionInstallerActivity(info.packageName, info.name, info.exported,
                info.enabled && info.applicationInfo.enabled)
        }
        override fun launch(activity: AndroidRecognitionInstallerActivity, language: String) {
            context.startActivity(intent(activity.packageName, language).setComponent(ComponentName(activity.packageName, activity.name)))
        }
        private fun intent(packageName: String, language: String) = Intent("com.google.recognition.action.DOWNLOAD_LANGUAGE")
            .setPackage(packageName).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
    }
}
