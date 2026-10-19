package com.example.offlinetranslator.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Wait for the intended window, then dispatch once through Android rather than an Espresso root cache. */
fun pressSystemBack(compose: ComposeTestRule, windowTag: String) {
    compose.waitUntil(5_000) {
        val root = compose.onAllNodesWithTag(windowTag)
            .fetchSemanticsNodes(atLeastOneRootRequired = false).singleOrNull()?.root as? ViewRootForTest
        var focused = false
        compose.runOnIdle { focused = root?.view?.hasWindowFocus() == true }
        focused
    }
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val downTime = SystemClock.uptimeMillis()
    for (action in KeyEvent.ACTION_DOWN..KeyEvent.ACTION_UP) {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD,
        )
        assertTrue("Android must finish dispatching Back action $action",
            automation.injectInputEvent(event, true))
    }
}
