package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivitySession
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.MetricKind
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import fr.kristenjestin.mue.domain.model.SessionEquipment
import fr.kristenjestin.mue.domain.model.StrengthSet
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Every input rule of PRD 12, returning the same [Validated] the base app already uses.
 *
 * Two rules govern the whole file. Numbers are parsed accepting both `.` and `,` whatever the
 * phone's language is, which is why nothing here touches `NumberFormat` — formatting for
 * display is the opposite trip and belongs to the screens. And an empty optional field is valid
 * and means null: PRD 12 forbids turning a missing value into a zero.
 *
 * The messages are constants rather than resources, as in [MueValidation]: the app ships in
 * English only and the tests assert them character for character.
 */
object ActivityValidation {

    const val DURATION_ERROR: String = "Duration must be between 1 min and 99 h 59 min"
    const val TIMED_DURATION_ERROR: String = "Duration must be between 1 sec and 99 h 59 min"
    const val DATE_ERROR: String = "An activity cannot be in the future"
    const val EFFORT_ERROR: String = "Perceived effort must be between 1 and 10"
    const val NUMBER_ERROR: String = "Enter a positive number"
    const val PACE_ERROR: String = "Enter a pace as minutes and seconds"
    const val LOAD_ERROR: String = "Load must be between 0.01 and 1000 kg"
    const val REPETITIONS_ERROR: String = "Reps must be a whole number of 1 or more"
    const val SET_DURATION_ERROR: String = "Set duration must be 1 second or more"
    const val MOVEMENT_NAME_ERROR: String = "Enter an activity name of 1 to 60 characters"
    const val EQUIPMENT_NAME_ERROR: String = "Enter an equipment name of 1 to 40 characters"
    const val EXERCISE_NAME_ERROR: String = "Enter an exercise name of 1 to 60 characters"

    /** PRD 8.4: the free name of an equipment recorded as `other`. */
    const val MAX_EQUIPMENT_NAME_LENGTH: Int = 40

    /** A pace slower than 99:59 per kilometre is a typing accident, not a walk. */
    const val MAX_PACE_SECONDS: Int = 5_999

