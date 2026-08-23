package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate

/**
 * Every user-facing validation rule of the V1, with the exact wording of PRD 15.3.
 *
 * The messages are constants rather than string resources: the app ships in English
 * only, and unit tests assert them character for character.
 */
object MueValidation {

    const val WEIGHT_ERROR: String = "Weight must be between 30.0 and 250.0 kg"
    const val HEIGHT_ERROR: String = "Height must be between 120 and 230 cm"
    const val BIRTH_DATE_ERROR: String = "Enter a valid date of birth"

    /**
     * Rounds to the nearest 0.05 kg, then rejects anything outside 30.0–250.0 kg
     * (PRD FR-ENTRY-004, BR-003). The bounds keep their one-decimal wording in
     * [WEIGHT_ERROR]: they are unchanged, and `30.00` would only look like a new rule.
     */
    fun validateWeightKg(kilograms: Double): Validated<Weight> =
        Weight.ofKilogramsOrNull(kilograms)
            ?.let { Validated.Valid(it) }
            ?: Validated.Invalid(WEIGHT_ERROR)

    /**
     * Parses a hand-typed weight. Both `.` and `,` are accepted whatever the phone's
     * language is (PRD FR-ENTRY-004), which is why this never touches `NumberFormat`.
     */
    fun validateWeightInput(raw: String): Validated<Weight> {
        val normalized = raw.trim().replace(',', '.')
        val kilograms = normalized.toDoubleOrNull()
            ?: return Validated.Invalid(WEIGHT_ERROR)
        return validateWeightKg(kilograms)
    }

    /** A missing height is valid and simply means "no BMI" (PRD FR-PROFILE-001). */
    fun validateHeightCm(centimetres: Int?): Validated<Int?> = when {
        centimetres == null -> Validated.Valid(null)
        centimetres in UserProfile.HEIGHT_RANGE_CM -> Validated.Valid(centimetres)
        else -> Validated.Invalid(HEIGHT_ERROR)
    }

    /** A blank field clears the height; anything unparseable is rejected like an out-of-range value. */
    fun validateHeightInput(raw: String): Validated<Int?> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Validated.Valid(null)
        val centimetres = trimmed.toIntOrNull() ?: return Validated.Invalid(HEIGHT_ERROR)
        return validateHeightCm(centimetres)
    }

    /**
     * A missing birth date is valid; a present one may not be in the future nor more
     * than 120 years back (PRD FR-PROFILE-002).
     */
    fun validateBirthDate(birthDate: LocalDate?, today: LocalDate): Validated<LocalDate?> {
        if (birthDate == null) return Validated.Valid(null)
        val earliest = today.minusYears(UserProfile.MAX_AGE_YEARS)
        return if (birthDate.isAfter(today) || birthDate.isBefore(earliest)) {
            Validated.Invalid(BIRTH_DATE_ERROR)
        } else {
            Validated.Valid(birthDate)
        }
    }

    /** Never blocks a save (PRD FR-PROFILE-006); blank collapses to "no name". */
    fun normalizeDisplayName(raw: String?): String? =
        raw?.trim()?.take(UserProfile.MAX_DISPLAY_NAME_LENGTH)?.takeIf { it.isNotEmpty() }

    /** PRD BR-009: no measurement may carry a date after today. */
    fun isMeasurementDateAllowed(date: LocalDate, today: LocalDate): Boolean = !date.isAfter(today)

    /**
     * Validates a whole profile form at once, reporting each field independently so
     * the screen can highlight both errors together (PRD FR-PROFILE-003).
     */
    fun validateProfile(
        displayName: String?,
        heightInput: String,
        birthDate: LocalDate?,
        today: LocalDate,
    ): ProfileValidation {
        val height = validateHeightInput(heightInput)
        val birth = validateBirthDate(birthDate, today)
        val heightError = height.errorMessage
        val birthError = birth.errorMessage
        return if (heightError == null && birthError == null) {
            ProfileValidation.Valid(
                UserProfile(
                    displayName = normalizeDisplayName(displayName),
                    heightCm = height.valueOrNull,
                    birthDate = birth.valueOrNull,
                )
            )
        } else {
            ProfileValidation.Invalid(heightError, birthError)
        }
    }
}

sealed interface ProfileValidation {
    data class Valid(val profile: UserProfile) : ProfileValidation
    data class Invalid(val heightError: String?, val birthDateError: String?) : ProfileValidation
}
