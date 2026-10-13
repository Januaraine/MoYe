package app.moye.core.settings

import app.moye.core.model.ReaderTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    @Test
    fun nightThemeUsesTheMoonIcon() {
        assertEquals(AppIcon.NIGHT, appIconFor(ReaderTheme.DARK))
    }

    @Test
    fun paperAndLightUseTheStarIcon() {
        assertEquals(AppIcon.DAY, appIconFor(ReaderTheme.PAPER))
        assertEquals(AppIcon.DAY, appIconFor(ReaderTheme.LIGHT))
    }
}
