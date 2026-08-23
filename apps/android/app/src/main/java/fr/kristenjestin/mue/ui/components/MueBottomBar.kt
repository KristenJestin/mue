package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * A tab carries only its visible label.
 *
 * It deliberately has no `contentDescription`: the label is the name, and repeating it as a
 * description would make TalkBack announce the tab twice under the merging parent.
 */
@Immutable
data class MueTab(val label: String)

private val BarMinHeight = 60.dp
private val IndicatorSize = 7.dp

/**
 * Three-tab bar: a dot indicator above a label, nothing else.
 *
 * Only colours cross-fade when the selection changes — no sliding pill, no moving indicator.
 * PRD 8 requires the bar to stay perfectly still during a tab transition, which also means
 * the caller must place it *outside* the animated navigation content.
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
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(IndicatorSize)
                        .then(
                            if (selected) {
                                Modifier.background(tint, CircleShape)
                            } else {
                                Modifier.border(1.dp, tint, CircleShape)
                            },
                        ),
                )
                MueText(
                    text = tab.label,
                    style = MueTheme.typography.tabLabel,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Preview(name = "Bottom bar", showBackground = true, backgroundColor = 0xFF101012)
@Composable
private fun MueBottomBarPreview() {
    MuePreviewHost(padding = 0) {
        MueBottomBar(
            tabs = listOf(MueTab("Entry"), MueTab("Progress"), MueTab("Profile")),
            selectedIndex = 0,
            onTabSelected = {},
        )
        MueBottomBar(
            tabs = listOf(MueTab("Entry"), MueTab("Progress"), MueTab("Profile")),
            selectedIndex = 1,
            onTabSelected = {},
        )
    }
}
