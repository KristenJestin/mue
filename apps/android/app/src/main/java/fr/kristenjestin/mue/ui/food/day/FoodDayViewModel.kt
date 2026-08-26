package fr.kristenjestin.mue.ui.food.day

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.logic.MealSlotRules
import fr.kristenjestin.mue.domain.logic.NutritionMath
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.repository.FoodCatalogueRepository
import fr.kristenjestin.mue.domain.repository.FoodLogRepository
import fr.kristenjestin.mue.domain.repository.MealPlanRepository
import fr.kristenjestin.mue.domain.repository.RecipeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The state of the `Day` screen, and the two writes its proposal cards perform (PRD_FOOD 10
 * and 12).
 *
 * **The day being looked at lives here and not in a route.** `FoodRoute` says why: navigating by
 * date inside `Day` would mint a stack entry — and a saved state slot — on every step through
 * the week. It belongs to the screen's own saved state, which is what [SavedStateHandle] is, so
 * a day reached by walking back four days survives a rotation and a process death without ever
 * appearing in the module's stack.
 *
 * It is stored as an ISO string rather than as a `LocalDate` because that is what a `Bundle`
 * can carry, and it is read back defensively: a value written by another build is a day to
 * forget, not a crash on the first frame.
 *
 * Nothing here adds anything up. [fr.kristenjestin.mue.domain.logic.DailyNutritionSummary],
 * [MealSlotRules] and [FoodLabels] own the totals, the grouping and the strings, and
 * [FoodDayUiState.of] is the single call that puts them together — so PRD_FOOD 13.1's strict
 * `null` propagation is proved once, in the domain, and cannot be undone here.
 */
