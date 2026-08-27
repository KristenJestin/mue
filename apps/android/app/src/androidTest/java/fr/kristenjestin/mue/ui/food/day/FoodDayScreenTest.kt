package fr.kristenjestin.mue.ui.food.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

private val TODAY: LocalDate = FoodDayPreviewData.TODAY
private val YESTERDAY: LocalDate = TODAY.minusDays(1)

/**
 * The `Day` screen as it reaches the glass (PRD_FOOD 10, 12, 17 and 18).
 *
 * The screen is driven through its stateless composable, so every assertion is about what is
 * drawn rather than about how a ViewModel got there — the split `ActivityScreenTest` already
 * uses. Expected strings come from [FoodLabels] and [FoodDayMessages] rather than being spelled
 * out, so a rule that changes cannot leave a test agreeing with a copy of itself.
 */
class FoodDayScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var addedTo: MealSlot? = null
    private var edited: FoodLogEntryId? = null
    private var confirmed: MealPlanKey? = null
    private var swapped: MealPlanKey? = null
    private var dismissed: MealPlanKey? = null
    private var stepped: Int = 0

    // region the six moments (PRD_FOOD 10.1 and 17)

    @Test
    fun theSixMomentsAreAlwaysThere() {
        setDay(FoodDayUiState.of(TODAY, TODAY))

        MealSlot.ORDERED.forEach { slot ->
            compose.onNodeWithTag(FoodTestTags.DAY).performScrollToNode(
                hasTestTag(FoodTestTags.slot(slot)),
            )
            compose.onNodeWithTag(FoodTestTags.slot(slot)).assertIsDisplayed()
            compose.onNodeWithTag(FoodTestTags.addToSlot(slot)).assertIsDisplayed()
        }
    }

    /** PRD_FOOD 10.4 and 17: an empty day shows no total anywhere, invented or otherwise. */
    @Test
    fun anEmptyDayShowsNoTotalAtAll() {
        setDay(FoodDayUiState.of(TODAY, TODAY))

        MealSlot.ORDERED.forEach { slot ->
            compose.onNodeWithTag(FoodTestTags.slotTotal(slot), useUnmergedTree = true)
                .assertDoesNotExist()
        }
        compose.onNodeWithText("0 kcal", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    /** PRD_FOOD 17: the empty state of a moment is an invitation and never an error. */
    @Test
    fun anEmptyMomentInvitesRatherThanReports() {
        setDay(FoodDayUiState.of(TODAY, TODAY))

        compose.onNodeWithTag(FoodTestTags.addToSlot(MealSlot.BREAKFAST))
            .assertContentDescriptionContains(FoodDayMessages.ADD_FIRST, substring = true)
    }

    // endregion

    // region a known zero, an unknown, and nothing at all

    /**
     * The heart of PRD_FOOD 13: three facts, three drawings, on one screen.
     *
     * The lunch holds an espresso whose protein is a **known zero**; the snack holds a quick add
     * whose protein nobody wrote down; the dinner holds nothing at all — only an unconfirmed
     * proposal, which enters no total. Each moment is scrolled to before it is read, because a
     * `LazyColumn` composes what is near the viewport and an assertion made from the wrong
     * scroll position proves nothing about either.
     */
    @Test
    fun aKnownZeroAndAnUnknownAreDrawnDifferently() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.slot(MealSlot.LUNCH))
        assertDrawn(entryTag(FoodDayPreviewData.espresso().id), "≈ 0.0 g protein")

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))
        assertDrawn(entryTag(FoodDayPreviewData.tiramisu().id), "${FoodLabels.UNKNOWN} protein")

        /*
         * Dinner holds no line at all — its proposal is not one, and PRD_FOOD 12 keeps it out
         * of every total — so it draws neither of the two readings above. Breakfast would not
         * do: it holds the oat bowl, and a moment with a line shows its own total.
         */
        scrollTo(FoodTestTags.slot(MealSlot.DINNER))
        compose.onNodeWithTag(FoodTestTags.slotTotal(MealSlot.DINNER), useUnmergedTree = true)
            .assertDoesNotExist()
    }

    /**
     * PRD_FOOD 13.2, on the glass: **an empty day and an unknown value are not the same screen.**
     *
     * This is the assertion the whole null discipline ends on. Everything upstream of it — the
     * strict sums of `NutritionMath`, the `recordedEnergy` of `DailyNutritionSummary`, the `—` of
     * `FoodLabels` — is worth nothing if the last layer draws the two alike. The two states are
     * put on the *same* screen one after the other so that what is compared is what was drawn,
     * not two runs that might have differed for some other reason.
     *
     * A day nobody wrote on shows **no total at all**: four headings, four add buttons, and not a
     * figure anywhere. A day holding one line whose protein is unknown shows a moment that is
     * plainly recorded — `≈ 420 kcal` beside `— protein`. Neither of them shows a `0` where
     * nothing is known.
     */
    @Test
    fun anEmptyDayAndAnUnknownProteinAreNotTheSameScreen() {
        setDay(emptyDayState())

        /*
         * Nothing logged: no moment claims a total, and no figure is drawn anywhere. Each
         * moment is scrolled to before it is read — a `LazyColumn` disposes what is far from
         * the viewport, and a sweep of the tree from one position would only prove that the
         * moments it happened to compose were empty.
         */
        val empty = MealSlot.ORDERED.flatMap { slot ->
            scrollTo(FoodTestTags.slot(slot))
            assertEquals(emptyList<MealSlot>(), momentsShowingATotal())
            drawnText()
        }
        assertTrue(
            "an untouched day drew an energy: $empty",
            empty.none { it.contains(FoodLabels.ENERGY_UNIT) },
        )
        assertTrue(
            "an untouched day drew a dash, which is a value it does not have: $empty",
            empty.none { it.contains(FoodLabels.UNKNOWN) },
        )

        showDay(unknownProteinDayState())
        scrollTo(FoodTestTags.slot(MealSlot.SNACK))

        // One line, protein unknown: the moment is recorded, and says so metric by metric.
        assertEquals(listOf(MealSlot.SNACK), momentsShowingATotal())
        val snackTotal = FoodTestTags.slotTotal(MealSlot.SNACK)
        compose.onNodeWithTag(snackTotal, useUnmergedTree = true).assertExists()
        assertDrawn(snackTotal, "≈ 420 kcal")
        assertDrawn(snackTotal, "${FoodLabels.UNKNOWN} protein")

        val unknown = drawnText()
        assertTrue(
            "the unknown day drew no dash at all: $unknown",
            unknown.any { it.contains(FoodLabels.UNKNOWN) },
        )
        // PRD_FOOD 13.2: the unknown protein is a dash. It is never `0.0 g`, and never `0 kcal`.
        assertTrue(
            "an unknown was rendered as a zero: $unknown",
            unknown.none { it == "≈ 0.0 g ${FoodDayFormat.PROTEIN_NOUN}" || it == "≈ 0 kcal" },
        )

        assertNotEquals(
            "an untouched day and a day of unknown protein drew the same thing",
            empty,
            unknown,
        )
    }

    /** PRD_FOOD 22, metric by metric: an unknown protein leaves the energy a number. */
    @Test
    fun anUnknownProteinLeavesItsMomentsEnergyKnown() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))

        val snackTotal = FoodTestTags.slotTotal(MealSlot.SNACK)
        compose.onNodeWithTag(snackTotal, useUnmergedTree = true).assertExists()
        assertDrawn(snackTotal, "≈ 420 kcal")
        assertDrawn(snackTotal, "${FoodLabels.UNKNOWN} protein")
    }

    // endregion

    // region date navigation (PRD_FOOD 10.1 and 22)

    @Test
    fun theDayIsNamedAndTheStepsAreThere() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.DAY_DATE).assertIsDisplayed()
        compose.onNodeWithContentDescription(FoodDayMessages.PREVIOUS_DAY).assertIsDisplayed()
        compose.onNodeWithContentDescription(FoodDayMessages.NEXT_DAY).assertIsDisplayed()
    }

    /**
     * The second finding on the glass: "je puisse pas aller dans le futur".
     *
     * PRD_FOOD 12 and 15 let a proposal sit up to sixty days ahead, so the arrow reaches there.
     * PRD_FOOD 22 still refuses a line on such a day, which is what the two tests below check.
     */
    @Test
    fun tomorrowIsReachableFromToday() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.NEXT_DAY).assertIsEnabled()
        compose.onNodeWithTag(FoodTestTags.PREVIOUS_DAY).assertIsEnabled()
    }

    /** The ceiling moved rather than going: the last plannable day is still the last one. */
    @Test
    fun theLastPlannableDayIsStillTheLastOne() {
        setDay(
            FoodDayUiState.of(
                date = TODAY.plusDays(MealPlanEntry.MAX_DAYS_AHEAD),
                today = TODAY,
            ),
        )

        compose.onNodeWithTag(FoodTestTags.NEXT_DAY).assertIsNotEnabled()
    }

    /**
     * What a day ahead actually shows (PRD_FOOD 12 and 22).
     *
     * It says once what it is, and each of its six moments stops offering to log. The add row
     * keeps its place — PRD_FOOD 10.1 wants it "toujours présent" — and stops being a control,
     * which is the difference between refusing before the tap and refusing after `Save entry`.
     */
    @Test
    fun aDayAheadSaysSoAndRefusesToBeLogged() {
        setDay(FoodDayUiState.of(date = TODAY.plusDays(2), today = TODAY))

        /*
         * The note announces itself as one sentence and its two lines are cleared from the merged
         * tree, which is what stops a screen reader hearing them as loose fragments (PRD_FOOD 18).
         * So the announcement is read on the node that carries it, and the glyphs are read on the
         * unmerged tree — `onNodeWithText` would be looking for a string this node deliberately
         * does not publish.
         */
        compose.onNodeWithTag(FoodTestTags.FUTURE_DAY)
            .assertIsDisplayed()
            .assertContentDescriptionContains(FoodDayMessages.FUTURE_DAY, substring = true)
        assertDrawn(FoodTestTags.FUTURE_DAY, FoodDayMessages.FUTURE_DAY)
        assertDrawn(FoodTestTags.FUTURE_DAY, FoodDayMessages.FUTURE_DAY_DETAIL)

        MealSlot.ORDERED.forEach { slot ->
            compose.onNodeWithTag(FoodTestTags.DAY).performScrollToNode(
                hasTestTag(FoodTestTags.addToSlot(slot)),
            )
            compose.onNodeWithTag(FoodTestTags.addToSlot(slot))
                .assertIsNotEnabled()
                .assertContentDescriptionContains(
                    FoodDayMessages.PLANNABLE_SLOT,
                    substring = true,
                )
        }

        addedTo = null
        compose.onNodeWithTag(FoodTestTags.addToSlot(MealSlot.BREAKFAST)).performClick()
        assertTrue("a future day opened the add sheet anyway", addedTo == null)
    }

    /** Today says none of that, and its rows are buttons as they always were. */
    @Test
    fun todaySaysNothingAboutBeingAhead() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.FUTURE_DAY).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.addToSlot(MealSlot.BREAKFAST)).assertIsEnabled()
    }

    @Test
    fun aPastDayCanStillBeWalkedForward() {
        setDay(previewDayState(date = YESTERDAY, today = TODAY))

        compose.onNodeWithTag(FoodTestTags.NEXT_DAY).assertIsEnabled()
        compose.onNodeWithTag(FoodTestTags.NEXT_DAY).performClick()

        assertEquals(1, stepped)
    }

    /** `Today` alone never says which day is on screen; what is heard does. */
    @Test
    fun theDateAnnouncesItselfInFull() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.DAY_DATE)
            .assertContentDescriptionContains(FoodDayFormat.TODAY, substring = true)
        compose.onNodeWithTag(FoodTestTags.DAY_DATE).assertContentDescriptionContains("2026", substring = true)
    }

    // endregion

    // region proposals (PRD_FOOD 12 and 18)

    @Test
    fun aProposalCarriesItsThreeActions() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))

        compose.onNodeWithTag(FoodTestTags.confirmPlan(MealSlot.DINNER)).performClick()
        compose.onNodeWithTag(FoodTestTags.swapPlan(MealSlot.DINNER)).performClick()
        compose.onNodeWithTag(FoodTestTags.dismissPlan(MealSlot.DINNER)).performClick()

        val dinner = MealPlanKey(TODAY, MealSlot.DINNER)
        assertEquals(dinner, confirmed)
        assertEquals(dinner, swapped)
        assertEquals(dinner, dismissed)
    }

    /**
     * PRD_FOOD 18: a proposal is never told apart by colour alone.
     *
     * The card is one announcement — its information, then its three actions as three separate
     * targets — so the word `Suggested` is looked for both in what is drawn and in what is said.
     */
    @Test
    fun aProposalSaysThatItIsOne() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))

        compose.onNodeWithTag(FoodTestTags.plan(MealSlot.DINNER)).assertExists()
        compose.onNodeWithContentDescription(FoodDayMessages.SUGGESTED, substring = true)
            .assertExists()
        compose.onNodeWithText(
            FoodDayMessages.SUGGESTED.uppercase(),
            useUnmergedTree = true,
        ).assertExists()
    }

    // endregion

    // region what a tap does

    @Test
    fun addingToAMomentCarriesThatMoment() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.addToSlot(MealSlot.LUNCH))
        compose.onNodeWithTag(FoodTestTags.addToSlot(MealSlot.LUNCH)).performClick()

        assertEquals(MealSlot.LUNCH, addedTo)
    }

    @Test
    fun tappingALineOpensThatLine() {
        setDay(previewDayState())

        val id = FoodDayPreviewData.breakfast().id
        scrollTo(FoodTestTags.logEntry(id.value))
        compose.onNodeWithTag(FoodTestTags.logEntry(id.value)).performClick()

        assertEquals(id, edited)
    }

    /** PRD_FOOD 18: a line states what it is, when it was, and how much of it. */
    @Test
    fun aLineAnnouncesItselfWhole() {
        setDay(previewDayState())

        val id = FoodDayPreviewData.breakfast().id
        scrollTo(FoodTestTags.logEntry(id.value))

        compose.onNodeWithTag(FoodTestTags.logEntry(id.value))
            .assertContentDescriptionContains(FoodDayPreviewData.BREAKFAST_TITLE, substring = true)
        compose.onNodeWithTag(FoodTestTags.logEntry(id.value))
            .assertContentDescriptionContains("about 370 kcal", substring = true)
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyControlClearsTheTouchMinimum() {
        setDay(previewDayState())

        assertTallEnough(FoodTestTags.PREVIOUS_DAY)
        assertTallEnough(FoodTestTags.NEXT_DAY)
        assertTallEnough(FoodTestTags.DAY_DATE)

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))
        assertTallEnough(FoodTestTags.confirmPlan(MealSlot.DINNER))
        assertTallEnough(FoodTestTags.swapPlan(MealSlot.DINNER))
        assertTallEnough(FoodTestTags.dismissPlan(MealSlot.DINNER))
        assertTallEnough(FoodTestTags.addToSlot(MealSlot.DINNER))
    }

    // endregion

    // region harness

    /** The handle of one journal line, which is the only thing that tells two lines apart. */
    private fun entryTag(id: FoodLogEntryId): String = FoodTestTags.logEntry(id.value)

    /**
     * Asserts that the node handled by [tag] draws [text] somewhere inside itself.
     *
     * A moment holding exactly one line necessarily totals to that line's own figures, so
     * `≈ 420 kcal` and `— protein` are each drawn twice on such a screen — once on the line
     * and once on the moment's heading — and both of them are right (PRD_FOOD 10.1: a moment
     * shows its own total as soon as it holds a line). A sweep of the whole tree for one of
     * those strings therefore cannot say *which* of the two it means, and fails for being
     * ambiguous rather than for finding anything wrong.
     *
     * Scoping the search to a tagged subtree is what names the node instead: the assertion says
     * "the espresso line reads a known zero" or "the snack's own total reads a dash", and it
     * goes on saying exactly that when a fixture gains a line. Counting the matches would only
     * restate today's fixture, and would still not name what it is talking about.
     */
    private fun assertDrawn(tag: String, text: String) {
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun assertTallEnough(tag: String) {
        val height = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
        assertTrue("$tag is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FoodTestTags.DAY)
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    /**
     * The day on screen, held in state rather than closed over.
     *
     * `setContent` may only be called once per test, and two of the tests below have to compare
     * *two* days on the same glass — so the state is swapped instead of the content being set
     * again.
     */
    private val shown = mutableStateOf<FoodDayUiState?>(null)

    private fun setDay(state: FoodDayUiState, fontScale: Float = 1f) {
        shown.value = state
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    shown.value?.let { day ->
                        FoodDayScreen(
                            state = day,
                            onPreviousDay = { stepped-- },
                            onNextDay = { stepped++ },
                            onOpenDatePicker = {},
                            onDismissDatePicker = {},
                            onDayPicked = {},
                            onAddToSlot = { addedTo = it },
                            onEditEntry = { edited = it },
                            onConfirmPlan = { confirmed = it },
                            onSwapPlan = { swapped = it },
                            onDismissPlan = { dismissed = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Puts another day on the same screen, for the comparisons that need both. */
    private fun showDay(state: FoodDayUiState) {
        shown.value = state
        compose.waitForIdle()
    }

    /** How many of the six moments are currently drawing a total of their own. */
    private fun momentsShowingATotal(): List<MealSlot> = MealSlot.ORDERED.filter { slot ->
        compose.onAllNodesWithTag(FoodTestTags.slotTotal(slot), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }

    /** Every string the screen is currently drawing, glyph for glyph. */
    private fun drawnText(): List<String> =
        compose.onAllNodes(hasText("", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .map { it.text }

    // endregion
}
