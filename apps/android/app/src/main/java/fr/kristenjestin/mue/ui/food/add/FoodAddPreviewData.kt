package fr.kristenjestin.mue.ui.food.add

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
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.ui.food.day.FoodDayPreviewData
import fr.kristenjestin.mue.ui.food.recipes.RecipePreviewData
import java.time.LocalDate
import java.time.LocalTime

/**
 * The foods and the drafts the add flow is drawn, photographed and asserted against.
 *
 * In `main` rather than in a test source set for the reason `FoodDayPreviewData` gives: the
 * previews, the Compose tests and the screenshots those tests write all need the *same* fixture,
 * and a copy in each is how a `—` becomes a `0` without anyone noticing.
 *
 * The four foods below are shaped to carry every reading PRD_FOOD 13 has at once:
 *
 * - **rice** — a cooking ratio, so the raw/cooked selector appears and a cooked weight is
 *   converted before it counts; its fibre is unknown, so a `—` sits beside real numbers;
 * - **apple** — a usual portion, so the counter appears beside the exact weight;
 * - **espresso** — every metric a **known zero**, which is a fact about black coffee;
 * - **the long name** — PRD_FOOD 15's 80-character ceiling, which a one-line row would cut.
 */
internal object FoodAddPreviewData {

    val TODAY: LocalDate = FoodDayPreviewData.TODAY

    /** Mid-evening, so a new line lands in dinner without the fixture saying so twice. */
    val NOW: LocalTime = LocalTime.of(19, 40)

    const val RICE_NAME: String = "Rice, white, long grain, raw"
    const val APPLE_NAME: String = "Apple, flesh without skin, raw (average)"
    const val ESPRESSO_NAME: String = "Espresso, no sugar"
    const val QUICK_NAME: String = "Restaurant tiramisu"

    /** PRD_FOOD 15's ceiling, borrowed from the `Day` fixture so both screens test one name. */
    const val LONGEST_NAME: String = FoodDayPreviewData.LONGEST_NAME

