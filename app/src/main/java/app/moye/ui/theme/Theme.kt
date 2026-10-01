package app.moye.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.moye.core.model.ReaderTheme

private val Ink = Color(0xFF1C1915)
private val Paper = Color(0xFFF4EFE4)
private val Cinnabar = Color(0xFF8C3A3A)
private val Moss = Color(0xFF3E5348)

private val ShelfColors = lightColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFFFFF8F4),
    secondary = Moss,
    onSecondary = Color(0xFFF4EFE4),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFF7F3EA),
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7E0D4),
    onSurfaceVariant = Color(0xFF5C564C),
    outline = Color(0xFFC9C0B2),
    error = Color(0xFF8C2F2F),
    onError = Color(0xFFFFF8F4),
)

data class ReaderPalette(
    val background: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
)

fun readerPalette(theme: ReaderTheme): ReaderPalette = when (theme) {
    ReaderTheme.PAPER -> ReaderPalette(
        background = Color(0xFFF3E6D0),
        text = Color(0xFF3A2C22),
        muted = Color(0xFF8A7362),
        accent = Color(0xFF8C3A3A),
    )
    ReaderTheme.LIGHT -> ReaderPalette(
        background = Color(0xFFFFFCF8),
        text = Color(0xFF1A1A1A),
        muted = Color(0xFF6E6A64),
        accent = Color(0xFF8C3A3A),
    )
    ReaderTheme.DARK -> ReaderPalette(
        background = Color(0xFF141210),
        text = Color(0xFFE7E1D6),
        muted = Color(0xFFA3988C),
        accent = Color(0xFFE0A090),
    )
}

@Composable
fun MoYeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ShelfColors, content = content)
}
