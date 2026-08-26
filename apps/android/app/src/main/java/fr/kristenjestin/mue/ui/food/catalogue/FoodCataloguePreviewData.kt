package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.ui.food.day.FoodDayPreviewData

/**
 * One catalogue, shared by the previews, the Compose tests and the screenshots.
 *
 * It lives in `main` rather than in a test source set for the reason `FoodDayPreviewData` does:
 * a `@Preview`, an instrumented test and a screenshot all have to be looking at the *same*
 * catalogue, and three fixtures would drift until the picture no longer showed what the test
 * asserted.
 *
 * The five foods are chosen so that PRD_FOOD 13.2 is visible in one screenful. [blackCoffee]
 * knows its values and every one of them is **zero**; [greekYoghurt] is missing its fibre, which
 * nobody wrote down; [auntsCake] has no value at all, which PRD_FOOD 15 accepts as ordinary.
 * Drawn side by side, `≈ 0.0 g fibre` and `— fibre` and a card of five dashes are three
 * different statements, and the module is wrong the moment any two of them look alike.
 */
internal object FoodCataloguePreviewData {

    /** PRD_FOOD 15's 80-character ceiling, borrowed rather than written a second time. */
    const val LONGEST_NAME: String = FoodDayPreviewData.LONGEST_NAME

    const val OATS_NAME: String = "Rolled oats"
    const val YOGHURT_NAME: String = "Greek yoghurt, 0% fat"
    const val COFFEE_NAME: String = "Black coffee"
    const val CAKE_NAME: String = "Aunt Simone's walnut cake"
    const val YOGHURT_BRAND: String = "Bjorg"
    const val YOGHURT_BARCODE: String = "3229820129488"

    /** PRD_FOOD 9.1: an entry of the embedded subset. Read-only, duplicable, never edited. */
    fun rolledOats(): Food = Food(
        id = FoodId("preview-oats"),
        name = OATS_NAME,
        source = FoodSource.CIQUAL,
        sourceId = "9620",
        sourceVersion = "ciqual-2025.1",
        per100 = Nutrients(
            energy = energy(370.0),
            protein = macro(13.2),
            carbs = macro(58.7),
            fat = macro(7.0),
            fibre = macro(10.1),
        ),
    )

    /** The same provenance, at the longest name a food may carry (PRD_FOOD 15). */
    fun longestNamedFood(): Food = Food(
        id = FoodId("preview-longest"),
        name = LONGEST_NAME,
        source = FoodSource.CIQUAL,
        sourceId = "26034",
        per100 = Nutrients(
            energy = energy(146.0),
            protein = macro(8.4),
            carbs = macro(14.9),
            fat = macro(5.7),
            fibre = macro(3.2),
        ),
    )

    /**
     * PRD_FOOD 9.2: a product copied from Open Food Facts, incomplete and editable.
     *
     * Its fibre is `null` because the remote card does not state one. PRD_FOOD 9.2 calls that
     * the nominal case, and PRD_FOOD 13.2 draws it `—`.
     */
    fun greekYoghurt(): Food = Food(
        id = FoodId("preview-yoghurt"),
        name = YOGHURT_NAME,
        source = FoodSource.OPEN_FOOD_FACTS,
        brand = YOGHURT_BRAND,
        barcode = YOGHURT_BARCODE,
        sourceId = YOGHURT_BARCODE,
        per100 = Nutrients(
            energy = energy(59.0),
            protein = macro(10.3),
            carbs = macro(3.6),
            fat = macro(0.2),
            fibre = null,
        ),
        servingLabel = "pot",
        servingSize = Quantity.ofUsualServingOrNull(150.0),
    )

    /**
     * A personal food whose values are all **known zeros** (PRD_FOOD 13.2).
     *
     * Black coffee really has no energy and no protein. It is the foil for [greekYoghurt]: one
     * of them says "there is none" and the other says "nobody said", and the two must never be
     * drawn alike.
     */
    fun blackCoffee(): Food = Food(
        id = FoodId("preview-coffee"),
        name = COFFEE_NAME,
        source = FoodSource.CUSTOM,
        referenceUnit = ReferenceUnit.MILLILITRE,
        per100 = Nutrients(
            energy = Energy.ZERO,
            protein = Macro.ZERO,
            carbs = Macro.ZERO,
            fat = Macro.ZERO,
            fibre = Macro.ZERO,
        ),
    )

