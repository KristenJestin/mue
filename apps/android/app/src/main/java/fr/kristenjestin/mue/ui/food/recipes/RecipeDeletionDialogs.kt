package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The two moments a deletion stops and speaks (FR-RECIPE-006 and PRD_FOOD 17).
 *
 * **It asks, and then it reports.** FR-RECIPE-006 wants a confirmation, and PRD_FOOD 17 wants
 * the moments a deletion frees to be *signalled* rather than silently emptied. Those are two
 * different sentences at two different times: before the write nothing is known about which
 * proposals reference the recipe — `RecipeRepository` offers no read for it — and after the
 * write the keys it returned say exactly which, by date and moment.
 *
 * So the first dialog states the two consequences that are always true (the journal keeps its
 * lines, any proposal is freed), and the second names the freed moments the delete reported. A
 * deletion that freed none never reaches the second: it has nothing to say and the card leaves
 * at once.
 *
 * Both are Material `AlertDialog`s, the base PRD's documented exception for a blocking,
 * focus-trapped confirmation — the same shape `DeleteActivityDialog` uses, borrowed rather than
 * reinvented so the app asks its destructive questions one way.
 */
@Composable
internal fun RecipeDeletionDialogs(
    state: RecipeDetailUiState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onAcknowledge: () -> Unit,
) {
    val colors = MueTheme.colors

    if (state.isConfirmingDelete) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { MueText(RecipeMessages.DELETE_TITLE, MueTheme.typography.sectionTitle) },
            text = {
                MueText(
                    text = RecipeMessages.DELETE_BODY,
                    style = MueTheme.typography.body,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    MueText(
                        text = RecipeMessages.DELETE_CONFIRM,
                        style = MueTheme.typography.button,
                        color = colors.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onCancel) {
                    MueText(
                        text = RecipeMessages.CANCEL,
                        style = MueTheme.typography.button,
                        color = colors.textSecondary,
                    )
                }
            },
            containerColor = colors.canvasElevated,
            shape = MueTheme.shapes.card,
        )
    }

    val freed = state.freedPlans
    if (state.isDeleted && freed.isNotEmpty()) {
        AlertDialog(
            // Acknowledged either way: the row is already gone, so there is nothing to cancel.
            onDismissRequest = onAcknowledge,
            title = { MueText(RecipeMessages.DELETED_TITLE, MueTheme.typography.sectionTitle) },
            text = {
                Column(
                    modifier = Modifier.testTag(RecipeTestTags.FREED_PLANS),
                    verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
                ) {
                    MueText(
                        text = RecipeMessages.freedPlans(freed.size),
                        style = MueTheme.typography.body,
                        color = colors.textSecondary,
                    )
                    freed.forEach { plan ->
                        MueText(plan, MueTheme.typography.bodyStrong)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onAcknowledge) {
                    MueText(
                        text = RecipeMessages.DONE,
                        style = MueTheme.typography.button,
                        color = colors.accent,
                    )
                }
            },
            containerColor = colors.canvasElevated,
            shape = MueTheme.shapes.card,
        )
    }
}
