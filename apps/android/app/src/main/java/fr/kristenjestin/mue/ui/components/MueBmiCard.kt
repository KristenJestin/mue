package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private val BarHeight = 8.dp
private val MarkerSize = 16.dp
private val MarkerRing = 3.dp

/** Title of the card, and the word TalkBack leads with wherever a BMI is shown. */
internal const val BMI_LABEL: String = "Body mass index"

/** Chip text when the age does not allow a band to be named (PRD FR-BMI-002). */
private const val REFERENCE_CHIP = "Reference"

/** Shown instead of a value when the period holds no measurement (PRD FR-PROGRESS-003). */
internal const val BMI_UNAVAILABLE: String = "—"

/** Handles for the Compose tests: the card's whole point is sometimes to be absent. */
internal object MueBmiCardTags {
    const val CARD: String = "bmi:card"
    const val REFERENCE_BAR: String = "bmi:referenceBar"
}

/** The four band fills of the prototype, left to right. Decoration only, never load-bearing. */
private val BandColors = listOf(
    Color(0xFF8D775D),
    Color(0xFFF6EDE1),
    Color(0xFF8D775D),
    Color(0xFF594B3D),
)

/**
 * The amber BMI card: the value, the four-band reference scale and the caution text.
 *
 * It lives here because Progress reads it and Profile echoes it. Progress is where the user
 * *reads* their state, so that screen gets the card in full; Profile, where the height is
 * entered, keeps the compact readout instead.
 *
 * Only a [Bmi.Classified] gets the named reference bar and its marker (PRD FR-BMI-002), and
 * that case is decided by the domain layer and read here, never re-derived from the age. A
 * [Bmi.Unavailable] still draws the card — on Progress an empty period must show `—` rather
 * than fall back on a value from outside it — but with no bar and no caution to attach it to.
 */
@Composable
fun MueBmiCard(bmi: Bmi, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val typography = MueTheme.typography
    val spacing = MueTheme.spacing
    val available = bmi as? Bmi.Available
    val category = (bmi as? Bmi.Classified)?.category
    val value = formatBmiValue(available?.value, rememberMueLocale())

    MueAccentCard(modifier = modifier.testTag(MueBmiCardTags.CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(
                    text = BMI_LABEL,
                    style = typography.label,
                    color = colors.onAccentSecondary,
                )
                MueAnimatedNumber(
                    text = value,
                    modifier = Modifier.padding(top = spacing.sm),
                    style = typography.metricDisplay,
                    color = colors.onAccent,
                    contentDescription = bmiDescription(bmi, value),
                    durationMillis = MueMotion.BmiMillis,
                )
            }
            MueValueChip(
                // No category means no label: PRD FR-BMI-002 leaves only the value and the
                // caution text when the age does not allow one.
                text = category?.label ?: REFERENCE_CHIP,
                container = colors.onAccent.copy(alpha = 0.10f),
                contentColor = colors.onAccent,
                modifier = Modifier.padding(start = spacing.md),
            )
        }

        if (bmi is Bmi.Classified) {
            BmiReferenceBar(
                value = bmi.value,
                category = bmi.category,
                modifier = Modifier.padding(top = spacing.xl),
            )
        }

        // PRD FR-BMI-002: the caution follows the value, so a card showing `—` carries none.
        if (available != null) {
            MueText(
                text = BmiCalculator.DISCLAIMER,
                style = typography.caption,
                color = colors.onAccentSecondary,
                modifier = Modifier.padding(top = spacing.lg),
            )
        }
    }
}

/**
 * Four equal bands with a marker sliding to the value's place inside its own band.
 *
 * The whole block is a picture of something already spoken by the value and its category
 * label, so it is collapsed into a single description rather than read band by band.
 */
@Composable
private fun BmiReferenceBar(value: Double, category: BmiCategory, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val activeBand = BmiReferenceScale.bandIndexOf(category)
    val fraction by animateFloatAsState(
        targetValue = BmiReferenceScale.markerFraction(value),
        animationSpec = MueMotion.spec(MueMotion.BmiMillis),
        label = "bmiMarker",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                testTag = MueBmiCardTags.REFERENCE_BAR
                contentDescription = "Reference scale, ${category.label}"
            },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(MarkerSize)) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(BarHeight)
                    .clip(MueTheme.shapes.pill),
            ) {
                BandColors.forEach { band ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(band))
                }
            }

            val travelPx = with(LocalDensity.current) { (maxWidth - MarkerSize).toPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // Read inside the layout lambda so the marker moves without recomposing.
                    .offset { IntOffset((travelPx * fraction).roundToInt(), 0) }
                    .size(MarkerSize)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .padding(MarkerRing)
                    .clip(CircleShape)
                    .background(colors.onAccent),
            )
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = MueTheme.spacing.sm)) {
            BmiReferenceScale.SHORT_LABELS.forEachIndexed { index, label ->
                MueText(
                    text = label,
                    style = MueTheme.typography.micro.copy(fontSize = 10.sp, lineHeight = 13.sp),
                    color = if (index == activeBand) colors.onAccent else colors.onAccentSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** What TalkBack reads for a BMI, wherever it is shown. */
internal fun bmiDescription(bmi: Bmi, value: String): String = when (bmi) {
    is Bmi.Unavailable -> "$BMI_LABEL unavailable"
    is Bmi.ValueOnly -> "$BMI_LABEL $value"
    is Bmi.Classified -> "$BMI_LABEL $value, ${bmi.category.label}"
}

/** The phone's primary locale, as a `java.time` / `java.text` locale (PRD BR-010). */
@Composable
internal fun rememberMueLocale(): Locale {
    val tag = ComposeLocale.current.toLanguageTag()
    return remember(tag) { Locale.forLanguageTag(tag) }
}

/** One decimal, exactly as PRD FR-BMI-001 requires, in the phone's numbering. */
internal fun formatBmiValue(value: Double?, locale: Locale): String =
    value?.let {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(it)
    } ?: BMI_UNAVAILABLE

@Preview(name = "BMI card", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueBmiCardPreview() {
    MuePreviewHost {
        MueBmiCard(Bmi.Classified(25.9, BmiCategory.OVERWEIGHT))
        MueBmiCard(Bmi.ValueOnly(23.0))
        MueBmiCard(Bmi.Unavailable)
    }
}
