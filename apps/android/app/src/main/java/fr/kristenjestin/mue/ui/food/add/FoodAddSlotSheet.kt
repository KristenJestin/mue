package fr.kristenjestin.mue.ui.food.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * The override on FR-FOOD-007's derived moment: the six moments and their hours, one tap each.
 *
 * ## Why the choice lives in a panel and not on the form
 *
 * The moment is not a question the sheet asks. The hour decides it — PRD_FOOD 10.3's windows are
 * already in the domain — and a control that asks for it beside the clock is the same fact entered
 * twice, which is the owner's report word for word: *"je définis mon heure de bouffer, le système
 * a déjà en mémoire les plages"*. Six moments would have made that grid three rows tall.
 *
 * But removing the choice altogether would **forbid a real meal**. PRD_FOOD 10.3 says the windows
 * "ne créent aucune contrainte : elles ne font que choisir la valeur par défaut", and the case is
 * the ordinary one rather than an exotic one: *"y a un monde où je vais manger à 11h30 ou 15h mon
 * repas de midi"*. So the choice is here, one tap from the quiet line that names the moment, and
 * closes the instant it is made.
 *
 * ## One column, not a grid
 *
 * `Morning snack` and `Evening snack` are two words each, and the six of them across two columns
 * on 360 dp is where a name gets cut in half at a doubled font scale — the defect the tab bar and
 * the proposal's actions were both fixed for. A full-width row per moment gives every name the
 * whole gutter-to-gutter width, so nothing is measured, nothing is dropped and nothing is
 * ellipsised: a long name simply makes a taller row.
 *
 * `MueChoiceCard` carries the selection three ways — fill, outline and the `selectable` node —
 * because PRD 15 and PRD_FOOD 18 both refuse to let colour carry it alone.
 */
@Composable
internal fun FoodAddSlotSheet(
    visible: Boolean,
    slots: List<FoodAddSlotUiState>,
    onDismiss: () -> Unit,
    onSelect: (MealSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    MueBottomSheet(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(FoodTestTags.SLOT_SHEET),
        title = FoodAddMessages.SLOT_SHEET_TITLE,
        scrimContentDescription = FoodAddMessages.CLOSE_SLOT_SHEET,
        // Six rows at the largest font size are taller than the panel's cap, and a moment that
        // cannot be reached is a moment that cannot be chosen.
        bodyScrolls = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.SLOT_PICKER),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
        ) {
            slots.forEach { option ->
                MueChoiceCard(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelect(option.slot) },
                    /*
                     * PRD_FOOD 10.3's window, on the thing being chosen — and, while planning,
                     * whether that moment is already spoken for (PRD_FOOD 8.5).
                     *
                     * A moment is not a word anyone can define by looking at it, and its hours
                     * are the definition. `MueChoiceCard` never caps a description, so at the
                     * largest font size `05:00 – 10:00 · Already suggested` wraps and the row
                     * grows rather than the hours being cut — half a window is a wrong window.
                     *
                     * This is also where the shape of a planned day is read. Six moments is more
                     * than a planning screen can usefully draw at once, so rather than a grid of
                     * six proposals somewhere else, the panel that already lists the six says
                     * which of them are taken — one column, one tap, no second screen.
                     */
                    description = option.description,
                    icon = {
                        MueIcon(
                            iconName = option.iconName,
                            tint = if (option.selected) {
                                MueTheme.colors.onAccentSoft
                            } else {
                                MueTheme.colors.textTertiary
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
