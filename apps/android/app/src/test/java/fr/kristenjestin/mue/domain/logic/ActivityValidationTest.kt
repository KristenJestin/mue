package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.equipmentOf
import fr.kristenjestin.mue.domain.model.loadOf
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDurationValidationTest {

    @Test
    fun `the shortest and the longest session of PRD FR-ACTIVITY-005 are accepted`() {
        assertEquals(60, ActivityValidation.validateDuration("0", "1").valueOrNull?.seconds)
        assertEquals(359_940, ActivityValidation.validateDuration("99", "59").valueOrNull?.seconds)
    }

    @Test
    fun `a session shorter than a minute or longer than the ceiling is refused`() {
        assertEquals(
            ActivityValidation.DURATION_ERROR,
            ActivityValidation.validateDuration("0", "0").errorMessage,
        )
        assertEquals(
            ActivityValidation.DURATION_ERROR,
            ActivityValidation.validateDuration("100", "0").errorMessage,
        )
        assertEquals(
            ActivityValidation.DURATION_ERROR,
            ActivityValidation.validateDuration("", "").errorMessage,
        )
    }

    @Test
    fun `a minutes box is a minutes box and never overflows into hours`() {
        assertEquals(
            ActivityValidation.DURATION_ERROR,
            ActivityValidation.validateDuration("0", "60").errorMessage,
        )
    }

    @Test
    fun `one part left blank counts as zero`() {
        assertEquals(3_600, ActivityValidation.validateDuration("1", "").valueOrNull?.seconds)
        assertEquals(900, ActivityValidation.validateDuration("", "15").valueOrNull?.seconds)
    }

    @Test
    fun `anything that is not a whole number of hours or minutes is refused`() {
        assertFalse(ActivityValidation.validateDuration("1.5", "0").isValid)
        assertFalse(ActivityValidation.validateDuration("0", "-5").isValid)
        assertFalse(ActivityValidation.validateDuration("many", "0").isValid)
    }
}

class TimedDurationValidationTest {

    @Test
    fun `a session measured by the timer may be shorter than the manual floor`() {
        assertEquals(
            40,
            ActivityValidation.validateTimedDuration("0", "0", "40").valueOrNull?.seconds,
        )
        assertEquals(1, ActivityValidation.validateTimedDuration(0, 0, 1).valueOrNull?.seconds)
        assertEquals(
            ActivityValidation.DURATION_ERROR,
            ActivityValidation.validateDuration("0", "0").errorMessage,
        )
    }

    @Test
    fun `the ceiling and the zero of PRD FR-TIMER-006 are refused with the timed message`() {
        assertEquals(
            359_940,
            ActivityValidation.validateTimedDuration("99", "59", "0").valueOrNull?.seconds,
        )
        assertEquals(
            ActivityValidation.TIMED_DURATION_ERROR,
            ActivityValidation.validateTimedDuration("99", "59", "1").errorMessage,
        )
        assertEquals(
            ActivityValidation.TIMED_DURATION_ERROR,
            ActivityValidation.validateTimedDuration("0", "0", "0").errorMessage,
        )
        assertEquals(
            ActivityValidation.TIMED_DURATION_ERROR,
            ActivityValidation.validateTimedDuration("", "", "").errorMessage,
        )
    }

    @Test
    fun `a minutes or a seconds box never overflows into the box above it`() {
        assertFalse(ActivityValidation.validateTimedDuration("0", "60", "0").isValid)
        assertFalse(ActivityValidation.validateTimedDuration("0", "0", "60").isValid)
        assertEquals(
            2_538,
            ActivityValidation.validateTimedDuration("", "42", "18").valueOrNull?.seconds,
        )
    }

    @Test
    fun `anything that is not a whole number of hours, minutes or seconds is refused`() {
        assertFalse(ActivityValidation.validateTimedDuration("1.5", "0", "0").isValid)
        assertFalse(ActivityValidation.validateTimedDuration("0", "0", "-5").isValid)
        assertFalse(ActivityValidation.validateTimedDuration("0", "half", "0").isValid)
    }
}

class ActivityFieldValidationTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 24)

    @Test
    fun `today is allowed and tomorrow is not`() {
        assertTrue(ActivityValidation.validateStartedOn(today, today).isValid)
        assertTrue(ActivityValidation.validateStartedOn(today.minusDays(400), today).isValid)
        assertEquals(
            ActivityValidation.DATE_ERROR,
            ActivityValidation.validateStartedOn(today.plusDays(1), today).errorMessage,
        )
    }

    @Test
    fun `a start time is kept to the minute PRD 16-3 stores`() {
        assertEquals(
            LocalTime.of(18, 45),
            ActivityValidation.normalizeStartTime(LocalTime.of(18, 45, 31, 900)),
        )
        assertNull(ActivityValidation.normalizeStartTime(null))
    }

    @Test
    fun `the effort scale stops at both ends`() {
        assertTrue(ActivityValidation.validatePerceivedEffort(null).isValid)
        assertNull(ActivityValidation.validatePerceivedEffort(null).valueOrNull)
        assertEquals(1, ActivityValidation.validatePerceivedEffort(1).valueOrNull?.value)
        assertEquals(10, ActivityValidation.validatePerceivedEffort(10).valueOrNull?.value)
        assertEquals(
            ActivityValidation.EFFORT_ERROR,
            ActivityValidation.validatePerceivedEffort(0).errorMessage,
        )
        assertEquals(
            ActivityValidation.EFFORT_ERROR,
            ActivityValidation.validatePerceivedEffort(11).errorMessage,
        )
    }

    @Test
    fun `a blank note is no note and a long one is cut rather than refused`() {
        assertNull(ActivityValidation.normalizeNotes(null))
        assertNull(ActivityValidation.normalizeNotes("   "))
        assertEquals("Felt good", ActivityValidation.normalizeNotes("  Felt good  "))
        assertEquals(500, ActivityValidation.normalizeNotes("x".repeat(600))?.length)
    }

    @Test
    fun `a custom activity name runs from one to sixty characters`() {
        assertEquals("Padel", ActivityValidation.validateCustomMovementName("  Padel ").valueOrNull)
        assertTrue(ActivityValidation.validateCustomMovementName("x".repeat(60)).isValid)
        assertFalse(ActivityValidation.validateCustomMovementName("x".repeat(61)).isValid)
        assertEquals(
            ActivityValidation.MOVEMENT_NAME_ERROR,
            ActivityValidation.validateCustomMovementName("   ").errorMessage,
        )
    }

    @Test
    fun `a custom equipment name runs from one to forty characters`() {
        assertTrue(ActivityValidation.validateCustomEquipmentName("x".repeat(40)).isValid)
        assertFalse(ActivityValidation.validateCustomEquipmentName("x".repeat(41)).isValid)
        assertEquals(
            ActivityValidation.EQUIPMENT_NAME_ERROR,
            ActivityValidation.validateCustomEquipmentName("").errorMessage,
        )
    }

    @Test
    fun `an exercise name is required and capped`() {
        assertEquals("Bench press", ActivityValidation.validateExerciseName(" Bench press ").valueOrNull)
        assertFalse(ActivityValidation.validateExerciseName("").isValid)
        assertFalse(ActivityValidation.validateExerciseName("x".repeat(61)).isValid)
    }

    @Test
    fun `the same equipment is never added twice to one session, whatever its case`() {
        val kept = ActivityValidation.distinctEquipment(
            listOf(
                equipmentOf(EquipmentType.TREADMILL, position = 0),
                equipmentOf(EquipmentType.TREADMILL, position = 1),
                equipmentOf(EquipmentType.OTHER, "Garden rower", position = 2),
                equipmentOf(EquipmentType.OTHER, " garden ROWER ", position = 3),
                equipmentOf(EquipmentType.OTHER, "Sled", position = 4),
            )
        )
        assertEquals(3, kept.size)
        assertEquals(listOf(0, 1, 2), kept.map { it.position })
        assertEquals("Garden rower", kept[1].displayName)
    }

    @Test
    fun `a free name belongs to the other type alone`() {
        assertTrue(ActivityValidation.isNamingConsistent(equipmentOf(EquipmentType.OTHER, "Sled")))
        assertFalse(ActivityValidation.isNamingConsistent(equipmentOf(EquipmentType.OTHER)))
        assertTrue(ActivityValidation.isNamingConsistent(equipmentOf(EquipmentType.TREADMILL)))
        assertFalse(
            ActivityValidation.isNamingConsistent(equipmentOf(EquipmentType.TREADMILL, "Mine")),
        )
    }
}

class MetricInputTest {

    @Test
    fun `an empty optional field is valid and means nothing was measured`() {
        MetricKind.EDITABLE.forEach { kind ->
            val validated = ActivityValidation.validateMetric(kind, "")
            assertTrue(validated.isValid, "$kind should accept a blank field")
            assertNull(validated.valueOrNull)
            assertNull(ActivityValidation.validateMetric(kind, "   ").valueOrNull)
        }
    }

    @Test
    fun `a measurement is stored in the canonical unit of PRD 8-3`() {
        assertEquals(4_200, ActivityValidation.validateMetric(MetricKind.DISTANCE, "4.2").valueOrNull)
        assertEquals(
            560,
            ActivityValidation.validateMetric(MetricKind.REPORTED_SPEED, "5.6").valueOrNull,
        )
        assertEquals(
            2_450,
            ActivityValidation.validateMetric(MetricKind.AVERAGE_SPEED, "24.5").valueOrNull,
        )
        assertEquals(
            280,
            ActivityValidation.validateMetric(MetricKind.ESTIMATED_ENERGY, "280").valueOrNull,
        )
        assertEquals(25, ActivityValidation.validateMetric(MetricKind.INCLINE, "2.5").valueOrNull)
    }

    @Test
    fun `a negative measurement is always refused`() {
        assertEquals(
            ActivityValidation.NUMBER_ERROR,
            ActivityValidation.validateMetric(MetricKind.DISTANCE, "-1").errorMessage,
        )
        assertEquals(
            ActivityValidation.NUMBER_ERROR,
            ActivityValidation.validateMetric(MetricKind.INCLINE, "-0.5").errorMessage,
        )
    }

