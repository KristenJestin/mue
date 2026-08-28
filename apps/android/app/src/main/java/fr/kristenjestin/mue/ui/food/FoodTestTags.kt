package fr.kristenjestin.mue.ui.food

import fr.kristenjestin.mue.domain.model.MealSlot

/**
 * Handles for the Compose tests of the Food module, in the arrangement `ActivityTestTags` uses:
 * they exist for the parts a test cannot address by their visible text — lists that scroll, rows
 * that repeat, and the parts whose whole job is sometimes to be empty.
 *
 * Fields keyed by an id — a moment, a line, an ingredient — build their tag from that id, so a
 * test can name one row among many without counting positions.
 *
 * The screens are not written yet, and the tags are here first on purpose. Six of them arrive one
 * directory at a time; if each brought its own tags, this file would be reopened six times and
 * every one of those changes would collide with the other five. PRD_FOOD 7's tables are what the
 * names below follow, so a screen that lands later finds its handles already agreed.
 */
internal object FoodTestTags {

    /** The tab's own content, which is a placeholder until `Day` lands. */
    const val PLACEHOLDER: String = "food:placeholder"

    // region the module shell (PRD_FOOD 7)

    /** The switcher over the four views; each button carries [view] below. */
    const val VIEW_SWITCHER: String = "food:viewSwitcher"

    fun view(route: FoodRoute.View): String = "food:view:${route.key}"

    // endregion

    // region `Day` (PRD_FOOD 10.1)

    const val DAY: String = "food:day"
    const val DAY_DATE: String = "food:dayDate"
    const val PREVIOUS_DAY: String = "food:previousDay"
    const val NEXT_DAY: String = "food:nextDay"
    const val DAY_DATE_PICKER: String = "food:dayDatePicker"

    /** PRD_FOOD 22 and 12: what a day still to come says about itself. */
    const val FUTURE_DAY: String = "food:futureDay"

    /** PRD_FOOD 17: the one line a day with neither an entry nor a proposal draws. */
    const val DAY_EMPTY: String = "food:dayEmpty"

    /** One of the six moments, drawn when it holds a line or a proposal and not before. */
    fun slot(slot: MealSlot): String = "food:slot:${slot.id}"

    /** A moment's own total, which PRD_FOOD 10.1 only shows once it holds a line. */
    fun slotTotal(slot: MealSlot): String = "food:slotTotal:${slot.id}"

    /**
     * The day's one add action, pinned at the foot of the screen.
     *
     * It replaces the six `addToSlot(slot)` handles this file used to publish. That is the
     * owner's instruction over PRD_FOOD 10.1's "toujours présent" — and it is the same handle
     * shape `CREATE_FOOD` and `CREATE_RECIPE` already have on the other two views, because it is
     * now the same control in the same place. **No moment is named**, here or on the tap: the
     * hour decides it on the sheet (FR-FOOD-007), which is exactly what a per-moment `+` was
     * overriding.
     */
    const val ADD_TO_DAY: String = "food:addToDay"

    /** One journal line, in any of PRD_FOOD 10.2's three forms. */
    fun logEntry(entryId: String): String = "food:logEntry:$entryId"

    // endregion

    // region proposals (PRD_FOOD 12)

    /** The dashed card of an unconfirmed proposal, and its three actions. */
    fun plan(slot: MealSlot): String = "food:plan:${slot.id}"

    fun confirmPlan(slot: MealSlot): String = "food:confirmPlan:${slot.id}"

    fun swapPlan(slot: MealSlot): String = "food:swapPlan:${slot.id}"

    fun dismissPlan(slot: MealSlot): String = "food:dismissPlan:${slot.id}"

    /**
     * FR-PLAN-001's confirmation, and the control that answers it.
     *
     * They replace `SWAP_SHEET` and `SWAP_SEARCH`, which named a screen that never existed:
     * `FoodRoute.Swap` drew a wordless empty `Box`. Replacing a proposal is now the same sheet as
     * posing one, so what needed a handle was the question asked before a row is overwritten.
     */
    const val REPLACE_PLAN_DIALOG: String = "food:replacePlanDialog"
    const val REPLACE_PLAN_CONFIRM: String = "food:replacePlanConfirm"

