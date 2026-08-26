package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.testing.LocaleRule
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The food form against PRD_FOOD 15's table — **by calling the validators, never by restating
 * them**.
 *
 * Every expected sentence below is a `FoodValidation` constant rather than a literal. That is the
 * point of the file: if a bound or a wording moves in the domain, these tests follow it, and the
 * one thing they can catch is the screen quietly growing a rule of its own.
 */
class FoodEditorUiStateTest {

    /** Decimals must not follow the phone's region on the way in or on the way out. */
    @get:Rule
    val locale = LocaleRule(Locale.FRANCE)

    // region PRD_FOOD 15, field by field

    @Test
    fun `a name outside the 1 to 80 characters is refused with the domain's own sentence`() {
        val tooLong = state(FoodEditorDraft(name = "x".repeat(81), attempted = true))
        val blank = state(FoodEditorDraft(name = "   ", attempted = true))

        assertEquals(FoodValidation.NAME_ERROR, tooLong.nameError)
        assertEquals(FoodValidation.NAME_ERROR, blank.nameError)
        assertNull(state(FoodEditorDraft(name = "Oats", attempted = true)).nameError)
    }

    /** PRD_FOOD 15's ceiling is exactly 80, and the ceiling itself is accepted. */
    @Test
    fun `the longest name PRD_FOOD 15 allows is accepted`() {
        assertEquals(Food.MAX_NAME_LENGTH, FoodCataloguePreviewData.LONGEST_NAME.length)
        assertNull(
            state(FoodEditorDraft(name = FoodCataloguePreviewData.LONGEST_NAME, attempted = true))
                .nameError,
        )
    }

    @Test
    fun `energy outside 0 to 900 kcal per 100 is refused`() {
        assertEquals(
            FoodValidation.ENERGY_PER_100_ERROR,
            state(FoodEditorDraft(name = "x", energy = "901", attempted = true)).energyError,
        )
        assertNull(state(FoodEditorDraft(name = "x", energy = "900", attempted = true)).energyError)
    }

    @Test
    fun `a macronutrient outside 0 to 100 g per 100 is refused`() {
        assertEquals(
            FoodValidation.MACRO_PER_100_ERROR,
            state(FoodEditorDraft(name = "x", fibre = "101", attempted = true)).fibreError,
        )
    }

    /**
     * PRD_FOOD 15's one rule that belongs to no single field: the **known** macronutrients may
     * not add up past 100 g, and the unknown ones are ignored by the check.
     */
    @Test
    fun `the known macronutrients may not add up past 100 g`() {
        val over = state(
            FoodEditorDraft(name = "x", protein = "60", carbs = "60", fat = "40", attempted = true),
        )

        assertEquals(FoodValidation.MACRO_SUM_ERROR, over.macroSumError)
        assertNull(over.proteinError, "the sum is nobody's field, so no field is blamed for it")

        val underWithUnknowns = state(
            FoodEditorDraft(name = "x", protein = "60", fat = "40", attempted = true),
        )
        assertNull(underWithUnknowns.macroSumError)
    }

    /** PRD_FOOD FR-FOOD-006: a usual portion needs both halves or neither. */
    @Test
    fun `half a usual portion is refused and none at all is fine`() {
        assertEquals(
            FoodValidation.USUAL_SERVING_PAIR_ERROR,
            state(FoodEditorDraft(name = "x", servingLabel = "pot", attempted = true))
                .servingError,
        )
        assertNull(state(FoodEditorDraft(name = "x", attempted = true)).servingError)
        assertNull(
            state(
                FoodEditorDraft(
                    name = "x",
                    servingLabel = "pot",
                    servingSize = "150",
                    attempted = true,
                ),
            ).servingError,
        )
    }

    @Test
    fun `a barcode that is not 8 to 14 digits is refused, and none at all is fine`() {
        assertEquals(
            FoodValidation.BARCODE_ERROR,
            state(FoodEditorDraft(name = "x", barcode = "12345", attempted = true)).barcodeError,
        )
        assertNull(state(FoodEditorDraft(name = "x", attempted = true)).barcodeError)
    }

