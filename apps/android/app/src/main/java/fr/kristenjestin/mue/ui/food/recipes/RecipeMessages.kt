package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.model.RecipeType

/**
 * Every word the three recipe screens put on screen, in one place (PRD_FOOD 11, 17 and 18).
 *
 * Constants rather than resources, for the reason `FoodDayMessages` gives: Mue ships in one
 * language, and a string a test can name is a string a test cannot mistype. The accessibility
 * labels sit here too — PRD_FOOD 18 makes them part of the interface rather than a decoration
 * added afterwards.
 *
 * Nothing here formats a number. Energies, macronutrients and quantities arrive already
 * rendered from [fr.kristenjestin.mue.domain.logic.FoodLabels]; this object only supplies the
 * nouns that sit beside them.
 */
internal object RecipeMessages {

    // region `Recipes` (PRD_FOOD 11 and FR-RECIPE-005)

    const val LIST_TITLE: String = "Recipes"
    const val LIST_EYEBROW: String = "What you cook"

    const val SEARCH_PLACEHOLDER: String = "Search your recipes"
    const val SEARCH_LABEL: String = "Search your recipes"
    const val CLEAR_SEARCH: String = "Clear the search"

    /** FR-RECIPE-005's filters: every type, then the three of [RecipeType]. */
    const val TYPE_ALL: String = "All"

    const val FAVOURITES: String = "Favourites"
    const val SHOW_FAVOURITES_ONLY: String = "Show favourites only"
    const val SHOW_EVERY_RECIPE: String = "Show every recipe"

    const val ADD_FAVOURITE: String = "Add to favourites"
    const val REMOVE_FAVOURITE: String = "Remove from favourites"

    /** PRD_FOOD 17: "message d'invitation et bouton de creation, sans recette factice". */
    const val NO_RECIPES_TITLE: String = "No recipes yet"
    const val NO_RECIPES_BODY: String =
        "A recipe is a saved shortcut: these foods, in these quantities. A yoghurt needs none."

    /** The other empty list: recipes exist, this filter simply matches none of them. */
    const val NO_MATCH_TITLE: String = "Nothing matches"
    const val NO_MATCH_BODY: String = "Try another word, another moment, or clear the filters."

    const val CREATE_RECIPE: String = "New recipe"

    fun recipeCount(count: Int): String = when (count) {
        0 -> "no recipes"
        1 -> "$count recipe"
        else -> "$count recipes"
    }

    /** "Serves 4" — the number a recipe's ingredient quantities are written for (PRD_FOOD 8.3). */
    fun serves(baseServings: Int): String = "Serves $baseServings"

    /** "25 min", and nothing at all when no preparation time was given. */
    fun prepTime(minutes: Int?): String? = minutes?.let { "$it min" }

    fun typeLabel(type: RecipeType?): String = type?.label ?: TYPE_ALL

    // endregion

    // region `Recipe detail` (PRD_FOOD 11, FR-RECIPE-003, 004 and 006)

    const val BACK: String = "Back"

    const val INGREDIENTS: String = "Ingredients"
    const val STEPS: String = "Steps"

    /** FR-RECIPE-003: the block PRD_FOOD 11 recomputes live, and the one PRD_FOOD 13.1 divides. */
    const val PER_SERVING: String = "Per serving"

    const val SERVINGS: String = "Servings"
    const val FEWER_SERVINGS: String = "One serving fewer"
    const val MORE_SERVINGS: String = "One serving more"

    /** FR-RECIPE-004: what the quantities currently on screen add up to. */
    fun forServings(digits: String, plural: Boolean): String = "For ${servings(digits, plural)}"

    fun servings(digits: String, plural: Boolean): String =
        "$digits ${if (plural) SERVINGS_NOUN_PLURAL else SERVINGS_NOUN}"

    const val EDIT_RECIPE: String = "Edit recipe"
    const val DELETE_RECIPE: String = "Delete recipe"

    /**
     * PRD_FOOD 21.2: a recipe may reference a food this device has not received yet.
     *
     * The ingredient is still shown, by the name and quantity snapshot it carries, and only its
     * contribution is unknown. This note is why the figures beside it read a dash.
     */
    const val ORPHAN_INGREDIENT: String = "Not in your catalogue yet"

    /** The snapshot is missing too — a row written before a name was captured. */
    const val UNNAMED_INGREDIENT: String = "Unknown food"

