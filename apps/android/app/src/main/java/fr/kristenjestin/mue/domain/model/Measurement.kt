package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * A weight, stored as whole tenths of a kilogram.
 *
 * The PRD requires the tenth to be preserved exactly (PRD 11.1). Floating point
 * would drift, so the tenth is the unit of truth everywhere in the app and doubles
 * only ever appear at the display boundary.
 */
@JvmInline
value class Weight private constructor(val tenthsKg: Int) : Comparable<Weight> {

    val kilograms: Double get() = tenthsKg / 10.0

    operator fun minus(other: Weight): Int = tenthsKg - other.tenthsKg

    override fun compareTo(other: Weight): Int = tenthsKg.compareTo(other.tenthsKg)

    companion object {
        /** 30.0 kg, per PRD BR-003. */
        const val MIN_TENTHS: Int = 300

        /** 250.0 kg, per PRD BR-003. */
        const val MAX_TENTHS: Int = 2500

        /** The value Entry starts from when no measurement exists (PRD FR-ENTRY-001). */
        val DEFAULT: Weight = Weight(700)

        val RANGE: IntRange = MIN_TENTHS..MAX_TENTHS

        fun ofTenthsOrNull(tenthsKg: Int): Weight? =
            if (tenthsKg in RANGE) Weight(tenthsKg) else null

        /** Rounds to the nearest tenth, then range-checks. Returns null when out of bounds. */
        fun ofKilogramsOrNull(kilograms: Double): Weight? =
            if (kilograms.isFinite()) ofTenthsOrNull(Math.round(kilograms * 10.0).toInt()) else null

        /** Clamps into the valid range instead of rejecting. Used by the ruler, never by input validation. */
        fun ofTenthsClamped(tenthsKg: Int): Weight =
            Weight(tenthsKg.coerceIn(MIN_TENTHS, MAX_TENTHS))
    }
}

/**
 * One weight recorded for one calendar day.
 *
 * The date is a pure local date with no time and no zone, which makes a
 * timezone-induced off-by-one-day impossible (PRD 11.1).
 */
data class Measurement(
    val date: LocalDate,
    val weight: Weight,
)