    /**
     * PRD_FOOD 8.6's own example: dry rice, whose mass changes on the hob and whose values
     * describe the state `rawLabel` names.
     *
     * Its fibre is deliberately unknown. Ciqual leaves 48 of the 1 038 seeded entries without
     * one, so a `—` beside four numbers is the ordinary case rather than a contrived one.
     */
    fun rice(): Food = Food(
        id = FoodId("preview-rice"),
        name = RICE_NAME,
        source = FoodSource.CIQUAL,
        referenceUnit = ReferenceUnit.GRAM,
        per100 = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(349.0),
            protein = Macro.ofGramsOrNull(7.2),
            carbs = Macro.ofGramsOrNull(78.4),
            fat = Macro.ofGramsOrNull(0.6),
            fibre = null,
        ),
        sourceId = "9110",
        cookedRatio = CookedRatio.ofRatioOrNull(2.26),
    )

    /** FR-FOOD-006: a food declaring a usual portion, so the counter has something to count. */
    fun apple(): Food = Food(
        id = FoodId("preview-apple"),
        name = APPLE_NAME,
        source = FoodSource.CIQUAL,
        per100 = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(51.3),
            protein = Macro.ofGramsOrNull(0.26),
            carbs = Macro.ofGramsOrNull(11.9),
            fat = Macro.ofGramsOrNull(0.24),
            fibre = Macro.ofGramsOrNull(2.39),
        ),
        sourceId = "13396",
        servingLabel = "1 apple",
        servingSize = Quantity.ofAmountOrNull(150.0),
    )

    /** A known zero in every metric: black coffee really has none of any of them. */
    fun espresso(): Food = Food(
        id = FoodId("preview-espresso"),
        name = ESPRESSO_NAME,
        source = FoodSource.CIQUAL,
        referenceUnit = ReferenceUnit.MILLILITRE,
        per100 = Nutrients.ZERO,
        sourceId = "18066",
    )

    /** A personal food at PRD_FOOD 15's 80-character ceiling, with a brand beside it. */
    fun longNamed(): Food = Food(
        id = FoodId("preview-long"),
        name = LONGEST_NAME,
        source = FoodSource.CUSTOM,
        per100 = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(173.1),
            protein = Macro.ofGramsOrNull(16.4),
            carbs = Macro.ofGramsOrNull(14.1),
            fat = Macro.ofGramsOrNull(5.1),
            fibre = Macro.ofGramsOrNull(3.2),
        ),
        brand = "Home",
    )

    fun catalogue(): List<Food> = listOf(rice(), apple(), espresso(), longNamed())

    /** A line built from a recipe, which FR-FOOD-008 corrects by its servings alone. */
    fun recipeEntry(): FoodLogEntry = FoodLogEntry(
        id = FoodLogEntryId("preview-recipe-line"),
        consumedOn = TODAY,
        consumedAt = LocalTime.of(20, 10),
        slot = MealSlot.DINNER,
        kind = FoodLogKind.RECIPE,
        title = "Sheet-pan salmon & greens",
        amount = LoggedAmount.Portioned(servings(1.0)),
        nutrients = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(432.0),
            protein = Macro.ofGramsOrNull(31.0),
            carbs = Macro.ofGramsOrNull(28.0),
            fat = Macro.ofGramsOrNull(19.0),
            fibre = null,
        ),
        estimation = Estimation.APPROXIMATE,
        sourceRef = "preview-salmon",
        amountLabel = "1 × serving",
    )

    /** PRD_FOOD 17: a stored line whose catalogue entry has since been deleted. */
    fun orphanedEntry(): FoodLogEntry = FoodLogEntry(
        id = FoodLogEntryId("preview-orphan"),
        consumedOn = TODAY,
        consumedAt = LocalTime.of(12, 30),
        slot = MealSlot.LUNCH,
        kind = FoodLogKind.FOOD,
        title = "A food that has since been deleted",
        amount = LoggedAmount.Measured(quantity(180.0), ReferenceUnit.GRAM),
        nutrients = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(211.0),
            protein = Macro.ofGramsOrNull(12.0),
            carbs = null,
            fat = null,
            fibre = null,
        ),
        estimation = Estimation.MEASURED,
        sourceRef = "gone",
        amountLabel = "180 g",
    )

    /** A draft aimed at tonight's dinner, which is where every preview below starts. */
    fun draft(slot: MealSlot = MealSlot.DINNER): FoodAddDraft =
        FoodAddDraft.forTarget(date = TODAY, slot = slot, today = TODAY, now = NOW)

    /** The barcode every scan fixture is built around: the recorded Nutella card's own. */
    const val SCANNED_BARCODE: String = "3017620422003"

    /**
     * A product card as Open Food Facts really returns one, with a gap in it (PRD_FOOD 9.2).
     *
     * Its shape is the recorded Nutella fixture's: four values from the manufacturer, and **no
     * fibre**, because the only figure Open Food Facts has for it is one it estimated from the
     * ingredient list and PRD_FOOD 17 refuses estimates. It is the fixture that makes an
     * incomplete card visible in a preview and in a test — a `—` on a real product, beside four
     * real numbers, rather than an invented `0`.
     */
    fun scannedProduct(): Food = Food(
        id = FoodId("preview-scanned"),
        name = "Nutella",
        source = FoodSource.OPEN_FOOD_FACTS,
        referenceUnit = ReferenceUnit.GRAM,
        per100 = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(539.0),
            protein = Macro.ofGramsOrNull(6.3),
            carbs = Macro.ofGramsOrNull(57.5),
            fat = Macro.ofGramsOrNull(30.9),
            fibre = null,
        ),
        brand = "Ferrero",
        barcode = SCANNED_BARCODE,
        sourceId = SCANNED_BARCODE,
        sourceVersion = "v3.6/947",
    )

    private fun quantity(amount: Double): Quantity =
        requireNotNull(Quantity.ofIngredientOrNull(amount)) { "$amount is not a quantity" }

    private fun servings(count: Double): Servings =
        requireNotNull(Servings.ofConsumedOrNull(count)) { "$count is not a serving count" }
}

/** PRD_FOOD 7's ways in, which is what the sheet opens on when nothing has been chosen. */
internal fun previewPathsState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft(),
    today = FoodAddPreviewData.TODAY,
)

/**
 * The scan stage as somebody with **no camera permission** meets it (PRD_FOOD 17 and 18).
 *
 * The picture that has to be checked by eye rather than only by test: the explanation is quiet
 * rather than alarming, the field under it is a full-width control with its own label and its own
 * button, and nothing about the panel reads as a degraded version of another one. That is what
 * "une alternative complète à la caméra" has to look like.
 */
internal fun previewScanRefusedState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(scanning = true),
    today = FoodAddPreviewData.TODAY,
).let { state ->
    state.copy(
        scan = state.scan?.withCamera(isGranted = false, isAvailable = true, canRequest = false),
    )
}