    /** PRD_FOOD 8.5, stated once on the planning sheet: only a recipe can be proposed. */
    const val PLAN_ONLY_RECIPES: String = "food:planOnlyRecipes"

    // endregion

    // region `Trends` (PRD_FOOD 10.5)

    const val TRENDS: String = "food:trends"

    /** The seven bars, whose whole job is sometimes to be empty. */
    const val WEEKLY_BARS: String = "food:weeklyBars"

    fun weeklyBar(dayIndex: Int): String = "food:weeklyBar:$dayIndex"

    const val TRENDS_AVERAGE: String = "food:trendsAverage"
    const val TRENDS_DAYS_LOGGED: String = "food:trendsDaysLogged"
    const val TRENDS_ENTRY_COUNT: String = "food:trendsEntryCount"
    const val HISTORY_LIST: String = "food:historyList"

    // endregion

    // region `Add food` (PRD_FOOD 7, FR-FOOD-002 to 006)

    const val ADD_SHEET: String = "food:addSheet"

    /** PRD_FOOD 7's four paths, and the confirmation every one of them ends on. */
    const val ADD_BY_SEARCH: String = "food:addBySearch"
    const val ADD_BY_SCAN: String = "food:addByScan"
    const val ADD_BY_RECIPE: String = "food:addByRecipe"
    const val ADD_QUICK: String = "food:addQuick"

    const val SEARCH_FIELD: String = "food:searchField"
    const val SEARCH_RESULTS: String = "food:searchResults"

    /** PRD_FOOD 18: the manual code is a complete alternative to the camera, never a fallback. */
    const val SCANNER_PREVIEW: String = "food:scannerPreview"
    const val BARCODE_FIELD: String = "food:barcodeField"

    /**
     * FR-FOOD-004: the recipe a line is being built from, on the sheet.
     *
     * Its own name rather than [LOG_RECIPE]'s, which belongs to a different control on a
     * different screen — the action PRD_FOOD 11 puts on a recipe's own card. This one is the
     * chosen recipe, and what it does when tapped is open the picker again.
     */
    const val CHOSEN_RECIPE: String = "food:chosenRecipe"

    const val QUICK_NAME_FIELD: String = "food:quickNameField"
    const val QUICK_ENERGY_FIELD: String = "food:quickEnergyField"

    /** The confirmation stage: how much, when, and in which moment (PRD_FOOD 10.3). */
    const val QUANTITY_FIELD: String = "food:quantityField"
    const val UNIT_PICKER: String = "food:unitPicker"
    const val SERVINGS_STEPPER: String = "food:servingsStepper"
    /**
     * The moment, which is **derived and not asked for** (FR-FOOD-007).
     *
     * [SLOT_FIELD] is the one quiet line the sheet draws for it: what the hour decided, and the
     * way to overrule it. [SLOT_PICKER] is the list of moments inside the panel that line opens,
     * which is where the choosing now happens — it used to be a grid of tiles on the form itself,
     * asking for a fact the hour beside it had already given.
     */
    const val SLOT_FIELD: String = "food:slotField"
    const val SLOT_PICKER: String = "food:slotPicker"
    const val SLOT_SHEET: String = "food:slotSheet"
    const val TIME_FIELD: String = "food:timeField"

    /** PRD_FOOD 10.3: what the sheet says when the hour and the moment disagree. */
    const val SLOT_TIME_NOTE: String = "food:slotTimeNote"

    const val TIME_PICKER: String = "food:timePicker"
    const val CONFIRM_BUTTON: String = "food:confirmButton"
    const val DELETE_BUTTON: String = "food:deleteButton"

    // endregion

    // region `Recipes` (PRD_FOOD 11)

