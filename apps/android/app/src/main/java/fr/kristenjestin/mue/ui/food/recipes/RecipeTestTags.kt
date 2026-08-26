package fr.kristenjestin.mue.ui.food.recipes

/**
 * The handles this half of the module needs and [fr.kristenjestin.mue.ui.food.FoodTestTags] does
 * not carry.
 *
 * That object was written before any recipe screen existed and is shared with five other
 * directories, so the tags it already names — `RECIPES`, `RECIPE_LIST`, `RECIPE_DETAIL`,
 * `RECIPE_EDITOR`, `ingredient(index)` and the rest — are used as they stand and nothing is
 * added to it. Reopening a file every sibling directory also edits is how three agents collide
 * on one line; the Activity module made the same call for `EXERCISE_CREATE_TAG`.
 *
 * The prefix stays `food:` so a tag reads the same wherever it is found.
 */
internal object RecipeTestTags {

    // region `Recipes` (PRD_FOOD 11, FR-RECIPE-005)

    /** The single-choice row of recipe types, and one handle per option. */
    const val TYPE_FILTER: String = "food:recipeTypeFilter"

    fun typeFilter(id: String): String = "food:recipeTypeFilter:$id"

    /** FR-RECIPE-005: favourites, which is a toggle rather than one of the type options. */
    const val FAVOURITES_FILTER: String = "food:recipeFavouritesFilter"

    /** PRD_FOOD 17: the invitation of an empty catalogue, and the "nothing matched" of a filter. */
    const val EMPTY_STATE: String = "food:recipesEmpty"

    // endregion

    // region `Recipe detail` (PRD_FOOD 11, FR-RECIPE-003 and 004)

    const val FEWER_SERVINGS: String = "food:recipeFewerServings"
    const val MORE_SERVINGS: String = "food:recipeMoreServings"

    /** PRD_FOOD 13.1: the values for the number of servings currently on screen. */
    const val RECIPE_TOTAL: String = "food:recipeTotal"

    const val RECIPE_INGREDIENTS: String = "food:recipeIngredients"
    const val RECIPE_STEPS: String = "food:recipeSteps"

    /** One ingredient of the card, named by its row so a test never counts positions. */
    fun detailIngredient(id: String): String = "food:recipeDetailIngredient:$id"

    /** PRD_FOOD 21.2: the note on an ingredient whose food has not arrived on this device. */
    fun orphanIngredient(id: String): String = "food:recipeOrphanIngredient:$id"

    const val DELETE_RECIPE: String = "food:deleteRecipe"

    /** FR-RECIPE-006: the moments a deletion has just freed, named one by one. */
    const val FREED_PLANS: String = "food:recipeFreedPlans"

    // endregion

    // region `Recipe editor` (PRD_FOOD 11, FR-RECIPE-001 to 003)

    const val SAVE_RECIPE: String = "food:saveRecipe"
    const val RECIPE_DESCRIPTION_FIELD: String = "food:recipeDescriptionField"

    /** PRD_FOOD 11: the live `Per serving` block of the form. */
    const val EDITOR_PER_SERVING: String = "food:recipeEditorPerServing"

    /** PRD_FOOD 15: what says a recipe cannot be saved without an ingredient. */
    const val INGREDIENT_COUNT_ERROR: String = "food:recipeIngredientCountError"

    /** The ingredient picker, and the row of one food inside it. */
    const val PICKER_DONE: String = "food:ingredientPickerDone"

    fun pickerRow(foodId: String): String = "food:ingredientPickerRow:$foodId"

    // endregion
}
