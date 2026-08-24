package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * A short closed list laid out as equal segments — `Indoor` / `Outdoor` / `Not set` of
 * FR-ACTIVITY-008.
 *
 * Not a Material `SegmentedButton`: that control draws its own outlined shape, its own
 * check-mark on the selected segment and its own ripple, none of which belong to this design
 * system. What Material provides that matters — the radio-group semantics — is one modifier.
 *
 * Sized by the number of options, so a fourth entry would narrow the segments rather than
 * scroll them; three is what the PRD asks for and what a 390 dp row holds.
 */
@Composable
fun <T> MueSegmentedChoice(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        options.forEach { option ->
            Segment(
                label = label(option),
                selected = option == selected,
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.small

    val container by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surfaceStrong,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "segmentContainer",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "segmentBorder",
    )
    // The second, colour-free carrier of the selection (PRD 15).
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 2.dp else 1.dp,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "segmentBorderWidth",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onAccentSoft else colors.textSecondary,
        animationSpec = MueMotion.spec(MueMotion.PresetChangeMillis),
        label = "segmentContent",
    )

    Box(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(container)
            .border(borderWidth, border, shape)
            .selectable(
                selected = selected,
                indication = null,
                interactionSource = null,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = MueTheme.spacing.sm, vertical = MueTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        MueText(
            text = label,
            style = MueTheme.typography.chip,
            color = content,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Segmented choice", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueSegmentedChoicePreview() {
    MuePreviewHost(padding = 28) {
        val environments = listOf("Indoor", "Outdoor", "Not set")
        MueSurfaceCard(shape = MueTheme.shapes.field) {
            MueText(
                "Environment",
                MueTheme.typography.label,
                color = MueTheme.colors.textTertiary,
                modifier = Modifier.padding(bottom = MueTheme.spacing.md),
            )
            MueSegmentedChoice(
                options = environments,
                selected = "Not set",
                onSelect = {},
                label = { it },
            )
        }
        MueSegmentedChoice(
            options = environments,
            selected = "Outdoor",
            onSelect = {},
            label = { it },
        )
    }
}
