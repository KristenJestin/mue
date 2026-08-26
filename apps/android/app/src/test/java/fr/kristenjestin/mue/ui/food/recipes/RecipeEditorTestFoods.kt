package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients

/**
 * Two catalogue entries the form's own tests build with, kept apart from [RecipePreviewData]
 * because they exist for arithmetic rather than for a picture.
 *
 * [SKYR] is chosen so the numbers divide exactly: 200 g of a 63 kcal per 100 g food is 126 kcal
 * for the whole dish, and 63 kcal for each of two servings. A figure that needs no rounding is a
 * figure a failure message can be read straight off.
 */
internal object RecipeEditorTestFoods {

    val SKYR: Food = Food(
        id = FoodId("test-food-skyr"),
        name = "Skyr, plain",
        source = FoodSource.CIQUAL,
        per100 = Nutrients(
            energy = Energy.ofPer100OrNull(63.0),
            protein = Macro.ofPer100OrNull(11.0),
        ),
    )

    val OATS: Food = Food(
        id = FoodId("test-food-oats"),
        name = "Oats, rolled",
        source = FoodSource.CUSTOM,
        per100 = Nutrients(energy = Energy.ofPer100OrNull(370.0)),
    )
}
