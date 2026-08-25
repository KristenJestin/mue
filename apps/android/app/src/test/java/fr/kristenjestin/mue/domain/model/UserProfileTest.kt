package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserProfileTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Test
    fun `an empty profile carries nothing`() {
        assertEquals(UserProfile(null, null, null), UserProfile.EMPTY)
        assertNull(UserProfile.EMPTY.heightMetres)
        assertNull(UserProfile.EMPTY.ageOn(today))
    }

    @Test
    fun `height converts to metres`() {
        assertEquals(1.78, UserProfile(heightCm = 178).heightMetres)
        assertEquals(2.0, UserProfile(heightCm = 200).heightMetres)
    }

    @Test
    fun `age counts whole years only`() {
        val birthDate = LocalDate.of(2006, 8, 23)
        assertEquals(20, UserProfile(birthDate = birthDate).ageOn(today))
        assertEquals(19, UserProfile(birthDate = birthDate.plusDays(1)).ageOn(today))
    }

    @Test
    fun `the PRD bounds are exposed on the model`() {
        assertEquals(120..230, UserProfile.HEIGHT_RANGE_CM)
        assertEquals(40, UserProfile.MAX_DISPLAY_NAME_LENGTH)
        assertEquals(120L, UserProfile.MAX_AGE_YEARS)
        assertEquals(20L, UserProfile.ADULT_AGE_YEARS)
    }
}

class UserPreferencesTest {

    @Test
    fun `haptics are enabled by default`() {
        assertTrue(UserPreferences.DEFAULT.hapticsEnabled)
        assertTrue(UserPreferences().hapticsEnabled)
    }

    @Test
    fun `haptics can be turned off`() {
        assertEquals(UserPreferences(false), UserPreferences.DEFAULT.copy(hapticsEnabled = false))
    }
}
