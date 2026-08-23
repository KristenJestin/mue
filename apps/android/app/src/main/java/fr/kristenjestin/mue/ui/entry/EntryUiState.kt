package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything the Entry screen draws. The weight lives here as a [Weight] — tenths of a
 * kilogram — and only becomes text at the very edge of the UI (PRD 11.1).
 */
@Immutable
data class EntryUiState(
    val weight: Weight = Weight.DEFAULT,
    /**
     * Counts the writes that did *not* come from the scale — the history seed, the `−` / `+`
     * controls, the keyboard. The scale watches this rather than [weight] so it can tell an
     * order to move from the echo of its own movement, which always trails a frame behind.
     */
    val weightRevision: Int = 0,
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    /** `Hello Kris,` or null when no display name is set — the line then disappears (FR-ENTRY-007). */
    val greeting: String? = null,
    val manualEntry: Boolean = false,
    /** Exactly what the user typed, kept verbatim so an invalid value can be corrected (PRD 15.3). */
    val manualInput: String = "",
    val manualError: String? = null,
    val datePickerVisible: Boolean = false,
    val justSaved: Boolean = false,
    val saveError: String? = null,
    val hapticsEnabled: Boolean = true,
    /** Bumped on every successful save so the centre marker can flare once (PRD 13). */
    val saveFlareCount: Int = 0,
) {
    val isAtLowerStop: Boolean get() = weight.tenthsKg <= Weight.MIN_TENTHS
    val isAtUpperStop: Boolean get() = weight.tenthsKg >= Weight.MAX_TENTHS
    val isToday: Boolean get() = date == today
}

/**
 * Display formatting for the Entry screen.
 *
 * PRD BR-010: what the user reads follows the phone's language, so a French phone shows
 * `74,5`. The CSV never comes through here — it has its own, locale-proof writer.
 */
object EntryFormat {

    /** One decimal, phone's decimal separator. */
    fun weight(weight: Weight, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.1f", weight.kilograms)

    /** What TalkBack reads for the scale and the hero readout. */
    fun spokenWeight(weight: Weight, locale: Locale = Locale.getDefault()): String =
        "${weight(weight, locale)} kilograms"

    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    /** The header chip: `Today` while the measurement is dated today, the day itself otherwise. */
    fun headerDate(date: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String =
        if (date == today) {
            "Today"
        } else {
            date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
}
