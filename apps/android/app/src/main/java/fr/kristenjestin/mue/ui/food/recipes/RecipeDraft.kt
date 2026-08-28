package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.valueOrNull
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A recipe being written, exactly as it was typed.
 *
 * Every field is a raw input string rather than a parsed value, for the reason `ActivityDraft`
 * gives: a half-typed `12,` has to come back unchanged after the process is killed, and
 * `Quantity` refuses zero — so an ingredient whose quantity has not been typed yet has no domain
 * value that could stand for it. Parsing happens once, on save, through
 * [fr.kristenjestin.mue.domain.logic.FoodValidation].
 *
 * The whole draft is stored as one JSON string under one `SavedStateHandle` key: an unbounded
 * list of ingredients cannot be flattened into `Bundle` keys.
 *
 * [foodName] is carried on every row even while the food is in the catalogue, because it is what
 * PRD_FOOD 8.3 stores on the saved ingredient — the snapshot PRD_FOOD 21.2 renders an orphan by.
 * Writing it from the picker is what makes a recipe legible on a device that never receives the
 * food.
 */
@Serializable
internal data class RecipeDraft(
    /** The recipe being edited, or null while creating one (FR-RECIPE-001 and 006). */
    val recipeId: String? = null,
    val name: String = "",
    val typeId: String = RecipeType.MAIN.id,
    /** PRD_FOOD 15: a whole number from 1 to 12, stepped rather than typed. */
    val baseServings: String = DEFAULT_BASE_SERVINGS,
    val description: String = "",
    val prepTimeMinutes: String = "",
    /** PRD_FOOD 15: one step per line, blank lines dropped by the validator. */
    val steps: String = "",
    val ingredients: List<RecipeIngredientDraft> = emptyList(),
    /** FR-RECIPE-005: kept so saving an edit does not silently un-favourite a recipe. */
    val isFavourite: Boolean = false,
    /**
     * PRD_FOOD 14's cover, carried through untouched.
     *
     * This form neither sets nor clears it: choosing a picture needs the file store PRD_FOOD 14
     * describes — `files/recipe-images/{uuid}.webp`, a thumbnail, and a deletion that takes both
     * with it — and none of it exists in `data` yet. What the field is here for is the opposite
     * of a feature: a recipe written elsewhere with a cover, reopened here to fix a step, must
     * not come out of this form having lost it.
     */
    val imageRef: String? = null,
) {
    val type: RecipeType get() = RecipeType.fromId(typeId)

    val isEditing: Boolean get() = recipeId != null

    fun withIngredient(index: Int, block: (RecipeIngredientDraft) -> RecipeIngredientDraft):
        RecipeDraft {
        val rows = ingredients.toMutableList()
        val row = rows.getOrNull(index) ?: return this
        rows[index] = block(row)
        return copy(ingredients = rows)
    }

    /**
     * The base servings moved one whole serving, or this draft unchanged at a bound.
     *
     * `FoodValidation.validateBaseServings` owns PRD_FOOD 15's 1 to 12 and is asked twice: once
     * to read what is currently in the field, once to say whether the neighbour is still a legal
     * number of servings. A step that would leave the range returns `this`, which greys the
     * button. A field holding something unreadable steps to [DEFAULT_BASE_SERVINGS] so that the
     * control is never dead.
     */
    fun steppedBaseServings(up: Boolean): RecipeDraft {
        val current = FoodValidation.validateBaseServings(baseServings).valueOrNull
            ?: return copy(baseServings = DEFAULT_BASE_SERVINGS)
        val moved = current + if (up) 1 else -1
        val next = FoodValidation.validateBaseServings(moved).valueOrNull ?: return this
        return copy(baseServings = next.toString())
    }

    /** Whether [steppedBaseServings] would move — all the stepper's buttons need to know. */
    fun canStepBaseServings(up: Boolean): Boolean =
        steppedBaseServings(up).baseServings != baseServings

    fun toJson(): String = format.encodeToString(serializer(), this)

    companion object {

        /** What a form opens on: enough for a household, and inside PRD_FOOD 15's 1 to 12. */
        const val DEFAULT_BASE_SERVINGS: String = "4"

        /**
         * Total and non-throwing: a draft that cannot be read is a draft that was never there,
         * and a blank form is a better outcome than a crash on the first frame after an update.
         */
        fun fromJson(raw: String?): RecipeDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching { format.decodeFromString(serializer(), raw) }.getOrNull()
        }

        /** The form a saved recipe reopens as (FR-RECIPE-006). */
        fun of(
            recipe: Recipe,
            ingredients: List<RecipeIngredient>,
            names: Map<String, String> = emptyMap(),
        ): RecipeDraft = RecipeDraft(
            recipeId = recipe.id.value,
            name = recipe.name,
            typeId = recipe.type.id,
            baseServings = recipe.baseServings.toString(),
            description = recipe.description.orEmpty(),
            prepTimeMinutes = recipe.prepTimeMinutes?.toString().orEmpty(),
            steps = recipe.steps.joinToString(separator = "\n"),
            ingredients = ingredients.map { RecipeIngredientDraft.of(it, names) },
            isFavourite = recipe.isFavourite,
            imageRef = recipe.imageRef,
        )

        private val format = Json { ignoreUnknownKeys = true }
    }
}

/**
 * One ingredient row of the form.
 *
 * The quantity is text and the food is an id: FR-RECIPE-002 refuses free text as an ingredient,
 * so a row exists only once a `Food` has been chosen, and the number beside it is whatever has
 * been typed so far.
 */
@Serializable
internal data class RecipeIngredientDraft(
    val id: String,
    val foodId: String,
    val foodName: String,
    val unitId: String = ReferenceUnit.GRAM.id,
    val quantity: String = "",
) {
    val unit: ReferenceUnit get() = ReferenceUnit.fromId(unitId)

    companion object {

        fun of(
            ingredient: RecipeIngredient,
            names: Map<String, String> = emptyMap(),
        ): RecipeIngredientDraft = RecipeIngredientDraft(
            id = ingredient.id.value,
            foodId = ingredient.foodId.value,
            /*
             * The catalogue's name wins when the food is there, because it may have been
             * corrected since; the stored snapshot answers when it is not (PRD_FOOD 21.2), and
             * an ingredient with neither keeps its row rather than vanishing from the form.
             */
            foodName = names[ingredient.foodId.value]
                ?: ingredient.foodName?.takeIf { it.isNotBlank() }
                ?: RecipeMessages.UNNAMED_INGREDIENT,
            unitId = ingredient.unit.id,
            quantity = typedAmount(ingredient.quantity.amount),
        )

        /** A new row, with the quantity still to type. */
        fun newRow(foodId: String, foodName: String, unit: ReferenceUnit): RecipeIngredientDraft =
            RecipeIngredientDraft(
                id = RecipeIngredientId.random().value,
                foodId = foodId,
                foodName = foodName,
                unitId = unit.id,
            )

        /**
         * A stored quantity written back into a text field.
         *
         * Trailing zeros are dropped so reopening a recipe and saving it again is a no-op: a box
         * showing `260.000` would be re-parsed to the same value but read as something the
         * person had typed. The separator is a `.` and never a `,`, whatever the phone's
         * language — `FoodValidation` accepts both on the way in, and a field this file wrote
         * must not depend on a locale to be read back.
         */
        internal fun typedAmount(amount: Double): String {
            val whole = amount.toLong()
            return if (amount == whole.toDouble()) whole.toString() else amount.toString()
        }
    }
}
