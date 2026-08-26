package fr.kristenjestin.mue.ui.food

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import kotlin.math.roundToInt

private val SwitcherIconSize: Dp = 18.dp

/**
 * Which view is showing and how to change it, published for the screens rather than passed to
 * them.
 *
 * A composition local and not three new parameters, for the reason `LocalReduceMotion` is one: the
 * three view screens have no business learning about the module's stack to draw a control they do
 * not own, and their own tests compose them with no stack at all. Absent — which is what every one
 * of those tests sees — [FoodViewScaffold] draws no switcher and the screen is exactly what it was
 * before this file existed. `FoodNavHost` is the single place that provides it.
 */
@Immutable
internal data class FoodViewSelection(
    val selected: FoodRoute.View,
    val onSelect: (FoodRoute.View) -> Unit,
)

internal val LocalFoodViewSelection = staticCompositionLocalOf<FoodViewSelection?> { null }

/**
 * The shell of one of PRD_FOOD 7's views: the app's screen scaffold with the switcher under its
 * wordmark.
 *
 * The prototype puts the switcher exactly here — between `MUE` and the scrolling section, at the
 * screen gutter — and so does this. It sits in [MueScreenScaffold]'s own header slot, which means
 * it is drawn edge to edge and stays clear of the ramp the content below dissolves into: the rail
 * can scroll past the gutter, and what fades under it is the journal rather than the control.
 *
 * A view screen calls this instead of [MueScreenScaffold] and gains the switcher by doing nothing
 * else. A sheet calls `MueSubScreenScaffold` and therefore cannot show one, which is the right
 * answer without a single condition being written: a sheet is a modal over a view, and switching
 * views from inside one would leave the sheet with nothing behind it.
 */
@Composable
internal fun FoodViewScaffold(
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    topFade: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val selection = LocalFoodViewSelection.current
    MueScreenScaffold(
        modifier = modifier,
        trailing = trailing,
        verticalArrangement = verticalArrangement,
        topFade = topFade,
        header = selection?.let { current ->
            {
                FoodViewSwitcher(
                    views = FoodRoute.SWITCHABLE,
                    selected = current.selected,
                    onSelect = current.onSelect,
                    modifier = Modifier.padding(bottom = MueTheme.spacing.md),
                )
            }
        },
        content = content,
    )
}

/**
 * The switcher over PRD_FOOD 7's views, which the module has been missing since its first screen.
 *
 * [FoodRoute.View] has described this control from the beginning — "siblings reached by a
 * switcher" — and [FoodTestTags.VIEW_SWITCHER] and [FoodTestTags.view] were agreed for it before
 * any screen landed. Nothing ever drew it. The consequence was not cosmetic: `Foods` was reachable
 * only through a back door (a search that finds nothing offering to create a food), `Recipes` only
 * through `Use a recipe` inside the add sheet, and the owner's report says exactly that — *"je peux
 * pas créer de food sans ajouter via « add what you ate »"*. Three of four views had no front door.
 *
 * **It is a rail, not a set of equal segments.** Four names across 360 dp is 90 dp each, and at
 * twice the font scale `Recipes` alone needs more than that: laid out as segments they would all
 * be cut, which is the very defect that cost the tab bar nine fixes today — `Progress` and
 * `Profile` both reaching the glass as `Pro…`. [fr.kristenjestin.mue.ui.components.MueBottomBar]
 * answers it by measuring its labels and dropping every one of them below the width they need,
 * because a permanent bar cannot scroll and five glyphs are still five distinguishable glyphs.
 *
 * This control can scroll, and that is the better answer here for one reason: the complaint being
 * fixed is that a view could not be *found*. Answering it with three anonymous glyphs would be
 * half a fix. A rail gives each name the width it asks for and lets the row overflow, so **every
 * label is drawn whole at every font scale** — never measured, never dropped, never ellipsised.
 * The guarantee is structural rather than computed: a child of a horizontally scrolling [Row] is
 * measured with an unbounded width, so [MueText] lays its label out at its intrinsic size and has
 * nothing to ellipsise. Two views therefore cannot draw the same stump, because there are no
 * stumps.
 *
 * What it costs is that at the largest font scale not every name is on screen at once. That is the
 * honest trade: the reader who asked for large text gets large text and a row that moves, rather
 * than small text or no text.
 */
