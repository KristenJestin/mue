package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.ActivitySummary
import fr.kristenjestin.mue.domain.model.DateWindow
import fr.kristenjestin.mue.domain.model.MetricKind
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Display strings of the Activity dashboard and of the history.
 *
 * The labels are English, the numbers and the dates follow the phone's language (PRD 12), so
 * every entry point takes an explicit [Locale] defaulting to the platform one — which is also
 * what makes these rules testable without an emulator.
 *
 * Nothing here ever turns a missing optional value into a zero (PRD 12 and 13.3): an absent
 * quantity reads as [UNAVAILABLE], and the cards drop the fact altogether rather than print it.
 */
object ActivityFormat {

    /** Shown wherever a quantity has no value at all, as on Progress. */
    const val UNAVAILABLE: String = "—"

    /** PRD 11.3: every energy is an estimation and says so before its digits. */
    const val ENERGY_PREFIX: String = "≈"

    const val TODAY: String = "Today"
    const val YESTERDAY: String = "Yesterday"

    /** PRD FR-ACTIVITY-002 allows at most two secondary facts beside the duration. */
    const val FACT_LIMIT: Int = 2

    /** Between the date and the start time on a card, and between two facts. */
    const val FACT_SEPARATOR: String = " · "

    /** How many days back a session is still named by its weekday rather than by its date. */
    private const val WEEKDAY_HORIZON_DAYS = 7L

    private const val RANGE_DASH = '–'

    /** PRD 12: a distance always shows at least one decimal, `5.0 km` included. */
    private const val MIN_DISTANCE_DECIMALS = 1

    /**
     * `45 min` under an hour, `2h 15m` above it — both spellings come from the PRD itself
     * (FR-ACTIVITY-001 and section 18), and a single rule produces the two.
     */
    fun duration(duration: ActivityDuration, locale: Locale = Locale.getDefault()): String {
        val hours = duration.hoursPart
        val minutes = duration.minutesPart
        return when {
            hours == 0 -> "${integer(minutes, locale)} min"
            minutes == 0 -> "${integer(hours, locale)}h"
            else -> "${integer(hours, locale)}h ${integer(minutes, locale)}m"
        }
    }

    /**
     * PRD 12: metres are stored, kilometres with a decimal are shown.
     *
     * One decimal is a floor rather than a rule, so `5000 m` still reads `5.0 km` while a
     * `2950 m` walk reads `2.95 km` — the card and the editable field agree on the value, and
     * a glance never rounds away a hundredth the form would show.
     */
    fun distance(metres: Int?, locale: Locale = Locale.getDefault()): String {
        if (metres == null) return UNAVAILABLE
        val kind = MetricKind.DISTANCE
        val value = decimal(kind.toDisplayValue(metres), MIN_DISTANCE_DECIMALS, kind.displayDecimals, locale)
        return "$value ${kind.displayUnit}"
    }

    /** PRD 11.3: `≈280 kcal`, and never `0 kcal` standing in for a missing estimation. */
    fun energy(kcal: Int?, locale: Locale = Locale.getDefault()): String =
        if (kcal == null) UNAVAILABLE else "$ENERGY_PREFIX${integer(kcal, locale)} kcal"

    /** PRD 11.2: every valid set of the session, warm-ups included. */
    fun setCount(count: Int?, locale: Locale = Locale.getDefault()): String = when (count) {
        null -> UNAVAILABLE
        1 -> "${integer(count, locale)} set"
        else -> "${integer(count, locale)} sets"
    }

    /** PRD 13.2 shows `0 sessions` rather than hiding the count, so zero is a real reading. */
    fun sessionCount(count: Int, locale: Locale = Locale.getDefault()): String =
        "${integer(count, locale)} ${sessionNoun(count)}"

    /**
     * The noun alone, for the one place the dashboard sets the digits in a larger cut than the
     * word beside them. Splitting [sessionCount] on its space would break the moment a locale
     * grouped its thousands with one.
     */
    fun sessionNoun(count: Int): String = if (count == 1) "session" else "sessions"

    /**
     * The secondary facts of a card (PRD FR-ACTIVITY-002): distance, set count and estimated
     * energy, in that order, and at most [FACT_LIMIT] of them. A fact the session does not
     * carry is left out entirely rather than shown as a dash — the card has no room to say
     * what a session is *not*.
     */
    fun facts(summary: ActivitySummary, locale: Locale = Locale.getDefault()): List<String> =
        listOfNotNull(
            summary.distanceMetres?.let { distance(it, locale) },
            summary.validSetCount?.let { setCount(it, locale) },
            summary.estimatedEnergyKcal?.let { energy(it, locale) },
        ).take(FACT_LIMIT)

