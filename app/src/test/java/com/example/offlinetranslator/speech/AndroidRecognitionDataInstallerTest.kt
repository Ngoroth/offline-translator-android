package com.example.offlinetranslator.speech

import org.junit.Assert.*
import org.junit.Test

class AndroidRecognitionDataInstallerTest {
    private val identity = AndroidRecognitionServiceIdentity(AndroidRecognitionServices.GOOGLE_COMPONENT,
        210683483L, 1, AndroidRecognitionServices.GOOGLE_SIGNER)
    private val qualifiedIdentities = listOf(
        identity.copy(version = 310683500L),
        identity.copy(version = 310683500L,
            signer = "7ce83c1b71f3d572fed04c8d40c5cb10ff75e6d87d9df6fbd53f0468c2905053"),
    )
    private val request = AndroidRecognitionDataInstallRequest(identity, "ru-RU")
    private val activity = AndroidRecognitionInstallerActivity("com.google.android.tts", "public.DownloadActivity", true, true)

    @Test fun unknownComponentOrCurrentSignerCannotLaunchInstaller() {
        for (qualified in qualifiedIdentities) {
            val candidates = listOf(
                qualified.copy(component = "other/Service"),
                qualified.copy(signer = "0".repeat(64)),
            )
            for (candidate in candidates) {
                val platform = Platform(candidate, activity)
                assertFalse(AndroidRecognitionDataInstaller(platform).install(request.copy(identity = candidate)))
                assertTrue(platform.launched.isEmpty())
                assertEquals(0, platform.resolutions)
            }
        }
    }

    @Test fun absentPrivateDisabledOrWrongPackageInstallerNeverLaunchesFallback() {
        for (candidate in listOf(null, activity.copy(exported = false), activity.copy(enabled = false),
            activity.copy(packageName = "other.package"))) {
            val platform = Platform(identity, candidate)
            assertFalse(AndroidRecognitionDataInstaller(platform).install(request))
            assertTrue(platform.launched.isEmpty())
        }
    }

    @Test fun replacementDuringResolveRevokesLaunchAndDeniedLaunchIsVisibleFailure() {
        for (replacement in listOf(
            identity.copy(version = identity.version + 1),
            identity.copy(updatedAt = 2),
            identity.copy(signer = qualifiedIdentities.last().signer),
        )) {
            val platform = Platform(identity, activity)
            platform.afterResolve = { platform.current = replacement }
            assertFalse(AndroidRecognitionDataInstaller(platform).install(request))
            assertTrue(platform.launched.isEmpty())
            platform.afterResolve = null
            val resolutions = platform.resolutions
            assertFalse(AndroidRecognitionDataInstaller(platform).install(request))
            assertEquals(resolutions, platform.resolutions)
            assertTrue(platform.launched.isEmpty())
        }
        val denied = Platform(identity, activity).apply { this.denied = true }
        assertFalse(AndroidRecognitionDataInstaller(denied).install(request))
        assertTrue(denied.launched.isEmpty())
    }

    @Test fun installedProviderGetsOnlyOneSelectedLanguageAndNoAutomaticNextAction() {
        for (candidate in qualifiedIdentities) {
            val platform = Platform(candidate, activity)
            val installer = AndroidRecognitionDataInstaller(platform)
            val selected = request.copy(identity = candidate)
            assertTrue(installer.install(selected))
            assertEquals(listOf(activity to "ru-RU"), platform.launched)
            assertFalse(installer.install(null))
            assertFalse(installer.install(selected.copy(language = "arbitrary")))
            platform.current = null
            assertFalse(installer.install(selected.copy(language = "en-US")))
            assertEquals(listOf(activity to "ru-RU"), platform.launched)
        }
    }

    private class Platform(var current: AndroidRecognitionServiceIdentity?, val activity: AndroidRecognitionInstallerActivity?) : AndroidRecognitionInstallerClient {
        var resolutions = 0
        var afterResolve: (() -> Unit)? = null
        var denied = false
        val launched = mutableListOf<Pair<AndroidRecognitionInstallerActivity, String>>()
        override fun identity(component: String) = current
        override fun resolve(packageName: String, language: String): AndroidRecognitionInstallerActivity? {
            resolutions++
            afterResolve?.invoke()
            return activity
        }
        override fun launch(activity: AndroidRecognitionInstallerActivity, language: String) {
            if (denied) throw SecurityException("Denied")
            launched += activity to language
        }
    }
}
