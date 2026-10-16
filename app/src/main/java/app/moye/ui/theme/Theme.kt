package app.moye.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.moye.core.model.ReaderTheme

private val Black = Color(0xFF111111)
private val White = Color(0xFFFFFFFF)
private val Ink = Color(0xFF161616)
private val PaperWhite = Color(0xFFFFFFFF)
private val PaperGray = Color(0xFFE6E6E6)
private val Mist = Color(0xFFF4F4F4)
private val Silver = Color(0xFFD0D0D0)
private val Ash = Color(0xFF8A8A8A)
private val Graphite = Color(0xFF5C5C5C)
private val Night = Color(0xFF111111)
private val NightRaised = Color(0xFF1C1C1C)
private val NightLine = Color(0xFF3A3A3A)
private val NightInk = Color(0xFFF2F2F2)
private val NightMuted = Color(0xFFA3A3A3)

private val DayColors = lightColorScheme(
    primary = Ink,
    onPrimary = White,
    primaryContainer = PaperGray,
    onPrimaryContainer = Ink,
    secondary = Graphite,
    onSecondary = White,
    secondaryContainer = PaperGray,
    onSecondaryContainer = Ink,
    tertiary = Graphite,
    onTertiary = White,
    tertiaryContainer = PaperGray,
    onTertiaryContainer = Ink,
    background = PaperWhite,
    onBackground = Ink,
    surface = Mist,
    onSurface = Ink,
    surfaceVariant = PaperGray,
    onSurfaceVariant = Graphite,
    surfaceTint = Color.Transparent,
    inverseSurface = Night,
    inverseOnSurface = NightInk,
    outline = Ash,
    outlineVariant = Silver,
    error = Ink,
    onError = White,
    errorContainer = PaperGray,
    onErrorContainer = Ink,
    scrim = Black,
    inversePrimary = Graphite,
    surfaceBright = PaperWhite,
    surfaceDim = PaperGray,
    surfaceContainerLowest = PaperWhite,
    surfaceContainerLow = Mist,
    surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = PaperGray,
    surfaceContainerHighest = Silver,
)

private val NightColors = darkColorScheme(
    primary = NightInk,
    onPrimary = Night,
    primaryContainer = NightLine,
    onPrimaryContainer = NightInk,
    secondary = NightMuted,
    onSecondary = Night,
    secondaryContainer = NightLine,
    onSecondaryContainer = NightInk,
    tertiary = NightMuted,
    onTertiary = Night,
    tertiaryContainer = NightLine,
    onTertiaryContainer = NightInk,
    background = Night,
    onBackground = NightInk,
    surface = NightRaised,
    onSurface = NightInk,
    surfaceVariant = NightLine,
    onSurfaceVariant = NightMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = PaperWhite,
    inverseOnSurface = Ink,
    outline = Ash,
    outlineVariant = NightLine,
    error = NightInk,
    onError = Night,
    errorContainer = NightLine,
    onErrorContainer = NightInk,
    scrim = Black,
    inversePrimary = NightMuted,
    surfaceBright = NightLine,
    surfaceDim = Night,
    surfaceContainerLowest = Night,
    surfaceContainerLow = NightRaised,
    surfaceContainer = Color(0xFF222222),
    surfaceContainerHigh = NightLine,
    surfaceContainerHighest = Color(0xFF4A4A4A),
)

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
)

/** Shelf, toolbars, and sheets. Independent of the page color. */
fun chromePalette(night: Boolean): ReaderPalette = if (night) {
    ReaderPalette(
        background = Night,
        text = NightInk,
        muted = NightMuted,
        accent = NightInk,
    )
} else {
    ReaderPalette(
        background = PaperWhite,
        text = Ink,
        muted = Graphite,
        accent = Ink,
    )
}

fun readerPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.PAPER -> ReaderPalette(
        background = PaperGray,
        text = Ink,
        muted = Graphite,
        accent = Ink,
    )
    ReaderTheme.LIGHT -> ReaderPalette(
        background = PaperWhite,
        text = Ink,
        muted = Graphite,
        accent = Ink,
    )
    ReaderTheme.DARK -> ReaderPalette(
        background = Night,
        text = NightInk,
        muted = NightMuted,
        accent = NightInk,
    )
}

@Composable
fun MoYeTheme(night: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.decorView.setBackgroundColor(if (night) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !night
            controller.isAppearanceLightNavigationBars = !night
        }
    }
    MaterialTheme(
        colorScheme = if (night) NightColors else DayColors,
        content = content,
    )
}
