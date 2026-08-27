package fr.kristenjestin.mue.ui.food.add

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The food picker on the glass (PRD_FOOD 9.4, 11 and 17).
 *
 * The list is driven through the stateless screen, so what is asserted is what a person sees:
 * the names whole, the provenance beside them, the energy per 100 — and, for a card nobody has
 * filled in, a `—` rather than a plausible zero.
 */
class FoodPickerScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var picked: FoodId? = null
    private var created = 0
    private var typed: String? = null
    private var filtered: FoodSource? = null

    // region what the list shows (PRD_FOOD 9.4)

    @Test
    fun anEmptySearchShowsTheRecentlyUsedUnderTheirOwnHeading() {
        show(previewPickerState())

        compose.onNodeWithText(FoodAddMessages.RECENT_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.SEARCH_RESULTS).assertIsDisplayed()
        assertDrawn(rowTag(FoodAddPreviewData.apple()), FoodAddPreviewData.APPLE_NAME)
    }

    /**
     * PRD_FOOD 9.4: the recently used sit **at the head of** the catalogue, not in place of it.
     *
     * Both headings are on screen at once, and the rows under the second one are the catalogue's.
     */
    @Test
    fun theCatalogueIsDrawnUnderTheRecentlyUsed() {
        show(previewPickerState())

        compose.onNodeWithText(FoodAddMessages.RECENT_SECTION).assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.CATALOGUE_SECTION).assertIsDisplayed()
        assertDrawn(rowTag(FoodAddPreviewData.rice()), FoodAddPreviewData.RICE_NAME)
    }

    /**
     * The first defect, on the glass.
     *
     * A phone that has logged nothing has no recency, and that used to leave `Nothing logged yet`
     * standing over a catalogue of 1 038 seeded foods. The head may be empty; the list is not.
     */
    @Test
    fun aPhoneThatHasLoggedNothingStillSeesTheCatalogue() {
        show(previewPickerNothingLoggedState())

        compose.onNodeWithText(FoodAddMessages.NOTHING_RECENT).assertDoesNotExist()
        compose.onNodeWithText(FoodAddMessages.CATALOGUE_SECTION).assertIsDisplayed()
        assertDrawn(rowTag(FoodAddPreviewData.rice()), FoodAddPreviewData.RICE_NAME)
    }

    /** FR-CATALOG-004: every row says where its food came from, and what it is worth per 100. */
    @Test
    fun aRowSaysWhereItsFoodCameFromAndWhatItIsWorth() {
        show(previewPickerState())

        val rice = rowTag(FoodAddPreviewData.rice())
        assertDrawn(rice, FoodAddMessages.SOURCE_CIQUAL)
        assertDrawn(rice, "≈ 349 kcal")
        assertDrawn(rice, "Per 100 g raw")
    }

    @Test
    fun tappingARowChoosesThatFood() {
        show(previewPickerState())

        compose.onNodeWithTag(rowTag(FoodAddPreviewData.apple())).performClick()

        assertEquals(FoodAddPreviewData.apple().id, picked)
    }

    /** PRD_FOOD 18: a row is announced whole rather than as three loose fragments. */
    @Test
    fun aRowAnnouncesItselfWhole() {
        show(previewPickerState())

        compose.onNodeWithTag(rowTag(FoodAddPreviewData.rice()))
            .assertContentDescriptionContains(FoodAddPreviewData.RICE_NAME, substring = true)
        compose.onNodeWithTag(rowTag(FoodAddPreviewData.rice()))
            .assertContentDescriptionContains("about 349 kcal", substring = true)
    }

    // endregion

    // region searching and filtering

    /**
     * Typing reaches the search.
     *
     * Aimed at the **editable node**, not at the handle. `FoodTestTags.SEARCH_FIELD` sits on
     * `MueSearchField`'s outer row — the pill with its icon, its border and its clear button — and
     * that row has no text action of its own, so `performTextInput` on it could only ever fail
     * with "RequestFocus is defined". The field inside carries `SEARCH_LABEL` as its description,
     * which is what a screen reader uses to find it and what this uses too.
     */
    @Test
    fun typingReachesTheSearch() {
        show(previewPickerState())

        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.SEARCH_FIELD)),
        ).performTextInput("rice")

        assertEquals("rice", typed)
    }

    @Test
    fun theSourceFilterIsOfferedAndChoosingOneReachesTheSearch() {
        show(previewPickerState())

        compose.onNodeWithText(FoodAddMessages.SOURCE_ALL).assertIsDisplayed()
        compose.onNodeWithText(FoodAddMessages.SOURCE_CUSTOM).performClick()

        assertEquals(FoodSource.CUSTOM, filtered)
    }

    // endregion

    // region the empty states (PRD_FOOD 17)

    @Test
    fun aSearchWithNoResultSaysSoAndOffersToCreateOne() {
        show(previewEmptyPickerState())

        compose.onNodeWithText(FoodAddMessages.NO_RESULTS).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.CREATE_FOOD).assertIsDisplayed().performClick()

        assertEquals(1, created)
    }

    /**
     * Nothing logged yet is a different fact, and offers no creation of its own.
     *
     * It can only be reached with the catalogue empty as well, which on a seeded phone never
     * happens — the state is built by hand here precisely because the app cannot produce it.
     */
    @Test
    fun nothingLoggedYetSaysSoWithoutOfferingACreation() {
        show(
            previewPickerState().copy(
                recent = emptyList(),
                results = emptyList(),
                emptyMessage = FoodAddMessages.NOTHING_RECENT,
            ),
        )

        compose.onNodeWithText(FoodAddMessages.NOTHING_RECENT).assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.CREATE_FOOD).assertDoesNotExist()
    }

    // endregion

    // region PRD_FOOD 9.2: an incomplete card is the nominal case

    @Test
    fun aFoodWithNoEnergyReadsAsUnknownAndNeverAsZero() {
        val unknown = FoodPickerRowUiState.of(
            Food(
                id = FoodId("androidtest-unknown"),
                name = "Own brand oat biscuits",
                source = FoodSource.OPEN_FOOD_FACTS,
                brand = "Own brand",
            ),
        )
        show(previewPickerState().copy(results = listOf(unknown)))

        assertDrawn(FoodTestTags.foodCard("androidtest-unknown"), FoodLabels.UNKNOWN)
        compose.onNodeWithText("≈ 0 kcal").assertDoesNotExist()
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyRowClearsTheTouchMinimum() {
        show(previewPickerState())

        val height = compose.onNodeWithTag(rowTag(FoodAddPreviewData.rice()))
            .getUnclippedBoundsInRoot()
            .height
        assertTrue("a picker row is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    // endregion

    // region harness

    private fun rowTag(food: Food): String =
        FoodTestTags.foodCard(food.id.value)

    private fun assertDrawn(tag: String, text: String) {
        compose.onNodeWithTag(FoodTestTags.SEARCH_RESULTS)
            .performScrollToNode(hasTestTag(tag))
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text)),
            useUnmergedTree = true,
        ).assertExists()
    }

    private val shown = mutableStateOf<FoodPickerUiState?>(null)

    private fun show(state: FoodPickerUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                shown.value?.let { picker ->
                    FoodPickerScreen(
                        state = picker,
                        onQueryChange = { typed = it },
                        onClearQuery = { typed = "" },
                        onSourceSelected = { filtered = it },
                        onPicked = { picked = it },
                        onCreateFood = { created++ },
                        onBack = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
