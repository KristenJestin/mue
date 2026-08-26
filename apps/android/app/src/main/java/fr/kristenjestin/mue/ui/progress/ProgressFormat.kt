package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.Weight
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Display strings of the Progress screen.
 *
 * Numbers and dates follow the phone's language (PRD BR-010), so every entry point takes
 * an explicit [Locale] defaulting to the platform one — that is also what makes these
 * rules testable without an emulator.
 */
object ProgressFormat {

    /** Shown wherever an indicator has no value for the period (PRD FR-PROGRESS-003). */
    const val UNAVAILABLE: String = "—"

    private const val MINUS = '−'

    /** A weight carries two decimals; a value derived from weights carries one. */
    private const val WEIGHT_DECIMALS = 2
    private const val DERIVED_DECIMALS = 1

    /** Resting energy carries none at all (PRD_SCALE FR-BODY-003). */
    private const val ENERGY_DECIMALS = 0

    /** Two decimals, no unit: `74.05` in English, `74,05` in French (PRD FR-PROGRESS-003). */
    fun weight(weight: Weight?, locale: Locale = Locale.getDefault()): String =
        weight?.let { decimal(it.kilograms, WEIGHT_DECIMALS, locale) } ?: UNAVAILABLE

    /** One decimal, as PRD FR-BMI-001 requires: the BMI is derived, not a weight. */
    fun bmi(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.let { decimal(it, DERIVED_DECIMALS, locale) } ?: UNAVAILABLE

    /**
     * The change over the period: a difference between two weights, so two decimals and an
     * always-visible sign (PRD FR-PROGRESS-003), e.g. `−0.35`.
     */
    fun signedWeight(value: Double?, locale: Locale = Locale.getDefault()): String =
        signed(value, WEIGHT_DECIMALS, locale)

    /**
     * The weekly pace: a derived value, so one decimal and an always-visible sign
     * (PRD FR-PROGRESS-003), e.g. `−0.3`.
     */
    fun signedPace(value: Double?, locale: Locale = Locale.getDefault()): String =
        signed(value, DERIVED_DECIMALS, locale)

    /** The chart-card badge of the prototype, e.g. `−1.15 kg`. */
    fun signedKilograms(value: Double?, locale: Locale = Locale.getDefault()): String =
        if (value == null) UNAVAILABLE else "${signedWeight(value, locale)} kg"

    /**
     * A body-composition percentage or mass: one decimal (PRD_SCALE FR-BODY-003).
     *
     * The same shape as [bmi] and for the same reason — a figure derived from a weight is not a
     * weight — but named after what it is, because FR-BODY-003 states the rule in its own words
     * and a reader chasing that rule should not have to notice that the BMI already carried it.
     */
    fun estimate(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.let { decimal(it, DERIVED_DECIMALS, locale) } ?: UNAVAILABLE

    /**
     * The change against the previous estimate: one decimal and an always-visible sign
     * (PRD_SCALE FR-BODY-003, the only perspective that requirement allows).
     */
    fun signedEstimate(value: Double?, locale: Locale = Locale.getDefault()): String =
        signed(value, DERIVED_DECIMALS, locale)

    /**
     * Resting energy: a whole number of kilocalories (PRD_SCALE FR-BODY-003).
     *
     * No decimal at all, deliberately. PRD_SCALE 13.3 forbids showing digits that would suggest a
     * clinical precision, and a tenth of a kilocalorie on a value whose equation carries an error
     * of a hundred would be exactly that.
     */
    fun energy(value: Int?, locale: Locale = Locale.getDefault()): String =
        value?.let { decimal(it.toDouble(), ENERGY_DECIMALS, locale) } ?: UNAVAILABLE

    /** The change in resting energy: a whole number, with its sign. */
    fun signedEnergy(value: Int?, locale: Locale = Locale.getDefault()): String =
        signed(value?.toDouble(), ENERGY_DECIMALS, locale)

    /** Full localised date, e.g. `Aug 18, 2026` or `18 août 2026`. */
    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /** Same as [date], but the current day reads `Today` as in the prototype. */
    fun dateOrToday(
        date: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = if (date == today) TODAY else date(date, locale)

    const val TODAY: String = "Today"

    /** The sign is taken from the *rounded* value so a change of −0.001 kg never reads `−0.00`. */
    private fun signed(value: Double?, decimals: Int, locale: Locale): String {
        if (value == null || !value.isFinite()) return UNAVAILABLE
        val scale = TEN.pow(decimals)
        val rounded = (value * scale).roundToLong() / scale
        val sign = if (rounded < 0.0) MINUS else '+'
        return sign + decimal(abs(rounded), decimals, locale)
    }

    private fun decimal(value: Double, decimals: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = false
        }.format(value)

    private const val TEN = 10.0
}
