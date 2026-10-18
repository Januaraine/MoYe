package app.moye.core.settings

import app.moye.core.model.Appearance
import app.moye.core.model.ReaderTheme
import app.moye.core.model.displayedPaperTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    @Test
    fun nightAppearanceUsesTheMoonAndForcesBlackPaper() {
        assertEquals(AppIcon.NIGHT, appIconFor(Appearance.NIGHT))
        assertEquals(ReaderTheme.DARK, displayedPaperTheme(Appearance.NIGHT, ReaderTheme.LIGHT))
        assertEquals(ReaderTheme.DARK, displayedPaperTheme(Appearance.NIGHT, ReaderTheme.PAPER))
        assertEquals(ReaderTheme.DARK, displayedPaperTheme(Appearance.NIGHT, ReaderTheme.DARK))
    }

    @Test
    fun dayAppearanceKeepsTheStarAndTheChosenPaper() {
        assertEquals(AppIcon.DAY, appIconFor(Appearance.DAY))
        assertEquals(ReaderTheme.DARK, displayedPaperTheme(Appearance.DAY, ReaderTheme.DARK))
        assertEquals(ReaderTheme.LIGHT, displayedPaperTheme(Appearance.DAY, ReaderTheme.LIGHT))
        assertEquals(ReaderTheme.PAPER, displayedPaperTheme(Appearance.DAY, ReaderTheme.PAPER))
    }
}
