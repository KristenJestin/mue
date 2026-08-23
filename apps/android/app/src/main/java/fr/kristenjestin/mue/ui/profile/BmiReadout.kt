package fr.kristenjestin.mue.ui.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.categoryOrNull
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.bmiDescription
import fr.kristenjestin.mue.ui.components.formatBmiValue
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** Short form of the card's title, since the line already sits under the Height field. */
private const val PREFIX = "BMI"

private const val SEPARATOR = " · "

/** How high the readout hops when a save lands. */
private val HopHeight: Dp = 7.dp

/** Fraction of the hop spent rising; the rest is the fall back to the line. */
private const val HopRiseFraction = 0.35f

/**
 * The live BMI under the Height field: value, and the band label when the domain layer
 * allows one.
 *
 * Its job is to confirm that what is being typed took effect, so it is fed from the form and
 * moves with every keystroke, exactly as the full card on Progress does. [echoCount] is
 * bumped by a successful save: the readout then hops once, which is Profile's share of the
 * light leaving the button (PRD 13). With no BMI on screen there is no readout and the halo
 * carries the confirmation alone.
 */
@Composable
internal fun BmiReadout(bmi: Bmi.Available, echoCount: Int, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val value = formatBmiValue(bmi.value, rememberMueLocale())
    val category = bmi.categoryOrNull

    val hop = remember { Animatable(0f) }
    LaunchedEffect(echoCount) {
        if (echoCount > 0 && !reduceMotion) {
            hop.snapTo(0f)
            hop.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = MueMotion.SaveHopMillis
                    1f at (MueMotion.SaveHopMillis * HopRiseFraction).toInt() using MueMotion.Enter
                },
            )
        }
    }
    val hopPx = with(LocalDensity.current) { HopHeight.toPx() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        MueText(
            text = PREFIX + " " + value + (category?.let { SEPARATOR + it.label } ?: ""),
            style = MueTheme.typography.bodyStrong,
            color = colors.accent,
            maxLines = 1,
            modifier = Modifier
                .testTag(ProfileTestTags.BMI_READOUT)
                .graphicsLayer { translationY = -hopPx * hop.value }
                .semantics { contentDescription = bmiDescription(bmi, value) },
        )
        // PRD FR-BMI-002: the caution goes wherever a BMI value goes.
        MueText(
            text = BmiCalculator.DISCLAIMER,
            style = MueTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}
