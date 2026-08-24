package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The prototype's `h-24` plot area. */
private val DefaultBarAreaHeight: Dp = 96.dp

/** The prototype's `w-2.5` bar, centred in its rail. */
private val BarWidth: Dp = 10.dp

/**
 * FR-ACTIVITY-001: an active day never renders as nothing, so a ten-minute walk still reads
 * next to a two-hour ride.
 */
private val MinActiveBarHeight: Dp = 3.dp

/** One day of the weekly card. [value] is any additive quantity; the module passes seconds. */
@Immutable
data class MueWeekDay(
    val label: String,
    val value: Long,
    val accessibleText: String,
    val emphasised: Boolean = false,
    val testTag: String? = null,
)

/**
 * Height of each bar as a fraction of the plot area.
 *
 * The scale is relative to the week on screen — the longest day fills the area and the rest
 * follow in proportion (FR-ACTIVITY-001). It is deliberately not comparable across weeks: the
 * week's total is written out in full above the card, so the bars only have to say which day
 * was the heavy one.
 */
internal object MueWeekBarScale {

    const val DAYS: Int = 7

    /**
     * [minFraction] is the floor an *active* day is lifted to. A day with nothing in it keeps
     * its empty rail and returns `0f`, which is what distinguishes "short" from "none".
     */
    fun fractionsOf(values: List<Long>, minFraction: Float): List<Float> {
        val peak = values.maxOrNull() ?: 0L
        if (peak <= 0L) return List(values.size) { 0f }
        val floor = minFraction.coerceIn(0f, 1f)
        return values.map { value ->
            if (value <= 0L) 0f else (value.toFloat() / peak).coerceIn(floor, 1f)
        }
    }
}

/**
 * The seven-day visualisation of the weekly card.
 *
 * Every day keeps a visible rail whatever the week holds, so an empty week reads as a quiet
 * row of rails rather than as a broken card (PRD 13.2).
 *
 * The bars grow from their base **on first display only** (PRD 14.2). `rememberSaveable` is
 * what makes that true across a rotation as well as across a recomposition; a plain `remember`
 * would replay the growth every time the activity was recreated. Reduced motion skips the
 * growth entirely rather than shortening it — the bars are simply there.
 */
@Composable
fun MueWeekBars(
    days: List<MueWeekDay>,
    modifier: Modifier = Modifier,
    barAreaHeight: Dp = DefaultBarAreaHeight,
    contentDescription: String = "Activity duration by day",
) {
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val minFraction = MinActiveBarHeight / barAreaHeight
    val fractions = MueWeekBarScale.fractionsOf(days.map { it.value }, minFraction)

    var grown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { grown = true }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            val target = if (grown || reduceMotion) fractions.getOrElse(index) { 0f } else 0f
            val fraction by animateFloatAsState(
                targetValue = target,
                animationSpec = if (reduceMotion) {
                    snap()
                } else {
                    MueMotion.spec(
                        durationMillis = MueMotion.WeeklyBarGrowthMillis,
                        easing = MueMotion.Enter,
                        delayMillis = index * MueMotion.WeeklyBarStaggerMillis,
                    )
                },
                label = "weekBar",
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    // Outside the cleared node: a tag set on the same node it clears is not
                    // worth betting a test on.
                    .then(day.testTag?.let { Modifier.testTag(it) } ?: Modifier),
            ) {
                Column(
                    // The rail and the letter are one reading, not two.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { this.contentDescription = day.accessibleText },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barAreaHeight)
                            .clip(MueTheme.shapes.pill)
                            .background(colors.surface),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(BarWidth)
                                .height(barAreaHeight * fraction)
                                .clip(MueTheme.shapes.pill)
                                .background(if (day.emphasised) colors.accent else colors.textQuiet),
                        )
                    }
                    MueText(
                        text = day.label,
                        // PRD FR-ACTIVITY-001 asks for the accent *and* an emphasised label, so
                        // the current day survives a screen read with colour turned off.
                        style = if (day.emphasised) {
                            MueTheme.typography.chip
                        } else {
                            MueTheme.typography.micro
                        },
                        color = if (day.emphasised) colors.accent else colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Preview(name = "Week bars", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueWeekBarsPreview() {
    val letters = listOf("M", "T", "W", "T", "F", "S", "S")
    MuePreviewHost(padding = 28) {
        MueSurfaceCard {
            MueText("Your rhythm", MueTheme.typography.label, color = MueTheme.colors.textTertiary)
            MueText("3 sessions", MueTheme.typography.metricMedium)
            MueWeekBars(
                days = listOf(1920L, 4080L, 1080L, 5280L, 60L, 3240L, 0L).mapIndexed { i, v ->
                    MueWeekDay(
                        label = letters[i],
                        value = v,
                        accessibleText = "${letters[i]}, ${v / 60} minutes",
                        emphasised = i == 3,
                    )
                },
                modifier = Modifier.padding(top = MueTheme.spacing.xl),
            )
        }
        MueSurfaceCard {
            MueText("No activity this week.", MueTheme.typography.bodyStrong)
            MueWeekBars(
                days = letters.map { MueWeekDay(it, 0L, "$it, no activity") },
                modifier = Modifier.padding(top = MueTheme.spacing.xl),
            )
        }
    }
}
