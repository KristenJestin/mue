package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * The minimal health profile (PRD 11.2). Every field is optional: the app stays
 * fully usable with an entirely empty profile.
 *
 * Age is never stored, only derived from [birthDate] (PRD BR-005), and it exists
 * for exactly one purpose: deciding whether an adult BMI category may be shown.
 */
data class UserProfile(
    val displayName: String? = null,
    val heightCm: Int? = null,
    val birthDate: LocalDate? = null,
) {
    val heightMetres: Double? get() = heightCm?.let { it / 100.0 }

    /** Whole years lived on [today]; null when no birth date is known. */
    fun ageOn(today: LocalDate): Int? =
        birthDate?.let { java.time.Period.between(it, today).years }

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH: Int = 40

        /** PRD FR-PROFILE-001. */
        val HEIGHT_RANGE_CM: IntRange = 120..230

        /** PRD FR-PROFILE-002. */
        const val MAX_AGE_YEARS: Long = 120

        /** PRD FR-BMI-002: below this age the V1 shows the BMI value with no category. */
        const val ADULT_AGE_YEARS: Long = 20

        val EMPTY: UserProfile = UserProfile()
    }
}
