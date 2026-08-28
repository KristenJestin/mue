package fr.kristenjestin.mue.ui.food

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.dp
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.food.catalogue.FoodsScreen
import fr.kristenjestin.mue.ui.food.catalogue.previewFoodsState
import fr.kristenjestin.mue.ui.food.day.FoodDayScreen
import fr.kristenjestin.mue.ui.food.day.previewDayState
import fr.kristenjestin.mue.ui.food.recipes.RecipeListScreen
import fr.kristenjestin.mue.ui.food.recipes.previewRecipeListState
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The narrowest phone the app supports, where three segments get about 95 dp each. */
private val NarrowestPhone: Dp = 360.dp

/** The largest text size the system offers. */
private const val LargestFontScale = 2f

/**
 * The switcher over PRD_FOOD 7's views — the control the module described from its first commit
 * and never drew.
 *
 * [FoodTestTags.VIEW_SWITCHER] and [FoodTestTags.view] were agreed before any Food screen landed,
 * `FoodRoute.View` documents its four members as "siblings reached by a switcher", and
 * `FoodRoute.VIEWS` orders them for it. No composable referenced either tag. The result is the
 * owner's report word for word — *"je peux pas créer de food sans ajouter via « add what you ate »
 * ?"* — because `Foods` was reachable only through a search that finds nothing, `Recipes` only
 * through `Use a recipe` inside the add sheet, and `Trends` through nothing at all.
 *
 * The screens under test are the **real** ones, driven by their own preview states. A stand-in
 * would prove that a switcher composes; only the shipped screens prove that the switcher is on
 * them, which is the whole of the defect.
 *
 * The last three tests read the [TextLayoutResult] each label hands out rather than its semantics
 * string, for `MueBottomBarLabelTest`'s reason: `onNodeWithTag(...).assertIsDisplayed()` is happy
 * with a pill drawing `Rec…`, and a switcher whose entries cannot be told apart is the tab bar's
 * `Pro… / Pro…` defect one level down.
 */
class FoodViewSwitcherTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The switcher exists at all, which is the finding.
     *
     * `Day` is where the module opens, so this is the first thing anyone sees of the Food tab.
     */
    @Test
    fun theDayViewCarriesTheSwitcher() {
        setModule()

        compose.onNodeWithTag(FoodTestTags.VIEW_SWITCHER).assertExists()
    }

    /** Every view the switcher offers is one tap from every other. */
    @Test
    fun everyOfferedViewIsReachableFromEveryOther() {
        setModule()

        offered().forEach { from ->
            select(from)
            offered().forEach { to ->
                select(to)
                compose.onNodeWithTag(FoodTestTags.view(to)).assertIsSelected()
                compose.onNodeWithTag(bodyTagOf(to)).assertExists()
            }
        }
    }

    /**
     * `Foods` in one tap from the day, which is the report answered.
     *
     * Held beside the back door it does not close: the catalogue was reachable only by searching
     * for something that does not exist and accepting the offer to create it.
     */
    @Test
    fun theCatalogueIsOneTapFromTheDay() {
        setModule()

        select(FoodRoute.Foods)

        compose.onNodeWithTag(FoodTestTags.FOODS).assertExists()
        compose.onNodeWithTag(FoodTestTags.CREATE_FOOD).assertExists()
    }

    /** PRD_ACTIVITIES 15's lesson: an entry announces its name, its role and its state. */
    @Test
    fun everyEntryIsATabThatSaysWhetherItIsTheOneShowing() {
        setModule()
        select(FoodRoute.Recipes)

        offered().forEach { view ->
            val node = compose.onNodeWithTag(FoodTestTags.view(view))
            node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            if (view == FoodRoute.Recipes) node.assertIsSelected() else node.assertIsNotSelected()
        }
    }

    /**
     * A sheet is a modal over a view, so it carries no switcher.
     *
     * Nothing in the module writes that rule down: a sheet raises `MueSubScreenScaffold` and a
     * view raises `FoodViewScaffold`, and only the second one knows about the switcher.
     */
    @Test
    fun aSheetOverAViewShowsNoSwitcher() {
        val stack = FoodStackUnderTest()
        setModule(stack)

        stack.open(FoodRoute.AddFood())
        compose.waitForIdle()

        compose.onNodeWithTag(FoodTestTags.VIEW_SWITCHER).assertDoesNotExist()
    }

    /**
     * `Trends` is not offered, and that is a decision rather than an oversight.
     *
     * Its route still answers `FoodPlaceholder`, a deliberately wordless empty `Box`. That is the
     * honest drawing for a screen nobody has built; it is a defect the moment a control offers it
     * as the peer of three finished views, because what a reader finds is a blank canvas with
     * nothing on it to explain itself. This test is the guard on that reasoning both ways: it goes
     * the day PRD_FOOD 10.5's screen lands, and until then it stops the view being added back with
     * nothing behind it.
     */
    @Test
    fun trendsIsNotOfferedWhileItHasNoScreen() {
        setModule()

        compose.onNodeWithTag(FoodTestTags.view(FoodRoute.Trends)).assertDoesNotExist()
    }

    /**
     * PRD_FOOD 18's touch minimum, on the segment rather than on the frame around it.
     *
     * The prototype draws a short track — `p-1` around buttons of `py-2.5` — and a track sized to
     * that leaves each segment 40 dp once the frame's own margin is taken off both sides. Forty is
     * under the floor, on the one control in the module whose entire purpose is to be tapped, and
     * nothing in the semantics tree would ever have said so.
     */
    @Test
    fun everySegmentIsBigEnoughToTap() {
        setModule()

        offered().forEach { view ->
            val height = compose
                .onNodeWithTag(FoodTestTags.view(view))
                .getUnclippedBoundsInRoot()
                .height
            assertTrue(
                "«${view.label}» is $height tall, under $MueMinTouchTarget",
                height >= MueMinTouchTarget,
            )
        }
    }

    // region what reaches the glass (the tab bar's lesson, one level down)

    /** The width the switcher is already right at, and the one nothing here may move. */
    @Test
    fun everyNameIsDrawnWholeAtTheOrdinaryFontScale() {
        setModule(fontScale = 1f)

        offered().forEach { view ->
            assertEquals("«${view.label}» is not drawn whole", view.label, drawnLabel(view))
        }
    }

    /**
     * The defect the tab bar paid nine fixes for, refused here by measurement.
     *
     * The track is a set of **equal segments**, which is precisely the layout a long name gets cut
     * in: a third of 360 dp is about 95 dp, and at twice the font scale `Recipes` wants more. So
     * the rule this asserts is not "every name survives" — it is **whole or absent**. A label that
     * fits is drawn entire; below that the track drops every one of them and keeps the glyphs, as
     * `MueBottomBar` does. What must never reach the glass is a fragment.
     */
    @Test
    fun noNameIsDrawnCutAtTwiceTheFontScale() {
        setModule(fontScale = LargestFontScale)

        offered().forEach { view ->
            val drawn = drawnLabel(view)
            assertTrue(
                "«${view.label}» reaches the glass as «$drawn»",
                drawn == null || drawn == view.label,
            )
        }
    }

    /**
     * The measurement actually bites at the largest size, rather than merely being written down.
     *
     * `Recipes` does not fit a third of the narrowest phone at twice the font scale, so **no**
     * label is drawn — the widest one decides for all three, because a track with two words and
     * one glyph in it would read as two kinds of thing.
     *
     * If this ever fails because the labels *do* fit, the threshold has moved and the previous
     * test is the one that still matters. It is not a rule about a `dp` breakpoint: nothing in the
     * switcher reads one.
     */
    @Test
    fun theTrackFallsBackToGlyphsWhenAWordWouldNotFit() {
        setModule(fontScale = LargestFontScale)

        val drawn = offered().mapNotNull(::drawnLabel)

        assertEquals("the track still draws $drawn", emptyList<String>(), drawn)
    }

    /**
     * Two entries a reader cannot tell apart is a switcher that cannot be read, whatever it draws.
     *
     * Once the words are gone the glyph is what carries the name, so this reads the announcement
     * rather than the glass: three segments, three different names, at the size where the names
     * are no longer written out.
     */
    @Test
    fun everyEntryStillAnnouncesItsOwnNameOnceTheWordsAreGone() {
        setModule(fontScale = LargestFontScale)

        offered().forEach { view ->
            compose.onNodeWithTag(FoodTestTags.view(view))
                .assertContentDescriptionEquals(view.label)
        }
    }

    // endregion

    // region harness

    /**
     * The views a person must be able to reach, named here rather than read off the control.
     *
     * Asking `FoodRoute.SWITCHABLE` what it offers would let this file agree with a copy of
     * itself: the list would still be right whatever the switcher had been narrowed to. These
     * three are the views that have a screen behind them, which is the expectation the report
     * states — a front door for the catalogue, and one for the recipes.
     */
    private fun offered(): List<FoodRoute.View> =
        listOf(FoodRoute.Day, FoodRoute.Recipes, FoodRoute.Foods)

    private fun select(view: FoodRoute.View) {
        compose.onNodeWithTag(FoodTestTags.view(view)).performClick()
        compose.waitForIdle()
    }

    /** The handle each view's own body already publishes, so arriving is asserted and not assumed. */
    private fun bodyTagOf(view: FoodRoute.View): String = when (view) {
        FoodRoute.Day -> FoodTestTags.DAY
        FoodRoute.Recipes -> FoodTestTags.RECIPES
        FoodRoute.Foods -> FoodTestTags.FOODS
        FoodRoute.Trends -> FoodTestTags.TRENDS
    }

    /**
     * The glyphs [view]'s name puts on the glass, or null when no label is drawn at all.
     *
     * Unmerged, so it finds the text node itself rather than the pill that merges it: the merged
     * pill reports the whole name however few of its letters are drawn, which is exactly what made
     * the tab bar's own truncation invisible to every assertion the shell had.
     */
    private fun drawnLabel(view: FoodRoute.View): String? {
        val node = compose
            .onAllNodes(hasText(view.label), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull { it.config.contains(SemanticsActions.GetTextLayoutResult) }
            ?: return null

        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.firstOrNull() ?: return null

        return view.label.take(layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    /** A stack a test can open a sheet on without a screen having to offer the action. */
    private class FoodStackUnderTest {
        var stack: FoodStack? = null

        fun open(sheet: FoodRoute) {
            stack?.push(sheet)
        }
    }

    private fun setModule(
        held: FoodStackUnderTest? = null,
        fontScale: Float = 1f,
        width: Dp = NarrowestPhone,
    ): Unit {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    val stack = rememberFoodStack()
                    held?.stack = stack
                    Box(Modifier.width(width)) {
                        FoodNavHost(stack = stack, modifier = Modifier.fillMaxSize()) { route ->
                            ViewUnderTest(route)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /**
     * The shipped screens, driven by the preview states their own tests use.
     *
     * The three views are real because the finding is about them; the sheets are a bare label,
     * because what matters about a sheet here is only that it draws no switcher.
     */
    @Composable
    private fun ViewUnderTest(route: FoodRoute) {
        when (route) {
            FoodRoute.Day -> FoodDayScreen(
                state = previewDayState(),
                onPreviousDay = {},
                onNextDay = {},
                onOpenDatePicker = {},
                onDismissDatePicker = {},
                onDayPicked = {},
                onAdd = {},
                onEditEntry = {},
                onConfirmPlan = {},
                onSwapPlan = {},
                onDismissPlan = {},
                modifier = Modifier.fillMaxSize(),
            )

            FoodRoute.Recipes -> RecipeListScreen(
                state = previewRecipeListState(),
                onQueryChange = {},
                onClearQuery = {},
                onTypeSelected = {},
                onToggleFavourites = {},
                onToggleFavourite = { _, _ -> },
                onOpenRecipe = {},
                onCreateRecipe = {},
                modifier = Modifier.fillMaxSize(),
            )

            FoodRoute.Foods -> FoodsScreen(
                state = previewFoodsState(),
                onQueryChange = {},
                onClearQuery = {},
                onSourceChange = {},
                onOpenFood = {},
                onCreateFood = {},
                onOpenPreferences = {},
                modifier = Modifier.fillMaxSize(),
            )

            else -> Box(Modifier.fillMaxSize()) { MueText(route.key, MueTheme.typography.body) }
        }
    }

    // endregion
}