    /**
     * PRD_FOOD 15: a recipe with no ingredient cannot be saved, so the card shows no total.
     *
     * An empty strict sum is a *known* zero (PRD_FOOD 13.1), and printing "0 kcal" over a recipe
     * nobody has filled in yet would be exactly the invented total PRD_FOOD 10.4 forbids of an
     * untouched day.
     */
    const val NO_INGREDIENTS: String = "No ingredients yet"

    const val NO_STEPS: String = "No steps written"

    /** PRD_FOOD 17: the recipe was deleted while its card was open, or the id is stale. */
    const val MISSING_RECIPE: String = "This recipe no longer exists"

    // FR-RECIPE-006: deletion asks first, and then names the proposals it freed.
    const val DELETE_TITLE: String = "Delete this recipe?"
    const val DELETE_BODY: String =
        "Every line already in your journal keeps its values. " +
            "Any meal plan that proposes it is freed."
    const val DELETE_CONFIRM: String = "Delete"
    const val CANCEL: String = "Cancel"
    const val DELETED_TITLE: String = "Recipe deleted"
    const val DONE: String = "Done"

    /**
     * PRD_FOOD 17: "recette supprimee mais proposee : le moment est libere et signale".
     *
     * The moments are named rather than counted, because "Tuesday lunch" is something a person
     * can go and fill in and "2 meal plans" is not.
     */
    fun freedPlans(count: Int): String = when (count) {
        1 -> "One moment is free again:"
        else -> "$count moments are free again:"
    }

    // endregion

    // region `Recipe editor` (PRD_FOOD 11, FR-RECIPE-001 to 003)

    const val CREATE_TITLE: String = "New recipe"
    const val EDIT_TITLE: String = "Edit recipe"

    const val NAME_LABEL: String = "Name"
    const val NAME_PLACEHOLDER: String = "Sheet-pan salmon"
    const val TYPE_LABEL: String = "Moment"
    const val SERVINGS_LABEL: String = "Servings"

    /**
     * The two ends of the base-servings stepper.
     *
     * A whole serving each way, because PRD_FOOD 15 makes this field a whole number — the step
     * that applies it is `RecipeDraft.steppedBaseServings`, which asks `FoodValidation` whether
     * the neighbour is legal rather than testing 1 and 12 itself.
     */
    const val FEWER_BASE_SERVINGS: String = "One serving fewer"
    const val MORE_BASE_SERVINGS: String = "One serving more"
    const val PREP_TIME_LABEL: String = "Preparation time"
    const val PREP_TIME_SUFFIX: String = "min"
    const val DESCRIPTION_LABEL: String = "Description"
    const val OPTIONAL_PLACEHOLDER: String = "Optional"
    const val STEPS_LABEL: String = "Steps, one per line"

    /** PRD_FOOD 8.3: "les quantites des ingredients sont exprimees pour la recette entiere". */
    const val INGREDIENTS_HINT: String = "Quantities are for the whole recipe"

    const val ADD_INGREDIENT: String = "Add an ingredient"
    const val QUANTITY_LABEL: String = "Quantity"

    fun removeIngredient(name: String): String = "Remove $name"

    const val SAVE_RECIPE: String = "Save recipe"
    const val SAVE_CHANGES: String = "Save changes"

    /** PRD_FOOD 11: the picker every ingredient is chosen through. */
    const val PICKER_TITLE: String = "Choose an ingredient"
    const val PICKER_EYEBROW: String = "Build your recipe"
    const val PICKER_SEARCH_PLACEHOLDER: String = "Search skyr, oats, salmon..."
    const val PICKER_SEARCH_LABEL: String = "Search the food catalogue"
    const val PICKER_CLOSE: String = "Close the ingredient picker"
    const val PICKER_EMPTY: String = "No matching food yet"
    const val PICKER_RECENT: String = "Recently used"
    const val PICKER_RESULTS: String = "Results"
    const val PICKER_DONE: String = "Done"

    /**
     * What the picker's own action says once it has added something.
     *
     * The picker stays open after a pick: PRD_FOOD 11 builds a recipe out of several foods at
     * once, and a sheet that closed on the first one would have to be reopened for every
     * ingredient — the very complaint the activity module's exercise picker earned. So the sheet
     * reports what it has done and waits to be closed.
     */
    fun addedCount(count: Int): String =
        if (count == 0) PICKER_DONE else "$PICKER_DONE ($count added)"

    /** PRD_FOOD 18: "l'ajout d'une ligne annonce le resultat sans voler le focus". */
    fun addedAnnouncement(name: String): String = "$name added"

    // endregion

    private const val SERVINGS_NOUN = "serving"
    private const val SERVINGS_NOUN_PLURAL = "servings"
}
