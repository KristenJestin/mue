package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BmiCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 23)

    /** A 20-year-old on [today], so categories are allowed. */
    private val adultBirthDate: LocalDate = LocalDate.of(2006, 8, 23)

    @Test
    fun `divides weight by height squared and keeps one decimal`() {
        // 70.0 / 1.75^2 = 22.857...
        val bmi = calculate(kilograms = 70.0, heightCm = 175)
        assertEquals(22.9, bmi.valueOrNull)
    }

    @Test
    fun `rounds down when the second decimal is below five`() {
        // 80.0 / 1.80^2 = 24.691...
        assertEquals(24.7, calculate(kilograms = 80.0, heightCm = 180).valueOrNull)
    }

    @Test
    fun `is unavailable without a height`() {
        assertIs<Bmi.Unavailable>(
            BmiCalculator.calculate(Weight.ofKilogramsOrNull(70.0), null, adultBirthDate, today)
        )
    }

    @Test
    fun `is unavailable without a weight`() {
        assertIs<Bmi.Unavailable>(BmiCalculator.calculate(null, 175, adultBirthDate, today))
    }

    @Test
    fun `is unavailable when the stored height is outside the accepted range`() {
        assertIs<Bmi.Unavailable>(
            BmiCalculator.calculate(Weight.ofKilogramsOrNull(70.0), 119, adultBirthDate, today)
        )
        assertIs<Bmi.Unavailable>(
            BmiCalculator.calculate(Weight.ofKilogramsOrNull(70.0), 231, adultBirthDate, today)
        )
    }

    @Test
    fun `shows a value with no category when the birth date is unknown`() {
        val bmi = BmiCalculator.calculate(Weight.ofKilogramsOrNull(70.0), 175, null, today)
        assertIs<Bmi.ValueOnly>(bmi)
        assertEquals(22.9, bmi.value)
        assertNull(bmi.categoryOrNull)
    }

    @Test
    fun `shows a value with no category one day before the twentieth birthday`() {
        val bmi = BmiCalculator.calculate(
            weight = Weight.ofKilogramsOrNull(70.0),
            heightCm = 175,
            birthDate = adultBirthDate.plusDays(1),
            today = today,
        )
        assertIs<Bmi.ValueOnly>(bmi)
    }

    @Test
    fun `classifies exactly on the twentieth birthday`() {
        val bmi = BmiCalculator.calculate(
            weight = Weight.ofKilogramsOrNull(70.0),
            heightCm = 175,
            birthDate = adultBirthDate,
            today = today,
        )
        assertIs<Bmi.Classified>(bmi)
    }

    @Test
    fun `a birth date in the future never unlocks a category`() {
        val bmi = BmiCalculator.calculate(
            weight = Weight.ofKilogramsOrNull(70.0),
            heightCm = 175,
            birthDate = today.plusDays(1),
            today = today,
        )
        assertIs<Bmi.ValueOnly>(bmi)
    }

    @Test
    fun `a leap day birth becomes adult on the twenty ninth of february`() {
        val leapBirth = LocalDate.of(2004, 2, 29)
        assertFalse(BmiCalculator.isAdultOn(leapBirth, LocalDate.of(2024, 2, 28)))
        assertTrue(BmiCalculator.isAdultOn(leapBirth, LocalDate.of(2024, 2, 29)))
    }

    @Test
    fun `an unknown birth date is never treated as adult`() {
        assertFalse(BmiCalculator.isAdultOn(null, today))
    }

    // Height 200 cm squares to exactly 4, so each weight lands on an exact boundary.

    @Test
    fun `below 18 point 5 is underweight`() {
        assertCategory(kilograms = 73.6, expectedValue = 18.4, expected = BmiCategory.UNDERWEIGHT)
    }

    @Test
    fun `exactly 18 point 5 is healthy weight`() {
        assertCategory(kilograms = 74.0, expectedValue = 18.5, expected = BmiCategory.HEALTHY_WEIGHT)
    }

    @Test
    fun `exactly 24 point 9 is healthy weight`() {
        assertCategory(kilograms = 99.6, expectedValue = 24.9, expected = BmiCategory.HEALTHY_WEIGHT)
    }

    @Test
    fun `exactly 25 point 0 is overweight`() {
        assertCategory(kilograms = 100.0, expectedValue = 25.0, expected = BmiCategory.OVERWEIGHT)
    }

    @Test
    fun `exactly 29 point 9 is overweight`() {
        assertCategory(kilograms = 119.6, expectedValue = 29.9, expected = BmiCategory.OVERWEIGHT)
    }

    @Test
    fun `exactly 30 point 0 is obesity`() {
        assertCategory(kilograms = 120.0, expectedValue = 30.0, expected = BmiCategory.OBESITY)
    }

    @Test
    fun `well above thirty is obesity`() {
        assertCategory(kilograms = 200.0, expectedValue = 50.0, expected = BmiCategory.OBESITY)
    }

    @Test
    fun `classification runs on the displayed value so the band gaps disappear`() {
        assertEquals(BmiCategory.HEALTHY_WEIGHT, BmiCalculator.categoryOf(24.9))
        assertEquals(BmiCategory.OVERWEIGHT, BmiCalculator.categoryOf(25.0))
        assertEquals(BmiCategory.OVERWEIGHT, BmiCalculator.categoryOf(29.9))
        assertEquals(BmiCategory.OBESITY, BmiCalculator.categoryOf(30.0))
        assertEquals(BmiCategory.UNDERWEIGHT, BmiCalculator.categoryOf(18.4))
        assertEquals(BmiCategory.HEALTHY_WEIGHT, BmiCalculator.categoryOf(18.5))
    }

    @Test
    fun `category labels match the product copy`() {
        assertEquals("Underweight", BmiCategory.UNDERWEIGHT.label)
        assertEquals("Healthy weight", BmiCategory.HEALTHY_WEIGHT.label)
        assertEquals("Overweight", BmiCategory.OVERWEIGHT.label)
        assertEquals("Obesity", BmiCategory.OBESITY.label)
    }

    @Test
    fun `the profile overload reads height and birth date from the profile`() {
        val profile = UserProfile(heightCm = 200, birthDate = adultBirthDate)
        val bmi = BmiCalculator.calculate(Weight.ofKilogramsOrNull(100.0), profile, today)
        assertEquals(Bmi.Classified(25.0, BmiCategory.OVERWEIGHT), bmi)
    }

    @Test
    fun `an unavailable result exposes neither value nor category`() {
        assertNull(Bmi.Unavailable.valueOrNull)
        assertNull(Bmi.Unavailable.categoryOrNull)
    }

    @Test
    fun `a disclaimer is always available to display next to the value`() {
        assertTrue(BmiCalculator.DISCLAIMER.isNotBlank())
    }

    private fun calculate(kilograms: Double, heightCm: Int): Bmi =
        BmiCalculator.calculate(
            weight = Weight.ofKilogramsOrNull(kilograms),
            heightCm = heightCm,
            birthDate = adultBirthDate,
            today = today,
        )

    private fun assertCategory(kilograms: Double, expectedValue: Double, expected: BmiCategory) {
        val bmi = calculate(kilograms = kilograms, heightCm = 200)
        assertIs<Bmi.Classified>(bmi)
        assertEquals(expectedValue, bmi.value)
        assertEquals(expected, bmi.category)
    }
}
