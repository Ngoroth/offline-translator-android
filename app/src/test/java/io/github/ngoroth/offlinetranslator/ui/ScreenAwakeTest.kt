package io.github.ngoroth.offlinetranslator.ui

import org.junit.Assert.*
import org.junit.Test

class ScreenAwakeTest {
    @Test fun independentDemandsAndRepeatedReleaseCannotClearAnotherOwner() {
        val applied = mutableListOf<Boolean>()
        val screen = ScreenAwake(applied::add)
        screen.setStarted(true)
        val first = screen.owner().acquire()
        val second = screen.owner().acquire()
        first.close()
        first.close()
        assertTrue(screen.keepScreenOn)
        second.close()
        assertFalse(screen.keepScreenOn)
        assertEquals(listOf(true, false), applied)
    }

    @Test fun stoppedWindowReappliesOnlyStillCurrentDemandsAndDestructionIsTerminal() {
        val screen = ScreenAwake {}
        var current = true
        val owner = screen.owner { current }
        owner.acquire()
        assertFalse(screen.keepScreenOn)
        screen.setStarted(true)
        assertTrue(screen.keepScreenOn)
        screen.setStarted(false)
        assertFalse(screen.keepScreenOn)
        current = false
        screen.setStarted(true)
        assertFalse(screen.keepScreenOn)
        current = true
        screen.refresh()
        assertFalse("Revoked demands must not return", screen.keepScreenOn)
        owner.acquire()
        assertTrue(screen.keepScreenOn)
        screen.close()
        screen.setStarted(true)
        screen.owner().acquire()
        assertFalse(screen.keepScreenOn)
    }

    @Test fun disposedAndStaleOwnersCannotAcquireOrReleaseSuccessorWork() {
        val screen = ScreenAwake {}
        screen.setStarted(true)
        var current = true
        val stale = screen.owner { current }
        val lateRelease = stale.acquire()
        current = false
        screen.refresh()
        assertFalse(screen.keepScreenOn)
        stale.acquire()
        assertFalse(screen.keepScreenOn)
        stale.close()
        val successor = screen.owner()
        val live = successor.acquire()
        lateRelease.close()
        stale.acquire()
        assertTrue(screen.keepScreenOn)
        successor.close()
        live.close()
        assertFalse(screen.keepScreenOn)
    }

    @Test fun foregroundRevocationMakesLateFinallyHarmlessToNewWork() {
        val screen = ScreenAwake {}
        screen.setStarted(true)
        val owner = screen.owner()
        val cancelled = owner.acquire()
        owner.clear()
        assertFalse(screen.keepScreenOn)
        val resumed = owner.acquire()
        cancelled.close()
        assertTrue(screen.keepScreenOn)
        resumed.close()
        assertFalse(screen.keepScreenOn)
    }
}
