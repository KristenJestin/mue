package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MuePickerEmpty
import fr.kristenjestin.mue.ui.components.MuePickerList
import fr.kristenjestin.mue.ui.components.MuePickerRow
import fr.kristenjestin.mue.ui.components.MuePickerSectionHeader
import fr.kristenjestin.mue.ui.components.MuePickerSheet
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The picker an ingredient is chosen through (PRD_FOOD 11, FR-RECIPE-002).
 *
 * **Choosing a food does not close it.** A recipe is several foods at once, and a sheet that
 * dismissed on the first selection would have to be reopened for every ingredient — the very
 * complaint the activity module's exercise picker earned. So the sheet stays, its action reports
 * what it has added, and it closes when it is told to. The same food may be picked twice on
 * purpose: `RecipeIngredientId` exists because a marinade and a sauce can draw on the same oil,
 * so no row is drawn as "already selected" and none of them toggles.
 *
 * FR-RECIPE-002 refuses free text as an ingredient, so there is no "create what you typed"
 * action here. A food that does not exist yet is created in `Foods`, which is the catalogue's
 * own screen and the module's one place for it; the shared `Food picker` sheet PRD_FOOD 7 lists
 * will replace this one when the catalogue lands, and only the callbacks below have to move.
 */
@Composable
internal fun RecipeIngredientPickerSheet(
    state: RecipePickerUiState,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MuePickerSheet(
        visible = state.visible,
        onDismissRequest = onDismiss,
        title = RecipeMessages.PICKER_TITLE,
        eyebrow = RecipeMessages.PICKER_EYEBROW,
        query = state.query,
        onQueryChange = onQueryChange,
        modifier = modifier.testTag(FoodTestTags.FOOD_PICKER),
        searchPlaceholder = RecipeMessages.PICKER_SEARCH_PLACEHOLDER,
        searchLabel = RecipeMessages.PICKER_SEARCH_LABEL,
        searchIcon = {
            MueIcon(ActivityIcons.SEARCH, tint = MueTheme.colors.textTertiary, size = 18.dp)
        },
        closeContentDescription = RecipeMessages.PICKER_CLOSE,
        footer = {
            /*
             * PRD_FOOD 18: "l'ajout d'une ligne annonce le résultat sans voler le focus". A live
             * region says what was added; the focus stays in the list so the next ingredient is
             * one gesture away.
             */
            state.lastAdded?.let { announcement ->
                MueText(
                    text = "",
                    style = MueTheme.typography.micro,
                    modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    },
                )
            }

            MuePrimaryButton(
                label = state.doneLabel,
                onClick = onDismiss,
                modifier = Modifier
                    .padding(top = MueTheme.spacing.lg)
                    .testTag(RecipeTestTags.PICKER_DONE),
            )
        },
    ) {
        MuePickerSectionHeader(
            title = state.sectionTitle,
            modifier = Modifier.padding(top = MueTheme.spacing.md),
        )

        if (state.isEmpty) {
            MuePickerEmpty(
                message = RecipeMessages.PICKER_EMPTY,
                modifier = Modifier.padding(top = MueTheme.spacing.sm),
            )
        } else {
            MuePickerList(modifier = Modifier.padding(top = MueTheme.spacing.sm)) {
                state.results.forEachIndexed { index, row ->
                    MuePickerRow(
                        name = row.name,
                        meta = row.meta,
                        showDivider = index > 0,
                        onClick = { onPick(row.id) },
                        leading = {
                            MueIcon(
                                iconName = row.iconName,
                                tint = MueTheme.colors.onAccentSoft,
                                size = 18.dp,
                            )
                        },
                        modifier = Modifier.testTag(RecipeTestTags.pickerRow(row.id)),
                    )
                }
            }
        }
    }
}
