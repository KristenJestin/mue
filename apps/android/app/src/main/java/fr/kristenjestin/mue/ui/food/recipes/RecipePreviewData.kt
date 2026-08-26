package fr.kristenjestin.mue.ui.food.recipes

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.ui.food.FoodIcons

/**
 * The recipes the previews draw, the Compose tests drive and the screenshots keep.
 *
 * In `main` rather than in a test source set for the reason `FoodDayPreviewData` is: three
 * callers need the very same fixture, and a copy in each would let the picture that was looked
 * at and the data that was asserted drift apart — which is exactly how a `—` becomes a `0`
 * without anyone noticing.
 *
 * Three recipes, chosen to hold every reading PRD_FOOD 13 has at once:
 *
 * - [salmon] — every ingredient resolved, so every metric of its total is a number;
 * - [curry] — one ingredient references a food this device has never received (PRD_FOOD 21.2),
 *   so its contribution is unknown and the recipe's total is unknown *and not zero*;
 * - [emptyRecipe] — no ingredient at all, which an empty strict sum would make a **known**
 *   zero. PRD_FOOD 15 refuses to save such a recipe and the card refuses to total it.
 *
 * The salmon also carries a name at PRD_FOOD 15's 80-character ceiling, because a card that
 * ellipsises it still passes every assertion a semantics string can make.
 */
internal object RecipePreviewData {

    /**
     * Exactly 80 characters: the longest name PRD_FOOD 15 lets a recipe carry.
     *
     * Ordinary words on purpose. The layout test asserts that no word is broken at a doubled font
     * scale, and a fixture stuffed with `xxxxxxxxxxxx` would either fail for a reason nobody
     * writes into a recipe or pass by having nothing long in it.
     */
    const val LONGEST_NAME: String =
        "Sheet-pan salmon with charred greens, pearled barley, and a dill lemon-caper oil"

    const val CURRY_NAME: String = "Red lentil curry"
    const val EMPTY_NAME: String = "Sunday roast, still to write"

    val SALMON_ID: RecipeId = RecipeId("preview-salmon")
    val CURRY_ID: RecipeId = RecipeId("preview-curry")
    val EMPTY_ID: RecipeId = RecipeId("preview-empty")

    /** The ingredient whose food this device has never received (PRD_FOOD 21.2). */
    const val ORPHAN_SNAPSHOT: String = "Red lentils, dry"

    val ORPHAN_FOOD_ID: FoodId = FoodId("preview-food-lentils")

    fun salmonFillet(): Food = food("preview-food-salmon", "Salmon fillet, raw", 208.0, 20.4, 0.0, 13.4, 0.0)

    fun pearlBarley(): Food = food("preview-food-barley", "Pearl barley, dry", 352.0, 10.6, 67.3, 2.3, 15.6)

    fun greens(): Food = food("preview-food-greens", "Tenderstem broccoli", 35.0, 3.3, 2.2, 0.6, 3.1)

    fun coconutMilk(): Food = food("preview-food-coconut", "Coconut milk", 197.0, 2.0, 3.3, 19.0, 0.5)

    /** The catalogue the previews resolve against. It deliberately holds no red lentils. */
    fun catalogue(): List<Food> = listOf(salmonFillet(), pearlBarley(), greens(), coconutMilk())

    fun catalogueById(): Map<FoodId, Food> = catalogue().associateBy(Food::id)

