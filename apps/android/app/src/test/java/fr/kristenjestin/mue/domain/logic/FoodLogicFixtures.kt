package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import java.time.LocalDate
import java.time.LocalTime

/**
 * Builders for the Food logic tests. Identifiers are fixed rather than random, so two fixtures
 * built the same way compare equal, exactly as the activity fixtures already do.
 *
 * Every builder goes through the real factories of the domain contract: a fixture that could not
 * exist in the application cannot exist in a test either.
 */

fun quantityOf(amount: Double): Quantity =
    requireNotNull(Quantity.ofAmountOrNull(amount)) { "$amount is not a quantity" }

fun kcalOf(kilocalories: Double): Energy =
    requireNotNull(Energy.ofKilocaloriesOrNull(kilocalories)) { "$kilocalories kcal is out of range" }

fun macroOf(grams: Double): Macro =
    requireNotNull(Macro.ofGramsOrNull(grams)) { "$grams g is out of range" }

fun ratioOf(ratio: Double): CookedRatio =
    requireNotNull(CookedRatio.ofRatioOrNull(ratio)) { "$ratio is not a cooking ratio" }

fun servingsOf(count: Double): Servings =
    requireNotNull(Servings.ofCountOrNull(count)) { "$count servings is out of range" }

/** A per-100 bundle where every omitted metric stays unknown, never zero. */
fun per100(
    energy: Double? = null,
    protein: Double? = null,
    carbs: Double? = null,
    fat: Double? = null,
    fibre: Double? = null,
): Nutrients = Nutrients(
    energy = energy?.let(::kcalOf),
    protein = protein?.let(::macroOf),
    carbs = carbs?.let(::macroOf),
    fat = fat?.let(::macroOf),
    fibre = fibre?.let(::macroOf),
)

fun foodOf(
    name: String = "Chicken breast",
    per100: Nutrients = per100(energy = 165.0, protein = 31.0),
    cookedRatio: CookedRatio? = null,
    servingLabel: String? = null,
    servingSize: Quantity? = null,
    referenceUnit: ReferenceUnit = ReferenceUnit.GRAM,
    source: FoodSource = FoodSource.CUSTOM,
    cookedLabel: String = Food.DEFAULT_COOKED_LABEL,
    id: String = "food-1",
): Food = Food(
    id = FoodId(id),
    name = name,
    source = source,
    referenceUnit = referenceUnit,
    per100 = per100,
    servingLabel = servingLabel,
    servingSize = servingSize,
    cookedRatio = cookedRatio,
    cookedLabel = cookedLabel,
)

/** The chicken breast of PRD_FOOD 8.6 and of the acceptance criterion of PRD_FOOD 22. */
fun chickenBreast(): Food = foodOf(
    name = "Chicken breast",
    per100 = per100(energy = 165.0, protein = 31.0),
    cookedRatio = ratioOf(0.72),
    id = "food-chicken",
)

/** The dry wholemeal pasta of PRD_FOOD 8.6, which absorbs water instead of losing it. */
fun dryPasta(): Food = foodOf(
    name = "Wholemeal pasta, dry",
    per100 = per100(energy = 350.0, protein = 13.0, carbs = 65.0),
    cookedRatio = ratioOf(2.3),
    id = "food-pasta",
)

fun ingredientOf(
    foodId: String,
    amount: Double,
    position: Int = 0,
    unit: ReferenceUnit = ReferenceUnit.GRAM,
    foodName: String? = null,
): RecipeIngredient = RecipeIngredient(
    id = RecipeIngredientId("ingredient-$foodId-$position"),
    foodId = FoodId(foodId),
    quantity = quantityOf(amount),
    unit = unit,
    position = position,
    foodName = foodName,
)

fun recipeOf(
    name: String = "Rice and oil",
    baseServings: Int = 4,
    type: RecipeType = RecipeType.MAIN,
    id: String = "recipe-1",
): Recipe = Recipe(
    id = RecipeId(id),
    name = name,
    type = type,
    baseServings = baseServings,
)

fun recipeDetailOf(
    ingredients: List<RecipeIngredient> = emptyList(),
    baseServings: Int = 4,
    name: String = "Rice and oil",
    id: String = "recipe-1",
): RecipeDetail = RecipeDetail(
    recipe = recipeOf(name = name, baseServings = baseServings, id = id),
    ingredients = ingredients,
)

fun catalogueOf(vararg foods: Food): Map<FoodId, Food> = foods.associateBy { it.id }

fun logEntryOf(
    isoDate: String = "2026-08-19",
    at: String = "13:00",
    slot: MealSlot = MealSlot.forTime(LocalTime.parse(at)),
    kind: FoodLogKind = FoodLogKind.FOOD,
    title: String = "Chicken breast",
    nutrients: Nutrients = per100(energy = 200.0, protein = 20.0),
    amount: LoggedAmount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM),
    estimation: Estimation = Estimation.MEASURED,
    weighedCooked: Boolean = false,
    portions: Servings? = null,
    id: String = "entry-$isoDate-$at",
): FoodLogEntry = FoodLogEntry(
    id = FoodLogEntryId(id),
    consumedOn = LocalDate.parse(isoDate),
    consumedAt = LocalTime.parse(at),
    slot = slot,
    kind = kind,
    title = title,
    amount = amount,
    nutrients = nutrients,
    estimation = estimation,
    portions = portions,
    weighedCooked = weighedCooked,
)

fun planOf(
    isoDate: String = "2026-08-19",
    slot: MealSlot = MealSlot.LUNCH,
    recipeId: String = "recipe-1",
    servings: Double = 1.0,
    consumedLogEntryId: String? = null,
): MealPlanEntry = MealPlanEntry(
    plannedOn = LocalDate.parse(isoDate),
    slot = slot,
    recipeId = RecipeId(recipeId),
    plannedServings = servingsOf(servings),
    consumedLogEntryId = consumedLogEntryId?.let(::FoodLogEntryId),
)
