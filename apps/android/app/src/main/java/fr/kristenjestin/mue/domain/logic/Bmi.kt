package fr.kristenjestin.mue.domain.logic

/**
 * The four adult categories of PRD FR-BMI-002. Labels are part of the product copy
 * and the app is English-only, so they live with the category rather than in
 * Android resources.
 */
enum class BmiCategory(val label: String) {
    UNDERWEIGHT("Underweight"),
    HEALTHY_WEIGHT("Healthy weight"),
    OVERWEIGHT("Overweight"),
    OBESITY("Obesity"),
}

/**
 * The outcome of a BMI computation.
 *
 * The three cases are distinct on purpose: PRD FR-BMI-002 hides the named reference
 * bar whenever no category is allowed, so the UI must be able to tell "no BMI"
 * from "a BMI I may not name".
 */
sealed interface Bmi {

    /** Weight or height is missing, so there is nothing to show at all (PRD 15.1, 15.2). */
    data object Unavailable : Bmi

    sealed interface Available : Bmi {
        /** Rounded to one decimal, exactly as displayed (PRD FR-BMI-001). */
        val value: Double
    }

    /** A value with no category: no birth date, or the user is under 20 (PRD 15.2). */
    data class ValueOnly(override val value: Double) : Available

    /** A value the user's age allows us to name, so the reference bar may be shown. */
    data class Classified(override val value: Double, val category: BmiCategory) : Available
}

val Bmi.valueOrNull: Double? get() = (this as? Bmi.Available)?.value

val Bmi.categoryOrNull: BmiCategory? get() = (this as? Bmi.Classified)?.category
