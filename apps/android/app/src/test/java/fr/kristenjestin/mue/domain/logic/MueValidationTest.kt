package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.testing.LocaleRule
import java.time.LocalDate
import java.util.Locale
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

class MueValidationTest {

    /** Validation must not drift with the phone's language (PRD BR-010). */
    @get:Rule
    val localeRule = LocaleRule(Locale.FRANCE)

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    @Test
    fun `the error messages match the PRD word for word`() {
        assertEquals("Weight must be between 30.0 and 250.0 kg", MueValidation.WEIGHT_ERROR)
        assertEquals("Height must be between 120 and 230 cm", MueValidation.HEIGHT_ERROR)
        assertEquals("Enter a valid date of birth", MueValidation.BIRTH_DATE_ERROR)
    }

    @Test
    fun `weight accepts both bounds`() {
        assertEquals(300, MueValidation.validateWeightKg(30.0).valueOrNull?.tenthsKg)
        assertEquals(2500, MueValidation.validateWeightKg(250.0).valueOrNull?.tenthsKg)
    }

    @Test
    fun `weight below the lower bound reports the weight message`() {
        assertEquals(MueValidation.WEIGHT_ERROR, MueValidation.validateWeightKg(29.9).errorMessage)
    }

    @Test
    fun `weight above the upper bound reports the weight message`() {
        assertEquals(MueValidation.WEIGHT_ERROR, MueValidation.validateWeightKg(250.1).errorMessage)
    }

    @Test
    fun `typed weight accepts a dot separator`() {
        assertEquals(745, MueValidation.validateWeightInput("74.5").valueOrNull?.tenthsKg)
    }

    @Test
    fun `typed weight accepts a comma separator whatever the phone language is`() {
        assertEquals(745, MueValidation.validateWeightInput("74,5").valueOrNull?.tenthsKg)
    }

    @Test
    fun `typed weight tolerates surrounding whitespace`() {
        assertEquals(745, MueValidation.validateWeightInput("  74,5  ").valueOrNull?.tenthsKg)
    }

    @Test
    fun `typed weight rounds to the nearest tenth`() {
        assertEquals(745, MueValidation.validateWeightInput("74,45").valueOrNull?.tenthsKg)
        assertEquals(744, MueValidation.validateWeightInput("74,44").valueOrNull?.tenthsKg)
    }

    @Test
    fun `unparseable weight is rejected with the weight message`() {
        assertEquals(MueValidation.WEIGHT_ERROR, MueValidation.validateWeightInput("").errorMessage)
        assertEquals(MueValidation.WEIGHT_ERROR, MueValidation.validateWeightInput("abc").errorMessage)
        assertEquals(MueValidation.WEIGHT_ERROR, MueValidation.validateWeightInput("74,5,5").errorMessage)
    }

    @Test
    fun `an absent height is valid and simply means no BMI`() {
        val validated = MueValidation.validateHeightCm(null)
        assertTrue(validated.isValid)
        assertNull(validated.valueOrNull)
    }

    @Test
    fun `height accepts both bounds`() {
        assertEquals(120, MueValidation.validateHeightCm(120).valueOrNull)
        assertEquals(230, MueValidation.validateHeightCm(230).valueOrNull)
        assertEquals(UserProfile.HEIGHT_RANGE_CM, 120..230)
    }

    @Test
    fun `height outside the range reports the height message`() {
        assertEquals(MueValidation.HEIGHT_ERROR, MueValidation.validateHeightCm(119).errorMessage)
        assertEquals(MueValidation.HEIGHT_ERROR, MueValidation.validateHeightCm(231).errorMessage)
    }

    @Test
    fun `a blank height field clears the height`() {
        assertTrue(MueValidation.validateHeightInput("").isValid)
        assertNull(MueValidation.validateHeightInput("   ").valueOrNull)
    }

