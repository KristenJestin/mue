package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.equipmentOf
import fr.kristenjestin.mue.testing.LocaleRule
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * PRD 12: a number is read the same way whatever the phone's language, so both separators are
 * accepted everywhere. Running the same assertions under a locale whose own separator is the
 * comma is the only way to prove a naive implementation would have broken.
 */
class ActivityValidationFrenchInputTest {

    @get:Rule
    val locale = LocaleRule(Locale.FRANCE)

    @Test
    fun `a comma and a point mean the same distance`() {
        assertEquals(4_200, ActivityValidation.validateMetric(MetricKind.DISTANCE, "4,2").valueOrNull)
        assertEquals(4_200, ActivityValidation.validateMetric(MetricKind.DISTANCE, "4.2").valueOrNull)
    }

    @Test
    fun `a comma and a point mean the same load`() {
        assertEquals(62_500, ActivityValidation.validateLoad("62,5").valueOrNull?.grams)
        assertEquals(62_500, ActivityValidation.validateLoad("62.5").valueOrNull?.grams)
    }

    @Test
    fun `a half-typed number keeps the value already there`() {
        assertEquals(7.0, ActivityValidation.parseDecimal("7,"))
        assertEquals(7.0, ActivityValidation.parseDecimal("7."))
        assertEquals(2_500, ActivityValidation.validateMetric(MetricKind.INCLINE, "250,").valueOrNull)
    }
}

class ActivityValidationEnglishInputTest {

    @get:Rule
    val locale = LocaleRule(Locale.US)

    @Test
    fun `a comma is still a decimal separator on an English phone`() {
        assertEquals(4_200, ActivityValidation.validateMetric(MetricKind.DISTANCE, "4,2").valueOrNull)
        assertEquals(62_500, ActivityValidation.validateLoad("62,5").valueOrNull?.grams)
        assertEquals(560, ActivityValidation.validateMetric(MetricKind.REPORTED_SPEED, "5,6").valueOrNull)
    }
}

/**
 * Turkish is the case that breaks a careless fold: `"I".lowercase()` yields a dotless `ı` there,
 * and the same equipment name would then fold two ways on two phones.
 */
class ActivityValidationTurkishFoldTest {

    @get:Rule
    val locale = LocaleRule(Locale.forLanguageTag("tr-TR"))

    @Test
    fun `an equipment name folds the same way as it would anywhere else`() {
        val equipment = equipmentOf(EquipmentType.OTHER, "INCLINE TRAINER")
        assertEquals("incline trainer", equipment.customNameFolded)
    }

    @Test
    fun `an exercise name folds the same way as it would anywhere else`() {
        assertEquals("incline press", ExerciseDefinition.fold("INCLINE PRESS"))
    }
}
