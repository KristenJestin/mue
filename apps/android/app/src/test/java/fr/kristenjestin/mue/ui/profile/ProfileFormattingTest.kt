package fr.kristenjestin.mue.ui.profile

import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals

/** PRD BR-010: what the Profile screen shows follows the phone's language, never the CSV's. */
class ProfileFormattingTest {

    @Test
    fun `the birth date follows the phone's language`() {
        val date = LocalDate.of(1992, 4, 16)
        assertEquals("April 16, 1992", formatBirthDate(date, Locale.US))
        assertEquals("16 avril 1992", formatBirthDate(date, Locale.FRANCE))
    }

    @Test
    fun `a single year is not pluralised`() {
        assertEquals("1 year", formatAge(1))
        assertEquals("0 years", formatAge(0))
        assertEquals("34 years", formatAge(34))
    }
}
