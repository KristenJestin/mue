package fr.kristenjestin.mue.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Two halves of a line — a name on the left, a figure or an action on the right — side by side
 * while both can be read, stacked once they cannot.
 *
 * This is the layout `FoodDayEntryCard` was given when a doubled font scale broke it, lifted out
 * of the Food module because the same row exists all over the app: a label and its value, a title
 * and its date, a field and its `Change`. The failure is always the same one. A plain `Row` has
 * to measure its **unweighted** child first, and at whatever width that child asks for; the
 * `weight(1f)` half is handed what is left. At the ordinary text size there is plenty left, so
 * nothing shows. At twice that size the right-hand half doubles too, the left-hand half is handed
 * a ribbon, and its text is broken *in the middle of a word* — or ellipsised down to a stump.
 * No assertion can see either: `onNodeWithText` matches the semantics string, which is the whole
 * label however the glyphs fall.
 *
 * Three fixes were weighed before this one. A `dp` cap on the right-hand half does not grow with
 * the text, so it is either too tight at scale 1.0 or still too loose at 2.0. A weight on both
 * halves divides the line by a fixed ratio at *every* scale, including the ordinary one where it
 * is already right. Shrinking the type answers someone who asked for large text with small text.
 * All three trade one scale against the other.
 *
 * So the decision is measured instead. `minIntrinsicWidth` of a paragraph is exactly the width of
 * its longest word — the narrowest it can be laid out in without breaking one — and
 * `maxIntrinsicWidth` of the right-hand half is the width it wants. While the two fit beside each
 * other the layout is the one that shipped, to the pixel. When they no longer do, [end] drops
 * onto its own line under [start] and [start] gets the whole width rather than a ribbon. Nothing
 * is capped, nothing is shrunk, and nothing about the ordinary scale changes.
 *
 * [end] stays end-aligned when it drops, so the eye still finds the figure on the right of the
 * line at either scale. [gap] is the room the two are asked to keep between them while they sit
 * abreast; [stackedGap] is what separates them once they no longer do, and defaults to the same.
 * A caller replacing a `Row` that kept no gap at all passes `gap = 0.dp` and a stacked gap of its
 * own, which is how the abreast case stays the drawing that shipped, to the pixel.
 *
 * Abreast, the two are placed exactly where `Row(SpaceBetween, [verticalAlignment])` placed them —
 * the alignment object itself does the arithmetic rather than a hand-rolled halving, because the
 * two round an odd remainder differently and a row swapped for this one would otherwise move by a
 * pixel. Stacked, [verticalAlignment] no longer means anything and the two simply follow one
 * another.
 */
@Composable
internal fun MueSplitRow(
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
    stackedGap: Dp = gap,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
) {
    Layout(
        contents = listOf(start, end),
        modifier = modifier,
    ) { (startMeasurables, endMeasurables), constraints ->
        val startMeasurable = startMeasurables.first()
        val endMeasurable = endMeasurables.firstOrNull()
        val gapPx = gap.roundToPx()
        val stackedGapPx = stackedGap.roundToPx()

        /*
         * A split row is always measured inside a bounded parent; the fallback is only so that an
         * intrinsic pass, which hands out an infinite width, cannot make this crash.
         */
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            startMeasurable.maxIntrinsicWidth(constraints.maxHeight) + gapPx +
                (endMeasurable?.maxIntrinsicWidth(constraints.maxHeight) ?: 0)
        }

        // Nothing on the right: the whole width belongs to the left, and no gap is spent.
        if (endMeasurable == null) {
            val only = startMeasurable.measure(constraints.atMostWide(width))
            return@Layout layout(width, only.height) { only.placeRelative(0, 0) }
        }

        val endWidth = endMeasurable
            .maxIntrinsicWidth(constraints.maxHeight)
            .coerceIn(0, width)
        val besideStart = width - endWidth - gapPx
        val narrowestStart = startMeasurable.minIntrinsicWidth(constraints.maxHeight)

        // Each measurable may be measured once, so the choice is made before either is.
        if (besideStart >= narrowestStart) {
            val startPlaceable = startMeasurable.measure(constraints.atMostWide(besideStart))
            val endPlaceable = endMeasurable.measure(constraints.atMostWide(endWidth))
            val height = maxOf(startPlaceable.height, endPlaceable.height)
            layout(width, height) {
                startPlaceable.placeRelative(
                    0,
                    verticalAlignment.align(startPlaceable.height, height),
                )
                endPlaceable.placeRelative(
                    width - endPlaceable.width,
                    verticalAlignment.align(endPlaceable.height, height),
                )
            }
        } else {
            val startPlaceable = startMeasurable.measure(constraints.atMostWide(width))
            val endPlaceable = endMeasurable.measure(constraints.atMostWide(width))
            val height = startPlaceable.height + stackedGapPx + endPlaceable.height
            layout(width, height) {
                startPlaceable.placeRelative(0, 0)
                endPlaceable.placeRelative(
                    width - endPlaceable.width,
                    startPlaceable.height + stackedGapPx,
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