    @Test
    fun `an overlong brand is refused and a missing one is not`() {
        assertEquals(
            FoodValidation.BRAND_ERROR,
            state(FoodEditorDraft(name = "x", brand = "b".repeat(81), attempted = true))
                .brandError,
        )
        assertNull(state(FoodEditorDraft(name = "x", attempted = true)).brandError)
    }

    /** PRD_FOOD 15: "une valeur refusée est signalée sans jamais vider le formulaire". */
    @Test
    fun `a refused form keeps every character that was typed`() {
        val draft = FoodEditorDraft(
            name = "x".repeat(81),
            brand = "Bjorg",
            energy = "1200",
            protein = "7,5",
            attempted = true,
        )

        val shown = state(draft)

        assertEquals(draft.name, shown.name)
        assertEquals("Bjorg", shown.brand)
        assertEquals("1200", shown.energy)
        assertEquals("7,5", shown.protein, "a comma typed on a French phone survives the refusal")
    }

    /** PRD_FOOD 15 asks for a refusal, not for a scolding halfway through the first word. */
    @Test
    fun `nothing is refused before the form has been submitted`() {
        val untouched = state(FoodEditorDraft(name = "", energy = "1200"))

        assertNull(untouched.nameError)
        assertNull(untouched.energyError)
    }

    // endregion

    // region a blank is a null, never a zero (PRD_FOOD 13.1 and 15)

    /** PRD_FOOD 15: "aliment sans aucune valeur : accepté", and it is accepted as *unknown*. */
    @Test
    fun `a form with no figure at all yields an unknown bundle rather than zeros`() {
        val food = FoodEditorDraft(name = "Aunt's cake")
            .toFoodOrNull(id = FoodId("new"), source = FoodSource.CUSTOM)

        assertNotNull(food)
        assertEquals(Nutrients.UNKNOWN, food.per100)
        assertNull(food.per100.energy)
        assertNull(food.per100.fibre)
    }

    /** A typed `0` is a known zero, and it is not the same food as the one above. */
    @Test
    fun `a typed zero is a known zero`() {
        val food = FoodEditorDraft(name = "Black coffee", energy = "0", fibre = "0")
            .toFoodOrNull(id = FoodId("new"), source = FoodSource.CUSTOM)

        assertNotNull(food)
        assertEquals(Energy.ZERO, food.per100.energy)
        assertEquals(Macro.ZERO, food.per100.fibre)
        assertNull(food.per100.protein, "a field nobody filled in stays unknown")
    }

    /** The trip back: an unknown value comes out of the form as a blank, never as `0`. */
    @Test
    fun `an unknown value is written back into the form as a blank`() {
        val draft = FoodEditorDraft.of(FoodCataloguePreviewData.greekYoghurt())

        assertEquals("", draft.fibre)
        assertEquals("59", draft.energy)
        assertEquals("10.3", draft.protein)
        assertEquals("0.2", draft.fat)
    }

    /** And a known zero comes out as a zero, so reopening a card does not lose the fact. */
    @Test
    fun `a known zero is written back into the form as a zero`() {
        val draft = FoodEditorDraft.of(FoodCataloguePreviewData.blackCoffee())

        assertEquals("0", draft.energy)
        assertEquals("0", draft.fibre)
    }

    /** Decimals are written with a full stop whatever the phone's region says (PRD_FOOD 13.2). */
    @Test
    fun `figures are written back with a full stop under a comma locale`() {
        val draft = FoodEditorDraft.of(FoodCataloguePreviewData.rolledOats())

        assertEquals("13.2", draft.protein)
        assertFalse(draft.protein.contains(','))
    }

    // endregion

    // region what a save keeps (PRD_FOOD 8.6, 9.1, 9.2)

