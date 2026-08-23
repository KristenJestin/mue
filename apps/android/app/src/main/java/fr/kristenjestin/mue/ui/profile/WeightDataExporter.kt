package fr.kristenjestin.mue.ui.profile

import fr.kristenjestin.mue.domain.model.Measurement
import java.io.File
import java.time.LocalDate

/**
 * Seam between [ProfileViewModel] and `CsvExportWriter`.
 *
 * The writer is a concrete class doing real file I/O; behind this interface the export
 * failure path of PRD 15.4 can be exercised on the JVM without a filesystem.
 */
fun interface WeightDataExporter {

    /** Returns a *complete* file, or throws. A half-written file is never returned. */
    suspend fun export(measurements: List<Measurement>, exportDate: LocalDate): File
}
