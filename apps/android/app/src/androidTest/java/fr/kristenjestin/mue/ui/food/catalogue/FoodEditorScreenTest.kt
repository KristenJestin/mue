package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The food form on the glass (PRD_FOOD 9.1, 9.3, 15, 17 and FR-CATALOG-003).
 *
 * The error sentences asserted below are `FoodValidation`'s own constants, never literals: the
 * screen's job is to put the domain's words beside the right field, and a test spelling them out
 * again would agree with itself rather than with PRD_FOOD 15.
 */
@RunWith(AndroidJUnit4::class)
class FoodEditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var saved: Int = 0
    private var deleteRequested: Int = 0
    private var deleteConfirmed: Int = 0
    private var name: String? = null

    private val shown = mutableStateOf(previewFoodEditorState())

    // region PRD_FOOD 15: a refused value beside its field, and nothing emptied

    @Test
    fun aRefusedValueIsShownBesideItsOwnField() {
        setEditor(refusedFoodEditorState())

        compose.onNodeWithText(FoodValidation.NAME_ERROR).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(FoodValidation.ENERGY_PER_100_ERROR)
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** PRD_FOOD 15: "sans jamais vider le formulaire". */
    @Test
    fun aRefusedFormKeepsWhatWasTyped() {
        setEditor(refusedFoodEditorState())

        compose.onNodeWithText("1200").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bjorg").performScrollTo().assertIsDisplayed()
    }

    /** The one rule of PRD_FOOD 15 that belongs to no field is shown under the group it judges. */
    @Test
    fun theMacroSumRefusalIsShownUnderTheGroup() {
        setEditor(macroSumRefusedFoodEditorState())

        compose.onNodeWithText(FoodValidation.MACRO_SUM_ERROR).performScrollTo().assertIsDisplayed()
    }

    /** PRD_FOOD 15 and 9.2: a card with no value is ordinary, and the form says so first. */
    @Test
    fun theFormSaysThatAnEmptyFieldMeansUnknown() {
        setEditor(previewFoodEditorState())

        compose.onNodeWithText(FoodCatalogueMessages.VALUES_HINT)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // endregion

    // region typing

    @Test
    fun typingANameReachesTheViewModel() {
        setEditor(previewFoodEditorState())

        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.FOOD_NAME_FIELD)),
        ).performScrollTo().performTextReplacement("Skyr")

        assertEquals("Skyr", name)
    }

    @Test
    fun savingReachesTheViewModel() {
        setEditor(previewFoodEditorState())

        compose.onNodeWithTag(FoodTestTags.CONFIRM_BUTTON).performClick()

        assertEquals(1, saved)
    }

    // endregion

    // region PRD_FOOD 9.1: a reference entry

    /**
     * A Ciqual entry is read here and duplicated, never written.
     *
     * The fields are inert, the action says `Duplicate`, the delete control is absent, and the
     * reason is on screen rather than left for someone to discover by pressing things.
     */
    @Test
    fun aReferenceEntryIsInertAndOffersTheDuplicate() {
        setEditor(referenceFoodEditorState())

        /*
         * A disabled `BasicTextField` publishes **no** `SetText` action at all — that is what
         * being disabled means in the semantics tree — so `hasSetTextAction()` cannot match one
         * and this assertion could never have run against a read-only card. It looked for the
         * editable node in order to assert it was not editable.
         *
         * The absence *is* the claim, so it is what is asserted: there is nothing typable inside
         * the name field, and the field itself is on screen and inert.
         */
        compose.onNodeWithTag(FoodTestTags.FOOD_NAME_FIELD).performScrollTo().assertIsDisplayed()
        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.FOOD_NAME_FIELD)),
        ).assertDoesNotExist()

        compose.onNodeWithText(FoodCatalogueMessages.DUPLICATE).assertIsDisplayed()
        compose.onNodeWithText(FoodCatalogueMessages.READ_ONLY_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).assertDoesNotExist()
    }

    /** PRD_FOOD 9.2: a copied product is editable, and it says what it keeps. */
    @Test
    fun aPackagedProductIsEditableAndKeepsItsOrigin() {
        setEditor(previewFoodEditorState())

        compose.onNode(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(FoodTestTags.FOOD_NAME_FIELD)),
        ).performScrollTo().assertIsEnabled()

        compose.onNodeWithText(FoodCatalogueMessages.KEEPS_SOURCE_NOTE)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).assertIsDisplayed()
    }

    // endregion

    // region PRD_FOOD 9.3 and 17: the deletion and its refusals

    @Test
    fun deletingAsksBeforeItActs() {
        setEditor(previewFoodEditorState())

        compose.onNodeWithTag(FoodTestTags.DELETE_BUTTON).performClick()

        assertEquals(1, deleteRequested)
        assertEquals(0, deleteConfirmed)
    }

    /** PRD_FOOD 9.3: the question says what survives — the journal keeps its frozen values. */
    @Test
    fun theQuestionSaysWhatIsAndIsNotLost() {
        setEditor(
            previewFoodEditorState().copy(deletion = FoodDeletionUiState.Confirming),
        )

        compose.onNodeWithText(FoodCatalogueMessages.DELETE_TITLE).assertIsDisplayed()
        compose.onNodeWithText(
            FoodCatalogueMessages.deleteBody(FoodCataloguePreviewData.YOGHURT_NAME),
        ).assertIsDisplayed()

        compose.onNodeWithText(FoodCatalogueMessages.DELETE_CONFIRM).performClick()
        assertEquals(1, deleteConfirmed)
    }

    /**
     * PRD_FOOD 17 and 22: "les recettes concernées sont nommées".
     *
     * The names are on the glass, not a count of them — which is the whole reason
     * `FoodDeletion.UsedByRecipes` carries the list.
     */
    @Test
    fun aRefusalNamesTheRecipesThatHoldTheFood() {
        val message = FoodCatalogueMessages.usedByRecipes(listOf("Tiramisu", "Overnight oats"))
        setEditor(
            previewFoodEditorState().copy(deletion = FoodDeletionUiState.Refused(message)),
        )

        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Tiramisu", substring = true).assertExists()
        compose.onNodeWithText("Overnight oats", substring = true).assertExists()
    }

    /** PRD_FOOD 9.1: reference data is not the person's to remove, and the way out is named. */
    @Test
    fun aReadOnlyRefusalPointsAtTheDuplicate() {
        setEditor(
            referenceFoodEditorState().copy(
                deletion = FoodDeletionUiState.Refused(FoodCatalogueMessages.READ_ONLY_REFUSAL),
            ),
        )

        compose.onNodeWithText(FoodCatalogueMessages.READ_ONLY_REFUSAL).assertIsDisplayed()
        compose.onNodeWithText(FoodCatalogueMessages.CLOSE_DELETION).assertIsDisplayed()
    }

    // endregion

    private fun setEditor(state: FoodEditorUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                FoodEditorScreen(
                    state = shown.value,
                    actions = FoodEditorActions(
                        onNameChange = { name = it },
                        onSave = { saved++ },
                        onDeleteRequested = { deleteRequested++ },
                        onDeleteConfirmed = { deleteConfirmed++ },
                    ),
                )
            }
        }
        compose.waitForIdle()
    }
}