    @Test
    fun `text and absurd numbers never become a stored value`() {
        assertFalse(ActivityValidation.validateMetric(MetricKind.DISTANCE, "far").isValid)
        assertFalse(ActivityValidation.validateMetric(MetricKind.DISTANCE, "1e30").isValid)
        assertFalse(ActivityValidation.validateMetric(MetricKind.ESTIMATED_ENERGY, "4,2,1").isValid)
    }

    @Test
    fun `a pace is typed as minutes and seconds and stored per kilometre`() {
        assertEquals(430, ActivityValidation.validatePace("7", "10").valueOrNull)
        assertEquals(430, ActivityValidation.validateMetric(MetricKind.AVERAGE_PACE, "7:10").valueOrNull)
        assertEquals(420, ActivityValidation.validateMetric(MetricKind.AVERAGE_PACE, "7").valueOrNull)
        assertEquals(30, ActivityValidation.validatePace("", "30").valueOrNull)
    }

    @Test
    fun `a pace with no minutes and no seconds is simply not measured`() {
        assertTrue(ActivityValidation.validatePace("", "").isValid)
        assertNull(ActivityValidation.validatePace("", "").valueOrNull)
        assertNull(ActivityValidation.validateMetric(MetricKind.AVERAGE_PACE, "").valueOrNull)
    }

    @Test
    fun `a pace refuses sixty seconds, a negative minute and a third part`() {
        assertEquals(
            ActivityValidation.PACE_ERROR,
            ActivityValidation.validatePace("7", "60").errorMessage,
        )
        assertFalse(ActivityValidation.validatePace("-1", "10").isValid)
        assertFalse(ActivityValidation.validateMetric(MetricKind.AVERAGE_PACE, "1:2:3").isValid)
        assertFalse(ActivityValidation.validatePace("0", "0").isValid)
        assertFalse(ActivityValidation.validatePace("100", "0").isValid)
    }
}

class StrengthInputTest {

    @Test
    fun `a load is typed in kilograms and stored in grams`() {
        assertEquals(loadOf(60.0), ActivityValidation.validateLoad("60").valueOrNull)
        assertEquals(loadOf(62.5), ActivityValidation.validateLoad("62.5").valueOrNull)
        assertEquals(62_570, ActivityValidation.validateLoad("62.567").valueOrNull?.grams)
    }

    @Test
    fun `an empty load is valid because a set may carry none at all`() {
        assertTrue(ActivityValidation.validateLoad("").isValid)
        assertNull(ActivityValidation.validateLoad("  ").valueOrNull)
    }

    @Test
    fun `a load of zero or below is refused rather than stored`() {
        assertEquals(ActivityValidation.LOAD_ERROR, ActivityValidation.validateLoad("0").errorMessage)
        assertFalse(ActivityValidation.validateLoad("-5").isValid)
        assertFalse(ActivityValidation.validateLoad("0.004").isValid)
        assertFalse(ActivityValidation.validateLoad("2000").isValid)
    }

    @Test
    fun `reps are whole and strictly positive when present`() {
        assertEquals(8, ActivityValidation.validateRepetitions("8").valueOrNull)
        assertTrue(ActivityValidation.validateRepetitions("").isValid)
        assertNull(ActivityValidation.validateRepetitions("").valueOrNull)
        assertEquals(
            ActivityValidation.REPETITIONS_ERROR,
            ActivityValidation.validateRepetitions("0").errorMessage,
        )
        assertFalse(ActivityValidation.validateRepetitions("-3").isValid)
        assertFalse(ActivityValidation.validateRepetitions("8.5").isValid)
        assertFalse(ActivityValidation.validateRepetitions("1000").isValid)
    }

    @Test
    fun `a set duration is read as seconds or as minutes and seconds`() {
        assertEquals(90, ActivityValidation.validateSetDuration("90").valueOrNull?.seconds)
        assertEquals(90, ActivityValidation.validateSetDuration("1:30").valueOrNull?.seconds)
        assertEquals(45, ActivityValidation.validateSetDuration("45").valueOrNull?.seconds)
        assertEquals(
            ActivityDuration.SECONDS_PER_MINUTE,
            ActivityValidation.validateSetDuration("1:00").valueOrNull?.seconds,
        )
    }

    @Test
    fun `an empty set duration is valid and a zero one is not`() {
        assertTrue(ActivityValidation.validateSetDuration("").isValid)
        assertNull(ActivityValidation.validateSetDuration("").valueOrNull)
        assertEquals(
            ActivityValidation.SET_DURATION_ERROR,
            ActivityValidation.validateSetDuration("0").errorMessage,
        )
        assertFalse(ActivityValidation.validateSetDuration("-30").isValid)
        assertFalse(ActivityValidation.validateSetDuration("1:60").isValid)
        assertFalse(ActivityValidation.validateSetDuration("a while").isValid)
    }
}
