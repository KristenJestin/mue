package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Assert.assertTrue

/**
 * The recipe card's state (PRD_FOOD 11, 13.1, 21.2 and FR-RECIPE-004).
 *
 * Every expected string is asked of [FoodLabels] or of [NutritionMath] rather than spelled out,
 * so a rule that changes cannot leave a test agreeing with a copy of itself. The one thing that
 * *is* spelled out is the dash, because the point of half of these tests is that a particular
 * figure is a dash and not a zero.
 */
class RecipeDetailUiStateTest {

    // region an orphan ingredient (PRD_FOOD 21.2 and 13.1)

    /**
     * **The test the whole half of the module turns on.**
     *
     * PRD_FOOD 21.2 lets a recipe reference a food this device has not received; the ingredient
     * is drawn by its `foodName` snapshot and its contribution is unknown. PRD_FOOD 13.1 then
     * makes the *whole* recipe unknown, because a strict sum is null as soon as one contribution
     * is — so every figure on the card reads `—`. Not one of them reads `≈ 0`, which is what a
     * `?: 0` anywhere between the domain and the glass would have produced.
     */
    @Test
    fun `an orphan ingredient makes every figure unknown and never zero`() {
        val state = orphanRecipeDetailState()

        val lentils = state.ingredients.first { it.isOrphan }
        assertEquals(RecipePreviewData.ORPHAN_SNAPSHOT, lentils.name)
        assertEquals(FoodLabels.UNKNOWN, lentils.energyLabel)
        assertTrue(state.hasOrphanIngredient)

        val perServing = assertNotNull(state.perServing)
        val forServings = assertNotNull(state.forServings)
        val drawn = listOf(perServing, forServings).flatMap { block ->
            listOf(block.energyLabel) + block.macros.map { it.value }
        }

        assertTrue(
            "a figure of a recipe with an unknown ingredient was drawn: $drawn",
            drawn.all { it == FoodLabels.UNKNOWN },
        )
        assertTrue(
            "an unknown was rendered as a zero: $drawn",
            drawn.none { it.contains("0") },
        )
    }

    /** PRD_FOOD 21.2: the row is *shown*, by its snapshot, and its quantity is still readable. */
    @Test
    fun `an orphan ingredient keeps its name and its quantity`() {
        val lentils = orphanRecipeDetailState().ingredients.first { it.isOrphan }

        assertEquals(RecipePreviewData.ORPHAN_SNAPSHOT, lentils.name)
        assertEquals("300 g", lentils.quantityLabel)
        assertTrue(lentils.description.contains(RecipeMessages.ORPHAN_INGREDIENT))
    }

    /**
     * The other half of PRD_FOOD 13.1, on the same object: the ingredient that *is* known keeps a
     * number, even though the recipe's total is unknown.
     */
    @Test
    fun `a known ingredient of an unknown recipe keeps its own figure`() {
        val coconut = orphanRecipeDetailState().ingredients.first { !it.isOrphan }

        assertFalse(coconut.isOrphan)
        assertTrue(
            "a resolved ingredient lost its energy: ${coconut.energyLabel}",
            assertNotNull(coconut.energyLabel).startsWith(FoodLabels.APPROXIMATE_PREFIX),
        )
    }

    // endregion

    // region nothing to total (PRD_FOOD 13.1 and 15)

    /**
     * PRD_FOOD 13.1: `strictSum(emptyList())` is a **known** zero, so a recipe with no ingredient
     * would read `≈ 0 kcal` if the card totalled it. It shows no block at all instead — the same
     * distinction the `Day` screen draws between a moment with nothing in it and a moment whose
     * value is unknown.
     */
    @Test
    fun `a recipe with no ingredient shows no total at all`() {
        val state = emptyRecipeDetailState()

        assertFalse(state.hasIngredients)
        assertNull(state.perServing)
        assertNull(state.forServings)
    }

    /** And the domain agrees about why: the empty sum really is a zero, not an unknown. */
    @Test
    fun `the empty sum this guards against really is a known zero`() {
        val total = NutritionMath.recipeTotal(RecipePreviewData.emptyRecipe(), emptyMap())

        assertEquals("≈ 0 kcal", FoodLabels.energy(total.energy))
    }

    // endregion

    // region servings (PRD_FOOD FR-RECIPE-004)

    /** The card opens on the number the recipe is written for (PRD_FOOD 8.3). */
    @Test
    fun `the card opens on the recipe's own servings`() {
        val state = previewRecipeDetailState()

        assertEquals(RecipePreviewData.salmon().recipe.baseServings, state.baseServings)
        assertEquals(state.baseServings.toDouble(), state.servings.count)
    }

