package fr.kristenjestin.mue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.theme.mueAmberGlow

/** Visible chrome of the back control. The touch target around it is [MueMinTouchTarget]. */
private val NavigationChromeSize = 40.dp

/**
 * Shell of a screen reached *from* a tab rather than by one: a back control, a centred title,
 * and an optional trailing slot, over the same canvas and the same amber glow as
 * [MueScreenScaffold].
 *
 * It is a sibling rather than a flag on the scaffold because the two differ in what the header
 * *is*: the wordmark names the app, the title names the screen, and a scaffold that sometimes
 * drew one and sometimes the other would make every caller state which. `Log activity` and
 * `Strength session` are the two screens that need this one.
 *
 * The bottom tab bar stays outside this scaffold, exactly as for [MueScreenScaffold]: contract
 * decision 2 keeps it visible on both sub-screens.
 */
@Composable
fun MueSubScreenScaffold(
    title: String,
    onNavigateBack: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationContentDescription: String = "Back",
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
    trailing: @Composable (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    topFade: Dp = MueContentTopFade,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MueTheme.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MueTheme.colors.canvas)
            .mueAmberGlow(),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        top = spacing.screenTop,
                        bottom = spacing.sm,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(MueMinTouchTarget)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                            onClick = onNavigateBack,
                        )
                        .semantics { contentDescription = navigationContentDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(NavigationChromeSize)
                            .clip(CircleShape)
                            .background(MueTheme.colors.surface)
                            .border(1.dp, MueTheme.colors.surfaceBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                        content = { navigationIcon() },
                    )
                }

                MueText(
                    text = title,
                    style = MueTheme.typography.sectionTitle,
                    color = MueTheme.colors.textPrimary,
                    /*
                     * No ceiling: at the largest font size on a 360 dp phone the two screens this
                     * scaffold carries announced themselves as `Activity in pr…` and
                     * `Strength ses…`, and a screen that will not say its own name is the one
                     * thing a header is for. The title is already centred and already weighted,
                     * so a second line simply makes the header a line taller; at the ordinary
                     * size every title still fits on one and nothing moves.
                     */
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = spacing.sm)
                        .semantics { heading() },
                )

                // Balances the back control so the title is centred on the screen rather than
                // on what is left of it.
                Box(
                    modifier = Modifier.size(MueMinTouchTarget),
                    contentAlignment = Alignment.Center,
                    content = { trailing?.invoke() },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .subScreenTopFade(topFade)
                    .padding(horizontal = horizontalPadding),
                verticalArrangement = verticalArrangement,
                content = content,
            )
        }
    }
}

/**
 * Same ramp as the tab scaffold's, restated because that one is file-private and this file may
 * not edit it. Masks the content's own alpha rather than painting a scrim, so the amber glow
 * keeps its full strength through the fade.
 */
private fun Modifier.subScreenTopFade(height: Dp): Modifier {
    if (height <= 0.dp) return this
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = height.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
}

@Preview(name = "Sub-screen scaffold", showBackground = true, backgroundColor = 0xFF101012, heightDp = 420)
@Composable
private fun MueSubScreenScaffoldPreview() {
    MueTheme {
        MueSubScreenScaffold(
            title = "Log activity",
            onNavigateBack = {},
            navigationIcon = { MuePreviewIcon(MuePreviewGlyph.BACK) },
        ) {
            MueScreenTitle(
                title = "Make it yours.",
                eyebrow = "What did you do?",
                modifier = Modifier.padding(top = MueTheme.spacing.xl),
            )
            MueSurfaceCard(modifier = Modifier.padding(top = MueTheme.spacing.xl)) {
                MueText("Treadmill details", MueTheme.typography.sectionTitle)
                MueText(
                    "Distance, reported speed, estimated energy and incline.",
                    MueTheme.typography.body,
                    color = MueTheme.colors.textSecondary,
                )
            }
        }
    }
}
