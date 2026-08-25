package fr.kristenjestin.mue.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * A tab carries its visible label and the Lucide vector above it (PRD_ACTIVITIES 14.1).
 *
 * It deliberately has no `contentDescription`: the label is the name, and repeating it as a
 * description would make TalkBack announce the tab twice under the merging parent.
 */
@Immutable
data class MueTab(val label: String, @param:DrawableRes val iconRes: Int)

private val BarMinHeight = 60.dp
private val TabIconSize = 24.dp

/**
 * Four-tab bar: an icon above a label, nothing else.
 *
 * Only colours cross-fade when the selection changes — no sliding pill, no moving indicator,
 * and the icon never changes shape or weight, so nothing in the bar reflows. PRD 8 requires it
 * to stay perfectly still during a tab transition, which also means the caller must place it
 * *outside* the animated navigation content.
 *
 * The icon and the label take the accent together: PRD_ACTIVITIES 14.1 forbids leaving the
 * selected state to the icon alone.
 */
@Composable
fun MueBottomBar(
    tabs: List<MueTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.canvasElevated)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(
                    color = colors.hairline,
                    start = Offset(0f, stroke / 2f),
                    end = Offset(size.width, stroke / 2f),
                    strokeWidth = stroke,
                )
            }
            /*
             * The window is edge to edge, so the IME never resizes it: without this the bar,
             * and the screen above it, stay under the keyboard.
             *
             * The two insets are unioned rather than chained as `navigationBarsPadding()
             * .imePadding()`. The IME inset already spans the navigation bar, so chaining
             * would add the bar's height a second time and the whole tab bar would jump by
             * that much the moment the keyboard appeared. A union is the taller of the two:
             * the navigation bar alone at rest, exactly the keyboard once it is up.
             *
             * Growing here also shrinks the weighted content above, which is what lifts each
             * screen's own bottom actions clear of the keyboard.
             */
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val tint by animateColorAsState(
                targetValue = if (selected) colors.accent else colors.textTertiary,
                animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
                label = "tabTint",
            )

            // `selectable` already merges the row into one node carrying the label and the
            // selected state, which is everything TalkBack needs to announce.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) },
                    )
                    .heightIn(min = BarMinHeight)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MueIcon(iconRes = tab.iconRes, tint = tint, size = TabIconSize)
                MueText(
                    text = tab.label,
                    style = MueTheme.typography.tabLabel,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Preview(name = "Bottom bar", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueBottomBarPreview() {
    val tabs = listOf(
        MueTab("Entry", R.drawable.ic_scale),
        MueTab("Progress", R.drawable.ic_chart_no_axes_combined),
        MueTab("Activity", R.drawable.ic_activity),
        MueTab("Profile", R.drawable.ic_user_round),
    )
    MuePreviewHost(padding = 0) {
        MueBottomBar(tabs = tabs, selectedIndex = 0, onTabSelected = {})
        MueBottomBar(tabs = tabs, selectedIndex = 2, onTabSelected = {})
    }
}
