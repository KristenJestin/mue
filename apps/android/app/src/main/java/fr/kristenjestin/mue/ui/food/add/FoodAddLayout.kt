package fr.kristenjestin.mue.ui.food.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * One nutritional figure: what it is, and what it is worth (PRD_FOOD 13.2).
 *
 * The value is drawn exactly as [fr.kristenjestin.mue.domain.logic.FoodLabels] rendered it —
 * `≈ 12.3 g`, `≈ 0.0 g`, or `—` — and nothing on this side of the domain may turn one into
 * another. It is never truncated: a `—` cut in half is not a smaller unknown, and `≈ 1204 kcal`
 * cut in half is a wrong number.
 *
 * The two halves are split by [MueSplitRow] rather than by a `Row` with a weight, for the reason
 * that component records: an unweighted figure measured first takes the width it wants, and at a
 * doubled font scale the label beside it is left a ribbon and breaks mid-word. Here they stack
 * instead, and the figure stays end-aligned when they do.
 */
@Composable
internal fun FoodFigureRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = MueTheme.colors
    val type = MueTheme.typography
    MueSplitRow(
        modifier = modifier.fillMaxWidth(),
        gap = MueTheme.spacing.md,
        start = { MueText(label, type.body, color = colors.textSecondary) },
        end = { MueText(value, type.bodyStrong, color = colors.textPrimary) },
    )
}

/**
 * A titled block of the sheet — `How much?`, `Which moment?` — on the app's own card.
 *
 * [description] is what a screen reader hears **instead of** the fragments inside, for a block
 * that is one fact rather than a set of controls (PRD_FOOD 18). It is arranged exactly as the
 * `Day` screen arranges a moment's total: the sentence goes on the heading, and the figures under
 * it are hidden from accessibility rather than cleared — so the drawn strings stay in the
 * semantics tree and a test can still read what a reader actually sees, which is the only way to
 * prove a `—` has not become a `0` on the way to the glass.
 */
@Composable
internal fun FoodSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MueSurfaceCard(
        modifier = modifier.fillMaxWidth(),
        shape = MueTheme.shapes.card,
        contentPadding = PaddingValues(MueTheme.spacing.xl),
    ) {
        MueText(
            text = title,
            style = MueTheme.typography.label,
            color = MueTheme.colors.textTertiary,
            modifier = description
                ?.let { spoken ->
                    Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                        heading()
                    }
                }
                ?: Modifier,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MueTheme.spacing.md)
                .then(
                    if (description == null) {
                        Modifier
                    } else {
                        Modifier.semantics { hideFromAccessibility() }
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
            content = content,
        )
    }
}
