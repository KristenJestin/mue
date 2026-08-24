package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlin.math.roundToInt

/** PRD 8: perceived effort runs 1 to 10, so the slider carries eight stops between its ends. */
private const val EffortMin = 1
private const val EffortMax = 10
private const val EffortSteps = EffortMax - EffortMin - 1

/** What the readout says before anything has been chosen. The field is optional (PRD 10.5). */
const val MueEffortUnsetLabel: String = "Not set"

/**
 * Perceived effort, 1 to 10.
 *
 * **Third documented Material exception**, alongside the date picker and the delete
 * confirmation of the base PRD's section 12.1, and recorded as such in PRD_ACTIVITIES 14 and in
 * the build contract's decision 5. Material's `Slider` is natively an accessible adjustable
 * control — it publishes a range, responds to the accessibility `setProgress` action and to
 * `page up` / `page down`, all of which a custom control would have to reimplement. A bespoke
 * ten-segment control would also put every segment under 40 dp on a 390 dp screen, which PRD 15
 * forbids outright. Only the skin is ours.
 *
 * The value is nullable because effort is optional and PRD 12 forbids showing an absent
 * optional value as a number: until the person moves the slider it reads [MueEffortUnsetLabel]
 * and the track stays quiet.
 */
@Composable
fun MueEffortSlider(
    value: Int?,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Perceived effort",
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    val chosen = value != null
    val readout = value?.let { "$it/$EffortMax" } ?: MueEffortUnsetLabel

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.surfaceBorder, shape)
            .padding(
                horizontal = MueTheme.spacing.lg,
                vertical = MueTheme.spacing.md,
            ),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            icon?.invoke()
            MueText(label, MueTheme.typography.label, color = colors.textTertiary, maxLines = 1)
        }

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = MueMinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            Slider(
                value = (value ?: EffortMin).toFloat(),
                onValueChange = { onValueChange(it.roundToInt().coerceIn(EffortMin, EffortMax)) },
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        contentDescription = label
                        stateDescription = if (chosen) {
                            "$value out of $EffortMax"
                        } else {
                            MueEffortUnsetLabel
                        }
                    },
                enabled = enabled,
                valueRange = EffortMin.toFloat()..EffortMax.toFloat(),
                steps = EffortSteps,
                colors = SliderDefaults.colors(
                    thumbColor = if (chosen) colors.accent else colors.textTertiary,
                    activeTrackColor = if (chosen) colors.accent else colors.surfaceStrong,
                    activeTickColor = colors.onAccent,
                    inactiveTrackColor = colors.surfaceStrong,
                    inactiveTickColor = colors.textQuiet,
                    disabledThumbColor = colors.textQuiet,
                    disabledActiveTrackColor = colors.surfaceStrong,
                    disabledInactiveTrackColor = colors.surfaceStrong,
                ),
            )
            MueText(
                text = readout,
                style = MueTheme.typography.bodyStrong,
                color = if (chosen) colors.accent else colors.textTertiary,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 56.dp),
            )
        }
    }
}

@Preview(name = "Effort slider", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueEffortSliderPreview() {
    MuePreviewHost(padding = 28) {
        MueEffortSlider(value = null, onValueChange = {})
        MueEffortSlider(
            value = 6,
            onValueChange = {},
            icon = { MuePreviewIcon(MuePreviewGlyph.DOT, size = 14.dp) },
        )
        MueEffortSlider(value = 10, onValueChange = {}, enabled = false)
    }
}
