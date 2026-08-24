package fr.kristenjestin.mue.domain.model

import kotlin.math.roundToLong

/**
 * A span of time, stored as whole seconds (PRD 8.2).
 *
 * A session is typed as hours and minutes, so its value is always a multiple of sixty; the
 * second stays the stored unit for the Health Connect translation PRD 16.6 anticipates. A
 * strength set uses the same class with far smaller values, so the class itself only refuses
 * negatives and the session bounds are applied where a session is validated.
 */
@JvmInline
value class ActivityDuration private constructor(val seconds: Int) : Comparable<ActivityDuration> {

    /** Hours of the `h`/`min` pair the forms use (PRD FR-ACTIVITY-005). */
    val hoursPart: Int get() = seconds / SECONDS_PER_HOUR

    val minutesPart: Int get() = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE

    /** Minutes of the `m:ss` pair a set duration reads as (PRD 11.4). */
    val totalMinutes: Int get() = seconds / SECONDS_PER_MINUTE

    val secondsPart: Int get() = seconds % SECONDS_PER_MINUTE

    val isSessionLength: Boolean get() = seconds in SESSION_RANGE

    operator fun plus(other: ActivityDuration): ActivityDuration =
        ActivityDuration(seconds + other.seconds)

    override fun compareTo(other: ActivityDuration): Int = seconds.compareTo(other.seconds)

    companion object {
        const val SECONDS_PER_MINUTE: Int = 60
        const val SECONDS_PER_HOUR: Int = 3_600

        /** One minute, the shortest session PRD FR-ACTIVITY-005 accepts. */
        const val SESSION_MIN_SECONDS: Int = 60

        /** 99 h 59 m, the longest session PRD FR-ACTIVITY-005 accepts. */
        const val SESSION_MAX_SECONDS: Int = 359_940

        val SESSION_RANGE: IntRange = SESSION_MIN_SECONDS..SESSION_MAX_SECONDS

        val ZERO: ActivityDuration = ActivityDuration(0)

        /** Null below zero. Zero is allowed: an empty day of the weekly bars is a real total. */
        fun ofSecondsOrNull(seconds: Int): ActivityDuration? =
            if (seconds >= 0) ActivityDuration(seconds) else null

        /** Counted in `Long` because both parts come from text fields and may be enormous. */
        fun ofHoursAndMinutesOrNull(hours: Int, minutes: Int): ActivityDuration? {
            if (hours < 0 || minutes < 0) return null
            val total = hours.toLong() * SECONDS_PER_HOUR + minutes.toLong() * SECONDS_PER_MINUTE
            return if (total > Int.MAX_VALUE) null else ActivityDuration(total.toInt())
        }

        /** The session bounds of PRD FR-ACTIVITY-005, applied to an hours-and-minutes pair. */
        fun ofSessionOrNull(hours: Int, minutes: Int): ActivityDuration? =
            ofHoursAndMinutesOrNull(hours, minutes)?.takeIf { it.isSessionLength }

        fun sum(durations: Iterable<ActivityDuration>): ActivityDuration =
            durations.fold(ZERO, ActivityDuration::plus)
    }
}

/**
 * A lifted weight, stored as whole grams (PRD 9.4).
 *
 * The gram makes both the 0.5 kg step and the 1.25 kg plate exact, which a float would not.
 * Input carries at most two decimals of a kilogram (PRD 12), so anything finer is rounded to
 * the nearest 10 g rather than refused.
 */
@JvmInline
value class Load private constructor(val grams: Int) : Comparable<Load> {

    val kilograms: Double get() = grams / GRAMS_PER_KILOGRAM.toDouble()

    override fun compareTo(other: Load): Int = grams.compareTo(other.grams)

    companion object {
        const val GRAMS_PER_KILOGRAM: Int = 1_000

        /** Two decimals of a kilogram, and no finer (PRD 12). */
        const val STEP_GRAMS: Int = 10

        /**
         * 1000 kg. PRD 9.4 sets no ceiling, but grams in an `Int` stop at about 2147 kg and a
         * text field will happily offer `1e30`; this bound keeps the arithmetic honest and sits
         * far above anything a person lifts.
         */
        const val MAX_GRAMS: Int = 1_000_000

        /** Strictly positive, per PRD 9.4: an absent load is null, never zero. */
        fun ofGramsOrNull(grams: Int): Load? =
            if (grams in 1..MAX_GRAMS) Load(grams) else null

        /** Rounds to the nearest 10 g first, so `62.567` becomes `62.57 kg` rather than failing. */
        fun ofKilogramsOrNull(kilograms: Double): Load? {
            if (!kilograms.isFinite()) return null
            val steps = (kilograms * GRAMS_PER_KILOGRAM / STEP_GRAMS).roundToLong()
            if (steps !in MIN_STEPS..MAX_STEPS) return null
            return Load(steps.toInt() * STEP_GRAMS)
        }

        private val MIN_STEPS: Long = 1L
        private val MAX_STEPS: Long = (MAX_GRAMS / STEP_GRAMS).toLong()
    }
}

/** The 1-to-10 scale of PRD 8.2, shared by a whole session and by a single set. */
@JvmInline
value class PerceivedEffort private constructor(val value: Int) : Comparable<PerceivedEffort> {

    override fun compareTo(other: PerceivedEffort): Int = value.compareTo(other.value)

    companion object {
        const val MIN: Int = 1
        const val MAX: Int = 10

        val RANGE: IntRange = MIN..MAX

        fun ofOrNull(value: Int): PerceivedEffort? =
            if (value in RANGE) PerceivedEffort(value) else null
    }
}
