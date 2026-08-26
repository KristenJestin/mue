package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import java.time.LocalDate
import java.time.LocalTime

/**
 * One day of the journal, shaped to show every reading of a value the module has.
 *
 * It is here in `main` rather than in a test source set because three callers need the very same
 * day: the two `@Preview`s, the Compose tests, and the screenshots those tests write. A fixture
 * copied into each would let the picture that was looked at and the day that was asserted drift
 * apart — which is exactly how a `—` becomes a `0` without anyone noticing.
 *
 * The day deliberately holds all four of PRD_FOOD 13's readings at once:
 *
 * - **known** — the breakfast bowl, `≈ 370 kcal` and `≈ 29.1 g protein`;
 * - **a known zero** — the espresso, `≈ 0 kcal` and `≈ 0.0 g protein`, which is a fact about
 *   black coffee and not an absence;
 * - **unknown** — the restaurant tiramisu, whose protein nobody wrote down: `— protein`, beside
 *   an energy that *is* known, so the moment's protein is `—` while its energy is a number
 *   (PRD_FOOD 22, metric by metric);
 * - **nothing at all** — dinner, which holds one unconfirmed proposal and no line, and therefore
 *   shows no total whatsoever (PRD_FOOD 10.1 and 10.4).
 *
 * The lunch bowl also carries a name at PRD_FOOD 15's 80-character ceiling, because a card that
 * ellipsises it still passes every assertion a semantics string can make.
 */
internal object FoodDayPreviewData {

    val TODAY: LocalDate = LocalDate.of(2026, 8, 24)

    /** Exactly 80 characters: the longest name PRD_FOOD 15 lets a food or a quick add carry. */
    const val LONGEST_NAME: String =
        "Golden chicken grain bowl with roasted squash, pomegranate and a tahini dressing"

    const val BREAKFAST_TITLE: String = "Berry oat & skyr bowl"
    const val ESPRESSO_TITLE: String = "Espresso, no sugar"
    const val TIRAMISU_TITLE: String = "Restaurant tiramisu"
    const val PLANNED_RECIPE: String = "Sheet-pan salmon & greens"

    val PLANNED_RECIPE_ID: RecipeId = RecipeId("preview-salmon")

    /** The recipe line of PRD_FOOD 10.2, every metric known. */
    fun breakfast(date: LocalDate = TODAY): FoodLogEntry = entry(
        id = "preview-breakfast",
        date = date,
        time = LocalTime.of(7, 55),
        slot = MealSlot.BREAKFAST,
        kind = FoodLogKind.RECIPE,
        title = BREAKFAST_TITLE,
        amount = LoggedAmount.Portioned(servings(1.0)),
        nutrients = nutrients(369.5, 29.1, 38.8, 8.6, 5.2),
        amountLabel = "1 × serving",
    )

    /** The food line of PRD_FOOD 10.2, weighed, and named at the 80-character ceiling. */
    fun lunch(date: LocalDate = TODAY): FoodLogEntry = entry(
        id = "preview-lunch",
        date = date,
        time = LocalTime.of(12, 30),
        slot = MealSlot.LUNCH,
        kind = FoodLogKind.FOOD,
        title = LONGEST_NAME,
        amount = LoggedAmount.Measured(quantity(225.0), ReferenceUnit.GRAM),
        nutrients = nutrients(389.4, 36.9, 31.8, 11.4, 7.1),
        amountLabel = "225 g",
    )

    /** A known zero, in every metric. Black coffee really has none of any of them. */
    fun espresso(date: LocalDate = TODAY): FoodLogEntry = entry(
        id = "preview-espresso",
        date = date,
        time = LocalTime.of(13, 10),
        slot = MealSlot.LUNCH,
        kind = FoodLogKind.FOOD,
        title = ESPRESSO_TITLE,
        amount = LoggedAmount.Measured(quantity(30.0), ReferenceUnit.MILLILITRE),
        nutrients = Nutrients.ZERO,
        amountLabel = "30 ml",
        estimation = Estimation.MEASURED,
    )