    @Test
    fun `a typed height is parsed and range checked`() {
        assertEquals(178, MueValidation.validateHeightInput(" 178 ").valueOrNull)
        assertEquals(MueValidation.HEIGHT_ERROR, MueValidation.validateHeightInput("300").errorMessage)
        assertEquals(MueValidation.HEIGHT_ERROR, MueValidation.validateHeightInput("1,78").errorMessage)
    }

    @Test
    fun `an absent birth date is valid`() {
        assertTrue(MueValidation.validateBirthDate(null, today).isValid)
    }

    @Test
    fun `a birth date of today is valid`() {
        assertEquals(today, MueValidation.validateBirthDate(today, today).valueOrNull)
    }

    @Test
    fun `a birth date in the future is rejected`() {
        assertEquals(
            MueValidation.BIRTH_DATE_ERROR,
            MueValidation.validateBirthDate(today.plusDays(1), today).errorMessage,
        )
    }

    @Test
    fun `exactly one hundred and twenty years ago is still valid`() {
        val earliest = today.minusYears(120)
        assertEquals(earliest, MueValidation.validateBirthDate(earliest, today).valueOrNull)
    }

    @Test
    fun `one day beyond one hundred and twenty years is rejected`() {
        assertEquals(
            MueValidation.BIRTH_DATE_ERROR,
            MueValidation.validateBirthDate(today.minusYears(120).minusDays(1), today).errorMessage,
        )
    }

    @Test
    fun `a display name is trimmed, capped and collapsed to null when blank`() {
        assertNull(MueValidation.normalizeDisplayName(null))
        assertNull(MueValidation.normalizeDisplayName(""))
        assertNull(MueValidation.normalizeDisplayName("   "))
        assertEquals("Kristen", MueValidation.normalizeDisplayName("  Kristen  "))
        assertEquals(
            UserProfile.MAX_DISPLAY_NAME_LENGTH,
            MueValidation.normalizeDisplayName("x".repeat(60))?.length,
        )
    }

    @Test
    fun `a measurement may be dated today but never tomorrow`() {
        assertTrue(MueValidation.isMeasurementDateAllowed(today, today))
        assertTrue(MueValidation.isMeasurementDateAllowed(today.minusYears(3), today))
        assertFalse(MueValidation.isMeasurementDateAllowed(today.plusDays(1), today))
    }

    @Test
    fun `a complete valid form produces a normalized profile`() {
        val result = MueValidation.validateProfile(
            displayName = "  Kristen  ",
            heightInput = "178",
            birthDate = LocalDate.of(1990, 5, 4),
            today = today,
        )
        assertIs<ProfileValidation.Valid>(result)
        assertEquals(UserProfile("Kristen", 178, LocalDate.of(1990, 5, 4)), result.profile)
    }

    @Test
    fun `an entirely empty form is valid and yields an empty profile`() {
        val result = MueValidation.validateProfile("", "", null, today)
        assertIs<ProfileValidation.Valid>(result)
        assertEquals(UserProfile.EMPTY, result.profile)
    }

    @Test
    fun `both field errors are reported together`() {
        val result = MueValidation.validateProfile("Kristen", "300", today.plusDays(1), today)
        assertIs<ProfileValidation.Invalid>(result)
        assertEquals(MueValidation.HEIGHT_ERROR, result.heightError)
        assertEquals(MueValidation.BIRTH_DATE_ERROR, result.birthDateError)
    }

    @Test
    fun `an invalid height alone does not blame the birth date`() {
        val result = MueValidation.validateProfile(null, "10", LocalDate.of(1990, 5, 4), today)
        assertIs<ProfileValidation.Invalid>(result)
        assertEquals(MueValidation.HEIGHT_ERROR, result.heightError)
        assertNull(result.birthDateError)
    }

    @Test
    fun `mapping a validated value keeps the invalid branch untouched`() {
        val valid: Validated<Int?> = MueValidation.validateHeightCm(180)
        assertEquals(1.8, valid.map { it!! / 100.0 }.valueOrNull)
        assertEquals(
            MueValidation.HEIGHT_ERROR,
            MueValidation.validateHeightCm(10).map { it }.errorMessage,
        )
    }
}
