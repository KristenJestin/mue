package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate

/**
 * Builds the export file's bytes (PRD 9.5).
 *
 * Nothing here consults the default [java.util.Locale]: numbers are assembled digit
 * by digit from the stored tenths and dates come from `LocalDate.toString()`, which
 * is ISO by definition. That is what makes the file byte-identical on a French
 * phone, as PRD FR-CSV-003 requires.
 */
object CsvExport {

    const val HEADER: String = "date,weight_kg"
    const val SEPARATOR: Char = ','
    const val LINE_ENDING: String = "\n"
    const val FILE_NAME_PREFIX: String = "mue-weight-"
    const val FILE_EXTENSION: String = ".csv"

    /** UTF-8 without BOM, LF endings, oldest measurement first. */
    fun buildContent(measurements: List<Measurement>): String = buildString {
        append(HEADER).append(LINE_ENDING)
        measurements.sortedBy { it.date }.forEach { measurement ->
            append(measurement.date.toString())
            append(SEPARATOR)
            append(formatWeight(measurement.weight))
            append(LINE_ENDING)
        }
    }

    /** `toByteArray` with UTF-8 never emits a BOM, which is exactly what PRD FR-CSV-003 asks. */
    fun buildBytes(measurements: List<Measurement>): ByteArray =
        buildContent(measurements).toByteArray(Charsets.UTF_8)

    /** `mue-weight-YYYY-MM-DD.csv`, dated by the export, not by the data. */
    fun fileName(exportDate: LocalDate): String =
        "$FILE_NAME_PREFIX$exportDate$FILE_EXTENSION"

    /** One decimal, always a dot, built from the integer tenths so no formatter is involved. */
    fun formatWeight(weight: Weight): String {
        val tenths = weight.tenthsKg
        return "${tenths / 10}.${tenths % 10}"
    }
}
