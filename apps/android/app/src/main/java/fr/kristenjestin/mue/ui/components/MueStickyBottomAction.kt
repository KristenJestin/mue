package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueTheme

/** Length of the ramp the scrolling content dissolves into, above the action. */
private val ScrimHeight: Dp = 32.dp

/**
 * The pinned save action of `Log activity` and `Strength session`.
 *
 * It carries no window inset of its own. Contract decision 2 keeps the tab bar visible on both
 * screens, and the bar — which sits outside the navigation host — already owns the navigation
 * bar and the IME. Padding here as well would lift the action a whole bar too far.
 *
 * The ramp above it is painted rather than composed, so a row of content scrolling underneath
 * dissolves into the canvas instead of being cut across.
 */
@Composable
fun MueStickyBottomAction(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canvas = MueTheme.colors.canvas
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val ramp = ScrimHeight.toPx().coerceAtMost(size.height)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(canvas.copy(alpha = 0f), canvas),
                        startY = 0f,
                        endY = ramp,
                    ),
                    size = Size(size.width, ramp),
                )
                drawRect(
                    color = canvas,
                    topLeft = Offset(0f, ramp),
                    size = Size(size.width, size.height - ramp),
                )
            }
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = ScrimHeight,
                bottom = MueTheme.spacing.screenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        content = content,
    )
}

@Preview(name = "Sticky bottom action", showBackground = true, backgroundColor = 0xFF101012, heightDp = 320)
@Composable
private fun MueStickyBottomActionPreview() {
    MueTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MueTheme.spacing.screenHorizontal, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(6) {
                    MueSurfaceCard(shape = MueTheme.shapes.field) {
                        MueText("Scrolling content", MueTheme.typography.body)
                    }
                }
            }
            MueStickyBottomAction(modifier = Modifier.align(Alignment.BottomCenter)) {
                MuePrimaryButton(label = "Save activity", onClick = {})
            }
        }
    }
}
