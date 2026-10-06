package app.moye.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoPlayGateTest {
    @Test
    fun manualInterruptCancelsThePreviousWaitWithoutDisarming() {
        val running = AutoPlayGate().start()
        val ticket = running.token
        assertTrue(running.isCurrent(ticket))

        val interrupted = running.interrupt()
        assertTrue(interrupted.armed)
        assertFalse(interrupted.controlsOpen)
        assertTrue(interrupted.acceptsSchedule)
        assertFalse(interrupted.isCurrent(ticket))
        assertTrue(interrupted.isCurrent(interrupted.token))
    }

    @Test
    fun openingControlsPausesSchedulingAndClosingReschedules() {
        val running = AutoPlayGate().start()
        val oldTicket = running.token
        val blocked = running.showControls()
        assertTrue(blocked.armed)
        assertTrue(blocked.controlsOpen)
        assertFalse(blocked.acceptsSchedule)
        assertFalse(blocked.isCurrent(oldTicket))
        assertFalse(blocked.isCurrent(blocked.token))

        val resumed = blocked.hideControls()
        assertTrue(resumed.armed)
        assertFalse(resumed.controlsOpen)
        assertTrue(resumed.acceptsSchedule)
        assertFalse(resumed.isCurrent(blocked.token))
        assertTrue(resumed.isCurrent(resumed.token))
    }

    @Test
    fun pauseKeepsControlsFromRestartingAutoPlay() {
        val paused = AutoPlayGate().start().pause()
        assertFalse(paused.armed)
        val hidden = paused.hideControls()
        assertFalse(hidden.acceptsSchedule)
        assertEquals(paused.token, hidden.token)
    }
}
