package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat

/**
 * The recipe form (PRD_FOOD 11, FR-RECIPE-001 to 003).
 *
 * **Every bound is asked of [FoodValidation] and none of them is restated.** The name, the base
 * servings, the preparation time, the steps, each ingredient quantity and the *number* of
 * ingredients each come back from a validator with the exact sentence PRD_FOOD 15 wants beside
 * the field. Nothing in this file knows that a recipe serves at most twelve or that a step is at
 * most 500 characters.
 *
 * The live `Per serving` block of PRD_FOOD 11 is [NutritionMath]'s, computed from the rows that
 * currently parse. A row whose quantity has not been typed yet contributes [Nutrients.UNKNOWN]
 * rather than nothing at all, so the block reads `—` until the form is complete instead of
 * quietly totalling half a recipe — and a form with no ingredient shows **no block at all**,
 * because an empty strict sum is a known zero and `≈ 0 kcal` over an empty form would be a
 * number nobody typed.
 *
 * Errors are held back until a save has been attempted ([showErrors]). PRD_FOOD 15 asks that a
 * refused value be signalled beside its field and that the form never be emptied; it does not
 * ask that an empty name be an error before anyone has finished typing it.
 */
@Immutable
internal data class RecipeEditorUiState(
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** True for the beat the save button discharges on, as on `Log activity`. */
    val justSaved: Boolean = false,
    val name: String = "",
    val nameError: String? = null,
    val type: RecipeType = RecipeType.MAIN,
    val baseServings: String = RecipeDraft.DEFAULT_BASE_SERVINGS,
    val baseServingsError: String? = null,
    /**
     * Whether the base-servings stepper may move each way (PRD_FOOD 15).
     *
     * Both are `RecipeDraft.canStepBaseServings`, which asks
     * [fr.kristenjestin.mue.domain.logic.FoodValidation.validateBaseServings] whether the
     * neighbouring number is still a legal count. The 1 and the 12 appear nowhere on this side.
     */
    val canAddBaseServing: Boolean = true,
    val canRemoveBaseServing: Boolean = true,
    val description: String = "",
    val prepTime: String = "",
    val prepTimeError: String? = null,
    val steps: String = "",
    val stepsError: String? = null,
    val ingredients: List<RecipeEditorIngredientUiState> = emptyList(),
    /** PRD_FOOD 15: "une recette sans ingredient ne peut pas etre enregistree". */
    val ingredientCountError: String? = null,
    /** FR-RECIPE-003: recomputed at every keystroke; null while there is nothing to total. */
    val perServing: RecipeNutritionUiState? = null,
    val picker: RecipePickerUiState = RecipePickerUiState(),
) {

    val screenTitle: String
        get() = if (isEditing) RecipeMessages.EDIT_TITLE else RecipeMessages.CREATE_TITLE

    val saveLabel: String
        get() = if (isEditing) RecipeMessages.SAVE_CHANGES else RecipeMessages.SAVE_RECIPE

    /** True when at least one field is currently being refused. */
    val hasError: Boolean
        get() = nameError != null ||
            baseServingsError != null ||
            prepTimeError != null ||
            stepsError != null ||
            ingredientCountError != null ||
            ingredients.any { it.quantityError != null }

    companion object {

        /**
         * The whole form from one draft and the catalogue its rows name.
         *
         * [showErrors] gates only what is *shown*: every validator runs either way, because the
         * `Per serving` block is built from the rows that parse and a save reads the same
         * answers.
         */
        fun of(
            draft: RecipeDraft,
            foods: Map<FoodId, Food> = emptyMap(),
            showErrors: Boolean = false,
            isLoading: Boolean = false,
            isSaving: Boolean = false,
            justSaved: Boolean = false,
            picker: RecipePickerUiState = RecipePickerUiState(),
            /**
             * FR-FOOD-010: the preference that hides every figure of the module. Every field of
             * the form stays exactly where it was — "le reste du module continue de fonctionner à
             * l'identique" — and only the values go.
             */
            showEnergy: Boolean = true,
        ): RecipeEditorUiState {
            val baseServings = FoodValidation.validateBaseServings(draft.baseServings)
            val rows = draft.ingredients.map { row ->
                RecipeEditorIngredientUiState.of(
                    row = row,
                    food = foods[FoodId(row.foodId)],
                    showErrors = showErrors,
                    showEnergy = showEnergy,
                )
            }

            /*
             * PRD_FOOD 13.1, over what the form currently holds. A row that does not parse is an
             * unknown contribution and not a missing one, which is what keeps the block honest
             * while a quantity is still being typed.
             */
            val contributions = draft.ingredients.map { row ->
                contributionOf(row, foods[FoodId(row.foodId)])
            }
            val perServing = NutritionMath.perServing(
                recipeTotal = NutritionMath.recipeTotal(contributions),
                baseServings = baseServings.valueOrNull ?: 0,
            )

            return RecipeEditorUiState(
                isEditing = draft.isEditing,
                isLoading = isLoading,
                isSaving = isSaving,
                justSaved = justSaved,
                name = draft.name,
                nameError = FoodValidation.validateName(draft.name).errorMessage.orNull(showErrors),
                type = draft.type,
                baseServings = draft.baseServings,
                baseServingsError = baseServings.errorMessage.orNull(showErrors),
                canAddBaseServing = draft.canStepBaseServings(up = true),
                canRemoveBaseServing = draft.canStepBaseServings(up = false),
                description = draft.description,
                prepTime = draft.prepTimeMinutes,
                prepTimeError = FoodValidation.validatePrepTime(draft.prepTimeMinutes)
                    .errorMessage.orNull(showErrors),
                steps = draft.steps,
                stepsError = FoodValidation.validateSteps(draft.steps)
                    .errorMessage.orNull(showErrors),
                ingredients = rows,
                ingredientCountError = FoodValidation
                    .validateIngredientCount(draft.ingredients.size)
                    .errorMessage.orNull(showErrors),
                perServing = RecipeNutritionUiState
                    .of(RecipeMessages.PER_SERVING, perServing)
                    .takeIf { draft.ingredients.isNotEmpty() && showEnergy },
                picker = picker,
            )
        }

        /**
         * What one row of the form is worth for the whole recipe (PRD_FOOD 8.3 and 13.1).
         *
         * Unknown in three cases that all mean the same thing — nobody has said yet: the food has
         * not reached this device (PRD_FOOD 21.2), the quantity has not been typed, or what was
         * typed is not a quantity. None of the three is a zero.
         */
        internal fun contributionOf(row: RecipeIngredientDraft, food: Food?): Nutrients {
            val quantity: Quantity? =
                FoodValidation.validateIngredientQuantity(row.quantity).valueOrNull
            if (food == null || quantity == null) return Nutrients.UNKNOWN
            return NutritionMath.contribution(food.per100, quantity)
        }

        private fun String?.orNull(showErrors: Boolean): String? = if (showErrors) this else null
    }
}

