package com.example.pssender

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// Look of the app: a deep navy theme for dark mode (or pure black, for OLED screens), a soft white one for light mode,
// and one accent color per console. The text can be made larger than the phone's own size. The page colors are repeated in res/values/themes.xml and res/values-night/themes.xml
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
fun accentColor(index: Int): Color = accentShade(index, true)

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

/** How the app looks beyond the phone's light or dark setting: pure black in dark mode, and a larger text size. */
object AppLook {
    /** The text sizes on offer, as a factor on the phone's own text size. */
    val SCALES = listOf(1.0f, 1.15f, 1.3f)

    var black by mutableStateOf(false)
        private set
    var textScale by mutableStateOf(1.0f)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        black = prefs(ctx).getBoolean("look_black", false)
        textScale = prefs(ctx).getFloat("look_text", 1.0f).takeIf { it in SCALES } ?: 1.0f
    }

    fun setBlack(ctx: Context, on: Boolean) {
        black = on
        prefs(ctx).edit().putBoolean("look_black", on).apply()
    }

    fun setTextScale(ctx: Context, scale: Float) {
        textScale = scale
        prefs(ctx).edit().putFloat("look_text", scale).apply()
    }
}

/** [scheme] with black pages and near-black cards, for OLED screens (dark mode only). */
fun blackScheme(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0C),
    surfaceContainer = Color(0xFF111114),
    surfaceContainerHigh = Color(0xFF18181C),
    surfaceContainerHighest = Color(0xFF202026),
    surfaceVariant = Color(0xFF1A1A1F),
    surfaceBright = Color(0xFF26262C)
)

// ---------------------------------------------------------------------------------------------
// The look: blood red and black. Black pages with a deep red glow coming in from the top, red-tinted black cards,
// one blood-red accent (red fading into black on the main buttons), white text on red. Everything is round: buttons
// and chips are pills, icons sit in circles, cards have large round corners. Dark only.
// ---------------------------------------------------------------------------------------------

/** The blood red the app is built around, and the brighter red used for small lit things (dots, labels). */
val BLOOD = Color(0xFFB3001B)
val BLOOD_BRIGHT = Color(0xFFFF4D5E)
val BLOOD_LIGHT = Color(0xFFE0182E)
val BLOOD_DARK = Color(0xFF3A0008)
private val PAGE = Color(0xFF050304)

/** Red into black: the main buttons, the selected tab, the console's badge. */
val bloodGradient: Brush get() = Brush.linearGradient(listOf(BLOOD_LIGHT, BLOOD, BLOOD_DARK))

/** Behind every screen: a deep red glow at the top fading into black (plain black with the black look on). */
fun pageBackground(black: Boolean): Brush =
    if (black) Brush.verticalGradient(listOf(Color.Black, Color.Black))
    else Brush.radialGradient(
        colors = listOf(Color(0xFF4A0410), Color(0xFF1A0206), PAGE),
        center = Offset(540f, -120f),
        radius = 1500f
    )

/** The colors: red-tinted blacks for everything that sits on the page, blood red as the one accent. */
fun bloodScheme(): ColorScheme = darkColorScheme(
    primary = BLOOD,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0610),
    onPrimaryContainer = Color(0xFFFFDADC),
    secondary = BLOOD,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A0610),
    onSecondaryContainer = Color(0xFFFFDADC),
    tertiary = BLOOD_BRIGHT,
    onTertiary = Color(0xFF1A0206),
    background = PAGE,
    onBackground = Color(0xFFF3ECEC),
    surface = Color(0xFF0B0506),
    onSurface = Color(0xFFF3ECEC),
    surfaceVariant = Color(0xFF1F0E12),
    onSurfaceVariant = Color(0xFFB3A2A5),
    outline = Color(0xFF6A4A50),
    outlineVariant = Color(0xFF3A1A1F),
    error = Color(0xFFFF8A3D),
    onError = Color(0xFF2A1000),
    errorContainer = Color(0xFF3A1A08),
    onErrorContainer = Color(0xFFFFDCC4),
    surfaceBright = Color(0xFF2A1217),
    surfaceContainerLowest = PAGE,
    surfaceContainerLow = Color(0xFF110709),
    surfaceContainer = Color(0xFF170A0D),
    surfaceContainerHigh = Color(0xFF1F0E12),
    surfaceContainerHighest = Color(0xFF2A1217),
    surfaceDim = PAGE
)

/** Round everything: text fields and small cards softly, buttons and chips fully, dialogs and big cards generously. */
val ROUND_SHAPES = Shapes(
    extraSmall = RoundedCornerShape(16.dp),
    small = RoundedCornerShape(50),
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Inter (SIL Open Font License, licenses/Inter-OFL.txt), bundled so the app looks the same on every phone. Scripts it
 * doesn't cover (Arabic, Hindi, Thai, Japanese, ...) fall back to the phone's own fonts by themselves. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold)
)

/** Material's text styles, all in Inter; headings a little heavier. */
val APP_TYPOGRAPHY: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold),
        displayMedium = t.displayMedium.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold),
        displaySmall = t.displaySmall.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold),
        headlineLarge = t.headlineLarge.copy(fontFamily = Inter, fontWeight = FontWeight.ExtraBold),
        headlineMedium = t.headlineMedium.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontFamily = Inter, fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(fontFamily = Inter),
        bodyMedium = t.bodyMedium.copy(fontFamily = Inter),
        bodySmall = t.bodySmall.copy(fontFamily = Inter),
        labelLarge = t.labelLarge.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontFamily = Inter, fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontFamily = Inter, fontWeight = FontWeight.Medium)
    )
}

/** Wraps the whole screen in the blood-red look (the console's own color stays on its badge). */
@Composable
fun AppTheme(@Suppress("UNUSED_PARAMETER") accentIndex: Int, content: @Composable () -> Unit) {
    val scheme = bloodScheme().let { if (AppLook.black) blackScheme(it) else it }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * AppLook.textScale)) {
        MaterialTheme(colorScheme = scheme, shapes = ROUND_SHAPES, typography = APP_TYPOGRAPHY, content = content)
    }
}

/** Colors that mean the same thing on every screen: all good, careful, and bad. */
class StatusColors(val good: Color, val warn: Color, val bad: Color)

// Lit red for "answers", amber for "careful", orange for "wrong": told apart by lightness as well as hue
private val DARK_STATUS = StatusColors(Color(0xFFFF4D5E), Color(0xFFF5B942), Color(0xFFFF8A3D))
private val LIGHT_STATUS = StatusColors(Color(0xFF137851), Color(0xFF945E00), Color(0xFFC0262D))

/** The status colors for dark or light mode. */
fun statusColorsFor(dark: Boolean): StatusColors = if (dark) DARK_STATUS else LIGHT_STATUS

/** The app is dark only now, so these are always the dark ones. */
@Composable
fun statusColors(): StatusColors = statusColorsFor(true)
