package app.moye.core.input

import kotlin.test.Test
import kotlin.test.assertEquals

class ReadingZonesTest {
    @Test
    fun centerOpensControlsAndSidesDoNot() {
        assertEquals(ReadingZone.PREVIOUS_PAGE, ReadingZones.fromX(0.05f))
        assertEquals(ReadingZone.CONTROLS, ReadingZones.fromX(0.5f))
        assertEquals(ReadingZone.ADVANCE, ReadingZones.fromX(0.9f))
        assertEquals(ReadingZone.CONTROLS, ReadingZones.fromX(ReadingZones.LEFT_EDGE))
        assertEquals(ReadingZone.CONTROLS, ReadingZones.fromX(ReadingZones.RIGHT_EDGE))
    }
}