    /** PRD_FOOD 15: "aliment sans aucune valeur : accepté". Five dashes, and no error. */
    fun auntsCake(): Food = Food(
        id = FoodId("preview-cake"),
        name = CAKE_NAME,
        source = FoodSource.CUSTOM,
        per100 = Nutrients.UNKNOWN,
    )

    fun catalogue(): List<Food> = listOf(
        rolledOats(),
        longestNamedFood(),
        greekYoghurt(),
        blackCoffee(),
        auntsCake(),
    )

    private fun energy(kilocalories: Double): Energy =
        requireNotNull(Energy.ofPer100OrNull(kilocalories))

    private fun macro(grams: Double): Macro = requireNotNull(Macro.ofPer100OrNull(grams))
}

/**
 * The catalogue as it is browsed with nothing typed, the recently used at the top.
 *
 * The two lists do not overlap, exactly as the screen's own state does not: a food shown under
 * `Recently used` is left out of the catalogue below it, so no food is drawn twice.
 */
internal fun previewFoodsState(showEnergy: Boolean = true): FoodsUiState {
    val recent = listOf(FoodCataloguePreviewData.greekYoghurt())
    val recentIds = recent.map { it.id }.toSet()
    val rest = FoodCataloguePreviewData.catalogue().filterNot { it.id in recentIds }

    return FoodsUiState(
        isLoading = false,
        recent = recent.map { FoodRowUiState.of(it, showEnergy) },
        results = rest.map { FoodRowUiState.of(it, showEnergy) },
        matchCount = FoodCataloguePreviewData.catalogue().size,
        showEnergy = showEnergy,
    )
}

/**
 * PRD_FOOD 17: a search that matched nothing, which offers the creation instead.
 *
 * The picture to look at when that criterion is in question: no result, one sentence naming what
 * was typed, and one button carrying it into the form.
 */
internal fun noMatchFoodsState(query: String = "kombucha"): FoodsUiState = FoodsUiState(
    query = query,
    isLoading = false,
    results = emptyList(),
)

/** PRD_FOOD 13.2 and FR-FOOD-010: the same catalogue with every figure withheld. */
internal fun hiddenEnergyFoodsState(): FoodsUiState = previewFoodsState(showEnergy = false)

/** The editor over a copied product, which keeps its barcode and its provenance. */
internal fun previewFoodEditorState(): FoodEditorUiState = FoodEditorUiState.of(
    draft = FoodEditorDraft.of(FoodCataloguePreviewData.greekYoghurt()),
    mode = FoodEditorMode.EDIT,
    source = FoodSource.OPEN_FOOD_FACTS,
)

/** The editor over a reference entry: nothing to type, one thing to do — duplicate it. */
internal fun referenceFoodEditorState(): FoodEditorUiState = FoodEditorUiState.of(
    draft = FoodEditorDraft.of(FoodCataloguePreviewData.rolledOats()),
    mode = FoodEditorMode.REFERENCE,
    source = FoodSource.CIQUAL,
)

/** PRD_FOOD 15: two refused values at once, each beside its own field, and nothing emptied. */
internal fun refusedFoodEditorState(): FoodEditorUiState = FoodEditorUiState.of(
    draft = FoodEditorDraft(
        name = "",
        brand = "Bjorg",
        energy = "1200",
        attempted = true,
    ),
    mode = FoodEditorMode.CREATE,
    source = FoodSource.CUSTOM,
)

/**
 * PRD_FOOD 15's one rule that belongs to no single field: the known macronutrients may not add
 * up past 100 g. The three figures are each valid on their own, which is the whole point.
 */
internal fun macroSumRefusedFoodEditorState(): FoodEditorUiState = FoodEditorUiState.of(
    draft = FoodEditorDraft(
        name = "Impossible paste",
        energy = "500",
        protein = "60",
        carbs = "60",
        fat = "40",
        attempted = true,
    ),
    mode = FoodEditorMode.CREATE,
    source = FoodSource.CUSTOM,
)
