package fr.kristenjestin.mue.ui.entry

import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.testing.LocaleRule
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PRD BR-010: what the screen shows follows the phone's language. Nothing here is allowed to
 * leak into the CSV, which has its own locale-proof writer.
 */
class EntryFormatTest {

    private val weight = Weight.ofHundredthsClamped(7_405)
    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun `an english phone shows a decimal point`() {
        assertEquals("74.05", EntryFormat.weight(weight, Locale.UK))
    }

    @Test
    fun `a french phone shows a decimal comma`() {
        assertEquals("74,05", EntryFormat.weight(weight, Locale.FRANCE))
    }

    @Test
    fun `the value always carries exactly two decimals`() {
        assertEquals("70.00", EntryFormat.weight(Weight.DEFAULT, Locale.UK))
        assertEquals("30.00", EntryFormat.weight(Weight.ofHundredthsClamped(3_000), Locale.UK))
        assertEquals("250.00", EntryFormat.weight(Weight.ofHundredthsClamped(25_000), Locale.UK))
        assertEquals("74.50", EntryFormat.weight(Weight.ofHundredthsClamped(7_450), Locale.UK))
    }

    /** The half-step is the whole point of the second decimal; it must not round away. */
    @Test
    fun `a value between two graduations reads as itself`() {
        assertEquals("74.05", EntryFormat.weight(Weight.ofHundredthsClamped(7_405), Locale.UK))
        assertEquals("74.15", EntryFormat.weight(Weight.ofHundredthsClamped(7_415), Locale.UK))
        assertEquals("74.95", EntryFormat.weight(Weight.ofHundredthsClamped(7_495), Locale.UK))
    }

    @Test
    fun `TalkBack hears kilograms spelled out`() {
        assertEquals("74.05 kilograms", EntryFormat.spokenWeight(weight, Locale.UK))
    }

    @Test
    fun `the header says Today only on today`() {
        assertEquals("Today", EntryFormat.headerDate(today, today, Locale.UK))
        assertNotEquals("Today", EntryFormat.headerDate(today.minusDays(1), today, Locale.UK))
    }

    /** The exact pattern belongs to the JDK's locale data; only the language must follow. */
    @Test
    fun `the date row follows the phone's language`() {
        val english = EntryFormat.date(today, Locale.UK)
        assertTrue(english.contains("August") && english.contains("2026"), english)
        val french = EntryFormat.date(today, Locale.FRANCE)
        assertTrue(french.contains("août") && french.contains("2026"), french)
    }

    @Test
    fun `the default locale is what the screen actually uses`() {
        assertEquals(EntryFormat.weight(weight, Locale.getDefault()), EntryFormat.weight(weight))
    }

    @get:Rule
    val locale = LocaleRule(Locale.FRANCE)
}
