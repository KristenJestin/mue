package fr.kristenjestin.mue.ui.food.catalogue

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.ui.food.day.FoodDayFormat
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the catalogue hands the glass, and the one rule it exists to keep: PRD_FOOD 13.2's
 * unknown is `—` and a known zero is a zero, at every level and on every row.
 *
 * Every assertion here is about a *rendered string*, because that is the last place the rule can
 * still be lost. The domain proves the arithmetic; this file proves that nothing between the
 * arithmetic and the card supplies a fallback.
 */
class FoodCatalogueUiStateTest {

    /** The figures must not follow the phone's region (PRD_FOOD 13.2: `tabular-nums`, not a locale). */
    @get:Rule
    val locale = LocaleRule(Locale.UK)

    // region unknown, zero, and nothing at all

    /**
     * The pair that PRD_FOOD 13.2 exists for, side by side.
     *
     * The yoghurt's fibre is `null` — Open Food Facts does not state one, which PRD_FOOD 9.2
     * calls the nominal case. The coffee's fibre is `Macro.ZERO` — black coffee really has none.
     * One reads `— fibre` and the other `≈ 0.0 g fibre`, and no code path may produce either
     * from the other.
     */
    @Test
    fun `an unknown fibre and a known zero are two different rows`() {
        val yoghurt = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt())
        val coffee = FoodRowUiState.of(FoodCataloguePreviewData.blackCoffee())

        assertEquals("${FoodLabels.UNKNOWN} fibre", yoghurt.figures.last())
        assertEquals("≈ 0.0 g fibre", coffee.figures.last())
        assertNotEquals(yoghurt.figures.last(), coffee.figures.last())