    /**
     * PRD_FOOD 15 stops a serving count at ten and a recipe at twelve servings, so a recipe for
     * eleven opens at the count the validator allows rather than at one it does not.
     */
    @Test
    fun `a recipe for more servings than a count allows opens at the ceiling`() {
        val servings = RecipeDetailUiState.defaultServings(baseServings = 12)

        assertEquals(10.0, servings.count)
    }

    /** FR-RECIPE-004: the quantities follow the count, and the per-serving values do not. */
    @Test
    fun `doubling the servings doubles the ingredients and leaves a serving alone`() {
        val detail = RecipePreviewData.salmon()
        val foods = RecipePreviewData.catalogueById()
        val base = detail.recipe.baseServings

        val atBase = RecipeDetailUiState.of(detail, foods)
        val doubled = RecipeDetailUiState.of(
            detail = detail,
            foods = foods,
            servings = requireNotNull(Servings.ofConsumedOrNull(base * 2.0)),
        )

        val expected = NutritionMath.scaledIngredientQuantityOrNull(
            quantity = detail.ingredients.first().quantity,
            baseServings = base,
            servings = requireNotNull(Servings.ofConsumedOrNull(base * 2.0)),
        )
        assertEquals(
            FoodLabels.quantity(expected, detail.ingredients.first().unit),
            doubled.ingredients.first().quantityLabel,
        )
        // `Per serving` is a division by `baseServings` and knows nothing of what is displayed.
        assertEquals(atBase.perServing?.energyLabel, doubled.perServing?.energyLabel)
        // The figures for the count on screen do move, because they are the per-serving line.
        assertTrue(atBase.forServings?.energyLabel != doubled.forServings?.energyLabel)
    }

    /** The step is asked of `FoodValidation`, so both ends of PRD_FOOD 15's range are its own. */
    @Test
    fun `the servings step stops where the validator does`() {
        val one = requireNotNull(Servings.ofConsumedOrNull(1.0))
        val ten = requireNotNull(Servings.ofConsumedOrNull(10.0))

        assertNull(RecipeDetailUiState.stepped(one, up = false))
        assertNull(RecipeDetailUiState.stepped(ten, up = true))
        assertEquals(2.0, RecipeDetailUiState.stepped(one, up = true)?.count)
    }

    /** FR-RECIPE-004: what the figures for the chosen count are is `NutritionMath`'s answer. */
    @Test
    fun `the figures on screen are the per-serving line for that count`() {
        val detail = RecipePreviewData.salmon()
        val foods = RecipePreviewData.catalogueById()
        val three = requireNotNull(Servings.ofConsumedOrNull(3.0))

        val state = RecipeDetailUiState.of(detail, foods, servings = three)

        val expected = NutritionMath.recipeLine(NutritionMath.perServing(detail, foods), three)
        assertEquals(FoodLabels.energy(expected.energy), state.forServings?.energyLabel)
    }

    // endregion

    // region what the card says about itself

    @Test
    fun `a missing recipe says so rather than drawing an empty card`() {
        val state = RecipeDetailUiState.of(detail = null)

        assertTrue(state.isMissing)
        assertEquals(RecipeMessages.MISSING_RECIPE, state.screenTitle)
    }

    @Test
    fun `a card still being read is not a card that is missing`() {
        val state = RecipeDetailUiState.of(detail = null, isLoading = true)

        assertFalse(state.isMissing)
        assertTrue(state.isLoading)
    }

    @Test
    fun `the steps and the facts are the recipe's own`() {
        val state = previewRecipeDetailState()
        val recipe = RecipePreviewData.salmon().recipe

        assertEquals(recipe.steps, state.steps)
        assertTrue(state.facts.contains(RecipeMessages.serves(recipe.baseServings)))
        assertTrue(state.facts.contains(recipe.type.label))
    }

    // endregion

    // region the moments a deletion frees (FR-RECIPE-006 and PRD_FOOD 17)

    /** The keys `RecipeRepository.delete` returns become sentences a person can act on. */
    @Test
    fun `freed proposals are named by their date and their moment`() {
        val keys = listOf(
            MealPlanKey(LocalDate.of(2026, 9, 1), MealSlot.LUNCH),
            MealPlanKey(LocalDate.of(2026, 9, 3), MealSlot.DINNER),
        )

        val deleted = RecipeDeletionUiState.deleted(keys, Locale.UK)

        assertEquals(2, deleted.freedPlans.size)
        assertTrue(deleted.freedPlans.first().contains(MealSlot.LUNCH.label))
        assertTrue(deleted.freedPlans.first().contains("2026"))
        assertTrue(deleted.freedPlans.last().contains(MealSlot.DINNER.label))
    }

    @Test
    fun `a deletion that freed nothing has nothing to name`() {
        val deleted = RecipeDeletionUiState.deleted(emptyList(), Locale.UK)

        assertTrue(deleted.freedPlans.isEmpty())
    }

    // endregion
}
