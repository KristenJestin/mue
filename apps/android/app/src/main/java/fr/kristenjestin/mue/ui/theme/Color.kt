package fr.kristenjestin.mue.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic palette of the app. Mue ships a single dark theme, so there is exactly one
 * instance of this class: the roles below are the vocabulary, not a themable surface.
 *
 * Surfaces, borders and text tiers are deliberately kept translucent white so the amber
 * glow painted behind the content shows through them, as in the approved prototypes.
 */
@Immutable
data class MueColors(
    /** Page background, behind everything. */
    val canvas: Color,
    /** Opaque lift used by the bottom bar and the bottom sheet. */
    val canvasElevated: Color,
    /** Default card / field fill. */
    val surface: Color,
    /** Card fill for pressed, focused or emphasised containers. */
    val surfaceStrong: Color,
    /** Resting container outline. */
    val surfaceBorder: Color,
    /** Container outline while focused. */
    val surfaceBorderFocused: Color,
    /** Separators and 1 dp rules. */
    val hairline: Color,
    /** Brand amber. */
    val accent: Color,
    /** Muted amber container, for value chips on the dark canvas. */
    val accentSoft: Color,
    /** Ink used on top of [accent]. */
    val onAccent: Color,
    /** Supporting ink on top of [accent]; still 4.6:1 against the amber. */
    val onAccentSecondary: Color,
    /** Content colour on top of [accentSoft]. */
    val onAccentSoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Decoration only — ruler ticks, chart grid, inactive strokes. Never text. */
    val textQuiet: Color,
    val error: Color,
    val scrim: Color,
    /** Source colour of the radial glow at the top of every screen. */
    val glow: Color,
)

/**
 * Contrast ratios against [MueColors.canvas] (#101012):
 * accent 10.3:1 · textPrimary 19.0:1 · textSecondary 6.2:1 · textTertiary 5.0:1 · error 7.1:1.
 * [MueColors.onAccent] reaches 9.7:1 on the amber and [MueColors.onAccentSecondary] 4.6:1.
 */
val MueDarkColors: MueColors = MueColors(
    canvas = Color(0xFF101012),
    canvasElevated = Color(0xFF17171B),
    surface = Color(0x0AFFFFFF),
    surfaceStrong = Color(0x12FFFFFF),
    surfaceBorder = Color(0x1FFFFFFF),
    surfaceBorderFocused = Color(0x61FFFFFF),
    hairline = Color(0x14FFFFFF),
    accent = Color(0xFFEFB45F),
    accentSoft = Color(0xFF33291E),
    onAccent = Color(0xFF1D160D),
    onAccentSecondary = Color(0xFF604826),
    onAccentSoft = Color(0xFFEFB45F),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0x8CFFFFFF),
    textTertiary = Color(0x7AFFFFFF),
    textQuiet = Color(0x4DFFFFFF),
    error = Color(0xFFE8836A),
    scrim = Color(0xA6000000),
    glow = Color(0xFFEFB45F),
)

val LocalMueColors = staticCompositionLocalOf { MueDarkColors }

/**
 * Content colour of the enclosing container. Amber containers publish [MueColors.onAccent]
 * here so nested components do not have to know what they are sitting on.
 */
val LocalMueContentColor = staticCompositionLocalOf { MueDarkColors.textPrimary }