    /** Every ingredient known: the recipe whose figures are all numbers. */
    fun salmon(): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = SALMON_ID,
            name = LONGEST_NAME,
            type = RecipeType.MAIN,
            baseServings = 2,
            description = "A tray, twenty-five minutes, and nothing to stir.",
            prepTimeMinutes = 25,
            steps = listOf(
                "Heat the oven to 200 C.",
                "Toss the greens in oil and spread them on the tray.",
                "Lay the fillets on top and roast for 14 minutes.",
                "Stir the dressing through the barley and serve.",
            ),
            isFavourite = true,
        ),
        ingredients = listOf(
            ingredient("preview-ing-salmon", salmonFillet().id, 260.0, 0),
            ingredient("preview-ing-barley", pearlBarley().id, 120.0, 1),
            ingredient("preview-ing-greens", greens().id, 200.0, 2),
        ),
    )

    /**
     * PRD_FOOD 21.2, on screen: one ingredient references a food that never arrived.
     *
     * The row still appears — by [RecipeIngredient.foodName], the snapshot that exists for
     * precisely this — and its contribution is [Nutrients.UNKNOWN]. PRD_FOOD 13.1's strict sum
     * then makes the whole recipe unknown, so every figure on this card reads `—` and none of
     * them reads `0`.
     */
    fun curry(): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = CURRY_ID,
            name = CURRY_NAME,
            type = RecipeType.MAIN,
            baseServings = 4,
            prepTimeMinutes = 35,
            steps = listOf("Soften the aromatics.", "Add the lentils and the coconut milk."),
        ),
        ingredients = listOf(
            ingredient("preview-ing-lentils", ORPHAN_FOOD_ID, 300.0, 0, name = ORPHAN_SNAPSHOT),
            ingredient("preview-ing-coconut", coconutMilk().id, 400.0, 1),
        ),
    )

    /** A recipe with no ingredient, which a strict sum would call a known zero (PRD_FOOD 13.1). */
    fun emptyRecipe(): RecipeDetail = RecipeDetail(
        recipe = Recipe(
            id = EMPTY_ID,
            name = EMPTY_NAME,
            type = RecipeType.MAIN,
            baseServings = 6,
        ),
    )

    fun details(): List<RecipeDetail> = listOf(salmon(), curry(), emptyRecipe())

    /** Favourites first, then by name — the order `RecipeRepository.observeAll` returns. */
    fun recipes(): List<Recipe> = details()
        .map { it.recipe }
        .sortedWith(compareByDescending<Recipe> { it.isFavourite }.thenBy { it.nameFolded })

    private fun ingredient(
        id: String,
        foodId: FoodId,
        amount: Double,
        position: Int,
        name: String? = null,
        unit: ReferenceUnit = ReferenceUnit.GRAM,
    ): RecipeIngredient = RecipeIngredient(
        id = RecipeIngredientId(id),
        foodId = foodId,
        quantity = quantity(amount),
        unit = unit,
        position = position,
        foodName = name ?: catalogueById()[foodId]?.name,
    )

    private fun food(
        id: String,
        name: String,
        energy: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fibre: Double,
    ): Food = Food(
        id = FoodId(id),
        name = name,
        source = FoodSource.CIQUAL,
        per100 = Nutrients(
            energy = Energy.ofPer100OrNull(energy),
            protein = Macro.ofPer100OrNull(protein),
            carbs = Macro.ofPer100OrNull(carbs),
            fat = Macro.ofPer100OrNull(fat),
            fibre = Macro.ofPer100OrNull(fibre),
        ),
    )

    private fun quantity(amount: Double): Quantity =
        requireNotNull(Quantity.ofIngredientOrNull(amount)) { "$amount is not a quantity" }
}

/** The populated `Recipes` view the previews and the screenshots draw. */
internal fun previewRecipeListState(
    type: RecipeType? = null,
    favouritesOnly: Boolean = false,
): RecipeListUiState = RecipeListUiState.of(
    type = type,
    favouritesOnly = favouritesOnly,
    recipes = RecipePreviewData.recipes(),
    totalCount = RecipePreviewData.recipes().size,
)

/** PRD_FOOD 17: a catalogue nobody has written in yet — an invitation, and no fake recipe. */
internal fun emptyRecipeListState(): RecipeListUiState = RecipeListUiState.of()

