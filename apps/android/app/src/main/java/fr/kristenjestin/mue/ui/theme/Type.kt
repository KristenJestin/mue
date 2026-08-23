package fr.kristenjestin.mue.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fr.kristenjestin.mue.R

/**
 * The embedded `sora.ttf` is a variable font exposing a single `wght` axis clamped to 100..800.
 * [FontWeight.Black] is registered anyway, mapped to the 800 instance, so a caller asking for
 * 900 gets the heaviest cut the file actually contains instead of a synthetic bold.
 */
@OptIn(ExperimentalTextApi::class)
private fun soraFont(weight: FontWeight, axisWeight: Int = weight.weight) = Font(
    resId = R.font.sora,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(axisWeight)),
)

val Sora = FontFamily(
    soraFont(FontWeight.Normal),
    soraFont(FontWeight.Medium),
    soraFont(FontWeight.SemiBold),
    soraFont(FontWeight.Bold),
    soraFont(FontWeight.ExtraBold),
    soraFont(FontWeight.Black, axisWeight = 800),
)

/** Lining, fixed-width figures so a rolling readout never reflows. */
private const val TabularFigures = "tnum"

@Immutable
data class MueTypography(
    /** `MUE`, top left of every screen. */
    val wordmark: TextStyle,
    /** Muted line above a screen title, e.g. `Hello Kris,`. */
    val eyebrow: TextStyle,
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    /** Small grey caption sitting above a value inside a field or a card. */
    val label: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val caption: TextStyle,
    val micro: TextStyle,
    /** Tiny uppercase wide-tracked hint, e.g. `SLIDE TO ADJUST`. */
    val hint: TextStyle,
    /** The hero weight readout on Entry. */
    val weightDisplay: TextStyle,
    /** The BMI readout on the amber Profile card. */
    val metricDisplay: TextStyle,
    val metricLarge: TextStyle,
    val metricMedium: TextStyle,
    /** Editable numeric value inside a field. */
    val fieldValue: TextStyle,
    val button: TextStyle,
    val chip: TextStyle,
    val tabLabel: TextStyle,
)

val MueTypeScale: MueTypography = MueTypography(
    wordmark = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Black,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.em,
    ),
    eyebrow = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    screenTitle = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.045).em,
    ),
    sectionTitle = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).em,
    ),
    label = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    body = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodyStrong = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    micro = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
    hint = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.em,
        textAlign = TextAlign.Center,
    ),
    /**
     * The hero readout's *ceiling*, not its guaranteed size.
     *
     * Since BR-003 gained a second decimal the widest reading is six glyphs, `250.00`, and on
     * a phone that no longer clears the `−` and `+` controls at this size. Entry measures the
     * widest reading against the room it actually has and scales this down when it must, so
     * the value is as large as the screen allows rather than as large as this line says.
     */
    weightDisplay = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Medium,
        fontSize = 72.sp,
        lineHeight = 74.sp,
        letterSpacing = (-0.06).em,
        fontFeatureSettings = TabularFigures,
    ),
    metricDisplay = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 50.sp,
        letterSpacing = (-0.055).em,
        fontFeatureSettings = TabularFigures,
    ),
    metricLarge = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.05).em,
        fontFeatureSettings = TabularFigures,
    ),
    metricMedium = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TabularFigures,
    ),
    fieldValue = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.03).em,
        fontFeatureSettings = TabularFigures,
    ),
    button = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.005.em,
    ),
    chip = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    tabLabel = TextStyle(
        fontFamily = Sora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

val LocalMueTypography = staticCompositionLocalOf { MueTypeScale }
