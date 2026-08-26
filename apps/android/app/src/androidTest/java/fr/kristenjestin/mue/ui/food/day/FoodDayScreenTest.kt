package fr.kristenjestin.mue.ui.food.day

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
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

    // region the four moments (PRD_FOOD 10.1 and 17)

    @Test
    fun theFourMomentsAreAlwaysThere() {
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
     * whose protein nobody wrote down; the breakfast of an empty day holds nothing at all. The
     * strings are read off the semantics tree of the total itself — hidden from a screen reader,
     * kept for exactly this — so what is asserted is what is drawn.
     */
    @Test
    fun aKnownZeroAndAnUnknownAreDrawnDifferently() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))

        compose.onNodeWithText("≈ 0.0 g protein", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("${FoodLabels.UNKNOWN} protein", useUnmergedTree = true)
            .assertExists()
    }

    /** PRD_FOOD 22, metric by metric: an unknown protein leaves the energy a number. */
    @Test
    fun anUnknownProteinLeavesItsMomentsEnergyKnown() {
        setDay(previewDayState())

        scrollTo(FoodTestTags.slot(MealSlot.SNACK))

        compose.onNodeWithTag(FoodTestTags.slotTotal(MealSlot.SNACK), useUnmergedTree = true)
            .assertExists()
        compose.onNodeWithText("≈ 420 kcal", useUnmergedTree = true).assertExists()
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

    /** PRD_FOOD 22: "un jour futur ne peut pas être complété". */
    @Test
    fun tomorrowIsOutOfReachFromToday() {
        setDay(previewDayState())

        compose.onNodeWithTag(FoodTestTags.NEXT_DAY).assertIsNotEnabled()
        compose.onNodeWithTag(FoodTestTags.PREVIOUS_DAY).assertIsEnabled()
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

    private fun assertTallEnough(tag: String) {
        val height = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
        assertTrue("$tag is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(FoodTestTags.DAY)
            .performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }

    private fun setDay(state: FoodDayUiState, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MueTheme {
                    FoodDayScreen(
                        state = state,
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

    // endregion
}
