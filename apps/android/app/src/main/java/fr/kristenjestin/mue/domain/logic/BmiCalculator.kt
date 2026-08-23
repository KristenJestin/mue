package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * BMI, its rounding and its category gate (PRD 9.4).
 *
 * The BMI is never stored (PRD BR-005): every caller recomputes it from the latest
 * weight and the current profile.
 */
object BmiCalculator {

    /** Shown next to every BMI so the number is never read as a diagnosis (PRD FR-BMI-002). */
    const val DISCLAIMER: String =
        "BMI is a general screening indicator, not a diagnosis."

    fun calculate(weight: Weight?, profile: UserProfile, today: LocalDate): Bmi =
        calculate(weight, profile.heightCm, profile.birthDate, today)

    fun calculate(
        weight: Weight?,
        heightCm: Int?,
        birthDate: LocalDate?,
        today: LocalDate,
    ): Bmi {
        if (weight == null) return Bmi.Unavailable
        if (heightCm == null || heightCm !in UserProfile.HEIGHT_RANGE_CM) return Bmi.Unavailable

        val heightMetres = heightCm / 100.0
        val value = roundToOneDecimal(weight.kilograms / (heightMetres * heightMetres))

        return if (isAdultOn(birthDate, today)) {
            Bmi.Classified(value, categoryOf(value))
        } else {
            Bmi.ValueOnly(value)
        }
    }

    /**
     * Classification runs on the displayed value, not the raw one. The PRD's bands
     * leave gaps between 24.9 and 25.0 and between 29.9 and 30.0; rounding first
     * closes them the same way the screen does.
     */
    fun categoryOf(roundedValue: Double): BmiCategory = when {
        roundedValue < 18.5 -> BmiCategory.UNDERWEIGHT
        roundedValue < 25.0 -> BmiCategory.HEALTHY_WEIGHT
        roundedValue < 30.0 -> BmiCategory.OVERWEIGHT
        else -> BmiCategory.OBESITY
    }

    /**
     * A category is only allowed once the birth date *proves* the user is 20 or
     * older; an unknown birth date is never treated as adult (PRD FR-BMI-002).
     */
    fun isAdultOn(birthDate: LocalDate?, today: LocalDate): Boolean {
        if (birthDate == null) return false
        return !today.isBefore(birthDate.plusYears(UserProfile.ADULT_AGE_YEARS))
    }

    private fun roundToOneDecimal(raw: Double): Double =
        if (raw.isFinite()) (raw * 10.0).roundToLong() / 10.0 else raw
}
