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

/** The picker on an empty search: what was logged most recently (PRD_FOOD 9.4). */
internal fun previewPickerState(): FoodPickerUiState = FoodPickerUiState(
    query = "",
    sources = sourceFilters(null),
    results = FoodAddPreviewData.catalogue().map(FoodPickerRowUiState::of),
    isRecent = true,
    sectionTitle = FoodAddMessages.RECENT_SECTION,
    emptyMessage = null,
)

/** PRD_FOOD 17: a search that matches nothing offers the creation instead. */
internal fun previewEmptyPickerState(): FoodPickerUiState = FoodPickerUiState(
    query = "sauerkraut ice cream",
    sources = sourceFilters(null),
    results = emptyList(),
    isRecent = false,
    sectionTitle = FoodAddMessages.RESULTS_SECTION,
    emptyMessage = FoodAddMessages.NO_RESULTS,
)

private fun sourceFilters(selected: FoodSource?): List<FoodSourceFilterUiState> =
    listOf<FoodSource?>(null).plus(FoodSource.entries).map { source ->
        FoodSourceFilterUiState(
            source = source,
            label = source?.let(FoodAddMessages::sourceLabel) ?: FoodAddMessages.SOURCE_ALL,
            selected = source == selected,
        )
    }
