package fr.kristenjestin.mue.ui.food

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorRoute
import fr.kristenjestin.mue.ui.food.catalogue.FoodPreferencesRoute
import fr.kristenjestin.mue.ui.food.catalogue.FoodsRoute
import fr.kristenjestin.mue.ui.food.day.FoodDayRoute
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion

/**
 * The Food tab (PRD_FOOD 7), which is the second tab that is not a single screen.
 *
 * The tab bar stays above this host and never learns what is open inside it: a sheet keeps the
 * bar visible like every other screen, and back closes the sheet before it ever reaches the
 * shell.
 *
 * Two movements live here rather than one, because PRD_FOOD 7 describes two things. Changing
 * **view** is a sibling switch and slides sideways, exactly as a tab change does — the four views
 * are peers and none of them contains another. Opening a **sheet** raises it over whatever is
 * showing, as the Activity tab's own stack does. The direction of each is resolved once, above
 * the animation, because `transitionSpec` runs outside composition.
 *
 * `Day` is the first route with a screen behind it; the rest still draw [FoodPlaceholder]. They
 * land one directory at a time, and the routes, the tags and the icons they need are already here,
 * so none of them has to reopen a file another is editing.
 */
@Composable
fun FoodNavHost(modifier: Modifier = Modifier) {
    val stack = rememberFoodStack()

    /*
     * PRD_FOOD 9.4 and 17: "une recherche sans résultat propose la création d'un aliment
     * pré-rempli du terme saisi."
     *
     * The term has nowhere else to live. `FoodRoute.FoodEditor` carries an optional `FoodId` and
     * nothing more, and widening it would mean re-encoding a free-text name inside a route key
     * that three other screens are being built against this week. So the prefill is held beside
     * the stack, saved the same way the stack is, and cleared by the editor as it closes — the
     * one shortcoming of the frozen route this module found, and it is named in the report.
     */
    var editorPrefill by rememberSaveable { mutableStateOf<String?>(null) }

    FoodNavHost(stack = stack, modifier = modifier) { route ->
        FoodDestination(
            route = route,
            stack = stack,
            editorPrefill = editorPrefill,
            onEditorPrefillChange = { editorPrefill = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The stack mechanics, with the screens left to the caller so tests can drive them without a
 * database behind them — the same split the tab shell and the Activity tab both use.
 *
 * Each route is composed inside its own [rememberSaveableStateHolder] slot, so a view returned to
 * is found scrolled where it was left, and a closed sheet gives its slot up rather than greeting
 * the next visit with an abandoned form.
 */
@Composable
internal fun FoodNavHost(
    stack: FoodStack,
    modifier: Modifier = Modifier,
    content: @Composable (FoodRoute) -> Unit,
) {
    val screenStates = rememberSaveableStateHolder()
    val keys = stack.entries.map(FoodRoute::key)

    /*
     * Nested handlers resolve innermost first, so this one answers before the tab shell's and
     * back moves within the module instead of leaving it. On a bare `Day` it is disabled and the
     * shell takes over, which is what returns to `Entry`.
     */
    BackHandler(enabled = stack.canGoBack) { stack.back() }

    val live = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        live.filterNot(keys::contains).forEach { gone ->
            screenStates.removeState(gone)
            live.remove(gone)
        }
        live.addAll(keys)
    }

    // All four directions are resolved here because `transitionSpec` runs outside composition.
    val rightwards = MueMotion.tabTransition(forward = true)
    val leftwards = MueMotion.tabTransition(forward = false)
    val opening = foodSheetTransition(opening = true)
    val closing = foodSheetTransition(opening = false)

    AnimatedContent(
        targetState = stack.entries,
        modifier = modifier,
        transitionSpec = {
            val from = initialState.viewOrNull()
            val to = targetState.viewOrNull()
            when {
                // A view change is a sibling switch, and PRD_FOOD 7's order is its direction.
                from != to -> if (indexOf(to) > indexOf(from)) rightwards else leftwards
                targetState.size >= initialState.size -> opening
                else -> closing
            }
        },
        contentKey = { entries -> entries.last().key },
        label = "foodStack",
    ) { entries ->
        val route = entries.last()
        screenStates.SaveableStateProvider(route.key) { content(route) }
    }
}

private fun List<FoodRoute>.viewOrNull(): FoodRoute.View? = firstOrNull() as? FoodRoute.View

/** An unknown view sorts first, so a restored stack still animates in some coherent direction. */
private fun indexOf(view: FoodRoute.View?): Int = FoodRoute.VIEWS.indexOf(view)

/**
 * Where each screen's callbacks land on the stack.
 *
 * The `when` is exhaustive today over placeholders, which is the point: each of the screens that
 * follow replaces exactly one branch of it, and touches nothing else that is shared. `Day` is the
 * first of them, and the only thing it needed from this file was the stack the three routes it
 * opens are pushed onto.
 */
@Composable
private fun FoodDestination(
    route: FoodRoute,
    stack: FoodStack,
    editorPrefill: String?,
    onEditorPrefillChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        /*
         * PRD_FOOD 10.1. The day being viewed is deliberately absent from every route below:
         * see the note on `FoodRoute`. What the screen hands back is the day it was on, so a `+`
         * pressed on Tuesday's lunch opens `Add food` already aimed at Tuesday's lunch.
         */
        FoodRoute.Day -> FoodDayRoute(
            onAddToSlot = { date, slot ->
                stack.push(FoodRoute.AddFood(date = date, slot = slot))
            },
            onEditEntry = { entryId -> stack.push(FoodRoute.AddFood(entryId = entryId)) },
            onSwapPlan = { plan -> stack.push(FoodRoute.Swap(plan)) },
            modifier = modifier,
        )

        FoodRoute.Trends -> FoodPlaceholder(modifier)
        FoodRoute.Recipes -> FoodPlaceholder(modifier)

        /* PRD_FOOD 9: the catalogue, generic and personal alike. */
        FoodRoute.Foods -> FoodsRoute(
            onOpenFood = { foodId ->
                onEditorPrefillChange(null)
                stack.push(FoodRoute.FoodEditor(foodId))
            },
            onCreateFood = { prefill ->
                onEditorPrefillChange(prefill)
                stack.push(FoodRoute.FoodEditor())
            },
            onOpenPreferences = { stack.push(FoodRoute.Preferences) },
            modifier = modifier,
        )

        is FoodRoute.AddFood -> FoodPlaceholder(modifier)
        is FoodRoute.RecipeDetail -> FoodPlaceholder(modifier)
        is FoodRoute.RecipeEditor -> FoodPlaceholder(modifier)
        FoodRoute.FoodPicker -> FoodPlaceholder(modifier)

        /* FR-CATALOG-003: creating a personal food, correcting one, duplicating a reference. */
        is FoodRoute.FoodEditor -> FoodEditorRoute(
            foodId = route.foodId,
            prefillName = editorPrefill.takeIf { route.foodId == null },
            onFinished = {
                /*
                 * The term dies with the sheet. Kept, it would prefill the *next* blank editor
                 * with a word from a search nobody remembers making.
                 */
                onEditorPrefillChange(null)
                stack.pop()
            },
            modifier = modifier,
        )

        is FoodRoute.Swap -> FoodPlaceholder(modifier)

        /* PRD_FOOD 6.7 and 13.2: the module's occasional settings, and nowhere else. */
        FoodRoute.Preferences -> FoodPreferencesRoute(
            onBack = { stack.pop() },
            modifier = modifier,
        )
    }
}

/**
 * What a route draws until its screen arrives: the space, and nothing in it.
 *
 * Deliberately wordless. PRD_FOOD 17 writes the module's empty states — "Nothing logged yet", and
 * the rest — against real screens with real data behind them, and putting one of those sentences
 * here would ship copy that has to be found and removed later, and would read to anyone opening
 * the tab as a finished screen saying the day is empty. An empty tab says what is true: this part
 * of the app is not built yet.
 */
@Composable
private fun FoodPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.testTag(FoodTestTags.PLACEHOLDER))
}

/**
 * A sheet rises over the view (PRD_FOOD 19: "feuille modale glissante"); closing it lets what is
 * underneath settle again. Reduced motion drops the movement and keeps the cross-fade, as
 * everywhere else in the app.
 */
@Composable
@ReadOnlyComposable
private fun foodSheetTransition(opening: Boolean): ContentTransform {
    val enterSpec = MueMotion.spec<Float>(MueMotion.SheetMillis, MueMotion.Enter)
    val exitSpec = MueMotion.spec<Float>(MueMotion.SheetMillis, MueMotion.Exit)
    if (LocalReduceMotion.current) {
        return fadeIn(enterSpec) togetherWith fadeOut(exitSpec)
    }
    val offsetSpec = MueMotion.spec<IntOffset>(MueMotion.SheetMillis, MueMotion.Standard)
    val direction = if (opening) 1 else -1
    return (
        slideInVertically(offsetSpec) { height -> direction * height / 8 } + fadeIn(enterSpec)
        ) togetherWith (
        slideOutVertically(offsetSpec) { height -> -direction * height / 8 } + fadeOut(exitSpec)
        )
}