/**
 * One ingredient row of the form: the food it names, the quantity typed for the whole recipe,
 * and what that quantity is worth (PRD_FOOD 11).
 *
 * [isOrphan] is the ordinary case of PRD_FOOD 21.2 rather than a mistake: a recipe received from
 * the server may name a food this device has not got, and the row keeps its snapshot name and
 * its quantity while its contribution stays unknown. The row is still editable and the recipe is
 * still saveable — refusing to save would lose the very ingredient the snapshot exists to keep.
 */
@Immutable
internal data class RecipeEditorIngredientUiState(
    val id: String,
    val name: String,
    /** `g` or `ml`, taken from the food's own reference unit (PRD_FOOD 8.6). */
    val unitSymbol: String,
    val quantity: String,
    val quantityError: String?,
    /**
     * `≈ 541 kcal` once the quantity parses, `—` until then (PRD_FOOD 11).
     *
     * Null when FR-FOOD-010 has hidden the figures, which is a third thing rather than a fourth
     * reading of the value: there is nothing to draw at all, and the row keeps its name.
     */
    val energyLabel: String?,
    val isOrphan: Boolean,
    val removeLabel: String,
    val description: String,
) {
    companion object {

        fun of(
            row: RecipeIngredientDraft,
            food: Food?,
            showErrors: Boolean,
            showEnergy: Boolean = true,
        ): RecipeEditorIngredientUiState {
            val quantity = FoodValidation.validateIngredientQuantity(row.quantity)
            val contribution = RecipeEditorUiState.contributionOf(row, food)
            val name = food?.name ?: row.foodName
            val energy = FoodLabels.energy(contribution.energy).takeIf { showEnergy }
            return RecipeEditorIngredientUiState(
                id = row.id,
                name = name,
                unitSymbol = row.unit.symbol,
                quantity = row.quantity,
                quantityError = if (showErrors) quantity.errorMessage else null,
                energyLabel = energy,
                isOrphan = food == null,
                removeLabel = RecipeMessages.removeIngredient(name),
                description = FoodDayFormat.sentence(
                    name,
                    energy?.let(FoodDayFormat::spoken),
                    if (food == null) RecipeMessages.ORPHAN_INGREDIENT else null,
                ),
            )
        }
    }
}

/**
 * The ingredient picker (PRD_FOOD 11).
 *
 * [addedCount] is why this carries state at all: **picking a food does not close the sheet**. A
 * recipe is several foods at once, and a picker that dismissed on the first selection would have
 * to be reopened for every ingredient — the complaint the activity module's exercise picker
 * earned. So the sheet stays, counts what it has added, and is closed on purpose.
 */
@Immutable
internal data class RecipePickerUiState(
    val visible: Boolean = false,
    val query: String = "",
    val results: List<RecipePickerRowUiState> = emptyList(),
    val addedCount: Int = 0,
    /** PRD_FOOD 18: what a screen reader hears when a row is added, without the focus moving. */
    val lastAdded: String? = null,
    val isLoading: Boolean = false,
) {
    val sectionTitle: String
        get() = if (query.isBlank()) RecipeMessages.PICKER_RECENT else RecipeMessages.PICKER_RESULTS

    val doneLabel: String get() = RecipeMessages.addedCount(addedCount)

    val isEmpty: Boolean get() = !isLoading && results.isEmpty()
}

/** One food offered by the picker. Choosing it adds a row; choosing it twice adds two. */
@Immutable
internal data class RecipePickerRowUiState(
    val id: String,
    val name: String,
    /** The brand and the provenance, which is what tells two similar entries apart. */
    val meta: String?,
    val iconName: String,
)
