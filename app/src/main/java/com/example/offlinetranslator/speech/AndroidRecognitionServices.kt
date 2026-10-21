package com.example.offlinetranslator.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
import com.example.offlinetranslator.R
import com.example.offlinetranslator.core.SpeechRecognitionReadiness
import java.security.MessageDigest

internal data class AndroidRecognitionServiceChoice(val component: String?, val label: String)

/** Installed identity, not an attestation of the asynchronously bound remote process. */
internal data class AndroidRecognitionServiceIdentity(
    val component: String,
    val version: Long,
    val updatedAt: Long,
    val signer: String,
)

internal data class AndroidRecognitionServiceStatus(
    val readiness: SpeechRecognitionReadiness,
    val identity: AndroidRecognitionServiceIdentity? = null,
)

internal object AndroidRecognitionServices {
    const val GOOGLE_COMPONENT = "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
    const val GOOGLE_SIGNER = "d22cc500299fb22873a01a010de1c82fbe4d061119b94814dd301dab50cb7678"
    const val GOOGLE_ALTERNATE_SIGNER = "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"

    fun isQualified(identity: AndroidRecognitionServiceIdentity): Boolean =
        identity.component == GOOGLE_COMPONENT &&
            (identity.signer == GOOGLE_SIGNER || identity.signer == GOOGLE_ALTERNATE_SIGNER)

    fun choices(context: Context): List<AndroidRecognitionServiceChoice> {
        val manager = context.packageManager
        val services = manager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { result ->
                val service = result.serviceInfo ?: return@mapNotNull null
                if (!service.enabled || !service.applicationInfo.enabled || !service.exported) return@mapNotNull null
                AndroidRecognitionServiceChoice(
                    ComponentName(service.packageName, service.name).flattenToString(),
                    result.loadLabel(manager).toString().ifBlank { service.packageName },
                )
            }.distinctBy { it.component }
        val repeated = services.groupingBy { it.label }.eachCount()
        return listOf(AndroidRecognitionServiceChoice(null, context.getString(R.string.recognition_service_on_device))) +
            services.map { choice ->
                if (repeated.getValue(choice.label) > 1) choice.copy(label = "${choice.label} (${choice.component})") else choice
            }.sortedWith(compareBy({ it.label }, { it.component }))
    }

    fun status(context: Context, selected: String): AndroidRecognitionServiceStatus {
        val unavailable = AndroidRecognitionServiceStatus(SpeechRecognitionReadiness.ENGINE_UNAVAILABLE)
        val incompatible = AndroidRecognitionServiceStatus(SpeechRecognitionReadiness.INCOMPATIBLE_SERVICE)
        val component = ComponentName.unflattenFromString(selected) ?: return incompatible
        return try {
            val manager = context.packageManager
            val service = manager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE).setPackage(component.packageName), 0)
                .firstOrNull { it.serviceInfo?.let { info ->
                    info.packageName == component.packageName && info.name == component.className
                } == true }?.serviceInfo ?: return unavailable
            if (!service.enabled || !service.applicationInfo.enabled || !service.exported) return unavailable
            if (component.flattenToString() != GOOGLE_COMPONENT) return incompatible
            val info = manager.getPackageInfo(component.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            // History is deliberately not accepted: qualify exactly the current signer set.
            val signers = info.signingInfo?.apkContentsSigners ?: return incompatible
            if (signers.size != 1) return incompatible
            val digest = MessageDigest.getInstance("SHA-256").digest(signers.single().toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val identity = AndroidRecognitionServiceIdentity(component.flattenToString(), info.longVersionCode, info.lastUpdateTime, digest)
            if (!isQualified(identity)) return incompatible
            AndroidRecognitionServiceStatus(SpeechRecognitionReadiness.READY, identity)
        } catch (_: PackageManager.NameNotFoundException) {
            unavailable
        } catch (_: SecurityException) {
            unavailable
        }
    }
}
