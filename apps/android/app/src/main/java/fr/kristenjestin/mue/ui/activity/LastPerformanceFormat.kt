package fr.kristenjestin.mue.ui.activity

import fr.kristenjestin.mue.domain.logic.isValid
import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.LastPerformance
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.SetMeasure
import fr.kristenjestin.mue.domain.model.StrengthSet
import fr.kristenjestin.mue.domain.model.TrackingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * The line PRD 11.4 puts under an exercise name: what that exercise last came to.
 *
 * The rendering follows the tracking mode, so the mode travels with the set. Numbers follow the
 * phone's language while the words stay English (PRD 12), which is why every entry point takes
 * an explicit [Locale] — that is also what makes these rules provable without a device.
 *
 * Nothing is returned when the exercise has never been practised, and nothing is returned for a
 * set that does not carry the primary measure of its mode: [isValid] decides that, here as
 * everywhere else, rather than this file restating the rule.
 */
object LastPerformanceFormat {

    /** PRD 11.4 renders `Last time · 60 kg × 8`. */
    const val PREFIX: String = "Last time"

    private const val SEPARATOR = " · "

    /** A multiplication sign, not the letter: `60 kg × 8` is a quantity, not an equation. */
    private const val TIMES = " × "

    private const val KILOGRAMS = "kg"

    /** PRD 12: at most two decimals, and no decimal at all when there is nothing to say. */
    private const val MAX_LOAD_DECIMALS = 2

    fun format(performance: LastPerformance?, locale: Locale = Locale.getDefault()): String? {
        val summary = performance?.let { summary(it.trackingMode, it.set, locale) } ?: return null
        return PREFIX + SEPARATOR + summary
    }

    /** The part after the prefix; null when the set carries nothing worth quoting. */
    fun summary(
        mode: TrackingMode,
        set: StrengthSet,
        locale: Locale = Locale.getDefault(),
    ): String? {
        if (!mode.isValid(set)) return null
        val load = set.load?.takeIf { mode.usesLoad }
        return when (mode.primary) {
            SetMeasure.REPETITIONS -> {
                val reps = set.repetitions ?: return null
                // `60 kg × 8` reads as one quantity, so the unit is said once and the reps are
                // bare; with no load the number has to name itself — `8 reps`.
                if (load == null) {
                    repetitions(reps, locale)
                } else {
                    kilograms(load, locale) + TIMES + integer(reps, locale)
                }
            }

            SetMeasure.DURATION -> {
                val held = clock(set.duration ?: return null, locale)
                // A load and a hold are two facts side by side, not a product: `20 kg · 1:30`.
                if (load == null) held else kilograms(load, locale) + SEPARATOR + held
            }
        }
    }

    /** `60 kg`, `62.5 kg`, `62,5 kg` — the trailing zero of a round load is never shown. */
    fun kilograms(load: Load, locale: Locale = Locale.getDefault()): String =
        "${decimal(load.kilograms, locale)} $KILOGRAMS"

    /**
     * `1:30` above a minute, `45s` below it (PRD 11.4).
     *
     * A bare `0:45` would read as a stopwatch someone forgot to start; under a minute the unit
     * says more than the leading zero does.
     */
    fun clock(duration: ActivityDuration, locale: Locale = Locale.getDefault()): String =
        if (duration.seconds < ActivityDuration.SECONDS_PER_MINUTE) {
            "${integer(duration.seconds, locale)}s"
        } else {
            String.format(locale, "%d:%02d", duration.totalMinutes, duration.secondsPart)
        }

    /** `12 reps`, and `1 rep` — the app is English-only, so the plural is simply right. */
    private fun repetitions(count: Int, locale: Locale): String =
        integer(count, locale) + if (count == 1) " rep" else " reps"

    private fun integer(value: Int, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = false }.format(value)

    private fun decimal(value: Double, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = MAX_LOAD_DECIMALS
            isGroupingUsed = false
        }.format(value)
}
