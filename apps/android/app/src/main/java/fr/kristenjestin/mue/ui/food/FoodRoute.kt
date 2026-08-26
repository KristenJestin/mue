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

    /** PRD_FOOD 10.1: opens on today, four moments, no header band and no daily summary. */
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
     * [date] and [slot] travel together or not at all. Opening from a moment's `+` carries both;
     * opening from anywhere else carries neither and FR-FOOD-007 lets the clock preselect the
     * moment, which is a better default than half a target read back from a stale key.
     *
     * The two readings are exclusive by construction — nothing builds one carrying both — and
     * they use different separators, so the key itself says which of the two it is.
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

    /** FR-CATALOG-003: creating a personal food, or editing one that is not read-only. */
    data class FoodEditor(val foodId: FoodId? = null) : FoodRoute {
        override val key: String
            get() = foodId?.let { "$FOOD_EDITOR_KEY$ID_SEPARATOR${it.value}" } ?: FOOD_EDITOR_KEY
    }

    /**
     * FR-PLAN-002: replacing the proposal on one moment.
     *
     * A proposal has no identifier of its own — PRD_FOOD 8.5 makes the `(date, moment)` pair its
     * identity — so that pair is what the route carries, written with [MealPlanKey]'s own
     * `aggregateId` rather than a second encoding of the same thing.
     */
    data class Swap(val plan: MealPlanKey) : FoodRoute {
        override val key: String get() = "$SWAP_KEY$ID_SEPARATOR${plan.aggregateId}"
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
        private const val SWAP_KEY = "swap"

        /** Neither separator can occur in a stored UUID, in an ISO date or in a moment's id. */
        private const val ID_SEPARATOR = ':'
        private const val ALTERNATE_SEPARATOR = '#'

        /** PRD_FOOD 7 lists the four views in this order, and the switcher shows them in it. */
        val VIEWS: List<View> = listOf(Day, Trends, Recipes, Foods)

        /**
         * The views the switcher actually offers: [VIEWS] less the ones still drawing nothing.
         *
         * `Trends` is the one it leaves out, and leaving it out is the honest answer rather than
         * an omission. Its route still draws `FoodPlaceholder`, which is a deliberately wordless
         * empty `Box` — the right thing to show while a whole tab is unbuilt, and the wrong thing
         * to reach through a control that offers it as the peer of three finished screens. A
         * reader who taps `Trends` and lands on a blank canvas has found a defect, not an honest
         * confession; the confession would need copy PRD_FOOD 17 does not write, over a screen
         * that does not exist yet.
         *
         * It costs nothing to put back: PRD_FOOD 10.5's screen lands, and its route stops
         * answering `FoodPlaceholder`, and this list becomes [VIEWS]. The order is [VIEWS]' own,
         * so the switcher and the animation that follows a view change never disagree about which
         * way sideways is.
         */
        val SWITCHABLE: List<View> = VIEWS.filterNot { it == Trends }

        /**
         * The inverse of [key]. An unreadable key falls back to [Day] rather than throwing: a
         * saved stack outlives the code that wrote it, and losing a screen is a better outcome
         * than a crash on the first frame after an update.
         *
         * This is why it stays **total** as the six remaining screens land: a stack saved by a
         * build that knew `swap` and restored by one that did not would otherwise take the app
         * down before it drew anything. A half-readable sheet degrades the same way — a target
         * whose date will not parse gives a plain `Add food`, which still works.
         */
        fun fromKey(key: String): FoodRoute = when {
            key == Day.key -> Day
            key == Trends.key -> Trends
            key == Recipes.key -> Recipes
            key == Foods.key -> Foods
            key == FoodPicker.key -> FoodPicker
            key == Preferences.key -> Preferences

            key == ADD_FOOD_KEY -> AddFood()
            key.startsWith("$ADD_FOOD_KEY$ID_SEPARATOR") ->
                MealPlanKey.parseOrNull(key.substringAfter(ID_SEPARATOR))
                    ?.let { AddFood(date = it.plannedOn, slot = it.slot) }
                    ?: AddFood()

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
             * The one sheet that cannot exist without its parameter: a swap with no proposal
             * behind it has nothing to replace, so an unreadable one is dropped rather than
             * opened empty.
             */
            key.startsWith("$SWAP_KEY$ID_SEPARATOR") ->
                MealPlanKey.parseOrNull(key.substringAfter(ID_SEPARATOR))?.let(::Swap) ?: Day

            else -> Day
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
     * Swaps the sheet on top for another, leaving nothing behind it.
     *
     * `Swap` is a handover rather than a journey: FR-PLAN-002 replaces the proposal and going
     * back must reach the day, not the sheet that was already answered. On a bare view — which
     * is never popped — this is simply a push.
     */
    fun replaceTop(sheet: FoodRoute) {
        entries = entries.take((entries.size - 1).coerceAtLeast(1)) + sheet
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
