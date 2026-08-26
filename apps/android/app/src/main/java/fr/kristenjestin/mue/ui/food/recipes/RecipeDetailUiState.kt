package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat
import java.util.Locale

/**
 * What the card of one recipe draws (PRD_FOOD 11, FR-RECIPE-003, 004 and 006).
 *
 * **Every figure here comes out of [NutritionMath] and none of it is computed twice.** The
 * recipe's total is `recipeTotal(detail, foods)`, the `Per serving` block is `perServing(…)`,
 * the figures for the number of servings on screen are `recipeLine(perServing, servings)`, and
 * each ingredient quantity is `scaledIngredientQuantityOrNull(…)`. The strings are then
 * [FoodLabels]', so PRD_FOOD 13.1's rule — `null` is unknown, `0` is a measured zero, and no
 * conversion between the two exists — is proved once in the domain and cannot be undone here.
 *
 * Two absences are deliberate and both are the rule rather than an omission.
 *
 * **An orphan ingredient is normal.** PRD_FOOD 21.2 lets a recipe reference a food this device
 * has not received; the row is drawn from [RecipeIngredient.foodName], its contribution is
 * [Nutrients.UNKNOWN], and the strict sum therefore makes the whole recipe unknown. Every figure
 * reads `—`, and none of them reads `≈ 0`.
 *
 * **A recipe with no ingredient shows no total at all.** An empty strict sum is a *known* zero
 * (PRD_FOOD 13.1), so a block built from it would print `≈ 0 kcal` over a recipe nobody has
 * filled in — the invented total PRD_FOOD 10.4 forbids of an untouched day. PRD_FOOD 15 refuses
 * to save such a recipe in the first place, and
 * [fr.kristenjestin.mue.domain.logic.FoodValidation.validateIngredientCount] is where the editor
 * refuses it; the card simply declines to total nothing.
 */