class FoodDayViewModel(
    private val logs: FoodLogRepository,
    private val plans: MealPlanRepository,
    private val recipes: RecipeRepository,
    private val foods: FoodCatalogueRepository,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: () -> Locale = Locale::getDefault,
) : ViewModel() {

    private val dates: Flow<LocalDate> = savedStateHandle
        .getStateFlow<String?>(KEY_DATE, null)
        .map(::dateOrToday)
        .distinctUntilChanged()

    /**
     * One day's rows, re-subscribed whenever the date moves.
     *
     * The reading carries the date it was made for, exactly as `ActivityViewModel`'s week does:
     * while the new day is still being read the old rows are still arriving, and a state built
     * from a date and rows that disagree would show yesterday's dinner under today's heading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val readings: Flow<DayReading> = dates.flatMapLatest { date ->
        combine(
            logs.observeDay(date),
            plans.observeDay(date),
            recipes.observeAll(),
        ) { entries, dayPlans, allRecipes ->
            DayReading(
                date = date,
                entries = entries,
                plans = dayPlans,
                recipeNames = allRecipes.associate { it.id to it.name },
            )
        }
    }

    val uiState: StateFlow<FoodDayUiState> = combine(
        dates,
        readings,
        savedStateHandle.getStateFlow(KEY_DATE_PICKER, false),
    ) { date, reading, datePicker ->
        buildState(date, reading, datePicker)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = buildState(
            date = dateOrToday(savedStateHandle[KEY_DATE]),
            reading = DayReading.PENDING,
            datePicker = savedStateHandle[KEY_DATE_PICKER] ?: false,
        ),
    )

    // region date navigation (PRD_FOOD 10.1)

    fun onPreviousDay() {
        select(viewedDate().minusDays(1))
    }

    /**
     * PRD_FOOD 22: "un jour futur ne peut pas être complété".
     *
     * The button is disabled on today, and the guard is here as well: a disabled control that
     * would still act if pressed is one accessibility service away from being pressed.
     */
    fun onNextDay() {
        val next = viewedDate().plusDays(1)
        if (FoodLogEntry.isLoggableOn(next, today())) select(next)
    }

    fun onShowDatePicker() {
        savedStateHandle[KEY_DATE_PICKER] = true
    }

    fun onDismissDatePicker() {
        savedStateHandle[KEY_DATE_PICKER] = false
    }

    /** A day chosen from the calendar; a future one is refused rather than silently accepted. */
    fun onDayPicked(date: LocalDate) {
        val today = today()
        select(if (FoodLogEntry.isLoggableOn(date, today)) date else today)
        onDismissDatePicker()
    }

    /** Back to today, which is where the screen opens (PRD_FOOD 10.1). */
    fun onToday() {
        select(today())
    }

    // endregion

    // region proposals (PRD_FOOD 12)

    /**
     * `I ate this`: the proposal becomes a real line, and the two stay linked.
     *
     * The line's values are computed once, here, and frozen on it (PRD_FOOD 8.4): the recipe may
     * be edited or deleted afterwards and this line will not move. Every step of that
     * computation is [NutritionMath]'s — the strict sum of the ingredients, the division by
     * `baseServings`, the multiplication by the portions eaten — so a proposal confirmed from
     * this screen is worth exactly what the same recipe logged by hand is worth.
     *
     * An ingredient whose food is missing from the catalogue makes its contribution unknown
     * rather than zero, which [NutritionMath.ingredientContribution] already guarantees; the
     * line is saved all the same, with `—` where nothing is known.
     */
    fun onConfirmPlan(key: MealPlanKey) {
        viewModelScope.launch {
            val plan = plans.find(key) ?: return@launch
            if (plan.isConsumed) return@launch
            val detail = recipes.findDetail(plan.recipeId) ?: return@launch
            val catalogue: Map<FoodId, Food> =
                foods.findByIds(detail.foodIds).associateBy(Food::id)
            val amount = LoggedAmount.Portioned(plan.plannedServings)
            val entry = FoodLogEntry(
                id = FoodLogEntryId.random(),
                consumedOn = key.plannedOn,
                consumedAt = MealSlotRules.defaultTime(
                    slot = key.slot,
                    date = key.plannedOn,
                    today = today(),
                    now = now(),
                ),
                slot = key.slot,
                kind = FoodLogKind.RECIPE,
                title = detail.recipe.name,
                amount = amount,
                nutrients = NutritionMath.recipeLine(detail, catalogue, plan.plannedServings),
                /*
                 * PRD_FOOD 13.2: "toute valeur issue d'un calcul ou d'une source externe est
                 * précédée de `≈`", and a recipe line is nothing but a calculation. PRD_FOOD 8.4
                 * would rather derive this from the ingredients — approximate as soon as one of
                 * them is — but no `Food` and no `RecipeIngredient` in the frozen model carries
                 * an `Estimation` to derive it from, so the honest answer is the cautious one.
                 */
                estimation = Estimation.APPROXIMATE,
                sourceRef = plan.recipeId.value,
                amountLabel = FoodLabels.amountLabel(amount),
                fromPlan = key,
            )
            logs.save(entry)
            plans.setConsumed(key, entry.id)
        }
    }

    /** `Dismiss`: the moment is freed, and neither the recipe nor the journal is touched. */
    fun onDismissPlan(key: MealPlanKey) {
        viewModelScope.launch { plans.delete(key) }
    }

    // endregion

    private fun buildState(
        date: LocalDate,
        reading: DayReading,
        datePicker: Boolean,
    ): FoodDayUiState {
        val settled = reading.date == date
        return FoodDayUiState.of(
            date = date,
            today = today(),
            entries = if (settled) reading.entries else emptyList(),
            plans = if (settled) reading.plans else emptyList(),
            recipeNames = if (settled) reading.recipeNames else emptyMap(),
            isLoading = !settled,
            isDatePickerVisible = datePicker,
            locale = locale(),
        )
    }

    private fun select(date: LocalDate) {
        savedStateHandle[KEY_DATE] = date.toString()
    }

    private fun viewedDate(): LocalDate = dateOrToday(savedStateHandle[KEY_DATE])

    /**
     * The stored day, or today.
     *
     * Total and non-throwing, for the reason `FoodRoute.fromKey` is: a value saved by another
     * build outlives the code that wrote it, and opening on today is a better outcome than a
     * crash before the first frame.
     */
    private fun dateOrToday(stored: String?): LocalDate {
        if (stored.isNullOrBlank()) return today()
        return try {
            LocalDate.parse(stored)
        } catch (_: DateTimeParseException) {
            today()
        }
    }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun now(): LocalTime = LocalTime.now(clock)

    /**
     * One day's rows and the day they were read for, kept together on purpose.
     *
     * [PENDING] is the state before anything has been read at all: no date, so it matches no
     * day, so the screen knows it is still loading rather than looking at an empty journal.
     */
    private data class DayReading(
        val date: LocalDate?,
        val entries: List<FoodLogEntry> = emptyList(),
        val plans: List<MealPlanEntry> = emptyList(),
        val recipeNames: Map<RecipeId, String> = emptyMap(),
    ) {
        companion object {
            val PENDING: DayReading = DayReading(date = null)
        }
    }

    companion object {

        /** The day on screen, ISO-8601, which is what a `Bundle` can carry across a death. */
        internal const val KEY_DATE: String = "food.day.date"

        internal const val KEY_DATE_PICKER: String = "food.day.datePicker"

        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * The factory this screen is created from once the journal has a store behind it.
         *
         * The four repositories are handed in rather than read off `AppContainer`, because none
         * of them is registered there yet: PRD_FOOD 20's five Room tables land in a parallel
         * change, and the interfaces shipped first precisely so that this ViewModel could be
         * written and tested against them in the meantime. Wiring the tab is then one call.
         */
        fun factory(
            logs: FoodLogRepository,
            plans: MealPlanRepository,
            recipes: RecipeRepository,
            foods: FoodCatalogueRepository,
            clock: Clock = Clock.systemDefaultZone(),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FoodDayViewModel(
                    logs = logs,
                    plans = plans,
                    recipes = recipes,
                    foods = foods,
                    savedStateHandle = createSavedStateHandle(),
                    clock = clock,
                )
            }
        }
    }
}
