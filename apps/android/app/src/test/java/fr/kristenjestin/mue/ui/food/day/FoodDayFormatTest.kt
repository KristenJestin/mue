package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 24)

/**
 * The words the `Day` screen puts around the figures [FoodLabels] has already rendered.
 *
 * The locale is passed explicitly everywhere, which is what makes these rules provable without
 * an emulator and what keeps the module's *labels* English while its dates follow the phone
 * (PRD 12).
 */
class FoodDayFormatTest {

    // region the date (PRD_FOOD 10.1)

    @Test
    fun `today and yesterday are named, and the rest of the week keeps its weekday`() {
        assertEquals(FoodDayFormat.TODAY, FoodDayFormat.dayLabel(TODAY, TODAY, Locale.UK))
        assertEquals(
            FoodDayFormat.YESTERDAY,
            FoodDayFormat.dayLabel(TODAY.minusDays(1), TODAY, Locale.UK),
        )
        assertEquals("Thursday", FoodDayFormat.dayLabel(TODAY.minusDays(4), TODAY, Locale.UK))
    }

    /** Past the week, a weekday would name four different days; the date does not. */
    @Test
    fun `older than a week, the day is named by its date`() {
        val old = TODAY.minusDays(40)

        assertEquals(FoodDayFormat.date(old, Locale.UK), FoodDayFormat.dayLabel(old, TODAY, Locale.UK))
    }

    /**
     * `Today` alone never says which day a meal is about to be written to, so what is heard
     * keeps the word and the date behind it.
     */
    @Test
    fun `the announcement of a day always carries the date itself`() {
        val spoken = FoodDayFormat.dayDescription(TODAY, TODAY, Locale.UK)

        assertTrue(spoken.startsWith(FoodDayFormat.TODAY), spoken)
        assertTrue(spoken.contains("2026"), spoken)
        assertTrue(spoken.contains("August"), spoken)
    }

    /**
     * A day already named by its date, or by its weekday, is not announced twice.
     *
     * Only `Today` and `Yesterday` say nothing about which day they are, so only those two get
     * the date read out after them.
     */
    @Test
    fun `a day named by its date is not announced twice`() {
        val old = TODAY.minusDays(400)
        val thursday = TODAY.minusDays(4)

        assertEquals(
            FoodDayFormat.fullDate(old, Locale.UK),
            FoodDayFormat.dayDescription(old, TODAY, Locale.UK),
        )
        assertEquals(
            FoodDayFormat.fullDate(thursday, Locale.UK),
            FoodDayFormat.dayDescription(thursday, TODAY, Locale.UK),
        )
    }

    /** PRD 12: the label is English, the date is the phone's. */
    @Test
    fun `the date follows the phone's language`() {
        val french = FoodDayFormat.date(TODAY, Locale.FRANCE)

        assertTrue(french.contains("août"), french)
    }

    // endregion

    // region values (PRD_FOOD 13.2)

    @Test
    fun `a known value keeps its approximation mark and its unit`() {
        val known = Nutrients(
            energy = Energy.ofKilocaloriesOrNull(369.5),
            protein = Macro.ofGramsOrNull(29.1),
        )

        assertEquals("≈ 370 kcal", FoodDayFormat.energy(known))
        assertEquals("≈ 29.1 g protein", FoodDayFormat.protein(known))
    }

    @Test
    fun `a known zero is a zero and an unknown is a dash`() {
        assertEquals("≈ 0 kcal", FoodDayFormat.energy(Nutrients.ZERO))
        assertEquals("≈ 0.0 g protein", FoodDayFormat.protein(Nutrients.ZERO))

        assertEquals(FoodLabels.UNKNOWN, FoodDayFormat.energy(Nutrients.UNKNOWN))
        assertEquals("${FoodLabels.UNKNOWN} protein", FoodDayFormat.protein(Nutrients.UNKNOWN))
    }

    // endregion

    // region what is heard rather than read (PRD_FOOD 18)

    /**
     * `—` and `≈` are drawings. Read out they are punctuation or nothing at all, and a skipped
     * dash would make an unknown value sound exactly like a value that was not mentioned.
     */
    @Test
    fun `the two glyphs of PRD_FOOD 13 become words`() {
        assertEquals("unknown protein", FoodDayFormat.spoken("${FoodLabels.UNKNOWN} protein"))
        assertEquals("about 370 kcal", FoodDayFormat.spoken("≈ 370 kcal"))
        assertEquals("unknown", FoodDayFormat.spoken(FoodLabels.UNKNOWN))
    }

    /** A known zero still says zero: it is a fact, and it is not an absence. */
    @Test
    fun `a known zero is never heard as unknown`() {
        assertEquals("about 0 kcal", FoodDayFormat.spoken(FoodDayFormat.energy(Nutrients.ZERO)))
    }

    @Test
    fun `an announcement drops the facts a line does not carry`() {
        assertEquals("Snack, 1 entry", FoodDayFormat.sentence("Snack", "1 entry", null, ""))
    }

    // endregion

    // region servings (PRD_FOOD 12)

    @Test
    fun `one serving is singular and any other count is not`() {
        assertEquals("1 serving", FoodDayMessages.servings(Servings.ONE))
        assertEquals(
            "1.5 servings",
            FoodDayMessages.servings(requireNotNull(Servings.ofConsumedOrNull(1.5))),
        )
        assertEquals(
            "0.25 servings",
            FoodDayMessages.servings(requireNotNull(Servings.ofConsumedOrNull(0.25))),
        )
    }

    @Test
    fun `a moment says how many lines it holds, and says nothing rather than zero`() {
        assertEquals(FoodDayMessages.NOTHING_LOGGED, FoodDayMessages.entryCount(0))
        assertEquals("1 entry", FoodDayMessages.entryCount(1))
        assertEquals("3 entries", FoodDayMessages.entryCount(3))
    }

    // endregion
}
