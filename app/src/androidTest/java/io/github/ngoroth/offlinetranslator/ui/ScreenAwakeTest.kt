package io.github.ngoroth.offlinetranslator.ui

import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ngoroth.offlinetranslator.MainActivity
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test

/** Actual Activity/window assertions; real display timeout and explicit-lock evidence are separate. */
class ScreenAwakeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun startedWindowOwnsIndependentDemandsAcrossStopReturnAndDestruction() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        lateinit var owner: ScreenAwake.Owner
        lateinit var first: ScreenAwake.Demand
        lateinit var second: ScreenAwake.Demand
        try {
            scenario.onActivity { it.setContent {} }
            compose.waitForIdle()
            scenario.onActivity {
                activity = it
                assertFlag(it, false)
                owner = it.screenAwake.owner()
                first = owner.acquire()
                second = owner.acquire()
                assertFlag(it, true)
                first.close()
                first.close()
                assertFlag(it, true)
            }
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.onActivity { assertFlag(it, true) }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertFlag(it, false) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertFlag(it, true)
                second.close()
                assertFlag(it, false)
                owner.acquire()
                assertFlag(it, true)
            }
        } finally {
            scenario.close()
        }
        instrumentation.runOnMainSync {
            assertFlag(activity, false)
            owner.acquire()
            assertFlag(activity, false)
        }
    }

    @Test fun composedDemandSurvivesDialogAndReleasesOnDisposal() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent {} }
            compose.waitForIdle()
            val dialogOpen = mutableStateOf(true)
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        DisposableEffect(activity) {
                            val owner = activity.screenAwake.owner()
                            val demand = owner.acquire()
                            onDispose { demand.close(); owner.close() }
                        }
                        if (dialogOpen.value) {
                            AlertDialog(
                                onDismissRequest = { dialogOpen.value = false },
                                title = { Text("Screen demand") },
                                confirmButton = {
                                    TextButton(onClick = { dialogOpen.value = false }) { Text("Close") }
                                },
                            )
                        }
                    }
                }
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertFlag(it, true)
                dialogOpen.value = false
            }
            compose.waitForIdle()
            scenario.onActivity {
                assertFlag(it, true)
                it.setContent {}
            }
            compose.waitForIdle()
            scenario.onActivity { assertFlag(it, false) }
        }
    }

    private fun assertFlag(activity: MainActivity, expected: Boolean) {
        assertEquals(expected, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