    /**
     * The quick add of PRD_FOOD 10.2: a name and an energy, and nothing else known.
     *
     * PRD_FOOD 13.1 stores the protein as `null` rather than as `0` — "l'ajout rapide stocke les
     * protéines à `null` lorsqu'elles ne sont pas renseignées" — and this line is what proves
     * the screen keeps the two apart.
     */
    fun tiramisu(date: LocalDate = TODAY): FoodLogEntry = entry(
        id = "preview-tiramisu",
        date = date,
        time = LocalTime.of(16, 30),
        slot = MealSlot.SNACK,
        kind = FoodLogKind.QUICK,
        title = TIRAMISU_TITLE,
        amount = LoggedAmount.Unmeasured,
        nutrients = Nutrients(energy = Energy.ofKilocaloriesOrNull(420.0)),
        amountLabel = null,
    )

    /** PRD_FOOD 12: an unconfirmed proposal, which enters no total. */
    fun plannedDinner(date: LocalDate = TODAY): MealPlanEntry = MealPlanEntry(
        plannedOn = date,
        slot = MealSlot.DINNER,
        recipeId = PLANNED_RECIPE_ID,
        plannedServings = servings(1.5),
    )

    fun entries(date: LocalDate = TODAY): List<FoodLogEntry> =
        listOf(breakfast(date), lunch(date), espresso(date), tiramisu(date))

    fun plans(date: LocalDate = TODAY): List<MealPlanEntry> = listOf(plannedDinner(date))

    val recipeNames: Map<RecipeId, String> = mapOf(PLANNED_RECIPE_ID to PLANNED_RECIPE)

    private fun entry(
        id: String,
        date: LocalDate,
        time: LocalTime,
        slot: MealSlot,
        kind: FoodLogKind,
        title: String,
        amount: LoggedAmount,
        nutrients: Nutrients,
        amountLabel: String?,
        estimation: Estimation = Estimation.APPROXIMATE,
    ): FoodLogEntry = FoodLogEntry(
        id = FoodLogEntryId(id),
        consumedOn = date,
        consumedAt = time,
        slot = slot,
        kind = kind,
        title = title,
        amount = amount,
        nutrients = nutrients,
        estimation = estimation,
        amountLabel = amountLabel,
    )

    private fun nutrients(
        energy: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        fibre: Double,
    ): Nutrients = Nutrients(
        energy = Energy.ofKilocaloriesOrNull(energy),
        protein = Macro.ofGramsOrNull(protein),
        carbs = Macro.ofGramsOrNull(carbs),
        fat = Macro.ofGramsOrNull(fat),
        fibre = Macro.ofGramsOrNull(fibre),
    )

    private fun quantity(amount: Double): Quantity =
        requireNotNull(Quantity.ofIngredientOrNull(amount)) { "$amount is not a quantity" }

    private fun servings(count: Double): Servings =
        requireNotNull(Servings.ofConsumedOrNull(count)) { "$count is not a serving count" }
}

/** The populated day the previews and the screenshots both draw. */
internal fun previewDayState(
    date: LocalDate = FoodDayPreviewData.TODAY,
    today: LocalDate = FoodDayPreviewData.TODAY,
): FoodDayUiState = FoodDayUiState.of(
    date = date,
    today = today,
    entries = FoodDayPreviewData.entries(date),
    plans = FoodDayPreviewData.plans(date),
    recipeNames = FoodDayPreviewData.recipeNames,
)

/**
 * A day nobody has written anything on (PRD_FOOD 10.4 and 17).
 *
 * Four moments, four add buttons, and **no total anywhere** — not a `0`, and not a `—` either.
 */
internal fun emptyDayState(
    date: LocalDate = FoodDayPreviewData.TODAY,
    today: LocalDate = FoodDayPreviewData.TODAY,
): FoodDayUiState = FoodDayUiState.of(date = date, today = today)

/**
 * A day holding **one** line, whose protein is genuinely unknown (PRD_FOOD 13.1 and 13.2).
 *
 * The other half of the module's null discipline, and the state that has to be told apart from
 * [emptyDayState] at a glance. Its snack shows `≈ 420 kcal` beside `— protein`: an energy that
 * is known, a protein that is not, and a moment that is plainly *recorded* — where the empty day
 * shows no total at all. A screen that collapsed the two would be reporting an untouched day and
 * an incomplete one as the same fact.
 */
internal fun unknownProteinDayState(
    date: LocalDate = FoodDayPreviewData.TODAY,
    today: LocalDate = FoodDayPreviewData.TODAY,
): FoodDayUiState = FoodDayUiState.of(
    date = date,
    today = today,
    entries = listOf(FoodDayPreviewData.tiramisu(date)),
)
