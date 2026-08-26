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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The bullet the prototype sets between two facts of one line, as on the `Day` cards. */
private val FactBulletSize: Dp = 3.dp

/**
 * The small facts of one card — `Main`, `Serves 4`, `25 min` — separated by the prototype's
 * bullet.
 *
 * A `Row` measures each fact in turn out of what is left, so the last one takes the shortfall
 * alone and comes out as a column of letters at a doubled font scale. A flow row makes the
 * shortfall a line break instead. Each fact carries its own leading bullet, so a fact that will
 * not fit moves to the next line *with* the mark that separates it and never leaves one
 * stranded. At one line — every line at the ordinary scale — this draws what the `Row` drew.
 *
 * The *other* half of that defect, a name squeezed into a ribbon by the figures beside it, is
 * [fr.kristenjestin.mue.ui.components.MueSplitRow]'s to answer, and these screens use it rather
 * than a weighted `Row`.
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
