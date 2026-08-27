package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything the Entry screen draws. The weight lives here as a [Weight] — hundredths of a
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
    /**
     * Everything a paired scale adds to this screen, and [EntryScaleUiState.ABSENT] when there
     * is none — which is the default, so `Entry` is the base PRD's screen until a scale is
     * paired and the Bluetooth layer is wired (PRD_SCALE 18.1).
     */
    val scale: EntryScaleUiState = EntryScaleUiState.ABSENT,
) {
    val isAtLowerStop: Boolean get() = weight.hundredthsKg <= Weight.MIN_HUNDREDTHS
    val isAtUpperStop: Boolean get() = weight.hundredthsKg >= Weight.MAX_HUNDREDTHS
    val isToday: Boolean get() = date == today
}

/**
 * Display formatting for the Entry screen.
 *
 * PRD BR-010: what the user reads follows the phone's language, so a French phone shows
 * `74,05`. The CSV never comes through here — it has its own, locale-proof writer.
 */
object EntryFormat {

    /** Two decimals (PRD FR-ENTRY-002), phone's decimal separator. */
    fun weight(weight: Weight, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f", weight.kilograms)

    /** What TalkBack reads for the scale and the hero readout. */
    fun spokenWeight(weight: Weight, locale: Locale = Locale.getDefault()): String =
        "${weight(weight, locale)} kilograms"

    fun date(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

    /**
     * The header chip, which is only ever the date itself.
     *
     * It used to answer `Today` on today, and the chip was therefore permanent. A chip that
     * repeats the default state of the screen it sits on is a chip nobody reads, so the screen
     * now drops it entirely while [EntryUiState.isToday] holds and this function has one case
     * left. That is also why the scale could take the slot: a received weigh-in selects today
     * (BR-SCALE-009), so during a live session the date has nothing to say.
     */
    fun headerDate(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}
