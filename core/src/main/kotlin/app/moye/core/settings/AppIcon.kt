package app.moye.core.settings

import app.moye.core.model.ReaderTheme

enum class AppIcon {
    DAY,
    NIGHT,
}

/** Star icon for paper and light reading; moon icon for night reading. */
fun appIconFor(theme: ReaderTheme): AppIcon = when (theme) {
    ReaderTheme.DARK -> AppIcon.NIGHT
    ReaderTheme.PAPER, ReaderTheme.LIGHT -> AppIcon.DAY
}