    /** PRD_FOOD 9.2: correcting a copied product keeps its provenance and its identifiers. */
    @Test
    fun `correcting a packaged product keeps its source and its source id`() {
        val stored = FoodCataloguePreviewData.greekYoghurt()
        val corrected = FoodEditorDraft.of(stored).copy(fibre = "0.4")
            .toFoodOrNull(id = stored.id, source = stored.source, existing = stored)

        assertNotNull(corrected)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, corrected.source)
        assertEquals(stored.sourceId, corrected.sourceId)
        assertEquals(stored.barcode, corrected.barcode)
        assertEquals(Macro.ofPer100OrNull(0.4), corrected.per100.fibre)
    }

    /**
     * PRD_FOOD 9.1: a duplicate is a personal food with a new id, and it claims no provenance.
     *
     * Keeping the Ciqual `sourceId` on it would tell `findBySourceId` that the reference table
     * produced a row it has never heard of, and a re-seeding would then have two entries to
     * reconcile.
     */
    @Test
    fun `duplicating a reference entry drops its identifiers and keeps its cooking ratio`() {
        val stored = FoodCataloguePreviewData.rolledOats().copy(
            cookedRatio = fr.kristenjestin.mue.domain.model.CookedRatio.ofRatioOrNull(2.3),
        )
        val copy = FoodEditorDraft.of(stored)
            .toFoodOrNull(id = FoodId("copy"), source = FoodSource.CUSTOM, existing = stored)

        assertNotNull(copy)
        assertEquals(FoodSource.CUSTOM, copy.source)
        assertEquals(FoodId("copy"), copy.id)
        assertNull(copy.sourceId)
        assertNull(copy.sourceVersion)
        assertFalse(copy.isReadOnly)
        assertEquals(stored.cookedRatio, copy.cookedRatio, "the ratio describes the aliment")
        assertEquals(stored.per100, copy.per100)
    }

    /** PRD_FOOD 8.6: the unit is a real choice, made once, and never converted. */
    @Test
    fun `the reference unit chosen on the form is the one that is saved`() {
        val food = FoodEditorDraft(name = "Broth", unitId = ReferenceUnit.MILLILITRE.id)
            .toFoodOrNull(id = FoodId("new"), source = FoodSource.CUSTOM)

        assertNotNull(food)
        assertEquals(ReferenceUnit.MILLILITRE, food.referenceUnit)
    }

    /** A refused form produces no food at all rather than a half-written one. */
    @Test
    fun `a refused form yields no food`() {
        assertNull(
            FoodEditorDraft(name = "").toFoodOrNull(FoodId("new"), FoodSource.CUSTOM),
        )
        assertNull(
            FoodEditorDraft(name = "x", energy = "5000")
                .toFoodOrNull(FoodId("new"), FoodSource.CUSTOM),
        )
    }

    // endregion

    // region the sheet itself

    @Test
    fun `the three modes name themselves and offer different things`() {
        val create = state(FoodEditorDraft(), mode = FoodEditorMode.CREATE)
        val edit = state(FoodEditorDraft(), mode = FoodEditorMode.EDIT)
        val reference = state(FoodEditorDraft(), mode = FoodEditorMode.REFERENCE)

        assertEquals(FoodCatalogueMessages.NEW_TITLE, create.title)
        assertEquals(FoodCatalogueMessages.EDIT_TITLE, edit.title)
        assertEquals(FoodCatalogueMessages.REFERENCE_TITLE, reference.title)

        assertEquals(FoodCatalogueMessages.SAVE, create.primaryLabel)
        assertEquals(FoodCatalogueMessages.DUPLICATE, reference.primaryLabel)

        // PRD_FOOD 9.1: reference data is neither written nor removed.
        assertTrue(reference.isReadOnly)
        assertFalse(reference.canDelete)
        // PRD_FOOD 9.3: only a row that already exists can be deleted.
        assertFalse(create.canDelete)
        assertTrue(edit.canDelete)
    }

    /** A draft survives the round trip through the `Bundle`, half-typed decimals included. */
    @Test
    fun `a draft survives being written down and read back`() {
        val draft = FoodEditorDraft(
            name = "Half typed",
            energy = "7,",
            servingLabel = "pot",
            attempted = true,
        )

        assertEquals(draft, FoodEditorDraft.fromJson(draft.toJson()))
    }

    /** A blob written by another build costs some typing, never the first frame. */
    @Test
    fun `an unreadable draft is no draft at all`() {
        assertNull(FoodEditorDraft.fromJson("{not json"))
        assertNull(FoodEditorDraft.fromJson(null))
        assertNull(FoodEditorDraft.fromJson("  "))
    }

    // endregion

    private fun state(
        draft: FoodEditorDraft,
        mode: FoodEditorMode = FoodEditorMode.CREATE,
        source: FoodSource = FoodSource.CUSTOM,
    ): FoodEditorUiState = FoodEditorUiState.of(draft = draft, mode = mode, source = source)
}
