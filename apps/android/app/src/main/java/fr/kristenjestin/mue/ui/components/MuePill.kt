package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

private val PillHeight = 40.dp

/**
 * Period selector chip. The visible pill stays at [PillHeight] to keep the prototype's low
 * density, while the touch area is padded out to the Android minimum around it.
 */
@Composable
fun MuePeriodPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val container by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceStrong,
        animationSpec = MueMotion.spec(MueMotion.PeriodChangeMillis),
        label = "pillContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onAccent else colors.textTertiary,
        animationSpec = MueMotion.spec(MueMotion.PeriodChangeMillis),
        label = "pillContent",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "pillScale",
    )

    Box(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .height(PillHeight)
                .clip(MueTheme.shapes.pill)
                .background(container)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            MueText(label, MueTheme.typography.chip, color = content, maxLines = 1)
        }
    }
}

/** Outlined chip sitting on the right of a screen header, e.g. `Today`. */
@Composable
fun MueHeaderChip(text: String, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    Box(
        modifier = modifier
            .clip(MueTheme.shapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.surfaceBorder, MueTheme.shapes.pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MueText(text, MueTheme.typography.chip, color = colors.textSecondary, maxLines = 1)
    }
}

/** Filled chip carrying a value, e.g. the `−1.1 kg` badge on the Progress chart card. */
@Composable
fun MueValueChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MueTheme.colors.accentSoft,
    contentColor: Color = MueTheme.colors.onAccentSoft,
) {
    Box(
        modifier = modifier
            .clip(MueTheme.shapes.pill)
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        MueText(text, MueTheme.typography.chip, color = contentColor, maxLines = 1)
    }
}

@Preview(name = "Pills", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MuePillPreview() {
    MuePreviewHost {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MuePeriodPill("7 days", selected = false, onClick = {})
            MuePeriodPill("30 days", selected = true, onClick = {})
            MuePeriodPill("All", selected = false, onClick = {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MueHeaderChip("Today")
            MueHeaderChip("Health profile")
            MueValueChip("−1.1 kg")
        }
    }
}