/**
 * A product found, before it is copied into the catalogue (PRD_FOOD 9.2).
 *
 * What to look at: the fibre row reads `—` while the four around it carry numbers. Open Food
 * Facts does have a fibre figure for this card and it marked it `estimate`; PRD_FOOD 17 refuses
 * estimates, so Mue does not know it. The value stays empty and stays editable once the product
 * is added — which is the sentence printed under the figures.
 */
internal fun previewScanFoundState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(
        scanning = true,
        scanBarcode = FoodAddPreviewData.SCANNED_BARCODE,
    ),
    today = FoodAddPreviewData.TODAY,
    scan = FoodScanState.Found(FoodAddPreviewData.scannedProduct(), alreadyInCatalogue = false),
).let { state ->
    state.copy(
        scan = state.scan?.withCamera(isGranted = true, isAvailable = true, canRequest = false),
    )
}

/**
 * 600 g of rice **weighed cooked** (PRD_FOOD 8.6 and 13.1).
 *
 * The one picture that says why the raw/cooked selector exists: the same 600 g read as raw would
 * count 2 094 kcal, where the reference weight behind these figures is 265.487 g.
 */
internal fun previewCookedState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(
        foodId = FoodAddPreviewData.rice().id.value,
        quantity = "600",
        weighedCooked = true,
    ),
    food = FoodAddPreviewData.rice(),
    today = FoodAddPreviewData.TODAY,
)

/** The portion counter of FR-FOOD-006, on a food that declares one. */
internal fun previewPortionsState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft(MealSlot.SNACK).copy(
        foodId = FoodAddPreviewData.apple().id.value,
        portionThousandths = Servings.THOUSANDTHS_PER_SERVING + Servings.USUAL_STEP_THOUSANDTHS,
        quantity = "225",
    ),
    food = FoodAddPreviewData.apple(),
    today = FoodAddPreviewData.TODAY,
)

/** PRD_FOOD 15's longest name, weighed, for the narrow and large-font picture. */
internal fun previewLongNameState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(
        foodId = FoodAddPreviewData.longNamed().id.value,
        quantity = "225",
    ),
    food = FoodAddPreviewData.longNamed(),
    today = FoodAddPreviewData.TODAY,
)

/**
 * A quick add whose energy is stated and whose protein is not (FR-FOOD-005, PRD_FOOD 13.1).
 *
 * Its figures read `≈ 300 kcal` beside four `—`. Held against a food whose values are all known
 * zeros, the two drawings are the whole of the module's null discipline.
 */
internal fun previewQuickState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(
        kindId = FoodLogKind.QUICK.id,
        quickTitle = FoodAddPreviewData.QUICK_NAME,
        quickEnergy = "300",
    ),
    today = FoodAddPreviewData.TODAY,
)

/** A known zero in every metric, which is not the same screen as the one above. */
internal fun previewKnownZeroState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft().copy(
        foodId = FoodAddPreviewData.espresso().id.value,
        quantity = "30",
    ),
    food = FoodAddPreviewData.espresso(),
    today = FoodAddPreviewData.TODAY,
)

/** FR-FOOD-008 on a recipe line: its servings, rescaled from what the line was saved with. */
internal fun previewServingsState(): FoodAddUiState {
    val entry = FoodAddPreviewData.recipeEntry()
    return FoodAddUiState.of(
        draft = FoodAddDraft.forEntry(entry).copy(servings = "1.5"),
        original = entry,
        today = FoodAddPreviewData.TODAY,
    )
}

/** PRD_FOOD 17: a line whose food is gone keeps its values and moves only in time. */
internal fun previewOrphanedState(): FoodAddUiState {
    val entry = FoodAddPreviewData.orphanedEntry()
    return FoodAddUiState.of(
        draft = FoodAddDraft.forEntry(entry),
        original = entry,
        today = FoodAddPreviewData.TODAY,
    )
}

/**
 * Breakfast at six in the evening (PRD_FOOD 10.3).
 *
 * The pairing the owner could not read — "je peux sélectionner breakfast, mais avoir un time à
 * 18h, je comprends pas" — and the two things that now explain it: the hours under every moment's
 * name, and the sentence under the time field naming the moment the clock would have chosen. It
 * is still saved exactly as chosen; the windows "ne créent aucune contrainte".
 */
internal fun previewLateBreakfastState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft(MealSlot.BREAKFAST)
        .withTime(LocalTime.of(18, 0))
        .copy(
            foodId = FoodAddPreviewData.apple().id.value,
            quantity = "150",
            timePinned = true,
        ),
    food = FoodAddPreviewData.apple(),
    today = FoodAddPreviewData.TODAY,
)

