package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The bullet the prototype sets between two facts of one line, as on the `Day` cards. */
private val FactBulletSize: Dp = 3.dp

/**
 * Two blocks side by side while both can be read, stacked once they cannot.
 *
 * This is the measured split that shipped on the `Day` screen's journal card, applied to the
 * rows of this half of the module: an ingredient and its contribution, a nutrient's noun and its
 * value. It is restated here rather than imported because the shipped one is private to
 * `ui/food/day` and that directory is another chunk's; a shared `MueSplitRow` belongs to the
 * font-scale sweep now going through the whole `ui` tree, not to a recipe screen reaching
 * sideways into a sibling.
 *
 * **A `Row` with `weight(1f)` on the left is what breaks.** The unweighted right-hand block is
 * measured first, at whatever width it asks for: `≈ 29.1 g` at font scale 2.0 wants some 430 px
 * of a 1080 px screen, and the weighted left is handed what is left. At that width an ordinary
 * ingredient name breaks *mid-word* over a dozen lines and a quantity comes out one glyph per
 * line — while every assertion still passes, because a semantics string is the whole text
 * however the glyphs fall.
 *
 * So the decision is measured instead. `minIntrinsicWidth` of a paragraph is the width of its
 * longest word — the narrowest it can be laid out in without breaking one — and
 * `maxIntrinsicWidth` of the value block is the width it wants. While the two fit beside each
 * other the layout is the ordinary one, to the pixel. When they no longer do, the value drops
 * onto its own line and the name gets the whole width. Nothing is capped and nothing is shrunk.
 *
 * The value stays end-aligned when it drops, so the eye still finds the figures on the right.
 */
@Composable
internal fun RecipeSplitRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gap: Dp = MueTheme.spacing.md,
) {
    Layout(
        contents = listOf(start, end),
        modifier = modifier,
    ) { (startMeasurables, endMeasurables), constraints ->
        val startMeasurable = startMeasurables.first()
        val endMeasurable = endMeasurables.first()
        val gapPx = gap.roundToPx()

        /*
         * A row is always measured inside a bounded parent; the fallback exists only so that an
         * intrinsic pass, which hands out an infinite width, cannot make this crash.
         */
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            startMeasurable.maxIntrinsicWidth(constraints.maxHeight) + gapPx +
                endMeasurable.maxIntrinsicWidth(constraints.maxHeight)
        }

        val endWidth = endMeasurable.maxIntrinsicWidth(constraints.maxHeight).coerceIn(0, width)
        val besideEnd = width - endWidth - gapPx
        val narrowestStart = startMeasurable.minIntrinsicWidth(constraints.maxHeight)

        // Each measurable may be measured once, so the choice is made before either is.
        if (besideEnd >= narrowestStart) {
            val startPlaceable = startMeasurable.measure(constraints.atMostWide(besideEnd))
            val endPlaceable = endMeasurable.measure(constraints.atMostWide(endWidth))
            val height = maxOf(startPlaceable.height, endPlaceable.height)
            layout(width, height) {
                startPlaceable.placeRelative(0, (height - startPlaceable.height) / 2)
                endPlaceable.placeRelative(
                    width - endPlaceable.width,
                    (height - endPlaceable.height) / 2,
                )
            }
        } else {
            val startPlaceable = startMeasurable.measure(constraints.atMostWide(width))
            val endPlaceable = endMeasurable.measure(constraints.atMostWide(width))
            val height = startPlaceable.height + gapPx + endPlaceable.height
            layout(width, height) {
                startPlaceable.placeRelative(0, 0)
                endPlaceable.placeRelative(
                    width - endPlaceable.width,
                    startPlaceable.height + gapPx,
                )
            }
        }
    }
}

/**
 * The same constraints, free to be anything up to [maxWidth] wide.
 *
 * `copy(maxWidth = …)` alone would keep a tight `minWidth` and could leave the two crossed over.
 */
private fun Constraints.atMostWide(maxWidth: Int): Constraints = Constraints(
    minWidth = 0,
    maxWidth = maxWidth.coerceAtLeast(0),
    minHeight = 0,
    maxHeight = maxHeight,
)

/**
 * The small facts of one card — `Main`, `Serves 4`, `25 min` — separated by the prototype's
 * bullet.
 *
 * A `Row` measures each fact in turn out of what is left, so the last one takes the shortfall
 * alone and comes out as a column of letters at a doubled font scale. A flow row makes the
 * shortfall a line break instead. Each fact carries its own leading bullet, so a fact that will
 * not fit moves to the next line *with* the mark that separates it and never leaves one
 * stranded. At one line — every line at the ordinary scale — this draws what the `Row` drew.
 */
@Composable
internal fun RecipeFactRow(facts: List<String>, modifier: Modifier = Modifier) {
    val colors = MueTheme.colors
    FlowRow(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
    ) {
        facts.forEachIndexed { index, fact ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = MueTheme.spacing.sm)
                            .size(FactBulletSize)
                            .clip(MueTheme.shapes.pill)
                            .background(colors.textQuiet),
                    )
                }
                MueText(fact, MueTheme.typography.micro, color = colors.textTertiary)
            }
        }
    }
}
