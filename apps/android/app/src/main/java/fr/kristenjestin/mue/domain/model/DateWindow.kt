package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * A closed calendar window. A null bound means "unbounded on that side", which is
 * what [Period.ALL] needs; using sentinel dates instead would leak into the SQL
 * string comparison the storage layer relies on.
 */
data class DateWindow(
    val start: LocalDate?,
    val endInclusive: LocalDate?,
) {
    operator fun contains(date: LocalDate): Boolean =
        (start == null || !date.isBefore(start)) &&
            (endInclusive == null || !date.isAfter(endInclusive))

    companion object {
        val UNBOUNDED: DateWindow = DateWindow(null, null)

        fun of(start: LocalDate, endInclusive: LocalDate): DateWindow =
            DateWindow(start, endInclusive)
    }
}
