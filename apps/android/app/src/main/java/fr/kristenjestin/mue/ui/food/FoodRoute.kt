package fr.kristenjestin.mue.ui.food

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeId
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The screens of the Food tab (PRD_FOOD 7).
 *
 * PRD_FOOD 7 describes the module in two halves, and this file keeps them apart rather than
 * flattening them into one list. **Four views** — `Day`, `Trends`, `Recipes`, `Foods` — are
 * siblings reached by a switcher, exactly as the four tabs of the shell are; **seven sheets**
 * open over whichever view is showing and are the only thing that stacks. [FoodStack] is that
 * shape: a root that is always a [View], and sheets above it.
 *
 * Each route knows how to write itself as a single string, so the whole stack crosses a `Bundle`
 * as plain text and comes back after process death — the arrangement [ActivityRoute] already
 * uses for the Activity tab.
 *
 * The day whose journal is on screen is **not** part of a route. PRD_FOOD 10.1 navigates by date
 * within `Day`, and putting that date in the key would mint a new stack entry — and a new state
 * slot — on every step through the week; it belongs to the screen's own saved state, beside the
 * scroll position. A sheet that needs a day is handed one, which is what [AddFood.date] is for.
 *
 * The screens themselves do not exist yet. They are shipped as routes first on purpose: every
 * later screen then lands in a directory of its own without this file, [FoodTestTags] or
 * [FoodIcons] having to be reopened.
 *
 * [ActivityRoute]: fr.kristenjestin.mue.ui.activity.ActivityRoute
 */
@Immutable
sealed interface FoodRoute {

    /** Identifies the route in the saved stack, in its state slot and to `AnimatedContent`. */
    val key: String

    /**
     * One of PRD_FOOD 7's four views.
     *
     * They never stack on one another: the switcher above them is a selection, so choosing one
     * replaces the root and takes any open sheet with it. [label] is what that switcher shows.
     */
    sealed interface View : FoodRoute {
        val label: String
    }

    /** PRD_FOOD 10.1: opens on today, six moments, no header band and no daily summary. */
    data object Day : View {
        override val key: String = "day"
        override val label: String = "Day"
    }

    /** PRD_FOOD 10.5: seven days of what was recorded, and the history under them. */
    data object Trends : View {
        override val key: String = "trends"
        override val label: String = "Trends"
    }

    /** PRD_FOOD 11: the saved preparations. */
    data object Recipes : View {
        override val key: String = "recipes"
        override val label: String = "Recipes"
    }

    /** PRD_FOOD 9: the catalogue, generic and personal alike. */
    data object Foods : View {
        override val key: String = "foods"
        override val label: String = "Foods"
    }

    /**
     * PRD_FOOD 7's `Add food`, in its two readings: a new line, or the correction of one that
     * already exists — FR-FOOD-008 reuses this very sheet to edit, as the Activity module's
     * `Log` route does.
     *
     * **A day may travel without a moment, and now usually does.** The `Day` screen's one add
     * action hands over the journal being looked at and nothing else, so that FR-FOOD-007's clock
     * decides the moment — `FoodAddDraft.forTarget` leaves it unpinned exactly when [slot] is
     * null. A [slot] still means "this moment, whatever the hour says", which is what confirming
     * a proposal and correcting a line both need.
     *
     * All three shapes therefore have to survive a `Bundle`. A date-only route written as the
     * bare [ADD_FOOD_KEY] would come back as a sheet aimed at **today** — so a process death
     * while logging Tuesday's supper would have moved it to Wednesday, silently, in the one place
     * nobody looks. It carries the ISO date instead, which `MealPlanKey.parseOrNull` rejects
     * (it demands a separator and a known moment) and the branch below then reads on its own.
     *
     * The entry reading is exclusive by construction — nothing builds one carrying both — and it
     * uses a different separator, so the key itself says which of the readings it is.
     */
    data class AddFood(
        val date: LocalDate? = null,
        val slot: MealSlot? = null,
        val entryId: FoodLogEntryId? = null,
    ) : FoodRoute {
        override val key: String
            get() = when {
                entryId != null -> "$ADD_FOOD_KEY$ALTERNATE_SEPARATOR${entryId.value}"
                date != null && slot != null ->
                    "$ADD_FOOD_KEY$ID_SEPARATOR${MealPlanKey(date, slot).aggregateId}"

                date != null -> "$ADD_FOOD_KEY$ID_SEPARATOR$date"

                else -> ADD_FOOD_KEY
            }
    }

    /** PRD_FOOD 11: one recipe, with the servings recomputed live. */
    data class RecipeDetail(val recipeId: RecipeId) : FoodRoute {
        override val key: String get() = "$RECIPE_DETAIL_KEY$ID_SEPARATOR${recipeId.value}"
    }

