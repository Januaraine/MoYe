package app.moye.core.settings

import app.moye.core.model.Appearance

enum class AppIcon {
    DAY,
    NIGHT,
}

/** The launcher mark follows the home day/night switch, not the paper color. */
fun appIconFor(appearance: Appearance): AppIcon = when (appearance) {
    Appearance.NIGHT -> AppIcon.NIGHT
    Appearance.DAY -> AppIcon.DAY
}