    const val RECIPES: String = "food:recipes"
    const val RECIPE_LIST: String = "food:recipeList"
    const val RECIPE_SEARCH: String = "food:recipeSearch"
    const val CREATE_RECIPE: String = "food:createRecipe"

    fun recipeCard(recipeId: String): String = "food:recipe:$recipeId"

    fun favouriteRecipe(recipeId: String): String = "food:favouriteRecipe:$recipeId"

    /**
     * FR-FOOD-004's picker: the recipe a line is built from, chosen over the `Add food` sheet.
     *
     * Its own handle rather than [RECIPE_LIST]'s, because it is not the `Recipes` view: it has no
     * switcher, no bottom action and no favourites, and a test that could not tell the two apart
     * would pass on the very confusion this screen exists to end.
     */
    const val RECIPE_PICKER: String = "food:recipePicker"

    const val RECIPE_DETAIL: String = "food:recipeDetail"

    /**
     * The facts block, which is the first thing the card draws.
     *
     * It exists so `FoodSubScreenHeaderTest` can measure where the content column actually
     * begins — the one assertion that cannot be made from a string, because a control faded to
     * nothing is still in the semantics tree with all of its text.
     */
    const val RECIPE_FACTS: String = "food:recipeFacts"
    const val RECIPE_SERVINGS: String = "food:recipeServings"
    const val RECIPE_PER_SERVING: String = "food:recipePerServing"
    const val LOG_RECIPE: String = "food:logRecipe"
    const val EDIT_RECIPE: String = "food:editRecipe"

    const val RECIPE_EDITOR: String = "food:recipeEditor"
    const val RECIPE_NAME_FIELD: String = "food:recipeNameField"
    const val RECIPE_TYPE_PICKER: String = "food:recipeTypePicker"
    const val RECIPE_PREP_TIME_FIELD: String = "food:recipePrepTimeField"
    const val RECIPE_SERVINGS_FIELD: String = "food:recipeServingsField"
    const val INGREDIENT_LIST: String = "food:ingredientList"
    const val ADD_INGREDIENT: String = "food:addIngredient"
    const val STEPS_FIELD: String = "food:stepsField"
    const val RECIPE_COVER: String = "food:recipeCover"

    /** One ingredient row of the editor, and the quantity that makes its contribution appear. */
    fun ingredient(index: Int): String = "food:ingredient:$index"

    fun ingredientQuantity(index: Int): String = "food:ingredientQuantity:$index"

    fun removeIngredient(index: Int): String = "food:removeIngredient:$index"

    // endregion

    // region `Foods` (PRD_FOOD 9)

    const val FOODS: String = "food:foods"
    const val FOOD_LIST: String = "food:foodList"
    const val FOOD_SEARCH: String = "food:foodSearch"
    const val CREATE_FOOD: String = "food:createFood"

    fun foodCard(foodId: String): String = "food:food:$foodId"

    /** The shared picker of PRD_FOOD 11, which reuses the search but answers to its own tag. */
    const val FOOD_PICKER: String = "food:foodPicker"

    const val FOOD_EDITOR: String = "food:foodEditor"
    const val FOOD_NAME_FIELD: String = "food:foodNameField"
    const val FOOD_BRAND_FIELD: String = "food:foodBrandField"
    const val REFERENCE_UNIT_PICKER: String = "food:referenceUnitPicker"
    const val SERVING_FIELD: String = "food:servingField"

    /** One per-100 value: energy, protein, carbohydrate, fat, fibre (PRD_FOOD 9.1). */
    fun nutrientField(nutrient: String): String = "food:nutrient:$nutrient"

    // endregion

    // region preferences (PRD_FOOD 6.7 and FR-FOOD-010)

    const val PREFERENCES: String = "food:preferences"
    const val OPEN_PREFERENCES: String = "food:openPreferences"
    const val HIDE_ENERGY_TOGGLE: String = "food:hideEnergyToggle"

    // endregion
}
