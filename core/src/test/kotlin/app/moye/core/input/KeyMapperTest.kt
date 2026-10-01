package app.moye.core.input

import app.moye.core.model.ReaderCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMapperTest {
    @Test
    fun mapsPageTurnerKeys() {
        assertEquals(ReaderCommand.PREVIOUS, KeyMapper.map(92))
        assertEquals(ReaderCommand.NEXT, KeyMapper.map(93))
        assertEquals(ReaderCommand.PREVIOUS, KeyMapper.map(24))
        assertEquals(ReaderCommand.NEXT, KeyMapper.map(25))
        assertEquals(ReaderCommand.TOGGLE_PLAYBACK, KeyMapper.map(85))
        assertEquals(ReaderCommand.TOGGLE_PLAYBACK, KeyMapper.map(62))
    }

    @Test
    fun ignoresUnmappedKeys() {
        assertNull(KeyMapper.map(4))
        assertNull(KeyMapper.map(111))
        assertNull(KeyMapper.map(0))
    }
}
