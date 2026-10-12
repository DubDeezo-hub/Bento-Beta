package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// ---------------------------------------------------------------------------------------------
// Look of the app: a deep navy theme for dark mode, a soft white one for light mode, and one accent
// color per console. The page colors are repeated in res/values/themes.xml and res/values-night/themes.xml
// so the status bar and the first frame match the screen.
// ---------------------------------------------------------------------------------------------

private val DARK_PAGE = Color(0xFF0E1320)
private val LIGHT_PAGE = Color(0xFFF4F6FB)

/**
 * One accent color a console can use: a bright shade for the dark theme and a deeper one for the light theme.
 * [nameRes] is the text of its name (an R.string value).
 */
class Accent(val nameRes: Int, val dark: Color, val light: Color)

val ACCENTS = listOf(
    Accent(R.string.color_blue, Color(0xFF4D94FF), Color(0xFF1463DE)),
    Accent(R.string.color_orange, Color(0xFFFFA24D), Color(0xFFA65300)),
    Accent(R.string.color_green, Color(0xFF4FD98E), Color(0xFF137946)),
    Accent(R.string.color_pink, Color(0xFFFF7AB6), Color(0xFFC21868)),
    Accent(R.string.color_purple, Color(0xFFB097FF), Color(0xFF6D4AE0)),
    Accent(R.string.color_teal, Color(0xFF3CD6C8), Color(0xFF0A776E))
)

/** The accent for a saved color number; a number outside the list wraps around instead of crashing. */
fun accentAt(index: Int): Accent = ACCENTS[index.mod(ACCENTS.size)]

/** The shade of a console's accent for dark or light mode. */
fun accentShade(index: Int, dark: Boolean): Color = if (dark) accentAt(index).dark else accentAt(index).light

/** The accent color of a console, in the shade that suits the current light or dark mode. */
@Composable
fun accentColor(index: Int): Color = accentShade(index, isSystemInDarkTheme())

/** The color scheme for one accent in light or dark mode. Text on the accent is dark in dark mode and white in light mode. */
fun appColorScheme(accent: Accent, dark: Boolean): ColorScheme =
    if (dark) {
        val card = Color(0xFF172033)
        darkColorScheme(
            primary = accent.dark,
            onPrimary = Color(0xFF07101F),
            primaryContainer = lerp(card, accent.dark, 0.30f),
            onPrimaryContainer = Color(0xFFEAF1FF),
            secondary = accent.dark,
            onSecondary = Color(0xFF07101F),
            secondaryContainer = lerp(card, accent.dark, 0.30f),
            onSecondaryContainer = Color(0xFFEAF1FF),
            background = DARK_PAGE,
            onBackground = Color(0xFFE8EEF9),
            surface = Color(0xFF131A2B),
            onSurface = Color(0xFFE8EEF9),
            surfaceVariant = Color(0xFF1F2A44),
            onSurfaceVariant = Color(0xFF9AA8C4),
            outline = Color(0xFF5B6E96),
            outlineVariant = Color(0xFF2A3858),
            error = Color(0xFFFF6B6B),
            onError = Color(0xFF3A0A0A),
            errorContainer = Color(0xFF4A1A1F),
            onErrorContainer = Color(0xFFFFDAD6),
            surfaceBright = Color(0xFF26324D),
            surfaceContainerLowest = Color(0xFF0B101C),
            surfaceContainerLow = Color(0xFF121A2B),
            surfaceContainer = card,
            surfaceContainerHigh = Color(0xFF1C2740),
            surfaceContainerHighest = Color(0xFF243150),
            surfaceDim = Color(0xFF0B101C)
        )
    } else {
        val card = Color(0xFFEEF1F8)
        lightColorScheme(
            primary = accent.light,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = lerp(card, accent.light, 0.20f),
            onPrimaryContainer = Color(0xFF0B1B3A),
            secondary = accent.light,
            onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = lerp(card, accent.light, 0.20f),
            onSecondaryContainer = Color(0xFF0B1B3A),
            background = LIGHT_PAGE,
            onBackground = Color(0xFF141B2D),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF141B2D),
            surfaceVariant = Color(0xFFE6EAF3),
            onSurfaceVariant = Color(0xFF55627C),
            outline = Color(0xFF7C8AA6),
            outlineVariant = Color(0xFFC8D0E1),
            error = Color(0xFFB3261E),
            onError = Color(0xFFFFFFFF),
            errorContainer = Color(0xFFFCE0DE),
            onErrorContainer = Color(0xFF410E0B),
            surfaceBright = Color(0xFFFFFFFF),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF7F9FD),
            surfaceContainer = card,
            surfaceContainerHigh = Color(0xFFE9EDF6),
            surfaceContainerHighest = Color(0xFFE2E7F2),
            surfaceDim = Color(0xFFD9DEEA)
        )
    }

/** Wraps the whole screen: dark or light by the phone's setting, tinted with the accent of the selected console. */
@Composable
fun AppTheme(accentIndex: Int, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appColorScheme(accentAt(accentIndex), isSystemInDarkTheme()), content = content)
}

/** Colors that mean the same thing on every screen: all good, careful, and bad. */
class StatusColors(val good: Color, val warn: Color, val bad: Color)

private val DARK_STATUS = StatusColors(Color(0xFF3DDC97), Color(0xFFF5B942), Color(0xFFFF6B6B))
private val LIGHT_STATUS = StatusColors(Color(0xFF137851), Color(0xFF945E00), Color(0xFFC0262D))

/** The status colors for dark or light mode. */
fun statusColorsFor(dark: Boolean): StatusColors = if (dark) DARK_STATUS else LIGHT_STATUS

@Composable
fun statusColors(): StatusColors = statusColorsFor(isSystemInDarkTheme())
