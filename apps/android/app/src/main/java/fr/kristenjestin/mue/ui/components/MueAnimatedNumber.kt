package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** Plain holder, not snapshot state: reading it must not invalidate the composition. */
private class PreviousText(var value: String)

/**
 * Numeric readout whose digits roll vertically when the value changes, upwards when the
 * number grows. Separators and the optional [suffix] stay put.
 *
 * When the system animation scale is off the rolling is skipped entirely and the value is
 * swapped instantly, as required by PRD 14.
 */
@Composable
fun MueAnimatedNumber(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MueTheme.typography.weightDisplay,
    color: Color = MueTheme.contentColor,
    suffix: String? = null,
    suffixStyle: TextStyle = MueTheme.typography.body,
    suffixColor: Color = MueTheme.colors.textTertiary,
    contentDescription: String? = null,
    durationMillis: Int = MueMotion.NumberRollMillis,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
) {
    val reduceMotion = LocalReduceMotion.current
    val previous = remember { PreviousText(text) }
    val upwards = remember(text) {
        val growing = text.digitsAsLong() >= previous.value.digitsAsLong()
        previous.value = text
        growing
    }

    val spoken = contentDescription ?: listOfNotNull(text, suffix).joinToString(" ")

    // `transitionSpec` is not a composable scope, so the specs are resolved here.
    val offsetSpec = MueMotion.spec<IntOffset>(durationMillis)
    val fadeSpec = MueMotion.spec<Float>(durationMillis)
    val direction = if (upwards) 1 else -1

    Row(
        modifier = modifier.semantics(mergeDescendants = true) { this.contentDescription = spoken },
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.Bottom,
    ) {
        text.forEachIndexed { index, character ->
            if (reduceMotion || !character.isDigit()) {
                MueText(character.toString(), style, color = color)
            } else {
                key(index) {
                    AnimatedContent(
                        targetState = character,
                        modifier = Modifier.clipToBounds(),
                        transitionSpec = {
                            ContentTransform(
                                targetContentEnter = slideInVertically(offsetSpec) { height ->
                                    direction * height
                                } + fadeIn(fadeSpec),
                                initialContentExit = slideOutVertically(offsetSpec) { height ->
                                    -direction * height
                                } + fadeOut(fadeSpec),
                                sizeTransform = SizeTransform(clip = false),
                            )
                        },
                        label = "digit",
                    ) { rolled ->
                        MueText(rolled.toString(), style, color = color)
                    }
                }
            }
        }
        suffix?.let {
            MueText(
                text = it,
                style = suffixStyle,
                color = suffixColor,
                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
            )
        }
    }
}

private fun String.digitsAsLong(): Long = filter { it.isDigit() }.toLongOrNull() ?: 0L

@Preview(name = "Animated number", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueAnimatedNumberPreview() {
    MuePreviewHost {
        MueAnimatedNumber(text = "74.5", suffix = "kg")
        MueAnimatedNumber(
            text = "23.0",
            style = MueTheme.typography.metricLarge,
            suffixStyle = MueTheme.typography.caption,
        )
    }
}
