package fr.kristenjestin.mue.ui.food.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertTextEquals
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

    private var added: Int = 0
    private var addedOn: LocalDate? = null
    private var edited: FoodLogEntryId? = null
    private var confirmed: MealPlanKey? = null
    private var swapped: MealPlanKey? = null
    private var dismissed: MealPlanKey? = null
    private var stepped: Int = 0

    // region a heading when the moment holds something (the owner, over PRD_FOOD 10.1 and 17)

    /**
     * The owner's instruction on the screen: no moment draws until it holds something.
     *
     * This used to assert the opposite — all six present, each with its own add row, PRD_FOOD
     * 10.1's "toujours présent". *"J'ai « lunch », et les snacks sont grisés ? […] est-ce qu'on
     * pourrait pas imaginer juste avoir les headers […] uniquement quand il y a un élément
     * dedans"*. There is one action for all six now, and it is at the foot of the screen.
     */
    @Test
    fun anUntouchedDayDrawsNoMomentAndOneAction() {
        setDay(FoodDayUiState.of(TODAY, TODAY))

        MealSlot.ORDERED.forEach { slot ->
            compose.onNodeWithTag(FoodTestTags.slot(slot), useUnmergedTree = true)
                .assertDoesNotExist()
        }
        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.DAY_EMPTY).assertIsDisplayed()
    }

    /** A moment appears the instant it holds a line, and its five neighbours stay away. */
    @Test
    fun aMomentAppearsOnlyOnceItHoldsSomething() {
        setDay(
            FoodDayUiState.of(
                date = TODAY,
                today = TODAY,
                entries = listOf(FoodDayPreviewData.lunch()),
            ),
        )

        compose.onNodeWithTag(FoodTestTags.slot(MealSlot.LUNCH)).assertIsDisplayed()
        (MealSlot.ORDERED - MealSlot.LUNCH).forEach { slot ->
            compose.onNodeWithTag(FoodTestTags.slot(slot), useUnmergedTree = true)
                .assertDoesNotExist()
        }
        compose.onNodeWithTag(FoodTestTags.DAY_EMPTY, useUnmergedTree = true).assertDoesNotExist()
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

    /**
     * PRD_FOOD 17: the empty state is an invitation and never an error.
     *
     * The invitation is the action at the foot of the screen; the line above it is the report.
     * Two different jobs, which the six add rows used to do at once.
     */
    @Test
    fun anEmptyDayInvitesRatherThanReports() {
        setDay(FoodDayUiState.of(TODAY, TODAY))

        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY)
            .assertTextEquals(FoodDayMessages.ADD_FIRST)
        compose.onNodeWithText(FoodDayMessages.NOTHING_LOGGED_YET).assertIsDisplayed()
    }

    /** The words change once the day holds a line, and they never name a moment. */
    @Test
    fun theActionOffersAnotherLineOnceTheDayHoldsOne() {
        setDay(
            FoodDayUiState.of(
                date = TODAY,
                today = TODAY,
                entries = listOf(FoodDayPreviewData.lunch()),
            ),
        )

        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY)
            .assertTextEquals(FoodDayMessages.ADD_MORE)
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
     * A day nobody wrote on shows **no total at all**, and now no moment either: a date, one
     * line saying nothing has been logged, and the action. A day holding one line whose protein
     * is unknown shows a moment that is plainly recorded — `≈ 420 kcal` beside `— protein`.
     * Neither of them shows a `0` where nothing is known.
     */
    @Test
    fun anEmptyDayAndAnUnknownProteinAreNotTheSameScreen() {
        setDay(emptyDayState())

        /*
         * Nothing logged: no moment claims a total, and no figure is drawn anywhere. There is
         * nothing to scroll to any more — an untouched day fits on one screen precisely because
         * no moment is drawn — so the whole tree is read where it stands.
         */
        assertEquals(emptyList<MealSlot>(), momentsShowingATotal())
        val empty = drawnText()
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
     * It says once what it is, and the day's one action stops offering to log. The action keeps
     * its place and stops being a control, which is the difference between refusing before the
     * tap and refusing after `Save entry` — and it is now said **once** rather than by six rows
     * that read as six errors.
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

        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY)
            .assertIsDisplayed()
            .assertIsNotEnabled()

        added = 0
        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY).performClick()
        assertEquals("a future day opened the add sheet anyway", 0, added)
    }

    /** Today says none of that, and its rows are buttons as they always were. */
    @Test
    fun todaySaysNothingAboutBeingAhead() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.FUTURE_DAY).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY).assertIsEnabled()
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

    /**
     * The one add action carries the **day**, and nothing else.
     *
     * The defect it fixes: at 00:26 the sheet offered `Breakfast` while showing its own window as
     * 05:00–10:00, because breakfast's `+` had passed breakfast explicitly and pinned it over the
     * clock. There is no moment to pass any more — the screen's callback takes none — so
     * `FoodAddDraft.forTarget` leaves the moment unpinned and the hour decides it (FR-FOOD-007).
     *
     * That the day travels is asserted here; that no moment does is asserted by the signature,
     * which is why this test can only count taps. `FoodStackTest` covers the route the tap builds.
     */
    @Test
    fun theAddActionCarriesTheDayAndNoMoment() {
        setDay(previewDayState(date = YESTERDAY, today = TODAY))

        compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY).performClick()

        assertEquals(1, added)
        assertEquals(YESTERDAY, addedOn)
    }

    /** One action for the six moments: no moment publishes an add control of its own. */
    @Test
    fun noMomentCarriesAnAddControlOfItsOwn() {
        setDay(previewDayState())

        assertEquals(
            1,
            compose.onAllNodesWithTag(FoodTestTags.ADD_TO_DAY).fetchSemanticsNodes().size,
        )
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

    /**
     * The last card of the day is reachable, band and all.
     *
     * `Log activity` once shipped a 112 dp strip at the foot of its scroll that no thumb could
     * touch: the whole pinned band had been subtracted from the viewport, ramp included, so
     * content came to rest under chrome that swallows gestures. This screen grew a band for the
     * first time today, so it inherits the hazard and the fix — the list's own padding is the
     * **solid block alone**, and this asserts the consequence.
     */
    @Test
    fun theLastCardIsNotHidingUnderThePinnedAction() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.plan(MealSlot.DINNER))

        val cardBottom = compose.onNodeWithTag(FoodTestTags.plan(MealSlot.DINNER))
            .getUnclippedBoundsInRoot()
            .bottom
        val actionTop = compose.onNodeWithTag(FoodTestTags.ADD_TO_DAY)
            .getUnclippedBoundsInRoot()
            .top

        assertTrue(
            "the last card ends at $cardBottom, under an action that starts at $actionTop",
            cardBottom <= actionTop,
        )
    }

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
        assertTallEnough(FoodTestTags.ADD_TO_DAY)
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
                            onAdd = {
                                added += 1
                                addedOn = day.date
                            },
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
