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

    private val weight = Weight.ofTenthsClamped(745)
    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun `an english phone shows a decimal point`() {
        assertEquals("74.5", EntryFormat.weight(weight, Locale.UK))
    }

    @Test
    fun `a french phone shows a decimal comma`() {
        assertEquals("74,5", EntryFormat.weight(weight, Locale.FRANCE))
    }

    @Test
    fun `the value always carries exactly one decimal`() {
        assertEquals("70.0", EntryFormat.weight(Weight.DEFAULT, Locale.UK))
        assertEquals("30.0", EntryFormat.weight(Weight.ofTenthsClamped(300), Locale.UK))
        assertEquals("250.0", EntryFormat.weight(Weight.ofTenthsClamped(2500), Locale.UK))
    }

    @Test
    fun `TalkBack hears kilograms spelled out`() {
        assertEquals("74.5 kilograms", EntryFormat.spokenWeight(weight, Locale.UK))
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