@Immutable
internal data class RecipeDetailUiState(
    val recipeId: RecipeId? = null,
    val isLoading: Boolean = false,
    /** PRD_FOOD 17: deleted while its card was open, or an id from a stale stack. */
    val isMissing: Boolean = false,
    val name: String = "",
    val typeLabel: String = "",
    val description: String? = null,
    /** `Main`, `Serves 4`, `25 min` — the same facts the list card carries. */
    val facts: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    val favouriteLabel: String = RecipeMessages.ADD_FAVOURITE,
    val baseServings: Int = Recipe.BASE_SERVINGS_RANGE.first,
    /** FR-RECIPE-004: the number of servings the quantities on screen are written for. */
    val servings: Servings = Servings.ONE,
    val servingsLabel: String = "",
    val canAddServing: Boolean = false,
    val canRemoveServing: Boolean = false,
    val ingredients: List<RecipeIngredientUiState> = emptyList(),
    val steps: List<String> = emptyList(),
    /** PRD_FOOD 13.1's `valeur par portion`; null exactly while there is nothing to total. */
    val perServing: RecipeNutritionUiState? = null,
    /** The same values multiplied by [servings], which is PRD_FOOD 13.1's `ligne RECIPE`. */
    val forServings: RecipeNutritionUiState? = null,
    /** PRD_FOOD 21.2: at least one ingredient's food has not reached this device. */
    val hasOrphanIngredient: Boolean = false,
    val deletion: RecipeDeletionUiState = RecipeDeletionUiState.Idle,
) {

    val hasIngredients: Boolean get() = ingredients.isNotEmpty()

    val hasSteps: Boolean get() = steps.isNotEmpty()

    /** True once the row is gone; the screen leaves as soon as the freed moments are read. */
    val isDeleted: Boolean get() = deletion is RecipeDeletionUiState.Deleted

    /** FR-RECIPE-006: the moments the deletion freed, already worded (PRD_FOOD 17). */
    val freedPlans: List<String>
        get() = (deletion as? RecipeDeletionUiState.Deleted)?.freedPlans.orEmpty()

    val isConfirmingDelete: Boolean get() = deletion == RecipeDeletionUiState.Confirming

    val screenTitle: String get() = if (isMissing) RecipeMessages.MISSING_RECIPE else name

    companion object {

        fun of(
            detail: RecipeDetail?,
            foods: Map<FoodId, Food> = emptyMap(),
            servings: Servings? = null,
            isLoading: Boolean = false,
            deletion: RecipeDeletionUiState = RecipeDeletionUiState.Idle,
            recipeId: RecipeId? = detail?.id,
            /**
             * FR-FOOD-010: the preference that hides every energy and macronutrient of the
             * module. The ingredients, the steps and the servings counter all stay — "le reste du
             * module continue de fonctionner à l'identique" — and only the figures go.
             */
            showEnergy: Boolean = true,
        ): RecipeDetailUiState {
            if (detail == null) {
                return RecipeDetailUiState(
                    recipeId = recipeId,
                    isLoading = isLoading,
                    isMissing = !isLoading,
                    deletion = deletion,
                )
            }

            val recipe = detail.recipe
            val base = recipe.baseServings
            val chosen = servings ?: defaultServings(base)

            // PRD_FOOD 13.1, in the order that section writes it. Nothing below recomputes any
            // of these; they are read for their strings and for nothing else.
            val perServing = NutritionMath.perServing(detail, foods)
            val forChosen = NutritionMath.recipeLine(perServing, chosen)

            val facts = listOfNotNull(
                recipe.type.label,
                RecipeMessages.serves(base),
                RecipeMessages.prepTime(recipe.prepTimeMinutes),
            )
            val rows = detail.ingredients.map { ingredient ->
                RecipeIngredientUiState.of(
                    ingredient = ingredient,
                    food = foods[ingredient.foodId],
                    baseServings = base,
                    servings = chosen,
                    showEnergy = showEnergy,
                )
            }
            val totals = detail.hasIngredients && showEnergy

            return RecipeDetailUiState(
                recipeId = recipe.id,
                isLoading = isLoading,
                isMissing = false,
                name = recipe.name,
                typeLabel = recipe.type.label,
                description = recipe.description,
                facts = facts,
                isFavourite = recipe.isFavourite,
                favouriteLabel = if (recipe.isFavourite) {
                    RecipeMessages.REMOVE_FAVOURITE
                } else {
                    RecipeMessages.ADD_FAVOURITE
                },
                baseServings = base,
                servings = chosen,
                servingsLabel = RecipeFormat.servings(chosen),
                canAddServing = stepped(chosen, up = true) != null,
                canRemoveServing = stepped(chosen, up = false) != null,
                ingredients = rows,
                steps = recipe.steps,
                perServing = RecipeNutritionUiState
                    .of(RecipeMessages.PER_SERVING, perServing)
                    .takeIf { totals },
                forServings = RecipeNutritionUiState
                    .of(RecipeFormat.forServings(chosen), forChosen)
                    .takeIf { totals },
                hasOrphanIngredient = rows.any { it.isOrphan },
                deletion = deletion,
            )
        }

        /**
         * The count the card opens on: the number the recipe is written for.
         *
         * PRD_FOOD 15 bounds a serving count at 10 and a recipe at 12 servings, so a recipe for
         * eleven opens at ten. The bound is asked of
         * [fr.kristenjestin.mue.domain.logic.FoodValidation] rather than restated, and the count
         * is only ever a *display* choice — the stored quantities never move (FR-RECIPE-004).
         */
        fun defaultServings(baseServings: Int): Servings =
            servingsOrNull(baseServings.toDouble())
                ?: servingsOrNull(MAX_DISPLAY_SERVINGS)
                ?: Servings.ONE

        /**
         * One whole serving up or down, or null at either end of PRD_FOOD 15's range.
         *
         * Whole servings rather than the quarter step of PRD_FOOD 15: a card is read, and a
         * quarter of a portion belongs to *logging* one. Every value this produces is still a
         * multiple of that quarter and still inside the range, because the same validator
         * decides — nothing here restates a bound.
         */
        fun stepped(from: Servings, up: Boolean): Servings? {
            val moved = from.count + if (up) 1.0 else -1.0
            return servingsOrNull(moved)
        }

        private fun servingsOrNull(count: Double): Servings? =
            FoodValidation.validateConsumedServings(count).valueOrNull

        /** PRD_FOOD 15's ceiling for a serving count, read back from the validator. */
        private const val MAX_DISPLAY_SERVINGS: Double = 10.0
    }
}

