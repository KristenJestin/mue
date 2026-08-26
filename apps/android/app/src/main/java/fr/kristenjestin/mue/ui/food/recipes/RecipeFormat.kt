package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat
import java.util.Locale

/**
 * How the recipe screens word what [FoodLabels] has already rendered (PRD_FOOD 13.2 and 18).
 *
 * Nothing here formats a number and nothing here adds one up. Every energy, macronutrient and
 * quantity arrives from [FoodLabels], every total from
 * [fr.kristenjestin.mue.domain.logic.NutritionMath]; this object decides which strings sit
 * beside which and what a screen reader hears instead of the glyphs.
 *
 * [FoodDayFormat] is reused rather than copied for the two rules that are the module's and not
 * one screen's: `spoken`, which turns a drawn dash into the word "unknown" and the `≈` into
 * "about" (PRD_FOOD 18), and `sentence`, which joins the facts of one announcement. A second
 * copy of either would be a second chance for an unknown to be announced as nothing.
 */
internal object RecipeFormat {

    /** The four macronutrients of PRD_FOOD 8.2, in the order that section lists them. */
    val MACRO_NAMES: List<String> = listOf("Protein", "Carbs", "Fat", "Fibre")

    /** `≈ 369 kcal`, or `—` when the value is not known (PRD_FOOD 13.2). */
    fun energy(nutrients: Nutrients): String = FoodLabels.energy(nutrients.energy)

    /**
     * The four macronutrients as label-and-value pairs, unknowns included.
     *
     * [FoodLabels.macros] renders all four — an unknown one reads `—` and is not dropped —
     * because PRD_FOOD 22 requires an unknown energy to leave the other metrics of a total
     * readable, and a metric that vanished could not be observed at all.
     */
    fun macros(nutrients: Nutrients): List<RecipeMacroUiState> =
        MACRO_NAMES.zip(FoodLabels.macros(nutrients)) { name, value ->
            RecipeMacroUiState(name = name, value = value)
        }

    /** PRD_FOOD 18: a whole nutrition block heard as one sentence rather than eight fragments. */
    fun blockDescription(title: String, nutrients: Nutrients): String = FoodDayFormat.sentence(
        title,
        FoodDayFormat.spoken(energy(nutrients)),
        *macros(nutrients).map { "${FoodDayFormat.spoken(it.value)} ${it.name}" }.toTypedArray(),
    )

    /** A count of servings with its noun — `1 serving`, `2.5 servings`. */
    fun servings(count: Servings): String {
        val digits = FoodLabels.servings(count)
        return RecipeMessages.servings(digits, plural = count != Servings.ONE)
    }

    /** FR-RECIPE-004: `For 3 servings`, the heading of the figures currently on screen. */
    fun forServings(count: Servings): String {
        val digits = FoodLabels.servings(count)
        return RecipeMessages.forServings(digits, plural = count != Servings.ONE)
    }

    /**
     * A moment a deletion has freed — `Sep 1, 2026, Lunch` (PRD_FOOD 17 and FR-RECIPE-006).
     *
     * The date is localised through [FoodDayFormat.date], which is the module's one date
     * formatter, so a freed moment reads the way the day it belongs to does.
     */
    fun planLabel(key: MealPlanKey, locale: Locale = Locale.getDefault()): String =
        FoodDayFormat.sentence(FoodDayFormat.date(key.plannedOn, locale), key.slot.label)
}

/** One macronutrient of a nutrition block: its noun, and what [FoodLabels] drew for it. */
@Immutable
internal data class RecipeMacroUiState(val name: String, val value: String)

/**
 * A rendered nutrition block — the `Per serving` of PRD_FOOD 11, or the figures for the number
 * of servings currently chosen (FR-RECIPE-004).
 *
 * Every string in it is already drawn, which is what makes PRD_FOOD 13.1's rule provable on the
 * JVM: an unknown metric is `—` here and can no longer become a `0` between this object and the
 * glass.
 */
@Immutable
internal data class RecipeNutritionUiState(
    val title: String,
    val energyLabel: String,
    val macros: List<RecipeMacroUiState>,
    /** PRD_FOOD 18: the whole block as one announcement. */
    val description: String,
) {
    companion object {
        fun of(title: String, nutrients: Nutrients): RecipeNutritionUiState =
            RecipeNutritionUiState(
                title = title,
                energyLabel = RecipeFormat.energy(nutrients),
                macros = RecipeFormat.macros(nutrients),
                description = RecipeFormat.blockDescription(title, nutrients),
            )
    }
}
