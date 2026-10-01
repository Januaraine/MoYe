package app.moye.core.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingTimerTest {
    @Test
    fun countsOnlyWhileRunning() {
        val timer = ReadingTimer()
        timer.resume(1_000)
        assertEquals(500, timer.takeDelta(1_500))
        timer.pause(2_000)
        assertEquals(500, timer.takeDelta(2_000))
        assertEquals(0, timer.takeDelta(9_000))
        assertFalse(timer.isRunning)
        assertEquals(DurationParts(0, 0, true), DurationParts.from(30_000))
        assertEquals(DurationParts(1, 5, false), DurationParts.from(3_900_000))
    }

    @Test
    fun resumeDoesNotResetAccumulatedTime() {
        val timer = ReadingTimer()
        timer.resume(0)
        timer.pause(1_000)
        timer.resume(5_000)
        assertTrue(timer.isRunning)
        assertEquals(1_400, timer.elapsed(5_400))
    }
}