    /**
     * The one place a hand-typed number is read. Both separators are accepted whatever the
     * phone's language is (PRD 12), and a half-typed `7,` is worth `7` so a draft survives.
     */
    fun parseDecimal(raw: String): Double? {
        val normalized = raw.trim().replace(',', '.')
        if (normalized.isEmpty()) return null
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    fun parseInteger(raw: String): Int? = raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()

    /** PRD FR-ACTIVITY-005: from 1 minute to 99 h 59 min, the bounds of a session typed by hand. */
    fun validateDuration(hours: Int, minutes: Int): Validated<ActivityDuration> =
        ActivityDuration.ofSessionOrNull(hours, minutes)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(DURATION_ERROR)

    /** A blank part counts as zero, so an hour typed alone is a valid hour-long session. */
    fun validateDuration(hours: String, minutes: String): Validated<ActivityDuration> {
        val typedHours = hours.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(DURATION_ERROR)
        val typedMinutes = minutes.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(DURATION_ERROR)
        if (typedMinutes >= ActivityDuration.SECONDS_PER_MINUTE) {
            return Validated.Invalid(DURATION_ERROR)
        }
        return validateDuration(typedHours, typedMinutes)
    }

    /**
     * PRD FR-TIMER-006: from 1 second to 99 h 59 min, the bounds a session that was measured
     * rather than typed is held to.
     *
     * It sits beside [validateDuration] instead of replacing it. The one-minute floor above is
     * the floor of the manual form, which cannot express seconds; the timer can, so a session of
     * forty seconds is real and is saved rather than losing its measured time.
     */
    fun validateTimedDuration(hours: Int, minutes: Int, seconds: Int): Validated<ActivityDuration> =
        ActivityDuration.ofTimedSessionOrNull(hours, minutes, seconds)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(TIMED_DURATION_ERROR)

    /** A blank part counts as zero, and neither box may overflow into the one above it. */
    fun validateTimedDuration(
        hours: String,
        minutes: String,
        seconds: String,
    ): Validated<ActivityDuration> {
        val typedHours = hours.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(TIMED_DURATION_ERROR)
        val typedMinutes = minutes.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(TIMED_DURATION_ERROR)
        val typedSeconds = seconds.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(TIMED_DURATION_ERROR)
        if (typedMinutes >= ActivityDuration.SECONDS_PER_MINUTE ||
            typedSeconds >= ActivityDuration.SECONDS_PER_MINUTE
        ) {
            return Validated.Invalid(TIMED_DURATION_ERROR)
        }
        return validateTimedDuration(typedHours, typedMinutes, typedSeconds)
    }

    /** PRD FR-ACTIVITY-005: today is allowed, tomorrow is not. */
    fun validateStartedOn(date: LocalDate, today: LocalDate): Validated<LocalDate> =
        if (date.isAfter(today)) Validated.Invalid(DATE_ERROR) else Validated.Valid(date)

    /**
     * PRD 16.3 stores an optional start time as `HH:MM`, so anything finer is dropped here
     * rather than at the storage boundary; a missing time stays distinct from midnight.
     */
    fun normalizeStartTime(time: LocalTime?): LocalTime? = time?.truncatedTo(ChronoUnit.MINUTES)

    fun validatePerceivedEffort(value: Int?): Validated<PerceivedEffort?> =
        if (value == null) {
            Validated.Valid(null)
        } else {
            PerceivedEffort.ofOrNull(value)
                ?.let { Validated.Valid(it) }
                ?: Validated.Invalid(EFFORT_ERROR)
        }

    /** Never blocks a save: the field caps its own length, and a blank note is no note. */
    fun normalizeNotes(raw: String?): String? = raw
        ?.trim()
        ?.take(ActivitySession.MAX_NOTES_LENGTH)
        ?.takeIf { it.isNotEmpty() }

    /** PRD FR-ACTIVITY-008: the only path that produces an `other` movement, 1 to 60 characters. */
    fun validateCustomMovementName(raw: String): Validated<String> {
        val trimmed = raw.trim()
        val tooLong = trimmed.length > ActivitySession.MAX_CUSTOM_MOVEMENT_NAME_LENGTH
        return if (trimmed.isEmpty() || tooLong) {
            Validated.Invalid(MOVEMENT_NAME_ERROR)
        } else {
            Validated.Valid(trimmed)
        }
    }

    /** PRD 8.4: required and capped at 40 characters, and only on an `other` equipment. */
    fun validateCustomEquipmentName(raw: String): Validated<String> {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || trimmed.length > MAX_EQUIPMENT_NAME_LENGTH) {
            Validated.Invalid(EQUIPMENT_NAME_ERROR)
        } else {
            Validated.Valid(trimmed)
        }
    }