/**
 * The picker on an empty search: what was logged most recently, **and the catalogue under it**
 * (PRD_FOOD 9.4).
 *
 * The two lists are disjoint here for the reason the ViewModel de-duplicates them: a food drawn
 * under both headings would be two cards for one food, and two nodes answering to one test tag.
 */
internal fun previewPickerState(): FoodPickerUiState = FoodPickerUiState(
    query = "",
    sources = sourceFilters(null),
    recent = listOf(FoodAddPreviewData.apple()).map(FoodPickerRowUiState::of),
    results = FoodAddPreviewData.catalogue()
        .filterNot { it.id == FoodAddPreviewData.apple().id }
        .map(FoodPickerRowUiState::of),
    isRecent = true,
    recentTitle = FoodAddMessages.RECENT_SECTION,
    sectionTitle = FoodAddMessages.CATALOGUE_SECTION,
    emptyMessage = null,
)

/**
 * The same screen on a phone that has logged nothing yet.
 *
 * The head is empty and the catalogue is not, which is the whole of the first defect: 1 038
 * seeded entries were hidden behind `Nothing logged yet` because the recents were read as the
 * list rather than as its head.
 */
internal fun previewPickerNothingLoggedState(): FoodPickerUiState = FoodPickerUiState(
    query = "",
    sources = sourceFilters(null),
    recent = emptyList(),
    results = FoodAddPreviewData.catalogue().map(FoodPickerRowUiState::of),
    isRecent = true,
    recentTitle = FoodAddMessages.RECENT_SECTION,
    sectionTitle = FoodAddMessages.CATALOGUE_SECTION,
    emptyMessage = null,
)

/** PRD_FOOD 17: a search that matches nothing offers the creation instead. */
internal fun previewEmptyPickerState(): FoodPickerUiState = FoodPickerUiState(
    query = "sauerkraut ice cream",
    sources = sourceFilters(null),
    recent = emptyList(),
    results = emptyList(),
    isRecent = false,
    recentTitle = FoodAddMessages.RECENT_SECTION,
    sectionTitle = FoodAddMessages.RESULTS_SECTION,
    emptyMessage = FoodAddMessages.NO_RESULTS,
)

/**
 * The recipe picker with something to choose (FR-FOOD-004).
 *
 * It borrows `RecipePreviewData`'s own recipes rather than inventing three more: the picker and
 * the `Recipes` view show the same objects, and two fixtures for one catalogue is how the two
 * screens end up disagreeing about what a recipe looks like.
 */
internal fun previewRecipePickerState(): RecipePickerUiState = RecipePickerUiState(
    query = "",
    results = RecipePreviewData.recipes().map(RecipePickerRowUiState::of),
    sectionTitle = FoodAddMessages.RECIPE_RESULTS_SECTION,
    emptyMessage = null,
    hasAnyRecipe = true,
)

/** PRD_FOOD 17: "aucune recette enregistrée" — the invitation, and no fake recipe. */
internal fun previewEmptyRecipePickerState(): RecipePickerUiState = RecipePickerUiState(
    query = "",
    results = emptyList(),
    sectionTitle = FoodAddMessages.RECIPE_RESULTS_SECTION,
    emptyMessage = FoodAddMessages.NO_RECIPES,
    hasAnyRecipe = false,
)

/**
 * FR-FOOD-004 on the sheet: a recipe chosen, and the servings still to state.
 *
 * The figures under the field are `Per serving` until a count is typed, which is the recipe's
 * answer to the per-100 card a food shows at the same moment.
 */
internal fun previewRecipeServingsState(): FoodAddUiState = FoodAddUiState.of(
    draft = FoodAddPreviewData.draft(MealSlot.DINNER).copy(
        kindId = FoodLogKind.RECIPE.id,
        recipeId = RecipePreviewData.SALMON_ID.value,
        servings = "1.5",
    ),
    recipe = FoodAddRecipe(RecipePreviewData.salmon(), RecipePreviewData.catalogueById()),
    today = FoodAddPreviewData.TODAY,
)

private fun sourceFilters(selected: FoodSource?): List<FoodSourceFilterUiState> =
    listOf<FoodSource?>(null).plus(FoodSource.entries).map { source ->
        FoodSourceFilterUiState(
            source = source,
            label = source?.let(FoodAddMessages::sourceLabel) ?: FoodAddMessages.SOURCE_ALL,
            selected = source == selected,
        )
    }
