package fr.kristenjestin.mue.ui.profile

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.ui.components.MueAccentCard
import fr.kristenjestin.mue.ui.components.MueAnimatedNumber
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueValueChip
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlin.math.roundToInt

private val BarHeight = 8.dp
private val MarkerSize = 16.dp
private val MarkerRing = 3.dp

/** The four band fills of the prototype, left to right. Decoration only, never load-bearing. */
private val BandColors = listOf(
    Color(0xFF8D775D),
    Color(0xFFF6EDE1),
    Color(0xFF8D775D),
    Color(0xFF594B3D),
)

/**
 * The amber card of the Profile prototype.
 *
 * Only a [Bmi.Available] reaches here — a missing height or an empty history means no card at
 * all (PRD 15.1, 15.2) — and only a [Bmi.Classified] gets the named reference bar and its
 * marker (PRD FR-BMI-002). The case is decided by the domain layer and read here, never
 * re-derived from the age.
 */
@Composable
internal fun BmiCard(bmi: Bmi.Available, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val typography = MueTheme.typography
    val spacing = MueTheme.spacing
    val category = (bmi as? Bmi.Classified)?.category
    val value = formatBmiValue(bmi.value, rememberProfileLocale())

    MueAccentCard(modifier = modifier.testTag(ProfileTestTags.BMI_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MueText(
                    text = "Body mass index",
                    style = typography.label,
                    color = colors.onAccentSecondary,
                )
                MueAnimatedNumber(
                    text = value,
                    modifier = Modifier.padding(top = spacing.sm),
                    style = typography.metricDisplay,
                    color = colors.onAccent,
                    // The label above and the chip beside already name the number; a longer
                    // description here would only make TalkBack say them twice.
                    contentDescription = value,
                    durationMillis = MueMotion.BmiMillis,
                )
            }
            MueValueChip(
                // No category means no label: PRD FR-BMI-002 leaves only the value and the
                // caution text when the age does not allow one.
                text = category?.label ?: "Reference",
                container = colors.onAccent.copy(alpha = 0.10f),
                contentColor = colors.onAccent,
                modifier = Modifier.padding(start = spacing.md),
            )
        }

        category?.let {
            BmiReferenceBar(
                value = bmi.value,
                category = it,
                modifier = Modifier.padding(top = spacing.xl),
            )
        }

        MueText(
            text = BmiCalculator.DISCLAIMER,
            style = typography.caption,
            color = colors.onAccentSecondary,
            modifier = Modifier.padding(top = spacing.lg),
        )
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
                testTag = ProfileTestTags.BMI_REFERENCE_BAR
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