        // And the unknown never borrows a digit from the zero.
        assertFalse(yoghurt.figures.last().contains('0'))
    }

    /** PRD_FOOD 15: "aliment sans aucune valeur : accepté" — five dashes, and no error. */
    @Test
    fun `a food with no value at all is five dashes and never five zeros`() {
        val row = FoodRowUiState.of(FoodCataloguePreviewData.auntsCake())

        assertEquals(FoodLabels.UNKNOWN, row.figures.first())
        assertEquals(
            listOf("— protein", "— carbs", "— fat", "— fibre"),
            row.figures.drop(1),
        )
        assertTrue(row.figures.none { it.contains('0') })
    }

    /** A known zero energy is a zero, and it keeps its approximation mark and its unit. */
    @Test
    fun `a known zero energy reads as a zero`() {
        val row = FoodRowUiState.of(FoodCataloguePreviewData.blackCoffee())

        assertEquals("≈ 0 kcal", row.figures.first())
    }

    /** The four macronutrients keep their nouns, so a missing row cannot be mistaken for one. */
    @Test
    fun `every macronutrient is named, including the ones nobody wrote down`() {
        val row = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt())

        assertEquals(5, row.figures.size)
        assertEquals("≈ 59 kcal", row.figures[0])
        assertEquals("≈ 10.3 g protein", row.figures[1])
        assertEquals("≈ 3.6 g carbs", row.figures[2])
        assertEquals("≈ 0.2 g fat", row.figures[3])
        assertEquals("— fibre", row.figures[4])
    }

    // endregion

    // region `Show energy` (PRD_FOOD 13.2 and FR-FOOD-010)

    /**
     * PRD_FOOD 22: "masquer l'énergie depuis les préférences retire tous les chiffres
     * nutritionnels sans casser un parcours".
     *
     * Every figure goes and nothing else does: the name, the brand, the provenance and the basis
     * are all still there, so the row is still a row you can find a food with.
     */
    @Test
    fun `hiding energy removes every figure and nothing else`() {
        val shown = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt(), showEnergy = true)
        val hidden = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt(), showEnergy = false)

        assertTrue(shown.hasFigures)
        assertFalse(hidden.hasFigures)
        assertEquals(emptyList(), hidden.figures)

        assertEquals(shown.name, hidden.name)
        assertEquals(shown.brand, hidden.brand)
        assertEquals(shown.sourceLabel, hidden.sourceLabel)
        assertEquals(shown.basisLabel, hidden.basisLabel)
    }

    /** A hidden figure is hidden from the ear as well: nothing may leak into what is announced. */
    @Test
    fun `hiding energy also removes the figures from what is announced`() {
        val hidden = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt(), showEnergy = false)

        assertFalse(hidden.description.contains("kcal"), hidden.description)
        assertFalse(hidden.description.contains("protein"), hidden.description)
        assertFalse(hidden.description.contains(FoodDayFormat.UNKNOWN_SPOKEN), hidden.description)
        assertTrue(hidden.description.contains(FoodCataloguePreviewData.YOGHURT_NAME))
    }

    /**
     * The withheld row is not the row of a food that has nothing written down.
     *
     * `Show energy` off must not read like `Aunt Simone's walnut cake`. One says "you asked not
     * to see this", the other says "nobody knows"; drawing them alike would be exactly the
     * confusion PRD_FOOD 13.2 spends the module's effort preventing.
     */
    @Test
    fun `a hidden figure is not the same thing as an unknown one`() {
        val hidden = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt(), showEnergy = false)
        val unknown = FoodRowUiState.of(FoodCataloguePreviewData.auntsCake(), showEnergy = true)

        assertFalse(hidden.hasFigures)
        assertTrue(unknown.hasFigures)
        assertTrue(unknown.figures.all { it.contains(FoodLabels.UNKNOWN) })
    }

    // endregion

    // region provenance (FR-CATALOG-004)

    @Test
    fun `every row says where it came from and only Ciqual is read-only`() {
        val ciqual = FoodRowUiState.of(FoodCataloguePreviewData.rolledOats())
        val packaged = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt())
        val personal = FoodRowUiState.of(FoodCataloguePreviewData.blackCoffee())

        assertEquals("Generic", ciqual.sourceLabel)
        assertEquals("Packaged", packaged.sourceLabel)
        assertEquals("Personal", personal.sourceLabel)

        assertTrue(ciqual.isReadOnly)
        assertFalse(packaged.isReadOnly, "PRD_FOOD 9.2: a copied product is editable")
        assertFalse(personal.isReadOnly)
    }

    /** PRD_FOOD 8.6: a liquid is quoted per 100 ml and Mue applies no density between the two. */
    @Test
    fun `a liquid is quoted per 100 millilitres`() {
        assertEquals(
            "per 100 ml",
            FoodRowUiState.of(FoodCataloguePreviewData.blackCoffee()).basisLabel,
        )
        assertEquals(
            "per 100 g",
            FoodRowUiState.of(FoodCataloguePreviewData.rolledOats()).basisLabel,
        )
    }

    /** PRD_FOOD 18: a row states what it is, whose it is, and what it is worth, in one breath. */
    @Test
    fun `a row announces itself whole, in words rather than glyphs`() {
        val spoken = FoodRowUiState.of(FoodCataloguePreviewData.greekYoghurt()).description

        assertTrue(spoken.startsWith(FoodCataloguePreviewData.YOGHURT_NAME), spoken)
        assertTrue(spoken.contains(FoodCataloguePreviewData.YOGHURT_BRAND), spoken)
        assertTrue(spoken.contains("Packaged"), spoken)
        assertTrue(spoken.contains("about 59 kcal"), spoken)
        assertTrue(spoken.contains("${FoodDayFormat.UNKNOWN_SPOKEN} fibre"), spoken)

        // `≈` and `—` are drawings; neither survives into what is heard.
        assertFalse(spoken.contains(FoodLabels.UNKNOWN), spoken)
        assertFalse(spoken.contains('≈'), spoken)
    }

    // endregion

    // region the list itself (PRD_FOOD 9.4, 9.5 and 17)

    /** PRD_FOOD 17: a search with no result names the term and offers to create it. */
    @Test
    fun `a fruitless search offers a food prefilled with what was typed`() {
        val state = FoodsUiState(query = "  kombucha  ", isLoading = false, results = emptyList())

        assertEquals("Nothing in the catalogue matches “kombucha”.", state.emptyMessage)
        assertEquals("Create “kombucha”", state.createLabel)
        assertEquals("kombucha", state.createPrefill)
    }

    /** With nothing typed the action is the plain one, and carries no term. */
    @Test
    fun `with nothing typed the action is simply a new food`() {
        val state = FoodsUiState(isLoading = false, results = emptyList())

        assertEquals(FoodCatalogueMessages.CREATE_FOOD, state.createLabel)
        assertNull(state.createPrefill)
        assertNull(state.emptyMessage, "the seeded catalogue is never empty of itself")
    }

    /** A filter that empties the list is a different fact from a search that found nothing. */
    @Test
    fun `a filter that finds nothing says which filter`() {
        val state = FoodsUiState(
            source = FoodSource.OPEN_FOOD_FACTS,
            isLoading = false,
            results = emptyList(),
        )

        assertEquals("Nothing under Packaged yet.", state.emptyMessage)
    }

    /** Nothing is said while the answer is still being read. */
    @Test
    fun `a list that has not been read yet says nothing at all`() {
        assertNull(FoodsUiState(query = "kombucha", isLoading = true).emptyMessage)
    }

    /**
     * PRD_FOOD 9.4 and 9.5: 1 038 entries, a page of them, and the page says so.
     *
     * A silent truncation is how someone concludes their food is not in the catalogue.
     */
    @Test
    fun `a full page admits that it is one`() {
        val rows = List(FoodCatalogueViewModel.RESULT_LIMIT) {
            FoodRowUiState.of(FoodCataloguePreviewData.rolledOats())
        }

        assertTrue(FoodsUiState(isLoading = false, results = rows).isCapped)
        assertFalse(FoodsUiState(isLoading = false, results = rows.drop(1)).isCapped)
        assertEquals(
            "Showing the first 60. Keep typing to narrow it down.",
            FoodCatalogueMessages.showingFirst(FoodCatalogueViewModel.RESULT_LIMIT),
        )
    }

    // endregion

    /** PRD_FOOD 13.2 once more, from the other side: nothing renders a null as a zero. */
    @Test
    fun `no rendered figure of an unknown bundle contains a digit`() {
        val figures = listOf(FoodCatalogueFormat.energy(Nutrients.UNKNOWN)) +
            FoodCatalogueFormat.macros(Nutrients.UNKNOWN)

        assertTrue(figures.all { it.startsWith(FoodLabels.UNKNOWN) }, figures.toString())
        assertTrue(figures.none { it.any(Char::isDigit) }, figures.toString())
    }

    /** And the mirror: a bundle of known zeros renders zeros, digits and all. */
    @Test
    fun `a bundle of known zeros renders zeros`() {
        val zeros = Nutrients(
            energy = fr.kristenjestin.mue.domain.model.Energy.ZERO,
            protein = Macro.ZERO,
            carbs = Macro.ZERO,
            fat = Macro.ZERO,
            fibre = Macro.ZERO,
        )

        assertEquals("≈ 0 kcal", FoodCatalogueFormat.energy(zeros))
        assertEquals(
            listOf("≈ 0.0 g protein", "≈ 0.0 g carbs", "≈ 0.0 g fat", "≈ 0.0 g fibre"),
            FoodCatalogueFormat.macros(zeros),
        )
    }
}