@Composable
internal fun FoodViewSwitcher(
    views: List<FoodRoute.View>,
    selected: FoodRoute.View,
    onSelect: (FoodRoute.View) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
) {
    val scroll = rememberScrollState()
    val selectedIndex = views.indexOf(selected).coerceAtLeast(0)

    /*
     * Brings the current view into sight when the rail is too narrow to hold them all.
     *
     * Positioned by index rather than by measured offset: `maxValue` is zero exactly while
     * everything fits, so at an ordinary font scale this does nothing at all, and when it does
     * scroll the two ends are exact — the first name flush left, the last flush right. With three
     * or four names that is precise enough to put the middle ones on screen too, and it needs
     * neither an experimental relocation API nor coordinate arithmetic that a padding modifier
     * could quietly offset by a gutter.
     */
    LaunchedEffect(selectedIndex, scroll.maxValue) {
        if (scroll.maxValue > 0) {
            val last = (views.size - 1).coerceAtLeast(1)
            scroll.scrollTo((scroll.maxValue.toFloat() * selectedIndex / last).roundToInt())
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            // Inside the scroll, so the gutter travels with the names instead of clipping them.
            .padding(horizontal = horizontalPadding)
            .selectableGroup()
            .testTag(FoodTestTags.VIEW_SWITCHER),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        views.forEach { view ->
            ViewPill(
                view = view,
                selected = view == selected,
                onClick = { onSelect(view) },
            )
        }
    }
}

/**
 * One view's name, with its glyph.
 *
 * `selectable` merges the pill into a single node carrying the label, [Role.Tab] and the selected
 * state, which is everything TalkBack needs to announce `Foods, tab, selected` — the arrangement
 * the bottom bar uses, and the one lesson that bar paid for. The icon stays silent because the
 * name is beside it; unlike the bar's, this label is never dropped, so the name is never the
 * icon's to carry.
 *
 * The selection is carried by three things and never by colour alone (PRD 15 and PRD_FOOD 18): the
 * fill, the border, and the state `selectable` publishes.
 */
@Composable
private fun ViewPill(
    view: FoodRoute.View,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.pill

    val container by animateColorAsState(
        targetValue = if (selected) colors.accentSoft else colors.surfaceStrong,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "viewPillContainer",
    )
    val border by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.surfaceBorder,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "viewPillBorder",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onAccentSoft else colors.textSecondary,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "viewPillContent",
    )

    Row(
        modifier = Modifier
            .heightIn(min = MueMinTouchTarget)
            .clip(shape)
            .background(container)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .selectable(
                selected = selected,
                indication = null,
                interactionSource = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = MueTheme.spacing.lg, vertical = MueTheme.spacing.sm)
            .testTag(FoodTestTags.view(view)),
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MueIcon(
            iconName = FoodIcons.forView(view),
            tint = content,
            size = SwitcherIconSize,
        )
        /*
         * `maxLines = 1` and nothing else. The row it sits in scrolls, so the constraint reaching
         * this label is unbounded and there is no width for it to be cut to — which is what makes
         * "drawn whole or not at all" hold without a measurement being taken anywhere.
         */
        MueText(
            text = view.label,
            style = MueTheme.typography.chip,
            color = content,
            maxLines = 1,
        )
    }
}

// region previews

@Preview(name = "Food views", showBackground = true, backgroundColor = 0xFF101012, widthDp = 390)
@Composable
private fun FoodViewSwitcherPreview() {
    MuePreviewHost(padding = 0) {
        FoodViewSwitcher(
            views = FoodRoute.SWITCHABLE,
            selected = FoodRoute.Day,
            onSelect = {},
        )
        FoodViewSwitcher(
            views = FoodRoute.SWITCHABLE,
            selected = FoodRoute.Foods,
            onSelect = {},
        )
    }
}

/**
 * The rail on the narrowest phone the app supports at the largest font scale.
 *
 * What to look for: three whole words. Not `Rec…`, not three bare glyphs — the row simply runs
 * past the right edge and scrolls, and the selected name is the one brought into sight. Laid out
 * as equal segments this is where `Recipes` would have been cut, which is the tab bar's defect
 * moved one level down.
 */
@Preview(
    name = "Food views · 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    fontScale = 2.0f,
)
@Composable
private fun FoodViewSwitcherNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodViewSwitcher(
            views = FoodRoute.SWITCHABLE,
            selected = FoodRoute.Recipes,
            onSelect = {},
        )
    }
}

// endregion
