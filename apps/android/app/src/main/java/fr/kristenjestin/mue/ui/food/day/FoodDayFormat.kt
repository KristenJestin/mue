package fr.kristenjestin.mue.ui.food.day

import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.Nutrients
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * How the `Day` screen words what [FoodLabels] has already rendered (PRD_FOOD 13.2 and 18).
 *
 * Nothing here formats a number. Every energy, every macronutrient and every quantity arrives
 * from [FoodLabels], which is the one place PRD_FOOD 13.1's rule lives: an unknown value is
 * `—` and never `0`. This object only decides which of those strings sit beside which, and
 * what a screen reader hears instead of the glyphs.
 *
 * That last part is the reason [spoken] exists. `—` is a *drawing*: TalkBack reads it as "dash"
 * or as nothing at all, and PRD_FOOD 18 asks for values to be announced "avec leur unité et la
 * mention d'approximation". So the eye gets `—` and the ear gets [UNKNOWN_SPOKEN]; the two are
 * the same fact, and neither of them is a zero.
 *
 * The labels are English and the dates follow the phone's language (PRD 12), so every entry
 * point takes an explicit [Locale] defaulting to the platform one — which is also what makes
 * these rules testable without an emulator.
 */
object FoodDayFormat {

    const val TODAY: String = "Today"
    const val YESTERDAY: String = "Yesterday"

    /** What TalkBack says where the screen draws [FoodLabels.UNKNOWN]. */
    const val UNKNOWN_SPOKEN: String = "unknown"

    /** The noun that follows a protein figure, on a line and on a moment's total alike. */
    const val PROTEIN_NOUN: String = "protein"

    /** Between two facts of one line, as on the Activity cards. */
    const val SEPARATOR: String = ", "

    /** How many days back a day is still named by its weekday rather than by its date. */
    private const val WEEKDAY_HORIZON_DAYS = 7L

    /**
     * The label between the two arrows (PRD_FOOD 10.1).
     *
     * `Today`, `Yesterday`, then the weekday for the rest of the week, then the date itself.
     * Within seven days there is exactly one Monday, so the weekday names a day without
     * ambiguity and keeps the row short — which matters, because the two arrows beside it are
     * fixed at the 48 dp of PRD_FOOD 18 and the row still has to hold at a doubled font scale.
     */
    fun dayLabel(
        date: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String = when {
        date == today -> TODAY
        date == today.minusDays(1) -> YESTERDAY
        date.isAfter(today.minusDays(WEEKDAY_HORIZON_DAYS)) && date.isBefore(today) ->
            date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)

        else -> date(date, locale)
    }

    /**
     * The same day, spelled out in full for a screen reader.
     *
     * `Today` alone tells someone who cannot see the screen nothing about which day they are
     * about to add a meal to, so the announcement keeps the word *and* the date behind it.
     */
    fun dayDescription(
        date: LocalDate,
        today: LocalDate,
        locale: Locale = Locale.getDefault(),
    ): String {
        val full = fullDate(date, locale)
        val label = dayLabel(date, today, locale)
        /*
         * Only the two words that name a day without dating it need the date behind them. A
         * weekday and a date are both already inside the full form, so anything else would be
         * announced twice.
         */
        return if (label == TODAY || label == YESTERDAY) "$label$SEPARATOR$full" else full
    }

    /** Localised medium date, e.g. `Aug 24, 2026`. */
    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    /** Localised full date, e.g. `Monday, August 24, 2026`, for what is heard rather than read. */
    fun fullDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(date)

    /** PRD_FOOD 10.3 stores a local time; the phone's own clock convention shows it. */
    fun time(time: LocalTime, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

    /**
     * The energy of a line or of a moment — `≈ 369 kcal`, or `—` when it is not known.
     *
     * [FoodLabels.energy] draws the distinction; nothing here may collapse it.
     */
    fun energy(nutrients: Nutrients): String = FoodLabels.energy(nutrients.energy)

    /**
     * The protein of a line or of a moment, with its noun — `≈ 29.1 g protein`, or
     * `— protein`.
     *
     * PRD_FOOD 22 asks that an unknown energy leave the *other* metrics of its moment known, so
     * the moment's total shows a second metric beside the energy: with energy alone on screen
     * that criterion could not be observed at all.
     */
    fun protein(nutrients: Nutrients): String =
        "${FoodLabels.macro(nutrients.protein)} $PROTEIN_NOUN"

    /**
     * What a rendered value sounds like.
     *
     * Two glyphs cannot be spoken as they are drawn. [FoodLabels.UNKNOWN] is a dash, which
     * TalkBack reads as "dash" or skips outright — and skipping it would make an unknown value
     * indistinguishable from an absent one, which is the very confusion PRD_FOOD 13.1 exists to
     * prevent. [FoodLabels.APPROXIMATE_PREFIX] is the `≈` PRD_FOOD 18 requires to be announced:
     * "les totaux et valeurs nutritionnelles sont annoncés avec leur unité et la mention
     * d'approximation".
     *
     * Nothing else is touched, and no value is invented: `≈ 0 kcal` still says zero, `—` still
     * says unknown, and the two remain as far apart in the ear as they are on the screen.
     */
    fun spoken(label: String): String = label
        .replace(FoodLabels.UNKNOWN, UNKNOWN_SPOKEN)
        .replace(FoodLabels.APPROXIMATE_PREFIX, APPROXIMATE_SPOKEN)
        .trim()

    /** The word `≈` stands for when it is heard rather than read (PRD_FOOD 18). */
    const val APPROXIMATE_SPOKEN: String = "about "

    /** Joins the facts of one announcement, dropping the ones a line does not carry. */
    fun sentence(vararg parts: String?): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(SEPARATOR)
}
