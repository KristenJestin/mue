package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat

/**
 * What the `Recipes` view draws (PRD_FOOD 11 and FR-RECIPE-005): a search line, the filters, and
 * the saved recipes under them.
 *
 * **No card carries an energy.** [Recipe] holds no nutritional value at all (PRD_FOOD 8.3) and
 * `RecipeRepository.observeAll` returns recipes without their ingredients, so a figure on a card
 * could only be invented — and PRD_FOOD 13.1 has exactly one thing to say about an invented
 * number. The totals live where the ingredients do, on the card of one recipe.
 *
 * [hasAnyRecipe] is what tells PRD_FOOD 17's two empty lists apart: a catalogue nobody has
 * written in yet gets the invitation, and a filter that matches nothing says so instead. They
 * are different facts and they read differently.
 */
@Immutable
internal data class RecipeListUiState(
    val query: String = "",
    /** FR-RECIPE-005's type filter; null is every type. */
    val type: RecipeType? = null,
    val favouritesOnly: Boolean = false,
    val recipes: List<RecipeCardUiState> = emptyList(),
    /** True while the list being shown has not been read back for these filters yet. */
    val isLoading: Boolean = false,
    /** How many recipes exist at all, whatever the filters say. */
    val hasAnyRecipe: Boolean = false,
) {

    val isFiltered: Boolean get() = query.isNotBlank() || type != null || favouritesOnly

    /** PRD_FOOD 17: "aucune recette enregistree" — an invitation, and no fake recipe. */
    val showsInvitation: Boolean get() = !isLoading && !hasAnyRecipe

    /** Recipes exist; none of them answers these filters. */
    val showsNoMatch: Boolean get() = !isLoading && hasAnyRecipe && recipes.isEmpty()

    /** For a screen reader, which cannot count the cards. */
    val countLabel: String get() = RecipeMessages.recipeCount(recipes.size)

    companion object {

        fun of(
            query: String = "",
            type: RecipeType? = null,
            favouritesOnly: Boolean = false,
            recipes: List<Recipe> = emptyList(),
            totalCount: Int = recipes.size,
            isLoading: Boolean = false,
        ): RecipeListUiState = RecipeListUiState(
            query = query,
            type = type,
            favouritesOnly = favouritesOnly,
            recipes = recipes.map(RecipeCardUiState::of),
            isLoading = isLoading,
            hasAnyRecipe = totalCount > 0,
        )
    }
}

/**
 * One saved recipe as a card: its name, what it is for, how many it serves and how long it
 * takes.
 *
 * Nothing on it says who wrote it. PRD_FOOD 8.3 and FR-RECIPE-005 keep the origin of a mutation
 * in the server's audit, and no screen of Food badges or filters a recipe by the tool that
 * created it.
 */
@Immutable
internal data class RecipeCardUiState(
    val id: RecipeId,
    val name: String,
    val iconName: String,
    /** `Main`, `Serves 4`, `25 min` — the facts that fit under the name. */
    val facts: List<String>,
    val isFavourite: Boolean,
    /** PRD_FOOD 18: the whole card announced as one thing. */
    val description: String,
    /** What the star does next, which is not what it currently shows. */
    val favouriteLabel: String,
) {
    companion object {

        fun of(recipe: Recipe): RecipeCardUiState {
            val facts = listOfNotNull(
                recipe.type.label,
                RecipeMessages.serves(recipe.baseServings),
                RecipeMessages.prepTime(recipe.prepTimeMinutes),
            )
            return RecipeCardUiState(
                id = recipe.id,
                name = recipe.name,
                iconName = FoodIcons.CHEF_HAT,
                facts = facts,
                isFavourite = recipe.isFavourite,
                description = FoodDayFormat.sentence(
                    recipe.name,
                    *facts.toTypedArray(),
                    if (recipe.isFavourite) RecipeMessages.FAVOURITES else null,
                ),
                favouriteLabel = if (recipe.isFavourite) {
                    RecipeMessages.REMOVE_FAVOURITE
                } else {
                    RecipeMessages.ADD_FAVOURITE
                },
            )
        }
    }
}
