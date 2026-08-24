package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/** Visible height of the chip. The touch area around it is grown to [MueMinTouchTarget]. */
private val ChipHeight = 32.dp

/**
 * An equipment tag that can be taken off again (FR-ACTIVITY-008).
 *
 * The whole chip removes, as in the prototype, rather than only the cross: a 12 dp glyph would
 * be an unreachable target, and a chip that both opens something and removes itself would need
 * two targets inside 32 dp. The cross is therefore decoration, and the accessible name says
 * what the tap does.
 *
 * The `×` is a character rather than a vector: that is what the prototype draws, and it keeps
 * the chip independent of the drawables landing in another chunk.
 */
@Composable
fun MueRemovableChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    removeContentDescription: String = "Remove $label",
) {
    val colors = MueTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "chipScale",
    )

    Box(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onRemove,
            )
            .semantics { contentDescription = removeContentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .height(ChipHeight)
                .clip(MueTheme.shapes.pill)
                .background(colors.accentSoft)
                .padding(horizontal = MueTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            MueText(label, MueTheme.typography.chip, color = colors.onAccentSoft, maxLines = 1)
            MueText("×", MueTheme.typography.chip, color = colors.accent.copy(alpha = 0.55f))
        }
    }
}

/** Wrapping row of chips. Equipment lists are short but their names are not. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MueChipRow(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        content = { content() },
    )
}

/**
 * The dashed full-width action of the prototypes — `Choose equipment`, `Add set`,
 * `Add another exercise`.
 *
 * It lives beside the chips because that is where it first appears, and it is shared rather
 * than restated per screen: three call sites drawing their own dashed outline is three chances
 * for the dash length to drift.
 *
 * It sizes to its label. Almost every use wants `Modifier.fillMaxWidth()`, but `Add set` sits
 * next to a much longer sibling and has to be allowed to stay narrow — see [MueSetListActions].
 */
@Composable
fun MueDashedAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.field
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = MueMotion.spec(MueMotion.PressMillis),
        label = "dashedActionScale",
    )
    val contentColor = if (enabled) colors.textSecondary else colors.textQuiet

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .drawBehind {
                val stroke = 1.dp.toPx()
                val dash = 4.dp.toPx()
                // Read off the shape rather than restated, so the dashes and the clip can
                // never round to two different corners.
                val radius = (shape as? RoundedCornerShape)?.topStart?.toPx(size, this) ?: 0f
                drawRoundRect(
                    color = colors.surfaceBorder,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
                    ),
                )
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = MueTheme.spacing.md, vertical = MueTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.invoke()
        MueText(label, MueTheme.typography.chip, color = contentColor, maxLines = 1)
    }
}

@Preview(name = "Chips and dashed actions", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun MueRemovableChipPreview() {
    MuePreviewHost(padding = 28) {
        MueSurfaceCard(shape = MueTheme.shapes.field) {
            MueText(
                "Equipment · optional",
                MueTheme.typography.label,
                color = MueTheme.colors.textTertiary,
                modifier = Modifier.padding(bottom = MueTheme.spacing.sm),
            )
            MueChipRow {
                MueRemovableChip("Yoga mat", onRemove = {})
                MueRemovableChip("Resistance bands", onRemove = {})
                MueRemovableChip("Kettlebell", onRemove = {})
            }
            MueDashedAction(
                label = "Add another equipment",
                onClick = {},
                icon = { MuePreviewIcon(MuePreviewGlyph.PLUS, size = 14.dp) },
                modifier = Modifier.fillMaxWidth().padding(top = MueTheme.spacing.md),
            )
        }
        MueDashedAction(
            label = "Add another exercise",
            onClick = {},
            icon = { MuePreviewIcon(MuePreviewGlyph.PLUS, size = 16.dp) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
