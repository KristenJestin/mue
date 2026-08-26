package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.day.announcedAs
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The gap between the name and the figures while the two still fit on one line. */
private val SplitGap: Dp = 16.dp

/**
 * The provenance tile at the head of a catalogue row: the prototype's `h-11 w-11`, so 44 dp.
 *
 * The row had none at all. Where the prototype opens every food with an amber glyph on a dark
 * tile — the same object the journal, the picker and the recipes all lead with — this screen put
 * a 16 dp grey glyph on a **third line**, under the name and under the figures. The result was a
 * tall block of left-aligned text with nothing to enter it by, and the one list in the module
 * that did not look like the others.
 */
private val SourceTileSize: Dp = 44.dp

/**
 * One food of the catalogue (PRD_FOOD 9.4, 13.2, 16.3 and 18).
 *
 * The card announces itself whole rather than as five loose fragments, which is what
 * [announcedAs] does on the journal's own lines; the figures inside it are already words by then
 * — `about 59 kcal`, `unknown fibre` — because `—` and `≈` cannot be spoken as they are drawn.
 */
@Composable
internal fun FoodCatalogueRow(
    state: FoodRowUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors

    MueSurfaceCard(
        modifier = modifier
            .heightIn(min = MueMinTouchTarget)
            .testTag(FoodTestTags.foodCard(state.id.value)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
        onClick = onClick,
        onClickLabel = state.name,
    ) {
        Row(
            // The card is one announcement, not five fragments (PRD_FOOD 18).
            modifier = Modifier.fillMaxWidth().announcedAs(state.description),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(SourceTileSize)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // Decorative: the provenance is spelled out in the meta line beside it.
                MueIcon(iconName = state.iconName, tint = colors.onAccentSoft, size = 20.dp)
            }

            Column(
                modifier = Modifier.weight(1f).padding(start = spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                FoodSplitRow(
                    leading = {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                            /*
                             * No `maxLines`. PRD_FOOD 15 lets a name run to eighty characters,
                             * and `MueText` ellipsises whatever it cannot fit — which
                             * `onNodeWithText` would never notice, because it matches the
                             * semantics string rather than the glyphs. The name wraps instead.
                             */
                            MueText(state.name, MueTheme.typography.bodyStrong)
                            /*
                             * The prototype's `(brand||source)` line, kept as `brand · source`
                             * rather than as a choice between the two: FR-CATALOG-004 wants a
                             * provenance readable on every food, and a branded product that
                             * printed only its brand would no longer say it was scanned.
                             */
                            MueText(
                                text = state.metaLabel,
                                style = MueTheme.typography.micro,
                                color = colors.textTertiary,
                            )
                        }
                    },
                    figures = {
                        if (state.hasFigures) {
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                            ) {
                                MueText(
                                    text = state.figures.first(),
                                    style = MueTheme.typography.bodyStrong,
                                    color = colors.accent,
                                    textAlign = TextAlign.End,
                                )
                                MueText(
                                    text = state.basisLabel,
                                    style = MueTheme.typography.micro,
                                    color = colors.textQuiet,
                                    textAlign = TextAlign.End,
                                )
                            }
                        }
                    },
                    gap = SplitGap,
                    modifier = Modifier.fillMaxWidth(),
                )

                /*
                 * The four macronutrients, each keeping its noun, so `— fibre` cannot be mistaken
                 * for a missing row and `≈ 0.0 g fibre` cannot be mistaken for an unknown one.
                 * They wrap onto as many lines as the font scale needs rather than being squeezed
                 * on one.
                 */
                if (state.figures.size > 1) {
                    MueText(
                        text = state.figures.drop(1).joinToString(SEPARATOR),
                        style = MueTheme.typography.caption,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

private const val SEPARATOR = "   "

/**
 * A name on the left and its figures on the right — until they no longer fit, and then one above
 * the other.
 *
 * `Row` with `weight(1f)` cannot do this, and that is the defect this project has already paid
 * for on the journal's own cards: at a doubled font scale the trailing figures take the width
 * they want, the weighted name is left a ribbon narrower than its longest word, and the text
 * breaks *mid-word* over a dozen lines — or is ellipsised away, which no `onNodeWithText`
 * assertion can see. Capping the figures or padding the name would only trade one scale against
 * the other.
 *
 * So the decision is **measured**. `minIntrinsicWidth` of a paragraph is exactly the width of its
 * longest word — the narrowest it can be laid out in without breaking one — and
 * `maxIntrinsicWidth` of the figures is the width they want. While the two fit side by side the
 * layout is the ordinary one, to the pixel; when they do not, the figures drop under the name and
 * the name takes the whole width. Nothing is capped and nothing is shrunk.
 *
 * The figures stay end-aligned when they drop, so the eye still finds the energy on the right of
 * the card at either scale.
 *
 * This is the same measured split the shipped journal card uses. It is a private composable of
 * this package rather than a shared component because promoting it would mean reopening
 * `ui/components`, which another chunk of work owns this week.
 */
@Composable
internal fun FoodSplitRow(
    leading: @Composable () -> Unit,
    figures: @Composable () -> Unit,
    gap: Dp,
    modifier: Modifier = Modifier,
) {
    Layout(contents = listOf(leading, figures), modifier = modifier) {
            (leadingMeasurables, figureMeasurables), constraints ->
        val leadingMeasurable = leadingMeasurables.first()
        val figuresMeasurable = figureMeasurables.firstOrNull()
        val gapPx = gap.roundToPx()

        val figuresWanted = figuresMeasurable?.maxIntrinsicWidth(constraints.maxHeight) ?: 0

        /*
         * A card is always measured inside a bounded row; the fallback is only so that an
         * intrinsic pass, which hands out an infinite width, cannot make this crash.
         */
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            leadingMeasurable.maxIntrinsicWidth(constraints.maxHeight) + gapPx + figuresWanted
        }

        if (figuresMeasurable == null || figuresWanted == 0) {
            val placeable = leadingMeasurable.measure(constraints.atMostWide(width))
            return@Layout layout(width, placeable.height) { placeable.placeRelative(0, 0) }
        }

        val figuresWidth = figuresWanted.coerceIn(0, width)
        val besideName = width - figuresWidth - gapPx
        val narrowestName = leadingMeasurable.minIntrinsicWidth(constraints.maxHeight)

        // Each measurable may be measured once, so the choice is made before either is.
        if (besideName >= narrowestName) {
            val namePlaceable = leadingMeasurable.measure(constraints.atMostWide(besideName))
            val figuresPlaceable = figuresMeasurable.measure(constraints.atMostWide(figuresWidth))
            val height = maxOf(namePlaceable.height, figuresPlaceable.height)
            layout(width, height) {
                namePlaceable.placeRelative(0, (height - namePlaceable.height) / 2)
                figuresPlaceable.placeRelative(
                    width - figuresPlaceable.width,
                    (height - figuresPlaceable.height) / 2,
                )
            }
        } else {
            val namePlaceable = leadingMeasurable.measure(constraints.atMostWide(width))
            val figuresPlaceable = figuresMeasurable.measure(constraints.atMostWide(width))
            val height = namePlaceable.height + gapPx + figuresPlaceable.height
            layout(width, height) {
                namePlaceable.placeRelative(0, 0)
                figuresPlaceable.placeRelative(
                    width - figuresPlaceable.width,
                    namePlaceable.height + gapPx,
                )
            }
        }
    }
}

/** A width offered rather than imposed: the child may take less, and never more. */
private fun Constraints.atMostWide(width: Int): Constraints =
    copy(minWidth = 0, maxWidth = width.coerceAtLeast(0))
