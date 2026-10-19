package com.example.offlinetranslator

import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.offlinetranslator.core.LanguageCatalog
import com.example.offlinetranslator.ui.InterfaceLanguage

class MainActivity : ComponentActivity() {
    private var initialLocaleReady by mutableStateOf(false)
    private var localeConfigurationReady = false
    private val localeDrawGate = ViewTreeObserver.OnPreDrawListener { localeConfigurationReady }

    override fun onCreate(savedInstanceState: Bundle?) {
        InterfaceLanguage.ensureDefault(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateLocaleReadiness()
        window.decorView.viewTreeObserver.addOnPreDrawListener(localeDrawGate)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                if (initialLocaleReady) {
                    // This branch latches once: subsequent locale changes never unmount route state.
                    TranslatorHome()
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }

    override fun onStart() {
        InterfaceLanguage.ensureDefault(this)
        updateLocaleReadiness()
        super.onStart()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        InterfaceLanguage.ensureDefault(this)
        updateLocaleReadiness()
        window.decorView.invalidate()
    }

    override fun onDestroy() {
        window.decorView.viewTreeObserver.removeOnPreDrawListener(localeDrawGate)
        super.onDestroy()
    }

    private fun updateLocaleReadiness() {
        val selected = InterfaceLanguage.current(this)
        val effective = resources.configuration.locales[0]
        val effectiveLanguage = if (effective.language == "in") "id" else effective.language
        localeConfigurationReady = effectiveLanguage == selected.value &&
            (selected != LanguageCatalog.CHINESE ||
                (effective.script != "Hant" && effective.country != "TW" &&
                    effective.country != "HK" && effective.country != "MO"))
        if (localeConfigurationReady) initialLocaleReady = true
        // Await configuration delivery without blocking/polling or drawing a stale localized surface.
        // The draw gate also covers foreground re-entry, preserving the mounted composition.
    }
}