/**
 * PRD_FOOD 17's *other* empty list: recipes exist, and this filter matches none of them.
 *
 * Held beside [emptyRecipeListState] it is the whole distinction in two states — an invitation
 * for someone who has written nothing, and a plain statement of fact for someone who has written
 * three and searched for a fourth.
 */
internal fun noMatchRecipeListState(): RecipeListUiState = RecipeListUiState.of(
    query = "bouillabaisse",
    totalCount = RecipePreviewData.recipes().size,
)

/** The card of a recipe whose every ingredient is known (PRD_FOOD 11 and 13.1). */
internal fun previewRecipeDetailState(
    deletion: RecipeDeletionUiState = RecipeDeletionUiState.Idle,
): RecipeDetailUiState = RecipeDetailUiState.of(
    detail = RecipePreviewData.salmon(),
    foods = RecipePreviewData.catalogueById(),
    deletion = deletion,
)

/**
 * The card of a recipe with an orphan ingredient (PRD_FOOD 21.2 and 13.1).
 *
 * The one picture to look at when PRD_FOOD 13.1 is in question: the lentils are drawn by their
 * snapshot with `—` beside them, and every figure of the recipe reads `—` rather than `≈ 0`.
 */
internal fun orphanRecipeDetailState(): RecipeDetailUiState = RecipeDetailUiState.of(
    detail = RecipePreviewData.curry(),
    foods = RecipePreviewData.catalogueById(),
)

/** The card of a recipe with no ingredient at all, which shows no total whatsoever. */
internal fun emptyRecipeDetailState(): RecipeDetailUiState = RecipeDetailUiState.of(
    detail = RecipePreviewData.emptyRecipe(),
    foods = emptyMap(),
)

/** The form as it reopens on a saved recipe (FR-RECIPE-006), every quantity already typed. */
internal fun previewRecipeEditorState(): RecipeEditorUiState {
    val detail = RecipePreviewData.salmon()
    return RecipeEditorUiState.of(
        draft = RecipeDraft.of(detail.recipe, detail.ingredients),
        foods = RecipePreviewData.catalogueById(),
    )
}

/**
 * A blank form that has just been refused (PRD_FOOD 15).
 *
 * Every sentence on it comes back from a validator, including the one that stops a recipe with
 * no ingredient from being saved into a card that would read `0 kcal` for ever.
 */
internal fun refusedRecipeEditorState(): RecipeEditorUiState = RecipeEditorUiState.of(
    draft = RecipeDraft(baseServings = "0"),
    showErrors = true,
)

/**
 * The form with its ingredient picker open (PRD_FOOD 11 and FR-RECIPE-002).
 *
 * [addedCount] is what the sheet's own action reports: it stays open after a pick, so it has to
 * say what it has done.
 */
internal fun pickerRecipeEditorState(addedCount: Int = 0): RecipeEditorUiState {
    val detail = RecipePreviewData.salmon()
    return RecipeEditorUiState.of(
        draft = RecipeDraft.of(detail.recipe, detail.ingredients),
        foods = RecipePreviewData.catalogueById(),
        picker = RecipePickerUiState(
            visible = true,
            addedCount = addedCount,
            results = RecipePreviewData.catalogue().map { food ->
                RecipePickerRowUiState(
                    id = food.id.value,
                    name = food.name,
                    meta = food.brand,
                    iconName = FoodIcons.forSource(food.source),
                )
            },
        ),
    )
}

/**
 * The form opened on a recipe naming a food this device never received (PRD_FOOD 21.2).
 *
 * The row keeps its snapshot and its quantity, its energy reads `—`, and the form is still
 * saveable: refusing would lose the ingredient the snapshot exists to preserve.
 */
internal fun orphanRecipeEditorState(): RecipeEditorUiState {
    val detail = RecipePreviewData.curry()
    return RecipeEditorUiState.of(
        draft = RecipeDraft.of(detail.recipe, detail.ingredients),
        foods = RecipePreviewData.catalogueById(),
    )
}
