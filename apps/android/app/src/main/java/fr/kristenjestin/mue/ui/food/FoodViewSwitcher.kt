package fr.kristenjestin.mue.ui.food

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
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

private val SwitcherIconSize: Dp = 18.dp

/** The prototype's `p-1`: the frame's inner margin, which is what makes the track *contained*. */
private val TrackPadding: Dp = 4.dp

/**
 * The room a drawn label needs to itself inside its segment, beyond its own glyphs.
 *
 * Half of it falls either side of a centred label, so two neighbouring names keep a whole gutter
 * between them. `MueBottomBar`'s `TabLabelGutter` is the same quantity for the same reason, and
 * `Food Pro…` running into `Pro…` is what its absence looks like.
 */
private val SegmentLabelGutter: Dp = 12.dp

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
 * it stays clear of the ramp the content below dissolves into: what fades under it is the journal
 * rather than the control.
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
 * The switcher over PRD_FOOD 7's views: **a contained track of equal segments**, as the prototype
 * draws it.
 *
 * `food.html` is unambiguous about the shape and PRD_FOOD 19 says outright that "le prototype fait
 * autorité pour la mise en page" — a rounded frame at the screen gutter, a faint fill, four pixels
 * of inner margin, and inside it segments of equal width of which the selected one is solid amber
 * on dark ink. What shipped instead was a row of free-floating pills that scrolled sideways, which
 * is a different control.
 *
 * ## The reason the track was rejected, and what answers it
 *
 * The objection was real and is not waved away here: segments of equal width are the layout where
 * a long name gets **cut in the middle**. Three segments inside a 360 dp screen leave about 95 dp
 * each, and at font scale 2.0 `Recipes` alone wants more than that — the exact defect that cost
 * the tab bar nine commits, with `Progress` and `Profile` both reaching the glass as `Pro…`.
 *
 * So this measures, exactly as [fr.kristenjestin.mue.ui.components.MueBottomBar] does. Every label
 * is laid out at this control's own type style, at the current density and font scale, and the
 * widest is compared against one segment's share less [SegmentLabelGutter]. Below that, the track
 * drops **every** label and keeps the glyphs, whose names move into their `contentDescription` so
 * TalkBack still announces `Recipes, tab, selected`. Three distinguishable icons in three segments
 * is a control that still works; three stumps of the same three letters is not.
 *
 * The widest label decides for all of them, again as in the bar: a track with two words and one
 * glyph in it would read as two kinds of thing.
 *
 * Nothing here reads a `dp` breakpoint, so a longer view name, a denser script or a wider phone
 * each move the threshold by themselves.
 *
 * ## The rejected alternatives
 *
 * *Shrinking the labels* so they fit: refused for the bar's reason, which has not changed — a
 * reader who sets the largest font is asking for large text, and answering with small text
 * punishes exactly the reader who asked.
 *
 * *Keeping the scrolling rail*: it did draw every name whole, and that was its argument. But it is
 * not the control the prototype draws, it gives no impression of how many views there are, and a
 * horizontally scrolling row inside a vertically scrolling screen is a gesture conflict on the one
 * control whose job was to make the views findable in the first place.
 */