    /** PRD 9.2: a name is required, and one already in the catalogue reuses its definition. */
    fun validateExerciseName(raw: String): Validated<String> {
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || trimmed.length > ExerciseDefinition.MAX_NAME_LENGTH) {
            Validated.Invalid(EXERCISE_NAME_ERROR)
        } else {
            Validated.Valid(trimmed)
        }
    }

    /**
     * A measurement typed in its display unit, returned in the canonical unit of PRD 8.3.
     * A pace is typed as minutes and seconds and goes through [validatePace]; every other kind
     * is a plain decimal.
     */
    fun validateMetric(kind: MetricKind, raw: String): Validated<Int?> {
        if (raw.isBlank()) return Validated.Valid(null)
        if (kind == MetricKind.AVERAGE_PACE) return validatePace(raw)
        val displayValue = parseDecimal(raw) ?: return Validated.Invalid(NUMBER_ERROR)
        val canonical = kind.toCanonicalOrNull(displayValue)
            ?: return Validated.Invalid(NUMBER_ERROR)
        return Validated.Valid(canonical)
    }

    /**
     * PRD FR-ACTIVITY-007: a pace reads `7:10 /km` and is stored in seconds per kilometre.
     * A bare number is read as whole minutes, which is all a lone `7` can mean.
     */
    fun validatePace(raw: String): Validated<Int?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validated.Valid(null)
        val parts = trimmed.split(':')
        if (parts.size > 2) return Validated.Invalid(PACE_ERROR)
        val minutes = parts[0]
        val seconds = parts.getOrNull(1).orEmpty()
        return validatePace(minutes, seconds)
    }

    /** The two-field form of [validatePace]; both parts blank means no pace at all. */
    fun validatePace(minutes: String, seconds: String): Validated<Int?> {
        if (minutes.isBlank() && seconds.isBlank()) return Validated.Valid(null)
        val typedMinutes = minutes.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(PACE_ERROR)
        val typedSeconds = seconds.trim().ifEmpty { "0" }.toIntOrNull()
            ?: return Validated.Invalid(PACE_ERROR)
        if (typedMinutes < 0 || typedSeconds !in 0 until ActivityDuration.SECONDS_PER_MINUTE) {
            return Validated.Invalid(PACE_ERROR)
        }
        val total = typedMinutes * ActivityDuration.SECONDS_PER_MINUTE + typedSeconds
        return if (total in 1..MAX_PACE_SECONDS) {
            Validated.Valid(total)
        } else {
            Validated.Invalid(PACE_ERROR)
        }
    }

    /** PRD 9.4 and 12: kilograms in, at most two decimals, grams out; blank means no load. */
    fun validateLoad(raw: String): Validated<Load?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val kilograms = parseDecimal(raw) ?: return Validated.Invalid(LOAD_ERROR)
        return Load.ofKilogramsOrNull(kilograms)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(LOAD_ERROR)
    }

    /** PRD 9.4: strictly positive when present, and never stored as a zero. */
    fun validateRepetitions(raw: String): Validated<Int?> {
        if (raw.isBlank()) return Validated.Valid(null)
        val value = parseInteger(raw) ?: return Validated.Invalid(REPETITIONS_ERROR)
        return if (value in StrengthSet.REPETITIONS_RANGE) {
            Validated.Valid(value)
        } else {
            Validated.Invalid(REPETITIONS_ERROR)
        }
    }

    /**
     * The duration of one set (PRD 9.4), typed either as whole seconds or as minutes and
     * seconds; both are accepted because PRD 11.4 reads the value back in the second form.
     */
    fun validateSetDuration(raw: String): Validated<ActivityDuration?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validated.Valid(null)
        val seconds = parseClockSeconds(trimmed) ?: return Validated.Invalid(SET_DURATION_ERROR)
        return ActivityDuration.ofSecondsOrNull(seconds)
            ?.takeIf { it.seconds >= 1 }
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(SET_DURATION_ERROR)
    }

    /**
     * PRD FR-ACTIVITY-008: the same equipment, known or custom and whatever its case, is never
     * added twice to one session. The first occurrence wins and the positions are renumbered.
     */
    fun distinctEquipment(equipment: List<SessionEquipment>): List<SessionEquipment> = equipment
        .distinctBy { it.equipmentType to it.customNameFolded }
        .mapIndexed { index, item -> item.copy(position = index) }

    /** PRD 8.2 and 8.4: a free name belongs to the `other` type alone, and is required there. */
    fun isNamingConsistent(equipment: SessionEquipment): Boolean =
        if (equipment.equipmentType == EquipmentType.OTHER) {
            equipment.customNameFolded.isNotEmpty()
        } else {
            equipment.customName == null
        }

    private fun parseClockSeconds(trimmed: String): Int? {
        val parts = trimmed.split(':')
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()
            2 -> {
                val minutes = parts[0].trim().ifEmpty { "0" }.toIntOrNull()
                val seconds = parts[1].trim().ifEmpty { "0" }.toIntOrNull()
                if (minutes == null || seconds == null ||
                    seconds !in 0 until ActivityDuration.SECONDS_PER_MINUTE
                ) {
                    null
                } else {
                    minutes * ActivityDuration.SECONDS_PER_MINUTE + seconds
                }
            }
            else -> null
        }
    }
}