    /** FR-RECIPE-001 and 006: the same form creates and edits, so the id is what tells them apart. */
    data class RecipeEditor(val recipeId: RecipeId? = null) : FoodRoute {
        override val key: String
            get() = recipeId?.let { "$RECIPE_EDITOR_KEY$ID_SEPARATOR${it.value}" } ?: RECIPE_EDITOR_KEY
    }

    /** PRD_FOOD 11: the shared picker an ingredient is chosen with — search, scan or create. */
    data object FoodPicker : FoodRoute {
        override val key: String = "foodPicker"
    }

    /**
     * FR-FOOD-004: the recipe a line is being built from, chosen the way a food is.
     *
     * A **sheet**, which is the whole point of it. `Use a recipe` used to `select(Recipes)`, and
     * selecting a view replaces the root and takes the open sheet with it: somebody three taps
     * into logging dinner was dropped onto the recipe catalogue, with the view switcher and the
     * bottom bar back and no thread to the meal they were writing. This is pushed over the sheet
     * exactly as [FoodPicker] is, so leaving it returns to `Add food` with the choice made.
     *
     * Parameterless for [FoodPicker]'s reason and with the same answer: what it chose is written
     * into the add flow's own ViewModel, which both screens share.
     */
    data object RecipePicker : FoodRoute {
        override val key: String = "recipePicker"
    }

    /** FR-CATALOG-003: creating a personal food, or editing one that is not read-only. */
    data class FoodEditor(val foodId: FoodId? = null) : FoodRoute {
        override val key: String
            get() = foodId?.let { "$FOOD_EDITOR_KEY$ID_SEPARATOR${it.value}" } ?: FOOD_EDITOR_KEY
    }

    /**
     * PRD_FOOD 12 and FR-PLAN-001: posing a proposal on a day, or replacing the one on a moment.
     *
     * ## It is a route, and it is the `Add food` sheet
     *
     * A proposal is a recipe, a serving count and a moment, and [AddFood] already asks for all
     * three: `FoodAddStage.SERVINGS` is that form, and `RecipePicker` is how the recipe is
     * chosen. Building a second screen would have meant a second stepper against the same
     * `FoodValidation.validateConsumedServings`, a second moment panel and a second place for
     * either to drift. So this route resolves to the same sheet, told to plan.
     *
     * ## Why it is not simply [AddFood] on a future date
     *
     * Deriving the intent from the date alone works for the day screen's own action — a day the
     * journal refuses admits one honest meaning — but not for `Swap`. Replacing **today's**
     * dinner proposal is planning on a day the journal would happily take a line, so the date
     * cannot say which of the two the sheet is. The route says it instead.
     *
     * [slot] is null when the day screen opens it, which is what lets `MealSlotRules
     * .plannedSlotFor` choose the moment from the dish and the day; it is set by `Swap`, which
     * names the moment being replaced and pins it.
     *
     * ## This is `FoodRoute.Swap`, restored
     *
     * `Swap` was a `data class` carrying a [MealPlanKey] and answering `FoodPlaceholder` — a
     * wordless empty `Box` with no title and no way out — so its action was withdrawn from the
     * proposal card. It has a real screen now, and it is this one: replacing a proposal and posing
     * one are the same gesture, so they are the same destination. Its old key still resolves here,
     * which is what a stack saved by yesterday's build restores to.
     */
    data class PlanMeal(val date: LocalDate, val slot: MealSlot? = null) : FoodRoute {
        override val key: String
            get() = slot
                ?.let { "$PLAN_MEAL_KEY$ID_SEPARATOR${MealPlanKey(date, it).aggregateId}" }
                ?: "$PLAN_MEAL_KEY$ID_SEPARATOR$date"
    }

    /** PRD_FOOD 6.7: the module's occasional settings live here and nowhere on a screen. */
    data object Preferences : FoodRoute {
        override val key: String = "preferences"
    }