@Composable
internal fun FoodViewSwitcher(
    views: List<FoodRoute.View>,
    selected: FoodRoute.View,
    onSelect: (FoodRoute.View) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MueTheme.spacing.screenHorizontal,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
    ) {
        val segments = views.size.coerceAtLeast(1)
        val labelled = labelsFitInSegment(
            views = views,
            share = (maxWidth - TrackPadding * 2) / segments,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                /*
                 * Before the padding, deliberately.
                 *
                 * A semantics modifier reports the bounds of the coordinator at *its* position in
                 * the chain, so a tag written after `.padding(...)` measures the space inside the
                 * frame rather than the frame. Sat at the end, as it was, it reported 48 dp on a
                 * track that was really 56 — which is a handle that cannot see the very defect
                 * this control was reported for. Every other use of the tag is an `assertExists`,
                 * to which the position makes no difference.
                 */
                .testTag(FoodTestTags.VIEW_SWITCHER)
                .clip(MueTheme.shapes.field)
                .background(MueTheme.colors.surface)
                /*
                 * Horizontally only. The prototype's inner margin exists on all four sides, and
                 * on three of them it still does — but taken vertically as well it was *added*
                 * to a segment that had just been raised to the 48 dp touch minimum, which made
                 * the track 56 dp and pushed the whole tab down by eight: "ton bouton avec la
                 * clé à molette là, il est plus gros, du coup ça décale légèrement vers le bas
                 * toute la tab". The margin is now drawn *inside* each segment instead, by
                 * [ViewSegment], where it insets the fill without inflating the track.
                 */
                .padding(horizontal = TrackPadding)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            views.forEach { view ->
                ViewSegment(
                    view = view,
                    selected = view == selected,
                    labelled = labelled,
                    onClick = { onSelect(view) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Whether every view's name can be drawn whole inside [share] of the track, gutter included.
 *
 * The labels are laid out with the switcher's own type style, at the current density and font
 * scale, so the answer follows the reader's text size without a breakpoint being written down.
 */
@Composable
private fun labelsFitInSegment(views: List<FoodRoute.View>, share: Dp): Boolean {
    val measurer = rememberTextMeasurer()
    val style = MueTheme.typography.chip
    val room = with(LocalDensity.current) { (share - SegmentLabelGutter).roundToPx() }

    return remember(measurer, views, style, room) {
        room > 0 && views.all { view ->
            measurer.measure(view.label, style, maxLines = 1).size.width <= room
        }
    }
}

/**
 * One segment of the track: a name, or the glyph that stands in for it.
 *
 * `selectable` merges the segment into a single node carrying the label, [Role.Tab] and the
 * selected state, which is everything TalkBack needs to announce `Foods, tab, selected` — the
 * arrangement the bottom bar uses, and the one lesson that bar paid for.
 *
 * The selection is carried by the fill, by the ink, and by the state `selectable` publishes, never
 * by colour alone (PRD 15 and PRD_FOOD 18). The unselected segments have no fill of their own: the
 * prototype gives the frame one fill and the active segment another, and outlining the inactive
 * ones would draw three boxes where the prototype draws one.
 */
@Composable
private fun ViewSegment(
    view: FoodRoute.View,
    selected: Boolean,
    labelled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val shape = MueTheme.shapes.small

    val container by animateColorAsState(
        targetValue = if (selected) colors.accent else Color.Transparent,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "viewSegmentContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onAccent else colors.textTertiary,
        animationSpec = MueMotion.spec(MueMotion.TabChangeMillis),
        label = "viewSegmentContent",
    )

    Box(
        modifier = modifier
            /*
             * The **segment** is the target, so the floor is on the segment and not on the frame
             * around it: sized off the frame, a 48 dp track leaves each button 40 dp once the
             * prototype's inner margin is taken off either side — under the minimum PRD_FOOD 18
             * sets, on the control whose whole job is to be tappable.
             *
             * What follows is the ordering that keeps that target *without* the track growing to
             * hold it. `selectable` sits **above** the vertical inset, so the hit area is the
             * whole 48 dp; the inset below it is what the fill is drawn inside, so the amber
             * block is the prototype's 40 dp. A target may be larger than what it draws, and here
             * it is — which is why the track is back to 48 dp and nothing under it moved.
             *
             * Both facts are measured: `everySegmentIsBigEnoughToTap` reads this node's bounds
             * (the layout node is the full 48 dp however the inset falls inside it) and
             * `theTrackDoesNotGrowToHoldItsTouchTargets` reads the track's.
             */
            .heightIn(min = MueMinTouchTarget)
            .selectable(
                selected = selected,
                indication = null,
                interactionSource = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = TrackPadding)
            .clip(shape)
            .background(container)
            .padding(horizontal = MueTheme.spacing.xs)
            .testTag(FoodTestTags.view(view)),
        contentAlignment = Alignment.Center,
    ) {
        if (labelled) {
            /*
             * `maxLines = 1` and no ellipsis worth reaching: the segment was measured wide enough
             * for this very string before `labelled` was allowed to be true, so the constraint
             * this label meets is one it already fits inside.
             */
            MueText(
                text = view.label,
                style = MueTheme.typography.chip,
                color = content,
                maxLines = 1,
            )
        } else {
            MueIcon(
                iconName = FoodIcons.forView(view),
                tint = content,
                size = SwitcherIconSize,
                /*
                 * Alone in the segment, the glyph has to carry the name itself. `selectable`
                 * above merges it up, so TalkBack still says `Recipes, tab, selected`.
                 */
                contentDescription = view.label,
            )
        }
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
 * The track on the narrowest phone the app supports at the largest font scale.
 *
 * What to look for: **three whole glyphs and no words**. Not `Rec…`, not `Recipe` over `s` — at
 * this scale `Recipes` does not fit a third of 360 dp, so every label goes and the icons stay,
 * which is the same answer `MueBottomBar` gives one level up. The track itself does not move: the
 * frame, the segments and the amber fill are the ones at the ordinary scale.
 *
 * `onNodeWithText("Recipes")` could never see the difference — the semantics string is `Recipes`
 * whatever the glyphs do — which is why this is a preview to look at, and why the switcher's own
 * instrumented test reads the drawn layout instead.
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
