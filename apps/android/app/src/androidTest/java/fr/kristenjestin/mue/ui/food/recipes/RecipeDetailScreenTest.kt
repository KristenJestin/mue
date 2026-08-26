package fr.kristenjestin.mue.ui.food.recipes

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Servings
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

private val TUESDAY_LUNCH = MealPlanKey(LocalDate.of(2026, 9, 1), MealSlot.LUNCH)

/**
 * The recipe card as it reaches the glass (PRD_FOOD 11, 13, 17, 18, 21.2 and FR-RECIPE-004
 * to 006).
 *
 * The point of half of this suite is a single glyph. PRD_FOOD 13.2 says an unknown value is `—`
 * and never `0`, and the last layer is the one that can undo every strict sum upstream of it —
 * so the assertions read what is actually drawn, scoped to a named subtree, rather than sweeping
 * the tree for a string that a card and a total both legitimately draw.
 */
class RecipeDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var backed = 0
    private var edited = 0
    private var starred = 0
    private var deleteRequested = 0
    private var deleteCancelled = 0
    private var deleteConfirmed = 0
    private var acknowledged = 0
    private var stepped = 0

    // region an orphan ingredient (PRD_FOOD 21.2 and 13.1)

    /**
     * **The assertion this half of the module exists to make.**
     *
     * The curry names a food this device has never received. Its row is still drawn, from the
     * `foodName` snapshot PRD_FOOD 21.2 keeps for exactly this, and its energy is a dash — and
     * so is every figure of the recipe, because a strict sum is unknown as soon as one
     * contribution is. Nowhere on the card is there a `0`.
     */
    @Test
    fun anOrphanIngredientIsDrawnAndItsRecipeReadsUnknownRatherThanZero() {
        setCard(orphanRecipeDetailState())

        val orphanRow = RecipeTestTags.detailIngredient(orphanIngredientId())
        scrollTo(orphanRow)

        assertDrawn(orphanRow, RecipePreviewData.ORPHAN_SNAPSHOT)
        assertDrawn(orphanRow, FoodLabels.UNKNOWN)
        /*
         * Unmerged. The ingredient card announces itself as one sentence, so everything **under**
         * it is cleared from the merged tree — which is exactly what stops a screen reader hearing
         * a name, a quantity and a warning as three fragments. The card keeps its own handle; the
         * warning inside it does not, so a merged lookup could never find this tag and never did.
         */
        compose.onNodeWithTag(
            RecipeTestTags.orphanIngredient(orphanIngredientId()),
            useUnmergedTree = true,
        ).assertExists()

        scrollTo(FoodTestTags.RECIPE_PER_SERVING)
        assertDrawn(FoodTestTags.RECIPE_PER_SERVING, FoodLabels.UNKNOWN)

        scrollTo(RecipeTestTags.RECIPE_TOTAL)
        assertDrawn(RecipeTestTags.RECIPE_TOTAL, FoodLabels.UNKNOWN)

        val drawn = drawnText()
        assertTrue(
            "a recipe with an unknown ingredient drew an energy: $drawn",
            drawn.none { it.contains("0 ${FoodLabels.ENERGY_UNIT}") },
        )
        assertTrue(
            "a recipe with an unknown ingredient drew a zero macronutrient: $drawn",
            drawn.none { it.startsWith("${FoodLabels.APPROXIMATE_PREFIX}0.0") },
        )
    }

    /** The ingredient that *is* known keeps its own figure, metric by metric (PRD_FOOD 22). */
    @Test
    fun aKnownIngredientOfAnUnknownRecipeKeepsItsFigure() {
        setCard(orphanRecipeDetailState())

        val known = orphanRecipeDetailState().ingredients.first { !it.isOrphan }
        val energy = requireNotNull(known.energyLabel)
        val tag = RecipeTestTags.detailIngredient(known.id)
        scrollTo(tag)

        assertDrawn(tag, energy)
        assertNotEquals(FoodLabels.UNKNOWN, energy)
    }

    /**
     * FR-FOOD-010: "masquer l'énergie retire tous les chiffres nutritionnels sans casser un
     * parcours".
     *
     * Not one figure is left on the glass — no `kcal`, no gram, and no dash standing in for one —
     * while the ingredients, the steps and the servings counter are exactly where they were.
     */
    @Test
    fun hidingTheEnergyLeavesNoFigureAndBreaksNothing() {
        setCard(hiddenEnergyRecipeDetailState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_PER_SERVING).assertDoesNotExist()
        compose.onNodeWithTag(RecipeTestTags.RECIPE_TOTAL).assertDoesNotExist()

        val drawn = drawnText()
        assertTrue(
            "a figure survived the preference: $drawn",
            drawn.none { it.contains(FoodLabels.ENERGY_UNIT) || it.contains(FoodLabels.UNKNOWN) },
        )

        previewRecipeDetailState().ingredients.forEach { ingredient ->
            val tag = RecipeTestTags.detailIngredient(ingredient.id)
            scrollTo(tag)
            assertDrawn(tag, ingredient.name)
            assertDrawn(tag, ingredient.quantityLabel)
        }
        scrollTo(RecipeTestTags.MORE_SERVINGS)
        compose.onNodeWithTag(RecipeTestTags.MORE_SERVINGS).assertExists()
    }

    // endregion

    // region nothing to total, and an unknown, are not the same card

    /**
     * PRD_FOOD 13.1 in two states on the same glass.
     *
     * A recipe with no ingredient shows **no block at all** — an empty strict sum is a known zero
     * and printing it would be an invented total. A recipe whose ingredient is unknown shows the
     * blocks, reading `—`. Neither of them shows a `0`.
     */
    @Test
    fun aRecipeWithNothingToTotalAndOneThatIsUnknownAreNotTheSameCard() {
        setCard(emptyRecipeDetailState())

        compose.onNodeWithTag(FoodTestTags.RECIPE_PER_SERVING).assertDoesNotExist()
        compose.onNodeWithTag(RecipeTestTags.RECIPE_TOTAL).assertDoesNotExist()
        compose.onNodeWithText(RecipeMessages.NO_INGREDIENTS).assertIsDisplayed()

        val empty = drawnText()
        assertTrue(
            "a recipe with nothing to total drew an energy: $empty",
            empty.none { it.contains(FoodLabels.ENERGY_UNIT) },
        )

        setState(orphanRecipeDetailState())
        scrollTo(FoodTestTags.RECIPE_PER_SERVING)

        compose.onNodeWithTag(FoodTestTags.RECIPE_PER_SERVING).assertExists()
        assertNotEquals("the two cards drew the same thing", empty, drawnText())
    }

    // endregion

    // region servings (FR-RECIPE-004)

    @Test
    fun theCardOpensOnTheServingsTheRecipeIsWrittenFor() {
        setCard(previewRecipeDetailState())

        /*
         * Scrolled to by the stepper beside it rather than by the figure itself. The servings
         * block announces `Servings, 2` as one thing, so the label and the number under it are
         * cleared from the merged tree — `FoodTestTags.RECIPE_SERVINGS` is on the number, and
         * `performScrollTo` looks in the merged tree, so it could never find it. The two step
         * buttons are siblings of that block and keep their handles.
         */
        scrollTo(RecipeTestTags.MORE_SERVINGS)
        compose.onNodeWithText(
            RecipeFormat.servings(servings(2.0)),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(FoodTestTags.RECIPE_SERVINGS, useUnmergedTree = true).assertExists()
    }

    @Test
    fun theServingsStepsAreThereAndReport() {
        setCard(previewRecipeDetailState())

        scrollTo(RecipeTestTags.MORE_SERVINGS)
        compose.onNodeWithTag(RecipeTestTags.MORE_SERVINGS).performClick()
        compose.onNodeWithTag(RecipeTestTags.FEWER_SERVINGS).performClick()

        assertEquals(2, stepped)
    }

    /** Changing the count changes the quantities on screen, which is the whole of FR-RECIPE-004. */
    @Test
    fun raisingTheServingsRaisesTheQuantitiesDrawn() {
        setCard(previewRecipeDetailState())

        val first = previewRecipeDetailState().ingredients.first()
        val tag = RecipeTestTags.detailIngredient(first.id)
        scrollTo(tag)
        assertDrawn(tag, first.quantityLabel)

        setState(
            RecipeDetailUiState.of(
                detail = RecipePreviewData.salmon(),
                foods = RecipePreviewData.catalogueById(),
                servings = servings(4.0),
            ),
        )
        scrollTo(tag)

        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(first.quantityLabel)),
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    // endregion

    // region deletion (FR-RECIPE-006 and PRD_FOOD 17)

    @Test
    fun deletingAsksBeforeItActs() {
        setCard(previewRecipeDetailState())

        compose.onNodeWithTag(RecipeTestTags.DELETE_RECIPE).performClick()
        assertEquals(1, deleteRequested)
        assertEquals(0, deleteConfirmed)

        setState(previewRecipeDetailState(deletion = RecipeDeletionUiState.Confirming))
        compose.onNodeWithText(RecipeMessages.DELETE_TITLE).assertIsDisplayed()

        compose.onNodeWithText(RecipeMessages.CANCEL).performClick()
        assertEquals(1, deleteCancelled)
    }

    /**
     * PRD_FOOD 17: "le moment est libéré **et signalé**".
     *
     * The keys `RecipeRepository.delete` returned are on screen, by date and by moment, and the
     * card does not leave until they have been read.
     */
    @Test
    fun aDeletionNamesTheMomentsItFreed() {
        setCard(
            previewRecipeDetailState(
                deletion = RecipeDeletionUiState.deleted(listOf(TUESDAY_LUNCH), Locale.UK),
            ),
        )

        compose.onNodeWithText(RecipeMessages.DELETED_TITLE).assertIsDisplayed()
        compose.onNodeWithTag(RecipeTestTags.FREED_PLANS).assertExists()
        compose.onNodeWithText(RecipeFormat.planLabel(TUESDAY_LUNCH, Locale.UK)).assertIsDisplayed()

        compose.onNodeWithText(RecipeMessages.DONE).performClick()
        assertEquals(1, acknowledged)
    }

    /** A deletion that freed nothing has nothing to report and never opens a second dialog. */
    @Test
    fun aDeletionThatFreedNothingSaysNothing() {
        setCard(
            previewRecipeDetailState(
                deletion = RecipeDeletionUiState.deleted(emptyList(), Locale.UK),
            ),
        )

        compose.onNodeWithText(RecipeMessages.DELETED_TITLE).assertDoesNotExist()
        compose.onNodeWithTag(RecipeTestTags.FREED_PLANS).assertDoesNotExist()
    }

    // endregion

    // region what the rest of the card does

    @Test
    fun theStepsAreNumberedAndAllThere() {
        setCard(previewRecipeDetailState())

        RecipePreviewData.salmon().recipe.steps.forEach { step ->
            scrollTo(RecipeTestTags.RECIPE_STEPS)
            compose.onNodeWithText(step, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun theCardOpensTheFormStarsAndGoesBack() {
        setCard(previewRecipeDetailState())

        compose.onNodeWithTag(FoodTestTags.EDIT_RECIPE).performClick()
        assertEquals(1, edited)

        compose.onNodeWithTag(FoodTestTags.favouriteRecipe(RecipePreviewData.SALMON_ID.value))
            .performClick()
        assertEquals(1, starred)

        compose.onNodeWithContentDescription(RecipeMessages.BACK).performClick()
        assertEquals(1, backed)
    }

    @Test
    fun aRecipeThatIsGoneSaysSoAndOffersNoActions() {
        setCard(RecipeDetailUiState.of(detail = null))

        assertDrawn(FoodTestTags.RECIPE_DETAIL, RecipeMessages.MISSING_RECIPE)
        compose.onNodeWithTag(RecipeTestTags.DELETE_RECIPE).assertDoesNotExist()
        compose.onNodeWithTag(FoodTestTags.EDIT_RECIPE).assertDoesNotExist()
    }

    // endregion

    // region touch targets (PRD_FOOD 18)

    @Test
    fun everyControlClearsTheTouchMinimum() {
        setCard(previewRecipeDetailState())

        scrollTo(RecipeTestTags.MORE_SERVINGS)
        assertTallEnough(RecipeTestTags.MORE_SERVINGS)
        assertTallEnough(RecipeTestTags.FEWER_SERVINGS)
        assertTallEnough(FoodTestTags.favouriteRecipe(RecipePreviewData.SALMON_ID.value))
        assertTallEnough(FoodTestTags.EDIT_RECIPE)
        assertTallEnough(RecipeTestTags.DELETE_RECIPE)
    }

    // endregion

    // region harness

    private fun orphanIngredientId(): String =
        orphanRecipeDetailState().ingredients.first { it.isOrphan }.id

    private fun servings(count: Double): Servings =
        requireNotNull(Servings.ofConsumedOrNull(count))

    /**
     * Asserts that the node handled by [tag] draws [text] somewhere inside itself.
     *
     * Scoped rather than swept: a card holding one ingredient necessarily totals to that
     * ingredient's own figure, so the same string is legitimately drawn twice and a sweep could
     * not say which it meant.
     */
    private fun assertDrawn(tag: String, text: String) {
        compose.onNode(
            hasTestTag(tag) and hasAnyDescendant(hasText(text, substring = true)),
            useUnmergedTree = true,
        ).assertExists()
    }

    /** Every string currently on the glass, for the assertions that are about an absence. */
    private fun drawnText(): List<String> = compose
        .onAllNodes(hasText("", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text }
        }

    private fun assertTallEnough(tag: String) {
        val height = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
        assertTrue("$tag is $height, under $MueMinTouchTarget", height >= MueMinTouchTarget)
    }

    private fun scrollTo(tag: String): SemanticsNodeInteraction =
        compose.onNodeWithTag(tag).performScrollTo().also { compose.waitForIdle() }

    private val shown = mutableStateOf<RecipeDetailUiState?>(null)

    private fun setState(state: RecipeDetailUiState) {
        shown.value = state
        compose.waitForIdle()
    }

    private fun setCard(state: RecipeDetailUiState) {
        shown.value = state
        compose.setContent {
            MueTheme {
                shown.value?.let { card ->
                    RecipeDetailScreen(
                        state = card,
                        onBack = { backed++ },
                        onEdit = { edited++ },
                        onToggleFavourite = { starred++ },
                        onFewerServings = { stepped++ },
                        onMoreServings = { stepped++ },
                        onRequestDelete = { deleteRequested++ },
                        onCancelDelete = { deleteCancelled++ },
                        onConfirmDelete = { deleteConfirmed++ },
                        onDeletionAcknowledged = { acknowledged++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    // endregion
}