    companion object {
        private const val ADD_FOOD_KEY = "addFood"
        private const val RECIPE_DETAIL_KEY = "recipeDetail"
        private const val RECIPE_EDITOR_KEY = "recipeEditor"
        private const val FOOD_EDITOR_KEY = "foodEditor"
        private const val PLAN_MEAL_KEY = "planMeal"

        /**
         * What [PlanMeal] used to be called, and what a stack saved by an older build carries.
         *
         * Still read, never written. `swap:2026-09-03:dinner` names exactly the day and moment
         * [PlanMeal] takes, so a restored stack lands on the screen the reader was on rather than
         * being dropped back to `Day` — and the destination it lands on is the one that finally
         * does the job the placeholder never did.
         */
        private const val LEGACY_SWAP_KEY = "swap"

        /** Neither separator can occur in a stored UUID, in an ISO date or in a moment's id. */
        private const val ID_SEPARATOR = ':'
        private const val ALTERNATE_SEPARATOR = '#'

        /** PRD_FOOD 7 lists the four views in this order, and the switcher shows them in it. */
        val VIEWS: List<View> = listOf(Day, Trends, Recipes, Foods)

        /**
         * The views the switcher actually offers: [VIEWS] less the ones still drawing nothing.
         *
         * **`Trends` is the one it leaves out, and it stays out.** The prototype draws four
         * segments and PRD_FOOD 19 makes the prototype authoritative on layout, so this is the one
         * place the track deliberately departs from it — because the fourth segment would lead
         * somewhere. `FoodRoute.Trends` still answers `FoodPlaceholder`, a deliberately wordless
         * empty `Box`: the right thing to show while a whole view is unbuilt, and the wrong thing
         * to offer as the peer of three finished screens. A reader who taps it and lands on a blank
         * canvas has found a defect, not an honest confession.
         *
         * The alternative considered was giving it an honest empty state and putting it in the
         * track. It was rejected because PRD_FOOD 10.5 names five things `Trends` shows — seven
         * bars, the mean of the filled days, the count of filled days, the line count, a tappable
         * history — and an empty state is a statement about a screen that exists. Writing "no data
         * yet" over a view that has none of those five would be a *fourth* screen invented to
         * excuse the absence of the third, with copy PRD_FOOD 17 does not write.
         *
         * A three-segment track is also the better control at the largest font size: three shares
         * of 360 dp is a third more room per name than four, which is what decides whether the
         * words survive at all — see `FoodViewSwitcher`.
         *
         * It costs nothing to put back: PRD_FOOD 10.5's screen lands, its route stops answering
         * `FoodPlaceholder`, and this list becomes [VIEWS]. The order is [VIEWS]' own, so the
         * switcher and the animation that follows a view change never disagree about which way
         * sideways is.
         */
        val SWITCHABLE: List<View> = VIEWS.filterNot { it == Trends }

        /**
         * The inverse of [key]. An unreadable key falls back to [Day] rather than throwing: a
         * saved stack outlives the code that wrote it, and losing a screen is a better outcome
         * than a crash on the first frame after an update.
         *
         * This is why it stays **total**: a stack saved by a build that knew a key and restored by
         * one that did not would otherwise take the app down before it drew anything. It is also
         * what lets a key be *renamed* — `swap` became `planMeal` and both still resolve, to the
         * same screen. A half-readable sheet degrades the same way: a target whose date will not
         * parse gives a plain `Add food`, which still works.
         */
        fun fromKey(key: String): FoodRoute = when {
            key == Day.key -> Day
            key == Trends.key -> Trends
            key == Recipes.key -> Recipes
            key == Foods.key -> Foods
            key == FoodPicker.key -> FoodPicker
            key == RecipePicker.key -> RecipePicker
            key == Preferences.key -> Preferences

            key == ADD_FOOD_KEY -> AddFood()
            /*
             * A moment and its day, or a day on its own. `parseOrNull` answers the first and
             * refuses everything else, so the ISO date is tried after it rather than before —
             * `LocalDate.parse` would throw on `2026-08-28:lunch` and never reach the pair.
             * A target that reads as neither degrades to a plain sheet, which still works.
             */
            key.startsWith("$ADD_FOOD_KEY$ID_SEPARATOR") -> {
                val target = key.substringAfter(ID_SEPARATOR)
                MealPlanKey.parseOrNull(target)
                    ?.let { AddFood(date = it.plannedOn, slot = it.slot) }
                    ?: AddFood(date = localDateOrNull(target))
            }

            key.startsWith("$ADD_FOOD_KEY$ALTERNATE_SEPARATOR") ->
                AddFood(entryId = FoodLogEntryId(key.substringAfter(ALTERNATE_SEPARATOR)))

            key.startsWith("$RECIPE_DETAIL_KEY$ID_SEPARATOR") ->
                RecipeDetail(RecipeId(key.substringAfter(ID_SEPARATOR)))

            key == RECIPE_EDITOR_KEY -> RecipeEditor()
            key.startsWith("$RECIPE_EDITOR_KEY$ID_SEPARATOR") ->
                RecipeEditor(RecipeId(key.substringAfter(ID_SEPARATOR)))

            key == FOOD_EDITOR_KEY -> FoodEditor()
            key.startsWith("$FOOD_EDITOR_KEY$ID_SEPARATOR") ->
                FoodEditor(FoodId(key.substringAfter(ID_SEPARATOR)))

            /*
             * The one sheet that cannot exist without its parameter: a proposal has to be posed
             * on a day, and PRD_FOOD 15 will not take "today" as a substitute for a date somebody
             * chose. An unreadable one is dropped rather than opened on the wrong day.
             *
             * A moment and its day, or a day on its own — read in that order and for
             * `AddFood`'s reason: `LocalDate.parse` throws on `2026-09-03:dinner`, so the pair
             * has to be tried first.
             */
            key.startsWith("$PLAN_MEAL_KEY$ID_SEPARATOR") ||
                key.startsWith("$LEGACY_SWAP_KEY$ID_SEPARATOR") -> {
                val target = key.substringAfter(ID_SEPARATOR)
                MealPlanKey.parseOrNull(target)
                    ?.let { PlanMeal(date = it.plannedOn, slot = it.slot) }
                    ?: localDateOrNull(target)?.let { PlanMeal(date = it) }
                    ?: Day
            }

            else -> Day
        }

        /**
         * An ISO day, or null — never a throw.
         *
         * [fromKey]'s own contract: a key written by another build outlives the code that wrote
         * it, and losing the day a sheet was aimed at is a better outcome than taking the first
         * frame down. A null here gives a plain `Add food`, which opens on today.
         */
        private fun localDateOrNull(value: String): LocalDate? = try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * The Food tab's own stack: one of the four views at the bottom, PRD_FOOD 7's sheets above it.
 *
 * The base shell has no stack — its tabs are siblings — and the Activity tab has a plain one.
 * This tab is both at once, so the smallest thing that models it is a list whose *first* entry
 * is the view and whose *last* entry is what is on screen. Everything a navigation library would
 * add here (a graph, entry providers, a lifecycle per entry) would only re-describe that list.
 */
@Stable
class FoodStack internal constructor(entries: List<FoodRoute>) {

    var entries: List<FoodRoute> by mutableStateOf(entries.rooted())
        private set

    /** Which of the four views is underneath, whatever is open on top of it. */
    val view: FoodRoute.View get() = entries.first() as FoodRoute.View

    val current: FoodRoute get() = entries.last()

    /** True while a sheet is open, and on any view but `Day`, which back leaves the module from. */
    val canGoBack: Boolean get() = entries.size > 1 || view != FoodRoute.Day

    /**
     * Switches view (PRD_FOOD 7).
     *
     * The four views are siblings, so this replaces the root instead of stacking on it — and it
     * takes any open sheet with it, because a sheet belongs to the view that opened it and
     * leaving one open over another view would be a sheet with nothing behind it.
     */
    fun select(view: FoodRoute.View) {
        if (entries.size > 1 || this.view != view) entries = listOf(view)
    }

    fun push(sheet: FoodRoute) {
        entries = entries + sheet
    }

    /**
     * Drops the top [count] sheets, never the view underneath.
     *
     * Saving a new food from inside the picker pops two at once: PRD_FOOD 11 has the picker
     * offer creation, and returning to it after the save would offer to create the very same
     * food again.
     */
    fun pop(count: Int = 1) {
        entries = entries.take((entries.size - count).coerceAtLeast(1))
    }

    /**
     * What the system back button does inside the module: close the sheet on top, and once the
     * sheets are gone return to `Day`.
     *
     * Back only leaves the tab from `Day`, mirroring the shell, where it only leaves the app
     * from `Entry`. Landing on `Entry` straight out of the recipe catalogue would be two
     * journeys undone by one press.
     */
    fun back() {
        when {
            entries.size > 1 -> pop()
            view != FoodRoute.Day -> select(FoodRoute.Day)
        }
    }
}

/**
 * A restored list is put back into shape rather than trusted.
 *
 * The bottom entry has to be a view and the ones above it have to be sheets; a key written by
 * another build can be neither. Anything that does not fit is dropped, which costs a screen —
 * where trusting it would cost the first frame.
 */
private fun List<FoodRoute>.rooted(): List<FoodRoute> {
    val root = firstOrNull() as? FoodRoute.View ?: FoodRoute.Day
    return listOf(root) + drop(1).filterNot { it is FoodRoute.View }
}

private val FoodStackSaver: Saver<FoodStack, Any> = listSaver(
    save = { stack -> stack.entries.map(FoodRoute::key) },
    restore = { keys -> FoodStack(keys.map { key -> FoodRoute.fromKey(key) }) },
)

/** A stack that survives rotation, a trip through another tab, and process death. */
@Composable
fun rememberFoodStack(): FoodStack = rememberSaveable(saver = FoodStackSaver) {
    FoodStack(listOf(FoodRoute.Day))
}
