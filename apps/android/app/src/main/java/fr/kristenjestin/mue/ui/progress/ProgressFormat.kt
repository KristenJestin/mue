package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.Weight
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
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

    /** One decimal, no unit: `74.5` in English, `74,5` in French. */
    fun weight(weight: Weight?, locale: Locale = Locale.getDefault()): String =
        weight?.let { decimal(it.kilograms, locale) } ?: UNAVAILABLE

    fun bmi(value: Double?, locale: Locale = Locale.getDefault()): String =
        value?.let { decimal(it, locale) } ?: UNAVAILABLE

    /**
     * Change and pace always carry their sign (PRD FR-PROGRESS-003). The sign is taken
     * from the *rounded* value so a change of −0.04 kg never renders as a negative zero.
     */
    fun signed(value: Double?, locale: Locale = Locale.getDefault()): String {
        if (value == null || !value.isFinite()) return UNAVAILABLE
        val rounded = (value * 10.0).roundToLong() / 10.0
        val sign = if (rounded < 0.0) MINUS else '+'
        return sign + decimal(abs(rounded), locale)
    }

    /** The chart-card badge of the prototype, e.g. `−1.1 kg`. */
    fun signedKilograms(value: Double?, locale: Locale = Locale.getDefault()): String =
        if (value == null) UNAVAILABLE else "${signed(value, locale)} kg"

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

    private fun decimal(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
            isGroupingUsed = false
        }.format(value)
}
