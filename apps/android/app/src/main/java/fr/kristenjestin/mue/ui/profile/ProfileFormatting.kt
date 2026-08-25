package fr.kristenjestin.mue.ui.profile

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Everything the Profile screen shows follows the phone's language, decimal comma included
 * (PRD BR-010). The CSV does not, and is built by `CsvExport` instead — no formatter here is
 * ever involved in an export.
 *
 * The BMI's own formatting travelled to `ui/components` with the card that both screens draw.
 */

internal fun formatBirthDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

internal fun formatAge(years: Int): String = if (years == 1) "1 year" else "$years years"
