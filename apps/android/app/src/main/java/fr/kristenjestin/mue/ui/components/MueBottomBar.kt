package fr.kristenjestin.mue.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

@Immutable
data class MueTab(val label: String, val contentDescription: String = label)

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
            .navigationBarsPadding()
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

            Column(
                modifier = Modifier
                    .weight(1f)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) },
                    )
                    .heightIn(min = BarMinHeight)
                    .padding(vertical = 10.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = tab.contentDescription
                    },
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