    /**
     * The header range of PRD FR-ACTIVITY-001, e.g. `Aug 17–23`.
     *
     * The PRD's own example reads `Aug 18–24`, which is a Tuesday-to-Monday span in 2026: it
     * illustrates the wording, not the boundaries. The week itself always runs Monday to Sunday
     * and is computed by `WeeklyActivitySummary`, never here.
     *
     * Which side of the range carries the month follows the phone's language: English writes
     * the month first, French the day, so the shared part is factored out of whichever end the
     * locale puts it on.
     */
    fun weekRange(window: DateWindow, locale: Locale = Locale.getDefault()): String {
        val start = window.start ?: return UNAVAILABLE
        val end = window.endInclusive ?: return UNAVAILABLE
        val days = dayOnlyFormatter(locale)

        return when {
            start.year != end.year ->
                "${date(start, locale)} $RANGE_DASH ${date(end, locale)}"

            start.month != end.month ->
                "${monthDay(start, locale)} $RANGE_DASH ${monthDay(end, locale)}"

            dayComesFirst(locale) ->
                "${days.format(start)}$RANGE_DASH${days.format(end)} " +
                    monthOnlyFormatter(locale).format(start)

            else ->
                "${monthOnlyFormatter(locale).format(start)} " +
                    "${days.format(start)}$RANGE_DASH${days.format(end)}"
        }
    }

    /**
     * How a card names its day: `Today`, `Yesterday`, then the weekday for the rest of the
     * week — the prototype's `Thu` — then the date itself, with the year once it is not this
     * one. The history groups by month, so a bare `Aug 12` never loses its context there.
     */
    fun dayLabel(
        date: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = when {
        date == today -> TODAY
        date == today.minusDays(1) -> YESTERDAY
        date.isAfter(today.minusDays(WEEKDAY_HORIZON_DAYS)) && date.isBefore(today) ->
            date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
        date.year == today.year -> monthDay(date, locale)
        else -> date(date, locale)
    }

    /** The day of a card, followed by its start time when the session carries one. */
    fun dayAndTime(
        date: LocalDate,
        time: LocalTime?,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        val day = dayLabel(date, today, locale)
        return if (time == null) day else "$day$FACT_SEPARATOR${time(time, locale)}"
    }

    /** Minute precision, in the phone's own clock convention (PRD 16.3 stores `HH:mm`). */
    fun time(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

    /** A history heading, e.g. `August 2026` (PRD FR-ACTIVITY-012). */
    fun monthTitle(month: YearMonth, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(MONTH_TITLE_PATTERN, locale).format(month)

    /** Full localised date, e.g. `Aug 17, 2026`. */
    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /** The single letter under a weekly bar: `M T W T F S S`, `L M M J V S D` in French. */
    fun dayInitial(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
        day.getDisplayName(TextStyle.NARROW, locale)

    /**
     * What a weekly bar says out loud. The current day is named as such rather than left to
     * the accent colour alone (PRD 15).
     */
    fun dayDescription(
        day: DayOfWeek,
        dayDuration: ActivityDuration,
        isToday: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val name = day.getDisplayName(TextStyle.FULL, locale)
        val head = if (isToday) "$name, $TODAY" else name
        val value = if (dayDuration.seconds == 0) NO_ACTIVITY else duration(dayDuration, locale)
        return "$head, $value"
    }

    private const val NO_ACTIVITY = "no activity"

    /** Standalone month plus year: `August 2026`, `août 2026`. */
    private const val MONTH_TITLE_PATTERN = "LLLL y"

    private fun monthDay(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern(monthDayPattern(locale), locale).format(date)

    private fun dayOnlyFormatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern("d", locale)

    private fun monthOnlyFormatter(locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM", locale)

    private fun dayComesFirst(locale: Locale): Boolean =
        monthDayPattern(locale).trimStart().startsWith('d')

    /**
     * The locale's medium date pattern with its year taken out, so `MMM d, y` becomes `MMM d`
     * and `d MMM y` becomes `d MMM`.
     *
     * There is no JDK call for a month-and-day skeleton, and hardcoding `MMM d` would print
     * `août 18` on a French phone. Anything left blank falls back to the English order rather
     * than to an empty pattern, which would throw at format time.
     */
    private fun monthDayPattern(locale: Locale): String {
        val medium = DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            FormatStyle.MEDIUM,
            null,
            IsoChronology.INSTANCE,
            locale,
        )
        val withoutYear = YEAR_FIELD.replace(medium, "").trim { it.isWhitespace() || it in ",./-" }
        return withoutYear.ifEmpty { "MMM d" }
    }

    private val YEAR_FIELD = Regex("[yu]+")

    private fun integer(value: Int, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    private fun decimal(value: Double, minDecimals: Int, maxDecimals: Int, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = minDecimals
            maximumFractionDigits = maxDecimals
        }.format(value)
}
