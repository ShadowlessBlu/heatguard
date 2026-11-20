package gh.knust.echowear.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp

// RAIL palette (option A + C, November 2026): deep green for actions and
// headings, mint for "all good", a pale green-grey ground with white cards.
private val RailGreen = Color(0xFF15524D)
private val Mint = Color(0xFF2DBD8C)
private val TealDark = Color(0xFF3FC2A5)
private val Amber = Color(0xFFB26A00)
private val Danger = Color(0xFFB3261E)
private val DangerDark = Color(0xFFFFB4AB)

private val LightColors = lightColorScheme(
    primary = RailGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E8E5),
    onPrimaryContainer = Color(0xFF0B2B28),
    secondary = Amber,
    tertiary = Mint,
    onTertiary = Color(0xFF0B2B28),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF10201D),
    surface = Color.White,
    onSurface = Color(0xFF10201D),
    surfaceVariant = Color(0xFFE4ECE9),
    // #4A5B57 on white is 6.9:1, so muted text still reads for older eyes.
    onSurfaceVariant = Color(0xFF4A5B57),
    outline = Color(0xFFC9D8D4),
    outlineVariant = Color(0xFFDFE8E5),
    error = Danger,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00382D),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFFB8EFE0),
    secondary = Color(0xFFF5BE62),
    error = DangerDark,
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/**
 * High-contrast schemes, ported from the dashboard's accessibility toggle.
 *
 * These are not "dark mode with more saturation". Material's default
 * containers sit around 3:1 against their surface, which is fine for a
 * 25-year-old developer and not fine for the person this device is actually
 * for. Pure black on white (and white on black) clears 15:1, and the accent
 * colours are darkened or lightened until they clear 7:1 against the surface
 * they are drawn on, so nothing conveys state by a hue an older eye may not
 * separate.
 */
private val HighContrastLight = lightColorScheme(
    primary = Color(0xFF004D3D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F5EC),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF6B3D00),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE8E8E8),
    onSurfaceVariant = Color(0xFF1A1A1A),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFF8C0009),
    onError = Color.White,
    errorContainer = Color(0xFFFFD9D6),
    onErrorContainer = Color.Black,
)

private val HighContrastDark = darkColorScheme(
    primary = Color(0xFF7BF2D4),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF00382D),
    onPrimaryContainer = Color(0xFFD7F5EC),
    secondary = Color(0xFFFFD08A),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFFEAEAEA),
    outline = Color(0xFFBFBFBF),
    error = Color(0xFFFFB4AB),
    onError = Color.Black,
    errorContainer = Color(0xFF6B0007),
    onErrorContainer = Color(0xFFFFD9D6),
)

/** Colours that carry meaning rather than brand, so every screen agrees. */
data class StatusPalette(
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val idle: Color,
    val chart: List<Color>,
)

val LocalStatusPalette = staticCompositionLocalOf {
    StatusPalette(
        ok = Color(0xFF1B8A3A),
        warn = Color(0xFFB26A00),
        bad = Color(0xFFB3261E),
        idle = Color(0xFF6B7280),
        chart = listOf(
            Color(0xFF0E7C66), Color(0xFFB26A00), Color(0xFF3B6FB6),
            Color(0xFF8E44AD), Color(0xFFB3261E),
        ),
    )
}

private val HighContrastStatus = StatusPalette(
    ok = Color(0xFF00631F),
    warn = Color(0xFF7A4500),
    bad = Color(0xFF8C0009),
    idle = Color(0xFF3A3A3A),
    chart = listOf(
        Color(0xFF00463A), Color(0xFF7A4500), Color(0xFF1F3F7A),
        Color(0xFF5B2A72), Color(0xFF8C0009),
    ),
)

private val HighContrastStatusDark = StatusPalette(
    ok = Color(0xFF6EE79A),
    warn = Color(0xFFFFD08A),
    bad = Color(0xFFFFB4AB),
    idle = Color(0xFFBFBFBF),
    chart = listOf(
        Color(0xFF7BF2D4), Color(0xFFFFD08A), Color(0xFF9EC3FF),
        Color(0xFFE0B3FF), Color(0xFFFFB4AB),
    ),
)

/** Whether animations should be suppressed; read by the live charts. */
val LocalReduceMotion = staticCompositionLocalOf { false }

private fun TextStyle.scaled(factor: Float): TextStyle =
    if (fontSize.isSpecified) copy(fontSize = fontSize * factor) else this

private fun scaledTypography(base: Typography, factor: Float): Typography =
    if (factor == 1f) base else Typography(
        displayLarge = base.displayLarge.scaled(factor),
        displayMedium = base.displayMedium.scaled(factor),
        displaySmall = base.displaySmall.scaled(factor),
        headlineLarge = base.headlineLarge.scaled(factor),
        headlineMedium = base.headlineMedium.scaled(factor),
        headlineSmall = base.headlineSmall.scaled(factor),
        titleLarge = base.titleLarge.scaled(factor),
        titleMedium = base.titleMedium.scaled(factor),
        titleSmall = base.titleSmall.scaled(factor),
        bodyLarge = base.bodyLarge.scaled(factor),
        bodyMedium = base.bodyMedium.scaled(factor),
        bodySmall = base.bodySmall.scaled(factor),
        labelLarge = base.labelLarge.scaled(factor),
        labelMedium = base.labelMedium.scaled(factor),
        labelSmall = base.labelSmall.scaled(factor),
    )

@Composable
fun EchoWearTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You is nice on a Galaxy A52 running Android 12+, but the fall
    // alert relies on red reading as danger, so dynamic colour is opt-in only
    // for the non-alert surfaces - and is ignored outright in high contrast,
    // where the wallpaper must not be allowed to pick the palette.
    dynamicColor: Boolean = false,
    highContrast: Boolean = false,
    largeText: Boolean = false,
    textScale: Float = 1f,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        highContrast && darkTheme -> HighContrastDark
        highContrast -> HighContrastLight
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    // The large-text switch and the slider compose: the switch is the one-tap
    // version of dragging the slider to 1.3, and someone who has already
    // chosen 1.6 should not be pushed back down by flipping it on.
    val factor = maxOf(textScale, if (largeText) 1.3f else 1f).coerceIn(0.85f, 1.8f)

    val palette = when {
        highContrast && darkTheme -> HighContrastStatusDark
        highContrast -> HighContrastStatus
        else -> LocalStatusPalette.current
    }

    CompositionLocalProvider(
        LocalStatusPalette provides palette,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = scaledTypography(MaterialTheme.typography, factor),
            content = content,
        )
    }
}

/** Minimum touch target for the wearer-facing controls. Material's 48 dp is
 *  the floor for a steady hand; an emergency control pressed by someone who
 *  has just fallen gets more. */
val MinTouchTarget: Dp = 56.dp
