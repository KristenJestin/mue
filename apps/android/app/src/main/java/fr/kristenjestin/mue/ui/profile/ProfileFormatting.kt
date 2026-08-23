package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.text.intl.Locale as ComposeLocale

/**
 * Everything the Profile screen shows follows the phone's language, decimal comma included
 * (PRD BR-010). The CSV does not, and is built by `CsvExport` instead — no formatter here is
 * ever involved in an export.
 */

/** The phone's primary locale, as a `java.time` / `java.text` locale. */
@Composable
internal fun rememberProfileLocale(): Locale {
    val tag = ComposeLocale.current.toLanguageTag()
    return remember(tag) { Locale.forLanguageTag(tag) }
}

/** One decimal, exactly as PRD FR-BMI-001 requires, in the phone's numbering. */
internal fun formatBmiValue(value: Double, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        isGroupingUsed = false
    }.format(value)

internal fun formatBirthDate(date: LocalDate, locale: Locale): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))

internal fun formatAge(years: Int): String = if (years == 1) "1 year" else "$years years"
