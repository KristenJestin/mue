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
import androidx.compose.runtime.CompositionLocalProvider
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
import fr.kristenjestin.mue.ui.food.add.FoodAddRoute
import fr.kristenjestin.mue.ui.food.add.FoodPickerRoute
import fr.kristenjestin.mue.ui.food.add.RecipePickerRoute
import fr.kristenjestin.mue.ui.food.add.foodAddViewModel
import fr.kristenjestin.mue.ui.food.catalogue.FoodEditorRoute
import fr.kristenjestin.mue.ui.food.catalogue.FoodsRoute
import fr.kristenjestin.mue.ui.food.day.FoodDayRoute
import fr.kristenjestin.mue.ui.food.recipes.RecipeDetailRoute
import fr.kristenjestin.mue.ui.food.recipes.RecipeEditorRoute
import fr.kristenjestin.mue.ui.food.recipes.RecipeListRoute
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
 * Only `Trends` still draws [FoodPlaceholder]; the day, the add sheet — which now also plans —
 * the catalogue and the three recipe screens all have screens behind them. They landed one
 * directory at a time, and the routes, the tags and the icons they needed were already here, so
 * none of them had to reopen a file another was editing.
 *
 * `Preferences` used to be an eighth sheet here. It is not a Food route any more: PRD_FOOD 6.7's
 * options live in `Profile`, in the stack that tab already keeps for `Server settings`, and this
 * module neither draws the door nor knows the screen exists. Its key is retired rather than
 * redirected — see [FoodRoute.Companion.fromKey].
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

    /*
     * PRD_FOOD 17: "Produit absent d'Open Food Facts → bascule vers la création manuelle
     * **pré-remplie du code-barres**."
     *
     * A second holder beside the first rather than a pair in one, for the same reason and with
     * the same shortcoming: `FoodRoute.FoodEditor` carries an optional `FoodId` and nothing else.
     * Two `String?`s keep both saveable with no custom `Saver` and keep them independent — a
     * fruitless *search* prefills a name and no barcode, a fruitless *lookup* prefills a barcode
     * and no name, and neither can leak into the other's creation.
     */
    var editorBarcode by rememberSaveable { mutableStateOf<String?>(null) }

    FoodNavHost(stack = stack, modifier = modifier) { route ->
        FoodDestination(
            route = route,
            stack = stack,
            editorPrefill = editorPrefill,
            onEditorPrefillChange = { editorPrefill = it },
            editorBarcode = editorBarcode,
            onEditorBarcodeChange = { editorBarcode = it },
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

    /*
     * PRD_FOOD 7's switcher, published once for whichever view is showing.
     *
     * It is provided *around* the animated content rather than drawn beside it, because the
     * switcher belongs under each view's own wordmark — where the prototype puts it — and that
     * seam lives inside `MueScreenScaffold`, which each view raises for itself. `FoodViewScaffold`
     * is what reads this; a sheet raises `MueSubScreenScaffold` instead and therefore cannot show
     * a switcher, with no condition written down anywhere.
     */
    val selection = FoodViewSelection(selected = stack.view, onSelect = stack::select)

    CompositionLocalProvider(LocalFoodViewSelection provides selection) {
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
}

private fun List<FoodRoute>.viewOrNull(): FoodRoute.View? = firstOrNull() as? FoodRoute.View

/** An unknown view sorts first, so a restored stack still animates in some coherent direction. */
private fun indexOf(view: FoodRoute.View?): Int = FoodRoute.VIEWS.indexOf(view)

/**
 * Where each screen's callbacks land on the stack.
 *
 * The `when` is exhaustive over the eleven routes, which is the point: each of the screens that
 * follow replaces exactly one branch of it, and touches nothing else that is shared. What the add
 * flow needed from this file is a push, a pop, a select, and the one call that hands the picker's
 * choice to the sheet that opened it — plus the prefill the catalogue had already put here.
 */
@Composable
private fun FoodDestination(
    route: FoodRoute,
    stack: FoodStack,
    editorPrefill: String?,
    onEditorPrefillChange: (String?) -> Unit,
    editorBarcode: String?,
    onEditorBarcodeChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (route) {
        /*
         * PRD_FOOD 10.1. The day being viewed is deliberately absent from every route below:
         * see the note on `FoodRoute`. What the screen hands back is the day it was on — and
         * **only** the day.
         *
         * The moment used to travel with it, because the `+` that was pressed named one. It no
         * longer does: `AddFood` with a date and no moment is what makes the hour decide
         * (`FoodAddDraft.forTarget` leaves `slotPinned = false`), which is the rule the six `+`
         * buttons were overriding every time one of them was used. The override the owner asked
         * for is still on the sheet, in `SLOT_FIELD`.
         */
        FoodRoute.Day -> FoodDayRoute(
            onAdd = { date -> stack.push(FoodRoute.AddFood(date = date)) },
            /*
             * PRD_FOOD 12: the same action at the foot of the screen, on a day the journal cannot
             * take. It is one gesture with two destinations rather than two controls, because a
             * day still to come leaves it only one honest meaning — and the day screen is what
             * knows which day it is on.
             */
            onPlan = { date -> stack.push(FoodRoute.PlanMeal(date = date)) },
            onEditEntry = { entryId -> stack.push(FoodRoute.AddFood(entryId = entryId)) },
            /*
             * FR-PLAN-002, and `Swap` is back on the card because this is somewhere real to go.
             * Replacing a proposal *is* posing one, so it opens the same sheet, aimed at the
             * moment being replaced — which pins it, so the derivation from the dish does not
             * quietly move the meal to another moment.
             */
            onSwapPlan = { plan -> stack.push(FoodRoute.PlanMeal(plan.plannedOn, plan.slot)) },
            modifier = modifier,
        )

        FoodRoute.Trends -> FoodPlaceholder(modifier)

        // PRD_FOOD 11. The list opens a card and creates a recipe; nothing else on it navigates.
        FoodRoute.Recipes -> RecipeListRoute(
            onOpenRecipe = { recipeId -> stack.push(FoodRoute.RecipeDetail(recipeId)) },
            onCreateRecipe = { stack.push(FoodRoute.RecipeEditor()) },
            modifier = modifier,
        )

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
            modifier = modifier,
        )

        /*
         * PRD_FOOD 7's `Add food`, in both its readings (FR-FOOD-002 to 008).
         *
         * The four callbacks are the four things the sheet cannot do itself, and every one of
         * them is a **push**. `Use a recipe` used to be a `select(Recipes)`, which is not a
         * handover at all: selecting a view replaces the root of the module, so the sheet closed,
         * the switcher and the bottom bar came back, and the person logging a meal was left on
         * the recipe catalogue with nothing tying it to what they had been writing. It now opens
         * `RecipePicker` over the sheet exactly as `Search a food` opens `FoodPicker`, and the
         * choice comes back through the ViewModel the two share.
         */
        is FoodRoute.AddFood -> FoodAddRoute(
            date = route.date,
            slot = route.slot,
            entryId = route.entryId,
            onClose = { stack.pop() },
            onSearchFood = { stack.push(FoodRoute.FoodPicker) },
            onUseRecipe = { stack.push(FoodRoute.RecipePicker) },
            /*
             * PRD_FOOD 17's fourth row: a barcode Open Food Facts has no card for opens the
             * editor already holding it. `push` and not `replaceTop`, so back returns to the
             * scan with the number still in the field — a lookup that failed for the network's
             * reasons is worth another try, and the person has not lost the digits either way.
             */
            onCreateFood = { barcode ->
                onEditorPrefillChange(null)
                onEditorBarcodeChange(barcode)
                stack.push(FoodRoute.FoodEditor())
            },
            modifier = modifier,
        )

        /*
         * FR-RECIPE-006: a deleted recipe leaves nothing to come back to, so the card is popped
         * rather than left showing a row that no longer exists. It is popped only once the
         * screen has said which meal plans the deletion freed — that report has no other home.
         */
        is FoodRoute.RecipeDetail -> RecipeDetailRoute(
            recipeId = route.recipeId,
            onBack = stack::back,
            onEdit = { stack.push(FoodRoute.RecipeEditor(route.recipeId)) },
            onDeleted = { stack.pop() },
            modifier = modifier,
        )

        /*
         * FR-RECIPE-001 and 006: one form creates and edits. A save returns to whatever opened
         * it — the list for a new recipe, the card for an edited one — rather than always to the
         * list, which would lose the reader's place.
         */
        is FoodRoute.RecipeEditor -> RecipeEditorRoute(
            recipeId = route.recipeId,
            onBack = stack::back,
            onSaved = { stack.pop() },
            modifier = modifier,
        )

        /*
         * PRD_FOOD 11's shared selector (PRD_FOOD 9.4).
         *
         * The route carries no parameter and the stack has no result channel, so what the picker
         * chose is written straight into the add flow's own ViewModel — the activity's store
         * hands both screens the same instance, exactly as `Log activity` and the strength
         * editor share one draft. Nothing is returned through the stack.
         *
         * The creation it offers carries the term that found nothing, through the same holder
         * `Foods` fills: one shortcoming of the frozen route, answered once for both screens.
         */
        FoodRoute.FoodPicker -> {
            val add = foodAddViewModel()
            FoodPickerRoute(
                onPicked = { foodId ->
                    add.onFoodChosen(foodId)
                    stack.pop()
                },
                onCreateFood = { prefill ->
                    onEditorPrefillChange(prefill)
                    stack.push(FoodRoute.FoodEditor())
                },
                onBack = { stack.pop() },
                modifier = modifier,
            )
        }

        /*
         * FR-FOOD-004's picker, the food picker's twin.
         *
         * Same shape and same reason: the route carries no parameter, the stack has no result
         * channel, so what it chose is written straight into the add flow's ViewModel and the
         * sheet underneath finds it there. `stack.pop()` and never `select`, which is the whole
         * difference between coming back to the meal being logged and being sent to browse.
         *
         * The creation it offers is pushed on top rather than swapped in: a recipe written from
         * here belongs to this list, and saving it pops back to a picker that now has something
         * to choose.
         */
        FoodRoute.RecipePicker -> {
            val add = foodAddViewModel()
            RecipePickerRoute(
                onPicked = { recipeId ->
                    add.onRecipeChosen(recipeId)
                    stack.pop()
                },
                onCreateRecipe = { stack.push(FoodRoute.RecipeEditor()) },
                onBack = { stack.pop() },
                modifier = modifier,
            )
        }

        /* FR-CATALOG-003: creating a personal food, correcting one, duplicating a reference. */
        is FoodRoute.FoodEditor -> FoodEditorRoute(
            foodId = route.foodId,
            prefillName = editorPrefill.takeIf { route.foodId == null },
            prefillBarcode = editorBarcode.takeIf { route.foodId == null },
            onFinished = {
                /*
                 * The term dies with the sheet. Kept, it would prefill the *next* blank editor
                 * with a word from a search nobody remembers making — and the barcode would be
                 * worse still, since a stale one would attach another product's number to a food
                 * typed out by hand a week later.
                 */
                onEditorPrefillChange(null)
                onEditorBarcodeChange(null)
                stack.pop()
            },
            modifier = modifier,
        )

        /*
         * PRD_FOOD 12 and FR-PLAN-001, on the sheet that already asks for all three facts.
         *
         * The same composable as `AddFood`, told to plan, so a proposal's servings pass through
         * the very stepper a consumption's do and PRD_FOOD 15's counter exists once. `onSearchFood`
         * and `onCreateFood` are unreachable from here — §8.5 admits no plain food into a
         * proposal, so the planning sheet offers the recipe path alone — and are wired to nothing
         * rather than to a screen that would write the wrong kind of thing.
         */
        is FoodRoute.PlanMeal -> FoodAddRoute(
            date = route.date,
            slot = route.slot,
            entryId = null,
            planning = true,
            onClose = { stack.pop() },
            onSearchFood = {},
            onUseRecipe = { stack.push(FoodRoute.RecipePicker) },
            onCreateFood = {},
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