/**
 * One ingredient of the card (PRD_FOOD 8.3), with the quantity rescaled to the servings on
 * screen (FR-RECIPE-004) and the contribution that quantity is worth (PRD_FOOD 13.1).
 *
 * [isOrphan] is not an error state. PRD_FOOD 21.2 requires the row to be shown from its
 * name-and-quantity snapshot when the food has not arrived, and the only consequence is that its
 * contribution — and therefore the recipe's total — is unknown.
 */
@Immutable
internal data class RecipeIngredientUiState(
    val id: String,
    val name: String,
    /** `260 g` for the servings currently chosen, or `—` when it cannot be represented. */
    val quantityLabel: String,
    /**
     * `≈ 541 kcal`, or `—` for an ingredient whose food is missing.
     *
     * Null is a third thing and not a fourth reading of the value: FR-FOOD-010 has hidden every
     * figure of the module, so there is nothing to draw here at all — the row keeps its name and
     * its quantity.
     */
    val energyLabel: String?,
    val isOrphan: Boolean,
    val description: String,
) {
    companion object {

        fun of(
            ingredient: RecipeIngredient,
            food: Food?,
            baseServings: Int,
            servings: Servings,
            showEnergy: Boolean = true,
        ): RecipeIngredientUiState {
            val scaled = NutritionMath.scaledIngredientQuantityOrNull(
                quantity = ingredient.quantity,
                baseServings = baseServings,
                servings = servings,
            )
            /*
             * The contribution of the quantity actually on screen, so the figures beside a row
             * and the row's own weight can never disagree. A missing food or an unrepresentable
             * quantity is unknown rather than zero — which is `ingredientContribution`'s own
             * rule, restated here only in the choice of which quantity to scale by.
             */
            val contribution = if (food == null || scaled == null) {
                Nutrients.UNKNOWN
            } else {
                NutritionMath.contribution(food.per100, scaled)
            }
            val name = food?.name
                ?: ingredient.foodName?.takeIf { it.isNotBlank() }
                ?: RecipeMessages.UNNAMED_INGREDIENT
            val quantity = FoodLabels.quantity(scaled, ingredient.unit)
            val energy = FoodLabels.energy(contribution.energy).takeIf { showEnergy }

            return RecipeIngredientUiState(
                id = ingredient.id.value,
                name = name,
                quantityLabel = quantity,
                energyLabel = energy,
                isOrphan = food == null,
                description = FoodDayFormat.sentence(
                    name,
                    quantity,
                    energy?.let(FoodDayFormat::spoken),
                    if (food == null) RecipeMessages.ORPHAN_INGREDIENT else null,
                ),
            )
        }
    }
}

/**
 * Where a deletion has got to (FR-RECIPE-006).
 *
 * [Deleted] carries the moments the deletion freed, already worded. `RecipeRepository.delete`
 * returns the `MealPlanKey`s that referenced the recipe and they are not decoration: PRD_FOOD 17
 * requires that "le moment est libéré **et signalé**", and a proposal whose recipe is gone is a
 * slot the person can go and fill in. Naming them is the whole point — `2 meal plans` is not
 * something anyone can act on.
 */
internal sealed interface RecipeDeletionUiState {

    data object Idle : RecipeDeletionUiState

    /** FR-RECIPE-006: "la suppression demande confirmation". */
    data object Confirming : RecipeDeletionUiState

    data class Deleted(val freedPlans: List<String> = emptyList()) : RecipeDeletionUiState

    companion object {

        /** The freed keys as PRD_FOOD 17 words them, in the order the repository returned. */
        fun deleted(freed: List<MealPlanKey>, locale: Locale = Locale.getDefault()): Deleted =
            Deleted(freed.map { RecipeFormat.planLabel(it, locale) })
    }
}
