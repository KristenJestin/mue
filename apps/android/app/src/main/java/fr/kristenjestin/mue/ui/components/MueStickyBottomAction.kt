package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Length of the ramp the scrolling content dissolves into, above the action.
 *
 * Public because a screen has to know it: the ramp is drawn over content that keeps scrolling
 * underneath, so only the solid block below it may be subtracted from a scrolling viewport.
 *
 * It mirrors [MueContentTopFade] — one dissolve length for both ends of a screen. It was 32 dp;
 * the last 8 dp of that bought no legibility and made the band that much taller.
 */
val MueStickyActionRamp: Dp = MueContentTopFade

/** Same weight as the tab bar's top edge, which this line is the sibling of. */
private val HairlineWidth: Dp = 1.dp

/**
 * The pinned save action of `Log activity` and `Strength session`.
 *
 * It carries no window inset of its own. Contract decision 2 keeps the tab bar visible on both
 * screens, and the bar — which sits outside the navigation host — already owns the navigation
 * bar and the IME. Padding here as well would lift the action a whole bar too far.
 *
 * The band is two parts, and the split is what makes it honest. The ramp is painted rather
 * than composed and holds no pointer input, so a row of content scrolling underneath dissolves
 * into the canvas instead of being cut across *and* a thumb landing in the fade still reaches
 * the list: what cannot be seen never eats a gesture. The solid block below it is the reverse —
 * visible chrome, opaque to touch, so a drag on it never scrolls something invisible behind.
 *
 * [coversContent] says that scrollable content continues underneath. That, and only that, is
 * when the band earns the hairline at its top edge: an edge to the chrome exactly while it is
 * hiding something, and nothing at all when the screen fits.
 */
@Composable
fun MueStickyBottomAction(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
    coversContent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MueTheme.colors
    val canvas = colors.canvas
    val hairline = colors.hairline
    val hairlineAlpha by animateFloatAsState(
        targetValue = if (coversContent) 1f else 0f,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "stickyActionHairline",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(MueStickyActionRamp)
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(canvas.copy(alpha = 0f), canvas),
                        ),
                    )
                },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(canvas)
                .opaqueToTouch()
                .drawBehind {
                    if (hairlineAlpha <= 0f) return@drawBehind
                    val stroke = HairlineWidth.toPx()
                    drawLine(
                        color = hairline,
                        start = Offset(0f, stroke / 2f),
                        end = Offset(size.width, stroke / 2f),
                        strokeWidth = stroke,
                        alpha = hairlineAlpha,
                    )
                }
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    // The same clearance the tab bar leaves under its own hairline, so the
                    // line reads as an edge rather than as something glued to the action.
                    top = MueTheme.spacing.sm,
                    bottom = MueTheme.spacing.screenBottom,
                ),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            content = content,
        )
    }
}

/**
 * Stops a drag on the solid block from reaching the scrolling content behind it.
 *
 * Compose leaves the siblings drawn underneath alone as soon as a node carrying pointer input
 * is hit, so listening is enough — nothing is consumed, and the buttons inside keep receiving
 * their own events and their own touch targets.
 */
private fun Modifier.opaqueToTouch(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
        }
    }
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
            MueStickyBottomAction(
                modifier = Modifier.align(Alignment.BottomCenter),
                coversContent = true,
            ) {
                MuePrimaryButton(label = "Save activity", onClick = {})
            }
        }
    }
}
