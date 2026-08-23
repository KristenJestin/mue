package fr.kristenjestin.mue.ui.components

import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BmiReferenceScaleTest {

    @Test
    fun `each category owns one quarter of the bar`() {
        BmiCategory.entries.forEachIndexed { index, category ->
            assertEquals(index, BmiReferenceScale.bandIndexOf(category))
        }
    }

    @Test
    fun `the four short labels match the four bands`() {
        assertEquals(BmiReferenceScale.CATEGORIES.size, BmiReferenceScale.SHORT_LABELS.size)
    }

    @Test
    fun `band boundaries land on the quarter marks`() {
        assertEquals(0.25f, BmiReferenceScale.markerFraction(18.5))
        assertEquals(0.50f, BmiReferenceScale.markerFraction(25.0))
        assertEquals(0.75f, BmiReferenceScale.markerFraction(30.0))
    }

    @Test
    fun `a value just under a boundary stays in the lower band`() {
        assertTrue(BmiReferenceScale.markerFraction(24.9) < 0.5f)
        assertTrue(BmiReferenceScale.markerFraction(29.9) < 0.75f)
        assertTrue(BmiReferenceScale.markerFraction(18.4) < 0.25f)
    }

    @Test
    fun `the marker never leaves the bar`() {
        listOf(5.0, 12.0, 18.5, 22.0, 27.5, 33.0, 90.0).forEach { value ->
            val fraction = BmiReferenceScale.markerFraction(value)
            assertTrue(fraction in 0f..1f, "$value mapped to $fraction")
        }
    }

    @Test
    fun `extreme values are pinned to the ends`() {
        assertEquals(0f, BmiReferenceScale.markerFraction(9.0))
        assertEquals(1f, BmiReferenceScale.markerFraction(120.0))
    }

    @Test
    fun `the marker rises with the value`() {
        val values = listOf(14.0, 18.0, 20.0, 24.0, 26.0, 29.0, 31.0, 38.0)
        val fractions = values.map(BmiReferenceScale::markerFraction)
        assertEquals(fractions.sorted(), fractions)
    }
}

class MueBmiFormattingTest {

    @Test
    fun `the BMI always shows one decimal`() {
        assertEquals("23.0", formatBmiValue(23.0, Locale.US))
        assertEquals("29.1", formatBmiValue(29.1, Locale.US))
    }

    @Test
    fun `the BMI follows the phone's decimal separator`() {
        assertEquals("23,0", formatBmiValue(23.0, Locale.FRANCE))
    }

    /** PRD FR-PROGRESS-003: a period with no measurement reads as a dash, never as a zero. */
    @Test
    fun `no value at all reads as a dash`() {
        assertEquals(BMI_UNAVAILABLE, formatBmiValue(null, Locale.US))
        assertEquals(BMI_UNAVAILABLE, formatBmiValue(null, Locale.FRANCE))
    }

    @Test
    fun `TalkBack names the band only when the domain layer allows one`() {
        assertEquals(
            "Body mass index unavailable",
            bmiDescription(Bmi.Unavailable, BMI_UNAVAILABLE),
        )
        assertEquals("Body mass index 23.0", bmiDescription(Bmi.ValueOnly(23.0), "23.0"))
        assertEquals(
            "Body mass index 23.0, Healthy weight",
            bmiDescription(Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT), "23.0"),
        )
    }
}
