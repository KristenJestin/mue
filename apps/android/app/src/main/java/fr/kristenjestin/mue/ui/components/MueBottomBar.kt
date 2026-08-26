package fr.kristenjestin.mue.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.R
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * A tab carries its name and the Lucide vector above it (PRD_ACTIVITIES 14.1).
 *
 * It carries no separate `contentDescription`: the label *is* the name. Whether the name is
 * drawn or only spoken is [MueBottomBar]'s decision, taken from the width it is given — and
 * either way it is this one string, so the two can never disagree.
 */
@Immutable
data class MueTab(val label: String, @param:DrawableRes val iconRes: Int)

private val BarMinHeight = 60.dp
private val TabIconSize = 24.dp

/**
 * The room a drawn label needs to itself, beyond its own glyphs.
 *
 * Half of it falls either side of a centred label, so two neighbours keep a whole gutter between
 * them. `Food Pro…` ran straight into `Pro…` for want of exactly this.
 */
private val TabLabelGutter = 12.dp

/** A floor under the gutter: a label may reach a tab's edge only over this much padding. */
private val TabHorizontalPadding = 4.dp

/**
 * The permanent tab bar: an icon above a label, nothing else.
 *
 * Only colours cross-fade when the selection changes — no sliding pill, no moving indicator,
 * and the icon never changes shape or weight, so nothing in the bar reflows. PRD 8 requires it
 * to stay perfectly still during a tab transition, which also means the caller must place it
 * *outside* the animated navigation content.
 *
 * The icon and the label take the accent together: PRD_ACTIVITIES 14.1 forbids leaving the
 * selected state to the icon alone.
 *
 * **A label is drawn only while it can be drawn whole.** Five tabs across 360 dp is 72 dp each,
 * and at the largest font scale `Progress` and `Profile` both came out `Pro…` — two tabs with one
 * visible name, told apart by their glyph alone, and `Food Pro…` running together for want of a
 * gutter. Below the width its longest label needs, the bar therefore drops **every** label and
 * keeps the icons, which is what Material makes optional in a navigation bar and what leaves the
 * tabs distinguishable instead of identical. The name moves into the icon's
 * `contentDescription`, so TalkBack still announces `Progress, tab, selected` — the `Role.Tab`
 * and the selected state sit on `selectable` and never move.
 *
 * The rejected alternative was shrinking the labels to some 8 sp so they would fit. Someone who
 * sets their phone to the largest font is asking for large text; answering with smaller text
 * punishes exactly the reader who asked, and it would have left the bar the only place in the app
 * that ignores the system size.
 *
 * The threshold is measured rather than guessed: every label is laid out at the tab bar's own
 * type style and current density, and the widest one is compared against a tab's share of the
 * width less [TabLabelGutter]. Nothing here reads a `dp` breakpoint, so a longer word, a denser
 * script or a wider phone each move the threshold by themselves.
 */
@Composable
fun MueBottomBar(
    tabs: List<MueTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    BoxWithConstraints(
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
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        val labelled = labelsFitIn(tabs, share = maxWidth / tabs.size.coerceAtLeast(1))

        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    targetValue = if (selected) colors.accent else colors.textTertiary,
                    animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
                    label = "tabTint",
                )

                // `selectable` already merges the tab into one node carrying the name and the
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
                        .padding(vertical = 8.dp, horizontal = TabHorizontalPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MueIcon(
                        iconRes = tab.iconRes,
                        tint = tint,
                        size = TabIconSize,
                        /*
                         * Silent while the label is drawn beside it — the merging parent would
                         * otherwise say the name twice. Alone in the control, it has to carry
                         * the name itself, which is the one case PRD_ACTIVITIES 15 asks for.
                         */
                        contentDescription = tab.label.takeUnless { labelled },
                    )
                    if (labelled) {
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
    }
}

/**
 * Whether every label can be drawn whole in [share] of the bar, gutter included.
 *
 * The widest label decides for all of them: a bar with three names and two glyphs would read as
 * five different kinds of thing. The labels are laid out with the bar's own type style, at the
 * current density and font scale, so the answer follows the reader's text size without a
 * breakpoint being written down anywhere.
 */
@Composable
private fun labelsFitIn(tabs: List<MueTab>, share: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MueTheme.typography.tabLabel
    val room = with(LocalDensity.current) { (share - TabLabelGutter).roundToPx() }

    return remember(measurer, tabs, style, room) {
        room > 0 && tabs.all { tab ->
            measurer.measure(tab.label, style, maxLines = 1).size.width <= room
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
        MueTab("Food", R.drawable.ic_utensils),
        MueTab("Profile", R.drawable.ic_user_round),
    )
    MuePreviewHost(padding = 0) {
        MueBottomBar(tabs = tabs, selectedIndex = 0, onTabSelected = {})
        MueBottomBar(tabs = tabs, selectedIndex = 3, onTabSelected = {})
    }
}

/**
 * The same bar on the narrowest phone the app supports and at the largest font scale.
 *
 * Five tabs across 360 dp is 72 dp each, and `Progress` is the longest label in the bar. It no
 * longer fits at this scale, so this preview should show **five glyphs and no words** — which is
 * the whole point: `Progress` and `Profile` were both drawing `Pro…`, and a bar cannot say two
 * things with one word.
 *
 * `onNodeWithText("Progress")` could never have seen that. It matches the semantics string, which
 * stayed `Progress` whatever the glyphs did, so a shortened label passed every assertion the shell
 * has. `MueBottomBarLabelTest` reads the text layout instead.
 */
@Preview(
    name = "Bottom bar · 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    fontScale = 2.0f,
)
@Composable
private fun MueBottomBarNarrowPreview() {
    val tabs = listOf(
        MueTab("Entry", R.drawable.ic_scale),
        MueTab("Progress", R.drawable.ic_chart_no_axes_combined),
        MueTab("Activity", R.drawable.ic_activity),
        MueTab("Food", R.drawable.ic_utensils),
        MueTab("Profile", R.drawable.ic_user_round),
    )
    MuePreviewHost(padding = 0) {
        MueBottomBar(tabs = tabs, selectedIndex = 3, onTabSelected = {})
    }
}
