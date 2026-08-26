package fr.kristenjestin.mue.domain.model

import java.util.UUID

/** The identifier of a recipe (PRD_FOOD 8.3), stored as a `TEXT` UUID by PRD_FOOD 20.1. */
@JvmInline
value class RecipeId(val value: String) {
    companion object {
        fun random(): RecipeId = RecipeId(UUID.randomUUID().toString())
    }
}

/**
 * The identifier of one ingredient row.
 *
 * It exists because a recipe may legitimately list the same food twice — a marinade and a sauce
 * from the same oil — so `(recipeId, foodId)` is not a key. It is never a cross-aggregate
 * reference: PRD_FOOD 21.2 synchronises the recipe whole, ingredients included.
 */
@JvmInline
value class RecipeIngredientId(val value: String) {
    companion object {
        fun random(): RecipeIngredientId = RecipeIngredientId(UUID.randomUUID().toString())
    }
}

/**
 * Which moment a recipe is written for (PRD_FOOD 8.3).
 *
 * Three values and not four: a recipe is a dish, and lunch and dinner are the same dish at two
 * hours. It is a filter of the recipe list, never a constraint on the slot a line is logged in.
 */
enum class RecipeType(val id: String, val label: String) {
    BREAKFAST("breakfast", "Breakfast"),
    MAIN("main", "Main"),
    SNACK("snack", "Snack"),
    ;

    companion object {
        private val byId: Map<String, RecipeType> = entries.associateBy { it.id }

        /** Total and non-throwing; an unreadable type is the one that fits most dishes. */
        fun fromId(id: String): RecipeType = byId[id] ?: MAIN
    }
}

/**
 * A saved shortcut — "these foods, in these quantities" (PRD_FOOD 8.1 and 8.3).
 *
 * A recipe **stores no nutritional value at all**. Its totals are recomputed from its
 * ingredients and [baseServings] every time it is shown, which is what makes correcting one
 * food correct every recipe that uses it with no migration (PRD_FOOD 8.3).
 *
 * Nothing here records who wrote it. PRD_FOOD 8.3 and FR-PLAN-004 are explicit: the origin of a
 * mutation belongs to the server's audit, never to the business object, and no screen of Food
 * badges, filters or protects a recipe by the tool that created it.
 *
 * `createdAt` and `updatedAt` of PRD_FOOD 8.3 are absent for the reason they are absent from
 * [Food] and from `ActivitySession`: they are audit columns of the stored row.
 */
data class Recipe(
    val id: RecipeId,
    val name: String,
    val type: RecipeType,
    /** PRD_FOOD 15: a whole number from 1 to 12. Ingredient quantities are for all of them. */
    val baseServings: Int,
    val description: String? = null,
    val prepTimeMinutes: Int? = null,
    /** PRD_FOOD 15: at most 30 lines of at most 500 characters, entered one per line. */
    val steps: List<String> = emptyList(),
    val imageRef: String? = null,
    val isFavourite: Boolean = false,
) {
    val nameFolded: String get() = Food.fold(name)

    companion object {
        /** PRD_FOOD 15: the same rule as a food name — 1 to 80 characters once trimmed. */
        const val MIN_NAME_LENGTH: Int = Food.MIN_NAME_LENGTH

        const val MAX_NAME_LENGTH: Int = Food.MAX_NAME_LENGTH

        /** PRD_FOOD 15: "Portions d'une recette : entier de 1 à 12". */
        val BASE_SERVINGS_RANGE: IntRange = 1..12

        /** PRD_FOOD 15: "Étapes d'une recette : 0 à 30 lignes, 500 caractères par ligne". */
        const val MAX_STEPS: Int = 30

        const val MAX_STEP_LENGTH: Int = 500

        /** PRD_FOOD 15: "Ingrédients d'une recette : 1 à 40". */
        const val MIN_INGREDIENTS: Int = 1

        const val MAX_INGREDIENTS: Int = 40

        /**
         * PRD_FOOD 15 bounds neither the description nor the preparation time. Both fields are
         * free text on a form, so both carry a ceiling here for the reason `Load.MAX_GRAMS`
         * does: to keep a mistyped `1e9` out of the storage. The description reuses the 500
         * characters PRD_FOOD 15 already allows a step; the time stops at a full day.
         */
        const val MAX_DESCRIPTION_LENGTH: Int = 500

        val PREP_TIME_MINUTES_RANGE: IntRange = 1..1_440
    }
}

/**
 * One ingredient of one recipe (PRD_FOOD 8.3).
 *
 * [quantity] is the amount for the **whole recipe**, never for one serving (PRD_FOOD 8.3), and
 * [unit] repeats the food's own reference unit so the row stays readable when the food itself is
 * missing.
 *
 * [foodName] is that same insurance and is required by PRD_FOOD 21.2: a recipe may arrive from
 * the server referencing a food this device has not received yet, and the client must apply the
 * recipe and show the ingredient by its name-and-quantity snapshot rather than reject the
 * aggregate. It is null only for rows written before a name was captured.
 */
data class RecipeIngredient(
    val id: RecipeIngredientId,
    val foodId: FoodId,
    val quantity: Quantity,
    val unit: ReferenceUnit,
    val position: Int,
    val foodName: String? = null,
)

/**
 * A whole recipe as the editor loads it, as the repository writes it in one transaction, and as
 * PRD_FOOD 21.2 synchronises it: **a recipe never appears without its ingredients**.
 *
 * PRD_FOOD 21.3 resolves a conflict on the whole aggregate — the last accepted mutation wins
 * entire — and never merges ingredient rows one by one, which is exactly why the aggregate is
 * this type and not a recipe with a lazily loaded list.
 */
data class RecipeDetail(
    val recipe: Recipe,
    val ingredients: List<RecipeIngredient> = emptyList(),
) {
    val id: RecipeId get() = recipe.id

    /** PRD_FOOD 15: a recipe with no ingredient cannot be saved. */
    val hasIngredients: Boolean get() = ingredients.isNotEmpty()

    /** The distinct foods to resolve before the totals of PRD_FOOD 13.1 can be computed. */
    val foodIds: List<FoodId> get() = ingredients.map { it.foodId }.distinct()
}
